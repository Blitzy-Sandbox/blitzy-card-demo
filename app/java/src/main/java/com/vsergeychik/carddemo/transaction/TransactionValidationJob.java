package com.vsergeychik.carddemo.transaction;

import com.vsergeychik.carddemo.account.AccountRepository;
import com.vsergeychik.carddemo.account.model.AccountRecord;
import com.vsergeychik.carddemo.card.CardXrefRepository;
import com.vsergeychik.carddemo.card.model.CardXrefRecord;
import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.config.BatchConfig;
import com.vsergeychik.carddemo.config.BatchConfig.StepContract;
import com.vsergeychik.carddemo.transaction.model.DalyTranRecord;
import com.vsergeychik.carddemo.transaction.model.TranCatBalRecord;
import com.vsergeychik.carddemo.transaction.model.TranRecord;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.step.builder.SimpleStepBuilder;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@code CBTRN02C} - the daily-transaction poster of {@code app/jcl/POSTTRAN.jcl}, translated to a
 * chunk-oriented Spring Batch job.
 *
 * <h2>The name says "validation"; the program validates <em>and</em> posts</h2>
 *
 * <p>The class name is mandated by the build prompt and is used verbatim under AAP rule <strong>R1</strong>
 * - a prompt-mandated name never authorises adding, removing or altering logic. The verified source header
 * reads <em>"Function    : Post the records from daily transaction file."</em>
 * ({@code app/cbl/CBTRN02C.cbl:5}), and this is the program {@code app/jcl/POSTTRAN.jcl:23} actually
 * invokes, so the behaviour here is the source's: each of the 300 daily transactions is validated through
 * the cascade of {@code 1500-VALIDATE-TRAN} and then <strong>either posted or rejected</strong>. The
 * divergence is recorded rather than resolved by renaming (practice <strong>B4</strong> - document, do not
 * silently fix); AAP 0.8.4 carries the full register.
 *
 * <h2>Governing rules</h2>
 *
 * <p><strong>{@code review_rules} returns exactly one line - "No user rules provided." That single line is
 * the whole rules document, so <em>no</em> user-specified rule governs this file.</strong> Their absence is
 * not licence to lower the bar: AAP 0.10.2's enterprise-practice substitutes bind instead, and each is
 * honoured here concretely.
 *
 * <ul>
 *   <li><strong>B1 / B2 - constraint fidelity.</strong> Spring Batch 5.2.6 and Spring Boot 3.5.16 APIs
 *       only, and no dependency outside the closed set. The {@code Job} and {@code Step} are built through
 *       {@link BatchConfig}'s {@code JobBuilder} / {@code StepBuilder} seams and its single
 *       {@code PlatformTransactionManager}. This class declares no {@code @EnableBatchProcessing}, no
 *       {@code JobRepository}, no {@code JobLauncher} and no transaction manager - Boot auto-configures the
 *       first and {@link BatchConfig} owns the rest, and duplicating either would break gate
 *       <strong>G3</strong>.</li>
 *   <li><strong>B3 - reference inputs immutable.</strong> Nothing under {@code app/cbl}, {@code app/cpy},
 *       {@code app/jcl} or {@code app/data} is written; they are the parity oracle (gate
 *       <strong>G5</strong>).</li>
 *   <li><strong>B4 - no silent scope creep.</strong> The name-versus-behaviour divergence is stated in the
 *       heading above rather than resolved by renaming, and the three source defects below are recorded at
 *       their sites rather than corrected.</li>
 *   <li><strong>B5 - behaviour is preserved including its defects.</strong> Three are reproduced
 *       deliberately and must not be tidied; each is commented at its site. They are listed under
 *       <em>Preserved defects</em> below.</li>
 *   <li><strong>B6 - the security posture is neither weakened nor unrequestedly strengthened.</strong>
 *       {@code CBTRN02C} is a batch poster and authenticates nothing, so this file holds no credential, no
 *       comparison against one and no filter chain. It reads and rewrites the account master through the
 *       injected repository and nothing else, so no access path is widened either.</li>
 *   <li><strong>B7 - deterministic and non-interactive.</strong> Nothing here reads a console, blocks on
 *       input or consults a wall clock: the one time source is the injected {@link Clock}, which is what
 *       makes {@code TRAN-PROC-TS} reproducible for a parity case, and {@code SYSOUT} is an injectable
 *       {@link SysoutSink} rather than a hard-wired stream. The whole program runs from a single
 *       {@code mvn -B clean verify}.</li>
 *   <li><strong>B8 - explicit over implicit.</strong> Every monetary value is a {@link BigDecimal} at scale
 *       exactly {@value CobolDecimal#MONETARY_SCALE} stored through {@link CobolDecimal} with
 *       {@code RoundingMode.DOWN} (gates <strong>G23</strong>, <strong>G24</strong>); the keyword
 *       {@code ROUNDED} appears zero times in all 28 programs, so {@code HALF_UP}, {@code HALF_EVEN},
 *       {@code CEILING} and {@code FLOOR} appear nowhere in this file. No {@code double} and no
 *       {@code float} (gate <strong>G22</strong>). No dataset literal: every dataset is reached only through
 *       an injected repository, whose name comes from {@code application.yml} (gate <strong>G46</strong>).
 *       No wildcard import (gate <strong>G52</strong>). The code page is always the injected dataset
 *       charset, never the platform default.</li>
 *   <li><strong>B9 - no static mutable state</strong> (gate <strong>G53</strong>). The COBOL
 *       {@code WORKING-STORAGE} of {@code app/cbl/CBTRN02C.cbl:99-190} - {@code WS-TRANSACTION-COUNT},
 *       {@code WS-REJECT-COUNT}, {@code WS-TEMP-BAL}, {@code WS-VALIDATION-FAIL-REASON}, its description,
 *       {@code WS-CREATE-TRANCAT-REC}, {@code APPL-RESULT}, {@code END-OF-FILE} and the six
 *       {@code FILE STATUS} items - lives in a {@link WorkingStorage} instance owned by one
 *       {@link PostingRun}, built per step execution. Every collaborator is constructor-injected.</li>
 *   <li><strong>B10 / G51 - reachable with no {@code JobLauncher}.</strong> {@link #postTransactions()} is
 *       the whole {@code PROCEDURE DIVISION} as a plain method call, and {@link #newChunkDelegate()} exposes
 *       the reader / processor / writer triple directly, so every branch of the cascade and of the posting
 *       sequence is assertable by a unit test and by the 20 {@code CBTRN02C} parity cases with no framework
 *       in the path. The {@code Job} bean is assembly and nothing else.</li>
 *   <li><strong>B11 - hand-written codecs.</strong> Every record image is produced by the repositories, the
 *       reject writer and the record models through {@link FixedWidthCodec}, with {@code FILLER} emitted
 *       (gates <strong>G19</strong>, <strong>G21</strong>).</li>
 *   <li><strong>B12 - environmental limits documented, not absorbed.</strong> The expected values of the 20
 *       parity cases for this program are <strong>statically derived</strong> from the COBOL paragraphs, the
 *       copybook byte layouts, the JCL DD contracts and the real {@code app/data/ASCII} fixtures - they are
 *       <em>not</em> captured from a COBOL execution, because none is possible in this environment (AAP
 *       0.7.6, risk <strong>R-A</strong>).</li>
 * </ul>
 *
 * <p>Gate <strong>G44</strong>: no data-definition statement, no entity annotation and no version column
 * appears here or is reachable from here.
 *
 * <h2>The JCL contract - {@code app/jcl/POSTTRAN.jcl}</h2>
 *
 * <p>One step, {@code //STEP15 EXEC PGM=CBTRN02C}, and it is a <strong>bare</strong> {@code EXEC} card: it
 * declares no {@code PARM}, so <strong>this job declares no {@link JobParameters}</strong> and none may be
 * invented. Its six data DDs, in the order the program opens them:
 *
 * <table border="1">
 *   <caption>DD statements and the collaborator each resolves to</caption>
 *   <tr><th>DD</th><th>Dataset</th><th>Verb</th><th>Collaborator</th></tr>
 *   <tr><td>{@value #DALYTRAN_DD_NAME}</td><td>{@code DALYTRAN.PS}</td><td>{@code OPEN INPUT}</td>
 *       <td>{@link DalyTranRepository}</td></tr>
 *   <tr><td>{@value #TRANFILE_DD_NAME}</td><td>{@code TRANSACT.VSAM.KSDS}</td><td>{@code OPEN OUTPUT}</td>
 *       <td>{@link TransactionRepository}</td></tr>
 *   <tr><td>{@value #XREFFILE_DD_NAME}</td><td>{@code CARDXREF.VSAM.KSDS}</td><td>{@code OPEN INPUT}</td>
 *       <td>{@link CardXrefRepository}</td></tr>
 *   <tr><td>{@value #DALYREJS_DD_NAME}</td><td>{@code DALYREJS(+1)}, {@code RECFM=F LRECL=430}</td>
 *       <td>{@code OPEN OUTPUT}</td><td>{@link DalyRejectWriter}</td></tr>
 *   <tr><td>{@value #ACCTFILE_DD_NAME}</td><td>{@code ACCTDATA.VSAM.KSDS}</td><td>{@code OPEN I-O}</td>
 *       <td>{@link AccountRepository}</td></tr>
 *   <tr><td>{@value #TCATBALF_DD_NAME}</td><td>{@code TCATBALF.VSAM.KSDS}</td><td>{@code OPEN I-O}</td>
 *       <td>{@link TranCatBalRepository}</td></tr>
 * </table>
 *
 * <p>Every one of the six is proven at construction to resolve to the same dataset as the repository that
 * reads it, through {@link BatchConfig#requireSameDataset(String, String, String)}. A DD the JCL declares
 * and the job then reads around is a DD statement that drives nothing, and that is exactly the class of
 * defect the proof exists to catch.
 *
 * <h2>Why chunk-oriented, and why the commit interval is {@value #CHUNK_SIZE}</h2>
 *
 * <p>This is one of only <em>two</em> chunk-oriented jobs in the estate (AAP 0.3.5; the other is
 * {@code account/AccountInterestCalcJob}), and {@link BatchConfig#chunkStep(String, int)} names it as such.
 * The loop of {@code app/cbl/CBTRN02C.cbl:202-219} is genuinely record-at-a-time with independent outcomes:
 * one daily transaction is read, validated, and then either posted or rejected, with no cross-record
 * accumulation beyond the two counters.
 *
 * <p>The commit interval is <strong>one item</strong>, chosen to preserve write ordering rather than
 * throughput (AAP 0.8.6). Posting one record writes to three datasets in a fixed order - the category
 * balance, then the account master, then the transaction master - and a larger chunk would let a rollback
 * discard writes the COBOL had already made durable: {@code CBTRN02C} issues no syncpoint and every dataset
 * it touches is {@code RECOVERY(NONE)}, so each verb stands the instant it completes. One item per chunk is
 * the granularity of the COBOL loop, so the read / process / write triple commits exactly where the
 * single-pass program did.
 *
 * <p>The chunk's own transaction, taken from {@link BatchConfig}'s single transaction manager, is also what
 * makes the repositories' keyed probes take a row lock: they consult whether a unit of work is open and
 * choose a locking read when one is. This class therefore holds no transaction plumbing of its own.
 *
 * <h2>The cascade - and what gate G31 does and does not require</h2>
 *
 * <p>{@code 1500-VALIDATE-TRAN} ({@code :370-378}) performs {@code 1500-A-LOOKUP-XREF} and then, only
 * {@code IF WS-VALIDATION-FAIL-REASON = 0}, {@code 1500-B-LOOKUP-ACCT}. So:
 *
 * <ol>
 *   <li><strong>Card lookup ({@code :380-392}) short-circuits the account lookup.</strong> An invalid card
 *       number sets reason 100 and the account is never read.</li>
 *   <li><strong>Account lookup ({@code :393-422}) sets reason 101 on {@code INVALID KEY}.</strong></li>
 *   <li><strong>The credit-limit test ({@code :407-413}) and the expiration test ({@code :414-420}) are two
 *       sequential, independently-guarded {@code IF}s.</strong> The second is <em>not</em> guarded by the
 *       outcome of the first. When both fail, {@code MOVE 103} at {@code :417}
 *       <strong>overwrites</strong> the 102 that {@code :410} had just stored, and the description is
 *       overwritten with it - <strong>last writer wins</strong>. That is reproduced exactly. No early
 *       return, no {@code else} and no guard is inserted after the credit-limit failure.</li>
 * </ol>
 *
 * <p>Gate <strong>G31</strong>'s "no implicit fall-through" is satisfied by there being no
 * <em>unintended</em> leakage - no stage's reject text reaching another stage's outcome, and no stage
 * silently skipped. It does <strong>not</strong> authorise adding guards the COBOL lacks, and the 102/103
 * overwrite is deliberate rather than a defect to be closed.
 *
 * <h2>Preserved defects (practice B5)</h2>
 *
 * <ol>
 *   <li><strong>Reason 109 is set and unreachable.</strong> {@code 2800-UPDATE-ACCOUNT-REC} sets reason 109
 *       on an {@code INVALID KEY} from the account {@code REWRITE} ({@code :555-558}) - but the mainline
 *       tested the reason at {@code :211} <em>before</em> routing this record down the posting path, so the
 *       new value produces no reject record and never increments {@code WS-REJECT-COUNT}. It is kept
 *       set-but-unused; see {@link RecordOutcome#accountRewriteInvalidKey()}.</li>
 *   <li><strong>The reject-count line carries two spaces before its colon.</strong> {@code :227} is
 *       {@code 'TRANSACTIONS PROCESSED :'} and {@code :228} is {@code 'TRANSACTIONS REJECTED  :'}. The
 *       literals differ and both are emitted verbatim.</li>
 *   <li><strong>{@code 9300-DALYREJS-CLOSE} displays the wrong file's status.</strong> {@code :649} is
 *       {@code MOVE XREFFILE-STATUS TO IO-STATUS} inside the rejects-file close guard. The cross-reference
 *       file's status is what reaches {@code 9910-DISPLAY-IO-STATUS} there, not the rejects file's, and that
 *       is reproduced.</li>
 * </ol>
 *
 * <h2>{@code READ ... INTO} and the stale record area</h2>
 *
 * <p>{@code DALYTRAN-RECORD}, {@code CARD-XREF-RECORD}, {@code ACCOUNT-RECORD},
 * {@code TRAN-CAT-BAL-RECORD} and {@code TRAN-RECORD} are all {@code WORKING-STORAGE} items, so a
 * {@code READ ... INTO} that fails transfers nothing and leaves the area holding the <em>previous</em>
 * record. That is reproduced by replacing an area only when a read carries a record, and never clearing it -
 * which is what makes {@code INITIALIZE TRAN-CAT-BAL-RECORD} at {@code :504} inherit the previous record's
 * {@code FILLER}, exactly as the mainframe does. See {@link PostingRun}.
 *
 * @see DalyRejectWriter the 430-byte reject record and its reason codes
 * @see BatchConfig the job, step, gating and return-code scaffolding
 */
@Configuration(TransactionValidationJob.CONFIGURATION_BEAN_NAME)
public class TransactionValidationJob {

    // =================================================================================================
    // Identity. The job key is the carddemo.jobs key; the job name is the bean name BatchConfig derives
    // from it, so the two can never drift apart.
    // =================================================================================================

    /** The bean name of this configuration class. */
    public static final String CONFIGURATION_BEAN_NAME = "transactionValidationJobConfiguration";

    /** The {@code carddemo.jobs} key whose contract this job is built from. */
    public static final String JOB_KEY = "transaction-validation-job";

    /**
     * The job name, which is also the {@code Job} bean's name and its identity in the batch metadata.
     *
     * <p>It is exactly what {@code BatchConfig.jobBeanNameOf(}{@value #JOB_KEY}{@code )} derives, which is
     * what lets a launcher resolve this job's contract from the name it was asked to run.
     */
    public static final String JOB_NAME = "transactionValidationJob";

    /** The {@code Step} bean's name. */
    public static final String STEP_BEAN_NAME = "transactionValidationStep";

    /** The COBOL {@code PROGRAM-ID} this job was translated from. */
    public static final String PROGRAM_ID = "CBTRN02C";

    /** The JCL step name: {@code app/jcl/POSTTRAN.jcl:23}. */
    public static final String STEP_NAME = "STEP15";

    /** The JCL this job's contract is transcribed from, quoted in every construction-time refusal. */
    public static final String JCL_REFERENCE = "app/jcl/POSTTRAN.jcl";

    /**
     * The one step {@value #JCL_REFERENCE} declares, ungated.
     *
     * <p>{@code app/jcl/POSTTRAN.jcl} contains a single {@code EXEC} card and no {@code COND} at all, so
     * the sequence is one ungated step. A second step, or a gate, would be a job that runs different work
     * against the same six datasets.
     */
    public static final List<StepContract> REQUIRED_STEPS =
            List.of(new StepContract(STEP_NAME, PROGRAM_ID, false));

    /**
     * The commit interval, in items: {@value}.
     *
     * <p>Not a performance setting. See the class documentation: one record per chunk is the granularity of
     * the COBOL loop, and it is what keeps a rollback from discarding a write the COBOL had already made
     * durable.
     */
    public static final int CHUNK_SIZE = 1;

    // =================================================================================================
    // The six DD names of app/jcl/POSTTRAN.jcl:28-42. Each is the CONFIGURATION KEY the step declares,
    // never a dataset name (gate G46) - the dataset behind it is resolved from application.yml.
    // =================================================================================================

    /** {@code //DALYTRAN} - the sequential daily-transaction file, this job's only input loop. */
    public static final String DALYTRAN_DD_NAME = DalyTranRepository.DD_NAME;

    /** {@code //TRANFILE} - the transaction master, opened for a keyed load. */
    public static final String TRANFILE_DD_NAME = TransactionRepository.INPUT_DD_NAME;

    /** {@code //XREFFILE} - the card cross reference, read by its <strong>base</strong> 16-byte key. */
    public static final String XREFFILE_DD_NAME = CardXrefRepository.BATCH_DD_NAME;

    /** {@code //DALYREJS} - the rejected-transaction generation, {@code RECFM=F LRECL=430}. */
    public static final String DALYREJS_DD_NAME = DalyRejectWriter.DD_NAME;

    /** {@code //ACCTFILE} - the account master, opened {@code I-O} and rewritten on every posting. */
    public static final String ACCTFILE_DD_NAME = AccountRepository.BATCH_DD_NAME;

    /** {@code //TCATBALF} - the transaction category balances, opened {@code I-O}. */
    public static final String TCATBALF_DD_NAME = TranCatBalRepository.DD_NAME;

    // =================================================================================================
    // APPL-RESULT - app/cbl/CBTRN02C.cbl:142-144. The values the program's guard chains move, named so a
    // reader can match a Java branch to the MOVE it reproduces.
    // =================================================================================================

    /** {@code MOVE 8 TO APPL-RESULT} - the assumed failure every I/O paragraph starts from. */
    public static final int APPL_RESULT_ASSUMED_FAILURE = AbendException.RETURN_CODE_ASSUMED_FAILURE;

    /** {@code MOVE 0 TO APPL-RESULT} - {@code 88 APPL-AOK VALUE 0}. */
    public static final int APPL_RESULT_AOK = FileStatus.APPL_AOK;

    /** {@code MOVE 16 TO APPL-RESULT} - {@code 88 APPL-EOF VALUE 16}, set only by the DALYTRAN read. */
    public static final int APPL_RESULT_EOF = FileStatus.APPL_EOF;

    /** {@code MOVE 12 TO APPL-RESULT} - the fatal arm of every guard chain in the program. */
    public static final int APPL_RESULT_FATAL = AbendException.RETURN_CODE_IO_ERROR;

    /** {@code MOVE 4 TO RETURN-CODE} at {@code :230} - the warning a run with any reject reports. */
    public static final int RETURN_CODE_REJECTS_PRESENT = AbendException.RETURN_CODE_WARNING;

    /** {@code RETURN-CODE} of a run that rejected nothing. */
    public static final int RETURN_CODE_CLEAN = AbendException.RETURN_CODE_OK;

    /**
     * The feedback byte of a permanent-error status: {@code '9'} followed by a binary zero.
     *
     * <p>Used only where a collaborator reports a {@link FileStatus.Outcome} rather than a two-character
     * status - the reject writer's sink contract does, because a collector has no {@code FILE STATUS} to
     * report - so that {@code 9910-DISPLAY-IO-STATUS} still has a status to render. It matches the value
     * every repository in this module composes, so one failure renders identically wherever it surfaces.
     */
    private static final char PERMANENT_ERROR_FEEDBACK_CODE = 0;

    /** The status reported for a failure the COBOL vocabulary has no specific code for. */
    public static final String PERMANENT_ERROR_STATUS = "9" + PERMANENT_ERROR_FEEDBACK_CODE;

    // =================================================================================================
    // WORKING-STORAGE flags and widths - app/cbl/CBTRN02C.cbl:146, :184-190.
    // =================================================================================================

    /** {@code MOVE 'Y' TO END-OF-FILE} at {@code :361}. */
    public static final String AT_END_OF_FILE = "Y";

    /** {@code 01 END-OF-FILE PIC X(01) VALUE 'N'} at {@code :146}. */
    public static final String NOT_AT_END_OF_FILE = "N";

    /** {@code MOVE 'Y' TO WS-CREATE-TRANCAT-REC} at {@code :478}. */
    public static final String CREATE_TRANCAT_REC = "Y";

    /** {@code MOVE 'N' TO WS-CREATE-TRANCAT-REC} at {@code :473}. */
    public static final String DO_NOT_CREATE_TRANCAT_REC = "N";

    /**
     * {@code WS-TRANSACTION-COUNT} and {@code WS-REJECT-COUNT} are both {@code PIC 9(09)} ({@code :185-186}),
     * so both render zero-filled to nine digits wherever {@code DISPLAY} contributes them.
     */
    public static final int COUNTER_WIDTH = 9;

    /**
     * {@code WS-TEMP-BAL PIC S9(09)V99} ({@code :187}) - nine integer digits and scale
     * {@value CobolDecimal#MONETARY_SCALE}.
     */
    public static final int WS_TEMP_BAL_INTEGER_DIGITS = 9;

    /** The scale of {@code WS-TEMP-BAL}, and of every monetary field this job touches. */
    public static final int WS_TEMP_BAL_SCALE = CobolDecimal.MONETARY_SCALE;

    // =================================================================================================
    // Reject reasons. Declared by DalyRejectWriter, which owns the 430-byte trailer they are written
    // into; re-exposed here by delegation so a reader of this class sees which of them CBTRN02C sets.
    // =================================================================================================

    /** {@code MOVE 100 ...} at {@code :385} - {@code 'INVALID CARD NUMBER FOUND'}. */
    public static final int REASON_INVALID_CARD_NUMBER = DalyRejectWriter.REASON_INVALID_CARD_NUMBER;

    /** {@code MOVE 101 ...} at {@code :397} - {@code 'ACCOUNT RECORD NOT FOUND'}. */
    public static final int REASON_ACCOUNT_RECORD_NOT_FOUND =
            DalyRejectWriter.REASON_ACCOUNT_RECORD_NOT_FOUND;

    /** {@code MOVE 102 ...} at {@code :410} - {@code 'OVERLIMIT TRANSACTION'}. */
    public static final int REASON_OVERLIMIT_TRANSACTION = DalyRejectWriter.REASON_OVERLIMIT_TRANSACTION;

    /** {@code MOVE 103 ...} at {@code :417} - {@code 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION'}. */
    public static final int REASON_TRANSACTION_AFTER_EXPIRATION =
            DalyRejectWriter.REASON_TRANSACTION_AFTER_EXPIRATION;

    /**
     * {@code MOVE 109 ...} at {@code :556} - set by the account rewrite's {@code INVALID KEY} and never
     * acted on. See the class documentation's preserved-defect list.
     */
    public static final int REASON_ACCOUNT_NOT_FOUND_ON_REWRITE =
            DalyRejectWriter.REASON_ACCOUNT_NOT_FOUND_ON_REWRITE;

    /** {@code MOVE 0 TO WS-VALIDATION-FAIL-REASON} at {@code :208} - the "no reason yet" value. */
    public static final int REASON_NONE = DalyRejectWriter.REASON_NONE;

    /** {@code MOVE SPACES TO WS-VALIDATION-FAIL-REASON-DESC} at {@code :209}. */
    public static final String DESC_SPACES = DalyRejectWriter.DESC_SPACES;

    // =================================================================================================
    // Every DISPLAY literal, transcribed byte for byte. They are part of the parity fingerprint, so each
    // carries the line it comes from and none is normalised, re-spelled or reworded.
    // =================================================================================================

    /** {@code :194}. */
    public static final String START_OF_EXECUTION = "START OF EXECUTION OF PROGRAM CBTRN02C";

    /** {@code :232}. */
    public static final String END_OF_EXECUTION = "END OF EXECUTION OF PROGRAM CBTRN02C";

    /** {@code :247} - note it names the DD, not "DALYTRAN FILE" as the matching close does. */
    public static final String ERROR_OPENING_DALYTRAN = "ERROR OPENING DALYTRAN";

    /** {@code :265}. */
    public static final String ERROR_OPENING_TRANFILE = "ERROR OPENING TRANSACTION FILE";

    /** {@code :284}. */
    public static final String ERROR_OPENING_XREFFILE = "ERROR OPENING CROSS REF FILE";

    /** {@code :302} - {@code DALY}, abbreviated, unlike the close at {@code :648}. */
    public static final String ERROR_OPENING_DALYREJS = "ERROR OPENING DALY REJECTS FILE";

    /** {@code :320} - {@code MASTER}, which the matching close at {@code :666} omits. */
    public static final String ERROR_OPENING_ACCTFILE = "ERROR OPENING ACCOUNT MASTER FILE";

    /** {@code :338}. */
    public static final String ERROR_OPENING_TCATBALF = "ERROR OPENING TRANSACTION BALANCE FILE";

    /** {@code :363}. */
    public static final String ERROR_READING_DALYTRAN = "ERROR READING DALYTRAN FILE";

    /** {@code :460}. */
    public static final String ERROR_WRITING_DALYREJS = "ERROR WRITING TO REJECTS FILE";

    /** {@code :489}. */
    public static final String ERROR_READING_TCATBALF = "ERROR READING TRANSACTION BALANCE FILE";

    /** {@code :520} - the create path of {@code 2700-A}. */
    public static final String ERROR_WRITING_TCATBALF = "ERROR WRITING TRANSACTION BALANCE FILE";

    /** {@code :538} - the update path of {@code 2700-B}. */
    public static final String ERROR_REWRITING_TCATBALF = "ERROR REWRITING TRANSACTION BALANCE FILE";

    /** {@code :574}. */
    public static final String ERROR_WRITING_TRANFILE = "ERROR WRITING TO TRANSACTION FILE";

    /** {@code :593} - {@code FILE}, which the matching open at {@code :247} omits. */
    public static final String ERROR_CLOSING_DALYTRAN = "ERROR CLOSING DALYTRAN FILE";

    /** {@code :611}. */
    public static final String ERROR_CLOSING_TRANFILE = "ERROR CLOSING TRANSACTION FILE";

    /** {@code :630}. */
    public static final String ERROR_CLOSING_XREFFILE = "ERROR CLOSING CROSS REF FILE";

    /** {@code :648} - {@code DAILY}, spelled out, unlike the open at {@code :302}. */
    public static final String ERROR_CLOSING_DALYREJS = "ERROR CLOSING DAILY REJECTS FILE";

    /** {@code :666} - no {@code MASTER}, unlike the open at {@code :320}. */
    public static final String ERROR_CLOSING_ACCTFILE = "ERROR CLOSING ACCOUNT FILE";

    /**
     * {@code :685} - the one pair whose open and close texts differ in nothing but the verb.
     *
     * <p>The other five pairs each differ by more: {@code DALYTRAN} gains {@code FILE} on the close,
     * {@code DALY} becomes {@code DAILY}, {@code MASTER} is dropped, and only the transaction file and the
     * cross reference are otherwise symmetric.
     */
    public static final String ERROR_CLOSING_TCATBALF = "ERROR CLOSING TRANSACTION BALANCE FILE";

    /**
     * {@code :476} - the first operand of the not-found display, whose trailing space is inside the
     * literal.
     *
     * <p>{@code DISPLAY 'TCATBAL record not found for key : ' FD-TRAN-CAT-KEY '.. Creating.'} contributes
     * its three operands with no separator of its own, so the emitted line is this text, then the
     * {@value TranCatBalRepository#KEY_LENGTH}-character key image, then
     * {@value #TCATBAL_NOT_FOUND_SUFFIX}.
     */
    public static final String TCATBAL_NOT_FOUND_PREFIX = "TCATBAL record not found for key : ";

    /** {@code :477} - the third operand: two dots, a space, and {@code Creating.} */
    public static final String TCATBAL_NOT_FOUND_SUFFIX = ".. Creating.";

    /** {@code :227} - one space before the colon. */
    public static final String TRANSACTIONS_PROCESSED_PREFIX = "TRANSACTIONS PROCESSED :";

    /**
     * {@code :228} - <strong>two</strong> spaces before the colon.
     *
     * <p>The asymmetry with {@value #TRANSACTIONS_PROCESSED_PREFIX} is in the source and is preserved
     * (practice B5). Aligning the two literals would change two bytes of every run's output.
     */
    public static final String TRANSACTIONS_REJECTED_PREFIX = "TRANSACTIONS REJECTED  :";

    // =================================================================================================
    // Z-GET-DB2-FORMAT-TIMESTAMP - app/cbl/CBTRN02C.cbl:149-174 and :692-705.
    // =================================================================================================

    /** {@code 01 DB2-FORMAT-TS PIC X(26)} at {@code :159}. */
    public static final int DB2_TIMESTAMP_LENGTH = 26;

    /**
     * {@code MOVE '-' TO DB2-STREEP-1 DB2-STREEP-2 DB2-STREEP-3} at {@code :702}.
     *
     * <p>All three separators are hyphens, so the <strong>date and time are joined by a hyphen</strong> and
     * not by a space: {@code DB2-STREEP-3} sits between {@code DB2-DD} and {@code DB2-HH} ({@code :166}).
     * The comment at {@code :149} spells the shape out as {@code EEEE-MM-DD-UU.MM.SS.HH0000}.
     */
    public static final String DB2_TIMESTAMP_HYPHEN = "-";

    /** {@code MOVE '.' TO DB2-DOT-1 DB2-DOT-2 DB2-DOT-3} at {@code :703}. */
    public static final String DB2_TIMESTAMP_DOT = ".";

    /** {@code MOVE '0000' TO DB2-REST} at {@code :701} - {@code DB2-REST PIC X(04)}. */
    public static final String DB2_TIMESTAMP_REST = "0000";

    /** {@code 01 COBOL-TS} at {@code :150-158}: 4+2+2+2+2+2+2+5 characters. */
    public static final int COBOL_TIMESTAMP_LENGTH = 21;

    /**
     * The divisor that turns nanoseconds into the hundredths {@code COB-MIL PIC X(02)} holds.
     *
     * <p>{@code FUNCTION CURRENT-DATE} reports hundredths of a second and nothing finer, so the composed
     * timestamp is never microsecond-precise however precise the injected {@link Clock} is - which is why
     * {@code DB2-REST} is the literal {@value #DB2_TIMESTAMP_REST} rather than a measured value.
     */
    private static final int NANOS_PER_HUNDREDTH = 10_000_000;

    /** Width of {@code COB-YYYY} / {@code DB2-YYYY}. */
    private static final int YEAR_WIDTH = 4;

    /** Width of every other {@code COBOL-TS} component except {@code COB-REST}. */
    private static final int TWO_DIGIT_WIDTH = 2;

    /** Width of {@code COB-REST PIC X(05)} - the offset from Greenwich, {@code +hhmm} or {@code -hhmm}. */
    private static final int GREENWICH_OFFSET_WIDTH = 5;

    /** Seconds in an hour, for rendering {@code COB-REST}. */
    private static final int SECONDS_PER_HOUR = 3600;

    /** Seconds in a minute, for rendering {@code COB-REST}. */
    private static final int SECONDS_PER_MINUTE = 60;

    // =================================================================================================
    // Collaborators. All final, all constructor-injected, none static and none mutable (practice B9,
    // gate G53). This class holds no dataset state of its own: a run's six open files belong to the
    // PostingRun that opened them.
    // =================================================================================================

    /** The job, step, gating and return-code scaffolding. This class holds no Batch plumbing itself. */
    private final BatchConfig batchConfig;

    /** {@value #DALYTRAN_DD_NAME} - the sequential input that drives the program's only loop. */
    private final DalyTranRepository dalyTranRepository;

    /** {@value #XREFFILE_DD_NAME} - read by the base 16-byte card number, never by the alternate index. */
    private final CardXrefRepository cardXrefRepository;

    /** {@value #ACCTFILE_DD_NAME} - opened {@code I-O}, read by key and rewritten on every posting. */
    private final AccountRepository accountRepository;

    /** {@value #TCATBALF_DD_NAME} - opened {@code I-O}, read by the 17-byte key, written or rewritten. */
    private final TranCatBalRepository tranCatBalRepository;

    /** {@value #TRANFILE_DD_NAME} - the transaction master this job adds posted records to. */
    private final TransactionRepository transactionRepository;

    /** {@value #DALYREJS_DD_NAME} - the 430-byte reject generation. */
    private final DalyRejectWriter dalyRejectWriter;

    /**
     * The clock behind {@code FUNCTION CURRENT-DATE} at {@code :693}.
     *
     * <p>Injected rather than defaulted so a parity case can pin {@code TRAN-PROC-TS} and assert its 26
     * bytes. A job that silently fell back to the system clock would write a timestamp no case could
     * express.
     */
    private final Clock clock;

    /** The code page every record this job touches is encoded in - never the platform default. */
    private final Charset datasetCharset;

    /** The codec used for the numeric renderings this class performs: the counters and the timestamp. */
    private final FixedWidthCodec codec;

    /** Where {@code DISPLAY} goes when nothing overrides it. */
    private final SysoutSink sysoutSink;

    /** This job's validated step contract - one ungated {@value #STEP_NAME}. */
    private final StepContract stepContract;

    /**
     * Wires the six collaborators and proves the contract before any run can start.
     *
     * <p>Three checks happen here rather than at launch, because a job whose contract is wrong does not
     * fail when it is <em>built</em> - it runs, against the wrong dataset or with the wrong gating, and the
     * first sign of trouble is output that does not match:
     *
     * <ol>
     *   <li><strong>The step sequence.</strong> {@link BatchConfig#requireSteps(String, List, String)}
     *       against {@link #REQUIRED_STEPS}: one step named {@value #STEP_NAME} running
     *       {@value #PROGRAM_ID}, ungated. {@value #JCL_REFERENCE} has one {@code EXEC} card and no
     *       {@code COND}.</li>
     *   <li><strong>The parameter contract.</strong> {@value #JCL_REFERENCE} is a bare
     *       {@code EXEC PGM=CBTRN02C} with no {@code PARM}, so the contract must declare no parameters. A
     *       declared parameter here would be an input the COBOL program never receives, and Spring Batch
     *       identifies an instance by its parameters, so it would silently resolve submissions to a
     *       different job instance.</li>
     *   <li><strong>Every DD.</strong> {@link BatchConfig#requireSameDataset(String, String, String)} for
     *       each of the six, so a DD the JCL declares cannot be one the job then reads around. The job's
     *       side is resolved job-first, which is what makes the shipped
     *       {@code carddemo.jobs.transaction-validation-job.datasets.TRANFILE.alias: TRANSACT} count rather
     *       than be assumed.</li>
     * </ol>
     *
     * <p>The dataset code page is taken from a repository rather than injected separately, so this class
     * cannot disagree with the repositories about the encoding of the very bytes it renders.
     *
     * @param batchConfig           the batch scaffolding; required
     * @param dalyTranRepository    {@value #DALYTRAN_DD_NAME}; required
     * @param cardXrefRepository    {@value #XREFFILE_DD_NAME}; required
     * @param accountRepository     {@value #ACCTFILE_DD_NAME}; required
     * @param tranCatBalRepository  {@value #TCATBALF_DD_NAME}; required
     * @param transactionRepository {@value #TRANFILE_DD_NAME}; required
     * @param dalyRejectWriter      {@value #DALYREJS_DD_NAME}; required
     * @param sysoutSinkProvider    the {@code SYSOUT} destination; may resolve to no bean, in which case
     *                              the process's standard output is used in the dataset code page
     * @param clock                 the clock behind {@code FUNCTION CURRENT-DATE}; required
     * @throws NullPointerException  if any required collaborator or the provider is {@code null}
     * @throws IllegalStateException if this job's {@code carddemo.jobs} contract names another program,
     *                               declares another step sequence, gates its only step, declares any job
     *                               parameter, or binds a DD to a dataset other than the one the
     *                               repository reading it is bound to
     */
    public TransactionValidationJob(BatchConfig batchConfig,
            DalyTranRepository dalyTranRepository,
            CardXrefRepository cardXrefRepository,
            AccountRepository accountRepository,
            TranCatBalRepository tranCatBalRepository,
            TransactionRepository transactionRepository,
            DalyRejectWriter dalyRejectWriter,
            ObjectProvider<SysoutSink> sysoutSinkProvider,
            Clock clock) {

        this.batchConfig = Objects.requireNonNull(batchConfig, "The batch scaffolding is required: the "
                + "job and step builders, the job repository and the transaction manager all arrive "
                + "through it, so this class holds no Spring Batch plumbing of its own");
        this.dalyTranRepository = Objects.requireNonNull(dalyTranRepository, "The daily-transaction "
                + "repository is required: " + DALYTRAN_DD_NAME + " is the sequential read that drives "
                + "this program's only loop (app/cbl/CBTRN02C.cbl:202-219)");
        this.cardXrefRepository = Objects.requireNonNull(cardXrefRepository, "The cross-reference "
                + "repository is required: 1500-A-LOOKUP-XREF reads " + XREFFILE_DD_NAME + " by its BASE "
                + "16-byte card number to obtain the account the transaction belongs to");
        this.accountRepository = Objects.requireNonNull(accountRepository, "The account master repository "
                + "is required: " + ACCTFILE_DD_NAME + " is opened I-O, read by key in 1500-B-LOOKUP-ACCT "
                + "and rewritten in 2800-UPDATE-ACCOUNT-REC");
        this.tranCatBalRepository = Objects.requireNonNull(tranCatBalRepository, "The transaction "
                + "category balance repository is required: " + TCATBALF_DD_NAME + " is opened I-O and "
                + "either written or rewritten for every posted transaction");
        this.transactionRepository = Objects.requireNonNull(transactionRepository, "The transaction "
                + "repository is required: " + TRANFILE_DD_NAME + " is where a posted record is added, by "
                + "the TRAN-ID the record itself carries");
        this.dalyRejectWriter = Objects.requireNonNull(dalyRejectWriter, "The reject writer is required: "
                + DALYREJS_DD_NAME + " receives one " + DalyRejectWriter.RECORD_LENGTH + "-byte record per "
                + "rejected transaction, and a run whose input is entirely clean still opens and closes "
                + "it");
        Objects.requireNonNull(sysoutSinkProvider, "A SYSOUT sink provider is required; it may resolve "
                + "to no bean, in which case the standard output stream is used");
        this.clock = Objects.requireNonNull(clock, "A Clock is required: FUNCTION CURRENT-DATE at "
                + "app/cbl/CBTRN02C.cbl:693 supplies the TRAN-PROC-TS every posted transaction carries, "
                + "so the clock is injected rather than defaulted - a job that silently fell back to the "
                + "system clock would write a timestamp no parity case could pin");

        this.datasetCharset = dalyTranRepository.datasetCharset();
        this.codec = new FixedWidthCodec(this.datasetCharset);
        this.sysoutSink = sysoutSinkProvider.getIfAvailable(() -> standardOutput(this.datasetCharset));
        this.stepContract = requireUngatedStep(batchConfig);
        requireNoJobParameters(batchConfig);
        requireStepDatasets(batchConfig);
    }

    /**
     * Requires the configured step sequence to be the single ungated {@value #STEP_NAME} of
     * {@value #JCL_REFERENCE}.
     *
     * @param scaffolding the scaffolding holding the contracts
     * @return the one declared step contract
     * @throws IllegalStateException if the configured sequence differs in name, program, gating or length
     */
    private static StepContract requireUngatedStep(BatchConfig scaffolding) {
        List<StepContract> declared =
                scaffolding.requireSteps(JOB_KEY, REQUIRED_STEPS, JCL_REFERENCE);
        return declared.get(0);
    }

    /**
     * Requires the contract to declare <strong>no</strong> job parameters.
     *
     * <p>{@code app/jcl/POSTTRAN.jcl:23} is {@code //STEP15 EXEC PGM=CBTRN02C} with nothing after the
     * program name. A parameter declared here could not change what the program does - it receives none -
     * but it would change which job instance a submission resolves to, so it is refused rather than
     * ignored.
     *
     * @param scaffolding the scaffolding holding the contracts
     * @throws IllegalStateException if any parameter is declared
     */
    private static void requireNoJobParameters(BatchConfig scaffolding) {
        JobParameters declared = scaffolding.contract(JOB_KEY).jobParameters();
        if (!declared.isEmpty()) {
            throw new IllegalStateException("The carddemo.jobs contract for '" + JOB_KEY + "' declares "
                    + declared.getParameters().keySet() + ", but " + JCL_REFERENCE + ":23 is a bare "
                    + "EXEC PGM=" + PROGRAM_ID + " and declares no PARM, so this job receives no job "
                    + "parameters. Spring Batch identifies a job instance by its parameters, so a value "
                    + "here cannot reach the program but does resolve a submission to a different "
                    + "instance. Remove carddemo.jobs." + JOB_KEY + ".parameters, or declare it as the "
                    + "empty list.");
        }
    }

    /**
     * Proves that each of the six DD names {@value #JCL_REFERENCE} declares resolves to the dataset the
     * repository that reads it is bound to.
     *
     * <p>{@value #TRANFILE_DD_NAME} is the one that can genuinely diverge: {@code app/jcl/TRANREPT.jcl}
     * binds the same eight characters to {@code TRANSACT.DALY(+1)} instead, which is why the shipped
     * configuration states this job's {@code TRANFILE} explicitly as an alias of the master. Proving it
     * here is what keeps that statement load-bearing.
     *
     * <p>{@value #XREFFILE_DD_NAME} and {@value #ACCTFILE_DD_NAME} are the batch DD names of datasets the
     * CSD also names ({@code CCXREF} and {@code ACCTDAT}), so both keys are compared - two configuration
     * keys can be overridden independently, and a step that named one while reading through the other would
     * make its own DD statement decorative.
     *
     * <p>{@value #DALYTRAN_DD_NAME}, {@value #DALYREJS_DD_NAME} and {@value #TCATBALF_DD_NAME} are each
     * addressed under one key, so those comparisons cannot fail - and they still earn their line, because
     * they prove the key is declared at all and keep the list of this step's datasets complete rather than
     * selective.
     *
     * @param scaffolding the scaffolding holding the contracts and the DD catalogue
     * @throws IllegalStateException if any DD is undeclared, or resolves to a different dataset from the
     *                               repository that reads or writes it
     */
    private static void requireStepDatasets(BatchConfig scaffolding) {
        scaffolding.requireSameDataset(JOB_KEY, DALYTRAN_DD_NAME, DalyTranRepository.DD_NAME);
        scaffolding.requireSameDataset(JOB_KEY, TRANFILE_DD_NAME, TransactionRepository.CICS_FILE_NAME);
        scaffolding.requireSameDataset(JOB_KEY, XREFFILE_DD_NAME, CardXrefRepository.BASE_DD_NAME);
        scaffolding.requireSameDataset(JOB_KEY, DALYREJS_DD_NAME, DalyRejectWriter.DD_NAME);
        scaffolding.requireSameDataset(JOB_KEY, ACCTFILE_DD_NAME, AccountRepository.CICS_FILE_NAME);
        scaffolding.requireSameDataset(JOB_KEY, TCATBALF_DD_NAME, TranCatBalRepository.DD_NAME);
    }

    // =================================================================================================
    // The Spring Batch surface: one Job, one chunk-oriented Step of CHUNK_SIZE items, and nothing else.
    // No @EnableBatchProcessing, no JobRepository bean, no JobLauncher bean and no transaction manager -
    // Boot auto-configures the first and BatchConfig owns the others (practice B2, gate G3).
    // =================================================================================================

    /**
     * The job {@value #JCL_REFERENCE} translates to: one step, no parameters.
     *
     * <p>{@link BatchConfig#job(String)} supplies everything shared - the job repository, the abend and
     * {@code COND}-bypass return-code listeners, the run-identity incrementer, the parameter allow-list and
     * the non-restartable policy - so this method contributes only the step.
     *
     * <p><strong>Restart is refused, and that is a parity decision rather than a policy one.</strong> A JCL
     * step has no restart: an operator who re-runs {@code POSTTRAN} submits the job again, which is a new
     * pass over the whole of {@value #DALYTRAN_DD_NAME}. Spring Batch's restart is a different thing - it
     * resumes the <em>same</em> instance from the item after the last commit - and this program stores no
     * position in its execution context, so a resumed run would re-read the daily file from the beginning
     * while the accounts and balances it had already updated stayed updated. Every transaction before the
     * failure point would be posted a second time. Refusing the restart is what makes that unreachable.
     *
     * @return the job; never {@code null}
     */
    @Bean
    public Job transactionValidationJob() {
        return batchConfig.job(JOB_NAME)
                .preventRestart()
                .start(transactionValidationStep())
                .build();
    }

    /**
     * The single step, chunk-oriented at a commit interval of {@value #CHUNK_SIZE}.
     *
     * <p>One delegate instance is the reader, the processor, the writer and the step listener, because all
     * four need the same {@code WORKING-STORAGE} and the same six open files - which is what a COBOL program
     * is. Registering it as a stream is what gives the {@code OPEN} and {@code CLOSE} paragraphs their
     * lifecycle: {@link ChunkDelegate#open(ExecutionContext)} runs the banner and the six opens at the start
     * of the step execution, and {@link ChunkDelegate#close()} runs the six closes, the two count lines and
     * the closing banner at the end.
     *
     * <p>What is registered is a {@link StepScopedChunkDelegate}, which builds a fresh
     * {@link ChunkDelegate} per step execution rather than closing over one. A {@code Step} bean is built
     * once and launched as often as the operator launches the job, so a delegate captured here would be
     * shared by every execution - including two that overlap. {@code CBTRN02C} has no such sharing to
     * reproduce: each JCL submission is its own address space with its own {@code WORKING-STORAGE} and its
     * own six open files.
     *
     * @return the step; never {@code null}
     */
    @Bean(STEP_BEAN_NAME)
    public Step transactionValidationStep() {
        StepScopedChunkDelegate delegate = new StepScopedChunkDelegate(this);
        SimpleStepBuilder<DalyTranRecord, RecordOutcome> builder =
                batchConfig.chunkStep(stepContract.name(), CHUNK_SIZE);
        builder.reader(delegate).processor(delegate).writer(delegate).stream(delegate);
        builder.listener((StepExecutionListener) delegate);
        return builder.build();
    }

    /**
     * A fresh chunk delegate over this job.
     *
     * <p>Exposed so a unit test and the parity harness can drive the reader, processor and writer contract
     * directly - the stream lifecycle included - with no {@code JobLauncher} and no application context
     * (practice B10, gate G51).
     *
     * @return a new delegate; never {@code null}
     */
    public ChunkDelegate newChunkDelegate() {
        return new ChunkDelegate(this);
    }

    /**
     * A fresh run of the program: one {@code WORKING-STORAGE} and one set of six file handles.
     *
     * <p>Package-visible so a test can drive the paragraphs individually - open the files, invoke a single
     * {@code 1500-VALIDATE-TRAN}, assert the reason, and close - rather than only through the whole loop.
     *
     * @param sysout where {@code DISPLAY} goes; must not be {@code null}
     * @return a new, unopened run; never {@code null}
     * @throws NullPointerException if {@code sysout} is {@code null}
     */
    PostingRun newRun(SysoutSink sysout) {
        return new PostingRun(this, sysout);
    }

    // =================================================================================================
    // Read-only accessors. Diagnostics and assertions, never a way to reach around this class.
    // =================================================================================================

    /**
     * The job parameters {@value #JCL_REFERENCE} declares: none.
     *
     * @return the declared parameters, always empty; never {@code null}
     */
    public JobParameters jobParameters() {
        return batchConfig.contract(JOB_KEY).jobParameters();
    }

    /**
     * This job's validated step contract.
     *
     * @return the contract; never {@code null}
     */
    public StepContract stepContract() {
        return stepContract;
    }

    /**
     * The {@code SYSOUT} destination this job writes to when nothing overrides it.
     *
     * @return the sink; never {@code null}
     */
    public SysoutSink sysoutSink() {
        return sysoutSink;
    }

    /**
     * The dataset code page every record this job touches is encoded in.
     *
     * @return the charset; never {@code null} and never a platform default
     */
    public Charset datasetCharset() {
        return datasetCharset;
    }

    /**
     * The clock behind {@code FUNCTION CURRENT-DATE}.
     *
     * @return the clock; never {@code null}
     */
    public Clock clock() {
        return clock;
    }

    // =================================================================================================
    // PROCEDURE DIVISION - app/cbl/CBTRN02C.cbl:193-234.
    //
    // Runnable with no application context, no JobLauncher and no HTTP layer (practice B10, gate G51), so
    // every branch below is reachable from a plain unit test and every write is reachable by the parity
    // harness.
    // =================================================================================================

    /**
     * Runs the whole program against the injected {@code SYSOUT} sink.
     *
     * @return what the run did: the two counters and the {@code RETURN-CODE}
     * @throws AbendException if any file operation the program checks reports a status it treats as fatal
     */
    public RunOutcome postTransactions() {
        return postTransactions(sysoutSink);
    }

    /**
     * Runs the whole program, emitting every {@code DISPLAY} to the given sink.
     *
     * <p>The literal transcription of {@code app/cbl/CBTRN02C.cbl:193-234}: the opening banner, the six
     * opens, the read / validate / post-or-reject loop, the six closes, the two count lines, the
     * {@code RETURN-CODE} decision and the closing banner - in that order.
     *
     * <p><strong>An abend leaves the closes unperformed</strong>, exactly as it does on the mainframe: the
     * closes and the trailer are inside the {@code try}, and the {@code finally} only releases the handles
     * so nothing is left open. A failed run therefore emits neither count line nor the closing banner.
     *
     * @param sysout where each {@code DISPLAY} goes; must not be {@code null}
     * @return what the run did; never {@code null}
     * @throws NullPointerException if {@code sysout} is {@code null}
     * @throws AbendException       if any file operation the program checks reports a fatal status
     */
    public RunOutcome postTransactions(SysoutSink sysout) {
        Objects.requireNonNull(sysout, "A SYSOUT sink is required to run " + PROGRAM_ID);
        PostingRun run = newRun(sysout);
        try {
            // DISPLAY 'START OF EXECUTION OF PROGRAM CBTRN02C'.                                      L194
            sysout.write(START_OF_EXECUTION);

            // PERFORM 0000-DALYTRAN-OPEN through 0500-TCATBALF-OPEN.                            L195-L200
            run.openFiles();

            // PERFORM UNTIL END-OF-FILE = 'Y' ... END-PERFORM.                                  L202-L219
            while (!run.workingStorage().endOfFileIsYes()) {
                DalyTranRecord item = run.dalytranGetNext();
                if (item != null) {
                    run.processRecord(item);
                }
            }

            // PERFORM 9000-DALYTRAN-CLOSE through 9500-TCATBALF-CLOSE.                          L221-L226
            run.closeFiles();

            // The two count lines, the RETURN-CODE decision and the closing banner.             L227-L232
            return run.writeTrailer();
        } finally {
            run.release();
        }
    }

    // =================================================================================================
    // Z-GET-DB2-FORMAT-TIMESTAMP - app/cbl/CBTRN02C.cbl:692-705.
    // =================================================================================================

    /**
     * Composes {@code DB2-FORMAT-TS} - the 26 characters {@code 2000-POST-TRANSACTION} moves into
     * {@code TRAN-PROC-TS} at {@code :438}.
     *
     * <p>The shape is the one the comment at {@code :149} spells out, {@code EEEE-MM-DD-UU.MM.SS.HH0000},
     * which is to say: hyphens at 1-based positions 5, 8 and <strong>11</strong>, dots at 14, 17 and 20, and
     * the literal {@value #DB2_TIMESTAMP_REST} in the last four bytes. <strong>The date and the time are
     * joined by a hyphen, not by a space</strong> - {@code DB2-STREEP-3} at {@code :166} sits between
     * {@code DB2-DD} and {@code DB2-HH}, and {@code :702} moves {@code '-'} into all three separators
     * together.
     *
     * <p>{@code DB2-MIL PIC 9(002)} is fed from {@code COB-MIL}, the hundredths of a second
     * {@code FUNCTION CURRENT-DATE} reports, so the value is never finer than a hundredth however precise
     * the injected clock is - which is why {@code DB2-REST} is a literal rather than a measured value.
     *
     * <p>Every one of the 26 bytes is written by the paragraph, so nothing is carried over from a previous
     * call.
     *
     * @return exactly {@value #DB2_TIMESTAMP_LENGTH} characters
     */
    public String db2FormatTimestamp() {
        // MOVE FUNCTION CURRENT-DATE TO COBOL-TS                                                     L693
        CobolTimestamp current = currentDate();

        // The eight MOVEs at L694-L701, then the separators at L702-L703, in one composition.
        return current.yyyy()                                                                 //     L694
                + DB2_TIMESTAMP_HYPHEN                                                        //     L702
                + current.mm()                                                                //     L695
                + DB2_TIMESTAMP_HYPHEN                                                        //     L702
                + current.dd()                                                                //     L696
                + DB2_TIMESTAMP_HYPHEN                                                        //     L702
                + current.hh()                                                                //     L697
                + DB2_TIMESTAMP_DOT                                                           //     L703
                + current.min()                                                               //     L698
                + DB2_TIMESTAMP_DOT                                                           //     L703
                + current.ss()                                                                //     L699
                + DB2_TIMESTAMP_DOT                                                           //     L703
                + current.mil()                                                               //     L700
                + DB2_TIMESTAMP_REST;                                                         //     L701
    }

    /**
     * {@code FUNCTION CURRENT-DATE} into {@code COBOL-TS} - {@code app/cbl/CBTRN02C.cbl:150-158}.
     *
     * <p>The intrinsic returns {@value #COBOL_TIMESTAMP_LENGTH} characters: {@code YYYY}, {@code MM},
     * {@code DD}, {@code HH}, {@code MM}, {@code SS}, hundredths, and a five-character offset from
     * Greenwich. All eight are modelled even though {@link #db2FormatTimestamp()} reads seven of them,
     * because the eighth occupies declared bytes of {@code COBOL-TS} and a partial model of a group item is
     * a model that cannot be checked.
     *
     * <p>Read in the clock's own zone, because {@code FUNCTION CURRENT-DATE} reports local time. Every
     * component is produced by the numeric mover, so each is zero-filled on the left to its declared width
     * exactly as a {@code PIC 9} receiver would be.
     *
     * @return the current date and time in the clock's own zone; never {@code null}
     */
    public CobolTimestamp currentDate() {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), clock.getZone());
        int offsetSeconds = clock.getZone().getRules().getOffset(clock.instant()).getTotalSeconds();
        return new CobolTimestamp(
                codec.movePic9(now.getYear(), YEAR_WIDTH),
                codec.movePic9(now.getMonthValue(), TWO_DIGIT_WIDTH),
                codec.movePic9(now.getDayOfMonth(), TWO_DIGIT_WIDTH),
                codec.movePic9(now.getHour(), TWO_DIGIT_WIDTH),
                codec.movePic9(now.getMinute(), TWO_DIGIT_WIDTH),
                codec.movePic9(now.getSecond(), TWO_DIGIT_WIDTH),
                codec.movePic9(now.getNano() / NANOS_PER_HUNDREDTH, TWO_DIGIT_WIDTH),
                greenwichOffsetImage(offsetSeconds));
    }

    /**
     * Renders {@code COB-REST PIC X(05)}: the offset from Greenwich as {@code +hhmm} or {@code -hhmm}.
     *
     * <p>Never read by {@code Z-GET-DB2-FORMAT-TIMESTAMP}, and modelled anyway so {@code COBOL-TS} is a
     * whole group item rather than the seven components this program happens to consume.
     *
     * @param offsetSeconds the zone's total offset in seconds, which may be negative
     * @return exactly {@value #GREENWICH_OFFSET_WIDTH} characters
     */
    private String greenwichOffsetImage(int offsetSeconds) {
        String sign = offsetSeconds < 0 ? "-" : "+";
        int magnitude = Math.abs(offsetSeconds);
        int hours = magnitude / SECONDS_PER_HOUR;
        int minutes = (magnitude % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE;
        return sign + codec.movePic9(hours, TWO_DIGIT_WIDTH) + codec.movePic9(minutes, TWO_DIGIT_WIDTH);
    }

    /**
     * The eight components of {@code COBOL-TS} - {@code app/cbl/CBTRN02C.cbl:150-158}.
     *
     * @param yyyy {@code COB-YYYY PIC X(04)} - the year
     * @param mm   {@code COB-MM PIC X(02)} - the month
     * @param dd   {@code COB-DD PIC X(02)} - the day
     * @param hh   {@code COB-HH PIC X(02)} - the hour, {@code 00} to {@code 23}
     * @param min  {@code COB-MIN PIC X(02)} - the minute
     * @param ss   {@code COB-SS PIC X(02)} - the second
     * @param mil  {@code COB-MIL PIC X(02)} - hundredths of a second, the whole of the intrinsic's
     *             sub-second precision
     * @param rest {@code COB-REST PIC X(05)} - the offset from Greenwich, never read by this program
     */
    public record CobolTimestamp(String yyyy, String mm, String dd, String hh, String min, String ss,
                                 String mil, String rest) {

        /**
         * Validates every component's declared width, so a partially composed timestamp cannot exist.
         *
         * @throws NullPointerException     if any component is {@code null}
         * @throws IllegalArgumentException if any component is not exactly its declared width
         */
        public CobolTimestamp {
            requireWidth(yyyy, YEAR_WIDTH, "COB-YYYY");
            requireWidth(mm, TWO_DIGIT_WIDTH, "COB-MM");
            requireWidth(dd, TWO_DIGIT_WIDTH, "COB-DD");
            requireWidth(hh, TWO_DIGIT_WIDTH, "COB-HH");
            requireWidth(min, TWO_DIGIT_WIDTH, "COB-MIN");
            requireWidth(ss, TWO_DIGIT_WIDTH, "COB-SS");
            requireWidth(mil, TWO_DIGIT_WIDTH, "COB-MIL");
            requireWidth(rest, GREENWICH_OFFSET_WIDTH, "COB-REST");
        }

        /**
         * The whole {@value #COBOL_TIMESTAMP_LENGTH}-character group image, in declaration order - which is
         * what {@code FUNCTION CURRENT-DATE} returned.
         *
         * @return the group's characters; never {@code null}
         */
        public String image() {
            return yyyy + mm + dd + hh + min + ss + mil + rest;
        }

        /**
         * Rejects a component whose width does not match its {@code PICTURE}.
         *
         * @param value     the component
         * @param width     its declared width
         * @param cobolName its COBOL name, for the diagnostic
         * @throws NullPointerException     if {@code value} is {@code null}
         * @throws IllegalArgumentException if {@code value} is not exactly {@code width} characters
         */
        private static void requireWidth(String value, int width, String cobolName) {
            Objects.requireNonNull(value, cobolName + " is a character item and holds characters, never "
                    + "null");
            if (value.length() != width) {
                throw new IllegalArgumentException(cobolName + " is PIC X(" + width + "), so it holds "
                        + "exactly " + width + " character(s); '" + value + "' is " + value.length()
                        + ". COBOL-TS is a group item of exactly " + COBOL_TIMESTAMP_LENGTH + " bytes and "
                        + "a component of the wrong width would move every item after it.");
            }
        }
    }

    // =================================================================================================
    // SYSOUT - app/jcl/POSTTRAN.jcl:27.
    // =================================================================================================

    /**
     * Where a {@code DISPLAY} goes.
     *
     * <p>A seam rather than a hard-wired stream, so a unit test and the parity harness can collect the
     * emitted lines and assert them byte for byte - the banners, the two count lines, the
     * {@code '.. Creating.'} notice and every {@code 'ERROR ...'} line are all part of the fingerprint.
     */
    public interface SysoutSink {

        /**
         * Emits one displayed line.
         *
         * @param line the line's characters, exactly as {@code DISPLAY} composed them; never {@code null}
         */
        void write(String line);
    }

    /**
         * The process's standard output, in a named code page.
     *
     * <p>The code page is a parameter and never the platform default, because a displayed record is the
     * dataset's own bytes.
     *
     * @param charset the code page to encode the displayed lines in
     * @return a sink writing one line per call; never {@code null}
     * @throws NullPointerException if {@code charset} is {@code null}
     */
    public static SysoutSink standardOutput(Charset charset) {
        Objects.requireNonNull(charset, "A code page is required for SYSOUT: a displayed record is the "
                + "dataset's own bytes, and the platform default is never assumed");
        PrintStream stream = new PrintStream(new FileOutputStream(FileDescriptor.out), true, charset);
        return stream::println;
    }

    // =================================================================================================
    // What one record did, and what a whole run did - the values a test and the parity differ assert on.
    // =================================================================================================

    /**
     * What processing one daily-transaction record did: the chunk step's output item.
     *
     * <p>It carries no dataset state. Every verb has already run by the time it exists, because relocating
     * a write into the writer stage would reorder it against the {@code SYSOUT} sequence on the failure
     * paths - the COBOL abends inside {@code 2700} or {@code 2900}, before the mainline reaches the next
     * record.
     *
     * @param recordNumber            {@code WS-TRANSACTION-COUNT} after this record, so the first record is
     *                                1
     * @param dalytranId              the record's {@code DALYTRAN-ID}, untrimmed
     * @param validationFailReason    {@code WS-VALIDATION-FAIL-REASON} as the mainline tested it at
     *                                {@code :211} - so {@link #REASON_NONE} for a posted record, and never
     *                                {@link #REASON_ACCOUNT_NOT_FOUND_ON_REWRITE}, which is set afterwards
     * @param validationFailReasonDesc {@code WS-VALIDATION-FAIL-REASON-DESC} at the same instant, padded to
     *                                nothing - the reject writer pads it to its declared 76 characters
     * @param tempBal                 {@code WS-TEMP-BAL} after {@code :403}, at scale
     *                                {@value CobolDecimal#MONETARY_SCALE}, or {@code null} when the
     *                                account lookup never reached the {@code COMPUTE}
     * @param posted                  whether {@code 2000-POST-TRANSACTION} ran
     * @param rejected                whether {@code 2500-WRITE-REJECT-REC} ran, which is also the only
     *                                thing that increments {@code WS-REJECT-COUNT}
     * @param tranCatBalCreated       whether {@code 2700-A-CREATE-TCATBAL-REC} ran rather than
     *                                {@code 2700-B}
     * @param accountRewriteInvalidKey whether the account {@code REWRITE} at {@code :554} reported the
     *                                {@code INVALID KEY} condition and therefore set reason 109. It is
     *                                surfaced <strong>only</strong> so a test can prove that the record was
     *                                still not rejected and the reject count still not incremented; see the
     *                                class documentation's preserved-defect list
     * @param procTimestamp           the 26-character {@code TRAN-PROC-TS} written, or {@code null} when
     *                                nothing was posted
     */
    public record RecordOutcome(long recordNumber,
                                String dalytranId,
                                int validationFailReason,
                                String validationFailReasonDesc,
                                BigDecimal tempBal,
                                boolean posted,
                                boolean rejected,
                                boolean tranCatBalCreated,
                                boolean accountRewriteInvalidKey,
                                String procTimestamp) {

        /**
         * Enforces the invariants a record outcome cannot violate.
         *
         * @throws NullPointerException     if {@code dalytranId} or {@code validationFailReasonDesc} is
         *                                  {@code null}
         * @throws IllegalArgumentException if the record was both posted and rejected, or neither, or if a
         *                                  posted record carries a non-zero reason
         */
        public RecordOutcome {
            Objects.requireNonNull(dalytranId, "A record outcome names the DALYTRAN-ID it processed");
            Objects.requireNonNull(validationFailReasonDesc, "A record outcome carries the description "
                    + "the trailer would receive; MOVE SPACES is the empty string, never null");
            if (posted == rejected) {
                throw new IllegalArgumentException("app/cbl/CBTRN02C.cbl:211-216 is one IF with two arms, "
                        + "so exactly one of 2000-POST-TRANSACTION and 2500-WRITE-REJECT-REC runs for "
                        + "every record read. posted=" + posted + " rejected=" + rejected + " describes "
                        + "neither arm.");
            }
            if (posted && validationFailReason != REASON_NONE) {
                throw new IllegalArgumentException("A posted record took the WS-VALIDATION-FAIL-REASON = 0 "
                        + "arm at app/cbl/CBTRN02C.cbl:211, so the reason it was tested with is 0; "
                        + validationFailReason + " is not. Reason 109, which 2800-UPDATE-ACCOUNT-REC may "
                        + "set afterwards, is reported through accountRewriteInvalidKey rather than here, "
                        + "because the mainline had already routed the record by then.");
            }
        }

        /**
         * {@code WS-TEMP-BAL} if the {@code COMPUTE} at {@code :403} was reached.
         *
         * @return the computed temporary balance, or empty when the cascade stopped before it
         */
        public Optional<BigDecimal> temporaryBalance() {
            return Optional.ofNullable(tempBal);
        }

        /**
         * The {@code TRAN-PROC-TS} written, if anything was posted.
         *
         * @return the 26-character timestamp, or empty for a rejected record
         */
        public Optional<String> processingTimestamp() {
            return Optional.ofNullable(procTimestamp);
        }
    }

    /**
     * What a whole run did - the values {@code app/cbl/CBTRN02C.cbl:227-231} displays and decides on.
     *
     * @param transactionCount {@code WS-TRANSACTION-COUNT} at {@code GOBACK}
     * @param rejectCount      {@code WS-REJECT-COUNT} at {@code GOBACK}
     * @param returnCode       {@link #RETURN_CODE_REJECTS_PRESENT} when {@code rejectCount} is positive,
     *                         otherwise {@link #RETURN_CODE_CLEAN}
     */
    public record RunOutcome(long transactionCount, long rejectCount, int returnCode) {

        /**
         * Enforces the two invariants of {@code :229-231}.
         *
         * @throws IllegalArgumentException if either count is negative, if the reject count exceeds the
         *                                  transaction count, or if the return code does not follow from
         *                                  the reject count
         */
        public RunOutcome {
            if (transactionCount < 0 || rejectCount < 0) {
                throw new IllegalArgumentException("WS-TRANSACTION-COUNT and WS-REJECT-COUNT are both "
                        + "PIC 9(09), an unsigned picture with no sign position, so neither can be "
                        + "negative: " + transactionCount + " / " + rejectCount);
            }
            if (rejectCount > transactionCount) {
                throw new IllegalArgumentException("Every reject is one of the records counted at "
                        + "app/cbl/CBTRN02C.cbl:206, so WS-REJECT-COUNT can never exceed "
                        + "WS-TRANSACTION-COUNT: " + rejectCount + " > " + transactionCount);
            }
            int required = rejectCount > 0 ? RETURN_CODE_REJECTS_PRESENT : RETURN_CODE_CLEAN;
            if (returnCode != required) {
                throw new IllegalArgumentException("app/cbl/CBTRN02C.cbl:229-231 moves "
                        + RETURN_CODE_REJECTS_PRESENT + " to RETURN-CODE when WS-REJECT-COUNT is greater "
                        + "than zero and leaves it at " + RETURN_CODE_CLEAN + " otherwise, so "
                        + rejectCount + " reject(s) require return code " + required + ", not "
                        + returnCode);
            }
        }
    }

    // =================================================================================================
    // WORKING-STORAGE SECTION - app/cbl/CBTRN02C.cbl:99-190.
    //
    // An instance per run, never a static field (practice B9, gate G53): two concurrent executions must
    // not share a counter, and a parity case must not depend on what ran before it.
    // =================================================================================================

    /**
     * One run's {@code WORKING-STORAGE}: {@code APPL-RESULT}, {@code END-OF-FILE}, the six
     * {@code FILE STATUS} items, {@code WS-VALIDATION-TRAILER}, {@code WS-COUNTERS} and {@code WS-FLAGS}.
     *
     * <p>Every mutation is a named method carrying the COBOL statement it reproduces, so the register's
     * transitions are auditable against the source rather than inferred from assignments.
     *
     * <p><strong>{@code WS-TEMP-BAL} is a {@link BigDecimal} at scale exactly
     * {@value CobolDecimal#MONETARY_SCALE}</strong>, because {@code :187} declares it
     * {@code PIC S9(09)V99}. It is never a binary floating-point primitive, and its store names both its
     * scale and its rounding mode (gates G22, G23, G24).
     */
    static final class WorkingStorage {

        /** {@code 01 APPL-RESULT PIC S9(9) COMP} - {@code :142}. */
        private int applResult = APPL_RESULT_AOK;

        /** {@code 01 END-OF-FILE PIC X(01) VALUE 'N'} - {@code :146}. */
        private String endOfFile = NOT_AT_END_OF_FILE;

        /** {@code 01 DALYTRAN-STATUS} - {@code :103}. */
        private String dalytranStatus = FileStatus.OK;

        /** {@code 01 TRANFILE-STATUS} - {@code :108}. */
        private String tranfileStatus = FileStatus.OK;

        /** {@code 01 XREFFILE-STATUS} - {@code :113}. */
        private String xreffileStatus = FileStatus.OK;

        /** {@code 01 DALYREJS-STATUS} - {@code :117}. */
        private String dalyrejsStatus = FileStatus.OK;

        /** {@code 01 ACCTFILE-STATUS} - {@code :122}. */
        private String acctfileStatus = FileStatus.OK;

        /** {@code 01 TCATBALF-STATUS} - {@code :127}. */
        private String tcatbalfStatus = FileStatus.OK;

        /** {@code 05 WS-VALIDATION-FAIL-REASON PIC 9(04)} - {@code :181}. */
        private int validationFailReason = REASON_NONE;

        /** {@code 05 WS-VALIDATION-FAIL-REASON-DESC PIC X(76)} - {@code :182}. */
        private String validationFailReasonDesc = DESC_SPACES;

        /** {@code 05 WS-TRANSACTION-COUNT PIC 9(09) VALUE 0} - {@code :185}. */
        private long transactionCount;

        /** {@code 05 WS-REJECT-COUNT PIC 9(09) VALUE 0} - {@code :186}. */
        private long rejectCount;

        /** {@code 05 WS-TEMP-BAL PIC S9(09)V99} - {@code :187}. */
        private BigDecimal tempBal = CobolDecimal.zero(WS_TEMP_BAL_SCALE);

        /** {@code 05 WS-CREATE-TRANCAT-REC PIC X(01) VALUE 'N'} - {@code :190}. */
        private String createTrancatRec = DO_NOT_CREATE_TRANCAT_REC;

        /** {@code MOVE n TO APPL-RESULT}. */
        void moveToApplResult(int value) {
            applResult = value;
        }

        /** {@code 88 APPL-AOK VALUE 0}. */
        boolean applAok() {
            return applResult == APPL_RESULT_AOK;
        }

        /** {@code 88 APPL-EOF VALUE 16}. */
        boolean applEof() {
            return applResult == APPL_RESULT_EOF;
        }

        /** The current {@code APPL-RESULT}, for a diagnostic and for an abend's {@code RETURN-CODE}. */
        int applResult() {
            return applResult;
        }

        /** {@code MOVE 'Y' TO END-OF-FILE} - {@code :361}. */
        void moveEndOfFileYes() {
            endOfFile = AT_END_OF_FILE;
        }

        /** {@code IF END-OF-FILE = 'Y'} - the loop's own termination test at {@code :202}. */
        boolean endOfFileIsYes() {
            return AT_END_OF_FILE.equals(endOfFile);
        }

        /** {@code END-OF-FILE}, for a diagnostic. */
        String endOfFile() {
            return endOfFile;
        }

        /** {@code MOVE 0 TO WS-VALIDATION-FAIL-REASON} / {@code MOVE SPACES TO ...-DESC} - {@code :208-209}. */
        void resetValidationTrailer() {
            validationFailReason = REASON_NONE;
            validationFailReasonDesc = DESC_SPACES;
        }

        /**
         * {@code MOVE nnn TO WS-VALIDATION-FAIL-REASON} followed by its paired {@code MOVE '...' TO ...-DESC}.
         *
         * <p>Deliberately unguarded: a second call overwrites the first, which is precisely what
         * {@code :417} does to the value {@code :410} had stored. See the class documentation.
         *
         * @param reason      the reason code
         * @param description its paired description text
         */
        void moveToValidationTrailer(int reason, String description) {
            validationFailReason = reason;
            validationFailReasonDesc = description;
        }

        /** {@code IF WS-VALIDATION-FAIL-REASON = 0} - {@code :211} and {@code :372}. */
        boolean validationFailReasonIsZero() {
            return validationFailReason == REASON_NONE;
        }

        /** {@code WS-VALIDATION-FAIL-REASON}. */
        int validationFailReason() {
            return validationFailReason;
        }

        /** {@code WS-VALIDATION-FAIL-REASON-DESC}. */
        String validationFailReasonDesc() {
            return validationFailReasonDesc;
        }

        /** {@code ADD 1 TO WS-TRANSACTION-COUNT} - {@code :206}. */
        void addOneToTransactionCount() {
            transactionCount++;
        }

        /** {@code WS-TRANSACTION-COUNT}. */
        long transactionCount() {
            return transactionCount;
        }

        /** {@code ADD 1 TO WS-REJECT-COUNT} - {@code :214}. */
        void addOneToRejectCount() {
            rejectCount++;
        }

        /** {@code WS-REJECT-COUNT}. */
        long rejectCount() {
            return rejectCount;
        }

        /**
         * {@code COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT} -
         * {@code :403-405}.
         *
         * <p>The receiver is {@code PIC S9(09)V99} and the statement carries no {@code ROUNDED}, so the
         * store truncates toward zero at scale {@value CobolDecimal#MONETARY_SCALE}. All three operands are
         * already at that scale, so nothing is actually discarded on any reachable path - and the store
         * still names its scale and rounding mode, because that is the property gates G23 and G24 are
         * about.
         *
         * @param cycleCredit {@code ACCT-CURR-CYC-CREDIT}
         * @param cycleDebit  {@code ACCT-CURR-CYC-DEBIT}
         * @param amount      {@code DALYTRAN-AMT}
         * @return the stored {@code WS-TEMP-BAL}
         */
        BigDecimal computeTempBal(BigDecimal cycleCredit, BigDecimal cycleDebit, BigDecimal amount) {
            BigDecimal difference = CobolDecimal.subtract(cycleCredit, cycleDebit, WS_TEMP_BAL_SCALE);
            tempBal = CobolDecimal.storeAtPicture(CobolDecimal.add(difference, amount, WS_TEMP_BAL_SCALE),
                    WS_TEMP_BAL_INTEGER_DIGITS, WS_TEMP_BAL_SCALE);
            return tempBal;
        }

        /** {@code WS-TEMP-BAL}, at scale exactly {@value CobolDecimal#MONETARY_SCALE}. */
        BigDecimal tempBal() {
            return tempBal;
        }

        /** {@code MOVE 'N' TO WS-CREATE-TRANCAT-REC} - {@code :473}. */
        void moveDoNotCreateTrancatRec() {
            createTrancatRec = DO_NOT_CREATE_TRANCAT_REC;
        }

        /** {@code MOVE 'Y' TO WS-CREATE-TRANCAT-REC} - {@code :478}. */
        void moveCreateTrancatRec() {
            createTrancatRec = CREATE_TRANCAT_REC;
        }

        /** {@code IF WS-CREATE-TRANCAT-REC = 'Y'} - {@code :495}. */
        boolean createTrancatRecIsYes() {
            return CREATE_TRANCAT_REC.equals(createTrancatRec);
        }

        /** {@code MOVE ... TO DALYTRAN-STATUS} - what the last {@code DALYTRAN} operation reported. */
        void moveDalytranStatus(String status) {
            dalytranStatus = requireStatus(status, "DALYTRAN-STATUS");
        }

        /** {@code DALYTRAN-STATUS}. */
        String dalytranStatus() {
            return dalytranStatus;
        }

        /** What the last {@value #TRANFILE_DD_NAME} operation reported. */
        void moveTranfileStatus(String status) {
            tranfileStatus = requireStatus(status, "TRANFILE-STATUS");
        }

        /** {@code TRANFILE-STATUS}. */
        String tranfileStatus() {
            return tranfileStatus;
        }

        /** What the last {@value #XREFFILE_DD_NAME} operation reported. */
        void moveXreffileStatus(String status) {
            xreffileStatus = requireStatus(status, "XREFFILE-STATUS");
        }

        /** {@code XREFFILE-STATUS} - also what {@code :649} wrongly displays for the rejects file. */
        String xreffileStatus() {
            return xreffileStatus;
        }

        /** What the last {@value #DALYREJS_DD_NAME} operation reported. */
        void moveDalyrejsStatus(String status) {
            dalyrejsStatus = requireStatus(status, "DALYREJS-STATUS");
        }

        /** {@code DALYREJS-STATUS}. */
        String dalyrejsStatus() {
            return dalyrejsStatus;
        }

        /** What the last {@value #ACCTFILE_DD_NAME} operation reported. */
        void moveAcctfileStatus(String status) {
            acctfileStatus = requireStatus(status, "ACCTFILE-STATUS");
        }

        /** {@code ACCTFILE-STATUS}. */
        String acctfileStatus() {
            return acctfileStatus;
        }

        /** What the last {@value #TCATBALF_DD_NAME} operation reported. */
        void moveTcatbalfStatus(String status) {
            tcatbalfStatus = requireStatus(status, "TCATBALF-STATUS");
        }

        /** {@code TCATBALF-STATUS}. */
        String tcatbalfStatus() {
            return tcatbalfStatus;
        }

        /**
         * Refuses a status that is not two characters, because a {@code FILE STATUS} item is
         * {@code PIC XX} and a shorter or longer value would render wrongly in
         * {@code 9910-DISPLAY-IO-STATUS}.
         *
         * @param status    the reported status
         * @param cobolName the item's COBOL name, for the diagnostic
         * @return {@code status}
         * @throws NullPointerException     if {@code status} is {@code null}
         * @throws IllegalArgumentException if it is not exactly {@value FileStatus#STATUS_LENGTH}
         *                                  characters
         */
        private static String requireStatus(String status, String cobolName) {
            Objects.requireNonNull(status, cobolName + " is a two-character item and always holds a "
                    + "reported status, never null");
            if (status.length() != FileStatus.STATUS_LENGTH) {
                throw new IllegalArgumentException(cobolName + " is two one-character items, so it holds "
                        + "exactly " + FileStatus.STATUS_LENGTH + " character(s); '" + status + "' is "
                        + status.length());
            }
            return status;
        }
    }

    // =================================================================================================
    // One run of the program: one WORKING-STORAGE, six open files, and the paragraphs that use them.
    // =================================================================================================

    /**
     * One execution of {@code CBTRN02C} - its {@code WORKING-STORAGE}, its six open files and every
     * paragraph of its {@code PROCEDURE DIVISION}, each a named method carrying the source lines it
     * reproduces.
     *
     * <p>An instance per step execution, built by {@link TransactionValidationJob#newRun(SysoutSink)}. A
     * COBOL program's storage belongs to its execution, so two runs never share a counter, a file handle or
     * a record area.
     *
     * <p><strong>The five record areas are {@code WORKING-STORAGE} items, not {@code FD} areas.</strong>
     * {@code READ ... INTO} transfers into them only on success, so a failed read leaves the previous
     * record in place. That is reproduced by replacing an area only when a read carries a record and never
     * clearing one - which is what makes {@code INITIALIZE TRAN-CAT-BAL-RECORD} at {@code :504} inherit the
     * previous record's 22 reserved bytes, exactly as the mainframe does.
     *
     * <p>The initial content of an area that has never received a record is
     * <em>implementation-defined</em> in COBOL. It is modelled here as a fully allocated record of zeros
     * and spaces, which is deterministic, testable, and reachable only if the very first keyed read of a run
     * reports a permanent error - a path on which the program's own behaviour is undefined too.
     */
    static final class PostingRun {

        /** The job whose collaborators this run uses. */
        private final TransactionValidationJob job;

        /** Where this run's {@code DISPLAY} output goes. */
        private final SysoutSink sysout;

        /** This run's {@code WORKING-STORAGE}. */
        private final WorkingStorage workingStorage = new WorkingStorage();

        /** {@code DALYTRAN-FILE} - {@code OPEN INPUT} at {@code :238}. */
        private DalyTranRepository.DalytranFile dalytranFile;

        /** {@code TRANSACT-FILE} - {@code OPEN OUTPUT} at {@code :256}; see {@link #tranfileOpen()}. */
        private TransactionRepository.InputFile tranfile;

        /** {@code XREF-FILE} - {@code OPEN INPUT} at {@code :275}. */
        private CardXrefRepository.BrowseCursor xreffile;

        /** {@code DALYREJS-FILE} - {@code OPEN OUTPUT} at {@code :293}. */
        private DalyRejectWriter.RejectsFile dalyrejs;

        /** {@code ACCOUNT-FILE} - {@code OPEN I-O} at {@code :311}. */
        private AccountRepository.AccountFile acctfile;

        /** {@code TCATBAL-FILE} - {@code OPEN I-O} at {@code :329}. */
        private TranCatBalRepository.TranCatBalFile tcatbalf;

        /** {@code 01 CARD-XREF-RECORD} - the {@code COPY CVACT03Y} area at {@code :112}. */
        private CardXrefRecord cardXrefRecord;

        /** {@code 01 ACCOUNT-RECORD} - the {@code COPY CVACT01Y} area at {@code :121}. */
        private AccountRecord accountRecord;

        /** {@code 01 TRAN-CAT-BAL-RECORD} - the {@code COPY CVTRA01Y} area at {@code :126}. */
        private TranCatBalRecord tranCatBalRecord;

        /** {@code 01 TRAN-RECORD} - the {@code COPY CVTRA05Y} area at {@code :107}. */
        private final TranRecord tranRecord;

        /**
         * Whether the six {@code CLOSE} paragraphs completed, which is what decides the abnormal
         * disposition of the {@code DISP=(NEW,CATLG,DELETE)} rejects generation.
         */
        private boolean closedNormally;

        /**
         * Allocates the run's storage. No file is opened here: {@code :195-200} opens them, and a run that
         * abends during the opens must have opened only the ones before the failure.
         *
         * @param job    the job whose collaborators this run uses
         * @param sysout where {@code DISPLAY} goes
         * @throws NullPointerException if either argument is {@code null}
         */
        PostingRun(TransactionValidationJob job, SysoutSink sysout) {
            this.job = Objects.requireNonNull(job, "A job is required to run one of its executions");
            this.sysout = Objects.requireNonNull(sysout, "A SYSOUT sink is required: this program's "
                    + "DISPLAY statements are part of its observable behaviour, so there is no run "
                    + "without somewhere for them to go");
            Charset charset = job.datasetCharset;
            this.cardXrefRecord = new CardXrefRecord("", 0, 0L);
            this.accountRecord = new AccountRecord(charset);
            this.tranCatBalRecord = TranCatBalRecord.newInstance(charset);
            this.tranRecord = new TranRecord(charset);
        }

        /**
         * This run's {@code WORKING-STORAGE}.
         *
         * @return the register; never {@code null}
         */
        WorkingStorage workingStorage() {
            return workingStorage;
        }

        /**
         * Where this run's {@code DISPLAY} output goes.
         *
         * @return the sink; never {@code null}
         */
        SysoutSink sysout() {
            return sysout;
        }

        /**
         * The {@code CARD-XREF-RECORD} area as it stands - the record the last successful
         * {@code 1500-A-LOOKUP-XREF} transferred.
         *
         * @return the area's content; never {@code null}
         */
        CardXrefRecord cardXrefRecord() {
            return cardXrefRecord;
        }

        /**
         * The {@code ACCOUNT-RECORD} area as it stands, including any balance {@code 2800} has added to.
         *
         * @return the area's content; never {@code null}
         */
        AccountRecord accountRecord() {
            return accountRecord;
        }

        /**
         * The {@code TRAN-CAT-BAL-RECORD} area as it stands.
         *
         * @return the area's content; never {@code null}
         */
        TranCatBalRecord tranCatBalRecord() {
            return tranCatBalRecord;
        }

        /**
         * The {@code TRAN-RECORD} area as it stands - what {@code 2900} would write.
         *
         * @return the area's content; never {@code null}
         */
        TranRecord tranRecord() {
            return tranRecord;
        }

        // ---------------------------------------------------------------------------------------------
        // The six OPEN paragraphs - app/cbl/CBTRN02C.cbl:236-343, performed in order at :195-200.
        // ---------------------------------------------------------------------------------------------

        /**
         * Performs the six opens in the source's order: {@code app/cbl/CBTRN02C.cbl:195-200}.
         *
         * <p>The order matters because each failure emits a different message, so a run that failed on the
         * third open must have emitted nothing from the fourth, fifth or sixth.
         *
         * @throws AbendException if any open reports a status other than {@code '00'}
         */
        void openFiles() {
            dalytranOpen();                                                                   //     L195
            tranfileOpen();                                                                   //     L196
            xreffileOpen();                                                                   //     L197
            dalyrejsOpen();                                                                   //     L198
            acctfileOpen();                                                                   //     L199
            tcatbalfOpen();                                                                   //     L200
        }

        /**
         * {@code 0000-DALYTRAN-OPEN} - {@code app/cbl/CBTRN02C.cbl:236-252}, an {@code OPEN INPUT} over the
         * sequential daily-transaction file.
         *
         * @throws AbendException if the open does not report {@code '00'}
         */
        void dalytranOpen() {
            workingStorage.moveToApplResult(APPL_RESULT_ASSUMED_FAILURE);                     //     L237
            dalytranFile = job.dalyTranRepository.open();                                     //     L238
            String status = dalytranFile.openStatus();
            workingStorage.moveDalytranStatus(status);
            applResultFromOkStatus(status);                                                   // L239-243
            if (!workingStorage.applAok()) {                                                  //     L244
                throw reportAndAbend(ERROR_OPENING_DALYTRAN, status);                         // L247-250
            }
        }

        /**
         * {@code 0100-TRANFILE-OPEN} - {@code app/cbl/CBTRN02C.cbl:254-270}.
         *
         * <p><strong>The source verb is {@code OPEN OUTPUT} on an indexed file with
         * {@code ACCESS MODE IS RANDOM} ({@code :34-38}), which is VSAM load mode - and this method models
         * it as a reachability probe over the same DD name and the same dataset, without emptying
         * anything.</strong> Two reasons, both recorded rather than resolved by inventing a verb (practice
         * B4):
         *
         * <ul>
         *   <li>{@link TransactionRepository} publishes no master-output open. Its documentation states
         *       that {@code CBTRN02C} is "served by {@code write(TranRecord)}" - the keyed add - and its
         *       {@code openOutput} pair addresses the completely different {@code SYSTRAN} generation of
         *       {@code app/jcl/INTCALC.jcl}. Opening the master through
         *       {@link TransactionRepository#openInput()} resolves the destination named by
         *       {@value #TRANFILE_DD_NAME} and reports whether it can be reached, which is precisely the
         *       fact the guard chain at {@code :257-269} branches on.</li>
         *   <li>Emptying the dataset would be a data-definition or bulk-delete statement over a
         *       {@code DISP=SHR} production KSDS, and gate <strong>G44</strong> forbids any such statement
         *       anywhere in this module.</li>
         * </ul>
         *
         * <p>The observable consequence is confined and faithful: a record whose {@code TRAN-ID} already
         * exists reports the duplicate status {@code '22'}, which {@code 2900-WRITE-TRANSACTION-FILE}
         * treats as {@link #APPL_RESULT_FATAL} exactly as it treats every status other than {@code '00'}
         * ({@code :566-570}), so the guard chain the source wrote is the guard chain that runs.
         *
         * @throws AbendException if the open does not report {@code '00'}
         */
        void tranfileOpen() {
            workingStorage.moveToApplResult(APPL_RESULT_ASSUMED_FAILURE);                     //     L255
            tranfile = job.transactionRepository.openInput();                                 //     L256
            String status = tranfile.openStatus();
            workingStorage.moveTranfileStatus(status);
            applResultFromOkStatus(status);                                                   // L257-261
            if (!workingStorage.applAok()) {                                                  //     L262
                throw reportAndAbend(ERROR_OPENING_TRANFILE, status);                         // L265-268
            }
        }

        /**
         * {@code 0200-XREFFILE-OPEN} - {@code app/cbl/CBTRN02C.cbl:273-289}, an {@code OPEN INPUT} over the
         * cross-reference cluster.
         *
         * <p>The open is a cursor over the base cluster and the reads that follow are keyed, which is what
         * an {@code OPEN INPUT} of an {@code ORGANIZATION INDEXED ACCESS MODE IS RANDOM} file is: the file
         * is made available, and each {@code READ} then addresses one record by its key.
         *
         * @throws AbendException if the open does not report {@code '00'}
         */
        void xreffileOpen() {
            workingStorage.moveToApplResult(APPL_RESULT_ASSUMED_FAILURE);                     //     L274
            xreffile = job.cardXrefRepository.openBrowse();                                   //     L275
            String status = xreffile.openStatus();
            workingStorage.moveXreffileStatus(status);
            applResultFromOkStatus(status);                                                   // L276-280
            if (!workingStorage.applAok()) {                                                  //     L281
                throw reportAndAbend(ERROR_OPENING_XREFFILE, status);                         // L284-287
            }
        }

        /**
         * {@code 0300-DALYREJS-OPEN} - {@code app/cbl/CBTRN02C.cbl:291-307}.
         *
         * <p>Performed on every run, including one whose input is entirely clean and which therefore writes
         * no reject at all - the {@code DISP=(NEW,CATLG,DELETE)} generation of
         * {@code app/jcl/POSTTRAN.jcl:34-38} is created by the step whether or not a record reaches it.
         *
         * @throws AbendException if the open does not report {@code '00'}
         */
        void dalyrejsOpen() {
            workingStorage.moveToApplResult(APPL_RESULT_ASSUMED_FAILURE);                     //     L292
            dalyrejs = job.dalyRejectWriter.openOutput();                                     //     L293
            String status = statusOf(dalyrejs.openOutcome());
            workingStorage.moveDalyrejsStatus(status);
            applResultFromOkStatus(status);                                                   // L294-298
            if (!workingStorage.applAok()) {                                                  //     L299
                throw reportAndAbend(ERROR_OPENING_DALYREJS, status);                         // L302-305
            }
        }

        /**
         * {@code 0400-ACCTFILE-OPEN} - {@code app/cbl/CBTRN02C.cbl:309-325}, an {@code OPEN I-O} because
         * {@code 2800-UPDATE-ACCOUNT-REC} rewrites through it.
         *
         * @throws AbendException if the open does not report {@code '00'}
         */
        void acctfileOpen() {
            workingStorage.moveToApplResult(APPL_RESULT_ASSUMED_FAILURE);                     //     L310
            acctfile = job.accountRepository.open(AccountRepository.OpenMode.I_O);             //     L311
            String status = acctfile.openStatus();
            workingStorage.moveAcctfileStatus(status);
            applResultFromOkStatus(status);                                                   // L312-316
            if (!workingStorage.applAok()) {                                                  //     L317
                throw reportAndAbend(ERROR_OPENING_ACCTFILE, status);                         // L320-323
            }
        }

        /**
         * {@code 0500-TCATBALF-OPEN} - {@code app/cbl/CBTRN02C.cbl:327-343}, an {@code OPEN I-O} because
         * {@code 2700} both writes and rewrites through it.
         *
         * @throws AbendException if the open does not report {@code '00'}
         */
        void tcatbalfOpen() {
            workingStorage.moveToApplResult(APPL_RESULT_ASSUMED_FAILURE);                     //     L328
            tcatbalf = job.tranCatBalRepository.open(TranCatBalRepository.OpenMode.I_O);       //     L329
            String status = tcatbalf.openStatus();
            workingStorage.moveTcatbalfStatus(status);
            applResultFromOkStatus(status);                                                   // L330-334
            if (!workingStorage.applAok()) {                                                  //     L335
                throw reportAndAbend(ERROR_OPENING_TCATBALF, status);                         // L338-341
            }
        }

        // ---------------------------------------------------------------------------------------------
        // 1000-DALYTRAN-GET-NEXT - app/cbl/CBTRN02C.cbl:345-369.
        // ---------------------------------------------------------------------------------------------

        /**
         * Reads the next daily transaction: {@code READ DALYTRAN-FILE INTO DALYTRAN-RECORD} at {@code :346}.
         *
         * <p>The three-armed status test of {@code :347-356} is the whole paragraph: {@code '00'} is
         * {@link #APPL_RESULT_AOK}, {@code '10'} is {@link #APPL_RESULT_EOF} and therefore
         * {@code MOVE 'Y' TO END-OF-FILE} at {@code :361}, and anything else is
         * {@link #APPL_RESULT_FATAL} and abends with {@value #ERROR_READING_DALYTRAN}.
         *
         * <p>Returns {@code null} at end of file, which is both what {@code MOVE 'Y' TO END-OF-FILE} means
         * and what the {@code ItemReader} contract uses to end a step.
         *
         * @return the record read, or {@code null} at end of file
         * @throws IllegalStateException if the files were never opened
         * @throws AbendException        if the read reports a status that is neither {@code '00'} nor
         *                               {@code '10'}
         */
        DalyTranRecord dalytranGetNext() {
            requireOpen();
            DalyTranRepository.ReadResult result = dalytranFile.readNext();                   //     L346
            String status = result.status();
            workingStorage.moveDalytranStatus(status);
            if (FileStatus.OK.equals(status)) {                                               //     L347
                workingStorage.moveToApplResult(APPL_RESULT_AOK);                             //     L348
            } else if (FileStatus.END_OF_FILE.equals(status)) {                               //     L351
                workingStorage.moveToApplResult(APPL_RESULT_EOF);                             //     L352
            } else {
                workingStorage.moveToApplResult(APPL_RESULT_FATAL);                           //     L354
            }
            if (workingStorage.applAok()) {                                                   //     L357
                return result.dalyTran().orElseThrow(() -> new IllegalStateException(
                        "A read of " + DALYTRAN_DD_NAME + " reported file status " + FileStatus.OK
                                + " with no decoded record, so READ ... INTO DALYTRAN-RECORD had nothing "
                                + "to transfer. Only the success arm carries a record, and it always "
                                + "does."));
            }
            if (workingStorage.applEof()) {                                                   //     L360
                workingStorage.moveEndOfFileYes();                                            //     L361
                return null;
            }
            throw reportAndAbend(ERROR_READING_DALYTRAN, status);                             // L363-366
        }

        // ---------------------------------------------------------------------------------------------
        // The mainline record body - app/cbl/CBTRN02C.cbl:206-216.
        // ---------------------------------------------------------------------------------------------

        /**
         * Everything the mainline does with one record: {@code app/cbl/CBTRN02C.cbl:206-216}.
         *
         * <pre>
         * ADD 1 TO WS-TRANSACTION-COUNT                                                          L206
         * MOVE 0 TO WS-VALIDATION-FAIL-REASON                                                    L208
         * MOVE SPACES TO WS-VALIDATION-FAIL-REASON-DESC                                          L209
         * PERFORM 1500-VALIDATE-TRAN                                                             L210
         * IF WS-VALIDATION-FAIL-REASON = 0                                                       L211
         *   PERFORM 2000-POST-TRANSACTION                                                        L212
         * ELSE                                                                                   L213
         *   ADD 1 TO WS-REJECT-COUNT                                                             L214
         *   PERFORM 2500-WRITE-REJECT-REC                                                        L215
         * END-IF                                                                                 L216
         * </pre>
         *
         * <p>The count at {@code :206} is incremented <strong>before</strong> the validation, so a record
         * that abends inside the posting sequence has still been counted - which is what the trailer of an
         * abended run would have reported had it been reached.
         *
         * <p>The reason and its description are reset at {@code :208-209} before <em>every</em> validation,
         * so no reason can leak from one record to the next.
         *
         * <p>The reason recorded in the returned outcome is the value the {@code IF} at {@code :211} tested,
         * captured before {@code 2000-POST-TRANSACTION} runs. That is what makes reason 109 - which
         * {@code 2800-UPDATE-ACCOUNT-REC} may set afterwards - visibly unable to change this record's fate.
         *
         * @param item the record {@link #dalytranGetNext()} returned; must not be {@code null}
         * @return what this record did; never {@code null}
         * @throws NullPointerException  if {@code item} is {@code null}
         * @throws IllegalStateException if the files were never opened
         * @throws AbendException        if any file operation this record triggers reports a fatal status
         */
        RecordOutcome processRecord(DalyTranRecord item) {
            Objects.requireNonNull(item, "A daily-transaction record is required to process one");
            requireOpen();

            workingStorage.addOneToTransactionCount();                                        //     L206
            workingStorage.resetValidationTrailer();                                          // L208-209
            validateTran(item);                                                               //     L210

            int reasonAsTested = workingStorage.validationFailReason();
            String descAsTested = workingStorage.validationFailReasonDesc();
            BigDecimal tempBalAsComputed = reachedCreditLimitTest(reasonAsTested)
                    ? workingStorage.tempBal()
                    : null;

            if (workingStorage.validationFailReasonIsZero()) {                                //     L211
                PostingResult posting = postTransaction(item);                                //     L212
                return new RecordOutcome(workingStorage.transactionCount(), item.dalytranId(),
                        reasonAsTested, descAsTested, tempBalAsComputed, true, false,
                        posting.tranCatBalCreated(), posting.accountRewriteInvalidKey(),
                        posting.procTimestamp());
            }
            workingStorage.addOneToRejectCount();                                             //     L214
            writeRejectRec(item);                                                             //     L215
            return new RecordOutcome(workingStorage.transactionCount(), item.dalytranId(),
                    reasonAsTested, descAsTested, tempBalAsComputed, false, true, false, false, null);
        }

        /**
         * Whether the cascade got as far as the {@code COMPUTE} at {@code :403}, and {@code WS-TEMP-BAL} is
         * therefore this record's value rather than the previous record's.
         *
         * <p>{@code WS-TEMP-BAL} is {@code WORKING-STORAGE} and is never reset, so on a record rejected at
         * stage 1 or stage 2 it still holds whatever the last account lookup computed. Reporting it as
         * absent for those records is a property of the <em>outcome type</em>, not a change to the register:
         * {@link WorkingStorage#tempBal()} still returns the stale value, exactly as the COBOL item would.
         *
         * @param reason the reason the mainline tested
         * @return {@code true} unless the record was rejected before the account was read
         */
        private boolean reachedCreditLimitTest(int reason) {
            return reason != REASON_INVALID_CARD_NUMBER && reason != REASON_ACCOUNT_RECORD_NOT_FOUND;
        }

        // ---------------------------------------------------------------------------------------------
        // 1500-VALIDATE-TRAN, 1500-A-LOOKUP-XREF and 1500-B-LOOKUP-ACCT - app/cbl/CBTRN02C.cbl:370-422.
        // ---------------------------------------------------------------------------------------------

        /**
         * {@code 1500-VALIDATE-TRAN} - {@code app/cbl/CBTRN02C.cbl:370-378}.
         *
         * <pre>
         * PERFORM 1500-A-LOOKUP-XREF.                                                            L371
         * IF WS-VALIDATION-FAIL-REASON = 0                                                        L372
         *    PERFORM 1500-B-LOOKUP-ACCT                                                           L373
         * ELSE                                                                                    L374
         *    CONTINUE                                                                             L375
         * END-IF                                                                                  L376
         * </pre>
         *
         * <p><strong>This is the only short-circuit in the cascade.</strong> A card-lookup failure means the
         * account is never read. The two tests inside {@code 1500-B} do <em>not</em> short-circuit each
         * other; see {@link #lookupAcct(DalyTranRecord)}.
         *
         * <p>The comment at {@code :377} reads {@code * ADD MORE VALIDATIONS HERE}. Nothing is added: this is
         * a like-for-like migration, and a fifth stage would be a new business rule.
         *
         * @param item the record being validated
         * @throws AbendException if a read reports a status the paragraph's own guards make fatal
         */
        void validateTran(DalyTranRecord item) {
            lookupXref(item);                                                                 //     L371
            if (workingStorage.validationFailReasonIsZero()) {                                //     L372
                lookupAcct(item);                                                             //     L373
            }
            // ELSE CONTINUE at L374-375 - explicitly nothing, and explicitly not an early return from the
            // enclosing mainline: the reject branch is the mainline's own IF at L211.
        }

        /**
         * {@code 1500-A-LOOKUP-XREF} - {@code app/cbl/CBTRN02C.cbl:380-392}.
         *
         * <pre>
         * MOVE DALYTRAN-CARD-NUM TO FD-XREF-CARD-NUM                                             L382
         * READ XREF-FILE INTO CARD-XREF-RECORD                                                    L383
         *    INVALID KEY                                                                          L384
         *      MOVE 100 TO WS-VALIDATION-FAIL-REASON                                              L385
         *      MOVE 'INVALID CARD NUMBER FOUND' TO WS-VALIDATION-FAIL-REASON-DESC              L386-387
         *    NOT INVALID KEY                                                                       L388
         *        CONTINUE                                                                          L390
         * END-READ                                                                                 L391
         * </pre>
         *
         * <p><strong>The key is {@code FD-XREF-CARD-NUM}, the {@code RECORD KEY} of {@code :43} - the base
         * cluster's 16-byte card number.</strong> It is read through
         * {@link CardXrefRepository#readByCardNumber(String)} and never through the alternate-index finder:
         * the alternate index is a different <em>access path</em> over the same one cluster, not a different
         * table, and reading through it here would address the dataset by the wrong key (gate G45).
         *
         * <p>There is <strong>no status guard</strong> in this paragraph - only the two {@code READ} phrases.
         * So a status that is neither success nor the invalid-key condition takes <em>neither</em> arm: the
         * reason stays 0, the record area is not replaced, and {@code 1500-B} runs against whatever the area
         * already held. That is what the source does and it is preserved rather than closed; the window is
         * reachable only through a permanent I/O error, on which the COBOL's own behaviour is undefined.
         *
         * @param item the record being validated
         */
        void lookupXref(DalyTranRecord item) {
            String cardNumber = item.dalytranCardNum();                                       //     L382
            CardXrefRepository.ReadResult result =
                    job.cardXrefRepository.readByCardNumber(cardNumber);                      //     L383
            workingStorage.moveXreffileStatus(result.status());
            if (result.isNotFound()) {                                                        //     L384
                workingStorage.moveToValidationTrailer(REASON_INVALID_CARD_NUMBER,             // L385-387
                        DalyRejectWriter.DESC_INVALID_CARD_NUMBER);
                return;
            }
            // NOT INVALID KEY -> CONTINUE (L388-390). The READ ... INTO transfers only here, which is what
            // leaves the previous record in place on any other outcome.
            result.record().ifPresent(record -> cardXrefRecord = record);
        }

        /**
         * {@code 1500-B-LOOKUP-ACCT} - {@code app/cbl/CBTRN02C.cbl:393-422}.
         *
         * <pre>
         * MOVE XREF-ACCT-ID TO FD-ACCT-ID                                                         L394
         * READ ACCOUNT-FILE INTO ACCOUNT-RECORD                                                    L395
         *    INVALID KEY                                                                            L396
         *      MOVE 101 TO WS-VALIDATION-FAIL-REASON                                                L397
         *      MOVE 'ACCOUNT RECORD NOT FOUND' TO WS-VALIDATION-FAIL-REASON-DESC              L398-399
         *    NOT INVALID KEY                                                                        L400
         *      COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT L403-405
         *      IF ACCT-CREDIT-LIMIT &gt;= WS-TEMP-BAL   CONTINUE                                L407-408
         *      ELSE MOVE 102 ... 'OVERLIMIT TRANSACTION'                                       L410-412
         *      END-IF                                                                               L413
         *      IF ACCT-EXPIRAION-DATE &gt;= DALYTRAN-ORIG-TS (1:10)   CONTINUE                  L414-415
         *      ELSE MOVE 103 ... 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION'                  L417-419
         *      END-IF                                                                               L420
         * END-READ                                                                                  L421
         * </pre>
         *
         * <h2>The two tests are independent, and 103 overwrites 102 - deliberately</h2>
         *
         * <p>{@code :413} closes the credit-limit {@code IF} and {@code :414} opens a second, unrelated one.
         * The expiration test is <strong>not</strong> guarded by the credit-limit outcome, so when both fail
         * the {@code MOVE 103} at {@code :417} overwrites the 102 that {@code :410} stored, and the
         * description is overwritten with it. <strong>Last writer wins.</strong>
         *
         * <p><strong>Do not add an early return, an {@code else}, or a guard after the credit-limit
         * failure.</strong> Gate G31's "no implicit fall-through" means no stage's reject text may leak into
         * another stage's outcome and no stage may be silently skipped - it does not license inserting a
         * guard the COBOL lacks. A record that is both over limit and past expiry is rejected as 103 on the
         * mainframe, and must be rejected as 103 here.
         *
         * <h2>The expiration comparison is a character comparison</h2>
         *
         * <p>{@code ACCT-EXPIRAION-DATE} - the copybook really does misspell it, and the misspelling is
         * preserved because field-for-field diffing depends on the name (AAP implicit requirement I1) - is
         * {@code PIC X(10)}, and {@code DALYTRAN-ORIG-TS (1:10)} is the first ten characters of a
         * {@code PIC X(26)} item. Both hold {@code yyyy-MM-dd}, for which character order and chronological
         * order coincide, so the comparison is left as the plain character comparison the COBOL performs.
         * Parsing either side into a date type would introduce a way to fail on data COBOL compares happily,
         * such as a blank or partial date.
         *
         * @param item the record being validated
         */
        void lookupAcct(DalyTranRecord item) {
            long accountId = cardXrefRecord.xrefAcctId();                                     //     L394
            AccountRepository.ReadResult result = acctfile.readByKey(accountId);              //     L395
            workingStorage.moveAcctfileStatus(result.status());
            if (result.isNotFound()) {                                                        //     L396
                workingStorage.moveToValidationTrailer(REASON_ACCOUNT_RECORD_NOT_FOUND,        // L397-399
                        DalyRejectWriter.DESC_ACCOUNT_RECORD_NOT_FOUND);
                return;
            }
            if (result.isFound()) {
                // READ ... INTO ACCOUNT-RECORD transfers only on the success arm.
                accountRecord = result.account().orElseThrow(() -> new IllegalStateException(
                        "A read of " + ACCTFILE_DD_NAME + " reported file status " + FileStatus.OK
                                + " with no decoded record, so READ ... INTO ACCOUNT-RECORD had nothing "
                                + "to transfer."));
            } else {
                // Neither READ phrase applies - the same untaken-arm window 1500-A-LOOKUP-XREF has. The
                // reason stays 0 and the two tests below run against the retained record area, which is
                // what the COBOL does.
                return;
            }

            // COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT   L403-405
            BigDecimal tempBal = workingStorage.computeTempBal(accountRecord.getAcctCurrCycCredit(),
                    accountRecord.getAcctCurrCycDebit(), item.dalytranAmt());

            // IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL  CONTINUE  ELSE  MOVE 102 ...                 L407-413
            if (accountRecord.getAcctCreditLimit().compareTo(tempBal) < 0) {
                workingStorage.moveToValidationTrailer(REASON_OVERLIMIT_TRANSACTION,           // L410-412
                        DalyRejectWriter.DESC_OVERLIMIT_TRANSACTION);
            }

            // IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)  CONTINUE  ELSE MOVE 103 ...     L414-420
            //
            // DELIBERATELY UNGUARDED. This IF is not nested inside the one above and is not reached by an
            // ELSE: when the credit-limit test has just stored 102, this test still runs, and a failure
            // here overwrites 102 with 103. Adding a guard, an else or a return would change which reason
            // a doubly-invalid transaction is rejected with. See this method's documentation.
            if (accountRecord.getAcctExpiraionDate().compareTo(item.dalytranOrigDt()) < 0) {
                workingStorage.moveToValidationTrailer(REASON_TRANSACTION_AFTER_EXPIRATION,    // L417-419
                        DalyRejectWriter.DESC_TRANSACTION_AFTER_EXPIRATION);
            }
        }

        // ---------------------------------------------------------------------------------------------
        // 2000-POST-TRANSACTION - app/cbl/CBTRN02C.cbl:424-444.
        // ---------------------------------------------------------------------------------------------

        /**
         * {@code 2000-POST-TRANSACTION} - {@code app/cbl/CBTRN02C.cbl:424-444}.
         *
         * <p>Twelve {@code MOVE}s from {@code DALYTRAN-*} to {@code TRAN-*} in source order
         * ({@code :425-436}), then {@code Z-GET-DB2-FORMAT-TIMESTAMP} and
         * {@code MOVE DB2-FORMAT-TS TO TRAN-PROC-TS} ({@code :437-438}), then three paragraphs
         * <strong>in this order and no other</strong> ({@code :440-442}):
         * {@code 2700-UPDATE-TCATBAL}, {@code 2800-UPDATE-ACCOUNT-REC},
         * {@code 2900-WRITE-TRANSACTION-FILE}.
         *
         * <p><strong>The order is contractual, not incidental.</strong> The category balance is committed
         * before the account is rewritten and both before the transaction is added, so a failure inside
         * {@code 2700} leaves the account and the master untouched while a failure inside {@code 2900}
         * leaves both already updated. Reordering them would change what a failed posting leaves behind.
         *
         * <p><strong>Every move is a field copy through the model's codec-backed movers</strong>, so the
         * direction of truncation is declared at the call rather than assumed. The three numeric fields -
         * {@code CAT-CD}, {@code AMT} and {@code MERCHANT-ID} - are copied as their stored <em>images</em>
         * rather than as decoded values: sender and receiver share a {@code PICTURE} in every case
         * ({@code CVTRA06Y} and {@code CVTRA05Y} are the same fourteen spans), so a COBOL {@code MOVE}
         * transfers the digits and the sign unchanged, and the image form is the only one that carries a
         * negative zero across without re-signing it.
         *
         * <p>{@code TRAN-RECORD} is a {@code WORKING-STORAGE} item and its trailing {@code FILLER X(20)} is
         * not one of the twelve receivers, so those twenty reserved bytes keep the spaces the area was
         * allocated with - which is what makes the written record exactly
         * {@value TranRecord#RECORD_LENGTH} bytes (gates G19, G21).
         *
         * @param item the record being posted
         * @return what the posting sequence did
         * @throws AbendException if {@code 2700} or {@code 2900} reports a fatal status
         */
        PostingResult postTransaction(DalyTranRecord item) {
            tranRecord.moveTranId(item.dalytranId());                                         //     L425
            tranRecord.moveTranTypeCd(item.dalytranTypeCd());                                 //     L426
            tranRecord.moveTranCatCd(item.dalytranCatCdImage());                              //     L427
            tranRecord.moveTranSource(item.dalytranSource());                                 //     L428
            tranRecord.moveTranDesc(item.dalytranDesc());                                     //     L429
            tranRecord.writeTranAmtImage(item.dalytranAmtImage());                            //     L430
            tranRecord.moveTranMerchantId(item.dalytranMerchantIdImage());                    //     L431
            tranRecord.moveTranMerchantName(item.dalytranMerchantName());                      //     L432
            tranRecord.moveTranMerchantCity(item.dalytranMerchantCity());                      //     L433
            tranRecord.moveTranMerchantZip(item.dalytranMerchantZip());                        //     L434
            tranRecord.moveTranCardNum(item.dalytranCardNum());                               //     L435
            tranRecord.moveTranOrigTs(item.dalytranOrigTs());                                 //     L436

            String procTimestamp = job.db2FormatTimestamp();                                  //     L437
            tranRecord.moveTranProcTs(procTimestamp);                                         //     L438

            boolean created = updateTcatbal(item);                                            //     L440
            boolean rewriteInvalidKey = updateAccountRec(item);                               //     L441
            writeTransactionFile();                                                           //     L442
            return new PostingResult(created, rewriteInvalidKey, procTimestamp);
        }

        // ---------------------------------------------------------------------------------------------
        // 2500-WRITE-REJECT-REC - app/cbl/CBTRN02C.cbl:446-465.
        // ---------------------------------------------------------------------------------------------

        /**
         * {@code 2500-WRITE-REJECT-REC} - {@code app/cbl/CBTRN02C.cbl:446-465}.
         *
         * <pre>
         * MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA                                                L447
         * MOVE WS-VALIDATION-TRAILER TO VALIDATION-TRAILER                                        L448
         * MOVE 8 TO APPL-RESULT                                                                    L450
         * WRITE FD-REJS-RECORD FROM REJECT-RECORD                                                   L451
         * ... '00' -&gt; 0 else 12; if not AOK: DISPLAY 'ERROR WRITING TO REJECTS FILE' ...      L452-464
         * </pre>
         *
         * <p>The record composition belongs to {@link DalyRejectWriter}, which owns the
         * {@value DalyRejectWriter#RECORD_LENGTH}-byte layout of {@code :81-84} - the 350-byte transaction
         * followed by the 80-byte trailer of a {@code PIC 9(04)} reason and a {@code PIC X(76)} description.
         * The guard chain around the write is this paragraph's and stays here, including the
         * {@value #ERROR_WRITING_DALYREJS} text.
         *
         * <p>The trailer is written from {@code WS-VALIDATION-FAIL-REASON} and its description as the
         * cascade left them - not from a code re-derived here - so the 102-overwritten-by-103 outcome
         * reaches the dataset exactly as the mainframe writes it.
         *
         * @param item the record being rejected
         * @throws AbendException if the write does not report {@code '00'}
         */
        void writeRejectRec(DalyTranRecord item) {
            dalyrejs.moveToRejectTranData(item);                                              //     L447
            dalyrejs.moveToValidationTrailer(workingStorage.validationFailReason(),            //     L448
                    workingStorage.validationFailReasonDesc());

            workingStorage.moveToApplResult(APPL_RESULT_ASSUMED_FAILURE);                     //     L450
            String status = statusOf(dalyrejs.writeRejectRec());                              //     L451
            workingStorage.moveDalyrejsStatus(status);
            applResultFromOkStatus(status);                                                   // L452-456
            if (!workingStorage.applAok()) {                                                  //     L457
                throw reportAndAbend(ERROR_WRITING_DALYREJS, status);                         // L460-463
            }
        }

        // ---------------------------------------------------------------------------------------------
        // 2700-UPDATE-TCATBAL and its two arms - app/cbl/CBTRN02C.cbl:467-542.
        // ---------------------------------------------------------------------------------------------

        /**
         * {@code 2700-UPDATE-TCATBAL} - {@code app/cbl/CBTRN02C.cbl:467-501}.
         *
         * <p>The key is the 17-byte {@code FD-TRAN-CAT-KEY} of {@code :93-96}, built at {@code :469-471}
         * from {@code XREF-ACCT-ID}, {@code DALYTRAN-TYPE-CD} and {@code DALYTRAN-CAT-CD} - the account the
         * cross reference resolved, not the one the transaction names.
         *
         * <p><strong>{@code '00'} and {@code '23'} are both success.</strong> {@code :481} is
         * {@code IF TCATBALF-STATUS = '00' OR '23'}, so a missing balance record is a branch and not a
         * failure: the {@code INVALID KEY} phrase at {@code :475-478} displays the not-found notice and sets
         * {@code WS-CREATE-TRANCAT-REC}, and {@code :495} then chooses between the create and update arms.
         * Anything else is {@link #APPL_RESULT_FATAL} and abends with
         * {@value #ERROR_READING_TCATBALF}.
         *
         * <p>The notice at {@code :476-477} is one {@code DISPLAY} of three operands, and COBOL contributes
         * each operand's own characters with no separator of its own - so the emitted line is
         * {@value #TCATBAL_NOT_FOUND_PREFIX} (whose trailing space is inside the literal), then the
         * 17-character key image, then {@value #TCATBAL_NOT_FOUND_SUFFIX}. The whitespace between the two
         * source lines is source layout, not output.
         *
         * @param item the record being posted
         * @return {@code true} when the create arm ran, {@code false} when the update arm did
         * @throws AbendException if the read reports a status that is neither {@code '00'} nor {@code '23'},
         *                        or if the write or rewrite that follows fails
         */
        boolean updateTcatbal(DalyTranRecord item) {
            TranCatBalRecord.TranCatKey key = new TranCatBalRecord.TranCatKey(                 // L469-471
                    cardXrefRecord.xrefAcctId(), item.dalytranTypeCd(), item.dalytranCatCd());

            workingStorage.moveDoNotCreateTrancatRec();                                       //     L473
            TranCatBalRepository.ReadResult result = tcatbalf.readByKey(key);                  //     L474
            workingStorage.moveTcatbalfStatus(result.status());
            if (result.isNotFound()) {                                                        //     L475
                sysout.write(TCATBAL_NOT_FOUND_PREFIX + key.image(job.datasetCharset)          // L476-477
                        + TCATBAL_NOT_FOUND_SUFFIX);
                workingStorage.moveCreateTrancatRec();                                        //     L478
            } else {
                // READ ... INTO TRAN-CAT-BAL-RECORD transfers only when a record was found. On any other
                // outcome the area keeps the previous record - which is exactly what INITIALIZE at :504
                // then inherits the FILLER of.
                result.record().ifPresent(found -> tranCatBalRecord = found);
            }

            // IF TCATBALF-STATUS = '00' OR '23' -> 0, ELSE -> 12.                             L481-485
            if (FileStatus.isOkOrNotFound(result.status())) {
                workingStorage.moveToApplResult(APPL_RESULT_AOK);
            } else {
                workingStorage.moveToApplResult(APPL_RESULT_FATAL);
            }
            if (!workingStorage.applAok()) {                                                  //     L486
                throw reportAndAbend(ERROR_READING_TCATBALF, result.status());                 // L489-492
            }

            if (workingStorage.createTrancatRecIsYes()) {                                     //     L495
                createTcatbalRec(item);                                                       //     L496
                return true;
            }
            updateTcatbalRec(item);                                                           //     L498
            return false;
        }

        /**
         * {@code 2700-A-CREATE-TCATBAL-REC} - {@code app/cbl/CBTRN02C.cbl:503-524}.
         *
         * <pre>
         * INITIALIZE TRAN-CAT-BAL-RECORD                                                          L504
         * MOVE XREF-ACCT-ID TO TRANCAT-ACCT-ID                                                    L505
         * MOVE DALYTRAN-TYPE-CD TO TRANCAT-TYPE-CD                                                L506
         * MOVE DALYTRAN-CAT-CD TO TRANCAT-CD                                                      L507
         * ADD DALYTRAN-AMT TO TRAN-CAT-BAL                                                        L508
         * WRITE FD-TRAN-CAT-BAL-RECORD FROM TRAN-CAT-BAL-RECORD                                   L510
         * </pre>
         *
         * <p><strong>{@code INITIALIZE} skips {@code FILLER}.</strong> A COBOL {@code INITIALIZE} with no
         * {@code REPLACING} sets each elementary item to the figurative constant for its category - zero for
         * numeric, space for alphanumeric - and leaves {@code FILLER} untouched. The 22 reserved bytes of
         * {@code CVTRA01Y} therefore keep whatever the failed {@code READ} at {@code :474} left in the area,
         * and the {@code WRITE} sends them to the dataset. Blanking them would be a 22-byte parity
         * difference per created record.
         *
         * <p>Because {@code INITIALIZE} zeroes the balance first, {@code ADD DALYTRAN-AMT TO TRAN-CAT-BAL}
         * leaves the new record's balance equal to the transaction amount.
         *
         * @param item the record being posted
         * @throws AbendException if the write does not report {@code '00'}
         */
        void createTcatbalRec(DalyTranRecord item) {
            tranCatBalRecord.initialize()                                                     //     L504
                    .trancatAcctId(cardXrefRecord.xrefAcctId())                               //     L505
                    .trancatTypeCd(item.dalytranTypeCd())                                     //     L506
                    .trancatCd(item.dalytranCatCd())                                          //     L507
                    .addToTranCatBal(item.dalytranAmt());                                     //     L508

            String status = tcatbalf.write(tranCatBalRecord).status();                         //     L510
            workingStorage.moveTcatbalfStatus(status);
            applResultFromOkStatus(status);                                                   // L512-516
            if (!workingStorage.applAok()) {                                                  //     L517
                throw reportAndAbend(ERROR_WRITING_TCATBALF, status);                          // L520-523
            }
        }

        /**
         * {@code 2700-B-UPDATE-TCATBAL-REC} - {@code app/cbl/CBTRN02C.cbl:526-542}.
         *
         * <pre>
         * ADD DALYTRAN-AMT TO TRAN-CAT-BAL                                                        L527
         * REWRITE FD-TRAN-CAT-BAL-RECORD FROM TRAN-CAT-BAL-RECORD                                 L528
         * </pre>
         *
         * <p>The augend is the balance the {@code READ} at {@code :474} transferred, so this arm increments
         * a stored balance where {@code 2700-A} establishes one. Both go through {@link CobolDecimal} at
         * scale {@value CobolDecimal#MONETARY_SCALE} with {@code RoundingMode.DOWN}, because
         * {@code TRAN-CAT-BAL} is {@code PIC S9(09)V99} and the statement carries no {@code ROUNDED}.
         *
         * <p><strong>Note the paragraph performs no {@code MOVE} to the key.</strong> The record being
         * rewritten is the one just read, so its key is already the key it was read under.
         *
         * @param item the record being posted
         * @throws AbendException if the rewrite does not report {@code '00'}
         */
        void updateTcatbalRec(DalyTranRecord item) {
            tranCatBalRecord.addToTranCatBal(item.dalytranAmt());                             //     L527

            String status = tcatbalf.rewrite(tranCatBalRecord).status();                       //     L528
            workingStorage.moveTcatbalfStatus(status);
            applResultFromOkStatus(status);                                                   // L530-534
            if (!workingStorage.applAok()) {                                                  //     L535
                throw reportAndAbend(ERROR_REWRITING_TCATBALF, status);                        // L538-541
            }
        }

        // ---------------------------------------------------------------------------------------------
        // 2800-UPDATE-ACCOUNT-REC - app/cbl/CBTRN02C.cbl:545-560.
        // ---------------------------------------------------------------------------------------------

        /**
         * {@code 2800-UPDATE-ACCOUNT-REC} - {@code app/cbl/CBTRN02C.cbl:545-560}.
         *
         * <pre>
         * ADD DALYTRAN-AMT  TO ACCT-CURR-BAL                                                      L547
         * IF DALYTRAN-AMT &gt;= 0                                                                L548
         *    ADD DALYTRAN-AMT TO ACCT-CURR-CYC-CREDIT                                             L549
         * ELSE                                                                                     L550
         *    ADD DALYTRAN-AMT TO ACCT-CURR-CYC-DEBIT                                               L551
         * END-IF                                                                                   L552
         * REWRITE FD-ACCTFILE-REC FROM  ACCOUNT-RECORD                                             L554
         *    INVALID KEY                                                                            L555
         *      MOVE 109 TO WS-VALIDATION-FAIL-REASON                                                L556
         *      MOVE 'ACCOUNT RECORD NOT FOUND' TO WS-VALIDATION-FAIL-REASON-DESC              L557-558
         * END-REWRITE                                                                                L559
         * </pre>
         *
         * <p><strong>Note the sign convention:</strong> a <em>non-negative</em> amount is added to
         * {@code ACCT-CURR-CYC-CREDIT} and a negative one to {@code ACCT-CURR-CYC-DEBIT}. Zero takes the
         * credit branch, because the test is {@code >= 0} and not {@code > 0}. Both branches are driven by
         * tests (gate G50).
         *
         * <p>All three receivers are {@code PIC S9(10)V99} ({@code app/cpy/CVACT01Y.cpy:7,13,14}), so every
         * store is a {@link BigDecimal} at scale {@value CobolDecimal#MONETARY_SCALE} with
         * {@code RoundingMode.DOWN}.
         *
         * <h2>Preserved defect: reason 109 is set and can never be acted on</h2>
         *
         * <p>The mainline tested {@code WS-VALIDATION-FAIL-REASON} at {@code :211} and had already chosen the
         * posting arm before this paragraph ran, so the 109 stored here reaches no {@code IF}: it produces
         * <strong>no reject record</strong> and does <strong>not</strong> increment
         * {@code WS-REJECT-COUNT}. It is set and left, and the fact that it happened is reported through the
         * returned flag purely so a test can prove the record was <em>not</em> re-routed. Do not add a
         * branch that rejects on it.
         *
         * <p>{@code 2900-WRITE-TRANSACTION-FILE} runs next regardless, because {@code :442} follows
         * {@code :441} unconditionally - so a transaction whose account rewrite found nothing is still added
         * to the master.
         *
         * @param item the record being posted
         * @return {@code true} when the rewrite reported the {@code INVALID KEY} condition and reason 109 was
         *         therefore stored
         */
        boolean updateAccountRec(DalyTranRecord item) {
            BigDecimal amount = item.dalytranAmt();

            // ADD DALYTRAN-AMT TO ACCT-CURR-BAL                                                    L547
            accountRecord.setAcctCurrBal(CobolDecimal.add(accountRecord.getAcctCurrBal(), amount,
                    CobolDecimal.MONETARY_SCALE));

            if (amount.signum() >= 0) {                                                       //     L548
                // ADD DALYTRAN-AMT TO ACCT-CURR-CYC-CREDIT                                         L549
                accountRecord.setAcctCurrCycCredit(CobolDecimal.add(
                        accountRecord.getAcctCurrCycCredit(), amount, CobolDecimal.MONETARY_SCALE));
            } else {
                // ADD DALYTRAN-AMT TO ACCT-CURR-CYC-DEBIT                                          L551
                accountRecord.setAcctCurrCycDebit(CobolDecimal.add(
                        accountRecord.getAcctCurrCycDebit(), amount, CobolDecimal.MONETARY_SCALE));
            }

            AccountRepository.WriteResult result = acctfile.rewrite(accountRecord);            //     L554
            workingStorage.moveAcctfileStatus(result.status());
            if (result.isNotFound()) {                                                        //     L555
                // PRESERVED DEFECT (practice B5): set and never acted on. See this method's javadoc.
                workingStorage.moveToValidationTrailer(REASON_ACCOUNT_NOT_FOUND_ON_REWRITE,    // L556-558
                        DalyRejectWriter.DESC_ACCOUNT_RECORD_NOT_FOUND);
                return true;
            }
            // There is no status guard and no ELSE in this paragraph: any other outcome, success included,
            // simply falls through to :560 EXIT. A failed rewrite that is not an invalid key therefore does
            // not abend here, and 2900 runs next either way.
            return false;
        }

        // ---------------------------------------------------------------------------------------------
        // 2900-WRITE-TRANSACTION-FILE - app/cbl/CBTRN02C.cbl:562-579.
        // ---------------------------------------------------------------------------------------------

        /**
         * {@code 2900-WRITE-TRANSACTION-FILE} - {@code app/cbl/CBTRN02C.cbl:562-579}.
         *
         * <p>{@code WRITE FD-TRANFILE-REC FROM TRAN-RECORD} at {@code :564} is a keyed add, because
         * {@code :34-38} declares the file {@code ORGANIZATION IS INDEXED ACCESS MODE IS RANDOM RECORD KEY
         * IS FD-TRANS-ID}: the record is added under the {@code TRAN-ID} it carries.
         *
         * <p>The guard accepts {@code '00'} and nothing else ({@code :566-570}), so the duplicate status
         * {@code '22'} is {@link #APPL_RESULT_FATAL} here just like an unreachable dataset - the program
         * enumerates no duplicate arm at all.
         *
         * <p>The record written is exactly {@value TranRecord#RECORD_LENGTH} bytes with its trailing
         * {@code FILLER} intact, which the repository asserts before it stores anything (gates G19, G21).
         *
         * @throws AbendException if the write does not report {@code '00'}
         */
        void writeTransactionFile() {
            workingStorage.moveToApplResult(APPL_RESULT_ASSUMED_FAILURE);                     //     L563
            String status = job.transactionRepository.write(tranRecord).status();              //     L564
            workingStorage.moveTranfileStatus(status);
            applResultFromOkStatus(status);                                                   // L566-570
            if (!workingStorage.applAok()) {                                                  //     L571
                throw reportAndAbend(ERROR_WRITING_TRANFILE, status);                          // L574-577
            }
        }

        // ---------------------------------------------------------------------------------------------
        // The six CLOSE paragraphs - app/cbl/CBTRN02C.cbl:582-690, performed in order at :221-226.
        // ---------------------------------------------------------------------------------------------

        /**
         * Performs the six closes in the source's order: {@code app/cbl/CBTRN02C.cbl:221-226} - the same
         * order as the opens.
         *
         * <p>Reached only when the loop ended at end of file. An abend leaves these unperformed on the
         * mainframe, which is why {@link TransactionValidationJob#postTransactions(SysoutSink)} calls this
         * inside its {@code try} and releases the handles in its {@code finally}.
         *
         * @throws AbendException if any close reports a status other than {@code '00'}
         */
        void closeFiles() {
            requireOpen();
            dalytranClose();                                                                  //     L221
            tranfileClose();                                                                  //     L222
            xreffileClose();                                                                  //     L223
            dalyrejsClose();                                                                  //     L224
            acctfileClose();                                                                  //     L225
            tcatbalfClose();                                                                  //     L226
            closedNormally = true;
        }

        /**
         * {@code 9000-DALYTRAN-CLOSE} - {@code app/cbl/CBTRN02C.cbl:582-598}.
         *
         * @throws AbendException if the close does not report {@code '00'}
         */
        void dalytranClose() {
            workingStorage.moveToApplResult(APPL_RESULT_ASSUMED_FAILURE);                     //     L583
            String status = dalytranFile.closeFile();                                         //     L584
            workingStorage.moveDalytranStatus(status);
            applResultFromOkStatus(status);                                                   // L585-589
            if (!workingStorage.applAok()) {                                                  //     L590
                throw reportAndAbend(ERROR_CLOSING_DALYTRAN, status);                          // L593-596
            }
        }

        /**
         * {@code 9100-TRANFILE-CLOSE} - {@code app/cbl/CBTRN02C.cbl:600-616}.
         *
         * @throws AbendException if the close does not report {@code '00'}
         */
        void tranfileClose() {
            workingStorage.moveToApplResult(APPL_RESULT_ASSUMED_FAILURE);                     //     L601
            String status = tranfile.closeInput();                                            //     L602
            workingStorage.moveTranfileStatus(status);
            applResultFromOkStatus(status);                                                   // L603-607
            if (!workingStorage.applAok()) {                                                  //     L608
                throw reportAndAbend(ERROR_CLOSING_TRANFILE, status);                          // L611-614
            }
        }

        /**
         * {@code 9200-XREFFILE-CLOSE} - {@code app/cbl/CBTRN02C.cbl:619-635}.
         *
         * @throws AbendException if the close does not report {@code '00'}
         */
        void xreffileClose() {
            workingStorage.moveToApplResult(APPL_RESULT_ASSUMED_FAILURE);                     //     L620
            String status = xreffile.closeBrowse();                                           //     L621
            workingStorage.moveXreffileStatus(status);
            applResultFromOkStatus(status);                                                   // L622-626
            if (!workingStorage.applAok()) {                                                  //     L627
                throw reportAndAbend(ERROR_CLOSING_XREFFILE, status);                          // L630-633
            }
        }

        /**
         * {@code 9300-DALYREJS-CLOSE} - {@code app/cbl/CBTRN02C.cbl:637-653}.
         *
         * <h2>Preserved defect: the wrong file's status is displayed</h2>
         *
         * <p>{@code :640} tests {@code DALYREJS-STATUS}, but {@code :649} moves
         * <strong>{@code XREFFILE-STATUS}</strong> to {@code IO-STATUS} before performing
         * {@code 9910-DISPLAY-IO-STATUS}. So the status rendered on this failure path is the cross-reference
         * file's - which, at this point in the mainline, is whatever {@code 9200-XREFFILE-CLOSE} last set.
         * The decision to abend is still driven by the rejects file's own status; only the displayed value is
         * the other file's.
         *
         * <p>Reproduced verbatim (practice B5). Correcting it here would change a line of output on a path a
         * parity case can reach.
         *
         * @throws AbendException if the close does not report {@code '00'}
         */
        void dalyrejsClose() {
            workingStorage.moveToApplResult(APPL_RESULT_ASSUMED_FAILURE);                     //     L638
            String status = statusOf(dalyrejs.closeOutput());                                 //     L639
            workingStorage.moveDalyrejsStatus(status);
            applResultFromOkStatus(status);                                                   // L640-644
            if (!workingStorage.applAok()) {                                                  //     L645
                // MOVE XREFFILE-STATUS TO IO-STATUS - the wrong operand, preserved.                L649
                throw reportAndAbend(ERROR_CLOSING_DALYREJS, workingStorage.xreffileStatus()); // L648-651
            }
        }

        /**
         * {@code 9400-ACCTFILE-CLOSE} - {@code app/cbl/CBTRN02C.cbl:655-671}.
         *
         * @throws AbendException if the close does not report {@code '00'}
         */
        void acctfileClose() {
            workingStorage.moveToApplResult(APPL_RESULT_ASSUMED_FAILURE);                     //     L656
            String status = acctfile.closeFile();                                             //     L657
            workingStorage.moveAcctfileStatus(status);
            applResultFromOkStatus(status);                                                   // L658-662
            if (!workingStorage.applAok()) {                                                  //     L663
                throw reportAndAbend(ERROR_CLOSING_ACCTFILE, status);                          // L666-669
            }
        }

        /**
         * {@code 9500-TCATBALF-CLOSE} - {@code app/cbl/CBTRN02C.cbl:674-690}.
         *
         * @throws AbendException if the close does not report {@code '00'}
         */
        void tcatbalfClose() {
            workingStorage.moveToApplResult(APPL_RESULT_ASSUMED_FAILURE);                     //     L675
            String status = tcatbalf.closeFile();                                             //     L676
            workingStorage.moveTcatbalfStatus(status);
            applResultFromOkStatus(status);                                                   // L677-681
            if (!workingStorage.applAok()) {                                                  //     L682
                throw reportAndAbend(ERROR_CLOSING_TCATBALF, status);                          // L685-688
            }
        }

        // ---------------------------------------------------------------------------------------------
        // The trailer - app/cbl/CBTRN02C.cbl:227-232.
        // ---------------------------------------------------------------------------------------------

        /**
         * The two count lines, the {@code RETURN-CODE} decision and the closing banner -
         * {@code app/cbl/CBTRN02C.cbl:227-232}.
         *
         * <pre>
         * DISPLAY 'TRANSACTIONS PROCESSED :' WS-TRANSACTION-COUNT                                  L227
         * DISPLAY 'TRANSACTIONS REJECTED  :' WS-REJECT-COUNT                                       L228
         * IF WS-REJECT-COUNT &gt; 0   MOVE 4 TO RETURN-CODE   END-IF                            L229-231
         * DISPLAY 'END OF EXECUTION OF PROGRAM CBTRN02C'                                            L232
         * </pre>
         *
         * <p>Both counters are {@code PIC 9(09)}, so each renders zero-filled to
         * {@value #COUNTER_WIDTH} digits: a clean run of the shipped 300-record fixture emits
         * {@code TRANSACTIONS PROCESSED :000000300} and {@code TRANSACTIONS REJECTED  :000000000}.
         * <strong>The second literal carries two spaces before its colon</strong> and the first carries one;
         * that asymmetry is in the source and is emitted verbatim.
         *
         * <p>{@code MOVE 4 TO RETURN-CODE} is the whole of the return-code logic: there is no arm that sets
         * 8 or 12 outside the abend paragraph, so a run that completes reports 4 when it rejected anything
         * and 0 otherwise (gate G35).
         *
         * @return the run's two counters and its return code; never {@code null}
         */
        RunOutcome writeTrailer() {
            long processed = workingStorage.transactionCount();
            long rejected = workingStorage.rejectCount();

            sysout.write(TRANSACTIONS_PROCESSED_PREFIX                                        //     L227
                    + job.codec.movePic9(processed, COUNTER_WIDTH));
            sysout.write(TRANSACTIONS_REJECTED_PREFIX                                         //     L228
                    + job.codec.movePic9(rejected, COUNTER_WIDTH));

            int returnCode = rejected > 0                                                     // L229-231
                    ? RETURN_CODE_REJECTS_PRESENT
                    : RETURN_CODE_CLEAN;

            sysout.write(END_OF_EXECUTION);                                                   //     L232
            return new RunOutcome(processed, rejected, returnCode);
        }

        // ---------------------------------------------------------------------------------------------
        // Handle release, and the shared guard-chain helpers.
        // ---------------------------------------------------------------------------------------------

        /**
         * Releases whatever this run still holds, silently, and settles the rejects generation's
         * disposition.
         *
         * <p>Idempotent and safe on a run that never opened anything, because it is the {@code finally} of
         * {@link TransactionValidationJob#postTransactions(SysoutSink)} and of
         * {@link ChunkDelegate#close()}. It emits no {@code DISPLAY} and raises nothing: the six
         * {@code CLOSE} paragraphs are the observable close, and this only makes sure no cursor is leaked
         * when they were not reached.
         *
         * <p><strong>The abnormal disposition of {@value #DALYREJS_DD_NAME}.</strong>
         * {@code app/jcl/POSTTRAN.jcl:34} declares {@code DISP=(NEW,CATLG,DELETE)}, so the generation is
         * catalogued when the step ends normally and <strong>deleted</strong> when it does not. A run that
         * abended therefore leaves no reject generation behind, while its account rewrites and balance
         * updates stand - every other DD in the step is {@code DISP=SHR}.
         *
         * <p>The dataset is closed <em>before</em> the disposition is applied, which is the order the
         * mainframe uses and the order {@code discardGeneration()} requires. On the normal path the close
         * already happened in {@link #dalyrejsClose()} and this one is the no-op an already-closed handle
         * reports; its outcome is discarded here either way, because the observable close is the
         * {@code 9300-DALYREJS-CLOSE} paragraph and an abended run never performs it.
         */
        void release() {
            if (dalyrejs != null) {
                DalyRejectWriter.RejectsFile releasing = dalyrejs;
                dalyrejs = null;
                releasing.closeOutput();
                if (!closedNormally) {
                    // DISP=(NEW,CATLG,DELETE) - the abnormal disposition, applied after the close.
                    releasing.discardGeneration();
                }
            }
            if (tcatbalf != null) {
                tcatbalf.close();
                tcatbalf = null;
            }
            if (acctfile != null) {
                acctfile.close();
                acctfile = null;
            }
            if (xreffile != null) {
                xreffile.close();
                xreffile = null;
            }
            if (tranfile != null) {
                tranfile.close();
                tranfile = null;
            }
            if (dalytranFile != null) {
                dalytranFile.close();
                dalytranFile = null;
            }
        }

        /**
         * Whether the six {@code CLOSE} paragraphs completed.
         *
         * @return {@code true} once {@link #closeFiles()} has run to the end
         */
        boolean closedNormally() {
            return closedNormally;
        }

        /**
         * The {@code IF status = '00' MOVE 0 ELSE MOVE 12} ladder every I/O paragraph in this program runs.
         *
         * <p>Sixteen paragraphs share it verbatim, so it is written once. The only two that do not are
         * {@code 1000-DALYTRAN-GET-NEXT}, which has a third arm for end of file, and
         * {@code 2700-UPDATE-TCATBAL}, which accepts {@code '23'} as well.
         *
         * @param status the two-character status the operation reported
         */
        private void applResultFromOkStatus(String status) {
            if (FileStatus.isOk(status)) {
                workingStorage.moveToApplResult(APPL_RESULT_AOK);
            } else {
                workingStorage.moveToApplResult(APPL_RESULT_FATAL);
            }
        }

        /**
         * The three-statement failure tail every guard chain ends with:
         * {@code DISPLAY '<text>'}, {@code MOVE <status> TO IO-STATUS} then
         * {@code PERFORM 9910-DISPLAY-IO-STATUS}, then {@code PERFORM 9999-ABEND-PROGRAM}.
         *
         * <p>{@code 9910-DISPLAY-IO-STATUS} ({@code :714-727}) renders the four-character
         * {@code IO-STATUS-04} image behind the prefix {@value FileStatus#DISPLAY_PREFIX}, splitting a
         * {@code '9x'} permanent error into its status key and the binary value of its feedback byte. That
         * rendering belongs to {@link FileStatus#toDisplayLine(String)} and is not re-implemented here.
         *
         * <p>{@code 9999-ABEND-PROGRAM} ({@code :707-711}) displays
         * {@value AbendException#ABEND_DISPLAY_TEXT}, moves {@code 0} to {@code TIMING} and {@code 999} to
         * {@code ABCODE}, then calls {@code CEE3ABD}. The returned {@link AbendException} carries all three:
         * the current {@code APPL-RESULT} as its {@code RETURN-CODE}, {@code 999} and {@code 0}
         * (gate G35).
         *
         * <p>Returned rather than thrown so the call site reads {@code throw reportAndAbend(...)}, which
         * makes the abend visible at the paragraph it belongs to instead of hidden inside a helper.
         *
         * @param text   the paragraph's own {@code DISPLAY} literal
         * @param status the status to render, which is the operand the source moves to {@code IO-STATUS} -
         *               and at {@code :649} that is deliberately another file's
         * @return the abend to throw; never {@code null}
         */
        private AbendException reportAndAbend(String text, String status) {
            sysout.write(text);
            sysout.write(FileStatus.toDisplayLine(status));                                   // L714-727
            sysout.write(AbendException.ABEND_DISPLAY_TEXT);                                  //     L708
            return AbendException.standard(PROGRAM_ID, workingStorage.applResult(),           // L709-711
                    text + " (" + FileStatus.toStatusImage(status) + ")");
        }

        /**
         * The two-character status a collaborator's {@link FileStatus.Outcome} stands for.
         *
         * <p>Needed only for {@value #DALYREJS_DD_NAME}, whose sink contract reports an outcome rather than
         * a status - a collector has no {@code FILE STATUS} to report. {@link FileStatus.Outcome#OTHER}
         * stands for every value the programs did not enumerate, so it renders as
         * {@link #PERMANENT_ERROR_STATUS}, which is what every repository in this module composes for the
         * same situation.
         *
         * @param outcome the reported outcome; must not be {@code null}
         * @return the two-character status
         * @throws NullPointerException if {@code outcome} is {@code null}
         */
        private static String statusOf(FileStatus.Outcome outcome) {
            Objects.requireNonNull(outcome, "A reject-file operation reports an outcome; there is no "
                    + "COBOL FILE STATUS meaning 'no answer', so a null is a defect in the sink");
            return outcome.batchStatus().orElse(PERMANENT_ERROR_STATUS);
        }

        /**
         * Refuses an operation on a run whose files were never opened.
         *
         * @throws IllegalStateException if {@link #openFiles()} has not run, or the handles were released
         */
        private void requireOpen() {
            if (dalytranFile == null) {
                throw new IllegalStateException("No file is open on this run. app/cbl/CBTRN02C.cbl:195-200 "
                        + "opens all six before the loop at :202 reads anything, so openFiles() must run "
                        + "first; a run whose handles have been released cannot be reused - build another "
                        + "with newRun(SysoutSink).");
            }
        }
    }

    /**
     * What the three paragraphs of {@code 2000-POST-TRANSACTION} did, carried back to the mainline.
     *
     * @param tranCatBalCreated        {@code true} when {@code 2700-A-CREATE-TCATBAL-REC} ran rather than
     *                                 {@code 2700-B}
     * @param accountRewriteInvalidKey {@code true} when the {@code REWRITE} at {@code :554} reported the
     *                                 {@code INVALID KEY} condition and reason 109 was stored - which
     *                                 changes nothing, by design
     * @param procTimestamp            the 26-character {@code TRAN-PROC-TS} the record was stamped with
     */
    record PostingResult(boolean tranCatBalCreated, boolean accountRewriteInvalidKey,
                         String procTimestamp) {

        /**
         * Requires the timestamp a posted record always carries.
         *
         * @throws NullPointerException     if {@code procTimestamp} is {@code null}
         * @throws IllegalArgumentException if it is not exactly {@value #DB2_TIMESTAMP_LENGTH} characters
         */
        PostingResult {
            Objects.requireNonNull(procTimestamp, "A posted record always carries the TRAN-PROC-TS that "
                    + "app/cbl/CBTRN02C.cbl:438 moved into it");
            if (procTimestamp.length() != DB2_TIMESTAMP_LENGTH) {
                throw new IllegalArgumentException("DB2-FORMAT-TS is PIC X(" + DB2_TIMESTAMP_LENGTH
                        + "), so TRAN-PROC-TS receives exactly " + DB2_TIMESTAMP_LENGTH + " characters; '"
                        + procTimestamp + "' is " + procTimestamp.length());
            }
        }
    }

    // =================================================================================================
    // The Spring Batch chunk delegate: one object, four roles, one run.
    // =================================================================================================

    /**
     * The step's reader, processor, writer and step listener, all backed by one {@link PostingRun}.
     *
     * <p>Four roles rather than four objects because all four need the same {@code WORKING-STORAGE} and the
     * same six open files, which is what a COBOL program is. Splitting them would mean sharing the run
     * through a field on the singleton configuration bean, and two step executions would then consume each
     * other's records.
     *
     * <p>The lifecycle maps onto the program's own:
     *
     * <table border="1">
     *   <caption>Spring Batch lifecycle to COBOL paragraph</caption>
     *   <tr><th>Callback</th><th>COBOL</th></tr>
     *   <tr><td>{@link #open(ExecutionContext)}</td><td>{@code :194} banner and {@code :195-200} opens</td></tr>
     *   <tr><td>{@link #read()}</td><td>{@code :204} {@code PERFORM 1000-DALYTRAN-GET-NEXT}, and at end of
     *       file the epilogue that follows the loop - {@code :221-226} closes and {@code :227-232} the
     *       trailer</td></tr>
     *   <tr><td>{@link #process(DalyTranRecord)}</td><td>{@code :206-216} the record body</td></tr>
     *   <tr><td>{@link #write(Chunk)}</td><td>bookkeeping only - no dataset verb</td></tr>
     *   <tr><td>{@link #close()}</td><td>handle release only, plus the {@code DISP} disposition</td></tr>
     * </table>
     *
     * <p><strong>The writer stage performs no dataset I/O, deliberately.</strong> Every verb of
     * {@code 2000-POST-TRANSACTION} and {@code 2500-WRITE-REJECT-REC} runs in
     * {@link #process(DalyTranRecord)}, where the source puts them. Relocating a write into the writer would
     * move it after the next record's read, and on the failure paths that difference is observable: the
     * COBOL abends inside the posting sequence, before the mainline reaches the next record at all.
     */
    public static final class ChunkDelegate
            implements ItemStreamReader<DalyTranRecord>, ItemProcessor<DalyTranRecord, RecordOutcome>,
            ItemWriter<RecordOutcome>, StepExecutionListener {

        /** The job whose collaborators this delegate's run uses. */
        private final TransactionValidationJob job;

        /** This execution's run, created by {@link #open(ExecutionContext)} and released by {@link #close()}. */
        private PostingRun run;

        /** What the trailer reported, available once the read that ended the loop performed the epilogue. */
        private RunOutcome outcome;

        /**
         * Whether {@code :221-232} has been attempted, successfully or not.
         *
         * <p>Separate from {@link #outcome} so a failed attempt is never repeated: an abend inside a
         * {@code CLOSE} terminates the program there, and a retry that succeeded would emit a trailer the
         * mainframe never emits.
         */
        private boolean epilogueAttempted;

        /** How many records the writer stage saw posted. */
        private long posted;

        /** How many records the writer stage saw rejected - {@code WS-REJECT-COUNT} seen from outside. */
        private long rejected;

        /**
         * Binds the delegate to its job. The run itself is created by the stream open, not here, so a
         * delegate that is never opened holds nothing.
         *
         * @param job the job; must not be {@code null}
         * @throws NullPointerException if {@code job} is {@code null}
         */
        ChunkDelegate(TransactionValidationJob job) {
            this.job = Objects.requireNonNull(job, "A job is required to build its chunk delegate");
        }

        /**
         * Nothing to do before the step: this job has no parameters to receive
         * ({@value #JCL_REFERENCE} declares no {@code PARM}).
         *
         * @param stepExecution the execution starting; must not be {@code null}
         */
        @Override
        public void beforeStep(StepExecution stepExecution) {
            Objects.requireNonNull(stepExecution, "A step execution is required to start a delegate");
        }

        /**
         * {@code app/cbl/CBTRN02C.cbl:194-200} - the start banner and the six opens, in order.
         *
         * @param executionContext the step's execution context, which this program stores nothing in:
         *                         {@code CBTRN02C} has no restart semantics, and inventing some would let a
         *                         restarted step re-read the daily file from the beginning and post every
         *                         earlier transaction a second time
         * @throws IllegalStateException if a run is already open on this delegate
         * @throws AbendException        if any open reports a status other than {@code '00'}
         */
        @Override
        public void open(ExecutionContext executionContext) {
            Objects.requireNonNull(executionContext, "An execution context is required by the ItemStream "
                    + "contract, even though this program stores nothing in it");
            if (run != null) {
                throw new IllegalStateException("A run is already open on this delegate. One delegate "
                        + "serves one step execution at a time, because a COBOL program's WORKING-STORAGE "
                        + "belongs to its execution; build another with newChunkDelegate() for a "
                        + "concurrent run.");
            }
            posted = 0;
            rejected = 0;
            outcome = null;
            epilogueAttempted = false;
            PostingRun opening = job.newRun(job.sysoutSink);
            run = opening;

            // DISPLAY 'START OF EXECUTION OF PROGRAM CBTRN02C'.                                      L194
            opening.sysout().write(START_OF_EXECUTION);

            // PERFORM 0000-DALYTRAN-OPEN through 0500-TCATBALF-OPEN.                            L195-L200
            opening.openFiles();
        }

        /**
         * {@code app/cbl/CBTRN02C.cbl:204} - {@code PERFORM 1000-DALYTRAN-GET-NEXT}, and, when that read
         * ends the loop, the epilogue that follows the loop at {@code :221-232}.
         *
         * <p>Returns {@code null} at end of file, which is both what {@code MOVE 'Y' TO END-OF-FILE} means
         * and what the {@code ItemReader} contract uses to end a step.
         *
         * <p><strong>Why the epilogue is performed here and not in {@link #close()}.</strong>
         * {@code PERFORM UNTIL END-OF-FILE = 'Y'} is left by the read that sets the flag, and
         * {@code 9000-DALYTRAN-CLOSE} is the very next statement, so this <em>is</em> the source's own
         * position for it. It also has to be here for two mechanical reasons, both properties of
         * {@code AbstractStep.execute}:
         *
         * <ul>
         *   <li>{@code afterStep} runs <em>before</em> the item streams are closed, so an outcome produced
         *       in {@code close()} would never reach {@link #afterStep(StepExecution)} and
         *       {@code MOVE 4 TO RETURN-CODE} ({@code :230}) could never become the step's exit code
         *       (gate G35).</li>
         *   <li>An exception from an item stream's {@code close()} is caught and logged rather than
         *       propagated, so a {@code CLOSE} that abends would leave the step reporting
         *       {@code COMPLETED}. Thrown from a read it fails the step, which is what a non-zero
         *       {@code RETURN-CODE} from an abend has to do.</li>
         * </ul>
         *
         * <p>The epilogue runs exactly once, because {@code END-OF-FILE = 'Y'} is set once and the chunk
         * loop stops on the first {@code null}. A step that ends before end of file - an abend, a stop
         * request - never reaches it, and {@link #close()} then releases the handles silently, exactly as
         * the mainframe leaves the {@code CLOSE} paragraphs unperformed.
         *
         * @return the next daily transaction, or {@code null} at end of file
         * @throws IllegalStateException if no run is open
         * @throws AbendException        if the read reports a status that is neither {@code '00'} nor
         *                               {@code '10'}, or if the epilogue's closes report one
         */
        @Override
        public DalyTranRecord read() {
            PostingRun reading = requireRun();
            DalyTranRecord item = reading.dalytranGetNext();                                  //     L204
            if (item == null) {
                finishNormally(reading);                                                      // L221-L232
            }
            return item;
        }

        /**
         * The statements after the loop - {@code app/cbl/CBTRN02C.cbl:221-232}.
         *
         * <p>Attempted <strong>at most once</strong>, whether it succeeded or abended. A COBOL program that
         * abends inside {@code 9400-ACCTFILE-CLOSE} terminates there: the remaining closes are not
         * performed, the trailer is not written, and nothing retries the sequence. Guarding on the outcome
         * alone would let {@link #close()} start the epilogue again after a close had already failed, and a
         * second attempt that succeeded would emit a trailer for a run that abended.
         *
         * @param finishing the run whose loop has ended at end of file
         * @throws AbendException if any of the six closes reports a status other than {@code '00'}
         */
        private void finishNormally(PostingRun finishing) {
            if (epilogueAttempted) {
                return;
            }
            epilogueAttempted = true;

            // PERFORM 9000-DALYTRAN-CLOSE through 9500-TCATBALF-CLOSE.                          L221-L226
            finishing.closeFiles();

            // The two count lines, the RETURN-CODE decision and the closing banner.             L227-L232
            outcome = finishing.writeTrailer();
        }

        /**
         * {@code app/cbl/CBTRN02C.cbl:206-216} - the record body: count, reset, validate, then post or
         * reject.
         *
         * @param item the record the reader returned
         * @return what the record did; never {@code null}, so no item is ever filtered out
         * @throws NullPointerException  if {@code item} is {@code null}
         * @throws IllegalStateException if no run is open
         * @throws AbendException        if any file operation this record triggers reports a fatal status
         */
        @Override
        public RecordOutcome process(DalyTranRecord item) {
            return requireRun().processRecord(item);
        }

        /**
         * The writer stage: bookkeeping, and no dataset I/O. See this class's documentation for why.
         *
         * @param chunk the outcomes of this chunk's records - exactly one, since the commit interval is
         *              {@value #CHUNK_SIZE}
         * @throws NullPointerException if {@code chunk} is {@code null}
         */
        @Override
        public void write(Chunk<? extends RecordOutcome> chunk) {
            Objects.requireNonNull(chunk, "A chunk is required to write one");
            for (RecordOutcome recordOutcome : chunk) {
                if (recordOutcome.posted()) {
                    posted++;
                } else {
                    rejected++;
                }
            }
        }

        /**
         * Contributes nothing to the execution context.
         *
         * <p>{@link PostingRun} stores no restartable position - the job refuses restart for the reason
         * {@link TransactionValidationJob#transactionValidationJob()} sets out - so this is a no-op that
         * exists to keep the stream contract stated in one place.
         *
         * @param executionContext the step's execution context; must not be {@code null}
         */
        @Override
        public void update(ExecutionContext executionContext) {
            Objects.requireNonNull(executionContext, "An execution context is required by the ItemStream "
                    + "contract, even though this program stores nothing in it");
        }

        /**
         * Releases the six handles, and nothing observable.
         *
         * <p>The epilogue of {@code app/cbl/CBTRN02C.cbl:221-232} - the six closes, the two count lines, the
         * {@code RETURN-CODE} decision and the closing banner - belongs to
         * {@link #read()}, which is where the loop ends; see that method for the two mechanical reasons it
         * cannot live here. This only makes sure nothing is left open, and settles the
         * {@code DISP=(NEW,CATLG,DELETE)} disposition of {@value #DALYREJS_DD_NAME}: a run that abended
         * leaves no reject generation behind, exactly as {@code app/jcl/POSTTRAN.jcl:34} declares.
         *
         * <p>The end-of-file guard is a safety net for a caller that drives the stream by hand and reached
         * end of file without going through {@link #read()}; in a step it is already satisfied and
         * {@link #finishNormally(PostingRun)} is then a no-op. An abend leaves the paragraphs unperformed on
         * the mainframe, so a failed step emits none of them.
         *
         * @throws AbendException if a close reports a status other than {@code '00'}
         */
        @Override
        public void close() {
            PostingRun finishing = run;
            if (finishing == null) {
                return;
            }
            run = null;
            try {
                if (finishing.workingStorage().endOfFileIsYes()) {
                    // Normally already done by the read that ended the loop; this covers a caller that
                    // drives the stream by hand and reached end of file through a path of its own.
                    finishNormally(finishing);
                }
            } finally {
                finishing.release();
            }
        }

        /**
         * Leaves the exit status to the step and the job listeners.
         *
         * <p>An abend's status comes from {@link BatchConfig}'s listeners translating
         * {@link AbendException}, and a completed run's return code of
         * {@value #RETURN_CODE_REJECTS_PRESENT} is contributed here when the trailer set one - which is what
         * carries {@code MOVE 4 TO RETURN-CODE} ({@code :230}) onto the step's exit code, and with it onto
         * the process exit code (gate G35). A clean run returns {@code null}, which Spring Batch reads as "no
         * change".
         *
         * @param stepExecution the execution finishing; must not be {@code null}
         * @return the exit status carrying return code {@value #RETURN_CODE_REJECTS_PRESENT} when the run
         *         rejected anything, otherwise {@code null}
         */
        @Override
        public ExitStatus afterStep(StepExecution stepExecution) {
            Objects.requireNonNull(stepExecution, "A step execution is required to finish a delegate");
            if (outcome == null || outcome.returnCode() == RETURN_CODE_CLEAN) {
                return null;
            }
            return stepExecution.getExitStatus()
                    .replaceExitCode(Integer.toString(outcome.returnCode()));
        }

        /**
         * What the trailer reported, once the loop has ended at end of file.
         *
         * <p>Set by the epilogue in {@link #read()} rather than by {@link #close()}, so it is already
         * available when Spring Batch invokes {@link #afterStep(StepExecution)} - which it does before it
         * closes the item streams.
         *
         * @return the run outcome, or empty for a run that abended or has not finished
         */
        public Optional<RunOutcome> runOutcome() {
            return Optional.ofNullable(outcome);
        }

        /**
         * How many records the writer stage saw posted.
         *
         * @return the count, never negative
         */
        public long postedCount() {
            return posted;
        }

        /**
         * How many records the writer stage saw rejected.
         *
         * @return the count, never negative
         */
        public long rejectedCount() {
            return rejected;
        }

        /**
         * The run in progress, exposed so a test can assert on {@code WORKING-STORAGE} between chunks.
         *
         * @return the open run, or empty before the stream is opened and after it is closed
         */
        Optional<PostingRun> currentRun() {
            return Optional.ofNullable(run);
        }

        /**
         * The open run, or a diagnosis of the callback order that reached here without one.
         *
         * @return the run; never {@code null}
         * @throws IllegalStateException if no run is open
         */
        private PostingRun requireRun() {
            if (run == null) {
                throw new IllegalStateException("No run is open on this delegate, so there is nothing to "
                        + "address. open(ExecutionContext) performs the banner and the six OPEN paragraphs "
                        + "and close() performs the six CLOSE paragraphs, so this means a callback arrived "
                        + "before the stream was opened or after it was closed.");
            }
            return run;
        }
    }

    /**
     * A per-step-execution wrapper that builds a fresh {@link ChunkDelegate} for each execution instead of
     * closing over one.
     *
     * <p>A {@code Step} bean is built once and launched as often as the operator launches the job, so a
     * delegate captured in {@link TransactionValidationJob#transactionValidationStep()} would be shared by
     * every execution - including two that overlap. {@code CBTRN02C} has no such sharing to reproduce: each
     * JCL submission is its own address space with its own {@code WORKING-STORAGE} and its own six open
     * files.
     *
     * <p>The scope is a {@link ThreadLocal} keyed by the {@link StepExecution} rather than by call order,
     * because a chunk step registers one object as reader, stream and step listener at once, so
     * {@link #beforeStep(StepExecution)} can legitimately arrive more than once for one execution. Arriving
     * again for the <em>same</em> execution reuses that execution's delegate; arriving for a
     * <em>different</em> one while a scope is still held is refused, because silently replacing the first
     * would abandon its six open files.
     */
    static final class StepScopedChunkDelegate
            implements ItemStreamReader<DalyTranRecord>, ItemProcessor<DalyTranRecord, RecordOutcome>,
            ItemWriter<RecordOutcome>, StepExecutionListener {

        /** The job each scoped delegate is built over. */
        private final TransactionValidationJob job;

        /**
         * The scope belonging to the step execution running on this thread. Per-instance and {@code final};
         * the mutability is per-thread, never shared (practice B9, gate G53).
         */
        private final ThreadLocal<Scope> executionScope = new ThreadLocal<>();

        /**
         * Binds the wrapper to its job.
         *
         * @param job the job; must not be {@code null}
         * @throws NullPointerException if {@code job} is {@code null}
         */
        StepScopedChunkDelegate(TransactionValidationJob job) {
            this.job = Objects.requireNonNull(job, "A job is required to scope its chunk delegate");
        }

        /**
         * One step execution and the delegate that is its address space.
         *
         * @param stepExecution the execution the delegate belongs to
         * @param delegate      that execution's delegate
         */
        private record Scope(StepExecution stepExecution, ChunkDelegate delegate) {
        }

        /**
         * Establishes this execution's delegate.
         *
         * @param stepExecution the execution starting; must not be {@code null}
         * @throws NullPointerException  if {@code stepExecution} is {@code null}
         * @throws IllegalStateException if a different execution is already scoped to this thread
         */
        @Override
        public void beforeStep(StepExecution stepExecution) {
            Objects.requireNonNull(stepExecution, "A step execution is required to scope a delegate");
            Scope existing = executionScope.get();
            if (existing != null) {
                if (existing.stepExecution() != stepExecution) {
                    throw new IllegalStateException("A chunk delegate is already scoped to this thread for "
                            + "a different step execution. Each step execution gets its own delegate, and "
                            + "one cannot be started inside another on the same thread: replacing the first "
                            + "would abandon the six files it has open.");
                }
                existing.delegate().beforeStep(stepExecution);
                return;
            }
            ChunkDelegate delegate = job.newChunkDelegate();
            executionScope.set(new Scope(stepExecution, delegate));
            delegate.beforeStep(stepExecution);
        }

        /**
         * Forwards the stream open - the banner and the six {@code OPEN}s - to this execution's delegate.
         *
         * @param executionContext the step's execution context; must not be {@code null}
         */
        @Override
        public void open(ExecutionContext executionContext) {
            requireScopedDelegate("open").open(executionContext);
        }

        /**
         * Forwards {@code 1000-DALYTRAN-GET-NEXT} to this execution's delegate.
         *
         * @return the next record, or {@code null} at end of file
         */
        @Override
        public DalyTranRecord read() {
            return requireScopedDelegate("read").read();
        }

        /**
         * Forwards the record body to this execution's delegate.
         *
         * @param item the record read; must not be {@code null}
         * @return the outcome of the record body; never {@code null}
         */
        @Override
        public RecordOutcome process(DalyTranRecord item) {
            return requireScopedDelegate("process").process(item);
        }

        /**
         * Forwards the chunk bookkeeping to this execution's delegate.
         *
         * @param chunk the processed outcomes; must not be {@code null}
         */
        @Override
        public void write(Chunk<? extends RecordOutcome> chunk) {
            requireScopedDelegate("write").write(chunk);
        }

        /**
         * Forwards the stream update to this execution's delegate when one is scoped.
         *
         * @param executionContext the step's execution context; must not be {@code null}
         */
        @Override
        public void update(ExecutionContext executionContext) {
            Scope scope = executionScope.get();
            if (scope != null) {
                scope.delegate().update(executionContext);
            }
        }

        /**
         * Forwards the stream close - the handle release - and then releases the scope.
         *
         * <p>The scope is released here rather than in {@code afterStep}, because Spring Batch invokes the
         * step listeners before closing the streams and clearing it there would leave the release with
         * nothing to address. A close with no delegate scoped is a no-op: a stream may be closed without
         * ever having been opened.
         */
        @Override
        public void close() {
            Scope scope = executionScope.get();
            if (scope == null) {
                return;
            }
            try {
                scope.delegate().close();
            } finally {
                executionScope.remove();
            }
        }

        /**
         * Forwards the exit status to this execution's delegate, which contributes
         * {@value #RETURN_CODE_REJECTS_PRESENT} when the run rejected anything.
         *
         * @param stepExecution the execution finishing; must not be {@code null}
         * @return the delegate's exit status, or {@code null} when no delegate is scoped or the run was
         *         clean
         */
        @Override
        public ExitStatus afterStep(StepExecution stepExecution) {
            Objects.requireNonNull(stepExecution, "A step execution is required to finish a delegate");
            Scope scope = executionScope.get();
            return scope == null ? null : scope.delegate().afterStep(stepExecution);
        }

        /**
         * The delegate currently scoped to the calling thread, if any.
         *
         * <p>Exposed so a test can assert that two step executions were handed different delegates, and that
         * the scope is released when the step ends.
         *
         * @return the scoped delegate, or empty when no step execution is in progress on this thread
         */
        Optional<ChunkDelegate> scopedDelegate() {
            return Optional.ofNullable(executionScope.get()).map(Scope::delegate);
        }

        /**
         * This execution's delegate, or a diagnosis of the callback order that reached here without one.
         *
         * @param callback the callback name, for the message
         * @return the scoped delegate; never {@code null}
         * @throws IllegalStateException if no delegate is scoped to the calling thread
         */
        private ChunkDelegate requireScopedDelegate(String callback) {
            Scope scope = executionScope.get();
            if (scope == null) {
                throw new IllegalStateException("No chunk delegate is scoped to this thread, so '"
                        + callback + "' has no run to address. beforeStep establishes the scope and it is "
                        + "released by close, so this means the callback arrived outside a step execution "
                        + "or on a different thread from the one executing the step.");
            }
            return scope.delegate();
        }
    }
}

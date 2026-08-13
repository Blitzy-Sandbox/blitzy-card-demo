package com.vsergeychik.carddemo.transaction;

import com.vsergeychik.carddemo.account.AccountRepository;
import com.vsergeychik.carddemo.account.model.AccountRecord;
import com.vsergeychik.carddemo.card.CardXrefRepository;
import com.vsergeychik.carddemo.card.model.CardXrefRecord;
import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.DatasetRelation.BackendDiagnostic;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.config.BatchConfig;
import com.vsergeychik.carddemo.config.BatchConfig.StepContract;
import com.vsergeychik.carddemo.common.DatasetUnitOfWork;
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
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
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
 * <p>Nothing here reads a console, blocks on input or consults a wall clock: the one time source is the
 * injected {@link Clock}, which is what makes {@code TRAN-PROC-TS} reproducible for a parity case, and
 * {@code SYSOUT} is an injectable {@link SysoutSink} rather than a hard-wired stream.
 */
@Configuration(TransactionValidationJob.CONFIGURATION_BEAN_NAME)
public class TransactionValidationJob {
    private static final Log LOG = LogFactory.getLog(TransactionValidationJob.class);

    public static final String CONFIGURATION_BEAN_NAME = "transactionValidationJobConfiguration";

    public static final String JOB_KEY = "transaction-validation-job";

    public static final String JOB_NAME = "transactionValidationJob";

    public static final String STEP_BEAN_NAME = "transactionValidationStep";

    /**
     * The COBOL {@code PROGRAM-ID} this job was translated from.
     */
    public static final String PROGRAM_ID = "CBTRN02C";

    /**
     * The JCL step name: {@code app/jcl/POSTTRAN.jcl:23}.
     */
    public static final String STEP_NAME = "STEP15";

    /**
     * The JCL this job's contract is transcribed from, quoted in every construction-time refusal.
     */
    public static final String JCL_REFERENCE = "app/jcl/POSTTRAN.jcl";

    /**
     * The one step {@link #JCL_REFERENCE} declares, ungated.
     */
    public static final List<StepContract> REQUIRED_STEPS =
            List.of(new StepContract(STEP_NAME, PROGRAM_ID, false));

    public static final int CHUNK_SIZE = 1;

    public static final String DALYTRAN_DD_NAME = DalyTranRepository.DD_NAME;

    public static final String TRANFILE_DD_NAME = TransactionRepository.INPUT_DD_NAME;

    public static final String XREFFILE_DD_NAME = CardXrefRepository.BATCH_DD_NAME;

    /**
     * {@code //DALYREJS} - the rejected-transaction generation, {@code RECFM=F LRECL=430}.
     */
    public static final String DALYREJS_DD_NAME = DalyRejectWriter.DD_NAME;

    public static final String ACCTFILE_DD_NAME = AccountRepository.BATCH_DD_NAME;

    public static final String TCATBALF_DD_NAME = TranCatBalRepository.DD_NAME;

    /**
     * {@code MOVE 8 TO APPL-RESULT} - the assumed failure every I/O paragraph starts from.
     */
    public static final int APPL_RESULT_ASSUMED_FAILURE = AbendException.RETURN_CODE_ASSUMED_FAILURE;

    /**
     * {@code MOVE 0 TO APPL-RESULT} - {@code 88 APPL-AOK VALUE 0}.
     */
    public static final int APPL_RESULT_AOK = FileStatus.APPL_AOK;

    /**
     * {@code MOVE 16 TO APPL-RESULT} - {@code 88 APPL-EOF VALUE 16}, set only by the DALYTRAN read.
     */
    public static final int APPL_RESULT_EOF = FileStatus.APPL_EOF;

    /**
     * {@code MOVE 12 TO APPL-RESULT} - the fatal arm of every guard chain in the program.
     */
    public static final int APPL_RESULT_FATAL = AbendException.RETURN_CODE_IO_ERROR;

    /**
     * {@code MOVE 4 TO RETURN-CODE} at {@code :230} - the warning a run with any reject reports.
     */
    public static final int RETURN_CODE_REJECTS_PRESENT = AbendException.RETURN_CODE_WARNING;

    /**
     * {@code RETURN-CODE} of a run that rejected nothing.
     */
    public static final int RETURN_CODE_CLEAN = AbendException.RETURN_CODE_OK;

    private static final char PERMANENT_ERROR_FEEDBACK_CODE = 0;

    /**
     * The status reported for a failure the COBOL vocabulary has no specific code for.
     */
    public static final String PERMANENT_ERROR_STATUS = "9" + PERMANENT_ERROR_FEEDBACK_CODE;

    /**
     * {@code MOVE 'Y' TO END-OF-FILE} at {@code :361}.
     */
    public static final String AT_END_OF_FILE = "Y";

    /**
     * {@code 01 END-OF-FILE PIC X(01) VALUE 'N'} at {@code :146}.
     */
    public static final String NOT_AT_END_OF_FILE = "N";

    /**
     * {@code MOVE 'Y' TO WS-CREATE-TRANCAT-REC} at {@code :478}.
     */
    public static final String CREATE_TRANCAT_REC = "Y";

    /**
     * {@code MOVE 'N' TO WS-CREATE-TRANCAT-REC} at {@code :473}.
     */
    public static final String DO_NOT_CREATE_TRANCAT_REC = "N";

    /**
     * {@code WS-TRANSACTION-COUNT} and {@code WS-REJECT-COUNT} are both {@code PIC 9(09)}
     * ({@code :185-186}), so both render zero-filled to nine digits wherever {@code DISPLAY} contributes
     * them.
     */
    public static final int COUNTER_WIDTH = 9;

    /**
     * {@code WS-TEMP-BAL PIC S9(09)V99} ({@code :187}) - nine integer digits and scale
     * {@value CobolDecimal#MONETARY_SCALE}.
     */
    public static final int WS_TEMP_BAL_INTEGER_DIGITS = 9;

    /**
     * The scale of {@code WS-TEMP-BAL}, and of every monetary field this job touches.
     */
    public static final int WS_TEMP_BAL_SCALE = CobolDecimal.MONETARY_SCALE;

    public static final int REASON_INVALID_CARD_NUMBER = DalyRejectWriter.REASON_INVALID_CARD_NUMBER;

    public static final int REASON_ACCOUNT_RECORD_NOT_FOUND =
            DalyRejectWriter.REASON_ACCOUNT_RECORD_NOT_FOUND;

    public static final int REASON_OVERLIMIT_TRANSACTION = DalyRejectWriter.REASON_OVERLIMIT_TRANSACTION;

    public static final int REASON_TRANSACTION_AFTER_EXPIRATION =
            DalyRejectWriter.REASON_TRANSACTION_AFTER_EXPIRATION;

    public static final int REASON_ACCOUNT_NOT_FOUND_ON_REWRITE =
            DalyRejectWriter.REASON_ACCOUNT_NOT_FOUND_ON_REWRITE;

    /**
     * {@code MOVE 0 TO WS-VALIDATION-FAIL-REASON} at {@code :208} - the "no reason yet" value.
     */
    public static final int REASON_NONE = DalyRejectWriter.REASON_NONE;

    /**
     * {@code MOVE SPACES TO WS-VALIDATION-FAIL-REASON-DESC} at {@code :209}.
     */
    public static final String DESC_SPACES = DalyRejectWriter.DESC_SPACES;

    public static final String START_OF_EXECUTION = "START OF EXECUTION OF PROGRAM CBTRN02C";

    public static final String END_OF_EXECUTION = "END OF EXECUTION OF PROGRAM CBTRN02C";

    public static final String ERROR_OPENING_DALYTRAN = "ERROR OPENING DALYTRAN";

    public static final String ERROR_OPENING_TRANFILE = "ERROR OPENING TRANSACTION FILE";

    public static final String ERROR_OPENING_XREFFILE = "ERROR OPENING CROSS REF FILE";

    public static final String ERROR_OPENING_DALYREJS = "ERROR OPENING DALY REJECTS FILE";

    public static final String ERROR_OPENING_ACCTFILE = "ERROR OPENING ACCOUNT MASTER FILE";

    public static final String ERROR_OPENING_TCATBALF = "ERROR OPENING TRANSACTION BALANCE FILE";

    public static final String ERROR_READING_DALYTRAN = "ERROR READING DALYTRAN FILE";

    public static final String ERROR_WRITING_DALYREJS = "ERROR WRITING TO REJECTS FILE";

    public static final String ERROR_READING_TCATBALF = "ERROR READING TRANSACTION BALANCE FILE";

    public static final String ERROR_WRITING_TCATBALF = "ERROR WRITING TRANSACTION BALANCE FILE";

    public static final String ERROR_REWRITING_TCATBALF = "ERROR REWRITING TRANSACTION BALANCE FILE";

    public static final String ERROR_WRITING_TRANFILE = "ERROR WRITING TO TRANSACTION FILE";

    public static final String ERROR_CLOSING_DALYTRAN = "ERROR CLOSING DALYTRAN FILE";

    public static final String ERROR_CLOSING_TRANFILE = "ERROR CLOSING TRANSACTION FILE";

    public static final String ERROR_CLOSING_XREFFILE = "ERROR CLOSING CROSS REF FILE";

    public static final String ERROR_CLOSING_DALYREJS = "ERROR CLOSING DAILY REJECTS FILE";

    public static final String ERROR_CLOSING_ACCTFILE = "ERROR CLOSING ACCOUNT FILE";

    public static final String ERROR_CLOSING_TCATBALF = "ERROR CLOSING TRANSACTION BALANCE FILE";

    /**
     * {@code :476} - the first operand of the not-found display, whose trailing space is inside the
     * literal.
     */
    public static final String TCATBAL_NOT_FOUND_PREFIX = "TCATBAL record not found for key : ";

    public static final String TCATBAL_NOT_FOUND_SUFFIX = ".. Creating.";

    public static final String TRANSACTIONS_PROCESSED_PREFIX = "TRANSACTIONS PROCESSED :";

    public static final String TRANSACTIONS_REJECTED_PREFIX = "TRANSACTIONS REJECTED  :";

    /**
     * {@code 01 DB2-FORMAT-TS PIC X(26)} at {@code :159}.
     */
    public static final int DB2_TIMESTAMP_LENGTH = 26;

    /**
     * {@code MOVE '-' TO DB2-STREEP-1 DB2-STREEP-2 DB2-STREEP-3} at {@code :702}.
     */
    public static final String DB2_TIMESTAMP_HYPHEN = "-";

    /**
     * {@code MOVE '.' TO DB2-DOT-1 DB2-DOT-2 DB2-DOT-3} at {@code :703}.
     */
    public static final String DB2_TIMESTAMP_DOT = ".";

    /**
     * {@code MOVE '0000' TO DB2-REST} at {@code :701} - {@code DB2-REST PIC X(04)}.
     */
    public static final String DB2_TIMESTAMP_REST = "0000";

    /**
     * {@code 01 COBOL-TS} at {@code :150-158}: 4+2+2+2+2+2+2+5 characters.
     */
    public static final int COBOL_TIMESTAMP_LENGTH = 21;

    private static final int NANOS_PER_HUNDREDTH = 10_000_000;

    private static final int YEAR_WIDTH = 4;

    private static final int TWO_DIGIT_WIDTH = 2;

    private static final int GREENWICH_OFFSET_WIDTH = 5;

    private static final int SECONDS_PER_HOUR = 3600;

    private static final int SECONDS_PER_MINUTE = 60;

    private final BatchConfig batchConfig;

    private final DalyTranRepository dalyTranRepository;

    private final CardXrefRepository cardXrefRepository;

    static final String WRITE_TCATBALF_VERB =
            "2700-A-CREATE-TCATBAL-REC WRITE FD-TRAN-CAT-BAL-RECORD";

    static final String REWRITE_TCATBALF_VERB =
            "2700-B-UPDATE-TCATBAL-REC REWRITE FD-TRAN-CAT-BAL-RECORD";

    static final String REWRITE_ACCTFILE_VERB = "2800-UPDATE-ACCOUNT-REC REWRITE FD-ACCTFILE-REC";

    static final String WRITE_TRANFILE_VERB = "2900-WRITE-TRANSACTION-FILE WRITE FD-TRANFILE-REC";

    static final String WRITE_DALYREJS_VERB = "2500-WRITE-REJECT-REC WRITE FD-REJS-RECORD";

    static final String DALYREJS_OPEN_DISPOSITION =
            "establish the " + DALYREJS_DD_NAME + " generation of app/jcl/POSTTRAN.jcl:34 (NEW)";

    static final String DALYREJS_ABNORMAL_DISPOSITION =
            "discard the " + DALYREJS_DD_NAME + " generation of app/jcl/POSTTRAN.jcl:34 (DELETE)";

    /**
     * The name carried into the boundary that opens {@value #TRANFILE_DD_NAME} in load mode.
     *
     * <p>A boundary is needed for the same reason {@value #DALYREJS_DD_NAME}'s open needs one, and only in
     * one of the open's three outcomes: over a cluster configured {@code REUSE}, VSAM load mode
     * <em>resets</em> the cluster, so the open performs a delete. This open runs in the
     * {@link org.springframework.batch.item.ItemStream} open callback, outside the chunk transaction and
     * on a connection whose {@code auto-commit} is disabled, so without a boundary of its own that reset
     * would be rolled back when the connection returned while the open had already reported {@code '00'} -
     * and the run would then post on top of records the source's open had discarded. Over the shipped
     * {@code NOREUSE} master nothing is mutated and the boundary wraps a read.
     */
    static final String TRANFILE_OPEN_DISPOSITION =
            "open " + TRANFILE_DD_NAME + " in VSAM load mode, resetting the cluster if it is REUSE "
                    + "(app/cbl/CBTRN02C.cbl:256 over app/jcl/POSTTRAN.jcl:28)";

    /** {@value #ACCTFILE_DD_NAME} - opened {@code I-O}, read by key and rewritten on every posting. */
    private final AccountRepository accountRepository;

    private final TranCatBalRepository tranCatBalRepository;

    private final TransactionRepository transactionRepository;

    private final DalyRejectWriter dalyRejectWriter;

    private final DatasetUnitOfWork unitOfWork;

    private final Clock clock;

    private final Charset datasetCharset;

    private final FixedWidthCodec codec;

    private final SysoutSink sysoutSink;

    private final StepContract stepContract;

    /**
     * Wires the six collaborators and proves the contract before any run can start.
     *
     * <p>A declared parameter here would be an input the COBOL program never receives, and Spring Batch
     * identifies an instance by its parameters, so it would silently resolve submissions to a different job
     * instance.
     *
     * @param batchConfig the batch scaffolding; required
     * @param dalyTranRepository {@link #DALYTRAN_DD_NAME}; required
     * @param cardXrefRepository {@link #XREFFILE_DD_NAME}; required
     * @param accountRepository {@link #ACCTFILE_DD_NAME}; required
     * @param tranCatBalRepository {@link #TCATBALF_DD_NAME}; required
     * @param transactionRepository {@link #TRANFILE_DD_NAME}; required
     * @param dalyRejectWriter {@link #DALYREJS_DD_NAME}; required
     * @param unitOfWork the per-verb durability seam and the boundary the two {@link #DALYREJS_DD_NAME}
     *     disposition statements are applied through; required
     * @param sysoutSinkProvider the {@code SYSOUT} destination; may resolve to no bean, in which case the
     *     process's standard output is used in the dataset code page
     * @param clock the clock behind {@code FUNCTION CURRENT-DATE}; required
     * @throws NullPointerException if any required collaborator or the provider is {@code null}
     * @throws IllegalStateException if this job's {@code carddemo.jobs} contract names another program,
     *     declares another step sequence, gates its only step, declares any job parameter
     */
    public TransactionValidationJob(BatchConfig batchConfig,
            DalyTranRepository dalyTranRepository,
            CardXrefRepository cardXrefRepository,
            AccountRepository accountRepository,
            TranCatBalRepository tranCatBalRepository,
            TransactionRepository transactionRepository,
            DalyRejectWriter dalyRejectWriter,
            DatasetUnitOfWork unitOfWork,
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
        this.unitOfWork = Objects.requireNonNull(unitOfWork, "The dataset unit of work is required: "
                + "this program posts, and every WRITE and REWRITE it issues must be durable the moment "
                + "it completes - CBTRN02C takes no syncpoint and every dataset it touches is "
                + "RECOVERY(NONE), so a chunk transaction around the three verbs of :440-442 would undo "
                + "writes the source leaves in place. " + DALYREJS_DD_NAME + " adds a second need: it is "
                + "the one DD in this step whose disposition is DISP=(NEW,CATLG,DELETE) "
                + "(app/jcl/POSTTRAN.jcl:34), and Spring Batch runs the ItemStream open and close "
                + "callbacks OUTSIDE the chunk transaction - so the generation clear and the abnormal "
                + "discard need a boundary of their own or the pool, which hands out connections with "
                + "auto-commit disabled, rolls them back while this job reports them applied");
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

    private static StepContract requireUngatedStep(BatchConfig scaffolding) {
        List<StepContract> declared =
                scaffolding.requireSteps(JOB_KEY, REQUIRED_STEPS, JCL_REFERENCE);
        return declared.get(0);
    }

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

    private static void requireStepDatasets(BatchConfig scaffolding) {
        scaffolding.requireSameDataset(JOB_KEY, DALYTRAN_DD_NAME, DalyTranRepository.DD_NAME);
        scaffolding.requireSameDataset(JOB_KEY, TRANFILE_DD_NAME, TransactionRepository.CICS_FILE_NAME);
        scaffolding.requireSameDataset(JOB_KEY, XREFFILE_DD_NAME, CardXrefRepository.BASE_DD_NAME);
        scaffolding.requireSameDataset(JOB_KEY, DALYREJS_DD_NAME, DalyRejectWriter.DD_NAME);
        scaffolding.requireSameDataset(JOB_KEY, ACCTFILE_DD_NAME, AccountRepository.CICS_FILE_NAME);
        scaffolding.requireSameDataset(JOB_KEY, TCATBALF_DD_NAME, TranCatBalRepository.DD_NAME);
    }

    /**
     * The job {@link #JCL_REFERENCE} translates to: one step, no parameters.
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

    public ChunkDelegate newChunkDelegate() {
        return new ChunkDelegate(this);
    }

    PostingRun newRun(SysoutSink sysout) {
        return new PostingRun(this, sysout);
    }

    /**
     * The job parameters {@link #JCL_REFERENCE} declares: none.
     *
     * @return the declared parameters, always empty; never {@code null}
     */
    public JobParameters jobParameters() {
        return batchConfig.contract(JOB_KEY).jobParameters();
    }

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

    /**
     * Runs the whole program against the injected {@code SYSOUT} sink.
     *
     * @return what the run did: the two counters and the {@code RETURN-CODE}
     * @throws IllegalStateException if no unit of work is open, as {@link #postTransactions(SysoutSink)}
     *     describes
     * @throws AbendException if any file operation the program checks reports a status it treats as fatal
     */
    public RunOutcome postTransactions() {
        return postTransactions(sysoutSink);
    }

    /**
     * Runs the whole program, emitting every {@code DISPLAY} to the given sink.
     *
     * @param sysout where each {@code DISPLAY} goes; must not be {@code null}
     * @return what the run did; never {@code null}
     * @throws NullPointerException if {@code sysout} is {@code null}
     * @throws IllegalStateException if no unit of work is open, in which case nothing has been attempted
     *     and no {@code DISPLAY} has been emitted
     * @throws AbendException if any file operation the program checks reports a fatal status
     */
    public RunOutcome postTransactions(SysoutSink sysout) {
        Objects.requireNonNull(sysout, "A SYSOUT sink is required to run " + PROGRAM_ID);
        DatasetUnitOfWork.requireActiveToPersist("A run of " + PROGRAM_ID + " (" + JCL_REFERENCE + " "
                + STEP_NAME + "), which posts every accepted record and writes every rejected one",
                TCATBALF_DD_NAME + ", " + ACCTFILE_DD_NAME + ", " + TRANFILE_DD_NAME + " and "
                        + DALYREJS_DD_NAME);
        PostingRun run = newRun(sysout);
        try {
            sysout.write(START_OF_EXECUTION);

            run.openFiles();

            while (!run.workingStorage().endOfFileIsYes()) {
                DalyTranRecord item = run.dalytranGetNext();
                if (item != null) {
                    run.processRecord(item);
                }
            }

            run.closeFiles();

            return run.writeTrailer();
        } finally {
            run.release();
        }
    }

    /**
     * Composes {@code DB2-FORMAT-TS} - the 26 characters {@code 2000-POST-TRANSACTION} moves into
     * {@code TRAN-PROC-TS} at {@code :438}.
     *
     * <p>{@code DB2-MIL PIC 9(002)} is fed from {@code COB-MIL}, the hundredths of a second
     * {@code FUNCTION CURRENT-DATE} reports, so the value is never finer than a hundredth however precise
     * the injected clock is - which is why {@code DB2-REST} is a literal rather than a measured value.
     *
     * @return exactly {@value #DB2_TIMESTAMP_LENGTH} characters
     */
    public String db2FormatTimestamp() {
        CobolTimestamp current = currentDate();

        return current.yyyy()
                + DB2_TIMESTAMP_HYPHEN
                + current.mm()
                + DB2_TIMESTAMP_HYPHEN
                + current.dd()
                + DB2_TIMESTAMP_HYPHEN
                + current.hh()
                + DB2_TIMESTAMP_DOT
                + current.min()
                + DB2_TIMESTAMP_DOT
                + current.ss()
                + DB2_TIMESTAMP_DOT
                + current.mil()
                + DB2_TIMESTAMP_REST;
    }

    /**
     * {@code FUNCTION CURRENT-DATE} into {@code COBOL-TS} - {@code app/cbl/CBTRN02C.cbl:150-158}.
     *
     * <p>All eight are modelled even though {@link #db2FormatTimestamp()} reads seven of them, because the
     * eighth occupies declared bytes of {@code COBOL-TS} and a partial model of a group item is a model
     * that cannot be checked.
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
     * @param mm {@code COB-MM PIC X(02)} - the month
     * @param dd {@code COB-DD PIC X(02)} - the day
     * @param hh {@code COB-HH PIC X(02)} - the hour, {@code 00} to {@code 23}
     * @param min {@code COB-MIN PIC X(02)} - the minute
     * @param ss {@code COB-SS PIC X(02)} - the second
     * @param mil {@code COB-MIL PIC X(02)} - hundredths of a second, the whole of the intrinsic's
     *     sub-second precision
     * @param rest {@code COB-REST PIC X(05)} - the offset from Greenwich, never read by this program
     */
    public record CobolTimestamp(String yyyy, String mm, String dd, String hh, String min, String ss,
                                 String mil, String rest) {
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

    public interface SysoutSink {
        void write(String line);
    }

    /**
     * The process's standard output, in a named code page.
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

    /**
     * What processing one daily-transaction record did: the chunk step's output item.
     *
     * @param recordNumber {@code WS-TRANSACTION-COUNT} after this record, so the first record is 1
     * @param dalytranId the record's {@code DALYTRAN-ID}, untrimmed
     * @param validationFailReason {@code WS-VALIDATION-FAIL-REASON} as the mainline tested it at
     *     {@code :211} - so {@link #REASON_NONE} for a posted record, and never
     *     {@link #REASON_ACCOUNT_NOT_FOUND_ON_REWRITE}
     * @param validationFailReasonDesc {@code WS-VALIDATION-FAIL-REASON-DESC} at the same instant, padded to
     *     nothing - the reject writer pads it to its declared 76 characters
     * @param tempBal {@code WS-TEMP-BAL} after {@code :403}, at scale {@value CobolDecimal#MONETARY_SCALE},
     *     or {@code null} when the account lookup never reached the {@code COMPUTE}
     * @param posted whether {@code 2000-POST-TRANSACTION} ran
     * @param rejected whether {@code 2500-WRITE-REJECT-REC} ran, which is also the only thing that
     *     increments {@code WS-REJECT-COUNT}
     * @param tranCatBalCreated whether {@code 2700-A-CREATE-TCATBAL-REC} ran rather than {@code 2700-B}
     * @param accountRewriteInvalidKey whether the account {@code REWRITE} at {@code :554} reported the
     *     {@code INVALID KEY} condition and therefore set reason 109
     * @param procTimestamp the 26-character {@code TRAN-PROC-TS} written, or {@code null} when nothing was
     *     posted
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
     * @param rejectCount {@code WS-REJECT-COUNT} at {@code GOBACK}
     * @param returnCode {@link #RETURN_CODE_REJECTS_PRESENT} when {@code rejectCount} is positive,
     *     otherwise {@link #RETURN_CODE_CLEAN}
     */
    public record RunOutcome(long transactionCount, long rejectCount, int returnCode) {
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

    /**
     * One run's {@code WORKING-STORAGE}: {@code APPL-RESULT}, {@code END-OF-FILE}, the six
     * {@code FILE STATUS} items, {@code WS-VALIDATION-TRAILER}, {@code WS-COUNTERS} and {@code WS-FLAGS}.
     *
     * <p>{@code WS-TEMP-BAL} is a {@link BigDecimal} at scale exactly {@value CobolDecimal#MONETARY_SCALE},
     * because {@code :187} declares it {@code PIC S9(09)V99}.
     */
    static final class WorkingStorage {
        private int applResult = APPL_RESULT_AOK;

        private String endOfFile = NOT_AT_END_OF_FILE;

        private String dalytranStatus = FileStatus.OK;

        private String tranfileStatus = FileStatus.OK;

        private String xreffileStatus = FileStatus.OK;

        private String dalyrejsStatus = FileStatus.OK;

        private String acctfileStatus = FileStatus.OK;

        private String tcatbalfStatus = FileStatus.OK;

        private int validationFailReason = REASON_NONE;

        private String validationFailReasonDesc = DESC_SPACES;

        private long transactionCount;

        private long rejectCount;

        private BigDecimal tempBal = CobolDecimal.zero(WS_TEMP_BAL_SCALE);

        private String createTrancatRec = DO_NOT_CREATE_TRANCAT_REC;

        void moveToApplResult(int value) {
            applResult = value;
        }

        boolean applAok() {
            return applResult == APPL_RESULT_AOK;
        }

        boolean applEof() {
            return applResult == APPL_RESULT_EOF;
        }

        int applResult() {
            return applResult;
        }

        void moveEndOfFileYes() {
            endOfFile = AT_END_OF_FILE;
        }

        boolean endOfFileIsYes() {
            return AT_END_OF_FILE.equals(endOfFile);
        }

        String endOfFile() {
            return endOfFile;
        }

        void resetValidationTrailer() {
            validationFailReason = REASON_NONE;
            validationFailReasonDesc = DESC_SPACES;
        }

        void moveToValidationTrailer(int reason, String description) {
            validationFailReason = reason;
            validationFailReasonDesc = description;
        }

        boolean validationFailReasonIsZero() {
            return validationFailReason == REASON_NONE;
        }

        int validationFailReason() {
            return validationFailReason;
        }

        String validationFailReasonDesc() {
            return validationFailReasonDesc;
        }

        void addOneToTransactionCount() {
            transactionCount++;
        }

        long transactionCount() {
            return transactionCount;
        }

        void addOneToRejectCount() {
            rejectCount++;
        }

        long rejectCount() {
            return rejectCount;
        }

        BigDecimal computeTempBal(BigDecimal cycleCredit, BigDecimal cycleDebit, BigDecimal amount) {
            BigDecimal difference = CobolDecimal.subtract(cycleCredit, cycleDebit, WS_TEMP_BAL_SCALE);
            tempBal = CobolDecimal.storeAtPicture(CobolDecimal.add(difference, amount, WS_TEMP_BAL_SCALE),
                    WS_TEMP_BAL_INTEGER_DIGITS, WS_TEMP_BAL_SCALE);
            return tempBal;
        }

        /**
         * {@code WS-TEMP-BAL}, at scale exactly {@value CobolDecimal#MONETARY_SCALE}.
         */
        BigDecimal tempBal() {
            return tempBal;
        }

        void moveDoNotCreateTrancatRec() {
            createTrancatRec = DO_NOT_CREATE_TRANCAT_REC;
        }

        void moveCreateTrancatRec() {
            createTrancatRec = CREATE_TRANCAT_REC;
        }

        boolean createTrancatRecIsYes() {
            return CREATE_TRANCAT_REC.equals(createTrancatRec);
        }

        void moveDalytranStatus(String status) {
            dalytranStatus = requireStatus(status, "DALYTRAN-STATUS");
        }

        String dalytranStatus() {
            return dalytranStatus;
        }

        void moveTranfileStatus(String status) {
            tranfileStatus = requireStatus(status, "TRANFILE-STATUS");
        }

        String tranfileStatus() {
            return tranfileStatus;
        }

        void moveXreffileStatus(String status) {
            xreffileStatus = requireStatus(status, "XREFFILE-STATUS");
        }

        String xreffileStatus() {
            return xreffileStatus;
        }

        void moveDalyrejsStatus(String status) {
            dalyrejsStatus = requireStatus(status, "DALYREJS-STATUS");
        }

        String dalyrejsStatus() {
            return dalyrejsStatus;
        }

        void moveAcctfileStatus(String status) {
            acctfileStatus = requireStatus(status, "ACCTFILE-STATUS");
        }

        String acctfileStatus() {
            return acctfileStatus;
        }

        void moveTcatbalfStatus(String status) {
            tcatbalfStatus = requireStatus(status, "TCATBALF-STATUS");
        }

        String tcatbalfStatus() {
            return tcatbalfStatus;
        }

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

    /**
     * One execution of {@code CBTRN02C} - its {@code WORKING-STORAGE}, its six open files and every
     * paragraph of its {@code PROCEDURE DIVISION}, each a named method carrying the source lines it
     * reproduces.
     *
     * <p>A COBOL program's storage belongs to its execution, so two runs never share a counter, a file
     * handle or a record area.
     */
    static final class PostingRun {
        private final TransactionValidationJob job;

        private final SysoutSink sysout;

        private final WorkingStorage workingStorage = new WorkingStorage();

        private DalyTranRepository.DalytranFile dalytranFile;

        private TransactionRepository.LoadModeFile tranfile;

        private CardXrefRepository.BrowseCursor xreffile;

        private DalyRejectWriter.RejectsFile dalyrejs;

        private AccountRepository.AccountFile acctfile;

        private TranCatBalRepository.TranCatBalFile tcatbalf;

        private CardXrefRecord cardXrefRecord;

        private AccountRecord accountRecord;

        private TranCatBalRecord tranCatBalRecord;

        private final TranRecord tranRecord;

        private boolean closedNormally;

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

        WorkingStorage workingStorage() {
            return workingStorage;
        }

        SysoutSink sysout() {
            return sysout;
        }

        CardXrefRecord cardXrefRecord() {
            return cardXrefRecord;
        }

        AccountRecord accountRecord() {
            return accountRecord;
        }

        TranCatBalRecord tranCatBalRecord() {
            return tranCatBalRecord;
        }

        TranRecord tranRecord() {
            return tranRecord;
        }

        void openFiles() {
            dalytranOpen();
            tranfileOpen();
            xreffileOpen();
            dalyrejsOpen();
            acctfileOpen();
            tcatbalfOpen();
        }

        void dalytranOpen() {
            workingStorage.moveToApplResult(APPL_RESULT_ASSUMED_FAILURE);
            dalytranFile = job.dalyTranRepository.open();
            String status = dalytranFile.openStatus();
            workingStorage.moveDalytranStatus(status);
            applResultFromOkStatus(status);
            if (!workingStorage.applAok()) {
                throw reportAndAbend(ERROR_OPENING_DALYTRAN, status);
            }
        }

        /**
         * {@code 0100-TRANFILE-OPEN} - {@code app/cbl/CBTRN02C.cbl:254-270}.
         *
         * <p><strong>The source verb is {@code OPEN OUTPUT} on an {@code ORGANIZATION IS INDEXED} file
         * ({@code :34-38}), which is VSAM load mode - and load mode requires an empty base
         * cluster.</strong> {@link TransactionRepository#openLoadMode()} issues that verb and derives its
         * outcome from the dataset's own record count and its configured {@code REUSE} attribute, with no
         * data-definition statement on any path (gate <strong>G44</strong>). Over a {@code REUSE} cluster
         * that outcome includes the reset load mode performs, which is why this open is applied through
         * {@link DatasetUnitOfWork#persistDisposition(String, java.util.function.Supplier)}: the reset is a
         * mutation issued from the {@link org.springframework.batch.item.ItemStream} open callback, outside
         * the chunk transaction, and without a boundary of its own it would be rolled back after the open
         * had already reported {@code '00'}.
         *
         * <p>Against the catalogued master that answer is a failure, and the failure is the point.
         * {@code app/jcl/POSTTRAN.jcl:28-29} binds {@code TRANFILE} to the existing
         * {@code AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS} with {@code DISP=SHR}, and
         * {@code app/catlg/LISTCAT.txt:3595-3597} records that cluster as {@code NOREUSE} holding
         * {@code REC-TOTAL 311}. So the open reports {@link FileStatus#OPEN_MODE_CONFLICT},
         * {@code :257-261} leaves {@code APPL-RESULT} at 12, and {@code :262-268} displays
         * {@code 'ERROR OPENING TRANSACTION FILE'} and abends. This is the <em>second</em> open of six, so
         * the abend happens before {@code 0200-XREFFILE-OPEN} and long before
         * {@code 1000-DALYTRAN-GET-NEXT} reads a record: no category balance, no account balance and no
         * transaction record is touched, and the four opens after this one emit nothing.
         *
         * <p>Against an empty master the open succeeds and the whole job runs, which is what makes this a
         * derived outcome rather than a hard-coded refusal.
         *
         * @throws AbendException if the open does not report {@code '00'}
         */
        void tranfileOpen() {
            workingStorage.moveToApplResult(APPL_RESULT_ASSUMED_FAILURE);                     //     L255
            // Load mode resets a REUSE cluster, so the open can mutate - and this runs in the ItemStream
            // open callback, outside the chunk transaction. Applied through its own boundary so that a
            // reset is committed rather than reported and then rolled back when the connection returns.
            tranfile = job.unitOfWork.persistDisposition(TRANFILE_OPEN_DISPOSITION,
                    job.transactionRepository::openLoadMode);                                 //     L256
            String status = tranfile.openStatus();
            workingStorage.moveTranfileStatus(status);
            applResultFromOkStatus(status);
            if (!workingStorage.applAok()) {
                throw reportAndAbend(ERROR_OPENING_TRANFILE, status);
            }
        }

        void xreffileOpen() {
            workingStorage.moveToApplResult(APPL_RESULT_ASSUMED_FAILURE);
            xreffile = job.cardXrefRepository.openBrowse();
            String status = xreffile.openStatus();
            workingStorage.moveXreffileStatus(status);
            applResultFromOkStatus(status);
            if (!workingStorage.applAok()) {
                throw reportAndAbend(ERROR_OPENING_XREFFILE, status);
            }
        }

        void dalyrejsOpen() {
            workingStorage.moveToApplResult(APPL_RESULT_ASSUMED_FAILURE);
            dalyrejs = job.unitOfWork.persistDisposition(DALYREJS_OPEN_DISPOSITION,
                    job.dalyRejectWriter::openOutput);
            String status = statusOf(dalyrejs.openOutcome());
            workingStorage.moveDalyrejsStatus(status);
            applResultFromOkStatus(status);
            if (!workingStorage.applAok()) {
                throw reportAndAbend(ERROR_OPENING_DALYREJS, status);
            }
        }

        void acctfileOpen() {
            workingStorage.moveToApplResult(APPL_RESULT_ASSUMED_FAILURE);
            acctfile = job.accountRepository.open(AccountRepository.OpenMode.I_O);
            String status = acctfile.openStatus();
            workingStorage.moveAcctfileStatus(status);
            applResultFromOkStatus(status);
            if (!workingStorage.applAok()) {
                throw reportAndAbend(ERROR_OPENING_ACCTFILE, status);
            }
        }

        void tcatbalfOpen() {
            workingStorage.moveToApplResult(APPL_RESULT_ASSUMED_FAILURE);
            tcatbalf = job.tranCatBalRepository.open(TranCatBalRepository.OpenMode.I_O);
            String status = tcatbalf.openStatus();
            workingStorage.moveTcatbalfStatus(status);
            applResultFromOkStatus(status);
            if (!workingStorage.applAok()) {
                throw reportAndAbend(ERROR_OPENING_TCATBALF, status);
            }
        }

        DalyTranRecord dalytranGetNext() {
            requireOpen();
            DalyTranRepository.ReadResult result = dalytranFile.readNext();
            String status = result.status();
            workingStorage.moveDalytranStatus(status);
            if (FileStatus.OK.equals(status)) {
                workingStorage.moveToApplResult(APPL_RESULT_AOK);
            } else if (FileStatus.END_OF_FILE.equals(status)) {
                workingStorage.moveToApplResult(APPL_RESULT_EOF);
            } else {
                workingStorage.moveToApplResult(APPL_RESULT_FATAL);
            }
            if (workingStorage.applAok()) {
                return result.dalyTran().orElseThrow(() -> new IllegalStateException(
                        "A read of " + DALYTRAN_DD_NAME + " reported file status " + FileStatus.OK
                                + " with no decoded record, so READ ... INTO DALYTRAN-RECORD had nothing "
                                + "to transfer. Only the success arm carries a record, and it always "
                                + "does."));
            }
            if (workingStorage.applEof()) {
                workingStorage.moveEndOfFileYes();
                return null;
            }
            throw reportAndAbend(ERROR_READING_DALYTRAN, status);
        }

        RecordOutcome processRecord(DalyTranRecord item) {
            Objects.requireNonNull(item, "A daily-transaction record is required to process one");
            requireOpen();

            workingStorage.addOneToTransactionCount();
            workingStorage.resetValidationTrailer();
            validateTran(item);

            int reasonAsTested = workingStorage.validationFailReason();
            String descAsTested = workingStorage.validationFailReasonDesc();
            BigDecimal tempBalAsComputed = reachedCreditLimitTest(reasonAsTested)
                    ? workingStorage.tempBal()
                    : null;

            if (workingStorage.validationFailReasonIsZero()) {
                PostingResult posting = postTransaction(item);
                return new RecordOutcome(workingStorage.transactionCount(), item.dalytranId(),
                        reasonAsTested, descAsTested, tempBalAsComputed, true, false,
                        posting.tranCatBalCreated(), posting.accountRewriteInvalidKey(),
                        posting.procTimestamp());
            }
            workingStorage.addOneToRejectCount();
            writeRejectRec(item);
            return new RecordOutcome(workingStorage.transactionCount(), item.dalytranId(),
                    reasonAsTested, descAsTested, tempBalAsComputed, false, true, false, false, null);
        }

        private boolean reachedCreditLimitTest(int reason) {
            return reason != REASON_INVALID_CARD_NUMBER && reason != REASON_ACCOUNT_RECORD_NOT_FOUND;
        }

        void validateTran(DalyTranRecord item) {
            lookupXref(item);
            if (workingStorage.validationFailReasonIsZero()) {
                lookupAcct(item);
            }
        }

        void lookupXref(DalyTranRecord item) {
            String cardNumber = item.dalytranCardNum();
            CardXrefRepository.ReadResult result =
                    job.cardXrefRepository.readByCardNumber(cardNumber);
            workingStorage.moveXreffileStatus(result.status());
            if (result.isNotFound()) {
                workingStorage.moveToValidationTrailer(REASON_INVALID_CARD_NUMBER,
                        DalyRejectWriter.DESC_INVALID_CARD_NUMBER);
                return;
            }
            result.record().ifPresent(record -> cardXrefRecord = record);
        }

        void lookupAcct(DalyTranRecord item) {
            long accountId = cardXrefRecord.xrefAcctId();
            AccountRepository.ReadResult result = acctfile.readByKey(accountId);
            workingStorage.moveAcctfileStatus(result.status());
            if (result.isNotFound()) {
                workingStorage.moveToValidationTrailer(REASON_ACCOUNT_RECORD_NOT_FOUND,
                        DalyRejectWriter.DESC_ACCOUNT_RECORD_NOT_FOUND);
                return;
            }
            if (result.isFound()) {
                accountRecord = result.account().orElseThrow(() -> new IllegalStateException(
                        "A read of " + ACCTFILE_DD_NAME + " reported file status " + FileStatus.OK
                                + " with no decoded record, so READ ... INTO ACCOUNT-RECORD had nothing "
                                + "to transfer."));
            } else {
                return;
            }

            BigDecimal tempBal = workingStorage.computeTempBal(accountRecord.getAcctCurrCycCredit(),
                    accountRecord.getAcctCurrCycDebit(), item.dalytranAmt());

            if (accountRecord.getAcctCreditLimit().compareTo(tempBal) < 0) {
                workingStorage.moveToValidationTrailer(REASON_OVERLIMIT_TRANSACTION,
                        DalyRejectWriter.DESC_OVERLIMIT_TRANSACTION);
            }

            if (accountRecord.getAcctExpiraionDate().compareTo(item.dalytranOrigDt()) < 0) {
                workingStorage.moveToValidationTrailer(REASON_TRANSACTION_AFTER_EXPIRATION,
                        DalyRejectWriter.DESC_TRANSACTION_AFTER_EXPIRATION);
            }
        }

        PostingResult postTransaction(DalyTranRecord item) {
            tranRecord.moveTranId(item.dalytranId());
            tranRecord.moveTranTypeCd(item.dalytranTypeCd());
            tranRecord.moveTranCatCd(item.dalytranCatCdImage());
            tranRecord.moveTranSource(item.dalytranSource());
            tranRecord.moveTranDesc(item.dalytranDesc());
            tranRecord.writeTranAmtImage(item.dalytranAmtImage());
            tranRecord.moveTranMerchantId(item.dalytranMerchantIdImage());
            tranRecord.moveTranMerchantName(item.dalytranMerchantName());
            tranRecord.moveTranMerchantCity(item.dalytranMerchantCity());
            tranRecord.moveTranMerchantZip(item.dalytranMerchantZip());
            tranRecord.moveTranCardNum(item.dalytranCardNum());
            tranRecord.moveTranOrigTs(item.dalytranOrigTs());

            String procTimestamp = job.db2FormatTimestamp();
            tranRecord.moveTranProcTs(procTimestamp);

            boolean created = updateTcatbal(item);
            boolean rewriteInvalidKey = updateAccountRec(item);
            writeTransactionFile();
            return new PostingResult(created, rewriteInvalidKey, procTimestamp);
        }

        void writeRejectRec(DalyTranRecord item) {
            dalyrejs.moveToRejectTranData(item);
            dalyrejs.moveToValidationTrailer(workingStorage.validationFailReason(),
                    workingStorage.validationFailReasonDesc());

            workingStorage.moveToApplResult(APPL_RESULT_ASSUMED_FAILURE);
            String status = statusOf(job.unitOfWork.persistVerb(
                    WRITE_DALYREJS_VERB, dalyrejs::writeRejectRec));
            workingStorage.moveDalyrejsStatus(status);
            applResultFromOkStatus(status);
            if (!workingStorage.applAok()) {
                throw reportAndAbend(ERROR_WRITING_DALYREJS, status);
            }
        }

        boolean updateTcatbal(DalyTranRecord item) {
            TranCatBalRecord.TranCatKey key = new TranCatBalRecord.TranCatKey(
                    cardXrefRecord.xrefAcctId(), item.dalytranTypeCd(), item.dalytranCatCd());

            workingStorage.moveDoNotCreateTrancatRec();
            TranCatBalRepository.ReadResult result = tcatbalf.readByKey(key);
            workingStorage.moveTcatbalfStatus(result.status());
            if (result.isNotFound()) {
                sysout.write(TCATBAL_NOT_FOUND_PREFIX + key.image(job.datasetCharset)
                        + TCATBAL_NOT_FOUND_SUFFIX);
                workingStorage.moveCreateTrancatRec();
            } else {
                result.record().ifPresent(found -> tranCatBalRecord = found);
            }

            if (FileStatus.isOkOrNotFound(result.status())) {
                workingStorage.moveToApplResult(APPL_RESULT_AOK);
            } else {
                workingStorage.moveToApplResult(APPL_RESULT_FATAL);
            }
            if (!workingStorage.applAok()) {
                throw reportAndAbend(ERROR_READING_TCATBALF, result.status());
            }

            if (workingStorage.createTrancatRecIsYes()) {
                createTcatbalRec(item);
                return true;
            }
            updateTcatbalRec(item);
            return false;
        }

        void createTcatbalRec(DalyTranRecord item) {
            tranCatBalRecord.initialize()
                    .trancatAcctId(cardXrefRecord.xrefAcctId())
                    .trancatTypeCd(item.dalytranTypeCd())
                    .trancatCd(item.dalytranCatCd())
                    .addToTranCatBal(item.dalytranAmt());

            String status = job.unitOfWork.persistVerb(
                    WRITE_TCATBALF_VERB, () -> tcatbalf.write(tranCatBalRecord)).status();
            workingStorage.moveTcatbalfStatus(status);
            applResultFromOkStatus(status);
            if (!workingStorage.applAok()) {
                throw reportAndAbend(ERROR_WRITING_TCATBALF, status);
            }
        }

        void updateTcatbalRec(DalyTranRecord item) {
            tranCatBalRecord.addToTranCatBal(item.dalytranAmt());

            String status = job.unitOfWork.persistVerb(
                    REWRITE_TCATBALF_VERB, () -> tcatbalf.rewrite(tranCatBalRecord)).status();
            workingStorage.moveTcatbalfStatus(status);
            applResultFromOkStatus(status);
            if (!workingStorage.applAok()) {
                throw reportAndAbend(ERROR_REWRITING_TCATBALF, status);
            }
        }

        boolean updateAccountRec(DalyTranRecord item) {
            BigDecimal amount = item.dalytranAmt();

            accountRecord.setAcctCurrBal(CobolDecimal.add(accountRecord.getAcctCurrBal(), amount,
                    CobolDecimal.MONETARY_SCALE));

            if (amount.signum() >= 0) {
                accountRecord.setAcctCurrCycCredit(CobolDecimal.add(
                        accountRecord.getAcctCurrCycCredit(), amount, CobolDecimal.MONETARY_SCALE));
            } else {
                accountRecord.setAcctCurrCycDebit(CobolDecimal.add(
                        accountRecord.getAcctCurrCycDebit(), amount, CobolDecimal.MONETARY_SCALE));
            }

            // ACCTFILE is DISP=SHR over a RECOVERY(NONE) cluster and 2900 runs next unconditionally, so a
            // failed WRITE there abends at :707 with this rewrite already applied - exactly as the COBOL
            // leaves it.
            AccountRepository.WriteResult result = job.unitOfWork.persistVerb(
                    REWRITE_ACCTFILE_VERB, () -> acctfile.rewrite(accountRecord));
            workingStorage.moveAcctfileStatus(result.status());
            if (result.isNotFound()) {
                workingStorage.moveToValidationTrailer(REASON_ACCOUNT_NOT_FOUND_ON_REWRITE,
                        DalyRejectWriter.DESC_ACCOUNT_RECORD_NOT_FOUND);
                return true;
            }
            return false;
        }

        void writeTransactionFile() {
            workingStorage.moveToApplResult(APPL_RESULT_ASSUMED_FAILURE);
            String status = job.unitOfWork.persistVerb(
                    WRITE_TRANFILE_VERB, () -> job.transactionRepository.write(tranRecord)).status();
            workingStorage.moveTranfileStatus(status);
            applResultFromOkStatus(status);
            if (!workingStorage.applAok()) {
                throw reportAndAbend(ERROR_WRITING_TRANFILE, status);
            }
        }

        void closeFiles() {
            requireOpen();
            dalytranClose();
            tranfileClose();
            xreffileClose();
            dalyrejsClose();
            acctfileClose();
            tcatbalfClose();
            closedNormally = true;
        }

        void dalytranClose() {
            workingStorage.moveToApplResult(APPL_RESULT_ASSUMED_FAILURE);
            String status = dalytranFile.closeFile();
            workingStorage.moveDalytranStatus(status);
            applResultFromOkStatus(status);
            if (!workingStorage.applAok()) {
                throw reportAndAbend(ERROR_CLOSING_DALYTRAN, status);
            }
        }

        void tranfileClose() {
            workingStorage.moveToApplResult(APPL_RESULT_ASSUMED_FAILURE);
            String status = tranfile.closeLoadMode();
            workingStorage.moveTranfileStatus(status);
            applResultFromOkStatus(status);
            if (!workingStorage.applAok()) {
                throw reportAndAbend(ERROR_CLOSING_TRANFILE, status);
            }
        }

        void xreffileClose() {
            workingStorage.moveToApplResult(APPL_RESULT_ASSUMED_FAILURE);
            String status = xreffile.closeBrowse();
            workingStorage.moveXreffileStatus(status);
            applResultFromOkStatus(status);
            if (!workingStorage.applAok()) {
                throw reportAndAbend(ERROR_CLOSING_XREFFILE, status);
            }
        }

        void dalyrejsClose() {
            workingStorage.moveToApplResult(APPL_RESULT_ASSUMED_FAILURE);
            String status = statusOf(dalyrejs.closeOutput());
            workingStorage.moveDalyrejsStatus(status);
            applResultFromOkStatus(status);
            if (!workingStorage.applAok()) {
                // MOVE XREFFILE-STATUS TO IO-STATUS - the wrong operand, preserved. L649.
                throw reportAndAbend(ERROR_CLOSING_DALYREJS, workingStorage.xreffileStatus());
            }
        }

        void acctfileClose() {
            workingStorage.moveToApplResult(APPL_RESULT_ASSUMED_FAILURE);
            String status = acctfile.closeFile();
            workingStorage.moveAcctfileStatus(status);
            applResultFromOkStatus(status);
            if (!workingStorage.applAok()) {
                throw reportAndAbend(ERROR_CLOSING_ACCTFILE, status);
            }
        }

        void tcatbalfClose() {
            workingStorage.moveToApplResult(APPL_RESULT_ASSUMED_FAILURE);
            String status = tcatbalf.closeFile();
            workingStorage.moveTcatbalfStatus(status);
            applResultFromOkStatus(status);
            if (!workingStorage.applAok()) {
                throw reportAndAbend(ERROR_CLOSING_TCATBALF, status);
            }
        }

        RunOutcome writeTrailer() {
            long processed = workingStorage.transactionCount();
            long rejected = workingStorage.rejectCount();

            sysout.write(TRANSACTIONS_PROCESSED_PREFIX
                    + job.codec.movePic9(processed, COUNTER_WIDTH));
            sysout.write(TRANSACTIONS_REJECTED_PREFIX
                    + job.codec.movePic9(rejected, COUNTER_WIDTH));

            int returnCode = rejected > 0
                    ? RETURN_CODE_REJECTS_PRESENT
                    : RETURN_CODE_CLEAN;

            sysout.write(END_OF_EXECUTION);
            return new RunOutcome(processed, rejected, returnCode);
        }

        void release() {
            if (dalyrejs != null) {
                DalyRejectWriter.RejectsFile releasing = dalyrejs;
                dalyrejs = null;
                releasing.closeOutput();
                if (!closedNormally) {
                    try {
                        FileStatus.Outcome disposition = job.unitOfWork.persistDisposition(
                                DALYREJS_ABNORMAL_DISPOSITION, releasing::discardGeneration);
                        if (disposition != FileStatus.Outcome.OK) {
                            LOG.error("The " + DALYREJS_DD_NAME + " generation of this abended run could "
                                    + "not be discarded; it reported FILE STATUS outcome "
                                    + disposition.name() + ". app/jcl/POSTTRAN.jcl:34 declares "
                                    + "DISP=(NEW,CATLG,DELETE), so rejects from a run that did not "
                                    + "complete may remain where the mainframe would leave none; they "
                                    + "must not be treated as this step's reject set");
                        }
                    } catch (RuntimeException dispositionFailure) {
                        // The exception itself is never handed to the logger: a driver composes its message
                        // around the values it refused, so logging the throwable would emit that text and
                        // its whole cause chain verbatim (CWE-532) in a form a control character can split
                        // into a forged entry (CWE-117).
                        LOG.error("The " + DALYREJS_DD_NAME + " abnormal disposition of "
                                + "app/jcl/POSTTRAN.jcl:34 could not be applied at all - "
                                + BackendDiagnostic.of(dispositionFailure).describe()
                                + "; the rejects of this abended run may remain catalogued. The abend "
                                + "itself is propagated unchanged, because it is what the caller needs "
                                + "to see");
                    }
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

        boolean closedNormally() {
            return closedNormally;
        }

        private void applResultFromOkStatus(String status) {
            if (FileStatus.isOk(status)) {
                workingStorage.moveToApplResult(APPL_RESULT_AOK);
            } else {
                workingStorage.moveToApplResult(APPL_RESULT_FATAL);
            }
        }

        private AbendException reportAndAbend(String text, String status) {
            sysout.write(text);
            sysout.write(FileStatus.toDisplayLine(status));
            sysout.write(AbendException.ABEND_DISPLAY_TEXT);
            return AbendException.standard(PROGRAM_ID, workingStorage.applResult(),
                    text + " (" + FileStatus.toStatusImage(status) + ")");
        }

        private static String statusOf(FileStatus.Outcome outcome) {
            Objects.requireNonNull(outcome, "A reject-file operation reports an outcome; there is no "
                    + "COBOL FILE STATUS meaning 'no answer', so a null is a defect in the sink");
            return outcome.batchStatus().orElse(PERMANENT_ERROR_STATUS);
        }

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
     * @param tranCatBalCreated {@code true} when {@code 2700-A-CREATE-TCATBAL-REC} ran rather than
     *     {@code 2700-B}
     * @param accountRewriteInvalidKey {@code true} when the {@code REWRITE} at {@code :554} reported the
     *     {@code INVALID KEY} condition and reason 109 was stored - which changes nothing, by design
     * @param procTimestamp the 26-character {@code TRAN-PROC-TS} the record was stamped with
     */
    record PostingResult(boolean tranCatBalCreated, boolean accountRewriteInvalidKey,
                         String procTimestamp) {
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

    /**
     * The step's reader, processor, writer and step listener, all backed by one {@link PostingRun}.
     */
    public static final class ChunkDelegate
            implements ItemStreamReader<DalyTranRecord>, ItemProcessor<DalyTranRecord, RecordOutcome>,
            ItemWriter<RecordOutcome>, StepExecutionListener {
        private final TransactionValidationJob job;

        private PostingRun run;

        private RunOutcome outcome;

        private boolean epilogueAttempted;

        private long posted;

        private long rejected;

        ChunkDelegate(TransactionValidationJob job) {
            this.job = Objects.requireNonNull(job, "A job is required to build its chunk delegate");
        }

        /**
         * Nothing to do before the step: this job has no parameters to receive ({@link #JCL_REFERENCE}
         * declares no {@code PARM}).
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
         *     {@code CBTRN02C} has no restart semantics
         * @throws IllegalStateException if a run is already open on this delegate
         * @throws AbendException if any open reports a status other than {@code '00'}
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

            opening.sysout().write(START_OF_EXECUTION);

            opening.openFiles();
        }

        /**
         * {@code app/cbl/CBTRN02C.cbl:204} - {@code PERFORM 1000-DALYTRAN-GET-NEXT}, and, when that read
         * ends the loop, the epilogue that follows the loop at {@code :221-232}.
         *
         * <p>The epilogue runs exactly once, because {@code END-OF-FILE = 'Y'} is set once and the chunk
         * loop stops on the first {@code null}.
         *
         * @return the next daily transaction, or {@code null} at end of file
         * @throws IllegalStateException if no run is open
         * @throws AbendException if the read reports a status that is neither {@code '00'} nor
         *     {@code '10'}, or if the epilogue's closes report one
         */
        @Override
        public DalyTranRecord read() {
            PostingRun reading = requireRun();
            DalyTranRecord item = reading.dalytranGetNext();
            if (item == null) {
                finishNormally(reading);
            }
            return item;
        }

        private void finishNormally(PostingRun finishing) {
            if (epilogueAttempted) {
                return;
            }
            epilogueAttempted = true;

            finishing.closeFiles();

            outcome = finishing.writeTrailer();
        }

        /**
         * {@code app/cbl/CBTRN02C.cbl:206-216} - the record body: count, reset, validate, then post or
         * reject.
         *
         * @param item the record the reader returned
         * @return what the record did; never {@code null}, so no item is ever filtered out
         * @throws NullPointerException if {@code item} is {@code null}
         * @throws IllegalStateException if no run is open
         * @throws AbendException if any file operation this record triggers reports a fatal status
         */
        @Override
        public RecordOutcome process(DalyTranRecord item) {
            return requireRun().processRecord(item);
        }

        /**
         * The writer stage: bookkeeping, and no dataset I/O.
         *
         * @param chunk the outcomes of this chunk's records - exactly one, since the commit interval is
         *     {@value #CHUNK_SIZE}
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
         * <p>The epilogue of {@code app/cbl/CBTRN02C.cbl:221-232} - the six closes, the two count lines,
         * the {@code RETURN-CODE} decision and the closing banner - belongs to {@link #read()}, which is
         * where the loop ends; see that method for the two mechanical reasons it cannot live here.
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
                    finishNormally(finishing);
                }
            } finally {
                finishing.release();
            }
        }

        /**
         * Leaves the exit status to the step and the job listeners.
         *
         * @param stepExecution the execution finishing; must not be {@code null}
         * @return the exit status carrying return code {@link #RETURN_CODE_REJECTS_PRESENT} when the run
         *     rejected anything, otherwise {@code null}
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

        Optional<PostingRun> currentRun() {
            return Optional.ofNullable(run);
        }

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
     */
    static final class StepScopedChunkDelegate
            implements ItemStreamReader<DalyTranRecord>, ItemProcessor<DalyTranRecord, RecordOutcome>,
            ItemWriter<RecordOutcome>, StepExecutionListener {
        private final TransactionValidationJob job;

        private final ThreadLocal<Scope> executionScope = new ThreadLocal<>();

        StepScopedChunkDelegate(TransactionValidationJob job) {
            this.job = Objects.requireNonNull(job, "A job is required to scope its chunk delegate");
        }

        /**
         * One step execution and the delegate that is its address space.
         *
         * @param stepExecution the execution the delegate belongs to
         * @param delegate that execution's delegate
         */
        private record Scope(StepExecution stepExecution, ChunkDelegate delegate) {
        }

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
         * {@link #RETURN_CODE_REJECTS_PRESENT} when the run rejected anything.
         *
         * @param stepExecution the execution finishing; must not be {@code null}
         * @return the delegate's exit status, or {@code null} when no delegate is scoped or the run was
         *     clean
         */
        @Override
        public ExitStatus afterStep(StepExecution stepExecution) {
            Objects.requireNonNull(stepExecution, "A step execution is required to finish a delegate");
            Scope scope = executionScope.get();
            return scope == null ? null : scope.delegate().afterStep(stepExecution);
        }

        Optional<ChunkDelegate> scopedDelegate() {
            return Optional.ofNullable(executionScope.get()).map(Scope::delegate);
        }

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

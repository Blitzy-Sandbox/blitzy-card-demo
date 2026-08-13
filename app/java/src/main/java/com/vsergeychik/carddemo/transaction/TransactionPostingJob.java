package com.vsergeychik.carddemo.transaction;

import com.vsergeychik.carddemo.account.AccountRepository;
import com.vsergeychik.carddemo.account.model.AccountRecord;
import com.vsergeychik.carddemo.card.CardRepository;
import com.vsergeychik.carddemo.card.CardRepository.BrowseDirection;
import com.vsergeychik.carddemo.card.CardXrefRepository;
import com.vsergeychik.carddemo.card.model.CardRecord;
import com.vsergeychik.carddemo.card.model.CardXrefRecord;
import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.config.BatchConfig;
import com.vsergeychik.carddemo.config.BatchConfig.JobContract;
import com.vsergeychik.carddemo.config.BatchConfig.StepContract;
import com.vsergeychik.carddemo.config.BatchConfig.StopSignal;
import com.vsergeychik.carddemo.customer.CustomerRepository;
import com.vsergeychik.carddemo.customer.model.CustomerRecord;
import com.vsergeychik.carddemo.transaction.model.DalyTranRecord;
import com.vsergeychik.carddemo.transaction.model.TranRecord;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The Java translation of {@code app/cbl/CBTRN01C.cbl} (491 lines), assembled as a single-step Spring Batch
 * job.
 */
@Configuration(TransactionPostingJob.CONFIGURATION_BEAN_NAME)
public class TransactionPostingJob {
    public static final String CONFIGURATION_BEAN_NAME = "transactionPostingJobConfiguration";

    /**
     * The COBOL program this class translates.
     */
    public static final String PROGRAM_ID = "CBTRN01C";

    public static final String JOB_KEY = "transaction-posting-job";

    public static final String JOB_NAME = "transactionPostingJob";

    public static final String STEP_NAME = "STEP01";

    /**
     * The whole step sequence this job runs: one step, {@link #STEP_NAME}, running {@link #PROGRAM_ID},
     * ungated.
     */
    public static final List<StepContract> REQUIRED_STEPS =
            List.of(new StepContract(STEP_NAME, PROGRAM_ID, false));

    /**
     * {@code SELECT DALYTRAN-FILE ASSIGN TO DALYTRAN} - {@code app/cbl/CBTRN01C.cbl:29}.
     */
    public static final String DALYTRAN_DD_NAME = "DALYTRAN";

    /**
     * {@code SELECT CUSTOMER-FILE ASSIGN TO CUSTFILE} - {@code app/cbl/CBTRN01C.cbl:34}.
     */
    public static final String CUSTFILE_DD_NAME = "CUSTFILE";

    /**
     * {@code SELECT XREF-FILE ASSIGN TO XREFFILE} - {@code app/cbl/CBTRN01C.cbl:40}.
     */
    public static final String XREFFILE_DD_NAME = "XREFFILE";

    /**
     * {@code SELECT CARD-FILE ASSIGN TO CARDFILE} - {@code app/cbl/CBTRN01C.cbl:46}.
     */
    public static final String CARDFILE_DD_NAME = "CARDFILE";

    /**
     * {@code SELECT ACCOUNT-FILE ASSIGN TO ACCTFILE} - {@code app/cbl/CBTRN01C.cbl:52}.
     */
    public static final String ACCTFILE_DD_NAME = "ACCTFILE";

    /**
     * {@code SELECT TRANSACT-FILE ASSIGN TO TRANFILE} - {@code app/cbl/CBTRN01C.cbl:58}.
     */
    public static final String TRANFILE_DD_NAME = "TRANFILE";

    /**
     * {@code app/cbl/CBTRN01C.cbl:156}.
     */
    public static final String START_BANNER = "START OF EXECUTION OF PROGRAM CBTRN01C";

    /**
     * {@code app/cbl/CBTRN01C.cbl:195}.
     */
    public static final String END_BANNER = "END OF EXECUTION OF PROGRAM CBTRN01C";

    /**
     * {@code app/cbl/CBTRN01C.cbl:263}.
     */
    public static final String ERROR_OPENING_DALYTRAN = "ERROR OPENING DAILY TRANSACTION FILE";

    /**
     * {@code app/cbl/CBTRN01C.cbl:282}.
     */
    public static final String ERROR_OPENING_CUSTFILE = "ERROR OPENING CUSTOMER FILE";

    /**
     * {@code app/cbl/CBTRN01C.cbl:300}.
     */
    public static final String ERROR_OPENING_XREFFILE = "ERROR OPENING CROSS REF FILE";

    /**
     * {@code app/cbl/CBTRN01C.cbl:318}.
     */
    public static final String ERROR_OPENING_CARDFILE = "ERROR OPENING CARD FILE";

    /**
     * {@code app/cbl/CBTRN01C.cbl:336}.
     */
    public static final String ERROR_OPENING_ACCTFILE = "ERROR OPENING ACCOUNT FILE";

    /**
     * {@code app/cbl/CBTRN01C.cbl:354}.
     */
    public static final String ERROR_OPENING_TRANFILE = "ERROR OPENING TRANSACTION FILE";

    /**
     * {@code app/cbl/CBTRN01C.cbl:219}.
     */
    public static final String ERROR_READING_DALYTRAN = "ERROR READING DAILY TRANSACTION FILE";

    /**
     * {@code app/cbl/CBTRN01C.cbl:372} and {@code :390} - the same literal at both sites.
     */
    public static final String ERROR_CLOSING_CUSTFILE = "ERROR CLOSING CUSTOMER FILE";

    /**
     * {@code app/cbl/CBTRN01C.cbl:408}.
     */
    public static final String ERROR_CLOSING_XREFFILE = "ERROR CLOSING CROSS REF FILE";

    /**
     * {@code app/cbl/CBTRN01C.cbl:426}.
     */
    public static final String ERROR_CLOSING_CARDFILE = "ERROR CLOSING CARD FILE";

    /**
     * {@code app/cbl/CBTRN01C.cbl:444}.
     */
    public static final String ERROR_CLOSING_ACCTFILE = "ERROR CLOSING ACCOUNT FILE";

    /**
     * {@code app/cbl/CBTRN01C.cbl:462}.
     */
    public static final String ERROR_CLOSING_TRANFILE = "ERROR CLOSING TRANSACTION FILE";

    /**
     * {@code app/cbl/CBTRN01C.cbl:232}.
     */
    public static final String INVALID_CARD_NUMBER_FOR_XREF = "INVALID CARD NUMBER FOR XREF";

    /**
     * {@code app/cbl/CBTRN01C.cbl:235}.
     */
    public static final String SUCCESSFUL_READ_OF_XREF = "SUCCESSFUL READ OF XREF";

    /**
     * {@code app/cbl/CBTRN01C.cbl:236} - the prefix of the card-number line, with its trailing space.
     */
    public static final String XREF_CARD_NUMBER_PREFIX = "CARD NUMBER: ";

    /**
     * {@code app/cbl/CBTRN01C.cbl:237} - the prefix of the account-id line.
     */
    public static final String XREF_ACCOUNT_ID_PREFIX = "ACCOUNT ID : ";

    /**
     * {@code app/cbl/CBTRN01C.cbl:238}.
     */
    public static final String XREF_CUSTOMER_ID_PREFIX = "CUSTOMER ID: ";

    /**
     * {@code app/cbl/CBTRN01C.cbl:246}.
     */
    public static final String INVALID_ACCOUNT_NUMBER_FOUND = "INVALID ACCOUNT NUMBER FOUND";

    /**
     * {@code app/cbl/CBTRN01C.cbl:249}.
     */
    public static final String SUCCESSFUL_READ_OF_ACCOUNT_FILE = "SUCCESSFUL READ OF ACCOUNT FILE";

    /**
     * The first operand of {@code DISPLAY 'ACCOUNT ' ACCT-ID ' NOT FOUND'} - {@code :178}.
     */
    public static final String ACCOUNT_NOT_FOUND_PREFIX = "ACCOUNT ";

    /**
     * The third operand of {@code DISPLAY 'ACCOUNT ' ACCT-ID ' NOT FOUND'} - {@code :178}.
     */
    public static final String ACCOUNT_NOT_FOUND_SUFFIX = " NOT FOUND";

    /**
     * The first operand of the card-not-verified message - {@code app/cbl/CBTRN01C.cbl:181}.
     */
    public static final String CARD_NOT_VERIFIED_PREFIX = "CARD NUMBER ";

    /**
     * The third operand of the card-not-verified message - {@code app/cbl/CBTRN01C.cbl:182}.
     */
    public static final String CARD_NOT_VERIFIED_SUFFIX =
            " COULD NOT BE VERIFIED. SKIPPING TRANSACTION ID-";

    /**
     * The value the open and close paragraphs seed {@code APPL-RESULT} with before attempting the verb -
     * {@code MOVE 8 TO APPL-RESULT} at {@code :253} and its five siblings, and
     * {@code ADD 8 TO ZERO GIVING APPL-RESULT} at {@code :362} and its five.
     */
    public static final int APPL_RESULT_ASSUMED_FAILURE = AbendException.RETURN_CODE_ASSUMED_FAILURE;

    /**
     * The value every failing arm moves into {@code APPL-RESULT}, and therefore the {@code RETURN-CODE}
     * every abend of this program carries: {@code MOVE 12 TO APPL-RESULT}.
     */
    public static final int APPL_RESULT_FATAL = AbendException.RETURN_CODE_IO_ERROR;

    /**
     * {@code 01 END-OF-DAILY-TRANS-FILE PIC X(01) VALUE 'N'} - {@code app/cbl/CBTRN01C.cbl:146}.
     */
    public static final String NOT_AT_END_OF_FILE = "N";

    public static final String AT_END_OF_FILE = "Y";

    /**
     * The value {@code :170} and {@code :174} move into {@code WS-XREF-READ-STATUS} and
     * {@code WS-ACCT-READ-STATUS} before each lookup: a clean slate, tested as {@code = 0} at {@code :173}
     * and as {@code NOT = 0} at {@code :177}.
     */
    public static final int READ_STATUS_OK = 0;

    /**
     * The value the two {@code INVALID KEY} arms move into their read status -
     * {@code MOVE 4 TO WS-XREF-READ-STATUS} at {@code :233} and {@code MOVE 4 TO WS-ACCT-READ-STATUS} at
     * {@code :247}.
     */
    public static final int READ_STATUS_INVALID_KEY = 4;

    /**
     * The {@code XREF-ACCT-ID} a never-populated {@code CARD-XREF-RECORD} area yields: zero.
     */
    public static final long UNPOPULATED_ACCOUNT_ID = 0L;

    private static final char PERMANENT_ERROR_FEEDBACK_CODE = 0;

    /**
     * The two-character {@code FILE STATUS} this class reports for a dataset that could not be reached.
     */
    public static final String PERMANENT_ERROR_STATUS = "9" + PERMANENT_ERROR_FEEDBACK_CODE;

    /**
     * The lowest possible {@code CARD-NUM} key: sixteen spaces.
     */
    public static final String LOWEST_CARD_NUMBER_KEY = " ".repeat(CardRecord.CARD_NUM_LENGTH);

    public static final int BANNER_LINES = 2;

    /**
     * How many {@code SYSOUT} lines one successful {@code 2000-LOOKUP-XREF} emits: {@code :235} through
     * {@code :238}.
     */
    public static final int XREF_SUCCESS_LINES = 4;

    private static final String SYSOUT_SUBJECT = "a SYSOUT display line of " + PROGRAM_ID;

    private static final char SYSOUT_LINE_TERMINATOR = '\n';

    private static final Log LOG = LogFactory.getLog(TransactionPostingJob.class);

    private final BatchConfig batchConfig;

    private final DalyTranRepository dalyTranRepository;

    private final CustomerRepository customerRepository;

    private final CardXrefRepository cardXrefRepository;

    private final CardRepository cardRepository;

    private final AccountRepository accountRepository;

    private final TransactionRepository transactionRepository;

    private final FixedWidthCodec codec;

    private final ObjectProvider<SysoutSink> sysoutSinkProvider;

    private final StepContract stepContract;

    /**
     * Wires the job and proves, at start-up, that configuration still describes {@link #PROGRAM_ID}.
     *
     * @param batchConfig the batch seam supplying builders, contracts and dataset bindings; never
     *     {@code null}
     * @param dalyTranRepository DD {@link #DALYTRAN_DD_NAME}, the sequential input; never {@code null}
     * @param customerRepository DD {@link #CUSTFILE_DD_NAME}, opened and closed only; never {@code null}
     * @param cardXrefRepository DD {@link #XREFFILE_DD_NAME}, the {@code CCXREF} base cluster; never
     *     {@code null}
     * @param cardRepository DD {@link #CARDFILE_DD_NAME}, opened and closed only; never {@code null}
     * @param accountRepository DD {@link #ACCTFILE_DD_NAME}, read by key; never {@code null}
     * @param transactionRepository DD {@link #TRANFILE_DD_NAME}, opened and closed only; never {@code null}
     * @param sysoutSinkProvider provider for a deployment-supplied {@code SYSOUT} destination; never
     *     {@code null}, though it may resolve to nothing, in which case {@link #defaultSysoutSink()} is used
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalStateException if the contract is absent, names another program, declares a parameter,
     *     gates the step, declares any other step sequence
     */
    public TransactionPostingJob(
            BatchConfig batchConfig,
            DalyTranRepository dalyTranRepository,
            CustomerRepository customerRepository,
            CardXrefRepository cardXrefRepository,
            CardRepository cardRepository,
            AccountRepository accountRepository,
            TransactionRepository transactionRepository,
            ObjectProvider<SysoutSink> sysoutSinkProvider) {
        this.batchConfig = Objects.requireNonNull(batchConfig, "The batch configuration seam is required: "
                + "it supplies the job repository, the transaction manager, the abend listeners, the job "
                + "contracts and the dataset bindings, so no job class assembles Spring Batch plumbing or "
                + "resolves a dataset name of its own");
        this.dalyTranRepository = Objects.requireNonNull(dalyTranRepository, "The daily-transaction "
                + "repository is required: app/cbl/CBTRN01C.cbl:29 assigns DD " + DALYTRAN_DD_NAME
                + ", and it is the one file this program reads");
        this.customerRepository = Objects.requireNonNull(customerRepository, "The customer repository is "
                + "required even though this program never reads the customer file: 0100-CUSTFILE-OPEN and "
                + "9100-CUSTFILE-CLOSE are two of its twelve file verbs, both can fail and abend, and both "
                + "are part of its observable behaviour");
        this.cardXrefRepository = Objects.requireNonNull(cardXrefRepository, "The card cross-reference "
                + "repository is required: 2000-LOOKUP-XREF reads the CCXREF base cluster by the "
                + "sixteen-character card number");
        this.cardRepository = Objects.requireNonNull(cardRepository, "The card repository is required even "
                + "though this program never reads the card file: 0300-CARDFILE-OPEN and "
                + "9300-CARDFILE-CLOSE are two of its twelve file verbs");
        this.accountRepository = Objects.requireNonNull(accountRepository, "The account repository is "
                + "required: 3000-READ-ACCOUNT reads it by the cross-referenced account id. It is read "
                + "only - the program issues no WRITE and no REWRITE anywhere");
        this.transactionRepository = Objects.requireNonNull(transactionRepository, "The transaction "
                + "repository is required even though this program never reads or writes the transaction "
                + "file: 0500-TRANFILE-OPEN and 9500-TRANFILE-CLOSE are its last two file verbs, and the "
                + "posting this job's name promises is exactly the write it never issues");
        this.sysoutSinkProvider = Objects.requireNonNull(sysoutSinkProvider, "A SysoutSink provider is "
                + "required: DISPLAY output is emitted through an injected sink so it can be captured and "
                + "compared, never written straight to a stream from the program body");

        this.codec = new FixedWidthCodec(dalyTranRepository.datasetCharset());

        JobContract contract = batchConfig.contract(JOB_KEY);
        requireProgram(contract.program(), "carddemo.jobs." + JOB_KEY + ".program");
        requireNoJobParameters(contract);

        StepContract step = contract.step(STEP_NAME);
        requireProgram(step.program(),
                "carddemo.jobs." + JOB_KEY + ".steps[" + STEP_NAME + "].program");
        requireUngatedStep(step);
        batchConfig.requireSteps(JOB_KEY, REQUIRED_STEPS,
                "app/cbl/CBTRN01C.cbl:155 - the program's single MAIN-PARA; no JCL declares an EXEC card "
                        + "for it, so the step name is this module's own and configuration owns it");
        this.stepContract = step;

        requireDatasetGeometry(DALYTRAN_DD_NAME, DalyTranRecord.RECORD_LENGTH, "app/cpy/CVTRA06Y.cpy");
        requireDatasetGeometry(CUSTFILE_DD_NAME, CustomerRecord.RECORD_LENGTH, "app/cpy/CVCUS01Y.cpy");
        requireDatasetGeometry(XREFFILE_DD_NAME, CardXrefRecord.RECORD_LENGTH, "app/cpy/CVACT03Y.cpy");
        requireDatasetGeometry(CARDFILE_DD_NAME, CardRecord.RECORD_LENGTH, "app/cpy/CVACT02Y.cpy");
        requireDatasetGeometry(ACCTFILE_DD_NAME, AccountRecord.RECORD_LENGTH, "app/cpy/CVACT01Y.cpy");
        requireDatasetGeometry(TRANFILE_DD_NAME, TranRecord.RECORD_LENGTH, "app/cpy/CVTRA05Y.cpy");

        batchConfig.requireSameDataset(JOB_KEY, DALYTRAN_DD_NAME, DalyTranRepository.DD_NAME);
        batchConfig.requireSameDataset(JOB_KEY, CUSTFILE_DD_NAME, CustomerRepository.BATCH_DD_NAME);
        batchConfig.requireSameDataset(JOB_KEY, ACCTFILE_DD_NAME, AccountRepository.BATCH_DD_NAME);
    }

    private static void requireProgram(String configured, String key) {
        if (!PROGRAM_ID.equals(configured)) {
            throw new IllegalStateException(key + " is '" + configured + "', but "
                    + TransactionPostingJob.class.getSimpleName() + " translates " + PROGRAM_ID
                    + " (app/cbl/CBTRN01C.cbl). Correct the configured program name; do not repoint this "
                    + "class.");
        }
    }

    private static void requireNoJobParameters(JobContract contract) {
        if (!contract.parameters().isEmpty()) {
            throw new IllegalStateException("carddemo.jobs." + JOB_KEY + ".parameters declares "
                    + contract.parameters().size() + " parameter(s), but " + PROGRAM_ID + " is invoked by "
                    + "no JCL anywhere - grep -rn \"" + PROGRAM_ID + "\" app/jcl/ app/proc/ app/csd/ "
                    + "returns nothing - so there is no PARM to translate and the program declares no "
                    + "LINKAGE SECTION. The empty list is the contract; declare no parameter here.");
        }
    }

    private static void requireUngatedStep(StepContract step) {
        if (step.requirePrecedingExitCodeZero()) {
            throw new IllegalStateException("carddemo.jobs." + JOB_KEY + ".steps[" + step.name()
                    + "].require-preceding-exit-code-zero is true, but this job declares one step and "
                    + PROGRAM_ID + " has no JCL, so there is no COND to reproduce and no preceding step to "
                    + "test. Gating the first step of a single-step job would stop it running at all.");
        }
    }

    /**
     * Requires a DD to bind a dataset that can be addressed and whose record width agrees with the copybook
     * the program's record area is declared from.
     *
     * @param ddName the DD name, which is also the {@code carddemo.datasets} key
     * @param copybookLength the record length the copybook declares
     * @param copybookPath the copybook that declares it, for the diagnostic
     * @throws IllegalStateException if the width disagrees or the dataset name is absent or blank
     */
    private void requireDatasetGeometry(String ddName, int copybookLength, String copybookPath) {
        var binding = batchConfig.datasetBinding(JOB_KEY, ddName);
        if (binding.recordLength() != copybookLength) {
            throw new IllegalStateException("carddemo.datasets." + ddName + ".record-length is "
                    + binding.recordLength() + ", but " + copybookPath + " declares " + copybookLength
                    + " bytes and " + PROGRAM_ID + " declares its record area from that copybook. This "
                    + "module addresses every record by absolute offset, so a differently-sized record "
                    + "would misplace every field after the first. Correct carddemo.datasets." + ddName
                    + ".record-length to " + copybookLength + ".");
        }
        String dsname = binding.dsname();
        if (dsname == null || dsname.isBlank()) {
            throw new IllegalStateException("carddemo.datasets." + ddName + ".dsname is "
                    + (dsname == null ? "not declared" : "blank") + ", so DD " + ddName + " names no "
                    + "dataset for " + PROGRAM_ID + " to open. Supply that dataset, or the environment "
                    + "variable the binding defers to.");
        }
    }

    /**
     * The job, published as a bean under {@link #JOB_NAME}: one step, no parameters, no gating, no trigger.
     *
     * @return the {@link #JOB_NAME} job; never {@code null}
     */
    @Bean(JOB_NAME)
    public Job transactionPostingJob() {
        return batchConfig.job(JOB_NAME)
                .start(transactionPostingStep())
                .build();
    }

    /**
     * The single step, named {@link #STEP_NAME} as this job's contract declares.
     *
     * @return a fresh step; never {@code null}
     */
    public Step transactionPostingStep() {
        return batchConfig.taskletStep(stepContract.name(), transactionPostingTasklet()).build();
    }

    /**
     * The step body: one invocation, one complete execution of the procedure division.
     *
     * <p>An {@link AbendException} must reach the framework so {@code BatchConfig}'s listeners can carry
     * its {@code RETURN-CODE} onto the step's exit status and the process exit code; swallowing it would
     * report a failed job as complete.
     *
     * @return a tasklet that runs the program exactly once per step execution; never {@code null}
     */
    public Tasklet transactionPostingTasklet() {
        return (contribution, chunkContext) -> {
            ExecutionSummary summary = execute(resolveSysoutSink(), StopSignal.of(chunkContext));
            for (int recorded = 0; recorded < summary.recordsRead(); recorded++) {
                contribution.incrementReadCount();
            }
            return RepeatStatus.FINISHED;
        };
    }

    /**
     * The job parameters this job is launched with: none.
     *
     * @return empty job parameters; never {@code null}
     */
    public JobParameters jobParameters() {
        return batchConfig.contract(JOB_KEY).jobParameters();
    }

    /**
     * This job's validated step contract, as {@code carddemo.jobs} declares it.
     *
     * @return the contract for step {@link #STEP_NAME}; never {@code null}
     */
    public StepContract stepContract() {
        return stepContract;
    }

    /**
     * Runs {@link #PROGRAM_ID} once: open six files, read the daily transaction file to end of file
     * reporting each record, close six files.
     *
     * @param sysout where the {@code DISPLAY} lines go; never {@code null}
     * @return the return code the program left, and what the run read and looked up
     * @throws NullPointerException if {@code sysout} is {@code null}
     * @throws AbendException if any of the twelve file verbs, or the read, reports a status the program
     *     treats as fatal - carrying {@code RETURN-CODE} {@link #APPL_RESULT_FATAL}, {@code ABCODE} 999 and
     *     {@code TIMING} 0
     */
    public ExecutionSummary execute(SysoutSink sysout) {
        return execute(sysout, StopSignal.RUNNING);
    }

    /**
     * Runs {@link #PROGRAM_ID}, yielding to the given stop signal between records.
     *
     * @param sysout where the {@code DISPLAY} lines go; never {@code null}
     * @param stopSignal the between-record cancellation probe; {@link StopSignal#RUNNING} for a caller
     *     outside a step; never {@code null}
     * @return the return code the program left, and what the run read and looked up
     * @throws NullPointerException if {@code sysout} or {@code stopSignal} is {@code null}
     * @throws AbendException if any file verb or the read reports a fatal status
     * @throws BatchConfig.StopRequestedException if the step is asked to stop, which abandons the pass
     *     between records
     */
    public ExecutionSummary execute(SysoutSink sysout, StopSignal stopSignal) {
        Objects.requireNonNull(sysout, "A SYSOUT sink is required to run " + PROGRAM_ID + ": the program's "
                + "observable output is its DISPLAY lines, so there is nothing to run without somewhere to "
                + "put them");
        Objects.requireNonNull(stopSignal, "A stop signal is required; pass StopSignal.RUNNING outside a "
                + "step, which is what the single-argument overload does");
        return new Execution(sysout).run(stopSignal);
    }

    /**
     * One execution of {@link #PROGRAM_ID}: its {@code WORKING-STORAGE}, its file handles and its body.
     */
    private final class Execution {
        private final SysoutSink sysout;

        private String endOfDailyTransFile = NOT_AT_END_OF_FILE;

        private int applResult;

        private String ioStatus;

        private int xrefReadStatus;

        private int acctReadStatus;

        private DalyTranRecord dalytranRecord = new DalyTranRecord(codec.charset());

        private String xrefCardNum = " ".repeat(CardXrefRecord.XREF_CARD_NUM_LENGTH);

        private CardXrefRecord cardXrefRecord;

        private String acctId = "0".repeat(AccountRecord.KEY_LENGTH);

        private long acctIdKey;

        private String dalytranStatus;

        private String custfileStatus;

        private String xreffileStatus;

        private String cardfileStatus;

        private String acctfileStatus;

        private String tranfileStatus;

        private DalyTranRepository.DalytranFile dalytranFile;

        private CustomerRepository.CustomerFile custfile;

        private CardXrefRepository.BrowseCursor xreffile;

        private CardRepository.CardBrowse cardfile;

        private AccountRepository.AccountFile acctfile;

        private TransactionRepository.InputFile tranfile;

        private boolean dalytranCloseIssued;

        private boolean custfileCloseIssued;

        private boolean xreffileCloseIssued;

        private boolean cardfileCloseIssued;

        private boolean acctfileCloseIssued;

        private boolean tranfileCloseIssued;

        private int recordsRead;

        private int xrefLookups;

        private int accountReads;

        private RuntimeException datasetRefusal;

        private CardXrefRepository xrefAccess;

        private Execution(SysoutSink sysout) {
            this.sysout = sysout;
        }

        private ExecutionSummary run(StopSignal stopSignal) {
            sysout.display(START_BANNER);

            try {
                openDalytran();
                openCustfile();
                openXreffile();
                openCardfile();
                openAcctfile();
                openTranfile();

                while (!AT_END_OF_FILE.equals(endOfDailyTransFile)) {
                    stopSignal.checkStopRequested();

                    // :165 IF END-OF-DAILY-TRANS-FILE = 'N' Redundant - the PERFORM UNTIL condition has
                    // already established it and the flag holds only 'N' or 'Y' - so its false path is
                    // unreachable and a coverage report shows this as a half-taken branch.
                    if (NOT_AT_END_OF_FILE.equals(endOfDailyTransFile)) {
                        getNextDalytranRecord();

                        if (NOT_AT_END_OF_FILE.equals(endOfDailyTransFile)) {
                            sysout.display(dalytranRecord.displayImage());
                        }

                        xrefReadStatus = READ_STATUS_OK;
                        xrefCardNum = codec.movePicX(dalytranRecord.dalytranCardNum(),
                                CardXrefRecord.XREF_CARD_NUM_LENGTH);
                        lookupXref();

                        if (xrefReadStatus == READ_STATUS_OK) {
                            acctReadStatus = READ_STATUS_OK;
                            moveXrefAccountIdToAccountId();
                            readAccount();
                            if (acctReadStatus != READ_STATUS_OK) {
                                sysout.display(ACCOUNT_NOT_FOUND_PREFIX + acctId
                                        + ACCOUNT_NOT_FOUND_SUFFIX);
                            }
                        } else {
                            sysout.display(CARD_NOT_VERIFIED_PREFIX + dalytranRecord.dalytranCardNum()
                                    + CARD_NOT_VERIFIED_SUFFIX + dalytranRecord.dalytranId());
                        }
                    }
                }

                closeDalytran();
                closeCustfile();
                closeXreffile();
                closeCardfile();
                closeAcctfile();
                closeTranfile();

                sysout.display(END_BANNER);

                // :197 GOBACK. - RETURN-CODE is never moved anywhere in this program, so a run that returns
                // at all returns zero.
                return new ExecutionSummary(AbendException.RETURN_CODE_OK, recordsRead, xrefLookups,
                        accountReads);
            } finally {
                releaseUnclosedHandles();
            }
        }

        private void moveXrefAccountIdToAccountId() {
            long xrefAcctId = cardXrefRecord == null
                    ? UNPOPULATED_ACCOUNT_ID
                    : cardXrefRecord.xrefAcctId();
            acctIdKey = xrefAcctId;
            acctId = codec.movePic9(xrefAcctId, AccountRecord.KEY_LENGTH);
        }

        private void getNextDalytranRecord() {
            DalyTranRepository.ReadResult read = readDalytran();
            dalytranStatus = read.status();

            if (FileStatus.isOk(dalytranStatus)) {
                applResult = FileStatus.APPL_AOK;
                dalytranRecord = read.dalyTran().orElseThrow(() -> new IllegalStateException("A read of "
                        + "DD " + DALYTRAN_DD_NAME + " reported file status "
                        + FileStatus.toStatusImage(FileStatus.OK) + " and carried no record. The two are "
                        + "contradictory: a successful READ ... INTO populates the record area, which "
                        + PROGRAM_ID + ":168 then displays."));
                recordsRead++;
            } else if (FileStatus.isEndOfFile(dalytranStatus)) {
                applResult = FileStatus.APPL_EOF;
            } else {
                applResult = APPL_RESULT_FATAL;
            }

            if (applResult == FileStatus.APPL_AOK) {
                return;
            }
            if (applResult == FileStatus.APPL_EOF) {
                endOfDailyTransFile = AT_END_OF_FILE;
                return;
            }
            throw reportAndAbend(ERROR_READING_DALYTRAN, dalytranStatus);
        }

        private DalyTranRepository.ReadResult readDalytran() {
            try {
                return dalytranFile.readNext();
            } catch (RuntimeException refused) {
                datasetRefusal = refused;
                return DalyTranRepository.ReadResult.other(PERMANENT_ERROR_STATUS);
            }
        }

        private void lookupXref() {
            xrefLookups++;

            // :228 MOVE XREF-CARD-NUM TO FD-XREF-CARD-NUM - the repository applies the same PIC X(16) MOVE
            // to whatever it is handed, so the key is built exactly once. :229 READ XREF-FILE RECORD INTO
            // CARD-XREF-RECORD KEY IS FD-XREF-CARD-NUM.
            CardXrefRepository.ReadResult read = readXrefByCardNumber(xrefCardNum);
            xreffileStatus = read.status();

            if (isInvalidKeyCondition(xreffileStatus)) {
                sysout.display(INVALID_CARD_NUMBER_FOR_XREF);
                xrefReadStatus = READ_STATUS_INVALID_KEY;
            } else if (FileStatus.isOk(xreffileStatus)) {
                cardXrefRecord = read.record().orElseThrow(() -> new IllegalStateException("A read of DD "
                        + XREFFILE_DD_NAME + " reported file status "
                        + FileStatus.toStatusImage(FileStatus.OK) + " and carried no record. The two are "
                        + "contradictory: the NOT INVALID KEY arm of " + PROGRAM_ID + ":234 displays three "
                        + "fields of the record area a successful READ ... INTO has just populated."));
                xrefCardNum = codec.movePicX(cardXrefRecord.xrefCardNum(),
                        CardXrefRecord.XREF_CARD_NUM_LENGTH);
                sysout.display(SUCCESSFUL_READ_OF_XREF);
                sysout.display(XREF_CARD_NUMBER_PREFIX + xrefCardNum);
                sysout.display(XREF_ACCOUNT_ID_PREFIX
                        + codec.movePic9(cardXrefRecord.xrefAcctId(), CardXrefRecord.XREF_ACCT_ID_LENGTH));
                sysout.display(XREF_CUSTOMER_ID_PREFIX
                        + codec.movePic9(cardXrefRecord.xrefCustId(), CardXrefRecord.XREF_CUST_ID_LENGTH));
            }
        }

        private CardXrefRepository.ReadResult readXrefByCardNumber(String cardNumber) {
            try {
                return xrefAccess.readByCardNumber(cardNumber);
            } catch (RuntimeException refused) {
                datasetRefusal = refused;
                return CardXrefRepository.ReadResult.other(XREFFILE_DD_NAME, PERMANENT_ERROR_STATUS);
            }
        }

        private void readAccount() {
            accountReads++;

            AccountRepository.ReadResult read = readAccountByKey(acctIdKey);
            acctfileStatus = read.status();

            if (isInvalidKeyCondition(acctfileStatus)) {
                sysout.display(INVALID_ACCOUNT_NUMBER_FOUND);
                acctReadStatus = READ_STATUS_INVALID_KEY;
            } else if (FileStatus.isOk(acctfileStatus)) {
                acctId = codec.movePic9(read.account().orElseThrow(() -> new IllegalStateException("A read "
                        + "of DD " + ACCTFILE_DD_NAME + " reported file status "
                        + FileStatus.toStatusImage(FileStatus.OK) + " and carried no record. The two are "
                        + "contradictory: a successful READ ... INTO populates ACCOUNT-RECORD, which is "
                        + "what makes ACCT-ID the account file's own value from " + PROGRAM_ID
                        + ":248 onward.")).getAcctId(), AccountRecord.KEY_LENGTH);
                sysout.display(SUCCESSFUL_READ_OF_ACCOUNT_FILE);
            }
        }

        private AccountRepository.ReadResult readAccountByKey(long accountId) {
            try {
                return acctfile.readByKey(accountId);
            } catch (RuntimeException refused) {
                datasetRefusal = refused;
                return AccountRepository.ReadResult.of(PERMANENT_ERROR_STATUS);
            }
        }

        private void openDalytran() {
            applResult = APPL_RESULT_ASSUMED_FAILURE;
            dalytranStatus = openDalytranFile();
            if (FileStatus.isOk(dalytranStatus)) {
                applResult = FileStatus.APPL_AOK;
            } else {
                applResult = APPL_RESULT_FATAL;
            }
            if (applResult == FileStatus.APPL_AOK) {
                return;
            }
            throw reportAndAbend(ERROR_OPENING_DALYTRAN, dalytranStatus);
        }

        private void openCustfile() {
            applResult = APPL_RESULT_ASSUMED_FAILURE;
            custfileStatus = openCustomerFile();
            if (FileStatus.isOk(custfileStatus)) {
                applResult = FileStatus.APPL_AOK;
            } else {
                applResult = APPL_RESULT_FATAL;
            }
            if (applResult == FileStatus.APPL_AOK) {
                return;
            }
            throw reportAndAbend(ERROR_OPENING_CUSTFILE, custfileStatus);
        }

        private void openXreffile() {
            applResult = APPL_RESULT_ASSUMED_FAILURE;
            xreffileStatus = openXrefFile();
            if (FileStatus.isOk(xreffileStatus)) {
                applResult = FileStatus.APPL_AOK;
            } else {
                applResult = APPL_RESULT_FATAL;
            }
            if (applResult == FileStatus.APPL_AOK) {
                return;
            }
            throw reportAndAbend(ERROR_OPENING_XREFFILE, xreffileStatus);
        }

        private void openCardfile() {
            applResult = APPL_RESULT_ASSUMED_FAILURE;
            cardfileStatus = openCardFile();
            if (FileStatus.isOk(cardfileStatus)) {
                applResult = FileStatus.APPL_AOK;
            } else {
                applResult = APPL_RESULT_FATAL;
            }
            if (applResult == FileStatus.APPL_AOK) {
                return;
            }
            throw reportAndAbend(ERROR_OPENING_CARDFILE, cardfileStatus);
        }

        private void openAcctfile() {
            applResult = APPL_RESULT_ASSUMED_FAILURE;
            acctfileStatus = openAccountFile();
            if (FileStatus.isOk(acctfileStatus)) {
                applResult = FileStatus.APPL_AOK;
            } else {
                applResult = APPL_RESULT_FATAL;
            }
            if (applResult == FileStatus.APPL_AOK) {
                return;
            }
            throw reportAndAbend(ERROR_OPENING_ACCTFILE, acctfileStatus);
        }

        private void openTranfile() {
            applResult = APPL_RESULT_ASSUMED_FAILURE;
            tranfileStatus = openTransactionFile();
            if (FileStatus.isOk(tranfileStatus)) {
                applResult = FileStatus.APPL_AOK;
            } else {
                applResult = APPL_RESULT_FATAL;
            }
            if (applResult == FileStatus.APPL_AOK) {
                return;
            }
            throw reportAndAbend(ERROR_OPENING_TRANFILE, tranfileStatus);
        }

        // A COBOL program has no usable file after a failed OPEN either, and it is what makes the
        // best-effort release below release exactly the files an OPEN actually established.

        private String openDalytranFile() {
            try {
                DalyTranRepository.DalytranFile opened = dalyTranRepository.open();
                String status = opened.openStatus();
                if (FileStatus.isOk(status)) {
                    dalytranFile = opened;
                }
                return status;
            } catch (RuntimeException refused) {
                datasetRefusal = refused;
                return PERMANENT_ERROR_STATUS;
            }
        }

        private String openCustomerFile() {
            try {
                CustomerRepository.CustomerFile opened = customerRepository.openInput();
                String status = opened.openStatus();
                if (FileStatus.isOk(status)) {
                    custfile = opened;
                }
                return status;
            } catch (RuntimeException refused) {
                datasetRefusal = refused;
                return PERMANENT_ERROR_STATUS;
            }
        }

        private String openXrefFile() {
            try {
                var binding = batchConfig.datasetBinding(JOB_KEY, XREFFILE_DD_NAME);
                CardXrefRepository access = cardXrefRepository.addressing(binding, XREFFILE_DD_NAME,
                        null, CardXrefRepository.ALTERNATE_INDEX_BATCH_DD_NAME);
                CardXrefRepository.BrowseCursor opened = access.openBrowse();
                String status = opened.openStatus();
                if (FileStatus.isOk(status)) {
                    xrefAccess = access;
                    xreffile = opened;
                }
                return status;
            } catch (RuntimeException refused) {
                datasetRefusal = refused;
                return PERMANENT_ERROR_STATUS;
            }
        }

        private String openCardFile() {
            try {
                var binding = batchConfig.datasetBinding(JOB_KEY, CARDFILE_DD_NAME);
                CardRepository access = cardRepository.addressing(binding, CARDFILE_DD_NAME);
                CardRepository.CardBrowse opened =
                        access.openBrowse(LOWEST_CARD_NUMBER_KEY, BrowseDirection.FORWARD);
                String status = FileStatus.batchStatusOfCicsResp(opened.openResp())
                        .orElse(PERMANENT_ERROR_STATUS);
                if (FileStatus.isOk(status)) {
                    cardfile = opened;
                }
                return status;
            } catch (RuntimeException refused) {
                datasetRefusal = refused;
                return PERMANENT_ERROR_STATUS;
            }
        }

        private String openAccountFile() {
            try {
                AccountRepository.AccountFile opened =
                        accountRepository.open(AccountRepository.OpenMode.INPUT);
                String status = opened.openStatus();
                if (FileStatus.isOk(status)) {
                    acctfile = opened;
                }
                return status;
            } catch (RuntimeException refused) {
                datasetRefusal = refused;
                return PERMANENT_ERROR_STATUS;
            }
        }

        private String openTransactionFile() {
            try {
                var binding = batchConfig.datasetBinding(JOB_KEY, TRANFILE_DD_NAME);
                TransactionRepository.InputFile opened = transactionRepository.openInput(binding);
                String status = opened.openStatus();
                if (FileStatus.isOk(status)) {
                    tranfile = opened;
                }
                return status;
            } catch (RuntimeException refused) {
                datasetRefusal = refused;
                return PERMANENT_ERROR_STATUS;
            }
        }

        private void closeDalytran() {
            applResult = APPL_RESULT_ASSUMED_FAILURE;
            dalytranCloseIssued = true;
            dalytranStatus = closeDalytranFile();
            if (FileStatus.isOk(dalytranStatus)) {
                applResult = FileStatus.APPL_AOK;
            } else {
                applResult = APPL_RESULT_FATAL;
            }
            if (applResult == FileStatus.APPL_AOK) {
                return;
            }
            throw reportAndAbend(ERROR_CLOSING_CUSTFILE, custfileStatus);
        }

        private void closeCustfile() {
            applResult = APPL_RESULT_ASSUMED_FAILURE;
            custfileCloseIssued = true;
            custfileStatus = closeCustomerFile();
            if (FileStatus.isOk(custfileStatus)) {
                applResult = FileStatus.APPL_AOK;
            } else {
                applResult = APPL_RESULT_FATAL;
            }
            if (applResult == FileStatus.APPL_AOK) {
                return;
            }
            throw reportAndAbend(ERROR_CLOSING_CUSTFILE, custfileStatus);
        }

        private void closeXreffile() {
            applResult = APPL_RESULT_ASSUMED_FAILURE;
            xreffileCloseIssued = true;
            xreffileStatus = closeXrefFile();
            if (FileStatus.isOk(xreffileStatus)) {
                applResult = FileStatus.APPL_AOK;
            } else {
                applResult = APPL_RESULT_FATAL;
            }
            if (applResult == FileStatus.APPL_AOK) {
                return;
            }
            throw reportAndAbend(ERROR_CLOSING_XREFFILE, xreffileStatus);
        }

        private void closeCardfile() {
            applResult = APPL_RESULT_ASSUMED_FAILURE;
            cardfileCloseIssued = true;
            cardfileStatus = closeCardFile();
            if (FileStatus.isOk(cardfileStatus)) {
                applResult = FileStatus.APPL_AOK;
            } else {
                applResult = APPL_RESULT_FATAL;
            }
            if (applResult == FileStatus.APPL_AOK) {
                return;
            }
            throw reportAndAbend(ERROR_CLOSING_CARDFILE, cardfileStatus);
        }

        private void closeAcctfile() {
            applResult = APPL_RESULT_ASSUMED_FAILURE;
            acctfileCloseIssued = true;
            acctfileStatus = closeAccountFile();
            if (FileStatus.isOk(acctfileStatus)) {
                applResult = FileStatus.APPL_AOK;
            } else {
                applResult = APPL_RESULT_FATAL;
            }
            if (applResult == FileStatus.APPL_AOK) {
                return;
            }
            throw reportAndAbend(ERROR_CLOSING_ACCTFILE, acctfileStatus);
        }

        private void closeTranfile() {
            applResult = APPL_RESULT_ASSUMED_FAILURE;
            tranfileCloseIssued = true;
            tranfileStatus = closeTransactionFile();
            if (FileStatus.isOk(tranfileStatus)) {
                applResult = FileStatus.APPL_AOK;
            } else {
                applResult = APPL_RESULT_FATAL;
            }
            if (applResult == FileStatus.APPL_AOK) {
                return;
            }
            throw reportAndAbend(ERROR_CLOSING_TRANFILE, tranfileStatus);
        }

        private String closeDalytranFile() {
            try {
                return dalytranFile.closeFile();
            } catch (RuntimeException refused) {
                datasetRefusal = refused;
                return PERMANENT_ERROR_STATUS;
            }
        }

        private String closeCustomerFile() {
            try {
                return custfile.closeFile();
            } catch (RuntimeException refused) {
                datasetRefusal = refused;
                return PERMANENT_ERROR_STATUS;
            }
        }

        private String closeXrefFile() {
            try {
                return xreffile.closeBrowse();
            } catch (RuntimeException refused) {
                datasetRefusal = refused;
                return PERMANENT_ERROR_STATUS;
            }
        }

        private String closeCardFile() {
            try {
                cardfile.endBrowse();
                return FileStatus.OK;
            } catch (RuntimeException refused) {
                datasetRefusal = refused;
                return PERMANENT_ERROR_STATUS;
            }
        }

        private String closeAccountFile() {
            try {
                return acctfile.closeFile();
            } catch (RuntimeException refused) {
                datasetRefusal = refused;
                return PERMANENT_ERROR_STATUS;
            }
        }

        private String closeTransactionFile() {
            try {
                return tranfile.closeInput();
            } catch (RuntimeException refused) {
                datasetRefusal = refused;
                return PERMANENT_ERROR_STATUS;
            }
        }

        // No COBOL counterpart, and deliberately invisible.

        private void releaseUnclosedHandles() {
            if (!tranfileCloseIssued) {
                releaseQuietly(tranfile, TRANFILE_DD_NAME);
            }
            if (!acctfileCloseIssued) {
                releaseQuietly(acctfile, ACCTFILE_DD_NAME);
            }
            if (!cardfileCloseIssued) {
                releaseQuietly(cardfile, CARDFILE_DD_NAME);
            }
            if (!xreffileCloseIssued) {
                releaseQuietly(xreffile, XREFFILE_DD_NAME);
            }
            if (!custfileCloseIssued) {
                releaseQuietly(custfile, CUSTFILE_DD_NAME);
            }
            if (!dalytranCloseIssued) {
                releaseQuietly(dalytranFile, DALYTRAN_DD_NAME);
            }
        }

        private AbendException reportAndAbend(String errorText, String status) {
            sysout.display(errorText);
            ioStatus = status;
            displayIoStatus();
            return abendProgram(errorText);
        }

        private void displayIoStatus() {
            sysout.display(FileStatus.toDisplayLine(ioStatus));
        }

        private AbendException abendProgram(String reason) {
            sysout.display(AbendException.ABEND_DISPLAY_TEXT);
            return AbendException.standard(PROGRAM_ID, applResult,
                    reason + " " + FileStatus.toDisplayLine(ioStatus), datasetRefusal);
        }
    }

    static boolean isInvalidKeyCondition(String status) {
        return FileStatus.isNotFound(status) || FileStatus.isDuplicate(status);
    }

    private static void releaseQuietly(AutoCloseable handle, String ddName) {
        if (handle == null) {
            return;
        }
        try {
            handle.close();
        } catch (Exception cleanupFailure) {
            LOG.warn("Releasing the " + ddName + " handle of " + PROGRAM_ID + " after an incomplete run "
                    + "failed - " + cleanupFailure.getClass().getName() + ". The run's own outcome is "
                    + "reported unchanged, because the run's own failure is the one that matters.");
        }
    }

    private SysoutSink resolveSysoutSink() {
        return sysoutSinkProvider.getIfAvailable(this::defaultSysoutSink);
    }

    /**
     * The sink used when the container publishes none: one line per {@code DISPLAY}, verbatim, in the
     * configured dataset code page, to the process's standard output.
     *
     * @return a sink writing to the process's standard output; never {@code null}
     */
    public SysoutSink defaultSysoutSink() {
        return sysoutSinkTo(System.out);
    }

    /**
     * The same sink over a caller-chosen stream, for a deployment whose {@code SYSOUT} is a file, a pipe or
     * an in-memory buffer rather than the process's own output.
     *
     * @param destination where the bytes go; never {@code null}
     * @return a sink writing to that stream; never {@code null}
     * @throws NullPointerException if {@code destination} is {@code null}
     */
    public SysoutSink sysoutSinkTo(OutputStream destination) {
        return new StreamSysoutSink(destination, codec);
    }

    @FunctionalInterface
    public interface SysoutSink {
        void display(String line);
    }

    /**
     * The default {@link SysoutSink}: bytes in the configured code page, one line feed, flushed.
     */
    private static final class StreamSysoutSink implements SysoutSink {
        private final OutputStream destination;

        private final FixedWidthCodec codec;

        private StreamSysoutSink(OutputStream destination, FixedWidthCodec codec) {
            this.destination = Objects.requireNonNull(destination, "A destination stream is required for "
                    + "SYSOUT: " + PROGRAM_ID + "'s DISPLAY sequence is its entire observable output");
            this.codec = Objects.requireNonNull(codec, "A codec is required: a SYSOUT line is written in a "
                    + "named code page, never in the platform default");
        }

        /**
         * Writes the line and its terminator, then flushes.
         *
         * @param line the line to emit
         * @throws NullPointerException if {@code line} is {@code null}
         * @throws UncheckedIOException if the stream refuses the write
         */
        @Override
        public void display(String line) {
            Objects.requireNonNull(line, "A line is required to display; " + PROGRAM_ID + " never displays "
                    + "nothing");
            byte[] encoded = codec.encodeImage(line + SYSOUT_LINE_TERMINATOR, SYSOUT_SUBJECT);
            try {
                destination.write(encoded);
                destination.flush();
            } catch (IOException refused) {
                throw new UncheckedIOException("Could not write a SYSOUT line of " + PROGRAM_ID + " to its "
                        + "destination stream; " + encoded.length + " byte(s) were pending", refused);
            }
        }
    }

    public record ExecutionSummary(int returnCode, int recordsRead, int xrefLookups, int accountReads) {
        /**
         * How many cross-reference lookups a run performs beyond the number of records it read: one,
         * always, and never zero.
         */
        public static final int POST_END_OF_FILE_LOOKUPS = 1;

        public ExecutionSummary {
            if (recordsRead < 0 || xrefLookups < 0 || accountReads < 0) {
                throw new IllegalArgumentException("An execution of " + PROGRAM_ID + " cannot have read "
                        + recordsRead + " record(s), performed " + xrefLookups + " cross-reference "
                        + "lookup(s) and " + accountReads + " account read(s): none of those can be "
                        + "negative.");
            }
            if (xrefLookups != recordsRead + POST_END_OF_FILE_LOOKUPS) {
                throw new IllegalArgumentException("An execution of " + PROGRAM_ID + " that read "
                        + recordsRead + " record(s) performs exactly "
                        + (recordsRead + POST_END_OF_FILE_LOOKUPS) + " cross-reference lookup(s), not "
                        + xrefLookups + ". The extra one is not optional: app/cbl/CBTRN01C.cbl:170-172 sits "
                        + "outside the end-of-file guard that closes at :169, so the iteration whose read "
                        + "reported end of file looks up the previous record's card number before the loop "
                        + "ends. See TransactionPostingJob's documentation, defect 1.");
            }
            if (accountReads > xrefLookups) {
                throw new IllegalArgumentException("An execution of " + PROGRAM_ID + " cannot have "
                        + "performed " + accountReads + " account read(s) against " + xrefLookups
                        + " cross-reference lookup(s): app/cbl/CBTRN01C.cbl:176 reads the account only on "
                        + "the arm a lookup that did not report an invalid key selects, so there is at most "
                        + "one account read per lookup.");
            }
        }

        /**
         * The one lookup this run performed beyond its record count.
         *
         * @return {@value #POST_END_OF_FILE_LOOKUPS}, always
         */
        public int postEndOfFileLookups() {
            return xrefLookups - recordsRead;
        }

        /**
         * How many raw {@code DALYTRAN-RECORD} images the run displayed: one per record read.
         *
         * @return the number of record image lines
         */
        public int recordImageLinesDisplayed() {
            return recordsRead;
        }

        /**
         * Whether the run ended with {@code RETURN-CODE} zero, which every execution that returns at all
         * does.
         *
         * @return {@code true} when the return code is {@link AbendException#RETURN_CODE_OK}
         */
        public boolean completedCleanly() {
            return returnCode == AbendException.RETURN_CODE_OK;
        }
    }
}

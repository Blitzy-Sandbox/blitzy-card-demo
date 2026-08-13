package com.vsergeychik.carddemo.account;

import com.vsergeychik.carddemo.account.AccountRepository.AccountFile;
import com.vsergeychik.carddemo.account.AccountRepository.OpenMode;
import com.vsergeychik.carddemo.account.AccountRepository.ReadResult;
import com.vsergeychik.carddemo.account.model.AccountRecord;
import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.config.BatchConfig;
import com.vsergeychik.carddemo.config.BatchConfig.StepContract;
import com.vsergeychik.carddemo.config.BatchConfig.StopSignal;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Objects;

/**
 * {@code CBACT01C} - the batch program that reads the account master sequentially and prints every record
 * to {@code SYSOUT}.
 *
 * <p>A future reader who "completes" this class by adding a balance calculation would not be finishing an
 * unfinished migration; they would be inventing a feature the COBOL estate does not have, and every one of
 * this program's parity cases would fail at once.
 */
@Configuration(AccountBalanceJob.CONFIGURATION_BEAN_NAME)
public class AccountBalanceJob {
    /**
     * The bean name of this configuration class itself.
     */
    public static final String CONFIGURATION_BEAN_NAME = "accountBalanceJobConfiguration";

    private static final Log LOG = LogFactory.getLog(AccountBalanceJob.class);

    /**
     * The COBOL {@code PROGRAM-ID} this job was translated from, as {@code app/cbl/CBACT01C.cbl:L23}
     * declares it.
     */
    public static final String PROGRAM_ID = "CBACT01C";

    public static final String JOB_KEY = "account-balance-job";

    public static final String JOB_NAME = "accountBalanceJob";

    /**
     * The single step, named after the JCL step it replaces: {@code //STEP05 EXEC PGM=CBACT01C} at
     * {@code app/jcl/READACCT.jcl:L22}.
     */
    public static final String STEP_NAME = "STEP05";

    /**
     * The whole step sequence of {@code app/jcl/READACCT.jcl}: one step, {@link #STEP_NAME}, running
     * {@link #PROGRAM_ID}, ungated.
     */
    public static final List<StepContract> REQUIRED_STEPS =
            List.of(new StepContract(STEP_NAME, PROGRAM_ID, false));

    /**
     * The DD name the JCL binds the account master under, {@code app/jcl/READACCT.jcl:L25-L26}.
     */
    public static final String DD_NAME = AccountRepository.BATCH_DD_NAME;

    /**
     * {@code DISPLAY 'START OF EXECUTION OF PROGRAM CBACT01C'} - {@code app/cbl/CBACT01C.cbl:L71}.
     */
    public static final String START_OF_EXECUTION = "START OF EXECUTION OF PROGRAM CBACT01C";

    /**
     * {@code DISPLAY 'END OF EXECUTION OF PROGRAM CBACT01C'} - {@code app/cbl/CBACT01C.cbl:L85}.
     */
    public static final String END_OF_EXECUTION = "END OF EXECUTION OF PROGRAM CBACT01C";

    /**
     * {@code DISPLAY 'ERROR OPENING ACCTFILE'} - {@code app/cbl/CBACT01C.cbl:L144}.
     */
    public static final String ERROR_OPENING_ACCTFILE = "ERROR OPENING ACCTFILE";

    /**
     * {@code DISPLAY 'ERROR READING ACCOUNT FILE'} - {@code app/cbl/CBACT01C.cbl:L110}.
     */
    public static final String ERROR_READING_ACCOUNT_FILE = "ERROR READING ACCOUNT FILE";

    /**
     * {@code DISPLAY 'ERROR CLOSING ACCOUNT FILE'} - {@code app/cbl/CBACT01C.cbl:L162}.
     */
    public static final String ERROR_CLOSING_ACCOUNT_FILE = "ERROR CLOSING ACCOUNT FILE";

    /**
     * The width of every label literal in {@code 1100-DISPLAY-ACCT-RECORD}: exactly 25 characters, ending
     * in a colon, with the field's display image following immediately and no space between.
     */
    public static final int LABEL_WIDTH = 25;

    /**
     * The number of hyphens in the separator line at {@code app/cbl/CBACT01C.cbl:L130}: exactly 49.
     */
    public static final int SEPARATOR_WIDTH = 49;

    /**
     * The rule that closes every record's field block: {@code DISPLAY ' '} at
     * {@code app/cbl/CBACT01C.cbl:L130}.
     */
    public static final String RECORD_SEPARATOR = "-".repeat(SEPARATOR_WIDTH);

    // Each is its copybook field name padded to LABEL_WIDTH and terminated with a colon, which is exactly
    // how the COBOL literals are formed.

    /**
     * Label for {@code ACCT-ID} - {@code app/cbl/CBACT01C.cbl:L119}.
     */
    public static final String LABEL_ACCT_ID = label(AccountRecord.ACCT_ID_NAME);

    /**
     * Label for {@code ACCT-ACTIVE-STATUS} - {@code app/cbl/CBACT01C.cbl:L120}.
     */
    public static final String LABEL_ACCT_ACTIVE_STATUS = label(AccountRecord.ACCT_ACTIVE_STATUS_NAME);

    /**
     * Label for {@code ACCT-CURR-BAL} - {@code app/cbl/CBACT01C.cbl:L121}.
     */
    public static final String LABEL_ACCT_CURR_BAL = label(AccountRecord.ACCT_CURR_BAL_NAME);

    /**
     * Label for {@code ACCT-CREDIT-LIMIT} - {@code app/cbl/CBACT01C.cbl:L122}.
     */
    public static final String LABEL_ACCT_CREDIT_LIMIT = label(AccountRecord.ACCT_CREDIT_LIMIT_NAME);

    /**
     * Label for {@code ACCT-CASH-CREDIT-LIMIT} - {@code app/cbl/CBACT01C.cbl:L123}.
     */
    public static final String LABEL_ACCT_CASH_CREDIT_LIMIT =
            label(AccountRecord.ACCT_CASH_CREDIT_LIMIT_NAME);

    /**
     * Label for {@code ACCT-OPEN-DATE} - {@code app/cbl/CBACT01C.cbl:L124}.
     */
    public static final String LABEL_ACCT_OPEN_DATE = label(AccountRecord.ACCT_OPEN_DATE_NAME);

    /**
     * Label for {@code ACCT-EXPIRAION-DATE} - {@code app/cbl/CBACT01C.cbl:L125}.
     */
    public static final String LABEL_ACCT_EXPIRAION_DATE =
            label(AccountRecord.ACCT_EXPIRAION_DATE_NAME);

    /**
     * Label for {@code ACCT-REISSUE-DATE} - {@code app/cbl/CBACT01C.cbl:L126}.
     */
    public static final String LABEL_ACCT_REISSUE_DATE = label(AccountRecord.ACCT_REISSUE_DATE_NAME);

    /**
     * Label for {@code ACCT-CURR-CYC-CREDIT} - {@code app/cbl/CBACT01C.cbl:L127}.
     */
    public static final String LABEL_ACCT_CURR_CYC_CREDIT =
            label(AccountRecord.ACCT_CURR_CYC_CREDIT_NAME);

    /**
     * Label for {@code ACCT-CURR-CYC-DEBIT} - {@code app/cbl/CBACT01C.cbl:L128}.
     */
    public static final String LABEL_ACCT_CURR_CYC_DEBIT =
            label(AccountRecord.ACCT_CURR_CYC_DEBIT_NAME);

    /**
     * Label for {@code ACCT-GROUP-ID} - {@code app/cbl/CBACT01C.cbl:L129}.
     */
    public static final String LABEL_ACCT_GROUP_ID = label(AccountRecord.ACCT_GROUP_ID_NAME);

    /**
     * The eleven labels in emission order, published so the sequence can be asserted as a whole rather than
     * one constant at a time.
     */
    public static final List<String> FIELD_LABELS = List.of(
            LABEL_ACCT_ID,
            LABEL_ACCT_ACTIVE_STATUS,
            LABEL_ACCT_CURR_BAL,
            LABEL_ACCT_CREDIT_LIMIT,
            LABEL_ACCT_CASH_CREDIT_LIMIT,
            LABEL_ACCT_OPEN_DATE,
            LABEL_ACCT_EXPIRAION_DATE,
            LABEL_ACCT_REISSUE_DATE,
            LABEL_ACCT_CURR_CYC_CREDIT,
            LABEL_ACCT_CURR_CYC_DEBIT,
            LABEL_ACCT_GROUP_ID);

    public static final int LINES_PER_RECORD = 13;

    /**
     * {@code 88 APPL-AOK VALUE 0} - {@code app/cbl/CBACT01C.cbl:L62}.
     */
    public static final int APPL_AOK = FileStatus.APPL_AOK;

    /**
     * {@code 88 APPL-EOF VALUE 16} - {@code app/cbl/CBACT01C.cbl:L63}.
     */
    public static final int APPL_EOF = FileStatus.APPL_EOF;

    /**
     * The value moved into {@code APPL-RESULT} before each I/O verb is issued: {@code 8}, from
     * {@code MOVE 8 TO APPL-RESULT} at {@code L134} and {@code ADD 8 TO ZERO GIVING APPL-RESULT} at
     * {@code L152}.
     */
    public static final int APPL_RESULT_ASSUMED_FAILURE = AbendException.RETURN_CODE_ASSUMED_FAILURE;

    /**
     * The value moved into {@code APPL-RESULT} on every fatal arm: {@code 12}, from {@code MOVE 12} at
     * {@code L101} and {@code L139} and {@code ADD 12 TO ZERO GIVING} at {@code L157}.
     */
    public static final int APPL_RESULT_FATAL = AccountRepository.APPL_RESULT_FATAL;

    public static final int RETURN_CODE_NORMAL_END = AbendException.RETURN_CODE_OK;

    /**
     * {@code END-OF-FILE PIC X(01) VALUE 'N'} - {@code app/cbl/CBACT01C.cbl:L65}, its declared initial
     * value.
     */
    public static final String END_OF_FILE_NO = "N";

    /**
     * The value {@code MOVE 'Y' TO END-OF-FILE} places in the flag at {@code app/cbl/CBACT01C.cbl:L108},
     * and the value the mainline loop terminates on.
     */
    public static final String END_OF_FILE_YES = "Y";

    private final BatchConfig batchConfig;

    private final AccountRepository accountRepository;

    private final FixedWidthCodec codec;

    private final SysoutSink sysoutSink;

    private final StepContract stepContract;

    public AccountBalanceJob(BatchConfig batchConfig,
            AccountRepository accountRepository,
            ObjectProvider<SysoutSink> sysoutSinkProvider) {
        this.batchConfig = Objects.requireNonNull(batchConfig, "The batch scaffolding is required: the "
                + "job and step builders, the job repository and the transaction manager all arrive "
                + "through it, so this class holds no Spring Batch plumbing of its own");
        this.accountRepository = Objects.requireNonNull(accountRepository, "The account master "
                + "repository is required: " + PROGRAM_ID + " reads the " + DD_NAME + " dataset "
                + "sequentially and does nothing else");
        Objects.requireNonNull(sysoutSinkProvider, "A SYSOUT sink provider is required; it may resolve "
                + "to no bean, in which case the standard output stream is used");

        this.codec = new FixedWidthCodec(accountRepository.datasetCharset());
        this.sysoutSink = sysoutSinkProvider
                .getIfAvailable(() -> standardOutput(accountRepository.datasetCharset()));
        this.stepContract = requireUngatedStep(batchConfig);
    }

    private static StepContract requireUngatedStep(BatchConfig scaffolding) {
        StepContract contract = scaffolding.contract(JOB_KEY).step(STEP_NAME);
        if (!PROGRAM_ID.equals(contract.program())) {
            throw new IllegalStateException("The carddemo.jobs contract for '" + JOB_KEY + "' declares "
                    + "step '" + STEP_NAME + "' running program '" + contract.program() + "', but this "
                    + "job is the translation of " + PROGRAM_ID + " (app/jcl/READACCT.jcl:L22). A step "
                    + "that named another program would resolve another program's DD names.");
        }
        if (contract.requirePrecedingExitCodeZero()) {
            throw new IllegalStateException("The carddemo.jobs contract for '" + JOB_KEY + "' gates step "
                    + "'" + STEP_NAME + "' on a preceding exit code, but app/jcl/READACCT.jcl carries no "
                    + "COND and declares only this step. Gating the only step of a single-step job would "
                    + "bypass all of its work.");
        }
        scaffolding.requireSteps(JOB_KEY, REQUIRED_STEPS, "app/jcl/READACCT.jcl:L22");
        return contract;
    }

    /**
     * The job, published as a bean: one step, no job parameters.
     *
     * @return the {@link #JOB_NAME} job; never {@code null}
     */
    @Bean
    public Job accountBalanceJob() {
        return batchConfig.job(JOB_NAME)
                .start(accountBalanceStep())
                .build();
    }

    /**
     * The single step, named {@link #STEP_NAME} after the JCL step it replaces.
     *
     * <p>A tasklet step, and never a chunk-oriented one: the COBOL is one sequential pass whose display
     * ordering is its entire observable behaviour, and chunking would relocate the commit boundaries that
     * ordering sits inside.
     *
     * @return a fresh step; never {@code null}
     */
    public Step accountBalanceStep() {
        return batchConfig.taskletStep(stepContract.name(), accountFileDisplayTasklet()).build();
    }

    /**
     * The step body: one invocation, one complete pass over the account master.
     *
     * @return the tasklet; never {@code null}
     */
    public Tasklet accountFileDisplayTasklet() {
        return this::executeStep;
    }

    private RepeatStatus executeStep(StepContribution contribution, ChunkContext chunkContext) {
        int recordsDisplayed = readAndPrintAccountFile(sysoutSink, StopSignal.of(chunkContext));
        for (int recorded = 0; recorded < recordsDisplayed; recorded++) {
            contribution.incrementReadCount();
        }
        return RepeatStatus.FINISHED;
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
     * The sink every {@code DISPLAY} of this job is written to.
     *
     * @return the resolved sink; never {@code null}
     */
    public SysoutSink sysoutSink() {
        return sysoutSink;
    }

    /**
     * Runs the program against the configured {@code SYSOUT} sink.
     *
     * @return the number of account records read and displayed
     * @throws AbendException if the open, a read or the close reports a status this program treats as fatal
     */
    public int readAndPrintAccountFile() {
        return readAndPrintAccountFile(sysoutSink);
    }

    /**
     * Runs the program, writing every {@code DISPLAY} to the given sink: the whole of
     * {@code app/cbl/CBACT01C.cbl:L71-L87}, in order.
     *
     * @param sysout where every displayed line goes; must not be {@code null}
     * @return the number of account records read and displayed; {@code 0} for an empty dataset
     * @throws NullPointerException if {@code sysout} is {@code null}
     * @throws AbendException if the open, a read or the close reports a status this program treats as
     *     fatal, carrying {@link #APPL_RESULT_FATAL} as its return code
     */
    public int readAndPrintAccountFile(SysoutSink sysout) {
        return readAndPrintAccountFile(sysout, StopSignal.RUNNING);
    }

    /**
     * Runs the program, yielding to the given stop signal between records.
     *
     * @param sysout where every displayed line goes; must not be {@code null}
     * @param stopSignal the between-record cancellation probe; {@link StopSignal#RUNNING} for a caller
     *     outside a step; must not be {@code null}
     * @return the number of account records read and displayed; {@code 0} for an empty dataset
     * @throws NullPointerException if {@code sysout} or {@code stopSignal} is {@code null}
     * @throws AbendException if the open, a read or the close reports a status this program treats as
     *     fatal, carrying {@link #APPL_RESULT_FATAL} as its return code
     * @throws BatchConfig.StopRequestedException if the step is asked to stop, which abandons the pass
     *     between records
     */
    public int readAndPrintAccountFile(SysoutSink sysout, StopSignal stopSignal) {
        Objects.requireNonNull(sysout, "A SYSOUT sink is required: the displayed line sequence is this "
                + "program's entire observable output, so there is nothing to run without somewhere to "
                + "write it");
        Objects.requireNonNull(stopSignal, "A stop signal is required; pass StopSignal.RUNNING outside a "
                + "step, which is what the single-argument overload does");

        sysout.write(START_OF_EXECUTION);

        WorkingStorage workingStorage = new WorkingStorage();

        AccountFile acctFile = acctFileOpen(sysout, workingStorage);

        boolean closeIssued = false;

        try {
            int recordsDisplayed = acctFileDisplayLoop(sysout, workingStorage, acctFile, stopSignal);

            closeIssued = true;
            acctFileClose(sysout, workingStorage, acctFile);

            sysout.write(END_OF_EXECUTION);

            return recordsDisplayed;
        } finally {
            if (!closeIssued) {
                releaseHandle(acctFile);
            }
        }
    }

    private static void releaseHandle(AccountFile acctFile) {
        try {
            acctFile.closeFile();
        } catch (RuntimeException cleanupFailure) {
            LOG.warn("Releasing the " + DD_NAME + " browse of " + PROGRAM_ID + " after an incomplete run "
                    + "failed - " + cleanupFailure.getClass().getName()
                    + ". The run's own outcome is reported to the caller unchanged, because the run's own "
                    + "failure is the one that matters.");
        }
    }

    private int acctFileDisplayLoop(SysoutSink sysout, WorkingStorage workingStorage,
            AccountFile acctFile, StopSignal stopSignal) {
        int recordsDisplayed = 0;
        while (!workingStorage.endOfFileIsYes()) {
            stopSignal.checkStopRequested();
            recordsDisplayed += acctFileDisplayIteration(sysout, workingStorage, acctFile);
        }
        return recordsDisplayed;
    }

    int acctFileDisplayIteration(SysoutSink sysout, WorkingStorage workingStorage,
            AccountFile acctFile) {
        if (!workingStorage.endOfFileIsNo()) {
            return 0;
        }

        ReadResult result = acctFileGetNext(sysout, workingStorage, acctFile);

        if (!workingStorage.endOfFileIsNo()) {
            return 0;
        }

        AccountRecord account = result.account().orElseThrow(() -> new IllegalStateException(
                "1000-ACCTFILE-GET-NEXT reported file status '" + result.status() + "' and left "
                        + "END-OF-FILE = '" + workingStorage.endOfFileFlag() + "', so the mainline "
                        + "reached DISPLAY ACCOUNT-RECORD with no record to display. Only the '"
                        + FileStatus.OK + "' arm leaves the flag unchanged, and that arm always carries "
                        + "the decoded record."));
        sysout.write(account.toFixedWidthString());
        return 1;
    }

    private ReadResult acctFileGetNext(SysoutSink sysout, WorkingStorage workingStorage,
            AccountFile acctFile) {
        ReadResult result = acctFile.readNext();
        String status = result.status();

        if (FileStatus.isOk(status)) {
            workingStorage.moveToApplResult(APPL_AOK);
            displayAcctRecord(sysout, result.account().orElseThrow(() -> new IllegalStateException(
                    "A read of the account master reported file status '" + FileStatus.OK + "' with no "
                            + "decoded record; 1100-DISPLAY-ACCT-RECORD has nothing to display.")));
        } else if (FileStatus.isEndOfFile(status)) {
            workingStorage.moveToApplResult(APPL_EOF);
        } else {
            workingStorage.moveToApplResult(APPL_RESULT_FATAL);
        }

        if (!workingStorage.applAok()) {
            if (workingStorage.applEof()) {
                workingStorage.moveEndOfFile(END_OF_FILE_YES);
            } else {
                throw reportAndAbend(sysout, workingStorage, ERROR_READING_ACCOUNT_FILE, status);
            }
        }
        return result;
    }

    private void displayAcctRecord(SysoutSink sysout, AccountRecord account) {
        sysout.write(displayLine(LABEL_ACCT_ID, account.rawAcctId()));
        sysout.write(displayLine(LABEL_ACCT_ACTIVE_STATUS, account.rawAcctActiveStatus()));
        sysout.write(displayLine(LABEL_ACCT_CURR_BAL, account.rawAcctCurrBal()));
        sysout.write(displayLine(LABEL_ACCT_CREDIT_LIMIT, account.rawAcctCreditLimit()));
        sysout.write(displayLine(LABEL_ACCT_CASH_CREDIT_LIMIT,
                account.rawAcctCashCreditLimit()));
        sysout.write(displayLine(LABEL_ACCT_OPEN_DATE, account.rawAcctOpenDate()));
        sysout.write(displayLine(LABEL_ACCT_EXPIRAION_DATE, account.rawAcctExpiraionDate()));
        sysout.write(displayLine(LABEL_ACCT_REISSUE_DATE, account.rawAcctReissueDate()));
        sysout.write(displayLine(LABEL_ACCT_CURR_CYC_CREDIT, account.rawAcctCurrCycCredit()));
        sysout.write(displayLine(LABEL_ACCT_CURR_CYC_DEBIT, account.rawAcctCurrCycDebit()));
        sysout.write(displayLine(LABEL_ACCT_GROUP_ID, account.rawAcctGroupId()));
        sysout.write(RECORD_SEPARATOR);
    }

    private String displayLine(String label, String image) {
        return codec.concatenateDelimitedBySize(label, image);
    }

    private AccountFile acctFileOpen(SysoutSink sysout, WorkingStorage workingStorage) {
        workingStorage.moveToApplResult(APPL_RESULT_ASSUMED_FAILURE);

        AccountFile acctFile = accountRepository.open(OpenMode.INPUT);
        String status = acctFile.openStatus();

        if (FileStatus.isOk(status)) {
            workingStorage.moveToApplResult(APPL_AOK);
        } else {
            workingStorage.moveToApplResult(APPL_RESULT_FATAL);
        }

        if (!workingStorage.applAok()) {
            throw reportAndAbend(sysout, workingStorage, ERROR_OPENING_ACCTFILE, status);
        }
        return acctFile;
    }

    private void acctFileClose(SysoutSink sysout, WorkingStorage workingStorage,
            AccountFile acctFile) {
        workingStorage.addToZeroGivingApplResult(APPL_RESULT_ASSUMED_FAILURE);

        String status = acctFile.closeFile();

        if (FileStatus.isOk(status)) {
            workingStorage.subtractApplResultFromApplResult();
        } else {
            workingStorage.addToZeroGivingApplResult(APPL_RESULT_FATAL);
        }

        if (!workingStorage.applAok()) {
            throw reportAndAbend(sysout, workingStorage, ERROR_CLOSING_ACCOUNT_FILE, status);
        }
    }

    private AbendException reportAndAbend(SysoutSink sysout, WorkingStorage workingStorage,
            String errorText, String status) {
        sysout.write(errorText);

        String statusLine = displayIoStatus(sysout, status);

        return abendProgram(sysout, workingStorage, errorText + " - " + statusLine);
    }

    private String displayIoStatus(SysoutSink sysout, String status) {
        String statusLine = FileStatus.toDisplayLine(status);
        sysout.write(statusLine);
        return statusLine;
    }

    private AbendException abendProgram(SysoutSink sysout, WorkingStorage workingStorage,
            String reason) {
        sysout.write(AbendException.ABEND_DISPLAY_TEXT);

        return AbendException.standard(PROGRAM_ID, workingStorage.applResult(), reason);
    }

    private static String label(String cobolName) {
        return cobolName + " ".repeat(LABEL_WIDTH - cobolName.length() - 1) + ':';
    }

    /**
     * Where a {@code DISPLAY} goes: the Java form of {@code //SYSOUT DD SYSOUT=*}
     * ({@code app/jcl/READACCT.jcl:L27}).
     */
    @FunctionalInterface
    public interface SysoutSink {
        void write(String line);
    }

    /**
     * The production sink: one line per call to the process's standard output stream, in the code page
     * given.
     *
     * @param charset the code page to encode each line in; must not be {@code null}
     * @return a sink writing to standard output
     * @throws NullPointerException if {@code charset} is {@code null}
     */
    public static SysoutSink standardOutput(Charset charset) {
        Objects.requireNonNull(charset, "A code page is required for SYSOUT: a displayed record is the "
                + "dataset's own bytes, and the platform default is never assumed");
        PrintStream stream = new PrintStream(new FileOutputStream(FileDescriptor.out), true, charset);
        return stream::println;
    }

    /**
     * The two working-storage items whose values drive this program's control flow:
     * {@code APPL-RESULT PIC S9(9) COMP} with its two {@code 88}-level conditions, and
     * {@code END-OF-FILE PIC X(01) VALUE 'N'}.
     */
    static final class WorkingStorage {
        private int applResult;

        private String endOfFile = END_OF_FILE_NO;

        boolean applAok() {
            return applResult == APPL_AOK;
        }

        boolean applEof() {
            return applResult == APPL_EOF;
        }

        void moveToApplResult(int value) {
            applResult = value;
        }

        void addToZeroGivingApplResult(int addend) {
            applResult = 0 + addend;
        }

        void subtractApplResultFromApplResult() {
            applResult = applResult - applResult;
        }

        int applResult() {
            return applResult;
        }

        boolean endOfFileIsYes() {
            return END_OF_FILE_YES.equals(endOfFile);
        }

        boolean endOfFileIsNo() {
            return END_OF_FILE_NO.equals(endOfFile);
        }

        void moveEndOfFile(String value) {
            Objects.requireNonNull(value, "END-OF-FILE is PIC X(01) and holds a character, never null");
            if (value.length() != 1) {
                throw new IllegalArgumentException("END-OF-FILE is PIC X(01), so it holds exactly one "
                        + "character; '" + value + "' is " + value.length() + ". A wider value would be "
                        + "truncated on the right and a narrower one space-padded, and neither is a "
                        + "move this program performs.");
            }
            endOfFile = value;
        }

        String endOfFileFlag() {
            return endOfFile;
        }
    }
}

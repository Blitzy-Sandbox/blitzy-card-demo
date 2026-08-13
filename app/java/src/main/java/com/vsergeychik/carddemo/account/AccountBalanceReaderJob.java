package com.vsergeychik.carddemo.account;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Objects;

import com.vsergeychik.carddemo.card.CardRepository;
import com.vsergeychik.carddemo.card.CardRepository.BrowseDirection;
import com.vsergeychik.carddemo.card.CardRepository.CardBrowse;
import com.vsergeychik.carddemo.card.CardRepository.CardReadResult;
import com.vsergeychik.carddemo.card.model.CardRecord;
import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.config.BatchConfig;
import com.vsergeychik.carddemo.config.BatchConfig.JobContract;
import com.vsergeychik.carddemo.config.BatchConfig.JobParameterContract;
import com.vsergeychik.carddemo.config.BatchConfig.StepContract;
import com.vsergeychik.carddemo.config.BatchConfig.StopSignal;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@code CBACT02C} - the batch job that reads the card master sequentially and prints every record.
 *
 * <p>Tasklet rather than chunk-oriented, deliberately: the whole step body runs in one pass inside one
 * transaction, so the order in which records are read and lines are emitted is provably the order the
 * single-pass COBOL produces.
 */
@Configuration(AccountBalanceReaderJob.CONFIGURATION_BEAN_NAME)
public class AccountBalanceReaderJob {
    public static final String CONFIGURATION_BEAN_NAME = "accountBalanceReaderJobConfiguration";

    /**
     * The COBOL {@code PROGRAM-ID} this job was translated from: {@value}.
     */
    public static final String PROGRAM_ID = "CBACT02C";

    private static final Log LOG = LogFactory.getLog(AccountBalanceReaderJob.class);

    public static final String JOB_KEY = "account-balance-reader-job";

    public static final String JOB_NAME = "accountBalanceReaderJob";

    /**
     * The single step's name, transcribed from the JCL step it replaces: {@value}.
     */
    public static final String STEP_NAME = "STEP05";

    /**
     * The whole step sequence of {@code app/jcl/READCARD.jcl}: one step, {@link #STEP_NAME}, running
     * {@link #PROGRAM_ID}, ungated.
     */
    public static final List<StepContract> REQUIRED_STEPS =
            List.of(new StepContract(STEP_NAME, PROGRAM_ID, false));

    /**
     * The DD name of the one input this program reads: {@value}.
     */
    public static final String DD_NAME = "CARDFILE";

    /**
     * {@code app/cbl/CBACT02C.cbl:71}: {@code DISPLAY 'START OF EXECUTION OF PROGRAM CBACT02C'}.
     */
    public static final String START_BANNER = "START OF EXECUTION OF PROGRAM CBACT02C";

    /**
     * {@code app/cbl/CBACT02C.cbl:85}: {@code DISPLAY 'END OF EXECUTION OF PROGRAM CBACT02C'}.
     */
    public static final String END_BANNER = "END OF EXECUTION OF PROGRAM CBACT02C";

    /**
     * {@code app/cbl/CBACT02C.cbl:129}: {@code DISPLAY 'ERROR OPENING CARDFILE'}.
     */
    public static final String OPEN_ERROR_TEXT = "ERROR OPENING CARDFILE";

    /**
     * {@code app/cbl/CBACT02C.cbl:110}: {@code DISPLAY 'ERROR READING CARDFILE'}.
     */
    public static final String READ_ERROR_TEXT = "ERROR READING CARDFILE";

    /**
     * {@code app/cbl/CBACT02C.cbl:147}: {@code DISPLAY 'ERROR CLOSING CARDFILE'}.
     */
    public static final String CLOSE_ERROR_TEXT = "ERROR CLOSING CARDFILE";

    /**
     * The value moved into {@code APPL-RESULT} before an operation is attempted: {@value}.
     */
    public static final int APPL_RESULT_ASSUMED_FAILURE = AbendException.RETURN_CODE_ASSUMED_FAILURE;

    /**
     * The value moved into {@code APPL-RESULT} when an operation fails outright: {@value}.
     */
    public static final int APPL_RESULT_FATAL = AbendException.RETURN_CODE_IO_ERROR;

    /**
     * {@code END-OF-FILE}'s initial value, {@code 'N'} - {@code app/cbl/CBACT02C.cbl:65}.
     */
    public static final char END_OF_FILE_NO = 'N';

    /**
     * The only value ever moved into {@code END-OF-FILE}, {@code 'Y'} - {@code app/cbl/CBACT02C.cbl:108}.
     */
    public static final char END_OF_FILE_YES = 'Y';

    private static final char PERMANENT_ERROR_FEEDBACK_CODE = 0;

    /**
     * The two-character status reported for a failure that has no two-character equivalent: {@code '9'}
     * followed by {@link #PERMANENT_ERROR_FEEDBACK_CODE}.
     *
     * <p>Handing this value to {@link FileStatus#toDisplayLine(String)} therefore emits
     * {@code FILE STATUS IS: NNNN9000}, which is exactly the line the COBOL would have written.
     */
    public static final String PERMANENT_ERROR_STATUS = "9" + PERMANENT_ERROR_FEEDBACK_CODE;

    /**
     * The key a sequential read of the card master starts from: {@value CardRecord#CARD_NUM_LENGTH} spaces.
     */
    public static final String LOWEST_CARD_NUMBER_KEY = " ".repeat(CardRecord.CARD_NUM_LENGTH);

    private final BatchConfig batchConfig;

    private final CardRepository cardRepository;

    private final Charset datasetCharset;

    private final ObjectProvider<SysoutSink> sysoutSinkProvider;

    public AccountBalanceReaderJob(
            BatchConfig batchConfig,
            CardRepository cardRepository,
            @Qualifier(CardRepository.DATASET_CHARSET_BEAN_NAME) Charset datasetCharset,
            ObjectProvider<SysoutSink> sysoutSinkProvider) {
        this.batchConfig = Objects.requireNonNull(batchConfig,
                "The batch scaffolding is required: this job owns no job repository, no transaction "
                        + "manager and no listeners of its own, and receives all three through it");
        this.cardRepository = Objects.requireNonNull(cardRepository,
                "The card repository is required: CBACT02C reads the card master and nothing else, "
                        + "however this class is named");
        this.datasetCharset = Objects.requireNonNull(datasetCharset,
                "A dataset charset is required: the record image DISPLAY writes is bytes in a specific "
                        + "code page, so the code page is stated explicitly and never taken from the "
                        + "platform");
        this.sysoutSinkProvider = Objects.requireNonNull(sysoutSinkProvider,
                "A sink provider is required: SYSOUT is a seam so that a parity case can capture the "
                        + "exact line sequence this job emits");
        this.batchConfig.requireSameDataset(JOB_KEY, DD_NAME, CardRepository.BASE_DD_NAME);
    }

    /**
     * The runnable job: one tasklet step, {@code STEP05}, and nothing else.
     *
     * <p>Batch job launching is switched off in both profiles and this class adds no scheduler, no start-up
     * runner and no timer, so the job runs only when something deliberately launches it - which is how a
     * job ran on the mainframe, when JCL submitted an {@code EXEC PGM=} step and not before.
     *
     * @return the job, bound to the shared job repository and carrying the shared return-code listener;
     *     never {@code null}
     * @throws IllegalStateException if the configured contract for {@link #JOB_KEY} is missing, declares a
     *     step other than {@link #STEP_NAME}, or declares a job parameter
     */
    @Bean
    public Job accountBalanceReaderJob() {
        return batchConfig.job(JOB_NAME)
                .start(readCardfileStep())
                .build();
    }

    /**
     * The one step, a tasklet named after the JCL step it replaces.
     *
     * @return a fresh step, bound to the shared repository, the shared listener and the module's
     *     transaction manager; never {@code null}
     * @throws IllegalStateException if the configured contract is missing or disagrees with the JCL
     */
    public Step readCardfileStep() {
        JobContract contract = requireContract();
        return batchConfig.taskletStep(contract.step(STEP_NAME).name(), readCardfileTasklet()).build();
    }

    public Tasklet readCardfileTasklet() {
        return (contribution, chunkContext) -> {
            int recordsRead = execute(sysoutSink(), StopSignal.of(chunkContext));
            for (int recorded = 0; recorded < recordsRead; recorded++) {
                contribution.incrementReadCount();
            }
            return RepeatStatus.FINISHED;
        };
    }

    /**
     * The dataset the {@link #DD_NAME} DD resolves to for this job, as configuration declares it.
     *
     * @return the configured dataset name for {@link #DD_NAME}; never blank
     * @throws IllegalStateException if neither this job nor the global catalogue declares {@link #DD_NAME}
     */
    public String cardfileDatasetName() {
        return batchConfig.datasetBinding(JOB_KEY, DD_NAME).dsname();
    }

    private JobContract requireContract() {
        JobContract contract = batchConfig.contract(JOB_KEY);
        batchConfig.requireSteps(JOB_KEY, REQUIRED_STEPS, "app/jcl/READCARD.jcl:22");
        List<JobParameterContract> parameters = contract.parameters();
        if (!parameters.isEmpty()) {
            throw new IllegalStateException("carddemo.jobs." + JOB_KEY + " declares "
                    + parameters.size() + " job parameter(s) - "
                    + parameters.stream().map(JobParameterContract::name).toList()
                    + " - but app/jcl/READCARD.jcl:22 is a bare //STEP05 EXEC PGM=" + PROGRAM_ID
                    + " with no PARM, and " + PROGRAM_ID + " declares no LINKAGE SECTION to receive "
                    + "one into. Remove the parameter: the only step in this estate carrying a PARM "
                    + "belongs to a different job.");
        }
        return contract;
    }

    /**
     * Runs the whole program once, writing every {@code DISPLAY} to {@code sysout}.
     *
     * @param sysout where the {@code DISPLAY} output goes; never {@code null}
     * @return the number of card records read and displayed; {@code 0} for an empty dataset
     * @throws NullPointerException if {@code sysout} is {@code null}
     * @throws AbendException if the open, a read or the close fails, carrying {@link #APPL_RESULT_FATAL} as
     *     the {@code RETURN-CODE} exactly as {@code 9999-ABEND-PROGRAM} does
     */
    public int execute(SysoutSink sysout) {
        return execute(sysout, StopSignal.RUNNING);
    }

    /**
     * Runs the program, yielding to the given stop signal between records.
     *
     * @param sysout where the {@code DISPLAY} output goes; never {@code null}
     * @param stopSignal the between-record cancellation probe; {@link StopSignal#RUNNING} for a caller
     *     outside a step; never {@code null}
     * @return the number of card records read and displayed; {@code 0} for an empty dataset
     * @throws NullPointerException if {@code sysout} or {@code stopSignal} is {@code null}
     * @throws AbendException if the open, a read or the close fails, carrying {@link #APPL_RESULT_FATAL} as
     *     the {@code RETURN-CODE} exactly as {@code 9999-ABEND-PROGRAM} does
     * @throws BatchConfig.StopRequestedException if the step is asked to stop, which abandons the pass
     *     between records
     */
    public int execute(SysoutSink sysout, StopSignal stopSignal) {
        Objects.requireNonNull(sysout, "A SYSOUT sink is required to run " + PROGRAM_ID
                + "; every one of its DISPLAY statements writes through it");
        Objects.requireNonNull(stopSignal, "A stop signal is required; pass StopSignal.RUNNING outside a "
                + "step, which is what the single-argument overload does");
        return new CardfileRead(cardfileRepository(), datasetCharset, sysout).execute(stopSignal);
    }

    private CardRepository cardfileRepository() {
        return cardRepository.addressing(batchConfig.datasetBinding(JOB_KEY, DD_NAME), DD_NAME);
    }

    /**
     * One run of {@code CBACT02C}, holding that run's {@code WORKING-STORAGE}.
     */
    private static final class CardfileRead {
        private final CardRepository cardRepository;

        private final Charset datasetCharset;

        private final SysoutSink sysout;

        private int applResult;

        private char endOfFile = END_OF_FILE_NO;

        private int recordsRead;

        private String cardfileStatus;

        private String ioStatus;

        private CardBrowse cardfile;

        private CardRecord cardRecord;

        private String cardRecordImage;

        private RuntimeException datasetRefusal;

        private CardfileRead(CardRepository cardRepository, Charset datasetCharset,
                             SysoutSink sysout) {
            this.cardRepository = cardRepository;
            this.datasetCharset = datasetCharset;
            this.sysout = sysout;
        }

        private int execute(StopSignal stopSignal) {
            sysout.write(START_BANNER);
            openCardfile();
            try {
                executeAfterOpen(stopSignal);
            } finally {
                releaseBrowse();
            }
            return recordsRead;
        }

        private void executeAfterOpen(StopSignal stopSignal) {
            while (!endOfFile()) {
                stopSignal.checkStopRequested();

                // The COBOL states the guard redundantly; restating it in Java would add a branch nothing
                // could ever take, and an unreachable branch is worse than no branch - it reads as a case
                // somebody forgot to cover.
                readNextCardfileRecord();

                if (!endOfFile()) {
                    recordsRead++;

                    sysout.write(cardRecordImage);
                }
            }

            closeCardfile();
            sysout.write(END_BANNER);
        }

        private void releaseBrowse() {
            if (cardfile == null || closeIssued) {
                return;
            }
            try {
                cardfile.endBrowse();
            } catch (RuntimeException cleanupFailure) {
                LOG.warn("Ending the " + DD_NAME + " browse of " + PROGRAM_ID + " after an incomplete run "
                        + "failed - " + cleanupFailure.getClass().getName()
                        + ". The run's own outcome is reported unchanged, because the run's own failure is "
                        + "the one that matters.");
            }
        }

        private boolean endOfFile() {
            return endOfFile == END_OF_FILE_YES;
        }

        private boolean closeIssued;

        private void openCardfile() {
            applResult = APPL_RESULT_ASSUMED_FAILURE;

            cardfileStatus = openCardfileBrowse();

            if (FileStatus.OK.equals(cardfileStatus)) {
                applResult = FileStatus.APPL_AOK;
            } else {
                applResult = APPL_RESULT_FATAL;
            }

            if (applResult == FileStatus.APPL_AOK) {
                return;
            }
            throw reportFileErrorAndAbend(OPEN_ERROR_TEXT);
        }

        private String openCardfileBrowse() {
            try {
                cardfile = cardRepository.openBrowse(LOWEST_CARD_NUMBER_KEY, BrowseDirection.FORWARD);
                return FileStatus.batchStatusOfCicsResp(cardfile.openResp())
                        .orElse(PERMANENT_ERROR_STATUS);
            } catch (RuntimeException refused) {
                datasetRefusal = refused;
                return PERMANENT_ERROR_STATUS;
            }
        }

        private void readNextCardfileRecord() {
            CardReadResult result = cardfile.readNext();

            if (result.isRecordReturned()) {
                cardRecord = result.requireRecord();
                cardRecordImage = result.requireStoredImage();
            }

            cardfileStatus = result.batchStatus().orElse(PERMANENT_ERROR_STATUS);

            if (FileStatus.OK.equals(cardfileStatus)) {
                applResult = FileStatus.APPL_AOK;
            } else if (FileStatus.END_OF_FILE.equals(cardfileStatus)) {
                applResult = FileStatus.APPL_EOF;
            } else {
                applResult = APPL_RESULT_FATAL;
            }

            if (applResult == FileStatus.APPL_AOK) {
                return;
            }
            if (applResult == FileStatus.APPL_EOF) {
                endOfFile = END_OF_FILE_YES;
                return;
            }
            throw reportFileErrorAndAbend(READ_ERROR_TEXT);
        }

        private void closeCardfile() {
            applResult = APPL_RESULT_ASSUMED_FAILURE;

            cardfileStatus = closeCardfileBrowse();

            if (FileStatus.OK.equals(cardfileStatus)) {
                applResult -= applResult;
            } else {
                applResult = APPL_RESULT_FATAL;
            }

            if (applResult == FileStatus.APPL_AOK) {
                return;
            }
            throw reportFileErrorAndAbend(CLOSE_ERROR_TEXT);
        }

        private String closeCardfileBrowse() {
            closeIssued = true;
            try {
                cardfile.endBrowse();
                return FileStatus.OK;
            } catch (RuntimeException refused) {
                datasetRefusal = refused;
                return PERMANENT_ERROR_STATUS;
            }
        }

        private AbendException reportFileErrorAndAbend(String errorText) {
            sysout.write(errorText);
            ioStatus = cardfileStatus;
            displayIoStatus();
            return abendProgram(errorText);
        }

        private void displayIoStatus() {
            sysout.write(FileStatus.toDisplayLine(ioStatus));
        }

        private AbendException abendProgram(String reason) {
            sysout.write(AbendException.ABEND_DISPLAY_TEXT);
            return AbendException.standard(PROGRAM_ID, applResult,
                    reason + " " + FileStatus.toDisplayLine(ioStatus), datasetRefusal);
        }
    }

    /**
     * The sink this job writes its {@code DISPLAY} output to: an application-supplied one where the context
     * publishes it, and {@link #standardOutput(Charset)} over the active dataset code page otherwise.
     *
     * @return the sink to use for the next run; never {@code null}
     */
    public SysoutSink sysoutSink() {
        return sysoutSinkProvider.getIfAvailable(() -> standardOutput(datasetCharset));
    }

    /**
     * The default sink: the job's own standard output stream, written verbatim in the code page the card
     * master is read in.
     *
     * <p>On the mainframe the record's bytes pass from the KSDS to the spool unchanged; encoding them in
     * whatever {@code file.encoding} happens to be would corrupt every byte outside the invariant ASCII
     * range and would make the emitted line depend on the JVM rather than on the program.
     *
     * @param charset the code page to encode each line in - the active dataset code page; must not be
     *     {@code null}
     * @return a sink over the standard output stream; never {@code null}
     * @throws NullPointerException if {@code charset} is {@code null}
     */
    public static SysoutSink standardOutput(Charset charset) {
        Objects.requireNonNull(charset, "A code page is required for SYSOUT: a displayed record is the "
                + "dataset's own bytes, and the platform default is never assumed");
        return new PrintStreamSysoutSink(
                new PrintStream(new FileOutputStream(FileDescriptor.out), true, charset));
    }

    @FunctionalInterface
    public interface SysoutSink {
        void write(String line);
    }

    public record PrintStreamSysoutSink(PrintStream stream) implements SysoutSink {
        public PrintStreamSysoutSink {
            Objects.requireNonNull(stream, "A print stream is required to emit SYSOUT lines");
        }

        /**
         * Writes the line and terminates it, which is what one {@code DISPLAY} produces.
         *
         * @param line the line; never {@code null}
         * @throws NullPointerException if {@code line} is {@code null}
         */
        @Override
        public void write(String line) {
            Objects.requireNonNull(line, "A DISPLAY never writes an absent line");
            stream.println(line);
        }
    }
}

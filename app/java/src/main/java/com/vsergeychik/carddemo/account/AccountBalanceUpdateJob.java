package com.vsergeychik.carddemo.account;

import com.vsergeychik.carddemo.card.CardXrefRepository;
import com.vsergeychik.carddemo.card.CardXrefRepository.BrowseCursor;
import com.vsergeychik.carddemo.card.CardXrefRepository.ReadResult;
import com.vsergeychik.carddemo.card.model.CardXrefRecord;
import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.config.BatchConfig;
import com.vsergeychik.carddemo.config.BatchConfig.JobContract;
import com.vsergeychik.carddemo.config.BatchConfig.StepContract;
import com.vsergeychik.carddemo.config.BatchConfig.StopSignal;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Objects;

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
 * The Java translation of {@code app/cbl/CBACT03C.cbl} - the CardDemo batch program that reads the card
 * cross-reference file and prints it - assembled as the single-step Spring Batch job that
 * {@code app/jcl/READXREF.jcl} runs.
 *
 * <p>The source states its own purpose in one line at {@code app/cbl/CBACT03C.cbl:5} -
 * {@code Function : Read and print account cross reference data file.} That is exactly and only what this
 * class does.
 */
@Configuration(AccountBalanceUpdateJob.CONFIGURATION_BEAN_NAME)
public class AccountBalanceUpdateJob {
    public static final String CONFIGURATION_BEAN_NAME = "accountBalanceUpdateJobConfiguration";

    /**
     * The configuration key of this job's contract under {@code carddemo.jobs}, spelled exactly as
     * {@code application.yml} declares it.
     */
    public static final String JOB_KEY = "account-balance-update-job";

    public static final String JOB_NAME = "accountBalanceUpdateJob";

    public static final String STEP_BEAN_NAME = "accountBalanceUpdateStep";

    /**
     * The COBOL {@code PROGRAM-ID} this job translates: {@code app/cbl/CBACT03C.cbl:23}.
     */
    public static final String PROGRAM_NAME = "CBACT03C";

    private static final Log LOG = LogFactory.getLog(AccountBalanceUpdateJob.class);

    /**
     * The step name, transcribed from {@code app/jcl/READXREF.jcl}'s only step.
     */
    public static final String STEP_NAME = "STEP05";

    /**
     * The whole step sequence of {@code app/jcl/READXREF.jcl}: one step, {@link #STEP_NAME}, running
     * {@link #PROGRAM_NAME}, ungated.
     */
    public static final List<StepContract> REQUIRED_STEPS =
            List.of(new StepContract(STEP_NAME, PROGRAM_NAME, false));

    /**
     * The DD name of this job's one input dataset, as {@code app/jcl/READXREF.jcl} declares it and as
     * {@code app/cbl/CBACT03C.cbl:29} assigns it - {@code SELECT XREFFILE-FILE ASSIGN TO XREFFILE}.
     */
    public static final String XREFFILE_DD_NAME = "XREFFILE";

    public static final String DATASET_CHARSET_BEAN_NAME =
            CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME;

    /**
     * {@code app/cbl/CBACT03C.cbl:71} - the first line of output.
     */
    public static final String START_OF_EXECUTION = "START OF EXECUTION OF PROGRAM CBACT03C";

    /**
     * {@code app/cbl/CBACT03C.cbl:85} - the last line of output, emitted after the close.
     */
    public static final String END_OF_EXECUTION = "END OF EXECUTION OF PROGRAM CBACT03C";

    /**
     * {@code app/cbl/CBACT03C.cbl:129} - the open paragraph's failure message.
     */
    public static final String ERROR_OPENING_XREFFILE = "ERROR OPENING XREFFILE";

    /**
     * {@code app/cbl/CBACT03C.cbl:110} - the read paragraph's failure message.
     */
    public static final String ERROR_READING_XREFFILE = "ERROR READING XREFFILE";

    /**
     * {@code app/cbl/CBACT03C.cbl:147} - the close paragraph's failure message.
     */
    public static final String ERROR_CLOSING_XREFFILE = "ERROR CLOSING XREFFILE";

    /**
     * How many lines each successfully read record produces: two, from {@code app/cbl/CBACT03C.cbl:96} and
     * {@code :78}.
     */
    public static final int DISPLAYS_PER_RECORD = 2;

    /**
     * How many lines an execution emits regardless of how many records it reads: the start banner at
     * {@code app/cbl/CBACT03C.cbl:71} and the end banner at {@code :85}.
     */
    public static final int BANNER_LINES = 2;

    /**
     * The value {@code 0000-XREFFILE-OPEN} seeds {@code APPL-RESULT} with at
     * {@code app/cbl/CBACT03C.cbl:119} ({@code MOVE 8 TO APPL-RESULT}) and that {@code 9000-XREFFILE-CLOSE}
     * seeds it with at {@code :137} ({@code ADD 8 TO ZERO GIVING APPL-RESULT}).
     */
    public static final int APPL_RESULT_ASSUMED_FAILURE = AbendException.RETURN_CODE_ASSUMED_FAILURE;

    /**
     * The value all three paragraphs move for a status other than the ones they name -
     * {@code app/cbl/CBACT03C.cbl:101}, {@code :124} and {@code :142} - and therefore the
     * {@code RETURN-CODE} this program's abend carries.
     */
    public static final int APPL_RESULT_FATAL = CardXrefRepository.APPL_RESULT_FATAL;

    /**
     * {@code END-OF-FILE} while records remain: the {@code VALUE 'N'} the field is declared with.
     */
    public static final String NOT_AT_END_OF_FILE = "N";

    /**
     * {@code END-OF-FILE} once the read has reported {@code '10'}: the {@code 'Y'} of {@code :108}.
     */
    public static final String AT_END_OF_FILE = "Y";

    private static final String RECORD_DISPLAY_SUBJECT =
            "the CARD-XREF-RECORD display image of app/cbl/CBACT03C.cbl:78 and :96";

    private static final char SYSOUT_LINE_TERMINATOR = '\n';

    // Every one is final and set by the constructor, and there is no static mutable state anywhere in this
    // file: the COBOL's WORKING-STORAGE becomes locals of execute(SysoutSink), never fields, so two
    // concurrent executions cannot see each other's END-OF-FILE flag or record area.

    private final BatchConfig batchConfig;

    private final CardXrefRepository cardXrefRepository;

    private final FixedWidthCodec codec;

    private final ObjectProvider<SysoutSink> sysoutSinkProvider;

    private final String xrefFileDatasetName;

    private final String stepName;

    /**
     * Wires the job and validates, at startup, that the configured contract still says what
     * {@code app/jcl/READXREF.jcl} says.
     *
     * @param batchConfig the batch seam supplying job and step builders and the job contracts; never
     *     {@code null}
     * @param cardXrefRepository the cross-reference repository, browsed in base-key order; never
     *     {@code null}
     * @param datasetCharset the module's active dataset code page, injected by the bean name
     *     {@link #DATASET_CHARSET_BEAN_NAME} so it is stated explicitly rather than taken from the platform;
     *     never {@code null}
     * @param sysoutSinkProvider provider for a deployment-supplied {@code SYSOUT} destination; never
     *     {@code null}, though it may resolve to nothing, in which case {@link #defaultSysoutSink()} is used
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalStateException if the configured contract names a different program, declares a step
     *     named anything but {@link #STEP_NAME}, gates that step behind a preceding step's exit code, declares
     *     any job parameter
     */
    public AccountBalanceUpdateJob(
            BatchConfig batchConfig,
            CardXrefRepository cardXrefRepository,
            @Qualifier(DATASET_CHARSET_BEAN_NAME) Charset datasetCharset,
            ObjectProvider<SysoutSink> sysoutSinkProvider) {
        this.batchConfig = Objects.requireNonNull(batchConfig, "The batch configuration seam is "
                + "required: it supplies the job repository, the transaction manager and the abend "
                + "listener, so no job class assembles Spring Batch plumbing of its own");
        this.cardXrefRepository = Objects.requireNonNull(cardXrefRepository, "The card cross-reference "
                + "repository is required: app/cbl/CBACT03C.cbl:29 assigns its one file to DD "
                + XREFFILE_DD_NAME + ", and this job reads nothing else");
        this.codec = new FixedWidthCodec(Objects.requireNonNull(datasetCharset, "A dataset charset is "
                + "required: the record this program displays is 50 bytes of fixed-width data, so the "
                + "code page is stated explicitly and never taken from the platform"));
        this.sysoutSinkProvider = Objects.requireNonNull(sysoutSinkProvider, "A SysoutSink provider is "
                + "required: DISPLAY output is emitted through an injected sink so it can be captured "
                + "and compared, never written straight to a stream from the program body");

        JobContract contract = batchConfig.contract(JOB_KEY);
        requireProgram(contract.program(), "carddemo.jobs." + JOB_KEY + ".program");
        requireNoJobParameters(contract);

        StepContract step = contract.step(STEP_NAME);
        requireProgram(step.program(), "carddemo.jobs." + JOB_KEY + ".steps[" + STEP_NAME
                + "].program");
        requireUngatedStep(step);
        // READXREF.jcl has exactly one EXEC, so the sequence is compared whole - one comparison covering
        // the cardinality and order the field-level checks are structurally blind to.
        batchConfig.requireSteps(JOB_KEY, REQUIRED_STEPS, "app/jcl/READXREF.jcl:22");
        this.stepName = step.name();

        var xrefFile = batchConfig.datasetBinding(JOB_KEY, XREFFILE_DD_NAME);
        requireCopybookRecordLength(xrefFile.recordLength());
        this.xrefFileDatasetName = requireUsableDatasetName(xrefFile.dsname());
        batchConfig.requireSameDataset(JOB_KEY, XREFFILE_DD_NAME, CardXrefRepository.BASE_DD_NAME);
    }

    private static void requireProgram(String configured, String key) {
        if (!PROGRAM_NAME.equals(configured)) {
            throw new IllegalStateException(key + " is '" + configured + "', but "
                    + AccountBalanceUpdateJob.class.getSimpleName() + " translates " + PROGRAM_NAME
                    + " (app/cbl/CBACT03C.cbl), which app/jcl/READXREF.jcl runs as its only step. "
                    + "Correct the configured program name; do not repoint this class.");
        }
    }

    private static void requireNoJobParameters(JobContract contract) {
        if (!contract.parameters().isEmpty()) {
            throw new IllegalStateException("carddemo.jobs." + JOB_KEY + ".parameters declares "
                    + contract.parameters().size() + " parameter(s), but app/jcl/READXREF.jcl:22 runs "
                    + PROGRAM_NAME + " with no PARM at all and the program declares no LINKAGE "
                    + "SECTION. The empty list is the contract; declare no parameter here.");
        }
    }

    private static void requireUngatedStep(StepContract step) {
        if (step.requirePrecedingExitCodeZero()) {
            throw new IllegalStateException("carddemo.jobs." + JOB_KEY + ".steps[" + step.name()
                    + "].require-preceding-exit-code-zero is true, but app/jcl/READXREF.jcl declares "
                    + "no COND on its only step. Gating the first step of a single-step job behind a "
                    + "preceding step's exit code would stop it running at all.");
        }
    }

    /**
     * Requires the configured record width to agree with {@code app/cpy/CVACT03Y.cpy}.
     *
     * @param configuredRecordLength the width configuration declares for the DD
     * @throws IllegalStateException if it is not {@value CardXrefRecord#RECORD_LENGTH}
     */
    private static void requireCopybookRecordLength(int configuredRecordLength) {
        if (configuredRecordLength != CardXrefRecord.RECORD_LENGTH) {
            throw new IllegalStateException("carddemo.datasets." + XREFFILE_DD_NAME
                    + ".record-length is " + configuredRecordLength + ", but app/cpy/CVACT03Y.cpy "
                    + "declares (RECLN " + CardXrefRecord.RECORD_LENGTH + ") and "
                    + PROGRAM_NAME + " DISPLAYs the whole record area. The 36-byte form of "
                    + "app/data/ASCII/cardxref.txt is the fixture omitting its trailing FILLER X("
                    + CardXrefRecord.FILLER_LENGTH + ") and is widened to "
                    + CardXrefRecord.RECORD_LENGTH + " before use, never configured down to.");
        }
    }

    private static String requireUsableDatasetName(String dsname) {
        if (dsname == null || dsname.isBlank()) {
            throw new IllegalStateException("carddemo.datasets." + XREFFILE_DD_NAME + ".dsname is "
                    + (dsname == null ? "not declared" : "blank") + ", so DD " + XREFFILE_DD_NAME
                    + " names no dataset for " + PROGRAM_NAME + " to read. app/jcl/READXREF.jcl binds "
                    + "it to the card cross-reference cluster; supply that dataset, or the "
                    + "environment variable the binding defers to.");
        }
        return dsname;
    }

    /**
     * The job {@code app/jcl/READXREF.jcl} runs: one step, no parameters, no gating.
     *
     * @return the job, named {@link #JOB_NAME} in the batch metadata
     */
    @Bean(JOB_NAME)
    public Job accountBalanceUpdateJob() {
        return batchConfig.job(JOB_NAME)
                .start(accountBalanceUpdateStep())
                .build();
    }

    /**
     * The step, named for {@code app/jcl/READXREF.jcl}'s {@code //STEP05}.
     *
     * @return the single step of this job
     */
    @Bean(STEP_BEAN_NAME)
    public Step accountBalanceUpdateStep() {
        return batchConfig.taskletStep(stepName, accountBalanceUpdateTasklet()).build();
    }

    /**
     * The step body: a thin adapter that resolves the {@code SYSOUT} destination and runs the program.
     *
     * @return a tasklet that runs the program exactly once per step execution
     */
    public Tasklet accountBalanceUpdateTasklet() {
        return (contribution, chunkContext) -> {
            ExecutionSummary summary = execute(resolveSysoutSink(), StopSignal.of(chunkContext));
            for (int recorded = 0; recorded < summary.recordsRead(); recorded++) {
                contribution.incrementReadCount();
            }
            return RepeatStatus.FINISHED;
        };
    }

    /**
     * Runs {@code CBACT03C}: read the card cross-reference file from beginning to end and display every
     * record, twice.
     *
     * <p>The guard at {@code :75} is redundant - the {@code PERFORM UNTIL} condition already establishes
     * it, and {@code END-OF-FILE} holds only {@code 'N'} or {@code 'Y'} - so its false path is unreachable.
     *
     * @param sysout where the {@code DISPLAY} lines go; never {@code null}
     * @return the return code and the number of records read
     * @throws NullPointerException if {@code sysout} is {@code null}
     * @throws AbendException if the open, a read or the close reports a file status the program does not
     *     name, carrying {@code RETURN-CODE} {@link #APPL_RESULT_FATAL}, {@code ABCODE} 999 and {@code TIMING}
     *     0
     */
    public ExecutionSummary execute(SysoutSink sysout) {
        return execute(sysout, StopSignal.RUNNING);
    }

    /**
     * Runs {@code CBACT03C}, yielding to the given stop signal between records.
     *
     * @param sysout where the {@code DISPLAY} lines go; never {@code null}
     * @param stopSignal the between-record cancellation probe; {@link StopSignal#RUNNING} for a caller
     *     outside a step; never {@code null}
     * @return the return code and the number of records read
     * @throws NullPointerException if {@code sysout} or {@code stopSignal} is {@code null}
     * @throws AbendException if the open, a read or the close reports a file status the program does not
     *     name
     * @throws BatchConfig.StopRequestedException if the step is asked to stop, which abandons the pass
     *     between records
     */
    public ExecutionSummary execute(SysoutSink sysout, StopSignal stopSignal) {
        Objects.requireNonNull(sysout, "A SYSOUT sink is required to run " + PROGRAM_NAME
                + ": the program's observable output is its DISPLAY lines, so there is nothing to run "
                + "without somewhere to put them");
        Objects.requireNonNull(stopSignal, "A stop signal is required; pass StopSignal.RUNNING outside a "
                + "step, which is what the single-argument overload does");

        sysout.display(START_OF_EXECUTION);

        BrowseCursor cursor = openXrefFile(sysout);

        boolean closeIssued = false;

        try {
            String endOfFile = NOT_AT_END_OF_FILE;
            CardXrefRecord recordArea = null;
            String recordAreaImage = null;
            int recordsRead = 0;

            while (!AT_END_OF_FILE.equals(endOfFile)) {
                stopSignal.checkStopRequested();

                if (NOT_AT_END_OF_FILE.equals(endOfFile)) {
                    GetNextOutcome next = getNextXrefRecord(cursor, sysout);
                    endOfFile = next.endOfFile();
                    if (next.record() != null) {
                        recordArea = next.record();
                        recordAreaImage = next.storedImage();
                        recordsRead++;
                    }

                    if (NOT_AT_END_OF_FILE.equals(endOfFile)) {
                        sysout.display(recordAreaImage);
                    }
                }
            }

            closeIssued = true;
            closeXrefFile(cursor, sysout);

            sysout.display(END_OF_EXECUTION);

            // :87 GOBACK. - RETURN-CODE is never moved, so the program returns zero.
            return new ExecutionSummary(AbendException.RETURN_CODE_OK, recordsRead);
        } finally {
            if (!closeIssued) {
                releaseCursor(cursor);
            }
        }
    }

    private static void releaseCursor(BrowseCursor cursor) {
        try {
            cursor.closeBrowse();
        } catch (RuntimeException cleanupFailure) {
            LOG.warn("Closing the " + XREFFILE_DD_NAME + " browse of " + PROGRAM_NAME + " after an "
                    + "incomplete run failed - " + cleanupFailure.getClass().getName()
                    + ". The run's own outcome is reported unchanged, because the run's own failure is the "
                    + "one that matters.");
        }
    }

    private GetNextOutcome getNextXrefRecord(BrowseCursor cursor, SysoutSink sysout) {
        ReadResult read = cursor.readNext();
        String status = read.status();

        int applResult;
        CardXrefRecord record = null;
        String storedImage = null;
        if (FileStatus.isOk(status)) {
            applResult = FileStatus.APPL_AOK;
            record = read.record().orElseThrow(() -> new IllegalStateException("A read of DD "
                    + XREFFILE_DD_NAME + " reported file status " + FileStatus.toStatusImage(status)
                    + " and carried no record. The two are contradictory: a successful READ leaves the "
                    + "record area populated, which is why " + PROGRAM_NAME + ":96 DISPLAYs it."));
            storedImage = read.requireStoredImage();
            sysout.display(storedImage);
        } else if (FileStatus.isEndOfFile(status)) {
            applResult = FileStatus.APPL_EOF;
        } else {
            applResult = APPL_RESULT_FATAL;
        }

        if (applAok(applResult)) {
            return new GetNextOutcome(NOT_AT_END_OF_FILE, record, storedImage);
        }
        if (applEof(applResult)) {
            return new GetNextOutcome(AT_END_OF_FILE, null, null);
        }
        reportIoFailure(sysout, ERROR_READING_XREFFILE, status);
        throw abendProgram(sysout, ERROR_READING_XREFFILE, status);
    }

    private CardXrefRepository xrefFileRepository() {
        return cardXrefRepository.addressing(
                batchConfig.datasetBinding(JOB_KEY, XREFFILE_DD_NAME), XREFFILE_DD_NAME,
                null, CardXrefRepository.ALTERNATE_INDEX_BATCH_DD_NAME);
    }

    private BrowseCursor openXrefFile(SysoutSink sysout) {
        // :119 MOVE 8 TO APPL-RESULT. - dead, and preserved.
        int applResult = APPL_RESULT_ASSUMED_FAILURE;

        BrowseCursor cursor = xrefFileRepository().openBrowse();
        String status = cursor.openStatus();

        if (FileStatus.isOk(status)) {
            applResult = FileStatus.APPL_AOK;
        } else {
            applResult = APPL_RESULT_FATAL;
        }

        if (!applAok(applResult)) {
            reportIoFailure(sysout, ERROR_OPENING_XREFFILE, status);
            throw abendProgram(sysout, ERROR_OPENING_XREFFILE, status);
        }
        return cursor;
    }

    private void closeXrefFile(BrowseCursor cursor, SysoutSink sysout) {
        // :137 ADD 8 TO ZERO GIVING APPL-RESULT. - dead, and preserved.
        int applResult = APPL_RESULT_ASSUMED_FAILURE;

        String status = cursor.closeBrowse();

        if (FileStatus.isOk(status)) {
            applResult -= applResult;
        } else {
            applResult = APPL_RESULT_FATAL;
        }

        if (!applAok(applResult)) {
            reportIoFailure(sysout, ERROR_CLOSING_XREFFILE, status);
            throw abendProgram(sysout, ERROR_CLOSING_XREFFILE, status);
        }
    }

    private static boolean applAok(int applResult) {
        return applResult == FileStatus.APPL_AOK;
    }

    private static boolean applEof(int applResult) {
        return applResult == FileStatus.APPL_EOF;
    }

    // Shared by all three paragraphs above, exactly as the COBOL shares them.

    private static void reportIoFailure(SysoutSink sysout, String message, String status) {
        sysout.display(message);
        sysout.display(FileStatus.toDisplayLine(status));
    }

    private static AbendException abendProgram(SysoutSink sysout, String message, String status) {
        sysout.display(AbendException.ABEND_DISPLAY_TEXT);
        return AbendException.standard(PROGRAM_NAME, APPL_RESULT_FATAL,
                message + " - " + FileStatus.toDisplayLine(status));
    }

    // DISPLAY CARD-XREF-RECORD - the record area rendered exactly as the 50 bytes it occupies.

    /**
     * Renders a cross-reference record as the {@value CardXrefRecord#RECORD_LENGTH}-character image it
     * encodes to.
     *
     * <p>The encode path is used rather than formatting the three fields here, so the copybook's padding
     * rules live in exactly one place: the card number left justified and space-padded, both identifiers
     * right justified and zero-filled, and the {@code FILLER} span present rather than dropped.
     *
     * @param record the record to render; never {@code null}
     * @return exactly {@value CardXrefRecord#RECORD_LENGTH} characters
     * @throws NullPointerException if {@code record} is {@code null}
     */
    public String displayImageOf(CardXrefRecord record) {
        Objects.requireNonNull(record, "A record is required to render the CARD-XREF-RECORD display "
                + "image; app/cbl/CBACT03C.cbl:78 and :96 display the record area, which a successful "
                + "READ has always populated");
        return codec.decodeImage(record.encode(codec), RECORD_DISPLAY_SUBJECT);
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
     * @return a sink writing to that stream
     * @throws NullPointerException if {@code destination} is {@code null}
     */
    public SysoutSink sysoutSinkTo(OutputStream destination) {
        return new StreamSysoutSink(destination, codec);
    }

    /**
     * The dataset DD {@link #XREFFILE_DD_NAME} is bound to, as {@code carddemo.datasets.XREFFILE} declares
     * it.
     *
     * @return the configured dataset name; never {@code null}, never blank
     */
    public String xrefFileDatasetName() {
        return xrefFileDatasetName;
    }

    /**
     * The step name this job's configured contract declares, which the constructor has already confirmed is
     * {@link #STEP_NAME}.
     *
     * @return the step name; never {@code null}
     */
    public String stepName() {
        return stepName;
    }

    @FunctionalInterface
    public interface SysoutSink {
        void display(String line);
    }

    /**
     * The default {@link SysoutSink}: bytes in the configured code page, one line feed, flushed.
     */
    private static final class StreamSysoutSink implements SysoutSink {
        private static final String SYSOUT_SUBJECT = "a SYSOUT display line of " + PROGRAM_NAME;

        private final OutputStream destination;

        private final FixedWidthCodec codec;

        private StreamSysoutSink(OutputStream destination, FixedWidthCodec codec) {
            this.destination = Objects.requireNonNull(destination, "A destination stream is required");
            this.codec = Objects.requireNonNull(codec, "A codec is required: a SYSOUT line is written "
                    + "in a named code page, never in the platform default");
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
            Objects.requireNonNull(line, "A line is required to display; " + PROGRAM_NAME + " never "
                    + "displays nothing");
            byte[] encoded = codec.encodeImage(line + SYSOUT_LINE_TERMINATOR, SYSOUT_SUBJECT);
            try {
                destination.write(encoded);
                destination.flush();
            } catch (IOException refused) {
                throw new UncheckedIOException("Could not write a SYSOUT line of " + PROGRAM_NAME
                        + " to its destination stream; " + encoded.length + " byte(s) were pending",
                        refused);
            }
        }
    }

    /**
     * What one {@code PERFORM 1000-XREFFILE-GET-NEXT} left behind: the new {@code END-OF-FILE} value and
     * the record the {@code READ ... INTO} placed in the record area.
     *
     * @param endOfFile {@link #NOT_AT_END_OF_FILE} or {@link #AT_END_OF_FILE}
     * @param record the record read, or {@code null} at end of file - in which case the caller leaves its
     *     record area untouched, exactly as a COBOL {@code READ ... INTO} does at {@code AT END}
     * @param storedImage the row's stored fixed-width record image, exactly as the dataset holds it
     */
    private record GetNextOutcome(String endOfFile, CardXrefRecord record, String storedImage) {
    }

    public record ExecutionSummary(int returnCode, int recordsRead) {
        public ExecutionSummary {
            if (recordsRead < 0) {
                throw new IllegalArgumentException("A record count of " + recordsRead + " is not "
                        + "possible: a sequential browse returns zero or more records, and " + PROGRAM_NAME
                        + " counts the ones it displayed.");
            }
        }

        /**
         * How many record lines the execution emitted: {@value #DISPLAYS_PER_RECORD} per record, because
         * {@code app/cbl/CBACT03C.cbl} displays the record area at {@code :96} and again at {@code :78}.
         *
         * @return the number of record lines
         */
        public int recordLinesDisplayed() {
            return recordsRead * DISPLAYS_PER_RECORD;
        }

        /**
         * How many lines the execution emitted in total: the two banners plus the record lines.
         *
         * <p>For the 50-record {@code app/data/ASCII/cardxref.txt} fixture this is 102, which is the
         * expectation the parity case for this program states.
         *
         * @return the total number of {@code SYSOUT} lines
         */
        public int totalLinesDisplayed() {
            return BANNER_LINES + recordLinesDisplayed();
        }
    }
}

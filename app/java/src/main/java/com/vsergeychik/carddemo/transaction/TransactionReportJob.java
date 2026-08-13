package com.vsergeychik.carddemo.transaction;

import com.vsergeychik.carddemo.card.CardXrefRepository;
import com.vsergeychik.carddemo.card.CardXrefRepository.BrowseCursor;
import com.vsergeychik.carddemo.card.model.CardXrefRecord;
import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.DatasetRelation.BackendDiagnostic;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.PhysicalSequence;
import com.vsergeychik.carddemo.common.RecordImageForm;
import com.vsergeychik.carddemo.config.BatchConfig;
import com.vsergeychik.carddemo.config.BatchConfig.JobContract;
import com.vsergeychik.carddemo.config.DataSourceConfig.DatasetBinding;
import com.vsergeychik.carddemo.config.BatchConfig.StepContract;
import com.vsergeychik.carddemo.config.BatchConfig.StopSignal;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.common.DatasetUnitOfWork;
import com.vsergeychik.carddemo.statement.StatementGenerationJobA.DatasetUtilityPort;
import com.vsergeychik.carddemo.statement.StatementGenerationJobA.JdbcDatasetUtilityPort;
import com.vsergeychik.carddemo.transaction.model.TranCategoryRecord;
import com.vsergeychik.carddemo.transaction.model.TranRecord;
import com.vsergeychik.carddemo.transaction.model.TranReportLayouts;
import com.vsergeychik.carddemo.transaction.model.TranTypeRecord;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code CBTRN03C} - print the transaction detail report - as a Spring Batch job.
 *
 * <p>Every transition is unconditional: {@code app/jcl/TRANREPT.jcl} carries no step-level {@code COND} at
 * all, the {@code COND=} it contains being the SORT's {@code INCLUDE} filter, which is why
 * {@link BatchConfig#precedingExitCodeZeroDecider()} is deliberately not wired into this job.
 */
@Configuration(TransactionReportJob.CONFIGURATION_BEAN_NAME)
public class TransactionReportJob {
    // Every name is a configuration key or a JCL name, never a literal invented here.

    private static final Log LOG = LogFactory.getLog(TransactionReportJob.class);

    private static void reportCleanupFailure(String ddName, RuntimeException cleanupFailure) {
        LOG.warn("Releasing the " + ddName + " handle of " + PROGRAM_NAME + " after an incomplete run "
                + "failed - " + BackendDiagnostic.of(cleanupFailure).describe()
                + ". The run's own outcome is reported to the caller unchanged, because the run's own "
                + "failure is the one that matters.");
    }

    public static final String CONFIGURATION_BEAN_NAME = "transactionReportJobConfiguration";

    public static final String JOB_KEY = "transaction-report-job";

    public static final String JOB_NAME = "transactionReportJob";

    public static final String STEP_BEAN_NAME = "transactionReportStep";

    public static final String BACKUP_STEP_BEAN_NAME = "transactionReportBackupStep";

    public static final String SORT_STEP_BEAN_NAME = "transactionReportSortStep";

    /**
     * The COBOL {@code PROGRAM-ID} this class translates.
     */
    public static final String PROGRAM_NAME = "CBTRN03C";

    /**
     * The step this class is: {@code //STEP10R EXEC PGM=CBTRN03C} ({@code app/jcl/TRANREPT.jcl:L59},
     * {@code app/proc/TRANREPT.prc:L57}).
     */
    public static final String STEP_NAME = "STEP10R";

    /**
     * The first step, {@code EXEC PROC=REPROC} ({@code app/proc/TRANREPT.prc:L21}) - the
     * {@code IDCAMS REPRO INFILE(FILEIN) OUTFILE(FILEOUT)} of {@code app/ctl/REPROCT.ctl:L15}, which
     * unloads the transaction master onto the backup generation.
     */
    public static final String BACKUP_STEP_NAME = "STEP01R";

    /**
     * The second step, {@code EXEC PGM=SORT} ({@code app/proc/TRANREPT.prc:L35}) - the DFSORT that filters
     * the unloaded records to the reporting date range and orders them by card number.
     */
    public static final String SORT_STEP_NAME = "STEP05R";

    /**
     * The utility {@link #BACKUP_STEP_NAME} runs: {@code EXEC PGM=IDCAMS}, reached through
     * {@code EXEC PROC=REPROC} at {@code app/proc/TRANREPT.prc:L21} whose own {@code PRC001} step is
     * {@code EXEC PGM=IDCAMS} in {@code app/proc/REPROC.prc}.
     */
    public static final String BACKUP_STEP_PROGRAM = "IDCAMS";

    /**
     * The utility {@link #SORT_STEP_NAME} runs: {@code EXEC PGM=SORT}, {@code app/proc/TRANREPT.prc:L35}.
     */
    public static final String SORT_STEP_PROGRAM = "SORT";

    /**
     * The whole step sequence of {@code app/proc/TRANREPT.prc}: the {@link #BACKUP_STEP_PROGRAM} unload,
     * the {@link #SORT_STEP_PROGRAM} filter-and-sort, then {@link #PROGRAM_NAME}, in that order and none of
     * them gated.
     */
    public static final List<StepContract> REQUIRED_STEPS = List.of(
            new StepContract(BACKUP_STEP_NAME, BACKUP_STEP_PROGRAM, false),
            new StepContract(SORT_STEP_NAME, SORT_STEP_PROGRAM, false),
            new StepContract(STEP_NAME, PROGRAM_NAME, false));

    /**
     * The sequential input: {@code SELECT TRANSACT-FILE ASSIGN TO TRANFILE}
     * ({@code app/cbl/CBTRN03C.cbl:L29}), bound by
     * {@code carddemo.jobs.transaction-report-job.datasets.TRANFILE} to the sorted daily file.
     */
    public static final String TRANFILE_DD_NAME = "TRANFILE";

    /**
     * The card cross-reference: {@code SELECT XREF-FILE ASSIGN TO CARDXREF}
     * ({@code app/cbl/CBTRN03C.cbl:L33-L37}), {@code ORGANIZATION IS INDEXED},
     * {@code ACCESS MODE IS RANDOM}, {@code RECORD KEY IS FD-XREF-CARD-NUM}.
     */
    public static final String CARDXREF_DD_NAME = "CARDXREF";

    /**
     * The transaction type file: {@code SELECT TRANTYPE-FILE ASSIGN TO TRANTYPE}
     * ({@code app/cbl/CBTRN03C.cbl:L39-L43}), keyed on the 2-byte {@code FD-TRAN-TYPE}.
     */
    public static final String TRANTYPE_DD_NAME = TranTypeRepository.DD_NAME;

    /**
     * The transaction category file: {@code SELECT TRANCATG-FILE ASSIGN TO TRANCATG}
     * ({@code app/cbl/CBTRN03C.cbl:L45-L49}), keyed on the 6-byte {@code FD-TRAN-CAT-KEY}.
     */
    public static final String TRANCATG_DD_NAME = TranCategoryRepository.DD_NAME;

    /**
     * The reporting date range: {@code SELECT DATE-PARMS-FILE ASSIGN TO DATEPARM}
     * ({@code app/cbl/CBTRN03C.cbl:L55-L57}), one 80-byte sequential record.
     */
    public static final String DATEPARM_DD_NAME = DateParmReader.DD_NAME;

    /**
     * The report output: {@code SELECT REPORT-FILE ASSIGN TO TRANREPT}
     * ({@code app/cbl/CBTRN03C.cbl:L51-L53}), {@code RECFM=FB LRECL=133}.
     */
    public static final String TRANREPT_DD_NAME = TranReportWriter.DD_NAME;

    static final String TRANREPT_ABNORMAL_DISPOSITION =
            TRANREPT_DD_NAME + " DISP=(NEW,CATLG,DELETE) abnormal disposition";

    /**
     * {@link #BACKUP_STEP_NAME}'s input: {@code //PRC001.FILEIN DD DISP=SHR}
     * ({@code app/jcl/TRANREPT.jcl:L26-L27}, {@code app/proc/TRANREPT.prc:L24-L25}), the transaction master
     * itself - which is why configuration binds it {@code alias: TRANSACT}.
     */
    public static final String BACKUP_INPUT_DD_NAME = "FILEIN";

    /**
     * {@link #BACKUP_STEP_NAME}'s output: {@code //PRC001.FILEOUT DD DISP=(NEW,CATLG,DELETE)} with
     * {@code DCB=(LRECL=350,RECFM=FB,BLKSIZE=0)} ({@code app/jcl/TRANREPT.jcl:L29-L33}), the backup
     * generation - {@code REPRO OUTFILE(FILEOUT)}.
     */
    public static final String BACKUP_OUTPUT_DD_NAME = "FILEOUT";

    /**
     * {@link #SORT_STEP_NAME}'s input: {@code //SORTIN DD DISP=SHR} ({@code app/jcl/TRANREPT.jcl:L38-L39}),
     * the same backup generation {@link #BACKUP_OUTPUT_DD_NAME} just wrote.
     */
    public static final String SORT_INPUT_DD_NAME = "SORTIN";

    /**
     * {@link #SORT_STEP_NAME}'s output: {@code //SORTOUT DD DISP=(NEW,CATLG,DELETE)} with
     * {@code DCB=(*.SORTIN)} ({@code app/jcl/TRANREPT.jcl:L51-L55}), the sorted daily file - and the
     * dataset this job's own {@link #TRANFILE_DD_NAME} binding then reports from.
     */
    public static final String SORT_OUTPUT_DD_NAME = "SORTOUT";

    /**
     * The record format every dataset of the two utility steps declares: {@code FB}, fixed blocked
     * ({@code app/jcl/TRANREPT.jcl:L31}, and {@code L53}'s {@code DCB=(*.SORTIN)} by reference).
     */
    public static final String UTILITY_RECORD_FORMAT = "FB";

    /**
     * The value {@code carddemo.jobs.transaction-report-job.date-range-source} must hold.
     */
    public static final String DATE_RANGE_SOURCE = DATEPARM_DD_NAME;

    /**
     * {@code TRAN-CARD-NUM,263,16,ZD} - one-based position of the sort key.
     */
    public static final int SORT_TRAN_CARD_NUM_POSITION = TranRecord.TRAN_CARD_NUM_OFFSET + 1;

    /**
     * {@code TRAN-CARD-NUM,263,16,ZD} - length of the sort key.
     */
    public static final int SORT_TRAN_CARD_NUM_LENGTH = TranRecord.TRAN_CARD_NUM_LENGTH;

    /**
     * {@code TRAN-PROC-DT,305,10,CH} - one-based position of the include-filter field.
     */
    public static final int SORT_TRAN_PROC_DT_POSITION = TranRecord.TRAN_PROC_DT_OFFSET + 1;

    /**
     * {@code TRAN-PROC-DT,305,10,CH} - length of the include-filter field.
     */
    public static final int SORT_TRAN_PROC_DT_LENGTH = TranRecord.TRAN_PROC_DT_LENGTH;

    /**
     * {@code PARM-START-DATE,C'2022-01-01'} - {@code app/jcl/TRANREPT.jcl:L43},
     * {@code app/proc/TRANREPT.prc:L41}.
     */
    public static final String SORT_INCLUDE_START_DATE = "2022-01-01";

    /**
     * {@code PARM-END-DATE,C'2022-07-06'} - {@code app/jcl/TRANREPT.jcl:L44},
     * {@code app/proc/TRANREPT.prc:L42}.
     */
    public static final String SORT_INCLUDE_END_DATE = "2022-07-06";

    /**
     * {@code 05 WS-PAGE-SIZE PIC 9(03) COMP-3 VALUE 20} - {@code app/cbl/CBTRN03C.cbl:L131-L132}.
     */
    public static final int PAGE_SIZE = 20;

    /**
     * Lines written by {@code 1120-WRITE-HEADERS}: four, each followed by {@code ADD 1}.
     */
    public static final int HEADER_LINES = 4;

    /**
     * Lines written by {@code 1110-WRITE-PAGE-TOTALS} and by {@code 1120-WRITE-ACCOUNT-TOTALS}: the total
     * line and the rule line that follows it, each followed by {@code ADD 1}.
     */
    public static final int TOTALS_BLOCK_LINES = 2;

    /**
     * {@code p} in the {@code PIC S9(09)V99} of the three running totals
     * ({@code app/cbl/CBTRN03C.cbl:L134-L136}).
     */
    public static final int TOTAL_INTEGER_DIGITS = 9;

    public static final int TOTAL_SCALE = CobolDecimal.MONETARY_SCALE;

    /**
     * Width of {@code WS-CURR-CARD-NUM PIC X(16)} - {@code app/cbl/CBTRN03C.cbl:L137}.
     */
    public static final int CARD_NUMBER_KEY_LENGTH = TranRecord.TRAN_CARD_NUM_LENGTH;

    /**
     * Width of {@code WS-START-DATE} and {@code WS-END-DATE} - {@code :L123} and {@code :L125}.
     */
    public static final int DATE_LENGTH = DateParmReader.START_DATE_LENGTH;

    /**
     * {@code MOVE 8 TO APPL-RESULT} - the value every open paragraph seeds and the very next test
     * overwrites on both arms.
     */
    public static final int APPL_RESULT_ASSUMED_FAILURE = AbendException.RETURN_CODE_ASSUMED_FAILURE;

    /**
     * {@code MOVE 12 TO APPL-RESULT} - the {@code WHEN OTHER} value, and the abend's return code.
     */
    public static final int APPL_RESULT_FATAL = AbendException.RETURN_CODE_IO_ERROR;

    /**
     * {@code 88 APPL-AOK VALUE 0} - {@code app/cbl/CBTRN03C.cbl:L151}.
     */
    public static final int APPL_RESULT_AOK = FileStatus.APPL_AOK;

    /**
     * {@code 88 APPL-EOF VALUE 16} - {@code app/cbl/CBTRN03C.cbl:L152}.
     */
    public static final int APPL_RESULT_EOF = FileStatus.APPL_EOF;

    /**
     * {@code MOVE 23 TO IO-STATUS} - what all three keyed lookups move before rendering the status line
     * ({@code :488}, {@code :498}, {@code :508}).
     */
    public static final String IO_STATUS_INVALID_KEY = FileStatus.NOT_FOUND;

    private static final char PERMANENT_ERROR_FEEDBACK_CODE = 0;

    /**
     * The two-character file status attributed to a report write or close that the sink refused.
     */
    public static final String PERMANENT_ERROR_STATUS = "9" + PERMANENT_ERROR_FEEDBACK_CODE;

    /**
     * {@code WS-FIRST-TIME} before the first detail line is written.
     */
    public static final String FIRST_TIME = "Y";

    /**
     * {@code WS-FIRST-TIME} after {@code :276} has moved {@code 'N'} into it.
     */
    public static final String NOT_FIRST_TIME = "N";

    /**
     * {@code END-OF-FILE} once a read has reported end of file, or {@code DATEPARM} was empty.
     */
    public static final String AT_END_OF_FILE = "Y";

    /**
     * {@code END-OF-FILE} while records remain.
     */
    public static final String NOT_AT_END_OF_FILE = "N";

    // Trailing spaces are significant and are preserved: COBOL DISPLAY concatenates its operands with no
    // separator, so the space inside 'TRAN-AMT ' is the only thing between the label and the value, and
    // 'WS-PAGE-TOTAL' having none is why that line runs together.

    /**
     * {@code app/cbl/CBTRN03C.cbl:L160}.
     */
    public static final String START_OF_EXECUTION = "START OF EXECUTION OF PROGRAM CBTRN03C";

    /**
     * {@code app/cbl/CBTRN03C.cbl:L215}.
     */
    public static final String END_OF_EXECUTION = "END OF EXECUTION OF PROGRAM CBTRN03C";

    /**
     * {@code app/cbl/CBTRN03C.cbl:L387}, {@code 0000-TRANFILE-OPEN}.
     */
    public static final String ERROR_OPENING_TRANFILE = "ERROR OPENING TRANFILE";

    /**
     * {@code app/cbl/CBTRN03C.cbl:L405}, {@code 0100-REPTFILE-OPEN}.
     */
    public static final String ERROR_OPENING_REPTFILE = "ERROR OPENING REPTFILE";

    /**
     * {@code app/cbl/CBTRN03C.cbl:L423}, {@code 0200-CARDXREF-OPEN}.
     */
    public static final String ERROR_OPENING_CROSS_REF_FILE = "ERROR OPENING CROSS REF FILE";

    /**
     * {@code app/cbl/CBTRN03C.cbl:L441}, {@code 0300-TRANTYPE-OPEN}.
     */
    public static final String ERROR_OPENING_TRANSACTION_TYPE_FILE =
            "ERROR OPENING TRANSACTION TYPE FILE";

    /**
     * {@code app/cbl/CBTRN03C.cbl:L459}, {@code 0400-TRANCATG-OPEN}.
     */
    public static final String ERROR_OPENING_TRANSACTION_CATG_FILE =
            "ERROR OPENING TRANSACTION CATG FILE";

    /**
     * {@code app/cbl/CBTRN03C.cbl:L477}, {@code 0500-DATEPARM-OPEN}.
     */
    public static final String ERROR_OPENING_DATE_PARM_FILE = "ERROR OPENING DATE PARM FILE";

    /**
     * {@code app/cbl/CBTRN03C.cbl:L238}, {@code 0550-DATEPARM-READ}.
     */
    public static final String ERROR_READING_DATEPARM_FILE = "ERROR READING DATEPARM FILE";

    /**
     * {@code app/cbl/CBTRN03C.cbl:L266}, {@code 1000-TRANFILE-GET-NEXT}.
     */
    public static final String ERROR_READING_TRANSACTION_FILE = "ERROR READING TRANSACTION FILE";

    /**
     * {@code app/cbl/CBTRN03C.cbl:L354}, {@code 1111-WRITE-REPORT-REC}.
     */
    public static final String ERROR_WRITING_REPTFILE = "ERROR WRITING REPTFILE";

    /**
     * {@code app/cbl/CBTRN03C.cbl:L525}, {@code 9000-TRANFILE-CLOSE}.
     */
    public static final String ERROR_CLOSING_POSTED_TRANSACTION_FILE =
            "ERROR CLOSING POSTED TRANSACTION FILE";

    /**
     * {@code app/cbl/CBTRN03C.cbl:L543}, {@code 9100-REPTFILE-CLOSE}.
     */
    public static final String ERROR_CLOSING_REPORT_FILE = "ERROR CLOSING REPORT FILE";

    /**
     * {@code app/cbl/CBTRN03C.cbl:L562}, {@code 9200-CARDXREF-CLOSE}.
     */
    public static final String ERROR_CLOSING_CROSS_REF_FILE = "ERROR CLOSING CROSS REF FILE";

    /**
     * {@code app/cbl/CBTRN03C.cbl:L580}, {@code 9300-TRANTYPE-CLOSE}.
     */
    public static final String ERROR_CLOSING_TRANSACTION_TYPE_FILE =
            "ERROR CLOSING TRANSACTION TYPE FILE";

    /**
     * {@code app/cbl/CBTRN03C.cbl:L598}, {@code 9400-TRANCATG-CLOSE}.
     */
    public static final String ERROR_CLOSING_TRANSACTION_CATG_FILE =
            "ERROR CLOSING TRANSACTION CATG FILE";

    /**
     * {@code app/cbl/CBTRN03C.cbl:L616}, {@code 9500-DATEPARM-CLOSE}.
     */
    public static final String ERROR_CLOSING_DATE_PARM_FILE = "ERROR CLOSING DATE PARM FILE";

    /**
     * {@code DISPLAY 'INVALID CARD NUMBER : ' FD-XREF-CARD-NUM} - {@code :L487}.
     */
    public static final String INVALID_CARD_NUMBER = "INVALID CARD NUMBER : ";

    /**
     * {@code DISPLAY 'INVALID TRANSACTION TYPE : ' FD-TRAN-TYPE} - {@code :L497}.
     */
    public static final String INVALID_TRANSACTION_TYPE = "INVALID TRANSACTION TYPE : ";

    /**
     * {@code DISPLAY 'INVALID TRAN CATG KEY : ' FD-TRAN-CAT-KEY} - {@code :L507}.
     */
    public static final String INVALID_TRAN_CATG_KEY = "INVALID TRAN CATG KEY : ";

    public static final String REPORTING_FROM = "Reporting from ";

    public static final String REPORTING_TO = " to ";

    /**
     * {@code DISPLAY 'TRAN-AMT ' TRAN-AMT} - {@code :L198}, part of defect 2's evidence trail.
     */
    public static final String TRAN_AMT_DISPLAY_LABEL = "TRAN-AMT ";

    /**
     * {@code DISPLAY 'WS-PAGE-TOTAL' WS-PAGE-TOTAL} - {@code :L199}.
     */
    public static final String WS_PAGE_TOTAL_DISPLAY_LABEL = "WS-PAGE-TOTAL";

    public static final String DATASET_CHARSET_BEAN_NAME =
            CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME;

    private static final char SYSOUT_LINE_TERMINATOR = '\n';

    private static final String SYSOUT_SUBJECT = "a SYSOUT display line of " + PROGRAM_NAME;

    private final BatchConfig batchConfig;

    private final TransactionRepository transactionRepository;

    private final CardXrefRepository cardXrefRepository;

    private final TranTypeRepository tranTypeRepository;

    private final TranCategoryRepository tranCategoryRepository;

    private final DateParmReader dateParmReader;

    private final TranReportWriter tranReportWriter;

    private final DatasetUnitOfWork unitOfWork;

    private final DatasetUtilityPort datasetUtilityPort;

    private final FixedWidthCodec codec;

    private final ObjectProvider<SysoutSink> sysoutSinkProvider;

    private final String stepName;

    private final String backupStepName;

    private final String sortStepName;

    private final String tranFileDatasetName;

    /**
     * Wires the job and validates, at startup, that the configured contract still says what
     * {@code app/jcl/TRANREPT.jcl} and {@code app/proc/TRANREPT.prc} say.
     *
     * @param batchConfig the batch seam supplying job and step builders, the job contract and DD-name
     *     resolution; never {@code null}
     * @param transactionRepository the transaction repository, opened over this job's own
     *     {@link #TRANFILE_DD_NAME} binding; never {@code null}
     * @param cardXrefRepository the card cross-reference repository, opened for input and read randomly by
     *     card number; never {@code null}
     * @param tranTypeRepository the transaction type repository; never {@code null}
     * @param tranCategoryRepository the transaction category repository; never {@code null}
     * @param dateParmReader the reporting date range's reader - the reason this job needs no job parameter;
     *     never {@code null}
     * @param tranReportWriter the 133-byte report writer; never {@code null}
     * @param datasetCharset the module's active dataset code page, injected by the bean name
     *     {@link #DATASET_CHARSET_BEAN_NAME} so it is stated explicitly rather than taken from the platform;
     *     never {@code null}
     * @param sysoutSinkProvider provider for a {@code SYSOUT} destination; never {@code null}, though it
     *     may resolve to nothing, in which case {@link #defaultSysoutSink()} is used
     * @param jdbcTemplate the module's single {@link JdbcTemplate}, used only to build the default
     *     {@linkplain DatasetUtilityPort utility port} when the deployment supplies none; never {@code null}
     * @param recordImageForm how this deployment's driver presents a record image, from
     *     {@value RecordImageForm#FORM_PROPERTY}
     * @param physicalSequence the deployment's physical-record ordinal, from
     *     {@value PhysicalSequence#EXPRESSION_PROPERTY}
     * @param unitOfWork the dataset unit of work the step's reads and writes run inside
     * @param datasetUtilityPortProvider provider for the dataset-utility path the two preparatory steps
     *     use; never {@code null}, though it may resolve to nothing
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalStateException if the contract names a different program, declares any job parameter,
     *     sources its date range from anything but {@link #DATE_RANGE_SOURCE}, omits or reorders the three JCL
     *     steps, gates any of them
     */
    public TransactionReportJob(
            BatchConfig batchConfig,
            TransactionRepository transactionRepository,
            CardXrefRepository cardXrefRepository,
            TranTypeRepository tranTypeRepository,
            TranCategoryRepository tranCategoryRepository,
            DateParmReader dateParmReader,
            TranReportWriter tranReportWriter,
            @Qualifier(DATASET_CHARSET_BEAN_NAME) Charset datasetCharset,
            ObjectProvider<SysoutSink> sysoutSinkProvider,
            JdbcTemplate jdbcTemplate,
            RecordImageForm recordImageForm,
            PhysicalSequence physicalSequence,
            DatasetUnitOfWork unitOfWork,
            ObjectProvider<DatasetUtilityPort> datasetUtilityPortProvider) {
        this.batchConfig = Objects.requireNonNull(batchConfig, "The batch configuration seam is "
                + "required: it supplies the job repository, the transaction manager and the abend "
                + "listener, so no job class assembles Spring Batch plumbing of its own");
        this.transactionRepository = Objects.requireNonNull(transactionRepository, "The transaction "
                + "repository is required: app/cbl/CBTRN03C.cbl:29 assigns the report's input to DD "
                + TRANFILE_DD_NAME + ", which app/jcl/TRANREPT.jcl:65-66 binds to the sorted daily "
                + "file");
        this.cardXrefRepository = Objects.requireNonNull(cardXrefRepository, "The card cross-reference "
                + "repository is required: 1500-A-LOOKUP-XREF reads it once per account break to "
                + "resolve XREF-ACCT-ID for the detail line");
        this.tranTypeRepository = Objects.requireNonNull(tranTypeRepository, "The transaction type "
                + "repository is required: 1500-B-LOOKUP-TRANTYPE reads it for every detail line's "
                + "TRAN-TYPE-DESC");
        this.tranCategoryRepository = Objects.requireNonNull(tranCategoryRepository, "The transaction "
                + "category repository is required: 1500-C-LOOKUP-TRANCATG reads it for every detail "
                + "line's TRAN-CAT-TYPE-DESC");
        this.dateParmReader = Objects.requireNonNull(dateParmReader, "The " + DATEPARM_DD_NAME
                + " reader is required: 0550-DATEPARM-READ takes the reporting range from a dataset, "
                + "not from a PARM, so there is no job parameter to fall back on");
        this.tranReportWriter = Objects.requireNonNull(tranReportWriter, "The report writer is "
                + "required: it owns the " + TranReportWriter.RECORD_LENGTH + "-byte record and every "
                + "width decision this job must not make for itself");
        this.codec = new FixedWidthCodec(Objects.requireNonNull(datasetCharset, "A dataset charset is "
                + "required: this job renders SYSOUT lines, a 16-byte key and two zoned DISPLAY images, "
                + "so the code page is stated explicitly and never taken from the platform"));
        this.sysoutSinkProvider = Objects.requireNonNull(sysoutSinkProvider, "A SysoutSink provider is "
                + "required: DISPLAY output is emitted through an injected sink so it can be captured "
                + "and compared, never written straight to a stream from the program body");
        Objects.requireNonNull(jdbcTemplate, "A JdbcTemplate is required to build the default dataset "
                + "utility path for the " + BACKUP_STEP_NAME + " unload and the " + SORT_STEP_NAME
                + " sort; the data-source configuration declares the single instance this module shares");
        Objects.requireNonNull(recordImageForm, "A record-image representation is required: whether "
                + "this deployment's driver presents a record image as characters or as bytes is stated "
                + "once, by " + RecordImageForm.FORM_PROPERTY + ", and never decided per component");
        Objects.requireNonNull(physicalSequence, "A physical-record ordinal is required: the backup "
                + "unload and the sort move a physical-sequential dataset record for record and in "
                + "order, and SQL returns rows in no order unless a statement says which. It is stated "
                + "once, by " + PhysicalSequence.EXPRESSION_PROPERTY + ", and never decided per "
                + "component");
        this.unitOfWork = Objects.requireNonNull(unitOfWork, "A unit of work is required: "
                + "app/jcl/TRANREPT.jcl:76-80 declares " + TRANREPT_DD_NAME
                + " with DISP=(NEW,CATLG,DELETE), so a run that does not complete normally must leave no "
                + "report at all - and that deletion has to be persisted in a boundary of its own, or the "
                + "very failure that triggered it would undo it");
        Objects.requireNonNull(datasetUtilityPortProvider, "A dataset-utility port provider is "
                + "required: the two preparatory steps run mainframe utilities over whole record images, "
                + "and which data path they use is a deployment-time input rather than a decision this "
                + "job makes");
        this.datasetUtilityPort = datasetUtilityPortProvider.getIfAvailable(
                () -> new JdbcDatasetUtilityPort(jdbcTemplate, datasetCharset, recordImageForm,
                        physicalSequence));

        JobContract contract = batchConfig.contract(JOB_KEY);
        requireProgram(contract.program(), "carddemo.jobs." + JOB_KEY + ".program");
        requireNoJobParameters(contract);
        requireDateRangeSource(contract);
        requireStepSequence(contract);
        batchConfig.requireSteps(JOB_KEY, REQUIRED_STEPS, "app/proc/TRANREPT.prc");

        StepContract step = contract.step(STEP_NAME);
        requireProgram(step.program(), "carddemo.jobs." + JOB_KEY + ".steps[" + STEP_NAME
                + "].program");
        this.stepName = step.name();
        this.backupStepName = contract.step(BACKUP_STEP_NAME).name();
        this.sortStepName = contract.step(SORT_STEP_NAME).name();

        var tranFile = batchConfig.datasetBinding(JOB_KEY, TRANFILE_DD_NAME);
        requireTranFileRecordWidth(tranFile.recordLength());
        this.tranFileDatasetName = requireUsableDatasetName(TRANFILE_DD_NAME, tranFile.dsname());

        requireUtilityStepBindings();
        requireReportRecordWidth(tranReportWriter.recordLength());

        batchConfig.requireSameDataset(JOB_KEY, CARDXREF_DD_NAME, CardXrefRepository.BASE_DD_NAME);
        batchConfig.requireSameDataset(JOB_KEY, TRANTYPE_DD_NAME, TranTypeRepository.DD_NAME);
        batchConfig.requireSameDataset(JOB_KEY, TRANCATG_DD_NAME, TranCategoryRepository.DD_NAME);
    }

    /**
     * Requires the four DDs of the two preparatory steps to be bound, addressable, and to declare the
     * geometry the JCL declares.
     *
     * @throws IllegalStateException if any of the four is unbound, blank, of the wrong width, or of a
     *     record format other than {@link #UTILITY_RECORD_FORMAT}, or if the two sort bindings disagree
     */
    private void requireUtilityStepBindings() {
        for (String ddName : List.of(BACKUP_INPUT_DD_NAME, BACKUP_OUTPUT_DD_NAME,
                SORT_INPUT_DD_NAME, SORT_OUTPUT_DD_NAME)) {
            var binding = batchConfig.datasetBinding(JOB_KEY, ddName);
            requireUsableDatasetName(ddName, binding.dsname());
            if (binding.recordLength() != TranRecord.RECORD_LENGTH) {
                throw new IllegalStateException("The " + ddName + " binding of " + JOB_KEY
                        + " declares record-length " + binding.recordLength() + ", but "
                        + "app/jcl/TRANREPT.jcl:31 declares DCB=(LRECL=" + TranRecord.RECORD_LENGTH
                        + ",RECFM=" + UTILITY_RECORD_FORMAT + ",BLKSIZE=0) for the unload and its SORT "
                        + "reads TRAN-CARD-NUM at one-based " + SORT_TRAN_CARD_NUM_POSITION
                        + " and TRAN-PROC-DT at one-based " + SORT_TRAN_PROC_DT_POSITION
                        + " out of that record. app/cpy/CVTRA05Y.cpy declares (RECLN "
                        + TranRecord.RECORD_LENGTH + ").");
            }
            if (!UTILITY_RECORD_FORMAT.equalsIgnoreCase(binding.recordFormat())) {
                throw new IllegalStateException("The " + ddName + " binding of " + JOB_KEY
                        + " declares record-format "
                        + (binding.recordFormat() == null
                                ? "absent" : "'" + binding.recordFormat() + "'")
                        + ", but app/jcl/TRANREPT.jcl:31 declares RECFM=" + UTILITY_RECORD_FORMAT
                        + " and its L53 DCB=(*.SORTIN) carries the same format onto the sorted file. "
                        + "Fixed blocked is what makes every record exactly "
                        + TranRecord.RECORD_LENGTH + " bytes, so it is required rather than assumed.");
            }
        }
        var sortIn = batchConfig.datasetBinding(JOB_KEY, SORT_INPUT_DD_NAME);
        var sortOut = batchConfig.datasetBinding(JOB_KEY, SORT_OUTPUT_DD_NAME);
        if (sortIn.recordLength() != sortOut.recordLength()
                || !String.valueOf(sortIn.recordFormat())
                        .equalsIgnoreCase(String.valueOf(sortOut.recordFormat()))) {
            throw new IllegalStateException("The " + SORT_OUTPUT_DD_NAME + " binding of " + JOB_KEY
                    + " declares record-length " + sortOut.recordLength() + " and record-format '"
                    + sortOut.recordFormat() + "', but app/jcl/TRANREPT.jcl:53 declares "
                    + "DCB=(*.SORTIN) - the geometry of " + SORT_INPUT_DD_NAME + ", which is "
                    + sortIn.recordLength() + " and '" + sortIn.recordFormat() + "'. The sorted file is "
                    + "the same records in a different order, so it is the same shape.");
        }
    }

    private static void requireProgram(String configured, String key) {
        if (!PROGRAM_NAME.equals(configured)) {
            throw new IllegalStateException(key + " is '" + configured + "', but "
                    + TransactionReportJob.class.getSimpleName() + " translates " + PROGRAM_NAME
                    + " (app/cbl/CBTRN03C.cbl), which app/jcl/TRANREPT.jcl:59 runs as its " + STEP_NAME
                    + " step. Correct the configured program name; do not repoint this class.");
        }
    }

    private static void requireNoJobParameters(JobContract contract) {
        if (!contract.parameters().isEmpty()) {
            throw new IllegalStateException("carddemo.jobs." + JOB_KEY + ".parameters declares "
                    + contract.parameters().size() + " parameter(s), but app/jcl/TRANREPT.jcl:59 runs "
                    + PROGRAM_NAME + " with no PARM and the program declares no LINKAGE SECTION. Its "
                    + "reporting date range comes from DD " + DATEPARM_DD_NAME + " at "
                    + "app/cbl/CBTRN03C.cbl:221, which is what date-range-source records. The empty "
                    + "list is the contract; declare no parameter here.");
        }
    }

    private static void requireDateRangeSource(JobContract contract) {
        if (!DATE_RANGE_SOURCE.equals(contract.dateRangeSource())) {
            throw new IllegalStateException("carddemo.jobs." + JOB_KEY + ".date-range-source is "
                    + (contract.dateRangeSource() == null
                            ? "not declared" : "'" + contract.dateRangeSource() + "'")
                    + ", but app/cbl/CBTRN03C.cbl:221 reads the reporting range from DD "
                    + DATE_RANGE_SOURCE + " - a dataset, not a PARM. Declare date-range-source: "
                    + DATE_RANGE_SOURCE + ".");
        }
    }

    /**
     * Requires the contract to record all three JCL steps, in order, all ungated.
     *
     * @param contract the configured contract
     * @throws IllegalStateException if the sequence is not exactly {@link #BACKUP_STEP_NAME},
     *     {@link #SORT_STEP_NAME}, {@link #STEP_NAME}, or if any step is gated
     */
    private static void requireStepSequence(JobContract contract) {
        List<String> expected = List.of(BACKUP_STEP_NAME, SORT_STEP_NAME, STEP_NAME);
        List<String> declared = contract.steps().stream().map(StepContract::name).toList();
        if (!expected.equals(declared)) {
            throw new IllegalStateException("carddemo.jobs." + JOB_KEY + ".steps declares " + declared
                    + ", but app/proc/TRANREPT.prc names " + expected + " in that order - the REPROC "
                    + "unload, the DFSORT filter-and-sort, and " + PROGRAM_NAME + " itself. "
                    + "app/jcl/TRANREPT.jcl spells the first two identically, which is a defect in that "
                    + "JCL; the .prc names are the unambiguous ones. Restore the sequence.");
        }
        for (StepContract step : contract.steps()) {
            if (step.requirePrecedingExitCodeZero()) {
                throw new IllegalStateException("carddemo.jobs." + JOB_KEY + ".steps[" + step.name()
                        + "].require-preceding-exit-code-zero is true, but no step of "
                        + "app/jcl/TRANREPT.jcl carries a COND. The COND= at its L47 is the SORT's "
                        + "INCLUDE filter on TRAN-PROC-DT, not step gating, and gating this job's "
                        + "steps would skip a report the mainframe produces.");
            }
        }
    }

    /**
     * Requires the {@link #TRANFILE_DD_NAME} binding to agree with {@code app/cpy/CVTRA05Y.cpy}.
     *
     * @param configuredRecordLength the width configuration declares for the DD
     * @throws IllegalStateException if it is not {@value TranRecord#RECORD_LENGTH}
     */
    private static void requireTranFileRecordWidth(int configuredRecordLength) {
        if (configuredRecordLength != TranRecord.RECORD_LENGTH) {
            throw new IllegalStateException("The " + TRANFILE_DD_NAME + " binding of "
                    + JOB_KEY + " declares record-length " + configuredRecordLength
                    + ", but app/cpy/CVTRA05Y.cpy declares (RECLN " + TranRecord.RECORD_LENGTH
                    + ") and app/jcl/TRANREPT.jcl:31 unloads the transaction file at LRECL=350. "
                    + PROGRAM_NAME + " positions every report column from a field of that record, and "
                    + "TRAN-CARD-NUM alone sits at one-based " + SORT_TRAN_CARD_NUM_POSITION + ".");
        }
    }

    private static void requireReportRecordWidth(int configuredRecordLength) {
        if (configuredRecordLength != TranReportWriter.RECORD_LENGTH) {
            throw new IllegalStateException("The " + TRANREPT_DD_NAME + " writer reports a record "
                    + "length of " + configuredRecordLength + ", but app/jcl/TRANREPT.jcl:78 declares "
                    + "LRECL=" + TranReportWriter.RECORD_LENGTH + " and app/cpy/CVTRA07Y.cpy:48 "
                    + "declares TRANSACTION-HEADER-2 as PIC X(" + TranReportWriter.RECORD_LENGTH
                    + "). Every line of this report is that wide.");
        }
    }

    private static String requireUsableDatasetName(String ddName, String dsname) {
        if (dsname == null || dsname.isBlank()) {
            throw new IllegalStateException("The " + ddName + " binding of " + JOB_KEY + " has a "
                    + (dsname == null ? "missing" : "blank") + " dsname, so DD " + ddName
                    + " names no dataset for " + PROGRAM_NAME + " to read. app/jcl/TRANREPT.jcl binds "
                    + "it to the sorted daily transaction file; supply that dataset, or the "
                    + "environment variable the binding defers to.");
        }
        return dsname;
    }

    /**
     * The job {@code app/jcl/TRANREPT.jcl} is: three steps in order, no parameters, no gating.
     *
     * @return the job, named {@link #JOB_NAME} in the batch metadata
     */
    @Bean(JOB_NAME)
    public Job transactionReportJob() {
        return batchConfig.job(JOB_NAME)
                .start(transactionReportBackupStep())
                .next(transactionReportSortStep())
                .next(transactionReportStep())
                .build();
    }

    /**
     * {@link #BACKUP_STEP_NAME} - {@code EXEC PROC=REPROC} ({@code app/jcl/TRANREPT.jcl:L23},
     * {@code app/proc/TRANREPT.prc:L21}), an {@code IDCAMS REPRO} and nothing more.
     *
     * @return the unload step; never {@code null}
     */
    @Bean(BACKUP_STEP_BEAN_NAME)
    public Step transactionReportBackupStep() {
        return batchConfig.taskletStep(backupStepName, transactionReportBackupTasklet()).build();
    }

    /**
     * {@link #SORT_STEP_NAME} - {@code EXEC PGM=SORT} ({@code app/jcl/TRANREPT.jcl:L37},
     * {@code app/proc/TRANREPT.prc:L35}).
     *
     * @return the filter-and-sort step; never {@code null}
     */
    @Bean(SORT_STEP_BEAN_NAME)
    public Step transactionReportSortStep() {
        return batchConfig.taskletStep(sortStepName, transactionReportSortTasklet()).build();
    }

    /**
     * {@link #BACKUP_STEP_NAME}'s body: one call, one complete unload.
     *
     * @return a tasklet that unloads the master exactly once per step execution
     */
    public Tasklet transactionReportBackupTasklet() {
        return (contribution, chunkContext) ->
                reportRecordsWritten(contribution,
                        unloadTransactionMaster(StopSignal.of(chunkContext)));
    }

    /**
     * {@link #SORT_STEP_NAME}'s body: one call, one complete filter and sort.
     *
     * @return a tasklet that filters and sorts exactly once per step execution
     */
    public Tasklet transactionReportSortTasklet() {
        return (contribution, chunkContext) ->
                reportRecordsWritten(contribution,
                        filterAndSortUnloadedTransactions(StopSignal.of(chunkContext)));
    }

    private static RepeatStatus reportRecordsWritten(StepContribution contribution,
            int recordsWritten) {
        contribution.incrementWriteCount(recordsWritten);
        return RepeatStatus.FINISHED;
    }

    /**
     * {@link #BACKUP_STEP_NAME} - {@code REPRO INFILE(FILEIN) OUTFILE(FILEOUT)}
     * ({@code app/ctl/REPROCT.ctl:L15}).
     *
     * @return how many records were unloaded
     * @throws IllegalStateException if either dataset cannot be addressed
     */
    public int unloadTransactionMaster() {
        return unloadTransactionMaster(StopSignal.RUNNING);
    }

    /**
     * {@link #BACKUP_STEP_NAME}, yielding to a stop request between written records.
     *
     * @param stopSignal the between-record cancellation probe; never {@code null}
     * @return how many records were unloaded
     * @throws IllegalStateException if either dataset cannot be addressed
     * @throws BatchConfig.StopRequestedException if the step is asked to stop
     */
    public int unloadTransactionMaster(StopSignal stopSignal) {
        DatasetBinding source = batchConfig.datasetBinding(JOB_KEY, BACKUP_INPUT_DD_NAME);
        DatasetBinding destination = batchConfig.datasetBinding(JOB_KEY, BACKUP_OUTPUT_DD_NAME);
        List<String> unloaded = new ArrayList<>(datasetUtilityPort.readAllRecordImages(source));
        if (source.keyLength() != null) {
            unloaded.sort(MASTER_KEY_ORDER);
        }
        datasetUtilityPort.deleteAllRecords(destination);
        return datasetUtilityPort.writeRecordImages(destination, unloaded, stopSignal);
    }

    /**
     * {@link #SORT_STEP_NAME} - {@code SORT FIELDS=(TRAN-CARD-NUM,A)} with
     * {@code INCLUDE COND=(TRAN-PROC-DT,GE,PARM-START-DATE,AND,TRAN-PROC-DT,LE,PARM-END-DATE)}
     * ({@code app/jcl/TRANREPT.jcl:L46-L48}, {@code app/proc/TRANREPT.prc:L44-L46}).
     *
     * <p>{@code CBTRN03C} breaks and subtotals by account as records arrive
     * ({@code app/cbl/CBTRN03C.cbl:L168-L207}), resolving the account from the card number, so records for
     * one card must arrive together.
     *
     * @return how many records were written to {@link #SORT_OUTPUT_DD_NAME}
     * @throws IllegalStateException if either dataset cannot be addressed
     */
    public int filterAndSortUnloadedTransactions() {
        return filterAndSortUnloadedTransactions(StopSignal.RUNNING);
    }

    /**
     * {@link #SORT_STEP_NAME}, yielding to a stop request between written records.
     *
     * @param stopSignal the between-record cancellation probe; never {@code null}
     * @return how many records were written to {@link #SORT_OUTPUT_DD_NAME}
     * @throws IllegalStateException if either dataset cannot be addressed
     * @throws BatchConfig.StopRequestedException if the step is asked to stop
     */
    public int filterAndSortUnloadedTransactions(StopSignal stopSignal) {
        DatasetBinding source = batchConfig.datasetBinding(JOB_KEY, SORT_INPUT_DD_NAME);
        DatasetBinding destination = batchConfig.datasetBinding(JOB_KEY, SORT_OUTPUT_DD_NAME);
        List<String> included = filterAndSort(datasetUtilityPort.readAllRecordImages(source));
        datasetUtilityPort.deleteAllRecords(destination);
        return datasetUtilityPort.writeRecordImages(destination, included, stopSignal);
    }

    /**
     * The SORT step's transformation, as a function of its input - so it is assertable with no dataset, no
     * backend and no step execution.
     *
     * @param recordImages the unloaded records, each {@value TranRecord#RECORD_LENGTH} characters; never
     *     {@code null} and never containing {@code null}
     * @return the included records in {@code TRAN-CARD-NUM} order; empty when none qualifies, which is not
     *     an error - it is an empty report
     * @throws IllegalArgumentException if any record is not {@value TranRecord#RECORD_LENGTH} characters
     */
    public List<String> filterAndSort(List<String> recordImages) {
        Objects.requireNonNull(recordImages, "Records are required to filter and sort; an empty unload "
                + "yields an empty sorted file and is not an error");
        List<String> included = new ArrayList<>(recordImages.size());
        for (String recordImage : recordImages) {
            if (includedByDateRange(requireSortableRecord(recordImage))) {
                included.add(recordImage);
            }
        }
        included.sort(SORT_FIELDS_ORDER);
        return included;
    }

    private static boolean includedByDateRange(String recordImage) {
        String processedDate = jclField(recordImage, SORT_TRAN_PROC_DT_POSITION,
                SORT_TRAN_PROC_DT_LENGTH);
        return processedDate.compareTo(SORT_INCLUDE_START_DATE) >= 0
                && processedDate.compareTo(SORT_INCLUDE_END_DATE) <= 0;
    }

    /**
     * Requires one record to be exactly as wide as the copybook declares, before a fixed span is read out
     * of it.
     *
     * @param recordImage the record to check
     * @return that record, unchanged
     * @throws IllegalArgumentException if it is not {@value TranRecord#RECORD_LENGTH} characters
     */
    private static String requireSortableRecord(String recordImage) {
        Objects.requireNonNull(recordImage, "A record of " + SORT_INPUT_DD_NAME + " is null, which is "
                + "not a record a dataset can hold");
        if (recordImage.length() != TranRecord.RECORD_LENGTH) {
            throw new IllegalArgumentException("A record of " + SORT_INPUT_DD_NAME + " is "
                    + recordImage.length() + " characters, but app/cpy/CVTRA05Y.cpy declares (RECLN "
                    + TranRecord.RECORD_LENGTH + ") and app/jcl/TRANREPT.jcl:31 unloads at LRECL="
                    + TranRecord.RECORD_LENGTH + ". TRAN-PROC-DT sits at one-based "
                    + SORT_TRAN_PROC_DT_POSITION + " and TRAN-CARD-NUM at one-based "
                    + SORT_TRAN_CARD_NUM_POSITION + ", so a record of any other width would be filtered "
                    + "and ordered on the wrong bytes.");
        }
        return recordImage;
    }

    /**
     * {@code SORT FIELDS=(TRAN-CARD-NUM,A)} - ascending {@code TRAN-CARD-NUM}, the field the symbol table
     * declares at {@code 263,16,ZD} ({@code app/jcl/TRANREPT.jcl:L41,L46}).
     */
    public static final Comparator<String> SORT_FIELDS_ORDER = Comparator.comparing(
            (String recordImage) -> jclField(recordImage, SORT_TRAN_CARD_NUM_POSITION,
                    SORT_TRAN_CARD_NUM_LENGTH));

    private static final Comparator<String> MASTER_KEY_ORDER = Comparator.comparing(
            (String recordImage) -> recordImage.substring(TranRecord.TRAN_ID_OFFSET,
                    TranRecord.TRAN_ID_OFFSET + TranRecord.TRAN_ID_KEY_LENGTH));

    private static String jclField(String recordImage, int oneBasedPosition, int length) {
        int from = oneBasedPosition - 1;
        return recordImage.substring(from, from + length);
    }

    /**
     * The step, named for {@code //STEP10R EXEC PGM=CBTRN03C}.
     *
     * @return the single migrated step of this job
     */
    @Bean(STEP_BEAN_NAME)
    public Step transactionReportStep() {
        return batchConfig.taskletStep(stepName, transactionReportTasklet()).build();
    }

    /**
     * The step body: a thin adapter that resolves the {@code SYSOUT} destination and runs the program.
     *
     * @return a tasklet that runs the program exactly once per step execution
     */
    public Tasklet transactionReportTasklet() {
        return (contribution, chunkContext) -> {
            execute(resolveSysoutSink(), null, StopSignal.of(chunkContext));
            return RepeatStatus.FINISHED;
        };
    }

    /**
     * Runs {@code CBTRN03C} against the configured {@link #TRANREPT_DD_NAME} dataset.
     *
     * @param sysout where the {@code DISPLAY} lines go; never {@code null}
     * @return what the run produced and the return code it ended with
     * @throws NullPointerException if {@code sysout} is {@code null}
     * @throws AbendException if any open, read, write or close reports a status the program does not name,
     *     or if a keyed lookup finds nothing
     * @throws IllegalStateException if the configured {@link #TRANREPT_DD_NAME} name cannot be addressed as
     *     a dataset, which is a configuration fault rather than a dataset condition and therefore has no COBOL
     *     guard
     */
    public ExecutionSummary execute(SysoutSink sysout) {
        return execute(sysout, null);
    }

    /**
     * Runs {@code CBTRN03C}, optionally against a caller-supplied report sink.
     *
     * @param sysout where the {@code DISPLAY} lines go; never {@code null}
     * @param reportSink where the report records go, or {@code null} to write to the configured
     *     {@link #TRANREPT_DD_NAME} dataset
     * @return what the run produced and the return code it ended with
     * @throws NullPointerException if {@code sysout} is {@code null}
     * @throws AbendException if any open, read, write or close reports a status the program does not name,
     *     or if a keyed lookup finds nothing
     * @throws IllegalStateException if {@code reportSink} is {@code null} and the configured
     *     {@link #TRANREPT_DD_NAME} name cannot be addressed as a dataset
     */
    public ExecutionSummary execute(SysoutSink sysout, TranReportWriter.RecordSink reportSink) {
        return execute(sysout, reportSink, StopSignal.RUNNING);
    }

    /**
     * Runs {@code CBTRN03C}, yielding to the given stop signal between records.
     *
     * @param sysout where the {@code DISPLAY} lines go; never {@code null}
     * @param reportSink where the report records go, or {@code null} to write to the configured
     *     {@link #TRANREPT_DD_NAME} dataset
     * @param stopSignal the between-record cancellation probe; {@link StopSignal#RUNNING} for a caller
     *     outside a step; never {@code null}
     * @return what the run produced and the return code it ended with
     * @throws NullPointerException if {@code sysout} or {@code stopSignal} is {@code null}
     * @throws AbendException if any open, read, write or close reports a status the program does not name,
     *     or if a keyed lookup finds nothing
     * @throws IllegalStateException if {@code reportSink} is {@code null} and the configured
     *     {@link #TRANREPT_DD_NAME} name cannot be addressed as a dataset
     * @throws BatchConfig.StopRequestedException if the step is asked to stop, which abandons the pass
     *     between records
     */
    public ExecutionSummary execute(SysoutSink sysout, TranReportWriter.RecordSink reportSink,
            StopSignal stopSignal) {
        Objects.requireNonNull(sysout, "A SYSOUT sink is required to run " + PROGRAM_NAME
                + ": its DISPLAY lines are half of its observable output, so there is nothing to run "
                + "without somewhere to put them");
        Objects.requireNonNull(stopSignal, "A stop signal is required; pass StopSignal.RUNNING outside a "
                + "step, which is what the two-argument overload does");
        return new ReportRun(sysout, reportSink).run(stopSignal);
    }

    /**
     * {@code IF FUNCTION MOD(WS-LINE-COUNTER, WS-PAGE-SIZE) = 0} - {@code app/cbl/CBTRN03C.cbl:282}.
     *
     * <p>{@code WS-LINE-COUNTER} is {@code PIC 9(09) COMP-3}, an unsigned nine-digit packed field, so a
     * negative argument cannot arise in the program and is rejected here rather than given a meaning COBOL
     * does not define for it.
     *
     * @param lineCounter the current {@code WS-LINE-COUNTER}
     * @return {@code true} when a page total and a fresh set of headers are due
     * @throws IllegalArgumentException if {@code lineCounter} is negative
     */
    public static boolean isPageBoundary(long lineCounter) {
        if (lineCounter < 0) {
            throw new IllegalArgumentException("WS-LINE-COUNTER is PIC 9(09) COMP-3, an unsigned "
                    + "field, so " + lineCounter + " is not a value it can hold. FUNCTION MOD is being "
                    + "asked about a counter that could not have been produced by "
                    + PROGRAM_NAME + ".");
        }
        return lineCounter % PAGE_SIZE == 0;
    }

    /**
     * {@code IF TRAN-PROC-TS (1:10) >= WS-START-DATE AND TRAN-PROC-TS (1:10) <= WS-END-DATE} -
     * {@code app/cbl/CBTRN03C.cbl:173-174}.
     *
     * @param procDate {@code TRAN-PROC-TS (1:10)}, the first ten characters of the processing timestamp
     * @param startDate {@code WS-START-DATE}
     * @param endDate {@code WS-END-DATE}
     * @return {@code true} when the record is inside the reporting range
     * @throws NullPointerException if any argument is {@code null}
     */
    public static boolean withinReportingRange(String procDate, String startDate, String endDate) {
        Objects.requireNonNull(procDate, "TRAN-PROC-TS (1:10) is required to test the reporting range");
        Objects.requireNonNull(startDate, "WS-START-DATE is required to test the reporting range");
        Objects.requireNonNull(endDate, "WS-END-DATE is required to test the reporting range");
        return procDate.compareTo(startDate) >= 0 && procDate.compareTo(endDate) <= 0;
    }

    private SysoutSink resolveSysoutSink() {
        return sysoutSinkProvider.getIfAvailable(this::defaultSysoutSink);
    }

    /**
     * The fallback {@code SYSOUT} destination: the process's standard output, in the dataset code page.
     *
     * @return a sink writing to standard output
     */
    public SysoutSink defaultSysoutSink() {
        return sysoutSinkTo(System.out);
    }

    /**
     * A {@code SYSOUT} sink over an arbitrary stream, encoded in the dataset code page.
     *
     * @param destination where the encoded lines go; never {@code null}
     * @return a sink writing to {@code destination}
     * @throws NullPointerException if {@code destination} is {@code null}
     */
    public SysoutSink sysoutSinkTo(OutputStream destination) {
        return new StreamSysoutSink(destination, codec);
    }

    /**
     * The dataset DD {@link #TRANFILE_DD_NAME} resolves to for this job.
     *
     * @return the configured dataset name; never blank
     */
    public String tranFileDatasetName() {
        return tranFileDatasetName;
    }

    /**
     * The configured step name, which is {@link #STEP_NAME} unless configuration says otherwise.
     *
     * @return the step name as the batch metadata will record it
     */
    public String stepName() {
        return stepName;
    }

    /**
     * The configured name of the unload step, which is {@link #BACKUP_STEP_NAME} unless configuration says
     * otherwise.
     *
     * @return the step name as the batch metadata will record it
     */
    public String backupStepName() {
        return backupStepName;
    }

    /**
     * The configured name of the filter-and-sort step, which is {@link #SORT_STEP_NAME} unless
     * configuration says otherwise.
     *
     * @return the step name as the batch metadata will record it
     */
    public String sortStepName() {
        return sortStepName;
    }

    /**
     * The dataset-utility path the two preparatory steps use, as resolved.
     *
     * @return the port, deployment-supplied or the JDBC default; never {@code null}
     */
    public DatasetUtilityPort datasetUtilityPort() {
        return datasetUtilityPort;
    }

    /**
     * The dataset code page every byte of this job's input, output and {@code SYSOUT} is read and written
     * in.
     *
     * @return the injected charset; never {@code null}
     */
    public Charset datasetCharset() {
        return codec.charset();
    }

    /**
     * One execution of {@code CBTRN03C}: its {@code WORKING-STORAGE}, its file handles and its paragraphs.
     *
     * <p>An inner class rather than a set of static helpers taking a state parameter, because the COBOL
     * paragraphs read and write shared {@code WORKING-STORAGE} freely and threading a state object through
     * twenty methods would obscure exactly the ordering this translation exists to preserve.
     */
    private final class ReportRun {
        private final SysoutSink sysout;

        private final TranReportWriter.RecordSink reportSink;

        private final TranReportLayouts layouts;

        private TranRecord tranRecord;

        private String startDate = " ".repeat(DATE_LENGTH);

        private String endDate = " ".repeat(DATE_LENGTH);

        private String firstTime = FIRST_TIME;

        private String endOfFile = NOT_AT_END_OF_FILE;

        private long lineCounter;

        private BigDecimal pageTotal = CobolDecimal.monetaryZero();

        private BigDecimal accountTotal = CobolDecimal.monetaryZero();

        private BigDecimal grandTotal = CobolDecimal.monetaryZero();

        private String currentCardNumber = " ".repeat(CARD_NUMBER_KEY_LENGTH);

        private String xrefCardNumberKey = " ".repeat(CARD_NUMBER_KEY_LENGTH);

        private String tranTypeKey = " ".repeat(TranTypeRecord.TRAN_TYPE_LENGTH);

        private String tranCatTypeCode = " ".repeat(TranCategoryRecord.TRAN_TYPE_CD_LENGTH);

        private int tranCatCode;

        private CardXrefRecord xrefRecord;

        private TranTypeRecord tranTypeRecord;

        private TranCategoryRecord tranCategoryRecord;

        private TransactionRepository.InputFile tranFile;

        private TranReportWriter.ReportFile reportFile;

        private BrowseCursor xrefCursor;

        private int recordsRead;

        private int detailLinesWritten;

        private ReportRun(SysoutSink sysout, TranReportWriter.RecordSink reportSink) {
            this.sysout = sysout;
            this.reportSink = reportSink;
            this.layouts = new TranReportLayouts(codec);
            this.tranRecord = new TranRecord(codec.charset());
            // It matters for one reachable path: a record whose TRAN-CARD-NUM is sixteen spaces equals the
            // initial WS-CURR-CARD-NUM, so :181 is false, so 1500-A-LOOKUP-XREF never runs - and :364 then
            // moves whatever CARD-XREF-RECORD holds.
            this.xrefRecord = new CardXrefRecord("", 0, 0L);
            this.tranTypeRecord = TranTypeRecord.empty(codec);
            this.tranCategoryRecord = TranCategoryRecord.of("  ", 0, "", codec.charset());
        }

        private ExecutionSummary run(StopSignal stopSignal) {
            boolean completedNormally = false;
            try {
                ExecutionSummary summary = runToGoback(stopSignal);
                completedNormally = true;
                return summary;
            } finally {
                releaseHandles(completedNormally);
            }
        }

        private void releaseHandles(boolean completedNormally) {
            if (tranFile != null && tranFile.isOpen()) {
                try {
                    tranFile.closeInput();
                } catch (RuntimeException cleanupFailure) {
                    reportCleanupFailure(TRANFILE_DD_NAME, cleanupFailure);
                }
            }
            if (reportFile != null && reportFile.isOpen()) {
                try {
                    reportFile.closeOutput();
                } catch (RuntimeException cleanupFailure) {
                    reportCleanupFailure(TRANREPT_DD_NAME, cleanupFailure);
                }
            }
            if (xrefCursor != null && xrefCursor.isOpen()) {
                try {
                    xrefCursor.closeBrowse();
                } catch (RuntimeException cleanupFailure) {
                    reportCleanupFailure(CARDXREF_DD_NAME, cleanupFailure);
                }
            }
            if (!completedNormally) {
                discardReportGeneration();
            }
        }

        private void discardReportGeneration() {
            if (reportFile == null) {
                return;
            }
            try {
                FileStatus.Outcome disposition = unitOfWork.persistDisposition(
                        TRANREPT_ABNORMAL_DISPOSITION, reportFile::discardGeneration);
                if (disposition != FileStatus.Outcome.OK) {
                    LOG.error("The " + TRANREPT_DD_NAME + " generation of this abended run could not be "
                            + "discarded; it reported FILE STATUS outcome " + disposition.name()
                            + ". app/jcl/TRANREPT.jcl:76 declares DISP=(NEW,CATLG,DELETE), so a partial "
                            + "report may remain where the mainframe would leave none; its account and "
                            + "grand totals are not trustworthy");
                }
            } catch (RuntimeException dispositionFailure) {
                reportCleanupFailure(TRANREPT_ABNORMAL_DISPOSITION, dispositionFailure);
            }
        }

        private ExecutionSummary runToGoback(StopSignal stopSignal) {
            sysout.display(START_OF_EXECUTION);

            openTranFile();
            openReptFile();
            openCardXref();
            openTranType();
            openTranCatg();
            openDateParm();

            dateParmRead();

            while (!AT_END_OF_FILE.equals(endOfFile)) {
                // The between-record yield to a stop request: a call rather than a condition, so it adds no
                // arm to the translated control flow, and positioned before 1000-TRANFILE-GET-NEXT so a
                // detail line is never abandoned half written.
                stopSignal.checkStopRequested();

                // :171 IF END-OF-FILE = 'N' - redundant: the PERFORM UNTIL condition already establishes it
                // and the flag holds only 'N' or 'Y', so the false path is unreachable.
                if (NOT_AT_END_OF_FILE.equals(endOfFile)) {
                    tranFileGetNext();

                    if (!withinReportingRange(tranRecord.tranProcDt(), startDate, endDate)) {
                        break;
                    }

                    if (NOT_AT_END_OF_FILE.equals(endOfFile)) {
                        reportOneRecord();
                    } else {
                        writeFinalTotals();
                    }
                }
            }

            closeTranFile();
            closeReptFile();
            closeCardXref();
            closeTranType();
            closeTranCatg();
            closeDateParm();

            sysout.display(END_OF_EXECUTION);

            // :217 GOBACK. - RETURN-CODE is never moved anywhere in this program, so a clean run returns
            // zero and the step's exit status stays COMPLETED.
            return new ExecutionSummary(AbendException.RETURN_CODE_OK, recordsRead, detailLinesWritten,
                    reportFile.recordsWritten(), lineCounter, grandTotal);
        }

        private void reportOneRecord() {
            sysout.display(tranRecord.displayImage());

            if (!currentCardNumber.equals(tranRecord.tranCardNum())) {
                if (NOT_FIRST_TIME.equals(firstTime)) {
                    writeAccountTotals();
                }

                currentCardNumber = codec.movePicX(tranRecord.tranCardNum(), CARD_NUMBER_KEY_LENGTH);

                xrefCardNumberKey = codec.movePicX(tranRecord.tranCardNum(), CARD_NUMBER_KEY_LENGTH);

                lookupXref();
            }

            tranTypeKey = codec.movePicX(tranRecord.tranTypeCd(), TranTypeRecord.TRAN_TYPE_LENGTH);

            lookupTranType();

            tranCatTypeCode =
                    codec.movePicX(tranRecord.tranTypeCd(), TranCategoryRecord.TRAN_TYPE_CD_LENGTH);

            tranCatCode = tranRecord.tranCatCd();

            lookupTranCategory();

            writeTransactionReport();
        }

        private void writeFinalTotals() {
            sysout.display(TRAN_AMT_DISPLAY_LABEL + tranRecord.tranAmtImage());

            sysout.display(WS_PAGE_TOTAL_DISPLAY_LABEL + zonedTotalImage(pageTotal));

            BigDecimal amount = tranRecord.tranAmt();
            pageTotal = addToTotal(pageTotal, amount);
            accountTotal = addToTotal(accountTotal, amount);

            writePageTotals();

            writeGrandTotals();
        }

        private void writeTransactionReport() {
            if (FIRST_TIME.equals(firstTime)) {
                firstTime = NOT_FIRST_TIME;
                layouts.moveReptStartDate(startDate);
                layouts.moveReptEndDate(endDate);
                writeHeaders();
            }

            if (isPageBoundary(lineCounter)) {
                writePageTotals();
                writeHeaders();
            }

            BigDecimal amount = tranRecord.tranAmt();
            pageTotal = addToTotal(pageTotal, amount);
            accountTotal = addToTotal(accountTotal, amount);

            writeDetail();
        }

        private void writePageTotals() {
            layouts.moveReptPageTotal(pageTotal);
            writeReportRec(layouts.renderReportPageTotals());
            grandTotal = addToTotal(grandTotal, pageTotal);
            pageTotal = CobolDecimal.monetaryZero();
            lineCounter++;
            writeReportRec(layouts.renderTransactionHeader2());
            lineCounter++;
        }

        private void writeAccountTotals() {
            layouts.moveReptAccountTotal(accountTotal);
            writeReportRec(layouts.renderReportAccountTotals());
            accountTotal = CobolDecimal.monetaryZero();
            lineCounter++;
            writeReportRec(layouts.renderTransactionHeader2());
            lineCounter++;
        }

        private void writeGrandTotals() {
            layouts.moveReptGrandTotal(grandTotal);
            writeReportRec(layouts.renderReportGrandTotals());
        }

        private void writeHeaders() {
            writeReportRec(layouts.renderReportNameHeader());
            lineCounter++;
            writeReportRec(TranReportWriter.WS_BLANK_LINE_IMAGE);
            lineCounter++;
            writeReportRec(layouts.renderTransactionHeader1());
            lineCounter++;
            writeReportRec(layouts.renderTransactionHeader2());
            lineCounter++;
        }

        private void writeDetail() {
            layouts.initializeTransactionDetailReport();
            layouts.moveTranReportTransId(tranRecord.tranId());
            layouts.moveTranReportAccountId(xrefRecord.xrefAcctId());
            layouts.moveTranReportTypeCd(tranRecord.tranTypeCd());
            // :366 MOVE TRAN-TYPE-DESC TO TRAN-REPORT-TYPE-DESC X(50) -&gt; X(15), right-truncated.
            layouts.moveTranReportTypeDesc(tranTypeRecord.tranTypeDesc());
            // :367 MOVE TRAN-CAT-CD OF TRAN-RECORD TO ... 9(04) -&gt; 9(04); the sender's own digit image
            // is passed, so the move is a character move and cannot re-derive the value.
            layouts.moveTranReportCatCd(tranRecord.tranCatCdImage());
            // :368 MOVE TRAN-CAT-TYPE-DESC TO TRAN-REPORT-CAT-DESC X(50) -&gt; X(29), right-truncated.
            layouts.moveTranReportCatDesc(tranCategoryRecord.tranCatTypeDesc());
            layouts.moveTranReportSource(tranRecord.tranSource());
            layouts.moveTranReportAmt(tranRecord.tranAmt());
            writeReportRec(layouts.renderTransactionDetailReport());
            lineCounter++;
            detailLinesWritten++;
        }

        private void writeReportRec(String layoutImage) {
            FileStatus.Outcome outcome = reportFile.writeLine(layoutImage);

            int applResult = outcome == FileStatus.Outcome.OK
                    ? APPL_RESULT_AOK
                    : APPL_RESULT_FATAL;

            if (!applAok(applResult)) {
                String status = statusOf(outcome);
                reportIoFailure(sysout, ERROR_WRITING_REPTFILE, status);
                throw abendProgram(sysout, ERROR_WRITING_REPTFILE, status);
            }
        }

        private void dateParmRead() {
            DateParmReader.ReadResult read = dateParmReader.read();

            int applResult;
            if (FileStatus.isOk(read.status())) {
                applResult = APPL_RESULT_AOK;
            } else if (FileStatus.isEndOfFile(read.status())) {
                applResult = APPL_RESULT_EOF;
            } else {
                applResult = APPL_RESULT_FATAL;
            }

            if (applAok(applResult)) {
                DateParmReader.DateParm range = read.dateParm().orElseThrow(() ->
                        new IllegalStateException("A read of DD " + DATEPARM_DD_NAME + " reported file "
                                + "status " + FileStatus.toStatusImage(read.status()) + " and carried no "
                                + "range. The two are contradictory: a successful READ leaves "
                                + "WS-DATEPARM-RECORD populated, which is what " + PROGRAM_NAME
                                + ":232 displays and what every range test afterwards reads."));
                startDate = range.startDate();
                endDate = range.endDate();
                sysout.display(REPORTING_FROM + startDate + REPORTING_TO + endDate);
                return;
            }

            if (applEof(applResult)) {
                endOfFile = AT_END_OF_FILE;
                return;
            }

            reportIoFailure(sysout, ERROR_READING_DATEPARM_FILE, read.status());
            throw abendProgram(sysout, ERROR_READING_DATEPARM_FILE, read.status());
        }

        private void tranFileGetNext() {
            TransactionRepository.ReadResult read = tranFile.readNext();
            String status = read.status();

            int applResult;
            if (FileStatus.isOk(status)) {
                applResult = APPL_RESULT_AOK;
                tranRecord = read.record().orElseThrow(() -> new IllegalStateException("A read of DD "
                        + TRANFILE_DD_NAME + " reported file status " + FileStatus.toStatusImage(status)
                        + " and carried no record. The two are contradictory: a successful READ leaves "
                        + "TRAN-RECORD populated, which is what " + PROGRAM_NAME + ":180 displays."));
                recordsRead++;
            } else if (FileStatus.isEndOfFile(status)) {
                applResult = APPL_RESULT_EOF;
            } else {
                applResult = APPL_RESULT_FATAL;
            }

            if (applAok(applResult)) {
                return;
            }

            if (applEof(applResult)) {
                endOfFile = AT_END_OF_FILE;
                return;
            }

            reportIoFailure(sysout, ERROR_READING_TRANSACTION_FILE, status);
            throw abendProgram(sysout, ERROR_READING_TRANSACTION_FILE, status);
        }

        private void lookupXref() {
            CardXrefRepository.ReadResult read = cardXrefRepository.readByCardNumber(xrefCardNumberKey);
            if (read.isFound()) {
                xrefRecord = read.record().orElseThrow(() -> new IllegalStateException("A keyed read of "
                        + "DD " + CARDXREF_DD_NAME + " reported success and carried no record, which "
                        + "are contradictory: " + PROGRAM_NAME + ":364 moves XREF-ACCT-ID out of the "
                        + "area the read populates."));
                return;
            }
            if (!read.isNotFound()) {
                // The FILE STATUS item is set, control passes the END-READ, and the paragraph exits with
                // the record area UNCHANGED - which the report then reads, exactly as the source does.
                noteReadFailedOtherThanInvalidKey(CARDXREF_DD_NAME, read.status());
                return;
            }
            reportIoFailure(sysout, INVALID_CARD_NUMBER + xrefCardNumberKey, IO_STATUS_INVALID_KEY);
            throw abendProgram(sysout, INVALID_CARD_NUMBER + xrefCardNumberKey, IO_STATUS_INVALID_KEY);
        }

        private void lookupTranType() {
            TranTypeRepository.ReadResult read = tranTypeRepository.readByTranType(tranTypeKey);
            if (read.isFound()) {
                tranTypeRecord = read.record().orElseThrow(() -> new IllegalStateException("A keyed "
                        + "read of DD " + TRANTYPE_DD_NAME + " reported success and carried no record, "
                        + "which are contradictory: " + PROGRAM_NAME + ":366 moves TRAN-TYPE-DESC out "
                        + "of the area the read populates."));
                return;
            }
            if (!read.isNotFound()) {
                // The FILE STATUS item is set, control passes the END-READ, and the paragraph exits with
                // the record area UNCHANGED - which the report then reads, exactly as the source does.
                noteReadFailedOtherThanInvalidKey(TRANTYPE_DD_NAME, read.status());
                return;
            }
            String displayed = INVALID_TRANSACTION_TYPE + read.keyImage();
            reportIoFailure(sysout, displayed, IO_STATUS_INVALID_KEY);
            throw abendProgram(sysout, displayed, IO_STATUS_INVALID_KEY);
        }

        private void lookupTranCategory() {
            TranCategoryRepository.ReadResult read =
                    tranCategoryRepository.readByKey(tranCatTypeCode, tranCatCode);
            if (read.isFound()) {
                tranCategoryRecord = read.record().orElseThrow(() -> new IllegalStateException("A keyed "
                        + "read of DD " + TRANCATG_DD_NAME + " reported success and carried no record, "
                        + "which are contradictory: " + PROGRAM_NAME + ":368 moves TRAN-CAT-TYPE-DESC "
                        + "out of the area the read populates."));
                return;
            }
            if (!read.isNotFound()) {
                // The FILE STATUS item is set, control passes the END-READ, and the paragraph exits with
                // the record area UNCHANGED - which the report then reads, exactly as the source does.
                noteReadFailedOtherThanInvalidKey(TRANCATG_DD_NAME, read.status());
                return;
            }
            String displayed = INVALID_TRAN_CATG_KEY
                    + tranCategoryRepository.keyImage(tranCatTypeCode, tranCatCode);
            reportIoFailure(sysout, displayed, IO_STATUS_INVALID_KEY);
            throw abendProgram(sysout, displayed, IO_STATUS_INVALID_KEY);
        }

        private void noteReadFailedOtherThanInvalidKey(String ddName, String status) {
            LOG.error("A keyed read of DD " + ddName + " reported file status "
                    + FileStatus.toStatusImage(status) + ", which is not the INVALID KEY condition, so "
                    + PROGRAM_NAME + "'s INVALID KEY arm does not run and no status is substituted for "
                    + "this one. Every one of these files declares a FILE STATUS item in its SELECT and "
                    + "the program has no USE AFTER ERROR declarative, so control passes the END-READ "
                    + "and the paragraph reaches its EXIT: the record area is left exactly as it was and "
                    + "the report goes on to compose a line from it. Reported here rather than on the "
                    + "SYSOUT channel, because the DISPLAY belongs to the INVALID KEY arm and this is "
                    + "not that arm - emitting it would put a line in the report's output that the "
                    + "program never wrote.");
        }

        private void openTranFile() {
            var binding = batchConfig.datasetBinding(JOB_KEY, TRANFILE_DD_NAME);
            tranFile = transactionRepository.openInput(binding);
            checkMoveFormStatus(tranFile.openStatus(), ERROR_OPENING_TRANFILE);
        }

        private void openReptFile() {
            reportFile = reportSink == null
                    ? tranReportWriter.openOutput()
                    : tranReportWriter.openOutput(reportSink);
            checkMoveFormStatus(statusOf(reportFile.openOutcome()), ERROR_OPENING_REPTFILE);
        }

        private void openCardXref() {
            xrefCursor = cardXrefRepository.openBrowse();
            checkMoveFormStatus(xrefCursor.openStatus(), ERROR_OPENING_CROSS_REF_FILE);
        }

        private void openTranType() {
            checkMoveFormStatus(tranTypeRepository.open(), ERROR_OPENING_TRANSACTION_TYPE_FILE);
        }

        private void openTranCatg() {
            checkMoveFormStatus(tranCategoryRepository.open(), ERROR_OPENING_TRANSACTION_CATG_FILE);
        }

        private void openDateParm() {
            checkMoveFormStatus(dateParmReader.open(), ERROR_OPENING_DATE_PARM_FILE);
        }

        private void closeTranFile() {
            // :515 ADD 8 TO ZERO GIVING APPL-RESULT. - dead, and preserved.
            int applResult = APPL_RESULT_ASSUMED_FAILURE;

            String status = tranFile.closeInput();

            if (FileStatus.isOk(status)) {
                applResult -= applResult;
            } else {
                applResult = APPL_RESULT_FATAL;
            }

            if (!applAok(applResult)) {
                reportIoFailure(sysout, ERROR_CLOSING_POSTED_TRANSACTION_FILE, status);
                throw abendProgram(sysout, ERROR_CLOSING_POSTED_TRANSACTION_FILE, status);
            }
        }

        private void closeReptFile() {
            // :533 ADD 8 TO ZERO GIVING APPL-RESULT. - dead, and preserved.
            int applResult = APPL_RESULT_ASSUMED_FAILURE;

            String status = statusOf(reportFile.closeOutput());

            if (FileStatus.isOk(status)) {
                applResult -= applResult;
            } else {
                applResult = APPL_RESULT_FATAL;
            }

            if (!applAok(applResult)) {
                reportIoFailure(sysout, ERROR_CLOSING_REPORT_FILE, status);
                throw abendProgram(sysout, ERROR_CLOSING_REPORT_FILE, status);
            }
        }

        private void closeCardXref() {
            checkMoveFormStatus(xrefCursor.closeBrowse(), ERROR_CLOSING_CROSS_REF_FILE);
        }

        private void closeTranType() {
            checkMoveFormStatus(tranTypeRepository.close(), ERROR_CLOSING_TRANSACTION_TYPE_FILE);
        }

        private void closeTranCatg() {
            checkMoveFormStatus(tranCategoryRepository.close(), ERROR_CLOSING_TRANSACTION_CATG_FILE);
        }

        private void closeDateParm() {
            checkMoveFormStatus(dateParmReader.close(), ERROR_CLOSING_DATE_PARM_FILE);
        }

        private void checkMoveFormStatus(String status, String message) {
            // MOVE 8 TO APPL-RESULT. - dead, and preserved.
            int applResult = APPL_RESULT_ASSUMED_FAILURE;

            if (FileStatus.isOk(status)) {
                applResult = APPL_RESULT_AOK;
            } else {
                applResult = APPL_RESULT_FATAL;
            }

            if (!applAok(applResult)) {
                reportIoFailure(sysout, message, status);
                throw abendProgram(sysout, message, status);
            }
        }

        private static BigDecimal addToTotal(BigDecimal total, BigDecimal addend) {
            return CobolDecimal.storeAtPicture(total.add(addend), TOTAL_INTEGER_DIGITS, TOTAL_SCALE);
        }

        private String zonedTotalImage(BigDecimal total) {
            return codec.encodeSignedScaled(total, TOTAL_INTEGER_DIGITS, TOTAL_SCALE);
        }
    }

    private static boolean applAok(int applResult) {
        return applResult == APPL_RESULT_AOK;
    }

    private static boolean applEof(int applResult) {
        return applResult == APPL_RESULT_EOF;
    }

    private static String statusOf(FileStatus.Outcome outcome) {
        return outcome.batchStatus().orElse(PERMANENT_ERROR_STATUS);
    }

    private static void reportIoFailure(SysoutSink sysout, String message, String status) {
        sysout.display(message);
        sysout.display(FileStatus.toDisplayLine(status));
    }

    private static AbendException abendProgram(SysoutSink sysout, String message, String status) {
        sysout.display(AbendException.ABEND_DISPLAY_TEXT);
        return AbendException.standard(PROGRAM_NAME, APPL_RESULT_FATAL,
                message + " - " + FileStatus.toDisplayLine(status));
    }

    @FunctionalInterface
    public interface SysoutSink {
        void display(String line);
    }

    /**
     * A {@link SysoutSink} over an {@link OutputStream}, encoding each line in the dataset code page and
     * flushing it.
     */
    private static final class StreamSysoutSink implements SysoutSink {
        private final OutputStream destination;

        private final FixedWidthCodec codec;

        private StreamSysoutSink(OutputStream destination, FixedWidthCodec codec) {
            this.destination = Objects.requireNonNull(destination, "A destination stream is required to "
                    + "write the SYSOUT lines of " + PROGRAM_NAME);
            this.codec = Objects.requireNonNull(codec, "A codec is required: a SYSOUT line is written in "
                    + "a named code page, never in the platform default");
        }

        /**
         * Encodes the line and its terminator and writes them.
         *
         * @param line the line to emit; never {@code null}
         * @throws NullPointerException if {@code line} is {@code null}
         * @throws UncheckedIOException if the destination refuses the bytes
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

    public record ExecutionSummary(int returnCode,
                                   int recordsRead,
                                   int detailLinesWritten,
                                   int reportLinesWritten,
                                   long lineCounter,
                                   BigDecimal grandTotal) {
        public ExecutionSummary {
            Objects.requireNonNull(grandTotal, "WS-GRAND-TOTAL is required on a summary; the program "
                    + "initialises it to zero at app/cbl/CBTRN03C.cbl:136, so it is never absent");
            requireNotNegative(recordsRead, "recordsRead");
            requireNotNegative(detailLinesWritten, "detailLinesWritten");
            requireNotNegative(reportLinesWritten, "reportLinesWritten");
            if (lineCounter < 0) {
                throw new IllegalArgumentException("WS-LINE-COUNTER is PIC 9(09) COMP-3, an unsigned "
                        + "field, so " + lineCounter + " is not a value a run of " + PROGRAM_NAME
                        + " could have left in it.");
            }
            if (detailLinesWritten > reportLinesWritten) {
                throw new IllegalArgumentException("A run reported " + detailLinesWritten + " detail "
                        + "line(s) out of " + reportLinesWritten + " report line(s), which is not "
                        + "possible: every detail line is a report line, and a report that has any "
                        + "detail line also has the four header lines that preceded it.");
            }
            if (grandTotal.scale() != TOTAL_SCALE) {
                throw new IllegalArgumentException("WS-GRAND-TOTAL is PIC S9(09)V99, so its scale is "
                        + TOTAL_SCALE + " and not " + grandTotal.scale() + ". Route the value through "
                        + "CobolDecimal so the scale and the rounding mode are named in one place.");
            }
        }

        private static void requireNotNegative(int count, String name) {
            if (count < 0) {
                throw new IllegalArgumentException("A count of " + count + " is not possible for "
                        + name + ": a run of " + PROGRAM_NAME + " reads and writes zero or more.");
            }
        }

        /**
         * Whether the run produced a report body at all.
         *
         * @return {@code true} when at least one line reached the report
         */
        public boolean producedReport() {
            return reportLinesWritten > 0;
        }

        /**
         * How many report lines were not detail lines: headers, rules and total lines.
         *
         * @return {@code reportLinesWritten} less {@code detailLinesWritten}, never negative
         */
        public int structuralLinesWritten() {
            return reportLinesWritten - detailLinesWritten;
        }
    }
}

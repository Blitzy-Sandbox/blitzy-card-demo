package com.carddemo.batch.jobs;

import com.carddemo.batch.processors.TransactionReportProcessor;
import com.carddemo.batch.processors.TransactionReportProcessor.ReportLine;
import com.carddemo.exception.FileAccessException;
import com.carddemo.model.entity.Transaction;
import com.carddemo.observability.MetricsConfig;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.service.report.ReportSubmissionService.ReportJobMessage;

import io.awspring.cloud.sqs.annotation.SqsListener;
import io.micrometer.core.instrument.MeterRegistry;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecutionException;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemStreamWriter;
import org.springframework.batch.item.support.ListItemReader;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.transaction.PlatformTransactionManager;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Spring Batch re-host of the mainframe transaction-detail report, combining JCL job
 * {@code app/jcl/TRANREPT.jcl} and COBOL program {@code app/cbl/CBTRN03C.cbl}, with the
 * 133-byte report layout taken from copybooks {@code app/cpy/CVTRA07Y.cpy} (report record and
 * the page/account/grand total lines) and {@code app/cpy/COTTL01Y.cpy} (branded title lines).
 * Source commit {@code 27d6c6f}; the COBOL/JCL is a behavioral reference and is never copied.
 *
 * <p>The original {@code TRANREPT.jcl} ran three stages: {@code STEP05R} backed up the master
 * {@code TRANSACT} KSDS to {@code TRANSACT.BKUP(+1)}; a {@code DFSORT} step filtered that backup to
 * a {@code [PARM-START-DATE, PARM-END-DATE]} window and sorted ascending by {@code TRAN-CARD-NUM}
 * into {@code TRANSACT.DALY(+1)}; and {@code STEP10R EXEC PGM=CBTRN03C} read the filtered, sorted
 * transactions plus the {@code CARDXREF}, {@code TRANTYPE}, {@code TRANCATG}, and {@code DATEPARM}
 * reference files and wrote the formatted report to {@code TRANREPT(+1)} ({@code LRECL=133}).</p>
 *
 * <p>This configuration exposes a single chunk-oriented {@link Job} bean ({@value #JOB_NAME}) whose
 * one step streams posted transactions in the report window, enriches each into a report detail
 * line, and renders the byte-faithful 133-column report to Amazon S3 (the {@code TRANREPT}
 * equivalent). The DFSORT date filter and card-major ordering are reproduced in the step reader and
 * the {@link TransactionReportProcessor}; the control-break totals, pagination, and fixed-width
 * formatting of {@code CBTRN03C} are reproduced in the inline report writer.</p>
 *
 * <p><b>Online-to-batch bridge (Decision D-004).</b> The CICS {@code WRITEQ TD} report-submission
 * mechanism is re-platformed to an SQS FIFO queue: {@code ReportSubmissionService} publishes a
 * {@link ReportJobMessage} to {@code carddemo-report-jobs.fifo}, and the {@link #onReportJobMessage}
 * listener in this class consumes it and launches {@value #JOB_NAME} with the requested date window
 * as job parameters. The queue name resolves from configuration; the LocalStack endpoint is never
 * hardcoded (it is supplied by {@code application*.yml} and {@code AwsConfig}).</p>
 *
 * <p><b>Inline reader and writer.</b> No sibling report reader or report writer exists, so both are
 * defined inline here: a {@link StepScope step-scoped} {@link ListItemReader} over
 * {@link TransactionRepository#findByProcessingDateRange(String, String)} (re-sorted card-major for
 * the control break) and a job-local {@link ItemStreamWriter} that buffers the rendered report and
 * uploads it once on {@code close()}. The rationale for keeping them inline, and for the corrected
 * total roll-up and the added title block (both noted below), is recorded in {@code DECISION_LOG.md}
 * rather than in code comments.</p>
 *
 * <p><b>Faithful, with two deliberate refinements.</b> The 133-byte line layout, field offsets,
 * column headers, the all-dashes separator, and the three total tiers are preserved byte-faithfully
 * from {@code CVTRA07Y}. Two refinements over the literal {@code CBTRN03C} behavior are applied per
 * this file's specification: (1) the totals roll up exactly once with no double counting, and the
 * final account total is flushed alongside the final page and grand totals on {@code close()}
 * (whereas {@code CBTRN03C} omitted the final account total and re-added the last amount at
 * end-of-file); and (2) the {@code COTTL01Y} title lines are emitted as a branded title block
 * ({@code CBTRN03C} did not reference {@code COTTL01Y}). All monetary accumulation uses
 * {@link BigDecimal} at scale {@value #AMOUNT_SCALE}, and amounts are compared with
 * {@link BigDecimal#compareTo(BigDecimal)} (never {@code equals}).</p>
 *
 * <p>The job is not auto-run ({@code spring.batch.job.enabled=false}); it is launched only by the
 * SQS listener here and by the batch pipeline orchestrator's stage 4b.</p>
 */
@Configuration(value = "transactionReportJobConfig", proxyBeanMethods = false)
public class TransactionReportJob {

    /** Canonical job name; the launch key for the SQS trigger and the pipeline orchestrator. */
    public static final String JOB_NAME = "transactionReportJob";

    /** Canonical step name for the single chunk-oriented report step. */
    public static final String STEP_NAME = "transactionReportStep";

    /** Spring bean name of the inline step-scoped report reader. */
    public static final String READER_BEAN = "transactionReportReader";

    /** Spring bean name of the inline step-scoped report writer. */
    public static final String WRITER_BEAN = "transactionReportWriter";

    /** Job-parameter key for the inclusive report-window start date ({@code yyyy-MM-dd}). */
    public static final String PARAM_START_DATE = "startDate";

    /** Job-parameter key for the inclusive report-window end date ({@code yyyy-MM-dd}). */
    public static final String PARAM_END_DATE = "endDate";

    /** Job-parameter key for the report type ({@code Monthly}/{@code Yearly}/{@code Custom}). */
    public static final String PARAM_REPORT_TYPE = "reportType";

    /** Job-parameter key for the per-submission unique run id (ensures a distinct JobInstance). */
    public static final String PARAM_RUN_ID = "runId";

    /** Chunk commit interval for the report step. */
    static final int CHUNK_SIZE = 100;

    /** Lines per report page before a page total and a fresh header block are emitted ({@code WS-PAGE-SIZE}). */
    static final int PAGE_SIZE = 20;

    /** Fixed report record length in bytes ({@code TRANREPT} {@code LRECL=133}). */
    static final int LINE_WIDTH = 133;

    /** Implied decimal positions for every monetary amount and total ({@code PIC ...V99}). */
    static final int AMOUNT_SCALE = 2;

    /** Total width of an edited amount field including its leading sign position ({@code PIC -ZZZ,ZZZ,ZZZ.ZZ}). */
    static final int AMOUNT_FIELD_WIDTH = 15;

    /** Width of the zero-suppressed magnitude portion of an edited amount (the field minus the sign position). */
    static final int MAGNITUDE_WIDTH = AMOUNT_FIELD_WIDTH - 1;

    /** {@code COTTL01Y CCDA-TITLE01} — {@code PIC X(40)}, exactly 40 characters. */
    static final String TITLE_LINE_1 = "      AWS Mainframe Modernization       ";

    /** {@code COTTL01Y CCDA-TITLE02} — {@code PIC X(40)}, exactly 40 characters. */
    static final String TITLE_LINE_2 = "              CardDemo                  ";

    /** {@code COTTL01Y CCDA-THANK-YOU} — {@code PIC X(40)}, exactly 40 characters; emitted as the report footer. */
    static final String THANK_YOU_LINE = "Thank you for using CCDA application... ";

    private static final Logger LOGGER = LoggerFactory.getLogger(TransactionReportJob.class);

    private final TransactionRepository transactionRepository;
    private final S3Client s3Client;
    private final MeterRegistry meterRegistry;
    private final JobLauncher jobLauncher;
    private final Job transactionReportJob;
    private final String outputBucket;
    private final String reportObjectKey;

    /**
     * Creates the report-job configuration. AWS region, endpoint, and credentials are resolved by
     * {@link com.carddemo.config.AwsConfig} from configuration (LocalStack for the local/test
     * profiles), never here; this constructor only binds collaborators and externalized resource
     * names. The {@link Job} is injected lazily and by qualified name to break the self-reference
     * that would otherwise arise because this class both defines {@value #JOB_NAME} and launches it
     * from the SQS listener.
     *
     * @param transactionRepository repository supplying the in-window transactions for the report
     * @param s3Client              synchronous S3 client used to upload the rendered report object
     * @param meterRegistry         Micrometer registry for the processed-records counter
     * @param jobLauncher           Spring Batch launcher (Boot auto-configured) used by the SQS trigger
     * @param transactionReportJob  the {@value #JOB_NAME} bean, injected lazily to avoid a cycle
     * @param outputBucket          batch-output S3 bucket, from {@code carddemo.aws.s3.bucket-output}
     * @param reportObjectKey       S3 object key for the report, from {@code carddemo.batch.report.object-key}
     */
    public TransactionReportJob(
            TransactionRepository transactionRepository,
            S3Client s3Client,
            MeterRegistry meterRegistry,
            JobLauncher jobLauncher,
            @Lazy @Qualifier(JOB_NAME) Job transactionReportJob,
            @Value("${carddemo.aws.s3.bucket-output:carddemo-batch-output}") String outputBucket,
            @Value("${carddemo.batch.report.object-key:TRANREPT}") String reportObjectKey) {
        this.transactionRepository = transactionRepository;
        this.s3Client = s3Client;
        this.meterRegistry = meterRegistry;
        this.jobLauncher = jobLauncher;
        this.transactionReportJob = transactionReportJob;
        this.outputBucket = outputBucket;
        this.reportObjectKey = reportObjectKey;
    }

    /**
     * SQS FIFO consumer for the online-to-batch report bridge (Decision D-004). Receives a
     * {@link ReportJobMessage} (deserialized from the JSON body by the framework) from the queue
     * named by {@code carddemo.aws.sqs.report-queue} and launches {@value #JOB_NAME} with the
     * requested date window. A unique {@value #PARAM_RUN_ID} parameter makes every submission a
     * distinct {@code JobInstance}, so repeated requests for the same window each produce a report.
     *
     * <p>Launch-level failures are logged and not rethrown: the queue is FIFO with a unique run id
     * per message, so the benign duplicate cases cannot occur, and rethrowing would only trigger an
     * unproductive redelivery. Step-level failures are captured in the returned {@code JobExecution}
     * status (not thrown by {@link JobLauncher#run}).</p>
     *
     * @param message the report-submission message carrying the report type and date window
     */
    @SqsListener("${carddemo.aws.sqs.report-queue:carddemo-report-jobs.fifo}")
    public void onReportJobMessage(ReportJobMessage message) {
        if (message == null) {
            LOGGER.warn("Received a null report-job message; ignoring");
            return;
        }
        JobParameters parameters = new JobParametersBuilder()
                .addString(PARAM_START_DATE, safe(message.startDate()))
                .addString(PARAM_END_DATE, safe(message.endDate()))
                .addString(PARAM_REPORT_TYPE, safe(message.reportType()))
                .addString(PARAM_RUN_ID, UUID.randomUUID().toString())
                .toJobParameters();
        try {
            LOGGER.info("Launching {} for report window [{} .. {}] type={}",
                    JOB_NAME, message.startDate(), message.endDate(), message.reportType());
            jobLauncher.run(transactionReportJob, parameters);
        } catch (JobExecutionException ex) {
            LOGGER.error("Failed to launch {} for report window [{} .. {}]",
                    JOB_NAME, message.startDate(), message.endDate(), ex);
        }
    }

    /**
     * Null-safe coercion for a job-parameter value: a {@code null} becomes an empty string so
     * {@link JobParametersBuilder#addString(String, String)} always receives a non-null value.
     *
     * @param value the raw message field
     * @return {@code value}, or {@code ""} when {@code value} is {@code null}
     */
    private static String safe(String value) {
        return value == null ? "" : value;
    }

    // ---------------------------------------------------------------------------------------------
    // Spring Batch wiring: inline reader, inline writer factory, step, and job.
    // ---------------------------------------------------------------------------------------------

    /**
     * Inline step-scoped reader over the posted transactions that fall in the report window.
     * Reproduces the {@code TRANREPT.jcl} DFSORT {@code INCLUDE COND} date filter and the
     * {@code SORT FIELDS=(TRAN-CARD-NUM,A)} ordering: it fetches the window via
     * {@link TransactionRepository#findByProcessingDateRange(String, String)} (which orders by
     * {@code tranId}) and re-sorts the result card-major ({@code tranCardNum}, then {@code tranId})
     * so the writer's control break by card matches {@code CBTRN03C}.
     *
     * @param startDate inclusive window start ({@code yyyy-MM-dd}) from the job parameters
     * @param endDate   inclusive window end ({@code yyyy-MM-dd}) from the job parameters
     * @return a {@link ListItemReader} over the card-major-ordered transactions in the window
     */
    @Bean(name = READER_BEAN)
    @StepScope
    public ListItemReader<Transaction> transactionReportReader(
            @Value("#{jobParameters['startDate']}") String startDate,
            @Value("#{jobParameters['endDate']}") String endDate) {
        List<Transaction> transactions =
                new ArrayList<>(transactionRepository.findByProcessingDateRange(startDate, endDate));
        transactions.sort(
                Comparator.comparing(Transaction::getTranCardNum,
                                Comparator.nullsLast(Comparator.<String>naturalOrder()))
                        .thenComparing(Transaction::getTranId,
                                Comparator.nullsLast(Comparator.<String>naturalOrder())));
        LOGGER.info("Report reader loaded {} transaction(s) for window [{} .. {}]",
                transactions.size(), startDate, endDate);
        return new ListItemReader<>(transactions);
    }

    /**
     * Inline step-scoped report writer factory. The writer buffers the rendered 133-byte report
     * across all chunks and uploads it once to S3 on {@code close()} (hence {@link ItemStreamWriter}).
     * The output bucket and object key are externalized ({@code carddemo.aws.s3.bucket-output} and
     * {@code carddemo.batch.report.object-key}); the date window is taken from the job parameters so
     * the report's {@code REPORT-NAME-HEADER} date range matches the requested window.
     *
     * @param startDate inclusive window start ({@code yyyy-MM-dd}) from the job parameters
     * @param endDate   inclusive window end ({@code yyyy-MM-dd}) from the job parameters
     * @return a job-local {@link ItemStreamWriter} that renders and uploads the report
     */
    @Bean(name = WRITER_BEAN)
    @StepScope
    public ItemStreamWriter<ReportLine> transactionReportWriter(
            @Value("#{jobParameters['startDate']}") String startDate,
            @Value("#{jobParameters['endDate']}") String endDate) {
        return new ReportItemWriter(
                s3Client, outputBucket, reportObjectKey, meterRegistry, startDate, endDate);
    }

    /**
     * The single chunk-oriented step: read posted transactions in the window, enrich each into a
     * {@link ReportLine} via {@link TransactionReportProcessor}, and stream them to the inline report
     * writer. The writer is also registered as a step stream so its {@code open}/{@code update}/
     * {@code close} lifecycle (buffer init, progress checkpoint, and the S3 upload) is driven by the
     * step.
     *
     * @param jobRepository      the batch job repository (Spring Boot auto-configured)
     * @param transactionManager the batch transaction manager (Spring Boot auto-configured)
     * @param reader             the inline step-scoped report reader
     * @param processor          the transaction-to-report-line processor
     * @param reportWriter       the inline step-scoped report writer (also registered as a stream)
     * @return the configured report step
     */
    @Bean
    public Step transactionReportStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            @Qualifier(READER_BEAN) ItemReader<Transaction> reader,
            TransactionReportProcessor processor,
            @Qualifier(WRITER_BEAN) ItemStreamWriter<ReportLine> reportWriter) {
        return new StepBuilder(STEP_NAME, jobRepository)
                .<Transaction, ReportLine>chunk(CHUNK_SIZE, transactionManager)
                .reader(reader)
                .processor(processor)
                .writer(reportWriter)
                .stream(reportWriter)
                .build();
    }

    /**
     * The transaction-report {@link Job}: a single start step. Not auto-run
     * ({@code spring.batch.job.enabled=false}); launched only by {@link #onReportJobMessage} and the
     * batch pipeline orchestrator (stage 4b). Each launch supplies a unique {@code runId} parameter so
     * every submission is a distinct {@code JobInstance}.
     *
     * @param jobRepository         the batch job repository (Spring Boot auto-configured)
     * @param transactionReportStep the report step bean
     * @return the configured report job
     */
    @Bean(name = JOB_NAME)
    public Job transactionReportJob(JobRepository jobRepository, Step transactionReportStep) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(transactionReportStep)
                .build();
    }

    // ---------------------------------------------------------------------------------------------
    // Fixed-width formatters reproducing the CVTRA07Y / COTTL01Y layout. Package-private and static
    // so they can be unit-tested directly without bootstrapping the Spring context.
    // ---------------------------------------------------------------------------------------------

    /**
     * Formats a monetary value to the COBOL edited-numeric pattern used throughout the report. The
     * detail amount uses the {@code PIC -ZZZ,ZZZ,ZZZ.ZZ} mask (leading space for non-negative); the
     * page/account/grand totals use {@code PIC +ZZZ,ZZZ,ZZZ.ZZ} (leading {@code '+'} for
     * non-negative). The magnitude is zero-suppressed ({@code Z}): a leading integer zero and the
     * thousands separators that precede the first significant digit become spaces, and a zero value
     * renders as an all-blank magnitude.
     *
     * @param value    the amount; {@code null} is treated as zero
     * @param plusSign {@code true} for the {@code '+'} total mask, {@code false} for the {@code '-'} detail mask
     * @return the {@value #AMOUNT_FIELD_WIDTH}-character edited amount (1 sign position + magnitude)
     */
    static String formatCobolAmount(BigDecimal value, boolean plusSign) {
        BigDecimal scaled = (value == null ? BigDecimal.ZERO : value)
                .setScale(AMOUNT_SCALE, RoundingMode.HALF_EVEN);
        int signum = scaled.signum();
        char sign = (signum < 0) ? '-' : (plusSign ? '+' : ' ');
        String magnitude;
        if (signum == 0) {
            magnitude = " ".repeat(MAGNITUDE_WIDTH);
        } else {
            DecimalFormat decimalFormat =
                    new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US));
            String formatted = decimalFormat.format(scaled.abs());
            if (formatted.startsWith("0.")) {
                formatted = formatted.substring(1);
            }
            magnitude = padLeft(formatted, MAGNITUDE_WIDTH);
        }
        return sign + magnitude;
    }

    /**
     * Right-justifies {@code value} in a field of {@code width} by left-padding with spaces. A value
     * already at least {@code width} long is returned unchanged.
     *
     * @param value the text to right-justify ({@code null} is treated as empty)
     * @param width the target field width
     * @return the right-justified, space-padded field
     */
    static String padLeft(String value, int width) {
        String v = (value == null) ? "" : value;
        return v.length() >= width ? v : " ".repeat(width - v.length()) + v;
    }

    /**
     * Left-justifies {@code value} in a fixed field of {@code width}, space-padding on the right and
     * truncating on the right when too long — i.e., a COBOL {@code MOVE} into a {@code PIC X(width)}.
     *
     * @param value the text to place ({@code null} is treated as empty)
     * @param width the fixed field width
     * @return the left-justified, space-padded (or right-truncated) field
     */
    static String fixed(String value, int width) {
        String v = (value == null) ? "" : value;
        if (v.length() == width) {
            return v;
        }
        return v.length() > width ? v.substring(0, width) : v + " ".repeat(width - v.length());
    }

    /**
     * Renders the account id as the report's {@code PIC X(11)} field sourced from a {@code 9(11)}
     * value: an 11-digit zero-padded number, or 11 spaces when the cross-reference yielded no account.
     *
     * @param accountId the enriched account id ({@code null} when unresolved)
     * @return the 11-character account-id field
     */
    static String accountIdField(Long accountId) {
        if (accountId == null) {
            return " ".repeat(11);
        }
        String digits = String.format(Locale.ROOT, "%011d", accountId);
        return digits.length() > 11 ? digits.substring(digits.length() - 11) : digits;
    }

    /**
     * Renders the category code as the report's {@code PIC 9(04)} field: a 4-digit zero-padded number
     * ({@code null} renders as {@code 0000}).
     *
     * @param categoryCode the enriched category code ({@code null} when unresolved)
     * @return the 4-character category-code field
     */
    static String categoryCodeField(Integer categoryCode) {
        int code = (categoryCode == null) ? 0 : categoryCode;
        String digits = String.format(Locale.ROOT, "%04d", Math.abs(code));
        return digits.length() > 4 ? digits.substring(digits.length() - 4) : digits;
    }

    /**
     * Renders a {@link ReportLine} to the {@code CVTRA07Y TRANSACTION-DETAIL-REPORT} layout: a
     * 114-character content string at the copybook's fixed offsets (transaction id 16, account id 11,
     * type code 2 + {@code '-'} + type description 15, category code 4 + {@code '-'} + category
     * description 29, source 10, then the edited amount), which the caller pads to
     * {@value #LINE_WIDTH}.
     *
     * @param line the enriched report line
     * @return the 114-character detail content
     */
    static String buildDetailLine(ReportLine line) {
        StringBuilder sb = new StringBuilder(LINE_WIDTH);
        sb.append(fixed(line.tranId(), 16));
        sb.append(' ');
        sb.append(accountIdField(line.accountId()));
        sb.append(' ');
        sb.append(fixed(line.typeCode(), 2));
        sb.append('-');
        sb.append(fixed(line.typeDescription(), 15));
        sb.append(' ');
        sb.append(categoryCodeField(line.categoryCode()));
        sb.append('-');
        sb.append(fixed(line.categoryDescription(), 29));
        sb.append(' ');
        sb.append(fixed(line.source(), 10));
        sb.append("    ");
        sb.append(formatCobolAmount(line.amount(), false));
        sb.append("  ");
        return sb.toString();
    }

    /**
     * Renders the {@code CVTRA07Y REPORT-NAME-HEADER}: the short name ({@code DALYREPT}), the long
     * name ({@code Daily Transaction Report}), and the {@code Date Range: <start> to <end>} window.
     *
     * @param startDate the inclusive window start ({@code yyyy-MM-dd})
     * @param endDate   the inclusive window end ({@code yyyy-MM-dd})
     * @return the report name / date-range header content
     */
    static String buildNameHeader(String startDate, String endDate) {
        StringBuilder sb = new StringBuilder(LINE_WIDTH);
        sb.append(fixed("DALYREPT", 38));
        sb.append(fixed("Daily Transaction Report", 41));
        sb.append("Date Range: ");
        sb.append(fixed(startDate, 10));
        sb.append(" to ");
        sb.append(fixed(endDate, 10));
        return sb.toString();
    }

    /**
     * Renders the {@code CVTRA07Y TRANSACTION-HEADER-1} column-heading line.
     *
     * @return the column-header content
     */
    static String buildColumnHeader() {
        StringBuilder sb = new StringBuilder(LINE_WIDTH);
        sb.append(fixed("Transaction ID", 17));
        sb.append(fixed("Account ID", 12));
        sb.append(fixed("Transaction Type", 19));
        sb.append(fixed("Tran Category", 35));
        sb.append(fixed("Tran Source", 14));
        sb.append(' ');
        sb.append(fixed("        Amount", 16));
        return sb.toString();
    }

    /**
     * Renders the {@code CVTRA07Y TRANSACTION-HEADER-2} separator: exactly {@value #LINE_WIDTH} dashes
     * (the copybook's {@code PIC X(133) VALUE ALL '-'}, which fixes the report width).
     *
     * @return a string of {@value #LINE_WIDTH} {@code '-'} characters
     */
    static String separatorLine() {
        return "-".repeat(LINE_WIDTH);
    }

    /**
     * Renders the {@code CVTRA07Y REPORT-PAGE-TOTALS} line: the {@code Page Total} label, a run of
     * dots, and the edited {@code +} total.
     *
     * @param total the accumulated page total
     * @return the page-total content
     */
    static String buildPageTotalLine(BigDecimal total) {
        return fixed("Page Total", 11) + ".".repeat(86) + formatCobolAmount(total, true);
    }

    /**
     * Renders the {@code CVTRA07Y REPORT-ACCOUNT-TOTALS} line: the {@code Account Total} label, a run
     * of dots, and the edited {@code +} total.
     *
     * @param total the accumulated account total
     * @return the account-total content
     */
    static String buildAccountTotalLine(BigDecimal total) {
        return fixed("Account Total", 13) + ".".repeat(84) + formatCobolAmount(total, true);
    }

    /**
     * Renders the {@code CVTRA07Y REPORT-GRAND-TOTALS} line: the {@code Grand Total} label, a run of
     * dots, and the edited {@code +} total.
     *
     * @param total the accumulated grand total
     * @return the grand-total content
     */
    static String buildGrandTotalLine(BigDecimal total) {
        return fixed("Grand Total", 11) + ".".repeat(86) + formatCobolAmount(total, true);
    }

    /**
     * Pads or truncates {@code content} to exactly {@value #LINE_WIDTH} characters so every emitted
     * record matches the fixed {@code TRANREPT} {@code LRECL}.
     *
     * @param content the raw line content
     * @return the content normalized to {@value #LINE_WIDTH} characters
     */
    static String padToLineWidth(String content) {
        String c = (content == null) ? "" : content;
        if (c.length() == LINE_WIDTH) {
            return c;
        }
        return c.length() > LINE_WIDTH
                ? c.substring(0, LINE_WIDTH)
                : c + " ".repeat(LINE_WIDTH - c.length());
    }

    // ---------------------------------------------------------------------------------------------
    // Inline report writer.
    // ---------------------------------------------------------------------------------------------

    /**
     * Inline {@link ItemStreamWriter} that renders the byte-faithful 133-byte transaction report and
     * uploads it once to S3 on {@code close()} (the {@code TRANREPT(+1)} equivalent). It reproduces
     * the {@code CBTRN03C} report mechanics: a branded title and column-header block at the top of
     * every page, fixed-width detail lines, a control break by card that emits an account total, a
     * page break every {@value #PAGE_SIZE} lines that emits a page total, and a closing grand total —
     * with the two refinements documented on the enclosing class (single-count roll-up; the final
     * account total flushed on close).
     *
     * <p>All monetary state is held as {@link BigDecimal} at scale {@value #AMOUNT_SCALE}. The
     * accumulators are checkpointed to the {@link ExecutionContext} in {@code update()} for batch
     * bookkeeping. Because the rendered report is held in memory and cannot be serialized, each
     * execution rebuilds the whole report in {@code open()}; combined with the unique per-submission
     * {@code runId} (each launch is a new {@code JobInstance}), a partial restart of the same instance
     * does not occur in normal operation, so the report is always assembled in full.</p>
     */
    static final class ReportItemWriter implements ItemStreamWriter<ReportLine> {

        private static final Logger WRITER_LOG = LoggerFactory.getLogger(ReportItemWriter.class);
        private static final String CONTENT_TYPE = "text/plain";
        private static final String CTX_PAGE_TOTAL = "report.pageTotal";
        private static final String CTX_ACCOUNT_TOTAL = "report.accountTotal";
        private static final String CTX_GRAND_TOTAL = "report.grandTotal";
        private static final String CTX_LINE_COUNTER = "report.lineCounter";

        private final S3Client s3Client;
        private final String bucket;
        private final String objectKey;
        private final MeterRegistry meterRegistry;
        private final String startDate;
        private final String endDate;

        private StringBuilder report;
        private BigDecimal pageTotal;
        private BigDecimal accountTotal;
        private BigDecimal grandTotal;
        private long lineCounter;
        private String currentCardNumber;
        private boolean beforeFirstRecord;
        private boolean uploaded;

        ReportItemWriter(S3Client s3Client, String bucket, String objectKey,
                MeterRegistry meterRegistry, String startDate, String endDate) {
            this.s3Client = s3Client;
            this.bucket = bucket;
            this.objectKey = objectKey;
            this.meterRegistry = meterRegistry;
            this.startDate = startDate;
            this.endDate = endDate;
        }

        @Override
        public void open(ExecutionContext executionContext) {
            this.report = new StringBuilder();
            this.pageTotal = zeroMoney();
            this.accountTotal = zeroMoney();
            this.grandTotal = zeroMoney();
            this.lineCounter = 0L;
            this.currentCardNumber = null;
            this.beforeFirstRecord = true;
            this.uploaded = false;
        }

        @Override
        public void update(ExecutionContext executionContext) {
            executionContext.putString(CTX_PAGE_TOTAL, pageTotal.toPlainString());
            executionContext.putString(CTX_ACCOUNT_TOTAL, accountTotal.toPlainString());
            executionContext.putString(CTX_GRAND_TOTAL, grandTotal.toPlainString());
            executionContext.putLong(CTX_LINE_COUNTER, lineCounter);
        }

        @Override
        public void write(Chunk<? extends ReportLine> chunk) {
            for (ReportLine line : chunk) {
                processLine(line);
                MetricsConfig.recordsProcessed(meterRegistry).increment();
            }
        }

        @Override
        public void close() {
            if (uploaded) {
                return;
            }
            if (beforeFirstRecord) {
                writeHeaderBlock();
                beforeFirstRecord = false;
            } else {
                writeAccountTotals();
            }
            writePageTotals();
            writeGrandTotals();
            appendLine(THANK_YOU_LINE);
            uploadReport();
            this.uploaded = true;
            this.report = null;
        }

        /**
         * Processes one report line: applies the control break by card, the page break, the running
         * page/account totals, and emits the formatted detail line. Mirrors the {@code CBTRN03C} main
         * loop ordering (account total on card change, then page break, then detail).
         */
        private void processLine(ReportLine line) {
            String card = line.cardNumber();
            if (currentCardNumber == null || !currentCardNumber.equals(card)) {
                if (!beforeFirstRecord) {
                    writeAccountTotals();
                }
                currentCardNumber = card;
            }
            if (beforeFirstRecord) {
                writeHeaderBlock();
                beforeFirstRecord = false;
            }
            if (lineCounter % PAGE_SIZE == 0) {
                writePageTotals();
                writeHeaderBlock();
            }
            BigDecimal amount = (line.amount() == null)
                    ? zeroMoney()
                    : line.amount().setScale(AMOUNT_SCALE, RoundingMode.HALF_EVEN);
            pageTotal = pageTotal.add(amount);
            accountTotal = accountTotal.add(amount);
            appendLine(buildDetailLine(line));
        }

        /** Emits the per-page block: the two title lines, the name/date-range header, a blank line, the column header, and the dash separator. */
        private void writeHeaderBlock() {
            appendLine(TITLE_LINE_1);
            appendLine(TITLE_LINE_2);
            appendLine(buildNameHeader(startDate, endDate));
            appendLine("");
            appendLine(buildColumnHeader());
            appendLine(separatorLine());
        }

        /** Emits the page total, rolls it into the grand total, resets the page total, and emits the separator. */
        private void writePageTotals() {
            appendLine(buildPageTotalLine(pageTotal));
            grandTotal = grandTotal.add(pageTotal);
            pageTotal = zeroMoney();
            appendLine(separatorLine());
        }

        /** Emits the account total, resets it, and emits the separator. */
        private void writeAccountTotals() {
            appendLine(buildAccountTotalLine(accountTotal));
            accountTotal = zeroMoney();
            appendLine(separatorLine());
        }

        /** Emits the grand total. */
        private void writeGrandTotals() {
            appendLine(buildGrandTotalLine(grandTotal));
        }

        /** Appends one record, padded to {@value #LINE_WIDTH}, and advances the line counter. */
        private void appendLine(String content) {
            report.append(padToLineWidth(content)).append('\n');
            lineCounter++;
        }

        /** Uploads the assembled report to S3 once, mapping any AWS failure to {@link FileAccessException}. */
        private void uploadReport() {
            byte[] payload = report.toString().getBytes(StandardCharsets.ISO_8859_1);
            try {
                s3Client.putObject(
                        PutObjectRequest.builder()
                                .bucket(bucket)
                                .key(objectKey)
                                .contentType(CONTENT_TYPE)
                                .build(),
                        RequestBody.fromBytes(payload));
                WRITER_LOG.info("Wrote transaction report ({} bytes, {} lines) to s3://{}/{}",
                        payload.length, lineCounter, bucket, objectKey);
            } catch (SdkException ex) {
                WRITER_LOG.error("Failed to upload transaction report to s3://{}/{} ({} bytes)",
                        bucket, objectKey, payload.length, ex);
                throw new FileAccessException(
                        "Failed to write transaction report to S3 location " + bucket + "/" + objectKey, ex);
            }
        }

        /** A zero amount at the canonical money scale. */
        private static BigDecimal zeroMoney() {
            return BigDecimal.ZERO.setScale(AMOUNT_SCALE);
        }
    }
}

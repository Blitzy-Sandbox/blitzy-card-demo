package com.carddemo.batch.jobs;

import com.carddemo.batch.processors.TransactionReportProcessor;
import com.carddemo.batch.processors.TransactionReportProcessor.ReportLine;
import com.carddemo.model.entity.Transaction;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.service.report.ReportSubmissionService.ReportJobMessage;
import io.awspring.cloud.sqs.annotation.SqsListener;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
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
 * Spring Batch configuration that re-hosts the mainframe transaction-detail report as a
 * chunk-oriented {@link Job} <em>and</em> exposes the SQS FIFO consumer that triggers it.
 *
 * <p><strong>Lineage</strong> (logic only &mdash; the COBOL/JCL is <em>never</em> copied;
 * traceability is via AWS CardDemo source commit {@code 27d6c6f}):</p>
 * <ul>
 *   <li>{@code app/jcl/TRANREPT.jcl} &mdash; the two-step job stream: {@code STEP05R}
 *       (backup {@code TRANSACT}&nbsp;&rarr;&nbsp;{@code BKUP(+1)} then a DFSORT filter to the
 *       {@code [PARM-START-DATE, PARM-END-DATE]} window, ordered by {@code TRAN-CARD-NUM}) and
 *       {@code STEP10R} ({@code PGM=CBTRN03C} writing {@code TRANREPT}, {@code LRECL=133}).</li>
 *   <li>{@code app/cbl/CBTRN03C.cbl} &mdash; the report program: date-window filter, per-card
 *       control break, reference enrichment, 20-line pagination and the three total tiers.</li>
 *   <li>{@code app/cpy/CVTRA07Y.cpy} &mdash; the 133-byte report record layout (name header,
 *       detail line, column headers, separator, and page/account/grand total lines).</li>
 *   <li>{@code app/cpy/COTTL01Y.cpy} &mdash; the application title lines emitted as a banner.</li>
 * </ul>
 *
 * <h2>Online-to-batch bridge (Decision D-004)</h2>
 * <p>The CICS transient-data-queue write performed by {@code CORPT00C} ({@code WRITEQ TD}) is
 * replaced by an SQS FIFO queue. {@link com.carddemo.service.report.ReportSubmissionService}
 * publishes a {@link ReportJobMessage} to {@code carddemo-report-jobs.fifo}; the
 * {@link #onReportJobMessage(ReportJobMessage) listener} below consumes it and launches
 * {@code transactionReportJob} with the message's {@code startDate}/{@code endDate} (plus a unique
 * run id so every submission is a distinct {@code JobInstance}). The queue name is resolved from
 * configuration ({@code carddemo.aws.sqs.report-queue}); the LocalStack endpoint is never
 * hardcoded (it is supplied by {@code application-local.yml}/{@code application-test.yml}).</p>
 *
 * <h2>Inline reader and writer (rationale &rarr; {@code DECISION_LOG.md})</h2>
 * <p>No sibling report {@code ItemReader}/{@code ItemWriter} exists in the codebase, so both are
 * defined inline here: a {@code @StepScope} {@link ListItemReader} over
 * {@link TransactionRepository#findByProcessingDateRange(String, String)} (re-sorted card-major so
 * the COBOL control break works), and a {@code @StepScope} {@link ItemStreamWriter} that renders
 * the byte-faithful 133-column report and uploads it to S3 in {@code close()}. The per-record
 * enrichment is delegated to the sibling {@link TransactionReportProcessor}; totals, pagination
 * and control breaks are this writer's concern.</p>
 *
 * <h2>Binding rules (AAP &sect;0.8)</h2>
 * <p>Java 25 / Spring Boot 3.5.x, base package {@code com.carddemo}, Jakarta only. Every monetary
 * amount and total is a scale-2 {@link BigDecimal}; {@code float}/{@code double} are never used and
 * comparisons use {@link BigDecimal#compareTo(BigDecimal)}. The 133-byte layout, field offsets,
 * headers and total structure are preserved exactly (external-interface-contract preservation).
 * The job is <strong>not</strong> auto-run ({@code spring.batch.job.enabled=false}); it is launched
 * only by the SQS listener and by the batch pipeline orchestrator (stage&nbsp;4b).</p>
 */
@Configuration(value = "transactionReportJobConfig", proxyBeanMethods = false)
public final class TransactionReportJob {

    /** Logger for launch outcomes and report-writer diagnostics (Observability rule, AAP &sect;0.7.1). */
    private static final Logger LOGGER = LoggerFactory.getLogger(TransactionReportJob.class);

    /** Canonical Spring Batch job name (also the {@code TRANREPT}/{@code CBTRN03C} job identity). */
    public static final String JOB_NAME = "transactionReportJob";

    /** Bean and step name of the single chunk-oriented report step. */
    public static final String STEP_NAME = "transactionReportStep";

    /** Bean name of the inline step-scoped transaction reader. */
    public static final String READER_BEAN_NAME = "transactionReportReader";

    /** Bean name of the inline step-scoped report writer. */
    public static final String WRITER_BEAN_NAME = "transactionReportWriter";

    /** Job-parameter key carrying the inclusive window lower bound ({@code yyyy-MM-dd}). */
    public static final String PARAM_START_DATE = "startDate";

    /** Job-parameter key carrying the inclusive window upper bound ({@code yyyy-MM-dd}). */
    public static final String PARAM_END_DATE = "endDate";

    /** Job-parameter key carrying the report type ({@code Monthly}/{@code Yearly}/{@code Custom}). */
    public static final String PARAM_REPORT_TYPE = "reportType";

    /** Job-parameter key carrying a unique run id so each submission is a distinct {@code JobInstance}. */
    public static final String PARAM_RUN_ID = "runId";

    /** Chunk commit interval for the report step. */
    static final int CHUNK_SIZE = 100;

    /** Lines per page before a page-total/header break ({@code WS-PAGE-SIZE} in {@code CBTRN03C}). */
    static final int PAGE_SIZE = 20;

    /**
     * Number of report lines a header block advances the line counter by. {@code CBTRN03C}'s
     * {@code 1120-WRITE-HEADERS} writes four counted lines (name header, blank, column header,
     * separator); the two COTTL01Y banner lines this migration adds are intentionally
     * <em>not</em> counted so the COBOL page cadence is preserved exactly.
     */
    static final int HEADER_LINE_COUNT = 4;

    /** Fixed report record width in characters ({@code CVTRA07Y}/{@code LRECL=133}). */
    static final int LINE_WIDTH = 133;

    /** Integer digit positions in the edited amount picture ({@code ZZZ,ZZZ,ZZZ} = 9 digits). */
    static final int INT_DIGITS = 9;

    /** Monetary scale of every amount/total ({@code PIC ... V99}). */
    static final int AMOUNT_SCALE = 2;

    /** Width of an edited amount field ({@code -ZZZ,ZZZ,ZZZ.ZZ} / {@code +ZZZ,ZZZ,ZZZ.ZZ} = 15). */
    static final int EDITED_AMOUNT_WIDTH = 15;

    /** Scale-2 zero used for all total initialisation and zero-amount fallbacks. */
    private static final BigDecimal ZERO_AMOUNT = BigDecimal.ZERO.setScale(AMOUNT_SCALE);

    /** Divisor separating the integer and fractional halves of an unscaled scale-2 amount. */
    private static final BigInteger HUNDRED = BigInteger.valueOf(100);

    // --- COTTL01Y banner lines (exact 40-char literals, emitted left-justified in the 133 field) --
    /** {@code CCDA-TITLE01} &mdash; application title line 1. */
    private static final String TITLE_LINE_1 = "      AWS Mainframe Modernization       ";

    /** {@code CCDA-TITLE02} &mdash; application title line 2. */
    private static final String TITLE_LINE_2 = "              CardDemo                  ";

    /** {@code CCDA-THANK-YOU} &mdash; closing banner line emitted once as a footer. */
    private static final String THANK_YOU_LINE = "Thank you for using CCDA application... ";

    /** Pre-rendered 133-char title line 1 (constant across runs). */
    private static final String TITLE_LINE_1_133 = pad(TITLE_LINE_1);

    /** Pre-rendered 133-char title line 2 (constant across runs). */
    private static final String TITLE_LINE_2_133 = pad(TITLE_LINE_2);

    /** Pre-rendered 133-char thank-you footer (constant across runs). */
    private static final String THANK_YOU_133 = pad(THANK_YOU_LINE);

    /** Pre-rendered 133-char blank line ({@code WS-BLANK-LINE}). */
    private static final String BLANK_133 = pad("");

    /** Pre-rendered 133-char column-header line ({@code TRANSACTION-HEADER-1}). */
    private static final String HEADER_1_133 = pad(buildColumnHeader());

    /** Pre-rendered 133-dash separator line ({@code TRANSACTION-HEADER-2 PIC X(133) VALUE ALL '-'}). */
    private static final String SEPARATOR_133 = "-".repeat(LINE_WIDTH);

    /** Synchronous job launcher (auto-configured by Spring Boot) used to start the report job. */
    private final JobLauncher jobLauncher;

    /**
     * The report job bean defined in this same configuration. Injected {@link Lazy lazily} to break
     * the self-reference cycle between this {@code @Configuration} bean and its {@code @Bean} method.
     */
    private final Job transactionReportJob;

    /**
     * Creates the report-job configuration.
     *
     * @param jobLauncher          the auto-configured Spring Batch job launcher; never {@code null}
     * @param transactionReportJob the report job bean (lazy self-reference); never {@code null}
     */
    public TransactionReportJob(
            final JobLauncher jobLauncher,
            @Lazy @Qualifier(JOB_NAME) final Job transactionReportJob) {
        this.jobLauncher = Objects.requireNonNull(jobLauncher, "jobLauncher must not be null");
        this.transactionReportJob =
                Objects.requireNonNull(transactionReportJob, "transactionReportJob must not be null");
    }

    /**
     * SQS FIFO consumer that bridges report submission to batch execution (Decision D-004). Receives
     * a {@link ReportJobMessage} (deserialized from JSON by the framework) published by
     * {@link com.carddemo.service.report.ReportSubmissionService} and launches the report job with
     * the message's date window, report type and a unique run id.
     *
     * <p>A {@code null}/blank message is acknowledged and dropped (logged at WARN) rather than
     * thrown, so a malformed payload cannot create a poison-message loop. A launch failure is
     * rethrown so SQS redelivers the message for a retry.</p>
     *
     * @param message the report-job message; ignored when {@code null} or missing its date window
     */
    @SqsListener("${carddemo.aws.sqs.report-queue:carddemo-report-jobs.fifo}")
    public void onReportJobMessage(final ReportJobMessage message) {
        if (message == null || isBlank(message.startDate()) || isBlank(message.endDate())) {
            LOGGER.warn("Discarding malformed report-job message (missing date window): {}", message);
            return;
        }
        final String reportType = message.reportType() == null ? "" : message.reportType();
        final JobParameters parameters = new JobParametersBuilder()
                .addString(PARAM_START_DATE, message.startDate())
                .addString(PARAM_END_DATE, message.endDate())
                .addString(PARAM_REPORT_TYPE, reportType)
                .addString(PARAM_RUN_ID, UUID.randomUUID().toString())
                .toJobParameters();
        try {
            final JobExecution execution = jobLauncher.run(transactionReportJob, parameters);
            LOGGER.info("Launched {}: executionId={}, status={}, window=[{}..{}], reportType={}",
                    JOB_NAME, execution.getId(), execution.getStatus(),
                    message.startDate(), message.endDate(), reportType);
        } catch (final JobExecutionException launchFailure) {
            LOGGER.error("Failed to launch {} for window=[{}..{}]: {}",
                    JOB_NAME, message.startDate(), message.endDate(), launchFailure.getMessage());
            // Rethrow so the SQS framework redelivers (the message is not acknowledged).
            throw new IllegalStateException("Unable to launch " + JOB_NAME, launchFailure);
        }
    }

    /**
     * Inline step-scoped reader over the posted transactions in the report's date window
     * ({@code STEP05R}'s DFSORT filter + {@code CBTRN03C}'s main read loop). The repository finder
     * orders by {@code tranId}; the list is re-sorted card-major ({@code tranCardNum} then
     * {@code tranId}) so {@code CBTRN03C}'s per-card control break sees contiguous card groups.
     *
     * @param transactionRepository the transaction repository; never {@code null}
     * @param startDate             inclusive window lower bound ({@code yyyy-MM-dd}) from job parameters
     * @param endDate               inclusive window upper bound ({@code yyyy-MM-dd}) from job parameters
     * @return a {@link ListItemReader} serving the card-ordered, in-window transactions
     */
    @Bean(READER_BEAN_NAME)
    @StepScope
    public ListItemReader<Transaction> transactionReportReader(
            final TransactionRepository transactionRepository,
            @Value("#{jobParameters['startDate']}") final String startDate,
            @Value("#{jobParameters['endDate']}") final String endDate) {
        Objects.requireNonNull(transactionRepository, "transactionRepository must not be null");
        final List<Transaction> inWindow =
                transactionRepository.findByProcessingDateRange(startDate, endDate);
        final List<Transaction> cardOrdered = new ArrayList<>(inWindow);
        cardOrdered.sort(Comparator
                .comparing(Transaction::getTranCardNum, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Transaction::getTranId, Comparator.nullsLast(Comparator.naturalOrder())));
        return new ListItemReader<>(cardOrdered);
    }

    /**
     * Inline step-scoped report writer ({@code CBTRN03C} report-writing paragraphs + {@code TRANREPT}
     * output). Renders the byte-faithful 133-column report and uploads it to the configured S3
     * batch-output bucket in {@code close()}.
     *
     * @param s3Client     the synchronous S3 client (from {@code AwsConfig}); never {@code null}
     * @param outputBucket the S3 output bucket, resolved from {@code carddemo.aws.s3.bucket-output}
     *                     (default {@code carddemo-batch-output})
     * @param reportKey    the S3 object key for the report, resolved from
     *                     {@code carddemo.batch.report.report-key} (default {@code TRANREPT}); the
     *                     bucket is versioned, so each run produces a new object version (D-003)
     * @param startDate    inclusive window lower bound ({@code yyyy-MM-dd}) for the name-header range
     * @param endDate      inclusive window upper bound ({@code yyyy-MM-dd}) for the name-header range
     * @return the report writer for this step execution
     */
    @Bean(WRITER_BEAN_NAME)
    @StepScope
    public ReportItemWriter transactionReportWriter(
            final S3Client s3Client,
            @Value("${carddemo.aws.s3.bucket-output:carddemo-batch-output}") final String outputBucket,
            @Value("${carddemo.batch.report.report-key:TRANREPT}") final String reportKey,
            @Value("#{jobParameters['startDate']}") final String startDate,
            @Value("#{jobParameters['endDate']}") final String endDate) {
        return new ReportItemWriter(s3Client, outputBucket, reportKey, startDate, endDate);
    }

    /**
     * The single chunk-oriented report step ({@code STEP10R}/{@code CBTRN03C}): reads in-window
     * transactions, enriches them via {@link TransactionReportProcessor}, and streams the rendered
     * report through the inline writer. The writer is registered with {@code .stream(...)} so its
     * {@code ItemStream} lifecycle ({@code open}/{@code update}/{@code close}) runs.
     *
     * @param jobRepository             the auto-configured Spring Batch job repository
     * @param transactionManager        the auto-configured platform transaction manager
     * @param transactionReportReader   the inline step-scoped reader
     * @param transactionReportProcessor the sibling enrichment processor
     * @param transactionReportWriter   the inline step-scoped report writer
     * @return the configured report step
     */
    @Bean(STEP_NAME)
    public Step transactionReportStep(
            final JobRepository jobRepository,
            final PlatformTransactionManager transactionManager,
            @Qualifier(READER_BEAN_NAME) final ItemReader<Transaction> transactionReportReader,
            final TransactionReportProcessor transactionReportProcessor,
            @Qualifier(WRITER_BEAN_NAME) final ItemStreamWriter<ReportLine> transactionReportWriter) {
        return new StepBuilder(STEP_NAME, jobRepository)
                .<Transaction, ReportLine>chunk(CHUNK_SIZE, transactionManager)
                .reader(transactionReportReader)
                .processor(transactionReportProcessor)
                .writer(transactionReportWriter)
                .stream(transactionReportWriter)
                .build();
    }

    /**
     * The report job: a single step that produces the transaction-detail report. The job is not
     * auto-run; it is launched by {@link #onReportJobMessage(ReportJobMessage)} and by the batch
     * pipeline orchestrator (stage&nbsp;4b).
     *
     * @param jobRepository           the auto-configured Spring Batch job repository
     * @param transactionReportStep   the report step bean
     * @return the report job
     */
    @Bean(JOB_NAME)
    public Job transactionReportJob(
            final JobRepository jobRepository,
            @Qualifier(STEP_NAME) final Step transactionReportStep) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(transactionReportStep)
                .build();
    }

    // ----------------------------------------------------------------------------------------------
    // Side-effect-free formatters (package-private for unit testing). Each returns a line padded to
    // exactly LINE_WIDTH (133) characters, byte-faithful to the CVTRA07Y / COTTL01Y layouts.
    // ----------------------------------------------------------------------------------------------

    /**
     * Right-pads (or truncates) a value to exactly {@link #LINE_WIDTH} characters &mdash; the
     * {@code MOVE ... TO FD-REPTFILE-REC PIC X(133)} that every report line undergoes.
     *
     * @param value the line body (may be {@code null})
     * @return the value as exactly {@value #LINE_WIDTH} characters
     */
    static String pad(final String value) {
        return padField(value, LINE_WIDTH);
    }

    /**
     * Left-justifies {@code value} in a fixed {@code width} field: {@code null} becomes spaces,
     * shorter values are right-padded with spaces, longer values are truncated. Mirrors a COBOL
     * {@code MOVE} of an alphanumeric item into a {@code PIC X(width)} field.
     *
     * @param value the field value (may be {@code null})
     * @param width the fixed field width
     * @return the value rendered in exactly {@code width} characters
     */
    static String padField(final String value, final int width) {
        final String safe = value == null ? "" : value;
        if (safe.length() == width) {
            return safe;
        }
        if (safe.length() > width) {
            return safe.substring(0, width);
        }
        final StringBuilder sb = new StringBuilder(width);
        sb.append(safe);
        while (sb.length() < width) {
            sb.append(' ');
        }
        return sb.toString();
    }

    /**
     * Builds the {@code TRANSACTION-HEADER-1} column-header body (114 characters, before the pad to
     * 133). Column widths match {@code CVTRA07Y} exactly.
     *
     * @return the column-header body
     */
    private static String buildColumnHeader() {
        return padField("Transaction ID", 17)
                + padField("Account ID", 12)
                + padField("Transaction Type", 19)
                + padField("Tran Category", 35)
                + padField("Tran Source", 14)
                + " "
                + padField("        Amount", 16);
    }

    /**
     * Renders {@code REPORT-NAME-HEADER}: the short/long report names and the inclusive date range
     * ({@code 'Date Range: ' + start + ' to ' + end}).
     *
     * @param startDate the window lower bound ({@code yyyy-MM-dd}); {@code null}/blank renders spaces
     * @param endDate   the window upper bound ({@code yyyy-MM-dd}); {@code null}/blank renders spaces
     * @return the 133-character name-header line
     */
    static String formatNameHeader(final String startDate, final String endDate) {
        final String body = padField("DALYREPT", 38)
                + padField("Daily Transaction Report", 41)
                + "Date Range: "
                + padField(startDate, 10)
                + " to "
                + padField(endDate, 10);
        return pad(body);
    }

    /**
     * Renders one {@code TRANSACTION-DETAIL-REPORT} line from an enriched {@link ReportLine}, at the
     * exact {@code CVTRA07Y} offsets: tran id {@code X(16)}, account id {@code X(11)}, type code
     * {@code X(2)} + {@code '-'} + type description {@code X(15)}, category code {@code 9(4)} +
     * {@code '-'} + category description {@code X(29)}, source {@code X(10)}, then the amount edited
     * as {@code -ZZZ,ZZZ,ZZZ.ZZ}.
     *
     * @param line the enriched report line
     * @return the 133-character detail line
     */
    static String formatDetailLine(final ReportLine line) {
        final String body = padField(line.tranId(), 16)
                + " "
                + accountIdField(line.accountId())
                + " "
                + padField(line.typeCode(), 2)
                + "-"
                + padField(line.typeDescription(), 15)
                + " "
                + categoryCodeField(line.categoryCode())
                + "-"
                + padField(line.categoryDescription(), 29)
                + " "
                + padField(line.source(), 10)
                + "    "
                + editNumeric(line.amount(), false)
                + "  ";
        return pad(body);
    }

    /**
     * Renders {@code REPORT-PAGE-TOTALS}: {@code 'Page Total'} + a dotted leader + the total edited
     * as {@code +ZZZ,ZZZ,ZZZ.ZZ}.
     *
     * @param total the accumulated page total
     * @return the 133-character page-total line
     */
    static String formatPageTotal(final BigDecimal total) {
        return pad(padField("Page Total", 11) + ".".repeat(86) + editNumeric(total, true));
    }

    /**
     * Renders {@code REPORT-ACCOUNT-TOTALS}: {@code 'Account Total'} + a dotted leader + the total
     * edited as {@code +ZZZ,ZZZ,ZZZ.ZZ}.
     *
     * @param total the accumulated account (per-card) total
     * @return the 133-character account-total line
     */
    static String formatAccountTotal(final BigDecimal total) {
        return pad(padField("Account Total", 13) + ".".repeat(84) + editNumeric(total, true));
    }

    /**
     * Renders {@code REPORT-GRAND-TOTALS}: {@code 'Grand Total'} + a dotted leader + the total edited
     * as {@code +ZZZ,ZZZ,ZZZ.ZZ}.
     *
     * @param total the accumulated grand total
     * @return the 133-character grand-total line
     */
    static String formatGrandTotal(final BigDecimal total) {
        return pad(padField("Grand Total", 11) + ".".repeat(86) + editNumeric(total, true));
    }

    /**
     * Renders the account id field ({@code TRAN-REPORT-ACCOUNT-ID PIC X(11)} fed from the numeric
     * {@code XREF-ACCT-ID PIC 9(11)}): an 11-digit zero-padded number, or 11 spaces when the
     * cross-reference is absent ({@code accountId == null}).
     *
     * @param accountId the resolved account id, or {@code null} when the cross-reference is absent
     * @return the 11-character account-id field
     */
    static String accountIdField(final Long accountId) {
        if (accountId == null) {
            return " ".repeat(11);
        }
        final String digits = String.format("%011d", Math.abs(accountId));
        return digits.length() > 11 ? digits.substring(digits.length() - 11) : digits;
    }

    /**
     * Renders the category code field ({@code TRAN-REPORT-CAT-CD PIC 9(4)}): a 4-digit zero-padded
     * number ({@code null} defaults to {@code 0000}).
     *
     * @param categoryCode the category code, or {@code null}
     * @return the 4-character zero-padded category code
     */
    static String categoryCodeField(final Integer categoryCode) {
        final int code = categoryCode == null ? 0 : categoryCode;
        final String digits = String.format("%04d", Math.abs(code));
        return digits.length() > 4 ? digits.substring(digits.length() - 4) : digits;
    }

    /**
     * Reproduces a COBOL numeric edit into a 15-character field
     * ({@code -ZZZ,ZZZ,ZZZ.ZZ} or {@code +ZZZ,ZZZ,ZZZ.ZZ}).
     *
     * <ul>
     *   <li>A zero value edits to {@value #EDITED_AMOUNT_WIDTH} spaces &mdash; every digit position
     *       is a {@code Z} (zero-suppression) symbol, so a zero value blanks the entire field,
     *       including the sign and decimal point.</li>
     *   <li>Otherwise: leading zeros (and the commas inside the suppressed zone) become spaces; the
     *       sign is {@code '-'} for negatives, and for non-negatives {@code '+'} in plus style or a
     *       space in minus style; the two fractional digits always print.</li>
     * </ul>
     *
     * @param rawValue  the amount (treated as zero when {@code null})
     * @param plusStyle {@code true} for the {@code +ZZZ...} (totals) picture, {@code false} for the
     *                  {@code -ZZZ...} (detail) picture
     * @return the edited 15-character amount
     */
    static String editNumeric(final BigDecimal rawValue, final boolean plusStyle) {
        final BigDecimal value = rawValue == null ? ZERO_AMOUNT : rawValue;
        if (value.compareTo(BigDecimal.ZERO) == 0) {
            return " ".repeat(EDITED_AMOUNT_WIDTH);
        }
        final boolean negative = value.signum() < 0;
        final BigInteger unscaled =
                value.abs().setScale(AMOUNT_SCALE, RoundingMode.HALF_EVEN).unscaledValue();
        final BigInteger integerPart = unscaled.divide(HUNDRED);
        final int fraction = unscaled.mod(HUNDRED).intValueExact();

        String digits = integerPart.toString();
        if (digits.length() > INT_DIGITS) {
            digits = digits.substring(digits.length() - INT_DIGITS);
        } else {
            digits = "0".repeat(INT_DIGITS - digits.length()) + digits;
        }
        final char[] grouped = (digits.substring(0, 3) + ","
                + digits.substring(3, 6) + ","
                + digits.substring(6, 9)).toCharArray();
        for (int i = 0; i < grouped.length; i++) {
            final char c = grouped[i];
            if (c == '0' || c == ',') {
                grouped[i] = ' ';
            } else {
                break;
            }
        }
        final char sign = negative ? '-' : (plusStyle ? '+' : ' ');
        return sign + new String(grouped) + "." + String.format("%02d", fraction);
    }

    /**
     * Null/blank guard for the incoming message date fields.
     *
     * @param value the value to test
     * @return {@code true} when {@code value} is {@code null} or contains only whitespace
     */
    private static boolean isBlank(final String value) {
        return value == null || value.isBlank();
    }

    /**
     * Inline {@link ItemStreamWriter} that renders the 133-column transaction-detail report from the
     * enriched {@link ReportLine} stream and uploads it to S3 once, in {@link #close()}.
     *
     * <p>Reproduces {@code CBTRN03C}'s cross-record report logic faithfully: a per-card control
     * break that emits account totals ({@code 1120-WRITE-ACCOUNT-TOTALS}), a page break every
     * {@value #PAGE_SIZE} counted lines that emits page totals and re-prints the header block
     * ({@code 1110-WRITE-PAGE-TOTALS} + {@code 1120-WRITE-HEADERS}), and three roll-up tiers
     * (page&nbsp;&rarr;&nbsp;grand, account, grand). All accumulation uses scale-2
     * {@link BigDecimal} and comparisons use {@link BigDecimal#compareTo(BigDecimal)}.</p>
     *
     * <p><strong>Restart note:</strong> the upstream {@link ListItemReader} is not restart-aware and
     * replays the full list on a fresh step execution, so {@link #open(ExecutionContext)} always
     * re-initialises this writer's accumulators rather than restoring them (restoring would
     * double-count); the snapshot written in {@link #update(ExecutionContext)} is diagnostic only.
     * The class is intentionally <em>not</em> {@code final} so the {@code @StepScope} CGLIB proxy can
     * subclass it.</p>
     */
    public static class ReportItemWriter implements ItemStreamWriter<ReportLine> {

        /** Execution-context key prefix for the diagnostic snapshot. */
        private static final String CTX_PREFIX = "carddemo.report.";

        /** Execution-context key: counted report lines on the current page. */
        private static final String CTX_LINE_COUNTER = CTX_PREFIX + "lineCounter";

        /** Execution-context key: current page subtotal. */
        private static final String CTX_PAGE_TOTAL = CTX_PREFIX + "pageTotal";

        /** Execution-context key: current account (per-card) subtotal. */
        private static final String CTX_ACCOUNT_TOTAL = CTX_PREFIX + "accountTotal";

        /** Execution-context key: running grand total. */
        private static final String CTX_GRAND_TOTAL = CTX_PREFIX + "grandTotal";

        /** MIME content type recorded on the S3 report object. */
        private static final String CONTENT_TYPE = "text/plain";

        /** Synchronous S3 client (bean from {@code AwsConfig}). */
        private final S3Client s3Client;

        /** Target S3 output bucket for the report object. */
        private final String outputBucket;

        /** S3 object key for the report ({@code TRANREPT} equivalent). */
        private final String reportKey;

        /** Inclusive window lower bound for the name-header date range. */
        private final String startDate;

        /** Inclusive window upper bound for the name-header date range. */
        private final String endDate;

        /** Per-run report buffer (allocated in {@link #open(ExecutionContext)}). */
        private StringBuilder report;

        /** Current page subtotal (scale 2). */
        private BigDecimal pageTotal;

        /** Current account (per-card) subtotal (scale 2). */
        private BigDecimal accountTotal;

        /** Running grand total (scale 2). */
        private BigDecimal grandTotal;

        /** Counted report lines used for the {@value #PAGE_SIZE}-line page cadence. */
        private long lineCounter;

        /** Control-break key: the card number of the group currently being printed. */
        private String currentCardNumber;

        /** {@code WS-FIRST-TIME} flag: {@code true} until the first detail line is processed. */
        private boolean firstLine;

        /** Count of detail lines written this run (for the lifecycle log line). */
        private long detailCount;

        /**
         * Creates the report writer.
         *
         * @param s3Client     synchronous S3 client; never {@code null}
         * @param outputBucket S3 output bucket; never {@code null}
         * @param reportKey    S3 report object key; never {@code null}
         * @param startDate    window lower bound for the name header (may be {@code null})
         * @param endDate      window upper bound for the name header (may be {@code null})
         */
        ReportItemWriter(final S3Client s3Client, final String outputBucket, final String reportKey,
                final String startDate, final String endDate) {
            this.s3Client = Objects.requireNonNull(s3Client, "s3Client must not be null");
            this.outputBucket = Objects.requireNonNull(outputBucket, "outputBucket must not be null");
            this.reportKey = Objects.requireNonNull(reportKey, "reportKey must not be null");
            this.startDate = startDate;
            this.endDate = endDate;
        }

        /**
         * Allocates a fresh buffer and zeroes all accumulators for the run (always fresh; see the
         * restart note on the class).
         *
         * @param executionContext the step execution context (not restored from)
         */
        @Override
        public void open(final ExecutionContext executionContext) {
            this.report = new StringBuilder();
            this.pageTotal = ZERO_AMOUNT;
            this.accountTotal = ZERO_AMOUNT;
            this.grandTotal = ZERO_AMOUNT;
            this.lineCounter = 0L;
            this.currentCardNumber = null;
            this.firstLine = true;
            this.detailCount = 0L;
            LOGGER.debug("Report writer opened: bucket={}, key={}, window=[{}..{}]",
                    outputBucket, reportKey, startDate, endDate);
        }

        /**
         * Renders and buffers each enriched report line in the chunk.
         *
         * @param chunk the chunk of enriched report lines
         */
        @Override
        public void write(final Chunk<? extends ReportLine> chunk) {
            for (final ReportLine line : chunk) {
                appendReportLine(line);
            }
        }

        /**
         * Writes a diagnostic snapshot of the accumulators to the execution context (not restored on
         * restart; see the class note).
         *
         * @param executionContext the step execution context
         */
        @Override
        public void update(final ExecutionContext executionContext) {
            executionContext.putLong(CTX_LINE_COUNTER, lineCounter);
            executionContext.putString(CTX_PAGE_TOTAL, pageTotal.toPlainString());
            executionContext.putString(CTX_ACCOUNT_TOTAL, accountTotal.toPlainString());
            executionContext.putString(CTX_GRAND_TOTAL, grandTotal.toPlainString());
        }

        /**
         * Flushes the closing totals and uploads the assembled report to S3.
         *
         * <p>When at least one detail line was written, the final card's account total is emitted
         * (faithful correctness; {@code CBTRN03C} omits this last account total), followed by the
         * page total (which rolls into the grand total) and the grand total. When no in-window
         * records existed, a well-formed empty report (header block + zero page/grand totals) is
         * emitted. A closing {@code CCDA-THANK-YOU} banner line is always appended.</p>
         *
         * @throws IllegalStateException if the S3 upload fails
         */
        @Override
        public void close() {
            if (report == null) {
                return;
            }
            if (firstLine) {
                writeHeaderBlock();
                writePageTotals();
                writeGrandTotals();
            } else {
                writeAccountTotals();
                writePageTotals();
                writeGrandTotals();
            }
            appendLine(THANK_YOU_133);
            uploadReport();
        }

        // --- CBTRN03C report-writing paragraphs (stateful, single-threaded per step execution) ----

        /**
         * Processes one enriched line: per-card control break, first-line/page-break header
         * emission, total accumulation, and the detail line write
         * ({@code 1100-WRITE-TRANSACTION-REPORT} + the main-loop control break).
         *
         * @param line the enriched report line
         */
        private void appendReportLine(final ReportLine line) {
            final String cardNumber = line.cardNumber();
            if (currentCardNumber == null || !currentCardNumber.equals(cardNumber)) {
                if (!firstLine) {
                    writeAccountTotals();
                }
                currentCardNumber = cardNumber;
            }
            if (firstLine) {
                firstLine = false;
                writeHeaderBlock();
            }
            if (lineCounter % PAGE_SIZE == 0) {
                writePageTotals();
                writeHeaderBlock();
            }
            final BigDecimal amount = line.amount() == null ? ZERO_AMOUNT : line.amount();
            pageTotal = pageTotal.add(amount);
            accountTotal = accountTotal.add(amount);
            appendLine(formatDetailLine(line));
            lineCounter++;
            detailCount++;
        }

        /**
         * Emits the header block ({@code 1120-WRITE-HEADERS}): the two-line COTTL01Y banner
         * (uncounted) followed by the four counted CVTRA07Y header lines (name header, blank,
         * column header, separator).
         */
        private void writeHeaderBlock() {
            appendLine(TITLE_LINE_1_133);
            appendLine(TITLE_LINE_2_133);
            appendLine(formatNameHeader(startDate, endDate));
            appendLine(BLANK_133);
            appendLine(HEADER_1_133);
            appendLine(SEPARATOR_133);
            lineCounter += HEADER_LINE_COUNT;
        }

        /**
         * Emits the page total ({@code 1110-WRITE-PAGE-TOTALS}): writes the page-total line, rolls
         * the page total into the grand total, resets the page total, and writes the separator.
         */
        private void writePageTotals() {
            appendLine(formatPageTotal(pageTotal));
            grandTotal = grandTotal.add(pageTotal);
            pageTotal = ZERO_AMOUNT;
            lineCounter++;
            appendLine(SEPARATOR_133);
            lineCounter++;
        }

        /**
         * Emits the account total ({@code 1120-WRITE-ACCOUNT-TOTALS}): writes the account-total line,
         * resets the account total, and writes the separator.
         */
        private void writeAccountTotals() {
            appendLine(formatAccountTotal(accountTotal));
            accountTotal = ZERO_AMOUNT;
            lineCounter++;
            appendLine(SEPARATOR_133);
            lineCounter++;
        }

        /**
         * Emits the grand total ({@code 1110-WRITE-GRAND-TOTALS}): writes the grand-total line.
         */
        private void writeGrandTotals() {
            appendLine(formatGrandTotal(grandTotal));
        }

        /**
         * Appends one 133-character line plus a newline separator to the report buffer.
         *
         * @param line the 133-character line
         */
        private void appendLine(final String line) {
            report.append(line).append('\n');
        }

        /**
         * Uploads the assembled report to S3 as one object (the {@code TRANREPT} generation; the
         * versioned bucket yields a new object version per run, D-003). The byte content uses
         * {@code ISO-8859-1} so every character maps to one byte, preserving the fixed-width layout.
         *
         * @throws IllegalStateException if the S3 upload fails
         */
        private void uploadReport() {
            final byte[] payload = report.toString().getBytes(StandardCharsets.ISO_8859_1);
            try {
                s3Client.putObject(
                        PutObjectRequest.builder()
                                .bucket(outputBucket)
                                .key(reportKey)
                                .contentType(CONTENT_TYPE)
                                .build(),
                        RequestBody.fromBytes(payload));
                LOGGER.info("Wrote transaction report to S3: bucket={}, key={}, details={}, bytes={}",
                        outputBucket, reportKey, detailCount, payload.length);
            } catch (final SdkException uploadFailure) {
                LOGGER.error("Failed to write transaction report to S3: bucket={}, key={}, cause={}",
                        outputBucket, reportKey, uploadFailure.getMessage());
                throw new IllegalStateException(
                        "Failed to write transaction report to S3 " + outputBucket + "/" + reportKey,
                        uploadFailure);
            } finally {
                this.report = null;
            }
        }
    }
}

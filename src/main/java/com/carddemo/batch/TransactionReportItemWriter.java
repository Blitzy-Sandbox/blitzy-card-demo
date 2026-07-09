package com.carddemo.batch;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.ItemStreamWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.carddemo.exception.FileProcessingException;

import io.awspring.cloud.s3.ObjectMetadata;
import io.awspring.cloud.s3.S3Template;

/**
 * Stateful Spring Batch writer that renders the CardDemo <em>transaction detail report</em> and
 * publishes it as a versioned object in the batch-output S3 bucket. This is writer&nbsp;#3 of the
 * {@code TransactionReportJob} pipeline and the structured-Java translation of the report-writing
 * paragraphs of the legacy batch program {@code CBTRN03C.CBL} (source reference-only, SHA
 * {@code 27d6c6f}).
 *
 * <h2>COBOL lineage</h2>
 * <p>Each responsibility below maps one-to-one to a paragraph of {@code CBTRN03C}:</p>
 * <ul>
 *   <li>{@code 1100-WRITE-TRANSACTION-REPORT} &rarr; the per-item orchestration in
 *       {@link #write(Chunk)} (first-time headers, page-break, totals accumulation, detail).</li>
 *   <li>{@code 1120-WRITE-HEADERS} &rarr; {@link #writeHeaders()} (the {@code REPORT-NAME-HEADER}
 *       line, a blank line, {@code TRANSACTION-HEADER-1} and {@code TRANSACTION-HEADER-2}).</li>
 *   <li>{@code 1120-WRITE-DETAIL} &rarr; {@link #writeDetail(ReportDetailLine)}.</li>
 *   <li>{@code 1120-WRITE-ACCOUNT-TOTALS} &rarr; {@link #writeAccountTotals()} (control break on a
 *       change of {@link ReportDetailLine#accountId()}).</li>
 *   <li>{@code 1110-WRITE-PAGE-TOTALS} &rarr; {@link #writePageTotals()}.</li>
 *   <li>{@code 1110-WRITE-GRAND-TOTALS} &rarr; {@link #writeGrandTotals()}.</li>
 * </ul>
 *
 * <h2>Statefulness</h2>
 * <p>The COBOL program keeps its page counter and running totals in {@code WORKING-STORAGE} for the
 * whole run; the equivalent Java state ({@link #lineCounter}, {@link #pageTotal},
 * {@link #accountTotal}, {@link #grandTotal}, {@link #currentAccountId}, {@link #firstTime}) must
 * therefore survive <em>between chunks</em>. The state is reset only in {@link #open(ExecutionContext)}
 * and flushed only in {@link #close()} &mdash; never between chunks. The bean is {@link StepScope}
 * so exactly one instance backs a single step execution.</p>
 *
 * <h2>Byte parity (Gate&nbsp;1 / Gate&nbsp;5)</h2>
 * <p>Every emitted record is exactly {@value #RECORD_LENGTH} characters wide, matching the legacy
 * {@code TRANREPT} dataset ({@code LRECL=133, RECFM=FB}). Column positions and labels reproduce
 * copybook {@code CVTRA07Y} byte-for-byte. Records are buffered to a temporary file (memory-safe for
 * arbitrarily large runs), each terminated with a single {@code '\n'}, and the whole artifact is
 * uploaded to S3 on {@link #close()}.</p>
 *
 * <h2>Decimal fidelity (AAP&nbsp;§0.8.2)</h2>
 * <p>All totals are {@link BigDecimal} of scale&nbsp;2 accumulated with {@link RoundingMode#HALF_UP};
 * no {@code float} or {@code double} is used anywhere in the totalling or the numeric editing. The
 * edited amount columns reproduce the COBOL {@code PIC -ZZZ,ZZZ,ZZZ.ZZ} (detail) and
 * {@code PIC +ZZZ,ZZZ,ZZZ.ZZ} (totals) pictures, including zero suppression and the fixed sign.</p>
 *
 * <h2>Totalling semantics (deviation from a literal reading of {@code CBTRN03C})</h2>
 * <p>The legacy end-of-file path accumulates the grand total from the page totals and, as a latent
 * artifact, both re-adds a stale {@code TRAN-AMT} at EOF and never emits a final account total. This
 * writer follows the corrected, explicitly specified behaviour: the grand total is accumulated
 * per detail row (so {@link #writePageTotals()} does <strong>not</strong> re-add into it), and a
 * final account total, page total and grand total are all flushed in {@link #close()}. The grand
 * total therefore equals the exact sum of every detail amount. The rationale for this deviation is
 * recorded in {@code docs/decision-log.md}, not in code comments.</p>
 *
 * <h2>AWS / LocalStack (AAP&nbsp;§0.7.7)</h2>
 * <p>The report is uploaded through the Spring Cloud AWS {@link S3Template} auto-configured bean,
 * which is injected directly to avoid a {@code batch &rarr; config} dependency cycle (see
 * {@code config.AwsConfig}). Every AWS interaction is exercisable against LocalStack with zero live
 * AWS credentials; the target bucket must be provisioned by infrastructure (or, in integration
 * tests, self-provisioned).</p>
 *
 * <h2>Faults</h2>
 * <p>Any temp-file I/O or S3 failure is logged with structured context and re-thrown as a
 * {@link FileProcessingException} (the structured-Java replacement for the COBOL abend paragraphs);
 * the JVM is never terminated.</p>
 */
@Component
@StepScope
public class TransactionReportItemWriter implements ItemStreamWriter<ReportDetailLine>, StepExecutionListener {

    private static final Logger LOG = LoggerFactory.getLogger(TransactionReportItemWriter.class);

    /** Fixed record width of the {@code TRANREPT} dataset ({@code LRECL=133}). */
    private static final int RECORD_LENGTH = 133;

    /** Number of integer digit positions in the edited amount picture ({@code ZZZ,ZZZ,ZZZ}). */
    private static final int AMOUNT_INTEGER_DIGITS = 9;

    /** Abend context reproduced on the {@link FileProcessingException} (mirrors {@code 9999-ABEND-PROGRAM}). */
    private static final String ABEND_CODE = "0999";
    private static final String ABEND_CULPRIT = "CBTRN03C";

    /** Canonical zero value ({@code 0.00}) used to initialise and reset the running totals. */
    private static final BigDecimal ZERO_SCALE_2 = BigDecimal.ZERO.setScale(2);

    /** A full-width blank line ({@code WS-BLANK-LINE PIC X(133) VALUE SPACES}). */
    private static final String BLANK_LINE = " ".repeat(RECORD_LENGTH);

    /** The separator line ({@code TRANSACTION-HEADER-2 PIC X(133) VALUE ALL '-'}). */
    private static final String SEPARATOR_LINE = "-".repeat(RECORD_LENGTH);

    /**
     * Column header line ({@code TRANSACTION-HEADER-1} in {@code CVTRA07Y}); 114 characters before
     * the record is padded to {@value #RECORD_LENGTH}.
     */
    private static final String TRANSACTION_HEADER_1 =
            pad("Transaction ID", 17)
            + pad("Account ID", 12)
            + pad("Transaction Type", 19)
            + pad("Tran Category", 35)
            + pad("Tran Source", 14)
            + " "
            + pad("        Amount", 16);

    /** Literal prefix of {@code REPORT-PAGE-TOTALS}: {@code X(11)'Page Total'} + {@code X(86) ALL '.'} = 97 chars. */
    private static final String PAGE_TOTAL_PREFIX = pad("Page Total", 11) + ".".repeat(86);

    /** Literal prefix of {@code REPORT-ACCOUNT-TOTALS}: {@code X(13)'Account Total'} + {@code X(84) ALL '.'} = 97 chars. */
    private static final String ACCOUNT_TOTAL_PREFIX = pad("Account Total", 13) + ".".repeat(84);

    /** Literal prefix of {@code REPORT-GRAND-TOTALS}: {@code X(11)'Grand Total'} + {@code X(86) ALL '.'} = 97 chars. */
    private static final String GRAND_TOTAL_PREFIX = pad("Grand Total", 11) + ".".repeat(86);

    // ---------------------------------------------------------------------------------------------
    // Injected, immutable configuration
    // ---------------------------------------------------------------------------------------------

    /** Auto-configured Spring Cloud AWS S3 operations facade (LocalStack-backed). */
    private final S3Template s3Template;

    /** Page-break threshold ({@code WS-PAGE-SIZE}, COBOL default {@code 20}). */
    private final int pageSize;

    /** Target S3 bucket for the report artifact (legacy GDG generation &rarr; versioned S3 object). */
    private final String outputBucket;

    /** Stable S3 object key for the report artifact (for example {@code tranrept.dat}). */
    private final String objectKey;

    /**
     * Report start date rendered in {@code REPT-START-DATE} of the name header. Resolved per run from
     * the {@code startDate} job parameter, else the {@code carddemo.batch.report.start-date} property,
     * else the JCL default {@code 2022-01-01} &mdash; the same three-level chain used by
     * {@link TransactionReportProcessor}, so the header's "Date Range" always matches the window the
     * processor actually filtered on (the byte-parity of {@code MOVE WS-START-DATE TO REPT-START-DATE}
     * in {@code CBTRN03C}).
     */
    private final String reportStartDate;

    /**
     * Report end date rendered in {@code REPT-END-DATE} of the name header. Resolved per run from the
     * {@code endDate} job parameter, else the {@code carddemo.batch.report.end-date} property, else the
     * JCL default {@code 2022-07-06} &mdash; the same three-level chain used by
     * {@link TransactionReportProcessor}, so the header's "Date Range" always matches the window the
     * processor actually filtered on (the byte-parity of {@code MOVE WS-END-DATE TO REPT-END-DATE} in
     * {@code CBTRN03C}).
     */
    private final String reportEndDate;

    // ---------------------------------------------------------------------------------------------
    // Mutable run state (mirrors CBTRN03C WORKING-STORAGE; spans chunks, reset only in open())
    // ---------------------------------------------------------------------------------------------

    /** {@code WS-LINE-COUNTER}: records written on the current page (drives the page break). */
    private int lineCounter;

    /** {@code WS-PAGE-TOTAL}: running total for the current page. */
    private BigDecimal pageTotal = ZERO_SCALE_2;

    /** {@code WS-ACCOUNT-TOTAL}: running total for the current account (control-break group). */
    private BigDecimal accountTotal = ZERO_SCALE_2;

    /** {@code WS-GRAND-TOTAL}: running total for the whole report (accumulated per detail row). */
    private BigDecimal grandTotal = ZERO_SCALE_2;

    /** Control-break key: the account id of the group currently being totalled ({@code null} before the first row). */
    private Long currentAccountId;

    /** {@code WS-FIRST-TIME}: {@code true} until the opening headers have been written. */
    private boolean firstTime = true;

    /** {@code true} once at least one detail row has been written (guards the final-total flush). */
    private boolean anyDetailWritten;

    /** Temp-file buffer holding the assembled report until it is uploaded on {@link #close()}. */
    private Path tempFile;

    /** Buffered writer over {@link #tempFile}; {@code null} outside the {@code open()..close()} window. */
    private BufferedWriter writer;

    /** Idempotency guard so {@link #close()} flushes/uploads at most once. */
    private boolean closed;

    /**
     * Creates the writer with its injected collaborators and externalised configuration.
     *
     * <p>All configuration is supplied through constructor injection (no field or setter injection),
     * keeping the instance fully initialised and trivially unit-testable without a Spring context.</p>
     *
     * @param s3Template      the auto-configured Spring Cloud AWS {@link S3Template}; must not be {@code null}
     * @param pageSize        the page-break threshold ({@code WS-PAGE-SIZE}); must be positive
     * @param outputBucket    the target S3 bucket name; must not be {@code null} or blank
     * @param objectKey       the stable S3 object key for the report; must not be {@code null} or blank
     * @param reportStartDate the report start date for the name header ({@code REPT-START-DATE}),
     *                        resolved from the {@code startDate} job parameter, else the
     *                        {@code carddemo.batch.report.start-date} property, else the JCL default
     *                        {@code 2022-01-01}
     * @param reportEndDate   the report end date for the name header ({@code REPT-END-DATE}), resolved
     *                        from the {@code endDate} job parameter, else the
     *                        {@code carddemo.batch.report.end-date} property, else the JCL default
     *                        {@code 2022-07-06}
     * @throws IllegalArgumentException if {@code pageSize} is not positive or a required name is blank
     */
    public TransactionReportItemWriter(
            S3Template s3Template,
            @Value("${carddemo.batch.report.page-size:20}") int pageSize,
            @Value("${carddemo.aws.s3.output-bucket:carddemo-batch-output}") String outputBucket,
            @Value("${carddemo.batch.report.object-key:tranrept.dat}") String objectKey,
            @Value("#{jobParameters['startDate'] ?: '${carddemo.batch.report.start-date:2022-01-01}'}")
            String reportStartDate,
            @Value("#{jobParameters['endDate'] ?: '${carddemo.batch.report.end-date:2022-07-06}'}")
            String reportEndDate) {
        this.s3Template = Objects.requireNonNull(s3Template, "s3Template must not be null");
        if (pageSize <= 0) {
            throw new IllegalArgumentException("carddemo.batch.report.page-size must be positive but was " + pageSize);
        }
        if (outputBucket == null || outputBucket.isBlank()) {
            throw new IllegalArgumentException("carddemo.aws.s3.output-bucket must not be blank");
        }
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("carddemo.batch.report.object-key must not be blank");
        }
        this.pageSize = pageSize;
        this.outputBucket = outputBucket;
        this.objectKey = objectKey;
        this.reportStartDate = (reportStartDate == null) ? "" : reportStartDate;
        this.reportEndDate = (reportEndDate == null) ? "" : reportEndDate;
    }

    // ---------------------------------------------------------------------------------------------
    // ItemStream lifecycle
    // ---------------------------------------------------------------------------------------------

    /**
     * Resets all run state and opens a fresh temp-file buffer for a new step execution.
     *
     * <p>This is the sole place the counters and running totals are (re)initialised, so a restart
     * always begins from a clean slate: {@code lineCounter = 0}, all totals {@code 0.00},
     * {@code currentAccountId = null} and {@code firstTime = true}. The report is not restartable
     * mid-run (see {@link #update(ExecutionContext)}).</p>
     *
     * @param executionContext the step execution context (not consulted; state is always rebuilt fresh)
     * @throws ItemStreamException never thrown directly; temp-file failures surface as {@link FileProcessingException}
     */
    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException {
        // Defensive: discard any buffer left over from a prior, improperly closed execution.
        closeWriterQuietly();
        deleteTempFileQuietly();

        this.lineCounter = 0;
        this.pageTotal = ZERO_SCALE_2;
        this.accountTotal = ZERO_SCALE_2;
        this.grandTotal = ZERO_SCALE_2;
        this.currentAccountId = null;
        this.firstTime = true;
        this.anyDetailWritten = false;
        this.closed = false;

        try {
            this.tempFile = Files.createTempFile("carddemo-tranrept-", ".dat");
            this.writer = Files.newBufferedWriter(this.tempFile, StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            LOG.error("Failed to open temporary buffer for the transaction detail report", e);
            throw new FileProcessingException(
                    "Unable to open temporary buffer for the transaction detail report", e);
        }
        LOG.debug("Opened transaction report writer (pageSize={}, bucket={}, key={}, tempFile={})",
                pageSize, outputBucket, objectKey, tempFile);
    }

    /**
     * No-op checkpoint hook.
     *
     * <p>The report's control-break totals cannot be meaningfully resumed from a mid-run checkpoint,
     * so no restart state is persisted; {@link #open(ExecutionContext)} always rebuilds the state
     * from scratch. The method is implemented explicitly to document that intent.</p>
     *
     * @param executionContext the step execution context (intentionally unused)
     * @throws ItemStreamException never thrown
     */
    @Override
    public void update(ExecutionContext executionContext) throws ItemStreamException {
        // Intentionally empty: report generation is not restartable mid-run.
    }

    /**
     * Flushes the trailing totals and <strong>uploads the assembled report to S3</strong> — the Java
     * equivalent of the COBOL end-of-file path. Performed here — not in {@link #close()} — so that an
     * upload failure fails the step (QA finding <strong>F4</strong>, decision {@code D-027}).
     *
     * <p><strong>Why the upload lives here.</strong> {@code AbstractStep} persists the step and job
     * batch/exit status <em>after</em> {@code afterStep()} returns but <em>before</em> {@code close()}
     * runs, and it swallows any exception thrown from either callback. Uploading in {@code close()}
     * therefore could not influence the recorded outcome: a failed report upload was previously only
     * logged as "Exception while closing step execution resources" while the step/job was already
     * recorded {@code COMPLETED} — the report was silently lost and (for the SQS-triggered report path)
     * the trigger message was acked with nothing produced. Uploading here and marking the step
     * {@code FAILED} on failure makes that failure durable.</p>
     *
     * <p>When at least one detail row was written it emits, in order, the final account total
     * ({@code 1120-WRITE-ACCOUNT-TOTALS} for the last account), the final page total
     * ({@code 1110-WRITE-PAGE-TOTALS}) and the grand total ({@code 1110-WRITE-GRAND-TOTALS}); an empty
     * run produces no artifact. If the step already failed during item processing (this callback also
     * runs on the failure path), finalization is skipped and the {@code FAILED} status is preserved.
     * The {@link #closed} guard keeps finalization single-shot; {@link #close()} always removes the temp
     * file afterwards.</p>
     *
     * @param stepExecution the completed (or failing) step execution; never {@code null}
     * @return {@code null} on success, or {@link ExitStatus#FAILED} when totals rendering or the S3
     *         upload fails
     */
    @Override
    public ExitStatus afterStep(final StepExecution stepExecution) {
        if (stepExecution.getStatus() != BatchStatus.COMPLETED) {
            LOG.warn("Transaction-report step status is {} at afterStep; skipping report upload",
                    stepExecution.getStatus());
            return null;
        }
        if (closed) {
            return null;
        }
        closed = true;
        try {
            if (writer != null && anyDetailWritten) {
                // COBOL EOF path (corrected): final account total, then page total, then grand total.
                writeAccountTotals();
                writePageTotals();
                writeGrandTotals();
            }
            closeWriterQuietly();
            if (anyDetailWritten && tempFile != null) {
                uploadReport();
            }
        } catch (final RuntimeException e) {
            LOG.error("Transaction-report finalization failed; failing the step", e);
            stepExecution.addFailureException(e);
            stepExecution.setStatus(BatchStatus.FAILED);
            return ExitStatus.FAILED;
        }
        return null;
    }

    /**
     * Releases the report buffer and deletes the temp file. <strong>Cleanup only</strong> (QA finding
     * <strong>F4</strong>, decision {@code D-027}): the totals rendering and S3 upload were moved to
     * {@link #afterStep(StepExecution)} because {@code AbstractStep} swallows any exception thrown from
     * {@code close()} and has already persisted the step/job status by the time {@code close()} runs, so
     * an upload here could never fail the step. This method performs no upload; it guarantees the writer
     * is closed and the temp file removed regardless of the step outcome. It is null-safe and idempotent.
     *
     * @throws ItemStreamException never thrown (cleanup failures are logged, not propagated)
     */
    @Override
    public void close() throws ItemStreamException {
        closeWriterQuietly();
        deleteTempFileQuietly();
    }

    // ---------------------------------------------------------------------------------------------
    // ItemWriter
    // ---------------------------------------------------------------------------------------------

    /**
     * Writes one chunk of detail rows, preserving the exact {@code CBTRN03C} control-flow order.
     *
     * <p>Because the writer is stateful across the whole step, this method never resets any counter
     * or total; it simply continues from where the previous chunk left off. Each row is processed by
     * {@link #writeLine(ReportDetailLine)}.</p>
     *
     * @param chunk the chunk of report rows to emit; must not be {@code null}
     * @throws FileProcessingException if the writer is used outside its {@code open()..close()} window
     *                                 or a temp-file write fails
     */
    @Override
    public void write(Chunk<? extends ReportDetailLine> chunk) {
        Objects.requireNonNull(chunk, "chunk must not be null");
        if (writer == null || closed) {
            throw new FileProcessingException(
                    "TransactionReportItemWriter used outside its open()..close() lifecycle",
                    ABEND_CODE, ABEND_CULPRIT, null, null);
        }
        for (ReportDetailLine line : chunk.getItems()) {
            writeLine(line);
        }
    }

    /**
     * Emits a single detail row, reproducing paragraph {@code 1100-WRITE-TRANSACTION-REPORT} plus the
     * account control break performed by the {@code CBTRN03C} main loop immediately before it.
     *
     * <p>Order of operations (identical to the legacy program):</p>
     * <ol>
     *   <li>on the very first row, emit the opening headers ({@code WS-FIRST-TIME});</li>
     *   <li>on a change of {@link ReportDetailLine#accountId()}, emit the previous account's total
     *       ({@code 1120-WRITE-ACCOUNT-TOTALS}) and reset the account total;</li>
     *   <li>when {@code lineCounter} reaches a whole multiple of {@link #pageSize}
     *       ({@code FUNCTION MOD(WS-LINE-COUNTER, WS-PAGE-SIZE) = 0}), emit the page total and a
     *       fresh set of headers;</li>
     *   <li>accumulate the amount into the page, account and grand totals (scale&nbsp;2, HALF_UP);</li>
     *   <li>emit the detail line and advance {@code lineCounter}.</li>
     * </ol>
     *
     * @param line the row to emit; must not be {@code null}
     */
    private void writeLine(ReportDetailLine line) {
        Objects.requireNonNull(line, "report line must not be null");

        if (firstTime) {
            writeHeaders();
            firstTime = false;
        }

        final Long accountId = line.accountId();
        if (currentAccountId != null && !Objects.equals(currentAccountId, accountId)) {
            writeAccountTotals();
        }
        currentAccountId = accountId;

        if (lineCounter > 0 && lineCounter % pageSize == 0) {
            writePageTotals();
            writeHeaders();
        }

        final BigDecimal amount = line.amount();
        pageTotal = pageTotal.add(amount).setScale(2, RoundingMode.HALF_UP);
        accountTotal = accountTotal.add(amount).setScale(2, RoundingMode.HALF_UP);
        grandTotal = grandTotal.add(amount).setScale(2, RoundingMode.HALF_UP);

        writeDetail(line);
    }

    // ---------------------------------------------------------------------------------------------
    // Report record builders (one method per CBTRN03C paragraph)
    // ---------------------------------------------------------------------------------------------

    /**
     * Emits the four-line page header block ({@code 1120-WRITE-HEADERS}): the report name header, a
     * blank line, the column header line and the dashed separator. Advances {@code lineCounter} by 4.
     */
    private void writeHeaders() {
        writeRecord(buildReportNameHeader());
        writeRecord(BLANK_LINE);
        writeRecord(TRANSACTION_HEADER_1);
        writeRecord(SEPARATOR_LINE);
        lineCounter += 4;
    }

    /**
     * Builds the {@code REPORT-NAME-HEADER} line from {@code CVTRA07Y}: the short name, long name,
     * the {@code "Date Range: "} label and the start/end dates. 115 characters before the record is
     * padded to {@value #RECORD_LENGTH}.
     *
     * @return the assembled (pre-pad) name-header content
     */
    private String buildReportNameHeader() {
        return pad("DALYREPT", 38)
                + pad("Daily Transaction Report", 41)
                + "Date Range: "
                + pad(reportStartDate, 10)
                + " to "
                + pad(reportEndDate, 10);
    }

    /**
     * Emits one detail line ({@code 1120-WRITE-DETAIL}) with every column placed at the exact
     * position defined by {@code TRANSACTION-DETAIL-REPORT} in {@code CVTRA07Y} (114 characters
     * before the record is padded to {@value #RECORD_LENGTH}). Advances {@code lineCounter} by 1 and
     * marks that detail output has begun.
     *
     * @param line the row whose fields populate the detail columns
     */
    private void writeDetail(ReportDetailLine line) {
        final StringBuilder sb = new StringBuilder(RECORD_LENGTH);
        sb.append(pad(line.transactionId(), 16));      // TRAN-REPORT-TRANS-ID  X(16)
        sb.append(' ');                                // FILLER                X(01)
        sb.append(formatAccountId(line.accountId()));  // TRAN-REPORT-ACCOUNT-ID X(11)
        sb.append(' ');                                // FILLER                X(01)
        sb.append(pad(line.typeCode(), 2));            // TRAN-REPORT-TYPE-CD   X(02)
        sb.append('-');                                // FILLER                X(01) '-'
        sb.append(pad(line.typeDescription(), 15));    // TRAN-REPORT-TYPE-DESC X(15)
        sb.append(' ');                                // FILLER                X(01)
        sb.append(formatCategoryCode(line.categoryCode())); // TRAN-REPORT-CAT-CD 9(04)
        sb.append('-');                                // FILLER                X(01) '-'
        sb.append(pad(line.categoryDescription(), 29));// TRAN-REPORT-CAT-DESC  X(29)
        sb.append(' ');                                // FILLER                X(01)
        sb.append(pad(line.source(), 10));             // TRAN-REPORT-SOURCE    X(10)
        sb.append("    ");                             // FILLER                X(04)
        sb.append(formatAmount(line.amount(), false)); // TRAN-REPORT-AMT  -ZZZ,ZZZ,ZZZ.ZZ
        sb.append("  ");                               // FILLER                X(02)
        writeRecord(sb.toString());
        lineCounter++;
        anyDetailWritten = true;
    }

    /**
     * Emits the page-total line and a fresh separator ({@code 1110-WRITE-PAGE-TOTALS}), then resets
     * the page total. Advances {@code lineCounter} by 2.
     *
     * <p>Unlike the legacy paragraph this does <strong>not</strong> fold the page total into the
     * grand total, because the grand total is accumulated per detail row in
     * {@link #writeLine(ReportDetailLine)} (see the class-level totalling note).</p>
     */
    private void writePageTotals() {
        writeRecord(PAGE_TOTAL_PREFIX + formatAmount(pageTotal, true));
        pageTotal = ZERO_SCALE_2;
        lineCounter++;
        writeRecord(SEPARATOR_LINE);
        lineCounter++;
    }

    /**
     * Emits the account-total line and a fresh separator ({@code 1120-WRITE-ACCOUNT-TOTALS}), then
     * resets the account total. Advances {@code lineCounter} by 2.
     */
    private void writeAccountTotals() {
        writeRecord(ACCOUNT_TOTAL_PREFIX + formatAmount(accountTotal, true));
        accountTotal = ZERO_SCALE_2;
        lineCounter++;
        writeRecord(SEPARATOR_LINE);
        lineCounter++;
    }

    /**
     * Emits the grand-total line ({@code 1110-WRITE-GRAND-TOTALS}). Mirroring the legacy paragraph,
     * {@code lineCounter} is not advanced (this is always the final record of the report).
     */
    private void writeGrandTotals() {
        writeRecord(GRAND_TOTAL_PREFIX + formatAmount(grandTotal, true));
    }

    /**
     * Normalises {@code content} to exactly {@value #RECORD_LENGTH} characters and writes it to the
     * temp-file buffer followed by a single {@code '\n'} line terminator
     * ({@code 1111-WRITE-REPORT-REC}).
     *
     * @param content the pre-assembled record content (padded/truncated to the fixed width here)
     * @throws FileProcessingException if the underlying buffer write fails
     */
    private void writeRecord(String content) {
        final String record = pad(content, RECORD_LENGTH);
        try {
            writer.write(record);
            writer.write('\n');
        } catch (IOException e) {
            LOG.error("Failed writing a transaction report record to temp buffer {}", tempFile, e);
            throw new FileProcessingException(
                    "Failed writing transaction report record to temporary buffer",
                    ABEND_CODE, ABEND_CULPRIT, null, e);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // S3 upload + resource cleanup
    // ---------------------------------------------------------------------------------------------

    /**
     * Streams the assembled temp-file report to the configured S3 bucket/key through
     * {@link S3Template}. The object is the modern equivalent of a GDG generation (AAP&nbsp;§0.7.7).
     *
     * @throws FileProcessingException if the file cannot be read or the S3 upload fails
     */
    private void uploadReport() {
        try {
            final long contentLength = Files.size(tempFile);
            final ObjectMetadata metadata = ObjectMetadata.builder()
                    .contentType("text/plain")
                    .contentLength(contentLength)
                    .build();
            try (InputStream in = Files.newInputStream(tempFile)) {
                s3Template.upload(outputBucket, objectKey, in, metadata);
            }
            LOG.info("Uploaded transaction detail report to s3://{}/{} ({} bytes, {} lines)",
                    outputBucket, objectKey, contentLength, lineCounter);
        } catch (IOException | RuntimeException e) {
            LOG.error("Failed uploading transaction detail report to s3://{}/{}", outputBucket, objectKey, e);
            throw new FileProcessingException(
                    "Failed uploading transaction detail report to s3://" + outputBucket + "/" + objectKey,
                    ABEND_CODE, ABEND_CULPRIT, null, e);
        }
    }

    /**
     * Closes the buffered writer if open, swallowing (but logging) any failure so cleanup can proceed.
     * Idempotent: safe to call more than once.
     */
    private void closeWriterQuietly() {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException e) {
                LOG.warn("Failed to close the temporary report buffer {}", tempFile, e);
            } finally {
                writer = null;
            }
        }
    }

    /**
     * Deletes the temp-file buffer if present, swallowing (but logging) any failure. Idempotent.
     */
    private void deleteTempFileQuietly() {
        if (tempFile != null) {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException e) {
                LOG.warn("Failed to delete the temporary report buffer {}", tempFile, e);
            } finally {
                tempFile = null;
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Fixed-width / COBOL numeric-edit formatting helpers
    // ---------------------------------------------------------------------------------------------

    /**
     * Left-justifies {@code value} in a field of {@code width} characters, space-padding on the right
     * and truncating any overflow &mdash; the behaviour of a COBOL {@code MOVE} into a
     * {@code PIC X(width)} field. A {@code null} value is treated as an empty string.
     *
     * @param value the value to place (may be {@code null})
     * @param width the fixed field width; must be non-negative
     * @return a string of exactly {@code width} characters
     */
    private static String pad(String value, int width) {
        final String v = (value == null) ? "" : value;
        if (v.length() == width) {
            return v;
        }
        if (v.length() > width) {
            return v.substring(0, width);
        }
        return v + " ".repeat(width - v.length());
    }

    /**
     * Renders a monetary amount into the 15-character COBOL edited picture
     * {@code -ZZZ,ZZZ,ZZZ.ZZ} (detail, {@code plusSign == false}) or {@code +ZZZ,ZZZ,ZZZ.ZZ}
     * (totals, {@code plusSign == true}): a single fixed sign position followed by a 14-character
     * zero-suppressed, comma-grouped numeric field.
     *
     * <p>Sign position: {@code '-'} for a negative value; for a non-negative value {@code '+'} when
     * {@code plusSign} is set, otherwise a space. Per the COBOL all-{@code Z} rule, a zero value
     * blanks the entire 14-character numeric field. All arithmetic is {@link BigDecimal}; no
     * {@code float}/{@code double} is used.</p>
     *
     * @param value    the amount to edit (re-scaled to two fractional digits, HALF_UP)
     * @param plusSign {@code true} to render a leading {@code '+'} for non-negative values
     * @return a string of exactly 15 characters
     */
    private static String formatAmount(BigDecimal value, boolean plusSign) {
        final BigDecimal scaled = value.setScale(2, RoundingMode.HALF_UP);
        final boolean negative = scaled.signum() < 0;
        final char sign = negative ? '-' : (plusSign ? '+' : ' ');
        final String numeric = (scaled.signum() == 0) ? " ".repeat(14) : editNumeric(scaled.abs());
        return sign + numeric;
    }

    /**
     * Edits a strictly positive amount into the 14-character {@code ZZZ,ZZZ,ZZZ.ZZ} field: nine
     * integer digit positions (with grouping commas) subject to leading-zero suppression, a decimal
     * point, and two always-present fraction digits. Leading zeros &mdash; and any grouping comma
     * that precedes the first significant digit &mdash; are replaced by spaces. Integer overflow
     * beyond nine digits is high-order truncated, matching a COBOL {@code MOVE} into the smaller
     * picture.
     *
     * @param absValue a positive amount of scale&nbsp;2
     * @return the 14-character edited numeric field
     */
    private static String editNumeric(BigDecimal absValue) {
        final String plain = absValue.toPlainString();
        final int dot = plain.indexOf('.');
        String intPart = plain.substring(0, dot);
        final String fracPart = plain.substring(dot + 1);

        if (intPart.length() > AMOUNT_INTEGER_DIGITS) {
            intPart = intPart.substring(intPart.length() - AMOUNT_INTEGER_DIGITS);
        } else if (intPart.length() < AMOUNT_INTEGER_DIGITS) {
            intPart = "0".repeat(AMOUNT_INTEGER_DIGITS - intPart.length()) + intPart;
        }

        final char[] digits = intPart.toCharArray();
        final StringBuilder sb = new StringBuilder(14);
        boolean seenNonZero = false;
        for (int i = 0; i < AMOUNT_INTEGER_DIGITS; i++) {
            if (i == 3 || i == 6) {
                sb.append(seenNonZero ? ',' : ' ');
            }
            final char d = digits[i];
            if (!seenNonZero && d == '0') {
                sb.append(' ');
            } else {
                seenNonZero = true;
                sb.append(d);
            }
        }
        sb.append('.');
        sb.append(fracPart);
        return sb.toString();
    }

    /**
     * Renders an account id into the 11-character {@code TRAN-REPORT-ACCOUNT-ID} field, reproducing
     * the legacy {@code XREF-ACCT-ID PIC 9(11)} moved into {@code PIC X(11)}: right-justified, zero
     * filled. A {@code null} id yields 11 spaces; an id wider than 11 digits is low-order truncated.
     *
     * @param accountId the account id (may be {@code null})
     * @return a string of exactly 11 characters
     */
    private static String formatAccountId(Long accountId) {
        if (accountId == null) {
            return " ".repeat(11);
        }
        String s = Long.toString(accountId);
        if (s.startsWith("-")) {
            s = s.substring(1);
        }
        if (s.length() > 11) {
            s = s.substring(s.length() - 11);
        } else if (s.length() < 11) {
            s = "0".repeat(11 - s.length()) + s;
        }
        return s;
    }

    /**
     * Renders a transaction category code into the 4-character {@code TRAN-REPORT-CAT-CD PIC 9(04)}
     * field: right-justified, zero filled. A {@code null} code is treated as {@code 0}; a value wider
     * than four digits is low-order truncated.
     *
     * @param categoryCode the category code (may be {@code null})
     * @return a string of exactly 4 characters
     */
    private static String formatCategoryCode(Integer categoryCode) {
        String s = Integer.toString(categoryCode == null ? 0 : categoryCode);
        if (s.startsWith("-")) {
            s = s.substring(1);
        }
        if (s.length() > 4) {
            s = s.substring(s.length() - 4);
        } else if (s.length() < 4) {
            s = "0".repeat(4 - s.length()) + s;
        }
        return s;
    }
}


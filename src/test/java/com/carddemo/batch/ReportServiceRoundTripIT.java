package com.carddemo.batch;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import io.awspring.cloud.sqs.listener.MessageListenerContainerRegistry;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.carddemo.dto.ReportRequest;
import com.carddemo.dto.ReportResponse;
import com.carddemo.entity.Transaction;
import com.carddemo.observability.CorrelationIdFilter;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.service.ReportService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>Gate&nbsp;5 real producer&nbsp;&rarr;&nbsp;consumer round-trip</strong> for the migrated
 * {@code CORPT00C} report bridge, verifying the SQS FIFO payload-type-header defect fix (QA finding
 * <em>F3</em>). Unlike {@link ReportJobLauncherIT} &mdash; which hand-crafts a JSON string and puts it
 * on the queue with a raw SDK {@code sendMessage} &mdash; this test drives the <em>real production
 * producer</em>, {@link ReportService#generateReport(ReportRequest)}, which serializes a typed
 * {@link ReportService.ReportJobMessage} through the application's own {@code SqsTemplate} bean. It
 * therefore exercises the exact send path a REST caller triggers, end to end:
 *
 * <pre>
 *   ReportService.generateReport(CUSTOM, confirmed)          [real producer, real SqsTemplate]
 *      -&gt; SQS FIFO carddemo-report-jobs.fifo                 [real LocalStack transport]
 *      -&gt; &#64;SqsListener ReportJobLauncher.onReportRequest    [String payload, real consumer]
 *      -&gt; transactionReportJob (Spring Batch, PostgreSQL 16)
 *      -&gt; tranrept.dat (LRECL=133) in carddemo-batch-output  [real LocalStack S3]
 * </pre>
 *
 * <h2>Why this is the decisive test for F3</h2>
 * <p>The defect: the auto-configured {@code SqsTemplate}'s default message converter stamps every
 * message with a {@code JavaType} header carrying the payload's fully-qualified class name
 * ({@code com.carddemo.service.ReportService$ReportJobMessage}). The consumer's handler accepts a raw
 * {@link String}, so on receipt the converter tries to instantiate that producer-side class for a
 * {@code String} target and raises a {@code MessageConversionException} &mdash; the job never launches
 * and the message is redelivered until it is dropped. The fix is a dedicated
 * {@code SqsTemplateConfig.sqsTemplate} bean built with
 * {@code configureDefaultConverter(c -> c.doNotSendPayloadTypeHeader())}, so the header is omitted and
 * the {@code String} consumer converts cleanly.</p>
 *
 * <p>This test discriminates the fix precisely: if the payload-type header were still sent, the
 * listener conversion would fail, {@code transactionReportJob} would never run, and
 * {@code tranrept.dat} would never appear &mdash; the {@link Awaitility} poll would time out and the
 * test would fail. Its success proves the real producer&rarr;consumer contract holds (AAP Gate&nbsp;5)
 * with <strong>zero live AWS</strong> (LocalStack only, AAP&nbsp;&sect;0.7.7).</p>
 *
 * <h2>Correlation propagation (Observability rule, AAP&nbsp;&sect;0.7.1)</h2>
 * <p>The producer copies the current MDC {@code correlationId}
 * ({@link CorrelationIdFilter#CORRELATION_ID_MDC_KEY}) onto the trigger message; the consumer
 * re-establishes it as the {@code correlationId} job parameter. This test seeds a unique MDC
 * correlation id before calling the producer and asserts exactly one {@code transactionReportJob}
 * execution carrying it, proving the REST&nbsp;&rarr;&nbsp;SQS&nbsp;&rarr;&nbsp;batch trace is stitched
 * end to end.</p>
 *
 * <h2>Context isolation from {@link ReportJobLauncherIT}</h2>
 * <p>This class declares its own {@link DynamicPropertySource} pre-refresh hook
 * ({@link #createReportQueueBeforeContextRefresh(DynamicPropertyRegistry)}), a distinct method from the
 * sibling IT's, so Spring's {@code DynamicPropertiesContextCustomizer} cache key differs and this test
 * runs in its <em>own</em> application context with its own {@code @SqsListener}. Failsafe runs test
 * classes sequentially and each IT stops its listener in teardown, so at most one listener polls the
 * shared LocalStack queue at any time &mdash; no cross-class FIFO competition. Self-provisioning and
 * teardown follow AAP&nbsp;&sect;0.7.7: this test creates the output bucket and the FIFO queue it uses
 * and removes them afterwards, never depending on pre-existing state.</p>
 *
 * <p>Source COBOL/JCL is referenced read-only at commit SHA {@code 27d6c6f}; it is not copied here.
 * Design rationale lives in {@code docs/decision-log.md}, not in these comments (Explainability rule).</p>
 *
 * @see ReportService
 * @see ReportJobLauncher
 * @see SqsTemplateConfig
 * @see ReportJobLauncherIT
 * @see AbstractBatchIntegrationTest
 */
@DisplayName("ReportService round-trip IT — real producer → SQS FIFO → @SqsListener → transactionReportJob → S3 (F3)")
class ReportServiceRoundTripIT extends AbstractBatchIntegrationTest {

    // ---------------------------------------------------------------------------------------------
    // Report artifact / message contract constants (mirror the production defaults they exercise)
    // ---------------------------------------------------------------------------------------------

    /** Stable S3 object key of the report artifact ({@code carddemo.batch.report.object-key}). */
    private static final String REPORT_OBJECT_KEY = "tranrept.dat";

    /** Bean name of the launched Spring Batch job ({@code TransactionReportJob#transactionReportJob}). */
    private static final String REPORT_JOB_NAME = "transactionReportJob";

    /**
     * Unique MDC correlation id seeded before the producer call; the producer propagates it onto the
     * FIFO message and the consumer re-establishes it as the job's {@code correlationId} parameter.
     * Distinct from {@link ReportJobLauncherIT}'s id so cross-class batch-metadata queries never clash.
     */
    private static final String RT_CORRELATION_ID = "it-roundtrip-001";

    /** Inclusive reporting-window start (JCL {@code PARM-START-DATE}). */
    private static final LocalDate WINDOW_START_DATE = LocalDate.parse("2022-01-01");

    /** Inclusive reporting-window end (JCL {@code PARM-END-DATE}). */
    private static final LocalDate WINDOW_END_DATE = LocalDate.parse("2022-07-06");

    // ---------------------------------------------------------------------------------------------
    // Deterministic seed data (a small, known set of in-window, enrichable transactions)
    // ---------------------------------------------------------------------------------------------

    /**
     * Card number present in the V3-seeded {@code card_xref} (resolves to account&nbsp;2), so the
     * report processor's {@code 1500-A-LOOKUP-XREF} enrichment succeeds and the rows survive the
     * filter instead of being dropped.
     */
    private static final String SEED_CARD_NUMBER = "0923877193247330";

    /** Transaction type code {@code '01'} (V3 {@code transaction_type} = "Purchase"). */
    private static final String SEED_TYPE_CODE = "01";

    /** Transaction category code {@code 1} (V3 {@code transaction_category_type} ('01',1)). */
    private static final int SEED_CATEGORY_CODE = 1;

    /**
     * Processing timestamp text ({@code TRAN-PROC-TS PIC X(26)}) whose leading {@code yyyy-MM-dd}
     * ({@code 2022-06-10}) falls inside the inclusive reporting window, so the processor keeps the rows.
     */
    private static final String IN_WINDOW_PROC_TS = "2022-06-10-12.00.00.000000";

    /** First seeded transaction id (16 chars, {@code TRAN-ID PIC X(16)}); distinct from the sibling IT. */
    private static final String SEED_TRAN_ID_1 = "IT000000000RT001";

    /** Second seeded transaction id (16 chars, {@code TRAN-ID PIC X(16)}); distinct from the sibling IT. */
    private static final String SEED_TRAN_ID_2 = "IT000000000RT002";

    /** First seeded amount (scale&nbsp;2). */
    private static final BigDecimal SEED_AMOUNT_1 = new BigDecimal("100.00");

    /** Second seeded amount (scale&nbsp;2). */
    private static final BigDecimal SEED_AMOUNT_2 = new BigDecimal("250.50");

    /** Expected grand total = {@link #SEED_AMOUNT_1} + {@link #SEED_AMOUNT_2} (scale&nbsp;2). */
    private static final BigDecimal EXPECTED_GRAND_TOTAL = new BigDecimal("350.50");

    // ---------------------------------------------------------------------------------------------
    // Fixed-width report layout constants (from TransactionReportItemWriter, CBTRN03C / CVTRA07Y)
    // ---------------------------------------------------------------------------------------------

    /** Fixed record width of the {@code TRANREPT} dataset ({@code LRECL=133}). */
    private static final int RECORD_LENGTH = 133;

    /**
     * Character offset of the amount field on a total line: {@code pad("Grand Total", 11)} (11) plus
     * the {@code X(86) ALL '.'} filler (86) equals 97 characters of prefix.
     */
    private static final int TOTAL_AMOUNT_OFFSET = 97;

    /** Width of the edited amount field on a total line ({@code PIC +ZZZ,ZZZ,ZZZ.ZZ}). */
    private static final int TOTAL_AMOUNT_WIDTH = 15;

    /** Leading label of the grand-total record ({@code REPORT-GRAND-TOTALS}); uniquely identifies it. */
    private static final String GRAND_TOTAL_LABEL = "Grand Total";

    /** Maximum time to await asynchronous listener consumption + job execution + S3 upload. */
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(60);

    /** Poll interval while awaiting the report object. */
    private static final Duration AWAIT_POLL_INTERVAL = Duration.ofSeconds(1);

    // ---------------------------------------------------------------------------------------------
    // Injected collaborators
    // ---------------------------------------------------------------------------------------------

    /** The real production producer under test (drives the application's own {@code SqsTemplate} bean). */
    @Autowired
    private ReportService reportService;

    /** Repository used to seed and clear the known in-window {@link Transaction} rows. */
    @Autowired
    private TransactionRepository transactionRepository;

    /** Read-only view of the Spring Batch meta-data, used to assert the launched execution. */
    @Autowired
    private JobExplorer jobExplorer;

    /**
     * The Spring Cloud AWS SQS listener registry (a {@link org.springframework.context.SmartLifecycle}).
     * Stopped in {@link #teardown()} <em>before</em> the FIFO queue is deleted so the (context-scoped)
     * {@code @SqsListener} stops polling first; otherwise it would keep polling the just-deleted queue
     * and log benign but noisy {@code QueueDoesNotExistException}s for the remainder of the JVM's test run.
     */
    @Autowired
    private MessageListenerContainerRegistry sqsListenerContainerRegistry;

    // ---------------------------------------------------------------------------------------------
    // Phase 1 — pre-create the FIFO queue BEFORE the context refreshes (critical ordering)
    // ---------------------------------------------------------------------------------------------

    /**
     * Creates the report-request FIFO queue in the shared LocalStack container before the Spring
     * context is refreshed, so the auto-started {@code @SqsListener} finds it on startup. This method
     * is intentionally distinct from {@link ReportJobLauncherIT}'s equivalent so this test resolves to
     * its own, isolated application context (see the class Javadoc). It creates a genuine FIFO queue
     * ({@code FifoQueue=true}, {@code ContentBasedDeduplication=true}) using the container's bundled
     * {@code awslocal} CLI, needing no Spring-managed AWS client (none exists yet at this point).
     *
     * @param registry the Spring dynamic-property registry (unused; the queue is created as a side effect)
     */
    @DynamicPropertySource
    static void createReportQueueBeforeContextRefresh(final DynamicPropertyRegistry registry) {
        // Delegate to the shared, idempotent + normalizing creator (see
        // AbstractBatchIntegrationTest#ensureReportRequestFifoQueue). Because the batch IT suite shares one
        // static LocalStack across all subclass tests and application-test.yml sets
        // queue-not-found-strategy=create, a sibling full-context IT's @SqsListener may have already
        // auto-created this FIFO queue WITHOUT ContentBasedDeduplication; the shared helper tolerates that
        // and forces CBD=true so this test's real producer (SqsTemplate, no dedup id) converts cleanly.
        ensureReportRequestFifoQueue();
    }

    // ---------------------------------------------------------------------------------------------
    // Phase 2 — self-provision the output bucket + seed deterministic in-window transactions
    // ---------------------------------------------------------------------------------------------

    /**
     * Provisions the batch-output S3 bucket and seeds a small, known set of in-window transactions so
     * the generated report is deterministic regardless of any ambient data. The test is intentionally
     * <em>not</em> transactional: the seed rows are committed here so the asynchronous batch job — which
     * runs on the SQS listener thread over its own connection — can read them.
     */
    @BeforeEach
    void provisionBucketAndSeedTransactions() {
        createBucket(BUCKET_OUTPUT);
        transactionRepository.deleteAll();
        transactionRepository.saveAll(List.of(
                newSeededTransaction(SEED_TRAN_ID_1, SEED_AMOUNT_1),
                newSeededTransaction(SEED_TRAN_ID_2, SEED_AMOUNT_2)));
    }

    // ---------------------------------------------------------------------------------------------
    // Phase 3 + 4 — drive the REAL producer, await async processing, and assert the contract
    // ---------------------------------------------------------------------------------------------

    /**
     * Calls {@link ReportService#generateReport(ReportRequest)} with a confirmed CUSTOM request and
     * asserts the full bridge contract end to end: the real producer enqueues a typed message (with the
     * payload-type header suppressed by the F3 fix), the {@code @SqsListener} consumes it as a
     * {@link String}, {@code transactionReportJob} runs exactly once carrying the seeded correlation id
     * and COMPLETES, and the fixed-width {@code tranrept.dat} report is written to S3 with a grand total
     * matching the seeded amounts.
     */
    @Test
    @DisplayName("The real ReportService producer path yields the 133-char tranrept.dat report in S3 (F3 header fix)")
    void realProducerPathProducesReportInS3() {
        // Seed a unique correlation id into the MDC so the producer copies it onto the FIFO message
        // (ReportService.resolveCorrelationId reads CorrelationIdFilter.CORRELATION_ID_MDC_KEY).
        MDC.put(CorrelationIdFilter.CORRELATION_ID_MDC_KEY, RT_CORRELATION_ID);
        final ReportResponse response;
        try {
            // Drive the REAL production producer: a confirmed CUSTOM report over the seeded window.
            final ReportRequest request =
                    new ReportRequest("CUSTOM", WINDOW_START_DATE, WINDOW_END_DATE, Boolean.TRUE);
            response = this.reportService.generateReport(request);
        } finally {
            // Always clear the MDC so no correlation id leaks to sibling tests on this thread.
            MDC.remove(CorrelationIdFilter.CORRELATION_ID_MDC_KEY);
        }

        // (0) The producer accepted and enqueued the job (proves the real SqsTemplate send succeeded).
        assertThat(response).as("generateReport must return a response").isNotNull();
        assertThat(response.status())
                .as("a confirmed CUSTOM request must be ACCEPTED")
                .isEqualTo(ReportResponse.STATUS_ACCEPTED);
        assertThat(response.jobId())
                .as("an accepted report must carry a non-null jobId")
                .isNotNull();

        // Listener consumption + job execution + S3 upload are asynchronous: poll (no Thread.sleep)
        // under a bounded timeout until the report object materialises. If the payload-type header were
        // still present (the F3 defect), listener conversion would fail and this would never become true.
        Awaitility.await("transaction report object " + BUCKET_OUTPUT + "/" + REPORT_OBJECT_KEY)
                .atMost(AWAIT_TIMEOUT)
                .pollInterval(AWAIT_POLL_INTERVAL)
                .until(() -> objectExists(BUCKET_OUTPUT, REPORT_OBJECT_KEY));

        // (1) The report artifact exists in the batch-output bucket.
        assertThat(objectExists(BUCKET_OUTPUT, REPORT_OBJECT_KEY))
                .as("report object must exist in S3 after the real producer path runs")
                .isTrue();

        // (2) Every report record is exactly 133 characters wide (LRECL=133 parity). The writer uses
        // ISO-8859-1 and terminates each record with a single '\n', so the last split element is the
        // empty string after the trailing newline.
        final String report =
                new String(readObject(BUCKET_OUTPUT, REPORT_OBJECT_KEY), StandardCharsets.ISO_8859_1);
        assertThat(report).as("report must end with a newline").endsWith("\n");
        final String[] lines = report.split("\n", -1);
        assertThat(lines[lines.length - 1]).as("trailing element after final newline is empty").isEmpty();
        for (int i = 0; i < lines.length - 1; i++) {
            assertThat(lines[i])
                    .as("report line %d must be exactly %d characters", i, RECORD_LENGTH)
                    .hasSize(RECORD_LENGTH);
        }

        // (3) The report contains detail rows for both seeded transactions.
        assertThat(report)
                .as("report must contain the seeded transaction ids")
                .contains(SEED_TRAN_ID_1)
                .contains(SEED_TRAN_ID_2);

        // (4) The grand total equals the sum of the seeded amounts (scale 2).
        final BigDecimal grandTotal = parseGrandTotal(lines);
        assertThat(grandTotal)
                .as("grand total must equal the sum of the seeded amounts")
                .isEqualByComparingTo(EXPECTED_GRAND_TOTAL);

        // (5) transactionReportJob ran exactly once with the seeded correlationId and COMPLETED,
        // proving the correlation id propagated REST -> SQS -> batch (Observability rule).
        final List<JobExecution> matchingExecutions = executionsWithCorrelationId(RT_CORRELATION_ID);
        assertThat(matchingExecutions)
                .as("transactionReportJob must run exactly once for correlationId %s", RT_CORRELATION_ID)
                .hasSize(1);
        final JobExecution execution = matchingExecutions.get(0);
        assertThat(execution.getStatus())
                .as("the launched report job must complete successfully")
                .isEqualTo(BatchStatus.COMPLETED);
        // The requested window was propagated verbatim as identifying job parameters.
        assertThat(execution.getJobParameters().getString("startDate")).isEqualTo(WINDOW_START_DATE.toString());
        assertThat(execution.getJobParameters().getString("endDate")).isEqualTo(WINDOW_END_DATE.toString());
    }

    // ---------------------------------------------------------------------------------------------
    // Phase 5 — teardown (§0.7.7): remove the bucket, the seeded rows, and the FIFO queue
    // ---------------------------------------------------------------------------------------------

    /**
     * Tears down everything this test provisioned so no S3/SQS or database state leaks to sibling
     * integration tests sharing the singleton containers (AAP&nbsp;&sect;0.7.7). The listener is stopped
     * first so it is no longer polling when the queue is removed; the bucket and queue deletions are
     * idempotent (a missing bucket/queue is treated as already-gone), so cleanup is safe even if setup
     * failed part-way.
     */
    @AfterEach
    void teardown() {
        sqsListenerContainerRegistry.stop();
        deleteBucketRecursively(BUCKET_OUTPUT);
        transactionRepository.deleteAll();
        deleteQueue(QUEUE_REPORT_FIFO);
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /**
     * Builds one in-window, enrichable {@link Transaction} for seeding. The card number resolves in the
     * V3 {@code card_xref}, and the type/category codes resolve in the V3 reference tables, so the report
     * processor keeps the row and renders its descriptions.
     *
     * @param tranId the 16-character transaction id (primary key); must not be {@code null}
     * @param amount the signed transaction amount (scale&nbsp;2); must not be {@code null}
     * @return a fully populated transaction ready to persist
     */
    private static Transaction newSeededTransaction(final String tranId, final BigDecimal amount) {
        final Transaction tx = new Transaction();
        tx.setTranId(tranId);
        tx.setTranTypeCd(SEED_TYPE_CODE);
        tx.setTranCatCd(SEED_CATEGORY_CODE);
        tx.setTranSource("POS");
        tx.setTranDesc("Integration-test seeded round-trip report row");
        tx.setTranAmt(amount);
        tx.setTranCardNum(SEED_CARD_NUMBER);
        tx.setTranOrigTs(IN_WINDOW_PROC_TS);
        tx.setTranProcTs(IN_WINDOW_PROC_TS);
        return tx;
    }

    /**
     * Extracts and parses the grand-total amount from the rendered report lines. The grand-total record
     * is the only line beginning with {@code "Grand Total"}; its edited amount occupies the fixed
     * 15-character field at offset {@value #TOTAL_AMOUNT_OFFSET}. The field is a single sign position
     * ({@code '+'} / {@code '-'} / space) followed by a zero-suppressed, comma-grouped
     * {@code ZZZ,ZZZ,ZZZ.ZZ} numeric; stripping the spaces and grouping commas yields the plain decimal,
     * and the sign is reapplied.
     *
     * @param lines the report split on {@code '\n'} (the trailing empty element is ignored)
     * @return the parsed grand total (scale&nbsp;2)
     * @throws AssertionError if no grand-total line is present
     */
    private static BigDecimal parseGrandTotal(final String[] lines) {
        for (final String line : lines) {
            if (line.startsWith(GRAND_TOTAL_LABEL)) {
                assertThat(line.length())
                        .as("grand-total line must be at least %d characters", TOTAL_AMOUNT_OFFSET + TOTAL_AMOUNT_WIDTH)
                        .isGreaterThanOrEqualTo(TOTAL_AMOUNT_OFFSET + TOTAL_AMOUNT_WIDTH);
                final String amountField =
                        line.substring(TOTAL_AMOUNT_OFFSET, TOTAL_AMOUNT_OFFSET + TOTAL_AMOUNT_WIDTH);
                final char sign = amountField.charAt(0);
                final String digits = amountField.substring(1).replace(",", "").replace(" ", "");
                final BigDecimal value = new BigDecimal(digits);
                return (sign == '-') ? value.negate() : value;
            }
        }
        throw new AssertionError("report did not contain a 'Grand Total' line");
    }

    /**
     * Collects every {@code transactionReportJob} execution whose non-identifying {@code correlationId}
     * job parameter equals the supplied value, across all instances of the job.
     *
     * @param correlationId the correlation id to match; must not be {@code null}
     * @return the matching executions (empty if none)
     */
    private List<JobExecution> executionsWithCorrelationId(final String correlationId) {
        final List<JobExecution> matches = new ArrayList<>();
        for (final JobInstance instance : jobExplorer.getJobInstances(REPORT_JOB_NAME, 0, 100)) {
            for (final JobExecution execution : jobExplorer.getJobExecutions(instance)) {
                final String executionCorrelationId =
                        execution.getJobParameters().getString(CorrelationIdFilter.CORRELATION_ID_MDC_KEY);
                if (correlationId.equals(executionCorrelationId)) {
                    matches.add(execution);
                }
            }
        }
        return matches;
    }
}

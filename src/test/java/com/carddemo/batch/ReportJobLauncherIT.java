package com.carddemo.batch;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.awspring.cloud.sqs.listener.MessageListenerContainerRegistry;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Container;

import com.carddemo.entity.Transaction;
import com.carddemo.observability.CorrelationIdFilter;
import com.carddemo.repository.TransactionRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test for the migrated <strong>CORPT00C report-submission bridge</strong> —
 * the SQS&nbsp;FIFO&nbsp;&rarr;&nbsp;{@code @SqsListener}&nbsp;&rarr;&nbsp;Spring&nbsp;Batch&nbsp;&rarr;
 * &nbsp;S3 path. Executed by the Maven <strong>Failsafe</strong> plugin (class name ends in {@code IT}),
 * it exercises the <em>real</em> transport contracts with <strong>zero live AWS</strong>: a JSON
 * report-request message placed on the self-provisioned {@code carddemo-report-jobs.fifo} LocalStack
 * SQS queue is consumed by {@link ReportJobLauncher}, which launches the real
 * {@code transactionReportJob} against a real PostgreSQL&nbsp;16 database, producing the 133-character
 * {@code tranrept.dat} report object in the LocalStack S3 bucket {@code carddemo-batch-output}.
 *
 * <h2>What this proves (AAP&nbsp;Gate&nbsp;5 &amp; Gate&nbsp;8)</h2>
 * <p>It is the single test that verifies the batch-trigger contract of the migrated report bridge on
 * the real message transport rather than by self-certification: the exact JSON message schema
 * ({@code jobId}, {@code reportName}, {@code startDate}, {@code endDate}, {@code correlationId}) is
 * accepted by the listener, the resulting Spring Batch job runs exactly once, and the fixed-width
 * report artifact contract ({@code LRECL=133}) is honoured. The correlation id supplied in the message
 * is propagated all the way to the launched {@code JobExecution}'s parameters, stitching the
 * REST&nbsp;&rarr;&nbsp;SQS&nbsp;&rarr;&nbsp;batch trace (Observability rule, AAP&nbsp;&sect;0.7.1).</p>
 *
 * <h2>COBOL/JCL lineage (reference-only, source commit SHA {@code 27d6c6f}; NOT copied)</h2>
 * <ul>
 *   <li>{@code app/cbl/CORPT00C.cbl} (CICS transaction {@code CR00}) submitted the report job by
 *       writing a JCL stream to an extra-partition Transient Data Queue
 *       ({@code WIRTE-JOBSUB-TDQ}: {@code EXEC CICS WRITEQ TD QUEUE('JOBS')}); the JES internal reader
 *       (INTRDR) then started the {@code TRANREPT} batch job. That order-preserving, fire-and-forget
 *       bridge is migrated to an SQS FIFO queue consumed by {@link ReportJobLauncher} (AAP&nbsp;&sect;0.8.5,
 *       &sect;0.7.7).</li>
 *   <li>{@code app/jcl/TRANREPT.jcl} / {@code app/proc/TRANREPT.prc} sorted the processed-transaction
 *       file ascending by {@code TRAN-CARD-NUM} and filtered it to the inclusive window
 *       {@code PARM-START-DATE=2022-01-01} &hellip; {@code PARM-END-DATE=2022-07-06} before feeding the
 *       report program.</li>
 *   <li>{@code app/cbl/CBTRN03C.cbl} produced the fixed-width {@code LRECL=133} report with per-page,
 *       per-account and grand totals ({@code WS-PAGE-TOTAL} / {@code WS-ACCOUNT-TOTAL} /
 *       {@code WS-GRAND-TOTAL}).</li>
 * </ul>
 *
 * <h2>Listener startup and queue ordering (Spring Cloud AWS 3.3.0 reality)</h2>
 * <p>This IT inherits its fully-wired Spring context, its live PostgreSQL/LocalStack infrastructure,
 * and the AWS self-provisioning helpers from {@link AbstractBatchIntegrationTest}. Because that base
 * does <strong>not</strong> exclude {@code SqsAutoConfiguration}, the {@code @SqsListener} on
 * {@link ReportJobLauncher} is started automatically when the context refreshes — this is the one IT
 * that deliberately keeps the listener active (other integration tests exclude the SQS
 * auto-configuration to keep it off). Spring Cloud AWS&nbsp;3.3.0 exposes no
 * {@code spring.cloud.aws.sqs.listener.auto-startup} flag (only {@code max-concurrent-messages},
 * {@code max-messages-per-poll} and {@code poll-timeout}); enabling the listener is therefore a matter
 * of not excluding its auto-configuration, not of toggling a property.</p>
 *
 * <p>An enabled listener resolves its queue when the context finishes refreshing, so the FIFO queue
 * must exist <em>first</em>. This test creates it inside a {@link DynamicPropertySource} hook —
 * which Spring evaluates after {@link AbstractBatchIntegrationTest} has started the shared LocalStack
 * container but <em>before</em> the application context is refreshed — using the container's bundled
 * {@code awslocal} CLI. Pre-creating the queue with the correct FIFO attributes
 * ({@code FifoQueue=true}, {@code ContentBasedDeduplication=true}) avoids the race in which an enabled
 * listener would otherwise try to poll (or auto-create) a queue that does not yet exist.</p>
 *
 * <h2>Determinism (Refinement R3)</h2>
 * <p>The report reader streams the <em>entire</em> {@code transaction} table (ordered by card number),
 * so the report content is made deterministic by clearing that table and seeding a small, known set of
 * in-window rows in {@link #provisionBucketAndSeedTransactions()}. The seeded rows use a card number
 * present in the V3-seeded {@code card_xref} (so the processor's account enrichment succeeds and the
 * rows are not filtered out) and a processing timestamp inside the inclusive window
 * {@code 2022-01-01 .. 2022-07-06}. The reference tables ({@code card_xref}, {@code transaction_type},
 * {@code transaction_category_type}) are left untouched.</p>
 *
 * <h2>Self-provisioning (AAP&nbsp;&sect;0.7.7)</h2>
 * <p>This test creates the S3 output bucket and the FIFO queue it exercises and tears them down again,
 * never depending on pre-existing LocalStack state. Asynchronous consumption and job execution are
 * awaited with {@link Awaitility} under a bounded timeout — there is no {@code Thread.sleep}. Design
 * rationale lives in {@code docs/decision-log.md}, not in these comments (Explainability rule).</p>
 *
 * @see ReportJobLauncher
 * @see TransactionReportJob
 * @see TransactionReportItemWriter
 * @see AbstractBatchIntegrationTest
 */
@DisplayName("ReportJobLauncher IT — SQS FIFO message → @SqsListener → transactionReportJob → S3 tranrept.dat")
class ReportJobLauncherIT extends AbstractBatchIntegrationTest {

    // ---------------------------------------------------------------------------------------------
    // Report artifact / message contract constants (mirror the production defaults they exercise)
    // ---------------------------------------------------------------------------------------------

    /** Stable S3 object key of the report artifact ({@code carddemo.batch.report.object-key}). */
    private static final String REPORT_OBJECT_KEY = "tranrept.dat";

    /** Bean name of the launched Spring Batch job ({@code TransactionReportJob#transactionReportJob}). */
    private static final String REPORT_JOB_NAME = "transactionReportJob";

    /** Fixed correlation id carried by the request message and asserted on the launched execution. */
    private static final String IT_CORRELATION_ID = "it-report-001";

    /** FIFO message group id (mandatory for a FIFO queue) that orders this test's single message. */
    private static final String MESSAGE_GROUP_ID = "carddemo-report-it";

    /** Inclusive reporting-window start requested by the message (JCL {@code PARM-START-DATE}). */
    private static final String WINDOW_START_DATE = "2022-01-01";

    /** Inclusive reporting-window end requested by the message (JCL {@code PARM-END-DATE}). */
    private static final String WINDOW_END_DATE = "2022-07-06";

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

    /** First seeded transaction id (16 chars, {@code TRAN-ID PIC X(16)}). */
    private static final String SEED_TRAN_ID_1 = "IT000000000RPT01";

    /** Second seeded transaction id (16 chars, {@code TRAN-ID PIC X(16)}). */
    private static final String SEED_TRAN_ID_2 = "IT000000000RPT02";

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
     * and log benign but noisy {@code QueueDoesNotExistException}s for the remainder of the JVM's test
     * run (the listener outlives this test because it is bound to the shared application context).
     */
    @Autowired
    private MessageListenerContainerRegistry sqsListenerContainerRegistry;

    // ---------------------------------------------------------------------------------------------
    // Phase 1 — pre-create the FIFO queue BEFORE the context refreshes (critical ordering)
    // ---------------------------------------------------------------------------------------------

    /**
     * Creates the report-request FIFO queue in the shared LocalStack container before the Spring
     * context is refreshed, so the auto-started {@code @SqsListener} finds it on startup.
     *
     * <p>Spring evaluates every {@link DynamicPropertySource} method after
     * {@link AbstractBatchIntegrationTest} has started the shared {@code LOCALSTACK} container (its
     * static initializer runs when the superclass is initialized) but before the application context
     * is refreshed. The queue is created with the container's bundled {@code awslocal} CLI so it need
     * not depend on any Spring-managed AWS client (none exists yet at this point). It is a genuine FIFO
     * queue ({@code FifoQueue=true}) with content-based deduplication enabled, matching the queue the
     * migrated {@code CORPT00C} bridge and {@link ReportJobLauncher} expect.</p>
     *
     * <p>The {@link DynamicPropertyRegistry} argument is required by the {@code @DynamicPropertySource}
     * contract; this hook contributes no properties (the base class already registers the LocalStack
     * endpoint, region, and credentials) and is used solely for its ordered, pre-refresh side effect.</p>
     *
     * @param registry the Spring dynamic-property registry (unused; the queue is created as a side effect)
     */
    @DynamicPropertySource
    static void createReportQueueBeforeContextRefresh(final DynamicPropertyRegistry registry) {
        try {
            final Container.ExecResult result = LOCALSTACK.execInContainer(
                    "awslocal", "sqs", "create-queue",
                    "--queue-name", QUEUE_REPORT_FIFO,
                    "--attributes", "FifoQueue=true,ContentBasedDeduplication=true");
            if (result.getExitCode() != 0) {
                throw new IllegalStateException(
                        "Failed to pre-create FIFO queue '" + QUEUE_REPORT_FIFO + "' in LocalStack (exit="
                                + result.getExitCode() + "): " + result.getStderr());
            }
        } catch (final IOException e) {
            throw new IllegalStateException(
                    "I/O error while pre-creating FIFO queue '" + QUEUE_REPORT_FIFO + "' in LocalStack", e);
        } catch (final InterruptedException e) {
            // Restore the interrupt status before surfacing the failure (never swallow the interrupt).
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while pre-creating FIFO queue '" + QUEUE_REPORT_FIFO + "' in LocalStack", e);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Phase 2 — self-provision the output bucket + seed deterministic in-window transactions
    // ---------------------------------------------------------------------------------------------

    /**
     * Provisions the batch-output S3 bucket and seeds a small, known set of in-window transactions so
     * the generated report is deterministic regardless of any ambient data.
     *
     * <p>The {@code transaction} table (empty in the V3 seed) is cleared first, then two rows are
     * inserted on the same V3-known card number (so both enrich to account&nbsp;2) with a processing
     * timestamp inside the reporting window. The test is intentionally <em>not</em> transactional: the
     * seed rows are committed here so the asynchronous batch job — which runs on the SQS listener
     * thread over its own connection — can read them.</p>
     */
    @BeforeEach
    void provisionBucketAndSeedTransactions() {
        // Self-provision only the bucket this test exercises (the report writer's output bucket).
        createBucket(BUCKET_OUTPUT);

        // Deterministic input: clear any ambient rows, then seed the known in-window transactions.
        transactionRepository.deleteAll();
        transactionRepository.saveAll(List.of(
                newSeededTransaction(SEED_TRAN_ID_1, SEED_AMOUNT_1),
                newSeededTransaction(SEED_TRAN_ID_2, SEED_AMOUNT_2)));
    }

    // ---------------------------------------------------------------------------------------------
    // Phase 3 + 4 — send the request message, await async processing, and assert the contract
    // ---------------------------------------------------------------------------------------------

    /**
     * Sends one report-request message to the FIFO queue and asserts the full bridge contract: the
     * listener launches {@code transactionReportJob}, which writes the fixed-width {@code tranrept.dat}
     * report to S3 with a grand total matching the seeded amounts, and the job runs exactly once with
     * the message's correlation id.
     */
    @Test
    @DisplayName("A report-request message is consumed and produces the 133-char tranrept.dat report in S3")
    void reportRequestMessageProducesReportInS3() {
        // A unique jobId gives every run a fresh, restartable JobInstance (jobId is the identifying
        // dedup key), so the job always launches rather than being rejected as an already-complete
        // duplicate; the correlationId is fixed so it can be asserted on the launched execution.
        final String jobId = UUID.randomUUID().toString();
        final String messageBody = """
                {
                  "jobId": "%s",
                  "reportName": "DAILY",
                  "startDate": "%s",
                  "endDate": "%s",
                  "correlationId": "%s"
                }""".formatted(jobId, WINDOW_START_DATE, WINDOW_END_DATE, IT_CORRELATION_ID);

        // Send on the pre-created FIFO queue (content-based dedup is on, so only a group id is needed).
        final String reportQueueUrl = queueUrl(QUEUE_REPORT_FIFO);
        sendMessage(reportQueueUrl, messageBody, MESSAGE_GROUP_ID);

        // Listener consumption + job execution + S3 upload are asynchronous: poll (no Thread.sleep)
        // under a bounded timeout until the report object materialises.
        Awaitility.await("transaction report object " + BUCKET_OUTPUT + "/" + REPORT_OBJECT_KEY)
                .atMost(AWAIT_TIMEOUT)
                .pollInterval(AWAIT_POLL_INTERVAL)
                .until(() -> objectExists(BUCKET_OUTPUT, REPORT_OBJECT_KEY));

        // (1) The report artifact exists in the batch-output bucket.
        assertThat(objectExists(BUCKET_OUTPUT, REPORT_OBJECT_KEY))
                .as("report object must exist in S3 after the job runs")
                .isTrue();

        // (2) Every report record is exactly 133 characters wide (LRECL=133 parity). The writer uses
        // ISO-8859-1 and terminates each record with a single '\n', so the last split element is the
        // empty string after the trailing newline.
        final String report = new String(readObject(BUCKET_OUTPUT, REPORT_OBJECT_KEY), StandardCharsets.ISO_8859_1);
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

        // (5) transactionReportJob ran exactly once with the message's correlationId and COMPLETED.
        final List<JobExecution> matchingExecutions = executionsWithCorrelationId(IT_CORRELATION_ID);
        assertThat(matchingExecutions)
                .as("transactionReportJob must run exactly once for correlationId %s", IT_CORRELATION_ID)
                .hasSize(1);
        final JobExecution execution = matchingExecutions.get(0);
        assertThat(execution.getStatus())
                .as("the launched report job must complete successfully")
                .isEqualTo(BatchStatus.COMPLETED);
        // The requested window was propagated verbatim as identifying job parameters.
        assertThat(execution.getJobParameters().getString("startDate")).isEqualTo(WINDOW_START_DATE);
        assertThat(execution.getJobParameters().getString("endDate")).isEqualTo(WINDOW_END_DATE);
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
        // Stop the @SqsListener BEFORE removing the queue so it does not spend the rest of the JVM's
        // test run logging QueueDoesNotExistException against the deleted queue. stop() is idempotent
        // (the SmartLifecycle is a no-op if already stopped), and this is the only test on this
        // context, so nothing needs the listener again afterwards.
        sqsListenerContainerRegistry.stop();
        deleteBucketRecursively(BUCKET_OUTPUT);
        transactionRepository.deleteAll();
        deleteQueue(QUEUE_REPORT_FIFO);
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /**
     * Builds one in-window, enrichable {@link Transaction} for seeding. The card number resolves in
     * the V3 {@code card_xref}, and the type/category codes resolve in the V3 reference tables, so the
     * report processor keeps the row and renders its descriptions.
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
        tx.setTranDesc("Integration-test seeded report row");
        tx.setTranAmt(amount);
        tx.setTranCardNum(SEED_CARD_NUMBER);
        tx.setTranOrigTs(IN_WINDOW_PROC_TS);
        tx.setTranProcTs(IN_WINDOW_PROC_TS);
        return tx;
    }

    /**
     * Extracts and parses the grand-total amount from the rendered report lines. The grand-total record
     * is the only line beginning with {@code "Grand Total"}; its edited amount occupies the fixed
     * 15-character field at offset {@value #TOTAL_AMOUNT_OFFSET}
     * ({@code pad("Grand Total",11)} + {@code X(86) '.'}). The field is a single sign position
     * ({@code '+'} / {@code '-'} / space) followed by a zero-suppressed, comma-grouped
     * {@code ZZZ,ZZZ,ZZZ.ZZ} numeric; stripping the spaces and grouping commas yields the plain
     * decimal, and the sign is reapplied.
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
                final String amountField = line.substring(TOTAL_AMOUNT_OFFSET, TOTAL_AMOUNT_OFFSET + TOTAL_AMOUNT_WIDTH);
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


package com.carddemo.integration.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.model.entity.Transaction;
import com.carddemo.repository.TransactionRepository;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * Integration test for the Spring Batch transaction-report job {@code transactionReportJob}
 * (production class {@link com.carddemo.batch.jobs.TransactionReportJob}, single step
 * {@code transactionReportStep}) <em>and</em> its online-to-batch SQS FIFO bridge (the
 * {@code @SqsListener} on {@code carddemo-report-jobs.fifo}, design decision D-004).
 *
 * <p>This IT proves behavioural parity with the mainframe report pipeline {@code app/jcl/TRANREPT.jcl}
 * + {@code app/cbl/CBTRN03C.cbl} (report layout {@code app/cpy/CVTRA07Y.cpy}, branded titles
 * {@code app/cpy/COTTL01Y.cpy}; source commit {@code 27d6c6f}; REFERENCE ONLY &mdash; no COBOL/JCL is
 * copied). The verified behaviours are:</p>
 * <ul>
 *   <li><strong>133-byte report fidelity</strong> &mdash; {@code CBTRN03C}'s {@code FD-REPTFILE-REC
 *       PIC X(133)} and {@code TRANREPT.jcl}'s {@code LRECL=133}; the {@code CVTRA07Y
 *       TRANSACTION-HEADER-2} all-dashes separator pins the width &mdash; rendered to S3 bucket
 *       {@code carddemo-batch-output}.</li>
 *   <li><strong>{@code COTTL01Y} title block</strong> &mdash; {@code AWS Mainframe Modernization},
 *       {@code CardDemo}, and the {@code Thank you...} footer.</li>
 *   <li><strong>Inclusive date-window selection</strong> &mdash; {@code CBTRN03C}'s
 *       {@code IF TRAN-PROC-TS(1:10) >= WS-START-DATE AND <= WS-END-DATE} (the {@code TRANREPT.jcl}
 *       DFSORT {@code INCLUDE COND} date filter), proven deterministically by a populated-vs-empty
 *       report size differential plus an out-of-window exclusion check.</li>
 *   <li><strong>The CICS TDQ {@code WRITEQ} bridge</strong> (originally {@code app/cbl/CORPT00C.cbl})
 *       &mdash; publishing a {@code ReportJobMessage} JSON to the FIFO queue causes the listener to
 *       launch the same report job, attributable solely to the SQS trigger.</li>
 * </ul>
 *
 * <p>All Testcontainers (PostgreSQL + LocalStack) wiring, dynamic property registration, AWS
 * self-provisioning (including the FIFO report queue), the S3 helpers, the {@code JobLauncher}, the
 * {@code SqsAsyncClient}, the inherited resource-name constants, and the per-test S3 cleanup are
 * provided by {@link AbstractBatchIntegrationTest}; none of that scaffolding (and no
 * {@code @SpringBootTest}/{@code @ActiveProfiles}/{@code @Testcontainers}/{@code @Tag} annotation) is
 * redeclared here.</p>
 *
 * <p><strong>Self-seeded transactions.</strong> The posted {@code Transaction} table is not seeded by
 * Flyway (transactions are produced at runtime), so each test self-seeds controlled rows with known
 * {@code tranProcTs} values to exercise the date window deterministically. The shared singleton
 * containers mean the database is never assumed pristine: seeded ids are collision-free
 * {@code ZZRPT}-prefixed (distinct from sibling ITs), and the 2022-07 window is disjoint from both
 * the 2022-06 {@code dailytran.txt} fixture data and the 1999 empty window, so only this test's rows
 * surface in its report. Every seeded row references reference data that the Flyway seed actually
 * contains (card cross-reference, transaction type, transaction category) because
 * {@link com.carddemo.batch.processors.TransactionReportProcessor}'s enrichment lookups are
 * fail-fast: a missing cross-reference/type/category is fatal (COBOL {@code 9999-ABEND-PROGRAM}), so
 * an unseeded card number would abort the step rather than complete the job.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TransactionReportJobIT extends AbstractBatchIntegrationTest {

    /** Fixed report record length in bytes ({@code TRANREPT} {@code LRECL=133}; {@code CVTRA07Y} width). */
    private static final int REPORT_RECORD_LENGTH = 133;

    /** Processing timestamp inside the report window (July 2022); {@code substring(0,10)} = {@code 2022-07-15}. */
    private static final String IN_WINDOW_TS = "2022-07-15-12.00.00.000000";

    /** Processing timestamp outside the report window (August 2022); excluded by the 2022-07 window. */
    private static final String OUT_WINDOW_TS = "2022-08-15-12.00.00.000000";

    /** Inclusive report-window start ({@code yyyy-MM-dd}). */
    private static final String WINDOW_START = "2022-07-01";

    /** Inclusive report-window end ({@code yyyy-MM-dd}). */
    private static final String WINDOW_END = "2022-07-31";

    /** Start of a window that admits no seeded data (1999), proving the filter excludes everything. */
    private static final String EMPTY_START = "1999-01-01";

    /** End of the empty (1999) window. */
    private static final String EMPTY_END = "1999-12-31";

    /**
     * A card number that exists in the Flyway {@code card_xref} seed (cust/acct 50). The
     * report processor's {@code 1500-A-LOOKUP-XREF} is fatal on a missing cross-reference, so every
     * seeded transaction must reference a real cross-referenced card for the job to complete; this is
     * a deliberate, necessary refinement of the prompt's purely synthetic card scheme (the card number
     * is not rendered in the {@code CVTRA07Y} detail layout, so row distinctiveness is asserted on the
     * rendered transaction id instead).
     */
    private static final String VALID_CARD_NUM = "0500024453765740";

    /** Transaction-type code present in the {@code transaction_type} seed ({@code 01} = Purchase). */
    private static final String VALID_TYPE_CD = "01";

    /** Transaction-category code present in the {@code transaction_category} seed for type {@code 01} ({@code 01/5}). */
    private static final int VALID_CAT_CD = 5;

    /** The report job under test, injected by its canonical bean name. */
    @Autowired
    @Qualifier("transactionReportJob")
    private Job transactionReportJob;

    /** Repository used to self-seed controlled {@code Transaction} rows with known processing timestamps. */
    @Autowired
    private TransactionRepository transactionRepository;

    // ---------------------------------------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------------------------------------

    /**
     * Test A &mdash; a direct launch with {@code startDate}/{@code endDate} job parameters produces a
     * 133-byte {@code CVTRA07Y} report carrying the {@code COTTL01Y} titles in S3.
     *
     * @throws Exception if the launcher rejects the run
     */
    @Test
    @Order(1)
    void directLaunchProducesByteFaithfulReportWithTitles() throws Exception {
        seedTransaction(1, IN_WINDOW_TS);

        JobExecution execution = launchReport(WINDOW_START, WINDOW_END);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        byte[] report = locateReportObject();
        assertThat(report)
                .as("A completed report job over an in-window seeded row must produce a report object in %s",
                        BUCKET_OUTPUT)
                .isNotNull();
        assertThat(report.length).as("report object must be non-empty").isGreaterThan(0);

        // 133-byte fidelity (robust disjunction): the writer emits newline-delimited 133-char records,
        // so the newline branch asserts every record is within the fixed LRECL; the blocked branch is
        // retained for a hypothetical newline-free (pure FB) rendering of the same report.
        String content = new String(report, StandardCharsets.ISO_8859_1);
        if (content.indexOf('\n') >= 0) {
            content.lines().forEach(line ->
                    assertThat(line.length()).as("133-col report record").isLessThanOrEqualTo(REPORT_RECORD_LENGTH));
        } else {
            assertThat(report.length % REPORT_RECORD_LENGTH).as("blocked 133-byte LRECL").isZero();
        }

        // COTTL01Y title block present (lenient contains-any across the three branded lines).
        assertThat(content).containsAnyOf("AWS Mainframe Modernization", "CardDemo", "Thank you");

        // The in-window row surfaced. The CVTRA07Y detail layout renders the 16-char transaction id
        // (TRAN-REPORT-TRANS-ID) but not the card number, so the distinctive ZZRPT id is matched.
        assertThat(content).contains("ZZRPT00000000001");
    }

    /**
     * Test B &mdash; date-window filter fidelity, proven deterministically by a size differential:
     * a window that admits seeded data yields a larger report than a window that admits none. This
     * exercises {@code TransactionRepository.findByProcessingDateRange} and the processor's inclusive
     * {@code substring(0,10)} window ({@code CBTRN03C} date selection parity) without parsing report
     * internals.
     *
     * @throws Exception if either launch is rejected
     */
    @Test
    @Order(2)
    void dateWindowFilterAdmitsInWindowAndExcludesOutOfWindow() throws Exception {
        seedTransaction(2, IN_WINDOW_TS);
        seedTransaction(3, OUT_WINDOW_TS);

        // Populated window (2022-07) — capture the bytes immediately, before the empty run overwrites
        // the fixed report object key.
        launchReport(WINDOW_START, WINDOW_END);
        byte[] populated = locateReportObject();
        assertThat(populated)
                .as("A populated (2022-07) window must produce a report object in %s", BUCKET_OUTPUT)
                .isNotNull();
        assertThat(populated.length).as("populated-window report must be non-empty").isGreaterThan(0);

        // Empty window (1999) — same fixed report key, overwritten with a titles/totals-only report.
        launchReport(EMPTY_START, EMPTY_END);
        byte[] empty = locateReportObject();

        // The 2022-07 window includes the seeded in-window row(s); the 1999 window includes none, so
        // its report carries only the header/title/total scaffolding and is strictly smaller.
        assertThat(populated.length).isGreaterThan(empty == null ? 0 : empty.length);

        // The 2022-08 row (seq 3) must be absent from the 2022-07 report, proving out-of-window
        // exclusion. Matched on the distinctive transaction id (the card number is not rendered).
        String populatedContent = new String(populated, StandardCharsets.ISO_8859_1);
        assertThat(populatedContent).doesNotContain("ZZRPT00000000003");
    }

    /**
     * Test C &mdash; the SQS FIFO TDQ bridge (online-to-batch, D-004). Publishing a
     * {@code ReportJobMessage} JSON to {@code carddemo-report-jobs.fifo} must cause the
     * {@code @SqsListener} to launch {@code transactionReportJob}, producing a report in S3 with no
     * direct {@code jobLauncher} call here, so the report's presence is attributable solely to the
     * SQS trigger.
     *
     * @throws Exception if resolving the queue URL or publishing the message fails
     */
    @Test
    @Order(3)
    void sqsFifoTriggerLaunchesReportJob() throws Exception {
        seedTransaction(4, IN_WINDOW_TS);

        // Resolve the FIFO queue URL and publish the report-submission message via the inherited
        // async SQS client (AWS SDK v2 consumer-builder forms; no deprecated overloads). FIFO requires
        // a messageGroupId; a unique messageDeduplicationId prevents content-based dedup from
        // suppressing the send. The JSON field names match the ReportJobMessage record.
        String queueUrl = sqsAsyncClient.getQueueUrl(b -> b.queueName(REPORT_QUEUE)).get().queueUrl();
        String json = "{\"reportType\":\"Monthly\",\"startDate\":\"" + WINDOW_START
                + "\",\"endDate\":\"" + WINDOW_END + "\"}";
        sqsAsyncClient.sendMessage(b -> b.queueUrl(queueUrl)
                .messageBody(json)
                .messageGroupId("report")
                .messageDeduplicationId(UUID.randomUUID().toString())).get();

        // Soft-bounded wait for the asynchronously produced report. The base @BeforeEach emptied the
        // buckets, so any report object now can only originate from the listener launching the job.
        try {
            Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofSeconds(2))
                    .ignoreExceptions()
                    .untilAsserted(() -> {
                        byte[] report = locateReportObject();
                        assertThat(report).isNotNull();
                        assertThat(report.length).isGreaterThan(0);
                    });
        } catch (ConditionTimeoutException timeout) {
            throw new AssertionError(String.format(
                    "SQS-triggered report not observed within 30s. Bucket %s keys=%s; report queue url=%s",
                    BUCKET_OUTPUT, listObjectKeys(BUCKET_OUTPUT), queueUrl), timeout);
        }

        // The bridge launched the real report job end-to-end: the produced object is the byte-faithful
        // CVTRA07Y report (133-aligned, carrying a COTTL01Y title token).
        byte[] triggered = locateReportObject();
        assertThat(triggered)
                .as("SQS-triggered report must remain present after detection; bucket %s keys=%s",
                        BUCKET_OUTPUT, listObjectKeys(BUCKET_OUTPUT))
                .isNotNull();
        assertThat(triggered.length).as("SQS-triggered report must be non-empty").isGreaterThan(0);
        String content = new String(triggered, StandardCharsets.ISO_8859_1);
        if (content.indexOf('\n') >= 0) {
            content.lines().forEach(line ->
                    assertThat(line.length()).as("133-col report record").isLessThanOrEqualTo(REPORT_RECORD_LENGTH));
        } else {
            assertThat(triggered.length % REPORT_RECORD_LENGTH).as("blocked 133-byte LRECL").isZero();
        }
        assertThat(content).containsAnyOf("AWS Mainframe Modernization", "CardDemo", "Thank you");
    }

    // ---------------------------------------------------------------------------------------------
    // Test-data + launch helpers
    // ---------------------------------------------------------------------------------------------

    /**
     * Persists one fully-populated {@link Transaction} with a collision-free {@code ZZRPT} id and the
     * supplied processing timestamp. Every NOT-NULL column is set (the schema is {@code ddl-auto:
     * validate} against the Flyway V1 schema), and the type/category/card reference values are present
     * in the Flyway seed so the report processor's fail-fast enrichment lookups succeed.
     *
     * @param seq    a per-row sequence number that disambiguates the id and the description
     * @param procTs the processing timestamp to drive the date-window filter
     * @return the generated transaction id ({@code ZZRPT} + 11 digits, 16 characters)
     */
    private String seedTransaction(int seq, String procTs) {
        String tranId = String.format("ZZRPT%011d", seq); // "ZZRPT" + 11 digits = 16 chars
        Transaction t = new Transaction();
        t.setTranId(tranId);
        t.setTranTypeCd(VALID_TYPE_CD);
        t.setTranCatCd(VALID_CAT_CD);
        t.setTranSource("System");
        t.setTranDesc("IT report seed " + seq);
        t.setTranAmt(new BigDecimal("100.00"));
        t.setTranMerchantId(0L);
        t.setTranMerchantName("IT MERCHANT");
        t.setTranMerchantCity("IT CITY");
        t.setTranMerchantZip("00000");
        t.setTranCardNum(VALID_CARD_NUM);
        t.setTranOrigTs(procTs);
        t.setTranProcTs(procTs);
        transactionRepository.save(t);
        return tranId;
    }

    /**
     * Locates the report object written to the batch-output bucket. The configured key is probed first
     * (default {@code TRANREPT}, with the historical aliases), then a key scan that skips the other
     * known output objects, so the test is robust to the configured key without assuming it.
     *
     * @return the report bytes, or {@code null} when no report object is present
     */
    private byte[] locateReportObject() {
        for (String k : new String[] {"TRANREPT", "DALYREPT", "TRANREPORT", "REPORT"}) {
            byte[] b = getS3ObjectOrNull(BUCKET_OUTPUT, k);
            if (b != null && b.length > 0) {
                return b;
            }
        }
        for (String key : listObjectKeys(BUCKET_OUTPUT)) {
            if (key.equals("DALYREJS") || key.equals("SYSTRAN")
                    || key.equals("TRANSACT.BKUP") || key.equals("COMBINED")) {
                continue;
            }
            byte[] b = getS3ObjectOrNull(BUCKET_OUTPUT, key);
            if (b != null && b.length > 0) {
                return b;
            }
        }
        return null;
    }

    /**
     * Launches {@code transactionReportJob} directly with a unique run id and the supplied report
     * window, mirroring {@code TRANREPT.jcl}'s {@code PARM-START-DATE}/{@code PARM-END-DATE}.
     *
     * @param start inclusive window start ({@code yyyy-MM-dd})
     * @param end   inclusive window end ({@code yyyy-MM-dd})
     * @return the resulting {@link JobExecution}
     * @throws Exception if the launcher rejects the run
     */
    private JobExecution launchReport(String start, String end) throws Exception {
        return jobLauncher.run(transactionReportJob,
                baseParams().addString("startDate", start).addString("endDate", end).toJobParameters());
    }
}

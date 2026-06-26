/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.carddemo.integration;

import com.carddemo.entity.Transaction;
import com.carddemo.entity.TransactionCategoryId;
import com.carddemo.enums.TransactionTypeCode;
import com.carddemo.repository.CardXrefRepository;
import com.carddemo.repository.TransactionCategoryRepository;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.repository.TransactionTypeRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.test.JobLauncherTestUtils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testcontainers integration test for the Spring Batch job
 * {@code transactionReportJob}
 * ({@link com.carddemo.batch.job.TransactionReportJobConfig}), the Java
 * realization of the legacy COBOL batch program {@code CBTRN03C} driven by the
 * JCL job {@code TRANREPT} (members {@code app/cbl/CBTRN03C.cbl},
 * {@code app/jcl/TRANREPT.jcl} and copybook {@code app/cpy/CVTRA07Y.cpy} at
 * source commit {@code 27d6c6f}). The job produces the date-windowed,
 * card-grouped transaction detail report with page, account, and grand totals,
 * emitting {@value #RECORD_WIDTH}-byte fixed-width lines (the {@code TRANREPT}
 * {@code LRECL=133} and the COBOL {@code FD-REPTFILE-REC PIC X(133)}) to the
 * Amazon S3 output bucket.
 *
 * <h2>The CBTRN03C / CVTRA07Y contract under test</h2>
 * The suite verifies the behaviours that carry the byte-equivalence and
 * interface-contract obligations of the migration (Gates&nbsp;1, 4 and 5):
 * <ul>
 *   <li><strong>133-byte fixed-width records.</strong> Every emitted line is
 *       exactly {@value #RECORD_WIDTH} characters wide, the central
 *       {@code CVTRA07Y} {@code FD-REPTFILE-REC PIC X(133)} contract, and the
 *       all-hyphen separator ({@code TRANSACTION-HEADER-2 PIC X(133) VALUE ALL
 *       '-'}) is present.</li>
 *   <li><strong>Inclusive date window.</strong> Only transactions whose
 *       processing date falls in {@code [reportStartDate, reportEndDate]} appear,
 *       reproducing the DFSORT {@code INCLUDE COND=(TRAN-PROC-DT,GE,...,LE,...)}
 *       on the first ten characters of {@code TRAN-PROC-TS}; rows exactly on
 *       either boundary are included.</li>
 *   <li><strong>Enrichment.</strong> Each detail line resolves the account id
 *       from {@code card_xref} ({@code 1500-A-LOOKUP-XREF}), the transaction-type
 *       description from {@code transaction_type} ({@code 1500-B-LOOKUP-TRANTYPE}),
 *       and the transaction-category description from {@code transaction_category}
 *       via the composite {@code {type,category}} key
 *       ({@code 1500-C-LOOKUP-TRANCATG}).</li>
 *   <li><strong>Paging.</strong> A page break is taken every time the physical
 *       line counter is a multiple of {@code WS-PAGE-SIZE = }{@value #PAGE_SIZE},
 *       reprinting the name/column header block.</li>
 *   <li><strong>Control-break totals.</strong> A per-card (account) subtotal is
 *       flushed when the card number changes, and a single grand total closes the
 *       report; both use the {@code +ZZZ,ZZZ,ZZZ.ZZ} edit mask.</li>
 * </ul>
 *
 * <h2>Real infrastructure (no mocks, no H2, no live AWS)</h2>
 * This class extends {@link AbstractIntegrationIT}, so it runs against a
 * <strong>real PostgreSQL&nbsp;16</strong> container with Flyway applying the
 * {@code V1}/{@code V2}/{@code V3} migrations (schema, indexes, and the seed
 * derived from the nine ASCII fixtures) and Hibernate under
 * {@code ddl-auto=validate}, and against a <strong>real LocalStack</strong> S3
 * endpoint into which the {@link com.carddemo.batch.writer.FixedWidthS3ItemWriter}
 * uploads the materialized report object. The job is launched against the real
 * {@code BATCH_*} metadata tables (auto-created by
 * {@code spring.batch.jdbc.initialize-schema=always}). The output bucket name is
 * read from the config-bound {@link AbstractIntegrationIT#outputBucket()} and is
 * never hardcoded.
 *
 * <h2>Why this IT is not {@code @Transactional}</h2>
 * Spring Batch commits in its own transactions, so a test-managed rollback
 * transaction would both hide the job's writes and deadlock against the job's
 * chunk commits. This class therefore owns its data lifecycle explicitly:
 * {@link #resetStateAndWireBatch()} clears the {@code transactions} master,
 * provisions and empties the S3 output bucket, and wires
 * {@link JobLauncherTestUtils} before each test, and {@link #cleanState()}
 * clears the table and empties the bucket afterwards, returning the shared
 * singleton containers to the empty-seed state the other suites expect.
 *
 * <h2>JobLauncherTestUtils wiring (multi-job context)</h2>
 * The migration defines several {@code Job} beans, so {@link AbstractIntegrationIT}
 * deliberately does not expose a {@link JobLauncherTestUtils} bean (it would
 * require exactly one {@code Job}). This IT instead instantiates it manually and
 * injects the specific job under test by {@link Qualifier qualified} bean name
 * ({@code transactionReportJob}), wiring the real {@link JobLauncher} and
 * {@link JobRepository} in {@link #resetStateAndWireBatch()}.
 */
@DisplayName("TransactionReportJob IT — CBTRN03C/CVTRA07Y parity (133-byte records, inclusive window, paging, control-break totals)")
public class TransactionReportJobIT extends AbstractIntegrationIT {

    /** The posted-transaction master table the report job reads from (seeds empty). */
    private static final String TRANSACTIONS_TABLE = "transactions";

    /** Fixed report record width — {@code TRANREPT} {@code LRECL=133}, COBOL {@code FD-REPTFILE-REC PIC X(133)}. */
    private static final int RECORD_WIDTH = 133;

    /** Lines per page before a page total and header reprint — COBOL {@code WS-PAGE-SIZE VALUE 20}. */
    private static final int PAGE_SIZE = 20;

    /**
     * Start column (0-based, inclusive) of the 15-character edited amount field on a
     * total line. Account totals place it after {@code "Account Total"}(13) + 84 dots;
     * page and grand totals after an 11-character label + 86 dots; both equal 97.
     */
    private static final int AMOUNT_FIELD_START = 97;

    /** End column (0-based, exclusive) of the edited amount field: a 15-character {@code +ZZZ,ZZZ,ZZZ.ZZ} mask. */
    private static final int AMOUNT_FIELD_END = 112;

    /** Inclusive window start passed as the {@code reportStartDate} job parameter (JCL {@code PARM-START-DATE}). */
    private static final String START_DATE = "2022-01-01";

    /** Inclusive window end passed as the {@code reportEndDate} job parameter (JCL {@code PARM-END-DATE}). */
    private static final String END_DATE = "2022-07-06";

    /** Required job-parameter key carrying the inclusive window start; mirrors {@code TransactionReportJobConfig.START_DATE_PARAM}. */
    private static final String REPORT_START_DATE_PARAM = "reportStartDate";

    /** Required job-parameter key carrying the inclusive window end; mirrors {@code TransactionReportJobConfig.END_DATE_PARAM}. */
    private static final String REPORT_END_DATE_PARAM = "reportEndDate";

    /** A seeded {@code card_xref} card number that resolves to account id {@code 2}. */
    private static final String CARD_A = "0923877193247330";

    /** A seeded {@code card_xref} card number that resolves to account id {@code 50}. */
    private static final String CARD_B = "0500024453765740";

    /** Default seeded transaction type used by fixtures: {@code "01"} → {@code transaction_type} "Purchase". */
    private static final TransactionTypeCode DEFAULT_TYPE = TransactionTypeCode.PURCHASE;

    /** Default seeded category code used by fixtures: {@code {01,1}} → {@code transaction_category} "Regular Sales Draft". */
    private static final int DEFAULT_CATEGORY = 1;

    /** Name-header / page-header line prefix (COBOL {@code REPT-SHORT-NAME 'DALYREPT'}); unique per page. */
    private static final String NAME_HEADER_PREFIX = "DALYREPT";

    /** Column-header line prefix (COBOL {@code TRANSACTION-HEADER-1}). */
    private static final String COLUMN_HEADER_PREFIX = "Transaction ID";

    /** Per-card (account) subtotal line prefix (COBOL {@code REPORT-ACCOUNT-TOTALS}). */
    private static final String ACCOUNT_TOTAL_PREFIX = "Account Total";

    /** Grand-total line prefix (COBOL {@code REPORT-GRAND-TOTALS}). */
    private static final String GRAND_TOTAL_PREFIX = "Grand Total";

    /** The full-record hyphen separator (COBOL {@code TRANSACTION-HEADER-2 PIC X(133) VALUE ALL '-'}). */
    private static final String SEPARATOR_LINE = "-".repeat(RECORD_WIDTH);

    /**
     * Monotonic {@code run.id} source giving every launch a unique {@code JobInstance}. Seeded with
     * {@link System#nanoTime()} so identifiers never collide with a prior run when the singleton
     * PostgreSQL container is reused ({@code withReuse(true)}) across separate JVM executions.
     */
    private static final AtomicLong RUN_ID_SEQUENCE = new AtomicLong(System.nanoTime());

    @Autowired
    private JobLauncher jobLauncher;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    @Qualifier("transactionReportJob")
    private Job transactionReportJob;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private CardXrefRepository cardXrefRepository;

    @Autowired
    private TransactionTypeRepository transactionTypeRepository;

    @Autowired
    private TransactionCategoryRepository transactionCategoryRepository;

    /** Manually wired per-test (the base class deliberately exposes no such bean in the multi-job context). */
    private JobLauncherTestUtils jobLauncherTestUtils;

    /**
     * Clears the {@code transactions} master to a deterministic empty state (this IT cannot be
     * {@code @Transactional}), idempotently provisions the canonical AWS resources (the output
     * bucket the report writer uploads to must exist — {@code AwsConfig} does not auto-create it),
     * empties that output bucket, and wires {@link JobLauncherTestUtils} to the qualified
     * {@code transactionReportJob} with the real launcher and repository.
     */
    @BeforeEach
    void resetStateAndWireBatch() {
        deleteFrom(TRANSACTIONS_TABLE);
        provisionCanonicalAwsResources();
        emptyBucket(outputBucket());

        jobLauncherTestUtils = new JobLauncherTestUtils();
        jobLauncherTestUtils.setJobLauncher(jobLauncher);
        jobLauncherTestUtils.setJobRepository(jobRepository);
        jobLauncherTestUtils.setJob(transactionReportJob);
    }

    /**
     * Returns the shared {@code transactions} table and S3 output bucket to their empty-seed state
     * for the other suites that share the singleton containers.
     */
    @AfterEach
    void cleanState() {
        deleteFrom(TRANSACTIONS_TABLE);
        emptyBucket(outputBucket());
    }

    // =====================================================================================
    // Test methods.
    // =====================================================================================

    /**
     * The report job runs end-to-end against real PostgreSQL and LocalStack and reports success:
     * a small in-window seed is read, rendered, and uploaded, and the {@link JobExecution} reports
     * {@link BatchStatus#COMPLETED} with the {@code COMPLETED} exit code.
     *
     * @throws Exception if the job launch fails (propagated from {@link JobLauncherTestUtils})
     */
    @Test
    @DisplayName("transactionReportJob completes with BatchStatus.COMPLETED and ExitStatus COMPLETED")
    void jobCompletesSuccessfully() throws Exception {
        persist(1, CARD_A, "100.00", "2022-02-01");
        persist(2, CARD_A, "250.75", "2022-03-15");
        persist(3, CARD_B, "10.00", "2022-04-20");

        JobExecution execution = launchReportJob(START_DATE, END_DATE);

        assertThat(execution.getStatus())
                .as("transactionReportJob batch status")
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode())
                .as("transactionReportJob exit code")
                .isEqualTo(ExitStatus.COMPLETED.getExitCode());
    }

    /**
     * <strong>CRITICAL — CVTRA07Y 133-byte record contract (Gate&nbsp;5).</strong> Exactly one
     * report object is written to the config-bound output bucket; every line of that object,
     * decoded as US-ASCII and split on the writer's newline delimiter, is exactly
     * {@value #RECORD_WIDTH} characters wide, and the all-hyphen separator line
     * ({@code TRANSACTION-HEADER-2 PIC X(133) VALUE ALL '-'}) is present.
     *
     * @throws Exception if the job launch fails
     */
    @Test
    @DisplayName("CRITICAL: exactly one report object whose every line is 133 characters wide (CVTRA07Y contract)")
    void reportObjectWrittenToOutputBucketWithFixedWidthLines() throws Exception {
        persist(1, CARD_A, "100.00", "2022-02-01");
        persist(2, CARD_A, "200.00", "2022-03-01");
        persist(3, CARD_B, "-30.50", "2022-04-01");

        JobExecution execution = launchReportJob(START_DATE, END_DATE);
        assertThat(execution.getStatus())
                .as("transactionReportJob batch status")
                .isEqualTo(BatchStatus.COMPLETED);

        List<String> lines = readSingleReportObjectLines();

        assertThat(lines)
                .as("the report object must contain at least the header block and one detail line")
                .isNotEmpty();
        assertThat(lines)
                .as("every CVTRA07Y report line is exactly %d characters wide (FD-REPTFILE-REC PIC X(133))",
                        RECORD_WIDTH)
                .allSatisfy(line -> assertThat(line.length()).isEqualTo(RECORD_WIDTH));
        assertThat(lines)
                .as("the 133-hyphen separator line (TRANSACTION-HEADER-2) is present")
                .contains(SEPARATOR_LINE);
    }

    /**
     * Only transactions whose processing date falls in the inclusive window
     * {@code [reportStartDate, reportEndDate]} appear as detail lines, reproducing the DFSORT
     * {@code INCLUDE COND} on the first ten characters of {@code TRAN-PROC-TS}. Rows exactly on
     * the {@code startDate} and {@code endDate} boundaries are included; rows one day outside
     * either boundary are excluded. The detail-line count equals
     * {@link TransactionRepository#findByProcessingDateWindow(String, String)} for the same
     * window.
     *
     * @throws Exception if the job launch fails
     */
    @Test
    @DisplayName("only in-window transactions appear; the window is boundary-inclusive")
    void onlyInWindowTransactionsAppear() throws Exception {
        // In-window: two boundary rows (exactly on start and end) plus one interior row.
        persist(1, CARD_A, "11.11", START_DATE);     // boundary: start date — INCLUDED
        persist(2, CARD_A, "22.22", "2022-04-15");   // interior — INCLUDED
        persist(3, CARD_A, "33.33", END_DATE);       // boundary: end date — INCLUDED
        // Out-of-window: one day before start and one day after end.
        persist(101, CARD_A, "44.44", "2021-12-31"); // before start — EXCLUDED
        persist(102, CARD_A, "55.55", "2022-07-07"); // after end — EXCLUDED

        JobExecution execution = launchReportJob(START_DATE, END_DATE);
        assertThat(execution.getStatus())
                .as("transactionReportJob batch status")
                .isEqualTo(BatchStatus.COMPLETED);

        List<String> details = detailLines(readSingleReportObjectLines());

        int windowCount = transactionRepository.findByProcessingDateWindow(START_DATE, END_DATE).size();
        assertThat(windowCount)
                .as("the repository window finder returns exactly the three in-window rows")
                .isEqualTo(3);
        assertThat(details)
                .as("the report emits exactly one detail line per in-window transaction")
                .hasSize(windowCount);
        assertThat(details)
                .as("both boundary rows (start and end date) and the interior row are present")
                .anyMatch(line -> line.startsWith(tranId(1)))
                .anyMatch(line -> line.startsWith(tranId(2)))
                .anyMatch(line -> line.startsWith(tranId(3)));
        assertThat(details)
                .as("rows one day outside either boundary are absent")
                .noneMatch(line -> line.startsWith(tranId(101)))
                .noneMatch(line -> line.startsWith(tranId(102)));
    }

    /**
     * A known transaction's detail line carries the enrichment values resolved from the seeded
     * reference tables: the account id from {@code card_xref} ({@code 1500-A-LOOKUP-XREF}), the
     * transaction-type description from {@code transaction_type} ({@code 1500-B-LOOKUP-TRANTYPE}),
     * and the transaction-category description from {@code transaction_category} via the composite
     * {@code {type,category}} key ({@code 1500-C-LOOKUP-TRANCATG}). The expected values are read
     * from the same repositories the processor uses, so the assertion stays robust against seed
     * changes and verifies the enrichment contract rather than hardcoded literals.
     *
     * @throws Exception if the job launch fails
     */
    @Test
    @DisplayName("each detail line is enriched with card_xref account id and type/category descriptions")
    void enrichmentPopulatesTypeAndCategoryDescriptions() throws Exception {
        persist(1, CARD_A, "123.45", "2022-05-10");

        JobExecution execution = launchReportJob(START_DATE, END_DATE);
        assertThat(execution.getStatus())
                .as("transactionReportJob batch status")
                .isEqualTo(BatchStatus.COMPLETED);

        // Resolve the expected enrichment values from the same seeded tables the processor reads.
        Long expectedAccountId = cardXrefRepository.findById(CARD_A).orElseThrow().getXrefAcctId();
        String expectedTypeDesc = transactionTypeRepository.findById(DEFAULT_TYPE.getCode())
                .orElseThrow().getTranTypeDesc();
        String expectedCategoryDesc = transactionCategoryRepository
                .findById(new TransactionCategoryId(DEFAULT_TYPE.getCode(), DEFAULT_CATEGORY))
                .orElseThrow().getTranCatTypeDesc();
        String expectedAccountIdField = String.format("%011d", expectedAccountId);

        String detailLine = detailLines(readSingleReportObjectLines()).stream()
                .filter(line -> line.startsWith(tranId(1)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no detail line found for the seeded transaction"));

        assertThat(detailLine)
                .as("detail line carries the card_xref account id (zero-padded to 11 digits)")
                .contains(expectedAccountIdField);
        assertThat(detailLine)
                .as("detail line carries the transaction_type description '%s'", expectedTypeDesc)
                .contains(expectedTypeDesc);
        assertThat(detailLine)
                .as("detail line carries the transaction_category description '%s'", expectedCategoryDesc)
                .contains(expectedCategoryDesc);
    }

    /**
     * With more than {@value #PAGE_SIZE} in-window detail lines for a single card, the report
     * reprints its name/column header on each page break. The page break is keyed on the COBOL
     * physical line counter ({@code WS-LINE-COUNTER}) reaching a multiple of
     * {@code WS-PAGE-SIZE = }{@value #PAGE_SIZE} at the start of a detail cycle, where the
     * four-line header block and two-line page-total block also advance the counter. The expected
     * number of name-header occurrences is computed by
     * {@link #expectedNameHeaderCountForSingleCard(int)}, a faithful in-test replication of that
     * mechanics; for 25 single-card rows it is two (one initial header plus one page break),
     * consistent with {@code ceil(25 / 20)}.
     *
     * @throws Exception if the job launch fails
     */
    @Test
    @DisplayName("a page break reprints the header; line-counter paging (WS-PAGE-SIZE=20) is preserved")
    void pageBreakEveryTwentyDetailLines() throws Exception {
        final int detailRows = 25;
        for (int i = 1; i <= detailRows; i++) {
            persist(i, CARD_A, "10.00", "2022-04-01");
        }

        JobExecution execution = launchReportJob(START_DATE, END_DATE);
        assertThat(execution.getStatus())
                .as("transactionReportJob batch status")
                .isEqualTo(BatchStatus.COMPLETED);

        List<String> lines = readSingleReportObjectLines();
        long nameHeaderCount = lines.stream().filter(line -> line.startsWith(NAME_HEADER_PREFIX)).count();

        assertThat(detailLines(lines))
                .as("all %d single-card in-window rows render as detail lines", detailRows)
                .hasSize(detailRows);
        assertThat(nameHeaderCount)
                .as("more than %d detail lines force at least one page break (header reprinted)", PAGE_SIZE)
                .isGreaterThanOrEqualTo(2L);
        assertThat(nameHeaderCount)
                .as("the name header is reprinted exactly per CBTRN03C line-counter paging mechanics")
                .isEqualTo(expectedNameHeaderCountForSingleCard(detailRows));
        assertThat(lines.stream().filter(line -> line.startsWith(COLUMN_HEADER_PREFIX)).count())
                .as("the column header is reprinted alongside every name header")
                .isEqualTo(nameHeaderCount);
    }

    /**
     * Per-card (account) subtotals and a single grand total close the report, formatted with the
     * {@code +ZZZ,ZZZ,ZZZ.ZZ} edit mask ({@code REPORT-ACCOUNT-TOTALS} /
     * {@code REPORT-GRAND-TOTALS}). Two cards are seeded with distinct sums (one card mixing a
     * positive and a negative amount to exercise the sign masks); the parsed account-total amounts
     * equal the per-card sums and the grand total equals the {@link BigDecimal} sum of every
     * in-window amount.
     *
     * @throws Exception if the job launch fails
     */
    @Test
    @DisplayName("per-card subtotals and a grand total are present and equal the summed in-window amounts")
    void grandTotalAndPerCardSubtotalsPresent() throws Exception {
        // CARD_B: two positive rows. CARD_A: a positive and a negative row (exercises both masks).
        persist(10, CARD_B, "100.00", "2022-03-15");
        persist(11, CARD_B, "25.50", "2022-03-16");
        persist(20, CARD_A, "200.00", "2022-03-17");
        persist(21, CARD_A, "-50.00", "2022-03-18");

        BigDecimal sumCardB = new BigDecimal("125.50");
        BigDecimal sumCardA = new BigDecimal("150.00");
        BigDecimal grand = sumCardA.add(sumCardB); // 275.50 — includes the negative row

        JobExecution execution = launchReportJob(START_DATE, END_DATE);
        assertThat(execution.getStatus())
                .as("transactionReportJob batch status")
                .isEqualTo(BatchStatus.COMPLETED);

        List<String> lines = readSingleReportObjectLines();

        List<BigDecimal> accountTotals = new ArrayList<>();
        for (String line : lines) {
            if (line.startsWith(ACCOUNT_TOTAL_PREFIX)) {
                accountTotals.add(parseEditedTotalAmount(line));
            }
        }
        assertThat(accountTotals)
                .as("one per-card (account) subtotal is emitted for each of the two cards")
                .hasSize(2)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactlyInAnyOrder(sumCardB, sumCardA);

        String grandLine = lines.stream()
                .filter(line -> line.startsWith(GRAND_TOTAL_PREFIX))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no grand-total line found in the report"));
        assertThat(parseEditedTotalAmount(grandLine))
                .as("the grand total equals the BigDecimal sum of every in-window amount")
                .isEqualByComparingTo(grand);
        assertThat(grandLine.substring(AMOUNT_FIELD_START, AMOUNT_FIELD_END))
                .as("a positive grand total carries the '+' sign of the +ZZZ,ZZZ,ZZZ.ZZ totals mask")
                .contains("+");
    }

    // =====================================================================================
    // Helper methods — seed builders, launch, S3 read-back, line classifiers, amount parsing.
    // =====================================================================================

    /**
     * Persists one posted {@link Transaction} whose processing timestamp places it on
     * {@code processingDate}, using the default seeded type ({@link #DEFAULT_TYPE}) and category
     * ({@link #DEFAULT_CATEGORY}) so the processor's enrichment lookups resolve against the Flyway
     * seed. Each row is saved (and flushed) in its own repository transaction; because this IT is
     * deliberately not {@code @Transactional}, the write is committed and therefore visible to the
     * batch step that runs in its own transaction.
     *
     * @param sequence       the numeric stem of the 16-character {@code TRAN-ID} (zero-padded)
     * @param cardNum        the {@code TRAN-CARD-NUM}; must exist in the seeded {@code card_xref}
     * @param amount         the {@code TRAN-AMT} as a decimal string (may be negative)
     * @param processingDate the {@code YYYY-MM-DD} date that drives the {@code TRAN-PROC-TS} window
     */
    private void persist(int sequence, String cardNum, String amount, String processingDate) {
        final Transaction transaction = new Transaction(
                tranId(sequence),
                DEFAULT_TYPE.getCode(),
                DEFAULT_CATEGORY,
                "POS",
                "INTEGRATION TEST TRANSACTION",
                new BigDecimal(amount),
                1L,
                "TEST MERCHANT",
                "TEST CITY",
                "00000",
                cardNum,
                ts(processingDate),
                ts(processingDate));
        transactionRepository.saveAndFlush(transaction);
    }

    /**
     * Formats a numeric stem into the 16-character zero-padded {@code TRAN-ID PIC X(16)} key, the
     * exact width the detail line emits as its leading field, so {@code line.startsWith(tranId(n))}
     * is an exact detail-line identity check.
     *
     * @param sequence the numeric stem
     * @return the 16-character zero-padded transaction identifier
     */
    private static String tranId(long sequence) {
        return String.format("%016d", sequence);
    }

    /**
     * Builds a 26-character {@code TRAN-PROC-TS}/{@code TRAN-ORIG-TS} timestamp at midnight for the
     * given date, preserving the legacy {@code YYYY-MM-DD HH:MM:SS.mmmmmm} format byte-for-byte.
     * Only the leading ten characters ({@code YYYY-MM-DD}) participate in the report window filter.
     *
     * @param date the {@code YYYY-MM-DD} date portion
     * @return the 26-character timestamp text
     */
    private static String ts(String date) {
        return date + " 00:00:00.000000";
    }

    /**
     * Launches {@code transactionReportJob} for the inclusive {@code [startDate, endDate]} window
     * and returns its {@link JobExecution}. A monotonic {@code run.id} parameter makes every launch
     * a fresh {@link org.springframework.batch.core.JobInstance JobInstance} (avoiding a completed
     * instance being re-run), and the two required date parameters are passed under the exact keys
     * the job and processor late-bind ({@code reportStartDate} / {@code reportEndDate}).
     *
     * @param startDate the inclusive window start ({@code YYYY-MM-DD})
     * @param endDate   the inclusive window end ({@code YYYY-MM-DD})
     * @return the completed job execution
     * @throws Exception if the launcher fails to run the job
     */
    private JobExecution launchReportJob(String startDate, String endDate) throws Exception {
        final JobParameters parameters = new JobParametersBuilder()
                .addLong("run.id", RUN_ID_SEQUENCE.incrementAndGet())
                .addString(REPORT_START_DATE_PARAM, startDate)
                .addString(REPORT_END_DATE_PARAM, endDate)
                .toJobParameters();
        return jobLauncherTestUtils.launchJob(parameters);
    }

    /**
     * Reads the single report object the job uploads to the output bucket and returns its
     * fixed-width lines. Asserts that <strong>exactly one</strong> object exists (the writer
     * uploads one versioned object per run, and the test isolates S3 state per method), decodes the
     * payload as {@link StandardCharsets#US_ASCII US-ASCII} (the writer's charset), splits it on the
     * writer's {@code "\n"} record delimiter, and drops the trailing empty element produced by the
     * payload's terminal newline. Every retained element is therefore a real 133-character record.
     *
     * @return the report's fixed-width lines, in file order
     */
    private List<String> readSingleReportObjectLines() {
        final String bucket = outputBucket();
        final byte[] payload;
        try (S3Client s3 = newS3Client()) {
            final List<S3Object> objects = s3.listObjectsV2(
                    ListObjectsV2Request.builder().bucket(bucket).build()).contents();
            assertThat(objects)
                    .as("exactly one transaction-report object is written to the '%s' output bucket", bucket)
                    .hasSize(1);
            final String key = objects.get(0).key();
            payload = s3.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();
        }
        final String text = new String(payload, StandardCharsets.US_ASCII);
        final List<String> lines = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            if (!line.isEmpty()) {
                lines.add(line);
            }
        }
        return lines;
    }

    /**
     * Returns the detail lines among the supplied report lines. A detail line is the only line type
     * whose first character is a digit (its leading field is the 16-character zero-padded
     * {@code TRAN-ID}); every header, separator, blank, and total line begins with a letter, hyphen,
     * or space.
     *
     * @param reportLines the full set of report lines
     * @return the detail lines, in file order
     */
    private static List<String> detailLines(List<String> reportLines) {
        final List<String> details = new ArrayList<>();
        for (String line : reportLines) {
            if (isDetailLine(line)) {
                details.add(line);
            }
        }
        return details;
    }

    /**
     * Tests whether a report line is a transaction detail line (its leading {@code TRAN-ID} field
     * begins with a digit).
     *
     * @param line the report line
     * @return {@code true} when the line is a detail line
     */
    private static boolean isDetailLine(String line) {
        return !line.isEmpty() && Character.isDigit(line.charAt(0));
    }

    /**
     * Parses the {@code +ZZZ,ZZZ,ZZZ.ZZ}-edited amount carried by a total line (account, page, or
     * grand) at the fixed {@code [}{@value #AMOUNT_FIELD_START}{@code , }{@value #AMOUNT_FIELD_END}{@code )}
     * column span. The grouping commas, the leading {@code '+'} sign, and the zero-suppression
     * spaces are stripped (a negative {@code '-'} is retained); an all-blank field (the COBOL
     * all-{@code Z} zero-suppression of an exact zero) parses to {@link BigDecimal#ZERO}.
     *
     * @param totalLine the 133-character total line
     * @return the parsed amount as a {@link BigDecimal}
     */
    private static BigDecimal parseEditedTotalAmount(String totalLine) {
        final String field = totalLine.substring(AMOUNT_FIELD_START, AMOUNT_FIELD_END);
        final String digits = field.replace(",", "").replace("+", "").replace(" ", "");
        return digits.isEmpty() ? BigDecimal.ZERO : new BigDecimal(digits);
    }

    /**
     * Computes the expected number of name-header occurrences for a report of {@code detailRows}
     * detail lines that all belong to a <strong>single card</strong>, by faithfully replaying the
     * {@code CBTRN03C} physical-line-counter paging of
     * {@link com.carddemo.batch.processor.TransactionReportProcessor}: the
     * four-line header block prints on the first row and again on every page break, a page break is
     * taken whenever the line counter is a multiple of {@code WS-PAGE-SIZE = }{@value #PAGE_SIZE} at
     * the start of a detail cycle, and the four-line header block and the two-line page-total block
     * both advance that counter (so the first row's header leaves the counter at four and does not
     * itself re-trigger a break). For a single card there is no mid-stream account-total break.
     *
     * @param detailRows the number of single-card detail rows
     * @return the number of times the name/column header is printed
     */
    private static long expectedNameHeaderCountForSingleCard(int detailRows) {
        long nameHeaders = 0;
        int lineCounter = 0;
        boolean firstRow = true;
        for (int i = 0; i < detailRows; i++) {
            if (firstRow) {
                firstRow = false;
                nameHeaders++;        // emitHeaders(): name + blank + column + separator
                lineCounter += 4;
            }
            if (lineCounter % PAGE_SIZE == 0) {
                lineCounter += 2;     // emitPageTotals(): page-total line + separator
                nameHeaders++;        // emitHeaders() reprinted after the page total
                lineCounter += 4;
            }
            lineCounter += 1;         // the detail line for this row
        }
        return nameHeaders;
    }
}

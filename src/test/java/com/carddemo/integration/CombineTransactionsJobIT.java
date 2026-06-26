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

import com.carddemo.batch.processor.TransactionCombineComparator;
import com.carddemo.entity.Transaction;
import com.carddemo.enums.TransactionTypeCode;
import com.carddemo.repository.TransactionRepository;

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
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testcontainers integration test for the Spring Batch job
 * {@code combineTransactionsJob} ({@link com.carddemo.batch.job.CombineTransactionsJobConfig}),
 * the Java realization of the legacy JCL job {@code COMBTRAN} (member
 * {@code app/jcl/COMBTRAN.jcl} at source commit {@code 27d6c6f}). The legacy job has no
 * COBOL program: it is a pure DFSORT + IDCAMS {@code REPRO} pipeline whose
 * {@code STEP05R EXEC PGM=SORT} concatenates {@code TRANSACT.BKUP(0)} and
 * {@code SYSTRAN(0)} and applies the single control card {@code SORT FIELDS=(TRAN-ID,A)}
 * ({@code SYMNAMES TRAN-ID,1,16,CH}), then {@code STEP10 EXEC PGM=IDCAMS} {@code REPRO}s
 * the sorted, combined dataset into {@code TRANSACT.VSAM.KSDS}.
 *
 * <h2>The COMBTRAN parity invariant under test</h2>
 * The control card declares <strong>no</strong> {@code SUM FIELDS=NONE} (and no
 * {@code NODUPS}/{@code XSUM}), so the combine is <em>order-preserving</em> and
 * <em>non-collapsing</em>: the output is ascending by {@code TRAN-ID} and
 * <strong>no record is lost or de-duplicated</strong>, with equal keys retaining their
 * relative input order. Reproducing that parity exactly is the central goal of this suite.
 *
 * <h2>Real infrastructure (no mocks, no H2, no live AWS)</h2>
 * This class extends {@link AbstractIntegrationIT}, so it runs against a <strong>real
 * PostgreSQL&nbsp;16</strong> container with Flyway applying the {@code V1}/{@code V2}/{@code V3}
 * migrations and Hibernate under {@code ddl-auto=validate}; the shared LocalStack container is
 * also up but unused by this job. The job is launched against the real {@code BATCH_*}
 * metadata tables (auto-created by {@code spring.batch.jdbc.initialize-schema=always}).
 *
 * <h2>Why this IT is not {@code @Transactional}</h2>
 * Spring Batch commits in its own transactions, so a test-managed rollback transaction would
 * both hide the job's writes and deadlock against the job's chunk commits. This class therefore
 * owns its data lifecycle explicitly: {@link #initBatchAndCleanState()} clears the
 * {@code transactions} master before each test and {@link #cleanState()} clears it afterwards,
 * returning the shared singleton container to the empty-seed state the other suites expect.
 *
 * <h2>JobLauncherTestUtils wiring (multi-job context)</h2>
 * The migration defines several {@code Job} beans, so {@link AbstractIntegrationIT}
 * deliberately does not expose a {@link JobLauncherTestUtils} bean (it would require exactly
 * one {@code Job}). This IT instead instantiates it manually and injects the specific job under
 * test by {@link Qualifier qualified} bean name, wiring the real {@link JobLauncher} and
 * {@link JobRepository} in {@link #initBatchAndCleanState()}.
 *
 * <h2>How the job's implementation shapes these assertions</h2>
 * {@code combineTransactionsReader()} reads the whole {@code transactions} master via
 * {@link TransactionRepository#findAll()}, sorts it in memory with the injected
 * {@link TransactionCombineComparator} (ascending {@code TRAN-ID}, stable, duplicate-retaining),
 * and the {@code combineTransactionsWriter()} loads the result back into the same table via
 * {@code saveAll}. Because {@code tran_id} is the entity's natural primary key, the source/sink
 * table <strong>cannot physically hold two rows with the same {@code TRAN-ID}</strong>. The
 * suite is split accordingly:
 * <ul>
 *   <li>{@link #jobCompletesSuccessfully()}, {@link #outputIsSortedAscendingByTranId()} and
 *       {@link #recordCountPreservedAcrossCombine()} <strong>launch the real job</strong> and
 *       assert completion, a complete key-ordered master, and exact record-count preservation.</li>
 *   <li>{@link #duplicateTranIdsAreRetainedNotCollapsed()} and {@link #stableOrderForEqualKeys()}
 *       reproduce the job's <em>reader sort stage</em> ({@code combined.sort(comparator)}) over an
 *       in-memory input that carries a duplicate {@code TRAN-ID} — the COMBTRAN concatenation case
 *       (a key shared by {@code BKUP(0)} and {@code SYSTRAN(0)}) that the PK-keyed master cannot
 *       itself host — using the <strong>same Spring-managed comparator bean the job injects</strong>.
 *       These complement (rather than duplicate) the pure unit test
 *       {@code com.carddemo.unit.batch.processor.TransactionCombineComparatorTest}, which exercises a
 *       hand-constructed comparator instead of the wired bean.</li>
 * </ul>
 */
@DisplayName("CombineTransactionsJob IT — COMBTRAN parity (ascending TRAN-ID, duplicates retained, no record loss)")
class CombineTransactionsJobIT extends AbstractIntegrationIT {

    /** The transaction master table the combine job reads from and writes back to. */
    private static final String TRANSACTIONS_TABLE = "transactions";

    /** Default 16-character {@code TRAN-CARD-NUM} (exact width avoids {@code CHAR(16)} bpchar padding mismatch). */
    private static final String CARD_DEFAULT = "4000000000000000";

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
    @Qualifier("combineTransactionsJob")
    private Job combineTransactionsJob;

    @Autowired
    private TransactionRepository transactionRepository;

    /** The exact comparator bean the combine job injects into its reader sort stage. */
    @Autowired
    private TransactionCombineComparator transactionCombineComparator;

    /** Manually wired per-test (the base class deliberately exposes no such bean in the multi-job context). */
    private JobLauncherTestUtils jobLauncherTestUtils;

    /**
     * Clears the {@code transactions} master to a deterministic empty state (this IT cannot be
     * {@code @Transactional}) and wires {@link JobLauncherTestUtils} to the qualified
     * {@code combineTransactionsJob} with the real launcher and repository.
     */
    @BeforeEach
    void initBatchAndCleanState() {
        deleteFrom(TRANSACTIONS_TABLE);
        jobLauncherTestUtils = new JobLauncherTestUtils();
        jobLauncherTestUtils.setJobLauncher(jobLauncher);
        jobLauncherTestUtils.setJobRepository(jobRepository);
        jobLauncherTestUtils.setJob(combineTransactionsJob);
    }

    /** Returns the shared {@code transactions} table to its empty-seed state for the other suites. */
    @AfterEach
    void cleanState() {
        deleteFrom(TRANSACTIONS_TABLE);
    }

    // =====================================================================================
    // Test methods.
    // =====================================================================================

    /**
     * The job runs end-to-end against real PostgreSQL and reports success: a scrambled-order seed
     * is combined and the {@link JobExecution} reports {@link BatchStatus#COMPLETED} with the
     * {@code COMPLETED} exit code.
     *
     * @throws Exception if the job launch fails (propagated from {@link JobLauncherTestUtils})
     */
    @Test
    @DisplayName("combineTransactionsJob completes with BatchStatus.COMPLETED and ExitStatus COMPLETED")
    void jobCompletesSuccessfully() throws Exception {
        // Seed the master in scrambled TRAN-ID order (3,1,5,2,4): STEP05R would receive these from
        // the concatenated BKUP(0)+SYSTRAN(0) inputs in arbitrary order before the ascending sort.
        persist(3, TransactionTypeCode.PURCHASE, "30.00", "MERCHANT-3");
        persist(1, TransactionTypeCode.PAYMENT, "10.00", "MERCHANT-1");
        persist(5, TransactionTypeCode.CREDIT, "50.00", "MERCHANT-5");
        persist(2, TransactionTypeCode.PURCHASE, "20.00", "MERCHANT-2");
        persist(4, TransactionTypeCode.REFUND, "40.00", "MERCHANT-4");

        JobExecution execution = launchCombineJob();

        assertThat(execution.getStatus())
                .as("combineTransactionsJob batch status")
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode())
                .as("combineTransactionsJob exit code")
                .isEqualTo(ExitStatus.COMPLETED.getExitCode());
    }

    /**
     * After the combine, every seeded transaction survives and the persisted master is queryable in
     * ascending {@code TRAN-ID} order, realizing the {@code STEP05R SORT FIELDS=(TRAN-ID,A)} goal of
     * a key-ordered transaction master.
     *
     * @throws Exception if the job launch fails
     */
    @Test
    @DisplayName("after combine, the persisted master is complete and ordered ascending by TRAN-ID")
    void outputIsSortedAscendingByTranId() throws Exception {
        persist(3, TransactionTypeCode.PURCHASE, "30.00", "MERCHANT-3");
        persist(1, TransactionTypeCode.PAYMENT, "10.00", "MERCHANT-1");
        persist(5, TransactionTypeCode.CREDIT, "50.00", "MERCHANT-5");
        persist(2, TransactionTypeCode.PURCHASE, "20.00", "MERCHANT-2");
        persist(4, TransactionTypeCode.REFUND, "40.00", "MERCHANT-4");

        JobExecution execution = launchCombineJob();
        assertThat(execution.getStatus())
                .as("combineTransactionsJob batch status")
                .isEqualTo(BatchStatus.COMPLETED);

        List<String> persistedIds = transactionRepository.findAll(Sort.by(Sort.Direction.ASC, "tranId"))
                .stream()
                .map(Transaction::getTranId)
                .toList();

        assertThat(persistedIds)
                .as("every seeded TRAN-ID survives the combine, in ascending order (STEP05R SORT FIELDS=(TRAN-ID,A))")
                .containsExactly(tranId(1), tranId(2), tranId(3), tranId(4), tranId(5));
        assertThat(persistedIds)
                .as("the persisted TRAN-IDs are in non-decreasing String order")
                .isSorted();
    }

    /**
     * <strong>CRITICAL — COMBTRAN duplicate-retention parity.</strong> Equal {@code TRAN-ID}
     * records are retained, never collapsed, because the control card declares no
     * {@code SUM FIELDS=NONE}.
     *
     * <p>Since the PK-keyed {@code transactions} master cannot hold two rows with the same
     * {@code TRAN-ID}, this reproduces the job's reader sort stage
     * ({@code combined.sort(transactionCombineComparator)} in
     * {@code CombineTransactionsJobConfig.combineTransactionsReader()}) over an in-memory input
     * containing a duplicate key — the concatenation case where {@code BKUP(0)} and
     * {@code SYSTRAN(0)} share a {@code TRAN-ID} — using the same Spring-managed comparator bean
     * the job injects.
     */
    @Test
    @DisplayName("CRITICAL: equal TRAN-ID records are retained, not collapsed (no SUM FIELDS=NONE)")
    void duplicateTranIdsAreRetainedNotCollapsed() {
        Transaction duplicateA =
                newTransaction(tranId(1), TransactionTypeCode.PURCHASE, new BigDecimal("10.00"), "MERCHANT-A");
        Transaction duplicateB =
                newTransaction(tranId(1), TransactionTypeCode.PAYMENT, new BigDecimal("20.00"), "MERCHANT-B");
        Transaction higherKey =
                newTransaction(tranId(2), TransactionTypeCode.CREDIT, new BigDecimal("30.00"), "MERCHANT-C");

        // The job's reader gathers the combined set then sorts it in place with this exact comparator.
        List<Transaction> combined = new ArrayList<>(List.of(higherKey, duplicateA, duplicateB));
        combined.sort(transactionCombineComparator);

        // The comparator signals equality (0) for the duplicate pair: a dedup-aware pipeline COULD
        // collapse them, but the combine performs a plain stable sort and never does.
        assertThat(transactionCombineComparator.compare(duplicateA, duplicateB))
                .as("comparator returns 0 for equal TRAN-IDs (no tie-breaker that could hide a duplicate)")
                .isZero();

        // No record is lost: 3 in, 3 out — both id-0001 duplicates survive alongside the higher key.
        assertThat(combined)
                .as("the combine sort retains every record, including both duplicate-key rows (no collapse)")
                .hasSize(3);
        assertThat(combined)
                .as("both equal-key rows survive, distinguished by their retained merchant names")
                .extracting(Transaction::getMerchantName)
                .contains("MERCHANT-A", "MERCHANT-B");
    }

    /**
     * The combine preserves the record count exactly — output count equals input count — proving
     * the writer drops and de-duplicates nothing as it loads the master (the {@code REPRO} parity).
     *
     * @throws Exception if the job launch fails
     */
    @Test
    @DisplayName("combine preserves the record count and retains every TRAN-ID (no rows dropped)")
    void recordCountPreservedAcrossCombine() throws Exception {
        persist(4, TransactionTypeCode.PURCHASE, "40.00", "MERCHANT-4");
        persist(2, TransactionTypeCode.PAYMENT, "20.00", "MERCHANT-2");
        persist(6, TransactionTypeCode.CREDIT, "60.00", "MERCHANT-6");
        persist(1, TransactionTypeCode.REFUND, "10.00", "MERCHANT-1");
        persist(5, TransactionTypeCode.PURCHASE, "50.00", "MERCHANT-5");
        persist(3, TransactionTypeCode.PAYMENT, "30.00", "MERCHANT-3");

        long inputCount = countRows(TRANSACTIONS_TABLE);
        assertThat(inputCount).as("seeded input record count").isEqualTo(6L);

        JobExecution execution = launchCombineJob();
        assertThat(execution.getStatus())
                .as("combineTransactionsJob batch status")
                .isEqualTo(BatchStatus.COMPLETED);

        assertThat(countRows(TRANSACTIONS_TABLE))
                .as("COMBTRAN retains every record — output count equals input count (no SUM FIELDS=NONE de-dup)")
                .isEqualTo(inputCount);
        assertThat(transactionRepository.findAll().stream().map(Transaction::getTranId).toList())
                .as("every input TRAN-ID is still present after the combine")
                .containsExactlyInAnyOrder(
                        tranId(1), tranId(2), tranId(3), tranId(4), tranId(5), tranId(6));
    }

    /**
     * Equal {@code TRAN-ID} keys keep their relative input order through the combine sort, because
     * {@link java.util.List#sort(java.util.Comparator)} is guaranteed stable and the comparator
     * declares no tie-breaker. This mirrors the COMBTRAN concatenation order
     * ({@code BKUP(0)} before {@code SYSTRAN(0)}); the same PK-keyed-master rationale as
     * {@link #duplicateTranIdsAreRetainedNotCollapsed()} applies, so the job's reader sort stage is
     * reproduced in memory with the wired comparator bean.
     */
    @Test
    @DisplayName("equal TRAN-ID keys keep their relative input order — the combine sort is stable")
    void stableOrderForEqualKeys() {
        Transaction firstArrival =
                newTransaction(tranId(1), TransactionTypeCode.PURCHASE, new BigDecimal("10.00"), "FIRST-ARRIVAL");
        Transaction secondArrival =
                newTransaction(tranId(1), TransactionTypeCode.PAYMENT, new BigDecimal("20.00"), "SECOND-ARRIVAL");
        Transaction higherKey =
                newTransaction(tranId(3), TransactionTypeCode.CREDIT, new BigDecimal("30.00"), "HIGHER-KEY");

        // higherKey leads the input; the two equal keys follow in arrival order (first, then second).
        List<Transaction> combined = new ArrayList<>(List.of(higherKey, firstArrival, secondArrival));
        combined.sort(transactionCombineComparator);

        // Stability: the equal-key pair keeps first-before-second; the higher key migrates to the tail.
        // equals()/hashCode() are TRAN-ID-only, so the value-equal pair is distinguished by identity.
        assertThat(combined.get(0))
                .as("first-arriving equal-key row stays first (stable sort)")
                .isSameAs(firstArrival);
        assertThat(combined.get(1))
                .as("second-arriving equal-key row stays second (stable sort)")
                .isSameAs(secondArrival);
        assertThat(combined.get(2))
                .as("the higher TRAN-ID sorts last, proving the ascending sort actually ran")
                .isSameAs(higherKey);
        assertThat(combined)
                .as("relative order of equal keys preserved, corroborated by merchant name")
                .extracting(Transaction::getMerchantName)
                .containsExactly("FIRST-ARRIVAL", "SECOND-ARRIVAL", "HIGHER-KEY");
    }

    // =====================================================================================
    // Fixtures / helpers.
    // =====================================================================================

    /**
     * Formats a numeric identifier as the zero-padded 16-character {@code TRAN-ID}
     * ({@code PIC X(16)}); exact width avoids {@code CHAR(16)} bpchar padding mismatch on read-back.
     *
     * @param n the numeric identifier
     * @return the 16-character zero-padded {@code TRAN-ID}
     */
    private static String tranId(long n) {
        return String.format("%016d", n);
    }

    /**
     * Builds a 26-character {@code TRAN-ORIG-TS}/{@code TRAN-PROC-TS} value from a {@code YYYY-MM-DD}
     * date, yielding the exact {@code YYYY-MM-DD HH:MM:SS.mmmmmm} layout the {@code CHAR(26)} column
     * preserves.
     *
     * @param date the {@code YYYY-MM-DD} date portion
     * @return the 26-character timestamp text
     */
    private static String ts(String date) {
        return date + " 00:00:00.000000";
    }

    /**
     * Builds a fully populated {@link Transaction} mirroring the {@code CVTRA05Y} record layout,
     * parameterized on the fields the combine assertions vary ({@code TRAN-ID}, type, amount, and a
     * distinguishing merchant name).
     *
     * @param tranId       the transaction identifier ({@code TRAN-ID})
     * @param type         the transaction type ({@code TRAN-TYPE-CD}, persisted via the converter)
     * @param amount       the monetary amount ({@code TRAN-AMT}, scale 2)
     * @param merchantName the merchant name ({@code TRAN-MERCHANT-NAME}) used to tell rows apart
     * @return a new, unsaved transaction fixture
     */
    private Transaction newTransaction(String tranId, TransactionTypeCode type,
            BigDecimal amount, String merchantName) {
        return new Transaction(
                tranId,                     // TRAN-ID            X(16) — natural primary key
                type.getCode(),             // TRAN-TYPE-CD       X(02) raw write-through (CVTRA05Y PIC X(2))
                1,                          // TRAN-CAT-CD        9(04)
                "POS",                      // TRAN-SOURCE        X(10)
                "Combine IT transaction",   // TRAN-DESC          X(100)
                amount,                     // TRAN-AMT           S9(09)V99 (scale 2)
                123456789L,                 // TRAN-MERCHANT-ID   9(09)
                merchantName,               // TRAN-MERCHANT-NAME X(50) — distinguishing field
                "SEATTLE",                  // TRAN-MERCHANT-CITY X(50)
                "98101",                    // TRAN-MERCHANT-ZIP  X(10)
                CARD_DEFAULT,               // TRAN-CARD-NUM      X(16)
                ts("2022-05-01"),           // TRAN-ORIG-TS       X(26)
                ts("2022-05-01"));          // TRAN-PROC-TS       X(26)
    }

    /**
     * Persists a transaction into the master from a numeric id and a string amount, returning the
     * saved entity.
     *
     * @param idNumber     the numeric identifier formatted by {@link #tranId(long)}
     * @param type         the transaction type
     * @param amount       the decimal amount as a string (parsed to a scale-2 {@link BigDecimal})
     * @param merchantName the distinguishing merchant name
     * @return the persisted transaction
     */
    private Transaction persist(long idNumber, TransactionTypeCode type, String amount, String merchantName) {
        return transactionRepository.save(
                newTransaction(tranId(idNumber), type, new BigDecimal(amount), merchantName));
    }

    /**
     * Builds job parameters carrying a unique {@code run.id} so every launch is a distinct
     * {@code JobInstance}.
     *
     * @return unique job parameters for one launch
     */
    private JobParameters uniqueJobParameters() {
        return new JobParametersBuilder()
                .addLong("run.id", RUN_ID_SEQUENCE.incrementAndGet())
                .toJobParameters();
    }

    /**
     * Launches {@code combineTransactionsJob} synchronously (Spring Boot's default launcher) with
     * unique parameters and returns the completed execution.
     *
     * @return the {@link JobExecution} of the finished run
     * @throws Exception if the launch fails
     */
    private JobExecution launchCombineJob() throws Exception {
        return jobLauncherTestUtils.launchJob(uniqueJobParameters());
    }
}

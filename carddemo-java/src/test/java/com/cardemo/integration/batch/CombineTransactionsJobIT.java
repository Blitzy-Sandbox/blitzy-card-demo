/*
 * ============================================================================
 *  CardDemo — Greenfield Java 25 LTS + Spring Boot 3.x Migration
 *  Stage-3 Combine / Merge Transactions — Spring Batch JOB integration test
 * ============================================================================
 *
 *  PROVENANCE & TRACEABILITY (AAP §0.7.1 / §0.7.2)
 *  Net-new greenfield parity test with NO COBOL source equivalent. It validates
 *  the production {@code combineTransactionsJob}, the Java migration of JCL
 *  app/jcl/COMBTRAN.jcl. COMBTRAN is unusual in the estate: it has NO COBOL
 *  program — it is a pure DFSORT (//STEP05R EXEC PGM=SORT, SORT FIELDS=(TRAN-ID,A))
 *  plus IDCAMS REPRO (//STEP10) merge job. Per AAP §0.7.6 the DFSORT step maps to
 *  a Java Comparator + bulk JPA insert, and the IDCAMS REPRO reload maps to
 *  TransactionRepository.saveAll merge-by-primary-key. The JCL source is read-only
 *  reference and is NEVER copied into this repository; traceability is by the
 *  frozen baseline commit SHA 27d6c6f only. Base package is com.cardemo
 *  (decision D-006 — deliberately NOT com.carddemo).
 * ============================================================================
 */
package com.cardemo.integration.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.cardemo.model.entity.Transaction;
import com.cardemo.repository.TransactionRepository;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Sort;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Integration test for <strong>Stage&nbsp;3</strong> of the CardDemo Spring Batch pipeline — the
 * production {@code combineTransactionsJob}, the faithful Java&nbsp;25 / Spring&nbsp;Boot&nbsp;3.x
 * migration of the JES job {@code app/jcl/COMBTRAN.jcl}.
 *
 * <h2>What COMBTRAN is (and is not)</h2>
 * <p>{@code COMBTRAN.jcl} has <strong>no COBOL program</strong>; it is a two-step utility job:</p>
 * <ol>
 *   <li><strong>{@code //STEP05R EXEC PGM=SORT} (DFSORT).</strong> {@code SORTIN} is the concatenation
 *       of {@code AWS.M2.CARDDEMO.TRANSACT.BKUP(0)} (the prior transaction-master backup) followed by
 *       {@code AWS.M2.CARDDEMO.SYSTRAN(0)} (the Stage-2 interest transactions). {@code SYMNAMES} defines
 *       {@code TRAN-ID,1,16,CH} and {@code SYSIN} requests {@code SORT FIELDS=(TRAN-ID,A)} — a global
 *       <strong>ascending</strong> sort on the 16-character transaction id, written to
 *       {@code SORTOUT = AWS.M2.CARDDEMO.TRANSACT.COMBINED(+1)}.</li>
 *   <li><strong>{@code //STEP10 EXEC PGM=IDCAMS} ({@code REPRO}).</strong>
 *       {@code REPRO INFILE(TRANSACT) OUTFILE(TRANVSAM)} bulk-loads the sorted {@code COMBINED}
 *       sequential file into the VSAM KSDS {@code AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS}.</li>
 * </ol>
 * <p>Net behaviour: <em>merge the BKUP master with the SYSTRAN interest transactions, order the union
 * ascending by {@code TRAN-ID}, and (re)load it into the transaction master</em> — with no per-record
 * transformation, deduplication or validation (DFSORT changes only ordering, never content).</p>
 *
 * <h2>Production contract under test (autowired by bean name)</h2>
 * <p>The migration (see {@code com.cardemo.batch.jobs.CombineTransactionsJob}) realizes COMBTRAN as a
 * single chunk step {@code combineTransactionsStep} (chunk size 100, identity processor, generics
 * {@code <Transaction, Transaction>}):</p>
 * <ul>
 *   <li><strong>Reader</strong> ({@code @StepScope IteratorItemReader}) concatenates
 *       {@code transactionRepository.findAll()} (the BKUP master = the current PostgreSQL transaction
 *       table) with the interest {@code Transaction} records read back from S3
 *       {@code carddemo-batch-output} under the {@code systran/} prefix (the {@code SYSTRAN(0)} GDG &rarr;
 *       S3 substitution, decision D-003), then performs the global ascending sort with
 *       {@code TransactionCombineProcessor.BY_TRAN_ID} ({@code == Comparator.comparing(Transaction::getTranId)}) —
 *       the DFSORT {@code SORT FIELDS=(TRAN-ID,A)} parity.</li>
 *   <li><strong>Writer</strong> delegates each chunk to {@code transactionRepository.saveAll(...)}.
 *       Because {@link Transaction#getTranId() tranId} is the assigned 16-character {@code @Id} primary
 *       key, {@code saveAll} merges by PK — an existing id UPDATES in place (no duplicate row) and a new
 *       id INSERTS — exactly reproducing {@code IDCAMS REPRO}'s full-reload (replace/merge) semantics.</li>
 * </ul>
 *
 * <h2>Assertion strategy (assert via repository final-state; SYSTRAN staged as inspect-&amp;-adapt)</h2>
 * <p>The {@code transaction} table is <strong>not</strong> seeded by the Flyway {@code V3} fixtures (it
 * is populated at runtime by the daily-posting job), so the repository starts <strong>empty</strong>.
 * Each test seeds its own BKUP rows and optionally stages SYSTRAN objects, then asserts the final
 * repository state. The two strongest, least-brittle parity statements anchor the suite:</p>
 * <ol>
 *   <li><strong>merge-by-PK produces NO duplicates</strong> (the {@code IDCAMS REPRO} full-reload), and</li>
 *   <li><strong>ascending {@code TRAN-ID} ordering</strong> (the DFSORT {@code SORT FIELDS=(TRAN-ID,A)}).</li>
 * </ol>
 * <p>The SYSTRAN union test stages objects in the <em>exact</em> fixed-width 350-byte {@code CVTRA05Y}
 * representation the production reader parses (the serializer here is the precise inverse of
 * {@code CombineTransactionsJob.deserializeSystranRecord}, so it is a derived contract, not a guess).
 * Every monetary assertion uses {@code compareTo} (AssertJ {@code isEqualByComparingTo}, never the
 * scale-sensitive {@code equals}); no {@code float}/{@code double} appears anywhere (AAP §0.7.3).</p>
 *
 * <h2>Harness &amp; conventions</h2>
 * <p>Extends {@link AbstractBatchJobIT}, inheriting the singleton PostgreSQL&nbsp;16 + LocalStack
 * containers, the {@code @DynamicPropertySource} wiring, the AWS provisioning/teardown lifecycle, and the
 * {@code launchJob}/{@code uniqueParams}/{@code clearJobRepository}/{@code putObject}/{@code emptyBucket}/
 * {@code countObjects} helpers — none redeclared here. The {@code *IT} suffix routes this class to the
 * {@code maven-failsafe-plugin} under {@code mvn verify -Pintegration}. The job is autowired
 * <strong>by bean name</strong> ({@code combineTransactionsJob}) and launched with {@link #launchJob}.</p>
 *
 * @see AbstractBatchJobIT
 * @see Transaction
 * @see TransactionRepository
 */
// The Spring Batch metadata tables (BATCH_JOB_INSTANCE, BATCH_JOB_EXECUTION, BATCH_*_SEQ, ...) are
// provisioned by Flyway (db/migration/V5__batch_metadata.sql) exactly as in production, so this IT
// deliberately runs with the PRODUCTION setting `spring.batch.jdbc.initialize-schema=never` (inherited
// from application.yml — NO override here). The inherited `clearJobRepository()` (@BeforeEach) and every
// `launchJob(...)` therefore exercise the SAME Flyway-owned batch schema the production app uses (re:
// QA FINAL 7 F-1 — tests must not mask the production schema-provisioning path).
@Import(CombineTransactionsJobIT.SecurityCorsTestConfig.class)
class CombineTransactionsJobIT extends AbstractBatchJobIT {

    // -------------------------------------------------------------------------
    // Parity constants — the fixed-width SYSTRAN record contract (CVTRA05Y) and
    // the S3 staging coordinates the production reader expects.
    // -------------------------------------------------------------------------

    /**
     * S3 key prefix under which Stage&nbsp;2 ({@code InterestCalculationJob}) stages the SYSTRAN interest
     * transactions and under which {@code combineTransactionsReader} lists them
     * ({@code systran/<yyyyMMdd>/<tranId>.dat}). Must match the production reader's prefix exactly.
     */
    private static final String SYSTRAN_KEY_PREFIX = "systran/";

    /**
     * Fixed SYSTRAN record length. The legacy {@code TRANSACT} DD is {@code RECFM=F LRECL=350} and the
     * record layout is {@code CVTRA05Y} ({@code 01 TRAN-RECORD}, RECLN&nbsp;350); the production reader
     * parses fixed offsets within a 350-character record, so the serializer below emits exactly this width.
     */
    private static final int SYSTRAN_RECORD_LENGTH = 350;

    /** Scale of {@code TRAN-AMT PIC S9(09)V99} (two fractional digits); mirrors the production encoder. */
    private static final int AMOUNT_SCALE = 2;

    /**
     * Full 26-character DB2 timestamp pattern ({@code yyyy-MM-dd-HH.mm.ss.SSSSSS}) matching the production
     * reader's {@code DB2_TIMESTAMP_FULL}. {@code TRAN-ORIG-TS}/{@code TRAN-PROC-TS} ({@code PIC X(26)})
     * round-trip through this exact pattern, so a staged record deserializes without loss.
     */
    private static final DateTimeFormatter DB2_TIMESTAMP_FULL =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HH.mm.ss.SSSSSS");

    // -------------------------------------------------------------------------
    // Production beans under test — autowired BY NAME from the full context.
    // -------------------------------------------------------------------------

    /** The Stage-3 job under test, resolved by bean name {@code combineTransactionsJob}. */
    @Autowired
    private Job combineTransactionsJob;

    /**
     * The transaction master — seeded as the BKUP source, asserted as the REPRO merge target. Its
     * {@code findAll()} is the BKUP half of the reader's {@code SORTIN}; its {@code saveAll(...)} is the
     * writer's bulk merge.
     */
    @Autowired
    private TransactionRepository transactionRepository;

    // -------------------------------------------------------------------------
    // Hermetic hygiene — the transaction table + SYSTRAN bucket are SHARED state
    // across the cached context and its sibling batch ITs, so each test starts
    // and ends from the natural empty baseline (the transaction table carries no
    // Flyway seed) and an empty carddemo-batch-output/systran prefix.
    // -------------------------------------------------------------------------

    /**
     * Resets the shared business state before each test: clears every transaction row (restoring the
     * empty {@code transaction}-table baseline) and empties the {@code carddemo-batch-output} bucket so a
     * sibling IT's staged {@code systran/} objects can never leak into this test's BKUP+SYSTRAN union.
     */
    @BeforeEach
    void resetBusinessStateBeforeEachTest() {
        transactionRepository.deleteAll();
        emptyBucket(BATCH_OUTPUT_BUCKET);
    }

    /**
     * Restores the empty baseline after each test (courtesy to sibling ITs that assert on
     * {@code transactionRepository.count()} or list the staging bucket).
     */
    @AfterEach
    void resetBusinessStateAfterEachTest() {
        transactionRepository.deleteAll();
        emptyBucket(BATCH_OUTPUT_BUCKET);
    }

    // -------------------------------------------------------------------------
    // Launch helper.
    // -------------------------------------------------------------------------

    /**
     * Launches {@code combineTransactionsJob} with a unique {@code JobInstance}. COMBTRAN takes no
     * run-date {@code PARM}, so no extra job parameters are supplied (unlike the Stage-2 INTCALC job).
     *
     * @return the terminal {@link JobExecution}
     * @throws Exception if the launch fails
     */
    private JobExecution launchCombineJob() throws Exception {
        return launchJob(combineTransactionsJob, uniqueParams());
    }

    // -------------------------------------------------------------------------
    // Fixture builders.
    // -------------------------------------------------------------------------

    /**
     * Builds a 16-character zero-padded numeric {@code TRAN-ID} ({@code PIC X(16)}). Because the id is
     * fixed-width and zero-padded, its lexicographic order equals its numeric order — the property the
     * DFSORT {@code CH} (character) sort and the {@code BY_TRAN_ID} comparator both rely on.
     *
     * @param n the numeric seed
     * @return the 16-character id, e.g. {@code n=3 -> "0000000000000003"}
     */
    private static String id(final int n) {
        return String.format("%016d", n);
    }

    /**
     * Builds a fully-populated {@link Transaction} with deterministic, parity-safe field values. The
     * monetary amount is supplied as an exact decimal string (no {@code float}/{@code double}, AAP
     * §0.7.3) so the persisted/serialized scale is explicit. The two timestamps are set to fixed,
     * micro-second-free values that round-trip cleanly through {@link #DB2_TIMESTAMP_FULL}.
     *
     * @param tranId      the 16-character transaction id (primary key)
     * @param amount      the {@code TRAN-AMT} as an exact decimal string (e.g. {@code "88.88"})
     * @param description the {@code TRAN-DESC} free-text description
     * @return the populated entity (not yet persisted)
     */
    private static Transaction transaction(final String tranId, final String amount,
            final String description) {
        final Transaction t = new Transaction();
        t.setTranId(tranId);
        t.setTranTypeCd("01");
        t.setTranCatCd(1);
        t.setTranSource("SYSTRAN");
        t.setTranDesc(description);
        t.setTranAmt(new BigDecimal(amount));
        t.setTranMerchantId(123456789L);
        t.setTranMerchantName("ACME MERCHANT");
        t.setTranMerchantCity("SEATTLE");
        t.setTranMerchantZip("98101");
        t.setTranCardNum("4111111111111111");
        t.setTranOrigTs(LocalDateTime.of(2022, 7, 19, 23, 23, 5));
        t.setTranProcTs(LocalDateTime.of(2022, 7, 19, 23, 23, 6));
        return t;
    }

    // -------------------------------------------------------------------------
    // SYSTRAN fixed-width serializer — the EXACT inverse of the production
    // reader's CombineTransactionsJob.deserializeSystranRecord (CVTRA05Y, 350B).
    // -------------------------------------------------------------------------

    /**
     * Left-justifies an alpha field into a fixed width, space-padding on the RIGHT (COBOL
     * {@code PIC X(n)} display convention). The production reader recovers the value with
     * {@code stripTrailing()}, so trailing spaces added here are transparently removed on read.
     *
     * @param value the field value ({@code null} treated as empty)
     * @param width the fixed field width
     * @return the {@code width}-character, right-space-padded field
     */
    private static String alpha(final String value, final int width) {
        final String v = value == null ? "" : value;
        final String truncated = v.length() > width ? v.substring(0, width) : v;
        return truncated + " ".repeat(width - truncated.length());
    }

    /**
     * Right-justifies a non-negative integer into a fixed width, zero-padding on the LEFT (COBOL
     * numeric-display convention). A {@code null} renders as all spaces, which the reader trims to an
     * empty string and maps to {@code null}.
     *
     * @param value the numeric value, or {@code null}
     * @param width the fixed field width
     * @return the {@code width}-character field
     */
    private static String numeric(final Long value, final int width) {
        if (value == null) {
            return " ".repeat(width);
        }
        final String digits = Long.toString(value);
        return "0".repeat(width - digits.length()) + digits;
    }

    /**
     * {@link #numeric(Long, int)} overload for {@link Integer} fields (for example {@code TRAN-CAT-CD}).
     *
     * @param value the numeric value, or {@code null}
     * @param width the fixed field width
     * @return the {@code width}-character field
     */
    private static String numeric(final Integer value, final int width) {
        return numeric(value == null ? null : value.longValue(), width);
    }

    /**
     * Encodes {@code TRAN-AMT} exactly as the production reader decodes it: the value's unscaled
     * magnitude at scale&nbsp;2 ({@code value × 100}) as a {@code width}-digit, left-zero-padded integer.
     * The reader reverses this with {@code new BigDecimal(new BigInteger(digits), 2)}. A {@code null}
     * renders as all spaces (&rarr; {@code null} on read). Uses {@link BigInteger} exclusively — never
     * {@code float}/{@code double} (AAP §0.7.3).
     *
     * @param value the monetary amount, or {@code null}
     * @param width the fixed field width (11 for {@code S9(09)V99})
     * @return the {@code width}-character field
     */
    private static String amount(final BigDecimal value, final int width) {
        if (value == null) {
            return " ".repeat(width);
        }
        final BigInteger unscaled = value.setScale(AMOUNT_SCALE).unscaledValue().abs();
        final String digits = unscaled.toString();
        return "0".repeat(width - digits.length()) + digits;
    }

    /**
     * Renders a 26-character DB2 timestamp ({@link #DB2_TIMESTAMP_FULL}) or 26 spaces when {@code null}.
     *
     * @param value the timestamp, or {@code null}
     * @return the 26-character field
     */
    private static String timestamp(final LocalDateTime value) {
        return value == null ? " ".repeat(26) : DB2_TIMESTAMP_FULL.format(value);
    }

    /**
     * Serializes a {@link Transaction} into the <strong>exact</strong> fixed-width 350-byte
     * {@code CVTRA05Y} record the production {@code combineTransactionsReader} parses — the precise
     * inverse of {@code CombineTransactionsJob.deserializeSystranRecord}. Field offsets/widths (0-based,
     * end-exclusive) follow {@code CVTRA05Y 01 TRAN-RECORD}:
     * <pre>
     *   [0,16)    TRAN-ID            X(16)    alpha    | [143,152) TRAN-MERCHANT-ID   9(9)   numeric
     *   [16,18)   TRAN-TYPE-CD       X(2)     alpha    | [152,202) TRAN-MERCHANT-NAME X(50)  alpha
     *   [18,22)   TRAN-CAT-CD        9(4)     numeric  | [202,252) TRAN-MERCHANT-CITY X(50)  alpha
     *   [22,32)   TRAN-SOURCE        X(10)    alpha    | [252,262) TRAN-MERCHANT-ZIP  X(10)  alpha
     *   [32,132)  TRAN-DESC          X(100)   alpha    | [262,278) TRAN-CARD-NUM      X(16)  alpha
     *   [132,143) TRAN-AMT  S9(09)V99 (11 digits)      | [278,304) TRAN-ORIG-TS       X(26)  timestamp
     *                                                  | [304,330) TRAN-PROC-TS       X(26)  timestamp
     *                                                  | [330,350) FILLER             X(20)  spaces
     * </pre>
     *
     * @param t the transaction to encode
     * @return the 350-character fixed-width SYSTRAN record
     */
    private static String serializeSystranRecord(final Transaction t) {
        final StringBuilder sb = new StringBuilder(SYSTRAN_RECORD_LENGTH);
        sb.append(alpha(t.getTranId(), 16));               // [0,16)    TRAN-ID
        sb.append(alpha(t.getTranTypeCd(), 2));            // [16,18)   TRAN-TYPE-CD
        sb.append(numeric(t.getTranCatCd(), 4));           // [18,22)   TRAN-CAT-CD
        sb.append(alpha(t.getTranSource(), 10));           // [22,32)   TRAN-SOURCE
        sb.append(alpha(t.getTranDesc(), 100));            // [32,132)  TRAN-DESC
        sb.append(amount(t.getTranAmt(), 11));             // [132,143) TRAN-AMT
        sb.append(numeric(t.getTranMerchantId(), 9));      // [143,152) TRAN-MERCHANT-ID
        sb.append(alpha(t.getTranMerchantName(), 50));     // [152,202) TRAN-MERCHANT-NAME
        sb.append(alpha(t.getTranMerchantCity(), 50));     // [202,252) TRAN-MERCHANT-CITY
        sb.append(alpha(t.getTranMerchantZip(), 10));      // [252,262) TRAN-MERCHANT-ZIP
        sb.append(alpha(t.getTranCardNum(), 16));          // [262,278) TRAN-CARD-NUM
        sb.append(timestamp(t.getTranOrigTs()));           // [278,304) TRAN-ORIG-TS
        sb.append(timestamp(t.getTranProcTs()));           // [304,330) TRAN-PROC-TS
        sb.append(" ".repeat(20));                         // [330,350) FILLER
        return sb.toString();
    }

    /**
     * Stages one {@link Transaction} as a SYSTRAN object in {@code carddemo-batch-output} under
     * {@code systran/<yyyyMMdd>/<tranId>.dat}, in the exact 350-byte fixed-width format the production
     * reader expects. Fails fast (before the job runs) if the encoded record is not exactly 350 bytes,
     * turning any offset drift into a clear, local assertion failure rather than an opaque parse error.
     *
     * @param t the interest transaction to stage
     */
    private void stageSystranObject(final Transaction t) {
        final String record = serializeSystranRecord(t);
        assertThat(record.length())
                .as("SYSTRAN record for %s must be the fixed CVTRA05Y width", t.getTranId())
                .isEqualTo(SYSTRAN_RECORD_LENGTH);
        final String key = SYSTRAN_KEY_PREFIX + "20220719/" + t.getTranId() + ".dat";
        putObject(BATCH_OUTPUT_BUCKET, key, record);
    }

    // -------------------------------------------------------------------------
    // Ordering oracles — the BY_TRAN_ID / DFSORT SORT FIELDS=(TRAN-ID,A) parity.
    // -------------------------------------------------------------------------

    /**
     * Reads back every persisted id ordered by {@code tranId} ascending. {@code Sort.by("tranId")} yields
     * the database's ascending order on the primary key — the observable effect of the reader's
     * {@code BY_TRAN_ID} comparator and of the DFSORT {@code SORT FIELDS=(TRAN-ID,A)} step.
     *
     * @return the persisted ids in ascending {@code tranId} order
     */
    private List<String> persistedIdsAscending() {
        return transactionRepository.findAll(Sort.by("tranId")).stream()
                .map(Transaction::getTranId)
                .toList();
    }

    /**
     * Distinct union of two id lists, preserving first-seen order (the set-merge oracle for the expected
     * post-REPRO row count).
     *
     * @param a the first id list (BKUP)
     * @param b the second id list (SYSTRAN)
     * @return the distinct union
     */
    private static List<String> distinctUnion(final List<String> a, final List<String> b) {
        final LinkedHashSet<String> union = new LinkedHashSet<>(a);
        union.addAll(b);
        return new ArrayList<>(union);
    }

    /**
     * Natural-ascending sort of the given ids. Java's natural {@link String} ordering is exactly the
     * comparison {@code TransactionCombineProcessor.BY_TRAN_ID == Comparator.comparing(Transaction::getTranId)}
     * applies, which is the DFSORT {@code SORT FIELDS=(TRAN-ID,A)} parity for the zero-padded ids.
     *
     * @param ids the ids to sort
     * @return a new ascending list
     */
    private static List<String> ascending(final List<String> ids) {
        final List<String> sorted = new ArrayList<>(ids);
        sorted.sort(Comparator.naturalOrder());
        return sorted;
    }

    // =========================================================================
    // Phase 1 — DB-only merge (no SYSTRAN): IDCAMS REPRO re-load is idempotent.
    // =========================================================================

    /**
     * <strong>Given</strong> the COMBTRAN {@code //STEP05R} {@code SORTIN} concatenates {@code BKUP(0)}
     * with {@code SYSTRAN(0)} — here the BKUP master is the current {@code transaction} table seeded with
     * {@code K} distinct 16-character {@code TRAN-ID} rows, and {@code SYSTRAN(0)} is empty (no
     * {@code systran/} objects in {@code carddemo-batch-output}), so {@code SORTIN == BKUP} only.
     * <strong>When</strong> {@code combineTransactionsJob} runs the DFSORT sort ({@code STEP05R}) and the
     * IDCAMS {@code REPRO} re-load ({@code STEP10}). <strong>Then</strong> the job completes and
     * {@code REPRO}'s full-reload re-saves every BKUP row by its assigned {@code @Id} primary key, so the
     * row count is UNCHANGED ({@code == K}) — re-saving identical PKs UPDATES in place and creates NO
     * duplicate rows (merge-by-PK parity with IDCAMS {@code REPRO}).
     */
    @Test
    @DisplayName("DB-only combine (empty SYSTRAN) re-loads the BKUP master with NO duplicate rows "
            + "— IDCAMS REPRO merge-by-PK (COMBTRAN STEP10)")
    void dbOnlyCombine_reLoadsWithoutDuplicates() throws Exception {
        // GIVEN — K BKUP rows, empty SYSTRAN staging.
        final int k = 6;
        final List<Transaction> seeded = new ArrayList<>();
        for (int i = 1; i <= k; i++) {
            seeded.add(transaction(id(i), "10.00", "BKUP TXN " + i));
        }
        transactionRepository.saveAll(seeded);
        assertThat(transactionRepository.count()).as("K BKUP rows seeded").isEqualTo(k);
        assertThat(countObjects(BATCH_OUTPUT_BUCKET, SYSTRAN_KEY_PREFIX))
                .as("no SYSTRAN staged for the DB-only case").isZero();

        // WHEN — run Stage-3 combine (DFSORT STEP05R + IDCAMS REPRO STEP10).
        final JobExecution execution = launchCombineJob();

        // THEN — completes, and re-loading identical PKs creates no duplicates.
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(transactionRepository.count())
                .as("REPRO merge-by-PK: re-saving identical PKs must not create duplicates")
                .isEqualTo(k);
        for (final Transaction t : seeded) {
            assertThat(transactionRepository.findById(t.getTranId()))
                    .as("BKUP row %s still present after the combine", t.getTranId())
                    .isPresent();
        }
    }

    // =========================================================================
    // Phase 2 — Union merge (DB BKUP ⧺ S3 SYSTRAN): new ids insert, existing
    // id updates in place. IDCAMS REPRO replace/merge semantics.
    // =========================================================================

    /**
     * <strong>Given</strong> the COMBTRAN {@code SORTIN} concatenation of {@code BKUP(0)} ⧺
     * {@code SYSTRAN(0)} — {@code K} BKUP rows are seeded in the DB, and {@code M} SYSTRAN objects are
     * staged to S3 {@code carddemo-batch-output} under {@code systran/} in the exact fixed-width 350-byte
     * {@code CVTRA05Y} format the production reader parses. Of the {@code M} SYSTRAN rows, two carry NEW
     * ids (absent from BKUP) and one carries an EXISTING BKUP id with a DIFFERENT amount/description.
     * <strong>When</strong> {@code combineTransactionsJob} runs. <strong>Then</strong> the
     * {@code IDCAMS REPRO} merge-by-PK grows the master by ONLY the two NEW distinct ids
     * ({@code count == K + 2}); the existing id is merge-UPDATED (not duplicated), and — because the
     * DFSORT ascending sort is STABLE, keeping the BKUP copy immediately before the SYSTRAN copy for the
     * shared id so {@code saveAll} merges the SYSTRAN copy LAST — the surviving row carries the SYSTRAN
     * payload (amount compared via {@code compareTo}).
     */
    @Test
    @DisplayName("Union combine merges BKUP ⧺ SYSTRAN by PK: new ids INSERT, existing id UPDATEs (no dup) "
            + "— COMBTRAN SORTIN concatenation + IDCAMS REPRO")
    void unionCombine_mergesNewAndExistingByPrimaryKey() throws Exception {
        // GIVEN — K BKUP rows.
        final int k = 5;
        final List<Transaction> bkup = new ArrayList<>();
        for (int i = 1; i <= k; i++) {
            bkup.add(transaction(id(i), "10.00", "BKUP TXN " + i));
        }
        transactionRepository.saveAll(bkup);

        // ... and M=3 staged SYSTRAN rows: one overlapping existing id, two new ids.
        final String existingId = id(3);
        final String newIdA = id(101);
        final String newIdB = id(102);
        final BigDecimal mergedAmount = new BigDecimal("88.88");
        stageSystranObject(transaction(existingId, "88.88", "SYSTRAN MERGE UPDATE"));
        stageSystranObject(transaction(newIdA, "11.11", "SYSTRAN NEW A"));
        stageSystranObject(transaction(newIdB, "22.22", "SYSTRAN NEW B"));
        assertThat(countObjects(BATCH_OUTPUT_BUCKET, SYSTRAN_KEY_PREFIX))
                .as("3 SYSTRAN objects staged").isEqualTo(3);

        // WHEN
        final JobExecution execution = launchCombineJob();

        // THEN — count grows by the 2 NEW distinct ids only.
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(transactionRepository.count())
                .as("REPRO merge-by-PK: K BKUP + 2 NEW ids; the existing-id SYSTRAN row updates (no dup)")
                .isEqualTo(k + 2);

        // ... the existing id reflects the SYSTRAN payload (merge-update, SYSTRAN copy merged last).
        final Transaction merged = transactionRepository.findById(existingId).orElseThrow();
        assertThat(merged.getTranAmt())
                .as("existing id %s merge-updated to the SYSTRAN amount (compareTo, AAP §0.7.3)", existingId)
                .isEqualByComparingTo(mergedAmount);
        assertThat(merged.getTranDesc())
                .as("existing id %s carries the full SYSTRAN payload after merge", existingId)
                .isEqualTo("SYSTRAN MERGE UPDATE");

        // ... both new ids inserted, and one round-trips its staged SYSTRAN amount through the reader.
        assertThat(transactionRepository.findById(newIdA)).as("new id A inserted").isPresent();
        assertThat(transactionRepository.findById(newIdB)).as("new id B inserted").isPresent();
        assertThat(transactionRepository.findById(newIdA).orElseThrow().getTranAmt())
                .as("new id A persisted with its SYSTRAN amount (compareTo)")
                .isEqualByComparingTo(new BigDecimal("11.11"));
    }

    // =========================================================================
    // Phase 3 — Sort ordering parity: DFSORT SORT FIELDS=(TRAN-ID,A) ->
    // TransactionCombineProcessor.BY_TRAN_ID (AAP §0.7.6).
    // =========================================================================

    /**
     * <strong>Given</strong> COMBTRAN {@code //STEP05R}: {@code SYMNAMES TRAN-ID,1,16,CH} +
     * {@code SORT FIELDS=(TRAN-ID,A)} globally orders the merged {@code SORTIN} ascending by the
     * 16-character {@code TRAN-ID}. BKUP rows are seeded DELIBERATELY OUT OF natural id order and SYSTRAN
     * rows are staged with interleaving (all-new) ids, so a correct combine must reorder the union
     * ascending. <strong>When</strong> {@code combineTransactionsJob} runs. <strong>Then</strong> reading
     * the persisted master back by {@code tranId} ascending equals the ascending {@code BKUP ∪ SYSTRAN}
     * union and is strictly sorted — the observable effect of the production reader's
     * {@code BY_TRAN_ID == Comparator.comparing(Transaction::getTranId)} comparator, the documented Java
     * mapping of the DFSORT {@code SORT FIELDS=(TRAN-ID,A)} step (AAP §0.7.6).
     */
    @Test
    @DisplayName("Combine orders the merged set ascending by TRAN-ID — DFSORT SORT FIELDS=(TRAN-ID,A) "
            + "-> BY_TRAN_ID comparator (COMBTRAN STEP05R, AAP §0.7.6)")
    void combine_ordersByTranIdAscending() throws Exception {
        // GIVEN — scrambled BKUP ids + interleaving SYSTRAN ids (all distinct, all new).
        final List<String> bkupIds = List.of(id(40), id(10), id(30), id(20), id(50));
        final List<Transaction> bkup = new ArrayList<>();
        for (final String tranId : bkupIds) {
            bkup.add(transaction(tranId, "10.00", "BKUP " + tranId));
        }
        transactionRepository.saveAll(bkup);

        final List<String> systranIds = List.of(id(25), id(5), id(45));
        for (final String tranId : systranIds) {
            stageSystranObject(transaction(tranId, "20.00", "SYSTRAN " + tranId));
        }

        final List<String> expectedAscending = ascending(distinctUnion(bkupIds, systranIds));

        // WHEN
        final JobExecution execution = launchCombineJob();

        // THEN — the persisted set, read back in tranId order, equals the ascending union.
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(transactionRepository.count())
                .as("every distinct BKUP ∪ SYSTRAN id persisted exactly once")
                .isEqualTo(expectedAscending.size());

        final List<String> persisted = persistedIdsAscending();
        assertThat(persisted)
                .as("read-back tranId order equals the ascending BKUP ∪ SYSTRAN union (DFSORT parity)")
                .containsExactlyElementsOf(expectedAscending);
        assertThat(persisted)
                .as("read-back ids are strictly ascending by TRAN-ID (BY_TRAN_ID comparator effect)")
                .isSorted();
    }

    // -------------------------------------------------------------------------
    // Nested test configuration.
    // -------------------------------------------------------------------------

    /**
     * Supplies a minimal {@link CorsConfigurationSource} bean so the production {@code WebConfig} /
     * {@code SecurityConfig} graph initializes in this {@code webEnvironment = NONE} batch context.
     * Declared locally (not imported from a sibling IT) so this test couples only to its declared
     * dependencies; mirrors the self-contained configuration used across the batch IT suite.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class SecurityCorsTestConfig {

        /**
         * @return an empty {@link UrlBasedCorsConfigurationSource} satisfying the
         *         {@code CorsConfigurationSource} dependency without registering any cross-origin
         *         mappings (none are required for batch tests)
         */
        @Bean
        CorsConfigurationSource corsConfigurationSource() {
            return new UrlBasedCorsConfigurationSource();
        }
    }
}

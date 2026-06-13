/*
 * CardDemo COBOL-to-Java migration — Stage-4a Statement Generation integration test.
 *
 * Traceability (COBOL is NEVER copied; reference by source commit SHA 27d6c6f only):
 *   - JCL  : app/jcl/CREASTMT.JCL   (DELDEF01 DELETE/DEFINE work cluster; STEP010 SORT; STEP020 REPRO;
 *                                    STEP030 IEFBR14 delete prior STATEMNT.PS/STATEMNT.HTML;
 *                                    STEP040 EXEC PGM=CBSTM03A — the last three gated COND=(0,NE))
 *   - COBOL: app/cbl/CBSTM03A.CBL   (statement driver: 1000-MAINLINE walks CARDXREF, joins CUST/ACCT,
 *                                    writes dual STMT-FILE PIC X(80) text + HTML-FILE PIC X(100))
 *   - COBOL: app/cbl/CBSTM03B.CBL   (generic OPEN/READ/READ-K/WRITE/CLOSE file-service CALLed by CBSTM03A;
 *                                    in the target it is an @Autowired file-service BEAN exercised
 *                                    transitively through the job — it is NEVER tested standalone here)
 *   - DATA : app/data/ASCII/cardxref.txt (50 cross-references -> the Flyway V3 card_xref seed driving the job)
 *
 * This integration test pins the production {@code statementGenerationJob} (Stage-4a, runs in parallel
 * with Stage-4b after Stage-3) to the EXACT behaviour of CREASTMT.JCL / CBSTM03A:
 *   (a) the two-step prepare->generate sequence wired with the Spring Batch default transition
 *       {@code start(prepareStatementsStep).next(generateStatementsStep)}, which reproduces the JCL
 *       {@code COND=(0,NE)} gate (generate runs ONLY if prepare ended normally);
 *   (b) one statement per card present in the cross-reference (CBSTM03A 1000-XREFFILE-GET-NEXT), each
 *       emitted as a dual output — one fixed-width plain-text object (STMT-FILE, PIC X(80)) AND one HTML
 *       object (HTML-FILE, PIC X(100)) — to the S3 statements bucket; and
 *   (c) the idempotent delete-then-create cadence (STEP030 deletes the prior run's outputs before
 *       STEP040 regenerates them), so a re-run never accumulates or duplicates statement objects.
 *
 * Assertions anchor on robust, parity-meaningful S3 facts: object COUNT (one .txt and one .html per
 * statemented card; .txt count == .html count), prepare-cleans-then-generate sequencing, and tolerant
 * content {@code contains} checks. The AccountStatement DTO and exact statement layout are treated as
 * inspect-and-adapt (asserted via S3 object presence + substring), never by importing the DTO. All
 * monetary comparisons use {@link java.math.BigDecimal#compareTo} — no float/double anywhere.
 */
package com.cardemo.integration.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Transaction;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.TransactionRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Integration test for the production {@code statementGenerationJob} bean (Stage-4a of the batch
 * pipeline), the Java migration of JCL {@code CREASTMT.JCL} driving COBOL {@code CBSTM03A} (which in
 * turn {@code CALL}s the file-service subprogram {@code CBSTM03B}).
 *
 * <p><strong>What this proves.</strong> The legacy statement job deletes the prior run's outputs and
 * then writes, for every card in the cross-reference, a pair of fixed-width outputs — a plain-text
 * statement ({@code STMT-FILE}, {@code PIC X(80)}) and an HTML statement ({@code HTML-FILE},
 * {@code PIC X(100)}). The migrated job realizes those two sequential PS/HTML datasets as a pair of S3
 * objects per statemented card ({@code .txt} + {@code .html}) in the {@code carddemo-statements} bucket,
 * and reproduces the {@code COND=(0,NE)} gate as the Spring Batch default
 * {@code start(prepareStatementsStep).next(generateStatementsStep)} transition. Each {@code @Test}
 * pins one of those behaviours against the real job output.</p>
 *
 * <p><strong>CBSTM03B is verified transitively.</strong> The COBOL {@code CALL 'CBSTM03B'} maps to an
 * {@code @Autowired} file-service bean used <em>inside</em> the production {@code StatementProcessor} /
 * {@code StatementWriter}; it is therefore never instantiated or asserted on directly here. Its
 * file-service role is proven by the fact that statements are actually produced (and, in
 * {@link #seededTransactions_appearInStatement_withExactAmounts()}, that a seeded card's transactions
 * appear in that card's statement).</p>
 *
 * <p><strong>Test strategy.</strong> The {@code card_xref}, {@code account} and {@code customer} tables
 * are Flyway-seeded (V3) with the 50 canonical ASCII fixtures, so the job has 50 cross-references to
 * statement and every cross-reference resolves to an existing account and customer (the processor's
 * keyed reads are fatal on a miss — COBOL {@code 9999-ABEND-PROGRAM}). The {@code transaction} table is
 * NOT seeded by Flyway, so this test fully owns it: a test that wants transaction detail lines seeds its
 * own rows on a {@code card_xref}-backed card number. Statements are asserted purely via the inherited
 * S3 helpers ({@code listKeys}/{@code countObjects}/{@code getObjectAsString}); the expected statement
 * count is derived dynamically from the repository (the number of <em>distinct</em> account ids across
 * the cross-references, because the writer keys each statement object by account id) rather than
 * hardcoded.</p>
 *
 * <p>Conventions inherited from {@link AbstractBatchJobIT}: real PostgreSQL + LocalStack Testcontainers,
 * {@code webEnvironment=NONE}, {@code @ActiveProfiles("test")}, manual {@code launchJob(...)} (six
 * {@code Job} beans exist, so {@code @SpringBatchTest} auto-wiring would be ambiguous), per-launch
 * {@code uniqueParams()}, and the {@code listKeys}/{@code countObjects}/{@code getObjectAsString}/
 * {@code putObject}/{@code emptyBucket} S3 helpers — none redeclared here. The {@code @TestPropertySource}
 * below provisions the Spring Batch metadata tables (Flyway only creates the business tables; for a
 * non-embedded database {@code spring.batch.jdbc.initialize-schema} defaults to {@code embedded}, a
 * no-op, and the inherited {@code clearJobRepository()} / {@code launchJob(...)} require those tables),
 * and {@code SecurityCorsTestConfig} supplies the {@link CorsConfigurationSource} bean the production
 * security graph requires under {@code webEnvironment=NONE}.</p>
 */
@TestPropertySource(properties = "spring.batch.jdbc.initialize-schema=always")
@Import(StatementGenerationJobIT.SecurityCorsTestConfig.class)
@DisplayName("StatementGenerationJob (Stage-4a) integration — CREASTMT/CBSTM03A prepare-then-generate parity")
class StatementGenerationJobIT extends AbstractBatchJobIT {

    // -------------------------------------------------------------------------------------------------
    // S3 layout constants — MUST mirror the production StatementWriter / StatementGenerationJob, which
    // write/clean objects under the "statements/" prefix as "<accountId>.txt" and "<accountId>.html".
    // -------------------------------------------------------------------------------------------------

    /** Key prefix under which the writer emits (and the prepare tasklet cleans) statement objects. */
    private static final String STATEMENTS_KEY_PREFIX = "statements/";

    /** Suffix of the plain-text statement object (COBOL {@code STMT-FILE}, {@code FD-STMTFILE-REC PIC X(80)}). */
    private static final String TEXT_SUFFIX = ".txt";

    /** Suffix of the HTML statement object (COBOL {@code HTML-FILE}, {@code FD-HTMLFILE-REC PIC X(100)}). */
    private static final String HTML_SUFFIX = ".html";

    // -------------------------------------------------------------------------------------------------
    // Production step names (assembled in StatementGenerationJob) — used for the sequencing assertions.
    // -------------------------------------------------------------------------------------------------

    /** First step: the prepare/cleanup tasklet (CREASTMT STEP030 + DELDEF01 cleanup intent). */
    private static final String PREPARE_STEP = "prepareStatementsStep";

    /** Second step: the XREF-driven statement-generation chunk (CREASTMT STEP040 = CBSTM03A). */
    private static final String GENERATE_STEP = "generateStatementsStep";

    /**
     * Stable banner/caption literals every statement body carries (CBSTM03A {@code 5000-CREATE-STATEMENT}).
     * These are layout-tolerant content anchors: they hold even for an account with zero transactions,
     * because the header, basic-details and (empty) transaction-summary sections are always present.
     */
    private static final List<String> TEXT_STATEMENT_MARKERS = List.of(
            "START OF STATEMENT", // ST-LINE0 banner
            "Basic Details",      // ST-LINE6 caption
            "Account ID",         // ST-LINE7 label
            "Current Balance",    // ST-LINE8 label
            "FICO Score",         // ST-LINE9 label
            "TRANSACTION SUMMARY",// ST-LINE11 caption
            "Total EXP:",         // ST-LINE14A running-total caption
            "END OF STATEMENT");  // ST-LINE15 banner

    /**
     * Production statement-generation job under test, autowired by bean name. The CardDemo context holds
     * six {@code Job} beans, so this MUST be wired by the field name {@code statementGenerationJob}
     * (matching the {@code @Bean} method name in {@code StatementGenerationJob}); a by-type injection
     * would be ambiguous.
     */
    @Autowired
    private Job statementGenerationJob;

    /**
     * The {@code CARDXREF} driver repository (Flyway-seeded with 50 rows). Used to compute the expected
     * statement count (distinct account ids) and to obtain a real card number for transaction seeding.
     */
    @Autowired
    private CardCrossReferenceRepository cardCrossReferenceRepository;

    /**
     * The {@code TRANSACT} repository. The base {@code transaction} table is NOT seeded by Flyway, so
     * this test owns it entirely; tests that need statement detail lines seed their own rows here.
     */
    @Autowired
    private TransactionRepository transactionRepository;

    // -------------------------------------------------------------------------------------------------
    // Per-test hygiene. The base @BeforeEach clears ONLY the Spring Batch job repository; here we also
    // guarantee a clean transaction table and a clean statements bucket both before and after each test
    // so statement-object counts and content checks are unambiguous and tests are fully independent.
    // -------------------------------------------------------------------------------------------------

    @BeforeEach
    void resetBeforeEach() {
        resetState();
    }

    @AfterEach
    void resetAfterEach() {
        resetState();
    }

    private void resetState() {
        transactionRepository.deleteAll();
        emptyBucket(STATEMENTS_BUCKET);
    }

    // -------------------------------------------------------------------------------------------------
    // Helpers.
    // -------------------------------------------------------------------------------------------------

    /**
     * Lists the statement object keys under {@link #STATEMENTS_KEY_PREFIX} whose key ends with the given
     * suffix ({@link #TEXT_SUFFIX} or {@link #HTML_SUFFIX}), preserving listing order.
     *
     * @param suffix the object-key suffix to filter by
     * @return the matching keys (possibly empty, never {@code null})
     */
    private List<String> statementKeys(String suffix) {
        return listKeys(STATEMENTS_BUCKET, STATEMENTS_KEY_PREFIX).stream()
                .filter(key -> key.endsWith(suffix))
                .toList();
    }

    /**
     * Computes the number of statement objects the job is expected to produce per format. The writer keys
     * each statement object by the resolved account id ({@code statements/<gen>/<accountId>.txt|.html}),
     * so two cross-references sharing an account would collapse onto one key; the robust expectation is
     * therefore the count of <em>distinct</em> account ids across all cross-references. With the canonical
     * fixture (50 cross-references, each a distinct account) this equals {@code cardCrossReferenceRepository.count()}.
     *
     * @return the expected number of {@code .txt} (and, identically, {@code .html}) statement objects
     */
    private long expectedStatementCount() {
        return cardCrossReferenceRepository.findAll().stream()
                .map(CardCrossReference::getXrefAcctId)
                .distinct()
                .count();
    }

    /**
     * Returns the card number of the cross-reference owning the given account id. Used to seed
     * transactions onto a real {@code card_xref} card so they surface in that account's statement
     * (CBSTM03A {@code findByTranCardNum} per-card gather).
     *
     * @param accountId the owning account id to resolve a card for
     * @return the 16-character card number of a cross-reference whose account id equals {@code accountId}
     */
    private String cardNumberForAccount(long accountId) {
        return cardCrossReferenceRepository.findAll().stream()
                .filter(xref -> Long.valueOf(accountId).equals(xref.getXrefAcctId()))
                .map(CardCrossReference::getXrefCardNum)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Expected a seeded card_xref row for account " + accountId));
    }

    /**
     * Builds an unsaved {@link Transaction} for seeding, mirroring the proven sibling fixture shape so the
     * row satisfies every {@code transaction} column constraint and reference foreign key: the
     * type/category codes {@code ("01", 1)} are V3-seeded reference values, all NOT-NULL string columns
     * are within their declared lengths, and {@code TRAN-AMT} is a scale-2 {@link BigDecimal} (no
     * float/double). Only the card number actually matters to the statement processor (which gathers a
     * card's transactions), but full validity is required for the INSERT to succeed.
     *
     * @param seq        a unique sequence number rendered into the 16-digit {@code TRAN-ID} primary key
     * @param cardNumber a {@code card_xref}-backed card number (the per-card statement gather key)
     * @param amount     the {@code TRAN-AMT} as a decimal string, parsed to a scale-2 {@link BigDecimal}
     * @return a fully-populated, unsaved {@link Transaction}
     */
    private Transaction txn(int seq, String cardNumber, String amount) {
        Transaction t = new Transaction();
        t.setTranId(String.format("%016d", seq)); // TRAN-ID PIC X(16) — 16-char zero-padded numeric key
        t.setTranTypeCd("01");                     // type_code (len 2) — V3-seeded 'Purchase'
        t.setTranCatCd(1);                         // category_code — V3-seeded ('01',1) 'Regular Sales Draft'
        t.setTranSource("STMTGEN");                // source (len 10)
        t.setTranDesc("STMTGEN IT TXN " + seq);    // description (len 100)
        t.setTranAmt(new BigDecimal(amount));      // TRAN-AMT PIC S9(09)V99 -> BigDecimal(scale 2)
        t.setTranMerchantId(800000000L + seq);
        t.setTranMerchantName("ACME MERCHANT");
        t.setTranMerchantCity("SEATTLE");
        t.setTranMerchantZip("98101");
        t.setTranCardNum(cardNumber);              // card_number (len 16) — drives the per-card gather
        LocalDateTime ts = LocalDate.of(2024, 1, 15).atTime(12, 0, 0);
        t.setTranOrigTs(ts);
        t.setTranProcTs(ts);
        return t;
    }

    /**
     * Finds the single {@link StepExecution} with the given step name within a job execution.
     *
     * @param execution the completed job execution to inspect
     * @param stepName  the step name to locate ({@link #PREPARE_STEP} or {@link #GENERATE_STEP})
     * @return the matching {@link StepExecution}
     */
    private static StepExecution stepExecution(JobExecution execution, String stepName) {
        return execution.getStepExecutions().stream()
                .filter(se -> stepName.equals(se.getStepName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No StepExecution named '" + stepName + "'"));
    }

    /**
     * Extracts the account id encoded in a statement object key. The writer names each object
     * {@code statements/<generation>/<accountId>.txt} (or {@code .html}), so the account id is the
     * filename stem (the segment after the last {@code '/'} and before the {@code '.'} suffix).
     *
     * @param key a statement object key
     * @return the numeric account id encoded in the key
     */
    private static long accountIdFromKey(String key) {
        String fileName = key.substring(key.lastIndexOf('/') + 1);
        return Long.parseLong(fileName.substring(0, fileName.indexOf('.')));
    }

    // =================================================================================================
    // Phase 1 — Happy path: one text + one HTML statement per statemented card.
    // =================================================================================================

    /**
     * Proves the core CREASTMT/CBSTM03A behaviour: the prepare-then-generate job COMPLETES and writes,
     * for every card present in the cross-reference, BOTH a plain-text statement object
     * ({@code .txt} = {@code STMT-FILE PIC X(80)}) and an HTML statement object
     * ({@code .html} = {@code HTML-FILE PIC X(100)}) to the statements bucket, in lock-step. The two
     * objects are written together for each card (CBSTM03A emits {@code STMT-FILE} and {@code HTML-FILE}
     * in the same {@code 1000-MAINLINE} pass), so the {@code .txt} count equals the {@code .html} count
     * equals the number of distinct statemented accounts. A tolerant content spot-check confirms a
     * sampled statement carries the always-present banners/captions and names its own account id.
     */
    @Test
    @DisplayName("Happy path: prepare->generate COMPLETES and writes one .txt + one .html per statemented "
            + "card (CREASTMT STEP030->STEP040; CBSTM03A dual STMT-FILE + HTML-FILE)")
    void happyPath_oneTextAndOneHtmlPerStatementedCard_completed() throws Exception {
        // Given: the Flyway-seeded card_xref drives one statement per distinct account, and the
        // statements bucket starts empty (cleaned in @BeforeEach). CBSTM03A writes a text AND an HTML
        // output for each card it statements.
        long expected = expectedStatementCount();
        assertThat(expected)
                .as("fixture sanity: every seeded cross-reference is a distinct account, so the job "
                        + "statements one object pair per cross-reference")
                .isEqualTo(cardCrossReferenceRepository.count())
                .isGreaterThan(0);

        // When: the statement-generation job runs (CREASTMT STEP030 prepare -> STEP040 CBSTM03A generate).
        JobExecution execution = launchJob(statementGenerationJob, uniqueParams());

        // Then: the job completed normally — the COND=(0,NE) gate let STEP040 run after STEP030.
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // And: exactly one .txt and one .html per statemented card, written in lock-step.
        List<String> textKeys = statementKeys(TEXT_SUFFIX);
        List<String> htmlKeys = statementKeys(HTML_SUFFIX);
        assertThat(textKeys)
                .as("one text statement (.txt = STMT-FILE PIC X(80)) per statemented card")
                .hasSize((int) expected);
        assertThat(htmlKeys)
                .as("one HTML statement (.html = HTML-FILE PIC X(100)) per statemented card")
                .hasSize((int) expected);
        assertThat(textKeys.size())
                .as("dual-output invariant: a text AND an HTML object are emitted together per statement")
                .isEqualTo(htmlKeys.size());
        assertThat(countObjects(STATEMENTS_BUCKET))
                .as("the bucket holds exactly the (text + html) pairs — no stray objects")
                .isEqualTo(expected * 2);

        // And: spot-check one statement's content (exact layout is inspect-and-adapt -> assert tolerantly).
        String sampleTextKey = textKeys.get(0);
        String sampleText = getObjectAsString(STATEMENTS_BUCKET, sampleTextKey);
        for (String marker : TEXT_STATEMENT_MARKERS) {
            assertThat(sampleText)
                    .as("text statement %s carries the CBSTM03A marker '%s'", sampleTextKey, marker)
                    .contains(marker);
        }
        // The text body names its own account id, picture-edited as ST-ACCT-ID (9(11) zero-padded), and
        // that id must equal the account id encoded in the object key (statements/<gen>/<accountId>.txt).
        long sampleAccountId = accountIdFromKey(sampleTextKey);
        assertThat(sampleText)
                .as("the .txt body names its own account id (zero-padded to 11 digits, ST-ACCT-ID)")
                .contains(String.format("%011d", sampleAccountId));

        // And: the paired .html object is a complete HTML document (HTML-FILE output, 5100/5200 builders).
        String sampleHtmlKey =
                sampleTextKey.substring(0, sampleTextKey.length() - TEXT_SUFFIX.length()) + HTML_SUFFIX;
        String sampleHtml = getObjectAsString(STATEMENTS_BUCKET, sampleHtmlKey);
        assertThat(sampleHtml)
                .as("the paired .html object is a full HTML statement document")
                .contains("<!DOCTYPE html>", "Bank of XYZ", "Transaction Summary");
    }

    /**
     * Proves the per-card transaction gather (CBSTM03A {@code 4000-TRNXFILE-GET} /
     * {@code 6000-WRITE-TRANS}, realized as {@code TransactionRepository.findByTranCardNum}) and, with
     * it, that the {@code CBSTM03B} file-service bean is wired and exercised transitively: transactions
     * seeded onto one card surface as detail lines — with EXACT amounts — in that card's statement, and
     * the running total ({@code WS-TOTAL-AMT}) reflects their sum. Monetary values are handled purely as
     * {@link BigDecimal} and compared via {@link BigDecimal#compareTo} (no float/double). Seeding does not
     * change the per-card object counts.
     */
    @Test
    @DisplayName("Seeded transactions surface as exact detail lines + running total in their card's "
            + "statement (CBSTM03A 6000-WRITE-TRANS via findByTranCardNum; CBSTM03B exercised transitively)")
    void seededTransactions_appearInStatement_withExactAmounts() throws Exception {
        // Given: a real card_xref-backed card for account 1, with three seeded transactions of known amounts.
        long accountId = 1L;
        String card = cardNumberForAccount(accountId);
        BigDecimal a1 = new BigDecimal("1234.56");
        BigDecimal a2 = new BigDecimal("7890.12");
        BigDecimal a3 = new BigDecimal("100.01");
        transactionRepository.saveAll(List.of(
                txn(900001, card, a1.toPlainString()),
                txn(900002, card, a2.toPlainString()),
                txn(900003, card, a3.toPlainString())));
        // Expected running total (CBSTM03A WS-TOTAL-AMT) computed with BigDecimal and verified via compareTo.
        BigDecimal expectedTotal = a1.add(a2).add(a3);
        assertThat(expectedTotal.compareTo(new BigDecimal("9224.69")))
                .as("seeded running total is exact (BigDecimal arithmetic, compared via compareTo)")
                .isZero();

        // When: the statement-generation job runs.
        JobExecution execution = launchJob(statementGenerationJob, uniqueParams());

        // Then: it completed and still produced exactly one (text + html) pair per card.
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        long expected = expectedStatementCount();
        assertThat(statementKeys(TEXT_SUFFIX)).hasSize((int) expected);
        assertThat(statementKeys(HTML_SUFFIX)).hasSize((int) expected);

        // And: account 1's text statement lists each seeded transaction id and its exact amount, plus the
        // running total — proving the per-card gather ran and the file-service path produced the output.
        String acct1TextKey = statementKeys(TEXT_SUFFIX).stream()
                .filter(key -> key.endsWith("/" + accountId + TEXT_SUFFIX))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no .txt statement for account " + accountId));
        String acct1Text = getObjectAsString(STATEMENTS_BUCKET, acct1TextKey);
        assertThat(acct1Text)
                .as("statement for account %d lists each seeded transaction id, amount and the total "
                        + "(CBSTM03A 6000-WRITE-TRANS + WS-TOTAL-AMT)", accountId)
                .contains(String.format("%016d", 900001))
                .contains(String.format("%016d", 900002))
                .contains(String.format("%016d", 900003))
                .contains(a1.toPlainString())
                .contains(a2.toPlainString())
                .contains(a3.toPlainString())
                .contains(expectedTotal.toPlainString());
    }

    // =================================================================================================
    // Phase 2 — prepare->generate sequencing & idempotent re-run (JCL COND=(0,NE) parity).
    // =================================================================================================

    /**
     * Proves CREASTMT {@code STEP030} ({@code EXEC PGM=IEFBR14} deleting the prior run's
     * {@code STATEMNT.PS}/{@code STATEMNT.HTML}): a stale object staged under the {@code statements/}
     * prefix before launch is GONE after the run, because the prepare tasklet deletes every prior-run
     * object before generation, leaving only the fresh run's statement pairs.
     */
    @Test
    @DisplayName("Prepare step deletes the prior run's statement objects before generating "
            + "(CREASTMT STEP030 delete of STATEMNT.PS/STATEMNT.HTML)")
    void prepareStep_deletesStaleStatementsBeforeGenerate() throws Exception {
        // Given: a stale object from a notional prior run sits under the statements/ prefix.
        String staleKey = STATEMENTS_KEY_PREFIX + "19990101/stale-prior-run.txt";
        putObject(STATEMENTS_BUCKET, staleKey, "STALE STATEMENT FROM A PRIOR RUN - MUST BE DELETED");
        assertThat(listKeys(STATEMENTS_BUCKET, STATEMENTS_KEY_PREFIX))
                .as("precondition: the stale object is present before the job runs")
                .contains(staleKey);

        // When: the job runs (STEP030 prepare cleans the bucket, then STEP040 regenerates).
        JobExecution execution = launchJob(statementGenerationJob, uniqueParams());

        // Then: it completed, the stale object is gone, and only fresh statement pairs remain.
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(listKeys(STATEMENTS_BUCKET, STATEMENTS_KEY_PREFIX))
                .as("prepare step removed the prior-run object before regenerating (STEP030 delete)")
                .doesNotContain(staleKey);
        long expected = expectedStatementCount();
        assertThat(countObjects(STATEMENTS_BUCKET))
                .as("only the fresh run's (text + html) statement pairs remain — no stale carryover")
                .isEqualTo(expected * 2);
    }

    /**
     * Proves the JCL {@code COND=(0,NE)} sequential gate, realized as the Spring Batch default
     * {@code start(prepareStatementsStep).next(generateStatementsStep)} transition: the job runs exactly
     * the two steps, both COMPLETE, and the prepare step finishes before the generate step starts (so
     * generation can only run after the prepare/cleanup ended normally).
     */
    @Test
    @DisplayName("Steps run in order: prepareStatementsStep COMPLETES before generateStatementsStep starts "
            + "(JCL COND=(0,NE) -> Spring Batch default .next(...) transition)")
    void steps_executeInOrder_prepareCompletedBeforeGenerate() throws Exception {
        // When: the job runs.
        JobExecution execution = launchJob(statementGenerationJob, uniqueParams());

        // Then: it completed with exactly the two expected steps.
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getStepExecutions())
                .as("the job runs exactly two steps: prepare then generate")
                .hasSize(2);

        StepExecution prepare = stepExecution(execution, PREPARE_STEP);
        StepExecution generate = stepExecution(execution, GENERATE_STEP);

        // Both steps completed normally — the COND=(0,NE) gate requires prepare to end RC=0 before generate.
        assertThat(prepare.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(generate.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // And: prepare finished before generate started — the sequential prepare->generate ordering.
        assertThat(prepare.getEndTime()).isNotNull();
        assertThat(generate.getStartTime()).isNotNull();
        assertThat(prepare.getEndTime().isAfter(generate.getStartTime()))
                .as("prepareStatementsStep end (%s) must not be after generateStatementsStep start (%s)",
                        prepare.getEndTime(), generate.getStartTime())
                .isFalse();
    }

    /**
     * Proves the legacy delete-then-create cadence makes regeneration idempotent: running the job twice
     * (each with fresh, unique parameters) WITHOUT any manual cleanup between runs leaves the SAME number
     * of statement objects — the prepare step deletes the prior run's objects and generation recreates
     * them (last-write-wins on the per-day, per-account key), so the bucket neither accumulates nor
     * duplicates statements (CREASTMT STEP030 delete -> STEP040 recreate).
     */
    @Test
    @DisplayName("Re-running the job is idempotent: prepare-then-generate leaves the same statement-object "
            + "count (CREASTMT delete-then-create; no accumulation/duplication)")
    void reRun_isIdempotent_sameStatementCount() throws Exception {
        // When: the job runs once, then again, with NO manual cleanup between runs (the prepare step is
        // the only thing that clears the prior run's objects).
        JobExecution first = launchJob(statementGenerationJob, uniqueParams());
        assertThat(first.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        long afterFirst = countObjects(STATEMENTS_BUCKET);

        JobExecution second = launchJob(statementGenerationJob, uniqueParams());
        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        long afterSecond = countObjects(STATEMENTS_BUCKET);

        // Then: both runs leave the same object count (one text+html pair per statemented card).
        long expected = expectedStatementCount();
        assertThat(afterFirst)
                .as("first run writes one (text + html) pair per statemented card")
                .isEqualTo(expected * 2);
        assertThat(afterSecond)
                .as("second run is idempotent: prepare deletes then generate recreates -> same count")
                .isEqualTo(afterFirst);
    }

    // =================================================================================================
    // Test-only Spring configuration.
    // =================================================================================================

    /**
     * Supplies the {@link CorsConfigurationSource} bean that the production {@code SecurityConfig} /
     * {@code WebConfig} graph requires. Under {@code @SpringBootTest(webEnvironment = NONE)} the servlet
     * web infrastructure that would otherwise publish this bean is absent, so the security filter chain's
     * CORS delegate would be unsatisfied and the application context would fail to refresh. This
     * {@code @TestConfiguration} publishes an empty {@link UrlBasedCorsConfigurationSource} (behaviorally
     * equivalent to the production default of no CORS mappings) so the non-web batch context loads. It is
     * {@code @TestConfiguration} (excluded from the application component scan) and {@code @Import}ed only
     * by this class, so it touches neither production code nor the shared base harness.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class SecurityCorsTestConfig {

        /**
         * @return an empty {@link UrlBasedCorsConfigurationSource} satisfying the
         *         {@code CorsConfigurationSource} dependency without registering any cross-origin mappings
         */
        @Bean
        CorsConfigurationSource corsConfigurationSource() {
            return new UrlBasedCorsConfigurationSource();
        }
    }
}

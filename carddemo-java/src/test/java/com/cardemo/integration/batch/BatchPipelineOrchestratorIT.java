/*
 * ============================================================================
 *  CardDemo — Greenfield Java 25 LTS + Spring Boot 3.x Migration
 *  Integration Test — 5-Stage Batch Pipeline ORCHESTRATION (step sequencing,
 *  JCL COND condition-code routing, and the parallel Stage-4 split)
 * ============================================================================
 *
 *  PROVENANCE & TRACEABILITY (AAP §0.7.1 / §0.7.2)
 *  Net-new greenfield test with NO COBOL source equivalent. The orchestration
 *  behaviour exercised here is translated from the frozen AWS CardDemo COBOL/JCL
 *  baseline at commit SHA 27d6c6f; the JCL job-step chain it pins — POSTTRAN ->
 *  INTCALC -> COMBTRAN -> (CREASTMT || TRANREPT), i.e. app/jcl/POSTTRAN.jcl,
 *  app/jcl/INTCALC.jcl, app/jcl/COMBTRAN.jcl, app/jcl/CREASTMT.JCL and
 *  app/jcl/TRANREPT.jcl — is READ-ONLY reference and is NEVER copied into this
 *  repository. Base package is com.cardemo (decision D-006, deliberately NOT
 *  com.carddemo), matching <groupId>com.cardemo</groupId> in carddemo-java/pom.xml.
 * ============================================================================
 */
package com.cardemo.integration.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.cardemo.batch.jobs.DailyTransactionPostingJob;
import com.cardemo.model.entity.Account;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.TransactionRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
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
 * Integration test for the production {@code cardDemoBatchPipelineJob} — the
 * {@link com.cardemo.batch.jobs.BatchPipelineOrchestrator} that sequences the entire 5-stage batch
 * pipeline. It is the Java/Spring-Batch migration of the legacy JCL/JES job-step chain
 * {@code POSTTRAN -> INTCALC -> COMBTRAN -> (CREASTMT || TRANREPT)}, mapping JCL {@code COND} codes to
 * Spring Batch {@link ExitStatus} + a {@code JobExecutionDecider} + a {@code FlowBuilder.split()}
 * (parallel Stage-4a/4b). Traceability to the frozen mainframe baseline is by commit SHA
 * {@code 27d6c6f} only; no COBOL is copied (AAP §0.7.2).
 *
 * <h2>What this IT proves — ORCHESTRATION semantics, not per-stage correctness</h2>
 * <p>This class is deliberately <strong>narrower</strong> than {@code e2e/BatchPipelineE2ETest} (which
 * exercises holistic scenarios A–F): it isolates the orchestration contract of §0.7.6 with focused
 * failure injection, asserting on {@link JobExecution#getStepExecutions()} names + start/end ordering +
 * status and on the decider's routing effect (which steps ran), plus the presence of the terminal
 * S3/DB artifacts. The internal correctness of each stage is owned by the five per-stage {@code *IT}s.
 * The three orchestration rules pinned here are:</p>
 * <ul>
 *   <li><strong>Sequential dependency chain (§0.7.6 L1067)</strong> — a stage starts only after its
 *       predecessor completes; only the terminal Stage&nbsp;4 (statements&nbsp;||&nbsp;report) runs in
 *       parallel, via {@code FlowBuilder.split()} (§0.7.6 L1073).</li>
 *   <li><strong>PROCEED on {@code COMPLETED_WITH_REJECTS} (§0.7.6, the RC=4 parity)</strong> — a
 *       POSTTRAN partial failure (legacy {@code RETURN-CODE=4}) is success-with-rejects, so the
 *       decider treats it as PROCEED and the downstream chain still runs.</li>
 *   <li><strong>STOP on a POSTTRAN hard failure</strong> — a genuine posting failure routes the decider
 *       to STOP, the job ends {@link BatchStatus#FAILED}, and no downstream stage runs.</li>
 * </ul>
 *
 * <h2>Data source — the DEFAULT S3-staged posting reader</h2>
 * <p>Unlike {@code BatchPipelineE2ETest} (which selects {@code carddemo.batch.daily-transaction.reader
 * = repository}), this IT uses the <strong>default S3-staged reader</strong> (the property is left
 * unset, so {@code DailyTransactionReader}'s {@code @ConditionalOnProperty(matchIfMissing = true)} S3
 * bean is active). POSTTRAN therefore reads a small, hand-crafted 350-byte fixed-width {@code DALYTRAN}
 * file staged into the {@code carddemo-batch-input} S3 bucket. That choice is what makes the Stage-3
 * STOP test possible: staging a <em>malformed</em> object drives the strict reader to fail the posting
 * step deterministically.</p>
 *
 * <h2>Harness contract</h2>
 * <p>Extends {@link AbstractBatchJobIT}, inheriting the singleton PostgreSQL&nbsp;16 + LocalStack
 * containers, the {@code @DynamicPropertySource} wiring (including {@code spring.batch.job.enabled =
 * false} so tests control launch timing), AWS provisioning/teardown, and the
 * {@code launchJob(...)}/{@code uniqueParams(...)}/{@code putObject(...)}/{@code countObjects(...)}/
 * {@code emptyBucket(...)} helpers — none redeclared here. The production job is autowired
 * <strong>by bean name</strong> ({@code cardDemoBatchPipelineJob}) to disambiguate the six {@code Job}
 * beans in the context. The {@code @TestPropertySource} below provisions the Spring Batch metadata
 * tables (Flyway only creates business tables), and {@code SecurityCorsTestConfig} supplies the
 * {@link CorsConfigurationSource} bean the production security graph requires under
 * {@code webEnvironment = NONE}. The {@code *IT} suffix routes this class to {@code maven-failsafe-plugin}
 * under the Maven {@code integration} profile.</p>
 *
 * <h2>Decimal discipline (AAP §0.7.3)</h2>
 * <p>Every monetary value is a {@link BigDecimal}; numeric comparisons use {@code compareTo} semantics
 * (AssertJ {@code isEqualByComparingTo}), never {@code equals}. No {@code float}/{@code double} appears
 * for any field originating from a COBOL {@code PIC} clause.</p>
 *
 * @see com.cardemo.batch.jobs.BatchPipelineOrchestrator
 * @see AbstractBatchJobIT
 * @see DailyTransactionPostingJob
 */
// Create the Spring Batch metadata schema (BATCH_JOB_INSTANCE, BATCH_JOB_EXECUTION, ...) in the
// Testcontainers PostgreSQL: the Flyway migrations provision only business tables, and for a
// non-embedded database `spring.batch.jdbc.initialize-schema` defaults to `embedded` (a no-op). The
// inherited `clearJobRepository()` (@BeforeEach) and every `launchJob(...)` require these tables.
@TestPropertySource(properties = "spring.batch.jdbc.initialize-schema=always")
@Import(BatchPipelineOrchestratorIT.SecurityCorsTestConfig.class)
@DisplayName("BatchPipelineOrchestrator (cardDemoBatchPipelineJob) — sequencing, COND PROCEED/STOP routing, and the Stage-4 parallel split")
class BatchPipelineOrchestratorIT extends AbstractBatchJobIT {

    // -------------------------------------------------------------------------
    // Orchestration constants (mirroring BatchPipelineOrchestrator's wired step bean names and the JCL
    // job-step chain it reproduces). Held as literals so this test depends only on the externally
    // observable launch + step-naming contract, not on production internals beyond the depends_on set.
    // -------------------------------------------------------------------------

    /** Stage&nbsp;1 step bean name (POSTTRAN / CBTRN02C). */
    private static final String STEP_POSTING = "dailyTransactionPostingStep";

    /** Stage&nbsp;2 step bean name (INTCALC / CBACT04C). */
    private static final String STEP_INTEREST = "interestCalculationStep";

    /** Stage&nbsp;3 step bean name (COMBTRAN / DFSORT + IDCAMS REPRO). */
    private static final String STEP_COMBINE = "combineTransactionsStep";

    /** Stage&nbsp;4a step&nbsp;1 bean name (CREASTMT prepare). */
    private static final String STEP_PREPARE_STATEMENTS = "prepareStatementsStep";

    /** Stage&nbsp;4a step&nbsp;2 bean name (CREASTMT / CBSTM03A generate). */
    private static final String STEP_GENERATE_STATEMENTS = "generateStatementsStep";

    /** Stage&nbsp;4b step bean name (TRANREPT / CBTRN03C). */
    private static final String STEP_REPORT = "transactionReportStep";

    // -------------------------------------------------------------------------
    // Job-parameter names + values forwarded to the pipeline (shared by every step in the execution).
    // -------------------------------------------------------------------------

    /**
     * Job-parameter name carrying the staged S3 object key for the POSTTRAN reader, mirroring
     * {@code DailyTransactionReader.OBJECT_KEY_PARAMETER}. Held as a literal (rather than importing the
     * reader, which is outside this file's dependency whitelist) so the test depends only on the
     * externally-observable launch contract.
     */
    private static final String OBJECT_KEY_PARAMETER = "dalytranObjectKey";

    /** INTCALC {@code parmDate} parameter (INTCALC.jcl PARM; CBACT04C run date) — matches the e2e value. */
    private static final String PARM_DATE = "2022071800";

    /** Inclusive lower bound of the TRANREPT processing-date window (TRANREPT.jcl DATEPARM). */
    private static final String START_DATE = "2022-01-01";

    /** Inclusive upper bound of the TRANREPT processing-date window (TRANREPT.jcl DATEPARM). */
    private static final String END_DATE = "2022-07-06";

    // -------------------------------------------------------------------------
    // S3 output side-effect coordinates (asserted in the happy path).
    // -------------------------------------------------------------------------

    /** Key prefix under which CREASTMT (CBSTM03A/CBSTM03B) writes statements into {@code carddemo-statements}. */
    private static final String STATEMENTS_KEY_PREFIX = "statements/";

    /** Key prefix under which TRANREPT (CBTRN03C) writes the report into {@code carddemo-batch-output}. */
    private static final String REPORT_KEY_PREFIX = "tranrept/";

    // -------------------------------------------------------------------------
    // DALYTRAN fixed-width layout constants (CVTRA06Y, RECLN 350) and posting parity values.
    // -------------------------------------------------------------------------

    /** Fixed length of one {@code DALYTRAN} input record ({@code CVTRA06Y}, RECLN 350). */
    private static final int DALYTRAN_RECORD_LENGTH = 350;

    /** Monetary scale of every {@code PIC S9(n)V99} field — two fractional digits (AAP §0.7.3). */
    private static final int MONETARY_SCALE = 2;

    /** Width of the {@code DALYTRAN-AMT S9(09)V99} zoned-decimal field (10 digits + overpunch). */
    private static final int AMOUNT_FIELD_WIDTH = 11;

    /** Width of the 26-character {@code DALYTRAN-ORIG-TS}/{@code DALYTRAN-PROC-TS} timestamp fields. */
    private static final int TIMESTAMP_WIDTH = 26;

    /** A small, comfortably in-limit transaction amount for the all-valid happy-path records. */
    private static final BigDecimal SMALL_VALID_AMOUNT = new BigDecimal("1.00");

    /**
     * A 16-digit card number deliberately absent from the seeded {@code CARDXREF} rows, used to force
     * the {@code CBTRN02C 1500-A-LOOKUP-XREF} miss (reject 100) in the PROCEED-on-rejects scenario.
     */
    private static final String ABSENT_CARD_NUM = "9999999999999999";

    /**
     * 26-character timestamp format of {@code DALYTRAN-ORIG-TS}/{@code DALYTRAN-PROC-TS}
     * ({@code yyyy-MM-dd HH:mm:ss.SSSSSS}), matching {@code DailyTransactionReader.TIMESTAMP_FORMATTER}.
     */
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS", Locale.ROOT);

    /**
     * A curated set of Flyway-V3-seeded {@code CARDXREF} card numbers mapped to their owning
     * {@code ACCTDAT} account ids. Because {@code CardCrossReferenceRepository} is outside this file's
     * dependency whitelist, a valid card&rarr;account pair is selected from these known-seeded constants
     * (verified against {@code app/data/ASCII/cardxref.txt}) and the account is re-read at runtime through
     * the autowired {@link AccountRepository} so the crafted record stays consistent with the live seed.
     * Insertion order is preserved so selection is deterministic when headrooms tie.
     */
    private static final Map<String, Long> SEEDED_CARD_TO_ACCOUNT = seededCardToAccount();

    // -------------------------------------------------------------------------
    // Production beans under test / fixtures — autowired BY NAME from the full context.
    // -------------------------------------------------------------------------

    /** The master orchestrator job under test, resolved BY BEAN NAME (six-{@code Job} ambiguity). */
    @Autowired
    private Job cardDemoBatchPipelineJob;

    /** Account master — read (never written) to pick a max-headroom valid card and its expiry. */
    @Autowired
    private AccountRepository accountRepository;

    /** Transaction master — emptied between tests and asserted for posted-row presence in the happy path. */
    @Autowired
    private TransactionRepository transactionRepository;

    // -------------------------------------------------------------------------
    // Per-test hygiene. The base @BeforeEach clears ONLY the Spring Batch job repository; it does NOT
    // empty the S3 buckets or the business tables. We guarantee an independent starting point here: an
    // empty transaction master (clean counts, no DALYTRAN-ID collisions) and empty input/output/statement
    // buckets (no staged input or reject/posted/statement/report objects leaked from a sibling run).
    // -------------------------------------------------------------------------

    @BeforeEach
    void resetBusinessStateBeforeEachTest() {
        transactionRepository.deleteAll();
        emptyBucket(BATCH_INPUT_BUCKET);
        emptyBucket(BATCH_OUTPUT_BUCKET);
        emptyBucket(STATEMENTS_BUCKET);
    }

    @AfterEach
    void resetBusinessStateAfterEachTest() {
        transactionRepository.deleteAll();
        emptyBucket(BATCH_INPUT_BUCKET);
        emptyBucket(BATCH_OUTPUT_BUCKET);
        emptyBucket(STATEMENTS_BUCKET);
    }

    // =========================================================================
    // Phase 1 — HAPPY PATH: full sequential chain + parallel Stage-4 split.
    // Proves the §0.7.6 sequential-dependency chain (L1067) and the FlowBuilder.split() parallel
    // Stage-4a/4b (L1073): with an all-valid POSTTRAN input the decider routes PROCEED on a clean
    // COMPLETED, every one of the six steps runs in the JCL order, the two Stage-4 flows both run after
    // COMBTRAN, and the terminal CREASTMT/TRANREPT S3 artifacts plus posted DB rows materialise.
    // =========================================================================

    @Test
    @DisplayName("Happy path: all six steps run in JCL order, Stage-4 runs in parallel after COMBTRAN, and terminal S3/DB artifacts appear")
    void allValidInput_runsAllStagesSequentiallyThenParallelSplit_andProducesArtifacts() throws Exception {
        // ----------------------------------------------------------------- Given
        // A small, all-valid DALYTRAN file on a max-headroom seeded card so POSTTRAN posts cleanly with
        // ZERO rejects (exit COMPLETED). Downstream prerequisites (accounts/cards/xref/tcatbal/discgrp)
        // are already provided by the Flyway V3 seed; the transaction master starts empty (per @BeforeEach).
        CardAccount valid = selectMaxHeadroomSeededCard();
        LocalDateTime origTs = beforeExpiry(valid.account());
        String objectKey = stageDalytranFile(List.of(
                dalytranRecord(valid.cardNumber(), SMALL_VALID_AMOUNT, origTs),
                dalytranRecord(valid.cardNumber(), SMALL_VALID_AMOUNT, origTs)));

        // ------------------------------------------------------------------ When
        // Launch the orchestrator with the staged input key plus the date parameters the sub-steps consume.
        JobExecution execution = launchJob(cardDemoBatchPipelineJob, uniqueParams(builder -> builder
                .addString(OBJECT_KEY_PARAMETER, objectKey)
                .addString("parmDate", PARM_DATE)
                .addString("startDate", START_DATE)
                .addString("endDate", END_DATE)));

        // ------------------------------------------------------------------ Then
        // (1) The whole pipeline completed — the decider chose PROCEED and no downstream stage failed.
        assertThat(execution.getStatus())
                .as("Clean POSTTRAN -> decider PROCEED -> full chain completes; step trace = %s", stepNames(execution))
                .isEqualTo(BatchStatus.COMPLETED);

        // (2) POSTTRAN exited the clean COMPLETED branch (no rejects) — the PROCEED trigger for this path.
        StepExecution posting = stepByName(execution, STEP_POSTING);
        assertThat(posting.getExitStatus().getExitCode())
                .as("All-valid input must yield a clean COMPLETED posting exit (no rejects)")
                .isEqualTo(ExitStatus.COMPLETED.getExitCode());

        // (3) Every one of the six pipeline steps executed (none skipped by the decider).
        assertThat(stepNames(execution))
                .as("PROCEED path must run all six pipeline steps")
                .contains(STEP_POSTING, STEP_INTEREST, STEP_COMBINE,
                        STEP_PREPARE_STATEMENTS, STEP_GENERATE_STATEMENTS, STEP_REPORT);

        // (4) Sequential-dependency chain (§0.7.6 L1067): POSTTRAN -> INTCALC -> COMBTRAN, each starting
        // only after its predecessor finished (end-time of N <= start-time of N+1).
        StepExecution interest = stepByName(execution, STEP_INTEREST);
        StepExecution combine = stepByName(execution, STEP_COMBINE);
        assertThat(posting.getEndTime())
                .as("INTCALC must start only after POSTTRAN completes")
                .isBeforeOrEqualTo(interest.getStartTime());
        assertThat(interest.getEndTime())
                .as("COMBTRAN must start only after INTCALC completes")
                .isBeforeOrEqualTo(combine.getStartTime());

        // (5) Parallel Stage-4 split (§0.7.6 L1073): the statement flow (prepare -> generate) and the
        // report flow both START only after COMBTRAN completes, and both reach COMPLETED. We do NOT assert
        // a fixed finish order between the two flows — they run on a bounded TaskExecutor, not synchronously.
        StepExecution prepare = stepByName(execution, STEP_PREPARE_STATEMENTS);
        StepExecution generate = stepByName(execution, STEP_GENERATE_STATEMENTS);
        StepExecution report = stepByName(execution, STEP_REPORT);
        assertThat(combine.getEndTime())
                .as("Statement flow (Stage-4a) must start only after COMBTRAN completes")
                .isBeforeOrEqualTo(prepare.getStartTime());
        assertThat(combine.getEndTime())
                .as("Report flow (Stage-4b) must start only after COMBTRAN completes")
                .isBeforeOrEqualTo(report.getStartTime());
        // Within the statement flow, prepare precedes generate (intra-flow sequencing).
        assertThat(prepare.getEndTime())
                .as("generateStatementsStep must start only after prepareStatementsStep completes")
                .isBeforeOrEqualTo(generate.getStartTime());
        // Both parallel flows reached a COMPLETED terminal step.
        assertThat(generate.getStatus())
                .as("Statement flow (Stage-4a) must complete")
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(report.getStatus())
                .as("Report flow (Stage-4b) must complete")
                .isEqualTo(BatchStatus.COMPLETED);

        // (6) Terminal side effects: CREASTMT statements in carddemo-statements, a TRANREPT report in
        // carddemo-batch-output, and the cleanly-posted rows in the transaction master.
        assertThat(countObjects(STATEMENTS_BUCKET, STATEMENTS_KEY_PREFIX))
                .as("CREASTMT must write at least one statement object under '%s'", STATEMENTS_KEY_PREFIX)
                .isGreaterThanOrEqualTo(1);
        assertThat(countObjects(BATCH_OUTPUT_BUCKET, REPORT_KEY_PREFIX))
                .as("TRANREPT must write a report object under '%s'", REPORT_KEY_PREFIX)
                .isGreaterThanOrEqualTo(1);
        assertThat(transactionRepository.count())
                .as("Clean posting must persist the valid DALYTRAN records to the transaction master")
                .isGreaterThanOrEqualTo(1L);
    }

    // =========================================================================
    // Phase 2 — PROCEED on COMPLETED_WITH_REJECTS (the key COND/RC=4 parity).
    // Proves the §0.7.6 rule that a POSTTRAN PARTIAL failure (legacy RETURN-CODE=4) is success-with-
    // rejects: RejectCountStepListener sets exit COMPLETED_WITH_REJECTS, the postingDecider's PROCEED set
    // {COMPLETED, COMPLETED_WITH_REJECTS} routes PROCEED, and the downstream chain STILL RUNS to a
    // COMPLETED job — distinguishing a recoverable reject from a hard failure (Phase 3).
    // =========================================================================

    @Test
    @DisplayName("Rejects proceed: COMPLETED_WITH_REJECTS posting still routes PROCEED, the full chain runs, and the job completes")
    void postingWithRejects_routesProceed_andRunsDownstreamToCompletion() throws Exception {
        // ----------------------------------------------------------------- Given
        // A MIXED DALYTRAN file: one valid record (posts cleanly) plus one record on a card absent from
        // CARDXREF, which CBTRN02C's xref lookup rejects (reject code 100). That single guaranteed reject
        // makes the posting step finish COMPLETED with rejectCount > 0 -> exit COMPLETED_WITH_REJECTS.
        CardAccount valid = selectMaxHeadroomSeededCard();
        LocalDateTime origTs = beforeExpiry(valid.account());
        String objectKey = stageDalytranFile(List.of(
                dalytranRecord(valid.cardNumber(), SMALL_VALID_AMOUNT, origTs),
                dalytranRecord(ABSENT_CARD_NUM, SMALL_VALID_AMOUNT, origTs)));

        // ------------------------------------------------------------------ When
        JobExecution execution = launchJob(cardDemoBatchPipelineJob, uniqueParams(builder -> builder
                .addString(OBJECT_KEY_PARAMETER, objectKey)
                .addString("parmDate", PARM_DATE)
                .addString("startDate", START_DATE)
                .addString("endDate", END_DATE)));

        // ------------------------------------------------------------------ Then
        // (1) POSTTRAN exited COMPLETED_WITH_REJECTS — the RC=4 parity signal that drives PROCEED.
        StepExecution posting = stepByName(execution, STEP_POSTING);
        assertThat(posting.getStatus())
                .as("A reject is not a step failure: the posting step itself completes")
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(posting.getExitStatus().getExitCode())
                .as("rejectCount > 0 with a COMPLETED step must surface the COMPLETED_WITH_REJECTS exit")
                .isEqualTo(DailyTransactionPostingJob.EXIT_CODE_COMPLETED_WITH_REJECTS);
        assertThat(rejectCountOf(posting))
                .as("At least the absent-card record must be rejected")
                .isGreaterThanOrEqualTo(1L);

        // (2) The decider routed PROCEED — proven by the downstream steps having EXECUTED. This is the
        // exact §0.7.6 RC=4 parity: a partial POSTTRAN failure does NOT stop the pipeline.
        assertThat(ranStep(execution, STEP_INTEREST))
                .as("COMPLETED_WITH_REJECTS must route PROCEED, so INTCALC must run")
                .isTrue();
        assertThat(stepNames(execution))
                .as("PROCEED on rejects must run the entire downstream chain")
                .contains(STEP_INTEREST, STEP_COMBINE,
                        STEP_PREPARE_STATEMENTS, STEP_GENERATE_STATEMENTS, STEP_REPORT);

        // (3) Sequencing still holds, and the overall job completes despite the reject.
        StepExecution interest = stepByName(execution, STEP_INTEREST);
        assertThat(posting.getEndTime())
                .as("INTCALC must start only after the (reject-bearing) POSTTRAN completes")
                .isBeforeOrEqualTo(interest.getStartTime());
        assertThat(execution.getStatus())
                .as("A POSTTRAN partial failure (rejects) must still yield a COMPLETED pipeline; trace = %s",
                        stepNames(execution))
                .isEqualTo(BatchStatus.COMPLETED);
    }

    // =========================================================================
    // Phase 3 — STOP on a POSTTRAN HARD failure.
    // Proves the COND STOP path and the sequential-dependency guarantee: a genuine posting failure (the
    // strict S3 reader rejecting a malformed object) yields a non-COMPLETED* exit, the postingDecider
    // routes STOP -> fail(), the job ends FAILED, and NO downstream stage runs. This is the unambiguous
    // counterpart to Phase 2 — a hard failure stops the pipeline where a reject would have proceeded.
    // =========================================================================

    @Test
    @DisplayName("Hard failure stops: a malformed POSTTRAN input fails the step, the decider routes STOP, the job fails, and no downstream stage runs")
    void postingHardFailure_routesStop_andFailsJobWithoutRunningDownstream() throws Exception {
        // ----------------------------------------------------------------- Given
        // A MALFORMED input object: a single line that is NOT the fixed 350-byte width. The strict
        // FixedLengthTokenizer in the default S3 posting reader raises a FlatFileParseException, and
        // because the posting step has NO skip policy the step FAILS outright (distinct from a reject).
        String objectKey = stageRawObject("THIS-IS-NOT-A-350-BYTE-FIXED-WIDTH-DALYTRAN-RECORD");

        // ------------------------------------------------------------------ When
        // launchJob returns the FAILED JobExecution (a step/flow failure does not raise from the launcher).
        JobExecution execution = launchJob(cardDemoBatchPipelineJob, uniqueParams(builder -> builder
                .addString(OBJECT_KEY_PARAMETER, objectKey)
                .addString("parmDate", PARM_DATE)
                .addString("startDate", START_DATE)
                .addString("endDate", END_DATE)));

        // ------------------------------------------------------------------ Then
        // (1) The overall job FAILED — the decider's STOP branch calls fail().
        assertThat(execution.getStatus())
                .as("Hard POSTTRAN failure -> decider STOP -> fail(); step trace = %s", stepNames(execution))
                .isEqualTo(BatchStatus.FAILED);

        // (2) POSTTRAN actually ran and FAILED (a non-COMPLETED* exit) — the STOP trigger.
        StepExecution posting = stepByName(execution, STEP_POSTING);
        assertThat(posting.getStatus())
                .as("The malformed input must FAIL the posting step (strict reader, no skip policy)")
                .isEqualTo(BatchStatus.FAILED);
        assertThat(posting.getExitStatus().getExitCode())
                .as("A failed posting step must NOT report a PROCEED exit code")
                .isNotEqualTo(ExitStatus.COMPLETED.getExitCode())
                .isNotEqualTo(DailyTransactionPostingJob.EXIT_CODE_COMPLETED_WITH_REJECTS);

        // (3) Sequential-dependency guarantee: because the decider routed STOP, NONE of the downstream
        // stages ran. Their absence from getStepExecutions() proves the COND STOP path short-circuited
        // the chain before INTCALC.
        assertThat(ranStep(execution, STEP_INTEREST))
                .as("STOP must short-circuit before INTCALC; trace = %s", stepNames(execution))
                .isFalse();
        assertThat(ranStep(execution, STEP_COMBINE)).as("STOP must prevent COMBTRAN").isFalse();
        assertThat(ranStep(execution, STEP_PREPARE_STATEMENTS)).as("STOP must prevent CREASTMT prepare").isFalse();
        assertThat(ranStep(execution, STEP_GENERATE_STATEMENTS)).as("STOP must prevent CREASTMT generate").isFalse();
        assertThat(ranStep(execution, STEP_REPORT)).as("STOP must prevent TRANREPT").isFalse();
        assertThat(stepNames(execution))
                .as("Only the failed posting step should appear in the execution trace")
                .containsExactly(STEP_POSTING);
    }

    // =========================================================================
    // Valid card/account selection (whitelist-safe).
    // CardCrossReferenceRepository is outside this file's dependency whitelist, so a known-seeded
    // card->account pair is chosen from constants verified against app/data/ASCII/cardxref.txt and the
    // owning account is re-read through the autowired AccountRepository. Selecting the MAX-headroom account
    // guarantees a small in-limit amount cannot trip the over-limit (reject 102) branch.
    // =========================================================================

    /** A seeded card number paired with its owning, live-read {@link Account}. */
    private record CardAccount(String cardNumber, Account account) {
    }

    /**
     * Selects the seeded card&rarr;account pair with the greatest credit headroom, where
     * {@code headroom = ACCT-CREDIT-LIMIT - (ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT)} — the exact quantity
     * {@code CBTRN02C} compares against the post-transaction balance. Accounts missing any field required for
     * the headroom/expiry computation are skipped.
     *
     * @return the maximum-headroom {@link CardAccount}; fails the test if the seed exposes none
     */
    private CardAccount selectMaxHeadroomSeededCard() {
        CardAccount best = null;
        BigDecimal bestHeadroom = null;
        for (Map.Entry<String, Long> candidate : SEEDED_CARD_TO_ACCOUNT.entrySet()) {
            Optional<Account> maybeAccount = accountRepository.findById(candidate.getValue());
            if (maybeAccount.isEmpty()) {
                continue;
            }
            Account account = maybeAccount.get();
            if (account.getAcctCreditLimit() == null
                    || account.getAcctCurrCycCredit() == null
                    || account.getAcctCurrCycDebit() == null
                    || account.getAcctExpirationDate() == null) {
                continue;
            }
            BigDecimal headroom = account.getAcctCreditLimit()
                    .subtract(account.getAcctCurrCycCredit().subtract(account.getAcctCurrCycDebit()));
            if (bestHeadroom == null || headroom.compareTo(bestHeadroom) > 0) {
                bestHeadroom = headroom;
                best = new CardAccount(candidate.getKey(), account);
            }
        }
        if (best == null) {
            throw new IllegalStateException(
                    "No seeded card->account pair resolved against the live ACCTDAT seed (V3__seed_data.sql)");
        }
        return best;
    }

    /**
     * @param account the owning account
     * @return an {@code ORIG-TS} one day before the account's expiry (at NOON) — guaranteed not-expired so the
     *         posting step cannot raise reject 103 (expired card)
     */
    private static LocalDateTime beforeExpiry(final Account account) {
        return LocalDateTime.of(account.getAcctExpirationDate().minusDays(1), LocalTime.NOON);
    }

    /**
     * @return the immutable, insertion-ordered map of Flyway-V3-seeded {@code CARDXREF} card numbers to their
     *         owning {@code ACCTDAT} account ids (verified byte-for-byte against {@code cardxref.txt})
     */
    private static Map<String, Long> seededCardToAccount() {
        Map<String, Long> seeded = new LinkedHashMap<>();
        seeded.put("0500024453765740", 50L);
        seeded.put("0923877193247330", 2L);
        seeded.put("0683586198171516", 27L);
        seeded.put("0927987108636232", 20L);
        seeded.put("0982496213629795", 12L);
        seeded.put("1014086565224350", 44L);
        return java.util.Collections.unmodifiableMap(seeded);
    }

    // =========================================================================
    // DALYTRAN fixed-width record builder + formatters (parity with DailyTransactionReader's strict
    // FixedLengthTokenizer; mirrors the per-stage DailyTransactionPostingJobIT builder). Every non-varying
    // field carries a valid, in-range default so the only reject driver is the explicitly-chosen card number.
    // =========================================================================

    /** Monotonic per-instance sequence backing unique 16-char DALYTRAN/transaction ids within a test. */
    private long recordSequence;

    /**
     * @return the next unique 16-character {@code DALYTRAN-ID} for this test instance
     *         ({@code "ORCHTXN" + 9 zero-padded digits} = 16 chars)
     */
    private String nextDalytranId() {
        return "ORCHTXN" + numeric(++recordSequence, 9);
    }

    /**
     * Assembles one exactly-350-character {@code DALYTRAN} record (1-indexed, inclusive ranges) matching
     * {@code CVTRA06Y} and the production reader's tokenizer. All fields other than the three parameters use
     * valid, in-range defaults (type {@code 01}, category {@code 0001}, etc.) so the record posts cleanly
     * unless its card number is absent from {@code CARDXREF}.
     *
     * @param cardNumber     the {@code DALYTRAN-CARD-NUM} (a seeded card to post, or {@link #ABSENT_CARD_NUM}
     *                       to force reject 100)
     * @param amount         the {@code DALYTRAN-AMT} (non-negative; small enough to stay in-limit)
     * @param origTimestamp  the {@code DALYTRAN-ORIG-TS}
     * @return the assembled 350-character fixed-width record
     */
    private String dalytranRecord(final String cardNumber, final BigDecimal amount,
            final LocalDateTime origTimestamp) {
        final StringBuilder sb = new StringBuilder(DALYTRAN_RECORD_LENGTH);
        sb.append(alpha(nextDalytranId(), 16));                  // 1-16   DALYTRAN-ID
        sb.append(alpha("01", 2));                               // 17-18  DALYTRAN-TYPE-CD
        sb.append(numeric(1L, 4));                               // 19-22  DALYTRAN-CAT-CD
        sb.append(alpha("POS", 10));                             // 23-32  DALYTRAN-SOURCE
        sb.append(alpha("ORCHESTRATION PARITY TXN", 100));       // 33-132 DALYTRAN-DESC
        sb.append(encodeAmount(amount));                         // 133-143 DALYTRAN-AMT (11)
        sb.append(numeric(123_456_789L, 9));                     // 144-152 DALYTRAN-MERCHANT-ID
        sb.append(alpha("TEST MERCHANT", 50));                   // 153-202 DALYTRAN-MERCHANT-NAME
        sb.append(alpha("TEST CITY", 50));                       // 203-252 DALYTRAN-MERCHANT-CITY
        sb.append(alpha("00000", 10));                           // 253-262 DALYTRAN-MERCHANT-ZIP
        sb.append(alpha(cardNumber, 16));                        // 263-278 DALYTRAN-CARD-NUM
        sb.append(formatTimestamp(origTimestamp));               // 279-304 DALYTRAN-ORIG-TS (26)
        sb.append(blanks(TIMESTAMP_WIDTH));                      // 305-330 DALYTRAN-PROC-TS (blank -> null)
        sb.append(blanks(20));                                   // 331-350 FILLER
        final String record = sb.toString();
        if (record.length() != DALYTRAN_RECORD_LENGTH) {
            throw new IllegalStateException("Assembled DALYTRAN record length " + record.length()
                    + " != " + DALYTRAN_RECORD_LENGTH + " — field widths drifted");
        }
        return record;
    }

    /** Left-justifies an alphanumeric value, space-padding (or truncating) to {@code width}. */
    private static String alpha(final String value, final int width) {
        final String v = (value == null) ? "" : value;
        if (v.length() >= width) {
            return v.substring(0, width);
        }
        return v + blanks(width - v.length());
    }

    /** Right-justifies a non-negative integer, zero-padding to {@code width}. */
    private static String numeric(final long value, final int width) {
        final String formatted = String.format(Locale.ROOT, "%0" + width + "d", value);
        if (formatted.length() != width) {
            throw new IllegalStateException("numeric value " + value + " overflows width " + width);
        }
        return formatted;
    }

    /**
     * Encodes a non-negative {@link BigDecimal} into the 11-byte {@code S9(09)V99} zoned-decimal field. For a
     * positive amount the reader treats {@code '0'..'9'} in the trailing byte as positive, so the field is the
     * unscaled value ({@code amount * 100}) right-justified, zero-padded to 11 digits.
     */
    private static String encodeAmount(final BigDecimal amount) {
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("encodeAmount supports non-negative amounts only: " + amount);
        }
        final long unscaled = amount.setScale(MONETARY_SCALE, RoundingMode.UNNECESSARY)
                .movePointRight(MONETARY_SCALE)
                .longValueExact();
        final String encoded = String.format(Locale.ROOT, "%0" + AMOUNT_FIELD_WIDTH + "d", unscaled);
        if (encoded.length() != AMOUNT_FIELD_WIDTH) {
            throw new IllegalStateException("DALYTRAN-AMT " + amount + " overflows the S9(09)V99 11-byte field");
        }
        return encoded;
    }

    /** Formats a timestamp to the fixed 26-character {@code yyyy-MM-dd HH:mm:ss.SSSSSS} layout. */
    private static String formatTimestamp(final LocalDateTime timestamp) {
        final String formatted = timestamp.format(TIMESTAMP_FORMATTER);
        if (formatted.length() != TIMESTAMP_WIDTH) {
            throw new IllegalStateException("timestamp '" + formatted + "' is not " + TIMESTAMP_WIDTH + " chars");
        }
        return formatted;
    }

    /** @return a run of {@code count} ASCII spaces. */
    private static String blanks(final int count) {
        return " ".repeat(count);
    }

    // =========================================================================
    // S3 staging / launch-effect helpers.
    // =========================================================================

    /**
     * Stages the given fixed-width records as one newline-joined object in {@code carddemo-batch-input} under a
     * unique key and returns that key. The body is written as ISO-8859-1 bytes (byte-identical to the reader's
     * encoding) so the strict 350-byte tokenizer reads each line verbatim.
     *
     * @param records one or more exactly-350-character records
     * @return the staged object key (pass as the {@code dalytranObjectKey} job parameter)
     */
    private String stageDalytranFile(final List<String> records) {
        final String body = String.join("\n", records);
        final String key = "it/dalytran-" + UUID.randomUUID() + ".txt";
        putObject(BATCH_INPUT_BUCKET, key, body.getBytes(StandardCharsets.ISO_8859_1));
        return key;
    }

    /**
     * Stages a raw, deliberately MALFORMED object (a line that is not the fixed 350-byte width) in
     * {@code carddemo-batch-input}. The strict {@code FixedLengthTokenizer} raises a {@code FlatFileParseException}
     * on read and — because the posting step has no skip policy — the step FAILS, driving the decider STOP path.
     *
     * @param malformedBody the malformed body to stage (not 350 bytes per line)
     * @return the staged object key (pass as the {@code dalytranObjectKey} job parameter)
     */
    private String stageRawObject(final String malformedBody) {
        final String key = "it/dalytran-malformed-" + UUID.randomUUID() + ".txt";
        putObject(BATCH_INPUT_BUCKET, key, malformedBody.getBytes(StandardCharsets.ISO_8859_1));
        return key;
    }

    /**
     * @param stepExecution a completed step execution
     * @return the reject tally the posting listener published under {@link DailyTransactionPostingJob#REJECT_COUNT_KEY}
     *         (0 if the key is absent), read as a long to match the listener's {@code putLong}
     */
    private static long rejectCountOf(final StepExecution stepExecution) {
        return stepExecution.getExecutionContext().getLong(DailyTransactionPostingJob.REJECT_COUNT_KEY, 0L);
    }

    // =========================================================================
    // Step-execution trace helpers (the base class intentionally exposes none).
    // =========================================================================

    /**
     * @param execution the job execution to inspect
     * @param stepName  the step bean name to locate
     * @return the {@link StepExecution} with the given name
     * @throws AssertionError if no step with that name ran (message lists the steps that did)
     */
    private static StepExecution stepByName(final JobExecution execution, final String stepName) {
        return execution.getStepExecutions().stream()
                .filter(step -> stepName.equals(step.getStepName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected a step execution named '" + stepName
                        + "' but found: " + stepNames(execution)));
    }

    /**
     * @param execution the job execution to inspect
     * @param stepName  the step bean name to test
     * @return {@code true} iff a step with the given name appears in the execution trace
     */
    private static boolean ranStep(final JobExecution execution, final String stepName) {
        return execution.getStepExecutions().stream().anyMatch(step -> stepName.equals(step.getStepName()));
    }

    /**
     * @param execution the job execution to inspect
     * @return the names of every step that ran, in iteration order (for diagnostic assertion messages)
     */
    private static List<String> stepNames(final JobExecution execution) {
        final List<String> names = new ArrayList<>();
        for (final StepExecution step : execution.getStepExecutions()) {
            names.add(step.getStepName());
        }
        return names;
    }

    // =========================================================================
    // Test-only configuration. The production security graph (imported by the full @SpringBootTest context)
    // requires a CorsConfigurationSource bean; under webEnvironment = NONE no web auto-configuration supplies
    // one, so this @TestConfiguration provides an empty (no-mappings) source — mirroring every sibling batch IT.
    // =========================================================================

    @TestConfiguration(proxyBeanMethods = false)
    static class SecurityCorsTestConfig {

        /**
         * @return an empty CORS source (no mappings) satisfying the security filter chain's CORS DSL under the
         *         non-web batch test context
         */
        @Bean
        CorsConfigurationSource corsConfigurationSource() {
            return new UrlBasedCorsConfigurationSource();
        }
    }
}

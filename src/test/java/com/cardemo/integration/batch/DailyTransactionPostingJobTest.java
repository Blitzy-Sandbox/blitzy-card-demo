/*
 * ******************************************************************
 * Component   : DailyTransactionPostingJobTest.java
 * Application : CardDemo
 * Type        : JUnit 5 integration test (Spring Boot 3.5.11,
 *               Testcontainers 2.0.3, PostgreSQL 16, LocalStack)
 * Function    : Proves the migrated posting job reproduces POSTTRAN
 *               where only an assembled job over a real database and
 *               object store can show it: the read-only CBTRN01C
 *               pre-flight step that precedes the posting step, the
 *               exit-status contract in which return code 4 is set if
 *               and only if the reject count exceeds zero, the
 *               atomicity of the three writes the source committed
 *               separately, and the 430-byte reject record whose
 *               80-byte trailer carries a four-digit reason and a
 *               76-character description.
 * Source      : app/jcl/POSTTRAN.jcl, app/cbl/CBTRN02C.cbl,
 *               app/cbl/CBTRN01C.cbl, app/cpy/CVTRA06Y.cpy,
 *               app/cpy/CVTRA05Y.cpy, app/cpy/CVTRA01Y.cpy,
 *               app/cpy/CVACT01Y.cpy @ 7756d89
 * ******************************************************************
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
 * language governing permissions and limitations under the License
 * ******************************************************************
 */
package com.cardemo.integration.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.flow.JobExecutionDecider;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.StepLocator;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.cardemo.batch.jobs.DailyTransactionPostingJob;
import com.cardemo.e2e.PostingParityOracle;
import com.cardemo.batch.writers.RejectWriter;
import com.cardemo.model.entity.Transaction;
import com.cardemo.model.enums.FileStatus;
import com.cardemo.model.enums.RejectCode;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.DailyTransactionRepository;
import com.cardemo.repository.TransactionCategoryBalanceRepository;
import com.cardemo.repository.TransactionRepository;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

/**
 * Integration test for the assembled daily transaction posting job.
 *
 * <h2>What it does</h2>
 *
 * <p>Every assertion is a property of the <em>assembled</em> topology - the job, its two steps, reader,
 * processor and writers - measured against PostgreSQL 16 and LocalStack. The source contract is the
 * {@code STEP15 EXEC PGM=CBTRN02C} at {@code app/jcl/POSTTRAN.jcl:23}, its 430-byte
 * {@code DALYREJS} allocation at {@code :36}, and these verified program boundaries:
 * {@code app/cbl/CBTRN02C.cbl:176-182}, {@code :202-234}, {@code :230}, {@code :370-422},
 * {@code :424-465}, {@code :467-500}, {@code :556}, {@code :707-711} and {@code :714-727}.
 *
 * <ul>
 *   <li><strong>Read-only pre-flight.</strong> {@code app/cbl/CBTRN01C.cbl} has six {@code SELECT}s and only
 *       {@code OPEN}, {@code READ}, {@code CLOSE} and {@code DISPLAY} verbs. It is the labelled first step,
 *       performs zero domain writes, and is not promoted into an invented seventh job.</li>
 *   <li><strong>Return code and reachability.</strong> {@code CBTRN02C:229-231} sets return code 4 if and only
 *       if rejects exist. The shipped fixtures can reach code 102 only, so the run completes with rejects,
 *       while a synthetic row pins the sequential overwrite in which code 103 replaces code 102.</li>
 *   <li><strong>Fixed-width output.</strong> Every reject record is the 350-byte input image followed by a
 *       four-character code and a 76-character description, exactly 430 bytes in all. Posted rows retain the
 *       26-character processing timestamp whose last four characters are literal zeros.</li>
 *   <li><strong>Stateful posting.</strong> Category balances are incremented rather than replaced, and a
 *       negative transaction is added to the debit accumulator without absolute-value normalisation.</li>
 *   <li><strong>Atomicity - a labelled deviation, not parity.</strong> The source commits its category,
 *       account and transaction writes independently. Java deliberately makes them one transaction, so a
 *       simulated account rewrite failure leaves neither the earlier category change nor a transaction row.</li>
 * </ul>
 *
 * <p>One evidence gap is stated rather than invented. File status {@code '35'} is
 * <strong>Not available</strong>: it occurs nowhere in the frozen COBOL corpus, so a faithful test would
 * require a source occurrence or a captured legacy run that exercises an unavailable dataset.
 *
 * <p><strong>The exact reject count IS asserted.</strong> Declining it on the ground that "the
 * count is model-sensitive fixture data" would be wrong: {@code 2800-UPDATE-ACCOUNT-REC} ends in
 * {@code REWRITE FD-ACCTFILE-REC} at {@code app/cbl/CBTRN02C.cbl:561} and a VSAM {@code REWRITE} replaces the
 * record in the cluster, so the re-read at {@code :394} returns the mutated accumulators and the stateless
 * reading is a misreading rather than a second model. Exactly one faithful model exists,
 * {@code com.cardemo.e2e.PostingParityOracle} re-derives it from the frozen source and fixtures without
 * importing any production type, and its result is committed under
 * {@code src/test/resources/expected/posttran}. The count asserted below is read from that committed
 * expectation rather than restated here, so this class cannot disagree with the Gate 1 suites about it. A
 * captured legacy 430-byte {@code DALYREJS} generation with its resulting images remains
 * <strong>Not available</strong> and would corroborate that reading against the real runtime.
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>{@code ./mvnw clean verify}. Failsafe collects this tier at {@code integration-test} and asserts it at
 * {@code verify}; Surefire is bound to {@code src/test/java/com/cardemo/unit/**} and excludes this tree, so
 * moving or renaming this class would leave it collected by neither plugin and silently never run. A container
 * runtime with an accessible socket is a prerequisite.
 *
 * <h2>Key configurations and defaults</h2>
 *
 * <ul>
 *   <li>The {@code test} profile is active; the three Flyway migrations own the schema and the seed, and
 *       {@code spring.batch.job.enabled} is {@code false} so nothing launches on refresh.</li>
 *   <li>The harness starts PostgreSQL 16 from immutable image digest
 *       {@code postgres@sha256:33f923b05f64ca54ac4401c01126a6b92afe839a0aa0a52bc5aeb5cc958e5f20}
 *       and LocalStack from {@code localstack/localstack:4.14.0}; no live service is reachable.</li>
 *   <li>Time comes from the parent's fixed UTC {@code java.time.Clock} bean, so the generated
 *       26-character timestamps are reproducible.</li>
 *   <li>The job name defaults to {@code POSTTRAN} and the chunk size to
 *       {@code carddemo.batch.posttran.chunk-size}.</li>
 *   <li>The seeded input is the three hundred staged rows of {@code app/data/ASCII/dailytran.txt}, which
 *       carry both positive and negative overpunch signs and therefore exercise the cycle-debit branch.</li>
 * </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <dl>
 *   <dt>Container startup fails, or the whole tier is skipped</dt>
 *   <dd>No container runtime is reachable. Start the daemon and re-run.</dd>
 *
 *   <dt>{@code Could not resolve dependencies} for a Testcontainers module at version 2.0.3</dt>
 *   <dd><strong>Blocker.</strong> The 2.x modules use the prefixed artefact names. Keep the managed-version
 *       property override and the prefixed coordinates together; importing a second bill of materials or
 *       reverting to a bare module name is not a remedy.</dd>
 *
 *   <dt>{@code warnings found and -Werror specified}, or an unused import found in review</dt>
 *   <dd>Remove the warning or import. Do not suppress the compiler gate and do not retain speculative
 *       dependencies.</dd>
 *
 *   <dt>The posting query returns no rows although the fixture is populated</dt>
 *   <dd>A wall-clock API replaced the injected fixed clock and moved the run outside its deterministic
 *       business instant. Restore constructor-injected clock use; do not widen the query window.</dd>
 *
 *   <dt>{@code Existing transaction detected in JobRepository}</dt>
 *   <dd>A launching method lost its {@code @Transactional(propagation = Propagation.NOT_SUPPORTED)}. Every
 *       method here that launches carries it.</dd>
 *
 *   <dt>The exit code is {@code COMPLETED} when rejects were counted, or vice versa</dt>
 *   <dd>The decider stopped keying on the reject count. Return code 4 is set if and only if that count
 *       exceeds zero, and no other condition may influence it.</dd>
 *
 *   <dt>The posted count and the inserted row count disagree</dt>
 *   <dd>The three writes are no longer in one unit of work, so a partial posting committed. That is the
 *       orphaned-row hazard the single transaction boundary exists to remove.</dd>
 * </dl>
 *
 * <h2>Finding severity and remediation</h2>
 *
 * <dl>
 *   <dt>Blocker - wrong Testcontainers version mechanism or module coordinates</dt>
 *   <dd>Restore the one managed-version property override and all four prefixed test coordinates.</dd>
 *   <dt>High - a pre-flight write, lost cause, or partial posting survives</dt>
 *   <dd>Keep the pre-flight business transaction read-only, preserve the store failure as the translated
 *       exception's cause, and keep all three posting writes inside one transaction.</dd>
 *   <dt>Medium - an exact reject total is treated as an oracle</dt>
 *   <dd>Remove the total and assert invariant accounting plus per-record classification until a legacy
 *       baseline becomes available.</dd>
 *   <dt>Low - assertion text omits its source locator</dt>
 *   <dd>Add the exact file, paragraph or symbol so a failure identifies the contract it protects.</dd>
 * </dl>
 *
 * <h2>Thread safety and state</h2>
 *
 * <p>Not thread safe and not required to be: one instance per test method, no shared mutable state, no second
 * thread and no second connection. <strong>This class declares no {@code static} field of any kind</strong> -
 * the parent permits exactly two in this package and both are its containers - so every constant below is an
 * immutable instance field initialised at its declaration.
 */
@DisplayName("Daily transaction posting job: pre-flight, reject geometry and classification, stateful posting "
        + "and atomicity")
class DailyTransactionPostingJobTest extends AbstractBatchIntegrationTest {

    /** The assembled job under test, injected by the bean name its configuration registers. */
    @Autowired
    @Qualifier(DailyTransactionPostingJob.JOB_BEAN_NAME)
    private Job dailyTransactionPostingJob;

    /** Resolves the step beans by name and proves the decider is not a bean. */
    @Autowired
    private ApplicationContext applicationContext;

    /** Counts the staged input rows the job consumes. */
    @Autowired
    private DailyTransactionRepository dailyTransactionRepository;

    /**
     * Reads the committed posted rows and their fixed-width processing timestamps, and supplies the one
     * controlled write failure that lets a chunk roll back after the end-of-run counters were bumped.
     *
     * <p>A spy rather than a replacement, exactly as {@link #accountRepository} is: every other test in this
     * class still drives the real Spring Data repository, and the framework's after-method reset removes any
     * stub before the next test without rebuilding the application context.
     */
    @MockitoSpyBean
    private TransactionRepository transactionRepository;

    /** Reads the category-balance relation before and after each posting run. */
    @Autowired
    private TransactionCategoryBalanceRepository transactionCategoryBalanceRepository;

    /**
     * Reads account accumulators and supplies the one controlled account-rewrite failure.
     *
     * <p>The Spring Framework override is a spy rather than a replacement, so every ordinary test still drives
     * the real Spring Data repository. Its default after-method reset removes the failure stub before the next
     * test without replacing the application context.
     */
    @MockitoSpyBean
    private AccountRepository accountRepository;

    /** Reads the concrete DALYREJS generation key back from the LocalStack object store. */
    @Autowired
    private S3Client s3Client;

    /** The configured output bucket, resolved from the same property the production writer consumes. */
    @Value("${carddemo.aws.s3.batch-output-bucket}")
    private String batchOutputBucket;

    /**
     * Reads cross-relation aggregates that no single repository can express without duplicating production
     * finders.
     *
     * <p>Every statement is a fixed, parameter-free read. <strong>Writes must not go through this
     * object.</strong> The pool has auto-commit disabled, so an out-of-transaction write can report an affected
     * row count and then roll back silently when the connection returns to the pool.
     */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** The two step names, in the order {@code app/jcl/POSTTRAN.jcl} implies. */
    private final List<String> stepNamesInSourceOrder = List.of(
            DailyTransactionPostingJob.PRE_FLIGHT_STEP_BEAN_NAME,
            DailyTransactionPostingJob.POSTING_STEP_BEAN_NAME);

    /** The job name, the member name of {@code app/jcl/POSTTRAN.jcl}. */
    private final String jobName = "POSTTRAN";

    /** The completed-with-rejects exit code, which return code 4 maps onto. */
    private final String exitCodeCompletedWithRejects = "COMPLETED WITH REJECTS";

    /**
     * The largest parameter count the job's validator accepts.
     *
     * <p>One more than this is refused. The figure is deliberately restated here rather than read from the
     * production constant, which is private: the bound is a contract, and a test that read it from the class
     * under test would pass whatever the class happened to say.
     */
    private final int maxAcceptedJobParameters = 16;

    /** Rows in {@code app/data/ASCII/dailytran.txt}, and therefore the staged input population. */
    private final long seededDailyTransactionCount = 300L;

    /** A deterministic ingest ordinal outside the 1 through 300 seed range. */
    private final long syntheticIngestSequence = 900_001L;

    /** A non-sensitive, exact-width identifier used to find the synthetic reject record. */
    private final String syntheticTransactionId = "SYNTHETIC-103001";

    /** An in-range amount that exceeds every seeded credit limit without overflowing WS-TEMP-BAL. */
    private final BigDecimal syntheticOverLimitAmount = new BigDecimal("9000000.00");

    /** A 26-character originating timestamp later than every seeded account expiry. */
    private final String syntheticExpiredOriginTimestamp = "2099-12-31-00.00.00.000000";

    /** The source's date, time and hundredths fields before its four literal trailing zeros. */
    private final DateTimeFormatter processingTimestampFormat =
            DateTimeFormatter.ofPattern("uuuu-MM-dd-HH.mm.ss.SS", Locale.ROOT);

    /**
     * The reserved column name the transaction table carries, quoted exactly as the migration emits it.
     *
     * <p>{@code transaction} is a reserved word, so the migration emits it quoted in lower case. Any
     * native query must quote it identically or the statement does not parse.
     */
    private final String countCommittedTransactions = "SELECT COUNT(*) FROM \"transaction\"";

    /** Groups each posted amount by the exact three-component category-balance key it updates. */
    private final String sumPostedAmountsByCategory = """
            SELECT x.xref_acct_id, t.tran_type_cd, t.tran_cat_cd, SUM(t.tran_amt)
              FROM "transaction" t
              JOIN card_cross_reference x ON x.xref_card_num = t.tran_card_num
             GROUP BY x.xref_acct_id, t.tran_type_cd, t.tran_cat_cd
             ORDER BY x.xref_acct_id, t.tran_type_cd, t.tran_cat_cd
            """;

    /** Groups only posted negative amounts, which the source adds to the debit accumulator unchanged. */
    private final String sumNegativePostedAmountsByAccount = """
            SELECT x.xref_acct_id, SUM(t.tran_amt)
              FROM "transaction" t
              JOIN card_cross_reference x ON x.xref_card_num = t.tran_card_num
             WHERE t.tran_amt < 0
             GROUP BY x.xref_acct_id
             ORDER BY x.xref_acct_id
            """;

    /** Counts every one of the eleven domain tables while excluding Spring Batch metadata. */
    private final String countAllDomainRows = """
            SELECT relation_name, row_count
              FROM (
                    SELECT 'account' AS relation_name, COUNT(*) AS row_count FROM account
                    UNION ALL SELECT 'card', COUNT(*) FROM card
                    UNION ALL SELECT 'card_cross_reference', COUNT(*) FROM card_cross_reference
                    UNION ALL SELECT 'customer', COUNT(*) FROM customer
                    UNION ALL SELECT 'daily_transaction', COUNT(*) FROM daily_transaction
                    UNION ALL SELECT 'disclosure_group', COUNT(*) FROM disclosure_group
                    UNION ALL SELECT 'transaction', COUNT(*) FROM "transaction"
                    UNION ALL SELECT 'transaction_category', COUNT(*) FROM transaction_category
                    UNION ALL SELECT 'transaction_category_balance', COUNT(*)
                      FROM transaction_category_balance
                    UNION ALL SELECT 'transaction_type', COUNT(*) FROM transaction_type
                    UNION ALL SELECT 'user_security', COUNT(*) FROM user_security
                   ) domain_counts
             ORDER BY relation_name
            """;

    /**
     * The assembled topology is the pre-flight step then the posting step, and the decider is not a bean.
     *
     * <p>Purpose: pin the structure before any behaviour, so a topology regression is reported as a missing
     * step rather than as a downstream assertion failure. Inputs: none; nothing is launched. Output: none.
     * Side effects: none. Error modes: a third step means one was invented; a resolvable decider bean means
     * the gating was refactored into the context, where a test could reach it directly and stop exercising
     * the flow.
     */
    @Test
    @DisplayName("1. the job holds exactly the CBTRN01C pre-flight step and the CBTRN02C posting step, and "
            + "no JobExecutionDecider is exposed as a bean")
    void theAssembledTopologyIsThePreFlightStepThenThePostingStep() {
        assertThat(dailyTransactionPostingJob.getName())
                .as("the job carries the member name of app/jcl/POSTTRAN.jcl")
                .isEqualTo(jobName);
        assertThat(dailyTransactionPostingJob)
                .as("DailyTransactionPostingJob.FLOW_BEAN_NAME builds a flow job, which makes its steps "
                        + "enumerable without launching it")
                .isInstanceOf(StepLocator.class);
        assertThat(((StepLocator) dailyTransactionPostingJob).getStepNames())
                .as("two steps and no more. CBTRN01C is read-only - its verb inventory is OPEN, READ, CLOSE "
                        + "and DISPLAY, with no WRITE, REWRITE or DELETE anywhere - and it has no JCL member "
                        + "of its own, so it is folded in here as a labelled step rather than promoted to a "
                        + "seventh job")
                .containsExactlyInAnyOrderElementsOf(stepNamesInSourceOrder);

        assertThatExceptionOfType(NoSuchBeanDefinitionException.class)
                .as("POSTTRAN.jcl contains no COND and DailyTransactionPostingJob creates its return-code "
                        + "decider inline, so the only observation path is the assembled flow's exit status")
                .isThrownBy(() -> applicationContext.getBean(JobExecutionDecider.class));
    }

    /**
     * The job refuses a parameter set it never declared, rather than ignoring it.
     *
     * <p>Purpose: pin the validator, which is the one guard that stops a malformed parameter from silently
     * becoming part of a job instance identity. Inputs: a value carrying a control character, and separately
     * a parameter map above the accepted maximum. Output: none. Side effects: none - the launch is refused
     * before any step runs. Error modes: an accepted launch means the validator was removed, after which a
     * forged parameter value would reach the job repository and the logs unfiltered.
     */
    @Test
    @DisplayName("2. a launch carrying a control character in a parameter value, or more parameters than the "
            + "job accepts, is refused before any step runs")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aMalformedParameterSetIsRefused() {
        // The harness rewraps every launch refusal as IllegalStateException, preserving the framework
        // exception as the cause, so that a refused launch can never be mistaken for a business failure with
        // an exit status to assert on. The assertion therefore names both layers.
        assertThatExceptionOfType(IllegalStateException.class)
                .as("DailyTransactionPostingJob.PostTranParametersValidator protects the job-instance and log "
                        + "boundary, so a carriage return in a parameter cannot forge a log record")
                .isThrownBy(() -> launchJob(dailyTransactionPostingJob, jobParameters(Map.of(
                        RUN_ID_PARAMETER, runId(),
                        "forged", "value\r\nlevel=INFO message=posted"))))
                .withMessageContaining("Job 'POSTTRAN' could not be launched")
                .withCauseInstanceOf(JobParametersInvalidException.class);

        final Map<String, String> tooMany = new LinkedHashMap<>();
        tooMany.put(RUN_ID_PARAMETER, runId());
        for (int index = 0; index < maxAcceptedJobParameters; index++) {
            tooMany.put("spurious" + index, Integer.toString(index));
        }
        assertThatExceptionOfType(IllegalStateException.class)
                .as("EXEC PGM=CBTRN02C in app/jcl/POSTTRAN.jcl passes no PARM at all, so the accepted "
                        + "parameter surface is deliberately small; accepting an unbounded set would let two "
                        + "identical runs become two job instances of the same work")
                .isThrownBy(() -> launchJob(dailyTransactionPostingJob, jobParameters(tooMany)))
                .withMessageContaining("Job 'POSTTRAN' could not be launched")
                .withCauseInstanceOf(JobParametersInvalidException.class);
    }

    /**
     * The unmodified fixture produces rejects, preserves accounting and maps source return code 4 to success.
     *
     * <p>Purpose: pin the end-of-run counters and exit status without inventing an exact reject total. Inputs:
     * the 300 staged rows. Output: none. Side effects: accepted rows are committed. Error modes: a missing
     * counter loses the source display contract; a plain completed exit with positive rejects loses
     * {@code app/cbl/CBTRN02C.cbl:229-231}; a row absent from both sinks has vanished.
     */
    @Test
    @DisplayName("3. the seeded run rejects at least one row, accounts for all 300, and completes with rejects")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aRunPublishesBothCountersAndKeysItsExitCodeOnTheRejectCount() {
        assertThat(readFixture("dailytran.txt").size())
                .as("the harness resolves the exact classpath fixture name; dailytran.txt contains 300 records")
                .isEqualTo(Math.toIntExact(seededDailyTransactionCount));
        assertThat(dailyTransactionRepository.count())
                .as("app/data/ASCII/dailytran.txt seeds three hundred staged rows, which carry both "
                        + "positive and negative overpunch signs and so exercise the cycle-debit branch")
                .isEqualTo(seededDailyTransactionCount);

        final JobExecution execution = launchPostingJob();

        final ExecutionContext context = execution.getExecutionContext();
        assertThat(context.containsKey(DailyTransactionPostingJob.PROCESSED_COUNT_CONTEXT_ENTRY))
                .as("app/cbl/CBTRN02C.cbl:227 displays the processed count after close, so the matching "
                        + "DailyTransactionPostingJob context entry must be promoted")
                .isTrue();
        assertThat(context.containsKey(DailyTransactionPostingJob.REJECT_COUNT_CONTEXT_ENTRY))
                .as("app/cbl/CBTRN02C.cbl:228-230 displays the reject count and keys return code 4 on it, so "
                        + "the matching DailyTransactionPostingJob context entry is mandatory")
                .isTrue();

        final long processed = processedCount(execution);
        final long rejected = context.getLong(DailyTransactionPostingJob.REJECT_COUNT_CONTEXT_ENTRY);
        assertThat(processed)
                .as("app/cbl/CBTRN02C.cbl:206 increments WS-TRANSACTION-COUNT for every row the loop sees")
                .isEqualTo(seededDailyTransactionCount);
        final long expectedRejects =
                PostingParityOracle.readCommittedExpectation("rejects.txt").size();
        assertThat(expectedRejects)
                .as("the premise: the committed expectation must carry at least one reject, or the assertion "
                        + "below would be satisfied by a run that rejected nothing")
                .isPositive();
        assertThat(rejected)
                .as("app/cbl/CBTRN02C.cbl:410 is reachable in the fixture, and the exact total is now "
                        + "asserted rather than declined: it is derived from the frozen source and fixtures by "
                        + "PostingParityOracle and committed under src/test/resources/expected/posttran")
                .isEqualTo(expectedRejects);
        assertThat(committedTransactionCount() + rejected)
                .as("every one of the 300 staged rows is either committed or rejected; processedSeen itself "
                        + "already includes rejects because app/cbl/CBTRN02C.cbl:206 precedes the branch")
                .isEqualTo(seededDailyTransactionCount);
        assertThat(execution.getExitStatus().getExitCode())
                .as("app/cbl/CBTRN02C.cbl:230 is the corpus's only numeric RETURN-CODE assignment: positive "
                        + "rejects map to the successful return-code-4 outcome")
                .isEqualTo(exitCodeCompletedWithRejects);
    }

    /**
     * Every accepted row is committed once and carries the deterministic 26-character processing timestamp.
     *
     * <p>Purpose: pin the posted sink and the timestamp assembled at
     * {@code app/cbl/CBTRN02C.cbl:692-705}. Inputs: the seeded rows. Output: none. Side effects: the run
     * commits. Error modes: a row-count mismatch loses or duplicates an accepted record; a temporal
     * conversion, host clock or nanosecond formatter changes the fixed-width bytes.
     */
    @Test
    @DisplayName("4. every accepted record is committed once with the fixed 26-character processing timestamp")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void everyPostedRecordIsCommittedExactlyOnce() {
        assertThat(committedTransactionCount())
                .as("V3__seed_data.sql inserts no transaction rows, and the parent's reset deletes any a "
                        + "previous test committed, so the run starts from an empty relation")
                .isZero();

        final JobExecution execution = launchPostingJob();
        final long accepted = processedCount(execution) - rejectedCount(execution);
        final var postedRows = transactionRepository.findAll();
        final String expectedTimestamp = processingTimestampFormat.format(
                LocalDateTime.ofInstant(fixedInstant(), ZoneOffset.UTC)) + "0000";

        assertThat(postedRows.size())
                .as("app/cbl/CBTRN02C.cbl:211-215 routes each input to exactly one arm, so committed rows "
                        + "equal all records seen less rejects")
                .isEqualTo(Math.toIntExact(accepted));
        for (final var posted : postedRows) {
            assertThat(posted.getProcTs())
                    .as("app/cbl/CBTRN02C.cbl:692-705 builds DB2-FORMAT-TS from the injected clock and moves "
                            + "four literal zeros at :701")
                    .hasSize(26)
                    .endsWith("0000")
                    .isEqualTo(expectedTimestamp);
        }
    }

    /**
     * Every reject produced by the unmodified fixtures is code 102 and none is 100, 101 or 103.
     *
     * <p>Purpose: bind the assembled validation cascade to the independently verified fixture reachability.
     * Inputs: the shipped 300 rows. Output: none. Side effects: the run commits. Error modes: any other code
     * means a lookup, expiry comparison or sequential overwrite no longer follows
     * {@code app/cbl/CBTRN02C.cbl:370-422}.
     */
    @Test
    @DisplayName("5. every shipped-fixture reject is code 102, and codes 100, 101 and 103 remain unreachable")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void everyFixtureRejectIsOverLimitAndNoOtherReachableCodeAppears() {
        final JobExecution execution = launchPostingJob();
        final List<String> records = rejectRecords(execution);
        final List<Integer> reasonCodes = records.stream()
                .map(this::rejectReasonCode)
                .toList();

        assertThat(records)
                .as("the shipped account limits make app/cbl/CBTRN02C.cbl:410 reachable, so DALYREJS must "
                        + "contain at least one fixed-width record")
                .isNotEmpty();
        assertThat(reasonCodes)
                .as("app/cbl/CBTRN02C.cbl:380-422 plus zero card/account orphans and the 2022-06-10 origin "
                        + "date make 100, 101 and 103 unreachable; only :410 code 102 can classify a fixture row")
                .allMatch(code -> code.intValue() == RejectCode.OVERLIMIT_TRANSACTION.getCode())
                .doesNotContain(
                        Integer.valueOf(RejectCode.INVALID_CARD_NUMBER.getCode()),
                        Integer.valueOf(RejectCode.ACCOUNT_RECORD_NOT_FOUND.getCode()),
                        Integer.valueOf(RejectCode.TRANSACTION_RECEIVED_AFTER_ACCT_EXPIRATION.getCode()));
        assertThat(records.stream()
                        .map(this::rejectDescription)
                        .map(String::stripTrailing)
                        .toList())
                .as("app/cbl/CBTRN02C.cbl:411 moves the exact OVERLIMIT TRANSACTION literal into the "
                        + "76-character description field")
                .allMatch(RejectCode.OVERLIMIT_TRANSACTION.getDescription()::equals);
    }

    /**
     * Every reject record is exactly 430 bytes and its trailer is exactly four plus 76 characters.
     *
     * <p>Purpose: pin the byte boundary declared independently by {@code app/jcl/POSTTRAN.jcl:36} and
     * {@code app/cbl/CBTRN02C.cbl:176-182}. Inputs: the emitted DALYREJS generation. Output: none. Side
     * effects: the run commits. Error modes: a delimiter, multibyte encoding or width drift breaks the
     * modulus or one component width.
     */
    @Test
    @DisplayName("6. every DALYREJS record is 430 bytes: 350 data plus a four-and-76-character trailer")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void everyRejectRecordHasTheDeclaredFixedWidthGeometry() {
        final JobExecution execution = launchPostingJob();
        final byte[] payload = rejectPayload(execution);

        assertThat(payload)
                .as("app/jcl/POSTTRAN.jcl:38 allocates DALYREJS(+1), so positive rejects produce one non-empty "
                        + "generation")
                .isNotEmpty();
        assertThat(payload.length % RejectCode.REJECT_RECORD_LENGTH)
                .as("POSTTRAN.jcl:36 declares RECFM=F,LRECL=430, so the object contains whole records and "
                        + "no separators or partial tail")
                .isZero();

        for (final String record : rejectRecords(payload)) {
            final String transactionImage = record.substring(0, RejectCode.REJECT_TRAN_DATA_LENGTH);
            final String trailer = record.substring(RejectCode.REJECT_TRAN_DATA_LENGTH);
            final String reason = trailer.substring(0, RejectCode.FAIL_REASON_LENGTH);
            final String description = trailer.substring(RejectCode.FAIL_REASON_LENGTH);

            assertThat(record.length())
                    .as("app/cbl/CBTRN02C.cbl:176-182 declares REJECT-RECORD as X(350) plus X(80)")
                    .isEqualTo(RejectCode.REJECT_RECORD_LENGTH);
            assertThat(transactionImage.length())
                    .as("app/cbl/CBTRN02C.cbl:447 moves the unmodified 350-byte DALYTRAN image into "
                            + "REJECT-TRAN-DATA")
                    .isEqualTo(RejectCode.REJECT_TRAN_DATA_LENGTH);
            assertThat(trailer.length())
                    .as("app/cbl/CBTRN02C.cbl:177-182 declares VALIDATION-TRAILER as the final 80 bytes")
                    .isEqualTo(RejectCode.VALIDATION_TRAILER_LENGTH);
            assertThat(reason)
                    .as("app/cbl/CBTRN02C.cbl:181 declares WS-VALIDATION-FAIL-REASON PIC 9(04)")
                    .hasSize(RejectCode.FAIL_REASON_LENGTH)
                    .containsOnlyDigits();
            assertThat(description)
                    .as("app/cbl/CBTRN02C.cbl:182 declares WS-VALIDATION-FAIL-REASON-DESC PIC X(76)")
                    .hasSize(RejectCode.FAIL_REASON_DESC_LENGTH);
        }
    }

    /**
     * Category balances equal their opening values plus every posted amount for the same composite key.
     *
     * <p>Purpose: distinguish the two source branches' {@code ADD} operations from a plausible replacement
     * implementation. Inputs: opening balances and the committed transaction rows. Output: none. Side effects:
     * the run commits. Error modes: replacing an existing value or failing to initialise a new value from zero
     * makes at least one expected sum differ.
     */
    @Test
    @DisplayName("7. category-balance create and rewrite branches both add the posted amount, never replace it")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void categoryBalancesAreAddedToRatherThanReplaced() {
        final Map<String, BigDecimal> openingBalances = categoryBalanceSnapshot();

        launchPostingJob();

        final Map<String, BigDecimal> postedAmounts = postedAmountsByCategory();
        final Map<String, BigDecimal> expectedBalances = new TreeMap<>(openingBalances);
        postedAmounts.forEach((key, amount) -> expectedBalances.merge(key, amount, BigDecimal::add));
        final Map<String, BigDecimal> actualBalances = categoryBalanceSnapshot();

        assertThat(postedAmounts)
                .as("at least one accepted row must reach app/cbl/CBTRN02C.cbl:467-542, otherwise the "
                        + "category-balance write contract was not exercised")
                .isNotEmpty();
        assertThat(actualBalances.size())
                .as("app/cbl/CBTRN02C.cbl:467-500 may create category keys but may neither lose an opening "
                        + "key nor create one without a posted transaction")
                .isEqualTo(expectedBalances.size());
        for (final Map.Entry<String, BigDecimal> expected : expectedBalances.entrySet()) {
            final BigDecimal actual = actualBalances.get(expected.getKey());
            assertThat(actual)
                    .as("app/cbl/CBTRN02C.cbl:508 and :527 both ADD DALYTRAN-AMT; neither branch assigns or "
                            + "replaces TRAN-CAT-BAL")
                    .isNotNull()
                    .isEqualByComparingTo(expected.getValue());
        }
    }

    /**
     * Every account that accepted a negative amount stores the same negative sum in its debit accumulator.
     *
     * <p>Purpose: pin the sign-sensitive branch at {@code app/cbl/CBTRN02C.cbl:547-552}. Inputs: committed
     * negative transactions grouped through their card cross-reference. Output: none. Side effects: the run
     * commits. Error modes: an absolute value, subtraction or non-negative constraint turns the stored value
     * positive and changes the subsequent over-limit formula.
     */
    @Test
    @DisplayName("8. posted negative amounts remain negative in ACCT-CURR-CYC-DEBIT; no absolute value is taken")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void negativePostedAmountsRemainNegativeInTheDebitAccumulator() {
        launchPostingJob();

        final Map<Long, BigDecimal> expectedDebits = negativePostedAmountsByAccount();
        assertThat(expectedDebits)
                .as("app/data/ASCII/dailytran.txt contains 50 negative amounts and at least one must post")
                .isNotEmpty();

        for (final Map.Entry<Long, BigDecimal> expected : expectedDebits.entrySet()) {
            final var account = accountRepository.findById(expected.getKey()).orElseThrow(
                    () -> new IllegalStateException("A cross-reference resolved an account absent after posting"));
            assertThat(expected.getValue())
                    .as("app/cpy/CVTRA06Y.cpy:10 declares DALYTRAN-AMT signed, so grouped source amounts "
                            + "remain signed")
                    .isNegative();
            assertThat(account.getCurrentCycleDebit())
                    .as("app/cbl/CBTRN02C.cbl:551 adds the negative amount itself to the debit accumulator")
                    .isNegative()
                    .isEqualByComparingTo(expected.getValue());
        }
    }

    /**
     * A row that fails both unguarded account checks emits one reject bearing code 103, not code 102.
     *
     * <p>Purpose: pin the non-trivial bug-fix boundary at {@code app/cbl/CBTRN02C.cbl:407-419}, where the
     * expiry assignment follows the over-limit assignment with no guard or early exit. Inputs: one committed
     * synthetic staging row cloned from a valid fixture row, with an over-limit amount and a future originating
     * date. Output: none. Side effects: the row is inserted before launch and deleted in a {@code finally}
     * block. Error modes: guarding the second test emits 102; treating both failures independently emits two
     * records.
     */
    @Test
    @DisplayName("9. when over-limit and expired are both true, code 103 overwrites 102 and exactly one "
            + "reject is emitted")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void expirationOverwritesOverLimitAndEmitsExactlyOneReject() {
        final var firstPage = dailyTransactionRepository.findAllByOrderByIngestSequenceAsc(
                PageRequest.of(0, 1));
        assertThat(firstPage.getContent().size())
                .as("V3__seed_data.sql must supply a committed template row for the synthetic boundary case")
                .isEqualTo(1);

        final var synthetic = firstPage.getContent().get(0);
        synthetic.setIngestSequence(Long.valueOf(syntheticIngestSequence));
        synthetic.setTransactionId(syntheticTransactionId);
        synthetic.setAmount(syntheticOverLimitAmount);
        synthetic.setOrigTs(syntheticExpiredOriginTimestamp);
        synthetic.setProcTs(" ".repeat(26));

        boolean inserted = false;
        try {
            dailyTransactionRepository.saveAndFlush(synthetic);
            inserted = true;

            final JobExecution execution = launchPostingJob();
            final List<String> matching = rejectRecords(execution).stream()
                    .filter(record -> record.substring(0, 16).equals(syntheticTransactionId))
                    .toList();

            assertThat(matching.size())
                    .as("app/cbl/CBTRN02C.cbl:407-419 writes one shared reason field, and :446-465 writes "
                            + "one record per rejected input, so two failed checks still produce one record")
                    .isEqualTo(1);
            final String record = matching.get(0);
            assertThat(rejectReasonCode(record))
                    .as("app/cbl/CBTRN02C.cbl:417 is unguarded and follows :410, so code 103 overwrites 102")
                    .isEqualTo(RejectCode.TRANSACTION_RECEIVED_AFTER_ACCT_EXPIRATION.getCode())
                    .isNotEqualTo(RejectCode.OVERLIMIT_TRANSACTION.getCode());
            assertThat(record.substring(RejectCode.REJECT_TRAN_DATA_LENGTH))
                    .as("app/cbl/CBTRN02C.cbl:176-182 and :446-465 require the one record to carry the "
                            + "complete code-103 validation trailer")
                    .isEqualTo(RejectCode.TRANSACTION_RECEIVED_AFTER_ACCT_EXPIRATION.toValidationTrailer());
        } finally {
            if (inserted) {
                dailyTransactionRepository.deleteById(Long.valueOf(syntheticIngestSequence));
                dailyTransactionRepository.flush();
            }
        }
    }

    /**
     * The labelled pre-flight step runs first and changes none of the eleven domain tables.
     *
     * <p>Purpose: prove both consequences of folding read-only {@code CBTRN01C} into the job. Inputs: the
     * seeded database. Output: none. Side effects: an isolated step execution writes framework metadata only;
     * the subsequent full run is used solely to observe step order. Error modes: a domain count change means a
     * write verb was invented, and reversed execution order defeats the purpose of a pre-flight.
     */
    @Test
    @DisplayName("10. CBTRN01C is the first labelled step and its isolated execution writes no domain row")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void thePreFlightStepRunsFirstAndWritesNoDomainState() {
        final Map<String, Long> before = domainRowCounts();
        final Step preFlight = applicationContext.getBean(
                DailyTransactionPostingJob.PRE_FLIGHT_STEP_BEAN_NAME, Step.class);
        final JobRepository jobRepository = applicationContext.getBean(JobRepository.class);
        final Job preFlightOnly = new JobBuilder(jobName + "-preflight-only", jobRepository)
                .start(preFlight)
                .build();

        final JobExecution isolated = launchJob(preFlightOnly, runIdParameters(Map.of()));
        assertThat(isolated.getStatus())
                .as("app/cbl/CBTRN01C.cbl:155-197 completes its diagnostic pass before the posting step runs")
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(isolated.getStepExecutions())
                .as("DailyTransactionPostingJob.PRE_FLIGHT_STEP_BEAN_NAME is the isolated job's one labelled "
                        + "CBTRN01C step")
                .singleElement()
                .satisfies(step -> {
                    assertThat(step.getStepName())
                            .as("the step name is DailyTransactionPostingJob.PRE_FLIGHT_STEP_BEAN_NAME")
                            .isEqualTo(DailyTransactionPostingJob.PRE_FLIGHT_STEP_BEAN_NAME);
                    assertThat(step.getWriteCount())
                            .as("CBTRN01C has WRITE, REWRITE and DELETE counts of zero")
                            .isZero();
                });
        assertThat(domainRowCounts())
                .as("all eleven V1 domain relations retain their row counts after the isolated pre-flight; "
                        + "Spring Batch metadata is deliberately outside this census")
                .isEqualTo(before);

        final JobExecution fullRun = launchPostingJob();
        assertThat(fullRun.getStepExecutions().stream()
                        .sorted(Comparator.comparing(StepExecution::getId))
                        .map(StepExecution::getStepName)
                        .toList())
                .as("DailyTransactionPostingJob.PRE_FLIGHT_STEP_BEAN_NAME must precede "
                        + "POSTING_STEP_BEAN_NAME in the assembled flow")
                .containsExactlyElementsOf(stepNamesInSourceOrder);
    }

    /**
     * An account rewrite failure rolls back the earlier category write and prevents the transaction insert.
     *
     * <p>Purpose: assert the deliberately atomic Java behaviour, explicitly a deviation from the three
     * independent source commits at {@code app/cbl/CBTRN02C.cbl:440-442}. Inputs: the real repository with its
     * {@code flush} boundary made to fail. Output: none. Side effects: none survive. Error modes: a changed
     * category map or transaction row is the orphan hazard; a missing message or cause swallows diagnostic
     * context.
     */
    @Test
    @DisplayName("11. labelled deviation: account rewrite failure rolls back category and transaction writes")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void accountRewriteFailureRollsBackTheWholePostingUnit() {
        final Map<String, BigDecimal> openingBalances = categoryBalanceSnapshot();
        assertThat(committedTransactionCount())
                .as("the atomicity probe starts from V3's intentionally empty transaction relation")
                .isZero();

        final DataAccessResourceFailureException simulatedFailure =
                new DataAccessResourceFailureException("simulated account rewrite failure");
        doThrow(simulatedFailure).when(accountRepository).flush();

        final JobExecution execution = launchJob(dailyTransactionPostingJob, runIdParameters(Map.of()));

        assertThat(execution.getStatus())
                .as("app/cbl/CBTRN02C.cbl:707-711 makes a store failure an abend rather than a reject")
                .isEqualTo(BatchStatus.FAILED);
        final Map<String, BigDecimal> balancesAfterFailure = categoryBalanceSnapshot();
        assertThat(balancesAfterFailure.size())
                .as("the labelled transaction deviation around app/cbl/CBTRN02C.cbl:440-442 may neither add "
                        + "nor remove a category-balance row after failure")
                .isEqualTo(openingBalances.size());
        for (final Map.Entry<String, BigDecimal> opening : openingBalances.entrySet()) {
            assertThat(balancesAfterFailure.get(opening.getKey()))
                    .as("labelled deviation from app/cbl/CBTRN02C.cbl:556: the category write that occurred "
                            + "before the failed account rewrite is rolled back with it")
                    .isNotNull()
                    .isEqualByComparingTo(opening.getValue());
        }
        assertThat(committedTransactionCount())
                .as("app/cbl/CBTRN02C.cbl:442 follows the failed account rewrite and therefore never persists; "
                        + "neither legacy orphan survives the Java transaction")
                .isZero();
        assertThat(postingStepCounter(execution, DailyTransactionPostingJob.PROCESSED_COUNT_CONTEXT_ENTRY))
                .as("finding B-08: the chunk that rolled back must contribute nothing to the counter either. "
                        + "Its record is re-read on a restart and counted then, so counting it here as well "
                        + "is what made a 300-record run report 301 processed")
                .isZero();
        assertThat(postingStepCounter(execution, DailyTransactionPostingJob.REJECT_COUNT_CONTEXT_ENTRY))
                .as("and the reject counter likewise, which is not cosmetic: app/cbl/CBTRN02C.cbl:229-231 "
                        + "derives return code 4 from it")
                .isZero();

        final Throwable translated = failureChain(execution).stream()
                .filter(failure -> failure.getMessage() != null
                        && failure.getMessage().contains("ACCOUNT REWRITE FAILED"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "The failed execution did not retain the account rewrite diagnostic"));
        final boolean messageRetained = translated.getMessage().contains("ACCOUNT REWRITE FAILED")
                && translated.getMessage().contains("109");
        assertThat(messageRetained)
                .as("app/cbl/CBTRN02C.cbl:556 assigns 109, so the exception retains that paragraph context")
                .isTrue();
        assertThat(translated.getCause())
                .as("Rule 1 Clause B requires the original store failure to remain the direct cause")
                .isSameAs(simulatedFailure);
    }

    /**
     * The fixed twenty-character status prefix is copied into the rendered line without modification.
     *
     * <p>Purpose: pin the observability counter-constraint at {@code app/cbl/CBTRN02C.cbl:714-727}. Inputs:
     * status {@code '23'}, whose four-character rendering is deterministic. Output: none. Side effects: none.
     * Error modes: trimming, masking, reformatting or a second prefix changes the source-visible line.
     */
    @Test
    @DisplayName("12. FILE STATUS IS: NNNN passes through unmodified before the four rendered status characters")
    void theFileStatusDisplayPrefixPassesThroughUnmodified() {
        final String displayed = FileStatus.DISPLAY_MESSAGE_PREFIX + FileStatus.renderIoStatus04("23");
        final String copiedPrefix = displayed.substring(0, FileStatus.DISPLAY_MESSAGE_PREFIX.length());

        assertThat(copiedPrefix)
                .as("app/cbl/CBTRN02C.cbl:721 and :725 emit the fixed prefix byte for byte")
                .isEqualTo(FileStatus.DISPLAY_MESSAGE_PREFIX);
        assertThat(displayed)
                .as("app/cbl/CBTRN02C.cbl:721 and :725 concatenate the prefix and IO-STATUS-04 with no "
                        + "inserted separator")
                .startsWith(FileStatus.DISPLAY_MESSAGE_PREFIX)
                .endsWith("0023");
    }

    /**
     * A chunk that fails after the counters were bumped contributes nothing to them, and the restart reports
     * 300 processed for 300 inputs.
     *
     * <p><b>Purpose.</b> Close QA finding B-08 with the reproduction the finding describes. The two end-of-run
     * counters live in the step execution context so that they survive a restart, but an execution context is
     * not a transactional resource: a chunk that rolled back used to leave its increments behind,
     * {@code AbstractStep} then persisted that in-memory context in its own {@code finally} block, the reader
     * re-read the same records after the restart, and they were counted a second time. The measured symptom
     * was {@code TRANSACTIONS PROCESSED :000000301} for 300 distinct input records.
     *
     * <p><b>Inputs.</b> The 300-record fixture, and one injected {@link DataAccessResourceFailureException}
     * on the 150th posted write - deliberately mid-run rather than on the first record, so that a real
     * checkpoint exists to restart from. The commit interval is one record, so that failure rolls back exactly
     * one chunk.
     *
     * <p><b>Output.</b> None. <b>Side effects:</b> two executions of the same job instance, the first failed
     * and the second a restart; the run's posted rows, category balances and reject generations are committed
     * and are disposed with the containers.
     *
     * <p><b>What is asserted, and why it cannot be satisfied by the defect.</b> After the failure, the
     * processed counter must equal the committed posted rows plus the counted rejects - an invariant the defect
     * breaks by exactly one, because the rolled-back chunk bumps the counter and commits no row. After the
     * restart, the counters must read 300 and 38 rather than 301 and 38, with 262 committed rows and no
     * duplicate identifier, which is the finding's own expected outcome.
     *
     * <p><b>Error modes.</b> A first run that completes means the injected failure never fired and the test
     * proves nothing, so the status is asserted first. A restart that fails leaves the second set of
     * assertions unreachable and the failure names the execution.
     */
    @Test
    @DisplayName("13. a chunk that does not commit is not counted twice: the restart reports 300 for 300 inputs")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aChunkThatDoesNotCommitIsNotCountedTwiceAcrossARestart() {
        final List<String> expectedPosted =
                PostingParityOracle.readCommittedExpectation("transactions.txt");
        final String failingTransactionId = expectedPosted
                .get(expectedPosted.size() / 2)
                .split(java.util.regex.Pattern.quote(PostingParityOracle.FIELD_SEPARATOR))[0];
        doThrow(new DataAccessResourceFailureException(
                "simulated transaction write failure on " + failingTransactionId))
                .when(transactionRepository).saveAllAndFlush(argThat(
                        items -> items != null && containsTransactionId(items, failingTransactionId)));

        final JobExecution failedRun = launchJob(dailyTransactionPostingJob, runIdParameters(Map.of()));

        assertThat(failedRun.getStatus())
                .as("the injected write failure must actually have fired; a completed first run would make "
                        + "every assertion below vacuous")
                .isEqualTo(BatchStatus.FAILED);

        final long processedAfterFailure =
                postingStepCounter(failedRun, DailyTransactionPostingJob.PROCESSED_COUNT_CONTEXT_ENTRY);
        final long rejectedAfterFailure =
                postingStepCounter(failedRun, DailyTransactionPostingJob.REJECT_COUNT_CONTEXT_ENTRY);
        assertThat(processedAfterFailure)
                .as("finding B-08: every record app/cbl/CBTRN02C.cbl:206 counted must have had its outcome "
                        + "committed - a posted row or a counted reject. The chunk that rolled back committed "
                        + "neither, so counting it here is what made the restart report one too many. "
                        + "Resolved: processed %s, committed %s, rejected %s",
                        Long.valueOf(processedAfterFailure), Long.valueOf(committedTransactionCount()),
                        Long.valueOf(rejectedAfterFailure))
                .isEqualTo(committedTransactionCount() + rejectedAfterFailure);

        reset(transactionRepository);

        final JobExecution restartedRun = launchJob(dailyTransactionPostingJob, runIdParameters(Map.of()));

        assertThat(restartedRun.getStatus())
                .as("the same parameters address the same job instance, so this launch restarts the failed "
                        + "execution rather than starting a new one")
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(restartedRun.getId())
                .as("and it is a second execution of that instance, not the first one returned again")
                .isNotEqualTo(failedRun.getId());
        final long expectedRejects = PostingParityOracle.readCommittedExpectation("rejects.txt").size();
        assertThat(processedCount(restartedRun))
                .as("app/cbl/CBTRN02C.cbl:206 counts each of the 300 input records exactly once across the "
                        + "whole run. 301 is the finding's measured symptom")
                .isEqualTo(seededDailyTransactionCount);
        assertThat(rejectedCount(restartedRun))
                .as("and app/cbl/CBTRN02C.cbl:214 likewise, which decides return code 4 at :229-:231. The "
                        + "total comes from the committed parity expectation, not from a literal")
                .isEqualTo(expectedRejects);
        assertThat(restartedRun.getExitStatus().getExitCode())
                .as("the fixture rejects more than zero rows, so :230 gives return code 4")
                .isEqualTo(exitCodeCompletedWithRejects);
        assertThat(committedTransactionCount())
                .as("and every accepted record is committed exactly once across the two executions")
                .isEqualTo(seededDailyTransactionCount - expectedRejects);
        assertThat(distinctCommittedTransactionIds())
                .as("with no identifier committed twice, which a restart that re-posted a committed chunk "
                        + "would produce")
                .isEqualTo(committedTransactionCount());
    }

    /**
     * Whether a chunk handed to the posted writer carries the given transaction identifier.
     *
     * <p>Used as the argument matcher that selects exactly one chunk to fail. Matching on the payload rather
     * than on an invocation count is deliberate: every other call is left unstubbed and so reaches the real
     * repository through the spy, which is the only delegation an interface-backed Spring Data proxy supports.
     *
     * @param items the chunk the writer passed, never {@code null} at the call site
     * @param transactionId the identifier to look for
     * @return {@code true} if any item in the chunk carries that identifier
     */
    private boolean containsTransactionId(final Iterable<? extends Transaction> items,
            final String transactionId) {

        for (final Transaction item : items) {
            if (item != null && transactionId.equals(item.getTransactionId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Counts the distinct committed transaction identifiers.
     *
     * <p>Read through the shared template with a fixed, parameter-free statement, like every other aggregate
     * in this class.
     *
     * @return the number of distinct identifiers in the transaction relation
     */
    private long distinctCommittedTransactionIds() {
        final Long count = jdbcTemplate.queryForObject(
                "select count(distinct tran_id) from \"transaction\"", Long.class);
        if (count == null) {
            throw new IllegalStateException("The distinct identifier query returned no scalar result");
        }
        return count.longValue();
    }

    /**
     * Launches the assembled job with the deterministic per-test identifier and requires normal completion.
     *
     * @return the completed execution, never {@code null}
     */
    private JobExecution launchPostingJob() {
        final JobExecution execution = launchJob(dailyTransactionPostingJob, runIdParameters(Map.of()));
        assertThat(execution.getStatus())
                .as("app/cbl/CBTRN02C.cbl:229-231 makes return code 4 a completed-with-rejects outcome; only "
                        + "an abend or failed step may fail POSTTRAN")
                .isEqualTo(BatchStatus.COMPLETED);
        return execution;
    }

    /**
     * Reads the source's all-records-seen counter from the promoted job context.
     *
     * @param execution the completed posting execution
     * @return the number of records whose loop body ran
     */
    private long processedCount(final JobExecution execution) {
        return requiredContextCount(execution, DailyTransactionPostingJob.PROCESSED_COUNT_CONTEXT_ENTRY);
    }

    /**
     * Reads the source's rejected-record counter from the promoted job context.
     *
     * @param execution the completed posting execution
     * @return the number of rejected records
     */
    private long rejectedCount(final JobExecution execution) {
        return requiredContextCount(execution, DailyTransactionPostingJob.REJECT_COUNT_CONTEXT_ENTRY);
    }

    /**
     * Reads one counter out of the posting step's own execution context, treating an absent entry as zero.
     *
     * <p>The step context rather than the promoted job context, because a step that failed never reaches the
     * promotion in {@code PostTranStepListener.afterStep}, and this is the only place a failed run's counters
     * can be observed. Absent counts as zero for the same reason the production reader treats it that way: a
     * chunk that rolled back before writing anything leaves nothing behind, which is the outcome asserted.
     *
     * @param execution the finished execution, successful or failed
     * @param key the production-owned context key
     * @return the counter value, or zero when the entry is absent
     */
    private long postingStepCounter(final JobExecution execution, final String key) {
        for (final StepExecution step : execution.getStepExecutions()) {
            final ExecutionContext context = step.getExecutionContext();
            if (context.containsKey(key)) {
                return context.getLong(key);
            }
        }
        return 0L;
    }

    /**
     * Reads one required long from the job execution context.
     *
     * @param execution the execution whose context is read
     * @param key the production-owned context key
     * @return the stored long
     * @throws IllegalStateException if the job failed to publish the key
     */
    private long requiredContextCount(final JobExecution execution, final String key) {
        final ExecutionContext context = execution.getExecutionContext();
        if (!context.containsKey(key)) {
            throw new IllegalStateException(
                    "The posting execution did not publish a required source counter into its job context");
        }
        return context.getLong(key);
    }

    /**
     * Counts committed posted rows with the reserved table name quoted exactly as the migration emits it.
     *
     * @return the committed row count
     */
    private long committedTransactionCount() {
        final Long count = jdbcTemplate.queryForObject(countCommittedTransactions, Long.class);
        if (count == null) {
            throw new IllegalStateException("The transaction count query returned no scalar result");
        }
        return count.longValue();
    }

    /**
     * Fetches the concrete reject generation created by this execution.
     *
     * @param execution the posting execution
     * @return the object payload exactly as stored
     * @throws IllegalStateException if the writer did not publish a concrete object key
     */
    private byte[] rejectPayload(final JobExecution execution) {
        final ExecutionContext context = execution.getExecutionContext();
        final long publishedKeys = context.getLong(RejectWriter.REJECT_OBJECT_KEYS_COUNT_ENTRY, 0L);
        if (publishedKeys != 1L) {
            throw new IllegalStateException(
                    "Positive rejects did not publish exactly one concrete DALYREJS key into the job context");
        }
        final String objectKey = context.getString(RejectWriter.rejectObjectKeysIndexEntry(0));
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalStateException("The published DALYREJS object key is blank");
        }
        return s3Client.getObjectAsBytes(GetObjectRequest.builder()
                        .bucket(batchOutputBucket)
                        .key(objectKey)
                        .build())
                .asByteArray();
    }

    /**
     * Reads and splits the reject generation belonging to an execution.
     *
     * @param execution the completed posting execution
     * @return immutable 430-character records in object order
     */
    private List<String> rejectRecords(final JobExecution execution) {
        return rejectRecords(rejectPayload(execution));
    }

    /**
     * Splits an undelimited fixed-width payload by counting bytes.
     *
     * @param payload the object bytes
     * @return immutable 430-character records
     * @throws IllegalStateException if a partial record is present
     */
    private List<String> rejectRecords(final byte[] payload) {
        if (payload.length % RejectCode.REJECT_RECORD_LENGTH != 0) {
            throw new IllegalStateException(String.format(Locale.ROOT,
                    "DALYREJS contains %d bytes, which is not a whole number of %d-byte records",
                    Integer.valueOf(payload.length), Integer.valueOf(RejectCode.REJECT_RECORD_LENGTH)));
        }
        final List<String> records =
                new ArrayList<>(payload.length / RejectCode.REJECT_RECORD_LENGTH);
        for (int offset = 0; offset < payload.length; offset += RejectCode.REJECT_RECORD_LENGTH) {
            records.add(new String(payload, offset, RejectCode.REJECT_RECORD_LENGTH,
                    StandardCharsets.ISO_8859_1));
        }
        return List.copyOf(records);
    }

    /**
     * Decodes the four numeric characters at the start of a reject trailer.
     *
     * @param record one complete reject record
     * @return the numeric reject code
     */
    private int rejectReasonCode(final String record) {
        final int start = RejectCode.REJECT_TRAN_DATA_LENGTH;
        return Integer.parseInt(record.substring(start, start + RejectCode.FAIL_REASON_LENGTH));
    }

    /**
     * Extracts the 76-character reject description without changing its padding.
     *
     * @param record one complete reject record
     * @return the fixed-width description
     */
    private String rejectDescription(final String record) {
        final int start = RejectCode.REJECT_TRAN_DATA_LENGTH + RejectCode.FAIL_REASON_LENGTH;
        return record.substring(start, start + RejectCode.FAIL_REASON_DESC_LENGTH);
    }

    /**
     * Takes a value snapshot of every category-balance row, keyed in source browse order.
     *
     * @return a sorted, detached map of composite key image to balance
     */
    private Map<String, BigDecimal> categoryBalanceSnapshot() {
        final Map<String, BigDecimal> snapshot = new TreeMap<>();
        for (final var row : transactionCategoryBalanceRepository.findAll()) {
            final var id = row.getId();
            snapshot.put(balanceKey(id.getAccountId(), id.getTypeCd(), id.getCatCd()), row.getBalance());
        }
        return Map.copyOf(snapshot);
    }

    /**
     * Aggregates committed posted amounts by the category-balance key they update.
     *
     * @return a sorted map of key image to signed sum
     */
    private Map<String, BigDecimal> postedAmountsByCategory() {
        return jdbcTemplate.query(sumPostedAmountsByCategory, resultSet -> {
            final Map<String, BigDecimal> amounts = new TreeMap<>();
            while (resultSet.next()) {
                amounts.put(
                        balanceKey(
                                Long.valueOf(resultSet.getLong(1)),
                                resultSet.getString(2),
                                Integer.valueOf(resultSet.getInt(3))),
                        resultSet.getBigDecimal(4));
            }
            return Map.copyOf(amounts);
        });
    }

    /**
     * Aggregates only committed negative posted amounts by account.
     *
     * @return a sorted map of account identifier to negative sum
     */
    private Map<Long, BigDecimal> negativePostedAmountsByAccount() {
        return jdbcTemplate.query(sumNegativePostedAmountsByAccount, resultSet -> {
            final Map<Long, BigDecimal> amounts = new TreeMap<>();
            while (resultSet.next()) {
                amounts.put(Long.valueOf(resultSet.getLong(1)), resultSet.getBigDecimal(2));
            }
            return Map.copyOf(amounts);
        });
    }

    /**
     * Renders the 17-character transaction-category key without relying on entity equality semantics.
     *
     * @param accountId the eleven-digit account component
     * @param typeCode the two-character type component
     * @param categoryCode the four-digit category component
     * @return a deterministic key image
     */
    private String balanceKey(final Long accountId, final String typeCode, final Integer categoryCode) {
        if (accountId == null || typeCode == null || categoryCode == null) {
            throw new IllegalStateException("A persisted transaction-category key contains a null component");
        }
        return String.format(Locale.ROOT, "%011d%s%04d",
                accountId, typeCode.stripTrailing(), categoryCode);
    }

    /**
     * Counts the eleven domain relations, deliberately excluding framework metadata tables.
     *
     * @return a sorted relation-to-count map
     */
    private Map<String, Long> domainRowCounts() {
        return jdbcTemplate.query(countAllDomainRows, resultSet -> {
            final Map<String, Long> counts = new TreeMap<>();
            while (resultSet.next()) {
                counts.put(resultSet.getString(1), Long.valueOf(resultSet.getLong(2)));
            }
            return Map.copyOf(counts);
        });
    }

    /**
     * Flattens every execution failure and its retained causes for focused diagnostic assertions.
     *
     * @param execution the failed execution
     * @return immutable failures in outer-to-inner order
     */
    private List<Throwable> failureChain(final JobExecution execution) {
        final List<Throwable> chain = new ArrayList<>();
        for (final Throwable topLevel : execution.getAllFailureExceptions()) {
            Throwable current = topLevel;
            while (current != null && !chain.contains(current)) {
                chain.add(current);
                if (current.getCause() == current) {
                    break;
                }
                current = current.getCause();
            }
        }
        return List.copyOf(chain);
    }
}

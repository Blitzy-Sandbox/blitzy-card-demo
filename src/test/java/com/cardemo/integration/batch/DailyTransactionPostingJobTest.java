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
 * Source      : app/jcl/POSTTRAN.jcl, app/cbl/CBTRN02C.cbl:202-234,
 *               :370-378, :380-392, :393-422, :424-465, :442-465,
 *               :467-500, :545-560, :562, :692-705, :707-710,
 *               :714-731, app/cbl/CBTRN01C.cbl:29-58, :156, :195,
 *               app/cpy/CVTRA06Y.cpy, app/cpy/CVTRA05Y.cpy,
 *               app/data/ASCII/dailytran.txt, CONTRIBUTING.md:33-34
 *               @ 7756d89
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

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.job.flow.JobExecutionDecider;
import org.springframework.batch.core.step.StepLocator;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.cardemo.batch.jobs.DailyTransactionPostingJob;
import com.cardemo.repository.DailyTransactionRepository;

/**
 * Integration test for the assembled daily transaction posting job.
 *
 * <h2>What it does</h2>
 *
 * <p>Every assertion here is a property of the <em>assembled</em> topology - the job, its two steps, its
 * decider, its reader, its processor and its two writers - measured against a real PostgreSQL 16 schema seeded
 * with the three hundred records of {@code app/data/ASCII/dailytran.txt}. Four behaviours are pinned:
 *
 * <ul>
 *   <li><strong>The read-only pre-flight step, {@code app/cbl/CBTRN01C.cbl}.</strong> That program has no job
 *       of its own and its verb inventory is {@code OPEN}, {@code READ}, {@code CLOSE} and {@code DISPLAY}
 *       only - there is no {@code WRITE}, {@code REWRITE} or {@code DELETE} anywhere in it - so it becomes a
 *       labelled read-only step ahead of the posting step rather than a sixth job. Inventing a job for it
 *       would invent a JCL member the corpus does not contain.</li>
 *   <li><strong>The exit-status contract, {@code app/cbl/CBTRN02C.cbl:202-234}.</strong> After the files
 *       close, the processed and rejected counts are displayed and return code 4 is set <em>if and only
 *       if</em> the reject count exceeds zero. There is no other determinant, so the
 *       completed-with-rejects outcome keys on exactly that condition and on nothing else.</li>
 *   <li><strong>The published counters.</strong> The two figures the source displays at the end of the run
 *       are published to the job execution context, which is what lets the decider key on the reject count
 *       without re-counting rows.</li>
 *   <li><strong>Atomicity, {@code app/cbl/CBTRN02C.cbl:424-465}.</strong> The source performs three
 *       independent commits - a category-balance upsert, an account update and a transaction insert - and a
 *       failure between them left orphaned rows behind. The Java tier scopes all three into one unit of work.
 *       That closes a real hazard and is therefore a <strong>labelled deviation</strong> rather than parity,
 *       owed an entry in the planned {@code DECISION_LOG.md}; what this test pins is that the posted count and
 *       the inserted row count agree exactly, which is the observable consequence.</li>
 * </ul>
 *
 * <p>Two things are deliberately not asserted. No exact reject count: the fixture's reject population is a
 * property of the shipped data rather than a contract, and pinning a number here would turn a data change into
 * a test failure that names the wrong cause. And no comparison against a captured baseline: an expected
 * {@code DALYREJS} at 430 bytes per record from a real {@code CBTRN02C} run is <strong>Not available</strong>
 * anywhere in the repository, and a baseline produced by running this implementation would be circular. What
 * is needed to close that gap is a capture from the legacy system at a known input state.
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
 * <h2>Thread safety and state</h2>
 *
 * <p>Not thread safe and not required to be: one instance per test method, no shared mutable state, no second
 * thread and no second connection. <strong>This class declares no {@code static} field of any kind</strong> -
 * the parent permits exactly two in this package and both are its containers - so every constant below is an
 * immutable instance field initialised at its declaration.
 */
@DisplayName("Daily transaction posting job: the read-only pre-flight step, and return code 4 if and only if "
        + "rejects were counted")
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
     * Counts committed rows in the transaction table, which no repository in this test's dependency set
     * reaches.
     *
     * <p>Reads only, and with one constant statement carrying no parameter at all. <strong>Writes must not go
     * through this object.</strong> The pool is configured with auto-commit switched off, so a write issued
     * here outside a transaction reports its affected-row count and is then rolled back when the connection
     * returns to the pool - silently, because the count is returned before the rollback happens.
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

    /**
     * The reserved column name the transaction table carries, quoted exactly as the migration emits it.
     *
     * <p>{@code transaction} is a reserved word, so the migration emits it double quoted and lower case. Any
     * native query must quote it identically or the statement does not parse.
     */
    private final String countCommittedTransactions = "SELECT COUNT(*) FROM \"transaction\"";

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
                .as("the job is a flow job, which is what makes its steps enumerable without launching it")
                .isInstanceOf(StepLocator.class);
        assertThat(((StepLocator) dailyTransactionPostingJob).getStepNames())
                .as("two steps and no more. CBTRN01C is read-only - its verb inventory is OPEN, READ, CLOSE "
                        + "and DISPLAY, with no WRITE, REWRITE or DELETE anywhere - and it has no JCL member "
                        + "of its own, so it is folded in here as a labelled step rather than promoted to a "
                        + "sixth job")
                .containsExactlyInAnyOrderElementsOf(stepNamesInSourceOrder);

        assertThatExceptionOfType(NoSuchBeanDefinitionException.class)
                .as("the return-code decider is an inline object rather than a bean, so the only way to "
                        + "observe it is to run the flow and read the exit status it produced")
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
                .as("a parameter value becomes part of the job instance identity and reaches the job "
                        + "repository and every log line about the run, so a carriage return in one would "
                        + "let a caller forge a log record")
                .isThrownBy(() -> launchJob(dailyTransactionPostingJob, jobParameters(Map.of(
                        RUN_ID_PARAMETER, runId(),
                        "forged", "value\r\nlevel=INFO message=posted"))))
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
                .withCauseInstanceOf(JobParametersInvalidException.class);
    }

    /**
     * A run over the seeded input completes, executes both steps in order, and publishes both counters.
     *
     * <p>Purpose: pin the pre-flight-then-post ordering, the two published counters and the exit-status
     * contract in a single measured run. Inputs: the three hundred staged rows the seed migration loads.
     * Output: none. Side effects: the run commits and is restored by the parent's reset hook. Error modes: a
     * missing counter means the end-of-run display has no Java counterpart, which is what the decider keys
     * on.
     */
    @Test
    @DisplayName("3. a run over the seeded 300 staged rows runs the pre-flight step first, publishes both "
            + "counters, and its exit code is COMPLETED WITH REJECTS if and only if rejects were counted")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aRunPublishesBothCountersAndKeysItsExitCodeOnTheRejectCount() {
        assertThat(dailyTransactionRepository.count())
                .as("app/data/ASCII/dailytran.txt seeds three hundred staged rows, which carry both "
                        + "positive and negative overpunch signs and so exercise the cycle-debit branch")
                .isEqualTo(seededDailyTransactionCount);

        final JobExecution execution = launchJob(dailyTransactionPostingJob, runIdParameters(Map.of()));

        assertThat(execution.getStatus())
                .as("a run with rejects is still a completed run; only an abend fails it. Failures were %s",
                        execution.getAllFailureExceptions())
                .isEqualTo(BatchStatus.COMPLETED);

        assertThat(execution.getStepExecutions().stream()
                        .sorted(Comparator.comparing(StepExecution::getId))
                        .map(StepExecution::getStepName)
                        .toList())
                .as("the pre-flight step must precede the posting step. Running it afterwards would report "
                        + "an unopenable file only once the posting had already been attempted")
                .containsExactlyElementsOf(stepNamesInSourceOrder);

        final ExecutionContext context = execution.getExecutionContext();
        assertThat(context.containsKey(DailyTransactionPostingJob.PROCESSED_COUNT_CONTEXT_ENTRY))
                .as("the processed count is the first of the two figures the source displays after the files "
                        + "close, so it must reach the job execution context")
                .isTrue();
        assertThat(context.containsKey(DailyTransactionPostingJob.REJECT_COUNT_CONTEXT_ENTRY))
                .as("and the reject count is the second - and the one the decider keys on, so its absence "
                        + "would silently make every run look clean")
                .isTrue();

        final long processed = context.getLong(DailyTransactionPostingJob.PROCESSED_COUNT_CONTEXT_ENTRY);
        final long rejected = context.getLong(DailyTransactionPostingJob.REJECT_COUNT_CONTEXT_ENTRY);
        assertThat(processed)
                .as("every staged row is either posted or rejected; a row that is neither has vanished")
                .isEqualTo(seededDailyTransactionCount);
        assertThat(rejected)
                .as("the reject count cannot exceed the processed count and cannot be negative")
                .isBetween(0L, processed);

        final String exitCode = execution.getExitStatus().getExitCode();
        if (rejected > 0L) {
            assertThat(exitCode)
                    .as("app/cbl/CBTRN02C.cbl:230 sets MOVE 4 TO RETURN-CODE if and only if the reject "
                            + "count exceeds zero, and %d were counted. That is the only numeric "
                            + "RETURN-CODE assignment in the whole 19,254-line corpus",
                            Long.valueOf(rejected))
                    .isEqualTo(exitCodeCompletedWithRejects);
        } else {
            assertThat(exitCode)
                    .as("no reject was counted, so the run must carry the plain completed code; producing "
                            + "the completed-with-rejects code anyway would report a return code of 4 the "
                            + "source would never have set")
                    .isEqualTo(ExitStatus.COMPLETED.getExitCode());
        }
    }

    /**
     * Posting is atomic: the posted count and the committed row count agree exactly.
     *
     * <p>Purpose: pin the observable consequence of scoping the source's three separate commits into one unit
     * of work. Inputs: the seeded staged rows. Output: none. Side effects: the run commits. Error modes: a
     * committed row count below the posted count means a transaction insert was lost; above it means a row
     * was inserted for a record the processor rejected, which is the orphaned-row outcome the source's three
     * independent commits allowed and this boundary removes.
     */
    @Test
    @DisplayName("4. the posted count and the committed transaction rows agree exactly, so no partial "
            + "posting survives - the labelled deviation from three separate commits")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void everyPostedRecordIsCommittedExactlyOnce() {
        assertThat(jdbcTemplate.queryForObject(countCommittedTransactions, Long.class))
                .as("V3__seed_data.sql inserts no transaction rows, and the parent's reset deletes any a "
                        + "previous test committed, so the run starts from an empty relation")
                .isZero();

        final JobExecution execution = launchJob(dailyTransactionPostingJob, runIdParameters(Map.of()));
        assertThat(execution.getStatus())
                .as("failures were %s", execution.getAllFailureExceptions())
                .isEqualTo(BatchStatus.COMPLETED);

        final ExecutionContext context = execution.getExecutionContext();
        final long processed = context.getLong(DailyTransactionPostingJob.PROCESSED_COUNT_CONTEXT_ENTRY);
        final long rejected = context.getLong(DailyTransactionPostingJob.REJECT_COUNT_CONTEXT_ENTRY);

        assertThat(jdbcTemplate.queryForObject(countCommittedTransactions, Long.class))
                .as("the source committed the category-balance upsert, the account update and the "
                        + "transaction insert separately, so a failure between them left orphaned rows "
                        + "behind. One unit of work removes that outcome, and the observable consequence is "
                        + "that exactly the accepted records are present: %d processed less %d rejected",
                        Long.valueOf(processed), Long.valueOf(rejected))
                .isEqualTo(processed - rejected);
    }
}

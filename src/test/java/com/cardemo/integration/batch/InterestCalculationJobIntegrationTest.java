/*
 ******************************************************************************
 * Program     : InterestCalculationJobIntegrationTest
 * Application : CardDemo
 * Type        : Java integration test (JUnit 5, Failsafe tier)
 * Function    : Launches the only assembled Spring Batch job against a real
 *               PostgreSQL 16 instance and a real LocalStack endpoint, and
 *               asserts the outcomes a mocked step cannot reach: the parameter
 *               validator's four rejection arms, the duplicate-instance refusal,
 *               the cycle-counter reset the next posting cycle depends on, and
 *               the fact that generated interest does NOT reach the keyed
 *               transaction cluster.
 * Source      : app/cbl/CBACT04C.cbl @ 7756d89 - the interest program, whose
 *               1050 paragraph zeroes both cycle counters at :L350-:L370
 * Source      : app/jcl/INTCALC.jcl @ 7756d89 - PARM='2022071800' and the
 *               brand-new sequential generation the job writes to
 * Source      : app/cpy/CVTRA01Y.cpy @ 7756d89 - the 50-byte category balance
 *               record the step reads, keyed 17 bytes
 ******************************************************************************
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************
 */
package com.cardemo.integration.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import com.cardemo.batch.jobs.InterestCalculationJob;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.StepExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The batch tier's concrete assertions for the interest calculation job.
 *
 * <h2>What it does, and why a unit test cannot</h2>
 *
 * <p>A unit test can drive a processor with a hand-built item and assert the arithmetic, and this project
 * has those. What it cannot do is prove that the assembled job actually runs: that the job's parameter
 * validator is wired to the job rather than merely written, that the reader's derived query resolves against
 * the real schema, that a chunk commits, that the framework refuses a duplicate job instance, and that the
 * step's writes land where the source puts them and nowhere else. Each of those is a property of the
 * assembled topology plus a real database, so each needs this tier.
 *
 * <p>The most valuable assertion here is the negative one. {@code CBACT04C} declares its transaction output
 * with sequential organisation and its job control allocates a brand-new generation of a sequential group on
 * every run, so generated interest reaches the keyed transaction cluster only later, through the combine
 * job's sort and load. If this job ever wrote to the transaction table directly, every unit test would still
 * pass and the divergence would surface only as duplicate keys in a job that does not exist yet. The
 * assertion that the table is still empty afterwards is what closes that gap.
 *
 * <h2>How to build, run and test</h2>
 *
 * <p>Run with {@code ./mvnw -B clean verify}. A reachable Docker daemon is a hard prerequisite: the parent
 * starts {@code postgres:16} and a LocalStack container, applies the three Flyway migrations and provisions
 * the three buckets and the FIFO queue. Compile alone with {@code ./mvnw -q test-compile}.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>None of its own. Every datasource, cloud endpoint and credential property is registered by the parent
 * from the containers, and the job's one parameter is supplied per test method.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <dl>
 *   <dt>{@code Existing transaction detected in JobRepository}</dt>
 *   <dd>A launching method lost its {@code @Transactional(propagation = Propagation.NOT_SUPPORTED)}. The
 *       parent's launch helper refuses before the framework can, and its message names the remedy.</dd>
 *   <dt>{@code A job instance already exists and is complete}</dt>
 *   <dd>Two methods used the same {@code parmDate}. Every method here uses a distinct value for exactly
 *       this reason, because a job launched from this tier commits and survives the test.</dd>
 *   <dt>A state assertion fails on a re-run of the suite</dt>
 *   <dd>Job writes are committed and are not rolled back. Every assertion here is written to hold after any
 *       number of successful runs rather than to assume a pristine database.</dd>
 *   </dl>
 *
 * <h2>Thread safety and state</h2>
 *
 * <p>Not thread safe and not required to be. One instance per test method, no shared mutable state, and no
 * {@code static} field, which the parent forbids.
 */
@DisplayName("Interest calculation job against real PostgreSQL 16 and LocalStack")
class InterestCalculationJobIntegrationTest extends AbstractBatchIntegrationTest {

    /** The assembled job under test, injected by the bean name its configuration registers. */
    @Autowired
    @Qualifier("interestCalculationJob")
    private Job interestCalculationJob;

    /** Reads committed state directly, because job writes sit outside the tier's rollback scope. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** The parameter validator's four rejection arms, asserted against the assembled job. */
    @Nested
    @DisplayName("Parameter validation: the PARM-DATE contract the JCL supplies")
    class ParameterValidation {

        @Test
        @DisplayName("an absent parmDate is refused before any step runs")
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        void absentParmDateIsRefused() {
            JobParameters empty = runIdParameters(Map.of());

            assertThatThrownBy(() -> launchJob(interestCalculationJob, empty))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(transactionRowCount())
                    .as("a refused launch writes nothing")
                    .isZero();
        }

        @Test
        @DisplayName("a parmDate of the wrong length is refused, matching PARM-DATE PIC X(10)")
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        void wrongLengthParmDateIsRefused() {
            assertThatThrownBy(() -> launchJob(interestCalculationJob,
                    runIdParameters(Map.of("parmDate", "20220718"))))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("a parmDate carrying a non-digit is refused")
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        void nonNumericParmDateIsRefused() {
            assertThatThrownBy(() -> launchJob(interestCalculationJob,
                    runIdParameters(Map.of("parmDate", "2022X71800"))))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("a parmDate not ending in the two-zero trailer is refused")
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        void missingTrailerParmDateIsRefused() {
            assertThatThrownBy(() -> launchJob(interestCalculationJob,
                    runIdParameters(Map.of("parmDate", "2022071812"))))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    /** The wiring the launch depends on, asserted before any launch so a failure names the cause. */
    @Nested
    @DisplayName("Wiring: the Job bean, its published name and its parameter validator")
    class JobWiring {

        @Test
        @DisplayName("the Job bean resolves under its qualifier and carries the JCL job name")
        void jobBeanResolves() {
            assertThat(interestCalculationJob)
                    .as("the jobs package declares this Job bean directly rather than relying on a batch "
                            + "configuration class, so its absence here would mean the batch tier has no "
                            + "launchable job at all")
                    .isNotNull();
            assertThat(interestCalculationJob.getName())
                    .as("the name is the JCL member's job name, app/jcl/INTCALC.jcl, so an operator reading "
                            + "a batch table sees the identifier the legacy schedule used")
                    .isEqualTo("INTCALC");
        }

        @Test
        @DisplayName("the job declares a parameter validator, so a bad PARM-DATE cannot reach a step")
        void jobDeclaresAValidator() {
            assertThat(interestCalculationJob.getJobParametersValidator())
                    .as("PARM-DATE is concatenated into every generated transaction identifier, so it must "
                            + "be refused before the first step rather than corrupting output. The refusals "
                            + "themselves are asserted above; this asserts the mechanism exists at all, so a "
                            + "validator removed by accident fails here rather than silently admitting every "
                            + "value")
                    .isNotNull();
        }
    }

    /** A real launch, and the committed state it must and must not leave behind. */
    @Nested
    @DisplayName("A real launch: completion, the cycle reset, and where interest does not go")
    class SuccessfulLaunch {

        @Test
        @DisplayName("the job completes, reads every seeded category balance, and zeroes both cycle counters")
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        void jobCompletesAndZeroesBothCycleCounters() {
            JobExecution execution = launchJob(interestCalculationJob,
                    runIdParameters(Map.of("parmDate", "2022071800")));

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(execution.getExitStatus().getExitCode())
                    .isEqualTo(ExitStatus.COMPLETED.getExitCode());

            assertThat(execution.getStepExecutions()).isNotEmpty();
            StepExecution step = execution.getStepExecutions().iterator().next();
            assertThat(step.getReadCount())
                    .as("tcatbal.txt seeds 50 category balance rows and the step reads all of them")
                    .isEqualTo(50L);
            assertThat(step.getFailureExceptions()).isEmpty();

            // CBACT04C:L350-L370 adds the accumulated interest to the current balance and then zeroes BOTH
            // cycle counters before the rewrite. Omitting that reset does not fail anything until the NEXT
            // posting cycle, where the over-limit temporary balance would be computed from stale cycle
            // figures - which is exactly the class of latent divergence this tier exists to catch.
            Long accountsWithNonZeroCycleFigures = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM account "
                            + "WHERE acct_curr_cyc_credit <> 0 OR acct_curr_cyc_debit <> 0",
                    Long.class);
            assertThat(accountsWithNonZeroCycleFigures)
                    .as("every account the job touched must have both cycle counters reset to zero")
                    .isZero();
        }

        @Test
        @DisplayName("the run reserves an object-storage generation and records what it wrote")
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        void theRunReservesAnObjectStorageGeneration() {
            JobExecution execution = launchJob(interestCalculationJob,
                    runIdParameters(Map.of("parmDate", "2022081000")));

            assertThat(execution.getStatus())
                    .as("failure details: %s", execution.getAllFailureExceptions())
                    .isEqualTo(BatchStatus.COMPLETED);

            // A GDG next-generation write became an object key under a monotonically increasing prefix
            // (AAP 0.5.2.2). The run records the prefix it reserved, so a non-blank value here is the
            // evidence that the object-storage path executed rather than being configured and skipped.
            assertThat(execution.getExecutionContext()
                            .getString("carddemo.systran.generation.prefix", ""))
                    .as("the prefix entry is written by the job's own generation step; an empty value means "
                            + "no generation was reserved")
                    .isNotBlank();

            // The count entry and the indexed entries it describes are the run's own manifest of the objects
            // it created, in creation order. The count is asserted rather than the indexed entries because
            // the count is what a consumer reads first, and a manifest whose count is absent is unusable.
            assertThat(execution.getExecutionContext()
                            .containsKey(InterestCalculationJob.SYSTRAN_GENERATION_KEYS_COUNT_CONTEXT_ENTRY))
                    .as("the job publishes %s alongside the indexed entries that count describes",
                            InterestCalculationJob.SYSTRAN_GENERATION_KEYS_COUNT_CONTEXT_ENTRY)
                    .isTrue();
        }

        @Test
        @DisplayName("every step that ran ended in a terminal, non-failed state")
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        void everyStepEndedCleanly() {
            JobExecution execution = launchJob(interestCalculationJob,
                    runIdParameters(Map.of("parmDate", "2022081100")));

            assertThat(execution.getStepExecutions())
                    .isNotEmpty()
                    .allSatisfy(step -> assertThat(step.getStatus())
                            .as("step '%s' did not complete; a partially executed interest run would leave "
                                    + "some accounts with interest applied and their cycle counters reset "
                                    + "and others not, which no legacy return code describes",
                                    step.getStepName())
                            .isEqualTo(BatchStatus.COMPLETED));
        }

        @Test
        @DisplayName("generated interest does not reach the keyed transaction cluster")
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        void generatedInterestNeverReachesTheTransactionTable() {
            launchJob(interestCalculationJob, runIdParameters(Map.of("parmDate", "2022072000")));

            assertThat(transactionRowCount())
                    .as("CBACT04C writes a sequential generation, not the keyed cluster; the combine job "
                            + "is what loads it later, so this table must still be empty")
                    .isZero();
        }

        @Test
        @DisplayName("relaunching the same instance is refused rather than silently running again")
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        void relaunchingAnIdenticalInstanceIsRefused() {
            JobParameters parameters = runIdParameters(Map.of("parmDate", "2022072100"));

            JobExecution first = launchJob(interestCalculationJob, parameters);
            assertThat(first.getStatus()).isEqualTo(BatchStatus.COMPLETED);

            // The duplicate-instance exposure the migration plan requires to surface rather than be smoothed
            // over: a repeated date parameter would otherwise regenerate colliding identifiers.
            assertThatThrownBy(() -> launchJob(interestCalculationJob, parameters))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("a distinct parmDate is a distinct instance and runs to completion")
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        void aDistinctParmDateIsANewInstance() {
            JobExecution execution = launchJob(interestCalculationJob,
                    runIdParameters(Map.of("parmDate", "2022072200")));

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(execution.getJobInstance().getInstanceId()).isNotNull();
        }
    }

    /**
     * Counts the rows in the keyed transaction cluster.
     *
     * <p>Native SQL rather than a repository call, and double-quoted, because {@code V1} emits the table in
     * the quoted lowercase form {@code "transaction"} to avoid the reserved word.
     *
     * @return the committed row count, never negative
     */
    private long transactionRowCount() {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM \"transaction\"", Long.class);
        return count == null ? 0L : count.longValue();
    }
}

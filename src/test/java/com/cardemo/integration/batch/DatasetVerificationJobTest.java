/*
 ******************************************************************************
 * Program     : DatasetVerificationJobTest
 * Application : CardDemo
 * Type        : Java integration test (JUnit 5, Failsafe tier)
 * Function    : Executes the four AAP F-020 read-only verification steps -
 *               READACCT, READCARD, READXREF and READCUST - as one assembled
 *               Spring Batch job against a real PostgreSQL 16 instance and a
 *               real LocalStack endpoint, and asserts the four properties a
 *               unit test cannot reach: the row counts each step observes, the
 *               restartable checkpoint state each step persists, that the run
 *               stores nothing in any relation or bucket, and that a dataset
 *               the run cannot reach abends the step, fails the job and stops
 *               the remaining steps.
 * Source      : app/jcl/READACCT.jcl @ 7756d89 - STEP05 EXEC PGM=CBACT01C
 * Source      : app/jcl/READCARD.jcl @ 7756d89 - STEP05 EXEC PGM=CBACT02C
 * Source      : app/jcl/READXREF.jcl @ 7756d89 - STEP05 EXEC PGM=CBACT03C
 * Source      : app/jcl/READCUST.jcl @ 7756d89 - STEP05 EXEC PGM=CBCUS01C
 * Source      : app/cbl/CBACT01C.cbl:L71-L85 @ 7756d89 - the OPEN, loop READ,
 *               CLOSE and end-of-run DISPLAY shape all four programs share,
 *               and :L144-L147 - the ERROR OPENING ACCTFILE abend path
 * Source      : app/cpy/CVACT01Y.cpy, CVACT02Y.cpy, CVACT03Y.cpy,
 *               CVCUS01Y.cpy @ 7756d89 - the four record layouts
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

import com.cardemo.config.BatchConfig;
import com.cardemo.exception.FatalProcessingException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * The batch tier's concrete assertions for the four AAP F-020 dataset verification steps.
 *
 * <h2>What it does, and why a unit test cannot</h2>
 *
 * <p>{@code app/cbl/CBACT01C.cbl}, {@code CBACT02C.cbl}, {@code CBACT03C.cbl} and {@code CBCUS01C.cbl} have a
 * verb inventory of {@code OPEN}, {@code READ}, {@code CLOSE} and {@code DISPLAY} and nothing else: each is a
 * submitted job whose whole purpose is to prove a dataset can be read end to end. Their Java counterparts are
 * the four {@code Step} beans {@link BatchConfig} declares, run by
 * {@link BatchConfig#DATASET_VERIFICATION_JOB_BEAN_NAME} because a {@code Step} cannot be launched on its own.
 *
 * <p>{@code com.cardemo.unit.config.BatchConfigTest} asserts the topology - which steps exist, in which order,
 * wired to which reader, with a writer that holds nothing it could store through - and
 * {@code com.cardemo.unit.batch.AccountReaderTest} and its three siblings assert each reader's own contract
 * against mocked repositories. Neither can reach what this class asserts, because each of the four properties
 * below is a property of the assembled job plus a real database:
 *
 * <ul>
 *   <li><strong>Counts.</strong> That each step's derived keyset query resolves against the real schema and
 *       returns every seeded row - fifty accounts, fifty cards, fifty cross-references, fifty customers,
 *       each figure taken from the frozen fixture rather than typed in.</li>
 *   <li><strong>Checkpoints.</strong> That each reader's restart cursor is not merely written to an in-memory
 *       context but <em>persisted</em> to {@code BATCH_STEP_EXECUTION_CONTEXT}, which is the only thing that
 *       makes a restart possible. Read back through {@link JobExplorer}, so an in-memory value that never
 *       reached the metadata store fails here.</li>
 *   <li><strong>No writes.</strong> That a run changes no row in any of the eleven business relations and
 *       leaves no object in any of the three buckets. The structural argument - the step's writer holds no
 *       repository, no object-store client and no {@code EntityManager} - is made in the unit tier; this is
 *       the measured confirmation, taken as a per-relation content digest before and after the run so an
 *       {@code UPDATE} that preserved the row count is caught too.</li>
 *   <li><strong>Mapped failures.</strong> That a dataset the run cannot reach produces the source's abend
 *       rather than a silent skip: {@code app/cbl/CBACT01C.cbl:L144-L147} displays
 *       {@code ERROR OPENING ACCTFILE}, renders the status and performs {@code 9999-ABEND-PROGRAM}, so the
 *       step must fail with {@link FatalProcessingException} carrying abend code
 *       {@value com.cardemo.exception.FatalProcessingException#BATCH_ABEND_CODE}, the job must fail, and the
 *       three later steps must not run.</li>
 * </ul>
 *
 * <h2>How the unreachable-dataset case is produced</h2>
 *
 * <p>By renaming the {@code account} relation out of the way on a connection of its own and renaming it back
 * in a {@code finally}, which makes the reader's own {@code count()} raise a genuine driver error rather than
 * a stubbed one. A stub would prove that a mock throws; this proves that the production mapping from a real
 * {@code DataAccessException} to the source's abend is wired. The restoration is asserted rather than assumed,
 * so a failure to restore is reported here by name instead of cascading into every later class in the tier.
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
 * from the containers. The job takes no parameters, because none of the four JCL members passes one; the
 * parent's run identifier is added so that two launches are two job instances.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <dl>
 *   <dt>{@code Existing transaction detected in JobRepository}</dt>
 *   <dd>A launching method lost its {@code @Transactional(propagation = Propagation.NOT_SUPPORTED)}. The
 *       parent's launch helper refuses before the framework can, and its message names the remedy.</dd>
 *   <dt>Every later batch class fails on a missing {@code account} relation</dt>
 *   <dd>The unreachable-dataset test failed to restore the rename. The restoration runs in a
 *       {@code finally} and is asserted, so the first failure names this class; fix it here rather than in
 *       the class that reports the symptom.</dd>
 *   <dt>A count assertion reports more rows than the fixture carries</dt>
 *   <dd>Some other class committed rows and the parent's reset did not remove them. The counts here are read
 *       from the frozen fixtures, so the fixture is never the thing to change.</dd>
 * </dl>
 *
 * <h2>Thread safety and state</h2>
 *
 * <p>Not thread safe and not required to be. One instance per test method, no shared mutable state and no
 * {@code static} field, which the parent forbids.
 */
@DisplayName("The four AAP F-020 verification steps against real PostgreSQL 16 and LocalStack")
class DatasetVerificationJobTest extends AbstractBatchIntegrationTest {

    /**
     * The eleven business relations, quoted where the name is a reserved word.
     *
     * <p>The {@code BATCH_*} relations are deliberately absent: the framework writes those on every launch by
     * design, so including them would make the no-writes assertion fail on its own metadata. The Flyway
     * history relation is absent for the same reason - it is infrastructure, not business state.
     */
    private static final List<String> BUSINESS_RELATIONS = List.of(
            "account", "card", "card_cross_reference", "customer", "daily_transaction",
            "disclosure_group", "transaction_category", "transaction_category_balance",
            "transaction_type", "user_security", "\"transaction\"");

    /** The assembled job under test, injected by the bean name its configuration registers. */
    @Autowired
    @Qualifier(BatchConfig.DATASET_VERIFICATION_JOB_BEAN_NAME)
    private Job datasetVerificationJob;

    /** Reads committed state directly, because job writes sit outside the tier's rollback scope. */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Reads the persisted metadata, so a checkpoint is proved to have reached the store. */
    @Autowired
    private JobExplorer jobExplorer;

    /** Lists bucket contents, so the no-writes claim covers the object store and not only the database. */
    @Autowired
    private S3Client objectStore;

    /**
     * The datasource, used only for the two statements that must commit outside any transaction.
     *
     * <p>Taken directly rather than through {@link JdbcTemplate} because the pool is configured with
     * auto-commit off; see {@link #commitDdl(String)}.
     */
    @Autowired
    private DataSource dataSource;

    /** Logical name of the batch input bucket, bound from the property the parent registers. */
    @Value("${carddemo.aws.s3.batch-input-bucket}")
    private String batchInputBucket;

    /** Logical name of the batch output bucket, the only versioned one. */
    @Value("${carddemo.aws.s3.batch-output-bucket}")
    private String batchOutputBucket;

    /** Logical name of the statements bucket. */
    @Value("${carddemo.aws.s3.statements-bucket}")
    private String statementsBucket;

    /**
     * A complete run: every seeded row read, every checkpoint persisted, nothing stored anywhere.
     */
    @Nested
    @DisplayName("A complete run reads every seeded row and stores nothing")
    class CompleteRun {

        @Test
        @DisplayName("all four steps run in JCL member order and each reads every seeded row")
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        void allFourStepsReadEverySeededRow() {
            final JobExecution execution =
                    launchJob(datasetVerificationJob, runIdParameters(Map.of()));

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(execution.getExitStatus().getExitCode())
                    .as("none of the four members carries a COND parameter, so a clean run has one outcome")
                    .isEqualTo(ExitStatus.COMPLETED.getExitCode());

            final List<StepExecution> steps = orderedSteps(execution);
            assertThat(steps).extracting(StepExecution::getStepName)
                    .as("app/jcl/READACCT.jcl, READCARD.jcl, READXREF.jcl, READCUST.jcl in that order")
                    .containsExactly(
                            BatchConfig.READ_ACCOUNT_STEP_BEAN_NAME,
                            BatchConfig.READ_CARD_STEP_BEAN_NAME,
                            BatchConfig.READ_CROSS_REFERENCE_STEP_BEAN_NAME,
                            BatchConfig.READ_CUSTOMER_STEP_BEAN_NAME);

            for (final Map.Entry<String, String> expected : stepToFixture().entrySet()) {
                final long seeded = readFixture(expected.getValue()).size();
                final StepExecution step = stepNamed(execution, expected.getKey());

                assertThat(step.getStatus())
                        .as("%s", step.getStepName())
                        .isEqualTo(BatchStatus.COMPLETED);
                assertThat(step.getReadCount())
                        .as("%s must read every row of app/data/ASCII/%s", step.getStepName(),
                                expected.getValue())
                        .isEqualTo(seeded);
                assertThat(step.getWriteCount())
                        .as("%s hands every row it read to the counter; the framework's write count is the "
                                + "number of items handed to the writer and not a number of rows stored",
                                step.getStepName())
                        .isEqualTo(seeded);
                assertThat(step.getFilterCount())
                        .as("%s filters nothing: the four programs have no processor", step.getStepName())
                        .isZero();
                assertThat(step.getSkipCount())
                        .as("%s skips nothing: a read failure abends rather than being skipped",
                                step.getStepName())
                        .isZero();
                assertThat(step.getExecutionContext()
                                .getLong(step.getStepName() + BatchConfig.VERIFIED_ROW_COUNT_SUFFIX))
                        .as("%s publishes the end-of-run count the source DISPLAYs", step.getStepName())
                        .isEqualTo(seeded);
            }
        }

        @Test
        @DisplayName("each step persists its reader's restart cursor to the metadata store")
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        void eachStepPersistsItsRestartCursor() {
            final JobExecution execution =
                    launchJob(datasetVerificationJob, runIdParameters(Map.of()));

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

            // Read back through the explorer rather than off the returned execution: an in-memory context
            // entry that never reached BATCH_STEP_EXECUTION_CONTEXT would satisfy an assertion on the
            // returned object and would still make a restart start from zero.
            final JobExecution persisted = jobExplorer.getJobExecution(execution.getId());
            assertThat(persisted).as("the launch must be recorded in the metadata store").isNotNull();

            final Map<String, String> cursorKeys = Map.of(
                    BatchConfig.READ_ACCOUNT_STEP_BEAN_NAME, "AccountReader.recordsRead",
                    BatchConfig.READ_CARD_STEP_BEAN_NAME, "CardReader.recordsRead",
                    BatchConfig.READ_CROSS_REFERENCE_STEP_BEAN_NAME,
                    "CardCrossReferenceReader.recordsRead",
                    BatchConfig.READ_CUSTOMER_STEP_BEAN_NAME, "CustomerReader.recordsRead");

            for (final Map.Entry<String, String> expected : stepToFixture().entrySet()) {
                final long seeded = readFixture(expected.getValue()).size();
                final ExecutionContext context =
                        stepNamed(persisted, expected.getKey()).getExecutionContext();
                final String cursorKey = cursorKeys.get(expected.getKey());

                assertThat(context.containsKey(cursorKey))
                        .as("%s must persist %s, or a restart cannot resume", expected.getKey(), cursorKey)
                        .isTrue();
                assertThat(context.getLong(cursorKey))
                        .as("%s", cursorKey)
                        .isEqualTo(seeded);
                assertThat(context.containsKey(
                                expected.getKey() + BatchConfig.VERIFIED_ROW_COUNT_SUFFIX))
                        .as("the published count is persisted alongside the cursor, so an operator reading "
                                + "the metadata tables sees what the step observed")
                        .isTrue();
            }

            // The two readers that keyset-scan on a numeric identifier also persist the last key they saw,
            // and it has to be the greatest one in the relation or the scan stopped early.
            assertThat(stepNamed(persisted, BatchConfig.READ_ACCOUNT_STEP_BEAN_NAME)
                            .getExecutionContext().getLong("AccountReader.lastAccountId"))
                    .as("the keyset cursor must have reached the highest ACCT-ID of "
                            + "app/cpy/CVACT01Y.cpy")
                    .isEqualTo(highestKey("account", "acct_id"));
            assertThat(stepNamed(persisted, BatchConfig.READ_CUSTOMER_STEP_BEAN_NAME)
                            .getExecutionContext().getLong("CustomerReader.lastCustomerId"))
                    .as("the keyset cursor must have reached the highest CUST-ID of "
                            + "app/cpy/CVCUS01Y.cpy")
                    .isEqualTo(highestKey("customer", "cust_id"));
        }

        @Test
        @DisplayName("the run changes no row in any of the eleven business relations")
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        void theRunChangesNoRow() {
            // A content digest rather than a row count, because the claim is that nothing was stored at all:
            // an UPDATE that preserved the count, or a delete-and-reinsert, would pass a count comparison.
            final Map<String, String> before = relationDigests();

            final JobExecution execution =
                    launchJob(datasetVerificationJob, runIdParameters(Map.of()));
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

            assertThat(relationDigests())
                    .as("app/cbl/CBACT01C.cbl and its three siblings declare no WRITE, REWRITE or DELETE "
                            + "anywhere, so a verification run is observationally invisible to the data")
                    .isEqualTo(before);
        }

        @Test
        @DisplayName("the run leaves no object in any of the three buckets")
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        void theRunLeavesNoObject() {
            final JobExecution execution =
                    launchJob(datasetVerificationJob, runIdParameters(Map.of()));
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

            for (final String bucket : List.of(batchInputBucket, batchOutputBucket, statementsBucket)) {
                assertThat(objectKeys(bucket))
                        .as("%s: none of the four members allocates an output dataset, so a verification "
                                + "run has no generation to write", bucket)
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("a second run starts cold rather than continuing the first")
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        void aSecondRunStartsCold() {
            final long seededAccounts = readFixture("acctdata.txt").size();

            launchJob(datasetVerificationJob, runIdParameters(Map.of()));
            final JobExecution second = launchJob(datasetVerificationJob,
                    jobParameters(Map.of(RUN_ID_PARAMETER, runId() + "-second")));

            assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            final StepExecution accountStep =
                    stepNamed(second, BatchConfig.READ_ACCOUNT_STEP_BEAN_NAME);
            assertThat(accountStep.getReadCount())
                    .as("a fresh job instance is a cold start: the second run reads the relation again "
                            + "rather than resuming where the first stopped")
                    .isEqualTo(seededAccounts);
            assertThat(accountStep.getExecutionContext().getLong(
                            BatchConfig.READ_ACCOUNT_STEP_BEAN_NAME
                                    + BatchConfig.VERIFIED_ROW_COUNT_SUFFIX))
                    .as("the published count is the second run's own, not the sum of both")
                    .isEqualTo(seededAccounts);
        }
    }

    /**
     * The abend path: a dataset the run cannot reach.
     */
    @Nested
    @DisplayName("A dataset the run cannot reach abends the step, fails the job and stops the stream")
    class UnreachableDataset {

        @Test
        @DisplayName("an unreadable account relation abends the first step and runs none of the other three")
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        void anUnreadableAccountRelationAbendsTheFirstStep() {
            final String hidden = "account_hidden_by_verification_test";
            final JobExecution execution;
            try {
                commitDdl("ALTER TABLE account RENAME TO " + hidden);
                assertThat(relationIsReadable("account"))
                        .as("the relation has to be genuinely unreachable before the launch, or this test "
                                + "asserts nothing; see commitDdl for why an ordinary JdbcTemplate call is "
                                + "not enough here")
                        .isFalse();
                execution = launchJob(datasetVerificationJob, runIdParameters(Map.of()));
            } finally {
                commitDdl("ALTER TABLE IF EXISTS " + hidden + " RENAME TO account");
            }

            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM account", Long.class))
                    .as("the relation is restored before anything else asserts against it; a failure here "
                            + "is this class's to fix and not the next class's to report")
                    .isEqualTo(readFixture("acctdata.txt").size());

            assertThat(execution.getStatus())
                    .as("app/cbl/CBACT01C.cbl:L147 performs 9999-ABEND-PROGRAM, which terminates the job "
                            + "step rather than continuing past an unreadable dataset")
                    .isEqualTo(BatchStatus.FAILED);
            assertThat(execution.getExitStatus().getExitCode())
                    .isEqualTo(ExitStatus.FAILED.getExitCode());

            assertThat(orderedSteps(execution)).extracting(StepExecution::getStepName)
                    .as("the chain is unconditional, so a failed step fails the job and the three later "
                            + "members never run - which is what a non-zero completion code does on the "
                            + "legacy side")
                    .containsExactly(BatchConfig.READ_ACCOUNT_STEP_BEAN_NAME);

            final StepExecution failed =
                    stepNamed(execution, BatchConfig.READ_ACCOUNT_STEP_BEAN_NAME);
            assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(failed.getReadCount())
                    .as("the abend is on the OPEN of app/cbl/CBACT01C.cbl:L72, before the loop reads "
                            + "anything")
                    .isZero();

            final Optional<FatalProcessingException> abend = failed.getFailureExceptions().stream()
                    .map(DatasetVerificationJobTest::abendWithin)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .findFirst();
            assertThat(abend)
                    .as("a DataAccessException from the store must surface as the source's abend and not as "
                            + "a bare framework failure; recorded failures were %s",
                            failed.getFailureExceptions())
                    .isPresent();
            assertThat(abend.get().getAbendCode())
                    .as("MOVE 999 TO ABCODE, app/cbl/CBACT01C.cbl:L172")
                    .isEqualTo(Integer.toString(FatalProcessingException.BATCH_ABEND_CODE));
            assertThat(abend.get().getAbendCulprit())
                    .as("ABEND-CULPRIT PIC X(8) of app/cpy/CSMSG02Y.cpy carries the failing program")
                    .isEqualTo("CBACT01C");
            assertThat(abend.get().getAbendMessage())
                    .as("the abend message carries the rendered four-character file status of "
                            + "9910-DISPLAY-IO-STATUS, which is what an operator needs to diagnose it")
                    .contains("file status");
            assertThat(abend.get().getAbendReason())
                    .as("DISPLAY 'ERROR OPENING ACCTFILE', app/cbl/CBACT01C.cbl:L144 - and note the "
                            + "wording really is ACCTFILE here rather than ACCOUNT FILE")
                    .isEqualTo("ERROR OPENING ACCTFILE");
        }
    }

    /**
     * Maps each verification step onto the frozen fixture whose record count it must observe.
     *
     * <p>Insertion-ordered so a caller iterating it walks the steps in JCL member order.
     *
     * @return the step bean name to fixture file name mapping, in JCL member order
     */
    private static Map<String, String> stepToFixture() {
        final Map<String, String> mapping = new LinkedHashMap<>();
        mapping.put(BatchConfig.READ_ACCOUNT_STEP_BEAN_NAME, "acctdata.txt");
        mapping.put(BatchConfig.READ_CARD_STEP_BEAN_NAME, "carddata.txt");
        mapping.put(BatchConfig.READ_CROSS_REFERENCE_STEP_BEAN_NAME, "cardxref.txt");
        mapping.put(BatchConfig.READ_CUSTOMER_STEP_BEAN_NAME, "custdata.txt");
        return mapping;
    }

    /**
     * Returns the step executions of one job execution in the order the framework started them.
     *
     * @param execution the job execution to read
     * @return the step executions, earliest first
     */
    private static List<StepExecution> orderedSteps(final JobExecution execution) {
        final List<StepExecution> ordered = new ArrayList<>(execution.getStepExecutions());
        ordered.sort((left, right) -> Long.compare(left.getId().longValue(), right.getId().longValue()));
        return List.copyOf(ordered);
    }

    /**
     * Returns the one step execution of a given step name.
     *
     * @param execution the job execution to read
     * @param stepName the step bean name
     * @return the step execution
     */
    private static StepExecution stepNamed(final JobExecution execution, final String stepName) {
        return execution.getStepExecutions().stream()
                .filter(step -> stepName.equals(step.getStepName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No step execution named " + stepName + " in job execution " + execution.getId()
                                + "; the executions present were " + execution.getStepExecutions()));
    }

    /**
     * Unwraps a recorded step failure down to the abend it carries, if it carries one.
     *
     * <p>The framework wraps a step failure, so the abend the reader threw is normally a cause rather than
     * the recorded throwable itself. Walking the cause chain is what makes the assertion about the production
     * mapping rather than about the framework's wrapping.
     *
     * @param failure a recorded step failure
     * @return the abend, or empty when the failure was not one
     */
    private static Optional<FatalProcessingException> abendWithin(final Throwable failure) {
        for (Throwable candidate = failure; candidate != null; candidate = candidate.getCause()) {
            if (candidate instanceof final FatalProcessingException abend) {
                return Optional.of(abend);
            }
            if (candidate == candidate.getCause()) {
                break;
            }
        }
        return Optional.empty();
    }

    /**
     * Issues one data-definition statement on a connection of its own, with auto-commit turned on.
     *
     * <p><strong>Why this cannot be an ordinary {@code JdbcTemplate.execute} call.</strong>
     * {@code application.yml} sets {@code spring.datasource.hikari.auto-commit: false}, so a statement issued
     * with no surrounding transaction opens one that nobody commits, and the pool rolls it back the moment the
     * connection is returned. A rename issued that way appears to succeed, is silently undone, and the test
     * that depends on it then asserts against a perfectly readable relation - which is exactly the false pass
     * this method exists to prevent. The parent's own committed-state reset takes the same connection for the
     * same reason.
     *
     * @param statement the statement to issue
     */
    private void commitDdl(final String statement) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            try (Statement handle = connection.createStatement()) {
                handle.execute(statement);
            }
        } catch (final SQLException failure) {
            throw new IllegalStateException(
                    "The statement '" + statement + "' could not be issued, so this test cannot establish "
                            + "the state it asserts against. Fix the statement rather than skipping it.",
                    failure);
        }
    }

    /**
     * Reports whether a relation can currently be read at all.
     *
     * @param relation the relation to probe
     * @return {@code true} when a count succeeds, {@code false} when the relation cannot be reached
     */
    private boolean relationIsReadable(final String relation) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            try (Statement handle = connection.createStatement()) {
                handle.execute("SELECT count(*) FROM " + relation);
                return true;
            }
        } catch (final SQLException unreachable) {
            return false;
        }
    }

    /**
     * Returns a content digest of every business relation, keyed by relation name.
     *
     * <p>The digest is over the whole row rendered as text and ordered by that text, so it is stable across
     * physical row order and sensitive to any insert, update or delete.
     *
     * @return the digests, one per relation
     */
    private Map<String, String> relationDigests() {
        final Map<String, String> digests = new LinkedHashMap<>();
        for (final String relation : BUSINESS_RELATIONS) {
            final String digest = jdbcTemplate.queryForObject(
                    "SELECT coalesce(md5(string_agg(r::text, '|' ORDER BY r::text)), 'empty') FROM "
                            + relation + " r",
                    String.class);
            digests.put(relation, digest);
        }
        return digests;
    }

    /**
     * Returns the greatest value of a numeric key column, which a completed keyset scan must have reached.
     *
     * @param relation the relation to read
     * @param keyColumn the key column
     * @return the greatest key value
     */
    private long highestKey(final String relation, final String keyColumn) {
        final Long highest = jdbcTemplate.queryForObject(
                "SELECT max(" + keyColumn + ") FROM " + relation, Long.class);
        assertThat(highest).as("%s.%s must be seeded for this assertion to mean anything", relation,
                keyColumn).isNotNull();
        return highest.longValue();
    }

    /**
     * Returns every object key currently present in a bucket.
     *
     * @param bucket the bucket to list
     * @return the keys, which the parent's reset leaves empty before each test
     */
    private List<String> objectKeys(final String bucket) {
        return objectStore.listObjectsV2(request -> request.bucket(bucket)).contents().stream()
                .map(object -> object.key())
                .toList();
    }
}

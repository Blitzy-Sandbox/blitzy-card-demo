/*
 * ******************************************************************
 * Program     : BatchHarnessIsolationTest.java
 * Component   : Integration test tier, resident at
 *               src/test/java/com/cardemo/integration/batch
 * Application : CardDemo
 * Type        : JUnit 5 integration test - Testcontainers PostgreSQL 16
 *               and LocalStack, real Spring context, Failsafe bound by
 *               path
 * Function    : Proves the two isolation guarantees the batch harness
 *               makes, against the real containers rather than by
 *               inspection. First, that the committed-state reset is
 *               live: a row committed outside the test transaction is
 *               gone by the next test, Spring Batch metadata is empty at
 *               the start of every test, and the money columns a job can
 *               change are restored from the frozen fixtures. Second,
 *               that the unique identifying run parameter is mandatory:
 *               parameter assembly and job launch both refuse to proceed
 *               without it, and the value supplied is derived from the
 *               test's name rather than from a clock or a random source.
 * Source      : app/jcl/POSTTRAN.jcl, app/jcl/INTCALC.jcl (the jobs
 *               whose commits this reset undoes), app/data/ASCII/
 *               acctdata.txt and tcatbal.txt (the seeded money values
 *               restored), app/csd/CARDDEMO.CSD:499-505 (the queue the
 *               object store and FIFO bridge replace),
 *               app/cbl/CBACT04C.cbl:1-21 (banner convention)
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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 * ******************************************************************
 */
package com.cardemo.integration.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.cardemo.unit.model.FixtureLoader;

/**
 * Proves the batch harness's isolation and determinism guarantees against the real containers.
 *
 * <h2>What it does</h2>
 *
 * <p>The harness makes two promises that no amount of reading can verify: that everything a launched job
 * commits is undone before the next test sees it, and that no launch can happen without a unique identifying
 * run parameter. Both are the difference between an assertion about the code under test and an assertion
 * about which tests happened to run first, so both are executed here rather than assumed.
 *
 * <p>The reset proof is necessarily ordered, and deliberately so: one test commits a row <em>outside</em> the
 * class-level transaction, exactly as a launched job does, and a second test - guaranteed by
 * {@link org.junit.jupiter.api.MethodOrderer.OrderAnnotation} to run after it - asserts the row is gone. A
 * single unordered test cannot express that, because the thing being proved is precisely what one test can
 * still observe of another. Ordering here is therefore evidence, not a dependency the production code has.
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>{@code ./mvnw -B -ntp -Ddependency-check.skip=true verify} runs it with the tier;
 * {@code ./mvnw -B -ntp -Dit.test=BatchHarnessIsolationTest verify} runs it alone. A reachable Docker socket
 * is a prerequisite: the harness starts a real PostgreSQL 16 container and a real LocalStack container, and
 * an in-memory substitute for either would prove nothing - an in-memory database would not validate the
 * PostgreSQL schema and an in-memory queue would not exercise FIFO semantics.
 *
 * <h2>Key configs and defaults</h2>
 *
 * <p>None of its own. Every setting - the {@code test} profile, both image tags, the injected connection
 * details, the endpoint overrides, the fixed clock - is declared once by the harness. No host name, port,
 * bucket address, queue address, credential or JDBC URL appears here.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><em>{@code theResetRemovedWhatThePreviousTestCommitted} fails.</em> The reset hook was removed,
 *       narrowed, or made conditional on something other than an active transaction. Restore it; the whole
 *       tier's determinism rests on it.</li>
 *   <li><em>A run-identifier refusal test fails.</em> The guard was removed from
 *       {@code jobParameters} or from {@code launchJob}. Both are needed: the first covers the normal path,
 *       the second covers a subclass that assembles parameters through the builder directly.</li>
 *   <li><em>Every test fails with a container or Docker error.</em> There is no reachable Docker socket.
 *       State the blocker rather than asserting an untested pass.</li>
 *   </ul>
 *
 * <h2>Thread safety and side effects</h2>
 *
 * <p>One test commits one row on purpose, outside the class-level transaction, because a committed row is the
 * only honest stand-in for what a launched job leaves behind. That row is removed by the very mechanism under
 * test, and the following test asserts its absence, so the class leaves the database exactly as it found it -
 * which is itself the assertion.
 *
 * <h2>Method ordering</h2>
 *
 * <p><strong>ORDERING IS DELIBERATE.</strong> The subject of this class is a between-test mechanism, so the
 * assertion cannot live inside one test: one method commits the row and the <em>next</em> method asserts the
 * reset removed it. Reversing them would assert absence before anything had been written, which passes
 * vacuously. This is the one place in the test tree where a method depends on the method before it, and it is
 * declared here so that ordering elsewhere - where it only hides coupling - stays refusable.
 */
@DisplayName("Batch harness: committed-state reset and the mandatory unique run identifier")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BatchHarnessIsolationTest extends AbstractBatchIntegrationTest {

    /** Identifier of the account whose row this class commits and then expects to be restored. */
    private static final long PROBE_ACCOUNT_ID = 1L;

    /** A value no seeded account carries, so its presence can only mean the reset did not run. */
    private static final BigDecimal PROBE_BALANCE = new BigDecimal("-987654.32");

    /** The datasource, used to commit and to observe outside the class-level transaction. */
    @Autowired
    private DataSource dataSource;

    @Test
    @Order(1)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("1. the reset leaves the seeded starting point in place before every test")
    void theResetLeavesTheSeededStartingPointInPlace() throws SQLException {
        // The reset ran in @BeforeEach. Everything below is what it guarantees a test may rely on.
        assertThat(countRows("\"transaction\""))
                .as("V3 seeds the transaction relation empty; the posting job is what fills it")
                .isZero();
        assertThat(countRows("batch_job_instance"))
                .as("the job repository is emptied per test, so an execution count is meaningful")
                .isZero();
        assertThat(countRows("batch_job_execution")).isZero();
        assertThat(countRows("batch_step_execution")).isZero();
        assertThat(countRows("account")).as("no seeded row is ever deleted").isEqualTo(50L);
        assertThat(countRows("transaction_category_balance")).isEqualTo(50L);
        assertThat(countRows("daily_transaction")).isEqualTo(300L);

        assertThat(currentBalanceOf(PROBE_ACCOUNT_ID))
                .as("the first account's seeded balance, decoded from acctdata.txt columns 13-24")
                .isEqualByComparingTo(seededBalanceOfFirstAccount());
    }

    @Test
    @Order(2)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("2. a value committed outside the test transaction really is committed and visible")
    void aValueCommittedOutsideTheTestTransactionIsVisible() throws SQLException {
        // This is the stand-in for a launched job: it commits on its own connection, so the class-level
        // rollback has no hold on it. If this test did not genuinely commit, the next test would prove
        // nothing.
        commitProbeBalance();

        assertThat(currentBalanceOf(PROBE_ACCOUNT_ID))
                .as("committed on its own connection, exactly as a Spring Batch chunk commits")
                .isEqualByComparingTo(PROBE_BALANCE);
    }

    @Test
    @Order(3)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("3. the reset removed what the previous test committed, restoring the fixture value")
    void theResetRemovedWhatThePreviousTestCommitted() throws SQLException {
        // The assertion the whole hook exists for. Without the reset this reads -987654.32 and every later
        // assertion in the tier would be measuring the previous test instead of the code under test.
        assertThat(currentBalanceOf(PROBE_ACCOUNT_ID))
                .as("restored from acctdata.txt, not left at what test 2 committed")
                .isEqualByComparingTo(seededBalanceOfFirstAccount())
                .isNotEqualByComparingTo(PROBE_BALANCE);
    }

    @Test
    @Order(4)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("4. the reset also restores every category balance from tcatbal.txt")
    void theResetRestoresEveryCategoryBalance() throws SQLException {
        final FixtureLoader.FixtureData balances =
                FixtureLoader.load(FixtureLoader.Fixture.TRANSACTION_CATEGORY_BALANCE);

        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet stored = statement.executeQuery(
                        "SELECT acct_id, tran_type_cd, tran_cat_cd, tran_cat_bal "
                                + "FROM transaction_category_balance "
                                + "ORDER BY acct_id, tran_type_cd, tran_cat_cd")) {
            int rows = 0;
            while (stored.next()) {
                final BigDecimal expected = balances.signedDecimal(rows, 18,
                        FixtureLoader.AMOUNT_FIELD_WIDTH);
                assertThat(stored.getBigDecimal("tran_cat_bal"))
                        .as("row %d of tcatbal.txt", rows)
                        .isEqualByComparingTo(expected);
                rows++;
            }

            assertThat(rows).isEqualTo(50);
        }
    }

    @Test
    @Order(5)
    @DisplayName("5. the reset is skipped, correctly, while the test transaction is active")
    void theResetIsSkippedWhileTheTestTransactionIsActive() {
        // This method is transactional, so nothing it does is committed and there is nothing committed to
        // restore. Calling the reset explicitly must therefore be a no-op rather than an error: it must not
        // enlist its statements in a transaction that is about to roll back.
        resetCommittedState();
        resetCommittedState();

        assertThat(runId()).isNotBlank();
    }

    @Test
    @Order(6)
    @DisplayName("6. the run identifier is derived from the test's own name, never from a clock")
    void theRunIdentifierIsDerivedFromTheTestName() {
        // A timestamp or a random value would also be unique and would also be irreproducible: the job
        // instance would differ between runs and a failure could not be re-executed against the same one.
        assertThat(runId()).isEqualTo("BatchHarnessIsolationTest.theRunIdentifierIsDerivedFromTheTestName");
        assertThat(runId()).isEqualTo(runId());
        assertThat(cloudNamespace())
                .isEqualTo("it/BatchHarnessIsolationTest.theRunIdentifierIsDerivedFromTheTestName/");
        assertThat(cloudNamespace()).endsWith("/");
    }

    @Test
    @Order(7)
    @DisplayName("7. runIdParameters stamps the identifier in and keeps the job's own parameters")
    void runIdParametersStampsTheIdentifierIn() {
        final Map<String, String> own = new LinkedHashMap<>();
        own.put("carddemo.parmDate", "2022061000");

        final JobParameters assembled = runIdParameters(own);

        assertThat(assembled.getString("carddemo.parmDate"))
                .as("the ten-character linkage parameter of app/cbl/CBACT04C.cbl:175-180 is untouched")
                .isEqualTo("2022061000");
        assertThat(assembled.getString(RUN_ID_PARAMETER)).isEqualTo(runId());
        assertThat(assembled.getParameters()).hasSize(2);
        assertThat(assembled.getParameters().get(RUN_ID_PARAMETER).isIdentifying())
                .as("only an identifying parameter contributes to the job instance")
                .isTrue();
    }

    @Test
    @Order(8)
    @DisplayName("8. runIdParameters works for a job that takes no parameters of its own")
    void runIdParametersWorksForAJobWithNoParametersOfItsOwn() {
        final JobParameters assembled = runIdParameters(Map.of());

        assertThat(assembled.getParameters()).hasSize(1);
        assertThat(assembled.getString(RUN_ID_PARAMETER)).isEqualTo(runId());
    }

    @Test
    @Order(9)
    @DisplayName("9. runIdParameters refuses to overwrite an identifier the caller already supplied")
    void runIdParametersRefusesToOverwriteASuppliedIdentifier() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> runIdParameters(Map.of(RUN_ID_PARAMETER, "already-set")))
                .withMessageContaining("already carries")
                .withMessageContaining(RUN_ID_PARAMETER);
    }

    @Test
    @Order(10)
    @DisplayName("10. jobParameters refuses a map that omits the identifying run parameter")
    void jobParametersRefusesAMapWithoutTheRunIdentifier() {
        final IllegalArgumentException refused = catchThrowableOfType(IllegalArgumentException.class,
                () -> jobParameters(Map.of("carddemo.parmDate", "2022061000")));

        assertThat(refused).isNotNull();
        assertThat(refused).hasMessageContaining(RUN_ID_PARAMETER)
                .hasMessageContaining("instance-already-complete")
                .hasMessageContaining("runIdParameters");
        assertThat(refused.getMessage())
                .as("Clause D: a parameter value never reaches a message")
                .doesNotContain("2022061000");
    }

    @Test
    @Order(11)
    @DisplayName("11. jobParameters refuses a blank run identifier as firmly as an absent one")
    void jobParametersRefusesABlankRunIdentifier() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> jobParameters(Map.of(RUN_ID_PARAMETER, "   ")))
                .withMessageContaining(RUN_ID_PARAMETER);
    }

    @Test
    @Order(12)
    @DisplayName("12. jobParameters still refuses a blank name and a null value")
    void jobParametersStillRefusesABlankNameAndANullValue() {
        final Map<String, String> blankName = new LinkedHashMap<>();
        blankName.put(RUN_ID_PARAMETER, runId());
        blankName.put("  ", "value");
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> jobParameters(blankName))
                .withMessageContaining("neither null nor blank");

        final Map<String, String> nullValue = new LinkedHashMap<>();
        nullValue.put(RUN_ID_PARAMETER, runId());
        nullValue.put("carddemo.parmDate", null);
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> jobParameters(nullValue))
                .withMessageContaining("has a null value");
    }

    @Test
    @Order(13)
    @DisplayName("13. jobParameters is order-independent, so a hash-ordered map cannot change the instance")
    void jobParametersIsOrderIndependent() {
        final Map<String, String> oneOrder = new LinkedHashMap<>();
        oneOrder.put("b", "2");
        oneOrder.put(RUN_ID_PARAMETER, runId());
        oneOrder.put("a", "1");

        final Map<String, String> otherOrder = new LinkedHashMap<>();
        otherOrder.put("a", "1");
        otherOrder.put("b", "2");
        otherOrder.put(RUN_ID_PARAMETER, runId());

        assertThat(jobParameters(oneOrder)).isEqualTo(jobParameters(otherOrder));
    }

    @Test
    @Order(14)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("14. launchJob refuses parameters assembled without the identifier, bypassing jobParameters")
    void launchJobRefusesParametersAssembledWithoutTheIdentifier() {
        // The bypass route: JobParametersBuilder directly, which never passes through jobParameters(...) and
        // would otherwise reintroduce the ordering coupling. The job is never reached, so no job bean is
        // needed and nothing is committed.
        final JobParameters bypassed =
                new JobParametersBuilder().addString("carddemo.parmDate", "2022061000").toJobParameters();

        final IllegalStateException refused = catchThrowableOfType(IllegalStateException.class,
                () -> launchJob(new NeverRunJob(), bypassed));

        assertThat(refused).isNotNull();
        assertThat(refused).hasMessageContaining(RUN_ID_PARAMETER)
                .hasMessageContaining("never-run")
                .hasMessageContaining("runIdParameters");
    }

    @Test
    @Order(15)
    @DisplayName("15. launchJob still refuses to run while the test transaction is active")
    void launchJobStillRefusesWhileTheTestTransactionIsActive() {
        // This method is transactional on purpose. The transaction guard has to fire before the run-identifier
        // guard would, because Spring Batch refuses job repository work inside an existing transaction and
        // the resulting failure would otherwise surface several frames inside a JDK proxy.
        final IllegalStateException refused = catchThrowableOfType(IllegalStateException.class,
                () -> launchJob(new NeverRunJob(), runIdParameters(Map.of())));

        assertThat(refused).isNotNull();
        assertThat(refused).hasMessageContaining("cannot be launched while a transaction is active")
                .hasMessageContaining("NOT_SUPPORTED");
    }

    @Test
    @Order(16)
    @DisplayName("16. the delegated fixture reader is live in this tier too")
    void theDelegatedFixtureReaderIsLive() {
        assertThat(readFixture("dailytran.txt")).hasSize(300);
        assertThat(readFixture("acctdata.txt").getFirst()).hasSize(300);
        assertThat(readFixture("tcatbal.txt")).hasSize(50);
    }

    @Test
    @Order(17)
    @DisplayName("17. the injected clock is the fixed fixture instant, so no assertion reads a moving clock")
    void theInjectedClockIsTheFixedFixtureInstant() {
        assertThat(fixedInstant().toString()).isEqualTo("2022-06-10T19:27:53Z");
        assertThat(clock().instant()).isEqualTo(fixedInstant());
    }

    /**
     * Reads the current balance of one account on a fresh connection, outside any test transaction.
     *
     * @param accountId the account to read
     * @return the stored balance
     * @throws SQLException if the read cannot be issued
     */
    private BigDecimal currentBalanceOf(final long accountId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet stored = statement.executeQuery(
                        "SELECT acct_curr_bal FROM account WHERE acct_id = " + accountId)) {
            assertThat(stored.next()).isTrue();
            return stored.getBigDecimal(1);
        }
    }

    /**
     * Commits a recognisable balance on its own auto-committing connection, standing in for a job's commit.
     *
     * @throws SQLException if the update cannot be issued
     */
    private void commitProbeBalance() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            connection.setAutoCommit(true);
            statement.executeUpdate("UPDATE account SET acct_curr_bal = " + PROBE_BALANCE
                    + " WHERE acct_id = " + PROBE_ACCOUNT_ID);
        }
    }

    /**
     * Counts the rows of one relation on a fresh connection, outside any test transaction.
     *
     * @param relation the relation name, already quoted where the identifier requires it
     * @return the row count
     * @throws SQLException if the count cannot be issued
     */
    private long countRows(final String relation) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet counted = statement.executeQuery("SELECT COUNT(*) FROM " + relation)) {
            assertThat(counted.next()).isTrue();
            return counted.getLong(1);
        }
    }

    /**
     * Returns the first account's seeded balance, decoded from the frozen fixture rather than typed here.
     *
     * @return the value {@code acctdata.txt} carries at columns 13-24 of its first record
     */
    private static BigDecimal seededBalanceOfFirstAccount() {
        return FixtureLoader.load(FixtureLoader.Fixture.ACCOUNT)
                .signedDecimal(0, 13, FixtureLoader.MONEY_FIELD_WIDTH);
    }

    /**
     * A job that exists only to be refused.
     *
     * <p>Both guards in {@code launchJob} fire before the launcher is reached, so the two tests that assert
     * them need a {@code Job} reference that is never executed. Using a real job bean would couple those
     * assertions to whichever jobs happen to be declared, and would risk actually running one; this type
     * carries a name and nothing else, and its {@code execute} method states that reaching it is itself the
     * defect.
     */
    private static final class NeverRunJob implements org.springframework.batch.core.Job {

        /** The name the refusal message is expected to quote. */
        private static final String NAME = "never-run";

        /**
         * Sole constructor; there is no state to initialise.
         */
        NeverRunJob() {
            // Intentionally empty.
        }

        @Override
        public String getName() {
            return NAME;
        }

        @Override
        public boolean isRestartable() {
            return false;
        }

        @Override
        public void execute(final org.springframework.batch.core.JobExecution execution) {
            throw new AssertionError("the guards in launchJob must refuse before the launcher is reached, "
                    + "so this job must never be executed");
        }

        @Override
        public org.springframework.batch.core.JobParametersIncrementer getJobParametersIncrementer() {
            return null;
        }

        @Override
        public org.springframework.batch.core.JobParametersValidator getJobParametersValidator() {
            return parameters -> {
                // Nothing to validate: this job is never launched.
            };
        }
    }
}

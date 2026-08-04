/*
 * ******************************************************************
 * Program     : DailyTransactionPostingJobContextLifecycleTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Verifies two properties of the daily transaction
 *               posting job that are invisible to its behavioural
 *               tests because neither shows up in a job's result.
 *
 *               First, the diagnostic context lifecycle. The job
 *               displaces two MDC entries for the duration of a run
 *               and must put back exactly what it found. Held in a
 *               single per-thread slot that is correct only while no
 *               second execution can begin on a thread that already
 *               has one in progress; the orchestrated pipeline makes
 *               that reachable, and a single slot then loses the
 *               outer scope's identifier permanently. These tests
 *               drive the nested case directly.
 *
 *               Second, diagnostic privacy. The pre-flight reproduces
 *               two COBOL DISPLAY statements that print an account
 *               number and a cross-reference triple in full. The
 *               values must not reach any log event at any level, on
 *               either the application logger or the parity logger,
 *               while the surrounding literals stay byte-exact.
 * Source      : app/cbl/CBTRN01C.cbl:L177-L179 (DISPLAY 'ACCOUNT '
 *                                               ACCT-ID ' NOT FOUND')
 *               app/cbl/CBTRN01C.cbl:L234-L238 (SUCCESSFUL READ OF
 *                                               XREF, then the card,
 *                                               account and customer
 *                                               identifiers)
 *               app/cpy/CVACT01Y.cpy           (ACCT-ID PIC 9(11))
 *               app/cpy/CVCUS01Y.cpy           (CUST-ID PIC 9(09))
 *               app/cpy/CVACT03Y.cpy           (the cross-reference
 *                                               triple)
 * ******************************************************************
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
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 * ******************************************************************
 */

package com.cardemo.unit.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cardemo.batch.jobs.DailyTransactionPostingJob;
import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.observability.CorrelationIdFilter;
import com.cardemo.observability.MetricsConfig;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.CardRepository;
import com.cardemo.repository.CustomerRepository;
import com.cardemo.repository.DailyTransactionRepository;
import com.cardemo.repository.TransactionCategoryBalanceRepository;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.service.shared.FileStatusMapper;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The diagnostic context lifecycle and the diagnostic privacy of {@link DailyTransactionPostingJob}.
 *
 * <p>Both properties are reached by reflection, because both live behind private members and neither is
 * observable from the job's result. That is the point of testing them: a job whose context bookkeeping is
 * wrong still returns {@code COMPLETED}, and a job that prints an account number still posts every record
 * correctly. Nothing in the behavioural suites can fail on either defect.
 */
@DisplayName("DailyTransactionPostingJob - diagnostic context lifecycle and diagnostic privacy")
class DailyTransactionPostingJobContextLifecycleTest {

    /** An eleven-digit account identifier, the width of {@code ACCT-ID} at {@code app/cpy/CVACT01Y.cpy}. */
    private static final Long ACCOUNT_ID = 10000000123L;

    /** A nine-digit customer identifier, the width of {@code CUST-ID} at {@code app/cpy/CVCUS01Y.cpy}. */
    private static final Long CUSTOMER_ID = 100000456L;

    /** A sixteen-digit card number. */
    private static final String CARD_NUMBER = "4111111111117890";

    /** The parity logger the pre-flight routes its record-level emissions to. */
    private static final String PARITY_LOGGER_NAME = "com.cardemo.parity.CBTRN01C";

    /** Captures every event both loggers emit, at every level. */
    private ListAppender<ILoggingEvent> logEvents;

    /** The application logger of the class under test, captured at {@link Level#TRACE}. */
    private Logger capturedLogger;

    /** The parity logger, captured separately because its name is not a descendant of the class logger. */
    private Logger capturedParityLogger;

    /** Attaches the in-memory appender to both loggers at {@code TRACE}. */
    @BeforeEach
    void captureLogs() {
        this.logEvents = new ListAppender<>();
        this.logEvents.start();

        this.capturedLogger = (Logger) LoggerFactory.getLogger(DailyTransactionPostingJob.class);
        this.capturedLogger.addAppender(this.logEvents);
        this.capturedLogger.setLevel(Level.TRACE);

        // TRACE on the parity logger too. It is pinned OFF in every shipped profile, so capturing it at
        // TRACE is deliberately the worst case: it proves the values are safe even for the operator who
        // turns parity output on to diagnose an incident, which is exactly when it would be turned on.
        this.capturedParityLogger = (Logger) LoggerFactory.getLogger(PARITY_LOGGER_NAME);
        this.capturedParityLogger.addAppender(this.logEvents);
        this.capturedParityLogger.setLevel(Level.TRACE);
    }

    /** Detaches the appender and clears any diagnostic context the tests left behind. */
    @AfterEach
    void releaseLogs() {
        this.capturedParityLogger.detachAppender(this.logEvents);
        this.capturedParityLogger.setLevel(null);
        this.capturedLogger.detachAppender(this.logEvents);
        this.capturedLogger.setLevel(null);
        MDC.clear();
    }

    // ==================================================================
    // 1 - The diagnostic context lifecycle, including the nested case
    //     that a single per-thread slot cannot represent.
    // ==================================================================

    @Nested
    @DisplayName("1. The displaced diagnostic context is restored per invocation, and nests")
    class DiagnosticContextLifecycle {

        @Test
        @DisplayName("an absent context is restored to absent, so nothing leaks onto a pooled thread")
        void anAbsentContextIsRestoredToAbsent() throws Exception {
            assertThat(CorrelationIdFilter.currentCorrelationId())
                    .as("precondition: this thread starts with no correlation identifier")
                    .isNull();

            establish(1L, 100L);
            assertThat(CorrelationIdFilter.currentCorrelationId())
                    .as("the job generates one when the thread has none, so the run's lines correlate")
                    .isNotNull();

            restore(100L);
            assertThat(CorrelationIdFilter.currentCorrelationId())
                    .as("and it must be removed rather than left behind. A pooled thread outlives the job, "
                            + "so a value left here is attributed to whatever runs next")
                    .isNull();
        }

        @Test
        @DisplayName("a pre-existing context survives: an inherited identifier is put back, not regenerated")
        void aPreExistingContextIsRestoredExactly() throws Exception {
            CorrelationIdFilter.propagate("outer-correlation-id");

            establish(1L, 100L);
            assertThat(CorrelationIdFilter.currentCorrelationId())
                    .as("a job launched from inside a traced request keeps that request's identifier, so "
                            + "the whole causal chain shares one")
                    .isEqualTo("outer-correlation-id");

            restore(100L);
            assertThat(CorrelationIdFilter.currentCorrelationId())
                    .as("and the outer value is still there afterwards. Restoring is not the same as "
                            + "removing, and a blanket removal would satisfy the previous test while "
                            + "silently failing this one")
                    .isEqualTo("outer-correlation-id");
        }

        @Test
        @DisplayName("NESTED: an inner execution's restore does not consume the outer execution's snapshot")
        void aNestedExecutionRestoresBothLevels() throws Exception {
            CorrelationIdFilter.propagate("outer-correlation-id");
            CorrelationIdFilter.propagateJobInstanceId("7");

            establish(1L, 100L);
            CorrelationIdFilter.propagate("inner-correlation-id");
            establish(2L, 200L);

            restore(200L);
            assertThat(CorrelationIdFilter.currentCorrelationId())
                    .as("the inner restore puts back what the INNER establish displaced, which is the "
                            + "identifier in force when it ran")
                    .isEqualTo("inner-correlation-id");

            restore(100L);
            assertThat(CorrelationIdFilter.currentCorrelationId())
                    .as("and the outer restore then puts back the outer value. This is the assertion a "
                            + "single per-thread slot cannot satisfy: the inner establish would have "
                            + "overwritten the outer's snapshot and the inner restore would have removed "
                            + "it, leaving this restore with nothing to put back and the thread carrying "
                            + "the inner identifier for the rest of its life")
                    .isEqualTo("outer-correlation-id");
            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY_JOB_INSTANCE_ID))
                    .as("and the job instance entry nests identically, because both are displaced together")
                    .isEqualTo("7");
        }

        @Test
        @DisplayName("the per-thread stack is discarded once empty, so no bookkeeping is retained")
        void theStackIsDiscardedWhenEmpty() throws Exception {
            establish(1L, 100L);
            establish(2L, 200L);
            assertThat(displacedStackDepth())
                    .as("two executions in progress on this thread means two displacements held")
                    .isEqualTo(2);

            restore(200L);
            restore(100L);
            assertThat(displacedStackDepth())
                    .as("the ThreadLocal itself must be removed once its last entry is popped. Leaving an "
                            + "empty deque attached would retain a container on every pooled thread that "
                            + "has ever run this job")
                    .isZero();
        }

        @Test
        @DisplayName("a restore with nothing displaced is a no-op, not a failure")
        void anUnpairedRestoreIsANoOp() throws Exception {
            CorrelationIdFilter.propagate("untouched");

            restore(100L);

            assertThat(CorrelationIdFilter.currentCorrelationId())
                    .as("reachable rather than defensive: if the listener abends before the push completes, "
                            + "this thread's context was never modified, so there is nothing to undo and "
                            + "nothing to damage")
                    .isEqualTo("untouched");
        }

        @Test
        @DisplayName("an out-of-order restore is reported rather than silently restoring the wrong run")
        void anOutOfOrderRestoreIsReported() throws Exception {
            establish(1L, 100L);

            restore(999L);

            assertThat(capturedLogText())
                    .as("no supported launcher interleaves pushes and pops, so a mismatch means the "
                            + "invariant is broken. Restoring silently would attribute one run's "
                            + "correlation identifier to another and leave nothing to diagnose it with")
                    .contains("out of order")
                    .contains("999")
                    .contains("100");
            assertThat(displacedStackDepth())
                    .as("and the entry is still popped, because leaving it would strand it on the thread")
                    .isZero();
        }
    }

    // ==================================================================
    // 2 - Diagnostic privacy: the two pre-flight emissions that print
    //     identifiers in the source must not print them here.
    // ==================================================================

    @Nested
    @DisplayName("2. No account or customer identifier reaches any log event at any level")
    class DiagnosticPrivacy {

        @Test
        @DisplayName("'ACCOUNT ... NOT FOUND' keeps its literal but not the eleven-digit identifier")
        void theMissingAccountWarningCarriesNoIdentifier() throws Exception {
            final DailyTransactionPostingJob job = jobWith(Optional.of(crossReference()), Optional.empty());

            verifyRecord(job, true, false);

            final String text = capturedLogText();
            assertThat(text)
                    .as("the emission itself must survive: app/cbl/CBTRN01C.cbl:L177-L179 displays it, and "
                            + "a referential gap in the master is an operational event an operator needs")
                    .contains("NOT FOUND");
            assertThat(text)
                    .as("but the account number is not the part that carries the operational meaning. An "
                            + "account number is what a cardholder quotes to be recognised")
                    .doesNotContain(Long.toString(ACCOUNT_ID));
            assertThat(text)
                    .as("and the masked projection keeps the last four digits, so two lines about the same "
                            + "account can still be correlated")
                    .contains("0123");
        }

        @Test
        @DisplayName("the cross-reference triple masks all three identifiers, not only the card number")
        void theCrossReferenceTripleMasksEveryIdentifier() throws Exception {
            final DailyTransactionPostingJob job =
                    jobWith(Optional.of(crossReference()), Optional.of(account()));

            verifyRecord(job, true, true);

            final String text = capturedLogText();
            assertThat(text)
                    .as("the cross-reference record is the one line where a card number, an account number "
                            + "and a customer number appear together, so an unmasked pair here re-links "
                            + "exactly what masking the card number is meant to break")
                    .doesNotContain(CARD_NUMBER)
                    .doesNotContain(Long.toString(ACCOUNT_ID))
                    .doesNotContain(Long.toString(CUSTOMER_ID));
        }

        @Test
        @DisplayName("a null identifier is reported as absent, never as the literal 'null'")
        void anAbsentIdentifierIsNamedAsAbsent() throws Exception {
            assertThat(maskIdentifier(null))
                    .as("a log line reading 'ACCOUNT null NOT FOUND' is a defect report about the logger "
                            + "rather than about the account")
                    .isEqualTo("(absent)")
                    .doesNotContain("null");
        }

        @Test
        @DisplayName("a value no longer than the visible suffix is masked in full rather than exposed")
        void aShortIdentifierIsMaskedEntirely() throws Exception {
            assertThat(maskIdentifier(42L))
                    .as("keeping a last-four projection of a value that is itself four digits or fewer "
                            + "would publish the whole value, so the projection must collapse instead")
                    .isEqualTo("**")
                    .doesNotContain("42");
        }

        @Test
        @DisplayName("both identifier widths are masked to their last four digits")
        void bothIdentifierWidthsAreMasked() throws Exception {
            assertThat(maskIdentifier(ACCOUNT_ID))
                    .as("ACCT-ID is PIC 9(11): seven masked, four visible")
                    .isEqualTo("*******0123");
            assertThat(maskIdentifier(CUSTOMER_ID))
                    .as("CUST-ID is PIC 9(09): five masked, four visible. Masked at the call site because "
                            + "logback-spring.xml deliberately has no nine-digit rule - one would also "
                            + "redact the PIC 9(09) end-of-run counters")
                    .isEqualTo("*****0456");
        }
    }

    // ==================================================================
    // Reflective access. Both concerns live behind private members, and
    // exposing them for a test would weaken the class to observe it.
    // ==================================================================

    /**
     * Invokes {@code establishDiagnosticContext}.
     *
     * @param jobInstanceId the instance identifier to publish
     * @param jobExecutionId the execution identifier to record against the displacement
     * @throws Exception if the member cannot be reached, which is itself a failure worth surfacing
     */
    private static void establish(final long jobInstanceId, final long jobExecutionId) throws Exception {
        invokeStatic("establishDiagnosticContext",
                new Class<?>[] {long.class, long.class}, jobInstanceId, jobExecutionId);
    }

    /**
     * Invokes {@code restoreDiagnosticContext}.
     *
     * @param jobExecutionId the execution identifier the restore claims to be undoing
     * @throws Exception if the member cannot be reached
     */
    private static void restore(final long jobExecutionId) throws Exception {
        invokeStatic("restoreDiagnosticContext", new Class<?>[] {long.class}, jobExecutionId);
    }

    /**
     * Invokes {@code maskIdentifier}.
     *
     * @param identifier the value to mask, possibly {@code null}
     * @return the masked projection
     * @throws Exception if the member cannot be reached
     */
    private static String maskIdentifier(final Long identifier) throws Exception {
        return (String) invokeStatic("maskIdentifier", new Class<?>[] {Long.class}, identifier);
    }

    /**
     * Invokes a private static member of the class under test.
     *
     * @param name the member name
     * @param parameterTypes its parameter types
     * @param arguments the arguments to pass
     * @return whatever the member returned
     * @throws Exception if the member is absent or throws
     */
    private static Object invokeStatic(
            final String name, final Class<?>[] parameterTypes, final Object... arguments)
            throws Exception {

        final Method method = DailyTransactionPostingJob.class.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        try {
            return method.invoke(null, arguments);
        } catch (final InvocationTargetException wrapped) {
            if (wrapped.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw wrapped;
        }
    }

    /**
     * Reads the depth of the per-thread displaced-context stack.
     *
     * @return the number of displacements held for this thread, zero when the holder itself is absent
     * @throws Exception if the field cannot be reached
     */
    @SuppressWarnings("unchecked")
    private static int displacedStackDepth() throws Exception {
        final java.lang.reflect.Field field =
                DailyTransactionPostingJob.class.getDeclaredField("DIAGNOSTIC_SNAPSHOTS");
        field.setAccessible(true);
        final ThreadLocal<java.util.Deque<?>> holder = (ThreadLocal<java.util.Deque<?>>) field.get(null);
        final java.util.Deque<?> displaced = holder.get();
        return displaced == null ? 0 : displaced.size();
    }

    /**
     * Invokes the private {@code preFlightVerifyRecord} for one record, over a stated resolution.
     *
     * <p>The pre-flight resolves its window's cross-references and accounts once and then verifies each
     * record against that resolution, so which of the two lookups succeeded is an argument here rather than a
     * property of the stubs. The record's ordinal within the run is what a diagnostic names instead of the
     * record's own identifiers.
     *
     * @param job the job instance whose repositories are stubbed
     * @param crossReferenceResolves whether the window resolved this card number
     * @param accountPresent whether the window found the account the cross-reference names
     * @throws Exception if the member cannot be reached or the invocation throws
     */
    private static void verifyRecord(final DailyTransactionPostingJob job,
            final boolean crossReferenceResolves, final boolean accountPresent) throws Exception {

        final Class<?> lookups = Class.forName(
                "com.cardemo.batch.jobs.DailyTransactionPostingJob$PreFlightLookups");
        final java.lang.reflect.Constructor<?> resolution =
                lookups.getDeclaredConstructor(java.util.Map.class, java.util.Set.class);
        resolution.setAccessible(true);
        final Object resolved = resolution.newInstance(
                crossReferenceResolves
                        ? java.util.Map.of(CARD_NUMBER, crossReference())
                        : java.util.Map.of(),
                accountPresent ? java.util.Set.of(ACCOUNT_ID) : java.util.Set.of());
        final Method method = DailyTransactionPostingJob.class.getDeclaredMethod("preFlightVerifyRecord",
                com.cardemo.model.entity.DailyTransaction.class, long.class, lookups);
        method.setAccessible(true);
        try {
            method.invoke(job, dailyTransaction(), Long.valueOf(1L), resolved);
        } catch (final InvocationTargetException wrapped) {
            if (wrapped.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw wrapped;
        }
    }

    // ==================================================================
    // Fixtures.
    // ==================================================================

    /**
     * Builds the job with mocked collaborators and the two lookups the pre-flight performs stubbed.
     *
     * @param crossReference what the cross-reference lookup returns
     * @param account what the account lookup returns
     * @return a fully constructed job, never {@code null}
     */
    private static DailyTransactionPostingJob jobWith(
            final Optional<CardCrossReference> crossReference, final Optional<Account> account) {

        final CardCrossReferenceRepository crossReferences = mock(CardCrossReferenceRepository.class);
        when(crossReferences.findById(any())).thenReturn(crossReference);
        final AccountRepository accounts = mock(AccountRepository.class);
        when(accounts.findById(any())).thenReturn(account);

        return new DailyTransactionPostingJob(
                mock(JobRepository.class),
                mock(PlatformTransactionManager.class),
                mock(DailyTransactionRepository.class),
                mock(TransactionRepository.class),
                crossReferences,
                accounts,
                mock(TransactionCategoryBalanceRepository.class),
                mock(CardRepository.class),
                mock(CustomerRepository.class),
                mock(MetricsConfig.class),
                mock(FileStatusMapper.class),
                "posttran",
                100);
    }

    /**
     * The cross-reference record whose three identifiers the log must not carry.
     *
     * @return the record, never {@code null}
     */
    private static CardCrossReference crossReference() {
        return new CardCrossReference(CARD_NUMBER, CUSTOMER_ID, ACCOUNT_ID);
    }

    /**
     * An account record for the branch where the master read succeeds.
     *
     * @return the record, never {@code null}
     */
    private static Account account() {
        return new Account(ACCOUNT_ID, "Y", new BigDecimal("100.00"), new BigDecimal("5000.00"),
                new BigDecimal("500.00"), "2001-02-03", "2031-02-03", "2021-02-03",
                new BigDecimal("0.00"), new BigDecimal("0.00"), "98109", "DEFAULT");
    }

    /**
     * The daily transaction the pre-flight verifies, carrying the card number that drives both lookups.
     *
     * @return the record, never {@code null}
     */
    private static com.cardemo.model.entity.DailyTransaction dailyTransaction() {
        return new com.cardemo.model.entity.DailyTransaction(1L, "0000000000000001", "01", 1,
                "POS TERM", "PURCHASE AT MERCHANT", new BigDecimal("10.00"), 123456789L,
                "SAMPLE MERCHANT", "SAMPLE CITY", "12345", CARD_NUMBER,
                "2022-06-11 02:00:00.000000", "2022-06-11 02:00:00.000000");
    }

    /**
     * Renders every captured event as one searchable string - message, arguments and throwable chain.
     *
     * @return the concatenated log output, never {@code null}
     */
    private String capturedLogText() {
        final StringBuilder text = new StringBuilder(512);
        for (final ILoggingEvent event : this.logEvents.list) {
            text.append(event.getFormattedMessage()).append('\n');
            if (event.getArgumentArray() != null) {
                for (final Object argument : event.getArgumentArray()) {
                    text.append(argument).append('\n');
                }
            }
        }
        return text.toString();
    }
}

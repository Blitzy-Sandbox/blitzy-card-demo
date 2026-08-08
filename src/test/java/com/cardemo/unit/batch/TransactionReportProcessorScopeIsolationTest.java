/*
 * ******************************************************************
 * Program     : TransactionReportProcessorScopeIsolationTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Proves the report processor and its backup reader are
 *               owned by exactly one class, and that the reporting
 *               window and running accumulators are isolated per step
 *               execution. The processor carries nine mutable
 *               accumulators - the line counter, three running totals,
 *               the control break key, the first-time flag, the cross
 *               reference, the two header dates and the stale amount -
 *               so two overlapping executions sharing one instance
 *               would mix one report's totals into another's. Under
 *               finding F-008 neither class may carry @Component or
 *               @StepScope while TransactionReportJob constructs it
 *               with new: nothing would resolve the bean definitions
 *               and the annotations would document a binding that
 *               never happens.
 *               These tests assert the annotations are absent, that
 *               neither class is a component-scan candidate, that
 *               neither holds static mutable state, and that
 *               construction per execution isolates the window, the
 *               counters and the control break key both interleaved on
 *               one thread and concurrently across two.
 * Source      : app/cbl/CBTRN03C.cbl:L127-L137 (WS-PAGE-SIZE 20)
 *               app/cbl/CBTRN03C.cbl:L170-L206 (the read loop)
 *               app/cbl/CBTRN03C.cbl:L173-L178 (the date re-filter)
 *               app/cbl/CBTRN03C.cbl:L181-L188 (the control break)
 *               app/jcl/TRANREPT.jcl           (DATEPARM, 80 bytes)
 *               app/proc/TRANREPT.prc:STEP05R  (TRAN-PROC-DT,305,10)
 *               app/cpy/CVTRA05Y.cpy           (350-byte record)
 *                                                       @ 7756d89
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
package com.cardemo.unit.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cardemo.batch.processors.TransactionCombineProcessor;
import com.cardemo.batch.processors.TransactionReportProcessor;
import com.cardemo.batch.readers.CombinedTransactionReader;
import com.cardemo.batch.readers.DailyTransactionReader;
import com.cardemo.batch.readers.TransactionBackupReader;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Transaction;
import com.cardemo.model.entity.TransactionCategory;
import com.cardemo.model.entity.TransactionType;
import com.cardemo.model.key.TransactionCategoryId;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.TransactionCategoryRepository;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.repository.TransactionTypeRepository;
import com.cardemo.service.shared.FileStatusMapper;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.test.MetaDataInstanceFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.stereotype.Component;

/**
 * Verifies the ownership model of {@link TransactionReportProcessor} and
 * {@link TransactionBackupReader}, and that each report execution is isolated from every other.
 *
 * <p><strong>The ownership model, and why the assertions take this shape.</strong> Neither class may carry
 * {@code @Component} or {@code @StepScope} while its only consumer,
 * {@code com.cardemo.batch.jobs.TransactionReportJob}, constructs it with {@code new}: nothing would resolve
 * either bean, so the container would publish two definitions no production path uses and the annotations
 * would describe a lifecycle that never runs - two ownership models for one type, which is finding
 * <strong>F-008</strong>. The job is the declared single owner, so asserting {@code @Component},
 * {@code @StepScope} or the presence of a {@code scopedTarget.} bean definition would be asserting the
 * defect. This class asserts their absence instead.
 *
 * <p><strong>Why the isolation guarantee still needs a test.</strong> Removing a scope only moves the
 * guarantee; it does not weaken it, <em>provided</em> two things hold. First, the owner must construct one
 * instance per step execution - it does, inside each tasklet body. Second, and this is the part a reader
 * cannot verify by inspection, neither class may hold <strong>static mutable state</strong>, because a static
 * field is shared by every instance and would defeat per-execution construction exactly as a singleton scope
 * would. {@code StructuralOwnership.neitherClassHoldsStaticMutableState} asserts that directly over the
 * declared fields of both classes, which is a stronger and more durable statement than the annotation checks
 * it replaces.
 *
 * <p><strong>How the two executions overlap.</strong> The single-threaded case builds one processor per
 * execution and interleaves records between them, which is the shape a restart or a second launch takes. The
 * multi-threaded case holds both executions open simultaneously behind a latch, which is the shape a
 * partitioned or multi-threaded step takes and is what would expose shared static state.
 *
 * <p><strong>Side effects.</strong> None outside the test JVM: Mockito doubles for the three repositories, one
 * classpath scan over {@code com.cardemo.batch}, and a two-thread executor that is always shut down. No
 * Spring context, no database, no container, no network, and no thread-local to release - dropping
 * {@code StepSynchronizationManager} is itself a consequence of the model, because nothing resolves a scoped
 * bean any more.
 */
@DisplayName("TransactionReportProcessor - single ownership and per-execution isolation")
class TransactionReportProcessorScopeIsolationTest {

    /** The bean name Spring would derive from the processor, asserted absent from the scan candidates. */
    private static final String BEAN_NAME = "transactionReportProcessor";

    /** The package the component scan walks when proving neither class is a scan candidate. */
    private static final String SCANNED_PACKAGE = "com.cardemo.batch";

    /** A card number of the sixteen characters {@code TRAN-CARD-NUM PIC X(16)} declares. */
    private static final String CARD_A = "4111111111111111";

    /** A second card number, so the control break can be exercised. */
    private static final String CARD_B = "4222222222222222";

    /** TRANFILE. The per-record body never touches it, so a bare double is enough. */
    private TransactionRepository transactions;

    /** CARDXREF, resolving both test cards and nothing else. */
    private CardCrossReferenceRepository crossReferences;

    /** TRANTYPE, resolving the single type code these fixtures use. */
    private TransactionTypeRepository types;

    /** TRANCATG, resolving the single composite key these fixtures use. */
    private TransactionCategoryRepository categories;

    @BeforeEach
    void setUp() {
        // No Spring context and no step scope registration. Production constructs the processor directly
        // inside the STEP10R tasklet body, so the doubles are held as plain fields and handed to the
        // constructor exactly as TransactionReportJob hands it its repositories.
        transactions = mock(TransactionRepository.class);
        crossReferences = crossReferenceRepository();
        types = typeRepository();
        categories = categoryRepository();
    }

    /**
     * A cross-reference repository that resolves both test cards and nothing else.
     *
     * @return the double
     */
    private CardCrossReferenceRepository crossReferenceRepository() {
        CardCrossReferenceRepository repository = mock(CardCrossReferenceRepository.class);
        when(repository.findById(CARD_A))
                .thenReturn(Optional.of(new CardCrossReference(CARD_A, 1L, 11L)));
        when(repository.findById(CARD_B))
                .thenReturn(Optional.of(new CardCrossReference(CARD_B, 2L, 22L)));
        return repository;
    }

    /**
     * A transaction-type repository that resolves the single type code these fixtures use.
     *
     * @return the double
     */
    private TransactionTypeRepository typeRepository() {
        TransactionTypeRepository repository = mock(TransactionTypeRepository.class);
        when(repository.findAll()).thenReturn(List.of(new TransactionType("01", "PURCHASE")));
        return repository;
    }

    /**
     * A transaction-category repository that resolves the single composite key these fixtures use.
     *
     * @return the double
     */
    private TransactionCategoryRepository categoryRepository() {
        TransactionCategoryRepository repository = mock(TransactionCategoryRepository.class);
        when(repository.findAll()).thenReturn(List.of(new TransactionCategory(
                new TransactionCategoryId("01", 1001), "RETAIL")));
        return repository;
    }

    /**
     * Builds a step execution whose job parameters carry one reporting window.
     *
     * @param jobInstanceId the job instance identifier, distinct per execution
     * @param startDate the inclusive start date, ten characters
     * @param endDate the inclusive end date, ten characters
     * @return a step execution linked to a job execution and a job instance
     */
    private static StepExecution executionFor(long jobInstanceId, String startDate, String endDate) {
        JobParameters parameters = new JobParametersBuilder()
                .addString(TransactionReportProcessor.START_DATE_JOB_PARAMETER, startDate)
                .addString(TransactionReportProcessor.END_DATE_JOB_PARAMETER, endDate)
                .toJobParameters();
        JobExecution jobExecution = MetaDataInstanceFactory.createJobExecution(
                "transactionReportJob", Long.valueOf(jobInstanceId), Long.valueOf(jobInstanceId), parameters);
        return MetaDataInstanceFactory.createStepExecution(
                jobExecution, "reportStep", Long.valueOf(jobInstanceId));
    }

    /**
     * Builds one transaction fixture.
     *
     * @param transactionId sixteen characters
     * @param cardNumber sixteen characters
     * @param procDate the ten-character processing date the re-filter reads
     * @param amount the transaction amount
     * @return a fully populated transaction
     */
    private static Transaction transaction(String transactionId, String cardNumber, String procDate,
            String amount) {
        return new Transaction(transactionId, "01", Integer.valueOf(1001), "POS TERM",
                "A DESCRIPTION", new BigDecimal(amount), Long.valueOf(1L),
                "A MERCHANT", "A CITY", "10001", cardNumber,
                procDate + "-00.00.00.000000", procDate + "-00.00.00.000000");
    }

    /**
     * Builds one processor for one step execution, exactly as the owning step does.
     *
     * <p>This mirrors {@code TransactionReportJob.executeStep10r}: the two reporting dates are read from the
     * step execution's job parameters - which is what {@code startDateSymbol} and {@code endDateSymbol} do -
     * and passed to the constructor. Nothing resolves a bean, because there is no bean.
     *
     * @param execution the step execution whose job parameters carry the reporting window
     * @return a processor owned by that execution and by nothing else
     */
    private TransactionReportProcessor processorFor(StepExecution execution) {
        JobParameters parameters = execution.getJobExecution().getJobParameters();
        return new TransactionReportProcessor(
                transactions,
                crossReferences,
                types,
                categories,
                new FileStatusMapper(),
                parameters.getString(TransactionReportProcessor.START_DATE_JOB_PARAMETER),
                parameters.getString(TransactionReportProcessor.END_DATE_JOB_PARAMETER));
    }

    /**
     * Runs a body that owns its processor for the length of one execution.
     *
     * <p>The step context registration the earlier revision performed is gone with the scope: the body simply
     * runs, and its processor lives no longer than the call.
     *
     * @param body the body to run
     * @param <T> the body's result type
     * @return the body's result
     */
    private static <T> T forOneExecution(Callable<T> body) {
        try {
            return body.call();
        } catch (Exception failure) {
            throw new IllegalStateException("the execution body failed", failure);
        }
    }

    @Nested
    @DisplayName("one owner: neither class is a bean, and neither shares state statically")
    class StructuralOwnership {

        @Test
        @DisplayName("neither class carries @Component or @StepScope")
        void neitherClassIsAStepScopedComponent() {
            // F-008. These four assertions were the inverse a revision ago, when both classes were annotated
            // and TransactionReportJob nevertheless built them with new. The annotations promised a container
            // lifecycle that no production path invoked, and the promise is what made the defect hard to see.
            assertThat(TransactionReportProcessor.class.getAnnotation(Component.class))
                    .as("the processor is constructed by TransactionReportJob, so a bean definition would be "
                            + "a second unused provenance for one type")
                    .isNull();
            assertThat(TransactionReportProcessor.class.getAnnotation(StepScope.class))
                    .as("per-execution isolation comes from construction inside the tasklet, not from a scope")
                    .isNull();
            assertThat(TransactionBackupReader.class.getAnnotation(Component.class))
                    .as("one scoped definition could not serve both reader call sites: STEP01R reads the "
                            + "cluster and STEP10R reads a generation")
                    .isNull();
            assertThat(TransactionBackupReader.class.getAnnotation(StepScope.class))
                    .isNull();
        }

        @Test
        @DisplayName("a component scan over com.cardemo.batch finds neither class")
        void aComponentScanFindsNeitherClass() {
            // The annotation checks above could pass while a meta-annotated stereotype still made either class
            // a candidate. This asserts the outcome that actually matters - that the scan the application
            // performs contributes no definition for either type - by running the same scanner Spring runs
            // with the same default filters, which are exactly the @Component stereotype filters. No include
            // filter is added: include filters are OR-ed, so an assignability filter would match both classes
            // on type alone and the assertion would test nothing.
            ClassPathScanningCandidateComponentProvider scanner =
                    new ClassPathScanningCandidateComponentProvider(true);

            Set<String> candidates = scanner.findCandidateComponents(SCANNED_PACKAGE).stream()
                    .map(definition -> String.valueOf(definition.getBeanClassName()))
                    .collect(Collectors.toUnmodifiableSet());

            assertThat(candidates)
                    .as("the scan must find the container-owned collaborators, or the two absences below "
                            + "would be proved by a scan that simply found nothing")
                    .contains(TransactionCombineProcessor.class.getName(),
                            CombinedTransactionReader.class.getName(),
                            DailyTransactionReader.class.getName());
            assertThat(candidates)
                    .as("no bean named %s may be contributed by the scan of %s", BEAN_NAME, SCANNED_PACKAGE)
                    .doesNotContain(TransactionReportProcessor.class.getName(),
                            TransactionBackupReader.class.getName());
        }

        @Test
        @DisplayName("neither class holds static mutable state, which is what makes construction sufficient")
        void neitherClassHoldsStaticMutableState() {
            // The load-bearing assertion of this class. Per-execution construction isolates instance state by
            // definition; it isolates nothing that lives on the class. A static non-final field would be
            // shared by every execution and would reproduce exactly the singleton defect the scope was once
            // added to prevent - silently, and with no annotation left to hint at it.
            assertThat(mutableStaticFields(TransactionReportProcessor.class))
                    .as("the processor's nine accumulators must all be instance fields")
                    .isEmpty();
            assertThat(mutableStaticFields(TransactionBackupReader.class))
                    .as("the reader's row counters and stream handles must all be instance fields")
                    .isEmpty();
        }

        /**
         * Names every declared field that is static and not final.
         *
         * @param type the class to inspect
         * @return the offending field names, empty when there are none
         */
        private List<String> mutableStaticFields(Class<?> type) {
            return Arrays.stream(type.getDeclaredFields())
                    .filter(field -> Modifier.isStatic(field.getModifiers()))
                    .filter(field -> !Modifier.isFinal(field.getModifiers()))
                    .filter(field -> !field.isSynthetic())
                    .map(Field::getName)
                    .toList();
        }

        @Test
        @DisplayName("both reporting dates stay the last two constructor parameters, and neither is @Value bound")
        void bothDatesArePlainConstructorParameters() {
            Parameter[] parameters = TransactionReportProcessor.class.getConstructors()[0].getParameters();

            assertThat(parameters)
                    .as("TRANFILE, CARDXREF, TRANTYPE, TRANCATG, the status mapper and the two dates")
                    .hasSize(7);
            assertThat(parameters[5].getType())
                    .as("the two dates are the last two parameters, so an added collaborator shifts them and "
                            + "this assertion has to say which parameter it means")
                    .isEqualTo(String.class);
            assertThat(parameters[6].getType()).isEqualTo(String.class);
            assertThat(parameters[5].getAnnotation(Value.class))
                    .as("the owning step reads jobParameters['%s'] and passes it; a @Value here would be a "
                            + "binding nothing performs",
                            TransactionReportProcessor.START_DATE_JOB_PARAMETER)
                    .isNull();
            assertThat(parameters[6].getAnnotation(Value.class))
                    .as("likewise for jobParameters['%s']",
                            TransactionReportProcessor.END_DATE_JOB_PARAMETER)
                    .isNull();
        }

        @Test
        @DisplayName("the constructor still validates the window, so nothing was lost with the binding")
        void theConstructorStillValidatesTheWindow() {
            // Moving the binding out of the constructor must not move the validation out with it. An inverted
            // period and a blank date were rejected before F-008 and are rejected now, by the same
            // requireReportingDate and requireOrderedPeriod calls, at the same point in the lifecycle.
            StepExecution inverted = executionFor(90L, "2026-03-31", "2026-03-01");
            assertThatThrownBy(() -> processorFor(inverted))
                    .as("an inverted period would silently produce an empty report")
                    .isInstanceOf(FatalProcessingException.class);

            StepExecution blank = executionFor(91L, "2026-03-01", "   ");
            assertThatThrownBy(() -> processorFor(blank))
                    .as("a blank WS-END-DATE cannot be compared lexically")
                    .isInstanceOf(FatalProcessingException.class);
        }

    }

    @Nested
    @DisplayName("each step execution gets its own instance, bound to its own window")
    class PerExecutionBinding {

        @Test
        @DisplayName("two executions own two distinct instances")
        void twoExecutionsOwnTwoInstances() {
            TransactionReportProcessor first = forOneExecution(() ->
                    processorFor(executionFor(1L, "2026-01-01", "2026-01-31")));
            TransactionReportProcessor second = forOneExecution(() ->
                    processorFor(executionFor(2L, "2026-02-01", "2026-02-28")));

            assertThat(first).isNotSameAs(second);
        }

        @Test
        @DisplayName("each instance filters by its own job parameters, not by a shared window")
        void eachInstanceUsesItsOwnWindow() {
            Transaction january = transaction("0000000000000001", CARD_A, "2026-01-15", "10.00");

            TransactionReportProcessor.ReportLines inJanuaryWindow = forOneExecution(() ->
                    processorFor(executionFor(3L, "2026-01-01", "2026-01-31")).process(january));
            TransactionReportProcessor.ReportLines inFebruaryWindow = forOneExecution(() ->
                    processorFor(executionFor(4L, "2026-02-01", "2026-02-28")).process(january));

            assertThat(inJanuaryWindow).as("inside its window the record is reported").isNotNull();
            assertThat(inFebruaryWindow).as("outside its window the record is filtered").isNull();
        }
    }

    @Nested
    @DisplayName("overlapping executions do not share accumulators")
    class OverlappingExecutions {

        @Test
        @DisplayName("interleaved executions on one thread keep separate totals, counters and break keys")
        void interleavedExecutionsAreIsolated() {
            // Two executions, each owning its processor exactly as its STEP10R tasklet would, with records
            // interleaved between them so a shared accumulator could not hide behind sequential runs.
            TransactionReportProcessor firstExecution =
                    processorFor(executionFor(5L, "2026-01-01", "2026-01-31"));
            firstExecution.process(transaction("0000000000000001", CARD_A, "2026-01-02", "100.00"));
            firstExecution.process(transaction("0000000000000002", CARD_A, "2026-01-03", "200.00"));

            // The second execution starts while the first is still mid-report.
            TransactionReportProcessor secondExecution =
                    processorFor(executionFor(6L, "2026-01-01", "2026-01-31"));

            assertThat(secondExecution).isNotSameAs(firstExecution);
            assertThat(secondExecution.lineCounter())
                    .as("a fresh execution must start with no lines emitted")
                    .isZero();
            assertThat(secondExecution.pageTotal()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(secondExecution.accountTotal()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(secondExecution.grandTotal()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(secondExecution.currentCardNumber().strip())
                    .as("the control break key must not carry the other execution's card")
                    .isEmpty();

            secondExecution.process(transaction("0000000000000003", CARD_B, "2026-01-04", "7.00"));
            assertThat(secondExecution.accountTotal()).isEqualByComparingTo(new BigDecimal("7.00"));

            // Back in the first execution: its own state is intact and untouched by the second.
            assertThat(firstExecution.accountTotal())
                    .as("the first execution's total must not have absorbed the second's 7.00")
                    .isEqualByComparingTo(new BigDecimal("300.00"));
            assertThat(firstExecution.currentCardNumber().strip()).isEqualTo(CARD_A);

            // And a record arriving late on the first execution still sees only the first execution's key.
            firstExecution.process(transaction("0000000000000004", CARD_A, "2026-01-05", "50.00"));
            assertThat(firstExecution.accountTotal()).isEqualByComparingTo(new BigDecimal("350.00"));
            assertThat(secondExecution.accountTotal())
                    .as("the second execution must not have absorbed the first's late 50.00")
                    .isEqualByComparingTo(new BigDecimal("7.00"));
        }

        @Test
        @DisplayName("two concurrent executions on two threads keep separate totals")
        void concurrentExecutionsAreIsolated() throws Exception {
            CountDownLatch bothStarted = new CountDownLatch(2);
            ExecutorService threads = Executors.newFixedThreadPool(2);
            try {
                Future<BigDecimal> first = threads.submit(() -> runWindow(
                        executionFor(7L, "2026-01-01", "2026-01-31"), CARD_A, "100.00", bothStarted));
                Future<BigDecimal> second = threads.submit(() -> runWindow(
                        executionFor(8L, "2026-01-01", "2026-01-31"), CARD_B, "5.00", bothStarted));

                assertThat(first.get(30L, TimeUnit.SECONDS))
                        .as("each thread reports only its own amount")
                        .isEqualByComparingTo(new BigDecimal("100.00"));
                assertThat(second.get(30L, TimeUnit.SECONDS))
                        .isEqualByComparingTo(new BigDecimal("5.00"));
            } finally {
                threads.shutdownNow();
                assertThat(threads.awaitTermination(30L, TimeUnit.SECONDS)).isTrue();
            }
        }

        /**
         * Processes one record inside its own step context, holding the context open until both threads have
         * one, so the two executions genuinely overlap rather than running one after the other.
         *
         * @param execution the execution to bind on this thread
         * @param cardNumber the card number to report
         * @param amount the amount to report
         * @param bothStarted the rendezvous both threads wait on
         * @return the account total this execution accumulated
         * @throws InterruptedException if the rendezvous is interrupted
         */
        private BigDecimal runWindow(StepExecution execution, String cardNumber, String amount,
                CountDownLatch bothStarted) throws InterruptedException {
            TransactionReportProcessor processor = processorFor(execution);
            bothStarted.countDown();
            assertThat(bothStarted.await(30L, TimeUnit.SECONDS))
                    .as("both executions must be live at the same time, which is what would expose a static "
                            + "field shared across instances")
                    .isTrue();
            processor.process(transaction("000000000000000" + cardNumber.charAt(1),
                    cardNumber, "2026-01-10", amount));
            return processor.accountTotal();
        }
    }
}

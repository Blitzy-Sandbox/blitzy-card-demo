/*
 * ******************************************************************
 * Program     : TransactionReportProcessorScopeIsolationTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Proves the report processor is a genuinely step
 *               scoped bean whose reporting window and running
 *               accumulators are isolated per step execution. The
 *               class carries nine mutable accumulators - the line
 *               counter, three running totals, the control break key,
 *               the first-time flag, the cross reference, the two
 *               header dates and the stale amount - and as a
 *               singleton two overlapping executions shared every one
 *               of them, mixing one report's totals into another's.
 *               The previous note deferred the scoping to BatchConfig,
 *               a file that does not exist in this tree, so nothing
 *               enforced it. These tests assert the annotations, the
 *               job-parameter binding, per-execution instance
 *               identity, interleaved isolation on one thread and
 *               concurrent isolation across two threads.
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cardemo.batch.processors.TransactionReportProcessor;
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
import java.lang.reflect.Parameter;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.scope.context.StepSynchronizationManager;
import org.springframework.batch.test.MetaDataInstanceFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Verifies that {@link TransactionReportProcessor} is scoped and bound per step execution.
 *
 * <p><strong>Why identity alone is not the assertion.</strong> {@code @StepScope} is
 * {@code @Scope(value = "step", proxyMode = TARGET_CLASS)}, so the container publishes a CGLIB proxy under
 * the plain bean name and the real instance under {@code scopedTarget.<beanName>}. Comparing the proxy across
 * two executions would compare one object with itself and prove nothing. These tests therefore resolve the
 * <em>target</em> bean inside each step context, which is the instance that actually holds the accumulators,
 * and additionally assert the behaviour that isolation exists to protect: two executions must not see each
 * other's reporting window, line counter, control break key or running totals.
 *
 * <p><strong>How the two executions overlap.</strong> {@code StepSynchronizationManager} holds its contexts
 * on a per-thread deque, so registering a second execution inside the first genuinely nests them and closing
 * returns to the first - that is the single-threaded overlap. The multi-threaded case registers one execution
 * per thread and holds both open simultaneously behind a latch, which is the shape a partitioned or
 * multi-threaded step takes.
 *
 * <p><strong>Side effects.</strong> None outside the test JVM: two Spring contexts, Mockito doubles for the
 * three repositories, and a two-thread executor that is always shut down. No database, no container, no
 * network. Every step context registered is closed in {@link #tearDown()}, including after a failure, so no
 * thread-local leaks into another test.
 */
@DisplayName("TransactionReportProcessor - step scope and per-execution isolation")
class TransactionReportProcessorScopeIsolationTest {

    /** The bean name Spring derives from the class, and the prefix under which the real instance lives. */
    private static final String BEAN_NAME = "transactionReportProcessor";

    /** {@code org.springframework.aop.scope.ScopedProxyUtils} prefixes the target with this. */
    private static final String TARGET_BEAN_NAME = "scopedTarget." + BEAN_NAME;

    /** A card number of the sixteen characters {@code TRAN-CARD-NUM PIC X(16)} declares. */
    private static final String CARD_A = "4111111111111111";

    /** A second card number, so the control break can be exercised. */
    private static final String CARD_B = "4222222222222222";

    private AnnotationConfigApplicationContext context;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext();
        // TRANFILE. The processor takes it so that the step it belongs to owns its own reader source; the
        // per-record body never touches it, so a bare double is enough - but it must be registered, or the
        // scoped target cannot be constructed at all.
        context.registerBean(TransactionRepository.class, () -> mock(TransactionRepository.class));
        context.registerBean(CardCrossReferenceRepository.class, this::crossReferenceRepository);
        context.registerBean(TransactionTypeRepository.class, this::typeRepository);
        context.registerBean(TransactionCategoryRepository.class, this::categoryRepository);
        context.registerBean(FileStatusMapper.class, FileStatusMapper::new);
        // The scope itself. StepScope is a BeanFactoryPostProcessor, so registering it as a bean is what
        // registers the "step" scope with the factory - exactly as spring-boot-starter-batch does at runtime.
        context.registerBeanDefinition("stepScope", BeanDefinitionBuilder
                .genericBeanDefinition(org.springframework.batch.core.scope.StepScope.class)
                .getBeanDefinition());
        // register(Class) reads @Component and the @Scope meta-annotation on @StepScope, so the scoped proxy
        // is created the same way the component scan creates it. Nothing here overrides the class's own
        // declaration, which is the point: the test must fail if that declaration is removed.
        context.register(TransactionReportProcessor.class);
        context.refresh();
    }

    @AfterEach
    void tearDown() {
        // release() clears this thread's whole deque, so a test that failed mid-nesting cannot leak a context.
        StepSynchronizationManager.release();
        if (context != null) {
            context.close();
        }
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
     * Resolves the real, scoped instance inside the currently registered step context.
     *
     * @return the target instance the accumulators live on
     */
    private TransactionReportProcessor target() {
        return context.getBean(TARGET_BEAN_NAME, TransactionReportProcessor.class);
    }

    /**
     * Runs a body inside a freshly registered step context and closes it afterwards.
     *
     * @param execution the execution to bind
     * @param body the body to run
     * @param <T> the body's result type
     * @return the body's result
     */
    private static <T> T inScopeOf(StepExecution execution, Callable<T> body) {
        StepSynchronizationManager.register(execution);
        try {
            return body.call();
        } catch (Exception failure) {
            throw new IllegalStateException("the scoped body failed", failure);
        } finally {
            StepSynchronizationManager.close();
        }
    }

    @Nested
    @DisplayName("the scoping is declared on the class, not deferred to a configuration file")
    class DeclaredScoping {

        @Test
        @DisplayName("the class carries @Component and @StepScope")
        void theClassIsAStepScopedComponent() {
            assertThat(TransactionReportProcessor.class.getAnnotation(Component.class))
                    .as("without @Component nothing registers the bean at all")
                    .isNotNull();
            assertThat(TransactionReportProcessor.class.getAnnotation(StepScope.class))
                    .as("without @StepScope one singleton serves every execution")
                    .isNotNull();
        }

        @Test
        @DisplayName("both reporting dates are bound from job parameters on the constructor")
        void bothDatesAreBoundFromJobParameters() {
            Parameter[] parameters = TransactionReportProcessor.class.getConstructors()[0].getParameters();

            assertThat(parameters)
                    .as("TRANFILE, CARDXREF, TRANTYPE, TRANCATG, the status mapper and the two dates")
                    .hasSize(7);
            assertThat(parameters[5].getType())
                    .as("the two bound dates are the last two parameters, so an added collaborator shifts "
                            + "them and this assertion has to say which parameter it means")
                    .isEqualTo(String.class);
            assertThat(parameters[6].getType()).isEqualTo(String.class);
            assertThat(parameters[5].getAnnotation(Value.class))
                    .isNotNull()
                    .extracting(Value::value)
                    .isEqualTo("#{jobParameters['"
                            + TransactionReportProcessor.START_DATE_JOB_PARAMETER + "']}");
            assertThat(parameters[6].getAnnotation(Value.class))
                    .isNotNull()
                    .extracting(Value::value)
                    .isEqualTo("#{jobParameters['"
                            + TransactionReportProcessor.END_DATE_JOB_PARAMETER + "']}");
        }

        @Test
        @DisplayName("the container publishes a scoped proxy and a distinct scoped target")
        void theContainerPublishesAScopedProxy() {
            assertThat(context.containsBeanDefinition(BEAN_NAME)).isTrue();
            assertThat(context.containsBeanDefinition(TARGET_BEAN_NAME))
                    .as("the target definition is what proves the proxy mode took effect")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("each step execution gets its own instance, bound to its own window")
    class PerExecutionBinding {

        @Test
        @DisplayName("two executions resolve two distinct instances")
        void twoExecutionsResolveTwoInstances() {
            TransactionReportProcessor first = inScopeOf(
                    executionFor(1L, "2026-01-01", "2026-01-31"),
                    TransactionReportProcessorScopeIsolationTest.this::target);
            TransactionReportProcessor second = inScopeOf(
                    executionFor(2L, "2026-02-01", "2026-02-28"),
                    TransactionReportProcessorScopeIsolationTest.this::target);

            assertThat(first).isNotSameAs(second);
        }

        @Test
        @DisplayName("each instance filters by its own job parameters, not by a shared window")
        void eachInstanceUsesItsOwnWindow() {
            Transaction january = transaction("0000000000000001", CARD_A, "2026-01-15", "10.00");

            TransactionReportProcessor.ReportLines inJanuaryWindow = inScopeOf(
                    executionFor(3L, "2026-01-01", "2026-01-31"),
                    () -> TransactionReportProcessorScopeIsolationTest.this.target().process(january));
            TransactionReportProcessor.ReportLines inFebruaryWindow = inScopeOf(
                    executionFor(4L, "2026-02-01", "2026-02-28"),
                    () -> TransactionReportProcessorScopeIsolationTest.this.target().process(january));

            assertThat(inJanuaryWindow).as("inside its window the record is reported").isNotNull();
            assertThat(inFebruaryWindow).as("outside its window the record is filtered").isNull();
        }
    }

    @Nested
    @DisplayName("overlapping executions do not share accumulators")
    class OverlappingExecutions {

        @Test
        @DisplayName("nested executions on one thread keep separate totals, counters and break keys")
        void nestedExecutionsAreIsolated() {
            StepExecution outer = executionFor(5L, "2026-01-01", "2026-01-31");
            StepExecution inner = executionFor(6L, "2026-01-01", "2026-01-31");

            StepSynchronizationManager.register(outer);
            TransactionReportProcessor outerProcessor = target();
            outerProcessor.process(transaction("0000000000000001", CARD_A, "2026-01-02", "100.00"));
            outerProcessor.process(transaction("0000000000000002", CARD_A, "2026-01-03", "200.00"));

            // The second execution starts while the first is still open and mid-report.
            StepSynchronizationManager.register(inner);
            TransactionReportProcessor innerProcessor = target();

            assertThat(innerProcessor).isNotSameAs(outerProcessor);
            assertThat(innerProcessor.lineCounter())
                    .as("a fresh execution must start with no lines emitted")
                    .isZero();
            assertThat(innerProcessor.pageTotal()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(innerProcessor.accountTotal()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(innerProcessor.grandTotal()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(innerProcessor.currentCardNumber().strip())
                    .as("the control break key must not carry the other execution's card")
                    .isEmpty();

            innerProcessor.process(transaction("0000000000000003", CARD_B, "2026-01-04", "7.00"));
            assertThat(innerProcessor.accountTotal()).isEqualByComparingTo(new BigDecimal("7.00"));

            StepSynchronizationManager.close();

            // Back in the first execution: its own state is intact and untouched by the second.
            TransactionReportProcessor resumed = target();
            assertThat(resumed).isSameAs(outerProcessor);
            assertThat(resumed.accountTotal())
                    .as("the first execution's total must not have absorbed the second's 7.00")
                    .isEqualByComparingTo(new BigDecimal("300.00"));
            assertThat(resumed.currentCardNumber().strip()).isEqualTo(CARD_A);

            StepSynchronizationManager.close();
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
            StepSynchronizationManager.register(execution);
            try {
                TransactionReportProcessor processor = target();
                bothStarted.countDown();
                assertThat(bothStarted.await(30L, TimeUnit.SECONDS))
                        .as("both executions must be open at the same time")
                        .isTrue();
                processor.process(transaction("000000000000000" + cardNumber.charAt(1),
                        cardNumber, "2026-01-10", amount));
                return processor.accountTotal();
            } finally {
                StepSynchronizationManager.close();
            }
        }
    }
}

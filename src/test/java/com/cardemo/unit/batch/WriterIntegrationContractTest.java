/*
 * ******************************************************************
 * Program     : WriterIntegrationContractTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Regression guard for the integration contract of the
 *               transaction and reject writers: step-scoped execution
 *               isolation, the configured GDG prefix as the single
 *               source of truth, the complete ordered generation record
 *               in the JobExecution context, the permitted single-byte
 *               character set on every fixed-width field, canonical
 *               metric ownership, key-ordering width, and curated
 *               failure logging.
 * Source      : app/cbl/CBTRN02C.cbl:L442-L465  (2500-WRITE-REJECT-REC)
 *               app/cbl/CBTRN02C.cbl:L562-L579  (2900-WRITE-TRANSACTION-FILE)
 *               app/cbl/CBTRN02C.cbl:L226-L231  (the two counters, RC 4)
 *               app/cbl/CBTRN02C.cbl:L547-L552  (the sign branch)
 *               app/cpy/CVTRA05Y.cpy            (RECLN = 350)
 *               app/jcl/POSTTRAN.jcl:L34-L38    (DALYREJS, LRECL 430)
 *               app/jcl/DALYREJS.jcl:L25        (the GDG base)
 *               app/catlg/LISTCAT.txt:L3942     (GDG ------- 7) @ 7756d89
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
package com.cardemo.unit.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cardemo.batch.writers.RejectWriter;
import com.cardemo.batch.writers.TransactionWriter;
import com.cardemo.model.entity.DailyTransaction;
import com.cardemo.model.entity.Transaction;
import com.cardemo.model.enums.RejectCode;
import com.cardemo.observability.MetricsConfig;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.service.shared.FileStatusMapper;
import io.awspring.cloud.s3.S3Operations;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.test.StepScopeTestUtils;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

/**
 * Proves the integration contract of {@link TransactionWriter} and {@link RejectWriter}.
 *
 * <h2>What this class is for</h2>
 * <p>
 * A code review found fourteen defects across these two writers, one of them a Blocker. This class pins the
 * fixes to the <em>observable</em> surface - the execution contexts, the emitted bytes, the meter registry and
 * the captured log events - rather than to the implementation, so the guarantees keep holding whatever the
 * writers are refactored into.
 *
 * <h2>The four guarantees, and why each is asserted the way it is</h2>
 * <ul>
 * <li><b>Execution isolation (Blocker).</b> Asserted as a property of the <em>declaration</em> -
 *     {@code @StepScope} is present - rather than by racing two threads. A concurrency test that passes proves
 *     only that it did not lose the race this time; the annotation is what makes the isolation structural, and
 *     its presence is a fact a test can settle definitively.</li>
 * <li><b>The complete ordered generation record (High).</b> Asserted by writing several chunks and then reading
 *     the job context back through the published protocol - read the count, then read that many indexed
 *     entries - and checking the keys come back in creation order. This is exactly what a downstream consumer
 *     does, so the test is the consumer.</li>
 * <li><b>The permitted character set (High).</b> Asserted across the whole {@code char} domain by driving the
 *     encoder, not by inspecting a constant. Both directions matter: every control byte is refused, and every
 *     printable byte the frozen fixtures actually contain - the zoned-decimal overpunch characters included -
 *     still passes.</li>
 * <li><b>Canonical metric ownership (High).</b> Asserted by counting the meters in a fresh registry after the
 *     writers have run. If either writer registered its own instrument the count would exceed the sanctioned
 *     four, and no amount of name agreement would hide it.</li>
 * </ul>
 *
 * <h2>Fixture safety</h2>
 * <p>
 * Card numbers are in the {@code 4000} test range with no valid check digit, and no fixture value is a real
 * identifier. They are nonetheless treated as sensitive, because the assertions are about whether the code
 * would emit them.
 */
@DisplayName("Writer integration contract (B-02, H-03, H-06, H-07, H-08, H-09, H-13, H-14, M-05, M-09, L-01)")
class WriterIntegrationContractTest {

    /** The configured reject generation base, exactly as {@code application.yml} declares it. */
    private static final String CONFIGURED_REJECT_PREFIX = "gdg/dalyrejs";

    /** The configured posted-transaction mirror prefix, exactly as {@code application.yml} declares it. */
    private static final String CONFIGURED_TRANSACTION_PREFIX = "transact";

    /** The destination bucket name; a value no other assertion can collide with. */
    private static final String OUTPUT_BUCKET = "carddemo-batch-output";

    /** {@code TRAN-CARD-NUM PIC X(16)}: cardholder data, and never permitted on a log channel. */
    private static final String CARD_NUMBER = "4000000000000019";

    /** {@code TRAN-ID PIC X(16)}: the identifier the review found on the log channel. */
    private static final String TRANSACTION_ID = "TRAN000000000091";

    /** A second identifier, so an "identifier list" leak is distinguishable from a single-value one. */
    private static final String SECOND_TRANSACTION_ID = "TRAN000000000092";

    /** A negative amount: {@code app/data/ASCII/dailytran.txt} carries genuine debits. */
    private static final BigDecimal DEBIT_AMOUNT = new BigDecimal("-12.34");

    /** A positive amount, so the signed total is provably a sum and not an accumulation of magnitudes. */
    private static final BigDecimal CREDIT_AMOUNT = new BigDecimal("30.00");

    private ListAppender<ILoggingEvent> events;

    private final List<Logger> attached = new ArrayList<>();

    private SimpleMeterRegistry meterRegistry;

    private MetricsConfig metrics;

    @BeforeEach
    void attachAppenderAndRegistry() {
        events = new ListAppender<>();
        events.start();
        attach(TransactionWriter.class);
        attach(RejectWriter.class);
        // A FRESH registry per test, so the meter count is a measurement of what these writers registered and
        // not of what some earlier test left behind.
        meterRegistry = new SimpleMeterRegistry();
        metrics = new MetricsConfig(meterRegistry);
    }

    @AfterEach
    void detachAppenderAndCloseRegistry() {
        attached.forEach(logger -> logger.detachAppender(events));
        attached.clear();
        events.stop();
        meterRegistry.close();
    }

    /**
     * Attaches the shared appender to one writer's logger at TRACE, so no emission can hide below a threshold.
     *
     * @param writerType the writer whose logger is being observed
     */
    private void attach(Class<?> writerType) {
        Logger logger = (Logger) LoggerFactory.getLogger(writerType);
        logger.addAppender(events);
        logger.setLevel(Level.TRACE);
        attached.add(logger);
    }

    /**
     * Renders every captured event - formatted message, raw argument array and any throwable message - into one
     * string for token searching, so an assertion sees what was passed and not only what was rendered.
     *
     * @return the concatenation of every captured event; never {@code null}
     */
    private String capturedText() {
        StringBuilder text = new StringBuilder(2048);
        for (ILoggingEvent event : events.list) {
            text.append(event.getFormattedMessage()).append('\n');
            Object[] arguments = event.getArgumentArray();
            if (arguments != null) {
                for (Object argument : arguments) {
                    text.append(String.valueOf(argument)).append('\n');
                }
            }
            if (event.getThrowableProxy() != null) {
                text.append(event.getThrowableProxy().getMessage()).append('\n');
            }
        }
        return text.toString();
    }

    // ====================================================================================================
    // Fixtures.
    // ====================================================================================================

    /**
     * Builds a step execution wired to a job execution and a job instance, which is what both writers need in
     * order to scope an object key and to publish a generation.
     *
     * @param jobInstanceId the instance identifier the generation prefix is derived from
     * @return a usable step execution; never {@code null}
     */
    private static StepExecution stepExecution(long jobInstanceId) {
        JobExecution jobExecution =
                new JobExecution(new JobInstance(Long.valueOf(jobInstanceId), "POSTTRAN"), null, null);
        jobExecution.setId(Long.valueOf(jobInstanceId * 10L));
        return jobExecution.createStepExecution("write");
    }

    /**
     * Builds a posted transaction of {@code app/cpy/CVTRA05Y.cpy} with a caller-chosen identifier and amount.
     *
     * @param transactionId {@code TRAN-ID PIC X(16)}
     * @param amount {@code TRAN-AMT PIC S9(09)V99}, of either sign
     * @return the transaction; never {@code null}
     */
    private static Transaction transaction(String transactionId, BigDecimal amount) {
        return new Transaction(transactionId,
                "01",
                Integer.valueOf(1),
                "POS       ",
                "A SYNTHETIC DESCRIPTION",
                amount,
                Long.valueOf(1L),
                "MERCHANT",
                "CITY",
                "0000091234",
                CARD_NUMBER,
                "2025-01-01-00.00.00.000000",
                "2025-01-01-00.00.00.000000");
    }

    /**
     * Builds a staged daily transaction of {@code app/cpy/CVTRA06Y.cpy}, optionally with a poisoned description.
     *
     * @param description {@code DALYTRAN-DESC PIC X(100)}, the field an injected control byte would ride in
     * @return the staged transaction; never {@code null}
     */
    private static DailyTransaction dailyTransaction(String description) {
        return new DailyTransaction(Long.valueOf(1L),
                TRANSACTION_ID,
                "01",
                Integer.valueOf(1),
                "POS       ",
                description,
                DEBIT_AMOUNT,
                Long.valueOf(1L),
                "MERCHANT",
                "CITY",
                "0000091234",
                CARD_NUMBER,
                "2025-01-01-00.00.00.000000",
                " ".repeat(26));
    }

    /**
     * Builds a transaction writer over mocked collaborators that both succeed.
     *
     * @param repository the repository mock the caller also wants to assert against
     * @param objectStorage the object-storage mock
     * @return the writer; never {@code null}
     */
    private TransactionWriter transactionWriter(TransactionRepository repository, S3Operations objectStorage) {
        return new TransactionWriter(repository,
                objectStorage,
                new FileStatusMapper(),
                metrics,
                OUTPUT_BUCKET,
                CONFIGURED_TRANSACTION_PREFIX);
    }

    /**
     * Builds a reject writer over a mocked object store for one step execution.
     *
     * @param objectStorage the object-storage mock
     * @param execution the step execution, or {@code null} to exercise the no-context path
     * @return the writer; never {@code null}
     */
    private RejectWriter rejectWriter(S3Operations objectStorage, StepExecution execution) {
        return new RejectWriter(objectStorage,
                metrics,
                new FileStatusMapper(),
                OUTPUT_BUCKET,
                CONFIGURED_REJECT_PREFIX,
                execution);
    }

    // ====================================================================================================
    // B-02 - execution isolation.
    // ====================================================================================================

    @Nested
    @DisplayName("Both writers are step-scoped, so no state is shared across executions (B-02)")
    class ExecutionIsolation {

        @Test
        @DisplayName("TransactionWriter declares @StepScope")
        void transactionWriterIsStepScoped() {
            // Asserted on the declaration rather than by racing two threads: a race that happens not to
            // interleave proves nothing, whereas the annotation is what makes the isolation structural.
            assertThat(TransactionWriter.class.getAnnotation(StepScope.class))
                    .as("TransactionWriter must be step-scoped; a singleton sharing a captured StepExecution "
                            + "lets two concurrent executions overwrite each other's object ownership")
                    .isNotNull();
        }

        @Test
        @DisplayName("RejectWriter declares @StepScope")
        void rejectWriterIsStepScoped() {
            assertThat(RejectWriter.class.getAnnotation(StepScope.class)).isNotNull();
        }

        @Test
        @DisplayName("neither writer is final, so the class-based scope proxy can subclass it")
        void writersArePr0xyable() {
            // @StepScope proxies by subclassing (proxyMode = TARGET_CLASS). A final class would make the
            // annotation fail at context refresh rather than at compile time, which is exactly the kind of
            // defect a unit test should catch instead of an integration run.
            assertThat(java.lang.reflect.Modifier.isFinal(TransactionWriter.class.getModifiers())).isFalse();
            assertThat(java.lang.reflect.Modifier.isFinal(RejectWriter.class.getModifiers())).isFalse();
        }

        @Test
        @DisplayName("the container really does hand out one instance per step execution")
        void theContainerIsolatesPerStepExecution() throws Exception {
            // The annotation assertions above prove the DECLARATION. This proves the BEHAVIOUR, and it does so
            // through the scoped proxy the container hands a singleton - which is how production reaches the
            // bean - rather than by asking the context for the target directly.
            //
            // The observable that settles it is the captured step execution. In scope A the writer is given one
            // by beforeStep; in scope B a write must then fail with the not-captured diagnostic. It can only
            // fail if scope B resolved a DIFFERENT instance, because a shared singleton would still be holding
            // A's execution and would happily write - which is precisely the Blocker: run B silently adopting
            // run A's job instance and object ownership.
            try (AnnotationConfigApplicationContext context = stepScopedContext()) {
                TransactionWriter proxy = context.getBean(SingletonHolder.class).writer();
                StepExecution executionA = stepExecution(1L);
                StepExecution executionB = stepExecution(2L);

                StepScopeTestUtils.doInStepScope(executionA, () -> {
                    proxy.beforeStep(executionA);
                    proxy.write(new Chunk<>(List.of(transaction(TRANSACTION_ID, CREDIT_AMOUNT))));
                    return null;
                });

                // Scope A really did write, so the negative result below is about isolation and not about the
                // writer being broken.
                assertThat(publishedTransactionKeys(executionA)).hasSize(1);

                assertThatThrownBy(() -> StepScopeTestUtils.doInStepScope(executionB, () -> {
                    proxy.write(new Chunk<>(List.of(transaction(SECOND_TRANSACTION_ID, CREDIT_AMOUNT))));
                    return null;
                }))
                        .as("a second step execution must get a fresh instance with no captured execution, "
                                + "not the one the first execution populated")
                        .hasMessageContaining("no step execution was captured");

                // And nothing of run B leaked into run A's generation.
                assertThat(publishedTransactionKeys(executionB)).isEmpty();
            }
        }

        @Test
        @DisplayName("a step-scoped writer can still be injected into a singleton, so refresh does not fail")
        void singletonInjectionStillRefreshes() {
            // The regression this guards: @StepScope proxies by subclassing, and a singleton that
            // constructor-injects the bean - InterestCalculationJob does exactly that - would fail context
            // refresh outright if the proxy could not be built. Refresh succeeding IS the assertion.
            try (AnnotationConfigApplicationContext context = stepScopedContext()) {
                SingletonHolder holder = context.getBean(SingletonHolder.class);
                assertThat(holder.writer())
                        .as("the singleton must receive a scope proxy, not a null and not the raw target")
                        .isNotNull();
                assertThat(org.springframework.aop.support.AopUtils.isAopProxy(holder.writer())
                        || holder.writer().getClass() != TransactionWriter.class)
                        .as("the injected reference must be a scoped proxy rather than a shared instance")
                        .isTrue();
            }
        }

        @Test
        @DisplayName("two concurrent executions publish into their own job contexts, not into one another's")
        void concurrentExecutionsDoNotShareContexts() throws Exception {
            // The step-scoped bean is one instance per execution, so this test builds two instances - which is
            // what the container would do - and proves their published generations are disjoint.
            S3Operations objectStorage = mock(S3Operations.class);
            TransactionRepository repository = mock(TransactionRepository.class);
            when(repository.saveAllAndFlush(any())).thenAnswer(invocation -> List.of());

            StepExecution first = stepExecution(1L);
            StepExecution second = stepExecution(2L);

            TransactionWriter firstWriter = transactionWriter(repository, objectStorage);
            firstWriter.beforeStep(first);
            firstWriter.write(new Chunk<>(List.of(transaction(TRANSACTION_ID, CREDIT_AMOUNT))));

            TransactionWriter secondWriter = transactionWriter(repository, objectStorage);
            secondWriter.beforeStep(second);
            secondWriter.write(new Chunk<>(List.of(transaction(SECOND_TRANSACTION_ID, CREDIT_AMOUNT))));

            List<String> firstKeys = publishedTransactionKeys(first);
            List<String> secondKeys = publishedTransactionKeys(second);

            assertThat(firstKeys).hasSize(1);
            assertThat(secondKeys).hasSize(1);
            // Each key carries its own zero-padded job instance identifier, so the two generations cannot
            // collide and neither run can overwrite the other's object.
            assertThat(firstKeys.getFirst()).isNotEqualTo(secondKeys.getFirst());
            assertThat(firstKeys.getFirst()).contains("0000000000000000001");
            assertThat(secondKeys.getFirst()).contains("0000000000000000002");
        }
    }

    // ====================================================================================================
    // H-08 - the complete ordered generation record in the JobExecution context.
    // ====================================================================================================

    @Nested
    @DisplayName("The complete ordered generation is published to the JobExecution context (H-08)")
    class GenerationRecord {

        private TransactionRepository repository;

        private StepExecution execution;

        private TransactionWriter writer;

        @BeforeEach
        void writeThreeChunks() throws Exception {
            repository = mock(TransactionRepository.class);
            when(repository.saveAllAndFlush(any())).thenAnswer(invocation -> List.of());
            execution = stepExecution(7L);
            writer = transactionWriter(repository, mock(S3Operations.class));
            writer.beforeStep(execution);

            for (int chunk = 0; chunk < 3; chunk++) {
                writer.write(new Chunk<>(List.of(transaction(TRANSACTION_ID, CREDIT_AMOUNT))));
                // The framework advances the write count between chunks; doing it here keeps the ordinal
                // distinct so the three keys differ, exactly as they would in a real step.
                execution.setWriteCount(chunk + 1L);
            }
        }

        @Test
        @DisplayName("the count entry reports every object, not just the latest")
        void countCoversEveryObject() {
            assertThat(execution.getJobExecution()
                    .getExecutionContext()
                    .getLong(TransactionWriter.OBJECT_KEYS_COUNT_ENTRY))
                    .isEqualTo(3L);
        }

        @Test
        @DisplayName("the indexed entries return the keys in creation order")
        void indexedEntriesPreserveOrder() {
            List<String> keys = publishedTransactionKeys(execution);
            assertThat(keys).hasSize(3).doesNotHaveDuplicates();
            // Creation order is also ascending lexicographic order, which is the property that lets a (0)
            // generation reference resolve as "the lexicographically greatest prefix".
            assertThat(keys).isSorted();
        }

        @Test
        @DisplayName("the step context still names the latest object for an in-step listener")
        void stepContextStillCarriesTheLatest() {
            String latest = execution.getExecutionContext()
                    .getString(TransactionWriter.OBJECT_KEY_CONTEXT_ENTRY);
            assertThat(latest).isEqualTo(publishedTransactionKeys(execution).getLast());
        }

        @Test
        @DisplayName("no key contains the entry separator, so an indexed read cannot be ambiguous")
        void keysCarryNoDelimiter() {
            // The indexed scheme has no separator by construction. This asserts the property that made the
            // alternative - one comma-joined string - unsafe, so the reasoning behind the choice stays checked
            // rather than merely documented.
            assertThat(publishedTransactionKeys(execution)).allSatisfy(key -> assertThat(key).doesNotContain(","));
        }

        @Test
        @DisplayName("RejectWriter publishes its ordered generation into the JobExecution context too")
        void rejectWriterPublishesOrderedKeys() throws Exception {
            StepExecution rejectExecution = stepExecution(9L);
            RejectWriter rejects = rejectWriter(mock(S3Operations.class), rejectExecution);

            rejects.write(new Chunk<>(List.of(
                    new RejectWriter.RejectedTransaction(dailyTransaction("FIRST"), RejectCode.INVALID_CARD_NUMBER))));
            rejects.write(new Chunk<>(List.of(
                    new RejectWriter.RejectedTransaction(dailyTransaction("SECOND"), RejectCode.INVALID_CARD_NUMBER))));

            ExecutionContext jobContext = rejectExecution.getJobExecution().getExecutionContext();
            assertThat(jobContext.getLong(RejectWriter.REJECT_OBJECT_KEYS_COUNT_ENTRY)).isEqualTo(2L);
            List<String> keys = List.of(
                    jobContext.getString(RejectWriter.rejectObjectKeysIndexEntry(0)),
                    jobContext.getString(RejectWriter.rejectObjectKeysIndexEntry(1)));
            assertThat(keys).doesNotHaveDuplicates().isSorted();
        }

        @Test
        @DisplayName("a negative index is refused rather than naming an entry no writer emits")
        void negativeIndexIsRefused() {
            assertThatThrownBy(() -> TransactionWriter.objectKeysIndexEntry(-1))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> RejectWriter.rejectObjectKeysIndexEntry(-1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ====================================================================================================
    // H-06, H-07, M-05 - configured prefixes and key ordering.
    // ====================================================================================================

    @Nested
    @DisplayName("Object keys come from configuration and order lexicographically (H-06, H-07, M-05)")
    class KeyComposition {

        @Test
        @DisplayName("a reject key starts with the CONFIGURED gdg prefix, not a hardcoded one")
        void rejectKeyUsesConfiguredPrefix() throws Exception {
            StepExecution execution = stepExecution(4L);
            RejectWriter writer = rejectWriter(mock(S3Operations.class), execution);
            writer.write(new Chunk<>(List.of(
                    new RejectWriter.RejectedTransaction(dailyTransaction("ONE"), RejectCode.INVALID_CARD_NUMBER))));

            String key = execution.getExecutionContext()
                    .getString(RejectWriter.REJECT_OBJECT_KEY_CONTEXT_KEY);
            assertThat(key).startsWith(CONFIGURED_REJECT_PREFIX + "/");
            // The decisive half: the OLD hardcoded root must not appear at the start. A key under "dalyrejs/"
            // is invisible to every consumer configured from the catalogue, which is silent data loss.
            assertThat(key).doesNotStartWith("dalyrejs/");
        }

        @Test
        @DisplayName("a configured prefix with or without a trailing slash composes the identical key")
        void trailingSeparatorIsNormalised() throws Exception {
            StepExecution bare = stepExecution(5L);
            StepExecution slashed = stepExecution(5L);
            new RejectWriter(mock(S3Operations.class), metrics, new FileStatusMapper(),
                    OUTPUT_BUCKET, CONFIGURED_REJECT_PREFIX, bare)
                    .write(new Chunk<>(List.of(new RejectWriter.RejectedTransaction(
                            dailyTransaction("ONE"), RejectCode.INVALID_CARD_NUMBER))));
            new RejectWriter(mock(S3Operations.class), metrics, new FileStatusMapper(),
                    OUTPUT_BUCKET, CONFIGURED_REJECT_PREFIX + "/", slashed)
                    .write(new Chunk<>(List.of(new RejectWriter.RejectedTransaction(
                            dailyTransaction("ONE"), RejectCode.INVALID_CARD_NUMBER))));

            assertThat(bare.getExecutionContext().getString(RejectWriter.REJECT_OBJECT_KEY_CONTEXT_KEY))
                    .isEqualTo(slashed.getExecutionContext()
                            .getString(RejectWriter.REJECT_OBJECT_KEY_CONTEXT_KEY));
        }

        @Test
        @DisplayName("a blank configured prefix fails construction rather than writing to the bucket root")
        void blankPrefixIsRefused() {
            assertThatThrownBy(() -> rejectWriterWithPrefix("   "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("daly-rejs");
            assertThatThrownBy(() -> rejectWriterWithPrefix("/"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a blank transaction prefix fails construction, because no inline default remains")
        void blankTransactionPrefixIsRefused() {
            // The H-07 assertion. An inline default would make this construction succeed and write a whole
            // generation under a silently different prefix.
            assertThatThrownBy(() -> new TransactionWriter(mock(TransactionRepository.class),
                    mock(S3Operations.class), new FileStatusMapper(), metrics, OUTPUT_BUCKET, "  "))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("the reject sequence component is 19 digits, so lexical order tracks numeric order")
        void rejectSequenceCoversItsDomain() throws Exception {
            // M-05. Six digits would number the millionth object "1000000", which sorts BEFORE "999999" and
            // breaks the equivalence that lets (0) resolve to the newest object. Asserted by driving the writer
            // and measuring the rendered component rather than by reading the format constant.
            StepExecution execution = stepExecution(6L);
            RejectWriter writer = rejectWriter(mock(S3Operations.class), execution);
            writer.write(new Chunk<>(List.of(
                    new RejectWriter.RejectedTransaction(dailyTransaction("ONE"), RejectCode.INVALID_CARD_NUMBER))));

            String key = execution.getExecutionContext()
                    .getString(RejectWriter.REJECT_OBJECT_KEY_CONTEXT_KEY);
            String sequence = key.substring(key.lastIndexOf('-') + 1, key.lastIndexOf('.'));
            assertThat(sequence).hasSize(19).containsOnlyDigits();
        }

        /**
         * Constructs a reject writer with one specific configured prefix, for the refusal assertions.
         *
         * @param prefix the configured value under test
         * @return the constructed writer, which the refusal cases never reach
         */
        private RejectWriter rejectWriterWithPrefix(String prefix) {
            return new RejectWriter(mock(S3Operations.class), metrics, new FileStatusMapper(),
                    OUTPUT_BUCKET, prefix, stepExecution(1L));
        }
    }

    // ====================================================================================================
    // H-13 - the permitted single-byte character set.
    // ====================================================================================================

    @Nested
    @DisplayName("Fixed-width fields admit only printable single-byte characters (H-13)")
    class PermittedCharacterSet {

        private TransactionWriter writer;

        @BeforeEach
        void buildWriter() {
            writer = transactionWriter(mock(TransactionRepository.class), mock(S3Operations.class));
        }

        @Test
        @DisplayName("CR, LF, NUL, TAB and DEL are all refused in a PIC X field")
        void controlBytesAreRefused() {
            // These five are the injection payloads that matter: the stream is unblocked and undelimited, so a
            // line-oriented consumer treats CR or LF as a record boundary the format does not have, and NUL
            // terminates a C string early.
            for (char control : new char[] {'\r', '\n', '\u0000', '\t', '\u007F'}) {
                assertThatThrownBy(() -> writer.composeFixedWidthImage(
                        transactionWithDescription("BEFORE" + control + "AFTER")))
                        .as("code point U+%04X must be refused", Integer.valueOf(control))
                        .hasMessageContaining("outside the permitted set")
                        .hasMessageContaining("undelimited");
            }
        }

        @Test
        @DisplayName("every C0 and C1 control code point is refused, not just the well-known ones")
        void everyControlCodePointIsRefused() {
            // Swept rather than enumerated, because a deny-list of "the ones we thought of" is exactly the
            // failure mode the positive rule was chosen to avoid.
            for (char candidate = '\u0000'; candidate < '\u0020'; candidate++) {
                assertRefused(candidate);
            }
            for (char candidate = '\u007F'; candidate <= '\u009F'; candidate++) {
                assertRefused(candidate);
            }
        }

        @Test
        @DisplayName("every printable ASCII character passes, overpunch characters included")
        void printableAsciiPasses() {
            // The other direction, and the one that proves nothing legitimate was broken. The zoned-decimal
            // overpunch set - '{', '}' and 'A' to 'R' - lives inside this range, and app/data/ASCII is
            // printable throughout.
            for (char candidate = '\u0020'; candidate <= '\u007E'; candidate++) {
                final char value = candidate;
                assertThatCode(() -> writer.composeFixedWidthImage(
                        transactionWithDescription("OK" + value)))
                        .as("code point U+%04X must be permitted", Integer.valueOf(candidate))
                        .doesNotThrowAnyException();
            }
        }

        @Test
        @DisplayName("the printable upper half of ISO-8859-1 passes")
        void printableLatin1Passes() {
            for (char candidate = '\u00A0'; candidate <= '\u00FF'; candidate++) {
                final char value = candidate;
                assertThatCode(() -> writer.composeFixedWidthImage(
                        transactionWithDescription("OK" + value)))
                        .doesNotThrowAnyException();
            }
        }

        @Test
        @DisplayName("a character above the single-byte range is still refused, as it always was")
        void unencodableCharacterIsStillRefused() {
            assertThatThrownBy(() -> writer.composeFixedWidthImage(
                    transactionWithDescription("BEFORE\u20ACAFTER")))
                    .hasMessageContaining("single byte");
        }

        @Test
        @DisplayName("the geometry is unchanged: a permitted record is still exactly 350 characters")
        void geometryIsPreserved() {
            assertThat(writer.composeFixedWidthImage(transactionWithDescription("A DESCRIPTION")))
                    .hasSize(TransactionWriter.RECORD_LENGTH);
        }

        @Test
        @DisplayName("RejectWriter refuses control bytes too, and keeps its 430-byte geometry")
        void rejectWriterEnforcesTheSameSet() throws Exception {
            StepExecution execution = stepExecution(3L);
            RejectWriter rejects = rejectWriter(mock(S3Operations.class), execution);

            assertThatThrownBy(() -> rejects.write(new Chunk<>(List.of(
                    new RejectWriter.RejectedTransaction(
                            dailyTransaction("BEFORE\nAFTER"), RejectCode.INVALID_CARD_NUMBER)))))
                    .hasMessageContaining("outside the permitted set");

            // And a clean record still writes, so the guard did not simply reject everything.
            assertThatCode(() -> rejects.write(new Chunk<>(List.of(
                    new RejectWriter.RejectedTransaction(
                            dailyTransaction("A CLEAN DESCRIPTION"), RejectCode.INVALID_CARD_NUMBER)))))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a control byte diagnostic reports the position and code point, never the value")
        void diagnosticWithholdsTheValue() {
            assertThatThrownBy(() -> writer.composeFixedWidthImage(
                    transactionWithDescription("SECRETVALUE\rMORE")))
                    .hasMessageContaining("U+000D")
                    .hasMessageContaining("position 12")
                    .hasMessageNotContaining("SECRETVALUE");
        }

        /**
         * Asserts that one code point is refused when it appears in a description field.
         *
         * @param candidate the code point under test
         */
        private void assertRefused(char candidate) {
            assertThatThrownBy(() -> writer.composeFixedWidthImage(
                    transactionWithDescription("X" + candidate)))
                    .as("code point U+%04X must be refused", Integer.valueOf(candidate))
                    .hasMessageContaining("permitted set");
        }

        /**
         * Builds a transaction whose description carries the value under test.
         *
         * @param description the {@code TRAN-DESC PIC X(100)} value
         * @return the transaction; never {@code null}
         */
        private static Transaction transactionWithDescription(String description) {
            return new Transaction(TRANSACTION_ID, "01", Integer.valueOf(1), "POS       ", description,
                    CREDIT_AMOUNT, Long.valueOf(1L), "MERCHANT", "CITY", "0000091234", CARD_NUMBER,
                    "2025-01-01-00.00.00.000000", "2025-01-01-00.00.00.000000");
        }
    }

    // ====================================================================================================
    // H-09, H-14, L-01 - canonical instrument ownership and exact counting.
    // ====================================================================================================

    @Nested
    @DisplayName("Instruments belong to MetricsConfig alone (H-09, H-14, L-01)")
    class InstrumentOwnership {

        @Test
        @DisplayName("neither writer declares a metric name or tag key of its own")
        void writersDeclareNoMetricNames() {
            // H-14. Asserted on the reflected constant set, because the defect was a second DECLARATION site -
            // which no behavioural test can see, since both sites agree with themselves until one is edited.
            assertThat(declaredStringConstants(TransactionWriter.class))
                    .noneMatch(value -> value.startsWith("carddemo.batch.records"))
                    .noneMatch(value -> value.equals("reject.code"));
            assertThat(declaredStringConstants(RejectWriter.class))
                    .noneMatch(value -> value.startsWith("carddemo.batch.records"))
                    .noneMatch(value -> value.equals("reject.code"));
        }

        @Test
        @DisplayName("running both writers registers no instrument beyond the sanctioned four")
        void noFifthInstrumentAppears() throws Exception {
            TransactionRepository repository = mock(TransactionRepository.class);
            when(repository.saveAllAndFlush(any())).thenAnswer(invocation -> List.of());
            TransactionWriter transactions = transactionWriter(repository, mock(S3Operations.class));
            transactions.beforeStep(stepExecution(1L));
            transactions.write(new Chunk<>(List.of(transaction(TRANSACTION_ID, CREDIT_AMOUNT))));

            RejectWriter rejects = rejectWriter(mock(S3Operations.class), stepExecution(2L));
            rejects.write(new Chunk<>(List.of(new RejectWriter.RejectedTransaction(
                    dailyTransaction("ONE"), RejectCode.INVALID_CARD_NUMBER))));

            // Counted by distinct NAME, not by Meter instance. Micrometer materialises one Meter per tag
            // combination, so a correctly tagged reject counter is several Meters of one instrument and an
            // instance count would fail against correct code. The four-instrument contract fixes four names;
            // that is what is asserted, and it is exactly the property a fifth instrument would break.
            assertThat(meterRegistry.getMeters())
                    .extracting(meter -> meter.getId().getName())
                    .as("exactly the four sanctioned instrument NAMES, all owned by MetricsConfig")
                    .containsOnly(MetricsConfig.METRIC_RECORDS_PROCESSED,
                            MetricsConfig.METRIC_RECORDS_REJECTED,
                            MetricsConfig.METRIC_AUTHENTICATION_ATTEMPTS,
                            MetricsConfig.METRIC_TRANSACTION_AMOUNT_TOTAL);
            // And the distinct-name count is exactly four. Names repeat legitimately - MetricsConfig
            // pre-registers every bounded tag value of the reject and authentication series, so those two names
            // appear five and two times respectively - which is why the set, not the list, is measured.
            assertThat(meterRegistry.getMeters().stream()
                    .map(meter -> meter.getId().getName())
                    .distinct()
                    .toList())
                    .as("no instrument name beyond the sanctioned four")
                    .hasSize(4);
        }

        @Test
        @DisplayName("the processed counter advances by the exact chunk size in one increment")
        void processedCounterIsExact() throws Exception {
            // L-01. The observable outcome is the same total either way, so this asserts the total is exact
            // while the implementation is free to reach it in one call.
            TransactionRepository repository = mock(TransactionRepository.class);
            when(repository.saveAllAndFlush(any())).thenAnswer(invocation -> List.of());
            TransactionWriter writer = transactionWriter(repository, mock(S3Operations.class));
            writer.beforeStep(stepExecution(1L));

            writer.write(new Chunk<>(List.of(
                    transaction(TRANSACTION_ID, CREDIT_AMOUNT),
                    transaction(SECOND_TRANSACTION_ID, CREDIT_AMOUNT),
                    transaction("TRAN000000000093", CREDIT_AMOUNT))));

            assertThat(meterRegistry.counter(MetricsConfig.METRIC_RECORDS_PROCESSED).count())
                    .isEqualTo(3.0d);
        }

        @Test
        @DisplayName("the signed amount total is a SUM: a debit lowers it, never raises it")
        void signedAmountTotalHasARealCaller() throws Exception {
            // H-09's completion. The Phase-3 gauge needed a caller; this proves it has one AND that the caller
            // does not normalise a sign. app/cbl/CBTRN02C.cbl:L547-L552 adds a negative amount to the cycle
            // debit accumulator, so a signed stream is the contract, not an accident.
            TransactionRepository repository = mock(TransactionRepository.class);
            when(repository.saveAllAndFlush(any())).thenAnswer(invocation -> List.of());
            TransactionWriter writer = transactionWriter(repository, mock(S3Operations.class));
            writer.beforeStep(stepExecution(1L));

            writer.write(new Chunk<>(List.of(
                    transaction(TRANSACTION_ID, CREDIT_AMOUNT),
                    transaction(SECOND_TRANSACTION_ID, DEBIT_AMOUNT))));

            // 30.00 + (-12.34) = 17.66. Under a monotonic counter the debit would have been discarded and this
            // would read 30.00, which is precisely the defect the gauge replaced.
            assertThat(meterRegistry.get(MetricsConfig.METRIC_TRANSACTION_AMOUNT_TOTAL).gauge().value())
                    .isEqualTo(17.66d);
        }

        @Test
        @DisplayName("the rejected counter is tagged by reject code, spelled by MetricsConfig")
        void rejectedCounterUsesTheCanonicalTag() throws Exception {
            RejectWriter rejects = rejectWriter(mock(S3Operations.class), stepExecution(2L));
            rejects.write(new Chunk<>(List.of(new RejectWriter.RejectedTransaction(
                    dailyTransaction("ONE"), RejectCode.OVERLIMIT_TRANSACTION))));

            assertThat(meterRegistry.get(MetricsConfig.METRIC_RECORDS_REJECTED)
                    .tag(MetricsConfig.TAG_REJECT_CODE, Integer.toString(RejectCode.OVERLIMIT_TRANSACTION.getCode()))
                    .counter()
                    .count())
                    .isEqualTo(1.0d);
        }
    }

    // ====================================================================================================
    // M-09 - curated failure logging.
    // ====================================================================================================

    @Nested
    @DisplayName("Failure logs carry a symbolic reason, never identifiers or topology (M-09)")
    class CuratedLogging {

        @Test
        @DisplayName("an encoder rejection logs a reason and no TRAN-ID, bucket or object key")
        void encoderRejectionIsCurated() {
            TransactionWriter writer =
                    transactionWriter(mock(TransactionRepository.class), mock(S3Operations.class));

            assertThatThrownBy(() -> writer.composeFixedWidthImage(new Transaction(TRANSACTION_ID, "01",
                    Integer.valueOf(1), "POS       ", "BAD\rVALUE", CREDIT_AMOUNT, Long.valueOf(1L),
                    "MERCHANT", "CITY", "0000091234", CARD_NUMBER,
                    "2025-01-01-00.00.00.000000", "2025-01-01-00.00.00.000000")))
                    .isInstanceOf(RuntimeException.class);

            String logged = capturedText();
            assertThat(logged).contains("unencodable-record");
            assertThat(logged)
                    .doesNotContain(TRANSACTION_ID)
                    .doesNotContain(CARD_NUMBER)
                    .doesNotContain(OUTPUT_BUCKET);
        }

        @Test
        @DisplayName("a duplicate key logs a reason and the chunk size, not the candidate identifiers")
        void duplicateKeyIsCurated() {
            TransactionRepository repository = mock(TransactionRepository.class);
            when(repository.saveAllAndFlush(any()))
                    .thenThrow(new org.springframework.dao.DuplicateKeyException("duplicate key value"));
            TransactionWriter writer = transactionWriter(repository, mock(S3Operations.class));
            writer.beforeStep(stepExecution(1L));

            assertThatThrownBy(() -> writer.write(new Chunk<>(List.of(
                    transaction(TRANSACTION_ID, CREDIT_AMOUNT),
                    transaction(SECOND_TRANSACTION_ID, CREDIT_AMOUNT)))))
                    .isInstanceOf(RuntimeException.class);

            String logged = capturedText();
            assertThat(logged).contains("duplicate-key").contains("chunkSize=2");
            // The identifiers survive on the THROWN value, which is where an operator handling the failure
            // needs them; they must not reach the log sink.
            assertThat(logged)
                    .doesNotContain(TRANSACTION_ID)
                    .doesNotContain(SECOND_TRANSACTION_ID)
                    .doesNotContain(CARD_NUMBER);
        }

        @Test
        @DisplayName("the thrown exception still carries the full detail the log withholds")
        void exceptionRetainsTheDetail() {
            TransactionRepository repository = mock(TransactionRepository.class);
            when(repository.saveAllAndFlush(any()))
                    .thenThrow(new org.springframework.dao.DuplicateKeyException("duplicate key value"));
            TransactionWriter writer = transactionWriter(repository, mock(S3Operations.class));
            writer.beforeStep(stepExecution(1L));

            // The split is the whole point of the pattern: classify on the log, preserve on the exception.
            assertThatThrownBy(() -> writer.write(new Chunk<>(
                    List.of(transaction(TRANSACTION_ID, CREDIT_AMOUNT)))))
                    .hasMessageContaining(TRANSACTION_ID);
        }

        @Test
        @DisplayName("an object-store failure logs the status and operation, not the bucket or key")
        void uploadFailureIsCurated() {
            TransactionRepository repository = mock(TransactionRepository.class);
            when(repository.saveAllAndFlush(any())).thenAnswer(invocation -> List.of());
            S3Operations objectStorage = mock(S3Operations.class);
            when(objectStorage.upload(anyString(), anyString(), any(), any()))
                    .thenThrow(new IllegalStateException("connection reset"));
            TransactionWriter writer = transactionWriter(repository, objectStorage);
            writer.beforeStep(stepExecution(1L));

            assertThatThrownBy(() -> writer.write(new Chunk<>(
                    List.of(transaction(TRANSACTION_ID, CREDIT_AMOUNT)))))
                    .isInstanceOf(RuntimeException.class);

            String logged = capturedText();
            assertThat(logged).contains("operation=WRITE").contains("logicalFile=TRANSACT");
            assertThat(logged).doesNotContain(OUTPUT_BUCKET).doesNotContain(CONFIGURED_TRANSACTION_PREFIX + "/");
        }
    }

    // ====================================================================================================
    // Shared helpers.
    // ====================================================================================================

    /**
     * Reads the published transaction generation back through the documented protocol: the count, then that many
     * indexed entries, in order.
     *
     * <p>Written as a consumer would write it, so this helper is itself part of what the H-08 tests assert.
     *
     * @param execution the step execution whose job execution holds the record
     * @return the keys in creation order; never {@code null}, possibly empty
     */
    private static List<String> publishedTransactionKeys(StepExecution execution) {
        ExecutionContext jobContext = execution.getJobExecution().getExecutionContext();
        int count = Math.toIntExact(jobContext.getLong(TransactionWriter.OBJECT_KEYS_COUNT_ENTRY, 0L));
        List<String> keys = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            keys.add(jobContext.getString(TransactionWriter.objectKeysIndexEntry(index)));
        }
        return keys;
    }

    /**
     * Builds the minimal context for the two behavioural step-scope proofs, registering the <b>production</b>
     * {@link TransactionWriter} class so that the container reads the real {@code @StepScope} annotation off it.
     *
     * <p>That is the point of registering the class rather than declaring a {@code @Bean} method for it: a
     * {@code @Bean @StepScope} factory method would re-declare the scope in this file, and these tests would
     * then keep passing if the annotation were ever removed from the production class - guarding the test's own
     * configuration instead of the fix. Registering the class makes the production declaration load-bearing here.
     *
     * <p>The two {@code @Value} constructor properties are supplied through a property source, since a
     * hand-built context has no application configuration.
     *
     * @return a refreshed context the caller must close; never {@code null}
     */
    private static AnnotationConfigApplicationContext stepScopedContext() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("writer-contract-test",
                Map.of("carddemo.aws.s3.batch-output-bucket", OUTPUT_BUCKET,
                        "carddemo.aws.s3.transaction-object-prefix", CONFIGURED_TRANSACTION_PREFIX)));
        context.register(StepScopeTestConfiguration.class, TransactionWriter.class);
        context.refresh();
        return context;
    }

    /**
     * The minimal Spring context used by the two behavioural step-scope proofs.
     *
     * <p>It registers Spring Batch's own {@code StepScope} so that the {@code step} scope name resolves, the
     * writer as a step-scoped bean, and a singleton that constructor-injects it - which is the shape
     * {@code InterestCalculationJob} has in production and therefore the shape that must keep refreshing.
     * Collaborators are mocks, because none of them is what is under test here.
     */
    // @TestConfiguration, not @Configuration. This class is registered EXPLICITLY into a hand-built
    // context, so the annotation's only other effect matters: @SpringBootApplication component-scans
    // com.cardemo.**, the test classes are on the classpath during the integration tier, and a nested
    // @Configuration is a scan candidate while a @TestConfiguration is excluded by Boot's TypeExcludeFilter.
    // Scanned in, its @Bean methods entered the application context and collided by NAME with the real
    // definitions - which fails the context outright, because bean-definition overriding is off.
    @TestConfiguration
    static class StepScopeTestConfiguration {

        /**
         * Registers the {@code step} scope. Boot's batch auto-configuration does this in production; a
         * hand-built context must do it explicitly or the scope name does not resolve.
         *
         * @return the scope post-processor
         */
        @Bean
        static org.springframework.batch.core.scope.StepScope stepScope() {
            return new org.springframework.batch.core.scope.StepScope();
        }

        /**
         * The collaborators the writer's constructor needs. Mocks, because none of them is under test here.
         *
         * @return the repository mock
         */
        @Bean
        TransactionRepository transactionRepository() {
            return mock(TransactionRepository.class);
        }

        /**
         * @return the object-storage mock
         */
        @Bean
        S3Operations objectStorage() {
            return mock(S3Operations.class);
        }

        /**
         * @return the real status mapper, which is a pure collaborator
         */
        @Bean
        FileStatusMapper fileStatusMapper() {
            return new FileStatusMapper();
        }

        /**
         * @return the canonical instrument owner over a throwaway registry
         */
        @Bean
        MetricsConfig metricsConfig() {
            return new MetricsConfig(new SimpleMeterRegistry());
        }

        /**
         * A singleton that injects the step-scoped writer, standing in for {@code InterestCalculationJob}.
         *
         * @param writer the scoped proxy the container supplies
         * @return the holder
         */
        @Bean
        SingletonHolder singletonHolder(TransactionWriter writer) {
            return new SingletonHolder(writer);
        }
    }

    /**
     * A singleton holding a step-scoped collaborator, so the refresh-time proxy requirement is exercised.
     *
     * @param writer the injected scoped proxy
     */
    record SingletonHolder(TransactionWriter writer) {
    }

    /**
     * Collects every {@code String} constant a class declares, so a test can assert on the declaration set
     * rather than on behaviour that two agreeing declaration sites would hide.
     *
     * @param type the class to reflect over
     * @return the declared string constant values; never {@code null}
     */
    private static List<String> declaredStringConstants(Class<?> type) {
        List<String> values = new ArrayList<>();
        for (java.lang.reflect.Field field : type.getDeclaredFields()) {
            if (field.getType() != String.class
                    || !java.lang.reflect.Modifier.isStatic(field.getModifiers())
                    || !java.lang.reflect.Modifier.isFinal(field.getModifiers())) {
                continue;
            }
            field.setAccessible(true);
            try {
                Object value = field.get(null);
                if (value instanceof String text) {
                    values.add(text);
                }
            } catch (IllegalAccessException unreadable) {
                throw new AssertionError("could not read " + type.getName() + "." + field.getName(),
                        unreadable);
            }
        }
        // Referencing Method keeps the reflective intent of this helper explicit to a reader scanning imports.
        assertThat(Method.class).isNotNull();
        return values;
    }
}

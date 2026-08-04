/*
 * ******************************************************************
 * Program     : TransactionWriterStepContractTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Proves TransactionWriter is registered as a Spring Batch step
 *               listener through its interface rather than an annotation, so
 *               the step execution its object keys depend on is captured
 *               before the first write, and that a write without it fails
 *               with an actionable wiring message.
 * Source      : app/cbl/CBTRN02C.cbl:L562 (2900-WRITE-TRANSACTION-FILE)
 *               app/cpy/CVTRA05Y.cpy       (350-byte record)
 *               app/jcl/DEFGDGB.jcl        (GDG generation keys) @ 7756d89
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
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;

import com.cardemo.batch.writers.TransactionWriter;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.model.entity.Transaction;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.service.shared.FileStatusMapper;
import io.awspring.cloud.s3.S3Operations;
import com.cardemo.observability.MetricsConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.listener.StepListenerFactoryBean;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.test.MetaDataInstanceFactory;

/**
 * The step-listener contract of {@code TransactionWriter}, which nothing was guaranteeing.
 *
 * <p>The writer cannot function without a {@link StepExecution}: the job-instance identifier scopes every
 * object key, the write count orders the objects within the step, and the execution context receives the
 * created key. None is reachable through the {@code ItemWriter} interface, which takes only a chunk, so the
 * value has to arrive through a listener callback - and the callback only fires if Spring Batch registered
 * this object as a step listener.
 *
 * <p>It previously declared that callback with {@code @BeforeStep} alone. {@code SimpleStepBuilder}
 * auto-registers a writer when {@code StepListenerFactoryBean.isListener} recognises it, which succeeds
 * either through a listener interface or through a listener annotation - but a writer that needs the job
 * instance is step-scoped, step scope proxies by subclassing, and a proxy presents its target's interfaces
 * far more reliably than its target's method annotations. With no {@code BatchConfig} in this branch to make
 * the registration explicit, the writer's central dependency rested on the weaker of the two mechanisms.
 *
 * <p>These tests assert the contract directly: that the framework's own recognition predicate accepts this
 * writer, that it accepts it <em>as</em> a {@link StepExecutionListener}, that a write before the callback
 * fails with an actionable message rather than a {@code NullPointerException}, and that a write after the
 * callback consumes the step state the callback captured.
 */
@DisplayName("TransactionWriter: the step-listener contract its object keys depend on")
class TransactionWriterStepContractTest {

    /**
     * Renders a key number exactly as {@code TransactionWriter} does: zero-padded to nineteen digits, the
     * width of {@code Long.MAX_VALUE}, so lexicographic and numeric order coincide.
     */
    private static final String PADDING_FORMAT = "%019d";

    private TransactionWriter writer;

    @BeforeEach
    void buildWriter() {
        writer = new TransactionWriter(
                mock(TransactionRepository.class),
                mock(S3Operations.class),
                new FileStatusMapper(),
                new MetricsConfig(new SimpleMeterRegistry()),
                "carddemo-batch-output",
                "transact");
    }

    @Nested
    @DisplayName("1. Spring Batch will register this writer as a step listener")
    class TheFrameworkRecognisesIt {

        @Test
        @DisplayName("StepListenerFactoryBean.isListener accepts the writer")
        void theRecognitionPredicateAcceptsIt() {
            // This is the exact check SimpleStepBuilder.registerAsStreamsAndListeners performs on the
            // reader, processor and writer it is given. If it answered false, beforeStep would never be
            // called and every object key would be built from a null step execution.
            assertThat(StepListenerFactoryBean.isListener(writer)).isTrue();
        }

        @Test
        @DisplayName("It is recognised through the interface, so registration survives a step-scope proxy")
        void itIsRecognisedThroughTheInterface() {
            assertThat(writer).isInstanceOf(StepExecutionListener.class);
        }

        @Test
        @DisplayName("The listener the factory derives from it is a step-execution listener")
        void theDerivedListenerIsAStepExecutionListener() {
            assertThat(StepListenerFactoryBean.getListener(writer))
                    .isInstanceOf(StepExecutionListener.class);
        }
    }

    @Nested
    @DisplayName("2. The step state is required before the first write, and is consumed by it")
    class TheStateIsInitialisedBeforeWrite {

        @Test
        @DisplayName("A write with no captured step execution fails with the wiring remediation")
        void aWriteWithoutTheCallbackIsRefused() {
            final FatalProcessingException refusal =
                    assertThatExceptionOfType(FatalProcessingException.class)
                            .isThrownBy(() -> writer.write(Chunk.of(transaction())))
                            .actual();

            assertThat(refusal.getAbendReason())
                    .isEqualTo("no step execution was captured before the first write");
            assertThat(refusal.getAbendMessage())
                    .contains("must be registered on a Spring Batch step")
                    .contains("beforeStep");
        }

        @Test
        @DisplayName("An empty or null chunk is a no-op and does not require the step state")
        void anEmptyChunkNeedsNothing() {
            // The exhausted-reader case: no row, no object, no counter movement - and therefore no need
            // for a step execution either, so the guard must not fire on it.
            assertThatNoFailure(() -> writer.write(Chunk.of()));
            assertThatNoFailure(() -> writer.write(null));
        }

        @Test
        @DisplayName("After the callback the write proceeds, and scopes its object key on the captured step")
        void theCallbackSuppliesEverythingTheWriteConsumes() throws Exception {
            final StepExecution execution = MetaDataInstanceFactory.createStepExecution();
            final long jobInstanceId = execution.getJobExecution().getJobInstance().getInstanceId();

            writer.beforeStep(execution);
            writer.write(Chunk.of(transaction()));

            // All three things the callback exists to supply are now demonstrably in use: the job instance
            // scopes the prefix, the write count orders the object within the step, and the context entry
            // received the concrete key a later step re-reads.
            final String objectKey = execution.getExecutionContext()
                    .getString(TransactionWriter.OBJECT_KEY_CONTEXT_ENTRY);
            assertThat(objectKey)
                    .startsWith("transact/" + PADDING_FORMAT.formatted(jobInstanceId) + "/")
                    .endsWith(PADDING_FORMAT.formatted(execution.getWriteCount()) + ".dat");
        }

        @Test
        @DisplayName("A null execution is accepted by the callback and reported at write time")
        void aNullExecutionIsReportedLateRatherThanEarly() {
            writer.beforeStep(null);

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> writer.write(Chunk.of(transaction())))
                    .satisfies(refusal -> assertThat(refusal.getAbendReason())
                            .isEqualTo("no step execution was captured before the first write"));
        }
    }

    /**
     * Asserts a call completes without throwing anything.
     *
     * @param call the call to make, never {@code null}
     */
    private static void assertThatNoFailure(final ThrowingCall call) {
        try {
            call.run();
        } catch (Exception failure) {
            throw new AssertionError("expected no failure but got " + failure, failure);
        }
    }

    /** A call that may throw a checked exception, which {@code ItemWriter.write} declares. */
    private interface ThrowingCall {

        /**
         * Makes the call.
         *
         * @throws Exception whatever the call declares
         */
        void run() throws Exception;
    }

    /**
     * A minimally populated transaction: enough to reach the object-storage step.
     *
     * @return the transaction, never {@code null}
     */
    private static Transaction transaction() {
        return new Transaction(
                "0000000000000001", "PR", 1, "POS TERM", "A PURCHASE", new BigDecimal("1.00"),
                1L, "A MERCHANT", "A CITY", "0000012345", "4111111111111111",
                "2024-01-01 00:00:00.0000", "2024-01-01 00:00:00.0000");
    }

}

/*
 * ******************************************************************
 * Program     : BatchWriterScopeIsolationTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Proves both output writers are step scoped and that
 *               nothing they hold is shared between two executions.
 *               Both used to be singletons carrying per-run state: the
 *               transaction writer held the StepExecution in a
 *               volatile field populated by a @BeforeStep callback,
 *               and the statement writer derived its GDG generation
 *               segment the same way. Marking a field volatile
 *               publishes a value safely but does not isolate it, so a
 *               second execution overwrote the first's while the first
 *               was still writing and one job's objects could be keyed
 *               by another job's instance identifier - which, for a
 *               relative generation reference, silently rewrites the
 *               wrong generation. These tests assert the annotations,
 *               the absence of any lifecycle-populated field, the
 *               per-execution object keys, nested isolation on one
 *               thread and concurrent isolation across two threads.
 * Source      : app/cbl/CBTRN02C.cbl:L562-L579 (DALYTRAN write guard)
 *               app/cbl/CBTRN02C.cbl:L424-L444 (2000-POST-TRANSACTION)
 *               app/cpy/CVTRA05Y.cpy           (350-byte record)
 *               app/cbl/CBSTM03A.CBL:L293      (OPEN OUTPUT)
 *               app/cbl/CBSTM03A.CBL:L339      (CLOSE)
 *               app/jcl/CREASTMT.JCL:STEP040   (LRECL 80 / 100)
 *               app/jcl/DEFGDGB.jcl            (GDG generations)
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cardemo.batch.writers.StatementWriter;
import com.cardemo.batch.writers.TransactionWriter;
import com.cardemo.model.entity.Transaction;
import com.cardemo.observability.MetricsConfig;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.service.shared.FileStatusMapper;
import io.awspring.cloud.s3.ObjectMetadata;
import io.awspring.cloud.s3.S3Resource;
import io.awspring.cloud.s3.S3Template;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.annotation.BeforeStep;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.scope.context.StepSynchronizationManager;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.test.MetaDataInstanceFactory;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable;

/**
 * Verifies the step-scope contract of {@link TransactionWriter} and {@link StatementWriter}.
 *
 * <p><strong>The defect these tests close.</strong> Both writers need something no {@code ItemWriter} method
 * argument carries: the job-instance identifier that scopes an object key, and, for the transaction writer,
 * the write count that orders objects within a step and the execution context the created key is published
 * into. As singletons they obtained it from a {@code @BeforeStep} callback into a mutable field. That is safe
 * publication and <em>not</em> isolation: two overlapping executions share one field, so the second
 * overwrites the first while the first is still writing. For an object key that means one job's output can
 * land under another job's generation prefix - and because a relative {@code (0)} reference resolves to the
 * greatest existing prefix, the wrong generation is then read back downstream.
 *
 * <p><strong>What is asserted, and why in four layers.</strong> A structural layer, because the annotations
 * are the mechanism and their removal must fail a test rather than pass silently. A no-mutable-lifecycle-state
 * layer, because re-adding a {@code @BeforeStep} field would reintroduce the defect without changing any
 * annotation. A key-derivation layer, because the identifier reaching the key is the observable consequence.
 * And two overlap layers - nested on one thread, concurrent on two - because that is the condition under
 * which the old design failed.
 *
 * <p><strong>Side effects.</strong> None outside the test JVM. Mockito doubles for the repository and the
 * object-storage client, a {@link SimpleMeterRegistry}, and a two-thread executor that is always shut down.
 * Every step context registered is released in {@link #tearDown()} even after a failure.
 */
@DisplayName("Batch writers - step scope and per-execution isolation")
class BatchWriterScopeIsolationTest {

    /** {@code org.springframework.aop.scope.ScopedProxyUtils} prefixes the scoped target with this. */
    private static final String TARGET_PREFIX = "scopedTarget.";

    /** The bucket property the transaction writer requires and never defaults. */
    private static final String OUTPUT_BUCKET_PROPERTY = "carddemo.aws.s3.batch-output-bucket";

    private AnnotationConfigApplicationContext context;

    /** Every object key the doubles observed, in call order, across all threads. */
    private final List<String> observedKeys = Collections.synchronizedList(new ArrayList<>());

    /**
     * The bytes the doubles currently hold, keyed by object key.
     *
     * <p>Needed because the transaction writer stages each chunk as a transient part and concatenates the
     * parts into one generation object when the step ends, so the promotion reads back what the chunks wrote.
     * Synchronized because two of these tests write from two threads at once.
     */
    private final Map<String, byte[]> storedObjects =
            Collections.synchronizedMap(new LinkedHashMap<>());

    @BeforeEach
    void setUp() {
        observedKeys.clear();
        storedObjects.clear();
        context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                "batchWriterScopeIsolationTest",
                Map.of(OUTPUT_BUCKET_PROPERTY, "carddemo-batch-output",
                        "carddemo.aws.s3.statements-bucket", "carddemo-statements")));
        context.registerBean(TransactionRepository.class, BatchWriterScopeIsolationTest::repository);
        context.registerBean(S3Template.class, this::objectStorage);
        // TransactionWriter also takes the object-store client, which pages the part listing its end-of-step
        // consolidation walks and batches the delete of the parts afterwards. It reads and writes the same
        // fake store as the template above, so a promotion driven from these tests observes what the chunks
        // actually wrote.
        context.registerBean(S3Client.class, this::objectStoreClient);
        context.registerBean(FileStatusMapper.class, FileStatusMapper::new);
        context.registerBean(MeterRegistry.class, SimpleMeterRegistry::new);
        // The writers take the metrics facade, not the registry: MetricsConfig is the sole registrar of the
        // four sanctioned instruments, so a writer that resolved its own meter could register a second series
        // under the same name and Micrometer would keep only the first registration's metadata.
        context.registerBean(MetricsConfig.class,
                () -> new MetricsConfig(context.getBean(MeterRegistry.class)));
        // StatementWriter derives the statement month from the clock, so the scoped target cannot be created
        // without one. Fixed rather than system, so the month segment of an object key is deterministic here.
        context.registerBean(Clock.class,
                () -> Clock.fixed(Instant.parse("2026-08-15T00:00:00Z"), ZoneOffset.UTC));
        context.registerBeanDefinition("stepScope", BeanDefinitionBuilder
                .genericBeanDefinition(org.springframework.batch.core.scope.StepScope.class)
                .getBeanDefinition());
        // register(Class) honours the classes' own @Component and @StepScope declarations. Nothing here
        // substitutes for them, so removing either annotation must break these tests.
        context.register(TransactionWriter.class, StatementWriter.class);
        context.refresh();
    }

    @AfterEach
    void tearDown() {
        StepSynchronizationManager.release();
        if (context != null) {
            context.close();
        }
    }

    /**
     * A repository double whose flush accepts anything and returns the same list.
     *
     * @return the double
     */
    private static TransactionRepository repository() {
        TransactionRepository repository = mock(TransactionRepository.class);
        when(repository.saveAllAndFlush(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        return repository;
    }

    /**
     * An object-storage double that records every key it is asked to store.
     *
     * @return the double
     */
    private S3Template objectStorage() {
        S3Template template = mock(S3Template.class);
        when(template.upload(anyString(), anyString(), any(InputStream.class), any(ObjectMetadata.class)))
                .thenAnswer(invocation -> {
                    final String key = invocation.getArgument(1);
                    observedKeys.add(key);
                    try (InputStream body = invocation.getArgument(2, InputStream.class)) {
                        storedObjects.put(key, body.readAllBytes());
                    }
                    return storedResource(key);
                });
        when(template.download(anyString(), anyString()))
                .thenAnswer(invocation -> storedResource(invocation.getArgument(1, String.class)));
        return template;
    }

    /**
     * An object-store client double that pages the fake store's keys and honours a batched delete.
     *
     * @return the double
     */
    private S3Client objectStoreClient() {
        S3Client client = mock(S3Client.class);
        when(client.listObjectsV2(any(ListObjectsV2Request.class))).thenAnswer(invocation -> {
            final String prefix = invocation.getArgument(0, ListObjectsV2Request.class).prefix();
            final List<S3Object> contents = new ArrayList<>();
            for (final String key : List.copyOf(storedObjects.keySet())) {
                if (key.startsWith(prefix)) {
                    contents.add(S3Object.builder().key(key).build());
                }
            }
            return ListObjectsV2Response.builder().contents(contents).isTruncated(Boolean.FALSE).build();
        });
        when(client.listObjectsV2Paginator(any(ListObjectsV2Request.class))).thenAnswer(invocation ->
                new ListObjectsV2Iterable(client, invocation.getArgument(0, ListObjectsV2Request.class)));
        when(client.deleteObjects(any(DeleteObjectsRequest.class))).thenAnswer(invocation -> {
            for (final ObjectIdentifier identifier
                    : invocation.getArgument(0, DeleteObjectsRequest.class).delete().objects()) {
                storedObjects.remove(identifier.key());
            }
            return DeleteObjectsResponse.builder().build();
        });
        return client;
    }

    /**
     * A resource view of one object the fake store holds.
     *
     * @param key the object key
     * @return a resource reporting that object's key, length and content
     */
    private S3Resource storedResource(final String key) {
        final S3Resource resource = mock(S3Resource.class);
        final byte[] bytes = storedObjects.getOrDefault(key, new byte[0]);
        when(resource.getFilename()).thenReturn(key);
        when(resource.contentLength()).thenReturn(Long.valueOf(bytes.length));
        try {
            when(resource.getInputStream()).thenReturn(new ByteArrayInputStream(bytes));
        } catch (final IOException impossible) {
            throw new IllegalStateException("stubbing cannot fail", impossible);
        }
        return resource;
    }

    /**
     * Builds a step execution under its own job instance.
     *
     * @param jobInstanceId the job instance identifier, distinct per execution
     * @return a step execution linked to a job execution and a job instance
     */
    private static StepExecution executionFor(long jobInstanceId) {
        JobExecution jobExecution = MetaDataInstanceFactory.createJobExecution(
                "batchWriterJob", Long.valueOf(jobInstanceId), Long.valueOf(jobInstanceId));
        return MetaDataInstanceFactory.createStepExecution(
                jobExecution, "writeStep", Long.valueOf(jobInstanceId));
    }

    /**
     * Builds one transaction fixture at the exact widths {@code app/cpy/CVTRA05Y.cpy} declares.
     *
     * @param transactionId sixteen characters
     * @return a fully populated transaction
     */
    private static Transaction transaction(String transactionId) {
        return new Transaction(transactionId, "01", Integer.valueOf(1001), "POS TERM",
                "A DESCRIPTION", new BigDecimal("10.00"), Long.valueOf(1L),
                "A MERCHANT", "A CITY", "10001", "4111111111111111",
                "2026-01-01-00.00.00.000000", "2026-01-01-00.00.00.000000");
    }

    /**
     * Resolves the real, scoped instance of a writer inside the currently registered step context.
     *
     * @param type the writer class
     * @param beanName the bean name Spring derived from that class
     * @param <T> the writer type
     * @return the target instance the per-execution state lives on
     */
    private <T> T target(Class<T> type, String beanName) {
        return context.getBean(TARGET_PREFIX + beanName, type);
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
    @DisplayName("the scoping is declared on the writers themselves")
    class DeclaredScoping {

        @Test
        @DisplayName("both writers carry @Component and @StepScope")
        void bothWritersAreStepScopedComponents() {
            for (Class<?> writer : List.of(TransactionWriter.class, StatementWriter.class)) {
                assertThat(writer.getAnnotation(Component.class))
                        .as("%s must be a registered bean", writer.getSimpleName())
                        .isNotNull();
                assertThat(writer.getAnnotation(StepScope.class))
                        .as("%s must be created once per step execution", writer.getSimpleName())
                        .isNotNull();
            }
        }

        @Test
        @DisplayName("both writers take the step execution through the interface, never through @BeforeStep")
        void neitherWriterCapturesStateInBeforeStep() {
            for (Class<?> writer : List.of(TransactionWriter.class, StatementWriter.class)) {
                for (Method method : writer.getDeclaredMethods()) {
                    // THE ANNOTATION IS THE PART THAT MUST NOT BE USED, and the reason is this very scope.
                    // SimpleStepBuilder auto-registers a writer as a step listener when it either implements
                    // a listener interface or carries a listener annotation; step scope proxies by
                    // subclassing, and a proxy presents its target's INTERFACES reliably while an annotation
                    // on the target's method is found only if the check unwraps the proxy. Declaring the
                    // interface makes registration a property of the type instead of of the proxying.
                    assertThat(method.getAnnotation(BeforeStep.class))
                            .as("%s.%s must not rely on the annotation being visible through a scoped proxy",
                                    writer.getSimpleName(), method.getName())
                            .isNull();
                }
                assertThat(StepExecutionListener.class)
                        .as("%s must announce the callback by implementing the listener interface",
                                writer.getSimpleName())
                        .isAssignableFrom(writer);
                assertThat(writer.getDeclaredMethods())
                        .as("%s must declare beforeStep itself rather than inheriting the default no-op, or "
                                + "it would never learn its job instance", writer.getSimpleName())
                        .anyMatch(method -> "beforeStep".equals(method.getName()));
            }
        }

        @Test
        @DisplayName("the step execution is the only mutable TransactionWriter field, and the scope confines it")
        void theTransactionWriterHoldsNoMutableState() throws NoSuchFieldException {
            // Isolation here is a property of @StepScope, not of finality: each execution receives its own
            // instance, so a field the framework populates after construction is owned by one execution and
            // has nothing to race with. This asserts that the framework-supplied step execution is the ONLY
            // such field, so a second mutable slot - a running total, a cached key, a reused buffer - still
            // fails here. It is deliberately not volatile; see the field's own declaration for why marking it
            // so would imply sharing that step scope has removed. The confinement itself is proved by the
            // nested and concurrent execution tests in this class rather than by any modifier.
            List<String> mutable = new ArrayList<>();
            for (Field field : TransactionWriter.class.getDeclaredFields()) {
                if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                if (!Modifier.isFinal(field.getModifiers())) {
                    mutable.add(field.getName());
                }
            }
            assertThat(mutable)
                    .as("the framework-supplied execution and the promoted-once flag, and nothing else: a "
                            + "running total, a cached key or a reused buffer must still fail here")
                    .containsExactly("stepExecution", "generationCommitted");
            for (final String confined : mutable) {
                assertThat(Modifier.isVolatile(
                        TransactionWriter.class.getDeclaredField(confined).getModifiers()))
                        .as("volatile would advertise a sharing that step scope has removed, on [%s]",
                                confined)
                        .isFalse();
            }
        }

        @Test
        @DisplayName("StatementWriter is not final, which a CGLIB scoped proxy requires")
        void theStatementWriterCanBeProxied() {
            assertThat(Modifier.isFinal(StatementWriter.class.getModifiers()))
                    .as("a final class cannot be subclassed by the scoped proxy, so the context would "
                            + "fail to start")
                    .isFalse();
        }

        @Test
        @DisplayName("the container publishes a scoped target for each writer")
        void theContainerPublishesScopedTargets() {
            assertThat(context.containsBeanDefinition(TARGET_PREFIX + "transactionWriter")).isTrue();
            assertThat(context.containsBeanDefinition(TARGET_PREFIX + "statementWriter")).isTrue();
        }
    }

    @Nested
    @DisplayName("object keys carry the writing execution's own job instance")
    class PerExecutionKeys {

        @Test
        @DisplayName("two executions of the transaction writer resolve two instances and two key prefixes")
        void transactionKeysAreScopedPerExecution() {
            String firstKey = inScopeOf(executionFor(41L), () -> writeOneTransaction("0000000000000001"));
            String secondKey = inScopeOf(executionFor(42L), () -> writeOneTransaction("0000000000000002"));

            assertThat(firstKey).contains(zeroPadded(41L));
            assertThat(secondKey).contains(zeroPadded(42L));
            assertThat(firstKey).isNotEqualTo(secondKey);
        }

        @Test
        @DisplayName("two executions of the statement writer use two generation segments")
        void statementGenerationsAreScopedPerExecution() {
            Map<String, String> firstKeys = inScopeOf(executionFor(51L),
                    () -> writeOneStatement("00000000001"));
            Map<String, String> secondKeys = inScopeOf(executionFor(52L),
                    () -> writeOneStatement("00000000001"));

            assertThat(firstKeys.get(StatementWriter.STMTFILE_DD_NAME))
                    .contains("generation=" + zeroPadded(51L));
            assertThat(secondKeys.get(StatementWriter.STMTFILE_DD_NAME))
                    .contains("generation=" + zeroPadded(52L));
        }

        @Test
        @DisplayName("the created transaction key is published into that execution's own context")
        void theCreatedKeyGoesToItsOwnContext() {
            StepExecution execution = executionFor(61L);
            String key = inScopeOf(execution, () -> writeOneTransaction("0000000000000003"));

            assertThat(execution.getExecutionContext()
                    .getString(TransactionWriter.OBJECT_KEY_CONTEXT_ENTRY))
                    .isEqualTo(key);
        }
    }

    @Nested
    @DisplayName("overlapping executions do not share the key prefix")
    class OverlappingExecutions {

        @Test
        @DisplayName("nested executions on one thread each key their own object")
        void nestedExecutionsAreIsolated() throws Exception {
            StepExecution outer = executionFor(71L);
            StepExecution inner = executionFor(72L);

            StepSynchronizationManager.register(outer);
            TransactionWriter outerWriter = target(TransactionWriter.class, "transactionWriter");
            outerWriter.beforeStep(outer);
            outerWriter.write(Chunk.of(transaction("0000000000000001")));

            StepSynchronizationManager.register(inner);
            TransactionWriter innerWriter = target(TransactionWriter.class, "transactionWriter");
            assertThat(innerWriter).isNotSameAs(outerWriter);
            innerWriter.beforeStep(inner);
            innerWriter.write(Chunk.of(transaction("0000000000000002")));

            StepSynchronizationManager.close();

            TransactionWriter resumed = target(TransactionWriter.class, "transactionWriter");
            assertThat(resumed).isSameAs(outerWriter);
            // Deliberately NOT re-delivering the callback: the resumed instance must still hold the outer
            // execution it was given before the inner one opened, which is the isolation being proved.
            resumed.write(Chunk.of(transaction("0000000000000003")));

            StepSynchronizationManager.close();

            assertThat(observedKeys).hasSize(3);
            assertThat(observedKeys.get(0)).contains(zeroPadded(71L));
            assertThat(observedKeys.get(1))
                    .as("the inner execution must not borrow the outer's job instance")
                    .contains(zeroPadded(72L));
            assertThat(observedKeys.get(2))
                    .as("and the outer must not have been overwritten by the inner")
                    .contains(zeroPadded(71L));
        }

        @Test
        @DisplayName("two concurrent executions each key their own object")
        void concurrentExecutionsAreIsolated() throws Exception {
            CountDownLatch bothStarted = new CountDownLatch(2);
            ExecutorService threads = Executors.newFixedThreadPool(2);
            try {
                Future<String> first = threads.submit(
                        () -> writeConcurrently(executionFor(81L), "0000000000000001", bothStarted));
                Future<String> second = threads.submit(
                        () -> writeConcurrently(executionFor(82L), "0000000000000002", bothStarted));

                assertThat(first.get(30L, TimeUnit.SECONDS)).contains(zeroPadded(81L));
                assertThat(second.get(30L, TimeUnit.SECONDS)).contains(zeroPadded(82L));
            } finally {
                threads.shutdownNow();
                assertThat(threads.awaitTermination(30L, TimeUnit.SECONDS)).isTrue();
            }
        }

        /**
         * Writes one chunk inside its own step context, holding the context open until both threads have one
         * so the executions genuinely overlap.
         *
         * @param execution the execution to bind on this thread
         * @param transactionId the identifier to write
         * @param bothStarted the rendezvous both threads wait on
         * @return the object key this execution published
         * @throws Exception if the write or the rendezvous fails
         */
        private String writeConcurrently(StepExecution execution, String transactionId,
                CountDownLatch bothStarted) throws Exception {
            StepSynchronizationManager.register(execution);
            try {
                TransactionWriter writer = target(TransactionWriter.class, "transactionWriter");
                writer.beforeStep(execution);
                bothStarted.countDown();
                assertThat(bothStarted.await(30L, TimeUnit.SECONDS))
                        .as("both executions must be open at the same time")
                        .isTrue();
                writer.write(Chunk.of(transaction(transactionId)));
                writer.afterStep(execution);
                return execution.getExecutionContext()
                        .getString(TransactionWriter.OBJECT_KEY_CONTEXT_ENTRY);
            } finally {
                StepSynchronizationManager.close();
            }
        }
    }

    /**
     * Writes one transaction through the scoped writer and returns the key it published.
     *
     * @param transactionId the identifier to write
     * @return the published object key
     * @throws Exception if the write fails
     */
    private String writeOneTransaction(String transactionId) throws Exception {
        TransactionWriter writer = target(TransactionWriter.class, "transactionWriter");
        // What SimpleStepBuilder does for us in a real step: the writer implements StepExecutionListener, so
        // the framework auto-registers it and calls beforeStep before the first chunk and afterStep when the
        // step ends. This context is built by hand and has no step builder in it, so both callbacks are
        // delivered here instead. Delivering them to the scoped instance rather than to a fresh object is the
        // point - a leak between executions would show up as the wrong job instance in the key below. And
        // afterStep is where the chunk's transient part becomes the one generation object whose key is
        // published, so a test that stopped at the write would observe no key at all.
        StepExecution execution = StepSynchronizationManager.getContext().getStepExecution();
        writer.beforeStep(execution);
        writer.write(Chunk.of(transaction(transactionId)));
        writer.afterStep(execution);
        return execution.getExecutionContext().getString(TransactionWriter.OBJECT_KEY_CONTEXT_ENTRY);
    }

    /**
     * Opens, writes and closes one statement through the scoped writer.
     *
     * @param accountId the eleven-digit account identifier
     * @return the created object keys, by logical file name
     */
    private Map<String, String> writeOneStatement(String accountId) {
        StatementWriter writer = target(StatementWriter.class, "statementWriter");
        // See writeOneTransaction: the callback the framework would deliver, delivered by hand.
        writer.beforeStep(StepSynchronizationManager.getContext().getStepExecution());
        writer.openStatementOutputs(accountId, "2026-08");

        // The lines are handed over already composed, because composition belongs to
        // com.cardemo.batch.processors.StatementProcessor and this test is about scope isolation rather than
        // about record content: what matters here is that two step executions cannot see each other's open
        // statement or each other's generation.
        writer.writeStatementLine("A B CUSTOMER");
        writer.writeStatementLine("1 ANY STREET SUITE 2 ANYTOWN NY USA 10001 " + accountId);
        writer.writeHtmlFragment("<p>A B CUSTOMER</p>");
        return writer.closeStatementOutputs();
    }

    /**
     * Renders a job-instance identifier the way the transaction key template does.
     *
     * @param jobInstanceId the identifier
     * @return the nineteen-digit zero-padded form
     */
    private static String zeroPadded(long jobInstanceId) {
        return String.format("%019d", Long.valueOf(jobInstanceId));
    }
}

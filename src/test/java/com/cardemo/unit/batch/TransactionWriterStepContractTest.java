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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cardemo.batch.writers.TransactionWriter;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.model.entity.Transaction;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.service.shared.FileStatusMapper;
import io.awspring.cloud.s3.ObjectMetadata;
import io.awspring.cloud.s3.S3Operations;
import io.awspring.cloud.s3.S3Resource;
import com.cardemo.observability.MetricsConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.listener.StepListenerFactoryBean;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.test.MetaDataInstanceFactory;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable;

/**
 * The step-listener contract of {@code TransactionWriter}, which nothing was guaranteeing.
 *
 * <p>The writer cannot function without a {@link StepExecution}: the job-instance identifier scopes every
 * object key, the write count orders the objects within the step, and the execution context receives the
 * created key. None is reachable through the {@code ItemWriter} interface, which takes only a chunk, so the
 * value has to arrive through a listener callback - and the callback only fires if Spring Batch registered
 * this object as a step listener.
 *
 * <p>Declaring that callback with {@code @BeforeStep} alone is not enough. {@code SimpleStepBuilder}
 * auto-registers a writer when {@code StepListenerFactoryBean.isListener} recognises it, which succeeds
 * either through a listener interface or through a listener annotation - but a writer that needs the job
 * instance is step-scoped, step scope proxies by subclassing, and a proxy presents its target's interfaces
 * far more reliably than its target's method annotations. Resting the writer's central dependency on the
 * weaker of the two mechanisms is what the interface declaration and {@code BatchConfig}'s explicit
 * registration together prevent.
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

    /** The destination bucket, a name and never an address. */
    private static final String BUCKET = "carddemo-batch-output";

    private TransactionWriter writer;

    /**
     * The fake object store both collaborators share: every object it currently holds, insertion ordered.
     *
     * <p>Needed because the writer stages each chunk as a transient part and concatenates the parts into the
     * one generation object when the step ends, so the key this suite asserts only exists after a listing, a
     * length measurement and an upload have all been answered.
     */
    private Map<String, byte[]> store;

    @BeforeEach
    void buildWriter() {
        store = new LinkedHashMap<>();
        final S3Operations objectStorage = mock(S3Operations.class);
        writer = new TransactionWriter(
                mock(TransactionRepository.class),
                objectStorage,
                stubObjectStore(objectStorage),
                new FileStatusMapper(),
                new MetricsConfig(new SimpleMeterRegistry()),
                BUCKET,
                "transact");
    }

    /**
     * Wires the fake store behind {@link S3Operations} and returns the paging client over the same map.
     *
     * <p>The part listing and the part deletion go through {@link S3Client} rather than
     * {@code S3Operations.listObjects}, because one {@code ListObjectsV2} truncates at a thousand keys and a
     * run staging more parts than that would concatenate only the first page - finding H-11. One untruncated
     * page is enough for this suite's single-chunk fixtures.
     *
     * @param objectStorage the upload and download half, never {@code null}
     * @return the listing and delete half, never {@code null}
     */
    private S3Client stubObjectStore(final S3Operations objectStorage) {
        when(objectStorage.upload(anyString(), anyString(), any(InputStream.class),
                any(ObjectMetadata.class))).thenAnswer(invocation -> {
                    final String key = invocation.getArgument(1, String.class);
                    try (InputStream body = invocation.getArgument(2, InputStream.class)) {
                        store.put(key, body.readAllBytes());
                    }
                    return storedResource(key);
                });
        when(objectStorage.download(anyString(), anyString()))
                .thenAnswer(invocation -> storedResource(invocation.getArgument(1, String.class)));

        final S3Client objectStoreClient = mock(S3Client.class);
        when(objectStoreClient.listObjectsV2(any(ListObjectsV2Request.class))).thenAnswer(invocation -> {
            final String prefix = invocation.getArgument(0, ListObjectsV2Request.class).prefix();
            final List<S3Object> contents = new ArrayList<>();
            for (final String key : new ArrayList<>(store.keySet())) {
                if (key.startsWith(prefix)) {
                    contents.add(S3Object.builder().key(key).build());
                }
            }
            return ListObjectsV2Response.builder().contents(contents).isTruncated(Boolean.FALSE).build();
        });
        when(objectStoreClient.listObjectsV2Paginator(any(ListObjectsV2Request.class)))
                .thenAnswer(invocation -> new ListObjectsV2Iterable(objectStoreClient,
                        invocation.getArgument(0, ListObjectsV2Request.class)));
        when(objectStoreClient.deleteObjects(any(DeleteObjectsRequest.class))).thenAnswer(invocation -> {
            for (final ObjectIdentifier identifier
                    : invocation.getArgument(0, DeleteObjectsRequest.class).delete().objects()) {
                store.remove(identifier.key());
            }
            return DeleteObjectsResponse.builder().build();
        });
        return objectStoreClient;
    }

    /**
     * A resource view of one object the fake store holds.
     *
     * @param key the object key
     * @return a resource reporting that object's key, length and content
     */
    private S3Resource storedResource(final String key) {
        final S3Resource resource = mock(S3Resource.class);
        final byte[] bytes = store.getOrDefault(key, new byte[0]);
        when(resource.getFilename()).thenReturn(key);
        when(resource.contentLength()).thenReturn(Long.valueOf(bytes.length));
        try {
            when(resource.getInputStream()).thenReturn(new ByteArrayInputStream(bytes));
        } catch (final IOException impossible) {
            throw new IllegalStateException("stubbing cannot fail", impossible);
        }
        return resource;
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

            // The chunk's bytes are a transient part, named by the job execution and the write count, so the
            // callback's write count is demonstrably in use before the step ends.
            assertThat(store.keySet())
                    .as("the chunk staged one part under the generation prefix the job instance scopes")
                    .containsExactly("transact/" + PADDING_FORMAT.formatted(jobInstanceId)
                            + "/parts/transact-part-"
                            + PADDING_FORMAT.formatted(execution.getJobExecution().getId())
                            + "-" + PADDING_FORMAT.formatted(execution.getWriteCount()) + ".dat");

            writer.afterStep(execution);

            // All three things the callback exists to supply are now demonstrably in use: the job instance
            // scopes the prefix, the write count ordered the part within the step, and the context entry
            // received the concrete generation key a later step re-reads.
            final String objectKey = execution.getExecutionContext()
                    .getString(TransactionWriter.OBJECT_KEY_CONTEXT_ENTRY);
            assertThat(objectKey)
                    .as("app/jcl/POSTTRAN.jcl names ONE dataset, so the published key names one object and "
                            + "not the last chunk of the run")
                    .isEqualTo("transact/" + PADDING_FORMAT.formatted(jobInstanceId) + "/transact-"
                            + PADDING_FORMAT.formatted(execution.getJobExecution().getId()) + ".dat");
            assertThat(store.keySet())
                    .as("and the parts it was assembled from are gone")
                    .containsExactly(objectKey);
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

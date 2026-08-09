/*
 * ******************************************************************
 * Program     : TransactionWriterTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Verifies the TRANSACT write boundary against the COBOL
 *               it reproduces: the relational insert and the 350-byte
 *               fixed-width emission performed as one unit, the 20-byte
 *               FILLER without which the image is 330 bytes, the
 *               trailing-sign overpunch on TRAN-AMT, the refusal to
 *               truncate any field that overflows its picture clause,
 *               the duplicate-key outcome that the identifier idiom
 *               makes reachable, the step-scoped object key, and the
 *               write-failure path that DISPLAYs then abends.
 * Source      : app/cbl/CBTRN02C.cbl:L562-L579 (2900-WRITE-TRANSACTION-FILE)
 *               app/cbl/CBTRN02C.cbl:L714-L727 (9910-DISPLAY-IO-STATUS)
 *               app/cbl/CBTRN02C.cbl:L707-L712 (9999-ABEND-PROGRAM)
 *               app/cbl/COTRN02C.cbl:L444-L451 (descending-browse ID)
 *               app/cbl/CBACT04C.cbl:L473-L515 (global suffix counter)
 *               app/cpy/CVTRA05Y.cpy           (350-byte TRAN-RECORD)
 *               app/jcl/TRANFILE.jcl:L53-L54   (KEYS(16 0), RECORDSIZE(350 350))
 *               app/catlg/LISTCAT.txt:L351-L360 (TRANSACT AIX) @ 7756d89
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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cardemo.batch.writers.TransactionWriter;
import com.cardemo.exception.DataIntegrityException;
import com.cardemo.exception.DuplicateRecordException;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.exception.FileAccessException;
import com.cardemo.model.entity.Transaction;
import com.cardemo.observability.MetricsConfig;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.service.shared.FileStatusMapper;
import io.awspring.cloud.s3.ObjectMetadata;
import io.awspring.cloud.s3.S3Operations;
import io.awspring.cloud.s3.S3Resource;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable;

/**
 * Unit tests for {@link TransactionWriter}, the Java form of {@code 2900-WRITE-TRANSACTION-FILE} at
 * {@code app/cbl/CBTRN02C.cbl:L562-L579}.
 *
 * <h2>What it does</h2>
 * <p>
 * The legacy paragraph performs one {@code WRITE} to an indexed cluster. The Java writer performs two things
 * that must both succeed: the row goes into the relational table that replaced the cluster, and the identical
 * 350-byte image goes to the object store so that Gate 1 can compare bytes against the mainframe baseline.
 * This suite fixes both halves. It proves the image is {@code 350} characters because
 * {@code app/cpy/CVTRA05Y.cpy} says so and because {@code app/jcl/TRANFILE.jcl:L54} declares
 * {@code RECORDSIZE(350 350)}, that the twenty-byte {@code FILLER} is present, that {@code TRAN-AMT} carries
 * its sign in the final byte through the zoned-decimal overpunch, that no field is ever truncated to fit, and
 * that a duplicate {@code TRAN-ID} surfaces as a duplicate rather than being upserted away - the collision is
 * the intended observable outcome of re-driving the interest job with a used date parameter.
 *
 * <h2>How to build and test</h2>
 * <pre>
 * ./mvnw -B -ntp -Dtest='TransactionWriterTest' -DfailIfNoTests=false test
 * </pre>
 *
 * <h2>Key configuration and defaults</h2>
 * <ul>
 * <li>{@code carddemo.aws.s3.batch-output-bucket} - required, non-blank; {@value #BUCKET} here.</li>
 * <li>{@code carddemo.aws.s3.transaction-object-prefix} - defaults to {@code transact}.</li>
 * <li>The step execution is captured by the {@code @BeforeStep} callback. There is no default: without it
 * there is no job instance to scope the object key on, and the writer fails fast rather than inventing one.
 * </li>
 * </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 * <ul>
 * <li><b>Blocker.</b> A failure in group 3 or group 4 means the emitted bytes no longer match the mainframe's.
 * Every subsequent record in the file shifts, so the parity comparison fails wholesale.</li>
 * <li><b>Blocker.</b> A failure in group 5 means a duplicate identifier is no longer distinguished from a
 * plain constraint violation, or is being silently absorbed. The distinction is drawn on {@code SQLSTATE}
 * {@code 23505} through {@code FileStatusMapper.classifyStoreFailure} rather than on which exception subtype
 * the persistence layer chose. Catching {@code DuplicateKeyException} ahead of its supertype
 * recognises a collision only when that subtype was chosen, and for a BATCHED flush it
 * need not be: the driver reports the batch and links the exception carrying the state of the failing entry
 * beneath it. Both shapes are asserted in group 5 for exactly that reason.</li>
 * <li><b>High.</b> A failure in group 7 means a lost object write would be reported as a clean run.</li>
 * <li><b>High.</b> A failure in group 2 means the writer would compose an object key from an absent job
 * instance, so two runs could collide on one key.</li>
 * <li><b>Medium.</b> A failure in group 6 means the downstream step reads the wrong object key from the
 * execution context.</li>
 * </ul>
 */
@DisplayName("TransactionWriter: CBTRN02C's insert and 350-byte TRANSACT image")
class TransactionWriterTest {

    /** The destination bucket. */
    private static final String BUCKET = "carddemo-batch-output";

    /** The default object prefix, as {@code application.yml} configures it. */
    private static final String PREFIX = "transact";

    /**
     * The base name inside every key, which is a constant in the writer and not the configured prefix.
     *
     * <p>It happens to equal {@link #PREFIX} under the shipped configuration, and stating it separately is
     * what keeps the two distinguishable: a test that reused {@code PREFIX} for both would pass even if the
     * writer started naming its objects after the configured value.
     */
    private static final String BASE_NAME = "transact";

    /** The job instance identifier the object key is scoped on. */
    private static final long JOB_INSTANCE_ID = 7L;

    /** {@code TRAN-RECORD}, the record length {@code app/cpy/CVTRA05Y.cpy} declares. */
    private static final int RECORD_LENGTH = 350;

    /** The job execution identifier, which names the generation object and separates one attempt's parts. */
    private static final long JOB_EXECUTION_ID = 7L;

    private TransactionRepository repository;
    private S3Operations objectStorage;

    /**
     * The paging and batch-deleting half of the object store.
     *
     * <p>The writer stages each chunk as a transient part and concatenates every part into the one generation
     * object when the step ends, and it lists those parts through {@code listObjectsV2Paginator} rather than
     * through {@code S3Operations.listObjects}: one {@code ListObjectsV2} truncates at a thousand keys, and
     * the posting step commits once per record by parity contract, so a page-bounded listing would have
     * concatenated the first thousand records and published them as the whole day.
     */
    private S3Client objectStoreClient;

    /** A fake object store both collaborators share: every object it currently holds, insertion ordered. */
    private Map<String, byte[]> store;

    private MeterRegistry meterRegistry;

    /**
     * The sole registrar of the four application counters. The writer counts through this collaborator, so
     * the registry above is read only to assert what was registered under it.
     */
    private MetricsConfig metricsConfig;
    private StepExecution stepExecution;
    private TransactionWriter writer;

    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger logger;
    private Level originalLevel;

    @BeforeEach
    void buildWriterAndCaptureLogs() {
        repository = Mockito.mock(TransactionRepository.class);
        objectStorage = Mockito.mock(S3Operations.class);
        objectStoreClient = Mockito.mock(S3Client.class);
        store = new LinkedHashMap<>();
        stubObjectStore();
        meterRegistry = new SimpleMeterRegistry();
        metricsConfig = new MetricsConfig(meterRegistry);
        writer = new TransactionWriter(repository, objectStorage, objectStoreClient, new FileStatusMapper(),
                metricsConfig, BUCKET, PREFIX);
        stepExecution = stepExecution(JOB_INSTANCE_ID);
        writer.beforeStep(stepExecution);

        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(TransactionWriter.class);
        originalLevel = logger.getLevel();
        logger.setLevel(Level.TRACE);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(originalLevel);
    }

    // ------------------------------------------------------------------------------------------------
    // Fixtures and helpers
    // ------------------------------------------------------------------------------------------------

    /**
     * Builds a step execution whose job instance identifier is known, so the composed object key is fully
     * determined by the test.
     *
     * @param instanceId the job instance identifier
     * @return the execution the framework would hand to the listener callback
     */
    private static StepExecution stepExecution(final long instanceId) {
        JobExecution jobExecution = new JobExecution(new JobInstance(Long.valueOf(instanceId), "POSTTRAN"),
                Long.valueOf(JOB_EXECUTION_ID), new JobParameters());
        return new StepExecution("dailyTransactionPostingStep", jobExecution);
    }

    /**
     * Wires the fake store behind both collaborators, so a part is a complete object the promotion can read
     * back and the promotion's own upload is observable.
     *
     * <p>The listing stub reports a single untruncated page, which is enough for every fixture here bar the
     * paging test, and {@code listObjectsV2Paginator} returns a real {@link ListObjectsV2Iterable} over the
     * same stub so the protocol is exercised rather than simulated.
     */
    private void stubObjectStore() {
        Mockito.when(objectStorage.upload(Mockito.anyString(), Mockito.anyString(),
                        Mockito.any(InputStream.class), Mockito.any(ObjectMetadata.class)))
                .thenAnswer(invocation -> {
                    final String key = invocation.getArgument(1, String.class);
                    try (InputStream body = invocation.getArgument(2, InputStream.class)) {
                        store.put(key, body.readAllBytes());
                    }
                    return storedResource(key);
                });
        Mockito.when(objectStorage.download(Mockito.anyString(), Mockito.anyString()))
                .thenAnswer(invocation -> storedResource(invocation.getArgument(1, String.class)));
        Mockito.when(objectStoreClient.listObjectsV2(Mockito.any(ListObjectsV2Request.class)))
                .thenAnswer(invocation -> {
                    final String prefix = invocation.getArgument(0, ListObjectsV2Request.class).prefix();
                    final List<String> matching = new ArrayList<>();
                    for (final String key : new ArrayList<>(store.keySet())) {
                        if (key.startsWith(prefix)) {
                            matching.add(key);
                        }
                    }
                    java.util.Collections.sort(matching);
                    final List<S3Object> contents = new ArrayList<>(matching.size());
                    for (final String key : matching) {
                        contents.add(S3Object.builder().key(key).build());
                    }
                    return ListObjectsV2Response.builder()
                            .contents(contents)
                            .isTruncated(Boolean.FALSE)
                            .build();
                });
        Mockito.when(objectStoreClient.listObjectsV2Paginator(Mockito.any(ListObjectsV2Request.class)))
                .thenAnswer(invocation -> new ListObjectsV2Iterable(objectStoreClient,
                        invocation.getArgument(0, ListObjectsV2Request.class)));
        Mockito.when(objectStoreClient.deleteObjects(Mockito.any(DeleteObjectsRequest.class)))
                .thenAnswer(invocation -> {
                    for (final ObjectIdentifier identifier
                            : invocation.getArgument(0, DeleteObjectsRequest.class).delete().objects()) {
                        store.remove(identifier.key());
                    }
                    return DeleteObjectsResponse.builder().build();
                });
    }

    /**
     * A resource view of one object the fake store holds.
     *
     * @param key the object key
     * @return a resource reporting that object's key, length and content
     */
    private S3Resource storedResource(final String key) {
        final S3Resource resource = Mockito.mock(S3Resource.class);
        final byte[] bytes = store.getOrDefault(key, new byte[0]);
        Mockito.when(resource.getFilename()).thenReturn(key);
        Mockito.when(resource.contentLength()).thenReturn(Long.valueOf(bytes.length));
        try {
            Mockito.when(resource.getInputStream()).thenReturn(new ByteArrayInputStream(bytes));
        } catch (final java.io.IOException impossible) {
            throw new IllegalStateException("stubbing cannot fail", impossible);
        }
        return resource;
    }

    /**
     * The key of the transient part one chunk of this step stages.
     *
     * @param instanceId the job instance the generation prefix is scoped on
     * @param ordinal the zero-based write ordinal of the chunk's first record
     * @return the part key
     */
    private static String partKey(final long instanceId, final long ordinal) {
        return String.format(Locale.ROOT, "%s/%019d/parts/%s-part-%019d-%019d.dat", PREFIX, instanceId,
                BASE_NAME, JOB_EXECUTION_ID, ordinal);
    }

    /**
     * The key of the one generation object this step's parts are concatenated into.
     *
     * @param instanceId the job instance the generation prefix is scoped on
     * @return the generation object key
     */
    private static String generationKey(final long instanceId) {
        return String.format(Locale.ROOT, "%s/%019d/%s-%019d.dat", PREFIX, instanceId, BASE_NAME,
                JOB_EXECUTION_ID);
    }

    /**
     * Builds one {@code TRAN-RECORD} in the {@code CVTRA05Y} layout, every field exactly as wide as its
     * picture clause so the image can be asserted by offset.
     *
     * @param transactionId {@code TRAN-ID PIC X(16)}
     * @param amount {@code TRAN-AMT PIC S9(09)V99}
     * @return the posted transaction
     */
    private static Transaction posted(final String transactionId, final String amount) {
        return new Transaction(
                transactionId,
                "01",
                Integer.valueOf(5001),
                "POS TERM  ",
                pad("Payment at Amazon", 100),
                new BigDecimal(amount),
                Long.valueOf(123456789L),
                pad("Amazon.com", 50),
                pad("Seattle", 50),
                "0000098101",
                "4111111111111111",
                "2022-07-18-11.22.33.123456",
                "2022-07-18-11.22.34.123456");
    }

    /** @return the canonical posted transaction: identifier 1, amount 100.00. */
    private static Transaction posted() {
        return posted("0000000000000001", "100.00");
    }

    /**
     * Right-pads a value with spaces to the width of its picture clause.
     *
     * @param value the value
     * @param width the field width
     * @return the padded value
     */
    private static String pad(final String value, final int width) {
        return value + " ".repeat(width - value.length());
    }

    /**
     * Writes one private field of a {@link Transaction}, so that a field state the entity's own guards refuse
     * to construct still reaches the writer's own geometry guards. Those guards are the last check before
     * bytes leave the process; a guard no test can reach is a guard nobody can trust.
     *
     * @param target the row to mutate
     * @param name the declared field name on {@link Transaction}
     * @param value the value to write
     */
    private static void setField(final Transaction target, final String name, final Object value) {
        try {
            java.lang.reflect.Field field = Transaction.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("cannot write " + name + " on Transaction", failure);
        }
    }

    /**
     * Builds a chunk of transactions.
     *
     * @param items the items
     * @return the chunk a chunk-oriented step would hand to the writer
     */
    private static Chunk<Transaction> chunk(final Transaction... items) {
        return new Chunk<>(List.of(items));
    }

    /**
     * The payload of the single object the writer created, decoded in the record charset.
     *
     * <p>Read from the fake store rather than from a captured stream: the store consumes the upload's stream
     * as the real one does, so a captured stream would already be at its end. The stored bytes are what the
     * object holds, which is the thing every geometry assertion is actually about.
     *
     * @return the bytes of the one object, decoded
     */
    private String uploadedPayload() {
        return new String(uploadedBytes(), StandardCharsets.ISO_8859_1);
    }

    /** @return the raw bytes of the single object the writer created. */
    private byte[] uploadedBytes() {
        assertThat(store).as("exactly one object was created").hasSize(1);
        return store.values().iterator().next();
    }

    /** @return the object key of the single object the writer created. */
    private String uploadedKey() {
        assertThat(store).as("exactly one object was created").hasSize(1);
        return store.keySet().iterator().next();
    }

    /** @return the metadata of the single upload the writer performed. */
    private ObjectMetadata uploadedMetadata() {
        ArgumentCaptor<ObjectMetadata> captor = ArgumentCaptor.forClass(ObjectMetadata.class);
        Mockito.verify(objectStorage).upload(Mockito.eq(BUCKET), Mockito.anyString(),
                Mockito.any(InputStream.class), captor.capture());
        return captor.getValue();
    }

    /** @return every message this class's logger received. */
    private List<String> loggedMessages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /** @return the value of the processed-records counter. */
    private double processedCount() {
        io.micrometer.core.instrument.Counter counter =
                meterRegistry.find("carddemo.batch.records.processed").counter();
        return counter == null ? 0.0d : counter.count();
    }

    /**
     * Makes the object store fail every upload.
     *
     * @param cause the failure the store raises
     */
    private void makeStoreFail(final RuntimeException cause) {
        Mockito.when(objectStorage.upload(Mockito.anyString(), Mockito.anyString(),
                Mockito.any(InputStream.class), Mockito.any(ObjectMetadata.class))).thenThrow(cause);
    }

    @Nested
    @DisplayName("1. Construction: the collaborators and the configuration the write cannot proceed without")
    class Construction {

        @ParameterizedTest(name = "a null {0} is refused by name")
        @ValueSource(strings = {"transactionRepository", "objectStorage", "objectStoreClient",
                "fileStatusMapper", "metrics"})
        @DisplayName("every collaborator is refused by name when absent")
        void everyCollaboratorIsRefusedByName(final String absent) {
            TransactionRepository repositoryArgument = "transactionRepository".equals(absent) ? null : repository;
            S3Operations storeArgument = "objectStorage".equals(absent) ? null : objectStorage;
            S3Client clientArgument = "objectStoreClient".equals(absent) ? null : objectStoreClient;
            FileStatusMapper mapperArgument =
                    "fileStatusMapper".equals(absent) ? null : new FileStatusMapper();
            MetricsConfig metricsArgument = "metrics".equals(absent) ? null : metricsConfig;

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new TransactionWriter(repositoryArgument, storeArgument, clientArgument,
                            mapperArgument, metricsArgument, BUCKET, PREFIX))
                    .withMessage(absent + " must not be null");
        }

        @ParameterizedTest(name = "a bucket of [{0}] is refused")
        @ValueSource(strings = {"", "   "})
        @DisplayName("a blank bucket is refused, naming the property that must be configured")
        void aBlankBucketIsRefused(final String bucket) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new TransactionWriter(repository, objectStorage, objectStoreClient,
                            new FileStatusMapper(), metricsConfig, bucket, PREFIX))
                    .withMessage("carddemo.aws.s3.batch-output-bucket must be configured with a non-blank value");
        }

        @Test
        @DisplayName("a null bucket is refused for the same reason")
        void aNullBucketIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new TransactionWriter(repository, objectStorage, objectStoreClient,
                            new FileStatusMapper(), metricsConfig, null, PREFIX))
                    .withMessage("carddemo.aws.s3.batch-output-bucket must be configured with a non-blank value");
        }

        @Test
        @DisplayName("a blank object prefix is refused, naming its own property and the hazard")
        void aBlankPrefixIsRefused() {
            // Finding m-02 moved the prefix grammar into com.cardemo.batch.GenerationPrefixContract, so the
            // wording of this diagnostic is that class's rather than this one's. The assertion holds the
            // CONTRACT - the property is named and the reason is given - instead of the exact sentence, which
            // is what it should always have held: a message this test spells out in full can only be changed
            // by editing the test, and that makes an improvement to a diagnostic look like a regression.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new TransactionWriter(repository, objectStorage, objectStoreClient,
                            new FileStatusMapper(), metricsConfig, BUCKET, "  "))
                    .withMessageStartingWith("carddemo.aws.s3.transaction-object-prefix")
                    .withMessageContaining("must not be blank")
                    .withMessageContaining("bucket root");
        }

        @Test
        @DisplayName("finding m-02: a malformed object prefix is refused by the SHARED grammar, so this "
                + "writer cannot drift from the five other object-key consumers")
        void aMalformedPrefixIsRefusedByTheSharedGrammar() {
            for (final String malformed : java.util.List.of(
                    "transact/", "/transact", "transact//mirror", "transact/../mirror", " transact")) {
                assertThatExceptionOfType(IllegalArgumentException.class)
                        .as("'%s' must be refused rather than silently rewritten", malformed)
                        .isThrownBy(() -> new TransactionWriter(repository, objectStorage, objectStoreClient,
                                new FileStatusMapper(), metricsConfig, BUCKET, malformed))
                        .withMessageStartingWith("carddemo.aws.s3.transaction-object-prefix");
            }
        }
    }

    @Nested
    @DisplayName("2. HIGH: the step context the object key is scoped on")
    class StepContext {

        @Test
        @DisplayName("writing before the listener callback fails fast rather than inventing a key")
        void writingBeforeTheCallbackFailsFast() {
            TransactionWriter detached = new TransactionWriter(repository, objectStorage, objectStoreClient,
                    new FileStatusMapper(), metricsConfig, BUCKET, PREFIX);

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> detached.write(chunk(posted())))
                    .satisfies(failure -> assertThat(failure.getAbendReason())
                            .isEqualTo("no step execution was captured before the first write"));
        }

        @Test
        @DisplayName("writing before the callback tells the operator what to wire, and stores nothing")
        void writingBeforeTheCallbackStoresNothing() throws Exception {
            TransactionWriter detached = new TransactionWriter(repository, objectStorage, objectStoreClient,
                    new FileStatusMapper(), metricsConfig, BUCKET, PREFIX);

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> detached.write(chunk(posted())));

            assertThat(loggedMessages())
                    .anyMatch(message -> message.contains("must be registered on a Spring Batch step"));
            Mockito.verify(repository, Mockito.never()).saveAllAndFlush(Mockito.anyList());
        }

        @Test
        @DisplayName("a step execution without a job instance is refused rather than defaulted to zero")
        void aStepExecutionWithoutAJobInstanceIsRefused() {
            writer.beforeStep(new StepExecution("dailyTransactionPostingStep",
                    new JobExecution(Long.valueOf(11L))));

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> writer.write(chunk(posted())))
                    .satisfies(failure -> assertThat(failure.getAbendReason())
                            .isEqualTo("the step execution carries no job instance"));
        }

        @Test
        @DisplayName("a later callback replaces the captured execution, as a restarted step requires")
        void aLaterCallbackReplacesTheExecution() throws Exception {
            writer.beforeStep(stepExecution(9L));

            writer.write(chunk(posted()));

            assertThat(uploadedKey())
                    .as("the object key must follow the current job instance, not the first one seen")
                    .startsWith(String.format(Locale.ROOT, "%s/%019d/", PREFIX, 9L));
        }
    }

    @Nested
    @DisplayName("3. BLOCKER: the image is 350 bytes, and the last twenty of them are FILLER")
    class RecordGeometry {

        @Test
        @DisplayName("one transaction composes exactly 350 characters")
        void oneTransactionComposes350Characters() {
            assertThat(writer.composeFixedWidthImage(posted()))
                    .as("app/jcl/TRANFILE.jcl:L54 declares RECORDSIZE(350 350)")
                    .hasSize(RECORD_LENGTH);
        }

        @Test
        @DisplayName("every field lands at the offset CVTRA05Y gives it")
        void everyFieldLandsAtItsOffset() {
            String image = writer.composeFixedWidthImage(posted());

            assertThat(image.substring(0, 16)).as("TRAN-ID").isEqualTo("0000000000000001");
            assertThat(image.substring(16, 18)).as("TRAN-TYPE-CD").isEqualTo("01");
            assertThat(image.substring(18, 22)).as("TRAN-CAT-CD").isEqualTo("5001");
            assertThat(image.substring(22, 32)).as("TRAN-SOURCE").isEqualTo("POS TERM  ");
            assertThat(image.substring(32, 132)).as("TRAN-DESC").isEqualTo(pad("Payment at Amazon", 100));
            assertThat(image.substring(132, 143)).as("TRAN-AMT").isEqualTo("0000001000{");
            assertThat(image.substring(143, 152)).as("TRAN-MERCHANT-ID").isEqualTo("123456789");
            assertThat(image.substring(152, 202)).as("TRAN-MERCHANT-NAME").isEqualTo(pad("Amazon.com", 50));
            assertThat(image.substring(202, 252)).as("TRAN-MERCHANT-CITY").isEqualTo(pad("Seattle", 50));
            assertThat(image.substring(252, 262)).as("TRAN-MERCHANT-ZIP").isEqualTo("0000098101");
            assertThat(image.substring(262, 278)).as("TRAN-CARD-NUM").isEqualTo("4111111111111111");
            assertThat(image.substring(278, 304)).as("TRAN-ORIG-TS").isEqualTo("2022-07-18-11.22.33.123456");
            assertThat(image.substring(304, 330)).as("TRAN-PROC-TS").isEqualTo("2022-07-18-11.22.34.123456");
        }

        @Test
        @DisplayName("the twenty-byte FILLER completes the record; without it the image is 330 bytes")
        void theFillerCompletesTheRecord() {
            String image = writer.composeFixedWidthImage(posted());

            assertThat(image.substring(330))
                    .as("the FILLER is not an entity column, but it is part of the record")
                    .isEqualTo(" ".repeat(20));
        }

        @Test
        @DisplayName("the card number sits at offset 263, where the alternate index expects it")
        void theCardNumberSitsAtTheAlternateIndexOffset() {
            String image = writer.composeFixedWidthImage(posted());

            assertThat(image.indexOf("4111111111111111"))
                    .as("app/proc/TRANREPT.prc's SYMNAMES places TRAN-CARD-NUM at 263, one-based")
                    .isEqualTo(262);
        }

        @Test
        @DisplayName("a shorter field is right-padded rather than shifting its neighbours left")
        void aShorterFieldIsRightPadded() {
            Transaction transaction = posted();
            setField(transaction, "merchantName", "AMZ");

            String image = writer.composeFixedWidthImage(transaction);

            assertThat(image.substring(152, 202)).isEqualTo(pad("AMZ", 50));
            assertThat(image.substring(202, 252)).isEqualTo(pad("Seattle", 50));
            assertThat(image).hasSize(RECORD_LENGTH);
        }

        @Test
        @DisplayName("an absent text field becomes spaces, never a shortened record")
        void anAbsentTextFieldBecomesSpaces() {
            Transaction transaction = posted();
            setField(transaction, "merchantCity", null);

            String image = writer.composeFixedWidthImage(transaction);

            assertThat(image.substring(202, 252)).isEqualTo(" ".repeat(50));
            assertThat(image).hasSize(RECORD_LENGTH);
        }

        @Test
        @DisplayName("a chunk of three emits one object of exactly three records")
        void aChunkOfThreeEmitsThreeRecords() throws Exception {
            writer.write(chunk(posted(), posted("0000000000000002", "1.00"),
                    posted("0000000000000003", "-2.00")));

            String payload = uploadedPayload();
            assertThat(payload).hasSize(3 * RECORD_LENGTH);
            assertThat(payload.substring(0, 16)).isEqualTo("0000000000000001");
            assertThat(payload.substring(RECORD_LENGTH, RECORD_LENGTH + 16)).isEqualTo("0000000000000002");
            assertThat(payload.substring(2 * RECORD_LENGTH, 2 * RECORD_LENGTH + 16))
                    .isEqualTo("0000000000000003");
        }

        @Test
        @DisplayName("the payload encodes byte for byte, so no record boundary moves")
        void thePayloadEncodesByteForByte() throws Exception {
            writer.write(chunk(posted(), posted("0000000000000002", "1.00")));

            assertThat(uploadedPayload())
                    .as("a multi-byte charset would shift every record after the first non-ASCII byte")
                    .hasSize(2 * RECORD_LENGTH);
        }

        @Test
        @DisplayName("a Latin-1 character outside ASCII still occupies exactly one byte")
        void aLatin1CharacterOccupiesOneByte() throws Exception {
            Transaction transaction = posted();
            setField(transaction, "merchantCity", pad("Ni\u00f1o", 50));

            writer.write(chunk(transaction));

            String payload = uploadedPayload();
            assertThat(payload).hasSize(RECORD_LENGTH);
            assertThat(payload.substring(202, 252)).isEqualTo(pad("Ni\u00f1o", 50));
        }
    }

    @Nested
    @DisplayName("4. BLOCKER: no field is truncated to fit, and TRAN-AMT carries its sign in the last byte")
    class FieldRendering {

        @ParameterizedTest(name = "{0} renders as {1}")
        @CsvSource(delimiter = '|', value = {
            "0.00|0000000000{",
            "0.01|0000000000A",
            "0.09|0000000000I",
            "100.00|0000001000{",
            "999999999.99|9999999999I",
            "-0.01|0000000000J",
            "-0.09|0000000000R",
            "-100.00|0000001000}",
            "-999999999.99|9999999999R"})
        @DisplayName("the zoned-decimal overpunch carries the sign for both alphabets")
        void theOverpunchCarriesTheSign(final String amount, final String rendered) {
            String image = writer.composeFixedWidthImage(posted("0000000000000001", amount));

            assertThat(image.substring(132, 143)).isEqualTo(rendered);
        }

        @Test
        @DisplayName("a negative amount is never normalised to its absolute value")
        void aNegativeAmountIsNeverNormalised() {
            String image = writer.composeFixedWidthImage(posted("0000000000000001", "-250.55"));

            assertThat(image.substring(132, 143))
                    .as("taking abs() would emit E where the mainframe emits N")
                    .isEqualTo("0000002505N");
        }

        @ParameterizedTest(name = "a tie at {0} renders as {1}")
        @CsvSource(delimiter = '|', value = {
            "0.005|0000000000{",
            "0.015|0000000000B",
            "0.025|0000000000B"})
        @DisplayName("a tie is rounded half even, matching the posting arithmetic")
        void aTieIsRoundedHalfEven(final String amount, final String rendered) {
            Transaction transaction = posted();
            setField(transaction, "amount", new BigDecimal(amount));

            assertThat(writer.composeFixedWidthImage(transaction).substring(132, 143))
                    .as("HALF_EVEN sends a tie to the even digit: 0.005 to 0.00 where HALF_UP would "
                            + "give 0.01, and 0.025 to 0.02 where HALF_UP would give 0.03")
                    .isEqualTo(rendered);
        }

        @Test
        @DisplayName("a negative tie that rounds to zero loses its sign, because zoned zero is positive")
        void aNegativeTieRoundingToZeroLosesItsSign() {
            Transaction transaction = posted();
            setField(transaction, "amount", new BigDecimal("-0.005"));

            assertThat(writer.composeFixedWidthImage(transaction).substring(132, 143))
                    .as("HALF_EVEN sends -0.005 to 0.00, whose signum is zero, so the overpunch is { not }")
                    .isEqualTo("0000000000{");
        }

        @Test
        @DisplayName("an amount beyond PIC S9(09)V99 is refused rather than truncated")
        void anOverWideAmountIsRefused() {
            Transaction transaction = posted();
            setField(transaction, "amount", new BigDecimal("1000000000.00"));

            assertThatExceptionOfType(DataIntegrityException.class)
                    .isThrownBy(() -> writer.composeFixedWidthImage(transaction))
                    .withMessageContaining("TRAN-AMT needs 12 zoned positions but PIC S9(09)V99 provides "
                            + "exactly 11");
        }

        @Test
        @DisplayName("an absent amount is refused, because the column is NOT NULL")
        void anAbsentAmountIsRefused() {
            Transaction transaction = posted();
            setField(transaction, "amount", null);

            assertThatExceptionOfType(DataIntegrityException.class)
                    .isThrownBy(() -> writer.composeFixedWidthImage(transaction))
                    .withMessageContaining("TRAN-AMT is absent");
        }

        @Test
        @DisplayName("an over-wide character field is refused rather than truncated")
        void anOverWideCharacterFieldIsRefused() {
            Transaction transaction = posted();
            setField(transaction, "merchantName", "X".repeat(51));

            assertThatExceptionOfType(DataIntegrityException.class)
                    .isThrownBy(() -> writer.composeFixedWidthImage(transaction))
                    .withMessageContaining("TRAN-MERCHANT-NAME is 51 characters but its picture clause "
                            + "declares exactly 50");
        }

        @Test
        @DisplayName("a character the record charset cannot hold in one byte is refused, not substituted")
        void anUnencodableCharacterIsRefused() {
            Transaction transaction = posted();
            setField(transaction, "merchantCity", pad("Tokyo \u6771\u4eac", 50));

            assertThatExceptionOfType(DataIntegrityException.class)
                    .isThrownBy(() -> writer.composeFixedWidthImage(transaction))
                    .withMessageContaining("cannot represent in a single byte");
        }

        @Test
        @DisplayName("an absent unsigned field is refused, because it has no null representation")
        void anAbsentUnsignedFieldIsRefused() {
            Transaction transaction = posted();
            setField(transaction, "merchantId", null);

            assertThatExceptionOfType(DataIntegrityException.class)
                    .isThrownBy(() -> writer.composeFixedWidthImage(transaction))
                    .withMessageContaining("TRAN-MERCHANT-ID is absent");
        }

        @Test
        @DisplayName("a negative unsigned field is refused, because there is no sign position at all")
        void aNegativeUnsignedFieldIsRefused() {
            Transaction transaction = posted();
            setField(transaction, "categoryCode", Integer.valueOf(-1));

            assertThatExceptionOfType(DataIntegrityException.class)
                    .isThrownBy(() -> writer.composeFixedWidthImage(transaction))
                    .withMessageContaining("TRAN-CAT-CD is negative");
        }

        @Test
        @DisplayName("an unsigned field needing more digits than its clause is refused")
        void anOverWideUnsignedFieldIsRefused() {
            Transaction transaction = posted();
            setField(transaction, "categoryCode", Integer.valueOf(12345));

            assertThatExceptionOfType(DataIntegrityException.class)
                    .isThrownBy(() -> writer.composeFixedWidthImage(transaction))
                    .withMessageContaining("TRAN-CAT-CD needs 5 digits but its picture clause declares "
                            + "exactly 4");
        }

        @Test
        @DisplayName("an unsigned field narrower than its clause is zero-filled on the left")
        void aNarrowUnsignedFieldIsZeroFilled() {
            Transaction transaction = posted();
            setField(transaction, "categoryCode", Integer.valueOf(7));

            assertThat(writer.composeFixedWidthImage(transaction).substring(18, 22)).isEqualTo("0007");
        }

        @Test
        @DisplayName("a null item has no record image and is refused by name")
        void aNullItemIsRefused() {
            assertThatExceptionOfType(DataIntegrityException.class)
                    .isThrownBy(() -> writer.composeFixedWidthImage(null))
                    .withMessageContaining("the chunk contained a null item, which has no record image");
        }

        @ParameterizedTest(name = "a TRAN-ID of [{0}] is refused")
        @ValueSource(strings = {"", "                "})
        @DisplayName("an absent or blank TRAN-ID is refused, because the cluster is keyed on it")
        void aBlankIdentifierIsRefused(final String transactionId) {
            Transaction transaction = posted();
            setField(transaction, "transactionId", transactionId);

            assertThatExceptionOfType(DataIntegrityException.class)
                    .isThrownBy(() -> writer.composeFixedWidthImage(transaction))
                    .withMessageContaining("KEYS(16 0)");
        }

        @Test
        @DisplayName("a null TRAN-ID is refused and rendered as (absent) in the diagnostic")
        void aNullIdentifierIsRenderedAsAbsent() {
            Transaction transaction = posted();
            setField(transaction, "transactionId", null);

            assertThatExceptionOfType(DataIntegrityException.class)
                    .isThrownBy(() -> writer.composeFixedWidthImage(transaction))
                    .withMessageContaining("TRAN-ID (absent)");
        }

        @Test
        @DisplayName("a control character in the key is escaped in the diagnostic rather than emitted raw")
        void aControlCharacterInTheKeyIsEscaped() {
            Transaction transaction = posted();
            setField(transaction, "transactionId", "000000000000000\u0001");
            setField(transaction, "merchantName", "X".repeat(51));

            assertThatExceptionOfType(DataIntegrityException.class)
                    .isThrownBy(() -> writer.composeFixedWidthImage(transaction))
                    .withMessageContaining("\\u0001");
        }
    }

    @Nested
    @DisplayName("5. BLOCKER: the insert, and the duplicate identifier the source's idiom makes reachable")
    class Persistence {

        /**
         * The outbox entry is recorded before the rows, so a surviving entry proves the rows exist.
         *
         * <p>Purpose: assert the first half of finding M-07. The object is uploaded after the chunk
         * transaction commits, which is the only ordering that cannot publish an object describing rows that
         * were rolled back - but it leaves the mirror problem, that a failed upload strands durable rows with
         * no object and nothing to notice it. The repair is an outbox entry committed with the rows, and this
         * case pins that it is written into the step execution context, which the framework persists inside
         * the chunk transaction.
         */
        @Test
        @DisplayName("M-07: the chunk records what it owes the store, in the context the framework commits "
                + "with the rows")
        void theChunkRecordsWhatItOwesTheStore() throws Exception {
            writer.write(chunk(posted(), posted("0000000000000002", "1.00")));

            // Cleared on the success path, because the object now exists - so the entry is asserted through
            // the ordering that produced it rather than through a leftover.
            assertThat(stepExecution.getExecutionContext()
                            .getString(TransactionWriter.PENDING_OBJECT_KEY_ENTRY, ""))
                    .as("a completed emission owes the store nothing, so the entry is gone")
                    .isEmpty();

            // And on the failure path the entry survives, naming exactly what is owed.
            Mockito.doThrow(new IllegalStateException("the bucket is unreachable"))
                    .when(objectStorage).upload(Mockito.anyString(), Mockito.anyString(), Mockito.any(),
                            Mockito.any(ObjectMetadata.class));
            assertThatExceptionOfType(RuntimeException.class)
                    .isThrownBy(() -> writer.write(chunk(posted("0000000000000003", "2.00"))));

            assertThat(stepExecution.getExecutionContext()
                            .getString(TransactionWriter.PENDING_OBJECT_KEY_ENTRY, ""))
                    .as("the rows committed and the object did not, so the entry remains as the record of it")
                    .isNotEmpty();
            assertThat(stepExecution.getExecutionContext()
                            .getString(TransactionWriter.PENDING_IDENTIFIERS_ENTRY, ""))
                    .as("and it names the identifiers the object must be rebuilt from")
                    .isEqualTo("0000000000000003");
        }

        /**
         * A restart settles the outstanding emission by re-deriving it from the committed rows.
         *
         * <p>Purpose: assert the second half of finding M-07. What is being checked is not merely that
         * something is re-uploaded, but that the bytes come from the <em>relation</em> - so the mirror cannot
         * disagree with what the database holds - and that the key is the one the entry named, which makes the
         * repeat idempotent.
         */
        @Test
        @DisplayName("M-07: a restart settles an outstanding emission from the committed rows, at the key the "
                + "entry named")
        void aRestartSettlesTheOutstandingEmissionFromTheRows() {
            final Transaction committed = posted("0000000000000009", "12.34");
            Mockito.when(repository.findById("0000000000000009"))
                    .thenReturn(java.util.Optional.of(committed));

            final StepExecution restarted = stepExecution(JOB_INSTANCE_ID);
            restarted.getExecutionContext()
                    .putString(TransactionWriter.PENDING_OBJECT_KEY_ENTRY, "transact/pending/object.dat");
            restarted.getExecutionContext()
                    .putString(TransactionWriter.PENDING_IDENTIFIERS_ENTRY, "0000000000000009");

            writer.beforeStep(restarted);

            final ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
            Mockito.verify(objectStorage).upload(Mockito.anyString(), keys.capture(),
                    Mockito.any(), Mockito.any(ObjectMetadata.class));
            assertThat(keys.getValue())
                    .as("the same key the entry named, so settling an already-settled entry rewrites "
                            + "identical bytes rather than creating a second object")
                    .isEqualTo("transact/pending/object.dat");
            Mockito.verify(repository).findById("0000000000000009");
            assertThat(restarted.getExecutionContext()
                            .getString(TransactionWriter.PENDING_OBJECT_KEY_ENTRY, ""))
                    .as("and the entry is cleared once the store holds the object")
                    .isEmpty();
        }

        /**
         * An entry naming a row that is not there is discarded rather than emitted.
         *
         * <p>Purpose: this is the case that keeps the reconciliation sound. If the chunk's rows were rolled
         * back after all, honouring the entry would create an object describing transactions that do not
         * exist - which is the very orphan the post-commit ordering exists to prevent. Getting this wrong
         * would turn a safety mechanism into the defect it was built to remove.
         */
        @Test
        @DisplayName("M-07: an outstanding entry whose rows are absent is discarded, never emitted")
        void anEntryWhoseRowsAreAbsentIsDiscarded() {
            Mockito.when(repository.findById(Mockito.anyString())).thenReturn(java.util.Optional.empty());

            final StepExecution restarted = stepExecution(JOB_INSTANCE_ID);
            restarted.getExecutionContext()
                    .putString(TransactionWriter.PENDING_OBJECT_KEY_ENTRY, "transact/pending/object.dat");
            restarted.getExecutionContext()
                    .putString(TransactionWriter.PENDING_IDENTIFIERS_ENTRY, "0000000000000009");

            writer.beforeStep(restarted);

            Mockito.verify(objectStorage, Mockito.never()).upload(Mockito.anyString(), Mockito.anyString(),
                    Mockito.any(), Mockito.any(ObjectMetadata.class));
            assertThat(restarted.getExecutionContext()
                            .getString(TransactionWriter.PENDING_OBJECT_KEY_ENTRY, ""))
                    .as("the entry is discarded, so it cannot be retried forever against rows that will "
                            + "never appear")
                    .isEmpty();
        }

        @Test
        @DisplayName("the chunk is inserted exactly once, with every item")
        void theChunkIsInsertedOnce() throws Exception {
            Transaction first = posted();
            Transaction second = posted("0000000000000002", "1.00");

            writer.write(chunk(first, second));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<Transaction>> captor = ArgumentCaptor.forClass(List.class);
            Mockito.verify(repository, Mockito.times(1)).saveAllAndFlush(captor.capture());
            assertThat(captor.getValue()).containsExactly(first, second);
        }

        @Test
        @DisplayName("a duplicate key surfaces as a duplicate record, never as an upsert")
        void aDuplicateKeySurfacesAsADuplicate() {
            Mockito.when(repository.saveAllAndFlush(Mockito.anyList()))
                    .thenThrow(new DuplicateKeyException("transaction_pkey"));

            assertThatExceptionOfType(DuplicateRecordException.class)
                    .isThrownBy(() -> writer.write(chunk(posted())))
                    .satisfies(failure -> {
                        assertThat(failure.getCollidingKey())
                                .as("a chunk of one attributes the collision exactly")
                                .isEqualTo("0000000000000001");
                        assertThat(failure.getCause()).isInstanceOf(DuplicateKeyException.class);
                    });
        }

        @Test
        @DisplayName("the duplicate diagnostic names the identifier idiom that makes a collision reachable")
        void theDuplicateDiagnosticNamesTheIdiom() {
            Mockito.when(repository.saveAllAndFlush(Mockito.anyList()))
                    .thenThrow(new DuplicateKeyException("transaction_pkey"));

            assertThatExceptionOfType(DuplicateRecordException.class)
                    .isThrownBy(() -> writer.write(chunk(posted())))
                    .satisfies(failure -> assertThat(failure.getMessage())
                            .contains("app/cbl/COTRN02C.cbl:L444-L451")
                            .contains("do not substitute a sequence"));
        }

        @Test
        @DisplayName("a duplicate in a multi-item chunk lists every candidate and names no single key")
        void aDuplicateInAChunkListsEveryCandidate() {
            Mockito.when(repository.saveAllAndFlush(Mockito.anyList()))
                    .thenThrow(new DuplicateKeyException("transaction_pkey"));

            assertThatExceptionOfType(DuplicateRecordException.class)
                    .isThrownBy(() -> writer.write(chunk(posted(), posted("0000000000000002", "1.00"))))
                    .satisfies(failure -> {
                        assertThat(failure.getCollidingKey())
                                .as("the driver does not say which row collided, so none is claimed")
                                .isNull();
                        assertThat(failure.getMessage())
                                .contains("0000000000000001, 0000000000000002");
                    });
        }

        @Test
        @DisplayName("a batched collision, whose state is linked below the thrown exception, is still a duplicate")
        void aBatchedCollisionIsRecognisedByItsSqlState() {
            // Finding F-8. This is the shape a batched flush produces: a generic integrity violation whose
            // own state says nothing, carrying the failing entry's state on a linked exception underneath.
            // Recognition by subtype misses it entirely and reports it as a referential or check-constraint
            // failure, losing the identifier race this group exists to pin.
            Mockito.when(repository.saveAllAndFlush(Mockito.anyList()))
                    .thenThrow(new DataIntegrityViolationException("batch entry 0 was refused",
                            new SQLException("batch failed", "40001",
                                    new SQLException("duplicate key value violates unique constraint "
                                            + "\"pk_transaction\"", "23505"))));

            assertThatExceptionOfType(DuplicateRecordException.class)
                    .isThrownBy(() -> writer.write(chunk(posted())))
                    .satisfies(failure -> assertThat(failure.getMessage())
                            .contains("Duplicate TRAN-ID rejected by the insert into"));
        }

        @Test
        @DisplayName("a plain constraint violation is classified separately from a duplicate")
        void aConstraintViolationIsClassifiedSeparately() {
            Mockito.when(repository.saveAllAndFlush(Mockito.anyList()))
                    .thenThrow(new DataIntegrityViolationException("fk_transaction_card"));

            assertThatExceptionOfType(DataIntegrityException.class)
                    .isThrownBy(() -> writer.write(chunk(posted())))
                    .satisfies(failure -> {
                        assertThat(failure).isNotInstanceOf(DuplicateRecordException.class);
                        assertThat(failure.getMessage()).contains("A constraint violation rejected the insert");
                        assertThat(failure.getCause()).isInstanceOf(DataIntegrityViolationException.class);
                    });
        }

        @Test
        @DisplayName("any other store failure abends as CBTRN02C rather than being classified")
        void anyOtherStoreFailureAbends() {
            Mockito.when(repository.saveAllAndFlush(Mockito.anyList()))
                    .thenThrow(new DataAccessResourceFailureException("the connection dropped"));

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> writer.write(chunk(posted())))
                    .satisfies(failure -> {
                        assertThat(failure.getAbendCulprit()).isEqualTo("CBTRN02C");
                        assertThat(failure.getAbendCode())
                                .isEqualTo(String.valueOf(FatalProcessingException.BATCH_ABEND_CODE));
                        assertThat(failure.getCause())
                                .isInstanceOf(DataAccessResourceFailureException.class);
                    });
        }

        @Test
        @DisplayName("a failed insert writes no object, so the table and the dataset cannot diverge")
        void aFailedInsertWritesNoObject() {
            Mockito.when(repository.saveAllAndFlush(Mockito.anyList()))
                    .thenThrow(new DuplicateKeyException("transaction_pkey"));

            assertThatExceptionOfType(DuplicateRecordException.class)
                    .isThrownBy(() -> writer.write(chunk(posted())));

            Mockito.verify(objectStorage, Mockito.never()).upload(Mockito.anyString(), Mockito.anyString(),
                    Mockito.any(InputStream.class), Mockito.any(ObjectMetadata.class));
            assertThat(processedCount()).isZero();
        }

        @Test
        @DisplayName("an unrenderable record is refused before the insert is attempted")
        void anUnrenderableRecordIsRefusedBeforeTheInsert() {
            Transaction broken = posted();
            setField(broken, "amount", null);

            assertThatExceptionOfType(DataIntegrityException.class)
                    .isThrownBy(() -> writer.write(chunk(broken)));

            Mockito.verify(repository, Mockito.never()).saveAllAndFlush(Mockito.anyList());
        }
    }

    @Nested
    @DisplayName("6. The object key, its metadata and the context entry a later step reads")
    class ObjectKeyAndPublication {

        @Test
        @DisplayName("a chunk stages a part named by the prefix, the job instance, the attempt and the ordinal")
        void theKeyCarriesTheOrdinal() throws Exception {
            writer.write(chunk(posted()));

            assertThat(uploadedKey())
                    .as("a chunk's bytes are a transient part beneath the generation prefix, not the "
                            + "generation itself: app/jcl/POSTTRAN.jcl names ONE dataset per output")
                    .isEqualTo(partKey(JOB_INSTANCE_ID, 0L));
        }

        @Test
        @DisplayName("the part ordinal follows the step's own write count, so chunks cannot overwrite")
        void theOrdinalFollowsTheWriteCount() throws Exception {
            stepExecution.setWriteCount(4L);

            writer.write(chunk(posted()));

            assertThat(uploadedKey())
                    .as("the ordinal orders the parts within the attempt, which is the order they must be "
                            + "concatenated in")
                    .endsWith(String.format(Locale.ROOT, "-%019d.dat", 4L));
        }

        @Test
        @DisplayName("a part key carries the job execution too, so a restart cannot overwrite the first "
                + "attempt's earliest records")
        void thePartKeyCarriesTheAttempt() throws Exception {
            // The write count of a RESTARTED step begins again at zero while its reader resumes where the
            // failed attempt stopped. Keyed on the ordinal alone, the restart's first chunk would overwrite
            // the first attempt's first part - replacing the earliest records of the run with later ones.
            writer.write(chunk(posted()));

            assertThat(uploadedKey())
                    .contains("-" + String.format(Locale.ROOT, "%019d", JOB_EXECUTION_ID) + "-");
        }

        @Test
        @DisplayName("the metadata declares the byte count and an opaque content type")
        void theMetadataDeclaresTheByteCount() throws Exception {
            writer.write(chunk(posted(), posted("0000000000000002", "1.00")));

            ObjectMetadata metadata = uploadedMetadata();
            assertThat(metadata.getContentLength()).isEqualTo(Long.valueOf(2L * RECORD_LENGTH));
            assertThat(metadata.getContentType()).isEqualTo("application/octet-stream");
        }

        @Test
        @DisplayName("nothing is published while only a part exists, so no step reads a fraction of the run")
        void nothingIsPublishedUntilTheGenerationExists() throws Exception {
            writer.write(chunk(posted()));

            assertThat(stepExecution.getExecutionContext()
                    .containsKey(TransactionWriter.OBJECT_KEY_CONTEXT_ENTRY))
                    .as("a key naming a part would tell a downstream step to read one chunk of the run")
                    .isFalse();
        }

        @Test
        @DisplayName("the generation key is published once the step ends, and it names the one object")
        void theObjectKeyIsPublished() throws Exception {
            writer.write(chunk(posted()));

            writer.afterStep(stepExecution);

            assertThat(stepExecution.getExecutionContext()
                    .getString(TransactionWriter.OBJECT_KEY_CONTEXT_ENTRY))
                    .isEqualTo(generationKey(JOB_INSTANCE_ID));
        }

        @Test
        @DisplayName("a custom object prefix is honoured in every key position")
        void aCustomPrefixIsHonoured() throws Exception {
            TransactionWriter prefixed = new TransactionWriter(repository, objectStorage, objectStoreClient,
                    new FileStatusMapper(), metricsConfig, BUCKET, "systran");
            prefixed.beforeStep(stepExecution);

            prefixed.write(chunk(posted()));

            assertThat(uploadedKey())
                    .as("app/jcl/DEFGDGB.jcl defines several GDG bases, and the prefix selects one")
                    .startsWith("systran/")
                    .contains("/parts/transact-part-");

            prefixed.afterStep(stepExecution);

            assertThat(stepExecution.getExecutionContext()
                    .getString(TransactionWriter.OBJECT_KEY_CONTEXT_ENTRY))
                    .as("the configured value selects the generation namespace; the base name inside it is a "
                            + "constant, so a prefix change relocates the generation without renaming it")
                    .startsWith("systran/")
                    .contains("/" + BASE_NAME + "-");
        }
    }

    @Nested
    @DisplayName("7. HIGH: a failed object write DISPLAYs, then abends, and publishes nothing")
    class WriteFailure {

        @BeforeEach
        void makeTheStoreFail() {
            makeStoreFail(new IllegalStateException("the bucket is unreachable"));
        }

        @Test
        @DisplayName("the source's own DISPLAY text precedes the abend")
        void theSourcesDisplayTextPrecedesTheAbend() {
            assertThatExceptionOfType(RuntimeException.class)
                    .isThrownBy(() -> writer.write(chunk(posted())));

            assertThat(loggedMessages()).contains("ERROR WRITING TO TRANSACTION FILE");
        }

        @Test
        @DisplayName("9910-DISPLAY-IO-STATUS renders the '9x' status as four characters")
        void theIoStatusIsRenderedAsFourCharacters() {
            assertThatExceptionOfType(RuntimeException.class)
                    .isThrownBy(() -> writer.write(chunk(posted())));

            assertThat(loggedMessages()).contains("FILE STATUS IS: NNNN9048");
        }

        @Test
        @DisplayName("the abend line is emitted, and the reason names the bucket, object and operation")
        void theAbendLineIsEmitted() {
            // NO LOG RECORD NAMES THE BUCKET OR THE OBJECT KEY, and that is deliberate: a log line carrying
            // them is an inventory of this deployment's storage layout, written to wherever logs are shipped.
            // They are carried by the escalation reason instead, which an operator only ever sees while
            // already inside the failure. Requiring the log line to quote the bucket would defeat that.
            //
            // The escalation reason is not reachable from THIS scenario, and that is worth stating rather than
            // asserting around: the status mapper raises on a '9x' status, so the typed FileAccessException it
            // builds is what the caller receives, and abendProgram's own reason is constructed only on the
            // path where the mapper returns without raising. theStatusMapsToAFileAccessException asserts that
            // outcome directly. What this test owns is the pair of DISPLAY lines and their disclosure limit.
            assertThatExceptionOfType(RuntimeException.class)
                    .isThrownBy(() -> writer.write(chunk(posted())))
                    .satisfies(failure -> assertThat(failure.getMessage())
                            .as("the typed failure names the logical file, the operation and the status")
                            .contains("WRITE")
                            .contains("TRANSACT"));

            assertThat(loggedMessages()).contains("ABENDING PROGRAM");
            assertThat(loggedMessages()).anyMatch(message -> message.contains("logicalFile=TRANSACT")
                    && message.contains("operation=WRITE"));
            assertThat(loggedMessages())
                    .as("no log record may name the bucket")
                    .noneMatch(message -> message.contains(BUCKET));
        }

        @Test
        @DisplayName("the '9x' status maps to a file access exception carrying the store failure")
        void theStatusMapsToAFileAccessException() {
            assertThatExceptionOfType(FileAccessException.class)
                    .isThrownBy(() -> writer.write(chunk(posted())))
                    .satisfies(failure -> assertThat(failure.getCause())
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessage("the bucket is unreachable"));
        }

        @Test
        @DisplayName("a failed object write publishes no key, so no step reads a phantom object")
        void aFailedWritePublishesNoKey() {
            assertThatExceptionOfType(RuntimeException.class)
                    .isThrownBy(() -> writer.write(chunk(posted())));

            assertThat(stepExecution.getExecutionContext()
                    .containsKey(TransactionWriter.OBJECT_KEY_CONTEXT_ENTRY))
                    .isFalse();
        }

        @Test
        @DisplayName("a failed object write counts no processed record")
        void aFailedWriteCountsNothing() {
            assertThatExceptionOfType(RuntimeException.class)
                    .isThrownBy(() -> writer.write(chunk(posted())));

            assertThat(processedCount()).isZero();
        }
    }

    @Nested
    @DisplayName("8. The chunk contract and the processed-records counter")
    class ChunkContract {

        @Test
        @DisplayName("a null chunk is a no-op, because a step with nothing to write writes nothing")
        void aNullChunkIsANoOp() throws Exception {
            writer.write(null);

            Mockito.verifyNoInteractions(repository);
            Mockito.verifyNoInteractions(objectStorage);
        }

        @Test
        @DisplayName("an empty chunk is a no-op and creates no empty object")
        void anEmptyChunkIsANoOp() throws Exception {
            writer.write(new Chunk<>(List.of()));

            Mockito.verifyNoInteractions(repository);
            Mockito.verifyNoInteractions(objectStorage);
            assertThat(processedCount()).isZero();
        }

        @Test
        @DisplayName("the counter is incremented once per record, not once per chunk")
        void theCounterIsIncrementedOncePerRecord() throws Exception {
            writer.write(chunk(posted(), posted("0000000000000002", "1.00"),
                    posted("0000000000000003", "2.00")));

            assertThat(processedCount())
                    .as("the counter replaces DISPLAY 'TRANSACTIONS PROCESSED :'")
                    .isEqualTo(3.0d);
        }

        @Test
        @DisplayName("two chunks accumulate on the same counter")
        void twoChunksAccumulate() throws Exception {
            writer.write(chunk(posted()));
            stepExecution.setWriteCount(1L);
            writer.write(chunk(posted("0000000000000002", "1.00")));

            assertThat(processedCount()).isEqualTo(2.0d);
        }

        @Test
        @DisplayName("the insert precedes the object write, so the table is the system of record")
        void theInsertPrecedesTheObjectWrite() throws Exception {
            writer.write(chunk(posted()));

            org.mockito.InOrder order = Mockito.inOrder(repository, objectStorage);
            order.verify(repository).saveAllAndFlush(Mockito.anyList());
            order.verify(objectStorage).upload(Mockito.eq(BUCKET), Mockito.anyString(),
                    Mockito.any(InputStream.class), Mockito.any(ObjectMetadata.class));
        }

        @Test
        @DisplayName("the record length is exposed as a constant so callers cannot re-derive it")
        void theRecordLengthIsExposed() {
            assertThat(TransactionWriter.RECORD_LENGTH)
                    .as("app/cpy/CVTRA05Y.cpy declares RECLN 350")
                    .isEqualTo(RECORD_LENGTH);
            assertThat(TransactionWriter.OBJECT_KEY_CONTEXT_ENTRY)
                    .isEqualTo("carddemo.transaction.object.key");
        }
    }

    // ==================================================================================================
    // 9 - FINDING, severity Critical, RESOLVED. The writer used to emit ONE OBJECT PER CHUNK under the
    //     generation prefix, and the posting step commits once per record by parity contract, so a
    //     300-record run left 300 objects of 350 bytes and no generation object at all. A consumer
    //     resolving the (0) generation as the lexicographically greatest key under the prefix therefore
    //     read the LAST RECORD of the run as the entire day's postings, and the job execution context grew
    //     an indexed key manifest that had to be capped to keep its re-serialisation affordable. Each chunk
    //     now stages a transient part and afterStep concatenates every part into ONE generation object,
    //     which is what app/jcl/POSTTRAN.jcl's single-dataset output means; the manifest holds one key and
    //     needs no cap.
    // ==================================================================================================

    @Nested
    @DisplayName("9. CRITICAL: the run's chunk parts are consolidated into ONE generation object")
    class GenerationConsolidation {

        @Test
        @DisplayName("three chunks stage three parts and promote to one object holding all three records")
        void threeChunksBecomeOneGenerationObject() throws Exception {
            writeChunks(3);

            assertThat(store.keySet())
                    .as("before the step ends the run exists as its parts, so nothing is lost by a failure")
                    .containsExactly(partKey(JOB_INSTANCE_ID, 0L), partKey(JOB_INSTANCE_ID, 1L),
                            partKey(JOB_INSTANCE_ID, 2L));

            writer.afterStep(stepExecution);

            assertThat(store.keySet())
                    .as("app/jcl/POSTTRAN.jcl:L28 names ONE dataset, so the run is ONE object and the parts "
                            + "it was assembled from are gone")
                    .containsExactly(generationKey(JOB_INSTANCE_ID));
            assertThat(store.get(generationKey(JOB_INSTANCE_ID)))
                    .as("and it holds every record of the run, at the exact 350-byte geometry")
                    .hasSize(3 * RECORD_LENGTH);
        }

        @Test
        @DisplayName("the records appear in the order the chunks wrote them")
        void theRecordsKeepTheirWriteOrder() throws Exception {
            writeChunks(3);
            writer.afterStep(stepExecution);

            String payload = new String(store.get(generationKey(JOB_INSTANCE_ID)),
                    StandardCharsets.ISO_8859_1);
            assertThat(payload.substring(0, 16)).isEqualTo("0000000000000001");
            assertThat(payload.substring(RECORD_LENGTH, RECORD_LENGTH + 16)).isEqualTo("0000000000000002");
            assertThat(payload.substring(2 * RECORD_LENGTH, 2 * RECORD_LENGTH + 16))
                    .isEqualTo("0000000000000003");
        }

        @Test
        @DisplayName("the manifest holds exactly one key, so no bound is needed to keep it affordable")
        void theManifestHoldsExactlyOneKey() throws Exception {
            writeChunks(3);
            writer.afterStep(stepExecution);

            ExecutionContext jobContext = stepExecution.getJobExecution().getExecutionContext();
            assertThat(jobContext.getLong(TransactionWriter.OBJECT_KEYS_COUNT_ENTRY))
                    .as("one generation is one object, whatever the record count, so the context cost is "
                            + "constant rather than quadratic in the run length")
                    .isEqualTo(1L);
            assertThat(jobContext.getString(TransactionWriter.objectKeysIndexEntry(0)))
                    .isEqualTo(generationKey(JOB_INSTANCE_ID));
            assertThat(jobContext.containsKey(TransactionWriter.objectKeysIndexEntry(1)))
                    .as("and there is no second entry to read")
                    .isFalse();
        }

        @Test
        @DisplayName("the generation prefix names this job instance only, so listing it cannot race")
        void theGenerationPrefixNamesTheJobInstance() throws Exception {
            writeChunks(2);
            writer.afterStep(stepExecution);

            String published = stepExecution.getJobExecution().getExecutionContext()
                    .getString(TransactionWriter.OBJECT_KEYS_GENERATION_PREFIX_ENTRY);
            assertThat(published)
                    .as("it is the created key up to its last separator, so it cannot drift from the "
                            + "key template")
                    .isEqualTo(String.format(Locale.ROOT, "%s/%019d/", PREFIX, JOB_INSTANCE_ID));
        }

        @Test
        @DisplayName("the generation object declares its exact byte count and an opaque content type")
        void theGenerationDeclaresItsLength() throws Exception {
            writeChunks(2);
            writer.afterStep(stepExecution);

            ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<ObjectMetadata> metadata = ArgumentCaptor.forClass(ObjectMetadata.class);
            Mockito.verify(objectStorage, Mockito.atLeastOnce()).upload(Mockito.eq(BUCKET), keys.capture(),
                    Mockito.any(InputStream.class), metadata.capture());
            int generationUpload = keys.getAllValues().indexOf(generationKey(JOB_INSTANCE_ID));
            assertThat(generationUpload).as("the generation was uploaded").isNotNegative();
            assertThat(metadata.getAllValues().get(generationUpload).getContentLength())
                    .as("app/jcl/POSTTRAN.jcl declares RECFM=FB, so a consumer finds boundaries by counting "
                            + "350 bytes and a short or long concatenation must be refused rather than stored")
                    .isEqualTo(Long.valueOf(2L * RECORD_LENGTH));
            assertThat(metadata.getAllValues().get(generationUpload).getContentType())
                    .isEqualTo("application/octet-stream");
        }

        @Test
        @DisplayName("a run past one listing page still concatenates every part, and none is orphaned")
        void aRunPastOneListingPageStillConcatenatesEveryPart() throws Exception {
            // The boundary the truncating listing broke. A single ListObjectsV2 carries at most a thousand
            // keys, and one part per record makes a thousand records the point at which a page-bounded
            // listing silently published a prefix of the run. Slicing the answer into thousand-key pages
            // here is what makes the paginator's contribution observable.
            Mockito.when(objectStoreClient.listObjectsV2(Mockito.any(ListObjectsV2Request.class)))
                    .thenAnswer(invocation -> {
                        final ListObjectsV2Request request =
                                invocation.getArgument(0, ListObjectsV2Request.class);
                        final List<String> matching = new ArrayList<>();
                        for (final String key : new ArrayList<>(store.keySet())) {
                            if (key.startsWith(request.prefix())) {
                                matching.add(key);
                            }
                        }
                        java.util.Collections.sort(matching);
                        final int from = request.continuationToken() == null
                                ? 0
                                : Integer.parseInt(request.continuationToken().substring("token-".length()));
                        final int to = Math.min(from + PAGE_LIMIT, matching.size());
                        final List<S3Object> contents = new ArrayList<>(to - from);
                        for (int index = from; index < to; index++) {
                            contents.add(S3Object.builder().key(matching.get(index)).build());
                        }
                        final boolean truncated = to < matching.size();
                        return ListObjectsV2Response.builder()
                                .contents(contents)
                                .isTruncated(Boolean.valueOf(truncated))
                                .nextContinuationToken(truncated ? "token-" + to : null)
                                .build();
                    });

            final int records = PAGE_LIMIT + 3;
            writeChunks(records);

            writer.afterStep(stepExecution);

            assertThat(store.keySet())
                    .as("every part was named for concatenation and for deletion, including the ones past "
                            + "the first listing page")
                    .containsExactly(generationKey(JOB_INSTANCE_ID));
            assertThat(store.get(generationKey(JOB_INSTANCE_ID)))
                    .as("a single-page listing produced " + PAGE_LIMIT + " records here and called it the "
                            + "whole day's postings")
                    .hasSize(records * RECORD_LENGTH);
        }

        @Test
        @DisplayName("the promoted parts are deleted in batches of at most 1000 keys, each named once")
        void thePartsAreDeletedInBoundedBatches() throws Exception {
            writeChunks(3);
            writer.afterStep(stepExecution);

            ArgumentCaptor<DeleteObjectsRequest> deletes =
                    ArgumentCaptor.forClass(DeleteObjectsRequest.class);
            Mockito.verify(objectStoreClient, Mockito.atLeastOnce()).deleteObjects(deletes.capture());
            List<String> named = new ArrayList<>();
            for (final DeleteObjectsRequest request : deletes.getAllValues()) {
                assertThat(request.delete().objects())
                        .as("no batch may exceed the store's own limit for one DeleteObjects request")
                        .hasSizeLessThanOrEqualTo(PAGE_LIMIT);
                request.delete().objects().forEach(identifier -> named.add(identifier.key()));
            }
            assertThat(named)
                    .as("a per-key loop meant one round trip per posted transaction at the end of an "
                            + "otherwise finished run")
                    .containsExactly(partKey(JOB_INSTANCE_ID, 0L), partKey(JOB_INSTANCE_ID, 1L),
                            partKey(JOB_INSTANCE_ID, 2L));
        }

        @Test
        @DisplayName("a step that posted nothing creates no object at all")
        void anEmptyStepCreatesNoObject() {
            writer.afterStep(stepExecution);

            assertThat(store).as("OPEN OUTPUT then CLOSE of an empty dataset writes no record").isEmpty();
            assertThat(stepExecution.getExecutionContext()
                    .containsKey(TransactionWriter.OBJECT_KEY_CONTEXT_ENTRY))
                    .isFalse();
        }

        @Test
        @DisplayName("the promotion is idempotent, so a repeated callback creates no second object")
        void thePromotionIsIdempotent() throws Exception {
            writeChunks(2);

            writer.afterStep(stepExecution);
            writer.afterStep(stepExecution);

            assertThat(store.keySet()).containsExactly(generationKey(JOB_INSTANCE_ID));
            assertThat(store.get(generationKey(JOB_INSTANCE_ID))).hasSize(2 * RECORD_LENGTH);
        }

        @Test
        @DisplayName("a step that ended badly keeps its parts and catalogues nothing, so the restart can "
                + "concatenate the whole instance")
        void aFailedStepRetainsItsPartsAndPublishesNothing() throws Exception {
            writeChunks(2);
            stepExecution.setStatus(org.springframework.batch.core.BatchStatus.FAILED);

            writer.afterStep(stepExecution);

            assertThat(store.keySet())
                    .as("app/jcl/POSTTRAN.jcl declares its generation output DISP=(NEW,CATLG,DELETE), so an "
                            + "abending step leaves nothing catalogued - and the parts are what lets a "
                            + "restart produce ONE complete generation rather than a second partial one")
                    .containsExactly(partKey(JOB_INSTANCE_ID, 0L), partKey(JOB_INSTANCE_ID, 1L));
            assertThat(stepExecution.getExecutionContext()
                    .containsKey(TransactionWriter.OBJECT_KEY_CONTEXT_ENTRY))
                    .isFalse();
            assertThat(loggedMessages())
                    .anyMatch(message -> message.contains("are")
                            && message.contains("retained so that a restart"));
        }

        @Test
        @DisplayName("the step's own exit status is left exactly as the step set it")
        void theExitStatusIsUntouched() throws Exception {
            // RC=4 - COMPLETED WITH REJECTS, app/cbl/CBTRN02C.cbl:L230 - is decided by the reject count and
            // by nothing else, so the promotion must not overwrite it.
            writeChunks(1);

            assertThat(writer.afterStep(stepExecution))
                    .as("returning null leaves whatever the step already concluded in place")
                    .isNull();
        }

        @Test
        @DisplayName("a promotion the store refuses DISPLAYs, abends, and publishes no key")
        void aFailedPromotionAbends() throws Exception {
            writeChunks(1);
            Mockito.when(objectStorage.upload(Mockito.anyString(),
                            Mockito.eq(generationKey(JOB_INSTANCE_ID)), Mockito.any(InputStream.class),
                            Mockito.any(ObjectMetadata.class)))
                    .thenThrow(new IllegalStateException("the bucket is unreachable"));

            assertThatExceptionOfType(FileAccessException.class)
                    .isThrownBy(() -> writer.afterStep(stepExecution))
                    .as("concatenating the parts IS this writer's CLOSE, so a failure takes the source's "
                            + "own CLOSE path at app/cbl/CBTRN02C.cbl:L600-L616, 9100-TRANFILE-CLOSE")
                    .satisfies(failure -> assertThat(failure.getCause())
                            .isInstanceOf(IllegalStateException.class));

            assertThat(loggedMessages())
                    .as("the close paragraph has its own DISPLAY at :L611, and reporting the write's text "
                            + "instead would name a diagnostic the source never emits at this point")
                    .contains("ERROR CLOSING TRANSACTION FILE", "ABENDING PROGRAM")
                    .doesNotContain("ERROR WRITING TO TRANSACTION FILE");
            assertThat(stepExecution.getExecutionContext()
                    .containsKey(TransactionWriter.OBJECT_KEY_CONTEXT_ENTRY))
                    .isFalse();
            assertThat(store.keySet())
                    .as("and the parts survive, because until the generation exists they are the only mirror")
                    .containsExactly(partKey(JOB_INSTANCE_ID, 0L));
        }

        @Test
        @DisplayName("a null execution is tolerated by the callback, as beforeStep already is")
        void aNullExecutionIsTolerated() {
            assertThat(writer.afterStep(null)).isNull();
        }

        /** The store's own maximum for one listing page and for one batched delete. */
        private static final int PAGE_LIMIT = 1000;

        /**
         * Writes one single-record chunk per requested record, advancing the step's write count so each
         * chunk stages its own part.
         *
         * @param chunks how many parts to stage
         * @throws Exception if the writer does
         */
        private void writeChunks(final int chunks) throws Exception {
            for (int ordinal = 0; ordinal < chunks; ordinal++) {
                stepExecution.setWriteCount(ordinal);
                writer.write(chunk(posted(String.format(Locale.ROOT, "%016d", ordinal + 1), "1.00")));
            }
        }
    }
}

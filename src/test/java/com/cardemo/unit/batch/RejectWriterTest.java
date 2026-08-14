/*
 * ******************************************************************
 * Program     : RejectWriterTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Verifies the DALYREJS emission boundary against the
 *               COBOL it reproduces: the 430-byte record that resolves
 *               as 350 bytes of transaction image plus an 80-byte
 *               validation trailer of a four-digit reason code and a
 *               76-character description, the zoned-decimal trailing
 *               sign overpunch on DALYTRAN-AMT, the byte-transparent
 *               ISO-8859-1 framing that keeps every record boundary
 *               where the mainframe put it, the object key derived from
 *               the job instance and job execution identifiers, the
 *               chunk and single-record entry points, the reject
 *               counter tagged by reject code, and the write-failure
 *               path that DISPLAYs then abends with code 999.
 * Source      : app/cbl/CBTRN02C.cbl:L176-L182 (REJECT-RECORD layout)
 *               app/cbl/CBTRN02C.cbl:L442-L465 (2500-WRITE-REJECT-REC)
 *               app/cbl/CBTRN02C.cbl:L714-L727 (9910-DISPLAY-IO-STATUS)
 *               app/cbl/CBTRN02C.cbl:L707-L712 (9999-ABEND-PROGRAM)
 *               app/jcl/POSTTRAN.jcl:L34-L38   (DALYREJS DD, LRECL=430)
 *               app/jcl/DALYREJS.jcl:L24-L28   (GDG base)
 *               app/cpy/CVTRA06Y.cpy           (350-byte DALYTRAN layout)
 *               app/data/ASCII/dailytran.txt   (overpunch signs) @ 7756d89
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
import com.cardemo.batch.writers.RejectWriter;
import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.exception.FileAccessException;
import com.cardemo.model.entity.DailyTransaction;
import com.cardemo.model.enums.RejectCode;
import com.cardemo.observability.MetricsConfig;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.item.Chunk;

/**
 * Unit tests for {@link RejectWriter}, the Java form of {@code 2500-WRITE-REJECT-REC} at
 * {@code app/cbl/CBTRN02C.cbl:L442-L465}.
 *
 * <h2>What it does</h2>
 * <p>
 * Every assertion here is a statement about bytes. The legacy step writes a fixed-length record to a dataset
 * declared {@code RECFM=F,LRECL=430} at {@code app/jcl/POSTTRAN.jcl:L34-L38}, and that 430 is not a round
 * number chosen for convenience: it is {@code REJECT-TRAN-DATA PIC X(350)} followed by
 * {@code VALIDATION-TRAILER}, itself {@code WS-VALIDATION-FAIL-REASON PIC 9(4)} plus
 * {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)} ({@code app/cbl/CBTRN02C.cbl:L176-L182}). The suite proves
 * the arithmetic holds field by field, that the trailing-sign overpunch on {@code DALYTRAN-AMT} is produced
 * for both signs across the whole alphabet, that the payload encodes byte for byte so no record boundary
 * moves, and that a failed write reproduces the source's {@code DISPLAY} then abend rather than being
 * swallowed.
 *
 * <h2>How to build and test</h2>
 * <pre>
 * ./mvnw -B -ntp -Dtest='RejectWriterTest' -DfailIfNoTests=false test
 * </pre>
 *
 * <h2>Key configuration and defaults</h2>
 * <ul>
 * <li>{@code carddemo.aws.s3.batch-output-bucket} - the destination bucket; {@value #BUCKET} here.</li>
 * <li>{@code #stepExecution} - the step-scoped execution the object key is derived from. A {@code null}
 * execution is tolerated and yields the unassigned identifier {@code 0}, because the writer is constructible
 * outside a step for diagnostics.</li>
 * <li>{@code ISO-8859-1} - the record charset. It is the only single-byte transparent charset in the JDK's
 * required set, and the framing guard fails the write if characters and bytes ever disagree.</li>
 * </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 * <ul>
 * <li><b>Blocker.</b> A failure in group 2 or group 3 means the emitted record no longer matches the
 * mainframe's bytes. Gate 1 compares this output against the legacy baseline, so a one-byte shift fails the
 * parity comparison for every record in the file, not just the record that moved.</li>
 * <li><b>Blocker.</b> A failure in group 4 means a value that cannot fit its picture clause is being written
 * anyway - silent truncation, which no downstream test can detect because the record still has the right
 * length.</li>
 * <li><b>High.</b> A failure in group 6 means the write-failure path no longer abends, so a lost reject file
 * would be reported as a clean run.</li>
 * <li><b>High.</b> A failure in group 5 means two runs can collide on one object key, overwriting a
 * generation that the legacy GDG would have preserved.</li>
 * <li><b>Medium.</b> A failure in group 7 means the {@code reject.code} tag is wrong, so the dashboard panel
 * that replaces {@code DISPLAY 'TRANSACTIONS REJECTED  :'} attributes rejects to the wrong reason.</li>
 * </ul>
 */
@DisplayName("RejectWriter: CBTRN02C's 430-byte DALYREJS record")
class RejectWriterTest {

    /** The destination bucket, standing in for the {@code DALYREJS} GDG base. */
    private static final String BUCKET = "carddemo-batch-output";

    /**
     * The configured generation prefix, {@value}, mirroring {@code carddemo.aws.s3.gdg-prefixes.daly-rejs}
     * without the {@code gdg/} parent so that the key assertions below read against one path segment. The
     * writer takes the prefix as configuration rather than embedding it, so the tests supply it too.
     */
    private static final String REJECT_PREFIX = "dalyrejs";

    /** The job instance identifier the generation prefix is derived from. */
    private static final long JOB_INSTANCE_ID = 7L;

    /** The job execution identifier the generation key is derived from. */
    private static final long JOB_EXECUTION_ID = 42L;

    /** {@code REJECT-TRAN-DATA PIC X(350)}, the transaction image half of the record. */
    private static final int TRAN_DATA_LENGTH = 350;

    /** {@code VALIDATION-TRAILER}, the reason half of the record. */
    private static final int TRAILER_LENGTH = 80;

    /** The whole record: {@value #TRAN_DATA_LENGTH} plus {@value #TRAILER_LENGTH}. */
    private static final int RECORD_LENGTH = TRAN_DATA_LENGTH + TRAILER_LENGTH;

    /**
     * How many keys one listing page carries here, matching the service's own maximum so the paging boundary
     * the tests cross is the real one.
     */
    private static final int KEYS_PER_PAGE = 1000;

    private S3Operations s3Operations;

    /**
     * The paging and batch-deleting half of the object store.
     *
     * <p><b>Why a second collaborator rather than more of the first.</b> {@code S3Operations.listObjects}
     * issues one {@code ListObjectsV2} and hands back that single page of at most {@value #KEYS_PER_PAGE}
     * keys, which is finding H-11: a run rejecting more than a page's worth of records staged more parts than
     * one listing could name, so the concatenation read a prefix of them and reported it as the whole
     * generation. The writer now pages through {@code S3Client.listObjectsV2Paginator} and deletes in batched
     * requests, so both have to be stubbed here, and the paging stub below returns a <em>real</em>
     * {@link ListObjectsV2Iterable} so the protocol is exercised rather than simulated.
     */
    private S3Client objectStoreClient;

    /** Every listing request the writer issued, so the page count can be asserted rather than inferred. */
    private List<ListObjectsV2Request> listingRequests;

    /** Every batched delete request the writer issued, in order, so the batching bound can be asserted. */
    private List<DeleteObjectsRequest> deleteRequests;

    /**
     * A fake object store: every object it currently holds, keyed by object key, insertion ordered.
     *
     * <p><b>Why a store rather than a captured stream.</b> Before finding M-06 the writer held one
     * {@code OutputStream} open across the whole step, so a test could observe the stream and call that the
     * object. It no longer does: each chunk uploads a complete part object and the close concatenates the parts
     * into the generation object and deletes them. Both halves of that - that the parts really are complete
     * objects, and that they are gone afterwards - can only be observed against something that models
     * completion and deletion, so this map is read AND written by the stubs below.
     */
    private Map<String, byte[]> store;

    /** Every key the writer uploaded, in upload order, including parts it later deleted. */
    private List<String> uploadOrder;

    /** The metadata stated on each uploaded object, keyed the same way. */
    private Map<String, ObjectMetadata> openedMetadata;

    /** Whether {@link #commitGeneration()} has already closed the writer, so it is closed exactly once. */
    private boolean generationClosed;

    private MeterRegistry meterRegistry;

    /**
     * The sole registrar of the four application counters. The writer counts through this collaborator rather
     * than resolving a meter itself, so the registry above is read only to assert what was registered.
     */
    private MetricsConfig metricsConfig;
    private StepExecution stepExecution;
    private RejectWriter writer;

    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger logger;
    private Level originalLevel;

    @BeforeEach
    void buildWriterAndCaptureLogs() {
        s3Operations = Mockito.mock(S3Operations.class);
        objectStoreClient = Mockito.mock(S3Client.class);
        store = new LinkedHashMap<>();
        uploadOrder = new ArrayList<>();
        openedMetadata = new LinkedHashMap<>();
        listingRequests = new ArrayList<>();
        deleteRequests = new ArrayList<>();
        generationClosed = false;
        stubObjectStore();
        meterRegistry = new SimpleMeterRegistry();
        metricsConfig = new MetricsConfig(meterRegistry);
        stepExecution = stepExecution(JOB_INSTANCE_ID, JOB_EXECUTION_ID);
        writer = new RejectWriter(s3Operations, objectStoreClient, metricsConfig, new FileStatusMapper(),
                BUCKET, REJECT_PREFIX, stepExecution);

        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(RejectWriter.class);
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
     * Builds a step execution whose job instance and job execution identifiers are known, so that the object
     * key the writer composes is fully determined by the test.
     *
     * @param instanceId the job instance identifier, which drives the generation prefix
     * @param executionId the job execution identifier, which drives the generation key
     * @return a step execution carrying both
     */
    private static StepExecution stepExecution(final long instanceId, final long executionId) {
        JobExecution jobExecution = new JobExecution(new JobInstance(Long.valueOf(instanceId), "POSTTRAN"),
                Long.valueOf(executionId), new JobParameters());
        return new StepExecution("dailyTransactionPostingStep", jobExecution);
    }

    /**
     * Builds one {@code DALYTRAN-RECORD} in the {@code CVTRA06Y} layout, with every field exactly as wide as
     * its picture clause so that the emitted image can be asserted by offset.
     *
     * @param transactionId {@code DALYTRAN-ID PIC X(16)}
     * @param amount {@code DALYTRAN-AMT PIC S9(09)V99}
     * @return the staging row that failed validation
     */
    private static DailyTransaction rejected(final String transactionId, final String amount) {
        return new DailyTransaction(
                Long.valueOf(1L),
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

    /** @return the canonical rejected row: a positive amount of 100.00. */
    private static DailyTransaction rejected() {
        return rejected("0000000000000001", "100.00");
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
     * Writes one private field of a {@link DailyTransaction}, so that a field state the entity's own guards
     * refuse to construct - a {@code null} in a {@code NOT NULL} column, a value wider than its picture
     * clause, a magnitude outside the zoned-decimal domain - still reaches the writer's geometry guards. Those
     * guards are the last line before bytes leave the process, and a guard that no test can reach is a guard
     * nobody can trust.
     *
     * @param target the row to mutate
     * @param name the declared field name on {@link DailyTransaction}
     * @param value the value to write
     */
    private static void setField(final DailyTransaction target, final String name, final Object value) {
        try {
            java.lang.reflect.Field field = DailyTransaction.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("cannot write " + name + " on DailyTransaction", failure);
        }
    }

    /**
     * Stubs a superseded generation object so a restart has something concrete to carry forward.
     *
     * <p>Both the existence probe and the download are stubbed, because the writer checks the first before
     * trusting the second: an object named by a restored context but absent from the store is nothing to carry,
     * whereas one that is present must be readable.
     *
     * @param key the superseded object's key
     * @param payload its exact bytes
     */
    private void stubSupersededObject(final String key, final byte[] payload) {
        Mockito.when(s3Operations.objectExists(BUCKET, key)).thenReturn(Boolean.TRUE);
        S3Resource prior = Mockito.mock(S3Resource.class);
        try {
            Mockito.when(prior.getInputStream()).thenReturn(new ByteArrayInputStream(payload));
        } catch (java.io.IOException impossible) {
            throw new IllegalStateException("stubbing cannot fail", impossible);
        }
        Mockito.when(s3Operations.download(BUCKET, key)).thenReturn(prior);
    }

    /**
     * Installs the recording facade over the mocked object store: upload, list, download, exists and delete.
     *
     * <p>Every stub reads and writes the {@link #store} map rather than capturing a stream, for the reason
     * that field records: after finding M-06 the writer uploads each chunk as a complete part object and
     * concatenates the parts on close, so completion and deletion are the two things a test has to be able to
     * observe, and neither is visible in a stream held open across the step.
     */
    private void stubObjectStore() {
        Mockito.when(s3Operations.upload(Mockito.anyString(), Mockito.anyString(),
                        Mockito.any(InputStream.class), Mockito.any(ObjectMetadata.class)))
                .thenAnswer(invocation -> {
                    final String key = invocation.getArgument(1, String.class);
                    final byte[] bytes;
                    try (InputStream body = invocation.getArgument(2, InputStream.class)) {
                        bytes = body.readAllBytes();
                    }
                    store.put(key, bytes);
                    uploadOrder.add(key);
                    openedMetadata.put(key, invocation.getArgument(3, ObjectMetadata.class));
                    return storedResource(key);
                });
        Mockito.when(s3Operations.download(Mockito.anyString(), Mockito.anyString()))
                .thenAnswer(invocation -> storedResource(invocation.getArgument(1, String.class)));
        Mockito.when(s3Operations.objectExists(Mockito.anyString(), Mockito.anyString()))
                .thenAnswer(invocation -> Boolean.valueOf(
                        store.containsKey(invocation.getArgument(1, String.class))));
        stubPagedListing();
        stubBatchedDelete();
    }

    /**
     * Serves the listing one page at a time, exactly as the service does.
     *
     * <p>The keys under the requested prefix are sliced into pages of {@value #KEYS_PER_PAGE}, each page
     * carrying the truncation flag and continuation token the protocol requires, and
     * {@code listObjectsV2Paginator} returns a real {@link ListObjectsV2Iterable} over this same stub. So the
     * paging is <em>exercised</em> - one request per page, driven by the token - rather than simulated by
     * handing back every key at once, which is what would let a single-page regression pass unnoticed.
     *
     * <p>The keys are sorted before slicing, so a part on the second page is genuinely a part the first page
     * did not name.
     */
    private void stubPagedListing() {
        Mockito.when(objectStoreClient.listObjectsV2(Mockito.any(ListObjectsV2Request.class)))
                .thenAnswer(invocation -> {
                    final ListObjectsV2Request request =
                            invocation.getArgument(0, ListObjectsV2Request.class);
                    listingRequests.add(request);
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
                    final int to = Math.min(from + KEYS_PER_PAGE, matching.size());
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
        Mockito.when(objectStoreClient.listObjectsV2Paginator(Mockito.any(ListObjectsV2Request.class)))
                .thenAnswer(invocation -> new ListObjectsV2Iterable(objectStoreClient,
                        invocation.getArgument(0, ListObjectsV2Request.class)));
    }

    /**
     * Removes every key a batched delete names, and reports no per-key error.
     *
     * <p>The request is recorded so a test can assert that no batch exceeded the store's thousand-key limit -
     * a bound the writer honours explicitly rather than by hoping a run stays small.
     */
    private void stubBatchedDelete() {
        Mockito.when(objectStoreClient.deleteObjects(Mockito.any(DeleteObjectsRequest.class)))
                .thenAnswer(invocation -> {
                    final DeleteObjectsRequest request =
                            invocation.getArgument(0, DeleteObjectsRequest.class);
                    deleteRequests.add(request);
                    for (final ObjectIdentifier identifier : request.delete().objects()) {
                        store.remove(identifier.key());
                    }
                    return DeleteObjectsResponse.builder().build();
                });
    }

    /**
     * Builds a resource view of one object the fake store holds.
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
        } catch (java.io.IOException impossible) {
            throw new IllegalStateException("stubbing cannot fail", impossible);
        }
        return resource;
    }

    /**
     * The key of the part an earlier attempt would have left at one ordinal.
     *
     * @param ordinal the zero-based part ordinal
     * @return the part key under this generation
     */
    private static String partKey(final long ordinal) {
        return String.format(Locale.ROOT, "%s/%019d/parts/reject-part-%019d.dat",
                REJECT_PREFIX, Long.valueOf(JOB_INSTANCE_ID), Long.valueOf(ordinal));
    }

    /**
     * Plants the durable parts an interrupted earlier attempt of this generation left behind.
     *
     * <p>This is what a real failure leaves, and it is the correction to how the restart used to be tested.
     * The previous harness stubbed a <em>completed</em> prior generation object - a state a failed attempt can
     * never leave, because its upload is abandoned - so the consolidation path was being verified against a
     * fiction while the real path silently lost records. Finding M-06.
     *
     * @param parts each earlier chunk's payload, in the order it was written
     */
    private void stubPriorAttemptParts(final byte[]... parts) {
        for (int ordinal = 0; ordinal < parts.length; ordinal++) {
            store.put(partKey(ordinal), parts[ordinal]);
        }
    }

    /**
     * Completes the run's single generation, exactly as a step does at its end.
     *
     * <p>{@code app/cbl/CBTRN02C.cbl} writes records and then performs {@code 9300-DALYREJS-CLOSE} once; the
     * writer mirrors that, so the object exists only after the close. Every payload and key helper below goes
     * through this method so no assertion can accidentally inspect a generation that was never closed.
     * Idempotent, so a test may also close explicitly.
     */
    private void commitGeneration() {
        if (!generationClosed) {
            generationClosed = true;
            writer.close();
        }
    }

    /**
     * Completes the generation and returns its content as a string in the record charset.
     *
     * @return the written payload
     */
    private String uploadedPayload() {
        return new String(uploadedBytes(), StandardCharsets.ISO_8859_1);
    }

    /**
     * Completes the generation and returns its raw bytes, so that byte length can be compared against
     * character length independently of any decoding.
     *
     * @return the written bytes
     */
    private byte[] uploadedBytes() {
        commitGeneration();
        assertThat(store).as("exactly one generation object per run").hasSize(1);
        return store.values().iterator().next();
    }

    /** @return the object key of the single generation the writer created. */
    private String uploadedKey() {
        commitGeneration();
        assertThat(store)
                .as("app/jcl/POSTTRAN.jcl:L38 names ONE dataset, so a run creates ONE object and the "
                        + "durable chunk parts it was assembled from are gone")
                .hasSize(1);
        return store.keySet().iterator().next();
    }

    /**
     * @return every object key the store still holds after the close, in insertion order - the generation
     *     objects, the parts having been deleted once the generation was committed
     */
    private List<String> uploadedKeys() {
        commitGeneration();
        return List.copyOf(store.keySet());
    }

    /** @return every key the writer uploaded, parts included, in upload order. */
    private List<String> allUploadedKeys() {
        return List.copyOf(uploadOrder);
    }

    /** @return the metadata of the single generation the writer created. */
    private ObjectMetadata uploadedMetadata() {
        return openedMetadata.get(uploadedKey());
    }

    /** @return every message this class's logger received. */
    private List<String> loggedMessages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /**
     * Builds a chunk of rejected items.
     *
     * @param items the items
     * @return the chunk a chunk-oriented step would hand to the writer
     */
    private static Chunk<RejectWriter.RejectedTransaction> chunk(final RejectWriter.RejectedTransaction... items) {
        List<RejectWriter.RejectedTransaction> list = new ArrayList<>(List.of(items));
        return new Chunk<>(list);
    }

    /**
     * Reads one counter's value.
     *
     * @param code the reject code the counter is tagged with
     * @return the count, or zero when the counter was never registered
     */
    private double rejectCount(final int code) {
        io.micrometer.core.instrument.Counter counter = meterRegistry
                .find(MetricsConfig.METRIC_RECORDS_REJECTED)
                .tag(MetricsConfig.TAG_REJECT_CODE, Integer.toString(code))
                .counter();
        return counter == null ? 0.0d : counter.count();
    }

    @Nested
    @DisplayName("1. Construction: the collaborators the emission cannot proceed without")
    class Construction {

        @Test
        @DisplayName("a null store is refused by name")
        void aNullStoreIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new RejectWriter(null, objectStoreClient, metricsConfig,
                            new FileStatusMapper(), BUCKET, REJECT_PREFIX, stepExecution))
                    .withMessage("s3Operations must not be null");
        }

        @Test
        @DisplayName("a null object-store client is refused by name")
        void aNullObjectStoreClientIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new RejectWriter(s3Operations, null, metricsConfig,
                            new FileStatusMapper(), BUCKET, REJECT_PREFIX, stepExecution))
                    .as("the paging client is what makes the part listing complete at any reject volume, so a "
                            + "context missing it must fail at startup rather than at a truncated close")
                    .withMessage("objectStoreClient must not be null");
        }

        @Test
        @DisplayName("a null metrics collaborator is refused by name")
        void aNullMetricsConfigIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new RejectWriter(s3Operations, objectStoreClient, null,
                            new FileStatusMapper(), BUCKET, REJECT_PREFIX, stepExecution))
                    .withMessage("metricsConfig must not be null");
        }

        @Test
        @DisplayName("a null status mapper is refused by name")
        void aNullStatusMapperIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new RejectWriter(s3Operations, objectStoreClient, metricsConfig, null,
                            BUCKET, REJECT_PREFIX, stepExecution))
                    .withMessage("fileStatusMapper must not be null");
        }

        @Test
        @DisplayName("a null bucket is refused by name")
        void aNullBucketIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new RejectWriter(s3Operations, objectStoreClient, metricsConfig,
                            new FileStatusMapper(), null, REJECT_PREFIX, stepExecution))
                    .withMessageContaining(
                            "carddemo.aws.s3.batch-output-bucket must be configured with a non-blank value");
        }

        @Test
        @DisplayName("a null step execution is tolerated and yields the unassigned identifier")
        void aNullStepExecutionIsTolerated() {
            RejectWriter detached =
                    new RejectWriter(s3Operations, objectStoreClient, metricsConfig, new FileStatusMapper(),
                            BUCKET, REJECT_PREFIX, null);

            detached.writeReject(rejected(), RejectCode.INVALID_CARD_NUMBER);

            assertThat(uploadedKey())
                    .as("outside a step there is no job instance, so the prefix carries nineteen zeros")
                    .startsWith("dalyrejs/0000000000000000000/");
        }

        @Test
        @DisplayName("outside a step nothing is published to an execution context, and the writer says so")
        void outsideAStepNothingIsPublished() {
            RejectWriter detached =
                    new RejectWriter(s3Operations, objectStoreClient, metricsConfig, new FileStatusMapper(),
                            BUCKET, REJECT_PREFIX, null);

            detached.writeReject(rejected(), RejectCode.INVALID_CARD_NUMBER);
            // The key is published by the close, so the close is what has nothing to publish to.
            detached.close();

            assertThat(loggedMessages())
                    .contains("No step context is available, so the DALYREJS generation key is not published");
        }
    }

    @Nested
    @DisplayName("2. BLOCKER: the record is 430 bytes, and 430 is 350 plus 80")
    class RecordGeometry {

        @Test
        @DisplayName("one reject emits exactly 430 characters")
        void oneRejectEmitsExactly430Characters() {
            writer.writeReject(rejected(), RejectCode.INVALID_CARD_NUMBER);

            assertThat(uploadedPayload())
                    .as("app/jcl/POSTTRAN.jcl declares DALYREJS as RECFM=F,LRECL=430")
                    .hasSize(RECORD_LENGTH);
        }

        @Test
        @DisplayName("the trailer is a four-digit reason code followed by a 76-character description")
        void theTrailerIsAReasonCodeAndADescription() {
            writer.writeReject(rejected(), RejectCode.OVERLIMIT_TRANSACTION);

            String trailer = uploadedPayload().substring(TRAN_DATA_LENGTH);
            assertThat(trailer).hasSize(TRAILER_LENGTH);
            assertThat(trailer.substring(0, 4))
                    .as("WS-VALIDATION-FAIL-REASON PIC 9(4) is zero-filled, not space-filled")
                    .isEqualTo("0102");
            assertThat(trailer.substring(4))
                    .as("WS-VALIDATION-FAIL-REASON-DESC PIC X(76) carries the literal, space-padded")
                    .isEqualTo(pad("OVERLIMIT TRANSACTION", 76));
        }

        @ParameterizedTest(name = "reject {0} renders reason {1} and its own literal description")
        @CsvSource({
            "INVALID_CARD_NUMBER, 0100, INVALID CARD NUMBER FOUND",
            "ACCOUNT_RECORD_NOT_FOUND, 0101, ACCOUNT RECORD NOT FOUND",
            "OVERLIMIT_TRANSACTION, 0102, OVERLIMIT TRANSACTION",
            "TRANSACTION_RECEIVED_AFTER_ACCT_EXPIRATION, 0103, TRANSACTION RECEIVED AFTER ACCT EXPIRATION",
            "ACCOUNT_RECORD_NOT_FOUND_ON_REWRITE, 0109, ACCOUNT RECORD NOT FOUND"})
        @DisplayName("every reject code renders its own reason and its own exact literal")
        void everyRejectCodeRendersItsOwnLiteral(final RejectCode rejectCode, final String reason,
                final String description) {
            writer.writeReject(rejected(), rejectCode);

            String trailer = uploadedPayload().substring(TRAN_DATA_LENGTH);
            assertThat(trailer.substring(0, 4)).isEqualTo(reason);
            assertThat(trailer.substring(4)).isEqualTo(pad(description, 76));
        }

        @Test
        @DisplayName("every DALYTRAN field lands at the offset CVTRA06Y gives it")
        void everyFieldLandsAtItsOffset() {
            writer.writeReject(rejected(), RejectCode.INVALID_CARD_NUMBER);

            String image = uploadedPayload().substring(0, TRAN_DATA_LENGTH);
            assertThat(image.substring(0, 16)).as("DALYTRAN-ID").isEqualTo("0000000000000001");
            assertThat(image.substring(16, 18)).as("DALYTRAN-TYPE-CD").isEqualTo("01");
            assertThat(image.substring(18, 22)).as("DALYTRAN-CAT-CD").isEqualTo("5001");
            assertThat(image.substring(22, 32)).as("DALYTRAN-SOURCE").isEqualTo("POS TERM  ");
            assertThat(image.substring(32, 132)).as("DALYTRAN-DESC")
                    .isEqualTo(pad("Payment at Amazon", 100));
            assertThat(image.substring(132, 143)).as("DALYTRAN-AMT").isEqualTo("0000001000{");
            assertThat(image.substring(143, 152)).as("DALYTRAN-MERCHANT-ID").isEqualTo("123456789");
            assertThat(image.substring(152, 202)).as("DALYTRAN-MERCHANT-NAME")
                    .isEqualTo(pad("Amazon.com", 50));
            assertThat(image.substring(202, 252)).as("DALYTRAN-MERCHANT-CITY")
                    .isEqualTo(pad("Seattle", 50));
            assertThat(image.substring(252, 262)).as("DALYTRAN-MERCHANT-ZIP").isEqualTo("0000098101");
            assertThat(image.substring(262, 278)).as("DALYTRAN-CARD-NUM").isEqualTo("4111111111111111");
            assertThat(image.substring(278, 304)).as("DALYTRAN-ORIG-TS")
                    .isEqualTo("2022-07-18-11.22.33.123456");
            assertThat(image.substring(304, 330)).as("DALYTRAN-PROC-TS")
                    .isEqualTo("2022-07-18-11.22.34.123456");
            assertThat(image.substring(330))
                    .as("the 20-byte FILLER completes the 350-byte staging record")
                    .isEqualTo(" ".repeat(20));
        }

        @Test
        @DisplayName("the payload encodes byte for byte, so no record boundary moves")
        void thePayloadEncodesByteForByte() {
            writer.writeReject(rejected(), RejectCode.INVALID_CARD_NUMBER);

            byte[] bytes = uploadedBytes();
            assertThat(bytes)
                    .as("a multi-byte charset would silently shift every subsequent record")
                    .hasSize(RECORD_LENGTH);
            assertThat(new String(bytes, StandardCharsets.ISO_8859_1))
                    .as("byte length and character length must agree, or a record boundary has moved")
                    .hasSize(bytes.length);
        }

        @Test
        @DisplayName("a shorter field is right-padded rather than shifting its neighbours left")
        void aShorterFieldIsRightPadded() {
            DailyTransaction transaction = rejected();
            setField(transaction, "merchantName", "AMZ");

            writer.writeReject(transaction, RejectCode.INVALID_CARD_NUMBER);

            String image = uploadedPayload();
            assertThat(image.substring(152, 202)).isEqualTo(pad("AMZ", 50));
            assertThat(image.substring(202, 252))
                    .as("the city must still start at 203, not at 156")
                    .isEqualTo(pad("Seattle", 50));
            assertThat(image).hasSize(RECORD_LENGTH);
        }

        @Test
        @DisplayName("an absent optional text field becomes spaces, never a shortened record")
        void anAbsentTextFieldBecomesSpaces() {
            DailyTransaction transaction = rejected();
            setField(transaction, "merchantCity", null);

            writer.writeReject(transaction, RejectCode.INVALID_CARD_NUMBER);

            String image = uploadedPayload();
            assertThat(image.substring(202, 252)).isEqualTo(" ".repeat(50));
            assertThat(image).hasSize(RECORD_LENGTH);
        }
    }

    @Nested
    @DisplayName("3. BLOCKER: the trailing-sign overpunch of DALYTRAN-AMT PIC S9(09)V99")
    class SignOverpunch {

        @ParameterizedTest(name = "{0} renders as {1}")
        @CsvSource(delimiter = '|', value = {
            "0.00|0000000000{",
            "0.01|0000000000A",
            "0.02|0000000000B",
            "0.03|0000000000C",
            "0.04|0000000000D",
            "0.05|0000000000E",
            "0.06|0000000000F",
            "0.07|0000000000G",
            "0.08|0000000000H",
            "0.09|0000000000I",
            "100.00|0000001000{",
            "999999999.99|9999999999I"})
        @DisplayName("a non-negative amount carries the positive overpunch alphabet { through I")
        void aPositiveAmountCarriesThePositiveAlphabet(final String amount, final String rendered) {
            writer.writeReject(rejected("0000000000000001", amount), RejectCode.INVALID_CARD_NUMBER);

            assertThat(uploadedPayload().substring(132, 143)).isEqualTo(rendered);
        }

        @ParameterizedTest(name = "{0} renders as {1}")
        @CsvSource(delimiter = '|', value = {
            "-0.01|0000000000J",
            "-0.02|0000000000K",
            "-0.03|0000000000L",
            "-0.04|0000000000M",
            "-0.05|0000000000N",
            "-0.06|0000000000O",
            "-0.07|0000000000P",
            "-0.08|0000000000Q",
            "-0.09|0000000000R",
            "-100.00|0000001000}",
            "-999999999.99|9999999999R"})
        @DisplayName("a negative amount carries the negative overpunch alphabet } through R")
        void aNegativeAmountCarriesTheNegativeAlphabet(final String amount, final String rendered) {
            writer.writeReject(rejected("0000000000000001", amount), RejectCode.INVALID_CARD_NUMBER);

            assertThat(uploadedPayload().substring(132, 143))
                    .as("app/data/ASCII/dailytran.txt carries both signs, so the debit branch is real")
                    .isEqualTo(rendered);
        }

        @Test
        @DisplayName("a negative amount is never normalised to its absolute value")
        void aNegativeAmountIsNeverNormalised() {
            writer.writeReject(rejected("0000000000000001", "-250.55"), RejectCode.OVERLIMIT_TRANSACTION);

            assertThat(uploadedPayload().substring(132, 143))
                    .as("the sign lives in the final byte; taking abs() would emit E instead of N")
                    .isEqualTo("0000002505N");
        }

        @Test
        @DisplayName("an amount of scale one is rendered at scale two, not truncated")
        void anAmountOfScaleOneIsRenderedAtScaleTwo() {
            writer.writeReject(rejected("0000000000000001", "12.5"), RejectCode.INVALID_CARD_NUMBER);

            assertThat(uploadedPayload().substring(132, 143)).isEqualTo("0000000125{");
        }

        @Test
        @DisplayName("an amount beyond PIC S9(09)V99 is refused rather than truncated")
        void anOverWideAmountIsRefused() {
            DailyTransaction transaction = rejected();
            setField(transaction, "amount", new BigDecimal("1000000000.00"));

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> writer.writeReject(transaction, RejectCode.INVALID_CARD_NUMBER))
                    .withMessageContaining("does not fit its picture clause");
            Mockito.verify(s3Operations, Mockito.never()).upload(Mockito.anyString(), Mockito.anyString(),
                    Mockito.any(InputStream.class), Mockito.any(ObjectMetadata.class));
        }

        @Test
        @DisplayName("an amount below the negative bound is refused rather than truncated")
        void anUnderWideAmountIsRefused() {
            DailyTransaction transaction = rejected();
            setField(transaction, "amount", new BigDecimal("-1000000000.00"));

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> writer.writeReject(transaction, RejectCode.INVALID_CARD_NUMBER))
                    .withMessageContaining("does not fit its picture clause");
        }

        @Test
        @DisplayName("an absent amount is refused, because PIC S9(09)V99 has no null representation")
        void anAbsentAmountIsRefused() {
            DailyTransaction transaction = rejected();
            setField(transaction, "amount", null);

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> writer.writeReject(transaction, RejectCode.INVALID_CARD_NUMBER))
                    .withMessageContaining("has no null");
        }

        @Test
        @DisplayName("an amount of scale three is rounded half even, matching the posting arithmetic")
        void anAmountOfScaleThreeIsRoundedHalfEven() {
            DailyTransaction transaction = rejected();
            setField(transaction, "amount", new BigDecimal("0.005"));

            writer.writeReject(transaction, RejectCode.INVALID_CARD_NUMBER);

            assertThat(uploadedPayload().substring(132, 143))
                    .as("HALF_EVEN sends a tie to the even digit, so 0.005 becomes 0.00")
                    .isEqualTo("0000000000{");
        }
    }

    @Nested
    @DisplayName("4. BLOCKER: a value that cannot fit its picture clause is refused, never truncated")
    class GeometryGuards {

        @Test
        @DisplayName("an over-wide text field abends rather than being silently truncated")
        void anOverWideTextFieldAbends() {
            DailyTransaction transaction = rejected();
            setField(transaction, "merchantName", "X".repeat(51));

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> writer.writeReject(transaction, RejectCode.INVALID_CARD_NUMBER))
                    .withMessageContaining("DALYTRAN-MERCHANT-NAME does not fit its picture clause");
        }

        @Test
        @DisplayName("the geometry failure withholds the offending value, which may identify a customer")
        void theGeometryFailureWithholdsTheValue() {
            DailyTransaction transaction = rejected();
            setField(transaction, "merchantName", "SECRETMERCHANT".repeat(4));

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> writer.writeReject(transaction, RejectCode.INVALID_CARD_NUMBER))
                    .satisfies(failure -> assertThat(failure.getMessage())
                            .as("Rule 1 clause D1 keeps field content out of diagnostics")
                            .doesNotContain("SECRETMERCHANT"));
        }

        @Test
        @DisplayName("the geometry failure abends as CBTRN02C with code 999 and return code 12")
        void theGeometryFailureAbendsAsCbtrn02c() {
            DailyTransaction transaction = rejected();
            setField(transaction, "amount", null);

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> writer.writeReject(transaction, RejectCode.INVALID_CARD_NUMBER))
                    .satisfies(failure -> {
                        assertThat(failure.getAbendCode()).isEqualTo("999");
                        assertThat(failure.getAbendCulprit()).isEqualTo("CBTRN02C");
                        assertThat(failure.getAbendReason()).isEqualTo("REJECT RECORD GEOMETRY VIOLATION");
                    });
        }

        @Test
        @DisplayName("an absent category code is refused, because an unsigned field has no null form")
        void anAbsentCategoryCodeIsRefused() {
            DailyTransaction transaction = rejected();
            setField(transaction, "categoryCode", null);

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> writer.writeReject(transaction, RejectCode.INVALID_CARD_NUMBER))
                    .withMessageContaining("DALYTRAN-CAT-CD is null and cannot be rendered");
        }

        @Test
        @DisplayName("an absent merchant identifier is refused for the same reason")
        void anAbsentMerchantIdentifierIsRefused() {
            DailyTransaction transaction = rejected();
            setField(transaction, "merchantId", null);

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> writer.writeReject(transaction, RejectCode.INVALID_CARD_NUMBER))
                    .withMessageContaining("DALYTRAN-MERCHANT-ID is null and cannot be rendered");
        }

        @ParameterizedTest(name = "a category code of {0} is refused")
        @ValueSource(ints = {-1, 10000})
        @DisplayName("a category code outside PIC 9(04) is refused")
        void anOutOfRangeCategoryCodeIsRefused(final int categoryCode) {
            DailyTransaction transaction = rejected();
            setField(transaction, "categoryCode", Integer.valueOf(categoryCode));

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> writer.writeReject(transaction, RejectCode.INVALID_CARD_NUMBER))
                    .withMessageContaining("admits only 0 through 9999");
        }

        @ParameterizedTest(name = "a merchant identifier of {0} is refused")
        @ValueSource(longs = {-1L, 1_000_000_000L})
        @DisplayName("a merchant identifier outside PIC 9(09) is refused")
        void anOutOfRangeMerchantIdentifierIsRefused(final long merchantId) {
            DailyTransaction transaction = rejected();
            setField(transaction, "merchantId", Long.valueOf(merchantId));

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> writer.writeReject(transaction, RejectCode.INVALID_CARD_NUMBER))
                    .withMessageContaining("admits only 0 through 999999999");
        }

        @Test
        @DisplayName("a category code narrower than its field is zero-filled on the left")
        void aNarrowCategoryCodeIsZeroFilled() {
            DailyTransaction transaction = rejected();
            setField(transaction, "categoryCode", Integer.valueOf(7));

            writer.writeReject(transaction, RejectCode.INVALID_CARD_NUMBER);

            assertThat(uploadedPayload().substring(18, 22))
                    .as("PIC 9(04) is zero-filled; space-filling would break the downstream sort")
                    .isEqualTo("0007");
        }
    }

    @Nested
    @DisplayName("5. The generation key that replaces the GDG relative generation")
    class GenerationKey {

        @Test
        @DisplayName("the key carries the job instance and the job execution, and no chunk sequence")
        void theKeyCarriesBothIdentifiersAndNoSequence() {
            writer.writeReject(rejected(), RejectCode.INVALID_CARD_NUMBER);

            assertThat(uploadedKey())
                    .as("app/jcl/DALYREJS.jcl's GDG base becomes a deterministic object prefix, and "
                            + "app/jcl/POSTTRAN.jcl:L38 names ONE dataset per run, so there is no per-chunk "
                            + "sequence to carry")
                    .isEqualTo(String.format(Locale.ROOT, "dalyrejs/%019d/%019d.dat",
                            JOB_INSTANCE_ID, JOB_EXECUTION_ID));
        }

        @Test
        @DisplayName("HIGH H-04: two emissions land in ONE generation object, not two")
        void twoEmissionsShareTheOneGenerationObject() {
            writer.writeReject(rejected(), RejectCode.INVALID_CARD_NUMBER);
            writer.writeReject(rejected("0000000000000002", "5.00"), RejectCode.OVERLIMIT_TRANSACTION);

            assertThat(uploadedKeys())
                    .as("app/jcl/POSTTRAN.jcl:L38 writes AWS.M2.CARDDEMO.DALYREJS(+1) - a single dataset - so a "
                            + "later (0) reference resolves the whole run. One object per emission fragmented "
                            + "that generation and made (0) resolve only the last fragment")
                    .containsExactly(String.format(Locale.ROOT, "dalyrejs/%019d/%019d.dat",
                            JOB_INSTANCE_ID, JOB_EXECUTION_ID));
            assertThat(uploadedPayload())
                    .as("both records are in that one object, in the order they were written")
                    .hasSize(2 * RECORD_LENGTH)
                    .startsWith("0000000000000001");
        }

        /**
         * A run that rejects more records than one listing page can name still lands whole.
         *
         * <p>Purpose: assert finding H-11 at the boundary that used to break. The part listing was a single
         * {@code ListObjectsV2}, which the service truncates at {@value #KEYS_PER_PAGE} keys, so a run staging
         * more parts than that concatenated only the keys the first page carried - and, because the surplus
         * parts were also never named for deletion, left them behind as orphans while reporting a complete
         * generation. The posting step commits once per record by parity contract, so one part per record makes
         * this boundary a volume of {@value #KEYS_PER_PAGE} rejects, not a theoretical extreme.
         *
         * <p>{@value #KEYS_PER_PAGE} plus five parts is deliberately just past the boundary: it needs two
         * listing pages, so a regression to a single request fails on both the byte count and the leftovers,
         * and it keeps the fixture small enough to stay a unit test.
         */
        @Test
        @DisplayName("H-11: more parts than one listing page still concatenate whole, and none is orphaned")
        void aRunPastOneListingPageStillConcatenatesEveryPart() {
            final int rejects = KEYS_PER_PAGE + 5;
            for (int index = 0; index < rejects; index++) {
                writer.writeReject(rejected(String.format(Locale.ROOT, "%016d", Integer.valueOf(index + 1)),
                        "1.00"), RejectCode.INVALID_CARD_NUMBER);
            }
            assertThat(store).as("one part per emission, all staged before the close").hasSize(rejects);

            commitGeneration();

            assertThat(store.keySet())
                    .as("the close leaves exactly the generation object: every part was named for deletion, "
                            + "including the ones past the first listing page")
                    .containsExactly(String.format(Locale.ROOT, "dalyrejs/%019d/%019d.dat",
                            JOB_INSTANCE_ID, JOB_EXECUTION_ID));
            assertThat(store.values().iterator().next())
                    .as("and it holds every record - a single-page listing produced " + KEYS_PER_PAGE
                            + " records here and called it the whole generation")
                    .hasSize(rejects * RECORD_LENGTH);
            assertThat(listingRequests)
                    .as("which took more than one request, because the service pages at " + KEYS_PER_PAGE)
                    .hasSizeGreaterThan(1);
        }

        /**
         * The parts are deleted in batches that honour the store's own request limit.
         *
         * <p>Purpose: a per-key delete loop at one part per record meant one sequential round trip per
         * rejected transaction at the end of an otherwise finished run. Batching is the standard remedy, and
         * the only thing that can go wrong with it is exceeding the thousand-key limit a single
         * {@code DeleteObjects} accepts - so that bound is what is asserted, along with the completeness the
         * batching must not cost.
         */
        @Test
        @DisplayName("the promoted parts are deleted in batches of at most 1000 keys, and every one is named")
        void thePromotedPartsAreDeletedInBoundedBatches() {
            final int rejects = KEYS_PER_PAGE + 5;
            for (int index = 0; index < rejects; index++) {
                writer.writeReject(rejected(String.format(Locale.ROOT, "%016d", Integer.valueOf(index + 1)),
                        "1.00"), RejectCode.INVALID_CARD_NUMBER);
            }

            commitGeneration();

            assertThat(deleteRequests)
                    .as("more parts than one request may name, so more than one request is issued")
                    .hasSize(2);
            int named = 0;
            for (final DeleteObjectsRequest request : deleteRequests) {
                assertThat(request.delete().objects())
                        .as("no batch may exceed the store's own limit for one DeleteObjects request")
                        .hasSizeLessThanOrEqualTo(KEYS_PER_PAGE);
                named += request.delete().objects().size();
            }
            assertThat(named)
                    .as("and between them the batches name every staged part exactly once")
                    .isEqualTo(rejects);
        }

        @Test
        @DisplayName("two job instances write under different prefixes")
        void twoJobInstancesWriteUnderDifferentPrefixes() {
            RejectWriter other = new RejectWriter(s3Operations, objectStoreClient, metricsConfig,
                    new FileStatusMapper(), BUCKET, REJECT_PREFIX, stepExecution(8L, 43L));

            writer.writeReject(rejected(), RejectCode.INVALID_CARD_NUMBER);
            other.writeReject(rejected(), RejectCode.INVALID_CARD_NUMBER);
            other.close();

            // Order is not asserted: each generation object comes into existence at its own close, and the
            // two closes here are interleaved by the test rather than by the writers. What matters is that
            // the two job instances landed under two different prefixes.
            assertThat(uploadedKeys())
                    .containsExactlyInAnyOrder(
                            String.format(Locale.ROOT, "dalyrejs/%019d/%019d.dat", 7L, 42L),
                            String.format(Locale.ROOT, "dalyrejs/%019d/%019d.dat", 8L, 43L));
        }

        /**
         * A chunk's records are durable objects the moment the chunk is written, not only at close.
         *
         * <p>Purpose: assert finding M-06 directly, rather than through its restart consequence. The defect was
         * that {@link RejectWriter#update(ExecutionContext)} published a record count at every chunk boundary
         * while the records themselves existed only inside an upload that a failure would abandon - so the
         * checkpoint described bytes that were about to cease to exist. What is asserted here is the property
         * that makes the checkpoint honest: after a write and before any close, the store already holds the
         * chunk's bytes, and the count the writer publishes agrees with them.
         */
        @Test
        @DisplayName("M-06: a written chunk is a complete object BEFORE the close, so the checkpoint the "
                + "writer publishes describes bytes that already exist")
        void aWrittenChunkIsDurableBeforeTheClose() {
            writer.writeReject(rejected(), RejectCode.INVALID_CARD_NUMBER);
            writer.writeReject(rejected("0000000000000002", "1.00"), RejectCode.OVERLIMIT_TRANSACTION);

            // No close yet. Under the previous implementation the store held nothing at this point.
            assertThat(store.keySet())
                    .as("each chunk's records are a complete object as soon as they are written, so a failure "
                            + "from here on cannot lose them")
                    .containsExactly(partKey(0L), partKey(1L));
            assertThat(store.get(partKey(0L)))
                    .as("and each part carries whole 430-byte records, so it can be read on its own")
                    .hasSize(RECORD_LENGTH);

            final ExecutionContext checkpoint = new ExecutionContext();
            writer.update(checkpoint);
            assertThat(checkpoint.getLong(RejectWriter.REJECT_RECORD_COUNT_CONTEXT_KEY))
                    .as("the published count describes durable objects rather than buffered bytes")
                    .isEqualTo(2L);
            assertThat(checkpoint.getLong(RejectWriter.REJECT_PART_COUNT_CONTEXT_KEY))
                    .as("and the part count says how many objects that is")
                    .isEqualTo(2L);

            long durableBytes = 0L;
            for (final byte[] part : store.values()) {
                durableBytes += part.length;
            }
            assertThat(durableBytes / RECORD_LENGTH)
                    .as("the durable record total must equal the published count, which is exactly the "
                            + "reconciliation that was impossible before")
                    .isEqualTo(checkpoint.getLong(RejectWriter.REJECT_RECORD_COUNT_CONTEXT_KEY));
        }

        @Test
        @DisplayName("a restart carries the failed attempt's durable parts into ONE object, and deletes them "
                + "only after that object is committed")
        void aRestartConsolidatesRatherThanFragmentingTheGeneration() throws Exception {
            // THE SHAPE A REAL FAILURE LEAVES. Finding M-06. This case used to plant a COMPLETED prior
            // generation object - a state a failed attempt can never leave behind, because the object it was
            // building existed only inside an upload that the failure abandoned. The consolidation path was
            // therefore being verified against a fiction while the real path silently lost every reject the
            // failed attempt had found. What a failure leaves now is durable chunk parts, so that is what is
            // planted here.
            //
            // Measured before the H-04 fix, on the 300-row fixture with a forced failure at record 46: two
            // objects of 430x4 and 430x34 under one generation, a manifest naming only the second, and a
            // published record count of 34 beside a reject count of 38.
            final byte[] firstPart = new byte[RECORD_LENGTH];
            final byte[] secondPart = new byte[RECORD_LENGTH];
            java.util.Arrays.fill(firstPart, (byte) 'A');
            java.util.Arrays.fill(secondPart, (byte) 'B');
            stubPriorAttemptParts(firstPart, secondPart);

            final ExecutionContext restored = new ExecutionContext();
            restored.putLong(RejectWriter.REJECT_RECORD_COUNT_CONTEXT_KEY, 2L);

            writer.open(restored);
            writer.writeReject(rejected(), RejectCode.INVALID_CARD_NUMBER);

            // Asserted before the generation is committed, because the ordering is the safety property: while
            // the generation object does not yet exist the parts are the only copy of the run's rejects, so a
            // failure here cannot lose records that exist nowhere else.
            Mockito.verify(objectStoreClient, Mockito.never())
                    .deleteObjects(Mockito.any(DeleteObjectsRequest.class));

            assertThat(uploadedKeys())
                    .as("the generation must end as ONE object, with the parts it was assembled from deleted")
                    .containsExactly(String.format(Locale.ROOT, "dalyrejs/%019d/%019d.dat",
                            JOB_INSTANCE_ID, JOB_EXECUTION_ID));
            assertThat(uploadedPayload())
                    .as("the failed attempt's records come first, in ordinal order, then this attempt's - the "
                            + "order an uninterrupted run would have written them in")
                    .hasSize(3 * RECORD_LENGTH)
                    .startsWith("AAAA");
            assertThat(uploadedPayload().substring(RECORD_LENGTH, RECORD_LENGTH + 4))
                    .as("and the second part follows the first, not the other way round: the ordinal is zero "
                            + "padded so lexicographic key order is write order")
                    .isEqualTo("BBBB");

            assertThat(deleteRequests)
                    .as("both carried parts were named for deletion once the generation was committed")
                    .singleElement()
                    .satisfies(request -> assertThat(request.delete().objects().stream()
                            .map(ObjectIdentifier::key)
                            .toList())
                            .contains(partKey(0L), partKey(1L)));

            final ExecutionContext published = new ExecutionContext();
            writer.update(published);
            assertThat(published.getLong(RejectWriter.REJECT_RECORD_COUNT_CONTEXT_KEY))
                    .as("the published count must describe the object it names - 2 carried plus 1 written - "
                            + "or a consumer cannot reconcile it against the reject count")
                    .isEqualTo(3L);
        }

        @Test
        @DisplayName("a restart that rejects nothing still promotes the failed attempt's parts, so the "
                + "generation holds its records rather than being lost with them")
        void aRestartThatRejectsNothingRepublishesTheCarriedForwardObject() {
            stubPriorAttemptParts(new byte[RECORD_LENGTH], new byte[RECORD_LENGTH]);

            final ExecutionContext restored = new ExecutionContext();
            restored.putLong(RejectWriter.REJECT_RECORD_COUNT_CONTEXT_KEY, 2L);

            writer.open(restored);
            writer.close();

            final String expectedKey = String.format(Locale.ROOT, "dalyrejs/%019d/%019d.dat",
                    JOB_INSTANCE_ID, JOB_EXECUTION_ID);
            assertThat(store.keySet())
                    .as("this attempt rejected nothing of its own, but the failed attempt's records are real "
                            + "and unreproducible - the reader has moved past them - so the close still "
                            + "promotes them into the generation")
                    .containsExactly(expectedKey);
            assertThat(store.get(expectedKey))
                    .as("both carried records, and nothing invented")
                    .hasSize(2 * RECORD_LENGTH);
            assertThat(stepExecution.getExecutionContext()
                    .getString(RejectWriter.REJECT_OBJECT_KEY_CONTEXT_KEY, ""))
                    .as("and the manifest names the object that actually holds them")
                    .isEqualTo(expectedKey);
        }

        @Test
        @DisplayName("a superseded object whose length is not a whole number of records is refused rather "
                + "than copied, because copying it would misalign every record after it")
        void aMisframedSupersededObjectIsRefused() {
            // A part whose length is not a whole multiple of the reject record length is not a shorter run,
            // it is a corrupt one, and adopting it would misalign every record after it.
            stubPriorAttemptParts(new byte[RECORD_LENGTH + 7]);

            final ExecutionContext restored = new ExecutionContext();
            restored.putLong(RejectWriter.REJECT_RECORD_COUNT_CONTEXT_KEY, 1L);

            // Refused at open, which is earlier than the old behaviour managed: the misframing is detected
            // when the parts are adopted rather than when the first new record is written, so nothing is ever
            // appended on top of a misaligned carry-forward.
            assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> writer.open(restored))
                    .withStackTraceContaining("not a whole number of");
        }

        @Test
        @DisplayName("the object metadata declares an opaque content type and the exact content length")
        void theMetadataDeclaresAnOpaqueContentType() {
            writer.writeReject(rejected(), RejectCode.INVALID_CARD_NUMBER);

            ObjectMetadata metadata = uploadedMetadata();
            assertThat(metadata.getContentType())
                    .as("a generic octet stream, so that no intermediary treats a fixed-width record stream "
                            + "as text and translates line endings it does not have")
                    .isEqualTo("application/octet-stream");
            assertThat(metadata.getContentLength())
                    .as("since finding M-06 the parts are complete objects and the generation is assembled "
                            + "from them, so the total IS known before the upload begins - and declaring it "
                            + "is what makes a short or long concatenation a refused upload rather than a "
                            + "stored one")
                    .isEqualTo(Long.valueOf(RECORD_LENGTH));
        }

        @Test
        @DisplayName("a closed generation publishes the key, the prefix and the cumulative count")
        void aSuccessfulEmissionPublishesItsKey() {
            writer.writeReject(rejected(), RejectCode.INVALID_CARD_NUMBER);

            assertThat(stepExecution.getExecutionContext()
                    .containsKey(RejectWriter.REJECT_OBJECT_KEY_CONTEXT_KEY))
                    .as("the key names an object the store has not accepted until the close, so publishing it "
                            + "before then would let a downstream step read a generation that is not there")
                    .isFalse();
            String key = uploadedKey();

            assertThat(stepExecution.getExecutionContext()
                    .getString(RejectWriter.REJECT_OBJECT_KEY_CONTEXT_KEY))
                    .isEqualTo(key);
            assertThat(stepExecution.getExecutionContext()
                    .getString(RejectWriter.REJECT_GENERATION_PREFIX_CONTEXT_KEY))
                    .isEqualTo(String.format(Locale.ROOT, "dalyrejs/%019d/", JOB_INSTANCE_ID));
            assertThat(stepExecution.getExecutionContext()
                    .getLong(RejectWriter.REJECT_RECORD_COUNT_CONTEXT_KEY))
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("the published count accumulates across emissions")
        void thePublishedCountAccumulates() throws Exception {
            writer.write(chunk(
                    new RejectWriter.RejectedTransaction(rejected(), RejectCode.INVALID_CARD_NUMBER),
                    new RejectWriter.RejectedTransaction(rejected("0000000000000002", "1.00"),
                            RejectCode.OVERLIMIT_TRANSACTION)));
            writer.writeReject(rejected("0000000000000003", "2.00"), RejectCode.ACCOUNT_RECORD_NOT_FOUND);

            assertThat(stepExecution.getExecutionContext()
                    .getLong(RejectWriter.REJECT_RECORD_COUNT_CONTEXT_KEY))
                    .as("the count is the run's reject total, which drives the return code 4 decision")
                    .isEqualTo(3L);
        }
    }

    @Nested
    @DisplayName("6. HIGH: a failed write DISPLAYs, then abends, and publishes nothing")
    class WriteFailure {

        @BeforeEach
        void makeTheStoreFail() {
            // The failure is injected on the upload of a chunk's durable part, which is where a WRITE now
            // reaches the store (finding M-06). Before, it was injected on createResource, because a write
            // only opened a stream.
            Mockito.when(s3Operations.upload(Mockito.anyString(), Mockito.anyString(),
                            Mockito.any(InputStream.class), Mockito.any(ObjectMetadata.class)))
                    .thenThrow(new IllegalStateException("the bucket is unreachable"));
        }

        @Test
        @DisplayName("the source's own DISPLAY text precedes the abend")
        void theSourcesDisplayTextPrecedesTheAbend() {
            assertThatExceptionOfType(RuntimeException.class)
                    .isThrownBy(() -> writer.writeReject(rejected(), RejectCode.INVALID_CARD_NUMBER));

            assertThat(loggedMessages())
                    .as("app/cbl/CBTRN02C.cbl:L460 DISPLAYs before performing 9999-ABEND-PROGRAM")
                    .contains("ERROR WRITING TO REJECTS FILE");
        }

        @Test
        @DisplayName("9910-DISPLAY-IO-STATUS renders the '9x' status as four characters")
        void theIoStatusIsRenderedAsFourCharacters() {
            assertThatExceptionOfType(RuntimeException.class)
                    .isThrownBy(() -> writer.writeReject(rejected(), RejectCode.INVALID_CARD_NUMBER));

            assertThat(loggedMessages())
                    .as("a '9' first byte expands the second byte into three digits")
                    .contains("FILE STATUS IS: NNNN9048");
        }

        @Test
        @DisplayName("the abend line names the file, the operation, the code and the return code")
        void theAbendLineNamesEverything() {
            assertThatExceptionOfType(RuntimeException.class)
                    .isThrownBy(() -> writer.writeReject(rejected(), RejectCode.INVALID_CARD_NUMBER));

            assertThat(loggedMessages()).anyMatch(message -> message.startsWith("ABENDING PROGRAM: DALYREJS")
                    && message.contains("WRITE")
                    && message.contains("999")
                    && message.contains("12"));
        }

        @Test
        @DisplayName("the '9x' status maps to a file access exception carrying the store failure as its cause")
        void theStatusMapsToAFileAccessException() {
            assertThatExceptionOfType(FileAccessException.class)
                    .isThrownBy(() -> writer.writeReject(rejected(), RejectCode.INVALID_CARD_NUMBER))
                    .satisfies(failure -> assertThat(failure.getCause())
                            .as("Rule 1 clause B4 preserves the root cause rather than swallowing it")
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessage("the bucket is unreachable"));
        }

        @Test
        @DisplayName("a failed write publishes no key, so no downstream step reads a phantom generation")
        void aFailedWritePublishesNoKey() {
            assertThatExceptionOfType(RuntimeException.class)
                    .isThrownBy(() -> writer.writeReject(rejected(), RejectCode.INVALID_CARD_NUMBER));

            assertThat(stepExecution.getExecutionContext()
                    .containsKey(RejectWriter.REJECT_OBJECT_KEY_CONTEXT_KEY))
                    .isFalse();
        }

        @Test
        @DisplayName("a failed write increments no reject counter")
        void aFailedWriteCountsNothing() {
            assertThatExceptionOfType(RuntimeException.class)
                    .isThrownBy(() -> writer.writeReject(rejected(), RejectCode.INVALID_CARD_NUMBER));

            assertThat(rejectCount(RejectCode.INVALID_CARD_NUMBER.getCode()))
                    .as("a record that never reached the dataset was never rejected, only lost")
                    .isZero();
        }
    }

    @Nested
    @DisplayName("7. The rejected-records counter that replaces DISPLAY 'TRANSACTIONS REJECTED  :'")
    class RejectCounter {

        @Test
        @DisplayName("one reject increments the counter tagged with its own code")
        void oneRejectIncrementsItsOwnCounter() {
            writer.writeReject(rejected(), RejectCode.OVERLIMIT_TRANSACTION);

            assertThat(rejectCount(102)).isEqualTo(1.0d);
            assertThat(rejectCount(100)).isZero();
        }

        @Test
        @DisplayName("two codes are counted separately, so the dashboard can attribute rejects")
        void twoCodesAreCountedSeparately() {
            writer.writeReject(rejected(), RejectCode.INVALID_CARD_NUMBER);
            writer.writeReject(rejected("0000000000000002", "1.00"), RejectCode.INVALID_CARD_NUMBER);
            writer.writeReject(rejected("0000000000000003", "2.00"),
                    RejectCode.TRANSACTION_RECEIVED_AFTER_ACCT_EXPIRATION);

            assertThat(rejectCount(100)).isEqualTo(2.0d);
            assertThat(rejectCount(103)).isEqualTo(1.0d);
        }

        @Test
        @DisplayName("the counter carries the name and tag key the dashboard queries")
        void theCounterCarriesTheDocumentedNameAndTag() {
            writer.writeReject(rejected(), RejectCode.ACCOUNT_RECORD_NOT_FOUND);

            assertThat(MetricsConfig.METRIC_RECORDS_REJECTED).isEqualTo("carddemo.batch.records.rejected");
            assertThat(MetricsConfig.TAG_REJECT_CODE).isEqualTo("reject.code");
            assertThat(meterRegistry.find(MetricsConfig.METRIC_RECORDS_REJECTED).counters())
                    .as("MetricsConfig pre-registers one series per reject code at startup, so a code that "
                            + "never occurs reports zero rather than being absent from the scrape. There are "
                            + "exactly five reject codes, so there are exactly five series")
                    .hasSize(RejectCode.values().length);
            assertThat(meterRegistry.find(MetricsConfig.METRIC_RECORDS_REJECTED)
                    .tag(MetricsConfig.TAG_REJECT_CODE,
                            Integer.toString(RejectCode.ACCOUNT_RECORD_NOT_FOUND.getCode()))
                    .counter())
                    .as("and the write above incremented the one series its reject code names")
                    .isNotNull()
                    .satisfies(counter -> assertThat(counter.count()).isEqualTo(1.0d));
        }
    }

    @Nested
    @DisplayName("8. The chunk entry point: one generation per chunk, one count per record")
    class ChunkWriting {

        @Test
        @DisplayName("an empty chunk appends nothing and says why")
        void anEmptyChunkCreatesNoGeneration() throws Exception {
            writer.write(chunk());
            writer.close();

            Mockito.verify(s3Operations, Mockito.never()).upload(Mockito.anyString(), Mockito.anyString(),
                    Mockito.any(InputStream.class), Mockito.any(ObjectMetadata.class));
            assertThat(loggedMessages())
                    .contains("No rejected transactions in this chunk; nothing is appended to the DALYREJS "
                            + "generation")
                    .as("a run that rejects nothing must leave no object behind at all")
                    .contains("No rejected transactions were written, so no DALYREJS generation was created");
        }

        @Test
        @DisplayName("a chunk of three emits one object of exactly three records")
        void aChunkOfThreeEmitsOneObject() throws Exception {
            writer.write(chunk(
                    new RejectWriter.RejectedTransaction(rejected(), RejectCode.INVALID_CARD_NUMBER),
                    new RejectWriter.RejectedTransaction(rejected("0000000000000002", "1.00"),
                            RejectCode.OVERLIMIT_TRANSACTION),
                    new RejectWriter.RejectedTransaction(rejected("0000000000000003", "-2.00"),
                            RejectCode.ACCOUNT_RECORD_NOT_FOUND)));

            String payload = uploadedPayload();
            assertThat(payload)
                    .as("a fixed-length dataset is a whole number of records, never a delimited stream")
                    .hasSize(3 * RECORD_LENGTH);
            assertThat(payload.substring(0, 16)).isEqualTo("0000000000000001");
            assertThat(payload.substring(RECORD_LENGTH, RECORD_LENGTH + 16)).isEqualTo("0000000000000002");
            assertThat(payload.substring(2 * RECORD_LENGTH, 2 * RECORD_LENGTH + 16))
                    .isEqualTo("0000000000000003");
        }

        @Test
        @DisplayName("a chunk preserves the reject code of each record, not the first record's code")
        void aChunkPreservesEachRecordsCode() throws Exception {
            writer.write(chunk(
                    new RejectWriter.RejectedTransaction(rejected(), RejectCode.INVALID_CARD_NUMBER),
                    new RejectWriter.RejectedTransaction(rejected("0000000000000002", "1.00"),
                            RejectCode.TRANSACTION_RECEIVED_AFTER_ACCT_EXPIRATION)));

            String payload = uploadedPayload();
            assertThat(payload.substring(TRAN_DATA_LENGTH, TRAN_DATA_LENGTH + 4)).isEqualTo("0100");
            assertThat(payload.substring(RECORD_LENGTH + TRAN_DATA_LENGTH,
                    RECORD_LENGTH + TRAN_DATA_LENGTH + 4)).isEqualTo("0103");
        }

        @Test
        @DisplayName("a chunk increments one counter per record, per code")
        void aChunkCountsEveryRecord() throws Exception {
            writer.write(chunk(
                    new RejectWriter.RejectedTransaction(rejected(), RejectCode.INVALID_CARD_NUMBER),
                    new RejectWriter.RejectedTransaction(rejected("0000000000000002", "1.00"),
                            RejectCode.INVALID_CARD_NUMBER),
                    new RejectWriter.RejectedTransaction(rejected("0000000000000003", "2.00"),
                            RejectCode.OVERLIMIT_TRANSACTION)));

            assertThat(rejectCount(100)).isEqualTo(2.0d);
            assertThat(rejectCount(102)).isEqualTo(1.0d);
        }

        @Test
        @DisplayName("a null chunk is refused by name")
        void aNullChunkIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> writer.write(null))
                    .withMessage("chunk must not be null");
        }

        @Test
        @DisplayName("an item pair refuses a null transaction and a null reject code by name")
        void anItemPairRefusesNulls() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new RejectWriter.RejectedTransaction(null,
                            RejectCode.INVALID_CARD_NUMBER))
                    .withMessage("transaction must not be null");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new RejectWriter.RejectedTransaction(rejected(), null))
                    .withMessage("rejectCode must not be null");
        }

        @Test
        @DisplayName("HIGH H-04: two chunks open one object rather than one object per chunk")
        void aChunkEmitsOneObjectNotThree() throws Exception {
            writer.write(chunk(
                    new RejectWriter.RejectedTransaction(rejected(), RejectCode.INVALID_CARD_NUMBER),
                    new RejectWriter.RejectedTransaction(rejected("0000000000000002", "1.00"),
                            RejectCode.INVALID_CARD_NUMBER)));
            writer.write(chunk(
                    new RejectWriter.RejectedTransaction(rejected("0000000000000003", "2.00"),
                            RejectCode.OVERLIMIT_TRANSACTION)));
            writer.close();

            assertThat(store)
                    .as("app/jcl/POSTTRAN.jcl:L38 names ONE dataset, so two chunks leave ONE object and the "
                            + "durable parts they were assembled from have been deleted")
                    .hasSize(1);
            assertThat(store.values().iterator().next())
                    .as("all three records are in the one generation object")
                    .hasSize(3 * RECORD_LENGTH);
            assertThat(allUploadedKeys())
                    .as("two chunks upload two durable parts, then the close promotes them into the one "
                            + "generation object - which is what makes a written reject survive a failure "
                            + "(finding M-06)")
                    .containsExactly(partKey(0L), partKey(1L), uploadedKey());
        }

        @Test
        @DisplayName("a chunk whose first record is unrenderable emits nothing at all")
        void aBadRecordInAChunkEmitsNothing() {
            DailyTransaction broken = rejected("0000000000000002", "1.00");
            setField(broken, "amount", null);

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> writer.write(chunk(
                            new RejectWriter.RejectedTransaction(rejected(), RejectCode.INVALID_CARD_NUMBER),
                            new RejectWriter.RejectedTransaction(broken, RejectCode.OVERLIMIT_TRANSACTION))));

            Mockito.verify(s3Operations, Mockito.never()).upload(Mockito.anyString(), Mockito.anyString(),
                    Mockito.any(InputStream.class), Mockito.any(ObjectMetadata.class));
            assertThat(rejectCount(100))
                    .as("the whole chunk is built before any byte is written, so nothing is half-counted")
                    .isZero();
        }
    }
}

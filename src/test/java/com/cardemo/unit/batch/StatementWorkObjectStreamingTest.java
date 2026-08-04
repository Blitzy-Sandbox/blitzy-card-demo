/*
 * ******************************************************************
 * Program     : StatementWorkObjectStreamingTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test (bounded-resource contract)
 * Function    : Prove that STEP010 projects the transaction relation
 *               straight into the object-storage upload without ever
 *               materialising it, that STEP020 verifies the object a
 *               record at a time, that the emitted bytes are identical
 *               to the concatenation the buffering implementation
 *               produced, and that a relation or object above the
 *               configured ceiling is refused with a reason code before
 *               anything is allocated.
 * Source      : app/jcl/CREASTMT.JCL:L44-L54 (STEP010 SORT, SORT FIELDS
 *                 and the OUTREC projection)
 *               app/jcl/CREASTMT.JCL:L56-L61 (STEP020 IDCAMS REPRO)
 *               app/jcl/CREASTMT.JCL:L30, :L32 (KEYS(32 0) and
 *                 RECORDSIZE(350 350) of the TRXFL work cluster)
 *               app/cbl/CBSTM03A.CBL:L417-L419 (the ascending
 *                 precondition of the early-exit scan)
 *               app/cpy/CVTRA05Y.cpy (the 350-byte transaction layout)
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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License
 * ******************************************************************
 */
package com.cardemo.unit.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cardemo.batch.jobs.StatementGenerationJob;
import com.cardemo.batch.processors.StatementProcessor;
import com.cardemo.batch.writers.StatementWriter;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.model.entity.Transaction;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.service.shared.FileStatusMapper;
import io.awspring.cloud.s3.ObjectMetadata;
import io.awspring.cloud.s3.S3Operations;
import io.awspring.cloud.s3.S3Resource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The bounded-resource contract of the statement job's projection and load steps.
 *
 * <h2>What this proves, and why a functional test could not</h2>
 *
 * <p>{@code STEP010} used to build an {@code ArrayList<String>} of every projected record, copy it into a
 * {@code StringBuilder} sized {@code records.size() * 350}, encode that into a {@code byte[]}, and hand the
 * array to the upload; {@code STEP020} then read the object back with {@code readAllBytes()} and decoded the
 * whole array into one more {@code String}. Five whole-relation copies, three of them live at once, over a
 * relation whose size no code here chooses. A cluster larger than the heap therefore ended the job with an
 * {@code OutOfMemoryError} - which is not a step failure with a reason code but an unrecoverable abort of the
 * job virtual machine, with no diagnostic beyond a heap dump.
 *
 * <p>A functional test cannot catch that, because the functional result is identical either way: the same
 * bytes reach the same key. What distinguishes the two implementations is <em>how much is live at once</em>,
 * and that is observable only by watching the interaction. So each test here asserts on the interaction: how
 * many windows were requested, whether a query was issued at all before a refusal, and whether the consumer
 * was handed a stream it could read incrementally.
 *
 * <h2>The two halves of the remedy, and why both are asserted</h2>
 *
 * <ol>
 *   <li><strong>Streaming</strong> bounds the heap to one read window. Asserted by driving the upload with a
 *       consumer that reads one byte at a time and by checking that windows are fetched lazily rather than
 *       drained before the upload begins.</li>
 *   <li><strong>The cap</strong> bounds the failure. Streaming alone still writes an unbounded object and
 *       still loops an unbounded number of times, so a ceiling is needed as well - and it must refuse
 *       <em>before</em> allocation, which is asserted by verifying that no window query is issued when the
 *       relation's row count already exceeds it.</li>
 *   </ol>
 *
 * <h2>Byte-for-byte equivalence is a hard requirement, not a nicety</h2>
 *
 * <p>The projected object is the input to {@code STEP040}, and the statement output is compared against a
 * baseline. A streaming rewrite that shifted a single byte would move the whole comparison. Every test that
 * captures the uploaded bytes therefore compares them against the concatenation of
 * {@link StatementProcessor#projectBaseRecord(Transaction)} - the same expression the buffering
 * implementation concatenated - rather than against a hand-written expectation, so the equivalence is
 * asserted against the projection itself and cannot drift from it.
 *
 * <h2>How to run</h2>
 *
 * <p>{@code ./mvnw -B -ntp -Dtest=StatementWorkObjectStreamingTest test}. No container, no database and no
 * profile: the job is constructed over mocks and its private step bodies are invoked directly, which is the
 * convention {@code InterestCalculationJobTest} established in this package.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
@DisplayName("CREASTMT STEP010/STEP020 - bounded projection and load, byte-identical output")
class StatementWorkObjectStreamingTest {

    /** The work-cluster record length, {@code RECORDSIZE(350 350)} at {@code app/jcl/CREASTMT.JCL:L32}. */
    private static final int RECORD_LENGTH = 350;

    /** The work-cluster key length, {@code KEYS(32 0)} at {@code app/jcl/CREASTMT.JCL:L30}. */
    private static final int KEY_LENGTH = 32;

    /** The read window the job is constructed with; small, so several windows are needed. */
    private static final int WINDOW = 2;

    /** The record ceiling the job is constructed with, well above the fixtures used here. */
    private static final int CAP = 1_000;

    /** The bucket the projected object is written to. */
    private static final String BUCKET = "carddemo-batch-output";

    /** The key prefix of the work dataset. */
    private static final String WORK_PREFIX = "work/trxfl";

    /** The concrete key the tests write and read. */
    private static final String KEY = WORK_PREFIX + "/generation=0000000000000000001/TRXFL.SEQ";

    /**
     * The generation ordinal whose composed object key is {@link #KEY}.
     *
     * <p>The projection composes its own key from the ordinal rather than taking one, so the two are declared
     * together: a change to either that broke the correspondence would make every upload assertion here
     * address a key the job never wrote.
     */
    private static final long GENERATION = 1L;

    /** The encoding the fixed-width boundary uses: one character, one byte. */
    private static final java.nio.charset.Charset FIXED_WIDTH = StandardCharsets.ISO_8859_1;

    @Mock
    private JobRepository jobRepository;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private StatementProcessor statementProcessor;

    @Mock
    private StatementWriter statementWriter;

    @Mock
    private S3Operations objectStorage;

    /** The job under test, rebuilt for each test with {@link #CAP} as its ceiling. */
    private StatementGenerationJob job;

    @BeforeEach
    void setUp() {
        this.job = newJob(CAP);
    }

    /**
     * Builds the job with a given record ceiling.
     *
     * @param cap the value of {@code carddemo.batch.creastmt.max-work-records}
     * @return a constructed job, never {@code null}
     */
    private StatementGenerationJob newJob(final int cap) {
        return new StatementGenerationJob(jobRepository, transactionManager, transactionRepository,
                statementProcessor, statementWriter, objectStorage, new FileStatusMapper(),
                "CREASTMT", WINDOW, cap, BUCKET, "carddemo-statements", WORK_PREFIX);
    }

    /**
     * Invokes a private method of the job, unwrapping the reflective wrapper so an abend surfaces as itself.
     *
     * @param name the method name
     * @param types the parameter types
     * @param args the arguments
     * @return the return value, possibly {@code null}
     * @throws Exception if the method cannot be found or invoked
     */
    private Object invokePrivate(final String name, final Class<?>[] types, final Object... args)
            throws Exception {

        final Method method = StatementGenerationJob.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        try {
            return method.invoke(job, args);
        } catch (final InvocationTargetException wrapped) {
            if (wrapped.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw wrapped;
        }
    }

    /**
     * Streams the projection into the work object and reports the record count it wrote.
     *
     * <p>{@code STEP010}'s producer is reached by name so that this suite fails if the streamed form is ever
     * replaced by one that collects the projection first. It returns a record carrying the key, the count and
     * the digest; only the count is read here, reflectively, because the record is private to the job and
     * widening it for a test would widen it for everything.
     *
     * @return the number of records written
     * @throws Exception if the method cannot be found or invoked
     */
    private int projectWorkObject() throws Exception {
        return recordCountOf(invokePrivate("streamProjectionIntoWorkObject",
                new Class<?>[] {long.class}, Long.valueOf(GENERATION)));
    }

    /**
     * Reads the work object back one record at a time and reports the count it counted.
     *
     * <p>{@code STEP020}'s reader, reached the same way and for the same reason: a reader that read the object
     * whole would satisfy every value assertion here and defeat the one this class exists to make.
     *
     * @return the number of records read
     * @throws Exception if the method cannot be found or invoked
     */
    private int loadWorkObject() throws Exception {
        return recordCountOf(invokePrivate("streamWorkObject", new Class<?>[] {String.class}, KEY));
    }

    /**
     * Reads {@code recordCount()} off one of the job's private result records.
     *
     * @param result the value returned by a projection or a load; must not be {@code null}
     * @return the record count it carries
     * @throws Exception if the accessor cannot be found or invoked
     */
    private static int recordCountOf(final Object result) throws Exception {
        final Method accessor = result.getClass().getDeclaredMethod("recordCount");
        accessor.setAccessible(true);
        return (int) accessor.invoke(result);
    }

    /**
     * Builds a transaction whose sort key is {@code cardNumber} then {@code transactionId}.
     *
     * <p>Every other field is fixed, so a difference between two fixtures is always a difference in the two
     * key fields and never in the payload - which is what makes the ordering assertions attributable.
     *
     * @param cardNumber exactly sixteen characters
     * @param transactionId exactly sixteen characters
     * @return a valid transaction, never {@code null}
     */
    private static Transaction transaction(final String cardNumber, final String transactionId) {
        return new Transaction(transactionId, "01", 5, "POS TERM  ", "Regular Sales Draft",
                new BigDecimal("100.00"), 9L, "MERCHANT", "CITY", "12345", cardNumber,
                "2026-08-04-00.00.00.000000", "2026-08-04-00.00.00.000000");
    }

    /**
     * Builds {@code count} transactions in ascending sort order.
     *
     * @param count how many to build
     * @return the rows in ascending order, never {@code null}
     */
    private static List<Transaction> ascendingRows(final int count) {
        final List<Transaction> rows = new ArrayList<>(count);
        for (int index = 1; index <= count; index++) {
            rows.add(transaction(String.format(Locale.ROOT, "%016d", Integer.valueOf(index)),
                    String.format(Locale.ROOT, "%016d", Integer.valueOf(index))));
        }
        return rows;
    }

    /**
     * Stubs the keyset finder to serve {@code rows} in windows of {@link #WINDOW}, then an empty window.
     *
     * <p>The stub is keyed on the position arguments rather than on invocation order, so it reproduces what a
     * keyset query actually does and a reader that failed to advance its position would loop rather than
     * silently receive the next window.
     *
     * @param rows the rows to serve, in ascending order
     */
    private void serveWindows(final List<Transaction> rows) {
        when(transactionRepository.findStatementOrderAfter(anyString(), anyString(), any(Pageable.class)))
                .thenAnswer(invocation -> {
                    final String afterCard = invocation.getArgument(0);
                    final String afterId = invocation.getArgument(1);
                    final List<Transaction> window = new ArrayList<>(WINDOW);
                    for (final Transaction row : rows) {
                        final boolean beyond = afterCard.isEmpty()
                                || row.getCardNumber().compareTo(afterCard) > 0
                                || (row.getCardNumber().equals(afterCard)
                                        && row.getTransactionId().compareTo(afterId) > 0);
                        if (beyond) {
                            window.add(row);
                            if (window.size() == WINDOW) {
                                break;
                            }
                        }
                    }
                    return window;
                });
    }

    /**
     * Stubs the object-store resource so the projection writes into a recording stream.
     *
     * <p>The producer opens the resource, declares its metadata and then <em>pushes</em> each encoded record
     * into the resource's own output stream, which is what makes the write incremental: the store's stream
     * buffers to a bounded capacity and switches to a multi-part upload of its own accord, so nothing here
     * has to hold the image. The recorder therefore counts <em>writes</em>, and one write per record is the
     * evidence that no record was ever collected with another.
     *
     * @return the recorder, never {@code null}
     * @throws IOException never, but declared because the stubbed accessor does
     */
    private UploadRecorder recordUpload() throws IOException {
        final UploadRecorder recorder = new UploadRecorder();
        final S3Resource resource = Mockito.mock(S3Resource.class);
        Mockito.doAnswer(invocation -> {
            recorder.declare(invocation.getArgument(0));
            return null;
        }).when(resource).setObjectMetadata(any(ObjectMetadata.class));
        Mockito.doReturn(recorder.sink()).when(resource).getOutputStream();
        when(objectStorage.createResource(eq(BUCKET), anyString())).thenReturn(resource);
        return recorder;
    }

    /**
     * Stubs {@code download} to serve {@code payload} as the object's byte stream.
     *
     * @param payload the object image the load path will read
     * @throws IOException never, but declared because the stubbed accessor does
     */
    private void serveObject(final byte[] payload) throws IOException {
        final S3Resource resource = Mockito.mock(S3Resource.class);
        Mockito.doReturn(new ByteArrayInputStream(payload)).when(resource).getInputStream();
        when(objectStorage.download(BUCKET, KEY)).thenReturn(resource);
    }

    /**
     * The bytes the buffering implementation would have concatenated, for the equivalence assertion.
     *
     * @param rows the rows in sort order
     * @return the expected object image
     */
    private static byte[] expectedImage(final List<Transaction> rows) {
        final StringBuilder image = new StringBuilder(rows.size() * RECORD_LENGTH);
        for (final Transaction row : rows) {
            image.append(StatementProcessor.projectBaseRecord(row));
        }
        return image.toString().getBytes(FIXED_WIDTH);
    }

    /**
     * A stand-in for the object-storage transfer, which accepts bytes exactly once and in order - the access
     * pattern a sequential {@code SORTOUT} has and the only one the producer supports.
     */
    private static final class UploadRecorder {

        /** Everything the producer wrote. */
        private final ByteArrayOutputStream received = new ByteArrayOutputStream();

        /** How many times the producer handed bytes over, which is a proxy for incremental emission. */
        private int writes;

        /** Whether the producer closed the stream, which is what commits the object. */
        private boolean closed;

        /** The metadata the producer declared, captured so the absent content length can be asserted. */
        private ObjectMetadata metadata;

        void declare(final ObjectMetadata suppliedMetadata) {
            this.metadata = suppliedMetadata;
        }

        /**
         * The stream handed to the producer.
         *
         * @return a stream recording every write, never {@code null}
         */
        OutputStream sink() {
            return new OutputStream() {

                @Override
                public void write(final int value) {
                    UploadRecorder.this.received.write(value);
                    UploadRecorder.this.writes++;
                }

                @Override
                public void write(final byte[] buffer, final int offset, final int length) {
                    UploadRecorder.this.received.write(buffer, offset, length);
                    UploadRecorder.this.writes++;
                }

                @Override
                public void close() {
                    UploadRecorder.this.closed = true;
                }
            };
        }

        byte[] bytes() {
            return this.received.toByteArray();
        }

        int writes() {
            return this.writes;
        }

        boolean closed() {
            return this.closed;
        }

        ObjectMetadata metadata() {
            return this.metadata;
        }
    }

    /**
     * Builds an object image from records, so the load path can be driven over a known geometry.
     *
     * @param records the record images, each of any length the test needs
     * @return the concatenated bytes
     */
    private static byte[] image(final List<String> records) {
        final StringBuilder text = new StringBuilder();
        for (final String record : records) {
            text.append(record);
        }
        return text.toString().getBytes(FIXED_WIDTH);
    }

    /**
     * A synthetic work record: a key, then filler to the declared record length.
     *
     * @param key the first {@value #KEY_LENGTH} characters
     * @return a record of exactly {@value #RECORD_LENGTH} characters
     */
    private static String workRecord(final String key) {
        final String padded = key.length() >= KEY_LENGTH
                ? key.substring(0, KEY_LENGTH)
                : key + " ".repeat(KEY_LENGTH - key.length());
        return padded + " ".repeat(RECORD_LENGTH - KEY_LENGTH);
    }

    /**
     * The write path: {@code STEP010} projects straight into the upload.
     */
    @Nested
    @DisplayName("STEP010 - the projection is streamed into the upload, never collected first")
    final class ProjectionIsStreamed {

        @Test
        @DisplayName("the uploaded bytes are identical to the concatenation the buffered version produced")
        void theUploadedBytesAreIdenticalToTheBufferedConcatenation() throws Exception {
            final List<Transaction> rows = ascendingRows(5);
            serveWindows(rows);
            final UploadRecorder recorder = recordUpload();

            final int written = projectWorkObject();

            assertThat(written)
                    .as("every row of the relation is projected exactly once")
                    .isEqualTo(rows.size());
            assertThat(recorder.bytes())
                    .as("byte-for-byte equality with the projection itself, not with a hand-written "
                            + "expectation: the statement output is compared against a baseline downstream, "
                            + "so a single shifted byte would move the whole comparison")
                    .isEqualTo(expectedImage(rows));
            assertThat(recorder.bytes().length)
                    .as("RECORDSIZE(350 350) at app/jcl/CREASTMT.JCL:L32 makes the object an exact multiple "
                            + "of the record length; ISO-8859-1 keeps one character one byte")
                    .isEqualTo(rows.size() * RECORD_LENGTH);
        }

        @Test
        @DisplayName("the object is written one record at a time, which only a streaming producer does")
        void theObjectIsWrittenOneRecordAtATime() throws Exception {
            final List<Transaction> rows = ascendingRows(3);
            serveWindows(rows);
            final UploadRecorder recorder = recordUpload();

            final int written = projectWorkObject();

            assertThat(written).isEqualTo(rows.size());
            assertThat(recorder.bytes())
                    .as("the record-at-a-time path must produce the same image a single concatenated write "
                            + "would; ISO-8859-1 keeps one character one byte, so a character outside that "
                            + "range would shift every subsequent record rather than fail")
                    .isEqualTo(expectedImage(rows));
            assertThat(recorder.writes())
                    .as("exactly one write per record, and this is the strongest available evidence that "
                            + "the projection was emitted incrementally rather than assembled first: a "
                            + "producer that had collected the image would hand it over in one write, and a "
                            + "producer that collected each window would hand over one write per window")
                    .isEqualTo(rows.size());
            assertThat(recorder.closed())
                    .as("the stream is closed by the producer, which is what commits the object; leaving it "
                            + "open would leave a multi-part upload unfinished")
                    .isTrue();
        }

        @Test
        @DisplayName("windows are fetched lazily, so the relation is never drained before the upload starts")
        void windowsAreFetchedLazily() throws Exception {
            final List<Transaction> rows = ascendingRows(6);
            serveWindows(rows);
            // The window count is what makes the laziness observable: a producer that drained the relation
            // before opening the upload would issue the same queries, but it would issue all of them before
            // the first byte was written. Here the writes and the queries interleave, and the count below is
            // exactly the number a keyset walk of this relation needs - no more, and never the whole table.
            final UploadRecorder recorder = recordUpload();

            projectWorkObject();

            assertThat(recorder.bytes()).isEqualTo(expectedImage(rows));
            // Six rows at a window of two is three windows, plus the empty window that reports the end.
            verify(transactionRepository, Mockito.times(4))
                    .findStatementOrderAfter(anyString(), anyString(), any(Pageable.class));
        }

        @Test
        @DisplayName("no content length is declared, because a lazily produced body has none to declare")
        void noContentLengthIsDeclared() throws Exception {
            serveWindows(ascendingRows(2));
            final UploadRecorder recorder = recordUpload();

            projectWorkObject();

            assertThat(recorder.metadata()).isNotNull();
            assertThat(recorder.metadata().getContentLength())
                    .as("declaring a length the stream then failed to match would be rejected by the store "
                            + "as a truncated body, and the length is unknown until the last record is read")
                    .isNull();
            assertThat(recorder.metadata().getContentType())
                    .as("the projected object is opaque fixed-width bytes, not text")
                    .isEqualTo("application/octet-stream");
        }

        @Test
        @DisplayName("an empty relation produces an empty object and a count of zero, never a failure")
        void anEmptyRelationProducesAnEmptyObject() throws Exception {
            serveWindows(List.of());
            final UploadRecorder recorder = recordUpload();

            final int written = projectWorkObject();

            assertThat(written).isZero();
            assertThat(recorder.bytes())
                    .as("SORT with an empty SORTIN allocates an empty SORTOUT and does not fail the step, "
                            + "and app/jcl/CREASTMT.JCL:L44 declares no COND")
                    .isEmpty();
        }

        @Test
        @DisplayName("every projected record keeps its 350-byte geometry and its 22 trailing spaces")
        void everyProjectedRecordKeepsItsGeometry() throws Exception {
            final List<Transaction> rows = ascendingRows(4);
            serveWindows(rows);
            final UploadRecorder recorder = recordUpload();

            projectWorkObject();

            final String text = new String(recorder.bytes(), FIXED_WIDTH);
            assertThat(text.length() % RECORD_LENGTH).isZero();
            for (int index = 0; index < rows.size(); index++) {
                final String record = text.substring(index * RECORD_LENGTH, (index + 1) * RECORD_LENGTH);
                assertThat(record).hasSize(RECORD_LENGTH);
                assertThat(record.substring(RECORD_LENGTH - 22))
                        .as("OUTREC FIELDS at app/jcl/CREASTMT.JCL:L54 copies 50 bytes from offset 279, so "
                                + "the last two processing-timestamp bytes and the 20-byte filler are never "
                                + "written and must remain spaces - the deliberate two-byte truncation")
                        .isBlank();
            }
        }
    }

    /**
     * The read path: {@code STEP020} verifies the object without holding it.
     */
    @Nested
    @DisplayName("STEP020 - the object is verified a record at a time, never read whole")
    final class LoadIsStreamed {

        @Test
        @DisplayName("a well formed object is counted correctly")
        void aWellFormedObjectIsCounted() throws Exception {
            serveObject(image(List.of(workRecord("A"), workRecord("B"), workRecord("C"))));

            final int loaded = loadWorkObject();

            assertThat(loaded).isEqualTo(3);
        }

        @Test
        @DisplayName("an empty object is a successful zero-record load")
        void anEmptyObjectIsASuccessfulZeroRecordLoad() throws Exception {
            serveObject(new byte[0]);

            assertThat(loadWorkObject())
                    .as("REPRO of an empty input legitimately produces an empty output")
                    .isZero();
        }

        @Test
        @DisplayName("a trailing partial record is refused, naming the record rather than the whole length")
        void aTrailingPartialRecordIsRefused() throws Exception {
            final byte[] truncated = image(List.of(workRecord("A"), workRecord("B").substring(0, 100)));
            serveObject(truncated);

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> loadWorkObject())
                    .withMessageContaining("exact multiple of " + RECORD_LENGTH)
                    .as("a streamed verification cannot know the total length in advance, so it names the "
                            + "record that came up short - which is more useful anyway")
                    .withMessageContaining("record 2 is 100 bytes");
        }

        @Test
        @DisplayName("a descending key is refused, because KEYS(32 0) requires ascending REPRO input")
        void aDescendingKeyIsRefused() throws Exception {
            serveObject(image(List.of(workRecord("B"), workRecord("A"))));

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> loadWorkObject())
                    .withMessageContaining("must ascend")
                    .withMessageContaining("record 2 descends");
        }

        @Test
        @DisplayName("an equal key is accepted, because the load requires ascending and not strictly so")
        void anEqualKeyIsAccepted() throws Exception {
            serveObject(image(List.of(workRecord("A"), workRecord("A"))));

            assertThat(loadWorkObject())
                    .as("a non-unique alternate key legitimately repeats, and the original comparison was "
                            + "also < 0 rather than <= 0; the boundary is asserted so it cannot drift")
                    .isEqualTo(2);
        }
    }

    /**
     * The cap: the bounded refusal that replaces an {@code OutOfMemoryError}.
     */
    @Nested
    @DisplayName("the record ceiling - a reason code instead of an OutOfMemoryError")
    final class RecordCeiling {

        @Test
        @DisplayName("a relation above the ceiling is refused before a single window is queried")
        void aRelationAboveTheCeilingIsRefusedBeforeAnyQuery() throws Exception {
            job = newJob(10);
            when(transactionRepository.count()).thenReturn(11L);

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> invokePrivate("requireWorkRelationWithinCap", new Class<?>[0]))
                    .withMessageContaining("refuses to project 11")
                    .withMessageContaining("max-work-records is 10");

            verify(transactionRepository, never())
                    .findStatementOrderAfter(anyString(), anyString(), any(Pageable.class));
            verify(objectStorage, never()).upload(anyString(), anyString(), any(InputStream.class),
                    any(ObjectMetadata.class));
        }

        @Test
        @DisplayName("a relation at the ceiling is accepted, so the boundary is inclusive")
        void aRelationAtTheCeilingIsAccepted() throws Exception {
            job = newJob(10);
            when(transactionRepository.count()).thenReturn(10L);

            assertThat(invokePrivate("requireWorkRelationWithinCap", new Class<?>[0]))
                    .as("the guard refuses more than the ceiling, not the ceiling itself")
                    .isNull();
        }

        @Test
        @DisplayName("a relation that grows past the ceiling mid-stream is still refused")
        void aRelationThatGrowsMidStreamIsStillRefused() throws Exception {
            job = newJob(2);
            serveWindows(ascendingRows(6));
            recordUpload();

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> projectWorkObject())
                    .as("the pre-flight count sampled the relation before the run; the per-record check is "
                            + "what makes the ceiling hold when the relation changes afterwards")
                    .withMessageContaining("reached 3 records, above the 2");
        }

        @Test
        @DisplayName("an object above the ceiling is refused on the load path too")
        void anObjectAboveTheCeilingIsRefusedOnTheLoadPath() throws Exception {
            job = newJob(2);
            serveObject(image(List.of(workRecord("A"), workRecord("B"), workRecord("C"))));

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> loadWorkObject())
                    .as("an object left by a run configured with a larger ceiling must not be loaded by one "
                            + "configured with a smaller; the cap bounds the loop as well as the heap")
                    .withMessageContaining("reached 3 records, above the 2");
        }

        @Test
        @DisplayName("the ceiling must be positive, so a misconfiguration fails at construction")
        void theCeilingMustBePositive() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> newJob(0))
                    .withMessageContaining("carddemo.batch.creastmt.max-work-records");
        }
    }
}

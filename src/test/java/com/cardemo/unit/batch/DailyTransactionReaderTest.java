/*
 * ******************************************************************
 * Program     : DailyTransactionReaderTest.java
 * Application : CardDemo
 * Type        : Java unit test (JUnit 5)
 * Function    : Verifies the sequential read contract of DailyTransactionReader,
 *               the replacement for the DALYTRAN reads of
 *               app/cbl/CBTRN02C.cbl. Asserts that the file is opened before
 *               the loop body runs, that the scan advances one slice at a time
 *               through the ordered finder and never through a bare findAll,
 *               that exhaustion ends the read rather than failing it, that the
 *               restart cursor survives an update and repositions an open, and
 *               that an open failure abends with the source's own
 *               'ERROR OPENING DALYTRAN' text rather than being swallowed.
 * Source      : app/cbl/CBTRN02C.cbl (731 lines, 0000-DALYTRAN-OPEN and
 *               1000-DALYTRAN-GET-NEXT), app/cpy/CVTRA06Y.cpy (350 B),
 *               app/jcl/POSTTRAN.jcl (DALYTRAN DD) @ 7756d89
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
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ******************************************************************
 */
package com.cardemo.unit.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cardemo.batch.readers.DailyTransactionReader;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.model.entity.DailyTransaction;
import com.cardemo.repository.DailyTransactionRepository;
import com.cardemo.service.shared.FileStatusMapper;
import io.awspring.cloud.s3.S3Operations;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.SliceImpl;

/**
 * Unit tests for {@link DailyTransactionReader} in its default repository-backed mode.
 *
 * <p>Inputs: a mocked {@link DailyTransactionRepository}, a mocked object store that must stay untouched in
 * this mode, and a real {@link FileStatusMapper} because the status vocabulary is the contract under test
 * rather than a collaborator to stub. Outputs: the drained record sequence and the asserted execution context.
 * Side effects: none. Error modes: a read before an open, a failed open, and every rejected construction
 * argument.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
@DisplayName("DailyTransactionReader: CBTRN02C's sequential read of DALYTRAN")
class DailyTransactionReaderTest {

    /** A slice size small enough that several slices are needed, so the paging is genuinely exercised. */
    private static final int PAGE_SIZE = 2;

    /** The default source token; the reader reads staged rows rather than fixed-width images. */
    private static final String REPOSITORY_SOURCE = "repository";

    /** The alternative source token, which decodes 350-byte images from the input object. */
    private static final String FIXED_WIDTH_SOURCE = "fixed-width";

    /** No bucket is needed in repository mode, which is exactly what one assertion below proves. */
    private static final String NO_BUCKET = "";

    /** The default object key, retained so the construction assertions do not depend on the default. */
    private static final String OBJECT_KEY = "dalytran/dailytran.txt";

    /** The execution-context entry carrying the number of records already consumed. */
    private static final String CONTEXT_KEY_RECORDS_READ = "DailyTransactionReader.recordsRead";

    /** The logical file name the abend text and the diagnostics must name. */
    private static final String LOGICAL_FILE = "DALYTRAN";

    /** The open-failure caption, transcribed from the source's own {@code DISPLAY}. */
    private static final String ERROR_OPENING = "ERROR OPENING DALYTRAN";

    /** A 26-character timestamp in the batch producer's shape: millisecond precision then four zeros. */
    private static final String TIMESTAMP = "2022-06-10-19.27.53.120000";

    /** The staged daily-transaction relation. */
    @Mock
    private DailyTransactionRepository repository;

    /** The object store, which repository mode must never reach. */
    @Mock
    private S3Operations objectStorage;

    /** Real rather than mocked: the status vocabulary is part of the contract being asserted. */
    private FileStatusMapper fileStatusMapper;

    /** Prepares the real status mapper. */
    @BeforeEach
    void prepare() {
        this.fileStatusMapper = new FileStatusMapper();
    }

    /**
     * Builds a reader in repository mode.
     *
     * @return the reader under test
     */
    private DailyTransactionReader reader() {
        return new DailyTransactionReader(this.repository, this.objectStorage, this.fileStatusMapper,
                REPOSITORY_SOURCE, PAGE_SIZE, NO_BUCKET, OBJECT_KEY);
    }

    /**
     * Builds one staged row.
     *
     * @param ordinal the one-based ordinal, which becomes the identifier
     * @return the entity
     */
    private static DailyTransaction row(final int ordinal) {
        return new DailyTransaction(Long.valueOf(ordinal),
                String.format(Locale.ROOT, "%016d", Integer.valueOf(ordinal)), "01", Integer.valueOf(1),
                "System", "STAGED PROBE", new BigDecimal("10.00"), Long.valueOf(123456789L),
                "PROBE MERCHANT", "ATLANTA", "30303", "0500024453765740", TIMESTAMP, TIMESTAMP);
    }

    /**
     * Stubs the keyset finder to serve the supplied rows one slice at a time.
     *
     * <p>The relation is walked by <em>seek</em> rather than by offset: each fetch asks for the rows whose
     * ingest ordinal is greater than the highest one already emitted. The stub therefore answers on the
     * position argument rather than on invocation order, which is what a keyset query actually does - a reader
     * that failed to advance its cursor would be served the same slice again instead of silently receiving the
     * next one. Row {@code n} of this fixture carries ordinal {@code n}, so the positions are
     * {@code 0}, {@code PAGE_SIZE}, {@code 2 * PAGE_SIZE} and so on.
     *
     * <p>Stubbed leniently on purpose. This helper describes the whole relation, and a test that restarts
     * from a checkpoint or stops part way through legitimately fetches only some of the slices. Strict
     * stubbing would report the unfetched slices as unnecessary, which would be reporting the very behaviour
     * under test as a defect.
     *
     * @param rows every row the relation holds, in ingestion order
     */
    private void stubSlices(final List<DailyTransaction> rows) {
        lenient().when(this.repository.findByIngestSequenceGreaterThanOrderByIngestSequenceAsc(
                        anyLong(), any(Pageable.class)))
                .thenAnswer(invocation -> {
                    final long after = invocation.getArgument(0, Long.class).longValue();
                    final Pageable request = invocation.getArgument(1, Pageable.class);
                    final List<DailyTransaction> beyond = new ArrayList<>();
                    for (final DailyTransaction candidate : rows) {
                        if (candidate.getIngestSequence().longValue() > after) {
                            beyond.add(candidate);
                        }
                    }
                    final int size = Math.min(request.getPageSize(), beyond.size());
                    return new SliceImpl<>(new ArrayList<>(beyond.subList(0, size)), request,
                            size < beyond.size());
                });
    }

    /**
     * Asserts how many times the keyset finder was asked for the rows past {@code after}.
     *
     * <p>The count is stated rather than assumed because the reader seeks past the same ordinal twice at the
     * head of a cold run: {@code OPEN INPUT} establishes reachability with a bounded one-row seek from the
     * cursor, and the first refill then seeks from that same cursor for a full slice. The overlap is
     * deliberate - the probe asks "is anything left" from wherever the reader actually stands, so a resumed
     * run does not re-enter the index at the head of a relation it has no further interest in.
     *
     * @param after the ordinal the fetch seeks past
     * @param expected how many fetches must have sought past it
     */
    private void verifySoughtPast(final long after, final int expected) {
        verify(this.repository, times(expected))
                .findByIngestSequenceGreaterThanOrderByIngestSequenceAsc(eq(after), any(Pageable.class));
    }

    /**
     * Drains a reader to exhaustion.
     *
     * @param reader the reader to drain
     * @return every record it produced, in order
     */
    private static List<DailyTransaction> drain(final DailyTransactionReader reader) {
        final List<DailyTransaction> drained = new ArrayList<>();
        DailyTransaction next = reader.read();
        while (next != null) {
            drained.add(next);
            next = reader.read();
        }
        return drained;
    }

    /**
     * Construction: every argument that cannot yield a working reader is refused at construction.
     */
    @Nested
    @DisplayName("1. construction refuses every argument that cannot yield a working reader")
    class ConstructionGuards {

        @Test
        @DisplayName("an absent collaborator is refused rather than deferred to the first read")
        void anAbsentCollaboratorIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new DailyTransactionReader(null, objectStorage, fileStatusMapper,
                            REPOSITORY_SOURCE, PAGE_SIZE, NO_BUCKET, OBJECT_KEY))
                    .withMessageContaining("dailyTransactionRepository");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new DailyTransactionReader(repository, null, fileStatusMapper,
                            REPOSITORY_SOURCE, PAGE_SIZE, NO_BUCKET, OBJECT_KEY))
                    .withMessageContaining("objectStorage");
            assertThatExceptionOfType(NullPointerException.class)
                    .as("the mapper is what turns a file status into an outcome, so a reader without one "
                            + "would treat every status as success")
                    .isThrownBy(() -> new DailyTransactionReader(repository, objectStorage, null,
                            REPOSITORY_SOURCE, PAGE_SIZE, NO_BUCKET, OBJECT_KEY))
                    .withMessageContaining("fileStatusMapper");
        }

        @ParameterizedTest
        @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
        @DisplayName("a non-positive slice size is refused, because it cannot advance the scan")
        void aNonPositiveSliceSizeIsRefused(final int pageSize) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a slice of %d rows would fetch for ever without ever reaching the end of the file",
                            Integer.valueOf(pageSize))
                    .isThrownBy(() -> new DailyTransactionReader(repository, objectStorage, fileStatusMapper,
                            REPOSITORY_SOURCE, pageSize, NO_BUCKET, OBJECT_KEY))
                    .withMessageContaining(LOGICAL_FILE);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   ", "database", "s3", "fixedwidth"})
        @DisplayName("a source token naming neither mode is refused, rather than silently defaulting")
        void anUnknownSourceTokenIsRefused(final String source) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("silently defaulting an unrecognised token would read the wrong input entirely, and "
                            + "the run would look successful while posting nothing. Token was '%s'", source)
                    .isThrownBy(() -> new DailyTransactionReader(repository, objectStorage, fileStatusMapper,
                            source, PAGE_SIZE, NO_BUCKET, OBJECT_KEY))
                    .withMessageContaining("repository");
        }

        @Test
        @DisplayName("fixed-width mode requires a bucket, and repository mode does not")
        void fixedWidthModeRequiresABucket() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("decoding 350-byte images means reading an object, and an object needs a bucket. "
                            + "Deferring this to the first read would fail the step rather than the wiring")
                    .isThrownBy(() -> new DailyTransactionReader(repository, objectStorage, fileStatusMapper,
                            FIXED_WIDTH_SOURCE, PAGE_SIZE, NO_BUCKET, OBJECT_KEY));

            assertThat(reader())
                    .as("repository mode reads no object at all, so the same empty bucket is legitimate "
                            + "there - which is why the requirement is conditional rather than absolute")
                    .isNotNull();
        }

        @Test
        @DisplayName("the published default slice size is positive, so the shipped configuration works")
        void theDefaultSliceSizeIsPositive() {
            assertThat(DailyTransactionReader.DEFAULT_PAGE_SIZE)
                    .as("the default is what the shipped configuration uses when the key is absent")
                    .isPositive();
            assertThat(DailyTransactionReader.DEFAULT_OBJECT_KEY)
                    .as("the ASCII fixture is named dailytran.txt in full, even though the mainframe dataset "
                            + "and DD name are DALYTRAN; a reader keyed on the abbreviated spelling would "
                            + "find nothing")
                    .isEqualTo(OBJECT_KEY);
        }
    }

    /**
     * The read lifecycle: open before read, one slice at a time, and exhaustion is not a failure.
     */
    @Nested
    @DisplayName("2. the scan advances one slice at a time and ends on exhaustion")
    class ReadLifecycle {

        @Test
        @DisplayName("read before open is refused, because the source opens the file before the loop body")
        void readBeforeOpenIsRefused() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .as("app/cbl/CBTRN02C.cbl performs 0000-DALYTRAN-OPEN before the mainline loop, so a "
                            + "read on an unopened file is a wiring error rather than an empty file")
                    .isThrownBy(() -> reader().read())
                    .withMessageContaining("0000-DALYTRAN-OPEN");
            verifyNoInteractions(repository, objectStorage);
        }

        @Test
        @DisplayName("every staged row is produced once, in ingestion order, across several slices")
        void everyStagedRowIsProducedOnceInOrder() {
            final List<DailyTransaction> staged = List.of(row(1), row(2), row(3), row(4), row(5));
            lenient().when(repository.count()).thenReturn(Long.valueOf(staged.size()));
            stubSlices(staged);

            final DailyTransactionReader reader = reader();
            reader.open(new ExecutionContext());
            final List<DailyTransaction> drained = drain(reader);

            assertThat(drained)
                    .as("five rows at a slice size of %d needs three fetches, and every row must appear "
                            + "exactly once and in ingestion order", Integer.valueOf(PAGE_SIZE))
                    .containsExactlyElementsOf(staged);
            assertThat(reader.getRecordsRead())
                    .as("the counter is what the end-of-run DISPLAY of app/cbl/CBTRN02C.cbl reports")
                    .isEqualTo(staged.size());

            // Three refills, each seeking past the highest ordinal the previous one emitted. Asserting the
            // positions rather than the call count is what distinguishes a seek from a walk-and-discard: an
            // offset scan would ask for page 1 and page 2 and re-read rows it had already emitted.
            // The open probe and the first refill both seek past zero; the two later refills seek past the
            // highest ordinal each previous slice emitted. Asserting the positions rather than a call count is
            // what distinguishes a seek from a walk-and-discard: an offset scan would ask for page 1 and page
            // 2 and re-read rows it had already emitted.
            verifySoughtPast(0L, 2);
            verifySoughtPast(PAGE_SIZE, 1);
            verifySoughtPast(2L * PAGE_SIZE, 1);
            verifyNoInteractions(objectStorage);
        }

        @Test
        @DisplayName("an exhausted relation ends the read rather than failing it, and stays exhausted")
        void anExhaustedRelationEndsTheRead() {
            lenient().when(repository.count()).thenReturn(Long.valueOf(0L));
            stubSlices(List.of());

            final DailyTransactionReader reader = reader();
            reader.open(new ExecutionContext());

            assertThat(reader.read())
                    .as("an empty DALYTRAN is a legitimate day with nothing to post, so the first read "
                            + "reports end of file rather than raising")
                    .isNull();
            assertThat(reader.read())
                    .as("and a further read stays at end of file rather than re-fetching, which is what "
                            + "keeps a chunk-oriented step from looping")
                    .isNull();
            assertThat(reader.getRecordsRead()).isZero();
            verifySoughtPast(0L, 2);
            verify(repository, never()).findByIngestSequenceGreaterThanOrderByIngestSequenceAsc(
                    eq(Long.valueOf(PAGE_SIZE).longValue()), any(Pageable.class));
        }

        @Test
        @DisplayName("close after a drained read leaves the reader closed rather than mid-scan")
        void closeAfterADrainedReadIsClean() {
            final List<DailyTransaction> staged = List.of(row(1));
            lenient().when(repository.count()).thenReturn(Long.valueOf(staged.size()));
            stubSlices(staged);

            final DailyTransactionReader reader = reader();
            reader.open(new ExecutionContext());
            drain(reader);
            reader.close();

            assertThatExceptionOfType(IllegalStateException.class)
                    .as("a closed reader must refuse a read rather than return a stale buffered row, "
                            + "which is the CLOSE of the source's 9000-DALYTRAN-CLOSE")
                    .isThrownBy(reader::read);
        }
    }

    /**
     * The restart cursor: what {@code update} writes, {@code open} must honour.
     */
    @Nested
    @DisplayName("3. the restart cursor survives an update and repositions an open")
    class RestartCursor {

        @Test
        @DisplayName("update publishes the count consumed so far, so a restart knows where it stopped")
        void updatePublishesTheCountConsumedSoFar() {
            final List<DailyTransaction> staged = List.of(row(1), row(2), row(3));
            lenient().when(repository.count()).thenReturn(Long.valueOf(staged.size()));
            stubSlices(staged);

            final DailyTransactionReader reader = reader();
            final ExecutionContext context = new ExecutionContext();
            reader.open(context);
            reader.read();
            reader.read();
            reader.update(context);

            assertThat(context.getLong(CONTEXT_KEY_RECORDS_READ))
                    .as("two records were consumed, so a restart must skip exactly two - a checkpoint that "
                            + "over-counts loses a record and one that under-counts posts it twice")
                    .isEqualTo(2L);
        }

        @Test
        @DisplayName("an open carrying a cursor resumes after the checkpoint rather than from the start")
        void anOpenCarryingACursorResumes() {
            final List<DailyTransaction> staged = List.of(row(1), row(2), row(3), row(4));
            lenient().when(repository.count()).thenReturn(Long.valueOf(staged.size()));
            stubSlices(staged);

            final ExecutionContext context = new ExecutionContext();
            context.putLong(CONTEXT_KEY_RECORDS_READ, 3L);

            final DailyTransactionReader reader = reader();
            reader.open(context);

            assertThat(drain(reader))
                    .as("three records were already posted before the restart, so only the fourth remains. "
                            + "Re-reading from the start would post the first three a second time")
                    .containsExactly(staged.get(3));
        }

        @Test
        @DisplayName("a re-open without a cursor restarts from the beginning and resets the counter")
        void aReopenWithoutACursorRestartsFromTheBeginning() {
            final List<DailyTransaction> staged = List.of(row(1), row(2));
            lenient().when(repository.count()).thenReturn(Long.valueOf(staged.size()));
            stubSlices(staged);

            final DailyTransactionReader reader = reader();
            reader.open(new ExecutionContext());
            drain(reader);
            assertThat(reader.getRecordsRead()).isEqualTo(2L);

            reader.open(new ExecutionContext());

            assertThat(reader.getRecordsRead())
                    .as("a fresh open resets the counter, so a second execution's DISPLAY cannot inherit "
                            + "the first execution's total")
                    .isZero();
            assertThat(drain(reader))
                    .as("and it rewinds, so the whole file is read again")
                    .containsExactlyElementsOf(staged);
        }

        @Test
        @DisplayName("a null execution context is tolerated, so the reader can be driven directly")
        void aNullExecutionContextIsTolerated() {
            lenient().when(repository.count()).thenReturn(Long.valueOf(0L));
            stubSlices(List.of());

            final DailyTransactionReader reader = reader();

            reader.open(null);

            assertThat(reader.read())
                    .as("a test or a diagnostic harness may drive the reader without a step, and refusing a "
                            + "null context would make that impossible for no gain")
                    .isNull();
        }
    }

    /**
     * The failure path: an open failure abends with the source's own caption.
     */
    @Nested
    @DisplayName("4. an open failure abends with the source's own caption rather than being swallowed")
    class FailurePath {

        @Test
        @DisplayName("a failed row-count probe abends, naming the file, the operation and the status")
        void aFailedProbeAbends() {
            // OPEN INPUT establishes reachability with a bounded one-row seek from the reader's own cursor,
            // not with an exact row count - so the probe that can fail is this finder.
            when(repository.findByIngestSequenceGreaterThanOrderByIngestSequenceAsc(
                    anyLong(), any(Pageable.class)))
                    .thenThrow(new IllegalStateException("relation unavailable"));

            final DailyTransactionReader reader = reader();

            assertThatExceptionOfType(FatalProcessingException.class)
                    .as("the source's guard displays ERROR OPENING DALYTRAN and then abends; swallowing the "
                            + "failure would post nothing and report success")
                    .isThrownBy(() -> reader.open(new ExecutionContext()))
                    .satisfies(abend -> {
                        assertThat(abend.getAbendReason()).isEqualTo(ERROR_OPENING);
                        assertThat(abend.getAbendCode())
                                .as("the batch abend code is the corpus-wide one for a batch program")
                                .isEqualTo(Integer.toString(FatalProcessingException.BATCH_ABEND_CODE));
                        assertThat(abend.getMessage())
                                .as("the message must carry the logical file and the operation so an "
                                        + "operator need not guess which of the six datasets ended the run")
                                .contains(LOGICAL_FILE)
                                .contains("OPEN");
                        assertThat(abend.getCause())
                                .as("the root cause is preserved rather than replaced")
                                .hasMessage("relation unavailable");
                    });
        }

        @Test
        @DisplayName("a failed open leaves the reader closed, so a subsequent read reports the wiring error")
        void aFailedOpenLeavesTheReaderClosed() {
            // OPEN INPUT establishes reachability with a bounded one-row seek from the reader's own cursor,
            // not with an exact row count - so the probe that can fail is this finder.
            when(repository.findByIngestSequenceGreaterThanOrderByIngestSequenceAsc(
                    anyLong(), any(Pageable.class)))
                    .thenThrow(new IllegalStateException("relation unavailable"));

            final DailyTransactionReader reader = reader();
            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> reader.open(new ExecutionContext()));

            assertThatExceptionOfType(IllegalStateException.class)
                    .as("an abended open must not leave a half-open reader that quietly returns null, "
                            + "because a step would then complete having read nothing")
                    .isThrownBy(reader::read);
            verify(objectStorage, never()).objectExists(any(), any());
        }
    }
}

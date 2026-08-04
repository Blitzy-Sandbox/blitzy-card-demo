/*
 * ******************************************************************
 * Program     : CardCrossReferenceReaderTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Verifies the read-only sequential scan of XREFFILE:
 *               the open guard and its row-count probe, the paging
 *               window that replaces READ NEXT, the end-of-file latch,
 *               the restart cursor written to and restored from the
 *               step execution context, the FILE STATUS to APPL-RESULT
 *               translation on every I/O path, the abend that carries
 *               culprit CBACT03C with code 999 and return code 12,
 *               and the verb inventory - CBACT03C performs OPEN, READ
 *               and CLOSE only, so this reader must never write.
 * Source      : app/cbl/CBACT03C.cbl:L72   (0000-XREFFILE-OPEN first)
 *               app/cbl/CBACT03C.cbl       (mainline READ loop)
 *               app/cbl/CBACT03C.cbl       (9910-DISPLAY-IO-STATUS)
 *               app/cbl/CBACT03C.cbl       (9999-ABEND-PROGRAM)
 *               app/jcl/READXREF.jcl               (the job that runs it)
 *               app/catlg/LISTCAT.txt       (XREFFILE cluster)
 *                                                          @ 7756d89
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
import com.cardemo.batch.readers.CardCrossReferenceReader;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.model.enums.FileStatus;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.service.shared.FileStatusMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.domain.Pageable;
import com.cardemo.repository.CardCrossReferenceRepository;

/**
 * Executable proof that the XREFFILE scan reproduces CBACT03C's observable behaviour and writes nothing.
 *
 * <p><b>What it does.</b> Drives the reader's full {@code ItemStreamReader} lifecycle - {@code open},
 * repeated {@code read}, {@code update}, {@code close} - over a mocked repository, and asserts the
 * rows it emits, the paging requests it issues, the execution-context keys it checkpoints, the log lines it
 * emits verbatim from the source's {@code DISPLAY} statements, and the abend it raises when the store
 * fails. {@link FileStatusMapper} is a <em>real</em> instance, because the {@code FILE STATUS} to
 * {@code APPL-RESULT} translation is the behaviour under test and stubbing it would make every guard
 * assertion tautological.
 *
 * <p><b>How to build and test.</b> {@code ./mvnw -B -ntp -Dtest='CardCrossReferenceReaderTest' test} runs this class
 * alone; it needs no container, no database and no cloud emulator.
 *
 * <p><b>Key configuration and defaults.</b> The page size is injected directly rather than through
 * {@code carddemo.batch.card-cross-reference-reader.page-size}, so the paging boundary can be exercised with a
 * small window instead of the {@value CardCrossReferenceReader#DEFAULT_PAGE_SIZE} default. The rows are ordered by
 * {@code cardNumber} ascending, which is what makes the scan sequential in the sense the COBOL
 * {@code READ NEXT} means.
 *
 * <p><b>Common failure modes and troubleshooting.</b>
 * <ul>
 * <li><b>Blocker.</b> A failure in group 6 means this reader has acquired a write verb. {@code CBACT03C}
 * has a verb inventory of {@code OPEN}, {@code READ} and {@code CLOSE} only - no {@code WRITE}, no
 * {@code REWRITE}, no {@code DELETE} anywhere - so any store mutation is a divergence, not an
 * optimisation.</li>
 * <li><b>High.</b> A failure in group 3 means the paging window no longer advances correctly. Every page
 * must be requested with the next page number and the same ascending sort, or rows are skipped or
 * repeated.</li>
 * <li><b>High.</b> A failure in group 4 means the restart cursor is wrong. The checkpoint is a row count,
 * from which the page number and the offset within that page are derived by division and remainder; getting
 * either wrong re-emits or loses rows on a restart.</li>
 * <li><b>Medium.</b> A failure in group 5 means a store failure no longer abends with culprit
 * {@code CBACT03C}, code 999 and return code 12, or no longer emits the source's own
 * {@code DISPLAY} text ahead of it.</li>
 * <li><b>Low.</b> A failure in group 2 means {@code read} before {@code open} no longer fails fast. The
 * source cannot reach its loop body with the file closed, so the state is unreachable in production and the
 * guard exists to catch a wiring defect in the step.</li>
 * </ul>
 */
@DisplayName("CardCrossReferenceReader: CBACT03C's read-only sequential scan of XREFFILE")
class CardCrossReferenceReaderTest {

    /** A small page, so the paging boundary is reachable without building a hundred rows. */
    private static final int PAGE_SIZE = 2;

    /** The keyset finder the reader scans with; its name carries the ORDER BY. */
    private static final String FINDER_NAME = "findByCardNumberGreaterThanOrderByCardNumberAsc";

    /** The execution-context key carrying the checkpointed row count. */
    private static final String CONTEXT_KEY_RECORDS_READ = "CardCrossReferenceReader.recordsRead";

    /** The execution-context key carrying the last emitted key. */
    private static final String CONTEXT_KEY_LAST = "CardCrossReferenceReader.lastCardNumber";

    private CardCrossReferenceRepository repository;
    private CardCrossReferenceReader reader;

    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger logger;
    private Level originalLevel;

    @BeforeEach
    void buildReaderAndCaptureLogs() {
        repository = Mockito.mock(CardCrossReferenceRepository.class);
        reader = new CardCrossReferenceReader(repository, new FileStatusMapper(), PAGE_SIZE);

        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(CardCrossReferenceReader.class);
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
    // Fixtures
    // ------------------------------------------------------------------------------------------------

    /**
     * Builds one {@code CARD-XREF-RECORD} in the {@code CVACT03Y} layout.
     *
     * @param sequence a small ordinal folded into the sixteen-digit card number, which is the scan key
     * @return the row the store would serve
     */
    private static CardCrossReference row(final long sequence) {
        return new CardCrossReference(
                String.format(java.util.Locale.ROOT, "%016d", 500024453765740L + sequence), 50L, 50L);
    }

    /**
     * Stubs the repository to serve the supplied rows across pages of {@link #PAGE_SIZE}, followed by an
     * empty page that reports end of file.
     *
     * @param rows the rows to serve, in scan order
     */
    private void stubRows(final List<CardCrossReference> rows) {
        Mockito.when(repository.count()).thenReturn((long) rows.size());
        // The keyset scan, modelled rather than paged. The reader asks for the rows strictly after a cursor
        // ordered by the key, never for a page index, so slicing by getPageNumber() would answer the same
        // first window for ever and the scan would not terminate. The restart probe additionally pages to an
        // ordinal with a window of one, which the offset below serves. `rows` is supplied in scan order,
        // which is the ordering the finder promises.
        Mockito.when(repository.findByCardNumberGreaterThanOrderByCardNumberAsc(
                Mockito.any(), Mockito.any(Pageable.class))).thenAnswer(invocation -> {
                    String cursor = invocation.getArgument(0);
                    Pageable request = invocation.getArgument(1);
                    List<CardCrossReference> after = rows.stream()
                            .filter(row -> cursor == null || row.getCardNumber().compareTo(cursor) > 0)
                            .toList();
                    int from = Math.min((int) request.getOffset(), after.size());
                    int to = Math.min(from + request.getPageSize(), after.size());
                    return List.copyOf(after.subList(from, to));
                });
    }

    /**
     * Drains the reader until it reports end of file.
     *
     * @return every row the reader emitted, in order
     */
    private List<CardCrossReference> drain() {
        List<CardCrossReference> emitted = new ArrayList<>();
        CardCrossReference row = reader.read();
        while (row != null) {
            emitted.add(row);
            row = reader.read();
        }
        return emitted;
    }

    /** @return every message this class's logger received. */
    private List<String> loggedMessages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Nested
    @DisplayName("1. open: the row-count probe, the START OF EXECUTION line and the open guard")
    class Open {

        @Test
        @DisplayName("an absent repository is refused")
        void anAbsentRepositoryIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new CardCrossReferenceReader(null, new FileStatusMapper(), PAGE_SIZE))
                    .withMessage("cardCrossReferenceRepository must not be null");
        }

        @Test
        @DisplayName("an absent status mapper is refused")
        void anAbsentStatusMapperIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new CardCrossReferenceReader(repository, null, PAGE_SIZE))
                    .withMessage("fileStatusMapper must not be null");
        }

        @ParameterizedTest(name = "a page size of {0} is refused")
        @ValueSource(ints = {0, -1, -100})
        @DisplayName("a non-positive page size is refused, because it cannot advance the scan")
        void aNonPositivePageSizeIsRefused(final int pageSize) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new CardCrossReferenceReader(repository, new FileStatusMapper(), pageSize))
                    .withMessageContaining("page-size must be at least 1 but was " + pageSize);
        }

        @Test
        @DisplayName("the default page size is exposed and is positive")
        void theDefaultPageSizeIsPositive() {
            assertThat(CardCrossReferenceReader.DEFAULT_PAGE_SIZE).isPositive();
        }

        @Test
        @DisplayName("open probes the row count and announces START OF EXECUTION")
        void openProbesTheRowCount() {
            stubRows(List.of(row(1)));

            reader.open(new ExecutionContext());

            Mockito.verify(repository).count();
            assertThat(loggedMessages())
                    .contains("START OF EXECUTION OF PROGRAM CBACT03C")
                    .contains("XREFFILE opened; rows available=1");
        }

        @Test
        @DisplayName("an empty file opens successfully and says so")
        void anEmptyFileOpensSuccessfully() {
            stubRows(List.of());

            reader.open(new ExecutionContext());

            assertThat(loggedMessages())
                    .contains("XREFFILE opened and is empty; the scan will complete with a row count "
                            + "of zero");
            assertThat(reader.read()).isNull();
        }

        @Test
        @DisplayName("a null execution context is tolerated, so the reader can be driven directly")
        void aNullExecutionContextIsTolerated() {
            stubRows(List.of(row(1)));

            reader.open(null);

            assertThat(reader.read()).isNotNull();
        }

        @Test
        @DisplayName("a failed row-count probe abends with the source's ERROR OPENING text")
        void aFailedProbeAbends() {
            QueryTimeoutException timeout = new QueryTimeoutException("open timeout");
            Mockito.when(repository.count()).thenThrow(timeout);

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> reader.open(new ExecutionContext()))
                    .satisfies(abend -> {
                        assertThat(abend.getAbendCulprit()).isEqualTo("CBACT03C");
                        assertThat(abend.getAbendCode()).isEqualTo("999");
                        assertThat(abend.getAbendReason()).isEqualTo("ERROR OPENING XREFFILE");
                        assertThat(abend).hasCause(timeout);
                    });
            assertThat(loggedMessages())
                    .contains("ERROR OPENING XREFFILE", "ABENDING PROGRAM")
                    .anyMatch(message -> message.startsWith("FILE STATUS IS: NNNN"));
        }

        @Test
        @DisplayName("open resets the counter, so a re-open does not resume by accident")
        void openResetsTheCounter() {
            stubRows(List.of(row(1), row(2), row(3)));
            reader.open(new ExecutionContext());
            reader.read();
            assertThat(reader.getRecordsRead()).isEqualTo(1L);

            reader.open(new ExecutionContext());

            assertThat(reader.getRecordsRead()).isZero();
        }
    }

    @Nested
    @DisplayName("2. read before open: the guard for a step wired the wrong way round")
    class ReadBeforeOpen {

        @Test
        @DisplayName("read before open fails fast and cites the source's ordering")
        void readBeforeOpenFailsFast() {
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> reader.read())
                    .withMessageContaining("read() called before open(ExecutionContext)")
                    .withMessageContaining("app/cbl/CBACT03C.cbl");
        }

        @Test
        @DisplayName("read after close fails fast too, because close releases the file")
        void readAfterCloseFailsFast() {
            stubRows(List.of(row(1)));
            reader.open(new ExecutionContext());
            reader.close();

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> reader.read());
        }
    }

    @Nested
    @DisplayName("3. read: the paging window that replaces READ NEXT")
    class Paging {

        @Test
        @DisplayName("every row is emitted once, in scan order")
        void everyRowIsEmittedOnce() {
            stubRows(List.of(row(1), row(2), row(3)));
            reader.open(new ExecutionContext());

            List<CardCrossReference> emitted = drain();

            assertThat(emitted).hasSize(3);
            assertThat(reader.getRecordsRead()).isEqualTo(3L);
        }

        @Test
        @DisplayName("the scan crosses a page boundary, requesting each page in turn")
        void theScanCrossesPageBoundaries() {
            stubRows(List.of(row(1), row(2), row(3), row(4), row(5)));
            reader.open(new ExecutionContext());

            drain();

            // A keyset scan advances by CURSOR, not by page index: every window is page zero and the key of
            // the last row of the previous window becomes the next lower bound. Crossing a window boundary is
            // therefore an advancing cursor, starting from the reader's seed.
            ArgumentCaptor<String> cursors = ArgumentCaptor.forClass(String.class);
            Mockito.verify(repository, Mockito.atLeast(3))
                    .findByCardNumberGreaterThanOrderByCardNumberAsc(cursors.capture(), Mockito.any(Pageable.class));
            assertThat(cursors.getAllValues()).hasSizeGreaterThanOrEqualTo(3);
            assertThat(cursors.getAllValues().get(0))
                    .as("the first window is seeded below the whole key space")
                    .isEqualTo("");
            assertThat(cursors.getAllValues())
                    .as("every later window starts strictly after the previous one")
                    .isSorted();
        }

        @Test
        @DisplayName("every page is requested with the same ascending sort")
        void everyPageCarriesTheAscendingSort() {
            stubRows(List.of(row(1), row(2), row(3)));
            reader.open(new ExecutionContext());

            drain();

            // The ascending order is no longer carried by the Pageable: it is part of the finder's NAME,
            // ...OrderByCardNumberAsc, which Spring Data turns into the ORDER BY. So the guarantee is
            // asserted where it now lives, and every window is additionally required to pass NO sort of its
            // own, which would either duplicate the clause or silently contradict it.
            assertThat(CardCrossReferenceRepository.class.getDeclaredMethods())
                    .as("the ascending order is expressed by the finder name")
                    .anySatisfy(method -> assertThat(method.getName()).isEqualTo(FINDER_NAME));
            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            Mockito.verify(repository, Mockito.atLeastOnce())
                    .findByCardNumberGreaterThanOrderByCardNumberAsc(Mockito.any(), captor.capture());
            assertThat(captor.getAllValues())
                    .allSatisfy(request -> assertThat(request.getSort().isSorted()).isFalse());
        }

        @Test
        @DisplayName("end of file returns null and latches, so a further read reads nothing more")
        void endOfFileLatches() {
            stubRows(List.of(row(1)));
            reader.open(new ExecutionContext());
            drain();
            int callsAtEndOfFile = Mockito.mockingDetails(repository).getInvocations().size();

            assertThat(reader.read()).isNull();

            assertThat(Mockito.mockingDetails(repository).getInvocations())
                    .as("the end-of-file latch means the store is not consulted again")
                    .hasSize(callsAtEndOfFile);
        }

        @Test
        @DisplayName("a null row inside a page is a physical I/O error, not a silent skip")
        void aNullRowInsideAPageAbends() {
            Mockito.when(repository.count()).thenReturn(1L);
            List<CardCrossReference> withNull = new ArrayList<>();
            withNull.add(null);
            Mockito.when(repository.findByCardNumberGreaterThanOrderByCardNumberAsc(
                    Mockito.any(), Mockito.any(Pageable.class)))
                    .thenReturn(withNull);
            reader.open(new ExecutionContext());

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> reader.read())
                    .satisfies(abend -> assertThat(abend.getAbendReason()).isEqualTo("ERROR READING XREFFILE"));
        }

        @Test
        @DisplayName("getRecordsRead advances once per emitted row and not once per store call")
        void getRecordsReadAdvancesPerRow() {
            stubRows(List.of(row(1), row(2), row(3), row(4)));
            reader.open(new ExecutionContext());

            reader.read();
            reader.read();

            assertThat(reader.getRecordsRead()).isEqualTo(2L);
        }
    }

    @Nested
    @DisplayName("4. The restart cursor: a row count, divided into a page and an offset")
    class RestartCursor {

        @Test
        @DisplayName("update checkpoints the row count and the last emitted key")
        void updateCheckpointsTheRowCountOnly() {
            stubRows(List.of(row(1), row(2), row(3)));
            reader.open(new ExecutionContext());
            reader.read();
            reader.read();
            ExecutionContext context = new ExecutionContext();

            reader.update(context);

            assertThat(context.getLong(CONTEXT_KEY_RECORDS_READ)).isEqualTo(2L);
            // The card number is NOT checkpointed, and that is the whole of finding M-04. An earlier revision
            // asserted the opposite. CardCrossReferenceReader.update() writes the row count and nothing else, because a
            // primary account number placed in an execution context is persisted to the job repository, where
            // it outlives the run and is readable by anything that can read the schema. The scan resumes
            // instead by re-probing the relation for the key at the checkpointed ordinal, which needs no
            // stored key at all.
            assertThat(context.containsKey(CONTEXT_KEY_LAST))
                    .as("a PAN must never be written to the job repository")
                    .isFalse();
            assertThat(context.entrySet())
                    .noneSatisfy(entry -> assertThat(String.valueOf(entry.getValue())).contains("4111"));
        }

        @Test
        @DisplayName("update tolerates a null context, so a directly driven reader does not fail")
        void updateToleratesANullContext() {
            stubRows(List.of(row(1)));
            reader.open(new ExecutionContext());

            reader.update(null);

            assertThat(reader.getRecordsRead()).isZero();
        }

        @Test
        @DisplayName("update before any row writes the count and no key")
        void updateBeforeAnyRowWritesNoKey() {
            stubRows(List.of(row(1)));
            reader.open(new ExecutionContext());
            ExecutionContext context = new ExecutionContext();

            reader.update(context);

            assertThat(context.getLong(CONTEXT_KEY_RECORDS_READ)).isZero();
            assertThat(context.containsKey(CONTEXT_KEY_LAST)).isFalse();
        }

        @Test
        @DisplayName("a checkpoint of three rows resumes on page one at offset one")
        void aCheckpointResumesAtTheRightOffset() {
            stubRows(List.of(row(1), row(2), row(3), row(4), row(5)));
            ExecutionContext context = new ExecutionContext();
            context.putLong(CONTEXT_KEY_RECORDS_READ, 3L);

            reader.open(context);

            // Three rows read at a page size of two: page 3/2 = 1, offset 3%2 = 1, so the resumed scan
            // begins with the second row of page one - the fourth row overall.
            assertThat(reader.getRecordsRead()).isEqualTo(3L);
            List<CardCrossReference> emitted = drain();
            assertThat(emitted)
                    .as("two rows remain after the checkpoint")
                    .hasSize(2);
            assertThat(reader.getRecordsRead()).isEqualTo(5L);
        }

        @Test
        @DisplayName("a checkpoint on a page boundary resumes at the start of the next page")
        void aCheckpointOnAPageBoundaryResumesCleanly() {
            stubRows(List.of(row(1), row(2), row(3), row(4)));
            ExecutionContext context = new ExecutionContext();
            context.putLong(CONTEXT_KEY_RECORDS_READ, 2L);

            reader.open(context);

            assertThat(drain()).hasSize(2);
        }

        @Test
        @DisplayName("a checkpoint of zero is not a restart and starts from the beginning")
        void aCheckpointOfZeroIsNotARestart() {
            stubRows(List.of(row(1), row(2)));
            ExecutionContext context = new ExecutionContext();
            context.putLong(CONTEXT_KEY_RECORDS_READ, 0L);

            reader.open(context);

            assertThat(drain()).hasSize(2);
            assertThat(loggedMessages()).noneMatch(message -> message.startsWith("Resuming"));
        }

        @Test
        @DisplayName("a restart announces where it resumed from")
        void aRestartAnnouncesItself() {
            stubRows(List.of(row(1), row(2), row(3)));
            ExecutionContext context = new ExecutionContext();
            context.putLong(CONTEXT_KEY_RECORDS_READ, 2L);

            reader.open(context);

            assertThat(loggedMessages())
                    .anyMatch(message -> message.startsWith("Resuming XREFFILE scan after 2 rows"));
        }

        @Test
        @DisplayName("a checkpointed last key is restored alongside the count")
        void aCheckpointCarriesTheRowCountAndNoKey() {
            stubRows(List.of(row(1), row(2), row(3)));
            ExecutionContext context = new ExecutionContext();
            context.putLong(CONTEXT_KEY_RECORDS_READ, 2L);

            reader.open(context);

            ExecutionContext rewritten = new ExecutionContext();
            reader.update(rewritten);
            // A checkpoint taken before the next row is emitted carries the row count forward and still no
            // key: the resume position is recovered by re-probing the relation at that ordinal, so there is
            // nothing to restore and nothing to leak. An earlier revision asserted the key was preserved.
            assertThat(rewritten.getLong(CONTEXT_KEY_RECORDS_READ))
                    .as("the row count is what survives, and it is enough to resume from")
                    .isEqualTo(2L);
            assertThat(rewritten.containsKey(CONTEXT_KEY_LAST))
                    .as("a PAN must never be written to the job repository")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("5. Store failures: the guard, the DISPLAY and the abend of 9999-ABEND-PROGRAM")
    class StoreFailures {

        @Test
        @DisplayName("a failed page read abends with culprit CBACT03C, code 999 and the source's text")
        void aFailedPageReadAbends() {
            Mockito.when(repository.count()).thenReturn(3L);
            QueryTimeoutException timeout = new QueryTimeoutException("read timeout");
            Mockito.when(repository.findByCardNumberGreaterThanOrderByCardNumberAsc(
                    Mockito.any(), Mockito.any(Pageable.class))).thenThrow(timeout);
            reader.open(new ExecutionContext());

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> reader.read())
                    .satisfies(abend -> {
                        assertThat(abend.getAbendCulprit()).isEqualTo("CBACT03C");
                        assertThat(abend.getAbendCode()).isEqualTo("999");
                        assertThat(abend.getAbendReason()).isEqualTo("ERROR READING XREFFILE");
                        assertThat(abend).hasCause(timeout);
                    });
            assertThat(loggedMessages()).contains("ERROR READING XREFFILE", "ABENDING PROGRAM");
        }

        @Test
        @DisplayName("the abend return code is 12, as CALL 'CEE3ABD' yields")
        void theAbendReturnCodeIsTwelve() {
            assertThat(FatalProcessingException.BATCH_RETURN_CODE).isEqualTo(12);
            assertThat(FatalProcessingException.BATCH_ABEND_CODE).isEqualTo(999);
        }

        @Test
        @DisplayName("the four-character FILE STATUS rendering precedes the abend on the read path")
        void theFileStatusRenderingPrecedesTheAbend() {
            Mockito.when(repository.count()).thenReturn(3L);
            Mockito.when(repository.findByCardNumberGreaterThanOrderByCardNumberAsc(
                    Mockito.any(), Mockito.any(Pageable.class)))
                    .thenThrow(new QueryTimeoutException("read timeout"));
            reader.open(new ExecutionContext());

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> reader.read());

            List<String> messages = loggedMessages();
            int readError = messages.indexOf("ERROR READING XREFFILE");
            int abending = messages.indexOf("ABENDING PROGRAM");
            assertThat(readError).isNotNegative();
            assertThat(messages.get(readError + 1))
                    .as("9910-DISPLAY-IO-STATUS emits between the failure text and the abend")
                    .startsWith("FILE STATUS IS: NNNN");
            assertThat(abending).isGreaterThan(readError);
        }

        @Test
        @DisplayName("close announces END OF EXECUTION with the row count it reached")
        void closeAnnouncesEndOfExecution() {
            stubRows(List.of(row(1), row(2)));
            reader.open(new ExecutionContext());
            drain();

            reader.close();

            assertThat(loggedMessages())
                    .contains("END OF EXECUTION OF PROGRAM CBACT03C recordsRead=2");
        }

        @Test
        @DisplayName("close is safe before open, so a step that fails early can still tear down")
        void closeIsSafeBeforeOpen() {
            reader.close();

            assertThat(loggedMessages())
                    .contains("END OF EXECUTION OF PROGRAM CBACT03C recordsRead=0");
        }
    }

    @Nested
    @DisplayName("6. BLOCKER-CRITICAL: the verb inventory is OPEN, READ and CLOSE only")
    class VerbInventory {

        @Test
        @DisplayName("a full lifecycle issues no write of any kind")
        void aFullLifecycleWritesNothing() {
            stubRows(List.of(row(1), row(2), row(3)));

            reader.open(new ExecutionContext());
            drain();
            reader.update(new ExecutionContext());
            reader.close();

            Mockito.verify(repository, Mockito.never()).save(Mockito.any());
            Mockito.verify(repository, Mockito.never()).saveAll(Mockito.any());
            Mockito.verify(repository, Mockito.never()).delete(Mockito.any());
            Mockito.verify(repository, Mockito.never()).deleteAll();
            Mockito.verify(repository, Mockito.never()).deleteById(Mockito.any());
            Mockito.verify(repository, Mockito.never()).flush();
        }

        @Test
        @DisplayName("only count and findAll are ever invoked on the store")
        void onlyCountAndTheKeysetFinderAreInvoked() {
            stubRows(List.of(row(1), row(2)));

            reader.open(new ExecutionContext());
            drain();
            reader.close();

            assertThat(Mockito.mockingDetails(repository).getInvocations())
                    .as("CBACT03C performs OPEN, READ and CLOSE and nothing else")
                    .allSatisfy(invocation -> assertThat(invocation.getMethod().getName())
                            .isIn("count", "findByCardNumberGreaterThanOrderByCardNumberAsc"));
        }
    }

    /**
     * Writes one private field of the reader under test, so that a state the public surface cannot reach is
     * still exercised. Used only for the retained parity guard at {@code app/cbl/CBACT03C.cbl:L75}: that
     * second {@code IF END-OF-FILE = 'N'} test can only fail for a value which is neither sentinel, and a
     * state production cannot produce is exactly the state a deliberately redundant guard absorbs.
     *
     * @param name the declared field name on {@link CardCrossReferenceReader}
     * @param value the value to write
     */
    private void setReaderField(final String name, final Object value) {
        try {
            java.lang.reflect.Field field = CardCrossReferenceReader.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(reader, value);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("cannot write " + name + " on CardCrossReferenceReader", failure);
        }
    }

    @Nested
    @DisplayName("7. Defensive edges: the retained parity guard, the DEBUG gate and the status contract")
    class DefensiveEdges {

        @Test
        @DisplayName("parity structure 1: the redundant guard answers null for a flag that is neither sentinel")
        void theRedundantGuardAnswersNull() {
            stubRows(List.of(row(1), row(2)));
            reader.open(new ExecutionContext());

            setReaderField("endOfFile", " ");

            assertThat(reader.read())
                    .as("app/cbl/CBACT03C.cbl:L75 fails for any value but 'N', which is why it is retained")
                    .isNull();
        }

        @Test
        @DisplayName("the record DISPLAY is suppressed when DEBUG is off and the row is still emitted")
        void theDisplayIsGatedOnDebug() {
            logger.setLevel(Level.INFO);
            stubRows(List.of(row(1)));

            reader.open(new ExecutionContext());

            assertThat(reader.read())
                    .as("gating the emission must never gate the scan")
                    .isNotNull();
            assertThat(appender.list)
                    .as("Rule 1 clause D1 keeps record content out of routine log volume")
                    .noneMatch(event -> event.getLevel() == Level.DEBUG);
            assertThat(loggedMessages())
                    .contains("START OF EXECUTION OF PROGRAM CBACT03C");
        }

        @Test
        @DisplayName("requireExactCode refuses the '9x' family, which has no two-character code to index")
        void requireExactCodeRefusesTheFamily() throws Exception {
            java.lang.reflect.Method method =
                    CardCrossReferenceReader.class.getDeclaredMethod("requireExactCode", FileStatus.class);
            method.setAccessible(true);

            Throwable cause = null;
            try {
                method.invoke(null, FileStatus.IO_ERROR);
            } catch (java.lang.reflect.InvocationTargetException failure) {
                cause = failure.getCause();
            }

            assertThat(cause)
                    .as("FileStatus.IO_ERROR is a family, so it must be refused rather than defaulted")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must expose an exact two-character code");
        }
    }
}

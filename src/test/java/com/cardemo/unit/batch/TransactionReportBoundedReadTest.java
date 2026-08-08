/*
 * ******************************************************************
 * Program     : TransactionReportBoundedReadTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test (bounded-resource contract)
 * Function    : Prove that TRANREPT's sort step derives the filtered,
 *               card-ordered daily generation from the relation in
 *               bounded pages instead of reading the whole TRANSACT.BKUP
 *               generation into the heap, that its exclusive upper bound
 *               is the day after the inclusive end date so the last day
 *               of the period is admitted, and that PRTCATBL's print
 *               step reads its generation one record at a time while
 *               verifying - rather than re-sorting - the composite key
 *               order the unload guarantees.
 * Source      : app/proc/TRANREPT.prc:L35-L53 (STEP05R SORT FIELDS,
 *                 SYMNAMES and the two-sided INCLUDE COND)
 *               app/proc/TRANREPT.prc:L29     (LRECL=350 of the SORTIN)
 *               app/jcl/PRTCATBL.jcl:L43      (STEP10R)
 *               app/jcl/PRTCATBL.jcl:L44-L45  (SORTIN is the generation
 *                 STEP05R has just written)
 *               app/jcl/PRTCATBL.jcl:L47-L50  (SYMNAMES key offsets)
 *               app/jcl/PRTCATBL.jcl:L52      (SORT FIELDS ascending)
 *               app/jcl/PRTCATBL.jcl:L61      (LRECL=40 of the SORTOUT)
 *               app/cpy/CVTRA01Y.cpy          (50-byte TCATBALF layout)
 *               app/cpy/CVTRA05Y.cpy          (350-byte transaction
 *                 layout)
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

import com.cardemo.batch.jobs.TransactionReportJob;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.model.entity.Transaction;
import com.cardemo.model.entity.TransactionCategoryBalance;
import com.cardemo.model.key.TransactionCategoryBalanceId;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.TransactionCategoryBalanceRepository;
import com.cardemo.repository.TransactionCategoryRepository;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.repository.TransactionTypeRepository;
import com.cardemo.service.shared.DateValidationService;
import com.cardemo.service.shared.FileStatusMapper;
import io.awspring.cloud.s3.ObjectMetadata;
import io.awspring.cloud.s3.S3Operations;
import io.awspring.cloud.s3.S3Resource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.SliceImpl;
import org.springframework.transaction.PlatformTransactionManager;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * The bounded-resource contract of the two report-job steps that read a generation.
 *
 * <h2>What this proves, and why the existing suites could not</h2>
 *
 * <p><strong>Finding M-08, severity High.</strong> Neither {@code executeStep05r} nor
 * {@code printCategoryBalances} may call a helper that
 * accumulates an entire generation into a {@code List<byte[]>} - the first over {@code TRANSACT.BKUP}, then
 * filtering it with {@code removeIf} and sorting it in place; the second over the
 * {@code TCATBALF.BKUP} generation, then building the whole report in one {@code StringBuilder}. Both inputs
 * are unbounded - the transaction cluster grows with the business and the category-balance cluster grows with
 * every account, type and category triple - so peak memory would be the whole dataset and a large run would
 * end in an
 * {@code OutOfMemoryError}, which is an abort of the job virtual machine rather than a step failure with a
 * reason code.
 *
 * <p>Neither the processor suite nor the real-infrastructure job suite can catch that, because the functional
 * result is identical either way: the same bytes reach the same key, and the corpus fixture is 300 records so
 * both pass. What separates a bounded implementation from an unbounded one is <em>how much is live at
 * once</em>, which is
 * observable only in the interaction. Every test here therefore asserts on the interaction: how many pages
 * were requested and with what bounds, whether the superseded whole-object read happens at all, and whether
 * the ordering the source declares is verified rather than achieved by making the input resident.
 *
 * <h2>The three properties asserted, and why each is needed</h2>
 *
 * <ol>
 *   <li><strong>The sort step queries the relation in bounded pages and never downloads the generation.</strong>
 *       Pushing {@code SORT FIELDS=(TRAN-CARD-NUM,A)} and the {@code INCLUDE COND} range into the database is
 *       the first remedy the finding names. A page count and the absence of any {@code download} call are what
 *       distinguish it from the resident form, so both are asserted.</li>
 *   <li><strong>The exclusive upper bound admits the whole of the inclusive end date.</strong> The source
 *       predicate is a character comparison on the ten-character processing-date prefix and is inclusive at
 *       both ends; a timestamp is twenty-six characters, so {@code procTs <= '2022-06-30'} would silently drop
 *       every row of the last day. That is the single highest-risk detail of the rewrite, so the derived bound
 *       is asserted directly and a row late on the end date is driven through.</li>
 *   <li><strong>The print step verifies the declared key order instead of re-sorting.</strong> A stream cannot
 *       be sorted without being made resident again, so the order the unload guarantees is checked - and a
 *       descending pair is refused rather than silently repaired, because a silent re-sort would have masked a
 *       producer regression.</li>
 * </ol>
 *
 * <h2>How to run</h2>
 *
 * <p>{@code ./mvnw -B -ntp -Dtest=TransactionReportBoundedReadTest test}. No container, no database and no
 * profile: the job is constructed over mocks and its private step bodies are invoked directly, which is the
 * convention {@code StatementWorkObjectStreamingTest} and {@code InterestCalculationJobTest} established in
 * this package.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
@DisplayName("TRANREPT STEP05R and PRTCATBL STEP10R - bounded reads, derived bounds, verified order")
class TransactionReportBoundedReadTest {

    /** The output bucket every key in this suite lives in. */
    private static final String BUCKET = "carddemo-batch-output";

    /** Rows per page, deliberately far below the fixture size so a page boundary is crossed. */
    private static final int CHUNK = 2;

    /** The concrete {@code SORTOUT} key the sort step writes. */
    private static final String DAILY_KEY = "gdg/transact-daly/generation=0000000000000000007/TRANSACT.DALY";

    /** The concrete {@code TCATBALF.BKUP} generation the print step reads. */
    private static final String BACKUP_KEY = "gdg/tcatbalf-bkup/generation=0000000000000000007/TCATBALF.BKUP";

    /** {@code app/proc/TRANREPT.prc:L29} - {@code RECORDSIZE(350 350)}. */
    private static final int TRANSACTION_RECORD_LENGTH = 350;

    /** {@code app/jcl/PRTCATBL.jcl:L37} - {@code LRECL=50}. */
    private static final int CATEGORY_BALANCE_RECORD_LENGTH = 50;

    /** {@code app/jcl/PRTCATBL.jcl:L61} - {@code LRECL=40}. */
    private static final int CATEGORY_BALANCE_REPORT_LINE_LENGTH = 40;

    /** The inclusive lower bound of the reporting period. */
    private static final String START_DATE = "2022-06-01";

    /** The inclusive upper bound of the reporting period. */
    private static final String END_DATE = "2022-06-30";

    /** The single-byte charset every fixed-width record is encoded in. */
    private static final java.nio.charset.Charset FIXED_WIDTH = StandardCharsets.ISO_8859_1;

    @Mock
    private JobRepository jobRepository;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private CardCrossReferenceRepository cardCrossReferenceRepository;

    @Mock
    private TransactionTypeRepository transactionTypeRepository;

    @Mock
    private TransactionCategoryRepository transactionCategoryRepository;

    @Mock
    private DateValidationService dateValidationService;

    @Mock
    private S3Operations objectStorage;

    @Mock
    private S3Client objectStoreClient;

    @Mock
    private TransactionCategoryBalanceRepository transactionCategoryBalanceRepository;

    /** The job under test, rebuilt for each test with {@link #CHUNK} as its page size. */
    private TransactionReportJob job;

    @BeforeEach
    void setUp() {
        this.job = new TransactionReportJob(jobRepository, transactionManager, transactionRepository,
                cardCrossReferenceRepository, transactionTypeRepository, transactionCategoryRepository,
                dateValidationService, new FileStatusMapper(), objectStorage,
                objectStoreClient,
                "TRANREPT", CHUNK, BUCKET, "gdg/transact-bkup", "gdg/transact-daly", "gdg/tranrept",
                transactionCategoryBalanceRepository, "gdg/tcatbalf-bkup", 10);
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

        final Method method = TransactionReportJob.class.getDeclaredMethod(name, types);
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
     * Streams the filtered, card-ordered daily generation and reports the record count it wrote.
     *
     * <p>Reached by name so that this suite fails if the streamed form is ever replaced by one that collects
     * the generation first. The name is {@code writeSortedGeneration}, which is the streaming helper
     * {@code executeStep05r} delegates SORTOUT production to; it pages the relation and writes each page
     * straight through to the object stream, so nothing but the current page is ever live.
     *
     * @param endDate the inclusive upper bound to drive with
     * @return how many records were written
     * @throws Exception if the method cannot be found or invoked
     */
    private long streamDaily(final String endDate) throws Exception {
        return (long) invokePrivate("writeSortedGeneration",
                new Class<?>[] {String.class, String.class, String.class},
                DAILY_KEY, START_DATE, endDate);
    }

    /**
     * Reads the category-balance generation back and prints it, reporting the line count.
     *
     * @return how many 40-byte lines were written
     * @throws Exception if the method cannot be found or invoked
     */
    private int printCategoryBalances() throws Exception {
        return (int) invokePrivate("printCategoryBalances", new Class<?>[] {String.class}, BACKUP_KEY);
    }

    /**
     * Builds a transaction whose report-relevant key fields are the card number and the identifier.
     *
     * @param cardNumber exactly sixteen characters
     * @param transactionId exactly sixteen characters
     * @param procTs the twenty-six character processing timestamp
     * @return a valid transaction, never {@code null}
     */
    private static Transaction transaction(
            final String cardNumber, final String transactionId, final String procTs) {

        return new Transaction(transactionId, "01", 5, "POS TERM  ", "Regular Sales Draft",
                new BigDecimal("100.00"), 9L, "MERCHANT", "CITY", "12345", cardNumber,
                "2022-06-15-00.00.00.000000", procTs);
    }

    /**
     * Builds {@code count} transactions in ascending card order, all inside the reporting period.
     *
     * @param count how many to build
     * @return the rows in ascending order, never {@code null}
     */
    private static List<Transaction> ascendingRows(final int count) {
        final List<Transaction> rows = new ArrayList<>(count);
        for (int index = 1; index <= count; index++) {
            final String key = String.format(Locale.ROOT, "%016d", Integer.valueOf(index));
            rows.add(transaction(key, key, "2022-06-15-12.00.00.000000"));
        }
        return rows;
    }

    /**
     * Stubs the half-open range finder to serve {@code rows} as {@link #CHUNK}-sized pages.
     *
     * <p>The stub honours the {@link Pageable} it is handed rather than counting invocations, so a caller that
     * failed to advance its page number would loop over the first page instead of silently receiving the next
     * one - which is what makes the page-count assertions attributable.
     *
     * @param rows the rows the relation holds, in the order the query's {@code ORDER BY} produces
     */
    private void servePages(final List<Transaction> rows) {
        when(transactionRepository.findByProcessingTimestampHalfOpenRangeOrderByCardNumberAsc(
                anyString(), anyString(), any(Pageable.class)))
                .thenAnswer(invocation -> {
                    final Pageable pageable = invocation.getArgument(2);
                    final int from = Math.min((int) pageable.getOffset(), rows.size());
                    final int to = Math.min(from + pageable.getPageSize(), rows.size());
                    return new SliceImpl<>(rows.subList(from, to), pageable, to < rows.size());
                });
    }

    /**
     * Stubs the object-store resource for a key so a write is captured rather than transferred.
     *
     * @param key the object key the step will create
     * @return the recorder, never {@code null}
     * @throws IOException never, but declared because the stubbed accessor does
     */
    private UploadRecorder recordUpload(final String key) throws IOException {
        final UploadRecorder recorder = new UploadRecorder();
        final S3Resource resource = Mockito.mock(S3Resource.class);
        Mockito.doNothing().when(resource).setObjectMetadata(any(ObjectMetadata.class));
        Mockito.doReturn(recorder.sink()).when(resource).getOutputStream();
        when(objectStorage.createResource(eq(BUCKET), eq(key))).thenReturn(resource);
        return recorder;
    }

    /**
     * Stubs {@code download} to serve {@code payload} as the category-balance generation's byte stream.
     *
     * @param payload the object image the print path will read
     * @throws IOException never, but declared because the stubbed accessor does
     */
    private void serveBackup(final byte[] payload) throws IOException {
        final S3Resource resource = Mockito.mock(S3Resource.class);
        Mockito.doReturn(new ByteArrayInputStream(payload)).when(resource).getInputStream();
        when(objectStorage.download(BUCKET, BACKUP_KEY)).thenReturn(resource);
    }

    /**
     * Renders one 50-byte {@code TCATBALF} unload record with a chosen composite key.
     *
     * <p>Offsets are the ones {@code app/jcl/PRTCATBL.jcl:L47-L50} declares: the account identifier at 1 for
     * 11, the type code at 12 for 2, the category code at 14 for 4 and the balance at 18 for 11, leaving 22
     * bytes of copybook filler.
     *
     * @param accountId the eleven-digit account identifier
     * @param typeCode the two-character type code
     * @param categoryCode the four-digit category code
     * @return exactly {@value #CATEGORY_BALANCE_RECORD_LENGTH} characters
     */
    // The balance image is eleven characters of zoned decimal whose LAST character carries both the final
    // digit and the sign as an overpunch: 'E' is +5, so "0000001234E" decodes to unscaled 12345, or 123.45 at
    // scale 2. app/data/ASCII/tcatbal.txt uses the same encoding, which is what makes the fixture faithful.
    private static String categoryBalanceRecord(
            final long accountId, final String typeCode, final int categoryCode) {

        final String record = String.format(Locale.ROOT, "%011d", Long.valueOf(accountId))
                + typeCode
                + String.format(Locale.ROOT, "%04d", Integer.valueOf(categoryCode))
                + "0000001234E";
        return record + " ".repeat(CATEGORY_BALANCE_RECORD_LENGTH - record.length());
    }

    /**
     * Concatenates record images into one object body.
     *
     * @param records the record images, each of whatever length the test needs
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
     * A stand-in for the object-storage transfer, accepting bytes once and in order.
     *
     * <p>The step wraps this sink in a {@code BufferedOutputStream}, so write <em>counts</em> are not a proxy
     * for incremental emission here and are deliberately not asserted; the bytes and the close are.
     */
    private static final class UploadRecorder {

        /** Everything the step wrote. */
        private final ByteArrayOutputStream received = new ByteArrayOutputStream();

        /** Whether the step closed the stream, which is what commits the object. */
        private boolean closed;

        /**
         * The stream handed to the step.
         *
         * @return a stream recording every write, never {@code null}
         */
        OutputStream sink() {
            return new OutputStream() {

                @Override
                public void write(final int value) {
                    UploadRecorder.this.received.write(value);
                }

                @Override
                public void write(final byte[] buffer, final int offset, final int length) {
                    UploadRecorder.this.received.write(buffer, offset, length);
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

        boolean closed() {
            return this.closed;
        }
    }

    /**
     * {@code STEP05R}: the filtered, ordered generation is derived from the relation, one page at a time.
     */
    @Nested
    @DisplayName("STEP05R - the daily generation is paged out of the relation, never read out of the backup")
    final class SortStepIsPaged {

        /**
         * Creates the sort-step group.
         *
         * <p>Declared explicitly because JUnit builds one instance per test method and the enclosing instance
         * supplies every collaborator, so the body has nothing to do.
         */
        SortStepIsPaged() {
            // Intentionally empty; the enclosing instance owns every collaborator.
        }

        @Test
        @DisplayName("the superseded whole-generation download never happens, which only the paged form can "
                + "claim")
        void theBackupGenerationIsNeverDownloaded() throws Exception {
            servePages(ascendingRows(5));
            recordUpload(DAILY_KEY);

            assertThat(streamDaily(END_DATE)).isEqualTo(5);

            // The resident form reached the generation through S3Operations.download and decoded every record
            // of it. A single download call here would mean the whole object was still being pulled in, which
            // is the exact allocation finding M-08 names. app/proc/TRANREPT.prc:L36-L37 still requires the
            // SORTIN generation to EXIST - executeStep05r asserts that separately - but the records now come
            // from the relation the generation was mirrored from moments earlier in the same job.
            verify(objectStorage, never()).download(anyString(), anyString());
        }

        @Test
        @DisplayName("pages are requested in bounded windows that advance, so peak memory is one page")
        void pagesAreRequestedInBoundedAdvancingWindows() throws Exception {
            servePages(ascendingRows(5));
            recordUpload(DAILY_KEY);

            assertThat(streamDaily(END_DATE)).isEqualTo(5);

            final ArgumentCaptor<Pageable> pages = ArgumentCaptor.forClass(Pageable.class);
            verify(transactionRepository, Mockito.times(3))
                    .findByProcessingTimestampHalfOpenRangeOrderByCardNumberAsc(
                            anyString(), anyString(), pages.capture());
            assertThat(pages.getAllValues())
                    .as("five rows at two per page is three requests, each bounded and each advancing; an "
                            + "unbounded Pageable or a repeated page number would be the resident form again")
                    .containsExactly(PageRequest.of(0, CHUNK), PageRequest.of(1, CHUNK),
                            PageRequest.of(2, CHUNK));
        }

        @Test
        @DisplayName("every emitted record keeps its 350-byte geometry and the query's card-ascending order")
        void everyRecordKeepsItsGeometryAndOrder() throws Exception {
            servePages(ascendingRows(4));
            final UploadRecorder recorder = recordUpload(DAILY_KEY);

            assertThat(streamDaily(END_DATE)).isEqualTo(4);

            final byte[] body = recorder.bytes();
            assertThat(body)
                    .as("app/proc/TRANREPT.prc:L29 - RECORDSIZE(350 350), so four records are 1400 bytes")
                    .hasSize(4 * TRANSACTION_RECORD_LENGTH);
            assertThat(recorder.closed())
                    .as("the object is committed by closing the stream, not by an in-memory hand-off")
                    .isTrue();

            final String text = new String(body, FIXED_WIDTH);
            final List<String> cards = new ArrayList<>();
            for (int index = 0; index < 4; index++) {
                final int start = index * TRANSACTION_RECORD_LENGTH;
                // app/proc/TRANREPT.prc:L39 - TRAN-CARD-NUM,263,16, so bytes 263-278 one-based.
                cards.add(text.substring(start + 262, start + 278));
            }
            assertThat(cards)
                    .as("SORT FIELDS=(TRAN-CARD-NUM,A) at app/proc/TRANREPT.prc:L44 becomes the query's own "
                            + "ORDER BY, so the emitted order is the sort's order without a resident sort")
                    .isSorted()
                    .containsExactly("0000000000000001", "0000000000000002", "0000000000000003",
                            "0000000000000004");
        }

        @Test
        @DisplayName("the exclusive upper bound is the DAY AFTER the inclusive end date, so the last day of "
                + "the period is admitted rather than silently dropped")
        void theUpperBoundIsTheDayAfterTheInclusiveEndDate() throws Exception {
            // A row stamped at the very end of the inclusive end date. Under a naive procTs <= '2022-06-30'
            // this row - and every other row of that day - would vanish from the report with nothing failing.
            servePages(List.of(transaction("0000000000000009", "0000000000000009",
                    "2022-06-30-23.59.59.999000")));
            final UploadRecorder recorder = recordUpload(DAILY_KEY);

            assertThat(streamDaily(END_DATE)).isEqualTo(1);

            final ArgumentCaptor<String> lower = ArgumentCaptor.forClass(String.class);
            final ArgumentCaptor<String> upper = ArgumentCaptor.forClass(String.class);
            verify(transactionRepository)
                    .findByProcessingTimestampHalfOpenRangeOrderByCardNumberAsc(
                            lower.capture(), upper.capture(), any(Pageable.class));
            assertThat(lower.getValue())
                    .as("app/proc/TRANREPT.prc:L45 - the lower bound is inclusive and is passed through")
                    .isEqualTo(START_DATE);
            assertThat(upper.getValue())
                    .as("a twenty-six character timestamp is never <= a ten-character date on the same day, "
                            + "so the inclusive end date becomes an exclusive bound. The bound is derived "
                            + "LEXICALLY - the final character is incremented - because the comparison the "
                            + "source performs at app/proc/TRANREPT.prc:L46 is a character comparison it "
                            + "never parses. It is exactly equivalent: every timestamp whose first ten "
                            + "characters are at most the end date compares less, because the decision falls "
                            + "at character ten, and every later date compares greater, because it falls "
                            + "earlier. See exclusiveEndBound for why this is preferred over adding a day")
                    .isEqualTo("2022-06-31");
            assertThat(recorder.bytes()).hasSize(TRANSACTION_RECORD_LENGTH);
        }

        @Test
        @DisplayName("a year end needs no calendar handling, because the bound is a comparison operand and "
                + "never a date")
        void theBoundNeedsNoCalendarHandling() throws Exception {
            servePages(List.of());
            recordUpload(DAILY_KEY);

            assertThat(streamDaily("2022-12-31")).isZero();

            final ArgumentCaptor<String> upper = ArgumentCaptor.forClass(String.class);
            verify(transactionRepository)
                    .findByProcessingTimestampHalfOpenRangeOrderByCardNumberAsc(
                            anyString(), upper.capture(), any(Pageable.class));
            assertThat(upper.getValue())
                    .as("2022-12-32 is not a calendar date and does not need to be: the value is only ever a "
                            + "comparison operand, never rendered, stored or reported. Every timestamp in "
                            + "December 2022 compares less than it and every timestamp in January 2023 "
                            + "compares greater, which is the whole requirement. Deriving 2023-01-01 instead "
                            + "would reintroduce month-end and leap-year handling into a comparison the "
                            + "source performs on characters")
                    .isEqualTo("2022-12-32");
        }

        @Test
        @DisplayName("an empty period writes an empty object and a count of zero, never a failure")
        void anEmptyPeriodWritesAnEmptyObject() throws Exception {
            servePages(List.of());
            final UploadRecorder recorder = recordUpload(DAILY_KEY);

            assertThat(streamDaily(END_DATE)).isZero();

            assertThat(recorder.bytes()).isEmpty();
            assertThat(recorder.closed())
                    .as("app/proc/TRANREPT.prc:L49-L53 allocates SORTOUT unconditionally, so an empty period "
                            + "still produces the generation")
                    .isTrue();
        }

        @Test
        @DisplayName("a malformed end date abends before any query is issued or any object is created")
        void aMalformedEndDateAbendsBeforeQuerying() {
            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> streamDaily("2022-06-3X"))
                    .satisfies(abend -> assertThat(abend.getAbendReason())
                            .as("the derived bound is the one place a malformed parameter can be caught "
                                    + "before it becomes an unbounded scan")
                            .isEqualTo("END DATE INVALID"));

            Mockito.verifyNoInteractions(transactionRepository);
            Mockito.verifyNoInteractions(objectStorage);
        }
    }

    /**
     * {@code STEP10R}: the print reads its generation record by record and verifies the declared order.
     */
    @Nested
    @DisplayName("PRTCATBL STEP10R - the generation is read a record at a time and its order is verified")
    final class PrintStepIsStreamed {

        /**
         * Creates the print-step group.
         *
         * <p>Declared explicitly for the same reason as the sort-step group.
         */
        PrintStepIsStreamed() {
            // Intentionally empty; the enclosing instance owns every collaborator.
        }

        @Test
        @DisplayName("an ascending generation prints one 40-byte line per record, in the source's order")
        void anAscendingGenerationPrintsOneLinePerRecord() throws Exception {
            serveBackup(image(List.of(
                    categoryBalanceRecord(1L, "01", 1),
                    categoryBalanceRecord(1L, "01", 2),
                    categoryBalanceRecord(2L, "01", 1))));
            final UploadRecorder recorder = recordUpload("reports/TCATBALF.REPT");

            assertThat(printCategoryBalances()).isEqualTo(3);

            assertThat(recorder.bytes())
                    .as("app/jcl/PRTCATBL.jcl:L61 - LRECL=40, so three lines are 120 bytes")
                    .hasSize(3 * CATEGORY_BALANCE_REPORT_LINE_LENGTH);
            final String text = new String(recorder.bytes(), FIXED_WIDTH);
            assertThat(text.substring(0, CATEGORY_BALANCE_REPORT_LINE_LENGTH))
                    .as("app/jcl/PRTCATBL.jcl:L53-L56 - each OUTREC X is one blank and EDIT prints every "
                            + "digit position without suppression")
                    .isEqualTo("00000000001 01 0001 000000123.45        ");
        }

        @Test
        @DisplayName("an empty generation prints nothing and is a successful zero-line run")
        void anEmptyGenerationPrintsNothing() throws Exception {
            serveBackup(new byte[0]);
            final UploadRecorder recorder = recordUpload("reports/TCATBALF.REPT");

            assertThat(printCategoryBalances()).isZero();

            assertThat(recorder.bytes()).isEmpty();
            assertThat(recorder.closed()).isTrue();
        }

        @Test
        @DisplayName("a DESCENDING composite key is refused by ordinal, because a stream cannot be re-sorted "
                + "and a silent re-sort would have hidden a producer regression")
        void aDescendingCompositeKeyIsRefused() throws Exception {
            serveBackup(image(List.of(
                    categoryBalanceRecord(2L, "01", 1),
                    categoryBalanceRecord(1L, "01", 1))));
            recordUpload("reports/TCATBALF.REPT");

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(TransactionReportBoundedReadTest.this::printCategoryBalances)
                    .satisfies(abend -> {
                        assertThat(abend.getAbendReason()).isEqualTo("SORT ORDER VIOLATION");
                        assertThat(abend.getAbendMessage())
                                .as("app/jcl/PRTCATBL.jcl:L52 declares the ascending order the unload "
                                        + "produces; the offending record is named by ordinal so the "
                                        + "producer can be found")
                                .contains("RECORD 2");
                    });
        }

        @Test
        @DisplayName("an EQUAL composite key is accepted, because SORT FIELDS requires ascending and not "
                + "strictly ascending")
        void anEqualCompositeKeyIsAccepted() throws Exception {
            serveBackup(image(List.of(
                    categoryBalanceRecord(1L, "01", 1),
                    categoryBalanceRecord(1L, "01", 1))));
            final UploadRecorder recorder = recordUpload("reports/TCATBALF.REPT");

            assertThat(printCategoryBalances())
                    .as("the composite primary key makes a duplicate impossible from the unload, but the "
                            + "check must not be stricter than the sort it stands in for")
                    .isEqualTo(2);
            assertThat(recorder.bytes()).hasSize(2 * CATEGORY_BALANCE_REPORT_LINE_LENGTH);
        }

        @Test
        @DisplayName("a trailing partial record is refused by naming the record, not the whole object length")
        void aTrailingPartialRecordIsRefused() throws Exception {
            serveBackup(image(List.of(categoryBalanceRecord(1L, "01", 1), "0000000000101")));
            recordUpload("reports/TCATBALF.REPT");

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(TransactionReportBoundedReadTest.this::printCategoryBalances)
                    .satisfies(abend -> {
                        assertThat(abend.getAbendReason()).isEqualTo("RECORD LENGTH VIOLATION");
                        assertThat(abend.getAbendMessage())
                                .as("a record-at-a-time reader can say which record is short; a whole-object "
                                        + "reader could only report a total that divided badly")
                                .isEqualTo("TCATBALF.BKUP RECORD IS 13 BYTES; EXPECTED "
                                        + CATEGORY_BALANCE_RECORD_LENGTH);
                    });
        }

        @Test
        @DisplayName("the unload feeds the print in the order the print requires, so the pair is consistent")
        void theUnloadFeedsThePrintInTheOrderThePrintRequires() throws Exception {
            final List<TransactionCategoryBalance> balances = List.of(
                    new TransactionCategoryBalance(
                            new TransactionCategoryBalanceId(1L, "01", 1), new BigDecimal("123.45")),
                    new TransactionCategoryBalance(
                            new TransactionCategoryBalanceId(2L, "01", 1), new BigDecimal("67.89")));
            when(transactionCategoryBalanceRepository
                    .findAllByOrderByIdAccountIdAscIdTypeCdAscIdCatCdAsc(any(Pageable.class)))
                    .thenAnswer(invocation -> {
                        final Pageable pageable = invocation.getArgument(0);
                        final int from = Math.min((int) pageable.getOffset(), balances.size());
                        final int to = Math.min(from + pageable.getPageSize(), balances.size());
                        return new SliceImpl<>(
                                balances.subList(from, to), pageable, to < balances.size());
                    });
            final UploadRecorder unload = recordUpload(BACKUP_KEY);

            assertThat((int) invokePrivate("unloadCategoryBalances",
                    new Class<?>[] {String.class}, BACKUP_KEY)).isEqualTo(2);

            // The print refuses a descending source, so the unload's ordering is a precondition of the pair
            // rather than an incidental property. Feeding the unload's own bytes straight into the print is
            // what proves the two agree; asserting each in isolation would not.
            serveBackup(unload.bytes());
            final UploadRecorder print = recordUpload("reports/TCATBALF.REPT");

            assertThat(printCategoryBalances()).isEqualTo(2);
            assertThat(unload.bytes())
                    .as("app/jcl/PRTCATBL.jcl:L37 - LRECL=50 on the unload")
                    .hasSize(2 * CATEGORY_BALANCE_RECORD_LENGTH);
            assertThat(print.bytes()).hasSize(2 * CATEGORY_BALANCE_REPORT_LINE_LENGTH);
        }
    }
}

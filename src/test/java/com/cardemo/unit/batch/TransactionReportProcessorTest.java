/*
 * ******************************************************************
 * Program     : TransactionReportProcessorTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Verifies the per-record boundary of the daily
 *               transaction report: twenty detail lines per page, the
 *               control break that triggers on the CARD NUMBER while
 *               the emitted label reads "Account Total", the page and
 *               grand total rollups, the inclusive date filter that the
 *               program re-applies after the sort has already applied
 *               it, the 133-byte fixed block record geometry of every
 *               emitted line, the -ZZZ,ZZZ,ZZZ.ZZ edited amount masks,
 *               and the end-of-data double count that is preserved
 *               rather than repaired.
 * Source      : app/cbl/CBTRN03C.cbl:L172-L196 (per-record body)
 *               app/cbl/CBTRN03C.cbl:L181-L188 (control break on card)
 *               app/cbl/CBTRN03C.cbl:L198-L203 (end of data)
 *               app/cbl/CBTRN03C.cbl:L275-L289 (1100-WRITE-TRANSACTION)
 *               app/cbl/CBTRN03C.cbl:L294-L302 (page totals)
 *               app/cbl/CBTRN03C.cbl:L307-L314 (account totals)
 *               app/cbl/CBTRN03C.cbl:L319-L321 (grand totals)
 *               app/cbl/CBTRN03C.cbl:L325-L339 (headers)
 *               app/cbl/CBTRN03C.cbl:L362-L373 (detail line)
 *               app/cbl/CBTRN03C.cbl:L485-L511 (three lookups)
 *               app/cbl/CBTRN03C.cbl:L627-L630 (abend 999 / RC 12)
 *               app/cpy/CVTRA07Y.cpy:L15-L31   (133-byte report line)
 *               app/proc/TRANREPT.prc:STEP05R  (sort + INCLUDE COND)
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
import static org.mockito.Mockito.mock;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cardemo.batch.processors.TransactionReportProcessor;
import com.cardemo.batch.processors.TransactionReportProcessor.ReportLines;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Transaction;
import com.cardemo.model.entity.TransactionCategory;
import com.cardemo.model.entity.TransactionType;
import com.cardemo.model.key.TransactionCategoryId;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.TransactionCategoryRepository;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.repository.TransactionTypeRepository;
import com.cardemo.service.shared.FileStatusMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
import org.springframework.dao.QueryTimeoutException;

/**
 * Executable proof that the report step reproduces {@code CBTRN03C}'s paging, control break and record
 * geometry, including the mislabelled break and the end-of-data double count.
 *
 * <p><b>What it does.</b> Feeds transactions through
 * {@link TransactionReportProcessor#process(Transaction)} and asserts the exact 133-character lines it
 * returns, the running totals it maintains, and the abends it raises when a lookup misses. The three
 * repositories are Mockito mocks; {@link FileStatusMapper} is a real instance because its
 * {@code FILE STATUS IS: NNNN} rendering appears verbatim in the log lines this suite asserts.
 *
 * <p><b>How to build and test.</b>
 * {@code ./mvnw -B -ntp -Dtest='TransactionReportProcessorTest' test} runs this class alone; it needs no
 * container, no database and no cloud emulator.
 *
 * <p><b>Key configuration and defaults.</b> The reporting period is {@code 2022-06-01} to
 * {@code 2022-06-30}, matching the month the corpus fixture's processing timestamps fall in, and the
 * period is inclusive at both ends. Page size is twenty. Every emitted line is exactly 133 characters,
 * which is the {@code LRECL} {@code app/proc/TRANREPT.prc:STEP10R} declares.
 *
 * <p><b>Two preserved legacy defects are asserted, not repaired.</b> The control break tests the card
 * number while the line it emits is labelled {@code "Account Total"} - group 3 asserts both halves of
 * that mismatch deliberately. And the end-of-data path adds the stale record buffer's amount to both the
 * page and the account total a second time - group 6 asserts that double count. Correcting either would
 * change output the boundary-parity gate diffs against the legacy baseline.
 *
 * <p><b>Common failure modes and troubleshooting.</b>
 * <ul>
 * <li><b>Blocker.</b> A failure in group 3 means the control-break key has moved. It is the card number
 * and only the card number; a break on the resolved account identifier would merge every card of a
 * multi-card account into one group and change the totals.</li>
 * <li><b>Blocker.</b> A failure in group 5 means a line of some length other than 133 reached the writer.
 * A fixed block file rejects that, and every downstream comparison is byte-oriented.</li>
 * <li><b>High.</b> A failure in group 4 means the page rollup has changed. The grand total accumulates
 * only through the page total, so a page total that fails to reset double counts and one that fails to
 * roll up loses a page.</li>
 * <li><b>High.</b> A failure in group 2 means the inclusive date filter has changed sense. The sort card
 * already filters, and the program filters again; both bounds are inclusive.</li>
 * <li><b>Medium.</b> A failure in group 7 means a lookup miss no longer abends. The source displays and
 * then reaches {@code 9999-ABEND-PROGRAM}; continuing from a stale buffer is what the guard prevents.</li>
 * <li><b>Low.</b> A failure in group 9 means an unmasked card number reached a log record.</li>
 * </ul>
 */
@DisplayName("TransactionReportProcessor: CBTRN03C's paging, mislabelled break and 133-byte lines")
class TransactionReportProcessorTest {

    /** The inclusive lower bound of the reporting period, {@code WS-START-DATE PIC X(10)}. */
    private static final String START_DATE = "2022-06-01";

    /** The inclusive upper bound of the reporting period, {@code WS-END-DATE PIC X(10)}. */
    private static final String END_DATE = "2022-06-30";

    /** The fixed block record length of {@code REPTFILE}. */
    private static final int LINE_LENGTH = 133;

    /** Detail lines per page, from {@code app/cbl/CBTRN03C.cbl:L127-L137}. */
    private static final int PAGE_SIZE = 20;

    /** The card number of the first control-break group. */
    private static final String CARD_A = "4111111111111111";

    /** The card number of the second control-break group. */
    private static final String CARD_B = "4222222222222222";

    /** The account the first card resolves to. */
    private static final long ACCOUNT_A = 11L;

    /** The account the second card resolves to. */
    private static final long ACCOUNT_B = 22L;

    /** {@code TRAN-TYPE-CD PIC X(02)}. */
    private static final String TYPE_CD = "01";

    /** {@code TRAN-CAT-CD PIC 9(04)}. */
    private static final int CAT_CD = 1000;

    /** The width of the edited amount field: one sign position plus the fourteen of ZZZ,ZZZ,ZZZ.ZZ. */
    private static final int EDITED_AMOUNT_WIDTH = 15;

    /**
     * The offset of {@code TRAN-REPORT-AMT} within the detail line, from the declaration order of
     * {@code app/cpy/CVTRA07Y.cpy:L15-L31}: 16 + 1 + 11 + 1 + 2 + 1 + 15 + 1 + 4 + 1 + 29 + 1 + 10 + 4.
     */
    private static final int DETAIL_AMOUNT_START = 97;

    /** The per-program logger the processor routes every DISPLAY reproduction to. */
    private static final String PARITY_LOGGER_NAME = "com.cardemo.parity.CBTRN03C";

    private CardCrossReferenceRepository cardCrossReferenceRepository;
    private TransactionTypeRepository transactionTypeRepository;
    private TransactionCategoryRepository transactionCategoryRepository;
    private FileStatusMapper fileStatusMapper;
    private TransactionReportProcessor processor;

    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger logger;
    private Level originalLevel;

    private ListAppender<ILoggingEvent> parityAppender;
    private ch.qos.logback.classic.Logger parityLogger;
    private Level originalParityLevel;

    @BeforeEach
    void buildProcessorAndCaptureLogs() {
        cardCrossReferenceRepository = Mockito.mock(CardCrossReferenceRepository.class);
        transactionTypeRepository = Mockito.mock(TransactionTypeRepository.class);
        transactionCategoryRepository = Mockito.mock(TransactionCategoryRepository.class);
        fileStatusMapper = new FileStatusMapper();

        logger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(TransactionReportProcessor.class);
        originalLevel = logger.getLevel();
        logger.setLevel(Level.TRACE);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        // The per-program parity logger. Every shipped profile pins it to OFF, so a test that wants to read
        // what DISPLAY emitted has to raise its level here as well as attach to it.
        parityLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(PARITY_LOGGER_NAME);
        originalParityLevel = parityLogger.getLevel();
        parityLogger.setLevel(Level.TRACE);
        parityAppender = new ListAppender<>();
        parityAppender.start();
        parityLogger.addAppender(parityAppender);

        processor = newProcessor(START_DATE, END_DATE);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(originalLevel);
        parityLogger.detachAppender(parityAppender);
        parityAppender.stop();
        parityLogger.setLevel(originalParityLevel);
    }

    /**
     * Builds a processor over the four mocked or real collaborators for a chosen period.
     *
     * @param startDate {@code WS-START-DATE}
     * @param endDate {@code WS-END-DATE}
     * @return the processor under test
     */
    private TransactionReportProcessor newProcessor(final String startDate, final String endDate) {
        return new TransactionReportProcessor(mock(TransactionRepository.class),
                cardCrossReferenceRepository, transactionTypeRepository,
                transactionCategoryRepository, fileStatusMapper, startDate, endDate);
    }

    /**
     * Builds a transaction for the report.
     *
     * @param transactionId {@code TRAN-ID}
     * @param cardNumber {@code TRAN-CARD-NUM}
     * @param amount {@code TRAN-AMT}
     * @param procTs {@code TRAN-PROC-TS}
     * @return the record the sorted reader would supply
     */
    private static Transaction transaction(final String transactionId, final String cardNumber,
                                           final String amount, final String procTs) {
        return new Transaction(transactionId, TYPE_CD, CAT_CD, "POS TERM", "PURCHASE AT MERCHANT",
                new BigDecimal(amount), 123456789L, "SAMPLE MERCHANT", "SAMPLE CITY", "12345",
                cardNumber, "2022-06-10 19:27:53.000000", procTs);
    }

    /**
     * Builds a transaction inside the reporting period on the first card.
     *
     * @param amount {@code TRAN-AMT}
     * @return the record
     */
    private static Transaction transaction(final String amount) {
        return transaction("0000000000683580", CARD_A, amount, "2022-06-10-19.27.53.000000");
    }

    /**
     * Stubs both reference tables so that every record resolves.
     *
     * <p>The processor loads each table once per step execution and serves every record's lookup from the
     * loaded map, so the collaborator call is {@code findAll} and not a keyed read per record. The keyed
     * form stubbed here previously left both maps empty, which made every record abend on a TRANTYPE miss.
     * The abend itself is still exercised, by group 7, through a table that genuinely lacks the row.
     */
    private void stubReferenceData() {
        Mockito.when(transactionTypeRepository.findAll())
                .thenReturn(List.of(new TransactionType(TYPE_CD, "Purchase")));
        Mockito.when(transactionCategoryRepository.findAll())
                .thenReturn(List.of(new TransactionCategory(
                        new TransactionCategoryId(TYPE_CD, CAT_CD), "Regular Sales Draft")));
    }

    /**
     * Stubs the cross-reference lookup for one card.
     *
     * @param cardNumber the key
     * @param accountId the account it resolves to
     */
    private void stubCrossReference(final String cardNumber, final long accountId) {
        Mockito.when(cardCrossReferenceRepository.findById(cardNumber))
                .thenReturn(Optional.of(new CardCrossReference(cardNumber, 123456789L, accountId)));
    }

    /** Stubs everything the happy path needs for both cards. */
    private void stubHappyPath() {
        stubReferenceData();
        stubCrossReference(CARD_A, ACCOUNT_A);
        stubCrossReference(CARD_B, ACCOUNT_B);
    }

    /** @return every message this class's logger received. */
    private List<String> loggedMessages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /**
     * Returns the messages the per-program parity logger received.
     *
     * <p>The record image of {@code :L180} and the two closing aggregates of {@code :L198-L199} are routed
     * to {@value #PARITY_LOGGER_NAME} rather than to the class logger, so that a deployment can silence the
     * record-by-record reproduction of a batch run without silencing the application's own diagnostics.
     * Assertions about what DISPLAY emitted therefore read this list, and assertions about the processor's
     * own diagnostics read {@link #loggedMessages()}.
     *
     * @return every message the parity logger received, in emission order
     */
    private List<String> parityMessages() {
        return parityAppender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /**
     * Drives a run of records on one card and collects every emitted line in order.
     *
     * @param count how many identical records to feed
     * @param cardNumber the card they all carry
     * @param amount the amount they all carry
     * @return every line the run emitted, in emission order
     */
    private List<String> runOfRecords(final int count, final String cardNumber, final String amount) {
        List<String> emitted = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            ReportLines result = processor.process(transaction(
                    String.format(java.util.Locale.ROOT, "%016d", index), cardNumber, amount,
                    "2022-06-10-19.27.53.000000"));
            if (result != null) {
                emitted.addAll(result.lines());
            }
        }
        return emitted;
    }

    @Nested
    @DisplayName("1. Construction: four collaborators and an ordered PIC X(10) period")
    class Construction {

        @ParameterizedTest(name = "an absent {0} abends before any record is read")
        @ValueSource(strings = {"CardCrossReferenceRepository", "TransactionTypeRepository",
            "TransactionCategoryRepository", "FileStatusMapper"})
        @DisplayName("each absent collaborator abends and names the DD it could not resolve")
        void eachAbsentCollaboratorAbends(final String missing) {
            CardCrossReferenceRepository xrefs =
                    "CardCrossReferenceRepository".equals(missing) ? null : cardCrossReferenceRepository;
            TransactionTypeRepository types =
                    "TransactionTypeRepository".equals(missing) ? null : transactionTypeRepository;
            TransactionCategoryRepository categories =
                    "TransactionCategoryRepository".equals(missing) ? null : transactionCategoryRepository;
            FileStatusMapper mapper = "FileStatusMapper".equals(missing) ? null : fileStatusMapper;

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> new TransactionReportProcessor(mock(TransactionRepository.class),
                            xrefs, types, categories, mapper, START_DATE, END_DATE))
                    .satisfies(abend -> {
                        assertThat(abend.getAbendCulprit()).isEqualTo("CBTRN03C");
                        assertThat(abend.getAbendMessage()).isEqualTo("ABENDING PROGRAM");
                        assertThat(abend.getAbendReason())
                                .contains(missing + " was not injected");
                    });
        }

        @Test
        @DisplayName("the abend code renders as the four zero-filled digits of 999")
        void theAbendCodeRendersAsFourDigits() {
            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> newProcessor(null, END_DATE))
                    .satisfies(abend -> assertThat(abend.getAbendCode())
                            .hasSize(FileStatusMapper.ABEND_CODE_WIDTH)
                            .isEqualTo("0999"));
        }

        @Test
        @DisplayName("an absent WS-START-DATE abends against DATEPARM")
        void anAbsentStartDateAbends() {
            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> newProcessor(null, END_DATE))
                    .satisfies(abend -> {
                        assertThat(abend.getAbendMessage()).isEqualTo("ERROR READING DATEPARM FILE");
                        assertThat(abend.getAbendReason())
                                .contains("WS-START-DATE was not supplied as a job parameter");
                    });
        }

        @Test
        @DisplayName("an absent WS-END-DATE abends against DATEPARM")
        void anAbsentEndDateAbends() {
            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> newProcessor(START_DATE, null))
                    .satisfies(abend -> assertThat(abend.getAbendReason())
                            .contains("WS-END-DATE was not supplied"));
        }

        @ParameterizedTest(name = "a period bound of [{0}] is refused")
        @ValueSource(strings = {"", "2022-06", "2022-06-011"})
        @DisplayName("a bound of any width but ten is refused, because the field is PIC X(10)")
        void aWronglyWidthedBoundIsRefused(final String bound) {
            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> newProcessor(bound, END_DATE))
                    .satisfies(abend -> assertThat(abend.getAbendReason())
                            .contains("WS-START-DATE is " + bound.length() + " characters"));
        }

        @Test
        @DisplayName("an inverted period is refused, because no record could satisfy it")
        void anInvertedPeriodIsRefused() {
            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> newProcessor(END_DATE, START_DATE))
                    .satisfies(abend -> assertThat(abend.getAbendReason())
                            .contains("is inverted")
                            .contains("the report would be empty"));
        }

        @Test
        @DisplayName("an equal start and end date is accepted, because both bounds are inclusive")
        void anEqualPeriodIsAccepted() {
            assertThat(newProcessor(START_DATE, START_DATE)).isNotNull();
        }

        @Test
        @DisplayName("construction announces the period exactly as :L232-L233 displays it")
        void constructionAnnouncesThePeriod() {
            assertThat(loggedMessages())
                    .contains("Reporting from " + START_DATE + " to " + END_DATE);
        }

        @Test
        @DisplayName("the counters and totals all start at zero and the break key starts blank")
        void theCountersStartAtZero() {
            assertThat(processor.lineCounter()).isZero();
            assertThat(processor.pageTotal()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(processor.accountTotal()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(processor.grandTotal()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(processor.currentCardNumber()).hasSize(16).isBlank();
        }
    }

    @Nested
    @DisplayName("2. The inclusive date re-filter of :L173-L178, applied after the sort already filtered")
    class DateFilter {

        @ParameterizedTest(name = "a processing date of {0} is reported: {1}")
        @CsvSource({
            "2022-05-31, false",
            "2022-06-01, true",
            "2022-06-15, true",
            "2022-06-30, true",
            "2022-07-01, false"
        })
        @DisplayName("both bounds are inclusive and everything outside them is dropped")
        void bothBoundsAreInclusive(final String date, final boolean reported) {
            stubHappyPath();

            ReportLines result = processor.process(
                    transaction("0000000000683580", CARD_A, "10.00", date + "-00.00.00.000000"));

            assertThat(result == null).isNotEqualTo(reported);
        }

        @Test
        @DisplayName("a filtered record emits nothing and advances no counter")
        void aFilteredRecordEmitsNothing() {
            stubHappyPath();

            processor.process(
                    transaction("0000000000683580", CARD_A, "10.00", "2022-05-01-00.00.00.000000"));

            assertThat(processor.lineCounter()).isZero();
            assertThat(processor.pageTotal()).isEqualByComparingTo(BigDecimal.ZERO);
            Mockito.verifyNoInteractions(cardCrossReferenceRepository, transactionTypeRepository,
                    transactionCategoryRepository);
        }

        @Test
        @DisplayName("only the first ten characters of TRAN-PROC-TS take part in the comparison")
        void onlyTenCharactersAreCompared() {
            stubHappyPath();

            ReportLines result = processor.process(
                    transaction("0000000000683580", CARD_A, "10.00", "2022-06-30-99.99.99.999999"));

            assertThat(result).isNotNull();
        }

        @ParameterizedTest(name = "a TRAN-PROC-TS of [{0}] cannot satisfy the filter")
        @ValueSource(strings = {"", "2022", "2022-06-3"})
        @DisplayName("a processing timestamp shorter than ten characters is dropped, never padded")
        void aShortTimestampIsDropped(final String procTs) {
            stubHappyPath();

            ReportLines result = processor.process(
                    transaction("0000000000683580", CARD_A, "10.00", procTs));

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("an absent record abends rather than being filtered away")
        void anAbsentRecordAbends() {
            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> processor.process(null))
                    .satisfies(abend -> {
                        assertThat(abend.getAbendMessage()).isEqualTo("ABENDING PROGRAM");
                        assertThat(abend.getAbendReason())
                                .contains("the reader supplied a null record");
                    });
        }
    }

    @Nested
    @DisplayName("3. PRESERVED DEFECT: the break tests the CARD NUMBER under an \"Account Total\" label")
    class ControlBreak {

        @Test
        @DisplayName("the first record opens the report and breaks nothing")
        void theFirstRecordBreaksNothing() {
            stubHappyPath();

            ReportLines result = processor.process(transaction("10.00"));

            assertThat(result.lines())
                    .as("four header lines and one detail line, with no account total")
                    .hasSize(5);
            assertThat(result.lines()).noneMatch(line -> line.startsWith("Account Total"));
            assertThat(processor.currentCardNumber()).isEqualTo(CARD_A);
        }

        @Test
        @DisplayName("a second record on the same card does not break and does not re-read CARDXREF")
        void aSecondRecordOnTheSameCardDoesNotBreak() {
            stubHappyPath();

            processor.process(transaction("10.00"));
            ReportLines second = processor.process(transaction("20.00"));

            assertThat(second.lines()).hasSize(1);
            Mockito.verify(cardCrossReferenceRepository, Mockito.times(1)).findById(CARD_A);
        }

        @Test
        @DisplayName("a new CARD NUMBER breaks and emits a line labelled Account Total")
        void aNewCardNumberEmitsAnAccountTotalLine() {
            stubHappyPath();
            processor.process(transaction("10.00"));

            ReportLines second = processor.process(
                    transaction("0000000000683581", CARD_B, "20.00", "2022-06-10-19.27.53.000000"));

            assertThat(second.lines())
                    .as("the label says Account Total; the key that triggered it is the card number")
                    .anyMatch(line -> line.startsWith("Account Total"));
            assertThat(processor.currentCardNumber()).isEqualTo(CARD_B);
        }

        @Test
        @DisplayName("two different cards on the SAME account still break: the key is the card, not the account")
        void twoCardsOnOneAccountStillBreak() {
            stubReferenceData();
            // Both cards resolve to the same account. A break on the resolved account identifier would
            // merge them into one group and emit no account total between them; the source breaks on the
            // card number, so it emits one.
            stubCrossReference(CARD_A, ACCOUNT_A);
            stubCrossReference(CARD_B, ACCOUNT_A);
            processor.process(transaction("10.00"));

            ReportLines second = processor.process(
                    transaction("0000000000683581", CARD_B, "20.00", "2022-06-10-19.27.53.000000"));

            assertThat(second.lines())
                    .as("this is the mislabelled break, preserved from app/cbl/CBTRN03C.cbl:L181")
                    .anyMatch(line -> line.startsWith("Account Total"));
        }

        @Test
        @DisplayName("the account total carries the previous card's accumulated amount and then resets")
        void theAccountTotalCarriesThePreviousGroup() {
            stubHappyPath();
            processor.process(transaction("10.00"));
            processor.process(transaction("20.00"));
            assertThat(processor.accountTotal()).isEqualByComparingTo(new BigDecimal("30.00"));

            processor.process(
                    transaction("0000000000683581", CARD_B, "5.00", "2022-06-10-19.27.53.000000"));

            assertThat(processor.accountTotal())
                    .as("the accumulator is zeroed on the break and then takes the new record's amount")
                    .isEqualByComparingTo(new BigDecimal("5.00"));
        }

        @Test
        @DisplayName("the break key is the card number space-padded to sixteen, so a short key still matches")
        void theBreakKeyIsSpacePaddedToSixteen() {
            stubReferenceData();
            Mockito.when(cardCrossReferenceRepository.findById("4111            "))
                    .thenReturn(Optional.of(new CardCrossReference("4111", 123456789L, ACCOUNT_A)));

            processor.process(transaction("0000000000683580", "4111", "10.00",
                    "2022-06-10-19.27.53.000000"));
            ReportLines second = processor.process(transaction("0000000000683581", "4111", "20.00",
                    "2022-06-10-19.27.53.000000"));

            assertThat(second.lines()).hasSize(1);
            assertThat(processor.currentCardNumber()).hasSize(16).isEqualTo("4111            ");
        }
    }

    @Nested
    @DisplayName("4. Paging and rollups: twenty detail lines per page, one grand total path")
    class PagingAndRollups {

        @Test
        @DisplayName("the first record emits the four header lines of :L325-L339")
        void theFirstRecordEmitsFourHeaders() {
            stubHappyPath();

            List<String> lines = processor.process(transaction("10.00")).lines();

            assertThat(lines.get(0)).startsWith("DALYREPT");
            assertThat(lines.get(1)).isBlank();
            assertThat(lines.get(2)).startsWith("Transaction ID");
            assertThat(lines.get(3)).isEqualTo("-".repeat(LINE_LENGTH));
            assertThat(processor.lineCounter())
                    .as("four header lines plus one detail line")
                    .isEqualTo(5L);
        }

        @Test
        @DisplayName("the header carries the report name, long name and the inclusive date range")
        void theHeaderCarriesTheDateRange() {
            stubHappyPath();

            String header = processor.process(transaction("10.00")).lines().get(0);

            assertThat(header)
                    .hasSize(LINE_LENGTH)
                    .contains("Daily Transaction Report")
                    .contains("Date Range: " + START_DATE + " to " + END_DATE);
        }

        @Test
        @DisplayName("a page break emits page totals then headers, once every twenty lines")
        void aPageBreakEmitsTotalsThenHeaders() {
            stubHappyPath();

            List<String> emitted = runOfRecords(20, CARD_A, "1.00");

            assertThat(emitted)
                    .filteredOn(line -> line.startsWith("Page Total"))
                    .as("the counter reaches a multiple of twenty once inside a run of twenty records")
                    .hasSize(1);
        }

        @Test
        @DisplayName("the page total rolls into the grand total and then resets, per :L297-L298")
        void thePageTotalRollsUpAndResets() {
            stubHappyPath();

            runOfRecords(20, CARD_A, "1.00");

            assertThat(processor.grandTotal())
                    .as("the grand total accumulates only through the page total")
                    .isGreaterThan(BigDecimal.ZERO);
            assertThat(processor.pageTotal().add(processor.grandTotal()))
                    .as("nothing is lost between the two: every amount is in one or the other")
                    .isEqualByComparingTo(new BigDecimal("20.00"));
        }

        @Test
        @DisplayName("the page total line is a label, a run of dots and an edited amount")
        void thePageTotalLineShape() {
            stubHappyPath();

            List<String> emitted = runOfRecords(20, CARD_A, "1.00");
            String pageTotal = emitted.stream()
                    .filter(line -> line.startsWith("Page Total")).findFirst().orElseThrow();

            assertThat(pageTotal)
                    .hasSize(LINE_LENGTH)
                    .startsWith("Page Total ")
                    .contains("....");
        }

        @Test
        @DisplayName("the account total line uses a shorter leader, because its label is longer")
        void theAccountTotalLineShape() {
            stubHappyPath();
            processor.process(transaction("12345.67"));

            ReportLines second = processor.process(
                    transaction("0000000000683581", CARD_B, "1.00", "2022-06-10-19.27.53.000000"));
            String accountTotal = second.lines().stream()
                    .filter(line -> line.startsWith("Account Total")).findFirst().orElseThrow();

            assertThat(accountTotal)
                    .hasSize(LINE_LENGTH)
                    .startsWith("Account Total")
                    .contains("12,345.67");
        }

        @Test
        @DisplayName("every emitted line advances the counter, so paging stays aligned with output")
        void everyLineAdvancesTheCounter() {
            stubHappyPath();

            List<String> emitted = runOfRecords(5, CARD_A, "1.00");

            assertThat(processor.lineCounter())
                    .as("the grand total line is the one line that deliberately does not advance it")
                    .isEqualTo(emitted.size());
        }

        @Test
        @DisplayName("the amounts accumulate into both the page and the account total, per :L287-L288")
        void amountsAccumulateIntoBothTotals() {
            stubHappyPath();

            processor.process(transaction("10.00"));
            processor.process(transaction("2.50"));

            assertThat(processor.pageTotal()).isEqualByComparingTo(new BigDecimal("12.50"));
            assertThat(processor.accountTotal()).isEqualByComparingTo(new BigDecimal("12.50"));
        }
    }

    @Nested
    @DisplayName("5. Record geometry: every line is exactly 133 characters")
    class RecordGeometry {

        @Test
        @DisplayName("every line of a multi-page, multi-card run is exactly 133 characters")
        void everyLineIsOneHundredAndThirtyThree() {
            stubHappyPath();

            List<String> emitted = new ArrayList<>(runOfRecords(25, CARD_A, "1.00"));
            emitted.addAll(processor.process(
                    transaction("0000000000683599", CARD_B, "2.00", "2022-06-10-19.27.53.000000"))
                    .lines());
            emitted.addAll(processor.finishReport().lines());

            assertThat(emitted).isNotEmpty();
            assertThat(emitted).allSatisfy(line -> assertThat(line).hasSize(LINE_LENGTH));
        }

        @Test
        @DisplayName("the detail line is assembled in CVTRA07Y declaration order with its two hyphens")
        void theDetailLineIsAssembledInCopybookOrder() {
            stubHappyPath();

            String detail = processor.process(transaction("1234.56")).lines().get(4);

            assertThat(detail).hasSize(LINE_LENGTH);
            assertThat(detail.substring(0, 16)).isEqualTo("0000000000683580");
            assertThat(detail.charAt(16)).isEqualTo(' ');
            assertThat(detail.substring(17, 28))
                    .as("XREF-ACCT-ID rendered as eleven zero-filled digits")
                    .isEqualTo("00000000011");
            assertThat(detail.substring(29, 31)).isEqualTo(TYPE_CD);
            assertThat(detail.charAt(31))
                    .as("CVTRA07Y:L21 FILLER PIC X(01) VALUE '-'")
                    .isEqualTo('-');
            assertThat(detail.substring(32, 47)).isEqualTo("Purchase       ");
            assertThat(detail.substring(48, 52))
                    .as("TRAN-CAT-CD rendered as four zero-filled digits")
                    .isEqualTo("1000");
            assertThat(detail.charAt(52))
                    .as("CVTRA07Y:L25, the second FILLER PIC X(01) VALUE '-'")
                    .isEqualTo('-');
            assertThat(detail.substring(53, 82)).startsWith("Regular Sales Draft");
            assertThat(detail.substring(83, 93)).isEqualTo("POS TERM  ");
            assertThat(detail).contains("1,234.56");
        }

        @Test
        @DisplayName("a 50-character description is truncated to the 15 and 29 the report line holds")
        void longDescriptionsAreTruncated() {
            stubCrossReference(CARD_A, ACCOUNT_A);
            Mockito.when(transactionTypeRepository.findAll())
                    .thenReturn(List.of(new TransactionType(TYPE_CD, "A".repeat(50))));
            Mockito.when(transactionCategoryRepository.findAll())
                    .thenReturn(List.of(new TransactionCategory(
                            new TransactionCategoryId(TYPE_CD, CAT_CD), "B".repeat(50))));

            String detail = processor.process(transaction("1.00")).lines().get(4);

            assertThat(detail).hasSize(LINE_LENGTH);
            assertThat(detail.substring(32, 47)).isEqualTo("A".repeat(15));
            assertThat(detail.substring(53, 82)).isEqualTo("B".repeat(29));
        }

        @Test
        @DisplayName("a ReportLines carrying a line of the wrong length is refused")
        void aWronglyWidthedLineIsRefused() {
            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> new ReportLines(List.of("too short")))
                    .satisfies(abend -> {
                        assertThat(abend.getAbendMessage()).isEqualTo("ERROR WRITING REPTFILE");
                        assertThat(abend.getAbendReason())
                                .contains("report line 1 of 1 is 9 characters")
                                .contains("fixed block record length is 133");
                    });
        }

        @Test
        @DisplayName("a ReportLines carrying no lines is refused")
        void anEmptyLineListIsRefused() {
            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> new ReportLines(List.of()))
                    .satisfies(abend -> assertThat(abend.getAbendReason())
                            .contains("the report line list is empty"));
        }

        @Test
        @DisplayName("a ReportLines carrying a null list is refused")
        void aNullLineListIsRefused() {
            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> new ReportLines(null))
                    .satisfies(abend -> assertThat(abend.getAbendReason())
                            .contains("the report line list is null"));
        }

        @Test
        @DisplayName("a ReportLines carrying a null line names its position")
        void aNullLineIsRefused() {
            List<String> lines = new ArrayList<>();
            lines.add(" ".repeat(LINE_LENGTH));
            lines.add(null);

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> new ReportLines(lines))
                    .satisfies(abend -> assertThat(abend.getAbendReason())
                            .contains("report line 2 of 2 is null"));
        }

        @Test
        @DisplayName("the returned line list is an immutable copy")
        void theLineListIsImmutable() {
            stubHappyPath();

            List<String> lines = processor.process(transaction("1.00")).lines();

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> lines.add(" ".repeat(LINE_LENGTH)));
        }
    }

    @Nested
    @DisplayName("6. PRESERVED DEFECT: the end-of-data path counts the stale buffer a second time")
    class EndOfData {

        @Test
        @DisplayName("finishReport emits page total, its trailing rule, then grand total - no account total")
        void finishReportEmitsThreeLines() {
            stubHappyPath();
            processor.process(transaction("10.00"));

            List<String> lines = processor.finishReport().lines();

            // 1120-WRITE-PAGE-TOTALS emits its own trailing rule at :L300-L302, so the end of data yields
            // three records rather than two. 1120-WRITE-ACCOUNT-TOTALS is deliberately not performed here.
            assertThat(lines).hasSize(3);
            assertThat(lines.get(0)).startsWith("Page Total");
            assertThat(lines.get(1)).isEqualTo("-".repeat(LINE_LENGTH));
            assertThat(lines.get(2)).startsWith("Grand Total");
            assertThat(lines).noneMatch(line -> line.startsWith("Account Total"));
        }

        @Test
        @DisplayName("the last record's amount is added a SECOND time, per :L200-L201")
        void theLastAmountIsCountedTwice() {
            stubHappyPath();
            processor.process(transaction("10.00"));

            processor.finishReport();

            // The record contributed 10.00 at :L287, and :L200 adds it again from the stale buffer, so the
            // grand total is 20.00 rather than 10.00. This is the source's arithmetic and is preserved.
            assertThat(processor.grandTotal())
                    .as("the double count of app/cbl/CBTRN03C.cbl:L200-L201, preserved not repaired")
                    .isEqualByComparingTo(new BigDecimal("20.00"));
        }

        @Test
        @DisplayName("finishReport on an empty run treats the stale buffer as zero rather than throwing")
        void finishReportOnAnEmptyRun() {
            List<String> lines = processor.finishReport().lines();

            assertThat(lines).hasSize(3);
            assertThat(processor.grandTotal()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("a FILTERED last record still populates the stale buffer, per :L172")
        void aFilteredRecordStillPopulatesTheBuffer() {
            stubHappyPath();
            processor.process(transaction("10.00"));

            // Outside the period, so it emits nothing - but the source captures TRAN-AMT before the filter,
            // so the buffer now holds 99.00 and the end of data counts that instead.
            processor.process(
                    transaction("0000000000683599", CARD_A, "99.00", "2022-01-01-00.00.00.000000"));
            processor.finishReport();

            assertThat(processor.grandTotal())
                    .as("10.00 from the reported record plus 99.00 from the stale buffer")
                    .isEqualByComparingTo(new BigDecimal("109.00"));
        }

        @Test
        @DisplayName("the two end-of-data DISPLAY lines are reproduced with their exact spacing")
        void theTwoDisplayLinesAreReproduced() {
            stubHappyPath();
            processor.process(transaction("10.00"));
            parityAppender.list.clear();

            processor.finishReport();

            assertThat(parityMessages())
                    .as("the first literal carries a trailing space, the second carries none. Both are "
                            + "emitted verbatim: neither carries an identifier, so an amount in them is not "
                            + "linkable to a cardholder and routing is the only control that applies")
                    .contains("TRAN-AMT 10.00", "WS-PAGE-TOTAL10.00");
        }

        @Test
        @DisplayName("the grand total line does not advance the line counter, by design")
        void theGrandTotalDoesNotAdvanceTheCounter() {
            stubHappyPath();
            processor.process(transaction("10.00"));
            long before = processor.lineCounter();

            processor.finishReport();

            assertThat(processor.lineCounter())
                    .as("the page total advances it twice; :L319-L321 advances it not at all")
                    .isEqualTo(before + 2);
        }
    }

    @Nested
    @DisplayName("7. The three lookups: a miss displays, renders the status and then abends")
    class Lookups {

        @Test
        @DisplayName("a CARDXREF miss abends, masks the key and renders FILE STATUS 0023")
        void aCrossReferenceMissAbends() {
            stubReferenceData();
            Mockito.when(cardCrossReferenceRepository.findById(CARD_A)).thenReturn(Optional.empty());

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> processor.process(transaction("10.00")))
                    .satisfies(abend -> {
                        assertThat(abend.getAbendMessage()).isEqualTo("INVALID CARD NUMBER :");
                        assertThat(abend.getAbendReason()).contains("CARDXREF holds no row");
                    });
            assertThat(loggedMessages())
                    .contains("INVALID CARD NUMBER : ************1111")
                    .contains(fileStatusMapper.displayIoStatus("23"));
        }

        @Test
        @DisplayName("a physical CARDXREF failure abends and preserves the cause the source ignored")
        void aPhysicalCrossReferenceFailureAbends() {
            stubReferenceData();
            QueryTimeoutException timeout = new QueryTimeoutException("xref timeout");
            Mockito.when(cardCrossReferenceRepository.findById(CARD_A)).thenThrow(timeout);

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> processor.process(transaction("10.00")))
                    .satisfies(abend -> {
                        assertThat(abend.getAbendReason())
                                .contains("the CARDXREF read failed physically")
                                .contains("would have continued from a stale buffer");
                        assertThat(abend).hasCause(timeout);
                    });
        }

        @Test
        @DisplayName("a TRANTYPE miss abends and displays the two-character key")
        void aTransactionTypeMissAbends() {
            stubCrossReference(CARD_A, ACCOUNT_A);
            // A table that holds no row for this key: the loaded map misses, exactly as INVALID KEY did.
            Mockito.when(transactionTypeRepository.findAll()).thenReturn(List.of());

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> processor.process(transaction("10.00")))
                    .satisfies(abend -> {
                        assertThat(abend.getAbendMessage()).isEqualTo("INVALID TRANSACTION TYPE :");
                        assertThat(abend.getAbendReason())
                                .contains("TRANTYPE holds no row for transaction type code '01'");
                    });
            assertThat(loggedMessages()).contains("INVALID TRANSACTION TYPE : 01");
        }

        @Test
        @DisplayName("a physical TRANTYPE failure abends and preserves the cause")
        void aPhysicalTransactionTypeFailureAbends() {
            stubCrossReference(CARD_A, ACCOUNT_A);
            QueryTimeoutException timeout = new QueryTimeoutException("trantype timeout");
            Mockito.when(transactionTypeRepository.findAll()).thenThrow(timeout);

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> processor.process(transaction("10.00")))
                    .satisfies(abend -> assertThat(abend).hasCause(timeout));
        }

        @Test
        @DisplayName("a TRANCATG miss abends and displays the six-byte composite key")
        void aTransactionCategoryMissAbends() {
            stubCrossReference(CARD_A, ACCOUNT_A);
            Mockito.when(transactionTypeRepository.findAll())
                    .thenReturn(List.of(new TransactionType(TYPE_CD, "Purchase")));
            Mockito.when(transactionCategoryRepository.findAll()).thenReturn(List.of());

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> processor.process(transaction("10.00")))
                    .satisfies(abend -> {
                        assertThat(abend.getAbendMessage()).isEqualTo("INVALID TRAN CATG KEY :");
                        assertThat(abend.getAbendReason())
                                .contains("the composite key type '01' category 1000");
                    });
            assertThat(loggedMessages())
                    .as("two characters of type then four zero-filled digits of category")
                    .contains("INVALID TRAN CATG KEY : 011000");
        }

        @Test
        @DisplayName("a physical TRANCATG failure abends and preserves the cause")
        void aPhysicalTransactionCategoryFailureAbends() {
            stubCrossReference(CARD_A, ACCOUNT_A);
            Mockito.when(transactionTypeRepository.findAll())
                    .thenReturn(List.of(new TransactionType(TYPE_CD, "Purchase")));
            QueryTimeoutException timeout = new QueryTimeoutException("trancatg timeout");
            Mockito.when(transactionCategoryRepository.findAll()).thenThrow(timeout);

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> processor.process(transaction("10.00")))
                    .satisfies(abend -> assertThat(abend).hasCause(timeout));
        }

        @Test
        @DisplayName("the lookups run in the :L189-L195 order: type before category")
        void theLookupsRunInSourceOrder() {
            stubHappyPath();

            processor.process(transaction("10.00"));

            org.mockito.InOrder order = Mockito.inOrder(cardCrossReferenceRepository,
                    transactionTypeRepository, transactionCategoryRepository);
            order.verify(cardCrossReferenceRepository).findById(CARD_A);
            order.verify(transactionTypeRepository).findAll();
            order.verify(transactionCategoryRepository).findAll();
        }
    }

    @Nested
    @DisplayName("8. Edited amounts: the -ZZZ,ZZZ,ZZZ.ZZ masks of the detail and total lines")
    class EditedAmounts {

        @ParameterizedTest(name = "a detail amount of {0} renders sign {1} and body {2}")
        @CsvSource(delimiter = '|', value = {
            // The edited field is fifteen characters: one sign position then the fourteen of
            // ZZZ,ZZZ,ZZZ.ZZ. Zero suppression blanks every leading digit AND the separators those digits
            // would have carried, so the body is right aligned within the remaining fourteen.
            // A pipe delimiter is mandatory here: the expected bodies contain the grouping COMMA that the
            // mask emits, and a comma-delimited row would silently split on it.
            "1234.56       | SPACE | 1,234.56",
            "1.00          | SPACE | 1.00",
            "-1.00         | MINUS | 1.00",
            "999999999.99  | SPACE | 999,999,999.99",
            "-999999999.99 | MINUS | 999,999,999.99",
            "0.01          | SPACE | .01",
            "-0.01         | MINUS | .01"
        })
        @DisplayName("zero suppression blanks leading digits and the separators they would carry")
        void theDetailMaskSuppressesLeadingZeros(final String amount, final String sign,
                                                 final String body) {
            stubHappyPath();

            String detail = processor.process(transaction(amount)).lines().get(4);

            String expected = ("MINUS".equals(sign) ? "-" : " ")
                    + " ".repeat(EDITED_AMOUNT_WIDTH - 1 - body.length()) + body;
            assertThat(detail).hasSize(LINE_LENGTH);
            assertThat(detail.substring(DETAIL_AMOUNT_START, DETAIL_AMOUNT_START + EDITED_AMOUNT_WIDTH))
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("a zero detail amount renders as fifteen spaces, because every digit is suppressed")
        void aZeroDetailAmountRendersBlank() {
            stubHappyPath();

            String detail = processor.process(transaction("0.00")).lines().get(4);

            assertThat(detail).hasSize(LINE_LENGTH);
            assertThat(detail.substring(DETAIL_AMOUNT_START, DETAIL_AMOUNT_START + EDITED_AMOUNT_WIDTH))
                    .as("the mask has no unsuppressed position, so the whole field is blank")
                    .isBlank();
        }

        @Test
        @DisplayName("a total amount carries a mandatory sign, unlike a detail amount")
        void aTotalAmountCarriesAMandatorySign() {
            stubHappyPath();
            processor.process(transaction("10.00"));

            String pageTotal = processor.finishReport().lines().get(0);

            assertThat(pageTotal)
                    .as("the total mask leads with + where the detail mask leads with a space")
                    .contains("+")
                    .hasSize(LINE_LENGTH);
        }

        @Test
        @DisplayName("a negative total renders its minus sign in the leading position")
        void aNegativeTotalRendersItsSign() {
            stubHappyPath();
            processor.process(transaction("-10.00"));

            String pageTotal = processor.finishReport().lines().get(0);

            assertThat(pageTotal).contains("-").contains("20.00");
        }

        @Test
        @DisplayName("an absent TRAN-AMT abends rather than defaulting to zero")
        void anAbsentAmountAbends() {
            stubHappyPath();
            Transaction item = transaction("10.00");
            setField(item, "amount", null);

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> processor.process(item))
                    .satisfies(abend -> assertThat(abend.getAbendReason())
                            .contains("TRAN-AMT is absent on transaction '0000000000683580'")
                            .contains("PIC S9(09)V99 cannot be unset"));
        }

        @Test
        @DisplayName("an absent TRAN-CAT-CD abends when the category key is assembled")
        void anAbsentCategoryCodeAbends() {
            stubHappyPath();
            Transaction item = transaction("10.00");
            setField(item, "categoryCode", null);

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> processor.process(item))
                    .satisfies(abend -> {
                        assertThat(abend.getAbendMessage()).isEqualTo("INVALID TRAN CATG KEY :");
                        assertThat(abend.getAbendReason())
                                .contains("TRAN-CAT-CD is absent")
                                .contains("six byte TRANCATG key");
                    });
        }

        @Test
        @DisplayName("an absent XREF-ACCT-ID abends at detail time")
        void anAbsentAccountIdAbends() {
            stubReferenceData();
            CardCrossReference xref = new CardCrossReference(CARD_A, 123456789L, ACCOUNT_A);
            setField(xref, "accountId", null);
            Mockito.when(cardCrossReferenceRepository.findById(CARD_A)).thenReturn(Optional.of(xref));

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> processor.process(transaction("10.00")))
                    .satisfies(abend -> assertThat(abend.getAbendReason())
                            .contains("XREF-ACCT-ID is absent")
                            .contains("PIC 9(11) cannot be unset"));
        }
    }

    @Nested
    @DisplayName("9. Diagnostic hygiene: the card number is masked wherever it is reported at all")
    class DiagnosticHygiene {

        @Test
        @DisplayName("no log record carries the card number in the clear")
        void noLogRecordCarriesTheCardNumberInTheClear() {
            stubHappyPath();

            processor.process(transaction("10.00"));
            processor.process(
                    transaction("0000000000683581", CARD_B, "20.00", "2022-06-10-19.27.53.000000"));
            processor.finishReport();

            assertThat(loggedMessages())
                    .noneMatch(message -> message.contains(CARD_A) || message.contains(CARD_B));
        }

        @Test
        @DisplayName("the record display masks all but the last four digits")
        void theRecordDisplayMasksAllButFourDigits() {
            stubHappyPath();

            processor.process(transaction("10.00"));

            assertThat(parityMessages())
                    .anyMatch(message -> message.contains("TRAN-RECORD id=0000000000683580")
                            && message.contains("card=************1111"));
        }

        @Test
        @DisplayName("an absent card number renders the fixed placeholder rather than throwing")
        void anAbsentCardNumberRendersAPlaceholder() {
            stubReferenceData();
            Transaction item = transaction("10.00");
            setField(item, "cardNumber", null);

            // An absent card number space-fills to sixteen, which is exactly the initial value of the break
            // key, so no control break occurs and CARDXREF is never read. The abend therefore comes from the
            // empty cross-reference buffer at detail time rather than from a failed lookup - and the
            // diagnostic still renders the placeholder rather than dereferencing the null.
            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> processor.process(item))
                    .satisfies(abend -> assertThat(abend.getAbendReason())
                            .contains("the CARDXREF buffer is empty at detail time"));
            Mockito.verifyNoInteractions(cardCrossReferenceRepository);
            assertThat(parityMessages())
                    .as("a diagnostic must never be the thing that throws")
                    .anyMatch(message -> message.contains("card=(absent)"));
        }
    }

    /**
     * Sets one declared field of an entity to a chosen value, so a test can express an absent
     * {@code NOT NULL} column that the entity's own constructor and setter both refuse.
     *
     * @param target the entity to mutate
     * @param fieldName the declared field name
     * @param value the value to set, typically {@code null}
     * @param <T> the entity type
     * @return the same instance, for chaining
     */
    private static <T> T setField(final T target, final String fieldName, final Object value) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
            return target;
        } catch (ReflectiveOperationException reflection) {
            throw new IllegalStateException("Field " + fieldName + " could not be set on "
                    + target.getClass().getSimpleName(), reflection);
        }
    }
}

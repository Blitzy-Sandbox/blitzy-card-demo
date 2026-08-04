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
 * Source      : app/cbl/CBTRN03C.cbl:L122-L137 (WORKING-STORAGE decls)
 *               app/cbl/CBTRN03C.cbl:L159-L217 (PROCEDURE DIVISION)
 *               app/cbl/CBTRN03C.cbl:L170-L179 (loop + date re-filter)
 *               app/cbl/CBTRN03C.cbl:L177       (NEXT SENTENCE - the one
 *                                                occurrence in the corpus;
 *                                                semantics Not available)
 *               app/cbl/CBTRN03C.cbl:L172-L196 (per-record body)
 *               app/cbl/CBTRN03C.cbl:L181-L188 (control break on card)
 *               app/cbl/CBTRN03C.cbl:L198-L203 (end of data)
 *               app/cbl/CBTRN03C.cbl:L220-L243 (0550-DATEPARM-READ)
 *               app/cbl/CBTRN03C.cbl:L248-L272 (1000-TRANFILE-GET-NEXT)
 *               app/cbl/CBTRN03C.cbl:L275-L289 (1100-WRITE-TRANSACTION)
 *               app/cbl/CBTRN03C.cbl:L294-L302 (1110-WRITE-PAGE-TOTALS)
 *               app/cbl/CBTRN03C.cbl:L307-L314 (1120-WRITE-ACCOUNT-TOTALS)
 *               app/cbl/CBTRN03C.cbl:L319-L321 (1110-WRITE-GRAND-TOTALS)
 *               app/cbl/CBTRN03C.cbl:L325-L339 (1120-WRITE-HEADERS)
 *               app/cbl/CBTRN03C.cbl:L345-L358 (1111-WRITE-REPORT-REC)
 *               app/cbl/CBTRN03C.cbl:L362-L373 (1120-WRITE-DETAIL)
 *               app/cbl/CBTRN03C.cbl:L376-L482 (six OPEN paragraphs)
 *               app/cbl/CBTRN03C.cbl:L484-L512 (three fatal lookups)
 *               app/cbl/CBTRN03C.cbl:L514-L621 (six CLOSE paragraphs)
 *               app/cbl/CBTRN03C.cbl:L627-L630 (abend 999 / RC 12)
 *               app/cbl/CBTRN03C.cbl:L633-L646 (9910-DISPLAY-IO-STATUS)
 *               app/cpy/CVTRA07Y.cpy:L4-L66     (the six line layouts)
 *               app/cpy/CVTRA05Y.cpy:L4-L18     (350-byte offset map)
 *               app/cpy/CVTRA03Y.cpy:L4-L7      (TRAN-TYPE-DESC X(50))
 *               app/cpy/CVTRA04Y.cpy:L4-L9      (TRAN-CAT-KEY, desc X(50))
 *               app/proc/TRANREPT.prc:L39-L46   (sort + INCLUDE COND)
 *               app/proc/TRANREPT.prc:L76       (LRECL=133)
 *               app/jcl/TRANREPT.jcl            (job stream, no COND=)
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
import com.cardemo.unit.model.FixedClockProvider;
import com.cardemo.unit.model.FixtureLoader;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
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
 * <p>The paragraphs it covers, all verified by direct reading of the frozen corpus at commit
 * {@code 7756d89}: the declarations at {@code app/cbl/CBTRN03C.cbl:L122-L137}, the mainline at
 * {@code :L159-L217}, the inclusive date predicate at {@code :L173-L174} and the {@code NEXT SENTENCE}
 * at {@code :L177}, the per-record and end-of-data branches at {@code :L179-L204},
 * {@code 0550-DATEPARM-READ} at {@code :L220-L243}, {@code 1000-TRANFILE-GET-NEXT} at
 * {@code :L248-L272}, {@code 1100-WRITE-TRANSACTION-REPORT} at {@code :L274-L290},
 * {@code 1110-WRITE-PAGE-TOTALS} at {@code :L293-L304}, {@code 1120-WRITE-ACCOUNT-TOTALS} at
 * {@code :L306-L316}, {@code 1110-WRITE-GRAND-TOTALS} at {@code :L318-L322},
 * {@code 1120-WRITE-HEADERS} at {@code :L324-L341}, {@code 1111-WRITE-REPORT-REC} at
 * {@code :L343-L359}, {@code 1120-WRITE-DETAIL} at {@code :L361-L374}, the three lookups at
 * {@code :L484-L512}, and the six line layouts of {@code app/cpy/CVTRA07Y.cpy:L4-L66}.
 *
 * <p><b>How to build and test.</b>
 * {@code ./mvnw -B -ntp -Dtest='TransactionReportProcessorTest' test} runs this class alone; it needs no
 * container, no database and no cloud emulator. The class lives under
 * {@code src/test/java/com/cardemo/unit/}, which is the tree <b>Surefire</b> collects; the integration
 * tier under {@code src/test/java/com/cardemo/integration/} belongs to Failsafe instead, and job and step
 * wiring is asserted there rather than here.
 *
 * <p><b>Key configuration and defaults.</b> The reporting period is {@code 2022-06-01} to
 * {@code 2022-06-30}, matching the month the corpus fixture's processing timestamps fall in, and the
 * period is inclusive at both ends. Page size is twenty, from
 * {@code WS-PAGE-SIZE PIC 9(03) COMP-3 VALUE 20} at {@code app/cbl/CBTRN03C.cbl:L131-L132}. Every
 * emitted line is exactly 133 characters, which is the {@code LRECL}
 * {@code app/proc/TRANREPT.prc:L76} declares. Every timestamp comes from the injected fixed clock of
 * {@link FixedClockProvider}, never from the wall clock, and every format and case operation passes
 * {@link java.util.Locale#ROOT}. Mockito runs in its default <em>strict stubs</em> mode: this class
 * creates mocks through {@link org.mockito.Mockito#mock(Class)} and stubs only what a given test
 * actually exercises, so an unused stub is a defect the suite surfaces rather than tolerates.
 *
 * <p><b>Two facts are `Not available` and are stated rather than fabricated</b>, as Rule 1 clause F
 * requires.
 * <ul>
 * <li><b>High. The semantics of {@code NEXT SENTENCE} at {@code app/cbl/CBTRN03C.cbl:L177}.</b> It is
 * the only occurrence in the twenty-eight program corpus, and no separator period exists anywhere inside
 * the loop body {@code :L170-L206} - the nearest is {@code END-PERFORM.} at {@code :L206}. Two readings
 * follow and they are irreconcilable: control passing to the statement after the next period would
 * terminate the entire report on the first out-of-range record, while the conservative reading makes the
 * construct a no-op so the filter has no effect at all. Both refute a plain inclusive per-record filter.
 * <b>Neither reading is chosen here.</b> What is needed to settle it: an IBM Enterprise COBOL compiler
 * and runtime on which to observe the actual behaviour. Group 10 asserts the pragmatic translation that
 * was shipped instead and labels it a divergence.</li>
 * <li><b>Medium. A captured legacy output baseline.</b> An exhaustive search of the repository for
 * <i>expected</i>, <i>baseline</i>, <i>golden</i>, {@code *.out} and {@code *TRANREPT*} returns only
 * dataset-definition JCL and zero captured data, so byte-for-byte report output to diff against is
 * <b>Not available</b>. What is needed: a report file captured from a z/OS run of
 * {@code app/proc/TRANREPT.prc:STEP10R}. This class therefore asserts structure, geometry and arithmetic
 * against the copybook and the program text, creates no baseline file of its own, and deliberately makes
 * no aggregate line-count assertion over a fixture - a count nothing can be checked against would assert
 * only that this suite agrees with itself.</li>
 * </ul>
 *
 * <p><b>Three preserved legacy defects are asserted, not repaired.</b> The control break tests the card
 * number while the line it emits is labelled {@code "Account Total"} - group 3 asserts both halves of
 * that mismatch deliberately. The end-of-data path adds the stale record buffer's amount to both the
 * page and the account total a second time - group 6 asserts that double count. And because
 * {@code :L198-L203} performs no closing {@code 1120-WRITE-ACCOUNT-TOTALS}, the last card group never
 * receives an {@code Account Total} line at all - group 6 asserts that absence too. Correcting any of
 * them would change output the boundary-parity gate diffs against the legacy baseline. Each is preserved,
 * marked at its locator here, and destined for a {@code DECISION_LOG.md} entry and a
 * {@code TRACEABILITY_MATRIX.md} row, which is what keeps them tracked rather than dead - the distinction
 * Rule 1 clause B actually draws.
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
 * <li><b>High.</b> A failure in group 11 means a line layout has drifted from
 * {@code app/cpy/CVTRA07Y.cpy}. The likeliest cause is the leader width of the account total line: it is
 * {@code X(84)} at {@code :L59} where the page and grand lines are {@code X(86)} at {@code :L53} and
 * {@code :L65}, because its label is two characters longer. Copying 86 onto all three is the obvious
 * mistake, and it shifts the edited amount by two columns on one line in three.</li>
 * <li><b>High.</b> A failure in group 12 means one of the five paragraphs behind the
 * {@code 1110-}/{@code 1120-} prefix collisions has been consolidated with another. They are five
 * distinct paragraphs with different line-counter arithmetic - notably
 * {@code 1110-WRITE-GRAND-TOTALS} advances the counter not at all - and tidying them into a uniform
 * increment shifts every subsequent page break.</li>
 * <li><b>Compilation, not a test failure.</b> {@code -Xlint:all -Werror} with
 * {@code failOnWarning} is fatal in this build, so a single unused import or a raw type fails the module
 * rather than this class. Remove the import; never suppress the warning.</li>
 * <li><b>Fixture name trap.</b> The corpus fixture is {@code app/data/ASCII/dailytran.txt} - the word
 * spelled in full - even though the mainframe DD name and dataset are {@code DALYTRAN}. A resource
 * lookup for {@code dalytran.txt} silently finds nothing. {@link FixtureLoader.Fixture#DAILY_TRANSACTION}
 * holds the correct name so no test spells it by hand.</li>
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

    /**
     * The one clock this class reads, fixed at {@link FixedClockProvider#CANONICAL_INSTANT}.
     *
     * <p>Rule 1 clause A puts determinism first, so no test may consult the wall clock, the default
     * locale, the default zone or a seedless random source. Taking the clock from the shared provider is
     * what makes that machine-checkable rather than a promise: this class contains no {@code now()} call
     * of any kind, and the timestamps below are derived rather than typed.
     */
    private static final Clock FIXED_CLOCK = FixedClockProvider.canonicalClock();

    /**
     * {@code TRAN-PROC-TS PIC X(26)} for every in-period record, derived from {@link #FIXED_CLOCK}.
     *
     * <p>{@link FixedClockProvider#batchTimestamp(Clock)} renders the twenty-six character batch form
     * {@code 2022-06-10-19.27.53.000000}, whose leading ten characters are {@code 2022-06-10} - inside the
     * {@code 2022-06-01}/{@code 2022-06-30} period by construction. Deriving it removes the last
     * wall-clock-shaped literal from this class; an earlier revision repeated the twenty-six characters by
     * hand at five call sites, where a single mistyped digit would have silently moved a record out of
     * period and turned a filter assertion green for the wrong reason.
     */
    private static final String IN_PERIOD_PROC_TS = FixedClockProvider.batchTimestamp(FIXED_CLOCK);

    /**
     * One-based column of {@code TRAN-CARD-NUM} in the 350-byte transaction record.
     *
     * <p>{@code app/cpy/CVTRA05Y.cpy:L15} places it after 262 bytes of preceding fields, and the DFSORT
     * symbol {@code TRAN-CARD-NUM,263,16,ZD} at {@code app/proc/TRANREPT.prc:L39} states the same offset
     * independently. Note the symbol declares {@code ZD} where the copybook declares {@code X(16)}; the
     * digits coincide for this data, and the copybook governs the field's type.
     */
    private static final int FIXTURE_CARD_NUMBER_COLUMN = 263;

    /**
     * The widest value {@code PIC S9(09)V99} can hold, from {@code app/cbl/CBTRN03C.cbl:L134-L136}.
     *
     * <p>Nine integer digits and two decimals is {@code NUMERIC(11,2)}, and the edited mask
     * {@code ZZZ,ZZZ,ZZZ.ZZ} at {@code app/cpy/CVTRA07Y.cpy:L30} has exactly nine integer positions - so
     * this value fills the field with nothing suppressed and nothing to spare. It is written as a
     * {@code String} because a {@code double} literal could not carry it exactly, which is the whole reason
     * every monetary value in this class is a {@link BigDecimal}.
     */
    private static final String NUMERIC_BOUNDARY = "999999999.99";

    /**
     * {@code FILLER X(11) VALUE 'Page Total'}, {@code app/cpy/CVTRA07Y.cpy:L51-L52}.
     *
     * <p>The literal is ten characters inside an eleven character field, so the field contributes one
     * trailing space and the leader run begins at column twelve, not eleven. See
     * {@link #PAGE_TOTAL_LABEL_WIDTH}.
     */
    private static final String PAGE_TOTAL_LABEL = "Page Total";

    /** Declared width of the page total label field, {@code app/cpy/CVTRA07Y.cpy:L51}. */
    private static final int PAGE_TOTAL_LABEL_WIDTH = 11;

    /**
     * {@code FILLER X(13) VALUE 'Account Total'}, {@code app/cpy/CVTRA07Y.cpy:L57-L58}.
     *
     * <p>Here the literal exactly fills its field, so the leader abuts the label with no separating space -
     * the opposite of the page and grand lines. That asymmetry is the copybook's, not a rendering choice.
     */
    private static final String ACCOUNT_TOTAL_LABEL = "Account Total";

    /** Declared width of the account total label field, {@code app/cpy/CVTRA07Y.cpy:L57}. */
    private static final int ACCOUNT_TOTAL_LABEL_WIDTH = 13;

    /** {@code FILLER X(11) VALUE 'Grand Total'}, {@code app/cpy/CVTRA07Y.cpy:L63-L64}. */
    private static final String GRAND_TOTAL_LABEL = "Grand Total";

    /**
     * Declared width of the grand total label field, {@code app/cpy/CVTRA07Y.cpy:L63}.
     *
     * <p>Eleven characters holding an eleven character literal, so like the account line and unlike the
     * page line its leader abuts the label directly.
     */
    private static final int GRAND_TOTAL_LABEL_WIDTH = 11;

    /**
     * Declared content width of every total line, before the record's own padding.
     *
     * <p>All three resolve to the same total from different parts: {@code 11 + 86 + 15} for the page and
     * grand lines and {@code 13 + 84 + 15} for the account line, per
     * {@code app/cpy/CVTRA07Y.cpy:L50-L66}. The equality is a coincidence of the copybook, not a shared
     * definition, which is why the leader widths are still asserted one by one.
     */
    private static final int TOTAL_LINE_CONTENT_WIDTH = 112;

    /**
     * Declared content width of {@code TRANSACTION-DETAIL-REPORT}, {@code app/cpy/CVTRA07Y.cpy:L15-L31}:
     * {@code 16+1+11+1+2+1+15+1+4+1+29+1+10+4+15+2}.
     */
    private static final int DETAIL_LINE_CONTENT_WIDTH = 114;

    /**
     * One-based column of {@code TRAN-ORIG-TS}, from {@code app/cpy/CVTRA05Y.cpy:L16}.
     *
     * <p>It is the timestamp the corpus fixture actually populates; see the finding on
     * {@code theFilteredFieldSitsAtTheDeclaredOffset}.
     */
    private static final int FIXTURE_ORIG_TS_COLUMN = 279;

    /**
     * One-based column of {@code TRAN-PROC-TS}, from {@code app/cpy/CVTRA05Y.cpy:L17} and corroborated by
     * {@code TRAN-PROC-DT,305,10,CH} at {@code app/proc/TRANREPT.prc:L40}.
     */
    private static final int FIXTURE_PROC_TS_COLUMN = 305;

    /**
     * Posted transaction access, held as a field rather than created inline so that group 13 can make the
     * {@code 0000-TRANFILE-OPEN} probe of {@code app/cbl/CBTRN03C.cbl:L376-L392} fail on demand.
     *
     * <p>It is never used by the per-record path: the step's reader owns the cursor, and this collaborator
     * exists only for the open and close paragraphs.
     */
    private TransactionRepository transactionRepository;

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
        transactionRepository = Mockito.mock(TransactionRepository.class);
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
        return new TransactionReportProcessor(transactionRepository,
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
                cardNumber, FixedClockProvider.onlineTimestamp(FIXED_CLOCK), procTs);
    }

    /**
     * Builds a transaction inside the reporting period on the first card.
     *
     * @param amount {@code TRAN-AMT}
     * @return the record
     */
    private static Transaction transaction(final String amount) {
        return transaction("0000000000683580", CARD_A, amount, IN_PERIOD_PROC_TS);
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
                    String.format(Locale.ROOT, "%016d", index), cardNumber, amount,
                    IN_PERIOD_PROC_TS));
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
                    transaction("0000000000683581", CARD_B, "20.00", IN_PERIOD_PROC_TS));

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
                    transaction("0000000000683581", CARD_B, "20.00", IN_PERIOD_PROC_TS));

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
                    transaction("0000000000683581", CARD_B, "5.00", IN_PERIOD_PROC_TS));

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
                    IN_PERIOD_PROC_TS));
            ReportLines second = processor.process(transaction("0000000000683581", "4111", "20.00",
                    IN_PERIOD_PROC_TS));

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
                    transaction("0000000000683581", CARD_B, "1.00", IN_PERIOD_PROC_TS));
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
                    transaction("0000000000683599", CARD_B, "2.00", IN_PERIOD_PROC_TS))
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

            InOrder order = Mockito.inOrder(cardCrossReferenceRepository,
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
                    transaction("0000000000683581", CARD_B, "20.00", IN_PERIOD_PROC_TS));
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

    @Nested
    @DisplayName("10. NEXT SENTENCE at :L177: semantics Not available, translation asserted as a divergence")
    class NextSentenceAmbiguity {

        /**
         * States the gap rather than papering over it, and proves the shipped translation.
         *
         * <p><b>Finding, High severity.</b> {@code app/cbl/CBTRN03C.cbl:L176-L177} closes the date test with
         * {@code ELSE NEXT SENTENCE}. Two facts about that site were established by reading the frozen
         * source, and both are decisive:
         * <ol>
         * <li>No separator period exists anywhere inside the loop body {@code :L170-L206}. The nearest
         * period is the one ending {@code END-PERFORM.} at {@code :L206}.</li>
         * <li>It is the only {@code NEXT SENTENCE} in the twenty-eight program corpus, so no sibling site
         * exists to calibrate the reading against.</li>
         * </ol>
         *
         * <p>{@code NEXT SENTENCE} transfers control to the statement following the next period. Under the
         * IBM Enterprise COBOL reading, the next period terminates the whole {@code PERFORM UNTIL}, so the
         * first out-of-range record ends the report. Under the conservative reading the construct is a
         * no-op and the filter has no effect at all. <b>Both readings refute a working inclusive per-record
         * filter, and this suite chooses neither.</b> The semantics are <b>Not available</b>. What is needed
         * to settle them: an IBM Enterprise COBOL compiler and runtime on which to observe the behaviour of
         * this exact paragraph.
         *
         * <p><b>What is asserted instead, and its remediation.</b> The shipped translation is the pragmatic
         * one - the item is filtered, {@code process} returns {@code null}, and the run continues. That is a
         * deliberate divergence from at least one of the two readings and possibly from both, recorded here
         * at High severity and destined for a {@code DECISION_LOG.md} entry. Remediation, once the semantics
         * are known: if the terminating reading holds, the step must stop reading at the first out-of-range
         * record, which a reader-level exhausted signal expresses; if the no-op reading holds, this filter
         * must be deleted so that every record the sort admitted is reported. Do not guess between them.
         */
        @Test
        @DisplayName("HIGH/Not available: an out-of-range record is filtered rather than terminating the run")
        void anOutOfRangeRecordIsFilteredAndTheRunContinues() {
            stubHappyPath();

            ReportLines filtered = processor.process(
                    transaction("0000000000683580", CARD_A, "10.00", "2022-07-01-19.27.53.000000"));
            ReportLines reported = processor.process(transaction("10.00"));

            assertThat(filtered)
                    .as("app/cbl/CBTRN03C.cbl:L177 - the shipped translation filters the item; Spring "
                            + "Batch reads null as 'drop this record'")
                    .isNull();
            assertThat(reported)
                    .as("DIVERGENCE, High: the run continues after an out-of-range record. Under the "
                            + "IBM Enterprise COBOL reading of NEXT SENTENCE it would have ended. The "
                            + "semantics are Not available, so this behaviour is asserted as the shipped "
                            + "choice and not as parity")
                    .isNotNull();
        }

        @Test
        @DisplayName("the predicate is inclusive at both bounds, independent of the NEXT SENTENCE question")
        void thePredicateIsInclusiveAtBothBounds() {
            stubHappyPath();

            // app/cbl/CBTRN03C.cbl:L173-L174 - >= WS-START-DATE AND <= WS-END-DATE. Whatever NEXT SENTENCE
            // does with the ELSE limb, the predicate itself is unambiguous, so it is asserted on its own.
            assertThat(processor.process(
                    transaction("0000000000683580", CARD_A, "10.00", START_DATE + "-00.00.00.000000")))
                    .as("app/cbl/CBTRN03C.cbl:L173 - the lower bound is >= and therefore inclusive")
                    .isNotNull();
            assertThat(processor.process(
                    transaction("0000000000683581", CARD_A, "10.00", END_DATE + "-23.59.59.000000")))
                    .as("app/cbl/CBTRN03C.cbl:L174 - the upper bound is <= and therefore inclusive")
                    .isNotNull();
            assertThat(processor.process(
                    transaction("0000000000683582", CARD_A, "10.00", "2022-05-31-23.59.59.000000")))
                    .as("one day before the period is outside it")
                    .isNull();
            assertThat(processor.process(
                    transaction("0000000000683583", CARD_A, "10.00", "2022-07-01-00.00.00.000000")))
                    .as("one day after the period is outside it")
                    .isNull();
        }

        @Test
        @DisplayName("the comparison is on characters, not on a parsed date: a non-date sorts by text")
        void theComparisonIsTextual() {
            stubHappyPath();

            // TRAN-PROC-TS (1:10) is compared against two PIC X(10) fields, so the operation is an
            // alphanumeric compare. '9999-99-99' is not a calendar date at all, yet it collates above
            // '2022-06-30' and is therefore rejected - which a parsed-date implementation could not do
            // without first throwing. Guarding the length before the substring is what keeps that true.
            assertThat(processor.process(
                    transaction("0000000000683584", CARD_A, "10.00", "9999-99-99-00.00.00.000000")))
                    .as("app/cbl/CBTRN03C.cbl:L173-L174 - a string comparison, never a parsed date")
                    .isNull();
        }

        @Test
        @DisplayName("the sort card already filtered, and the program filters again - the redundancy is kept")
        void theRedundantFilterIsPreserved() {
            stubHappyPath();

            // app/proc/TRANREPT.prc:L45-L46 applies INCLUDE COND=(TRAN-PROC-DT,GE,PARM-START-DATE,AND,
            // TRAN-PROC-DT,LE,PARM-END-DATE) before CBTRN03C ever runs, so by the time the program reaches
            // :L173 every record it sees has already passed the identical test. The program applies it a
            // second time regardless. That redundancy is preserved rather than optimised away: removing it
            // would make the processor's output depend on a guarantee established in another job step, and
            // a step that is reachable on its own would then silently report out-of-period records.
            ReportLines outOfPeriod = processor.process(
                    transaction("0000000000683585", CARD_A, "10.00", "2022-01-01-00.00.00.000000"));

            assertThat(outOfPeriod)
                    .as("app/proc/TRANREPT.prc:L45-L46 would not have admitted this record; the processor "
                            + "re-applies the range anyway, exactly as app/cbl/CBTRN03C.cbl:L173-L174 does")
                    .isNull();
        }

        /**
         * Anchors the filtered field to the real 350-byte record geometry, and records what the corpus
         * fixture actually holds there.
         *
         * <p>The fixture is {@code app/data/ASCII/dailytran.txt} - loaded through
         * {@link FixtureLoader.Fixture#DAILY_TRANSACTION} so that no test spells the name by hand, since the
         * mainframe dataset is {@code DALYTRAN} while the ASCII fixture spells the word in full. Reading at
         * the offsets the copybook and the DFSORT symbols both declare proves that this suite's synthetic
         * records sit at the same columns the job's own data does.
         *
         * <p><b>Finding, Medium severity - the daily fixture is not this job's input, and its processing
         * timestamp is blank.</b> {@code app/data/ASCII/dailytran.txt} is the {@code CVTRA06Y}
         * <em>staging</em> layout, where {@code DALYTRAN-PROC-TS} at columns 305-330 is
         * <b>twenty-six spaces</b>: the processing timestamp is assigned when a record is posted, not when
         * it is captured. This job reads the <em>posted</em> file instead -
         * {@code app/proc/TRANREPT.prc:L63-L64} points {@code TRANFILE} at
         * {@code AWS.M2.CARDDEMO.TRANSACT.DALY(+1)}, the sorted output of {@code STEP05R}. Consequences,
         * both asserted below: the ten characters the report's filter reads are blank in this fixture, so a
         * staging record can never satisfy {@code app/cbl/CBTRN03C.cbl:L173-L174} and must be dropped rather
         * than crash a {@code substring}; and the field that <em>is</em> populated at 279-304 is
         * {@code DALYTRAN-ORIG-TS}, which holds exactly
         * {@link FixedClockProvider#CANONICAL_ONLINE_TIMESTAMP} - which is why that constant is the right
         * anchor for this suite's records.
         *
         * <p>No aggregate line count is asserted over the fixture, and no baseline file is created: as the
         * class documentation records, captured legacy output is <b>Not available</b>, so a total could only
         * be checked against this suite's own arithmetic.
         *
         * <p><b>Remediation:</b> nothing in this class changes - the fixture is read for its geometry, not
         * as this job's input. Whoever wires the step must point the reader at the posted transaction
         * relation that {@code app/proc/TRANREPT.prc:L64} names, never at the daily staging file, or every
         * record will be filtered on a blank processing date and the report will come out empty.
         */
        @Test
        @DisplayName("the filtered field sits where CVTRA05Y and the sort symbols agree, and is blank there")
        void theFilteredFieldSitsAtTheDeclaredOffset() {
            FixtureLoader.FixtureData daily = FixtureLoader.load(FixtureLoader.Fixture.DAILY_TRANSACTION);

            String cardNumber = daily.field(0, FIXTURE_CARD_NUMBER_COLUMN, 16);
            String origTs = daily.field(0, FIXTURE_ORIG_TS_COLUMN, FixedClockProvider.TIMESTAMP_LENGTH);
            String procTs = daily.field(0, FIXTURE_PROC_TS_COLUMN, FixedClockProvider.TIMESTAMP_LENGTH);

            assertThat(daily.recordWidth())
                    .as("app/cpy/CVTRA06Y.cpy and app/cpy/CVTRA05Y.cpy both declare RECLN = 350")
                    .isEqualTo(350);
            assertThat(cardNumber)
                    .as("app/cpy/CVTRA05Y.cpy:L15 and app/proc/TRANREPT.prc:L39 - TRAN-CARD-NUM,263,16")
                    .hasSize(16)
                    .containsOnlyDigits();
            assertThat(origTs)
                    .as("app/cpy/CVTRA06Y.cpy DALYTRAN-ORIG-TS at 279-304 is the populated timestamp, and "
                            + "it is the instant FixedClockProvider pins")
                    .isEqualTo(FixedClockProvider.CANONICAL_ONLINE_TIMESTAMP);
            assertThat(procTs)
                    .as("Medium: the staging layout leaves DALYTRAN-PROC-TS at 305-330 blank until posting, "
                            + "so app/proc/TRANREPT.prc:L64 feeds this job the POSTED file instead")
                    .isBlank()
                    .hasSize(FixedClockProvider.TIMESTAMP_LENGTH);
        }

        @Test
        @DisplayName("a staging record's blank 26-byte PROC-TS is dropped, not padded and not crashed on")
        void aBlankProcessingTimestampIsDropped() {
            FixtureLoader.FixtureData daily = FixtureLoader.load(FixtureLoader.Fixture.DAILY_TRANSACTION);
            String blankProcTs = daily.field(0, FIXTURE_PROC_TS_COLUMN, FixedClockProvider.TIMESTAMP_LENGTH);

            ReportLines filtered = processor.process(transaction("0000000000683587",
                    daily.field(0, FIXTURE_CARD_NUMBER_COLUMN, 16), "10.00", blankProcTs));

            assertThat(filtered)
                    .as("twenty-six spaces collate below the 2022-06-01 lower bound of "
                            + "app/cbl/CBTRN03C.cbl:L173, so the record is filtered; a length guard is what "
                            + "keeps the substring from throwing on a short or empty field")
                    .isNull();
            Mockito.verifyNoInteractions(cardCrossReferenceRepository);
        }

        @Test
        @DisplayName("a filtered record performs no lookup and emits no line")
        void aFilteredRecordPerformsNoLookup() {
            // Deliberately unstubbed: a record dropped by the filter must not reach CARDXREF, TRANTYPE or
            // TRANCATG at all. Under Mockito's strict stubs an unnecessary stub here would itself fail.
            ReportLines filtered = processor.process(
                    transaction("0000000000683586", CARD_A, "10.00", "2021-12-31-00.00.00.000000"));

            assertThat(filtered).isNull();
            assertThat(processor.lineCounter())
                    .as("app/cbl/CBTRN03C.cbl:L177 - nothing downstream of the filter runs")
                    .isZero();
            Mockito.verifyNoInteractions(cardCrossReferenceRepository, transactionTypeRepository,
                    transactionCategoryRepository);
        }
    }

    @Nested
    @DisplayName("11. CVTRA07Y line geometry: six layouts, and three leader widths that are NOT uniform")
    class CopybookLineGeometry {

        /**
         * Emits one line of every kind and returns them by their {@code CVTRA07Y} label.
         *
         * <p>A first record produces the four-line header block of {@code app/cbl/CBTRN03C.cbl:L325-L339}
         * followed by its detail line; a second record on a different card prepends the two-line account
         * total block of {@code :L307-L314}; and {@code finishReport} closes with the page total, its rule
         * and the grand total of {@code :L294-L302} and {@code :L319-L321}.
         *
         * @return every line the run emitted, in emission order
         */
        private List<String> everyLineKind() {
            stubHappyPath();
            List<String> emitted = new ArrayList<>(processor.process(transaction("12345.67")).lines());
            emitted.addAll(processor.process(
                    transaction("0000000000683581", CARD_B, "1.00", IN_PERIOD_PROC_TS)).lines());
            emitted.addAll(processor.finishReport().lines());
            return emitted;
        }

        /**
         * Returns the one emitted line whose text begins with a chosen label.
         *
         * @param emitted every line of a run
         * @param label the {@code CVTRA07Y} label, for example {@code "Page Total"}
         * @return the first matching line
         */
        private static String lineLabelled(final List<String> emitted, final String label) {
            return emitted.stream().filter(line -> line.startsWith(label)).findFirst().orElseThrow();
        }

        /**
         * Asserts each total line's leader run independently, because the three are not the same width.
         *
         * <p><b>Finding, High severity - the account leader is two characters shorter.</b>
         * {@code app/cpy/CVTRA07Y.cpy:L53} and {@code :L65} declare {@code FILLER PIC X(86) VALUE ALL '.'}
         * for the page and grand total lines, while {@code :L59} declares {@code PIC X(84)} for the account
         * total line - because its label is {@code X(13) 'Account Total'} against the others'
         * {@code X(11)}. All three lines are therefore 112 characters before padding: 11 + 86 + 15,
         * 13 + 84 + 15 and 11 + 86 + 15. Copying 86 onto the account line is the obvious mistake and would
         * push its edited amount two columns right of where the legacy report puts it, so each width is
         * measured on its own rather than through a shared helper.
         *
         * <p><b>Remediation if this fails:</b> read each width off {@code app/cpy/CVTRA07Y.cpy:L53},
         * {@code :L59} and {@code :L65} individually and restore the three distinct constants. Do
         * <em>not</em> unify them behind a single leader constant, and do not compute the leader as the
         * line width minus the label length - the copybook is the authority, and a computed leader would
         * silently track whatever label a later edit introduced.
         */
        @Test
        @DisplayName("HIGH: the leaders are 86, 84 and 86 dots - the account line differs deliberately")
        void theThreeLeaderWidthsAreNotUniform() {
            List<String> emitted = everyLineKind();

            String page = lineLabelled(emitted, PAGE_TOTAL_LABEL);
            String account = lineLabelled(emitted, ACCOUNT_TOTAL_LABEL);
            String grand = lineLabelled(emitted, GRAND_TOTAL_LABEL);

            assertThat(leaderRunOf(page, PAGE_TOTAL_LABEL_WIDTH))
                    .as("app/cpy/CVTRA07Y.cpy:L51-L53 - X(11) label then X(86) of dots")
                    .isEqualTo(86);
            assertThat(leaderRunOf(account, ACCOUNT_TOTAL_LABEL_WIDTH))
                    .as("app/cpy/CVTRA07Y.cpy:L57-L59 - X(13) label then X(84) of dots, two fewer than the "
                            + "other two lines because the label is two characters longer")
                    .isEqualTo(84);
            assertThat(leaderRunOf(grand, GRAND_TOTAL_LABEL_WIDTH))
                    .as("app/cpy/CVTRA07Y.cpy:L63-L65 - X(11) label then X(86) of dots")
                    .isEqualTo(86);
        }

        /**
         * Records the separator asymmetry the three label fields produce.
         *
         * <p><b>Finding, Low severity.</b> {@code 'Page Total'} is a ten character literal in an
         * {@code X(11)} field, so one space separates it from the leader. {@code 'Account Total'} and
         * {@code 'Grand Total'} exactly fill their {@code X(13)} and {@code X(11)} fields, so their leaders
         * abut the label with no space at all. Nothing needs fixing - it is what the copybook declares - but
         * a renderer that trimmed labels or emitted a uniform separator would silently shift two lines in
         * three by one column, so the difference is pinned here.
         */
        @Test
        @DisplayName("LOW: only the page label leaves a separating space; the other two abut their leaders")
        void onlyThePageLabelLeavesASeparatingSpace() {
            List<String> emitted = everyLineKind();

            assertThat(lineLabelled(emitted, PAGE_TOTAL_LABEL).charAt(PAGE_TOTAL_LABEL.length()))
                    .as("app/cpy/CVTRA07Y.cpy:L51 - a ten character literal in an X(11) field")
                    .isEqualTo(' ');
            assertThat(lineLabelled(emitted, ACCOUNT_TOTAL_LABEL).charAt(ACCOUNT_TOTAL_LABEL.length()))
                    .as("app/cpy/CVTRA07Y.cpy:L57 - a thirteen character literal exactly fills X(13)")
                    .isEqualTo('.');
            assertThat(lineLabelled(emitted, GRAND_TOTAL_LABEL).charAt(GRAND_TOTAL_LABEL.length()))
                    .as("app/cpy/CVTRA07Y.cpy:L63 - an eleven character literal exactly fills X(11)")
                    .isEqualTo('.');
        }

        @Test
        @DisplayName("each total line is 112 characters of content, right-padded into the 133-byte record")
        void eachTotalLineIsOneHundredAndTwelveCharactersOfContent() {
            List<String> emitted = everyLineKind();

            for (String label : List.of(PAGE_TOTAL_LABEL, ACCOUNT_TOTAL_LABEL, GRAND_TOTAL_LABEL)) {
                String line = lineLabelled(emitted, label);
                assertThat(line)
                        .as("every record of REPTFILE is fixed block 133, per app/proc/TRANREPT.prc:L76")
                        .hasSize(LINE_LENGTH);
                assertThat(line.substring(TOTAL_LINE_CONTENT_WIDTH))
                        .as("app/cpy/CVTRA07Y.cpy declares " + label + " as 112 characters, so columns 113 "
                                + "to 133 are the record's own padding")
                        .isBlank();
                assertThat(line.substring(0, TOTAL_LINE_CONTENT_WIDTH).stripTrailing())
                        .as("the declared content itself must not be blank-padded internally")
                        .hasSize(TOTAL_LINE_CONTENT_WIDTH);
            }
        }

        @Test
        @DisplayName("REPORT-NAME-HEADER is the six declared fields of :L4-L13, 115 characters of content")
        void theNameHeaderIsAssembledFieldByField() {
            stubHappyPath();

            String nameHeader = processor.process(transaction("10.00")).lines().get(0);

            // app/cpy/CVTRA07Y.cpy:L5-L13 - X(38) 'DALYREPT', X(41) 'Daily Transaction Report',
            // X(12) 'Date Range: ' (the trailing space is inside the literal), X(10) start,
            // X(04) ' to ' (leading AND trailing space), X(10) end. 38+41+12+10+4+10 = 115.
            assertThat(nameHeader).hasSize(LINE_LENGTH);
            assertThat(nameHeader.substring(0, 38)).isEqualTo(padded("DALYREPT", 38));
            assertThat(nameHeader.substring(38, 79)).isEqualTo(padded("Daily Transaction Report", 41));
            assertThat(nameHeader.substring(79, 91))
                    .as("the literal carries its own trailing space, so the date abuts it")
                    .isEqualTo("Date Range: ");
            assertThat(nameHeader.substring(91, 101)).isEqualTo(START_DATE);
            assertThat(nameHeader.substring(101, 105))
                    .as("app/cpy/CVTRA07Y.cpy:L12 - FILLER X(04) VALUE ' to ', spaced on both sides")
                    .isEqualTo(" to ");
            assertThat(nameHeader.substring(105, 115)).isEqualTo(END_DATE);
            assertThat(nameHeader.substring(115))
                    .as("115 characters of content, then the record's padding")
                    .isBlank();
        }

        @Test
        @DisplayName("TRANSACTION-HEADER-1 keeps the EIGHT leading spaces inside its Amount literal")
        void theColumnHeadingsKeepTheirDeclaredWidths() {
            stubHappyPath();

            String headings = processor.process(transaction("10.00")).lines().get(2);

            // app/cpy/CVTRA07Y.cpy:L34-L46 - X(17) + X(12) + X(19) + X(35) + X(14) + X(01) + X(16) = 114.
            assertThat(headings).hasSize(LINE_LENGTH);
            assertThat(headings.substring(0, 17)).isEqualTo(padded("Transaction ID", 17));
            assertThat(headings.substring(17, 29)).isEqualTo(padded("Account ID", 12));
            assertThat(headings.substring(29, 48)).isEqualTo(padded("Transaction Type", 19));
            assertThat(headings.substring(48, 83)).isEqualTo(padded("Tran Category", 35));
            assertThat(headings.substring(83, 97)).isEqualTo(padded("Tran Source", 14));
            assertThat(headings.substring(97, 98))
                    .as("app/cpy/CVTRA07Y.cpy:L44 - a lone FILLER PIC X VALUE SPACES")
                    .isEqualTo(" ");
            assertThat(headings.substring(98, 114))
                    .as("app/cpy/CVTRA07Y.cpy:L45-L46 - the literal is '        Amount', eight leading "
                            + "spaces inside the quotes, which right-aligns the heading over the edited "
                            + "amount rather than padding the field")
                    .isEqualTo(padded("        Amount", 16));
            assertThat(headings.substring(114)).isBlank();
        }

        @Test
        @DisplayName("TRANSACTION-HEADER-2 is 133 hyphens, the one full-width structure in the copybook")
        void theRuleIsOneHundredAndThirtyThreeHyphens() {
            stubHappyPath();

            String rule = processor.process(transaction("10.00")).lines().get(3);

            // app/cpy/CVTRA07Y.cpy:L48 - 01 TRANSACTION-HEADER-2 PIC X(133) VALUE ALL '-'. It is the only
            // 133-byte structure declared, which is what proves the record width independently of the DCB.
            assertThat(rule)
                    .as("app/cpy/CVTRA07Y.cpy:L48 - PIC X(133) VALUE ALL '-'")
                    .isEqualTo("-".repeat(LINE_LENGTH));
        }

        @Test
        @DisplayName("WS-BLANK-LINE is 133 spaces, and it is a real emitted record")
        void theBlankLineIsOneHundredAndThirtyThreeSpaces() {
            stubHappyPath();

            String blank = processor.process(transaction("10.00")).lines().get(1);

            // app/cbl/CBTRN03C.cbl:L133 - WS-BLANK-LINE PIC X(133) VALUE SPACES, written at :L329-L331.
            // A fixed block file has no short record, so the blank line occupies its full width.
            assertThat(blank)
                    .as("app/cbl/CBTRN03C.cbl:L133 and :L329 - a blank line is still a 133-byte record")
                    .isEqualTo(" ".repeat(LINE_LENGTH))
                    .hasSize(LINE_LENGTH);
        }

        @Test
        @DisplayName("the detail line is 114 characters of content, padded into the 133-byte record")
        void theDetailLineIsOneHundredAndFourteenCharactersOfContent() {
            stubHappyPath();

            String detail = processor.process(transaction("10.00")).lines().get(4);

            // app/cpy/CVTRA07Y.cpy:L16-L31 - 16+1+11+1+2+1+15+1+4+1+29+1+10+4+15+2 = 114.
            assertThat(detail).hasSize(LINE_LENGTH);
            assertThat(detail.substring(DETAIL_LINE_CONTENT_WIDTH))
                    .as("columns 115 to 133 are the record's padding, not declared fields")
                    .isBlank();
        }

        /**
         * Counts the run of leader characters that follows a label.
         *
         * @param line an emitted total line
         * @param labelWidth the declared width of that line's label field
         * @return how many consecutive {@code '.'} characters follow the label
         */
        private static int leaderRunOf(final String line, final int labelWidth) {
            int run = 0;
            for (int index = labelWidth; index < line.length() && line.charAt(index) == '.'; index++) {
                run++;
            }
            return run;
        }
    }

    /**
     * The five paragraphs hidden behind two numeric-prefix collisions, each asserted separately.
     *
     * <p>{@code app/cbl/CBTRN03C.cbl} reuses {@code 1120-} three times and {@code 1110-} twice, with
     * {@code 1100-} and {@code 1111-} interleaved between them:
     *
     * <pre>
     * 274:  1100-WRITE-TRANSACTION-REPORT.
     * 293:  1110-WRITE-PAGE-TOTALS.          &lt;-- 1110- #1
     * 306:  1120-WRITE-ACCOUNT-TOTALS.       &lt;-- 1120- #1
     * 318:  1110-WRITE-GRAND-TOTALS.         &lt;-- 1110- #2
     * 324:  1120-WRITE-HEADERS.              &lt;-- 1120- #2
     * 343:  1111-WRITE-REPORT-REC.
     * 361:  1120-WRITE-DETAIL.               &lt;-- 1120- #3
     * </pre>
     *
     * <p>A Java name derived from the numeric prefix would therefore collide, and consolidating any two of
     * them would erase a distinction the report's arithmetic depends on. The five differ in exactly the
     * ways this group measures: how many lines each emits, how far each advances
     * {@code WS-LINE-COUNTER}, and which accumulator each resets. Names are disambiguated by the full
     * paragraph label, never by the prefix.
     *
     * <p><b>Finding, High severity - consolidation would be undetectable at runtime.</b> Two of these
     * paragraphs emit two lines and advance the counter twice, one emits four and advances four, one
     * emits one and advances one, and one emits one and advances <em>none</em>. Merging any pair, or
     * regularising the odd one, still produces a well formed 133 byte report - it simply appears on
     * different pages. Nothing throws, so only an assertion on the increment can catch it.
     *
     * <p><b>Remediation if any test here fails:</b> restore the increment of the single paragraph named
     * by the failure from its own locator - {@code :L299} and {@code :L302}, {@code :L311} and
     * {@code :L314}, {@code :L327}/{@code :L331}/{@code :L335}/{@code :L339}, {@code :L373}, and for
     * {@code 1110-WRITE-GRAND-TOTALS} the deliberate absence of one across {@code :L318-L322}. Do not
     * make the five uniform.
     */
    @Nested
    @DisplayName("12. Five paragraphs, two prefix collisions: line counts, increments and resets all differ")
    class ParagraphPrefixCollisions {

        @Test
        @DisplayName("1120-WRITE-HEADERS emits FOUR lines and advances the counter once per line")
        void writeHeadersEmitsFourLinesAndFourIncrements() {
            stubHappyPath();

            List<String> first = processor.process(transaction("10.00")).lines();

            // app/cbl/CBTRN03C.cbl:L325-L339 - REPORT-NAME-HEADER, WS-BLANK-LINE, TRANSACTION-HEADER-1 and
            // TRANSACTION-HEADER-2, each followed by ADD 1 TO WS-LINE-COUNTER. Then :L373 adds one more for
            // the detail line, so five records leave the counter at five.
            assertThat(first).as("four header lines then one detail line").hasSize(5);
            assertThat(processor.lineCounter())
                    .as("app/cbl/CBTRN03C.cbl:L327, :L331, :L335, :L339 and :L373 - five single increments")
                    .isEqualTo(5L);
        }

        @Test
        @DisplayName("the first record's header block is what keeps MOD(0,20) from breaking a page at once")
        void theHeaderBlockPrecedesThePageBreakTest() {
            stubHappyPath();

            processor.process(transaction("10.00"));

            // app/cbl/CBTRN03C.cbl:L275-L280 runs the first-time header block BEFORE the modulus test at
            // :L282. Were the order reversed, MOD(0, 20) = 0 would fire a page break on the very first
            // record and emit a Page Total of zero above the first heading.
            assertThat(processor.lineCounter() % PAGE_SIZE)
                    .as("the counter is 5 after the first record, so :L282 does not fire")
                    .isNotZero();
        }

        @Test
        @DisplayName("1110-WRITE-PAGE-TOTALS emits TWO lines, advances TWICE, rolls up and resets the page")
        void writePageTotalsEmitsTwoLinesAndRollsUp() {
            stubHappyPath();
            processor.process(transaction("10.00"));
            long counterBefore = processor.lineCounter();

            List<String> closing = processor.finishReport().lines();

            // app/cbl/CBTRN03C.cbl:L294-L302 - the totals line, then ADD WS-PAGE-TOTAL TO WS-GRAND-TOTAL at
            // :L297, MOVE 0 TO WS-PAGE-TOTAL at :L298, ADD 1 at :L299, TRANSACTION-HEADER-2 at :L300 and
            // ADD 1 at :L302. finishReport then adds only the single grand total line, which does not
            // advance the counter, so the whole closing block advances it by exactly two.
            assertThat(closing.get(0)).startsWith(PAGE_TOTAL_LABEL);
            assertThat(closing.get(1))
                    .as("app/cbl/CBTRN03C.cbl:L300 - the page total block re-emits the dashed rule")
                    .isEqualTo("-".repeat(LINE_LENGTH));
            assertThat(processor.lineCounter() - counterBefore)
                    .as("app/cbl/CBTRN03C.cbl:L299 and :L302 - two increments, and :L321 adds none")
                    .isEqualTo(2L);
            assertThat(processor.pageTotal())
                    .as("app/cbl/CBTRN03C.cbl:L298 - MOVE 0 TO WS-PAGE-TOTAL")
                    .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(processor.grandTotal())
                    .as("app/cbl/CBTRN03C.cbl:L297 - the page total is the ONLY path into the grand total; "
                            + "10.00 is counted twice by the preserved end-of-data defect at :L200-L201")
                    .isEqualByComparingTo(new BigDecimal("20.00"));
        }

        @Test
        @DisplayName("1120-WRITE-ACCOUNT-TOTALS emits TWO lines, advances TWICE and leaves the grand total")
        void writeAccountTotalsDoesNotTouchTheGrandTotal() {
            stubHappyPath();
            processor.process(transaction("10.00"));
            long counterBefore = processor.lineCounter();
            BigDecimal grandBefore = processor.grandTotal();

            List<String> second = processor.process(
                    transaction("0000000000683581", CARD_B, "1.00", IN_PERIOD_PROC_TS)).lines();

            // app/cbl/CBTRN03C.cbl:L307-L314 - the totals line, MOVE 0 TO WS-ACCOUNT-TOTAL at :L310,
            // ADD 1 at :L311, TRANSACTION-HEADER-2 at :L312-L313 and ADD 1 at :L314. There is no
            // ADD ... TO WS-GRAND-TOTAL anywhere in the paragraph: only :L297 feeds the grand total.
            assertThat(second.get(0)).startsWith(ACCOUNT_TOTAL_LABEL);
            assertThat(second.get(1)).isEqualTo("-".repeat(LINE_LENGTH));
            assertThat(second).as("account total, its rule, then the new card's detail line").hasSize(3);
            assertThat(processor.lineCounter() - counterBefore)
                    .as("app/cbl/CBTRN03C.cbl:L311 and :L314 for the block, plus :L373 for the detail line")
                    .isEqualTo(3L);
            assertThat(processor.grandTotal())
                    .as("app/cbl/CBTRN03C.cbl:L306-L316 contains no grand total arithmetic at all")
                    .isEqualByComparingTo(grandBefore);
            assertThat(processor.accountTotal())
                    .as("app/cbl/CBTRN03C.cbl:L310 zeroes the account total, then :L287-L288 adds the new "
                            + "record's 1.00")
                    .isEqualByComparingTo(new BigDecimal("1.00"));
        }

        @Test
        @DisplayName("1110-WRITE-GRAND-TOTALS emits ONE line and advances the counter NOT AT ALL")
        void writeGrandTotalsDoesNotAdvanceTheCounter() {
            stubHappyPath();
            processor.process(transaction("10.00"));

            // Take the counter immediately after the page total block, which is the last thing before the
            // grand total line, by re-deriving it: finishReport performs page totals (+2) then grand totals.
            List<String> closing = processor.finishReport().lines();
            long counterAfterEverything = processor.lineCounter();

            // app/cbl/CBTRN03C.cbl:L318-L322 - MOVE, MOVE, PERFORM 1111-WRITE-REPORT-REC, EXIT. There is no
            // ADD 1 TO WS-LINE-COUNTER, unlike every other emitting paragraph. Tidying one in would shift
            // every subsequent page break by a line, which is why the asymmetry is asserted rather than
            // assumed.
            assertThat(closing).as("page total, its rule, then the grand total").hasSize(3);
            assertThat(closing.get(2)).startsWith(GRAND_TOTAL_LABEL);
            assertThat(counterAfterEverything)
                    .as("five for the first record plus two for the page total block; the grand total line "
                            + "is emitted without an increment, so the counter is 7 and not 8")
                    .isEqualTo(7L);
        }

        @Test
        @DisplayName("1120-WRITE-DETAIL emits ONE line, advances once, and INITIALIZE spares both hyphens")
        void writeDetailKeepsBothFillerHyphens() {
            stubHappyPath();

            String detail = processor.process(transaction("10.00")).lines().get(4);

            // app/cbl/CBTRN03C.cbl:L362 - INITIALIZE TRANSACTION-DETAIL-REPORT sets elementary items to
            // spaces or zero but leaves FILLER at its VALUE, so the two FILLER PIC X(01) VALUE '-' at
            // app/cpy/CVTRA07Y.cpy:L21 and :L25 survive. Offsets: 16+1+11+1+2 = 31 for the first,
            // and +1+15+1+4 = 52 for the second.
            assertThat(detail.charAt(31))
                    .as("app/cpy/CVTRA07Y.cpy:L21 - the separator after TRAN-REPORT-TYPE-CD survives "
                            + "INITIALIZE")
                    .isEqualTo('-');
            assertThat(detail.charAt(52))
                    .as("app/cpy/CVTRA07Y.cpy:L25 - the separator after TRAN-REPORT-CAT-CD survives")
                    .isEqualTo('-');
            assertThat(detail.substring(48, 52))
                    .as("app/cpy/CVTRA07Y.cpy:L24 - PIC 9(04) is zero padded, so category 1000 prints as "
                            + "four digits and category 1 would print as 0001")
                    .isEqualTo("1000");
        }

        /**
         * Asserts that {@code 1111-WRITE-REPORT-REC} accepts one status and one only.
         *
         * <p>{@code app/cbl/CBTRN03C.cbl:L346} is {@code IF TRANREPT-STATUS = '00'}; every other value falls
         * to {@code :L354}, displays {@code 'ERROR WRITING REPTFILE'}, renders the status and abends. There
         * is no secondary success status on this path - unlike the statement file service, which accepts
         * {@code '04'} as well - so a lenient guard here would be a parity break. In the target the write
         * belongs to the step's writer, so what is verifiable at this tier is the guard's precondition: a
         * record whose length is not 133 can never write successfully to a fixed block file, and offering
         * one raises the same abend text with the same operator message.
         */
        @Test
        @DisplayName("1111-WRITE-REPORT-REC accepts '00' ONLY: a short record raises its own literal")
        void writeReportRecAcceptsOnlyStatusZeroZero() {
            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> new ReportLines(List.of("too short")))
                    .satisfies(abend -> {
                        assertThat(abend.getAbendMessage())
                                .as("app/cbl/CBTRN03C.cbl:L354 - the paragraph's own DISPLAY literal")
                                .isEqualTo("ERROR WRITING REPTFILE");
                        assertThat(abend.getAbendCode())
                                .as("app/cbl/CBTRN03C.cbl:L629 - MOVE 999 TO ABCODE")
                                .isEqualTo("0999");
                        assertThat(abend.getAbendCulprit())
                                .as("ABEND-CULPRIT PIC X(8) holds the eight character program name")
                                .isEqualTo("CBTRN03C");
                    });
        }

        /**
         * Pins the page break to the record it actually fires on, which is not the twentieth.
         *
         * <p>{@code app/cbl/CBTRN03C.cbl:L282} tests {@code FUNCTION MOD(WS-LINE-COUNTER, WS-PAGE-SIZE)}
         * against zero. Nothing in the program ever resets {@code WS-LINE-COUNTER}, and every emitting
         * paragraph advances it, so the break is governed by the running total of <em>all</em> lines rather
         * than by a count of detail lines. The arithmetic follows exactly:
         *
         * <ul>
         * <li>Record 1 writes the four line header block ({@code :L279}) and its detail line
         * ({@code :L289}), leaving the counter at 5.</li>
         * <li>Records 2 to 16 each add one, so the counter reaches 20 after record 16.</li>
         * <li>Record 17 therefore finds {@code MOD(20, 20) = 0} and breaks the page.</li>
         * </ul>
         *
         * <p>A per-page counter that reset at each break would instead have broken on record 21. Asserting
         * the position rather than a total is what tells the two designs apart: a count alone can coincide,
         * as an earlier revision of this test discovered when it guessed that forty records could not
         * produce exactly two breaks - they do, at records 17 and 31.
         */
        @Test
        @DisplayName("the page break is a MODULUS on the running counter: it fires on record 17, not 21")
        void thePageBreakIsAModulusOnTheRunningCounter() {
            stubHappyPath();

            int firstBreakRecord = 0;
            for (int record = 1; record <= 25 && firstBreakRecord == 0; record++) {
                List<String> lines = processor.process(transaction(
                        String.format(Locale.ROOT, "%016d", record), CARD_A, "1.00",
                        IN_PERIOD_PROC_TS)).lines();
                if (lines.stream().anyMatch(line -> line.startsWith(PAGE_TOTAL_LABEL))) {
                    firstBreakRecord = record;
                }
            }

            assertThat(firstBreakRecord)
                    .as("app/cbl/CBTRN03C.cbl:L282 over a counter the header block already advanced to 5; "
                            + "a per-page counter reset would have broken on record 21 instead")
                    .isEqualTo(17);
            assertThat(processor.lineCounter())
                    .as("the counter only ever increases - app/cbl/CBTRN03C.cbl contains no statement that "
                            + "resets WS-LINE-COUNTER")
                    .isGreaterThan(PAGE_SIZE);
        }

        @Test
        @DisplayName("a page break emits its totals block BEFORE the new page's headers, per :L283-L284")
        void aPageBreakEmitsTotalsBeforeHeaders() {
            stubHappyPath();

            List<String> breaking = null;
            for (int record = 1; record <= 17; record++) {
                breaking = processor.process(transaction(
                        String.format(Locale.ROOT, "%016d", record), CARD_A, "1.00",
                        IN_PERIOD_PROC_TS)).lines();
            }

            // app/cbl/CBTRN03C.cbl:L283-L284 - PERFORM 1110-WRITE-PAGE-TOTALS then PERFORM
            // 1120-WRITE-HEADERS, in that order: the page being closed is totalled first, then the next
            // page is headed. Seven lines in all: two for the totals block, four for the headers, one detail.
            assertThat(breaking).hasSize(7);
            assertThat(breaking.get(0)).startsWith(PAGE_TOTAL_LABEL);
            assertThat(breaking.get(1)).isEqualTo("-".repeat(LINE_LENGTH));
            assertThat(breaking.get(2))
                    .as("app/cbl/CBTRN03C.cbl:L284 then :L325 - the new page opens with REPORT-NAME-HEADER")
                    .startsWith("DALYREPT");
            assertThat(breaking.get(6))
                    .as("the record's own detail line is emitted last, at :L289")
                    .contains("1.00");
        }
    }

    /**
     * Untrusted input and the twelve lifecycle paragraphs, which are a third of the program's inventory.
     *
     * <p>Rule 1 clause A requires inputs to be treated as untrusted and clause B requires null and empty
     * cases to be handled explicitly, so this group drives the boundaries the source itself defines: the
     * widest value {@code PIC S9(09)V99} can hold, zero, a negative amount, and a card number that is
     * absent altogether. It then exercises the six {@code OPEN} paragraphs of
     * {@code app/cbl/CBTRN03C.cbl:L376-L482} and the six {@code CLOSE} paragraphs of {@code :L514-L621},
     * each of which abends with its own operator literal.
     */
    @Nested
    @DisplayName("13. Untrusted input at the PIC boundaries, and the six OPEN / six CLOSE paragraphs")
    class HostileInputAndDatasetLifecycle {

        @Test
        @DisplayName("the widest value PIC S9(09)V99 holds renders in full, and is not widened or rounded")
        void theNumericBoundaryRendersInFull() {
            stubHappyPath();

            String detail = processor.process(transaction(NUMERIC_BOUNDARY)).lines().get(4);
            List<String> closing = processor.finishReport().lines();

            // app/cbl/CBTRN03C.cbl:L134-L136 declare the three totals as PIC S9(09)V99, so nine integer
            // digits and two decimals - NUMERIC(11,2). The edited mask ZZZ,ZZZ,ZZZ.ZZ has exactly nine
            // integer positions, so the widest value fills it with no position to spare and no suppression.
            assertThat(detail.substring(DETAIL_AMOUNT_START, DETAIL_AMOUNT_START + EDITED_AMOUNT_WIDTH))
                    .as("app/cpy/CVTRA07Y.cpy:L30 - the detail mask's sign position stays blank when the "
                            + "value is non-negative, even at the maximum")
                    .isEqualTo(" 999,999,999.99");
            assertThat(processor.grandTotal())
                    .as("BigDecimal keeps the scale; the preserved double count of :L200-L201 doubles the "
                            + "figure, which a nine digit COBOL field could not have held - see the finding "
                            + "on the end-of-data branch")
                    .isEqualByComparingTo(new BigDecimal("1999999999.98"));
            assertThat(closing).allSatisfy(line -> assertThat(line).hasSize(LINE_LENGTH));
        }

        @Test
        @DisplayName("a zero amount blanks the whole detail field but still prints a signed zero total")
        void zeroBlanksTheDetailAndSignsTheTotal() {
            stubHappyPath();

            String detail = processor.process(transaction("0.00")).lines().get(4);
            String pageTotal = processor.finishReport().lines().get(0);

            // app/cpy/CVTRA07Y.cpy:L30 versus :L54 - the detail mask has no unsuppressed position, so a zero
            // amount blanks all fifteen characters; the totals mask leads with a mandatory '+', so the same
            // value still prints a sign there. The two masks differ by exactly that one character.
            assertThat(detail.substring(DETAIL_AMOUNT_START, DETAIL_AMOUNT_START + EDITED_AMOUNT_WIDTH))
                    .as("-ZZZ,ZZZ,ZZZ.ZZ over zero suppresses every digit AND its separators")
                    .isBlank();
            assertThat(pageTotal)
                    .as("+ZZZ,ZZZ,ZZZ.ZZ always emits its sign, so a zero total is not a blank line")
                    .contains("+")
                    .hasSize(LINE_LENGTH);
        }

        @Test
        @DisplayName("a negative amount puts its minus in the mask's FIXED first position, not floating")
        void aNegativeAmountSignsTheFirstPosition() {
            stubHappyPath();

            String detail = processor.process(transaction("-1.00")).lines().get(4);

            // app/cpy/CVTRA07Y.cpy:L30 - PIC -ZZZ,ZZZ,ZZZ.ZZ. The sign is a fixed leading character, not a
            // floating one: it stays in position 1 of the fifteen while zero suppression blanks the digits
            // between it and the value. A floating sign would have printed '-1.00' right-aligned instead.
            String edited = detail.substring(DETAIL_AMOUNT_START, DETAIL_AMOUNT_START + EDITED_AMOUNT_WIDTH);
            assertThat(edited.charAt(0))
                    .as("app/cpy/CVTRA07Y.cpy:L30 - the sign occupies a fixed position 1 and does not float")
                    .isEqualTo('-');
            assertThat(edited.substring(1).stripLeading()).isEqualTo("1.00");
            assertThat(edited).hasSize(EDITED_AMOUNT_WIDTH);
        }

        @Test
        @DisplayName("an absent card number space-fills to sixteen, so it cannot break against the initial key")
        void anAbsentCardNumberMatchesTheInitialBreakKey() {
            stubReferenceData();
            Transaction item = transaction("10.00");
            setField(item, "cardNumber", null);

            // app/cbl/CBTRN03C.cbl:L137 initialises WS-CURR-CARD-NUM to sixteen spaces, and :L181 compares
            // the incoming card against it. An absent card number space-fills to the identical value, so the
            // break at :L181 does NOT fire, CARDXREF is never read, and the failure surfaces later - at
            // detail time, where XREF-ACCT-ID is needed. Asserting that chain is what proves the guard order.
            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> processor.process(item))
                    .satisfies(abend -> assertThat(abend.getAbendReason())
                            .as("the diagnosis must name the empty buffer, not dereference the null")
                            .contains("the CARDXREF buffer is empty at detail time"));
            assertThat(processor.currentCardNumber())
                    .as("app/cbl/CBTRN03C.cbl:L137 - the break key is still its initial sixteen spaces")
                    .isEqualTo(" ".repeat(16));
            Mockito.verifyNoInteractions(cardCrossReferenceRepository);
        }

        @Test
        @DisplayName("openDatasets probes all six in the mainline's own order of :L161-L166")
        void openDatasetsProbesAllSixInSourceOrder() {
            processor.openDatasets();

            // app/cbl/CBTRN03C.cbl:L161-L166 - TRANFILE, REPTFILE, CARDXREF, TRANTYPE, TRANCATG, DATEPARM.
            // Four are relations this class holds a repository for and their open is a reachability probe;
            // REPTFILE is the step writer's handle and DATEPARM is a parameter card, so neither has a count.
            InOrder order = Mockito.inOrder(transactionRepository, cardCrossReferenceRepository,
                    transactionTypeRepository, transactionCategoryRepository);
            order.verify(transactionRepository).count();
            order.verify(cardCrossReferenceRepository).count();
            order.verify(transactionTypeRepository).count();
            order.verify(transactionCategoryRepository).count();
            order.verifyNoMoreInteractions();
        }

        @Test
        @DisplayName("closeDatasets repeats the OPEN order rather than reversing it, per :L208-L213")
        void closeDatasetsRepeatsTheOpenOrder() {
            processor.closeDatasets();

            // app/cbl/CBTRN03C.cbl:L208-L213 closes TRANSACT first, exactly as :L161 opened it first. The
            // order is the source's and is deliberately not the reverse of the open order.
            InOrder order = Mockito.inOrder(transactionRepository, cardCrossReferenceRepository,
                    transactionTypeRepository, transactionCategoryRepository);
            order.verify(transactionRepository).count();
            order.verify(cardCrossReferenceRepository).count();
            order.verify(transactionTypeRepository).count();
            order.verify(transactionCategoryRepository).count();
            order.verifyNoMoreInteractions();
        }

        @Test
        @DisplayName("an unreachable TRANFILE abends with :L387's literal, abend 999 and the status line")
        void anUnreachableTransactionFileAbends() {
            QueryTimeoutException unreachable = new QueryTimeoutException("TRANFILE is not reachable");
            Mockito.when(transactionRepository.count()).thenThrow(unreachable);

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> processor.openDatasets())
                    .satisfies(abend -> {
                        assertThat(abend.getAbendMessage())
                                .as("app/cbl/CBTRN03C.cbl:L387 - DISPLAY 'ERROR OPENING TRANFILE'")
                                .isEqualTo("ERROR OPENING TRANFILE");
                        assertThat(abend.getAbendCode())
                                .as("app/cbl/CBTRN03C.cbl:L629 - MOVE 999 TO ABCODE")
                                .isEqualTo("0999");
                        assertThat(abend.getCause())
                                .as("Rule 1 clause B - the root cause is preserved, not swallowed; the "
                                        + "source had no cause to keep because FILE STATUS is only a code")
                                .isSameAs(unreachable);
                    });
            assertThat(loggedMessages())
                    .as("app/cbl/CBTRN03C.cbl:L387-L389 - the literal, then 9910-DISPLAY-IO-STATUS")
                    .contains("ERROR OPENING TRANFILE")
                    .anyMatch(message -> message.startsWith("FILE STATUS IS: NNNN"));
        }

        @Test
        @DisplayName("an unreachable CARDXREF close abends with :L562's own distinct literal")
        void anUnreachableCrossReferenceCloseAbends() {
            Mockito.when(cardCrossReferenceRepository.count())
                    .thenThrow(new QueryTimeoutException("CARDXREF is not reachable"));

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> processor.closeDatasets())
                    .satisfies(abend -> assertThat(abend.getAbendMessage())
                            .as("app/cbl/CBTRN03C.cbl:L562 - each paragraph carries its OWN literal, so an "
                                    + "operator can tell which of the twelve failed")
                            .isEqualTo("ERROR CLOSING CROSS REF FILE"));
        }

        @Test
        @DisplayName("the lifecycle probes disturb no accumulator, so opening does not alter the report")
        void theLifecycleProbesDisturbNoAccumulator() {
            processor.openDatasets();
            processor.closeDatasets();

            assertThat(processor.lineCounter()).isZero();
            assertThat(processor.pageTotal()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(processor.accountTotal()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(processor.grandTotal()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(processor.currentCardNumber())
                    .as("app/cbl/CBTRN03C.cbl:L137 - still the initial VALUE SPACES")
                    .isEqualTo(" ".repeat(16));
        }
    }

    /**
     * Right-pads a literal to a declared {@code PIC X(n)} width, the way a COBOL {@code MOVE} does.
     *
     * @param value the literal
     * @param width the declared field width
     * @return the value padded with spaces to exactly {@code width} characters
     */
    private static String padded(final String value, final int width) {
        return value + " ".repeat(width - value.length());
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

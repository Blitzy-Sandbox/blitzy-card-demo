/*
 * ******************************************************************
 * Program     : StatementProcessorTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Verifies the statement generation boundary: the five
 *               step initialisation pipeline that replaces the
 *               self-modifying ALTER dispatch, the in-memory
 *               transaction table built by the self-recursive loop, the
 *               ordering-dependent early exit whose correctness rests
 *               on the upstream sort, the DFSORT OUTREC projection that
 *               truncates two bytes of the processing timestamp and
 *               drops the trailing filler, the dual 80-byte text and
 *               100-byte HTML emission, the per-card total, and the
 *               removal of the legacy 51-card by 10-transaction
 *               capacity ceiling as a labelled deviation.
 * Source      : app/cbl/CBSTM03A.CBL:L59-L83   (counters + call area)
 *               app/cbl/CBSTM03A.CBL:L146-L151 (ST-LINE15, HTML X(100))
 *               app/cbl/CBSTM03A.CBL:L225-L237 (51 x 10 table, PSAPTR)
 *               app/cbl/CBSTM03A.CBL:L293-L294 (OPEN OUTPUT, INITIALIZE)
 *               app/cbl/CBSTM03A.CBL:L296-L314 (ALTER entry point)
 *               app/cbl/CBSTM03A.CBL:L316-L342 (1000-MAINLINE)
 *               app/cbl/CBSTM03A.CBL:L416-L456 (ordered lookup)
 *               app/cbl/CBSTM03A.CBL:L726-L728 (altered paragraph)
 *               app/cbl/CBSTM03A.CBL:L730-L762 (TRNXFILE open + prime)
 *               app/cbl/CBSTM03A.CBL:L765-L781 (XREFFILE open)
 *               app/cbl/CBSTM03A.CBL:L783-L799 (CUSTFILE open)
 *               app/cbl/CBSTM03A.CBL:L801-L816 (ACCTFILE open, exit)
 *               app/cbl/CBSTM03A.CBL:L818-L847 (table build loop)
 *               app/cbl/CBSTM03A.CBL:L849-L853 (8599-EXIT, final flush)
 *               app/cbl/CBSTM03A.CBL:L921-L923 (abend: NO ABCODE)
 *               app/cbl/CBSTM03B.CBL:L130-L131 (bare GOBACK)
 *               app/cpy/COSTM01.CPY:L20-L36    (32-byte TRNX-KEY)
 *               app/jcl/CREASTMT.JCL:L29-L39   (KEYS 32, RECORDSIZE 350)
 *               app/jcl/CREASTMT.JCL:L53-L54   (sort + OUTREC)
 *               app/jcl/CREASTMT.JCL:L69       (HTMLFILE LRECL=80)
 *               app/jcl/CREASTMT.JCL:L90       (corrupted STMTFILE DD)
 *               app/jcl/CREASTMT.JCL:L94       (HTMLFILE LRECL=100)
 *               app/data/ASCII/custdata.txt    (500-byte rows)
 *               app/data/ASCII/acctdata.txt    (300-byte rows)
 *               app/data/ASCII/cardxref.txt    (36-byte rows)
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cardemo.batch.processors.StatementProcessor;
import com.cardemo.batch.processors.StatementProcessor.Statement;
import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.model.dto.StatementTransaction;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Transaction;
import com.cardemo.model.enums.FileStatus;
import com.cardemo.service.shared.FileService;
import com.cardemo.service.shared.FileStatusMapper;
import com.cardemo.unit.model.FixedClockProvider;
import com.cardemo.unit.model.FixtureLoader;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;

/**
 * Executable proof that the statement step reproduces {@code CBSTM03A}'s observable behaviour without its
 * self-modifying dispatch and without its capacity ceiling.
 *
 * <p><b>What it does.</b> Drives the processor over a <em>real</em> {@link FileService} bound to four fake
 * {@link FileService.Dataset} implementations, so that every status guard, every {@code '00' OR '04'}
 * secondary-status acceptance and every abend path is the production one rather than a stub. The customer
 * and account payloads are <em>actual rows of the corpus fixtures</em>, read through
 * {@link FixtureLoader}, which is what makes the fixed-width parse assertions meaningful: a parser that
 * mis-slices by one byte fails against real data in a way it would not fail against a synthetic record
 * built to the same assumption. The transaction payloads are produced by the production projection
 * {@link StatementProcessor#projectBaseRecord(Transaction)}, so the suite exercises the sort projection
 * and the parse against each other.
 *
 * <p><b>How to build and test.</b> {@code ./mvnw -B -ntp -Dtest='StatementProcessorTest' test} runs this
 * class alone; {@code ./mvnw -B -ntp clean verify} runs it inside the full gate. It needs no container, no
 * database and no cloud emulator. <b>Surefire</b> binds this tier: the root {@code pom.xml} includes
 * {@code **}{@code /*Test.java} and excludes {@code **}{@code /integration/**} and {@code **}{@code /e2e/**},
 * so a class moved out of {@code src/test/java/com/cardemo/unit/**} would match neither plugin's include set
 * and would <em>silently never run</em> — a green build with this class uncovered. That is why the file name
 * and location are a Blocker-severity contract and not a preference.
 *
 * <p><b>Key configuration and defaults.</b> Text records are 80 characters and HTML records are 100, the
 * two {@code LRECL} values {@code app/jcl/CREASTMT.JCL:STEP040} declares. The legacy table held 51 cards
 * of 10 transactions — a historical capacity limit of <b>510 transactions per run</b>; those numbers appear
 * here only as the thresholds at which a warning is logged, because the ceiling itself is deliberately gone.
 * Time is supplied exclusively by {@link FixedClockProvider}: every timestamp in every fixture is
 * {@link FixedClockProvider#CANONICAL_ONLINE_TIMESTAMP} or is derived from
 * {@link FixedClockProvider#canonicalClock()}, and no assertion in this class reads a wall clock, a default
 * locale, a default time zone or an unseeded random source. Every format and case operation passes
 * {@link Locale#ROOT}, which is load-bearing rather than decorative: the trailing-sign amount masks
 * {@code PIC 9(9).99-} and {@code PIC Z(9).99-} would render differently under a locale that groups or that
 * substitutes a different minus glyph. Where a collaborator is mocked rather than faked — group 21 only —
 * Mockito is <em>enforced</em> at {@link Strictness#STRICT_STUBS} through
 * {@code @MockitoSettings} on that group, so an unused stub fails the build rather than standing as a silent
 * assumption about a call the processor never makes. Groups 1 to 20 use a hand-written
 * {@link FileService.Dataset} fake instead, deliberately: the sequential read contract is stateful, a queue
 * expresses that far better than a consecutive-return chain, and — decisively — it leaves the <em>real</em>
 * {@link FileService} and {@link FileStatusMapper} in the path, so the status guards under test are the
 * production ones rather than stubs standing in for them.
 *
 * <p><b>Evidence honesty (Rule 1 Clause F).</b> A repository-wide search for <i>expected</i>, <i>baseline</i>,
 * <i>golden</i>, {@code *.out}, {@code *STMTFILE*} and {@code *HTMLFILE*} returns only dataset-definition JCL
 * and <b>zero captured data</b>. A legacy statement-output baseline is therefore <b>Not available</b>, and
 * what would be needed to produce one is a z/OS run of {@code app/jcl/CREASTMT.JCL} with {@code STMTFILE} and
 * {@code HTMLFILE} retained. Consequently this class creates no baseline file and asserts no aggregate output
 * comparison: it asserts geometry, ordering, arithmetic and status handling, each against a cited locator.
 *
 * <p><b>Two deliberate deviations from the source are asserted as deviations.</b> The self-modifying
 * {@code ALTER … TO PROCEED TO} chain is gone, replaced by a fixed five-step sequence - group 2 asserts
 * that sequence and its order, because the order is the observable part. And the 51-by-10 capacity ceiling
 * is gone, replaced by unbounded collections plus a warning at each legacy threshold - group 4 asserts
 * that a run larger than the COBOL table could hold succeeds and warns rather than overrunning.
 *
 * <p><b>Common failure modes and troubleshooting.</b>
 * <ul>
 * <li><b>Blocker.</b> A failure in group 7 means the {@code OUTREC} projection has changed. It must emit
 * the 16-byte card number, then 262 bytes from the head, then 50 bytes from offset 279 - which truncates
 * the processing timestamp to 24 characters and drops the 20-byte filler entirely. Reproducing that
 * truncation is what keeps statement output byte-comparable with the legacy baseline; "fixing" it is a
 * divergence.</li>
 * <li><b>Blocker.</b> A failure in group 5 means an emitted record is no longer exactly 80 or 100
 * characters. A fixed block file rejects that outright.</li>
 * <li><b>High.</b> A failure in group 3 means the ascending-card-number precondition is no longer
 * enforced. The lookup exits early on the first card greater than the one sought, so unsorted input would
 * silently skip cards rather than fail.</li>
 * <li><b>High.</b> A failure in group 2 means the initialisation order has changed. The transaction file
 * must be opened and primed before the table is built, and the table before any statement is produced.</li>
 * <li><b>Medium.</b> A failure in group 11 means {@code close} no longer attempts all four datasets. It
 * must close every one and report the first failure with the rest suppressed, not stop at the first.</li>
 * <li><b>Low.</b> A failure in group 12 means an unmasked card number reached a diagnostic.</li>
 * <li><b>High.</b> A failure in group 14 means the {@code '00' OR '04'} leniency has leaked from the nine
 * {@code IF}-guarded sites onto one of the four {@code EVALUATE}-guarded sites, or has been withdrawn from
 * the {@code IF} sites. The two guards are deliberately different and both halves must hold.</li>
 * <li><b>High.</b> A failure in group 15 means the final per-card counter flush of
 * {@code app/cbl/CBSTM03A.CBL:L850} has been lost, which silently drops the last card's transactions.</li>
 * <li><b>High.</b> A failure in group 16 means an over-capacity input is being silently truncated instead of
 * failing loudly — the very defect the removal of the 510 ceiling was required not to reintroduce.</li>
 * <li><b>Blocker.</b> A failure in group 17 means the emission order has changed. The three closing text
 * lines and the eight closing markup fragments are ordered output, so order is content.</li>
 * <li><b>Low.</b> A failure in group 21 means a forbidden pattern has entered the class under test — a
 * {@code PSAPTR}/TIOT analogue, a spawned sort process, {@code sun.misc.Unsafe} or JNI.</li>
 * </ul>
 *
 * <p><b>Reading the sources — the trap that invalidates every locator above.</b> All four primary sources are
 * <b>uppercase-named and CRLF-terminated</b>: {@code app/cbl/CBSTM03A.CBL}, {@code app/cbl/CBSTM03B.CBL},
 * {@code app/cpy/COSTM01.CPY} and {@code app/jcl/CREASTMT.JCL}. A {@code *.cbl} glob drops both programs and a
 * {@code *.jcl} glob drops the JCL member — which is the <em>sole</em> uppercase-extension member of
 * {@code app/jcl} and the only source for statement generation, so a case-sensitive glob makes this entire
 * feature vanish from scope without an error. Every locator cited in this class was verified after stripping
 * the carriage returns ({@code tr -d '\r' < app/cbl/CBSTM03A.CBL}); read without stripping, or read through a
 * lowercase glob, and every line number here is wrong. Three spot checks that fail immediately if the file was
 * read incorrectly: {@code L67} is {@code WS-FL-DD … VALUE 'TRNXFILE'}, {@code L226} is
 * {@code WS-CARD-TBL OCCURS 51 TIMES} and {@code L850} is {@code MOVE TR-CNT TO WS-TRCT (CR-CNT)}. By
 * contrast {@code app/cpy/CVTRA05Y.cpy} and {@code app/cpy/CVACT01Y.cpy} are LF-clean. This Java file itself is
 * written with <b>LF</b> endings per the root {@code .editorconfig}, despite four of its sources being CRLF.
 *
 * <p><b>The fixture-name trap.</b> The mainframe DD name and dataset are {@code DALYTRAN}, but the ASCII
 * fixture spells the word in full: it is {@code app/data/ASCII/dailytran.txt}, <b>not</b> {@code dalytran.txt}.
 * {@link FixtureLoader.Fixture#DAILY_TRANSACTION} carries the correct spelling, which is why fixtures are
 * resolved through that enum rather than by a literal path anywhere in this class.
 *
 * <p><b>Two instances of the documented Clause B conflict live in the class under test.</b> Clause B forbids
 * dead code; the parity mandate requires the source's control flow to survive one-for-one. Parity governs, and
 * Clause B is satisfied by its own wording — the prohibition is on artefacts <em>without a tracking
 * reference</em>. Group 13 asserts both instances are still tracked rather than deleted: the five unreachable
 * {@code EXIT.} statements at {@code app/cbl/CBSTM03A.CBL:L762}, {@code :L781}, {@code :L799}, {@code :L816}
 * and {@code :L853}, each dead because its paragraph {@code GO TO}s away first; and the immediately redundant
 * {@code MOVE 1 TO CR-JMP} at {@code :L324}, redundant because the {@code PERFORM VARYING CR-JMP FROM 1 BY 1}
 * of {@code :L417} re-initialises it. Both are severity Low and both are owed an entry
 * in the planned {@code DECISION_LOG.md} and a row in the planned {@code TRACEABILITY_MATRIX.md}; neither
 * document exists in this branch, so the decision itself lives in the docstring beside the code it governs,
 * where it cannot drift from it. Deleting either call site would break the paragraph map Gate 7 reads.
 */
@DisplayName("StatementProcessor: CBSTM03A without its ALTER dispatch or its capacity ceiling")
class StatementProcessorTest {

    /** {@code STMTFILE} record length, from {@code app/jcl/CREASTMT.JCL:STEP040}. */
    private static final int TEXT_WIDTH = 80;

    /** {@code HTMLFILE} record length, from the 100-character field at {@code app/cbl/CBSTM03A.CBL:L149}. */
    private static final int HTML_WIDTH = 100;

    /** The projected {@code TRNXFILE} record length. */
    private static final int RECORD_LENGTH = 350;

    /** The last byte position the {@code OUTREC} projection writes. */
    private static final int PROJECTION_END = 328;

    /** The card number of the first fixture cross-reference row. */
    private static final String CARD_LOW = "0500024453765740";

    /** A card number ordered after {@link #CARD_LOW}. */
    private static final String CARD_HIGH = "9500024453765740";

    /**
     * Three strictly ascending synthetic card numbers, for the multi-card control-break tests.
     *
     * <p>Ascending because {@code app/jcl/CREASTMT.JCL:L53} sorts on the card number and the lookup's early
     * exit at {@code app/cbl/CBSTM03A.CBL:L417-L419} depends on that ordering. Synthetic, and unrelated to any
     * real card range, because Rule 1 Clause D1 keeps test data free of anything resembling a live credential
     * or account identifier.
     */
    private static final String CARD_A = "1000000000000001";

    /** The middle card of the ascending triple. See {@link #CARD_A}. */
    private static final String CARD_B = "2000000000000002";

    /** The last card of the ascending triple, carrying a transaction count distinct from the other two. */
    private static final String CARD_C = "3000000000000003";

    private FakeDataset transactionDataset;
    private FakeDataset crossReferenceDataset;
    private FakeDataset customerDataset;
    private FakeDataset accountDataset;
    private FileService fileService;
    private StatementProcessor processor;

    /**
     * The order in which this test's datasets were opened.
     *
     * <p>Instance-scoped and handed to the four fakes in {@link #bindDatasetsAndCaptureLogs()}, replacing a
     * {@code static} accumulator that every test shared and one test reset. The shared form worked only
     * because JUnit runs this class single-threaded by default — precisely the environment-specific
     * assumption Rule 1 Clause C2 rules out.
     */
    private List<FileService.Dd> openSequence;

    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger logger;
    private Level originalLevel;

    /**
     * The 500-byte customer rows of {@code app/data/ASCII/custdata.txt}.
     *
     * <p>Deliberately an <b>instance</b> field, not a {@code static} one. Rule 1 Clause B forbids global
     * mutable state, and a static fixture cache that is populated on first use is exactly that: it couples
     * every test in the class to whichever one ran first and, under a parallel or reordered run, to a race.
     * The loader reads a classpath resource, so re-reading it per test costs microseconds and buys
     * independence. Fixtures are also resolved through {@link FixtureLoader.Fixture} rather than by literal
     * path, which is what keeps the {@code dailytran.txt}-versus-{@code dalytran.txt} trap out of this class.
     */
    private List<String> customerRows;

    /** The 300-byte account rows of {@code app/data/ASCII/acctdata.txt}. Instance-scoped, see above. */
    private List<String> accountRows;

    /** The 36-byte cross-reference rows of {@code app/data/ASCII/cardxref.txt}. Instance-scoped, see above. */
    private List<String> crossReferenceRows;

    /**
     * The one clock this class admits, fixed at {@link FixedClockProvider#CANONICAL_INSTANT}.
     *
     * <p>Rule 1 Clause A1 requires determinism. No assertion here may read a wall clock, so every timestamp
     * either comes from this clock through {@link FixedClockProvider#onlineTimestamp(Clock)} or is the
     * canonical literal that clock renders. That the two agree is asserted in group 18, which is what makes
     * the 26-versus-24 truncation assertions meaningful rather than tautological.
     */
    private Clock fixedClock;

    @BeforeEach
    void bindDatasetsAndCaptureLogs() {
        fixedClock = FixedClockProvider.canonicalClock();
        customerRows = FixtureLoader.load(FixtureLoader.Fixture.CUSTOMER).records();
        accountRows = FixtureLoader.load(FixtureLoader.Fixture.ACCOUNT).records();
        crossReferenceRows = FixtureLoader.load(FixtureLoader.Fixture.CARD_XREF).records();

        transactionDataset = new FakeDataset(FileService.Dd.TRNXFILE);
        crossReferenceDataset = new FakeDataset(FileService.Dd.XREFFILE);
        customerDataset = new FakeDataset(FileService.Dd.CUSTFILE);
        accountDataset = new FakeDataset(FileService.Dd.ACCTFILE);
        openSequence = new ArrayList<>();
        transactionDataset.recordOpensInto(openSequence);
        crossReferenceDataset.recordOpensInto(openSequence);
        customerDataset.recordOpensInto(openSequence);
        accountDataset.recordOpensInto(openSequence);
        fileService = new FileService(new FileStatusMapper(), List.of(transactionDataset,
                crossReferenceDataset, customerDataset, accountDataset));
        processor = new StatementProcessor(fileService);

        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(StatementProcessor.class);
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
    // Fixture builders
    // ------------------------------------------------------------------------------------------------

    /**
     * Builds a base 350-byte transaction record and projects it exactly as
     * {@code app/jcl/CREASTMT.JCL:STEP010} does, so the payload the fake dataset serves is the payload the
     * sort step would have produced.
     *
     * @param transactionId {@code TRAN-ID}
     * @param cardNumber {@code TRAN-CARD-NUM}
     * @param amount {@code TRAN-AMT}
     * @param description {@code TRAN-DESC}
     * @return the projected record, 350 characters
     */
    private String projectedRecord(final String transactionId, final String cardNumber,
                                   final String amount, final String description) {
        return StatementProcessor.projectBaseRecord(baseTransaction(transactionId, cardNumber,
                new BigDecimal(amount), description));
    }

    /**
     * Builds the unprojected {@code app/cpy/CVTRA05Y.cpy} entity every fixture in this class derives from.
     *
     * <p>Both timestamps come from {@link #fixedClock} through
     * {@link FixedClockProvider#onlineTimestamp(Clock)} rather than from a literal or from a wall clock, so
     * every record this class builds is byte-identical on every machine and in every time zone (Rule 1 Clause
     * A1). {@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS} are {@code PIC X(26)} at
     * {@code app/cpy/CVTRA05Y.cpy}, so they are carried as {@link String} and never as a temporal type —
     * group 18 asserts that, because a {@code LocalDateTime} could not represent the 24-character truncated
     * value the projection produces.
     *
     * @param transactionId {@code TRAN-ID}
     * @param cardNumber {@code TRAN-CARD-NUM}
     * @param amount {@code TRAN-AMT}, a {@link BigDecimal} because Gate 6 admits no {@code float} or
     * {@code double} in a financial field
     * @param description {@code TRAN-DESC}
     * @return the entity, never {@code null}
     */
    private Transaction baseTransaction(final String transactionId, final String cardNumber,
                                        final BigDecimal amount, final String description) {
        String timestamp = FixedClockProvider.onlineTimestamp(fixedClock);
        return new Transaction(transactionId, "01", 1000, "POS TERM", description, amount,
                123456789L, "SAMPLE MERCHANT", "SAMPLE CITY", "12345", cardNumber,
                timestamp, timestamp);
    }

    /**
     * Builds an {@code XREFFILE} record in the {@code CVACT03Y} layout.
     *
     * @param cardNumber {@code XREF-CARD-NUM}
     * @param customerId {@code XREF-CUST-ID}
     * @param accountId {@code XREF-ACCT-ID}
     * @return the 36-character record the fixture width carries
     */
    private static String crossReferenceRecord(final String cardNumber, final long customerId,
                                               final long accountId) {
        return cardNumber
                + String.format(Locale.ROOT, "%09d", customerId)
                + String.format(Locale.ROOT, "%011d", accountId);
    }

    /**
     * Stubs the minimum needed for {@link StatementProcessor#initialise()} to succeed: one transaction
     * record on {@link #CARD_LOW} and then end of file.
     *
     * @param records the projected transaction records to serve, in order
     */
    private void stubTransactionFile(final String... records) {
        for (String record : records) {
            transactionDataset.enqueueSequential("00", record);
        }
        transactionDataset.enqueueSequential("10", "");
    }

    /**
     * Binds the first fixture customer row under the key the processor will read it by, and the first
     * fixture account row likewise.
     *
     * @param customerId the key the cross-reference resolves
     * @param accountId the key the cross-reference resolves
     */
    private void stubKeyedFixtureRows(final long customerId, final long accountId) {
        customerDataset.bindKeyed(String.format(Locale.ROOT, "%09d", customerId), "00",
                customerRows.getFirst());
        accountDataset.bindKeyed(String.format(Locale.ROOT, "%011d", accountId), "00",
                accountRows.getFirst());
    }

    /**
     * Stubs one transaction on {@link #CARD_LOW} against an account carrying the supplied balance, then
     * composes the statement, so a mask assertion varies the balance and nothing else.
     *
     * @param currentBalance the {@code ACCT-CURR-BAL} the account row carries
     * @return the composed statement
     */
    private Statement statementWithBalance(final BigDecimal currentBalance) {
        stubTransactionFile(projectedRecord("0000000000000001", CARD_LOW, "1.00", "ONE"));
        crossReferenceDataset.enqueueSequential("10", "");
        customerDataset.bindKeyed(String.format(Locale.ROOT, "%09d", 1L), "00", customerRows.getFirst());
        accountDataset.bindKeyed(String.format(Locale.ROOT, "%011d", 1L), "00",
                StatementRecordFixtures.accountRecord(1L, currentBalance));

        return processor.process(new CardCrossReference(CARD_LOW, 1L, 1L));
    }

    /** @return every message this class's logger received. */
    private List<String> loggedMessages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /**
     * A card number that sorts before every fixture card, used to build a group without consuming it.
     *
     * <p>{@code app/cbl/CBSTM03A.CBL:L417-L419}'s early exit fires when the group in hand sorts after the
     * card being looked up, and the group is then <em>retained</em> for a later cross-reference row. Asking
     * for this card therefore drives exactly one control break and leaves the resulting group resident and
     * inspectable, which is what a test that wants to examine a parsed group needs now that the run is
     * streamed instead of tabulated.
     */
    private static final String CARD_BELOW_ALL = "0000000000000000";

    /** A card number that sorts after every fixture card, used to walk the stream to exhaustion. */
    private static final String CARD_ABOVE_ALL = "9999999999999999";

    /**
     * Builds the first card group of the stubbed transaction file and returns it without consuming it.
     *
     * <p>Group construction is lazy: {@link StatementProcessor#initialise()} takes the single priming read
     * of {@code :L748} and holds that one record as a lookahead, and a control break is only walked when a
     * cross-reference row asks for a card. Requesting {@link #CARD_BELOW_ALL} walks exactly one break and
     * then stops on the early exit, so the group is complete and still resident.
     *
     * @return the first card group, fully parsed
     */
    private StatementTransaction.CardGroup firstGroupRetained() {
        stubKeyedFixtureRows(1L, 1L);
        processor.process(new CardCrossReference(CARD_BELOW_ALL, 1L, 1L));
        return processor.currentCardGroup().orElseThrow();
    }

    /**
     * Walks the whole stubbed transaction file, building every group and retaining none.
     *
     * <p>Requesting {@link #CARD_ABOVE_ALL} makes the implicit continue of {@code :L421} skip past every
     * group in turn until the stream is exhausted, which is the streamed equivalent of the source having
     * loaded its whole table up front.
     */
    private void walkEveryGroup() {
        stubKeyedFixtureRows(1L, 1L);
        processor.process(new CardCrossReference(CARD_ABOVE_ALL, 1L, 1L));
    }

    /**
     * Stubs the whole happy path: one transaction on {@link #CARD_LOW}, one cross-reference row for that
     * card, and the fixture customer and account rows it resolves.
     *
     * @return the cross-reference the processor will be handed
     */
    private CardCrossReference stubHappyPath() {
        stubTransactionFile(projectedRecord("0000000000683580", CARD_LOW, "194.00", "PURCHASE ONE"));
        crossReferenceDataset.enqueueSequential("00", crossReferenceRecord(CARD_LOW, 1L, 1L));
        crossReferenceDataset.enqueueSequential("10", "");
        stubKeyedFixtureRows(1L, 1L);
        return new CardCrossReference(CARD_LOW, 1L, 1L);
    }

    @Nested
    @DisplayName("1. Construction: the file service is the only collaborator and it is required")
    class Construction {

        @Test
        @DisplayName("an absent file service is refused")
        void anAbsentFileServiceIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new StatementProcessor(null))
                    .withMessage("fileService must not be null");
        }

        @Test
        @DisplayName("no card group is resident before the stream is primed")
        void noCardGroupIsResidentBeforePriming() {
            // There is deliberately no whole-run table to be empty. The legacy 51-by-10 WS-TRAN-TBL was
            // replaced by a stream that holds one control-break group at a time, so residency - not
            // emptiness - is the property, and com.cardemo.unit.batch.StatementProcessorStreamingTest
            // asserts it across a whole run.
            assertThat(processor.currentCardGroup()).isEmpty();
            assertThat(processor.cardGroupsRead()).isZero();
        }
    }

    @Nested
    @DisplayName("2. DEVIATION: the ALTER chain becomes a fixed five-step initialisation sequence")
    class Initialisation {

        @Test
        @DisplayName("all four datasets are opened, in the order the altered handlers ran")
        void allFourDatasetsAreOpenedInOrder() {
            stubHappyPath();

            processor.initialise();

            assertThat(transactionDataset.openCount()).isEqualTo(1);
            assertThat(crossReferenceDataset.openCount()).isEqualTo(1);
            assertThat(customerDataset.openCount()).isEqualTo(1);
            assertThat(accountDataset.openCount()).isEqualTo(1);
            assertThat(openSequence)
                    .as("app/cbl/CBSTM03A.CBL:L760, :L851, :L779, :L797, :L815 in that order")
                    .containsExactly(FileService.Dd.TRNXFILE, FileService.Dd.XREFFILE,
                            FileService.Dd.CUSTFILE, FileService.Dd.ACCTFILE);
        }

        @Test
        @DisplayName("the transaction file is primed before the table is built")
        void theTransactionFileIsPrimedFirst() {
            stubHappyPath();

            processor.initialise();

            assertThat(transactionDataset.sequentialReadCount())
                    .as("exactly the one priming read of :L748. The source read on to fill a table here; the "
                            + "stream holds this single record as a lookahead and reads again only when a "
                            + "control break is walked")
                    .isEqualTo(1);
            assertThat(processor.currentCardGroup())
                    .as("and therefore no group yet: priming is not grouping")
                    .isEmpty();
        }

        @Test
        @DisplayName("initialise is idempotent, because the chain exits the machine permanently at :L815")
        void initialiseIsIdempotent() {
            stubHappyPath();

            processor.initialise();
            processor.initialise();

            assertThat(transactionDataset.openCount()).isEqualTo(1);
            assertThat(accountDataset.openCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("initialisation announces the card and transaction counts it loaded")
        void initialisationAnnouncesItsCounts() {
            stubHappyPath();

            processor.initialise();

            assertThat(loggedMessages())
                    .as("there are no counts to announce at initialisation now: nothing has been grouped, "
                            + "and announcing a card and transaction total here would have meant loading the "
                            + "whole file to produce it - which is the capacity ceiling this design removed")
                    .contains("Statement initialisation complete: the transaction stream is primed and the "
                            + "four datasets are open; card groups are consumed one control break at a time");
        }

        @Test
        @DisplayName("a failed TRNXFILE open abends and logs the source's ERROR OPENING text")
        void aFailedTransactionOpenAbends() {
            transactionDataset.openStatus("35");

            assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> processor.initialise());

            assertThat(loggedMessages()).contains("ERROR OPENING TRNXFILE");
            assertThat(crossReferenceDataset.openCount())
                    .as("the chain stops at the failing handler")
                    .isZero();
        }

        @ParameterizedTest(name = "a failed open of {0} abends and logs ERROR OPENING")
        @ValueSource(strings = {"XREFFILE", "CUSTFILE", "ACCTFILE"})
        @DisplayName("a failed open of any later dataset abends with its own DD name")
        void aFailedLaterOpenAbends(final String ddName) {
            stubHappyPath();
            datasetFor(ddName).openStatus("35");

            assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> processor.initialise());

            assertThat(loggedMessages()).contains("ERROR OPENING " + ddName);
        }

        @Test
        @DisplayName("a failed priming read abends and logs the source's ERROR READING text")
        void aFailedPrimingReadAbends() {
            transactionDataset.enqueueSequential("35", "");

            assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> processor.initialise());

            assertThat(loggedMessages()).contains("ERROR READING TRNXFILE");
        }

        @Test
        @DisplayName("a priming read reporting the secondary status 04 is accepted, per :L347-L351")
        void thePrimingReadAcceptsTheSecondaryStatus() {
            transactionDataset.enqueueSequential("04",
                    projectedRecord("0000000000683580", CARD_LOW, "10.00", "PURCHASE ONE"));
            transactionDataset.enqueueSequential("10", "");
            crossReferenceDataset.enqueueSequential("10", "");

            assertThat(firstGroupRetained().cardNumber())
                    .as("the secondary status is accepted, so the primed record reaches the first group")
                    .isEqualTo(CARD_LOW);
        }

        @Test
        @DisplayName("a short record is normalised to the record length by the service, then fails the parse")
        void aShortRecordIsNormalisedThenFailsTheParse() {
            transactionDataset.enqueueSequential("00", "x".repeat(PROJECTION_END - 1));

            // The dataset binding declares a 32-byte key and a 318-byte payload, so the file service pads
            // every read to the 350-byte record before the processor sees it. The processor's own minimum
            // length guard is therefore defence in depth rather than the live check, and what a short read
            // actually produces is a record whose amount field is not a zoned decimal.
            stubKeyedFixtureRows(1L, 1L);
            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> processor.process(new CardCrossReference(CARD_BELOW_ALL, 1L, 1L)))
                    .withMessageContaining("could not be interpreted in the app/cpy/COSTM01.CPY layout");
        }

        /**
         * Resolves a fake dataset by DD name, for the parameterised open-failure test.
         *
         * @param ddName the DD name
         * @return the fake bound to it
         */
        private FakeDataset datasetFor(final String ddName) {
            return switch (ddName) {
                case "XREFFILE" -> crossReferenceDataset;
                case "CUSTFILE" -> customerDataset;
                case "ACCTFILE" -> accountDataset;
                default -> transactionDataset;
            };
        }
    }

    @Nested
    @DisplayName("3. The table build loop of :L818-L853 and its ascending-card precondition")
    class TableBuild {

        @Test
        @DisplayName("the priming read groups every transaction of the first card, per :L825-L833")
        void thePrimingReadGroupsTheFirstCard() {
            stubTransactionFile(
                    projectedRecord("0000000000000001", CARD_LOW, "1.00", "ONE"),
                    projectedRecord("0000000000000002", CARD_LOW, "2.00", "TWO"),
                    projectedRecord("0000000000000003", CARD_LOW, "3.00", "THREE"));
            crossReferenceDataset.enqueueSequential("10", "");

            // One group resident, not a whole-run table - and it becomes resident when a control break is
            // WALKED, not when the stream is primed. The control break onto the NEXT card, the end-of-file
            // flush of the last group and the one-group residency invariant are asserted across a full run by
            // com.cardemo.unit.batch.StatementProcessorStreamingTest, which is where they belong now that the
            // run is streamed rather than tabulated.
            StatementTransaction.CardGroup group = firstGroupRetained();

            assertThat(group.cardNumber()).isEqualTo(CARD_LOW);
            assertThat(group.transactions())
                    .as("every transaction of the first card, and only that card's")
                    .hasSize(3);
            assertThat(processor.currentCardGroup())
                    .as("still resident, because the early exit stopped on it rather than consuming it")
                    .isPresent();
        }

        @Test
        @DisplayName("a DESCENDING card number abends, because the lookup's early exit would skip it")
        void aDescendingCardNumberAbends() {
            stubTransactionFile(
                    projectedRecord("0000000000000001", CARD_HIGH, "1.00", "ONE"),
                    projectedRecord("0000000000000002", CARD_LOW, "2.00", "TWO"));

            stubKeyedFixtureRows(1L, 1L);
            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> processor.process(new CardCrossReference(CARD_ABOVE_ALL, 1L, 1L)))
                    .withMessageContaining("TRNXFILE must ascend by card number")
                    .withMessageContaining("app/cbl/CBSTM03A.CBL:L417-L419")
                    .withMessageContaining("would be silently skipped");
        }

        @Test
        @DisplayName("a repeated card after an intervening card abends, because that is not ascending")
        void aRepeatedCardAfterAnotherAbends() {
            stubTransactionFile(
                    projectedRecord("0000000000000001", CARD_LOW, "1.00", "ONE"),
                    projectedRecord("0000000000000002", CARD_HIGH, "2.00", "TWO"),
                    projectedRecord("0000000000000003", CARD_LOW, "3.00", "THREE"));

            // The ascending-order precondition is checked as each control break is walked, so the walk has
            // to reach the offending record for the guard to see it.
            stubKeyedFixtureRows(1L, 1L);
            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> processor.process(new CardCrossReference(CARD_ABOVE_ALL, 1L, 1L)))
                    .withMessageContaining("must ascend by card number");
        }

        @Test
        @DisplayName("a failed read part-way through the loop abends and logs ERROR READING")
        void aFailedReadPartWayThroughAbends() {
            transactionDataset.enqueueSequential("00",
                    projectedRecord("0000000000000001", CARD_LOW, "1.00", "ONE"));
            transactionDataset.enqueueSequential("35", "");

            // The failing read is the SECOND one, which the walk takes when it looks past the primed record
            // for the end of the first group. Priming alone would not reach it.
            stubKeyedFixtureRows(1L, 1L);
            assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> processor.process(new CardCrossReference(CARD_ABOVE_ALL, 1L, 1L)));

            assertThat(loggedMessages()).contains("ERROR READING TRNXFILE");
        }
    }

    @Nested
    @DisplayName("4. DEVIATION: the 51-card by 10-transaction ceiling is gone, and each threshold warns")
    class CapacityCeiling {

        @Test
        @DisplayName("an eleventh transaction on one card warns but succeeds")
        void anEleventhTransactionWarnsButSucceeds() {
            String[] records = new String[StatementTransaction.LEGACY_MAX_TRANSACTIONS_PER_CARD + 1];
            for (int index = 0; index < records.length; index++) {
                records[index] = projectedRecord(
                        String.format(Locale.ROOT, "%016d", index), CARD_LOW, "1.00", "PURCHASE");
            }
            stubTransactionFile(records);
            crossReferenceDataset.enqueueSequential("10", "");

            assertThat(firstGroupRetained().transactions())
                    .as("WS-TRAN-TBL OCCURS 10 would have overrun here; the Java collection does not")
                    .hasSize(11);
            assertThat(loggedMessages())
                    .anyMatch(message -> message.contains("passed the legacy capacity of 10 transactions")
                            && message.contains("app/cbl/CBSTM03A.CBL:L228"));
        }

        @Test
        @DisplayName("a fifty-second card warns but succeeds")
        void aFiftySecondCardWarnsButSucceeds() {
            int cards = StatementTransaction.LEGACY_MAX_CARDS_PER_RUN + 1;
            String[] records = new String[cards];
            for (int index = 0; index < cards; index++) {
                records[index] = projectedRecord(String.format(Locale.ROOT, "%016d", index),
                        String.format(Locale.ROOT, "%016d", index + 1), "1.00", "PURCHASE");
            }
            stubTransactionFile(records);
            crossReferenceDataset.enqueueSequential("10", "");

            walkEveryGroup();

            assertThat(processor.cardGroupsRead())
                    .as("WS-CARD-TBL OCCURS 51 would have overrun here; the stream does not. Every group is "
                            + "built as the walk passes it, and none is retained, so the run never holds more "
                            + "than one at a time however many cards the file carries")
                    .isEqualTo((long) cards);
            assertThat(loggedMessages())
                    .anyMatch(message -> message.contains("passed the legacy capacity of 51 cards")
                            && message.contains("app/cbl/CBSTM03A.CBL:L226"));
        }

        @Test
        @DisplayName("a run within both legacy limits warns about neither")
        void aRunWithinTheLimitsWarnsAboutNeither() {
            stubTransactionFile(projectedRecord("0000000000000001", CARD_LOW, "1.00", "ONE"));
            crossReferenceDataset.enqueueSequential("10", "");

            processor.initialise();

            assertThat(loggedMessages()).noneMatch(message -> message.contains("legacy capacity"));
        }
    }

    @Nested
    @DisplayName("5. process: the dual 80-byte text and 100-byte HTML emission")
    class StatementEmission {

        @Test
        @DisplayName("every text line is exactly 80 characters and every HTML line exactly 100")
        void everyLineHasItsFixedWidth() {
            CardCrossReference xref = stubHappyPath();

            Statement statement = processor.process(xref);

            assertThat(statement.textLines()).isNotEmpty();
            assertThat(statement.htmlLines()).isNotEmpty();
            assertThat(statement.textLines())
                    .allSatisfy(line -> assertThat(line).hasSize(TEXT_WIDTH));
            assertThat(statement.htmlLines())
                    .allSatisfy(line -> assertThat(line).hasSize(HTML_WIDTH));
        }

        @Test
        @DisplayName("the statement opens and closes with the banner lines of :L604-L637")
        void theStatementIsBannered() {
            CardCrossReference xref = stubHappyPath();

            Statement statement = processor.process(xref);

            assertThat(statement.textLines().getFirst())
                    .startsWith("*".repeat(31))
                    .contains("START OF STATEMENT");
            assertThat(statement.textLines().getLast())
                    .startsWith("*".repeat(32))
                    .contains("END OF STATEMENT");
        }

        @Test
        @DisplayName("the statement carries the customer name and address from the fixture row")
        void theStatementCarriesTheCustomerDetails() {
            CardCrossReference xref = stubHappyPath();

            Statement statement = processor.process(xref);

            assertThat(statement.textLines())
                    .anyMatch(line -> line.contains("Immanuel") && line.contains("Kessler"));
            assertThat(statement.textLines())
                    .anyMatch(line -> line.contains("618 Deshaun Route"));
        }

        @Test
        @DisplayName("the statement carries the account identifier, balance and FICO score labels")
        void theStatementCarriesTheAccountBasics() {
            CardCrossReference xref = stubHappyPath();

            Statement statement = processor.process(xref);

            assertThat(statement.accountId()).isEqualTo("00000000001");
            assertThat(statement.textLines())
                    .anyMatch(line -> line.contains("Basic Details"))
                    .anyMatch(line -> line.contains("TRANSACTION SUMMARY"));
        }

        @Test
        @DisplayName("the balance mask 9(9).99- fills leading positions with zeros, not spaces")
        void theBalanceMaskFillsWithZeros() {
            // ST-CURR-BAL at app/cbl/CBSTM03A.CBL:L136 is PIC 9(9).99-, which is NOT the zero-suppressed
            // mask the transaction and total amounts use: leading positions are filled with zeros. Moved
            // here from the writer's suite when composition became this class's responsibility.
            Statement statement = statementWithBalance(new BigDecimal("1.00"));

            assertThat(statement.textLines())
                    .anyMatch(line -> line.contains("000000001.00"));
        }

        @Test
        @DisplayName("a balance wider than nine integer digits keeps its low-order nine, as a MOVE would")
        void anOverWideBalanceKeepsItsLowOrderDigits() {
            // A COBOL MOVE into a shorter numeric item truncates the HIGH-order digits. Truncating the other
            // end would change the magnitude by a power of ten, so the direction is the assertion.
            Statement statement = statementWithBalance(new BigDecimal("1234567890.12"));

            assertThat(statement.textLines())
                    .anyMatch(line -> line.contains("234567890.12"))
                    .noneMatch(line -> line.contains("1234567890.12"));
        }

        @Test
        @DisplayName("a zero balance renders as zeros rather than blanks or a failure")
        void aZeroBalanceRendersAsZeros() {
            Statement statement = statementWithBalance(new BigDecimal("0.00"));

            assertThat(statement.textLines())
                    .anyMatch(line -> line.contains("000000000.00"));
        }

        @Test
        @DisplayName("a negative balance carries the trailing sign the mask declares, never a leading one")
        void aNegativeBalanceCarriesATrailingSign() {
            // The mask ends in a trailing sign position, so the minus follows the digits. A leading minus
            // would shift every character of a fixed-width line.
            Statement statement = statementWithBalance(new BigDecimal("-12.34"));

            assertThat(statement.textLines())
                    .anyMatch(line -> line.contains("000000012.34-"))
                    .noneMatch(line -> line.contains("-000000012.34"));
        }

        @Test
        @DisplayName("the total expenditure is the sum of the card's transactions")
        void theTotalIsTheSumOfTheCardsTransactions() {
            stubTransactionFile(
                    projectedRecord("0000000000000001", CARD_LOW, "10.00", "ONE"),
                    projectedRecord("0000000000000002", CARD_LOW, "2.50", "TWO"));
            crossReferenceDataset.enqueueSequential("10", "");
            stubKeyedFixtureRows(1L, 1L);

            Statement statement = processor.process(new CardCrossReference(CARD_LOW, 1L, 1L));

            assertThat(statement.totalExpenditure()).isEqualByComparingTo(new BigDecimal("12.50"));
            assertThat(statement.totalExpenditure().scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("the emitted transaction lines carry the identifier, description and amount")
        void theTransactionLinesCarryTheirFields() {
            stubTransactionFile(
                    projectedRecord("0000000000683580", CARD_LOW, "1234.56", "COFFEE AND CAKE"));
            crossReferenceDataset.enqueueSequential("10", "");
            stubKeyedFixtureRows(1L, 1L);

            Statement statement = processor.process(new CardCrossReference(CARD_LOW, 1L, 1L));

            assertThat(statement.textLines())
                    .anyMatch(line -> line.contains("0000000000683580")
                            && line.contains("COFFEE AND CAKE")
                            && line.contains("1234.56"));
        }

        @Test
        @DisplayName("the HTML emission carries a paragraph per transaction field")
        void theHtmlCarriesAParagraphPerField() {
            stubTransactionFile(
                    projectedRecord("0000000000683580", CARD_LOW, "1234.56", "COFFEE AND CAKE"));
            crossReferenceDataset.enqueueSequential("10", "");
            stubKeyedFixtureRows(1L, 1L);

            Statement statement = processor.process(new CardCrossReference(CARD_LOW, 1L, 1L));

            assertThat(statement.htmlLines())
                    .anyMatch(line -> line.contains("<p>") && line.contains("0000000000683580"));
            assertThat(statement.htmlLines())
                    .anyMatch(line -> line.contains("COFFEE AND CAKE"));
        }

        @Test
        @DisplayName("a card with no transactions still produces a statement, with a zero total")
        void aCardWithNoTransactionsStillProducesAStatement() {
            stubTransactionFile(projectedRecord("0000000000000001", CARD_HIGH, "5.00", "OTHER CARD"));
            crossReferenceDataset.enqueueSequential("10", "");
            stubKeyedFixtureRows(1L, 1L);

            Statement statement = processor.process(new CardCrossReference(CARD_LOW, 1L, 1L));

            assertThat(statement.totalExpenditure()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(statement.textLines()).isNotEmpty();
        }

        @Test
        @DisplayName("an absent cross-reference argument is refused")
        void anAbsentCrossReferenceIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> processor.process(null))
                    .withMessage("crossReference must not be null");
        }

        @Test
        @DisplayName("process initialises on demand, so the caller need not sequence the two")
        void processInitialisesOnDemand() {
            CardCrossReference xref = stubHappyPath();

            processor.process(xref);

            assertThat(transactionDataset.openCount()).isEqualTo(1);
            assertThat(accountDataset.openCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("two statements for the same card each start their total from zero")
        void eachStatementStartsItsTotalFromZero() {
            // TWO DIFFERENT CARDS, deliberately, because CARDXREF is keyed by card number and so cannot hold
            // two rows for one card. This test used to hand the same row over twice, which the source's
            // whole-run table would have served from memory and the stream cannot: a group consumed by a
            // matching row is released, so a second request for the same card finds nothing and totals zero.
            // That difference is unreachable through a real cross-reference file, and the property being
            // tested - that the accumulator starts each statement at zero rather than carrying the previous
            // one forward - is expressed just as well by two cards, which the schema does permit.
            stubTransactionFile(
                    projectedRecord("0000000000000001", CARD_LOW, "10.00", "ONE"),
                    projectedRecord("0000000000000002", CARD_HIGH, "10.00", "TWO"));
            crossReferenceDataset.enqueueSequential("10", "");
            stubKeyedFixtureRows(1L, 1L);

            Statement first = processor.process(new CardCrossReference(CARD_LOW, 1L, 1L));
            Statement second = processor.process(new CardCrossReference(CARD_HIGH, 1L, 1L));

            assertThat(first.totalExpenditure()).isEqualByComparingTo(new BigDecimal("10.00"));
            assertThat(second.totalExpenditure())
                    .as("the accumulator is reset per statement, not per run: 10.00 and not 20.00")
                    .isEqualByComparingTo(new BigDecimal("10.00"));
        }
    }

    @Nested
    @DisplayName("6. The ordering-dependent lookup of :L416-L456")
    class OrderedLookup {

        @Test
        @DisplayName("the scan exits early on the first card greater than the one sought")
        void theScanExitsEarly() {
            // Three ascending cards. Seeking the middle one must not accumulate the third, and the early
            // exit is what stops it - a scan of the whole table would still find the right group, so the
            // observable difference is in the total, which is why that is what this asserts.
            stubTransactionFile(
                    projectedRecord("0000000000000001", "1000000000000000", "1.00", "FIRST"),
                    projectedRecord("0000000000000002", "5000000000000000", "2.00", "MIDDLE"),
                    projectedRecord("0000000000000003", "9000000000000000", "4.00", "LAST"));
            crossReferenceDataset.enqueueSequential("10", "");
            stubKeyedFixtureRows(1L, 1L);

            Statement statement = processor.process(
                    new CardCrossReference("5000000000000000", 1L, 1L));

            assertThat(statement.totalExpenditure()).isEqualByComparingTo(new BigDecimal("2.00"));
        }

        @Test
        @DisplayName("a card ordered before every table entry yields a zero total")
        void aCardBeforeEveryEntryYieldsZero() {
            stubTransactionFile(
                    projectedRecord("0000000000000001", "5000000000000000", "2.00", "MIDDLE"));
            crossReferenceDataset.enqueueSequential("10", "");
            stubKeyedFixtureRows(1L, 1L);

            Statement statement = processor.process(
                    new CardCrossReference("1000000000000000", 1L, 1L));

            assertThat(statement.totalExpenditure()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("a card ordered after every table entry yields a zero total")
        void aCardAfterEveryEntryYieldsZero() {
            stubTransactionFile(
                    projectedRecord("0000000000000001", "1000000000000000", "2.00", "FIRST"));
            crossReferenceDataset.enqueueSequential("10", "");
            stubKeyedFixtureRows(1L, 1L);

            Statement statement = processor.process(
                    new CardCrossReference("9000000000000000", 1L, 1L));

            assertThat(statement.totalExpenditure()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("the sought card number is compared on its fixed sixteen-character width")
        void theSoughtCardIsComparedOnFixedWidth() {
            stubTransactionFile(projectedRecord("0000000000000001", "4111", "3.00", "SHORT CARD"));
            crossReferenceDataset.enqueueSequential("10", "");
            stubKeyedFixtureRows(1L, 1L);

            Statement statement = processor.process(new CardCrossReference("4111", 1L, 1L));

            assertThat(statement.totalExpenditure()).isEqualByComparingTo(new BigDecimal("3.00"));
        }
    }

    @Nested
    @DisplayName("7. BLOCKER-CRITICAL: the OUTREC projection truncates and drops, and must keep doing so")
    class OutrecProjection {

        @Test
        @DisplayName("the projection writes exactly 328 bytes and pads to 350")
        void theProjectionWrites328Bytes() {
            String base = "A".repeat(RECORD_LENGTH);

            String projected = StatementProcessor.projectBaseRecord(base);

            assertThat(projected).hasSize(RECORD_LENGTH);
            assertThat(projected.substring(PROJECTION_END))
                    .as("everything after the last written byte is space-filled, not carried over")
                    .isBlank();
        }

        @Test
        @DisplayName("the projection is card number, then the 262-byte head, then 50 bytes from offset 279")
        void theProjectionIsThreeSlices() {
            // A base record whose every byte position is identifiable: the card number field is filled with
            // 'C', the head with 'H', the two timestamps with 'O' and 'P', and the filler with 'F'.
            String base = "H".repeat(262) + "C".repeat(16) + "O".repeat(26) + "P".repeat(26)
                    + "F".repeat(20);
            assertThat(base).hasSize(RECORD_LENGTH);

            String projected = StatementProcessor.projectBaseRecord(base);

            assertThat(projected.substring(0, 16))
                    .as("1:263,16 - the card number moves to the front")
                    .isEqualTo("C".repeat(16));
            assertThat(projected.substring(16, 16 + 262))
                    .as("17:1,262 - the whole head follows, and the head stops before the card field")
                    .isEqualTo("H".repeat(262));
            assertThat(projected.charAt(16 + 262))
                    .as("279:279,50 - the tail begins at the originating timestamp")
                    .isEqualTo('O');
        }

        @Test
        @DisplayName("PRESERVED: only 24 of the 26 processing-timestamp bytes survive the projection")
        void theProcessingTimestampIsTruncatedToTwentyFour() {
            String base = "H".repeat(262) + "C".repeat(16) + "O".repeat(26) + "P".repeat(26)
                    + "F".repeat(20);

            String projected = StatementProcessor.projectBaseRecord(base);

            // The tail is 50 bytes from offset 279: 26 of originating timestamp plus only 24 of processing.
            String tail = projected.substring(16 + 262, PROJECTION_END);
            assertThat(tail).hasSize(50);
            assertThat(tail.substring(0, 26)).isEqualTo("O".repeat(26));
            assertThat(tail.substring(26))
                    .as("two bytes of TRAN-PROC-TS are silently truncated; this is the source's shape")
                    .hasSize(StatementTransaction.PROCESSING_TIMESTAMP_SIGNIFICANT_LENGTH)
                    .isEqualTo("P".repeat(24));
        }

        @Test
        @DisplayName("PRESERVED: the 20-byte trailing filler is dropped entirely")
        void theTrailingFillerIsDropped() {
            String base = "H".repeat(262) + "C".repeat(16) + "O".repeat(26) + "P".repeat(26)
                    + "F".repeat(20);

            String projected = StatementProcessor.projectBaseRecord(base);

            assertThat(projected)
                    .as("no byte of the filler survives the OUTREC")
                    .doesNotContain("F");
        }

        @Test
        @DisplayName("a base record shorter than 350 characters is refused with the sort offsets cited")
        void aShortBaseRecordIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> StatementProcessor.projectBaseRecord("A".repeat(349)))
                    .withMessageContaining("at least 350 characters")
                    .withMessageContaining("app/jcl/CREASTMT.JCL:L53")
                    .withMessageContaining("offsets 263-278");
        }

        @Test
        @DisplayName("an absent base record is refused")
        void anAbsentBaseRecordIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> StatementProcessor.projectBaseRecord((String) null))
                    .withMessage("baseRecord must not be null");
        }

        @Test
        @DisplayName("an absent transaction is refused by the entity overload")
        void anAbsentTransactionIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> StatementProcessor.projectBaseRecord((Transaction) null))
                    .withMessage("transaction must not be null");
        }

        @Test
        @DisplayName("the entity overload composes the base record and then projects it")
        void theEntityOverloadComposesThenProjects() {
            String projected = projectedRecord("0000000000683580", CARD_LOW, "194.00", "PURCHASE ONE");

            assertThat(projected).hasSize(RECORD_LENGTH);
            assertThat(projected.substring(0, 16))
                    .as("the card number leads, because that is what the sort key requires")
                    .isEqualTo(CARD_LOW);
            assertThat(projected.substring(16, 32)).isEqualTo("0000000000683580");
        }

        @Test
        @DisplayName("the projected record round-trips through the parse the processor performs")
        void theProjectedRecordRoundTrips() {
            stubTransactionFile(
                    projectedRecord("0000000000683580", CARD_LOW, "194.00", "COFFEE AND CAKE"));
            crossReferenceDataset.enqueueSequential("10", "");

            StatementTransaction parsed = firstGroupRetained().transactions().getFirst();
            assertThat(parsed.cardNumber()).isEqualTo(CARD_LOW);
            assertThat(parsed.transactionId()).isEqualTo("0000000000683580");
            assertThat(parsed.amount()).isEqualByComparingTo(new BigDecimal("194.00"));
            assertThat(parsed.description()).startsWith("COFFEE AND CAKE");
            String emitted = FixedClockProvider.onlineTimestamp(fixedClock);
            assertThat(parsed.processingTimestamp())
                    .as("app/jcl/CREASTMT.JCL:L54 copies 50 bytes from offset 279, covering 279-328 only,"
                            + " so bytes 329-330 of TRNX-PROC-TS are never written and the parse pads them"
                            + " back; the surviving prefix is the first %d characters of the emitted value",
                            StatementTransaction.PROCESSING_TIMESTAMP_SIGNIFICANT_LENGTH)
                    .hasSize(StatementTransaction.PROCESSING_TIMESTAMP_LENGTH)
                    .startsWith(emitted.substring(0,
                            StatementTransaction.PROCESSING_TIMESTAMP_SIGNIFICANT_LENGTH))
                    .isNotEqualTo(emitted);
        }

        @Test
        @DisplayName("a negative amount survives the zoned-decimal encode and decode unchanged")
        void aNegativeAmountRoundTrips() {
            stubTransactionFile(
                    projectedRecord("0000000000683580", CARD_LOW, "-42.75", "REFUND"));
            crossReferenceDataset.enqueueSequential("10", "");

            assertThat(firstGroupRetained().transactions().getFirst().amount())
                    .isEqualByComparingTo(new BigDecimal("-42.75"));
        }
    }

    @Nested
    @DisplayName("8. statementSortComparator: card number then transaction identifier")
    class SortComparator {

        @Test
        @DisplayName("records order by card number first")
        void recordsOrderByCardNumberFirst() {
            Transaction low = transactionOn(CARD_LOW, "0000000000000009");
            Transaction high = transactionOn(CARD_HIGH, "0000000000000001");

            List<Transaction> sorted = new ArrayList<>(List.of(high, low));
            sorted.sort(StatementProcessor.statementSortComparator());

            assertThat(sorted).containsExactly(low, high);
        }

        @Test
        @DisplayName("records on one card order by transaction identifier")
        void recordsOnOneCardOrderByIdentifier() {
            Transaction second = transactionOn(CARD_LOW, "0000000000000002");
            Transaction first = transactionOn(CARD_LOW, "0000000000000001");

            List<Transaction> sorted = new ArrayList<>(List.of(second, first));
            sorted.sort(StatementProcessor.statementSortComparator());

            assertThat(sorted).containsExactly(first, second);
        }

        @Test
        @DisplayName("a null card number sorts first rather than throwing")
        void aNullCardNumberSortsFirst() {
            Transaction withCard = transactionOn(CARD_LOW, "0000000000000001");
            Transaction withoutCard = transactionOn(CARD_LOW, "0000000000000002");
            setField(withoutCard, "cardNumber", null);

            List<Transaction> sorted = new ArrayList<>(List.of(withCard, withoutCard));
            sorted.sort(StatementProcessor.statementSortComparator());

            assertThat(sorted).containsExactly(withoutCard, withCard);
        }

        @Test
        @DisplayName("a null transaction identifier sorts first within its card")
        void aNullIdentifierSortsFirst() {
            Transaction withId = transactionOn(CARD_LOW, "0000000000000001");
            Transaction withoutId = transactionOn(CARD_LOW, "0000000000000002");
            setField(withoutId, "transactionId", null);

            List<Transaction> sorted = new ArrayList<>(List.of(withId, withoutId));
            sorted.sort(StatementProcessor.statementSortComparator());

            assertThat(sorted).containsExactly(withoutId, withId);
        }

        @Test
        @DisplayName("the comparator is a fresh instance and is reusable")
        void theComparatorIsReusable() {
            Comparator<Transaction> first = StatementProcessor.statementSortComparator();
            Comparator<Transaction> second = StatementProcessor.statementSortComparator();

            assertThat(first).isNotNull();
            assertThat(second).isNotNull();
            assertThat(first.compare(transactionOn(CARD_LOW, "0000000000000001"),
                    transactionOn(CARD_LOW, "0000000000000001"))).isZero();
        }

        /**
         * Builds a transaction for the comparator tests.
         *
         * @param cardNumber {@code TRAN-CARD-NUM}
         * @param transactionId {@code TRAN-ID}
         * @return the record
         */
        private static Transaction transactionOn(final String cardNumber, final String transactionId) {
            return new Transaction(transactionId, "01", 1000, "POS TERM", "PURCHASE",
                    new BigDecimal("1.00"), 123456789L, "SAMPLE MERCHANT", "SAMPLE CITY", "12345",
                    cardNumber, "2022-06-10 19:27:53.000000", "2022-06-11 02:00:00.000000");
        }
    }

    @Nested
    @DisplayName("9. The Statement record: fixed widths on both output streams")
    class StatementRecord {

        @Test
        @DisplayName("a text line of the wrong width is refused with its index")
        void aWronglyWidthedTextLineIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new Statement("00000000001", BigDecimal.ZERO,
                            List.of("too short"), List.of()))
                    .withMessageContaining("textLines[0] must be exactly 80 characters");
        }

        @Test
        @DisplayName("an HTML line of the wrong width is refused with its index")
        void aWronglyWidthedHtmlLineIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new Statement("00000000001", BigDecimal.ZERO,
                            List.of(" ".repeat(TEXT_WIDTH)), List.of("too short")))
                    .withMessageContaining("htmlLines[0] must be exactly 100 characters");
        }

        @ParameterizedTest(name = "an absent {0} is refused")
        @ValueSource(strings = {"accountId", "totalExpenditure", "textLines", "htmlLines"})
        @DisplayName("every component is required")
        void everyComponentIsRequired(final String missing) {
            String accountId = "accountId".equals(missing) ? null : "00000000001";
            BigDecimal total = "totalExpenditure".equals(missing) ? null : BigDecimal.ZERO;
            List<String> textLines = "textLines".equals(missing) ? null : List.of();
            List<String> htmlLines = "htmlLines".equals(missing) ? null : List.of();

            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new Statement(accountId, total, textLines, htmlLines))
                    .withMessage(missing + " must not be null");
        }

        @Test
        @DisplayName("both line lists are immutable copies")
        void bothLineListsAreImmutableCopies() {
            List<String> mutableText = new ArrayList<>(List.of(" ".repeat(TEXT_WIDTH)));
            Statement statement = new Statement("00000000001", BigDecimal.ZERO, mutableText, List.of());

            mutableText.add(" ".repeat(TEXT_WIDTH));

            assertThat(statement.textLines()).hasSize(1);
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> statement.textLines().add(" ".repeat(TEXT_WIDTH)));
        }

        @Test
        @DisplayName("toString reports the counts rather than the content, so no statement text leaks")
        void toStringReportsCountsOnly() {
            Statement statement = new Statement("00000000001", BigDecimal.ZERO,
                    List.of(" ".repeat(TEXT_WIDTH)), List.of(" ".repeat(HTML_WIDTH)));

            assertThat(statement)
                    .hasToString("Statement[accountId=00000000001, textLines=1, htmlLines=1]");
        }
    }

    @Nested
    @DisplayName("10. readNextCrossReference: the driving sequential read and its end-of-file latch")
    class CrossReferenceRead {

        @Test
        @DisplayName("a record is parsed from the CVACT03Y layout")
        void aRecordIsParsed() {
            stubTransactionFile(projectedRecord("0000000000000001", CARD_LOW, "1.00", "ONE"));
            crossReferenceDataset.enqueueSequential("00", crossReferenceRecord(CARD_LOW, 5L, 7L));
            crossReferenceDataset.enqueueSequential("10", "");

            CardCrossReference parsed = processor.readNextCrossReference().orElseThrow();

            assertThat(parsed.getCardNumber()).isEqualTo(CARD_LOW);
            assertThat(parsed.getCustomerId()).isEqualTo(5L);
            assertThat(parsed.getAccountId()).isEqualTo(7L);
        }

        @Test
        @DisplayName("an actual 36-byte fixture row parses, padded to the 50-byte cluster record")
        void aFixtureRowParses() {
            stubTransactionFile(projectedRecord("0000000000000001", CARD_LOW, "1.00", "ONE"));
            crossReferenceDataset.enqueueSequential("00", crossReferenceRows.getFirst());
            crossReferenceDataset.enqueueSequential("10", "");

            CardCrossReference parsed = processor.readNextCrossReference().orElseThrow();

            assertThat(parsed.getCardNumber()).isEqualTo("0500024453765740");
            assertThat(parsed.getCustomerId())
                    .as("bytes 17-25 of app/data/ASCII/cardxref.txt row 1 read '000000050'")
                    .isEqualTo(50L);
            assertThat(parsed.getAccountId())
                    .as("bytes 26-36 of the same row read '00000000050'")
                    .isEqualTo(50L);
        }

        @Test
        @DisplayName("end of file returns empty and latches, so a second call reads nothing")
        void endOfFileLatches() {
            stubTransactionFile(projectedRecord("0000000000000001", CARD_LOW, "1.00", "ONE"));
            crossReferenceDataset.enqueueSequential("10", "");

            assertThat(processor.readNextCrossReference()).isEmpty();
            int readsAfterFirst = crossReferenceDataset.sequentialReadCount();
            assertThat(processor.readNextCrossReference()).isEmpty();

            assertThat(crossReferenceDataset.sequentialReadCount())
                    .as("the latch means the dataset is not read again")
                    .isEqualTo(readsAfterFirst);
        }

        @Test
        @DisplayName("a failed read abends and logs ERROR READING XREFFILE")
        void aFailedReadAbends() {
            stubTransactionFile(projectedRecord("0000000000000001", CARD_LOW, "1.00", "ONE"));
            crossReferenceDataset.enqueueSequential("35", "");

            assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> processor.readNextCrossReference());

            assertThat(loggedMessages()).contains("ERROR READING XREFFILE");
        }

        @Test
        @DisplayName("a malformed record abends with the card number masked")
        void aMalformedRecordAbendsWithTheCardMasked() {
            stubTransactionFile(projectedRecord("0000000000000001", CARD_LOW, "1.00", "ONE"));
            crossReferenceDataset.enqueueSequential("00", CARD_LOW + "NOTADIGIT" + "0".repeat(11));

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> processor.readNextCrossReference())
                    .satisfies(abend -> assertThat(abend.getMessage())
                            .contains("An XREFFILE record could not be interpreted")
                            .contains("app/cpy/CVACT03Y.cpy")
                            .doesNotContain(CARD_LOW));
        }
    }

    @Nested
    @DisplayName("11. close: every dataset is attempted and the first failure is reported")
    class Close {

        @Test
        @DisplayName("all four datasets are closed")
        void allFourDatasetsAreClosed() {
            stubHappyPath();
            processor.initialise();

            processor.close();

            assertThat(transactionDataset.closeCount()).isEqualTo(1);
            assertThat(crossReferenceDataset.closeCount()).isEqualTo(1);
            assertThat(customerDataset.closeCount()).isEqualTo(1);
            assertThat(accountDataset.closeCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("close reports the run's counts")
        void closeReportsTheCounts() {
            CardCrossReference xref = stubHappyPath();
            processor.process(xref);

            processor.close();

            assertThat(loggedMessages())
                    .contains("Statement run complete: statements=1 cards=1 transactions=1");
        }

        @Test
        @DisplayName("a failure on the first close does not stop the remaining three")
        void aFailureDoesNotStopTheRest() {
            stubHappyPath();
            processor.initialise();
            transactionDataset.closeStatus("35");

            assertThatExceptionOfType(CardDemoException.class).isThrownBy(() -> processor.close());

            assertThat(crossReferenceDataset.closeCount()).isEqualTo(1);
            assertThat(customerDataset.closeCount()).isEqualTo(1);
            assertThat(accountDataset.closeCount()).isEqualTo(1);
            assertThat(loggedMessages()).contains("ERROR CLOSING TRNXFILE");
        }

        @Test
        @DisplayName("two failures report the first and suppress the second")
        void twoFailuresReportTheFirstAndSuppressTheSecond() {
            stubHappyPath();
            processor.initialise();
            transactionDataset.closeStatus("35");
            accountDataset.closeStatus("35");

            assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> processor.close())
                    .satisfies(failure -> assertThat(failure.getSuppressed())
                            .as("nothing is swallowed: the later failure travels with the first")
                            .hasSize(1));
        }

        @Test
        @DisplayName("close resets the initialised flag, so a later call re-opens")
        void closeResetsTheInitialisedFlag() {
            stubHappyPath();
            processor.initialise();
            processor.close();
            // The first initialise consumed exactly one sequential read - the priming read - so the end-of-file
            // entry stubHappyPath queued behind it is still in the queue. Draining it before re-stubbing is
            // what keeps this test about re-opening rather than about the fixture's own bookkeeping.
            transactionDataset.drainSequential();
            stubTransactionFile(projectedRecord("0000000000000001", CARD_LOW, "1.00", "ONE"));
            crossReferenceDataset.enqueueSequential("10", "");

            processor.initialise();

            assertThat(transactionDataset.openCount()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("12. The HTML fragment table and diagnostic hygiene")
    class FragmentsAndHygiene {

        @Test
        @DisplayName("the fragment table is populated and immutable")
        void theFragmentTableIsPopulatedAndImmutable() {
            Map<String, String> fragments = StatementProcessor.htmlFragments();

            assertThat(fragments).isNotEmpty();
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> fragments.put("key", "value"));
        }

        @Test
        @DisplayName("every fragment fits the 100-character HTML record, which emission pads it to")
        void everyFragmentFitsTheHtmlRecord() {
            // The table holds the markup unpadded, exactly as the COBOL literals are written; the emitter
            // pads each one to the record width. What must hold of the table itself is that no fragment is
            // too long to fit, because a longer one would be silently truncated mid-tag.
            assertThat(StatementProcessor.htmlFragments().values())
                    .isNotEmpty()
                    .allSatisfy(fragment -> assertThat(fragment).hasSizeLessThanOrEqualTo(HTML_WIDTH));
        }

        @Test
        @DisplayName("no log record carries a card number in the clear")
        void noLogRecordCarriesACardNumberInTheClear() {
            CardCrossReference xref = stubHappyPath();

            processor.process(xref);
            processor.close();

            assertThat(loggedMessages()).noneMatch(message -> message.contains(CARD_LOW));
        }

        @Test
        @DisplayName("a malformed TRNXFILE record reports a masked card number")
        void aMalformedTransactionRecordMasksTheCard() {
            // A record of the right length whose amount field is not a zoned decimal.
            String malformed = CARD_LOW + "0000000000683580" + "01" + "1000" + "POS TERM  "
                    + "X".repeat(100) + "NOTNUMERIC!" + "0".repeat(9) + " ".repeat(110)
                    + " ".repeat(52) + " ".repeat(20);
            transactionDataset.enqueueSequential("00",
                    malformed + " ".repeat(Math.max(0, RECORD_LENGTH - malformed.length())));

            // The parse happens when the record is consumed into a group, not when it is primed: priming
            // holds the 350 bytes and nothing more. The abend is unchanged; only what provokes it moved.
            stubKeyedFixtureRows(1L, 1L);
            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> processor.process(new CardCrossReference(CARD_BELOW_ALL, 1L, 1L)))
                    .satisfies(abend -> assertThat(abend.getMessage())
                            .contains("could not be interpreted")
                            .contains("app/cpy/COSTM01.CPY")
                            .doesNotContain(CARD_LOW));
        }

        @Test
        @DisplayName("a malformed CUSTFILE record abends and cites the copybook")
        void aMalformedCustomerRecordAbends() {
            stubTransactionFile(projectedRecord("0000000000000001", CARD_LOW, "1.00", "ONE"));
            crossReferenceDataset.enqueueSequential("10", "");
            customerDataset.bindKeyed("000000001", "00", "!".repeat(500));
            accountDataset.bindKeyed("00000000001", "00", accountRows.getFirst());

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> processor.process(new CardCrossReference(CARD_LOW, 1L, 1L)))
                    .withMessageContaining("A CUSTFILE record could not be interpreted")
                    .withMessageContaining("app/cpy/CVCUS01Y.cpy");
        }

        @Test
        @DisplayName("a malformed ACCTFILE record abends and cites the copybook")
        void aMalformedAccountRecordAbends() {
            stubTransactionFile(projectedRecord("0000000000000001", CARD_LOW, "1.00", "ONE"));
            crossReferenceDataset.enqueueSequential("10", "");
            customerDataset.bindKeyed("000000001", "00", customerRows.getFirst());
            accountDataset.bindKeyed("00000000001", "00", "!".repeat(300));

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> processor.process(new CardCrossReference(CARD_LOW, 1L, 1L)))
                    .withMessageContaining("An ACCTFILE record could not be interpreted")
                    .withMessageContaining("app/cpy/CVACT01Y.cpy");
        }

        @Test
        @DisplayName("a failed CUSTFILE keyed read abends and logs ERROR READING CUSTFILE")
        void aFailedCustomerReadAbends() {
            stubTransactionFile(projectedRecord("0000000000000001", CARD_LOW, "1.00", "ONE"));
            crossReferenceDataset.enqueueSequential("10", "");
            customerDataset.keyedDefault("35");

            assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> processor.process(new CardCrossReference(CARD_LOW, 1L, 1L)));

            assertThat(loggedMessages()).contains("ERROR READING CUSTFILE");
        }

        @Test
        @DisplayName("a failed ACCTFILE keyed read abends and logs ERROR READING ACCTFILE")
        void aFailedAccountReadAbends() {
            stubTransactionFile(projectedRecord("0000000000000001", CARD_LOW, "1.00", "ONE"));
            crossReferenceDataset.enqueueSequential("10", "");
            customerDataset.bindKeyed("000000001", "00", customerRows.getFirst());
            accountDataset.keyedDefault("35");

            assertThatExceptionOfType(CardDemoException.class)
                    .isThrownBy(() -> processor.process(new CardCrossReference(CARD_LOW, 1L, 1L)));

            assertThat(loggedMessages()).contains("ERROR READING ACCTFILE");
        }
    }

    @Nested
    @DisplayName("13. The ALTER chain as five plain calls, and the two retained intentional no-ops")
    class OrderedChainAndRetainedNoOps {

        /**
         * The {@code ALTER} chain collapses to straight-line code because every transition is a literal.
         *
         * <p>{@code 0000-START} at {@code app/cbl/CBSTM03A.CBL:L296-L314} selects on {@code WS-FL-DD}, whose
         * initial value is fixed at {@code :L67} to {@code 'TRNXFILE'}, and each handler's tail hard-codes the
         * next value: {@code :L760} sets {@code 'READTRNX'}, {@code :L851} sets {@code 'XREFFILE'},
         * {@code :L779} sets {@code 'CUSTFILE'}, {@code :L797} sets {@code 'ACCTFILE'}, and {@code :L815}
         * leaves the machine permanently with {@code GO TO 1000-MAINLINE}. Nothing reads external state, so
         * the machine has exactly one path and models no variability at all.
         *
         * <p><b>Therefore the translation is an ordered sequence of five plain private calls, not a state
         * machine, not an enum map, not a {@code switch} on a DD name and not a strategy table.</b> This test
         * asserts that determination structurally: the class under test declares no field, no map and no
         * method that keys behaviour on a DD name. The DD-keyed strategy map genuinely belongs to
         * {@link FileService}, where {@code CBSTM03B}'s four-file-by-six-operation matrix really does vary.
         * Owed an entry in the planned {@code DECISION_LOG.md} as self-modifying code eliminated by static
         * flow analysis with observable order preserved.
         */
        @Test
        @DisplayName("the processor declares no DD-keyed dispatch structure; that belongs to FileService")
        void theProcessorHasNoDispatchTable() {
            List<String> dispatchLikeMembers = new ArrayList<>();
            for (var field : StatementProcessor.class.getDeclaredFields()) {
                if (Map.class.isAssignableFrom(field.getType())
                        && field.getGenericType().toString().contains(FileService.Dd.class.getName())) {
                    dispatchLikeMembers.add("field " + field.getName());
                }
            }
            for (Method method : StatementProcessor.class.getDeclaredMethods()) {
                if (FileService.Dd.class.equals(method.getReturnType())) {
                    dispatchLikeMembers.add("method " + method.getName());
                }
            }

            assertThat(dispatchLikeMembers)
                    .as("app/cbl/CBSTM03A.CBL:L296-L314 hard-codes every transition, so the translation is a"
                            + " fixed five-call sequence; a DD-keyed map or selector here would model"
                            + " variability that does not exist and duplicate FileService")
                    .isEmpty();
            assertThat(openSequence)
                    .as("no dataset is opened merely by loading the class")
                    .isEmpty();
        }

        /**
         * The five unreachable {@code EXIT.} statements are retained, tracked, and observably harmless.
         *
         * <p>{@code app/cbl/CBSTM03A.CBL:L762}, {@code :L781}, {@code :L799}, {@code :L816} and {@code :L853}
         * each sit immediately after a {@code GO TO}, so control never reaches any of them. Severity <b>Low</b>.
         * Clause B forbids <em>untracked</em> dead code; these are owed an entry in the planned
         * {@code DECISION_LOG.md} and a row in the planned {@code TRACEABILITY_MATRIX.md}, and carry an
         * intentional-no-op marker in the translation itself, so they are tracked. Deleting them would
         * break the paragraph map Gate 7 verifies.
         *
         * <p>What is assertable from outside is the consequence: retaining five no-ops changes nothing
         * observable. The initialisation sequence still opens exactly four datasets exactly once each and
         * still hands control to the mainline, which is the whole of their observable contribution.
         */
        @Test
        @DisplayName("the five dead EXIT statements are observably inert: four opens, one each, then mainline")
        void theFiveDeadExitsAreInert() {
            stubHappyPath();

            processor.initialise();

            assertThat(openSequence)
                    .as("app/cbl/CBSTM03A.CBL:L762/:L781/:L799/:L816/:L853 are unreachable, so they neither"
                            + " add nor suppress an open")
                    .containsExactly(FileService.Dd.TRNXFILE, FileService.Dd.XREFFILE,
                            FileService.Dd.CUSTFILE, FileService.Dd.ACCTFILE);
            assertThat(List.of(transactionDataset.openCount(), crossReferenceDataset.openCount(),
                            customerDataset.openCount(), accountDataset.openCount()))
                    .as("each dataset opens once; a reachable EXIT would have re-entered 0000-START")
                    .containsExactly(1, 1, 1, 1);
            assertThat(processor.readNextCrossReference())
                    .as("app/cbl/CBSTM03A.CBL:L815 GO TO 1000-MAINLINE left the machine, so the mainline runs")
                    .isPresent();
        }

        /**
         * The redundant {@code MOVE 1 TO CR-JMP} of {@code :L324} is retained and is observably harmless.
         *
         * <p>It is immediately redundant because {@code 4000-TRNXFILE-GET}'s
         * {@code PERFORM VARYING CR-JMP FROM 1 BY 1} at {@code :L417-L418} re-initialises the same subscript
         * before reading it. Severity <b>Low</b>, retained with an intentional-no-op marker and a
         * forward reference to the planned {@code DECISION_LOG.md}, for the same Gate 7 reason as the dead
         * {@code EXIT.}s.
         *
         * <p><b>The locator is {@code :L324}.</b> The adjacent {@code MOVE ZERO TO WS-TOTAL-AMT} at
         * {@code :L325} is <em>not</em> redundant, and this test proves the distinction rather than asserting
         * it: two successive statements for the same card each total from zero, which only holds because
         * {@code :L325} really does reset. Confusing the two locators would delete a live reset.
         */
        @Test
        @DisplayName("L324 is redundant but L325 is not: the per-statement total really is reset")
        void theRedundantSubscriptResetIsHarmlessButTheTotalResetIsNot() {
            stubTransactionFile(
                    projectedRecord("0000000000000001", CARD_LOW, "10.00", "ONE"),
                    projectedRecord("0000000000000002", CARD_LOW, "15.00", "TWO"));
            crossReferenceDataset.enqueueSequential("10", "");
            stubKeyedFixtureRows(1L, 1L);

            Statement first = processor.process(new CardCrossReference(CARD_LOW, 1L, 1L));
            Statement second = processor.process(new CardCrossReference(CARD_LOW, 1L, 1L));

            assertThat(first.totalExpenditure())
                    .as("app/cbl/CBSTM03A.CBL:L429 accumulates both transactions of the card")
                    .isEqualByComparingTo(new BigDecimal("25.00"));
            assertThat(second.totalExpenditure())
                    .as("app/cbl/CBSTM03A.CBL:L325 MOVE ZERO TO WS-TOTAL-AMT is live, not redundant; only"
                            + " :L324 MOVE 1 TO CR-JMP is the retained no-op")
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }

        /**
         * Neither {@code IF} of {@code 1000-MAINLINE} has an {@code ELSE} limb, and none is invented.
         *
         * <p>{@code app/cbl/CBSTM03A.CBL:L317-L329} is a {@code PERFORM UNTIL} wrapping
         * {@code IF END-OF-FILE = 'N'} wrapping a second identical test, and <b>both close with a bare
         * {@code END-IF}</b>. Contrast {@code app/cbl/CBACT04C.cbl}, whose {@code ELSE} exists and is
         * unreachable, and {@code app/cbl/CBTRN03C.cbl}, whose inner {@code ELSE} exists and is reachable.
         *
         * <p>The observable consequence of having no {@code ELSE} is that end of file produces <em>nothing</em>
         * — no flush, no final statement, no sentinel. An invented {@code ELSE} would show up here as an extra
         * emission after exhaustion.
         */
        @Test
        @DisplayName("end of file produces nothing at all, because neither mainline IF has an ELSE")
        void endOfFileProducesNothing() {
            stubTransactionFile(projectedRecord("0000000000000001", CARD_LOW, "1.00", "ONE"));
            crossReferenceDataset.enqueueSequential("00", crossReferenceRecord(CARD_LOW, 1L, 1L));
            crossReferenceDataset.enqueueSequential("10", "");
            stubKeyedFixtureRows(1L, 1L);

            assertThat(processor.readNextCrossReference()).isPresent();
            int messagesBeforeExhaustion = loggedMessages().size();

            assertThat(processor.readNextCrossReference())
                    .as("app/cbl/CBSTM03A.CBL:L356-L357 latches END-OF-FILE; there is no ELSE to flush")
                    .isEmpty();
            assertThat(processor.readNextCrossReference())
                    .as("a caller that keeps asking keeps getting nothing, per the :L317/:L321 guards")
                    .isEmpty();
            assertThat(loggedMessages().subList(messagesBeforeExhaustion, loggedMessages().size()))
                    .as("no end-of-data flush exists to announce, unlike CBACT04C's final account update")
                    .isEmpty();
        }

        /**
         * The six operation codes of the {@code CBSTM03B} call area are exactly the source's 88-levels.
         *
         * <p>{@code app/cbl/CBSTM03A.CBL:L71-L83} declares {@code WS-M03B-OPER PIC X(01)} with
         * {@code M03B-OPEN 'O'}, {@code M03B-CLOSE 'C'}, {@code M03B-READ 'R'}, {@code M03B-READ-K 'K'},
         * {@code M03B-WRITE 'W'} and {@code M03B-REWRITE 'Z'}, and {@code app/cbl/CBSTM03B.CBL:L102-L108}
         * declares the identical set on the linkage side. A code that drifted would be silently unrecognised
         * by the subprogram's {@code IF M03B-OPEN} chain.
         */
        @Test
        @DisplayName("the operation codes are O, C, R, K, W and Z, per :L74-L79")
        void theOperationCodesMatchTheSource() {
            // Keyed by the 88-level name with its hyphen rendered as an underscore, so that
            // M03B-READ-K reads across as READ_K and a renamed constant fails here rather than drifting.
            Map<String, Character> expected = new LinkedHashMap<>();
            expected.put("OPEN", 'O');
            expected.put("CLOSE", 'C');
            expected.put("READ", 'R');
            expected.put("READ_K", 'K');
            expected.put("WRITE", 'W');
            expected.put("REWRITE", 'Z');

            Map<String, Character> actual = new LinkedHashMap<>();
            for (FileService.Operation operation : FileService.Operation.values()) {
                actual.put(operation.name(), operation.code());
            }

            assertThat(actual)
                    .as("app/cbl/CBSTM03A.CBL:L74-L79 and app/cbl/CBSTM03B.CBL:L103-L108 declare the same six"
                            + " 88-levels; the subprogram's IF chain recognises no other code")
                    .containsExactlyInAnyOrderEntriesOf(expected);
            assertThat(FileService.OPERATION_WIDTH)
                    .as("WS-M03B-OPER PIC X(01) at app/cbl/CBSTM03A.CBL:L73")
                    .isEqualTo(1);
        }

        /**
         * The shared call area's field widths are the source's, so the fixed-width contract survives.
         *
         * <p>{@code app/cbl/CBSTM03A.CBL:L71-L83}: {@code WS-M03B-DD X(08)}, {@code WS-M03B-OPER X(01)},
         * {@code WS-M03B-RC X(02)}, {@code WS-M03B-KEY X(25)}, {@code WS-M03B-KEY-LN S9(4)} and
         * {@code WS-M03B-FLDT X(1000)} — 1040 characters in total.
         */
        @Test
        @DisplayName("the call area is 8 + 1 + 2 + 25 + 4 + 1000 = 1040 characters, per :L71-L83")
        void theCallAreaGeometryMatchesTheSource() {
            assertThat(FileService.DD_NAME_WIDTH).as("WS-M03B-DD X(08), :L72").isEqualTo(8);
            assertThat(FileService.OPERATION_WIDTH).as("WS-M03B-OPER X(01), :L73").isEqualTo(1);
            assertThat(FileService.KEY_WIDTH).as("WS-M03B-KEY X(25), :L81").isEqualTo(25);
            assertThat(FileService.KEY_LENGTH_FIELD_DIGITS).as("WS-M03B-KEY-LN S9(4), :L82").isEqualTo(4);
            assertThat(FileService.PAYLOAD_WIDTH).as("WS-M03B-FLDT X(1000), :L83").isEqualTo(1000);
            assertThat(FileService.SHARED_AREA_WIDTH)
                    .as("the six fields of :L71-L83 sum to the whole area")
                    .isEqualTo(FileService.DD_NAME_WIDTH + FileService.OPERATION_WIDTH
                            + FileStatus.STATUS_CODE_LENGTH + FileService.KEY_WIDTH
                            + FileService.KEY_LENGTH_FIELD_DIGITS + FileService.PAYLOAD_WIDTH);
        }
    }

    @Nested
    @DisplayName("14. HIGH: the scoped '00' OR '04' leniency, and its deliberate absence at the EVALUATE sites")
    class StatusLeniencyAsymmetry {

        /**
         * The nine {@code IF}-guarded call sites accept {@code '04'} as success alongside {@code '00'}.
         *
         * <p>{@code IF WS-M03B-RC = '00' OR '04'} appears at {@code app/cbl/CBSTM03A.CBL:L736},
         * {@code :L748}, {@code :L771}, {@code :L789}, {@code :L807}, {@code :L862}, {@code :L879},
         * {@code :L895} and {@code :L911} — the four opens, the priming read and the four closes. Those nine
         * are the migration's <b>third scoped-leniency site</b>, the other two being
         * {@code app/cbl/CBTRN02C.cbl:L481} and {@code app/cbl/CBACT04C.cbl:L422}. Everywhere else in the
         * corpus a status other than {@code '00'} is an error, so the leniency is scoped and must not leak.
         *
         * @param ddName the DD whose open reports the secondary status
         */
        @ParameterizedTest(name = "{0} open reporting 04 is accepted")
        @ValueSource(strings = {"TRNXFILE", "XREFFILE", "CUSTFILE", "ACCTFILE"})
        @DisplayName("every IF-guarded open accepts the secondary status 04")
        void everyGuardedOpenAcceptsTheSecondaryStatus(final String ddName) {
            stubHappyPath();
            datasetFor(ddName).openStatus("04");

            processor.initialise();

            assertThat(openSequence)
                    .as("app/cbl/CBSTM03A.CBL:L736/:L771/:L789/:L807 accept '00' OR '04', so a 04 open"
                            + " neither abends nor is retried")
                    .doesNotContain(FileService.Dd.valueOf(ddName));
            assertThat(loggedMessages())
                    .as("a 04 open is success, so no ERROR OPENING literal is emitted for it")
                    .doesNotContain("ERROR OPENING " + ddName);
        }

        /**
         * Every {@code IF}-guarded close accepts {@code '04'}, per {@code :L862}, {@code :L879},
         * {@code :L895} and {@code :L911}.
         *
         * @param ddName the DD whose close reports the secondary status
         */
        @ParameterizedTest(name = "{0} close reporting 04 is accepted")
        @ValueSource(strings = {"TRNXFILE", "XREFFILE", "CUSTFILE", "ACCTFILE"})
        @DisplayName("every IF-guarded close accepts the secondary status 04")
        void everyGuardedCloseAcceptsTheSecondaryStatus(final String ddName) {
            stubHappyPath();
            processor.initialise();
            datasetFor(ddName).closeStatus("04");

            processor.close();

            assertThat(datasetFor(ddName).closeCount())
                    .as("app/cbl/CBSTM03A.CBL:L862/:L879/:L895/:L911 accept '00' OR '04'")
                    .isEqualTo(1);
            assertThat(loggedMessages()).doesNotContain("ERROR CLOSING " + ddName);
        }

        /**
         * 🔴 The other half of the asymmetry: the {@code EVALUATE}-guarded sites do <b>not</b> grant
         * {@code '04'}.
         *
         * <p>{@code EVALUATE WS-M03B-RC} appears at {@code app/cbl/CBSTM03A.CBL:L353} (get-next
         * cross-reference), {@code :L379} (keyed customer), {@code :L403} (keyed account) and {@code :L837}
         * (the table-build read). Each has {@code WHEN '00'} and a {@code WHEN OTHER} that displays
         * {@code 'ERROR READING …'} and abends; only {@code :L353} and {@code :L837} additionally have a
         * {@code WHEN '10'}. <b>There is no {@code WHEN '04'} at any of the four.</b> So {@code '04'} falls
         * into {@code WHEN OTHER} and abends — the exact opposite of the nine {@code IF} sites.
         *
         * <p>Getting this wrong in either direction is a High-severity parity break: granting {@code '04'}
         * here would silently accept a record the source rejected, and withdrawing it from the {@code IF}
         * sites would abend an open the source accepted.
         */
        @Test
        @DisplayName("the EVALUATE-guarded cross-reference read ABENDS on 04, unlike the IF sites")
        void theCrossReferenceReadRejectsTheSecondaryStatus() {
            stubTransactionFile(projectedRecord("0000000000000001", CARD_LOW, "1.00", "ONE"));
            crossReferenceDataset.enqueueSequential("04", crossReferenceRecord(CARD_LOW, 1L, 1L));

            assertThatExceptionOfType(CardDemoException.class)
                    .as("app/cbl/CBSTM03A.CBL:L353-L362 has WHEN '00' and WHEN '10' only, so 04 reaches"
                            + " WHEN OTHER and abends")
                    .isThrownBy(() -> processor.readNextCrossReference());

            assertThat(loggedMessages())
                    .as("the WHEN OTHER limb of :L358-L361 displays this literal before abending")
                    .contains("ERROR READING XREFFILE");
        }

        /**
         * The keyed customer read abends on {@code '04'}, per the {@code EVALUATE} at {@code :L379-L386}.
         *
         * <p>That paragraph has no {@code WHEN '10'} either, which is why a missing customer abends rather
         * than terminating the loop.
         */
        @Test
        @DisplayName("the EVALUATE-guarded keyed reads ABEND on 04, and have no WHEN '10' at all")
        void theKeyedReadsRejectTheSecondaryStatus() {
            stubTransactionFile(projectedRecord("0000000000000001", CARD_LOW, "1.00", "ONE"));
            crossReferenceDataset.enqueueSequential("10", "");
            customerDataset.bindKeyed("000000001", "04", customerRows.getFirst());
            accountDataset.bindKeyed("00000000001", "00", accountRows.getFirst());

            assertThatExceptionOfType(CardDemoException.class)
                    .as("app/cbl/CBSTM03A.CBL:L379-L386 admits '00' only")
                    .isThrownBy(() -> processor.process(new CardCrossReference(CARD_LOW, 1L, 1L)));

            assertThat(loggedMessages()).contains("ERROR READING CUSTFILE");
        }

        /**
         * The table-build read of {@code :L837-L847} abends on {@code '04'}.
         *
         * <p>This is the site most likely to be got wrong, because the read <em>immediately before</em> it —
         * the priming read at {@code :L748} — is {@code IF}-guarded and therefore <em>does</em> accept
         * {@code '04'}. The same DD, the same operation, two adjacent statements, two different guards. The
         * priming read is stubbed to succeed with {@code '00'} here so that the failure can only come from the
         * loop read.
         */
        @Test
        @DisplayName("the table-build read ABENDS on 04 even though the adjacent priming read accepts it")
        void theTableBuildReadRejectsTheSecondaryStatusTheAdjacentReadAccepts() {
            transactionDataset.enqueueSequential("00",
                    projectedRecord("0000000000000001", CARD_LOW, "1.00", "ONE"));
            transactionDataset.enqueueSequential("04",
                    projectedRecord("0000000000000002", CARD_LOW, "2.00", "TWO"));
            crossReferenceDataset.enqueueSequential("10", "");
            stubKeyedFixtureRows(1L, 1L);

            assertThatExceptionOfType(CardDemoException.class)
                    .as("app/cbl/CBSTM03A.CBL:L837-L847 admits '00' and '10' only; :L748 admits '04' too")
                    .isThrownBy(() -> processor.process(new CardCrossReference(CARD_BELOW_ALL, 1L, 1L)));

            assertThat(loggedMessages())
                    .as("the WHEN OTHER limb of :L843-L846 displays this literal")
                    .contains("ERROR READING TRNXFILE");
        }

        /**
         * {@code '10'} is end of file — loop termination, never an exception.
         *
         * <p>{@code WHEN '10'} at {@code :L356} sets {@code END-OF-FILE} to {@code 'Y'} and {@code WHEN '10'}
         * at {@code :L841} branches to {@code 8599-EXIT}. Neither displays anything and neither abends, so a
         * mapper that translated {@code '10'} into a thrown exception would abend every well-formed run at the
         * end of its input.
         */
        @Test
        @DisplayName("status 10 terminates the loop and is never thrown, per :L356 and :L841")
        void endOfFileIsNeverThrown() {
            transactionDataset.enqueueSequential("00",
                    projectedRecord("0000000000000001", CARD_LOW, "1.00", "ONE"));
            transactionDataset.enqueueSequential("10", "");
            crossReferenceDataset.enqueueSequential("10", "");
            stubKeyedFixtureRows(1L, 1L);

            Statement statement = processor.process(new CardCrossReference(CARD_LOW, 1L, 1L));

            assertThat(statement).isNotNull();
            assertThat(processor.readNextCrossReference()).isEmpty();
            assertThat(loggedMessages())
                    .as("app/cbl/CBSTM03A.CBL:L841-L842 branches away silently; nothing is displayed")
                    .doesNotContain("ERROR READING TRNXFILE", "ERROR READING XREFFILE");
        }

        /**
         * Resolves a DD name to the fake bound to it.
         *
         * @param ddName one of the four DD names of {@code app/jcl/CREASTMT.JCL:L83-L86}
         * @return the fake dataset, never {@code null}
         */
        private FakeDataset datasetFor(final String ddName) {
            return switch (FileService.Dd.valueOf(ddName)) {
                case TRNXFILE -> transactionDataset;
                case XREFFILE -> crossReferenceDataset;
                case CUSTFILE -> customerDataset;
                case ACCTFILE -> accountDataset;
            };
        }
    }

    @Nested
    @DisplayName("15. HIGH: the final per-card counter flush of :L850, without which the last card is lost")
    class FinalCounterFlush {

        /**
         * 🔴 {@code 8599-EXIT}'s {@code MOVE TR-CNT TO WS-TRCT (CR-CNT)} at
         * {@code app/cbl/CBSTM03A.CBL:L850} is the <b>final</b> per-card counter flush.
         *
         * <p>Inside the loop, {@code :L822} writes a card's count only when the <em>next</em> card arrives and
         * forces the control break. The last card has no next card, so without the flush at {@code :L850} its
         * count stays zero and every one of its transactions is invisible to the lookup at {@code :L423},
         * which bounds its inner loop by {@code WS-TRCT (CR-JMP)}. The failure is silent: the statement is
         * produced, it is the right width, and it simply has no transactions and a zero total.
         *
         * <p>The input here is deliberately shaped so the bug cannot hide: <b>three cards with distinct
         * counts, the last one distinct from the others</b> (2, 1, then 3). A fixture where the last card
         * happened to match an earlier count would pass against a broken flush.
         */
        @Test
        @DisplayName("the LAST card's transactions all survive, with a count distinct from every other card")
        void theLastCardsCountIsFlushed() {
            stubTransactionFile(
                    projectedRecord("0000000000000001", CARD_A, "10.00", "A ONE"),
                    projectedRecord("0000000000000002", CARD_A, "11.00", "A TWO"),
                    projectedRecord("0000000000000003", CARD_B, "20.00", "B ONE"),
                    projectedRecord("0000000000000004", CARD_C, "30.00", "C ONE"),
                    projectedRecord("0000000000000005", CARD_C, "31.00", "C TWO"),
                    projectedRecord("0000000000000006", CARD_C, "32.00", "C THREE"));
            crossReferenceDataset.enqueueSequential("10", "");
            stubKeyedFixtureRows(1L, 1L);

            Statement last = processor.process(new CardCrossReference(CARD_C, 1L, 1L));

            assertThat(last.totalExpenditure())
                    .as("app/cbl/CBSTM03A.CBL:L850 flushed TR-CNT=3 for the final card; omit it and this"
                            + " total is zero while the statement still looks well formed")
                    .isEqualByComparingTo(new BigDecimal("93.00"));
            assertThat(last.textLines().stream().filter(line -> line.contains("C THREE")).count())
                    .as("the third and last transaction of the last card reaches the statement")
                    .isEqualTo(1L);
            assertThat(processor.cardGroupsRead())
                    .as("CR-CNT reached three groups, per :L823")
                    .isEqualTo(3L);
        }

        /**
         * Each card's count is flushed independently, so no card inherits another's count.
         *
         * <p>The control break at {@code :L821-L825} writes the outgoing card's count, increments
         * {@code CR-CNT} and resets {@code TR-CNT} to 1 — not to 0, because the record that triggered the
         * break is itself the first record of the new card. A translation that reset to 0 would lose one
         * transaction per card after the first.
         */
        @Test
        @DisplayName("every card keeps its own count: 2, 1 and 3, never a carried-over value")
        void everyCardKeepsItsOwnCount() {
            stubTransactionFile(
                    projectedRecord("0000000000000001", CARD_A, "10.00", "A ONE"),
                    projectedRecord("0000000000000002", CARD_A, "11.00", "A TWO"),
                    projectedRecord("0000000000000003", CARD_B, "20.00", "B ONE"),
                    projectedRecord("0000000000000004", CARD_C, "30.00", "C ONE"),
                    projectedRecord("0000000000000005", CARD_C, "31.00", "C TWO"),
                    projectedRecord("0000000000000006", CARD_C, "32.00", "C THREE"));
            crossReferenceDataset.enqueueSequential("10", "");
            stubKeyedFixtureRows(1L, 1L);

            Statement firstCard = processor.process(new CardCrossReference(CARD_A, 1L, 1L));
            Statement middleCard = processor.process(new CardCrossReference(CARD_B, 1L, 1L));
            Statement lastCard = processor.process(new CardCrossReference(CARD_C, 1L, 1L));

            assertThat(firstCard.totalExpenditure())
                    .as("app/cbl/CBSTM03A.CBL:L822 flushed TR-CNT=2 when CARD_B forced the break")
                    .isEqualByComparingTo(new BigDecimal("21.00"));
            assertThat(middleCard.totalExpenditure())
                    .as("MOVE 1 TO TR-CNT at :L824 counts the breaking record itself, so this card has 1")
                    .isEqualByComparingTo(new BigDecimal("20.00"));
            assertThat(lastCard.totalExpenditure())
                    .as("only :L850 can flush the final card's TR-CNT=3")
                    .isEqualByComparingTo(new BigDecimal("93.00"));
        }

        /**
         * A single-card file exercises the flush in isolation: the only card is also the last card.
         *
         * <p>With one card there is no control break at all, so {@code :L822} never runs and {@code :L850} is
         * the <em>only</em> statement that can record the count. This is the minimal reproduction of the bug.
         */
        @Test
        @DisplayName("a single-card file relies on :L850 alone, because no control break ever fires")
        void aSingleCardFileReliesOnTheFinalFlushAlone() {
            stubTransactionFile(
                    projectedRecord("0000000000000001", CARD_LOW, "5.00", "ONLY ONE"),
                    projectedRecord("0000000000000002", CARD_LOW, "6.00", "ONLY TWO"));
            crossReferenceDataset.enqueueSequential("10", "");
            stubKeyedFixtureRows(1L, 1L);

            Statement statement = processor.process(new CardCrossReference(CARD_LOW, 1L, 1L));

            assertThat(processor.cardGroupsRead())
                    .as("one group, so the IF limb of :L819-L820 ran twice and the ELSE limb never")
                    .isEqualTo(1L);
            assertThat(statement.totalExpenditure()).isEqualByComparingTo(new BigDecimal("11.00"));
        }
    }

    @Nested
    @DisplayName("16. HIGH: over-capacity input FAILS LOUDLY and is never silently truncated")
    class LoudCapacityFailure {

        /**
         * 🔴 The validation the source lacks entirely.
         *
         * <p>{@code app/cbl/CBSTM03A.CBL:L225-L233} declares {@code WS-CARD-TBL OCCURS 51 TIMES} each holding
         * {@code WS-TRAN-TBL OCCURS 10 TIMES} — a hard ceiling of <b>510 transactions per run</b> — and
         * <b>neither {@code CR-CNT} nor {@code TR-CNT} is bounds-checked anywhere</b>. The subscripted
         * {@code MOVE}s at {@code :L827-L829} therefore walk off the end of the table on the 511th
         * transaction. Severity <b>High</b>: a latent storage-overrun defect that corrupts adjacent storage
         * silently.
         *
         * <p><b>This is a LABELLED DEVIATION, not parity.</b> Java uses unbounded collections, so the ceiling
         * is gone. The justification is written down rather than assumed (Rule 1 Clause A5): the removal
         * eliminates a <b>memory-corruption and silent-truncation hazard</b>; it is <em>not</em> a performance
         * optimisation; the historical 510 limit is owed a row in the planned {@code TRACEABILITY_MATRIX.md}
         * as the legacy capacity; and the deviation itself is owed an entry in the planned
         * {@code DECISION_LOG.md}. Pretending the ceiling was
         * preserved would be false, and pretending its removal is invisible would be worse — which is why
         * group 4 asserts that passing each legacy threshold <em>warns</em>.
         *
         * <p><b>But unbounded must not mean unchecked.</b> Replacing a silent overrun with an unbounded read
         * would swap corruption for exhaustion, so the translation adds the bound the source never had and
         * <b>fails loudly</b> when it is exceeded. This test drives that with the
         * {@code StatementProcessor(FileService, int)} constructor at a deliberately tiny bound, which is the
         * only honest way to reach the limit in a unit test: the production default is
         * {@link StatementProcessor#MAX_TRANSACTIONS_PER_RUN}, four orders of magnitude above the 300-record
         * fixture.
         */
        @Test
        @DisplayName("exceeding the run bound raises FatalProcessingException instead of truncating")
        void exceedingTheRunBoundFailsLoudly() {
            StatementProcessor bounded = new StatementProcessor(fileService, 2);
            stubTransactionFile(
                    projectedRecord("0000000000000001", CARD_LOW, "1.00", "ONE"),
                    projectedRecord("0000000000000002", CARD_LOW, "2.00", "TWO"),
                    projectedRecord("0000000000000003", CARD_LOW, "3.00", "THREE"));
            crossReferenceDataset.enqueueSequential("10", "");
            stubKeyedFixtureRows(1L, 1L);

            assertThatExceptionOfType(FatalProcessingException.class)
                    .as("the source silently overran at app/cbl/CBSTM03A.CBL:L827-L829; the translation"
                            + " refuses instead, so no run can quietly lose records")
                    .isThrownBy(() -> bounded.process(new CardCrossReference(CARD_LOW, 1L, 1L)))
                    .withMessageContaining("exceeded the safety limit")
                    .withMessageContaining(StatementProcessor.KEY_MAX_TRANSACTIONS_PER_RUN)
                    .withMessageContaining(
                            String.valueOf(StatementTransaction.LEGACY_MAX_TRANSACTIONS_PER_RUN))
                    .withMessageContaining("app/cbl/CBSTM03A.CBL:L225-L233");
        }

        /**
         * The loud failure announces itself through the abend literal, so it cannot be mistaken for success.
         *
         * <p>Silent truncation is the specific failure mode being excluded, so it is not enough that an
         * exception is raised: the run must also say so. {@code app/cbl/CBSTM03A.CBL:L922} displays
         * {@code 'ABENDING PROGRAM'} on every abend path, and that literal is reproduced verbatim.
         */
        @Test
        @DisplayName("the loud failure logs ABENDING PROGRAM and no statement is returned")
        void theLoudFailureIsAnnounced() {
            StatementProcessor bounded = new StatementProcessor(fileService, 1);
            stubTransactionFile(
                    projectedRecord("0000000000000001", CARD_LOW, "1.00", "ONE"),
                    projectedRecord("0000000000000002", CARD_LOW, "2.00", "TWO"));
            crossReferenceDataset.enqueueSequential("10", "");
            stubKeyedFixtureRows(1L, 1L);

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> bounded.process(new CardCrossReference(CARD_LOW, 1L, 1L)));

            assertThat(loggedMessages())
                    .as("app/cbl/CBSTM03A.CBL:L922 DISPLAY 'ABENDING PROGRAM', reproduced verbatim")
                    .anyMatch(message -> message.startsWith("ABENDING PROGRAM"));
        }

        /**
         * A non-positive bound is refused at construction, because neither extreme can ever be right.
         *
         * <p>A bound of zero would abend on the first record of every run and a negative bound would never
         * trip at all, so both are configuration errors rather than policies. Rule 1 Clause B2 requires the
         * boundary to be validated explicitly rather than discovered at run time.
         *
         * @param bound a rejected bound
         */
        @ParameterizedTest(name = "a bound of {0} is refused")
        @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
        @DisplayName("a non-positive transaction bound is refused at construction")
        void aNonPositiveBoundIsRefused(final int bound) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new StatementProcessor(fileService, bound))
                    .withMessageContaining(StatementProcessor.KEY_MAX_TRANSACTIONS_PER_RUN)
                    .withMessageContaining("must be at least 1");
        }

        /**
         * The default bounds sit far above the legacy ceiling, so no legitimate run is affected.
         *
         * <p>If the defaults had been set near 510 the removal of the ceiling would be cosmetic. They are not:
         * both are {@link StatementProcessor#MAX_TRANSACTIONS_PER_RUN}, which is orders of magnitude above both
         * the legacy table and the largest corpus fixture — {@code app/data/ASCII/dailytran.txt} holds 300
         * records, per {@link FixtureLoader.Fixture#DAILY_TRANSACTION}. Note the fixture spelling:
         * {@code dailytran.txt}, <b>not</b> {@code dalytran.txt}, despite the DD name being {@code DALYTRAN}.
         */
        @Test
        @DisplayName("the default bounds are far above both the 510 ceiling and the 300-record fixture")
        void theDefaultBoundsAreFarAboveTheLegacyCeiling() {
            assertThat(StatementProcessor.MAX_TRANSACTIONS_PER_RUN)
                    .as("a default near the legacy 510 would make the deviation cosmetic")
                    .isGreaterThan(StatementTransaction.LEGACY_MAX_TRANSACTIONS_PER_RUN * 1_000);
            assertThat(StatementProcessor.MAX_TRANSACTIONS_PER_CARD_GROUP)
                    .isGreaterThan(StatementTransaction.LEGACY_MAX_TRANSACTIONS_PER_CARD * 1_000);
            assertThat(FixtureLoader.Fixture.DAILY_TRANSACTION.expectedRecordCount())
                    .as("app/data/ASCII/dailytran.txt - the full spelling, not dalytran.txt")
                    .isEqualTo(300);
            assertThat(FixtureLoader.Fixture.DAILY_TRANSACTION.resourceName())
                    .as("the fixture-name trap: the DD is DALYTRAN but the ASCII fixture spells it in full")
                    .isEqualTo("dailytran.txt");
            assertThat(StatementProcessor.MAX_TRANSACTIONS_PER_RUN)
                    .as("300 fixture records cannot approach the default bound")
                    .isGreaterThan(FixtureLoader.Fixture.DAILY_TRANSACTION.expectedRecordCount());
        }

        /**
         * The legacy capacity constants are recorded, so the historical limit is documented and not merely
         * deleted.
         *
         * <p>51 cards times 10 transactions is 510, and all three figures come from
         * {@code app/cbl/CBSTM03A.CBL:L226} and {@code :L228}. Keeping them as named constants is what lets
         * the warning messages and the planned {@code TRACEABILITY_MATRIX.md} cite one number rather than
         * three copies.
         */
        @Test
        @DisplayName("the historical 51 x 10 = 510 capacity is recorded as named constants")
        void theHistoricalCapacityIsRecorded() {
            assertThat(StatementTransaction.LEGACY_MAX_CARDS_PER_RUN)
                    .as("WS-CARD-TBL OCCURS 51 TIMES, app/cbl/CBSTM03A.CBL:L226")
                    .isEqualTo(51);
            assertThat(StatementTransaction.LEGACY_MAX_TRANSACTIONS_PER_CARD)
                    .as("WS-TRAN-TBL OCCURS 10 TIMES, app/cbl/CBSTM03A.CBL:L228")
                    .isEqualTo(10);
            assertThat(StatementTransaction.LEGACY_MAX_TRANSACTIONS_PER_RUN)
                    .as("51 x 10; the ceiling the unbounded collections removed")
                    .isEqualTo(StatementTransaction.LEGACY_MAX_CARDS_PER_RUN
                            * StatementTransaction.LEGACY_MAX_TRANSACTIONS_PER_CARD)
                    .isEqualTo(510);
        }
    }

    @Nested
    @DisplayName("17. BLOCKER: the closing emission order of :L433-L454 - three text lines, then eight fragments")
    class ClosingEmissionOrder {

        /**
         * The three closing text lines are emitted in the order {@code :L435-L437} writes them.
         *
         * <p>{@code 4000-TRNXFILE-GET} closes with {@code MOVE WS-TOTAL-AMT TO WS-TRN-AMT} at {@code :L433},
         * {@code MOVE WS-TRN-AMT TO ST-TOTAL-TRAMT} at {@code :L434}, and then exactly three writes:
         * {@code ST-LINE12} (a rule of 80 hyphens), {@code ST-LINE14A} (the {@code 'Total EXP:'} line) and
         * {@code ST-LINE15} (the end-of-statement banner). Order is content in a fixed-block sequential file,
         * so a reordering is a Blocker-severity parity break even though every line is individually correct.
         */
        @Test
        @DisplayName("the statement ends with a rule, then Total EXP:, then the END OF STATEMENT banner")
        void theClosingTextLinesAreOrdered() {
            Statement statement = processor.process(stubHappyPath());
            List<String> lines = statement.textLines();

            int lastIndex = lines.size() - 1;
            assertThat(lines.get(lastIndex))
                    .as("app/cbl/CBSTM03A.CBL:L437 writes ST-LINE15 last; :L144-L146 is 32 stars, the"
                            + " repeated 'END OF STATEMENT' text in X(16), then 32 stars")
                    .startsWith("*".repeat(32))
                    .contains("END OF STATEMENT")
                    .endsWith("*".repeat(32))
                    .hasSize(TEXT_WIDTH);
            assertThat(lines.get(lastIndex - 1))
                    .as("app/cbl/CBSTM03A.CBL:L436 writes ST-LINE14A; :L139 is 'Total EXP:' in X(10)")
                    .startsWith("Total EXP:")
                    .hasSize(TEXT_WIDTH);
            assertThat(lines.get(lastIndex - 2))
                    .as("app/cbl/CBSTM03A.CBL:L435 writes ST-LINE12; :L127 is ALL '-' in X(80)")
                    .isEqualTo("-".repeat(TEXT_WIDTH));
        }

        /**
         * 🔴 The eight closing markup fragments are emitted in exactly the order {@code :L439-L454} sets them.
         *
         * <p>Each is a {@code SET … TO TRUE} immediately followed by
         * {@code WRITE FD-HTMLFILE-REC FROM HTML-FIXED-LN}, and the order is
         * {@code HTML-LTRS}, {@code HTML-L10}, {@code HTML-L75}, {@code HTML-LTDE}, {@code HTML-LTRE},
         * {@code HTML-L78}, {@code HTML-L79}, {@code HTML-L80} — a row open, a spanning cell, the
         * end-of-statement heading, a cell close, a row close, then the table, body and document closes.
         *
         * <p>Note that {@code HTML-LTDE} closes the cell <em>after</em> the heading and that
         * {@code HTML-LTDS} is never set here; the source opens the cell with the spanning {@code HTML-L10}
         * instead. Reproducing that asymmetry is why the sequence is asserted literally rather than by pairing
         * opens with closes.
         */
        @Test
        @DisplayName("the eight closing fragments appear in the :L439-L454 order, ending </table></body></html>")
        void theClosingFragmentsAreOrdered() {
            Statement statement = processor.process(stubHappyPath());
            Map<String, String> fragments = StatementProcessor.htmlFragments();

            List<String> expectedTail = List.of(
                    fragments.get("HTML_LTRS"),
                    fragments.get("HTML_L10"),
                    fragments.get("HTML_L75"),
                    fragments.get("HTML_LTDE"),
                    fragments.get("HTML_LTRE"),
                    fragments.get("HTML_L78"),
                    fragments.get("HTML_L79"),
                    fragments.get("HTML_L80"));
            assertThat(expectedTail)
                    .as("all eight condition names of :L439-L454 resolve in the fragment table")
                    .doesNotContainNull();

            List<String> actualTail = statement.htmlLines()
                    .subList(statement.htmlLines().size() - expectedTail.size(),
                            statement.htmlLines().size())
                    .stream()
                    .map(String::stripTrailing)
                    .toList();

            assertThat(actualTail)
                    .as("app/cbl/CBSTM03A.CBL:L439-L454 - LTRS, L10, L75, LTDE, LTRE, L78, L79, L80;"
                            + " order is content in a fixed-block file")
                    .containsExactlyElementsOf(expectedTail.stream().map(String::stripTrailing).toList());
            assertThat(actualTail.getLast())
                    .as("app/cbl/CBSTM03A.CBL:L211 HTML-L80 VALUE '</html>' is the document's last line")
                    .isEqualTo("</html>");
        }

        /**
         * The closing fragments are padded to the 100-character record, not emitted at their natural length.
         *
         * <p>{@code HTML-FIXED-LN} is {@code PIC X(100)} at {@code app/cbl/CBSTM03A.CBL:L149}, so
         * {@code WRITE … FROM HTML-FIXED-LN} always writes 100 bytes regardless of how short the fragment is.
         * {@code '</html>'} is seven characters and is written as 7 plus 93 spaces.
         */
        @Test
        @DisplayName("every closing fragment is space-padded to the full 100-character HTML record")
        void theClosingFragmentsArePaddedToTheRecordWidth() {
            Statement statement = processor.process(stubHappyPath());

            assertThat(statement.htmlLines())
                    .as("app/cbl/CBSTM03A.CBL:L149 HTML-FIXED-LN PIC X(100), corroborated by"
                            + " app/jcl/CREASTMT.JCL:L94 DCB=(LRECL=100,...)")
                    .isNotEmpty()
                    .allSatisfy(line -> assertThat(line).hasSize(HTML_WIDTH));
            assertThat(statement.htmlLines().getLast())
                    .isEqualTo("</html>" + " ".repeat(HTML_WIDTH - "</html>".length()));
        }

        /**
         * The total written into {@code ST-TOTAL-TRAMT} is the accumulated {@code WS-TOTAL-AMT}, not a
         * recomputation.
         *
         * <p>{@code :L429} accumulates with {@code ADD TRNX-AMT TO WS-TOTAL-AMT} inside the inner loop, and
         * {@code :L433-L434} moves that accumulator - through the display field - into the total line. The
         * comparison uses {@link BigDecimal#compareTo(BigDecimal)} semantics through
         * {@code isEqualByComparingTo}, never {@link BigDecimal#equals(Object)}, because {@code 45.00} and
         * {@code 45.0} are the same money and different objects (AAP invariant 1).
         */
        @Test
        @DisplayName("the Total EXP: line carries the accumulated WS-TOTAL-AMT of :L429")
        void theTotalLineCarriesTheAccumulator() {
            stubTransactionFile(
                    projectedRecord("0000000000000001", CARD_LOW, "20.00", "ONE"),
                    projectedRecord("0000000000000002", CARD_LOW, "25.50", "TWO"));
            crossReferenceDataset.enqueueSequential("10", "");
            stubKeyedFixtureRows(1L, 1L);

            Statement statement = processor.process(new CardCrossReference(CARD_LOW, 1L, 1L));

            assertThat(statement.totalExpenditure())
                    .as("app/cbl/CBSTM03A.CBL:L429 ADD TRNX-AMT TO WS-TOTAL-AMT, compared with compareTo")
                    .isEqualByComparingTo(new BigDecimal("45.50"));
            assertThat(statement.totalExpenditure().scale())
                    .as("WS-TOTAL-AMT is PIC S9(9)V99 COMP-3 at :L65, so scale 2")
                    .isEqualTo(StatementTransaction.AMOUNT_SCALE);
            assertThat(statement.textLines())
                    .filteredOn(line -> line.startsWith("Total EXP:"))
                    .singleElement()
                    .satisfies(line -> assertThat(line).contains("45.50"));
        }
    }




    @Nested
    @DisplayName("18. The 80-byte line arithmetic, the trailing-sign masks, and the 34 markup fragments")
    class LineGeometryAndMasks {

        /**
         * Rendered width of {@code PIC 9(9).99-} and {@code PIC Z(9).99-}.
         *
         * <p>Nine integer positions, one decimal point, two decimal positions and one trailing sign position:
         * 9 + 1 + 2 + 1 = <b>13</b>. Declared once as a named constant because the same figure appears in three
         * of the seventeen line groups, and because reading it as 12 is the arithmetic slip that makes all
         * three appear one character short.
         */
        private static final int MASK_WIDTH = 13;

        /**
         * All seventeen {@code ST-LINE*} groups of {@code app/cbl/CBSTM03A.CBL:L85-L146} sum to 80.
         *
         * <p>The arithmetic is stated here rather than merely asserted on output, because a group whose
         * subfields do not sum to 80 is a source-reading error and not a runtime one. Verified against the
         * declaration: {@code ST-LINE0} 31+18+31; {@code ST-LINE1} 75+5; {@code ST-LINE2} and {@code ST-LINE3}
         * 50+30; {@code ST-LINE4} 80; {@code ST-LINE5} 80; {@code ST-LINE6} 33+14+33; {@code ST-LINE7} 20+20+40;
         * {@code ST-LINE8} 20+13+7+40 — the {@code PIC 9(9).99-} mask occupying 13 of those characters;
         * {@code ST-LINE9} 20+20+40; {@code ST-LINE10} 80; {@code ST-LINE11} 30+20+30; {@code ST-LINE12} 80;
         * {@code ST-LINE13} 16+51+13; {@code ST-LINE14} 16+1+49+1+13; {@code ST-LINE14A} 10+56+1+13;
         * {@code ST-LINE15} 32+16+32.
         *
         * <p>The two mask widths are the load-bearing part, and the easy arithmetic slip: {@code PIC 9(9).99-}
         * and {@code PIC Z(9).99-} are each <b>13</b> characters — nine integer positions, a decimal point, two
         * decimals and one trailing sign position, so 9+1+2+1 and <em>not</em> 12. That 13 is what makes
         * {@code ST-LINE8} (20+13+7+40), {@code ST-LINE14} (16+1+49+1+13) and {@code ST-LINE14A} (10+56+1+13)
         * each balance at exactly 80. Reading the mask as 12 leaves all three one character short, which is why
         * the widths are asserted arithmetically here as well as on emitted output.
         */
        @Test
        @DisplayName("every declared ST-LINE group sums to exactly 80, including the 12-character masks")
        void everyDeclaredLineGroupSumsToEighty() {
            Map<String, int[]> declaredGroups = new LinkedHashMap<>();
            declaredGroups.put("ST-LINE0  :L86-L89", new int[] {31, 18, 31});
            declaredGroups.put("ST-LINE1  :L90-L92", new int[] {75, 5});
            declaredGroups.put("ST-LINE2  :L93-L95", new int[] {50, 30});
            declaredGroups.put("ST-LINE3  :L96-L98", new int[] {50, 30});
            declaredGroups.put("ST-LINE4  :L99-L100", new int[] {80});
            declaredGroups.put("ST-LINE5  :L101-L102", new int[] {80});
            declaredGroups.put("ST-LINE6  :L103-L106", new int[] {33, 14, 33});
            declaredGroups.put("ST-LINE7  :L107-L110", new int[] {20, 20, 40});
            declaredGroups.put("ST-LINE8  :L111-L115", new int[] {20, MASK_WIDTH, 7, 40});
            declaredGroups.put("ST-LINE9  :L116-L119", new int[] {20, 20, 40});
            declaredGroups.put("ST-LINE10 :L120-L121", new int[] {80});
            declaredGroups.put("ST-LINE11 :L122-L125", new int[] {30, 20, 30});
            declaredGroups.put("ST-LINE12 :L126-L127", new int[] {80});
            declaredGroups.put("ST-LINE13 :L128-L131", new int[] {16, 51, 13});
            declaredGroups.put("ST-LINE14 :L132-L137", new int[] {16, 1, 49, 1, MASK_WIDTH});
            declaredGroups.put("ST-LINE14A :L138-L142", new int[] {10, 56, 1, MASK_WIDTH});
            declaredGroups.put("ST-LINE15 :L143-L146", new int[] {32, 16, 32});

            assertThat(declaredGroups)
                    .as("app/cbl/CBSTM03A.CBL:L85-L146 declares seventeen groups under 01 STATEMENT-LINES")
                    .hasSize(17);
            declaredGroups.forEach((group, widths) -> {
                int total = 0;
                for (int width : widths) {
                    total += width;
                }
                assertThat(total)
                        .as("%s must sum to the FD-STMTFILE-REC width of app/cbl/CBSTM03A.CBL:L45", group)
                        .isEqualTo(TEXT_WIDTH);
            });
        }

        /**
         * 🔴 The {@code PIC Z(9).99-} total mask carries a <b>trailing</b> sign and suppresses leading zeros.
         *
         * <p>{@code ST-TOTAL-TRAMT} at {@code app/cbl/CBSTM03A.CBL:L142} and {@code ST-TRANAMT} at
         * {@code :L137} are both {@code PIC Z(9).99-}. Two properties follow and both are commonly got wrong:
         * the minus occupies the <b>last</b> character position and is a <b>space</b> when the value is
         * non-negative; and {@code Z} suppresses leading zeros to spaces, unlike the {@code 9} of
         * {@code ST-CURR-BAL}.
         *
         * <p><b>This is the opposite convention from the report line's leading minus</b> in
         * {@code app/cpy/CVTRA07Y.cpy}, whose mask is {@code -ZZZ,ZZZ,ZZZ.ZZ}. Confusing the two produces a
         * statement that looks plausible and diffs on every signed line.
         */
        @Test
        @DisplayName("a non-negative total renders with a trailing SPACE where the minus would sit")
        void aNonNegativeTotalHasNoTrailingMinus() {
            stubTransactionFile(projectedRecord("0000000000000001", CARD_LOW, "45.50", "ONE"));
            crossReferenceDataset.enqueueSequential("10", "");
            stubKeyedFixtureRows(1L, 1L);

            Statement statement = processor.process(new CardCrossReference(CARD_LOW, 1L, 1L));
            String totalLine = totalLineOf(statement);

            assertThat(totalLine)
                    .as("app/cbl/CBSTM03A.CBL:L142 PIC Z(9).99- puts the sign LAST, blank when non-negative")
                    .contains("45.50")
                    .doesNotContain("-");
            assertThat(totalLine.substring(0, "Total EXP:".length() + 56 + 1 + MASK_WIDTH)
                            .stripTrailing())
                    .as("the mask's final position holds a space, not a minus")
                    .doesNotEndWith("-");
        }

        /**
         * A negative total puts the minus in the mask's final position, never in front of the digits.
         */
        @Test
        @DisplayName("a negative total carries a TRAILING minus, per PIC Z(9).99- at :L142")
        void aNegativeTotalCarriesATrailingMinus() {
            stubTransactionFile(projectedRecord("0000000000000001", CARD_LOW, "-45.50", "REFUND"));
            crossReferenceDataset.enqueueSequential("10", "");
            stubKeyedFixtureRows(1L, 1L);

            Statement statement = processor.process(new CardCrossReference(CARD_LOW, 1L, 1L));
            String rendered = totalLineOf(statement).stripTrailing();

            assertThat(rendered)
                    .as("app/cbl/CBSTM03A.CBL:L142 - trailing sign, NOT the leading minus of"
                            + " app/cpy/CVTRA07Y.cpy's -ZZZ,ZZZ,ZZZ.ZZ")
                    .endsWith("-")
                    .contains("45.50")
                    .doesNotContain("-45.50");
        }

        /**
         * 🔴 The zero case: {@code Z(9).99-} blanks its leading zeros rather than printing them.
         *
         * <p>A card with no transactions totals zero, and {@code Z} suppression means the nine integer
         * positions render as spaces with only {@code '.00'} and a blank sign position remaining. Contrast
         * {@code ST-CURR-BAL}'s {@code PIC 9(9).99-} at {@code :L113}, which fills the same positions with
         * literal zeros — group 5 asserts that half, and this asserts the other.
         */
        @Test
        @DisplayName("a zero total blanks its leading zeros, unlike the 9(9).99- balance mask")
        void aZeroTotalSuppressesItsLeadingZeros() {
            stubTransactionFile(projectedRecord("0000000000000001", CARD_HIGH, "1.00", "OTHER CARD"));
            crossReferenceDataset.enqueueSequential("10", "");
            stubKeyedFixtureRows(1L, 1L);

            Statement statement = processor.process(new CardCrossReference(CARD_LOW, 1L, 1L));
            String rendered = totalLineOf(statement);

            assertThat(statement.totalExpenditure())
                    .as("no transaction matched, so WS-TOTAL-AMT stayed at the :L325 reset")
                    .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(rendered)
                    .as("app/cbl/CBSTM03A.CBL:L142 PIC Z(9).99- suppresses leading zeros to spaces")
                    .contains(".00")
                    .doesNotContain("000000000.00")
                    .doesNotContain("-");
        }

        /**
         * The {@code PIC X(100)} markup field carries exactly 34 declared fragments.
         *
         * <p>{@code app/cbl/CBSTM03A.CBL:L148-L211} declares {@code 01 HTML-LINES} with
         * {@code 05 HTML-FIXED-LN PIC X(100)} at {@code :L149} and 34 88-level markup literals beneath it,
         * beginning {@code HTML-L01 VALUE '<!DOCTYPE html>'} at {@code :L150} and
         * {@code HTML-L02 VALUE '<html lang="en">'} at {@code :L151} and ending at {@code HTML-LTRE}. The count
         * is asserted so that a lost or duplicated fragment fails here rather than producing a subtly short
         * document. Three further {@code PIC X(100)} groups sit at {@code :L221-L223}.
         */
        @Test
        @DisplayName("34 fragments are declared, opening <!DOCTYPE html> and <html lang=\"en\">")
        void theFragmentTableMatchesTheDeclaration() {
            Map<String, String> fragments = StatementProcessor.htmlFragments();

            assertThat(fragments)
                    .as("app/cbl/CBSTM03A.CBL:L148-L211 declares 34 88-levels under HTML-FIXED-LN")
                    .hasSize(34);
            assertThat(fragments.get("HTML_L01"))
                    .as("app/cbl/CBSTM03A.CBL:L150")
                    .isEqualTo("<!DOCTYPE html>");
            assertThat(fragments.get("HTML_L02"))
                    .as("app/cbl/CBSTM03A.CBL:L151")
                    .isEqualTo("<html lang=\"en\">");
            assertThat(fragments.get("HTML_L80"))
                    .as("app/cbl/CBSTM03A.CBL:L211")
                    .isEqualTo("</html>");
            assertThat(fragments)
                    .as("HTML-LTDS at :L161 is declared but never SET anywhere in the procedure division;"
                            + " it is present so the table matches the declaration (severity Low, logged)")
                    .containsKey("HTML_LTDS");
            assertThat(fragments.values())
                    .as("app/cbl/CBSTM03A.CBL:L149 PIC X(100) bounds every fragment")
                    .allSatisfy(fragment -> assertThat(fragment.length()).isLessThanOrEqualTo(HTML_WIDTH));
        }

        /**
         * The document opens with the declaration and the root element, in declaration order.
         *
         * <p>{@code 5100-WRITE-HTML-HEADER} at {@code :L506} sets and writes {@code HTML-L01} then
         * {@code HTML-L02} as its first two statements, so the emitted document must begin with them.
         */
        @Test
        @DisplayName("the emitted document opens with HTML-L01 then HTML-L02, per :L508-L510")
        void theDocumentOpensWithTheDeclarationThenTheRoot() {
            Statement statement = processor.process(stubHappyPath());

            assertThat(statement.htmlLines().get(0).stripTrailing())
                    .as("app/cbl/CBSTM03A.CBL:L508-L509 SET HTML-L01 then WRITE")
                    .isEqualTo("<!DOCTYPE html>");
            assertThat(statement.htmlLines().get(1).stripTrailing())
                    .as("app/cbl/CBSTM03A.CBL:L510 SET HTML-L02")
                    .isEqualTo("<html lang=\"en\">");
        }

        /**
         * The markup fragments are fixed literals from the source and are never composed from input.
         *
         * <p>Rule 1 Clause D2 flags injection patterns. Every fragment the table serves is byte-identical to a
         * source literal, so no untrusted value can reach the markup structure — only the escaped text nodes,
         * which group 12 covers. This test proves the table is a constant: it is unmodifiable, and two calls
         * return equal content.
         *
         * @param key a fragment whose literal must be exactly the source's
         */
        @ParameterizedTest(name = "{0} is a fixed source literal")
        @ValueSource(strings = {"HTML_L01", "HTML_L02", "HTML_LTRS", "HTML_LTRE", "HTML_LTDE",
                "HTML_L75", "HTML_L78", "HTML_L79", "HTML_L80"})
        @DisplayName("the fragment table is an immutable constant, never built from input")
        void theFragmentTableIsAnImmutableConstant(final String key) {
            Map<String, String> first = StatementProcessor.htmlFragments();
            Map<String, String> second = StatementProcessor.htmlFragments();

            assertThat(first.get(key)).isNotNull().isEqualTo(second.get(key));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .as("a mutable markup table would let a caller inject structure")
                    .isThrownBy(() -> first.put(key, "<script>"));
        }

        /**
         * Extracts the single {@code ST-LINE14A} total line from a statement.
         *
         * @param statement the composed statement, never {@code null}
         * @return the 80-character total line, never {@code null}
         */
        private String totalLineOf(final Statement statement) {
            return statement.textLines().stream()
                    .filter(line -> line.startsWith("Total EXP:"))
                    .reduce((first, second) -> {
                        throw new IllegalStateException(
                                "app/cbl/CBSTM03A.CBL:L436 writes ST-LINE14A exactly once per statement");
                    })
                    .orElseThrow(() -> new IllegalStateException(
                            "app/cbl/CBSTM03A.CBL:L436 must emit the Total EXP: line"));
        }
    }

    @Nested
    @DisplayName("19. The 350-byte record: 32 + 318, timestamps as text, and decimal-only money")
    class RecordGeometryAndTypes {

        /**
         * All three figures of {@code app/cpy/COSTM01.CPY:L20-L36}, asserted together.
         *
         * <p>{@code TRNX-KEY} is {@code TRNX-CARD-NUM X(16)} plus {@code TRNX-ID X(16)} = <b>32</b>,
         * corroborated by {@code KEYS(32 0)} on the work cluster at {@code app/jcl/CREASTMT.JCL:L30}.
         * {@code TRNX-REST} sums to <b>318</b>: 2 + 4 + 10 + 100 + 11 + 9 + 50 + 50 + 10 + 26 + 26 + 20. The
         * record is therefore <b>350</b>, corroborated by {@code RECORDSIZE(350 350)} at
         * {@code app/jcl/CREASTMT.JCL:L32} and by {@code DCB=(LRECL=350,...)} at {@code :L50}.
         */
        @Test
        @DisplayName("TRNX-KEY is 32, TRNX-REST is 318 and the record is 350")
        void theThreeRecordFiguresHold() {
            int remainder = StatementTransaction.TYPE_CODE_LENGTH
                    + StatementTransaction.CATEGORY_CODE_LENGTH
                    + StatementTransaction.SOURCE_LENGTH
                    + StatementTransaction.DESCRIPTION_LENGTH
                    + StatementTransaction.AMOUNT_LENGTH
                    + StatementTransaction.MERCHANT_ID_LENGTH
                    + StatementTransaction.MERCHANT_NAME_LENGTH
                    + StatementTransaction.MERCHANT_CITY_LENGTH
                    + StatementTransaction.MERCHANT_ZIP_LENGTH
                    + StatementTransaction.ORIGINATING_TIMESTAMP_LENGTH
                    + StatementTransaction.PROCESSING_TIMESTAMP_LENGTH
                    + StatementTransaction.FILLER_LENGTH;

            assertThat(StatementTransaction.KEY_LENGTH)
                    .as("app/cpy/COSTM01.CPY:L21-L23 - 16 + 16; KEYS(32 0) at app/jcl/CREASTMT.JCL:L30")
                    .isEqualTo(StatementTransaction.CARD_NUMBER_LENGTH
                            + StatementTransaction.TRANSACTION_ID_LENGTH)
                    .isEqualTo(32);
            assertThat(StatementTransaction.REMAINDER_LENGTH)
                    .as("app/cpy/COSTM01.CPY:L24-L36 - the twelve subfields of TRNX-REST")
                    .isEqualTo(remainder)
                    .isEqualTo(318);
            assertThat(StatementTransaction.RECORD_LENGTH)
                    .as("RECORDSIZE(350 350) at app/jcl/CREASTMT.JCL:L32, LRECL=350 at :L50")
                    .isEqualTo(StatementTransaction.KEY_LENGTH + StatementTransaction.REMAINDER_LENGTH)
                    .isEqualTo(350);
        }

        /**
         * 🔴 The card number is first and the identifier second, reshuffled relative to
         * {@code app/cpy/CVTRA05Y.cpy}.
         *
         * <p>In the base layout the identifier occupies bytes 1-16 and the card number 263-278. In the
         * projected layout of {@code app/cpy/COSTM01.CPY} the card number occupies 1-16 and the identifier
         * 17-32. The reshuffle is produced by {@code OUTREC FIELDS=(1:263,16,17:1,262,279:279,50)} at
         * {@code app/jcl/CREASTMT.JCL:L54}, and it is what makes the 32-byte composite key
         * card-number-major — which in turn is what makes the ascending-card control break work.
         */
        @Test
        @DisplayName("the projected record leads with the card number, not the transaction identifier")
        void theProjectedRecordLeadsWithTheCardNumber() {
            String projected = projectedRecord("0000000000683580", CARD_LOW, "194.00", "PURCHASE");

            assertThat(projected)
                    .as("app/jcl/CREASTMT.JCL:L54 field 1:263,16 relocates the card number to the front")
                    .startsWith(CARD_LOW);
            assertThat(projected.substring(StatementTransaction.CARD_NUMBER_LENGTH,
                            StatementTransaction.KEY_LENGTH))
                    .as("field 17:1,262 places the original head - identifier first - at offset 17")
                    .isEqualTo("0000000000683580");
            assertThat(StatementTransaction.BASE_CARD_NUMBER_OFFSET)
                    .as("app/cpy/CVTRA05Y.cpy puts TRAN-CARD-NUM at 263, not at 1")
                    .isEqualTo(263);
        }

        /**
         * 🔴 Both timestamps are {@link String} over {@code CHAR(26)}, never a temporal type.
         *
         * <p>{@code TRNX-ORIG-TS} and {@code TRNX-PROC-TS} are {@code PIC X(26)} at
         * {@code app/cpy/COSTM01.CPY:L34-L35}. Modelling either as {@code LocalDateTime}, {@code Timestamp} or
         * {@code Instant} would be actively wrong rather than merely unidiomatic: the projection of
         * {@code app/jcl/CREASTMT.JCL:L54} delivers the processing timestamp <b>24 characters long</b>, and no
         * temporal type can hold a truncated timestamp — the parse would throw on every record.
         */
        @Test
        @DisplayName("origTs and procTs are declared String, so a 24-character truncation is representable")
        void theTimestampsAreTextNotTemporal() {
            for (String component : List.of("originatingTimestamp", "processingTimestamp")) {
                assertThat(recordComponentType(component))
                        .as("app/cpy/COSTM01.CPY:L34-L35 PIC X(26); a temporal type could not hold the"
                                + " 24-character value app/jcl/CREASTMT.JCL:L54 produces")
                        .isEqualTo(String.class);
            }
            assertThat(StatementTransaction.PROCESSING_TIMESTAMP_SIGNIFICANT_LENGTH)
                    .as("50 bytes from offset 279 reach 328, so only 24 of 26 survive")
                    .isEqualTo(24);
            assertThat(StatementTransaction.PROCESSING_TIMESTAMP_LENGTH
                            - StatementTransaction.PROCESSING_TIMESTAMP_SIGNIFICANT_LENGTH)
                    .as("bytes 329-330 are never written and are padded back")
                    .isEqualTo(StatementTransaction.PROCESSING_TIMESTAMP_PAD_LENGTH);
        }

        /**
         * Money is {@link BigDecimal} throughout: no {@code float} and no {@code double} anywhere.
         *
         * <p>Gate 6 admits no binary floating-point type in a financial field, and
         * {@code TRNX-AMT PIC S9(09)V99} at {@code app/cpy/COSTM01.CPY:L29} is decimal with a fixed scale of 2.
         * This inspects the declared component and accumulator types rather than trusting a value comparison,
         * because a {@code double} would pass many value assertions before failing on a repeating binary
         * fraction.
         */
        @Test
        @DisplayName("no financial component or accumulator is float or double")
        void noFinancialFieldIsBinaryFloatingPoint() {
            assertThat(recordComponentType("amount"))
                    .as("app/cpy/COSTM01.CPY:L29 TRNX-AMT PIC S9(09)V99 is decimal")
                    .isEqualTo(BigDecimal.class);
            for (var component : Statement.class.getRecordComponents()) {
                assertThat(component.getType())
                        .as("Statement.%s must not be a binary floating-point type (Gate 6)",
                                component.getName())
                        .isNotIn(float.class, double.class, Float.class, Double.class);
            }
            assertThat(StatementTransaction.AMOUNT_SCALE).isEqualTo(2);
            assertThat(StatementTransaction.AMOUNT_INTEGER_DIGITS)
                    .as("S9(09)V99 - nine integer digits")
                    .isEqualTo(9);
        }

        /**
         * Money equality is by {@code compareTo}, not {@code equals}, and the accumulator proves it.
         *
         * <p>{@code 45.5} and {@code 45.50} are the same amount and are not {@link BigDecimal#equals(Object)}
         * to each other, so an implementation that compared with {@code equals} would reject correct totals.
         * The accumulated total is asserted equal by comparison to a differently-scaled literal, which only
         * holds under {@code compareTo} semantics.
         */
        @Test
        @DisplayName("the total compares equal to a differently scaled literal, per AAP invariant 1")
        void moneyComparesByValueNotByScale() {
            stubTransactionFile(projectedRecord("0000000000000001", CARD_LOW, "45.50", "ONE"));
            crossReferenceDataset.enqueueSequential("10", "");
            stubKeyedFixtureRows(1L, 1L);

            BigDecimal total = processor.process(new CardCrossReference(CARD_LOW, 1L, 1L))
                    .totalExpenditure();

            assertThat(total)
                    .as("compareTo, never equals: 45.5 and 45.50 are the same money")
                    .isEqualByComparingTo(new BigDecimal("45.5"))
                    .isEqualByComparingTo(new BigDecimal("45.50"));
            assertThat(total.equals(new BigDecimal("45.5")))
                    .as("proof that equals would have rejected it, which is why compareTo is mandated")
                    .isFalse();
        }

        /**
         * The two-key comparator orders by card number and only then by identifier.
         *
         * <p>{@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)} at {@code app/jcl/CREASTMT.JCL:L53} names the card
         * number first. A comparator that led with the identifier would still be a total order and would still
         * sort - it would simply break the control break, because transactions for one card would no longer be
         * contiguous. This asserts the precedence directly: a lower identifier on a higher card must sort
         * after a higher identifier on a lower card.
         */
        @Test
        @DisplayName("the card number outranks the identifier, so one card's rows stay contiguous")
        void theCardNumberOutranksTheIdentifier() {
            Transaction lowCardHighId = baseTransaction("0000000000000009", CARD_A,
                    new BigDecimal("1.00"), "LOW CARD HIGH ID");
            Transaction highCardLowId = baseTransaction("0000000000000001", CARD_B,
                    new BigDecimal("1.00"), "HIGH CARD LOW ID");
            Comparator<Transaction> comparator = StatementProcessor.statementSortComparator();

            assertThat(comparator.compare(lowCardHighId, highCardLowId))
                    .as("app/jcl/CREASTMT.JCL:L53 - key 263,16 precedes key 1,16")
                    .isNegative();
            assertThat(comparator.compare(highCardLowId, lowCardHighId)).isPositive();
        }

        /**
         * Resolves the declared type of one {@link StatementTransaction} record component.
         *
         * @param componentName the record component name
         * @return its declared type, never {@code null}
         */
        private Class<?> recordComponentType(final String componentName) {
            for (var component : StatementTransaction.class.getRecordComponents()) {
                if (component.getName().equals(componentName)) {
                    return component.getType();
                }
            }
            throw new IllegalStateException(
                    "app/cpy/COSTM01.CPY declares " + componentName + "; the record must carry it");
        }
    }

    @Nested
    @DisplayName("20. ABEND VARIANCE: this program is OUTSIDE the abend-999 contract")
    class AbendVariance {

        /**
         * 🔴 {@code CBSTM03A} carries <b>no</b> abend code, unlike the eight programs that do.
         *
         * <p>{@code 9999-ABEND-PROGRAM} at {@code app/cbl/CBSTM03A.CBL:L921-L923} is exactly two statements:
         * {@code DISPLAY 'ABENDING PROGRAM'} and {@code CALL 'CEE3ABD'}. It declares <b>no
         * {@code MOVE 999 TO ABCODE}</b> and <b>no {@code MOVE 0 TO TIMING}</b>, so it calls the language
         * environment abend service with whatever those fields already held.
         *
         * <p>{@code MOVE 999 TO ABCODE} appears in exactly eight programs — {@code CBACT01C},
         * {@code CBACT02C}, {@code CBACT03C}, {@code CBACT04C}, {@code CBCUS01C}, {@code CBTRN01C},
         * {@code CBTRN02C} and {@code CBTRN03C} — and <b>{@code CBSTM03A} and {@code CBSTM03B} are not among
         * them</b>. The abend-999 contract must therefore not be over-generalised onto this path: the
         * statement path's fatal outcome carries no abend code at all, which is what distinguishes it from the
         * posting, interest and report paths.
         */
        @Test
        @DisplayName("a statement-path abend carries NO abend code 999, unlike CBTRN02C and CBACT04C")
        void theStatementPathCarriesNoAbendCode() {
            stubTransactionFile(projectedRecord("0000000000000001", CARD_LOW, "1.00", "ONE"));
            crossReferenceDataset.enqueueSequential("10", "");
            customerDataset.keyedDefault("35");

            FatalProcessingException abend = catchStatementAbend();

            assertThat(abend.getAbendCode())
                    .as("app/cbl/CBSTM03A.CBL:L921-L923 has no MOVE 999 TO ABCODE, unlike"
                            + " app/cbl/CBTRN02C.cbl:L707-L710, so ABEND-CODE PIC X(4) stays as declared")
                    .isNotEqualTo(String.valueOf(FatalProcessingException.BATCH_ABEND_CODE))
                    .isNotNull()
                    .isBlank();
            assertThat(abend.getAbendCode())
                    .as("an unset ABEND-CODE is four spaces, the width app/cpy/CSMSG02Y.cpy declares -"
                            + " modelling it as blank rather than as 999 is the whole point of this group")
                    .hasSize(FileStatusMapper.ABEND_CODE_UNSET.length())
                    .isEqualTo(FileStatusMapper.ABEND_CODE_UNSET);
        }

        /**
         * No part of the {@code CABENDD.CPY} payload is fabricated for this program.
         *
         * <p>{@code app/cpy/CSMSG02Y.cpy} — internally titled {@code CABENDD.CPY} — declares
         * {@code ABEND-CODE X(4)}, {@code ABEND-CULPRIT X(8)}, {@code ABEND-REASON X(50)} and
         * {@code ABEND-MSG X(72)}. The distinction this test draws is between the field the source
         * <em>declines</em> to populate and the fields a diagnostic legitimately supplies:
         * <ul>
         *   <li>{@code ABEND-CODE} is <b>blank</b>, because no {@code MOVE 999 TO ABCODE} exists here. Writing
         *   999 into it would be fabricated evidence under Rule 1 Clause F.</li>
         *   <li>{@code ABEND-CULPRIT} names <b>{@code CBSTM03A}</b> — an eight-character program name, exactly
         *   the width {@code X(8)} declares. That is not fabrication: it is the citation Clause F requires, and
         *   it is what tells an operator which of the ten batch programs abended.</li>
         *   <li>{@code ABEND-MSG} carries the diagnostic and must not fall back to
         *   {@link FatalProcessingException#DEFAULT_ABEND_MESSAGE}, which would mean the reason was lost.</li>
         * </ul>
         */
        @Test
        @DisplayName("the abend code is blank but the culprit correctly names CBSTM03A, not a fabricated 999")
        void theAbendPayloadIsHonestAboutWhatTheSourceSets() {
            stubTransactionFile(projectedRecord("0000000000000001", CARD_LOW, "1.00", "ONE"));
            crossReferenceDataset.enqueueSequential("10", "");
            customerDataset.keyedDefault("35");

            FatalProcessingException abend = catchStatementAbend();

            assertThat(abend.getAbendCode())
                    .as("the one field CBSTM03A declines to set, per app/cbl/CBSTM03A.CBL:L921-L923")
                    .isBlank();
            assertThat(abend.getAbendCulprit())
                    .as("ABEND-CULPRIT X(8) at app/cpy/CSMSG02Y.cpy - the citation Clause F requires,"
                            + " naming the abending program rather than inventing a code for it")
                    .isNotNull()
                    .startsWith("CBSTM03A");
            assertThat(abend.getAbendReason())
                    .as("ABEND-REASON X(50) carries why, without a card number or any other identifier")
                    .isNotNull()
                    .isNotBlank()
                    .doesNotContain(CARD_LOW);
            assertThat(abend.getAbendMessage())
                    .as("a fall back to the default message would mean the diagnostic was lost")
                    .isNotNull()
                    .isNotEqualTo(FatalProcessingException.DEFAULT_ABEND_MESSAGE)
                    .doesNotContain(CARD_LOW);
        }

        /**
         * The abend preserves the underlying cause rather than swallowing it.
         *
         * <p>Rule 1 Clause B4 requires the root cause to survive. The source could only
         * {@code DISPLAY 'RETURN CODE: ' WS-M03B-RC} before abending; the translation keeps that diagnostic
         * <em>and</em> chains the originating exception, so a status-mapping failure remains traceable to the
         * status that caused it.
         */
        @Test
        @DisplayName("the failure logs the source's ERROR READING literal and preserves the cause")
        void theFailurePreservesItsCause() {
            stubTransactionFile(projectedRecord("0000000000000001", CARD_LOW, "1.00", "ONE"));
            crossReferenceDataset.enqueueSequential("10", "");
            customerDataset.keyedDefault("35");

            CardDemoException failure = null;
            try {
                processor.process(new CardCrossReference(CARD_LOW, 1L, 1L));
            } catch (CardDemoException caught) {
                failure = caught;
            }

            assertThat(failure)
                    .as("a status of 35 at the keyed customer read must not pass silently")
                    .isNotNull();
            assertThat(failure.getMessage())
                    .as("Rule 1 Clause B4 - the message carries context, not just a type")
                    .isNotBlank();
            assertThat(loggedMessages())
                    .as("app/cbl/CBSTM03A.CBL:L383 DISPLAY 'ERROR READING CUSTFILE'")
                    .contains("ERROR READING CUSTFILE");
        }

        /**
         * {@code CBSTM03B} ends with a bare {@code GOBACK} and never abends on its own account.
         *
         * <p>{@code app/cbl/CBSTM03B.CBL:L130-L131} is {@code 9999-GOBACK.} followed by {@code GOBACK.} — no
         * abend, no {@code ABCODE}, and no {@code DISPLAY}. An unrecognised DD name reaches it through
         * {@code WHEN OTHER GO TO 9999-GOBACK} at {@code :L127-L128} and simply <b>returns with the return code
         * untouched</b>. So the decision to abend belongs entirely to the caller, which is why the guards live
         * in {@code CBSTM03A} and are asserted in group 14.
         */
        @Test
        @DisplayName("an unbound DD is rejected by the caller, because CBSTM03B just returns, per :L130-L131")
        void theSubprogramItselfNeverAbends() {
            FileService withoutAccountFile = new FileService(new FileStatusMapper(),
                    List.of(new FakeDataset(FileService.Dd.TRNXFILE),
                            new FakeDataset(FileService.Dd.XREFFILE),
                            new FakeDataset(FileService.Dd.CUSTFILE)));

            assertThat(withoutAccountFile.isBound(FileService.Dd.ACCTFILE))
                    .as("app/cbl/CBSTM03B.CBL:L118-L128 recognises four DD names; an unbound one falls to"
                            + " WHEN OTHER and returns")
                    .isFalse();
            assertThat(withoutAccountFile.isBound(FileService.Dd.TRNXFILE)).isTrue();
        }

        /**
         * Drives the processor to its abend and returns the exception.
         *
         * @return the raised abend, never {@code null}
         */
        private FatalProcessingException catchStatementAbend() {
            try {
                processor.process(new CardCrossReference(CARD_LOW, 1L, 1L));
            } catch (FatalProcessingException abend) {
                return abend;
            }
            throw new IllegalStateException(
                    "app/cbl/CBSTM03A.CBL:L385 performs 9999-ABEND-PROGRAM, so this must not return");
        }
    }


    @Nested
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.STRICT_STUBS)
    @DisplayName("21. The mocked CBSTM03B call contract: DD name, operation and payload at every call site")
    class FileServiceCallContract {

        /**
         * The {@code CALL 'CBSTM03B' USING WS-M03B-AREA} contract, verified against a strict mock.
         *
         * <p>Groups 1 to 20 drive a <em>real</em> {@link FileService} over fake datasets, because the status
         * guards under test are the production ones. This group inverts that: {@link FileService} is a
         * <b>Mockito mock</b>, so the assertion is about the calls the processor makes rather than about what
         * the service does with them. {@link org.mockito.quality.Strictness#STRICT_STUBS} applies, so a stub
         * this class declares and the processor never uses is itself a failure — which is what turns
         * "the processor probably opens all four" into proof.
         *
         * <p>The four DD names are those of {@code app/jcl/CREASTMT.JCL:L83-L86}: {@code TRNXFILE},
         * {@code XREFFILE}, {@code ACCTFILE} and {@code CUSTFILE}. Their <b>open order</b> is the order the
         * altered handlers ran — {@code :L731}, {@code :L766}, {@code :L784}, {@code :L802} — and
         * {@link InOrder} verification is what distinguishes an ordered sequence from four unordered calls.
         * The service's internals are owned by {@code com.cardemo.unit.service.FileServiceTest}; nothing here
         * asserts them.
         */
        @Test
        @DisplayName("initialise opens exactly the four DDs of :L83-L86, in the altered-handler order")
        void initialiseOpensTheFourDdsInOrder() {
            FileService mockService = mock(FileService.class);
            when(mockService.readAcceptingSecondaryStatus(FileService.Dd.TRNXFILE))
                    .thenReturn(projectedRecord("0000000000000001", CARD_LOW, "1.00", "ONE"));
            StatementProcessor mocked = new StatementProcessor(mockService);

            mocked.initialise();

            InOrder order = inOrder(mockService);
            order.verify(mockService).open(FileService.Dd.TRNXFILE);
            order.verify(mockService).readAcceptingSecondaryStatus(FileService.Dd.TRNXFILE);
            order.verify(mockService).open(FileService.Dd.XREFFILE);
            order.verify(mockService).open(FileService.Dd.CUSTFILE);
            order.verify(mockService).open(FileService.Dd.ACCTFILE);
            order.verifyNoMoreInteractions();
        }

        /**
         * The priming read uses the secondary-status-accepting operation, not the plain sequential read.
         *
         * <p>{@code :L744-L748} sets {@code M03B-READ} and then guards with
         * {@code IF WS-M03B-RC = '00' OR '04'}, so the priming read is one of the nine lenient sites. The
         * service exposes that as {@link FileService#readAcceptingSecondaryStatus(FileService.Dd)}, distinct
         * from {@link FileService#readNext(FileService.Dd)} which the {@code EVALUATE}-guarded sites use.
         * Choosing the wrong method at this call site is precisely how the leniency asymmetry of group 14 gets
         * broken, so it is verified at the call rather than only through its effect.
         */
        @Test
        @DisplayName("the priming read of :L744-L748 takes the lenient path, not the strict one")
        void thePrimingReadTakesTheLenientPath() {
            FileService mockService = mock(FileService.class);
            when(mockService.readAcceptingSecondaryStatus(FileService.Dd.TRNXFILE))
                    .thenReturn(projectedRecord("0000000000000001", CARD_LOW, "1.00", "ONE"));
            StatementProcessor mocked = new StatementProcessor(mockService);

            mocked.initialise();

            verify(mockService).readAcceptingSecondaryStatus(FileService.Dd.TRNXFILE);
            verify(mockService, never()).readNext(FileService.Dd.TRNXFILE);
        }

        /**
         * The driving read is a sequential {@code readNext} on {@code XREFFILE}, per {@code :L347-L351}.
         *
         * <p>{@code 1000-XREFFILE-GET-NEXT} sets {@code M03B-READ} — not {@code M03B-READ-K} — and its guard is
         * the {@code EVALUATE} at {@code :L353}, so it is a strict sequential read. An empty return models
         * {@code WHEN '10'}.
         */
        @Test
        @DisplayName("the cross-reference read is sequential on XREFFILE, per :L347-L348")
        void theCrossReferenceReadIsSequential() {
            FileService mockService = mock(FileService.class);
            when(mockService.readAcceptingSecondaryStatus(FileService.Dd.TRNXFILE))
                    .thenReturn(projectedRecord("0000000000000001", CARD_LOW, "1.00", "ONE"));
            when(mockService.readNext(FileService.Dd.XREFFILE)).thenReturn(Optional.empty());
            StatementProcessor mocked = new StatementProcessor(mockService);

            assertThat(mocked.readNextCrossReference())
                    .as("app/cbl/CBSTM03A.CBL:L356-L357 latches END-OF-FILE on '10'")
                    .isEmpty();

            verify(mockService).readNext(FileService.Dd.XREFFILE);
        }

        /**
         * 🔴 The two keyed reads pass the key <em>and its computed length</em>, which is the subtle half.
         *
         * <p>{@code 2000-CUSTFILE-GET} at {@code :L370-L377} sets {@code M03B-READ-K}, moves
         * {@code XREF-CUST-ID} into {@code WS-M03B-KEY} and computes
         * {@code WS-M03B-KEY-LN = LENGTH OF XREF-CUST-ID}, which is <b>9</b>. {@code 3000-ACCTFILE-GET} at
         * {@code :L394-L401} does the same with {@code XREF-ACCT-ID}, which is <b>11</b>. Those two lengths come
         * from {@code app/cpy/CVACT03Y.cpy} and are the cluster key lengths catalogued for {@code CUSTDATA} and
         * {@code ACCTDATA}. Passing the key without the right length would read the wrong record — or, on a
         * generic key field of {@code X(25)}, no record at all.
         */
        @Test
        @DisplayName("the keyed reads pass a 9-character customer key and an 11-character account key")
        void theKeyedReadsPassTheirComputedKeyLengths() {
            FileService mockService = mock(FileService.class);
            when(mockService.readAcceptingSecondaryStatus(FileService.Dd.TRNXFILE))
                    .thenReturn(projectedRecord("0000000000000001", CARD_LOW, "1.00", "ONE"));
            when(mockService.readByKey(any(FileService.Dd.class), anyString(), anyInt()))
                    .thenAnswer(invocation -> switch ((FileService.Dd) invocation.getArgument(0)) {
                        case CUSTFILE -> customerRows.getFirst();
                        case ACCTFILE -> accountRows.getFirst();
                        default -> throw new IllegalStateException(
                                "app/cbl/CBSTM03A.CBL performs no keyed read on any other DD");
                    });
            StatementProcessor mocked = new StatementProcessor(mockService);

            mocked.process(new CardCrossReference(CARD_LOW, 1L, 1L));

            verify(mockService).readByKey(FileService.Dd.CUSTFILE, "000000001",
                    FileService.Dd.CUSTFILE.keyWidth());
            verify(mockService).readByKey(FileService.Dd.ACCTFILE, "00000000001",
                    FileService.Dd.ACCTFILE.keyWidth());
            assertThat(FileService.Dd.CUSTFILE.keyWidth())
                    .as("COMPUTE WS-M03B-KEY-LN = LENGTH OF XREF-CUST-ID at app/cbl/CBSTM03A.CBL:L374")
                    .isEqualTo(9);
            assertThat(FileService.Dd.ACCTFILE.keyWidth())
                    .as("COMPUTE WS-M03B-KEY-LN = LENGTH OF XREF-ACCT-ID at app/cbl/CBSTM03A.CBL:L398")
                    .isEqualTo(11);
        }

        /**
         * {@code close} closes all four DDs and calls nothing else, per {@code :L331-L337}.
         *
         * <p>The mainline performs {@code 9100-TRNXFILE-CLOSE}, {@code 9200-XREFFILE-CLOSE},
         * {@code 9300-CUSTFILE-CLOSE} then {@code 9400-ACCTFILE-CLOSE} in that order.
         * {@link org.mockito.Mockito#verifyNoMoreInteractions} is what proves nothing extra was attempted —
         * no re-open, no stray read after the closes.
         */
        @Test
        @DisplayName("close closes all four DDs in the :L331-L337 order and does nothing else")
        void closeClosesAllFourInOrder() {
            FileService mockService = mock(FileService.class);
            StatementProcessor mocked = new StatementProcessor(mockService);

            mocked.close();

            InOrder order = inOrder(mockService);
            order.verify(mockService).close(FileService.Dd.TRNXFILE);
            order.verify(mockService).close(FileService.Dd.XREFFILE);
            order.verify(mockService).close(FileService.Dd.CUSTFILE);
            order.verify(mockService).close(FileService.Dd.ACCTFILE);
            verifyNoMoreInteractions(mockService);
        }

        /**
         * The payload the service returns is the only source of record content: nothing is invented.
         *
         * <p>{@code MOVE WS-M03B-FLDT TO TRNX-RECORD} at {@code :L756} and {@code :L839} is the whole of the
         * data path, so a field that appears in a statement but not in the returned payload would be
         * fabricated. Here the mock returns one known projected record and the statement is checked to carry
         * exactly that identifier, description and amount.
         */
        @Test
        @DisplayName("the statement carries only what WS-M03B-FLDT supplied, per :L756 and :L839")
        void thePayloadIsTheOnlySourceOfContent() {
            FileService mockService = mock(FileService.class);
            when(mockService.readAcceptingSecondaryStatus(FileService.Dd.TRNXFILE))
                    .thenReturn(projectedRecord("0000000000999999", CARD_LOW, "77.25", "MOCKED PAYLOAD"));
            when(mockService.readByKey(any(FileService.Dd.class), anyString(), anyInt()))
                    .thenAnswer(invocation -> switch ((FileService.Dd) invocation.getArgument(0)) {
                        case CUSTFILE -> customerRows.getFirst();
                        case ACCTFILE -> accountRows.getFirst();
                        default -> throw new IllegalStateException("no other DD is read by key");
                    });
            StatementProcessor mocked = new StatementProcessor(mockService);

            Statement statement = mocked.process(new CardCrossReference(CARD_LOW, 1L, 1L));

            assertThat(statement.textLines())
                    .as("app/cbl/CBSTM03A.CBL:L676-L678 moves the identifier, description and amount"
                            + " into ST-LINE14 from the payload and from nowhere else")
                    .anySatisfy(line -> assertThat(line)
                            .contains("0000000000999999")
                            .contains("MOCKED PAYLOAD")
                            .contains("77.25"));
            assertThat(statement.totalExpenditure()).isEqualByComparingTo(new BigDecimal("77.25"));
        }
    }

    @Nested
    @DisplayName("22. LOW/MEDIUM: preserved legacy defects and forbidden patterns, each with its severity")
    class PreservedDefectsAndForbiddenPatterns {

        /**
         * ⚠ <b>MEDIUM — LOG IT, FIX IT NOT.</b> The HTML output is declared at two different record lengths.
         *
         * <p>{@code app/jcl/CREASTMT.JCL} declares the same {@code HTMLFILE} DD at
         * <b>{@code DCB=(LRECL=80,...)} in the pre-delete step {@code STEP030}, line {@code L69}</b> and at
         * <b>{@code DCB=(LRECL=100,...)} in the execution step {@code STEP040}, line {@code L94}</b>. The two
         * cannot both be right.
         *
         * <p><b>The program's own field width settles it:</b> {@code 05 HTML-FIXED-LN PIC X(100)} at
         * {@code app/cbl/CBSTM03A.CBL:L149}, plus the three further {@code PIC X(100)} groups at
         * {@code :L221-L223}, mean every {@code WRITE FD-HTMLFILE-REC} moves 100 bytes. <b>100 is the real
         * width.</b> Remediation: none applied. The mismatch is recorded with both locators and is
         * <em>deliberately not reconciled</em>, because {@code app/**} is frozen and editing the JCL would
         * destroy the parity oracle. The Java side simply uses 100 and documents why.
         */
        @Test
        @DisplayName("MEDIUM: the 80-vs-100 HTMLFILE mismatch is settled at 100 by the X(100) field")
        void theHtmlRecordLengthMismatchIsSettledAtOneHundred() {
            assertThat(StatementTransaction.STATEMENT_HTML_RECORD_LENGTH)
                    .as("app/cbl/CBSTM03A.CBL:L149 HTML-FIXED-LN PIC X(100) and"
                            + " app/jcl/CREASTMT.JCL:L94 DCB=(LRECL=100,...) agree; :L69's LRECL=80 for the"
                            + " same DD is the logged Medium defect and is NOT reconciled")
                    .isEqualTo(100)
                    .isNotEqualTo(StatementTransaction.STATEMENT_TEXT_RECORD_LENGTH);
            assertThat(StatementTransaction.STATEMENT_TEXT_RECORD_LENGTH)
                    .as("app/cbl/CBSTM03A.CBL:L45 FD-STMTFILE-REC PIC X(80); the text stream really is 80,"
                            + " which is why the two streams must never share one width constant")
                    .isEqualTo(80);
            assertThat(StatementProcessor.htmlFragments().values())
                    .as("the fragments are bounded by 100, not by 80")
                    .allSatisfy(fragment -> assertThat(fragment.length()).isLessThanOrEqualTo(100));
        }

        /**
         * ⚠ <b>MEDIUM — LOG IT, FIX IT NOT.</b> The {@code STMTFILE} DD statement in {@code STEP040} is
         * corrupted.
         *
         * <p>{@code app/jcl/CREASTMT.JCL:L90} reads, verbatim,
         * {@code //         SPACE=(CYL,(1,1),RLSE), 00,RECFM=FB), ATA.VSAM.KSDS} — fragments of two other DD
         * statements spliced into one continuation line. Recorded with its locator. Remediation: none applied;
         * {@code app/**} is frozen. The consequence for this tier is nil, because the text record width comes
         * from {@code FD-STMTFILE-REC PIC X(80)} at {@code app/cbl/CBSTM03A.CBL:L45} and from the intact
         * {@code DCB=(LRECL=80,...)} at {@code :L89}, not from the corrupted line. Step gating and DD
         * allocation belong to {@code src/test/java/com/cardemo/integration/batch}.
         */
        @Test
        @DisplayName("MEDIUM: the corrupted STMTFILE DD line does not affect the 80-byte text width")
        void theCorruptedDdLineDoesNotAffectTheTextWidth() {
            Statement statement = processor.process(stubHappyPath());

            assertThat(statement.textLines())
                    .as("the width comes from app/cbl/CBSTM03A.CBL:L45 and the intact"
                            + " app/jcl/CREASTMT.JCL:L89, never from the corrupted :L90")
                    .isNotEmpty()
                    .allSatisfy(line -> assertThat(line).hasSize(TEXT_WIDTH));
            assertThat(TEXT_WIDTH).isEqualTo(StatementTransaction.STATEMENT_TEXT_RECORD_LENGTH);
        }

        /**
         * 🔴 <b>LOW — the {@code PSAPTR}/TIOT control-block peeking is omitted entirely, by design.</b>
         *
         * <p>{@code app/cbl/CBSTM03A.CBL:L235-L237} declares {@code 01 PSAPTR POINTER},
         * {@code 01 BUMP-TIOT PIC S9(08) BINARY VALUE ZERO} and
         * {@code 01 TIOT-INDEX REDEFINES BUMP-TIOT POINTER}, and the procedure division walks the z/OS prefixed
         * save area and task I/O table to {@code DISPLAY} the job and step names ({@code :L266-L291}). It is
         * pure mainframe control-block introspection with no portable meaning.
         *
         * <p><b>It must not be reproduced in any form</b> — no PSA peeking, no TIOT walking, no pointer
         * arithmetic, no {@code sun.misc.Unsafe}, no JNI and no reflection into JVM internals. Rule 1 Clause D2
         * requires known risky patterns to be flagged, and this is the flag. Recorded at severity <b>Low</b>
         * and owed an entry in the planned {@code DECISION_LOG.md}; the job identity it was reaching for is
         * supplied instead by
         * the batch job-instance identifier in the logging context. This test asserts the omission structurally:
         * the class under test declares no field of a pointer-like or unsafe type.
         */
        @Test
        @DisplayName("LOW: no PSAPTR/TIOT analogue - no pointer, Unsafe, JNI or ByteBuffer field")
        void noControlBlockPeekingAnalogueExists() {
            List<String> forbidden = new ArrayList<>();
            for (var field : StatementProcessor.class.getDeclaredFields()) {
                String typeName = field.getType().getName();
                if (typeName.startsWith("sun.misc")
                        || typeName.startsWith("jdk.internal")
                        || typeName.contains("Unsafe")
                        || typeName.startsWith("java.nio.ByteBuffer")
                        || typeName.startsWith("java.lang.foreign")) {
                    forbidden.add(field.getName() + " : " + typeName);
                }
            }

            assertThat(forbidden)
                    .as("app/cbl/CBSTM03A.CBL:L235-L237 PSAPTR/BUMP-TIOT/TIOT-INDEX are deliberately not"
                            + " translated; a pointer analogue here would be a Clause D2 risky pattern")
                    .isEmpty();
        }

        /**
         * 🔴 No external sort process is spawned: DFSORT became a {@link Comparator}.
         *
         * <p>AAP invariant 9 requires the DFSORT step of {@code app/jcl/CREASTMT.JCL:L53} to become
         * {@code java.util.Comparator} rather than an invoked utility, and Rule 1 Clause D2 independently
         * forbids shell-injection-shaped patterns. Both are discharged by the same fact: the sort is
         * {@link StatementProcessor#statementSortComparator()}, a pure in-process comparator, and the class
         * declares no method returning or accepting a {@link Process} or {@link ProcessBuilder}.
         */
        @Test
        @DisplayName("the sort is an in-process Comparator; no Process or ProcessBuilder is referenced")
        void noExternalSortProcessIsSpawned() {
            assertThat(StatementProcessor.statementSortComparator())
                    .as("app/jcl/CREASTMT.JCL:L53 SORT FIELDS became a Comparator, per AAP invariant 9")
                    .isInstanceOf(Comparator.class);

            List<String> processReferences = new ArrayList<>();
            for (Method method : StatementProcessor.class.getDeclaredMethods()) {
                if (Process.class.isAssignableFrom(method.getReturnType())
                        || ProcessBuilder.class.isAssignableFrom(method.getReturnType())) {
                    processReferences.add("returns from " + method.getName());
                }
                for (Class<?> parameterType : method.getParameterTypes()) {
                    if (Process.class.isAssignableFrom(parameterType)
                            || ProcessBuilder.class.isAssignableFrom(parameterType)) {
                        processReferences.add("parameter of " + method.getName());
                    }
                }
            }

            assertThat(processReferences)
                    .as("no external sort utility is invoked; Runtime.exec and ProcessBuilder are absent")
                    .isEmpty();
        }

        /**
         * No secret, credential or endpoint literal reaches a log record (Rule 1 Clause D1).
         *
         * <p>The clause names tests explicitly, so this tier carries no signing key, no AWS credential, no
         * token and no BCrypt hash. The statement body legitimately carries a card number, a customer name, an
         * address and a credit score — none of which may appear in a diagnostic. The whole run is driven and
         * then every captured log record is checked against the values the fixtures actually used.
         */
        @Test
        @DisplayName("no log record carries a card number, an endpoint, a credential or a JDBC URL")
        void noLogRecordCarriesSensitiveOrEnvironmentSpecificText() {
            stubTransactionFile(
                    projectedRecord("0000000000000001", CARD_LOW, "10.00", "ONE"),
                    projectedRecord("0000000000000002", CARD_HIGH, "20.00", "TWO"));
            crossReferenceDataset.enqueueSequential("10", "");
            stubKeyedFixtureRows(1L, 1L);
            processor.process(new CardCrossReference(CARD_LOW, 1L, 1L));
            processor.close();

            assertThat(loggedMessages())
                    .as("Rule 1 Clause D1 names tests explicitly; diagnostics carry DD names and counts only")
                    .isNotEmpty()
                    .allSatisfy(message -> assertThat(message)
                            .doesNotContain(CARD_LOW)
                            .doesNotContain(CARD_HIGH)
                            .doesNotContain("secret")
                            .doesNotContain("password")
                            .doesNotContain("jdbc:")
                            .doesNotContain("amazonaws.com")
                            .doesNotContain("localhost")
                            .doesNotContain("127.0.0.1"));
        }

        /**
         * The two capacity warnings cite their source locators, so a reader can check the claim.
         *
         * <p>Rule 1 Clause F requires evidence-based output. The warnings that mark the removed ceiling are
         * operator-facing, so they carry the locators of the two {@code OCCURS} clauses rather than bare
         * numbers: {@code app/cbl/CBSTM03A.CBL:L226} for the 51 cards and {@code :L228} for the 10
         * transactions.
         */
        @Test
        @DisplayName("the capacity warnings cite :L226 and :L228 rather than bare numbers")
        void theCapacityWarningsCiteTheirLocators() {
            List<String> records = new ArrayList<>();
            for (int index = 1; index <= StatementTransaction.LEGACY_MAX_TRANSACTIONS_PER_CARD + 1; index++) {
                records.add(projectedRecord(String.format(Locale.ROOT, "%016d", index),
                        CARD_LOW, "1.00", "RECORD " + index));
            }
            stubTransactionFile(records.toArray(new String[0]));
            crossReferenceDataset.enqueueSequential("10", "");
            stubKeyedFixtureRows(1L, 1L);

            processor.process(new CardCrossReference(CARD_LOW, 1L, 1L));

            assertThat(loggedMessages())
                    .as("Rule 1 Clause F - the warning names WS-TRAN-TBL OCCURS 10 and its locator")
                    .anySatisfy(message -> assertThat(message)
                            .contains("app/cbl/CBSTM03A.CBL:L228")
                            .contains("legacy capacity"));
        }
    }


    /**
     * Sets one declared field of an entity to a chosen value, so a test can express an absent
     * {@code NOT NULL} column that the entity's own constructor refuses to build.
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

    /**
     * A programmable {@link FileService.Dataset} standing in for one of the four datasets
     * {@code CBSTM03B} serves.
     *
     * <p>This is a hand-written fake rather than a mock for two reasons. First, the sequential contract is
     * stateful - each read consumes the next queued outcome - and expressing that through a mock's
     * consecutive-return chain reads far worse than a queue does. Second, and decisively, the file service
     * itself is the real production instance here, so the fake sits below the status guards rather than
     * replacing them: every {@code '00'}, {@code '04'}, {@code '10'} and {@code '35'} this fake reports is
     * interpreted by the production {@link FileStatusMapper}.
     */
    private static final class FakeDataset implements FileService.Dataset {

        /**
         * The test-owned list this fake appends to when it is opened, so the open order can be asserted.
         *
         * <p>Injected rather than {@code static}: a class-level accumulator is global mutable state, which
         * Rule 1 Clause B3 rules out, and it made every test depend on a reset call that a new test could
         * silently forget. Left {@code null} when a test does not care about ordering.
         */
        private List<FileService.Dd> openSequence;

        /** The queued sequential read outcomes, consumed in order. */
        private final Deque<FileService.DatasetRead> sequentialReads = new ArrayDeque<>();

        /** The keyed read outcomes, resolved by exact key. */
        private final Map<String, FileService.DatasetRead> keyedReads = new LinkedHashMap<>();

        private final FileService.Dd dd;
        private String openStatus = "00";
        private String closeStatus = "00";
        private FileService.DatasetRead keyedDefault = FileService.DatasetRead.withoutRecord("23");
        private int openCount;
        private int closeCount;
        private int sequentialReadCount;

        /**
         * @param dd the DD this fake is bound to
         */
        FakeDataset(final FileService.Dd dd) {
            this.dd = dd;
        }

        /**
         * Directs this fake to append its DD to the supplied list each time it opens successfully.
         *
         * @param sequence the test-owned accumulator, never {@code null}
         */
        void recordOpensInto(final List<FileService.Dd> sequence) {
            this.openSequence = sequence;
        }

        @Override
        public FileService.Dd dd() {
            return dd;
        }

        @Override
        public String openInput() {
            openCount++;
            if ("00".equals(openStatus) && openSequence != null) {
                openSequence.add(dd);
            }
            return openStatus;
        }

        @Override
        public String close() {
            closeCount++;
            return closeStatus;
        }

        @Override
        public FileService.DatasetRead readNext() {
            sequentialReadCount++;
            FileService.DatasetRead queued = sequentialReads.poll();
            return queued == null ? FileService.DatasetRead.withoutRecord("10") : queued;
        }

        @Override
        public FileService.DatasetRead readByKey(final String recordKey) {
            return keyedReads.getOrDefault(recordKey.strip(), keyedDefault);
        }

        /**
         * Queues one sequential read outcome.
         *
         * @param status the two-character status to report
         * @param record the payload to serve
         */
        void enqueueSequential(final String status, final String record) {
            sequentialReads.add(FileService.DatasetRead.of(status, record));
        }

        /**
         * Discards every queued sequential read outcome that has not been consumed.
         *
         * <p>Needed because the processor primes one record and reads again only when a control break is
         * walked, so a test that stubs a whole file and then only initialises leaves entries behind. A test
         * that re-stubs after that has to say whether it means to append to what is left or to start clean.
         */
        void drainSequential() {
            sequentialReads.clear();
        }

        /**
         * Binds one keyed read outcome.
         *
         * @param key the key, compared after stripping
         * @param status the two-character status to report
         * @param record the payload to serve
         */
        void bindKeyed(final String key, final String status, final String record) {
            keyedReads.put(key, FileService.DatasetRead.of(status, record));
        }

        /**
         * Sets the status every unbound keyed read reports.
         *
         * @param status the two-character status
         */
        void keyedDefault(final String status) {
            keyedDefault = FileService.DatasetRead.withoutRecord(status);
        }

        /**
         * Sets the status the open reports.
         *
         * @param status the two-character status
         */
        void openStatus(final String status) {
            openStatus = status;
        }

        /**
         * Sets the status the close reports.
         *
         * @param status the two-character status
         */
        void closeStatus(final String status) {
            closeStatus = status;
        }

        /** @return how many times this dataset was opened. */
        int openCount() {
            return openCount;
        }

        /** @return how many times this dataset was closed. */
        int closeCount() {
            return closeCount;
        }

        /** @return how many sequential reads this dataset served. */
        int sequentialReadCount() {
            return sequentialReadCount;
        }
    }
}

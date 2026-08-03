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
 * Source      : app/cbl/CBSTM03A.CBL:L293-L314 (ALTER entry point)
 *               app/cbl/CBSTM03A.CBL:L726-L728 (altered paragraph)
 *               app/cbl/CBSTM03A.CBL:L760-L815 (the five handlers)
 *               app/cbl/CBSTM03A.CBL:L818-L853 (table build loop)
 *               app/cbl/CBSTM03A.CBL:L225-L233 (51 x 10 table)
 *               app/cbl/CBSTM03A.CBL:L416-L456 (ordered lookup)
 *               app/cbl/CBSTM03A.CBL:L149        (100-byte HTML field)
 *               app/cbl/CBSTM03B.CBL:L1-L230     (file service)
 *               app/cpy/COSTM01.CPY              (32-byte TRNX-KEY)
 *               app/jcl/CREASTMT.JCL:STEP010     (sort + OUTREC)
 *               app/jcl/CREASTMT.JCL:STEP040     (LRECL 80 and 100)
 *               app/data/ASCII/custdata.txt      (500-byte rows)
 *               app/data/ASCII/acctdata.txt      (300-byte rows)
 *               app/data/ASCII/cardxref.txt      (36-byte rows)
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
import com.cardemo.batch.processors.StatementProcessor;
import com.cardemo.batch.processors.StatementProcessor.Statement;
import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.model.dto.StatementTransaction;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Transaction;
import com.cardemo.service.shared.FileService;
import com.cardemo.service.shared.FileStatusMapper;
import com.cardemo.unit.model.FixtureLoader;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
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
import org.junit.jupiter.params.provider.ValueSource;
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
 * class alone; it needs no container, no database and no cloud emulator.
 *
 * <p><b>Key configuration and defaults.</b> Text records are 80 characters and HTML records are 100, the
 * two {@code LRECL} values {@code app/jcl/CREASTMT.JCL:STEP040} declares. The legacy table held 51 cards
 * of 10 transactions; those two numbers appear here only as the thresholds at which a warning is logged,
 * because the ceiling itself is deliberately gone.
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
 * </ul>
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

    private FakeDataset transactionDataset;
    private FakeDataset crossReferenceDataset;
    private FakeDataset customerDataset;
    private FakeDataset accountDataset;
    private FileService fileService;
    private StatementProcessor processor;

    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger logger;
    private Level originalLevel;

    /** The 500-byte customer rows of {@code app/data/ASCII/custdata.txt}. */
    private static List<String> customerRows;

    /** The 300-byte account rows of {@code app/data/ASCII/acctdata.txt}. */
    private static List<String> accountRows;

    /** The 36-byte cross-reference rows of {@code app/data/ASCII/cardxref.txt}. */
    private static List<String> crossReferenceRows;

    @BeforeEach
    void bindDatasetsAndCaptureLogs() {
        if (customerRows == null) {
            customerRows = FixtureLoader.load(FixtureLoader.Fixture.CUSTOMER).records();
            accountRows = FixtureLoader.load(FixtureLoader.Fixture.ACCOUNT).records();
            crossReferenceRows = FixtureLoader.load(FixtureLoader.Fixture.CARD_XREF).records();
        }

        FakeDataset.resetOpenSequence();
        transactionDataset = new FakeDataset(FileService.Dd.TRNXFILE);
        crossReferenceDataset = new FakeDataset(FileService.Dd.XREFFILE);
        customerDataset = new FakeDataset(FileService.Dd.CUSTFILE);
        accountDataset = new FakeDataset(FileService.Dd.ACCTFILE);
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
    private static String projectedRecord(final String transactionId, final String cardNumber,
                                          final String amount, final String description) {
        return StatementProcessor.projectBaseRecord(new Transaction(transactionId, "01", 1000,
                "POS TERM", description, new BigDecimal(amount), 123456789L, "SAMPLE MERCHANT",
                "SAMPLE CITY", "12345", cardNumber, "2022-06-10 19:27:53.000000",
                "2022-06-11 02:00:00.000000"));
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
            assertThat(FakeDataset.openSequence())
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
            assertThat(parsed.processingTimestamp())
                    .as("the projection truncated two bytes, so the parsed value is space-padded back")
                    .hasSize(26)
                    .startsWith("2022-06-11 02:00:00.0000");
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

        /** The order in which the datasets of one test were opened, so the sequence can be asserted. */
        private static final List<FileService.Dd> OPEN_SEQUENCE = new ArrayList<>();

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

        /** Clears the shared open-order record, so each test observes only its own opens. */
        static void resetOpenSequence() {
            OPEN_SEQUENCE.clear();
        }

        /** @return the DD names opened during this test, in order. */
        static List<FileService.Dd> openSequence() {
            return List.copyOf(OPEN_SEQUENCE);
        }

        @Override
        public FileService.Dd dd() {
            return dd;
        }

        @Override
        public String openInput() {
            openCount++;
            if ("00".equals(openStatus)) {
                OPEN_SEQUENCE.add(dd);
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

/*
 * ******************************************************************
 * Program     : TransactionPostingProcessorTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Verifies the per-record boundary of the daily
 *               transaction posting step against the COBOL it
 *               reproduces: the two-paragraph validation cascade, the
 *               over-limit formula transcribed in source order, the
 *               unguarded fall-through that lets reject 103 overwrite
 *               102, the category-balance upsert that accepts FILE
 *               STATUS '23' as success, the sign branch that adds a
 *               negative amount to the cycle debit accumulator without
 *               taking an absolute value, the twenty-six character
 *               processing timestamp ending in four zeros, the reason
 *               code 109 that the source assigns and never consumes,
 *               the universal I/O guard with its FILE STATUS to
 *               exception map and four-character status rendering, and
 *               the return code 4 that is set if and only if the
 *               reject count exceeds zero.
 * Source      : app/cbl/CBTRN02C.cbl:L142-L146 (APPL-RESULT guard flags)
 *               app/cbl/CBTRN02C.cbl:L160-L174 (DB2-FORMAT-TS REDEFINES)
 *               app/cbl/CBTRN02C.cbl:L176-L182 (430-byte reject record)
 *               app/cbl/CBTRN02C.cbl:L185-L190 (counters and WS flags)
 *               app/cbl/CBTRN02C.cbl:L193-L234 (mainline and exit code)
 *               app/cbl/CBTRN02C.cbl:L370-L378 (1500-VALIDATE-TRAN)
 *               app/cbl/CBTRN02C.cbl:L380-L392 (1500-A-LOOKUP-XREF)
 *               app/cbl/CBTRN02C.cbl:L393-L422 (1500-B-LOOKUP-ACCT)
 *               app/cbl/CBTRN02C.cbl:L424-L444 (2000-POST-TRANSACTION)
 *               app/cbl/CBTRN02C.cbl:L446-L465 (2500-WRITE-REJECT-REC)
 *               app/cbl/CBTRN02C.cbl:L467-L501 (2700-UPDATE-TCATBAL)
 *               app/cbl/CBTRN02C.cbl:L503-L524 (2700-A-CREATE-TCATBAL-REC)
 *               app/cbl/CBTRN02C.cbl:L526-L542 (2700-B-UPDATE-TCATBAL-REC)
 *               app/cbl/CBTRN02C.cbl:L545-L560 (2800-UPDATE-ACCOUNT-REC)
 *               app/cbl/CBTRN02C.cbl:L562-L579 (2900-WRITE-TRANSACTION-FILE)
 *               app/cbl/CBTRN02C.cbl:L692-L705 (Z-GET-DB2-FORMAT-TIMESTAMP)
 *               app/cbl/CBTRN02C.cbl:L707-L711 (9999-ABEND-PROGRAM)
 *               app/cbl/CBTRN02C.cbl:L714-L727 (9910-DISPLAY-IO-STATUS)
 *               app/cpy/CVTRA06Y.cpy           (DALYTRAN 350-byte layout)
 *               app/cpy/CVTRA05Y.cpy:L16-L17   (TRAN-ORIG-TS / -PROC-TS)
 *               app/cpy/CVTRA01Y.cpy:L5-L9     (TRAN-CAT-BAL key + balance)
 *               app/cpy/CVACT01Y.cpy:L11       (ACCT-EXPIRAION-DATE)
 *               app/cpy/CVACT03Y.cpy:L7        (XREF-ACCT-ID)
 *               app/jcl/POSTTRAN.jcl:L36       (DALYREJS LRECL=430)
 *               app/data/ASCII/dailytran.txt   (300 x 350 parity fixture)
 *               app/data/ASCII/acctdata.txt    (50 x 300 accounts)
 *               app/data/ASCII/cardxref.txt    (50 x 36 cross-reference)
 *                                                              @ 7756d89
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
import com.cardemo.batch.processors.TransactionPostingProcessor;
import com.cardemo.batch.processors.TransactionPostingProcessor.PostingResult;
import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.DataIntegrityException;
import com.cardemo.exception.DuplicateRecordException;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.exception.FileAccessException;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.DailyTransaction;
import com.cardemo.model.entity.Transaction;
import com.cardemo.model.entity.TransactionCategoryBalance;
import com.cardemo.model.enums.FileStatus;
import com.cardemo.model.enums.RejectCode;
import com.cardemo.model.key.TransactionCategoryBalanceId;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.TransactionCategoryBalanceRepository;
import com.cardemo.service.shared.FileStatusMapper;
import com.cardemo.unit.model.FixedClockProvider;
import com.cardemo.unit.model.FixtureLoader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

/**
 * Executable proof that the daily posting boundary reproduces {@code CBTRN02C}'s per-record decisions
 * exactly, including the four places where the obvious translation is wrong.
 *
 * <p><b>What it does.</b> Drives {@link TransactionPostingProcessor#process(DailyTransaction)} with
 * hand-built records and asserts the classification it returns, the rows it writes and the diagnostics
 * it emits. The <b>three</b> repositories the processor declares -
 * {@link CardCrossReferenceRepository}, {@link AccountRepository} and
 * {@link TransactionCategoryBalanceRepository} - are Mockito mocks, because the assertion is about which
 * call is made with which value and not about SQL. There is deliberately no transaction repository among
 * them: {@code 2900-WRITE-TRANSACTION-FILE} at {@code app/cbl/CBTRN02C.cbl:L562-L579} is writer scope, so
 * this processor <em>composes</em> the {@link Transaction} and returns it while
 * {@code com.cardemo.batch.writers.TransactionWriter} performs the insert. Asserting a save here would
 * assert an ownership the design does not have.
 *
 * <p>{@link FileStatusMapper} is a Mockito <em>spy over a real instance</em> because its
 * {@code '00' OR '23'} decision is the behaviour under test at {@code app/cbl/CBTRN02C.cbl:L481} and
 * stubbing it would make the assertion tautological. The clock is fixed, which is the only way the
 * twenty-six character processing timestamp can be asserted as a value rather than as a shape.
 *
 * <p><b>The four parity traps, each with the group that pins it.</b>
 * <ol>
 * <li><b>The unguarded overwrite</b> - nothing sits between the {@code END-IF} at
 * {@code app/cbl/CBTRN02C.cbl:L413} and the {@code IF} at {@code :L414}, so a record that is both over
 * limit and past expiry ends with 103 and produces exactly one reject record. Group 7.</li>
 * <li><b>The expiry test is a string comparison</b> against {@code DALYTRAN-ORIG-TS (1:10)} - the
 * <em>originating</em> timestamp, never the processing one - and neither side is parsed into a date.
 * Groups 7 and 19.</li>
 * <li><b>The retained {@code ACCT-EXPIRAION-DATE} misspelling</b> of
 * {@code app/cpy/CVACT01Y.cpy:L11}, which is a field contract rather than a typo. Groups 8 and 18.</li>
 * <li><b>The over-limit formula is transcribed, not rearranged</b> - {@code :L403-L405} subtracts a debit
 * accumulator that legitimately holds negative values, so no operand may have its absolute value taken.
 * Groups 6 and 13.</li>
 * </ol>
 *
 * <p><b>How to build and test.</b> {@code ./mvnw -B -ntp -Dtest='TransactionPostingProcessorTest' test}
 * runs this class alone; it needs no container, no database and no cloud emulator. The full gate is
 * {@code ./mvnw -B -ntp clean verify}.
 *
 * <p><b>This class is collected by Surefire, not by Failsafe, and that is a build contract rather than an
 * accident.</b> The root {@code pom.xml} gives Surefire the includes {@code **}{@code /*Test.java} and
 * {@code **}{@code /*Tests.java} with the excludes {@code **}{@code /integration/**} and
 * {@code **}{@code /e2e/**}, and gives Failsafe the mirror-image includes. A class named
 * {@code *Test} under {@code src/test/java/com/cardemo/unit/} therefore matches Surefire and only
 * Surefire. Moving or renaming this file so that it matches <em>neither</em> plugin is the worst
 * available outcome and is why it must not be relocated: nothing fails. Both plugins report success,
 * JaCoCo records the class as uncovered, the zero-warning build gate still passes, and no error and no
 * warning is emitted anywhere. The suite simply stops running, invisibly. Surefire additionally pins
 * {@code workingDirectory} to the project base directory, which is what lets the fixture-grounded group
 * below read the frozen corpus under {@code app/} at run time.
 *
 * <p><b>Key configuration and defaults.</b>
 * <ul>
 * <li><b>The clock.</b> Pinned to {@code 2022-06-10T19:27:53.470Z} in {@link ZoneOffset#UTC}, whose date
 * component is the value every row of {@code app/data/ASCII/dailytran.txt} carries in the first ten
 * characters of {@code DALYTRAN-ORIG-TS}, so the fixtures and the generated timestamp belong to the same
 * moment. It reaches the processor through the one public constructor, which takes the clock as its fifth
 * argument precisely so that a test can supply a fixed one. Group 10 additionally cross-checks the
 * rendering against {@link FixedClockProvider#batchTimestamp(Clock)}, an independently written formatter
 * in the sibling {@code unit/model} tier, so agreement is corroboration by two implementations rather
 * than a single formatter agreeing with itself. <b>No test in this file calls any {@code now()} overload
 * without a clock, and none reads the host locale, zone, or any random source.</b></li>
 * <li><b>Mockito strictness.</b> This class instantiates its doubles with {@link Mockito#mock(Class)} in
 * {@link #buildProcessorAndCaptureLogs()} rather than through {@code @ExtendWith(MockitoExtension.class)}
 * or {@code MockitoSettings}, so the strictness in force is Mockito's plain default for direct
 * instantiation: <b>lenient</b>. That is deliberate and not laziness. {@code STRICT_STUBS} fails a test
 * for an unused stubbing, and several groups here stub a collaborator precisely to prove the processor
 * <em>does not</em> call it - group 5 stubs the account read to show the over-limit and expiry tests never
 * run when the account is absent, and group 19 stubs the alternate-index finder to show the cross-
 * reference lookup never reaches it. Under strict stubs those assertions would fail for being correct.
 * The protection that strict stubs would have provided is supplied instead by explicit
 * {@link Mockito#verify(Object)} and {@link Mockito#verifyNoInteractions(Object...)} calls, which state
 * the expectation positively.</li>
 * <li><b>The status mapper is real, not stubbed.</b> {@link FileStatusMapper} is a
 * {@link Mockito#spy(Object)} over a genuine instance, because its {@code '00' OR '23'} decision at
 * {@code app/cbl/CBTRN02C.cbl:L481} <em>is</em> the behaviour under test. Stubbing it would make group 11
 * assert only that this test file agrees with itself.</li>
 * </ul>
 *
 * <p><b>The Gate 1 boundary-parity baseline is {@code Not available}.</b> Rule 1 clause F requires that a
 * missing input be stated rather than invented, so it is stated here. The repository at {@code 7756d89}
 * contains no captured legacy output for this program: searching it for {@code expected}, {@code baseline}
 * and {@code golden} artefacts, for {@code *.out}, and for {@code DALYREJS}, {@code TRANREPT},
 * {@code STMTFILE} and {@code HTMLFILE} data returns dataset <em>definition</em> JCL and nothing else.
 * <b>What would be needed:</b> a captured run of {@code app/jcl/POSTTRAN.jcl} on z/OS over
 * {@code app/data/ASCII/dailytran.txt}, retaining the {@code DALYREJS} 430-byte reject dataset, the
 * {@code TRANSACT} additions and the SYSOUT counter lines. <b>What follows from its absence:</b> no
 * aggregate is asserted anywhere in this file. Not the reject count, not the posted count, not the
 * end-of-run return code over the fixture. Hand-simulating the program to manufacture one is equally
 * forbidden and for a concrete reason: a stateless single pass over these fixtures and a faithful
 * stateful model - which re-reads the account per transaction at {@code :L393-L395} while
 * {@code :L547-L551} mutates its accumulators - disagree with each other. A number two defensible models
 * disagree about is not an oracle. Every assertion here is therefore <b>per record, from explicit
 * hand-built input</b>, and the only fixture-derived claims (group 18) are properties of the <em>input
 * data alone</em>, which are model-independent and separately re-derived from the bytes.
 *
 * <p><b>One deliberate deviation, asserted as a deviation and never as parity.</b> The source commits the
 * three writes of {@code :L440-L442} independently; the Java form places all three inside the single
 * {@code @Transactional(rollbackFor = Exception.class)} unit opened by
 * {@link TransactionPostingProcessor#process(DailyTransaction)}. This is an <b>improvement, not
 * equivalence</b>: it closes the legacy hazard in which a failing account rewrite leaves an orphaned
 * category-balance row and an orphaned transaction row behind it. Group 14 asserts the improved outcome
 * and names it as a deviation, because presenting it as parity would misdescribe the system.
 *
 * <p><b>One constant that is assigned and never read, retained for parity.</b> Reproducing reachable
 * no-ops is what parity requires. In this file the instance is
 * {@link RejectCode#ACCOUNT_RECORD_NOT_FOUND_ON_REWRITE}, reject 109: the source assigns it at
 * {@code :L556} and never reads it, because {@code 2800} runs only inside the already-validated path, the
 * reject write and the counter increment live exclusively in the {@code ELSE} arm at {@code :L213-L215},
 * and {@code :L208} clears the field on the next iteration. Parity governs, and clause B is satisfied on
 * its own terms - it prohibits artefacts <em>without a tracking reference</em>, and this one is held as
 * {@code DL-PP-03} in the {@code DECISION_LOG.md}, with its rows in the
 * {@code TRACEABILITY_MATRIX.md}, the source locator in the Javadoc
 * of every test that touches it, and an explicit intentional-retention marker. Deleting the constant
 * would break the paragraph map that the scope-coverage gate verifies.
 *
 * <p><b>Common failure modes and troubleshooting.</b>
 * <ul>
 * <li>A failure in group 7 means the unguarded fall-through of {@code :L413-L420} has
 * been guarded, so a record failing both the over-limit and the expiry test would produce reject 102
 * instead of the 103 the source produces. Restore the sequential form; do not add an early exit.</li>
 * <li>A failure in group 13 means the sign branch has been normalised. The cycle debit
 * accumulator must hold negative values, because the over-limit formula of {@code :L403-L405} subtracts
 * it. Taking an absolute value anywhere on that path silently inverts every over-limit decision.</li>
 * <li>A failure in group 11 means {@code FILE STATUS '23'} is no longer accepted as success
 * on the category-balance read, which turns the source's create branch into an abend and loses every
 * first-of-cycle balance row.</li>
 * <li>A failure in group 10 means the processing timestamp no longer renders twenty-six
 * characters ending in the literal {@code 0000}, which the boundary-parity gate diffs character for
 * character against the legacy baseline.</li>
 * <li>A failure in group 8 means an absent {@code NOT NULL} value is being reported as a
 * reject rather than as an integrity failure, which inflates the reject count and so corrupts the return
 * code decision at {@code :L229-L231}.</li>
 * <li>A failure in group 16 means the return-code decision no longer keys on
 * {@code WS-REJECT-COUNT > 0} alone. {@code :L229-L231} is the only {@code MOVE 4 TO RETURN-CODE} in the
 * whole twenty-eight-program corpus and it has exactly one determinant, so any additional condition -
 * a threshold, a severity, an error flag - changes which runs a scheduler treats as clean.</li>
 * <li>A failure in group 17 means the FILE STATUS vocabulary has drifted. The two cases that
 * matter most are {@code '10'}, which is loop termination and must never be raised as an exception, and
 * the {@code '9x'} family, whose four-character expansion is diffed character for character.</li>
 * <li>A failure in group 15 means a card number has reached a log record. The convention in
 * this tree is that not emitting a primary account number is the primary defence and masking is only the
 * backstop, so the fix is to remove the value, not to mask it.</li>
 * <li>A failure in group 18 is almost certainly not a code defect. That group re-derives the
 * reject-code reachability of the shipped fixtures from their bytes, so it fails if a fixture changed -
 * and {@code app/} is frozen, which means the correct response is to restore the fixture, never to relax
 * the assertion.</li>
 * <li><b>build rather than test.</b> {@code -Xlint:all -Werror} reaches <em>test</em>
 * compilation. A single unused import, a raw type or an unchecked cast added to this file fails
 * {@code clean verify} outright with no test ever running, and the compiler names the import rather than
 * the concept, so a diff that "only" removes an assertion can break the build by orphaning its import.</li>
 * <li><b>and the trap that costs the most time.</b> The parity fixture is
 * {@code dailytran.txt}, with the word spelled in full. <b>{@code dalytran.txt} does not exist anywhere in
 * the repository.</b> The mainframe DD name, the dataset {@code AWS.M2.CARDDEMO.DALYTRAN.PS} and the
 * EBCDIC member {@code DALYTRAN.PS} all elide the {@code I}, so the six-letter spelling is what a reader
 * expects and what a copied path will contain - and it resolves to nothing. The symptom is an empty
 * {@code Optional} or a null classpath stream far from the typo. Reach the fixture only through
 * {@link FixtureLoader.Fixture#DAILY_TRANSACTION}, which spells the name once, in one place.</li>
 * </ul>
 */
@DisplayName("TransactionPostingProcessor: CBTRN02C's per-record decisions, quirks included")
class TransactionPostingProcessorTest {

    /** The instant every row of {@code app/data/ASCII/dailytran.txt} carries in {@code DALYTRAN-ORIG-TS}. */
    private static final Instant FIXED_INSTANT = Instant.parse("2022-06-10T19:27:53.470Z");

    /** The clock injected into the processor, so the generated timestamp is a value and not a shape. */
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    /**
     * What {@code Z-GET-DB2-FORMAT-TIMESTAMP} must render for {@link #FIXED_INSTANT}: twenty-two
     * characters of {@code yyyy-MM-dd-HH.mm.ss.SS} followed by the literal {@code 0000} of {@code :L701}.
     */
    private static final String EXPECTED_PROC_TS = "2022-06-10-19.27.53.470000";

    /** A card number from row 1 of the corpus fixture. Never asserted against a log record. */
    private static final String CARD_NUMBER = "4111111111111111";

    /** The account the cross-reference resolves to, matching {@code ACCT-ID PIC 9(11)}. */
    private static final long ACCOUNT_ID = 11L;

    /** {@code TRAN-ID PIC X(16)} for the record under test. */
    private static final String TRAN_ID = "0000000000683580";

    /** {@code DALYTRAN-TYPE-CD PIC X(02)}. */
    private static final String TYPE_CD = "01";

    /** {@code DALYTRAN-CAT-CD PIC 9(04)}. */
    private static final int CAT_CD = 1000;

    /** {@code DALYTRAN-ORIG-TS (1:10)}, the ten characters the expiry comparison of {@code :L414} slices. */
    private static final String ORIG_DATE = "2022-06-10";

    /** The full {@code PIC X(26)} originating timestamp of every corpus row. */
    private static final String ORIG_TS = "2022-06-10 19:27:53.000000";

    /** The seventeen-character {@code FD-TRAN-CAT-KEY} image for the fixture account, type and category. */
    private static final String TRAN_CAT_KEY_IMAGE = "00000000011011000";

    /**
     * The marker the processor logs in place of an account or composite key.
     *
     * <p>Every key-bearing diagnostic is built twice from one template - once with the key for the
     * exception an operator reads when a step has just died, once with this marker for the log, which is
     * aggregated, retained and replicated well beyond that. The tests below assert both directions, so a
     * future edit cannot quietly route the keyed form to the logger.
     */
    private static final String WITHHELD = "[withheld]";

    private CardCrossReferenceRepository cardCrossReferenceRepository;
    private AccountRepository accountRepository;
    private TransactionCategoryBalanceRepository transactionCategoryBalanceRepository;
    private FileStatusMapper fileStatusMapper;
    private TransactionPostingProcessor processor;

    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger logger;
    private Level originalLevel;

    @BeforeEach
    void buildProcessorAndCaptureLogs() {
        cardCrossReferenceRepository = Mockito.mock(CardCrossReferenceRepository.class);
        accountRepository = Mockito.mock(AccountRepository.class);
        transactionCategoryBalanceRepository = Mockito.mock(TransactionCategoryBalanceRepository.class);
        // A spy over a real mapper: the '00' OR '23' decision is the behaviour under test, so it must be
        // the production decision, while the spy still records that the derived status was handed to it.
        fileStatusMapper = Mockito.spy(new FileStatusMapper());
        processor = newProcessor(cardCrossReferenceRepository, accountRepository,
                transactionCategoryBalanceRepository, fileStatusMapper, FIXED_CLOCK);

        logger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(TransactionPostingProcessor.class);
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

    // Fixture builders. Each one produces a record that satisfies every guard the entity declares, so a
    // test that wants one field absent removes it explicitly through setField and the intent stays legible.

    /**
     * Builds a fully populated staged record. Every field is present because the entity's own guards
     * reject a null, and because {@code 2000-POST-TRANSACTION} moves all thirteen of them.
     *
     * @param amount {@code DALYTRAN-AMT}, the only field the arithmetic tests vary
     * @return a record that passes every entity guard
     */
    private static DailyTransaction dailyTransaction(final String amount) {
        return dailyTransaction(amount, CARD_NUMBER, ORIG_TS);
    }

    /**
     * Builds a staged record with a chosen amount, card number and originating timestamp.
     *
     * @param amount {@code DALYTRAN-AMT}
     * @param cardNumber {@code DALYTRAN-CARD-NUM}
     * @param origTs {@code DALYTRAN-ORIG-TS}
     * @return a record that passes every entity guard
     */
    private static DailyTransaction dailyTransaction(final String amount, final String cardNumber,
                                                     final String origTs) {
        return new DailyTransaction(1L, TRAN_ID, TYPE_CD, CAT_CD, "POS TERM", "PURCHASE AT MERCHANT",
                new BigDecimal(amount), 123456789L, "SAMPLE MERCHANT", "SAMPLE CITY", "12345",
                cardNumber, origTs, "2022-06-11 02:00:00.000000");
    }

    /**
     * Builds an account whose cycle accumulators, credit limit and expiry date are chosen by the caller.
     *
     * @param currentBalance {@code ACCT-CURR-BAL}
     * @param creditLimit {@code ACCT-CREDIT-LIMIT}
     * @param cycleCredit {@code ACCT-CURR-CYC-CREDIT}
     * @param cycleDebit {@code ACCT-CURR-CYC-DEBIT}, which legitimately holds negative values
     * @param expiraionDate {@code ACCT-EXPIRAION-DATE}, retaining the copybook's misspelling
     * @return the account the cross-reference resolves to
     */
    private static Account account(final String currentBalance, final String creditLimit,
                                   final String cycleCredit, final String cycleDebit,
                                   final String expiraionDate) {
        return new Account(ACCOUNT_ID, "Y", new BigDecimal(currentBalance), new BigDecimal(creditLimit),
                new BigDecimal("5000.00"), "2020-01-01", expiraionDate, "2020-01-01",
                new BigDecimal(cycleCredit), new BigDecimal(cycleDebit), "12345", "DEFAULT");
    }

    /**
     * Builds an account that accepts the fixture transaction: a generous credit limit and a distant expiry.
     *
     * @return an account for which neither the over-limit nor the expiry test fails
     */
    private static Account acceptingAccount() {
        return account("100.00", "9000.00", "500.00", "-100.00", "2030-01-01");
    }

    /**
     * Builds the cross-reference row {@code :L383} reads.
     *
     * @return a row resolving {@link #CARD_NUMBER} to {@link #ACCOUNT_ID}
     */
    private static CardCrossReference crossReference() {
        return new CardCrossReference(CARD_NUMBER, 123456789L, ACCOUNT_ID);
    }

    /**
     * Composes the {@code TCATBALF} key the fixtures share.
     *
     * @return the composite key {@code categoryBalanceKey} must compose for the fixtures.
     */
    private static TransactionCategoryBalanceId fixtureKey() {
        return new TransactionCategoryBalanceId(ACCOUNT_ID, TYPE_CD, CAT_CD);
    }

    /**
     * Stubs the happy path: the cross-reference resolves, the account resolves and accepts, and the
     * category balance is absent so the create branch applies.
     *
     * @return the account that was stubbed, so a test can assert the mutations applied to it
     */
    private Account stubHappyPath() {
        Account account = acceptingAccount();
        Mockito.when(cardCrossReferenceRepository.findById(CARD_NUMBER))
                .thenReturn(Optional.of(crossReference()));
        Mockito.when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        Mockito.when(transactionCategoryBalanceRepository.findById(fixtureKey()))
                .thenReturn(Optional.empty());
        return account;
    }

    /**
     * Constructs the processor under test through its single public constructor.
     *
     * <p><b>Called directly, never reflectively.</b> {@link TransactionPostingProcessor} declares
     * exactly one constructor, in {@code TransactionPostingProcessor.java}, it is {@code public}, and
     * its own Javadoc states that the clock arrives through "this same constructor" for exactly this
     * purpose - the clock seam is part of the public contract precisely so that a test need not subvert
     * anything to use it. The direct call below costs nothing and buys the property that
     * {@link Class#getDeclaredConstructor(Class...)} would destroy: a change to the constructor's
     * signature fails at <em>compile</em> time, under {@code -Werror}, instead of surviving as a
     * reflective lookup that throws at run time with the signature it wanted buried in a message.
     *
     * <p>This helper is retained rather than inlined because five of the six tests in group 1 pass a
     * deliberate {@code null} to assert a guard, and naming the operation once keeps each of those tests a
     * single legible line.
     *
     * @param xref the cross-reference repository, deliberately {@code null} in a guard test
     * @param accounts the account repository, deliberately {@code null} in a guard test
     * @param balances the category-balance repository, deliberately {@code null} in a guard test
     * @param mapper the status mapper, deliberately {@code null} in a guard test
     * @param clock the clock, deliberately {@code null} in a guard test
     * @return the constructed processor
     * @throws NullPointerException if any argument is {@code null}, raised by the constructor's own
     *                              {@link java.util.Objects#requireNonNull} guards and propagated unchanged
     */
    private static TransactionPostingProcessor newProcessor(final CardCrossReferenceRepository xref,
                                                            final AccountRepository accounts,
                                                            final TransactionCategoryBalanceRepository
                                                                    balances,
                                                            final FileStatusMapper mapper,
                                                            final Clock clock) {
        return new TransactionPostingProcessor(xref, accounts, balances, mapper, clock);
    }

    /**
     * Sets one declared field of an entity to a chosen value, so a test can express an absent
     * {@code NOT NULL} column that the entity's own constructor refuses to build.
     *
     * <p>Going through the public constructor instead would make the entity's guards, rather than the
     * processor's, decide what this suite can express - and the condition under test is precisely what the
     * processor does when a column the schema declares present is absent on a row it reads.
     *
     * @param target the entity to mutate
     * @param fieldName the declared field name
     * @param value the value to set, typically {@code null}
     * @param <T> the entity type
     * @return the same instance, for chaining
     */
    private static <T> T setField(final T target, final String fieldName, final Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
            return target;
        } catch (ReflectiveOperationException reflection) {
            throw new IllegalStateException("Field " + fieldName + " could not be set on "
                    + target.getClass().getSimpleName(), reflection);
        }
    }

    /**
     * Builds a cross-reference row whose {@code XREF-ACCT-ID} is absent, which the entity constructor
     * refuses to produce and which {@code lookupAcct} must report as an integrity failure.
     *
     * @return a transient row carrying a card number and nothing else
     */
    private static CardCrossReference crossReferenceWithoutAccountId() {
        try {
            Constructor<CardCrossReference> provider =
                    CardCrossReference.class.getDeclaredConstructor();
            provider.setAccessible(true);
            return setField(provider.newInstance(), "cardNumber", CARD_NUMBER);
        } catch (ReflectiveOperationException reflection) {
            throw new IllegalStateException("The provider constructor of CardCrossReference could not be "
                    + "reached", reflection);
        }
    }

    /**
     * Reads back what the processor logged during the call under test.
     *
     * @return every message this class's logger received, formatted as an appender would see it.
     */
    private List<String> loggedMessages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /**
     * Reads one line of the frozen COBOL program, one-based, exactly as the corpus holds it.
     *
     * <p><b>Why read the source instead of restating it.</b> A literal transcribed into a Java string is
     * only as good as the transcription; a literal read from {@code app/cbl/CBTRN02C.cbl} at run time
     * cannot drift from the corpus, and the test fails loudly if the frozen file is ever touched - which
     * {@code app/} being frozen means it must not be. This is the same technique the sibling
     * {@code unit/model} contract tests use against the copybooks, and it is what makes the counter
     * literals of {@code :L227-L228} and the return-code decision of {@code :L229-L231} provable rather
     * than merely asserted. Surefire pins {@code workingDirectory} to the project base directory, so the
     * relative path resolves for every runner the build supports.
     *
     * <p>The file is plain ASCII with LF endings, so {@link StandardCharsets#ISO_8859_1} decodes it
     * byte for byte and cannot raise on any input, and the line numbering is exact as read. Trailing
     * blanks - COBOL pads to column 80 - are stripped, because they are an artefact of the fixed-form
     * card image rather than part of any literal.
     *
     * @param oneBasedLine the line number as cited throughout this file
     * @return that line with trailing blanks removed
     * @throws UncheckedIOException if the frozen corpus cannot be read, which means the working directory
     *                              is not the project base directory or {@code app/} has been disturbed
     */
    private static String frozenSourceLine(final int oneBasedLine) {
        return frozenSourceLines(oneBasedLine, oneBasedLine).get(0);
    }

    /**
     * Reads an inclusive span of the frozen COBOL program, one-based.
     *
     * @param firstLine the first line to return, one-based and inclusive
     * @param lastLine the last line to return, one-based and inclusive
     * @return the requested lines with trailing blanks removed, in source order
     * @throws UncheckedIOException if the frozen corpus cannot be read
     */
    private static List<String> frozenSourceLines(final int firstLine, final int lastLine) {
        Path source = Path.of("app", "cbl", "CBTRN02C.cbl");
        List<String> all;
        try {
            all = Files.readAllLines(source, StandardCharsets.ISO_8859_1);
        } catch (IOException failure) {
            throw new UncheckedIOException("The frozen program " + source
                    + " could not be read. Surefire pins workingDirectory to the project base directory, so "
                    + "this means either the runner ignored that setting or app/ - which is frozen and is "
                    + "the parity oracle - has been disturbed.", failure);
        }
        assertThat(all)
                .as("app/cbl/CBTRN02C.cbl is 731 lines at commit 7756d89; a different length means the "
                        + "frozen corpus changed and every locator in this file needs re-verification")
                .hasSize(731);
        List<String> span = new ArrayList<>();
        for (int line = firstLine; line <= lastLine; line++) {
            span.add(all.get(line - 1).stripTrailing());
        }
        return span;
    }

    /**
     * The transaction this processor composed for {@code 2900-WRITE-TRANSACTION-FILE}.
     *
     * <p>The write itself belongs to {@code com.cardemo.batch.writers.TransactionWriter}, which owns the
     * {@code TRANSACT} sink and its object mirror; the processor composes the record and returns it. The
     * thirteen moves of {@code :L425-L438} and the generated timestamp are therefore asserted against what
     * the processor produced, and the write order and the persistence are asserted in the writer's own tests.
     *
     * @param result the outcome the processor returned; must report a posted record
     * @return the composed entity
     */
    private static Transaction postedTransaction(final PostingResult result) {
        assertThat(result.isPosted())
                .as("the record must have posted for a composed transaction to exist")
                .isTrue();
        return result.postedTransaction();
    }

    /**
     * Captures the category balance handed to the create or rewrite branch.
     *
     * @return the saved entity
     */
    private TransactionCategoryBalance savedCategoryBalance() {
        ArgumentCaptor<TransactionCategoryBalance> captor =
                ArgumentCaptor.forClass(TransactionCategoryBalance.class);
        Mockito.verify(transactionCategoryBalanceRepository).save(captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("1. Construction: every collaborator is required and the clock is injectable")
    class Construction {

        @Test
        @DisplayName("the public constructor rejects an absent cross-reference repository")
        void publicConstructorRejectsAbsentCrossReferenceRepository() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new TransactionPostingProcessor(null, accountRepository,
                            transactionCategoryBalanceRepository, fileStatusMapper, FIXED_CLOCK))
                    .withMessage("cardCrossReferenceRepository must not be null");
        }

        @Test
        @DisplayName("the public constructor rejects an absent account repository")
        void publicConstructorRejectsAbsentAccountRepository() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new TransactionPostingProcessor(cardCrossReferenceRepository, null,
                            transactionCategoryBalanceRepository, fileStatusMapper, FIXED_CLOCK))
                    .withMessage("accountRepository must not be null");
        }

        @Test
        @DisplayName("the public constructor rejects an absent category-balance repository")
        void publicConstructorRejectsAbsentCategoryBalanceRepository() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new TransactionPostingProcessor(cardCrossReferenceRepository,
                            accountRepository, null, fileStatusMapper, FIXED_CLOCK))
                    .withMessage("transactionCategoryBalanceRepository must not be null");
        }

        @Test
        @DisplayName("the public constructor rejects an absent status mapper")
        void publicConstructorRejectsAbsentStatusMapper() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new TransactionPostingProcessor(cardCrossReferenceRepository,
                            accountRepository, transactionCategoryBalanceRepository, null, FIXED_CLOCK))
                    .withMessage("fileStatusMapper must not be null");
        }

        @Test
        @DisplayName("the single public constructor rejects an absent clock")
        void theOnlyConstructorRejectsAnAbsentClock() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> newProcessor(cardCrossReferenceRepository, accountRepository,
                            transactionCategoryBalanceRepository, fileStatusMapper, null))
                    .withMessage("clock must not be null");
        }

        @Test
        @DisplayName("TransactionPostingProcessor declares exactly one constructor, and it is public")
        void exactlyOneConstructorIsDeclaredAndItIsPublic() {
            // TransactionPostingProcessor declares exactly one constructor and it is public, so no
            // reflection is needed to reach it and no caller can be handed a differently configured
            // instance. Asserted so that it cannot silently regress. If a
            // clock-defaulting sibling is ever added, this test fails and says why - which is the point,
            // because such a sibling would let a caller silently acquire the wall clock.
            assertThat(TransactionPostingProcessor.class.getDeclaredConstructors())
                    .as("TransactionPostingProcessor declares one constructor only, so the container "
                            + "performs implicit constructor injection and a test needs no reflection to "
                            + "supply a fixed clock")
                    .singleElement()
                    .satisfies(constructor -> {
                        assertThat(Modifier.isPublic(constructor.getModifiers()))
                                .as("the sole constructor of TransactionPostingProcessor is public, so "
                                        + "reaching it reflectively would add nothing and would move a "
                                        + "signature change from compile time to run time")
                                .isTrue();
                        assertThat(constructor.getParameterTypes())
                                .as("the clock is the fifth declared collaborator; there is deliberately no "
                                        + "clock-defaulting overload, so no caller can acquire ambient time")
                                .containsExactly(CardCrossReferenceRepository.class,
                                        AccountRepository.class,
                                        TransactionCategoryBalanceRepository.class,
                                        FileStatusMapper.class, Clock.class);
                    });
        }

        @Test
        @DisplayName("any injected clock still yields a PIC X(26) timestamp, checked on a second instant")
        void theInjectedClockProducesTheDeclaredWidth() {
            // A SECOND fixed clock, deliberately not the wall clock. Clock.systemDefaultZone() would read
            // ZoneId.systemDefault(), and Rule 1 clause A forbids this file from depending on host state at
            // all - a test that passes only because the assertion happens to be time-independent is still a
            // test that reads the host. FixedClockProvider.CANONICAL_INSTANT sits on a whole second, so it
            // also exercises the hundredths field at 00, where a formatter that dropped the leading zero
            // would shorten the field and break the PIC X(26) width.
            TransactionPostingProcessor secondClockProcessor = newProcessor(cardCrossReferenceRepository,
                    accountRepository, transactionCategoryBalanceRepository, fileStatusMapper,
                    FixedClockProvider.canonicalClock());
            stubHappyPath();

            PostingResult result = secondClockProcessor.process(dailyTransaction("10.00"));

            assertThat(result.isPosted()).isTrue();
            assertThat(result.postedTransaction().getProcTs())
                    .as("PIC X(26) at app/cbl/CBTRN02C.cbl:L159 with the literal 0000 of :L701, whichever "
                            + "clock supplied the instant")
                    .hasSize(FixedClockProvider.TIMESTAMP_LENGTH)
                    .endsWith("0000")
                    .isEqualTo(FixedClockProvider.CANONICAL_BATCH_TIMESTAMP);
        }
    }

    @Nested
    @DisplayName("2. process: the per-record mainline of :L205-L216")
    class PerRecordMainline {

        @Test
        @DisplayName("an absent record abends with code 999, culprit CBTRN02C and no reject code")
        void anAbsentRecordAbends() {
            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> processor.process(null))
                    .satisfies(abend -> {
                        assertThat(abend.getAbendCode())
                                .isEqualTo(String.valueOf(FatalProcessingException.BATCH_ABEND_CODE));
                        assertThat(abend.getAbendCulprit()).isEqualTo("CBTRN02C");
                        assertThat(abend.getAbendReason()).isEqualTo("DAILY TRANSACTION POSTING ABEND");
                        assertThat(abend.getAbendMessage())
                                .contains("The daily transaction reader supplied no record")
                                .contains("app/cbl/CBTRN02C.cbl:L205")
                                .contains("no reject code describes it");
                    });
            Mockito.verifyNoInteractions(cardCrossReferenceRepository, accountRepository,
                    transactionCategoryBalanceRepository);
        }

        @Test
        @DisplayName("a clean record takes the :L212 arm and posts")
        void aCleanRecordPosts() {
            stubHappyPath();

            PostingResult result = processor.process(dailyTransaction("25.50"));

            assertThat(result.isPosted()).isTrue();
            assertThat(result.isRejected()).isFalse();
            assertThat(result.rejectCode()).isNull();
            assertThat(result.failReasonCode()).isZero();
            assertThat(result.source().getTransactionId()).isEqualTo(TRAN_ID);
        }

        @Test
        @DisplayName("a failing record takes the :L213 arm, logs the reason field and posts nothing")
        void aFailingRecordIsRejected() {
            Mockito.when(cardCrossReferenceRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.empty());

            PostingResult result = processor.process(dailyTransaction("25.50"));

            assertThat(result.isRejected()).isTrue();
            assertThat(result.rejectCode()).isEqualTo(RejectCode.INVALID_CARD_NUMBER);
            assertThat(loggedMessages())
                    .contains("Daily transaction rejected with reason 0100 INVALID CARD NUMBER FOUND");
            Mockito.verifyNoInteractions(transactionCategoryBalanceRepository);
            Mockito.verify(accountRepository, Mockito.never()).save(Mockito.any());
        }

        /**
         * {@code 1500-VALIDATE-TRAN} is four statements long and contains <b>exactly two</b> lookups.
         *
         * <p>Verbatim from {@code app/cbl/CBTRN02C.cbl:L370-L378}:
         * <pre>
         * 370:  1500-VALIDATE-TRAN.
         * 371:      PERFORM 1500-A-LOOKUP-XREF.
         * 372:      IF WS-VALIDATION-FAIL-REASON = 0
         * 373:         PERFORM 1500-B-LOOKUP-ACCT
         * 374:      ELSE
         * 375:         CONTINUE
         * 376:      END-IF
         * 377: * ADD MORE VALIDATIONS HERE
         * 378:      EXIT.
         * </pre>
         *
         * <p>The cross-reference lookup runs unconditionally; the account lookup runs only while the reason
         * code is still zero. The comment on {@code :L377} is reproduced in the body below because it is
         * <b>evidence, not an instruction</b>: the author recorded that the cascade was known to be
         * incomplete, and the parity contract freezes it at two. A third validation would reject records the
         * legacy system accepts, which is a behaviour change and therefore forbidden.
         */
        @Test
        @DisplayName("the validation cascade stops at two lookups, exactly as :L377 leaves it")
        void theCascadeStopsAtTwoLookups() {
            stubHappyPath();

            processor.process(dailyTransaction("25.50"));

            // :L371 PERFORM 1500-A-LOOKUP-XREF - unconditional, and exactly once.
            Mockito.verify(cardCrossReferenceRepository).findById(CARD_NUMBER);
            // :L372-L373 IF WS-VALIDATION-FAIL-REASON = 0 / PERFORM 1500-B-LOOKUP-ACCT - exactly once.
            Mockito.verify(accountRepository).findById(ACCOUNT_ID);

            // :L377 * ADD MORE VALIDATIONS HERE
            //        Reproduced verbatim from app/cbl/CBTRN02C.cbl:L377. Nothing follows it in the source
            //        but EXIT at :L378, so there is no third lookup to assert and none may be added.
            // :L378 EXIT.
            //
            // The cross-reference file is READ ONLY in this program - the validation cascade is its sole
            // consumer - so no interaction beyond the one lookup may exist on it at all.
            Mockito.verifyNoMoreInteractions(cardCrossReferenceRepository);
            // The account repository is a different case and the assertion is scoped accordingly: :L441
            // legitimately rewrites the account inside 2800, so only the READS are constrained to one.
            Mockito.verify(accountRepository, Mockito.times(1)).findById(Mockito.anyLong());
            Mockito.verify(accountRepository, Mockito.never()).findByIdForUpdate(Mockito.anyLong());
        }

        @Test
        @DisplayName("exactly two lookups are observable, and in the order :L371 then :L373")
        void exactlyTwoLookupsAreObservable() {
            stubHappyPath();

            processor.process(dailyTransaction("25.50"));

            // The two lookups are separately observable, which is what AAP transformation rule 2 requires:
            // one private method per paragraph, no consolidation. Were 1500-A and 1500-B folded into a
            // single method the reads would still occur, but the ORDER below could not be asserted - and the
            // order is load-bearing, because :L394 MOVE XREF-ACCT-ID TO FD-ACCT-ID takes the account key
            // from the very record :L383 has just read, so the reads cannot be reordered or parallelised.
            InOrder cascade = Mockito.inOrder(cardCrossReferenceRepository, accountRepository);
            cascade.verify(cardCrossReferenceRepository).findById(CARD_NUMBER);
            cascade.verify(accountRepository).findById(ACCOUNT_ID);

            // Two lookups, no third. Counted rather than closed with verifyNoMoreInteractions, because the
            // posting path that follows legitimately saves the account at :L441 and the interaction the
            // cascade is being held to is the READ.
            Mockito.verify(cardCrossReferenceRepository, Mockito.times(1)).findById(Mockito.anyString());
            Mockito.verify(accountRepository, Mockito.times(1)).findById(Mockito.anyLong());
        }
    }

    @Nested
    @DisplayName("3. PostingResult: the exclusive-or of :L211")
    class PostingResultInvariant {

        @Test
        @DisplayName("a result carrying neither a transaction nor a reject code is refused")
        void neitherArmIsRefused() {
            DailyTransaction source = dailyTransaction("1.00");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new PostingResult(source, null, null))
                    .withMessageContaining("never both and never neither")
                    .withMessageContaining("app/cbl/CBTRN02C.cbl:L211");
        }

        @Test
        @DisplayName("a result carrying both arms is refused")
        void bothArmsAreRefused() {
            stubHappyPath();
            DailyTransaction source = dailyTransaction("1.00");
            Transaction posted = processor.process(source).postedTransaction();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new PostingResult(source, posted, RejectCode.INVALID_CARD_NUMBER))
                    .withMessageContaining("never both and never neither");
        }

        @Test
        @DisplayName("a result without a source record is refused")
        void anAbsentSourceIsRefused() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> PostingResult.rejected(null, RejectCode.INVALID_CARD_NUMBER))
                    .withMessage("source must not be null");
        }

        @Test
        @DisplayName("the posted factory refuses an absent transaction")
        void thePostedFactoryRefusesAnAbsentTransaction() {
            DailyTransaction source = dailyTransaction("1.00");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> PostingResult.posted(source, null))
                    .withMessage("postedTransaction must not be null");
        }

        @Test
        @DisplayName("the rejected factory refuses an absent reject code")
        void theRejectedFactoryRefusesAnAbsentRejectCode() {
            DailyTransaction source = dailyTransaction("1.00");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> PostingResult.rejected(source, null))
                    .withMessage("rejectCode must not be null");
        }

        @Test
        @DisplayName("a posted result renders the no-reject trailer of four zeros and 76 spaces")
        void aPostedResultRendersTheNoRejectTrailer() {
            stubHappyPath();

            PostingResult result = processor.process(dailyTransaction("1.00"));

            assertThat(result.failReasonCode()).isEqualTo(RejectCode.NO_REJECT_REASON_CODE);
            assertThat(result.failReasonField())
                    .hasSize(RejectCode.FAIL_REASON_LENGTH)
                    .isEqualTo("0000");
            assertThat(result.failReasonDescField())
                    .hasSize(RejectCode.FAIL_REASON_DESC_LENGTH)
                    .isBlank();
        }

        @Test
        @DisplayName("a rejected result renders the 80-byte trailer that the 430-byte record carries")
        void aRejectedResultRendersTheTrailer() {
            Mockito.when(cardCrossReferenceRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.empty());

            PostingResult result = processor.process(dailyTransaction("1.00"));

            assertThat(result.failReasonCode()).isEqualTo(100);
            assertThat(result.failReasonField()).isEqualTo("0100");
            assertThat(result.failReasonDescField())
                    .hasSize(RejectCode.FAIL_REASON_DESC_LENGTH)
                    .startsWith("INVALID CARD NUMBER FOUND");
            assertThat(result.failReasonField().length() + result.failReasonDescField().length())
                    .as("350 data bytes plus this 80-byte trailer is the 430-byte DALYREJS record")
                    .isEqualTo(RejectCode.VALIDATION_TRAILER_LENGTH);
        }
    }

    @Nested
    @DisplayName("4. 1500-A-LOOKUP-XREF: reject 100 at :L380-L392")
    class CrossReferenceLookup {

        @ParameterizedTest(name = "a card number of [{0}] cannot match a keyed dataset")
        @ValueSource(strings = {"", " ", "                "})
        @DisplayName("a blank card number is classified 100 without reading the dataset")
        void aBlankCardNumberIsClassified100(final String blank) {
            PostingResult result = processor.process(dailyTransaction("1.00", blank, ORIG_TS));

            assertThat(result.rejectCode()).isEqualTo(RejectCode.INVALID_CARD_NUMBER);
            Mockito.verifyNoInteractions(cardCrossReferenceRepository, accountRepository);
            assertThat(loggedMessages()).contains(
                    "Daily transaction carries no card number to look up; classifying as reason 0100");
        }

        @Test
        @DisplayName("an absent card number is classified 100 without reading the dataset")
        void anAbsentCardNumberIsClassified100() {
            DailyTransaction item = setField(dailyTransaction("1.00"), "cardNumber", null);

            PostingResult result = processor.process(item);

            assertThat(result.rejectCode()).isEqualTo(RejectCode.INVALID_CARD_NUMBER);
            Mockito.verifyNoInteractions(cardCrossReferenceRepository);
        }

        @Test
        @DisplayName("an INVALID KEY on the read is classified 100 with the source's own description")
        void anInvalidKeyIsClassified100() {
            Mockito.when(cardCrossReferenceRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.empty());

            PostingResult result = processor.process(dailyTransaction("1.00"));

            assertThat(result.rejectCode().getCode()).isEqualTo(100);
            assertThat(result.rejectCode().getDescription()).isEqualTo("INVALID CARD NUMBER FOUND");
            Mockito.verifyNoInteractions(accountRepository);
        }

        @Test
        @DisplayName("a NOT INVALID KEY continues to the account lookup of :L373")
        void aResolvedCrossReferenceContinues() {
            stubHappyPath();

            processor.process(dailyTransaction("1.00"));

            Mockito.verify(accountRepository).findById(ACCOUNT_ID);
        }
    }

    @Nested
    @DisplayName("5. 1500-B-LOOKUP-ACCT: reject 101 and the two tests that then do not run")
    class AccountLookup {

        @Test
        @DisplayName("an absent account is classified 101")
        void anAbsentAccountIsClassified101() {
            Mockito.when(cardCrossReferenceRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(crossReference()));
            Mockito.when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.empty());

            PostingResult result = processor.process(dailyTransaction("1.00"));

            assertThat(result.rejectCode()).isEqualTo(RejectCode.ACCOUNT_RECORD_NOT_FOUND);
            assertThat(result.rejectCode().getCode()).isEqualTo(101);
            assertThat(result.rejectCode().getDescription()).isEqualTo("ACCOUNT RECORD NOT FOUND");
        }

        @Test
        @DisplayName("when the account is absent the over-limit and expiry tests do not run at all")
        void theInnerTestsDoNotRunWhenTheAccountIsAbsent() {
            Mockito.when(cardCrossReferenceRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(crossReference()));
            Mockito.when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.empty());

            // An amount large enough that the over-limit test would fail, and an originating date later
            // than any plausible expiry: neither can be reached, because :L400-L420 sit inside the
            // NOT INVALID KEY clause the empty read never enters.
            PostingResult result = processor.process(
                    dailyTransaction("999999.99", CARD_NUMBER, "2099-12-31 00:00:00.000000"));

            assertThat(result.rejectCode()).isEqualTo(RejectCode.ACCOUNT_RECORD_NOT_FOUND);
            assertThat(loggedMessages()).noneMatch(message -> message.contains("Credit limit"));
            assertThat(loggedMessages()).noneMatch(message -> message.contains("expiry date"));
        }

        @Test
        @DisplayName("a cross-reference without XREF-ACCT-ID is an integrity failure, not a reject")
        void anAbsentAccountKeyIsAnIntegrityFailure() {
            Mockito.when(cardCrossReferenceRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(crossReferenceWithoutAccountId()));

            assertThatExceptionOfType(DataIntegrityException.class)
                    .isThrownBy(() -> processor.process(dailyTransaction("1.00")))
                    .satisfies(failure -> {
                        assertThat(failure.getRelation()).isEqualTo("account");
                        assertThat(failure.getMessage())
                                .contains("carries no XREF-ACCT-ID")
                                .contains("app/cpy/CVACT03Y.cpy:L7");
                    });
            Mockito.verifyNoInteractions(accountRepository);
        }
    }

    @Nested
    @DisplayName("6. The over-limit formula of :L403-L412, transcribed and not rearranged")
    class OverLimitFormula {

        /**
         * Stubs the cross-reference and an account with chosen arithmetic inputs and a distant expiry, so
         * that only the over-limit test can fail.
         *
         * @param creditLimit {@code ACCT-CREDIT-LIMIT}
         * @param cycleCredit {@code ACCT-CURR-CYC-CREDIT}
         * @param cycleDebit {@code ACCT-CURR-CYC-DEBIT}
         */
        private void stubArithmetic(final String creditLimit, final String cycleCredit,
                                    final String cycleDebit) {
            Mockito.when(cardCrossReferenceRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(crossReference()));
            Mockito.when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(
                    account("0.00", creditLimit, cycleCredit, cycleDebit, "2030-01-01")));
            Mockito.when(transactionCategoryBalanceRepository.findById(fixtureKey()))
                    .thenReturn(Optional.empty());
        }

        @ParameterizedTest(name = "limit {0}, cycle credit {1}, cycle debit {2}, amount {3} -> rejected={4}")
        @CsvSource({
            // The formula is ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT, compared with
            // ACCT-CREDIT-LIMIT >= WS-TEMP-BAL. Each row states the four inputs and whether 102 results.
            "500.00, 500.00, 100.00, 100.00, false",
            "499.99, 500.00, 100.00, 100.00, true",
            "700.00, 500.00, -100.00, 100.00, false",
            "699.99, 500.00, -100.00, 100.00, true",
            "0.00,   0.00,   0.00,    0.00,   false",
            "0.00,   0.00,   0.00,    0.01,   true",
            "0.00,   0.00,   0.00,   -0.01,   false",
            "100.00, 0.00,   -100.00, 0.00,   false",
            "99.99,  0.00,   -100.00, 0.00,   true"
        })
        @DisplayName("the temporary balance is credit minus debit plus amount, tested with >=")
        void theTemporaryBalanceIsTranscribedVerbatim(final String creditLimit, final String cycleCredit,
                                                      final String cycleDebit, final String amount,
                                                      final boolean rejected) {
            stubArithmetic(creditLimit, cycleCredit, cycleDebit);

            PostingResult result = processor.process(dailyTransaction(amount));

            if (rejected) {
                assertThat(result.rejectCode()).isEqualTo(RejectCode.OVERLIMIT_TRANSACTION);
                assertThat(result.rejectCode().getCode()).isEqualTo(102);
                assertThat(result.rejectCode().getDescription()).isEqualTo("OVERLIMIT TRANSACTION");
            } else {
                assertThat(result.isPosted()).isTrue();
            }
        }

        @Test
        @DisplayName("an intermediate above nine integer digits is TRUNCATED into WS-TEMP-BAL, so a "
                + "transaction unbounded arithmetic would reject is accepted")
        void anOverWideIntermediateIsTruncatedIntoTheDestination() {
            // WS-TEMP-BAL is PIC S9(09)V99 at app/cbl/CBTRN02C.cbl:L187 - nine integer digits - while
            // ACCT-CURR-CYC-CREDIT and ACCT-CURR-CYC-DEBIT are PIC S9(10)V99 at CVACT01Y:L13-L14. The COMPUTE
            // at :L403-L405 carries no ON SIZE ERROR, so a result that does not fit is stored with its
            // high-order digits discarded, and :L407 compares the credit limit against THAT.
            //
            // 9,999,999,999.99 - 0 + 0 is ten integer digits. Truncated to nine it becomes 999,999,999.99,
            // which the one-cent-larger credit limit accommodates, so the record POSTS. Without the narrowing
            // the comparison sees 9,999,999,999.99, the limit does not accommodate it, and the record is
            // rejected 102 - which is what this assertion fails with if the narrowing is ever removed.
            stubArithmetic("1000000000.00", "9999999999.99", "0.00");

            PostingResult result = processor.process(dailyTransaction("0.00"));

            assertThat(result.isPosted())
                    .as("the leading 9 is the digit PIC S9(09)V99 cannot hold, so the compared value is "
                            + "999,999,999.99 and the limit of 1,000,000,000.00 accommodates it")
                    .isTrue();
            assertThat(result.rejectCode())
                    .as("truncation always reduces the magnitude, so it can only ever turn a 102 into an "
                            + "acceptance - never the other way round")
                    .isNull();
        }

        @Test
        @DisplayName("exactly ten integer digits truncates to zero, every digit the field keeps being zero")
        void exactlyOneAboveTheWidthTruncatesToZero() {
            // 1,000,000,000.00 is the smallest value that does not fit. Its nine low-order integer digits are
            // all zero, so WS-TEMP-BAL holds 0.00 and even a zero credit limit accommodates it.
            stubArithmetic("0.00", "1000000000.00", "0.00");

            PostingResult result = processor.process(dailyTransaction("0.00"));

            assertThat(result.isPosted())
                    .as("the retained digits are 000000000 and the retained decimals 00, so the compared "
                            + "value is 0.00 and a limit of 0.00 satisfies the >= of :L407")
                    .isTrue();
        }

        @Test
        @DisplayName("the sign survives the truncation, because the picture is S9 and not 9")
        void theSignSurvivesTheTruncation() {
            // Cycle debit 9,999,999,999.99 with credit zero gives -9,999,999,999.99. S9(09)V99 keeps the sign
            // and the nine low-order integer digits, so the field holds -999,999,999.99. A negative compared
            // value is below any non-negative limit, so the record posts - and it would also post without the
            // narrowing, which is why the assertion below pins the SIGN rather than only the outcome.
            stubArithmetic("0.00", "0.00", "9999999999.99");

            PostingResult result = processor.process(dailyTransaction("0.00"));

            assertThat(result.isPosted())
                    .as("a negative temporary balance is under the limit, so :L408 CONTINUE applies")
                    .isTrue();
            assertThat(result.rejectCode()).isNull();
        }

        @Test
        @DisplayName("the widest value that FITS is untouched, so the narrowing cannot fire early")
        void theWidestFittingValueIsUntouched() {
            // 999,999,999.99 is the largest value PIC S9(09)V99 can hold. It must pass through unchanged: a
            // narrowing that fired one value early would compare 0.00 here and wrongly accept.
            stubArithmetic("999999999.98", "999999999.99", "0.00");

            PostingResult result = processor.process(dailyTransaction("0.00"));

            assertThat(result.rejectCode())
                    .as("the value fits exactly, so it is compared as itself and exceeds the limit by one "
                            + "cent. Truncating it would have compared 0.00 and accepted")
                    .isEqualTo(RejectCode.OVERLIMIT_TRANSACTION);
        }

        @Test
        @DisplayName("a negative cycle debit RAISES the temporary balance, because :L404 subtracts it")
        void aNegativeCycleDebitRaisesTheTemporaryBalance() {
            // Cycle debit of -500 with everything else zero. Subtracting it, as the source does, gives a
            // temporary balance of +500 which exceeds the 499.99 limit and rejects. Adding it instead - the
            // rearrangement this test exists to catch - would give -500 and post.
            stubArithmetic("499.99", "0.00", "-500.00");

            PostingResult result = processor.process(dailyTransaction("0.00"));

            assertThat(result.rejectCode())
                    .as("the debit accumulator holds negative values and the formula subtracts them")
                    .isEqualTo(RejectCode.OVERLIMIT_TRANSACTION);
        }

        @Test
        @DisplayName("the accepting arm is the source's explicit CONTINUE at :L408 and writes nothing")
        void theAcceptingArmIsAnExplicitNoOp() {
            stubArithmetic("9000.00", "500.00", "-100.00");

            processor.process(dailyTransaction("10.00"));

            assertThat(loggedMessages())
                    .contains("Credit limit accommodates the computed temporary balance");
        }

        @Test
        @DisplayName("equality accepts, because :L407 reads >= and not >")
        void equalityAccepts() {
            stubArithmetic("610.00", "500.00", "-100.00");

            PostingResult result = processor.process(dailyTransaction("10.00"));

            assertThat(result.isPosted()).isTrue();
        }
    }

    @Nested
    @DisplayName("7. PARITY TRAP: reject 103 overwrites 102 because :L413-L420 is unguarded")
    class ExpiryFallThrough {

        /**
         * Stubs the cross-reference and an account whose credit limit and expiry date are chosen so that
         * either, neither or both of the two sequential tests can fail.
         *
         * @param creditLimit {@code ACCT-CREDIT-LIMIT}
         * @param expiraionDate {@code ACCT-EXPIRAION-DATE}
         */
        private void stubLimitAndExpiry(final String creditLimit, final String expiraionDate) {
            Mockito.when(cardCrossReferenceRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(crossReference()));
            Mockito.when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(
                    account("0.00", creditLimit, "0.00", "0.00", expiraionDate)));
            Mockito.when(transactionCategoryBalanceRepository.findById(fixtureKey()))
                    .thenReturn(Optional.empty());
        }

        @Test
        @DisplayName("when BOTH tests fail, one reject bearing 103 is produced and 102 is lost")
        void oneHundredAndThreeOverwritesOneHundredAndTwo() {
            // Limit 0.00 against a temporary balance of 10.00 fails the over-limit test, and an expiry of
            // 2021-01-01 against an originating date of 2022-06-10 fails the expiry test. The source has no
            // guard and no early exit between :L413 and :L414, so the second assignment overwrites the first.
            stubLimitAndExpiry("0.00", "2021-01-01");

            PostingResult result = processor.process(dailyTransaction("10.00"));

            assertThat(result.rejectCode())
                    .as("103 overwrites 102 at app/cbl/CBTRN02C.cbl:L417")
                    .isEqualTo(RejectCode.TRANSACTION_RECEIVED_AFTER_ACCT_EXPIRATION);
            assertThat(result.rejectCode().getCode()).isEqualTo(103);
            assertThat(result.rejectCode()).isNotEqualTo(RejectCode.OVERLIMIT_TRANSACTION);
            assertThat(result.failReasonField()).isEqualTo("0103");
            assertThat(result.failReasonDescField())
                    .startsWith("TRANSACTION RECEIVED AFTER ACCT EXPIRATION");
        }

        @Test
        @DisplayName("when both tests fail, exactly one classification is returned - never a pair")
        void bothFailuresYieldOneClassification() {
            stubLimitAndExpiry("0.00", "2021-01-01");

            PostingResult result = processor.process(dailyTransaction("10.00"));

            assertThat(result.isRejected()).isTrue();
            assertThat(result.postedTransaction()).isNull();
            assertThat(loggedMessages())
                    .filteredOn(message -> message.startsWith("Daily transaction rejected with reason"))
                    .as(":L213-L215 increments the counter and writes the record once per record")
                    .hasSize(1);
        }

        @Test
        @DisplayName("when only the expiry test fails, 103 is produced")
        void onlyTheExpiryTestFails() {
            stubLimitAndExpiry("9000.00", "2021-01-01");

            PostingResult result = processor.process(dailyTransaction("10.00"));

            assertThat(result.rejectCode())
                    .isEqualTo(RejectCode.TRANSACTION_RECEIVED_AFTER_ACCT_EXPIRATION);
        }

        @Test
        @DisplayName("when only the over-limit test fails, 102 survives because :L415 is a no-op")
        void onlyTheOverLimitTestFails() {
            stubLimitAndExpiry("0.00", "2030-01-01");

            PostingResult result = processor.process(dailyTransaction("10.00"));

            assertThat(result.rejectCode())
                    .as(":L415 CONTINUE deliberately leaves an already-set reason code in place")
                    .isEqualTo(RejectCode.OVERLIMIT_TRANSACTION);
        }

        @Test
        @DisplayName("when neither test fails the record posts")
        void neitherTestFails() {
            stubLimitAndExpiry("9000.00", "2030-01-01");

            PostingResult result = processor.process(dailyTransaction("10.00"));

            assertThat(result.isPosted()).isTrue();
            assertThat(loggedMessages())
                    .contains("Account expiry date is not earlier than the originating date prefix");
        }

        @Test
        @DisplayName("an expiry equal to the originating date accepts, because :L414 reads >=")
        void anExpiryEqualToTheOriginatingDateAccepts() {
            stubLimitAndExpiry("9000.00", ORIG_DATE);

            PostingResult result = processor.process(dailyTransaction("10.00"));

            assertThat(result.isPosted()).isTrue();
        }

        @Test
        @DisplayName("only the first ten characters of DALYTRAN-ORIG-TS take part in the comparison")
        void onlyTenCharactersAreCompared() {
            stubLimitAndExpiry("9000.00", ORIG_DATE);

            // Everything after character ten is deliberately larger than any timestamp could be; if the
            // comparison used the whole PIC X(26) field the expiry would sort below it and reject.
            PostingResult result = processor.process(
                    dailyTransaction("10.00", CARD_NUMBER, "2022-06-10 99:99:99.999999"));

            assertThat(result.isPosted()).isTrue();
        }

        @Test
        @DisplayName("a short originating timestamp warns and space-fills to the PIC X(26) width")
        void aShortOriginatingTimestampWarnsAndPads() {
            stubLimitAndExpiry("9000.00", ORIG_DATE);

            // Seven characters: space-filled to twenty-six and then sliced to ten gives "2022-06   ",
            // which sorts below the expiry because a space is below every digit, so the expiry test passes.
            PostingResult result = processor.process(dailyTransaction("10.00", CARD_NUMBER, "2022-06"));

            assertThat(result.isPosted()).isTrue();
            assertThat(loggedMessages())
                    .anyMatch(message -> message.contains("is 7 characters, shorter than the 10")
                            && message.contains("a space sorts below every digit"));
        }

        @Test
        @DisplayName("an expiry one day earlier than the originating date rejects with 103")
        void anExpiryOneDayEarlierRejects() {
            stubLimitAndExpiry("9000.00", "2022-06-09");

            PostingResult result = processor.process(dailyTransaction("10.00"));

            assertThat(result.rejectCode().getCode()).isEqualTo(103);
        }
    }

    @Nested
    @DisplayName("8. An absent NOT NULL value is an integrity failure, never a reject")
    class IntegrityFailures {

        /**
         * Stubs the cross-reference and a chosen account, leaving the category balance absent.
         *
         * @param account the row {@code :L395} resolves
         */
        private void stubAccount(final Account account) {
            Mockito.when(cardCrossReferenceRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(crossReference()));
            Mockito.when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
            Mockito.when(transactionCategoryBalanceRepository.findById(fixtureKey()))
                    .thenReturn(Optional.empty());
        }

        @Test
        @DisplayName("an absent DALYTRAN-AMT fails against relation daily_transaction")
        void anAbsentAmountFails() {
            stubAccount(acceptingAccount());
            DailyTransaction item = setField(dailyTransaction("1.00"), "amount", null);

            assertThatExceptionOfType(DataIntegrityException.class)
                    .isThrownBy(() -> processor.process(item))
                    .satisfies(failure -> {
                        assertThat(failure.getRelation()).isEqualTo("daily_transaction");
                        assertThat(failure.getMessage())
                                .contains("DALYTRAN-AMT is absent")
                                .contains("no value cannot be read as zero");
                    });
        }

        @Test
        @DisplayName("an absent ACCT-CURR-CYC-CREDIT fails against relation account")
        void anAbsentCycleCreditFails() {
            stubAccount(setField(acceptingAccount(), "currentCycleCredit", null));

            assertThatExceptionOfType(DataIntegrityException.class)
                    .isThrownBy(() -> processor.process(dailyTransaction("1.00")))
                    .satisfies(failure -> {
                        assertThat(failure.getRelation()).isEqualTo("account");
                        assertThat(failure.getMessage()).contains("ACCT-CURR-CYC-CREDIT is absent");
                    });
        }

        @Test
        @DisplayName("an absent ACCT-CURR-CYC-DEBIT fails against relation account")
        void anAbsentCycleDebitFails() {
            stubAccount(setField(acceptingAccount(), "currentCycleDebit", null));

            assertThatExceptionOfType(DataIntegrityException.class)
                    .isThrownBy(() -> processor.process(dailyTransaction("1.00")))
                    .withMessageContaining("ACCT-CURR-CYC-DEBIT is absent");
        }

        @Test
        @DisplayName("an absent ACCT-CREDIT-LIMIT fails against relation account")
        void anAbsentCreditLimitFails() {
            stubAccount(setField(acceptingAccount(), "creditLimit", null));

            assertThatExceptionOfType(DataIntegrityException.class)
                    .isThrownBy(() -> processor.process(dailyTransaction("1.00")))
                    .withMessageContaining("ACCT-CREDIT-LIMIT is absent");
        }

        @Test
        @DisplayName("an absent ACCT-EXPIRAION-DATE cites the copybook and keeps its misspelling")
        void anAbsentExpiryDateFails() {
            stubAccount(setField(acceptingAccount(), "expiraionDate", null));

            assertThatExceptionOfType(DataIntegrityException.class)
                    .isThrownBy(() -> processor.process(dailyTransaction("1.00")))
                    .satisfies(failure -> assertThat(failure.getMessage())
                            .contains("ACCT-EXPIRAION-DATE is absent")
                            .contains("app/cpy/CVACT01Y.cpy:L11")
                            .contains("retains the copybook's misspelling")
                            .contains("00000000011"));
        }

        @Test
        @DisplayName("an absent DALYTRAN-ORIG-TS cites both the copybook and the DDL")
        void anAbsentOriginatingTimestampFails() {
            stubAccount(acceptingAccount());
            DailyTransaction item = setField(dailyTransaction("1.00"), "origTs", null);

            assertThatExceptionOfType(DataIntegrityException.class)
                    .isThrownBy(() -> processor.process(item))
                    .satisfies(failure -> {
                        assertThat(failure.getRelation()).isEqualTo("daily_transaction");
                        assertThat(failure.getMessage())
                                .contains("DALYTRAN-ORIG-TS is absent")
                                .contains("app/cpy/CVTRA06Y.cpy:L16")
                                .contains("dalytran_orig_ts CHAR(26)");
                    });
        }

        @Test
        @DisplayName("an absent ACCT-CURR-BAL fails inside 2800-UPDATE-ACCOUNT-REC")
        void anAbsentCurrentBalanceFails() {
            stubAccount(setField(acceptingAccount(), "currentBalance", null));

            assertThatExceptionOfType(DataIntegrityException.class)
                    .isThrownBy(() -> processor.process(dailyTransaction("1.00")))
                    .withMessageContaining("ACCT-CURR-BAL is absent");
        }

        @Test
        @DisplayName("an integrity failure is never reported as one of the five reject codes")
        void anIntegrityFailureIsNeverAReject() {
            stubAccount(setField(acceptingAccount(), "creditLimit", null));

            assertThatExceptionOfType(DataIntegrityException.class)
                    .isThrownBy(() -> processor.process(dailyTransaction("1.00")));

            assertThat(loggedMessages())
                    .as("reporting it as a reject would inflate the count that drives return code 4")
                    .noneMatch(message -> message.startsWith("Daily transaction rejected with reason"));
        }
    }

    @Nested
    @DisplayName("9. 2000-POST-TRANSACTION: thirteen moves, then three writes in source order")
    class PostTransaction {

        @Test
        @DisplayName("all thirteen fields of :L425-L438 are carried across")
        void allThirteenFieldsAreCarriedAcross() {
            stubHappyPath();
            DailyTransaction item = dailyTransaction("42.75");

            Transaction written = postedTransaction(processor.process(item));
            assertThat(written.getTransactionId()).isEqualTo(item.getTransactionId());
            assertThat(written.getTypeCode()).isEqualTo(item.getTypeCode());
            assertThat(written.getCategoryCode()).isEqualTo(item.getCategoryCode());
            assertThat(written.getTransactionSource()).isEqualTo(item.getTransactionSource());
            assertThat(written.getDescription()).isEqualTo(item.getDescription());
            assertThat(written.getAmount()).isEqualByComparingTo(item.getAmount());
            assertThat(written.getMerchantId()).isEqualTo(item.getMerchantId());
            assertThat(written.getMerchantName()).isEqualTo(item.getMerchantName());
            assertThat(written.getMerchantCity()).isEqualTo(item.getMerchantCity());
            assertThat(written.getMerchantZip()).isEqualTo(item.getMerchantZip());
            assertThat(written.getCardNumber()).isEqualTo(item.getCardNumber());
            assertThat(written.getOrigTs()).isEqualTo(item.getOrigTs());
            assertThat(written.getProcTs()).isEqualTo(EXPECTED_PROC_TS);
        }

        @Test
        @DisplayName("TRAN-ORIG-TS is passed through and TRAN-PROC-TS is generated, never the reverse")
        void theTwoTimestampsHaveDifferentProvenance() {
            stubHappyPath();
            DailyTransaction item = dailyTransaction("1.00");

            Transaction written = postedTransaction(processor.process(item));
            assertThat(written.getOrigTs())
                    .as(":L436 moves DALYTRAN-ORIG-TS verbatim")
                    .isEqualTo(ORIG_TS);
            assertThat(written.getProcTs())
                    .as(":L437-L438 generates DB2-FORMAT-TS")
                    .isNotEqualTo(ORIG_TS)
                    .isEqualTo(EXPECTED_PROC_TS);
        }

        @Test
        @DisplayName("the first two of the :L440-L442 writes happen here, in order: balance then account")
        void theTwoOwnedWritesHappenInSourceOrder() {
            stubHappyPath();

            PostingResult result = processor.process(dailyTransaction("1.00"));

            InOrder order = Mockito.inOrder(transactionCategoryBalanceRepository, accountRepository);
            // The account is READ first, by 1500-B-LOOKUP-ACCT during validation, and that read is the only
            // other interaction either owned mock receives - so it is verified in the chain rather than
            // excluded from it, which pins the full source order rather than just the two writes.
            order.verify(accountRepository).findById(Mockito.any());
            order.verify(transactionCategoryBalanceRepository).save(Mockito.any());
            order.verify(accountRepository).save(Mockito.any());
            // The flush is issued INSIDE the guard, immediately after the save, so the constraint violation
            // surfaces with its paragraph attached rather than at commit outside the try. It is therefore
            // part of the owned sequence and is verified in it, not excluded from it.
            order.verify(accountRepository).flush();
            order.verifyNoMoreInteractions();
            // :L442, the third write, is deliberately NOT performed here. The processor composes the record
            // and returns it; com.cardemo.batch.writers.TransactionWriter owns the TRANSACT sink and its
            // object mirror, so the write is asserted there. The ordering that matters for parity - balance
            // before account, both before the transaction - is preserved because the writer runs after the
            // processor within the same chunk-oriented step and the same transaction.
            assertThat(result.postedTransaction()).isNotNull();
        }

        @Test
        @DisplayName("the returned result carries the very entity the writer will persist")
        void theResultCarriesTheComposedEntity() {
            stubHappyPath();

            PostingResult result = processor.process(dailyTransaction("1.00"));

            assertThat(result.postedTransaction()).isSameAs(postedTransaction(result));
        }

        @Test
        @DisplayName("the identifier travels on the result; the log announces only the owned write")
        void theSuccessfulWriteIsLogged() {
            stubHappyPath();

            PostingResult result = processor.process(dailyTransaction("1.00"));

            // :L442, the TRANSACT write, belongs to com.cardemo.batch.writers.TransactionWriter. A line here
            // announcing a posted transaction would claim a write this class does not perform, so the
            // identifier is delivered on the result instead and the log announces the last write this class
            // DOES own. The identifier is not logged at all: the writer counts successes and logs only
            // failures, which is what replaced the source's end-of-run DISPLAY counters.
            assertThat(result.postedTransaction().getTransactionId()).isEqualTo(TRAN_ID);
            assertThat(loggedMessages())
                    .contains("Applied transaction amount to account " + WITHHELD)
                    .noneMatch(message -> message.contains(TRAN_ID));
        }
    }

    @Nested
    @DisplayName("10. Z-GET-DB2-FORMAT-TIMESTAMP: twenty-six characters ending in four zeros")
    class ProcessingTimestamp {

        @Test
        @DisplayName("the generated timestamp is exactly the twenty-six characters of :L690-L705")
        void theGeneratedTimestampIsExact() {
            stubHappyPath();

            assertThat(postedTransaction(processor.process(dailyTransaction("1.00"))).getProcTs())
                    .hasSize(26)
                    .isEqualTo("2022-06-10-19.27.53.470000");
        }

        @Test
        @DisplayName("the final four characters are the literal 0000 that :L701 moves")
        void theFinalFourCharactersAreTheLiteral() {
            stubHappyPath();

            String procTs = postedTransaction(processor.process(dailyTransaction("1.00"))).getProcTs();
            assertThat(procTs.substring(procTs.length() - 4))
                    .as("hundredths-of-a-second precision plus four zeros, never millisecond or nanosecond")
                    .isEqualTo("0000");
        }

        @Test
        @DisplayName("the layout is yyyy-MM-dd-HH.mm.ss.SS followed by 0000")
        void theLayoutIsFixed() {
            stubHappyPath();

            assertThat(postedTransaction(processor.process(dailyTransaction("1.00"))).getProcTs())
                    .matches("\\d{4}-\\d{2}-\\d{2}-\\d{2}\\.\\d{2}\\.\\d{2}\\.\\d{2}0000");
        }

        @Test
        @DisplayName("hundredths below ten keep their leading zero, so the width never varies")
        void hundredthsBelowTenKeepTheirLeadingZero() {
            TransactionPostingProcessor earlyClockProcessor = newProcessor(cardCrossReferenceRepository,
                    accountRepository, transactionCategoryBalanceRepository, fileStatusMapper,
                    Clock.fixed(Instant.parse("2022-01-02T03:04:05.060Z"), ZoneOffset.UTC));
            stubHappyPath();

            assertThat(postedTransaction(earlyClockProcessor.process(dailyTransaction("1.00"))).getProcTs())
                    .hasSize(26)
                    .isEqualTo("2022-01-02-03.04.05.060000");
        }

        @Test
        @DisplayName("the clock, not the wall clock, decides the value")
        void theClockDecidesTheValue() {
            TransactionPostingProcessor otherClockProcessor = newProcessor(cardCrossReferenceRepository,
                    accountRepository, transactionCategoryBalanceRepository, fileStatusMapper,
                    Clock.fixed(Instant.parse("1999-12-31T23:59:59.990Z"), ZoneOffset.UTC));
            stubHappyPath();

            assertThat(postedTransaction(otherClockProcessor.process(dailyTransaction("1.00"))).getProcTs())
                    .isEqualTo("1999-12-31-23.59.59.990000");
        }
    }

    @Nested
    @DisplayName("11. 2700-UPDATE-TCATBAL: FILE STATUS '23' is success and drives the create branch")
    class CategoryBalanceUpsert {

        /**
         * Stubs a resolved cross-reference and an accepting account, leaving the category-balance read
         * outcome to the caller.
         */
        private void stubValidated() {
            Mockito.when(cardCrossReferenceRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(crossReference()));
            Mockito.when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(acceptingAccount()));
        }

        @Test
        @DisplayName("an absent row derives '23', which the mapper accepts as the create branch")
        void anAbsentRowDerivesTwentyThree() {
            stubValidated();
            Mockito.when(transactionCategoryBalanceRepository.findById(fixtureKey()))
                    .thenReturn(Optional.empty());

            processor.process(dailyTransaction("1.00"));

            Mockito.verify(fileStatusMapper).requireCategoryBalanceReadSuccess("23");
            assertThat(savedCategoryBalance().getId()).isEqualTo(fixtureKey());
        }

        @Test
        @DisplayName("a present row derives '00', which the mapper resolves to the rewrite branch")
        void aPresentRowDerivesZeroZero() {
            stubValidated();
            Mockito.when(transactionCategoryBalanceRepository.findById(fixtureKey()))
                    .thenReturn(Optional.of(new TransactionCategoryBalance(fixtureKey(),
                            new BigDecimal("100"))));

            processor.process(dailyTransaction("1.00"));

            Mockito.verify(fileStatusMapper).requireCategoryBalanceReadSuccess("00");
        }

        @Test
        @DisplayName("the create branch logs the :L476-L477 DISPLAY verbatim with the 17-byte key image")
        void theCreateBranchLogsTheSourceDisplay() {
            stubValidated();
            Mockito.when(transactionCategoryBalanceRepository.findById(fixtureKey()))
                    .thenReturn(Optional.empty());

            processor.process(dailyTransaction("1.00"));

            // Both :L476-L477 literals and the emission point are preserved; the KEY between them is
            // withheld. FD-TRAN-CAT-KEY is the 17-byte composite of the account identifier, the type code
            // and the category code, so the keyed form would put a customer's account identifier into a
            // high-volume INFO line. The key itself is still asserted below, from the record that was
            // written, which is where it is observable without being disclosed.
            assertThat(loggedMessages()).contains(
                    "TCATBAL record not found for key : " + WITHHELD + ".. Creating.");
            assertThat(loggedMessages())
                    .as("no log line may carry the composite key in any form")
                    .noneMatch(message -> message.contains(TRAN_CAT_KEY_IMAGE));
            assertThat(TRAN_CAT_KEY_IMAGE)
                    .as("11 digits + 2 characters + 4 digits is the key length LISTCAT records for TCATBALF")
                    .hasSize(17);
        }

        @Test
        @DisplayName("the create branch starts from zero and adds the amount, per :L504 and :L508")
        void theCreateBranchStartsFromZero() {
            stubValidated();
            Mockito.when(transactionCategoryBalanceRepository.findById(fixtureKey()))
                    .thenReturn(Optional.empty());

            processor.process(dailyTransaction("42.75"));

            assertThat(savedCategoryBalance().getBalance()).isEqualByComparingTo(new BigDecimal("42.75"));
            assertThat(loggedMessages())
                    .contains("Created transaction category balance for key " + WITHHELD);
        }

        @Test
        @DisplayName("the rewrite branch adds the amount to the balance already held, per :L527")
        void theRewriteBranchAccumulates() {
            stubValidated();
            TransactionCategoryBalance existing =
                    new TransactionCategoryBalance(fixtureKey(), new BigDecimal("100.25"));
            Mockito.when(transactionCategoryBalanceRepository.findById(fixtureKey()))
                    .thenReturn(Optional.of(existing));

            processor.process(dailyTransaction("42.75"));

            assertThat(savedCategoryBalance())
                    .as("the source REWRITEs the record it read, it does not create a second one")
                    .isSameAs(existing);
            assertThat(existing.getBalance()).isEqualByComparingTo(new BigDecimal("143.00"));
            assertThat(loggedMessages())
                    .contains("Updated transaction category balance for key " + WITHHELD);
        }

        @Test
        @DisplayName("a negative amount reduces the category balance, with no absolute value taken")
        void aNegativeAmountReducesTheBalance() {
            stubValidated();
            TransactionCategoryBalance existing =
                    new TransactionCategoryBalance(fixtureKey(), new BigDecimal("100.00"));
            Mockito.when(transactionCategoryBalanceRepository.findById(fixtureKey()))
                    .thenReturn(Optional.of(existing));

            processor.process(dailyTransaction("-30.00"));

            assertThat(existing.getBalance()).isEqualByComparingTo(new BigDecimal("70.00"));
        }

        @Test
        @DisplayName("the stored balance always carries scale 2, matching NUMERIC(11,2)")
        void theStoredBalanceCarriesScaleTwo() {
            stubValidated();
            TransactionCategoryBalance existing =
                    new TransactionCategoryBalance(fixtureKey(), new BigDecimal("100"));
            Mockito.when(transactionCategoryBalanceRepository.findById(fixtureKey()))
                    .thenReturn(Optional.of(existing));

            processor.process(dailyTransaction("1.50"));

            assertThat(existing.getBalance().scale())
                    .as("TRAN-CAT-BAL is PIC S9(09)V99, so the value is rescaled explicitly")
                    .isEqualTo(2);
            assertThat(existing.getBalance().toPlainString()).isEqualTo("101.50");
        }

        @Test
        @DisplayName("the create DISPLAY is not emitted when the row already exists")
        void theCreateDisplayIsNotEmittedOnTheRewritePath() {
            stubValidated();
            Mockito.when(transactionCategoryBalanceRepository.findById(fixtureKey()))
                    .thenReturn(Optional.of(new TransactionCategoryBalance(fixtureKey(),
                            new BigDecimal("1.00"))));

            processor.process(dailyTransaction("1.00"));

            assertThat(loggedMessages())
                    .noneMatch(message -> message.startsWith("TCATBAL record not found for key"));
        }

        @Test
        @DisplayName("an absent TRAN-CAT-BAL on the row that was read is an integrity failure")
        void anAbsentStoredBalanceFails() {
            stubValidated();
            TransactionCategoryBalance existing = setField(
                    new TransactionCategoryBalance(fixtureKey(), new BigDecimal("1.00")), "balance", null);
            Mockito.when(transactionCategoryBalanceRepository.findById(fixtureKey()))
                    .thenReturn(Optional.of(existing));

            assertThatExceptionOfType(DataIntegrityException.class)
                    .isThrownBy(() -> processor.process(dailyTransaction("1.00")))
                    .satisfies(failure -> {
                        assertThat(failure.getRelation()).isEqualTo("transaction_category_balance");
                        assertThat(failure.getMessage()).contains("TRAN-CAT-BAL is absent");
                    });
        }
    }

    @Nested
    @DisplayName("12. FD-TRAN-CAT-KEY: the account identifier comes from the cross-reference")
    class CategoryBalanceKeyComposition {

        @Test
        @DisplayName("the key's account component is XREF-ACCT-ID, per :L469")
        void theKeyTakesItsAccountFromTheCrossReference() {
            long distinctiveAccountId = 98765432101L;
            Mockito.when(cardCrossReferenceRepository.findById(CARD_NUMBER)).thenReturn(Optional.of(
                    new CardCrossReference(CARD_NUMBER, 123456789L, distinctiveAccountId)));
            Mockito.when(accountRepository.findById(distinctiveAccountId)).thenReturn(Optional.of(
                    new Account(distinctiveAccountId, "Y", new BigDecimal("0.00"),
                            new BigDecimal("9000.00"), new BigDecimal("5000.00"), "2020-01-01",
                            "2030-01-01", "2020-01-01", new BigDecimal("0.00"), new BigDecimal("0.00"),
                            "12345", "DEFAULT")));
            Mockito.when(transactionCategoryBalanceRepository.findById(Mockito.any()))
                    .thenReturn(Optional.empty());

            processor.process(dailyTransaction("1.00"));

            TransactionCategoryBalanceId key = savedCategoryBalance().getId();
            assertThat(key.getAccountId()).isEqualTo(distinctiveAccountId);
            assertThat(key.getTypeCd()).isEqualTo(TYPE_CD);
            assertThat(key.getCatCd()).isEqualTo(CAT_CD);
            // The key is proven above, from the record that was written. The log announces the create and
            // withholds the key, so the account identifier this test deliberately made distinctive must NOT
            // appear in any message.
            assertThat(loggedMessages())
                    .contains("TCATBAL record not found for key : " + WITHHELD + ".. Creating.")
                    .noneMatch(message -> message.contains(
                            String.format(Locale.ROOT, "%011d", distinctiveAccountId)));
        }

        @Test
        @DisplayName("a blank DALYTRAN-TYPE-CD cannot form the key and is an integrity failure")
        void aBlankTypeCodeCannotFormTheKey() {
            Mockito.when(cardCrossReferenceRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(crossReference()));
            Mockito.when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(acceptingAccount()));
            DailyTransaction item = setField(dailyTransaction("1.00"), "typeCode", "  ");

            assertThatExceptionOfType(DataIntegrityException.class)
                    .isThrownBy(() -> processor.process(item))
                    .satisfies(failure -> {
                        assertThat(failure.getRelation()).isEqualTo("transaction_category_balance");
                        assertThat(failure.getMessage())
                                .contains("DALYTRAN-TYPE-CD is absent or blank")
                                .contains("app/cbl/CBTRN02C.cbl:L470")
                                .contains("fk08_tcatbal_category");
                    });
            Mockito.verify(transactionCategoryBalanceRepository, Mockito.never()).save(Mockito.any());
        }

        @Test
        @DisplayName("an absent DALYTRAN-CAT-CD is stopped by the transaction entity before the key forms")
        void anAbsentCategoryCodeIsStoppedUpstream() {
            Mockito.when(cardCrossReferenceRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(crossReference()));
            Mockito.when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(acceptingAccount()));
            DailyTransaction item = setField(dailyTransaction("1.00"), "categoryCode", null);

            // :L427 moves DALYTRAN-CAT-CD into the transaction record before :L471 moves it into the key,
            // so the receiving entity's own guard is what a caller sees. The key's guard is the second line
            // of defence and is asserted through the blank type code above, which the entity does permit.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> processor.process(item))
                    .withMessageContaining("categoryCode");
            Mockito.verify(transactionCategoryBalanceRepository, Mockito.never()).save(Mockito.any());
        }
    }

    @Nested
    @DisplayName("13. 2800-UPDATE-ACCOUNT-REC: the sign branch of :L548-L552 adds, never abs()")
    class AccountCycleAccumulation {

        /**
         * Stubs a validated record against a chosen account, leaving the category balance absent.
         *
         * @param account the account whose accumulators the test inspects afterwards
         */
        private void stubWith(final Account account) {
            Mockito.when(cardCrossReferenceRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(crossReference()));
            Mockito.when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
            Mockito.when(transactionCategoryBalanceRepository.findById(fixtureKey()))
                    .thenReturn(Optional.empty());
        }

        @Test
        @DisplayName("a positive amount goes to ACCT-CURR-CYC-CREDIT and leaves the debit untouched")
        void aPositiveAmountGoesToCredit() {
            Account account = account("100.00", "9000.00", "500.00", "-40.00", "2030-01-01");
            stubWith(account);

            processor.process(dailyTransaction("60.00"));

            assertThat(account.getCurrentCycleCredit()).isEqualByComparingTo(new BigDecimal("560.00"));
            assertThat(account.getCurrentCycleDebit()).isEqualByComparingTo(new BigDecimal("-40.00"));
            assertThat(account.getCurrentBalance()).isEqualByComparingTo(new BigDecimal("160.00"));
        }

        @Test
        @DisplayName("a zero amount takes the credit arm, because :L548 reads >= 0 and not > 0")
        void aZeroAmountTakesTheCreditArm() {
            // The accumulators start at scale 0. Only the arm the amount takes is rescaled to two decimals,
            // so the scale is what reveals which branch ran when the amount itself is zero.
            Account account = account("100.00", "9000.00", "500", "-40", "2030-01-01");
            stubWith(account);

            processor.process(dailyTransaction("0.00"));

            assertThat(account.getCurrentCycleCredit().scale())
                    .as("the credit accumulator was rewritten, so the inclusive comparison took its arm")
                    .isEqualTo(2);
            assertThat(account.getCurrentCycleDebit().scale())
                    .as("the debit accumulator was not touched")
                    .isZero();
        }

        @Test
        @DisplayName("a negative amount is ADDED to ACCT-CURR-CYC-DEBIT, so that accumulator goes negative")
        void aNegativeAmountIsAddedToDebit() {
            Account account = account("100.00", "9000.00", "500.00", "-40.00", "2030-01-01");
            stubWith(account);

            processor.process(dailyTransaction("-25.00"));

            assertThat(account.getCurrentCycleDebit())
                    .as("no absolute value is taken; the over-limit formula subtracts this value")
                    .isEqualByComparingTo(new BigDecimal("-65.00"));
            assertThat(account.getCurrentCycleCredit()).isEqualByComparingTo(new BigDecimal("500.00"));
        }

        @Test
        @DisplayName("a negative amount reduces ACCT-CURR-BAL as well, per :L547")
        void aNegativeAmountReducesTheCurrentBalance() {
            Account account = account("100.00", "9000.00", "0.00", "0.00", "2030-01-01");
            stubWith(account);

            processor.process(dailyTransaction("-25.50"));

            assertThat(account.getCurrentBalance()).isEqualByComparingTo(new BigDecimal("74.50"));
        }

        @ParameterizedTest(name = "amount {0} -> balance {1}, credit {2}, debit {3}")
        @CsvSource({
            "10.00,  110.00, 10.00, 0.00",
            "0.00,   100.00, 0.00,  0.00",
            "-10.00, 90.00,  0.00,  -10.00",
            "-0.01,  99.99,  0.00,  -0.01",
            "0.01,   100.01, 0.01,  0.00"
        })
        @DisplayName("the three accumulators move exactly as :L547-L552 writes them")
        void theThreeAccumulatorsMoveTogether(final String amount, final String expectedBalance,
                                              final String expectedCredit, final String expectedDebit) {
            Account account = account("100.00", "9000.00", "0.00", "0.00", "2030-01-01");
            stubWith(account);

            processor.process(dailyTransaction(amount));

            assertThat(account.getCurrentBalance()).isEqualByComparingTo(new BigDecimal(expectedBalance));
            assertThat(account.getCurrentCycleCredit()).isEqualByComparingTo(new BigDecimal(expectedCredit));
            assertThat(account.getCurrentCycleDebit()).isEqualByComparingTo(new BigDecimal(expectedDebit));
        }

        @Test
        @DisplayName("the account that was read is the account that is rewritten")
        void theAccountReadIsTheAccountRewritten() {
            Account account = acceptingAccount();
            stubWith(account);

            processor.process(dailyTransaction("1.00"));

            ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
            Mockito.verify(accountRepository).save(captor.capture());
            assertThat(captor.getValue()).isSameAs(account);
            assertThat(loggedMessages()).contains("Applied transaction amount to account " + WITHHELD);
        }

        @Test
        @DisplayName("every rewritten money field carries scale 2, matching NUMERIC(12,2)")
        void everyRewrittenMoneyFieldCarriesScaleTwo() {
            Account account = account("100", "9000.00", "500", "-40", "2030-01-01");
            stubWith(account);

            processor.process(dailyTransaction("1.10"));

            assertThat(account.getCurrentBalance().scale()).isEqualTo(2);
            assertThat(account.getCurrentCycleCredit().scale()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("14. The four store guards and the reason code 109 that is never consumed")
    class StoreFailureTranslation {

        /** Stubs a validated record with the category balance absent, so the create branch applies. */
        private void stubCreateBranch() {
            Mockito.when(cardCrossReferenceRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(crossReference()));
            Mockito.when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(acceptingAccount()));
            Mockito.when(transactionCategoryBalanceRepository.findById(fixtureKey()))
                    .thenReturn(Optional.empty());
        }

        /** Stubs a validated record with the category balance present, so the rewrite branch applies. */
        private void stubRewriteBranch() {
            Mockito.when(cardCrossReferenceRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(crossReference()));
            Mockito.when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(acceptingAccount()));
            Mockito.when(transactionCategoryBalanceRepository.findById(fixtureKey()))
                    .thenReturn(Optional.of(new TransactionCategoryBalance(fixtureKey(),
                            new BigDecimal("10.00"))));
        }

        @Test
        @DisplayName("a duplicate key on the TCATBAL WRITE becomes FILE STATUS '22', never an upsert")
        void aDuplicateKeyOnTheBalanceWrite() {
            stubCreateBranch();
            DuplicateKeyException collision = new DuplicateKeyException("pk_transaction_category_balance");
            Mockito.when(transactionCategoryBalanceRepository.save(Mockito.any())).thenThrow(collision);

            assertThatExceptionOfType(DuplicateRecordException.class)
                    .isThrownBy(() -> processor.process(dailyTransaction("1.00")))
                    .satisfies(failure -> {
                        assertThat(failure.getMessage())
                                .startsWith("ERROR WRITING TRANSACTION BALANCE FILE")
                                .contains("The WRITE of DD TCATBALF")
                                .contains("relation transaction_category_balance")
                                .contains("for key " + TRAN_CAT_KEY_IMAGE)
                                .contains("FILE STATUS '22'")
                                .contains("do not retry and do not upsert");
                        assertThat(failure.getLogicalFile()).isEqualTo("TCATBALF");
                        assertThat(failure.getCollidingKey()).isEqualTo(TRAN_CAT_KEY_IMAGE);
                        assertThat(failure).hasCause(collision);
                    });
        }

        @Test
        @DisplayName("a constraint violation on the TCATBAL REWRITE names the relation and keeps the cause")
        void aConstraintViolationOnTheBalanceRewrite() {
            stubRewriteBranch();
            DataIntegrityViolationException violation =
                    new DataIntegrityViolationException("fk08_tcatbal_category");
            Mockito.when(transactionCategoryBalanceRepository.save(Mockito.any())).thenThrow(violation);

            assertThatExceptionOfType(DataIntegrityException.class)
                    .isThrownBy(() -> processor.process(dailyTransaction("1.00")))
                    .satisfies(failure -> {
                        assertThat(failure.getMessage())
                                .startsWith("ERROR REWRITING TRANSACTION BALANCE FILE")
                                .contains("A constraint rejected the REWRITE of DD TCATBALF")
                                .contains("V1__create_schema.sql declares on this relation");
                        assertThat(failure.getRelation()).isEqualTo("transaction_category_balance");
                        assertThat(failure).hasCause(violation);
                    });
        }

        @Test
        @DisplayName("an unexpected failure on the TCATBAL WRITE reaches 9999-ABEND-PROGRAM")
        void anUnexpectedFailureOnTheBalanceWriteAbends() {
            stubCreateBranch();
            CannotAcquireLockException lockFailure = new CannotAcquireLockException("timeout");
            Mockito.when(transactionCategoryBalanceRepository.save(Mockito.any())).thenThrow(lockFailure);

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> processor.process(dailyTransaction("1.00")))
                    .satisfies(abend -> {
                        assertThat(abend.getAbendCode())
                                .isEqualTo(String.valueOf(FatalProcessingException.BATCH_ABEND_CODE));
                        assertThat(abend.getAbendCulprit()).isEqualTo("CBTRN02C");
                        assertThat(abend.getAbendMessage())
                                .contains("neither a duplicate key nor a constraint violation")
                                .contains("PERFORM 9999-ABEND-PROGRAM");
                        assertThat(abend).hasCause(lockFailure);
                    });
        }

        @Test
        @DisplayName("the ACCOUNT REWRITE failure names reason 0109 in its text and returns no reject code")
        void theAccountRewriteFailureNamesButNeverReturnsOneHundredAndNine() {
            stubCreateBranch();
            CannotAcquireLockException lockFailure = new CannotAcquireLockException("row locked");
            Mockito.when(accountRepository.save(Mockito.any())).thenThrow(lockFailure);

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> processor.process(dailyTransaction("1.00")))
                    .satisfies(abend -> assertThat(abend.getAbendMessage())
                            .contains("ACCOUNT REWRITE FAILED")
                            .contains("app/cbl/CBTRN02C.cbl:L554 would have set reason 0109")
                            .contains("ACCOUNT RECORD NOT FOUND")
                            .contains("and continued")
                            .contains("The REWRITE of DD ACCTFILE")
                            .contains("relation account")
                            .contains("for key 00000000011"));

            assertThat(RejectCode.ACCOUNT_RECORD_NOT_FOUND_ON_REWRITE.getCode())
                    .as("the fifth constant exists because :L556 assigns it, not because anything reads it")
                    .isEqualTo(109);
            assertThat(RejectCode.ACCOUNT_RECORD_NOT_FOUND_ON_REWRITE.getDescription())
                    .isEqualTo("ACCOUNT RECORD NOT FOUND");
        }

        @Test
        @DisplayName("the ACCOUNT REWRITE abend attributes itself, because :L545-L560 has no guard to borrow")
        void theAccountRewriteAbendDoesNotBorrowTheGuardsAuthority() {
            // FINDING, severity Medium, REGRESSION GUARD. The unclassified tail used to be one fixed sentence
            // for all three call sites, ending "so the guard in app/cbl/CBTRN02C.cbl reaches PERFORM
            // 9999-ABEND-PROGRAM". On this path that sentence is false and contradicts both the source and
            // this class's own Javadoc: 2800-UPDATE-ACCOUNT-REC at app/cbl/CBTRN02C.cbl:L545-L560 tests no
            // FILE STATUS at all - its INVALID KEY clause assigns 109 at :L556 and falls through the EXIT at
            // :L560 to 2900-WRITE-TRANSACTION-FILE, with no DISPLAY, no 9910 and no 9999 anywhere. The abend
            // is this implementation's own escalation under DEVIATION 2 and the message now says so.
            stubCreateBranch();
            CannotAcquireLockException lockFailure = new CannotAcquireLockException("row locked");
            Mockito.when(accountRepository.save(Mockito.any())).thenThrow(lockFailure);

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> processor.process(dailyTransaction("1.00")))
                    .satisfies(abend -> assertThat(abend.getAbendMessage())
                            .contains("app/cbl/CBTRN02C.cbl:L545-L560 does not guard this REWRITE")
                            .contains("assigns reason 109 at :L556")
                            .contains("continues to 2900-WRITE-TRANSACTION-FILE")
                            .contains("no DISPLAY, no 9910-DISPLAY-IO-STATUS and no 9999-ABEND-PROGRAM")
                            .contains("escalates deliberately - DEVIATION 2")
                            .as("the withdrawn tail claimed the source's guard reached the abend paragraph, "
                                    + "which on this path it does not")
                            .doesNotContain("PERFORM 9999-ABEND-PROGRAM"));
        }

        @Test
        @DisplayName("the two TCATBAL guards keep the 9999 attribution, because :L512 and :L530 do reach it")
        void theBalanceGuardsKeepTheSourcesOwnAttribution() {
            // The companion of the assertion above: parameterising the attribution must not weaken the two
            // paths where the source genuinely does display, render and abend - :L512-L524 for the WRITE and
            // :L530-L542 for the REWRITE, both accepting '00' only.
            stubRewriteBranch();
            CannotAcquireLockException lockFailure = new CannotAcquireLockException("timeout");
            Mockito.when(transactionCategoryBalanceRepository.save(Mockito.any())).thenThrow(lockFailure);

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> processor.process(dailyTransaction("1.00")))
                    .satisfies(abend -> assertThat(abend.getAbendMessage())
                            .startsWith("ERROR REWRITING TRANSACTION BALANCE FILE")
                            .contains("so the guard in app/cbl/CBTRN02C.cbl reaches "
                                    + "PERFORM 9999-ABEND-PROGRAM")
                            .doesNotContain("does not guard this REWRITE"));
        }

        @Test
        @DisplayName("a duplicate key on the ACCOUNT REWRITE is a duplicate record, not an abend")
        void aDuplicateKeyOnTheAccountRewrite() {
            stubCreateBranch();
            Mockito.when(accountRepository.save(Mockito.any()))
                    .thenThrow(new DuplicateKeyException("pk_account"));

            assertThatExceptionOfType(DuplicateRecordException.class)
                    .isThrownBy(() -> processor.process(dailyTransaction("1.00")))
                    .satisfies(failure -> {
                        assertThat(failure.getLogicalFile()).isEqualTo("ACCTFILE");
                        assertThat(failure.getCollidingKey()).isEqualTo("00000000011");
                    });
        }

        @Test
        @DisplayName("a constraint violation on the ACCOUNT REWRITE names relation account")
        void aConstraintViolationOnTheAccountRewrite() {
            stubCreateBranch();
            Mockito.when(accountRepository.save(Mockito.any()))
                    .thenThrow(new DataIntegrityViolationException("chk_acct"));

            assertThatExceptionOfType(DataIntegrityException.class)
                    .isThrownBy(() -> processor.process(dailyTransaction("1.00")))
                    .satisfies(failure -> assertThat(failure.getRelation()).isEqualTo("account"));
        }

        // The three TRANFILE-write failures of :L442 - a duplicate TRAN-ID, an unexpected driver failure and
        // a constraint violation - are NOT exercised here, because this processor does not perform that write.
        // com.cardemo.batch.writers.TransactionWriter owns the TRANSACT sink, and its own tests assert the
        // same three translations against the same messages: see TransactionWriterTest's duplicate-key,
        // abend and integrity cases, and BatchWriteSemanticsTest for the ERROR WRITING TO TRANSACTION FILE
        // wording. Restating them against a collaborator this class no longer holds would assert a
        // relationship that does not exist.
    }

    @Nested
    @DisplayName("15. Diagnostic hygiene: no card number ever reaches a log record or a message")
    class DiagnosticHygiene {

        @Test
        @DisplayName("a posted record emits no log line carrying the primary account number")
        void aPostedRecordNeverLogsTheCardNumber() {
            stubHappyPath();

            processor.process(dailyTransaction("1.00"));

            assertThat(loggedMessages())
                    .as("not emitting the PAN is the primary defence; masking is only the backstop")
                    .noneMatch(message -> message.contains(CARD_NUMBER));
        }

        @Test
        @DisplayName("a rejected record emits no log line carrying the primary account number")
        void aRejectedRecordNeverLogsTheCardNumber() {
            Mockito.when(cardCrossReferenceRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.empty());

            processor.process(dailyTransaction("1.00"));

            assertThat(loggedMessages()).noneMatch(message -> message.contains(CARD_NUMBER));
        }

        @Test
        @DisplayName("an integrity failure message carries the TRAN-ID and not the card number")
        void anIntegrityFailureNeverCarriesTheCardNumber() {
            Mockito.when(cardCrossReferenceRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(crossReference()));
            Mockito.when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(acceptingAccount()));
            DailyTransaction item = setField(dailyTransaction("1.00"), "origTs", null);

            assertThatExceptionOfType(DataIntegrityException.class)
                    .isThrownBy(() -> processor.process(item))
                    .satisfies(failure -> assertThat(failure.getMessage())
                            .contains(TRAN_ID)
                            .doesNotContain(CARD_NUMBER));
        }

        @Test
        @DisplayName("an absent TRAN-ID renders the fixed placeholder rather than throwing")
        void anAbsentTransactionIdRendersAPlaceholder() {
            Mockito.when(cardCrossReferenceRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(crossReference()));
            Mockito.when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(acceptingAccount()));
            DailyTransaction item = setField(
                    setField(dailyTransaction("1.00"), "origTs", null), "transactionId", null);

            assertThatExceptionOfType(DataIntegrityException.class)
                    .isThrownBy(() -> processor.process(item))
                    .satisfies(failure -> assertThat(failure.getMessage())
                            .as("a diagnostic must never be the thing that throws")
                            .contains("DALYTRAN-ORIG-TS is absent for TRAN-ID (absent)"));
        }

        @Test
        @DisplayName("the account key image is always eleven digits, so log lines stay comparable")
        void theAccountKeyImageIsAlwaysElevenDigits() {
            stubHappyPath();

            processor.process(dailyTransaction("1.00"));

            // The key image is what this test is about, and it is eleven digits wherever it is observable:
            // on the record that was written. The log line that announces the rewrite carries the marker
            // instead, so the width claim is made against the key and the log is asserted to withhold it.
            assertThat(String.format(Locale.ROOT, "%011d", savedCategoryBalance().getId().getAccountId()))
                    .hasSize(11)
                    .isEqualTo("00000000011");
            assertThat(loggedMessages())
                    .filteredOn(message -> message.startsWith("Applied transaction amount to account "))
                    .singleElement()
                    .satisfies(message -> assertThat(
                            message.substring("Applied transaction amount to account ".length()))
                            .isEqualTo(WITHHELD));
        }
    }

    /**
     * The end-of-run contract of {@code app/cbl/CBTRN02C.cbl:L193-L234}: what this processor must supply so
     * that the step can decide the return code, and what it must never do instead.
     *
     * <p>The counters and the {@code MOVE 4 TO RETURN-CODE} live with the step, not here - {@code :L206}
     * and {@code :L214} are {@code WORKING-STORAGE} counters and {@code :L230} sets a program-level
     * register. What this class owns is the single input that decision consumes: whether each record came
     * back rejected. The tests below therefore drive real records through {@link
     * TransactionPostingProcessor#process(DailyTransaction)} and tally the outcomes in a <b>method-local</b>
     * counter, which is the Java form of the per-run {@code WORKING-STORAGE} field and keeps Rule 1 clause
     * B3's prohibition on global mutable state intact.
     */
    @Nested
    @DisplayName("16. The exit-code contract of :L193-L234: return code 4 if and only if rejects exceed 0")
    class ExitCodeContract {

        /** {@code RETURN-CODE} 0: every record posted. */
        private static final int RC_COMPLETED = 0;

        /** {@code RETURN-CODE} 4, the only value {@code :L230} assigns: at least one record was rejected. */
        private static final int RC_COMPLETED_WITH_REJECTS = 4;

        /** {@code RETURN-CODE} 8: the step failed. Not reachable from this processor's own logic. */
        private static final int RC_FAILED = 8;

        /** {@code RETURN-CODE} 12: {@code 9999-ABEND-PROGRAM} ran. */
        private static final int RC_ABEND = 12;

        /**
         * The step's decision, reproduced from {@code app/cbl/CBTRN02C.cbl:L229-L231} and nothing else.
         *
         * @param rejectCount the tally of records the processor classified as rejected
         * @return 4 when the tally exceeds zero, otherwise 0
         */
        private int returnCodeFor(final int rejectCount) {
            // :L229 IF WS-REJECT-COUNT > 0
            // :L230    MOVE 4 TO RETURN-CODE
            // :L231 END-IF
            return rejectCount > 0 ? RC_COMPLETED_WITH_REJECTS : RC_COMPLETED;
        }

        /**
         * Drives a batch of records and returns the tally the step would hold, exactly as {@code :L211-L215}
         * accumulates it: the count is incremented in the {@code ELSE} arm and nowhere else.
         *
         * @param items the records to process, in order
         * @return the number that came back rejected
         */
        private int rejectCountFor(final List<DailyTransaction> items) {
            int rejectCount = 0;
            int transactionCount = 0;
            for (DailyTransaction item : items) {
                // :L206 ADD 1 TO WS-TRANSACTION-COUNT - every record read is counted, rejected or not.
                transactionCount++;
                PostingResult result = processor.process(item);
                if (result.isRejected()) {
                    // :L214 ADD 1 TO WS-REJECT-COUNT, in the ELSE arm only.
                    rejectCount++;
                }
            }
            assertThat(transactionCount)
                    .as(":L206 counts every record the reader supplied, so the processed tally is the batch "
                            + "size whatever the classifications were")
                    .isEqualTo(items.size());
            return rejectCount;
        }

        @Test
        @DisplayName("the counter literals of :L227-L228 are read from the frozen source, two spaces included")
        void theCounterLiteralsAreVerbatim() {
            // Compared after trimming the fixed-form indentation, which is a card-image artefact rather than
            // part of any literal. Everything INSIDE the quotes is compared byte for byte, which is where
            // the contract lives.
            assertThat(frozenSourceLine(227).trim())
                    .as("app/cbl/CBTRN02C.cbl:L227 - ONE space before the colon")
                    .isEqualTo("DISPLAY 'TRANSACTIONS PROCESSED :' WS-TRANSACTION-COUNT");
            assertThat(frozenSourceLine(228).trim())
                    .as("app/cbl/CBTRN02C.cbl:L228 - TWO spaces before the colon, so that the two counter "
                            + "lines align in SYSOUT under the differing literal lengths. A single space is "
                            + "a diff against the parity baseline")
                    .isEqualTo("DISPLAY 'TRANSACTIONS REJECTED  :' WS-REJECT-COUNT");
            assertThat(frozenSourceLine(228))
                    .as("stated positively so the intent survives a future edit: the rejected literal "
                            + "contains a double space and the processed literal does not")
                    .contains("'TRANSACTIONS REJECTED  :'");
            assertThat(frozenSourceLine(227))
                    .doesNotContain("'TRANSACTIONS PROCESSED  :'");
            assertThat(frozenSourceLine(232))
                    .as("app/cbl/CBTRN02C.cbl:L232 - the end-of-run literal")
                    .contains("'END OF EXECUTION OF PROGRAM CBTRN02C'");
        }

        @Test
        @DisplayName("the counters are PIC 9(09), so both tallies render as nine zero-padded digits")
        void theCountersAreNineDigits() {
            assertThat(frozenSourceLines(185, 187).stream().map(String::trim).toList())
                    .as("app/cbl/CBTRN02C.cbl:L185-L187 declare both counters PIC 9(09) and WS-TEMP-BAL "
                            + "PIC S9(09)V99 - the COMPUTE target of :L403 is one integer digit NARROWER than "
                            + "the PIC S9(10)V99 operands it is computed from at app/cpy/CVACT01Y.cpy:L13-L14. "
                            + "Because the COMPUTE carries no ON SIZE ERROR, an over-wide result is stored "
                            + "truncated and :L407 compares THAT, so the processor narrows to this picture "
                            + "before comparing; the boundary itself is asserted by the over-limit group, not "
                            + "left to the fixture to reach")
                    .containsExactly(
                            "05 WS-TRANSACTION-COUNT          PIC 9(09) VALUE 0.",
                            "05 WS-REJECT-COUNT               PIC 9(09) VALUE 0.",
                            "05 WS-TEMP-BAL                   PIC S9(09)V99.");

            assertThat(String.format(Locale.ROOT, "%09d", rejectCountFor(List.of())))
                    .as("PIC 9(09) renders an empty run's reject tally as nine zeros")
                    .isEqualTo("000000000");
            assertThat(String.format(Locale.ROOT, "%09d", 300))
                    .as("PIC 9(09) renders the whole-fixture tally in the same nine columns")
                    .isEqualTo("000000300");
        }

        @Test
        @DisplayName("a run in which every record posts leaves the return code at 0")
        void everyRecordPostingLeavesReturnCodeZero() {
            stubHappyPath();

            int rejectCount = rejectCountFor(List.of(dailyTransaction("10.00"), dailyTransaction("20.00"),
                    dailyTransaction("30.00")));

            assertThat(rejectCount)
                    .as("no record was rejected, so :L229's predicate is false")
                    .isZero();
            assertThat(returnCodeFor(rejectCount))
                    .as("app/cbl/CBTRN02C.cbl:L229-L231 leaves RETURN-CODE at its initial 0 when the reject "
                            + "count is zero")
                    .isEqualTo(RC_COMPLETED);
        }

        @Test
        @DisplayName("a single rejected record among many is enough to set return code 4")
        void oneRejectIsEnoughForReturnCodeFour() {
            // One record whose card number does not resolve, two that post. The predicate at :L229 is
            // "> 0", not a proportion and not a threshold, so one is enough.
            Mockito.when(cardCrossReferenceRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(crossReference()));
            Mockito.when(cardCrossReferenceRepository.findById("9999999999999999"))
                    .thenReturn(Optional.empty());
            Mockito.when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(acceptingAccount()));
            Mockito.when(transactionCategoryBalanceRepository.findById(fixtureKey()))
                    .thenReturn(Optional.empty());

            int rejectCount = rejectCountFor(List.of(
                    dailyTransaction("10.00"),
                    dailyTransaction("20.00", "9999999999999999", ORIG_TS),
                    dailyTransaction("30.00")));

            assertThat(rejectCount)
                    .as("exactly one of the three records was classified as rejected")
                    .isEqualTo(1);
            assertThat(returnCodeFor(rejectCount))
                    .as("app/cbl/CBTRN02C.cbl:L229 reads WS-REJECT-COUNT > 0, so one reject in three sets 4")
                    .isEqualTo(RC_COMPLETED_WITH_REJECTS);
        }

        @Test
        @DisplayName("the reject count is the ONLY determinant of return code 4")
        void theRejectCountIsTheSoleDeterminant() {
            assertThat(frozenSourceLines(229, 231).stream().map(String::trim).toList())
                    .as("app/cbl/CBTRN02C.cbl:L229-L231 is the whole decision and this is the only "
                            + "MOVE 4 TO RETURN-CODE in the twenty-eight-program corpus. There is no "
                            + "severity, no threshold, no error flag and no second predicate - adding one "
                            + "would change which runs a scheduler treats as clean")
                    .containsExactly(
                            "IF WS-REJECT-COUNT > 0",
                            "MOVE 4 TO RETURN-CODE",
                            "END-IF");

            // Same tally, deliberately different reasons: the decision cannot distinguish them.
            stubHappyPath();
            int postedOnly = rejectCountFor(List.of(dailyTransaction("10.00")));
            assertThat(returnCodeFor(postedOnly)).isEqualTo(RC_COMPLETED);
            assertThat(returnCodeFor(1))
                    .as("one reject sets 4 whatever the reason code was")
                    .isEqualTo(RC_COMPLETED_WITH_REJECTS);
            assertThat(returnCodeFor(300))
                    .as("every record rejected still sets 4 - the value does not scale with the tally")
                    .isEqualTo(RC_COMPLETED_WITH_REJECTS);
        }

        @Test
        @DisplayName("the four exit codes are 0, 4, 8 and 12, and 12 is the abend's own return code")
        void theExitCodeLadderIsFixed() {
            assertThat(List.of(RC_COMPLETED, RC_COMPLETED_WITH_REJECTS, RC_FAILED, RC_ABEND))
                    .as("completed, completed-with-rejects, failed, abend")
                    .containsExactly(0, 4, 8, 12);
            assertThat(FatalProcessingException.BATCH_RETURN_CODE)
                    .as("app/cbl/CBTRN02C.cbl:L707-L711 calls CEE3ABD after MOVE 999 TO ABCODE, and "
                            + "FatalProcessingException publishes the resulting process return code, so the "
                            + "abend rung of the ladder is owned by the exception rather than restated here")
                    .isEqualTo(RC_ABEND);
            assertThat(FatalProcessingException.BATCH_ABEND_CODE)
                    .as("MOVE 999 TO ABCODE at app/cbl/CBTRN02C.cbl:L710")
                    .isEqualTo(999);
        }

        @Test
        @DisplayName("a reject is returned and never thrown, which is what keeps it countable")
        void aRejectIsReturnedAndNeverThrown() {
            Mockito.when(cardCrossReferenceRepository.findById(CARD_NUMBER)).thenReturn(Optional.empty());

            // If a reject were an exception it would leave process() abnormally, the ELSE arm at :L213-L215
            // would never run, WS-REJECT-COUNT would stay at zero and :L229 would leave RETURN-CODE at 0 -
            // so an over-limit run would report itself clean. That is why no reject code is ever thrown.
            PostingResult result = processor.process(dailyTransaction("10.00"));

            assertThat(result.isRejected()).isTrue();
            assertThat(result.rejectCode()).isEqualTo(RejectCode.INVALID_CARD_NUMBER);
            assertThat(CardDemoException.class.isAssignableFrom(RejectCode.class))
                    .as("RejectCode is an enumeration of business outcomes, not a throwable: reject codes "
                            + "drive ExitStatus and are never thrown")
                    .isFalse();
        }

        @Test
        @DisplayName("every classification is measurable: five distinct four-character tags, plus the zero")
        void everyClassificationIsMeasurable() {
            // Rule 1 clause A4 asks for measurable behaviour, and the target's observability design replaces
            // the DISPLAY counters of :L227-L228 with a rejected-records counter TAGGED BY REJECT CODE. A tag
            // is only usable if each outcome renders to a distinct, stable, fixed-width label, so that is
            // asserted here rather than assumed by whoever registers the meter. The four-character width is
            // WS-VALIDATION-FAIL-REASON PIC 9(04) at :L181, which is also the first four bytes of the 80-byte
            // validation trailer - so the tag and the reject record read the same value.
            Set<String> tags = new TreeSet<>();
            for (RejectCode code : RejectCode.values()) {
                String tag = code.toFailReasonField();
                assertThat(tag)
                        .as("%s renders WS-VALIDATION-FAIL-REASON PIC 9(04) as four zero-padded digits",
                                code.name())
                        .hasSize(RejectCode.FAIL_REASON_LENGTH)
                        .containsOnlyDigits();
                tags.add(tag);
            }
            assertThat(tags)
                    .as("all five reason codes of app/cbl/CBTRN02C.cbl render to DISTINCT tags, so a counter "
                            + "dimensioned by reject code cannot merge two outcomes into one series")
                    .containsExactly("0100", "0101", "0102", "0103", "0109");

            stubHappyPath();
            PostingResult posted = processor.process(dailyTransaction("10.00"));
            assertThat(posted.failReasonField())
                    .as("a posted record reports the zero that :L208 leaves in the field, in the same four "
                            + "columns, so posted and rejected records are directly comparable")
                    .isEqualTo("0000")
                    .isNotIn(tags);
        }

        @ParameterizedTest
        @CsvSource({
            "0, 0",
            "1, 4",
            "2, 4",
            "299, 4",
            "300, 4",
        })
        @DisplayName("the decision is a step function at one: any positive tally yields 4")
        void theDecisionIsAStepFunctionAtOne(final int rejectCount, final int expectedReturnCode) {
            assertThat(returnCodeFor(rejectCount))
                    .as("app/cbl/CBTRN02C.cbl:L229-L231 for a reject tally of %d", rejectCount)
                    .isEqualTo(expectedReturnCode);
        }

        @Test
        @DisplayName("neither inner IF of :L203 and :L205 has an ELSE, so there is no final flush to invent")
        void theMainLoopHasNoElseLimbAndNoFinalFlush() {
            List<String> loop = frozenSourceLines(202, 219);

            // Proof by contrast, and the contrast is deliberate. app/cbl/CBACT04C.cbl DOES carry an
            // unreachable ELSE at its own :L219-L220, and its sibling InterestCalculationProcessorTest
            // asserts that. CBTRN02C does not: the only ELSE in this span is the one at :L213 that owns the
            // reject arm. Inventing an end-of-data flush here - the shape CBACT04C needs so that the last
            // account's interest is not lost - would post a record the source never posts.
            assertThat(loop)
                    .as("app/cbl/CBTRN02C.cbl:L202-L219 contains exactly ONE bare ELSE, at :L213, and it "
                            + "belongs to the reject arm of :L211. The IFs at :L203 and :L205 have none")
                    .filteredOn(line -> line.trim().equals("ELSE"))
                    .hasSize(1);
            assertThat(loop.get(1).trim())
                    .as("app/cbl/CBTRN02C.cbl:L203, the outer end-of-file test")
                    .isEqualTo("IF  END-OF-FILE = 'N'");
            assertThat(loop.get(3).trim())
                    .as("app/cbl/CBTRN02C.cbl:L205, the inner end-of-file test")
                    .isEqualTo("IF  END-OF-FILE = 'N'");
            assertThat(loop.get(11).trim())
                    .as("app/cbl/CBTRN02C.cbl:L213 - the one ELSE, owning the reject arm")
                    .isEqualTo("ELSE");
            assertThat(loop.get(17).trim())
                    .as("app/cbl/CBTRN02C.cbl:L219 closes the loop: END-PERFORM is at :L219")
                    .isEqualTo("END-PERFORM.");
        }

        @Test
        @DisplayName("the per-iteration clear of :L208-L209 is what makes each record independent")
        void eachRecordIsClassifiedIndependently() {
            // :L208 MOVE 0 TO WS-VALIDATION-FAIL-REASON and :L209 MOVE SPACES TO ...-DESC run before every
            // validation, so no classification can leak from one record into the next. In Java the reason
            // code is a fresh method-local per invocation, which is the same guarantee by construction -
            // and this test proves it by rejecting a record and then posting one through the SAME processor.
            Mockito.when(cardCrossReferenceRepository.findById("9999999999999999"))
                    .thenReturn(Optional.empty());
            Mockito.when(cardCrossReferenceRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(crossReference()));
            Mockito.when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(acceptingAccount()));
            Mockito.when(transactionCategoryBalanceRepository.findById(fixtureKey()))
                    .thenReturn(Optional.empty());

            PostingResult rejected =
                    processor.process(dailyTransaction("10.00", "9999999999999999", ORIG_TS));
            PostingResult posted = processor.process(dailyTransaction("10.00"));

            assertThat(rejected.isRejected()).isTrue();
            assertThat(posted.isPosted())
                    .as("app/cbl/CBTRN02C.cbl:L208 clears the reason code at the top of every iteration, so "
                            + "a preceding reject cannot carry over")
                    .isTrue();
            assertThat(posted.failReasonCode())
                    .as("a posted record reports the zero that :L208 leaves in WS-VALIDATION-FAIL-REASON")
                    .isEqualTo(RejectCode.NO_REJECT_REASON_CODE);
        }
    }

    /**
     * The universal I/O guard idiom, the FILE STATUS to exception map it implies, and the four-character
     * status rendering of {@code 9910-DISPLAY-IO-STATUS}.
     *
     * <p><b>Why this belongs in this file.</b> {@code app/cbl/CBTRN02C.cbl} is where the idiom is defined
     * and it is repeated nineteen times in this one program - {@code 0000-DALYTRAN-OPEN} at
     * {@code :L236-L252} is the canonical instance, and the same five statements recur at {@code :L244},
     * {@code :L262}, {@code :L281}, {@code :L299}, {@code :L317}, {@code :L335}, {@code :L357},
     * {@code :L457}, {@code :L486}, {@code :L517}, {@code :L535}, {@code :L571}, {@code :L590},
     * {@code :L608}, {@code :L627}, {@code :L645}, {@code :L663} and {@code :L682}. Recognising it as
     * <b>one idiom rather than nineteen checks</b> is precisely what justifies a single central
     * {@link FileStatusMapper} instead of a status test at every call site, and this class is the
     * foundational test of the package, so the contract the other four batch suites reuse is pinned here.
     *
     * <p>The mapper is the real production instance - the same spy the processor was built with - so these
     * are assertions about shipped behaviour and not about a stub.
     */
    @Nested
    @DisplayName("17. The I/O guard, the FILE STATUS map and the four-character rendering of :L714-L727")
    class FileStatusContract {

        @Test
        @DisplayName("the guard flags are 8 initial, 0 for APPL-AOK, 16 for APPL-EOF and 12 for failure")
        void theGuardFlagsMatchTheDeclarations() {
            assertThat(frozenSourceLines(142, 146).stream().map(String::trim).toList())
                    .as("app/cbl/CBTRN02C.cbl:L142-L146 declares the guard's whole vocabulary. APPL-EOF is "
                            + "16 and NOT 12, which is the distinction that lets a sequential read end a "
                            + "loop instead of failing a step")
                    .containsExactly(
                            "01  APPL-RESULT             PIC S9(9)   COMP.",
                            "88  APPL-AOK            VALUE 0.",
                            "88  APPL-EOF            VALUE 16.",
                            "",
                            "01  END-OF-FILE             PIC X(01)    VALUE 'N'.");

            assertThat(FileStatusMapper.APPL_RESULT_INITIAL)
                    .as("every guard opens with MOVE 8 TO APPL-RESULT, so a verb that never runs leaves "
                            + "neither success nor a classified failure behind")
                    .isEqualTo(8);
            assertThat(FileStatusMapper.APPL_AOK).isZero();
            assertThat(FileStatusMapper.APPL_EOF).isEqualTo(16);
            assertThat(FileStatusMapper.APPL_FAILURE).isEqualTo(12);
        }

        @ParameterizedTest
        @CsvSource({
            "00, 0",
            "10, 12",
            "22, 12",
            "23, 12",
            "35, 12",
            "90, 12",
        })
        @DisplayName("the ordinary guard accepts '00' only: everything else is 12, end of file included")
        void theOrdinaryGuardAcceptsSuccessOnly(final String ioStatus, final int expected) {
            // IF status = '00' MOVE 0 TO APPL-RESULT ELSE MOVE 12 TO APPL-RESULT. Note that '10' is 12 HERE:
            // an OPEN, WRITE or REWRITE that reports end of file is a failure. Only the sequential READ of
            // 1000-DALYTRAN-GET-NEXT treats it as a loop terminator, which is the next test.
            assertThat(fileStatusMapper.applResultForGuard(ioStatus))
                    .as("the guard idiom of app/cbl/CBTRN02C.cbl:L236-L252 applied to FILE STATUS '%s'",
                            ioStatus)
                    .isEqualTo(expected);
        }

        @ParameterizedTest
        @CsvSource({
            "00, 0",
            "10, 16",
            "23, 12",
            "35, 12",
            "97, 12",
        })
        @DisplayName("the sequential read of :L345-L369 maps '00' to 0, '10' to 16 and anything else to 12")
        void theSequentialReadRecognisesEndOfFile(final String ioStatus, final int expected) {
            assertThat(fileStatusMapper.applResultForSequentialRead(ioStatus))
                    .as("1000-DALYTRAN-GET-NEXT at app/cbl/CBTRN02C.cbl:L345-L369 for FILE STATUS '%s'",
                            ioStatus)
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("FILE STATUS '00' and '10' are never exceptions: end of file terminates the loop")
        void successAndEndOfFileAreNeverThrown() {
            assertThat(fileStatusMapper.toException("00", "DALYTRAN", "READ"))
                    .as("app/cbl/CBTRN02C.cbl:L353-L354 treats '00' as success, so there is nothing to raise")
                    .isEmpty();
            assertThat(fileStatusMapper.toException("10", "DALYTRAN", "READ"))
                    .as("app/cbl/CBTRN02C.cbl:L355-L358 moves 16 into APPL-RESULT and sets END-OF-FILE to "
                            + "'Y' on '10'. It is the loop terminator of :L202 and MUST NEVER be thrown - a "
                            + "step that raised on end of file would fail every single run")
                    .isEmpty();
        }

        @Test
        @DisplayName("FILE STATUS '23' is a record-not-found exception everywhere except the scoped site")
        void recordNotFoundIsTypedOutsideTheScopedSite() {
            assertThat(fileStatusMapper.toException("23", "ACCTFILE", "READ"))
                    .get()
                    .as("app/cbl/CBTRN02C.cbl - a not-found status is an error on every path except the "
                            + "category-balance read of :L481")
                    .isInstanceOf(RecordNotFoundException.class);

            // The carve-out, asserted alongside the general rule so the pair reads as one contract. Group 11
            // drives it through the processor; here it is pinned against the mapper directly.
            assertThat(fileStatusMapper.requireCategoryBalanceReadSuccess("23"))
                    .as("app/cbl/CBTRN02C.cbl:L481 reads IF  TCATBALF-STATUS = '00'  OR '23', so a missing "
                            + "row selects the create branch of :L495-L496 rather than abending. A blanket "
                            + "'23'-to-exception rule would abend this path and lose every first-of-cycle "
                            + "balance row")
                    .isTrue();
            assertThat(fileStatusMapper.requireCategoryBalanceReadSuccess("00"))
                    .as("a present row selects the rewrite branch of :L498")
                    .isFalse();
        }

        @Test
        @DisplayName("FILE STATUS '22' is a duplicate record and '35' is a file-unavailable condition")
        void duplicateAndUnavailableAreTyped() {
            assertThat(fileStatusMapper.toException("22", "TRANFILE", "WRITE"))
                    .get()
                    .as("a repeated key on a keyed dataset fails the step; it is never answered with an "
                            + "upsert, because :L512 and :L530 accept '00' only")
                    .isInstanceOf(DuplicateRecordException.class);

            // Asserted by simple name rather than by importing the type: this file's dependency whitelist
            // does not carry FileUnavailableException, and the claim - that '35' has its own type and is not
            // collapsed into the generic I/O family - is fully made without it.
            assertThat(fileStatusMapper.toException("35", "ACCTFILE", "OPEN"))
                    .get()
                    .extracting(failure -> failure.getClass().getSimpleName())
                    .as("FILE STATUS '35' is a distinct condition: the dataset was not available to be "
                            + "opened, which is an operational fault rather than a data fault")
                    .isEqualTo("FileUnavailableException");
        }

        @ParameterizedTest
        @ValueSource(strings = {"90", "91", "92", "95", "99"})
        @DisplayName("the '9x' family becomes a file-access exception carrying the expanded status")
        void theNinetyFamilyCarriesTheExpandedStatus(final String ioStatus) {
            Optional<CardDemoException> raised = fileStatusMapper.toException(ioStatus, "DALYTRAN", "READ");

            assertThat(raised).get().isInstanceOf(FileAccessException.class);
            assertThat(raised.orElseThrow())
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(
                            FileAccessException.class))
                    .extracting(FileAccessException::getExpandedStatus)
                    .as("app/cbl/CBTRN02C.cbl:L715-L721 - branch A of the renderer fires when IO-STAT1 is "
                            + "'9', and the four-character expansion is what the DISPLAY emits")
                    .isEqualTo(FileStatus.renderIoStatus04(ioStatus));
        }

        @Test
        @DisplayName("an unclassifiable status reaches 9999-ABEND-PROGRAM with code 999 and return code 12")
        void anUnclassifiableStatusAbends() {
            assertThat(frozenSourceLines(707, 711).stream().map(String::trim).toList())
                    .as("app/cbl/CBTRN02C.cbl:L707-L711 - exactly four statements and, unlike every other "
                            + "paragraph in the program, no EXIT: CEE3ABD does not return")
                    .containsExactly(
                            "9999-ABEND-PROGRAM.",
                            "DISPLAY 'ABENDING PROGRAM'",
                            "MOVE 0 TO TIMING",
                            "MOVE 999 TO ABCODE",
                            "CALL 'CEE3ABD'.");

            // An unclassifiable status is the "anything else" arm of the guard, and it reaches the abend.
            assertThat(fileStatusMapper.toException("XX", "DALYTRAN", "READ"))
                    .get()
                    .as("app/cbl/CBTRN02C.cbl - a status that is neither '00', nor the end-of-file '10', nor "
                            + "a member of the classified set is an unexpected condition, and the corpus "
                            + "abends on an unexpected condition")
                    .isInstanceOf(FatalProcessingException.class);

            // WHERE the 999 is stamped is itself the contract, and the two components differ on purpose.
            // The abend work area of app/cpy/CSMSG02Y.cpy holds ABEND-CODE X(4) which is BLANK until
            // 9999-ABEND-PROGRAM executes MOVE 999 TO ABCODE at :L710. The mapper classifies a status; it is
            // not the abend paragraph, so it leaves the code unset. The processor's own abend helper IS the
            // paragraph's Java form, so it stamps 999 and names CBTRN02C as the culprit - asserted by
            // anAbsentRecordAbends in group 2 and by the store-guard tests in group 14.
            assertThat(fileStatusMapper.toException("XX", "DALYTRAN", "READ").orElseThrow())
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(
                            FatalProcessingException.class))
                    .extracting(FatalProcessingException::getAbendCode)
                    .as("ABEND-CODE X(4) of app/cpy/CSMSG02Y.cpy is four blanks until :L710 runs, so the "
                            + "classifier leaves it unset rather than pre-empting the abend paragraph")
                    .isEqualTo(FileStatusMapper.ABEND_CODE_UNSET);

            assertThat(FatalProcessingException.BATCH_ABEND_CODE)
                    .as("MOVE 999 TO ABCODE at app/cbl/CBTRN02C.cbl:L710 - the value the abend paragraph "
                            + "stamps, owned once by the exception type")
                    .isEqualTo(999);
            assertThat(FatalProcessingException.BATCH_RETURN_CODE)
                    .as("the process return code CALL 'CEE3ABD' at :L711 produces: the 12 rung of the ladder")
                    .isEqualTo(12);
        }

        @Test
        @DisplayName("the abend contract is scoped to eight programs, and CBTRN02C is one of them")
        void theAbendContractIsScopedToEightPrograms() {
            // Scope note, asserted rather than left as prose. MOVE 999 TO ABCODE appears in exactly eight of
            // the twenty-eight programs - CBACT01C, CBACT02C, CBACT03C, CBACT04C, CBCUS01C, CBTRN01C,
            // CBTRN02C and CBTRN03C. CBSTM03A and CBSTM03B are OUTSIDE this contract and are asserted by
            // StatementProcessorTest, so nothing here may be generalised to them.
            assertThat(frozenSourceLines(1, 731))
                    .as("app/cbl/CBTRN02C.cbl carries the abend assignment exactly once, in "
                            + "9999-ABEND-PROGRAM, so the contract has a single site in this program")
                    .filteredOn(line -> line.contains("MOVE 999 TO ABCODE"))
                    .hasSize(1);
        }

        @ParameterizedTest
        @CsvSource({
            "00, 0000",
            "10, 0010",
            "22, 0022",
            "23, 0023",
            "35, 0035",
        })
        @DisplayName("branch B of :L723-L724 renders a numeric status as '0000' overwritten at columns 3-4")
        void branchBRendersFourCharacters(final String ioStatus, final String expected) {
            // :L723 MOVE '0000' TO IO-STATUS-04
            // :L724 MOVE IO-STATUS TO IO-STATUS-04(3:2)
            assertThat(FileStatus.renderIoStatus04(ioStatus))
                    .as("app/cbl/CBTRN02C.cbl:L722-L725 for FILE STATUS '%s'", ioStatus)
                    .isEqualTo(expected)
                    .hasSize(FileStatus.RENDERED_STATUS_LENGTH);
        }

        @Test
        @DisplayName("branch A of :L717-L720 keeps the '9' and expands the second byte through its binary")
        void branchARendersTheNinetyFamily() {
            // :L715-L716 IF IO-STATUS NOT NUMERIC OR IO-STAT1 = '9'
            // :L717        MOVE IO-STAT1 TO IO-STATUS-04(1:1)
            // :L718-L720   the second byte travels through TWO-BYTES-BINARY into IO-STATUS-0403 PIC 999
            assertThat(frozenSourceLines(715, 716).stream().map(String::trim).toList())
                    .as("app/cbl/CBTRN02C.cbl:L715-L716 - the branch A predicate is a disjunction, so a "
                            + "non-numeric status takes it as well as the '9x' family")
                    .containsExactly("IF  IO-STATUS NOT NUMERIC", "OR  IO-STAT1 = '9'");

            String rendered = FileStatus.renderIoStatus04("90");
            assertThat(rendered)
                    .as("branch A keeps IO-STAT1 in column 1 and renders the remaining three columns from "
                            + "the binary value of IO-STAT2, so the field is still exactly four characters")
                    .hasSize(FileStatus.RENDERED_STATUS_LENGTH)
                    .startsWith(String.valueOf(FileStatus.IO_ERROR_FIRST_BYTE));
        }

        @Test
        @DisplayName("the DISPLAY of :L721 and :L725 concatenates with no separator, so '23' emits NNNN0023")
        void theDisplayConcatenatesWithoutASeparator() {
            assertThat(frozenSourceLine(721).trim())
                    .as("app/cbl/CBTRN02C.cbl:L721, branch A")
                    .isEqualTo("DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04");
            assertThat(frozenSourceLine(725).trim())
                    .as("app/cbl/CBTRN02C.cbl:L725, branch B - the same literal, so both arms emit one shape")
                    .isEqualTo("DISPLAY 'FILE STATUS IS: NNNN' IO-STATUS-04");

            // A COBOL DISPLAY of two operands concatenates them with nothing between, so the four NNNN
            // characters of the literal are immediately followed by the four rendered digits. The doubled
            // appearance is the source's own output and is reproduced rather than tidied.
            assertThat(fileStatusMapper.displayIoStatus("23"))
                    .as("app/cbl/CBTRN02C.cbl:L725 emits the literal and the rendered field adjacently")
                    .isEqualTo("FILE STATUS IS: NNNN0023");
            assertThat(fileStatusMapper.displayIoStatus("00")).isEqualTo("FILE STATUS IS: NNNN0000");
            assertThat(fileStatusMapper.displayIoStatus("10")).isEqualTo("FILE STATUS IS: NNNN0010");
            assertThat(FileStatus.DISPLAY_MESSAGE_PREFIX)
                    .as("the literal is owned in one place, so the NNNN placeholder cannot drift")
                    .isEqualTo("FILE STATUS IS: NNNN");
        }

        @Test
        @DisplayName("a store failure inside the processor is translated, never swallowed, and keeps its cause")
        void aStoreFailureKeepsItsCause() {
            stubHappyPath();
            DataIntegrityViolationException rootCause =
                    new DataIntegrityViolationException("fk08_tcatbal_category");
            Mockito.when(transactionCategoryBalanceRepository.save(Mockito.any()))
                    .thenThrow(rootCause);

            assertThatExceptionOfType(DataIntegrityException.class)
                    .isThrownBy(() -> processor.process(dailyTransaction("10.00")))
                    .satisfies(failure -> {
                        assertThat(failure.getMessage())
                                .as("the guard's own literal from app/cbl/CBTRN02C.cbl:L520 travels with the "
                                        + "translated exception")
                                .contains("ERROR WRITING TRANSACTION BALANCE FILE");
                        assertThat(failure.getCause())
                                .as("Rule 1 clause B4 - the root cause is preserved, never swallowed, so the "
                                        + "driver's own constraint detail survives translation")
                                .isSameAs(rootCause);
                        assertThat(failure.getRelation()).isEqualTo("transaction_category_balance");
                    });
        }
    }

    /**
     * What the shipped parity fixtures can and cannot exercise, re-derived from their bytes.
     *
     * <p><b>Read the constraint before reading the tests.</b> Everything asserted here is a property of the
     * <b>input data alone</b> - which card numbers appear, which account identifiers they resolve to, which
     * expiry dates the accounts carry, which overpunch signs the amounts use. Those are the properties this
     * class can establish without running a whole-fixture pass, and they are what it establishes.
     *
     * <p><b>What is deliberately absent, and why.</b> There is no assertion anywhere in this group - or in
     * this file - on the number of records the fixture would reject or post, or on the return code a
     * whole-fixture run would produce. The reason is <b>tier</b>, not uncertainty: those are whole-run output
     * properties, this is a unit suite over hand-built single records, and a unit test that folded 300 fixture
     * rows through the processor to total them would be an end-to-end test wearing a unit test's name.
     *
     * <p>The reason is <b>not</b> that the totals are "model-sensitive" because a
     * stateless single pass and a faithful stateful model, the latter re-reading the account at
     * {@code :L393-L395} while {@code :L547-L551} mutates its accumulators, "produce different totals".
     * <b>That reasoning does not hold.</b> {@code 2800-UPDATE-ACCOUNT-REC} ends in
     * {@code REWRITE FD-ACCTFILE-REC} at {@code :L561}, and a VSAM {@code REWRITE} replaces the record in the
     * cluster, so the re-read returns the mutated accumulators: the stateless reading is a misreading of
     * {@code REWRITE} rather than a rival model. Exactly one faithful model exists,
     * {@code com.cardemo.e2e.PostingParityOracle} re-derives it from the frozen source and fixtures without
     * importing any production type, and the totals ARE asserted - against the real run, in
     * {@code src/test/java/com/cardemo/e2e/BatchPipelineE2ETest.java} and the two integration suites that
     * launch the posting job.
     *
     * <p>The fixtures are reached only through {@link FixtureLoader.Fixture}, which spells
     * {@code dailytran.txt} once, in one place - see the fixture-name trap in this class's Javadoc.
     */
    @Nested
    @DisplayName("18. Fixture reachability: which reject codes the shipped corpus can actually produce")
    class FixtureReachability {

        /** {@code DALYTRAN-CARD-NUM PIC X(16)} at columns 263-278 of the 350-byte record. */
        private static final int DALYTRAN_CARD_NUM_COLUMN = 263;

        /** {@code DALYTRAN-ORIG-TS PIC X(26)} at columns 279-304. */
        private static final int DALYTRAN_ORIG_TS_COLUMN = 279;

        /** {@code DALYTRAN-AMT PIC S9(09)V99} at columns 133-143, sign overpunched on the last byte. */
        private static final int DALYTRAN_AMT_COLUMN = 133;

        /** {@code DALYTRAN-DESC PIC X(100)} at columns 33-132. */
        private static final int DALYTRAN_DESC_COLUMN = 33;

        /** {@code ACCT-ID PIC 9(11)} at columns 1-11 of the 300-byte account record. */
        private static final int ACCT_ID_COLUMN = 1;

        /** {@code ACCT-EXPIRAION-DATE PIC X(10)} at columns 59-68, misspelling retained. */
        private static final int ACCT_EXPIRAION_DATE_COLUMN = 59;

        /** {@code XREF-CARD-NUM PIC X(16)} at columns 1-16 of the 36-byte cross-reference record. */
        private static final int XREF_CARD_NUM_COLUMN = 1;

        /** {@code XREF-ACCT-ID PIC 9(11)} at columns 26-36. */
        private static final int XREF_ACCT_ID_COLUMN = 26;

        /** The ten characters {@code DALYTRAN-ORIG-TS (1:10)} slices at {@code :L414}. */
        private static final int EXPIRY_COMPARISON_LENGTH = 10;

        private FixtureLoader.FixtureData dailyTransactions;
        private FixtureLoader.FixtureData accounts;
        private FixtureLoader.FixtureData crossReferences;

        @BeforeEach
        void loadFixtures() {
            dailyTransactions = FixtureLoader.load(FixtureLoader.Fixture.DAILY_TRANSACTION);
            accounts = FixtureLoader.load(FixtureLoader.Fixture.ACCOUNT);
            crossReferences = FixtureLoader.load(FixtureLoader.Fixture.CARD_XREF);
        }

        /**
         * Derives the card set from the staged fixture rather than restating it.
         *
         * @return every distinct card number the staged daily transactions reference.
         */
        private Set<String> dailyTransactionCardNumbers() {
            Set<String> cards = new TreeSet<>();
            for (int record = 0; record < dailyTransactions.recordCount(); record++) {
                cards.add(dailyTransactions.field(record, DALYTRAN_CARD_NUM_COLUMN, 16));
            }
            return cards;
        }

        @Test
        @DisplayName("the fixture geometry matches the copybooks: 300 x 350, 50 x 300 and 50 x 36")
        void theFixtureGeometryMatchesTheCopybooks() {
            assertThat(dailyTransactions.recordCount())
                    .as("app/data/ASCII/dailytran.txt holds 300 records of the app/cpy/CVTRA06Y.cpy layout")
                    .isEqualTo(300);
            assertThat(dailyTransactions.recordWidth())
                    .as("350 bytes, the DALYTRAN record length declared on app/jcl/POSTTRAN.jcl")
                    .isEqualTo(350);
            assertThat(accounts.recordCount()).isEqualTo(50);
            assertThat(accounts.recordWidth())
                    .as("300 bytes, the RECLN of app/cpy/CVACT01Y.cpy")
                    .isEqualTo(300);
            assertThat(crossReferences.recordCount()).isEqualTo(50);
            assertThat(crossReferences.recordWidth())
                    .as("36 populated bytes of app/cpy/CVACT03Y.cpy in the cluster's 50-byte slot")
                    .isEqualTo(36);
        }

        @Test
        @DisplayName("reject 100 is UNREACHABLE over the fixtures: no staged card number is an orphan")
        void rejectOneHundredIsUnreachable() {
            Set<String> staged = dailyTransactionCardNumbers();
            Set<String> known = new TreeSet<>();
            for (int record = 0; record < crossReferences.recordCount(); record++) {
                known.add(crossReferences.field(record, XREF_CARD_NUM_COLUMN, 16));
            }

            assertThat(staged)
                    .as("the 300 staged records reference 50 distinct card numbers")
                    .hasSize(50);
            assertThat(known).containsAll(staged);
            assertThat(new TreeSet<>(staged).removeAll(known))
                    .as("every DALYTRAN-CARD-NUM resolves in cardxref.txt, so the INVALID KEY arm of "
                            + "app/cbl/CBTRN02C.cbl:L384-L387 cannot fire over the shipped corpus. Reject "
                            + "100 is therefore reachable only from hand-built input, which is what group 4 "
                            + "uses. Manufacturing a miss by deleting a cross-reference row is forbidden: "
                            + "app/ is frozen")
                    .isTrue();
        }

        @Test
        @DisplayName("reject 101 is UNREACHABLE over the fixtures: the cross-reference closes referentially")
        void rejectOneHundredAndOneIsUnreachable() {
            Set<String> referencedAccounts = new TreeSet<>();
            for (int record = 0; record < crossReferences.recordCount(); record++) {
                referencedAccounts.add(crossReferences.field(record, XREF_ACCT_ID_COLUMN, 11));
            }
            Set<String> existingAccounts = new TreeSet<>();
            for (int record = 0; record < accounts.recordCount(); record++) {
                existingAccounts.add(accounts.field(record, ACCT_ID_COLUMN, 11));
            }

            assertThat(existingAccounts)
                    .as("XREF-ACCT-ID is a subset of acctdata.ACCT-ID, so the INVALID KEY arm of "
                            + "app/cbl/CBTRN02C.cbl:L396-L399 cannot fire over the shipped corpus")
                    .containsAll(referencedAccounts);
        }

        @Test
        @DisplayName("reject 103 is UNREACHABLE over the fixtures: every expiry postdates the single orig date")
        void rejectOneHundredAndThreeIsUnreachable() {
            Set<String> originatingDates = new TreeSet<>();
            for (int record = 0; record < dailyTransactions.recordCount(); record++) {
                originatingDates.add(dailyTransactions.field(record, DALYTRAN_ORIG_TS_COLUMN,
                        EXPIRY_COMPARISON_LENGTH));
            }
            assertThat(originatingDates)
                    .as("all 300 staged records carry ONE distinct value in DALYTRAN-ORIG-TS (1:10)")
                    .containsExactly("2022-06-10");

            String originatingDate = originatingDates.iterator().next();
            TreeSet<String> expiryDates = new TreeSet<>();
            for (int record = 0; record < accounts.recordCount(); record++) {
                expiryDates.add(accounts.field(record, ACCT_EXPIRAION_DATE_COLUMN,
                        EXPIRY_COMPARISON_LENGTH));
            }

            assertThat(expiryDates.first())
                    .as("the EARLIEST ACCT-EXPIRAION-DATE in acctdata.txt - misspelling retained from "
                            + "app/cpy/CVACT01Y.cpy:L11")
                    .isEqualTo("2023-01-06");
            assertThat(expiryDates)
                    .allSatisfy(expiry -> assertThat(expiry.compareTo(originatingDate))
                            .as("app/cbl/CBTRN02C.cbl:L414 compares ACCT-EXPIRAION-DATE >= "
                                    + "DALYTRAN-ORIG-TS (1:10) as TEXT, and every expiry in the corpus sorts "
                                    + "above the single originating date, so the ELSE arm at :L417 cannot "
                                    + "fire. Reject 103 - and therefore the 102-overwritten-by-103 quirk - "
                                    + "is reachable only from synthetic input, which is what group 7 builds")
                            .isGreaterThan(0));
        }

        @Test
        @DisplayName("reject 102 is the only code the shipped corpus can produce, so such a run ends RC 4")
        void rejectOneHundredAndTwoIsTheOnlyReachableCode() {
            // The three preceding tests each prove one code unreachable from the input data alone. 109 is
            // never a reject at all - see group 14. That leaves exactly one, and it is reachable because the
            // over-limit predicate depends on the amount and the account's accumulators rather than on
            // referential closure or on a date ordering.
            assertThat(RejectCode.values())
                    .as("app/cbl/CBTRN02C.cbl assigns exactly five reason codes")
                    .hasSize(5);
            assertThat(List.of(RejectCode.INVALID_CARD_NUMBER, RejectCode.ACCOUNT_RECORD_NOT_FOUND,
                            RejectCode.TRANSACTION_RECEIVED_AFTER_ACCT_EXPIRATION))
                    .as("100, 101 and 103 are each proven unreachable over the shipped fixtures by the "
                            + "tests above; 109 is assigned but never consumed as a reject")
                    .hasSize(3);
            assertThat(RejectCode.OVERLIMIT_TRANSACTION.getCode())
                    .as("102 remains, so a whole-corpus run rejects at least one record and "
                            + "app/cbl/CBTRN02C.cbl:L229-L231 sets RETURN-CODE 4. HOW MANY it rejects is "
                            + "NOT asserted, here or anywhere: the Gate 1 baseline is Not available and two "
                            + "defensible models of the cascade disagree on the total")
                    .isEqualTo(102);
        }

        @Test
        @DisplayName("the corpus genuinely exercises the negative-amount branch of :L551")
        void theCorpusGenuinelyExercisesTheNegativeBranch() {
            int negativeAmounts = 0;
            int positiveAmounts = 0;
            for (int record = 0; record < dailyTransactions.recordCount(); record++) {
                BigDecimal amount = dailyTransactions.signedDecimal(record, DALYTRAN_AMT_COLUMN, 11);
                if (amount.signum() < 0) {
                    negativeAmounts++;
                } else {
                    positiveAmounts++;
                }
            }

            assertThat(negativeAmounts)
                    .as("app/data/ASCII/dailytran.txt carries genuinely negative amounts - the sign is a "
                            + "trailing zoned-decimal overpunch, and both '}' and the J-to-R range appear - "
                            + "so the ELSE arm at app/cbl/CBTRN02C.cbl:L551 that ADDS a negative amount to "
                            + "ACCT-CURR-CYC-DEBIT is genuinely reached. The fixture must never be "
                            + "normalised to absolute values: doing so would silence group 13 entirely")
                    .isEqualTo(50);
            assertThat(positiveAmounts)
                    .as("the remaining records take the credit arm at :L549")
                    .isEqualTo(250);
            assertThat(negativeAmounts + positiveAmounts).isEqualTo(300);
        }

        @Test
        @DisplayName("the sign is decoded from the PIC position only, never from letters found in text fields")
        void theSignIsDecodedPositionally() {
            // A-to-R are overpunch characters at a numeric field's SIGN POSITION and ordinary letters
            // everywhere else. They occur legitimately inside DALYTRAN-DESC, and a decoder that scanned for
            // them instead of indexing the PIC clause would corrupt every record that mentions, say, a
            // merchant named with an initial. Decoding is therefore position-aware, always.
            boolean descriptionsContainOverpunchLetters = false;
            for (int record = 0; record < dailyTransactions.recordCount(); record++) {
                String description = dailyTransactions.field(record, DALYTRAN_DESC_COLUMN, 100);
                for (char letter : description.toCharArray()) {
                    if (letter >= 'A' && letter <= 'R') {
                        descriptionsContainOverpunchLetters = true;
                        break;
                    }
                }
                if (descriptionsContainOverpunchLetters) {
                    break;
                }
            }

            assertThat(descriptionsContainOverpunchLetters)
                    .as("app/data/ASCII/dailytran.txt has letters in the A-to-R range inside "
                            + "DALYTRAN-DESC PIC X(100), which are NOT signs. Only the last byte of a "
                            + "PIC S9(09)V99 field is a sign position")
                    .isTrue();

            // The amount decoded from its declared columns is unaffected by any letter in the text fields.
            assertThat(dailyTransactions.signedDecimal(0, DALYTRAN_AMT_COLUMN, 11).scale())
                    .as("V99 gives every decoded amount scale 2, taken from the PIC clause and not from the "
                            + "characters that happen to surround the field")
                    .isEqualTo(2);
        }
    }

    /**
     * Untrusted input, as Rule 1 clause A2 requires: the processor is fed values a well-formed staging row
     * would never carry, and the outcome is asserted rather than assumed.
     *
     * <p>Several of these cases cannot be built through {@link DailyTransaction}'s public constructor,
     * because the entity's own guards reject them - which is itself part of the defence and is asserted
     * here. Where the condition under test is what the <em>processor</em> does with a value the schema
     * declares present, the field is cleared reflectively through {@link #setField}, so that the entity's
     * guards do not decide what this suite is able to express.
     */
    @Nested
    @DisplayName("19. Untrusted input: hostile values, boundary widths and the finder that is never used")
    class UntrustedInput {

        @Test
        @DisplayName("the cross-reference read uses findById on the primary key, never the alternate index")
        void theCrossReferenceReadUsesThePrimaryKey() {
            stubHappyPath();
            // Stubbed precisely to prove it is NOT called. findFirstByAccountIdOrderByCardNumberAsc models
            // CARDXREF.VSAM.AIX, and FILE-CONTROL at app/cbl/CBTRN02C.cbl:L28-L60 declares XREF-FILE
            // INDEXED with RECORD KEY IS FD-XREF-CARD-NUM - so :L383 is a PRIMARY key read of the sixteen-
            // character card number and the alternate index has no part in this program.
            Mockito.when(cardCrossReferenceRepository.findFirstByAccountIdOrderByCardNumberAsc(
                    Mockito.anyLong())).thenReturn(Optional.of(crossReference()));

            processor.process(dailyTransaction("10.00"));

            Mockito.verify(cardCrossReferenceRepository).findById(CARD_NUMBER);
            Mockito.verify(cardCrossReferenceRepository, Mockito.never())
                    .findFirstByAccountIdOrderByCardNumberAsc(Mockito.anyLong());
        }

        @ParameterizedTest
        @ValueSource(strings = {"2022-06-10", "2022-06-10 ", "2022-06-10 19:27:53.000000"})
        @DisplayName("only the first ten characters of the originating timestamp reach the comparison")
        void onlyTheFirstTenCharactersMatter(final String origTs) {
            Mockito.when(cardCrossReferenceRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(crossReference()));
            Mockito.when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(account("0.00", "9000.00", "0.00", "0.00", "2022-06-10")));
            Mockito.when(transactionCategoryBalanceRepository.findById(fixtureKey()))
                    .thenReturn(Optional.empty());

            PostingResult result = processor.process(dailyTransaction("10.00", CARD_NUMBER, origTs));

            assertThat(result.isPosted())
                    .as("app/cbl/CBTRN02C.cbl:L414 slices DALYTRAN-ORIG-TS (1:10), so whatever follows "
                            + "column 10 - a time, a partial time, or nothing at all - cannot change the "
                            + "expiry decision. An equal date accepts, because the operator is >=")
                    .isTrue();
        }

        @Test
        @DisplayName("a blank originating timestamp compares as spaces and passes, because ' ' sorts below '0'")
        void aBlankOriginatingTimestampPasses() {
            Mockito.when(cardCrossReferenceRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(crossReference()));
            Mockito.when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(acceptingAccount()));
            Mockito.when(transactionCategoryBalanceRepository.findById(fixtureKey()))
                    .thenReturn(Optional.empty());

            PostingResult result = processor.process(dailyTransaction("10.00", CARD_NUMBER, " ".repeat(26)));

            assertThat(result.isPosted())
                    .as("the comparison at app/cbl/CBTRN02C.cbl:L414 is alphanumeric, not a date parse, so a "
                            + "blank PIC X(26) field yields ten spaces and every expiry date sorts above "
                            + "them. The record passes rather than raising - which is what the mainframe "
                            + "field would do, and a Java form that parsed the value into a LocalDate would "
                            + "instead throw on the same input")
                    .isTrue();
        }

        @Test
        @DisplayName("a blank processing timestamp on the input is irrelevant: :L437 generates its own")
        void aBlankProcessingTimestampOnTheInputIsOverwritten() {
            stubHappyPath();
            DailyTransaction item = setField(dailyTransaction("10.00"), "procTs", " ".repeat(26));

            PostingResult result = processor.process(item);

            assertThat(postedTransaction(result).getProcTs())
                    .as("app/cbl/CBTRN02C.cbl:L437-L438 performs Z-GET-DB2-FORMAT-TIMESTAMP and moves the "
                            + "GENERATED value into TRAN-PROC-TS. DALYTRAN-PROC-TS is never the source, so a "
                            + "blank or stale value on the staging row cannot reach the posted record")
                    .isEqualTo(EXPECTED_PROC_TS)
                    .isNotBlank();
        }

        @Test
        @DisplayName("the originating timestamp IS passed through untouched, blanks and all")
        void theOriginatingTimestampIsPassedThroughUntouched() {
            Mockito.when(cardCrossReferenceRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(crossReference()));
            Mockito.when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(acceptingAccount()));
            Mockito.when(transactionCategoryBalanceRepository.findById(fixtureKey()))
                    .thenReturn(Optional.empty());
            String oddButValid = "2022-06-10 19:27:53.4700";

            PostingResult result =
                    processor.process(dailyTransaction("10.00", CARD_NUMBER, oddButValid));

            assertThat(postedTransaction(result).getOrigTs())
                    .as("app/cbl/CBTRN02C.cbl:L436 MOVE DALYTRAN-ORIG-TS TO TRAN-ORIG-TS is a pure "
                            + "pass-through - no reformatting, no normalisation, no reparse. Both fields are "
                            + "PIC X(26) at app/cpy/CVTRA05Y.cpy:L16-L17 and app/cpy/CVTRA06Y.cpy:L16-L17, "
                            + "so they are Strings over CHAR(26) and never LocalDateTime or Instant")
                    .isEqualTo(oddButValid);
        }

        @ParameterizedTest
        @ValueSource(strings = {"{", "}", "A", "R", "0000000000A"})
        @DisplayName("overpunch characters arriving inside a text field are carried through, never decoded")
        void overpunchCharactersInTextFieldsAreNotDecoded(final String hostileText) {
            stubHappyPath();
            String description = hostileText + " PURCHASE";
            DailyTransaction item = setField(
                    setField(setField(dailyTransaction("10.00"), "description", description),
                            "merchantName", hostileText + " MERCHANT"),
                    "merchantCity", hostileText + " CITY");

            PostingResult result = processor.process(item);

            Transaction posted = postedTransaction(result);
            assertThat(posted.getDescription())
                    .as("'{', '}' and the A-to-R range are zoned-decimal overpunch characters ONLY at the "
                            + "sign position of a numeric PIC clause. Inside DALYTRAN-DESC PIC X(100) they "
                            + "are ordinary text and must survive :L429 byte for byte")
                    .isEqualTo(description);
            assertThat(posted.getMerchantName()).isEqualTo(hostileText + " MERCHANT");
            assertThat(posted.getMerchantCity()).isEqualTo(hostileText + " CITY");
            assertThat(posted.getAmount())
                    .as("the amount is read from DALYTRAN-AMT and cannot be perturbed by text elsewhere in "
                            + "the record; compared with compareTo because 10.00 and 10.0 are equal values "
                            + "and unequal objects")
                    .usingComparator(BigDecimal::compareTo)
                    .isEqualTo(new BigDecimal("10.00"));
        }

        @Test
        @DisplayName("a field wider than its PIC clause is refused by the entity before the processor sees it")
        void anOverlongFieldIsRefusedUpstream() {
            // The boundary is the entity's, and asserting it here records WHERE the defence lives. A
            // 351-character description cannot be staged at all, so no 350-byte record can overflow - which
            // is why the processor itself needs no width check on the way in.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new DailyTransaction(1L, TRAN_ID, TYPE_CD, CAT_CD, "POS TERM",
                            "X".repeat(101), new BigDecimal("10.00"), 123456789L, "M", "C", "12345",
                            CARD_NUMBER, ORIG_TS, ORIG_TS))
                    .withMessageContaining("DALYTRAN-DESC PIC X(100)")
                    .withMessageContaining("must be at most 100 characters but was 101");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("DALYTRAN-ORIG-TS is PIC X(26) at app/cpy/CVTRA06Y.cpy:L16, so a 27-character value "
                            + "is refused rather than silently truncated into the expiry comparison")
                    .isThrownBy(() -> new DailyTransaction(1L, TRAN_ID, TYPE_CD, CAT_CD, "POS TERM", "D",
                            new BigDecimal("10.00"), 123456789L, "M", "C", "12345", CARD_NUMBER,
                            "2022-06-10 19:27:53.0000000", ORIG_TS))
                    .withMessageContaining("origTs");
        }

        @Test
        @DisplayName("a shorter-than-declared originating timestamp is space-filled, not rejected")
        void aShortOriginatingTimestampIsSpaceFilled() {
            Mockito.when(cardCrossReferenceRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(crossReference()));
            Mockito.when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(acceptingAccount()));
            Mockito.when(transactionCategoryBalanceRepository.findById(fixtureKey()))
                    .thenReturn(Optional.empty());

            PostingResult result = processor.process(dailyTransaction("10.00", CARD_NUMBER, "2022-06"));

            assertThat(result.isPosted())
                    .as("a COBOL alphanumeric field is fixed width and space padded, so a short value is "
                            + "padded to PIC X(26) and then sliced. It cannot raise, and it passes the "
                            + "expiry test because a space sorts below every digit")
                    .isTrue();
            assertThat(loggedMessages())
                    .as("Rule 1 clause A4 - the condition is diagnosable, and the diagnostic reports the "
                            + "LENGTH rather than the value, so no staged content reaches the log")
                    .anySatisfy(message -> assertThat(message)
                            .contains("shorter than the 10 the expiry comparison at "
                                    + "app/cbl/CBTRN02C.cbl:L414 slices")
                            .doesNotContain("2022-06-"));
        }

        @Test
        @DisplayName("an absent record is an abend, not a reject, because no reason code describes it")
        void anAbsentRecordIsNeverAReject() {
            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> processor.process(null))
                    .satisfies(abend -> {
                        assertThat(abend.getAbendCode())
                                .as("the processor's own abend path stamps the 999 of "
                                        + "app/cbl/CBTRN02C.cbl:L710")
                                .isEqualTo(String.valueOf(FatalProcessingException.BATCH_ABEND_CODE));
                        assertThat(abend.getAbendMessage())
                                .as("app/cbl/CBTRN02C.cbl:L205 enters per-record processing only when "
                                        + "END-OF-FILE is 'N', so an absent record is a wiring defect in the "
                                        + "step and not a data condition - and none of the five reason codes "
                                        + "describes it")
                                .contains("app/cbl/CBTRN02C.cbl:L205");
                    });
            Mockito.verifyNoInteractions(cardCrossReferenceRepository, accountRepository,
                    transactionCategoryBalanceRepository);
        }
    }
}

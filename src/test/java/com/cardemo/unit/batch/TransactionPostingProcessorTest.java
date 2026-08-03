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
 *               processing timestamp ending in four zeros, and the
 *               reason code 109 that the source assigns and never
 *               consumes.
 * Source      : app/cbl/CBTRN02C.cbl:L205-L216 (per-record mainline)
 *               app/cbl/CBTRN02C.cbl:L370-L378 (1500-VALIDATE-TRAN)
 *               app/cbl/CBTRN02C.cbl:L380-L392 (1500-A-LOOKUP-XREF)
 *               app/cbl/CBTRN02C.cbl:L393-L422 (1500-B-LOOKUP-ACCT)
 *               app/cbl/CBTRN02C.cbl:L424-L465 (2000-POST-TRANSACTION)
 *               app/cbl/CBTRN02C.cbl:L467-L500 (2700-UPDATE-TCATBAL)
 *               app/cbl/CBTRN02C.cbl:L502-L524 (2700-A-CREATE-TCATBAL-REC)
 *               app/cbl/CBTRN02C.cbl:L526-L542 (2700-B-UPDATE-TCATBAL-REC)
 *               app/cbl/CBTRN02C.cbl:L544-L560 (2800-UPDATE-ACCOUNT-REC)
 *               app/cbl/CBTRN02C.cbl:L562-L579 (2900-WRITE-TRANSACTION-FILE)
 *               app/cbl/CBTRN02C.cbl:L690-L705 (Z-GET-DB2-FORMAT-TIMESTAMP)
 *               app/cbl/CBTRN02C.cbl:L707-L712 (9999-ABEND-PROGRAM)
 *               app/cpy/CVTRA06Y.cpy           (DALYTRAN 350-byte layout)
 *               app/cpy/CVTRA01Y.cpy:L5-L9     (TRAN-CAT-BAL key + balance)
 *               app/cpy/CVACT01Y.cpy:L11       (ACCT-EXPIRAION-DATE)
 *               app/cpy/CVACT03Y.cpy:L7        (XREF-ACCT-ID) @ 7756d89
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
import com.cardemo.exception.DataIntegrityException;
import com.cardemo.exception.DuplicateRecordException;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.DailyTransaction;
import com.cardemo.model.entity.Transaction;
import com.cardemo.model.entity.TransactionCategoryBalance;
import com.cardemo.model.enums.RejectCode;
import com.cardemo.model.key.TransactionCategoryBalanceId;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.TransactionCategoryBalanceRepository;
import com.cardemo.service.shared.FileStatusMapper;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

/**
 * Executable proof that the daily posting boundary reproduces {@code CBTRN02C}'s per-record decisions
 * exactly, including the three places where the obvious translation is wrong.
 *
 * <p><b>What it does.</b> Drives {@link TransactionPostingProcessor#process(DailyTransaction)} with
 * hand-built records and asserts the classification it returns, the rows it writes and the diagnostics
 * it emits. Four repositories are Mockito mocks because the assertion is about which call is made with
 * which value, not about SQL; {@link FileStatusMapper} is a Mockito <em>spy over a real instance</em>
 * because its {@code '00' OR '23'} decision is the behaviour under test at
 * {@code app/cbl/CBTRN02C.cbl:L481} and stubbing it would make the assertion tautological. The clock is
 * fixed, which is the only way the twenty-six character processing timestamp can be asserted as a value
 * rather than as a shape.
 *
 * <p><b>How to build and test.</b> {@code ./mvnw -B -ntp -Dtest='TransactionPostingProcessorTest' test}
 * runs this class alone; it needs no container, no database and no cloud emulator. The full gate is
 * {@code ./mvnw -B -ntp -Ddependency-check.skip=true clean verify}.
 *
 * <p><b>Key configuration and defaults.</b> The clock is pinned to {@code 2022-06-10T19:27:53.470Z} in
 * {@link ZoneOffset#UTC}, which is the instant every row of {@code app/data/ASCII/dailytran.txt} carries
 * in {@code DALYTRAN-ORIG-TS}, so the fixtures and the generated timestamp belong to the same moment.
 * The clock reaches the processor through the package-private constructor, which exists for exactly this
 * purpose; this suite lives in {@code com.cardemo.unit.batch} rather than in the production package, so
 * it reaches that constructor reflectively - the same technique
 * {@code TransactionCombineProcessorCoverageTest} uses to reach an entity's provider constructor.
 *
 * <p><b>Common failure modes and troubleshooting.</b>
 * <ul>
 * <li><b>Blocker.</b> A failure in group 7 means the unguarded fall-through of {@code :L413-L420} has
 * been guarded, so a record failing both the over-limit and the expiry test would produce reject 102
 * instead of the 103 the source produces. Restore the sequential form; do not add an early exit.</li>
 * <li><b>Blocker.</b> A failure in group 13 means the sign branch has been normalised. The cycle debit
 * accumulator must hold negative values, because the over-limit formula of {@code :L403-L405} subtracts
 * it. Taking an absolute value anywhere on that path silently inverts every over-limit decision.</li>
 * <li><b>High.</b> A failure in group 11 means {@code FILE STATUS '23'} is no longer accepted as success
 * on the category-balance read, which turns the source's create branch into an abend and loses every
 * first-of-cycle balance row.</li>
 * <li><b>High.</b> A failure in group 10 means the processing timestamp no longer renders twenty-six
 * characters ending in the literal {@code 0000}, which the boundary-parity gate diffs character for
 * character against the legacy baseline.</li>
 * <li><b>Medium.</b> A failure in group 8 means an absent {@code NOT NULL} value is being reported as a
 * reject rather than as an integrity failure, which inflates the reject count and so corrupts the return
 * code decision at {@code :L229-L231}.</li>
 * <li><b>Low.</b> A failure in group 15 means a card number has reached a log record. The convention in
 * this tree is that not emitting a primary account number is the primary defence and masking is only the
 * backstop, so the fix is to remove the value, not to mask it.</li>
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

    // ------------------------------------------------------------------------------------------------
    // Fixture builders. Each one produces a record that satisfies every guard the entity declares, so a
    // test that wants one field absent removes it explicitly through setField and the intent stays legible.
    // ------------------------------------------------------------------------------------------------

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

    /** @return the composite key {@code categoryBalanceKey} must compose for the fixtures. */
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
     * Reaches the package-private constructor that accepts a clock.
     *
     * <p>Reflection is used deliberately rather than moving this suite into the production package. The
     * constructor's package-private visibility is the production class's own statement that the clock seam
     * is not part of its public contract, and honouring that while still exercising the seam is what
     * reflection buys. Any exception the constructor raises is rethrown unchanged so that a test can
     * assert on it.
     *
     * @param xref the cross-reference repository, possibly {@code null} for a guard test
     * @param accounts the account repository, possibly {@code null}
     * @param balances the category-balance repository, possibly {@code null}
     * @param mapper the status mapper, possibly {@code null}
     * @param clock the clock, possibly {@code null}
     * @return the constructed processor
     */
    private static TransactionPostingProcessor newProcessor(final CardCrossReferenceRepository xref,
                                                            final AccountRepository accounts,
                                                            final TransactionCategoryBalanceRepository
                                                                    balances,
                                                            final FileStatusMapper mapper,
                                                            final Clock clock) {
        try {
            Constructor<TransactionPostingProcessor> constructor =
                    TransactionPostingProcessor.class.getDeclaredConstructor(
                            CardCrossReferenceRepository.class, AccountRepository.class,
                            TransactionCategoryBalanceRepository.class,
                            FileStatusMapper.class, Clock.class);
            constructor.setAccessible(true);
            return constructor.newInstance(xref, accounts, balances, mapper, clock);
        } catch (InvocationTargetException invocation) {
            Throwable cause = invocation.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("Constructor raised a checked throwable", cause);
        } catch (ReflectiveOperationException reflection) {
            throw new IllegalStateException(
                    "The clock-accepting constructor of TransactionPostingProcessor could not be reached; "
                            + "it is package-private by design and this suite reaches it reflectively",
                    reflection);
        }
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

    /** @return every message this class's logger received, formatted as an appender would see it. */
    private List<String> loggedMessages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
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
        @DisplayName("the clock-accepting constructor rejects an absent clock")
        void clockConstructorRejectsAbsentClock() {
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> newProcessor(cardCrossReferenceRepository, accountRepository,
                            transactionCategoryBalanceRepository, fileStatusMapper, null))
                    .withMessage("clock must not be null");
        }

        @Test
        @DisplayName("the injected clock is mandatory and still generates a PIC X(26) timestamp")
        void theInjectedClockProducesTheDeclaredWidth() {
            // There is deliberately no clock-defaulting constructor. The clock is a declared collaborator, so
            // a caller cannot silently get the wall clock and no test can pass against a time source the
            // deployment did not choose.
            TransactionPostingProcessor systemClockProcessor = new TransactionPostingProcessor(
                    cardCrossReferenceRepository, accountRepository,
                    transactionCategoryBalanceRepository, fileStatusMapper, Clock.systemDefaultZone());
            stubHappyPath();

            PostingResult result = systemClockProcessor.process(dailyTransaction("10.00"));

            assertThat(result.isPosted()).isTrue();
            assertThat(result.postedTransaction().getProcTs())
                    .as("PIC X(26) with the literal 0000 of :L701 regardless of which clock supplied it")
                    .hasSize(26)
                    .endsWith("0000");
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

        @Test
        @DisplayName("the validation cascade stops at two lookups, exactly as :L377 leaves it")
        void theCascadeStopsAtTwoLookups() {
            stubHappyPath();

            processor.process(dailyTransaction("25.50"));

            Mockito.verify(cardCrossReferenceRepository).findById(CARD_NUMBER);
            Mockito.verify(accountRepository).findById(ACCOUNT_ID);
            Mockito.verifyNoMoreInteractions(cardCrossReferenceRepository);
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
                    .as("millisecond precision plus four zeros, never nanosecond precision")
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
}

/*
 * ******************************************************************
 * Program     : InterestCalculationProcessorTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Verifies the per-record boundary of the interest
 *               calculation step: the account-level control break and
 *               the flush-reset-reload ordering it depends on, the
 *               interest formula computed as balance times rate
 *               divided by the literal 1200, the zero-rate suppression
 *               that emits nothing, the DEFAULT disclosure-group
 *               fallback whose second stage abends when the default
 *               row is missing, the cycle counters zeroed on every
 *               account rewrite, the run-sequential sixteen-character
 *               identifier built from PARM-DATE, and the two retained
 *               no-op paragraphs that keep the paragraph map provable.
 * Source      : app/cbl/CBACT04C.cbl:L185-L232 (1000-TCATBALF loop)
 *               app/cbl/CBACT04C.cbl:L194-L208 (control break)
 *               app/cbl/CBACT04C.cbl:L219-L220 (unreachable ELSE arm)
 *               app/cbl/CBACT04C.cbl:L350-L370 (1050-UPDATE-ACCOUNT)
 *               app/cbl/CBACT04C.cbl:L393-L413 (1200-B-GET-XREF)
 *               app/cbl/CBACT04C.cbl:L415-L460 (1300-A rate lookup)
 *               app/cbl/CBACT04C.cbl:L462-L470 (1300-COMPUTE-INTEREST)
 *               app/cbl/CBACT04C.cbl:L473-L516 (1300-B-WRITE-TX)
 *               app/cbl/CBACT04C.cbl:L518-L520 (1400-COMPUTE-FEES)
 *               app/cpy/CVTRA01Y.cpy:L5-L9     (TRAN-CAT-BAL record)
 *               app/cpy/CVTRA02Y.cpy:L6-L9     (DIS-INT-RATE key)
 *               app/jcl/INTCALC.jcl            (PARM='2022071800')
 *               app/data/ASCII/discgrp.txt     (17 DEFAULT rows of 51)
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
import com.cardemo.batch.processors.InterestCalculationProcessor;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.DisclosureGroup;
import com.cardemo.model.entity.Transaction;
import com.cardemo.model.entity.TransactionCategoryBalance;
import com.cardemo.model.enums.TransactionSource;
import com.cardemo.model.key.DisclosureGroupId;
import com.cardemo.model.key.TransactionCategoryBalanceId;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.DisclosureGroupRepository;
import com.cardemo.service.shared.FileStatusMapper;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
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
import org.springframework.dao.QueryTimeoutException;

/**
 * Executable proof that the interest step reproduces {@code CBACT04C}'s per-record arithmetic and
 * control flow, including the branch that never runs and the paragraph that does nothing.
 *
 * <p><b>What it does.</b> Drives {@link InterestCalculationProcessor#process(TransactionCategoryBalance)}
 * with sequences of category-balance rows in the browse order the step's reader guarantees, and asserts
 * the interest it computes, the account rewrites it performs at each control break, the synthesised
 * transaction it returns and the abends it raises. The three repositories are Mockito mocks;
 * {@link FileStatusMapper} is a real instance because its two-stage {@code '00' OR '23'} decision is the
 * behaviour under test and stubbing it would make the fallback assertions tautological.
 *
 * <p><b>How to build and test.</b>
 * {@code ./mvnw -B -ntp -Dtest='InterestCalculationProcessorTest' test} runs this class alone; it needs
 * no container, no database and no cloud emulator.
 *
 * <p><b>Key configuration and defaults.</b> {@code PARM-DATE} is {@code "2022071800"}, the exact value
 * {@code app/jcl/INTCALC.jcl} passes - eight date digits followed by two zeros, not an ISO date, because
 * it is concatenated with a six-digit suffix to form {@code TRAN-ID PIC X(16)}. The clock is pinned so
 * that both generated timestamps are asserted as values.
 *
 * <p><b>Where this suite deliberately contradicts the Agent Action Plan.</b> &sect;0.7.3.3 asserts that
 * "when the loop detects end of file it performs the account update one final time". The production class
 * establishes from the source - by indentation, at {@code app/cbl/CBACT04C.cbl:L188}, {@code :L191},
 * {@code :L218}, {@code :L219} and {@code :L221} - that the {@code ELSE} arm is <em>unreachable</em> and
 * that the last account of every run is therefore never flushed. These tests assert the source's
 * behaviour, not the plan's prose: group 11 proves the retained arm delegates correctly when invoked
 * directly, and group 3 proves nothing in the normal path invokes it. Asserting a final flush here would
 * lock in a defect the production class was written specifically to avoid.
 *
 * <p><b>Common failure modes and troubleshooting.</b>
 * <ul>
 * <li><b>Blocker.</b> A failure in group 5 means the formula has been algebraically rewritten. It must
 * multiply and only then divide by the literal {@code 1200} with {@code HALF_EVEN} at scale two; dividing
 * by 100 and then by 12, or multiplying by a decimal rate, rounds differently.</li>
 * <li><b>Blocker.</b> A failure in group 3 means the flush-reset-reload ordering of {@code :L196} to
 * {@code :L203} has moved. Any other order posts one account's interest onto another account's
 * balance.</li>
 * <li><b>High.</b> A failure in group 4 means the two cycle counters are no longer zeroed on the account
 * rewrite, which corrupts the over-limit arithmetic of the following posting cycle.</li>
 * <li><b>High.</b> A failure in group 7 means the two-stage rate lookup has changed shape: stage one must
 * tolerate a record not found and retry with the literal {@code DEFAULT} group, and stage two must
 * abend.</li>
 * <li><b>Medium.</b> A failure in group 6 means a zero rate is no longer suppressing the transaction, so
 * the run would emit interest rows of zero value that the source never writes.</li>
 * <li><b>Low.</b> A failure in group 12 means a card number has reached a log record.</li>
 * </ul>
 */
@DisplayName("InterestCalculationProcessor: CBACT04C's control break, formula and retained no-ops")
class InterestCalculationProcessorTest {

    /** The value {@code app/jcl/INTCALC.jcl} supplies: eight date digits then two zeros. */
    private static final String PARM_DATE = "2022071800";

    /** A pinned instant so that both generated timestamps are values rather than shapes. */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2022-07-18T04:05:06.070Z"), ZoneOffset.UTC);

    /** What {@code Z-GET-DB2-FORMAT-TIMESTAMP} renders for {@link #FIXED_CLOCK}. */
    private static final String EXPECTED_TIMESTAMP = "2022-07-18-04.05.06.070000";

    /** The first account in the browse sequence. */
    private static final long ACCOUNT_A = 11L;

    /** The second account in the browse sequence, which triggers a control break. */
    private static final long ACCOUNT_B = 22L;

    /** {@code TRANCAT-TYPE-CD PIC X(02)}. */
    private static final String TYPE_CD = "01";

    /** {@code TRANCAT-CD PIC 9(04)}. */
    private static final int CAT_CD = 5;

    /** {@code ACCT-GROUP-ID PIC X(10)} for the accounts under test. */
    private static final String GROUP_ID = "ZEROPCT";

    /** The card number the cross-reference resolves. Never asserted against a log record. */
    private static final String CARD_A = "4111111111111111";

    /** The card number for the second account. */
    private static final String CARD_B = "4222222222222222";

    private DisclosureGroupRepository disclosureGroupRepository;
    private AccountRepository accountRepository;
    private CardCrossReferenceRepository cardCrossReferenceRepository;
    private FileStatusMapper fileStatusMapper;
    private InterestCalculationProcessor processor;

    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger logger;
    private Level originalLevel;

    /**
     * The processor's isolated parity logger, captured alongside its class logger.
     *
     * <p>The processor emits on two channels by design. Operational lines - the default-group fallback
     * notices, the I/O statuses and every literal that precedes an abend - go to the class logger. The
     * per-record dump that reproduces {@code DISPLAY TRAN-CAT-BAL-RECORD} at
     * {@code app/cbl/CBACT04C.cbl:L193} goes instead to {@code com.cardemo.parity.CBACT04C}, a per-program
     * logger every shipped profile pins to {@code OFF}. Attaching one appender to both loggers means
     * {@link #loggedMessages()} sees a single ordered stream, so an assertion about either channel - and the
     * hygiene assertion that no channel carries a card number - keeps its meaning.
     */
    private ch.qos.logback.classic.Logger parityLogger;

    /** The parity logger's configured level, restored after each test. */
    private Level originalParityLevel;

    @BeforeEach
    void buildProcessorAndCaptureLogs() {
        disclosureGroupRepository = Mockito.mock(DisclosureGroupRepository.class);
        accountRepository = Mockito.mock(AccountRepository.class);
        cardCrossReferenceRepository = Mockito.mock(CardCrossReferenceRepository.class);
        fileStatusMapper = new FileStatusMapper();
        processor = new InterestCalculationProcessor(disclosureGroupRepository, accountRepository,
                cardCrossReferenceRepository, fileStatusMapper, PARM_DATE, FIXED_CLOCK);

        appender = new ListAppender<>();
        appender.start();

        logger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(InterestCalculationProcessor.class);
        originalLevel = logger.getLevel();
        logger.setLevel(Level.TRACE);
        logger.addAppender(appender);

        // The parity channel has to be raised explicitly: it is OFF in every shipped profile, which is the
        // point of it, so a test that wants the per-record dump must ask for it.
        parityLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("com.cardemo.parity.CBACT04C");
        originalParityLevel = parityLogger.getLevel();
        parityLogger.setLevel(Level.TRACE);
        parityLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        parityLogger.detachAppender(appender);
        parityLogger.setLevel(originalParityLevel);
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(originalLevel);
    }

    // ------------------------------------------------------------------------------------------------
    // Fixture builders
    // ------------------------------------------------------------------------------------------------

    /**
     * Builds one 50-byte {@code TRAN-CAT-BAL-RECORD}.
     *
     * @param accountId {@code TRANCAT-ACCT-ID}
     * @param typeCd {@code TRANCAT-TYPE-CD}
     * @param catCd {@code TRANCAT-CD}
     * @param balance {@code TRAN-CAT-BAL}
     * @return the record the reader would supply
     */
    private static TransactionCategoryBalance categoryBalance(final long accountId, final String typeCd,
                                                              final int catCd, final String balance) {
        return new TransactionCategoryBalance(
                new TransactionCategoryBalanceId(accountId, typeCd, catCd), new BigDecimal(balance));
    }

    /**
     * Builds one record for the fixture type and category.
     *
     * @param accountId {@code TRANCAT-ACCT-ID}
     * @param balance {@code TRAN-CAT-BAL}
     * @return the record the reader would supply
     */
    private static TransactionCategoryBalance categoryBalance(final long accountId, final String balance) {
        return categoryBalance(accountId, TYPE_CD, CAT_CD, balance);
    }

    /**
     * Builds the account row {@code 1200-A-GET-ACCT-DATA} reads.
     *
     * @param accountId {@code ACCT-ID}
     * @param currentBalance {@code ACCT-CURR-BAL}
     * @param groupId {@code ACCT-GROUP-ID}
     * @return the account row
     */
    private static Account account(final long accountId, final String currentBalance,
                                   final String groupId) {
        return new Account(accountId, "Y", new BigDecimal(currentBalance), new BigDecimal("9000.00"),
                new BigDecimal("5000.00"), "2020-01-01", "2030-01-01", "2020-01-01",
                new BigDecimal("500.00"), new BigDecimal("-100.00"), "12345", groupId);
    }

    /**
     * Builds the cross-reference row {@code 1200-B-GET-XREF-DATA} reads through the alternate index.
     *
     * @param accountId {@code XREF-ACCT-ID}
     * @param cardNumber {@code XREF-CARD-NUM}
     * @return the cross-reference row
     */
    private static CardCrossReference crossReference(final long accountId, final String cardNumber) {
        return new CardCrossReference(cardNumber, 123456789L, accountId);
    }

    /**
     * Builds a disclosure group row carrying a rate.
     *
     * @param groupId {@code DIS-ACCT-GROUP-ID}
     * @param rate {@code DIS-INT-RATE}
     * @return the disclosure group row
     */
    private static DisclosureGroup disclosureGroup(final String groupId, final String rate) {
        return new DisclosureGroup(new DisclosureGroupId(groupId, TYPE_CD, CAT_CD), new BigDecimal(rate));
    }

    /**
     * Stubs the account and cross-reference reads for one account.
     *
     * @param accountId the account the control break will load
     * @param currentBalance {@code ACCT-CURR-BAL}
     * @param cardNumber the card the cross-reference resolves
     * @return the account instance, so a test can assert the mutations applied to it
     */
    private Account stubAccount(final long accountId, final String currentBalance,
                                final String cardNumber) {
        Account account = account(accountId, currentBalance, GROUP_ID);
        Mockito.when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        Mockito.when(cardCrossReferenceRepository.findFirstByAccountIdOrderByCardNumberAsc(accountId))
                .thenReturn(Optional.of(crossReference(accountId, cardNumber)));
        return account;
    }

    /**
     * Stubs stage one of the rate lookup to resolve directly, with no {@code DEFAULT} retry.
     *
     * @param rate {@code DIS-INT-RATE}
     */
    private void stubDirectRate(final String rate) {
        Mockito.when(disclosureGroupRepository.findById(new DisclosureGroupId(GROUP_ID, TYPE_CD, CAT_CD)))
                .thenReturn(Optional.of(disclosureGroup(GROUP_ID, rate)));
    }

    /** @return every message the processor emitted, on its class logger and on its parity logger alike. */
    private List<String> loggedMessages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /**
     * Sets one declared field of an entity to a chosen value, so a test can express an absent
     * {@code NOT NULL} column that the entity's own setter refuses to accept.
     *
     * <p>Every setter on these entities guards its argument, which is correct for application code and
     * exactly wrong for a test whose subject is what the processor does when a row it read violates the
     * schema. Writing the field directly moves the decision about what is expressible from the entity to
     * this suite, where it belongs.
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
     * Stubs stage one of the rate lookup to resolve for <em>any</em> key.
     *
     * <p>Used by the tests that vary the type and category codes deliberately - the control-break tests and
     * the identifier-sequence tests - where keying the stub would make an unrelated DEFAULT retry the thing
     * under test. {@link #stubDirectRate(String)} keys on the exact composite key and is what proves the key
     * composition in group 8.
     *
     * @param rate {@code DIS-INT-RATE}
     */
    private void stubAnyRate(final String rate) {
        Mockito.when(disclosureGroupRepository.findById(Mockito.any()))
                .thenReturn(Optional.of(disclosureGroup(GROUP_ID, rate)));
    }

    /**
     * Builds a composite key with no components populated, which the public constructor refuses to produce.
     *
     * <p>The provider constructor is {@code protected} precisely so that application code cannot build an
     * unpopulated key. A row read from a dataset whose {@code NOT NULL} columns were somehow absent is
     * nonetheless what {@code requireAccountId} exists to reject, so the condition has to be expressible
     * here; reaching the provider constructor reflectively expresses it without weakening the entity.
     *
     * @return an unpopulated key
     */
    private static TransactionCategoryBalanceId blankCategoryBalanceKey() {
        try {
            java.lang.reflect.Constructor<TransactionCategoryBalanceId> provider =
                    TransactionCategoryBalanceId.class.getDeclaredConstructor();
            provider.setAccessible(true);
            return provider.newInstance();
        } catch (ReflectiveOperationException reflection) {
            throw new IllegalStateException(
                    "The provider constructor of TransactionCategoryBalanceId could not be reached",
                    reflection);
        }
    }

    /**
     * Invokes the retained unreachable {@code ELSE} arm directly.
     *
     * <p>The method is private and, in the source, unreachable. Invoking it reflectively is the only way
     * to prove that the retained arm still delegates to the flush it names, which is what makes it a
     * faithful reproduction rather than abandoned residue. Nothing in the normal path calls it, and group
     * 3 asserts that too.
     */
    private void invokeUnreachableEndOfFileArm() {
        try {
            Method arm = InterestCalculationProcessor.class
                    .getDeclaredMethod("updateAccountAtEndOfFile");
            arm.setAccessible(true);
            arm.invoke(processor);
        } catch (InvocationTargetException invocation) {
            Throwable cause = invocation.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("The retained arm raised a checked throwable", cause);
        } catch (ReflectiveOperationException reflection) {
            throw new IllegalStateException(
                    "updateAccountAtEndOfFile is retained as a marked unreachable no-op and must remain "
                            + "declared so that the paragraph map for app/cbl/CBACT04C.cbl:L219-L220 stays "
                            + "provable", reflection);
        }
    }

    @Nested
    @DisplayName("1. Construction: every collaborator and a PIC X(10) PARM-DATE are required")
    class Construction {

        @ParameterizedTest(name = "an absent {0} is refused")
        @ValueSource(strings = {"disclosureGroupRepository", "accountRepository",
            "cardCrossReferenceRepository", "fileStatusMapper", "clock"})
        @DisplayName("each absent collaborator abends by name")
        void eachAbsentCollaboratorIsRefused(final String missing) {
            DisclosureGroupRepository groups =
                    "disclosureGroupRepository".equals(missing) ? null : disclosureGroupRepository;
            AccountRepository accounts = "accountRepository".equals(missing) ? null : accountRepository;
            CardCrossReferenceRepository crossReferences =
                    "cardCrossReferenceRepository".equals(missing) ? null : cardCrossReferenceRepository;
            FileStatusMapper mapper = "fileStatusMapper".equals(missing) ? null : fileStatusMapper;
            Clock clock = "clock".equals(missing) ? null : FIXED_CLOCK;

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> new InterestCalculationProcessor(groups, accounts, crossReferences,
                            mapper, PARM_DATE, clock))
                    .satisfies(abend -> {
                        assertThat(abend.getAbendReason()).isEqualTo("MISSING COLLABORATOR");
                        assertThat(abend.getAbendMessage()).contains(missing);
                        assertThat(abend.getAbendCulprit()).isEqualTo("CBACT04C");
                    });
        }

        @Test
        @DisplayName("an absent PARM-DATE names the job parameter and cites INTCALC.jcl")
        void anAbsentParmDateIsRefused() {
            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> new InterestCalculationProcessor(disclosureGroupRepository,
                            accountRepository, cardCrossReferenceRepository, fileStatusMapper, null,
                            FIXED_CLOCK))
                    .satisfies(abend -> {
                        assertThat(abend.getAbendReason()).isEqualTo("MISSING PARM-DATE");
                        assertThat(abend.getAbendMessage())
                                .contains("'parmDate' is mandatory")
                                .contains("PARM='2022071800'");
                    });
        }

        @ParameterizedTest(name = "a PARM-DATE of [{0}] is refused")
        @ValueSource(strings = {"", "2022", "202207180", "20220718000"})
        @DisplayName("a PARM-DATE of any width but ten is refused, because TRAN-ID must fill PIC X(16)")
        void aWronglyWidthedParmDateIsRefused(final String parmDate) {
            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> new InterestCalculationProcessor(disclosureGroupRepository,
                            accountRepository, cardCrossReferenceRepository, fileStatusMapper, parmDate,
                            FIXED_CLOCK))
                    .satisfies(abend -> {
                        assertThat(abend.getAbendReason()).isEqualTo("INVALID PARM-DATE");
                        assertThat(abend.getAbendMessage())
                                .contains("PARM-DATE is " + parmDate.length() + " characters");
                    });
        }

        @Test
        @DisplayName("the ten-character PARM-DATE of INTCALC.jcl is accepted")
        void theJclParmDateIsAccepted() {
            assertThat(new InterestCalculationProcessor(disclosureGroupRepository, accountRepository,
                    cardCrossReferenceRepository, fileStatusMapper, PARM_DATE, FIXED_CLOCK)).isNotNull();
        }
    }

    @Nested
    @DisplayName("2. Record and key validation: every picture clause is enforced before the break")
    class RecordValidation {

        @Test
        @DisplayName("an absent record abends, because the loop body only runs on a populated record area")
        void anAbsentRecordAbends() {
            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> processor.process(null))
                    .satisfies(abend -> {
                        assertThat(abend.getAbendReason())
                                .isEqualTo("MISSING TRANSACTION CATEGORY BALANCE RECORD");
                        assertThat(abend.getAbendMessage()).contains("1000-TCATBALF-GET-NEXT");
                    });
        }

        @Test
        @DisplayName("an absent TRAN-CAT-KEY abends and names the seventeen-byte key")
        void anAbsentKeyAbends() {
            TransactionCategoryBalance item = new TransactionCategoryBalance(
                    new TransactionCategoryBalanceId(ACCOUNT_A, TYPE_CD, CAT_CD), BigDecimal.ONE);
            setField(item, "id", null);

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> processor.process(item))
                    .satisfies(abend -> {
                        assertThat(abend.getAbendReason())
                                .isEqualTo("MISSING TRANSACTION CATEGORY BALANCE KEY");
                        assertThat(abend.getAbendMessage()).contains("seventeen-byte composite key");
                    });
        }

        @Test
        @DisplayName("an absent TRANCAT-ACCT-ID abends against the account file")
        void anAbsentAccountIdAbends() {
            TransactionCategoryBalance item = categoryBalance(ACCOUNT_A, "1.00");
            setField(item, "id", blankCategoryBalanceKey());

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> processor.process(item))
                    .satisfies(abend -> {
                        assertThat(abend.getAbendReason()).isEqualTo("ERROR READING ACCOUNT FILE");
                        assertThat(abend.getAbendMessage()).contains("TRANCAT-ACCT-ID is absent");
                    });
        }

        @Test
        @DisplayName("an absent TRAN-CAT-BAL abends, because PIC S9(09)V99 cannot be null")
        void anAbsentBalanceAbends() {
            TransactionCategoryBalance item = categoryBalance(ACCOUNT_A, "1.00");
            setField(item, "balance", null);

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> processor.process(item))
                    .satisfies(abend -> assertThat(abend.getAbendReason())
                            .isEqualTo("MISSING TRANSACTION CATEGORY BALANCE"));
        }

        @Test
        @DisplayName("an absent ACCT-GROUP-ID abends against the disclosure group file")
        void anAbsentGroupIdAbends() {
            Account account = setField(account(ACCOUNT_A, "100.00", GROUP_ID), "groupId", null);
            Mockito.when(accountRepository.findById(ACCOUNT_A)).thenReturn(Optional.of(account));
            Mockito.when(cardCrossReferenceRepository.findFirstByAccountIdOrderByCardNumberAsc(ACCOUNT_A))
                    .thenReturn(Optional.of(crossReference(ACCOUNT_A, CARD_A)));

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> processor.process(categoryBalance(ACCOUNT_A, "100.00")))
                    .satisfies(abend -> {
                        assertThat(abend.getAbendReason())
                                .isEqualTo(FileStatusMapper.DISCGRP_READ_FAILURE_TEXT);
                        assertThat(abend.getAbendMessage())
                                .contains("ACCT-GROUP-ID is absent")
                                .contains("a blank group is not a null one");
                    });
        }

        @Test
        @DisplayName("an absent DIS-INT-RATE abends, because absent is not zero")
        void anAbsentInterestRateAbends() {
            stubAccount(ACCOUNT_A, "100.00", CARD_A);
            DisclosureGroup group =
                    setField(disclosureGroup(GROUP_ID, "1.00"), "interestRate", null);
            Mockito.when(disclosureGroupRepository.findById(
                    new DisclosureGroupId(GROUP_ID, TYPE_CD, CAT_CD))).thenReturn(Optional.of(group));

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> processor.process(categoryBalance(ACCOUNT_A, "100.00")))
                    .satisfies(abend -> assertThat(abend.getAbendMessage())
                            .contains("DIS-INT-RATE is absent")
                            .contains("absent is not zero"));
        }
    }

    @Nested
    @DisplayName("3. The control break of :L194-L208: flush, then reset, then reload")
    class ControlBreak {

        @Test
        @DisplayName("the first record flushes nothing, because :L198 has no previous account")
        void theFirstRecordFlushesNothing() {
            stubAccount(ACCOUNT_A, "100.00", CARD_A);
            stubDirectRate("12.00");

            processor.process(categoryBalance(ACCOUNT_A, "100.00"));

            Mockito.verify(accountRepository, Mockito.never()).save(Mockito.any());
        }

        @Test
        @DisplayName("a second record for the same account does not break and does not reload")
        void aSecondRecordForTheSameAccountDoesNotBreak() {
            stubAccount(ACCOUNT_A, "100.00", CARD_A);
            stubAnyRate("12.00");

            processor.process(categoryBalance(ACCOUNT_A, TYPE_CD, CAT_CD, "100.00"));
            processor.process(categoryBalance(ACCOUNT_A, "02", 6, "100.00"));

            Mockito.verify(accountRepository, Mockito.times(1)).findById(ACCOUNT_A);
            Mockito.verify(cardCrossReferenceRepository, Mockito.times(1))
                    .findFirstByAccountIdOrderByCardNumberAsc(ACCOUNT_A);
            Mockito.verify(accountRepository, Mockito.never()).save(Mockito.any());
        }

        @Test
        @DisplayName("a new account flushes the PREVIOUS account's accumulated interest, per :L196")
        void aNewAccountFlushesThePreviousOne() {
            Account first = stubAccount(ACCOUNT_A, "100.00", CARD_A);
            stubAccount(ACCOUNT_B, "200.00", CARD_B);
            stubDirectRate("12.00");

            // 100.00 * 12.00 / 1200 = 1.00 for the first account.
            processor.process(categoryBalance(ACCOUNT_A, "100.00"));
            processor.process(categoryBalance(ACCOUNT_B, "100.00"));

            ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
            Mockito.verify(accountRepository).save(captor.capture());
            assertThat(captor.getValue()).isSameAs(first);
            assertThat(first.getCurrentBalance()).isEqualByComparingTo(new BigDecimal("101.00"));
        }

        @Test
        @DisplayName("interest accumulates across every record of one account before the flush")
        void interestAccumulatesAcrossTheAccount() {
            Account first = stubAccount(ACCOUNT_A, "100.00", CARD_A);
            stubAccount(ACCOUNT_B, "200.00", CARD_B);
            stubAnyRate("12.00");

            // Three records at 1.00, 2.00 and 3.00 of interest respectively.
            processor.process(categoryBalance(ACCOUNT_A, TYPE_CD, CAT_CD, "100.00"));
            processor.process(categoryBalance(ACCOUNT_A, "02", 6, "200.00"));
            processor.process(categoryBalance(ACCOUNT_A, "03", 7, "300.00"));
            processor.process(categoryBalance(ACCOUNT_B, "100.00"));

            assertThat(first.getCurrentBalance()).isEqualByComparingTo(new BigDecimal("106.00"));
        }

        @Test
        @DisplayName("the accumulator resets on the break, so no interest leaks between accounts")
        void theAccumulatorResetsOnTheBreak() {
            stubAccount(ACCOUNT_A, "100.00", CARD_A);
            Account second = stubAccount(ACCOUNT_B, "200.00", CARD_B);
            Account third = stubAccount(33L, "300.00", "4333333333333333");
            stubDirectRate("12.00");

            processor.process(categoryBalance(ACCOUNT_A, "100.00"));
            processor.process(categoryBalance(ACCOUNT_B, "500.00"));
            processor.process(categoryBalance(33L, "100.00"));

            // Only the second account's own 5.00 reaches its balance; the first account's 1.00 does not.
            assertThat(second.getCurrentBalance()).isEqualByComparingTo(new BigDecimal("205.00"));
            assertThat(third.getCurrentBalance())
                    .as("the third account has not been flushed at all yet")
                    .isEqualByComparingTo(new BigDecimal("300.00"));
        }

        @Test
        @DisplayName("the break flushes before it reloads, so the write sees the previous account")
        void theBreakFlushesBeforeItReloads() {
            stubAccount(ACCOUNT_A, "100.00", CARD_A);
            stubAccount(ACCOUNT_B, "200.00", CARD_B);
            stubDirectRate("12.00");

            processor.process(categoryBalance(ACCOUNT_A, "100.00"));
            processor.process(categoryBalance(ACCOUNT_B, "100.00"));

            InOrder order = Mockito.inOrder(accountRepository);
            order.verify(accountRepository).findById(ACCOUNT_A);
            order.verify(accountRepository).save(Mockito.any());
            order.verify(accountRepository).findById(ACCOUNT_B);
        }

        @Test
        @DisplayName("the LAST account of the run is never flushed, exactly as :L219 never runs")
        void theLastAccountIsNeverFlushed() {
            Account only = stubAccount(ACCOUNT_A, "100.00", CARD_A);
            stubAnyRate("12.00");

            processor.process(categoryBalance(ACCOUNT_A, TYPE_CD, CAT_CD, "100.00"));
            processor.process(categoryBalance(ACCOUNT_A, "02", 6, "100.00"));

            assertThat(only.getCurrentBalance())
                    .as("no final flush exists and none may be added; see CBACT04C:L188/L218/L219 pairing")
                    .isEqualByComparingTo(new BigDecimal("100.00"));
            Mockito.verify(accountRepository, Mockito.never()).save(Mockito.any());
        }

        @Test
        @DisplayName("the break key is the account alone, so a type or category change does not break")
        void theBreakKeyIsTheAccountAlone() {
            stubAccount(ACCOUNT_A, "100.00", CARD_A);
            stubAnyRate("12.00");

            processor.process(categoryBalance(ACCOUNT_A, "01", 1, "100.00"));
            processor.process(categoryBalance(ACCOUNT_A, "99", 9999, "100.00"));

            Mockito.verify(accountRepository, Mockito.times(1)).findById(ACCOUNT_A);
        }
    }

    @Nested
    @DisplayName("4. 1050-UPDATE-ACCOUNT: both cycle counters are zeroed on every rewrite")
    class AccountRewrite {

        @Test
        @DisplayName("the two cycle counters are zeroed, per :L353-L354")
        void bothCycleCountersAreZeroed() {
            Account first = stubAccount(ACCOUNT_A, "100.00", CARD_A);
            stubAccount(ACCOUNT_B, "200.00", CARD_B);
            stubDirectRate("12.00");
            assertThat(first.getCurrentCycleCredit()).isEqualByComparingTo(new BigDecimal("500.00"));
            assertThat(first.getCurrentCycleDebit()).isEqualByComparingTo(new BigDecimal("-100.00"));

            processor.process(categoryBalance(ACCOUNT_A, "100.00"));
            processor.process(categoryBalance(ACCOUNT_B, "100.00"));

            assertThat(first.getCurrentCycleCredit())
                    .as("omitting this reset corrupts the next posting cycle's over-limit arithmetic")
                    .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(first.getCurrentCycleDebit()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("the zeroed counters carry scale 2, matching NUMERIC(12,2)")
        void theZeroedCountersCarryScaleTwo() {
            Account first = stubAccount(ACCOUNT_A, "100.00", CARD_A);
            stubAccount(ACCOUNT_B, "200.00", CARD_B);
            stubDirectRate("12.00");

            processor.process(categoryBalance(ACCOUNT_A, "100.00"));
            processor.process(categoryBalance(ACCOUNT_B, "100.00"));

            assertThat(first.getCurrentCycleCredit().scale()).isEqualTo(2);
            assertThat(first.getCurrentCycleDebit().scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("an absent ACCT-CURR-BAL on the flushed account abends")
        void anAbsentCurrentBalanceAbends() {
            Account first = stubAccount(ACCOUNT_A, "100.00", CARD_A);
            stubAccount(ACCOUNT_B, "200.00", CARD_B);
            stubDirectRate("12.00");
            processor.process(categoryBalance(ACCOUNT_A, "100.00"));
            setField(first, "currentBalance", null);

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> processor.process(categoryBalance(ACCOUNT_B, "100.00")))
                    .satisfies(abend -> {
                        assertThat(abend.getAbendReason()).isEqualTo("ERROR RE-WRITING ACCOUNT FILE");
                        assertThat(abend.getAbendMessage())
                                .contains("ACCT-CURR-BAL is absent on account 00000000011");
                    });
        }

        @Test
        @DisplayName("a store failure on the rewrite abends with the source's own DISPLAY text")
        void aStoreFailureOnTheRewriteAbends() {
            stubAccount(ACCOUNT_A, "100.00", CARD_A);
            stubAccount(ACCOUNT_B, "200.00", CARD_B);
            stubDirectRate("12.00");
            CannotAcquireLockException lockFailure = new CannotAcquireLockException("row locked");
            Mockito.when(accountRepository.save(Mockito.any())).thenThrow(lockFailure);
            processor.process(categoryBalance(ACCOUNT_A, "100.00"));

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> processor.process(categoryBalance(ACCOUNT_B, "100.00")))
                    .satisfies(abend -> {
                        assertThat(abend.getAbendReason()).isEqualTo("ERROR RE-WRITING ACCOUNT FILE");
                        assertThat(abend.getAbendMessage())
                                .contains("Rewrite failed for account 00000000011");
                        assertThat(abend).hasCause(lockFailure);
                    });
            assertThat(loggedMessages()).contains("ERROR RE-WRITING ACCOUNT FILE");
        }
    }

    @Nested
    @DisplayName("5. 1300-COMPUTE-INTEREST: balance times rate divided by the literal 1200")
    class InterestFormula {

        @ParameterizedTest(name = "balance {0} at rate {1} yields {2}")
        @CsvSource({
            // The last two rows are HALF_EVEN ties: 6/1200 is exactly 0.005 and 18/1200 exactly 0.015.
            // HALF_UP would produce 0.01 and 0.02; HALF_EVEN produces 0.00 and 0.02.
            "100.00,   15.00, 1.25",
            "1000.00,  1.00,  0.83",
            "1200.00,  1.00,  1.00",
            "-100.00,  15.00, -1.25",
            "0.00,      15.00, 0.00",
            "6.00,      1.00,  0.00",
            "18.00,     1.00,  0.02"
        })
        @DisplayName("the formula multiplies first and divides by 1200 with HALF_EVEN at scale 2")
        void theFormulaIsTranscribedVerbatim(final String balance, final String rate,
                                             final String expectedInterest) {
            stubAccount(ACCOUNT_A, "100.00", CARD_A);
            stubDirectRate(rate);

            Transaction emitted = processor.process(categoryBalance(ACCOUNT_A, balance));

            if (new BigDecimal(expectedInterest).compareTo(BigDecimal.ZERO) == 0
                    && new BigDecimal(balance).compareTo(BigDecimal.ZERO) == 0) {
                // A zero balance at a non-zero rate still emits a transaction of zero value; only a zero
                // RATE suppresses the record, which group 6 covers.
                assertThat(emitted).isNotNull();
            }
            assertThat(emitted).isNotNull();
            assertThat(emitted.getAmount()).isEqualByComparingTo(new BigDecimal(expectedInterest));
            assertThat(emitted.getAmount().scale())
                    .as("TRAN-AMT is PIC S9(09)V99, so the quotient is taken at scale two")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("a negative balance yields negative interest, with no absolute value taken")
        void aNegativeBalanceYieldsNegativeInterest() {
            Account first = stubAccount(ACCOUNT_A, "100.00", CARD_A);
            stubAccount(ACCOUNT_B, "200.00", CARD_B);
            stubDirectRate("12.00");

            processor.process(categoryBalance(ACCOUNT_A, "-100.00"));
            processor.process(categoryBalance(ACCOUNT_B, "100.00"));

            assertThat(first.getCurrentBalance()).isEqualByComparingTo(new BigDecimal("99.00"));
        }
    }

    @Nested
    @DisplayName("6. A zero rate emits nothing and accumulates nothing, per :L214-L217")
    class ZeroRate {

        @Test
        @DisplayName("a zero rate suppresses the transaction")
        void aZeroRateSuppressesTheTransaction() {
            stubAccount(ACCOUNT_A, "100.00", CARD_A);
            stubDirectRate("0.00");

            Transaction emitted = processor.process(categoryBalance(ACCOUNT_A, "100.00"));

            assertThat(emitted).isNull();
        }

        @Test
        @DisplayName("a zero rate accumulates nothing, so the flushed balance is unchanged")
        void aZeroRateAccumulatesNothing() {
            Account first = stubAccount(ACCOUNT_A, "100.00", CARD_A);
            stubAccount(ACCOUNT_B, "200.00", CARD_B);
            stubDirectRate("0.00");

            processor.process(categoryBalance(ACCOUNT_A, "100.00"));
            processor.process(categoryBalance(ACCOUNT_B, "100.00"));

            assertThat(first.getCurrentBalance()).isEqualByComparingTo(new BigDecimal("100.00"));
        }

        @Test
        @DisplayName("a rate of 0 and a rate of 0.00 are both zero, because compareTo not equals is used")
        void bothSpellingsOfZeroSuppress() {
            stubAccount(ACCOUNT_A, "100.00", CARD_A);
            Mockito.when(disclosureGroupRepository.findById(
                            new DisclosureGroupId(GROUP_ID, TYPE_CD, CAT_CD)))
                    .thenReturn(Optional.of(disclosureGroup(GROUP_ID, "0")));

            assertThat(processor.process(categoryBalance(ACCOUNT_A, "100.00")))
                    .as("BigDecimal 0 and 0.00 are unequal under equals and equal under compareTo")
                    .isNull();
        }

        @Test
        @DisplayName("a zero rate does not consume an identifier suffix")
        void aZeroRateDoesNotConsumeASuffix() {
            stubAccount(ACCOUNT_A, "100.00", CARD_A);
            Mockito.when(disclosureGroupRepository.findById(Mockito.any()))
                    .thenReturn(Optional.of(disclosureGroup(GROUP_ID, "0.00")))
                    .thenReturn(Optional.of(disclosureGroup(GROUP_ID, "12.00")));

            processor.process(categoryBalance(ACCOUNT_A, TYPE_CD, CAT_CD, "100.00"));
            Transaction second = processor.process(categoryBalance(ACCOUNT_A, "02", 6, "100.00"));

            assertThat(second.getTransactionId())
                    .as("the suffix is incremented in 1300-B-WRITE-TX, which a zero rate never reaches")
                    .isEqualTo(PARM_DATE + "000001");
        }
    }

    @Nested
    @DisplayName("7. The two-stage rate lookup: '23' retries with DEFAULT, then '00' only")
    class DefaultGroupFallback {

        /** The literal group identifier {@code :L440} substitutes. */
        private static final String DEFAULT_GROUP = "DEFAULT";

        @Test
        @DisplayName("stage one tolerates a record not found and logs both DISPLAY lines")
        void stageOneToleratesRecordNotFound() {
            stubAccount(ACCOUNT_A, "100.00", CARD_A);
            Mockito.when(disclosureGroupRepository.findById(Mockito.any())).thenReturn(Optional.empty());
            Mockito.when(disclosureGroupRepository.findDefaultGroupRate(TYPE_CD, CAT_CD))
                    .thenReturn(Optional.of(disclosureGroup(DEFAULT_GROUP, "15.00")));

            Transaction emitted = processor.process(categoryBalance(ACCOUNT_A, "100.00"));

            assertThat(emitted).isNotNull();
            assertThat(loggedMessages())
                    .contains("DISCLOSURE GROUP RECORD MISSING", "TRY WITH DEFAULT GROUP CODE");
        }

        @Test
        @DisplayName("the retry uses the type and category alone, because the group is substituted")
        void theRetryUsesTypeAndCategoryAlone() {
            stubAccount(ACCOUNT_A, "100.00", CARD_A);
            Mockito.when(disclosureGroupRepository.findById(Mockito.any())).thenReturn(Optional.empty());
            Mockito.when(disclosureGroupRepository.findDefaultGroupRate("07", 4321))
                    .thenReturn(Optional.of(disclosureGroup(DEFAULT_GROUP, "12.00")));

            Transaction emitted = processor.process(categoryBalance(ACCOUNT_A, "07", 4321, "100.00"));

            assertThat(emitted).isNotNull();
            Mockito.verify(disclosureGroupRepository).findDefaultGroupRate("07", 4321);
        }

        @Test
        @DisplayName("the rate taken from the DEFAULT row is the one the formula uses")
        void theDefaultRateDrivesTheFormula() {
            stubAccount(ACCOUNT_A, "100.00", CARD_A);
            Mockito.when(disclosureGroupRepository.findById(Mockito.any())).thenReturn(Optional.empty());
            Mockito.when(disclosureGroupRepository.findDefaultGroupRate(TYPE_CD, CAT_CD))
                    .thenReturn(Optional.of(disclosureGroup(DEFAULT_GROUP, "15.00")));

            Transaction emitted = processor.process(categoryBalance(ACCOUNT_A, "100.00"));

            // discgrp.txt row 17 carries DEFAULT with rate 00150{ = +15.00, so 100.00 * 15 / 1200 = 1.25.
            assertThat(emitted.getAmount()).isEqualByComparingTo(new BigDecimal("1.25"));
        }

        @Test
        @DisplayName("a DEFAULT row of zero rate suppresses the record, as 17 fixture rows can")
        void aZeroDefaultRateSuppresses() {
            stubAccount(ACCOUNT_A, "100.00", CARD_A);
            Mockito.when(disclosureGroupRepository.findById(Mockito.any())).thenReturn(Optional.empty());
            Mockito.when(disclosureGroupRepository.findDefaultGroupRate(TYPE_CD, CAT_CD))
                    .thenReturn(Optional.of(disclosureGroup(DEFAULT_GROUP, "0.00")));

            assertThat(processor.process(categoryBalance(ACCOUNT_A, "100.00"))).isNull();
        }

        @Test
        @DisplayName("a MISSING DEFAULT row abends: stage two admits '00' alone, per :L446-L459")
        void aMissingDefaultRowAbends() {
            stubAccount(ACCOUNT_A, "100.00", CARD_A);
            Mockito.when(disclosureGroupRepository.findById(Mockito.any())).thenReturn(Optional.empty());
            Mockito.when(disclosureGroupRepository.findDefaultGroupRate(TYPE_CD, CAT_CD))
                    .thenReturn(Optional.empty());

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> processor.process(categoryBalance(ACCOUNT_A, "100.00")))
                    .satisfies(abend -> assertThat(abend.getAbendReason())
                            .isEqualTo(FileStatusMapper.DEFAULT_DISCGRP_READ_FAILURE_TEXT));
            assertThat(loggedMessages())
                    .contains(FileStatusMapper.DEFAULT_DISCGRP_READ_FAILURE_TEXT);
        }

        @Test
        @DisplayName("when stage one resolves, the DEFAULT retry is never attempted")
        void aResolvedStageOneSkipsTheRetry() {
            stubAccount(ACCOUNT_A, "100.00", CARD_A);
            stubDirectRate("12.00");

            processor.process(categoryBalance(ACCOUNT_A, "100.00"));

            Mockito.verify(disclosureGroupRepository, Mockito.never())
                    .findDefaultGroupRate(Mockito.any(), Mockito.any());
            assertThat(loggedMessages()).doesNotContain("TRY WITH DEFAULT GROUP CODE");
        }

        @Test
        @DisplayName("an absent DIS-INT-RATE on the DEFAULT row abends too")
        void anAbsentDefaultRateAbends() {
            stubAccount(ACCOUNT_A, "100.00", CARD_A);
            Mockito.when(disclosureGroupRepository.findById(Mockito.any())).thenReturn(Optional.empty());
            DisclosureGroup defaultRow =
                    setField(disclosureGroup(DEFAULT_GROUP, "1.00"), "interestRate", null);
            Mockito.when(disclosureGroupRepository.findDefaultGroupRate(TYPE_CD, CAT_CD))
                    .thenReturn(Optional.of(defaultRow));

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> processor.process(categoryBalance(ACCOUNT_A, "100.00")))
                    .satisfies(abend -> assertThat(abend.getAbendMessage())
                            .contains("DIS-INT-RATE is absent"));
        }
    }

    @Nested
    @DisplayName("8. The rate key: group from the ACCOUNT, type and category from the balance key")
    class RateKeyComposition {

        @Test
        @DisplayName("the three components are taken from the two places :L210-L212 reads them")
        void theThreeComponentsComeFromTwoPlaces() {
            Account account = account(ACCOUNT_A, "100.00", "PREMIUM");
            Mockito.when(accountRepository.findById(ACCOUNT_A)).thenReturn(Optional.of(account));
            Mockito.when(cardCrossReferenceRepository.findFirstByAccountIdOrderByCardNumberAsc(ACCOUNT_A))
                    .thenReturn(Optional.of(crossReference(ACCOUNT_A, CARD_A)));
            Mockito.when(disclosureGroupRepository.findById(Mockito.any()))
                    .thenReturn(Optional.of(disclosureGroup("PREMIUM", "12.00")));

            processor.process(categoryBalance(ACCOUNT_A, "07", 4321, "100.00"));

            ArgumentCaptor<DisclosureGroupId> captor = ArgumentCaptor.forClass(DisclosureGroupId.class);
            Mockito.verify(disclosureGroupRepository).findById(captor.capture());
            DisclosureGroupId key = captor.getValue();
            assertThat(key.getAccountGroupId())
                    .as("the group identifier is the ACCOUNT's, not the balance record's")
                    .isEqualTo("PREMIUM");
            assertThat(key.getTranTypeCd()).isEqualTo("07");
            assertThat(key.getTranCatCd()).isEqualTo(4321);
        }

        @Test
        @DisplayName("the constructor is called in COPYBOOK order, not in the source's MOVE order")
        void theKeyIsBuiltInCopybookOrder() {
            // app/cpy/CVTRA02Y.cpy:L6-L8 declares group, type, category; :L210-L212 MOVEs group, category,
            // type. The constructor is positional, so a key built in MOVE order would place the category
            // code where the type code belongs and would resolve nothing.
            stubAccount(ACCOUNT_A, "100.00", CARD_A);
            stubDirectRate("12.00");

            Transaction emitted = processor.process(categoryBalance(ACCOUNT_A, "100.00"));

            assertThat(emitted)
                    .as("stubDirectRate keys on DisclosureGroupId(GROUP_ID, TYPE_CD, CAT_CD)")
                    .isNotNull();
        }

        @Test
        @DisplayName("an over-wide ACCT-GROUP-ID abends rather than being silently truncated")
        void anOverWideGroupIdAbends() {
            Account account =
                    setField(account(ACCOUNT_A, "100.00", "0123456789"), "groupId", "01234567890");
            Mockito.when(accountRepository.findById(ACCOUNT_A)).thenReturn(Optional.of(account));
            Mockito.when(cardCrossReferenceRepository.findFirstByAccountIdOrderByCardNumberAsc(ACCOUNT_A))
                    .thenReturn(Optional.of(crossReference(ACCOUNT_A, CARD_A)));

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> processor.process(categoryBalance(ACCOUNT_A, "100.00")))
                    .satisfies(abend -> assertThat(abend.getAbendMessage())
                            .contains("ACCT-GROUP-ID is 11 characters"));
        }
    }

    @Nested
    @DisplayName("9. 1300-B-WRITE-TX: the synthesised interest transaction of :L473-L516")
    class SynthesisedTransaction {

        @Test
        @DisplayName("TRAN-ID is PARM-DATE followed by a six-digit run-sequential suffix")
        void theIdentifierIsParmDatePlusSuffix() {
            stubAccount(ACCOUNT_A, "100.00", CARD_A);
            stubDirectRate("12.00");

            Transaction emitted = processor.process(categoryBalance(ACCOUNT_A, "100.00"));

            assertThat(emitted.getTransactionId())
                    .hasSize(16)
                    .isEqualTo("2022071800" + "000001")
                    .startsWith(PARM_DATE);
        }

        @Test
        @DisplayName("the suffix is run-sequential and is NOT reset per account, per :L474")
        void theSuffixIsRunSequential() {
            stubAccount(ACCOUNT_A, "100.00", CARD_A);
            stubAccount(ACCOUNT_B, "200.00", CARD_B);
            stubAnyRate("12.00");

            Transaction first = processor.process(categoryBalance(ACCOUNT_A, TYPE_CD, CAT_CD, "100.00"));
            Transaction second = processor.process(categoryBalance(ACCOUNT_A, "02", 6, "100.00"));
            Transaction third = processor.process(categoryBalance(ACCOUNT_B, "100.00"));

            assertThat(first.getTransactionId()).isEqualTo(PARM_DATE + "000001");
            assertThat(second.getTransactionId()).isEqualTo(PARM_DATE + "000002");
            assertThat(third.getTransactionId())
                    .as("a per-account reset would restart at 000001 and collide")
                    .isEqualTo(PARM_DATE + "000003");
        }

        @Test
        @DisplayName("the type, category and source are the fixed literals of :L477-L479")
        void theFixedLiteralsAreCarried() {
            stubAccount(ACCOUNT_A, "100.00", CARD_A);
            stubAnyRate("12.00");

            Transaction emitted = processor.process(categoryBalance(ACCOUNT_A, "07", 4321, "100.00"));

            assertThat(emitted.getTypeCode())
                    .as("the emitted type is the literal 01, never the balance record's own type")
                    .isEqualTo("01");
            assertThat(emitted.getCategoryCode()).isEqualTo(5);
            assertThat(emitted.getTransactionSource())
                    .isEqualTo(TransactionSource.SYSTEM.getFixedWidthValue())
                    .hasSize(10)
                    .startsWith("System");
        }

        @Test
        @DisplayName("the description is the literal prefix plus the eleven-digit account, padded to 100")
        void theDescriptionIsPrefixPlusAccount() {
            stubAccount(ACCOUNT_A, "100.00", CARD_A);
            stubDirectRate("12.00");

            Transaction emitted = processor.process(categoryBalance(ACCOUNT_A, "100.00"));

            assertThat(emitted.getDescription())
                    .hasSize(100)
                    .startsWith("Int. for a/c " + String.format(Locale.ROOT, "%011d", ACCOUNT_A));
            assertThat(emitted.getDescription().substring(24)).isBlank();
        }

        @Test
        @DisplayName("the merchant fields are zero and spaces, per :L487-L490")
        void theMerchantFieldsAreEmpty() {
            stubAccount(ACCOUNT_A, "100.00", CARD_A);
            stubDirectRate("12.00");

            Transaction emitted = processor.process(categoryBalance(ACCOUNT_A, "100.00"));

            assertThat(emitted.getMerchantId()).isZero();
            assertThat(emitted.getMerchantName()).hasSize(50).isBlank();
            assertThat(emitted.getMerchantCity()).hasSize(50).isBlank();
            assertThat(emitted.getMerchantZip()).hasSize(10).isBlank();
        }

        @Test
        @DisplayName("the card number comes from the cross-reference row, per :L492")
        void theCardNumberComesFromTheCrossReference() {
            stubAccount(ACCOUNT_A, "100.00", CARD_A);
            stubDirectRate("12.00");

            Transaction emitted = processor.process(categoryBalance(ACCOUNT_A, "100.00"));

            assertThat(emitted.getCardNumber()).isEqualTo(CARD_A);
        }

        @Test
        @DisplayName("both timestamps are the SAME generated value, per :L496-L498")
        void bothTimestampsAreTheSameValue() {
            stubAccount(ACCOUNT_A, "100.00", CARD_A);
            stubDirectRate("12.00");

            Transaction emitted = processor.process(categoryBalance(ACCOUNT_A, "100.00"));

            assertThat(emitted.getOrigTs())
                    .hasSize(26)
                    .endsWith("0000")
                    .isEqualTo(EXPECTED_TIMESTAMP)
                    .isEqualTo(emitted.getProcTs());
        }

        @Test
        @DisplayName("an absent XREF-CARD-NUM abends rather than writing a keyless transaction")
        void anAbsentCardNumberAbends() {
            Account account = account(ACCOUNT_A, "100.00", GROUP_ID);
            CardCrossReference xref = setField(crossReference(ACCOUNT_A, CARD_A), "cardNumber", null);
            Mockito.when(accountRepository.findById(ACCOUNT_A)).thenReturn(Optional.of(account));
            Mockito.when(cardCrossReferenceRepository.findFirstByAccountIdOrderByCardNumberAsc(ACCOUNT_A))
                    .thenReturn(Optional.of(xref));
            stubDirectRate("12.00");

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> processor.process(categoryBalance(ACCOUNT_A, "100.00")))
                    .satisfies(abend -> {
                        assertThat(abend.getAbendReason())
                                .isEqualTo("ERROR WRITING TRANSACTION RECORD");
                        assertThat(abend.getAbendMessage())
                                .contains("XREF-CARD-NUM is absent")
                                .contains("00000000011");
                    });
        }

        @Test
        @DisplayName("the interest transaction is returned rather than written by this class")
        void theTransactionIsReturnedNotWritten() {
            stubAccount(ACCOUNT_A, "100.00", CARD_A);
            stubDirectRate("12.00");

            Transaction emitted = processor.process(categoryBalance(ACCOUNT_A, "100.00"));

            assertThat(emitted).isNotNull();
            // INTCALC.jcl allocates a brand-new sequential generation, so the writer - not the processor -
            // owns emission. The processor reads the cross-reference once and writes nothing at all.
            Mockito.verify(cardCrossReferenceRepository).findFirstByAccountIdOrderByCardNumberAsc(ACCOUNT_A);
            Mockito.verifyNoMoreInteractions(cardCrossReferenceRepository);
            Mockito.verify(accountRepository, Mockito.never()).save(Mockito.any());
        }
    }

    @Nested
    @DisplayName("10. Read failures: every one abends with the source's own DISPLAY text")
    class ReadFailures {

        @Test
        @DisplayName("a failed account read abends with ERROR READING ACCOUNT FILE")
        void aFailedAccountReadAbends() {
            QueryTimeoutException timeout = new QueryTimeoutException("statement timeout");
            Mockito.when(accountRepository.findById(ACCOUNT_A)).thenThrow(timeout);

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> processor.process(categoryBalance(ACCOUNT_A, "100.00")))
                    .satisfies(abend -> {
                        assertThat(abend.getAbendReason()).isEqualTo("ERROR READING ACCOUNT FILE");
                        assertThat(abend.getAbendMessage())
                                .contains("Account read failed for 00000000011");
                        assertThat(abend).hasCause(timeout);
                    });
            assertThat(loggedMessages()).contains("ERROR READING ACCOUNT FILE");
        }

        @Test
        @DisplayName("a missing account abends, carrying the eleven-digit key on the abend not the log")
        void aMissingAccountAbends() {
            Mockito.when(accountRepository.findById(ACCOUNT_A)).thenReturn(Optional.empty());

            // The identifier and the log line have different audiences. The abend payload reaches an
            // operator whose step has just died and who needs to know WHICH account, so it carries the
            // eleven-digit key. The log is aggregated and retained well beyond that, so it announces the
            // same :L375 diagnostic with the key withheld. Both halves are asserted here, in both
            // directions, so neither can drift into the other.
            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> processor.process(categoryBalance(ACCOUNT_A, "100.00")))
                    .satisfies(abend -> assertThat(abend.getAbendMessage())
                            .isEqualTo("ACCOUNT NOT FOUND: 00000000011"));
            assertThat(loggedMessages())
                    .contains("ACCOUNT NOT FOUND: [withheld]", "ERROR READING ACCOUNT FILE");
            // Scoped to the diagnostic under test. The TRAN-CAT-BAL-RECORD parity line legitimately carries
            // TRANCAT-ACCT-ID, because a parity emission reproduces the source record image; what must never
            // carry the key is this friendly :L375 diagnostic.
            assertThat(loggedMessages())
                    .filteredOn(message -> message.startsWith("ACCOUNT NOT FOUND"))
                    .singleElement()
                    .isEqualTo("ACCOUNT NOT FOUND: [withheld]");
        }

        @Test
        @DisplayName("a failed cross-reference read abends with ERROR READING XREF FILE")
        void aFailedCrossReferenceReadAbends() {
            Mockito.when(accountRepository.findById(ACCOUNT_A))
                    .thenReturn(Optional.of(account(ACCOUNT_A, "100.00", GROUP_ID)));
            QueryTimeoutException timeout = new QueryTimeoutException("alternate index timeout");
            Mockito.when(cardCrossReferenceRepository.findFirstByAccountIdOrderByCardNumberAsc(ACCOUNT_A))
                    .thenThrow(timeout);

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> processor.process(categoryBalance(ACCOUNT_A, "100.00")))
                    .satisfies(abend -> {
                        assertThat(abend.getAbendReason()).isEqualTo("ERROR READING XREF FILE");
                        assertThat(abend.getAbendMessage())
                                .contains("Cross-reference read failed for account 00000000011");
                        assertThat(abend).hasCause(timeout);
                    });
        }

        @Test
        @DisplayName("an empty cross-reference read abends: :L393-L413 displays and then hits the guard")
        void anEmptyCrossReferenceReadAbends() {
            Mockito.when(accountRepository.findById(ACCOUNT_A))
                    .thenReturn(Optional.of(account(ACCOUNT_A, "100.00", GROUP_ID)));
            Mockito.when(cardCrossReferenceRepository.findFirstByAccountIdOrderByCardNumberAsc(ACCOUNT_A))
                    .thenReturn(Optional.empty());

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> processor.process(categoryBalance(ACCOUNT_A, "100.00")))
                    .satisfies(abend -> {
                        assertThat(abend.getAbendReason()).isEqualTo("ERROR READING XREF FILE");
                        assertThat(abend.getAbendMessage()).isEqualTo("ACCOUNT NOT FOUND: 00000000011");
                    });
        }

        @Test
        @DisplayName("a null cross-reference result is treated as an empty one, never dereferenced")
        void aNullCrossReferenceResultAbends() {
            Mockito.when(accountRepository.findById(ACCOUNT_A))
                    .thenReturn(Optional.of(account(ACCOUNT_A, "100.00", GROUP_ID)));
            Mockito.when(cardCrossReferenceRepository.findFirstByAccountIdOrderByCardNumberAsc(ACCOUNT_A))
                    .thenReturn(null);

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> processor.process(categoryBalance(ACCOUNT_A, "100.00")))
                    .satisfies(abend -> assertThat(abend.getAbendReason())
                            .isEqualTo("ERROR READING XREF FILE"));
        }

        @Test
        @DisplayName("a failed disclosure group read abends with the mapper's own failure text")
        void aFailedDisclosureGroupReadAbends() {
            stubAccount(ACCOUNT_A, "100.00", CARD_A);
            QueryTimeoutException timeout = new QueryTimeoutException("discgrp timeout");
            Mockito.when(disclosureGroupRepository.findById(Mockito.any())).thenThrow(timeout);

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> processor.process(categoryBalance(ACCOUNT_A, "100.00")))
                    .satisfies(abend -> {
                        assertThat(abend.getAbendReason())
                                .isEqualTo(FileStatusMapper.DISCGRP_READ_FAILURE_TEXT);
                        assertThat(abend.getAbendMessage()).contains("Disclosure group read failed for");
                        assertThat(abend).hasCause(timeout);
                    });
        }

        @Test
        @DisplayName("a failed DEFAULT group read abends with the default failure text")
        void aFailedDefaultGroupReadAbends() {
            stubAccount(ACCOUNT_A, "100.00", CARD_A);
            Mockito.when(disclosureGroupRepository.findById(Mockito.any())).thenReturn(Optional.empty());
            QueryTimeoutException timeout = new QueryTimeoutException("default discgrp timeout");
            Mockito.when(disclosureGroupRepository.findDefaultGroupRate(TYPE_CD, CAT_CD))
                    .thenThrow(timeout);

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> processor.process(categoryBalance(ACCOUNT_A, "100.00")))
                    .satisfies(abend -> {
                        assertThat(abend.getAbendReason())
                                .isEqualTo(FileStatusMapper.DEFAULT_DISCGRP_READ_FAILURE_TEXT);
                        assertThat(abend.getAbendMessage())
                                .contains("Default disclosure group read failed for type 01 category 5");
                        assertThat(abend).hasCause(timeout);
                    });
        }
    }

    @Nested
    @DisplayName("11. The two retained paragraphs: an empty one that runs and a live one that cannot")
    class RetainedParagraphs {

        @Test
        @DisplayName("1400-COMPUTE-FEES is declared, empty and reachable, per :L518-L520")
        void computeFeesIsDeclaredEmptyAndReachable() throws ReflectiveOperationException {
            Method computeFees =
                    InterestCalculationProcessor.class.getDeclaredMethod("computeFees");

            assertThat(computeFees.getReturnType())
                    .as("the paragraph is retained so the map for :L216 stays provable; do not delete it")
                    .isEqualTo(void.class);
            assertThat(computeFees.getParameterCount()).isZero();
        }

        @Test
        @DisplayName("the fee paragraph performs no work, so a posted record touches nothing extra")
        void theFeeParagraphPerformsNoWork() {
            stubAccount(ACCOUNT_A, "100.00", CARD_A);
            stubDirectRate("12.00");

            processor.process(categoryBalance(ACCOUNT_A, "100.00"));

            Mockito.verify(accountRepository).findById(ACCOUNT_A);
            Mockito.verify(cardCrossReferenceRepository).findFirstByAccountIdOrderByCardNumberAsc(ACCOUNT_A);
            Mockito.verify(disclosureGroupRepository).findById(Mockito.any());
            Mockito.verifyNoMoreInteractions(accountRepository, cardCrossReferenceRepository,
                    disclosureGroupRepository);
        }

        @Test
        @DisplayName("the unreachable ELSE arm of :L219-L220 is retained and still delegates to the flush")
        void theUnreachableArmStillDelegates() {
            Account only = stubAccount(ACCOUNT_A, "100.00", CARD_A);
            stubDirectRate("12.00");
            processor.process(categoryBalance(ACCOUNT_A, "100.00"));
            assertThat(only.getCurrentBalance()).isEqualByComparingTo(new BigDecimal("100.00"));

            invokeUnreachableEndOfFileArm();

            assertThat(only.getCurrentBalance())
                    .as("invoked directly it flushes; nothing in the normal path invokes it")
                    .isEqualByComparingTo(new BigDecimal("101.00"));
            Mockito.verify(accountRepository).save(only);
        }

        @Test
        @DisplayName("the retained arm abends when no account is loaded, rather than doing nothing")
        void theRetainedArmAbendsWithNoAccountLoaded() {
            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(this::flushWithNothingLoaded)
                    .satisfies(abend -> {
                        assertThat(abend.getAbendReason()).isEqualTo("ERROR RE-WRITING ACCOUNT FILE");
                        assertThat(abend.getAbendMessage())
                                .contains("1050-UPDATE-ACCOUNT was reached with no account record loaded");
                    });
        }

        /** Invokes the retained arm before any control break has loaded an account. */
        private void flushWithNothingLoaded() {
            invokeUnreachableEndOfFileArm();
        }
    }

    @Nested
    @DisplayName("12. Diagnostic hygiene: the account is rendered, the card number never is")
    class DiagnosticHygiene {

        @Test
        @DisplayName("no log record carries the primary account number")
        void noLogRecordCarriesTheCardNumber() {
            stubAccount(ACCOUNT_A, "100.00", CARD_A);
            stubAccount(ACCOUNT_B, "200.00", CARD_B);
            stubDirectRate("12.00");

            processor.process(categoryBalance(ACCOUNT_A, "100.00"));
            processor.process(categoryBalance(ACCOUNT_B, "100.00"));

            assertThat(loggedMessages())
                    .noneMatch(message -> message.contains(CARD_A) || message.contains(CARD_B));
        }

        @Test
        @DisplayName("the record display renders the account as eleven zero-filled digits")
        void theRecordDisplayRendersElevenDigits() {
            stubAccount(ACCOUNT_A, "100.00", CARD_A);
            stubDirectRate("12.00");

            processor.process(categoryBalance(ACCOUNT_A, "100.00"));

            assertThat(loggedMessages())
                    .anyMatch(message -> message.contains("TRAN-CAT-BAL-RECORD sequence=1")
                            && message.contains("TRANCAT-ACCT-ID=00000000011")
                            && message.contains("TRANCAT-TYPE-CD=01")
                            && message.contains("TRANCAT-CD=5"));
        }

        @Test
        @DisplayName("the record sequence counter advances once per record")
        void theSequenceCounterAdvancesPerRecord() {
            stubAccount(ACCOUNT_A, "100.00", CARD_A);
            stubAnyRate("12.00");

            processor.process(categoryBalance(ACCOUNT_A, TYPE_CD, CAT_CD, "100.00"));
            processor.process(categoryBalance(ACCOUNT_A, "02", 6, "100.00"));

            assertThat(loggedMessages())
                    .anyMatch(message -> message.contains("TRAN-CAT-BAL-RECORD sequence=2"));
        }
    }
}

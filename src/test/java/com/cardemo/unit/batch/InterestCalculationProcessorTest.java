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
 * Source      : app/cbl/CBACT04C.cbl:L27-L56   (FILE-CONTROL)
 *               app/cbl/CBACT04C.cbl:L173       (WS-TRANID-SUFFIX)
 *               app/cbl/CBACT04C.cbl:L175-L178  (LINKAGE PARM-DATE)
 *               app/cbl/CBACT04C.cbl:L180       (PROCEDURE USING)
 *               app/cbl/CBACT04C.cbl:L188-L222 (1000-TCATBALF loop)
 *               app/cbl/CBACT04C.cbl:L194-L208 (control break)
 *               app/cbl/CBACT04C.cbl:L219-L220 (end-of-data ELSE arm)
 *               app/cbl/CBACT04C.cbl:L281       (DALY REJECTS defect)
 *               app/cbl/CBACT04C.cbl:L309       (OPEN OUTPUT TRANSACT)
 *               app/cbl/CBACT04C.cbl:L325-L348 (1000-TCATBALF-GET-NEXT)
 *               app/cbl/CBACT04C.cbl:L350-L370 (1050-UPDATE-ACCOUNT)
 *               app/cbl/CBACT04C.cbl:L372-L391 (1100-GET-ACCT-DATA)
 *               app/cbl/CBACT04C.cbl:L393-L413 (1110-GET-XREF-DATA)
 *               app/cbl/CBACT04C.cbl:L415-L440 (1200-GET-INTEREST-RATE)
 *               app/cbl/CBACT04C.cbl:L443-L460 (1200-A default rate)
 *               app/cbl/CBACT04C.cbl:L462-L470 (1300-COMPUTE-INTEREST)
 *               app/cbl/CBACT04C.cbl:L473-L515 (1300-B-WRITE-TX)
 *               app/cbl/CBACT04C.cbl:L518-L520 (1400-COMPUTE-FEES)
 *               app/cpy/CVTRA01Y.cpy:L5-L9     (TRAN-CAT-BAL record)
 *               app/cpy/CVTRA02Y.cpy:L6-L9     (DIS-INT-RATE key)
 *               app/cpy/CVACT01Y.cpy           (ACCT money fields)
 *               app/cpy/CVACT03Y.cpy           (XREF-CARD-NUM)
 *               app/cpy/CVTRA05Y.cpy           (TRAN-RECORD, 350 B)
 *               app/jcl/INTCALC.jcl            (PARM='2022071800',
 *                                               SYSTRAN(+1) LRECL=350,
 *                                               XREFFILE + XREFFIL1)
 *               app/data/ASCII/acctdata.txt    (ACCT-GROUP-ID blank)
 *               app/data/ASCII/tcatbal.txt     (010001, all +0.00)
 *               app/data/ASCII/discgrp.txt     (17 DEFAULT rows of 51,
 *                                               7 of them zero-rate)
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
import com.cardemo.unit.model.FixedClockProvider;
import com.cardemo.unit.model.FixtureLoader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
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
import org.springframework.data.jpa.repository.Query;

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
 * it is concatenated with a six-digit suffix to form {@code TRAN-ID PIC X(16)}. The clock is pinned
 * through {@link com.cardemo.unit.model.FixedClockProvider}, the sibling tier's single time source, so
 * that both generated timestamps are asserted as values; no test in this class reads a wall clock, a
 * default locale, a default zone or a random source. Mockito runs in its lenient default mode rather than
 * under {@code MockitoExtension}: several groups deliberately stub a superset of what a given path
 * consumes - a zero-rate record never reaches the cross-reference read, for instance - and strict stubbing
 * would report those as failures instead of letting the assertion speak. Argument verification is done
 * explicitly with {@code Mockito.verify} and {@code verifyNoMoreInteractions} where it matters, which is
 * the stronger statement.
 *
 * <p><b>The three retained parity artefacts, and the one Rule 1 conflict they raise.</b> Rule 1 clause B
 * forbids dead code; the parity mandate requires reproducing reachable no-ops and preserving legacy
 * defects verbatim. <b>Parity governs</b>, and clause B's actual intent - no <em>untracked</em> residue -
 * is satisfied because each artefact is marked in code, asserted here, and is owed
 * an entry in the planned {@code DECISION_LOG.md} plus a row in the planned
 * {@code TRACEABILITY_MATRIX.md}. Deleting any of them would fail
 * a stated acceptance criterion (the paragraph map that gate 7 verifies) to satisfy a stylistic one. All
 * three instances that belong to {@code CBACT04C} are covered here:
 * <ol>
 * <li><b>The canonical instance.</b> {@code 1400-COMPUTE-FEES} at {@code app/cbl/CBACT04C.cbl:L518}-{@code :L520}
 * is empty apart from the comment {@code * To be implemented}, has no {@code -EXIT} label, and is
 * genuinely {@code PERFORM}ed at {@code :L216}. Retained as an empty private method; group 11 asserts it
 * exists, runs on the non-zero-rate path and does nothing.</li>
 * <li>The {@code ELSE} arm at {@code :L219}-{@code :L220} belongs to the outer {@code IF} at
 * {@code :L189}, so the flush the source reaches is the one at {@code :L196}. The arm is retained and
 * performs the whole of {@code 1050-UPDATE-ACCOUNT}; groups 3 and 11 assert both halves.</li>
 * <li>{@code 0200-DISCGRP-OPEN} displays {@code 'ERROR OPENING DALY REJECTS FILE'} at
 * {@code :L281}, in a program that has no rejects file at all - a copy-and-paste defect. Preserved
 * verbatim rather than corrected to {@code 'ERROR OPENING DISCLOSURE GROUP FILE'}; group 14 asserts the
 * literal.</li>
 * </ol>
 *
 * <p><b>Not available.</b> Two things a reviewer might expect to see asserted here do not exist and are
 * not fabricated. First, <b>no service-level objective</b> exists anywhere in the source corpus - the
 * COBOL publishes no throughput or latency target - so this class asserts no timing bound and no
 * performance threshold; what is needed to add one is a stated objective from the business, not a
 * measurement. Second, <b>no captured legacy output baseline</b> exists: the repository ships the input
 * fixtures under {@code app/data/ASCII/} but no recorded z/OS output for them, so every expected value
 * here is derived from the source's own arithmetic and literals rather than compared against a recorded
 * run; what is needed to strengthen that is an execution of {@code app/jcl/INTCALC.jcl} on a z/OS system
 * with its {@code SYSTRAN(+1)} generation captured.
 *
 * <p><b>What this suite asserts about the end-of-data arm, and where the variance is disclosed.</b> Every
 * assertion below is derived from the primary source and cited to it. The reachability of
 * {@code 1050-UPDATE-ACCOUNT} follows from the indentation at {@code app/cbl/CBACT04C.cbl:L188},
 * {@code :L191}, {@code :L218}, {@code :L219} and {@code :L221}: the {@code ELSE} at {@code :L219} pairs
 * with the outer {@code IF} at {@code :L189}, both at column 16, while the inner {@code IF} and its
 * {@code END-IF} at {@code :L191} and {@code :L218} sit at column 20. Group 3 pins where the flush is
 * reached from and what it does when it is - the completed account written, its accumulated interest
 * posted, both cycle accumulators reset - and group 11 pins that the retained arm still delegates to that
 * same flush when invoked directly. This suite states no conclusion about the specification prose: the one
 * authoritative record of every prose-versus-corpus variance in this tree, with its severity and its
 * remediation, is the register in {@code src/main/java/com/cardemo/package-info.java}, and duplicating it
 * in an assertion message is what turns a test from parity evidence into a second specification.
 *
 * <p><b>Common failure modes and troubleshooting.</b>
 * <ul>
 * <li>A failure in group 5 means the formula has been algebraically rewritten. It must
 * multiply and only then divide by the literal {@code 1200} with {@code HALF_EVEN} at scale two; dividing
 * by 100 and then by 12, or multiplying by a decimal rate, rounds differently.</li>
 * <li>A failure in group 3 means the flush-reset-reload ordering of {@code :L196} to
 * {@code :L203} has moved. Any other order posts one account's interest onto another account's
 * balance.</li>
 * <li>A failure in group 4 means the two cycle counters are no longer zeroed on the account
 * rewrite, which corrupts the over-limit arithmetic of the following posting cycle.</li>
 * <li>A failure in group 7 means the two-stage rate lookup has changed shape: stage one must
 * tolerate a record not found and retry with the literal {@code DEFAULT} group, and stage two must
 * abend.</li>
 * <li>A failure in group 6 means a zero rate is no longer suppressing the transaction, so
 * the run would emit interest rows of zero value that the source never writes.</li>
 * <li>A failure in group 14's two stale-rate tests means a rate is being cached across
 * records. In the source the {@code INVALID KEY} arm at {@code app/cbl/CBACT04C.cbl:L416} only displays and
 * leaves the previous iteration's record area intact, so the Java path must resolve the rate freshly per
 * record and hold no rate state; a cache silently posts one category's rate onto another's balance.</li>
 * <li><b>The shipped fixtures exercise almost
 * none of this program's logic, so synthetic data is mandatory.</b> Group 13 measures the three reasons:
 * {@code app/data/ASCII/acctdata.txt} carries a blank {@code ACCT-GROUP-ID} on all fifty rows, so the
 * stage-one rate read always misses and the direct-hit arm is unreachable; every row of
 * {@code app/data/ASCII/tcatbal.txt} is type-and-category {@code 010001}, whose {@code DEFAULT} rate is
 * {@code +15.00}, so the zero-rate suppression arm is unreachable; and every shipped {@code TRAN-CAT-BAL}
 * decodes to {@code +0.00}, so the formula, the rounding mode and the {@code NUMERIC(11,2)} scale are all
 * invisible. A suite driven only by shipped data would pass while measuring nothing. The remediation is
 * synthetic rows - never an edited fixture, because {@code app/**} is frozen and is the parity oracle.</li>
 * <li>A failure in group 12 means a card number has reached a log record.</li>
 * <li>A failure in group 14's first test means the preserved {@code 'ERROR OPENING DALY REJECTS
 * FILE'} literal of {@code :L281} has been "corrected". It must not be: it is legacy output the parity
 * comparison is measured against.</li>
 * <li><b>Build.</b> A compilation failure naming an unused import is the zero-warning gate doing its job -
 * {@code failOnWarning} with {@code -Xlint:all -Werror} reaches test compilation, so an import left behind
 * by a deleted assertion fails the build rather than lingering.</li>
 * </ul>
 */
@DisplayName("InterestCalculationProcessor: CBACT04C's control break, formula and retained no-ops")
class InterestCalculationProcessorTest {

    /** The value {@code app/jcl/INTCALC.jcl} supplies: eight date digits then two zeros. */
    private static final String PARM_DATE = "2022071800";

    /**
     * The instant this suite pins, chosen to fall inside the run {@code PARM-DATE} names.
     *
     * <p>Held separately from the clock so that {@link #EXPECTED_TIMESTAMP} can be derived from it through
     * the same helper the rest of the test tier uses, rather than transcribed by hand.
     */
    private static final Instant FIXED_INSTANT = Instant.parse("2022-07-18T04:05:06.070Z");

    /**
     * A pinned clock so that both generated timestamps are values rather than shapes.
     *
     * <p>Built by {@link FixedClockProvider#fixedClock(Instant)} rather than by a local
     * {@code Clock.fixed} call. The provider is the sibling tier's single time source and pins the zone to
     * {@link FixedClockProvider#CANONICAL_ZONE}; duplicating its two lines here would give this class a
     * second, independently drifting definition of "fixed", which Rule 1 clause C rules out. Nothing in
     * this class calls {@code now()} without a clock argument.
     */
    private static final Clock FIXED_CLOCK = FixedClockProvider.fixedClock(FIXED_INSTANT);

    /**
     * What {@code Z-GET-DB2-FORMAT-TIMESTAMP} at {@code app/cbl/CBACT04C.cbl:L613} renders for
     * {@link #FIXED_CLOCK}: {@code yyyy-MM-dd-HH.mm.ss.SS} then four literal zeros, 26 characters with a
     * dash - not a space - between the day and the hour.
     *
     * <p>Derived through {@link FixedClockProvider#batchTimestamp(Clock)} rather than written out, so the
     * expectation and the production formatter cannot drift apart silently while both still "look right".
     * Group 9 additionally asserts the literal text and the width, so a fault in the shared helper cannot
     * make this expectation vacuous.
     */
    private static final String EXPECTED_TIMESTAMP = FixedClockProvider.batchTimestamp(FIXED_CLOCK);

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

    /**
     * {@code ACCT-GROUP-ID} of a <em>synthetic</em> account that resolves a rate without the retry.
     *
     * <p>No shipped account can play this role: {@code app/data/ASCII/acctdata.txt} carries ten spaces in
     * columns 113-122 on all fifty rows, which group 13 measures. A non-blank group id therefore has to be
     * invented to reach the direct-hit arm of {@code 1200-GET-INTEREST-RATE} at all.
     */
    private static final String GROUP_ID_DIRECT = "GRPHIT";

    /** One-based first column of {@code ACCT-GROUP-ID PIC X(10)} in a 300-byte account record. */
    private static final int ACCT_GROUP_ID_COLUMN = 113;

    /** Width of {@code ACCT-GROUP-ID PIC X(10)} ({@code app/cpy/CVACT01Y.cpy}). */
    private static final int ACCT_GROUP_ID_WIDTH = 10;

    /** One-based first column of {@code DIS-ACCT-GROUP-ID PIC X(10)} in a 50-byte disclosure row. */
    private static final int DIS_GROUP_ID_COLUMN = 1;

    /** One-based first column of {@code TRANCAT-TYPE-CD} + {@code TRANCAT-CD} in a 50-byte balance row. */
    private static final int TRANCAT_TYPE_AND_CATEGORY_COLUMN = 12;

    /** Combined width of {@code TRANCAT-TYPE-CD PIC X(02)} and {@code TRANCAT-CD PIC 9(04)}. */
    private static final int TRANCAT_TYPE_AND_CATEGORY_WIDTH = 6;

    /** One-based first column of {@code TRAN-CAT-BAL PIC S9(09)V99} ({@code app/cpy/CVTRA01Y.cpy:L9}). */
    private static final int TRAN_CAT_BAL_COLUMN = 18;

    /** One-based first column of {@code DIS-TRAN-TYPE-CD} + {@code DIS-TRAN-CAT-CD}. */
    private static final int DIS_TYPE_AND_CATEGORY_COLUMN = 11;

    /** One-based first column of {@code DIS-INT-RATE PIC S9(04)V99} ({@code app/cpy/CVTRA02Y.cpy:L9}). */
    private static final int DIS_INT_RATE_COLUMN = 17;

    /** The padded probe {@code MOVE 'DEFAULT'} into {@code PIC X(10)} produces: seven characters, three blanks. */
    private static final String DEFAULT_GROUP_ID_PADDED = "DEFAULT   ";

    /** The only distinct {@code ACCT-GROUP-ID} the shipped account fixture carries. */
    private static final String BLANK_GROUP_ID = " ".repeat(ACCT_GROUP_ID_WIDTH);

    /** The only distinct type-and-category pair the shipped balance fixture carries. */
    private static final String SHIPPED_TYPE_AND_CATEGORY = "010001";

    /**
     * The seven zero-rate {@code DEFAULT} pairs of {@code app/data/ASCII/discgrp.txt}, measured not assumed.
     *
     * <p>Each of the seventeen {@code DEFAULT} rows was read at columns {@value #DIS_INT_RATE_COLUMN} to 22
     * and exactly these seven decode to {@code +0.00}; every other {@code DEFAULT} pair carries a non-zero
     * rate. They are the only keys through which the shipped data can reach the zero-rate arm of
     * {@code app/cbl/CBACT04C.cbl:L214}, which is why {@link #ZERO_RATE_TYPE_CD} and
     * {@link #ZERO_RATE_CAT_CD} are drawn from this set rather than chosen freely.
     */
    private static final Set<String> ZERO_RATE_DEFAULT_PAIRS = Set.of(
            "020001", "020002", "020003", "030001", "030002", "030003", "070001");

    /** {@code DIS-TRAN-TYPE-CD} of the zero-rate pair {@code 020001}, drawn from {@link #ZERO_RATE_DEFAULT_PAIRS}. */
    private static final String ZERO_RATE_TYPE_CD = "02";

    /** {@code DIS-TRAN-CAT-CD} of the zero-rate pair {@code 020001}. */
    private static final int ZERO_RATE_CAT_CD = 1;

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
     * Stubs the account and cross-reference reads for one account carrying a chosen {@code ACCT-GROUP-ID}.
     *
     * <p>{@link #stubAccount(long, String, String)} always uses {@link #GROUP_ID}; the fixture-driven and
     * stale-rate groups need the group id to vary, because it is the component of the rate key that the
     * <em>account</em> supplies rather than the balance row.
     *
     * @param accountId the account the control break will load
     * @param currentBalance {@code ACCT-CURR-BAL}
     * @param cardNumber the card the cross-reference resolves
     * @param groupId {@code ACCT-GROUP-ID PIC X(10)}
     * @return the account instance, so a test can assert the mutations applied to it
     */
    private Account stubAccountWithGroup(final long accountId, final String currentBalance,
                                         final String cardNumber, final String groupId) {
        Account account = account(accountId, currentBalance, groupId);
        Mockito.when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        Mockito.when(cardCrossReferenceRepository.findFirstByAccountIdOrderByCardNumberAsc(accountId))
                .thenReturn(Optional.of(crossReference(accountId, cardNumber)));
        return account;
    }

    /**
     * Stubs stage one of the rate lookup to resolve for one exact composite key and no other.
     *
     * <p>Keying the stub is what makes a miss a miss: any key the test does not name falls through to
     * Mockito's default {@link Optional#empty()}, which is the {@code '23'} that
     * {@code app/cbl/CBACT04C.cbl:L436} tests for.
     *
     * @param groupId {@code DIS-ACCT-GROUP-ID}
     * @param typeCd {@code DIS-TRAN-TYPE-CD}
     * @param catCd {@code DIS-TRAN-CAT-CD}
     * @param rate {@code DIS-INT-RATE}
     */
    private void stubKeyedRate(final String groupId, final String typeCd, final int catCd,
                               final String rate) {
        Mockito.when(disclosureGroupRepository.findById(new DisclosureGroupId(groupId, typeCd, catCd)))
                .thenReturn(Optional.of(new DisclosureGroup(
                        new DisclosureGroupId(groupId, typeCd, catCd), new BigDecimal(rate))));
    }

    /**
     * Stubs stage two, the {@code DEFAULT} retry of {@code app/cbl/CBACT04C.cbl:L443}-{@code :L460}.
     *
     * <p>The row it returns carries {@link #DEFAULT_GROUP_ID_PADDED} as its group id, because that is the
     * ten-character image {@code MOVE 'DEFAULT'} leaves in {@code FD-DIS-ACCT-GROUP-ID PIC X(10)}.
     *
     * @param typeCd {@code DIS-TRAN-TYPE-CD} carried into the retry
     * @param catCd {@code DIS-TRAN-CAT-CD} carried into the retry
     * @param rate {@code DIS-INT-RATE} of the default row
     */
    private void stubDefaultRate(final String typeCd, final int catCd, final String rate) {
        Mockito.when(disclosureGroupRepository.findDefaultGroupRate(typeCd, catCd))
                .thenReturn(Optional.of(new DisclosureGroup(
                        new DisclosureGroupId(DEFAULT_GROUP_ID_PADDED, typeCd, catCd),
                        new BigDecimal(rate))));
    }

    /**
     * Reads a repository-relative text file so an assertion can cite the artefact itself as its evidence.
     *
     * <p>Both callers assert a preserved legacy literal, one against the frozen COBOL and one against the
     * Java that reproduces it. Reading the files is what makes those assertions evidence rather than
     * restatement: a test that compared a string constant in this class against the same string constant in
     * this class would pass whatever the corpus said.
     *
     * <p>The path is resolved relative to the process working directory, which Surefire sets to the project
     * base directory - the same convention {@code com.cardemo.unit.model.RecordLayoutCopybook} already
     * relies on for {@code app/cpy}. Nothing outside the repository is read and nothing is written.
     *
     * @param relativePath the repository-relative path, as path segments
     * @return the file's lines, one entry per line, in order
     * @throws UncheckedIOException if the file cannot be read, which for a frozen artefact means the
     *     checkout is not the one this suite documents
     */
    private static List<String> repositoryLines(final String... relativePath) {
        Path path = Path.of(".");
        for (String segment : relativePath) {
            path = path.resolve(segment);
        }
        try {
            // ISO-8859-1 reads any byte sequence without failing, which is the right choice for a frozen
            // fixed-column corpus: the assertion is about characters at known columns, and a decoder that
            // could reject input would turn a parity question into an encoding question.
            return Files.readAllLines(path, StandardCharsets.ISO_8859_1);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(path
                    + " could not be read; this suite cites it as evidence and the citation must resolve",
                    unreadable);
        }
    }

    /**
     * Returns one one-based line of a repository file, the way a locator in this suite's Javadoc reads.
     *
     * @param lineNumber the one-based line number, exactly as cited
     * @param relativePath the repository-relative path, as path segments
     * @return that line, verbatim, including its column padding
     */
    private static String repositoryLine(final int lineNumber, final String... relativePath) {
        List<String> lines = repositoryLines(relativePath);
        assertThat(lines.size())
                .as("%s has fewer than %d lines, so the cited locator cannot resolve",
                        String.join("/", relativePath), lineNumber)
                .isGreaterThanOrEqualTo(lineNumber);
        return lines.get(lineNumber - 1);
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
     * Invokes the retained end-of-data {@code ELSE} arm of {@code app/cbl/CBACT04C.cbl:L219}-{@code :L220}
     * directly.
     *
     * <p>The method is private, and by the indentation pairing cited in the class documentation the source
     * reaches {@code 1050-UPDATE-ACCOUNT} from the control break at {@code :L196}. Invoking the arm
     * reflectively is the only way to prove that it still performs the whole of that paragraph - the save,
     * the interest posting and both cycle resets - which is what makes it a faithful reproduction of the
     * branch rather than abandoned residue. Group 3 covers the reachable path separately.
     */
    private void invokeEndOfFileArm() {
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
                    "updateAccountAtEndOfFile reproduces app/cbl/CBACT04C.cbl:L219-L220 and must remain "
                            + "declared so that the paragraph map for that branch stays provable",
                    reflection);
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
        @DisplayName("1050-UPDATE-ACCOUNT is reached only from the control break at :L196, so an account "
                + "still in progress is not written part way through its own category-balance rows")
        void theFlushIsReachedOnlyFromTheControlBreak() {
            Account inProgress = stubAccount(ACCOUNT_A, "100.00", CARD_A);
            stubAnyRate("12.00");

            processor.process(categoryBalance(ACCOUNT_A, TYPE_CD, CAT_CD, "100.00"));
            processor.process(categoryBalance(ACCOUNT_A, "02", 6, "100.00"));

            // app/cbl/CBACT04C.cbl:L194-L199. Both rows carry the same TRANCAT-ACCT-ID, so the break test at
            // :L194 is false on the second row and 1050-UPDATE-ACCOUNT - performed at :L196 and at :L220 - has
            // not been reached from :L196. Writing here would post an account's interest before its last
            // category-balance row had been read, which is the defect the break ordering exists to prevent.
            assertThat(inProgress.getCurrentBalance())
                    .as("ADD WS-TOTAL-INT TO ACCT-CURR-BAL at :L352 belongs to the flush, and the flush has "
                            + "not been reached, so the balance the account was read with is untouched")
                    .isEqualByComparingTo(new BigDecimal("100.00"));
            Mockito.verify(accountRepository, Mockito.never()).save(Mockito.any());
        }

        @Test
        @DisplayName("when the break does arrive the completed account IS written, with its accumulated "
                + "interest posted and both cycle accumulators reset to zero, per :L352-L354")
        void theCompletedAccountIsWrittenWithInterestPostedAndBothCyclesReset() {
            Account completed = stubAccount(ACCOUNT_A, "100.00", CARD_A);
            stubAccount(ACCOUNT_B, "200.00", CARD_B);
            stubAnyRate("12.00");
            assertThat(completed.getCurrentCycleCredit())
                    .as("the fixture must start non-zero, or a missing reset would be invisible")
                    .isEqualByComparingTo(new BigDecimal("500.00"));
            assertThat(completed.getCurrentCycleDebit())
                    .as("and the two accumulators must differ, so a transposed reset cannot pass")
                    .isEqualByComparingTo(new BigDecimal("-100.00"));

            // Two rows for account A, then one for account B. The third row is the control break, and the
            // break at :L196 flushes the account that has just completed - A - with both of its rows summed.
            processor.process(categoryBalance(ACCOUNT_A, TYPE_CD, CAT_CD, "100.00"));
            processor.process(categoryBalance(ACCOUNT_A, "02", 6, "100.00"));
            processor.process(categoryBalance(ACCOUNT_B, "100.00"));

            Mockito.verify(accountRepository).save(completed);
            assertThat(completed.getCurrentBalance())
                    .as(":L352 ADD WS-TOTAL-INT TO ACCT-CURR-BAL. Two rows of 100.00 at 12.00 per cent give "
                            + "100.00 * 12.00 / 1200 twice, so 1.00 + 1.00 on top of the opening 100.00")
                    .isEqualByComparingTo(new BigDecimal("102.00"));
            assertThat(completed.getCurrentCycleCredit())
                    .as(":L353 MOVE 0 TO ACCT-CURR-CYC-CREDIT. Omitting this reset corrupts the next "
                            + "posting cycle's over-limit arithmetic at app/cbl/CBTRN02C.cbl:L410-L413")
                    .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(completed.getCurrentCycleDebit())
                    .as(":L354 MOVE 0 TO ACCT-CURR-CYC-DEBIT, the other half of the same reset")
                    .isEqualByComparingTo(BigDecimal.ZERO);
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

        /**
         * {@code WS-TRANID-SUFFIX} is run-scoped state, and in Java that means an <em>instance</em> field.
         *
         * <p>The counter is declared at {@code app/cbl/CBACT04C.cbl:L173} as
         * {@code 05 WS-TRANID-SUFFIX PIC 9(06) VALUE 0} and incremented at {@code :L474}. It is never reset
         * per account, which the previous test asserts behaviourally - but "never reset" is exactly the
         * property that tempts an implementation towards {@code static}, and {@code static} would be wrong
         * for a different reason than parity: Rule 1 clause B forbids global mutable state, and a
         * {@code static} counter on a {@code @StepScope} bean would be shared across every step execution in
         * the JVM, so two concurrent job instances would interleave their identifiers and two sequential runs
         * would not restart at one. The behavioural test cannot see that; only the declaration can.
         *
         * <p>Asserted for the whole class rather than for one field, because the same reasoning governs every
         * piece of per-run state the processor carries - the record count, the accumulator, the control-break
         * key, the first-time flag and the two loaded records. Every mutable field must be an instance field;
         * only immutable constants may be {@code static}.
         */
        @Test
        @DisplayName("WS-TRANID-SUFFIX and every other mutable field are instance state, never static")
        void theSuffixIsInstanceStateAndNeverStatic() throws NoSuchFieldException {
            java.lang.reflect.Field suffix =
                    InterestCalculationProcessor.class.getDeclaredField("tranIdSuffix");

            assertThat(java.lang.reflect.Modifier.isStatic(suffix.getModifiers()))
                    .as("WS-TRANID-SUFFIX of CBACT04C.cbl:L173 is run state on a @StepScope bean; a static "
                            + "counter would be shared across every step execution in the JVM")
                    .isFalse();

            Set<String> staticMutableFields = new LinkedHashSet<>();
            for (java.lang.reflect.Field declared
                    : InterestCalculationProcessor.class.getDeclaredFields()) {
                if (declared.isSynthetic()) {
                    continue;
                }
                int modifiers = declared.getModifiers();
                if (java.lang.reflect.Modifier.isStatic(modifiers)
                        && !java.lang.reflect.Modifier.isFinal(modifiers)) {
                    staticMutableFields.add(declared.getName());
                }
            }

            assertThat(staticMutableFields)
                    .as("Rule 1 clause B forbids global mutable state; every mutable field of the processor "
                            + "must be instance state and only immutable constants may be static")
                    .isEmpty();
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
        @DisplayName("the end-of-data ELSE arm of :L219-L220 is retained and performs the whole of "
                + "1050-UPDATE-ACCOUNT: the account saved, its interest posted and both cycles reset")
        void theRetainedEndOfDataArmPerformsTheWholeFlush() {
            Account lastAccountInKeyOrder = stubAccount(ACCOUNT_A, "100.00", CARD_A);
            stubDirectRate("12.00");
            processor.process(categoryBalance(ACCOUNT_A, "100.00"));
            assertThat(lastAccountInKeyOrder.getCurrentBalance()).isEqualByComparingTo(
                    new BigDecimal("100.00"));

            invokeEndOfFileArm();

            Mockito.verify(accountRepository).save(lastAccountInKeyOrder);
            assertThat(lastAccountInKeyOrder.getCurrentBalance())
                    .as(":L352 ADD WS-TOTAL-INT TO ACCT-CURR-BAL, one row of 100.00 at 12.00 per cent")
                    .isEqualByComparingTo(new BigDecimal("101.00"));
            assertThat(lastAccountInKeyOrder.getCurrentCycleCredit())
                    .as(":L353 MOVE 0 TO ACCT-CURR-CYC-CREDIT")
                    .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(lastAccountInKeyOrder.getCurrentCycleDebit())
                    .as(":L354 MOVE 0 TO ACCT-CURR-CYC-DEBIT")
                    .isEqualByComparingTo(BigDecimal.ZERO);
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
            invokeEndOfFileArm();
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

    @Nested
    @DisplayName("13. HIGH: the shipped fixtures exercise almost none of this, so synthetic data is required")
    class ShippedFixtureCoverage {

        /**
         * Establishes, by column extraction rather than by assumption, that no shipped account can reach the
         * direct-hit arm of the rate lookup.
         *
         * <p>{@code ACCT-GROUP-ID PIC X(10)} occupies columns {@value #ACCT_GROUP_ID_COLUMN} to 122 of the
         * 300-byte account record ({@code app/cpy/CVACT01Y.cpy}). Every one of the fifty rows in
         * {@code app/data/ASCII/acctdata.txt} holds ten spaces there - one distinct value in the whole
         * fixture. Because {@code app/cbl/CBACT04C.cbl:L210} moves that field straight into the probe key,
         * the stage-one read at {@code :L416} can only ever miss over shipped data, so the {@code DEFAULT}
         * fallback of {@code :L436}-{@code :L438} is always taken and the direct arm is never entered.
         *
         * <p>synthesise an account
         * with a non-blank group id. Editing the fixture is not an option - {@code app/**} is frozen and is
         * the parity oracle.
         */
        @Test
        @DisplayName("acctdata.txt carries a blank ACCT-GROUP-ID on all 50 rows, so stage one always misses")
        void theShippedAccountGroupIdIsBlankOnEveryRow() {
            FixtureLoader.FixtureData accounts = FixtureLoader.load(FixtureLoader.Fixture.ACCOUNT);

            Set<String> distinctGroupIds = new LinkedHashSet<>();
            for (int row = 0; row < accounts.recordCount(); row++) {
                distinctGroupIds.add(
                        accounts.field(row, ACCT_GROUP_ID_COLUMN, ACCT_GROUP_ID_WIDTH));
            }

            assertThat(accounts.recordCount())
                    .as("app/data/ASCII/acctdata.txt is 15,050 bytes of 300-byte records")
                    .isEqualTo(50);
            assertThat(distinctGroupIds)
                    .as("ACCT-GROUP-ID at columns %d-%d is one blank value on every row, so the stage-one "
                                    + "disclosure-group read of CBACT04C.cbl:L416 can only miss",
                            ACCT_GROUP_ID_COLUMN, ACCT_GROUP_ID_COLUMN + ACCT_GROUP_ID_WIDTH - 1)
                    .containsExactly(BLANK_GROUP_ID);
        }

        /**
         * Establishes that the shipped balance fixture carries a single type-and-category pair, whose
         * {@code DEFAULT} rate is non-zero, so the zero-rate arm is unreachable from shipped data.
         *
         * <p>{@code TRANCAT-TYPE-CD PIC X(02)} and {@code TRANCAT-CD PIC 9(04)}
         * ({@code app/cpy/CVTRA01Y.cpy:L7}-{@code :L8}) occupy columns
         * {@value #TRANCAT_TYPE_AND_CATEGORY_COLUMN} to 17, and all fifty rows of
         * {@code app/data/ASCII/tcatbal.txt} read {@value #SHIPPED_TYPE_AND_CATEGORY}. That matters because
         * without a synthetic key drawn from {@link #ZERO_RATE_DEFAULT_PAIRS}, the suppression branch of
         * {@code app/cbl/CBACT04C.cbl:L214}-{@code :L217} would never be executed by any test.
         */
        @Test
        @DisplayName("tcatbal.txt carries one type-and-category pair on all 50 rows")
        void theShippedCategoryBalanceKeyIsOnePairOnEveryRow() {
            FixtureLoader.FixtureData balances =
                    FixtureLoader.load(FixtureLoader.Fixture.TRANSACTION_CATEGORY_BALANCE);

            Set<String> distinctPairs = new LinkedHashSet<>();
            for (int row = 0; row < balances.recordCount(); row++) {
                distinctPairs.add(balances.field(row, TRANCAT_TYPE_AND_CATEGORY_COLUMN,
                        TRANCAT_TYPE_AND_CATEGORY_WIDTH));
            }

            assertThat(balances.recordCount()).isEqualTo(50);
            assertThat(distinctPairs)
                    .as("every shipped balance row is type-and-category %s, and that pair's DEFAULT rate is "
                            + "non-zero, so no shipped row reaches the zero-rate skip of CBACT04C.cbl:L214",
                            SHIPPED_TYPE_AND_CATEGORY)
                    .containsExactly(SHIPPED_TYPE_AND_CATEGORY);
            assertThat(distinctPairs)
                    .as("the one shipped pair is not among the seven zero-rate DEFAULT pairs")
                    .doesNotContainAnyElementsOf(ZERO_RATE_DEFAULT_PAIRS);
        }

        /**
         * Establishes that every shipped {@code TRAN-CAT-BAL} is {@code +0.00}, so shipped data produces no
         * arithmetic worth measuring.
         *
         * <p>The field is {@code PIC S9(09)V99} at columns {@value #TRAN_CAT_BAL_COLUMN} to 28, eleven
         * characters whose last is a zoned-decimal overpunch. All fifty rows end that field with
         * <code>&#123;</code>, the overpunch for {@code +0}, over nine zero digits. Decoding is done through
         * {@link FixtureLoader.FixtureData#signedDecimal(int, int, int)} with the width taken from the
         * picture clause, because the overpunch is positional: the same letters occur legitimately inside
         * text fields, and {@code ACCT-ADDR-ZIP} holds {@code A000000000} on every account row, so a scan
         * that is not position-aware mis-decodes them.
         *
         * <p>The {@code /1200} formula, the {@code HALF_EVEN} rounding and the
         * {@code NUMERIC(11,2)} scale are all invisible at a zero balance. Synthetic non-zero balances are
         * mandatory, and group 5 supplies them.
         */
        @Test
        @DisplayName("every shipped TRAN-CAT-BAL decodes to +0.00, so real arithmetic needs synthetic values")
        void theShippedCategoryBalancesAreAllPlusZero() {
            FixtureLoader.FixtureData balances =
                    FixtureLoader.load(FixtureLoader.Fixture.TRANSACTION_CATEGORY_BALANCE);

            for (int row = 0; row < balances.recordCount(); row++) {
                assertThat(balances.signedDecimal(row, TRAN_CAT_BAL_COLUMN,
                                FixtureLoader.AMOUNT_FIELD_WIDTH))
                        .as("TRAN-CAT-BAL of tcatbal.txt row %d, decoded position-aware from PIC S9(09)V99",
                                row)
                        .isEqualByComparingTo(BigDecimal.ZERO);
            }
        }

        /**
         * Establishes the {@code DEFAULT} row inventory the fallback depends on: seventeen rows, of which
         * exactly seven carry a zero rate.
         *
         * <p>{@code DIS-ACCT-GROUP-ID PIC X(10)} is columns {@value #DIS_GROUP_ID_COLUMN} to 10 and
         * {@code DIS-INT-RATE PIC S9(04)V99} is columns {@value #DIS_INT_RATE_COLUMN} to 22
         * ({@code app/cpy/CVTRA02Y.cpy:L6}, {@code :L9}). Note the group id in the file is already the
         * padded ten-character image {@value #DEFAULT_GROUP_ID_PADDED}, which independently corroborates the
         * probe form group 14 asserts. The pair {@value #SHIPPED_TYPE_AND_CATEGORY} - the only pair the
         * balance fixture uses - carries {@code +15.00}, which is why the shipped path always computes
         * interest and never suppresses.
         */
        @Test
        @DisplayName("discgrp.txt has 17 DEFAULT rows, exactly 7 of them zero-rate")
        void theShippedDefaultRowsCarrySevenZeroRates() {
            FixtureLoader.FixtureData groups =
                    FixtureLoader.load(FixtureLoader.Fixture.DISCLOSURE_GROUP);

            Set<String> zeroRatePairs = new LinkedHashSet<>();
            int defaultRows = 0;
            BigDecimal shippedPairRate = null;
            for (int row = 0; row < groups.recordCount(); row++) {
                if (!DEFAULT_GROUP_ID_PADDED
                        .equals(groups.field(row, DIS_GROUP_ID_COLUMN, ACCT_GROUP_ID_WIDTH))) {
                    continue;
                }
                defaultRows++;
                String pair = groups.field(row, DIS_TYPE_AND_CATEGORY_COLUMN,
                        TRANCAT_TYPE_AND_CATEGORY_WIDTH);
                BigDecimal rate = groups.signedDecimal(row, DIS_INT_RATE_COLUMN,
                        FixtureLoader.RATE_FIELD_WIDTH);
                if (rate.compareTo(BigDecimal.ZERO) == 0) {
                    zeroRatePairs.add(pair);
                }
                if (SHIPPED_TYPE_AND_CATEGORY.equals(pair)) {
                    shippedPairRate = rate;
                }
            }

            assertThat(defaultRows)
                    .as("app/data/ASCII/discgrp.txt carries 17 rows keyed on the padded DEFAULT group, "
                            + "out of 51 rows in total")
                    .isEqualTo(17);
            assertThat(zeroRatePairs)
                    .as("only these DEFAULT pairs can reach the zero-rate skip of CBACT04C.cbl:L214")
                    .containsExactlyInAnyOrderElementsOf(ZERO_RATE_DEFAULT_PAIRS);
            assertThat(shippedPairRate)
                    .as("the pair every shipped balance row uses carries a non-zero DEFAULT rate")
                    .isNotNull()
                    .isEqualByComparingTo(new BigDecimal("15.00"));
        }

        /**
         * Drives the processor with a synthetic account to reach the direct-hit arm the fixtures cannot.
         *
         * <p>Proves the remediation, not merely the finding: with a non-blank {@code ACCT-GROUP-ID} the
         * stage-one read resolves and {@code 1200-A-GET-DEFAULT-INT-RATE} at
         * {@code app/cbl/CBACT04C.cbl:L443} is never entered.
         */
        @Test
        @DisplayName("a synthetic non-blank group id reaches the direct arm and skips the DEFAULT retry")
        void aSyntheticAccountReachesTheDirectHit() {
            stubAccountWithGroup(ACCOUNT_A, "100.00", CARD_A, GROUP_ID_DIRECT);
            stubKeyedRate(GROUP_ID_DIRECT, TYPE_CD, CAT_CD, "12.00");

            Transaction interest = processor.process(categoryBalance(ACCOUNT_A, "100.00"));

            assertThat(interest).isNotNull();
            assertThat(interest.getAmount())
                    .as("100.00 * 12.00 / 1200 as CBACT04C.cbl:L465 writes it")
                    .isEqualByComparingTo(new BigDecimal("1.00"));
            Mockito.verify(disclosureGroupRepository, Mockito.never())
                    .findDefaultGroupRate(Mockito.anyString(), Mockito.anyInt());
        }

        /**
         * Drives the processor with a synthetic zero-rate pair drawn from the measured seven, reaching the
         * suppression arm the fixtures cannot.
         *
         * <p>{@code app/cbl/CBACT04C.cbl:L214} guards <em>both</em> {@code 1300-COMPUTE-INTEREST} and
         * {@code 1400-COMPUTE-FEES}, so a zero rate emits nothing and accumulates nothing. As a Spring Batch
         * {@code ItemProcessor} filter that is a {@code null} return.
         */
        @Test
        @DisplayName("a synthetic zero-rate DEFAULT pair suppresses the item, per :L214-L217")
        void aSyntheticZeroRatePairReachesTheSkip() {
            stubAccountWithGroup(ACCOUNT_A, "100.00", CARD_A, GROUP_ID_DIRECT);
            stubDefaultRate(ZERO_RATE_TYPE_CD, ZERO_RATE_CAT_CD, "0.00");

            assertThat(ZERO_RATE_DEFAULT_PAIRS)
                    .as("the key under test is one of the seven measured zero-rate DEFAULT pairs")
                    .contains(ZERO_RATE_TYPE_CD + "000" + ZERO_RATE_CAT_CD);

            Transaction suppressed = processor.process(
                    categoryBalance(ACCOUNT_A, ZERO_RATE_TYPE_CD, ZERO_RATE_CAT_CD, "100.00"));

            assertThat(suppressed)
                    .as("a zero rate skips 1300 and 1400 alike, so the item is filtered out")
                    .isNull();
        }

        /**
         * Records what the shipped data actually proves when it is used unaltered: a zero balance yields a
         * zero interest transaction, yet the account rewrite still zeroes both cycle counters.
         *
         * <p>This is the one assertion in this group that the fixtures <em>can</em> support, and it is worth
         * making because it is the trap: a suite driven only by shipped data would pass while measuring
         * nothing about the formula, and would still look like it covered {@code 1050-UPDATE-ACCOUNT}. The
         * transaction is emitted because the rate is non-zero - {@code app/cbl/CBACT04C.cbl:L214} tests the
         * rate, never the balance.
         */
        @Test
        @DisplayName("the shipped +0.00 balance still emits a transaction and still resets both counters")
        void theShippedZeroBalanceStillResetsTheCycleCounters() {
            Account first = stubAccountWithGroup(ACCOUNT_A, "100.00", CARD_A, GROUP_ID_DIRECT);
            stubAccountWithGroup(ACCOUNT_B, "200.00", CARD_B, GROUP_ID_DIRECT);
            stubKeyedRate(GROUP_ID_DIRECT, TYPE_CD, CAT_CD, "15.00");

            Transaction zeroInterest = processor.process(categoryBalance(ACCOUNT_A, "0.00"));
            processor.process(categoryBalance(ACCOUNT_B, "0.00"));

            assertThat(zeroInterest).isNotNull();
            assertThat(zeroInterest.getAmount())
                    .as("a zero balance at a non-zero rate is a zero-amount transaction, not a skip")
                    .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(first.getCurrentBalance())
                    .as("nothing accrued, so ACCT-CURR-BAL is untouched by :L352")
                    .isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(first.getCurrentCycleCredit())
                    .as(":L353 zeroes ACCT-CURR-CYC-CREDIT regardless of the interest accrued")
                    .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(first.getCurrentCycleDebit())
                    .as(":L354 zeroes ACCT-CURR-CYC-DEBIT regardless of the interest accrued")
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @Nested
    @DisplayName("14. Preserved legacy defects, the padded probe, and the boundaries this step does not own")
    class PreservedDefectsAndBoundaries {

        /**
         * Conflict instance three: the open paragraph names a dataset this program does not have.
         *
         * <p>{@code 0200-DISCGRP-OPEN} begins at {@code app/cbl/CBACT04C.cbl:L270} and opens
         * {@code DISCGRP-FILE} at {@code :L272}, but its failure arm displays
         * {@code 'ERROR OPENING DALY REJECTS FILE'} at {@code :L281}. {@code CBACT04C} has no rejects file at
         * all - its five {@code SELECT} statements at {@code :L27}-{@code :L56} are the category balance,
         * cross reference, account, disclosure group and transaction files - so the literal is a
         * copy-and-paste defect carried over from the posting program. The neighbouring paragraphs get it
         * right, which is what proves it is a defect rather than a naming convention: {@code :L300} reads
         * {@code 'ERROR OPENING ACCOUNT MASTER FILE'} and {@code :L318} reads
         * {@code 'ERROR OPENING TRANSACTION FILE'}.
         *
         * <p><b>It is preserved verbatim and deliberately not repaired.</b> The literal is part of the
         * observable output the parity comparison is measured against, so correcting it to
         * {@code 'ERROR OPENING DISCLOSURE GROUP FILE'} would register as a diff. Severity <b>Low</b> - an
         * operator reading the line is pointed at the wrong dataset, and nothing else - owed an
         * entry in the planned {@code DECISION_LOG.md} as its tracking reference, which is what keeps
         * it a documented
         * reproduction rather than untracked residue under Rule 1 clause B.
         *
         * <p>Both halves of "preserved verbatim" are asserted, and each against the artefact that carries
         * it. The defect is established from the frozen corpus, which cannot change; the preservation is
         * established from the job's own source text rather than from a named private constant, so renaming
         * the constant does not break this test while deleting the literal still does.
         */
        @Test
        @DisplayName("the DISCGRP open literal of :L281 still names the DALY REJECTS file, verbatim")
        void theDiscgrpOpenLiteralNamesTheWrongFileVerbatim() {
            String wrongLiteral = "ERROR OPENING DALY REJECTS FILE";

            assertThat(repositoryLine(281, "app", "cbl", "CBACT04C.cbl"))
                    .as("the defect itself, read from the frozen parity oracle at CBACT04C.cbl:L281")
                    .contains("DISPLAY '" + wrongLiteral + "'");
            assertThat(repositoryLine(270, "app", "cbl", "CBACT04C.cbl"))
                    .as("and it sits inside 0200-DISCGRP-OPEN, which opens the disclosure group file")
                    .contains("0200-DISCGRP-OPEN");
            assertThat(repositoryLine(272, "app", "cbl", "CBACT04C.cbl"))
                    .contains("OPEN INPUT DISCGRP-FILE");
            assertThat(repositoryLine(300, "app", "cbl", "CBACT04C.cbl"))
                    .as("the neighbouring open gets it right, which is what makes :L281 a defect rather "
                            + "than a naming convention")
                    .contains("ERROR OPENING ACCOUNT MASTER FILE");

            assertThat(String.join("\n", repositoryLines("src", "main", "java", "com", "cardemo",
                    "batch", "jobs", "InterestCalculationJob.java")))
                    .as("the Java translation preserves the legacy text; do not correct it, the parity "
                            + "comparison is measured against this literal")
                    .contains("\"" + wrongLiteral + "\"")
                    .doesNotContain("\"ERROR OPENING DISCLOSURE GROUP FILE\"");
            assertThat(wrongLiteral)
                    .as("the corrected wording would be a diff against the legacy output")
                    .isNotEqualTo(FileStatusMapper.DISCGRP_READ_FAILURE_TEXT);
        }

        /**
         * The {@code DEFAULT} probe is the padded ten-character image, not the bare seven-character literal.
         *
         * <p>{@code app/cbl/CBACT04C.cbl:L437} executes {@code MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID}. A
         * COBOL alphanumeric move left-justifies and space-fills, and the receiving field is
         * {@code DIS-ACCT-GROUP-ID PIC X(10)} ({@code app/cpy/CVTRA02Y.cpy:L6}), so the seven characters
         * become {@value #DEFAULT_GROUP_ID_PADDED} - {@code DEFAULT} followed by three blanks - and it is
         * that image the retry at {@code :L444} reads with. {@code app/data/ASCII/discgrp.txt} stores the
         * same padded form, which group 13 measures independently.
         *
         * <p>a probe of the bare literal against a
         * {@code CHAR(10)} column matches or misses depending on the column type and the comparison
         * semantics, so a bare probe is the kind of fault that passes on one substrate and silently abends
         * every account on another.
         */
        @Test
        @DisplayName("the DEFAULT retry probes the padded PIC X(10) image, never the bare literal")
        void theDefaultGroupProbeIsPaddedToTenCharacters() throws NoSuchMethodException {
            Method retry = DisclosureGroupRepository.class
                    .getMethod("findDefaultGroupRate", String.class, Integer.class);
            Query query = retry.getAnnotation(Query.class);

            assertThat(query)
                    .as("the DEFAULT retry of CBACT04C.cbl:L444 is a declared query, so its probe is "
                            + "inspectable without a database")
                    .isNotNull();
            assertThat(DEFAULT_GROUP_ID_PADDED)
                    .as("MOVE 'DEFAULT' into PIC X(10) leaves seven characters and three blanks")
                    .hasSize(ACCT_GROUP_ID_WIDTH)
                    .isEqualTo("DEFAULT" + "   ");
            assertThat(query.value())
                    .as("the probe carries the padded image of app/cpy/CVTRA02Y.cpy:L6")
                    .contains("'" + DEFAULT_GROUP_ID_PADDED + "'");
            assertThat(query.value())
                    .as("a bare seven-character probe would depend on column type and comparison semantics")
                    .doesNotContain("'DEFAULT'");
        }

        /**
         * HIGH: a stage-one miss must resolve a fresh rate, never inherit the previous record's.
         *
         * <p>In the source, {@code READ DISCGRP-FILE INTO DIS-GROUP-RECORD} at
         * {@code app/cbl/CBACT04C.cbl:L416} carries an {@code INVALID KEY} arm that only displays. On an
         * invalid key the record area is <em>not</em> cleared, so {@code DIS-INT-RATE} still holds whatever
         * the previous iteration left there until the retry at {@code :L444} overwrites it. That is why the
         * Java translation must resolve the rate per record through an {@link Optional} and hold no rate
         * field across iterations.
         *
         * <p>This test is the one that would catch a cached rate: the first record resolves directly at
         * {@code 12.00} and the second, keyed differently, misses stage one and falls back to a
         * <em>different</em> default rate of {@code 24.00}. A carried-over rate would produce {@code 1.00}
         * twice; a fresh resolution produces {@code 1.00} then {@code 2.00}.
         */
        @Test
        @DisplayName("a stage-one miss resolves a fresh DEFAULT rate rather than reusing the last one")
        void aMissingRateRowDoesNotInheritThePreviousRecordsRate() {
            stubAccountWithGroup(ACCOUNT_A, "100.00", CARD_A, GROUP_ID_DIRECT);
            stubKeyedRate(GROUP_ID_DIRECT, TYPE_CD, CAT_CD, "12.00");
            stubDefaultRate("02", 6, "24.00");

            Transaction direct = processor.process(categoryBalance(ACCOUNT_A, TYPE_CD, CAT_CD, "100.00"));
            Transaction viaDefault = processor.process(categoryBalance(ACCOUNT_A, "02", 6, "100.00"));

            assertThat(direct).isNotNull();
            assertThat(direct.getAmount())
                    .as("the direct hit applies 12.00")
                    .isEqualByComparingTo(new BigDecimal("1.00"));
            assertThat(viaDefault).isNotNull();
            assertThat(viaDefault.getAmount())
                    .as("the miss applies the DEFAULT 24.00, not the 12.00 the record area would have held "
                            + "in the source - see CBACT04C.cbl:L416 and :L444")
                    .isEqualByComparingTo(new BigDecimal("2.00"));
        }

        /**
         * The reverse direction of the same hazard: a direct hit after a fallback must not inherit the
         * default rate either.
         *
         * <p>Stated separately because the two orderings fail differently. A cache written only on the
         * fallback path would pass the previous test and fail this one.
         */
        @Test
        @DisplayName("a direct hit after a DEFAULT fallback resolves its own rate, not the default's")
        void aDirectHitAfterAFallbackDoesNotInheritTheDefaultRate() {
            stubAccountWithGroup(ACCOUNT_A, "100.00", CARD_A, GROUP_ID_DIRECT);
            stubDefaultRate("02", 6, "24.00");
            stubKeyedRate(GROUP_ID_DIRECT, TYPE_CD, CAT_CD, "12.00");

            Transaction viaDefault = processor.process(categoryBalance(ACCOUNT_A, "02", 6, "100.00"));
            Transaction direct = processor.process(categoryBalance(ACCOUNT_A, TYPE_CD, CAT_CD, "100.00"));

            assertThat(viaDefault).isNotNull();
            assertThat(viaDefault.getAmount()).isEqualByComparingTo(new BigDecimal("2.00"));
            assertThat(direct).isNotNull();
            assertThat(direct.getAmount())
                    .as("the second record's own key resolves 12.00; no rate survives the iteration")
                    .isEqualByComparingTo(new BigDecimal("1.00"));
        }

        /**
         * The cross reference is read through the account alternate key, which is the only xref access path
         * the program declares.
         *
         * <p>{@code app/cbl/CBACT04C.cbl:L394}-{@code :L395} reads {@code XREF-FILE} with
         * {@code KEY IS FD-XREF-ACCT-ID}, the {@code ALTERNATE RECORD KEY} declared at {@code :L38}, so the
         * Java counterpart is an account-keyed finder rather than a card-keyed one.
         *
         * <p><b>A detail worth stating.</b> {@code app/jcl/INTCALC.jcl} allocates <em>two</em>
         * cross-reference DD names, {@code XREFFILE} and {@code XREFFIL1}, and the second reads as though the
         * program went through the alternate-index PATH. It does not: {@code CBACT04C} contains exactly one
         * xref {@code SELECT} ({@code :L34}-{@code :L39}) and it assigns {@code XREFFILE}, so
         * {@code XREFFIL1} is never referenced by the program at all. The read therefore goes through the
         * base cluster's own alternate key, and the Java lookup is an account-keyed finder on the
         * cross-reference repository - which is what this asserts.
         */
        @Test
        @DisplayName("the cross reference is read by account through the alternate-key finder")
        void theCrossReferenceLookupIsTheAlternateKeyFinder() throws NoSuchMethodException {
            assertThat(CardCrossReferenceRepository.class
                    .getMethod("findFirstByAccountIdOrderByCardNumberAsc", Long.class).getReturnType())
                    .as("the account-keyed finder replacing the ALTERNATE RECORD KEY of CBACT04C.cbl:L38")
                    .isEqualTo(Optional.class);

            stubAccount(ACCOUNT_A, "100.00", CARD_A);
            stubDirectRate("12.00");

            Transaction interest = processor.process(categoryBalance(ACCOUNT_A, "100.00"));

            assertThat(interest).isNotNull();
            assertThat(interest.getCardNumber())
                    .as(":L495 moves XREF-CARD-NUM into TRAN-CARD-NUM")
                    .isEqualTo(CARD_A);
            Mockito.verify(cardCrossReferenceRepository)
                    .findFirstByAccountIdOrderByCardNumberAsc(ACCOUNT_A);
            Mockito.verify(cardCrossReferenceRepository, Mockito.never()).findById(Mockito.anyString());
        }

        /**
         * This step writes a fresh sequential generation, so the processor persists nothing and owns no
         * duplicate-key decision.
         *
         * <p>{@code TRANSACT-FILE} is declared {@code ORGANIZATION IS SEQUENTIAL} with
         * {@code ACCESS MODE IS SEQUENTIAL} and <em>no</em> {@code RECORD KEY} at
         * {@code app/cbl/CBACT04C.cbl:L53}-{@code :L56}, and {@code 0400-TRANFILE-OPEN} opens it
         * {@code OPEN OUTPUT} at {@code :L309}. {@code app/jcl/INTCALC.jcl} allocates that DD as a brand-new
         * generation of a sequential generation group with {@code LRECL=350}. A sequential output file has no
         * key and therefore no duplicate-key condition: interest transactions reach the keyed cluster only
         * later, through the combine job's sort and bulk load, and it is there that a repeated
         * {@code PARM-DATE} would collide.
         *
         * <p>Two consequences are asserted structurally rather than described. The processor returns the
         * synthesised transaction for the step's writer to emit and never persists it itself, and its
         * constructor takes no transaction repository at all - so there is no seam through which a
         * duplicate-key check could be smuggled into this tier. Duplicate handling belongs to
         * {@code TransactionCombineProcessorTest}.
         */
        @Test
        @DisplayName("the processor returns the transaction and owns no transaction store at all")
        void theProcessorReturnsTheTransactionAndPersistsNothing() {
            assertThat(InterestCalculationProcessor.class.getDeclaredConstructors())
                    .as("one constructor, so the container needs no @Autowired to choose")
                    .hasSize(1);
            assertThat(InterestCalculationProcessor.class.getDeclaredConstructors()[0].getParameterTypes())
                    .as("no transaction repository is injectable: this step writes a fresh sequential "
                            + "generation per app/jcl/INTCALC.jcl, not the keyed cluster")
                    .containsExactly(DisclosureGroupRepository.class, AccountRepository.class,
                            CardCrossReferenceRepository.class, FileStatusMapper.class, String.class,
                            Clock.class);

            stubAccount(ACCOUNT_A, "100.00", CARD_A);
            stubDirectRate("12.00");

            Transaction emitted = processor.process(categoryBalance(ACCOUNT_A, "100.00"));

            assertThat(emitted)
                    .as("the item is handed to the step's writer; :L500 WRITE is the writer's job")
                    .isNotNull();
            Mockito.verify(accountRepository, Mockito.never()).save(Mockito.any());
            Mockito.verify(accountRepository, Mockito.never()).flush();
        }

        /**
         * {@code PARM-DATE} is carried opaquely: ten characters in, the same ten characters out.
         *
         * <p>{@code app/cbl/CBACT04C.cbl:L175}-{@code :L178} declares it {@code PIC X(10)} inside
         * {@code EXTERNAL-PARMS}, received by {@code PROCEDURE DIVISION USING EXTERNAL-PARMS} at
         * {@code :L180}, and {@code app/jcl/INTCALC.jcl} supplies {@code PARM='2022071800'} - eight date
         * digits followed by two zeros. The program never parses it, never reformats it and never validates
         * it as a date; {@code :L476}-{@code :L480} concatenates it verbatim ahead of the six-digit suffix to
         * fill {@code TRAN-ID PIC X(16)}.
         *
         * <p>A ten-character value that is not a date at all is therefore accepted, and this asserts exactly
         * that: a date parser or a format validator in the Java path would reject it and would be a
         * behaviour change. Because the date leads, generated identifiers are numerically large and dominate
         * the descending-key browse that {@code COTRN02C} and {@code COBIL00C} use to generate identifiers -
         * a consequence recorded here because nothing in this tier can observe it.
         */
        @Test
        @DisplayName("a ten-character non-date PARM-DATE is accepted and reappears verbatim in TRAN-ID")
        void theParmDateIsCarriedOpaquely() {
            String notADate = "9999999999";
            InterestCalculationProcessor opaqueParm = new InterestCalculationProcessor(
                    disclosureGroupRepository, accountRepository, cardCrossReferenceRepository,
                    fileStatusMapper, notADate, FIXED_CLOCK);
            stubAccount(ACCOUNT_A, "100.00", CARD_A);
            stubDirectRate("12.00");

            Transaction interest = opaqueParm.process(categoryBalance(ACCOUNT_A, "100.00"));

            assertThat(notADate).hasSize(10);
            assertThat(interest).isNotNull();
            assertThat(interest.getTransactionId())
                    .as("PARM-DATE is concatenated verbatim by :L476-L480; it is never parsed as a date")
                    .isEqualTo(notADate + "000001")
                    .hasSize(16);
        }

        /**
         * End of file on the driving read is loop termination, never an exception.
         *
         * <p>{@code 1000-TCATBALF-GET-NEXT} at {@code app/cbl/CBACT04C.cbl:L325}-{@code :L348} maps
         * {@code '00'} to {@code APPL-RESULT} zero at {@code :L327}, {@code '10'} to sixteen at
         * {@code :L330} - the value {@code 88 APPL-EOF} names at {@code :L135} - and anything else to twelve
         * at {@code :L333}. Only the third arm reaches the abend; the {@code '10'} arm sets
         * {@code END-OF-FILE} at {@code :L340} and the {@code PERFORM UNTIL} at {@code :L188} simply stops.
         * In the Java step that role belongs to the reader returning {@code null}, so what is assertable in
         * this tier is the mapping itself, which the processor's own collaborator owns.
         *
         * <p>Note the asymmetry that makes this worth stating: {@code '23'} is a tolerated status on the
         * disclosure-group read at {@code :L422} and is <em>not</em> tolerated here - a record-not-found on
         * the sequential driving read is a failure, not an accepted control path.
         */
        @Test
        @DisplayName("TCATBALF '10' maps to APPL-EOF and never throws, while '23' there is a failure")
        void endOfFileOnTheDrivingReadIsLoopTerminationNotAnException() {
            assertThat(fileStatusMapper.applResultForSequentialRead("00"))
                    .as(":L327 moves 0 into APPL-RESULT on '00'")
                    .isEqualTo(FileStatusMapper.APPL_AOK);
            assertThat(fileStatusMapper.applResultForSequentialRead("10"))
                    .as(":L330-L331 move 16 into APPL-RESULT on '10', the value 88 APPL-EOF names at :L135")
                    .isEqualTo(FileStatusMapper.APPL_EOF);
            assertThat(fileStatusMapper.applResultForSequentialRead("23"))
                    .as("record-not-found is NOT lenient on the driving read; :L333 moves 12")
                    .isEqualTo(FileStatusMapper.APPL_FAILURE);
            assertThat(fileStatusMapper.applResultForSequentialRead("35"))
                    .isEqualTo(FileStatusMapper.APPL_FAILURE);
        }

        /**
         * The tolerated status on the disclosure-group read is the scoped exception, and it is scoped to
         * stage one alone.
         *
         * <p>{@code app/cbl/CBACT04C.cbl:L422} reads {@code IF DISCGRP-STATUS = '00' OR '23'}, so a
         * record-not-found is an accepted control path that requests the retry rather than an error. The
         * retry's own guard at {@code :L446} admits {@code '00'} only - {@code :L444} carries no
         * {@code INVALID KEY} clause whatsoever - so the same status that is tolerated one paragraph earlier
         * is fatal there. A blanket rule mapping {@code '23'} to a not-found exception would abend every
         * account in the shipped fixture, since group 13 shows the fallback is always taken.
         */
        @Test
        @DisplayName("'23' is tolerated at :L422 and fatal at :L446, and the abend carries 999 with RC 12")
        void theToleratedStatusIsScopedToStageOneOnly() {
            assertThat(fileStatusMapper.requireDisclosureGroupReadSuccess("00"))
                    .as(":L422 admits '00' and asks for no retry")
                    .isFalse();
            assertThat(fileStatusMapper.requireDisclosureGroupReadSuccess("23"))
                    .as(":L422 admits '23' as an accepted control path and asks for the DEFAULT retry")
                    .isTrue();

            assertThatExceptionOfType(FatalProcessingException.class)
                    .as(":L446 admits '00' only, so a missing DEFAULT row reaches :L458")
                    .isThrownBy(() -> fileStatusMapper.requireDefaultDisclosureGroupReadSuccess("23"))
                    .satisfies(abend -> assertThat(abend.getAbendReason())
                            .isEqualTo(FileStatusMapper.DEFAULT_DISCGRP_READ_FAILURE_TEXT));

            assertThat(FatalProcessingException.BATCH_ABEND_CODE)
                    .as("9999-ABEND-PROGRAM at :L628 moves 999 into ABCODE before CALL 'CEE3ABD'")
                    .isEqualTo(999);
            assertThat(FatalProcessingException.BATCH_RETURN_CODE)
                    .as("the abend surfaces as return code 12, the value :L333 moves into APPL-RESULT")
                    .isEqualTo(12);
        }

        /**
         * The generated timestamp is the fixed clock's, rendered in the batch format, and nothing in this
         * class reads a wall clock.
         *
         * <p>{@code Z-GET-DB2-FORMAT-TIMESTAMP} at {@code app/cbl/CBACT04C.cbl:L613} builds a
         * {@code PIC X(26)} value whose own in-source comment at {@code :L140} reads
         * {@code EEEE-MM-DD-UU.MM.SS.HH0000}: three dashes, so a <em>dash</em> separates the day from the
         * hour, then hundredths, then four literal zeros moved in from {@code MOVE '0000' TO DB2-REST}. Never
         * nanoseconds, and a {@code String} over {@code CHAR(26)} rather than any date-time type.
         *
         * <p>Asserted against {@link FixedClockProvider#batchTimestamp(Clock)} - the sibling tier's shared
         * renderer - and against the literal text and width independently, so agreement with the helper
         * cannot be the only thing holding the expectation up.
         */
        @Test
        @DisplayName("both timestamps come from the injected fixed clock in the 26-character batch format")
        void theTimestampComesFromTheInjectedFixedClock() {
            stubAccount(ACCOUNT_A, "100.00", CARD_A);
            stubDirectRate("12.00");

            Transaction interest = processor.process(categoryBalance(ACCOUNT_A, "100.00"));

            assertThat(interest).isNotNull();
            assertThat(interest.getOrigTs())
                    .as("the shared renderer and the production formatter must agree")
                    .isEqualTo(FixedClockProvider.batchTimestamp(FIXED_CLOCK))
                    .isEqualTo("2022-07-18-04.05.06.070000")
                    .hasSize(FixedClockProvider.TIMESTAMP_LENGTH);
            assertThat(interest.getProcTs())
                    .as(":L497 and :L498 move the SAME DB2-FORMAT-TS into both fields")
                    .isEqualTo(interest.getOrigTs());
            assertThat(interest.getOrigTs().charAt(10))
                    .as(":L157 declares DB2-STREEP-3, so a dash - not a space - separates day from hour")
                    .isEqualTo('-');
            assertThat(interest.getOrigTs())
                    .as("MOVE '0000' TO DB2-REST, so the value ends in four literal zeros")
                    .endsWith("0000");
        }

        /**
         * A different clock produces a different timestamp, which is what proves the value is injected
         * rather than ambient.
         *
         * <p>Without this, an implementation that ignored its clock and called a wall clock would still pass
         * every other timestamp assertion whenever the two happened to agree on the rendered text - which,
         * for a fixed clock pinned to the past, they never would, but the assertion should not depend on that
         * coincidence to have meaning.
         */
        @Test
        @DisplayName("a second fixed clock moves the generated timestamp, so the clock is truly injected")
        void adifferentClockMovesTheTimestamp() {
            Clock canonical = FixedClockProvider.canonicalClock();
            InterestCalculationProcessor otherClock = new InterestCalculationProcessor(
                    disclosureGroupRepository, accountRepository, cardCrossReferenceRepository,
                    fileStatusMapper, PARM_DATE, canonical);
            stubAccount(ACCOUNT_A, "100.00", CARD_A);
            stubDirectRate("12.00");

            Transaction interest = otherClock.process(categoryBalance(ACCOUNT_A, "100.00"));

            assertThat(interest).isNotNull();
            assertThat(interest.getOrigTs())
                    .as("the canonical instant of the sibling tier, rendered in the batch format")
                    .isEqualTo(FixedClockProvider.CANONICAL_BATCH_TIMESTAMP)
                    .isNotEqualTo(EXPECTED_TIMESTAMP);
        }
    }
}

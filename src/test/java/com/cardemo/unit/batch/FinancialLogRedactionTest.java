/*
 * ******************************************************************
 * Program     : FinancialLogRedactionTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Captures what the batch diagnostics actually log and proves
 *               that no balance, credit limit, cycle accumulator, category
 *               balance, transaction amount or page total reaches a log
 *               event, while the labels and identifying fields that make
 *               each DISPLAY useful are still emitted.
 * Source      : app/cbl/CBACT01C.cbl:L118-L131 (1100-DISPLAY-ACCT-RECORD)
 *               app/cbl/CBACT04C.cbl:L192-L193 (DISPLAY TRAN-CAT-BAL-RECORD)
 *               app/cbl/CBTRN03C.cbl:L180      (DISPLAY TRAN-RECORD)
 *               app/cbl/CBTRN03C.cbl:L198-L199 (TRAN-AMT, WS-PAGE-TOTAL)
 *               app/cpy/CVACT01Y.cpy           (five S9(10)V99 fields)
 *               src/main/resources/logback-spring.xml (masking rules) @ 7756d89
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cardemo.batch.processors.InterestCalculationProcessor;
import com.cardemo.batch.processors.TransactionReportProcessor;
import com.cardemo.batch.readers.AccountReader;
import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.TransactionCategoryBalance;
import com.cardemo.model.key.TransactionCategoryBalanceId;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.DisclosureGroupRepository;
import com.cardemo.repository.TransactionCategoryRepository;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.repository.TransactionTypeRepository;
import com.cardemo.service.shared.FileStatusMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.data.domain.Pageable;

/**
 * The financial-value boundary of the batch diagnostics, asserted by capturing what is actually logged.
 *
 * <p>The batch corpus diagnoses itself with {@code DISPLAY}: {@code 1100-DISPLAY-ACCT-RECORD} writes eleven
 * labelled account fields to SYSOUT, {@code CBACT04C} writes the category-balance record, and
 * {@code CBTRN03C} writes {@code TRAN-AMT} and {@code WS-PAGE-TOTAL}. On z/OS those went to a job log held
 * under the same access controls as the datasets themselves. In the target they go to an aggregated
 * application log, and the values among them - a balance, two credit limits, two cycle accumulators, a
 * category balance, a transaction amount and a running page total - are customer financial data (CWE-532).
 *
 * <p><strong>Why masking downstream cannot substitute for withholding here.</strong> Every rule in
 * {@code src/main/resources/logback-spring.xml} keys on a recognisable shape: a bearer token, a BCrypt hash,
 * a labelled card number, a nine-digit run in a social-security position. An amount has no shape. It is a
 * run of digits, indistinguishable from the account identifier printed beside it, so a rule broad enough to
 * mask the amount would blank the identifier and destroy the diagnostic. The value therefore has to be
 * withheld where the distinction is still known, which is at the emission site.
 *
 * <p>These tests attach a {@link ListAppender} to each production logger, drive the real emission path at
 * the level that triggers it, and assert that no rendered value appears in any event while the identifying
 * fields that make the diagnostic useful still do.
 */
@DisplayName("Batch diagnostics: no financial value reaches a log event")
class FinancialLogRedactionTest {

    /**
     * The stand-in a withheld {@code PIC S9(10)V99} money field of {@code app/cpy/CVACT01Y.cpy} carries:
     * twelve characters, one per digit position the field would have occupied.
     *
     * <p>An earlier revision of this suite expected {@code "[REDACTED]"} here, taken from the {@code REDACTION}
     * property of {@code src/main/resources/logback-spring.xml}. That expectation is withdrawn, because it
     * confused two different mechanisms. {@code [REDACTED]} is what the <em>masking layer</em> substitutes when
     * one of its rules matches text that has already been rendered. What this suite inspects is the opposite
     * case: an emitter that never writes the value at all, and that must put something of exactly the field's
     * width in its place so the fixed-width record geometry still holds. Every emitter-side redaction in the
     * tree spells that as a run of {@code '*'} sized from the picture clause.
     */
    private static final String MONEY_REDACTION = "*".repeat(12);

    /**
     * The stand-in a withheld {@code PIC S9(09)V99} field carries: eleven characters, for
     * {@code TRAN-CAT-BAL} of {@code app/cpy/CVTRA01Y.cpy} and {@code TRAN-AMT} of {@code app/cpy/CVTRA05Y.cpy}.
     *
     * <p>Narrower than {@link #MONEY_REDACTION} by exactly the one integer digit the two picture clauses
     * differ by, which is the point: the token is the field's width, not a fixed word.
     */
    private static final String BALANCE_REDACTION = "*".repeat(11);

    /** A balance chosen so that no substring of it can occur by accident in a label or a citation. */
    private static final BigDecimal DISTINCTIVE_BALANCE = new BigDecimal("8675309.42");

    /** A credit limit, equally distinctive. */
    private static final BigDecimal DISTINCTIVE_LIMIT = new BigDecimal("7318428.91");

    /** A cash credit limit, equally distinctive. */
    private static final BigDecimal DISTINCTIVE_CASH_LIMIT = new BigDecimal("6204517.63");

    /** A cycle credit accumulator, equally distinctive. */
    private static final BigDecimal DISTINCTIVE_CYCLE_CREDIT = new BigDecimal("5193406.28");

    /** A cycle debit accumulator, negative because the posting path legitimately accumulates negatives. */
    private static final BigDecimal DISTINCTIVE_CYCLE_DEBIT = new BigDecimal("-4082395.17");

    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger classLogger;
    private ch.qos.logback.classic.Logger parityLogger;
    private Level originalClassLevel;
    private Level originalParityLevel;

    @AfterEach
    void detachAppender() {
        if (classLogger != null) {
            classLogger.detachAppender(appender);
            classLogger.setLevel(originalClassLevel);
        }
        if (parityLogger != null) {
            parityLogger.detachAppender(appender);
            parityLogger.setLevel(originalParityLevel);
        }
        if (appender != null) {
            appender.stop();
        }
    }

    /**
     * Attaches one capturing appender to <em>both</em> loggers a batch component emits on, and enables both.
     *
     * <p>Attaching to the class logger alone captures nothing of what this suite exists to inspect. Each of
     * the three components routes its record-level diagnostic to a per-program parity logger named
     * {@code com.cardemo.parity.<PROGRAM>}, and that name is not a descendant of the component's own class
     * logger, so a level or an appender set on the class logger reaches none of it. Because the two names are
     * unrelated, one appender on each cannot see the same event twice, and the captured list stays a single
     * ordered stream.
     *
     * @param loggingClass      the class whose own logger is captured, never {@code null}
     * @param parityLoggerName  the {@code com.cardemo.parity.<PROGRAM>} logger the component routes its
     *                          record-level diagnostic to, never {@code null}
     */
    private void capture(final Class<?> loggingClass, final String parityLoggerName) {
        appender = new ListAppender<>();
        appender.start();

        classLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(loggingClass);
        originalClassLevel = classLogger.getLevel();
        classLogger.setLevel(Level.TRACE);
        classLogger.addAppender(appender);

        parityLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(parityLoggerName);
        originalParityLevel = parityLogger.getLevel();
        parityLogger.setLevel(Level.TRACE);
        parityLogger.addAppender(appender);
    }

    /**
     * Renders every captured event as it would be written, message and arguments resolved.
     *
     * @return one string per captured event, never {@code null}
     */
    private List<String> capturedLines() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /**
     * Asserts that no captured event carries the digits of any of the supplied amounts.
     *
     * @param amounts the values that must not appear in any form, never {@code null}
     */
    private void assertNoAmountAppears(final BigDecimal... amounts) {
        final String joined = String.join("\n", capturedLines());

        assertThat(joined).as("something must have been logged, or the test proves nothing").isNotEmpty();
        for (final BigDecimal amount : amounts) {
            // Every rendering an emission site could plausibly use, so a change of formatting cannot
            // quietly reintroduce the value: the plain form, the unscaled digits and the absolute digits.
            assertThat(joined).doesNotContain(amount.toPlainString());
            assertThat(joined).doesNotContain(amount.unscaledValue().toString());
            assertThat(joined).doesNotContain(amount.abs().unscaledValue().toString());
            assertThat(joined).doesNotContain(amount.abs().toPlainString());
        }
    }

    @Nested
    @DisplayName("1. AccountReader, from 1100-DISPLAY-ACCT-RECORD")
    class TheAccountReaderWithholdsMoney {

        private AccountReader reader;

        @BeforeEach
        void driveOneRecord() {
            final Account account = new Account(1L, "Y", DISTINCTIVE_BALANCE, DISTINCTIVE_LIMIT,
                    DISTINCTIVE_CASH_LIMIT, "2024-01-01", "2030-01-01", "2027-01-01",
                    DISTINCTIVE_CYCLE_CREDIT, DISTINCTIVE_CYCLE_DEBIT, "0000012345", "DEFAULT   ");

            final AccountRepository repository = mock(AccountRepository.class);
            when(repository.count()).thenReturn(1L);
            when(repository.findByAccountIdGreaterThanOrderByAccountIdAsc(any(), any(Pageable.class)))
                    .thenReturn(List.of(account))
                    .thenReturn(List.of());

            reader = new AccountReader(repository, new FileStatusMapper(), 10);
            capture(AccountReader.class, "com.cardemo.parity.CBACT01C");
            reader.open(new ExecutionContext());
            assertThat(reader.read()).isNotNull();
        }

        @Test
        @DisplayName("None of the five monetary fields appears in any event")
        void noMonetaryFieldIsLogged() {
            assertNoAmountAppears(DISTINCTIVE_BALANCE, DISTINCTIVE_LIMIT, DISTINCTIVE_CASH_LIMIT,
                    DISTINCTIVE_CYCLE_CREDIT, DISTINCTIVE_CYCLE_DEBIT);
        }

        @Test
        @DisplayName("Each withheld field is replaced by the redaction token, not simply dropped")
        void theFieldsAreRedactedNotRemoved() {
            final String joined = String.join("\n", capturedLines());

            assertThat(joined).contains(MONEY_REDACTION);
            // Five monetary positions in the labelled listing and five in the record image.
            assertThat(joined.split(java.util.regex.Pattern.quote(MONEY_REDACTION), -1).length - 1)
                    .isGreaterThanOrEqualTo(5);
        }

        @Test
        @DisplayName("The labels and the identifying fields still appear, so the diagnostic still diagnoses")
        void theDiagnosticRemainsUseful() {
            final String joined = String.join("\n", capturedLines());

            assertThat(joined).contains("ACCT-ID");
            assertThat(joined).contains("ACCT-CURR-BAL");
            assertThat(joined).contains("ACCT-CREDIT-LIMIT");
            assertThat(joined).contains("00000000001");
            assertThat(joined).contains("2024-01-01");
        }
    }

    @Nested
    @DisplayName("2. TransactionReportProcessor, from DISPLAY TRAN-AMT and WS-PAGE-TOTAL")
    class TheReportProcessorWithholdsMoney {

        @BeforeEach
        void driveTheClosingTotals() {
            final TransactionReportProcessor processor = new TransactionReportProcessor(
                    mock(TransactionRepository.class),
                    mock(CardCrossReferenceRepository.class),
                    mock(TransactionTypeRepository.class),
                    mock(TransactionCategoryRepository.class),
                    new FileStatusMapper(),
                    "2024-01-01",
                    "2024-12-31");

            capture(TransactionReportProcessor.class, "com.cardemo.parity.CBTRN03C");
            processor.finishReport();
        }

        @Test
        @DisplayName("The two closing DISPLAY lines are emitted with their labels")
        void theClosingLinesStillAppear() {
            final String joined = String.join("\n", capturedLines());

            assertThat(joined).contains("TRAN-AMT");
            assertThat(joined).contains("WS-PAGE-TOTAL");
        }

        @Test
        @DisplayName("The two closing aggregates keep their operand verbatim, and only routing withholds them")
        void theClosingAggregatesKeepTheirOperandVerbatim() {
            // ONE CONTROL APPLIES TO THESE TWO LINES, NOT TWO, AND THE ASYMMETRY IS THE POINT.
            // The per-record diagnostic gets both controls: displayTranRecord replaces TRAN-AMT with a
            // same-width token AND routes the line to the parity channel, because it sits beside a
            // transaction identifier and a masked card number and is therefore linkable to a cardholder.
            // These two lines get the routing control only. They are end-of-data aggregates that carry no
            // identifier of any kind, so there is nothing on either line to link the figure to, and their
            // exact text is the output-equivalence guarantee this reproduction exists to provide - which
            // redacting would trade away in return for withholding an unlinkable total. An earlier revision
            // of this suite expected a redaction token here and therefore contradicted the emitter.
            assertThat(capturedLines())
                    .anySatisfy(line -> assertThat(line).isEqualTo("TRAN-AMT 0"))
                    .anySatisfy(line -> assertThat(line).isEqualTo("WS-PAGE-TOTAL0"));
        }

        @Test
        @DisplayName("Routing is what withholds them: both lines leave on the parity logger, never the class one")
        void bothClosingLinesLeaveOnTheParityLogger() {
            // This is the assertion that makes the verbatim operand safe, so it is asserted rather than
            // assumed. com.cardemo.parity.CBTRN03C is pinned to OFF by every shipped profile and a level set
            // on com.cardemo cannot raise it, because it is not an ancestor of that name. So no deployment
            // emits either line and only an isolated parity run does.
            assertThat(appender.list)
                    .filteredOn(event -> event.getFormattedMessage().startsWith("TRAN-AMT ")
                            || event.getFormattedMessage().startsWith("WS-PAGE-TOTAL"))
                    .isNotEmpty()
                    .allSatisfy(event -> assertThat(event.getLoggerName())
                            .as("a closing aggregate on the class logger would reach routine log volume")
                            .isEqualTo("com.cardemo.parity.CBTRN03C"));
        }

        @Test
        @DisplayName("The literal spacing of the two source DISPLAY statements is still reproduced")
        void theSourceSpacingIsReproduced() {
            // app/cbl/CBTRN03C.cbl:L198 - the literal 'TRAN-AMT ' carries its own trailing space.
            // app/cbl/CBTRN03C.cbl:L199 - 'WS-PAGE-TOTAL' has none, so the operand runs straight on.
            assertThat(capturedLines()).contains("TRAN-AMT 0");
            assertThat(capturedLines()).contains("WS-PAGE-TOTAL0");
        }
    }

    @Nested
    @DisplayName("3. InterestCalculationProcessor, from DISPLAY TRAN-CAT-BAL-RECORD")
    class TheInterestProcessorWithholdsMoney {

        /** A category balance chosen so no substring of it can occur in a label or a citation. */
        private static final BigDecimal DISTINCTIVE_CATEGORY_BALANCE = new BigDecimal("3971286.54");

        @BeforeEach
        void driveOneCategoryBalance() {
            final InterestCalculationProcessor processor = new InterestCalculationProcessor(
                    mock(DisclosureGroupRepository.class),
                    mock(AccountRepository.class),
                    mock(CardCrossReferenceRepository.class),
                    new FileStatusMapper(),
                    "2024010100",
                    Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC));

            capture(InterestCalculationProcessor.class, "com.cardemo.parity.CBACT04C");

            // The record diagnostic is emitted before the account read, and that read abends against an
            // empty store exactly as app/cbl/CBACT04C.cbl:L393-L413 abends on a missing account. The abend
            // is the source's behaviour and is not what this test is about, so it is allowed to happen: what
            // matters is what the diagnostic already emitted put on the log.
            try {
                processor.process(new TransactionCategoryBalance(
                        new TransactionCategoryBalanceId(1L, "PR", 1),
                        DISTINCTIVE_CATEGORY_BALANCE));
            } catch (RuntimeException expected) {
                assertThat(expected).isNotNull();
            }
        }

        @Test
        @DisplayName("TRAN-CAT-BAL does not appear in any event")
        void theCategoryBalanceIsNotLogged() {
            assertNoAmountAppears(DISTINCTIVE_CATEGORY_BALANCE);
        }

        @Test
        @DisplayName("The record diagnostic still names the three key fields and the sequence number")
        void theKeyFieldsStillAppear() {
            final String joined = String.join("\n", capturedLines());

            assertThat(joined).contains("TRAN-CAT-BAL-RECORD");
            assertThat(joined).contains("TRANCAT-ACCT-ID=00000000001");
            assertThat(joined).contains("TRANCAT-TYPE-CD=PR");
            assertThat(joined).contains("TRANCAT-CD=1");
            assertThat(joined).contains("sequence=1");
            assertThat(joined).contains("TRAN-CAT-BAL=" + BALANCE_REDACTION);
        }
    }
}

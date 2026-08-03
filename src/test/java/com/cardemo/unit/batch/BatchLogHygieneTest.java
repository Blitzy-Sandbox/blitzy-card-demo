/*
 * ******************************************************************
 * Program     : BatchLogHygieneTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test (log-capture contract)
 * Function    : Prove that the batch verification readers and the two
 *               posting/report processors emit no record value into any
 *               log event, at any enabled level - while still emitting the
 *               COBOL DISPLAY statements they are traceable to.
 * Source      : app/cbl/CBACT01C.cbl:L78, :L96, :L118-L131 (ACCOUNT-RECORD
 *                 and the eleven field lines of 1100-DISPLAY-ACCT-RECORD)
 *               app/cbl/CBACT02C.cbl:L78 (CARD-RECORD)
 *               app/cbl/CBACT03C.cbl:L78, :L96 (CARD-XREF-RECORD)
 *               app/cbl/CBCUS01C.cbl:L78, :L96 (CUSTOMER-RECORD)
 *               app/cpy/CVACT01Y.cpy, CVACT02Y.cpy, CVACT03Y.cpy,
 *                 CVCUS01Y.cpy (the record layouts whose values are withheld)
 *               @ 7756d89
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.cardemo.batch.readers.AccountReader;
import com.cardemo.batch.readers.CardCrossReferenceReader;
import com.cardemo.batch.readers.CardReader;
import com.cardemo.batch.readers.CustomerReader;
import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.Card;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Customer;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.CardRepository;
import com.cardemo.repository.CustomerRepository;
import com.cardemo.service.shared.FileStatusMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.data.domain.Pageable;

/**
 * The log-hygiene contract of the four read-only verification readers.
 *
 * <h2>What this class proves, and why it is not the same as masking</h2>
 * Each of {@code CBACT01C}, {@code CBACT02C}, {@code CBACT03C} and {@code CBCUS01C} writes its whole record
 * to SYSOUT for every row it reads, and {@code CBACT01C} additionally writes eleven labelled field lines
 * through {@code 1100-DISPLAY-ACCT-RECORD}. Reproduced literally those statements publish account
 * identifiers, balances, credit limits, cycle totals, card numbers, card verification values, social security
 * numbers, dates of birth, government-issued identifiers and names into the application log.
 *
 * <p>A log is not the database. It is aggregated, retained and replicated well outside the boundary that
 * protects the row, and a log level is a configuration setting rather than a boundary - {@code DEBUG} is
 * enabled in any diagnostic configuration. So the fix is not to lower a level, and not to mask one field
 * while leaving its neighbours: it is that <b>no value from the record is rendered at all</b>.
 *
 * <p>Every assertion here therefore captures at {@link Level#TRACE} - the lowest level any of these classes
 * can emit at - so that a value emitted at {@code DEBUG} cannot slip past. Each test also asserts that the
 * capture is non-empty, because an absence assertion against an empty capture proves nothing.
 *
 * <h2>What is deliberately still emitted</h2>
 * The emission POINTS are the traceability artefact and they survive: one event per row for
 * {@code CBACT02C} whose {@code :L96} display is commented out, two per row for the other three, plus
 * {@code CBACT01C}'s eleven labels and its 49-hyphen rule. The counters at start and end of run survive
 * unchanged, because a row count is not record data and the batch contract is built on it.
 *
 * <h2>How to run</h2>
 * {@code ./mvnw -B -ntp -Dtest=BatchLogHygieneTest test}. No container, no database and no profile: each
 * reader is built over a mocked repository and driven directly.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
@DisplayName("batch log hygiene - no record value reaches any log event at any level")
class BatchLogHygieneTest {

    /** {@code ACCT-ID PIC 9(11)}, and the value no account event may carry. */
    private static final long ACCOUNT_ID = 12345678901L;

    /** {@code CUST-ID PIC 9(09)}. */
    private static final long CUSTOMER_ID = 987654321L;

    /**
     * {@code CARD-NUM PIC X(16)}. Synthetic: not a real primary account number, not Luhn valid and issued by
     * no scheme.
     */
    private static final String CARD_NUMBER = "4111222233334444";

    /** {@code ACCT-CURR-BAL PIC S9(10)V99}. */
    private static final String CURRENT_BALANCE = "9876.54";

    /** {@code ACCT-CREDIT-LIMIT PIC S9(10)V99}. */
    private static final String CREDIT_LIMIT = "24680.00";

    /** {@code ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99}. */
    private static final String CASH_CREDIT_LIMIT = "1357.99";

    /** {@code ACCT-CURR-CYC-CREDIT PIC S9(10)V99}. */
    private static final String CYCLE_CREDIT = "4321.00";

    /** {@code ACCT-CURR-CYC-DEBIT PIC S9(10)V99}. */
    private static final String CYCLE_DEBIT = "8642.10";

    /** {@code CUST-SSN PIC 9(09)}. */
    private static final String SOCIAL_SECURITY_NUMBER = "111223333";

    /** {@code CUST-DOB-YYYY-MM-DD PIC X(10)}. */
    private static final String DATE_OF_BIRTH = "1979-04-17";

    /** {@code CUST-GOVT-ISSUED-ID PIC X(20)}. */
    private static final String GOVERNMENT_ID = "WA-DL-55443322";

    /** {@code CUST-FIRST-NAME PIC X(25)}. */
    private static final String FIRST_NAME = "ROSALIND";

    /** {@code CUST-LAST-NAME PIC X(25)}. */
    private static final String LAST_NAME = "FRANKLIN";

    /** {@code CUST-ADDR-LINE-1 PIC X(50)}. */
    private static final String ADDRESS_LINE_1 = "44 KINGS BUILDINGS WAY";

    /** {@code ACCT-GROUP-ID PIC X(10)}. */
    private static final String GROUP_ID = "PREMIER";

    /** The page size each reader is constructed with; one row per page keeps the drive trivial. */
    private static final int PAGE_SIZE = 2;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private CardRepository cardRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private CardCrossReferenceRepository cardCrossReferenceRepository;

    /** The real mapper: its status vocabulary is the reader's, and stubbing it would prove less. */
    private FileStatusMapper fileStatusMapper;

    /** The captured events, from whichever reader the test drives. */
    private ListAppender<ILoggingEvent> logEvents;

    /** The logger the appender is attached to, detached again in {@link #tearDown()}. */
    private Logger capturedLogger;

    /** The parity logger, when the reader under test routes anything to one. */
    private Logger capturedParityLogger;

    @BeforeEach
    void setUp() {
        this.fileStatusMapper = new FileStatusMapper();
    }

    @AfterEach
    void tearDown() {
        if (this.capturedParityLogger != null) {
            this.capturedParityLogger.detachAppender(this.logEvents);
            this.capturedParityLogger.setLevel(null);
            this.capturedParityLogger = null;
        }
        if (this.capturedLogger != null) {
            this.capturedLogger.detachAppender(this.logEvents);
            this.logEvents.stop();
            this.capturedLogger.setLevel(null);
        }
    }

    /**
     * Attaches an in-memory appender to one reader's logger at {@link Level#TRACE}.
     *
     * <p>{@code TRACE} rather than {@code DEBUG}: the point of the contract is that no level is low enough
     * to make a record value acceptable, so the capture must sit below every level the class can emit at.
     *
     * @param type the class whose logger to capture; must not be {@code null}
     */
    private void captureLogsOf(final Class<?> type) {
        this.capturedLogger = (Logger) LoggerFactory.getLogger(type);
        this.logEvents = new ListAppender<>();
        this.logEvents.start();
        this.capturedLogger.addAppender(this.logEvents);
        this.capturedLogger.setLevel(Level.TRACE);

        // "Any log event at any level" is what this suite is named for, and the class logger alone is not
        // that. AccountReader routes its two record-level emissions to a per-program parity logger whose name
        // is not a descendant of the class logger, so neither a level nor an appender set above reaches it.
        // The other three readers emit only on their class logger, so they have no second name to attach to.
        final String parityLogger = PARITY_LOGGERS.get(type);
        if (parityLogger != null) {
            this.capturedParityLogger = (Logger) LoggerFactory.getLogger(parityLogger);
            this.capturedParityLogger.addAppender(this.logEvents);
            this.capturedParityLogger.setLevel(Level.TRACE);
        }
    }

    /**
     * The parity logger each reader routes its record-level diagnostic to, where it has one.
     *
     * <p>Only {@code AccountReader} does: it is the one reader whose source paragraph displays the whole
     * record and a labelled field listing, so it is the one with output that had to be moved off the shared
     * channel rather than reduced to an ordinal.
     */
    private static final java.util.Map<Class<?>, String> PARITY_LOGGERS =
            java.util.Map.of(AccountReader.class, "com.cardemo.parity.CBACT01C");

    /**
     * Renders every captured event as one searchable string - the formatted message, every argument and the
     * whole throwable chain. Used only to prove that a value is <em>absent</em>.
     *
     * @return the concatenated log output, never {@code null}
     */
    private String capturedLogText() {
        final StringBuilder text = new StringBuilder(1024);
        for (final ILoggingEvent event : this.logEvents.list) {
            text.append(event.getFormattedMessage()).append('\n');
            if (event.getArgumentArray() != null) {
                for (final Object argument : event.getArgumentArray()) {
                    text.append(argument).append('\n');
                }
            }
            for (IThrowableProxy proxy = event.getThrowableProxy(); proxy != null; proxy = proxy.getCause()) {
                text.append(proxy.getClassName()).append('\n').append(proxy.getMessage()).append('\n');
            }
        }
        return text.toString();
    }

    /**
     * The account record whose every field the log must not carry.
     *
     * @return a fully populated account, never {@code null}
     */
    private static Account account() {
        return new Account(ACCOUNT_ID, "Y", new BigDecimal(CURRENT_BALANCE), new BigDecimal(CREDIT_LIMIT),
                new BigDecimal(CASH_CREDIT_LIMIT), "2001-02-03", "2031-02-03", "2021-02-03",
                new BigDecimal(CYCLE_CREDIT), new BigDecimal(CYCLE_DEBIT), "98109", GROUP_ID);
    }

    /**
     * The card record whose every field the log must not carry.
     *
     * @return a fully populated card, never {@code null}
     */
    private static Card card() {
        return new Card(CARD_NUMBER, ACCOUNT_ID, FIRST_NAME + " " + LAST_NAME, "2031-02-03", "Y");
    }

    /**
     * The customer record whose every field the log must not carry.
     *
     * @return a fully populated customer, never {@code null}
     */
    private static Customer customer() {
        return new Customer(CUSTOMER_ID, FIRST_NAME, "E", LAST_NAME, ADDRESS_LINE_1, "FLAT 2", "EDINBURGH",
                "WA", "USA", "98109", "(206)555-0142", "(425)555-0187", SOCIAL_SECURITY_NUMBER,
                GOVERNMENT_ID, DATE_OF_BIRTH, "0000000042", "Y", "812");
    }

    /**
     * The cross-reference record whose every field the log must not carry.
     *
     * @return a fully populated cross-reference, never {@code null}
     */
    private static CardCrossReference crossReference() {
        return new CardCrossReference(CARD_NUMBER, CUSTOMER_ID, ACCOUNT_ID);
    }

    /**
     * The account master: {@code DISPLAY ACCOUNT-RECORD} at {@code :L78} and the eleven labelled lines of
     * {@code 1100-DISPLAY-ACCT-RECORD} at {@code :L118-L131}.
     */
    @Nested
    @DisplayName("CBACT01C - the 300-byte image and the eleven field lines")
    final class AccountMaster {

        /**
         * Drives one row through the reader with its logger captured at {@code TRACE}.
         *
         * @return the row the reader emitted, for the caller to assert on
         */
        private Account driveOneRow() {
            captureLogsOf(AccountReader.class);
            when(accountRepository.count()).thenReturn(1L);
            when(accountRepository.findByAccountIdGreaterThanOrderByAccountIdAsc(any(), any(Pageable.class)))
                    .thenReturn(List.of(account())).thenReturn(List.of());

            final AccountReader reader =
                    new AccountReader(accountRepository, fileStatusMapper, PAGE_SIZE);
            reader.open(new ExecutionContext());
            final Account emitted = reader.read();
            reader.close();
            return emitted;
        }

        @Test
        @DisplayName("neither emission carries a balance, a limit or a cycle total")
        void neitherEmissionCarriesAMonetaryValue() {
            final Account emitted = driveOneRow();

            assertThat(emitted).as("the row still reaches the step, which is the reader's contract")
                    .isNotNull();
            final String logged = capturedLogText();
            assertThat(logged).as("the reader does emit, so these absence assertions are not vacuous")
                    .isNotEmpty();
            // The five MONETARY fields are what may not appear. An earlier revision also required the account
            // key and the group identifier to be absent; both are deliberately retained, and retaining them is
            // the documented design: AccountReader:1026 states that each present monetary value is replaced by
            // a same-width stand-in "so the label, the field order, the field count and the column geometry
            // are all still those of the source while the value itself never reaches a log event". The key is
            // what makes the remaining diagnostic useful, and SensitiveDataRedactionTest asserts its presence
            // for the same reason, so requiring its absence here contradicted both the reader and that suite.
            assertThat(logged)
                    .doesNotContain(CURRENT_BALANCE)
                    .doesNotContain(CREDIT_LIMIT)
                    .doesNotContain(CASH_CREDIT_LIMIT)
                    .doesNotContain(CYCLE_CREDIT)
                    .doesNotContain(CYCLE_DEBIT);
            assertThat(logged)
                    .as("the key and the group identifier are retained on purpose, and are not financial data")
                    .contains(Long.toString(ACCOUNT_ID))
                    .contains(GROUP_ID);
        }

        @Test
        @DisplayName("the eleven labels and the 49-hyphen rule of :L118-L131 are still emitted")
        void theParagraphStructureSurvives() {
            driveOneRow();
            final String logged = capturedLogText();

            // Parity structure 3: the label set, its order, the deliberate absence of ACCT-ADDR-ZIP and the
            // rule width are the traceable artefact. Withholding the values must not have cost any of it.
            assertThat(logged)
                    .contains("ACCT-ID                 :")
                    .contains("ACCT-ACTIVE-STATUS      :")
                    .contains("ACCT-CURR-BAL           :")
                    .contains("ACCT-CREDIT-LIMIT       :")
                    .contains("ACCT-CASH-CREDIT-LIMIT  :")
                    .contains("ACCT-OPEN-DATE          :")
                    .contains("ACCT-EXPIRAION-DATE     :")
                    .contains("ACCT-REISSUE-DATE       :")
                    .contains("ACCT-CURR-CYC-CREDIT    :")
                    .contains("ACCT-CURR-CYC-DEBIT     :")
                    .contains("ACCT-GROUP-ID           :")
                    .contains("-".repeat(49));
            assertThat(logged)
                    .as(":L118-L131 displays eleven of the twelve fields and skips the postal code")
                    .doesNotContain("ACCT-ADDR-ZIP");
        }

        @Test
        @DisplayName("the end-of-run row count is still emitted, because a count is not record data")
        void theRowCountSurvives() {
            driveOneRow();

            assertThat(capturedLogText()).contains("recordsRead=");
        }
    }

    /** The card master: {@code DISPLAY CARD-RECORD} at {@code app/cbl/CBACT02C.cbl:L78}. */
    @Nested
    @DisplayName("CBACT02C - the 150-byte image, which carries a card number and a CVV")
    final class CardMaster {

        @Test
        @DisplayName("the emission carries neither the card number, the CVV nor the owning account")
        void theEmissionCarriesNoFieldValue() {
            captureLogsOf(CardReader.class);
            when(cardRepository.count()).thenReturn(1L);
            when(cardRepository.findByCardNumberGreaterThanOrderByCardNumberAsc(any(), any(Pageable.class)))
                    .thenReturn(List.of(card())).thenReturn(List.of());

            final CardReader reader = new CardReader(cardRepository, fileStatusMapper, PAGE_SIZE);
            reader.open(new ExecutionContext());
            final Card emitted = reader.read();
            reader.close();

            assertThat(emitted).isNotNull();
            final String logged = capturedLogText();
            assertThat(logged).isNotEmpty();
            assertThat(logged)
                    .doesNotContain(CARD_NUMBER)
                    .doesNotContain(Long.toString(ACCOUNT_ID))
                    .doesNotContain(LAST_NAME);
            assertThat(logged).as("the row count is still reported").contains("recordsRead=");
        }
    }

    /** The customer master: {@code DISPLAY CUSTOMER-RECORD} at {@code :L78} and again at {@code :L96}. */
    @Nested
    @DisplayName("CBCUS01C - the 500-byte image, which carries an SSN, a date of birth and an address")
    final class CustomerMaster {

        @Test
        @DisplayName("neither emission carries the SSN, the date of birth, the government id, a name or CUST-ID")
        void neitherEmissionCarriesAFieldValue() {
            captureLogsOf(CustomerReader.class);
            when(customerRepository.count()).thenReturn(1L);
            when(customerRepository.findByCustomerIdGreaterThanOrderByCustomerIdAsc(any(), any(Pageable.class)))
                    .thenReturn(List.of(customer())).thenReturn(List.of());

            final CustomerReader reader =
                    new CustomerReader(customerRepository, fileStatusMapper, PAGE_SIZE);
            reader.open(new ExecutionContext());
            final Customer emitted = reader.read();
            reader.close();

            assertThat(emitted).isNotNull();
            final String logged = capturedLogText();
            assertThat(logged).isNotEmpty();
            assertThat(logged)
                    .doesNotContain(SOCIAL_SECURITY_NUMBER)
                    .doesNotContain(DATE_OF_BIRTH)
                    .doesNotContain(GOVERNMENT_ID)
                    .doesNotContain(FIRST_NAME)
                    .doesNotContain(LAST_NAME)
                    .doesNotContain(ADDRESS_LINE_1)
                    .doesNotContain(Long.toString(CUSTOMER_ID));
            assertThat(logged).contains("recordsRead=");
        }
    }

    /**
     * The cross-reference: {@code DISPLAY CARD-XREF-RECORD} at {@code app/cbl/CBACT03C.cbl:L78} and again at
     * {@code :L96}. This relation is nothing but the association between a card, an account and a customer,
     * so any one of its three columns is part of that association.
     */
    @Nested
    @DisplayName("CBACT03C - the association itself, so every column is sensitive")
    final class CrossReference {

        @Test
        @DisplayName("neither emission carries the card number, the account id or the customer id")
        void neitherEmissionCarriesAFieldValue() {
            captureLogsOf(CardCrossReferenceReader.class);
            when(cardCrossReferenceRepository.count()).thenReturn(1L);
            when(cardCrossReferenceRepository.findByCardNumberGreaterThanOrderByCardNumberAsc(
                    any(), any(Pageable.class)))
                    .thenReturn(List.of(crossReference())).thenReturn(List.of());

            final CardCrossReferenceReader reader =
                    new CardCrossReferenceReader(cardCrossReferenceRepository, fileStatusMapper, PAGE_SIZE);
            reader.open(new ExecutionContext());
            final CardCrossReference emitted = reader.read();
            reader.close();

            assertThat(emitted).isNotNull();
            final String logged = capturedLogText();
            assertThat(logged).isNotEmpty();
            assertThat(logged)
                    .doesNotContain(CARD_NUMBER)
                    .doesNotContain(Long.toString(ACCOUNT_ID))
                    .doesNotContain(Long.toString(CUSTOMER_ID));
            assertThat(logged).contains("recordsRead=");
        }
    }
}

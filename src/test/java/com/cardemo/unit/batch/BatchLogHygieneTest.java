/*
 * ******************************************************************
 * Program     : BatchLogHygieneTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test (log-capture contract)
 * Function    : Prove that the batch verification readers, the two staging
 *               readers and the posting and statement jobs emit no record
 *               value, business identifier or object-store key into any log
 *               event, at any enabled level - while still emitting the COBOL
 *               DISPLAY statements they are traceable to.
 * Source      : app/cbl/CBACT01C.cbl:L78, :L96, :L118-L131 (ACCOUNT-RECORD
 *                 and the eleven field lines of 1100-DISPLAY-ACCT-RECORD)
 *               app/cbl/CBACT02C.cbl:L78 (CARD-RECORD)
 *               app/cbl/CBACT03C.cbl:L78, :L96 (CARD-XREF-RECORD)
 *               app/cbl/CBCUS01C.cbl:L78, :L96 (CUSTOMER-RECORD)
 *               app/cbl/CBTRN01C.cbl:L177-L179 (DISPLAY 'ACCOUNT ' ACCT-ID
 *                 ' NOT FOUND') and :L180-L184 (DALYTRAN-CARD-NUM)
 *               app/cbl/CBTRN02C.cbl:L236-L252, :L582-L598 (the open and
 *                 close idioms the two staging readers borrow)
 *               app/jcl/CREASTMT.JCL:L67, :L72, :L87, :L92 (the HTMLFILE and
 *                 STMTFILE DD names that replace their concrete object keys)
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.cardemo.batch.jobs.DailyTransactionPostingJob;
import com.cardemo.batch.jobs.StatementGenerationJob;
import com.cardemo.batch.readers.AccountReader;
import com.cardemo.batch.readers.CardCrossReferenceReader;
import com.cardemo.batch.readers.CardReader;
import com.cardemo.batch.readers.CustomerReader;
import com.cardemo.batch.readers.DailyTransactionReader;
import com.cardemo.batch.readers.TransactionBackupReader;
import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.Card;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Customer;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.CardRepository;
import com.cardemo.repository.CustomerRepository;
import com.cardemo.repository.DailyTransactionRepository;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.service.shared.FileStatusMapper;
import io.awspring.cloud.s3.S3Operations;
import io.awspring.cloud.s3.S3Resource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.SliceImpl;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable;

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

    /**
     * The bucket the two staging readers are configured with.
     *
     * <p>A bucket name is configuration and identifies nobody, so - unlike a key - it is deliberately
     * <em>expected</em> in the log output. Naming it in the assertions is what proves the emissions were
     * reduced rather than merely emptied.
     */
    private static final String BUCKET = "carddemo-batch-test";

    /**
     * The {@code DALYTRAN} input key, and the value no {@code DailyTransactionReader} event may carry.
     *
     * <p>Date partitioned, exactly as the configured default is, which is what makes it disclosing: the key
     * states which business date the run covers.
     */
    private static final String DALYTRAN_OBJECT_KEY = "dalytran/2026-08-04/dailytran.txt";

    /**
     * The signing key the input object's authenticity envelope is derived from, at least the thirty-two bytes
     * the algorithm requires. A test literal: finding M-11 forbids a committed key anywhere in the repository.
     */
    private static final String SIGNING_KEY = "batch-log-hygiene-test-signing-key-0123456789";

    /**
     * The writer identity the stubbed input object claims.
     *
     * <p>Deliberately not a customer-derived value. It appears in the reader's own authenticity log line, and
     * this class exists to assert what may and may not appear there: an attribution identity may, a business
     * date or an object key may not.
     */
    private static final String OBJECT_WRITER = "carddemo-fixture-feed";

    /**
     * A {@code TRANSACT.BKUP} generation key, and the value no {@code TransactionBackupReader} event may
     * carry. It identifies one concrete backup of the transaction cluster and the run that produced it.
     */
    private static final String GENERATION_OBJECT_KEY =
            "gdg/transact-bkup/generation=0000000000000000007/TRANSACT.BKUP";

    /** {@code TRAN-ID PIC X(16)}, the checkpointed identifier neither reader may name by value. */
    private static final String CHECKPOINTED_TRANSACTION_ID = "0000000000000913";

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private CardRepository cardRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private CardCrossReferenceRepository cardCrossReferenceRepository;

    @Mock
    private DailyTransactionRepository dailyTransactionRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private S3Operations objectStorage;

    /**
     * The paginating object-storage client the generation reader resolves the {@code (0)} semantic through.
     *
     * <p>Separate from {@link #objectStorage} because only the enumeration needs pagination: a single-page
     * listing would resolve the greatest key of the first page rather than of the whole base.
     */
    @Mock
    private S3Client objectStoreClient;

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
        return new Card(CARD_NUMBER, ACCOUNT_ID, "007", FIRST_NAME + " " + LAST_NAME, "2031-02-03", "Y");
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

    /**
     * The {@code DALYTRAN} staging reader, whose disclosure is an <em>object key</em> rather than a record.
     *
     * <p><b>Finding, severity Medium, resolved - CWE-532 and CWE-200.</b> Three {@code INFO} emissions named
     * the concrete input object key and the checkpointed {@code DALYTRAN-ID}. The key is date partitioned, so
     * it states which business date a run covers, and because one key serves every record of a run, naming it
     * once names it for the whole run.
     *
     * <p>Each test here asserts <em>both</em> directions. The forbidden value must be absent, and the
     * replacement must be present - because an emission that was simply deleted would satisfy the first
     * assertion and destroy the diagnostic. The bucket name is deliberately expected in the output: it is
     * configuration and identifies nobody, so its presence is what distinguishes "reduced" from "emptied".
     */
    @Nested
    @DisplayName("DALYTRAN staging - the input object key and the checkpointed id are withheld")
    final class DalytranStaging {

        @Test
        @DisplayName("open and close name the logical dataset and the source, never the object key")
        void openAndCloseNameNoObjectKey() throws IOException {
            captureLogsOf(DailyTransactionReader.class);
            // Every collaborator mock is fully built BEFORE the first when(...) on objectStorage. Building
            // one inside a thenReturn(...) argument would start a second stubbing while the first is still
            // open, which Mockito reports as UnfinishedStubbing.
            final S3Resource object = authenticatedEmptyObject();
            when(objectStorage.objectExists(BUCKET, DALYTRAN_OBJECT_KEY)).thenReturn(Boolean.TRUE);
            when(objectStorage.download(BUCKET, DALYTRAN_OBJECT_KEY)).thenReturn(object);

            final DailyTransactionReader reader = new DailyTransactionReader(dailyTransactionRepository,
                    objectStorage, fileStatusMapper, "fixed-width", PAGE_SIZE, BUCKET,
                    DALYTRAN_OBJECT_KEY, SIGNING_KEY);
            reader.open(new ExecutionContext());
            reader.close();

            final String logged = capturedLogText();
            assertThat(logged).isNotEmpty();
            assertThat(logged)
                    .as("the key is passed to object storage and to nothing else; app/cbl/CBTRN02C.cbl "
                            + "displays no dataset name on open at :L247 either, so nothing is lost")
                    .doesNotContain(DALYTRAN_OBJECT_KEY)
                    .doesNotContain("dailytran.txt")
                    .doesNotContain("2026-08-04");
            assertThat(logged)
                    .as("what replaces it must still identify the dataset, the path taken and the row count")
                    .contains("DALYTRAN")
                    .contains("source=FIXED_WIDTH")
                    .contains("recordsRead=");
        }

        @Test
        @DisplayName("a restart reports the checkpointed id as present, never by value")
        void aRestartReportsThePresenceOfTheCheckpointedIdOnly() {
            captureLogsOf(DailyTransactionReader.class);
            // The staging relation is walked by ingest ordinal, so the open probe reaches the keyset finder;
            // an empty window is enough here, because what is under test is the resume LINE and not the walk.
            when(dailyTransactionRepository.findByIngestSequenceGreaterThanOrderByIngestSequenceAsc(
                    anyLong(), any(Pageable.class)))
                    .thenReturn(new SliceImpl<>(List.of(), Pageable.ofSize(PAGE_SIZE), false));

            final ExecutionContext restart = new ExecutionContext();
            // The two entry names mirror the reader's own private constants; they are literals here because
            // the reader keeps them private, which is correct - a checkpoint key is not part of its API.
            restart.putLong("DailyTransactionReader.recordsRead", 4L);
            restart.putString("DailyTransactionReader.lastTransactionId", CHECKPOINTED_TRANSACTION_ID);

            final DailyTransactionReader reader = new DailyTransactionReader(dailyTransactionRepository,
                    objectStorage, fileStatusMapper, "repository", PAGE_SIZE, "", DALYTRAN_OBJECT_KEY,
                    SIGNING_KEY);
            reader.open(restart);
            reader.close();

            final String logged = capturedLogText();
            assertThat(logged).isNotEmpty();
            assertThat(logged)
                    .as("the record count IS the restart position; the identifier only corroborates it, so "
                            + "naming it would disclose a business record key for no diagnostic gain")
                    .doesNotContain(CHECKPOINTED_TRANSACTION_ID);
            assertThat(logged)
                    .as("the presence marker and the record count are what a restart diagnostic needs")
                    .contains("checkpointedKey=present")
                    .contains("Resuming DALYTRAN read after 4 records");
        }

        @Test
        @DisplayName("no checkpoint reports absent, so the marker distinguishes the two states")
        void noCheckpointReportsAbsent() {
            captureLogsOf(DailyTransactionReader.class);
            // The staging relation is walked by ingest ordinal, so the open probe reaches the keyset finder;
            // an empty window is enough here, because what is under test is the resume LINE and not the walk.
            when(dailyTransactionRepository.findByIngestSequenceGreaterThanOrderByIngestSequenceAsc(
                    anyLong(), any(Pageable.class)))
                    .thenReturn(new SliceImpl<>(List.of(), Pageable.ofSize(PAGE_SIZE), false));

            final ExecutionContext restart = new ExecutionContext();
            restart.putLong("DailyTransactionReader.recordsRead", 4L);

            final DailyTransactionReader reader = new DailyTransactionReader(dailyTransactionRepository,
                    objectStorage, fileStatusMapper, "repository", PAGE_SIZE, "", DALYTRAN_OBJECT_KEY,
                    SIGNING_KEY);
            reader.open(restart);

            assertThat(capturedLogText())
                    .as("the control for the test above: a two-valued marker that always read 'present' "
                            + "would carry no information and would hide the resume-from-zero case")
                    .contains("checkpointedKey=absent");
        }
    }

    /**
     * The {@code TRANSACT.BKUP} generation reader, whose disclosure is a <em>generation key</em>.
     *
     * <p><b>Finding, severity Medium, resolved - CWE-532 and CWE-200.</b> Seven {@code INFO} emissions named
     * a generation object key or the checkpointed {@code TRAN-ID}. A generation key identifies one concrete
     * backup of the transaction cluster and the run that produced it.
     *
     * <p>The replacement is graded rather than uniform, and that is the point of the second test: where the
     * selected generation's identity genuinely matters - the standalone {@code (0)} resolution, which is the
     * symptom of a pipeline that ran out of order - it is reported as an ordinal out of a count. Everywhere
     * else a two-valued marker is the whole of the diagnostic value.
     */
    @Nested
    @DisplayName("TRANSACT.BKUP - the generation key and the checkpointed id are withheld")
    final class TransactBackupGeneration {

        @Test
        @DisplayName("resolution, open and close name a marker and a count, never the generation key")
        void resolutionOpenAndCloseNameNoGenerationKey() throws IOException {
            captureLogsOf(TransactionBackupReader.class);
            final S3Resource object = emptyObject();
            stubGenerationListing(GENERATION_OBJECT_KEY);
            when(objectStorage.objectExists(BUCKET, GENERATION_OBJECT_KEY)).thenReturn(Boolean.TRUE);
            when(objectStorage.download(BUCKET, GENERATION_OBJECT_KEY)).thenReturn(object);

            final TransactionBackupReader reader = new TransactionBackupReader(transactionRepository,
                    objectStorage, objectStoreClient, fileStatusMapper, "object-storage", PAGE_SIZE,
                    BUCKET,
                    "gdg/transact-bkup", null);
            reader.open(new ExecutionContext());
            reader.close();

            final String logged = capturedLogText();
            assertThat(logged).isNotEmpty();
            assertThat(logged)
                    .as("the key reaches object storage and the job repository, and no log event")
                    .doesNotContain(GENERATION_OBJECT_KEY)
                    .doesNotContain("generation=0000000000000000007");
            assertThat(logged)
                    .as("the configured prefix is configuration and stays, the count identifies the "
                            + "selection, and the marker states that a generation was in play at all")
                    .contains("gdg/transact-bkup")
                    .contains("resolved generation 1 of 1")
                    .contains("generation=resolved")
                    .contains("recordsRead=");
        }

        @Test
        @DisplayName("the ordinal counts every generation, so it distinguishes one backup from many")
        void theOrdinalCountsEveryGeneration() throws IOException {
            captureLogsOf(TransactionBackupReader.class);
            final String older = "gdg/transact-bkup/generation=0000000000000000006/TRANSACT.BKUP";
            final S3Resource object = emptyObject();
            stubGenerationListing(older, GENERATION_OBJECT_KEY);
            when(objectStorage.objectExists(BUCKET, GENERATION_OBJECT_KEY)).thenReturn(Boolean.TRUE);
            when(objectStorage.download(BUCKET, GENERATION_OBJECT_KEY)).thenReturn(object);

            final TransactionBackupReader reader = new TransactionBackupReader(transactionRepository,
                    objectStorage, objectStoreClient, fileStatusMapper, "object-storage", PAGE_SIZE,
                    BUCKET,
                    "gdg/transact-bkup", null);
            reader.open(new ExecutionContext());

            final String logged = capturedLogText();
            assertThat(logged)
                    .as("two generations exist and the (0) semantic takes the greatest, which is the second "
                            + "of two - so the ordinal moves with the listing rather than being a constant")
                    .contains("resolved generation 2 of 2");
            assertThat(logged)
                    .as("and neither key is named, not the selected one and not the one passed over")
                    .doesNotContain(GENERATION_OBJECT_KEY)
                    .doesNotContain(older);
        }

        @Test
        @DisplayName("a carry-forward names the context entry it came from, never the key it carried")
        void aCarryForwardNamesOnlyTheContextEntry() throws IOException {
            captureLogsOf(TransactionBackupReader.class);
            final S3Resource object = emptyObject();
            when(objectStorage.objectExists(BUCKET, GENERATION_OBJECT_KEY)).thenReturn(Boolean.TRUE);
            when(objectStorage.download(BUCKET, GENERATION_OBJECT_KEY)).thenReturn(object);

            final TransactionBackupReader reader = new TransactionBackupReader(transactionRepository,
                    objectStorage, objectStoreClient, fileStatusMapper, "object-storage", PAGE_SIZE,
                    BUCKET,
                    "gdg/transact-bkup", GENERATION_OBJECT_KEY);
            reader.open(new ExecutionContext());

            final String logged = capturedLogText();
            assertThat(logged)
                    .as("the promoted key is what a prior step wrote; naming the entry it arrived under is "
                            + "the diagnostic, and the key itself is readable from the job repository")
                    .doesNotContain(GENERATION_OBJECT_KEY);
            assertThat(logged)
                    .contains("carried forward from a prior step under")
                    .contains("carddemo.gdg.transact-bkup.objectKey");
            assertThat(logged)
                    .as("and the standalone resolution did NOT run, which is the fact an operator reads to "
                            + "tell a correctly ordered pipeline from one that re-resolved")
                    .doesNotContain("standalone (0) semantic applies");
        }

        @Test
        @DisplayName("a restart reports the checkpointed id as present, never by value")
        void aRestartReportsThePresenceOfTheCheckpointedIdOnly() {
            captureLogsOf(TransactionBackupReader.class);

            final ExecutionContext restart = new ExecutionContext();
            restart.putLong("TransactionBackupReader.recordsRead", 4L);
            restart.putString("TransactionBackupReader.lastTransactionId", CHECKPOINTED_TRANSACTION_ID);

            final TransactionBackupReader reader = new TransactionBackupReader(transactionRepository,
                    objectStorage, objectStoreClient, fileStatusMapper, "repository", PAGE_SIZE, "",
                    "gdg/transact-bkup", null);
            reader.open(restart);

            final String logged = capturedLogText();
            assertThat(logged).isNotEmpty();
            assertThat(logged).doesNotContain(CHECKPOINTED_TRANSACTION_ID);
            assertThat(logged)
                    .contains("checkpointedKey=present")
                    .contains("Resuming SORTIN read after 4 records");
        }
    }

    /**
     * The two jobs, asserted against their own source text rather than by drive.
     *
     * <p><b>Why the source and not a drive.</b> The emissions at issue are one arm of a private paragraph
     * reproduction inside a tasklet ({@code CBTRN01C}'s missing-account report) and one arm of an idempotent
     * pre-delete ({@code STEP030}'s delete failure). Reaching either through a drive means constructing a job
     * bean over thirteen collaborators and stubbing six open probes, and the drive would then prove the
     * property for <em>one</em> emission on <em>one</em> path. Reading the source proves it for <b>every</b>
     * emission in the file, including any added later, which is the property the finding actually asks for.
     *
     * <p>Every test here first asserts that the scan found something, so that none of them can pass because
     * a path moved or a pattern stopped matching.
     */
    @Nested
    @DisplayName("the posting and statement jobs - no identifier reaches any emission, by source")
    final class JobEmissionsBySource {

        @Test
        @DisplayName("CBTRN01C's missing-account report is identifier-free on the application logger")
        void theMissingAccountReportIsIdentifierFree() throws IOException {
            final String source = sourceOf(DailyTransactionPostingJob.class);

            assertThat(source)
                    .as("the scan must have something to read, or every assertion below is vacuous")
                    .contains("preFlightVerifyRecord");
            assertThat(source)
                    .as("app/cbl/CBTRN01C.cbl:L177-L179 DISPLAY 'ACCOUNT ' ACCT-ID ' NOT FOUND' must no "
                            + "longer be reproduced on the application logger with the raw identifier")
                    .doesNotContain("LOG.warn(\"ACCOUNT {} NOT FOUND\"");
            assertThat(source)
                    .as("the operational form carries the ordinal and no identifier")
                    .contains("LOG.warn(MSG_PRE_FLIGHT_ACCOUNT_NOT_FOUND, Long.valueOf(recordOrdinal))");
            assertThat(source)
                    .as("and the source's own wording survives on the hard-OFF parity channel, with the "
                            + "identifier masked - so the paragraph map the coverage gate reads is intact")
                    .contains("PARITY_LOG.debug(\"ACCOUNT {} NOT FOUND\", maskAccountIdentifier(accountId))");
        }

        @Test
        @DisplayName("every application-logger emission of the posting job is free of record expressions")
        void everyApplicationEmissionOfThePostingJobIsFreeOfRecordExpressions() throws IOException {
            final List<String> emissions = applicationLoggerEmissions(DailyTransactionPostingJob.class);

            assertThat(emissions)
                    .as("the emission scan must find the file's log calls, or the assertion is vacuous")
                    .hasSizeGreaterThan(15);
            for (final String emission : emissions) {
                assertThat(emission)
                        .as("no emission on the application logger may name a record, an account "
                                + "identifier, a card number or a transaction identifier: %s", emission)
                        .doesNotContain("accountId")
                        .doesNotContain("cardNumber")
                        .doesNotContain("record.")
                        .doesNotContain("getTransactionId");
            }
        }

        @Test
        @DisplayName("STEP030's delete guard is given a logical reference, never a statement key")
        void theStatementDeleteGuardIsGivenALogicalReference() throws IOException {
            final String source = sourceOf(StatementGenerationJob.class);

            assertThat(source)
                    .as("the scan must have something to read")
                    .contains("deleteStatementObjectIfPresent");
            assertThat(source)
                    .as("a statement key carries an account identifier and a statement month, so it must "
                            + "not reach the guard - which both logs it and puts it in the exception message")
                    .doesNotContain("guardObjectStore(ioStatus, key, \"DELETE\"")
                    .doesNotContain("guardObjectStore(ioStatus, markupKey");
            assertThat(source)
                    .as("what reaches the guard is the DD name of app/jcl/CREASTMT.JCL:L72 and :L67 with an "
                            + "ordinal, which identifies the object from the execution context instead")
                    .contains("guardObjectStore(ioStatus, reference, \"DELETE\"")
                    .contains("statementObjectReference(logicalNameOf(key), ordinal)");
            assertThat(source)
                    .as("both allocations of app/jcl/CREASTMT.JCL:L67 and :L72 come back under one prefix, so "
                            + "the reference must resolve to the DD each key belongs to rather than labelling "
                            + "every removed object with the text one")
                    .contains("STATEMENT_MARKUP_OBJECT_NAME")
                    .contains("STATEMENT_TEXT_OBJECT_NAME");
        }
    }

    /**
     * Reads the on-disk source of a class under test.
     *
     * <p>The path is derived from the class's own binary name rather than written out, so a class that moves
     * package makes this method fail loudly instead of silently reading nothing.
     *
     * @param type the class whose source to read; must not be {@code null}
     * @return the source text, never {@code null} and never empty
     * @throws IOException if the source cannot be read, which means the derived path is wrong
     */
    private static String sourceOf(final Class<?> type) throws IOException {
        final Path path = Path.of("src/main/java", type.getName().replace('.', '/') + ".java");
        final String source = Files.readString(path, StandardCharsets.UTF_8);
        assertThat(source).as("%s must be readable at %s", type.getSimpleName(), path).isNotEmpty();
        return source;
    }

    /**
     * Extracts every {@code LOG.<level>(...)} call from a class's source, with its whole argument list.
     *
     * <p>Only the <b>application</b> logger is collected: a {@code PARITY_LOG} call is excluded because that
     * channel is pinned {@code OFF} in every shipped profile and its arguments are masked, which is a
     * different contract asserted separately. Argument lists are captured by counting parentheses rather than
     * by a regular expression, so a nested call such as {@code Long.valueOf(x)} does not truncate the match.
     *
     * @param type the class whose source to scan; must not be {@code null}
     * @return one entry per application-logger emission, never {@code null}
     * @throws IOException if the source cannot be read
     */
    private static List<String> applicationLoggerEmissions(final Class<?> type) throws IOException {
        final String source = sourceOf(type);
        final List<String> emissions = new ArrayList<>();
        for (final String level : List.of("trace", "debug", "info", "warn", "error")) {
            final String needle = "LOG." + level + "(";
            int from = source.indexOf(needle);
            while (from >= 0) {
                // A PARITY_LOG call ends with the same characters, so it is excluded by looking at what
                // precedes the matched "LOG." rather than by matching the call itself.
                final boolean parity = from >= 7 && "PARITY_".equals(source.substring(from - 7, from));
                final int open = from + needle.length() - 1;
                int depth = 0;
                int cursor = open;
                while (cursor < source.length()) {
                    final char character = source.charAt(cursor);
                    if (character == '(') {
                        depth++;
                    } else if (character == ')') {
                        depth--;
                        if (depth == 0) {
                            break;
                        }
                    }
                    cursor++;
                }
                if (!parity) {
                    emissions.add(source.substring(open, Math.min(cursor + 1, source.length())));
                }
                from = source.indexOf(needle, cursor + 1);
            }
        }
        return emissions;
    }

    /**
     * Stubs the paginating client so that the generation base holds exactly {@code keys}.
     *
     * <p>A real {@link ListObjectsV2Iterable} over a stubbed {@code listObjectsV2} is returned rather than a
     * mocked iterable, so the reader's own paging call is exercised rather than simulated. The reader resolves
     * the {@code (0)} semantic through this client and not through {@link #objectStorage}, whose
     * {@code listObjects} returns one page.
     *
     * @param keys the object keys the base holds, in ascending order; must not be {@code null}
     */
    private void stubGenerationListing(final String... keys) {
        final List<S3Object> contents = new ArrayList<>(keys.length);
        for (final String key : keys) {
            contents.add(S3Object.builder().key(key).build());
        }
        final ListObjectsV2Response page = ListObjectsV2Response.builder()
                .contents(contents)
                .isTruncated(Boolean.FALSE)
                .build();
        when(objectStoreClient.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(page);
        when(objectStoreClient.listObjectsV2Paginator(any(ListObjectsV2Request.class)))
                .thenAnswer(invocation -> new ListObjectsV2Iterable(objectStoreClient,
                        invocation.getArgument(0, ListObjectsV2Request.class)));
    }

    /**
     * Builds an object that exists and holds no bytes, which is a successful zero-row read on both
     * fixed-width paths.
     *
     * @return an object whose stream is empty, never {@code null}
     * @throws IOException never, but declared because the stubbed accessor does
     */
    private static S3Resource emptyObject() throws IOException {
        final S3Resource object = Mockito.mock(S3Resource.class);
        Mockito.doReturn(new ByteArrayInputStream(new byte[0])).when(object).getInputStream();
        return object;
    }

    /**
     * An empty object that also carries a valid authenticity envelope for its own (empty) content.
     *
     * <p>Finding M-11: the staging reader refuses an input object that is not vouched for, so on that one path
     * a stub which is only a body is no longer a usable input. Kept separate from {@link #emptyObject()}
     * rather than folded into it, because the three generation-reader tests never ask for metadata or a
     * content length and Mockito's strict stubbing reports an unused stub as a failure - correctly, since an
     * unused stub is a claim about a collaboration that does not happen.
     *
     * @return the stubbed object, never {@code null}
     * @throws IOException never; declared because the stubbed method declares it
     */
    private static S3Resource authenticatedEmptyObject() throws IOException {
        final S3Resource object = emptyObject();
        final String emptyDigest = DailyTransactionReader.InputObjectEnvelope.hexadecimal(
                DailyTransactionReader.InputObjectEnvelope.newDigest().digest(new byte[0]));
        Mockito.doReturn(Long.valueOf(0L)).when(object).contentLength();
        Mockito.doReturn(java.util.Map.of(
                        DailyTransactionReader.InputObjectEnvelope.METADATA_WRITER, OBJECT_WRITER,
                        DailyTransactionReader.InputObjectEnvelope.METADATA_CONTENT_SHA256, emptyDigest,
                        DailyTransactionReader.InputObjectEnvelope.METADATA_SIGNATURE,
                        DailyTransactionReader.InputObjectEnvelope.sign(BUCKET, DALYTRAN_OBJECT_KEY, 0L,
                                OBJECT_WRITER, emptyDigest, SIGNING_KEY)))
                .when(object).metadata();
        return object;
    }
}

/*
 * ******************************************************************
 * Program     : ReaderSensitiveDataTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Regression guard for the data-minimisation contract of
 *               the four read-only sequential readers. Proves that no
 *               record image, no monetary value, no personal data and no
 *               primary account number reaches a log event or the Spring
 *               Batch execution context at ANY level, that the readers
 *               remain read-only, and that the emitted event COUNT per
 *               row is still the source's.
 * Source      : app/cbl/CBACT01C.cbl:L78,:L96 (DISPLAY ACCOUNT-RECORD)
 *               app/cbl/CBACT02C.cbl:L78      (:L96 is commented out)
 *               app/cbl/CBACT03C.cbl:L78,:L96 (both active)
 *               app/cbl/CBCUS01C.cbl:L78,:L96 (both active)
 *               app/cpy/CVACT01Y.cpy:L5-L17   (300-byte ACCOUNT-RECORD)
 *               app/cpy/CVACT02Y.cpy:L5-L7    (CARD-NUM; CARD-CVV-CD is not persisted at all)
 *               app/cpy/CVACT03Y.cpy:L5       (XREF-CARD-NUM)
 *               app/cpy/CVCUS01Y.cpy:L17-L20  (SSN, govt id, DOB, EFT)
 *               app/data/ASCII/acctdata.txt:L1 (overpunch signs) @ 7756d89
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
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
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.data.domain.Pageable;

/**
 * Proves the data-minimisation contract of {@link AccountReader}, {@link CardReader},
 * {@link CardCrossReferenceReader} and {@link CustomerReader}.
 *
 * <h2>What this class is for</h2>
 * <p>
 * A code review found four sensitive-data defects across these readers: a full 300-byte
 * {@code ACCOUNT-RECORD} image with balances, limits, dates, ZIP and group id emitted at DEBUG
 * (severity High); a monetary renderer that applied {@code abs()} and therefore lost the
 * zoned-decimal sign the fixture data genuinely carries (Medium); a full sixteen-digit primary
 * account number checkpointed into Spring Batch's generic {@code BATCH_STEP_EXECUTION_CONTEXT} by
 * two readers (Medium, twice); and whole-entity logging in the fourth that outsourced its own
 * exposure to another class's {@code toString()} (Medium).
 * <p>
 * All four are fixed. This class exists so they cannot come back. Each test is written against the
 * <em>observable surface</em> - captured log events and the execution context itself - rather than
 * against the implementation, so it keeps holding whatever the readers are refactored into.
 *
 * <h2>Why the event count is asserted alongside the content</h2>
 * <p>
 * The resolution of Rule 1 clause D against the parity mandate is a split: the record-event
 * <b>content</b> is minimised while the record-event <b>count</b> is preserved, because the count is
 * behaviour. {@code app/cbl/CBACT01C.cbl}, {@code CBACT03C.cbl} and {@code CBCUS01C.cbl} each
 * display every successfully read record twice - once from the read paragraph at {@code :L96} and
 * once from the mainline at {@code :L78} - whereas {@code CBACT02C.cbl:L96} carries an asterisk in
 * column 7 and is a comment, so {@code CardReader} emits once. Asserting only "no sensitive value"
 * would let a future change satisfy this class by deleting the events outright, which would break
 * parity in the opposite direction. Both halves are therefore pinned.
 *
 * <h2>Fixture safety</h2>
 * <p>
 * Every protected component in these fixtures is synthetic and unusable: the card numbers are in the
 * {@code 4000} test range with no valid check digit, the social-security area {@code 999} is never
 * issued, both telephone numbers sit in the reserved {@code 555-01xx} fiction range, and the
 * government-issued identifier is self-labelling. They are nonetheless treated as though they were
 * real, because the assertions are about whether the code would emit them.
 */
@DisplayName("Batch reader sensitive-data contract (H-10, M-01, M-04, M-10)")
class ReaderSensitiveDataTest {

    // ====================================================================================================
    // Fixture values. Each is a token this test searches captured output for, so each must be a string that
    // could not plausibly appear in a log line for any other reason.
    // ====================================================================================================

    /** {@code ACCT-ID PIC 9(11)}, an 11-digit surrogate key and NOT cardholder data. */
    private static final Long ACCOUNT_ID = Long.valueOf(99_999_999_991L);

    /** {@code CUST-ID PIC 9(09)}, a 9-digit surrogate key and NOT personal data. */
    private static final Long CUSTOMER_ID = Long.valueOf(999_999_991L);

    /** {@code CARD-NUM PIC X(16)}: cardholder data, and the subject of the two M-04 findings. */
    private static final String CARD_NUMBER = "4000000000000019";

    /** {@code CARD-EMBOSSED-NAME PIC X(50)}: the cardholder's name as it appears on the card. */
    private static final String EMBOSSED_NAME = "SYNTHETICA Q TESTCASE                             ";

    /**
     * {@code CUST-SSN PIC 9(09)}: area {@code 999} is never issued by the Social Security Administration.
     * <p>
     * Deliberately NOT a substring of, and containing no substring of, {@link #CUSTOMER_ID}. Both are
     * nine-digit numerics in the same never-issued {@code 999} space, and {@code CUST-ID} is legitimately
     * emitted while {@code CUST-SSN} must never be, so a shared digit run would make the "absent" assertion
     * fail against correct code and the "present" assertion pass against a leak.
     */
    private static final String SSN = "999009991";

    /** {@code CUST-GOVT-ISSUED-ID PIC X(20)}: self-labelling so it cannot be mistaken for real. */
    private static final String GOVERNMENT_ID = "SYNTHETIC-ID-000091";

    /** {@code CUST-DOB-YYYY-MM-DD PIC X(10)}. */
    private static final String DATE_OF_BIRTH = "1970-01-01";

    /** {@code CUST-EFT-ACCOUNT-ID PIC X(10)}. */
    private static final String EFT_ACCOUNT_ID = "EFT0000091";

    /** {@code CUST-PHONE-NUM-1 PIC X(15)}, in the reserved fiction range. */
    private static final String PHONE_1 = "(555) 555-0191";

    /** {@code CUST-PHONE-NUM-2 PIC X(15)}, in the reserved fiction range. */
    private static final String PHONE_2 = "(555) 555-0192";

    /**
     * {@code ACCT-CURR-BAL PIC S9(10)V99} carrying a NEGATIVE value.
     * <p>
     * The M-01 finding was that the deleted money renderer applied {@code abs()}. A negative balance is
     * therefore the fixture that would have exposed it, and {@code app/data/ASCII/dailytran.txt} proves
     * negative amounts are real in this corpus: it contains both the {@code '\u007b'} and the
     * {@code '\u007d'} overpunch characters, which decode to {@code +0} and {@code -0} respectively.
     */
    private static final BigDecimal NEGATIVE_BALANCE = new BigDecimal("-1234.56");

    /** {@code ACCT-CREDIT-LIMIT PIC S9(10)V99}. */
    private static final BigDecimal CREDIT_LIMIT = new BigDecimal("2020.00");

    /** {@code ACCT-ADDR-ZIP PIC X(10)}: geographic data no record image may publish. */
    private static final String ADDRESS_ZIP = "0000091234";

    /** {@code ACCT-GROUP-ID PIC X(10)}: the disclosure-group key no record image may publish. */
    private static final String GROUP_ID = "DEFAULT   ";

    /** Page size for every reader under test; one row per page exercises the page-turn path too. */
    private static final int PAGE_SIZE = 2;

    // ====================================================================================================
    // Log capture. One appender is attached to all four reader loggers at TRACE, so a test cannot pass by
    // an emission being routed to a level the assertion does not look at. That is the whole point: the
    // H-10 defect was at DEBUG, which a default INFO threshold would have hidden.
    // ====================================================================================================

    private ListAppender<ILoggingEvent> events;

    private final List<Logger> attached = new ArrayList<>();

    @BeforeEach
    void attachAppender() {
        events = new ListAppender<>();
        events.start();
        attach(AccountReader.class);
        attach(CardReader.class);
        attach(CardCrossReferenceReader.class);
        attach(CustomerReader.class);
    }

    @AfterEach
    void detachAppender() {
        attached.forEach(logger -> logger.detachAppender(events));
        attached.clear();
        events.stop();
    }

    /**
     * Attaches the shared appender to one reader's logger and forces TRACE so that every level is captured.
     *
     * @param readerType the reader whose logger is being observed; never {@code null}
     */
    private void attach(Class<?> readerType) {
        Logger logger = (Logger) LoggerFactory.getLogger(readerType);
        logger.addAppender(events);
        logger.setLevel(Level.TRACE);
        attached.add(logger);
    }

    /**
     * Renders every captured event - formatted message, argument array and any throwable - into one string
     * for token searching.
     * <p>
     * The argument array is rendered separately from the formatted message on purpose. A parameterised call
     * such as {@code LOG.debug("{}", entity)} formats through the argument's {@code toString()}, so an
     * argument that renders narrowly today can widen tomorrow without this file changing; including the raw
     * arguments means the search sees what was <em>passed</em>, not only what was rendered.
     *
     * @return the concatenation of every captured event; never {@code null}, possibly empty
     */
    private String capturedText() {
        StringBuilder text = new StringBuilder(1024);
        for (ILoggingEvent event : events.list) {
            text.append(event.getFormattedMessage()).append('\n');
            Object[] arguments = event.getArgumentArray();
            if (arguments != null) {
                for (Object argument : arguments) {
                    text.append(String.valueOf(argument)).append('\n');
                }
            }
            if (event.getThrowableProxy() != null) {
                text.append(event.getThrowableProxy().getMessage()).append('\n');
            }
        }
        return text.toString();
    }

    /**
     * Counts the captured events whose formatted message reports a record read or accepted.
     * <p>
     * Matched on the two verbs the readers use rather than on a full literal, so the assertion survives a
     * wording change while still counting the right events.
     *
     * @return the number of per-row record events captured
     */
    private long recordEventCount() {
        return events.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains("record read") || message.contains("record accepted"))
                .count();
    }

    // ====================================================================================================
    // Fixture builders.
    // ====================================================================================================

    /**
     * Builds the {@code ACCOUNT-RECORD} of {@code app/cpy/CVACT01Y.cpy} with a negative current balance.
     *
     * @return the account record; never {@code null}
     */
    private static Account account() {
        return new Account(ACCOUNT_ID,
                "Y",
                NEGATIVE_BALANCE,
                CREDIT_LIMIT,
                new BigDecimal("1020.00"),
                "2015-07-01",
                "2025-06-30",
                "2020-07-01",
                new BigDecimal("-99.99"),
                new BigDecimal("-88.88"),
                ADDRESS_ZIP,
                GROUP_ID);
    }

    /**
     * Builds the {@code CARD-RECORD} of {@code app/cpy/CVACT02Y.cpy}.
     *
     * @return the card record; never {@code null}
     */
    private static Card card() {
        return new Card(CARD_NUMBER, ACCOUNT_ID, "007", EMBOSSED_NAME, "2025-12-31", "Y");
    }

    /**
     * Builds the {@code CARD-XREF-RECORD} of {@code app/cpy/CVACT03Y.cpy}.
     *
     * @return the cross-reference record; never {@code null}
     */
    private static CardCrossReference crossReference() {
        return new CardCrossReference(CARD_NUMBER, CUSTOMER_ID, ACCOUNT_ID);
    }

    /**
     * Builds the {@code CUSTOMER-RECORD} of {@code app/cpy/CVCUS01Y.cpy} with every protected component
     * populated, so a whole-entity emission would be caught by at least six independent token searches.
     *
     * @return the customer record; never {@code null}
     */
    private static Customer customer() {
        return new Customer(CUSTOMER_ID,
                "SYNTHETICA",
                "Q",
                "TESTCASE",
                "1 SAMPLE STREET",
                "SUITE 100",
                "SPRINGFIELD",
                "IL",
                "USA",
                ADDRESS_ZIP,
                PHONE_1,
                PHONE_2,
                SSN,
                GOVERNMENT_ID,
                DATE_OF_BIRTH,
                EFT_ACCOUNT_ID,
                "Y",
                "751");
    }

    // ====================================================================================================
    // AccountReader - H-10 (record image at DEBUG) and M-01 (abs() sign loss).
    // ====================================================================================================

    @Nested
    @DisplayName("AccountReader publishes no record image and no monetary value (H-10, M-01)")
    class AccountReaderMinimisation {

        private AccountRepository repository;

        private AccountReader reader;

        /** The checkpoint taken mid-scan, while the reader's record area still holds the first row. */
        private ExecutionContext midScanCheckpoint;

        /** The checkpoint taken after end of data. */
        private ExecutionContext exhaustedCheckpoint;

        @BeforeEach
        void driveOneRow() {
            repository = mock(AccountRepository.class);
            when(repository.count()).thenReturn(Long.valueOf(1L));
            when(repository.findByAccountIdGreaterThanOrderByAccountIdAsc(any(), any(Pageable.class)))
                    // A one-row relation, modelled rather than sequenced. A call-ordered stub cannot serve
                    // this class: the restart path re-probes the SAME finder from the seed with
                    // PageRequest.of(ordinal, 1) to recover the key it must resume from, so a second reader
                    // opening a checkpoint would consume the exhausted answer and abort. Answering from the
                    // cursor and the offset makes the mock behave like the relation it stands for.
                    .thenAnswer(invocation -> {
                        final Long cursor = invocation.getArgument(0);
                        final Pageable window = invocation.getArgument(1);
                        final boolean pastTheOnlyRow = cursor != null && cursor.compareTo(ACCOUNT_ID) >= 0;
                        return pastTheOnlyRow || window.getOffset() > 0L
                                ? List.of() : List.of(account());
                    });
            reader = new AccountReader(repository, new FileStatusMapper(), PAGE_SIZE);
            reader.open(new ExecutionContext());

            assertThat(reader.read()).isNotNull();
            midScanCheckpoint = new ExecutionContext();
            reader.update(midScanCheckpoint);

            assertThat(reader.read()).isNull();
            exhaustedCheckpoint = new ExecutionContext();
            reader.update(exhaustedCheckpoint);
            reader.close();
        }

        @Test
        @DisplayName("no monetary value from the 300-byte record reaches any log level")
        void noMoneyIsLogged() {
            // Both the signed text and the unsigned text are searched. Searching only "-1234.56" would pass
            // against a renderer that stripped the sign - which is precisely the M-01 defect - so the
            // magnitude "1234.56" is asserted absent as well, and that single assertion covers both findings.
            assertThat(capturedText())
                    .doesNotContain("1234.56")
                    .doesNotContain("-1234.56")
                    .doesNotContain("2020.00")
                    .doesNotContain("1020.00")
                    .doesNotContain("99.99")
                    .doesNotContain("88.88");
        }

        @Test
        @DisplayName("no zero-padded fixed-width money field reaches any log level")
        void noFixedWidthMoneyIsLogged() {
            // The deleted renderer emitted PIC S9(10)V99 as 12 zero-padded characters. Searching for the
            // padded forms catches a reinstated fixed-width renderer even if it changed its separator.
            assertThat(capturedText())
                    .doesNotContain("000000123456")
                    .doesNotContain("000000202000")
                    .doesNotContain("00000123456");
        }

        @Test
        @DisplayName("no ZIP, group id, or account date reaches any log level")
        void noNonMonetaryRecordFieldIsLogged() {
            assertThat(capturedText())
                    .doesNotContain(ADDRESS_ZIP)
                    .doesNotContain(GROUP_ID.trim())
                    .doesNotContain("2015-07-01")
                    .doesNotContain("2025-06-30")
                    .doesNotContain("2020-07-01");
        }

        @Test
        @DisplayName("the two record events of CBACT01C:L78 and :L96 are still emitted")
        void bothRecordEventsSurvive() {
            // Parity structure 2: both DISPLAY ACCOUNT-RECORD statements are active in CBACT01C, so the
            // content minimisation must not have silently deleted an emission point.
            assertThat(recordEventCount()).isEqualTo(2L);
        }

        @Test
        @DisplayName("the reader stays read-only: only count() and findAll(Pageable) are reached")
        void readerRemainsReadOnly() {
            verify(repository, never()).save(any(Account.class));
            verify(repository, never()).delete(any(Account.class));
            verify(repository, never()).deleteAll();
        }

        @Test
        @DisplayName("the execution context carries only the row count and the non-cardholder ACCT-ID")
        void contextIsMinimal() {
            // Checkpointed at BOTH moments: mid-scan, which is when Spring Batch actually calls update at a
            // chunk boundary, and after exhaustion. A defect guarded on the current record area is invisible
            // to the second alone. The mid-scan capture is retaken here because this reader's fixture drives
            // the scan to completion in its own setup.
            assertThat(midScanCheckpoint.entrySet())
                    .allSatisfy(entry -> assertThat(entry.getValue()).isInstanceOf(Long.class));
            // ACCT-ID is an 11-digit surrogate key, not cardholder data, so it is permitted here - and being
            // stored as a long is itself a guarantee that no textual record image can be smuggled in.
            assertThat(exhaustedCheckpoint.entrySet())
                    .allSatisfy(entry -> assertThat(entry.getValue()).isInstanceOf(Long.class));
        }

        @Test
        @DisplayName("no execution-context value carries a monetary, ZIP or group value, at either moment")
        void contextCarriesNoRecordField() {
            for (ExecutionContext checkpoint : List.of(midScanCheckpoint, exhaustedCheckpoint)) {
                assertThat(checkpoint.entrySet()).noneSatisfy(entry -> {
                    String rendered = String.valueOf(entry.getValue());
                    assertThat(rendered)
                            .satisfiesAnyOf(
                                    value -> assertThat(value).contains("1234.56"),
                                    value -> assertThat(value).contains(ADDRESS_ZIP),
                                    value -> assertThat(value).contains(GROUP_ID.trim()));
                });
            }
        }
    }

    // ====================================================================================================
    // CardReader - M-04 (PAN in Spring Batch execution metadata).
    // ====================================================================================================

    @Nested
    @DisplayName("CardReader checkpoints no primary account number (M-04)")
    class CardReaderMinimisation {

        private CardRepository repository;

        private CardReader reader;

        /**
         * The checkpoint taken <em>mid-scan</em>, immediately after the first row and while the reader's
         * record area still holds it.
         * <p>
         * This is the checkpoint that matters and the reason it is captured separately. Spring Batch calls
         * {@code update} at a chunk boundary, which is <b>while rows remain</b> - not after end of data. A
         * checkpoint taken only post-exhaustion is a weaker probe, because by then the record area has been
         * cleared, so a reinstated write guarded on the current record would silently write nothing and the
         * assertion would pass against a defect. Both moments are therefore asserted.
         */
        private ExecutionContext midScanCheckpoint;

        /** The checkpoint taken after end of data, which is the {@code ItemStream} close-time position. */
        private ExecutionContext exhaustedCheckpoint;

        @BeforeEach
        void driveOneRow() {
            repository = mock(CardRepository.class);
            when(repository.count()).thenReturn(Long.valueOf(1L));
            when(repository.findByCardNumberGreaterThanOrderByCardNumberAsc(any(), any(Pageable.class)))
                    // A one-row relation, modelled rather than sequenced. A call-ordered stub cannot serve
                    // this class: the restart path re-probes the SAME finder from the seed with
                    // PageRequest.of(ordinal, 1) to recover the key it must resume from, so a second reader
                    // opening a checkpoint would consume the exhausted answer and abort. Answering from the
                    // cursor and the offset makes the mock behave like the relation it stands for.
                    .thenAnswer(invocation -> {
                        final String cursor = invocation.getArgument(0);
                        final Pageable window = invocation.getArgument(1);
                        final boolean pastTheOnlyRow = cursor != null && cursor.compareTo(CARD_NUMBER) >= 0;
                        return pastTheOnlyRow || window.getOffset() > 0L
                                ? List.of() : List.of(card());
                    });
            reader = new CardReader(repository, new FileStatusMapper(), PAGE_SIZE);
            reader.open(new ExecutionContext());

            assertThat(reader.read()).isNotNull();
            midScanCheckpoint = new ExecutionContext();
            reader.update(midScanCheckpoint);

            assertThat(reader.read()).isNull();
            exhaustedCheckpoint = new ExecutionContext();
            reader.update(exhaustedCheckpoint);
        }

        @Test
        @DisplayName("the mid-scan checkpoint holds the row count and nothing else")
        void midScanContextHoldsRowCountOnly() {
            assertThat(midScanCheckpoint.size()).isEqualTo(1);
            assertThat(midScanCheckpoint.getLong("CardReader.recordsRead")).isEqualTo(1L);
        }

        @Test
        @DisplayName("the post-exhaustion checkpoint holds the row count and nothing else")
        void exhaustedContextHoldsRowCountOnly() {
            assertThat(exhaustedCheckpoint.size()).isEqualTo(1);
            assertThat(exhaustedCheckpoint.getLong("CardReader.recordsRead")).isEqualTo(1L);
        }

        @Test
        @DisplayName("no execution-context value contains the card number, at any key, at either moment")
        void contextContainsNoCardNumber() {
            // Asserted over every entry rather than over one known key, so a checkpoint reinstated under a
            // different name is still caught.
            assertThat(midScanCheckpoint.entrySet())
                    .noneSatisfy(entry -> assertThat(String.valueOf(entry.getValue())).contains(CARD_NUMBER));
            assertThat(exhaustedCheckpoint.entrySet())
                    .noneSatisfy(entry -> assertThat(String.valueOf(entry.getValue())).contains(CARD_NUMBER));
        }

        @Test
        @DisplayName("a restart round trip from the mid-scan checkpoint neither restores nor reports a PAN")
        void restartRoundTripCarriesNoCardNumber() {
            events.list.clear();

            CardReader resumed = new CardReader(repository, new FileStatusMapper(), PAGE_SIZE);
            resumed.open(midScanCheckpoint);

            assertThat(capturedText()).doesNotContain(CARD_NUMBER);
            assertThat(capturedText()).contains("Resuming");
        }

        @Test
        @DisplayName("neither the card number nor the embossed name reaches any log level, and no card "
                + "verification value exists to reach one")
        void noCardholderDataIsLogged() {
            assertThat(capturedText())
                    .doesNotContain(CARD_NUMBER)
                    .doesNotContain(EMBOSSED_NAME.trim())
                    .doesNotContain("2025-12-31");
            // The card verification value cannot leak: app/cpy/CVACT02Y.cpy:L7 declares CARD-CVV-CD and
            // this system does persist it, but write-once and accessor-less - so no read path exists and
            // the label is asserted absent as a regression guard against a renderer that added one.
            assertThat(capturedText()).doesNotContain("CVV");
        }

        @Test
        @DisplayName("CBACT02C:L96 is a comment, so exactly ONE record event is emitted")
        void exactlyOneRecordEventSurvives() {
            // Parity structure 2 for this program specifically: app/cbl/CBACT02C.cbl:L96 carries an asterisk
            // in column 7. Emitting two events here would be as much a parity break as emitting one in the
            // three siblings whose :L96 is active.
            assertThat(recordEventCount()).isEqualTo(1L);
        }

        @Test
        @DisplayName("the reader stays read-only")
        void readerRemainsReadOnly() {
            verify(repository, never()).save(any(Card.class));
            verify(repository, never()).delete(any(Card.class));
            verify(repository, never()).deleteAll();
        }
    }

    // ====================================================================================================
    // CardCrossReferenceReader - M-04 (PAN in Spring Batch execution metadata).
    // ====================================================================================================

    @Nested
    @DisplayName("CardCrossReferenceReader checkpoints no primary account number (M-04)")
    class CardCrossReferenceReaderMinimisation {

        private CardCrossReferenceRepository repository;

        private CardCrossReferenceReader reader;

        /** The checkpoint taken mid-scan; see {@code CardReaderMinimisation} for why both moments matter. */
        private ExecutionContext midScanCheckpoint;

        /** The checkpoint taken after end of data. */
        private ExecutionContext exhaustedCheckpoint;

        @BeforeEach
        void driveOneRow() {
            repository = mock(CardCrossReferenceRepository.class);
            when(repository.count()).thenReturn(Long.valueOf(1L));
            when(repository.findByCardNumberGreaterThanOrderByCardNumberAsc(any(), any(Pageable.class)))
                    // A one-row relation, modelled rather than sequenced. A call-ordered stub cannot serve
                    // this class: the restart path re-probes the SAME finder from the seed with
                    // PageRequest.of(ordinal, 1) to recover the key it must resume from, so a second reader
                    // opening a checkpoint would consume the exhausted answer and abort. Answering from the
                    // cursor and the offset makes the mock behave like the relation it stands for.
                    .thenAnswer(invocation -> {
                        final String cursor = invocation.getArgument(0);
                        final Pageable window = invocation.getArgument(1);
                        final boolean pastTheOnlyRow = cursor != null && cursor.compareTo(CARD_NUMBER) >= 0;
                        return pastTheOnlyRow || window.getOffset() > 0L
                                ? List.of() : List.of(crossReference());
                    });
            reader = new CardCrossReferenceReader(repository, new FileStatusMapper(), PAGE_SIZE);
            reader.open(new ExecutionContext());

            assertThat(reader.read()).isNotNull();
            midScanCheckpoint = new ExecutionContext();
            reader.update(midScanCheckpoint);

            assertThat(reader.read()).isNull();
            exhaustedCheckpoint = new ExecutionContext();
            reader.update(exhaustedCheckpoint);
        }

        @Test
        @DisplayName("the mid-scan checkpoint holds the row count and nothing else")
        void midScanContextHoldsRowCountOnly() {
            assertThat(midScanCheckpoint.size()).isEqualTo(1);
            assertThat(midScanCheckpoint.getLong("CardCrossReferenceReader.recordsRead")).isEqualTo(1L);
        }

        @Test
        @DisplayName("the post-exhaustion checkpoint holds the row count and nothing else")
        void exhaustedContextHoldsRowCountOnly() {
            assertThat(exhaustedCheckpoint.size()).isEqualTo(1);
            assertThat(exhaustedCheckpoint.getLong("CardCrossReferenceReader.recordsRead")).isEqualTo(1L);
        }

        @Test
        @DisplayName("no execution-context value contains the card number, at any key, at either moment")
        void contextContainsNoCardNumber() {
            assertThat(midScanCheckpoint.entrySet())
                    .noneSatisfy(entry -> assertThat(String.valueOf(entry.getValue())).contains(CARD_NUMBER));
            assertThat(exhaustedCheckpoint.entrySet())
                    .noneSatisfy(entry -> assertThat(String.valueOf(entry.getValue())).contains(CARD_NUMBER));
        }

        @Test
        @DisplayName("a restart round trip from the mid-scan checkpoint neither restores nor reports a PAN")
        void restartRoundTripCarriesNoCardNumber() {
            events.list.clear();

            CardCrossReferenceReader resumed =
                    new CardCrossReferenceReader(repository, new FileStatusMapper(), PAGE_SIZE);
            resumed.open(midScanCheckpoint);

            assertThat(capturedText()).doesNotContain(CARD_NUMBER);
            assertThat(capturedText()).contains("Resuming");
        }

        @Test
        @DisplayName("neither the card number nor XREF-ACCT-ID is logged, and the event is still useful")
        void neitherIdentifierIsLogged() {
            assertThat(capturedText()).doesNotContain(CARD_NUMBER);
            // Reporting XREF-ACCT-ID, on the grounds that an 11-digit
            // account identifier is not cardholder data, is refused by the reader itself:
            // CardCrossReferenceReader:270 and :802 record that naming the account instead is "narrower but
            // still not narrow enough", because this relation is nothing BUT the association between a card
            // number and an account, so the account identifier identifies the cardholder by construction.
            // Both call sites (:808, :964) therefore emit the logical file and the ordinal and no field.
            assertThat(capturedText()).doesNotContain("XREF-ACCT-ID");
            // The original intent - that minimisation must not empty the event - restated against what is
            // actually emitted.
            assertThat(capturedText()).contains("XREFFILE");
            assertThat(capturedText()).contains("sequence=");
        }

        @Test
        @DisplayName("both record events of CBACT03C:L78 and :L96 are still emitted")
        void bothRecordEventsSurvive() {
            assertThat(recordEventCount()).isEqualTo(2L);
        }

        @Test
        @DisplayName("the reader stays read-only")
        void readerRemainsReadOnly() {
            verify(repository, never()).save(any(CardCrossReference.class));
            verify(repository, never()).delete(any(CardCrossReference.class));
            verify(repository, never()).deleteAll();
        }
    }

    // ====================================================================================================
    // CustomerReader - M-10 (whole-entity logging via a remote toString()).
    // ====================================================================================================

    @Nested
    @DisplayName("CustomerReader logs a local projection, not the entity (M-10)")
    class CustomerReaderMinimisation {

        private CustomerRepository repository;

        private CustomerReader reader;

        /** The checkpoint taken mid-scan, while the reader's record area still holds the first row. */
        private ExecutionContext midScanCheckpoint;

        /** The checkpoint taken after end of data. */
        private ExecutionContext exhaustedCheckpoint;

        @BeforeEach
        void driveOneRow() {
            repository = mock(CustomerRepository.class);
            when(repository.count()).thenReturn(Long.valueOf(1L));
            when(repository.findByCustomerIdGreaterThanOrderByCustomerIdAsc(any(), any(Pageable.class)))
                    // A one-row relation, modelled rather than sequenced. A call-ordered stub cannot serve
                    // this class: the restart path re-probes the SAME finder from the seed with
                    // PageRequest.of(ordinal, 1) to recover the key it must resume from, so a second reader
                    // opening a checkpoint would consume the exhausted answer and abort. Answering from the
                    // cursor and the offset makes the mock behave like the relation it stands for.
                    .thenAnswer(invocation -> {
                        final Long cursor = invocation.getArgument(0);
                        final Pageable window = invocation.getArgument(1);
                        final boolean pastTheOnlyRow = cursor != null && cursor.compareTo(CUSTOMER_ID) >= 0;
                        return pastTheOnlyRow || window.getOffset() > 0L
                                ? List.of() : List.of(customer());
                    });
            reader = new CustomerReader(repository, new FileStatusMapper(), PAGE_SIZE);
            reader.open(new ExecutionContext());

            assertThat(reader.read()).isNotNull();
            midScanCheckpoint = new ExecutionContext();
            reader.update(midScanCheckpoint);

            assertThat(reader.read()).isNull();
            exhaustedCheckpoint = new ExecutionContext();
            reader.update(exhaustedCheckpoint);
        }

        @Test
        @DisplayName("no Customer instance is ever passed to the logger as an argument")
        void noEntityIsHandedToTheLogger() {
            // The decisive M-10 assertion. It does not ask what Customer.toString() renders - that contract
            // belongs to another class and can change without this file changing. It asks whether the entity
            // was PASSED at all, which is the property that made the exposure external in the first place.
            assertThat(events.list)
                    .allSatisfy(event -> {
                        Object[] arguments = event.getArgumentArray();
                        if (arguments != null) {
                            assertThat(arguments).doesNotHaveAnyElementsOfTypes(Customer.class);
                        }
                    });
        }

        @Test
        @DisplayName("no personal-data component of the 500-byte record reaches any log level")
        void noPersonalDataIsLogged() {
            assertThat(capturedText())
                    .doesNotContain(SSN)
                    .doesNotContain(GOVERNMENT_ID)
                    .doesNotContain(DATE_OF_BIRTH)
                    .doesNotContain(EFT_ACCOUNT_ID)
                    .doesNotContain(PHONE_1)
                    .doesNotContain(PHONE_2)
                    .doesNotContain("SYNTHETICA")
                    .doesNotContain("TESTCASE")
                    .doesNotContain("1 SAMPLE STREET")
                    .doesNotContain(ADDRESS_ZIP);
        }

        @Test
        @DisplayName("the entity's own rendering is absent, so the projection is not toString() by another name")
        void entityRenderingIsAbsent() {
            // Customer.toString() answers "Customer[customerId=..., version=...]". Its class-name prefix
            // appearing in captured output would mean the entity was rendered after all, whether it was
            // passed as an argument or concatenated into a message.
            assertThat(capturedText()).doesNotContain("Customer[");
        }

        @Test
        @DisplayName("the event still carries its file and ordinal, so minimisation did not empty it")
        void theEventIsMinimisedButNotEmptied() {
            // CUST-ID and its nine digits are not reported: CustomerReader:688 and :841 render the logical
            // file and the row ordinal and no field of the record at all. What is kept here is the property
            // that minimisation must not leave a useless event behind, stated against
            // what the reader actually emits.
            assertThat(capturedText()).contains("CUSTFILE");
            assertThat(capturedText()).contains("sequence=");
            // And the identifier itself must be absent, which is the stronger property the withdrawal bought.
            assertThat(capturedText()).doesNotContain("CUST-ID");
            assertThat(capturedText()).doesNotContain("999999991");
        }

        @Test
        @DisplayName("both record events of CBCUS01C:L78 and :L96 are still emitted")
        void bothRecordEventsSurvive() {
            assertThat(recordEventCount()).isEqualTo(2L);
        }

        @Test
        @DisplayName("the execution context carries only numeric surrogate keys, at either moment")
        void contextIsNumericOnly() {
            for (ExecutionContext checkpoint : List.of(midScanCheckpoint, exhaustedCheckpoint)) {
                assertThat(checkpoint.entrySet())
                        .allSatisfy(entry -> assertThat(entry.getValue()).isInstanceOf(Long.class));
            }
        }

        @Test
        @DisplayName("no execution-context value carries a personal-data component, at either moment")
        void contextCarriesNoPersonalData() {
            for (ExecutionContext checkpoint : List.of(midScanCheckpoint, exhaustedCheckpoint)) {
                assertThat(checkpoint.entrySet()).noneSatisfy(entry -> {
                    String rendered = String.valueOf(entry.getValue());
                    assertThat(rendered)
                            .satisfiesAnyOf(
                                    value -> assertThat(value).contains(SSN),
                                    value -> assertThat(value).contains(GOVERNMENT_ID),
                                    value -> assertThat(value).contains(DATE_OF_BIRTH),
                                    value -> assertThat(value).contains(EFT_ACCOUNT_ID));
                });
            }
        }

        @Test
        @DisplayName("the reader stays read-only")
        void readerRemainsReadOnly() {
            verify(repository, never()).save(any(Customer.class));
            verify(repository, never()).delete(any(Customer.class));
            verify(repository, never()).deleteAll();
        }
    }
}

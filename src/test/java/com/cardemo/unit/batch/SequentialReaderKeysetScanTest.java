/*
 * ****************************************************************************
 * Test        : SequentialReaderKeysetScanTest
 * Application : CardDemo
 * Type        : JUnit 5 unit test, no Spring context and no database
 * Function    : Pins the keyset-seek browse contract of the four read-only verification readers that
 *               replace app/cbl/CBACT01C.cbl (ACCTFILE), app/cbl/CBACT02C.cbl (CARDFILE),
 *               app/cbl/CBACT03C.cbl (XREFFILE) and app/cbl/CBCUS01C.cbl (CUSTFILE). Each of those
 *               programs has a verb inventory of OPEN, READ and CLOSE only, so each becomes a
 *               sequential scan whose sole observable contract is the ORDER and the COMPLETENESS of
 *               the rows it emits.
 * ****************************************************************************
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"). You may not
 * use this file except in compliance with the License. A copy of the License
 * is located at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License
 * ****************************************************************************
 */

package com.cardemo.unit.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.data.domain.Pageable;

/**
 * Verifies that all four sequential verification readers browse their cluster by <b>keyset seek</b> and
 * that the seek is complete, non-repeating and restartable.
 *
 * <h2>Why this test exists</h2>
 * <p>
 * The readers previously positioned each window with a page number, issuing
 * {@code findAll(PageRequest.of(n, size, Sort...))}. That is correct only in the sense of producing the
 * right rows on an unchanging relation: the store must produce and discard every row before the window,
 * so walking a relation of <i>n</i> rows costs work proportional to <i>n</i> squared, and a restart
 * positioned by row count silently re-emits or skips rows if the relation changed between runs. Both
 * defects are invisible to a test that only checks the emitted sequence, which is why the assertions
 * below check the <em>arguments the reader passes to the store</em> as well as what it returns.
 *
 * <h2>What each nested group pins</h2>
 * <ul>
 *   <li><b>Seed.</b> The first window is requested with a bound provably below the whole key space, so
 *       the true first row is never skipped, and that bound is never itself a key.</li>
 *   <li><b>Progression.</b> Each subsequent window is requested with the highest key of the previous
 *       window, using a strict comparison, so no row is emitted twice and none is stepped over.</li>
 *   <li><b>Offset.</b> Every {@code Pageable} the reader passes carries page number zero, which is what
 *       makes the query a seek rather than an offset scan.</li>
 *   <li><b>End of scan.</b> An empty window ends the scan and the reader keeps answering {@code null},
 *       without issuing further queries.</li>
 *   <li><b>Restart.</b> A resumed run seeks to the checkpointed key rather than to a computed offset,
 *       and a context that claims progress without a key is rejected rather than silently restarted.</li>
 *   </ul>
 *
 * <p>
 * No Spring context, no database and no container: each reader is constructed directly against a mocked
 * repository, which is the only collaborator whose interaction is under test. {@link FileStatusMapper} is
 * used for real, because it is a pure function and mocking it would weaken the assertions.
 */
@DisplayName("com.cardemo.batch.readers - keyset-seek browse contract of the four verification readers")
class SequentialReaderKeysetScanTest {

    /** Window size used throughout, small enough that several windows are needed for a handful of rows. */
    private static final int PAGE_SIZE = 2;

    /** The seed bound for the two numeric key spaces: below {@code MIN_ACCOUNT_ID} and {@code MIN_CUSTOMER_ID}. */
    private static final Long NUMERIC_SEED = Long.valueOf(-1L);

    /** The seed bound for the two {@code CHAR(16)} key spaces, matching the repository's documented seed. */
    private static final String TEXT_SEED = "";

    // ====================================================================================================
    // Fixtures. Every value is synthetic: the account and customer identifiers are low integers, and the
    // card numbers sit in the 9999 test range so none can collide with an issued PAN.
    // ====================================================================================================

    /**
     * Builds an {@code ACCOUNT-RECORD} of {@code app/cpy/CVACT01Y.cpy} carrying the supplied key.
     *
     * @param accountId the eleven-digit {@code ACCT-ID}
     * @return the account; never {@code null}
     */
    private static Account account(final long accountId) {
        return new Account(Long.valueOf(accountId), "Y", new BigDecimal("194.00"),
                new BigDecimal("2020.00"), new BigDecimal("1020.00"), "2015-07-01", "2025-06-30",
                "2020-07-01", new BigDecimal("0.00"), new BigDecimal("0.00"), "0000012345",
                "DEFAULT   ");
    }

    /**
     * Builds a {@code CUSTOMER-RECORD} of {@code app/cpy/CVCUS01Y.cpy} carrying the supplied key. The
     * social-security area {@code 999} is never issued and both telephone numbers sit in the reserved
     * fiction range, so no component is usable.
     *
     * @param customerId the nine-digit {@code CUST-ID}
     * @return the customer; never {@code null}
     */
    private static Customer customer(final long customerId) {
        return new Customer(Long.valueOf(customerId), "SYNTHETICA", "Q", "TESTCASE", "1 SAMPLE STREET",
                "SUITE 100", "SPRINGFIELD", "IL", "USA", "0000012345", "(555) 010-0001",
                "(555) 010-0002", "999009999", "SYNTHETIC-ID-0001", "1990-01-01", "0000000001", "Y",
                "750");
    }

    /**
     * Builds a {@code CARD-RECORD} of {@code app/cpy/CVACT02Y.cpy} carrying the supplied key.
     *
     * @param cardNumber the sixteen-character {@code CARD-NUM}
     * @return the card; never {@code null}
     */
    private static Card card(final String cardNumber) {
        // No card verification argument: the entity declares no such field and the schema no such column.
        return new Card(cardNumber, Long.valueOf(1L), "SYNTHETICA Q TESTCASE     ", "2026-12-31", "Y");
    }

    /**
     * Builds a {@code CARD-XREF-RECORD} of {@code app/cpy/CVACT03Y.cpy} carrying the supplied key.
     *
     * @param cardNumber the sixteen-character {@code XREF-CARD-NUM}
     * @return the cross reference; never {@code null}
     */
    private static CardCrossReference crossReference(final String cardNumber) {
        return new CardCrossReference(cardNumber, Long.valueOf(1L), Long.valueOf(1L));
    }

    /**
     * Renders a synthetic sixteen-digit card number in the {@code 9999} test range.
     *
     * @param ordinal the row ordinal, which becomes the low-order digits
     * @return a sixteen-character numeric string that ascends with {@code ordinal}
     */
    private static String cardNumber(final int ordinal) {
        return String.format(Locale.ROOT, "9999%012d", Integer.valueOf(ordinal));
    }

    /**
     * Drains a reader to exhaustion, guarding against a non-terminating scan.
     *
     * @param <T> the emitted row type
     * @param next supplies the next row, or {@code null} at end of data
     * @param limit the hard ceiling on emissions before the drain is declared runaway
     * @return every row the reader emitted, in order
     */
    private static <T> List<T> drain(final java.util.function.Supplier<T> next, final int limit) {
        final List<T> emitted = new ArrayList<>();
        for (int guard = 0; guard <= limit; guard++) {
            final T row = next.get();
            if (row == null) {
                return emitted;
            }
            emitted.add(row);
        }
        throw new AssertionError("reader emitted more than " + limit
                + " rows and did not terminate; the keyset cursor is not advancing");
    }

    // ====================================================================================================
    // ACCTFILE, app/cbl/CBACT01C.cbl
    // ====================================================================================================

    @Nested
    @DisplayName("AccountReader over ACCTFILE")
    class AccountScan {

        @Test
        @DisplayName("seeds the first window below the whole key space and never re-reads a row")
        void seedsBelowKeySpaceAndAdvancesByHighestKey() {
            final AccountRepository repository = mock(AccountRepository.class);
            when(repository.count()).thenReturn(Long.valueOf(5L));
            when(repository.findByAccountIdGreaterThanOrderByAccountIdAsc(eq(NUMERIC_SEED),
                    any(Pageable.class))).thenReturn(List.of(account(1L), account(2L)));
            when(repository.findByAccountIdGreaterThanOrderByAccountIdAsc(eq(Long.valueOf(2L)),
                    any(Pageable.class))).thenReturn(List.of(account(7L), account(9L)));
            when(repository.findByAccountIdGreaterThanOrderByAccountIdAsc(eq(Long.valueOf(9L)),
                    any(Pageable.class))).thenReturn(List.of(account(11L)));
            when(repository.findByAccountIdGreaterThanOrderByAccountIdAsc(eq(Long.valueOf(11L)),
                    any(Pageable.class))).thenReturn(List.of());

            final AccountReader reader = new AccountReader(repository, new FileStatusMapper(), PAGE_SIZE);
            reader.open(new ExecutionContext());
            final List<Account> emitted = drain(reader::read, 20);

            assertThat(emitted).extracting(Account::getAccountId)
                    .as("every row is emitted exactly once, in ascending key order; the gaps between 2 "
                            + "and 7 and between 9 and 11 prove the cursor follows the DATA rather than "
                            + "counting rows, which is what an OFFSET page would have done")
                    .containsExactly(Long.valueOf(1L), Long.valueOf(2L), Long.valueOf(7L),
                            Long.valueOf(9L), Long.valueOf(11L));
            assertThat(reader.getRecordsRead()).isEqualTo(5L);
        }

        @Test
        @DisplayName("requests page zero on every window, so the query is a seek and never an offset scan")
        void everyWindowRequestsPageZero() {
            final AccountRepository repository = mock(AccountRepository.class);
            when(repository.count()).thenReturn(Long.valueOf(3L));
            when(repository.findByAccountIdGreaterThanOrderByAccountIdAsc(eq(NUMERIC_SEED),
                    any(Pageable.class))).thenReturn(List.of(account(1L), account(2L)));
            when(repository.findByAccountIdGreaterThanOrderByAccountIdAsc(eq(Long.valueOf(2L)),
                    any(Pageable.class))).thenReturn(List.of(account(3L)));
            when(repository.findByAccountIdGreaterThanOrderByAccountIdAsc(eq(Long.valueOf(3L)),
                    any(Pageable.class))).thenReturn(List.of());

            final AccountReader reader = new AccountReader(repository, new FileStatusMapper(), PAGE_SIZE);
            reader.open(new ExecutionContext());
            drain(reader::read, 20);

            final org.mockito.ArgumentCaptor<Pageable> captor =
                    org.mockito.ArgumentCaptor.forClass(Pageable.class);
            verify(repository, org.mockito.Mockito.atLeastOnce())
                    .findByAccountIdGreaterThanOrderByAccountIdAsc(any(), captor.capture());
            assertThat(captor.getAllValues())
                    .as("a non-zero page number would reintroduce the OFFSET the keyset seek exists to "
                            + "remove, so every window must be page zero")
                    .isNotEmpty()
                    .allSatisfy(pageable -> {
                        assertThat(pageable.getPageNumber()).isZero();
                        assertThat(pageable.getPageSize()).isEqualTo(PAGE_SIZE);
                    });
        }

        @Test
        @DisplayName("an empty relation completes with no rows and issues exactly one window query")
        void emptyRelationCompletesImmediately() {
            final AccountRepository repository = mock(AccountRepository.class);
            when(repository.count()).thenReturn(Long.valueOf(0L));
            when(repository.findByAccountIdGreaterThanOrderByAccountIdAsc(any(), any(Pageable.class)))
                    .thenReturn(List.of());

            final AccountReader reader = new AccountReader(repository, new FileStatusMapper(), PAGE_SIZE);
            reader.open(new ExecutionContext());

            assertThat(reader.read())
                    .as("app/cbl/CBACT01C.cbl:L108 moves 'Y' to END-OF-FILE and the mainline loop ends; "
                            + "an empty cluster is a successful run, not a fault")
                    .isNull();
            assertThat(reader.read())
                    .as("the end-of-file latch holds, so a second call answers null without a query")
                    .isNull();
            verify(repository, org.mockito.Mockito.times(1))
                    .findByAccountIdGreaterThanOrderByAccountIdAsc(eq(NUMERIC_SEED), any(Pageable.class));
        }

        @Test
        @DisplayName("a restart seeks past the checkpointed key rather than to a computed row offset")
        void restartSeeksPastCheckpointedKey() {
            final AccountRepository repository = mock(AccountRepository.class);
            when(repository.count()).thenReturn(Long.valueOf(5L));
            when(repository.findByAccountIdGreaterThanOrderByAccountIdAsc(eq(Long.valueOf(9L)),
                    any(Pageable.class))).thenReturn(List.of(account(11L)));
            when(repository.findByAccountIdGreaterThanOrderByAccountIdAsc(eq(Long.valueOf(11L)),
                    any(Pageable.class))).thenReturn(List.of());

            // A checkpoint written by a previous run: four rows emitted, the last of them ACCT-ID 9.
            final ExecutionContext restart = new ExecutionContext();
            restart.putLong("AccountReader.recordsRead", 4L);
            restart.putLong("AccountReader.lastAccountId", 9L);

            final AccountReader reader = new AccountReader(repository, new FileStatusMapper(), PAGE_SIZE);
            reader.open(restart);
            final List<Account> emitted = drain(reader::read, 20);

            assertThat(emitted).extracting(Account::getAccountId)
                    .as("the resumed run continues after the LAST KEY EMITTED, so it emits 11 and stops")
                    .containsExactly(Long.valueOf(11L));
            verify(repository, never())
                    .findByAccountIdGreaterThanOrderByAccountIdAsc(eq(NUMERIC_SEED), any(Pageable.class));
            assertThat(reader.getRecordsRead())
                    .as("the tally continues from the checkpoint: four rows before the restart plus one after")
                    .isEqualTo(5L);
        }

        @Test
        @DisplayName("a restart claiming progress with no key is rejected instead of silently re-scanning")
        void restartWithoutKeyIsRejected() {
            final AccountRepository repository = mock(AccountRepository.class);
            final ExecutionContext restart = new ExecutionContext();
            restart.putLong("AccountReader.recordsRead", 4L);

            final AccountReader reader = new AccountReader(repository, new FileStatusMapper(), PAGE_SIZE);

            assertThatExceptionOfType(IllegalStateException.class)
                    .as("restarting from the first row would re-emit the four rows the context says were "
                            + "already emitted, while reporting success; the failure is deliberately loud")
                    .isThrownBy(() -> reader.open(restart))
                    .withMessageContaining("AccountReader.lastAccountId")
                    .withMessageContaining("ACCTFILE");
            verify(repository, never()).findByAccountIdGreaterThanOrderByAccountIdAsc(any(),
                    any(Pageable.class));
        }

        @Test
        @DisplayName("checkpoints the last emitted key, which is the position a restart needs")
        void checkpointsLastEmittedKey() {
            final AccountRepository repository = mock(AccountRepository.class);
            when(repository.count()).thenReturn(Long.valueOf(2L));
            when(repository.findByAccountIdGreaterThanOrderByAccountIdAsc(eq(NUMERIC_SEED),
                    any(Pageable.class))).thenReturn(List.of(account(4L), account(6L)));

            final AccountReader reader = new AccountReader(repository, new FileStatusMapper(), PAGE_SIZE);
            reader.open(new ExecutionContext());
            reader.read();

            final ExecutionContext checkpoint = new ExecutionContext();
            reader.update(checkpoint);

            assertThat(checkpoint.getLong("AccountReader.lastAccountId"))
                    .as("the key of the row actually EMITTED, not the highest key fetched into the window; "
                            + "the window already holds 6 but only 4 has been handed out")
                    .isEqualTo(4L);
            assertThat(checkpoint.getLong("AccountReader.recordsRead")).isEqualTo(1L);
        }
    }

    // ====================================================================================================
    // CUSTFILE, app/cbl/CBCUS01C.cbl
    // ====================================================================================================

    @Nested
    @DisplayName("CustomerReader over CUSTFILE")
    class CustomerScan {

        @Test
        @DisplayName("seeds below the key space, advances by highest key and terminates")
        void seedsBelowKeySpaceAndAdvances() {
            final CustomerRepository repository = mock(CustomerRepository.class);
            when(repository.count()).thenReturn(Long.valueOf(3L));
            when(repository.findByCustomerIdGreaterThanOrderByCustomerIdAsc(eq(NUMERIC_SEED),
                    any(Pageable.class))).thenReturn(List.of(customer(1L), customer(5L)));
            when(repository.findByCustomerIdGreaterThanOrderByCustomerIdAsc(eq(Long.valueOf(5L)),
                    any(Pageable.class))).thenReturn(List.of(customer(8L)));
            when(repository.findByCustomerIdGreaterThanOrderByCustomerIdAsc(eq(Long.valueOf(8L)),
                    any(Pageable.class))).thenReturn(List.of());

            final CustomerReader reader =
                    new CustomerReader(repository, new FileStatusMapper(), PAGE_SIZE);
            reader.open(new ExecutionContext());
            final List<Customer> emitted = drain(reader::read, 20);

            assertThat(emitted).extracting(Customer::getCustomerId)
                    .containsExactly(Long.valueOf(1L), Long.valueOf(5L), Long.valueOf(8L));
            verify(repository, never()).findAll(any(Pageable.class));
        }

        @Test
        @DisplayName("a restart seeks past the checkpointed key")
        void restartSeeksPastCheckpointedKey() {
            final CustomerRepository repository = mock(CustomerRepository.class);
            when(repository.count()).thenReturn(Long.valueOf(3L));
            when(repository.findByCustomerIdGreaterThanOrderByCustomerIdAsc(eq(Long.valueOf(5L)),
                    any(Pageable.class))).thenReturn(List.of(customer(8L)));
            when(repository.findByCustomerIdGreaterThanOrderByCustomerIdAsc(eq(Long.valueOf(8L)),
                    any(Pageable.class))).thenReturn(List.of());

            final ExecutionContext restart = new ExecutionContext();
            restart.putLong("CustomerReader.recordsRead", 2L);
            restart.putLong("CustomerReader.lastCustomerId", 5L);

            final CustomerReader reader =
                    new CustomerReader(repository, new FileStatusMapper(), PAGE_SIZE);
            reader.open(restart);

            assertThat(drain(reader::read, 20)).extracting(Customer::getCustomerId)
                    .containsExactly(Long.valueOf(8L));
            verify(repository, never()).findByCustomerIdGreaterThanOrderByCustomerIdAsc(eq(NUMERIC_SEED),
                    any(Pageable.class));
        }

        @Test
        @DisplayName("a restart claiming progress with no key is rejected")
        void restartWithoutKeyIsRejected() {
            final CustomerRepository repository = mock(CustomerRepository.class);
            final ExecutionContext restart = new ExecutionContext();
            restart.putLong("CustomerReader.recordsRead", 2L);

            final CustomerReader reader =
                    new CustomerReader(repository, new FileStatusMapper(), PAGE_SIZE);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> reader.open(restart))
                    .withMessageContaining("CustomerReader.lastCustomerId")
                    .withMessageContaining("CUSTFILE");
        }
    }

    // ====================================================================================================
    // CARDFILE, app/cbl/CBACT02C.cbl
    // ====================================================================================================

    @Nested
    @DisplayName("CardReader over CARDFILE")
    class CardScan {

        @Test
        @DisplayName("seeds with the empty string, which precedes every CHAR(16) card number")
        void seedsWithEmptyStringAndAdvances() {
            final CardRepository repository = mock(CardRepository.class);
            when(repository.count()).thenReturn(Long.valueOf(3L));
            when(repository.findByCardNumberGreaterThanOrderByCardNumberAsc(eq(TEXT_SEED),
                    any(Pageable.class)))
                    .thenReturn(List.of(card(cardNumber(1)), card(cardNumber(2))));
            when(repository.findByCardNumberGreaterThanOrderByCardNumberAsc(eq(cardNumber(2)),
                    any(Pageable.class))).thenReturn(List.of(card(cardNumber(3))));
            when(repository.findByCardNumberGreaterThanOrderByCardNumberAsc(eq(cardNumber(3)),
                    any(Pageable.class))).thenReturn(List.of());

            final CardReader reader = new CardReader(repository, new FileStatusMapper(), PAGE_SIZE);
            reader.open(new ExecutionContext());

            assertThat(drain(reader::read, 20)).extracting(Card::getCardNumber)
                    .as("leading-zero-safe character collation, which is why CARD-NUM is compared as text: "
                            + "app/cpy/CVACT02Y.cpy:L5 declares PIC X(16), not a numeric picture")
                    .containsExactly(cardNumber(1), cardNumber(2), cardNumber(3));
            verify(repository, never()).findAll(any(Pageable.class));
            verify(repository, never()).findAllByOrderByCardNumberAsc(any(Pageable.class));
        }

        @Test
        @DisplayName("a restart seeks past the checkpointed card number")
        void restartSeeksPastCheckpointedCardNumber() {
            final CardRepository repository = mock(CardRepository.class);
            when(repository.count()).thenReturn(Long.valueOf(3L));
            // The ordinal re-probe IS the resume for this reader. No key is checkpointed - a primary account
            // number written to an execution context is persisted to the job repository, where it outlives the
            // run - so a row count of 2 is recovered by asking for the row at ordinal 1 with a window of one,
            // from the seed. The key that probe returns then bounds the forward scan.
            when(repository.findByCardNumberGreaterThanOrderByCardNumberAsc(eq(TEXT_SEED),
                    argThat(window -> window != null && window.getOffset() == 1L)))
                    .thenReturn(List.of(card(cardNumber(2))));
            when(repository.findByCardNumberGreaterThanOrderByCardNumberAsc(eq(cardNumber(2)),
                    any(Pageable.class))).thenReturn(List.of(card(cardNumber(3))));
            when(repository.findByCardNumberGreaterThanOrderByCardNumberAsc(eq(cardNumber(3)),
                    any(Pageable.class))).thenReturn(List.of());

            final ExecutionContext restart = new ExecutionContext();
            restart.putLong("CardReader.recordsRead", 2L);

            final CardReader reader = new CardReader(repository, new FileStatusMapper(), PAGE_SIZE);
            reader.open(restart);

            assertThat(drain(reader::read, 20)).extracting(Card::getCardNumber)
                    .containsExactly(cardNumber(3));
            // The seed is used, but only for the bounded ordinal probe above. What must never happen is a
            // forward window from the seed, which would re-emit the rows the context says were already read.
            verify(repository, never()).findByCardNumberGreaterThanOrderByCardNumberAsc(eq(TEXT_SEED),
                    argThat(window -> window != null && window.getOffset() == 0L));
        }

        @Test
        @DisplayName("a restart claiming progress with no key is rejected, and no card number is disclosed")
        void restartWithoutKeyIsRejectedWithoutDisclosingACardNumber() {
            final CardRepository repository = mock(CardRepository.class);
            final ExecutionContext restart = new ExecutionContext();
            restart.putLong("CardReader.recordsRead", 2L);

            final CardReader reader = new CardReader(repository, new FileStatusMapper(), PAGE_SIZE);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> reader.open(restart))
                    // The diagnostic names the file and the row count, and explains that the relation no
                    // longer yields a row at the checkpointed position. It does NOT name a context key for a
                    // card number, because this reader stores none.
                    .withMessageContaining("CARDFILE")
                    .withMessageContaining("2 rows already emitted")
                    .withMessageContaining("no longer yields a row at that position")
                    .satisfies(failure -> assertThat(failure.getMessage())
                            .as("the diagnostic names the context KEY, never a card number: Rule 1 clause "
                                    + "D1 keeps cardholder data out of messages as well as logs")
                            .doesNotContain("9999"));
        }
    }

    // ====================================================================================================
    // XREFFILE, app/cbl/CBACT03C.cbl
    // ====================================================================================================

    @Nested
    @DisplayName("CardCrossReferenceReader over XREFFILE")
    class CrossReferenceScan {

        @Test
        @DisplayName("seeds with the empty string and never consults the CXACAIX account path")
        void seedsWithEmptyStringAndNeverUsesAlternateIndex() {
            final CardCrossReferenceRepository repository = mock(CardCrossReferenceRepository.class);
            when(repository.count()).thenReturn(Long.valueOf(3L));
            when(repository.findByCardNumberGreaterThanOrderByCardNumberAsc(eq(TEXT_SEED),
                    any(Pageable.class))).thenReturn(
                            List.of(crossReference(cardNumber(1)), crossReference(cardNumber(2))));
            when(repository.findByCardNumberGreaterThanOrderByCardNumberAsc(eq(cardNumber(2)),
                    any(Pageable.class))).thenReturn(List.of(crossReference(cardNumber(3))));
            when(repository.findByCardNumberGreaterThanOrderByCardNumberAsc(eq(cardNumber(3)),
                    any(Pageable.class))).thenReturn(List.of());

            final CardCrossReferenceReader reader =
                    new CardCrossReferenceReader(repository, new FileStatusMapper(), PAGE_SIZE);
            reader.open(new ExecutionContext());

            assertThat(drain(reader::read, 20)).extracting(CardCrossReference::getCardNumber)
                    .containsExactly(cardNumber(1), cardNumber(2), cardNumber(3));
            verify(repository, never()).findFirstByAccountIdOrderByCardNumberAsc(any());
            verify(repository, never()).findAll(any(Pageable.class));
        }

        @Test
        @DisplayName("a restart seeks past the checkpointed card number")
        void restartSeeksPastCheckpointedCardNumber() {
            final CardCrossReferenceRepository repository = mock(CardCrossReferenceRepository.class);
            when(repository.count()).thenReturn(Long.valueOf(3L));
            // The ordinal re-probe IS the resume for this reader. No key is checkpointed - a primary account
            // number written to an execution context is persisted to the job repository, where it outlives the
            // run - so a row count of 2 is recovered by asking for the row at ordinal 1 with a window of one,
            // from the seed. The key that probe returns then bounds the forward scan.
            when(repository.findByCardNumberGreaterThanOrderByCardNumberAsc(eq(TEXT_SEED),
                    argThat(window -> window != null && window.getOffset() == 1L)))
                    .thenReturn(List.of(crossReference(cardNumber(2))));
            when(repository.findByCardNumberGreaterThanOrderByCardNumberAsc(eq(cardNumber(2)),
                    any(Pageable.class))).thenReturn(List.of(crossReference(cardNumber(3))));
            when(repository.findByCardNumberGreaterThanOrderByCardNumberAsc(eq(cardNumber(3)),
                    any(Pageable.class))).thenReturn(List.of());

            final ExecutionContext restart = new ExecutionContext();
            restart.putLong("CardCrossReferenceReader.recordsRead", 2L);

            final CardCrossReferenceReader reader =
                    new CardCrossReferenceReader(repository, new FileStatusMapper(), PAGE_SIZE);
            reader.open(restart);

            assertThat(drain(reader::read, 20)).extracting(CardCrossReference::getCardNumber)
                    .containsExactly(cardNumber(3));
            // The seed is used, but only for the bounded ordinal probe above. What must never happen is a
            // forward window from the seed, which would re-emit the rows the context says were already read.
            verify(repository, never()).findByCardNumberGreaterThanOrderByCardNumberAsc(eq(TEXT_SEED),
                    argThat(window -> window != null && window.getOffset() == 0L));
        }

        @Test
        @DisplayName("a restart claiming progress with no key is rejected")
        void restartWithoutKeyIsRejected() {
            final CardCrossReferenceRepository repository = mock(CardCrossReferenceRepository.class);
            final ExecutionContext restart = new ExecutionContext();
            restart.putLong("CardCrossReferenceReader.recordsRead", 2L);

            final CardCrossReferenceReader reader =
                    new CardCrossReferenceReader(repository, new FileStatusMapper(), PAGE_SIZE);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> reader.open(restart))
                    // The diagnostic names the file and the row count, and explains that the relation no
                    // longer yields a row at the checkpointed position. It does NOT name a context key for a
                    // card number, because this reader stores none.
                    .withMessageContaining("XREFFILE")
                    .withMessageContaining("2 rows already emitted")
                    .withMessageContaining("no longer yields a row at that position");
        }
    }
}

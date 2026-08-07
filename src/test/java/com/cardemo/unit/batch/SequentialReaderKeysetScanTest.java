/*
 * ****************************************************************************
 * Test        : SequentialReaderKeysetScanTest
 * Application : CardDemo
 * Type        : JUnit 5 unit test, no Spring context and no database
 * Function    : Pins the keyset-seek browse contract of the five sequential readers that scan a whole
 *               relation: the four read-only verification readers replacing app/cbl/CBACT01C.cbl
 *               (ACCTFILE), app/cbl/CBACT02C.cbl (CARDFILE), app/cbl/CBACT03C.cbl (XREFFILE) and
 *               app/cbl/CBCUS01C.cbl (CUSTFILE), plus the DALYTRAN staging reader that feeds both
 *               steps of the POSTTRAN job (app/cbl/CBTRN01C.cbl and app/cbl/CBTRN02C.cbl). The four
 *               verification programs have a verb inventory of OPEN, READ and CLOSE only, so each
 *               becomes a sequential scan whose sole observable contract is the ORDER and the
 *               COMPLETENESS of the rows it emits; the staging reader shares that contract.
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cardemo.batch.readers.AccountReader;
import com.cardemo.batch.readers.CardCrossReferenceReader;
import com.cardemo.batch.readers.CardReader;
import com.cardemo.batch.readers.CustomerReader;
import com.cardemo.batch.readers.DailyTransactionReader;
import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.Card;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Customer;
import com.cardemo.model.entity.DailyTransaction;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.CardRepository;
import com.cardemo.repository.CustomerRepository;
import com.cardemo.repository.DailyTransactionRepository;
import com.cardemo.service.shared.FileStatusMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.batch.item.ExecutionContext;
import io.awspring.cloud.s3.S3Operations;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable;
import org.mockito.ArgumentCaptor;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.batch.readers.TransactionBackupReader;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.SliceImpl;

/**
 * Verifies that all five sequential readers browse their relation by <b>keyset seek</b> and that the seek is
 * complete, non-repeating and restartable.
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
@DisplayName("com.cardemo.batch.readers - keyset-seek browse contract of the five sequential readers")
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
        // The third argument IS the card verification value: it is modelled and mandatory, but write-once
        // and accessor-less, so nothing can read it back out again.
        return new Card(cardNumber, Long.valueOf(1L), "007", "SYNTHETICA Q TESTCASE     ", "2026-12-31", "Y");
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

    // ====================================================================================================
    // DALYTRAN staging relation, app/cbl/CBTRN02C.cbl:L29-L32 read by app/cbl/CBTRN01C.cbl and CBTRN02C
    // ====================================================================================================

    /**
     * Builds a {@code DALYTRAN-RECORD} of {@code app/cpy/CVTRA06Y.cpy} at the supplied ingestion ordinal.
     *
     * <p>Every field is synthetic and fixed-width to the copybook: the card number sits in the {@code 9999}
     * test range so it cannot collide with an issued PAN, and the merchant fields are blank runs.
     *
     * @param ingestSequence the one-based {@code ingest_seq}, which is the record's position in the flat file
     * @return the staged record; never {@code null}
     */
    private static DailyTransaction staged(final long ingestSequence) {
        return new DailyTransaction(Long.valueOf(ingestSequence),
                String.format(Locale.ROOT, "%016d", Long.valueOf(ingestSequence)),
                "01", Integer.valueOf(1), "POS       ", " ".repeat(100), new BigDecimal("10.00"),
                Long.valueOf(0L), " ".repeat(50), " ".repeat(50), " ".repeat(10),
                "9999" + "0".repeat(12), " ".repeat(26), " ".repeat(26));
    }

    /**
     * Builds a reader on the {@code repository} path against a mocked repository.
     *
     * @param repository the mocked staging repository
     * @return the reader; never {@code null}
     */
    private static DailyTransactionReader stagingReader(final DailyTransactionRepository repository) {
        return new DailyTransactionReader(repository, mock(S3Operations.class), new FileStatusMapper(),
                "repository", PAGE_SIZE, "", "gdg/dalytran/current.ps", "");
    }

    @Nested
    @DisplayName("DailyTransactionReader over the DALYTRAN staging relation")
    class DailyTransactionScan {

        /**
         * The seed bound for the ingestion ordinal. {@code ingest_seq} is one-based, being the record's own
         * position in the file, so zero is provably below the whole key space and is never itself a key.
         */
        private static final long ORDINAL_SEED = 0L;

        @Test
        @DisplayName("seeds the first window below the whole key space and advances by the highest ordinal")
        void seedsBelowKeySpaceAndAdvancesByHighestOrdinal() {
            final DailyTransactionRepository repository = mock(DailyTransactionRepository.class);
            when(repository.findByIngestSequenceGreaterThanOrderByIngestSequenceAsc(
                    eq(ORDINAL_SEED), any(Pageable.class)))
                    .thenReturn(new SliceImpl<>(List.of(staged(1L)), Pageable.ofSize(PAGE_SIZE), true));
            when(repository.findByIngestSequenceGreaterThanOrderByIngestSequenceAsc(
                    eq(1L), any(Pageable.class)))
                    .thenReturn(new SliceImpl<>(List.of(staged(2L), staged(7L)),
                            Pageable.ofSize(PAGE_SIZE), true));
            when(repository.findByIngestSequenceGreaterThanOrderByIngestSequenceAsc(
                    eq(7L), any(Pageable.class)))
                    .thenReturn(new SliceImpl<>(List.of(staged(11L)), Pageable.ofSize(PAGE_SIZE), false));

            final DailyTransactionReader reader = stagingReader(repository);
            reader.open(new ExecutionContext());
            final List<DailyTransaction> emitted = drain(reader::read, 20);

            assertThat(emitted).extracting(DailyTransaction::getIngestSequence)
                    .as("every row is emitted exactly once in ascending ordinal order; the gaps between 2 "
                            + "and 7 and between 7 and 11 prove the cursor follows the DATA rather than "
                            + "counting rows, which is what an OFFSET page would have done")
                    .containsExactly(Long.valueOf(1L), Long.valueOf(2L), Long.valueOf(7L),
                            Long.valueOf(11L));
            assertThat(reader.getRecordsRead()).isEqualTo(4L);
            verify(repository, never()).count();
        }

        @Test
        @DisplayName("requests page zero on every window, so the query is a seek and never an offset scan")
        void everyWindowRequestsPageZero() {
            final DailyTransactionRepository repository = mock(DailyTransactionRepository.class);
            when(repository.findByIngestSequenceGreaterThanOrderByIngestSequenceAsc(
                    eq(ORDINAL_SEED), any(Pageable.class)))
                    .thenReturn(new SliceImpl<>(List.of(staged(1L), staged(2L)),
                            Pageable.ofSize(PAGE_SIZE), true));
            when(repository.findByIngestSequenceGreaterThanOrderByIngestSequenceAsc(
                    eq(2L), any(Pageable.class)))
                    .thenReturn(new SliceImpl<>(List.of(staged(3L)), Pageable.ofSize(PAGE_SIZE), false));

            final DailyTransactionReader reader = stagingReader(repository);
            reader.open(new ExecutionContext());
            drain(reader::read, 20);

            final org.mockito.ArgumentCaptor<Pageable> captor =
                    org.mockito.ArgumentCaptor.forClass(Pageable.class);
            verify(repository, org.mockito.Mockito.atLeastOnce())
                    .findByIngestSequenceGreaterThanOrderByIngestSequenceAsc(
                            org.mockito.ArgumentMatchers.anyLong(), captor.capture());
            assertThat(captor.getAllValues())
                    .as("a non-zero page number would reintroduce the OFFSET the keyset seek exists to "
                            + "remove, so every window - including the open probe - must be page zero")
                    .isNotEmpty()
                    .allSatisfy(pageable -> assertThat(pageable.getPageNumber()).isZero());
        }

        @Test
        @DisplayName("the open probe is bounded to one row and no whole-relation count is ever issued")
        void openProbeIsBoundedAndNeverCounts() {
            final DailyTransactionRepository repository = mock(DailyTransactionRepository.class);
            when(repository.findByIngestSequenceGreaterThanOrderByIngestSequenceAsc(
                    eq(ORDINAL_SEED), any(Pageable.class)))
                    .thenReturn(new SliceImpl<>(List.of(staged(1L)), Pageable.ofSize(1), false));

            final DailyTransactionReader reader = stagingReader(repository);
            reader.open(new ExecutionContext());

            final org.mockito.ArgumentCaptor<Pageable> captor =
                    org.mockito.ArgumentCaptor.forClass(Pageable.class);
            verify(repository).findByIngestSequenceGreaterThanOrderByIngestSequenceAsc(
                    eq(ORDINAL_SEED), captor.capture());
            assertThat(captor.getValue().getPageSize())
                    .as("OPEN INPUT has only to establish that the relation is reachable, so the probe asks "
                            + "for one row. A count() would make the store visit every staged row to answer "
                            + "a question nothing reads - app/cbl/CBTRN02C.cbl keeps its own tally at :L206 "
                            + "and never interrogates the file for a total")
                    .isEqualTo(1);
            verify(repository, never()).count();
        }

        @Test
        @DisplayName("an empty relation completes with no rows and is a successful run, not a fault")
        void emptyRelationCompletesImmediately() {
            final DailyTransactionRepository repository = mock(DailyTransactionRepository.class);
            when(repository.findByIngestSequenceGreaterThanOrderByIngestSequenceAsc(
                    org.mockito.ArgumentMatchers.anyLong(), any(Pageable.class)))
                    .thenReturn(new SliceImpl<>(List.of(), Pageable.ofSize(PAGE_SIZE), false));

            final DailyTransactionReader reader = stagingReader(repository);
            reader.open(new ExecutionContext());

            assertThat(reader.read())
                    .as("file status '10' terminates the loop at app/cbl/CBTRN02C.cbl:L345-L369; an empty "
                            + "staging relation is a successful run with a record count of zero")
                    .isNull();
            assertThat(reader.read())
                    .as("the end-of-data latch holds, so a second call answers null too")
                    .isNull();
            assertThat(reader.getRecordsRead()).isZero();
            verify(repository, never()).count();
        }

        @Test
        @DisplayName("a restart seeks past the checkpointed ordinal rather than to a computed row offset")
        void restartSeeksPastCheckpointedOrdinal() {
            final DailyTransactionRepository repository = mock(DailyTransactionRepository.class);
            when(repository.findByIngestSequenceGreaterThanOrderByIngestSequenceAsc(
                    eq(7L), any(Pageable.class)))
                    .thenReturn(new SliceImpl<>(List.of(staged(11L)), Pageable.ofSize(PAGE_SIZE), false));

            // A checkpoint written by a previous run: four rows emitted, the last of them ordinal 7. The
            // count and the ordinal deliberately DISAGREE, which is what an offset resume cannot survive:
            // dividing four by the page size would seek to the wrong place entirely.
            final ExecutionContext restart = new ExecutionContext();
            restart.putLong("DailyTransactionReader.recordsRead", 4L);
            restart.putLong("DailyTransactionReader.lastIngestSequence", 7L);

            final DailyTransactionReader reader = stagingReader(repository);
            reader.open(restart);
            final List<DailyTransaction> emitted = drain(reader::read, 20);

            assertThat(emitted).extracting(DailyTransaction::getIngestSequence)
                    .as("the resumed run continues after the LAST ORDINAL EMITTED, so it emits 11 and stops")
                    .containsExactly(Long.valueOf(11L));
            verify(repository, never()).findByIngestSequenceGreaterThanOrderByIngestSequenceAsc(
                    eq(ORDINAL_SEED), any(Pageable.class));
            assertThat(reader.getRecordsRead())
                    .as("the tally continues from the checkpoint: four rows before the restart plus one after")
                    .isEqualTo(5L);
        }

        @Test
        @DisplayName("the checkpoint carries the emitted ordinal, which is what makes the resume exact")
        void checkpointCarriesTheEmittedOrdinal() {
            final DailyTransactionRepository repository = mock(DailyTransactionRepository.class);
            when(repository.findByIngestSequenceGreaterThanOrderByIngestSequenceAsc(
                    eq(ORDINAL_SEED), any(Pageable.class)))
                    .thenReturn(new SliceImpl<>(List.of(staged(4L), staged(9L)),
                            Pageable.ofSize(PAGE_SIZE), true));

            final DailyTransactionReader reader = stagingReader(repository);
            reader.open(new ExecutionContext());
            reader.read();
            reader.read();

            final ExecutionContext checkpoint = new ExecutionContext();
            reader.update(checkpoint);

            assertThat(checkpoint.getLong("DailyTransactionReader.lastIngestSequence"))
                    .as("the cursor is written as the ordinal of the last row genuinely EMITTED, not the "
                            + "highest ordinal fetched into the buffer, so a mid-buffer restart cannot skip "
                            + "the remainder of that buffer")
                    .isEqualTo(9L);
            assertThat(checkpoint.getLong("DailyTransactionReader.recordsRead")).isEqualTo(2L);
        }

        @Test
        @DisplayName("a context with a row count but no ordinal falls back to the count, since ingest_seq "
                + "IS the file position")
        void restartWithoutOrdinalFallsBackToTheCount() {
            final DailyTransactionRepository repository = mock(DailyTransactionRepository.class);
            when(repository.findByIngestSequenceGreaterThanOrderByIngestSequenceAsc(
                    eq(4L), any(Pageable.class)))
                    .thenReturn(new SliceImpl<>(List.of(staged(5L)), Pageable.ofSize(PAGE_SIZE), false));

            // Written before the ordinal entry existed. Unlike the four verification readers, this reader
            // can reconstruct the cursor rather than having to reject the context: ingest_seq is the
            // record's one-based position in the file, so the k-th row carries ordinal k. The fallback
            // therefore degrades to re-reading at worst, never to skipping.
            final ExecutionContext restart = new ExecutionContext();
            restart.putLong("DailyTransactionReader.recordsRead", 4L);

            final DailyTransactionReader reader = stagingReader(repository);
            reader.open(restart);

            assertThat(drain(reader::read, 20)).extracting(DailyTransaction::getIngestSequence)
                    .containsExactly(Long.valueOf(5L));
            verify(repository, never()).findByIngestSequenceGreaterThanOrderByIngestSequenceAsc(
                    eq(ORDINAL_SEED), any(Pageable.class));
        }
    }

    @Nested
    @DisplayName("TransactionBackupReader resolves the current GDG generation (H-13, M-07, L-04)")
    class TransactionBackupGenerationSelection {

        /** Bucket the generations live in; any name works, because the listing is stubbed. */
        private static final String BUCKET = "carddemo-batch-output";

        /** The configured prefix, in the one canonical spelling the shared grammar accepts. */
        private static final String CONFIGURED_PREFIX = "gdg/transact-bkup";

        /**
         * Builds the reader on its object-storage path.
         *
         * @param client the paginated client to enumerate with
         * @return a reader configured to resolve its generation from {@link #BUCKET}
         */
        private TransactionBackupReader objectStorageReader(final S3Client client) {
            return new TransactionBackupReader(mock(TransactionRepository.class), mock(S3Operations.class),
                    client, new FileStatusMapper(), "object-storage", PAGE_SIZE, BUCKET, CONFIGURED_PREFIX,
                    null);
        }

        /**
         * Builds one listing response.
         *
         * @param truncated whether a further page follows
         * @param token     the continuation token to advertise, or {@code null}
         * @param keys      the keys the page carries
         * @return the response
         */
        private ListObjectsV2Response page(final boolean truncated, final String token,
                final String... keys) {

            final List<S3Object> contents = new ArrayList<>();
            for (final String key : keys) {
                contents.add(S3Object.builder().key(key).build());
            }
            return ListObjectsV2Response.builder()
                    .contents(contents)
                    .isTruncated(Boolean.valueOf(truncated))
                    .nextContinuationToken(token)
                    .build();
        }

        /**
         * Serves the stubbed pages through a real paginator.
         *
         * <p>The reader asks the client for a paginator rather than issuing one request, so the pages have to
         * be reached the way production reaches them. A real {@link ListObjectsV2Iterable} over the same mock
         * does exactly that: it issues the underlying request, reads the continuation token off the response
         * and issues the next one, which is why the request assertions below still measure what they measured
         * before - the listing being followed to its end, rather than a single page being taken for the whole.
         *
         * @param client the mock whose {@code listObjectsV2} pages are already stubbed; must not be
         *     {@code null}
         */
        private void servePagesThroughAPaginator(final S3Client client) {
            when(client.listObjectsV2Paginator(any(ListObjectsV2Request.class)))
                    .thenAnswer(invocation -> new ListObjectsV2Iterable(client,
                            invocation.getArgument(0, ListObjectsV2Request.class)));
        }

        @Test
        @DisplayName("H-13: the greatest key is taken across ALL pages, not just the first")
        void theGreatestKeyIsTakenAcrossEveryPage() {
            // The defect this pins: one ListObjectsV2 request returns the lexically SMALLEST page, so taking
            // its maximum selects the OLDEST generation of a large bucket and the report runs, successfully and
            // silently, over stale data. The newest generation is deliberately placed on the second page.
            final S3Client client = mock(S3Client.class);
            when(client.listObjectsV2(any(ListObjectsV2Request.class)))
                    .thenReturn(page(true, "token-1",
                            "gdg/transact-bkup/0000000001", "gdg/transact-bkup/0000000002"))
                    .thenReturn(page(false, null,
                            "gdg/transact-bkup/0000000003", "gdg/transact-bkup/0000000009"));
            servePagesThroughAPaginator(client);

            final TransactionBackupReader reader = objectStorageReader(client);
            assertThatThrownBy(() -> reader.open(new ExecutionContext()))
                    .as("the object itself is not stubbed, so the open fails AFTER resolution - which is "
                            + "exactly when the resolved key is observable")
                    .isInstanceOf(RuntimeException.class);

            final ArgumentCaptor<ListObjectsV2Request> requests =
                    ArgumentCaptor.forClass(ListObjectsV2Request.class);
            verify(client, times(2)).listObjectsV2(requests.capture());
            assertThat(requests.getAllValues()).extracting(ListObjectsV2Request::continuationToken)
                    .as("the first request carries no token and the second carries the one page one returned, "
                            + "which is what following the listing to its end means")
                    .containsExactly(null, "token-1");
        }

        @Test
        @DisplayName("L-04: the prefix is normalised to exactly one trailing separator")
        void thePrefixIsNormalisedToOneTrailingSeparator() {
            // Without the separator, 'gdg/transact-bkup' also matches 'gdg/transact-bkup-shadow', and a
            // generation of that unrelated base could be selected as the greatest key.
            final S3Client client = mock(S3Client.class);
            when(client.listObjectsV2(any(ListObjectsV2Request.class)))
                    .thenReturn(page(false, null, "gdg/transact-bkup/0000000001"));
            servePagesThroughAPaginator(client);

            final TransactionBackupReader reader = objectStorageReader(client);
            assertThatThrownBy(() -> reader.open(new ExecutionContext()))
                    .isInstanceOf(RuntimeException.class);

            final ArgumentCaptor<ListObjectsV2Request> requests =
                    ArgumentCaptor.forClass(ListObjectsV2Request.class);
            verify(client).listObjectsV2(requests.capture());
            assertThat(requests.getValue().prefix())
                    .isEqualTo(CONFIGURED_PREFIX + "/")
                    .doesNotEndWith("//");

            // Finding m-02. This assertion read DEFAULT_GENERATION_PREFIX.endsWith("/") until the prefix
            // grammar was centralized, and the constant did end with one - while application.yml declares
            // gdg/transact-bkup without one. The constant and the profile therefore disagreed by a character,
            // and only a unit test that omits the profile ever saw the difference, which is why it survived.
            // The constant is now the same relative form the profile declares, and the separator is DERIVED
            // for the listing. Asserting it on the value actually sent to S3, as the assertion above does, is
            // the stronger claim anyway: it is the listing filter that has to carry the boundary, not a
            // constant.
            assertThat(TransactionBackupReader.DEFAULT_GENERATION_PREFIX)
                    .as("the fallback must be the one canonical spelling, so a context without the profile "
                            + "validates the same value a context with it does")
                    .isEqualTo(CONFIGURED_PREFIX)
                    .doesNotEndWith("/");
        }

        @Test
        @DisplayName("L-04: a sibling prefix sharing the leading characters cannot be selected")
        void aSiblingPrefixCannotBeSelected() {
            // The sibling key sorts ABOVE every legitimate generation, so it would win a naive maximum. It is
            // excluded because the request prefix ends at a separator and the sibling does not sit under it.
            final S3Client client = mock(S3Client.class);
            when(client.listObjectsV2(any(ListObjectsV2Request.class)))
                    .thenReturn(page(false, null, "gdg/transact-bkup/0000000001"));
            servePagesThroughAPaginator(client);

            final TransactionBackupReader reader = objectStorageReader(client);
            assertThatThrownBy(() -> reader.open(new ExecutionContext()))
                    .isInstanceOf(RuntimeException.class);

            final ArgumentCaptor<ListObjectsV2Request> requests =
                    ArgumentCaptor.forClass(ListObjectsV2Request.class);
            verify(client).listObjectsV2(requests.capture());
            assertThat("gdg/transact-bkup-shadow/0000009999")
                    .as("the sibling does not start with the normalised prefix, so the store never returns it")
                    .doesNotStartWith(requests.getValue().prefix());
        }

        @Test
        @DisplayName("M-07: the repository path issues no count(), because a count was only ever logged")
        void theRepositoryPathIssuesNoCount() {
            final TransactionRepository repository = mock(TransactionRepository.class);
            when(repository.findStatementOrderAfter(anyString(), anyString(), any(Pageable.class)))
                    .thenReturn(List.of());

            final TransactionBackupReader reader = new TransactionBackupReader(repository,
                    mock(S3Operations.class), mock(S3Client.class), new FileStatusMapper(), "repository",
                    PAGE_SIZE, "", CONFIGURED_PREFIX, null);
            reader.open(new ExecutionContext());

            // An exact count is a full scan of the very relation the reader then walks in bounded windows, and
            // it was issued only to produce a log line. Emptiness is reported from the emitted-row counter at
            // the end of the run instead, where it is a fact rather than a prediction.
            verify(repository, never()).count();
        }
    }
}

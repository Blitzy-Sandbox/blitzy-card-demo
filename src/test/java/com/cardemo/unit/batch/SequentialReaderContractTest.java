/*
 * ******************************************************************
 * Program     : SequentialReaderContractTest
 * Application : CardDemo
 * Type        : JUnit 5 unit test
 * Function    : Pins the shared read-only sequential scan contract of the three
 *               remaining verification readers.
 * Source      : app/cbl/CBACT02C.cbl (178 lines, CARDFILE) @ 7756d89
 * Source      : app/cbl/CBACT03C.cbl (178 lines, XREFFILE) @ 7756d89
 * Source      : app/cbl/CBCUS01C.cbl (178 lines, CUSTFILE) @ 7756d89
 * Source      : app/jcl/READCARD.jcl, app/jcl/READXREF.jcl, app/jcl/READCUST.jcl
 *               (the three jobs that run them) @ 7756d89
 * ******************************************************************
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ******************************************************************
 */
package com.cardemo.unit.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cardemo.batch.readers.CardCrossReferenceReader;
import com.cardemo.batch.readers.CardReader;
import com.cardemo.batch.readers.CustomerReader;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.model.entity.Card;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Customer;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.CardRepository;
import com.cardemo.repository.CustomerRepository;
import com.cardemo.service.shared.FileStatusMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Unit test for {@link CardReader}, {@link CardCrossReferenceReader} and {@link CustomerReader}.
 *
 * <h2>What it does</h2>
 *
 * <p>{@code CBACT02C}, {@code CBACT03C} and {@code CBCUS01C} are three of the four programs whose entire verb
 * inventory is {@code OPEN}, {@code READ}, {@code CLOSE} and {@code DISPLAY} - none contains a {@code WRITE},
 * {@code REWRITE} or {@code DELETE} - so all three become read-only verification steps. They are the same
 * program with a different file, and their Java readers are correspondingly the same class with a different
 * entity, repository and order property. This test therefore pins the <strong>shared</strong> contract once per
 * reader rather than repeating a bespoke suite three times: ordered paged scan, sticky end of file, a restart
 * that resumes after the last record handed out, no write of any kind, and an abend on any I/O failure.
 *
 * <p>Each reader keeps its own nested group because the three differ in exactly the details that matter - the
 * order property that establishes sequential order, the logical file name that appears in the abend, and the
 * message text - and a single parameterised run would have to weaken its assertions to the intersection.
 *
 * <p>{@link AccountReaderTest} covers the fourth reader in the same shape, and deliberately in more depth:
 * {@code CBACT01C} is the one whose record image is rendered field by field, so it carries assertions the
 * other three have no counterpart for.
 *
 * <h2>How to build, run and test</h2>
 *
 * <p>Run alone with {@code ./mvnw -B -ntp -o test -Dtest=SequentialReaderContractTest -Djacoco.skip=true}, or
 * with the unit tier via {@code ./mvnw -B -ntp test}. No Spring context, no database, no container: each
 * repository is a Mockito double and each reader is constructed directly. The mapper is the real
 * {@link FileStatusMapper}, because stubbing the component that performs the status translation would remove
 * the behaviour under test.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>Each reader's page size is its third constructor argument, bound in production from
 * {@code carddemo.batch.card-reader.page-size},
 * {@code carddemo.batch.card-cross-reference-reader.page-size} and
 * {@code carddemo.batch.customer-reader.page-size} respectively, each defaulting to 100. This test passes 2 so
 * that a page boundary is crossed within three records.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li>A failure asserting the {@link Sort} means sequential order was lost. These steps exist to verify a
 *       file can be read in key order; an unordered scan returns the same rows and proves nothing.</li>
 *   <li>A failure in the sticky end-of-file test means {@code read()} resumed after returning {@code null}.</li>
 *   <li>A failure in the abend test means an I/O error was swallowed. The abend carries code
 *       {@link FatalProcessingException#BATCH_ABEND_CODE} and names the logical file.</li>
 *   <li>A failure asserting no write means a save or delete reached the repository. All three programs are
 *       read-only, and a write here would be an invented capability rather than a migration.</li>
 *   </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("The three remaining verification readers - shared read-only scan contract")
class SequentialReaderContractTest {

    /** Small enough that a page boundary is crossed within three records. */
    private static final int PAGE_SIZE = 2;

    @Mock
    private CardRepository cardRepository;

    @Mock
    private CardCrossReferenceRepository cardCrossReferenceRepository;

    @Mock
    private CustomerRepository customerRepository;

    private FileStatusMapper fileStatusMapper;

    @BeforeEach
    void setUp() {
        fileStatusMapper = new FileStatusMapper();
    }

    // ====================================================================================================
    // Shared helpers. Each takes the already-typed list, so no unchecked cast or raw type is needed
    // anywhere - the zero-warning compile would reject either.
    // ====================================================================================================

    /** Answers {@code findAll(Pageable)} out of {@code all}, honouring offset and page size. */
    private static <T, K extends Comparable<K>> org.mockito.stubbing.Answer<Object> paging(
            final List<T> all, final java.util.function.Function<T, K> key) {
        return invocation -> {
            // Argument zero is now the CURSOR and argument one the window. The readers scan by keyset -
            // "key > cursor ORDER BY key" - so a page index would answer the same first window for ever and
            // the scan would never terminate. The offset is still honoured because the restart probe pages to
            // an ordinal with a window of one to recover the key it must resume from.
            final K cursor = invocation.getArgument(0);
            final Pageable p = invocation.getArgument(1);
            final List<T> after = all.stream()
                    .filter(row -> cursor == null || key.apply(row).compareTo(cursor) > 0)
                    .toList();
            final int from = Math.min((int) p.getOffset(), after.size());
            final int to = Math.min(from + p.getPageSize(), after.size());
            return List.copyOf(after.subList(from, to));
        };
    }

    /**
     * Drains a reader to exhaustion, projecting each item through {@code key}.
     *
     * <p>The first parameter is a {@link Supplier} bound to the concrete reader's {@code read()} rather than
     * the {@link org.springframework.batch.item.ItemStreamReader} interface. The interface method declares
     * {@code throws Exception}, which every caller would then have to propagate; each concrete reader
     * overrides it without a checked exception, so binding at the concrete type keeps the signatures honest
     * and the call sites free of a checked exception none of them can actually raise.
     */
    private static <T> List<Object> drain(final Supplier<T> next, final Function<T, Object> key) {
        final List<Object> seen = new ArrayList<>();
        T item = next.get();
        while (item != null) {
            seen.add(key.apply(item));
            item = next.get();
        }
        return seen;
    }

    private static String cardNumber(final int n) {
        return String.format(java.util.Locale.ROOT, "%016d", Integer.valueOf(n));
    }

    private static Card card(final int n) {
        // CARD-CVV-CD is not a constructor parameter: app/cpy/CVACT02Y.cpy declares it but the entity
        // does not persist it, so there is no component here to supply.
        return new Card(cardNumber(n), Long.valueOf(n), "007", "FNAMEAA6 LNAME6", "2025-01-01", "Y");
    }

    private static CardCrossReference crossReference(final int n) {
        return new CardCrossReference(cardNumber(n), Long.valueOf(n), Long.valueOf(n));
    }

    private static Customer customer(final int n) {
        return new Customer(Long.valueOf(n), "FNAMEAA6", "M", "LNAME6", "1 Main Street", "Apt 2",
                "District 3", "NY", "USA", "12345", "5551234567", "5557654321", "123456789",
                "GOV1234567890", "19700101", "EFT0000001", "Y", "750");
    }

    @Nested
    @DisplayName("CardReader - CARDFILE, ordered by cardNumber (app/cbl/CBACT02C.cbl)")
    class CardReaderContract {

        private CardReader reader() {
            return new CardReader(cardRepository, fileStatusMapper, PAGE_SIZE);
        }

        @Test
        @DisplayName("reads every record once, ordered by cardNumber, across page boundaries")
        void readsEveryRecordOnceInOrder() {
            final List<Card> all = List.of(card(1), card(2), card(3), card(4), card(5));
            when(cardRepository.findByCardNumberGreaterThanOrderByCardNumberAsc(
                    any(), any(Pageable.class))).thenAnswer(paging(all, Card::getCardNumber));
            final CardReader reader = reader();
            reader.open(new ExecutionContext());

            assertThat(drain(reader::read, Card::getCardNumber))
                    .containsExactly(cardNumber(1), cardNumber(2), cardNumber(3), cardNumber(4), cardNumber(5));
            verify(cardRepository, org.mockito.Mockito.atLeastOnce())
                    .findByCardNumberGreaterThanOrderByCardNumberAsc("", PageRequest.ofSize(PAGE_SIZE));
        }

        @Test
        @DisplayName("end of file is sticky and no further page is requested")
        void endOfFileIsSticky() {
            when(cardRepository.findByCardNumberGreaterThanOrderByCardNumberAsc(
                    any(), any(Pageable.class))).thenAnswer(paging(List.of(card(1)), Card::getCardNumber));
            final CardReader reader = reader();
            reader.open(new ExecutionContext());
            drain(reader::read, Card::getCardNumber);
            final int atEof = org.mockito.Mockito.mockingDetails(cardRepository).getInvocations().size();

            assertThat(reader.read()).isNull();
            assertThat(org.mockito.Mockito.mockingDetails(cardRepository).getInvocations()).hasSize(atEof);
        }

        @Test
        @DisplayName("a restart resumes after the last record handed out")
        void aRestartResumes() {
            final List<Card> all = List.of(card(1), card(2), card(3), card(4));
            when(cardRepository.findByCardNumberGreaterThanOrderByCardNumberAsc(
                    any(), any(Pageable.class))).thenAnswer(paging(all, Card::getCardNumber));
            final CardReader first = reader();
            first.open(new ExecutionContext());
            first.read();
            first.read();
            final ExecutionContext saved = new ExecutionContext();
            first.update(saved);
            first.close();

            final CardReader resumed = reader();
            resumed.open(saved);
            assertThat(drain(resumed::read, Card::getCardNumber)).containsExactly(cardNumber(3), cardNumber(4));
        }

        @Test
        @DisplayName("never writes, and read() before open() is rejected")
        void neverWritesAndRequiresOpen() {
            assertThatIllegalStateException().isThrownBy(() -> reader().read());
            verify(cardRepository, never()).save(any());
            verify(cardRepository, never()).delete(any());
        }

        @Test
        @DisplayName("an I/O failure abends with the CARDFILE message")
        void anIoFailureAbends() {
            when(cardRepository.findByCardNumberGreaterThanOrderByCardNumberAsc(any(), any(Pageable.class)))
                    .thenThrow(new DataAccessResourceFailureException("simulated"));
            final CardReader reader = reader();
            reader.open(new ExecutionContext());

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(reader::read)
                    .withMessageContaining("ERROR READING CARDFILE");
        }

        @Test
        @DisplayName("the page size must be positive and both collaborators are required")
        void constructorGuards() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new CardReader(cardRepository, fileStatusMapper, 0));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new CardReader(null, fileStatusMapper, PAGE_SIZE));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new CardReader(cardRepository, null, PAGE_SIZE));
            assertThat(CardReader.DEFAULT_PAGE_SIZE).isPositive();
        }
    }

    @Nested
    @DisplayName("CardCrossReferenceReader - XREFFILE, ordered by cardNumber (app/cbl/CBACT03C.cbl)")
    class CardCrossReferenceReaderContract {

        private CardCrossReferenceReader reader() {
            return new CardCrossReferenceReader(cardCrossReferenceRepository, fileStatusMapper, PAGE_SIZE);
        }

        @Test
        @DisplayName("reads every record once, ordered by cardNumber, across page boundaries")
        void readsEveryRecordOnceInOrder() {
            final List<CardCrossReference> all =
                    List.of(crossReference(1), crossReference(2), crossReference(3));
            when(cardCrossReferenceRepository.findByCardNumberGreaterThanOrderByCardNumberAsc(
                    any(), any(Pageable.class))).thenAnswer(paging(all, CardCrossReference::getCardNumber));
            final CardCrossReferenceReader reader = reader();
            reader.open(new ExecutionContext());

            assertThat(drain(reader::read, CardCrossReference::getCardNumber))
                    .containsExactly(cardNumber(1), cardNumber(2), cardNumber(3));
            verify(cardCrossReferenceRepository, org.mockito.Mockito.atLeastOnce())
                    .findByCardNumberGreaterThanOrderByCardNumberAsc("", PageRequest.ofSize(PAGE_SIZE));
        }

        @Test
        @DisplayName("an empty file is not an error, and end of file is sticky")
        void anEmptyFileIsNotAnError() {
            when(cardCrossReferenceRepository.findByCardNumberGreaterThanOrderByCardNumberAsc(
                    any(), any(Pageable.class)))
                    .thenAnswer(paging(List.<CardCrossReference>of(), CardCrossReference::getCardNumber));
            final CardCrossReferenceReader reader = reader();

            assertThatCode(() -> reader.open(new ExecutionContext())).doesNotThrowAnyException();
            assertThat(drain(reader::read, CardCrossReference::getCardNumber)).isEmpty();
            assertThat(reader.read()).isNull();
        }

        @Test
        @DisplayName("a restart resumes after the last record handed out")
        void aRestartResumes() {
            final List<CardCrossReference> all =
                    List.of(crossReference(1), crossReference(2), crossReference(3), crossReference(4));
            when(cardCrossReferenceRepository.findByCardNumberGreaterThanOrderByCardNumberAsc(
                    any(), any(Pageable.class))).thenAnswer(paging(all, CardCrossReference::getCardNumber));
            final CardCrossReferenceReader first = reader();
            first.open(new ExecutionContext());
            first.read();
            final ExecutionContext saved = new ExecutionContext();
            first.update(saved);
            first.close();

            final CardCrossReferenceReader resumed = reader();
            resumed.open(saved);
            assertThat(drain(resumed::read, CardCrossReference::getCardNumber))
                    .containsExactly(cardNumber(2), cardNumber(3), cardNumber(4));
        }

        @Test
        @DisplayName("never writes, and read() before open() is rejected")
        void neverWritesAndRequiresOpen() {
            assertThatIllegalStateException().isThrownBy(() -> reader().read());
            verify(cardCrossReferenceRepository, never()).save(any());
            verify(cardCrossReferenceRepository, never()).delete(any());
        }

        @Test
        @DisplayName("an I/O failure abends with the XREFFILE message")
        void anIoFailureAbends() {
            when(cardCrossReferenceRepository.findByCardNumberGreaterThanOrderByCardNumberAsc(
                    any(), any(Pageable.class)))
                    .thenThrow(new DataAccessResourceFailureException("simulated"));
            final CardCrossReferenceReader reader = reader();
            reader.open(new ExecutionContext());

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(reader::read)
                    .withMessageContaining("ERROR READING XREFFILE");
        }

        @Test
        @DisplayName("the page size must be positive and both collaborators are required")
        void constructorGuards() {
            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(
                    () -> new CardCrossReferenceReader(cardCrossReferenceRepository, fileStatusMapper, 0));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new CardCrossReferenceReader(null, fileStatusMapper, PAGE_SIZE));
            assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                    () -> new CardCrossReferenceReader(cardCrossReferenceRepository, null, PAGE_SIZE));
            assertThat(CardCrossReferenceReader.DEFAULT_PAGE_SIZE).isPositive();
        }
    }

    @Nested
    @DisplayName("CustomerReader - CUSTFILE, ordered by customerId (app/cbl/CBCUS01C.cbl)")
    class CustomerReaderContract {

        private CustomerReader reader() {
            return new CustomerReader(customerRepository, fileStatusMapper, PAGE_SIZE);
        }

        @Test
        @DisplayName("reads every record once, ordered by customerId, across page boundaries")
        void readsEveryRecordOnceInOrder() {
            final List<Customer> all = List.of(customer(1), customer(2), customer(3), customer(4), customer(5));
            when(customerRepository.findByCustomerIdGreaterThanOrderByCustomerIdAsc(
                    any(), any(Pageable.class))).thenAnswer(paging(all, Customer::getCustomerId));
            final CustomerReader reader = reader();
            reader.open(new ExecutionContext());

            assertThat(drain(reader::read, Customer::getCustomerId))
                    .containsExactly(Long.valueOf(1), Long.valueOf(2), Long.valueOf(3),
                            Long.valueOf(4), Long.valueOf(5));
            verify(customerRepository, org.mockito.Mockito.atLeastOnce())
                    .findByCustomerIdGreaterThanOrderByCustomerIdAsc(Long.valueOf(-1L), PageRequest.ofSize(PAGE_SIZE));
        }

        @Test
        @DisplayName("a reused instance cold-starts, so no cursor leaks between scans")
        void aReusedInstanceColdStarts() {
            final List<Customer> all = List.of(customer(1), customer(2), customer(3));
            when(customerRepository.findByCustomerIdGreaterThanOrderByCustomerIdAsc(
                    any(), any(Pageable.class))).thenAnswer(paging(all, Customer::getCustomerId));
            final CustomerReader reader = reader();

            reader.open(new ExecutionContext());
            assertThat(drain(reader::read, Customer::getCustomerId)).hasSize(3);
            reader.close();

            reader.open(new ExecutionContext());
            assertThat(drain(reader::read, Customer::getCustomerId))
                    .as("open() cold-starts every cursor field, so the second scan sees the whole file")
                    .hasSize(3);
        }

        @Test
        @DisplayName("open() tolerates a null context and update() tolerates one too")
        void nullContextsAreHandled() {
            when(customerRepository.findByCustomerIdGreaterThanOrderByCustomerIdAsc(
                    any(), any(Pageable.class))).thenAnswer(paging(List.of(customer(1)), Customer::getCustomerId));
            final CustomerReader reader = reader();

            assertThatCode(() -> reader.open(null)).doesNotThrowAnyException();
            assertThat(drain(reader::read, Customer::getCustomerId)).containsExactly(Long.valueOf(1));
            assertThatCode(() -> reader.update(null)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("never writes, close() is idempotent, and read() before open() is rejected")
        void neverWritesAndCloseIsIdempotent() {
            assertThatIllegalStateException().isThrownBy(() -> reader().read());
            final CustomerReader reader = reader();
            when(customerRepository.findByCustomerIdGreaterThanOrderByCustomerIdAsc(
                    any(), any(Pageable.class))).thenAnswer(paging(List.of(customer(1)), Customer::getCustomerId));
            reader.open(new ExecutionContext());
            drain(reader::read, Customer::getCustomerId);

            assertThatCode(() -> {
                reader.close();
                reader.close();
            }).doesNotThrowAnyException();
            verify(customerRepository, never()).save(any());
            verify(customerRepository, never()).delete(any());
        }

        @Test
        @DisplayName("an I/O failure abends with the customer-file message")
        void anIoFailureAbends() {
            when(customerRepository.findByCustomerIdGreaterThanOrderByCustomerIdAsc(any(), any(Pageable.class)))
                    .thenThrow(new DataAccessResourceFailureException("simulated"));
            final CustomerReader reader = reader();
            reader.open(new ExecutionContext());

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(reader::read)
                    .withMessageContaining("ERROR READING CUSTOMER FILE");
        }

        @Test
        @DisplayName("an open failure abends rather than yielding a silently empty scan")
        void anOpenFailureAbends() {
            when(customerRepository.count())
                    .thenThrow(new DataAccessResourceFailureException("simulated"));

            assertThatExceptionOfType(FatalProcessingException.class)
                    .isThrownBy(() -> reader().open(new ExecutionContext()))
                    .withMessageContaining("ERROR OPENING CUSTFILE");
        }

        @Test
        @DisplayName("the page size must be positive and both collaborators are required")
        void constructorGuards() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new CustomerReader(customerRepository, fileStatusMapper, 0));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new CustomerReader(null, fileStatusMapper, PAGE_SIZE));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new CustomerReader(customerRepository, null, PAGE_SIZE));
            assertThat(CustomerReader.DEFAULT_PAGE_SIZE).isPositive();
        }
    }
}

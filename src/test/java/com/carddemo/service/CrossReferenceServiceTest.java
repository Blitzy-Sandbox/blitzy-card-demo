package com.carddemo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import com.carddemo.entity.CardXref;
import com.carddemo.entity.Transaction;
import com.carddemo.exception.ResourceNotFoundException;
import com.carddemo.repository.CardXrefRepository;
import com.carddemo.repository.TransactionRepository;

/**
 * Pure, fast unit tests for {@link CrossReferenceService}, the support service
 * that centralises the COBOL card cross-reference navigation and next
 * transaction-id generation (frozen reference SHA {@code 27d6c6f}, read-only —
 * not copied into this repository).
 *
 * <p>The two collaborating repositories are supplied as Mockito mocks and the
 * service is instantiated directly through its single constructor, so the suite
 * loads no Spring context and touches no database, Testcontainers, Docker, or
 * live AWS. Every branch of the public API is exercised, feeding the JaCoCo
 * line-coverage gate (Gate&nbsp;8).</p>
 *
 * <h2>Behavioural parity assertions</h2>
 * <ul>
 *   <li><b>{@code COACTVWC} paragraph {@code 9200-GETCARDXREF-BYACCT}</b> — the
 *       account&nbsp;&rarr;&nbsp;customer / account&nbsp;&rarr;&nbsp;card resolution.
 *       The not-found path asserts the exact legacy operator message
 *       <em>"Did not find this account in account card xref file"</em> so the
 *       external contract (Gate&nbsp;5) is preserved byte-for-byte.</li>
 *   <li><b>{@code COTRN02C} / {@code COBIL00C} paragraph {@code ADD-TRANSACTION}</b> —
 *       the descending {@code TRANSACT} browse plus one. The empty-table case
 *       asserts the first id is {@code "0000000000000001"} (the COBOL
 *       {@code READPREV} {@code ENDFILE} branch that moves {@code ZEROS} into
 *       {@code TRAN-ID}), and the highest-key browse is verified by capturing the
 *       {@link Pageable} passed to the repository (page&nbsp;0, size&nbsp;1, sort by
 *       {@code tranId} descending).</li>
 * </ul>
 *
 * <p>Only {@link String} and {@link Long} identifier values are used; no
 * {@code float}/{@code double} appears anywhere, consistent with the migration's
 * decimal-fidelity constraints.</p>
 */
@DisplayName("CrossReferenceService — COBOL COACTVWC 9200 / COTRN02C+COBIL00C ADD-TRANSACTION (SHA 27d6c6f)")
class CrossReferenceServiceTest {

    /**
     * The exact legacy operator message emitted by {@code COACTVWC} paragraph
     * {@code 9200-GETCARDXREF-BYACCT} on the not-found branch; the service must
     * reproduce it verbatim.
     */
    private static final String XREF_NOT_FOUND_MESSAGE =
            "Did not find this account in account card xref file";

    private final CardXrefRepository cardXrefRepository = mock(CardXrefRepository.class);
    private final TransactionRepository transactionRepository = mock(TransactionRepository.class);
    private final CrossReferenceService service =
            new CrossReferenceService(cardXrefRepository, transactionRepository);

    /**
     * Builds a {@link CardXref} fixture with the supplied card number, customer
     * id, and account id.
     *
     * @param cardNum the card number ({@code XREF-CARD-NUM})
     * @param custId  the customer id ({@code XREF-CUST-ID})
     * @param acctId  the account id ({@code XREF-ACCT-ID})
     * @return a populated {@link CardXref}
     */
    private static CardXref xref(String cardNum, Long custId, Long acctId) {
        CardXref x = new CardXref();
        x.setXrefCardNum(cardNum);
        x.setXrefCustId(custId);
        x.setXrefAcctId(acctId);
        return x;
    }

    /**
     * Builds a {@link Transaction} fixture carrying only the {@code TRAN-ID} key,
     * which is all the generation logic reads.
     *
     * @param tranId the transaction id key
     * @return a {@link Transaction} with its id set
     */
    private static Transaction txn(String tranId) {
        Transaction t = new Transaction();
        t.setTranId(tranId);
        return t;
    }

    // ------------------------------------------------------------------
    // findByAccount(Long) — COACTVWC 9200-GETCARDXREF-BYACCT (account AIX read)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("findByAccount delegates to CardXrefRepository.findByXrefAcctId and returns its result")
    void findByAccount_delegatesToRepository() {
        CardXref first = xref("1234567890123456", 100L, 55L);
        CardXref second = xref("6543210987654321", 100L, 55L);
        when(cardXrefRepository.findByXrefAcctId(55L)).thenReturn(List.of(first, second));

        List<CardXref> result = service.findByAccount(55L);

        assertThat(result).containsExactly(first, second);
        verify(cardXrefRepository).findByXrefAcctId(55L);
    }

    @Test
    @DisplayName("findByAccount returns an empty list when the account has no cross-reference rows")
    void findByAccount_noRows_returnsEmptyList() {
        when(cardXrefRepository.findByXrefAcctId(999L)).thenReturn(List.of());

        assertThat(service.findByAccount(999L)).isEmpty();
    }

    // ------------------------------------------------------------------
    // resolveCustomerId(Long) — moves XREF-CUST-ID; NOTFND -> legacy message
    // ------------------------------------------------------------------

    @Test
    @DisplayName("resolveCustomerId returns the first cross-reference row's customer id")
    void resolveCustomerId_present_returnsFirstCustId() {
        when(cardXrefRepository.findByXrefAcctId(55L)).thenReturn(List.of(
                xref("1234567890123456", 100L, 55L),
                xref("6543210987654321", 200L, 55L)));

        assertThat(service.resolveCustomerId(55L)).isEqualTo(100L);
    }

    @Test
    @DisplayName("resolveCustomerId throws ResourceNotFoundException with the exact legacy message when no xref exists")
    void resolveCustomerId_empty_throwsWithLegacyMessage() {
        when(cardXrefRepository.findByXrefAcctId(999L)).thenReturn(List.of());

        assertThatThrownBy(() -> service.resolveCustomerId(999L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage(XREF_NOT_FOUND_MESSAGE);
    }

    // ------------------------------------------------------------------
    // resolvePrimaryCardNumber(Long) — moves XREF-CARD-NUM; NOTFND -> message
    // ------------------------------------------------------------------

    @Test
    @DisplayName("resolvePrimaryCardNumber returns the first cross-reference row's card number")
    void resolvePrimaryCardNumber_present_returnsFirstCardNum() {
        when(cardXrefRepository.findByXrefAcctId(55L)).thenReturn(List.of(
                xref("1234567890123456", 100L, 55L),
                xref("6543210987654321", 200L, 55L)));

        assertThat(service.resolvePrimaryCardNumber(55L)).isEqualTo("1234567890123456");
    }

    @Test
    @DisplayName("resolvePrimaryCardNumber throws ResourceNotFoundException with the exact legacy message when no xref exists")
    void resolvePrimaryCardNumber_empty_throwsWithLegacyMessage() {
        when(cardXrefRepository.findByXrefAcctId(999L)).thenReturn(List.of());

        assertThatThrownBy(() -> service.resolvePrimaryCardNumber(999L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage(XREF_NOT_FOUND_MESSAGE);
    }

    // ------------------------------------------------------------------
    // generateNextTransactionId() — COTRN02C/COBIL00C ADD-TRANSACTION
    // ------------------------------------------------------------------

    @Test
    @DisplayName("generateNextTransactionId returns 0000000000000001 when the transaction table is empty")
    void generateNextTransactionId_emptyTable_returnsOnePadded() {
        when(transactionRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<Transaction>(List.of()));

        assertThat(service.generateNextTransactionId()).isEqualTo("0000000000000001");
    }

    @Test
    @DisplayName("generateNextTransactionId returns the highest existing id plus one, zero-padded to 16 digits")
    void generateNextTransactionId_existing_returnsMaxPlusOne() {
        when(transactionRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<Transaction>(List.of(txn("0000000000000041"))));

        assertThat(service.generateNextTransactionId()).isEqualTo("0000000000000042");
    }

    @Test
    @DisplayName("generateNextTransactionId trims fixed-width padding before parsing the highest id")
    void generateNextTransactionId_paddedId_isTrimmedThenIncremented() {
        when(transactionRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<Transaction>(List.of(txn("   99   "))));

        assertThat(service.generateNextTransactionId()).isEqualTo("0000000000000100");
    }

    @Test
    @DisplayName("generateNextTransactionId browses for the highest key: page 0, size 1, sorted by tranId descending")
    void generateNextTransactionId_requestsHighestKeyPage() {
        when(transactionRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<Transaction>(List.of(txn("0000000000000010"))));

        service.generateNextTransactionId();

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(transactionRepository).findAll(captor.capture());
        Pageable requested = captor.getValue();
        assertThat(requested.getPageNumber()).isZero();
        assertThat(requested.getPageSize()).isEqualTo(1);
        Sort.Order order = requested.getSort().getOrderFor("tranId");
        assertThat(order).isNotNull();
        assertThat(order.getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    @DisplayName("generateNextTransactionId always returns a 16-character string")
    void generateNextTransactionId_resultIsSixteenChars() {
        when(transactionRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<Transaction>(List.of(txn("0000000000000007"))));

        String next = service.generateNextTransactionId();

        assertThat(next).hasSize(16).isEqualTo("0000000000000008");
    }
}

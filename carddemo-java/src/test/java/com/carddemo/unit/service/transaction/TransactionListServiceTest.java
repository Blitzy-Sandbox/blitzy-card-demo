package com.carddemo.unit.service.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.exception.ValidationException;
import com.carddemo.model.dto.TransactionListResponse;
import com.carddemo.model.entity.Transaction;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.service.transaction.TransactionListService;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/** Unit tests for {@link TransactionListService}, the Java re-platform of COBOL online program COTRN00C (source commit {@code 27d6c6f}). */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionListService - COTRN00C paginated transaction browse (10 rows/page)")
class TransactionListServiceTest {

    private static final String ORIG_TS = "2022-07-19 12:00:00.000000";

    @Mock
    private TransactionRepository transactionRepository;

    @InjectMocks
    private TransactionListService service;

    // ----------------------------------------------------------------------------------------
    // Fixtures and reusable assertions
    // ----------------------------------------------------------------------------------------

    private static Transaction newTransaction(String id, String origTs, String desc, BigDecimal amt) {
        Transaction tx = new Transaction();
        tx.setTranId(id);
        tx.setTranOrigTs(origTs);
        tx.setTranDesc(desc);
        tx.setTranAmt(amt);
        return tx;
    }

    private static PageImpl<Transaction> emptyPage() {
        return new PageImpl<>(List.of());
    }

    private static void assertAmount(BigDecimal actual, String expected) {
        assertThat(actual).isEqualByComparingTo(new BigDecimal(expected));
        assertThat(actual.scale()).isEqualTo(2);
    }

    // ----------------------------------------------------------------------------------------
    // Tests
    // ----------------------------------------------------------------------------------------

    @Test
    @DisplayName("Maps each row to an item, formats the date, and echoes page number and empty filter")
    void happyPath_mapsRowsAndPaging() {
        Transaction tx1 = newTransaction("0000000000000001", ORIG_TS, "PURCHASE ONE", new BigDecimal("10.00"));
        Transaction tx2 = newTransaction("0000000000000002", ORIG_TS, "REFUND TWO", new BigDecimal("-5.50"));
        PageImpl<Transaction> page = new PageImpl<>(List.of(tx1, tx2));
        when(transactionRepository.findAll(any(Pageable.class))).thenReturn(page);

        TransactionListResponse response = service.listTransactions(0, null);

        assertThat(response.transactions()).hasSize(2);

        TransactionListResponse.TransactionListItem first = response.transactions().get(0);
        assertThat(first.selectionFlag()).isEmpty();
        assertThat(first.transactionId()).isEqualTo(tx1.getTranId());
        assertThat(first.date()).isEqualTo("07/19/22");
        assertThat(first.description()).isEqualTo(tx1.getTranDesc());
        assertAmount(first.amount(), "10.00");

        TransactionListResponse.TransactionListItem second = response.transactions().get(1);
        assertThat(second.transactionId()).isEqualTo(tx2.getTranId());
        assertAmount(second.amount(), "-5.50");

        assertThat(response.pageNumber()).isEqualTo("1");
        assertThat(response.transactionIdFilter()).isEmpty();
        assertThat(response.errorMessage()).isNull();
    }

    @Test
    @DisplayName("Requests the page index, a fixed size of 10, and tranId ASC sort (10-rows-per-page parity)")
    void paging_capturesPageRequest() {
        when(transactionRepository.findAll(any(Pageable.class))).thenReturn(emptyPage());

        service.listTransactions(2, null);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(transactionRepository).findAll(captor.capture());

        Pageable pageable = captor.getValue();
        assertThat(pageable.getPageNumber()).isEqualTo(2);
        assertThat(pageable.getPageSize()).isEqualTo(10);

        Sort.Order order = pageable.getSort().getOrderFor("tranId");
        assertThat(order).isNotNull();
        assertThat(order.getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    @Test
    @DisplayName("Clamps a negative page index to the first page (Math.max(page, 0))")
    void negativePage_clampedToZero() {
        when(transactionRepository.findAll(any(Pageable.class))).thenReturn(emptyPage());

        TransactionListResponse response = service.listTransactions(-3, null);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(transactionRepository).findAll(captor.capture());
        assertThat(captor.getValue().getPageNumber()).isEqualTo(0);
        assertThat(response.pageNumber()).isEqualTo("1");
    }

    @Test
    @DisplayName("A full page never yields more than 10 rows")
    void atMostTenRowsPerPage() {
        List<Transaction> tenRows = IntStream.rangeClosed(1, 10)
                .mapToObj(i -> newTransaction(
                        String.format("%016d", i), ORIG_TS, "DESC " + i, new BigDecimal("1.00")))
                .toList();
        when(transactionRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(tenRows));

        TransactionListResponse response = service.listTransactions(0, null);

        assertThat(response.transactions()).hasSize(10);
        assertThat(response.transactions().size()).isLessThanOrEqualTo(10);
    }

    @Test
    @DisplayName("An empty store yields an empty row list with no error")
    void emptyPage_returnsEmptyList() {
        when(transactionRepository.findAll(any(Pageable.class))).thenReturn(emptyPage());

        TransactionListResponse response = service.listTransactions(0, null);

        assertThat(response.transactions()).isEmpty();
        assertThat(response.pageNumber()).isEqualTo("1");
        assertThat(response.errorMessage()).isNull();
    }

    @Test
    @DisplayName("An all-numeric start key positions the browse via the GTEQ finder (10 rows, tranId ASC) and is echoed back")
    void filterNumeric_positionsBrowseViaGteqFinder() {
        Transaction tx2 = newTransaction("0000000000000002", ORIG_TS, "REFUND TWO", new BigDecimal("-5.50"));
        when(transactionRepository.findByTranIdGreaterThanEqual(eq("0000000000000002"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(tx2)));

        TransactionListResponse response = service.listTransactions(0, "0000000000000002");

        // COTRN00C PROCESS-ENTER-KEY + STARTBR GTEQ: the browse is positioned at the
        // supplied key via the GTEQ finder, NOT browsed from the start via findAll.
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(transactionRepository).findByTranIdGreaterThanEqual(eq("0000000000000002"), captor.capture());
        verify(transactionRepository, never()).findAll(any(Pageable.class));

        Pageable pageable = captor.getValue();
        assertThat(pageable.getPageSize()).isEqualTo(10);
        Sort.Order order = pageable.getSort().getOrderFor("tranId");
        assertThat(order).isNotNull();
        assertThat(order.getDirection()).isEqualTo(Sort.Direction.ASC);

        assertThat(response.transactions()).hasSize(1);
        assertThat(response.transactions().get(0).transactionId()).isEqualTo("0000000000000002");
        assertThat(response.transactionIdFilter()).isEqualTo("0000000000000002");
    }

    @ParameterizedTest
    @ValueSource(strings = {"12A", "abc"})
    @DisplayName("A non-numeric start key is rejected before the store is queried")
    void filterNonNumeric_throwsValidationException(String input) {
        assertThatThrownBy(() -> service.listTransactions(0, input))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Tran ID must be Numeric ...");

        verifyNoInteractions(transactionRepository);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    @DisplayName("A null, empty, or blank filter passes validation and is echoed (null becomes empty)")
    void filterBlankOrNull_noValidationError(String input) {
        when(transactionRepository.findAll(any(Pageable.class))).thenReturn(emptyPage());

        TransactionListResponse response = service.listTransactions(0, input);

        verify(transactionRepository).findAll(any(Pageable.class));
        String expectedEcho = input == null ? "" : input;
        assertThat(response.transactionIdFilter()).isEqualTo(expectedEcho);
    }

    @Test
    @DisplayName("A null or too-short origination timestamp formats to an empty display date")
    void dateFormatting_shortOrNull_returnsEmpty() {
        Transaction nullTs = newTransaction("0000000000000001", null, "NULL TS", new BigDecimal("1.00"));
        Transaction shortTs = newTransaction("0000000000000002", "2022", "SHORT TS", new BigDecimal("1.00"));
        when(transactionRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(nullTs, shortTs)));

        TransactionListResponse response = service.listTransactions(0, null);

        assertThat(response.transactions()).hasSize(2);
        assertThat(response.transactions().get(0).date()).isEmpty();
        assertThat(response.transactions().get(1).date()).isEmpty();
    }
}

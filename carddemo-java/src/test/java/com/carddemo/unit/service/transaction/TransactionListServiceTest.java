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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Unit tests for {@link TransactionListService} (COBOL {@code COTRN00C} parity): ten-rows-per-page
 * browse, the optional numeric tran-id filter edit, the {@code MM/DD/YY} display-date derivation,
 * and exact {@link BigDecimal} amounts.
 */
@ExtendWith(MockitoExtension.class)
class TransactionListServiceTest {

    @Mock
    private TransactionRepository transactionRepository;

    private TransactionListService service() {
        return new TransactionListService(transactionRepository);
    }

    private static Transaction tx(String id, String origTs, String desc, String amt) {
        Transaction t = new Transaction();
        t.setTranId(id);
        t.setTranOrigTs(origTs);
        t.setTranDesc(desc);
        t.setTranAmt(new BigDecimal(amt));
        return t;
    }

    @Test
    void nonNumericFilterRejected() {
        assertThatThrownBy(() -> service().listTransactions(0, "12A"))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Tran ID must be Numeric ...");
    }

    @Test
    void pageMapsRowsWithDisplayDateAndExactAmount() {
        Pageable pageable = PageRequest.of(0, 10);
        List<Transaction> content = List.of(
                tx("0000000000000001", "2026-08-15-12.30.00.000000", "PURCHASE", "12.34"),
                tx("0000000000000002", "2026-12-01-00.00.00.000000", "REFUND", "-5.00"));
        when(transactionRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(content, pageable, 2));

        TransactionListResponse response = service().listTransactions(0, null);

        assertThat(response.pageNumber()).isEqualTo("1");
        assertThat(response.transactionIdFilter()).isEmpty();
        assertThat(response.transactions()).hasSize(2);
        TransactionListResponse.TransactionListItem first = response.transactions().get(0);
        assertThat(first.selectionFlag()).isEmpty();
        assertThat(first.transactionId()).isEqualTo("0000000000000001");
        assertThat(first.date()).isEqualTo("08/15/26");
        assertThat(first.amount()).isEqualByComparingTo("12.34");
    }

    @Test
    void negativePageRejectedWith400() {
        // Issue 5: an invalid (negative) zero-based page must be rejected with HTTP 400, not
        // silently coerced to the first page. No repository access occurs.
        assertThatThrownBy(() -> service().listTransactions(-3, "123"))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Page number must be zero or greater");
        verifyNoInteractions(transactionRepository);
    }

    @Test
    void filterAppliedAsStartAtLeftPaddedBeforePaging() {
        // Issue 4: COTRN00C STARTBR positions on the entered id with GTEQ and reads forward, so the
        // filter is a "start-at" lower bound applied before paging. A short numeric filter ("15") is
        // left-padded to the stored 16-digit width ("0000000000000015") so the lexicographic >=
        // comparison equals a numeric >= comparison. The whole-table findAll must NOT be used.
        Pageable pageable = PageRequest.of(0, 10);
        List<Transaction> content = List.of(
                tx("0000000000000015", "2026-08-15-12.30.00.000000", "PURCHASE", "12.34"));
        when(transactionRepository.findByTranIdGreaterThanEqual(eq("0000000000000015"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(content, pageable, 1));

        TransactionListResponse response = service().listTransactions(0, "15");

        assertThat(response.transactionIdFilter()).isEqualTo("15");
        assertThat(response.transactions()).hasSize(1);
        assertThat(response.transactions().get(0).transactionId()).isEqualTo("0000000000000015");
        verify(transactionRepository).findByTranIdGreaterThanEqual(eq("0000000000000015"), any(Pageable.class));
        verify(transactionRepository, never()).findAll(any(Pageable.class));
    }

    @Test
    void hugePageClampedToMaxSafeOffset() {
        // Before the fix, page=300000000 * size 10 = 3_000_000_000 > Integer.MAX_VALUE, so Spring
        // Data raised InvalidDataAccessApiUsageException which surfaced as an unhandled HTTP 500.
        Pageable pageable = PageRequest.of(0, 10);
        when(transactionRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        TransactionListResponse response = service().listTransactions(300_000_000, null);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(transactionRepository).findAll(captor.capture());
        int clampedPage = captor.getValue().getPageNumber();
        assertThat(clampedPage).isEqualTo(Integer.MAX_VALUE / 10);
        assertThat((long) clampedPage * 10).isLessThanOrEqualTo(Integer.MAX_VALUE);
        // The service returns a graceful empty page rather than throwing.
        assertThat(response.transactions()).isEmpty();
    }

    @Test
    void shortTimestampYieldsBlankDisplayDate() {
        Pageable pageable = PageRequest.of(0, 10);
        when(transactionRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(tx("0000000000000003", "2026", "X", "1.00")), pageable, 1));

        TransactionListResponse response = service().listTransactions(0, "");

        assertThat(response.transactions().get(0).date()).isEmpty();
    }
}

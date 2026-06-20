package com.carddemo.unit.service.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
    void negativePageClampedAndFilterEchoed() {
        Pageable pageable = PageRequest.of(0, 10);
        when(transactionRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        TransactionListResponse response = service().listTransactions(-3, "123");

        assertThat(response.pageNumber()).isEqualTo("1");
        assertThat(response.transactionIdFilter()).isEqualTo("123");
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

package com.carddemo.unit.service.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.model.dto.TransactionDetailResponse;
import com.carddemo.model.entity.Transaction;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.service.transaction.TransactionDetailService;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link TransactionDetailService} (COBOL {@code COTRN01C} parity): empty-key edit,
 * the direct keyed read, the not-found path, and the zero-padded category/merchant formatting.
 */
@ExtendWith(MockitoExtension.class)
class TransactionDetailServiceTest {

    private static final String TRAN_ID = "0000000000000001";

    @Mock
    private TransactionRepository transactionRepository;

    private TransactionDetailService service() {
        return new TransactionDetailService(transactionRepository);
    }

    @Test
    void blankKeyRejected() {
        assertThatThrownBy(() -> service().getTransaction("  "))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Tran ID can NOT be empty...");
    }

    @Test
    void nullKeyRejected() {
        assertThatThrownBy(() -> service().getTransaction(null))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Tran ID can NOT be empty...");
    }

    @Test
    void notFoundRejected() {
        when(transactionRepository.findById(TRAN_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service().getTransaction(TRAN_ID))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage("Transaction ID NOT found...");
    }

    @Test
    void successPopulatesResponseWithZeroPaddedFields() {
        Transaction t = new Transaction();
        t.setTranId(TRAN_ID);
        t.setTranCardNum("4111111111111111");
        t.setTranTypeCd("01");
        t.setTranCatCd(5);
        t.setTranSource("POS");
        t.setTranDesc("PURCHASE");
        t.setTranAmt(new BigDecimal("99.99"));
        t.setTranOrigTs("2026-08-15-12.30.00.000000");
        t.setTranProcTs("2026-08-16-01.00.00.000000");
        t.setTranMerchantId(42L);
        t.setTranMerchantName("ACME");
        t.setTranMerchantCity("ANYTOWN");
        t.setTranMerchantZip("12345");
        when(transactionRepository.findById(TRAN_ID)).thenReturn(Optional.of(t));

        TransactionDetailResponse response = service().getTransaction(TRAN_ID);

        assertThat(response.transactionIdInput()).isEqualTo(TRAN_ID);
        assertThat(response.transactionId()).isEqualTo(TRAN_ID);
        assertThat(response.categoryCode()).isEqualTo("0005");
        assertThat(response.merchantId()).isEqualTo("000000042");
        assertThat(response.amount()).isEqualByComparingTo("99.99");
        assertThat(response.merchantName()).isEqualTo("ACME");
        assertThat(response.errorMessage()).isNull();
    }
}

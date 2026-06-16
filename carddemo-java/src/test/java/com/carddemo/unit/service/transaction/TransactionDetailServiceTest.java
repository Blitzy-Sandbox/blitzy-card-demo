package com.carddemo.unit.service.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.model.dto.TransactionDetailResponse;
import com.carddemo.model.entity.Transaction;
import com.carddemo.repository.TransactionRepository;
import com.carddemo.service.transaction.TransactionDetailService;
import java.math.BigDecimal;
import java.util.Optional;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link TransactionDetailService} (COBOL COTRN01C, source commit 27d6c6f). */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionDetailService - COTRN01C keyed transaction-detail read")
class TransactionDetailServiceTest {

    /** Sixteen-character transaction key (TRAN-ID PIC X(16)) reused across the lookup tests. */
    private static final String TXN_ID = "0000000000000099";

    @Mock
    private TransactionRepository transactionRepository;

    @InjectMocks
    private TransactionDetailService service;

    @Test
    @DisplayName("found: maps every persisted field onto the response (zero-padded code/merchant, BigDecimal amount)")
    void found_returnsMappedResponse() {
        Transaction tx = newTransaction(TXN_ID, "01", 7, "POS", "GROCERY PURCHASE",
                new BigDecimal("123.45"), 42L, "ACME STORE", "SPRINGFIELD", "62704-0001",
                "1234567890123456", "2023-01-15-09.30.00.000000", "2023-01-16-02.00.00.000000");
        when(transactionRepository.findById(TXN_ID)).thenReturn(Optional.of(tx));

        TransactionDetailResponse response = service.getTransaction(TXN_ID);

        assertThat(response.transactionIdInput()).isEqualTo(TXN_ID);
        assertThat(response.transactionId()).isEqualTo(tx.getTranId());
        assertThat(response.cardNumber()).isEqualTo(tx.getTranCardNum());
        assertThat(response.typeCode()).isEqualTo(tx.getTranTypeCd());
        assertThat(response.categoryCode()).isEqualTo("0007");
        assertThat(response.source()).isEqualTo(tx.getTranSource());
        assertThat(response.description()).isEqualTo(tx.getTranDesc());
        assertThat(response.amount()).isEqualByComparingTo(new BigDecimal("123.45"));
        assertThat(response.amount().scale()).isEqualTo(2);
        assertThat(response.originDate()).isEqualTo(tx.getTranOrigTs());
        assertThat(response.processDate()).isEqualTo(tx.getTranProcTs());
        assertThat(response.merchantId()).isEqualTo("000000042");
        assertThat(response.merchantName()).isEqualTo(tx.getTranMerchantName());
        assertThat(response.merchantCity()).isEqualTo(tx.getTranMerchantCity());
        assertThat(response.merchantZip()).isEqualTo(tx.getTranMerchantZip());
        assertThat(response.errorMessage()).isNull();

        verify(transactionRepository).findById(TXN_ID);
    }

    @Test
    @DisplayName("found: a negative amount passes through unchanged with scale 2 preserved")
    void found_negativeAmount_passthrough() {
        Transaction tx = newTransaction(TXN_ID, "02", 12, "ADJ", "REFUND ADJUSTMENT",
                new BigDecimal("-42.50"), 7L, "ACME STORE", "SPRINGFIELD", "62704",
                "1234567890123456", "2023-02-01-12.00.00.000000", "2023-02-02-02.00.00.000000");
        when(transactionRepository.findById(TXN_ID)).thenReturn(Optional.of(tx));

        TransactionDetailResponse response = service.getTransaction(TXN_ID);

        assertThat(response.amount()).isEqualByComparingTo(new BigDecimal("-42.50"));
        assertThat(response.amount().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("not found: an absent key raises RecordNotFoundException carrying FILE STATUS 23")
    void notFound_throwsRecordNotFoundException() {
        when(transactionRepository.findById(TXN_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getTransaction(TXN_ID))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessage("Transaction ID NOT found...")
                .asInstanceOf(InstanceOfAssertFactories.type(RecordNotFoundException.class))
                .extracting(RecordNotFoundException::getFileStatus)
                .isEqualTo("23");

        verify(transactionRepository).findById(TXN_ID);
    }

    @ParameterizedTest(name = "[{index}] blank id is rejected before any repository access")
    @NullSource
    @EmptySource
    @ValueSource(strings = {"   ", "\t", "\n"})
    @DisplayName("empty/blank id: rejected with ValidationException and the repository is never touched")
    void emptyKey_throwsValidationException(String transactionId) {
        assertThatThrownBy(() -> service.getTransaction(transactionId))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Tran ID can NOT be empty...");

        verifyNoInteractions(transactionRepository);
    }

    /**
     * Builds a fully populated {@link Transaction} fixture via the no-arg constructor and setters.
     *
     * @param tranId       transaction identifier
     * @param typeCd       transaction type code
     * @param catCd        transaction category code
     * @param source       transaction source
     * @param desc         transaction description
     * @param amount       transaction amount (may be negative; scale preserved)
     * @param merchantId   merchant identifier
     * @param merchantName merchant name
     * @param merchantCity merchant city
     * @param merchantZip  merchant ZIP code
     * @param cardNum      associated card number
     * @param origTs       origination timestamp
     * @param procTs       processing timestamp
     * @return the populated transaction fixture
     */
    private static Transaction newTransaction(String tranId, String typeCd, Integer catCd, String source,
            String desc, BigDecimal amount, Long merchantId, String merchantName, String merchantCity,
            String merchantZip, String cardNum, String origTs, String procTs) {
        Transaction tx = new Transaction();
        tx.setTranId(tranId);
        tx.setTranTypeCd(typeCd);
        tx.setTranCatCd(catCd);
        tx.setTranSource(source);
        tx.setTranDesc(desc);
        tx.setTranAmt(amount);
        tx.setTranMerchantId(merchantId);
        tx.setTranMerchantName(merchantName);
        tx.setTranMerchantCity(merchantCity);
        tx.setTranMerchantZip(merchantZip);
        tx.setTranCardNum(cardNum);
        tx.setTranOrigTs(origTs);
        tx.setTranProcTs(procTs);
        return tx;
    }
}

package com.carddemo.unit.service.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.model.entity.Transaction;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Transaction} (REFERENCE-ONLY, COBOL not copied; source commit 27d6c6f).
 * Verifies CVTRA05Y round-trip, signed scale-2 tranAmt (compareTo, never equals), and identity
 * over tranId per AAP {@code §0.8.2}.
 */
@DisplayName("Transaction entity — CVTRA05Y mapping, signed scale-2 amount, identity by tranId")
class TransactionTest {

    private Transaction transaction;

    @BeforeEach
    void setUp() {
        transaction = new Transaction();
    }

    @Test
    @DisplayName("scalar fields round-trip through getters/setters")
    void scalarFieldsRoundTrip() {
        transaction.setTranId("0000000000000001");
        transaction.setTranTypeCd("01");
        transaction.setTranCatCd(5);
        transaction.setTranSource("POS TERM");
        transaction.setTranDesc("PURCHASE");
        transaction.setTranMerchantId(123456789L);
        transaction.setTranMerchantName("ACME STORE");
        transaction.setTranMerchantCity("AUSTIN");
        transaction.setTranMerchantZip("73301");
        transaction.setTranCardNum("4111111111111111");
        transaction.setTranOrigTs("2024-01-01-00.00.00.000000");
        transaction.setTranProcTs("2024-01-02-00.00.00.000000");

        assertThat(transaction.getTranId()).isEqualTo("0000000000000001");
        assertThat(transaction.getTranTypeCd()).isEqualTo("01");
        assertThat(transaction.getTranCatCd()).isEqualTo(5);
        assertThat(transaction.getTranSource()).isEqualTo("POS TERM");
        assertThat(transaction.getTranDesc()).isEqualTo("PURCHASE");
        assertThat(transaction.getTranMerchantId()).isEqualTo(123456789L);
        assertThat(transaction.getTranMerchantName()).isEqualTo("ACME STORE");
        assertThat(transaction.getTranMerchantCity()).isEqualTo("AUSTIN");
        assertThat(transaction.getTranMerchantZip()).isEqualTo("73301");
        assertThat(transaction.getTranCardNum()).isEqualTo("4111111111111111");
        assertThat(transaction.getTranOrigTs()).isEqualTo("2024-01-01-00.00.00.000000");
        assertThat(transaction.getTranProcTs()).isEqualTo("2024-01-02-00.00.00.000000");
    }

    @Test
    @DisplayName("a positive tranAmt preserves scale 2 and compares by value")
    void positiveAmountPreservesScaleTwo() {
        transaction.setTranAmt(new BigDecimal("1234.56"));
        assertThat(transaction.getTranAmt().scale()).isEqualTo(2);
        assertThat(transaction.getTranAmt()).isEqualByComparingTo("1234.56");
    }

    @Test
    @DisplayName("a negative tranAmt (S9(09)V99 is signed) preserves sign and scale 2")
    void negativeAmountPreservesSignAndScale() {
        transaction.setTranAmt(new BigDecimal("-919.00"));
        assertThat(transaction.getTranAmt().scale()).isEqualTo(2);
        assertThat(transaction.getTranAmt()).isEqualByComparingTo("-919.00");
        assertThat(transaction.getTranAmt().compareTo(BigDecimal.ZERO)).isNegative();
    }

    @Test
    @DisplayName("identity is by tranId only: same id equal even when amounts differ")
    void identityBySameTranIdRegardlessOfAmount() {
        transaction.setTranId("0000000000000001");
        transaction.setTranAmt(new BigDecimal("10.00"));
        Transaction other = new Transaction();
        other.setTranId("0000000000000001");
        other.setTranAmt(new BigDecimal("-99.99"));

        assertThat(transaction).isEqualTo(other);
        assertThat(transaction).hasSameHashCodeAs(other);
    }

    @Test
    @DisplayName("different tranId breaks equality; equals(null)/equals(other type) are false")
    void differentIdNotEqualAndNullSafe() {
        transaction.setTranId("0000000000000001");
        Transaction other = new Transaction();
        other.setTranId("0000000000000002");

        assertThat(transaction).isNotEqualTo(other);
        assertThat(transaction.equals(null)).isFalse();
        assertThat(transaction.equals("not-a-transaction")).isFalse();
    }
}

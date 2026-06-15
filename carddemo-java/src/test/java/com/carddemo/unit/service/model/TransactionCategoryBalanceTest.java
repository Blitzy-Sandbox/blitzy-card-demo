package com.carddemo.unit.service.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.model.entity.TransactionCategoryBalance;
import com.carddemo.model.key.TransactionCategoryBalanceId;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TransactionCategoryBalance} (REFERENCE-ONLY, COBOL not copied; source commit 27d6c6f).
 * Verifies CVTRA01Y @EmbeddedId wiring, scale-2 tranCatBal (compareTo), and identity by composite id
 * per AAP {@code §0.8.2}.
 */
@DisplayName("TransactionCategoryBalance entity — CVTRA01Y embedded id, scale-2 balance")
class TransactionCategoryBalanceTest {

    @Test
    @DisplayName("embedded id and balance round-trip; balance preserves scale 2")
    void roundTripWithScaleTwoBalance() {
        TransactionCategoryBalanceId id = new TransactionCategoryBalanceId(1001L, "01", 5);
        TransactionCategoryBalance bal = new TransactionCategoryBalance();
        bal.setId(id);
        bal.setTranCatBal(new BigDecimal("250.00"));

        assertThat(bal.getId()).isEqualTo(id);
        assertThat(bal.getTranCatBal().scale()).isEqualTo(2);
        assertThat(bal.getTranCatBal()).isEqualByComparingTo("250.00");
    }

    @Test
    @DisplayName("identity is by embedded id; equals(null)/equals(other type) are false")
    void identityByEmbeddedId() {
        TransactionCategoryBalance a = new TransactionCategoryBalance();
        a.setId(new TransactionCategoryBalanceId(1001L, "01", 5));
        a.setTranCatBal(new BigDecimal("1.00"));
        TransactionCategoryBalance b = new TransactionCategoryBalance();
        b.setId(new TransactionCategoryBalanceId(1001L, "01", 5));
        b.setTranCatBal(new BigDecimal("999.99"));
        TransactionCategoryBalance c = new TransactionCategoryBalance();
        c.setId(new TransactionCategoryBalanceId(2002L, "01", 5));

        assertThat(a).isEqualTo(b);
        assertThat(a).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(c);
        assertThat(a.equals(null)).isFalse();
        assertThat(a.equals("nope")).isFalse();
    }
}

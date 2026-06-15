package com.carddemo.unit.service.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.model.entity.Account;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Account} (REFERENCE-ONLY, COBOL not copied; source commit 27d6c6f).
 * Verifies CVACT01Y round-trip, BigDecimal scale-2 fidelity (compareTo, never equals), and identity
 * over acctId per AAP {@code §0.8.2}.
 */
@DisplayName("Account entity — CVACT01Y mapping, scale-2 money, identity by acctId")
class AccountTest {

    private Account account;

    @BeforeEach
    void setUp() {
        account = new Account();
    }

    @Test
    @DisplayName("scalar fields round-trip through getters/setters (incl. misspelled acctExpiraionDate)")
    void scalarFieldsRoundTrip() {
        account.setAcctId(12345678901L);
        account.setAcctActiveStatus("Y");
        account.setAcctOpenDate("2020-01-01");
        account.setAcctExpiraionDate("2030-01-01");
        account.setAcctReissueDate("2025-01-01");
        account.setAcctAddrZip("90210");
        account.setAcctGroupId("GROUP00001");
        account.setVersion(0L);

        assertThat(account.getAcctId()).isEqualTo(12345678901L);
        assertThat(account.getAcctActiveStatus()).isEqualTo("Y");
        assertThat(account.getAcctOpenDate()).isEqualTo("2020-01-01");
        assertThat(account.getAcctExpiraionDate()).isEqualTo("2030-01-01");
        assertThat(account.getAcctReissueDate()).isEqualTo("2025-01-01");
        assertThat(account.getAcctAddrZip()).isEqualTo("90210");
        assertThat(account.getAcctGroupId()).isEqualTo("GROUP00001");
        assertThat(account.getVersion()).isEqualTo(0L);
    }

    @Test
    @DisplayName("all five S9(10)V99 money fields preserve scale 2 and compare by value")
    void moneyFieldsPreserveScaleTwo() {
        account.setAcctCurrBal(new BigDecimal("1234567890.12"));
        account.setAcctCreditLimit(new BigDecimal("5000.00"));
        account.setAcctCashCreditLimit(new BigDecimal("1000.00"));
        account.setAcctCurrCycCredit(new BigDecimal("250.50"));
        account.setAcctCurrCycDebit(new BigDecimal("75.25"));

        assertThat(account.getAcctCurrBal().scale()).isEqualTo(2);
        assertThat(account.getAcctCreditLimit().scale()).isEqualTo(2);
        assertThat(account.getAcctCashCreditLimit().scale()).isEqualTo(2);
        assertThat(account.getAcctCurrCycCredit().scale()).isEqualTo(2);
        assertThat(account.getAcctCurrCycDebit().scale()).isEqualTo(2);

        // Value equality via compareTo (isEqualByComparingTo), never equals (scale-sensitive).
        assertThat(account.getAcctCurrBal()).isEqualByComparingTo("1234567890.12");
        assertThat(account.getAcctCreditLimit()).isEqualByComparingTo(new BigDecimal("5000.00"));
        assertThat(account.getAcctCurrBal().compareTo(new BigDecimal("1234567890.12"))).isZero();
    }

    @Test
    @DisplayName("a signed (negative) balance preserves both sign and scale 2")
    void negativeBalancePreservesSignAndScale() {
        account.setAcctCurrBal(new BigDecimal("-42.50"));
        assertThat(account.getAcctCurrBal().scale()).isEqualTo(2);
        assertThat(account.getAcctCurrBal()).isEqualByComparingTo("-42.50");
        assertThat(account.getAcctCurrBal().signum()).isEqualTo(-1);
    }

    @Test
    @DisplayName("identity is by acctId only: same id equal even when balances differ")
    void identityBySameAcctIdRegardlessOfBalance() {
        account.setAcctId(1000000001L);
        account.setAcctCurrBal(new BigDecimal("100.00"));
        Account other = new Account();
        other.setAcctId(1000000001L);
        other.setAcctCurrBal(new BigDecimal("999.99"));

        assertThat(account).isEqualTo(other);
        assertThat(account).hasSameHashCodeAs(other);
    }

    @Test
    @DisplayName("different acctId breaks equality; equals(null)/equals(other type) are false")
    void differentIdNotEqualAndNullSafe() {
        account.setAcctId(1000000001L);
        Account other = new Account();
        other.setAcctId(2000000002L);

        assertThat(account).isNotEqualTo(other);
        assertThat(account.equals(null)).isFalse();
        assertThat(account.equals("not-an-account")).isFalse();
    }
}

package com.cardemo.unit.batch.processors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cardemo.batch.processors.InterestCalculationProcessor;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.model.dto.InterestCalculationResult;
import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.DisclosureGroup;
import com.cardemo.model.entity.TransactionCategoryBalance;
import com.cardemo.model.key.DisclosureGroupId;
import com.cardemo.model.key.TransactionCategoryBalanceId;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.DisclosureGroupRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link InterestCalculationProcessor} — the {@code CBACT04C} per-row interest
 * computation, a highest-risk migration site flagged by the CP3 review. Verifies the exact COBOL
 * interest formula {@code (TRAN-CAT-BAL * DIS-INT-RATE) / 1200} at scale 2 with HALF_EVEN rounding
 * (AAP §0.7.3/§0.7.6), the {@code 1200-GET-INTEREST-RATE} DEFAULT-group fallback, and the
 * zero-rate path that MUST return a non-null carrier (never filtered) so the account roll-up sees the row.
 */
class InterestCalculationProcessorTest {

    private static final Long ACCT_ID = 1L;
    private static final String GROUP_ID = "GRP1";
    private static final String TYPE_CODE = "01";
    private static final Integer CAT_CODE = 5;

    private AccountRepository accountRepository;
    private DisclosureGroupRepository disclosureGroupRepository;
    private CardCrossReferenceRepository crossReferenceRepository;
    private InterestCalculationProcessor processor;

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        disclosureGroupRepository = mock(DisclosureGroupRepository.class);
        crossReferenceRepository = mock(CardCrossReferenceRepository.class);
        processor = new InterestCalculationProcessor(
                accountRepository, disclosureGroupRepository, crossReferenceRepository);
        // PARM-DATE feeds the built interest transaction's id; set so it is not "null".
        processor.setParmDate("2024-01-01");
    }

    private TransactionCategoryBalance balanceRow(BigDecimal balance) {
        TransactionCategoryBalance row = new TransactionCategoryBalance();
        row.setId(new TransactionCategoryBalanceId(ACCT_ID, TYPE_CODE, CAT_CODE));
        row.setTranCatBal(balance);
        return row;
    }

    private void stubAccountAndXref() {
        Account account = new Account();
        account.setAcctId(ACCT_ID);
        account.setAcctGroupId(GROUP_ID);
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.of(account));

        CardCrossReference xref = new CardCrossReference();
        xref.setXrefCardNum("1234567890123456");
        xref.setXrefAcctId(ACCT_ID);
        when(crossReferenceRepository.findByXrefAcctId(ACCT_ID)).thenReturn(List.of(xref));
    }

    private DisclosureGroup disclosureGroup(String groupId, BigDecimal rate) {
        DisclosureGroup dg = new DisclosureGroup();
        dg.setId(new DisclosureGroupId(groupId, TYPE_CODE, CAT_CODE));
        dg.setDisIntRate(rate);
        return dg;
    }

    @Test
    @DisplayName("formula: (1000.00 * 12.00) / 1200 = 10.00 (scale 2), interest applied")
    void formulaExact() {
        stubAccountAndXref();
        when(disclosureGroupRepository.findById(new DisclosureGroupId(GROUP_ID, TYPE_CODE, CAT_CODE)))
                .thenReturn(Optional.of(disclosureGroup(GROUP_ID, new BigDecimal("12.00"))));

        InterestCalculationResult result = processor.process(balanceRow(new BigDecimal("1000.00")));

        assertThat(result).isNotNull();
        assertThat(result.interestApplied()).isTrue();
        assertThat(result.accountId()).isEqualTo(ACCT_ID);
        // COBOL (TRAN-CAT-BAL * DIS-INT-RATE) / 1200: rate is an annual percentage, /1200 yields
        // the monthly amount: 1000.00 * 12.00 / 1200 = 10.00.
        assertThat(result.monthlyInterest()).isEqualByComparingTo("10.00");
        assertThat(result.monthlyInterest().scale()).isEqualTo(2);
        assertThat(result.interestTransaction()).isNotNull();
        assertThat(result.interestTransaction().getTranAmt()).isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("formula rounds at scale 2: (100.00 * 1.00) / 1200 = 0.0833.. -> 0.08")
    void formulaRoundsToScaleTwo() {
        stubAccountAndXref();
        when(disclosureGroupRepository.findById(new DisclosureGroupId(GROUP_ID, TYPE_CODE, CAT_CODE)))
                .thenReturn(Optional.of(disclosureGroup(GROUP_ID, new BigDecimal("1.00"))));

        InterestCalculationResult result = processor.process(balanceRow(new BigDecimal("100.00")));

        assertThat(result.interestApplied()).isTrue();
        assertThat(result.monthlyInterest()).isEqualByComparingTo("0.08");
        assertThat(result.monthlyInterest().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("DEFAULT fallback: primary group absent -> re-read with 'DEFAULT' group id")
    void defaultGroupFallback() {
        stubAccountAndXref();
        // Primary (GRP1) read returns empty (COBOL DISCGRP-STATUS '23')...
        when(disclosureGroupRepository.findById(new DisclosureGroupId(GROUP_ID, TYPE_CODE, CAT_CODE)))
                .thenReturn(Optional.empty());
        // ...so the processor re-reads with the literal DEFAULT group id.
        when(disclosureGroupRepository.findById(new DisclosureGroupId("DEFAULT", TYPE_CODE, CAT_CODE)))
                .thenReturn(Optional.of(disclosureGroup("DEFAULT", new BigDecimal("6.00"))));

        InterestCalculationResult result = processor.process(balanceRow(new BigDecimal("1000.00")));

        assertThat(result.interestApplied()).isTrue();
        assertThat(result.monthlyInterest()).isEqualByComparingTo("5.00"); // (1000*6)/1200
        verify(disclosureGroupRepository).findById(new DisclosureGroupId(GROUP_ID, TYPE_CODE, CAT_CODE));
        verify(disclosureGroupRepository).findById(new DisclosureGroupId("DEFAULT", TYPE_CODE, CAT_CODE));
    }

    @Test
    @DisplayName("zero rate: returns a NON-NULL carrier with zero interest and no transaction (never filtered)")
    void zeroRateReturnsNonNullCarrier() {
        stubAccountAndXref();
        when(disclosureGroupRepository.findById(new DisclosureGroupId(GROUP_ID, TYPE_CODE, CAT_CODE)))
                .thenReturn(Optional.of(disclosureGroup(GROUP_ID, new BigDecimal("0.00"))));

        InterestCalculationResult result = processor.process(balanceRow(new BigDecimal("1000.00")));

        assertThat(result).isNotNull();
        assertThat(result.interestApplied()).isFalse();
        assertThat(result.interestTransaction()).isNull();
        assertThat(result.accountId()).isEqualTo(ACCT_ID);
        assertThat(result.monthlyInterest()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("neither primary nor DEFAULT disclosure group exists -> RecordNotFoundException (COBOL abend)")
    void missingDefaultGroupIsFatal() {
        stubAccountAndXref();
        when(disclosureGroupRepository.findById(new DisclosureGroupId(GROUP_ID, TYPE_CODE, CAT_CODE)))
                .thenReturn(Optional.empty());
        when(disclosureGroupRepository.findById(new DisclosureGroupId("DEFAULT", TYPE_CODE, CAT_CODE)))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> processor.process(balanceRow(new BigDecimal("1000.00"))))
                .isInstanceOf(RecordNotFoundException.class);
    }

    @Test
    @DisplayName("missing owning account -> RecordNotFoundException")
    void missingAccountIsFatal() {
        when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> processor.process(balanceRow(new BigDecimal("1000.00"))))
                .isInstanceOf(RecordNotFoundException.class);
    }
}

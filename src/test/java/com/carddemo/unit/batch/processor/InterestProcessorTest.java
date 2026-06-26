/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carddemo.unit.batch.processor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import com.carddemo.batch.processor.InterestProcessor;
import com.carddemo.entity.Account;
import com.carddemo.entity.CardXref;
import com.carddemo.entity.DisclosureGroup;
import com.carddemo.entity.DisclosureGroupId;
import com.carddemo.entity.Transaction;
import com.carddemo.entity.TransactionCategoryBalance;
import com.carddemo.entity.TransactionCategoryBalanceId;
import com.carddemo.enums.TransactionTypeCode;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardXrefRepository;
import com.carddemo.repository.DisclosureGroupRepository;
import com.carddemo.service.DateValidationService;

import io.micrometer.core.instrument.Counter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.JobExecution;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Behavioral-parity unit tests for {@link InterestProcessor}, the Java
 * realization of the COBOL interest-posting engine {@code CBACT04C} at source
 * commit {@code 27d6c6f}.
 *
 * <p>The suite verifies the precision-critical interest formula
 * {@code (TRAN-CAT-BAL * DIS-INT-RATE) / 1200} (multiply first, divide by the
 * literal 1200, scale 2, {@link java.math.RoundingMode#HALF_EVEN}); the
 * {@code DEFAULT} disclosure-group fallback; the zero/unknown-rate skip; the
 * step-scoped account control break with per-account interest flush; the
 * {@code @AfterStep} final flush; and the monotonic six-digit transaction-id
 * suffix with the run-date prefix.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InterestProcessor — CBACT04C interest formula & control break")
class InterestProcessorTest {

    private static final String PARM_DATE = "2022071800";
    private static final String TYPE_CD = "01";
    private static final Integer CAT_CD = 5;
    private static final String GROUP_ID = "GRP1";
    private static final String TIMESTAMP = "2022-07-18 00:00:00.000000";

    @Mock
    private DisclosureGroupRepository disclosureGroupRepository;
    @Mock
    private AccountRepository accountRepository;
    @Mock
    private CardXrefRepository cardXrefRepository;
    @Mock
    private DateValidationService dateValidationService;
    @Mock
    private Counter recordsProcessedCounter;
    @Mock
    private PlatformTransactionManager transactionManager;

    private InterestProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new InterestProcessor(
                disclosureGroupRepository,
                accountRepository,
                cardXrefRepository,
                dateValidationService,
                recordsProcessedCounter,
                transactionManager,
                PARM_DATE);
    }

    private TransactionCategoryBalance balance(Long acctId, BigDecimal amount) {
        return new TransactionCategoryBalance(
                new TransactionCategoryBalanceId(acctId, TYPE_CD, CAT_CD), amount);
    }

    private Account account(Long acctId, BigDecimal currBal) {
        return new Account(
                acctId, "Y", currBal, new BigDecimal("100000.00"), null, null,
                "2099-12-31", null, new BigDecimal("0.00"), new BigDecimal("0.00"),
                null, GROUP_ID, null);
    }

    private DisclosureGroup disclosure(String groupId, BigDecimal rate) {
        return new DisclosureGroup(new DisclosureGroupId(groupId, TYPE_CD, CAT_CD), rate);
    }

    private void stubAccount(Long acctId, BigDecimal currBal) {
        when(accountRepository.findById(acctId)).thenReturn(Optional.of(account(acctId, currBal)));
        when(cardXrefRepository.findByXrefAcctId(acctId))
                .thenReturn(List.of(new CardXref("4859452612877065", 1L, acctId)));
    }

    @Test
    @DisplayName("interest = (balance * rate) / 1200, scale 2 HALF_EVEN")
    void interestFormula() {
        stubAccount(1L, new BigDecimal("100.00"));
        when(disclosureGroupRepository.findById(any(DisclosureGroupId.class)))
                .thenReturn(Optional.of(disclosure(GROUP_ID, new BigDecimal("12.00"))));
        when(dateValidationService.currentTimestamp()).thenReturn(TIMESTAMP);

        // (1000.00 * 12.00) / 1200 = 10.00
        Transaction tx = processor.process(balance(1L, new BigDecimal("1000.00")));

        assertThat(tx).isNotNull();
        assertThat(tx.getTranAmt()).isEqualByComparingTo(new BigDecimal("10.00"));
        assertThat(tx.getTranAmt().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("HALF_EVEN rounding: 0.025 -> 0.02 (down to even), 0.035 -> 0.04 (up to even)")
    void halfEvenRounding() {
        stubAccount(1L, new BigDecimal("100.00"));
        // rate 100.00 makes (bal * 100)/1200 = bal/12; choose balances giving 0.025 and 0.035.
        when(disclosureGroupRepository.findById(any(DisclosureGroupId.class)))
                .thenReturn(Optional.of(disclosure(GROUP_ID, new BigDecimal("100.00"))));
        when(dateValidationService.currentTimestamp()).thenReturn(TIMESTAMP);

        // (0.30 * 100)/1200 = 30/1200 = 0.025 -> HALF_EVEN -> 0.02
        assertThat(processor.process(balance(1L, new BigDecimal("0.30"))).getTranAmt())
                .isEqualByComparingTo(new BigDecimal("0.02"));
        // (0.42 * 100)/1200 = 42/1200 = 0.035 -> HALF_EVEN -> 0.04
        assertThat(processor.process(balance(1L, new BigDecimal("0.42"))).getTranAmt())
                .isEqualByComparingTo(new BigDecimal("0.04"));
    }

    @Test
    @DisplayName("DEFAULT disclosure-group fallback when the account group has no row")
    void defaultGroupFallback() {
        stubAccount(1L, new BigDecimal("100.00"));
        // First lookup (account group) misses; second lookup (DEFAULT) hits.
        when(disclosureGroupRepository.findById(any(DisclosureGroupId.class)))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(disclosure("DEFAULT", new BigDecimal("12.00"))));
        when(dateValidationService.currentTimestamp()).thenReturn(TIMESTAMP);

        Transaction tx = processor.process(balance(1L, new BigDecimal("1000.00")));

        assertThat(tx).isNotNull();
        assertThat(tx.getTranAmt()).isEqualByComparingTo(new BigDecimal("10.00"));
        verify(disclosureGroupRepository, times(2)).findById(any(DisclosureGroupId.class));
    }

    @Test
    @DisplayName("zero rate skips the item (returns null, no transaction)")
    void zeroRateSkips() {
        stubAccount(1L, new BigDecimal("100.00"));
        when(disclosureGroupRepository.findById(any(DisclosureGroupId.class)))
                .thenReturn(Optional.of(disclosure(GROUP_ID, new BigDecimal("0.00"))));

        assertThat(processor.process(balance(1L, new BigDecimal("1000.00")))).isNull();
    }

    @Test
    @DisplayName("unknown rate (no account-group or DEFAULT row) skips the item")
    void unknownRateSkips() {
        stubAccount(1L, new BigDecimal("100.00"));
        when(disclosureGroupRepository.findById(any(DisclosureGroupId.class)))
                .thenReturn(Optional.empty());

        assertThat(processor.process(balance(1L, new BigDecimal("1000.00")))).isNull();
    }

    @Test
    @DisplayName("transaction id = run-date prefix + zero-padded 6-digit incrementing suffix")
    void transactionIdSuffixAndDescription() {
        stubAccount(1L, new BigDecimal("100.00"));
        when(disclosureGroupRepository.findById(any(DisclosureGroupId.class)))
                .thenReturn(Optional.of(disclosure(GROUP_ID, new BigDecimal("12.00"))));
        when(dateValidationService.currentTimestamp()).thenReturn(TIMESTAMP);

        Transaction first = processor.process(balance(1L, new BigDecimal("1000.00")));
        Transaction second = processor.process(balance(1L, new BigDecimal("1000.00")));

        assertThat(first.getTranId()).isEqualTo(PARM_DATE + "000001");
        assertThat(second.getTranId()).isEqualTo(PARM_DATE + "000002");
        assertThat(first.getTranDesc()).isEqualTo("Int. for a/c 00000000001");
        assertThat(first.getTransactionType()).isEqualTo(TransactionTypeCode.PURCHASE);
        assertThat(first.getTranCatCd()).isEqualTo(CAT_CD);
        assertThat(first.getOrigTs()).isEqualTo(TIMESTAMP);
        assertThat(first.getProcTs()).isEqualTo(TIMESTAMP);
    }

    @Test
    @DisplayName("account control break flushes the prior account's accumulated interest")
    void controlBreakFlush() {
        stubAccount(1L, new BigDecimal("100.00"));
        stubAccount(2L, new BigDecimal("200.00"));
        when(disclosureGroupRepository.findById(any(DisclosureGroupId.class)))
                .thenReturn(Optional.of(disclosure(GROUP_ID, new BigDecimal("12.00"))));
        when(dateValidationService.currentTimestamp()).thenReturn(TIMESTAMP);

        // Account 1: two items -> interest 10.00 + 5.00 = 15.00 accumulated.
        processor.process(balance(1L, new BigDecimal("1000.00")));
        processor.process(balance(1L, new BigDecimal("500.00")));
        // Account change to 2 triggers the flush of account 1.
        processor.process(balance(2L, new BigDecimal("1000.00")));

        ArgumentCaptor<Account> savedCaptor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(savedCaptor.capture());
        Account flushed = savedCaptor.getValue();
        assertThat(flushed.getAcctId()).isEqualTo(1L);
        // 100.00 + 15.00 = 115.00; cycle buckets reset to zero.
        assertThat(flushed.getCurrBal()).isEqualByComparingTo(new BigDecimal("115.00"));
        assertThat(flushed.getCurrCycCredit()).isEqualByComparingTo(new BigDecimal("0.00"));
        assertThat(flushed.getCurrCycDebit()).isEqualByComparingTo(new BigDecimal("0.00"));
    }

    @Test
    @DisplayName("@AfterStep flushes the final account group")
    void afterStepFinalFlush() {
        stubAccount(1L, new BigDecimal("100.00"));
        when(disclosureGroupRepository.findById(any(DisclosureGroupId.class)))
                .thenReturn(Optional.of(disclosure(GROUP_ID, new BigDecimal("12.00"))));
        when(dateValidationService.currentTimestamp()).thenReturn(TIMESTAMP);

        processor.process(balance(1L, new BigDecimal("1000.00")));

        StepExecution stepExecution =
                new StepExecution("interestStep", new JobExecution(1L));
        processor.afterStep(stepExecution);

        ArgumentCaptor<Account> savedCaptor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getAcctId()).isEqualTo(1L);
        assertThat(savedCaptor.getValue().getCurrBal()).isEqualByComparingTo(new BigDecimal("110.00"));
    }
}

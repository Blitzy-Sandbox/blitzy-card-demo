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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.batch.processor.InterestProcessor;
import com.carddemo.entity.Account;
import com.carddemo.entity.CardXref;
import com.carddemo.entity.DisclosureGroup;
import com.carddemo.entity.DisclosureGroupId;
import com.carddemo.entity.Transaction;
import com.carddemo.entity.TransactionCategoryBalance;
import com.carddemo.entity.TransactionCategoryBalanceId;
import com.carddemo.enums.TransactionSource;
import com.carddemo.enums.TransactionTypeCode;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardXrefRepository;
import com.carddemo.repository.DisclosureGroupRepository;
import com.carddemo.service.DateValidationService;
import io.micrometer.core.instrument.Counter;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Pure, in-memory behavioral-parity unit tests for {@link InterestProcessor},
 * the Java realization of the COBOL interest-calculation batch program
 * {@code CBACT04C} driven by JCL {@code INTCALC.jcl} ({@code PARM='2022071800'})
 * at source commit {@code 27d6c6f}.
 *
 * <p>The {@link InterestProcessor} is a {@code @StepScope} Spring Batch
 * {@code ItemProcessor}. These tests drive its {@code process} method and its
 * {@code @AfterStep} callback <em>directly as plain method calls</em> with all
 * collaborators mocked; no Spring {@code ApplicationContext}, no
 * {@code @StepScope} bootstrap, no {@code spring-batch-test}, and no
 * Testcontainers / database / AWS are involved. The raw ten-character
 * {@code PARM-DATE} job parameter is supplied through the production
 * constructor argument, exactly as the {@code @StepScope} bean is wired in the
 * job configuration.</p>
 *
 * <p>The suite verifies, against the frozen COBOL specification:</p>
 * <ul>
 *   <li><strong>The interest formula</strong> {@code (TRAN-CAT-BAL *
 *       DIS-INT-RATE) / 1200} (CBACT04C lines 464-465): multiply first, then
 *       divide by the literal {@code 1200}, scaled to two decimals. The amount
 *       is always compared with {@link BigDecimal#compareTo} and asserted to
 *       carry a scale of two; {@code double}/{@code float} are never used.</li>
 *   <li><strong>The {@code HALF_EVEN} deviation.</strong> COBOL {@code CBACT04C}
 *       carries no {@code ROUNDED} clause (it truncates); the migration
 *       deliberately applies {@link java.math.RoundingMode#HALF_EVEN}, an
 *       intentional divergence recorded in {@code DECISION_LOG.md}. A half-case
 *       distinguishes banker's rounding from truncation.</li>
 *   <li><strong>The {@code DEFAULT} disclosure-group fallback</strong>
 *       (CBACT04C 1200-A, lines 437-438): an account-group miss re-reads the
 *       {@code DEFAULT} group.</li>
 *   <li><strong>The zero/unknown-rate skip</strong> (CBACT04C line 214,
 *       {@code IF DIS-INT-RATE NOT = 0}): no transaction is produced.</li>
 *   <li><strong>The generated interest transaction</strong> (1300-B-WRITE-TX,
 *       lines 473-498): run-date-prefixed monotonic identifier and every mapped
 *       field.</li>
 *   <li><strong>The account control break</strong> (main loop lines 188-222 and
 *       1050-UPDATE-ACCOUNT lines 350-356): the per-account running total is
 *       flushed to the balance with the cycle buckets zeroed, and resets on each
 *       new account; the final account is flushed from {@code @AfterStep}.</li>
 * </ul>
 *
 * <p>This processor-level suite is intentionally complementary to (and not
 * redundant with) the sibling {@code InterestCalculationJobIT}, which exercises
 * the full INTCALC job against real PostgreSQL and LocalStack.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InterestProcessor \u2014 CBACT04C interest formula & control break")
class InterestProcessorTest {

    /** Raw ten-character {@code PARM-DATE} job parameter (JCL INTCALC line 22). */
    private static final String PARM_DATE = "2022071800";

    /** Transaction type code completing the disclosure key (COBOL TRANCAT-TYPE-CD). */
    private static final String TYPE_CD = "01";

    /** Transaction category code completing the disclosure key (COBOL TRANCAT-CD). */
    private static final Integer CAT_CD = 5;

    /** Account's own disclosure group id (COBOL ACCT-GROUP-ID). */
    private static final String GROUP_ID = "GRP1";

    /** Fallback group id used on an account-group miss (COBOL {@code MOVE 'DEFAULT'}). */
    private static final String DEFAULT_GROUP_ID = "DEFAULT";

    /** Card number resolved through the cross-reference (COBOL XREF-CARD-NUM). */
    private static final String CARD_NUM = "4859452612877065";

    /** Fixed 26-character DB2-format timestamp returned by the mocked clock service. */
    private static final String TIMESTAMP = "2022-07-18 00:00:00.000000";

    /**
     * Non-zero current-cycle credit seed, deliberately set so the control-break
     * flush's reset to zero is observable rather than vacuously true.
     */
    private static final BigDecimal CYC_CREDIT_SEED = new BigDecimal("50.00");

    /**
     * Non-zero current-cycle debit seed, deliberately set so the control-break
     * flush's reset to zero is observable rather than vacuously true.
     */
    private static final BigDecimal CYC_DEBIT_SEED = new BigDecimal("25.00");

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
        // Manual construction (not @InjectMocks): the production constructor takes the
        // raw String PARM-DATE (@Value("#{jobParameters['parmDate']}")) and a @Qualifier
        // Counter, which @InjectMocks cannot satisfy. The PARM-DATE is set here, exactly
        // mirroring how the @StepScope bean is wired from the job parameters.
        processor = new InterestProcessor(
                disclosureGroupRepository,
                accountRepository,
                cardXrefRepository,
                dateValidationService,
                recordsProcessedCounter,
                transactionManager,
                PARM_DATE);

        // The single shared stub: a fixed, 26-character DB2-format timestamp. It is
        // lenient because the zero/unknown-rate skip tests build no transaction and so
        // never consult the clock, which would otherwise trip STRICT_STUBS.
        lenient().when(dateValidationService.currentTimestamp()).thenReturn(TIMESTAMP);
    }

    // ------------------------------------------------------------------ //
    // Test data factories                                                //
    // ------------------------------------------------------------------ //

    /**
     * Builds a transaction-category balance for {@code acctId} keyed on the
     * shared type/category codes, carrying {@code amount} as the running balance.
     */
    private TransactionCategoryBalance balance(Long acctId, BigDecimal amount) {
        return new TransactionCategoryBalance(
                new TransactionCategoryBalanceId(acctId, TYPE_CD, CAT_CD), amount);
    }

    /**
     * Builds an account in disclosure group {@link #GROUP_ID} with the supplied
     * current balance and deliberately non-zero current-cycle credit/debit
     * buckets, so the control-break flush's zeroing is a meaningful assertion.
     */
    private Account account(Long acctId, BigDecimal currBal) {
        return new Account(
                acctId,
                "Y",
                currBal,
                new BigDecimal("100000.00"),
                new BigDecimal("5000.00"),
                "2020-01-01",
                "2099-12-31",
                "2020-01-01",
                CYC_CREDIT_SEED,
                CYC_DEBIT_SEED,
                "12345",
                GROUP_ID,
                0L);
    }

    /** Builds a disclosure-group row in {@code groupId} carrying {@code rate}. */
    private DisclosureGroup disclosure(String groupId, BigDecimal rate) {
        return new DisclosureGroup(new DisclosureGroupId(groupId, TYPE_CD, CAT_CD), rate);
    }

    /**
     * Stubs the account-master read and the cross-reference card-number read for
     * {@code acctId}, the two keyed reads the processor performs on an account
     * change (COBOL 1100-GET-ACCT-DATA and 1110-GET-XREF-DATA).
     */
    private void stubAccount(Long acctId, BigDecimal currBal) {
        when(accountRepository.findById(acctId)).thenReturn(Optional.of(account(acctId, currBal)));
        when(cardXrefRepository.findByXrefAcctId(acctId))
                .thenReturn(List.of(new CardXref(CARD_NUM, 1L, acctId)));
    }

    /** Builds a step execution with no Spring context for plain {@code @AfterStep} invocation. */
    private StepExecution newStepExecution() {
        return new StepExecution("interestStep", new JobExecution(1L));
    }

    // ------------------------------------------------------------------ //
    // Phase 2 \u2014 Interest formula (financial precision \u2014 the core)        //
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("interest = (balance * rate) / 1200, multiply-first, scale 2 HALF_EVEN")
    void process_computesInterestWithScaleTwo() {
        stubAccount(1L, new BigDecimal("100.00"));
        when(disclosureGroupRepository.findById(any(DisclosureGroupId.class)))
                .thenReturn(Optional.of(disclosure(GROUP_ID, new BigDecimal("12.00"))));

        // (1000.00 * 12.00) / 1200 = 12000.0000 / 1200 = 10.00
        Transaction tx = processor.process(balance(1L, new BigDecimal("1000.00")));

        assertThat(tx).isNotNull();
        assertThat(tx.getTranAmt()).isEqualByComparingTo(new BigDecimal("10.00"));
        assertThat(tx.getTranAmt().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("HALF_EVEN rounding (DECISION_LOG deviation, not COBOL truncation): 0.025->0.02, 0.035->0.04")
    void process_appliesHalfEvenRoundingNotTruncation() {
        stubAccount(1L, new BigDecimal("100.00"));
        // rate 100.00 makes interest = balance / 12; the chosen balances land the raw
        // quotient exactly on a .xx5 tie, the only place HALF_EVEN and truncation differ.
        when(disclosureGroupRepository.findById(any(DisclosureGroupId.class)))
                .thenReturn(Optional.of(disclosure(GROUP_ID, new BigDecimal("100.00"))));

        // (0.30 * 100.00)/1200 = 0.025 -> tie, preceding digit 2 (even) -> 0.02
        // (truncation would also yield 0.02, so this is the control case)
        Transaction roundDown = processor.process(balance(1L, new BigDecimal("0.30")));
        // (0.42 * 100.00)/1200 = 0.035 -> tie, preceding digit 3 (odd) -> 0.04
        // (truncation would yield 0.03, so 0.04 proves banker's rounding)
        Transaction roundUp = processor.process(balance(1L, new BigDecimal("0.42")));

        assertThat(roundDown.getTranAmt()).isEqualByComparingTo(new BigDecimal("0.02"));
        assertThat(roundUp.getTranAmt()).isEqualByComparingTo(new BigDecimal("0.04"));
        assertThat(roundUp.getTranAmt().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("multiply BEFORE divide: (1000.00 * 10.00)/1200 = 8.33, not a rearranged 10.00")
    void process_multipliesBeforeDividing() {
        stubAccount(1L, new BigDecimal("100.00"));
        when(disclosureGroupRepository.findById(any(DisclosureGroupId.class)))
                .thenReturn(Optional.of(disclosure(GROUP_ID, new BigDecimal("10.00"))));

        // Multiply-first: 10000.0000 / 1200 = 8.3333... -> 8.33.
        // A rearranged bal * (rate / 1200) that rounded (10.00 / 1200) to scale 2 (0.01)
        // would instead yield 1000.00 * 0.01 = 10.00; asserting 8.33 proves the operand order.
        Transaction tx = processor.process(balance(1L, new BigDecimal("1000.00")));

        assertThat(tx.getTranAmt()).isEqualByComparingTo(new BigDecimal("8.33"));
        assertThat(tx.getTranAmt().scale()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ //
    // Phase 3 \u2014 Disclosure-group fallback & zero/unknown-rate skip       //
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("DEFAULT fallback: an account-group miss re-reads the DEFAULT group, in order")
    void process_fallsBackToDefaultDisclosureGroup() {
        stubAccount(1L, new BigDecimal("100.00"));
        DisclosureGroupId accountGroupKey = new DisclosureGroupId(GROUP_ID, TYPE_CD, CAT_CD);
        DisclosureGroupId defaultGroupKey = new DisclosureGroupId(DEFAULT_GROUP_ID, TYPE_CD, CAT_CD);
        // First read (account's own group) misses; the re-read (DEFAULT) supplies the rate.
        when(disclosureGroupRepository.findById(accountGroupKey)).thenReturn(Optional.empty());
        when(disclosureGroupRepository.findById(defaultGroupKey))
                .thenReturn(Optional.of(disclosure(DEFAULT_GROUP_ID, new BigDecimal("12.00"))));

        Transaction tx = processor.process(balance(1L, new BigDecimal("1000.00")));

        assertThat(tx).isNotNull();
        // Interest computed from the DEFAULT rate: (1000.00 * 12.00)/1200 = 10.00.
        assertThat(tx.getTranAmt()).isEqualByComparingTo(new BigDecimal("10.00"));

        InOrder inOrder = inOrder(disclosureGroupRepository);
        inOrder.verify(disclosureGroupRepository).findById(accountGroupKey);
        inOrder.verify(disclosureGroupRepository).findById(defaultGroupKey);
    }

    @Test
    @DisplayName("zero rate skips: returns null and leaves the running total unchanged")
    void process_skipsZeroRateAndLeavesRunningTotalUnchanged() {
        stubAccount(1L, new BigDecimal("100.00"));
        when(disclosureGroupRepository.findById(any(DisclosureGroupId.class)))
                .thenReturn(Optional.of(disclosure(GROUP_ID, new BigDecimal("0.00"))));

        // Zero rate -> no interest transaction is produced.
        Transaction tx = processor.process(balance(1L, new BigDecimal("1000.00")));
        assertThat(tx).isNull();

        // Drive the end-of-step flush: because the running total stayed at zero, the
        // flushed balance must equal the original balance (behavioral proof of "unchanged").
        processor.afterStep(newStepExecution());

        ArgumentCaptor<Account> savedCaptor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getCurrBal()).isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    @DisplayName("unknown rate skips: neither the account-group nor the DEFAULT row exists -> null")
    void process_skipsWhenNoDisclosureRowFound() {
        stubAccount(1L, new BigDecimal("100.00"));
        // Both the account-group read and the DEFAULT re-read miss.
        when(disclosureGroupRepository.findById(any(DisclosureGroupId.class)))
                .thenReturn(Optional.empty());

        assertThat(processor.process(balance(1L, new BigDecimal("1000.00")))).isNull();

        // The miss-then-DEFAULT re-read is exactly two lookups.
        verify(disclosureGroupRepository, times(2)).findById(any(DisclosureGroupId.class));
    }

    // ------------------------------------------------------------------ //
    // Phase 4 \u2014 TRAN-ID generation & generated-transaction field mapping //
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("transaction id = run-date prefix + zero-padded 6-digit monotonic suffix")
    void process_generatesMonotonicTranIdSuffix() {
        stubAccount(1L, new BigDecimal("100.00"));
        when(disclosureGroupRepository.findById(any(DisclosureGroupId.class)))
                .thenReturn(Optional.of(disclosure(GROUP_ID, new BigDecimal("12.00"))));

        Transaction first = processor.process(balance(1L, new BigDecimal("1000.00")));
        Transaction second = processor.process(balance(1L, new BigDecimal("1000.00")));

        assertThat(first.getTranId()).isEqualTo("2022071800000001");
        assertThat(second.getTranId()).isEqualTo("2022071800000002");
    }

    @Test
    @DisplayName("generated interest transaction maps every field per CBACT04C 1300-B-WRITE-TX")
    void process_mapsGeneratedTransactionFields() {
        stubAccount(1L, new BigDecimal("100.00"));
        when(disclosureGroupRepository.findById(any(DisclosureGroupId.class)))
                .thenReturn(Optional.of(disclosure(GROUP_ID, new BigDecimal("12.00"))));

        Transaction tx = processor.process(balance(1L, new BigDecimal("1000.00")));

        // Type '01' (PURCHASE) and category 5 ('05').
        assertThat(tx.getTransactionType()).isEqualTo(TransactionTypeCode.fromCode("01"));
        assertThat(tx.getTransactionType()).isEqualTo(TransactionTypeCode.PURCHASE);
        assertThat(tx.getTranCatCd()).isEqualTo(CAT_CD);
        // Source label 'System' (TransactionSource.SYSTEM), asserted as the exact string.
        assertThat(tx.getTranSource())
                .isEqualTo(TransactionSource.SYSTEM.getLabel())
                .isEqualTo("System");
        // Description preserves the trailing space after "a/c" and zero-pads the account id
        // to 11 digits (COBOL ACCT-ID PIC 9(11)).
        assertThat(tx.getTranDesc()).isEqualTo("Int. for a/c 00000000001");
        // Amount equals the computed monthly interest.
        assertThat(tx.getTranAmt()).isEqualByComparingTo(new BigDecimal("10.00"));
        // Merchant id zero and card number resolved from the cross-reference.
        assertThat(tx.getMerchantId()).isEqualTo(0L);
        assertThat(tx.getCardNum()).isEqualTo(CARD_NUM);
        // Origination and processing timestamps share the single 26-character value.
        assertThat(tx.getOrigTs()).isEqualTo(tx.getProcTs());
        assertThat(tx.getOrigTs()).hasSize(26);

        // The records-processed counter is incremented once per produced interest transaction.
        verify(recordsProcessedCounter).increment();
    }

    // ------------------------------------------------------------------ //
    // Phase 5 \u2014 Control-break flush (step lifecycle driven manually)     //
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("account control break flushes the prior account: balance += running total, cycles zeroed")
    void process_flushesPriorAccountOnAccountChange() {
        stubAccount(1L, new BigDecimal("100.00"));
        stubAccount(2L, new BigDecimal("200.00"));
        when(disclosureGroupRepository.findById(any(DisclosureGroupId.class)))
                .thenReturn(Optional.of(disclosure(GROUP_ID, new BigDecimal("12.00"))));

        // Account 1 accrues 10.00 + 5.00 = 15.00; the change to account 2 flushes account 1.
        processor.process(balance(1L, new BigDecimal("1000.00")));
        processor.process(balance(1L, new BigDecimal("500.00")));
        processor.process(balance(2L, new BigDecimal("1000.00")));

        ArgumentCaptor<Account> savedCaptor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(savedCaptor.capture());
        Account flushed = savedCaptor.getValue();
        assertThat(flushed.getAcctId()).isEqualTo(1L);
        // 100.00 + 15.00 = 115.00; both cycle buckets reset from their non-zero seeds to zero.
        assertThat(flushed.getCurrBal()).isEqualByComparingTo(new BigDecimal("115.00"));
        assertThat(flushed.getCurrCycCredit()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(flushed.getCurrCycDebit()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("@AfterStep flushes the final account group and returns the step exit status unchanged")
    void afterStep_flushesFinalAccountAndReturnsExitStatusUnchanged() {
        stubAccount(1L, new BigDecimal("100.00"));
        when(disclosureGroupRepository.findById(any(DisclosureGroupId.class)))
                .thenReturn(Optional.of(disclosure(GROUP_ID, new BigDecimal("12.00"))));

        processor.process(balance(1L, new BigDecimal("1000.00")));

        // The iteration ends without a further account change, so the final account is
        // flushed from @AfterStep, invoked here as a plain method call.
        StepExecution stepExecution = newStepExecution();
        stepExecution.setExitStatus(ExitStatus.COMPLETED);
        ExitStatus result = processor.afterStep(stepExecution);

        // The callback returns the step's existing exit status, left unchanged.
        assertThat(result).isEqualTo(ExitStatus.COMPLETED);

        ArgumentCaptor<Account> savedCaptor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(savedCaptor.capture());
        Account flushed = savedCaptor.getValue();
        assertThat(flushed.getAcctId()).isEqualTo(1L);
        // 100.00 + 10.00 = 110.00; cycle buckets zeroed.
        assertThat(flushed.getCurrBal()).isEqualByComparingTo(new BigDecimal("110.00"));
        assertThat(flushed.getCurrCycCredit()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(flushed.getCurrCycDebit()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("running interest total resets per account: account B's flush excludes account A's interest")
    void process_resetsRunningInterestTotalOnNewAccount() {
        stubAccount(1L, new BigDecimal("100.00"));
        stubAccount(2L, new BigDecimal("200.00"));
        when(disclosureGroupRepository.findById(any(DisclosureGroupId.class)))
                .thenReturn(Optional.of(disclosure(GROUP_ID, new BigDecimal("12.00"))));

        processor.process(balance(1L, new BigDecimal("1000.00"))); // account 1: +10.00
        processor.process(balance(2L, new BigDecimal("2000.00"))); // change flushes 1; account 2: +20.00
        processor.afterStep(newStepExecution());                   // flushes account 2

        ArgumentCaptor<Account> savedCaptor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository, times(2)).save(savedCaptor.capture());
        List<Account> flushed = savedCaptor.getAllValues();

        // First flush: account 1 with only its own 10.00 (100.00 -> 110.00).
        assertThat(flushed.get(0).getAcctId()).isEqualTo(1L);
        assertThat(flushed.get(0).getCurrBal()).isEqualByComparingTo(new BigDecimal("110.00"));
        // Second flush: account 2 with only its own 20.00 (200.00 -> 220.00); the running
        // total reset on the account change, so account 1's 10.00 is NOT carried over.
        assertThat(flushed.get(1).getAcctId()).isEqualTo(2L);
        assertThat(flushed.get(1).getCurrBal()).isEqualByComparingTo(new BigDecimal("220.00"));
    }
}

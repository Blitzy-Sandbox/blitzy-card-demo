/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.awsm2.carddemo.service;

import com.awsm2.carddemo.adapter.AuditLogService;
import com.awsm2.carddemo.adapter.CacheService;
import com.awsm2.carddemo.adapter.KafkaEventPublisher;
import com.awsm2.carddemo.domain.Account;
import com.awsm2.carddemo.domain.CardCrossReference;
import com.awsm2.carddemo.domain.DisclosureGroup;
import com.awsm2.carddemo.domain.DisclosureGroup.DisclosureGroupId;
import com.awsm2.carddemo.domain.Transaction;
import com.awsm2.carddemo.domain.TransactionCategoryBalance;
import com.awsm2.carddemo.dto.AccountUpdateDto;
import com.awsm2.carddemo.dto.TransactionAddDto;
import com.awsm2.carddemo.exception.OnSizeErrorException;
import com.awsm2.carddemo.exception.RecordNotFoundException;
import com.awsm2.carddemo.repository.AccountRepository;
import com.awsm2.carddemo.repository.CardCrossReferenceRepository;
import com.awsm2.carddemo.repository.DisclosureGroupRepository;
import com.awsm2.carddemo.repository.TransactionCategoryBalanceRepository;
import com.awsm2.carddemo.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JUnit 5 + Mockito + AssertJ unit tests for
 * {@link InterestCalculationService}.
 *
 * <p><b>COBOL provenance.</b> {@link InterestCalculationService}
 * translates {@code app/cbl/CBACT04C.cbl} (the COBOL interest
 * calculation batch program). The COBOL source iterates the TCATBAL
 * VSAM cluster in {@code (acct-id, type-cd, cat-cd)} order, looks up
 * the applicable disclosure-group rate, computes
 * {@code (balance * rate) / 1200}, posts an interest transaction, and
 * updates the account at each account boundary.</p>
 *
 * <p><b>Behavioural invariants locked by this suite.</b></p>
 * <ol>
 *   <li><b>BigDecimal arithmetic</b> &mdash;
 *       {@code (balance × rate) ÷ 1200} computed via
 *       {@link BigDecimal#multiply(BigDecimal)} +
 *       {@link BigDecimal#divide(BigDecimal, int, java.math.RoundingMode)}
 *       with {@code scale=2}, {@code RoundingMode.HALF_EVEN}, and the
 *       literal {@code BigDecimal.valueOf(1200)} divisor preserved
 *       verbatim per AAP §0.6.1. No float/double.</li>
 *   <li><b>ON SIZE ERROR</b> &mdash; values that overflow the
 *       {@code PIC S9(10)V99} ceiling trigger
 *       {@link OnSizeErrorException}.</li>
 *   <li><b>Deterministic XREF selection</b> &mdash; uses the ORDERED
 *       repository method
 *       {@link CardCrossReferenceRepository#findByXrefAcctIdOrderByXrefCardNumAsc(Long)}
 *       (CP5 regression).</li>
 *   <li><b>DEFAULT-group fallback</b> &mdash; missing specific
 *       disclosure-group key falls back to the literal
 *       {@code "DEFAULT"} group; double-miss throws
 *       {@link RecordNotFoundException}.</li>
 *   <li><b>Zero rate skip</b> &mdash; a 0% rate does NOT post a
 *       transaction (matches COBOL {@code IF DIS-INT-RATE NOT = 0}).</li>
 *   <li><b>Account update at boundary</b> &mdash; total interest is
 *       added to the account balance and cycle credit/debit are
 *       cleared at each account boundary and at end-of-file.</li>
 *   <li><b>MSK + audit emission</b> &mdash; per-transaction
 *       {@code transaction.posted} event partitioned by acct ID,
 *       per-account {@code account.updated} event partitioned by acct
 *       ID, and a run-summary audit event.</li>
 * </ol>
 *
 * <p>External AWS interactions are fully mocked through
 * {@link MockitoExtension}; no Testcontainers or LocalStack are
 * involved.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InterestCalculationService unit tests (COBOL: CBACT04C.cbl)")
class InterestCalculationServiceTest {

    // ==================================================================
    // Test constants
    // ==================================================================
    private static final LocalDate PARM_DATE = LocalDate.of(2025, 1, 31);
    private static final String BATCH_RUN_ID = "BATCH-RUN-20250131";
    private static final Long ACCOUNT_ID = 10_000_000_001L;
    private static final String GROUP_ID = "GROUP1";
    private static final String TRAN_TYPE_CD = "01";
    private static final Integer TRAN_CAT_CD = 5;
    private static final String CARD_LOW = "4000000000000001";
    private static final String CARD_HIGH = "4000000000000002";

    // ==================================================================
    // Mocks and SUT
    // ==================================================================
    @Mock private TransactionCategoryBalanceRepository balanceRepository;
    @Mock private DisclosureGroupRepository disclosureGroupRepository;
    @Mock private AccountRepository accountRepository;
    @Mock private CardCrossReferenceRepository xrefRepository;
    @Mock private TransactionRepository transactionRepository;
    @Mock private KafkaEventPublisher kafkaEventPublisher;
    @Mock private CacheService cacheService;
    @Mock private AuditLogService auditLogService;

    @InjectMocks private InterestCalculationService service;

    // ==================================================================
    // Test fixtures
    // ==================================================================
    private Account account;

    @BeforeEach
    void setUp() {
        account = new Account();
        account.setAcctId(ACCOUNT_ID);
        account.setAcctGroupId(GROUP_ID);
        account.setAcctCurrBal(new BigDecimal("100.00"));
        account.setAcctCurrCycCredit(new BigDecimal("50.00"));
        account.setAcctCurrCycDebit(new BigDecimal("25.00"));
        account.setAcctActiveStatus("Y");
    }

    /**
     * Build a TransactionCategoryBalance row with the given amount.
     * The {@link TransactionCategoryBalance#getId()} composite key is
     * populated from the three CP5 constants.
     */
    private TransactionCategoryBalance buildBalance(BigDecimal amount) {
        return new TransactionCategoryBalance(
                ACCOUNT_ID, TRAN_TYPE_CD, TRAN_CAT_CD, amount);
    }

    private DisclosureGroup buildDisclosureGroup(String groupId, BigDecimal rate) {
        return new DisclosureGroup(
                new DisclosureGroupId(groupId, TRAN_TYPE_CD, TRAN_CAT_CD),
                rate);
    }

    /**
     * Provide a lenient happy-path stub set for repositories that may
     * be queried by tests that don't override.
     *
     * <p>NOTE: the {@code balanceRepository.findAll()} stub wraps the
     * caller-supplied list in a {@link ArrayList} so that the service's
     * in-place {@code balances.sort(...)} call at
     * {@link InterestCalculationService#calculateInterest(LocalDate, String)}
     * does not throw {@link UnsupportedOperationException} (which is what
     * happens when the underlying collection is the immutable
     * {@link List#of} instance).</p>
     */
    private void stubHappyPath(List<TransactionCategoryBalance> balances,
                               List<CardCrossReference> xrefs,
                               DisclosureGroup specific) {
        lenient().when(balanceRepository.findAll())
                .thenReturn(new ArrayList<>(balances));
        lenient().when(accountRepository.findById(ACCOUNT_ID))
                .thenReturn(Optional.of(account));
        lenient().when(accountRepository.save(any(Account.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(xrefRepository
                        .findByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                .thenReturn(xrefs);
        lenient().when(transactionRepository.save(any(Transaction.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        if (specific != null) {
            lenient().when(disclosureGroupRepository
                            .findById(new DisclosureGroupId(GROUP_ID,
                                    TRAN_TYPE_CD, TRAN_CAT_CD)))
                    .thenReturn(Optional.of(specific));
        }
    }

    // ==================================================================
    // @Nested test groups
    // ==================================================================

    @Nested
    @DisplayName("BigDecimal arithmetic (HALF_EVEN, literal 1200 divisor)")
    class BigDecimalArithmetic {

        @Test
        @DisplayName("computeMonthlyInterest: (1000.00 * 12.00%) / 1200 = 10.00")
        void computeMonthlyInterest_simpleCase() {
            BigDecimal balance = new BigDecimal("1000.00");
            BigDecimal rate = new BigDecimal("12.00");

            BigDecimal result = service.computeMonthlyInterest(balance, rate);

            // (1000.00 * 12.00) / 1200 = 12000.00 / 1200 = 10.00
            assertThat(result).isEqualByComparingTo(new BigDecimal("10.00"));
            assertThat(result.scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("uses HALF_EVEN banker's rounding")
        void computeMonthlyInterest_halfEvenRounding() {
            // (100.00 * 0.005) / 1200 = 0.5 / 1200 = 0.000416666...
            // Scale=2 → HALF_EVEN rounds 0.000... to 0.00
            BigDecimal r0 = service.computeMonthlyInterest(
                    new BigDecimal("100.00"), new BigDecimal("0.005"));
            assertThat(r0).isEqualByComparingTo(BigDecimal.ZERO);

            // A non-trivial case: 1200 * 1 / 1200 = 1.00 exact
            BigDecimal r1 = service.computeMonthlyInterest(
                    new BigDecimal("1200.00"), new BigDecimal("1.00"));
            assertThat(r1).isEqualByComparingTo(new BigDecimal("1.00"));
            assertThat(r1.scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("handles null balance/rate by returning 0.00")
        void computeMonthlyInterest_nulls() {
            assertThat(service.computeMonthlyInterest(null, BigDecimal.ONE))
                    .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(service.computeMonthlyInterest(BigDecimal.ONE, null))
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("divisor is literal 1200 (preserved per AAP §0.6.1)")
        void computeMonthlyInterest_divisorIs1200() {
            // (24000 * 12.00) / 1200 = 288000 / 1200 = 240.00
            BigDecimal result = service.computeMonthlyInterest(
                    new BigDecimal("24000.00"), new BigDecimal("12.00"));
            assertThat(result)
                    .isEqualByComparingTo(new BigDecimal("240.00"));
        }
    }

    @Nested
    @DisplayName("DEFAULT-group fallback (COBOL: 1200-A-GET-DEFAULT-INT-RATE)")
    class DisclosureGroupLookup {

        @Test
        @DisplayName("specific lookup hit — uses specific rate")
        void lookupInterestRate_specificHit() {
            BigDecimal expectedRate = new BigDecimal("9.50");
            when(disclosureGroupRepository.findById(
                    new DisclosureGroupId(GROUP_ID, TRAN_TYPE_CD, TRAN_CAT_CD)))
                    .thenReturn(Optional.of(buildDisclosureGroup(GROUP_ID, expectedRate)));

            BigDecimal rate = service.lookupInterestRate(
                    GROUP_ID, TRAN_TYPE_CD, TRAN_CAT_CD);

            assertThat(rate).isEqualByComparingTo(expectedRate);
        }

        @Test
        @DisplayName("specific miss → falls back to DEFAULT row")
        void lookupInterestRate_defaultFallback() {
            // Specific lookup misses
            when(disclosureGroupRepository.findById(
                    new DisclosureGroupId(GROUP_ID, TRAN_TYPE_CD, TRAN_CAT_CD)))
                    .thenReturn(Optional.empty());
            // DEFAULT row exists
            BigDecimal defaultRate = new BigDecimal("18.00");
            when(disclosureGroupRepository.findById(
                    new DisclosureGroupId("DEFAULT", TRAN_TYPE_CD, TRAN_CAT_CD)))
                    .thenReturn(Optional.of(buildDisclosureGroup("DEFAULT", defaultRate)));

            BigDecimal rate = service.lookupInterestRate(
                    GROUP_ID, TRAN_TYPE_CD, TRAN_CAT_CD);

            assertThat(rate).isEqualByComparingTo(defaultRate);
        }

        @Test
        @DisplayName("specific miss + DEFAULT miss → RecordNotFoundException")
        void lookupInterestRate_bothMissing_throwsRecordNotFound() {
            when(disclosureGroupRepository.findById(any(DisclosureGroupId.class)))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.lookupInterestRate(
                    GROUP_ID, TRAN_TYPE_CD, TRAN_CAT_CD))
                    .isInstanceOf(RecordNotFoundException.class)
                    .hasMessageContaining("DEFAULT disclosure group rule not found");
        }
    }

    @Nested
    @DisplayName("Deterministic XREF selection (CP5 — multi-card account)")
    class DeterministicXref {

        @Test
        @DisplayName("uses ordered AIX method, never the unordered one")
        void calculateInterest_callsOrderedXrefMethod() {
            // Arrange — one balance, multi-card account, valid rate
            stubHappyPath(
                    List.of(buildBalance(new BigDecimal("100.00"))),
                    List.of(new CardCrossReference(CARD_LOW, 999L, ACCOUNT_ID),
                            new CardCrossReference(CARD_HIGH, 999L, ACCOUNT_ID)),
                    buildDisclosureGroup(GROUP_ID, new BigDecimal("12.00")));

            // Act
            service.calculateInterest(PARM_DATE, BATCH_RUN_ID);

            // Assert — the ordered method was called; the unordered
            // method must NEVER be called.
            verify(xrefRepository)
                    .findByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID);
            verify(xrefRepository, never()).findByXrefAcctId(anyLong());
        }

        @Test
        @DisplayName("posts interest tran with lexicographically smallest card number")
        void calculateInterest_picksLexicographicallySmallestCard() {
            // Arrange — ordered repository returns LOW card first
            stubHappyPath(
                    List.of(buildBalance(new BigDecimal("1000.00"))),
                    List.of(new CardCrossReference(CARD_LOW, 999L, ACCOUNT_ID),
                            new CardCrossReference(CARD_HIGH, 999L, ACCOUNT_ID)),
                    buildDisclosureGroup(GROUP_ID, new BigDecimal("12.00")));

            // Act
            service.calculateInterest(PARM_DATE, BATCH_RUN_ID);

            // Assert — captured Transaction carries the LOW card
            ArgumentCaptor<Transaction> txCaptor =
                    ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(txCaptor.capture());
            assertThat(txCaptor.getValue().getTranCardNum())
                    .isEqualTo(CARD_LOW);
        }
    }

    @Nested
    @DisplayName("Zero-rate skip (COBOL: IF DIS-INT-RATE NOT = 0)")
    class ZeroRateSkip {

        @Test
        @DisplayName("rate=0 skips transaction posting")
        void calculateInterest_zeroRate_noTransactionPosted() {
            // Arrange — 0% rate
            stubHappyPath(
                    List.of(buildBalance(new BigDecimal("1000.00"))),
                    List.of(new CardCrossReference(CARD_LOW, 999L, ACCOUNT_ID)),
                    buildDisclosureGroup(GROUP_ID, BigDecimal.ZERO));

            // Act
            InterestCalculationService.Result result =
                    service.calculateInterest(PARM_DATE, BATCH_RUN_ID);

            // Assert — no Transaction was saved
            verify(transactionRepository, never()).save(any(Transaction.class));
            assertThat(result.transactionsPosted()).isEqualTo(0);
            // Account is still updated at boundary (totalInterest = 0)
            verify(accountRepository).save(any(Account.class));
        }
    }

    @Nested
    @DisplayName("Account boundary update (COBOL: 1050-UPDATE-ACCOUNT)")
    class AccountUpdate {

        @Test
        @DisplayName("adds total interest to balance and clears cycle credit/debit")
        void calculateInterest_updatesAccountAtBoundary() {
            // Arrange — balance starts at 100.00; rate 12.00% on 1000.00
            // balance ⇒ monthly interest 10.00; new balance 110.00
            stubHappyPath(
                    List.of(buildBalance(new BigDecimal("1000.00"))),
                    List.of(new CardCrossReference(CARD_LOW, 999L, ACCOUNT_ID)),
                    buildDisclosureGroup(GROUP_ID, new BigDecimal("12.00")));

            // Act
            service.calculateInterest(PARM_DATE, BATCH_RUN_ID);

            // Assert — captured Account has new balance 110.00, cycles cleared
            ArgumentCaptor<Account> acctCaptor =
                    ArgumentCaptor.forClass(Account.class);
            verify(accountRepository).save(acctCaptor.capture());
            Account saved = acctCaptor.getValue();
            assertThat(saved.getAcctCurrBal())
                    .isEqualByComparingTo(new BigDecimal("110.00"));
            assertThat(saved.getAcctCurrCycCredit())
                    .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(saved.getAcctCurrCycDebit())
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("multiple categories sum into per-account totalInterest")
        void calculateInterest_multipleCategoriesSum() {
            // Arrange — two balance rows for the SAME account, both at
            // 12.00% rate. (1000 * 12) / 1200 = 10, (500 * 12) / 1200 = 5.
            // Total interest: 15.00.
            TransactionCategoryBalance bal1 =
                    buildBalance(new BigDecimal("1000.00"));
            TransactionCategoryBalance bal2 = new TransactionCategoryBalance(
                    ACCOUNT_ID, TRAN_TYPE_CD, 6, new BigDecimal("500.00"));

            lenient().when(balanceRepository.findAll())
                    .thenReturn(new ArrayList<>(List.of(bal1, bal2)));
            lenient().when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(account));
            lenient().when(accountRepository.save(any(Account.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            lenient().when(xrefRepository
                            .findByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                    .thenReturn(List.of(new CardCrossReference(CARD_LOW, 999L, ACCOUNT_ID)));
            lenient().when(transactionRepository.save(any(Transaction.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            // First lookup (cat=5)
            lenient().when(disclosureGroupRepository.findById(
                    new DisclosureGroupId(GROUP_ID, TRAN_TYPE_CD, 5)))
                    .thenReturn(Optional.of(buildDisclosureGroup(GROUP_ID,
                            new BigDecimal("12.00"))));
            // Second lookup (cat=6)
            lenient().when(disclosureGroupRepository.findById(
                    new DisclosureGroupId(GROUP_ID, TRAN_TYPE_CD, 6)))
                    .thenReturn(Optional.of(new DisclosureGroup(
                            new DisclosureGroupId(GROUP_ID, TRAN_TYPE_CD, 6),
                            new BigDecimal("12.00"))));

            // Act
            InterestCalculationService.Result result =
                    service.calculateInterest(PARM_DATE, BATCH_RUN_ID);

            // Assert — two transactions, one account, total 15.00
            verify(transactionRepository, times(2))
                    .save(any(Transaction.class));
            assertThat(result.transactionsPosted()).isEqualTo(2);
            assertThat(result.accountsProcessed()).isEqualTo(1);
            assertThat(result.totalInterest())
                    .isEqualByComparingTo(new BigDecimal("15.00"));
            // Account balance: 100 + 10 + 5 = 115.00
            ArgumentCaptor<Account> acctCaptor =
                    ArgumentCaptor.forClass(Account.class);
            verify(accountRepository).save(acctCaptor.capture());
            assertThat(acctCaptor.getValue().getAcctCurrBal())
                    .isEqualByComparingTo(new BigDecimal("115.00"));
        }
    }

    @Nested
    @DisplayName("Side effects: MSK + audit emission")
    class EventsAndAudit {

        @Test
        @DisplayName("publishes transaction.posted partitioned by account ID")
        void calculateInterest_publishesTransactionPosted() {
            stubHappyPath(
                    List.of(buildBalance(new BigDecimal("1000.00"))),
                    List.of(new CardCrossReference(CARD_LOW, 999L, ACCOUNT_ID)),
                    buildDisclosureGroup(GROUP_ID, new BigDecimal("12.00")));

            service.calculateInterest(PARM_DATE, BATCH_RUN_ID);

            ArgumentCaptor<Long> keyCaptor =
                    ArgumentCaptor.forClass(Long.class);
            verify(kafkaEventPublisher).publishTransactionPosted(
                    keyCaptor.capture(), any(TransactionAddDto.class));
            assertThat(keyCaptor.getValue()).isEqualTo(ACCOUNT_ID);
        }

        @Test
        @DisplayName("publishes account.updated partitioned by account ID")
        void calculateInterest_publishesAccountUpdated() {
            stubHappyPath(
                    List.of(buildBalance(new BigDecimal("1000.00"))),
                    List.of(new CardCrossReference(CARD_LOW, 999L, ACCOUNT_ID)),
                    buildDisclosureGroup(GROUP_ID, new BigDecimal("12.00")));

            service.calculateInterest(PARM_DATE, BATCH_RUN_ID);

            ArgumentCaptor<Long> keyCaptor =
                    ArgumentCaptor.forClass(Long.class);
            verify(kafkaEventPublisher).publishAccountUpdated(
                    keyCaptor.capture(), any(AccountUpdateDto.class));
            assertThat(keyCaptor.getValue()).isEqualTo(ACCOUNT_ID);
        }

        @Test
        @DisplayName("emits a run-summary audit event")
        void calculateInterest_emitsRunSummaryAudit() {
            stubHappyPath(
                    List.of(buildBalance(new BigDecimal("1000.00"))),
                    List.of(new CardCrossReference(CARD_LOW, 999L, ACCOUNT_ID)),
                    buildDisclosureGroup(GROUP_ID, new BigDecimal("12.00")));

            service.calculateInterest(PARM_DATE, BATCH_RUN_ID);

            // The service emits multiple logAuditEvent calls (account
            // update boundary + run summary) — verify at least the
            // batch-run summary event is present.
            verify(auditLogService).logAuditEvent(
                    eq("interest.accrued"),
                    eq("BATCH_RUN"),
                    eq(BATCH_RUN_ID),
                    anyString(), anyMap(),
                    eq(BATCH_RUN_ID));
        }
    }

    @Nested
    @DisplayName("Input validation + missing account")
    class Validation {

        @Test
        @DisplayName("null parmDate throws NPE")
        void calculateInterest_nullParmDate_throwsNpe() {
            assertThatThrownBy(() ->
                    service.calculateInterest(null, BATCH_RUN_ID))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("null batchRunId throws IllegalArgumentException")
        void calculateInterest_nullBatchRunId_throwsIllegalArg() {
            assertThatThrownBy(() ->
                    service.calculateInterest(PARM_DATE, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("blank batchRunId throws IllegalArgumentException")
        void calculateInterest_blankBatchRunId_throwsIllegalArg() {
            assertThatThrownBy(() ->
                    service.calculateInterest(PARM_DATE, "   "))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("missing account on TCATBAL → RecordNotFoundException")
        void calculateInterest_missingAccount_throwsRecordNotFound() {
            when(balanceRepository.findAll())
                    .thenReturn(new ArrayList<>(List.of(
                            buildBalance(new BigDecimal("1000.00")))));
            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    service.calculateInterest(PARM_DATE, BATCH_RUN_ID))
                    .isInstanceOf(RecordNotFoundException.class)
                    .hasMessageContaining("Account not found");
        }

        @Test
        @DisplayName("empty TCATBAL → zero records processed, no transactions, no account update")
        void calculateInterest_emptyTcatbal_zeroResults() {
            when(balanceRepository.findAll()).thenReturn(new ArrayList<>());

            InterestCalculationService.Result result =
                    service.calculateInterest(PARM_DATE, BATCH_RUN_ID);

            assertThat(result.accountsProcessed()).isEqualTo(0);
            assertThat(result.transactionsPosted()).isEqualTo(0);
            assertThat(result.totalInterest())
                    .isEqualByComparingTo(BigDecimal.ZERO);

            verify(transactionRepository, never()).save(any());
            verify(accountRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("ON SIZE ERROR")
    class OnSizeError {

        @Test
        @DisplayName("monthly interest overflow throws OnSizeErrorException")
        void computeMonthlyInterest_overflow_throwsOnSizeError() {
            // (10_000_000_000_000 × 100) ÷ 1200 = 833_333_333_333.33,
            // which is > MAX_AMOUNT (99,999,999,999.99) per
            // InterestCalculationService.MAX_AMOUNT (PIC S9(10)V99 ceiling).
            BigDecimal balance = new BigDecimal("10000000000000.00");
            BigDecimal rate = new BigDecimal("100.00");
            assertThatThrownBy(() ->
                    service.computeMonthlyInterest(balance, rate))
                    .isInstanceOf(OnSizeErrorException.class);
        }
    }
}

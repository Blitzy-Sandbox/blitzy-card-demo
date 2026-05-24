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
import com.awsm2.carddemo.adapter.KafkaEventPublisher;
import com.awsm2.carddemo.domain.Account;
import com.awsm2.carddemo.domain.CardCrossReference;
import com.awsm2.carddemo.domain.DisclosureGroup;
import com.awsm2.carddemo.domain.DisclosureGroup.DisclosureGroupId;
import com.awsm2.carddemo.domain.Transaction;
import com.awsm2.carddemo.domain.TransactionCategoryBalance;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
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
 * {@code (balance * rate) / 1200}, and updates the account at each
 * boundary. The Java target aggregates per-account interest and writes
 * ONE per-account {@link Transaction} carrying the total interest
 * (per AAP &sect;0.4.1 schema).</p>
 *
 * <p><b>Behavioural invariants locked by this suite.</b></p>
 * <ol>
 *   <li><b>BigDecimal arithmetic</b> &mdash;
 *       {@code (balance × rate) ÷ 1200} computed with the literal
 *       {@code BigDecimal.valueOf(1200L)} divisor (AAP &sect;0.6.1),
 *       {@code scale=2}, {@code RoundingMode.HALF_EVEN}, no
 *       float/double.</li>
 *   <li><b>ON SIZE ERROR</b> &mdash; values that overflow the
 *       {@code PIC S9(10)V99} ceiling trigger
 *       {@link OnSizeErrorException}.</li>
 *   <li><b>Deterministic XREF selection</b> &mdash; uses the ORDERED
 *       repository method
 *       {@link CardCrossReferenceRepository#findByXrefAcctIdOrderByXrefCardNumAsc(Long)}.</li>
 *   <li><b>DEFAULT-group fallback</b> &mdash; missing specific
 *       disclosure-group key falls back to the literal
 *       {@code "DEFAULT"} group; double-miss returns zero rate (the
 *       account simply accrues no interest from that category).</li>
 *   <li><b>Zero rate skip</b> &mdash; a 0% rate does NOT post a
 *       transaction (matches COBOL {@code IF DIS-INT-RATE NOT = 0});
 *       the per-account post short-circuits when totalInt is zero.</li>
 *   <li><b>Account update at boundary</b> &mdash; total interest is
 *       added to the account balance and cycle credit/debit are
 *       cleared at each account boundary and at end-of-file.</li>
 *   <li><b>MSK + audit emission</b> &mdash; per-account
 *       {@code ledger.balanced} event partitioned by acct ID, plus per-
 *       transaction and per-run audit events.</li>
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
     * {@link InterestCalculationService#calculateInterest(LocalDate)}
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
        @DisplayName("simple case: (1000 * 12%) / 1200 = 10.00 per category")
        void simpleCase() {
            stubHappyPath(
                    List.of(buildBalance(new BigDecimal("1000.00"))),
                    List.of(new CardCrossReference(CARD_LOW, 999L, ACCOUNT_ID)),
                    buildDisclosureGroup(GROUP_ID, new BigDecimal("12.00")));

            InterestCalculationService.InterestResult result =
                    service.calculateInterest(PARM_DATE);

            assertThat(result.grandTotal())
                    .isEqualByComparingTo(new BigDecimal("10.00"));
            assertThat(result.acctCount()).isEqualTo(1);
            assertThat(result.tcatCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("HALF_EVEN banker's rounding: 0.000... rounds to 0.00")
        void halfEvenRoundingToZero() {
            // (100.00 * 0.005) / 1200 = 0.5 / 1200 = 0.000416666...
            // Scale=2 → HALF_EVEN rounds to 0.00 → no posting (skip)
            stubHappyPath(
                    List.of(buildBalance(new BigDecimal("100.00"))),
                    List.of(new CardCrossReference(CARD_LOW, 999L, ACCOUNT_ID)),
                    buildDisclosureGroup(GROUP_ID, new BigDecimal("0.005")));

            InterestCalculationService.InterestResult result =
                    service.calculateInterest(PARM_DATE);

            assertThat(result.grandTotal())
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("divisor is literal 1200 (preserved per AAP §0.6.1)")
        void divisorIs1200() {
            // (24000 * 12.00) / 1200 = 288000 / 1200 = 240.00
            stubHappyPath(
                    List.of(buildBalance(new BigDecimal("24000.00"))),
                    List.of(new CardCrossReference(CARD_LOW, 999L, ACCOUNT_ID)),
                    buildDisclosureGroup(GROUP_ID, new BigDecimal("12.00")));

            InterestCalculationService.InterestResult result =
                    service.calculateInterest(PARM_DATE);

            assertThat(result.grandTotal())
                    .isEqualByComparingTo(new BigDecimal("240.00"));
            assertThat(result.grandTotal().scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("exact result preserved: (1200 * 1) / 1200 = 1.00")
        void exactRoundingCase() {
            stubHappyPath(
                    List.of(buildBalance(new BigDecimal("1200.00"))),
                    List.of(new CardCrossReference(CARD_LOW, 999L, ACCOUNT_ID)),
                    buildDisclosureGroup(GROUP_ID, new BigDecimal("1.00")));

            InterestCalculationService.InterestResult result =
                    service.calculateInterest(PARM_DATE);

            assertThat(result.grandTotal())
                    .isEqualByComparingTo(new BigDecimal("1.00"));
            assertThat(result.grandTotal().scale()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("DEFAULT-group fallback (COBOL: 1200-A-GET-DEFAULT-INT-RATE)")
    class DisclosureGroupLookup {

        @Test
        @DisplayName("specific lookup hit — uses specific rate")
        void specificHit() {
            // Specific lookup hits with 9.50% rate; computed interest =
            // (1000 * 9.50) / 1200 = 7.916... → 7.92 (HALF_EVEN)
            stubHappyPath(
                    List.of(buildBalance(new BigDecimal("1000.00"))),
                    List.of(new CardCrossReference(CARD_LOW, 999L, ACCOUNT_ID)),
                    buildDisclosureGroup(GROUP_ID, new BigDecimal("9.50")));

            InterestCalculationService.InterestResult result =
                    service.calculateInterest(PARM_DATE);

            assertThat(result.grandTotal())
                    .isEqualByComparingTo(new BigDecimal("7.92"));
        }

        @Test
        @DisplayName("specific miss → falls back to DEFAULT row")
        void defaultFallback() {
            // Specific lookup misses; DEFAULT row exists with 18.00% rate.
            // Computed interest = (1000 * 18.00) / 1200 = 18000/1200 = 15.00
            lenient().when(balanceRepository.findAll())
                    .thenReturn(new ArrayList<>(List.of(
                            buildBalance(new BigDecimal("1000.00")))));
            lenient().when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(account));
            lenient().when(accountRepository.save(any(Account.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            lenient().when(xrefRepository
                            .findByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                    .thenReturn(List.of(new CardCrossReference(CARD_LOW, 999L, ACCOUNT_ID)));
            lenient().when(transactionRepository.save(any(Transaction.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // Specific lookup misses
            when(disclosureGroupRepository.findById(
                    new DisclosureGroupId(GROUP_ID, TRAN_TYPE_CD, TRAN_CAT_CD)))
                    .thenReturn(Optional.empty());
            // DEFAULT row exists
            when(disclosureGroupRepository.findById(
                    new DisclosureGroupId("DEFAULT", TRAN_TYPE_CD, TRAN_CAT_CD)))
                    .thenReturn(Optional.of(buildDisclosureGroup(
                            "DEFAULT", new BigDecimal("18.00"))));

            InterestCalculationService.InterestResult result =
                    service.calculateInterest(PARM_DATE);

            assertThat(result.grandTotal())
                    .isEqualByComparingTo(new BigDecimal("15.00"));
        }

        @Test
        @DisplayName("specific miss + DEFAULT miss → zero interest, no abend")
        void bothMissing_zeroInterest() {
            // Both lookups miss. The Java target deliberately treats
            // this as zero rate (continue the batch) rather than abend
            // like the COBOL source — a resilience improvement that
            // preserves business continuity per the AAP cloud-migration
            // intent.
            lenient().when(balanceRepository.findAll())
                    .thenReturn(new ArrayList<>(List.of(
                            buildBalance(new BigDecimal("1000.00")))));
            lenient().when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(account));
            lenient().when(disclosureGroupRepository.findById(any(DisclosureGroupId.class)))
                    .thenReturn(Optional.empty());

            InterestCalculationService.InterestResult result =
                    service.calculateInterest(PARM_DATE);

            assertThat(result.grandTotal())
                    .isEqualByComparingTo(BigDecimal.ZERO);
            // No transaction posted, no account update -- short-circuit
            // because totalInt is zero.
            verify(transactionRepository, never()).save(any(Transaction.class));
            verify(accountRepository, never()).save(any(Account.class));
        }
    }

    @Nested
    @DisplayName("Deterministic XREF selection (CP5 — multi-card account)")
    class DeterministicXref {

        @Test
        @DisplayName("uses ordered AIX method, never the unordered one")
        void callsOrderedXrefMethod() {
            stubHappyPath(
                    List.of(buildBalance(new BigDecimal("100.00"))),
                    List.of(new CardCrossReference(CARD_LOW, 999L, ACCOUNT_ID),
                            new CardCrossReference(CARD_HIGH, 999L, ACCOUNT_ID)),
                    buildDisclosureGroup(GROUP_ID, new BigDecimal("12.00")));

            service.calculateInterest(PARM_DATE);

            verify(xrefRepository)
                    .findByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID);
            verify(xrefRepository, never()).findByXrefAcctId(anyLong());
        }

        @Test
        @DisplayName("posts interest tran with lexicographically smallest card number")
        void picksLexicographicallySmallestCard() {
            stubHappyPath(
                    List.of(buildBalance(new BigDecimal("1000.00"))),
                    List.of(new CardCrossReference(CARD_LOW, 999L, ACCOUNT_ID),
                            new CardCrossReference(CARD_HIGH, 999L, ACCOUNT_ID)),
                    buildDisclosureGroup(GROUP_ID, new BigDecimal("12.00")));

            service.calculateInterest(PARM_DATE);

            ArgumentCaptor<Transaction> txCaptor =
                    ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(txCaptor.capture());
            assertThat(txCaptor.getValue().getTranCardNum())
                    .isEqualTo(CARD_LOW);
        }

        @Test
        @DisplayName("blank card number when no XREF row exists for the account")
        void emptyXref_postsBlankCard() {
            stubHappyPath(
                    List.of(buildBalance(new BigDecimal("1000.00"))),
                    List.of(),  // no XREF rows
                    buildDisclosureGroup(GROUP_ID, new BigDecimal("12.00")));

            service.calculateInterest(PARM_DATE);

            ArgumentCaptor<Transaction> txCaptor =
                    ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(txCaptor.capture());
            assertThat(txCaptor.getValue().getTranCardNum()).isEqualTo("");
        }
    }

    @Nested
    @DisplayName("Zero-rate skip (COBOL: IF DIS-INT-RATE NOT = 0)")
    class ZeroRateSkip {

        @Test
        @DisplayName("rate=0 results in zero interest → no transaction or account update")
        void zeroRate_noTransactionPosted() {
            stubHappyPath(
                    List.of(buildBalance(new BigDecimal("1000.00"))),
                    List.of(new CardCrossReference(CARD_LOW, 999L, ACCOUNT_ID)),
                    buildDisclosureGroup(GROUP_ID, BigDecimal.ZERO));

            InterestCalculationService.InterestResult result =
                    service.calculateInterest(PARM_DATE);

            // No transaction, no account update -- per-account
            // post short-circuits when totalInt is zero.
            verify(transactionRepository, never()).save(any(Transaction.class));
            verify(accountRepository, never()).save(any(Account.class));
            assertThat(result.grandTotal()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(result.acctCount()).isEqualTo(1);
            assertThat(result.tcatCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Account boundary update (COBOL: 1050-UPDATE-ACCOUNT)")
    class AccountUpdate {

        @Test
        @DisplayName("adds total interest to balance and clears cycle credit/debit")
        void updatesAccountAtBoundary() {
            // Arrange — balance starts at 100.00; rate 12.00% on 1000.00
            // balance ⇒ monthly interest 10.00; new balance 110.00
            stubHappyPath(
                    List.of(buildBalance(new BigDecimal("1000.00"))),
                    List.of(new CardCrossReference(CARD_LOW, 999L, ACCOUNT_ID)),
                    buildDisclosureGroup(GROUP_ID, new BigDecimal("12.00")));

            service.calculateInterest(PARM_DATE);

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
        @DisplayName("multiple categories sum into ONE per-account transaction")
        void multipleCategoriesSum() {
            // Arrange — two balance rows for the SAME account, both at
            // 12.00% rate. (1000 * 12) / 1200 = 10, (500 * 12) / 1200 = 5.
            // Per-account total: 15.00 -- written as ONE Transaction.
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

            InterestCalculationService.InterestResult result =
                    service.calculateInterest(PARM_DATE);

            // Per AAP §0.4.1 schema: ONE per-account Transaction
            // carrying the total interest across all categories.
            ArgumentCaptor<Transaction> txCaptor =
                    ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(txCaptor.capture());
            assertThat(txCaptor.getValue().getTranAmt())
                    .isEqualByComparingTo(new BigDecimal("15.00"));
            assertThat(result.tcatCount()).isEqualTo(2);
            assertThat(result.acctCount()).isEqualTo(1);
            assertThat(result.grandTotal())
                    .isEqualByComparingTo(new BigDecimal("15.00"));
            // Account balance: 100 + 15 = 115.00
            ArgumentCaptor<Account> acctCaptor =
                    ArgumentCaptor.forClass(Account.class);
            verify(accountRepository).save(acctCaptor.capture());
            assertThat(acctCaptor.getValue().getAcctCurrBal())
                    .isEqualByComparingTo(new BigDecimal("115.00"));
        }
    }

    @Nested
    @DisplayName("Interest transaction record content (COBOL: 1300-B-WRITE-TX)")
    class InterestTransactionContent {

        @Test
        @DisplayName("uses TYPE='01', CAT=5, SOURCE='System', MERCHANT-ID=0")
        void verbatimCobolLiterals() {
            stubHappyPath(
                    List.of(buildBalance(new BigDecimal("1000.00"))),
                    List.of(new CardCrossReference(CARD_LOW, 999L, ACCOUNT_ID)),
                    buildDisclosureGroup(GROUP_ID, new BigDecimal("12.00")));

            service.calculateInterest(PARM_DATE);

            ArgumentCaptor<Transaction> txCaptor =
                    ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(txCaptor.capture());
            Transaction tx = txCaptor.getValue();

            assertThat(tx.getTranTypeCd()).isEqualTo("01");
            assertThat(tx.getTranCatCd()).isEqualTo(5);
            assertThat(tx.getTranSource()).isEqualTo("System");
            assertThat(tx.getTranMerchantId()).isEqualTo(0L);
            assertThat(tx.getTranMerchantName()).isEqualTo("");
            assertThat(tx.getTranMerchantCity()).isEqualTo("");
            assertThat(tx.getTranMerchantZip()).isEqualTo("");
            assertThat(tx.getTranDesc()).contains("Int. for a/c");
            assertThat(tx.getTranAmt())
                    .isEqualByComparingTo(new BigDecimal("10.00"));
            // TRAN-ID is 16 chars
            assertThat(tx.getTranId()).hasSize(16);
            // Timestamps populated
            assertThat(tx.getTranOrigTs()).isNotNull();
            assertThat(tx.getTranProcTs()).isNotNull();
        }

        @Test
        @DisplayName("description includes zero-padded account ID")
        void descriptionIncludesPaddedAcctId() {
            stubHappyPath(
                    List.of(buildBalance(new BigDecimal("1000.00"))),
                    List.of(new CardCrossReference(CARD_LOW, 999L, ACCOUNT_ID)),
                    buildDisclosureGroup(GROUP_ID, new BigDecimal("12.00")));

            service.calculateInterest(PARM_DATE);

            ArgumentCaptor<Transaction> txCaptor =
                    ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(txCaptor.capture());
            assertThat(txCaptor.getValue().getTranDesc())
                    .isEqualTo("Int. for a/c 10000000001");
        }
    }

    @Nested
    @DisplayName("Side effects: MSK + audit emission")
    class EventsAndAudit {

        @Test
        @DisplayName("publishes ledger.balanced partitioned by account ID")
        void publishesLedgerBalanced() {
            stubHappyPath(
                    List.of(buildBalance(new BigDecimal("1000.00"))),
                    List.of(new CardCrossReference(CARD_LOW, 999L, ACCOUNT_ID)),
                    buildDisclosureGroup(GROUP_ID, new BigDecimal("12.00")));

            service.calculateInterest(PARM_DATE);

            ArgumentCaptor<Long> keyCaptor =
                    ArgumentCaptor.forClass(Long.class);
            verify(kafkaEventPublisher).publishLedgerBalanced(
                    keyCaptor.capture(), any());
            assertThat(keyCaptor.getValue()).isEqualTo(ACCOUNT_ID);
        }

        @Test
        @DisplayName("emits INTEREST_CALCULATED transaction-event audit")
        void emitsTransactionEventAudit() {
            stubHappyPath(
                    List.of(buildBalance(new BigDecimal("1000.00"))),
                    List.of(new CardCrossReference(CARD_LOW, 999L, ACCOUNT_ID)),
                    buildDisclosureGroup(GROUP_ID, new BigDecimal("12.00")));

            service.calculateInterest(PARM_DATE);

            verify(auditLogService).logTransactionEvent(
                    any(),  // tranId
                    any(),  // acctId
                    any(),  // operator
                    any(),  // eventType
                    any(),  // reasonCode
                    any(),  // payload
                    any()); // correlation
        }

        @Test
        @DisplayName("emits a run-summary audit event")
        void emitsRunSummaryAudit() {
            stubHappyPath(
                    List.of(buildBalance(new BigDecimal("1000.00"))),
                    List.of(new CardCrossReference(CARD_LOW, 999L, ACCOUNT_ID)),
                    buildDisclosureGroup(GROUP_ID, new BigDecimal("12.00")));

            service.calculateInterest(PARM_DATE);

            verify(auditLogService).logAuditEvent(
                    any(), any(), any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("Input validation + missing account")
    class Validation {

        @Test
        @DisplayName("null parmDate throws NPE")
        void nullParmDate_throwsNpe() {
            assertThatThrownBy(() -> service.calculateInterest(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("missing account on TCATBAL → RecordNotFoundException")
        void missingAccount_throwsRecordNotFound() {
            when(balanceRepository.findAll())
                    .thenReturn(new ArrayList<>(List.of(
                            buildBalance(new BigDecimal("1000.00")))));
            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.calculateInterest(PARM_DATE))
                    .isInstanceOf(RecordNotFoundException.class)
                    .hasMessageContaining("Account not found");
        }

        @Test
        @DisplayName("empty TCATBAL → zero counts, no transactions, no account update")
        void emptyTcatbal_zeroResults() {
            when(balanceRepository.findAll()).thenReturn(new ArrayList<>());

            InterestCalculationService.InterestResult result =
                    service.calculateInterest(PARM_DATE);

            assertThat(result.acctCount()).isEqualTo(0);
            assertThat(result.tcatCount()).isEqualTo(0);
            assertThat(result.grandTotal())
                    .isEqualByComparingTo(BigDecimal.ZERO);

            verify(transactionRepository, never()).save(any());
            verify(accountRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("ON SIZE ERROR (COBOL: PIC S9(10)V99 ceiling)")
    class OnSizeError {

        @Test
        @DisplayName("monthly interest overflow throws OnSizeErrorException")
        void monthlyInterestOverflow_throwsOnSizeError() {
            // (10_000_000_000_000 × 100) ÷ 1200 = 833_333_333_333.33,
            // which is > MAX_AMOUNT (99,999,999,999.99) per
            // InterestCalculationService.MAX_AMOUNT (PIC S9(10)V99 ceiling).
            stubHappyPath(
                    List.of(buildBalance(new BigDecimal("10000000000000.00"))),
                    List.of(new CardCrossReference(CARD_LOW, 999L, ACCOUNT_ID)),
                    buildDisclosureGroup(GROUP_ID, new BigDecimal("100.00")));

            assertThatThrownBy(() -> service.calculateInterest(PARM_DATE))
                    .isInstanceOf(OnSizeErrorException.class);
        }
    }

    @Nested
    @DisplayName("postAccountInterest public entry point")
    class PostAccountInterestDirect {

        @Test
        @DisplayName("zero totalInt short-circuits — no DB writes, no events")
        void zeroTotalInt_shortCircuits() {
            service.postAccountInterest(ACCOUNT_ID, BigDecimal.ZERO, PARM_DATE);

            verify(accountRepository, never()).findById(any());
            verify(accountRepository, never()).save(any(Account.class));
            verify(transactionRepository, never()).save(any(Transaction.class));
        }

        @Test
        @DisplayName("non-zero totalInt updates account and writes one transaction")
        void nonZeroTotalInt_postsTransaction() {
            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(account));
            when(accountRepository.save(any(Account.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(transactionRepository.save(any(Transaction.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(xrefRepository.findByXrefAcctIdOrderByXrefCardNumAsc(ACCOUNT_ID))
                    .thenReturn(List.of(new CardCrossReference(CARD_LOW, 999L, ACCOUNT_ID)));

            service.postAccountInterest(ACCOUNT_ID,
                    new BigDecimal("15.00"), PARM_DATE);

            ArgumentCaptor<Transaction> txCaptor =
                    ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(txCaptor.capture());
            assertThat(txCaptor.getValue().getTranAmt())
                    .isEqualByComparingTo(new BigDecimal("15.00"));

            ArgumentCaptor<Account> acctCaptor =
                    ArgumentCaptor.forClass(Account.class);
            verify(accountRepository).save(acctCaptor.capture());
            assertThat(acctCaptor.getValue().getAcctCurrBal())
                    .isEqualByComparingTo(new BigDecimal("115.00"));
        }

        @Test
        @DisplayName("non-zero totalInt with missing account → RecordNotFoundException")
        void missingAccount_throwsRecordNotFound() {
            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.postAccountInterest(
                    ACCOUNT_ID, new BigDecimal("15.00"), PARM_DATE))
                    .isInstanceOf(RecordNotFoundException.class);
        }

        @Test
        @DisplayName("null acctId throws NPE")
        void nullAcctId_throwsNpe() {
            assertThatThrownBy(() -> service.postAccountInterest(
                    null, new BigDecimal("1.00"), PARM_DATE))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("null totalInt throws NPE")
        void nullTotalInt_throwsNpe() {
            assertThatThrownBy(() -> service.postAccountInterest(
                    ACCOUNT_ID, null, PARM_DATE))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("null parmDate throws NPE")
        void nullParmDate_throwsNpe() {
            assertThatThrownBy(() -> service.postAccountInterest(
                    ACCOUNT_ID, new BigDecimal("1.00"), null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}

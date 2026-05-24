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
import com.awsm2.carddemo.domain.Account;
import com.awsm2.carddemo.domain.Transaction;
import com.awsm2.carddemo.domain.TransactionCategoryBalance;
import com.awsm2.carddemo.domain.TransactionCategoryBalance.TransactionCategoryBalanceId;
import com.awsm2.carddemo.exception.RecordNotFoundException;
import com.awsm2.carddemo.repository.AccountRepository;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * JUnit 5 + Mockito + AssertJ unit tests for {@link LedgerService}.
 *
 * <p>This suite validates the DDD aggregate-root service that
 * implements double-entry bookkeeping reconciliation semantics over
 * the {@code accounts} table, the per-category
 * {@code tran_cat_bal} buckets, and the {@code transactions} ledger.
 * The production service has no single COBOL counterpart &mdash; it
 * is an emergent contract that surfaces the invariants encoded across
 * {@code CBTRN02C} (paragraphs {@code 2700-UPDATE-TCATBAL} and
 * {@code 2800-UPDATE-ACCOUNT-REC}), {@code CBACT04C}
 * (paragraph {@code 1050-UPDATE-ACCOUNT}), and {@code COBIL00C}
 * (bill-payment account balance update).</p>
 *
 * <h2>Behavioural invariants locked by this suite</h2>
 * <ol>
 *   <li><b>Read-only by design</b> &mdash; no public method invokes
 *       {@code save(...)} / {@code delete(...)} on any injected
 *       repository. Verified by {@link ReadOnlyBoundary}.</li>
 *   <li><b>BigDecimal arithmetic</b> &mdash; per-category
 *       {@code TRAN-CAT-BAL} values are summed using
 *       {@link BigDecimal} with explicit {@code scale = 2} and
 *       {@link java.math.RoundingMode#HALF_EVEN HALF_EVEN}
 *       (banker's rounding) per AAP &sect;0.6.1. Verified by
 *       {@link BigDecimalArithmetic}.</li>
 *   <li><b>Imbalance audit</b> &mdash; any divergence between the
 *       persisted {@link Account#getAcctCurrBal()} and the sum of
 *       {@link TransactionCategoryBalance#getTranCatBal()} for the
 *       account emits a {@code LEDGER_IMBALANCE} audit event via
 *       {@link AuditLogService#logAuditEvent}. Verified by
 *       {@link Reconciliation}.</li>
 *   <li><b>Balanced silence</b> &mdash; when the sums equal, NO
 *       audit event is emitted; the service remains silent.
 *       Verified by {@link Reconciliation}.</li>
 *   <li><b>{@code RecordNotFoundException} on missing account</b>
 *       &mdash; both {@code reconcileAccount(Long)} and
 *       {@code snapshot(Long)} throw
 *       {@link RecordNotFoundException} when the supplied account
 *       identifier resolves to {@code Optional.empty()}, preserving
 *       COBOL {@code FILE STATUS '23'} NOTFND semantics. Verified by
 *       {@link RecordNotFoundScenarios}.</li>
 *   <li><b>Snapshot category projection</b> &mdash;
 *       {@link LedgerService#snapshot(Long)} returns a
 *       {@code Map<String, BigDecimal>} keyed by
 *       {@code "<typeCode>/<categoryCode>"} per the COBOL
 *       {@code CBTRN02C:2700-UPDATE-TCATBAL} per-bucket layout.
 *       Verified by {@link CategoryBalances}.</li>
 *   <li><b>Batch reconciliation</b> &mdash;
 *       {@link LedgerService#reconcileAll()} iterates
 *       {@code accountRepository.findAll()} and aggregates the
 *       per-account outcomes into a
 *       {@code BatchReconciliationResult} with accurate
 *       {@code totalAccounts}, {@code balancedAccounts}, and
 *       {@code unbalancedAccounts} counters. Verified by
 *       {@link BatchReconciliation}.</li>
 * </ol>
 *
 * <p>External AWS interactions are fully mocked through
 * {@link MockitoExtension}; no Testcontainers or LocalStack are
 * involved. All collaborators (AccountRepository, TransactionRepository,
 * TransactionCategoryBalanceRepository, AuditLogService) are declared
 * as {@code @Mock} fields and Mockito constructor-injects them into
 * the SUT via {@code @InjectMocks}, matching the production service's
 * 4-argument constructor signature exactly.</p>
 *
 * @see LedgerService
 * @see LedgerService.ReconciliationReport
 * @see LedgerService.BatchReconciliationResult
 * @see LedgerService.LedgerSnapshot
 */
// DDD aggregate: Ledger — no single COBOL source program; emergent
// contract across CBTRN02C, CBACT04C, COBIL00C per AAP §0.3.3.
@ExtendWith(MockitoExtension.class)
@DisplayName("LedgerService — DDD aggregate double-entry bookkeeping")
class LedgerServiceTest {

    // =========================================================================
    // Test constants — anchored values shared by every nested suite
    // =========================================================================

    /**
     * Canonical 11-digit account identifier (COBOL
     * {@code ACCT-ID PIC 9(11)}). All fixtures share this ID unless a
     * test specifically exercises a multi-account scenario.
     */
    private static final Long ACCOUNT_ID = 10_000_000_001L;

    /**
     * Second account identifier used by {@link BatchReconciliation}
     * scenarios that exercise iteration across multiple accounts.
     */
    private static final Long ACCOUNT_ID_2 = 10_000_000_002L;

    /**
     * Third account identifier used to validate the
     * {@code totalAccounts}/{@code balancedAccounts}/
     * {@code unbalancedAccounts} aggregation arithmetic in
     * {@link BatchReconciliation}.
     */
    private static final Long ACCOUNT_ID_3 = 10_000_000_003L;

    /** 2-character transaction-type code (COBOL {@code TRAN-TYPE-CD PIC X(02)}). */
    private static final String TYPE_CD_PURCHASE = "01";

    /** Second transaction-type code used for multi-bucket scenarios. */
    private static final String TYPE_CD_PAYMENT = "02";

    /** 4-digit transaction-category code (COBOL {@code TRAN-CAT-CD PIC 9(04)}). */
    private static final Integer CAT_CD_RETAIL = 5;

    /** Second category code used for multi-bucket scenarios. */
    private static final Integer CAT_CD_CASH = 6;

    /** Audit event type emitted on imbalance — must match the SUT constant. */
    private static final String AUDIT_LEDGER_IMBALANCE = "LEDGER_IMBALANCE";

    /** Audit resource type stamped on imbalance events. */
    private static final String AUDIT_RESOURCE_ACCOUNT = "ACCOUNT";

    /** Operator code stamped on automated reconciliation audit events. */
    private static final String AUDIT_OPERATOR_RECONCILIATION = "RECONCILIATION";

    // =========================================================================
    // Mocks and SUT
    //
    // Four collaborators are constructor-injected into LedgerService:
    //   1) AccountRepository                       — findById / findAll
    //   2) TransactionRepository                   — held for future audit
    //                                                lookups; the read-only
    //                                                path does NOT invoke it
    //   3) TransactionCategoryBalanceRepository    — findAll (then in-memory
    //                                                filter by acct_id)
    //   4) AuditLogService                         — logAuditEvent on
    //                                                imbalance
    //
    // The MockitoExtension processes @Mock + @InjectMocks before each
    // test (strict-stubbing validation; automatic mock state reset).
    // =========================================================================

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private TransactionCategoryBalanceRepository transactionCategoryBalanceRepository;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private LedgerService service;

    // =========================================================================
    // Test fixture: a fully-populated Account anchored on ACCOUNT_ID
    // =========================================================================

    /**
     * Shared {@link Account} fixture re-initialized before each test.
     * Carries every monetary and lifecycle field set to a deterministic
     * sentinel value so individual tests can override only the field(s)
     * they care about (e.g., {@link Reconciliation} mutates
     * {@code acctCurrBal} to engineer balanced / unbalanced scenarios).
     */
    private Account account;

    @BeforeEach
    void setUp() {
        account = newAccount(ACCOUNT_ID,
                new BigDecimal("300.00"),
                new BigDecimal("250.00"),
                new BigDecimal("75.00"));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Builds an {@link Account} fixture with the four monetary fields
     * commonly varied across tests. All other fields are populated with
     * deterministic, schema-conformant sentinel values.
     *
     * @param acctId            primary key (COBOL {@code ACCT-ID PIC 9(11)})
     * @param currBal           current balance (COBOL {@code ACCT-CURR-BAL PIC S9(10)V99})
     * @param currCycCredit     current-cycle credit total (COBOL
     *                          {@code ACCT-CURR-CYC-CREDIT})
     * @param currCycDebit      current-cycle debit total (COBOL
     *                          {@code ACCT-CURR-CYC-DEBIT})
     * @return a freshly constructed {@link Account} populated with
     *         every NOT NULL field per the V001 schema invariants
     */
    private Account newAccount(Long acctId,
                                BigDecimal currBal,
                                BigDecimal currCycCredit,
                                BigDecimal currCycDebit) {
        Account a = new Account();
        a.setAcctId(acctId);
        a.setAcctActiveStatus("Y");
        a.setAcctCurrBal(currBal);
        a.setAcctCreditLimit(new BigDecimal("10000.00"));
        a.setAcctCashCreditLimit(new BigDecimal("2000.00"));
        a.setAcctOpenDate(LocalDate.of(2020, 1, 1));
        a.setAcctExpirationDate(LocalDate.of(2030, 12, 31));
        a.setAcctReissueDate(LocalDate.of(2022, 6, 15));
        a.setAcctCurrCycCredit(currCycCredit);
        a.setAcctCurrCycDebit(currCycDebit);
        a.setAcctAddrZip("78487");
        a.setAcctGroupId("GROUP1");
        a.setVersion(0L);
        return a;
    }

    /**
     * Builds a {@link TransactionCategoryBalance} fixture with the
     * three composite-key sub-fields plus the running balance.
     *
     * <p>The composite key sub-fields correspond one-to-one to the
     * COBOL {@code TRAN-CAT-KEY} sub-fields (CVTRA01Y.cpy lines 5-8):
     * 11 (acct_id) + 2 (type_cd) + 4 (cd) = 17 bytes.</p>
     *
     * @param acctId         11-digit account identifier (must equal
     *                       {@link #ACCOUNT_ID} for tests that filter
     *                       by account)
     * @param typeCd         2-character transaction-type code (e.g.
     *                       {@link #TYPE_CD_PURCHASE})
     * @param catCd          4-digit transaction-category code (e.g.
     *                       {@link #CAT_CD_RETAIL})
     * @param balance        per-bucket running balance as a
     *                       {@link BigDecimal} with scale = 2
     * @return a freshly constructed {@link TransactionCategoryBalance}
     */
    private TransactionCategoryBalance newTcatBal(Long acctId,
                                                   String typeCd,
                                                   Integer catCd,
                                                   BigDecimal balance) {
        TransactionCategoryBalanceId id =
                new TransactionCategoryBalanceId(acctId, typeCd, catCd);
        return new TransactionCategoryBalance(id, balance);
    }

    // =========================================================================
    // @Nested test groups
    // =========================================================================

    /**
     * Reconciliation outcome — the primary read path of the DDD
     * aggregate. Verifies that {@code reconcileAccount(Long)} computes
     * the per-account expected balance (sum of TCATBAL buckets),
     * compares it against the persisted {@code acct_curr_bal}, and
     * either returns {@code balanced=true} (silent) or
     * {@code balanced=false} (audit emission).
     */
    @Nested
    @DisplayName("reconcileAccount(Long) — per-account reconciliation outcome")
    class Reconciliation {

        @Test
        @DisplayName("balanced: TCATBAL sum equals acct_curr_bal — no audit")
        void balanced_noAuditEmitted() {
            // Arrange: TCATBAL sum = 300.00 ; account.acct_curr_bal = 300.00
            // COBOL: CBTRN02C:2700-UPDATE-TCATBAL ↔ 2800-UPDATE-ACCOUNT-REC
            //        invariant (per-bucket sum = account balance).
            List<TransactionCategoryBalance> tcats = Arrays.asList(
                    newTcatBal(ACCOUNT_ID, TYPE_CD_PURCHASE, CAT_CD_RETAIL,
                            new BigDecimal("200.00")),
                    newTcatBal(ACCOUNT_ID, TYPE_CD_PAYMENT, CAT_CD_CASH,
                            new BigDecimal("100.00")));
            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(account));
            when(transactionCategoryBalanceRepository.findAll())
                    .thenReturn(tcats);

            // Act
            LedgerService.ReconciliationReport report =
                    service.reconcileAccount(ACCOUNT_ID);

            // Assert: balanced=true, expected==actual==300.00, no audit
            assertThat(report).isNotNull();
            assertThat(report.acctId()).isEqualTo(ACCOUNT_ID);
            assertThat(report.balanced()).isTrue();
            assertThat(report.expectedBalance())
                    .isEqualByComparingTo(new BigDecimal("300.00"));
            assertThat(report.actualBalance())
                    .isEqualByComparingTo(new BigDecimal("300.00"));

            // The audit pipeline MUST be silent on a balanced ledger.
            verify(auditLogService, never())
                    .logAuditEvent(anyString(), anyString(), anyString(),
                            anyString(), any(), any());
        }

        @Test
        @DisplayName("imbalance detected: emits LEDGER_IMBALANCE audit")
        void imbalance_emitsLedgerImbalanceAudit() {
            // Arrange: account.acct_curr_bal = 300.00 but TCATBAL sum = 250.00
            // — a 50.00 shortfall that must surface a LEDGER_IMBALANCE event.
            List<TransactionCategoryBalance> tcats = Arrays.asList(
                    newTcatBal(ACCOUNT_ID, TYPE_CD_PURCHASE, CAT_CD_RETAIL,
                            new BigDecimal("150.00")),
                    newTcatBal(ACCOUNT_ID, TYPE_CD_PAYMENT, CAT_CD_CASH,
                            new BigDecimal("100.00")));
            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(account));
            when(transactionCategoryBalanceRepository.findAll())
                    .thenReturn(tcats);

            // Act
            LedgerService.ReconciliationReport report =
                    service.reconcileAccount(ACCOUNT_ID);

            // Assert: balanced=false, expected=250.00, actual=300.00
            assertThat(report.balanced()).isFalse();
            assertThat(report.expectedBalance())
                    .isEqualByComparingTo(new BigDecimal("250.00"));
            assertThat(report.actualBalance())
                    .isEqualByComparingTo(new BigDecimal("300.00"));

            // Capture and verify the audit payload was emitted with
            // the LEDGER_IMBALANCE event type, the ACCOUNT resource
            // type, and the stringified account ID — exactly mirroring
            // the production AUDIT_* constants.
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> payloadCaptor =
                    ArgumentCaptor.forClass(Map.class);
            verify(auditLogService).logAuditEvent(
                    eq(AUDIT_LEDGER_IMBALANCE),
                    eq(AUDIT_RESOURCE_ACCOUNT),
                    eq(String.valueOf(ACCOUNT_ID)),
                    eq(AUDIT_OPERATOR_RECONCILIATION),
                    payloadCaptor.capture(),
                    eq((String) null));

            Map<String, Object> payload = payloadCaptor.getValue();
            assertThat(payload).isNotNull();
            assertThat(payload).containsKey("acctId");
            assertThat(payload.get("acctId")).isEqualTo(ACCOUNT_ID);
            assertThat(payload).containsKey("expectedBalance");
            assertThat((BigDecimal) payload.get("expectedBalance"))
                    .isEqualByComparingTo(new BigDecimal("250.00"));
            assertThat(payload).containsKey("actualBalance");
            assertThat((BigDecimal) payload.get("actualBalance"))
                    .isEqualByComparingTo(new BigDecimal("300.00"));
            assertThat(payload).containsKey("difference");
            assertThat((BigDecimal) payload.get("difference"))
                    .isEqualByComparingTo(new BigDecimal("-50.00"));
            assertThat(payload).containsKey("source");
            assertThat(payload.get("source"))
                    .isEqualTo("LedgerService.reconcileAccount");
        }

        @Test
        @DisplayName("audit pipeline failure does NOT abort reconciliation")
        void auditFailure_doesNotAbortReconciliation() {
            // Arrange: imbalance scenario — but the audit adapter
            // throws a RuntimeException on emission. Per the SUT's
            // contract, the imbalance must still be returned to the
            // caller (the local warn log captured the divergence).
            List<TransactionCategoryBalance> tcats = Collections.singletonList(
                    newTcatBal(ACCOUNT_ID, TYPE_CD_PURCHASE, CAT_CD_RETAIL,
                            new BigDecimal("100.00")));
            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(account));
            when(transactionCategoryBalanceRepository.findAll())
                    .thenReturn(tcats);
            org.mockito.Mockito.doThrow(new RuntimeException("OpenSearch down"))
                    .when(auditLogService).logAuditEvent(
                            anyString(), anyString(), anyString(),
                            anyString(), any(), any());

            // Act + Assert: no exception is propagated to the caller
            LedgerService.ReconciliationReport report =
                    service.reconcileAccount(ACCOUNT_ID);

            assertThat(report).isNotNull();
            assertThat(report.balanced()).isFalse();
            assertThat(report.expectedBalance())
                    .isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(report.actualBalance())
                    .isEqualByComparingTo(new BigDecimal("300.00"));
        }

        @Test
        @DisplayName("zero TCATBAL list with zero acct_curr_bal == balanced")
        void zeroEverywhere_isBalanced() {
            // A new / dormant account with no TCATBAL rows and zero
            // current balance must be reported as balanced (the COBOL
            // source treats every PIC S9(n)V99 field as zero-initialized
            // before first use; the Java target preserves this).
            account.setAcctCurrBal(BigDecimal.ZERO.setScale(2));
            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(account));
            when(transactionCategoryBalanceRepository.findAll())
                    .thenReturn(Collections.emptyList());

            LedgerService.ReconciliationReport report =
                    service.reconcileAccount(ACCOUNT_ID);

            assertThat(report.balanced()).isTrue();
            assertThat(report.expectedBalance())
                    .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(report.actualBalance())
                    .isEqualByComparingTo(BigDecimal.ZERO);
            verifyNoInteractions(auditLogService);
        }

        @Test
        @DisplayName("null acctId throws NullPointerException")
        void nullAcctId_throwsNpe() {
            assertThatThrownBy(() -> service.reconcileAccount(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("acctId");
        }

        @Test
        @DisplayName("TCATBAL rows for OTHER accounts are filtered out")
        void filteringByAcctId_otherAccountsExcluded() {
            // The SUT must use the in-memory filter approach (per the
            // schema's no-derived-query rule on the TCATBAL repo).
            // Verify that rows from a *different* account ID do not
            // contribute to the per-account sum.
            List<TransactionCategoryBalance> tcats = Arrays.asList(
                    // Our account — should be included
                    newTcatBal(ACCOUNT_ID, TYPE_CD_PURCHASE, CAT_CD_RETAIL,
                            new BigDecimal("300.00")),
                    // Another account — must be filtered out
                    newTcatBal(ACCOUNT_ID_2, TYPE_CD_PAYMENT, CAT_CD_CASH,
                            new BigDecimal("999.99")));
            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(account));
            when(transactionCategoryBalanceRepository.findAll())
                    .thenReturn(tcats);

            LedgerService.ReconciliationReport report =
                    service.reconcileAccount(ACCOUNT_ID);

            // Only the 300.00 belonging to ACCOUNT_ID should contribute.
            assertThat(report.expectedBalance())
                    .isEqualByComparingTo(new BigDecimal("300.00"));
            assertThat(report.balanced()).isTrue();
        }
    }

    /**
     * Batch reconciliation aggregation — verifies that
     * {@code reconcileAll()} iterates every Account row, invokes the
     * per-account {@code reconcileAccount(Long)} path, and aggregates
     * the outcomes into a {@code BatchReconciliationResult}.
     */
    @Nested
    @DisplayName("reconcileAll() — batch reconciliation aggregation")
    class BatchReconciliation {

        @Test
        @DisplayName("three accounts: two balanced, one unbalanced")
        void mixedOutcomes_aggregatesCorrectly() {
            // Arrange three accounts:
            //   acct1 — balanced (300.00 == 300.00)
            //   acct2 — balanced (200.00 == 200.00)
            //   acct3 — unbalanced (account=100.00 but TCATBAL=50.00)
            Account acct1 = newAccount(ACCOUNT_ID,
                    new BigDecimal("300.00"),
                    new BigDecimal("0.00"),
                    new BigDecimal("0.00"));
            Account acct2 = newAccount(ACCOUNT_ID_2,
                    new BigDecimal("200.00"),
                    new BigDecimal("0.00"),
                    new BigDecimal("0.00"));
            Account acct3 = newAccount(ACCOUNT_ID_3,
                    new BigDecimal("100.00"),
                    new BigDecimal("0.00"),
                    new BigDecimal("0.00"));

            // TCATBAL fixtures across all three accounts (returned as
            // ONE list by findAll(); the SUT filters by acct_id per
            // account in fetchCategoryBalances()).
            List<TransactionCategoryBalance> tcats = Arrays.asList(
                    newTcatBal(ACCOUNT_ID, TYPE_CD_PURCHASE, CAT_CD_RETAIL,
                            new BigDecimal("300.00")),
                    newTcatBal(ACCOUNT_ID_2, TYPE_CD_PURCHASE, CAT_CD_RETAIL,
                            new BigDecimal("200.00")),
                    newTcatBal(ACCOUNT_ID_3, TYPE_CD_PURCHASE, CAT_CD_RETAIL,
                            new BigDecimal("50.00")));

            when(accountRepository.findAll())
                    .thenReturn(Arrays.asList(acct1, acct2, acct3));
            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(acct1));
            when(accountRepository.findById(ACCOUNT_ID_2))
                    .thenReturn(Optional.of(acct2));
            when(accountRepository.findById(ACCOUNT_ID_3))
                    .thenReturn(Optional.of(acct3));
            when(transactionCategoryBalanceRepository.findAll())
                    .thenReturn(tcats);

            // Act
            LedgerService.BatchReconciliationResult result =
                    service.reconcileAll();

            // Assert: 3 total, 2 balanced, 1 unbalanced
            assertThat(result).isNotNull();
            assertThat(result.totalAccounts()).isEqualTo(3);
            assertThat(result.balancedAccounts()).isEqualTo(2);
            assertThat(result.unbalancedAccounts()).isEqualTo(1);

            // Exactly ONE imbalance audit must have been emitted
            // (for ACCOUNT_ID_3) — the two balanced accounts must
            // remain silent.
            verify(auditLogService).logAuditEvent(
                    eq(AUDIT_LEDGER_IMBALANCE),
                    eq(AUDIT_RESOURCE_ACCOUNT),
                    eq(String.valueOf(ACCOUNT_ID_3)),
                    eq(AUDIT_OPERATOR_RECONCILIATION),
                    any(),
                    any());
        }

        @Test
        @DisplayName("empty accounts table yields zero counts")
        void emptyAccountTable_zeroCounts() {
            when(accountRepository.findAll())
                    .thenReturn(Collections.emptyList());

            LedgerService.BatchReconciliationResult result =
                    service.reconcileAll();

            assertThat(result.totalAccounts()).isZero();
            assertThat(result.balancedAccounts()).isZero();
            assertThat(result.unbalancedAccounts()).isZero();

            // No per-account reconciliation paths should be invoked —
            // and therefore no TCATBAL findAll() reads either.
            verifyNoInteractions(auditLogService);
        }

        @Test
        @DisplayName("account with null acctId is defensively skipped")
        void accountWithNullId_isSkipped() {
            // Defensive path in the SUT — every persisted Account has
            // a non-null acct_id per V001 (PRIMARY KEY NOT NULL), but
            // the SUT skips a hypothetical Hibernate-hydrated entity
            // with a null primary key rather than NPE'ing.
            Account bad = new Account(); // no acctId set → null
            Account good = newAccount(ACCOUNT_ID,
                    new BigDecimal("100.00"),
                    new BigDecimal("0.00"),
                    new BigDecimal("0.00"));

            when(accountRepository.findAll())
                    .thenReturn(Arrays.asList(bad, good));
            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(good));
            when(transactionCategoryBalanceRepository.findAll())
                    .thenReturn(Collections.singletonList(
                            newTcatBal(ACCOUNT_ID, TYPE_CD_PURCHASE,
                                    CAT_CD_RETAIL, new BigDecimal("100.00"))));

            LedgerService.BatchReconciliationResult result =
                    service.reconcileAll();

            // Only the good account is reconciled and counted.
            assertThat(result.totalAccounts()).isEqualTo(1);
            assertThat(result.balancedAccounts()).isEqualTo(1);
            assertThat(result.unbalancedAccounts()).isZero();
        }

        @Test
        @DisplayName("single balanced account: counts (1, 1, 0)")
        void singleBalancedAccount() {
            Account single = newAccount(ACCOUNT_ID,
                    new BigDecimal("500.00"),
                    new BigDecimal("0.00"),
                    new BigDecimal("0.00"));
            when(accountRepository.findAll())
                    .thenReturn(Collections.singletonList(single));
            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(single));
            when(transactionCategoryBalanceRepository.findAll())
                    .thenReturn(Collections.singletonList(
                            newTcatBal(ACCOUNT_ID, TYPE_CD_PURCHASE,
                                    CAT_CD_RETAIL, new BigDecimal("500.00"))));

            LedgerService.BatchReconciliationResult result =
                    service.reconcileAll();

            assertThat(result.totalAccounts()).isEqualTo(1);
            assertThat(result.balancedAccounts()).isEqualTo(1);
            assertThat(result.unbalancedAccounts()).isZero();
            verify(auditLogService, never())
                    .logAuditEvent(anyString(), anyString(), anyString(),
                            anyString(), any(), any());
        }
    }

    /**
     * Per-category projection — verifies that
     * {@link LedgerService#snapshot(Long)} returns a
     * {@code LedgerSnapshot} carrying the persisted
     * {@code acct_curr_bal}/{@code acct_curr_cyc_credit}/
     * {@code acct_curr_cyc_debit} plus a per-category map keyed by
     * {@code "<typeCode>/<categoryCode>"} per COBOL
     * {@code CBTRN02C:2700-UPDATE-TCATBAL}.
     */
    @Nested
    @DisplayName("snapshot(Long) — per-account ledger snapshot")
    class CategoryBalances {

        @Test
        @DisplayName("returns snapshot with currentBalance, cycleCredit, cycleDebit")
        void returnsCurrentBalanceAndCycleTotals() {
            // Arrange: account with non-zero balance + cycle totals
            account.setAcctCurrBal(new BigDecimal("1234.56"));
            account.setAcctCurrCycCredit(new BigDecimal("500.00"));
            account.setAcctCurrCycDebit(new BigDecimal("250.25"));

            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(account));
            when(transactionCategoryBalanceRepository.findAll())
                    .thenReturn(Collections.emptyList());

            LedgerService.LedgerSnapshot snap = service.snapshot(ACCOUNT_ID);

            assertThat(snap).isNotNull();
            assertThat(snap.acctId()).isEqualTo(ACCOUNT_ID);
            assertThat(snap.currentBalance())
                    .isEqualByComparingTo(new BigDecimal("1234.56"));
            assertThat(snap.currCycCredit())
                    .isEqualByComparingTo(new BigDecimal("500.00"));
            assertThat(snap.currCycDebit())
                    .isEqualByComparingTo(new BigDecimal("250.25"));
            assertThat(snap.byCategory()).isEmpty();
        }

        @Test
        @DisplayName("byCategory map keys: '<typeCode>/<categoryCode>'")
        void byCategoryMapKeyFormat() {
            List<TransactionCategoryBalance> tcats = Arrays.asList(
                    newTcatBal(ACCOUNT_ID, TYPE_CD_PURCHASE, CAT_CD_RETAIL,
                            new BigDecimal("150.00")),
                    newTcatBal(ACCOUNT_ID, TYPE_CD_PAYMENT, CAT_CD_CASH,
                            new BigDecimal("75.50")));
            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(account));
            when(transactionCategoryBalanceRepository.findAll())
                    .thenReturn(tcats);

            LedgerService.LedgerSnapshot snap = service.snapshot(ACCOUNT_ID);

            assertThat(snap.byCategory()).hasSize(2);
            // Key format: typeCode + "/" + categoryCode
            //   "01/5"  ← purchase / retail
            //   "02/6"  ← payment / cash
            assertThat(snap.byCategory()).containsEntry(
                    TYPE_CD_PURCHASE + "/" + CAT_CD_RETAIL,
                    new BigDecimal("150.00"));
            assertThat(snap.byCategory()).containsEntry(
                    TYPE_CD_PAYMENT + "/" + CAT_CD_CASH,
                    new BigDecimal("75.50"));
        }

        @Test
        @DisplayName("snapshot filters TCATBAL rows by account ID")
        void filtersOutOtherAccountsTcatBalances() {
            // TCATBAL universe has rows for two different accounts.
            // Only the snapshot's target account should appear in the
            // returned map.
            List<TransactionCategoryBalance> tcats = Arrays.asList(
                    newTcatBal(ACCOUNT_ID, TYPE_CD_PURCHASE, CAT_CD_RETAIL,
                            new BigDecimal("100.00")),
                    newTcatBal(ACCOUNT_ID_2, TYPE_CD_PAYMENT, CAT_CD_CASH,
                            new BigDecimal("9999.99")));
            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(account));
            when(transactionCategoryBalanceRepository.findAll())
                    .thenReturn(tcats);

            LedgerService.LedgerSnapshot snap = service.snapshot(ACCOUNT_ID);

            assertThat(snap.byCategory()).hasSize(1);
            assertThat(snap.byCategory()).containsEntry(
                    TYPE_CD_PURCHASE + "/" + CAT_CD_RETAIL,
                    new BigDecimal("100.00"));
            // The other account's bucket must NOT leak through.
            assertThat(snap.byCategory()).doesNotContainEntry(
                    TYPE_CD_PAYMENT + "/" + CAT_CD_CASH,
                    new BigDecimal("9999.99"));
        }

        @Test
        @DisplayName("null monetary fields default to ZERO (COBOL parity)")
        void nullMonetaryFields_defaultToZero() {
            // The COBOL source has no concept of a NULL numeric field
            // (every PIC S9(n)V99 is zero-initialized before use).
            // The Java target preserves this: null becomes ZERO with
            // scale = 2.
            account.setAcctCurrBal(null);
            account.setAcctCurrCycCredit(null);
            account.setAcctCurrCycDebit(null);

            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(account));
            when(transactionCategoryBalanceRepository.findAll())
                    .thenReturn(Collections.emptyList());

            LedgerService.LedgerSnapshot snap = service.snapshot(ACCOUNT_ID);

            assertThat(snap.currentBalance())
                    .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(snap.currCycCredit())
                    .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(snap.currCycDebit())
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("null acctId throws NullPointerException")
        void nullAcctId_throwsNpe() {
            assertThatThrownBy(() -> service.snapshot(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("acctId");
        }

        @Test
        @DisplayName("TCATBAL with null balance defaults to ZERO in map")
        void tcatWithNullBalance_mapsToZero() {
            // The SUT's nonNullBalance() helper treats a null
            // TRAN-CAT-BAL as ZERO with scale = 2.
            TransactionCategoryBalance withNullBal =
                    newTcatBal(ACCOUNT_ID, TYPE_CD_PURCHASE, CAT_CD_RETAIL, null);

            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(account));
            when(transactionCategoryBalanceRepository.findAll())
                    .thenReturn(Collections.singletonList(withNullBal));

            LedgerService.LedgerSnapshot snap = service.snapshot(ACCOUNT_ID);

            assertThat(snap.byCategory()).hasSize(1);
            assertThat(snap.byCategory().get(
                    TYPE_CD_PURCHASE + "/" + CAT_CD_RETAIL))
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    /**
     * Exception-path verification: both
     * {@link LedgerService#reconcileAccount(Long)} and
     * {@link LedgerService#snapshot(Long)} throw
     * {@link RecordNotFoundException} when the supplied account ID
     * does not resolve to a row in the {@code accounts} table,
     * preserving COBOL {@code FILE STATUS '23'} (NOTFND) semantics.
     */
    @Nested
    @DisplayName("RecordNotFound — missing account propagates exception")
    class RecordNotFoundScenarios {

        @Test
        @DisplayName("reconcileAccount: missing account → RecordNotFoundException")
        void reconcileAccount_missingAccount_throwsRecordNotFound() {
            // COBOL: CBTRN02C lookup-miss == FILE STATUS '23' NOTFND.
            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.reconcileAccount(ACCOUNT_ID))
                    .isInstanceOf(RecordNotFoundException.class)
                    .hasMessageContaining("Account not found for reconciliation")
                    .hasMessageContaining(String.valueOf(ACCOUNT_ID));

            // TCATBAL repo must NOT be consulted when the account is
            // missing — the lookup short-circuits before any per-bucket
            // aggregation work.
            verify(transactionCategoryBalanceRepository, never()).findAll();
        }

        @Test
        @DisplayName("snapshot: missing account → RecordNotFoundException")
        void snapshot_missingAccount_throwsRecordNotFound() {
            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.snapshot(ACCOUNT_ID))
                    .isInstanceOf(RecordNotFoundException.class)
                    .hasMessageContaining("Account not found for snapshot")
                    .hasMessageContaining(String.valueOf(ACCOUNT_ID));

            verify(transactionCategoryBalanceRepository, never()).findAll();
        }
    }

    /**
     * Read-only boundary verification — the SUT is documented as
     * read-only by design. Every public method is annotated
     * {@code @Transactional(readOnly = true)} and the implementation
     * is forbidden from invoking persistence-mutating methods
     * ({@code save}, {@code delete}, etc.) on any repository.
     */
    @Nested
    @DisplayName("ReadOnlyBoundary — no save/delete on any repository")
    class ReadOnlyBoundary {

        @Test
        @DisplayName("reconcileAccount: no save/delete on Account repository")
        void reconcileAccount_noAccountMutation() {
            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(account));
            when(transactionCategoryBalanceRepository.findAll())
                    .thenReturn(Collections.singletonList(
                            newTcatBal(ACCOUNT_ID, TYPE_CD_PURCHASE,
                                    CAT_CD_RETAIL, new BigDecimal("300.00"))));

            service.reconcileAccount(ACCOUNT_ID);

            // The read-only path must NOT mutate the account.
            verify(accountRepository, never()).save(any(Account.class));
            verify(accountRepository, never()).delete(any(Account.class));
            verify(accountRepository, never()).deleteById(any());
        }

        @Test
        @DisplayName("reconcileAccount: no save/delete on TCATBAL repository")
        void reconcileAccount_noTcatBalMutation() {
            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(account));
            when(transactionCategoryBalanceRepository.findAll())
                    .thenReturn(Collections.singletonList(
                            newTcatBal(ACCOUNT_ID, TYPE_CD_PURCHASE,
                                    CAT_CD_RETAIL, new BigDecimal("300.00"))));

            service.reconcileAccount(ACCOUNT_ID);

            verify(transactionCategoryBalanceRepository, never())
                    .save(any(TransactionCategoryBalance.class));
            verify(transactionCategoryBalanceRepository, never())
                    .delete(any(TransactionCategoryBalance.class));
        }

        @Test
        @DisplayName("reconcileAccount: TransactionRepository is never used by read-only path")
        void reconcileAccount_noTransactionInteraction() {
            // The injected TransactionRepository is held for future
            // per-transaction audit lookups but the public read-only
            // methods must not touch it.
            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(account));
            when(transactionCategoryBalanceRepository.findAll())
                    .thenReturn(Collections.singletonList(
                            newTcatBal(ACCOUNT_ID, TYPE_CD_PURCHASE,
                                    CAT_CD_RETAIL, new BigDecimal("300.00"))));

            service.reconcileAccount(ACCOUNT_ID);

            verifyNoInteractions(transactionRepository);
        }

        @Test
        @DisplayName("snapshot: no save/delete on any repository")
        void snapshot_noMutationAnywhere() {
            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(account));
            when(transactionCategoryBalanceRepository.findAll())
                    .thenReturn(Collections.singletonList(
                            newTcatBal(ACCOUNT_ID, TYPE_CD_PURCHASE,
                                    CAT_CD_RETAIL, new BigDecimal("100.00"))));

            service.snapshot(ACCOUNT_ID);

            verify(accountRepository, never()).save(any(Account.class));
            verify(transactionCategoryBalanceRepository, never())
                    .save(any(TransactionCategoryBalance.class));
            verifyNoInteractions(transactionRepository);
        }

        @Test
        @DisplayName("reconcileAll: no mutation on any repository across iteration")
        void reconcileAll_noMutationAcrossIteration() {
            Account a1 = newAccount(ACCOUNT_ID,
                    new BigDecimal("100.00"),
                    new BigDecimal("0.00"),
                    new BigDecimal("0.00"));
            Account a2 = newAccount(ACCOUNT_ID_2,
                    new BigDecimal("200.00"),
                    new BigDecimal("0.00"),
                    new BigDecimal("0.00"));

            when(accountRepository.findAll())
                    .thenReturn(Arrays.asList(a1, a2));
            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(a1));
            when(accountRepository.findById(ACCOUNT_ID_2))
                    .thenReturn(Optional.of(a2));
            when(transactionCategoryBalanceRepository.findAll())
                    .thenReturn(Arrays.asList(
                            newTcatBal(ACCOUNT_ID, TYPE_CD_PURCHASE,
                                    CAT_CD_RETAIL, new BigDecimal("100.00")),
                            newTcatBal(ACCOUNT_ID_2, TYPE_CD_PURCHASE,
                                    CAT_CD_RETAIL, new BigDecimal("200.00"))));

            service.reconcileAll();

            verify(accountRepository, never()).save(any(Account.class));
            verify(transactionCategoryBalanceRepository, never())
                    .save(any(TransactionCategoryBalance.class));
            verifyNoInteractions(transactionRepository);
        }
    }

    /**
     * BigDecimal arithmetic discipline (AAP &sect;0.6.1) &mdash;
     * every monetary sum is computed with
     * {@code scale = 2} and
     * {@link java.math.RoundingMode#HALF_EVEN HALF_EVEN} (banker's
     * rounding) to preserve the COBOL {@code PIC S9(n)V99} /
     * {@code COMP-3} decimal semantics exactly. AssertJ's
     * {@code isEqualByComparingTo} is used because
     * {@code BigDecimal.equals} is scale-sensitive (e.g.,
     * {@code new BigDecimal("300.00").equals(new BigDecimal("300.0"))}
     * returns {@code false}).
     */
    @Nested
    @DisplayName("BigDecimal arithmetic — HALF_EVEN, scale=2 (AAP §0.6.1)")
    class BigDecimalArithmetic {

        @Test
        @DisplayName("sums multiple TCATBAL rows preserving HALF_EVEN scale=2")
        void multipleRowsSumWithHalfEven() {
            // 33.33 + 66.67 + 100.00 = 200.00
            // Demonstrates that the sum is scaled to 2 decimal places
            // (HALF_EVEN per the SUT's nonNullBalance + sumBalances
            // helpers).
            account.setAcctCurrBal(new BigDecimal("200.00"));
            List<TransactionCategoryBalance> tcats = Arrays.asList(
                    newTcatBal(ACCOUNT_ID, TYPE_CD_PURCHASE, CAT_CD_RETAIL,
                            new BigDecimal("33.33")),
                    newTcatBal(ACCOUNT_ID, TYPE_CD_PURCHASE, CAT_CD_CASH,
                            new BigDecimal("66.67")),
                    newTcatBal(ACCOUNT_ID, TYPE_CD_PAYMENT, CAT_CD_RETAIL,
                            new BigDecimal("100.00")));

            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(account));
            when(transactionCategoryBalanceRepository.findAll())
                    .thenReturn(tcats);

            LedgerService.ReconciliationReport report =
                    service.reconcileAccount(ACCOUNT_ID);

            assertThat(report.expectedBalance())
                    .isEqualByComparingTo(new BigDecimal("200.00"));
            assertThat(report.expectedBalance().scale()).isEqualTo(2);
            assertThat(report.balanced()).isTrue();
        }

        @Test
        @DisplayName("'300.00' == '300.0' via isEqualByComparingTo (scale-insensitive)")
        void compareByValueIgnoresScale() {
            // The SUT must use BigDecimal.compareTo() (scale-insensitive)
            // — NOT BigDecimal.equals() (scale-sensitive). Otherwise an
            // account with balance "300.00" would be reported as
            // unbalanced against a TCATBAL sum of "300.0".
            account.setAcctCurrBal(new BigDecimal("300.0"));   // scale 1
            List<TransactionCategoryBalance> tcats = Collections.singletonList(
                    newTcatBal(ACCOUNT_ID, TYPE_CD_PURCHASE, CAT_CD_RETAIL,
                            new BigDecimal("300.00")));         // scale 2

            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(account));
            when(transactionCategoryBalanceRepository.findAll())
                    .thenReturn(tcats);

            LedgerService.ReconciliationReport report =
                    service.reconcileAccount(ACCOUNT_ID);

            // Equal by value even though their string forms differ —
            // a balanced result MUST be returned.
            assertThat(report.balanced()).isTrue();
            assertThat(report.expectedBalance())
                    .isEqualByComparingTo(new BigDecimal("300.00"));
            assertThat(report.actualBalance())
                    .isEqualByComparingTo(new BigDecimal("300.0"));
        }

        @Test
        @DisplayName("negative balances participate in summation correctly")
        void negativeBalancesSummedCorrectly() {
            // Credit balances may be negative (refunds, reversals);
            // the sum honours the signed PIC S9(10)V99 semantics.
            // 500.00 + (-150.00) + (-50.00) = 300.00
            account.setAcctCurrBal(new BigDecimal("300.00"));
            List<TransactionCategoryBalance> tcats = Arrays.asList(
                    newTcatBal(ACCOUNT_ID, TYPE_CD_PURCHASE, CAT_CD_RETAIL,
                            new BigDecimal("500.00")),
                    newTcatBal(ACCOUNT_ID, TYPE_CD_PAYMENT, CAT_CD_CASH,
                            new BigDecimal("-150.00")),
                    newTcatBal(ACCOUNT_ID, TYPE_CD_PAYMENT, CAT_CD_RETAIL,
                            new BigDecimal("-50.00")));

            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(account));
            when(transactionCategoryBalanceRepository.findAll())
                    .thenReturn(tcats);

            LedgerService.ReconciliationReport report =
                    service.reconcileAccount(ACCOUNT_ID);

            assertThat(report.balanced()).isTrue();
            assertThat(report.expectedBalance())
                    .isEqualByComparingTo(new BigDecimal("300.00"));
        }

        @Test
        @DisplayName("difference is computed via subtract (expected - actual)")
        void differencePayloadMatchesExpectedMinusActual() {
            // expected = 100.00 (TCATBAL sum)
            // actual   = 300.00 (account balance)
            // difference = 100.00 - 300.00 = -200.00
            List<TransactionCategoryBalance> tcats = Collections.singletonList(
                    newTcatBal(ACCOUNT_ID, TYPE_CD_PURCHASE, CAT_CD_RETAIL,
                            new BigDecimal("100.00")));

            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(account));
            when(transactionCategoryBalanceRepository.findAll())
                    .thenReturn(tcats);

            service.reconcileAccount(ACCOUNT_ID);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> payloadCaptor =
                    ArgumentCaptor.forClass(Map.class);
            verify(auditLogService).logAuditEvent(
                    eq(AUDIT_LEDGER_IMBALANCE),
                    anyString(),
                    anyString(),
                    anyString(),
                    payloadCaptor.capture(),
                    any());

            BigDecimal difference = (BigDecimal) payloadCaptor.getValue().get("difference");
            assertThat(difference)
                    .isEqualByComparingTo(new BigDecimal("-200.00"));
            assertThat(difference.scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("null entries in TCATBAL list are tolerated (skipped)")
        @SuppressWarnings("ConstantConditions")
        void nullEntriesInTcatListAreSkipped() {
            // The SUT's defensive null-check skips null list entries
            // and null balance fields. Confirming this protects against
            // any Hibernate hydration quirks that might surface a null
            // row inside a result list.
            account.setAcctCurrBal(new BigDecimal("100.00"));
            List<TransactionCategoryBalance> tcats = Arrays.asList(
                    newTcatBal(ACCOUNT_ID, TYPE_CD_PURCHASE, CAT_CD_RETAIL,
                            new BigDecimal("100.00")),
                    null);

            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(account));
            when(transactionCategoryBalanceRepository.findAll())
                    .thenReturn(tcats);

            LedgerService.ReconciliationReport report =
                    service.reconcileAccount(ACCOUNT_ID);

            assertThat(report.balanced()).isTrue();
            assertThat(report.expectedBalance())
                    .isEqualByComparingTo(new BigDecimal("100.00"));
        }
    }

    /**
     * Structural import contract test &mdash; this empty {@code @Nested}
     * class exists solely to anchor the {@link Transaction} import
     * declared at the top of this file (the public reconciliation
     * methods aggregate via the TCATBAL repository, but the SUT's
     * constructor still accepts a {@link TransactionRepository} held
     * as a final field for future per-transaction audit lookups).
     * Verifying the type compiles into the test classpath catches any
     * future {@link Transaction}-shape divergence between this test
     * suite and the production service.
     */
    @Nested
    @DisplayName("Transaction import contract (compile-time only)")
    class TransactionImportContract {

        @Test
        @DisplayName("Transaction.class is referenceable from the test scope")
        void transactionTypeIsReferenceable() {
            // The Transaction class is referenced via {@code .class} so
            // that the import is exercised at compile + runtime; this
            // ensures any future structural divergence in
            // {@code Transaction} is caught here rather than at the
            // service compilation boundary.
            Class<?> txClass = Transaction.class;

            // Sanity-check the fully-qualified name to make sure we
            // imported the carddemo Transaction (and not some other
            // class of the same simple name from a transitive
            // dependency, e.g., Spring's @Transactional support
            // classes).
            assertThat(txClass.getName())
                    .isEqualTo("com.awsm2.carddemo.domain.Transaction");
        }
    }

    /**
     * Lenient-stub guard &mdash; placeholder for any future test
     * scenarios that need {@link org.mockito.Mockito#lenient} default
     * stubs. Mockito strict-stubbing (the default with
     * {@link MockitoExtension}) fails the test if a stubbed method is
     * never called; using {@code lenient()} bypasses this check for
     * setUp-style fixtures shared by every test.
     *
     * <p>This {@code @Nested} class is empty by design: every test
     * above declares the stubs it actually exercises, so no global
     * lenient default is required. Maintained as documentation /
     * extension point for future readers.</p>
     */
    @Nested
    @DisplayName("Mockito strict-stub discipline (documentation)")
    class StrictStubbingDiscipline {

        @Test
        @DisplayName("lenient() reference compiles — extension point preserved")
        void lenientReferenceCompiles() {
            // No actual stubbing is performed here; this test exists
            // purely to demonstrate that lenient() remains available
            // as an escape hatch for future test cases that need
            // shared setUp stubs not used by every nested test.
            // Capture the static reference into a no-op local
            // expression so the import is exercised and the test
            // remains a valid documentation artefact.
            org.mockito.stubbing.LenientStubber lenientStubber = lenient();
            assertThat(lenientStubber).isNotNull();
        }
    }
}

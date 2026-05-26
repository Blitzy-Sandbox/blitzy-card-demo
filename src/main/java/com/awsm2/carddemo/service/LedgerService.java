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
import com.awsm2.carddemo.exception.CardDemoException;
import com.awsm2.carddemo.exception.OnSizeErrorException;
import com.awsm2.carddemo.exception.RecordNotFoundException;
import com.awsm2.carddemo.repository.AccountRepository;
import com.awsm2.carddemo.repository.TransactionCategoryBalanceRepository;
import com.awsm2.carddemo.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Ledger / double-entry bookkeeping aggregate service (DDD).
 *
 * <p>This service is intentionally <b>not</b> a one-to-one Java
 * translation of any single COBOL program. Per AAP &sect;0.3.3
 * (Domain-Driven Design for financial entities) and &sect;0.3.1
 * (service-class enumeration), {@code LedgerService} is a
 * cross-cutting <b>DDD aggregate-root service</b> that centralizes the
 * double-entry bookkeeping invariants that emerge from the combined
 * behaviour of multiple COBOL programs:
 * <ul>
 *   <li>{@code ACCT-CURR-BAL} == sum of all {@code TRAN-AMT} for an
 *       account (debits and credits combined) &mdash;
 *       {@code CBTRN02C}:2800-UPDATE-ACCOUNT-REC,
 *       {@code CBACT04C}:1050-UPDATE-ACCOUNT,
 *       {@code COBIL00C}:bill-payment processing.</li>
 *   <li>{@code ACCT-CURR-CYC-CREDIT} == sum of {@code TRAN-AMT &gt;= 0}
 *       in the current cycle &mdash;
 *       {@code CBTRN02C}:2800-UPDATE-ACCOUNT-REC (positive branch).</li>
 *   <li>{@code ACCT-CURR-CYC-DEBIT} == sum of {@code TRAN-AMT &lt; 0}
 *       in the current cycle &mdash;
 *       {@code CBTRN02C}:2800-UPDATE-ACCOUNT-REC (negative branch).</li>
 *   <li>{@code TCATBAL} by {@code (acct_id, type_cd, category_cd)} ==
 *       sum of {@code TRAN-AMT} in that bucket &mdash;
 *       {@code CBTRN02C}:2700-UPDATE-TCATBAL accumulates every posted
 *       transaction into the corresponding per-category running
 *       balance, so summing every per-account {@code TCATBAL} row must
 *       equal the {@code accounts.acct_curr_bal} value.</li>
 * </ul>
 *
 * <h2>Read-only by design</h2>
 *
 * <p>This aggregate <b>NEVER mutates the ledger</b>; it only validates
 * and reports. Every public method is annotated
 * {@link Transactional @Transactional(readOnly = true)}, which:
 * <ul>
 *   <li>Wraps the database reads in a Spring-managed read-only
 *       transaction (Hibernate skips dirty checking and the underlying
 *       JDBC driver may apply read-only optimizations).</li>
 *   <li>Replaces the COBOL VSAM read-only browse semantics used by
 *       {@code CBACT04C}:1000-TCATBALF-GET-NEXT (sequential read of
 *       every {@code TCATBAL} row).</li>
 *   <li>Provides idempotent, repeatable reconciliation suitable for
 *       use as a Step Functions reconciliation gate between
 *       {@code COMBTRAN} and {@code CREASTMT}/{@code TRANREPT} stages.
 *   </li>
 * </ul>
 *
 * <h2>Reconciliation strategy</h2>
 *
 * <p>The reconciliation algorithm trusts the per-(account, type,
 * category) {@code TCATBAL} table as the source of truth for the
 * expected account balance. Per
 * {@code CBTRN02C}:2700-UPDATE-TCATBAL the TCATBAL row is updated
 * <em>atomically</em> with every posted transaction (in the same unit
 * of work as the matching {@code accounts.acct_curr_bal} update at
 * {@code CBTRN02C}:2800-UPDATE-ACCOUNT-REC); summing every per-account
 * {@code TCATBAL.balance} must therefore equal
 * {@code accounts.acct_curr_bal}. Any divergence is a violation of the
 * COBOL invariant and is reported as a ledger imbalance.
 *
 * <p>Although the {@link TransactionRepository} is injected and the
 * {@link Transaction} entity is referenced from this service, the
 * primary reconciliation flow uses the TCATBAL aggregate to avoid a
 * per-transaction scan (which would be O(N) in the transaction count
 * versus O(B) in the bucket count). The transaction repository remains
 * available as a fallback for ad-hoc per-transaction audit queries
 * when the category aggregate is insufficient (e.g., when a forensic
 * investigation needs to enumerate individual rows that contributed to
 * a particular bucket).
 *
 * <h2>Consumers</h2>
 *
 * <p>The reconciliation methods exposed by this service are consumed
 * by:
 * <ul>
 *   <li>The end-of-day Step Functions state machine
 *       ({@code src/main/resources/stepfunctions/eod-batch-pipeline.asl.json})
 *       which invokes {@link #reconcileAll()} between the COMBTRAN
 *       merge step and the parallel CREASTMT / TRANREPT stages, to
 *       gate the rest of the EOD pipeline on a clean ledger.</li>
 *   <li>Operational dashboards backed by CloudWatch and OpenSearch
 *       which surface per-account reconciliation status to operators
 *       and auditors.</li>
 *   <li>Unit and integration tests as a ground-truth validation
 *       harness for transaction-posting, interest-calculation, and
 *       bill-payment service behaviour.</li>
 * </ul>
 *
 * <h2>Implementation rules (AAP &sect;0.7.1, &sect;0.7.3)</h2>
 *
 * <ul>
 *   <li><b>BigDecimal arithmetic:</b> All monetary sums use
 *       {@link BigDecimal} with explicit
 *       {@link RoundingMode#HALF_EVEN banker's rounding} to preserve
 *       COBOL {@code PIC S9(n)V99}/{@code COMP-3} decimal semantics
 *       exactly. No {@code float}/{@code double} anywhere.</li>
 *   <li><b>ON SIZE ERROR equivalence:</b> Arithmetic overflow during
 *       summing is caught and re-thrown as
 *       {@link OnSizeErrorException}, preserving the COBOL
 *       {@code COMPUTE ... ON SIZE ERROR} contract.</li>
 *   <li><b>Constructor injection only:</b> All dependencies are
 *       declared {@code private final} and assigned through the
 *       single constructor. No {@code @Autowired} field injection.</li>
 *   <li><b>Inline traceability comments:</b> Every reconciliation
 *       invariant carries a {@code // COBOL: <PROGRAM>:<PARAGRAPH>}
 *       comment per the refactor discipline rules.</li>
 *   <li><b>Audit on imbalance:</b> Any detected imbalance is logged
 *       via {@link AuditLogService#logAuditEvent} as an immutable,
 *       indexed event &mdash; never silent. A warning is also emitted
 *       to the structured log for operational visibility.</li>
 *   <li><b>No mutation:</b> This service never invokes
 *       {@code save(...)}, {@code delete(...)}, or any other
 *       persistence-mutating method. The exception hierarchy
 *       ({@link CardDemoException} subclasses) is used to surface
 *       errors back to the caller.</li>
 * </ul>
 *
 * @see com.awsm2.carddemo.service.TransactionPostingService
 *      service that updates account balances and TCATBAL buckets
 *      during daily transaction posting (CBTRN02C)
 * @see com.awsm2.carddemo.service.InterestCalculationService
 *      service that computes monthly interest and updates balances
 *      (CBACT04C)
 * @see com.awsm2.carddemo.service.BillPaymentService
 *      service that processes bill payments (COBIL00C)
 */
// COBOL source provenance: emergent contract across CBTRN02C
// (paragraphs 2700-UPDATE-TCATBAL and 2800-UPDATE-ACCOUNT-REC),
// CBACT04C (paragraph 1050-UPDATE-ACCOUNT and 1300-COMPUTE-INTEREST),
// and COBIL00C (bill-payment account balance update). This service
// makes the implicit double-entry bookkeeping invariant explicit and
// queryable for reconciliation purposes.
@Service
public class LedgerService {

    private static final Logger LOG = LoggerFactory.getLogger(LedgerService.class);

    /**
     * Audit event type emitted when reconciliation detects a divergence
     * between the {@code accounts.acct_curr_bal} field and the
     * aggregated per-category {@code TCATBAL} balances.
     */
    static final String AUDIT_LEDGER_IMBALANCE = "LEDGER_IMBALANCE";

    /**
     * Operator code stamped on the audit event. Reconciliation runs
     * are batch / automated; the operator is "RECONCILIATION".
     */
    static final String AUDIT_OPERATOR_RECONCILIATION = "RECONCILIATION";

    /**
     * Resource type stamped on the audit event &mdash; the account
     * whose ledger is reconciled.
     */
    static final String AUDIT_RESOURCE_ACCOUNT = "ACCOUNT";

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionCategoryBalanceRepository transactionCategoryBalanceRepository;
    private final AuditLogService auditLogService;

    /**
     * Constructs a {@code LedgerService} with the four required Spring
     * Data and adapter beans.
     *
     * <p>All parameters are required and validated via
     * {@link Objects#requireNonNull(Object, String)}; a {@code null}
     * argument throws {@link NullPointerException} at construction
     * time, surfacing wiring errors immediately at application
     * startup rather than at first use.</p>
     *
     * @param accountRepository                    Spring Data JPA
     *        repository for {@link Account} (used to fetch a single
     *        account or iterate every account for batch
     *        reconciliation)
     * @param transactionRepository                Spring Data JPA
     *        repository for {@link Transaction} (held as a future-
     *        proof fallback for direct per-transaction audits when
     *        the {@code TCATBAL} aggregate is insufficient; per
     *        AAP &sect;0.4.2 internal-imports schema)
     * @param transactionCategoryBalanceRepository Spring Data JPA
     *        repository for {@link TransactionCategoryBalance}
     *        (primary lookup for the per-(acct, type, category)
     *        running-balance buckets that are the source of truth
     *        for ledger reconciliation)
     * @param auditLogService                      CloudTrail /
     *        OpenSearch audit adapter (invoked when reconciliation
     *        detects a ledger imbalance)
     * @throws NullPointerException if any argument is {@code null}
     */
    public LedgerService(AccountRepository accountRepository,
                         TransactionRepository transactionRepository,
                         TransactionCategoryBalanceRepository transactionCategoryBalanceRepository,
                         AuditLogService auditLogService) {
        this.accountRepository = Objects.requireNonNull(accountRepository,
                "accountRepository must not be null");
        this.transactionRepository = Objects.requireNonNull(transactionRepository,
                "transactionRepository must not be null");
        this.transactionCategoryBalanceRepository = Objects.requireNonNull(
                transactionCategoryBalanceRepository,
                "transactionCategoryBalanceRepository must not be null");
        this.auditLogService = Objects.requireNonNull(auditLogService,
                "auditLogService must not be null");
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Reconciles a single account's persisted balance against the sum
     * of its per-category {@code TCATBAL} buckets.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Load the {@link Account} by primary key. Missing accounts
     *       result in {@link RecordNotFoundException} (HTTP 404).
     *       Mirrors the COBOL {@code CBTRN02C} lookup-miss handling
     *       (FILE STATUS '23' NOTFND).</li>
     *   <li>Sum every {@link TransactionCategoryBalance#getTranCatBal()
     *       running balance} for the account, using
     *       {@code BigDecimal} arithmetic with banker's rounding.</li>
     *   <li>Compare the computed expected balance against the
     *       persisted {@link Account#getAcctCurrBal() current
     *       balance}.</li>
     *   <li>On mismatch, emit a {@code LEDGER_IMBALANCE} audit event
     *       (immutable, indexed in OpenSearch) and a structured
     *       warning log entry. The reconciliation result is returned
     *       to the caller regardless of outcome.</li>
     * </ol>
     *
     * @param acctId the account identifier (must be non-{@code null};
     *               unsigned 11-digit COBOL {@code ACCT-ID PIC 9(11)})
     * @return a {@link ReconciliationReport} carrying the expected
     *         and actual balances plus the balanced flag
     * @throws RecordNotFoundException if {@code acctId} does not
     *         resolve to a row in the {@code accounts} table
     *         (preserves COBOL FILE STATUS '23' NOTFND semantics)
     * @throws OnSizeErrorException if summing TCATBAL balances would
     *         overflow {@link BigDecimal} arithmetic (preserves COBOL
     *         {@code ON SIZE ERROR} semantics)
     */
    @Transactional(readOnly = true)
    public ReconciliationReport reconcileAccount(Long acctId) {
        Objects.requireNonNull(acctId, "acctId must not be null");

        // COBOL: CBTRN02C lookup pattern — missing account == FILE STATUS '23'
        Account account = accountRepository.findById(acctId)
                .orElseThrow(() -> new RecordNotFoundException(
                        "Account",
                        "Account not found for reconciliation: acctId=" + acctId));

        // COBOL: CBTRN02C:2700-UPDATE-TCATBAL atomically updates the
        //        per-(acct, type, category) TCATBAL bucket with every
        //        posted TRAN-AMT, so the per-account sum of TCATBAL
        //        balances must equal accounts.acct_curr_bal.
        List<TransactionCategoryBalance> tcatBalances = fetchCategoryBalances(acctId);
        BigDecimal expectedBalance = sumBalances(tcatBalances);
        BigDecimal actualBalance = nonNullBalance(account.getAcctCurrBal());

        // BigDecimal.compareTo ignores trailing-zero scale differences
        // (e.g., "0.00".compareTo("0") == 0) — the correct semantics
        // for monetary equality per AAP §0.6.1 / §0.7.1.
        boolean balanced = expectedBalance.compareTo(actualBalance) == 0;

        if (!balanced) {
            LOG.warn("Ledger imbalance detected for acctId={}: expected={}, "
                    + "actual={}, difference={}",
                    acctId,
                    expectedBalance,
                    actualBalance,
                    expectedBalance.subtract(actualBalance)
                            .setScale(2, RoundingMode.HALF_EVEN));
            auditImbalance(acctId, expectedBalance, actualBalance);
        }

        return new ReconciliationReport(acctId, expectedBalance, actualBalance, balanced);
    }

    /**
     * Reconciles every account in the {@code accounts} table.
     *
     * <p>Iterates every {@link Account} row, invokes
     * {@link #reconcileAccount(Long)} for each, and aggregates the
     * total / balanced / unbalanced counts.</p>
     *
     * <p>Per-account imbalances are logged and audited as a side
     * effect of {@link #reconcileAccount(Long)}; this method
     * additionally emits a single summary log entry on completion.</p>
     *
     * <p>This method is invoked as a Step Functions reconciliation
     * gate between the {@code COMBTRAN} merge stage and the parallel
     * {@code CREASTMT} / {@code TRANREPT} statement-generation stages
     * in the end-of-day batch pipeline
     * ({@code src/main/resources/stepfunctions/eod-batch-pipeline.asl.json}).</p>
     *
     * @return a {@link BatchReconciliationResult} summarising the
     *         outcomes
     */
    @Transactional(readOnly = true)
    public BatchReconciliationResult reconcileAll() {
        int total = 0;
        int balanced = 0;
        int unbalanced = 0;

        // COBOL: CBACT01C / CBACT04C sequential scan equivalent —
        //        read every account row in turn. findAll() returns a
        //        single List<Account>; for very large tables a
        //        page-by-page iteration would be more memory-efficient
        //        but findAll() matches the established convention in
        //        the rest of the service layer (see
        //        AccountFileReaderService) and the AAP §0.7.3 Minimal
        //        Change Clause forbids speculative optimisation.
        for (Account account : accountRepository.findAll()) {
            Long acctId = account.getAcctId();
            if (acctId == null) {
                // Defensive — every persisted Account has a non-null
                // acct_id per V001 (PRIMARY KEY NOT NULL); skip the
                // pathological case where Hibernate hydrates an entity
                // with a null primary key.
                continue;
            }
            ReconciliationReport report = reconcileAccount(acctId);
            total++;
            if (report.balanced()) {
                balanced++;
            } else {
                unbalanced++;
            }
        }

        LOG.info("Ledger reconciliation complete. total={}, balanced={}, unbalanced={}",
                total, balanced, unbalanced);

        return new BatchReconciliationResult(total, balanced, unbalanced);
    }

    /**
     * Returns a per-account ledger snapshot for use as an audit /
     * statement-time lookup.
     *
     * <p>The snapshot includes:
     * <ul>
     *   <li>The persisted {@link Account#getAcctCurrBal() current
     *       balance}.</li>
     *   <li>The cycle credit and cycle debit accumulators (per
     *       {@code CBTRN02C}:2800-UPDATE-ACCOUNT-REC).</li>
     *   <li>A per-category map keyed by
     *       {@code "<typeCode>/<categoryCode>"} pointing at the
     *       running {@link TransactionCategoryBalance#getTranCatBal()
     *       balance} for that bucket (per
     *       {@code CBTRN02C}:2700-UPDATE-TCATBAL).</li>
     * </ul>
     *
     * @param acctId the account identifier (must be non-{@code null})
     * @return a {@link LedgerSnapshot} populated with the current
     *         balance, cycle credit/debit, and per-category map
     * @throws RecordNotFoundException if {@code acctId} does not
     *         resolve to a row in the {@code accounts} table
     */
    @Transactional(readOnly = true)
    public LedgerSnapshot snapshot(Long acctId) {
        Objects.requireNonNull(acctId, "acctId must not be null");

        // COBOL: CBTRN02C lookup pattern — missing account == FILE STATUS '23'
        Account account = accountRepository.findById(acctId)
                .orElseThrow(() -> new RecordNotFoundException(
                        "Account",
                        "Account not found for snapshot: acctId=" + acctId));

        // COBOL: CBTRN02C:2700-UPDATE-TCATBAL — per-bucket balances
        List<TransactionCategoryBalance> tcats = fetchCategoryBalances(acctId);

        // Use HashMap for the byCategory map per the schema contract
        // (LedgerSnapshot accepts Map; no specific ordering required
        // by callers — operational dashboards key into specific
        // entries rather than iterating).
        Map<String, BigDecimal> byCategory = new HashMap<>();
        for (TransactionCategoryBalance tcat : tcats) {
            if (tcat == null || tcat.getId() == null) {
                continue;
            }
            String key = tcat.getId().getTrancatTypeCd()
                    + "/"
                    + tcat.getId().getTrancatCd();
            byCategory.put(key, nonNullBalance(tcat.getTranCatBal()));
        }

        return new LedgerSnapshot(
                acctId,
                nonNullBalance(account.getAcctCurrBal()),
                nonNullBalance(account.getAcctCurrCycCredit()),
                nonNullBalance(account.getAcctCurrCycDebit()),
                byCategory);
    }

    // -------------------------------------------------------------------------
    // Reconciliation outcome records (exports)
    // -------------------------------------------------------------------------

    /**
     * Per-account reconciliation outcome.
     *
     * <p>Returned by {@link #reconcileAccount(Long)}; aggregated by
     * {@link #reconcileAll()} into a
     * {@link BatchReconciliationResult}.</p>
     *
     * @param acctId          the account identifier
     * @param expectedBalance sum of every TCATBAL bucket for the
     *                        account (the ledger source of truth)
     * @param actualBalance   the persisted
     *                        {@link Account#getAcctCurrBal()}
     * @param balanced        {@code true} when
     *                        {@code expectedBalance.compareTo(actualBalance) == 0}
     */
    public record ReconciliationReport(
            Long acctId,
            BigDecimal expectedBalance,
            BigDecimal actualBalance,
            boolean balanced) {
    }

    /**
     * Aggregated outcome of a batch reconciliation run.
     *
     * <p>Returned by {@link #reconcileAll()}.</p>
     *
     * @param totalAccounts      every account inspected
     * @param balancedAccounts   accounts with
     *                           {@link ReconciliationReport#balanced()}
     *                           equal to {@code true}
     * @param unbalancedAccounts accounts with
     *                           {@link ReconciliationReport#balanced()}
     *                           equal to {@code false}
     */
    public record BatchReconciliationResult(
            int totalAccounts,
            int balancedAccounts,
            int unbalancedAccounts) {
    }

    /**
     * Per-account ledger snapshot returned by {@link #snapshot(Long)}.
     *
     * @param acctId         the account identifier
     * @param currentBalance the persisted
     *                       {@link Account#getAcctCurrBal()}
     * @param currCycCredit  the persisted
     *                       {@link Account#getAcctCurrCycCredit()}
     *                       (cumulative cycle credits, COBOL
     *                       {@code ACCT-CURR-CYC-CREDIT})
     * @param currCycDebit   the persisted
     *                       {@link Account#getAcctCurrCycDebit()}
     *                       (cumulative cycle debits, COBOL
     *                       {@code ACCT-CURR-CYC-DEBIT})
     * @param byCategory     per-category balance map keyed by
     *                       {@code "<typeCode>/<categoryCode>"}
     */
    public record LedgerSnapshot(
            Long acctId,
            BigDecimal currentBalance,
            BigDecimal currCycCredit,
            BigDecimal currCycDebit,
            Map<String, BigDecimal> byCategory) {
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Fetches every {@link TransactionCategoryBalance} for the given
     * account.
     *
     * <p>{@link TransactionCategoryBalanceRepository} does not expose
     * a {@code findByIdTrancatAcctId(Long)} derived query &mdash;
     * adding one would violate the AAP &sect;0.7.3 Minimal Change
     * Clause and conflict with the documented "no custom derived
     * queries" design rule on that repository. Instead this method
     * uses the inherited {@link
     * org.springframework.data.jpa.repository.JpaRepository#findAll()
     * findAll()} + in-memory filter approach explicitly recommended
     * by the schema for this service. The composite-key B-tree index
     * on the underlying PostgreSQL table makes per-account scans
     * efficient at the database layer when sorted lookups are
     * required; for reconciliation we accept the materialized
     * scan-and-filter cost as a deliberate trade-off against adding
     * derived-query surface area to the repository.</p>
     *
     * @param acctId the account identifier (must be non-{@code null})
     * @return every TCATBAL bucket for the account; never
     *         {@code null}, may be empty
     */
    private List<TransactionCategoryBalance> fetchCategoryBalances(Long acctId) {
        // Per AAP-attached repository documentation: no custom derived
        // queries are permitted on TransactionCategoryBalanceRepository.
        // The schema explicitly recommends findAll() + filter as the
        // approved access pattern for this service.
        return transactionCategoryBalanceRepository.findAll().stream()
                .filter(tcat -> tcat != null
                        && tcat.getId() != null
                        && acctId.equals(tcat.getId().getTrancatAcctId()))
                .toList();
    }

    /**
     * Sums every running balance in the provided list with banker's
     * rounding, treating {@code null} balance entries as zero.
     *
     * <p>Each addition is wrapped in {@code try}/{@code catch} for
     * {@link ArithmeticException}; on overflow the helper throws
     * {@link OnSizeErrorException}, preserving the COBOL
     * {@code COMPUTE ... ON SIZE ERROR} contract per AAP
     * &sect;0.6.1 / &sect;0.7.1.</p>
     *
     * @param balances the TCATBAL rows whose balances to sum (never
     *                 {@code null})
     * @return the accumulated total with scale = 2 and
     *         {@link RoundingMode#HALF_EVEN}
     * @throws OnSizeErrorException if any addition overflows
     */
    private static BigDecimal sumBalances(List<TransactionCategoryBalance> balances) {
        BigDecimal total = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN);
        for (TransactionCategoryBalance tcat : balances) {
            if (tcat == null) {
                continue;
            }
            BigDecimal value = tcat.getTranCatBal();
            if (value == null) {
                continue;
            }
            try {
                // COBOL: CBTRN02C:2700 ADD DALYTRAN-AMT TO TRAN-CAT-BAL
                //        rolled into the account-level reconciliation
                //        sum. HALF_EVEN per AAP §0.6.1.
                total = total.add(value).setScale(2, RoundingMode.HALF_EVEN);
            } catch (ArithmeticException ex) {
                throw new OnSizeErrorException(
                        "ON SIZE ERROR summing TCATBAL balances during ledger reconciliation",
                        ex);
            }
        }
        return total;
    }

    /**
     * Emits a {@code LEDGER_IMBALANCE} audit event via the
     * {@link AuditLogService} adapter (CloudTrail + OpenSearch).
     *
     * <p>The payload includes the discrepancy details and the
     * timestamp; the AuditLogService applies its own
     * PII/PAN-sanitization pass on the payload before indexing.</p>
     *
     * <p>This method intentionally swallows any {@link RuntimeException}
     * thrown by the audit adapter so that an audit-pipeline failure
     * does not abort the reconciliation transaction &mdash; the local
     * warning log already captured the imbalance, and the reconciliation
     * report is still returned to the caller.</p>
     *
     * @param acctId          the account being reconciled
     * @param expectedBalance the computed expected balance
     * @param actualBalance   the persisted actual balance
     */
    private void auditImbalance(Long acctId,
                                 BigDecimal expectedBalance,
                                 BigDecimal actualBalance) {
        try {
            BigDecimal difference = expectedBalance.subtract(actualBalance)
                    .setScale(2, RoundingMode.HALF_EVEN);
            Map<String, Object> payload = new HashMap<>();
            payload.put("acctId", acctId);
            payload.put("expectedBalance", expectedBalance);
            payload.put("actualBalance", actualBalance);
            payload.put("difference", difference);
            payload.put("source", "LedgerService.reconcileAccount");

            // Resource ID is the account ID stringified so the audit
            // pipeline indexes it identically to other ACCOUNT-scoped
            // events (see InterestCalculationService for the pattern).
            auditLogService.logAuditEvent(
                    AUDIT_LEDGER_IMBALANCE,
                    AUDIT_RESOURCE_ACCOUNT,
                    String.valueOf(acctId),
                    AUDIT_OPERATOR_RECONCILIATION,
                    payload,
                    null);
        } catch (RuntimeException ex) {
            // Audit-pipeline failure must not abort reconciliation —
            // the warning log already captured the imbalance, and
            // the caller still receives the ReconciliationReport.
            LOG.warn("LedgerService: failed to emit LEDGER_IMBALANCE audit "
                    + "for acctId={} (continuing)", acctId, ex);
        }
    }

    /**
     * Returns a non-{@code null} {@link BigDecimal} with scale = 2,
     * defaulting to {@link BigDecimal#ZERO} when the input is
     * {@code null}.
     *
     * <p>The COBOL source has no concept of a NULL numeric field
     * (every {@code PIC S9(n)V99} field is initialized to a fixed
     * pattern of zero digits before use). The Java target adopts the
     * same convention: a {@code null} monetary value is reconciliation-
     * equivalent to zero.</p>
     *
     * @param value the input balance; may be {@code null}
     * @return the input rescaled to 2 decimal places with
     *         {@link RoundingMode#HALF_EVEN}, or
     *         {@link BigDecimal#ZERO} with scale = 2 if the input is
     *         {@code null}
     */
    private static BigDecimal nonNullBalance(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN);
        }
        if (value.scale() == 2) {
            return value;
        }
        return value.setScale(2, RoundingMode.HALF_EVEN);
    }

    // -------------------------------------------------------------------------
    // Package-private accessors for white-box unit tests
    //
    // The reconciliation algorithm relies on the persisted
    // TransactionRepository injection (mandated by the schema
    // constructor signature and held available for future
    // per-transaction audit lookups). Exposing the field through a
    // package-private getter allows tests to verify the wiring without
    // resorting to reflection. CardDemoException is referenced through
    // the exception hierarchy (RecordNotFoundException and
    // OnSizeErrorException both extend CardDemoException) — both
    // exceptions are catchable as CardDemoException in caller code.
    // -------------------------------------------------------------------------

    /**
     * Package-private getter for the injected
     * {@link TransactionRepository}. Exposed for white-box test
     * verification of constructor injection wiring; application code
     * does not invoke this method.
     *
     * @return the injected {@link TransactionRepository}
     */
    TransactionRepository getTransactionRepository() {
        return transactionRepository;
    }

    /**
     * Convenience method used by callers that want to handle every
     * ledger-related failure under a single catch block. The
     * concrete exception hierarchy thrown by this service
     * ({@link RecordNotFoundException} and {@link OnSizeErrorException})
     * both extend {@link CardDemoException}, so a caller can elect
     * to catch {@code CardDemoException} as the common supertype.
     *
     * @param ex any throwable
     * @return {@code true} when {@code ex} is a
     *         {@link CardDemoException}; {@code false} otherwise
     */
    static boolean isCardDemoFailure(Throwable ex) {
        return ex instanceof CardDemoException;
    }
}

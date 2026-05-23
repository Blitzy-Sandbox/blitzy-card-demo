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
import com.awsm2.carddemo.domain.Transaction;
import com.awsm2.carddemo.exception.OnSizeErrorException;
import com.awsm2.carddemo.exception.RecordNotFoundException;
import com.awsm2.carddemo.repository.AccountRepository;
import com.awsm2.carddemo.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Ledger / double-entry bookkeeping service &mdash; provides
 * per-account reconciliation that validates that the running
 * {@link Account#getAcctCurrBal()} equals the sum of every
 * {@link Transaction#getTranAmt()} for that account&apos;s cards.
 *
 * <p>The COBOL source does not have a single dedicated double-entry
 * ledger program; instead, the end-of-day balance is an emergent
 * property of the complete batch pipeline
 * ({@code CBTRN02C} &rarr; {@code CBACT04C} &rarr; {@code COMBTRAN}
 * &rarr; {@code CREASTMT}/{@code TRANREPT}). This service makes that
 * implicit reconciliation explicit and surfaces a
 * {@code ledger.balanced} event for downstream audit / regulatory
 * consumers (AAP &sect;0.6.5: <em>&quot;Event-driven transaction
 * pipelines via Amazon MSK Kafka topics ({@code transaction.posted},
 * {@code account.updated}, {@code ledger.balanced}) partitioned by
 * account ID&quot;</em>).</p>
 *
 * <h2>Source provenance (AAP &sect;0.4.1)</h2>
 * <p>Marked &quot;Double-entry bookkeeping / ledger.balanced&quot; in
 * the file-by-file transformation plan. No single COBOL program is the
 * source; the reconciliation logic synthesises behavior that is
 * implicit in the batch pipeline as a whole.</p>
 *
 * <h2>Implementation notes (AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li><b>BigDecimal arithmetic:</b> All monetary arithmetic uses
 *       {@link BigDecimal} with {@link RoundingMode#HALF_EVEN}.</li>
 *   <li><b>ON SIZE ERROR:</b> The computed running total is guarded
 *       against the COBOL {@code PIC S9(10)V99} ceiling.</li>
 *   <li><b>Read-only by default:</b> Reconciliation does not write to
 *       persistence; it only reads and computes. Annotated
 *       {@link Transactional @Transactional(readOnly = true)}.</li>
 *   <li><b>Event emission:</b> Whether the reconciliation balances or
 *       not, a {@code ledger.balanced} event is emitted partitioned by
 *       account ID (AAP &sect;0.6.5). The event carries the
 *       reconciliation outcome so downstream auditors can detect
 *       imbalances even if no exception is thrown.</li>
 *   <li><b>Audit:</b> An audit record is written for every
 *       reconciliation regardless of outcome.</li>
 *   <li><b>No direct AWS SDK calls:</b> All AWS interaction goes
 *       through the {@link KafkaEventPublisher} and
 *       {@link AuditLogService} adapters.</li>
 * </ul>
 */
@Service
public class LedgerService {

    private static final Logger LOG =
            LoggerFactory.getLogger(LedgerService.class);

    /** ON SIZE ERROR guard for COBOL PIC S9(10)V99 fields. */
    static final BigDecimal MAX_AMOUNT = new BigDecimal("99999999999.99");

    /** Reconciliation tolerance &mdash; one cent. */
    static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    /** Audit event names. */
    static final String AUDIT_LEDGER_RECONCILED = "ledger.reconciled";

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final KafkaEventPublisher kafkaEventPublisher;
    private final AuditLogService auditLogService;

    public LedgerService(AccountRepository accountRepository,
                         TransactionRepository transactionRepository,
                         KafkaEventPublisher kafkaEventPublisher,
                         AuditLogService auditLogService) {
        this.accountRepository = Objects.requireNonNull(accountRepository,
                "accountRepository");
        this.transactionRepository = Objects.requireNonNull(transactionRepository,
                "transactionRepository");
        this.kafkaEventPublisher = Objects.requireNonNull(kafkaEventPublisher,
                "kafkaEventPublisher");
        this.auditLogService = Objects.requireNonNull(auditLogService,
                "auditLogService");
    }

    /**
     * Reconciliation outcome carrier emitted to the
     * {@code ledger.balanced} Kafka topic.
     *
     * @param accountId    the account being reconciled
     * @param balance      the persisted {@link Account#getAcctCurrBal()}
     * @param transactionTotal sum of {@link Transaction#getTranAmt()}
     *                         across every card linked to the account
     *                         in the reconciliation window
     * @param difference  {@code balance - transactionTotal}
     * @param balanced     {@code true} if {@code |difference| &le;
     *                     TOLERANCE} (one cent)
     * @param transactionCount number of transactions included in the
     *                         reconciliation
     * @param reconciledAt timestamp of the reconciliation
     */
    public record LedgerEvent(Long accountId,
                              BigDecimal balance,
                              BigDecimal transactionTotal,
                              BigDecimal difference,
                              boolean balanced,
                              int transactionCount,
                              LocalDateTime reconciledAt) {
    }

    /**
     * Reconciles a single account against its transactions.
     *
     * @param accountId account identifier
     * @param cardNumbers list of card numbers associated with the
     *                    account (typically supplied by the caller
     *                    after a XREF join); if empty, the
     *                    reconciliation is performed with no
     *                    transactions and the resulting total is zero
     * @return the {@link LedgerEvent} carrying the reconciliation
     *         outcome
     * @throws RecordNotFoundException if {@code accountId} does not
     *         exist
     */
    @Transactional(readOnly = true)
    public LedgerEvent reconcileAccount(Long accountId, List<String> cardNumbers) {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(cardNumbers, "cardNumbers");

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new RecordNotFoundException(
                        "ACCOUNT_NOT_FOUND",
                        "Account not found for reconciliation: " + accountId));

        BigDecimal balance = nonNull(account.getAcctCurrBal());
        BigDecimal total = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN);
        int txCount = 0;

        for (String cardNum : cardNumbers) {
            if (cardNum == null || cardNum.isBlank()) {
                continue;
            }
            List<Transaction> txs = transactionRepository.findByTranCardNum(cardNum);
            for (Transaction tx : txs) {
                BigDecimal amt = nonNull(tx.getTranAmt());
                total = total.add(amt).setScale(2, RoundingMode.HALF_EVEN);
                guardOnSizeError(total, "LEDGER-TOTAL");
                txCount++;
            }
        }

        BigDecimal difference = balance.subtract(total)
                .setScale(2, RoundingMode.HALF_EVEN);
        boolean balanced = difference.abs().compareTo(TOLERANCE) <= 0;

        LedgerEvent event = new LedgerEvent(
                accountId,
                balance,
                total,
                difference,
                balanced,
                txCount,
                LocalDateTime.now());

        publishLedgerBalanced(event);
        auditReconciliation(event);

        if (balanced) {
            LOG.info("Ledger reconciled successfully for account {} "
                    + "(balance={}, total={}, transactions={})",
                    accountId, balance, total, txCount);
        } else {
            LOG.warn("Ledger imbalance detected for account {} "
                    + "(balance={}, total={}, difference={}, transactions={})",
                    accountId, balance, total, difference, txCount);
        }

        return event;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void publishLedgerBalanced(LedgerEvent event) {
        Map<String, Object> payload = buildEventPayload(event);
        try {
            kafkaEventPublisher.publishLedgerBalanced(event.accountId(), payload);
        } catch (RuntimeException ex) {
            // Event emission failures should not fail the reconciliation
            // computation &mdash; the audit trail captures the outcome.
            LOG.warn("LedgerService: ledger.balanced publish failed "
                    + "for account {} (continuing)", event.accountId(), ex);
        }
    }

    private void auditReconciliation(LedgerEvent event) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("accountId", event.accountId());
        payload.put("balance", event.balance());
        payload.put("transactionTotal", event.transactionTotal());
        payload.put("difference", event.difference());
        payload.put("balanced", event.balanced());
        payload.put("transactionCount", event.transactionCount());
        payload.put("reconciledAt", event.reconciledAt());
        auditLogService.logAuditEvent(
                AUDIT_LEDGER_RECONCILED,
                "ACCOUNT_LEDGER",
                String.valueOf(event.accountId()),
                "BATCH",
                payload,
                null);
    }

    private static Map<String, Object> buildEventPayload(LedgerEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("accountId", event.accountId());
        payload.put("balance", event.balance());
        payload.put("transactionTotal", event.transactionTotal());
        payload.put("difference", event.difference());
        payload.put("balanced", event.balanced());
        payload.put("transactionCount", event.transactionCount());
        payload.put("reconciledAt", event.reconciledAt());
        return payload;
    }

    private static BigDecimal nonNull(BigDecimal value) {
        return value == null
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN)
                : value;
    }

    private static void guardOnSizeError(BigDecimal value, String fieldName) {
        if (value == null) {
            return;
        }
        if (value.abs().compareTo(MAX_AMOUNT) > 0) {
            throw new OnSizeErrorException(
                    "ON_SIZE_ERROR",
                    "ON SIZE ERROR on " + fieldName
                            + " (computed " + value
                            + " exceeds PIC S9(10)V99 ceiling)");
        }
    }
}

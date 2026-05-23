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
import com.awsm2.carddemo.exception.OnSizeErrorException;
import com.awsm2.carddemo.exception.RecordNotFoundException;
import com.awsm2.carddemo.repository.AccountRepository;
import com.awsm2.carddemo.repository.CardCrossReferenceRepository;
import com.awsm2.carddemo.repository.DisclosureGroupRepository;
import com.awsm2.carddemo.repository.TransactionCategoryBalanceRepository;
import com.awsm2.carddemo.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Interest-calculation batch service &mdash; the Java target for the COBOL
 * batch program {@code app/cbl/CBACT04C.cbl}.
 *
 * <p>This service implements the end-of-day interest-accrual job. It
 * iterates through every {@link TransactionCategoryBalance} row
 * (replaces the COBOL TCATBAL VSAM cluster read loop), looks up the
 * applicable disclosure-group interest rate by composite key
 * {@code (account-group-id, transaction-type-code, transaction-category-code)},
 * computes the monthly interest using the COBOL-literal formula
 * <em>{@code (balance * rate) / 1200}</em> (paragraph
 * {@code 1300-COMPUTE-INTEREST} at {@code CBACT04C.cbl} L462-L468), and
 * accumulates the per-account total. For every non-zero category interest
 * it writes a Java {@link Transaction} record (paragraph
 * {@code 1300-B-WRITE-TX} at L473-L515) keyed by {@code PARM-DATE +
 * WS-TRANID-SUFFIX} (date prefix + 6-digit sequence). When the iteration
 * crosses an account boundary the accumulated total is added to the
 * account's {@link Account#getAcctCurrBal()} and the cycle credit/debit
 * counters are zeroed (paragraph {@code 1050-UPDATE-ACCOUNT} at
 * {@code CBACT04C.cbl} L350-L370).</p>
 *
 * <h2>Source provenance (AAP &sect;0.7.3)</h2>
 * <ul>
 *   <li><b>COBOL program:</b> {@code app/cbl/CBACT04C.cbl} &mdash;
 *       batch interest calculator invoked by JCL job
 *       {@code app/jcl/INTCALC.jcl} (PARM=&apos;yyyyMMddHH&apos;).</li>
 *   <li><b>Record layouts:</b>
 *       {@code app/cpy/CVTRA01Y.cpy}
 *       ({@code TRAN-CAT-BAL-RECORD}, 50 bytes; {@link
 *       TransactionCategoryBalance}),
 *       {@code app/cpy/CVTRA02Y.cpy}
 *       ({@code DIS-GROUP-RECORD}, 50 bytes; {@link DisclosureGroup}),
 *       {@code app/cpy/CVACT01Y.cpy}
 *       ({@code ACCOUNT-RECORD}, 300 bytes; {@link Account}),
 *       {@code app/cpy/CVACT03Y.cpy}
 *       ({@code CARD-XREF-RECORD}, 50 bytes; {@link CardCrossReference}),
 *       {@code app/cpy/CVTRA05Y.cpy}
 *       ({@code TRAN-RECORD}, 350 bytes; {@link Transaction}).</li>
 * </ul>
 *
 * <h2>COBOL paragraph translation</h2>
 * <table>
 *   <caption>CBACT04C.cbl &harr; InterestCalculationService</caption>
 *   <tr><th>COBOL paragraph</th><th>Java equivalent</th></tr>
 *   <tr><td>{@code PROCEDURE DIVISION USING EXTERNAL-PARMS} (L180-L231)</td>
 *       <td>{@link #calculateInterest(LocalDate, String)} &mdash; outer
 *       loop driver</td></tr>
 *   <tr><td>{@code 1000-TCATBALF-GET-NEXT}</td>
 *       <td>{@link TransactionCategoryBalanceRepository#findAll()} stream
 *       ordered by composite key</td></tr>
 *   <tr><td>{@code 1100-GET-ACCT-DATA}</td>
 *       <td>{@link AccountRepository#findById(Object)}</td></tr>
 *   <tr><td>{@code 1110-GET-XREF-DATA}</td>
 *       <td>{@link CardCrossReferenceRepository#findByXrefAcctId(Long)}</td></tr>
 *   <tr><td>{@code 1200-GET-INTEREST-RATE} + {@code 1200-A-GET-DEFAULT-INT-RATE}
 *       (L415-L460 &mdash; with DEFAULT fallback when specific group
 *       missing)</td>
 *       <td>{@link #lookupInterestRate(String, String, Integer)} &mdash;
 *       primary lookup then literal {@code "DEFAULT"} group-code retry</td></tr>
 *   <tr><td>{@code 1300-COMPUTE-INTEREST} (L462-L468 &mdash; literal
 *       1200 divisor)</td>
 *       <td>{@link #computeMonthlyInterest(BigDecimal, BigDecimal)}</td></tr>
 *   <tr><td>{@code 1300-B-WRITE-TX} (L473-L515 &mdash; PARM-DATE +
 *       6-digit suffix transaction ID)</td>
 *       <td>{@link #writeInterestTransaction(String, BigDecimal, Account,
 *       String, LocalDateTime)}</td></tr>
 *   <tr><td>{@code 1050-UPDATE-ACCOUNT} (L350-L370 &mdash; ADD
 *       WS-TOTAL-INT TO ACCT-CURR-BAL; zero cycle counters)</td>
 *       <td>{@link #updateAccount(Account, BigDecimal)}</td></tr>
 * </table>
 *
 * <h2>Implementation notes (AAP &sect;0.7.1 Refactor Discipline)</h2>
 * <ul>
 *   <li><b>Literal 1200 divisor preserved:</b> Per AAP &sect;0.6.1
 *       <em>&quot;the divisor 1200 is preserved as
 *       {@code BigDecimal.valueOf(1200)} rather than precomputed&quot;</em>.
 *       This is the COBOL annualization formula
 *       <em>{@code rate / 12 months / 100 percentage scaling = rate / 1200}</em>
 *       and the literal is retained verbatim per the Minimal Change
 *       Clause.</li>
 *   <li><b>Banker's rounding:</b> All monetary arithmetic uses
 *       {@link RoundingMode#HALF_EVEN} (banker's rounding) with
 *       {@code setScale(2, HALF_EVEN)} after every multiply/divide chain,
 *       matching COBOL {@code COMPUTE} fixed-point semantics.</li>
 *   <li><b>DEFAULT group-code fallback:</b> When the specific
 *       {@code (account-group-id, type-code, category-code)} tuple
 *       returns no row, the service moves the literal string
 *       {@code "DEFAULT"} into the group-code position and retries
 *       (paragraph {@code 1200-A-GET-DEFAULT-INT-RATE} at L443). If the
 *       DEFAULT row is also missing, a {@link RecordNotFoundException}
 *       with reason code {@code DEFAULT_DISC_GROUP_MISSING} is thrown
 *       (corresponds to the COBOL display
 *       {@code 'ERROR READING DEFAULT DISCLOSURE GROUP'} at L455 followed
 *       by {@code PERFORM 9999-ABEND-PROGRAM}).</li>
 *   <li><b>Transaction-ID format:</b> The COBOL writer concatenates
 *       {@code PARM-DATE} (10 chars) with {@code WS-TRANID-SUFFIX} (6
 *       digits) to form the 16-character {@code TRAN-ID} (paragraph
 *       {@code 1300-B-WRITE-TX} at L473-L484). This service preserves
 *       that pattern verbatim with {@code parmDate + String.format("%06d",
 *       suffix)} where {@code suffix} is a per-job-run counter starting
 *       at 1.</li>
 *   <li><b>ON SIZE ERROR:</b> Every monetary arithmetic is guarded
 *       against the COBOL {@code PIC S9(10)V99} ceiling
 *       ({@code 99999999999.99}); an {@link OnSizeErrorException} is
 *       thrown if the computation would overflow.</li>
 *   <li><b>Transaction boundary:</b> The single
 *       {@link Transactional @Transactional(rollbackFor =
 *       Exception.class)} wraps the entire batch run so a partial failure
 *       rolls back all account updates and interest postings &mdash;
 *       matching COBOL CICS SYNCPOINT-on-success semantics.</li>
 *   <li><b>Cache invalidation:</b> After each {@code 1050-UPDATE-ACCOUNT},
 *       the {@code accountView} cache entry is evicted so subsequent
 *       reads from {@code AccountViewService} see the post-interest
 *       balance (per AAP &sect;0.3.3 cache-aside pattern).</li>
 *   <li><b>Event emission:</b> For each new interest {@link Transaction},
 *       the service publishes {@code transaction.posted} to MSK
 *       partitioned by account ID (AAP &sect;0.6.5). At the end of each
 *       account, an {@code account.updated} event is also published.</li>
 *   <li><b>Audit:</b> Each posted interest transaction and each
 *       account update is written to the audit trail (AAP &sect;0.6.6).</li>
 *   <li><b>No direct AWS SDK calls:</b> All AWS interaction is
 *       delegated to adapters ({@link AuditLogService},
 *       {@link CacheService}, {@link KafkaEventPublisher}) per AAP
 *       &sect;0.7.1.</li>
 * </ul>
 *
 * <p><strong>Concurrency:</strong> This service is a Spring singleton.
 * Instance fields are immutable (final collaborator references). The
 * per-run mutable state (running totals, suffix counter) is held as
 * method-local variables, so multiple parallel runs on separate threads
 * remain isolated. However, by AAP design this batch runs serially in
 * AWS Batch (one job per day per JES-equivalent schedule); concurrent
 * invocations are not expected.</p>
 */
@Service
public class InterestCalculationService {

    private static final Logger LOG =
            LoggerFactory.getLogger(InterestCalculationService.class);

    /**
     * Literal divisor used by the COBOL interest formula at
     * {@code CBACT04C.cbl} L465: {@code (TRAN-CAT-BAL * DIS-INT-RATE) /
     * 1200}. The value 1200 is the annualization constant (12 months
     * &times; 100 percentage scaling). Per AAP &sect;0.6.1 this divisor
     * is preserved as a literal {@link BigDecimal} rather than
     * pre-computed, to match COBOL semantics exactly.
     */
    static final BigDecimal MONTHLY_DIVISOR = BigDecimal.valueOf(1200);

    /**
     * Literal group-code used by the DEFAULT-fallback retry at
     * {@code CBACT04C.cbl} L437:
     * <pre>{@code MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID}</pre>
     * The COBOL source declares this as a 10-character field; the
     * trimmed Java literal is sufficient because Hibernate trims trailing
     * padding on read and the seed migration
     * {@code V012__seed_disclosure_group.sql} stores the row with the
     * trimmed key.
     */
    static final String DEFAULT_GROUP_CODE = "DEFAULT";

    /**
     * Maximum representable monetary value used as the ON SIZE ERROR
     * guard for the COBOL {@code PIC S9(10)V99} ceiling on
     * {@code ACCT-CURR-BAL} and {@code TRAN-AMT}. Any computed balance
     * or transaction amount whose absolute value exceeds this threshold
     * triggers an {@link OnSizeErrorException}.
     */
    static final BigDecimal MAX_AMOUNT = new BigDecimal("99999999999.99");

    /**
     * COBOL constants written into every interest {@link Transaction}
     * record by paragraph {@code 1300-B-WRITE-TX} (L485-L504). Per the
     * AAP Minimal Change Clause (&sect;0.7.3) these literals are
     * preserved verbatim.
     */
    static final String TRAN_TYPE_INTEREST = "01";
    static final Integer TRAN_CAT_INTEREST = 5;
    static final String TRAN_SOURCE_SYSTEM = "System";
    static final long TRAN_MERCHANT_ID_NONE = 0L;
    static final String TRAN_MERCHANT_BLANK = "";

    /** MSK topic discriminators (AAP &sect;0.6.5). */
    static final String EVENT_TRANSACTION_POSTED = "transaction.posted";
    static final String EVENT_ACCOUNT_UPDATED = "account.updated";

    /** Cache namespace for the AccountView cache-aside entries. */
    static final String CACHE_NS_ACCOUNT = "accountView";

    /** Audit event identifiers for the AuditLogService. */
    static final String AUDIT_EVENT_INTEREST_POSTED = "interest.posted";
    static final String AUDIT_EVENT_INTEREST_ACCRUED = "interest.accrued";

    /** ISO-style parm-date formatter for PARM=&apos;yyyyMMdd&apos;. */
    private static final DateTimeFormatter PARM_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd");

    private final TransactionCategoryBalanceRepository balanceRepository;
    private final DisclosureGroupRepository disclosureGroupRepository;
    private final AccountRepository accountRepository;
    private final CardCrossReferenceRepository xrefRepository;
    private final TransactionRepository transactionRepository;
    private final KafkaEventPublisher kafkaEventPublisher;
    private final CacheService cacheService;
    private final AuditLogService auditLogService;

    /**
     * Constructor injection (AAP &sect;0.7.1 &mdash; constructor injection
     * for loose coupling) of every collaborator.
     */
    public InterestCalculationService(
            TransactionCategoryBalanceRepository balanceRepository,
            DisclosureGroupRepository disclosureGroupRepository,
            AccountRepository accountRepository,
            CardCrossReferenceRepository xrefRepository,
            TransactionRepository transactionRepository,
            KafkaEventPublisher kafkaEventPublisher,
            CacheService cacheService,
            AuditLogService auditLogService) {
        this.balanceRepository = Objects.requireNonNull(balanceRepository,
                "balanceRepository");
        this.disclosureGroupRepository = Objects.requireNonNull(
                disclosureGroupRepository, "disclosureGroupRepository");
        this.accountRepository = Objects.requireNonNull(accountRepository,
                "accountRepository");
        this.xrefRepository = Objects.requireNonNull(xrefRepository,
                "xrefRepository");
        this.transactionRepository = Objects.requireNonNull(
                transactionRepository, "transactionRepository");
        this.kafkaEventPublisher = Objects.requireNonNull(kafkaEventPublisher,
                "kafkaEventPublisher");
        this.cacheService = Objects.requireNonNull(cacheService,
                "cacheService");
        this.auditLogService = Objects.requireNonNull(auditLogService,
                "auditLogService");
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Result summary returned to the caller (and to the Spring Batch
     * step wrapping this service) describing the work completed in the
     * batch run.
     *
     * @param accountsProcessed number of distinct accounts updated
     * @param transactionsPosted number of new interest {@link Transaction}
     *                           records written
     * @param totalInterest cumulative interest posted (sum of all
     *                      {@link Transaction#getTranAmt()} values)
     */
    public record Result(int accountsProcessed,
                         int transactionsPosted,
                         BigDecimal totalInterest) {
    }

    /**
     * Executes one end-of-day interest-calculation pass.
     *
     * <p>Translates the COBOL outer loop at {@code CBACT04C.cbl}
     * L188-L223 plus the surrounding open/close paragraphs. Iterates
     * through the {@link TransactionCategoryBalance} table ordered by
     * the composite key {@code (account-id, type-code, category-code)}
     * &mdash; the natural sort order of the source TCATBAL VSAM
     * cluster &mdash; accumulates per-account interest, writes
     * {@link Transaction} records, and updates the {@link Account}
     * balance at each account boundary.</p>
     *
     * @param parmDate the batch-job parameter date used both as the
     *                 transaction-ID prefix (10 chars wide as
     *                 {@code yyyyMMdd} + 2 placeholder digits zero) and
     *                 as the audit-trail run identifier. Must not be
     *                 {@code null}.
     * @param batchRunId batch execution identifier (e.g. Step Functions
     *                   execution ARN suffix). Must not be {@code null}.
     * @return a {@link Result} summary of the run
     * @throws RecordNotFoundException if an account or default
     *         disclosure group is missing (COBOL
     *         {@code DISPLAY 'ERROR ...'} followed by
     *         {@code PERFORM 9999-ABEND-PROGRAM})
     * @throws OnSizeErrorException if a computed amount overflows the
     *         {@code PIC S9(10)V99} ceiling
     */
    @Transactional(rollbackFor = Exception.class)
    public Result calculateInterest(LocalDate parmDate, String batchRunId) {
        Objects.requireNonNull(parmDate, "parmDate");
        if (batchRunId == null || batchRunId.isBlank()) {
            throw new IllegalArgumentException("batchRunId must not be null or blank");
        }

        // COBOL: STRING PARM-DATE, WS-TRANID-SUFFIX DELIMITED BY SIZE
        // INTO TRAN-ID -- the COBOL PARM-DATE is 10 chars (yyyyMMddHH);
        // we use yyyyMMdd + 2 padding zeros to match the 10-byte prefix
        // exactly. The 6-digit suffix is incremented per posted txn.
        String tranIdPrefix = parmDate.format(PARM_DATE_FORMATTER) + "00";

        LOG.info("CBACT04C: starting interest-calculation run "
                + "(parmDate={}, batchRunId={}, tranIdPrefix={})",
                parmDate, batchRunId, tranIdPrefix);

        // Iterate the entire TCATBAL VSAM cluster. Repository ordering
        // matches the COBOL VSAM key order (acct-id, type-cd, cat-cd).
        List<TransactionCategoryBalance> balances = balanceRepository.findAll();
        balances.sort((a, b) -> {
            int c = Long.compare(a.getId().getTrancatAcctId(),
                    b.getId().getTrancatAcctId());
            if (c != 0) {
                return c;
            }
            c = a.getId().getTrancatTypeCd().compareTo(b.getId().getTrancatTypeCd());
            if (c != 0) {
                return c;
            }
            return a.getId().getTrancatCd().compareTo(b.getId().getTrancatCd());
        });

        // Per-run mutable state corresponding to COBOL WORKING-STORAGE:
        //   WS-LAST-ACCT-NUM   PIC 9(11)         (the running account id)
        //   WS-FIRST-TIME      PIC X(01) VALUE 'Y'
        //   WS-TOTAL-INT       PIC S9(09)V99
        //   WS-TRANID-SUFFIX   PIC 9(06) VALUE 0
        //   WS-RECORD-COUNT    PIC 9(09) VALUE 0
        Long lastAcctNum = null;
        boolean firstTime = true;
        BigDecimal totalInt = BigDecimal.ZERO;
        int tranIdSuffix = 0;
        int recordCount = 0;

        // Per-account context (refreshed on account boundary)
        Account currentAccount = null;
        String currentCardNum = null;

        // Cumulative result counters
        int accountsProcessed = 0;
        int transactionsPosted = 0;
        BigDecimal cumulativeInterest = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN);

        for (TransactionCategoryBalance bal : balances) {
            recordCount++;
            Long currentAcctId = bal.getId().getTrancatAcctId();

            // COBOL: IF TRANCAT-ACCT-ID NOT= WS-LAST-ACCT-NUM (L194)
            if (!currentAcctId.equals(lastAcctNum)) {
                // COBOL: IF WS-FIRST-TIME NOT = 'Y' PERFORM 1050-UPDATE-ACCOUNT
                if (!firstTime) {
                    updateAccount(currentAccount, totalInt);
                    cumulativeInterest = cumulativeInterest.add(totalInt);
                    accountsProcessed++;
                } else {
                    firstTime = false;
                }
                // COBOL: MOVE 0 TO WS-TOTAL-INT (L201)
                totalInt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN);
                lastAcctNum = currentAcctId;

                // COBOL: MOVE TRANCAT-ACCT-ID TO FD-ACCT-ID
                //        PERFORM 1100-GET-ACCT-DATA (L203)
                currentAccount = accountRepository.findById(currentAcctId)
                        .orElseThrow(() -> new RecordNotFoundException(
                                "ACCOUNT_NOT_FOUND",
                                "Account not found for interest calc: "
                                        + currentAcctId));

                // COBOL: MOVE TRANCAT-ACCT-ID TO FD-XREF-ACCT-ID
                //        PERFORM 1110-GET-XREF-DATA (L204-L205)
                List<CardCrossReference> xrefs =
                        xrefRepository.findByXrefAcctId(currentAcctId);
                if (xrefs.isEmpty()) {
                    LOG.warn("CBACT04C: no XREF entry for account {} "
                            + "&mdash; interest posting will use blank card number",
                            currentAcctId);
                    currentCardNum = null;
                } else {
                    currentCardNum = xrefs.get(0).getXrefCardNum();
                }
            }

            // COBOL: MOVE ACCT-GROUP-ID TO FD-DIS-ACCT-GROUP-ID (L210)
            //        MOVE TRANCAT-CD TO FD-DIS-TRAN-CAT-CD     (L211)
            //        MOVE TRANCAT-TYPE-CD TO FD-DIS-TRAN-TYPE-CD (L212)
            //        PERFORM 1200-GET-INTEREST-RATE             (L213)
            BigDecimal rate = lookupInterestRate(
                    currentAccount.getAcctGroupId(),
                    bal.getId().getTrancatTypeCd(),
                    bal.getId().getTrancatCd());

            // COBOL: IF DIS-INT-RATE NOT = 0
            //          PERFORM 1300-COMPUTE-INTEREST  (L214-L215)
            //          PERFORM 1400-COMPUTE-FEES      (L216 &mdash; no-op in source)
            //        END-IF
            if (rate.signum() != 0) {
                BigDecimal monthlyInt = computeMonthlyInterest(
                        bal.getTranCatBal(), rate);
                totalInt = totalInt.add(monthlyInt)
                        .setScale(2, RoundingMode.HALF_EVEN);
                guardOnSizeError(totalInt, "WS-TOTAL-INT");

                // COBOL: PERFORM 1300-B-WRITE-TX (L469)
                tranIdSuffix++;
                String tranId = formatTranId(tranIdPrefix, tranIdSuffix);
                LocalDateTime now = LocalDateTime.now();
                Transaction tx = writeInterestTransaction(
                        tranId, monthlyInt, currentAccount, currentCardNum, now);

                publishTransactionPosted(currentAccount.getAcctId(), tx, batchRunId);
                auditLogService.logTransactionEvent(
                        tx.getTranId(),
                        currentAccount.getAcctId(),
                        "BATCH",
                        AUDIT_EVENT_INTEREST_POSTED,
                        null,
                        buildTxAuditPayload(tx, bal, rate, batchRunId),
                        batchRunId);
                transactionsPosted++;
            }
            // 1400-COMPUTE-FEES is a no-op in the COBOL source
            // (paragraph body is empty &mdash; L518-L520: "To be implemented").
        }

        // COBOL: END-OF-FILE branch &mdash; PERFORM 1050-UPDATE-ACCOUNT (L220)
        if (!firstTime && currentAccount != null) {
            updateAccount(currentAccount, totalInt);
            cumulativeInterest = cumulativeInterest.add(totalInt);
            accountsProcessed++;
        }

        LOG.info("CBACT04C: completed interest calculation; "
                + "records={}, accountsProcessed={}, transactionsPosted={}, "
                + "totalInterest={}",
                recordCount, accountsProcessed, transactionsPosted,
                cumulativeInterest);

        // Final audit summary for the batch run
        auditLogService.logAuditEvent(
                AUDIT_EVENT_INTEREST_ACCRUED,
                "BATCH_RUN",
                batchRunId,
                "BATCH",
                buildRunAuditPayload(parmDate, accountsProcessed,
                        transactionsPosted, cumulativeInterest, recordCount),
                batchRunId);

        return new Result(accountsProcessed, transactionsPosted, cumulativeInterest);
    }

    // -------------------------------------------------------------------------
    // Helper methods (one per COBOL paragraph)
    // -------------------------------------------------------------------------

    /**
     * COBOL: 1200-GET-INTEREST-RATE + 1200-A-GET-DEFAULT-INT-RATE (L415-L460).
     *
     * <p>Performs the disclosure-group lookup with the AAP-mandated
     * DEFAULT-fallback: first tries the specific {@code (group-id,
     * type-cd, cat-cd)} tuple; if missing, retries with the literal
     * group-code {@code "DEFAULT"}. If the DEFAULT row is also missing
     * the COBOL source aborts with {@code PERFORM 9999-ABEND-PROGRAM};
     * this Java target throws {@link RecordNotFoundException} with reason
     * code {@code DEFAULT_DISC_GROUP_MISSING}.</p>
     *
     * @param groupId  the account's {@link Account#getAcctGroupId()}
     *                 (10-char COBOL X(10) field)
     * @param typeCd   transaction type code (2-char COBOL X(02))
     * @param categoryCd transaction category code (4-digit COBOL 9(04))
     * @return the applicable disclosure-group interest rate
     */
    BigDecimal lookupInterestRate(String groupId, String typeCd, Integer categoryCd) {
        // Primary lookup &mdash; COBOL paragraph 1200-GET-INTEREST-RATE
        DisclosureGroupId specificKey = new DisclosureGroupId(
                groupId, typeCd, categoryCd);
        Optional<DisclosureGroup> specific =
                disclosureGroupRepository.findById(specificKey);
        if (specific.isPresent()) {
            return specific.get().getDisIntRate();
        }

        // Fallback &mdash; COBOL: MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID
        //                        PERFORM 1200-A-GET-DEFAULT-INT-RATE (L437-L438)
        LOG.debug("CBACT04C: disclosure group missing for ({}, {}, {}); "
                + "trying DEFAULT group code", groupId, typeCd, categoryCd);
        DisclosureGroupId defaultKey = new DisclosureGroupId(
                DEFAULT_GROUP_CODE, typeCd, categoryCd);
        return disclosureGroupRepository.findById(defaultKey)
                .map(DisclosureGroup::getDisIntRate)
                .orElseThrow(() -> new RecordNotFoundException(
                        "DEFAULT_DISC_GROUP_MISSING",
                        "DEFAULT disclosure group rule not found for "
                                + "(type=" + typeCd + ", cat=" + categoryCd
                                + "); CBACT04C cannot post interest"));
    }

    /**
     * COBOL: 1300-COMPUTE-INTEREST (L462-L468):
     * <pre>{@code COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200}</pre>
     *
     * <p>The literal 1200 divisor is preserved verbatim per AAP
     * &sect;0.6.1 (&quot;The divisor 1200 is preserved as
     * {@code BigDecimal.valueOf(1200)} rather than precomputed&quot;).
     * Banker's rounding ({@link RoundingMode#HALF_EVEN}) is applied at
     * the divide step to mirror COBOL {@code PIC S9(09)V99} fixed-point
     * truncation semantics.</p>
     *
     * @param balance the category balance ({@code TRAN-CAT-BAL})
     * @param rate    the annual interest rate ({@code DIS-INT-RATE})
     * @return the per-month interest amount with scale=2 and HALF_EVEN
     */
    BigDecimal computeMonthlyInterest(BigDecimal balance, BigDecimal rate) {
        if (balance == null || rate == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN);
        }
        BigDecimal result = balance
                .multiply(rate)
                .divide(MONTHLY_DIVISOR, 2, RoundingMode.HALF_EVEN);
        guardOnSizeError(result, "WS-MONTHLY-INT");
        return result;
    }

    /**
     * COBOL: 1300-B-WRITE-TX (L473-L515) &mdash; writes the interest
     * transaction record to the TRANSACT VSAM cluster.
     */
    Transaction writeInterestTransaction(String tranId,
                                         BigDecimal amount,
                                         Account account,
                                         String cardNum,
                                         LocalDateTime now) {
        // COBOL: STRING 'Int. for a/c ', ACCT-ID DELIMITED BY SIZE
        //        INTO TRAN-DESC (L488-L491)
        String description = "Int. for a/c " + account.getAcctId();
        Transaction tx = new Transaction(
                tranId,
                TRAN_TYPE_INTEREST,
                TRAN_CAT_INTEREST,
                TRAN_SOURCE_SYSTEM,
                description,
                amount,
                TRAN_MERCHANT_ID_NONE,
                TRAN_MERCHANT_BLANK,
                TRAN_MERCHANT_BLANK,
                TRAN_MERCHANT_BLANK,
                cardNum != null ? cardNum : "",
                now,
                now);
        return transactionRepository.save(tx);
    }

    /**
     * COBOL: 1050-UPDATE-ACCOUNT (L350-L370):
     * <pre>{@code ADD WS-TOTAL-INT  TO ACCT-CURR-BAL
     * MOVE 0 TO ACCT-CURR-CYC-CREDIT
     * MOVE 0 TO ACCT-CURR-CYC-DEBIT
     * REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD}</pre>
     *
     * <p>The Java target additionally evicts the cache entry, publishes
     * an {@code account.updated} MSK event, and emits an audit record.
     * These are pure cross-cutting concerns introduced by the cloud
     * migration (AAP &sect;0.3.3 cache-aside, &sect;0.6.5 event-driven,
     * &sect;0.6.6 audit) and do not alter the COBOL semantics.</p>
     */
    void updateAccount(Account account, BigDecimal totalInt) {
        BigDecimal previousBalance = account.getAcctCurrBal();
        BigDecimal newBalance = previousBalance.add(totalInt)
                .setScale(2, RoundingMode.HALF_EVEN);
        guardOnSizeError(newBalance, "ACCT-CURR-BAL");
        account.setAcctCurrBal(newBalance);
        // COBOL: MOVE 0 TO ACCT-CURR-CYC-CREDIT / ACCT-CURR-CYC-DEBIT
        account.setAcctCurrCycCredit(BigDecimal.ZERO
                .setScale(2, RoundingMode.HALF_EVEN));
        account.setAcctCurrCycDebit(BigDecimal.ZERO
                .setScale(2, RoundingMode.HALF_EVEN));
        Account saved = accountRepository.save(account);

        // Cache eviction (cache-aside &mdash; AAP §0.3.3)
        try {
            cacheService.evict(CACHE_NS_ACCOUNT, String.valueOf(saved.getAcctId()));
        } catch (RuntimeException ex) {
            // Cache failures must not roll back the financial transaction.
            LOG.warn("CBACT04C: cache eviction failed for account {} (continuing)",
                    saved.getAcctId(), ex);
        }

        // Account-updated event (AAP §0.6.5)
        AccountUpdateDto event = buildAccountUpdateEvent(saved);
        try {
            kafkaEventPublisher.publishAccountUpdated(saved.getAcctId(), event);
        } catch (RuntimeException ex) {
            LOG.warn("CBACT04C: account.updated publish failed for account {}",
                    saved.getAcctId(), ex);
        }

        // Audit (AAP §0.6.6)
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("accountId", saved.getAcctId());
        payload.put("interestPosted", totalInt);
        payload.put("previousBalance", previousBalance);
        payload.put("newBalance", newBalance);
        auditLogService.logAuditEvent(
                EVENT_ACCOUNT_UPDATED,
                "ACCOUNT",
                String.valueOf(saved.getAcctId()),
                "BATCH",
                payload,
                null);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Build the 16-character transaction ID per COBOL paragraph
     * {@code 1300-B-WRITE-TX} L478-L482:
     * <pre>{@code STRING PARM-DATE, WS-TRANID-SUFFIX
     *         DELIMITED BY SIZE
     *         INTO TRAN-ID
     *      END-STRING}</pre>
     * yielding a 16-char id (10-char prefix + 6-digit suffix).
     */
    private String formatTranId(String prefix, int suffix) {
        if (suffix > 999_999) {
            // COBOL WS-TRANID-SUFFIX is PIC 9(06); overflowing this
            // would cause a SOC7 (numeric truncation) at runtime.
            throw new OnSizeErrorException(
                    "TRAN_ID_SUFFIX_OVERFLOW",
                    "WS-TRANID-SUFFIX exceeded 999999 within a single batch run");
        }
        return prefix + String.format("%06d", suffix);
    }

    /**
     * COBOL ON SIZE ERROR guard: throws when the computed amount would
     * overflow the {@code PIC S9(10)V99} target.
     */
    private void guardOnSizeError(BigDecimal value, String fieldName) {
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

    /**
     * Publishes {@code transaction.posted} to MSK partitioned by account
     * id. Failures are logged but do not roll back the financial
     * transaction (the audit trail captures the failure for downstream
     * reconciliation).
     */
    private void publishTransactionPosted(Long acctId, Transaction tx, String batchRunId) {
        try {
            com.awsm2.carddemo.dto.TransactionAddDto event =
                    new com.awsm2.carddemo.dto.TransactionAddDto(
                            String.format("%011d", acctId),
                            tx.getTranCardNum() == null ? "" : tx.getTranCardNum(),
                            tx.getTranTypeCd(),
                            tx.getTranCatCd(),
                            tx.getTranSource(),
                            tx.getTranDesc(),
                            tx.getTranAmt(),
                            tx.getTranOrigTs(),
                            tx.getTranProcTs(),
                            tx.getTranMerchantId(),
                            tx.getTranMerchantName(),
                            tx.getTranMerchantCity(),
                            tx.getTranMerchantZip(),
                            "Y");
            kafkaEventPublisher.publishTransactionPosted(acctId, event);
        } catch (RuntimeException ex) {
            LOG.warn("CBACT04C: transaction.posted publish failed "
                    + "for account {} batchRun {} (continuing)",
                    acctId, batchRunId, ex);
        }
    }

    /**
     * Builds an {@link AccountUpdateDto} populated with the persisted
     * account state plus null defaults for the customer-side fields
     * (which {@link InterestCalculationService} does not modify). The
     * downstream consumer is responsible for joining the customer
     * record from its own data sources if needed.
     */
    private AccountUpdateDto buildAccountUpdateEvent(Account account) {
        return new AccountUpdateDto(
                account.getAcctId(),
                account.getAcctActiveStatus(),
                account.getAcctCurrBal(),
                account.getAcctCreditLimit(),
                account.getAcctCashCreditLimit(),
                account.getAcctOpenDate(),
                account.getAcctExpirationDate(),
                account.getAcctReissueDate(),
                account.getAcctCurrCycCredit(),
                account.getAcctCurrCycDebit(),
                account.getAcctAddrZip(),
                account.getAcctGroupId(),
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                account.getVersion());
    }

    private Map<String, Object> buildTxAuditPayload(Transaction tx,
                                                    TransactionCategoryBalance bal,
                                                    BigDecimal rate,
                                                    String batchRunId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("transactionId", tx.getTranId());
        payload.put("accountId", bal.getId().getTrancatAcctId());
        payload.put("typeCode", bal.getId().getTrancatTypeCd());
        payload.put("categoryCode", bal.getId().getTrancatCd());
        payload.put("baseBalance", bal.getTranCatBal());
        payload.put("interestRate", rate);
        payload.put("monthlyInterest", tx.getTranAmt());
        payload.put("batchRunId", batchRunId);
        return payload;
    }

    private Map<String, Object> buildRunAuditPayload(LocalDate parmDate,
                                                     int accountsProcessed,
                                                     int transactionsPosted,
                                                     BigDecimal totalInterest,
                                                     int recordCount) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("parmDate", parmDate);
        payload.put("recordsRead", recordCount);
        payload.put("accountsProcessed", accountsProcessed);
        payload.put("transactionsPosted", transactionsPosted);
        payload.put("totalInterest", totalInterest);
        return payload;
    }
}

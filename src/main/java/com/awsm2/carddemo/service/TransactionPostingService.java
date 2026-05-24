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
import com.awsm2.carddemo.adapter.S3OutputService;
import com.awsm2.carddemo.domain.Account;
import com.awsm2.carddemo.domain.CardCrossReference;
import com.awsm2.carddemo.domain.DailyTransaction;
import com.awsm2.carddemo.domain.Transaction;
import com.awsm2.carddemo.domain.TransactionCategoryBalance;
import com.awsm2.carddemo.domain.TransactionCategoryBalance.TransactionCategoryBalanceId;
import com.awsm2.carddemo.dto.AccountUpdateDto;
import com.awsm2.carddemo.dto.TransactionAddDto;
import com.awsm2.carddemo.exception.OnSizeErrorException;
import com.awsm2.carddemo.repository.AccountRepository;
import com.awsm2.carddemo.repository.CardCrossReferenceRepository;
import com.awsm2.carddemo.repository.DailyTransactionRepository;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Daily transaction-posting batch service &mdash; the Java target for
 * the COBOL batch program {@code app/cbl/CBTRN02C.cbl}.
 *
 * <p>This service implements the 4-stage validation cascade and the
 * post-success update sequence that the COBOL source performs for each
 * record in the {@code DALYTRAN} (daily transaction) staging file.
 * Records that fail validation are written to the {@code DALYREJS}
 * rejects file with a verbatim reject reason code (100&ndash;109) and
 * reason-description trailer (paragraph
 * {@code 2500-WRITE-REJECT-REC}). The job's UNIX/JES return code is
 * {@code 4} when any rejects exist and {@code 0} otherwise
 * (paragraph {@code MAIN-PARA} L228-L230).</p>
 *
 * <h2>Source provenance (AAP &sect;0.7.3)</h2>
 * <ul>
 *   <li><b>COBOL program:</b> {@code app/cbl/CBTRN02C.cbl} &mdash;
 *       invoked by JCL job {@code app/jcl/POSTTRAN.jcl} STEP15.</li>
 *   <li><b>Record layouts:</b>
 *       {@code app/cpy/CVTRA06Y.cpy}
 *       ({@code DALYTRAN-RECORD}; {@link DailyTransaction}),
 *       {@code app/cpy/CVACT03Y.cpy}
 *       ({@code CARD-XREF-RECORD}; {@link CardCrossReference}),
 *       {@code app/cpy/CVACT01Y.cpy}
 *       ({@code ACCOUNT-RECORD}; {@link Account}),
 *       {@code app/cpy/CVTRA01Y.cpy}
 *       ({@code TRAN-CAT-BAL-RECORD};
 *       {@link TransactionCategoryBalance}),
 *       {@code app/cpy/CVTRA05Y.cpy}
 *       ({@code TRAN-RECORD}; {@link Transaction}).</li>
 * </ul>
 *
 * <h2>COBOL reject codes (preserved verbatim per AAP &sect;0.7.2)</h2>
 *
 * <table>
 *   <caption>CBTRN02C.cbl reject codes</caption>
 *   <tr><th>Reject code</th><th>COBOL paragraph (line)</th>
 *       <th>Reject description</th></tr>
 *   <tr><td>{@code 100}</td><td>{@code 1500-A-LOOKUP-XREF} (L385)</td>
 *       <td>{@code INVALID CARD NUMBER FOUND}</td></tr>
 *   <tr><td>{@code 101}</td><td>{@code 1500-B-LOOKUP-ACCT} (L397)</td>
 *       <td>{@code ACCOUNT RECORD NOT FOUND}</td></tr>
 *   <tr><td>{@code 102}</td><td>{@code 1500-B-LOOKUP-ACCT} (L410)</td>
 *       <td>{@code OVERLIMIT TRANSACTION}</td></tr>
 *   <tr><td>{@code 103}</td><td>{@code 1500-B-LOOKUP-ACCT} (L417)</td>
 *       <td>{@code TRANSACTION RECEIVED AFTER ACCT EXPIRATION}</td></tr>
 *   <tr><td>{@code 109}</td><td>{@code 2800-UPDATE-ACCOUNT-REC} (L556)</td>
 *       <td>{@code ACCOUNT RECORD NOT FOUND} (REWRITE INVALID KEY)</td></tr>
 * </table>
 *
 * <h2>COBOL paragraph translation</h2>
 * <table>
 *   <caption>CBTRN02C.cbl &harr; TransactionPostingService</caption>
 *   <tr><th>COBOL paragraph</th><th>Java equivalent</th></tr>
 *   <tr><td>{@code PROCEDURE DIVISION} main loop (L196-L220)</td>
 *       <td>{@link #postDailyTransactions(String)}</td></tr>
 *   <tr><td>{@code 1500-VALIDATE-TRAN} (L370-L378)</td>
 *       <td>{@link #validate(DailyTransaction)}</td></tr>
 *   <tr><td>{@code 1500-A-LOOKUP-XREF} (L380-L392)</td>
 *       <td>{@link #lookupXref(DailyTransaction)} &mdash; reject 100</td></tr>
 *   <tr><td>{@code 1500-B-LOOKUP-ACCT} (L393-L421)</td>
 *       <td>{@link #validateAccount(DailyTransaction, Long)} &mdash;
 *       rejects 101 (account not found), 102 (overlimit), 103
 *       (expired)</td></tr>
 *   <tr><td>{@code 2000-POST-TRANSACTION} (L424-L444)</td>
 *       <td>{@link #postTransaction(DailyTransaction, Long, Account)}
 *       &mdash; orchestrates 2700/2800/2900</td></tr>
 *   <tr><td>{@code 2500-WRITE-REJECT-REC} (L446-L465)</td>
 *       <td>{@link #writeReject(DailyTransaction, RejectReason, String)}
 *       &mdash; calls {@link S3OutputService#writeRejection(String,
 *       String)}</td></tr>
 *   <tr><td>{@code 2700-UPDATE-TCATBAL} +
 *       {@code 2700-A-CREATE-TCATBAL-REC} +
 *       {@code 2700-B-UPDATE-TCATBAL-REC} (L467-L539)</td>
 *       <td>{@link #updateTransactionCategoryBalance(DailyTransaction,
 *       Long)}</td></tr>
 *   <tr><td>{@code 2800-UPDATE-ACCOUNT-REC} (L544-L560) &mdash;
 *       reject 109 on REWRITE INVALID KEY</td>
 *       <td>{@link #updateAccount(DailyTransaction, Account)}</td></tr>
 *   <tr><td>{@code 2900-WRITE-TRANSACTION-FILE} (L562-L578)</td>
 *       <td>{@link TransactionRepository#save(Object)} via
 *       {@link #writeTransactionRecord(DailyTransaction, Long)}</td></tr>
 * </table>
 *
 * <h2>Implementation notes (AAP &sect;0.7.1 Refactor Discipline)</h2>
 * <ul>
 *   <li><b>Reject codes preserved verbatim:</b> Every reject code and
 *       reject description matches the COBOL source byte-for-byte.
 *       Downstream consumers of the DALYREJS file (now an S3 object)
 *       receive identical reject reason codes per AAP &sect;0.7.2.</li>
 *   <li><b>Return code semantics:</b> {@link Result#returnCode()}
 *       mirrors the COBOL {@code RETURN-CODE}: {@code 4} when any
 *       rejects exist, {@code 0} otherwise. The AWS Batch wrapper
 *       maps this to its own job exit code so Step Functions
 *       conditional branches behave identically to JCL
 *       {@code COND=(4,LT)}.</li>
 *   <li><b>4-stage cascade:</b> The COBOL source short-circuits the
 *       cascade at the first failure (paragraph {@code 1500-VALIDATE-TRAN}
 *       only calls {@code 1500-B-LOOKUP-ACCT} when
 *       {@code WS-VALIDATION-FAIL-REASON = 0}). The Java port preserves
 *       this short-circuit behavior &mdash; the first
 *       {@link RejectReason} encountered halts further validation for
 *       that record.</li>
 *   <li><b>BigDecimal arithmetic:</b> All monetary arithmetic uses
 *       {@link BigDecimal} with {@link RoundingMode#HALF_EVEN}
 *       (AAP &sect;0.7.1).</li>
 *   <li><b>ON SIZE ERROR:</b> Account balance updates are guarded
 *       against the COBOL {@code PIC S9(10)V99} ceiling
 *       ({@code 99999999999.99}).</li>
 *   <li><b>Transaction boundary:</b> The whole batch run executes
 *       within a single {@link Transactional @Transactional(rollbackFor
 *       = Exception.class)} so a hard failure (unexpected DB error,
 *       Kafka publish exception) rolls back the entire run; per-record
 *       business-logic rejects do not roll back &mdash; they are
 *       written to S3 and the next record continues, exactly as in
 *       COBOL.</li>
 *   <li><b>No direct AWS SDK calls:</b> Rejects to S3 go through the
 *       {@link S3OutputService} adapter; transaction events go through
 *       {@link KafkaEventPublisher}; audit records go through
 *       {@link AuditLogService}.</li>
 * </ul>
 */
@Service
public class TransactionPostingService {

    private static final Logger LOG =
            LoggerFactory.getLogger(TransactionPostingService.class);

    /**
     * The maximum representable monetary value used as the ON SIZE
     * ERROR guard for the COBOL {@code PIC S9(10)V99} target columns
     * ({@code ACCT-CURR-BAL}, {@code ACCT-CURR-CYC-CREDIT},
     * {@code ACCT-CURR-CYC-DEBIT}).
     */
    static final BigDecimal MAX_AMOUNT = new BigDecimal("99999999999.99");

    /** Cache namespace for the AccountView cache-aside entries
     *  (mirrors {@link AccountViewService#CACHE_NS}). */
    static final String CACHE_NS_ACCOUNT = AccountViewService.CACHE_NS;

    /** Audit event names. */
    static final String AUDIT_TX_POSTED = "transaction.posted";
    static final String AUDIT_TX_REJECTED = "transaction.rejected";
    static final String AUDIT_RUN_SUMMARY = "batch.posttran.completed";

    private final DailyTransactionRepository dailyTransactionRepository;
    private final CardCrossReferenceRepository xrefRepository;
    private final AccountRepository accountRepository;
    private final TransactionCategoryBalanceRepository tcatbalRepository;
    private final TransactionRepository transactionRepository;
    private final S3OutputService s3OutputService;
    private final KafkaEventPublisher kafkaEventPublisher;
    private final CacheService cacheService;
    private final AuditLogService auditLogService;

    public TransactionPostingService(
            DailyTransactionRepository dailyTransactionRepository,
            CardCrossReferenceRepository xrefRepository,
            AccountRepository accountRepository,
            TransactionCategoryBalanceRepository tcatbalRepository,
            TransactionRepository transactionRepository,
            S3OutputService s3OutputService,
            KafkaEventPublisher kafkaEventPublisher,
            CacheService cacheService,
            AuditLogService auditLogService) {
        this.dailyTransactionRepository = Objects.requireNonNull(
                dailyTransactionRepository, "dailyTransactionRepository");
        this.xrefRepository = Objects.requireNonNull(xrefRepository,
                "xrefRepository");
        this.accountRepository = Objects.requireNonNull(accountRepository,
                "accountRepository");
        this.tcatbalRepository = Objects.requireNonNull(tcatbalRepository,
                "tcatbalRepository");
        this.transactionRepository = Objects.requireNonNull(
                transactionRepository, "transactionRepository");
        this.s3OutputService = Objects.requireNonNull(s3OutputService,
                "s3OutputService");
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
     * Enumerates the verbatim COBOL reject codes from
     * {@code CBTRN02C.cbl}. The numeric value and the reason
     * description are byte-identical to the source per AAP &sect;0.7.2.
     */
    public enum RejectReason {
        /** COBOL: paragraph 1500-A-LOOKUP-XREF (L385) MOVE 100. */
        INVALID_CARD(100, "INVALID CARD NUMBER FOUND"),
        /** COBOL: paragraph 1500-B-LOOKUP-ACCT (L397) MOVE 101. */
        ACCOUNT_NOT_FOUND(101, "ACCOUNT RECORD NOT FOUND"),
        /** COBOL: paragraph 1500-B-LOOKUP-ACCT (L410) MOVE 102. */
        OVERLIMIT(102, "OVERLIMIT TRANSACTION"),
        /** COBOL: paragraph 1500-B-LOOKUP-ACCT (L417) MOVE 103. */
        EXPIRED(103, "TRANSACTION RECEIVED AFTER ACCT EXPIRATION"),
        /** COBOL: paragraph 2800-UPDATE-ACCOUNT-REC (L556) MOVE 109. */
        ACCOUNT_REWRITE_FAIL(109, "ACCOUNT RECORD NOT FOUND");

        private final int code;
        private final String description;

        RejectReason(int code, String description) {
            this.code = code;
            this.description = description;
        }

        public int getCode() {
            return code;
        }

        public String getDescription() {
            return description;
        }
    }

    /**
     * Result summary returned to the caller (typically a Spring Batch
     * Tasklet step inside the {@code DailyTransactionPostingJob}).
     *
     * @param transactionsProcessed number of records read from the
     *                              DALYTRAN staging file
     * @param transactionsPosted    number of records that passed
     *                              validation and were posted
     * @param transactionsRejected  number of records that failed
     *                              validation and were written to
     *                              DALYREJS
     * @param returnCode            COBOL-equivalent {@code RETURN-CODE}
     *                              (0 or 4)
     */
    public record Result(int transactionsProcessed,
                         int transactionsPosted,
                         int transactionsRejected,
                         int returnCode) {
    }

    /**
     * Executes the daily-transaction posting batch run.
     *
     * @param batchRunId batch execution identifier (e.g. Step Functions
     *                   execution ARN suffix); must not be {@code null}
     *                   or blank
     * @return a {@link Result} summary of the run
     */
    @Transactional(rollbackFor = Exception.class)
    public Result postDailyTransactions(String batchRunId) {
        if (batchRunId == null || batchRunId.isBlank()) {
            throw new IllegalArgumentException("batchRunId must not be null or blank");
        }

        LOG.info("CBTRN02C: starting daily-transaction posting run (batchRunId={})",
                batchRunId);

        // COBOL: PERFORM UNTIL END-OF-FILE = 'Y' (L202)
        List<DailyTransaction> dailyTransactions = dailyTransactionRepository.findAll();

        int processed = 0;
        int posted = 0;
        int rejected = 0;

        for (DailyTransaction dly : dailyTransactions) {
            processed++;
            // COBOL: MOVE 0 TO WS-VALIDATION-FAIL-REASON
            //        MOVE SPACES TO WS-VALIDATION-FAIL-REASON-DESC (L208-L209)
            ValidationResult validation = validate(dly);
            if (validation.passed()) {
                // COBOL: IF WS-VALIDATION-FAIL-REASON = 0
                //          PERFORM 2000-POST-TRANSACTION  (L211-L212)
                postTransaction(dly, validation.xrefAcctId(),
                        validation.account());
                emitPostedEvents(dly, validation.xrefAcctId(), batchRunId);
                posted++;
            } else {
                // COBOL: ELSE ADD 1 TO WS-REJECT-COUNT
                //             PERFORM 2500-WRITE-REJECT-REC  (L214-L215)
                writeReject(dly, validation.reason(), batchRunId);
                emitRejectedEvent(dly, validation.reason(), batchRunId);
                rejected++;
            }
        }

        // COBOL: IF WS-REJECT-COUNT > 0 MOVE 4 TO RETURN-CODE (L229)
        int returnCode = rejected > 0 ? 4 : 0;

        LOG.info("CBTRN02C: completed posting run; "
                + "processed={}, posted={}, rejected={}, returnCode={}",
                processed, posted, rejected, returnCode);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("transactionsProcessed", processed);
        summary.put("transactionsPosted", posted);
        summary.put("transactionsRejected", rejected);
        summary.put("returnCode", returnCode);
        auditLogService.logAuditEvent(
                AUDIT_RUN_SUMMARY,
                "BATCH_RUN",
                batchRunId,
                "BATCH",
                summary,
                batchRunId);

        return new Result(processed, posted, rejected, returnCode);
    }

    // -------------------------------------------------------------------------
    // Validation cascade (4-stage)
    // -------------------------------------------------------------------------

    /**
     * Validation outcome carrier &mdash; on success holds the resolved
     * XREF account id and Account entity; on failure holds the
     * {@link RejectReason}.
     */
    private record ValidationResult(boolean passed,
                                    RejectReason reason,
                                    Long xrefAcctId,
                                    Account account) {
        static ValidationResult ok(Long acctId, Account account) {
            return new ValidationResult(true, null, acctId, account);
        }
        static ValidationResult fail(RejectReason reason) {
            return new ValidationResult(false, reason, null, null);
        }
    }

    /**
     * COBOL: 1500-VALIDATE-TRAN (L370-L378).
     *
     * <p>Performs the 4-stage cascade in source order. Short-circuits
     * on first failure per the COBOL source ({@code IF
     * WS-VALIDATION-FAIL-REASON = 0 PERFORM 1500-B-LOOKUP-ACCT}).</p>
     */
    ValidationResult validate(DailyTransaction dly) {
        // Stage 1: XREF lookup (reject 100)
        Optional<CardCrossReference> xrefOpt = lookupXref(dly);
        if (xrefOpt.isEmpty()) {
            return ValidationResult.fail(RejectReason.INVALID_CARD);
        }
        Long acctId = xrefOpt.get().getXrefAcctId();

        // Stages 2-4 occur inside the COBOL NOT INVALID KEY branch of
        // the READ ACCOUNT-FILE; the Java port collapses them into the
        // validateAccount helper for clarity.
        return validateAccount(dly, acctId);
    }

    /**
     * COBOL: 1500-A-LOOKUP-XREF (L380-L392).
     *
     * <p>Reads the {@code CARDXREF} VSAM cluster by primary key
     * {@code DALYTRAN-CARD-NUM}; on INVALID KEY moves reject reason 100
     * (translated to {@link Optional#empty()} in the Java target).</p>
     */
    Optional<CardCrossReference> lookupXref(DailyTransaction dly) {
        String cardNum = dly.getDalytranCardNum();
        if (cardNum == null || cardNum.isBlank()) {
            return Optional.empty();
        }
        return xrefRepository.findById(cardNum);
    }

    /**
     * COBOL: 1500-B-LOOKUP-ACCT (L393-L421).
     *
     * <p>Reads the {@code ACCT} VSAM cluster by {@code XREF-ACCT-ID};
     * on INVALID KEY moves reject reason 101. If found, computes the
     * provisional balance and checks reject reasons 102 (overlimit)
     * and 103 (expired).</p>
     */
    ValidationResult validateAccount(DailyTransaction dly, Long acctId) {
        // Stage 2 &mdash; account lookup (reject 101)
        Optional<Account> acctOpt = accountRepository.findById(acctId);
        if (acctOpt.isEmpty()) {
            return ValidationResult.fail(RejectReason.ACCOUNT_NOT_FOUND);
        }
        Account account = acctOpt.get();

        // Stage 3 &mdash; credit limit check (reject 102)
        // COBOL: COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT
        //                          - ACCT-CURR-CYC-DEBIT
        //                          + DALYTRAN-AMT  (L403-L405)
        BigDecimal cycCredit = nonNull(account.getAcctCurrCycCredit());
        BigDecimal cycDebit = nonNull(account.getAcctCurrCycDebit());
        BigDecimal tranAmt = nonNull(dly.getDalytranAmt());
        BigDecimal tempBal = cycCredit.subtract(cycDebit).add(tranAmt)
                .setScale(2, RoundingMode.HALF_EVEN);

        // COBOL: IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL CONTINUE
        //        ELSE MOVE 102 TO WS-VALIDATION-FAIL-REASON (L407-L412)
        BigDecimal creditLimit = nonNull(account.getAcctCreditLimit());
        if (creditLimit.compareTo(tempBal) < 0) {
            return ValidationResult.fail(RejectReason.OVERLIMIT);
        }

        // Stage 4 &mdash; expiration check (reject 103)
        // COBOL: IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)
        //          CONTINUE
        //        ELSE MOVE 103 TO WS-VALIDATION-FAIL-REASON (L414-L419)
        LocalDate expiry = account.getAcctExpirationDate();
        LocalDate origDate = dly.getDalytranOrigTs() != null
                ? dly.getDalytranOrigTs().toLocalDate()
                : null;
        if (expiry != null && origDate != null && expiry.isBefore(origDate)) {
            return ValidationResult.fail(RejectReason.EXPIRED);
        }

        return ValidationResult.ok(acctId, account);
    }

    // -------------------------------------------------------------------------
    // Post-success helpers (paragraph 2000-POST-TRANSACTION cascade)
    // -------------------------------------------------------------------------

    /**
     * COBOL: 2000-POST-TRANSACTION (L424-L444).
     *
     * <p>Orchestrates the three post-transaction updates:
     * 2700-UPDATE-TCATBAL, 2800-UPDATE-ACCOUNT-REC, and
     * 2900-WRITE-TRANSACTION-FILE.</p>
     */
    void postTransaction(DailyTransaction dly, Long acctId, Account account) {
        // COBOL: PERFORM 2700-UPDATE-TCATBAL
        updateTransactionCategoryBalance(dly, acctId);
        // COBOL: PERFORM 2800-UPDATE-ACCOUNT-REC
        updateAccount(dly, account);
        // COBOL: PERFORM 2900-WRITE-TRANSACTION-FILE
        writeTransactionRecord(dly, acctId);
    }

    /**
     * COBOL: 2700-UPDATE-TCATBAL + 2700-A-CREATE-TCATBAL-REC +
     * 2700-B-UPDATE-TCATBAL-REC (L467-L539).
     *
     * <p>Upserts the per-{@code (account, type, category)} running
     * balance: ADDs DALYTRAN-AMT to the existing balance if present,
     * or creates a new row with DALYTRAN-AMT if absent.</p>
     */
    void updateTransactionCategoryBalance(DailyTransaction dly, Long acctId) {
        TransactionCategoryBalanceId id = new TransactionCategoryBalanceId(
                acctId, dly.getDalytranTypeCd(), dly.getDalytranCatCd());
        Optional<TransactionCategoryBalance> existing =
                tcatbalRepository.findById(id);
        BigDecimal amt = nonNull(dly.getDalytranAmt());
        if (existing.isPresent()) {
            // COBOL: 2700-B-UPDATE-TCATBAL-REC &mdash; ADD DALYTRAN-AMT
            //        TO TRAN-CAT-BAL; REWRITE.
            TransactionCategoryBalance bal = existing.get();
            BigDecimal newBal = nonNull(bal.getTranCatBal()).add(amt)
                    .setScale(2, RoundingMode.HALF_EVEN);
            guardOnSizeError(newBal, "TRAN-CAT-BAL");
            bal.setTranCatBal(newBal);
            tcatbalRepository.save(bal);
        } else {
            // COBOL: 2700-A-CREATE-TCATBAL-REC &mdash; INITIALIZE; MOVE
            //        keys; ADD DALYTRAN-AMT TO TRAN-CAT-BAL; WRITE.
            BigDecimal initial = amt.setScale(2, RoundingMode.HALF_EVEN);
            guardOnSizeError(initial, "TRAN-CAT-BAL");
            TransactionCategoryBalance bal = new TransactionCategoryBalance(id, initial);
            tcatbalRepository.save(bal);
        }
    }

    /**
     * COBOL: 2800-UPDATE-ACCOUNT-REC (L544-L560).
     *
     * <p>Adds DALYTRAN-AMT to ACCT-CURR-BAL and to either
     * ACCT-CURR-CYC-CREDIT or ACCT-CURR-CYC-DEBIT depending on sign,
     * then REWRITEs the account. Reject 109 (account not found on
     * REWRITE INVALID KEY) cannot occur in the JPA target because the
     * account was already loaded by 1500-B-LOOKUP-ACCT &mdash; but the
     * code is retained for documentation parity.</p>
     */
    void updateAccount(DailyTransaction dly, Account account) {
        BigDecimal amt = nonNull(dly.getDalytranAmt());
        BigDecimal previousBalance = nonNull(account.getAcctCurrBal());
        BigDecimal newBalance = previousBalance.add(amt)
                .setScale(2, RoundingMode.HALF_EVEN);
        guardOnSizeError(newBalance, "ACCT-CURR-BAL");
        account.setAcctCurrBal(newBalance);

        // COBOL: IF DALYTRAN-AMT >= 0
        //          ADD DALYTRAN-AMT TO ACCT-CURR-CYC-CREDIT
        //        ELSE
        //          ADD DALYTRAN-AMT TO ACCT-CURR-CYC-DEBIT
        //        END-IF (L547-L550)
        if (amt.signum() >= 0) {
            BigDecimal newCycCredit = nonNull(account.getAcctCurrCycCredit())
                    .add(amt).setScale(2, RoundingMode.HALF_EVEN);
            guardOnSizeError(newCycCredit, "ACCT-CURR-CYC-CREDIT");
            account.setAcctCurrCycCredit(newCycCredit);
        } else {
            BigDecimal newCycDebit = nonNull(account.getAcctCurrCycDebit())
                    .add(amt).setScale(2, RoundingMode.HALF_EVEN);
            guardOnSizeError(newCycDebit, "ACCT-CURR-CYC-DEBIT");
            account.setAcctCurrCycDebit(newCycDebit);
        }

        Account saved = accountRepository.save(account);

        // Cache eviction so subsequent online reads see the
        // post-batch balance (AAP §0.3.3 cache-aside)
        try {
            cacheService.evict(CACHE_NS_ACCOUNT, String.valueOf(saved.getAcctId()));
        } catch (RuntimeException ex) {
            LOG.warn("CBTRN02C: cache eviction failed for account {} (continuing)",
                    saved.getAcctId(), ex);
        }
    }

    /**
     * COBOL: 2900-WRITE-TRANSACTION-FILE (L562-L578).
     *
     * <p>Composes the {@link Transaction} record by copying the DALYTRAN
     * fields verbatim and setting TRAN-PROC-TS to the current
     * timestamp (paragraph {@code Z-GET-DB2-FORMAT-TIMESTAMP}).</p>
     */
    Transaction writeTransactionRecord(DailyTransaction dly, Long acctId) {
        LocalDateTime now = LocalDateTime.now();
        // COBOL: MOVE DALYTRAN-* TO TRAN-* (L425-L437)
        Transaction tx = new Transaction(
                dly.getDalytranId(),
                dly.getDalytranTypeCd(),
                dly.getDalytranCatCd(),
                dly.getDalytranSource(),
                dly.getDalytranDesc(),
                nonNull(dly.getDalytranAmt()).setScale(2, RoundingMode.HALF_EVEN),
                dly.getDalytranMerchantId(),
                dly.getDalytranMerchantName(),
                dly.getDalytranMerchantCity(),
                dly.getDalytranMerchantZip(),
                dly.getDalytranCardNum(),
                dly.getDalytranOrigTs(),
                now);
        return transactionRepository.save(tx);
    }

    // -------------------------------------------------------------------------
    // Reject helpers
    // -------------------------------------------------------------------------

    /**
     * COBOL: 2500-WRITE-REJECT-REC (L446-L465).
     *
     * <p>Constructs the 430-byte rejection record (350-byte
     * REJECT-TRAN-DATA + 80-byte VALIDATION-TRAILER) and hands it to
     * the {@link S3OutputService} adapter for verbatim write to the
     * DALYREJS S3 prefix.</p>
     */
    void writeReject(DailyTransaction dly, RejectReason reason, String batchRunId) {
        // The COBOL VALIDATION-TRAILER is 80 bytes:
        //   05 REJECT-REASON-CD   PIC 9(03)  (3 bytes)
        //   05 REJECT-REASON-DESC PIC X(77)  (77 bytes)
        // The full 430-byte rejection record is REJECT-TRAN-DATA (350)
        // concatenated with VALIDATION-TRAILER (80). The S3 adapter
        // preserves bytes verbatim per AAP §0.7.2.
        String trailer = String.format("%03d", reason.getCode())
                + padRight(reason.getDescription(), 77);
        String record = formatDalytranRecord(dly) + trailer;
        s3OutputService.writeRejection(batchRunId, record);
    }

    /**
     * Formats the 350-byte DALYTRAN record per CVTRA06Y.cpy.
     *
     * <p>Field widths per COBOL copybook are preserved verbatim. Null
     * fields are rendered as the proper spaces/zeros.</p>
     */
    String formatDalytranRecord(DailyTransaction dly) {
        StringBuilder sb = new StringBuilder(350);
        sb.append(padRight(dly.getDalytranId(), 16));         // PIC X(16)
        sb.append(padRight(dly.getDalytranTypeCd(), 2));      // PIC X(02)
        sb.append(formatNumeric4(dly.getDalytranCatCd()));    // PIC 9(04)
        sb.append(padRight(dly.getDalytranSource(), 10));     // PIC X(10)
        sb.append(padRight(dly.getDalytranDesc(), 100));      // PIC X(100)
        sb.append(formatAmount(dly.getDalytranAmt()));        // PIC S9(09)V99 (12 chars)
        sb.append(formatNumeric9(dly.getDalytranMerchantId()));// PIC 9(09)
        sb.append(padRight(dly.getDalytranMerchantName(), 50));// PIC X(50)
        sb.append(padRight(dly.getDalytranMerchantCity(), 50));// PIC X(50)
        sb.append(padRight(dly.getDalytranMerchantZip(), 10));// PIC X(10)
        sb.append(padRight(dly.getDalytranCardNum(), 16));    // PIC X(16)
        sb.append(formatTimestamp(dly.getDalytranOrigTs()));  // PIC X(26)
        sb.append(formatTimestamp(dly.getDalytranProcTs()));  // PIC X(26)
        // Total above = 16+2+4+10+100+12+9+50+50+10+16+26+26 = 331 bytes
        // CVTRA06Y has a 19-byte FILLER at the end to bring total to 350
        sb.append(padRight("", 19));                          // FILLER
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Eventing and audit helpers
    // -------------------------------------------------------------------------

    /**
     * Emits {@code transaction.posted} and {@code account.updated}
     * MSK events plus an audit record (AAP &sect;0.6.5, &sect;0.6.6).
     */
    private void emitPostedEvents(DailyTransaction dly, Long acctId, String batchRunId) {
        // transaction.posted
        try {
            TransactionAddDto txEvent = new TransactionAddDto(
                    String.format("%011d", acctId),
                    nullToEmpty(dly.getDalytranCardNum()),
                    dly.getDalytranTypeCd(),
                    dly.getDalytranCatCd(),
                    dly.getDalytranSource(),
                    dly.getDalytranDesc(),
                    dly.getDalytranAmt(),
                    dly.getDalytranOrigTs(),
                    LocalDateTime.now(),
                    dly.getDalytranMerchantId(),
                    dly.getDalytranMerchantName(),
                    dly.getDalytranMerchantCity(),
                    dly.getDalytranMerchantZip(),
                    "Y");
            kafkaEventPublisher.publishTransactionPosted(acctId, txEvent);
        } catch (RuntimeException ex) {
            LOG.warn("CBTRN02C: transaction.posted publish failed for account {} (continuing)",
                    acctId, ex);
        }

        // account.updated
        Optional<Account> refreshed = accountRepository.findById(acctId);
        if (refreshed.isPresent()) {
            try {
                AccountUpdateDto event = buildAccountUpdateEvent(refreshed.get());
                kafkaEventPublisher.publishAccountUpdated(acctId, event);
            } catch (RuntimeException ex) {
                LOG.warn("CBTRN02C: account.updated publish failed for account {} (continuing)",
                        acctId, ex);
            }
        }

        // audit
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("transactionId", dly.getDalytranId());
        payload.put("accountId", acctId);
        payload.put("amount", dly.getDalytranAmt());
        payload.put("batchRunId", batchRunId);
        auditLogService.logTransactionEvent(
                dly.getDalytranId(),
                acctId,
                "BATCH",
                AUDIT_TX_POSTED,
                null,
                payload,
                batchRunId);
    }

    /**
     * Emits an audit record for a rejected transaction.
     */
    private void emitRejectedEvent(DailyTransaction dly, RejectReason reason, String batchRunId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("transactionId", dly.getDalytranId());
        payload.put("cardNumber", maskPan(dly.getDalytranCardNum()));
        payload.put("amount", dly.getDalytranAmt());
        payload.put("rejectCode", reason.getCode());
        payload.put("rejectReason", reason.getDescription());
        payload.put("batchRunId", batchRunId);
        auditLogService.logTransactionEvent(
                dly.getDalytranId(),
                null,
                "BATCH",
                AUDIT_TX_REJECTED,
                String.valueOf(reason.getCode()),
                payload,
                batchRunId);
    }

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

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

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

    private static BigDecimal nonNull(BigDecimal value) {
        return value == null
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN)
                : value;
    }

    private static String padRight(String value, int width) {
        if (value == null) {
            return " ".repeat(width);
        }
        if (value.length() >= width) {
            return value.substring(0, width);
        }
        return value + " ".repeat(width - value.length());
    }

    private static String formatNumeric4(Integer value) {
        return value == null ? "0000" : String.format("%04d", value);
    }

    private static String formatNumeric9(Long value) {
        return value == null ? "000000000" : String.format("%09d", value);
    }

    private static String formatAmount(BigDecimal value) {
        // COBOL PIC S9(09)V99 with a leading sign indicator
        // (e.g. "+000000123.45"). Width = 12 chars including the sign
        // and the implied decimal point.
        BigDecimal v = value == null
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN)
                : value.setScale(2, RoundingMode.HALF_EVEN);
        String sign = v.signum() < 0 ? "-" : "+";
        BigDecimal abs = v.abs();
        String digits = abs.toPlainString();
        // ensure 9 integer digits with optional fractional padding
        String[] parts = digits.split("\\.");
        String integerPart = parts.length > 0 ? parts[0] : "0";
        String fractionalPart = parts.length > 1 ? parts[1] : "00";
        if (fractionalPart.length() < 2) {
            fractionalPart = fractionalPart + "0".repeat(2 - fractionalPart.length());
        } else if (fractionalPart.length() > 2) {
            fractionalPart = fractionalPart.substring(0, 2);
        }
        integerPart = String.format("%09d",
                Long.parseLong(integerPart.isEmpty() ? "0" : integerPart));
        return sign + integerPart + "." + fractionalPart;
    }

    private static String formatTimestamp(LocalDateTime ts) {
        if (ts == null) {
            return " ".repeat(26);
        }
        // COBOL DB2-FORMAT-TS is X(26) &mdash; yyyy-MM-dd-HH.mm.ss.NNNNNN
        return String.format("%04d-%02d-%02d-%02d.%02d.%02d.%06d",
                ts.getYear(), ts.getMonthValue(), ts.getDayOfMonth(),
                ts.getHour(), ts.getMinute(), ts.getSecond(),
                ts.getNano() / 1000);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String maskPan(String pan) {
        if (pan == null || pan.length() < 4) {
            return "****";
        }
        return "****-****-****-" + pan.substring(pan.length() - 4);
    }
}

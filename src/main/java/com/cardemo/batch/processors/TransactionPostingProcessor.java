package com.cardemo.batch.processors;

import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.DailyTransaction;
import com.cardemo.model.entity.Transaction;
import com.cardemo.model.entity.TransactionCategoryBalance;
import com.cardemo.model.enums.RejectCode;
import com.cardemo.model.key.TransactionCategoryBalanceId;
import com.cardemo.observability.MetricsConfig;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.TransactionCategoryBalanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Daily Transaction Posting Processor — implements the 4-stage validation cascade
 * from COBOL program {@code CBTRN02C.cbl} (731 lines) in the CardDemo mainframe
 * application (commit {@code 27d6c6f}).
 *
 * <p>This is the <strong>most complex processor</strong> in the batch pipeline. It
 * validates each daily transaction through four sequential stages, then posts valid
 * transactions by updating the transaction category balance (TCATBAL) and the
 * account balance.</p>
 *
 * <h3>4-Stage Validation Cascade</h3>
 * <ol>
 *   <li><strong>Stage 1 — XREF Lookup</strong> (1500-A-LOOKUP-XREF): Resolves
 *       the card number to an account ID via the card cross-reference dataset.
 *       Reject code 100 if card number not found.</li>
 *   <li><strong>Stage 2 — Account Lookup</strong> (1500-B-LOOKUP-ACCT): Reads
 *       the account record using the account ID from the cross-reference.
 *       Reject code 101 if account not found.</li>
 *   <li><strong>Stage 3 — Credit Limit Check</strong>: Computes
 *       {@code cycleCredit - cycleDebit + transactionAmount} and compares with
 *       the account credit limit. Reject code 102 if over limit.</li>
 *   <li><strong>Stage 4 — Expiry Check</strong>: Compares the account expiration
 *       date with the transaction origination date. Reject code 103 if account
 *       expired before the transaction date.</li>
 * </ol>
 *
 * <h3>Post-Validation Processing</h3>
 * <p>After all 4 stages pass:</p>
 * <ol>
 *   <li>Build a {@link Transaction} entity from the {@link DailyTransaction} fields
 *       (2000-POST-TRANSACTION)</li>
 *   <li>Update the transaction category balance — create-or-update pattern
 *       (2700-UPDATE-TCATBAL)</li>
 *   <li>Update the account balance — classify to cycle credit or debit, update
 *       current balance (2800-UPDATE-ACCOUNT-REC)</li>
 * </ol>
 *
 * <h3>Spring Batch Integration</h3>
 * <p>Implements {@code ItemProcessor<DailyTransaction, Transaction>}. Returns
 * {@code null} for rejected items (Spring Batch convention for filtering).
 * Rejection details are stored in an internal list accessible via
 * {@link #getRejections()} for the {@code RejectWriter} to consume.</p>
 *
 * <p><strong>IMPORTANT: chunk-size=1 requirement.</strong> This processor performs
 * direct database writes (account balance updates, TCATBAL updates) within the
 * {@link #process(DailyTransaction)} method, which is atypical for a Spring Batch
 * {@code ItemProcessor}. Normally, processors only transform items and writers
 * perform I/O. However, this design preserves the CBTRN02C record-by-record
 * processing model where each transaction's validation and posting is a single
 * atomic operation. The step MUST be configured with {@code chunk(1)} to ensure
 * each transaction is fully processed and committed before the next one begins.
 * Using larger chunk sizes would cause validation state to leak between items
 * and could lead to incorrect balance updates.</p>
 *
 * <p>This processor uses mutable instance fields ({@code goodTranCount},
 * {@code badTranCount}, {@code rejections}) that mirror COBOL working storage.
 * The COBOL CBTRN02C runs single-threaded; concurrent execution of this
 * processor is not supported. The {@code @StepScope} annotation or single-step
 * configuration should be used to ensure isolation.</p>
 *
 * <h3>COBOL Source Reference</h3>
 * <ul>
 *   <li>{@code app/cbl/CBTRN02C.cbl} — Daily Transaction Posting Engine</li>
 *   <li>{@code app/jcl/POSTTRAN.jcl} — JCL job executing CBTRN02C</li>
 * </ul>
 *
 * @see DailyTransaction
 * @see Transaction
 * @see RejectCode
 */
@Component
@StepScope
public class TransactionPostingProcessor implements ItemProcessor<DailyTransaction, Transaction> {

    private static final Logger log = LoggerFactory.getLogger(TransactionPostingProcessor.class);

    private final CardCrossReferenceRepository cardCrossReferenceRepository;
    private final AccountRepository accountRepository;
    private final TransactionCategoryBalanceRepository transactionCategoryBalanceRepository;

    /**
     * Metrics configuration for recording batch processing counters.
     * Per AAP §0.7.1: {@code carddemo.batch.records.processed} (counter, per job)
     * and {@code carddemo.batch.records.rejected} (counter, with reason tag).
     */
    private final MetricsConfig metricsConfig;

    /**
     * Counter for successfully posted transactions.
     * Maps to COBOL {@code WS-GOOD-TRAN-COUNT PIC 9(09)}.
     */
    private int goodTranCount;

    /**
     * Counter for rejected transactions.
     * Maps to COBOL {@code WS-BAD-TRAN-COUNT PIC 9(09)}.
     */
    private int badTranCount;

    /**
     * Accumulated rejection details for the {@code RejectWriter} to consume.
     * Each entry corresponds to a daily transaction that failed one of the
     * 4 validation stages or the account update rewrite (code 109).
     */
    private final List<RejectionResult> rejections = new ArrayList<>();

    /**
     * Constructs a {@code TransactionPostingProcessor} with the required
     * Spring Data JPA repositories and observability dependencies injected
     * via constructor injection.
     *
     * @param cardCrossReferenceRepository repository for XREF lookups
     *        (Stage 1 — card number to account ID resolution)
     * @param accountRepository repository for account lookups and balance
     *        updates (Stages 2-4 and account balance update)
     * @param transactionCategoryBalanceRepository repository for TCATBAL
     *        create-or-update operations during transaction posting
     * @param metricsConfig observability metrics configuration for recording
     *        batch processed/rejected counters; must not be {@code null}
     */
    public TransactionPostingProcessor(
            CardCrossReferenceRepository cardCrossReferenceRepository,
            AccountRepository accountRepository,
            TransactionCategoryBalanceRepository transactionCategoryBalanceRepository,
            MetricsConfig metricsConfig) {
        this.cardCrossReferenceRepository = cardCrossReferenceRepository;
        this.accountRepository = accountRepository;
        this.transactionCategoryBalanceRepository = transactionCategoryBalanceRepository;
        this.metricsConfig = metricsConfig;
        this.goodTranCount = 0;
        this.badTranCount = 0;
    }

    /**
     * Processes a single daily transaction through the 4-stage validation cascade,
     * then posts it if all stages pass.
     *
     * <p>Maps to COBOL paragraphs:</p>
     * <ul>
     *   <li>{@code 1000-DALYTRAN-GET-NEXT} — loop body</li>
     *   <li>{@code 1500-VALIDATE-TRAN} — 4-stage validation cascade</li>
     *   <li>{@code 2000-POST-TRANSACTION} — transaction building and posting</li>
     * </ul>
     *
     * @param item the daily transaction staging record to validate and post
     * @return a fully constructed {@link Transaction} entity if all validation
     *         stages pass and account update succeeds; {@code null} if the
     *         transaction is rejected (Spring Batch convention for filtering)
     * @throws Exception if an unexpected error occurs during processing
     */
    @Override
    public Transaction process(DailyTransaction item) throws Exception {
        log.debug("Processing daily transaction: tranId={}, cardNum={}, amount={}",
                item.getDalytranId(), item.getDalytranCardNum(), item.getDalytranAmt());

        // ---------------------------------------------------------------
        // Stage 1 — XREF Lookup (1500-A-LOOKUP-XREF, lines 380-392)
        // Resolve card number to account ID via cross-reference dataset.
        // COBOL: READ XREF-FILE INTO CARD-XREF-RECORD; INVALID KEY → code 100
        // ---------------------------------------------------------------
        Optional<CardCrossReference> xrefOpt =
                cardCrossReferenceRepository.findById(item.getDalytranCardNum());
        if (xrefOpt.isEmpty()) {
            rejectTransaction(item, RejectCode.XREF_NOT_FOUND);
            return null;
        }

        CardCrossReference xref = xrefOpt.get();
        String acctId = xref.getXrefAcctId();

        // ---------------------------------------------------------------
        // Stage 2 — Account Lookup (1500-B-LOOKUP-ACCT, lines 393-402)
        // Read account record using account ID from cross-reference.
        // COBOL: READ ACCOUNT-FILE INTO ACCOUNT-RECORD; INVALID KEY → code 101
        // ---------------------------------------------------------------
        Optional<Account> acctOpt = accountRepository.findById(acctId);
        if (acctOpt.isEmpty()) {
            rejectTransaction(item, RejectCode.ACCOUNT_NOT_FOUND);
            return null;
        }

        Account account = acctOpt.get();

        // ---------------------------------------------------------------
        // Stage 3 — Credit Limit Check (lines 403-413)
        // COBOL: COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT
        //            - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT
        //        IF ACCT-CREDIT-LIMIT < WS-TEMP-BAL → code 102
        // CRITICAL: All computations use BigDecimal (WS-TEMP-BAL is
        //           PIC S9(09)V99 COMP-3). Use compareTo(), never equals().
        // ---------------------------------------------------------------
        BigDecimal tempBal = account.getAcctCurrCycCredit()
                .subtract(account.getAcctCurrCycDebit())
                .add(item.getDalytranAmt());
        if (account.getAcctCreditLimit().compareTo(tempBal) < 0) {
            rejectTransaction(item, RejectCode.CREDIT_LIMIT_EXCEEDED);
            return null;
        }

        // ---------------------------------------------------------------
        // Stage 4 — Expiry Check (lines 414-420)
        // COBOL: IF ACCT-EXPIRAION-DATE < DALYTRAN-ORIG-TS(1:10) → code 103
        // Note: "EXPIRAION" is the original COBOL field name (typo preserved
        //       in source). Java entity uses corrected name acctExpDate.
        // Comparison: account expired if expiration date is BEFORE
        //             the transaction origination date.
        // ---------------------------------------------------------------
        LocalDate tranDate = item.getDalytranOrigTs().toLocalDate();
        if (account.getAcctExpDate().isBefore(tranDate)) {
            rejectTransaction(item, RejectCode.CARD_EXPIRED);
            return null;
        }

        // ---------------------------------------------------------------
        // All 4 validation stages passed — proceed to post transaction
        // ---------------------------------------------------------------

        // Build Transaction entity from DailyTransaction fields (2000-POST-TRANSACTION)
        Transaction transaction = buildTransaction(item);

        // Update transaction category balance — create-or-update (2700-UPDATE-TCATBAL)
        updateTcatbal(acctId, item.getDalytranTypeCd(),
                item.getDalytranCatCd(), item.getDalytranAmt());

        // Update account balance — cycle credit/debit + current balance (2800-UPDATE-ACCOUNT-REC)
        boolean accountUpdated = updateAccount(account, item);
        if (!accountUpdated) {
            // Account rewrite failed (code 109) — transaction is rejected
            // Note: TCATBAL was already updated, matching COBOL behavior where
            // TCATBAL REWRITE occurs before ACCOUNT REWRITE in paragraph sequence
            return null;
        }

        goodTranCount++;
        // Record successful batch processing metric
        // Per AAP §0.7.1: carddemo.batch.records.processed counter tagged per job
        metricsConfig.recordBatchProcessed("DailyTransactionPosting");
        log.info("Transaction posted successfully: tranId={}, cardNum={}, acctId={}, amount={}",
                item.getDalytranId(), item.getDalytranCardNum(), acctId, item.getDalytranAmt());
        return transaction;
    }

    /**
     * Builds a {@link Transaction} entity by mapping all fields from the
     * {@link DailyTransaction} staging record.
     *
     * <p>Maps to COBOL paragraph {@code 2000-POST-TRANSACTION} (lines 424-465)
     * where each DALYTRAN field is MOVEd to the corresponding TRAN field.
     * The processing timestamp is set to the current date/time, replacing
     * the COBOL {@code Z-GET-DB2-FORMAT-TIMESTAMP} utility subroutine.</p>
     *
     * @param item the daily transaction staging record to map
     * @return a fully populated {@link Transaction} entity ready for persistence
     */
    private Transaction buildTransaction(DailyTransaction item) {
        Transaction tran = new Transaction();
        tran.setTranId(item.getDalytranId());
        tran.setTranTypeCd(item.getDalytranTypeCd());
        tran.setTranCatCd(item.getDalytranCatCd());
        tran.setTranSource(item.getDalytranSource());
        tran.setTranDesc(item.getDalytranDesc());
        tran.setTranAmt(item.getDalytranAmt());
        tran.setTranMerchantId(item.getDalytranMerchantId());
        tran.setTranMerchantName(item.getDalytranMerchantName());
        tran.setTranMerchantCity(item.getDalytranMerchantCity());
        tran.setTranMerchantZip(item.getDalytranMerchantZip());
        tran.setTranCardNum(item.getDalytranCardNum());
        tran.setTranOrigTs(item.getDalytranOrigTs());
        // Processing timestamp replaces Z-GET-DB2-FORMAT-TIMESTAMP
        tran.setTranProcTs(LocalDateTime.now());
        return tran;
    }

    /**
     * Updates the transaction category balance using a create-or-update pattern.
     *
     * <p>Maps to COBOL paragraph {@code 2700-UPDATE-TCATBAL} (lines 467-548):</p>
     * <ul>
     *   <li>If TCATBAL record exists (FILE STATUS '00' → 2700-B-UPDATE-TCATBAL-REC):
     *       ADD transaction amount to existing balance, REWRITE.</li>
     *   <li>If TCATBAL record NOT found (FILE STATUS '23' → 2700-A-CREATE-TCATBAL-REC):
     *       INITIALIZE new record, set key fields and amount, WRITE.</li>
     * </ul>
     *
     * @param acctId   the 11-character account identifier from the cross-reference
     * @param typeCode the 2-character transaction type code
     * @param catCode  the transaction category code (Short, matching SMALLINT DDL)
     * @param amount   the transaction amount to add to the balance (BigDecimal)
     */
    private void updateTcatbal(String acctId, String typeCode, Short catCode, BigDecimal amount) {
        TransactionCategoryBalanceId key = new TransactionCategoryBalanceId(acctId, typeCode, catCode);
        Optional<TransactionCategoryBalance> tcatbalOpt =
                transactionCategoryBalanceRepository.findById(key);

        if (tcatbalOpt.isPresent()) {
            // FILE STATUS '00' — record exists → update balance (2700-B-UPDATE-TCATBAL-REC)
            TransactionCategoryBalance tcatbal = tcatbalOpt.get();
            TransactionCategoryBalanceId existingKey = tcatbal.getId();
            tcatbal.setTranCatBal(tcatbal.getTranCatBal().add(amount));
            transactionCategoryBalanceRepository.save(tcatbal);
            log.debug("TCATBAL updated: key={}, acctId={}, typeCode={}, catCode={}, newBalance={}",
                    existingKey, acctId, typeCode, catCode, tcatbal.getTranCatBal());
        } else {
            // FILE STATUS '23' — not found → create new record (2700-A-CREATE-TCATBAL-REC)
            TransactionCategoryBalance newTcatbal = new TransactionCategoryBalance();
            newTcatbal.setId(key);
            newTcatbal.setTranCatBal(amount);
            transactionCategoryBalanceRepository.save(newTcatbal);
            log.debug("TCATBAL created: acctId={}, typeCode={}, catCode={}, balance={}",
                    acctId, typeCode, catCode, amount);
        }
    }

    /**
     * Updates the account balance after a transaction passes all validation stages.
     *
     * <p>Maps to COBOL paragraph {@code 2800-UPDATE-ACCOUNT-REC} (lines 550-580).
     * The COBOL execution order is preserved exactly:</p>
     * <ol>
     *   <li>ADD transaction amount to {@code ACCT-CURR-BAL} (current balance)</li>
     *   <li>IF amount {@code >= 0}: ADD to {@code ACCT-CURR-CYC-CREDIT}
     *       (positive and zero go to cycle credit)</li>
     *   <li>ELSE: ADD to {@code ACCT-CURR-CYC-DEBIT}
     *       (negative amounts go to cycle debit — COBOL ADD with negative
     *       value effectively reduces the debit running total)</li>
     *   <li>REWRITE account record; INVALID KEY → reject code 109</li>
     * </ol>
     *
     * @param account the account entity to update (already read in Stage 2)
     * @param item    the daily transaction providing the amount and context
     *                for rejection recording on failure
     * @return {@code true} if account was saved successfully;
     *         {@code false} if save failed (reject code 109 recorded)
     */
    private boolean updateAccount(Account account, DailyTransaction item) {
        BigDecimal amount = item.getDalytranAmt();

        // Step 1: ADD DALYTRAN-AMT TO ACCT-CURR-BAL (COBOL does balance first)
        account.setAcctCurrBal(account.getAcctCurrBal().add(amount));

        // Step 2: Classify to cycle credit or debit
        // COBOL: IF DALYTRAN-AMT >= 0 → ADD TO ACCT-CURR-CYC-CREDIT
        //        ELSE → ADD TO ACCT-CURR-CYC-DEBIT
        // Note: >= 0 means zero amounts go to cycle credit (COBOL behavior)
        if (amount.compareTo(BigDecimal.ZERO) >= 0) {
            account.setAcctCurrCycCredit(account.getAcctCurrCycCredit().add(amount));
        } else {
            account.setAcctCurrCycDebit(account.getAcctCurrCycDebit().add(amount));
        }

        // Step 3: Persist (REWRITE) — INVALID KEY maps to exception → code 109
        try {
            accountRepository.save(account);
            log.debug("Account updated: acctId={}, newBalance={}, cycCredit={}, cycDebit={}",
                    account.getAcctId(), account.getAcctCurrBal(),
                    account.getAcctCurrCycCredit(), account.getAcctCurrCycDebit());
            return true;
        } catch (Exception e) {
            // COBOL: REWRITE FD-ACCTFILE-REC ... INVALID KEY
            //        MOVE 0109 TO WS-VALIDATION-FAIL-CODE
            //        MOVE 'ACCOUNT RECORD NOT FOUND' TO WS-VALIDATION-FAIL-REASON-DESC
            log.error("Account rewrite failed: acctId={}, tranId={}, error={}",
                    account.getAcctId(), item.getDalytranId(), e.getMessage(), e);
            rejectTransaction(item, RejectCode.ACCOUNT_REWRITE_ERROR);
            return false;
        }
    }

    /**
     * Records a transaction rejection with the given reject code.
     *
     * <p>Maps to COBOL paragraph {@code 3000-WRITE-REJECT} (lines 600-650):
     * writes the daily transaction record and rejection reason to the
     * DALYREJS output files and increments {@code WS-BAD-TRAN-COUNT}.</p>
     *
     * <p>In the Spring Batch architecture, rejection details are accumulated
     * in the internal {@link #rejections} list, which the {@code RejectWriter}
     * accesses via {@link #getRejections()} to write S3 rejection files.</p>
     *
     * @param item       the daily transaction being rejected
     * @param rejectCode the enum constant identifying the rejection reason
     */
    private void rejectTransaction(DailyTransaction item, RejectCode rejectCode) {
        rejections.add(new RejectionResult(item, rejectCode, rejectCode.getDescription()));
        badTranCount++;
        // Record batch rejection metric with reject code as reason tag
        // Per AAP §0.7.1: carddemo.batch.records.rejected counter with reason tag
        metricsConfig.recordBatchRejected("DailyTransactionPosting",
                String.valueOf(rejectCode.getCode()));
        log.warn("Transaction rejected: tranId={}, cardNum={}, rejectCode={} ({}), reason={}",
                item.getDalytranId(), item.getDalytranCardNum(),
                rejectCode.getCode(), rejectCode.name(), rejectCode.getDescription());
    }

    /**
     * Returns the count of successfully posted transactions.
     * Maps to COBOL {@code WS-GOOD-TRAN-COUNT PIC 9(09)}.
     *
     * @return the number of transactions that passed all 4 validation stages
     *         and were posted successfully
     */
    public int getGoodTranCount() {
        return goodTranCount;
    }

    /**
     * Returns the count of rejected transactions.
     * Maps to COBOL {@code WS-BAD-TRAN-COUNT PIC 9(09)}.
     * Note: In COBOL, any rejection sets {@code RETURN-CODE = 4} (partial
     * failure). This maps to Spring Batch {@code ExitStatus} at the job
     * level, not within this processor.
     *
     * @return the number of transactions that were rejected by the validation
     *         cascade or by the account update failure
     */
    public int getBadTranCount() {
        return badTranCount;
    }

    /**
     * Returns an immutable copy of the accumulated rejection details.
     *
     * <p>Each {@link RejectionResult} contains the original daily transaction,
     * the reject code, and the reason description. The {@code RejectWriter}
     * consumes this list to produce the DALYREJS and DALYREJS-REASON output
     * files (mapped to S3 rejection files).</p>
     *
     * @return an unmodifiable list of all rejection results accumulated
     *         during this processor's lifecycle
     */
    public List<RejectionResult> getRejections() {
        return List.copyOf(rejections);
    }

    /**
     * Resets all mutable instance state to initial values, enabling the singleton
     * processor to be reused across multiple job executions within the same Spring
     * application context (e.g., integration tests or re-runnable batch jobs).
     *
     * <p>Mirrors the COBOL working storage initialization that occurs at program
     * load time (CBTRN02C.cbl lines 185-186):
     * <pre>
     * 05 WS-TRANSACTION-COUNT PIC 9(09) VALUE 0.
     * 05 WS-REJECT-COUNT      PIC 9(09) VALUE 0.
     * </pre>
     */
    public void resetState() {
        goodTranCount = 0;
        badTranCount = 0;
        rejections.clear();
    }

    /**
     * Immutable record carrying rejection details for a single daily transaction
     * that failed the validation cascade or account update.
     *
     * <p>Maps to the COBOL rejection output records written to DALYREJS-FILE
     * and DALYREJS-REASON-FILE. The rejection record contains:</p>
     * <ul>
     *   <li>The original daily transaction staging record (written as-is to
     *       DALYREJS-FILE in COBOL)</li>
     *   <li>The numeric reject code from {@link RejectCode} (WS-VALIDATION-FAIL-CODE
     *       PIC 9(04))</li>
     *   <li>The human-readable rejection description (WS-VALIDATION-FAIL-REASON-DESC
     *       PIC X(76))</li>
     * </ul>
     *
     * <p>This record is consumed by the {@code RejectWriter} to produce
     * S3 rejection output files with both the original transaction data
     * and the reason trailers.</p>
     *
     * @param originalTransaction the daily transaction staging record that was rejected
     * @param rejectCode          the enum constant identifying the rejection reason
     * @param reasonDescription   the human-readable description of the rejection
     */
    public record RejectionResult(
            DailyTransaction originalTransaction,
            RejectCode rejectCode,
            String reasonDescription) {
    }
}

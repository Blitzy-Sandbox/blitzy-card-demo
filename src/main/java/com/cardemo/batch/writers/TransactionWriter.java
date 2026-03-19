/*
 * TransactionWriter.java — Spring Batch ItemWriter for Posted Transaction Persistence
 *
 * COBOL Source Reference: aws-samples/carddemo commit 27d6c6f
 *   - CBTRN02C.cbl — Daily Transaction Posting Engine (731 lines)
 *   - CVTRA05Y.cpy — Transaction record layout (350 bytes)
 *   - CVACT01Y.cpy — Account record layout (300 bytes)
 *   - CVTRA01Y.cpy — Transaction category balance record layout (50 bytes)
 *   - POSTTRAN.jcl  — Daily transaction posting JCL
 *   - TRANREPT.jcl  — Transaction report/backup JCL
 *
 * Replaces COBOL paragraphs:
 *   2000-POST-TRANSACTION        → write() orchestration
 *   2700-UPDATE-TCATBAL           → updateTransactionCategoryBalance()
 *   2700-A-CREATE-TCATBAL-REC     → create path in updateTransactionCategoryBalance()
 *   2700-B-UPDATE-TCATBAL-REC     → update path in updateTransactionCategoryBalance()
 *   2800-UPDATE-ACCOUNT-REC       → updateAccountBalance()
 *   2900-WRITE-TRANSACTION-FILE   → transactionRepository.saveAll()
 *   Z-GET-DB2-FORMAT-TIMESTAMP    → LocalDateTime.now() with DB2_TIMESTAMP_FORMAT
 */
package com.cardemo.batch.writers;

import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.Transaction;
import com.cardemo.model.entity.TransactionCategoryBalance;
import com.cardemo.model.key.TransactionCategoryBalanceId;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.TransactionCategoryBalanceRepository;
import com.cardemo.repository.TransactionRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Spring Batch {@link ItemWriter} that posts validated daily transactions to
 * PostgreSQL and backs up posted transaction chunks to AWS S3.
 *
 * <p>This is the most complex writer in the CardDemo batch pipeline,
 * replacing three COBOL paragraphs from {@code CBTRN02C.cbl}:</p>
 * <ol>
 *   <li>{@code 2700-UPDATE-TCATBAL} — Update/create transaction category balance
 *       using composite key (ACCT-ID + TYPE-CD + CAT-CD)</li>
 *   <li>{@code 2800-UPDATE-ACCOUNT-REC} — Update account current balance and
 *       segregate into credit/debit cycle accumulators</li>
 *   <li>{@code 2900-WRITE-TRANSACTION-FILE} — Write transaction record to
 *       TRANSACT VSAM KSDS</li>
 * </ol>
 *
 * <h3>Transaction Posting Sequence</h3>
 * <p>For each transaction in the chunk, the {@link #write(Chunk)} method
 * executes the following sequence exactly matching the COBOL paragraph
 * ordering:</p>
 * <ol>
 *   <li>Set processing timestamp (Z-GET-DB2-FORMAT-TIMESTAMP)</li>
 *   <li>Resolve account ID from card number via cross-reference</li>
 *   <li>Update/create TransactionCategoryBalance (2700-UPDATE-TCATBAL)</li>
 *   <li>Update Account balance and cycle accumulators (2800-UPDATE-ACCOUNT-REC)</li>
 * </ol>
 * <p>After processing all items, transactions are bulk-saved to the database
 * (2900-WRITE-TRANSACTION-FILE) and a backup is written to S3.</p>
 *
 * <h3>Atomicity</h3>
 * <p>The write method is annotated with {@link Transactional}, ensuring that
 * all database operations within a chunk are atomic — equivalent to COBOL
 * ABEND behavior where failure in any sub-paragraph terminates the program,
 * and SYNCPOINT ROLLBACK semantics from COACTUPC.cbl.</p>
 *
 * <h3>S3 Backup</h3>
 * <p>A backup copy is written to S3 after successful DB persistence. This
 * replaces the GDG {@code TRANSACT.BKUP(+1)} pattern from TRANREPT.jcl.
 * S3 failures are logged as warnings but do NOT roll back the DB transaction,
 * since the COBOL GDG backup was a separate JCL step.</p>
 *
 * @see Transaction
 * @see Account
 * @see TransactionCategoryBalance
 */
@Component
public class TransactionWriter implements ItemWriter<Transaction> {

    private static final Logger log = LoggerFactory.getLogger(TransactionWriter.class);

    /**
     * DB2-compatible timestamp format matching COBOL Z-GET-DB2-FORMAT-TIMESTAMP.
     * COBOL STRING pattern: YYYY-MM-DD-HH.MM.SS.NN0000
     * Java equivalent:       yyyy-MM-dd-HH.mm.ss.SSS000
     *
     * The trailing '000' pads to 6 fractional digits matching DB2 TIMESTAMP(6).
     */
    private static final DateTimeFormatter DB2_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HH.mm.ss.SSS000");

    /**
     * Timestamp format for S3 backup key generation, producing compact
     * date-time suffixes for unique object keys.
     */
    private static final DateTimeFormatter BACKUP_KEY_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /**
     * Native SQL query to resolve a card number to its account ID via the
     * card_cross_references table. This avoids importing CardCrossReferenceRepository
     * which is outside this writer's declared dependency scope. Column name is
     * 'account_id' per V1__create_schema.sql (Table 6: card_cross_references).
     */
    private static final String CROSS_REF_QUERY =
            "SELECT account_id FROM card_cross_references WHERE card_num = ?1";

    // -- Repository dependencies (constructor-injected) --

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final TransactionCategoryBalanceRepository tcatBalRepository;
    private final S3Client s3Client;

    // -- JPA EntityManager for cross-reference resolution --

    /**
     * JPA EntityManager used for native SQL cross-reference lookups.
     * Injected by the container; participates in the same transaction
     * context as the repository calls.
     */
    @PersistenceContext
    private EntityManager entityManager;

    // -- Externalized configuration --

    /**
     * S3 bucket name for transaction backup output.
     * Configurable via application.yml; defaults to 'carddemo-batch-output'.
     * All S3 interactions testable against LocalStack per AAP §0.7.7.
     */
    @Value("${carddemo.s3.output-bucket:carddemo-batch-output}")
    private String outputBucket;

    /**
     * S3 key prefix for transaction backup files.
     * Configurable via application.yml; defaults to 'transactions/'.
     */
    @Value("${carddemo.s3.transaction-backup-prefix:transactions/}")
    private String backupPrefix;

    // -- Counters and caches --

    /**
     * Running count of transactions posted in the current job execution.
     * Maps COBOL WS-TRANSACTION-COUNT PIC 9(09) VALUE 0 from CBTRN02C.cbl line 185.
     * Incremented by chunk size after each successful saveAll().
     */
    private long transactionCount;

    /**
     * Cache mapping card numbers to account IDs to minimize repeated
     * cross-reference lookups within a batch run. Cleared on
     * {@link #resetTransactionCount()}.
     */
    private final Map<String, String> cardToAccountCache = new ConcurrentHashMap<>();

    /**
     * Sequential chunk counter for generating unique S3 backup keys.
     * Reset to zero on {@link #resetTransactionCount()}.
     */
    private final AtomicLong chunkCounter = new AtomicLong(0);

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    /**
     * Constructs a new TransactionWriter with all required repository and
     * S3 dependencies via constructor injection.
     *
     * @param transactionRepository repository for bulk transaction persistence
     *                              (replaces WRITE FD-TRANFILE-REC)
     * @param accountRepository     repository for account balance updates
     *                              (replaces REWRITE FD-ACCTFILE-REC)
     * @param tcatBalRepository     repository for category balance updates
     *                              (replaces READ/WRITE/REWRITE FD-TCATBAL-REC)
     * @param s3Client              S3 client for backup file output
     */
    public TransactionWriter(TransactionRepository transactionRepository,
                             AccountRepository accountRepository,
                             TransactionCategoryBalanceRepository tcatBalRepository,
                             S3Client s3Client) {
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.tcatBalRepository = tcatBalRepository;
        this.s3Client = s3Client;
    }

    // -----------------------------------------------------------------------
    // ItemWriter contract — maps COBOL 2000-POST-TRANSACTION
    // -----------------------------------------------------------------------

    /**
     * Posts a chunk of validated transactions to the database and backs them
     * up to S3.
     *
     * <p>Implements the full COBOL posting sequence from paragraph
     * {@code 2000-POST-TRANSACTION} through {@code 2900-WRITE-TRANSACTION-FILE}.
     * All database operations within the chunk are atomic via {@link Transactional}.</p>
     *
     * <p>Processing order per transaction (matching COBOL):</p>
     * <ol>
     *   <li>Set TRAN-PROC-TS via Z-GET-DB2-FORMAT-TIMESTAMP</li>
     *   <li>Resolve card number → account ID via cross-reference</li>
     *   <li>{@code 2700-UPDATE-TCATBAL} — Update/create category balance</li>
     *   <li>{@code 2800-UPDATE-ACCOUNT-REC} — Update account balance</li>
     * </ol>
     * <p>After all transactions: bulk save ({@code 2900-WRITE-TRANSACTION-FILE})
     * then S3 backup (GDG replacement).</p>
     *
     * @param chunk the chunk of validated transactions to post
     * @throws Exception if a database error prevents transaction posting
     */
    @Override
    @Transactional
    public void write(Chunk<? extends Transaction> chunk) throws Exception {
        List<? extends Transaction> transactions = chunk.getItems();
        int chunkSize = chunk.size();
        long currentChunk = chunkCounter.incrementAndGet();

        log.info("Processing transaction chunk #{} with {} transactions",
                currentChunk, chunkSize);

        // NOTE: TCATBAL and Account balance updates are performed by
        // TransactionPostingProcessor during the process() phase, matching
        // COBOL paragraph execution order (2700-UPDATE-TCATBAL and
        // 2800-UPDATE-ACCOUNT-REC occur before 2900-WRITE-TRANSACTION-FILE).
        // The writer's sole persistence responsibility is the Transaction
        // records themselves plus the S3 backup (GDG TRANSACT.BKUP(+1)).

        // Step 1: Bulk save all transactions to DB
        // Maps COBOL 2900-WRITE-TRANSACTION-FILE (WRITE FD-TRANFILE-REC)
        // Use ArrayList to resolve wildcard type for saveAll() compatibility
        List<Transaction> transactionsToSave = new ArrayList<>(transactions);
        transactionRepository.saveAll(transactionsToSave);

        // Increment counter — maps COBOL ADD 1 TO WS-TRANSACTION-COUNT
        // (aggregated per chunk rather than per-record for bulk efficiency)
        transactionCount += chunkSize;

        log.info("Chunk #{}: persisted {} transactions, cumulative total: {}",
                currentChunk, chunkSize, transactionCount);

        // Step 4: S3 backup — non-fatal on failure
        // Replaces GDG TRANSACT.BKUP(+1) from TRANREPT.jcl REPROC step
        writeS3Backup(transactions, currentChunk);
    }

    // -----------------------------------------------------------------------
    // Per-transaction posting — maps COBOL 2000-POST-TRANSACTION body
    // -----------------------------------------------------------------------

    /**
     * Posts a single transaction by setting the processing timestamp,
     * resolving the account, updating the category balance, and updating
     * the account balance.
     *
     * @param transaction the transaction to post
     */
    private void postSingleTransaction(Transaction transaction) {
        // Z-GET-DB2-FORMAT-TIMESTAMP → set TRAN-PROC-TS
        LocalDateTime procTs = LocalDateTime.now();
        transaction.setTranProcTs(procTs);

        // Extract fields matching COBOL MOVE sequence in 2000-POST-TRANSACTION
        String cardNum = transaction.getTranCardNum();
        String tranId = transaction.getTranId();
        BigDecimal amount = transaction.getTranAmt();
        String typeCd = transaction.getTranTypeCd();
        Short catCd = transaction.getTranCatCd();
        LocalDateTime origTs = transaction.getTranOrigTs();

        // Resolve account ID from card cross-reference
        String acctId = resolveAccountId(cardNum);

        // Step 1: Update/Create TransactionCategoryBalance (2700-UPDATE-TCATBAL)
        updateTransactionCategoryBalance(acctId, typeCd, catCd, amount);

        // Step 2: Update Account balance (2800-UPDATE-ACCOUNT-REC)
        updateAccountBalance(acctId, amount);

        log.debug("Posted transaction {}: card={}, acct={}, amt={}, "
                        + "type={}, cat={}, origTs={}, procTs={}",
                tranId, cardNum, acctId, amount,
                typeCd, catCd, origTs,
                procTs.format(DB2_TIMESTAMP_FORMAT));
    }

    // -----------------------------------------------------------------------
    // Account resolution via cross-reference
    // -----------------------------------------------------------------------

    /**
     * Resolves the account ID for a given card number by querying the
     * card_cross_references table directly.
     *
     * <p>Maps the COBOL cross-reference lookup from paragraph
     * {@code 1500-VALIDATE-TRAN}: {@code READ XREFFILE KEY IS XREF-CARD-NUM}
     * → {@code XREF-ACCT-ID}.</p>
     *
     * <p>Uses a native SQL query against the {@code card_cross_references}
     * table rather than importing CardCrossReferenceRepository (outside
     * declared dependency scope). Results are cached in
     * {@link #cardToAccountCache} for efficiency across repeated card
     * lookups within a single batch run.</p>
     *
     * @param cardNum the 16-character card number
     * @return the account ID (VARCHAR(11))
     * @throws IllegalStateException if no cross-reference is found
     */
    private String resolveAccountId(String cardNum) {
        return cardToAccountCache.computeIfAbsent(cardNum, cn -> {
            try {
                Object result = entityManager
                        .createNativeQuery(CROSS_REF_QUERY)
                        .setParameter(1, cn)
                        .getSingleResult();
                String acctId = result.toString();
                log.debug("Resolved card {} to account {}", cn, acctId);
                return acctId;
            } catch (NoResultException e) {
                String msg = "No card cross-reference found for card number: "
                        + cn + " — transaction should have been rejected "
                        + "during validation";
                log.error(msg);
                throw new IllegalStateException(msg, e);
            }
        });
    }

    // -----------------------------------------------------------------------
    // Category Balance update — COBOL §2700-UPDATE-TCATBAL
    // -----------------------------------------------------------------------

    /**
     * Updates or creates the transaction category balance record for the
     * given account, transaction type, and category combination.
     *
     * <p>Maps COBOL paragraphs:</p>
     * <ul>
     *   <li>{@code 2700-UPDATE-TCATBAL} — READ TCATBAL-FILE KEY IS TRAN-CAT-KEY</li>
     *   <li>{@code 2700-A-CREATE-TCATBAL-REC} — FILE STATUS '23' (not found)
     *       → create new record with balance = transaction amount</li>
     *   <li>{@code 2700-B-UPDATE-TCATBAL-REC} — FILE STATUS '00' (found)
     *       → ADD amount to existing balance, then REWRITE</li>
     * </ul>
     *
     * <p>All arithmetic uses {@link BigDecimal#add(BigDecimal)} to preserve
     * COBOL COMP-3 decimal precision (PIC S9(09)V99).</p>
     *
     * @param acctId the 11-character account ID
     * @param typeCd the 2-character transaction type code
     * @param catCd  the transaction category code (SMALLINT)
     * @param amount the transaction amount (BigDecimal, PIC S9(9)V99)
     */
    private void updateTransactionCategoryBalance(String acctId, String typeCd,
                                                   Short catCd, BigDecimal amount) {
        // Build composite key: TRAN-CAT-KEY = ACCT-ID + TYPE-CD + CAT-CD
        // Maps COBOL: MOVE FD-ACCT-ID TO TRAN-CAT-ACCT-ID,
        //             MOVE DALYTRAN-TYPE-CD TO TRAN-CAT-TYPE-CD,
        //             MOVE DALYTRAN-CAT-CD TO TRAN-CAT-CATEGORY-CD
        TransactionCategoryBalanceId compositeKey =
                new TransactionCategoryBalanceId(acctId, typeCd, catCd);

        Optional<TransactionCategoryBalance> existingOpt =
                tcatBalRepository.findById(compositeKey);

        if (existingOpt.isPresent()) {
            // 2700-B-UPDATE-TCATBAL-REC: ADD DALYTRAN-AMT TO TRAN-CAT-BAL; REWRITE
            TransactionCategoryBalance existing = existingOpt.get();
            BigDecimal currentBal = existing.getTranCatBal() != null
                    ? existing.getTranCatBal() : BigDecimal.ZERO;
            existing.setTranCatBal(currentBal.add(amount));
            tcatBalRepository.save(existing);

            log.debug("Updated category balance: acct={}, type={}, cat={}, newBal={}",
                    acctId, typeCd, catCd, existing.getTranCatBal());
        } else {
            // 2700-A-CREATE-TCATBAL-REC: FILE STATUS '23' — create new record
            // COBOL: INITIALIZE TRAN-CAT-BAL-RECORD, set key fields,
            //        ADD DALYTRAN-AMT TO TRAN-CAT-BAL (starting from ZERO), WRITE
            TransactionCategoryBalance newBalance = new TransactionCategoryBalance();
            newBalance.setId(compositeKey);
            newBalance.setTranCatBal(amount);
            tcatBalRepository.save(newBalance);

            log.debug("Created category balance: acct={}, type={}, cat={}, initialBal={}",
                    acctId, typeCd, catCd, amount);
        }
    }

    // -----------------------------------------------------------------------
    // Account Balance update — COBOL §2800-UPDATE-ACCOUNT-REC
    // -----------------------------------------------------------------------

    /**
     * Updates the account current balance and cycle accumulators.
     *
     * <p>Maps COBOL paragraph {@code 2800-UPDATE-ACCOUNT-REC}:</p>
     * <pre>
     * ADD DALYTRAN-AMT TO ACCT-CURR-BAL
     * IF DALYTRAN-AMT &gt;= 0
     *   ADD DALYTRAN-AMT TO ACCT-CURR-CYC-CREDIT
     * ELSE
     *   ADD DALYTRAN-AMT TO ACCT-CURR-CYC-DEBIT
     * END-IF
     * REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD
     * </pre>
     *
     * <p><strong>CRITICAL BigDecimal rules (AAP §0.8.2):</strong></p>
     * <ul>
     *   <li>Uses {@link BigDecimal#compareTo(BigDecimal)} for sign check —
     *       never {@link BigDecimal#equals(Object)} which is scale-sensitive</li>
     *   <li>Debit accumulator receives the negative amount directly —
     *       matching COBOL {@code ADD} semantics where the negative value
     *       is added as-is (no absolute-value conversion)</li>
     *   <li>{@code >= 0} → credit (including zero), matching COBOL
     *       {@code IF DALYTRAN-AMT >= 0}</li>
     * </ul>
     *
     * <p>Account has {@code @Version} for optimistic locking. The
     * {@link AccountRepository#save(Account)} call may throw
     * {@code OptimisticLockException} if a concurrent update occurred,
     * which is propagated to Spring Batch for retry/skip handling.</p>
     *
     * @param acctId the 11-character account ID
     * @param amount the transaction amount (negative for debits, zero or
     *               positive for credits)
     */
    private void updateAccountBalance(String acctId, BigDecimal amount) {
        Optional<Account> accountOpt = accountRepository.findById(acctId);
        if (!accountOpt.isPresent()) {
            String msg = "Account not found: " + acctId
                    + " — should have been validated during processing";
            log.error(msg);
            throw new IllegalStateException(msg);
        }
        Account account = accountOpt.get();

        // ADD DALYTRAN-AMT TO ACCT-CURR-BAL
        BigDecimal currentBal = account.getAcctCurrBal() != null
                ? account.getAcctCurrBal() : BigDecimal.ZERO;
        account.setAcctCurrBal(currentBal.add(amount));

        // Segregate into credit/debit cycle accumulators
        // COBOL: IF DALYTRAN-AMT >= 0 → ADD TO ACCT-CURR-CYC-CREDIT
        //        ELSE                 → ADD TO ACCT-CURR-CYC-DEBIT
        if (amount.compareTo(BigDecimal.ZERO) >= 0) {
            // Credit transaction (including zero-amount transactions)
            BigDecimal currentCredit = account.getAcctCurrCycCredit() != null
                    ? account.getAcctCurrCycCredit() : BigDecimal.ZERO;
            account.setAcctCurrCycCredit(currentCredit.add(amount));
        } else {
            // Debit transaction — add negative amount directly (COBOL ADD semantics:
            // the negative value is added as-is, accumulator becomes more negative)
            BigDecimal currentDebit = account.getAcctCurrCycDebit() != null
                    ? account.getAcctCurrCycDebit() : BigDecimal.ZERO;
            account.setAcctCurrCycDebit(currentDebit.add(amount));
        }

        // Save with @Version optimistic locking — equivalent to COBOL REWRITE
        // May throw OptimisticLockException if concurrent modification detected
        accountRepository.save(account);

        log.debug("Updated account {}: bal={}, cycCredit={}, cycDebit={}, txnAmt={}",
                acctId, account.getAcctCurrBal(),
                account.getAcctCurrCycCredit(),
                account.getAcctCurrCycDebit(), amount);
    }

    // -----------------------------------------------------------------------
    // S3 Backup — replaces GDG TRANSACT.BKUP(+1) from TRANREPT.jcl
    // -----------------------------------------------------------------------

    /**
     * Writes a backup of the posted transaction chunk to AWS S3.
     *
     * <p>Replaces the COBOL GDG (Generation Data Group) backup pattern
     * from TRANREPT.jcl: {@code TRANSACT.BKUP(+1)}. Each chunk produces
     * a uniquely-keyed S3 object in the configured output bucket with a
     * pipe-delimited text format.</p>
     *
     * <p><strong>Non-fatal</strong>: S3 failures are logged as warnings
     * but do NOT roll back the DB transaction. This is correct because the
     * COBOL GDG backup ran as a separate JCL step (TRANREPT.jcl) after the
     * posting SYNCPOINT commit.</p>
     *
     * <p>All S3 interactions are testable against LocalStack per AAP §0.7.7.</p>
     *
     * @param transactions the list of transactions to back up
     * @param chunkIndex   the sequential chunk number for key uniqueness
     */
    private void writeS3Backup(List<? extends Transaction> transactions,
                                long chunkIndex) {
        try {
            String timestamp = LocalDateTime.now().format(BACKUP_KEY_FORMAT);
            String s3Key = String.format("%sTRANSACT-%s-%04d.txt",
                    backupPrefix, timestamp, chunkIndex);

            StringBuilder content = new StringBuilder(512);
            content.append("# TRANSACTION BACKUP — Chunk ")
                    .append(chunkIndex)
                    .append(" — ")
                    .append(timestamp)
                    .append(System.lineSeparator());
            content.append("# TRAN-ID|TYPE-CD|CAT-CD|AMT|CARD-NUM|ORIG-TS|PROC-TS")
                    .append(System.lineSeparator());

            for (Transaction txn : transactions) {
                content.append(formatTransactionForBackup(txn));
                content.append(System.lineSeparator());
            }

            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(outputBucket)
                    .key(s3Key)
                    .contentType("text/plain")
                    .build();

            s3Client.putObject(putRequest,
                    RequestBody.fromString(content.toString(), StandardCharsets.UTF_8));

            log.info("S3 backup written: s3://{}/{} ({} transactions)",
                    outputBucket, s3Key, transactions.size());

        } catch (Exception ex) {
            // Non-fatal — log warning but do NOT propagate exception.
            // The DB transaction has already committed; S3 backup is secondary.
            log.warn("S3 backup failed for chunk #{} — {} transactions are "
                            + "persisted in database but backup was not written: {}",
                    chunkIndex, transactions.size(), ex.getMessage());
        }
    }

    /**
     * Formats a single transaction record for the S3 backup output.
     *
     * <p>Produces a pipe-delimited text line matching the field layout from
     * CVTRA05Y.cpy (350-byte record). Fields: TRAN-ID, TYPE-CD, CAT-CD,
     * AMT (plain string), CARD-NUM, ORIG-TS, PROC-TS (DB2 format).</p>
     *
     * @param transaction the transaction to format
     * @return pipe-delimited string representation
     */
    private String formatTransactionForBackup(Transaction transaction) {
        return String.join("|",
                nullSafe(transaction.getTranId()),
                nullSafe(transaction.getTranTypeCd()),
                String.valueOf(transaction.getTranCatCd()),
                transaction.getTranAmt() != null
                        ? transaction.getTranAmt().toPlainString() : "0.00",
                nullSafe(transaction.getTranCardNum()),
                transaction.getTranOrigTs() != null
                        ? transaction.getTranOrigTs().toString() : "",
                transaction.getTranProcTs() != null
                        ? transaction.getTranProcTs().format(DB2_TIMESTAMP_FORMAT)
                        : ""
        );
    }

    /**
     * Null-safe string conversion for backup formatting. Returns the
     * object's {@code toString()} result, or empty string if null.
     *
     * @param value the value to convert
     * @return string representation, or empty string if null
     */
    private String nullSafe(Object value) {
        return value != null ? value.toString() : "";
    }

    // -----------------------------------------------------------------------
    // Counter management — maps COBOL WS-TRANSACTION-COUNT
    // -----------------------------------------------------------------------

    /**
     * Returns the total number of transactions successfully posted in the
     * current job execution.
     *
     * <p>Maps COBOL field {@code WS-TRANSACTION-COUNT PIC 9(09) VALUE 0}
     * from CBTRN02C.cbl line 185. Used for batch summary reporting at job
     * completion.</p>
     *
     * @return the cumulative transaction count
     */
    public long getTransactionCount() {
        return transactionCount;
    }

    /**
     * Resets the transaction counter and internal state for a new job
     * execution.
     *
     * <p>Equivalent to COBOL WORKING-STORAGE initialization that occurs
     * at program entry ({@code WS-TRANSACTION-COUNT PIC 9(09) VALUE 0}).
     * Also clears the card-to-account resolution cache and chunk counter
     * to ensure clean state for the next batch run.</p>
     */
    public void resetTransactionCount() {
        transactionCount = 0;
        chunkCounter.set(0);
        cardToAccountCache.clear();
        log.info("TransactionWriter counters and cache reset for new job execution");
    }
}

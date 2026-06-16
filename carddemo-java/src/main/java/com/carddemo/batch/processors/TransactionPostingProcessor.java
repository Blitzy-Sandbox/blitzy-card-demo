package com.carddemo.batch.processors;

import com.carddemo.model.entity.Account;
import com.carddemo.model.entity.CardCrossReference;
import com.carddemo.model.entity.DailyTransaction;
import com.carddemo.model.entity.Transaction;
import com.carddemo.model.entity.TransactionCategoryBalance;
import com.carddemo.model.enums.FileStatus;
import com.carddemo.model.enums.RejectCode;
import com.carddemo.model.key.TransactionCategoryBalanceId;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.TransactionCategoryBalanceRepository;
import com.carddemo.service.shared.FileStatusMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Per-record {@link ItemProcessor} for the daily transaction posting batch step. Re-platforms
 * the per-record business logic of the mainframe daily-transaction posting program
 * {@code app/cbl/CBTRN02C.cbl} (source commit {@code 27d6c6f}) onto Spring Batch, with the
 * job/DD context taken from {@code app/jcl/POSTTRAN.jcl}.
 *
 * <p>For every {@link DailyTransaction} read from the input the COBOL main loop either posted
 * the record (when {@code WS-VALIDATION-FAIL-REASON} stayed {@code 0}) or wrote a reject
 * record. This processor reproduces that branch as a single typed outcome, {@link PostingResult},
 * which carries <em>either</em> a successfully posted {@link Transaction} together with the
 * recomputed {@link Account} and {@link TransactionCategoryBalance} states, <em>or</em> a reject
 * (numeric reason plus the fixed-width 80-byte trailer of {@code 2500-WRITE-REJECT-REC}). The
 * downstream writer routes the result: the success state is persisted and the reject is written
 * as the 430-byte reject record (350 data + 80 trailer, {@code LRECL=430}).</p>
 *
 * <p>The validation cascade of {@code 1500-VALIDATE-TRAN} is modelled as an ordered chain of
 * composable checks that preserves the COBOL control flow exactly:</p>
 * <ol>
 *   <li>{@code 1500-A-LOOKUP-XREF} &mdash; card cross-reference lookup; an absent key short-circuits
 *       the cascade with reject {@code 100}.</li>
 *   <li>{@code 1500-B-LOOKUP-ACCT} &mdash; account lookup, performed only when the cross-reference
 *       lookup passed; an absent key short-circuits with reject {@code 101}.</li>
 *   <li>Overlimit check {@code (C)} and expiration check {@code (D)} run sequentially with no
 *       short-circuit between them. When both fail, the expiration reason {@code 103} overwrites
 *       the overlimit reason {@code 102}, so {@code 103} takes precedence.</li>
 * </ol>
 *
 * <p>On success {@code 2000-POST-TRANSACTION} maps every daily-transaction field onto a new
 * {@link Transaction}, stamps the processing timestamp in DB2 format, and recomputes the
 * transaction-category balance ({@code 2700-UPDATE-TCATBAL}, find-or-create) and the account
 * balances ({@code 2800-UPDATE-ACCOUNT-REC}). These are in-memory computations only: this
 * processor performs reads and arithmetic and never calls a repository {@code save} or
 * {@code delete}. Persistence and the {@code REWRITE INVALID KEY} reject {@code 109}
 * ({@link RejectCode#ACCOUNT_UPDATE_NOT_FOUND}) belong to the downstream writer.</p>
 *
 * <p>All monetary values are {@link BigDecimal} and all numeric comparisons use
 * {@link BigDecimal#compareTo(BigDecimal)}. This processor is pure compute: it performs reads and
 * arithmetic only and emits no metrics. The posted-record and rejected-record counters are owned
 * by the downstream writers ({@code TransactionWriter} for {@code carddemo.batch.records.processed}
 * and {@code RejectWriter} for {@code carddemo.batch.records.rejected}), so each metric series is
 * emitted exactly once with a single, consistent tag scheme.</p>
 */
@Component
public class TransactionPostingProcessor
        implements ItemProcessor<DailyTransaction, TransactionPostingProcessor.PostingResult> {

    private static final Logger LOGGER = LoggerFactory.getLogger(TransactionPostingProcessor.class);

    /**
     * DB2 timestamp format ({@code yyyy-MM-dd-HH.mm.ss.SSSSSS}, 26 characters) reproducing the
     * COBOL {@code Z-GET-DB2-FORMAT-TIMESTAMP} layout written to {@code TRAN-PROC-TS}.
     */
    private static final DateTimeFormatter DB2_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HH.mm.ss.SSSSSS");

    /** Length of the date prefix compared against the account expiration date ({@code (1:10)}). */
    private static final int ORIG_TS_DATE_LENGTH = 10;

    private final CardCrossReferenceRepository cardCrossReferenceRepository;
    private final AccountRepository accountRepository;
    private final TransactionCategoryBalanceRepository transactionCategoryBalanceRepository;
    private final FileStatusMapper fileStatusMapper;
    private final Clock clock;

    /**
     * Creates the processor for Spring-managed use, defaulting the processing-timestamp clock to
     * the system default-zone clock.
     *
     * @param cardCrossReferenceRepository          card cross-reference lookup repository
     * @param accountRepository                     account lookup repository
     * @param transactionCategoryBalanceRepository  transaction-category-balance repository
     * @param fileStatusMapper                      COBOL {@code FILE STATUS} mapper
     */
    @Autowired
    public TransactionPostingProcessor(
            CardCrossReferenceRepository cardCrossReferenceRepository,
            AccountRepository accountRepository,
            TransactionCategoryBalanceRepository transactionCategoryBalanceRepository,
            FileStatusMapper fileStatusMapper) {
        this(cardCrossReferenceRepository, accountRepository, transactionCategoryBalanceRepository,
                fileStatusMapper, Clock.systemDefaultZone());
    }

    /**
     * Creates the processor with an explicit {@link Clock}, allowing tests to supply a fixed clock
     * so that the DB2-format processing timestamp is deterministic.
     *
     * @param cardCrossReferenceRepository          card cross-reference lookup repository
     * @param accountRepository                     account lookup repository
     * @param transactionCategoryBalanceRepository  transaction-category-balance repository
     * @param fileStatusMapper                      COBOL {@code FILE STATUS} mapper
     * @param clock                                 clock used to stamp the processing timestamp
     */
    public TransactionPostingProcessor(
            CardCrossReferenceRepository cardCrossReferenceRepository,
            AccountRepository accountRepository,
            TransactionCategoryBalanceRepository transactionCategoryBalanceRepository,
            FileStatusMapper fileStatusMapper,
            Clock clock) {
        this.cardCrossReferenceRepository = cardCrossReferenceRepository;
        this.accountRepository = accountRepository;
        this.transactionCategoryBalanceRepository = transactionCategoryBalanceRepository;
        this.fileStatusMapper = fileStatusMapper;
        this.clock = clock;
    }

    /**
     * Validates a single daily transaction and either posts it or rejects it, reproducing the
     * {@code 1500-VALIDATE-TRAN} / {@code 2000-POST-TRANSACTION} branch of {@code CBTRN02C}. The
     * returned {@link PostingResult} is never {@code null}: every input yields either a posted
     * result or a reject so that no record is silently filtered out of the chunk.
     *
     * @param dalytran the daily transaction to post; supplied non-null by the Spring Batch chunk
     * @return the typed posting outcome (posted or rejected)
     */
    @Override
    public PostingResult process(DailyTransaction dalytran) {
        // 1500-A-LOOKUP-XREF: an absent card cross-reference short-circuits the cascade (reject 100).
        Optional<CardCrossReference> crossReference =
                cardCrossReferenceRepository.findById(dalytran.getDalytranCardNum());
        if (crossReference.isEmpty()) {
            return reject(RejectCode.INVALID_CARD_NUMBER, dalytran);
        }
        Long accountId = crossReference.get().getXrefAcctId();

        // 1500-B-LOOKUP-ACCT: performed only when 1500-A passed; absent account rejects with 101.
        Optional<Account> accountLookup = accountRepository.findById(accountId);
        if (accountLookup.isEmpty()) {
            return reject(RejectCode.ACCOUNT_NOT_FOUND, dalytran);
        }
        Account account = accountLookup.get();

        // Overlimit (C) and expiration (D) execute sequentially with no short-circuit; when both
        // fail, the expiration reason (103) overwrites the overlimit reason (102).
        RejectCode rejectReason = null;
        if (!isWithinCreditLimit(account, dalytran)) {
            rejectReason = RejectCode.OVERLIMIT_TRANSACTION;
        }
        if (!isWithinExpiration(account, dalytran)) {
            rejectReason = RejectCode.TRANSACTION_AFTER_EXPIRATION;
        }
        // ADD MORE VALIDATIONS HERE (no-op placeholder reproduced verbatim from CBTRN02C).
        if (rejectReason != null) {
            return reject(rejectReason, dalytran);
        }

        // 2000-POST-TRANSACTION: map the record, recompute balances in memory, and post.
        Transaction postedTransaction = buildTransaction(dalytran);
        TransactionCategoryBalance categoryBalance = computeCategoryBalance(dalytran, accountId);
        Account updatedAccount = applyAccountBalances(account, dalytran);
        LOGGER.debug("Posted daily transaction {} to account {}",
                dalytran.getDalytranId(), accountId);
        return PostingResult.posted(postedTransaction, updatedAccount, categoryBalance);
    }

    /**
     * Overlimit check ({@code 1500-B}, branch C). Computes
     * {@code WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT} and passes
     * when {@code ACCT-CREDIT-LIMIT >= WS-TEMP-BAL}.
     *
     * @param account  the looked-up account
     * @param dalytran the daily transaction being validated
     * @return {@code true} when the transaction stays within the credit limit
     */
    private boolean isWithinCreditLimit(Account account, DailyTransaction dalytran) {
        BigDecimal temporaryBalance = account.getAcctCurrCycCredit()
                .subtract(account.getAcctCurrCycDebit())
                .add(dalytran.getDalytranAmt());
        return account.getAcctCreditLimit().compareTo(temporaryBalance) >= 0;
    }

    /**
     * Expiration check ({@code 1500-B}, branch D). Passes when
     * {@code ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)}. The comparison is lexicographic on the
     * {@code yyyy-MM-dd} strings, which is equivalent to chronological order. A missing or
     * shorter-than-ten-character origination timestamp is treated defensively as an empty date so
     * the check passes without raising an exception.
     *
     * @param account  the looked-up account
     * @param dalytran the daily transaction being validated
     * @return {@code true} when the transaction was received on or before the expiration date
     */
    private boolean isWithinExpiration(Account account, DailyTransaction dalytran) {
        String originationTimestamp = dalytran.getDalytranOrigTs();
        String originationDate =
                (originationTimestamp != null && originationTimestamp.length() >= ORIG_TS_DATE_LENGTH)
                        ? originationTimestamp.substring(0, ORIG_TS_DATE_LENGTH)
                        : "";
        return account.getAcctExpiraionDate().compareTo(originationDate) >= 0;
    }

    /**
     * Maps the daily transaction onto a new {@link Transaction} ({@code 2000-POST-TRANSACTION},
     * field-for-field) and stamps the processing timestamp in DB2 format.
     *
     * @param dalytran the daily transaction being posted
     * @return the populated transaction ready for persistence by the writer
     */
    private Transaction buildTransaction(DailyTransaction dalytran) {
        Transaction transaction = new Transaction();
        transaction.setTranId(dalytran.getDalytranId());
        transaction.setTranTypeCd(dalytran.getDalytranTypeCd());
        transaction.setTranCatCd(dalytran.getDalytranCatCd());
        transaction.setTranSource(dalytran.getDalytranSource());
        transaction.setTranDesc(dalytran.getDalytranDesc());
        transaction.setTranAmt(dalytran.getDalytranAmt());
        transaction.setTranMerchantId(dalytran.getDalytranMerchantId());
        transaction.setTranMerchantName(dalytran.getDalytranMerchantName());
        transaction.setTranMerchantCity(dalytran.getDalytranMerchantCity());
        transaction.setTranMerchantZip(dalytran.getDalytranMerchantZip());
        transaction.setTranCardNum(dalytran.getDalytranCardNum());
        transaction.setTranOrigTs(dalytran.getDalytranOrigTs());
        transaction.setTranProcTs(LocalDateTime.now(clock).format(DB2_TIMESTAMP_FORMAT));
        return transaction;
    }

    /**
     * Find-or-create of the transaction-category balance ({@code 2700-UPDATE-TCATBAL}). The COBOL
     * read tolerates only {@code FILE STATUS '00'} (record found) or {@code '23'} (not found, which
     * drives the create path); the equivalent Java outcomes are derived from the {@link Optional}
     * and routed through {@link FileStatusMapper#isRecordNotFound(String)}. The new balance is
     * computed in memory and returned for the writer to persist.
     *
     * @param dalytran  the daily transaction being posted
     * @param accountId the resolved account id (the {@code XREF-ACCT-ID} key component)
     * @return the created or updated transaction-category balance
     */
    private TransactionCategoryBalance computeCategoryBalance(DailyTransaction dalytran, Long accountId) {
        TransactionCategoryBalanceId key = new TransactionCategoryBalanceId(
                accountId, dalytran.getDalytranTypeCd(), dalytran.getDalytranCatCd());
        Optional<TransactionCategoryBalance> existing =
                transactionCategoryBalanceRepository.findById(key);

        String simulatedStatus = existing.isPresent()
                ? FileStatus.SUCCESS.getCode()
                : FileStatus.RECORD_NOT_FOUND.getCode();

        if (fileStatusMapper.isRecordNotFound(simulatedStatus)) {
            // 2700-A-CREATE-TCATBAL-REC: INITIALIZE then ADD DALYTRAN-AMT TO TRAN-CAT-BAL.
            TransactionCategoryBalance created = new TransactionCategoryBalance();
            created.setId(key);
            created.setTranCatBal(dalytran.getDalytranAmt());
            return created;
        }

        // 2700-B-UPDATE-TCATBAL-REC: ADD DALYTRAN-AMT TO TRAN-CAT-BAL.
        TransactionCategoryBalance updated = existing.get();
        updated.setTranCatBal(updated.getTranCatBal().add(dalytran.getDalytranAmt()));
        return updated;
    }

    /**
     * Recomputes the account balances ({@code 2800-UPDATE-ACCOUNT-REC}): adds the amount to the
     * current balance and, by the sign of the amount, to either the cycle credit or the cycle debit.
     * The mutation is in memory only; the {@code REWRITE INVALID KEY} reject {@code 109}
     * ({@link RejectCode#ACCOUNT_UPDATE_NOT_FOUND}) is raised by the downstream writer, never here,
     * because the account was just read in stage B.
     *
     * @param account  the looked-up account to update in place
     * @param dalytran the daily transaction being posted
     * @return the same account instance with updated balances
     */
    private Account applyAccountBalances(Account account, DailyTransaction dalytran) {
        BigDecimal amount = dalytran.getDalytranAmt();
        account.setAcctCurrBal(account.getAcctCurrBal().add(amount));
        if (amount.compareTo(BigDecimal.ZERO) >= 0) {
            account.setAcctCurrCycCredit(account.getAcctCurrCycCredit().add(amount));
        } else {
            account.setAcctCurrCycDebit(account.getAcctCurrCycDebit().add(amount));
        }
        return account;
    }

    /**
     * Builds the typed reject outcome ({@code 2500-WRITE-REJECT-REC} supplies the trailer; the
     * downstream {@code RejectWriter} performs the file write and emits the reason-tagged
     * {@code carddemo.batch.records.rejected} counter, so no metric is recorded here).
     *
     * @param rejectCode the reject reason
     * @param dalytran   the rejected daily transaction (source of the 350-byte reject data)
     * @return the reject posting result
     */
    private PostingResult reject(RejectCode rejectCode, DailyTransaction dalytran) {
        LOGGER.debug("Rejected daily transaction {} with reason {} ({})",
                dalytran.getDalytranId(), rejectCode.getCode(), rejectCode.name());
        return PostingResult.rejected(rejectCode, dalytran);
    }

    /**
     * Typed outcome of posting a single daily transaction. Carries either a successfully posted
     * {@link Transaction} together with the recomputed {@link Account} and
     * {@link TransactionCategoryBalance} (when {@code rejected} is {@code false}), or a reject (when
     * {@code rejected} is {@code true}) consisting of the numeric {@code rejectCode}, the 76-character
     * reason description, and the fixed-width 80-byte {@code rejectTrailer}. The downstream writer
     * uses {@link #originalDailyTransaction()} for the 350-byte reject data and concatenates it with
     * {@link #rejectTrailer()} to form the 430-byte reject record.
     *
     * @param rejected                 {@code true} for a reject, {@code false} for a posted record
     * @param postedTransaction        the posted transaction; non-null only when not rejected
     * @param updatedAccount           the account with recomputed balances; non-null only when not rejected
     * @param updatedCategoryBalance   the created/updated category balance; non-null only when not rejected
     * @param rejectCode               the numeric reject reason; non-null only when rejected
     * @param rejectReasonDescription  the 76-character reason description; non-null only when rejected
     * @param rejectTrailer            the 80-byte trailer (4-digit code + 76-char description); non-null only when rejected
     * @param originalDailyTransaction the source daily transaction (350-byte reject data) when rejected
     */
    public record PostingResult(
            boolean rejected,
            Transaction postedTransaction,
            Account updatedAccount,
            TransactionCategoryBalance updatedCategoryBalance,
            Integer rejectCode,
            String rejectReasonDescription,
            String rejectTrailer,
            DailyTransaction originalDailyTransaction) {

        /** Width of the COBOL {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)} reason description. */
        private static final int REASON_DESC_WIDTH = 76;

        /**
         * Builds a successful posting result.
         *
         * @param postedTransaction      the posted transaction
         * @param updatedAccount         the account with recomputed balances
         * @param updatedCategoryBalance the created/updated category balance
         * @return a non-rejected posting result
         */
        public static PostingResult posted(Transaction postedTransaction, Account updatedAccount,
                TransactionCategoryBalance updatedCategoryBalance) {
            return new PostingResult(false, postedTransaction, updatedAccount, updatedCategoryBalance,
                    null, null, null, null);
        }

        /**
         * Builds a reject result, constructing the fixed-width 80-byte trailer that mirrors
         * {@code WS-VALIDATION-TRAILER}: a 4-digit zero-padded reason code immediately followed by
         * the 76-character, space-padded reason description.
         *
         * @param rejectCode               the reject reason
         * @param originalDailyTransaction the rejected daily transaction
         * @return a rejected posting result with the populated reason fields and trailer
         */
        public static PostingResult rejected(RejectCode rejectCode,
                DailyTransaction originalDailyTransaction) {
            String description = padReason(rejectCode.getDescription());
            String trailer = String.format("%04d", rejectCode.getCode()) + description;
            return new PostingResult(true, null, null, null, rejectCode.getCode(),
                    description, trailer, originalDailyTransaction);
        }

        /**
         * Left-justifies and space-pads (or right-truncates) the reason text to exactly
         * {@link #REASON_DESC_WIDTH} characters, reproducing a COBOL {@code MOVE} to a
         * {@code PIC X(76)} field.
         *
         * @param reason the raw reason description
         * @return the reason text fixed to exactly {@link #REASON_DESC_WIDTH} characters
         */
        private static String padReason(String reason) {
            String value = (reason == null) ? "" : reason;
            if (value.length() >= REASON_DESC_WIDTH) {
                return value.substring(0, REASON_DESC_WIDTH);
            }
            return value + " ".repeat(REASON_DESC_WIDTH - value.length());
        }
    }
}

package com.carddemo.batch.processors;

import com.carddemo.exception.ValidationException;
import com.carddemo.model.entity.Account;
import com.carddemo.model.entity.CardCrossReference;
import com.carddemo.model.entity.DailyTransaction;
import com.carddemo.model.entity.Transaction;
import com.carddemo.model.enums.RejectCode;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardCrossReferenceRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Per-record {@link ItemProcessor} for the daily-transaction posting stage of the batch pipeline.
 *
 * <p>Translated (logic only, never source text; traceability via commit {@code 27d6c6f}) from the
 * COBOL daily-transaction posting program {@code app/cbl/CBTRN02C.cbl}. The per-record main-loop
 * body of that program ({@code 1500-VALIDATE-TRAN} followed by either {@code 2000-POST-TRANSACTION}
 * or {@code 2500-WRITE-REJECT-REC}) is reproduced here as a single {@link #process(DailyTransaction)}
 * invocation. Job and dataset context (the {@code DALYREJS} reject file, {@code RECFM=F, LRECL=430}
 * = 350 data + 80 trailer) comes from {@code app/jcl/POSTTRAN.jcl}.</p>
 *
 * <h2>Validation cascade (Strategy)</h2>
 * <p>The four validation stages run in the exact COBOL order, with the COBOL short-circuit and
 * overwrite semantics preserved:</p>
 * <ul>
 *   <li><strong>(A) {@code 1500-A-LOOKUP-XREF}</strong> &mdash; look up the card cross-reference by
 *       card number. Absent ({@code INVALID KEY}) rejects with code {@code 100} and stops the
 *       cascade (B/C/D do not run).</li>
 *   <li><strong>(B) {@code 1500-B-LOOKUP-ACCT}</strong> &mdash; only when A passed, look up the
 *       account by the cross-reference account id. Absent rejects with code {@code 101} and stops
 *       the cascade (C/D live inside the COBOL {@code NOT INVALID KEY} block).</li>
 *   <li><strong>(C) overlimit</strong> and <strong>(D) expiration</strong> &mdash; once the account
 *       is found, both checks run sequentially with <em>no</em> short-circuit between them. When
 *       both fail, the expiration result (code {@code 103}) overwrites the overlimit result (code
 *       {@code 102}), so {@code 103} takes precedence (verified at {@code CBTRN02C.cbl} L407-L420).</li>
 * </ul>
 *
 * <h2>Posting computation (projection only)</h2>
 * <p>On success ({@code 2000-POST-TRANSACTION}) the processor maps the daily transaction onto a new
 * {@link Transaction} field-for-field and stamps the processing timestamp in DB2 format
 * ({@code yyyy-MM-dd-HH.mm.ss.SSSSSS}, the {@code Z-GET-DB2-FORMAT-TIMESTAMP} layout). It does
 * <em>not</em> compute or apply the transaction-category balance ({@code 2700-UPDATE-TCATBAL}) or
 * the account balances ({@code 2800-UPDATE-ACCOUNT-REC}); those updates are owned exclusively by
 * the downstream transaction writer.</p>
 *
 * <h2>Persistence boundary (single apply path)</h2>
 * <p>This processor performs validation reads (lookups) only; it never mutates a managed entity and
 * never calls a repository {@code save}/{@code delete}, so it contributes no dirty state to the
 * chunk's persistence context. The resulting {@link PostingResult} carries either a posted
 * {@link Transaction} together with the validated owning {@link Account} (used downstream only for
 * its account id) or a reject (for the reject writer to emit as the 430-byte record). Within the
 * single chunk transaction the transaction writer is the sole component that applies the
 * category-balance and account-balance updates, so each accepted transaction is applied exactly
 * once. Reject code {@code 109} ({@link RejectCode#ACCOUNT_UPDATE_NOT_FOUND}) arises during the
 * writer's account-update step, not here, because the account was already read in stage B.</p>
 *
 * <h2>Observability</h2>
 * <p>The {@code carddemo.batch.records.rejected} counter, tagged by {@code reason}, is incremented
 * once per reject by this processor. The {@code carddemo.batch.records.processed} counter is owned
 * by the transaction writer (the terminal persist step) so that each posted record is counted
 * exactly once.</p>
 */
@Component
public class TransactionPostingProcessor
        implements ItemProcessor<DailyTransaction, TransactionPostingProcessor.PostingResult> {

    private static final Logger LOG = LoggerFactory.getLogger(TransactionPostingProcessor.class);

    /** Counter incremented once per rejected daily-transaction record (tagged by reason). */
    private static final String METRIC_RECORDS_REJECTED = "carddemo.batch.records.rejected";

    /** Tag key carrying the reject reason on the {@code carddemo.batch.records.rejected} counter. */
    private static final String TAG_REASON = "reason";

    /** Number of leading characters of the origination timestamp that hold the {@code yyyy-MM-dd} date. */
    private static final int ORIG_DATE_LENGTH = 10;

    /**
     * DB2 timestamp format {@code yyyy-MM-dd-HH.mm.ss.SSSSSS} (26 characters), reproducing the
     * {@code Z-GET-DB2-FORMAT-TIMESTAMP} layout the source program writes to {@code TRAN-PROC-TS}.
     */
    private static final DateTimeFormatter DB2_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HH.mm.ss.SSSSSS");

    private final CardCrossReferenceRepository cardCrossReferenceRepository;
    private final AccountRepository accountRepository;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    /**
     * Creates the processor with collaborators injected by the Spring container, using the system
     * clock for the processing timestamp.
     *
     * @param cardCrossReferenceRepository repository for the card cross-reference lookup (stage A)
     * @param accountRepository            repository for the account lookup (stage B)
     * @param meterRegistry                Micrometer registry for the reason-tagged rejected counter
     */
    @Autowired
    public TransactionPostingProcessor(
            final CardCrossReferenceRepository cardCrossReferenceRepository,
            final AccountRepository accountRepository,
            final MeterRegistry meterRegistry) {
        this(cardCrossReferenceRepository, accountRepository, meterRegistry, Clock.systemDefaultZone());
    }

    /**
     * Creates the processor with an explicit {@link Clock}, allowing the processing timestamp to be
     * made deterministic in tests.
     *
     * @param cardCrossReferenceRepository repository for the card cross-reference lookup (stage A)
     * @param accountRepository            repository for the account lookup (stage B)
     * @param meterRegistry                Micrometer registry for the reason-tagged rejected counter
     * @param clock                        clock used to stamp the processing timestamp
     */
    public TransactionPostingProcessor(
            final CardCrossReferenceRepository cardCrossReferenceRepository,
            final AccountRepository accountRepository,
            final MeterRegistry meterRegistry,
            final Clock clock) {
        this.cardCrossReferenceRepository =
                Objects.requireNonNull(cardCrossReferenceRepository, "cardCrossReferenceRepository must not be null");
        this.accountRepository =
                Objects.requireNonNull(accountRepository, "accountRepository must not be null");
        this.meterRegistry =
                Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Validates and posts a single daily-transaction record.
     *
     * <p>Runs the COBOL {@code 1500-VALIDATE-TRAN} cascade; on failure returns a reject
     * {@link PostingResult} (the record is never posted and the chunk continues so the reject writer
     * can emit it), and on success returns a posted {@link PostingResult} carrying the new
     * transaction plus the validated owning account (carried only for its id). This processor is a
     * read-only validator/projection: it mutates no account or transaction-category balances, so the
     * transaction writer remains the sole apply path and the balances are updated exactly once.</p>
     *
     * @param dalytran the daily-transaction record to post; must not be {@code null}
     * @return a {@link PostingResult} describing either the posted transaction or the reject
     * @throws ValidationException if the record carries a missing or malformed origination timestamp
     */
    @Override
    public PostingResult process(final DailyTransaction dalytran) {
        Objects.requireNonNull(dalytran, "dalytran must not be null");

        // 1500-A-LOOKUP-XREF: absent cross-reference -> reject 100 and stop the cascade.
        final Optional<CardCrossReference> xref =
                cardCrossReferenceRepository.findById(dalytran.getDalytranCardNum());
        if (xref.isEmpty()) {
            return reject(RejectCode.INVALID_CARD_NUMBER, dalytran);
        }
        final Long acctId = xref.get().getXrefAcctId();

        // 1500-B-LOOKUP-ACCT: only because A passed; absent account -> reject 101 and stop.
        final Optional<Account> accountLookup = accountRepository.findById(acctId);
        if (accountLookup.isEmpty()) {
            return reject(RejectCode.ACCOUNT_NOT_FOUND, dalytran);
        }
        final Account account = accountLookup.get();

        // Overlimit (C) and expiration (D) run sequentially with no short-circuit; when both fail,
        // expiration (103) overwrites overlimit (102), so 103 takes precedence.
        RejectCode failReason = null;
        if (!passesOverlimitCheck(account, dalytran)) {
            failReason = RejectCode.OVERLIMIT_TRANSACTION;
        }
        if (!passesExpirationCheck(account, dalytran)) {
            failReason = RejectCode.TRANSACTION_AFTER_EXPIRATION;
        }
        if (failReason != null) {
            return reject(failReason, dalytran);
        }
        // * ADD MORE VALIDATIONS HERE (no-op placeholder from the source program).

        // 2000-POST-TRANSACTION: project the posted transaction only. The category-balance
        // (2700-UPDATE-TCATBAL) and account (2800-UPDATE-ACCOUNT-REC) updates are applied exactly
        // once by the transaction writer within the single chunk transaction; applying them here as
        // well would double-count every accepted transaction.
        final Transaction posted = buildPostedTransaction(dalytran);
        return postedResult(posted, account);
    }

    /**
     * Overlimit check (COBOL {@code 1500-B}): {@code WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT -
     * ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT}; the transaction passes when
     * {@code ACCT-CREDIT-LIMIT >= WS-TEMP-BAL}.
     *
     * @param account  the account read in stage B
     * @param dalytran the daily-transaction record being validated
     * @return {@code true} when the credit limit covers the projected balance
     */
    private boolean passesOverlimitCheck(final Account account, final DailyTransaction dalytran) {
        final BigDecimal tempBal = account.getAcctCurrCycCredit()
                .subtract(account.getAcctCurrCycDebit())
                .add(dalytran.getDalytranAmt());
        return account.getAcctCreditLimit().compareTo(tempBal) >= 0;
    }

    /**
     * Expiration check (COBOL {@code 1500-B}): the transaction passes when
     * {@code ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)}. Both operands are {@code yyyy-MM-dd}
     * strings, for which the COBOL alphanumeric {@code >=} is lexicographically equivalent to a
     * chronological comparison, so {@link String#compareTo(String)} is used.
     *
     * @param account  the account read in stage B
     * @param dalytran the daily-transaction record being validated
     * @return {@code true} when the account is not expired relative to the transaction date
     * @throws ValidationException if the origination timestamp is missing or shorter than the date
     */
    private boolean passesExpirationCheck(final Account account, final DailyTransaction dalytran) {
        final String origTs = dalytran.getDalytranOrigTs();
        if (origTs == null || origTs.length() < ORIG_DATE_LENGTH) {
            throw new ValidationException(
                    "Daily transaction " + dalytran.getDalytranId()
                            + " has a missing or malformed origination timestamp",
                    "dalytranOrigTs");
        }
        final String origDate = origTs.substring(0, ORIG_DATE_LENGTH);
        return account.getAcctExpiraionDate().compareTo(origDate) >= 0;
    }

    /**
     * Builds the posted transaction (COBOL {@code 2000-POST-TRANSACTION}), mapping every daily
     * field onto the transaction record and stamping the processing timestamp in DB2 format.
     *
     * @param dalytran the validated daily-transaction record
     * @return a new {@link Transaction} populated from the daily record
     */
    private Transaction buildPostedTransaction(final DailyTransaction dalytran) {
        final Transaction tran = new Transaction();
        tran.setTranId(dalytran.getDalytranId());
        tran.setTranTypeCd(dalytran.getDalytranTypeCd());
        tran.setTranCatCd(dalytran.getDalytranCatCd());
        tran.setTranSource(dalytran.getDalytranSource());
        tran.setTranDesc(dalytran.getDalytranDesc());
        tran.setTranAmt(dalytran.getDalytranAmt());
        tran.setTranMerchantId(dalytran.getDalytranMerchantId());
        tran.setTranMerchantName(dalytran.getDalytranMerchantName());
        tran.setTranMerchantCity(dalytran.getDalytranMerchantCity());
        tran.setTranMerchantZip(dalytran.getDalytranMerchantZip());
        tran.setTranCardNum(dalytran.getDalytranCardNum());
        tran.setTranOrigTs(dalytran.getDalytranOrigTs());
        tran.setTranProcTs(LocalDateTime.now(clock).format(DB2_TIMESTAMP_FORMAT));
        return tran;
    }

    /**
     * Records a reject outcome: increments the reason-tagged rejected counter and returns the reject
     * {@link PostingResult}. No transaction is built.
     *
     * @param code     the reject reason
     * @param dalytran the daily-transaction record being rejected
     * @return a reject {@link PostingResult}
     */
    private PostingResult reject(final RejectCode code, final DailyTransaction dalytran) {
        meterRegistry.counter(METRIC_RECORDS_REJECTED, TAG_REASON, code.name()).increment();
        if (LOG.isDebugEnabled()) {
            LOG.debug("Rejecting daily transaction {} with reject code {} ({})",
                    dalytran.getDalytranId(), code.getCode(), code.name());
        }
        return PostingResult.rejected(code, dalytran);
    }

    /**
     * Builds a posted outcome and logs it at debug level. No metric is incremented here: the
     * {@code carddemo.batch.records.processed} counter is owned by the transaction writer (the
     * terminal persist step) so each posted record is counted exactly once.
     *
     * @param tran    the posted (projected) transaction
     * @param account the validated owning account (carried downstream for its account id only)
     * @return a posted {@link PostingResult}
     */
    private PostingResult postedResult(final Transaction tran, final Account account) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Posted daily transaction {} to account {}", tran.getTranId(), account.getAcctId());
        }
        return PostingResult.posted(tran, account);
    }

    /**
     * Typed outcome of {@link TransactionPostingProcessor#process(DailyTransaction)}: either a
     * successfully posted (projected) transaction together with its validated owning account, or a
     * reject (with the numeric reason, the {@code X(76)} reason description, and the 80-byte
     * validation trailer).
     *
     * <p>This is the contract consumed by the downstream transaction and reject writers. For a
     * posted result {@code postedTransaction} and {@code account} are non-null and the reject fields
     * are {@code null}; the {@code account} is carried for its account id only &mdash; the processor
     * mutates no balances, so the transaction writer applies the category-balance and account-balance
     * updates exactly once. For a reject the reverse holds and {@code originalDailyTransaction}
     * carries the source record used to build the 350-byte data portion of the 430-byte reject
     * record.</p>
     *
     * @param rejected                 {@code true} for a reject, {@code false} for a posted transaction
     * @param postedTransaction        the posted transaction, or {@code null} when rejected
     * @param account                  the validated owning account (carried for its id), or {@code null} when rejected
     * @param rejectCode               the numeric reject reason (100/101/102/103), or {@code null} when posted
     * @param rejectReasonDescription  the {@code X(76)} reason description (space-padded), or {@code null} when posted
     * @param rejectTrailer            the 80-byte validation trailer, or {@code null} when posted
     * @param originalDailyTransaction the source daily-transaction record, or {@code null} when posted
     */
    public record PostingResult(
            boolean rejected,
            Transaction postedTransaction,
            Account account,
            Integer rejectCode,
            String rejectReasonDescription,
            String rejectTrailer,
            DailyTransaction originalDailyTransaction) {

        /** Width of the numeric reject reason field ({@code WS-VALIDATION-FAIL-REASON PIC 9(04)}). */
        private static final int TRAILER_REASON_LENGTH = 4;

        /** Width of the reason-description field ({@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)}). */
        private static final int TRAILER_DESC_LENGTH = 76;

        /**
         * Builds a posted result carrying the projected transaction and its validated owning account.
         *
         * @param transaction the posted transaction
         * @param account     the validated owning account (carried for its account id)
         * @return a posted {@link PostingResult}
         */
        public static PostingResult posted(final Transaction transaction, final Account account) {
            return new PostingResult(false, transaction, account, null, null, null, null);
        }

        /**
         * Builds a reject result carrying the numeric reason, the {@code X(76)} reason description,
         * and the 80-byte validation trailer (4-digit zero-padded reason followed by the 76-character
         * space-padded description), together with the source record.
         *
         * @param code   the reject reason
         * @param source the source daily-transaction record
         * @return a reject {@link PostingResult}
         */
        public static PostingResult rejected(final RejectCode code, final DailyTransaction source) {
            final String description = padReasonDescription(code.getDescription());
            final String trailer = String.format("%0" + TRAILER_REASON_LENGTH + "d", code.getCode()) + description;
            return new PostingResult(true, null, null, code.getCode(), description, trailer, source);
        }

        /**
         * Left-justifies the reason description into exactly {@value #TRAILER_DESC_LENGTH} characters,
         * space-padding when shorter and truncating when longer ({@code MOVE ... TO PIC X(76)}).
         *
         * @param description the raw reason description; may be {@code null}
         * @return a string of exactly {@value #TRAILER_DESC_LENGTH} characters
         */
        private static String padReasonDescription(final String description) {
            final String value = description == null ? "" : description;
            if (value.length() >= TRAILER_DESC_LENGTH) {
                return value.substring(0, TRAILER_DESC_LENGTH);
            }
            return value + " ".repeat(TRAILER_DESC_LENGTH - value.length());
        }
    }
}

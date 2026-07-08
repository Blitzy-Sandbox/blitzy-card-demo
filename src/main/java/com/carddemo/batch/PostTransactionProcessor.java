package com.carddemo.batch;

import com.carddemo.entity.Account;
import com.carddemo.entity.CardXref;
import com.carddemo.entity.DailyTransaction;
import com.carddemo.entity.Transaction;
import com.carddemo.entity.TransactionCategoryBalance;
import com.carddemo.entity.TransactionCategoryBalanceId;
import com.carddemo.exception.FileProcessingException;
import com.carddemo.exception.RejectReason;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardXrefRepository;
import com.carddemo.repository.TransactionCategoryBalanceRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Spring Batch {@link ItemProcessor} that posts a single daily transaction &mdash; the
 * <strong>posting engine</strong> of the CardDemo transaction-posting job
 * ({@code PostTransactionJob}).
 *
 * <p><strong>COBOL lineage (reference-only, source SHA {@code 27d6c6f}).</strong> This class is a
 * high-fidelity, one-to-one translation of the per-record body of the legacy batch program
 * {@code app/cbl/CBTRN02C.cbl} (JCL {@code app/jcl/POSTTRAN.jcl}, step {@code STEP15}). The COBOL
 * main loop reads {@code DALYTRAN}, runs {@code 1500-VALIDATE-TRAN}, and dispatches on
 * {@code WS-VALIDATION-FAIL-REASON}:</p>
 *
 * <pre>
 *   IF WS-VALIDATION-FAIL-REASON = 0
 *       PERFORM 2000-POST-TRANSACTION      -&gt; {@link PostingResult#posted}
 *   ELSE
 *       ADD 1 TO WS-REJECT-COUNT
 *       PERFORM 2500-WRITE-REJECT-REC      -&gt; {@link PostingResult#rejected}
 *   END-IF
 * </pre>
 *
 * <p>In the migrated design each daily-transaction row is one chunk item. This processor
 * reproduces the paragraphs that <em>decide the outcome</em> and <em>apply the balance
 * side-effects</em> ({@code 1500-VALIDATE-TRAN}, {@code 2000-POST-TRANSACTION},
 * {@code 2700-UPDATE-TCATBAL}, {@code 2800-UPDATE-ACCOUNT-REC}). It returns a discriminated
 * {@link PostingResult}; the companion {@code PostTransactionItemWriter} performs the file writes
 * &mdash; the posted-transaction write ({@code 2900-WRITE-TRANSACTION-FILE}) and the 430-byte
 * reject-record write ({@code 2500-WRITE-REJECT-REC}) &mdash; and maintains the posted/rejected
 * counters.</p>
 *
 * <h2>Control-flow parity</h2>
 * <ul>
 *   <li><strong>Validation order</strong> is preserved exactly: {@code 100} (invalid card) then
 *       {@code 101} (account not found) then the two consecutive checks {@code 102} (overlimit)
 *       and {@code 103} (expired). The overlimit and expiration checks are <em>independent</em>
 *       {@code if} statements, not {@code else if}: when both fail the reason ends as {@code 103}
 *       because the expiration check runs second and overwrites {@code 102} &mdash; a deliberate
 *       last-write-wins behaviour matching the COBOL.</li>
 *   <li><strong>Side-effect order</strong> is preserved: {@code 2700-UPDATE-TCATBAL} (category
 *       balance) before {@code 2800-UPDATE-ACCOUNT-REC} (account balances); the posted-transaction
 *       write follows in the writer.</li>
 *   <li><strong>Rejects are normal flow.</strong> A business-rule failure returns
 *       {@link PostingResult#rejected} and applies <em>no</em> balance side-effects (the COBOL posts
 *       only when {@code WS-VALIDATION-FAIL-REASON = 0}); it is never signalled with an exception.</li>
 * </ul>
 *
 * <h2>Decimal fidelity</h2>
 * <p>Every monetary value is a {@link BigDecimal}; {@code float}/{@code double} are never used. Each
 * arithmetic result is normalised to {@code scale = 2} with {@link RoundingMode#HALF_UP} (see
 * {@link #scale2(BigDecimal)}), reproducing the {@code PIC S9(n)V99} pictures of the COBOL working
 * storage and copybooks. All amount comparisons use {@link BigDecimal#compareTo(BigDecimal)} rather
 * than {@code equals}/{@code ==} so that scale differences never affect the result.</p>
 *
 * <h2>Transaction boundary</h2>
 * <p>The processor runs inside the Spring Batch chunk transaction. The category-balance update
 * ({@code 2700}), the account-balance update ({@code 2800}) and the writer's posted-transaction
 * write commit atomically per chunk, preserving the COBOL per-record consistency. The
 * {@code Account} entity carries a {@link jakarta.persistence.Version @Version} column, so a
 * concurrent modification surfaces as an optimistic-lock failure on {@code save} and fails the
 * chunk &mdash; matching the serialized rewrite of the mainframe program.</p>
 *
 * <h2>Fault handling</h2>
 * <p>Business-rule rejects are returned, never thrown. Genuine infrastructure faults propagate as
 * runtime exceptions and fail the chunk; an ABEND-class data fault (an unparseable origination
 * timestamp) is raised as a {@link FileProcessingException} &mdash; the structured-Java stand-in for
 * the COBOL {@code 9999-ABEND-PROGRAM} paragraph &mdash; and never terminates the JVM.</p>
 *
 * <p>This component is stateless and therefore thread-safe; it holds only its injected
 * collaborators and an immutable {@link Clock}.</p>
 */
@Component
public final class PostTransactionProcessor
        implements ItemProcessor<DailyTransaction, PostingResult> {

    /** Structured logger; emits the category-balance create notice (COBOL {@code DISPLAY}). */
    private static final Logger LOG = LoggerFactory.getLogger(PostTransactionProcessor.class);

    /** Scale of every monetary value ({@code PIC S9(n)V99} &rarr; two fraction digits). */
    private static final int MONEY_SCALE = 2;

    /** Rounding applied to every monetary arithmetic result (AAP &sect;0.8.2). */
    private static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_UP;

    /** Number of leading characters of {@code DALYTRAN-ORIG-TS} that carry the {@code YYYY-MM-DD} date. */
    private static final int ORIG_DATE_LENGTH = 10;

    /**
     * DB2 timestamp format {@code yyyy-MM-dd-HH.mm.ss.SSSSSS} (26 characters) produced by the COBOL
     * {@code Z-GET-DB2-FORMAT-TIMESTAMP} paragraph and stored in {@code TRAN-PROC-TS PIC X(26)}.
     */
    private static final DateTimeFormatter DB2_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HH.mm.ss.SSSSSS");

    /** XREF file lookup ({@code CBTRN02C} {@code XREF-FILE}); resolves a card number to an account. */
    private final CardXrefRepository cardXrefRepository;

    /** ACCOUNT file, {@code ACCESS I-O} ({@code CBTRN02C} {@code ACCOUNT-FILE}); read plus balance update. */
    private final AccountRepository accountRepository;

    /** TCATBAL file, {@code ACCESS I-O} ({@code CBTRN02C} {@code TCATBAL-FILE}); read/create/update. */
    private final TransactionCategoryBalanceRepository transactionCategoryBalanceRepository;

    /** Clock backing the processing-timestamp; overridable for deterministic tests. */
    private final Clock clock;

    /**
     * Primary (framework) constructor using constructor injection for the three repository
     * collaborators. The processing {@link Clock} defaults to {@link Clock#systemDefaultZone()}.
     *
     * @param cardXrefRepository                   the card cross-reference repository; must not be {@code null}
     * @param accountRepository                    the account repository; must not be {@code null}
     * @param transactionCategoryBalanceRepository the transaction-category-balance repository; must not be {@code null}
     */
    @Autowired
    public PostTransactionProcessor(
            CardXrefRepository cardXrefRepository,
            AccountRepository accountRepository,
            TransactionCategoryBalanceRepository transactionCategoryBalanceRepository) {
        this(cardXrefRepository, accountRepository, transactionCategoryBalanceRepository,
                Clock.systemDefaultZone());
    }

    /**
     * Fully specified constructor allowing an explicit {@link Clock} to be supplied so the
     * processing timestamp ({@code TRAN-PROC-TS}) is deterministic under test.
     *
     * @param cardXrefRepository                   the card cross-reference repository; must not be {@code null}
     * @param accountRepository                    the account repository; must not be {@code null}
     * @param transactionCategoryBalanceRepository the transaction-category-balance repository; must not be {@code null}
     * @param clock                                the clock backing the processing timestamp; must not be {@code null}
     */
    PostTransactionProcessor(
            CardXrefRepository cardXrefRepository,
            AccountRepository accountRepository,
            TransactionCategoryBalanceRepository transactionCategoryBalanceRepository,
            Clock clock) {
        this.cardXrefRepository =
                Objects.requireNonNull(cardXrefRepository, "cardXrefRepository must not be null");
        this.accountRepository =
                Objects.requireNonNull(accountRepository, "accountRepository must not be null");
        this.transactionCategoryBalanceRepository = Objects.requireNonNull(
                transactionCategoryBalanceRepository,
                "transactionCategoryBalanceRepository must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Posts a single daily transaction, reproducing the per-record body of {@code CBTRN02C}.
     *
     * <p>Validates the transaction ({@code 1500-VALIDATE-TRAN}); on any business-rule failure a
     * {@link PostingResult#rejected} outcome is returned with no side-effects. Otherwise the posted
     * {@link Transaction} is built ({@code 2000-POST-TRANSACTION}), the category balance is
     * created/updated ({@code 2700-UPDATE-TCATBAL}), the account balances are updated
     * ({@code 2800-UPDATE-ACCOUNT-REC}), and a {@link PostingResult#posted} outcome is returned for
     * the writer to persist ({@code 2900-WRITE-TRANSACTION-FILE}).</p>
     *
     * @param dt the daily-transaction staging row to post; must not be {@code null}
     * @return a posted or rejected {@link PostingResult}; never {@code null}
     * @throws FileProcessingException if the origination timestamp cannot be parsed (ABEND-class
     *                                 data fault; COBOL {@code 9999-ABEND-PROGRAM})
     */
    @Override
    public PostingResult process(DailyTransaction dt) {
        Objects.requireNonNull(dt, "daily transaction must not be null");

        // The signed transaction amount, normalised to scale 2 (COBOL DALYTRAN-AMT PIC S9(09)V99).
        final BigDecimal amount = scale2(dt.getDalytranAmt());

        // -------------------------------------------------------------------------------------
        // Step 1 - 1500-VALIDATE-TRAN: compute the reject reason; never throw for business rules.
        // -------------------------------------------------------------------------------------
        RejectReason reason = RejectReason.VALID;
        CardXref xref = null;
        Account account = null;

        // 1500-A-LOOKUP-XREF: resolve the card number to a cross-reference row.
        Optional<CardXref> xrefLookup = cardXrefRepository.findById(dt.getDalytranCardNum());
        if (xrefLookup.isEmpty()) {
            reason = RejectReason.INVALID_CARD_NUMBER;
        } else {
            xref = xrefLookup.get();

            // 1500-B-LOOKUP-ACCT (only reached while the reason is still VALID, as in the COBOL).
            Optional<Account> accountLookup = accountRepository.findById(xref.getXrefAcctId());
            if (accountLookup.isEmpty()) {
                reason = RejectReason.ACCOUNT_NOT_FOUND;
            } else {
                account = accountLookup.get();

                // Overlimit check (102): WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT
                // + DALYTRAN-AMT; OK iff ACCT-CREDIT-LIMIT >= WS-TEMP-BAL.
                BigDecimal wsTempBal = scale2(account.getAcctCurrCycCredit()
                        .subtract(account.getAcctCurrCycDebit())
                        .add(amount));
                if (account.getAcctCreditLimit().compareTo(wsTempBal) < 0) {
                    reason = RejectReason.OVERLIMIT;
                }

                // CBTRN02C 1500-B: 102 then 103 sequential; 103 overwrites 102 (parity)
                // Expiration check (103): OK iff ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS(1:10).
                LocalDate origDate = parseOriginationDate(dt);
                if (account.getAcctExpirationDate().isBefore(origDate)) {
                    reason = RejectReason.ACCOUNT_EXPIRED;
                }
            }
        }

        // -------------------------------------------------------------------------------------
        // Step 2 - branch on reason: a non-VALID reason rejects the record with no side-effects.
        // -------------------------------------------------------------------------------------
        if (reason != RejectReason.VALID) {
            return PostingResult.rejected(dt, reason);
        }

        // -------------------------------------------------------------------------------------
        // Step 3 - 2000-POST-TRANSACTION: build the posted transaction from the daily record.
        // -------------------------------------------------------------------------------------
        Transaction transaction = buildPostedTransaction(dt, amount);

        // -------------------------------------------------------------------------------------
        // Step 4 - 2700-UPDATE-TCATBAL: create or update the transaction-category balance.
        // -------------------------------------------------------------------------------------
        updateCategoryBalance(dt, xref, amount);

        // -------------------------------------------------------------------------------------
        // Step 5 - 2800-UPDATE-ACCOUNT-REC: update the account balances.
        // -------------------------------------------------------------------------------------
        updateAccountBalances(account, amount);

        // -------------------------------------------------------------------------------------
        // Step 6 - return the posted outcome (writer performs 2900-WRITE-TRANSACTION-FILE).
        // -------------------------------------------------------------------------------------
        return PostingResult.posted(dt, transaction);
    }

    /**
     * Builds the posted {@link Transaction} from the daily-transaction row
     * ({@code 2000-POST-TRANSACTION}): the field-for-field {@code MOVE} of every business field
     * ({@code CVTRA06Y} &rarr; {@code CVTRA05Y}) plus the processing timestamp.
     *
     * @param dt     the source daily transaction; must not be {@code null}
     * @param amount the transaction amount normalised to scale 2
     * @return the fully populated posted transaction
     */
    private Transaction buildPostedTransaction(DailyTransaction dt, BigDecimal amount) {
        Transaction transaction = new Transaction();
        transaction.setTranId(dt.getDalytranId());
        transaction.setTranTypeCd(dt.getDalytranTypeCd());
        transaction.setTranCatCd(dt.getDalytranCatCd());
        transaction.setTranSource(dt.getDalytranSource());
        transaction.setTranDesc(dt.getDalytranDesc());
        transaction.setTranAmt(amount);
        transaction.setTranMerchantId(dt.getDalytranMerchantId());
        transaction.setTranMerchantName(dt.getDalytranMerchantName());
        transaction.setTranMerchantCity(dt.getDalytranMerchantCity());
        transaction.setTranMerchantZip(dt.getDalytranMerchantZip());
        transaction.setTranCardNum(dt.getDalytranCardNum());
        transaction.setTranOrigTs(dt.getDalytranOrigTs());
        // Z-GET-DB2-FORMAT-TIMESTAMP: MOVE FUNCTION CURRENT-DATE -> DB2 timestamp -> TRAN-PROC-TS.
        transaction.setTranProcTs(LocalDateTime.now(clock).format(DB2_TIMESTAMP_FORMAT));
        return transaction;
    }

    /**
     * Creates or updates the transaction-category balance ({@code 2700-UPDATE-TCATBAL}).
     *
     * <p>The composite key is {@code (XREF-ACCT-ID, DALYTRAN-TYPE-CD, DALYTRAN-CAT-CD)}. When no
     * row exists the COBOL sets {@code WS-CREATE-TRANCAT-REC = 'Y'} (INVALID KEY / status
     * {@code '23'}) and {@code 2700-A-CREATE-TCATBAL-REC} initialises a new record whose balance is
     * the transaction amount; otherwise {@code 2700-B-UPDATE-TCATBAL-REC} adds the amount to the
     * existing balance. The result is persisted via {@code save}.</p>
     *
     * @param dt     the source daily transaction; must not be {@code null}
     * @param xref   the resolved card cross-reference (supplies the account id); must not be {@code null}
     * @param amount the transaction amount normalised to scale 2
     */
    private void updateCategoryBalance(DailyTransaction dt, CardXref xref, BigDecimal amount) {
        TransactionCategoryBalanceId key = new TransactionCategoryBalanceId(
                xref.getXrefAcctId(), dt.getDalytranTypeCd(), dt.getDalytranCatCd());

        Optional<TransactionCategoryBalance> existing =
                transactionCategoryBalanceRepository.findById(key);

        TransactionCategoryBalance balance;
        if (existing.isEmpty()) {
            // 2700-A-CREATE-TCATBAL-REC: INITIALIZE then ADD DALYTRAN-AMT TO TRAN-CAT-BAL (= amount).
            LOG.debug("TCATBAL record not found for key {} - creating", key);
            balance = new TransactionCategoryBalance();
            balance.setId(key);
            balance.setTranCatBal(amount);
        } else {
            // 2700-B-UPDATE-TCATBAL-REC: ADD DALYTRAN-AMT TO TRAN-CAT-BAL.
            balance = existing.get();
            balance.setTranCatBal(scale2(balance.getTranCatBal().add(amount)));
        }
        transactionCategoryBalanceRepository.save(balance);
    }

    /**
     * Updates the account balances ({@code 2800-UPDATE-ACCOUNT-REC}).
     *
     * <p>Adds the transaction amount to {@code ACCT-CURR-BAL}; then, matching
     * {@code IF DALYTRAN-AMT >= 0 ADD TO ACCT-CURR-CYC-CREDIT ELSE ADD TO ACCT-CURR-CYC-DEBIT}, the
     * signed amount is added to the current-cycle credit total when non-negative and to the
     * current-cycle debit total otherwise. The managed account is persisted via {@code save}.</p>
     *
     * <p><strong>Parity note.</strong> The COBOL maps a {@code REWRITE} INVALID KEY to reject code
     * {@code 109} ({@link RejectReason#ACCOUNT_NOT_FOUND_ON_UPDATE}). In the JPA model the account
     * was just read as a managed entity within the chunk transaction, so a missing-account rewrite
     * cannot occur; a concurrent modification instead surfaces as an optimistic-lock failure on
     * {@code save} (the {@code @Version} column) and correctly fails the chunk. Code {@code 109} is
     * therefore intentionally not modelled here.</p>
     *
     * @param account the managed account to update; must not be {@code null}
     * @param amount  the transaction amount normalised to scale 2
     */
    private void updateAccountBalances(Account account, BigDecimal amount) {
        account.setAcctCurrBal(scale2(account.getAcctCurrBal().add(amount)));
        if (amount.signum() >= 0) {
            account.setAcctCurrCycCredit(scale2(account.getAcctCurrCycCredit().add(amount)));
        } else {
            account.setAcctCurrCycDebit(scale2(account.getAcctCurrCycDebit().add(amount)));
        }
        accountRepository.save(account);
    }

    /**
     * Parses the {@code YYYY-MM-DD} origination date from the first {@value #ORIG_DATE_LENGTH}
     * characters of {@code DALYTRAN-ORIG-TS}.
     *
     * <p>The COBOL expiration check compares {@code ACCT-EXPIRAION-DATE} against
     * {@code DALYTRAN-ORIG-TS(1:10)}. The migrated check parses that substring to a
     * {@link LocalDate}; an unparseable or truncated value is an ABEND-class data fault and is
     * raised as a {@link FileProcessingException} (COBOL {@code 9999-ABEND-PROGRAM}) rather than
     * terminating the JVM.</p>
     *
     * @param dt the source daily transaction; must not be {@code null}
     * @return the parsed origination date
     * @throws FileProcessingException if the origination timestamp is {@code null}, too short, or not
     *                                 a valid ISO date
     */
    private LocalDate parseOriginationDate(DailyTransaction dt) {
        String origTs = dt.getDalytranOrigTs();
        if (origTs == null || origTs.length() < ORIG_DATE_LENGTH) {
            throw new FileProcessingException(
                    "Unparseable DALYTRAN-ORIG-TS for transaction " + dt.getDalytranId()
                            + ": '" + origTs + "'");
        }
        try {
            return LocalDate.parse(origTs.substring(0, ORIG_DATE_LENGTH));
        } catch (DateTimeParseException ex) {
            throw new FileProcessingException(
                    "Unparseable DALYTRAN-ORIG-TS for transaction " + dt.getDalytranId()
                            + ": '" + origTs + "'", ex);
        }
    }

    /**
     * Normalises a monetary value to the fixed money scale ({@value #MONEY_SCALE}) using
     * {@link RoundingMode#HALF_UP}, reproducing the COBOL {@code PIC S9(n)V99} truncation/rounding
     * at each computation (AAP &sect;0.8.2). {@code float}/{@code double} are never involved.
     *
     * @param value the value to normalise; must not be {@code null}
     * @return the value rescaled to {@value #MONEY_SCALE} fraction digits
     */
    private static BigDecimal scale2(BigDecimal value) {
        return value.setScale(MONEY_SCALE, MONEY_ROUNDING);
    }
}


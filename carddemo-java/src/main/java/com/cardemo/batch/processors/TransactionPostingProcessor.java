package com.cardemo.batch.processors;

import com.cardemo.model.dto.PostedTransactionResult;
import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.DailyTransaction;
import com.cardemo.model.entity.Transaction;
import com.cardemo.model.enums.RejectCode;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardCrossReferenceRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Spring Batch {@link ItemProcessor} that reproduces the per-record
 * <strong>validation cascade</strong> and <strong>field mapping</strong> of the
 * legacy AWS CardDemo batch program <strong>{@code CBTRN02C}</strong> (Daily
 * Transaction Posting) in the greenfield Java&nbsp;25 LTS + Spring Boot&nbsp;3.5.x
 * migration.
 *
 * <h2>Provenance</h2>
 * <p>Translated from COBOL {@code app/cbl/CBTRN02C.cbl} at the frozen legacy
 * baseline commit SHA {@code 27d6c6f}. The COBOL source is <strong>read-only</strong>
 * reference material and is <strong>never copied</strong> into this repository;
 * traceability is by commit SHA only (AAP &sect;0.7.2). Per the <strong>Minimal
 * Change Clause</strong> (AAP &sect;0.7.1) this migration reproduces the COBOL
 * behaviour <em>exactly</em> &mdash; no business-rule "improvements", no extra
 * validations, no feature additions &mdash; and documents every technology
 * substitution at its point of use. The application base package is
 * {@code com.cardemo} (decision D-006).
 *
 * <h2>What this processor reproduces ({@code CBTRN02C} main loop)</h2>
 * <p>For each daily-transaction record read from the {@code DALYTRAN} staging
 * file, {@code CBTRN02C} clears {@code WS-VALIDATION-FAIL-REASON} to zero, runs
 * {@code 1500-VALIDATE-TRAN}, and then branches: a zero reason routes the record
 * to {@code 2000-POST-TRANSACTION}; a non-zero reason routes it to
 * {@code 2500-WRITE-REJECT-REC}. This processor performs exactly the
 * <em>validate</em> and <em>map</em> halves of that loop and returns a
 * {@link PostedTransactionResult} carrier that tells the downstream writers which
 * branch to take.</p>
 *
 * <h3>The 4-step validation cascade ({@code 1500-VALIDATE-TRAN})</h3>
 * <ol>
 *   <li><strong>{@code 1500-A-LOOKUP-XREF} (reject {@code 100}).</strong> Resolve
 *       the card cross-reference by card number. COBOL {@code READ XREF-FILE ...
 *       INVALID KEY} &rarr; {@link CardCrossReferenceRepository#findById(Object)};
 *       an empty result yields {@link RejectCode#INVALID_CARD_NUMBER}.</li>
 *   <li><strong>{@code 1500-B-LOOKUP-ACCT} (reject {@code 101}) &mdash; guarded.</strong>
 *       Only when step&nbsp;1 left the reason clear, resolve the account by the
 *       cross-reference's account id. An empty result yields
 *       {@link RejectCode#ACCOUNT_NOT_FOUND}.</li>
 *   <li><strong>Credit-limit check (reject {@code 102}).</strong> When the account
 *       is found, reject if {@code ACCT-CREDIT-LIMIT < (ACCT-CURR-CYC-CREDIT -
 *       ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT)} &rarr;
 *       {@link RejectCode#OVERLIMIT_TRANSACTION}.</li>
 *   <li><strong>Expiration check (reject {@code 103}).</strong> Also when the
 *       account is found, reject if the account expiration date precedes the
 *       transaction's origination date &rarr;
 *       {@link RejectCode#TRANSACTION_AFTER_EXPIRATION}.</li>
 * </ol>
 * <p><strong>Parity nuance &mdash; two independent sequential IFs, last-failure
 * wins.</strong> In {@code 1500-B-LOOKUP-ACCT} the credit-limit {@code IF} and the
 * expiration {@code IF} are <em>both</em> evaluated whenever the account exists
 * (they are not chained with {@code ELSE}). Consequently, if both fail, code
 * {@code 103} <strong>overwrites</strong> code {@code 102}. This class reproduces
 * that with two consecutive {@code if} statements (never {@code else if}), so the
 * surviving reason matches COBOL byte-for-byte. Only reject codes
 * {@code 100}, {@code 101}, {@code 102} and {@code 103} are produced here; code
 * {@code 109} belongs to the writer's {@code 2800} rewrite path and codes
 * {@code 104}-{@code 108} do not exist in the COBOL source.</p>
 *
 * <h2>"Reject &amp; continue" batch semantics (no throw)</h2>
 * <p>{@code CBTRN02C} never abends on a business reject: it writes a reject record
 * and continues, and sets {@code RETURN-CODE = 4} only at end-of-job when
 * {@code WS-REJECT-COUNT > 0}. This processor preserves that by <strong>returning
 * the carrier with the failing {@link RejectCode}</strong> on a reject &mdash; it
 * never throws and never returns {@code null}. (Returning {@code null} from a
 * Spring Batch {@code ItemProcessor} <em>filters</em> the item out of the chunk,
 * which would silently drop a reject that must be written to the reject sink.)
 * The typed exceptions {@link com.cardemo.exception.CreditLimitExceededException}
 * ({@code REJECT_CODE == 102}) and
 * {@link com.cardemo.exception.ExpiredCardException} ({@code REJECT_CODE == 103})
 * exist for the online/REST path ({@code AccountUpdateService} et al.); the batch
 * path here deliberately uses the {@link RejectCode} carrier <em>instead</em> of
 * throwing them, so the equivalence is by value (102/103), not by control flow.</p>
 *
 * <h2>Processor / writer boundary (do not cross)</h2>
 * <p>This processor only <em>validates</em> and <em>maps</em>. The COBOL
 * persistence paragraphs {@code 2700-UPDATE-TCATBAL} (category-balance upsert),
 * {@code 2800-UPDATE-ACCOUNT-REC} (account cycle-credit/debit + balance rewrite,
 * the source of reject {@code 109}) and {@code 2900-WRITE-TRANSACTION-FILE}
 * (transaction insert) are intentionally the responsibility of the
 * {@code com.cardemo.batch.writers} layer. No database writes, {@code save}s,
 * {@code REWRITE}s or the code-{@code 109} path are performed here. The resolved
 * {@link Account} and {@link CardCrossReference} are passed through on the carrier
 * so the writer can perform those updates <em>without re-reading</em>, matching
 * the COBOL reuse of the in-memory {@code ACCOUNT-RECORD}/{@code CARD-XREF-RECORD}.</p>
 *
 * <h2>Technology substitutions (documented per Minimal Change Clause)</h2>
 * <ul>
 *   <li>VSAM keyed {@code READ ... INVALID KEY} &rarr;
 *       {@link Optional}-returning {@code JpaRepository.findById}; "invalid key"
 *       &rarr; {@link Optional#isEmpty()}.</li>
 *   <li>{@code WS-VALIDATION-FAIL-REASON PIC 9(04)} accumulator &rarr; a local
 *       {@link RejectCode} (initialised to {@link RejectCode#NONE}, the zero
 *       sentinel).</li>
 *   <li>{@code COMP-3}/{@code PIC S9(n)V99} money &rarr; {@link BigDecimal};
 *       comparisons use {@link BigDecimal#compareTo(BigDecimal)} (never
 *       {@code equals}), and no {@code float}/{@code double} appears anywhere
 *       (AAP &sect;0.7.3).</li>
 *   <li>{@code FUNCTION CURRENT-DATE} / {@code Z-GET-DB2-FORMAT-TIMESTAMP} &rarr;
 *       {@code java.time} (see {@link #db2FormatTimestamp(LocalDateTime)}).</li>
 *   <li><strong>Date-typed fields.</strong> The migrated entities store the
 *       expiration date as {@link java.time.LocalDate} and the timestamps as
 *       {@link LocalDateTime} (not the raw {@code PIC X(10)}/{@code PIC X(26)}
 *       text). The expiration comparison is therefore performed on
 *       {@link java.time.LocalDate} values, which orders chronologically and
 *       hence <em>identically</em> to COBOL's lexicographic comparison of the
 *       {@code yyyy-MM-dd} strings &mdash; preserving behaviour exactly. The COBOL
 *       {@code CEEDAYS}-style date math is intentionally <em>not</em> used on this
 *       path, and {@code DateValidationService} is intentionally <em>not</em>
 *       invoked (the COBOL does not call it here).</li>
 * </ul>
 *
 * <h2>Thread-safety</h2>
 * <p>The processor is stateless apart from its injected, immutable collaborators
 * (two repositories and a {@link Clock}), so a single Spring-managed singleton is
 * safe to share across batch threads. The validation cascade keeps all mutable
 * state in method-local variables.</p>
 *
 * @see PostedTransactionResult
 * @see RejectCode
 * @see ItemProcessor
 */
@Component
public class TransactionPostingProcessor
        implements ItemProcessor<DailyTransaction, PostedTransactionResult> {

    /**
     * Date/time portion of the DB2 timestamp format used by
     * {@code Z-GET-DB2-FORMAT-TIMESTAMP}: {@code yyyy-MM-dd-HH.mm.ss.} (note the
     * hyphen between date and time and the dot separators within the time), up to
     * and including the trailing dot that precedes the fractional seconds.
     */
    // COBOL Z-GET-DB2-FORMAT-TIMESTAMP layout: EEEE-MM-DD-UU.MM.SS. + fraction.
    private static final DateTimeFormatter DB2_TIMESTAMP_BASE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HH.mm.ss.");

    /**
     * Full 26-character DB2 timestamp pattern including six fractional-second
     * digits. Used to parse the string produced by
     * {@link #db2FormatTimestamp(LocalDateTime)} back into a {@link LocalDateTime}
     * so the stored {@code TRAN-PROC-TS} carries exactly the COBOL hundredths
     * precision (the trailing {@code "0000"} keeps the remaining digits zero).
     */
    private static final DateTimeFormatter DB2_TIMESTAMP_FULL =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HH.mm.ss.SSSSSS");

    /**
     * Cross-reference repository &mdash; the JPA replacement for the keyed
     * {@code READ XREF-FILE} of {@code 1500-A-LOOKUP-XREF}. Typed
     * {@code JpaRepository<CardCrossReference, String>} because the cross-reference
     * key is the 16-character card number.
     */
    private final CardCrossReferenceRepository crossReferenceRepository;

    /**
     * Account repository &mdash; the JPA replacement for the keyed
     * {@code READ ACCOUNT-FILE} of {@code 1500-B-LOOKUP-ACCT}. Typed
     * {@code JpaRepository<Account, Long>} because the account key is the
     * eleven-digit {@code ACCT-ID}.
     */
    private final AccountRepository accountRepository;

    /**
     * Clock backing the {@code TRAN-PROC-TS} processing timestamp. Defaults to the
     * system clock in production and is injectable so tests can pin a fixed instant
     * and assert the posted {@code processed_timestamp} deterministically. This is
     * the {@code java.time} replacement for the implicit system clock read by
     * COBOL's {@code FUNCTION CURRENT-DATE}.
     */
    private final Clock clock;

    /**
     * Production constructor used by Spring for component injection. Uses the
     * system-default-zone {@link Clock} so {@code TRAN-PROC-TS} is stamped from the
     * current time, exactly as {@code FUNCTION CURRENT-DATE} does on the mainframe.
     *
     * @param crossReferenceRepository the card cross-reference repository (must not
     *                                 be {@code null})
     * @param accountRepository        the account repository (must not be
     *                                 {@code null})
     */
    @Autowired
    public TransactionPostingProcessor(CardCrossReferenceRepository crossReferenceRepository,
                                       AccountRepository accountRepository) {
        this(crossReferenceRepository, accountRepository, Clock.systemDefaultZone());
    }

    /**
     * Test-friendly constructor allowing a fixed {@link Clock} so the generated
     * {@code TRAN-PROC-TS} is deterministic. Behaves identically to the production
     * constructor in every other respect.
     *
     * @param crossReferenceRepository the card cross-reference repository (must not
     *                                 be {@code null})
     * @param accountRepository        the account repository (must not be
     *                                 {@code null})
     * @param clock                    the clock used to stamp the processing
     *                                 timestamp (must not be {@code null})
     */
    public TransactionPostingProcessor(CardCrossReferenceRepository crossReferenceRepository,
                                       AccountRepository accountRepository,
                                       Clock clock) {
        this.crossReferenceRepository = crossReferenceRepository;
        this.accountRepository = accountRepository;
        this.clock = clock;
    }

    /**
     * Validates and maps a single daily-transaction record, reproducing the
     * {@code 1500-VALIDATE-TRAN} cascade and the {@code 2000-POST-TRANSACTION}
     * field mapping of {@code CBTRN02C}, and returns the routing carrier consumed
     * by the posting/reject writers.
     *
     * <p>The local {@code reason} accumulator mirrors COBOL
     * {@code WS-VALIDATION-FAIL-REASON}, which the main loop initialises to zero
     * ({@link RejectCode#NONE}) before each record. The cascade preserves the COBOL
     * short-circuit structure exactly:</p>
     * <ul>
     *   <li>{@code 1500-A-LOOKUP-XREF} always runs first; a missing cross-reference
     *       sets reject {@code 100} and the account lookup is skipped (COBOL guards
     *       {@code 1500-B} with {@code IF WS-VALIDATION-FAIL-REASON = 0}).</li>
     *   <li>{@code 1500-B-LOOKUP-ACCT} runs only when the cross-reference was found;
     *       a missing account sets reject {@code 101}. When the account is found the
     *       two credit/expiration checks run as <strong>independent sequential</strong>
     *       {@code if} statements, so a {@code 103} expiration failure overwrites a
     *       {@code 102} over-limit failure (last-failure-wins), exactly as COBOL.</li>
     * </ul>
     *
     * <p>On acceptance ({@code reason == }{@link RejectCode#NONE}) the record is
     * mapped to a {@link Transaction} via {@link #mapPostedTransaction(DailyTransaction)}
     * and returned with the resolved account and cross-reference. On rejection the
     * carrier is returned with the failing {@link RejectCode} and whatever records
     * had been resolved (which may be {@code null} for early rejects) &mdash; the
     * item is <strong>never</strong> dropped (never {@code null}) and
     * <strong>never</strong> throws, preserving "reject &amp; continue".</p>
     *
     * @param item the daily-transaction staging record supplied by the reader;
     *             non-{@code null} per the Spring Batch chunk contract
     * @return a non-{@code null} {@link PostedTransactionResult}: accepted (with the
     *         mapped transaction) or rejected (with the failing reason)
     */
    @Override
    public PostedTransactionResult process(DailyTransaction item) {
        // WS-VALIDATION-FAIL-REASON accumulator: COBOL MOVE 0 TO WS-VALIDATION-FAIL-REASON
        // before each record -> RejectCode.NONE is the zero "no failure" sentinel.
        RejectCode reason = RejectCode.NONE;

        // Records resolved during validation, carried to the writer so 2700/2800 can
        // reuse them WITHOUT re-reading (mirrors COBOL's in-memory CARD-XREF-RECORD /
        // ACCOUNT-RECORD). They stay null until/unless resolved, so early rejects
        // (100/101) naturally carry null exactly as the prompt's contract requires.
        CardCrossReference crossReference = null;
        Account account = null;

        // --- Step 1: 1500-A-LOOKUP-XREF (reject 100) -----------------------------
        // COBOL: MOVE DALYTRAN-CARD-NUM TO FD-XREF-CARD-NUM; READ XREF-FILE ... INVALID KEY.
        // Substitution: keyed VSAM READ -> JpaRepository.findById; INVALID KEY -> Optional.isEmpty().
        final Optional<CardCrossReference> xrefLookup =
                crossReferenceRepository.findById(item.getDalytranCardNum());
        if (xrefLookup.isEmpty()) {
            // MOVE 100 TO WS-VALIDATION-FAIL-REASON ('INVALID CARD NUMBER FOUND').
            reason = RejectCode.INVALID_CARD_NUMBER;
        } else {
            // NOT INVALID KEY: keep CARD-XREF-RECORD (yields XREF-ACCT-ID for step 2).
            crossReference = xrefLookup.get();

            // --- Step 2: 1500-B-LOOKUP-ACCT (reject 101) -- guarded by reason == NONE ---
            // COBOL performs 1500-B only when 1500-A left WS-VALIDATION-FAIL-REASON = 0.
            // COBOL: MOVE XREF-ACCT-ID TO FD-ACCT-ID; READ ACCOUNT-FILE ... INVALID KEY.
            final Optional<Account> accountLookup =
                    accountRepository.findById(crossReference.getXrefAcctId());
            if (accountLookup.isEmpty()) {
                // MOVE 101 TO WS-VALIDATION-FAIL-REASON ('ACCOUNT RECORD NOT FOUND').
                reason = RejectCode.ACCOUNT_NOT_FOUND;
            } else {
                // NOT INVALID KEY: keep ACCOUNT-RECORD for the credit/expiration checks.
                account = accountLookup.get();

                // COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT
                // (COBOL S9(09)V99, scale 2, evaluated left-to-right). CRITICAL: the basis is the
                // cycle credit MINUS cycle debit PLUS the transaction amount -- NOT acctCurrBal.
                // All BigDecimal; no float/double (AAP §0.7.3).
                final BigDecimal tempBal = account.getAcctCurrCycCredit()
                        .subtract(account.getAcctCurrCycDebit())
                        .add(item.getDalytranAmt());

                // First IF (reject 102): COBOL IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL CONTINUE ELSE 102.
                // Reject when creditLimit < tempBal. compareTo (never equals) per AAP §0.7.3.
                if (account.getAcctCreditLimit().compareTo(tempBal) < 0) {
                    // MOVE 102 TO WS-VALIDATION-FAIL-REASON ('OVERLIMIT TRANSACTION').
                    reason = RejectCode.OVERLIMIT_TRANSACTION;
                }

                // Second IF (reject 103) -- runs REGARDLESS of the first (two independent IFs,
                // NOT else-if): COBOL IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS(1:10) CONTINUE ELSE 103.
                // ACCT-EXPIRAION-DATE (PIC X(10) 'yyyy-MM-dd') and DALYTRAN-ORIG-TS(1:10) (first 10
                // chars of the 26-char timestamp) are compared as raw date strings by COBOL.
                // Here the entities store these as LocalDate / LocalDateTime, so the faithful
                // equivalent compares the LocalDate values: LocalDate.compareTo orders chronologically,
                // which is IDENTICAL to the lexicographic ordering of the zero-padded yyyy-MM-dd ISO
                // strings -> behaviour preserved exactly. CEEDAYS-style date math is NOT used here and
                // DateValidationService is intentionally NOT invoked (the COBOL does not call it on
                // this path). Reject when the expiration date precedes the transaction's origination date.
                if (account.getAcctExpirationDate()
                        .compareTo(item.getDalytranOrigTs().toLocalDate()) < 0) {
                    // MOVE 103 TO WS-VALIDATION-FAIL-REASON ('TRANSACTION RECEIVED AFTER ACCT EXPIRATION').
                    // Overwrites a 102 set just above when BOTH checks fail (last-failure-wins).
                    reason = RejectCode.TRANSACTION_AFTER_EXPIRATION;
                }
            }
        }

        // Route exactly as the COBOL main loop: WS-VALIDATION-FAIL-REASON = 0 -> 2000-POST-TRANSACTION;
        // otherwise -> 2500-WRITE-REJECT-REC (and continue).
        if (reason == RejectCode.NONE) {
            final Transaction posted = mapPostedTransaction(item);
            return new PostedTransactionResult(posted, RejectCode.NONE, item, account, crossReference);
        }

        // Reject & continue: carry the failing reason (NEVER null -> a null return would make Spring
        // Batch FILTER the item, silently dropping a reject that must reach the reject writer).
        // account/crossReference may be null for early rejects (100/101); they are populated for 102/103.
        return new PostedTransactionResult(null, reason, item, account, crossReference);
    }

    /**
     * Reproduces {@code 2000-POST-TRANSACTION}'s 13 field {@code MOVE}s, building
     * the posted {@link Transaction} from the accepted {@link DailyTransaction}.
     *
     * <p>Twelve fields are copied verbatim from their {@code dalytran*} analogues;
     * {@code TRAN-ORIG-TS} is copied <strong>as-is</strong> (not re-stamped), and
     * only {@code TRAN-PROC-TS} is freshly generated from the current time
     * ({@code PERFORM Z-GET-DB2-FORMAT-TIMESTAMP}). The monetary amount stays a
     * {@link BigDecimal} with its scale untouched (no rounding/conversion,
     * AAP &sect;0.7.3).</p>
     *
     * <p>This method performs <strong>no persistence</strong>. The COBOL
     * {@code 2700}/{@code 2800}/{@code 2900} updates are the writer's
     * responsibility (see the class Javadoc's processor/writer boundary note).</p>
     *
     * @param item the accepted daily-transaction record
     * @return the mapped {@link Transaction} ready for the posting writer
     */
    private Transaction mapPostedTransaction(DailyTransaction item) {
        final Transaction tran = new Transaction();
        // 2000-POST-TRANSACTION field MOVEs (CBTRN02C lines 425-438), in COBOL order:
        tran.setTranId(item.getDalytranId());                      // MOVE DALYTRAN-ID            TO TRAN-ID
        tran.setTranTypeCd(item.getDalytranTypeCd());              // MOVE DALYTRAN-TYPE-CD       TO TRAN-TYPE-CD
        tran.setTranCatCd(item.getDalytranCatCd());                // MOVE DALYTRAN-CAT-CD        TO TRAN-CAT-CD
        tran.setTranSource(item.getDalytranSource());              // MOVE DALYTRAN-SOURCE        TO TRAN-SOURCE
        tran.setTranDesc(item.getDalytranDesc());                  // MOVE DALYTRAN-DESC          TO TRAN-DESC
        tran.setTranAmt(item.getDalytranAmt());                    // MOVE DALYTRAN-AMT           TO TRAN-AMT (BigDecimal, scale preserved)
        tran.setTranMerchantId(item.getDalytranMerchantId());      // MOVE DALYTRAN-MERCHANT-ID   TO TRAN-MERCHANT-ID
        tran.setTranMerchantName(item.getDalytranMerchantName());  // MOVE DALYTRAN-MERCHANT-NAME TO TRAN-MERCHANT-NAME
        tran.setTranMerchantCity(item.getDalytranMerchantCity());  // MOVE DALYTRAN-MERCHANT-CITY TO TRAN-MERCHANT-CITY
        tran.setTranMerchantZip(item.getDalytranMerchantZip());    // MOVE DALYTRAN-MERCHANT-ZIP  TO TRAN-MERCHANT-ZIP
        tran.setTranCardNum(item.getDalytranCardNum());            // MOVE DALYTRAN-CARD-NUM      TO TRAN-CARD-NUM
        tran.setTranOrigTs(item.getDalytranOrigTs());              // MOVE DALYTRAN-ORIG-TS       TO TRAN-ORIG-TS (as-is)
        tran.setTranProcTs(nextProcessingTimestamp());             // PERFORM Z-GET-DB2-FORMAT-TIMESTAMP; MOVE DB2-FORMAT-TS TO TRAN-PROC-TS
        return tran;
    }

    /**
     * Produces the fresh {@code TRAN-PROC-TS} processing timestamp, reproducing
     * {@code Z-GET-DB2-FORMAT-TIMESTAMP} on the current {@link #clock} time.
     *
     * <p>The COBOL paragraph builds a 26-character DB2-format string from
     * {@code FUNCTION CURRENT-DATE} keeping hundredths-of-second precision and
     * zero-padding the remaining fractional digits. Because the migrated
     * {@link Transaction#setTranProcTs(LocalDateTime)} stores a
     * {@link LocalDateTime}, this method builds that exact 26-character rendering
     * via {@link #db2FormatTimestamp(LocalDateTime)} and parses it back, so the
     * persisted value carries precisely the COBOL hundredths precision (the rest
     * zeroed) and round-trips to the identical external rendering downstream.</p>
     *
     * @return the processing timestamp truncated to COBOL hundredths precision
     */
    private LocalDateTime nextProcessingTimestamp() {
        // FUNCTION CURRENT-DATE -> java.time (the Clock is injectable for deterministic tests).
        final LocalDateTime now = LocalDateTime.now(clock);
        return LocalDateTime.parse(db2FormatTimestamp(now), DB2_TIMESTAMP_FULL);
    }

    /**
     * Renders a {@link LocalDateTime} into the 26-character DB2 timestamp string
     * produced by COBOL {@code Z-GET-DB2-FORMAT-TIMESTAMP}:
     * {@code yyyy-MM-dd-HH.mm.ss.} + a 2-digit hundredths-of-second value
     * ({@code COB-MIL}) + the literal {@code "0000"} ({@code COB-REST}), for
     * example {@code 2022-07-19-23.12.32.680000}.
     *
     * <p>The hundredths are computed as {@code nano / 10_000_000} (0&ndash;99),
     * reproducing the COBOL {@code COB-MIL PIC X(02)} hundredths field, and the
     * trailing {@code "0000"} reproduces the {@code COB-REST}/{@code DB2-REST}
     * literal so the fractional portion is always six digits (26 characters
     * total). This mirrors {@code MOVE COB-MIL TO DB2-MIL} followed by
     * {@code MOVE '0000' TO DB2-REST}.</p>
     *
     * @param now the timestamp to render (typically {@link LocalDateTime#now(Clock)})
     * @return the 26-character DB2-format timestamp string
     */
    private static String db2FormatTimestamp(LocalDateTime now) {
        // COB-MIL: hundredths-of-second (2 digits); COB-REST: literal '0000'.
        final int hundredths = now.getNano() / 10_000_000;
        return DB2_TIMESTAMP_BASE.format(now) + String.format("%02d", hundredths) + "0000";
    }
}

package com.cardemo.service.billing;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cardemo.exception.DuplicateRecordException;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.BillPaymentRequest;
import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Transaction;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.TransactionRepository;

/**
 * Bill-payment service &mdash; the Java&nbsp;25 / Spring&nbsp;Boot&nbsp;3.x translation of the online
 * CICS program <strong>{@code app/cbl/COBIL00C.cbl}</strong> (CICS transaction {@code CB00}, BMS
 * mapset {@code COBIL00}). Bill payment is feature <strong>F-009</strong> of the preserved CardDemo
 * estate: it pays an account's balance <em>in full</em> in a single unit of work &mdash; it generates
 * one payment {@code Transaction} for the full current balance and decrements the {@code Account}
 * balance to zero.
 *
 * <h2>Behavioral-parity contract (AAP &sect;0.7.2)</h2>
 * <p>This class reproduces the observable behavior of {@code COBIL00C}'s {@code PROCESS-ENTER-KEY}
 * paragraph and its file-I/O paragraphs ({@code READ-ACCTDAT-FILE}, {@code READ-CXACAIX-FILE}, the
 * {@code STARTBR}/{@code READPREV}/{@code ENDBR} browse-to-end, {@code WRITE-TRANSACT-FILE} and
 * {@code UPDATE-ACCTDAT-FILE}) <em>exactly</em> &mdash; the same validation order, the same verbatim
 * messages, the same transaction constants and the same pay-the-full-balance arithmetic. Per the
 * Minimal Change Clause (AAP &sect;0.7.1) nothing is added, enhanced or optimized beyond the
 * technology transition. The COBOL is read-only reference material at the frozen baseline commit SHA
 * {@code 27d6c6f} and is never copied into this repository &mdash; only its behavior is reproduced.</p>
 *
 * <h2>Service / controller boundary (layered architecture, AAP &sect;0.3.3)</h2>
 * <p>{@code COBIL00C}'s {@code MAIN-PARA} CICS pseudo-conversational plumbing is deliberately
 * <strong>excluded</strong> from this service: the {@code EIBCALEN = 0} sign-on redirect is a Spring
 * Security concern; the {@code EVALUATE EIBAID} PF-key dispatch and {@code XCTL} navigation are
 * controller routing; and {@code SEND}/{@code RECEIVE MAP}, the {@code COBIL0AI}/{@code COBIL0AO}
 * symbolic-map fields, cursor positioning and {@code COMMAREA} threading are controller + DTO
 * concerns. This service receives an already-bound {@link BillPaymentRequest} from
 * {@code BillingController} ({@code POST /api/billing/pay}) and returns a
 * {@link BillPaymentRequest.Response}; it performs only the data-centric business logic.</p>
 *
 * <h2>Technology substitutions (documented at each point of change, AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li><strong>VSAM KSDS keyed read {@code READ-ACCTDAT-FILE} &rarr; {@link AccountRepository#findById(Object)}.</strong>
 *       A {@code DFHRESP(NOTFND)} becomes a {@link RecordNotFoundException} (HTTP&nbsp;404).</li>
 *   <li><strong>{@code CXACAIX} alternate-index read {@code READ-CXACAIX-FILE} &rarr;
 *       {@link CardCrossReferenceRepository#findByXrefAcctId(Long)}.</strong> The non-unique index
 *       returns a {@link List}; the COBOL read the first matching record, so this takes
 *       {@code get(0)}. An empty result is the {@code DFHRESP(NOTFND)} path &rarr; 404.</li>
 *   <li><strong>{@code MOVE HIGH-VALUES TO TRAN-ID} + {@code STARTBR}/{@code READPREV}/{@code ENDBR} +
 *       {@code ADD 1} &rarr; {@link TransactionRepository#findMaxTransactionId()}{@code  + 1}.</strong>
 *       The browse-to-end-of-{@code TRANSACT} id discovery becomes a single max-key query; the
 *       {@code +1} increment and 16-digit zero-pad ({@code PIC 9(16)}) are performed here, not in the
 *       repository. An empty table ({@code ENDFILE} &rarr; zeros) is {@code Optional.empty()} &rarr;
 *       start from zero.</li>
 *   <li><strong>CICS {@code ASKTIME}/{@code FORMATTIME} &rarr; {@link LocalDateTime}.</strong> See
 *       {@link #processBillPayment(BillPaymentRequest)} for the whole-second truncation note.</li>
 *   <li><strong>VSAM {@code WRITE}/{@code REWRITE} &rarr; {@code save}/{@code saveAndFlush}.</strong>
 *       A {@code DFHRESP(DUPKEY)}/{@code DFHRESP(DUPREC)} on the transaction write becomes a
 *       {@link DuplicateRecordException} (HTTP&nbsp;409); the account {@code REWRITE} honors JPA
 *       {@code @Version} optimistic locking automatically.</li>
 *   <li><strong>Dual-update unit of work &rarr; {@link Transactional @Transactional}.</strong> The
 *       transaction insert and the account balance update commit or roll back together.</li>
 * </ul>
 *
 * <h2>No messaging</h2>
 * <p>Unlike {@code CORPT00C} (the sole online&rarr;batch bridge, migrated to
 * {@code ReportSubmissionService} with an SQS publish), {@code COBIL00C} has <strong>no</strong> CICS
 * TDQ {@code WRITEQ} and therefore no SQS/SNS involvement: bill payment is a purely synchronous
 * database operation.</p>
 *
 * <h2>Decimal fidelity (AAP &sect;0.7.3)</h2>
 * <p>Every monetary value is a {@link BigDecimal} of scale 2 &mdash; <strong>never</strong>
 * {@code float} or {@code double}. Balance tests use {@link BigDecimal#compareTo(BigDecimal)}, never
 * the scale-sensitive {@link BigDecimal#equals(Object)}, and the post-payment balance is computed with
 * {@link BigDecimal#subtract(BigDecimal)} (no algebraic shortcut to zero).</p>
 *
 * @see BillPaymentRequest
 * @see AccountRepository
 * @see TransactionRepository
 * @see CardCrossReferenceRepository
 */
@Service
public class BillPaymentService {

    // -----------------------------------------------------------------------------------------------
    // Verbatim COBOL MOVE constants used to populate the payment TRAN-RECORD (COBIL00C L218-232).
    // These literals are part of the preserved external contract (AAP §0.7.2) and must not change.
    // -----------------------------------------------------------------------------------------------

    /** {@code MOVE '02' TO TRAN-TYPE-CD} &mdash; payment transaction type code. */
    private static final String TRAN_TYPE_CD_PAYMENT = "02";

    /** {@code MOVE 2 TO TRAN-CAT-CD} &mdash; payment transaction category code. */
    private static final Integer TRAN_CAT_CD_PAYMENT = 2;

    /** {@code MOVE 'POS TERM' TO TRAN-SOURCE} &mdash; transaction origination source. */
    private static final String TRAN_SOURCE_POS_TERM = "POS TERM";

    /** {@code MOVE 'BILL PAYMENT - ONLINE' TO TRAN-DESC} &mdash; transaction description. */
    private static final String TRAN_DESC_BILL_PAYMENT = "BILL PAYMENT - ONLINE";

    /** {@code MOVE 999999999 TO TRAN-MERCHANT-ID} &mdash; synthetic bill-payment merchant id. */
    private static final long TRAN_MERCHANT_ID_BILL_PAYMENT = 999999999L;

    /** {@code MOVE 'BILL PAYMENT' TO TRAN-MERCHANT-NAME}. */
    private static final String TRAN_MERCHANT_NAME_BILL_PAYMENT = "BILL PAYMENT";

    /** {@code MOVE 'N/A' TO TRAN-MERCHANT-CITY}. */
    private static final String TRAN_MERCHANT_CITY_NA = "N/A";

    /** {@code MOVE 'N/A' TO TRAN-MERCHANT-ZIP}. */
    private static final String TRAN_MERCHANT_ZIP_NA = "N/A";

    /**
     * 16-digit zero-pad format ({@code PIC 9(16)} / {@code WS-TRAN-ID-NUM}) applied to the generated
     * transaction id so {@code 1} renders as {@code "0000000000000001"}.
     */
    private static final String TRAN_ID_FORMAT = "%016d";

    // -----------------------------------------------------------------------------------------------
    // Verbatim COBOL message text (WS-MESSAGE values). Reproduced exactly to preserve the external
    // contract (AAP §0.7.2); the trailing "..." and the double space in the success message are part
    // of the canonical COBIL00C output and must be kept.
    // -----------------------------------------------------------------------------------------------

    /** COBIL00C L161-162 &mdash; empty account id ({@code ACTIDINI = SPACES OR LOW-VALUES}). */
    private static final String MSG_ACCT_ID_EMPTY = "Acct ID can NOT be empty...";

    /** COBIL00C L187-188 &mdash; {@code EVALUATE CONFIRMI WHEN OTHER}. */
    private static final String MSG_INVALID_CONFIRM = "Invalid value. Valid values are (Y/N)...";

    /** COBIL00C L201-202 &mdash; {@code ACCT-CURR-BAL <= ZEROS}. */
    private static final String MSG_NOTHING_TO_PAY = "You have nothing to pay...";

    /** COBIL00C L361/L425 &mdash; {@code READ-ACCTDAT-FILE}/{@code READ-CXACAIX-FILE} {@code NOTFND}. */
    private static final String MSG_ACCOUNT_NOT_FOUND = "Account ID NOT found...";

    /** COBIL00C L536-537 &mdash; {@code WRITE-TRANSACT-FILE} {@code DUPKEY}/{@code DUPREC}. */
    private static final String MSG_TRAN_DUPLICATE = "Tran ID already exist...";

    /** COBIL00C L237-238 &mdash; preview branch prompt (blank confirm). */
    private static final String MSG_CONFIRM_PROMPT = "Confirm to make a bill payment...";

    /**
     * COBIL00C L527-531 success-message prefix. The COBOL {@code STRING} concatenated
     * {@code 'Payment successful. '} (trailing space) and {@code ' Your Transaction ID is '} (leading
     * space), yielding the canonical <strong>double</strong> space between {@code "successful."} and
     * {@code "Your"}; that double space is preserved verbatim.
     */
    private static final String MSG_PAYMENT_SUCCESS_PREFIX =
            "Payment successful.  Your Transaction ID is ";

    /** COBIL00C L530 success-message suffix &mdash; the trailing {@code '.'}. */
    private static final String MSG_PAYMENT_SUCCESS_SUFFIX = ".";

    /** Confirmation keystroke that authorizes the payment (COBOL {@code CONF-PAY-YES}, case-insensitive). */
    private static final String CONFIRM_YES = "Y";

    /** Confirmation keystroke that declines the payment (COBOL {@code 'N'}/{@code 'n'}, case-insensitive). */
    private static final String CONFIRM_NO = "N";

    // -----------------------------------------------------------------------------------------------
    // Injected collaborators. Constructor injection with private-final fields (no field @Autowired) —
    // keeps the build warning-free (AAP §0.7.8) and the class trivially unit-testable with mocks.
    // -----------------------------------------------------------------------------------------------

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final CardCrossReferenceRepository cardCrossReferenceRepository;

    /**
     * Creates the service with its required repositories.
     *
     * @param accountRepository            repository for the {@code ACCTDAT} replacement; supplies the
     *                                     keyed read and the balance {@code REWRITE} (honors
     *                                     {@code @Version})
     * @param transactionRepository        repository for the {@code TRANSACT} replacement; supplies the
     *                                     max-id discovery and the transaction {@code WRITE}
     * @param cardCrossReferenceRepository repository for the {@code CARDXREF}/{@code CXACAIX}
     *                                     replacement; resolves the card number for the new transaction
     */
    public BillPaymentService(AccountRepository accountRepository,
                              TransactionRepository transactionRepository,
                              CardCrossReferenceRepository cardCrossReferenceRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.cardCrossReferenceRepository = cardCrossReferenceRepository;
    }

    /**
     * Processes a bill-payment request, reproducing {@code COBIL00C}'s {@code PROCESS-ENTER-KEY}
     * paragraph (COBIL00C L154-244) end to end: <em>validate &rarr; read &rarr; confirm &rarr;
     * pay-or-preview</em>, in that exact order.
     *
     * <p>The COBOL {@code EVALUATE} evaluation order is preserved precisely:</p>
     * <ol>
     *   <li><strong>Step A</strong> &mdash; reject an empty account id ({@code EVALUATE TRUE} /
     *       {@code ACTIDINI = SPACES OR LOW-VALUES}, L158-167) with a {@link ValidationException}.</li>
     *   <li><strong>Step B</strong> &mdash; interpret the confirm flag ({@code EVALUATE CONFIRMI},
     *       L173-191): {@code Y}/{@code y} pays, {@code N}/{@code n} cancels (a no-op cleared response),
     *       blank/{@code null} previews, anything else is a {@link ValidationException}. The account is
     *       read only for the pay and preview paths.</li>
     *   <li><strong>Step C</strong> &mdash; read the account ({@code READ-ACCTDAT-FILE}); a miss is a
     *       {@link RecordNotFoundException}.</li>
     *   <li><strong>Step D</strong> &mdash; reject a non-positive balance ({@code ACCT-CURR-BAL <=
     *       ZEROS}, L197-206) with a {@link ValidationException}, for both pay and preview.</li>
     *   <li><strong>Step E</strong> &mdash; for preview, return the balance and the confirm prompt; for
     *       a confirmed payment, generate and write the transaction and update the balance
     *       (delegated to {@link #executeConfirmedPayment(Account, long, BigDecimal)}).</li>
     * </ol>
     *
     * <p><strong>{@code @Transactional} unit of work (AAP &sect;0.7.5).</strong> The confirmed-payment
     * path performs two writes &mdash; the transaction insert ({@code WRITE-TRANSACT-FILE}) and the
     * account balance {@code REWRITE} ({@code UPDATE-ACCTDAT-FILE}) &mdash; that {@code COBIL00C}
     * committed together before its implicit CICS {@code RETURN}. They are made atomic here by the
     * method-level {@code @Transactional}: a duplicate transaction id ({@link DuplicateRecordException})
     * or a stale account write ({@code OptimisticLockingFailureException}) rolls the whole payment back,
     * so no partial payment is ever persisted. A plain {@code @Transactional} is used because every
     * domain exception thrown here extends {@code RuntimeException} (via {@code CardDemoException}), so
     * Spring's default rollback rule already covers them; no explicit {@code rollbackFor} is required.
     * The preview and cancel paths perform no writes, so running them inside the transaction is
     * harmless.</p>
     *
     * @param request the validated bill-payment request (account id + confirm flag) bound by the
     *                controller; never {@code null}
     * @return the bill-payment response: a cleared response for a cancel ({@code N}); the balance plus
     *         the confirm prompt for a preview (blank); or the pre-payment balance, generated
     *         transaction id, post-payment balance and success message for a confirmed payment
     * @throws ValidationException     if the account id is empty/non-numeric, the confirm flag is
     *                                 invalid, or the balance is not positive (HTTP&nbsp;400)
     * @throws RecordNotFoundException if the account or its card cross-reference does not exist
     *                                 (HTTP&nbsp;404)
     * @throws DuplicateRecordException if the generated transaction id collides with an existing row
     *                                 (HTTP&nbsp;409)
     */
    @Transactional
    public BillPaymentRequest.Response processBillPayment(BillPaymentRequest request) {
        // CWE-20 null-body guard: a JSON `null` request body would otherwise NPE on the first field
        // deref below and surface as a generic HTTP 500. Map an absent body to the empty-account-id
        // path so it raises the SAME verbatim first-error (HTTP 400), preserving COBOL precedence.
        if (request == null) {
            throw new ValidationException(MSG_ACCT_ID_EMPTY);
        }

        // --- Step A: account-id presence edit (COBIL00C EVALUATE TRUE / ACTIDINI empty, L158-167) ---
        // The DTO @NotBlank normally catches this at the controller; re-checked here so parity holds
        // when the service is invoked directly (e.g. unit tests).
        String accountIdRaw = request.getAccountId();
        if (accountIdRaw == null || accountIdRaw.isBlank()) {
            throw new ValidationException(MSG_ACCT_ID_EMPTY);
        }

        // COBOL MOVE ACTIDINI TO ACCT-ID / XREF-ACCT-ID (L170-171): the one key drives both the ACCTDAT
        // keyed read and the CXACAIX alternate-index read; both JPA keys are Long. The @Pattern("\\d{1,11}")
        // guarantees digits at the controller, but parse defensively so direct/test calls hold parity.
        final long accountId;
        try {
            accountId = Long.parseLong(accountIdRaw.trim());
        } catch (NumberFormatException ex) {
            throw new ValidationException(MSG_ACCT_ID_EMPTY, ex);
        }

        // --- Step B: interpret the confirm flag (COBIL00C EVALUATE CONFIRMI, L173-191) ---
        // Order preserved exactly: Y/y -> pay; N/n -> cancel/no-op; blank/LOW-VALUES -> preview;
        // any other value -> invalid. The account is NOT read on the cancel or invalid paths.
        String confirm = request.getConfirm();
        final boolean confirmedPayment;
        if (confirm != null && confirm.equalsIgnoreCase(CONFIRM_YES)) {
            // COBOL WHEN 'Y'/'y' -> SET CONF-PAY-YES (then READ-ACCTDAT below).
            confirmedPayment = true;
        } else if (confirm != null && confirm.equalsIgnoreCase(CONFIRM_NO)) {
            // COBOL WHEN 'N'/'n' -> PERFORM CLEAR-CURRENT-SCREEN + MOVE 'Y' TO WS-ERR-FLG: the payment is
            // declined and the screen reset, which short-circuits the remainder of PROCESS-ENTER-KEY —
            // no account read, no error, no message. In REST this is a cleared (empty) response.
            return new BillPaymentRequest.Response();
        } else if (confirm == null || confirm.isBlank()) {
            // COBOL WHEN SPACES/LOW-VALUES -> READ-ACCTDAT then the "Confirm..." prompt (preview).
            confirmedPayment = false;
        } else {
            // COBOL WHEN OTHER -> "Invalid value. Valid values are (Y/N)..."; account is NOT read.
            throw new ValidationException(MSG_INVALID_CONFIRM);
        }

        // --- Step C: read the account (COBIL00C READ-ACCTDAT-FILE, L343-372) ---
        // Technology substitution: VSAM KSDS keyed read DATASET('ACCTDAT') RIDFLD(ACCT-ID) UPDATE ->
        // JpaRepository.findById; DFHRESP(NOTFND) -> RecordNotFoundException (HTTP 404).
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new RecordNotFoundException(MSG_ACCOUNT_NOT_FOUND));

        // COBOL MOVE ACCT-CURR-BAL TO WS-CURR-BAL TO CURBALI (L193-194): the balance the screen showed
        // and the full amount a confirmed bill payment clears. Scale 2; compared with compareTo below.
        BigDecimal currentBalance = account.getAcctCurrBal();

        // --- Step D: "nothing to pay" edit (COBIL00C L197-206; runs for BOTH confirmed and preview) ---
        // Use compareTo, NEVER equals (AAP §0.7.3 — equals is scale-sensitive, e.g. 0.00 vs 0).
        if (currentBalance.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ValidationException(MSG_NOTHING_TO_PAY);
        }

        // --- Step E: confirmed payment vs. preview (COBIL00C L208-244) ---
        if (!confirmedPayment) {
            // COBOL ELSE branch (L236-239): preview — show the balance and prompt for confirmation.
            // No transaction is written and the account is not updated.
            BillPaymentRequest.Response preview = new BillPaymentRequest.Response();
            preview.setCurrentBalance(currentBalance);
            preview.setMessage(MSG_CONFIRM_PROMPT);
            return preview;
        }

        // COBOL IF CONF-PAY-YES branch (L210-235): perform the full-balance payment.
        return executeConfirmedPayment(account, accountId, currentBalance);
    }

    /**
     * Executes the confirmed full-balance payment &mdash; the {@code COBIL00C} {@code CONF-PAY-YES}
     * branch (L210-235): resolve the card cross-reference, generate the next transaction id, write the
     * payment transaction, decrement the account balance and persist the account. All work runs inside
     * the caller's {@code @Transactional} boundary, so any failure rolls the entire payment back.
     *
     * @param account        the managed account whose balance is being paid (already read in Step C)
     * @param accountId      the numeric account id (the {@code CXACAIX} lookup key)
     * @param currentBalance the pre-payment balance ({@code ACCT-CURR-BAL}); the full amount to pay,
     *                       scale 2
     * @return the success response: pre-payment balance, generated transaction id, post-payment balance
     *         (zero after a full payment) and the verbatim success message
     * @throws RecordNotFoundException  if the account has no card cross-reference
     *                                  ({@code READ-CXACAIX-FILE} {@code NOTFND})
     * @throws DuplicateRecordException if the generated transaction id already exists
     *                                  ({@code WRITE-TRANSACT-FILE} {@code DUPKEY}/{@code DUPREC})
     */
    private BillPaymentRequest.Response executeConfirmedPayment(Account account,
                                                                long accountId,
                                                                BigDecimal currentBalance) {

        // 1) Resolve the card number via the CXACAIX alternate index (COBIL00C READ-CXACAIX-FILE, L408-436).
        //    Technology substitution: VSAM AIX read DATASET('CXACAIX') RIDFLD(XREF-ACCT-ID) ->
        //    CardCrossReferenceRepository.findByXrefAcctId. The index is NONUNIQUE, so the query returns a
        //    List; the COBOL READ returns the first matching record, so we take get(0). An empty result is
        //    the DFHRESP(NOTFND) path -> RecordNotFoundException (HTTP 404).
        List<CardCrossReference> xrefs = cardCrossReferenceRepository.findByXrefAcctId(accountId);
        if (xrefs.isEmpty()) {
            throw new RecordNotFoundException(MSG_ACCOUNT_NOT_FOUND);
        }
        CardCrossReference xref = xrefs.get(0);

        // 2) Auto-generate the next transaction id (Factory pattern, AAP §0.3.3; COBOL MOVE HIGH-VALUES TO
        //    TRAN-ID + STARTBR + READPREV + ENDBR + ADD 1, L212-217). Technology substitution: the
        //    browse-to-end of the TRANSACT KSDS becomes findMaxTransactionId(). TRAN-ID is a 16-char
        //    zero-padded numeric key, so its lexicographic MAX equals its numeric MAX; an empty table
        //    (COBOL ENDFILE -> MOVE ZEROS) surfaces as Optional.empty() -> start from 0. The +1 increment
        //    and the 16-digit zero-pad (PIC 9(16)) are performed HERE, not in the repository.
        long nextId = transactionRepository.findMaxTransactionId()
                .map(Long::parseLong)
                .orElse(0L) + 1L;
        String tranId = String.format(TRAN_ID_FORMAT, nextId);

        // 3) Populate the payment transaction (COBOL INITIALIZE TRAN-RECORD then the MOVEs, L218-232).
        //    The verbatim COBOL constants are part of the preserved external contract (AAP §0.7.2).
        Transaction newTransaction = new Transaction();
        newTransaction.setTranId(tranId);                                   // MOVE WS-TRAN-ID-NUM TO TRAN-ID
        newTransaction.setTranTypeCd(TRAN_TYPE_CD_PAYMENT);                 // MOVE '02'
        newTransaction.setTranCatCd(TRAN_CAT_CD_PAYMENT);                   // MOVE 2
        newTransaction.setTranSource(TRAN_SOURCE_POS_TERM);                 // MOVE 'POS TERM'
        newTransaction.setTranDesc(TRAN_DESC_BILL_PAYMENT);                 // MOVE 'BILL PAYMENT - ONLINE'
        newTransaction.setTranAmt(currentBalance);                         // MOVE ACCT-CURR-BAL TO TRAN-AMT (full balance)
        newTransaction.setTranCardNum(xref.getXrefCardNum());              // MOVE XREF-CARD-NUM TO TRAN-CARD-NUM
        newTransaction.setTranMerchantId(TRAN_MERCHANT_ID_BILL_PAYMENT);   // MOVE 999999999
        newTransaction.setTranMerchantName(TRAN_MERCHANT_NAME_BILL_PAYMENT); // MOVE 'BILL PAYMENT'
        newTransaction.setTranMerchantCity(TRAN_MERCHANT_CITY_NA);         // MOVE 'N/A'
        newTransaction.setTranMerchantZip(TRAN_MERCHANT_ZIP_NA);           // MOVE 'N/A'

        // 4) Timestamp (COBOL GET-CURRENT-TIMESTAMP: ASKTIME + FORMATTIME, then microseconds set to ZEROS,
        //    L249-267). Technology substitution: CICS ASKTIME/FORMATTIME -> java.time.LocalDateTime. The
        //    COBOL builds the 26-char text 'YYYY-MM-DD HH:MM:SS.000000' with the sub-second fraction
        //    explicitly zeroed (MOVE ZEROS TO WS-TIMESTAMP-TM-MS6). The Transaction entity maps
        //    TRAN-ORIG-TS / TRAN-PROC-TS to LocalDateTime (V1 TIMESTAMP columns), NOT to a 26-char String,
        //    so the faithful equivalent is LocalDateTime.now() truncated to whole seconds (zero nanos ==
        //    the zeroed microseconds). The same instant is assigned to BOTH timestamp fields.
        LocalDateTime timestamp = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        newTransaction.setTranOrigTs(timestamp);                           // MOVE WS-TIMESTAMP TO TRAN-ORIG-TS
        newTransaction.setTranProcTs(timestamp);                           // MOVE WS-TIMESTAMP TO TRAN-PROC-TS

        // 5) Persist the transaction FIRST (COBOL WRITE-TRANSACT-FILE, L233 / L510-547). Technology
        //    substitution: VSAM WRITE DATASET('TRANSACT') -> saveAndFlush. The flush forces the INSERT (and
        //    any duplicate-key violation) to surface NOW, inside this @Transactional unit, so a duplicate
        //    rolls back the whole payment. DFHRESP(DUPKEY)/DFHRESP(DUPREC) -> DuplicateRecordException
        //    (HTTP 409). The COBOL WHEN OTHER ("Unable to Add Bill pay Transaction...") is a CICS infra/IO
        //    failure; in Java the corresponding unexpected DataAccessException is left to propagate (no
        //    domain exception is invented for it).
        try {
            transactionRepository.saveAndFlush(newTransaction);
        } catch (DataIntegrityViolationException ex) {
            throw new DuplicateRecordException(MSG_TRAN_DUPLICATE, ex);
        }

        // 6) Recompute the balance — formula preserved verbatim (COBOL COMPUTE ACCT-CURR-BAL =
        //    ACCT-CURR-BAL - TRAN-AMT, L234). No algebraic shortcut to zero even though a full payment
        //    leaves zero (AAP §0.7.3); BigDecimal.subtract preserves scale 2.
        BigDecimal newBalance = account.getAcctCurrBal().subtract(newTransaction.getTranAmt());
        account.setAcctCurrBal(newBalance);

        // 7) Persist the account (COBOL UPDATE-ACCTDAT-FILE REWRITE, L235 / L377-403). Technology
        //    substitution: VSAM REWRITE DATASET('ACCTDAT') -> JpaRepository.save; the @Version column on
        //    Account makes Hibernate honor optimistic locking automatically (a stale write surfaces as
        //    OptimisticLockingFailureException and rolls back this transaction).
        accountRepository.save(account);

        // 8) Build the success response (COBOL WRITE-TRANSACT-FILE NORMAL branch, L523-531). currentBalance
        //    is the pre-payment balance the screen displayed; newBalance is the post-payment balance (zero
        //    after a full payment); the message is the verbatim COBOL STRING result (note the double
        //    space). The confirmation number surfaces the generated transaction id, per the DTO contract.
        String message = MSG_PAYMENT_SUCCESS_PREFIX + tranId + MSG_PAYMENT_SUCCESS_SUFFIX;
        return new BillPaymentRequest.Response(currentBalance, tranId, newBalance, message, tranId);
    }
}

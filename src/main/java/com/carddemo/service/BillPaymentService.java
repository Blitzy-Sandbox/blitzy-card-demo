package com.carddemo.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import io.micrometer.observation.annotation.Observed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.carddemo.dto.BillPaymentRequest;
import com.carddemo.dto.BillPaymentResponse;
import com.carddemo.entity.Account;
import com.carddemo.entity.Transaction;
import com.carddemo.exception.ResourceNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.TransactionRepository;

/**
 * Application service for the online <strong>Bill Payment</strong> transaction
 * ({@code CB00}), which pays a credit-card account's current balance
 * <em>in full</em> and records the payment as a posted transaction.
 *
 * <p>This is the Java&nbsp;25 / Spring&nbsp;Boot translation of the CICS/COBOL
 * program {@code COBIL00C} ({@code app/cbl/COBIL00C.cbl}, 572&nbsp;LOC), whose
 * frozen source is referenced &mdash; never copied &mdash; at commit SHA
 * {@code 27d6c6f} (full {@code 7756d895ffeb65f7ea72aaa609e356d9899afcec}). The
 * legacy program is pseudo-conversational: the operator keys an account id, the
 * screen echoes the current balance, and a confirmation flag authorises paying
 * the whole balance. The stateless REST replacement collapses that interaction
 * into a single {@link #payBill(BillPaymentRequest)} call whose
 * {@link BillPaymentRequest#confirm() confirm} flag carries the authorisation
 * that CICS held in the {@code COMMAREA}.</p>
 *
 * <h2>Control-flow parity with {@code COBIL00C} ({@code PROCESS-ENTER-KEY})</h2>
 * <p>The COBOL {@code PROCESS-ENTER-KEY} paragraph (source L154&ndash;L244)
 * evaluates its guards in a fixed order; {@link #payBill(BillPaymentRequest)}
 * preserves that ordering exactly (migration rule G3):</p>
 * <ol>
 *   <li><b>Empty account id</b> (L159&ndash;L164, {@code 'Acct ID can NOT be
 *       empty...'}). A missing, blank, non-numeric or non-positive account id is
 *       rejected up front with a {@link ValidationException} (HTTP&nbsp;400)
 *       carrying {@value #MSG_INVALID_ACCT}.</li>
 *   <li><b>Account lookup</b> (L177 / {@code READ-ACCTDAT-FILE}, L343&ndash;L372).
 *       The {@code EXEC CICS READ} of the {@code ACCTDAT} KSDS keyed by
 *       {@code ACCT-ID} maps to {@link AccountRepository#findById(Object)}; the
 *       {@code DFHRESP(NOTFND)} branch (L359&ndash;L364, {@code 'Account ID NOT
 *       found...'}) becomes a {@link ResourceNotFoundException} (HTTP&nbsp;404)
 *       carrying {@value #MSG_ACCT_NOT_FOUND}.</li>
 *   <li><b>Nothing-to-pay guard</b> (L197&ndash;L206). When
 *       {@code ACCT-CURR-BAL <= ZEROS} the program refuses the payment with
 *       {@code 'You have nothing to pay...'}; this maps to a
 *       {@link ValidationException} carrying {@value #MSG_NOTHING_TO_PAY}.</li>
 *   <li><b>Confirmation gate</b> (L208&ndash;L240). When the confirmation flag is
 *       not {@code 'Y'} the program writes nothing and prompts with
 *       {@code 'Confirm to make a bill payment...'}; this returns a
 *       {@link BillPaymentResponse} carrying {@value #MSG_CONFIRM} and a
 *       {@code null} confirmation number, and performs <em>no</em> persistence.</li>
 *   <li><b>Payment execution</b> (L210&ndash;L235). Only when confirmed does the
 *       program resolve the card, allocate the next transaction id, build the
 *       {@code TRAN-RECORD}, write it ({@code WRITE-TRANSACT-FILE}), subtract the
 *       amount from the balance and rewrite the account
 *       ({@code UPDATE-ACCTDAT-FILE}).</li>
 * </ol>
 *
 * <h2>Payment record field mapping (source L218&ndash;L235)</h2>
 * <p>The transaction written by a confirmed payment reproduces the COBOL
 * {@code MOVE}s verbatim: {@code TRAN-TYPE-CD = '02'}, {@code TRAN-CAT-CD = 2},
 * {@code TRAN-SOURCE = 'POS TERM'}, {@code TRAN-DESC = 'BILL PAYMENT - ONLINE'},
 * {@code TRAN-AMT = ACCT-CURR-BAL} (the full current balance),
 * {@code TRAN-CARD-NUM = XREF-CARD-NUM} (resolved through the account
 * cross-reference), {@code TRAN-MERCHANT-ID = 999999999},
 * {@code TRAN-MERCHANT-NAME = 'BILL PAYMENT'}, and
 * {@code TRAN-MERCHANT-CITY = TRAN-MERCHANT-ZIP = 'N/A'}. The origination and
 * processing timestamps are set to the same current-timestamp value
 * (L230&ndash;L232). After the write, {@code COMPUTE ACCT-CURR-BAL =
 * ACCT-CURR-BAL - TRAN-AMT} drives the balance to zero (L234).</p>
 *
 * <h2>Fidelity and safety</h2>
 * <ul>
 *   <li><b>Decimal fidelity.</b> Every monetary value is a
 *       {@link java.math.BigDecimal} held at {@value #MONEY_SCALE} decimal places
 *       via {@link RoundingMode#HALF_UP}, matching the {@code PIC S9(10)V99}
 *       picture of {@code ACCT-CURR-BAL} and the {@code PIC S9(09)V99} picture of
 *       {@code TRAN-AMT}. Floating-point types are never used for money
 *       (AAP&nbsp;&sect;0.8.2, Gate&nbsp;2). The payment amount equals the full
 *       current balance, so the resulting balance is {@code 0.00}.</li>
 *   <li><b>Atomicity.</b> The transaction write and the account rewrite occur in
 *       one {@link Transactional @Transactional(rollbackFor = Exception.class)}
 *       boundary, reproducing the implicit CICS unit of work: if either
 *       persistence step fails, both roll back.</li>
 *   <li><b>Timestamp preservation.</b> {@code TRAN-ORIG-TS} / {@code TRAN-PROC-TS}
 *       ({@code PIC X(26)}) are written as 26-character strings in the
 *       {@code yyyy-MM-dd HH:mm:ss.SSSSSS} layout that {@code COBIL00C}'s
 *       {@code GET-CURRENT-TIMESTAMP} builds via {@code EXEC CICS FORMATTIME}
 *       ({@code DATESEP('-')}, {@code TIMESEP(':')}); see {@link #nowTs26()}.</li>
 *   <li><b>PAN safety.</b> The resolved card number (PAN) is written to the
 *       transaction record but is <em>never</em> emitted to a log line.</li>
 * </ul>
 *
 * <h2>Design notes</h2>
 * <ul>
 *   <li>The three collaborators are supplied by <em>constructor injection</em>,
 *       replacing the static COBOL {@code CALL} / VSAM linkage with Spring
 *       dependency injection (AAP&nbsp;&sect;0.4.3). A single constructor means no
 *       {@code @Autowired} annotation is required.</li>
 *   <li>The service holds no mutable state; its only fields are the injected,
 *       {@code final} collaborators, so a single shared instance is safe for
 *       concurrent request threads.</li>
 *   <li>Structured logging is emitted through SLF4J; the request/batch
 *       {@code correlationId} is supplied by the MDC and rendered by the logging
 *       configuration, so no correlation state is managed here.</li>
 * </ul>
 *
 * @see CrossReferenceService
 * @see BillPaymentRequest
 * @see BillPaymentResponse
 */
@Service
public class BillPaymentService {

    /** SLF4J logger; correlation id is injected via MDC by the request/batch filter. */
    private static final Logger log = LoggerFactory.getLogger(BillPaymentService.class);

    /**
     * Rejection message for a missing, blank, non-numeric or non-positive account
     * id. Mirrors the intent of {@code COBIL00C}'s empty-account guard
     * ({@code 'Acct ID can NOT be empty...'}, source L161) restated as the
     * eleven-digit numeric contract enforced by this stateless service.
     */
    private static final String MSG_INVALID_ACCT =
            "Account number must be a non zero 11 digit number";

    /**
     * Verbatim {@code COBIL00C} account-not-found message
     * ({@code READ-ACCTDAT-FILE} {@code DFHRESP(NOTFND)} branch, source L361).
     */
    private static final String MSG_ACCT_NOT_FOUND = "Account ID NOT found...";

    /**
     * Verbatim {@code COBIL00C} nothing-to-pay message
     * ({@code PROCESS-ENTER-KEY}, source L201) raised when the current balance is
     * zero or negative.
     */
    private static final String MSG_NOTHING_TO_PAY = "You have nothing to pay...";

    /**
     * Verbatim {@code COBIL00C} confirmation prompt
     * ({@code PROCESS-ENTER-KEY}, source L237) returned when the payment has not
     * been confirmed; no persistence occurs on this path.
     */
    private static final String MSG_CONFIRM = "Confirm to make a bill payment...";

    /**
     * Fixed prefix of the {@code COBIL00C} success message assembled by the
     * {@code WRITE-TRANSACT-FILE} {@code DFHRESP(NORMAL)} {@code STRING}
     * (source L527&ndash;L531): {@code 'Payment successful. '} concatenated with
     * {@code ' Your Transaction ID is '} yields the double space after the
     * sentence. The generated transaction id and a trailing {@value #MSG_SUCCESS_SUFFIX}
     * are appended at call time.
     */
    private static final String MSG_SUCCESS_PREFIX =
            "Payment successful.  Your Transaction ID is ";

    /** Trailing period of the {@code COBIL00C} success message (source L530). */
    private static final String MSG_SUCCESS_SUFFIX = ".";

    /** {@code TRAN-TYPE-CD = '02'} for an online bill payment (source L220). */
    private static final String TRAN_TYPE_CD = "02";

    /** {@code TRAN-CAT-CD = 2} for an online bill payment (source L221). */
    private static final int TRAN_CAT_CD = 2;

    /** {@code TRAN-SOURCE = 'POS TERM'} (source L222). */
    private static final String TRAN_SOURCE = "POS TERM";

    /** {@code TRAN-DESC = 'BILL PAYMENT - ONLINE'} (source L223). */
    private static final String TRAN_DESC = "BILL PAYMENT - ONLINE";

    /** {@code TRAN-MERCHANT-ID = 999999999} (source L226). */
    private static final long TRAN_MERCHANT_ID = 999_999_999L;

    /** {@code TRAN-MERCHANT-NAME = 'BILL PAYMENT'} (source L227). */
    private static final String TRAN_MERCHANT_NAME = "BILL PAYMENT";

    /**
     * {@code 'N/A'} sentinel value written to both {@code TRAN-MERCHANT-CITY} and
     * {@code TRAN-MERCHANT-ZIP} (source L228&ndash;L229).
     */
    private static final String TRAN_MERCHANT_NA = "N/A";

    /**
     * Scale mandated for every monetary value, matching the two decimal places of
     * the COBOL {@code PIC S9(10)V99} / {@code PIC S9(09)V99} pictures.
     */
    private static final int MONEY_SCALE = 2;

    /**
     * 26-character timestamp formatter ({@code yyyy-MM-dd HH:mm:ss.SSSSSS}). This
     * reproduces the layout {@code COBIL00C}'s {@code GET-CURRENT-TIMESTAMP} builds
     * from {@code EXEC CICS FORMATTIME} with {@code DATESEP('-')} and
     * {@code TIMESEP(':')} (a space between date and time; six fractional-second
     * digits). {@link DateTimeFormatter} is immutable and thread-safe, so it is
     * shared as a constant.
     */
    private static final DateTimeFormatter TS_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

    /** Account repository backing the {@code ACCTDAT} KSDS (keyed on {@code ACCT-ID}). */
    private final AccountRepository accountRepository;

    /** Transaction repository backing the {@code TRANSACT} KSDS (keyed on {@code TRAN-ID}). */
    private final TransactionRepository transactionRepository;

    /**
     * Shared cross-reference / id-generation service reproducing the
     * {@code READ-CXACAIX-FILE} card lookup and the {@code STARTBR}/{@code READPREV}
     * next-transaction-id browse.
     */
    private final CrossReferenceService crossReferenceService;

    /**
     * Creates the service with its three collaborating beans.
     *
     * <p>Spring injects all three through this single constructor, so no
     * {@code @Autowired} annotation is needed. Each argument is stored in a
     * {@code final} field, making the service effectively immutable and
     * thread-safe.</p>
     *
     * @param accountRepository     the account repository; must not be {@code null}
     * @param transactionRepository the transaction repository; must not be {@code null}
     * @param crossReferenceService the cross-reference / id-generation service; must
     *                              not be {@code null}
     */
    public BillPaymentService(AccountRepository accountRepository,
            TransactionRepository transactionRepository,
            CrossReferenceService crossReferenceService) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.crossReferenceService = crossReferenceService;
    }

    /**
     * Pays the supplied account's current balance in full, reproducing the
     * {@code COBIL00C} {@code PROCESS-ENTER-KEY} decision flow (source
     * L154&ndash;L244).
     *
     * <p>Behaviour by path, in the exact COBOL evaluation order:</p>
     * <ul>
     *   <li>An invalid account id (null, blank, non-numeric, or {@code <= 0})
     *       raises {@link ValidationException} ({@value #MSG_INVALID_ACCT}).</li>
     *   <li>An account id with no matching record raises
     *       {@link ResourceNotFoundException} ({@value #MSG_ACCT_NOT_FOUND}).</li>
     *   <li>A zero or negative balance raises {@link ValidationException}
     *       ({@value #MSG_NOTHING_TO_PAY}).</li>
     *   <li>An unconfirmed request ({@code confirm} is {@code null} or
     *       {@code false}) returns a prompt response ({@value #MSG_CONFIRM}) with a
     *       {@code null} confirmation number and writes nothing.</li>
     *   <li>A confirmed request writes the bill-payment transaction, zeroes the
     *       balance, saves the account, and returns the success response.</li>
     * </ul>
     *
     * <p>The whole method runs in a single transaction that rolls back on any
     * exception, so the transaction write and the account update are atomic.</p>
     *
     * @param request the bill-payment request carrying the account id and the
     *                confirmation flag; must not be {@code null}
     * @return a {@link BillPaymentResponse} describing either the confirmation
     *         prompt (unconfirmed) or the applied payment (confirmed)
     * @throws ValidationException       if the account id is invalid or the balance
     *                                   is not positive (HTTP&nbsp;400)
     * @throws ResourceNotFoundException if the account, or its card cross-reference,
     *                                   cannot be found (HTTP&nbsp;404)
     */
    @Transactional(rollbackFor = Exception.class)
    @Observed(name = "carddemo.service", contextualName = "bill-payment")
    public BillPaymentResponse payBill(BillPaymentRequest request) {
        // Step 1 — validate the account id (COBOL empty/format guard, L159-L164).
        final long accountId = parseAndValidateAccountId(request);

        // Step 2 — read the account (READ-ACCTDAT-FILE, L343-L372). A missing
        // record reproduces the DFHRESP(NOTFND) branch as a 404.
        final Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException(MSG_ACCT_NOT_FOUND));

        // Step 3 — nothing-to-pay guard (L197-L206): balance must be strictly
        // positive. A null balance is treated as nothing to pay.
        final BigDecimal currentBalance = account.getAcctCurrBal();
        if (currentBalance == null || currentBalance.signum() <= 0) {
            throw new ValidationException(MSG_NOTHING_TO_PAY);
        }

        // Step 4 — confirmation gate (L208-L240). Without confirmation the COBOL
        // program prompts and writes nothing; the response echoes the balance and
        // omits a confirmation number.
        if (request.confirm() == null || !request.confirm()) {
            // Preview: compute the projected post-payment balance using the same
            // full-balance payoff math as the committed path (payAmt = full balance;
            // newBalance = balance - payAmt -> 0.00 at scale 2) so the caller can
            // review the outcome before confirming, but persist nothing — the COBOL
            // program prompts and writes no records without confirmation (L208-L240).
            final BigDecimal payAmt = currentBalance.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            final BigDecimal projectedBalance =
                    currentBalance.subtract(payAmt).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            log.debug("Bill payment for account {} requested without confirmation; "
                    + "returning confirmation prompt (projected balance {}) with no persistence",
                    accountId, projectedBalance);
            return new BillPaymentResponse(
                    request.accountId(),
                    currentBalance,
                    payAmt,
                    projectedBalance,
                    null,
                    MSG_CONFIRM);
        }

        // Step 5 — execute the payment (L210-L235).
        // Resolve the primary card through the account cross-reference
        // (READ-CXACAIX-FILE -> XREF-CARD-NUM); a missing xref raises a 404.
        final String cardNum = crossReferenceService.resolvePrimaryCardNumber(accountId);
        // Allocate the next 16-digit transaction id (STARTBR/READPREV + 1).
        final String tranId = crossReferenceService.generateNextTransactionId();
        // TRAN-AMT = ACCT-CURR-BAL (full balance), normalised to scale 2.
        final BigDecimal payAmt = currentBalance.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        // TRAN-ORIG-TS = TRAN-PROC-TS = current timestamp (L230-L232).
        final String timestamp = nowTs26();

        final Transaction txn = new Transaction();
        txn.setTranId(tranId);
        txn.setTranTypeCd(TRAN_TYPE_CD);
        txn.setTranCatCd(TRAN_CAT_CD);
        txn.setTranSource(TRAN_SOURCE);
        txn.setTranDesc(TRAN_DESC);
        txn.setTranAmt(payAmt);
        txn.setTranCardNum(cardNum);
        txn.setTranMerchantId(TRAN_MERCHANT_ID);
        txn.setTranMerchantName(TRAN_MERCHANT_NAME);
        txn.setTranMerchantCity(TRAN_MERCHANT_NA);
        txn.setTranMerchantZip(TRAN_MERCHANT_NA);
        txn.setTranOrigTs(timestamp);
        txn.setTranProcTs(timestamp);
        transactionRepository.save(txn); // WRITE-TRANSACT-FILE (L233)

        // COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT (L234) -> 0, then
        // UPDATE-ACCTDAT-FILE (L235). Both saves share this transaction boundary.
        final BigDecimal newBalance =
                currentBalance.subtract(payAmt).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        account.setAcctCurrBal(newBalance);
        accountRepository.save(account);

        log.info("Bill payment posted: account={}, tranId={}, amount={}, newBalance={}",
                accountId, tranId, payAmt, newBalance);

        final String message = MSG_SUCCESS_PREFIX + tranId + MSG_SUCCESS_SUFFIX;
        return new BillPaymentResponse(
                request.accountId(),
                currentBalance,
                payAmt,
                newBalance,
                tranId,
                message);
    }

    /**
     * Validates the request's account id and returns it as a primitive
     * {@code long} suitable for keyed access.
     *
     * <p>The {@link BillPaymentRequest#accountId()} value is a numeric string
     * (the Bean Validation constraints on the DTO already enforce a 1&ndash;11
     * digit pattern at the REST boundary). This method re-validates defensively so
     * that direct callers &mdash; tests and non-REST entry points &mdash; receive
     * the same guarantees: the id must be present, numeric, and strictly
     * positive, mirroring the COBOL {@code PIC 9(11)} account key.</p>
     *
     * @param request the bill-payment request (may be {@code null})
     * @return the validated account id as a positive {@code long}
     * @throws ValidationException if the request or its account id is
     *                             {@code null}/blank, non-numeric, or not strictly
     *                             positive
     */
    private long parseAndValidateAccountId(BillPaymentRequest request) {
        if (request == null) {
            throw new ValidationException(MSG_INVALID_ACCT);
        }
        final String raw = request.accountId();
        if (raw == null || raw.isBlank()) {
            throw new ValidationException(MSG_INVALID_ACCT);
        }
        final long parsed;
        try {
            parsed = Long.parseLong(raw.trim());
        } catch (NumberFormatException ex) {
            throw new ValidationException(MSG_INVALID_ACCT, ex);
        }
        if (parsed <= 0L) {
            throw new ValidationException(MSG_INVALID_ACCT);
        }
        return parsed;
    }

    /**
     * Returns the current timestamp as a 26-character string in the
     * {@code yyyy-MM-dd HH:mm:ss.SSSSSS} layout, reproducing
     * {@code COBIL00C}'s {@code GET-CURRENT-TIMESTAMP} value used for both
     * {@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS}.
     *
     * @return the formatted 26-character current-timestamp string
     */
    private String nowTs26() {
        return LocalDateTime.now().format(TS_FORMATTER);
    }
}

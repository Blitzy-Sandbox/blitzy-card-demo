package com.carddemo.service.billing;

import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.model.dto.BillPaymentRequest;
import com.carddemo.model.dto.BillPaymentResponse;
import com.carddemo.model.entity.Account;
import com.carddemo.model.entity.CardCrossReference;
import com.carddemo.model.entity.Transaction;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.TransactionRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Online bill-payment service: pays an account's current balance in full.
 *
 * <p>Behavioral translation of COBOL program {@code COBIL00C} (CICS transaction
 * {@code CB00}); see {@code app/cbl/COBIL00C.cbl} at source commit {@code 27d6c6f}.
 * The COBOL is a REFERENCE only and is not embedded here. The single public
 * operation {@link #pay(BillPaymentRequest)} reproduces the {@code PROCESS-ENTER-KEY}
 * control flow: confirmation gate, account read, "nothing to pay" guard, payment
 * transaction creation, and balance decrement, executed as one atomic unit.</p>
 */
@Service
public class BillPaymentService {

    private static final Logger LOG = LoggerFactory.getLogger(BillPaymentService.class);

    /** Confirmation values (COBIL00C: WS-CONF-PAY-FLG 88-levels CONF-PAY-YES / CONF-PAY-NO). */
    private static final String CONFIRM_YES_UPPER = "Y";
    private static final String CONFIRM_YES_LOWER = "y";
    private static final String CONFIRM_NO_UPPER = "N";
    private static final String CONFIRM_NO_LOWER = "n";

    /** Fixed payment-transaction field values (COBIL00C PROCESS-ENTER-KEY). */
    private static final String PAYMENT_TRAN_TYPE_CD = "02";
    private static final int PAYMENT_TRAN_CAT_CD = 2;
    private static final String PAYMENT_TRAN_SOURCE = "POS TERM";
    private static final String PAYMENT_TRAN_DESC = "BILL PAYMENT - ONLINE";
    private static final long PAYMENT_MERCHANT_ID = 999999999L;
    private static final String PAYMENT_MERCHANT_NAME = "BILL PAYMENT";
    private static final String PAYMENT_MERCHANT_CITY = "N/A";
    private static final String PAYMENT_MERCHANT_ZIP = "N/A";

    /** Transaction id is a fixed-width 16-digit zero-padded numeric string. */
    private static final String TRAN_ID_FORMAT = "%016d";

    /** 26-char timestamp matching COBOL GET-CURRENT-TIMESTAMP (yyyy-MM-dd HH:mm:ss.ffffff). */
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

    /** Exact COBOL operator messages, preserved verbatim. */
    private static final String MSG_ACCT_ID_EMPTY = "Acct ID can NOT be empty...";
    private static final String MSG_INVALID_CONFIRM = "Invalid value. Valid values are (Y/N)...";
    private static final String MSG_NOTHING_TO_PAY = "You have nothing to pay...";
    private static final String MSG_CONFIRM_PROMPT = "Confirm to make a bill payment...";
    private static final String MSG_ACCT_NOT_FOUND = "Account ID NOT found...";

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final CardCrossReferenceRepository cardCrossReferenceRepository;

    public BillPaymentService(AccountRepository accountRepository,
                              TransactionRepository transactionRepository,
                              CardCrossReferenceRepository cardCrossReferenceRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.cardCrossReferenceRepository = cardCrossReferenceRepository;
    }

    /**
     * Pays the account balance in full (COBIL00C PROCESS-ENTER-KEY).
     *
     * <p>The read-create-update sequence is atomic: a failure anywhere rolls the
     * whole posting back (CICS SYNCPOINT semantics).</p>
     *
     * @param request account id plus confirmation flag (BMS symbolic map COBIL00)
     * @return cleared screen (cancel), confirmation prompt (preview), or success result (posted)
     */
    @Transactional
    public BillPaymentResponse pay(BillPaymentRequest request) {
        // COBIL00C: SET CONF-PAY-NO TO TRUE (default: not yet confirmed).
        final String accountId = request.accountId() == null ? "" : request.accountId().trim();
        final String confirm = request.confirm() == null ? "" : request.confirm().trim();

        // EVALUATE TRUE / WHEN ACTIDINI = SPACES OR LOW-VALUES: account id must be present.
        if (accountId.isEmpty()) {
            throw new ValidationException(MSG_ACCT_ID_EMPTY, "accountId");
        }

        // EVALUATE CONFIRMI: confirmation gate (preserves COBOL branch order).
        final boolean confirmed;
        if (CONFIRM_YES_UPPER.equals(confirm) || CONFIRM_YES_LOWER.equals(confirm)) {
            // 'Y'/'y' -> SET CONF-PAY-YES, then READ-ACCTDAT-FILE.
            confirmed = true;
        } else if (CONFIRM_NO_UPPER.equals(confirm) || CONFIRM_NO_LOWER.equals(confirm)) {
            // 'N'/'n' -> CLEAR-CURRENT-SCREEN: cancel; nothing read, nothing posted.
            return new BillPaymentResponse(null, null, null, null);
        } else if (confirm.isEmpty()) {
            // SPACES/LOW-VALUES -> READ-ACCTDAT-FILE (preview only).
            confirmed = false;
        } else {
            // WHEN OTHER -> invalid; no account read.
            throw new ValidationException(MSG_INVALID_CONFIRM, "confirm");
        }

        // READ-ACCTDAT-FILE: keyed account read (NOTFND -> "Account ID NOT found...").
        final Account account = loadAccount(accountId);
        final BigDecimal currentBalance = account.getAcctCurrBal();

        // IF ACCT-CURR-BAL <= ZEROS: nothing to pay. compareTo, never equals (scale-insensitive).
        if (currentBalance.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ValidationException(MSG_NOTHING_TO_PAY, "accountId");
        }

        // IF CONF-PAY-YES -> post; ELSE -> prompt for confirmation (preview the balance).
        if (!confirmed) {
            return new BillPaymentResponse(accountId, currentBalance, confirm, MSG_CONFIRM_PROMPT);
        }

        // READ-CXACAIX-FILE: card number cross-referenced to the account (CXACAIX AIX).
        final String cardNumber = loadCardNumber(account.getAcctId());

        // STARTBR/READPREV/ENDBR HIGH-VALUES browse + ADD 1: next transaction id.
        final String tranId = nextTransactionId();

        // MOVE ACCT-CURR-BAL TO TRAN-AMT: pay in full.
        final BigDecimal paymentAmount = currentBalance;
        final String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);

        // WRITE-TRANSACT-FILE.
        final Transaction payment = buildPaymentTransaction(tranId, cardNumber, paymentAmount, timestamp);
        transactionRepository.save(payment);

        // COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT, then UPDATE-ACCTDAT-FILE.
        final BigDecimal newBalance = currentBalance.subtract(paymentAmount);
        account.setAcctCurrBal(newBalance);
        accountRepository.save(account);

        LOG.info("Bill payment posted: accountId={}, tranId={}, amount={}",
                accountId, tranId, paymentAmount);

        // WRITE-TRANSACT-FILE success message (NOTE: two spaces after the first period).
        final String successMessage = "Payment successful.  Your Transaction ID is " + tranId + ".";
        return new BillPaymentResponse(accountId, newBalance, confirm, successMessage);
    }

    /** READ-ACCTDAT-FILE: parse the numeric account id and load the account. */
    private Account loadAccount(String accountId) {
        final long acctId;
        try {
            acctId = Long.parseLong(accountId);
        } catch (NumberFormatException ex) {
            // A non-numeric id cannot key ACCTDAT -> NOTFND path.
            throw new RecordNotFoundException(MSG_ACCT_NOT_FOUND, ex);
        }
        final Optional<Account> accountOpt = accountRepository.findById(acctId);
        return accountOpt.orElseThrow(() -> new RecordNotFoundException(MSG_ACCT_NOT_FOUND));
    }

    /** READ-CXACAIX-FILE: first card cross-reference for the account (NONUNIQUE AIX). */
    private String loadCardNumber(Long acctId) {
        final List<CardCrossReference> xrefs = cardCrossReferenceRepository.findByXrefAcctId(acctId);
        if (xrefs.isEmpty()) {
            throw new RecordNotFoundException(MSG_ACCT_NOT_FOUND);
        }
        return xrefs.get(0).getXrefCardNum();
    }

    /** Browse-to-end + increment; zero-pad to 16 digits. Empty store -> first id is 1. */
    private String nextTransactionId() {
        final String maxTranId = transactionRepository.findMaxTranId();
        long lastId = 0L;
        if (maxTranId != null && !maxTranId.isBlank()) {
            lastId = Long.parseLong(maxTranId.trim());
        }
        return String.format(TRAN_ID_FORMAT, lastId + 1L);
    }

    /** Builds the payment transaction with the fixed COBIL00C field values. */
    private Transaction buildPaymentTransaction(String tranId, String cardNumber,
                                                BigDecimal amount, String timestamp) {
        final Transaction tran = new Transaction();
        tran.setTranId(tranId);
        tran.setTranTypeCd(PAYMENT_TRAN_TYPE_CD);
        tran.setTranCatCd(PAYMENT_TRAN_CAT_CD);
        tran.setTranSource(PAYMENT_TRAN_SOURCE);
        tran.setTranDesc(PAYMENT_TRAN_DESC);
        tran.setTranAmt(amount);
        tran.setTranMerchantId(PAYMENT_MERCHANT_ID);
        tran.setTranMerchantName(PAYMENT_MERCHANT_NAME);
        tran.setTranMerchantCity(PAYMENT_MERCHANT_CITY);
        tran.setTranMerchantZip(PAYMENT_MERCHANT_ZIP);
        tran.setTranCardNum(cardNumber);
        tran.setTranOrigTs(timestamp);
        tran.setTranProcTs(timestamp);
        return tran;
    }
}

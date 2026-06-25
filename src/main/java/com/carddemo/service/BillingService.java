/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carddemo.service;

import com.carddemo.dto.BillingDto;
import com.carddemo.entity.Account;
import com.carddemo.entity.CardXref;
import com.carddemo.entity.Transaction;
import com.carddemo.enums.TransactionTypeCode;
import com.carddemo.exception.BusinessRuleException;
import com.carddemo.exception.DuplicateRecordException;
import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.exception.ValidationException;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardXrefRepository;
import com.carddemo.repository.TransactionRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bill-payment service backing {@code POST /api/billing/pay} (CICS transaction
 * {@code CB00} / program {@code COBIL00C}) at source commit {@code 27d6c6f}.
 *
 * <p>This service translates {@code app/cbl/COBIL00C.cbl} &mdash; the online
 * bill-payment program &mdash; into an idiomatic, layered Spring service. It
 * pays the account balance in full: a single payment {@link Transaction} is
 * written and the {@link Account} current balance is decremented to zero, both
 * as one atomic unit of work that reproduces the legacy CICS implicit commit.
 * If either write fails, both are rolled back.</p>
 *
 * <p>The procedural {@code PROCESS-ENTER-KEY} paragraph is decomposed into
 * focused private methods rather than transliterated paragraph ranges:</p>
 * <ul>
 *   <li><strong>Validate input</strong> &mdash; the account identifier must be
 *       present and the confirmation flag must be {@code Y}/{@code N}; a blank
 *       flag re-prompts and {@code N} cancels the payment without any write.</li>
 *   <li><strong>Load</strong> &mdash; read the account by key and resolve the
 *       card number through the {@link CardXref} account alternate index
 *       (legacy {@code CXACAIX} read).</li>
 *   <li><strong>Guard</strong> &mdash; a non-positive balance yields the
 *       "nothing to pay" business-rule failure.</li>
 *   <li><strong>Post</strong> &mdash; auto-generate the next transaction
 *       identifier, build the payment transaction with the byte-exact legacy
 *       field values, persist it, decrement the balance and persist the
 *       account &mdash; all inside one {@code @Transactional} scope.</li>
 * </ul>
 *
 * <p>Monetary values are held as {@link BigDecimal} scaled to two fraction
 * digits using {@link RoundingMode#HALF_EVEN}; {@code double}/{@code float} are
 * never used so that decimal arithmetic stays exact. The next-identifier
 * generation is reproduced internally (the legacy program writes the payment
 * transaction directly), so no other service is invoked for it.</p>
 *
 * <p>The component is stateless and therefore thread-safe; all collaborators are
 * supplied through constructor injection.</p>
 */
@Service
public class BillingService {

    /** Scale applied to every monetary amount, matching COBOL {@code PIC S9(10)V99}. */
    private static final int MONEY_SCALE = 2;

    /** Width of the zero-padded numeric transaction identifier ({@code TRAN-ID PIC X(16)}). */
    private static final String TRAN_ID_FORMAT = "%016d";

    /** Transaction identifier seed used when no transaction yet exists. */
    private static final long FIRST_TRAN_ID = 1L;

    /** {@code TRAN-TYPE-CD} value hardcoded by the legacy bill-payment write. */
    private static final String TRAN_TYPE_PAYMENT_CODE = "02";

    /** {@code TRAN-CAT-CD} value hardcoded by the legacy bill-payment write. */
    private static final int BILL_PAYMENT_CATEGORY_CODE = 2;

    /** {@code TRAN-SOURCE} value hardcoded by the legacy bill-payment write. */
    private static final String TRAN_SOURCE_POS_TERM = "POS TERM";

    /** {@code TRAN-DESC} value hardcoded by the legacy bill-payment write. */
    private static final String TRAN_DESC_BILL_PAYMENT = "BILL PAYMENT - ONLINE";

    /** {@code TRAN-MERCHANT-ID} value hardcoded by the legacy bill-payment write. */
    private static final long BILL_PAYMENT_MERCHANT_ID = 999999999L;

    /** {@code TRAN-MERCHANT-NAME} value hardcoded by the legacy bill-payment write. */
    private static final String BILL_PAYMENT_MERCHANT_NAME = "BILL PAYMENT";

    /** {@code TRAN-MERCHANT-CITY}/{@code TRAN-MERCHANT-ZIP} value hardcoded by the legacy write. */
    private static final String NOT_APPLICABLE = "N/A";

    /** Affirmative confirmation flag ({@code CONFIRMI = 'Y'}). */
    private static final String CONFIRM_YES = "Y";

    /** Negative confirmation flag ({@code CONFIRMI = 'N'}); cancels the payment. */
    private static final String CONFIRM_NO = "N";

    /** Byte-exact message for an empty account identifier. */
    private static final String MSG_ACCT_EMPTY = "Acct ID can NOT be empty...";

    /** Byte-exact message re-prompting for the confirmation flag. */
    private static final String MSG_CONFIRM_REQUIRED = "Confirm to make a bill payment...";

    /** Byte-exact message for an out-of-range confirmation flag. */
    private static final String MSG_CONFIRM_INVALID = "Invalid value. Valid values are (Y/N)...";

    /** Byte-exact message for a missing account or cross-reference. */
    private static final String MSG_ACCT_NOT_FOUND = "Account ID NOT found...";

    /** Byte-exact message for a zero or negative balance. */
    private static final String MSG_NOTHING_TO_PAY = "You have nothing to pay...";

    /** Byte-exact message for a duplicate transaction identifier. */
    private static final String MSG_TRAN_DUPLICATE = "Tran ID already exist...";

    private final AccountRepository accountRepository;
    private final CardXrefRepository cardXrefRepository;
    private final TransactionRepository transactionRepository;
    private final DateValidationService dateValidationService;

    /**
     * Creates a bill-payment service with its collaborating repositories and the
     * shared date utility.
     *
     * @param accountRepository     account master access ({@code ACCTDAT})
     * @param cardXrefRepository    card cross-reference access ({@code CXACAIX})
     * @param transactionRepository posted-transaction access ({@code TRANSACT})
     * @param dateValidationService supplier of the 26-character processing
     *                              timestamp
     */
    public BillingService(AccountRepository accountRepository,
            CardXrefRepository cardXrefRepository,
            TransactionRepository transactionRepository,
            DateValidationService dateValidationService) {
        this.accountRepository = accountRepository;
        this.cardXrefRepository = cardXrefRepository;
        this.transactionRepository = transactionRepository;
        this.dateValidationService = dateValidationService;
    }

    /**
     * Pays the supplied account's balance in full, writing one payment
     * transaction and decrementing the account balance to zero as a single
     * atomic unit of work (CICS {@code SYNCPOINT} parity).
     *
     * <p>The rollback boundary spans both writes: if persisting the transaction
     * or the updated account fails for any reason, neither change is committed.</p>
     *
     * @param request the bill-payment request carrying the account identifier
     *                and the {@code Y}/{@code N} confirmation flag
     * @return the payment outcome echoing the account identifier and the updated
     *         balance; on cancellation ({@code confirm = N}) the balance is
     *         {@code null} and no write occurs
     * @throws ValidationException     if the account identifier is empty, the
     *                                 confirmation flag is blank, or the flag is
     *                                 neither {@code Y} nor {@code N}
     * @throws RecordNotFoundException if the account or its card cross-reference
     *                                 cannot be located
     * @throws BusinessRuleException   if the account balance is zero or negative
     * @throws DuplicateRecordException if the generated transaction identifier
     *                                 already exists
     */
    @Transactional(rollbackFor = Exception.class)
    public BillingDto.PayResponse payBill(BillingDto.PayRequest request) {
        String accountIdText = request.accountId();
        if (accountIdText == null || accountIdText.isBlank()) {
            throw new ValidationException(MSG_ACCT_EMPTY);
        }

        String confirm = request.confirm();
        if (confirm == null || confirm.isBlank()) {
            throw new ValidationException(MSG_CONFIRM_REQUIRED);
        }
        String confirmFlag = confirm.trim();
        if (CONFIRM_NO.equalsIgnoreCase(confirmFlag)) {
            return new BillingDto.PayResponse(accountIdText, null, CONFIRM_NO);
        }
        if (!CONFIRM_YES.equalsIgnoreCase(confirmFlag)) {
            throw new ValidationException(MSG_CONFIRM_INVALID);
        }

        long accountId = parseAccountId(accountIdText);
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new RecordNotFoundException(MSG_ACCT_NOT_FOUND));

        BigDecimal currentBalance = account.getCurrBal();
        if (currentBalance == null || currentBalance.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessRuleException(MSG_NOTHING_TO_PAY);
        }

        String cardNumber = resolveCardNumber(accountId);
        BigDecimal newBalance = postPayment(account, currentBalance, cardNumber);

        return new BillingDto.PayResponse(accountIdText, newBalance, CONFIRM_YES);
    }

    /**
     * Parses the textual account identifier into its numeric key, preserving the
     * legacy behaviour of treating an unusable identifier as "not found".
     *
     * @param accountIdText the account identifier text
     * @return the numeric account identifier
     * @throws RecordNotFoundException if the text is not a valid numeric key
     */
    private long parseAccountId(String accountIdText) {
        try {
            return Long.parseLong(accountIdText.trim());
        } catch (NumberFormatException ex) {
            throw new RecordNotFoundException(MSG_ACCT_NOT_FOUND, ex);
        }
    }

    /**
     * Resolves the card number for the payment transaction through the account
     * alternate index (legacy {@code CXACAIX} read).
     *
     * @param accountId the owning account identifier
     * @return the resolved card number
     * @throws RecordNotFoundException if no cross-reference exists for the account
     */
    private String resolveCardNumber(long accountId) {
        List<CardXref> crossReferences = cardXrefRepository.findByXrefAcctId(accountId);
        if (crossReferences.isEmpty()) {
            throw new RecordNotFoundException(MSG_ACCT_NOT_FOUND);
        }
        return crossReferences.get(0).getXrefCardNum();
    }

    /**
     * Writes the full-balance payment transaction and decrements the account
     * balance within the caller's transaction.
     *
     * @param account        the loaded account to update
     * @param currentBalance the current balance, paid in full
     * @param cardNumber     the resolved card number for the transaction
     * @return the new account balance after the payment
     * @throws DuplicateRecordException if the generated identifier already exists
     */
    private BigDecimal postPayment(Account account, BigDecimal currentBalance, String cardNumber) {
        String tranId = nextTransactionId();
        if (transactionRepository.existsById(tranId)) {
            throw new DuplicateRecordException(MSG_TRAN_DUPLICATE);
        }

        BigDecimal paymentAmount = currentBalance.setScale(MONEY_SCALE, RoundingMode.HALF_EVEN);
        String timestamp = dateValidationService.currentTimestamp();

        Transaction payment = new Transaction(
                tranId,
                TransactionTypeCode.fromCode(TRAN_TYPE_PAYMENT_CODE),
                BILL_PAYMENT_CATEGORY_CODE,
                TRAN_SOURCE_POS_TERM,
                TRAN_DESC_BILL_PAYMENT,
                paymentAmount,
                BILL_PAYMENT_MERCHANT_ID,
                BILL_PAYMENT_MERCHANT_NAME,
                NOT_APPLICABLE,
                NOT_APPLICABLE,
                cardNumber,
                timestamp,
                timestamp);
        transactionRepository.save(payment);

        BigDecimal newBalance = currentBalance.subtract(paymentAmount)
                .setScale(MONEY_SCALE, RoundingMode.HALF_EVEN);
        account.setCurrBal(newBalance);
        accountRepository.save(account);

        return newBalance;
    }

    /**
     * Generates the next transaction identifier by incrementing the highest
     * existing identifier, reproducing the legacy {@code STARTBR}/{@code READPREV}
     * "find last id then add one" step; when no transaction exists the sequence
     * starts at one.
     *
     * @return the next 16-character zero-padded transaction identifier
     */
    private String nextTransactionId() {
        long nextId = transactionRepository.findTopByOrderByTranIdDesc()
                .map(existing -> Long.parseLong(existing.getTranId().trim()) + 1L)
                .orElse(FIRST_TRAN_ID);
        return String.format(TRAN_ID_FORMAT, nextId);
    }
}

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

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

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
 *       account &mdash; all inside one short {@code REQUIRES_NEW} transaction
 *       per attempt.</li>
 * </ul>
 *
 * <p>Monetary values are held as {@link BigDecimal} scaled to two fraction
 * digits using {@link RoundingMode#HALF_EVEN}; {@code double}/{@code float} are
 * never used so that decimal arithmetic stays exact. The next-identifier
 * generation is reproduced internally (the legacy program writes the payment
 * transaction directly), so no other service is invoked for it.</p>
 *
 * <p>To reproduce the serialized record-locking guarantee CICS/VSAM provided,
 * identifier generation and the two writes execute inside a per-attempt
 * {@code REQUIRES_NEW} transaction that is retried on a transaction-identifier
 * primary-key collision; legitimate concurrent payments therefore all succeed
 * with unique sequential ids instead of returning a server error
 * (QA Issue #6).</p>
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

    /**
     * Maximum number of generate-id-then-insert attempts for the payment
     * transaction before surfacing a duplicate-identifier failure. Each attempt
     * runs in its own short transaction; a {@code DataIntegrityViolationException}
     * on the primary-key insert means a concurrent request claimed the same
     * identifier first, so the highest id is re-read and the payment retried —
     * reproducing the serialized record-locking guarantee CICS/VSAM provided.
     */
    private static final int MAX_TRAN_ID_ATTEMPTS = 25;

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

    /**
     * Byte-exact prefix of the {@code COBIL00C} payment-success banner. The legacy
     * program builds the confirmation with
     * {@code STRING 'Payment successful. ' ' Your Transaction ID is ' TRAN-ID '.'},
     * concatenating a literal ending in {@code ". "} with a literal beginning in
     * {@code " Your"} — yielding exactly two spaces after the first period —
     * followed by the 16-character transaction id and a trailing period. The id
     * and trailing period are appended at the call site.
     */
    private static final String MSG_PAY_SUCCESS_PREFIX = "Payment successful.  Your Transaction ID is ";

    private final AccountRepository accountRepository;
    private final CardXrefRepository cardXrefRepository;
    private final TransactionRepository transactionRepository;
    private final DateValidationService dateValidationService;

    /**
     * Programmatic transaction boundary used to make each generate-id-then-write
     * payment attempt its own short unit of work. It is configured with
     * {@code PROPAGATION_REQUIRES_NEW} so that a primary-key collision rolls back
     * only the failed attempt — leaving the retry free to re-read the highest
     * identifier and try again — rather than poisoning an enclosing transaction.
     */
    private final TransactionTemplate transactionTemplate;

    /**
     * Creates a bill-payment service with its collaborating repositories, the
     * shared date utility, and the platform transaction manager used to scope
     * each payment attempt.
     *
     * @param accountRepository     account master access ({@code ACCTDAT})
     * @param cardXrefRepository    card cross-reference access ({@code CXACAIX})
     * @param transactionRepository posted-transaction access ({@code TRANSACT})
     * @param dateValidationService supplier of the 26-character processing
     *                              timestamp
     * @param transactionManager    platform transaction manager backing the
     *                              per-attempt {@link TransactionTemplate}
     */
    public BillingService(AccountRepository accountRepository,
            CardXrefRepository cardXrefRepository,
            TransactionRepository transactionRepository,
            DateValidationService dateValidationService,
            PlatformTransactionManager transactionManager) {
        this.accountRepository = accountRepository;
        this.cardXrefRepository = cardXrefRepository;
        this.transactionRepository = transactionRepository;
        this.dateValidationService = dateValidationService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Pays the supplied account's balance in full, writing one payment
     * transaction and decrementing the account balance to zero as a single
     * atomic unit of work (CICS {@code SYNCPOINT} parity).
     *
     * <p>Each attempt runs in its own {@code REQUIRES_NEW} transaction whose
     * rollback boundary spans both writes: if persisting the transaction or the
     * updated account fails for any reason, neither change is committed. When the
     * failure is a transaction-identifier primary-key collision with a concurrent
     * payment, the highest identifier is re-read and the attempt retried, so
     * legitimate concurrent payments all succeed with unique sequential ids
     * rather than surfacing a server error (QA Issue #6 concurrency parity).</p>
     *
     * <p>Up-front input validation (account-id presence and confirmation flag) is
     * performed once, outside the retry boundary, because it neither writes nor
     * benefits from a fresh read. The account is (re-)loaded <em>inside</em> each
     * attempt so its {@code @Version} is current and the zero-or-negative-balance
     * guard reflects the latest committed state.</p>
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
     * @throws DuplicateRecordException if a unique transaction identifier cannot
     *                                 be generated after the configured number of
     *                                 attempts
     */
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
            // Cancel path: no payment is written, so transactionId and the success
            // banner are null (omitted from the JSON response via @JsonInclude).
            return new BillingDto.PayResponse(accountIdText, null, CONFIRM_NO, null, null);
        }
        if (!CONFIRM_YES.equalsIgnoreCase(confirmFlag)) {
            throw new ValidationException(MSG_CONFIRM_INVALID);
        }

        long accountId = parseAccountId(accountIdText);
        PaymentResult result = executePaymentWithRetry(accountId);

        return new BillingDto.PayResponse(accountIdText, result.newBalance(), CONFIRM_YES,
                result.tranId(), MSG_PAY_SUCCESS_PREFIX + result.tranId() + ".");
    }

    /**
     * Outcome of a posted bill payment: the generated transaction identifier and
     * the account balance after the payment. Surfacing the identifier lets
     * {@link #payBill} echo it and render the byte-exact {@code COBIL00C} success
     * banner that names the transaction id.
     *
     * @param tranId     the 16-character zero-padded transaction identifier
     * @param newBalance the account balance after the full-balance payment
     */
    private record PaymentResult(String tranId, BigDecimal newBalance) {
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
     * Posts the full-balance payment, retrying on a transaction-identifier
     * primary-key collision so that concurrent payments serialize cleanly the way
     * CICS/VSAM record locking did, rather than surfacing a {@code 500}
     * (QA Issue #6).
     *
     * <p>Each attempt runs in its own {@code REQUIRES_NEW} transaction (via
     * {@link #transactionTemplate}) that:</p>
     * <ol>
     *   <li>(re-)loads the account by key inside the attempt so its
     *       {@code @Version} is current and the zero-or-negative-balance guard
     *       reflects the latest committed state;</li>
     *   <li>resolves the card number through the account alternate index;</li>
     *   <li>generates the next identifier from the highest existing id;</li>
     *   <li>{@code insert}s the payment (INSERT-only) so a duplicate-key violation is
     *       raised <em>inside</em> the attempt (rolling it back) instead of at an
     *       outer commit; and</li>
     *   <li>decrements the account balance to zero.</li>
     * </ol>
     *
     * <p>A {@link DataIntegrityViolationException} means a concurrent payment
     * claimed the same identifier first; the highest id is re-read and the
     * payment retried. Validation failures (account-not-found,
     * nothing-to-pay) are not {@code DataIntegrityViolationException}s, so they
     * propagate immediately without consuming a retry. After
     * {@link #MAX_TRAN_ID_ATTEMPTS} unsuccessful attempts a
     * {@link DuplicateRecordException} is raised.</p>
     *
     * @param accountId the numeric account identifier to pay in full
     * @return the generated transaction identifier and the new account balance
     *         after the payment
     * @throws RecordNotFoundException  if the account or its card cross-reference
     *                                  cannot be located
     * @throws BusinessRuleException    if the account balance is zero or negative
     * @throws DuplicateRecordException if a unique identifier cannot be generated
     *                                  within {@link #MAX_TRAN_ID_ATTEMPTS} attempts
     */
    private PaymentResult executePaymentWithRetry(long accountId) {
        DataIntegrityViolationException lastCollision = null;
        for (int attempt = 1; attempt <= MAX_TRAN_ID_ATTEMPTS; attempt++) {
            try {
                return transactionTemplate.execute(status -> {
                    Account account = accountRepository.findById(accountId)
                            .orElseThrow(() -> new RecordNotFoundException(MSG_ACCT_NOT_FOUND));

                    BigDecimal currentBalance = account.getCurrBal();
                    if (currentBalance == null || currentBalance.compareTo(BigDecimal.ZERO) <= 0) {
                        throw new BusinessRuleException(MSG_NOTHING_TO_PAY);
                    }

                    String cardNumber = resolveCardNumber(accountId);
                    String tranId = nextTransactionId();
                    BigDecimal paymentAmount = currentBalance.setScale(MONEY_SCALE, RoundingMode.HALF_EVEN);
                    String timestamp = dateValidationService.currentTimestamp();

                    Transaction payment = new Transaction(
                            tranId,
                            TransactionTypeCode.PAYMENT.getCode(),
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
                    transactionRepository.insert(payment);

                    BigDecimal newBalance = currentBalance.subtract(paymentAmount)
                            .setScale(MONEY_SCALE, RoundingMode.HALF_EVEN);
                    account.setCurrBal(newBalance);
                    accountRepository.save(account);

                    return new PaymentResult(tranId, newBalance);
                });
            } catch (DataIntegrityViolationException ex) {
                // A concurrent payment claimed the same transaction id; re-read
                // the highest id on the next attempt.
                lastCollision = ex;
            }
        }
        throw new DuplicateRecordException(MSG_TRAN_DUPLICATE, lastCollision);
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

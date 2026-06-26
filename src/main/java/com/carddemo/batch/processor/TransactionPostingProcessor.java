/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.carddemo.batch.processor;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.carddemo.entity.Account;
import com.carddemo.entity.CardXref;
import com.carddemo.entity.DailyTransaction;
import com.carddemo.entity.Transaction;
import com.carddemo.entity.TransactionCategoryBalance;
import com.carddemo.entity.TransactionCategoryBalanceId;
import com.carddemo.enums.RejectReasonCode;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardXrefRepository;
import com.carddemo.repository.TransactionCategoryBalanceRepository;
import com.carddemo.service.DateValidationService;

/**
 * Spring Batch {@link ItemProcessor} that posts one staged daily-transaction
 * record, the migration of COBOL batch program {@code CBTRN02C} (the posting
 * engine of the {@code POSTTRAN} job) at source commit {@code 27d6c6f}.
 *
 * <p>For each {@link DailyTransaction} the processor runs the ordered
 * validation cascade and, when the record is valid, posts it: it builds the
 * {@link Transaction} to persist, upserts the affected transaction-category
 * balance, and applies the balance deltas to the owning account. The validation
 * cascade is, in order:</p>
 * <ol>
 *   <li><b>Card cross-reference existence</b> &mdash; a missing card number
 *       yields {@link RejectReasonCode#CARD_NOT_FOUND} and stops the cascade.</li>
 *   <li><b>Account existence</b> &mdash; evaluated only when the card resolved;
 *       a missing account yields {@link RejectReasonCode#ACCOUNT_NOT_FOUND} and
 *       stops the cascade.</li>
 *   <li><b>Credit limit</b> &mdash; rejects with
 *       {@link RejectReasonCode#OVER_CREDIT_LIMIT} when the projected cycle
 *       balance exceeds the credit limit.</li>
 *   <li><b>Account expiration</b> &mdash; rejects with
 *       {@link RejectReasonCode#ACCOUNT_EXPIRED} when the transaction was
 *       received after the account expiration date.</li>
 * </ol>
 *
 * <p>The credit-limit and expiration checks are evaluated as two independent,
 * sequential conditions against the same account: both are always evaluated
 * once the account is found, and a record that fails both carries
 * {@link RejectReasonCode#ACCOUNT_EXPIRED} (the later assignment wins).</p>
 *
 * <p>Every record yields a {@link PostingResult}, never {@code null}: a valid
 * record produces a {@link PostingResult.Posted} carrying its posted
 * {@link Transaction}, and a rejected record produces a
 * {@link PostingResult.Rejected} carrying the reconstructed 350-byte
 * {@code CVTRA06Y} record image plus its {@link RejectReasonCode}. The downstream
 * {@code PostingResultWriter} fans both arms out to their durable sinks &mdash;
 * posted transactions to the database and rejected records to the 430-byte
 * {@code DALYREJS}-equivalent S3 object &mdash; so no rejected record is ever
 * silently dropped. For posted records the category-balance and account updates
 * are dependent read-modify-writes performed here. The whole posting operation
 * runs under {@link Transactional @Transactional(rollbackFor = Exception.class)}
 * so the category-balance update, the account update, and the chunk writer's
 * transaction insert commit as one unit of work; the {@link Account} entity's
 * {@code @Version} column provides optimistic-locking detection.</p>
 *
 * <p>This processor deliberately records no Micrometer metrics. Record-count and
 * amount metrics are owned exclusively by the durable writers
 * ({@code PostedTransactionWriter} and {@link
 * com.carddemo.batch.writer.RejectTransactionWriter}), which increment their
 * meters only after a successful persist/upload, so a chunk that rolls back after
 * processing is never counted.</p>
 *
 * <p>All monetary arithmetic uses {@link BigDecimal} at scale {@value #MONEY_SCALE}
 * with {@link RoundingMode#HALF_EVEN} and {@link BigDecimal#compareTo}; no binary
 * floating point participates in a financial decision.</p>
 */
@Component
public class TransactionPostingProcessor implements ItemProcessor<DailyTransaction, PostingResult> {

    private static final Logger log = LoggerFactory.getLogger(TransactionPostingProcessor.class);

    /** Scale of every monetary {@link BigDecimal} (COBOL {@code V99}). */
    private static final int MONEY_SCALE = 2;

    /** Rounding applied to every monetary computation (COBOL {@code ROUNDED}). */
    private static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_EVEN;

    /** Length of the date prefix compared against the account expiration date. */
    private static final int DATE_PREFIX_LENGTH = 10;

    private final CardXrefRepository cardXrefRepository;
    private final AccountRepository accountRepository;
    private final TransactionCategoryBalanceRepository transactionCategoryBalanceRepository;
    private final DateValidationService dateValidationService;

    /**
     * Creates the processor with its auto-configured collaborators.
     *
     * @param cardXrefRepository                   card cross-reference lookup
     *                                             ({@code XREFFILE}); must not be
     *                                             {@code null}
     * @param accountRepository                    account master lookup and
     *                                             update ({@code ACCTFILE}); must
     *                                             not be {@code null}
     * @param transactionCategoryBalanceRepository transaction-category-balance
     *                                             upsert ({@code TCATBAL}); must
     *                                             not be {@code null}
     * @param dateValidationService                supplies the 26-character
     *                                             processing timestamp; must not
     *                                             be {@code null}
     */
    public TransactionPostingProcessor(
            CardXrefRepository cardXrefRepository,
            AccountRepository accountRepository,
            TransactionCategoryBalanceRepository transactionCategoryBalanceRepository,
            DateValidationService dateValidationService) {
        this.cardXrefRepository = cardXrefRepository;
        this.accountRepository = accountRepository;
        this.transactionCategoryBalanceRepository = transactionCategoryBalanceRepository;
        this.dateValidationService = dateValidationService;
    }

    /**
     * Validates and posts a single staged daily transaction.
     *
     * @param item the staged daily transaction to post; must not be {@code null}
     * @return a {@link PostingResult.Posted} carrying the posted
     *         {@link Transaction} when the record is valid, or a
     *         {@link PostingResult.Rejected} carrying the 350-byte record image and
     *         {@link RejectReasonCode} when the record is rejected; never
     *         {@code null}
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public PostingResult process(DailyTransaction item) {
        ValidationOutcome outcome = validate(item);
        if (outcome.reason() != RejectReasonCode.NONE) {
            return reject(item, outcome.reason());
        }

        Transaction posted = buildPostedTransaction(item);
        updateCategoryBalance(item, outcome.xref().getXrefAcctId());

        RejectReasonCode updateReason = updateAccount(item, outcome.account().getAcctId());
        if (updateReason != RejectReasonCode.NONE) {
            return reject(item, updateReason);
        }

        return new PostingResult.Posted(posted);
    }

    /**
     * Runs the ordered validation cascade and resolves the references required to
     * post the transaction.
     *
     * @param item the staged daily transaction
     * @return the validation outcome carrying the reject reason
     *         ({@link RejectReasonCode#NONE} when valid) and, when resolved, the
     *         card cross-reference and account
     */
    private ValidationOutcome validate(DailyTransaction item) {
        CardXref xref = cardXrefRepository.findById(item.getCardNum()).orElse(null);
        if (xref == null) {
            return new ValidationOutcome(RejectReasonCode.CARD_NOT_FOUND, null, null);
        }

        Account account = accountRepository.findById(xref.getXrefAcctId()).orElse(null);
        if (account == null) {
            return new ValidationOutcome(RejectReasonCode.ACCOUNT_NOT_FOUND, xref, null);
        }

        RejectReasonCode reason = RejectReasonCode.NONE;

        BigDecimal projectedBalance = scaled(account.getCurrCycCredit()
                .subtract(account.getCurrCycDebit())
                .add(item.getTranAmt()));
        if (account.getCreditLimit().compareTo(projectedBalance) < 0) {
            reason = RejectReasonCode.OVER_CREDIT_LIMIT;
        }

        String originalDate = datePrefix(item.getOrigTs());
        String expirationDate = (account.getExpirationDate() == null) ? "" : account.getExpirationDate();
        if (expirationDate.compareTo(originalDate) < 0) {
            reason = RejectReasonCode.ACCOUNT_EXPIRED;
        }

        return new ValidationOutcome(reason, xref, account);
    }

    /**
     * Maps the staged daily transaction onto a new {@link Transaction}, stamping
     * a freshly generated 26-character processing timestamp.
     *
     * @param item the staged daily transaction
     * @return the populated transaction to persist
     */
    private Transaction buildPostedTransaction(DailyTransaction item) {
        return new Transaction(
                item.getTranId(),
                item.getTranTypeCd(),
                item.getTranCatCd(),
                item.getTranSource(),
                item.getTranDesc(),
                item.getTranAmt(),
                item.getMerchantId(),
                item.getMerchantName(),
                item.getMerchantCity(),
                item.getMerchantZip(),
                item.getCardNum(),
                item.getOrigTs(),
                dateValidationService.currentTimestamp());
    }

    /**
     * Upserts the transaction-category balance for the posted transaction: a new
     * balance row is created starting from the transaction amount when none
     * exists, otherwise the amount is added to the running balance.
     *
     * @param item   the staged daily transaction
     * @param acctId the owning account identifier
     */
    private void updateCategoryBalance(DailyTransaction item, Long acctId) {
        TransactionCategoryBalanceId id = new TransactionCategoryBalanceId(
                acctId, item.getTranTypeCd(), item.getTranCatCd());

        TransactionCategoryBalance balance = transactionCategoryBalanceRepository.findById(id).orElse(null);
        if (balance == null) {
            balance = new TransactionCategoryBalance(id, scaled(item.getTranAmt()));
        } else {
            balance.setTranCatBal(scaled(balance.getTranCatBal().add(item.getTranAmt())));
        }
        transactionCategoryBalanceRepository.save(balance);
    }

    /**
     * Applies the transaction amount to the account balances: the current balance
     * always changes, and the amount is added to the cycle-credit bucket when it
     * is non-negative or to the cycle-debit bucket otherwise.
     *
     * @param item   the staged daily transaction
     * @param acctId the owning account identifier
     * @return {@link RejectReasonCode#NONE} on success, or
     *         {@link RejectReasonCode#ACCOUNT_NOT_FOUND_ON_UPDATE} when the
     *         account cannot be located for update
     */
    private RejectReasonCode updateAccount(DailyTransaction item, Long acctId) {
        Account account = accountRepository.findById(acctId).orElse(null);
        if (account == null) {
            return RejectReasonCode.ACCOUNT_NOT_FOUND_ON_UPDATE;
        }

        BigDecimal amount = item.getTranAmt();
        account.setCurrBal(scaled(account.getCurrBal().add(amount)));
        if (amount.compareTo(BigDecimal.ZERO) >= 0) {
            account.setCurrCycCredit(scaled(account.getCurrCycCredit().add(amount)));
        } else {
            account.setCurrCycDebit(scaled(account.getCurrCycDebit().add(amount)));
        }
        accountRepository.save(account);
        return RejectReasonCode.NONE;
    }

    /**
     * Builds the rejected-record result, reconstructing the 350-byte
     * {@code CVTRA06Y} record image so the downstream writer can emit the
     * 430-byte {@code DALYREJS}-equivalent reject record. This mirrors the COBOL
     * {@code 2500-WRITE-REJECT-REC} paragraph, which writes the original daily
     * transaction image followed by the reject reason.
     *
     * <p>No metric is incremented here; the rejected-record counter is owned by
     * {@link com.carddemo.batch.writer.RejectTransactionWriter} and increments
     * only after the reject record is durably written.</p>
     *
     * @param item   the rejected daily transaction
     * @param reason the reject reason; never {@link RejectReasonCode#NONE}
     * @return a {@link PostingResult.Rejected} carrying the 350-byte record image
     *         and the reject reason
     */
    private PostingResult reject(DailyTransaction item, RejectReasonCode reason) {
        if (log.isDebugEnabled()) {
            log.debug("Rejected daily transaction {}: {} {}",
                    item.getTranId(), reason.getFormattedCode(), reason.getDescription());
        }
        return new PostingResult.Rejected(DailyTransactionRecordImage.render(item), reason);
    }

    /**
     * Returns the leading {@value #DATE_PREFIX_LENGTH}-character date prefix of a
     * timestamp (the {@code YYYY-MM-DD} portion), or the value unchanged when it
     * is shorter, treating {@code null} as empty.
     *
     * @param timestamp the original timestamp value
     * @return the comparable date prefix
     */
    private static String datePrefix(String timestamp) {
        if (timestamp == null) {
            return "";
        }
        return (timestamp.length() >= DATE_PREFIX_LENGTH)
                ? timestamp.substring(0, DATE_PREFIX_LENGTH)
                : timestamp;
    }

    /**
     * Normalizes a monetary value to the canonical scale and rounding.
     *
     * @param value the value to normalize; must not be {@code null}
     * @return {@code value} at scale {@value #MONEY_SCALE} using
     *         {@link RoundingMode#HALF_EVEN}
     */
    private static BigDecimal scaled(BigDecimal value) {
        return value.setScale(MONEY_SCALE, MONEY_ROUNDING);
    }

    /**
     * Immutable result of the validation cascade: the reject reason plus the
     * references resolved during validation and reused when posting.
     *
     * @param reason  the reject reason ({@link RejectReasonCode#NONE} when valid)
     * @param xref    the resolved card cross-reference, or {@code null} when the
     *                card lookup failed
     * @param account the resolved account, or {@code null} when the account
     *                lookup failed
     */
    private record ValidationOutcome(RejectReasonCode reason, CardXref xref, Account account) {
    }
}

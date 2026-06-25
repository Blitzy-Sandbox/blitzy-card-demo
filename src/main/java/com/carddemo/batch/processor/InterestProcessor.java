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

import com.carddemo.entity.Account;
import com.carddemo.entity.CardXref;
import com.carddemo.entity.DisclosureGroup;
import com.carddemo.entity.DisclosureGroupId;
import com.carddemo.entity.Transaction;
import com.carddemo.entity.TransactionCategoryBalance;
import com.carddemo.enums.TransactionSource;
import com.carddemo.enums.TransactionTypeCode;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardXrefRepository;
import com.carddemo.repository.DisclosureGroupRepository;
import com.carddemo.service.DateValidationService;
import io.micrometer.core.instrument.Counter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.annotation.AfterStep;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Spring Batch {@link ItemProcessor} that posts monthly interest for each
 * transaction-category balance, the migration target of the legacy COBOL batch
 * program {@code CBACT04C} (interest calculator) driven by JCL
 * {@code app/jcl/INTCALC.jcl} ({@code STEP15 EXEC PGM=CBACT04C,PARM='2022071800'})
 * at source commit {@code 27d6c6f}.
 *
 * <p>The chunk reader streams {@link TransactionCategoryBalance} rows (the COBOL
 * {@code TCATBALF} KSDS) in account/category key order. For every category whose
 * disclosure interest rate is non-zero this processor computes the monthly
 * interest, emits a system-generated interest {@link Transaction} for the writer
 * to persist, and accumulates a per-account running total that is applied to the
 * account balance when the account changes. The mapping of COBOL paragraphs to
 * the methods of this class is:</p>
 *
 * <ul>
 *   <li>{@code 1000-TCATBALF-GET-NEXT} sequential read &rarr; one
 *       {@link #process(TransactionCategoryBalance)} invocation per item.</li>
 *   <li>{@code 1050-UPDATE-ACCOUNT} balance rewrite &rarr;
 *       {@link #flushCurrentAccount()} (account balance increased by the running
 *       interest total; current-cycle credit and debit reset to zero).</li>
 *   <li>{@code 1100-GET-ACCT-DATA} keyed read &rarr; {@link #loadAccount(Long)}.</li>
 *   <li>{@code 1110-GET-XREF-DATA} alternate-key read &rarr;
 *       {@link #loadCardNumber(Long)} (resolves the interest transaction's card
 *       number).</li>
 *   <li>{@code 1200-GET-INTEREST-RATE} / {@code 1200-A-GET-DEFAULT-INT-RATE}
 *       &rarr; {@link #resolveInterestRate(TransactionCategoryBalance)} with the
 *       {@code DEFAULT} account-group fallback.</li>
 *   <li>{@code 1300-COMPUTE-INTEREST} &rarr;
 *       {@link #computeMonthlyInterest(BigDecimal, BigDecimal)}.</li>
 *   <li>{@code 1300-B-WRITE-TX} &rarr;
 *       {@link #buildInterestTransaction(Long, BigDecimal)}.</li>
 *   <li>{@code 1400-COMPUTE-FEES} is an empty placeholder in the COBOL source and
 *       is intentionally not implemented.</li>
 * </ul>
 *
 * <p><strong>Decimal exactness.</strong> The monthly interest reproduces the COBOL
 * {@code COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200} without
 * algebraic rearrangement: the balance is multiplied by the rate first and then
 * divided by the {@link #INTEREST_DIVISOR} literal {@code 1200}, scaled to two
 * decimal places using {@link RoundingMode#HALF_EVEN}. All monetary values are
 * {@link BigDecimal}; {@code double} and {@code float} are never used.</p>
 *
 * <p><strong>Control-break state.</strong> Because Spring Batch invokes the
 * processor one item at a time, the per-account running total and the
 * "current account" tracking are held as mutable instance fields. The bean is
 * therefore {@link StepScope step-scoped}, giving each step execution its own
 * isolated state. The final account group is flushed from the {@link AfterStep}
 * callback, since the iteration ends without another account change to trigger
 * the last rewrite.</p>
 *
 * <p><strong>Atomicity.</strong> Each account flush (balance rewrite plus the
 * cycle resets) is executed inside a programmatic transaction via
 * {@link TransactionTemplate}, reproducing the CICS {@code SYNCPOINT} /
 * implicit batch-commit unit of work and rolling back on any runtime failure.</p>
 */
@Component
@StepScope
public class InterestProcessor implements ItemProcessor<TransactionCategoryBalance, Transaction> {

    /**
     * Fixed divisor for the monthly interest formula
     * {@code (TRAN-CAT-BAL * DIS-INT-RATE) / 1200}. Declared here, never in
     * external configuration, so the arithmetic constant travels with the
     * calculation it governs.
     */
    private static final BigDecimal INTEREST_DIVISOR = new BigDecimal("1200");

    /** Scale (two decimal places) applied to every monetary result. */
    private static final int MONETARY_SCALE = 2;

    /** Zero amount carried at monetary scale for balance and cycle resets. */
    private static final BigDecimal ZERO_AMOUNT = new BigDecimal("0.00");

    /**
     * Account-group identifier used for the {@code 1200-A} fallback read when no
     * disclosure-group row exists for the account's own group (COBOL
     * {@code MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID}).
     */
    private static final String DEFAULT_GROUP_ID = "DEFAULT";

    /** Literal description prefix for a generated interest transaction. */
    private static final String INTEREST_DESCRIPTION_PREFIX = "Int. for a/c ";

    /** Transaction type code assigned to interest transactions (COBOL {@code '01'}). */
    private static final String INTEREST_TRANSACTION_TYPE_CODE = "01";

    /** Transaction category code assigned to interest transactions (COBOL {@code '05'}). */
    private static final Integer INTEREST_TRANSACTION_CATEGORY_CODE = 5;

    /** Merchant identifier left at zero for system-generated interest transactions. */
    private static final Long NO_MERCHANT_ID = 0L;

    /** Blank value for the merchant name, city, and ZIP fields (COBOL {@code MOVE SPACES}). */
    private static final String BLANK = "";

    /** Width of the zero-padded transaction-id suffix (COBOL {@code WS-TRANID-SUFFIX PIC 9(06)}). */
    private static final String TRAN_ID_SUFFIX_FORMAT = "%06d";

    /** Width of the zero-padded account id embedded in the description (COBOL {@code ACCT-ID PIC 9(11)}). */
    private static final String ACCOUNT_ID_FORMAT = "%011d";

    private final DisclosureGroupRepository disclosureGroupRepository;
    private final AccountRepository accountRepository;
    private final CardXrefRepository cardXrefRepository;
    private final DateValidationService dateValidationService;
    private final Counter recordsProcessedCounter;
    private final TransactionTemplate transactionTemplate;

    /**
     * The raw, ten-character run date supplied as the batch job parameter,
     * modelling the JCL {@code PARM='2022071800'} ({@code PARM-DATE PIC X(10)}).
     * It is used verbatim as the leading portion of each generated transaction
     * identifier and is deliberately not parsed into a date.
     */
    private final String parmDate;

    /** Account id of the group currently being accumulated; {@code null} until the first item. */
    private Long currentAccountId;

    /** {@code true} until the first item is processed (COBOL {@code WS-FIRST-TIME = 'Y'}). */
    private boolean firstRecord = true;

    /** Running interest total for the current account (COBOL {@code WS-TOTAL-INT}). */
    private BigDecimal runningInterestTotal = ZERO_AMOUNT;

    /** Account entity for the group currently being accumulated. */
    private Account currentAccount;

    /** Card number resolved for the current account (COBOL {@code XREF-CARD-NUM}). */
    private String currentCardNumber;

    /** Monotonic transaction-id suffix counter (COBOL {@code WS-TRANID-SUFFIX}, starts at zero). */
    private int tranIdSuffix;

    /**
     * Creates the interest processor with all collaborators injected.
     *
     * @param disclosureGroupRepository repository resolving disclosure interest
     *                                  rates by composite group key
     * @param accountRepository         repository for reading and rewriting the
     *                                  account master
     * @param cardXrefRepository        repository resolving the card number for
     *                                  an account through the cross-reference
     *                                  alternate index
     * @param dateValidationService     service supplying the 26-character
     *                                  origination/processing timestamp
     * @param recordsProcessedCounter   Micrometer counter incremented once per
     *                                  produced interest transaction
     * @param transactionManager        platform transaction manager backing the
     *                                  programmatic account-flush transaction
     * @param parmDate                  the ten-character run date job parameter
     *                                  ({@code PARM-DATE}); bound late from the
     *                                  step's job parameters
     */
    public InterestProcessor(
            DisclosureGroupRepository disclosureGroupRepository,
            AccountRepository accountRepository,
            CardXrefRepository cardXrefRepository,
            DateValidationService dateValidationService,
            @Qualifier("batchRecordsProcessedCounter") Counter recordsProcessedCounter,
            PlatformTransactionManager transactionManager,
            @Value("#{jobParameters['parmDate']}") String parmDate) {
        this.disclosureGroupRepository = disclosureGroupRepository;
        this.accountRepository = accountRepository;
        this.cardXrefRepository = cardXrefRepository;
        this.dateValidationService = dateValidationService;
        this.recordsProcessedCounter = recordsProcessedCounter;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.parmDate = parmDate;
    }

    /**
     * Processes one transaction-category balance, returning the interest
     * {@link Transaction} to persist, or {@code null} when no interest is due
     * (zero or unknown disclosure rate) so the writer skips the item.
     *
     * <p>When the account identifier changes from the previous item, the prior
     * account's accumulated interest is flushed to its balance and a new running
     * total is started for the new account, whose master and card-number records
     * are loaded.</p>
     *
     * @param item the transaction-category balance to process
     * @return the generated interest transaction, or {@code null} to skip
     */
    @Override
    public Transaction process(TransactionCategoryBalance item) {
        Long accountId = item.getId().getAcctId();
        if (!Objects.equals(accountId, currentAccountId)) {
            if (firstRecord) {
                firstRecord = false;
            } else {
                flushCurrentAccount();
            }
            runningInterestTotal = ZERO_AMOUNT;
            currentAccountId = accountId;
            currentAccount = loadAccount(accountId);
            currentCardNumber = loadCardNumber(accountId);
        }

        BigDecimal disclosureRate = resolveInterestRate(item);
        if (disclosureRate == null || disclosureRate.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }

        BigDecimal monthlyInterest = computeMonthlyInterest(item.getTranCatBal(), disclosureRate);
        runningInterestTotal = runningInterestTotal.add(monthlyInterest);

        Transaction interestTransaction = buildInterestTransaction(accountId, monthlyInterest);
        recordsProcessedCounter.increment();
        return interestTransaction;
    }

    /**
     * Flushes the final account group at end of step, applying the last running
     * interest total to its balance. Spring Batch invokes this once the chunk
     * iteration has completed.
     *
     * @param stepExecution the completing step execution
     * @return the step's existing exit status, left unchanged
     */
    @AfterStep
    public ExitStatus afterStep(StepExecution stepExecution) {
        if (!firstRecord && currentAccount != null) {
            flushCurrentAccount();
        }
        return stepExecution.getExitStatus();
    }

    /**
     * Resolves the disclosure interest rate for the supplied category, falling
     * back to the {@code DEFAULT} account group when the account's own group has
     * no matching disclosure row (COBOL file status {@code 23}).
     *
     * @param item the transaction-category balance whose type and category codes
     *             complete the disclosure key
     * @return the disclosure interest rate, or {@code null} when neither the
     *         account-group nor the {@code DEFAULT}-group row exists
     */
    private BigDecimal resolveInterestRate(TransactionCategoryBalance item) {
        String tranTypeCode = item.getId().getTypeCd();
        Integer tranCategoryCode = item.getId().getCatCd();

        DisclosureGroupId groupKey =
                new DisclosureGroupId(currentAccount.getGroupId(), tranTypeCode, tranCategoryCode);
        Optional<DisclosureGroup> disclosureGroup = disclosureGroupRepository.findById(groupKey);

        if (disclosureGroup.isEmpty()) {
            DisclosureGroupId defaultKey =
                    new DisclosureGroupId(DEFAULT_GROUP_ID, tranTypeCode, tranCategoryCode);
            disclosureGroup = disclosureGroupRepository.findById(defaultKey);
        }

        return disclosureGroup.map(DisclosureGroup::getDisIntRate).orElse(null);
    }

    /**
     * Computes the monthly interest as {@code (balance * rate) / 1200}, scaled to
     * two decimal places with banker's rounding, preserving the COBOL operand
     * order and divisor literal exactly.
     *
     * @param tranCategoryBalance the category balance ({@code TRAN-CAT-BAL})
     * @param disclosureRate      the disclosure interest rate ({@code DIS-INT-RATE})
     * @return the monthly interest amount with scale two
     */
    private BigDecimal computeMonthlyInterest(BigDecimal tranCategoryBalance, BigDecimal disclosureRate) {
        return tranCategoryBalance
                .multiply(disclosureRate)
                .divide(INTEREST_DIVISOR, MONETARY_SCALE, RoundingMode.HALF_EVEN);
    }

    /**
     * Builds a system-generated interest transaction for the current account
     * (COBOL {@code 1300-B-WRITE-TX}). The transaction identifier concatenates the
     * raw ten-character run date with a six-digit, zero-padded, monotonically
     * incrementing suffix; the description embeds the eleven-digit, zero-padded
     * account id; and both timestamps share a single freshly generated value.
     *
     * @param accountId       the account the interest applies to
     * @param monthlyInterest the computed interest amount
     * @return the populated interest transaction
     */
    private Transaction buildInterestTransaction(Long accountId, BigDecimal monthlyInterest) {
        tranIdSuffix++;
        String transactionId = parmDate + String.format(TRAN_ID_SUFFIX_FORMAT, tranIdSuffix);
        String description = INTEREST_DESCRIPTION_PREFIX + String.format(ACCOUNT_ID_FORMAT, accountId);
        String timestamp = dateValidationService.currentTimestamp();

        return new Transaction(
                transactionId,
                TransactionTypeCode.fromCode(INTEREST_TRANSACTION_TYPE_CODE),
                INTEREST_TRANSACTION_CATEGORY_CODE,
                TransactionSource.SYSTEM.getLabel(),
                description,
                monthlyInterest,
                NO_MERCHANT_ID,
                BLANK,
                BLANK,
                BLANK,
                currentCardNumber,
                timestamp,
                timestamp);
    }

    /**
     * Applies the running interest total to the current account's balance and
     * resets the current-cycle credit and debit buckets to zero, persisting the
     * rewrite inside a programmatic transaction (COBOL {@code 1050-UPDATE-ACCOUNT}).
     */
    private void flushCurrentAccount() {
        transactionTemplate.executeWithoutResult(status -> {
            BigDecimal currentBalance =
                    currentAccount.getCurrBal() != null ? currentAccount.getCurrBal() : ZERO_AMOUNT;
            currentAccount.setCurrBal(currentBalance.add(runningInterestTotal));
            currentAccount.setCurrCycCredit(ZERO_AMOUNT);
            currentAccount.setCurrCycDebit(ZERO_AMOUNT);
            accountRepository.save(currentAccount);
        });
    }

    /**
     * Reads the account master record for the supplied identifier
     * (COBOL {@code 1100-GET-ACCT-DATA}).
     *
     * @param accountId the account identifier
     * @return the account entity
     * @throws IllegalStateException when no account exists for the identifier,
     *                               reproducing the legacy abend on a failed read
     */
    private Account loadAccount(Long accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalStateException(
                        "Account not found for interest calculation: " + accountId));
    }

    /**
     * Resolves the card number for the supplied account through the
     * cross-reference alternate index (COBOL {@code 1110-GET-XREF-DATA}). The
     * alternate index is non-unique, so the first matching cross-reference is
     * used, mirroring the single keyed read of the COBOL program.
     *
     * @param accountId the account identifier
     * @return the card number associated with the account
     * @throws IllegalStateException when no cross-reference exists for the
     *                               account, reproducing the legacy abend on a
     *                               failed read
     */
    private String loadCardNumber(Long accountId) {
        List<CardXref> crossReferences = cardXrefRepository.findByXrefAcctId(accountId);
        if (crossReferences.isEmpty()) {
            throw new IllegalStateException(
                    "Card cross-reference not found for account: " + accountId);
        }
        return crossReferences.get(0).getXrefCardNum();
    }
}

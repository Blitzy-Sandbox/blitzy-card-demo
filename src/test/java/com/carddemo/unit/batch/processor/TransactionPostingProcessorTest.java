/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carddemo.unit.batch.processor;

import java.math.BigDecimal;
import java.util.Optional;

import com.carddemo.batch.processor.PostingResult;
import com.carddemo.batch.processor.TransactionPostingProcessor;
import com.carddemo.entity.Account;
import com.carddemo.entity.CardXref;
import com.carddemo.entity.DailyTransaction;
import com.carddemo.entity.Transaction;
import com.carddemo.entity.TransactionCategoryBalance;
import com.carddemo.entity.TransactionCategoryBalanceId;
import com.carddemo.enums.RejectReasonCode;
import com.carddemo.enums.TransactionTypeCode;
import com.carddemo.repository.AccountRepository;
import com.carddemo.repository.CardXrefRepository;
import com.carddemo.repository.TransactionCategoryBalanceRepository;
import com.carddemo.service.DateValidationService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Behavioral-parity unit tests for {@link TransactionPostingProcessor}, the Java
 * realization of the COBOL daily-posting engine {@code CBTRN02C} (the posting
 * step of the {@code POSTTRAN} job) at source commit {@code 27d6c6f}. This is
 * the highest parity-risk processor in the batch suite (Validation Gates 1 and
 * 4), so the suite pins every observable decision of the engine.
 *
 * <p>This is a <strong>pure in-memory</strong> test: the system under test is
 * driven directly through {@link TransactionPostingProcessor#process(DailyTransaction)}
 * with every collaborator mocked. There is no Spring context, no
 * {@code JobLauncherTestUtils}, no Testcontainers, and no real PostgreSQL or
 * AWS/LocalStack — the end-to-end job behavior (including the 430-byte
 * {@code DALYREJS} reject object and the {@code RC=4} return code) is owned by
 * the sibling {@code PostTransactionJobIT} integration test.</p>
 *
 * <p>The assertions are written against the <em>compiled</em> system under test
 * ("compiled source wins"). The compiled processor:</p>
 * <ul>
 *   <li>is an {@code ItemProcessor<DailyTransaction, PostingResult>}: every
 *       record yields a {@link PostingResult} and never {@code null}, so a
 *       rejected record is never silently dropped;</li>
 *   <li>collaborates with exactly four beans — {@link CardXrefRepository},
 *       {@link AccountRepository}, {@link TransactionCategoryBalanceRepository}
 *       and {@link DateValidationService} — and <em>owns no Micrometer
 *       metrics</em> (record-count meters belong to the durable writers), so no
 *       counter is mocked or asserted here;</li>
 *   <li>signals rejects by returning {@link PostingResult.Rejected} carrying the
 *       reconstructed 350-byte {@code CVTRA06Y} record image plus the
 *       {@link RejectReasonCode}.</li>
 * </ul>
 *
 * <p>The four-stage validation cascade is asserted in exact ordered
 * short-circuit form, mirroring {@code 1500-VALIDATE-TRAN} (CBTRN02C
 * lines&nbsp;370-419): card cross-reference&nbsp;(100), account&nbsp;(101),
 * credit limit&nbsp;(102) and expiration&nbsp;(103). The credit-limit and
 * expiration checks are two sequential, independent {@code IF} blocks with no
 * {@code else} between them, so a record that fails both carries
 * {@link RejectReasonCode#ACCOUNT_EXPIRED} (103) — the later assignment wins
 * over {@link RejectReasonCode#OVER_CREDIT_LIMIT} (102). All monetary values are
 * {@link BigDecimal} at scale&nbsp;2 and are compared with
 * {@link BigDecimal#compareTo} (via AssertJ {@code isEqualByComparingTo}), never
 * {@code equals}.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionPostingProcessor — CBTRN02C posting cascade & reject routing")
class TransactionPostingProcessorTest {

    // --- Fixture shapes, modelled on app/data/ASCII/dailytran.txt record 1 ---

    /** {@code DALYTRAN-ID PIC X(16)}. */
    private static final String TRAN_ID = "0000000000683580";

    /** {@code DALYTRAN-TYPE-CD PIC X(02)} — resolves to {@link TransactionTypeCode#PURCHASE}. */
    private static final String TYPE_CD = "01";

    /** {@code DALYTRAN-CAT-CD PIC 9(04)}. */
    private static final Integer CAT_CD = 1;

    /** {@code DALYTRAN-SOURCE PIC X(10)}. */
    private static final String SOURCE = "POS TERM";

    /** {@code DALYTRAN-DESC PIC X(100)}. */
    private static final String DESC = "Purchase at Abshire-Lowe";

    /** {@code DALYTRAN-MERCHANT-ID PIC 9(09)}. */
    private static final Long MERCHANT_ID = 800000000L;

    /** {@code DALYTRAN-MERCHANT-NAME PIC X(50)}. */
    private static final String MERCHANT_NAME = "Abshire-Lowe";

    /** {@code DALYTRAN-MERCHANT-CITY PIC X(50)}. */
    private static final String MERCHANT_CITY = "North Enoshaven";

    /** {@code DALYTRAN-MERCHANT-ZIP PIC X(10)}. */
    private static final String MERCHANT_ZIP = "72112";

    /** {@code DALYTRAN-CARD-NUM PIC X(16)} — the card cross-reference lookup key. */
    private static final String CARD_NUM = "4859452612877065";

    /** {@code XREF-ACCT-ID PIC 9(11)} — the owning account resolved from the card. */
    private static final Long ACCT_ID = 10000000001L;

    /** {@code DALYTRAN-ORIG-TS PIC X(26)} — origination timestamp (26 characters). */
    private static final String ORIG_TS = "2022-06-10 19:27:53.000000";

    /** Fixed processing timestamp produced by the stubbed clock (26 characters). */
    private static final String PROC_TS = "2022-07-18 12:00:00.000000";

    /** A positive amount (credit bucket); scale 2, mirroring {@code PIC S9(09)V99}. */
    private static final BigDecimal AMOUNT = new BigDecimal("50.47");

    /** A negative amount (debit bucket) — the negative "Return" case from the daily feed. */
    private static final BigDecimal RETURN_AMOUNT = new BigDecimal("-25.00");

    /** Zero money at the canonical scale of 2. */
    private static final BigDecimal ZERO_MONEY = new BigDecimal("0.00");

    /** {@code CVTRA06Y DALYTRAN-RECORD} length reconstructed into a reject result. */
    private static final int DALYTRAN_RECORD_LENGTH = 350;

    /** {@code TRAN-PROC-TS PIC X(26)} width. */
    private static final int TIMESTAMP_LENGTH = 26;

    @Mock
    private CardXrefRepository cardXrefRepository;
    @Mock
    private AccountRepository accountRepository;
    @Mock
    private TransactionCategoryBalanceRepository transactionCategoryBalanceRepository;
    @Mock
    private DateValidationService dateValidationService;

    private TransactionPostingProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new TransactionPostingProcessor(
                cardXrefRepository,
                accountRepository,
                transactionCategoryBalanceRepository,
                dateValidationService);
        // Shared across every test; rejection paths never reach the posting step,
        // so the stub is lenient to avoid strict-stubbing "unnecessary stubbing".
        lenient().when(dateValidationService.currentTimestamp()).thenReturn(PROC_TS);
    }

    /**
     * Builds the reusable happy-path staging record, varying only the amount so
     * each test can drive the credit/debit bucket and credit-limit logic.
     *
     * @param amount the transaction amount
     * @return a fully populated {@link DailyTransaction}
     */
    private DailyTransaction dailyTransaction(BigDecimal amount) {
        return new DailyTransaction(
                TRAN_ID, TYPE_CD, CAT_CD, SOURCE, DESC, amount, MERCHANT_ID,
                MERCHANT_NAME, MERCHANT_CITY, MERCHANT_ZIP, CARD_NUM, ORIG_TS, "");
    }

    /**
     * Builds the card cross-reference that maps {@link #CARD_NUM} to
     * {@link #ACCT_ID}.
     *
     * @return the matching {@link CardXref}
     */
    private CardXref cardXref() {
        return new CardXref(CARD_NUM, 1L, ACCT_ID);
    }

    /**
     * Builds an account with zeroed cycle/balance fields, varying only the two
     * inputs to the credit-limit and expiration checks.
     *
     * @param creditLimit    the credit limit
     * @param expirationDate the account expiration date ({@code YYYY-MM-DD})
     * @return the matching {@link Account}
     */
    private Account account(BigDecimal creditLimit, String expirationDate) {
        return accountWithBalances(creditLimit, expirationDate, ZERO_MONEY, ZERO_MONEY, ZERO_MONEY);
    }

    /**
     * Builds an account with explicit balance and cycle totals for the posting
     * (account-update) assertions.
     *
     * @param creditLimit    the credit limit
     * @param expirationDate the account expiration date ({@code YYYY-MM-DD})
     * @param currBal        the current balance
     * @param currCycCredit  the current-cycle credit total
     * @param currCycDebit   the current-cycle debit total
     * @return the matching {@link Account}
     */
    private Account accountWithBalances(BigDecimal creditLimit, String expirationDate,
            BigDecimal currBal, BigDecimal currCycCredit, BigDecimal currCycDebit) {
        return new Account(
                ACCT_ID, "Y", currBal, creditLimit,
                null, null, expirationDate, null,
                currCycCredit, currCycDebit, null, "DEFAULT", null);
    }

    @Nested
    @DisplayName("Ordered reject cascade (1500-VALIDATE-TRAN) with verbatim reason codes")
    class RejectCascade {

        @Test
        @DisplayName("Stage 1: missing card cross-reference rejects with 100 CARD_NOT_FOUND and short-circuits")
        void cardNotFoundRejectsWith100() {
            DailyTransaction item = dailyTransaction(AMOUNT);
            when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.empty());

            PostingResult result = processor.process(item);

            assertThat(result).isInstanceOf(PostingResult.Rejected.class);
            PostingResult.Rejected rejected = (PostingResult.Rejected) result;
            assertThat(rejected.reason()).isEqualTo(RejectReasonCode.CARD_NOT_FOUND);
            assertThat(rejected.reason().getCode()).isEqualTo(100);
            assertThat(rejected.reason().getDescription()).isEqualTo("INVALID CARD NUMBER FOUND");
            // The reject carries the byte-exact 350-byte CVTRA06Y image (never dropped).
            assertThat(rejected.originalRecordImage())
                    .hasSize(DALYTRAN_RECORD_LENGTH)
                    .startsWith(TRAN_ID);
            // Stages 2-4 do not run: account and category-balance lookups never happen.
            verifyNoInteractions(accountRepository, transactionCategoryBalanceRepository);
        }

        @Test
        @DisplayName("Stage 2: missing account rejects with 101 ACCOUNT_NOT_FOUND and short-circuits")
        void accountNotFoundRejectsWith101() {
            DailyTransaction item = dailyTransaction(AMOUNT);
            when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(cardXref()));
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.empty());

            PostingResult result = processor.process(item);

            assertThat(result).isInstanceOf(PostingResult.Rejected.class);
            PostingResult.Rejected rejected = (PostingResult.Rejected) result;
            assertThat(rejected.reason()).isEqualTo(RejectReasonCode.ACCOUNT_NOT_FOUND);
            assertThat(rejected.reason().getCode()).isEqualTo(101);
            assertThat(rejected.reason().getDescription()).isEqualTo("ACCOUNT RECORD NOT FOUND");
            // No posting side effects when validation fails before the post step.
            verify(accountRepository, never()).save(any());
            verifyNoInteractions(transactionCategoryBalanceRepository);
        }

        @Test
        @DisplayName("Stage 3: projected balance over the credit limit rejects with 102 OVER_CREDIT_LIMIT")
        void overCreditLimitRejectsWith102() {
            DailyTransaction item = dailyTransaction(AMOUNT);
            when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(cardXref()));
            // Limit (10.00) below the projected balance (0 - 0 + 50.47) and a future
            // expiry: only the over-limit condition trips.
            when(accountRepository.findById(ACCT_ID))
                    .thenReturn(Optional.of(account(new BigDecimal("10.00"), "2099-12-31")));

            PostingResult result = processor.process(item);

            assertThat(result).isInstanceOf(PostingResult.Rejected.class);
            PostingResult.Rejected rejected = (PostingResult.Rejected) result;
            assertThat(rejected.reason()).isEqualTo(RejectReasonCode.OVER_CREDIT_LIMIT);
            assertThat(rejected.reason().getCode()).isEqualTo(102);
            assertThat(rejected.reason().getDescription()).isEqualTo("OVERLIMIT TRANSACTION");
            verify(accountRepository, never()).save(any());
            verifyNoInteractions(transactionCategoryBalanceRepository);
        }

        @Test
        @DisplayName("Stage 4: expired account rejects with 103 ACCOUNT_EXPIRED")
        void accountExpiredRejectsWith103() {
            DailyTransaction item = dailyTransaction(AMOUNT);
            when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(cardXref()));
            // Ample limit (credit check passes) but a past expiry: only the
            // expiration condition trips.
            when(accountRepository.findById(ACCT_ID))
                    .thenReturn(Optional.of(account(new BigDecimal("100000.00"), "2020-01-01")));

            PostingResult result = processor.process(item);

            assertThat(result).isInstanceOf(PostingResult.Rejected.class);
            PostingResult.Rejected rejected = (PostingResult.Rejected) result;
            assertThat(rejected.reason()).isEqualTo(RejectReasonCode.ACCOUNT_EXPIRED);
            assertThat(rejected.reason().getCode()).isEqualTo(103);
            assertThat(rejected.reason().getDescription())
                    .isEqualTo("TRANSACTION RECEIVED AFTER ACCT EXPIRATION");
            verify(accountRepository, never()).save(any());
            verifyNoInteractions(transactionCategoryBalanceRepository);
        }
    }

    @Nested
    @DisplayName("Last-wins (103 over 102) and post-update account loss (109)")
    class LastWinsAndUpdateFailure {

        @Test
        @DisplayName("Over-limit AND expired rejects with 103 — expiration overwrites the over-limit code")
        void overLimitAndExpiredYields103LastWins() {
            DailyTransaction item = dailyTransaction(AMOUNT);
            when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(cardXref()));
            // BOTH conditions fail: small limit (over) AND past expiry (expired).
            // The credit-limit check sets 102, then the expiration check (a separate,
            // unconditional IF with no else) overwrites it with 103.
            when(accountRepository.findById(ACCT_ID))
                    .thenReturn(Optional.of(account(new BigDecimal("10.00"), "2020-01-01")));

            PostingResult result = processor.process(item);

            assertThat(result).isInstanceOf(PostingResult.Rejected.class);
            PostingResult.Rejected rejected = (PostingResult.Rejected) result;
            assertThat(rejected.reason()).isEqualTo(RejectReasonCode.ACCOUNT_EXPIRED);
            assertThat(rejected.reason().getCode()).isEqualTo(103);
            // Prove 103 won: the result is NOT the over-credit-limit code 102.
            assertThat(rejected.reason()).isNotEqualTo(RejectReasonCode.OVER_CREDIT_LIMIT);
            assertThat(rejected.reason().getCode())
                    .isNotEqualTo(RejectReasonCode.OVER_CREDIT_LIMIT.getCode());
            verify(accountRepository, never()).save(any());
            verifyNoInteractions(transactionCategoryBalanceRepository);
        }

        @Test
        @DisplayName("Account absent at the update re-read rejects with 109 ACCOUNT_NOT_FOUND_ON_UPDATE")
        void accountMissingOnUpdateYields109() {
            DailyTransaction item = dailyTransaction(AMOUNT);
            when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(cardXref()));
            // Present during validation (passes all four stages), absent during the
            // 2800-UPDATE-ACCOUNT-REC re-read.
            when(accountRepository.findById(ACCT_ID))
                    .thenReturn(Optional.of(account(new BigDecimal("100000.00"), "2099-12-31")))
                    .thenReturn(Optional.empty());
            when(transactionCategoryBalanceRepository.findById(any(TransactionCategoryBalanceId.class)))
                    .thenReturn(Optional.empty());

            PostingResult result = processor.process(item);

            assertThat(result).isInstanceOf(PostingResult.Rejected.class);
            PostingResult.Rejected rejected = (PostingResult.Rejected) result;
            assertThat(rejected.reason()).isEqualTo(RejectReasonCode.ACCOUNT_NOT_FOUND_ON_UPDATE);
            assertThat(rejected.reason().getCode()).isEqualTo(109);
            assertThat(rejected.reason().getDescription()).isEqualTo("ACCOUNT RECORD NOT FOUND");
            // 109 is a DISTINCT code from 101 even though both share the same text.
            assertThat(RejectReasonCode.ACCOUNT_NOT_FOUND_ON_UPDATE.getCode())
                    .isNotEqualTo(RejectReasonCode.ACCOUNT_NOT_FOUND.getCode());
            assertThat(RejectReasonCode.ACCOUNT_NOT_FOUND_ON_UPDATE.getDescription())
                    .isEqualTo(RejectReasonCode.ACCOUNT_NOT_FOUND.getDescription());
        }
    }

    @Nested
    @DisplayName("Successful posting (2000-POST-TRANSACTION): mapping, TCATBAL upsert, account update")
    class SuccessfulPosting {

        @Test
        @DisplayName("Valid record maps every field onto the posted transaction and stamps a 26-char proc-ts")
        void validRecordMapsAllFields() {
            DailyTransaction item = dailyTransaction(AMOUNT);
            when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(cardXref()));
            when(accountRepository.findById(ACCT_ID))
                    .thenReturn(Optional.of(account(new BigDecimal("100000.00"), "2099-12-31")));
            when(transactionCategoryBalanceRepository.findById(any(TransactionCategoryBalanceId.class)))
                    .thenReturn(Optional.empty());

            PostingResult result = processor.process(item);

            assertThat(result).isInstanceOf(PostingResult.Posted.class);
            Transaction posted = ((PostingResult.Posted) result).transaction();
            assertThat(posted.getTranId()).isEqualTo(TRAN_ID);
            assertThat(posted.getTranTypeCd())
                    .isEqualTo(TransactionTypeCode.PURCHASE.getCode())
                    .isEqualTo(TYPE_CD);
            assertThat(posted.getTranCatCd()).isEqualTo(CAT_CD);
            assertThat(posted.getTranSource()).isEqualTo(SOURCE);
            assertThat(posted.getTranDesc()).isEqualTo(DESC);
            assertThat(posted.getTranAmt()).isEqualByComparingTo(AMOUNT);
            assertThat(posted.getMerchantId()).isEqualTo(MERCHANT_ID);
            assertThat(posted.getMerchantName()).isEqualTo(MERCHANT_NAME);
            assertThat(posted.getMerchantCity()).isEqualTo(MERCHANT_CITY);
            assertThat(posted.getMerchantZip()).isEqualTo(MERCHANT_ZIP);
            assertThat(posted.getCardNum()).isEqualTo(CARD_NUM);
            assertThat(posted.getOrigTs()).isEqualTo(ORIG_TS);
            // proc-ts comes from the injected clock and is exactly 26 characters.
            assertThat(posted.getProcTs()).isEqualTo(PROC_TS);
            assertThat(posted.getProcTs()).hasSize(TIMESTAMP_LENGTH);
        }

        @Test
        @DisplayName("TCATBAL upsert CREATE branch: a new balance starts from the transaction amount")
        void categoryBalanceCreatedFromAmount() {
            DailyTransaction item = dailyTransaction(AMOUNT);
            TransactionCategoryBalanceId expectedId =
                    new TransactionCategoryBalanceId(ACCT_ID, TYPE_CD, CAT_CD);
            when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(cardXref()));
            when(accountRepository.findById(ACCT_ID))
                    .thenReturn(Optional.of(account(new BigDecimal("100000.00"), "2099-12-31")));
            when(transactionCategoryBalanceRepository.findById(expectedId))
                    .thenReturn(Optional.empty());

            processor.process(item);

            ArgumentCaptor<TransactionCategoryBalance> balanceCaptor =
                    ArgumentCaptor.forClass(TransactionCategoryBalance.class);
            verify(transactionCategoryBalanceRepository).save(balanceCaptor.capture());
            TransactionCategoryBalance saved = balanceCaptor.getValue();
            assertThat(saved.getId()).isEqualTo(expectedId);
            assertThat(saved.getTranCatBal()).isEqualByComparingTo(AMOUNT);
            assertThat(saved.getTranCatBal().scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("TCATBAL upsert UPDATE branch: the amount is added to the running balance")
        void categoryBalanceAccumulatesAmount() {
            DailyTransaction item = dailyTransaction(AMOUNT);
            TransactionCategoryBalanceId expectedId =
                    new TransactionCategoryBalanceId(ACCT_ID, TYPE_CD, CAT_CD);
            TransactionCategoryBalance existing =
                    new TransactionCategoryBalance(expectedId, new BigDecimal("100.00"));
            when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(cardXref()));
            when(accountRepository.findById(ACCT_ID))
                    .thenReturn(Optional.of(account(new BigDecimal("100000.00"), "2099-12-31")));
            when(transactionCategoryBalanceRepository.findById(expectedId))
                    .thenReturn(Optional.of(existing));

            processor.process(item);

            ArgumentCaptor<TransactionCategoryBalance> balanceCaptor =
                    ArgumentCaptor.forClass(TransactionCategoryBalance.class);
            verify(transactionCategoryBalanceRepository).save(balanceCaptor.capture());
            // 100.00 (existing) + 50.47 (amount) = 150.47, at scale 2.
            assertThat(balanceCaptor.getValue().getTranCatBal())
                    .isEqualByComparingTo(new BigDecimal("150.47"));
            assertThat(balanceCaptor.getValue().getTranCatBal().scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("Account update — credit bucket: a non-negative amount adds to balance and cycle-credit")
        void accountUpdateAddsToCreditBucketWhenAmountNonNegative() {
            DailyTransaction item = dailyTransaction(AMOUNT);
            when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(cardXref()));
            when(accountRepository.findById(ACCT_ID))
                    .thenReturn(Optional.of(accountWithBalances(
                            new BigDecimal("100000.00"), "2099-12-31",
                            new BigDecimal("750.00"), new BigDecimal("1000.00"),
                            new BigDecimal("200.00"))));
            when(transactionCategoryBalanceRepository.findById(any(TransactionCategoryBalanceId.class)))
                    .thenReturn(Optional.empty());

            processor.process(item);

            ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
            verify(accountRepository).save(accountCaptor.capture());
            Account saved = accountCaptor.getValue();
            assertThat(saved.getCurrBal()).isEqualByComparingTo(new BigDecimal("800.47"));
            assertThat(saved.getCurrBal().scale()).isEqualTo(2);
            assertThat(saved.getCurrCycCredit()).isEqualByComparingTo(new BigDecimal("1050.47"));
            assertThat(saved.getCurrCycCredit().scale()).isEqualTo(2);
            // The debit bucket is untouched for a non-negative amount.
            assertThat(saved.getCurrCycDebit()).isEqualByComparingTo(new BigDecimal("200.00"));
        }

        @Test
        @DisplayName("Account update — debit bucket: a negative amount adds to balance and cycle-debit")
        void accountUpdateAddsToDebitBucketWhenAmountNegative() {
            DailyTransaction item = dailyTransaction(RETURN_AMOUNT);
            when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(cardXref()));
            when(accountRepository.findById(ACCT_ID))
                    .thenReturn(Optional.of(accountWithBalances(
                            new BigDecimal("100000.00"), "2099-12-31",
                            new BigDecimal("750.00"), new BigDecimal("1000.00"),
                            new BigDecimal("200.00"))));
            when(transactionCategoryBalanceRepository.findById(any(TransactionCategoryBalanceId.class)))
                    .thenReturn(Optional.empty());

            processor.process(item);

            ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
            verify(accountRepository).save(accountCaptor.capture());
            Account saved = accountCaptor.getValue();
            assertThat(saved.getCurrBal()).isEqualByComparingTo(new BigDecimal("725.00"));
            assertThat(saved.getCurrBal().scale()).isEqualTo(2);
            assertThat(saved.getCurrCycDebit()).isEqualByComparingTo(new BigDecimal("175.00"));
            assertThat(saved.getCurrCycDebit().scale()).isEqualTo(2);
            // The credit bucket is untouched for a negative amount.
            assertThat(saved.getCurrCycCredit()).isEqualByComparingTo(new BigDecimal("1000.00"));
        }

        @Test
        @DisplayName("A valid record posts once: exactly one balance save and one account save, no reject")
        void validRecordPersistsBalanceAndAccountExactlyOnce() {
            DailyTransaction item = dailyTransaction(AMOUNT);
            when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(cardXref()));
            when(accountRepository.findById(ACCT_ID))
                    .thenReturn(Optional.of(account(new BigDecimal("100000.00"), "2099-12-31")));
            when(transactionCategoryBalanceRepository.findById(any(TransactionCategoryBalanceId.class)))
                    .thenReturn(Optional.empty());

            PostingResult result = processor.process(item);

            assertThat(result).isInstanceOf(PostingResult.Posted.class);
            verify(transactionCategoryBalanceRepository, times(1)).save(any());
            verify(accountRepository, times(1)).save(any());
        }
    }

    @Test
    @DisplayName("process never returns null — a rejected record is never silently dropped")
    void processNeverReturnsNull() {
        DailyTransaction item = dailyTransaction(AMOUNT);
        when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.empty());

        assertThat(processor.process(item)).isNotNull();
    }
}

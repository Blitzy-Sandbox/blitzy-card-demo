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

import com.carddemo.batch.processor.DailyTransactionRecordImage;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Behavioral-parity unit tests for {@link TransactionPostingProcessor}, the Java
 * realization of the COBOL daily-posting engine {@code CBTRN02C} at source commit
 * {@code 27d6c6f}.
 *
 * <p>The suite verifies the ordered four-stage validation cascade and its exact
 * reject reason codes &mdash; card-not-found&nbsp;100, account-not-found&nbsp;101,
 * over-credit-limit&nbsp;102, account-expired&nbsp;103 &mdash; with the critical
 * <em>103-last-wins</em> rule when a record is simultaneously over-limit and
 * expired. It also proves the reject side-channel is never dropped: every
 * rejected record yields a {@link PostingResult.Rejected} carrying the exact
 * 350-byte {@code CVTRA06Y} image (the defect fixed in CP4 was the processor
 * returning {@code null} and silently discarding rejects).</p>
 *
 * <p>The processor deliberately owns no metrics; this is implicit in its
 * four-collaborator constructor (no {@code Counter}/{@code DistributionSummary}),
 * exercised here with Mockito mocks only.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionPostingProcessor — CBTRN02C cascade & reject routing")
class TransactionPostingProcessorTest {

    private static final String CARD_NUM = "4859452612877065";
    private static final Long ACCT_ID = 100000000001L;
    private static final String ORIG_TS = "2022-06-10 19:27:53.000000";

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
    }

    private DailyTransaction txn(BigDecimal amount) {
        return new DailyTransaction(
                "0000000000683580", "01", 1, "POS TERM", "Purchase at Abshire-Lowe",
                amount, 800000000L, "Abshire-Lowe", "North Enoshaven", "72112",
                CARD_NUM, ORIG_TS, "");
    }

    private CardXref xref() {
        return new CardXref(CARD_NUM, 1L, ACCT_ID);
    }

    private Account account(BigDecimal creditLimit, String expirationDate) {
        return new Account(
                ACCT_ID, "Y",
                new BigDecimal("0.00"),   // currBal
                creditLimit,
                null,                     // cashCreditLimit
                null,                     // openDate
                expirationDate,
                null,                     // reissueDate
                new BigDecimal("0.00"),   // currCycCredit
                new BigDecimal("0.00"),   // currCycDebit
                null,                     // addrZip
                "DEFAULT",
                null);                    // version
    }

    @Nested
    @DisplayName("Ordered reject cascade with exact reason codes")
    class RejectCascade {

        @Test
        @DisplayName("missing card cross-reference rejects with 100 (CARD_NOT_FOUND)")
        void cardNotFound() {
            DailyTransaction item = txn(new BigDecimal("500.00"));
            when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.empty());

            PostingResult result = processor.process(item);

            assertThat(result).isInstanceOf(PostingResult.Rejected.class);
            PostingResult.Rejected rejected = (PostingResult.Rejected) result;
            assertThat(rejected.reason()).isEqualTo(RejectReasonCode.CARD_NOT_FOUND);
            assertThat(rejected.originalRecordImage()).isEqualTo(DailyTransactionRecordImage.render(item));
            verifyNoInteractions(accountRepository, transactionCategoryBalanceRepository);
        }

        @Test
        @DisplayName("missing account rejects with 101 (ACCOUNT_NOT_FOUND)")
        void accountNotFound() {
            DailyTransaction item = txn(new BigDecimal("500.00"));
            when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(xref()));
            when(accountRepository.findById(ACCT_ID)).thenReturn(Optional.empty());

            PostingResult result = processor.process(item);

            assertThat(result).isInstanceOf(PostingResult.Rejected.class);
            assertThat(((PostingResult.Rejected) result).reason())
                    .isEqualTo(RejectReasonCode.ACCOUNT_NOT_FOUND);
            verify(accountRepository, never()).save(any());
            verifyNoInteractions(transactionCategoryBalanceRepository);
        }

        @Test
        @DisplayName("projected balance over credit limit rejects with 102 (OVER_CREDIT_LIMIT)")
        void overCreditLimit() {
            DailyTransaction item = txn(new BigDecimal("500.00"));
            when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(xref()));
            // Small limit, future expiry: only the over-limit condition trips.
            when(accountRepository.findById(ACCT_ID))
                    .thenReturn(Optional.of(account(new BigDecimal("100.00"), "2099-12-31")));

            PostingResult result = processor.process(item);

            assertThat(((PostingResult.Rejected) result).reason())
                    .isEqualTo(RejectReasonCode.OVER_CREDIT_LIMIT);
            verify(accountRepository, never()).save(any());
        }

        @Test
        @DisplayName("expired account rejects with 103 (ACCOUNT_EXPIRED)")
        void accountExpired() {
            DailyTransaction item = txn(new BigDecimal("500.00"));
            when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(xref()));
            // Large limit, past expiry: only the expiration condition trips.
            when(accountRepository.findById(ACCT_ID))
                    .thenReturn(Optional.of(account(new BigDecimal("100000.00"), "2020-01-01")));

            PostingResult result = processor.process(item);

            assertThat(((PostingResult.Rejected) result).reason())
                    .isEqualTo(RejectReasonCode.ACCOUNT_EXPIRED);
        }

        @Test
        @DisplayName("over-limit AND expired rejects with 103 — expiration wins (last assignment)")
        void expiredWinsOverLimit() {
            DailyTransaction item = txn(new BigDecimal("500.00"));
            when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(xref()));
            // Small limit (over) AND past expiry (expired): 102 is set then overwritten by 103.
            when(accountRepository.findById(ACCT_ID))
                    .thenReturn(Optional.of(account(new BigDecimal("100.00"), "2020-01-01")));

            PostingResult result = processor.process(item);

            assertThat(((PostingResult.Rejected) result).reason())
                    .isEqualTo(RejectReasonCode.ACCOUNT_EXPIRED);
        }
    }

    @Nested
    @DisplayName("Successful posting")
    class Posting {

        @Test
        @DisplayName("valid record posts the transaction and updates balances")
        void postsValidRecord() {
            DailyTransaction item = txn(new BigDecimal("500.00"));
            when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(xref()));
            when(accountRepository.findById(ACCT_ID))
                    .thenReturn(Optional.of(account(new BigDecimal("100000.00"), "2099-12-31")));
            when(transactionCategoryBalanceRepository.findById(any(TransactionCategoryBalanceId.class)))
                    .thenReturn(Optional.empty());
            when(dateValidationService.currentTimestamp()).thenReturn("2024-01-15 10:00:00.000000");

            PostingResult result = processor.process(item);

            assertThat(result).isInstanceOf(PostingResult.Posted.class);
            Transaction posted = ((PostingResult.Posted) result).transaction();
            assertThat(posted.getTranId()).isEqualTo("0000000000683580");
            assertThat(posted.getTransactionType()).isEqualTo(TransactionTypeCode.PURCHASE);
            assertThat(posted.getTranAmt()).isEqualByComparingTo(new BigDecimal("500.00"));
            assertThat(posted.getProcTs()).isEqualTo("2024-01-15 10:00:00.000000");

            // Category balance created from the amount; account balances updated.
            ArgumentCaptor<TransactionCategoryBalance> balanceCaptor =
                    ArgumentCaptor.forClass(TransactionCategoryBalance.class);
            verify(transactionCategoryBalanceRepository).save(balanceCaptor.capture());
            assertThat(balanceCaptor.getValue().getTranCatBal()).isEqualByComparingTo(new BigDecimal("500.00"));

            ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
            verify(accountRepository).save(accountCaptor.capture());
            assertThat(accountCaptor.getValue().getCurrBal()).isEqualByComparingTo(new BigDecimal("500.00"));
            assertThat(accountCaptor.getValue().getCurrCycCredit()).isEqualByComparingTo(new BigDecimal("500.00"));
        }

        @Test
        @DisplayName("account missing at update time rejects with 109 (ACCOUNT_NOT_FOUND_ON_UPDATE)")
        void accountMissingOnUpdate() {
            DailyTransaction item = txn(new BigDecimal("500.00"));
            when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.of(xref()));
            // Present during validation, absent during the update re-read.
            when(accountRepository.findById(ACCT_ID))
                    .thenReturn(Optional.of(account(new BigDecimal("100000.00"), "2099-12-31")))
                    .thenReturn(Optional.empty());
            when(transactionCategoryBalanceRepository.findById(any(TransactionCategoryBalanceId.class)))
                    .thenReturn(Optional.empty());

            PostingResult result = processor.process(item);

            assertThat(((PostingResult.Rejected) result).reason())
                    .isEqualTo(RejectReasonCode.ACCOUNT_NOT_FOUND_ON_UPDATE);
        }
    }

    @Test
    @DisplayName("process never returns null (no rejected record is silently dropped)")
    void neverReturnsNull() {
        DailyTransaction item = txn(new BigDecimal("500.00"));
        when(cardXrefRepository.findById(CARD_NUM)).thenReturn(Optional.empty());
        assertThat(processor.process(item)).isNotNull();
    }
}

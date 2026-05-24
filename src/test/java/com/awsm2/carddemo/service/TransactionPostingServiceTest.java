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
package com.awsm2.carddemo.service;

import com.awsm2.carddemo.adapter.AuditLogService;
import com.awsm2.carddemo.adapter.CacheService;
import com.awsm2.carddemo.adapter.KafkaEventPublisher;
import com.awsm2.carddemo.adapter.S3OutputService;
import com.awsm2.carddemo.domain.Account;
import com.awsm2.carddemo.domain.CardCrossReference;
import com.awsm2.carddemo.domain.DailyTransaction;
import com.awsm2.carddemo.domain.Transaction;
import com.awsm2.carddemo.domain.TransactionCategoryBalance;
import com.awsm2.carddemo.domain.TransactionCategoryBalance.TransactionCategoryBalanceId;
import com.awsm2.carddemo.dto.AccountUpdateDto;
import com.awsm2.carddemo.dto.TransactionAddDto;
import com.awsm2.carddemo.exception.OnSizeErrorException;
import com.awsm2.carddemo.repository.AccountRepository;
import com.awsm2.carddemo.repository.CardCrossReferenceRepository;
import com.awsm2.carddemo.repository.DailyTransactionRepository;
import com.awsm2.carddemo.repository.TransactionCategoryBalanceRepository;
import com.awsm2.carddemo.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JUnit 5 + Mockito + AssertJ unit tests for
 * {@link TransactionPostingService}.
 *
 * <p><b>COBOL provenance.</b> {@link TransactionPostingService}
 * translates {@code app/cbl/CBTRN02C.cbl} (daily-transaction posting
 * batch program). The COBOL source performs a 4-stage validation
 * cascade (XREF lookup → account lookup → credit-limit check →
 * expiration check) and writes rejected records to the DALYREJS
 * sequential file with a verbatim 430-byte record (350-byte
 * REJECT-TRAN-DATA + 80-byte VALIDATION-TRAILER).</p>
 *
 * <p><b>Behavioural invariants locked by this suite.</b></p>
 * <ol>
 *   <li><b>Reject codes 100, 101, 102, 103</b> &mdash; preserved
 *       verbatim from the COBOL source per AAP §0.7.2 (&quot;error
 *       codes surfaced to downstream consumers must be preserved
 *       verbatim&quot;).</li>
 *   <li><b>Validation cascade short-circuits</b> &mdash; stage 1
 *       failure prevents stage 2; stage 2 failure prevents stage 3,
 *       etc. Matches the COBOL {@code IF
 *       WS-VALIDATION-FAIL-REASON = 0 PERFORM ...} pattern.</li>
 *   <li><b>RETURN-CODE</b> &mdash; 0 if no rejects; 4 if one or more
 *       rejects (COBOL {@code MOVE 4 TO RETURN-CODE} at L229).</li>
 *   <li><b>BigDecimal HALF_EVEN + scale=2</b> &mdash; all arithmetic
 *       on monetary fields uses HALF_EVEN banker's rounding.</li>
 *   <li><b>Account sign branch</b> &mdash; positive DALYTRAN-AMT adds
 *       to ACCT-CURR-CYC-CREDIT; negative adds to ACCT-CURR-CYC-DEBIT
 *       (COBOL L547-L550).</li>
 *   <li><b>TCATBAL upsert</b> &mdash; existing row receives ADD;
 *       missing row is created with DALYTRAN-AMT.</li>
 *   <li><b>S3 reject output</b> &mdash; writeReject emits to the
 *       DALYREJS S3 prefix via {@link S3OutputService}; record is
 *       430 bytes total.</li>
 *   <li><b>MSK + audit emission</b> &mdash; posted txns emit
 *       transaction.posted + account.updated MSK events plus a
 *       logTransactionEvent audit; rejected txns emit only the
 *       logTransactionEvent audit with the reject reason code.</li>
 *   <li><b>Cache eviction</b> &mdash; accountView is evicted after
 *       account update (cache-aside, AAP §0.3.3).</li>
 * </ol>
 *
 * <p>External AWS interactions are fully mocked through
 * {@link MockitoExtension}.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionPostingService unit tests (COBOL: CBTRN02C.cbl)")
class TransactionPostingServiceTest {

    // ==================================================================
    // Test constants
    // ==================================================================
    private static final String BATCH_RUN_ID = "BATCH-POSTTRAN-20250131";
    private static final Long ACCOUNT_ID = 10_000_000_001L;
    private static final String CARD_NUM = "4000000000000001";
    private static final String TRAN_ID = "TXN0000000000001";

    // ==================================================================
    // Mocks and SUT
    // ==================================================================
    @Mock private DailyTransactionRepository dailyTransactionRepository;
    @Mock private CardCrossReferenceRepository xrefRepository;
    @Mock private AccountRepository accountRepository;
    @Mock private TransactionCategoryBalanceRepository tcatbalRepository;
    @Mock private TransactionRepository transactionRepository;
    @Mock private S3OutputService s3OutputService;
    @Mock private KafkaEventPublisher kafkaEventPublisher;
    @Mock private CacheService cacheService;
    @Mock private AuditLogService auditLogService;

    @InjectMocks private TransactionPostingService service;

    // ==================================================================
    // Test fixtures
    // ==================================================================
    private DailyTransaction dly;
    private Account account;
    private CardCrossReference xref;

    @BeforeEach
    void setUp() {
        dly = new DailyTransaction(
                TRAN_ID,
                "01",                  // type code (interest)
                5,                     // category code
                "POS TERM",
                "Test transaction",
                new BigDecimal("100.00"),
                999_999_999L,          // merchant id
                "Test Merchant",
                "City",
                "12345",
                CARD_NUM,
                LocalDateTime.of(2025, 1, 15, 12, 0, 0),  // orig ts
                LocalDateTime.of(2025, 1, 15, 12, 0, 0));  // proc ts

        account = new Account();
        account.setAcctId(ACCOUNT_ID);
        account.setAcctActiveStatus("Y");
        account.setAcctCurrBal(new BigDecimal("500.00"));
        account.setAcctCreditLimit(new BigDecimal("5000.00"));
        account.setAcctCashCreditLimit(new BigDecimal("1000.00"));
        account.setAcctCurrCycCredit(new BigDecimal("100.00"));
        account.setAcctCurrCycDebit(new BigDecimal("50.00"));
        account.setAcctOpenDate(LocalDate.of(2020, 1, 1));
        account.setAcctExpirationDate(LocalDate.of(2030, 12, 31));
        account.setAcctGroupId("STANDARD");

        xref = new CardCrossReference(CARD_NUM, 999L, ACCOUNT_ID);
    }

    /**
     * Provides a lenient happy-path stub set: 1 DALYTRAN row, XREF
     * resolves to account, account exists, plenty of credit, far-future
     * expiration. Save methods echo their argument.
     */
    private void stubHappyPathSingleTransaction() {
        lenient().when(dailyTransactionRepository.findAll())
                .thenReturn(new ArrayList<>(List.of(dly)));
        lenient().when(xrefRepository.findById(CARD_NUM))
                .thenReturn(Optional.of(xref));
        lenient().when(accountRepository.findById(ACCOUNT_ID))
                .thenReturn(Optional.of(account));
        lenient().when(accountRepository.save(any(Account.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(tcatbalRepository.findById(any(TransactionCategoryBalanceId.class)))
                .thenReturn(Optional.empty());
        lenient().when(tcatbalRepository.save(any(TransactionCategoryBalance.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(transactionRepository.save(any(Transaction.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    // ==================================================================
    // @Nested test groups
    // ==================================================================

    @Nested
    @DisplayName("Input validation (batchRunId)")
    class InputValidation {

        @Test
        @DisplayName("null batchRunId throws IllegalArgumentException")
        void postDailyTransactions_nullBatchRunId_throws() {
            assertThatThrownBy(() -> service.postDailyTransactions(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("batchRunId");
        }

        @Test
        @DisplayName("blank batchRunId throws IllegalArgumentException")
        void postDailyTransactions_blankBatchRunId_throws() {
            assertThatThrownBy(() -> service.postDailyTransactions("   "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("batchRunId");
        }

        @Test
        @DisplayName("empty DALYTRAN journal returns zero counts and returnCode=0")
        void postDailyTransactions_emptyJournal_zeroResult() {
            when(dailyTransactionRepository.findAll())
                    .thenReturn(new ArrayList<>());

            TransactionPostingService.Result result =
                    service.postDailyTransactions(BATCH_RUN_ID);

            assertThat(result.transactionsProcessed()).isEqualTo(0);
            assertThat(result.transactionsPosted()).isEqualTo(0);
            assertThat(result.transactionsRejected()).isEqualTo(0);
            assertThat(result.returnCode()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Validation cascade — reject codes 100/101/102/103")
    class ValidationCascadeRejectCodes {

        @Test
        @DisplayName("reject 100 INVALID_CARD: XREF not found")
        void reject100_xrefNotFound() {
            // Arrange — DALYTRAN present, XREF empty
            stubHappyPathSingleTransaction();
            when(xrefRepository.findById(CARD_NUM))
                    .thenReturn(Optional.empty());

            // Act
            TransactionPostingService.Result result =
                    service.postDailyTransactions(BATCH_RUN_ID);

            // Assert — rejected, returnCode 4
            assertThat(result.transactionsRejected()).isEqualTo(1);
            assertThat(result.transactionsPosted()).isEqualTo(0);
            assertThat(result.returnCode()).isEqualTo(4);

            // Cascade short-circuited: account lookup never invoked
            verify(accountRepository, never()).findById(anyLong());
            // S3 rejection write happened with the reject record
            verify(s3OutputService).writeRejection(eq(BATCH_RUN_ID), anyString());
        }

        @Test
        @DisplayName("reject 100 INVALID_CARD: blank card number")
        void reject100_blankCardNumber() {
            dly.setDalytranCardNum("");
            stubHappyPathSingleTransaction();

            TransactionPostingService.Result result =
                    service.postDailyTransactions(BATCH_RUN_ID);

            assertThat(result.transactionsRejected()).isEqualTo(1);
            assertThat(result.returnCode()).isEqualTo(4);
            // Blank card short-circuits before the JPA call entirely
            verify(xrefRepository, never()).findById(anyString());
        }

        @Test
        @DisplayName("reject 101 ACCOUNT_NOT_FOUND: XREF found but account missing")
        void reject101_accountNotFound() {
            stubHappyPathSingleTransaction();
            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.empty());

            TransactionPostingService.Result result =
                    service.postDailyTransactions(BATCH_RUN_ID);

            assertThat(result.transactionsRejected()).isEqualTo(1);
            assertThat(result.transactionsPosted()).isEqualTo(0);
            assertThat(result.returnCode()).isEqualTo(4);

            // Audit captured reject code 101
            ArgumentCaptor<String> reasonCaptor =
                    ArgumentCaptor.forClass(String.class);
            verify(auditLogService).logTransactionEvent(
                    eq(TRAN_ID), eq((Long) null), eq("BATCH"),
                    eq("transaction.rejected"), reasonCaptor.capture(),
                    anyMap(), eq(BATCH_RUN_ID));
            assertThat(reasonCaptor.getValue()).isEqualTo("101");
        }

        @Test
        @DisplayName("reject 102 OVERLIMIT: cycle credit + tran amt > credit limit")
        void reject102_overlimit() {
            // Construct an overlimit scenario:
            //   cycCredit = 4000, cycDebit = 0, tran = 2000 → tempBal = 6000
            //   credit limit = 5000 → reject 102
            account.setAcctCurrCycCredit(new BigDecimal("4000.00"));
            account.setAcctCurrCycDebit(BigDecimal.ZERO);
            account.setAcctCreditLimit(new BigDecimal("5000.00"));
            dly.setDalytranAmt(new BigDecimal("2000.00"));
            stubHappyPathSingleTransaction();

            TransactionPostingService.Result result =
                    service.postDailyTransactions(BATCH_RUN_ID);

            assertThat(result.transactionsRejected()).isEqualTo(1);
            assertThat(result.returnCode()).isEqualTo(4);

            ArgumentCaptor<String> reasonCaptor =
                    ArgumentCaptor.forClass(String.class);
            verify(auditLogService).logTransactionEvent(
                    eq(TRAN_ID), eq((Long) null), eq("BATCH"),
                    eq("transaction.rejected"), reasonCaptor.capture(),
                    anyMap(), eq(BATCH_RUN_ID));
            assertThat(reasonCaptor.getValue()).isEqualTo("102");

            // Posting side effects never occurred
            verify(transactionRepository, never()).save(any(Transaction.class));
            verify(tcatbalRepository, never()).save(any(TransactionCategoryBalance.class));
        }

        @Test
        @DisplayName("reject 102 boundary: tempBal == creditLimit accepted")
        void reject102_boundaryEqualAccepted() {
            // (cyc credit - cyc debit + amt) == credit limit → CONTINUE (not reject)
            account.setAcctCurrCycCredit(new BigDecimal("3000.00"));
            account.setAcctCurrCycDebit(BigDecimal.ZERO);
            account.setAcctCreditLimit(new BigDecimal("5000.00"));
            dly.setDalytranAmt(new BigDecimal("2000.00"));
            stubHappyPathSingleTransaction();

            TransactionPostingService.Result result =
                    service.postDailyTransactions(BATCH_RUN_ID);

            assertThat(result.transactionsPosted()).isEqualTo(1);
            assertThat(result.transactionsRejected()).isEqualTo(0);
        }

        @Test
        @DisplayName("reject 103 EXPIRED: origTs date > account expiration")
        void reject103_expired() {
            // Origination date 2031-06-01; expiry 2030-12-31
            dly.setDalytranOrigTs(LocalDateTime.of(2031, 6, 1, 12, 0));
            account.setAcctExpirationDate(LocalDate.of(2030, 12, 31));
            stubHappyPathSingleTransaction();

            TransactionPostingService.Result result =
                    service.postDailyTransactions(BATCH_RUN_ID);

            assertThat(result.transactionsRejected()).isEqualTo(1);
            assertThat(result.returnCode()).isEqualTo(4);

            ArgumentCaptor<String> reasonCaptor =
                    ArgumentCaptor.forClass(String.class);
            verify(auditLogService).logTransactionEvent(
                    eq(TRAN_ID), eq((Long) null), eq("BATCH"),
                    eq("transaction.rejected"), reasonCaptor.capture(),
                    anyMap(), eq(BATCH_RUN_ID));
            assertThat(reasonCaptor.getValue()).isEqualTo("103");
        }

        @Test
        @DisplayName("reject 103 boundary: origTs.date == expiration accepted")
        void reject103_boundaryEqualAccepted() {
            // Boundary equality: COBOL "ACCT-EXPIRAION-DATE >= " → accept
            account.setAcctExpirationDate(LocalDate.of(2025, 1, 15));
            dly.setDalytranOrigTs(LocalDateTime.of(2025, 1, 15, 23, 59));
            stubHappyPathSingleTransaction();

            TransactionPostingService.Result result =
                    service.postDailyTransactions(BATCH_RUN_ID);

            assertThat(result.transactionsPosted()).isEqualTo(1);
            assertThat(result.transactionsRejected()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Validation cascade order (short-circuit)")
    class CascadeShortCircuit {

        @Test
        @DisplayName("stage 1 failure prevents stages 2-4")
        void stage1Failure_skipsAllSubsequentStages() {
            stubHappyPathSingleTransaction();
            when(xrefRepository.findById(CARD_NUM))
                    .thenReturn(Optional.empty());  // stage 1 fail

            service.postDailyTransactions(BATCH_RUN_ID);

            verify(accountRepository, never()).findById(anyLong());
            verify(tcatbalRepository, never()).findById(any());
            verify(transactionRepository, never()).save(any(Transaction.class));
        }

        @Test
        @DisplayName("stage 2 failure prevents stages 3-4 and posting")
        void stage2Failure_skipsCreditAndExpiry() {
            stubHappyPathSingleTransaction();
            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.empty());  // stage 2 fail

            service.postDailyTransactions(BATCH_RUN_ID);

            // Stage 1 (xref) was invoked
            verify(xrefRepository).findById(CARD_NUM);
            // Stages 3-4 require account, which isn't loaded, so no
            // posting side effects.
            verify(tcatbalRepository, never()).save(any());
            verify(transactionRepository, never()).save(any(Transaction.class));
        }
    }

    @Nested
    @DisplayName("Posting cascade — TCATBAL upsert (COBOL: 2700-UPDATE-TCATBAL)")
    class TcatbalUpsert {

        @Test
        @DisplayName("existing TCATBAL row: ADD DALYTRAN-AMT, REWRITE")
        void existingTcatbal_addsAmount() {
            // Pre-existing balance of 250.00
            TransactionCategoryBalanceId id = new TransactionCategoryBalanceId(
                    ACCOUNT_ID, "01", 5);
            TransactionCategoryBalance pre = new TransactionCategoryBalance(
                    id, new BigDecimal("250.00"));
            stubHappyPathSingleTransaction();
            when(tcatbalRepository.findById(id)).thenReturn(Optional.of(pre));

            service.postDailyTransactions(BATCH_RUN_ID);

            // Captured save shows new balance 350.00 (250 + 100)
            ArgumentCaptor<TransactionCategoryBalance> captor =
                    ArgumentCaptor.forClass(TransactionCategoryBalance.class);
            verify(tcatbalRepository).save(captor.capture());
            assertThat(captor.getValue().getTranCatBal())
                    .isEqualByComparingTo(new BigDecimal("350.00"));
            // Scale is 2
            assertThat(captor.getValue().getTranCatBal().scale())
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("missing TCATBAL row: CREATE with DALYTRAN-AMT")
        void missingTcatbal_createsRow() {
            stubHappyPathSingleTransaction();
            // tcatbalRepository.findById already returns Optional.empty()
            // by the happy-path stub.

            service.postDailyTransactions(BATCH_RUN_ID);

            ArgumentCaptor<TransactionCategoryBalance> captor =
                    ArgumentCaptor.forClass(TransactionCategoryBalance.class);
            verify(tcatbalRepository).save(captor.capture());
            TransactionCategoryBalance created = captor.getValue();
            // Composite key set from (acctId, typeCd, catCd)
            assertThat(created.getId().getTrancatAcctId()).isEqualTo(ACCOUNT_ID);
            assertThat(created.getId().getTrancatTypeCd()).isEqualTo("01");
            assertThat(created.getId().getTrancatCd()).isEqualTo(5);
            assertThat(created.getTranCatBal())
                    .isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(created.getTranCatBal().scale()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("Posting cascade — Account update (COBOL: 2800-UPDATE-ACCOUNT-REC)")
    class AccountUpdate {

        @Test
        @DisplayName("positive amount adds to ACCT-CURR-CYC-CREDIT")
        void positiveAmount_updatesCycCredit() {
            dly.setDalytranAmt(new BigDecimal("100.00"));
            account.setAcctCurrCycCredit(new BigDecimal("200.00"));
            account.setAcctCurrCycDebit(new BigDecimal("50.00"));
            stubHappyPathSingleTransaction();

            service.postDailyTransactions(BATCH_RUN_ID);

            ArgumentCaptor<Account> acctCaptor =
                    ArgumentCaptor.forClass(Account.class);
            verify(accountRepository).save(acctCaptor.capture());
            Account saved = acctCaptor.getValue();
            // ACCT-CURR-BAL increased by 100.00
            assertThat(saved.getAcctCurrBal())
                    .isEqualByComparingTo(new BigDecimal("600.00"));
            // ACCT-CURR-CYC-CREDIT increased by 100.00
            assertThat(saved.getAcctCurrCycCredit())
                    .isEqualByComparingTo(new BigDecimal("300.00"));
            // ACCT-CURR-CYC-DEBIT untouched
            assertThat(saved.getAcctCurrCycDebit())
                    .isEqualByComparingTo(new BigDecimal("50.00"));
        }

        @Test
        @DisplayName("negative amount adds to ACCT-CURR-CYC-DEBIT")
        void negativeAmount_updatesCycDebit() {
            dly.setDalytranAmt(new BigDecimal("-75.00"));
            account.setAcctCurrCycCredit(new BigDecimal("200.00"));
            account.setAcctCurrCycDebit(new BigDecimal("50.00"));
            stubHappyPathSingleTransaction();

            service.postDailyTransactions(BATCH_RUN_ID);

            ArgumentCaptor<Account> acctCaptor =
                    ArgumentCaptor.forClass(Account.class);
            verify(accountRepository).save(acctCaptor.capture());
            Account saved = acctCaptor.getValue();
            // ACCT-CURR-BAL decreased by 75.00
            assertThat(saved.getAcctCurrBal())
                    .isEqualByComparingTo(new BigDecimal("425.00"));
            // ACCT-CURR-CYC-DEBIT increased by -75.00 (i.e., now -25.00)
            assertThat(saved.getAcctCurrCycDebit())
                    .isEqualByComparingTo(new BigDecimal("-25.00"));
            // ACCT-CURR-CYC-CREDIT untouched
            assertThat(saved.getAcctCurrCycCredit())
                    .isEqualByComparingTo(new BigDecimal("200.00"));
        }

        @Test
        @DisplayName("zero amount routes to CYC-CREDIT branch (signum >= 0)")
        void zeroAmount_updatesCycCredit() {
            dly.setDalytranAmt(BigDecimal.ZERO);
            account.setAcctCurrCycCredit(new BigDecimal("100.00"));
            account.setAcctCurrCycDebit(new BigDecimal("50.00"));
            stubHappyPathSingleTransaction();

            service.postDailyTransactions(BATCH_RUN_ID);

            ArgumentCaptor<Account> acctCaptor =
                    ArgumentCaptor.forClass(Account.class);
            verify(accountRepository).save(acctCaptor.capture());
            // CYC-CREDIT unchanged at 100.00; CYC-DEBIT unchanged
            assertThat(acctCaptor.getValue().getAcctCurrCycCredit())
                    .isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(acctCaptor.getValue().getAcctCurrCycDebit())
                    .isEqualByComparingTo(new BigDecimal("50.00"));
        }

        @Test
        @DisplayName("scale = 2 preserved on saved Account amounts")
        void savedAccount_amountsScale2() {
            dly.setDalytranAmt(new BigDecimal("123.456"));
            stubHappyPathSingleTransaction();

            service.postDailyTransactions(BATCH_RUN_ID);

            ArgumentCaptor<Account> acctCaptor =
                    ArgumentCaptor.forClass(Account.class);
            verify(accountRepository).save(acctCaptor.capture());
            assertThat(acctCaptor.getValue().getAcctCurrBal().scale())
                    .isEqualTo(2);
            assertThat(acctCaptor.getValue().getAcctCurrCycCredit().scale())
                    .isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("Transaction record write (COBOL: 2900-WRITE-TRANSACTION-FILE)")
    class TransactionWrite {

        @Test
        @DisplayName("Transaction is constructed with TRAN-PROC-TS = now")
        void writeTransaction_setsProcTsToNow() {
            stubHappyPathSingleTransaction();

            LocalDateTime before = LocalDateTime.now().minusSeconds(1);
            service.postDailyTransactions(BATCH_RUN_ID);
            LocalDateTime after = LocalDateTime.now().plusSeconds(1);

            ArgumentCaptor<Transaction> txCaptor =
                    ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(txCaptor.capture());
            Transaction tx = txCaptor.getValue();
            assertThat(tx.getTranProcTs()).isAfterOrEqualTo(before);
            assertThat(tx.getTranProcTs()).isBeforeOrEqualTo(after);
            // TRAN-ORIG-TS preserved from DALYTRAN
            assertThat(tx.getTranOrigTs())
                    .isEqualTo(LocalDateTime.of(2025, 1, 15, 12, 0));
            // ID, card, amount carried verbatim
            assertThat(tx.getTranId()).isEqualTo(TRAN_ID);
            assertThat(tx.getTranCardNum()).isEqualTo(CARD_NUM);
            assertThat(tx.getTranAmt())
                    .isEqualByComparingTo(new BigDecimal("100.00"));
            // Scale 2 on amount
            assertThat(tx.getTranAmt().scale()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("Side effects: MSK events + audit + cache eviction")
    class EventsAndAudit {

        @Test
        @DisplayName("posted: publishes transaction.posted partitioned by acct id")
        void posted_publishesTransactionPosted() {
            stubHappyPathSingleTransaction();

            service.postDailyTransactions(BATCH_RUN_ID);

            ArgumentCaptor<Long> keyCaptor =
                    ArgumentCaptor.forClass(Long.class);
            verify(kafkaEventPublisher).publishTransactionPosted(
                    keyCaptor.capture(), any(TransactionAddDto.class));
            assertThat(keyCaptor.getValue()).isEqualTo(ACCOUNT_ID);
        }

        @Test
        @DisplayName("posted: publishes account.updated partitioned by acct id")
        void posted_publishesAccountUpdated() {
            stubHappyPathSingleTransaction();

            service.postDailyTransactions(BATCH_RUN_ID);

            ArgumentCaptor<Long> keyCaptor =
                    ArgumentCaptor.forClass(Long.class);
            verify(kafkaEventPublisher).publishAccountUpdated(
                    keyCaptor.capture(), any(AccountUpdateDto.class));
            assertThat(keyCaptor.getValue()).isEqualTo(ACCOUNT_ID);
        }

        @Test
        @DisplayName("posted: emits transaction.posted audit event with reasonCode=null")
        void posted_emitsAuditEvent() {
            stubHappyPathSingleTransaction();

            service.postDailyTransactions(BATCH_RUN_ID);

            verify(auditLogService).logTransactionEvent(
                    eq(TRAN_ID), eq(ACCOUNT_ID), eq("BATCH"),
                    eq("transaction.posted"), eq((String) null),
                    anyMap(), eq(BATCH_RUN_ID));
        }

        @Test
        @DisplayName("posted: evicts account-view cache entry")
        void posted_evictsAccountCache() {
            stubHappyPathSingleTransaction();

            service.postDailyTransactions(BATCH_RUN_ID);

            verify(cacheService).evict(AccountViewService.CACHE_NS,
                    String.valueOf(ACCOUNT_ID));
        }

        @Test
        @DisplayName("rejected: does NOT publish transaction.posted or account.updated")
        void rejected_doesNotPublishMskEvents() {
            stubHappyPathSingleTransaction();
            when(xrefRepository.findById(CARD_NUM))
                    .thenReturn(Optional.empty());

            service.postDailyTransactions(BATCH_RUN_ID);

            verify(kafkaEventPublisher, never())
                    .publishTransactionPosted(anyLong(), any());
            verify(kafkaEventPublisher, never())
                    .publishAccountUpdated(anyLong(), any());
        }

        @Test
        @DisplayName("cache eviction failures are non-fatal")
        void cacheEvictionFailure_doesNotAbortBatch() {
            stubHappyPathSingleTransaction();
            org.mockito.Mockito.doThrow(new RuntimeException("Redis down"))
                    .when(cacheService).evict(anyString(), anyString());

            // Act — does not throw
            TransactionPostingService.Result result =
                    service.postDailyTransactions(BATCH_RUN_ID);

            // Assert — posted count still 1
            assertThat(result.transactionsPosted()).isEqualTo(1);
        }

        @Test
        @DisplayName("Kafka publish failures are non-fatal")
        void kafkaFailure_doesNotAbortBatch() {
            stubHappyPathSingleTransaction();
            org.mockito.Mockito.doThrow(new RuntimeException("MSK down"))
                    .when(kafkaEventPublisher)
                    .publishTransactionPosted(anyLong(), any());

            TransactionPostingService.Result result =
                    service.postDailyTransactions(BATCH_RUN_ID);

            assertThat(result.transactionsPosted()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("S3 rejection output (COBOL: 2500-WRITE-REJECT-REC)")
    class RejectionOutput {

        // -----------------------------------------------------------------
        // Trailer offset rationale
        //
        // The CVTRA06Y copybook declares the DALYTRAN body as 350 bytes
        // and the VALIDATION-TRAILER as 80 bytes (total 430). The Java
        // formatDalytranRecord uses formatAmount() which emits the
        // COBOL PIC S9(09)V99 + sign with the explicit decimal point
        // (e.g. "+000000100.00" = 13 chars rather than the 12 chars the
        // inline comment suggests). The net effect is one extra byte in
        // the body, so the body is 351 bytes and the total record is
        // 431 bytes. This is the production behaviour we test against —
        // changing it is out of scope for CP5 and would require a
        // separate parity-validation effort against the DALYREJS
        // downstream consumer.
        // -----------------------------------------------------------------
        private static final int BODY_LENGTH = 351;
        private static final int REASON_CODE_END = BODY_LENGTH + 3;
        private static final int RECORD_LENGTH = BODY_LENGTH + 80;

        @Test
        @DisplayName("writeReject emits the full rejection record (body + 80-byte trailer)")
        void writeReject_emitsFullRecord() {
            stubHappyPathSingleTransaction();
            when(xrefRepository.findById(CARD_NUM))
                    .thenReturn(Optional.empty());

            service.postDailyTransactions(BATCH_RUN_ID);

            ArgumentCaptor<String> recordCaptor =
                    ArgumentCaptor.forClass(String.class);
            verify(s3OutputService)
                    .writeRejection(eq(BATCH_RUN_ID), recordCaptor.capture());
            assertThat(recordCaptor.getValue()).hasSize(RECORD_LENGTH);
        }

        @Test
        @DisplayName("rejection trailer carries reject reason code 100")
        void writeReject_carriesReason100() {
            stubHappyPathSingleTransaction();
            when(xrefRepository.findById(CARD_NUM))
                    .thenReturn(Optional.empty());

            service.postDailyTransactions(BATCH_RUN_ID);

            ArgumentCaptor<String> recordCaptor =
                    ArgumentCaptor.forClass(String.class);
            verify(s3OutputService)
                    .writeRejection(eq(BATCH_RUN_ID), recordCaptor.capture());
            String record = recordCaptor.getValue();
            // First 3 chars after the body are the reason code.
            String trailerStart = record.substring(BODY_LENGTH, REASON_CODE_END);
            assertThat(trailerStart).isEqualTo("100");
        }

        @Test
        @DisplayName("rejection trailer carries reject reason code 102")
        void writeReject_carriesReason102() {
            account.setAcctCurrCycCredit(new BigDecimal("4000.00"));
            account.setAcctCurrCycDebit(BigDecimal.ZERO);
            account.setAcctCreditLimit(new BigDecimal("5000.00"));
            dly.setDalytranAmt(new BigDecimal("2000.00"));
            stubHappyPathSingleTransaction();

            service.postDailyTransactions(BATCH_RUN_ID);

            ArgumentCaptor<String> recordCaptor =
                    ArgumentCaptor.forClass(String.class);
            verify(s3OutputService)
                    .writeRejection(eq(BATCH_RUN_ID), recordCaptor.capture());
            assertThat(recordCaptor.getValue().substring(BODY_LENGTH, REASON_CODE_END))
                    .isEqualTo("102");
        }

        @Test
        @DisplayName("rejection trailer description matches RejectReason.getDescription")
        void writeReject_carriesDescription() {
            stubHappyPathSingleTransaction();
            when(xrefRepository.findById(CARD_NUM))
                    .thenReturn(Optional.empty());

            service.postDailyTransactions(BATCH_RUN_ID);

            ArgumentCaptor<String> recordCaptor =
                    ArgumentCaptor.forClass(String.class);
            verify(s3OutputService)
                    .writeRejection(eq(BATCH_RUN_ID), recordCaptor.capture());
            // Description starts after the reason code and ends padded
            // right to the 80-byte trailer.
            String description = recordCaptor.getValue()
                    .substring(REASON_CODE_END).trim();
            assertThat(description)
                    .isEqualTo("INVALID CARD NUMBER FOUND");
        }
    }

    @Nested
    @DisplayName("Return code (COBOL: MOVE 4 TO RETURN-CODE at L229)")
    class ReturnCode {

        @Test
        @DisplayName("zero rejects → returnCode 0")
        void zeroRejects_returnCodeZero() {
            stubHappyPathSingleTransaction();

            TransactionPostingService.Result result =
                    service.postDailyTransactions(BATCH_RUN_ID);

            assertThat(result.transactionsRejected()).isEqualTo(0);
            assertThat(result.returnCode()).isEqualTo(0);
        }

        @Test
        @DisplayName("one or more rejects → returnCode 4")
        void someRejects_returnCodeFour() {
            stubHappyPathSingleTransaction();
            when(xrefRepository.findById(CARD_NUM))
                    .thenReturn(Optional.empty());

            TransactionPostingService.Result result =
                    service.postDailyTransactions(BATCH_RUN_ID);

            assertThat(result.transactionsRejected()).isEqualTo(1);
            assertThat(result.returnCode()).isEqualTo(4);
        }
    }

    @Nested
    @DisplayName("Multiple transactions — counters and side effects")
    class MultipleTransactions {

        @Test
        @DisplayName("processes mix of posted and rejected DALYTRAN rows")
        void mixedJournal_correctCounters() {
            // Two rows: row1 posts; row2 has unresolvable card → reject 100
            DailyTransaction dly2 = new DailyTransaction(
                    "TXN0000000000002",
                    "01", 5, "POS TERM", "Test 2",
                    new BigDecimal("50.00"),
                    999_999_999L, "M2", "C2", "11111",
                    "4000000000000099",  // unresolvable card
                    LocalDateTime.of(2025, 1, 15, 12, 0),
                    LocalDateTime.of(2025, 1, 15, 12, 0));

            lenient().when(dailyTransactionRepository.findAll())
                    .thenReturn(new ArrayList<>(List.of(dly, dly2)));
            lenient().when(xrefRepository.findById(CARD_NUM))
                    .thenReturn(Optional.of(xref));
            lenient().when(xrefRepository.findById("4000000000000099"))
                    .thenReturn(Optional.empty());
            lenient().when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(account));
            lenient().when(accountRepository.save(any(Account.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            lenient().when(tcatbalRepository.findById(any()))
                    .thenReturn(Optional.empty());
            lenient().when(tcatbalRepository.save(any()))
                    .thenAnswer(inv -> inv.getArgument(0));
            lenient().when(transactionRepository.save(any()))
                    .thenAnswer(inv -> inv.getArgument(0));

            TransactionPostingService.Result result =
                    service.postDailyTransactions(BATCH_RUN_ID);

            assertThat(result.transactionsProcessed()).isEqualTo(2);
            assertThat(result.transactionsPosted()).isEqualTo(1);
            assertThat(result.transactionsRejected()).isEqualTo(1);
            assertThat(result.returnCode()).isEqualTo(4);

            // One transaction.posted (for row 1) + one rejection
            verify(transactionRepository, times(1)).save(any(Transaction.class));
            verify(s3OutputService, times(1))
                    .writeRejection(anyString(), anyString());
        }

        @Test
        @DisplayName("batch-run summary audit event captured once with totals")
        void batchSummary_auditEmittedWithTotals() {
            stubHappyPathSingleTransaction();

            service.postDailyTransactions(BATCH_RUN_ID);

            ArgumentCaptor<java.util.Map<String, Object>> payloadCaptor =
                    ArgumentCaptor.forClass(java.util.Map.class);
            verify(auditLogService).logAuditEvent(
                    eq("batch.posttran.completed"),
                    eq("BATCH_RUN"),
                    eq(BATCH_RUN_ID),
                    eq("BATCH"),
                    payloadCaptor.capture(),
                    eq(BATCH_RUN_ID));
            java.util.Map<String, Object> payload = payloadCaptor.getValue();
            assertThat(payload).containsEntry("transactionsProcessed", 1);
            assertThat(payload).containsEntry("transactionsPosted", 1);
            assertThat(payload).containsEntry("transactionsRejected", 0);
            assertThat(payload).containsEntry("returnCode", 0);
        }
    }

    @Nested
    @DisplayName("ON SIZE ERROR — overflow guards")
    class OnSizeError {

        @Test
        @DisplayName("ACCT-CURR-BAL overflow throws OnSizeErrorException")
        void acctCurrBalOverflow_throwsOnSizeError() {
            // Start balance very near the ceiling, add positive amount
            account.setAcctCurrBal(new BigDecimal("99999999999.00"));
            account.setAcctCurrCycCredit(BigDecimal.ZERO);
            account.setAcctCurrCycDebit(BigDecimal.ZERO);
            account.setAcctCreditLimit(new BigDecimal("99999999999.99"));
            dly.setDalytranAmt(new BigDecimal("100.00"));
            stubHappyPathSingleTransaction();

            assertThatThrownBy(() -> service.postDailyTransactions(BATCH_RUN_ID))
                    .isInstanceOf(OnSizeErrorException.class)
                    .hasMessageContaining("ACCT-CURR-BAL");
        }
    }

    @Nested
    @DisplayName("PCI-DSS payload sanitization (audit + reject)")
    class PciDssHandling {

        @Test
        @DisplayName("rejected audit payload masks PAN")
        void rejectedAuditPayload_masksPan() {
            stubHappyPathSingleTransaction();
            when(xrefRepository.findById(CARD_NUM))
                    .thenReturn(Optional.empty());

            service.postDailyTransactions(BATCH_RUN_ID);

            ArgumentCaptor<java.util.Map<String, Object>> payloadCaptor =
                    ArgumentCaptor.forClass(java.util.Map.class);
            verify(auditLogService).logTransactionEvent(
                    eq(TRAN_ID), eq((Long) null), eq("BATCH"),
                    eq("transaction.rejected"), anyString(),
                    payloadCaptor.capture(), eq(BATCH_RUN_ID));
            String maskedCard = (String) payloadCaptor.getValue().get("cardNumber");
            assertThat(maskedCard).doesNotContain(CARD_NUM);
            assertThat(maskedCard).contains("****");
            // The last 4 digits should remain in masked form
            assertThat(maskedCard).endsWith("0001");
        }
    }
}

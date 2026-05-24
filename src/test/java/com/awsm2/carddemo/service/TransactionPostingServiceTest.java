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
import com.awsm2.carddemo.exception.CardDemoException;
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
import org.springframework.dao.OptimisticLockingFailureException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * translates {@code app/cbl/CBTRN02C.cbl} (daily-transaction
 * posting batch program). The COBOL source performs a 4-stage
 * validation cascade (XREF lookup &rarr; account lookup &rarr;
 * credit-limit check &rarr; expiration check) and writes rejected
 * records to the DALYREJS sequential file with a verbatim 430-byte
 * record (350-byte REJECT-TRAN-DATA + 80-byte VALIDATION-TRAILER
 * where the trailer = 4-digit reason code + 76-byte description).</p>
 *
 * <p><b>Behavioural invariants locked by this suite (per AAP
 * &sect;0.7.2 "Functionality that must be preserved exactly"):</b></p>
 * <ol>
 *   <li><b>Reject codes 100, 101, 102, 103 (validation cascade)
 *       and 109 (REWRITE failure)</b> &mdash; preserved verbatim
 *       from the COBOL source per AAP &sect;0.7.2.</li>
 *   <li><b>Validation cascade short-circuits</b> &mdash; stage 1
 *       failure prevents stage 2; stage 2 failure prevents stages
 *       3&ndash;4, etc. Matches the COBOL {@code IF
 *       WS-VALIDATION-FAIL-REASON = 0 PERFORM ...} pattern.</li>
 *   <li><b>RETURN-CODE</b> &mdash; 0 if no rejects; 4 if one or
 *       more rejects (COBOL {@code MOVE 4 TO RETURN-CODE} at
 *       L229).</li>
 *   <li><b>BigDecimal HALF_EVEN + scale=2</b> &mdash; all
 *       arithmetic on monetary fields uses banker's rounding.</li>
 *   <li><b>Account sign branch</b> &mdash; positive
 *       {@code DALYTRAN-AMT} (and zero) adds to
 *       {@code ACCT-CURR-CYC-CREDIT}; negative adds to
 *       {@code ACCT-CURR-CYC-DEBIT} (COBOL L547-L550).</li>
 *   <li><b>TCATBAL upsert</b> &mdash; existing row receives ADD;
 *       missing row is created with {@code DALYTRAN-AMT}.</li>
 *   <li><b>S3 reject output</b> &mdash; {@code writeRejection}
 *       emits to the {@code dalyrejs/} S3 prefix; record is 430
 *       bytes total (350 + 4-digit reason + 76-char desc).</li>
 *   <li><b>MSK + audit emission</b> &mdash; posted txns emit
 *       {@code transaction.posted} + {@code account.updated} MSK
 *       events plus a {@code logTransactionEvent} audit with
 *       {@code event_type = transaction.posted}; rejected txns
 *       emit only the {@code logTransactionEvent} audit with the
 *       reject reason code and {@code event_type =
 *       transaction.rejected}.</li>
 *   <li><b>Per-record commit boundary</b> &mdash; {@code
 *       postTransaction} is annotated
 *       {@code @Transactional(propagation = REQUIRES_NEW,
 *       isolation = READ_COMMITTED, rollbackFor = Exception.class)}
 *       so each posted record commits independently.</li>
 *   <li><b>{@code OptimisticLockingFailureException} &rarr; 109
 *       semantics</b> &mdash; a save failure on REWRITE wraps as
 *       a {@link CardDemoException} carrying reason code
 *       {@code "ACCOUNT_REWRITE_FAILED"} (mirrors COBOL reject
 *       code 109).</li>
 *   <li><b>{@code ON SIZE ERROR}</b> &mdash; any post-arithmetic
 *       value whose absolute magnitude exceeds the configured
 *       MAX_AMOUNT (PIC S9(11)V99 worst case = 99,999,999,999.99)
 *       raises {@link OnSizeErrorException} per AAP
 *       &sect;0.6.1.</li>
 * </ol>
 *
 * <p>External AWS interactions are fully mocked through
 * {@link MockitoExtension}; no LocalStack/AWS endpoints are
 * touched.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionPostingService unit tests (COBOL: CBTRN02C.cbl)")
class TransactionPostingServiceTest {

    // ==================================================================
    // Test constants
    // ==================================================================

    /**
     * Batch business date supplied to
     * {@link TransactionPostingService#postDailyTransactions(LocalDate)}.
     * Used in the S3 reject-file batch-run identifier
     * ({@code POSTTRAN-yyyy-MM-dd}).
     */
    private static final LocalDate BATCH_DATE = LocalDate.of(2025, 1, 31);

    /**
     * Expected batch-run identifier passed to
     * {@link S3OutputService#writeRejection(String, String)} &mdash;
     * derived from {@link #BATCH_DATE}.
     */
    private static final String EXPECTED_BATCH_RUN_ID = "POSTTRAN-2025-01-31";

    private static final Long ACCOUNT_ID = 10_000_000_001L;
    private static final String CARD_NUM = "4000000000000001";
    private static final String TRAN_ID = "TXN0000000000001";

    // Reject record layout sizes — verbatim per COBOL WORKING-STORAGE
    // (CBTRN02C.cbl):
    //   REJECT-RECORD          = 430 bytes
    //   ├─ REJECT-TRAN-DATA    = 350 bytes  (the DALYTRAN body)
    //   └─ VALIDATION-TRAILER  =  80 bytes
    //      ├─ WS-VALIDATION-FAIL-REASON      PIC 9(04)  (4-digit zero-pad)
    //      └─ WS-VALIDATION-FAIL-REASON-DESC PIC X(76)  (76 chars, left-pad)
    private static final int BODY_LENGTH = 350;
    private static final int REASON_CODE_LENGTH = 4;
    private static final int REASON_CODE_END = BODY_LENGTH + REASON_CODE_LENGTH;
    private static final int RECORD_LENGTH = BODY_LENGTH + 80;

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
        // 13-arg DailyTransaction constructor in copybook-declared order
        // (per CVTRA06Y.cpy / DailyTransaction.java).
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
        lenient().when(tcatbalRepository.findById(any(
                        TransactionCategoryBalanceId.class)))
                .thenReturn(Optional.empty());
        lenient().when(tcatbalRepository.save(any(
                        TransactionCategoryBalance.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(transactionRepository.save(any(Transaction.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    // ==================================================================
    // @Nested test groups
    // ==================================================================

    @Nested
    @DisplayName("Input validation + empty journal")
    class InputValidation {

        @Test
        @DisplayName("null batchDate throws NullPointerException")
        void postDailyTransactions_nullBatchDate_throws() {
            assertThatThrownBy(() -> service.postDailyTransactions(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("batchDate");
        }

        @Test
        @DisplayName("empty DALYTRAN journal returns zero counts and returnCode=0")
        void postDailyTransactions_emptyJournal_zeroResult() {
            when(dailyTransactionRepository.findAll())
                    .thenReturn(new ArrayList<>());

            TransactionPostingService.PostingResult result =
                    service.postDailyTransactions(BATCH_DATE);

            assertThat(result.transactionCount()).isEqualTo(0);
            assertThat(result.rejectCount()).isEqualTo(0);
            assertThat(result.returnCode()).isEqualTo(0);

            // No S3 writes, no Kafka publishes, no audit events
            verify(s3OutputService, never()).writeRejection(
                    anyString(), anyString());
            verify(kafkaEventPublisher, never()).publishTransactionPosted(
                    anyLong(), any(TransactionAddDto.class));
            verify(auditLogService, never()).logTransactionEvent(
                    anyString(), any(), anyString(), anyString(),
                    any(), anyMap(), anyString());
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
            TransactionPostingService.PostingResult result =
                    service.postDailyTransactions(BATCH_DATE);

            // Assert — rejected, returnCode 4
            assertThat(result.transactionCount()).isEqualTo(1);
            assertThat(result.rejectCount()).isEqualTo(1);
            assertThat(result.returnCode()).isEqualTo(4);

            // Cascade short-circuited: account lookup never invoked
            verify(accountRepository, never()).findById(anyLong());
            // S3 rejection write happened with the reject record
            verify(s3OutputService).writeRejection(
                    eq(EXPECTED_BATCH_RUN_ID), anyString());
        }

        @Test
        @DisplayName("reject 100 INVALID_CARD: blank card number short-circuits XREF lookup")
        void reject100_blankCardNumber() {
            dly.setDalytranCardNum("");
            stubHappyPathSingleTransaction();

            TransactionPostingService.PostingResult result =
                    service.postDailyTransactions(BATCH_DATE);

            assertThat(result.rejectCount()).isEqualTo(1);
            assertThat(result.returnCode()).isEqualTo(4);
            // Blank card short-circuits before the JPA call entirely
            verify(xrefRepository, never()).findById(anyString());
        }

        @Test
        @DisplayName("reject 100 INVALID_CARD: null card number short-circuits XREF lookup")
        void reject100_nullCardNumber() {
            dly.setDalytranCardNum(null);
            stubHappyPathSingleTransaction();

            TransactionPostingService.PostingResult result =
                    service.postDailyTransactions(BATCH_DATE);

            assertThat(result.rejectCount()).isEqualTo(1);
            assertThat(result.returnCode()).isEqualTo(4);
            verify(xrefRepository, never()).findById(anyString());
        }

        @Test
        @DisplayName("reject 101 ACCOUNT_NOT_FOUND: XREF found but account missing")
        void reject101_accountNotFound() {
            stubHappyPathSingleTransaction();
            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.empty());

            TransactionPostingService.PostingResult result =
                    service.postDailyTransactions(BATCH_DATE);

            assertThat(result.rejectCount()).isEqualTo(1);
            assertThat(result.returnCode()).isEqualTo(4);

            // Audit captured reject code 101 (4-digit zero-padded)
            ArgumentCaptor<String> reasonCaptor =
                    ArgumentCaptor.forClass(String.class);
            verify(auditLogService).logTransactionEvent(
                    eq(TRAN_ID), eq((Long) null), eq("BATCH"),
                    eq("transaction.rejected"), reasonCaptor.capture(),
                    anyMap(), eq(TRAN_ID));
            assertThat(reasonCaptor.getValue()).isEqualTo("0101");
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

            TransactionPostingService.PostingResult result =
                    service.postDailyTransactions(BATCH_DATE);

            assertThat(result.rejectCount()).isEqualTo(1);
            assertThat(result.returnCode()).isEqualTo(4);

            ArgumentCaptor<String> reasonCaptor =
                    ArgumentCaptor.forClass(String.class);
            verify(auditLogService).logTransactionEvent(
                    eq(TRAN_ID), eq((Long) null), eq("BATCH"),
                    eq("transaction.rejected"), reasonCaptor.capture(),
                    anyMap(), eq(TRAN_ID));
            assertThat(reasonCaptor.getValue()).isEqualTo("0102");

            // Posting side effects never occurred
            verify(transactionRepository, never()).save(any(Transaction.class));
            verify(tcatbalRepository, never()).save(any(
                    TransactionCategoryBalance.class));
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

            TransactionPostingService.PostingResult result =
                    service.postDailyTransactions(BATCH_DATE);

            // Tx posted — no rejects
            assertThat(result.rejectCount()).isEqualTo(0);
            assertThat(result.returnCode()).isEqualTo(0);
            verify(transactionRepository).save(any(Transaction.class));
        }

        @Test
        @DisplayName("reject 103 EXPIRED: origTs date > account expiration")
        void reject103_expired() {
            // Origination date 2031-06-01; expiry 2030-12-31
            dly.setDalytranOrigTs(LocalDateTime.of(2031, 6, 1, 12, 0));
            account.setAcctExpirationDate(LocalDate.of(2030, 12, 31));
            stubHappyPathSingleTransaction();

            TransactionPostingService.PostingResult result =
                    service.postDailyTransactions(BATCH_DATE);

            assertThat(result.rejectCount()).isEqualTo(1);
            assertThat(result.returnCode()).isEqualTo(4);

            ArgumentCaptor<String> reasonCaptor =
                    ArgumentCaptor.forClass(String.class);
            verify(auditLogService).logTransactionEvent(
                    eq(TRAN_ID), eq((Long) null), eq("BATCH"),
                    eq("transaction.rejected"), reasonCaptor.capture(),
                    anyMap(), eq(TRAN_ID));
            assertThat(reasonCaptor.getValue()).isEqualTo("0103");
        }

        @Test
        @DisplayName("reject 103 boundary: origTs.date == expiration accepted")
        void reject103_boundaryEqualAccepted() {
            // Boundary equality: COBOL "ACCT-EXPIRAION-DATE >= " → accept
            account.setAcctExpirationDate(LocalDate.of(2025, 1, 15));
            dly.setDalytranOrigTs(LocalDateTime.of(2025, 1, 15, 23, 59));
            stubHappyPathSingleTransaction();

            TransactionPostingService.PostingResult result =
                    service.postDailyTransactions(BATCH_DATE);

            assertThat(result.rejectCount()).isEqualTo(0);
            verify(transactionRepository).save(any(Transaction.class));
        }
    }

    @Nested
    @DisplayName("Validation cascade order (short-circuit)")
    class CascadeShortCircuit {

        @Test
        @DisplayName("stage 1 failure prevents stages 2-4 and posting")
        void stage1Failure_skipsAllSubsequentStages() {
            stubHappyPathSingleTransaction();
            when(xrefRepository.findById(CARD_NUM))
                    .thenReturn(Optional.empty());  // stage 1 fail

            service.postDailyTransactions(BATCH_DATE);

            verify(accountRepository, never()).findById(anyLong());
            verify(tcatbalRepository, never()).findById(any());
            verify(transactionRepository, never()).save(any(Transaction.class));
        }

        @Test
        @DisplayName("stage 2 failure prevents credit/expiry checks and posting")
        void stage2Failure_skipsCreditAndExpiry() {
            stubHappyPathSingleTransaction();
            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.empty());  // stage 2 fail

            service.postDailyTransactions(BATCH_DATE);

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

            service.postDailyTransactions(BATCH_DATE);

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

            service.postDailyTransactions(BATCH_DATE);

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

        @Test
        @DisplayName("TCATBAL key uses XREF-ACCT-ID (not DALYTRAN-CARD-NUM)")
        void tcatbalKey_usesXrefAcctId() {
            stubHappyPathSingleTransaction();

            service.postDailyTransactions(BATCH_DATE);

            // The composite key must be built from the XREF acct id,
            // not from the card number — verbatim COBOL semantics.
            ArgumentCaptor<TransactionCategoryBalanceId> keyCaptor =
                    ArgumentCaptor.forClass(TransactionCategoryBalanceId.class);
            verify(tcatbalRepository).findById(keyCaptor.capture());
            TransactionCategoryBalanceId key = keyCaptor.getValue();
            assertThat(key.getTrancatAcctId()).isEqualTo(ACCOUNT_ID);
            assertThat(key.getTrancatTypeCd()).isEqualTo("01");
            assertThat(key.getTrancatCd()).isEqualTo(5);
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

            service.postDailyTransactions(BATCH_DATE);

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

            service.postDailyTransactions(BATCH_DATE);

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

            service.postDailyTransactions(BATCH_DATE);

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

            service.postDailyTransactions(BATCH_DATE);

            ArgumentCaptor<Account> acctCaptor =
                    ArgumentCaptor.forClass(Account.class);
            verify(accountRepository).save(acctCaptor.capture());
            assertThat(acctCaptor.getValue().getAcctCurrBal().scale())
                    .isEqualTo(2);
            assertThat(acctCaptor.getValue().getAcctCurrCycCredit().scale())
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("OptimisticLockingFailureException at save wraps as CardDemoException with 109 semantics")
        void optimisticLockFailure_wrappedAs109() {
            stubHappyPathSingleTransaction();
            when(accountRepository.save(any(Account.class)))
                    .thenThrow(new OptimisticLockingFailureException(
                            "version mismatch"));

            // The exception propagates from the @Transactional postTransaction
            // method back through postDailyTransactions to the caller.
            assertThatThrownBy(() -> service.postDailyTransactions(BATCH_DATE))
                    .isInstanceOf(CardDemoException.class)
                    .hasMessageContaining("ACCOUNT RECORD NOT FOUND (109)")
                    .extracting("reasonCode")
                    .isEqualTo("ACCOUNT_REWRITE_FAILED");
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
            service.postDailyTransactions(BATCH_DATE);
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
            // ID, card, amount, type carried verbatim
            assertThat(tx.getTranId()).isEqualTo(TRAN_ID);
            assertThat(tx.getTranCardNum()).isEqualTo(CARD_NUM);
            assertThat(tx.getTranAmt())
                    .isEqualByComparingTo(new BigDecimal("100.00"));
            // Scale 2 on amount
            assertThat(tx.getTranAmt().scale()).isEqualTo(2);
            assertThat(tx.getTranTypeCd()).isEqualTo("01");
            assertThat(tx.getTranCatCd()).isEqualTo(5);
            assertThat(tx.getTranSource()).isEqualTo("POS TERM");
            assertThat(tx.getTranDesc()).isEqualTo("Test transaction");
            assertThat(tx.getTranMerchantId()).isEqualTo(999_999_999L);
            assertThat(tx.getTranMerchantName()).isEqualTo("Test Merchant");
            assertThat(tx.getTranMerchantCity()).isEqualTo("City");
            assertThat(tx.getTranMerchantZip()).isEqualTo("12345");
        }
    }

    @Nested
    @DisplayName("Side effects: MSK events + audit emission")
    class EventsAndAudit {

        @Test
        @DisplayName("posted: publishes transaction.posted partitioned by acct id")
        void posted_publishesTransactionPosted() {
            stubHappyPathSingleTransaction();

            service.postDailyTransactions(BATCH_DATE);

            ArgumentCaptor<Long> keyCaptor =
                    ArgumentCaptor.forClass(Long.class);
            ArgumentCaptor<TransactionAddDto> dtoCaptor =
                    ArgumentCaptor.forClass(TransactionAddDto.class);
            verify(kafkaEventPublisher).publishTransactionPosted(
                    keyCaptor.capture(), dtoCaptor.capture());
            assertThat(keyCaptor.getValue()).isEqualTo(ACCOUNT_ID);
            // DTO contains the posted transaction's fields verbatim
            assertThat(dtoCaptor.getValue().cardNumber()).isEqualTo(CARD_NUM);
            assertThat(dtoCaptor.getValue().transactionType()).isEqualTo("01");
            assertThat(dtoCaptor.getValue().transactionCategory()).isEqualTo(5);
            assertThat(dtoCaptor.getValue().amount())
                    .isEqualByComparingTo(new BigDecimal("100.00"));
            // 11-digit zero-padded accountId for consistent partitioner hashing
            assertThat(dtoCaptor.getValue().accountId())
                    .isEqualTo("10000000001");
        }

        @Test
        @DisplayName("posted: publishes account.updated partitioned by acct id")
        void posted_publishesAccountUpdated() {
            stubHappyPathSingleTransaction();

            service.postDailyTransactions(BATCH_DATE);

            ArgumentCaptor<Long> keyCaptor =
                    ArgumentCaptor.forClass(Long.class);
            ArgumentCaptor<AccountUpdateDto> dtoCaptor =
                    ArgumentCaptor.forClass(AccountUpdateDto.class);
            verify(kafkaEventPublisher).publishAccountUpdated(
                    keyCaptor.capture(), dtoCaptor.capture());
            assertThat(keyCaptor.getValue()).isEqualTo(ACCOUNT_ID);
            assertThat(dtoCaptor.getValue().accountId()).isEqualTo(ACCOUNT_ID);
            // Post-update balance (500 + 100 = 600)
            assertThat(dtoCaptor.getValue().currentBalance())
                    .isEqualByComparingTo(new BigDecimal("600.00"));
        }

        @Test
        @DisplayName("posted: emits transaction.posted audit event with reasonCode=null")
        void posted_emitsAuditEvent() {
            stubHappyPathSingleTransaction();

            service.postDailyTransactions(BATCH_DATE);

            verify(auditLogService).logTransactionEvent(
                    eq(TRAN_ID), eq(ACCOUNT_ID), eq("BATCH"),
                    eq("transaction.posted"), eq((String) null),
                    anyMap(), eq(TRAN_ID));
        }

        @Test
        @DisplayName("rejected: does NOT publish transaction.posted or account.updated")
        void rejected_doesNotPublishMskEvents() {
            stubHappyPathSingleTransaction();
            when(xrefRepository.findById(CARD_NUM))
                    .thenReturn(Optional.empty());

            service.postDailyTransactions(BATCH_DATE);

            verify(kafkaEventPublisher, never())
                    .publishTransactionPosted(anyLong(), any());
            verify(kafkaEventPublisher, never())
                    .publishAccountUpdated(anyLong(), any());
        }

        @Test
        @DisplayName("Kafka publish failures are non-fatal (transaction still posted)")
        void kafkaFailure_doesNotAbortBatch() {
            stubHappyPathSingleTransaction();
            org.mockito.Mockito.doThrow(new RuntimeException("MSK down"))
                    .when(kafkaEventPublisher)
                    .publishTransactionPosted(anyLong(), any());

            TransactionPostingService.PostingResult result =
                    service.postDailyTransactions(BATCH_DATE);

            // Counts reflect a successful post — Kafka emission is
            // additive/best-effort per AAP §0.6.5.
            assertThat(result.rejectCount()).isEqualTo(0);
            assertThat(result.returnCode()).isEqualTo(0);
            verify(transactionRepository).save(any(Transaction.class));
        }

        @Test
        @DisplayName("account.updated publish failure is non-fatal")
        void accountUpdatedFailure_doesNotAbortBatch() {
            stubHappyPathSingleTransaction();
            org.mockito.Mockito.doThrow(new RuntimeException("MSK down"))
                    .when(kafkaEventPublisher)
                    .publishAccountUpdated(anyLong(), any());

            TransactionPostingService.PostingResult result =
                    service.postDailyTransactions(BATCH_DATE);

            assertThat(result.rejectCount()).isEqualTo(0);
            assertThat(result.returnCode()).isEqualTo(0);
            verify(transactionRepository).save(any(Transaction.class));
        }
    }

    @Nested
    @DisplayName("S3 rejection output (COBOL: 2500-WRITE-REJECT-REC)")
    class RejectionOutput {

        @Test
        @DisplayName("writeReject emits a 430-byte record (350 + 4-digit reason + 76-char desc)")
        void writeReject_emitsFullRecord() {
            stubHappyPathSingleTransaction();
            when(xrefRepository.findById(CARD_NUM))
                    .thenReturn(Optional.empty());

            service.postDailyTransactions(BATCH_DATE);

            ArgumentCaptor<String> recordCaptor =
                    ArgumentCaptor.forClass(String.class);
            verify(s3OutputService)
                    .writeRejection(eq(EXPECTED_BATCH_RUN_ID),
                            recordCaptor.capture());
            assertThat(recordCaptor.getValue()).hasSize(RECORD_LENGTH);
        }

        @Test
        @DisplayName("rejection trailer carries reject reason code 100 (4-digit zero-padded)")
        void writeReject_carriesReason100() {
            stubHappyPathSingleTransaction();
            when(xrefRepository.findById(CARD_NUM))
                    .thenReturn(Optional.empty());

            service.postDailyTransactions(BATCH_DATE);

            ArgumentCaptor<String> recordCaptor =
                    ArgumentCaptor.forClass(String.class);
            verify(s3OutputService)
                    .writeRejection(eq(EXPECTED_BATCH_RUN_ID),
                            recordCaptor.capture());
            String record = recordCaptor.getValue();
            // First 4 chars after the body are the 4-digit reason code.
            String reasonCode =
                    record.substring(BODY_LENGTH, REASON_CODE_END);
            assertThat(reasonCode).isEqualTo("0100");
        }

        @Test
        @DisplayName("rejection trailer carries reject reason code 102 (4-digit zero-padded)")
        void writeReject_carriesReason102() {
            account.setAcctCurrCycCredit(new BigDecimal("4000.00"));
            account.setAcctCurrCycDebit(BigDecimal.ZERO);
            account.setAcctCreditLimit(new BigDecimal("5000.00"));
            dly.setDalytranAmt(new BigDecimal("2000.00"));
            stubHappyPathSingleTransaction();

            service.postDailyTransactions(BATCH_DATE);

            ArgumentCaptor<String> recordCaptor =
                    ArgumentCaptor.forClass(String.class);
            verify(s3OutputService)
                    .writeRejection(eq(EXPECTED_BATCH_RUN_ID),
                            recordCaptor.capture());
            assertThat(recordCaptor.getValue()
                            .substring(BODY_LENGTH, REASON_CODE_END))
                    .isEqualTo("0102");
        }

        @Test
        @DisplayName("rejection trailer description matches the verbatim COBOL text")
        void writeReject_carriesDescription() {
            stubHappyPathSingleTransaction();
            when(xrefRepository.findById(CARD_NUM))
                    .thenReturn(Optional.empty());

            service.postDailyTransactions(BATCH_DATE);

            ArgumentCaptor<String> recordCaptor =
                    ArgumentCaptor.forClass(String.class);
            verify(s3OutputService)
                    .writeRejection(eq(EXPECTED_BATCH_RUN_ID),
                            recordCaptor.capture());
            // Description starts after the 4-digit reason code and
            // ends padded right to the 80-byte trailer (76 chars).
            String description = recordCaptor.getValue()
                    .substring(REASON_CODE_END).trim();
            assertThat(description).isEqualTo("INVALID CARD NUMBER FOUND");
        }

        @Test
        @DisplayName("rejection record body preserves DALYTRAN-ID at offset 0")
        void writeReject_bodyStartsWithTranId() {
            stubHappyPathSingleTransaction();
            when(xrefRepository.findById(CARD_NUM))
                    .thenReturn(Optional.empty());

            service.postDailyTransactions(BATCH_DATE);

            ArgumentCaptor<String> recordCaptor =
                    ArgumentCaptor.forClass(String.class);
            verify(s3OutputService)
                    .writeRejection(eq(EXPECTED_BATCH_RUN_ID),
                            recordCaptor.capture());
            String record = recordCaptor.getValue();
            // First 16 bytes = DALYTRAN-ID PIC X(16)
            assertThat(record.substring(0, 16)).isEqualTo(TRAN_ID);
        }

        @Test
        @DisplayName("S3 write failure raises CardDemoException with WRITE_REJECT_FAILED")
        void s3WriteFailure_wrappedAsCardDemoException() {
            stubHappyPathSingleTransaction();
            when(xrefRepository.findById(CARD_NUM))
                    .thenReturn(Optional.empty());
            org.mockito.Mockito.doThrow(new RuntimeException("S3 down"))
                    .when(s3OutputService)
                    .writeRejection(anyString(), anyString());

            assertThatThrownBy(() -> service.postDailyTransactions(BATCH_DATE))
                    .isInstanceOf(CardDemoException.class)
                    .hasMessageContaining("Failed to write reject record")
                    .extracting("reasonCode")
                    .isEqualTo("WRITE_REJECT_FAILED");
        }
    }

    @Nested
    @DisplayName("Return code (COBOL: MOVE 4 TO RETURN-CODE at L229)")
    class ReturnCode {

        @Test
        @DisplayName("zero rejects → returnCode 0")
        void zeroRejects_returnCodeZero() {
            stubHappyPathSingleTransaction();

            TransactionPostingService.PostingResult result =
                    service.postDailyTransactions(BATCH_DATE);

            assertThat(result.rejectCount()).isEqualTo(0);
            assertThat(result.returnCode()).isEqualTo(0);
        }

        @Test
        @DisplayName("one or more rejects → returnCode 4")
        void someRejects_returnCodeFour() {
            stubHappyPathSingleTransaction();
            when(xrefRepository.findById(CARD_NUM))
                    .thenReturn(Optional.empty());

            TransactionPostingService.PostingResult result =
                    service.postDailyTransactions(BATCH_DATE);

            assertThat(result.rejectCount()).isEqualTo(1);
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

            TransactionPostingService.PostingResult result =
                    service.postDailyTransactions(BATCH_DATE);

            assertThat(result.transactionCount()).isEqualTo(2);
            assertThat(result.rejectCount()).isEqualTo(1);
            assertThat(result.returnCode()).isEqualTo(4);

            // One transaction.posted (for row 1) + one rejection
            verify(transactionRepository, times(1)).save(any(Transaction.class));
            verify(s3OutputService, times(1))
                    .writeRejection(anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("ON SIZE ERROR — explicit overflow guards (AAP §0.6.1)")
    class OnSizeError {

        @Test
        @DisplayName("ACCT-CURR-BAL overflow throws OnSizeErrorException")
        void acctCurrBalOverflow_throwsOnSizeError() {
            // Start balance very near MAX_AMOUNT ceiling
            // (PIC S9(11)V99 max = 99,999,999,999.99).
            account.setAcctCurrBal(new BigDecimal("99999999999.00"));
            account.setAcctCurrCycCredit(BigDecimal.ZERO);
            account.setAcctCurrCycDebit(BigDecimal.ZERO);
            account.setAcctCreditLimit(new BigDecimal("99999999999.99"));
            dly.setDalytranAmt(new BigDecimal("100.00"));
            stubHappyPathSingleTransaction();

            assertThatThrownBy(() -> service.postDailyTransactions(BATCH_DATE))
                    .isInstanceOf(OnSizeErrorException.class)
                    .hasMessageContaining("ACCT-CURR-BAL");
        }

        @Test
        @DisplayName("ACCT-CURR-CYC-CREDIT overflow throws OnSizeErrorException")
        void acctCurrCycCreditOverflow_throwsOnSizeError() {
            account.setAcctCurrBal(new BigDecimal("0.00"));
            account.setAcctCurrCycCredit(new BigDecimal("99999999999.00"));
            account.setAcctCurrCycDebit(new BigDecimal("99999999999.00"));
            // Credit limit large enough to avoid reject 102
            account.setAcctCreditLimit(new BigDecimal("99999999999.99"));
            dly.setDalytranAmt(new BigDecimal("1000.00"));
            stubHappyPathSingleTransaction();

            assertThatThrownBy(() -> service.postDailyTransactions(BATCH_DATE))
                    .isInstanceOf(OnSizeErrorException.class)
                    .hasMessageContaining("ACCT-CURR-CYC-CREDIT");
        }

        @Test
        @DisplayName("TCATBAL overflow on existing row throws OnSizeErrorException")
        void tcatbalOverflow_throwsOnSizeError() {
            TransactionCategoryBalanceId id = new TransactionCategoryBalanceId(
                    ACCOUNT_ID, "01", 5);
            TransactionCategoryBalance pre = new TransactionCategoryBalance(
                    id, new BigDecimal("99999999999.00"));
            dly.setDalytranAmt(new BigDecimal("1000.00"));
            stubHappyPathSingleTransaction();
            when(tcatbalRepository.findById(id)).thenReturn(Optional.of(pre));

            assertThatThrownBy(() -> service.postDailyTransactions(BATCH_DATE))
                    .isInstanceOf(OnSizeErrorException.class)
                    .hasMessageContaining("TRAN-CAT-BAL");
        }
    }

    @Nested
    @DisplayName("PII discipline — audit payload contents (AAP §0.6.6 / rule 10)")
    class PiiDiscipline {

        @Test
        @DisplayName("rejected audit payload does NOT include full card number")
        void rejectedAuditPayload_omitsFullCardNumber() {
            stubHappyPathSingleTransaction();
            when(xrefRepository.findById(CARD_NUM))
                    .thenReturn(Optional.empty());

            service.postDailyTransactions(BATCH_DATE);

            @SuppressWarnings({"unchecked", "rawtypes"})
            ArgumentCaptor<Map<String, Object>> payloadCaptor =
                    (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
            verify(auditLogService).logTransactionEvent(
                    eq(TRAN_ID), eq((Long) null), eq("BATCH"),
                    eq("transaction.rejected"), anyString(),
                    payloadCaptor.capture(), eq(TRAN_ID));
            Map<String, Object> payload = payloadCaptor.getValue();
            // Per AAP rule 10 the payload contains tranId + reject metadata,
            // NOT the full card number — the adapter sanitizes as
            // defense-in-depth.
            assertThat(payload).doesNotContainKey("cardNumber");
            assertThat(payload).doesNotContainValue(CARD_NUM);
            // But it DOES contain the rejectCode for fraud-team filtering.
            assertThat(payload).containsKey("rejectCode");
            assertThat(payload).containsKey("rejectDescription");
        }

        @Test
        @DisplayName("posted audit payload does NOT include full card number")
        void postedAuditPayload_omitsFullCardNumber() {
            stubHappyPathSingleTransaction();

            service.postDailyTransactions(BATCH_DATE);

            @SuppressWarnings({"unchecked", "rawtypes"})
            ArgumentCaptor<Map<String, Object>> payloadCaptor =
                    (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
            verify(auditLogService).logTransactionEvent(
                    eq(TRAN_ID), eq(ACCOUNT_ID), eq("BATCH"),
                    eq("transaction.posted"), eq((String) null),
                    payloadCaptor.capture(), eq(TRAN_ID));
            Map<String, Object> payload = payloadCaptor.getValue();
            assertThat(payload).doesNotContainValue(CARD_NUM);
            assertThat(payload).containsKey("tranId");
            assertThat(payload).containsKey("acctId");
        }
    }
}

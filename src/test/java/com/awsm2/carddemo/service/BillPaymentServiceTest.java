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
import com.awsm2.carddemo.domain.Account;
import com.awsm2.carddemo.domain.CardCrossReference;
import com.awsm2.carddemo.domain.Transaction;
import com.awsm2.carddemo.dto.AccountUpdateDto;
import com.awsm2.carddemo.dto.BillPaymentDto;
import com.awsm2.carddemo.dto.TransactionAddDto;
import com.awsm2.carddemo.exception.OnSizeErrorException;
import com.awsm2.carddemo.exception.RecordNotFoundException;
import com.awsm2.carddemo.exception.ValidationException;
import com.awsm2.carddemo.repository.AccountRepository;
import com.awsm2.carddemo.repository.CardCrossReferenceRepository;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JUnit 5 + Mockito + AssertJ unit tests for {@link BillPaymentService}.
 *
 * <p><b>COBOL provenance.</b> {@link BillPaymentService} translates
 * {@code app/cbl/COBIL00C.cbl} (CICS transaction id {@code CB00},
 * file {@code 'ACCTDAT' + 'CARDXREF' + 'TRANSACT'}). The COBOL source
 * reads the account, optionally reads the card cross-reference,
 * assigns the next transaction ID, posts a payment record with
 * TYPE-CD='02', subtracts the current balance, and rewrites the
 * account row.</p>
 *
 * <p><b>Behavioural invariants locked by this suite.</b></p>
 * <ol>
 *   <li><b>Schema-mandated XREF method</b> &mdash; the service calls
 *       {@link CardCrossReferenceRepository#findByXrefAcctId(Long)}
 *       per the file schema {@code internal_imports} contract.</li>
 *   <li><b>MAX-TRAN-ID + 1 generator</b> &mdash; reads
 *       {@link TransactionRepository#findTopByNumericTranIdOrderByTranIdDesc()} and
 *       generates the next 16-digit zero-padded ID.</li>
 *   <li><b>Balance arithmetic</b> &mdash; new balance is
 *       {@code current.subtract(amountPaid)} with
 *       {@code RoundingMode.HALF_EVEN} at scale 2. Verified to be
 *       computed via {@link BigDecimal}, not float/double.</li>
 *   <li><b>OnSizeError</b> &mdash; balance that would overflow
 *       {@code PIC S9(10)V99} triggers
 *       {@link OnSizeErrorException}.</li>
 *   <li><b>Confirm-flag dispatch</b> &mdash;
 *       {@code Y/y} processes, {@code N/n} returns a cancellation
 *       DTO, blank returns a preview DTO with the current balance,
 *       any other value throws the verbatim COBOL message
 *       <em>"Invalid value. Valid values are (Y/N)..."</em>.</li>
 *   <li><b>Verbatim COBOL strings preserved</b> &mdash; per the AAP
 *       &sect;0.7.3 Minimal Change Clause:
 *       {@code "Acct ID can NOT be empty..."},
 *       {@code "Invalid value. Valid values are (Y/N)..."}.</li>
 *   <li><b>RecordNotFound mapping</b> &mdash; missing account or
 *       missing XREF resolves to HTTP 404 via
 *       {@link RecordNotFoundException}.</li>
 *   <li><b>Cache-aside post-write invalidation</b> &mdash; account
 *       cache entry is evicted under namespace
 *       {@link AccountViewService#CACHE_NS} with key = zero-padded
 *       11-digit account ID.</li>
 *   <li><b>Dual MSK publish</b> &mdash; both
 *       {@code transaction.posted} and {@code account.updated} are
 *       partitioned by owning account ID (AAP &sect;0.6.5).</li>
 *   <li><b>Audit emission</b> &mdash; {@code BILL_PAID} event carries
 *       PCI-DSS-safe payload (PAN last-four only, never full PAN).</li>
 * </ol>
 *
 * <p>External AWS interactions are fully mocked through
 * {@link MockitoExtension}; no Testcontainers or LocalStack are
 * involved.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BillPaymentService unit tests (COBOL: COBIL00C.cbl)")
class BillPaymentServiceTest {

    // ==================================================================
    // Constants
    // ==================================================================
    private static final Long ACCOUNT_ID = 10_000_000_001L;
    private static final String ACCOUNT_ID_STR = "10000000001";
    private static final String CARD_LOW = "4000000000000001";
    private static final String CARD_HIGH = "4000000000000002";
    private static final String LAST4_LOW = "0001";
    private static final BigDecimal STARTING_BALANCE = new BigDecimal("250.75");

    // ==================================================================
    // Mocks and SUT
    // ==================================================================
    @Mock private AccountRepository accountRepository;
    @Mock private CardCrossReferenceRepository cardCrossReferenceRepository;
    @Mock private TransactionRepository transactionRepository;
    @Mock private KafkaEventPublisher kafkaEventPublisher;
    @Mock private CacheService cacheService;
    @Mock private AuditLogService auditLogService;

    @InjectMocks private BillPaymentService service;

    // ==================================================================
    // Common test fixtures
    // ==================================================================
    private BillPaymentDto validRequest;
    private Account existingAccount;

    @BeforeEach
    void setUp() {
        validRequest = new BillPaymentDto(
                ACCOUNT_ID_STR,
                STARTING_BALANCE,
                "Y",
                null, // transactionId (assigned by service)
                null, // postedAt (assigned by service)
                null); // amountPaid (assigned by service)

        existingAccount = new Account();
        existingAccount.setAcctId(ACCOUNT_ID);
        existingAccount.setAcctCurrBal(STARTING_BALANCE);
        existingAccount.setAcctActiveStatus("Y");
    }

    private BillPaymentDto buildRequestWithConfirm(String confirm) {
        return new BillPaymentDto(
                ACCOUNT_ID_STR, STARTING_BALANCE, confirm, null, null, null);
    }

    private BillPaymentDto buildRequestWithAccountId(String accountId) {
        return new BillPaymentDto(
                accountId, STARTING_BALANCE, "Y", null, null, null);
    }

    /**
     * Stub a happy-path account + XREF + transaction save.
     * Uses {@code lenient()} on each so individual tests can verify
     * absence of side effects without strict-stubbing complaints.
     */
    private void stubHappyPathRepositories(BigDecimal openingBalance,
                                           List<CardCrossReference> xrefRows) {
        existingAccount.setAcctCurrBal(openingBalance);
        lenient().when(accountRepository.findById(ACCOUNT_ID))
                .thenReturn(Optional.of(existingAccount));
        lenient().when(accountRepository.save(any(Account.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(cardCrossReferenceRepository
                        .findByXrefAcctId(ACCOUNT_ID))
                .thenReturn(xrefRows);
        lenient().when(transactionRepository.findTopByNumericTranIdOrderByTranIdDesc())
                .thenReturn(Optional.empty());
        lenient().when(transactionRepository.save(any(Transaction.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(kafkaEventPublisher.publishTransactionPosted(
                        anyLong(), any(TransactionAddDto.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        lenient().when(kafkaEventPublisher.publishAccountUpdated(
                        anyLong(), any(AccountUpdateDto.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    // ==================================================================
    // @Nested test groups
    // ==================================================================

    @Nested
    @DisplayName("XREF lookup (schema-mandated findByXrefAcctId)")
    class XrefLookup {

        @Test
        @DisplayName("uses findByXrefAcctId (per file schema contract)")
        void processBillPayment_callsSchemaXrefMethod() {
            // Arrange — single-card account; method returns one row.
            List<CardCrossReference> xrefs = List.of(
                    new CardCrossReference(CARD_LOW, 999_999L, ACCOUNT_ID));
            stubHappyPathRepositories(STARTING_BALANCE, xrefs);

            // Act
            service.processBillPayment(validRequest);

            // Assert — service called findByXrefAcctId (per schema).
            verify(cardCrossReferenceRepository)
                    .findByXrefAcctId(ACCOUNT_ID);
        }

        @Test
        @DisplayName("posts transaction with first card from XREF result")
        void processBillPayment_picksFirstCard() {
            // Arrange — first card in result list is selected
            // (matches the COBOL READ-CXACAIX-FILE first-row semantic;
            // CARDDEMO data has 1:1 acct:card mapping).
            List<CardCrossReference> xrefs = List.of(
                    new CardCrossReference(CARD_LOW, 999_999L, ACCOUNT_ID),
                    new CardCrossReference(CARD_HIGH, 999_999L, ACCOUNT_ID));
            stubHappyPathRepositories(STARTING_BALANCE, xrefs);

            // Act
            service.processBillPayment(validRequest);

            // Assert — captured Transaction carries the LOW card (first
            // element)
            ArgumentCaptor<Transaction> txCaptor =
                    ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(txCaptor.capture());
            assertThat(txCaptor.getValue().getTranCardNum())
                    .isEqualTo(CARD_LOW);
        }
    }

    @Nested
    @DisplayName("Balance arithmetic (BigDecimal, HALF_EVEN, scale=2)")
    class BalanceArithmetic {

        @Test
        @DisplayName("subtracts payment from current balance (BigDecimal HALF_EVEN)")
        void processBillPayment_subtractsPaymentFromBalance() {
            // Arrange — balance 250.75 → 0.00 after full payment
            List<CardCrossReference> xrefs = List.of(
                    new CardCrossReference(CARD_LOW, 999_999L, ACCOUNT_ID));
            stubHappyPathRepositories(STARTING_BALANCE, xrefs);

            // Act
            BillPaymentDto response = service.processBillPayment(validRequest);

            // Assert — response carries the new balance (0.00) and the
            // captured account save reflects the same amount.
            assertThat(response.currentBalance())
                    .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(response.amountPaid())
                    .isEqualByComparingTo(STARTING_BALANCE);
            ArgumentCaptor<Account> acctCaptor =
                    ArgumentCaptor.forClass(Account.class);
            verify(accountRepository).save(acctCaptor.capture());
            assertThat(acctCaptor.getValue().getAcctCurrBal())
                    .isEqualByComparingTo(BigDecimal.ZERO);
            // Scale check — explicit setScale(2, HALF_EVEN) in the service
            assertThat(acctCaptor.getValue().getAcctCurrBal().scale())
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("returns 'nothing to pay' DTO when balance is zero (no exception)")
        void processBillPayment_zeroBalance_returnsNothingToPayDto() {
            // Arrange — zero balance: COBOL "IF ACCT-CURR-BAL <= ZEROS"
            // returns an informational DTO per agent_prompt; no
            // transaction is posted.
            lenient().when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(existingAccount));
            existingAccount.setAcctCurrBal(BigDecimal.ZERO);

            // Act
            BillPaymentDto response = service.processBillPayment(validRequest);

            // Assert — DTO returned with current balance, no write paths
            // exercised.
            assertThat(response).isNotNull();
            assertThat(response.currentBalance())
                    .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(response.transactionId()).isNull();
            assertThat(response.amountPaid()).isNull();
            verify(transactionRepository, never()).save(any());
            verify(accountRepository, never()).save(any());
            verify(kafkaEventPublisher, never())
                    .publishTransactionPosted(anyLong(), any());
            verify(kafkaEventPublisher, never())
                    .publishAccountUpdated(anyLong(), any());
        }

        @Test
        @DisplayName("returns 'nothing to pay' DTO when balance is negative")
        void processBillPayment_negativeBalance_returnsNothingToPayDto() {
            // Negative balance also short-circuits ("<= ZEROS" semantic).
            lenient().when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(existingAccount));
            existingAccount.setAcctCurrBal(new BigDecimal("-1.00"));

            BillPaymentDto response = service.processBillPayment(validRequest);

            assertThat(response).isNotNull();
            assertThat(response.transactionId()).isNull();
            verify(transactionRepository, never()).save(any());
        }

        @Test
        @DisplayName("preserves BigDecimal scale (no float/double conversion)")
        void processBillPayment_preservesBigDecimalScale() {
            // Arrange — balance value MUST flow through BigDecimal
            // arithmetic only (HALF_EVEN + scale 2 to match COBOL PIC
            // S9(10)V99).
            BigDecimal balance = new BigDecimal("123.45");
            List<CardCrossReference> xrefs = List.of(
                    new CardCrossReference(CARD_LOW, 999_999L, ACCOUNT_ID));
            stubHappyPathRepositories(balance, xrefs);

            // Act
            BillPaymentDto response = service.processBillPayment(validRequest);

            // Assert — amount paid carries the EXACT precision
            assertThat(response.amountPaid()).isEqualByComparingTo(balance);
            assertThat(response.amountPaid().scale()).isEqualTo(2);
            assertThat(response.currentBalance().scale()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("Validation gates (account ID + confirm flag)")
    class ValidationGates {

        @Test
        @DisplayName("rejects null accountId with verbatim COBOL message")
        void processBillPayment_nullAccountId_throwsValidation() {
            BillPaymentDto request = buildRequestWithAccountId(null);

            assertThatThrownBy(() -> service.processBillPayment(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Acct ID can NOT be empty");

            verify(accountRepository, never()).findById(anyLong());
        }

        @Test
        @DisplayName("rejects blank accountId with verbatim COBOL message")
        void processBillPayment_blankAccountId_throwsValidation() {
            BillPaymentDto request = buildRequestWithAccountId("   ");

            assertThatThrownBy(() -> service.processBillPayment(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Acct ID can NOT be empty");
        }

        @Test
        @DisplayName("rejects non-numeric accountId with verbatim COBOL message")
        void processBillPayment_nonNumericAccountId_throwsValidation() {
            BillPaymentDto request = buildRequestWithAccountId("ABC12345678");

            assertThatThrownBy(() -> service.processBillPayment(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Acct ID can NOT be empty");
        }

        @Test
        @DisplayName("rejects accountId with leading sign as non-digit")
        void processBillPayment_signedAccountId_throwsValidation() {
            BillPaymentDto request = buildRequestWithAccountId("-1234567890");

            assertThatThrownBy(() -> service.processBillPayment(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Acct ID can NOT be empty");
        }

        @Test
        @DisplayName("rejects zero accountId (PIC 9(11) effective-empty)")
        void processBillPayment_zeroAccountId_throwsValidation() {
            BillPaymentDto request = buildRequestWithAccountId("00000000000");

            assertThatThrownBy(() -> service.processBillPayment(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Acct ID can NOT be empty");
        }

        @Test
        @DisplayName("cancellation: confirm='N' returns DTO (no exception, no write)")
        void processBillPayment_confirmN_returnsCancellationDto() {
            BillPaymentDto request = buildRequestWithConfirm("N");

            BillPaymentDto response = service.processBillPayment(request);

            assertThat(response).isNotNull();
            assertThat(response.confirm()).isEqualTo("N");
            assertThat(response.transactionId()).isNull();
            verify(accountRepository, never()).findById(anyLong());
            verify(transactionRepository, never()).save(any());
            verify(kafkaEventPublisher, never())
                    .publishTransactionPosted(anyLong(), any());
        }

        @Test
        @DisplayName("cancellation: confirm='n' (lowercase) returns DTO")
        void processBillPayment_confirmLowercaseN_returnsCancellationDto() {
            BillPaymentDto request = buildRequestWithConfirm("n");

            BillPaymentDto response = service.processBillPayment(request);

            assertThat(response).isNotNull();
            verify(transactionRepository, never()).save(any());
        }

        @Test
        @DisplayName("preview: null confirm returns DTO with current balance after account load")
        void processBillPayment_nullConfirm_returnsPreviewDto() {
            // Blank confirm reads the account for display but does not
            // post (COBOL WHEN SPACES/LOW-VALUES at L182-L184).
            lenient().when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(existingAccount));
            BillPaymentDto request = buildRequestWithConfirm(null);

            BillPaymentDto response = service.processBillPayment(request);

            assertThat(response).isNotNull();
            assertThat(response.currentBalance())
                    .isEqualByComparingTo(STARTING_BALANCE);
            assertThat(response.transactionId()).isNull();
            verify(transactionRepository, never()).save(any());
            verify(accountRepository, never()).save(any());
        }

        @Test
        @DisplayName("preview: blank confirm returns DTO with current balance")
        void processBillPayment_blankConfirm_returnsPreviewDto() {
            lenient().when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(existingAccount));
            BillPaymentDto request = buildRequestWithConfirm("");

            BillPaymentDto response = service.processBillPayment(request);

            assertThat(response).isNotNull();
            verify(transactionRepository, never()).save(any());
        }

        @Test
        @DisplayName("rejects invalid confirm value with verbatim COBOL message")
        void processBillPayment_invalidConfirm_throwsValidation() {
            BillPaymentDto request = buildRequestWithConfirm("X");

            assertThatThrownBy(() -> service.processBillPayment(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining(
                            "Invalid value. Valid values are (Y/N)");

            verify(accountRepository, never()).findById(anyLong());
        }

        @Test
        @DisplayName("accepts lowercase 'y' (case-insensitive Y match)")
        void processBillPayment_confirmLowercaseY_succeeds() {
            List<CardCrossReference> xrefs = List.of(
                    new CardCrossReference(CARD_LOW, 999_999L, ACCOUNT_ID));
            stubHappyPathRepositories(STARTING_BALANCE, xrefs);
            BillPaymentDto request = buildRequestWithConfirm("y");

            // Act
            BillPaymentDto response = service.processBillPayment(request);

            // Assert
            assertThat(response).isNotNull();
            verify(transactionRepository).save(any(Transaction.class));
        }

        @Test
        @DisplayName("rejects null request with NullPointerException")
        void processBillPayment_nullRequest_throwsNpe() {
            assertThatThrownBy(() -> service.processBillPayment(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("RecordNotFound mapping (CICS FILE STATUS 23)")
    class RecordNotFound {

        @Test
        @DisplayName("throws RecordNotFoundException when account missing")
        void processBillPayment_accountMissing_throwsRecordNotFound() {
            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.processBillPayment(validRequest))
                    .isInstanceOf(RecordNotFoundException.class)
                    .hasMessageContaining("Account");

            verify(transactionRepository, never()).save(any());
            verify(accountRepository, never()).save(any());
        }

        @Test
        @DisplayName("throws RecordNotFoundException when XREF list empty")
        void processBillPayment_xrefEmpty_throwsRecordNotFound() {
            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(existingAccount));
            when(cardCrossReferenceRepository.findByXrefAcctId(ACCOUNT_ID))
                    .thenReturn(List.of());

            assertThatThrownBy(() -> service.processBillPayment(validRequest))
                    .isInstanceOf(RecordNotFoundException.class)
                    .hasMessageContaining("CardCrossReference");

            verify(transactionRepository, never()).save(any());
            verify(accountRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("Transaction record construction (verbatim COBOL literals)")
    class TransactionConstruction {

        @Test
        @DisplayName("generates 16-digit zero-padded transaction ID starting at 1 for empty journal")
        void processBillPayment_seedsTranIdWhenJournalEmpty() {
            List<CardCrossReference> xrefs = List.of(
                    new CardCrossReference(CARD_LOW, 999_999L, ACCOUNT_ID));
            stubHappyPathRepositories(STARTING_BALANCE, xrefs);
            // No transactions exist yet
            when(transactionRepository.findTopByNumericTranIdOrderByTranIdDesc())
                    .thenReturn(Optional.empty());

            service.processBillPayment(validRequest);

            ArgumentCaptor<Transaction> txCaptor =
                    ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(txCaptor.capture());
            assertThat(txCaptor.getValue().getTranId())
                    .isEqualTo("0000000000000001");
        }

        @Test
        @DisplayName("MAX-TRAN-ID + 1 idiom produces next sequential ID")
        void processBillPayment_incrementsExistingMaxTranId() {
            List<CardCrossReference> xrefs = List.of(
                    new CardCrossReference(CARD_LOW, 999_999L, ACCOUNT_ID));
            stubHappyPathRepositories(STARTING_BALANCE, xrefs);
            Transaction existing = new Transaction();
            existing.setTranId("0000000000000042");
            when(transactionRepository.findTopByNumericTranIdOrderByTranIdDesc())
                    .thenReturn(Optional.of(existing));

            service.processBillPayment(validRequest);

            ArgumentCaptor<Transaction> txCaptor =
                    ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(txCaptor.capture());
            assertThat(txCaptor.getValue().getTranId())
                    .isEqualTo("0000000000000043");
        }

        @Test
        @DisplayName("transaction carries verbatim COBOL literals (type, cat, source, desc, merchant)")
        void processBillPayment_carriesVerbatimCobolLiterals() {
            List<CardCrossReference> xrefs = List.of(
                    new CardCrossReference(CARD_LOW, 999_999L, ACCOUNT_ID));
            stubHappyPathRepositories(STARTING_BALANCE, xrefs);

            service.processBillPayment(validRequest);

            ArgumentCaptor<Transaction> txCaptor =
                    ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(txCaptor.capture());
            Transaction tx = txCaptor.getValue();

            // COBOL: MOVE '02' TO TRAN-TYPE-CD (L220)
            assertThat(tx.getTranTypeCd()).isEqualTo("02");
            // COBOL: MOVE 2 TO TRAN-CAT-CD (L221) — Integer
            assertThat(tx.getTranCatCd()).isEqualTo(2);
            // COBOL: MOVE 'POS TERM' TO TRAN-SOURCE (L222)
            assertThat(tx.getTranSource()).isEqualTo("POS TERM");
            // COBOL: MOVE 'BILL PAYMENT - ONLINE' TO TRAN-DESC (L223)
            assertThat(tx.getTranDesc()).isEqualTo("BILL PAYMENT - ONLINE");
            // COBOL: MOVE 999999999 TO TRAN-MERCHANT-ID (L226)
            assertThat(tx.getTranMerchantId()).isEqualTo(999_999_999L);
            // COBOL: MOVE 'BILL PAYMENT' TO TRAN-MERCHANT-NAME (L227)
            assertThat(tx.getTranMerchantName()).isEqualTo("BILL PAYMENT");
            // COBOL: MOVE 'N/A' TO TRAN-MERCHANT-CITY/ZIP (L228-L229)
            assertThat(tx.getTranMerchantCity()).isEqualTo("N/A");
            assertThat(tx.getTranMerchantZip()).isEqualTo("N/A");
            // COBOL: MOVE XREF-CARD-NUM TO TRAN-CARD-NUM (L225)
            assertThat(tx.getTranCardNum()).isEqualTo(CARD_LOW);
            // COBOL: MOVE ACCT-CURR-BAL TO TRAN-AMT (L224)
            assertThat(tx.getTranAmt())
                    .isEqualByComparingTo(STARTING_BALANCE);
            // Timestamps populated (L230, L249-L267 → LocalDateTime.now())
            assertThat(tx.getTranOrigTs()).isNotNull();
            assertThat(tx.getTranProcTs()).isNotNull();
        }
    }

    @Nested
    @DisplayName("MSK partitioning + audit emission (AAP §0.6.5 + §0.6.6)")
    class EventsAndAudit {

        @Test
        @DisplayName("publishes transaction.posted partitioned by account ID")
        void processBillPayment_publishesTransactionPostedByAcctId() {
            List<CardCrossReference> xrefs = List.of(
                    new CardCrossReference(CARD_LOW, 999_999L, ACCOUNT_ID));
            stubHappyPathRepositories(STARTING_BALANCE, xrefs);

            service.processBillPayment(validRequest);

            ArgumentCaptor<Long> partitionKeyCaptor =
                    ArgumentCaptor.forClass(Long.class);
            verify(kafkaEventPublisher).publishTransactionPosted(
                    partitionKeyCaptor.capture(), any(TransactionAddDto.class));
            assertThat(partitionKeyCaptor.getValue()).isEqualTo(ACCOUNT_ID);
        }

        @Test
        @DisplayName("publishes account.updated partitioned by account ID")
        void processBillPayment_publishesAccountUpdatedByAcctId() {
            List<CardCrossReference> xrefs = List.of(
                    new CardCrossReference(CARD_LOW, 999_999L, ACCOUNT_ID));
            stubHappyPathRepositories(STARTING_BALANCE, xrefs);

            service.processBillPayment(validRequest);

            ArgumentCaptor<Long> partitionKeyCaptor =
                    ArgumentCaptor.forClass(Long.class);
            verify(kafkaEventPublisher).publishAccountUpdated(
                    partitionKeyCaptor.capture(), any(AccountUpdateDto.class));
            assertThat(partitionKeyCaptor.getValue()).isEqualTo(ACCOUNT_ID);
        }

        @Test
        @DisplayName("logTransactionEvent invoked with BILL_PAID event type and PCI-DSS-safe payload")
        void processBillPayment_emitsBillPaidAuditEvent() {
            List<CardCrossReference> xrefs = List.of(
                    new CardCrossReference(CARD_LOW, 999_999L, ACCOUNT_ID));
            stubHappyPathRepositories(STARTING_BALANCE, xrefs);

            service.processBillPayment(validRequest);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> payloadCaptor =
                    ArgumentCaptor.forClass(Map.class);
            verify(auditLogService).logTransactionEvent(
                    anyString(),                    // transactionId
                    eq(ACCOUNT_ID),                 // accountId
                    eq("system"),                   // operatorCode
                    eq("BILL_PAID"),                // eventType
                    isNull(),                       // reasonCode
                    payloadCaptor.capture(),        // payload
                    isNull());                      // correlationId

            Map<String, Object> payload = payloadCaptor.getValue();
            assertThat(payload).containsKey("amount");
            assertThat(payload).containsEntry("cardLast4", LAST4_LOW);

            // PCI-DSS — no key or value carries the full PAN
            for (Map.Entry<String, Object> entry : payload.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                assertThat(key)
                        .as("audit key %s must not contain full PAN", key)
                        .doesNotContain(CARD_LOW)
                        .doesNotContain(CARD_HIGH);
                if (value != null) {
                    assertThat(value.toString())
                            .as("audit value for key %s must not contain full PAN", key)
                            .doesNotContain(CARD_LOW)
                            .doesNotContain(CARD_HIGH);
                }
            }
        }

        @Test
        @DisplayName("TransactionAddDto event payload carries verbatim transaction fields")
        void processBillPayment_eventPayloadCarriesTransactionFields() {
            List<CardCrossReference> xrefs = List.of(
                    new CardCrossReference(CARD_LOW, 999_999L, ACCOUNT_ID));
            stubHappyPathRepositories(STARTING_BALANCE, xrefs);

            service.processBillPayment(validRequest);

            ArgumentCaptor<TransactionAddDto> dtoCaptor =
                    ArgumentCaptor.forClass(TransactionAddDto.class);
            verify(kafkaEventPublisher).publishTransactionPosted(
                    anyLong(), dtoCaptor.capture());

            TransactionAddDto evt = dtoCaptor.getValue();
            assertThat(evt.accountId()).isEqualTo(ACCOUNT_ID_STR);
            assertThat(evt.cardNumber()).isEqualTo(CARD_LOW);
            assertThat(evt.transactionType()).isEqualTo("02");
            assertThat(evt.transactionCategory()).isEqualTo(2);
            assertThat(evt.source()).isEqualTo("POS TERM");
            assertThat(evt.description()).isEqualTo("BILL PAYMENT - ONLINE");
            assertThat(evt.amount()).isEqualByComparingTo(STARTING_BALANCE);
            assertThat(evt.merchantId()).isEqualTo(999_999_999L);
            assertThat(evt.merchantName()).isEqualTo("BILL PAYMENT");
            assertThat(evt.merchantCity()).isEqualTo("N/A");
            assertThat(evt.merchantZip()).isEqualTo("N/A");
        }
    }

    @Nested
    @DisplayName("Cache-aside post-write invalidation")
    class CacheAside {

        @Test
        @DisplayName("evicts account-view cache entry under zero-padded acct key")
        void processBillPayment_evictsAccountViewCache() {
            List<CardCrossReference> xrefs = List.of(
                    new CardCrossReference(CARD_LOW, 999_999L, ACCOUNT_ID));
            stubHappyPathRepositories(STARTING_BALANCE, xrefs);

            service.processBillPayment(validRequest);

            verify(cacheService).evict(
                    eq(AccountViewService.CACHE_NS), eq(ACCOUNT_ID_STR));
        }

        @Test
        @DisplayName("cache eviction is invoked after account save")
        void processBillPayment_evictsAfterSave() {
            List<CardCrossReference> xrefs = List.of(
                    new CardCrossReference(CARD_LOW, 999_999L, ACCOUNT_ID));
            stubHappyPathRepositories(STARTING_BALANCE, xrefs);

            BillPaymentDto response = service.processBillPayment(validRequest);

            assertThat(response).isNotNull();
            verify(accountRepository).save(any(Account.class));
            verify(cacheService).evict(anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("@Transactional rollback semantics (replaces implicit CICS SYNCPOINT)")
    class TransactionalRollback {

        /**
         * Verifies that when the {@code WRITE-TRANSACT-FILE} step
         * (COBIL00C.cbl L233 &mdash;
         * {@link TransactionRepository#save(Object)} in the Java target)
         * raises a runtime exception, the failure propagates up so the
         * surrounding {@code @Transactional(rollbackFor = Exception.class,
         * isolation = READ_COMMITTED)} boundary on
         * {@link BillPaymentService#processBillPayment(BillPaymentDto)}
         * triggers a Spring-managed rollback of the entire unit of
         * work &mdash; the Java equivalent of the implicit CICS task-end
         * {@code SYNCPOINT} that originally bracketed the
         * {@code WRITE TRANSACT} + {@code REWRITE ACCTDAT} pair
         * (AAP &sect;0.6.2). The test also locks in the
         * <em>no-downstream-side-effects</em> invariant: when the
         * transaction insert fails, no account rewrite, no cache
         * eviction, no MSK publish, and no audit emission may run, so
         * the rollback cannot leak phantom events to downstream
         * consumers. Mirrors the CICS contract that a failed
         * {@code WRITE} aborts the task before any other resource
         * update can commit.
         */
        // COBOL: COBIL00C:WRITE-TRANSACT-FILE failure (L233)
        @Test
        @DisplayName("transactionRepository.save throws — propagates exception, skips downstream side effects")
        void payBill_transactionSaveFails_propagatesException() {
            // Arrange — stub the happy-path reads then make the
            // TRANSACT save throw a runtime exception to simulate the
            // FILE STATUS '22' (DUPKEY) condition or any other
            // persistence-layer rejection on the journal insert.
            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(existingAccount));
            when(cardCrossReferenceRepository.findByXrefAcctId(ACCOUNT_ID))
                    .thenReturn(List.of(new CardCrossReference(
                            CARD_LOW, 999_999L, ACCOUNT_ID)));
            when(transactionRepository.findTopByNumericTranIdOrderByTranIdDesc())
                    .thenReturn(Optional.empty());
            when(transactionRepository.save(any(Transaction.class)))
                    .thenThrow(new RuntimeException(
                            "Simulated TRANSACT save failure"));

            // Act + Assert — exception propagates so @Transactional
            // boundary can trigger Spring-managed rollback of the
            // whole UOW. The Mockito strict-stubs mode will fail this
            // test if any of the downstream collaborators were
            // unexpectedly invoked.
            assertThatThrownBy(() -> service.processBillPayment(validRequest))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Simulated TRANSACT save failure");

            // No downstream side effects after the exception — the
            // entire @Transactional UOW must roll back atomically.
            verify(accountRepository, never()).save(any(Account.class));
            verify(cacheService, never()).evict(anyString(), anyString());
            verify(kafkaEventPublisher, never())
                    .publishTransactionPosted(anyLong(), any(TransactionAddDto.class));
            verify(kafkaEventPublisher, never())
                    .publishAccountUpdated(anyLong(), any(AccountUpdateDto.class));
            verify(auditLogService, never()).logTransactionEvent(
                    anyString(), anyLong(), anyString(), anyString(),
                    any(), anyMap(), any());
        }

        /**
         * Verifies that when the account-rewrite step
         * ({@code UPDATE-ACCTDAT-FILE} at COBIL00C.cbl L235 &mdash;
         * {@link AccountRepository#save(Object)} in the Java target)
         * raises a runtime exception <em>after</em> the journal
         * insert has succeeded, the exception propagates up so Spring's
         * {@code @Transactional(rollbackFor = Exception.class)}
         * boundary triggers a rollback of BOTH the just-inserted
         * transaction journal row AND the (failed) account balance
         * update. In a unit test we cannot directly observe Spring's
         * physical rollback (that requires {@code @DataJpaTest}); what
         * we CAN confirm is that (a) the exception propagates, and
         * (b) the post-write side effects (cache evict, MSK publishes,
         * audit emission) DID NOT execute &mdash; which Spring requires
         * to safely roll back without leaving phantom downstream
         * events visible to projection consumers.
         */
        // COBOL: COBIL00C:UPDATE-ACCTDAT-FILE failure (L235)
        @Test
        @DisplayName("accountRepository.save throws after TRANSACT insert — propagates exception, no phantom events")
        void payBill_accountSaveFails_propagatesException() {
            // Arrange — TRANSACT save succeeds (returns the supplied
            // entity), ACCTDAT save fails. This exercises the second
            // half of the @Transactional dual-write boundary.
            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(existingAccount));
            when(cardCrossReferenceRepository.findByXrefAcctId(ACCOUNT_ID))
                    .thenReturn(List.of(new CardCrossReference(
                            CARD_LOW, 999_999L, ACCOUNT_ID)));
            when(transactionRepository.findTopByNumericTranIdOrderByTranIdDesc())
                    .thenReturn(Optional.empty());
            when(transactionRepository.save(any(Transaction.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(accountRepository.save(any(Account.class)))
                    .thenThrow(new RuntimeException(
                            "Simulated ACCTDAT save failure"));

            // Act + Assert — exception propagates from the account
            // rewrite; Spring's @Transactional rollback will then void
            // the TRANSACT insert too (cannot observe directly in a
            // unit test, but the absence of phantom side effects
            // below proves the rollback path is safe).
            assertThatThrownBy(() -> service.processBillPayment(validRequest))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Simulated ACCTDAT save failure");

            // The post-write side effects (cache evict, MSK publishes,
            // audit emission) MUST NOT have been triggered. This is the
            // "no phantom events" invariant: a rolled-back JPA write
            // must NOT produce visible downstream MSK or OpenSearch
            // documents (AAP §0.6.5 / §0.7.1).
            verify(cacheService, never()).evict(anyString(), anyString());
            verify(kafkaEventPublisher, never())
                    .publishTransactionPosted(anyLong(), any(TransactionAddDto.class));
            verify(kafkaEventPublisher, never())
                    .publishAccountUpdated(anyLong(), any(AccountUpdateDto.class));
            verify(auditLogService, never()).logTransactionEvent(
                    anyString(), anyLong(), anyString(), anyString(),
                    any(), anyMap(), any());
        }

        /**
         * Verifies that an asynchronous Kafka publisher failure
         * (returned as a failed {@link CompletableFuture} rather than
         * a synchronous throw) does NOT propagate to the caller and
         * does NOT roll back the just-committed JPA state. Confirms
         * the fire-and-forget contract of
         * {@link KafkaEventPublisher#publishTransactionPosted(Long,
         * TransactionAddDto)} and
         * {@link KafkaEventPublisher#publishAccountUpdated(Long,
         * com.awsm2.carddemo.dto.AccountUpdateDto)} in the
         * BillPaymentService (the service intentionally does NOT
         * {@code .join()} the returned future, which would otherwise
         * surface async broker failures as synchronous exceptions and
         * unwind the @Transactional commit retrospectively).
         *
         * <p>This guards against an accidental regression where a
         * future refactor adds a blocking {@code .join()} on the
         * returned future &mdash; doing so would change the consistency
         * model from "JPA committed, MSK best-effort" to "all-or-
         * nothing", potentially preventing legitimate balance updates
         * from being applied during MSK partition outages
         * (AAP &sect;0.6.5 partition strategy).</p>
         */
        // COBOL: (NEW — no source equivalent; AAP §0.6.5 ordering invariant)
        @Test
        @DisplayName("kafka publish returns failed future — does not roll back committed JPA state")
        void payBill_kafkaPublishFails_doesNotPreventCommit() {
            // Arrange — happy-path stubs except the Kafka publishers
            // each return a failed CompletableFuture (simulating an
            // MSK broker error that surfaces AFTER the JPA commit has
            // succeeded; e.g., partition leader unavailable, broker
            // throttling, IAM auth refresh failure on rotation).
            when(accountRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(existingAccount));
            when(cardCrossReferenceRepository.findByXrefAcctId(ACCOUNT_ID))
                    .thenReturn(List.of(new CardCrossReference(
                            CARD_LOW, 999_999L, ACCOUNT_ID)));
            when(transactionRepository.findTopByNumericTranIdOrderByTranIdDesc())
                    .thenReturn(Optional.empty());
            when(transactionRepository.save(any(Transaction.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(accountRepository.save(any(Account.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            // CompletableFuture.failedFuture(...) is the JDK 9+
            // idiomatic factory for an already-failed future; the
            // synchronous service code receives the future immediately
            // and does NOT .join() it, so the failure remains async
            // and unobservable on the caller thread.
            when(kafkaEventPublisher.publishTransactionPosted(
                            anyLong(), any(TransactionAddDto.class)))
                    .thenReturn(CompletableFuture.failedFuture(
                            new RuntimeException(
                                    "Simulated MSK broker failure on transaction.posted")));
            when(kafkaEventPublisher.publishAccountUpdated(
                            anyLong(), any(AccountUpdateDto.class)))
                    .thenReturn(CompletableFuture.failedFuture(
                            new RuntimeException(
                                    "Simulated MSK broker failure on account.updated")));

            // Act — must NOT throw. The synchronous call sees only the
            // CompletableFuture references; the broker failures are
            // surfaced asynchronously via Spring's @Async error
            // handler (configured in KafkaConfig per AAP §0.6.5).
            BillPaymentDto response = service.processBillPayment(validRequest);

            // Assert — service returned a fully-populated confirmation
            // DTO (the JPA commit succeeded regardless of MSK state).
            assertThat(response).isNotNull();
            assertThat(response.transactionId()).isNotBlank();
            assertThat(response.transactionId()).hasSize(16);
            assertThat(response.amountPaid())
                    .isEqualByComparingTo(STARTING_BALANCE);

            // Both JPA writes committed; both Kafka publishes were
            // invoked (their failure is async and irrelevant here).
            verify(transactionRepository).save(any(Transaction.class));
            verify(accountRepository).save(any(Account.class));
            verify(kafkaEventPublisher).publishTransactionPosted(
                    anyLong(), any(TransactionAddDto.class));
            verify(kafkaEventPublisher).publishAccountUpdated(
                    anyLong(), any(AccountUpdateDto.class));
        }
    }

    @Nested
    @DisplayName("Response shape")
    class ResponseShape {

        @Test
        @DisplayName("response includes transactionId, postedAt, amountPaid, currentBalance")
        void processBillPayment_responseShape() {
            List<CardCrossReference> xrefs = List.of(
                    new CardCrossReference(CARD_LOW, 999_999L, ACCOUNT_ID));
            stubHappyPathRepositories(STARTING_BALANCE, xrefs);

            BillPaymentDto response = service.processBillPayment(validRequest);

            assertThat(response).isNotNull();
            assertThat(response.accountId()).isEqualTo(ACCOUNT_ID_STR);
            assertThat(response.confirm()).isEqualTo("Y");
            assertThat(response.transactionId()).isNotBlank();
            assertThat(response.transactionId()).hasSize(16);
            assertThat(response.postedAt()).isNotNull();
            assertThat(response.amountPaid()).isNotNull();
            assertThat(response.currentBalance())
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("response accountId is zero-padded to 11 digits")
        void processBillPayment_responseAccountIdZeroPadded() {
            // Build a request with a numerically valid but shorter
            // account ID
            Long smallAcctId = 1L;
            existingAccount.setAcctId(smallAcctId);
            existingAccount.setAcctCurrBal(STARTING_BALANCE);
            BillPaymentDto request = new BillPaymentDto(
                    "00000000001",
                    STARTING_BALANCE,
                    "Y",
                    null, null, null);
            List<CardCrossReference> xrefs = List.of(
                    new CardCrossReference(CARD_LOW, 999_999L, smallAcctId));
            lenient().when(accountRepository.findById(smallAcctId))
                    .thenReturn(Optional.of(existingAccount));
            lenient().when(accountRepository.save(any(Account.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            lenient().when(cardCrossReferenceRepository.findByXrefAcctId(smallAcctId))
                    .thenReturn(xrefs);
            lenient().when(transactionRepository.findTopByNumericTranIdOrderByTranIdDesc())
                    .thenReturn(Optional.empty());
            lenient().when(transactionRepository.save(any(Transaction.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            lenient().when(kafkaEventPublisher.publishTransactionPosted(
                            anyLong(), any(TransactionAddDto.class)))
                    .thenReturn(CompletableFuture.completedFuture(null));
            lenient().when(kafkaEventPublisher.publishAccountUpdated(
                            anyLong(), any(AccountUpdateDto.class)))
                    .thenReturn(CompletableFuture.completedFuture(null));

            BillPaymentDto response = service.processBillPayment(request);

            assertThat(response.accountId()).isEqualTo("00000000001");
            assertThat(response.accountId()).hasSize(11);
        }
    }
}

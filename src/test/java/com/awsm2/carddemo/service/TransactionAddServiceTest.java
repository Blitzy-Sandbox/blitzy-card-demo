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
import com.awsm2.carddemo.domain.CardCrossReference;
import com.awsm2.carddemo.domain.Transaction;
import com.awsm2.carddemo.dto.TransactionAddDto;
import com.awsm2.carddemo.exception.OnSizeErrorException;
import com.awsm2.carddemo.exception.RecordNotFoundException;
import com.awsm2.carddemo.exception.ValidationException;
import com.awsm2.carddemo.repository.CardCrossReferenceRepository;
import com.awsm2.carddemo.repository.TransactionRepository;
import com.awsm2.carddemo.validation.DateValidationService;
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
import java.time.LocalDateTime;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JUnit 5 + Mockito + AssertJ unit tests for
 * {@link TransactionAddService}.
 *
 * <p><b>COBOL provenance.</b> {@link TransactionAddService} translates
 * {@code app/cbl/COTRN02C.cbl} (CICS transaction id {@code CT02}, file
 * {@code 'TRANSACT'}). The COBOL source assigns the next transaction
 * ID by browsing the {@code TRANSACT} VSAM cluster in descending order
 * ({@code STARTBR} + {@code READPREV}), then performs
 * {@code ADD 1 TO WS-TRAN-ID-N} with an {@code ON SIZE ERROR} clause to
 * detect 16-digit overflow.</p>
 *
 * <p><b>Behavioural invariants locked by this suite.</b></p>
 * <ol>
 *   <li><b>MAX-TRAN-ID + 1 generator</b> &mdash; the next tran ID is
 *       the persisted maximum + 1, zero-padded to 16 digits. An empty
 *       table seeds from {@code SEED_TRAN_ID} ({@code 0000000000000000}).</li>
 *   <li><b>ON SIZE ERROR</b> &mdash; when the next ID would exceed
 *       {@code 9999999999999999} an {@link OnSizeErrorException} is
 *       raised (matches COBOL {@code ON SIZE ERROR} branch).</li>
 *   <li><b>XREF lookup</b> &mdash; if the card number resolves to a
 *       missing {@link CardCrossReference} the service throws
 *       {@link RecordNotFoundException}; a supplied {@code accountId}
 *       that does not match the card's owning account throws
 *       {@link ValidationException} ({@code XREF_MISMATCH}).</li>
 *   <li><b>Confirmation gate</b> &mdash; {@code confirm != 'Y'}
 *       (case-insensitive) blocks persistence with
 *       {@link ValidationException} ({@code NOT_CONFIRMED}).</li>
 *   <li><b>BigDecimal preservation</b> &mdash; the supplied amount
 *       is persisted verbatim with its scale intact (no float/double
 *       conversion).</li>
 *   <li><b>MSK partitioning</b> &mdash; the published
 *       {@code transaction.posted} event uses the OWNING ACCOUNT ID
 *       as the partition key, satisfying AAP §0.6.5 per-account
 *       ordering invariant.</li>
 *   <li><b>Audit + log discipline</b> &mdash; audit payload contains
 *       only the last 4 of the PAN; never the full card number.</li>
 * </ol>
 *
 * <p>External AWS interactions are fully mocked through
 * {@link MockitoExtension}; no Testcontainers or LocalStack are
 * involved.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionAddService unit tests (COBOL: COTRN02C.cbl)")
class TransactionAddServiceTest {

    // ==================================================================
    // Constants
    // ==================================================================
    private static final String CARD_NUMBER = "4111222233334444";
    private static final String LAST4 = "4444";
    private static final String ACCOUNT_ID_STR = "10000000001";
    private static final Long ACCOUNT_ID = 10_000_000_001L;
    private static final String TRAN_TYPE = "01";
    private static final Integer TRAN_CAT = 5;
    private static final String SOURCE = "POS";
    private static final String DESCRIPTION = "Coffee shop";
    private static final BigDecimal AMOUNT = new BigDecimal("12.50");
    private static final Long MERCHANT_ID = 999_888L;
    private static final String MERCHANT_NAME = "STARBUCKS #123";
    private static final String MERCHANT_CITY = "SEATTLE";
    private static final String MERCHANT_ZIP = "98101";

    // ==================================================================
    // Mocks and SUT
    // ==================================================================
    @Mock private TransactionRepository transactionRepository;
    @Mock private CardCrossReferenceRepository cardCrossReferenceRepository;
    @Mock private KafkaEventPublisher kafkaEventPublisher;
    @Mock private AuditLogService auditLogService;
    @Mock private DateValidationService dateValidationService;

    @InjectMocks private TransactionAddService service;

    // ==================================================================
    // Common test fixtures
    // ==================================================================
    private TransactionAddDto validRequest;

    @BeforeEach
    void setUp() {
        validRequest = new TransactionAddDto(
                ACCOUNT_ID_STR,
                CARD_NUMBER,
                TRAN_TYPE,
                TRAN_CAT,
                SOURCE,
                DESCRIPTION,
                AMOUNT,
                null, // originationTimestamp
                null, // processingTimestamp (assigned by service)
                MERCHANT_ID,
                MERCHANT_NAME,
                MERCHANT_CITY,
                MERCHANT_ZIP,
                "Y");
    }

    private TransactionAddDto buildRequestWithConfirm(String confirm) {
        return new TransactionAddDto(
                ACCOUNT_ID_STR, CARD_NUMBER, TRAN_TYPE, TRAN_CAT, SOURCE,
                DESCRIPTION, AMOUNT, null, null, MERCHANT_ID, MERCHANT_NAME,
                MERCHANT_CITY, MERCHANT_ZIP, confirm);
    }

    private TransactionAddDto buildRequestWithAmount(BigDecimal amount) {
        return new TransactionAddDto(
                ACCOUNT_ID_STR, CARD_NUMBER, TRAN_TYPE, TRAN_CAT, SOURCE,
                DESCRIPTION, amount, null, null, MERCHANT_ID, MERCHANT_NAME,
                MERCHANT_CITY, MERCHANT_ZIP, "Y");
    }

    private TransactionAddDto buildRequestWithAccountAndCard(String acct, String card) {
        return new TransactionAddDto(
                acct, card, TRAN_TYPE, TRAN_CAT, SOURCE, DESCRIPTION,
                AMOUNT, null, null, MERCHANT_ID, MERCHANT_NAME,
                MERCHANT_CITY, MERCHANT_ZIP, "Y");
    }

    /**
     * Stub the XREF lookup for the happy path (card → account).
     * Uses {@code lenient()} because some tests verify the XREF call
     * is never reached.
     *
     * <p>Constructor order is intentionally
     * {@code CardCrossReference(xrefCardNum, xrefCustId, xrefAcctId)};
     * the test seeds a fixed customer id but the actual account id
     * supplied by the caller.</p>
     */
    private void stubXrefByCard(String cardNumber, Long acctId) {
        CardCrossReference xref =
                new CardCrossReference(cardNumber, 999_999L /* custId */, acctId);
        lenient().when(cardCrossReferenceRepository.findById(cardNumber))
                .thenReturn(Optional.of(xref));
    }

    /**
     * Stub the transaction repository's MAX-TRAN-ID lookup and save.
     */
    private void stubTransactionRepository(String existingMax) {
        Transaction existing = null;
        if (existingMax != null) {
            existing = new Transaction(
                    existingMax, TRAN_TYPE, TRAN_CAT, SOURCE,
                    DESCRIPTION, AMOUNT, MERCHANT_ID, MERCHANT_NAME,
                    MERCHANT_CITY, MERCHANT_ZIP, CARD_NUMBER,
                    LocalDateTime.now(), LocalDateTime.now());
        }
        Optional<Transaction> result = existing == null
                ? Optional.empty() : Optional.of(existing);
        lenient().when(transactionRepository.findTopByOrderByTranIdDesc())
                .thenReturn(result);
        lenient().when(transactionRepository.save(any(Transaction.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private void stubKafkaPublishSuccess() {
        lenient().when(kafkaEventPublisher.publishTransactionPosted(
                anyLong(), any(TransactionAddDto.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    // ==================================================================
    // @Nested test groups
    // ==================================================================

    @Nested
    @DisplayName("MAX-TRAN-ID + 1 generator (COBOL: 9000-WRITE-TRANSACTION + ON SIZE ERROR)")
    class TranIdGeneration {

        @Test
        @DisplayName("seeds with 0000000000000001 when journal is empty")
        void addTransaction_emptyJournal_seedsWithOne() {
            // Arrange — empty journal
            stubXrefByCard(CARD_NUMBER, ACCOUNT_ID);
            stubTransactionRepository(null);
            stubKafkaPublishSuccess();

            // Act
            TransactionAddDto response = service.addTransaction(validRequest);

            // Assert — captured save carries tranId = 0000000000000001
            ArgumentCaptor<Transaction> captor =
                    ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(captor.capture());
            assertThat(captor.getValue().getTranId())
                    .isEqualTo("0000000000000001");
            assertThat(response.processingTimestamp()).isNotNull();
        }

        @Test
        @DisplayName("assigns MAX + 1 (zero-padded to 16 digits)")
        void addTransaction_existingMax_assignsNextSequentialId() {
            // Arrange — current max tran ID is 0000000000000123
            stubXrefByCard(CARD_NUMBER, ACCOUNT_ID);
            stubTransactionRepository("0000000000000123");
            stubKafkaPublishSuccess();

            // Act
            service.addTransaction(validRequest);

            // Assert — next id is 0000000000000124 (zero-padded)
            ArgumentCaptor<Transaction> captor =
                    ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(captor.capture());
            assertThat(captor.getValue().getTranId())
                    .isEqualTo("0000000000000124");
        }

        @Test
        @DisplayName("throws OnSizeErrorException at 16-digit ceiling")
        void addTransaction_maxIdReached_throwsOnSizeError() {
            // Arrange — current max is the ceiling
            stubXrefByCard(CARD_NUMBER, ACCOUNT_ID);
            lenient().when(transactionRepository.findTopByOrderByTranIdDesc())
                    .thenReturn(Optional.of(new Transaction(
                            "9999999999999999", TRAN_TYPE, TRAN_CAT, SOURCE,
                            DESCRIPTION, AMOUNT, MERCHANT_ID, MERCHANT_NAME,
                            MERCHANT_CITY, MERCHANT_ZIP, CARD_NUMBER,
                            LocalDateTime.now(), LocalDateTime.now())));

            // Act + Assert
            assertThatThrownBy(() -> service.addTransaction(validRequest))
                    .isInstanceOf(OnSizeErrorException.class)
                    .hasMessageContaining("exhausted");

            // No save, publish, or audit on overflow
            verify(transactionRepository, never()).save(any());
            verify(kafkaEventPublisher, never())
                    .publishTransactionPosted(anyLong(), any());
            verify(auditLogService, never())
                    .auditEvent(anyString(), anyString(), anyMap());
        }

        @Test
        @DisplayName("non-numeric existing tran ID re-seeds and continues")
        void addTransaction_nonNumericMax_reseeds() {
            // Arrange — corrupted (non-numeric) value in the table
            stubXrefByCard(CARD_NUMBER, ACCOUNT_ID);
            Transaction corrupted = new Transaction();
            corrupted.setTranId("ABCDEFGHIJKLMNOP");
            lenient().when(transactionRepository.findTopByOrderByTranIdDesc())
                    .thenReturn(Optional.of(corrupted));
            lenient().when(transactionRepository.save(any(Transaction.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            stubKafkaPublishSuccess();

            // Act
            service.addTransaction(validRequest);

            // Assert — service re-seeds and writes tran id = 1
            ArgumentCaptor<Transaction> captor =
                    ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(captor.capture());
            assertThat(captor.getValue().getTranId())
                    .isEqualTo("0000000000000001");
        }
    }

    @Nested
    @DisplayName("XREF resolution (COBOL: READ-CXACAIX-FILE)")
    class XrefResolution {

        @Test
        @DisplayName("missing XREF for supplied card → RecordNotFoundException")
        void addTransaction_xrefMissing_throwsRecordNotFound() {
            // Arrange — XREF lookup returns empty
            when(cardCrossReferenceRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.empty());

            // Act + Assert
            assertThatThrownBy(() -> service.addTransaction(validRequest))
                    .isInstanceOf(RecordNotFoundException.class)
                    .hasMessageContaining("cross-reference");

            verify(transactionRepository, never()).save(any());
        }

        @Test
        @DisplayName("accountId mismatch against XREF → ValidationException (XREF_MISMATCH)")
        void addTransaction_acctIdMismatch_throwsValidation() {
            // Arrange — XREF resolves to acct 99999999999 but request
            // says 10000000001
            CardCrossReference xref = new CardCrossReference(
                    CARD_NUMBER, 99_999_999_999L, 999_999L);
            when(cardCrossReferenceRepository.findById(CARD_NUMBER))
                    .thenReturn(Optional.of(xref));

            // Act + Assert
            assertThatThrownBy(() -> service.addTransaction(validRequest))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("does not match");

            verify(transactionRepository, never()).save(any());
        }

        @Test
        @DisplayName("only accountId supplied → AIX lookup; empty AIX → RecordNotFound")
        void addTransaction_accountOnly_emptyAix_throwsRecordNotFound() {
            // Arrange — only accountId supplied
            TransactionAddDto request =
                    buildRequestWithAccountAndCard(ACCOUNT_ID_STR, null);
            when(cardCrossReferenceRepository.findByXrefAcctId(ACCOUNT_ID))
                    .thenReturn(List.of());

            // Act + Assert
            assertThatThrownBy(() -> service.addTransaction(request))
                    .isInstanceOf(RecordNotFoundException.class)
                    .hasMessageContaining("No card cross-reference");

            verify(transactionRepository, never()).save(any());
        }

        @Test
        @DisplayName("neither accountId nor cardNumber → ValidationException")
        void addTransaction_noIdentifier_throwsValidation() {
            // Arrange — request missing both identifiers
            TransactionAddDto request =
                    buildRequestWithAccountAndCard(null, null);

            // Act + Assert — the per-field error message is collected
            // into ValidationException.fieldErrors (the top-level
            // exception message is the COBOL summary string).
            assertThatThrownBy(() -> service.addTransaction(request))
                    .isInstanceOf(ValidationException.class)
                    .extracting(ex -> ((ValidationException) ex).getFieldErrors())
                    .asList()
                    .anyMatch(fe -> fe.toString().contains("Either accountId or cardNumber"));
        }
    }

    @Nested
    @DisplayName("Confirmation gate (COBOL: CONFIRMI flag)")
    class Confirmation {

        @Test
        @DisplayName("'N' confirm value blocks persistence")
        void addTransaction_confirmN_throwsValidation() {
            stubXrefByCard(CARD_NUMBER, ACCOUNT_ID);
            TransactionAddDto request = buildRequestWithConfirm("N");

            // The top-level ValidationException message text is the
            // service-supplied message; the reasonCode discriminator
            // is "NOT_CONFIRMED".
            assertThatThrownBy(() -> service.addTransaction(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("Operator did not confirm");

            verify(transactionRepository, never()).save(any());
        }

        @Test
        @DisplayName("'y' (lowercase) is accepted (case-insensitive)")
        void addTransaction_confirmLowercaseY_succeeds() {
            stubXrefByCard(CARD_NUMBER, ACCOUNT_ID);
            stubTransactionRepository(null);
            stubKafkaPublishSuccess();
            TransactionAddDto request = buildRequestWithConfirm("y");

            service.addTransaction(request);

            verify(transactionRepository).save(any(Transaction.class));
        }

        @Test
        @DisplayName("null confirm blocks persistence")
        void addTransaction_nullConfirm_throwsValidation() {
            stubXrefByCard(CARD_NUMBER, ACCOUNT_ID);
            TransactionAddDto request = buildRequestWithConfirm(null);

            assertThatThrownBy(() -> service.addTransaction(request))
                    .isInstanceOf(ValidationException.class);

            verify(transactionRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("Field validation (COBOL: 1000-PROCESS-INPUTS)")
    class FieldValidation {

        @Test
        @DisplayName("rejects null request")
        void addTransaction_nullRequest_throwsNpe() {
            assertThatThrownBy(() -> service.addTransaction(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("rejects 10-digit accountId (must be 11)")
        void addTransaction_shortAccountId_throwsValidation() {
            TransactionAddDto request =
                    buildRequestWithAccountAndCard("1000000001", null);
            // The top-level ValidationException message is "Transaction
            // add request contains invalid fields"; the per-field error
            // is "accountId must be exactly 11 digits". Verify both.
            assertThatThrownBy(() -> service.addTransaction(request))
                    .isInstanceOf(ValidationException.class)
                    .extracting(ex -> ((ValidationException) ex).getFieldErrors())
                    .asList()
                    .anyMatch(fe -> fe.toString().contains("11 digits"));
        }

        @Test
        @DisplayName("rejects 15-digit cardNumber (must be 16)")
        void addTransaction_shortCardNumber_throwsValidation() {
            TransactionAddDto request = buildRequestWithAccountAndCard(
                    null, "411122223333444");
            assertThatThrownBy(() -> service.addTransaction(request))
                    .isInstanceOf(ValidationException.class)
                    .extracting(ex -> ((ValidationException) ex).getFieldErrors())
                    .asList()
                    .anyMatch(fe -> fe.toString().contains("16 digits"));
        }

        @Test
        @DisplayName("rejects missing amount")
        void addTransaction_nullAmount_throwsValidation() {
            TransactionAddDto request = buildRequestWithAmount(null);
            assertThatThrownBy(() -> service.addTransaction(request))
                    .isInstanceOf(ValidationException.class)
                    .extracting(ex -> ((ValidationException) ex).getFieldErrors())
                    .asList()
                    .anyMatch(fe -> fe.toString().contains("amount is required"));
        }

        @Test
        @DisplayName("rejects invalid origination date via DateValidationService")
        void addTransaction_invalidOriginationDate_throwsValidation() {
            LocalDateTime origin = LocalDateTime.of(2099, 1, 1, 12, 0);
            TransactionAddDto request = new TransactionAddDto(
                    ACCOUNT_ID_STR, CARD_NUMBER, TRAN_TYPE, TRAN_CAT, SOURCE,
                    DESCRIPTION, AMOUNT, origin, null, MERCHANT_ID,
                    MERCHANT_NAME, MERCHANT_CITY, MERCHANT_ZIP, "Y");
            when(dateValidationService.validate(anyString()))
                    .thenReturn(DateValidationService.DateValidationResult.invalid(
                            "E001", "Date in the future"));

            assertThatThrownBy(() -> service.addTransaction(request))
                    .isInstanceOf(ValidationException.class);
        }
    }

    @Nested
    @DisplayName("Happy path: persist + MSK publish + audit")
    class HappyPath {

        @Test
        @DisplayName("end-to-end success: write, publish, audit")
        void addTransaction_happyPath_writesPublishesAndAudits() {
            // Arrange
            stubXrefByCard(CARD_NUMBER, ACCOUNT_ID);
            stubTransactionRepository("0000000000000010");
            stubKafkaPublishSuccess();

            // Act
            TransactionAddDto response = service.addTransaction(validRequest);

            // Assert — response carries new tran ID, account ID (padded),
            // and processing timestamp
            assertThat(response).isNotNull();
            assertThat(response.accountId()).isEqualTo("10000000001");
            assertThat(response.processingTimestamp()).isNotNull();

            // Save called once
            verify(transactionRepository).save(any(Transaction.class));

            // MSK publish — partition key = owning account ID (AAP §0.6.5)
            ArgumentCaptor<Long> partitionKeyCaptor =
                    ArgumentCaptor.forClass(Long.class);
            verify(kafkaEventPublisher).publishTransactionPosted(
                    partitionKeyCaptor.capture(),
                    any(TransactionAddDto.class));
            assertThat(partitionKeyCaptor.getValue()).isEqualTo(ACCOUNT_ID);

            // Audit — payload contains transactionId, accountId, last4
            verify(auditLogService).auditEvent(
                    eq("transaction.added"), eq("system"), anyMap());
        }

        @Test
        @DisplayName("amount is preserved verbatim (BigDecimal, no float)")
        void addTransaction_amountPreservedVerbatim() {
            // Arrange — non-trivial decimal value
            BigDecimal amount = new BigDecimal("123.45");
            TransactionAddDto request = buildRequestWithAmount(amount);
            stubXrefByCard(CARD_NUMBER, ACCOUNT_ID);
            stubTransactionRepository(null);
            stubKafkaPublishSuccess();

            // Act
            service.addTransaction(request);

            // Assert — saved entity carries the EXACT BigDecimal value,
            // scale included (no rounding, no float conversion)
            ArgumentCaptor<Transaction> captor =
                    ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(captor.capture());
            BigDecimal saved = captor.getValue().getTranAmt();
            assertThat(saved).isEqualByComparingTo(amount);
            assertThat(saved.scale()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("PCI-DSS discipline: audit + log lines")
    class PciDssHandling {

        @Test
        @DisplayName("audit payload contains cardLast4 only, never the full PAN")
        void addTransaction_auditOmitsFullPan() {
            // Arrange
            stubXrefByCard(CARD_NUMBER, ACCOUNT_ID);
            stubTransactionRepository(null);
            stubKafkaPublishSuccess();

            // Act
            service.addTransaction(validRequest);

            // Assert — verify the audit payload has masked PAN
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> payloadCaptor =
                    ArgumentCaptor.forClass(Map.class);
            verify(auditLogService).auditEvent(
                    eq("transaction.added"), eq("system"),
                    payloadCaptor.capture());

            Map<String, Object> payload = payloadCaptor.getValue();
            assertThat(payload).containsEntry("cardLast4", LAST4);
            assertThat(payload).containsEntry("accountId", ACCOUNT_ID);

            // No key/value may include the full PAN
            for (Map.Entry<String, Object> entry : payload.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                assertThat(key)
                        .as("audit key %s must not contain full PAN", key)
                        .doesNotContain(CARD_NUMBER);
                if (value != null) {
                    assertThat(value.toString())
                            .as("audit value for key %s must not contain full PAN", key)
                            .doesNotContain(CARD_NUMBER);
                }
            }
        }
    }
}

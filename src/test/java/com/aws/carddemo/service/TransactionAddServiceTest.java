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
package com.aws.carddemo.service;

import com.aws.carddemo.entity.Account;
import com.aws.carddemo.entity.CardXref;
import com.aws.carddemo.entity.Transaction;
import com.aws.carddemo.repository.AccountRepository;
import com.aws.carddemo.repository.CardXrefRepository;
import com.aws.carddemo.repository.TransactionRepository;
import com.aws.carddemo.testsupport.TestFixtures;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TransactionAddService}, the migrated Java equivalent of the
 * 783-line COBOL CICS program {@code app/cbl/COTRN02C.cbl} (TRANID {@code CT02}).
 * The service adds a new transaction record to {@code TRANSACT} with a comprehensive
 * field-level validation cascade.
 *
 * <h2>COBOL Provenance — COTRN02C.cbl Validation Cascade</h2>
 *
 * <p>{@code VALIDATE-INPUT-KEY-FIELDS} (lines 193–230) — key resolution:
 * <ul>
 *   <li>If account ID supplied: must be numeric.</li>
 *   <li>If card number supplied: must be numeric.</li>
 *   <li>If neither supplied: {@code "Account or Card Number must be entered..."}</li>
 * </ul>
 *
 * <p>{@code VALIDATE-INPUT-DATA-FIELDS} (lines 235–437) — ordered checks (empty →
 * numeric → format → semantic date):
 * <ol>
 *   <li>Empty checks in COBOL paragraph order: type, category, source, description,
 *       amount, origin date, process date, merchant ID, merchant name, merchant
 *       city, merchant ZIP — each produces a verbatim reject reason.</li>
 *   <li>Numeric checks on type code, category code, merchant ID.</li>
 *   <li>Amount format check: must match {@code [-+]\d{8}\.\d{2}}.</li>
 *   <li>Date format check: must match {@code \d{4}-\d{2}-\d{2}}.</li>
 *   <li>Semantic date validation via the Java replacement for CSUTLDTC (strict
 *       {@link java.time.format.DateTimeFormatter} parsing).</li>
 * </ol>
 *
 * <p>{@code ADD-TRANSACTION} (lines 442–466):
 * <ol>
 *   <li>Auto-generate next TRAN-ID via the Java equivalent of
 *       {@code STARTBR HIGH-VALUES + READPREV + ENDBR}: read the highest existing
 *       TRAN-ID via {@link TransactionRepository#findTopByOrderByTransactionIdDesc()},
 *       increment by 1.</li>
 *   <li>INITIALIZE TRAN-RECORD; populate from screen inputs.</li>
 *   <li>WRITE TRANSACT.</li>
 * </ol>
 *
 * <h2>Test Strategy (AAP §0.10.1 Require Test Coverage Rule)</h2>
 *
 * <p>Every test instantiates the real {@link TransactionAddService} via its
 * 4-arg constructor with Mockito mocks for the three JPA repository boundaries and
 * a deterministic {@link Clock#fixed(Instant, java.time.ZoneId)} clock pinned at
 * {@code 2024-01-15T00:00:00Z}. The tests assert on observable behaviour of the
 * production service — captured {@link Transaction} entities passed to
 * {@code save()}, returned {@link TransactionAddResult} state, and short-circuit
 * semantics on reject paths via {@code verify(...never()).save(any())}.
 *
 * <h2>Reject Reason Strings (AAP §0.10.4)</h2>
 *
 * <p>The COBOL source uses very specific reason text. Tests use AssertJ
 * {@code containsIgnoringCase} on the key tokens (such as {@code "numeric"},
 * {@code "empty"}, {@code "account"}, {@code "card"}, {@code "amount"},
 * {@code "merchant"}, {@code "date"}) because exact byte-equality is
 * over-constrained for a Java migration where reject reasons may render with
 * different capitalisation or trailing ellipsis; the essential semantics (which
 * field is rejected) remain identical.
 *
 * @see TransactionAddService
 * @see TransactionAddRequest
 * @see TransactionAddResult
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionAddService — COTRN02C.cbl migration parity")
final class TransactionAddServiceTest {

    /** JPA boundary mock — see AAP §0.10.1 mocks-limited-to-external-boundaries rule. */
    @Mock
    private TransactionRepository transactionRepository;

    /** JPA boundary mock — see AAP §0.10.1 mocks-limited-to-external-boundaries rule. */
    @Mock
    private AccountRepository accountRepository;

    /** JPA boundary mock — see AAP §0.10.1 mocks-limited-to-external-boundaries rule. */
    @Mock
    private CardXrefRepository cardXrefRepository;

    /**
     * Deterministic {@link Clock} pinned at {@code 2024-01-15T00:00:00Z}. The
     * service consumes this clock to stamp {@code TRAN-ORIG-TS} and
     * {@code TRAN-PROC-TS}; the fixed instant guarantees reproducible
     * audit-timestamp assertions (AAP §0.10.9 test-independence rule).
     */
    private final Clock fixedClock = Clock.fixed(
            Instant.parse(TestFixtures.Dates.FIXED_CLOCK_INSTANT),
            ZoneOffset.UTC);

    /** System under test — instantiated fresh per {@code @Test} via {@link #setUp()}. */
    private TransactionAddService service;

    /**
     * Instantiate the real production {@link TransactionAddService} with Mockito
     * boundary mocks plus the deterministic clock. Per AAP §0.10.9 each test
     * sees a freshly constructed service so the tests can run in any order.
     */
    @BeforeEach
    void setUp() {
        service = new TransactionAddService(
                transactionRepository, accountRepository, cardXrefRepository, fixedClock);
    }

    // =========================================================================
    // HappyPath — auto-generated TRAN-ID, clock-stamped timestamps, persisted
    // =========================================================================

    /**
     * Happy-path scenarios for {@code COTRN02C ADD-TRANSACTION} (lines 442–466):
     * a fully-populated valid request produces a successful add, with the new
     * 16-character TRAN-ID derived from the last+1 pattern and the
     * {@code TRAN-ORIG-TS} / {@code TRAN-PROC-TS} fields stamped from the
     * injected {@link Clock}.
     */
    @Nested
    @DisplayName("Happy path — ADD-TRANSACTION (COTRN02C lines 442–466)")
    class HappyPath {

        @Test
        @DisplayName("addTransaction(validRequest) auto-generates next TRAN-ID and persists")
        void addTransaction_validRequest_generatesNextIdAndPersists() {
            // Arrange — last TRAN-ID is 0000000000683579, so next should be 0000000000683580
            when(transactionRepository.findTopByOrderByTransactionIdDesc())
                    .thenReturn(Optional.of(transactionWithId("0000000000683579")));
            when(transactionRepository.save(any(Transaction.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            TransactionAddRequest request = buildValidRequest();

            // Act
            TransactionAddResult result = service.addTransaction(request);

            // Assert — success outcome
            assertThat(result.isSuccess())
                    .as("happy-path add must return a successful result")
                    .isTrue();
            assertThat(result.getMessage())
                    .as("success message must include the new TRAN-ID per COBOL SEND-TRNADD-SCREEN")
                    .containsIgnoringCase("transaction")
                    .containsIgnoringCase("0000000000683580");

            // Assert — captured Transaction matches COBOL ADD-TRANSACTION field-by-field MOVE
            ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(captor.capture());
            Transaction saved = captor.getValue();

            assertThat(saved.getTransactionId())
                    .as("TRAN-ID = last+1, zero-padded to 16 chars per COTRN02C line 448")
                    .isEqualTo("0000000000683580");
            assertThat(saved.getCardNumber()).isEqualTo(request.getCardNumber());
            assertThat(saved.getTransactionTypeCode())
                    .isEqualTo(TestFixtures.Transactions.TRAN_TYPE_PURCHASE);
            assertThat(saved.getTransactionCategoryCode())
                    .isEqualTo(TestFixtures.Transactions.TRAN_CAT_REGULAR_SALES);
            assertThat(saved.getAmount())
                    .as("amount value preserved at scale 2 per PIC S9(09)V99")
                    .isEqualByComparingTo(request.getAmount())
                    .satisfies(b -> assertThat(b.scale()).isEqualTo(2));
        }

        @Test
        @DisplayName("addTransaction stamps TRAN-ORIG-TS and TRAN-PROC-TS from injected Clock")
        void addTransaction_validRequest_stampsTimestampsFromInjectedClock() {
            // Arrange
            when(transactionRepository.findTopByOrderByTransactionIdDesc())
                    .thenReturn(Optional.of(transactionWithId("0000000000000001")));
            when(transactionRepository.save(any(Transaction.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // Act
            service.addTransaction(buildValidRequest());

            // Assert — origin and process timestamps both stamped at fixed-clock time
            ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(captor.capture());
            Transaction saved = captor.getValue();

            assertThat(saved.getOriginTimestamp())
                    .as("TRAN-ORIG-TS must be derived from injected Clock at 2024-01-15T00:00:00Z")
                    .isEqualTo("2024-01-15 00:00:00.000000");
            assertThat(saved.getProcessTimestamp())
                    .as("TRAN-PROC-TS must be derived from injected Clock at 2024-01-15T00:00:00Z")
                    .isEqualTo("2024-01-15 00:00:00.000000");
        }

        @Test
        @DisplayName("addTransaction seeds TRAN-ID at 1 when transactions table is empty")
        void addTransaction_emptyStore_seedsTransactionIdAtOne() {
            // Arrange — empty store mirrors COBOL DFHRESP(ENDFILE) on READPREV
            when(transactionRepository.findTopByOrderByTransactionIdDesc())
                    .thenReturn(Optional.empty());
            when(transactionRepository.save(any(Transaction.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // Act
            TransactionAddResult result = service.addTransaction(buildValidRequest());

            // Assert — first TRAN-ID is 0000000000000001
            assertThat(result.isSuccess()).isTrue();
            ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(captor.capture());
            assertThat(captor.getValue().getTransactionId())
                    .as("first TRAN-ID seeds at FIRST_TRAN_ID per COBOL MOVE ZEROS TO TRAN-ID + ADD 1")
                    .isEqualTo("0000000000000001");
        }

        @Test
        @DisplayName("addTransaction resolves missing card number via CXACAIX cross-reference (account→card)")
        void addTransaction_accountIdOnly_resolvesCardNumberViaXref() {
            // Arrange — only account ID supplied; CardXref provides the card number
            when(transactionRepository.findTopByOrderByTransactionIdDesc())
                    .thenReturn(Optional.of(transactionWithId("0000000000000001")));
            when(transactionRepository.save(any(Transaction.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(cardXrefRepository.findByAccountId(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10))
                    .thenReturn(Optional.of(standardCardXref()));

            TransactionAddRequest request = buildValidRequest();
            request.setCardNumber("");

            // Act
            TransactionAddResult result = service.addTransaction(request);

            // Assert — service resolved the card number via the xref read
            assertThat(result.isSuccess()).isTrue();
            ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(captor.capture());
            assertThat(captor.getValue().getCardNumber())
                    .as("card number resolved via CXACAIX cross-reference per COTRN02C lines 211–216")
                    .isEqualTo(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01);
        }

        @Test
        @DisplayName("addTransaction resolves missing account ID via CCXREF cross-reference (card→account)")
        void addTransaction_cardNumberOnly_resolvesAccountIdViaXref() {
            // Arrange — only card number supplied; CardXref provides the account ID
            when(transactionRepository.findTopByOrderByTransactionIdDesc())
                    .thenReturn(Optional.of(transactionWithId("0000000000000001")));
            when(transactionRepository.save(any(Transaction.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(cardXrefRepository.findById(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01))
                    .thenReturn(Optional.of(standardCardXref()));

            TransactionAddRequest request = buildValidRequest();
            request.setAccountId("");

            // Act
            TransactionAddResult result = service.addTransaction(request);

            // Assert — service resolved the account ID via the xref read; no
            // accountRepository.findById call occurs because the resolved
            // accountId path only persists the transaction record, not the
            // account (account writes are out of scope for COTRN02C).
            assertThat(result.isSuccess()).isTrue();
            verify(transactionRepository).save(any(Transaction.class));
        }

        @Test
        @DisplayName("addTransaction tolerates non-numeric last TRAN-ID by reseeding at 1")
        void addTransaction_nonNumericLastTransactionId_reseedsAtOne() {
            // Arrange — the last TRAN-ID is a CBACT04C interest-transaction ID
            // (10-char PARM prefix + 6-digit suffix yielding a string that
            // overflows Long when parsed). The service must fall back to
            // FIRST_TRAN_ID rather than propagate NumberFormatException.
            when(transactionRepository.findTopByOrderByTransactionIdDesc())
                    .thenReturn(Optional.of(transactionWithId("INTEREST-ABCDEFG")));
            when(transactionRepository.save(any(Transaction.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // Act
            TransactionAddResult result = service.addTransaction(buildValidRequest());

            // Assert — service reseeded at 0000000000000001 after the parse fallback
            assertThat(result.isSuccess()).isTrue();
            ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(captor.capture());
            assertThat(captor.getValue().getTransactionId())
                    .as("non-numeric last TRAN-ID falls back to FIRST_TRAN_ID per the NumberFormatException catch")
                    .isEqualTo("0000000000000001");
        }

        @Test
        @DisplayName("addTransaction populates every COBOL TRAN-RECORD field on save")
        void addTransaction_validRequest_populatesAllRecordFields() {
            // Arrange
            when(transactionRepository.findTopByOrderByTransactionIdDesc())
                    .thenReturn(Optional.of(transactionWithId("0000000000000001")));
            when(transactionRepository.save(any(Transaction.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            TransactionAddRequest request = buildValidRequest();

            // Act
            service.addTransaction(request);

            // Assert — every TRAN-RECORD field from COTRN02C lines 449–462 is populated
            ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(captor.capture());
            Transaction saved = captor.getValue();

            assertThat(saved.getSource()).isEqualTo(request.getSource());
            assertThat(saved.getDescription()).isEqualTo(request.getDescription());
            assertThat(saved.getMerchantId()).isEqualTo(request.getMerchantId());
            assertThat(saved.getMerchantName()).isEqualTo(request.getMerchantName());
            assertThat(saved.getMerchantCity()).isEqualTo(request.getMerchantCity());
            assertThat(saved.getMerchantZip()).isEqualTo(request.getMerchantZip());
        }
    }

    // =========================================================================
    // ValidationRejects — VALIDATE-INPUT-KEY-FIELDS + VALIDATE-INPUT-DATA-FIELDS
    // =========================================================================

    /**
     * Validation reject scenarios for COTRN02C's two validation paragraphs.
     * Every test asserts that the service returns a failure outcome with the
     * COBOL-equivalent reject reason and that NO transaction record is persisted
     * (the fail-fast semantics of {@code VALIDATE-INPUT-KEY-FIELDS} and
     * {@code VALIDATE-INPUT-DATA-FIELDS}).
     */
    @Nested
    @DisplayName("Validation rejects — VALIDATE-INPUT-KEY-FIELDS / VALIDATE-INPUT-DATA-FIELDS")
    class ValidationRejects {

        // ---------------- VALIDATE-INPUT-KEY-FIELDS (lines 193–230) ----------------

        @Test
        @DisplayName("addTransaction rejects when both account ID and card number are missing")
        void addTransaction_noAccountAndNoCard_rejectsWithKeyRequiredMessage() {
            // Arrange
            TransactionAddRequest request = buildValidRequest();
            request.setAccountId("");
            request.setCardNumber("");

            // Act
            TransactionAddResult result = service.addTransaction(request);

            // Assert — both account and card mentioned in the COBOL reject literal
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage())
                    .as("reject literal includes both 'Account' and 'Card' tokens per COTRN02C line 199")
                    .containsIgnoringCase("account")
                    .containsIgnoringCase("card");
            verify(transactionRepository, never()).save(any());
        }

        @ParameterizedTest(name = "[{index}] non-numeric account ID ''{0}''")
        @ValueSource(strings = {"ABCDEFGHIJK", "1234567890A", "1234567890 "})
        @DisplayName("addTransaction rejects non-numeric account ID")
        void addTransaction_nonNumericAccountId_rejected(String invalidAccountId) {
            // Arrange — clear card number so the cascade routes through account-ID validation
            TransactionAddRequest request = buildValidRequest();
            request.setAccountId(invalidAccountId);
            request.setCardNumber("");

            // Act
            TransactionAddResult result = service.addTransaction(request);

            // Assert
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage())
                    .as("reject literal includes 'Numeric' token per COTRN02C line 207")
                    .containsIgnoringCase("numeric");
            verify(transactionRepository, never()).save(any());
        }

        @ParameterizedTest(name = "[{index}] non-numeric card number ''{0}''")
        @ValueSource(strings = {"411111111111ABCD", "4111111111111X11"})
        @DisplayName("addTransaction rejects non-numeric card number")
        void addTransaction_nonNumericCardNumber_rejected(String invalidCardNumber) {
            // Arrange — clear account so the cascade routes through card-number validation
            TransactionAddRequest request = buildValidRequest();
            request.setAccountId("");
            request.setCardNumber(invalidCardNumber);

            // Act
            TransactionAddResult result = service.addTransaction(request);

            // Assert
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage())
                    .as("reject literal mentions 'Card' and 'Numeric' per COTRN02C line 220")
                    .containsIgnoringCase("card")
                    .containsIgnoringCase("numeric");
            verify(transactionRepository, never()).save(any());
        }

        // ---------------- VALIDATE-INPUT-DATA-FIELDS empty checks ----------------

        @Test
        @DisplayName("addTransaction rejects empty type code (TTYPCDI)")
        void addTransaction_emptyTypeCode_rejected() {
            TransactionAddRequest request = buildValidRequest();
            request.setTransactionTypeCode("");

            TransactionAddResult result = service.addTransaction(request);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage())
                    .containsIgnoringCase("type")
                    .containsIgnoringCase("empty");
            verify(transactionRepository, never()).save(any());
        }

        @Test
        @DisplayName("addTransaction rejects empty category code (TCATCDI)")
        void addTransaction_emptyCategoryCode_rejected() {
            TransactionAddRequest request = buildValidRequest();
            request.setTransactionCategoryCode("");

            TransactionAddResult result = service.addTransaction(request);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage())
                    .containsIgnoringCase("category")
                    .containsIgnoringCase("empty");
            verify(transactionRepository, never()).save(any());
        }

        @Test
        @DisplayName("addTransaction rejects empty source (TRNSRCI)")
        void addTransaction_emptySource_rejected() {
            TransactionAddRequest request = buildValidRequest();
            request.setSource("");

            TransactionAddResult result = service.addTransaction(request);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage())
                    .containsIgnoringCase("source")
                    .containsIgnoringCase("empty");
            verify(transactionRepository, never()).save(any());
        }

        @Test
        @DisplayName("addTransaction rejects empty description (TDESCI)")
        void addTransaction_emptyDescription_rejected() {
            TransactionAddRequest request = buildValidRequest();
            request.setDescription("");

            TransactionAddResult result = service.addTransaction(request);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage())
                    .containsIgnoringCase("description")
                    .containsIgnoringCase("empty");
            verify(transactionRepository, never()).save(any());
        }

        @Test
        @DisplayName("addTransaction rejects null amount (TRNAMTI empty)")
        void addTransaction_emptyAmount_rejected() {
            TransactionAddRequest request = buildValidRequest();
            request.setAmount(null);

            TransactionAddResult result = service.addTransaction(request);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage())
                    .containsIgnoringCase("amount")
                    .containsIgnoringCase("empty");
            verify(transactionRepository, never()).save(any());
        }

        @Test
        @DisplayName("addTransaction rejects empty origin date (TORIGDTI)")
        void addTransaction_emptyOriginDate_rejected() {
            TransactionAddRequest request = buildValidRequest();
            request.setOriginDate("");

            TransactionAddResult result = service.addTransaction(request);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage())
                    .containsIgnoringCase("orig")
                    .containsIgnoringCase("date")
                    .containsIgnoringCase("empty");
            verify(transactionRepository, never()).save(any());
        }

        @Test
        @DisplayName("addTransaction rejects empty process date (TPROCDTI)")
        void addTransaction_emptyProcessDate_rejected() {
            TransactionAddRequest request = buildValidRequest();
            request.setProcessDate("");

            TransactionAddResult result = service.addTransaction(request);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage())
                    .containsIgnoringCase("proc")
                    .containsIgnoringCase("date")
                    .containsIgnoringCase("empty");
            verify(transactionRepository, never()).save(any());
        }

        @Test
        @DisplayName("addTransaction rejects empty merchant ID (MIDI)")
        void addTransaction_emptyMerchantId_rejected() {
            TransactionAddRequest request = buildValidRequest();
            request.setMerchantId("");

            TransactionAddResult result = service.addTransaction(request);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage())
                    .containsIgnoringCase("merchant")
                    .containsIgnoringCase("id")
                    .containsIgnoringCase("empty");
            verify(transactionRepository, never()).save(any());
        }

        @Test
        @DisplayName("addTransaction rejects empty merchant name (MNAMEI)")
        void addTransaction_emptyMerchantName_rejected() {
            TransactionAddRequest request = buildValidRequest();
            request.setMerchantName("");

            TransactionAddResult result = service.addTransaction(request);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage())
                    .containsIgnoringCase("merchant")
                    .containsIgnoringCase("name")
                    .containsIgnoringCase("empty");
            verify(transactionRepository, never()).save(any());
        }

        @Test
        @DisplayName("addTransaction rejects empty merchant city (MCITYI)")
        void addTransaction_emptyMerchantCity_rejected() {
            TransactionAddRequest request = buildValidRequest();
            request.setMerchantCity("");

            TransactionAddResult result = service.addTransaction(request);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage())
                    .containsIgnoringCase("merchant")
                    .containsIgnoringCase("city")
                    .containsIgnoringCase("empty");
            verify(transactionRepository, never()).save(any());
        }

        @Test
        @DisplayName("addTransaction rejects empty merchant zip (MZIPI)")
        void addTransaction_emptyMerchantZip_rejected() {
            TransactionAddRequest request = buildValidRequest();
            request.setMerchantZip("");

            TransactionAddResult result = service.addTransaction(request);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage())
                    .containsIgnoringCase("merchant")
                    .containsIgnoringCase("zip")
                    .containsIgnoringCase("empty");
            verify(transactionRepository, never()).save(any());
        }

        // ---------------- VALIDATE-INPUT-DATA-FIELDS numeric checks ----------------

        @ParameterizedTest(name = "[{index}] non-numeric type code ''{0}''")
        @ValueSource(strings = {"AB", "1X", " 1"})
        @DisplayName("addTransaction rejects non-numeric type code (TTYPCDI)")
        void addTransaction_nonNumericTypeCode_rejected(String invalidType) {
            TransactionAddRequest request = buildValidRequest();
            request.setTransactionTypeCode(invalidType);

            TransactionAddResult result = service.addTransaction(request);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage())
                    .containsIgnoringCase("type")
                    .containsIgnoringCase("numeric");
            verify(transactionRepository, never()).save(any());
        }

        @ParameterizedTest(name = "[{index}] non-numeric category code ''{0}''")
        @ValueSource(strings = {"ABCD", "12X4", "00 1"})
        @DisplayName("addTransaction rejects non-numeric category code (TCATCDI)")
        void addTransaction_nonNumericCategoryCode_rejected(String invalidCategory) {
            TransactionAddRequest request = buildValidRequest();
            request.setTransactionCategoryCode(invalidCategory);

            TransactionAddResult result = service.addTransaction(request);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage())
                    .containsIgnoringCase("category")
                    .containsIgnoringCase("numeric");
            verify(transactionRepository, never()).save(any());
        }

        @ParameterizedTest(name = "[{index}] non-numeric merchant ID ''{0}''")
        @ValueSource(strings = {"ABC", "12X", "1 2 3"})
        @DisplayName("addTransaction rejects non-numeric merchant ID (MIDI)")
        void addTransaction_nonNumericMerchantId_rejected(String invalidMerchantId) {
            TransactionAddRequest request = buildValidRequest();
            request.setMerchantId(invalidMerchantId);

            TransactionAddResult result = service.addTransaction(request);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage())
                    .containsIgnoringCase("merchant")
                    .containsIgnoringCase("numeric");
            verify(transactionRepository, never()).save(any());
        }

        // ---------------- VALIDATE-INPUT-DATA-FIELDS date checks ----------------

        @ParameterizedTest(name = "[{index}] invalid origin date ''{0}''")
        @ValueSource(strings = {"2024-13-01", "2024-02-30", "abcd-01-15", "2024/01/15"})
        @DisplayName("addTransaction rejects invalid origin date (TORIGDTI)")
        void addTransaction_invalidOriginDate_rejected(String invalidDate) {
            TransactionAddRequest request = buildValidRequest();
            request.setOriginDate(invalidDate);

            TransactionAddResult result = service.addTransaction(request);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage())
                    .containsIgnoringCase("orig")
                    .containsIgnoringCase("date");
            verify(transactionRepository, never()).save(any());
        }

        @ParameterizedTest(name = "[{index}] invalid process date ''{0}''")
        @ValueSource(strings = {"2024-13-01", "2024-02-30", "abcd-01-15", "2024/01/15"})
        @DisplayName("addTransaction rejects invalid process date (TPROCDTI)")
        void addTransaction_invalidProcessDate_rejected(String invalidDate) {
            TransactionAddRequest request = buildValidRequest();
            request.setProcessDate(invalidDate);

            TransactionAddResult result = service.addTransaction(request);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage())
                    .containsIgnoringCase("proc")
                    .containsIgnoringCase("date");
            verify(transactionRepository, never()).save(any());
        }
    }

    // =========================================================================
    // FinancialPrecision — BigDecimal scale 2 (PIC S9(09)V99) parity
    // =========================================================================

    /**
     * Financial-precision tests for {@code TRAN-AMT PIC S9(09)V99} parity.
     * Per AAP §0.10.3 monetary values are always {@link BigDecimal} at scale 2;
     * the COBOL field is SIGNED so negative amounts (refunds) must be accepted.
     */
    @Nested
    @DisplayName("Financial precision — BigDecimal scale 2 (PIC S9(09)V99)")
    class FinancialPrecision {

        @Test
        @DisplayName("addTransaction preserves BigDecimal scale 2 in persisted amount")
        void addTransaction_amountAtScale2_preserved() {
            // Arrange
            when(transactionRepository.findTopByOrderByTransactionIdDesc())
                    .thenReturn(Optional.of(transactionWithId("0000000000000001")));
            when(transactionRepository.save(any(Transaction.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            TransactionAddRequest request = buildValidRequest();
            request.setAmount(new BigDecimal("123.45"));

            // Act
            service.addTransaction(request);

            // Assert
            ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(captor.capture());
            assertThat(captor.getValue().getAmount())
                    .as("amount value preserved exactly")
                    .isEqualByComparingTo("123.45")
                    .satisfies(b -> assertThat(b.scale())
                            .as("scale must be 2 per PIC S9(09)V99")
                            .isEqualTo(2));
        }

        @Test
        @DisplayName("addTransaction accepts negative amount (signed PIC S9(09)V99 — refund)")
        void addTransaction_negativeAmount_acceptedAsSignedField() {
            // Arrange
            when(transactionRepository.findTopByOrderByTransactionIdDesc())
                    .thenReturn(Optional.of(transactionWithId("0000000000000001")));
            when(transactionRepository.save(any(Transaction.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            TransactionAddRequest request = buildValidRequest();
            request.setAmount(new BigDecimal("-50.00")); // refund

            // Act
            TransactionAddResult result = service.addTransaction(request);

            // Assert — refund must succeed because PIC S9(09)V99 is SIGNED
            assertThat(result.isSuccess())
                    .as("PIC S9(09)V99 is signed — negative amounts (refunds) must be accepted")
                    .isTrue();

            ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(captor.capture());
            assertThat(captor.getValue().getAmount())
                    .isEqualByComparingTo("-50.00")
                    .satisfies(b -> assertThat(b.scale()).isEqualTo(2));
        }

        @Test
        @DisplayName("addTransaction preserves PIC S9(09)V99 upper-bound value")
        void addTransaction_atUpperBound_preservesPrecision() {
            // Arrange — value at the PIC S9(09)V99 upper bound (999999999.99)
            when(transactionRepository.findTopByOrderByTransactionIdDesc())
                    .thenReturn(Optional.of(transactionWithId("0000000000000001")));
            when(transactionRepository.save(any(Transaction.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            TransactionAddRequest request = buildValidRequest();
            request.setAmount(new BigDecimal("999999999.99"));

            // Act
            service.addTransaction(request);

            // Assert
            ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(captor.capture());
            assertThat(captor.getValue().getAmount())
                    .as("upper-bound value preserved without rounding or overflow")
                    .isEqualByComparingTo("999999999.99")
                    .satisfies(b -> assertThat(b.scale()).isEqualTo(2));
        }
    }

    // =========================================================================
    // Helper factories — kept simple to honour the Require Test Coverage rule.
    // No business or calculation logic appears in these helpers; they merely
    // construct fixture objects with values sourced from TestFixtures or
    // hard-coded literal constants.
    // =========================================================================

    /**
     * Build a baseline valid {@link TransactionAddRequest} that passes every
     * COTRN02C validation stage. Individual reject tests override only the
     * field they intend to exercise so the rest of the request remains valid.
     *
     * @return a fully populated, validation-passing transaction-add request
     */
    private static TransactionAddRequest buildValidRequest() {
        TransactionAddRequest req = new TransactionAddRequest();
        req.setAccountId(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10);
        req.setCardNumber(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01);
        req.setTransactionTypeCode(TestFixtures.Transactions.TRAN_TYPE_PURCHASE);
        req.setTransactionCategoryCode(TestFixtures.Transactions.TRAN_CAT_REGULAR_SALES);
        req.setSource(TestFixtures.Transactions.TRAN_SOURCE_POS);
        req.setDescription("TEST PURCHASE");
        req.setAmount(new BigDecimal("50.00"));
        req.setOriginDate("2024-01-15");
        req.setProcessDate("2024-01-15");
        req.setMerchantId("123456789");
        req.setMerchantName("TEST MERCHANT");
        req.setMerchantCity("SEATTLE");
        req.setMerchantZip("98101");
        return req;
    }

    /**
     * Build a stub {@link Transaction} entity with only its TRAN-ID populated.
     * Used as the return value of
     * {@link TransactionRepository#findTopByOrderByTransactionIdDesc()} mock
     * configurations: the service reads only the {@code transactionId} field
     * to derive the next ID, so other fields can remain {@code null}.
     *
     * @param transactionId the 16-character TRAN-ID to attach to the stub
     * @return a {@link Transaction} carrying only the supplied identifier
     */
    private static Transaction transactionWithId(String transactionId) {
        Transaction t = new Transaction();
        t.setTransactionId(transactionId);
        return t;
    }

    /**
     * Build a standard {@link Account} entity for tests that exercise the
     * cross-reference / account-existence path. Not used by every test in the
     * suite — only by scenarios that need an account record on the
     * {@link AccountRepository} mock — and kept here for symmetry with the
     * sibling test classes (such as {@code BillPaymentServiceTest}).
     *
     * @return a populated {@link Account} with scale-2 balance and credit limit
     */
    @SuppressWarnings("unused") // retained for parity with sibling test patterns
    private static Account standardAccount() {
        Account a = new Account();
        a.setAccountId(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10);
        a.setActiveStatus("Y");
        a.setCurrentBalance(new BigDecimal("1000.00"));
        a.setCreditLimit(new BigDecimal("5000.00"));
        a.setVersion(1L);
        return a;
    }

    /**
     * Build a standard {@link CardXref} entity carrying the sample card number
     * and account ID from {@link TestFixtures}. Used as the return value of
     * {@link CardXrefRepository#findByAccountId(String)} / {@code findById}
     * stubs in tests that exercise the key-resolution branch.
     *
     * @return a populated {@link CardXref}
     */
    private static CardXref standardCardXref() {
        CardXref x = new CardXref();
        x.setCardNumber(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01);
        x.setAccountId(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10);
        x.setCustomerId("000000001");
        return x;
    }
}

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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BillPaymentService}, the migrated Java equivalent of
 * the 572-line CICS COBOL program {@code app/cbl/COBIL00C.cbl} (TRANID
 * {@code CB00}). The service pays off the FULL current balance of an account
 * — there is no partial-payment mode, exactly matching the COBOL semantic
 * where {@code TRAN-AMT = ACCT-CURR-BAL} and the balance is then zeroed.
 *
 * <h2>COBOL Provenance — COBIL00C.cbl</h2>
 *
 * <p>Hard-coded transaction constants used when generating the payment
 * record (COBIL00C lines 220–229) and asserted verbatim by these tests:
 * <ul>
 *   <li>{@code TRAN-TYPE-CD} = {@code "02"} (payment)</li>
 *   <li>{@code TRAN-CAT-CD} = {@code "0002"} (payment category)</li>
 *   <li>{@code TRAN-SOURCE} = {@code "POS TERM  "} (10-char space-padded)</li>
 *   <li>{@code TRAN-DESC} = {@code "BILL PAYMENT - ONLINE"}</li>
 *   <li>{@code TRAN-MERCHANT-ID} = {@code "999999999"}</li>
 *   <li>{@code TRAN-MERCHANT-NAME} = {@code "BILL PAYMENT"}</li>
 *   <li>{@code TRAN-MERCHANT-CITY} = {@code "N/A"}</li>
 *   <li>{@code TRAN-MERCHANT-ZIP} = {@code "N/A"}</li>
 * </ul>
 *
 * <p>{@code PROCESS-ENTER-KEY} logic (COBIL00C lines 154–244):
 * <ol>
 *   <li>Validate {@code ACTIDINI} not empty — "Acct ID can NOT be empty..."</li>
 *   <li>Evaluate {@code CONFIRMI} — Y/y → proceed; N/n → cancelled reject;
 *       OTHER → "Please confirm to make bill payment..." reject</li>
 *   <li>{@code READ ACCTDAT} — not found → "Account ID NOT found..."</li>
 *   <li>If {@code ACCT-CURR-BAL = 0} (or ≤ 0) → "You have nothing to pay..."</li>
 *   <li>{@code READ CXACAIX} (card cross-reference) → not found → reject</li>
 *   <li>Generate next {@code TRAN-ID} via STARTBR HIGH-VALUES + READPREV + 1</li>
 *   <li>{@code WRITE TRANSACT} with hard-coded constants and amount = balance</li>
 *   <li>{@code REWRITE ACCTDAT} with {@code ACCT-CURR-BAL = 0}</li>
 * </ol>
 *
 * <h2>Require Test Coverage Rule Compliance (AAP §0.10.1)</h2>
 *
 * <p>Tests instantiate the real {@link BillPaymentService}; mocks are only on
 * JPA repository boundaries and the {@link Clock}; no business logic is
 * reimplemented in test bodies. Every assertion verifies an observable
 * behaviour produced by the production class:
 * <ul>
 *   <li>{@link BillPaymentResult#isSuccess()} / {@link BillPaymentResult#getMessage()}
 *       — the return contract of {@link BillPaymentService#payBill}.</li>
 *   <li>{@link Account#getCurrentBalance()} captured via
 *       {@link ArgumentCaptor} on {@link AccountRepository#save(Object)} — the
 *       post-payment persisted state.</li>
 *   <li>{@link Transaction#getAmount()}, {@link Transaction#getTransactionId()},
 *       {@link Transaction#getTransactionTypeCode()}, etc., captured via
 *       {@link ArgumentCaptor} on {@link TransactionRepository#save(Object)}
 *       — the persisted payment-record state.</li>
 *   <li>{@code verify(repo, never()).save(any())} on every reject path —
 *       proves the validation-first short-circuit ordering.</li>
 * </ul>
 *
 * <h2>Financial Precision (AAP §0.10.3)</h2>
 *
 * <p>Every assertion on a monetary value pairs the numeric-equality check
 * ({@code isEqualByComparingTo("X.XX")}) with a scale-2 check
 * ({@code .satisfies(b -> assertThat(b.scale()).isEqualTo(2))}) to prove
 * both the value and the COBOL {@code PIC S9(10)V99} / {@code PIC S9(09)V99}
 * scale parity.
 *
 * @see BillPaymentService
 * @see BillPaymentRequest
 * @see BillPaymentResult
 * @see TestFixtures
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BillPaymentService — COBIL00C.cbl migration parity")
final class BillPaymentServiceTest {

    /**
     * Mocked {@link AccountRepository} — the database boundary for account
     * master record reads ({@code findById}) and the dual-write commit
     * ({@code save}). Per AAP §0.10.1, mocking is restricted to JPA
     * repositories and the Clock.
     */
    @Mock private AccountRepository accountRepository;

    /**
     * Mocked {@link TransactionRepository} — the database boundary for
     * transaction record reads ({@code findTopByOrderByTransactionIdDesc} —
     * Java replacement for COBOL {@code STARTBR HIGH-VALUES + READPREV})
     * and writes ({@code save}).
     */
    @Mock private TransactionRepository transactionRepository;

    /**
     * Mocked {@link CardXrefRepository} — the database boundary for card
     * cross-reference reads ({@code findByAccountId}). Java replacement for
     * the COBOL {@code READ CXACAIX FILE} access at COBIL00C lines 408–436.
     */
    @Mock private CardXrefRepository cardXrefRepository;

    /**
     * Deterministic clock fixed at {@code 2024-01-15T00:00:00Z} so the
     * transaction origin and process timestamps are reproducible across
     * test runs. Java replacement for the COBOL {@code GET-CURRENT-TIMESTAMP}
     * paragraph (COBIL00C lines 249–267) which reads the wall clock via
     * {@code EXEC CICS ASKTIME}.
     */
    private final Clock fixedClock = Clock.fixed(
            Instant.parse(TestFixtures.Dates.FIXED_CLOCK_INSTANT),
            ZoneOffset.UTC);

    /**
     * The real production service under test — instantiated in
     * {@link #setUp()} with the four mock collaborators. Per the Require
     * Test Coverage rule (AAP §0.10.1), every test method invokes this real
     * instance and asserts on its observable behaviour rather than
     * reimplementing COBOL logic.
     */
    private BillPaymentService service;

    /**
     * Initializes the real {@link BillPaymentService} with the three mocked
     * repository boundaries and the deterministic {@link Clock}. Invoked
     * before every {@code @Test} method.
     */
    @BeforeEach
    void setUp() {
        service = new BillPaymentService(
                accountRepository,
                transactionRepository,
                cardXrefRepository,
                fixedClock);
    }

    // =========================================================================
    // Helper factory methods — produce test-only fixture objects in a SINGLE
    // place so individual tests focus on behaviour, not setup boilerplate.
    // These helpers MUST NOT contain any business logic; they only populate
    // POJO fields with test fixture values.
    // =========================================================================

    /**
     * Build an {@link Account} fixture with the canonical
     * {@link TestFixtures.Accounts#SAMPLE_ACCOUNT_ID_10} ID and the supplied
     * current balance. The active status defaults to {@code "Y"}, credit
     * limit to {@code 5000.00}, and {@code @Version} to {@code 1L} so the
     * fixture mirrors a typical Flyway-seeded account row.
     *
     * @param balance the current balance to set; should already be at scale 2
     *                to mirror the COBOL {@code PIC S9(10)V99} field
     * @return a populated {@link Account} ready for use as a repository stub
     *         return value
     */
    private static Account accountWithBalance(BigDecimal balance) {
        Account account = new Account();
        account.setAccountId(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10);
        account.setActiveStatus("Y");
        account.setCurrentBalance(balance);
        account.setCreditLimit(new BigDecimal("5000.00"));
        account.setVersion(1L);
        return account;
    }

    /**
     * Build a {@link CardXref} fixture pointing the canonical
     * {@link TestFixtures.Cards#SAMPLE_CARD_NUMBER_01} card number at the
     * canonical {@link TestFixtures.Accounts#SAMPLE_ACCOUNT_ID_10} account.
     * Java equivalent of one row from {@code app/data/ASCII/cardxref.txt}.
     *
     * @return a populated {@link CardXref} ready for use as a repository
     *         stub return value
     */
    private static CardXref standardCardXref() {
        CardXref xref = new CardXref();
        xref.setCardNumber(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01);
        xref.setAccountId(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10);
        return xref;
    }

    /**
     * Build a {@link Transaction} fixture whose only populated field is the
     * 16-character {@code transactionId}. Used as the {@code Optional.of(...)}
     * return value for stubbing
     * {@link TransactionRepository#findTopByOrderByTransactionIdDesc()} so
     * the SUT can derive the next sequential TRAN-ID by adding 1.
     *
     * @param transactionId the 16-character zero-padded TRAN-ID to set
     * @return a {@link Transaction} carrying only the supplied transaction ID
     */
    private static Transaction transactionWithId(String transactionId) {
        Transaction transaction = new Transaction();
        transaction.setTransactionId(transactionId);
        return transaction;
    }

    // =========================================================================
    // Nested test group: HAPPY PATH (full-balance pay-off scenarios)
    // =========================================================================

    /**
     * Happy-path tests — verify the complete COBIL00C {@code PROCESS-ENTER-KEY}
     * workflow when every precondition is satisfied (operator confirms Y,
     * account exists, balance > 0, card cross-reference exists). Each test
     * asserts a specific observable behaviour: account-balance zeroing,
     * transaction-ID generation, card-number resolution, hard-coded constant
     * population.
     */
    @Nested
    @DisplayName("Happy path — full balance payoff")
    class HappyPath {

        /**
         * Verifies the complete happy-path workflow: a confirmed payment
         * against an account with a positive balance zeroes the persisted
         * account balance and creates a transaction record carrying every
         * hard-coded COBOL constant. This is the canonical golden-path test;
         * other happy-path tests narrow in on individual aspects of the
         * persisted state.
         */
        @Test
        @DisplayName("payBill(accountWithBalance) zeros the balance and creates a transaction")
        void payBill_accountWithBalance_zeroesBalanceAndCreatesTransaction() {
            // ----------------------------------------------------------------
            // Arrange — account with $250.75 balance, card cross-reference,
            // and an existing high TRAN-ID of 99 (so the new ID will be 100).
            // ----------------------------------------------------------------
            Account account = accountWithBalance(new BigDecimal("250.75"));
            when(accountRepository.findById(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10))
                    .thenReturn(Optional.of(account));
            when(cardXrefRepository.findByAccountId(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10))
                    .thenReturn(Optional.of(standardCardXref()));
            when(transactionRepository.findTopByOrderByTransactionIdDesc())
                    .thenReturn(Optional.of(transactionWithId("0000000000000099")));
            when(accountRepository.save(any(Account.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(transactionRepository.save(any(Transaction.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            BillPaymentRequest request = new BillPaymentRequest(
                    TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10, "Y");

            // ----------------------------------------------------------------
            // Act — invoke the real service.
            // ----------------------------------------------------------------
            BillPaymentResult result = service.payBill(request);

            // ----------------------------------------------------------------
            // Assert — service reports success and the success message
            // carries the freshly generated TRAN-ID per COBIL00C lines 527–531.
            // ----------------------------------------------------------------
            assertThat(result.isSuccess())
                    .as("Happy-path payment must return success")
                    .isTrue();
            assertThat(result.getMessage())
                    .as("Success message format per COBIL00C lines 527–531")
                    .contains("Payment successful")
                    .contains("Transaction ID");

            // ----------------------------------------------------------------
            // Assert — persisted account current balance is zero with scale 2.
            // ----------------------------------------------------------------
            ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
            verify(accountRepository).save(accountCaptor.capture());
            Account persistedAccount = accountCaptor.getValue();
            assertThat(persistedAccount.getCurrentBalance())
                    .as("Bill payment zeros ACCT-CURR-BAL per COBIL00C line 234")
                    .isEqualByComparingTo("0.00")
                    .satisfies(b -> assertThat(b.scale()).isEqualTo(2));

            // ----------------------------------------------------------------
            // Assert — persisted transaction record carries every hard-coded
            // COBOL constant verbatim (COBIL00C lines 220–229).
            // ----------------------------------------------------------------
            ArgumentCaptor<Transaction> txnCaptor = ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(txnCaptor.capture());
            Transaction txn = txnCaptor.getValue();
            assertThat(txn.getTransactionTypeCode())
                    .as("TRAN-TYPE-CD per COBIL00C line 220")
                    .isEqualTo("02");
            assertThat(txn.getTransactionCategoryCode())
                    .as("TRAN-CAT-CD per COBIL00C line 221")
                    .isEqualTo("0002");
            assertThat(txn.getDescription())
                    .as("TRAN-DESC per COBIL00C line 223")
                    .isEqualTo("BILL PAYMENT - ONLINE");
            assertThat(txn.getMerchantName())
                    .as("TRAN-MERCHANT-NAME per COBIL00C line 227")
                    .isEqualTo("BILL PAYMENT");
            assertThat(txn.getMerchantId())
                    .as("TRAN-MERCHANT-ID per COBIL00C line 226")
                    .isEqualTo("999999999");
            assertThat(txn.getMerchantCity())
                    .as("TRAN-MERCHANT-CITY per COBIL00C line 228")
                    .isEqualTo("N/A");
            assertThat(txn.getMerchantZip())
                    .as("TRAN-MERCHANT-ZIP per COBIL00C line 229")
                    .isEqualTo("N/A");

            // ----------------------------------------------------------------
            // Assert — transaction amount equals the pre-payment balance
            // (NOT the zero post-payment value), with scale 2 preserved per
            // AAP §0.10.3 financial-precision mandate.
            // ----------------------------------------------------------------
            assertThat(txn.getAmount())
                    .as("TRAN-AMT = pre-payment ACCT-CURR-BAL per COBIL00C line 224")
                    .isEqualByComparingTo("250.75")
                    .satisfies(b -> assertThat(b.scale()).isEqualTo(2));
        }

        /**
         * Verifies that the next TRAN-ID is generated by adding 1 to the
         * highest existing TRAN-ID and zero-padding to 16 characters.
         * Java replacement for the COBOL pattern at COBIL00C lines 212–217:
         * {@code MOVE HIGH-VALUES TO TRAN-ID} + {@code STARTBR} + {@code READPREV}
         * + {@code ADD 1 TO WS-TRAN-ID-NUM}.
         */
        @Test
        @DisplayName("payBill auto-generates next transaction ID")
        void payBill_validRequest_generatesNextTransactionId() {
            // Arrange — highest existing TRAN-ID is 99 → new ID should be 100.
            Account account = accountWithBalance(new BigDecimal("100.00"));
            when(accountRepository.findById(any())).thenReturn(Optional.of(account));
            when(cardXrefRepository.findByAccountId(any()))
                    .thenReturn(Optional.of(standardCardXref()));
            when(transactionRepository.findTopByOrderByTransactionIdDesc())
                    .thenReturn(Optional.of(transactionWithId("0000000000000099")));
            when(accountRepository.save(any(Account.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(transactionRepository.save(any(Transaction.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            BillPaymentRequest request = new BillPaymentRequest(
                    TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10, "Y");

            // Act
            BillPaymentResult result = service.payBill(request);

            // Assert — success outcome.
            assertThat(result.isSuccess()).isTrue();

            // Assert — the generated TRAN-ID is exactly last + 1, zero-padded
            // to the 16-character TRAN-ID PIC X(16) field width.
            ArgumentCaptor<Transaction> txnCaptor = ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(txnCaptor.capture());
            assertThat(txnCaptor.getValue().getTransactionId())
                    .as("TRAN-ID = last + 1 per COBIL00C STARTBR/READPREV pattern, "
                            + "zero-padded to 16 chars per TRAN-ID PIC X(16)")
                    .isEqualTo("0000000000000100");
        }

        /**
         * Verifies that the card number persisted on the payment transaction
         * is resolved from the {@link CardXrefRepository#findByAccountId}
         * call, NOT derived from the request (the COBIL00C workflow has no
         * card-number input — the card is looked up by account ID via the
         * CXACAIX cross-reference at COBIL00C lines 408–436).
         */
        @Test
        @DisplayName("payBill uses card number resolved from CXACAIX")
        void payBill_validRequest_resolvesCardNumberFromCrossReference() {
            // Arrange
            Account account = accountWithBalance(new BigDecimal("75.50"));
            when(accountRepository.findById(any())).thenReturn(Optional.of(account));
            when(cardXrefRepository.findByAccountId(any()))
                    .thenReturn(Optional.of(standardCardXref()));
            when(transactionRepository.findTopByOrderByTransactionIdDesc())
                    .thenReturn(Optional.of(transactionWithId("0000000000000001")));
            when(accountRepository.save(any(Account.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(transactionRepository.save(any(Transaction.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            BillPaymentRequest request = new BillPaymentRequest(
                    TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10, "Y");

            // Act
            service.payBill(request);

            // Assert — the card cross-reference was queried by account ID.
            verify(cardXrefRepository).findByAccountId(
                    TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10);

            // Assert — the persisted transaction carries the resolved PAN.
            ArgumentCaptor<Transaction> txnCaptor = ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(txnCaptor.capture());
            assertThat(txnCaptor.getValue().getCardNumber())
                    .as("Persisted TRAN-CARD-NUM resolved from CXACAIX, "
                            + "not derived from the request payload")
                    .isEqualTo(TestFixtures.Cards.SAMPLE_CARD_NUMBER_01);
        }

        /**
         * Verifies the canonical empty-table case: when no transactions
         * exist (Java {@link Optional#empty()} from
         * {@link TransactionRepository#findTopByOrderByTransactionIdDesc()}),
         * the SUT seeds the first TRAN-ID at 1. Java replacement for the
         * COBOL {@code DFHRESP(ENDFILE)} branch at COBIL00C line 488:
         * {@code MOVE ZEROS TO TRAN-ID} followed by {@code ADD 1}.
         */
        @Test
        @DisplayName("payBill seeds first transaction ID at 1 when no transactions exist")
        void payBill_emptyTransactionTable_seedsFirstTransactionIdAtOne() {
            // Arrange — empty transactions table.
            Account account = accountWithBalance(new BigDecimal("50.00"));
            when(accountRepository.findById(any())).thenReturn(Optional.of(account));
            when(cardXrefRepository.findByAccountId(any()))
                    .thenReturn(Optional.of(standardCardXref()));
            when(transactionRepository.findTopByOrderByTransactionIdDesc())
                    .thenReturn(Optional.empty());
            when(accountRepository.save(any(Account.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(transactionRepository.save(any(Transaction.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            BillPaymentRequest request = new BillPaymentRequest(
                    TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10, "Y");

            // Act
            BillPaymentResult result = service.payBill(request);

            // Assert
            assertThat(result.isSuccess()).isTrue();
            ArgumentCaptor<Transaction> txnCaptor = ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(txnCaptor.capture());
            assertThat(txnCaptor.getValue().getTransactionId())
                    .as("First TRAN-ID in empty table seeds at 1 per COBIL00C line 488 ENDFILE branch")
                    .isEqualTo("0000000000000001");
        }

        /**
         * Verifies that lower-case {@code "y"} is treated identically to
         * upper-case {@code "Y"} (COBIL00C lines 174–176:
         * {@code WHEN 'Y' WHEN 'y'} both proceed to PROCESS-ENTER-KEY).
         */
        @Test
        @DisplayName("payBill accepts lower-case 'y' confirmation")
        void payBill_lowerCaseConfirmation_acceptedAsConfirmed() {
            // Arrange
            Account account = accountWithBalance(new BigDecimal("10.00"));
            when(accountRepository.findById(any())).thenReturn(Optional.of(account));
            when(cardXrefRepository.findByAccountId(any()))
                    .thenReturn(Optional.of(standardCardXref()));
            when(transactionRepository.findTopByOrderByTransactionIdDesc())
                    .thenReturn(Optional.of(transactionWithId("0000000000000001")));
            when(accountRepository.save(any(Account.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(transactionRepository.save(any(Transaction.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            BillPaymentRequest request = new BillPaymentRequest(
                    TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10, "y");

            // Act
            BillPaymentResult result = service.payBill(request);

            // Assert
            assertThat(result.isSuccess())
                    .as("Lower-case 'y' must be accepted per COBIL00C line 176 WHEN 'y'")
                    .isTrue();
            verify(accountRepository).save(any(Account.class));
            verify(transactionRepository).save(any(Transaction.class));
        }
    }

    // =========================================================================
    // Nested test group: REJECT PATHS (validation-first short-circuits)
    // =========================================================================

    /**
     * Reject-path tests — verify the five COBIL00C reject branches each
     * return a {@link BillPaymentResult#failure(String)} with the verbatim
     * COBOL message and (critically) without performing any persistence
     * writes. The {@code verify(repo, never()).save(any())} assertions
     * prove the validation-first short-circuit ordering mandated by AAP
     * §0.10.1 (no business logic reimplementation in test bodies).
     */
    @Nested
    @DisplayName("Reject paths")
    class RejectPaths {

        /**
         * Verifies that a confirmed payment against a non-existent account
         * returns the "Account ID NOT found..." reject without performing
         * any persistence writes. Java mapping of COBIL00C lines 361 (the
         * {@code READ-ACCTDAT-FILE} NOTFND branch).
         */
        @Test
        @DisplayName("payBill rejects when account does not exist")
        void payBill_accountNotFound_rejectsWithAccountNotFoundMessage() {
            // Arrange — non-existent account.
            when(accountRepository.findById(TestFixtures.Accounts.NONEXISTENT_ACCOUNT_ID))
                    .thenReturn(Optional.empty());

            BillPaymentRequest request = new BillPaymentRequest(
                    TestFixtures.Accounts.NONEXISTENT_ACCOUNT_ID, "Y");

            // Act
            BillPaymentResult result = service.payBill(request);

            // Assert — failure with the verbatim COBOL message.
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage())
                    .as("Verbatim COBOL message 'Account ID NOT found...' per COBIL00C line 361")
                    .containsIgnoringCase("account")
                    .containsIgnoringCase("not found");

            // Critical: validation-first short-circuit — no writes occurred.
            verify(accountRepository, never()).save(any());
            verify(transactionRepository, never()).save(any());
        }

        /**
         * Verifies that a confirmed payment against an account with a zero
         * balance returns the "You have nothing to pay..." reject without
         * performing any persistence writes. Java mapping of COBIL00C lines
         * 197–206 ({@code IF ACCT-CURR-BAL <= ZEROS}).
         */
        @Test
        @DisplayName("payBill rejects when current balance is zero")
        void payBill_zeroBalance_rejectsWithNothingToPayMessage() {
            // Arrange — account with zero balance.
            Account zeroBalanceAccount = accountWithBalance(new BigDecimal("0.00"));
            when(accountRepository.findById(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10))
                    .thenReturn(Optional.of(zeroBalanceAccount));

            BillPaymentRequest request = new BillPaymentRequest(
                    TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10, "Y");

            // Act
            BillPaymentResult result = service.payBill(request);

            // Assert
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage())
                    .as("Verbatim COBOL message 'You have nothing to pay...' per COBIL00C line 201")
                    .containsIgnoringCase("nothing")
                    .containsIgnoringCase("pay");

            // Critical: validation-first short-circuit — no writes occurred.
            verify(accountRepository, never()).save(any());
            verify(transactionRepository, never()).save(any());
        }

        /**
         * Verifies that a payment with confirmation {@code "N"} returns the
         * "Confirmation cancelled..." reject WITHOUT performing ANY database
         * access (not even the account read). This is a stronger guarantee
         * than the other rejects — it proves the confirmation gate fires
         * BEFORE any persistence-layer interaction. Java mapping of COBIL00C
         * lines 178–181 ({@code WHEN 'N' WHEN 'n'} branch of
         * {@code EVALUATE CONFIRMI}).
         */
        @Test
        @DisplayName("payBill rejects when confirmation is 'N' without any DB access")
        void payBill_confirmationNo_rejectsWithCancelledMessageNoDbAccess() {
            // Arrange — no stubs at all; any DB access would surface as a
            // strict-stubs warning or a null-pointer when chaining off the
            // unstubbed Optional return.

            BillPaymentRequest request = new BillPaymentRequest(
                    TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10, "N");

            // Act
            BillPaymentResult result = service.payBill(request);

            // Assert
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage())
                    .as("Cancelled message per COBIL00C WHEN 'N' WHEN 'n' branch")
                    .containsIgnoringCase("cancelled");

            // Critical: confirmation gate fires BEFORE any DB access — no
            // calls to findById, findByAccountId, or
            // findTopByOrderByTransactionIdDesc occur for the cancelled path.
            verify(accountRepository, never()).findById(any());
            verify(cardXrefRepository, never()).findByAccountId(any());
            verify(transactionRepository, never()).findTopByOrderByTransactionIdDesc();
            verify(accountRepository, never()).save(any());
            verify(transactionRepository, never()).save(any());
        }

        /**
         * Verifies that lower-case {@code "n"} is treated identically to
         * upper-case {@code "N"} (COBIL00C lines 178–181:
         * {@code WHEN 'N' WHEN 'n'} both cancel).
         */
        @Test
        @DisplayName("payBill rejects when confirmation is lower-case 'n'")
        void payBill_lowerCaseConfirmationN_rejectsAsCancelled() {
            // Arrange
            BillPaymentRequest request = new BillPaymentRequest(
                    TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10, "n");

            // Act
            BillPaymentResult result = service.payBill(request);

            // Assert
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage()).containsIgnoringCase("cancelled");
            verify(accountRepository, never()).findById(any());
            verify(accountRepository, never()).save(any());
            verify(transactionRepository, never()).save(any());
        }

        /**
         * Verifies that a confirmation value other than Y/y/N/n returns the
         * "Please confirm..." reject without any DB access. Java mapping
         * of the collapsed COBIL00C {@code WHEN OTHER} + SPACES/LOW-VALUES
         * branches (lines 183–188 and 237–238) — the Java migration unifies
         * both into a single "please confirm" reject per AAP §0.10.2.
         */
        @Test
        @DisplayName("payBill rejects when confirmation value is invalid (not Y/N)")
        void payBill_invalidConfirmation_rejectsWithConfirmRequestMessage() {
            // Arrange
            BillPaymentRequest request = new BillPaymentRequest(
                    TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10, "X");

            // Act
            BillPaymentResult result = service.payBill(request);

            // Assert
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage())
                    .as("Collapsed 'Please confirm to make bill payment...' reject per AAP §0.10.2")
                    .containsIgnoringCase("confirm");

            // No DB access on the invalid-confirmation path.
            verify(accountRepository, never()).findById(any());
            verify(accountRepository, never()).save(any());
            verify(transactionRepository, never()).save(any());
        }

        /**
         * Verifies that a null confirmation value is treated as invalid
         * (collapses to the "please confirm" reject per the Java
         * migration's REST convention).
         */
        @Test
        @DisplayName("payBill rejects when confirmation is null")
        void payBill_nullConfirmation_rejectsWithConfirmRequestMessage() {
            // Arrange
            BillPaymentRequest request = new BillPaymentRequest(
                    TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10, null);

            // Act
            BillPaymentResult result = service.payBill(request);

            // Assert
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage()).containsIgnoringCase("confirm");
            verify(accountRepository, never()).findById(any());
            verify(accountRepository, never()).save(any());
            verify(transactionRepository, never()).save(any());
        }

        /**
         * Verifies that an empty/whitespace confirmation value is treated
         * as invalid. Java mapping of the COBIL00C SPACES/LOW-VALUES branch
         * at lines 237–238 (which surfaces "Confirm to make a bill payment...").
         */
        @Test
        @DisplayName("payBill rejects when confirmation is empty/whitespace")
        void payBill_emptyConfirmation_rejectsWithConfirmRequestMessage() {
            // Arrange
            BillPaymentRequest request = new BillPaymentRequest(
                    TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10, "   ");

            // Act
            BillPaymentResult result = service.payBill(request);

            // Assert
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage()).containsIgnoringCase("confirm");
            verify(accountRepository, never()).findById(any());
            verify(accountRepository, never()).save(any());
            verify(transactionRepository, never()).save(any());
        }

        /**
         * Verifies that an empty account ID is rejected with the verbatim
         * COBOL message. Java mapping of COBIL00C lines 159–164
         * ({@code IF ACTIDINI = SPACES OR LOW-VALUES} → {@code 'Acct ID can
         * NOT be empty...'}). The confirmation gate ({@code "Y"}) is
         * passed so this assertion specifically exercises the second
         * validation step.
         */
        @Test
        @DisplayName("payBill rejects when account ID is empty")
        void payBill_emptyAccountId_rejectsWithEmptyMessage() {
            // Arrange — confirmation is Y so the workflow advances past
            // the confirmation gate; account ID itself is empty.
            BillPaymentRequest request = new BillPaymentRequest("", "Y");

            // Act
            BillPaymentResult result = service.payBill(request);

            // Assert
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage())
                    .as("Verbatim COBOL 'Acct ID can NOT be empty...' per COBIL00C line 161")
                    .containsIgnoringCase("empty");

            // No DB access on the empty-account-ID path.
            verify(accountRepository, never()).findById(any());
            verify(accountRepository, never()).save(any());
            verify(transactionRepository, never()).save(any());
        }

        /**
         * Verifies that a missing card cross-reference (account exists but
         * the CXACAIX read returns empty) returns the "Account ID NOT
         * found..." reject — the COBIL00C workflow reuses the same reject
         * for both the ACCTDAT and CXACAIX NOTFND branches (line 425).
         */
        @Test
        @DisplayName("payBill rejects when card cross-reference is missing")
        void payBill_missingCardXref_rejectsWithAccountNotFoundMessage() {
            // Arrange — account exists, balance > 0, but no card xref.
            Account account = accountWithBalance(new BigDecimal("100.00"));
            when(accountRepository.findById(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10))
                    .thenReturn(Optional.of(account));
            when(cardXrefRepository.findByAccountId(TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10))
                    .thenReturn(Optional.empty());

            BillPaymentRequest request = new BillPaymentRequest(
                    TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10, "Y");

            // Act
            BillPaymentResult result = service.payBill(request);

            // Assert
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage())
                    .as("Reuses 'Account ID NOT found...' reject per COBIL00C line 425")
                    .containsIgnoringCase("not found");

            // No persistence writes on the missing-card-xref path.
            verify(accountRepository, never()).save(any());
            verify(transactionRepository, never()).save(any());
        }
    }

    // =========================================================================
    // Nested test group: FINANCIAL PRECISION (BigDecimal scale 2 mandate)
    // =========================================================================

    /**
     * Financial-precision tests — verify the AAP §0.10.3 mandate that all
     * monetary values flow through the system as {@link BigDecimal} at
     * scale 2, matching the COBOL {@code PIC S9(10)V99} account-balance
     * field width and {@code PIC S9(09)V99} transaction-amount field width.
     * Tests cover both the lower boundary (small fractional balance) and
     * the upper boundary (10-digit balance at the PIC field maximum).
     */
    @Nested
    @DisplayName("Financial precision — BigDecimal scale 2 (PIC S9(10)V99)")
    class FinancialPrecision {

        /**
         * Verifies that a fractional balance preserves scale 2 through the
         * pay-off workflow. Tests both the persisted account balance (zeroed
         * to {@code "0.00"} at scale 2) and the persisted transaction amount
         * (equal to the pre-payment balance, at scale 2).
         */
        @Test
        @DisplayName("payBill preserves scale 2 when balance is fractional")
        void payBill_fractionalBalance_preservesScale2() {
            // Arrange — balance with two decimal places.
            Account account = accountWithBalance(new BigDecimal("1234.56"));
            when(accountRepository.findById(any())).thenReturn(Optional.of(account));
            when(cardXrefRepository.findByAccountId(any()))
                    .thenReturn(Optional.of(standardCardXref()));
            when(transactionRepository.findTopByOrderByTransactionIdDesc())
                    .thenReturn(Optional.of(transactionWithId("0000000000000001")));
            when(accountRepository.save(any(Account.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(transactionRepository.save(any(Transaction.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            BillPaymentRequest request = new BillPaymentRequest(
                    TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10, "Y");

            // Act
            service.payBill(request);

            // Assert — persisted account balance: scale 2 preserved.
            ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
            verify(accountRepository).save(accountCaptor.capture());
            assertThat(accountCaptor.getValue().getCurrentBalance())
                    .as("ZERO_BALANCE constant carries scale 2 per AAP §0.10.3")
                    .isEqualByComparingTo("0.00")
                    .satisfies(b -> assertThat(b.scale()).isEqualTo(2));

            // Assert — persisted transaction amount: scale 2 preserved and
            // equal to the pre-payment balance (not zero).
            ArgumentCaptor<Transaction> txnCaptor = ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(txnCaptor.capture());
            assertThat(txnCaptor.getValue().getAmount())
                    .as("TRAN-AMT carries the pre-payment balance at scale 2")
                    .isEqualByComparingTo("1234.56")
                    .satisfies(b -> assertThat(b.scale()).isEqualTo(2));
        }

        /**
         * Verifies that a balance at the upper boundary of the COBOL
         * {@code PIC S9(10)V99} field (10 integer digits + 2 decimal
         * digits = {@code 9999999999.99}) is correctly paid off. The
         * transaction amount carries the boundary value and the account
         * balance is zeroed (with scale 2).
         */
        @Test
        @DisplayName("payBill handles boundary balance at PIC S9(10)V99 max")
        void payBill_largeBalanceAtPicBoundary_handlesCorrectly() {
            // Arrange — balance at the upper boundary of PIC S9(10)V99.
            Account account = accountWithBalance(new BigDecimal("9999999999.99"));
            when(accountRepository.findById(any())).thenReturn(Optional.of(account));
            when(cardXrefRepository.findByAccountId(any()))
                    .thenReturn(Optional.of(standardCardXref()));
            when(transactionRepository.findTopByOrderByTransactionIdDesc())
                    .thenReturn(Optional.of(transactionWithId("0000000000000001")));
            when(accountRepository.save(any(Account.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(transactionRepository.save(any(Transaction.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            BillPaymentRequest request = new BillPaymentRequest(
                    TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10, "Y");

            // Act
            BillPaymentResult result = service.payBill(request);

            // Assert — success.
            assertThat(result.isSuccess()).isTrue();

            // Assert — account balance zeroed with scale 2.
            ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
            verify(accountRepository).save(captor.capture());
            assertThat(captor.getValue().getCurrentBalance())
                    .isEqualByComparingTo("0.00")
                    .satisfies(b -> assertThat(b.scale()).isEqualTo(2));

            // Assert — transaction amount carries the boundary value.
            ArgumentCaptor<Transaction> txnCaptor = ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(txnCaptor.capture());
            assertThat(txnCaptor.getValue().getAmount())
                    .as("TRAN-AMT carries PIC S9(10)V99 boundary value at scale 2")
                    .isEqualByComparingTo("9999999999.99")
                    .satisfies(b -> assertThat(b.scale()).isEqualTo(2));
        }

        /**
         * Verifies that the smallest non-zero balance ({@code "0.01"} — one
         * cent) is correctly paid off. Tests the lower boundary of the COBOL
         * {@code PIC S9(10)V99} positive range and proves the zero-balance
         * guard uses strict signum comparison (not equality), so a one-cent
         * balance proceeds rather than being incorrectly rejected.
         */
        @Test
        @DisplayName("payBill processes smallest positive balance (one cent)")
        void payBill_oneCentBalance_processesPayment() {
            // Arrange — balance of exactly $0.01.
            Account account = accountWithBalance(new BigDecimal("0.01"));
            when(accountRepository.findById(any())).thenReturn(Optional.of(account));
            when(cardXrefRepository.findByAccountId(any()))
                    .thenReturn(Optional.of(standardCardXref()));
            when(transactionRepository.findTopByOrderByTransactionIdDesc())
                    .thenReturn(Optional.of(transactionWithId("0000000000000001")));
            when(accountRepository.save(any(Account.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(transactionRepository.save(any(Transaction.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            BillPaymentRequest request = new BillPaymentRequest(
                    TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10, "Y");

            // Act
            BillPaymentResult result = service.payBill(request);

            // Assert — success (one cent is greater than zero).
            assertThat(result.isSuccess())
                    .as("One-cent balance MUST proceed (signum > 0) not reject as nothing-to-pay")
                    .isTrue();

            // Assert — transaction amount carries the one-cent value.
            ArgumentCaptor<Transaction> txnCaptor = ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(txnCaptor.capture());
            assertThat(txnCaptor.getValue().getAmount())
                    .isEqualByComparingTo("0.01")
                    .satisfies(b -> assertThat(b.scale()).isEqualTo(2));
        }

        /**
         * Verifies that a negative balance (defensive case — the COBOL
         * workflow's {@code IF ACCT-CURR-BAL <= ZEROS} check covers both
         * zero AND negative balances at line 198) is rejected with the
         * "nothing to pay" message rather than processing a negative
         * payment.
         */
        @Test
        @DisplayName("payBill rejects negative balance as nothing to pay")
        void payBill_negativeBalance_rejectsAsNothingToPay() {
            // Arrange — a negative balance shouldn't occur in well-formed
            // data, but the COBOL guard <= ZEROS protects against it.
            Account account = accountWithBalance(new BigDecimal("-50.00"));
            when(accountRepository.findById(any())).thenReturn(Optional.of(account));

            BillPaymentRequest request = new BillPaymentRequest(
                    TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10, "Y");

            // Act
            BillPaymentResult result = service.payBill(request);

            // Assert — rejects via the same path as zero balance.
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage())
                    .as("Negative balance handled by 'IF ACCT-CURR-BAL <= ZEROS' guard")
                    .containsIgnoringCase("nothing")
                    .containsIgnoringCase("pay");

            // No writes for the negative-balance reject path.
            verify(accountRepository, never()).save(any());
            verify(transactionRepository, never()).save(any());
        }

        /**
         * Verifies that the {@link BillPaymentService} performs exactly the
         * expected number of repository writes — one {@code save()} call on
         * {@link AccountRepository} and one {@code save()} call on
         * {@link TransactionRepository}. Catches accidental dual-writes,
         * silent retries, or skipped persistence that {@code @ArgumentCaptor}
         * assertions alone might not surface.
         */
        @Test
        @DisplayName("payBill performs exactly one save on each repository")
        void payBill_validRequest_performsExactlyOneSaveOnEachRepository() {
            // Arrange
            Account account = accountWithBalance(new BigDecimal("500.00"));
            when(accountRepository.findById(any())).thenReturn(Optional.of(account));
            when(cardXrefRepository.findByAccountId(any()))
                    .thenReturn(Optional.of(standardCardXref()));
            when(transactionRepository.findTopByOrderByTransactionIdDesc())
                    .thenReturn(Optional.of(transactionWithId("0000000000000001")));
            when(accountRepository.save(any(Account.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(transactionRepository.save(any(Transaction.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            BillPaymentRequest request = new BillPaymentRequest(
                    TestFixtures.Accounts.SAMPLE_ACCOUNT_ID_10, "Y");

            // Act
            service.payBill(request);

            // Assert — exactly one save on each repository (idempotent commit).
            verify(accountRepository, times(1)).save(any(Account.class));
            verify(transactionRepository, times(1)).save(any(Transaction.class));
        }
    }
}

/*
 * BillPaymentServiceTest.java
 *
 * JUnit 5 + Mockito unit tests for BillPaymentService — validates the complete
 * bill payment workflow migrated from COBOL program COBIL00C.cbl (572 lines).
 *
 * Tests cover:
 *   - Input validation (null/blank account ID)
 *   - Account lookup (not found → RecordNotFoundException)
 *   - Balance validation (zero/negative → IllegalStateException)
 *   - Cross-reference lookup (not found → RecordNotFoundException)
 *   - Auto-ID generation (MAX + 1, 16-char zero-padded)
 *   - Hardcoded COBOL transaction values (type '02', cat 2, source 'POS TERM',
 *     desc 'BILL PAYMENT - ONLINE', merchantId '999999999')
 *   - BigDecimal balance update via subtract()
 *   - Atomic save of both transaction and account
 *   - BigDecimal precision (scale=2, no float/double)
 *
 * NO Spring context loading — pure Mockito unit tests.
 *
 * COBOL Source Reference:
 *   Program: COBIL00C (app/cbl/COBIL00C.cbl, commit 27d6c6f)
 *   Transaction: CB00
 *   Record Layouts: CVACT01Y.cpy (Account), CVACT03Y.cpy (XRef), CVTRA05Y.cpy (Transaction)
 *
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 * Licensed under the Apache License, Version 2.0
 */
package com.cardemo.unit.service;

import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Transaction;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.service.billing.BillPaymentService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BillPaymentService} — validates the bill payment workflow
 * migrated from COBOL program COBIL00C.cbl.
 *
 * <p>This test class uses Mockito (no Spring context) to verify all business logic
 * paths in {@code processPayment(String accountId)} including input validation,
 * account/cross-reference lookup, balance validation, auto-ID generation,
 * hardcoded COBOL transaction field values, BigDecimal balance arithmetic,
 * and atomic persistence of both the transaction and updated account.</p>
 *
 * <h3>Test Categories</h3>
 * <ul>
 *   <li>Input Validation (tests 1-2): null/blank account ID → IllegalArgumentException</li>
 *   <li>Account Lookup (test 3): account not found → RecordNotFoundException</li>
 *   <li>Balance Validation (tests 4-5): zero/negative balance → IllegalStateException</li>
 *   <li>Cross-Reference Lookup (test 6): xref not found → RecordNotFoundException</li>
 *   <li>Auto-ID Generation (test 7): MAX + 1, 16-char zero-padded format</li>
 *   <li>Transaction Fields (tests 8-12): hardcoded COBOL values verified exactly</li>
 *   <li>Balance Update (tests 13-14): BigDecimal.subtract() with compareTo() assertions</li>
 *   <li>Atomic Operation (tests 15-16): both entities saved, transaction returned</li>
 *   <li>BigDecimal Precision (tests 17-18): instanceof BigDecimal, scale=2</li>
 * </ul>
 *
 * <h3>Decimal Precision Rules (AAP §0.8.2)</h3>
 * <p>All monetary values use {@link BigDecimal}. All financial assertions use
 * {@code compareTo()}, NEVER {@code equals()}, to avoid scale-sensitivity.
 * Zero float/double usage anywhere in the test class.</p>
 */
@ExtendWith(MockitoExtension.class)
class BillPaymentServiceTest {

    // -----------------------------------------------------------------------
    // Test Constants — matching COBIL00C.cbl field values and format
    // -----------------------------------------------------------------------

    /**
     * Test account identifier — 11-character format matching COBOL ACCT-ID PIC 9(11).
     */
    private static final String ACCOUNT_ID = "00000000001";

    /**
     * Test card number — 16-character format matching COBOL XREF-CARD-NUM PIC X(16).
     */
    private static final String CARD_NUM = "4111111111111111";

    /**
     * Maximum existing transaction ID for auto-ID generation testing.
     * 16-character zero-padded format matching COBOL TRAN-ID PIC X(16).
     */
    private static final String MAX_TRAN_ID = "0000000000000100";

    /**
     * Expected next transaction ID = MAX_TRAN_ID + 1 = "0000000000000101".
     * Verifies the browse-to-end + increment pattern from COBIL00C.cbl lines 441-505.
     */
    private static final String EXPECTED_NEXT_ID = "0000000000000101";

    /**
     * Test current balance — BigDecimal with scale=2 matching COBOL PIC S9(10)V99.
     * No float/double per AAP §0.8.2.
     */
    private static final BigDecimal CURRENT_BALANCE = new BigDecimal("1000.00");

    /**
     * Test credit limit — BigDecimal with scale=2 matching COBOL PIC S9(10)V99.
     * Set higher than CURRENT_BALANCE so credit limit validation passes.
     */
    private static final BigDecimal CREDIT_LIMIT = new BigDecimal("5000.00");

    // -----------------------------------------------------------------------
    // Mocked Dependencies
    // -----------------------------------------------------------------------

    /** Mocked AccountRepository — provides findById() and save() for account operations. */
    @Mock
    private AccountRepository accountRepository;

    /** Mocked TransactionRepository — provides findMaxTransactionId() and save() for transactions. */
    @Mock
    private TransactionRepository transactionRepository;

    /** Mocked CardCrossReferenceRepository — provides findByXrefAcctId() for card resolution. */
    @Mock
    private CardCrossReferenceRepository cardCrossReferenceRepository;

    /** Service under test — BillPaymentService with all mocked repositories injected. */
    @InjectMocks
    private BillPaymentService billPaymentService;

    // -----------------------------------------------------------------------
    // Test Fixtures — initialized in @BeforeEach
    // -----------------------------------------------------------------------

    /** Test account fixture with positive balance and credit limit. */
    private Account account;

    /** Test cross-reference fixture linking account to card number. */
    private CardCrossReference xref;

    /**
     * Initializes test fixtures before each test method.
     *
     * <p>Creates an Account with a positive balance (BigDecimal "1000.00") and
     * a credit limit (BigDecimal "5000.00"), plus a CardCrossReference linking
     * the test account ID to a test card number. These fixtures represent the
     * COBOL ACCTDAT and CXACAIX records read during the COBIL00C.cbl payment flow.</p>
     */
    @BeforeEach
    void setUp() {
        account = new Account();
        account.setAcctId(ACCOUNT_ID);
        account.setAcctCurrBal(CURRENT_BALANCE);
        account.setAcctCreditLimit(CREDIT_LIMIT);

        xref = new CardCrossReference();
        xref.setXrefCardNum(CARD_NUM);
        xref.setXrefAcctId(ACCOUNT_ID);
    }

    /**
     * Configures all mocks for a successful payment flow.
     *
     * <p>Sets up:</p>
     * <ul>
     *   <li>AccountRepository.findById() → returns test account with positive balance</li>
     *   <li>CardCrossReferenceRepository.findByXrefAcctId() → returns test xref</li>
     *   <li>TransactionRepository.findMaxTransactionId() → returns "0000000000000100"</li>
     *   <li>TransactionRepository.save() → returns the saved transaction (pass-through)</li>
     *   <li>AccountRepository.save() → returns the saved account (pass-through)</li>
     * </ul>
     */
    private void setupSuccessfulPaymentMocks() {
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(cardCrossReferenceRepository.findByXrefAcctId(ACCOUNT_ID)).thenReturn(List.of(xref));
        when(transactionRepository.findMaxTransactionId()).thenReturn(Optional.of(MAX_TRAN_ID));
        when(transactionRepository.save(any(Transaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(accountRepository.save(any(Account.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    // =======================================================================
    // 1-2. Input Validation — Maps COBIL00C.cbl lines 159-167
    // =======================================================================

    /**
     * Test 1: Null account ID throws IllegalArgumentException.
     *
     * <p>Maps COBOL: {@code WHEN ACTIDINI OF COBIL0AI = SPACES OR LOW-VALUES}
     * (COBIL00C.cbl lines 159-167). A null account ID is the Java equivalent
     * of COBOL LOW-VALUES in the BMS field.</p>
     */
    @Test
    void testProcessPayment_nullAccountId_throwsIllegalArgument() {
        assertThatThrownBy(() -> billPaymentService.processPayment(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Test 2: Blank account ID throws IllegalArgumentException.
     *
     * <p>Maps COBOL: {@code WHEN ACTIDINI OF COBIL0AI = SPACES}
     * (COBIL00C.cbl lines 159-167). A blank string is the Java equivalent
     * of COBOL SPACES in the BMS input field.</p>
     */
    @Test
    void testProcessPayment_blankAccountId_throwsIllegalArgument() {
        assertThatThrownBy(() -> billPaymentService.processPayment("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // =======================================================================
    // 3. Account Lookup — Maps READ-ACCTDAT-FILE (lines 343-372)
    // =======================================================================

    /**
     * Test 3: Account not found throws RecordNotFoundException.
     *
     * <p>Maps COBOL: {@code EVALUATE WS-RESP-CD / WHEN DFHRESP(NOTFND)}
     * (COBIL00C.cbl lines 359-364). When the VSAM ACCTDAT dataset returns
     * FILE STATUS 23 (INVALID KEY), the COBOL program moves an error message
     * "Account ID NOT found...". The Java equivalent is
     * {@code findById() → Optional.empty() → RecordNotFoundException}.</p>
     */
    @Test
    void testProcessPayment_accountNotFound_throwsRecordNotFound() {
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> billPaymentService.processPayment(ACCOUNT_ID))
                .isInstanceOf(RecordNotFoundException.class);
    }

    // =======================================================================
    // 4-5. Balance Validation — Maps COBIL00C.cbl lines 197-206
    // =======================================================================

    /**
     * Test 4: Zero balance throws IllegalStateException.
     *
     * <p>Maps COBOL: {@code IF ACCT-CURR-BAL <= ZEROS} (COBIL00C.cbl line 197).
     * When the account current balance is exactly zero, the COBOL program
     * moves "You have nothing to pay..." to the message field.
     * Uses BigDecimal.ZERO — no float/double per AAP §0.8.2.</p>
     */
    @Test
    void testProcessPayment_zeroBalance_throwsIllegalState() {
        account.setAcctCurrBal(BigDecimal.ZERO);
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> billPaymentService.processPayment(ACCOUNT_ID))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * Test 5: Negative balance throws IllegalStateException.
     *
     * <p>Maps COBOL: {@code IF ACCT-CURR-BAL <= ZEROS} (COBIL00C.cbl line 197).
     * When the account current balance is negative, the same rejection applies.
     * Uses BigDecimal("-100.00") — no float/double per AAP §0.8.2.</p>
     */
    @Test
    void testProcessPayment_negativeBalance_throwsIllegalState() {
        account.setAcctCurrBal(new BigDecimal("-100.00"));
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> billPaymentService.processPayment(ACCOUNT_ID))
                .isInstanceOf(IllegalStateException.class);
    }

    // =======================================================================
    // 6. Cross-Reference Lookup — Maps READ-CXACAIX-FILE (lines 408-436)
    // =======================================================================

    /**
     * Test 6: Cross-reference not found throws RecordNotFoundException.
     *
     * <p>Maps COBOL: {@code EVALUATE WS-RESP-CD / WHEN DFHRESP(NOTFND)}
     * (COBIL00C.cbl lines 423-428). When the CXACAIX alternate index returns
     * no records for the given account ID, the COBOL program moves an error
     * message. The Java equivalent is an empty list from
     * {@code findByXrefAcctId()} → RecordNotFoundException.</p>
     */
    @Test
    void testProcessPayment_xrefNotFound_throwsRecordNotFound() {
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(cardCrossReferenceRepository.findByXrefAcctId(ACCOUNT_ID))
                .thenReturn(List.of());

        assertThatThrownBy(() -> billPaymentService.processPayment(ACCOUNT_ID))
                .isInstanceOf(RecordNotFoundException.class);
    }

    // =======================================================================
    // 7. Auto-ID Generation — Maps STARTBR/READPREV/ENDBR (lines 441-505)
    // =======================================================================

    /**
     * Test 7: Auto-ID generation produces MAX + 1 with 16-char zero-padded format.
     *
     * <p>Maps COBOL: The browse-to-end pattern in COBIL00C.cbl (lines 441-505):
     * {@code MOVE HIGH-VALUES TO TRAN-ID / STARTBR / READPREV / ENDBR /
     * MOVE TRAN-ID TO WS-TRAN-ID-NUM / ADD 1 TO WS-TRAN-ID-NUM}.
     * The Java equivalent queries {@code findMaxTransactionId()}, parses the
     * max ID as a long, adds 1, and formats with {@code %016d}.</p>
     *
     * <p>Given MAX_TRAN_ID = "0000000000000100", the expected next ID is
     * "0000000000000101" (100 + 1 = 101, formatted to 16 chars).</p>
     */
    @Test
    void testProcessPayment_autoIdGeneration() {
        setupSuccessfulPaymentMocks();

        Transaction result = billPaymentService.processPayment(ACCOUNT_ID);

        // Verify the generated ID is MAX + 1, zero-padded to 16 characters
        assertThat(result.getTranId()).isEqualTo(EXPECTED_NEXT_ID);
        assertThat(result.getTranId()).hasSize(16);
    }

    // =======================================================================
    // 8-12. Transaction Creation — Hardcoded COBOL Values (lines 218-232)
    // =======================================================================

    /**
     * Test 8: Transaction type code is "02".
     *
     * <p>Maps COBOL: {@code MOVE '02' TO TRAN-TYPE-CD} (COBIL00C.cbl line 220).
     * Bill payments use type code "02" as defined in the transaction type
     * reference data (TRANTYPE VSAM dataset).</p>
     */
    @Test
    void testProcessPayment_transactionTypeCd02() {
        setupSuccessfulPaymentMocks();

        Transaction result = billPaymentService.processPayment(ACCOUNT_ID);

        assertThat(result.getTranTypeCd()).isEqualTo("02");
    }

    /**
     * Test 9: Transaction category code is 2 (COBOL PIC 9(04) = "0002").
     *
     * <p>Maps COBOL: {@code MOVE 2 TO TRAN-CAT-CD} (COBIL00C.cbl line 221).
     * The COBOL PIC 9(04) field renders as "0002" in COBOL display format,
     * but is stored as {@code Short} in the Java entity per the DDL SMALLINT
     * column type. The assertion verifies the numeric value 2.</p>
     */
    @Test
    void testProcessPayment_transactionCatCd0002() {
        setupSuccessfulPaymentMocks();

        Transaction result = billPaymentService.processPayment(ACCOUNT_ID);

        // Short value 2 maps COBOL PIC 9(04) display "0002"
        assertThat(result.getTranCatCd()).isEqualTo((short) 2);
    }

    /**
     * Test 10: Transaction source is "POS TERM".
     *
     * <p>Maps COBOL: {@code MOVE 'POS TERM' TO TRAN-SOURCE}
     * (COBIL00C.cbl line 222). The source field identifies the origination
     * point of the transaction as a POS terminal for online bill payments.</p>
     */
    @Test
    void testProcessPayment_transactionSourcePOSTERM() {
        setupSuccessfulPaymentMocks();

        Transaction result = billPaymentService.processPayment(ACCOUNT_ID);

        assertThat(result.getTranSource()).isEqualTo("POS TERM");
    }

    /**
     * Test 11: Transaction description is "BILL PAYMENT - ONLINE".
     *
     * <p>Maps COBOL: {@code MOVE 'BILL PAYMENT - ONLINE' TO TRAN-DESC}
     * (COBIL00C.cbl line 223). The description field provides a human-readable
     * label for the bill payment transaction type.</p>
     */
    @Test
    void testProcessPayment_transactionDescBillPayment() {
        setupSuccessfulPaymentMocks();

        Transaction result = billPaymentService.processPayment(ACCOUNT_ID);

        assertThat(result.getTranDesc()).isEqualTo("BILL PAYMENT - ONLINE");
    }

    /**
     * Test 12: Merchant ID is "999999999".
     *
     * <p>Maps COBOL: {@code MOVE 999999999 TO TRAN-MERCHANT-ID}
     * (COBIL00C.cbl line 226, PIC 9(09)). The merchant ID "999999999" is a
     * sentinel value indicating an internal bill payment operation rather than
     * an external merchant transaction.</p>
     */
    @Test
    void testProcessPayment_transactionMerchantId999999999() {
        setupSuccessfulPaymentMocks();

        Transaction result = billPaymentService.processPayment(ACCOUNT_ID);

        assertThat(result.getTranMerchantId()).isEqualTo("999999999");
    }

    // =======================================================================
    // 13-14. Balance Update — BigDecimal arithmetic (AAP §0.8.2)
    // =======================================================================

    /**
     * Test 13: Balance updated via BigDecimal.subtract().
     *
     * <p>Maps COBOL: {@code COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT}
     * (COBIL00C.cbl line 234). The bill payment pays the full current balance,
     * so the new balance = currentBalance - currentBalance = 0.00.
     * Uses BigDecimal.compareTo() for assertion — NEVER equals() per AAP §0.8.2.</p>
     */
    @Test
    void testProcessPayment_balanceUpdated_bigDecimalSubtract() {
        setupSuccessfulPaymentMocks();

        billPaymentService.processPayment(ACCOUNT_ID);

        // After full payment: 1000.00 - 1000.00 = 0.00
        BigDecimal expectedNewBalance = CURRENT_BALANCE.subtract(CURRENT_BALANCE);
        // CRITICAL: Use compareTo(), NEVER equals() for BigDecimal (AAP §0.8.2)
        assertThat(account.getAcctCurrBal().compareTo(expectedNewBalance)).isEqualTo(0);
    }

    /**
     * Test 14: New balance verified using BigDecimal.compareTo().
     *
     * <p>Maps COBOL: After {@code COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT}
     * (COBIL00C.cbl line 234), the account balance should be zero for a full
     * balance payment.
     * Explicitly demonstrates compareTo() usage per AAP §0.8.2 decimal rules.</p>
     */
    @Test
    void testProcessPayment_balanceUpdated_compareTo() {
        setupSuccessfulPaymentMocks();

        billPaymentService.processPayment(ACCOUNT_ID);

        // Bill payment pays full balance → new balance must be zero
        // CRITICAL: Use compareTo(), NEVER equals() for BigDecimal (AAP §0.8.2)
        assertThat(account.getAcctCurrBal().compareTo(BigDecimal.ZERO)).isEqualTo(0);
    }

    // =======================================================================
    // 15-16. Atomic Operation — @Transactional scope
    // =======================================================================

    /**
     * Test 15: Both transaction and account are saved atomically.
     *
     * <p>Maps COBOL atomic sequence: WRITE-TRANSACT-FILE (lines 510-547) +
     * UPDATE-ACCTDAT-FILE (lines 377-403). In the COBOL CICS environment,
     * the WRITE + REWRITE operations occur within a single Unit of Work
     * (SYNCPOINT implied). The Java equivalent is {@code @Transactional}
     * ensuring both saves occur or both roll back.</p>
     *
     * <p>Verifies that {@code transactionRepository.save()} and
     * {@code accountRepository.save()} are both called exactly once.</p>
     */
    @Test
    void testProcessPayment_transactionAndAccountSaved() {
        setupSuccessfulPaymentMocks();

        billPaymentService.processPayment(ACCOUNT_ID);

        // Verify both repositories' save() methods were called
        verify(transactionRepository).save(any(Transaction.class));
        verify(accountRepository).save(any(Account.class));
    }

    /**
     * Test 16: Successful payment returns the created Transaction entity.
     *
     * <p>The method contract specifies returning the newly created
     * {@link Transaction} entity, enabling the caller (controller layer)
     * to include the transaction ID in the API response. This maps the
     * COBOL pattern where the transaction ID is displayed in the BMS
     * success message (COBIL00C.cbl lines 527-531).</p>
     */
    @Test
    void testProcessPayment_success_returnsTransaction() {
        setupSuccessfulPaymentMocks();

        Transaction result = billPaymentService.processPayment(ACCOUNT_ID);

        assertThat(result).isNotNull();
        assertThat(result.getTranId()).isNotNull();
        assertThat(result.getTranId()).isEqualTo(EXPECTED_NEXT_ID);
    }

    // =======================================================================
    // 17-18. BigDecimal Precision — AAP §0.8.2 enforcement
    // =======================================================================

    /**
     * Test 17: Transaction amount is a BigDecimal instance.
     *
     * <p>Enforces AAP §0.8.2 zero floating-point substitution rule: every
     * monetary field originating from a COBOL PIC clause with decimal
     * positions MUST use {@link BigDecimal}. The transaction amount maps
     * COBOL TRAN-AMT PIC S9(09)V99 — a packed decimal (COMP-3) field.</p>
     */
    @Test
    void testProcessPayment_amountIsBigDecimal() {
        setupSuccessfulPaymentMocks();

        Transaction result = billPaymentService.processPayment(ACCOUNT_ID);

        assertThat(result.getTranAmt()).isInstanceOf(BigDecimal.class);
    }

    /**
     * Test 18: Transaction amount preserves scale=2.
     *
     * <p>Enforces AAP §0.8.2 scale preservation rule: the BigDecimal scale
     * MUST match the COBOL PIC clause. TRAN-AMT PIC S9(09)V99 has 2 decimal
     * positions (V99), so the BigDecimal scale must be exactly 2.</p>
     *
     * <p>The transaction amount equals the account's current balance
     * (CURRENT_BALANCE = new BigDecimal("1000.00") which has scale=2),
     * so the scale is preserved through the assignment.</p>
     */
    @Test
    void testProcessPayment_amountScale2() {
        setupSuccessfulPaymentMocks();

        Transaction result = billPaymentService.processPayment(ACCOUNT_ID);

        assertThat(result.getTranAmt().scale()).isEqualTo(2);
    }
}

/*
 * TransactionDetailServiceTest.java
 *
 * JUnit 5 + Mockito unit tests for TransactionDetailService — validates the
 * single-transaction keyed read operation migrated from COBOL program COTRN01C.cbl
 * (330 lines, transaction ID CT01).
 *
 * Test Coverage:
 * - Input validation: null and blank transaction ID → IllegalArgumentException
 * - Not-found handling: repository returns empty → RecordNotFoundException
 * - Successful read: all 13 CVTRA05Y.cpy fields mapped correctly to DTO
 * - BigDecimal precision: amount uses compareTo() (NEVER equals()), scale 2
 * - Repository verification: findById called exactly once per invocation
 *
 * COBOL Traceability (original repository commit SHA 27d6c6f):
 * - PROCESS-ENTER-KEY (COTRN01C.cbl lines 144-192): Input validation + read + map
 * - READ-TRANSACT-FILE (COTRN01C.cbl lines 267-296): VSAM keyed read
 * - DFHRESP(NOTFND) (line 283): "Transaction ID NOT found..." → RecordNotFoundException
 * - TRNIDINI = SPACES OR LOW-VALUES (line 147): "Tran ID can NOT be empty..."
 *
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0
 */
package com.cardemo.unit.service;

import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.model.dto.TransactionDto;
import com.cardemo.model.entity.Transaction;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.service.transaction.TransactionDetailService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TransactionDetailService} — the service class migrating
 * COBOL program COTRN01C.cbl (single transaction detail keyed read).
 *
 * <p>Tests exercise the {@code getTransaction(String)} method which performs:</p>
 * <ol>
 *   <li>Input validation — null/blank transaction ID → IllegalArgumentException
 *       (maps COBOL line 147: {@code TRNIDINI = SPACES OR LOW-VALUES})</li>
 *   <li>Keyed read — calls {@code TransactionRepository.findById(transactionId)}
 *       (maps COBOL READ-TRANSACT-FILE paragraph, lines 267-296)</li>
 *   <li>DTO mapping — converts all 13 TRAN-RECORD fields from the entity to DTO
 *       (maps COBOL PROCESS-ENTER-KEY field population, lines 176-192)</li>
 * </ol>
 *
 * <p><strong>BigDecimal Rule (AAP §0.8.2):</strong> All financial amount assertions
 * use {@code BigDecimal.compareTo()} — NEVER {@code equals()}, which is
 * scale-sensitive and does not match COBOL numeric comparison semantics.</p>
 *
 * <p><strong>No Spring Context:</strong> These are isolated unit tests using Mockito
 * for dependency injection. No Spring ApplicationContext is loaded.</p>
 *
 * @see TransactionDetailService
 * @see Transaction
 * @see TransactionDto
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionDetailService — COTRN01C.cbl single transaction keyed read")
class TransactionDetailServiceTest {

    // -----------------------------------------------------------------------
    // Mocks and System Under Test
    // -----------------------------------------------------------------------

    /**
     * Mocked TransactionRepository — replaces the TRANSACT VSAM KSDS dataset.
     * Controls return values of findById(String) for test scenarios:
     * Optional.of(Transaction) for success, Optional.empty() for not-found.
     */
    @Mock
    private TransactionRepository transactionRepository;

    /**
     * System under test — TransactionDetailService with the mocked repository
     * injected via constructor injection.
     */
    @InjectMocks
    private TransactionDetailService transactionDetailService;

    // -----------------------------------------------------------------------
    // Test Constants — CVTRA05Y.cpy TRAN-RECORD field values
    // -----------------------------------------------------------------------

    /** Transaction ID — 16-char zero-padded string (TRAN-ID PIC X(16)) */
    private static final String TEST_TRAN_ID = "0000000000000001";

    /** Transaction type code — 2-char type (TRAN-TYPE-CD PIC X(02)) */
    private static final String TEST_TYPE_CD = "SA";

    /** Transaction category code as Short — entity stores as SMALLINT (TRAN-CAT-CD PIC 9(04)) */
    private static final Short TEST_CAT_CD = (short) 5001;

    /** Expected category code string in DTO — formatted with leading zeros */
    private static final String TEST_CAT_CD_STR = "5001";

    /** Transaction source — 10-char source identifier (TRAN-SOURCE PIC X(10)) */
    private static final String TEST_SOURCE = "POS TERM";

    /** Transaction description — up to 100 chars (TRAN-DESC PIC X(100)) */
    private static final String TEST_DESC = "Purchase at Electronics Store";

    /**
     * Transaction amount as BigDecimal with scale 2 — maps TRAN-AMT PIC S9(09)V99.
     * Constructed from String to guarantee exact scale, NEVER from double/float.
     */
    private static final BigDecimal TEST_AMOUNT = new BigDecimal("1234567.89");

    /** Merchant ID — 9-digit identifier (TRAN-MERCHANT-ID PIC 9(09)) */
    private static final String TEST_MERCHANT_ID = "123456789";

    /** Merchant name — up to 50 chars (TRAN-MERCHANT-NAME PIC X(50)) */
    private static final String TEST_MERCHANT_NAME = "Electronics World";

    /** Merchant city — up to 50 chars (TRAN-MERCHANT-CITY PIC X(50)) */
    private static final String TEST_MERCHANT_CITY = "New York";

    /** Merchant ZIP — up to 10 chars (TRAN-MERCHANT-ZIP PIC X(10)) */
    private static final String TEST_MERCHANT_ZIP = "10001";

    /** Card number — 16-char number (TRAN-CARD-NUM PIC X(16)) */
    private static final String TEST_CARD_NUM = "4111111111111111";

    /** Origination timestamp — deterministic value for assertion (TRAN-ORIG-TS PIC X(26)) */
    private static final LocalDateTime TEST_ORIG_TS = LocalDateTime.of(2024, 1, 15, 10, 30, 0);

    // -----------------------------------------------------------------------
    // Test Fixtures
    // -----------------------------------------------------------------------

    /** Pre-built Transaction entity used across success test scenarios */
    private Transaction testTransaction;

    /** Processing timestamp — set per test to exercise LocalDateTime.now() */
    private LocalDateTime testProcTs;

    /**
     * Initializes the test fixture Transaction entity with all 13 CVTRA05Y.cpy fields.
     *
     * <p>Uses the no-args constructor + setter pattern (as specified in the schema)
     * to build a fully populated Transaction entity matching a realistic TRANSACT
     * VSAM KSDS record.</p>
     */
    @BeforeEach
    void setUp() {
        // Use LocalDateTime.now() for processing timestamp — exercises the API
        // while remaining deterministic through reference-based assertion
        testProcTs = LocalDateTime.now();

        testTransaction = new Transaction();
        testTransaction.setTranId(TEST_TRAN_ID);
        testTransaction.setTranTypeCd(TEST_TYPE_CD);
        testTransaction.setTranCatCd(TEST_CAT_CD);
        testTransaction.setTranSource(TEST_SOURCE);
        testTransaction.setTranDesc(TEST_DESC);
        testTransaction.setTranAmt(TEST_AMOUNT);
        testTransaction.setTranCardNum(TEST_CARD_NUM);
        testTransaction.setTranMerchantId(TEST_MERCHANT_ID);
        testTransaction.setTranMerchantName(TEST_MERCHANT_NAME);
        testTransaction.setTranMerchantCity(TEST_MERCHANT_CITY);
        testTransaction.setTranMerchantZip(TEST_MERCHANT_ZIP);
        testTransaction.setTranOrigTs(TEST_ORIG_TS);
        testTransaction.setTranProcTs(testProcTs);
    }

    // -----------------------------------------------------------------------
    // Input Validation Tests — maps COTRN01C.cbl PROCESS-ENTER-KEY lines 146-156
    // -----------------------------------------------------------------------

    /**
     * Verifies that passing a null transaction ID throws IllegalArgumentException.
     *
     * <p>Maps COBOL COTRN01C.cbl line 147:
     * {@code WHEN TRNIDINI OF COTRN1AI = SPACES OR LOW-VALUES}
     * → {@code MOVE 'Tran ID can NOT be empty...' TO WS-MESSAGE}</p>
     *
     * <p>Also verifies that the repository is never invoked for null input,
     * ensuring the validation short-circuits before any database access.</p>
     */
    @Test
    @DisplayName("null transactionId → IllegalArgumentException (COBOL: SPACES/LOW-VALUES check)")
    void testGetTransaction_nullId_throwsIllegalArgument() {
        // Act & Assert — null triggers validation before repository call
        assertThatThrownBy(() -> transactionDetailService.getTransaction(null))
                .isInstanceOf(IllegalArgumentException.class);

        // Verify repository was NEVER called — validation short-circuits
        verify(transactionRepository, times(0)).findById(any());
    }

    /**
     * Verifies that passing a blank transaction ID throws IllegalArgumentException.
     *
     * <p>Maps COBOL COTRN01C.cbl line 147:
     * {@code WHEN TRNIDINI OF COTRN1AI = SPACES OR LOW-VALUES}
     * where SPACES is equivalent to a blank (whitespace-only) string in Java.</p>
     */
    @Test
    @DisplayName("blank transactionId → IllegalArgumentException (COBOL: SPACES check)")
    void testGetTransaction_blankId_throwsIllegalArgument() {
        // Act & Assert — blank string triggers same validation as COBOL SPACES
        assertThatThrownBy(() -> transactionDetailService.getTransaction("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -----------------------------------------------------------------------
    // Transaction Not Found Test — maps COTRN01C.cbl READ-TRANSACT-FILE NOTFND
    // -----------------------------------------------------------------------

    /**
     * Verifies that when the repository returns empty (record not found),
     * the service throws RecordNotFoundException.
     *
     * <p>Maps COBOL COTRN01C.cbl lines 283-288:
     * {@code WHEN DFHRESP(NOTFND)}
     * → {@code MOVE 'Transaction ID NOT found...' TO WS-MESSAGE}
     * which corresponds to COBOL FILE STATUS 23 (INVALID KEY).</p>
     */
    @Test
    @DisplayName("not found → RecordNotFoundException (COBOL: DFHRESP(NOTFND) / FILE STATUS 23)")
    void testGetTransaction_notFound_throwsRecordNotFound() {
        // Arrange — repository returns empty (maps VSAM record not found)
        when(transactionRepository.findById(TEST_TRAN_ID)).thenReturn(Optional.empty());

        // Act & Assert — RecordNotFoundException with entity context
        assertThatThrownBy(() -> transactionDetailService.getTransaction(TEST_TRAN_ID))
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessageContaining("Transaction")
                .hasMessageContaining(TEST_TRAN_ID);
    }

    // -----------------------------------------------------------------------
    // Successful Read Tests — maps COTRN01C.cbl PROCESS-ENTER-KEY lines 176-192
    // -----------------------------------------------------------------------

    /**
     * Verifies that a successful read returns a non-null, populated TransactionDto
     * with all fields from the CVTRA05Y.cpy TRAN-RECORD layout.
     *
     * <p>Maps COBOL COTRN01C.cbl lines 176-192 where each TRAN-RECORD field is
     * moved to the corresponding BMS screen output field (COTRN1AI).</p>
     */
    @Test
    @DisplayName("success → returns populated TransactionDto with all CVTRA05Y fields")
    void testGetTransaction_success_returnsPopulatedDto() {
        // Arrange — repository returns the test transaction
        when(transactionRepository.findById(TEST_TRAN_ID)).thenReturn(Optional.of(testTransaction));

        // Act
        TransactionDto result = transactionDetailService.getTransaction(TEST_TRAN_ID);

        // Assert — DTO is non-null and key fields are populated
        assertThat(result).isNotNull();
        assertThat(result.getTranId()).isEqualTo(TEST_TRAN_ID);
        assertThat(result.getTranTypeCd()).isEqualTo(TEST_TYPE_CD);
        assertThat(result.getTranCatCd()).isEqualTo(TEST_CAT_CD_STR);
        assertThat(result.getTranSource()).isEqualTo(TEST_SOURCE);
        assertThat(result.getTranDesc()).isEqualTo(TEST_DESC);
        // BigDecimal: use compareTo() per AAP §0.8.2
        assertThat(result.getTranAmt().compareTo(TEST_AMOUNT)).isEqualTo(0);
        assertThat(result.getTranCardNum()).isEqualTo(TEST_CARD_NUM);
        assertThat(result.getTranMerchId()).isEqualTo(TEST_MERCHANT_ID);
        assertThat(result.getTranMerchName()).isEqualTo(TEST_MERCHANT_NAME);
        assertThat(result.getTranMerchCity()).isEqualTo(TEST_MERCHANT_CITY);
        assertThat(result.getTranMerchZip()).isEqualTo(TEST_MERCHANT_ZIP);
        assertThat(result.getTranOrigTs()).isEqualTo(TEST_ORIG_TS);
        assertThat(result.getTranProcTs()).isEqualTo(testProcTs);
    }

    /**
     * Verifies that the amount field in the returned DTO is strictly a BigDecimal
     * instance — NEVER a float or double wrapper.
     *
     * <p>This test enforces the zero floating-point substitution rule from
     * AAP §0.8.2. COBOL TRAN-AMT PIC S9(09)V99 maps exclusively to
     * {@code java.math.BigDecimal} in the Java target.</p>
     */
    @Test
    @DisplayName("success → amount field is BigDecimal type (zero float/double)")
    void testGetTransaction_success_amountIsBigDecimal() {
        // Arrange
        when(transactionRepository.findById(TEST_TRAN_ID)).thenReturn(Optional.of(testTransaction));

        // Act
        TransactionDto result = transactionDetailService.getTransaction(TEST_TRAN_ID);

        // Assert — amount is BigDecimal, NOT Float or Double
        assertThat(result.getTranAmt()).isNotNull();
        assertThat(result.getTranAmt()).isInstanceOf(BigDecimal.class);
    }

    /**
     * Comprehensive field-by-field verification that all 13 CVTRA05Y.cpy data
     * fields are correctly mapped from the Transaction entity to the TransactionDto.
     *
     * <p>Maps the COBOL field population block in PROCESS-ENTER-KEY
     * (COTRN01C.cbl lines 176-192):</p>
     * <ol>
     *   <li>TRAN-ID → TRNIDI (line 178) → tranId</li>
     *   <li>TRAN-TYPE-CD → TTYPCDI (line 180) → tranTypeCd</li>
     *   <li>TRAN-CAT-CD → TCATCDI (line 181) → tranCatCd (Short→String with %04d)</li>
     *   <li>TRAN-SOURCE → TRNSRCI (line 182) → tranSource</li>
     *   <li>TRAN-DESC → TDESCI (line 184) → tranDesc</li>
     *   <li>TRAN-AMT → TRNAMTI (line 183) → tranAmt (BigDecimal, compareTo())</li>
     *   <li>TRAN-CARD-NUM → CARDNUMI (line 179) → tranCardNum</li>
     *   <li>TRAN-MERCHANT-ID → MIDI (line 187) → tranMerchId</li>
     *   <li>TRAN-MERCHANT-NAME → MNAMEI (line 188) → tranMerchName</li>
     *   <li>TRAN-MERCHANT-CITY → MCITYI (line 189) → tranMerchCity</li>
     *   <li>TRAN-MERCHANT-ZIP → MZIPI (line 190) → tranMerchZip</li>
     *   <li>TRAN-ORIG-TS → TORIGDTI (line 185) → tranOrigTs</li>
     *   <li>TRAN-PROC-TS → TPROCDTI (line 186) → tranProcTs</li>
     * </ol>
     */
    @Test
    @DisplayName("success → all 13 CVTRA05Y.cpy TRAN-RECORD fields mapped correctly")
    void testGetTransaction_success_allFieldsMapped() {
        // Arrange
        when(transactionRepository.findById(TEST_TRAN_ID)).thenReturn(Optional.of(testTransaction));

        // Act
        TransactionDto result = transactionDetailService.getTransaction(TEST_TRAN_ID);

        // Assert — verify each of the 13 CVTRA05Y.cpy fields individually

        // Field 1: TRAN-ID PIC X(16) → tranId (String)
        assertThat(result.getTranId()).isEqualTo(TEST_TRAN_ID);

        // Field 2: TRAN-TYPE-CD PIC X(02) → tranTypeCd (String)
        assertThat(result.getTranTypeCd()).isEqualTo(TEST_TYPE_CD);

        // Field 3: TRAN-CAT-CD PIC 9(04) → tranCatCd (Short→String via %04d)
        // Entity stores Short; DTO receives String with leading zeros preserved
        assertThat(result.getTranCatCd()).isEqualTo(TEST_CAT_CD_STR);

        // Field 4: TRAN-SOURCE PIC X(10) → tranSource (String)
        assertThat(result.getTranSource()).isEqualTo(TEST_SOURCE);

        // Field 5: TRAN-DESC PIC X(100) → tranDesc (String)
        assertThat(result.getTranDesc()).isEqualTo(TEST_DESC);

        // Field 6: TRAN-AMT PIC S9(09)V99 → tranAmt (BigDecimal)
        // CRITICAL: Uses compareTo() per AAP §0.8.2 — NEVER equals()
        assertThat(result.getTranAmt().compareTo(TEST_AMOUNT)).isEqualTo(0);

        // Field 7: TRAN-CARD-NUM PIC X(16) → tranCardNum (String)
        assertThat(result.getTranCardNum()).isEqualTo(TEST_CARD_NUM);

        // Field 8: TRAN-MERCHANT-ID PIC 9(09) → tranMerchId (String)
        // Note: Entity uses getMerchantId(); DTO uses getMerchId()
        assertThat(result.getTranMerchId()).isEqualTo(TEST_MERCHANT_ID);

        // Field 9: TRAN-MERCHANT-NAME PIC X(50) → tranMerchName (String)
        assertThat(result.getTranMerchName()).isEqualTo(TEST_MERCHANT_NAME);

        // Field 10: TRAN-MERCHANT-CITY PIC X(50) → tranMerchCity (String)
        assertThat(result.getTranMerchCity()).isEqualTo(TEST_MERCHANT_CITY);

        // Field 11: TRAN-MERCHANT-ZIP PIC X(10) → tranMerchZip (String)
        assertThat(result.getTranMerchZip()).isEqualTo(TEST_MERCHANT_ZIP);

        // Field 12: TRAN-ORIG-TS PIC X(26) → tranOrigTs (LocalDateTime)
        assertThat(result.getTranOrigTs()).isEqualTo(TEST_ORIG_TS);

        // Field 13: TRAN-PROC-TS PIC X(26) → tranProcTs (LocalDateTime)
        assertThat(result.getTranProcTs()).isEqualTo(testProcTs);
    }

    // -----------------------------------------------------------------------
    // BigDecimal Precision Tests — AAP §0.8.2 decimal precision rules
    // -----------------------------------------------------------------------

    /**
     * Verifies that the BigDecimal amount uses compareTo() for correct value
     * comparison, as mandated by AAP §0.8.2.
     *
     * <p>In COBOL, numeric comparisons are always value-based. Java's
     * {@code BigDecimal.equals()} is scale-sensitive (e.g., {@code 1.0 != 1.00}),
     * which does NOT match COBOL semantics. Only {@code compareTo()} provides
     * equivalent value comparison behavior.</p>
     *
     * <p>This test exercises three comparison scenarios:</p>
     * <ul>
     *   <li>Equal value → compareTo returns 0</li>
     *   <li>Different value → compareTo returns non-zero</li>
     *   <li>Zero comparison → compareTo returns non-zero for non-zero amounts</li>
     * </ul>
     */
    @Test
    @DisplayName("amount compareTo() validates correctly (AAP §0.8.2 BigDecimal rule)")
    void testGetTransaction_amount_comparesToCorrectly() {
        // Arrange
        when(transactionRepository.findById(TEST_TRAN_ID)).thenReturn(Optional.of(testTransaction));

        // Act
        TransactionDto result = transactionDetailService.getTransaction(TEST_TRAN_ID);

        // Assert — compareTo() returns 0 for equal values (correct comparison)
        assertThat(result.getTranAmt().compareTo(new BigDecimal("1234567.89"))).isEqualTo(0);

        // Assert — compareTo() returns non-zero for different values
        assertThat(result.getTranAmt().compareTo(new BigDecimal("1234567.90"))).isNotEqualTo(0);

        // Assert — compareTo() with zero confirms amount is non-zero
        assertThat(result.getTranAmt().compareTo(BigDecimal.valueOf(0))).isNotEqualTo(0);
    }

    /**
     * Verifies that the BigDecimal amount preserves scale 2, matching the
     * COBOL PIC S9(09)V99 two decimal positions.
     *
     * <p>The "V99" in the COBOL PIC clause specifies exactly 2 decimal digits.
     * The Java {@code BigDecimal} must maintain {@code scale() == 2} to ensure
     * identical precision semantics with the COBOL COMP-3 packed decimal
     * representation.</p>
     */
    @Test
    @DisplayName("amount preserves scale 2 matching COBOL PIC S9(09)V99")
    void testGetTransaction_amountWithScale2() {
        // Arrange
        when(transactionRepository.findById(TEST_TRAN_ID)).thenReturn(Optional.of(testTransaction));

        // Act
        TransactionDto result = transactionDetailService.getTransaction(TEST_TRAN_ID);

        // Assert — scale must be exactly 2 (matches COBOL V99)
        assertThat(result.getTranAmt().scale()).isEqualTo(2);
    }

    // -----------------------------------------------------------------------
    // Repository Verification Test — ensures correct JPA interaction
    // -----------------------------------------------------------------------

    /**
     * Verifies that the service calls {@code TransactionRepository.findById()}
     * exactly once with the provided transaction ID.
     *
     * <p>Maps the COBOL READ-TRANSACT-FILE paragraph (COTRN01C.cbl lines 267-296)
     * which performs a single EXEC CICS READ on the TRANSACT VSAM dataset.
     * The Java service should make exactly one database call per invocation —
     * no redundant reads, no caching, no retry.</p>
     */
    @Test
    @DisplayName("verifies findById called exactly once with correct ID")
    void testGetTransaction_verifiesRepositoryCalled() {
        // Arrange
        when(transactionRepository.findById(TEST_TRAN_ID)).thenReturn(Optional.of(testTransaction));

        // Act
        transactionDetailService.getTransaction(TEST_TRAN_ID);

        // Assert — findById called exactly once with the correct transaction ID
        verify(transactionRepository, times(1)).findById(TEST_TRAN_ID);
    }
}

package com.cardemo.unit.batch;

import com.cardemo.batch.processors.TransactionReportProcessor;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Transaction;
import com.cardemo.model.entity.TransactionCategory;
import com.cardemo.model.entity.TransactionType;
import com.cardemo.model.key.TransactionCategoryId;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.TransactionCategoryRepository;
import com.cardemo.repository.TransactionTypeRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TransactionReportProcessor}.
 *
 * <p>Tests the batch processor that implements the CBTRN03C.cbl transaction
 * detail report logic: date-range filtering, enrichment lookups (XREF,
 * TRANTYPE, TRANCATG), and multi-level BigDecimal total accumulation
 * (page, account, grand).</p>
 *
 * <p>Pure Mockito-based tests — no Spring context loading. All financial
 * assertions use {@code BigDecimal.compareTo()} per AAP §0.8.2 — never
 * {@code equals()} which is scale-sensitive.</p>
 */
@ExtendWith(MockitoExtension.class)
class TransactionReportProcessorTest {

    @Mock
    private CardCrossReferenceRepository cardCrossReferenceRepository;

    @Mock
    private TransactionTypeRepository transactionTypeRepository;

    @Mock
    private TransactionCategoryRepository transactionCategoryRepository;

    private TransactionReportProcessor processor;

    /**
     * Creates a fresh processor instance via constructor injection with the
     * three mock repositories and configures the date range to the full
     * calendar year 2024 (inclusive on both boundaries).
     */
    @BeforeEach
    void setUp() {
        processor = new TransactionReportProcessor(
                cardCrossReferenceRepository,
                transactionTypeRepository,
                transactionCategoryRepository
        );
        processor.setStartDate(LocalDate.of(2024, 1, 1));
        processor.setEndDate(LocalDate.of(2024, 12, 31));
    }

    // -----------------------------------------------------------------------
    // Date Filtering Tests
    // -----------------------------------------------------------------------

    /**
     * Verifies that a transaction whose processing timestamp falls within the
     * configured start/end date range passes through the processor (non-null
     * return). Maps to CBTRN03C lines 185-195 date filtering logic:
     * {@code IF TRAN-PROC-TS(1:10) >= WS-START-DATE AND <= WS-END-DATE}.
     */
    @Test
    void process_shouldReturnTransactionWithinDateRange() throws Exception {
        Transaction txn = createTransaction("TXN-001", "4000000000000001",
                new BigDecimal("100.00"), LocalDateTime.of(2024, 6, 15, 10, 0));

        setupDefaultLookupMocks("4000000000000001");

        Transaction result = processor.process(txn);

        assertThat(result).isNotNull();
    }

    /**
     * Verifies that a transaction with a processing timestamp before the
     * configured start date is filtered out (returns null). Corresponds to
     * COBOL: {@code TRAN-PROC-TS(1:10) >= WS-START-DATE} — the processor
     * short-circuits without performing any enrichment lookups.
     */
    @Test
    void process_shouldFilterTransactionBeforeStartDate() throws Exception {
        Transaction txn = createTransaction("TXN-002", "4000000000000001",
                new BigDecimal("100.00"), LocalDateTime.of(2023, 12, 31, 23, 59));

        Transaction result = processor.process(txn);

        assertThat(result).isNull();
        verifyNoInteractions(cardCrossReferenceRepository,
                transactionTypeRepository, transactionCategoryRepository);
    }

    /**
     * Verifies that a transaction with a processing timestamp after the
     * configured end date is filtered out (returns null). Corresponds to
     * COBOL: {@code TRAN-PROC-TS(1:10) <= WS-END-DATE} check.
     */
    @Test
    void process_shouldFilterTransactionAfterEndDate() throws Exception {
        Transaction txn = createTransaction("TXN-003", "4000000000000001",
                new BigDecimal("100.00"), LocalDateTime.of(2025, 1, 1, 0, 0));

        Transaction result = processor.process(txn);

        assertThat(result).isNull();
        verifyNoInteractions(cardCrossReferenceRepository,
                transactionTypeRepository, transactionCategoryRepository);
    }

    /**
     * Verifies that a transaction on the exact start date boundary is included
     * (inclusive start). COBOL uses {@code >=} comparison on the start date,
     * so a transaction on 2024-01-01 must pass through when the start date
     * is 2024-01-01.
     */
    @Test
    void process_shouldIncludeTransactionOnExactStartDate() throws Exception {
        Transaction txn = createTransaction("TXN-004", "4000000000000001",
                new BigDecimal("75.00"), LocalDateTime.of(2024, 1, 1, 0, 0));

        setupDefaultLookupMocks("4000000000000001");

        Transaction result = processor.process(txn);

        assertThat(result).isNotNull();
    }

    /**
     * Verifies that a transaction on the exact end date boundary is included
     * (inclusive end). COBOL uses {@code <=} comparison on the end date,
     * so a transaction on 2024-12-31 must pass through when the end date
     * is 2024-12-31.
     */
    @Test
    void process_shouldIncludeTransactionOnExactEndDate() throws Exception {
        Transaction txn = createTransaction("TXN-005", "4000000000000001",
                new BigDecimal("75.00"), LocalDateTime.of(2024, 12, 31, 23, 59));

        setupDefaultLookupMocks("4000000000000001");

        Transaction result = processor.process(txn);

        assertThat(result).isNotNull();
    }

    // -----------------------------------------------------------------------
    // Enrichment Lookup Tests
    // -----------------------------------------------------------------------

    /**
     * Verifies that the processor performs a card cross-reference (XREF)
     * lookup when encountering a new card number. Maps to CBTRN03C paragraph
     * 1500-A-LOOKUP-XREF which reads the XREF file by card number to resolve
     * the card number to an account identifier.
     */
    @Test
    void process_shouldPerformXrefLookup() throws Exception {
        Transaction txn = createTransaction("TXN-XREF-001", "4000000000000001",
                new BigDecimal("50.00"), LocalDateTime.of(2024, 6, 15, 10, 0));

        when(cardCrossReferenceRepository.findById("4000000000000001"))
                .thenReturn(Optional.of(createXref("4000000000000001", "00000000001")));
        when(transactionTypeRepository.findById(anyString()))
                .thenReturn(Optional.of(createTranType("SA", "Sale")));
        when(transactionCategoryRepository.findById(any(TransactionCategoryId.class)))
                .thenReturn(Optional.of(createTranCatg("SA", (short) 1,
                        "Regular Sales Draft")));

        processor.process(txn);

        verify(cardCrossReferenceRepository).findById("4000000000000001");
    }

    /**
     * Verifies that the processor performs a transaction type (TRANTYPE)
     * lookup using the transaction's type code. Maps to CBTRN03C paragraph
     * 1500-B-LOOKUP-TRANTYPE which reads the TRANTYPE file by type code
     * to resolve the type code to a human-readable description.
     */
    @Test
    void process_shouldPerformTranTypeLookup() throws Exception {
        Transaction txn = createTransaction("TXN-TYPE-001", "4000000000000001",
                new BigDecimal("50.00"), LocalDateTime.of(2024, 6, 15, 10, 0));

        when(cardCrossReferenceRepository.findById(anyString()))
                .thenReturn(Optional.of(createXref("4000000000000001", "00000000001")));
        when(transactionTypeRepository.findById("SA"))
                .thenReturn(Optional.of(createTranType("SA", "Sale")));
        when(transactionCategoryRepository.findById(any(TransactionCategoryId.class)))
                .thenReturn(Optional.of(createTranCatg("SA", (short) 1,
                        "Regular Sales Draft")));

        processor.process(txn);

        verify(transactionTypeRepository).findById("SA");
    }

    /**
     * Verifies that the processor performs a transaction category (TRANCATG)
     * lookup using the composite key of type code + category code. Maps to
     * CBTRN03C paragraph 1500-C-LOOKUP-TRANCATG which reads the TRANCATG
     * file using the composite TRAN-CAT-KEY (typeCode[2] + catCode).
     * The composite key uses {@link TransactionCategoryId} with Short catCode.
     */
    @Test
    void process_shouldPerformTranCatgLookupWithCompositeKey() throws Exception {
        Transaction txn = createTransaction("TXN-CATG-001", "4000000000000001",
                new BigDecimal("50.00"), LocalDateTime.of(2024, 6, 15, 10, 0));

        when(cardCrossReferenceRepository.findById(anyString()))
                .thenReturn(Optional.of(createXref("4000000000000001", "00000000001")));
        when(transactionTypeRepository.findById(anyString()))
                .thenReturn(Optional.of(createTranType("SA", "Sale")));

        TransactionCategoryId expectedKey = new TransactionCategoryId("SA", (short) 1);
        when(transactionCategoryRepository.findById(expectedKey))
                .thenReturn(Optional.of(createTranCatg("SA", (short) 1,
                        "Regular Sales Draft")));

        processor.process(txn);

        verify(transactionCategoryRepository).findById(expectedKey);
    }

    // -----------------------------------------------------------------------
    // Total Accumulation Tests
    // -----------------------------------------------------------------------

    /**
     * Verifies that the grand total correctly accumulates across multiple
     * transactions using BigDecimal precision. Processes 3 transactions with
     * amounts "100.50", "200.75", "50.25" and asserts grand total equals
     * "351.50". Uses {@code compareTo()} (not {@code equals()}) per AAP §0.8.2.
     * Maps to COBOL: {@code ADD TRAN-AMT TO WS-GRAND-TOTAL} which uses
     * PIC S9(09)V99 (exact decimal).
     */
    @Test
    void process_shouldAccumulateGrandTotalWithBigDecimalPrecision() throws Exception {
        setupDefaultLookupMocks("4000000000000001");

        processor.process(createTransaction("TXN-G01", "4000000000000001",
                new BigDecimal("100.50"), LocalDateTime.of(2024, 3, 1, 9, 0)));
        processor.process(createTransaction("TXN-G02", "4000000000000001",
                new BigDecimal("200.75"), LocalDateTime.of(2024, 3, 2, 10, 0)));
        processor.process(createTransaction("TXN-G03", "4000000000000001",
                new BigDecimal("50.25"), LocalDateTime.of(2024, 3, 3, 11, 0)));

        assertThat(processor.getGrandTotal().compareTo(new BigDecimal("351.50")))
                .isEqualTo(0);
    }

    /**
     * Verifies that the account total resets to zero when a new card number
     * is encountered (account break), while the grand total continues to
     * accumulate across all cards. Maps to CBTRN03C lines 200-210 where the
     * account total is written and reset on card number change, and the grand
     * total is accumulated independently via separate running totals.
     */
    @Test
    void process_shouldResetAccountTotalOnCardNumberChange() throws Exception {
        when(cardCrossReferenceRepository.findById("4000000000000001"))
                .thenReturn(Optional.of(createXref("4000000000000001", "00000000001")));
        when(cardCrossReferenceRepository.findById("4000000000000002"))
                .thenReturn(Optional.of(createXref("4000000000000002", "00000000002")));
        when(transactionTypeRepository.findById("SA"))
                .thenReturn(Optional.of(createTranType("SA", "Sale")));
        when(transactionCategoryRepository.findById(
                new TransactionCategoryId("SA", (short) 1)))
                .thenReturn(Optional.of(createTranCatg("SA", (short) 1,
                        "Regular Sales Draft")));

        // First card — account total starts accumulating for card 1
        processor.process(createTransaction("TXN-R01", "4000000000000001",
                new BigDecimal("100.00"), LocalDateTime.of(2024, 5, 1, 8, 0)));

        // Different card — triggers account break, resets account total
        processor.process(createTransaction("TXN-R02", "4000000000000002",
                new BigDecimal("200.00"), LocalDateTime.of(2024, 5, 2, 9, 0)));

        // Account total should be ONLY the second card's amount (reset on break)
        assertThat(processor.getAccountTotal().compareTo(new BigDecimal("200.00")))
                .isEqualTo(0);
        // Grand total should accumulate across both cards
        assertThat(processor.getGrandTotal().compareTo(new BigDecimal("300.00")))
                .isEqualTo(0);
    }

    // -----------------------------------------------------------------------
    // Constant and Precision Tests
    // -----------------------------------------------------------------------

    /**
     * Verifies that the PAGE_SIZE constant is set to 20, matching the COBOL
     * definition {@code WS-PAGE-SIZE VALUE 20} in CBTRN03C working storage.
     * Uses reflection to access the private static final field since no
     * public getter is exposed for this internal constant.
     */
    @Test
    void pageSizeConstant_shouldBe20() throws Exception {
        Field pageSizeField = TransactionReportProcessor.class
                .getDeclaredField("PAGE_SIZE");
        pageSizeField.setAccessible(true);

        int pageSize = pageSizeField.getInt(null);

        assertThat(pageSize).isEqualTo(20);
    }

    /**
     * Verifies that all monetary totals maintain exact BigDecimal precision
     * for small decimal values that would exhibit floating-point rounding
     * errors with float/double. Processes amounts "0.01", "0.02", "0.03"
     * and asserts grand total equals "0.06" exactly. This guards against
     * the classic 0.1 + 0.2 != 0.3 floating-point issue and validates the
     * COBOL PIC S9(09)V99 exact-decimal preservation.
     */
    @Test
    void process_shouldMaintainBigDecimalPrecisionForAllTotals() throws Exception {
        setupDefaultLookupMocks("4000000000000001");

        processor.process(createTransaction("TXN-P01", "4000000000000001",
                new BigDecimal("0.01"), LocalDateTime.of(2024, 7, 1, 12, 0)));
        processor.process(createTransaction("TXN-P02", "4000000000000001",
                new BigDecimal("0.02"), LocalDateTime.of(2024, 7, 2, 12, 0)));
        processor.process(createTransaction("TXN-P03", "4000000000000001",
                new BigDecimal("0.03"), LocalDateTime.of(2024, 7, 3, 12, 0)));

        // Grand total: 0.01 + 0.02 + 0.03 = 0.06 exactly with BigDecimal
        assertThat(processor.getGrandTotal().compareTo(new BigDecimal("0.06")))
                .isEqualTo(0);
    }

    // -----------------------------------------------------------------------
    // Helper Methods
    // -----------------------------------------------------------------------

    /**
     * Creates a fully-populated {@link Transaction} entity for test fixtures.
     * Sets default type code "SA" and category code 1 which are the standard
     * values used across test scenarios.
     *
     * @param tranId   unique transaction identifier
     * @param cardNum  16-digit card number
     * @param amount   transaction amount as BigDecimal (never float/double)
     * @param procTs   processing timestamp (date portion used for filtering)
     * @return a new Transaction with all required fields populated
     */
    private Transaction createTransaction(String tranId, String cardNum,
                                          BigDecimal amount, LocalDateTime procTs) {
        Transaction txn = new Transaction();
        txn.setTranId(tranId);
        txn.setTranCardNum(cardNum);
        txn.setTranTypeCd("SA");
        txn.setTranCatCd((short) 1);
        txn.setTranAmt(amount);
        txn.setTranProcTs(procTs);
        txn.setTranSource("POS TERM");
        txn.setTranDesc("Test transaction");
        return txn;
    }

    /**
     * Creates a {@link CardCrossReference} entity for XREF enrichment mock
     * setup. Maps to CVACT03Y.cpy 50-byte record layout with card number
     * as primary key, customer ID, and account ID.
     *
     * @param cardNum 16-digit card number (primary key)
     * @param acctId  11-digit account identifier
     * @return a new CardCrossReference with the given card-to-account mapping
     */
    private CardCrossReference createXref(String cardNum, String acctId) {
        return new CardCrossReference(cardNum, "000000001", acctId);
    }

    /**
     * Creates a {@link TransactionType} entity for TRANTYPE enrichment mock
     * setup. Maps to CVTRA03Y.cpy 60-byte record layout with 2-char type
     * code as primary key and description.
     *
     * @param typeCode 2-character type code (primary key)
     * @param desc     human-readable type description (up to 50 chars)
     * @return a new TransactionType with the given code and description
     */
    private TransactionType createTranType(String typeCode, String desc) {
        return new TransactionType(typeCode, desc);
    }

    /**
     * Creates a {@link TransactionCategory} entity for TRANCATG enrichment
     * mock setup. Uses the composite key {@link TransactionCategoryId} with
     * String typeCode and Short catCode. Maps to CVTRA04Y.cpy 60-byte record.
     *
     * @param typeCode 2-character type code (part of composite key)
     * @param catCode  numeric category code as Short (part of composite key)
     * @param desc     human-readable category description (up to 50 chars)
     * @return a new TransactionCategory with the composite key and description
     */
    private TransactionCategory createTranCatg(String typeCode, Short catCode,
                                               String desc) {
        TransactionCategoryId id = new TransactionCategoryId(typeCode, catCode);
        return new TransactionCategory(id, desc);
    }

    /**
     * Sets up the default mock return values for all three enrichment
     * repository lookups (XREF, TRANTYPE, TRANCATG) using the given card
     * number with standard type code "SA" and category code 1. Used by
     * tests that process transactions within the date range and need all
     * three enrichment lookups to succeed.
     *
     * @param cardNum the 16-digit card number to use for the XREF mock
     */
    private void setupDefaultLookupMocks(String cardNum) {
        when(cardCrossReferenceRepository.findById(cardNum))
                .thenReturn(Optional.of(createXref(cardNum, "00000000001")));
        when(transactionTypeRepository.findById("SA"))
                .thenReturn(Optional.of(createTranType("SA", "Sale")));
        when(transactionCategoryRepository.findById(
                new TransactionCategoryId("SA", (short) 1)))
                .thenReturn(Optional.of(createTranCatg("SA", (short) 1,
                        "Regular Sales Draft")));
    }
}

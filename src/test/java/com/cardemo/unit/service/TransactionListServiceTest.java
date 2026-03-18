package com.cardemo.unit.service;

import com.cardemo.model.dto.TransactionDto;
import com.cardemo.model.entity.Transaction;
import com.cardemo.repository.TransactionRepository;
import com.cardemo.service.transaction.TransactionListService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TransactionListService} — migrated from COTRN00C.cbl paginated
 * transaction browse with 10 rows per page, ascending sort by transaction ID (VSAM KSDS
 * STARTBR + READNEXT), optional startTransactionId filtering, and Transaction entity to
 * TransactionDto mapping with BigDecimal amounts (PIC S9(09)V99 COMP-3).
 *
 * <p>Uses JUnit 5 + Mockito (NO Spring context loading). Verifies:
 * <ul>
 *   <li>PAGE_SIZE = 10 (matching COBOL BMS screen definition — PERFORM UNTIL WS-IDX >= 11)</li>
 *   <li>Sort.by("tranId").ascending() — preserving VSAM KSDS key sequence</li>
 *   <li>startTransactionId filter → findByTranIdGreaterThanEqual; null/blank → findAll</li>
 *   <li>BigDecimal for all financial amounts; compareTo() for assertions per AAP §0.8.2</li>
 *   <li>All 13 fields correctly mapped from Transaction entity to TransactionDto</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class TransactionListServiceTest {

    /**
     * Expected page size matching COBOL COTRN00C.cbl BMS screen rows.
     * COTRN00C uses PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > 10
     * to populate 10 transaction rows on the 3270 screen.
     */
    private static final int PAGE_SIZE = 10;

    @Mock
    private TransactionRepository transactionRepository;

    @InjectMocks
    private TransactionListService transactionListService;

    private Transaction testTransaction;
    private LocalDateTime testOrigTs;
    private LocalDateTime testProcTs;

    /**
     * Initializes a fully populated Transaction entity fixture with all 13 data fields
     * matching CVTRA05Y.cpy TRAN-RECORD layout (350 bytes). Uses BigDecimal for
     * TRAN-AMT (PIC S9(09)V99 COMP-3) — zero floating-point substitution enforced.
     */
    @BeforeEach
    void setUp() {
        testOrigTs = LocalDateTime.of(2024, 3, 15, 10, 30, 0);
        testProcTs = LocalDateTime.of(2024, 3, 15, 14, 45, 0);

        testTransaction = new Transaction();
        testTransaction.setTranId("0000000000000001");
        testTransaction.setTranTypeCd("SA");
        testTransaction.setTranCatCd((short) 5001);
        testTransaction.setTranSource("POS TERM");
        testTransaction.setTranDesc("GROCERY STORE PURCHASE");
        testTransaction.setTranAmt(new BigDecimal("125.99"));
        testTransaction.setTranCardNum("4111111111111111");
        testTransaction.setTranMerchantId("123456789");
        testTransaction.setTranMerchantName("WALMART SUPERCENTER");
        testTransaction.setTranMerchantCity("ARLINGTON");
        testTransaction.setTranMerchantZip("76010");
        testTransaction.setTranOrigTs(testOrigTs);
        testTransaction.setTranProcTs(testProcTs);
    }

    // -------------------------------------------------------------------------
    // PAGE_SIZE Constant — CRITICAL: must be exactly 10
    // -------------------------------------------------------------------------

    /**
     * CRITICAL: Verifies that the service uses page size 10, matching the COBOL
     * COTRN00C.cbl BMS screen definition where PERFORM UNTIL WS-IDX >= 11 reads
     * up to 10 transaction records per page for the 3270 terminal display.
     */
    @Test
    void testListTransactions_pageSize10() {
        // Arrange — stub repository to return a single-item page
        Pageable expectedPageable = PageRequest.of(0, PAGE_SIZE, Sort.by(Sort.Direction.ASC, "tranId"));
        Page<Transaction> mockPage = new PageImpl<>(List.of(testTransaction), expectedPageable, 1);
        when(transactionRepository.findAll(any(Pageable.class))).thenReturn(mockPage);

        // Act
        transactionListService.listTransactions(null, 0);

        // Assert — capture Pageable argument and verify page size is exactly 10
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(transactionRepository).findAll(pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(10);
    }

    // -------------------------------------------------------------------------
    // Pagination
    // -------------------------------------------------------------------------

    /**
     * Verifies that requesting page 0 returns a non-null Page of TransactionDto
     * with the expected content size and total element count.
     */
    @Test
    void testListTransactions_firstPage_returnsPage() {
        // Arrange
        Pageable pageable = PageRequest.of(0, PAGE_SIZE, Sort.by(Sort.Direction.ASC, "tranId"));
        Page<Transaction> mockPage = new PageImpl<>(List.of(testTransaction), pageable, 1);
        when(transactionRepository.findAll(any(Pageable.class))).thenReturn(mockPage);

        // Act
        Page<TransactionDto> result = transactionListService.listTransactions(null, 0);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    /**
     * Verifies that when no transactions exist, the service returns an empty page
     * without throwing exceptions — matching COBOL COTRN00C behavior where an empty
     * STARTBR result simply displays an empty screen.
     */
    @Test
    void testListTransactions_emptyResult_returnsEmptyPage() {
        // Arrange
        Pageable pageable = PageRequest.of(0, PAGE_SIZE, Sort.by(Sort.Direction.ASC, "tranId"));
        Page<Transaction> emptyPage = new PageImpl<>(Collections.emptyList(), pageable, 0);
        when(transactionRepository.findAll(any(Pageable.class))).thenReturn(emptyPage);

        // Act
        Page<TransactionDto> result = transactionListService.listTransactions(null, 0);

        // Assert — empty page, no exception
        assertThat(result).isNotNull();
        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isEqualTo(0);
    }

    /**
     * Verifies that the service requests Sort.by("tranId").ascending(), preserving
     * the VSAM KSDS ascending key sequence used by COBOL STARTBR + READNEXT in
     * COTRN00C.cbl PROCESS-PAGE-FORWARD paragraph.
     */
    @Test
    void testListTransactions_sortByTranIdAscending() {
        // Arrange
        Pageable pageable = PageRequest.of(0, PAGE_SIZE, Sort.by(Sort.Direction.ASC, "tranId"));
        Page<Transaction> mockPage = new PageImpl<>(List.of(testTransaction), pageable, 1);
        when(transactionRepository.findAll(any(Pageable.class))).thenReturn(mockPage);

        // Act
        transactionListService.listTransactions(null, 0);

        // Assert — capture Pageable and verify ascending sort by tranId
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(transactionRepository).findAll(pageableCaptor.capture());
        Sort sort = pageableCaptor.getValue().getSort();
        Sort.Order order = sort.getOrderFor("tranId");
        assertThat(order).isNotNull();
        assertThat(order.getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    /**
     * Verifies hasNext metadata is true when more records exist beyond the current
     * page — matching COBOL COTRN00C behavior where after reading 10 records, an
     * extra READNEXT determines if CDEMO-CT00-NEXT-PAGE-FLG should be set.
     */
    @Test
    void testListTransactions_hasNextPage() {
        // Arrange — create 10 transactions for page 0 with 20 total (hasNext = true)
        List<Transaction> transactions = new ArrayList<>();
        for (int i = 0; i < PAGE_SIZE; i++) {
            Transaction txn = new Transaction();
            txn.setTranId(String.format("%016d", i + 1));
            txn.setTranAmt(BigDecimal.valueOf(10L));
            transactions.add(txn);
        }
        Pageable pageable = PageRequest.of(0, PAGE_SIZE, Sort.by(Sort.Direction.ASC, "tranId"));
        Page<Transaction> mockPage = new PageImpl<>(transactions, pageable, 20);
        when(transactionRepository.findAll(any(Pageable.class))).thenReturn(mockPage);

        // Act
        Page<TransactionDto> result = transactionListService.listTransactions(null, 0);

        // Assert — page metadata indicates more records
        assertThat(result.hasNext()).isTrue();
        assertThat(result.hasPrevious()).isFalse();
        assertThat(result.getTotalPages()).isEqualTo(2);
        assertThat(result.getContent()).hasSize(PAGE_SIZE);
    }

    // -------------------------------------------------------------------------
    // Transaction ID Filtering
    // -------------------------------------------------------------------------

    /**
     * Verifies that a non-null, non-blank startTransactionId triggers
     * findByTranIdGreaterThanEqual() instead of findAll() — mapping the COBOL
     * STARTBR with GTEQ (greater-than-or-equal) browse start position from
     * COTRN00C.cbl TRNIDINI field.
     */
    @Test
    void testListTransactions_withStartTransactionId_filtersCorrectly() {
        // Arrange
        String startTranId = "0000000000000005";
        Pageable pageable = PageRequest.of(0, PAGE_SIZE, Sort.by(Sort.Direction.ASC, "tranId"));
        Page<Transaction> mockPage = new PageImpl<>(List.of(testTransaction), pageable, 1);
        when(transactionRepository.findByTranIdGreaterThanEqual(eq(startTranId), any(Pageable.class)))
                .thenReturn(mockPage);

        // Act
        Page<TransactionDto> result = transactionListService.listTransactions(startTranId, 0);

        // Assert — filtered query invoked, findAll NOT invoked
        assertThat(result).isNotNull();
        assertThat(result.getContent()).hasSize(1);
        verify(transactionRepository).findByTranIdGreaterThanEqual(eq(startTranId), any(Pageable.class));
        verify(transactionRepository, never()).findAll(any(Pageable.class));
    }

    /**
     * Verifies that a null startTransactionId results in findAll() being called
     * without any filter — equivalent to COBOL STARTBR from LOW-VALUES (beginning
     * of file) when TRNIDINI is not entered by the operator.
     */
    @Test
    void testListTransactions_withoutFilter_returnsAll() {
        // Arrange
        Pageable pageable = PageRequest.of(0, PAGE_SIZE, Sort.by(Sort.Direction.ASC, "tranId"));
        Page<Transaction> mockPage = new PageImpl<>(List.of(testTransaction), pageable, 1);
        when(transactionRepository.findAll(any(Pageable.class))).thenReturn(mockPage);

        // Act
        Page<TransactionDto> result = transactionListService.listTransactions(null, 0);

        // Assert — unfiltered findAll invoked, filtered query NOT invoked
        assertThat(result).isNotNull();
        verify(transactionRepository).findAll(any(Pageable.class));
        verify(transactionRepository, never())
                .findByTranIdGreaterThanEqual(any(String.class), any(Pageable.class));
    }

    /**
     * Verifies that a blank (whitespace-only) startTransactionId is treated the
     * same as null — no filter applied. The service uses String.isBlank() to detect
     * this case, matching COBOL evaluation of SPACES/LOW-VALUES in TRNIDINI.
     */
    @Test
    void testListTransactions_blankFilter_returnsAll() {
        // Arrange
        Pageable pageable = PageRequest.of(0, PAGE_SIZE, Sort.by(Sort.Direction.ASC, "tranId"));
        Page<Transaction> mockPage = new PageImpl<>(List.of(testTransaction), pageable, 1);
        when(transactionRepository.findAll(any(Pageable.class))).thenReturn(mockPage);

        // Act — blank string filter
        Page<TransactionDto> result = transactionListService.listTransactions("   ", 0);

        // Assert — treated as no filter, findAll invoked
        assertThat(result).isNotNull();
        verify(transactionRepository).findAll(any(Pageable.class));
        verify(transactionRepository, never())
                .findByTranIdGreaterThanEqual(any(String.class), any(Pageable.class));
    }

    // -------------------------------------------------------------------------
    // BigDecimal Fields — AAP §0.8.2 Decimal Precision Rules
    // -------------------------------------------------------------------------

    /**
     * Verifies that the amount field in returned TransactionDto is a BigDecimal
     * instance — enforcing zero floating-point substitution per AAP §0.8.2 for
     * TRAN-AMT (PIC S9(09)V99 COMP-3) from CVTRA05Y.cpy.
     */
    @Test
    void testListTransactions_amountFieldsBigDecimal() {
        // Arrange
        Pageable pageable = PageRequest.of(0, PAGE_SIZE, Sort.by(Sort.Direction.ASC, "tranId"));
        Page<Transaction> mockPage = new PageImpl<>(List.of(testTransaction), pageable, 1);
        when(transactionRepository.findAll(any(Pageable.class))).thenReturn(mockPage);

        // Act
        Page<TransactionDto> result = transactionListService.listTransactions(null, 0);

        // Assert — amount must be BigDecimal, never float or double
        TransactionDto dto = result.getContent().get(0);
        assertThat(dto.getTranAmt()).isNotNull();
        assertThat(dto.getTranAmt()).isInstanceOf(BigDecimal.class);
    }

    /**
     * Verifies that BigDecimal.compareTo() (not equals()) is used for financial
     * amount assertions, per AAP §0.8.2. BigDecimal.equals() is scale-sensitive
     * and would fail for "125.99" vs "125.990"; compareTo() performs value comparison.
     */
    @Test
    void testListTransactions_amountCompareTo() {
        // Arrange
        Pageable pageable = PageRequest.of(0, PAGE_SIZE, Sort.by(Sort.Direction.ASC, "tranId"));
        Page<Transaction> mockPage = new PageImpl<>(List.of(testTransaction), pageable, 1);
        when(transactionRepository.findAll(any(Pageable.class))).thenReturn(mockPage);

        // Act
        Page<TransactionDto> result = transactionListService.listTransactions(null, 0);

        // Assert — use compareTo() == 0, NOT equals(), per AAP §0.8.2
        TransactionDto dto = result.getContent().get(0);
        BigDecimal expectedAmount = new BigDecimal("125.99");
        assertThat(dto.getTranAmt().compareTo(expectedAmount)).isZero();
        // Also verify non-zero comparison works
        assertThat(dto.getTranAmt().compareTo(BigDecimal.ZERO)).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // DTO Mapping — Transaction entity → TransactionDto (all 13 fields)
    // -------------------------------------------------------------------------

    /**
     * Verifies complete field mapping from Transaction entity to TransactionDto for
     * all 13 fields defined in CVTRA05Y.cpy TRAN-RECORD (350 bytes). Key mappings:
     * <ul>
     *   <li>tranCatCd: Short → String via String.format("%04d", ...)</li>
     *   <li>tranMerchantId → tranMerchId (abbreviated DTO getter)</li>
     *   <li>tranMerchantName → tranMerchName (abbreviated DTO getter)</li>
     *   <li>tranMerchantCity → tranMerchCity (abbreviated DTO getter)</li>
     *   <li>tranMerchantZip → tranMerchZip (abbreviated DTO getter)</li>
     *   <li>All other fields: direct mapping with same types</li>
     * </ul>
     */
    @Test
    void testListTransactions_entityToDtoMapping() {
        // Arrange
        Pageable pageable = PageRequest.of(0, PAGE_SIZE, Sort.by(Sort.Direction.ASC, "tranId"));
        Page<Transaction> mockPage = new PageImpl<>(List.of(testTransaction), pageable, 1);
        when(transactionRepository.findAll(any(Pageable.class))).thenReturn(mockPage);

        // Act
        Page<TransactionDto> result = transactionListService.listTransactions(null, 0);

        // Assert — verify all 13 fields mapped correctly from entity to DTO
        assertThat(result.getContent()).hasSize(1);
        TransactionDto dto = result.getContent().get(0);

        // Field 1: tranId (PIC X(16)) — direct mapping
        assertThat(dto.getTranId()).isEqualTo("0000000000000001");

        // Field 2: tranTypeCd (PIC X(02)) — direct mapping
        assertThat(dto.getTranTypeCd()).isEqualTo("SA");

        // Field 3: tranCatCd — Short 5001 → String "5001" via String.format("%04d", ...)
        assertThat(dto.getTranCatCd()).isEqualTo("5001");

        // Field 4: tranSource (PIC X(10)) — direct mapping
        assertThat(dto.getTranSource()).isEqualTo("POS TERM");

        // Field 5: tranDesc (PIC X(100)) — direct mapping
        assertThat(dto.getTranDesc()).isEqualTo("GROCERY STORE PURCHASE");

        // Field 6: tranAmt (PIC S9(09)V99 COMP-3) — BigDecimal, compareTo per AAP §0.8.2
        assertThat(dto.getTranAmt().compareTo(new BigDecimal("125.99"))).isZero();

        // Field 7: tranCardNum (PIC X(16)) — direct mapping
        assertThat(dto.getTranCardNum()).isEqualTo("4111111111111111");

        // Field 8: tranMerchantId → tranMerchId (abbreviated DTO name)
        assertThat(dto.getTranMerchId()).isEqualTo("123456789");

        // Field 9: tranMerchantName → tranMerchName (abbreviated DTO name)
        assertThat(dto.getTranMerchName()).isEqualTo("WALMART SUPERCENTER");

        // Field 10: tranMerchantCity → tranMerchCity (abbreviated DTO name)
        assertThat(dto.getTranMerchCity()).isEqualTo("ARLINGTON");

        // Field 11: tranMerchantZip → tranMerchZip (abbreviated DTO name)
        assertThat(dto.getTranMerchZip()).isEqualTo("76010");

        // Field 12: tranOrigTs (TRAN-ORIG-TS PIC X(26)) — LocalDateTime mapping
        assertThat(dto.getTranOrigTs()).isEqualTo(testOrigTs);

        // Field 13: tranProcTs (TRAN-PROC-TS PIC X(26)) — LocalDateTime mapping
        assertThat(dto.getTranProcTs()).isEqualTo(testProcTs);
    }
}

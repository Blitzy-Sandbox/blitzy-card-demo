package com.cardemo.unit.batch;

import com.cardemo.batch.processors.TransactionCombineProcessor;
import com.cardemo.model.entity.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TransactionCombineProcessor} — the simplest
 * processor in the batch pipeline that replaces {@code COMBTRAN.jcl}
 * DFSORT + IDCAMS REPRO logic.
 *
 * <p>There is no corresponding COBOL program for this processor; the
 * original JCL (53 lines, commit 27d6c6f) is a pure utility that:
 * <ol>
 *   <li>STEP05R — DFSORT: sorts merged transaction inputs by
 *       {@code TRAN-ID} (position 1, length 16, character ascending —
 *       {@code SORT FIELDS=(TRAN-ID,A)})</li>
 *   <li>STEP10 — IDCAMS REPRO: bulk loads sorted output to
 *       {@code TRANSACT.VSAM.KSDS}</li>
 * </ol>
 *
 * <p>Test coverage:
 * <ul>
 *   <li>Pass-through behavior of {@code process()} — returns same
 *       object reference unchanged</li>
 *   <li>{@code TRAN_ID_COMPARATOR} sort ordering — validates DFSORT
 *       {@code SORT FIELDS=(1,16,CH,A)} ascending character sort</li>
 *   <li>Lexicographic ordering correctness matching COBOL CH type</li>
 *   <li>Equal ID comparator contract (returns 0)</li>
 *   <li>BigDecimal precision preservation through pass-through
 *       (AAP §0.8.2 — compareTo, never equals)</li>
 * </ul>
 *
 * <p>This is a pure unit test — no Spring context loading, no mocks
 * needed (the processor has zero repository dependencies).
 *
 * @see TransactionCombineProcessor
 * @see Transaction
 */
@ExtendWith(MockitoExtension.class)
class TransactionCombineProcessorTest {

    /**
     * The processor under test. Instantiated directly via no-arg
     * constructor in {@link #setUp()} — no mocks or Spring context
     * required since this processor has no injected dependencies.
     */
    private TransactionCombineProcessor processor;

    /**
     * Initializes a fresh {@link TransactionCombineProcessor} instance
     * before each test method to ensure test isolation.
     */
    @BeforeEach
    void setUp() {
        processor = new TransactionCombineProcessor();
    }

    // -------------------------------------------------------------------
    // process() pass-through tests
    // -------------------------------------------------------------------

    /**
     * Verifies that {@code process()} returns the exact same object
     * reference it receives — confirming pure pass-through semantics.
     * The processor exists only to participate in the Spring Batch step
     * pipeline; sorting is handled at the step level via
     * {@link TransactionCombineProcessor#TRAN_ID_COMPARATOR}.
     *
     * <p>All fields must remain identical after processing, including
     * the BigDecimal {@code tranAmt} (no precision loss).
     */
    @Test
    void process_shouldReturnItemUnchanged() throws Exception {
        // Arrange — create a fully populated transaction
        Transaction transaction = createTransaction("0000000000000001");
        transaction.setTranAmt(new BigDecimal("5432.10"));
        transaction.setTranTypeCd("SA");
        transaction.setTranCatCd((short) 5001);
        transaction.setTranSource("POS TERM");
        transaction.setTranDesc("Electronics purchase at Best Buy");
        transaction.setTranCardNum("4111111111111111");
        transaction.setTranMerchantId("123456789");
        transaction.setTranMerchantName("Best Buy #1234");
        transaction.setTranMerchantCity("Seattle");
        transaction.setTranMerchantZip("98101");
        transaction.setTranOrigTs(LocalDateTime.of(2024, 1, 15, 10, 30, 0));
        transaction.setTranProcTs(LocalDateTime.of(2024, 1, 15, 14, 45, 30));

        // Act
        Transaction result = processor.process(transaction);

        // Assert — same object reference (pass-through)
        assertThat(result).isSameAs(transaction);

        // Assert — all fields unchanged
        assertThat(result.getTranId()).isEqualTo("0000000000000001");
        assertThat(result.getTranAmt().compareTo(new BigDecimal("5432.10"))).isEqualTo(0);
    }

    /**
     * Verifies that {@code process()} never returns null for a valid
     * transaction item. In Spring Batch, returning null from an
     * {@code ItemProcessor} signals that the item should be filtered
     * out — the combine processor must never filter.
     */
    @Test
    void process_shouldNotReturnNullForValidItem() throws Exception {
        // Arrange — minimal valid transaction
        Transaction transaction = createTransaction("0000000000000042");

        // Act
        Transaction result = processor.process(transaction);

        // Assert — result must not be null (item must not be filtered)
        assertThat(result).isNotNull();
    }

    // -------------------------------------------------------------------
    // TRAN_ID_COMPARATOR sorting tests
    // -------------------------------------------------------------------

    /**
     * Verifies that {@code TRAN_ID_COMPARATOR} sorts Transaction
     * records by {@code tranId} in ascending order — the exact
     * equivalent of DFSORT {@code SORT FIELDS=(1,16,CH,A)} from
     * COMBTRAN.jcl STEP05R.
     *
     * <p>Creates 5 transactions with zero-padded 16-character IDs in
     * random order and asserts they sort to ascending sequence after
     * applying the comparator.
     */
    @Test
    void comparator_shouldSortByTranIdAscending() {
        // Arrange — 5 transactions in random order
        List<Transaction> transactions = new ArrayList<>();
        transactions.add(createTransaction("0000000000000005"));
        transactions.add(createTransaction("0000000000000001"));
        transactions.add(createTransaction("0000000000000003"));
        transactions.add(createTransaction("0000000000000002"));
        transactions.add(createTransaction("0000000000000004"));

        // Act — sort using the DFSORT-equivalent comparator
        transactions.sort(TransactionCombineProcessor.TRAN_ID_COMPARATOR);

        // Assert — ascending order matching SORT FIELDS=(TRAN-ID,A)
        assertThat(transactions).hasSize(5);
        assertThat(transactions.get(0).getTranId()).isEqualTo("0000000000000001");
        assertThat(transactions.get(1).getTranId()).isEqualTo("0000000000000002");
        assertThat(transactions.get(2).getTranId()).isEqualTo("0000000000000003");
        assertThat(transactions.get(3).getTranId()).isEqualTo("0000000000000004");
        assertThat(transactions.get(4).getTranId()).isEqualTo("0000000000000005");
    }

    /**
     * Verifies that {@code TRAN_ID_COMPARATOR} handles lexicographic
     * (character-based) ordering correctly — matching the COBOL
     * {@code CH} (character) sort type. This ensures that string-based
     * transaction IDs sort by their natural character code points, not
     * by any numeric interpretation.
     *
     * <p>Uses hyphenated timestamp-style IDs to validate that the sort
     * behaves identically to COBOL EBCDIC character sorting for the
     * ASCII subset (alphanumerics and hyphens).
     */
    @Test
    void comparator_shouldHandleLexicographicOrdering() {
        // Arrange — transactions with timestamp-style IDs in random order
        List<Transaction> transactions = new ArrayList<>();
        transactions.add(createTransaction("2024-01-15-00003"));
        transactions.add(createTransaction("2024-01-15-00001"));
        transactions.add(createTransaction("2024-01-15-00002"));

        // Act — sort using lexicographic comparator
        transactions.sort(TransactionCombineProcessor.TRAN_ID_COMPARATOR);

        // Assert — ascending lexicographic order (matches COBOL CH type)
        assertThat(transactions).hasSize(3);
        assertThat(transactions.get(0).getTranId()).isEqualTo("2024-01-15-00001");
        assertThat(transactions.get(1).getTranId()).isEqualTo("2024-01-15-00002");
        assertThat(transactions.get(2).getTranId()).isEqualTo("2024-01-15-00003");
    }

    /**
     * Verifies that {@code TRAN_ID_COMPARATOR} returns 0 when both
     * transactions have equal {@code tranId} values, satisfying the
     * {@link java.util.Comparator} contract for equal elements.
     *
     * <p>In the DFSORT context, equal keys preserve their input order
     * (stable sort). Java's {@code List.sort()} is also stable, so
     * this test confirms the comparator correctly identifies equality.
     */
    @Test
    void comparator_shouldHandleEqualIds() {
        // Arrange — two transactions with identical IDs
        Transaction txn1 = createTransaction("0000000000000099");
        Transaction txn2 = createTransaction("0000000000000099");

        // Act
        int comparison = TransactionCombineProcessor.TRAN_ID_COMPARATOR.compare(txn1, txn2);

        // Assert — comparator returns 0 for equal IDs
        assertThat(comparison).isEqualTo(0);
    }

    // -------------------------------------------------------------------
    // BigDecimal precision tests
    // -------------------------------------------------------------------

    /**
     * Verifies that {@code process()} preserves {@link BigDecimal}
     * precision for the {@code tranAmt} field — critical per
     * AAP §0.8.2 which mandates zero floating-point substitution.
     *
     * <p>The COBOL field {@code TRAN-AMT PIC S9(09)V99} uses packed
     * decimal (COMP-3) with exact 2-decimal-place precision. The Java
     * pass-through must not introduce any precision loss, rounding, or
     * scale change.
     *
     * <p><strong>CRITICAL:</strong> This test uses
     * {@code BigDecimal.compareTo()} for financial assertions — never
     * {@code equals()}, which is scale-sensitive (AAP §0.8.2).
     */
    @Test
    void process_shouldPreserveBigDecimalPrecision() throws Exception {
        // Arrange — transaction with precise BigDecimal amount
        Transaction transaction = createTransaction("0000000000000007");
        transaction.setTranAmt(new BigDecimal("12345.67"));

        // Act
        Transaction result = processor.process(transaction);

        // Assert — BigDecimal precision preserved through pass-through
        // CRITICAL: Use compareTo() NOT equals() for BigDecimal (AAP §0.8.2)
        assertThat(result.getTranAmt()).isNotNull();
        assertThat(result.getTranAmt().compareTo(new BigDecimal("12345.67"))).isEqualTo(0);
    }

    // -------------------------------------------------------------------
    // Helper methods
    // -------------------------------------------------------------------

    /**
     * Creates a {@link Transaction} test fixture with the given
     * {@code tranId} and sensible default values for all fields.
     *
     * <p>Uses {@code BigDecimal} for the monetary amount (never
     * float/double) and {@code LocalDateTime} for timestamp fields,
     * consistent with the entity's COBOL-to-Java field mapping from
     * CVTRA05Y.cpy.
     *
     * @param tranId the 16-character transaction identifier
     * @return a fully populated Transaction instance
     */
    private Transaction createTransaction(String tranId) {
        Transaction transaction = new Transaction();
        transaction.setTranId(tranId);
        transaction.setTranTypeCd("SA");
        transaction.setTranCatCd((short) 5001);
        transaction.setTranSource("ONLINE");
        transaction.setTranDesc("Test transaction for combine processor");
        transaction.setTranAmt(new BigDecimal("100.00"));
        transaction.setTranMerchantId("000000001");
        transaction.setTranMerchantName("Test Merchant");
        transaction.setTranMerchantCity("Seattle");
        transaction.setTranMerchantZip("98101");
        transaction.setTranCardNum("4111111111111111");
        transaction.setTranOrigTs(LocalDateTime.of(2024, 3, 15, 10, 0, 0));
        transaction.setTranProcTs(LocalDateTime.of(2024, 3, 15, 14, 30, 0));
        return transaction;
    }
}

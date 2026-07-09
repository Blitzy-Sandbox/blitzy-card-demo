package com.carddemo.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import com.carddemo.entity.Transaction;

/**
 * Spring Data JPA slice tests for {@link TransactionRepository}, executed against a
 * <strong>real PostgreSQL&nbsp;16</strong> database provisioned by Testcontainers through
 * {@link AbstractRepositoryTest}. There is deliberately no mock and no embedded H2 substitute:
 * only a real PostgreSQL engine reproduces the {@code NUMERIC(11,2)} sign/scale semantics that
 * the migration depends on, and only the real engine lets Hibernate {@code ddl-auto=validate}
 * check the {@link Transaction} mapping against the Flyway-built {@code transaction} table.
 *
 * <p>The {@code transaction} entity is the Java translation of the COBOL copybook
 * {@code CVTRA05Y} ({@code TRAN-RECORD}, record length 350, source commit SHA {@code 27d6c6f} —
 * read-only reference, not copied into this repository). These tests anchor to AAP&nbsp;&sect;0.5.1
 * (entity/repository mapping), &sect;0.8.2 (decimal precision) and &sect;0.8.3 (VSAM alternate
 * index &rarr; Spring Data derived query).</p>
 *
 * <h2>Why exact-count assertions are safe here</h2>
 * <p>The Flyway {@code V3__seed_data.sql} migration loads <strong>zero</strong> rows into the base
 * {@code transaction} table (posted transactions are produced at runtime by the POSTTRAN batch),
 * so the table is empty at the start of every test. Each {@link org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest}
 * method (configured on the base class) runs inside a transaction that is rolled back on
 * completion, so every test self-provisions the exact rows it needs and its inserts never leak
 * into another test. The table also carries no foreign key on {@code tran_card_num}, so no parent
 * rows are required.</p>
 *
 * <h2>What is verified</h2>
 * <ul>
 *   <li><b>Round-trip + decimal &amp; timestamp fidelity</b> — a saved row is re-read from the
 *       database (after clearing the persistence context) and its signed {@code tran_amt}
 *       {@link BigDecimal} keeps value and scale&nbsp;2, while the 26-character {@code tran_proc_ts}
 *       / {@code tran_orig_ts} text is preserved verbatim (Gate&nbsp;5 contract fidelity).</li>
 *   <li><b>{@link TransactionRepository#findByTranProcTs(String)}</b> — reproduces the verified
 *       non-unique VSAM alternate index {@code TRANSACT.VSAM.AIX} on {@code TRAN-PROC-TS}: multiple
 *       rows can share a processing timestamp, so the finder returns a {@link List}.</li>
 *   <li><b>{@link TransactionRepository#findByTranCardNum(String, Pageable)}</b> — the paginated,
 *       card-scoped finder used by the transaction-list / statement / report flows, exercised at the
 *       {@code CT00} ({@code COTRN00C}) page size of ten rows per page.</li>
 * </ul>
 */
class TransactionRepositoryTest extends AbstractRepositoryTest {

    /** Card number reused across every row of the pagination test (16 characters). */
    private static final String CARD_NUM = "9999888877776666";

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private TestEntityManager entityManager;

    // ---------------------------------------------------------------------
    // Test-data helper
    // ---------------------------------------------------------------------

    /**
     * Builds a fully-populated {@link Transaction} so the individual test methods stay DRY and only
     * vary the values they actually assert on. Every scalar field is set to a valid, in-width value:
     * the two timestamp fields receive exactly-26-character text ({@code yyyy-mm-dd-hh.mm.ss.ffffff})
     * matching the {@code VARCHAR(26)} columns and the COBOL {@code PIC X(26)} fixed width.
     *
     * @param tranId  the 16-character natural primary key ({@code TRAN-ID})
     * @param cardNum the 16-character card number ({@code TRAN-CARD-NUM})
     * @param procTs  the 26-character processing timestamp ({@code TRAN-PROC-TS})
     * @param amt     the signed monetary amount ({@code TRAN-AMT}); persisted as {@code NUMERIC(11,2)}
     * @return a new, fully-populated transient {@link Transaction} instance
     */
    private Transaction newTxn(String tranId, String cardNum, String procTs, BigDecimal amt) {
        Transaction txn = new Transaction();
        txn.setTranId(tranId);
        txn.setTranTypeCd("01");
        txn.setTranCatCd(5);
        txn.setTranSource("POS");
        txn.setTranDesc("Test transaction " + tranId);
        txn.setTranAmt(amt);
        txn.setTranMerchantId(123456789L);
        txn.setTranMerchantName("Test Merchant");
        txn.setTranMerchantCity("Test City");
        txn.setTranMerchantZip("12345");
        txn.setTranCardNum(cardNum);
        txn.setTranOrigTs("2024-01-15-10.29.59.000000");
        txn.setTranProcTs(procTs);
        return txn;
    }

    // ---------------------------------------------------------------------
    // 1) save / findById round-trip — decimal + timestamp fidelity
    // ---------------------------------------------------------------------

    /**
     * A saved transaction must re-read identically from PostgreSQL&nbsp;16. The signed
     * {@code TRAN-AMT} ({@code PIC S9(09)V99}) must keep its value and scale&nbsp;2 with no
     * floating-point drift, and the 26-character {@code TRAN-PROC-TS} / {@code TRAN-ORIG-TS} text
     * must survive the {@code NUMERIC}/{@code VARCHAR} round-trip byte-for-byte. The persistence
     * context is cleared before the re-read so the assertions observe the database state, not a
     * first-level cache hit.
     */
    @Test
    @DisplayName("save + findById round-trips; preserves signed NUMERIC(11,2) scale and verbatim 26-char timestamps")
    void saveAndFindById_roundTrips_andPreservesDecimalAndTimestamp() {
        String tranId = "9000000000000001";
        String procTs = "2024-01-15-10.30.00.123456";
        Transaction toSave = newTxn(tranId, "1111222233334444", procTs, new BigDecimal("-12345.67"));

        transactionRepository.saveAndFlush(toSave);
        entityManager.clear();

        Transaction found = transactionRepository.findById(tranId).orElseThrow();

        // Decimal fidelity: negative value exercises the COBOL S (signed) picture; scale stays 2.
        assertThat(found.getTranAmt()).isEqualByComparingTo("-12345.67");
        assertThat(found.getTranAmt().scale()).isEqualTo(2);

        // Verbatim 26-character timestamp text (contract fidelity — Gate 5).
        assertThat(found.getTranProcTs()).isEqualTo(procTs);
        assertThat(found.getTranProcTs()).hasSize(26);
        assertThat(found.getTranOrigTs()).isEqualTo("2024-01-15-10.29.59.000000");
        assertThat(found.getTranOrigTs()).hasSize(26);

        // Remaining scalar fields survive the round-trip unchanged.
        assertThat(found.getTranId()).isEqualTo(tranId);
        assertThat(found.getTranTypeCd()).isEqualTo("01");
        assertThat(found.getTranCatCd()).isEqualTo(5);
        assertThat(found.getTranSource()).isEqualTo("POS");
        assertThat(found.getTranDesc()).isEqualTo("Test transaction " + tranId);
        assertThat(found.getTranMerchantId()).isEqualTo(123456789L);
        assertThat(found.getTranMerchantName()).isEqualTo("Test Merchant");
        assertThat(found.getTranMerchantCity()).isEqualTo("Test City");
        assertThat(found.getTranMerchantZip()).isEqualTo("12345");
        assertThat(found.getTranCardNum()).isEqualTo("1111222233334444");
    }

    // ---------------------------------------------------------------------
    // 2) findByTranProcTs — non-unique alternate index on TRAN-PROC-TS
    // ---------------------------------------------------------------------

    /**
     * {@link TransactionRepository#findByTranProcTs(String)} reproduces the verified non-unique VSAM
     * alternate index on {@code TRAN-PROC-TS}: two transactions sharing a processing timestamp must
     * both be returned (order unspecified), a transaction with a different timestamp must be
     * excluded, and a timestamp matching nothing must yield an empty {@link List}.
     */
    @Test
    @DisplayName("findByTranProcTs returns all rows sharing a processing timestamp (non-unique AIX)")
    void findByTranProcTs_returnsAllMatchingRows_nonUnique() {
        String sharedProcTs = "2024-02-01-09.00.00.000000";
        String otherProcTs = "2024-03-01-09.00.00.000000";

        Transaction match1 = newTxn("9000000000000001", CARD_NUM, sharedProcTs, new BigDecimal("10.00"));
        Transaction match2 = newTxn("9000000000000002", CARD_NUM, sharedProcTs, new BigDecimal("20.00"));
        Transaction nonMatch = newTxn("9000000000000003", CARD_NUM, otherProcTs, new BigDecimal("30.00"));

        transactionRepository.saveAllAndFlush(List.of(match1, match2, nonMatch));
        entityManager.clear();

        List<Transaction> found = transactionRepository.findByTranProcTs(sharedProcTs);

        assertThat(found).hasSize(2);
        assertThat(found).allMatch(t -> t.getTranProcTs().equals(sharedProcTs));
        assertThat(found).extracting(Transaction::getTranId)
                .containsExactlyInAnyOrder("9000000000000001", "9000000000000002");

        // A processing timestamp that matches nothing returns an empty list (never null).
        assertThat(transactionRepository.findByTranProcTs("1999-01-01-00.00.00.000000")).isEmpty();
    }

    // ---------------------------------------------------------------------
    // 3) findByTranCardNum — paginated card-scoped finder (CT00 = 10 rows/page)
    // ---------------------------------------------------------------------

    /**
     * {@link TransactionRepository#findByTranCardNum(String, Pageable)} must page a single card's
     * transactions ten rows at a time — the {@code CT00} ({@code COTRN00C}) list page size. With
     * twelve rows persisted for one card, page&nbsp;0 holds ten rows and reports two total pages and
     * twelve total elements; page&nbsp;1 holds the remaining two rows and is flagged as the last
     * page. Every returned row must belong to the queried card.
     */
    @Test
    @DisplayName("findByTranCardNum pages 10 rows/page (CT00): 12 rows -> page0=10, page1=2 (last)")
    void findByTranCardNum_pagesTenRowsPerPage() {
        List<Transaction> batch = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            String tranId = String.format("90000000000000%02d", i);
            batch.add(newTxn(tranId, CARD_NUM, "2024-04-01-09.00.00.000000", new BigDecimal("5.00")));
        }
        transactionRepository.saveAllAndFlush(batch);
        entityManager.clear();

        Pageable firstPageRequest = PageRequest.of(0, 10, Sort.by("tranId"));
        Page<Transaction> firstPage = transactionRepository.findByTranCardNum(CARD_NUM, firstPageRequest);

        assertThat(firstPage.getContent()).hasSize(10);
        assertThat(firstPage.getTotalElements()).isEqualTo(12L);
        assertThat(firstPage.getTotalPages()).isEqualTo(2);
        assertThat(firstPage.isLast()).isFalse();
        assertThat(firstPage.getContent()).allMatch(t -> t.getTranCardNum().equals(CARD_NUM));

        Pageable secondPageRequest = PageRequest.of(1, 10, Sort.by("tranId"));
        Page<Transaction> secondPage = transactionRepository.findByTranCardNum(CARD_NUM, secondPageRequest);

        assertThat(secondPage.getContent()).hasSize(2);
        assertThat(secondPage.isLast()).isTrue();
        assertThat(secondPage.getContent()).allMatch(t -> t.getTranCardNum().equals(CARD_NUM));
    }
}

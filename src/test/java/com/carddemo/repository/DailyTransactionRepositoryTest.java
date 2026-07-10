package com.carddemo.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import com.carddemo.entity.DailyTransaction;

/**
 * Spring Data JPA slice test for {@link DailyTransactionRepository}, executed
 * against a <strong>real PostgreSQL&nbsp;16</strong> database provisioned by
 * Testcontainers through {@link AbstractRepositoryTest} — never H2 and never a
 * mock. Running on the production engine is what makes the decimal-fidelity
 * assertions below meaningful: only PostgreSQL's genuine {@code NUMERIC(11,2)}
 * column reproduces the signed packed-decimal ({@code COMP-3}) semantics that
 * the CardDemo migration must preserve for the {@code DALYTRAN-AMT} money field.
 *
 * <h2>What is verified</h2>
 * <ul>
 *   <li><strong>{@code save}/{@code findById} round-trip</strong> — a fully
 *       populated staging row survives a flush + detach + reload with every
 *       scalar field, including the fixed-width 26-character timestamps,
 *       byte-identical.</li>
 *   <li><strong>Decimal fidelity of {@code DALYTRAN-AMT}</strong>
 *       ({@code NUMERIC(11,2)}, signed) — a <em>negative</em> amount exercises
 *       the COBOL {@code PIC S9(09)V99} sign, and a large positive amount
 *       exercises the magnitude ceiling; both round-trip exactly at scale&nbsp;2
 *       with no {@code float}/{@code double} drift.</li>
 *   <li><strong>{@code findAll(Pageable)} sorted paging</strong> — the exact
 *       access pattern the Spring Batch {@code RepositoryItemReader} uses when
 *       draining the {@code daily_transaction} staging table for the POSTTRAN
 *       posting pipeline (legacy {@code CBTRN02C}); the deterministic ascending
 *       order and page-size invariants the reader relies on are asserted.</li>
 * </ul>
 *
 * <h2>Interaction with the committed Flyway seed</h2>
 * <p>The {@code V3__seed_data.sql} migration commits <strong>300</strong>
 * {@code daily_transaction} rows before any test runs, and those rows are
 * visible to every test method. To stay robust against that shared, committed
 * state, the assertions here deliberately avoid brittle <em>exact</em>
 * global-count checks; instead they assert on this test's <em>own</em>
 * application-assigned ids ({@code "90000000000000NN"}, which never collide
 * with the zero-padded seed ids) and on paging <em>invariants</em> (page size
 * &le;&nbsp;10, non-empty, {@code totalElements >= 300}, ascending order). Each
 * {@code @DataJpaTest} method runs inside a transaction that is rolled back on
 * completion, so this test's inserts never leak into sibling tests.</p>
 *
 * <p>The base class supplies all Spring/Testcontainers wiring, so this subclass
 * carries <em>no</em> class-level annotations — it merely autowires the
 * repository under test plus a {@link TestEntityManager} for the
 * flush/{@code clear} lifecycle and adds {@code @Test} methods.</p>
 */
class DailyTransactionRepositoryTest extends AbstractRepositoryTest {

    /** Original-timestamp fixture, exactly 26 characters ({@code DALYTRAN-ORIG-TS PIC X(26)}). */
    private static final String ORIG_TS = "2024-03-10-11.59.59.000000";

    /** Processing-timestamp fixture, exactly 26 characters ({@code DALYTRAN-PROC-TS PIC X(26)}). */
    private static final String PROC_TS = "2024-03-10-12.00.00.000000";

    /** Repository under test — exposes only the inherited {@link org.springframework.data.jpa.repository.JpaRepository} methods (Gate&nbsp;7: no custom finders). */
    @Autowired
    private DailyTransactionRepository dailyTransactionRepository;

    /** Used only to {@code flush} and {@code clear} the persistence context so each re-read hits the real database. */
    @Autowired
    private TestEntityManager entityManager;

    /**
     * Persists a fully populated staging row carrying a <em>negative</em>
     * amount, reloads it from PostgreSQL, and asserts that the signed
     * {@code NUMERIC(11,2)} value and every other scalar — including the two
     * fixed-width 26-character timestamps — survive the round-trip unchanged.
     * The negative value directly exercises the {@code PIC S9(09)V99} sign that
     * the migration must preserve.
     */
    @Test
    void saveAndFindById_roundTrips_negativeAmountPreserved() {
        DailyTransaction d = newDaily("9000000000000001", new BigDecimal("-987.65"));

        dailyTransactionRepository.saveAndFlush(d);
        // Detach everything so findById is forced to read back from the database
        // rather than returning the still-managed instance from the first-level
        // cache — this is what actually exercises the NUMERIC(11,2) round-trip.
        entityManager.clear();

        DailyTransaction found =
                dailyTransactionRepository.findById("9000000000000001").orElseThrow();

        // Decimal fidelity: the signed amount and its scale are preserved exactly.
        assertThat(found.getDalytranAmt()).isEqualByComparingTo("-987.65");
        assertThat(found.getDalytranAmt().scale()).isEqualTo(2);

        // Every other scalar round-trips byte-identically.
        assertThat(found.getDalytranId()).isEqualTo("9000000000000001");
        assertThat(found.getDalytranTypeCd()).isEqualTo("01");
        assertThat(found.getDalytranCatCd()).isEqualTo(3);
        assertThat(found.getDalytranSource()).isEqualTo("POS TERM");
        assertThat(found.getDalytranDesc()).isEqualTo("Daily transaction round-trip fixture");
        assertThat(found.getDalytranMerchantId()).isEqualTo(800000000L);
        assertThat(found.getDalytranMerchantName()).isEqualTo("Globomantics Retail");
        assertThat(found.getDalytranMerchantCity()).isEqualTo("North Testerton");
        assertThat(found.getDalytranMerchantZip()).isEqualTo("12345-6789");
        assertThat(found.getDalytranCardNum()).isEqualTo("9999000011112222");

        // Fixed-width timestamps: identical content AND preserved 26-char length.
        assertThat(found.getDalytranOrigTs()).isEqualTo(ORIG_TS).hasSize(26);
        assertThat(found.getDalytranProcTs()).isEqualTo(PROC_TS).hasSize(26);
    }

    /**
     * Persists the maximum-magnitude amount used by this suite for the
     * {@code NUMERIC(11,2)} column and confirms it reloads exactly at scale&nbsp;2,
     * proving there is no floating-point widening or truncation on a large
     * positive value.
     */
    @Test
    void positiveAmount_roundTripsExactly() {
        dailyTransactionRepository.saveAndFlush(
                newDaily("9000000000000002", new BigDecimal("99999999.99")));
        entityManager.clear();

        DailyTransaction found =
                dailyTransactionRepository.findById("9000000000000002").orElseThrow();

        assertThat(found.getDalytranAmt()).isEqualByComparingTo("99999999.99");
        assertThat(found.getDalytranAmt().scale()).isEqualTo(2);
    }

    /**
     * Exercises the batch reader access pattern: pages over the staging table in
     * deterministic ascending {@code dalytranId} order via the inherited
     * {@code findAll(Pageable)} that a {@code RepositoryItemReader} would use.
     * Because the committed Flyway seed already contains 300 rows, the checks are
     * expressed as paging <em>invariants</em> (page size &le;&nbsp;10, non-empty,
     * {@code totalElements >= 300}, ascending order) plus retrieval of this
     * test's own inserted ids, rather than a fragile exact global count.
     */
    @Test
    void findAll_isPageableAndSorted() {
        List<DailyTransaction> own = List.of(
                newDaily("9000000000000003", new BigDecimal("10.00")),
                newDaily("9000000000000004", new BigDecimal("-20.50")),
                newDaily("9000000000000005", new BigDecimal("30.25")));
        dailyTransactionRepository.saveAllAndFlush(own);
        entityManager.clear();

        Page<DailyTransaction> page = dailyTransactionRepository.findAll(
                PageRequest.of(0, 10, Sort.by("dalytranId")));

        // Paging invariants: a full, non-empty page bounded by the requested size.
        assertThat(page.getContent()).isNotEmpty().hasSizeLessThanOrEqualTo(10);
        // 300 seeded rows are committed and visible, plus this test's own inserts.
        assertThat(page.getTotalElements()).isGreaterThanOrEqualTo(300L);

        // Deterministic ascending order is the contract the Spring Batch
        // RepositoryItemReader depends on for a stable, restartable read.
        List<String> ids = page.getContent().stream()
                .map(DailyTransaction::getDalytranId)
                .toList();
        assertThat(ids).isSorted();

        // This test's own rows are individually retrievable within the transaction.
        assertThat(dailyTransactionRepository.findById("9000000000000003")).isPresent();
        assertThat(dailyTransactionRepository.findById("9000000000000004")).isPresent();
        assertThat(dailyTransactionRepository.findById("9000000000000005")).isPresent();
    }

    /**
     * Builds a fully populated {@link DailyTransaction} fixture for the given
     * application-assigned id and amount, keeping the tests DRY. Every mapped
     * column of copybook {@code CVTRA06Y.cpy} is set to a deterministic value;
     * both timestamp fields use exactly 26-character literals to honour the
     * {@code PIC X(26)} fixed-width contract. The trailing {@code FILLER X(20)}
     * of the copybook is intentionally unmapped (pure record padding) and is
     * therefore not populated.
     *
     * @param id  the natural primary key ({@code DALYTRAN-ID}, 16 characters)
     * @param amt the signed monetary amount ({@code DALYTRAN-AMT}, scale 2)
     * @return a transient, fully populated {@link DailyTransaction}
     */
    private DailyTransaction newDaily(String id, BigDecimal amt) {
        DailyTransaction d = new DailyTransaction();
        d.setDalytranId(id);
        d.setDalytranTypeCd("01");
        d.setDalytranCatCd(3);
        d.setDalytranSource("POS TERM");
        d.setDalytranDesc("Daily transaction round-trip fixture");
        d.setDalytranAmt(amt);
        d.setDalytranMerchantId(800000000L);
        d.setDalytranMerchantName("Globomantics Retail");
        d.setDalytranMerchantCity("North Testerton");
        d.setDalytranMerchantZip("12345-6789");
        d.setDalytranCardNum("9999000011112222");
        d.setDalytranOrigTs(ORIG_TS);
        d.setDalytranProcTs(PROC_TS);
        return d;
    }
}

package com.carddemo.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.entity.TransactionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

/**
 * Spring Data JPA slice test for {@link TransactionTypeRepository}, executed
 * against a <strong>real PostgreSQL&nbsp;16 database</strong> provisioned by
 * Testcontainers through {@link AbstractRepositoryTest} (never H2, never a
 * mock).
 *
 * <p><strong>What is under test.</strong> {@code TransactionTypeRepository}
 * extends {@code JpaRepository<TransactionType, String>} and declares
 * <em>no</em> custom or derived query methods, so the reference table
 * {@code transaction_type} is exercised entirely through the inherited CRUD and
 * lookup operations. Coverage therefore focuses on {@code save}/{@code findById}
 * round-trips, committed-seed reads, and {@code existsById}/{@code count}
 * behaviour over the single-column {@code String} natural key
 * (COBOL {@code TRAN-TYPE PIC X(02)}).</p>
 *
 * <p><strong>Legacy provenance.</strong> The entity and repository are the
 * migration target for the COBOL copybook {@code app/cpy/CVTRA03Y.cpy}
 * ({@code TRAN-TYPE-RECORD}, record length 60 bytes) at source commit SHA
 * {@code 27d6c6f} (CardDemo v1.0-15-g27d6c6f-68). {@code transaction_type} is a
 * static reference/lookup table: it carries no monetary fields, no
 * {@code @Version} optimistic-lock column, and no foreign keys, so these tests
 * assert plain persistence round-trips rather than decimal-fidelity or
 * concurrency semantics.</p>
 *
 * <p><strong>Test data strategy.</strong> The real Flyway migrations run at
 * context startup ({@code V1__schema.sql} &rarr; {@code V2__indexes.sql} &rarr;
 * {@code V3__seed_data.sql}); the committed V3 seed contributes the seven
 * canonical transaction types (keys {@code "01".."07"}, with {@code "01"}
 * describing {@code "Purchase"}). To keep the mutating round-trips deterministic
 * and independent of the shared seed, this test provisions its own row under the
 * reserved code {@code "99"}, which the seed never uses. Because
 * {@link org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest @DataJpaTest}
 * wraps every method in a transaction that is rolled back at completion, the
 * {@code "99"} row (and any {@code count()} delta) is discarded automatically
 * between methods; the {@code count()} baseline is captured inside the method
 * rather than assuming a fixed global total.</p>
 *
 * <p>Both mapped columns are {@code VARCHAR} ({@code tran_type VARCHAR(2)},
 * {@code tran_type_desc VARCHAR(50)}), not {@code CHAR}, so values are stored and
 * returned without trailing space padding and {@code isEqualTo} comparisons are
 * exact.</p>
 *
 * @see TransactionTypeRepository
 * @see TransactionType
 * @see AbstractRepositoryTest
 */
class TransactionTypeRepositoryTest extends AbstractRepositoryTest {

    /**
     * The repository under test. Autowired from the {@code @DataJpaTest} slice
     * that {@link AbstractRepositoryTest} configures; no custom query methods
     * exist, so only inherited {@code JpaRepository} operations are exercised.
     */
    @Autowired
    private TransactionTypeRepository transactionTypeRepository;

    /**
     * Test-scoped persistence helper used to {@code flush} pending changes to
     * the database and {@code clear} the persistence context, forcing subsequent
     * reads to reload managed entities from PostgreSQL rather than returning the
     * first-level cache instance.
     */
    @Autowired
    private TestEntityManager entityManager;

    /**
     * Persisting a {@link TransactionType} and then reading it back by its
     * primary key must return an equal record. After the save is flushed the
     * persistence context is cleared, so {@code findById} materialises a fresh
     * instance from the database — proving the mapping and the {@code String}
     * key round-trip through real PostgreSQL rather than merely returning a
     * cached reference.
     */
    @Test
    @DisplayName("save then findById round-trips a transaction type on the String key")
    void saveAndFindById_roundTrips() {
        transactionTypeRepository.save(newType("99", "Test Type"));
        entityManager.flush();
        entityManager.clear();

        TransactionType found = transactionTypeRepository.findById("99").orElseThrow();

        assertThat(found.getTranType()).isEqualTo("99");
        assertThat(found.getTranTypeDesc()).isEqualTo("Test Type");
    }

    /**
     * The committed V3 Flyway seed must be visible to the repository: looking up
     * the first canonical transaction type by its key returns the documented
     * description. This is a secondary confirmation that the real seed migration
     * ran and is queryable through the repository; it exercises no mutation.
     */
    @Test
    @DisplayName("findById returns the committed V3 seed row ('01' maps to 'Purchase')")
    void findById_returnsSeededRow() {
        TransactionType found = transactionTypeRepository.findById("01").orElseThrow();

        assertThat(found.getTranTypeDesc()).isEqualTo("Purchase");
    }

    /**
     * {@code existsById} and {@code count} must reflect a newly saved row. The
     * reserved {@code "99"} key is absent from the seed, so it does not exist
     * before the save; after the save is flushed it exists, and the total row
     * count increases by exactly one relative to a baseline captured at the start
     * of the method (robust to whatever the shared seed total happens to be).
     */
    @Test
    @DisplayName("existsById and count reflect a newly saved transaction type")
    void existsAndCount_reflectSaves() {
        long baselineCount = transactionTypeRepository.count();
        assertThat(transactionTypeRepository.existsById("99")).isFalse();

        transactionTypeRepository.save(newType("99", "Test Type"));
        entityManager.flush();

        assertThat(transactionTypeRepository.existsById("99")).isTrue();
        assertThat(transactionTypeRepository.count()).isEqualTo(baselineCount + 1);
    }

    /**
     * Builds a fully populated {@link TransactionType} from its two mapped
     * fields, mirroring how the COBOL {@code TRAN-TYPE-RECORD} is assembled
     * (two-character key plus description; the trailing {@code FILLER} carries no
     * business data and is not mapped).
     *
     * @param code the two-character transaction-type code (natural key,
     *             {@code TRAN-TYPE PIC X(02)})
     * @param desc the transaction-type description
     *             ({@code TRAN-TYPE-DESC PIC X(50)})
     * @return a new, unsaved {@code TransactionType} carrying the supplied values
     */
    private TransactionType newType(String code, String desc) {
        TransactionType type = new TransactionType();
        type.setTranType(code);
        type.setTranTypeDesc(desc);
        return type;
    }
}

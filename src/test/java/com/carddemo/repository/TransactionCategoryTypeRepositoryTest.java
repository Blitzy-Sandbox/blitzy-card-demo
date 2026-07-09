package com.carddemo.repository;

import com.carddemo.entity.TransactionCategoryType;
import com.carddemo.entity.TransactionCategoryTypeId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring Data JPA slice test for {@link TransactionCategoryTypeRepository}, executed
 * against a <strong>real PostgreSQL&nbsp;16</strong> instance provisioned by
 * Testcontainers through {@link AbstractRepositoryTest} (never H2, never a mock).
 *
 * <p><strong>Legacy provenance.</strong> The entity under test,
 * {@link TransactionCategoryType}, is the migration of the COBOL copybook
 * {@code app/cpy/CVTRA04Y.cpy} ({@code TRAN-CAT-RECORD}, record length 60 bytes) at
 * source commit SHA {@code 27d6c6f} (CardDemo v1.0-15-g27d6c6f-68). Its two-part
 * natural key {@code TRAN-CAT-KEY} ({@code TRAN-TYPE-CD PIC X(02)} +
 * {@code TRAN-CAT-CD PIC 9(04)}) is modelled as the {@code @EmbeddedId}
 * {@link TransactionCategoryTypeId}. This is a static reference/lookup table with no
 * monetary fields, no {@code @Version} optimistic-lock column and no foreign keys, so
 * these tests focus purely on keyed access semantics.</p>
 *
 * <p><strong>What is verified.</strong></p>
 * <ol>
 *   <li>{@link #findById_byCompositeKey_returnsExactRow()} &ndash; full composite-key
 *       resolution via the inherited {@link org.springframework.data.repository.CrudRepository#findById(Object)
 *       findById(TransactionCategoryTypeId)}, exercising the
 *       {@link TransactionCategoryTypeId} {@code equals}/{@code hashCode} contract that
 *       Hibernate relies on for identity.</li>
 *   <li>{@link #findByIdTranTypeCd_traversesEmbeddedId_returnsAllRowsForType()} &ndash;
 *       the single additive derived query
 *       {@link TransactionCategoryTypeRepository#findByIdTranTypeCd(String)}, which
 *       Spring Data resolves by traversing the embedded-id property path
 *       {@code id.tranTypeCd} into {@code WHERE tran_type_cd = ?} &ndash; proving a
 *       single transaction type maps to every one of its category rows.</li>
 * </ol>
 *
 * <p><strong>Test isolation and data.</strong> {@link AbstractRepositoryTest} runs the
 * real Flyway migrations (including the committed {@code V3__seed_data.sql} seed) once
 * at context startup and wraps each {@code @Test} method in a transaction that is
 * rolled back on completion. Each test therefore provisions its own rows and cannot
 * leak them into another test. To keep the derived-query cardinality deterministic,
 * the mutating tests use transaction-type codes {@code "99"}/{@code "98"} that are
 * <em>absent</em> from the seed (which populates types {@code "01".."07"}); the
 * committed seed &ndash; where type {@code "01"} defines exactly five categories &ndash;
 * is asserted only as a secondary, read-only check.</p>
 *
 * @see TransactionCategoryTypeRepository
 * @see TransactionCategoryType
 * @see TransactionCategoryTypeId
 * @see AbstractRepositoryTest
 */
class TransactionCategoryTypeRepositoryTest extends AbstractRepositoryTest {

    /** Repository under test, wired from the {@code @DataJpaTest} JPA slice context. */
    @Autowired
    private TransactionCategoryTypeRepository transactionCategoryTypeRepository;

    /**
     * JPA test helper used to seed rows and to force a database round-trip: after
     * {@code persist}/{@code flush} a {@link TestEntityManager#clear() clear()} evicts
     * the first-level cache so that subsequent repository reads execute real SQL
     * against PostgreSQL rather than returning managed instances.
     */
    @Autowired
    private TestEntityManager entityManager;

    /**
     * Builds a detached {@link TransactionCategoryType} from its composite-key parts and
     * description, using the entity's no-arg constructor and the
     * {@link TransactionCategoryTypeId} all-args constructor.
     *
     * @param typeCd the transaction type code ({@code TRAN-TYPE-CD}, two characters)
     * @param catCd  the transaction category code ({@code TRAN-CAT-CD})
     * @param desc   the category-type description ({@code TRAN-CAT-TYPE-DESC})
     * @return a new, unmanaged {@link TransactionCategoryType} instance ready to persist
     */
    private TransactionCategoryType newCatType(String typeCd, Integer catCd, String desc) {
        TransactionCategoryType catType = new TransactionCategoryType();
        catType.setId(new TransactionCategoryTypeId(typeCd, catCd));
        catType.setTranCatTypeDesc(desc);
        return catType;
    }

    /**
     * Verifies that {@code findById} resolves a row by its full composite key
     * {@code (tranTypeCd, tranCatCd)} and materialises every field, including the
     * embedded-id components. The persistence context is cleared before the lookup so
     * the assertion reflects a genuine database read.
     */
    @Test
    @DisplayName("findById resolves a row by its full composite (tranTypeCd, tranCatCd) key")
    void findById_byCompositeKey_returnsExactRow() {
        entityManager.persist(newCatType("99", 1, "Test Category"));
        entityManager.flush();
        entityManager.clear();

        TransactionCategoryType found = transactionCategoryTypeRepository
                .findById(new TransactionCategoryTypeId("99", 1))
                .orElseThrow();

        assertThat(found.getId().getTranTypeCd()).isEqualTo("99");
        assertThat(found.getId().getTranCatCd()).isEqualTo(1);
        assertThat(found.getTranCatTypeDesc()).isEqualTo("Test Category");
    }

    /**
     * Verifies the embedded-id traversal query
     * {@link TransactionCategoryTypeRepository#findByIdTranTypeCd(String)}: three rows are
     * persisted under transaction type {@code "99"} (categories 1, 2 and 3) alongside a
     * distractor row under type {@code "98"}, and the query must return exactly the three
     * {@code "99"} rows &ndash; proving the derived {@code WHERE tran_type_cd = ?}
     * traversal of the {@code id.tranTypeCd} path.
     *
     * <p>As a secondary, read-only check it asserts the committed {@code V3} seed, in
     * which transaction type {@code "01"} defines five categories, to confirm the query
     * also works over pre-existing reference data.</p>
     */
    @Test
    @DisplayName("findByIdTranTypeCd traverses id.tranTypeCd and returns every category of a type")
    void findByIdTranTypeCd_traversesEmbeddedId_returnsAllRowsForType() {
        transactionCategoryTypeRepository.saveAllAndFlush(List.of(
                newCatType("99", 1, "Test Category One"),
                newCatType("99", 2, "Test Category Two"),
                newCatType("99", 3, "Test Category Three"),
                newCatType("98", 1, "Distractor Category")));
        entityManager.clear();

        List<TransactionCategoryType> found = transactionCategoryTypeRepository.findByIdTranTypeCd("99");

        assertThat(found).hasSize(3);
        assertThat(found).allMatch(t -> "99".equals(t.getId().getTranTypeCd()));

        // Secondary (committed V3 seed): transaction type "01" defines exactly 5 categories.
        assertThat(transactionCategoryTypeRepository.findByIdTranTypeCd("01")).hasSize(5);
    }
}

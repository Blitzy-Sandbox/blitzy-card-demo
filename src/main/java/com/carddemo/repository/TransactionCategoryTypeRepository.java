package com.carddemo.repository;

import com.carddemo.entity.TransactionCategoryType;
import com.carddemo.entity.TransactionCategoryTypeId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for the {@link TransactionCategoryType} reference/lookup
 * entity &ndash; the transaction-category-type table that supplies a human-readable
 * description for each valid <em>(transaction&nbsp;type,&nbsp;transaction&nbsp;category)</em>
 * combination and is consulted to validate and label transactions during posting and
 * reporting.
 *
 * <p><strong>Legacy provenance.</strong> This interface is the idiomatic Spring Data
 * replacement for keyed access to the transaction-category-type reference data
 * originally described by the COBOL copybook {@code app/cpy/CVTRA04Y.cpy}
 * ({@code TRAN-CAT-RECORD}, record length {@code 60} bytes) at source commit SHA
 * {@code 27d6c6f} (CardDemo v1.0-15-g27d6c6f-68). The table is a small, static
 * reference set seeded from the {@code trancatg} ASCII fixture; in the mainframe
 * system it was reached by keyed access on the composite {@code TRAN-CAT-KEY}, and
 * that access pattern is preserved here as primary-key lookups and full-table loads
 * through the inherited Spring Data operations. The posting program {@code CBTRN02C}
 * and the transaction-detail report {@code CBTRN03C} both read this table to resolve
 * the descriptive category type for a transaction.</p>
 *
 * <h2>Composite primary key</h2>
 * <p>{@link TransactionCategoryType} is keyed by the two-part natural key
 * {@code TRAN-CAT-KEY} (the leading {@code 6} bytes of the {@code 60}-byte record),
 * modelled as the {@link org.springframework.data.jpa.repository.JpaRepository
 * JpaRepository} identifier {@link TransactionCategoryTypeId} (an
 * {@code @EmbeddedId}). The key comprises:</p>
 * <ul>
 *   <li>{@code TRAN-TYPE-CD PIC X(02)} &rarr; {@link TransactionCategoryTypeId#getTranTypeCd()}
 *       (a two-character {@link String}, column {@code tran_type_cd}); and</li>
 *   <li>{@code TRAN-CAT-CD PIC 9(04)} &rarr; {@link TransactionCategoryTypeId#getTranCatCd()}
 *       (an {@link Integer} of up to four digits, column {@code tran_cat_cd}).</li>
 * </ul>
 * <p>The repository is therefore parameterized as
 * {@code JpaRepository<TransactionCategoryType, TransactionCategoryTypeId>}. This is
 * deliberately a <em>composite</em>-key lookup and must not be confused with the
 * single-key {@code TransactionType} (copybook {@code CVTRA03Y}, keyed on
 * {@code TRAN-TYPE} alone), which is served by a separate repository.</p>
 *
 * <h2>Query surface</h2>
 * <p>Full-key access uses the inherited
 * {@link JpaRepository#findById(Object) findById(TransactionCategoryTypeId)}, and
 * whole-table retrieval for validation and seeding uses
 * {@link JpaRepository#findAll()}. The single additive derived query,
 * {@link #findByIdTranTypeCd(String)}, enumerates every category defined for a given
 * transaction type &ndash; the access shape required where posting/reporting logic
 * iterates the categories within a type. No other finders are declared, avoiding
 * unrequested scope expansion.</p>
 *
 * <h2>Schema ownership</h2>
 * <p>The repository operates over the Flyway-managed {@code transaction_category_type}
 * table (composite primary key {@code (tran_type_cd, tran_cat_cd)}) under a
 * {@code spring.jpa.hibernate.ddl-auto: validate} configuration; it performs no schema
 * definition of its own.</p>
 *
 * @see TransactionCategoryType
 * @see TransactionCategoryTypeId
 * @see JpaRepository
 */
@Repository
public interface TransactionCategoryTypeRepository
        extends JpaRepository<TransactionCategoryType, TransactionCategoryTypeId> {

    /**
     * Finds every transaction-category-type row defined for the supplied
     * transaction-type code &ndash; i.e. enumerates all categories belonging to a
     * single transaction type.
     *
     * <p>Spring Data derives this query from the method name by traversing the
     * {@code @EmbeddedId} property path {@code id.tranTypeCd}: the {@code Id} segment
     * selects the embedded identifier field {@code id} on
     * {@link TransactionCategoryType}, and {@code TranTypeCd} selects the
     * {@link TransactionCategoryTypeId#getTranTypeCd() tranTypeCd} sub-field, yielding
     * {@code WHERE tran_type_cd = ?}. Because the transaction-type code is only the
     * first component of the composite key (the full key also includes
     * {@code TRAN-CAT-CD}), a single transaction type may map to many category rows,
     * so the result is returned as a {@link List}. The supplied code is matched
     * exactly (no wildcard semantics).</p>
     *
     * @param tranTypeCd the two-character transaction-type code to match
     *                   (COBOL {@code TRAN-TYPE-CD}, column {@code tran_type_cd});
     *                   must not be {@code null}
     * @return all matching {@link TransactionCategoryType} rows for the transaction
     *         type, in no guaranteed order; never {@code null}, and empty when no
     *         category is defined for the given type
     */
    List<TransactionCategoryType> findByIdTranTypeCd(String tranTypeCd);
}

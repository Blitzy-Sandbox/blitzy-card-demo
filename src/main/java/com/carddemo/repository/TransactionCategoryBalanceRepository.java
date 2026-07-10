package com.carddemo.repository;

import com.carddemo.entity.TransactionCategoryBalance;
import com.carddemo.entity.TransactionCategoryBalanceId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for the {@link TransactionCategoryBalance} entity &ndash; the
 * per-account, per-transaction-category running-balance table ({@code TCATBAL}).
 *
 * <p>This interface is the idiomatic Java/Spring replacement for the legacy AWS CardDemo VSAM
 * <strong>KSDS</strong> transaction-category-balance dataset, whose fixed-width record layout is
 * defined by the COBOL copybook {@code app/cpy/CVTRA01Y.cpy} ({@code TRAN-CAT-BAL-RECORD}, record
 * length {@code 50} bytes, source commit SHA {@code 27d6c6f} / CardDemo v1.0-15-g27d6c6f-68). In
 * the mainframe system the dataset is reached by VSAM keyed access on the 17-byte composite key
 * {@code TRAN-CAT-KEY}:</p>
 *
 * <pre>
 *   05  TRAN-CAT-KEY.                        (composite key, 17 bytes)
 *       10 TRANCAT-ACCT-ID   PIC 9(11).      -&gt; id.trancatAcctId (BIGINT)
 *       10 TRANCAT-TYPE-CD   PIC X(02).      -&gt; id.trancatTypeCd (VARCHAR(2))
 *       10 TRANCAT-CD        PIC 9(04).      -&gt; id.trancatCd     (INTEGER)
 *   05  TRAN-CAT-BAL         PIC S9(09)V99.  -&gt; tranCatBal       (NUMERIC(11,2))
 * </pre>
 *
 * <h2>Legacy access-pattern &rarr; Spring Data mapping</h2>
 * <p>The single legacy consumer of the base cluster is the batch interest-calculation program
 * {@code CBACT04C} (JCL step {@code INTCALC}). It opens {@code TCATBAL-FILE} with
 * {@code ACCESS MODE IS SEQUENTIAL} and walks the records in key order, treating each change in
 * {@code TRANCAT-ACCT-ID} as an account boundary: for every account it gathers the category
 * balances, resolves the matching disclosure-group interest rate, and computes interest
 * (paragraphs {@code 1000-TCATBALF-GET-NEXT} &rarr; {@code 1200-GET-INTEREST-RATE} &rarr;
 * {@code 1300-COMPUTE-INTEREST}). The two access shapes that legacy behaviour requires each map to
 * a distinct repository operation:</p>
 * <ul>
 *   <li><strong>Full composite-key read</strong> &mdash; a keyed fetch of a single
 *       account/type/category row maps to the inherited
 *       {@link JpaRepository#findById(Object) findById(TransactionCategoryBalanceId)}; new or
 *       recomputed balances are persisted through the inherited
 *       {@link JpaRepository#save(Object) save(..)}. No custom method is declared for these, as
 *       that would duplicate the inherited primary-key operations.</li>
 *   <li><strong>Per-account grouping</strong> &mdash; {@code CBACT04C}'s "all category balances for
 *       one account" access maps to the derived query {@link #findByIdTrancatAcctId(Long)}, which
 *       returns every category-balance row sharing a given {@code TRANCAT-ACCT-ID}.</li>
 * </ul>
 *
 * <p>Sequential, account-ordered chunk reading for the batch tier is expressed by the batch layer
 * using a {@code RepositoryItemReader} over the inherited
 * {@link JpaRepository#findAll(org.springframework.data.domain.Sort) findAll(Sort)} /
 * {@code findAll(Pageable)} sorted by the embedded id; that requires no bespoke method here.</p>
 *
 * <h2>Embedded-id property traversal</h2>
 * <p>{@link TransactionCategoryBalance} declares its key as
 * {@code @EmbeddedId TransactionCategoryBalanceId id}. Spring Data therefore resolves the finder
 * name {@code findByIdTrancatAcctId} along the property path {@code id.trancatAcctId} (the
 * {@code trancatAcctId} field of the embeddable key), generating
 * {@code WHERE id.trancatAcctId = ?1} against the {@code trancat_acct_id} column. Because
 * {@code trancatAcctId} is typed {@link Long}, the finder parameter is {@link Long}.</p>
 *
 * <h2>Type contract</h2>
 * <ul>
 *   <li>Aggregate type: {@link TransactionCategoryBalance}.</li>
 *   <li>Identifier type: {@link TransactionCategoryBalanceId} (an {@code @Embeddable} composite key
 *       over {@code trancatAcctId}, {@code trancatTypeCd}, {@code trancatCd}), so the interface is
 *       parameterised as {@code JpaRepository<TransactionCategoryBalance,
 *       TransactionCategoryBalanceId>}.</li>
 * </ul>
 *
 * <h2>Schema / performance note</h2>
 * <p>The repository runs against the Flyway-managed {@code transaction_category_balance} table
 * (composite primary key {@code (trancat_acct_id, trancat_type_cd, trancat_cd)}) under a
 * {@code spring.jpa.hibernate.ddl-auto: validate} configuration and performs no DDL of its own. To
 * preserve the account-grouped access performance of the original VSAM key, the leading
 * {@code trancat_acct_id} column is expected to be covered by the composite primary key / an index
 * declared by the schema migrations ({@code db/migration/V2__indexes.sql}); that index is owned by
 * the database-migration artifacts and is intentionally not declared here.</p>
 *
 * @see TransactionCategoryBalance
 * @see TransactionCategoryBalanceId
 * @see JpaRepository
 */
@Repository
public interface TransactionCategoryBalanceRepository
        extends JpaRepository<TransactionCategoryBalance, TransactionCategoryBalanceId> {

    /**
     * Finds every transaction-category balance row belonging to the supplied account &ndash; i.e.
     * all {@code (type, category)} balances recorded under one {@code TRANCAT-ACCT-ID}.
     *
     * <p>This derived query reproduces the per-account grouping performed by the batch
     * interest-calculation program {@code CBACT04C} ({@code INTCALC}), which reads {@code TCATBAL}
     * sequentially and processes the set of category balances for each account as a unit before
     * computing interest. Spring Data derives the query from the method name by traversing the
     * embedded-id property path {@code id.trancatAcctId} of {@link TransactionCategoryBalance},
     * generating {@code WHERE id.trancatAcctId = ?1} against the {@code trancat_acct_id} column. The
     * match is exact (no wildcard semantics).</p>
     *
     * <p>An account may own several category-balance rows, so the result is a {@link List}; it is
     * empty (never {@code null}) when the account has no category-balance rows. For a single
     * account/type/category row, use the inherited
     * {@link JpaRepository#findById(Object) findById(TransactionCategoryBalanceId)} instead.</p>
     *
     * @param trancatAcctId the account identifier to search on (COBOL {@code TRANCAT-ACCT-ID},
     *                      {@code PIC 9(11)}); must not be {@code null}
     * @return all matching {@link TransactionCategoryBalance} rows for the account, in no guaranteed
     *         order; an empty list if none match, never {@code null}
     */
    List<TransactionCategoryBalance> findByIdTrancatAcctId(Long trancatAcctId);
}

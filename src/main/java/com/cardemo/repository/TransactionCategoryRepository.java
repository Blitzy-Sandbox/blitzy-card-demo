package com.cardemo.repository;

import com.cardemo.model.entity.TransactionCategory;
import com.cardemo.model.key.TransactionCategoryId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the {@link TransactionCategory} entity,
 * replacing all VSAM keyed access to the {@code TRANCATG.VSAM.KSDS} dataset.
 *
 * <p>This is a <strong>read-only reference data</strong> repository that maps
 * transaction type + category code combinations to human-readable descriptions
 * for display enrichment in transaction screens and batch reports.</p>
 *
 * <h3>Source VSAM Dataset</h3>
 * <pre>
 * Dataset:     AWS.M2.CARDDEMO.TRANCATG.VSAM.KSDS
 * JCL Source:  app/jcl/TRANCATG.jcl (commit 27d6c6f)
 * Copybook:    app/cpy/CVTRA04Y.cpy (commit 27d6c6f)
 * Keys:        6 bytes at offset 0 (TRAN-TYPE-CD[2] + TRAN-CAT-CD[4])
 * Record Size: 60 bytes (fixed)
 * Access:      KSDS, INDEXED, SHAREOPTIONS(2 3)
 * </pre>
 *
 * <h3>COBOL Access Patterns Mapped</h3>
 * <ul>
 *   <li><strong>Keyed READ by composite key</strong> — {@code findById(TransactionCategoryId)}
 *       replaces {@code READ TRANCATG KEY IS TRAN-CAT-KEY} for single category lookup</li>
 *   <li><strong>Sequential browse / full table scan</strong> — {@code findAll()} replaces
 *       {@code STARTBR / READNEXT} for loading the complete reference data set</li>
 *   <li><strong>Existence check</strong> — {@code existsById(TransactionCategoryId)} replaces
 *       {@code READ TRANCATG} followed by FILE STATUS check for presence validation</li>
 *   <li><strong>Record count</strong> — {@code count()} provides a total category count
 *       for diagnostics and reporting</li>
 * </ul>
 *
 * <h3>Key Usage Contexts</h3>
 * <ul>
 *   <li>CBTRN03C.cbl (transaction report) — category description lookup for report enrichment</li>
 *   <li>CBTRN02C.cbl (daily transaction posting) — category validation during 4-stage cascade</li>
 *   <li>Online transaction screens — display enrichment with category descriptions</li>
 *   <li>Flyway V3 migration — loaded from {@code trancatg.txt} ASCII fixture data</li>
 * </ul>
 *
 * <h3>Design Notes</h3>
 * <ul>
 *   <li>No custom query methods are required — standard {@link JpaRepository} CRUD operations
 *       cover all access patterns for this reference data table</li>
 *   <li>The composite primary key uses {@link TransactionCategoryId} ({@code @EmbeddedId})
 *       containing {@code typeCode} (CHAR(2)) and {@code catCode} (SMALLINT)</li>
 *   <li>Although this is read-only reference data in practice, the full {@link JpaRepository}
 *       interface is extended to support Flyway seed data loading and potential admin operations</li>
 *   <li>No {@code @Version} field is needed on the entity — this data does not participate
 *       in optimistic concurrency control</li>
 * </ul>
 *
 * @see TransactionCategory
 * @see TransactionCategoryId
 * @see org.springframework.data.jpa.repository.JpaRepository
 */
@Repository
public interface TransactionCategoryRepository
        extends JpaRepository<TransactionCategory, TransactionCategoryId> {

    // No custom query methods required.
    //
    // All access patterns for the TRANCATG reference data are satisfied by
    // inherited JpaRepository methods:
    //
    //   findById(TransactionCategoryId)   — keyed READ by composite key (type + category)
    //   findAll()                          — full reference data load
    //   save(TransactionCategory)          — insert/update (used by Flyway seed loading)
    //   saveAll(Iterable<TransactionCategory>) — bulk insert
    //   deleteById(TransactionCategoryId)  — remove by composite key
    //   deleteAll()                        — truncate reference data
    //   count()                            — total category count
    //   existsById(TransactionCategoryId)  — existence check
}

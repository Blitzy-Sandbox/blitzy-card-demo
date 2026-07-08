package com.carddemo.repository;

import com.carddemo.entity.TransactionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the {@link TransactionType} reference/lookup
 * entity — the transaction-type table that describes and validates transaction
 * types during posting and reporting.
 *
 * <p><strong>Legacy provenance.</strong> This repository is the migration target
 * for keyed access against the transaction-type reference data originally held
 * in the COBOL copybook {@code app/cpy/CVTRA03Y.cpy} ({@code TRAN-TYPE-RECORD},
 * record length 60 bytes) at source commit SHA {@code 27d6c6f}
 * (CardDemo v1.0-15-g27d6c6f-68). In the mainframe system this data was reached
 * by VSAM keyed access on the two-character {@code TRAN-TYPE} field; that access
 * pattern is preserved here as primary-key lookups and full-table loads through
 * the inherited Spring Data operations.</p>
 *
 * <p><strong>Primary key.</strong> {@link TransactionType} has a single natural
 * key — the two-character transaction-type code (COBOL {@code TRAN-TYPE PIC X(02)})
 * mapped to {@code java.lang.String}. The repository is therefore parameterized
 * as {@code JpaRepository<TransactionType, String>}. This is deliberately a
 * simple (non-composite) key and must not be confused with the composite-key
 * {@code TransactionCategoryType} (copybook {@code CVTRA04Y}), which is keyed on
 * transaction type <em>and</em> category and uses an {@code @EmbeddedId}.</p>
 *
 * <p><strong>Query surface.</strong> No custom or derived query methods are
 * declared: this is a small static reference table whose only access needs are
 * lookup by primary key and whole-table retrieval for validation and seeding.
 * The inherited operations — notably {@link JpaRepository#findById(Object)},
 * {@link JpaRepository#findAll()}, {@link JpaRepository#save(Object)}, and
 * {@link JpaRepository#saveAll(Iterable)} — fully cover those needs, so no
 * additional methods are added (avoiding unrequested scope expansion).</p>
 *
 * <p><strong>Schema ownership.</strong> The repository operates over the Flyway-managed
 * {@code transaction_type} table under Hibernate {@code ddl-auto: validate}; it
 * performs no schema definition of its own.</p>
 */
@Repository
public interface TransactionTypeRepository extends JpaRepository<TransactionType, String> {
    // Intentionally empty: the entity uses a single String primary key, and the
    // inherited CRUD/lookup operations (findById, findAll, save, saveAll, ...)
    // satisfy every access pattern required for this reference table. No custom
    // or derived query methods are declared.
}

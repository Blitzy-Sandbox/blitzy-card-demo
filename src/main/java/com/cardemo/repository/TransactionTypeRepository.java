package com.cardemo.repository;

import com.cardemo.model.entity.TransactionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the {@link TransactionType} reference data entity.
 *
 * <p>Replaces all COBOL VSAM keyed access patterns for the
 * {@code AWS.M2.CARDDEMO.TRANTYPE.VSAM.KSDS} dataset, which stores transaction
 * type reference data mapping 2-character type codes to human-readable descriptions.</p>
 *
 * <h3>Source VSAM Dataset</h3>
 * <pre>
 * DEFINE CLUSTER (NAME(AWS.M2.CARDDEMO.TRANTYPE.VSAM.KSDS)
 *     KEYS(2 0)
 *     RECORDSIZE(60 60)
 *     SHAREOPTIONS(1 4)
 *     INDEXED)
 * </pre>
 * <ul>
 *   <li>Primary key: 2-byte type code at offset 0 ({@code TRAN-TYPE PIC X(02)})</li>
 *   <li>No alternate indexes</li>
 *   <li>Record size: 60 bytes</li>
 *   <li>{@code SHAREOPTIONS(1 4)} — read-only shared access (multiple readers, single updater)</li>
 * </ul>
 *
 * <h3>COBOL Access Patterns Mapped</h3>
 * <ul>
 *   <li>Read-only reference data lookups by type code via {@code findById(String)}</li>
 *   <li>Full table load for caching/display via {@code findAll()}</li>
 *   <li>Used for display enrichment in transaction list/detail screens
 *       ({@code COTRN00C.cbl}, {@code COTRN01C.cbl})</li>
 *   <li>Used by {@code CBTRN03C.cbl} (transaction report) for type description lookup</li>
 *   <li>Example values: "SA" → "Sale", "RE" → "Return"</li>
 * </ul>
 *
 * <h3>Inherited Operations</h3>
 * <p>All required data access operations are inherited from {@link JpaRepository}:</p>
 * <ul>
 *   <li>{@code findById(String)} — keyed read by type code (replaces COBOL READ TRANTYPE)</li>
 *   <li>{@code findAll()} — load all type codes (small reference table)</li>
 *   <li>{@code save(TransactionType)} — persist a type record</li>
 *   <li>{@code saveAll(Iterable)} — bulk persist type records (Flyway seed data)</li>
 *   <li>{@code deleteById(String)} — remove by type code</li>
 *   <li>{@code deleteAll()} — clear all type records</li>
 *   <li>{@code count()} — count total type records</li>
 *   <li>{@code existsById(String)} — check existence by type code</li>
 * </ul>
 *
 * <p>No custom query methods are needed — standard JPA operations cover all
 * read-only reference data access patterns for transaction type lookups.</p>
 *
 * <p>COBOL source reference: {@code app/jcl/TRANTYPE.jcl} and
 * {@code app/cpy/CVTRA03Y.cpy} from commit {@code 27d6c6f}.</p>
 *
 * @see TransactionType
 */
@Repository
public interface TransactionTypeRepository extends JpaRepository<TransactionType, String> {
    // All required operations are inherited from JpaRepository.
    // No custom query methods needed for this read-only reference data entity.
}

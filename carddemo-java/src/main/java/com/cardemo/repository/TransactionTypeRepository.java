package com.cardemo.repository;

import com.cardemo.model.entity.TransactionType;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA data-access interface for the {@link TransactionType}
 * reference/lookup entity.
 *
 * <p>This repository is the Java&nbsp;25 / Spring Data JPA replacement for the
 * legacy AWS CardDemo <strong>{@code TRANTYPE} VSAM KSDS dataset</strong>
 * ({@code AWS.M2.CARDDEMO.TRANTYPE.VSAM.KSDS}), provisioned on the mainframe by
 * the IDCAMS job {@code app/jcl/TRANTYPE.jcl} ({@code DEFINE CLUSTER ... KEYS(2 0)
 * RECORDSIZE(60 60)}). Its fixed 60-byte record layout is defined by the COBOL
 * copybook {@code app/cpy/CVTRA03Y.cpy} ({@code 01 TRAN-TYPE-RECORD}:
 * {@code TRAN-TYPE PIC X(02)} + {@code TRAN-TYPE-DESC PIC X(50)} +
 * {@code FILLER PIC X(08)}). In the migrated stack the same data lives in the
 * PostgreSQL {@code transaction_type} table mapped by the {@link TransactionType}
 * entity, and every access path is served through this interface (AAP
 * &sect;0.4.1, &sect;0.6.2).</p>
 *
 * <h2>Role &mdash; static reference / lookup data</h2>
 * <p>{@code TRANTYPE} is small, static reference data: it maps a two-character
 * transaction-type code to a human-readable description. On the mainframe it was
 * consulted &mdash; never written &mdash; by the batch programs that needed to
 * validate or describe a transaction's type code:</p>
 * <ul>
 *   <li>{@code CBTRN02C} ({@code POSTTRAN} daily-transaction posting): reads the
 *       dataset while validating each incoming transaction's type code before the
 *       row is posted to the ledger.</li>
 *   <li>{@code CBTRN03C} (transaction-detail report): paragraph
 *       {@code 1500-B-LOOKUP-TRANTYPE} performs a keyed {@code READ TRANTYPE-FILE
 *       INTO TRAN-TYPE-RECORD} on the two-byte code ({@code RECORD KEY IS
 *       FD-TRAN-TYPE}) to resolve the stored type code into its
 *       {@code TRAN-TYPE-DESC} for the printed report.</li>
 * </ul>
 *
 * <h2>Technology substitution &mdash; VSAM KSDS keyed access &rarr;
 * {@code JpaRepository} (Minimal Change Clause, AAP &sect;0.7.1)</h2>
 * <p>A KSDS exposes exactly the access paths the COBOL programs use against
 * {@code TRANTYPE}: a single keyed read on the two-byte primary key, and a
 * sequential browse of the whole (small) cluster. Both map directly onto
 * operations inherited from {@link JpaRepository}, so this interface declares
 * <strong>no</strong> additional methods of its own:</p>
 * <table border="1">
 *   <caption>Legacy KSDS operation &rarr; inherited Spring Data operation</caption>
 *   <tr><th>Legacy VSAM KSDS operation</th><th>Inherited repository operation</th></tr>
 *   <tr><td>Keyed {@code READ} on the two-byte {@code TRAN-TYPE} key
 *       ({@code 1500-B-LOOKUP-TRANTYPE} in {@code CBTRN03C}; posting validation in
 *       {@code CBTRN02C})</td>
 *       <td>{@link JpaRepository#findById(Object) findById(String)} &mdash; returns
 *           an {@code Optional}; an absent value models the COBOL invalid-key
 *           ("INVALID TRANSACTION TYPE") path</td></tr>
 *   <tr><td>Browse the (small) reference cluster to load all type codes</td>
 *       <td>{@link JpaRepository#findAll() findAll()}</td></tr>
 *   <tr><td>Reference-table row count</td>
 *       <td>{@link JpaRepository#count() count()}</td></tr>
 * </table>
 *
 * <h2>Generic type contract</h2>
 * <p>The repository is typed {@code JpaRepository<TransactionType, String>}. The
 * identifier type is {@link String} &mdash; not a numeric type &mdash; because
 * {@link TransactionType} carries a <strong>plain single-column primary key</strong>
 * ({@link TransactionType#getTranType()}), migrated from {@code TRAN-TYPE
 * PIC X(02)}: a fixed two-character (alphanumeric) code kept as a {@link String}
 * (PostgreSQL {@code VARCHAR(2)}) so the exact two-byte width and any leading
 * characters are preserved, matching the keyed VSAM access.</p>
 *
 * <p><strong>This is the interface's single subtlety.</strong> Do not confuse it
 * with the sibling {@code TransactionCategoryRepository}: {@code TransactionType}
 * (dataset {@code TRANTYPE}, copybook {@code CVTRA03Y.cpy}) keys on the type code
 * <em>alone</em> and therefore uses a plain {@code @Id} of type {@link String},
 * whereas {@code TransactionCategory} (dataset {@code TRANCATG}, copybook
 * {@code CVTRA04Y.cpy}) keys on the type code <em>plus</em> a four-digit category
 * code and therefore uses an {@code @EmbeddedId} composite key
 * ({@code TransactionCategoryId}). Hence this repository is parameterised with
 * {@link String}, not a composite-key type.</p>
 *
 * <h2>No optimistic locking / no transactional method</h2>
 * <p>Per AAP &sect;0.7.5, JPA {@code @Version} optimistic locking is applied
 * <strong>only</strong> to {@code Account} and {@code Card} (the read-update
 * programs {@code COACTUPC} and {@code COCRDUPC}). {@code TRANTYPE} is static
 * reference data that is read but never rewritten through a read-update snapshot
 * comparison, so the {@link TransactionType} entity carries no {@code @Version}
 * column and this interface declares no concurrency-specific method.</p>
 *
 * <h2>Design notes</h2>
 * <ul>
 *   <li><strong>No {@code @Repository} annotation.</strong> Spring Data
 *       automatically detects interfaces that extend {@link JpaRepository}, and
 *       Spring Boot auto-configures repository scanning for the application base
 *       package {@code com.cardemo}; an explicit {@code @Repository} or
 *       {@code @EnableJpaRepositories} is unnecessary. Persistence exceptions are
 *       still translated transparently for these proxies.</li>
 *   <li><strong>No custom query methods.</strong> Every operation the COBOL
 *       programs perform against {@code TRANTYPE} is a single keyed read or a
 *       full browse, and both are already provided by {@link JpaRepository}. Per
 *       the Minimal Change Clause (AAP &sect;0.7.1) no derived queries,
 *       {@code @Query} methods, Jakarta Bean Validation or business logic are
 *       added here &mdash; that behaviour belongs to the batch
 *       reader/processor/writer and service layers.</li>
 * </ul>
 *
 * <p><strong>Traceability.</strong> Derived from the frozen COBOL baseline at
 * commit SHA {@code 27d6c6f}. The COBOL/JCL sources are read-only reference
 * material and are never copied into this repository.</p>
 *
 * @see TransactionType
 * @see JpaRepository
 */
public interface TransactionTypeRepository extends JpaRepository<TransactionType, String> {
    // Intentionally empty: TRANTYPE is static reference/lookup data reached only by
    // a keyed read on the two-byte type code (CBTRN03C 1500-B-LOOKUP-TRANTYPE,
    // CBTRN02C posting validation) and a full browse of the small cluster. Both map
    // to operations inherited from JpaRepository (findById(String) / findAll() /
    // count()), so no custom query method is declared (Minimal Change Clause,
    // AAP §0.7.1). The String type parameter reflects TransactionType's plain @Id
    // (TRAN-TYPE PIC X(02) -> String) and deliberately contrasts with the composite
    // key used by the sibling TransactionCategoryRepository.
}

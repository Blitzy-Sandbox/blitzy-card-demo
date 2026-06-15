package com.cardemo.repository;

import com.cardemo.model.entity.TransactionCategory;
import com.cardemo.model.key.TransactionCategoryId;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA data-access interface for the {@link TransactionCategory}
 * reference/lookup entity.
 *
 * <p>This repository is the Java&nbsp;25 / Spring Data JPA replacement for the
 * legacy AWS CardDemo <strong>{@code TRANCATG} VSAM KSDS dataset</strong>
 * ({@code AWS.M2.CARDDEMO.TRANCATG.VSAM.KSDS}), provisioned on the mainframe by
 * the IDCAMS job {@code app/jcl/TRANCATG.jcl}
 * ({@code DEFINE CLUSTER ... KEYS(6 0) RECORDSIZE(60 60) INDEXED}). Its fixed
 * 60-byte record layout is defined by the COBOL copybook
 * {@code app/cpy/CVTRA04Y.cpy} ({@code 01 TRAN-CAT-RECORD}:
 * {@code TRAN-CAT-KEY} = {@code TRAN-TYPE-CD PIC X(02)} +
 * {@code TRAN-CAT-CD PIC 9(04)}, then {@code TRAN-CAT-TYPE-DESC PIC X(50)} and a
 * trailing {@code FILLER PIC X(04)}). In the migrated stack the same data lives
 * in the PostgreSQL {@code transaction_category} table mapped by the
 * {@link TransactionCategory} entity, and every access path is served through
 * this interface (AAP &sect;0.4.1, &sect;0.6.2).</p>
 *
 * <h2>Role &mdash; static reference / lookup data</h2>
 * <p>{@code TRANCATG} is small, static reference data: it maps a
 * {@code (transaction-type code, transaction-category code)} pair to a
 * human-readable category description. On the mainframe it was consulted
 * &mdash; never written &mdash; by the batch programs that needed to validate
 * or describe a transaction's type/category combination:</p>
 * <ul>
 *   <li>{@code CBTRN02C} ({@code POSTTRAN} daily-transaction posting): reads the
 *       dataset while validating each incoming transaction's type/category
 *       combination before the row is posted to the ledger.</li>
 *   <li>{@code CBTRN03C} (transaction-detail report): paragraph
 *       {@code 1500-C-LOOKUP-TRANCATG} performs a keyed
 *       {@code READ TRANCATG-FILE INTO TRAN-CAT-RECORD} on the six-byte
 *       composite key ({@code RECORD KEY IS FD-TRAN-CAT-KEY}) to resolve the
 *       stored type/category combination into its {@code TRAN-CAT-TYPE-DESC} for
 *       the printed report; an {@code INVALID KEY} sets {@code IO-STATUS 23}
 *       (record-not-found).</li>
 * </ul>
 *
 * <h2>Technology substitution &mdash; VSAM KSDS composite-keyed access &rarr;
 * {@code JpaRepository} (Minimal Change Clause, AAP &sect;0.7.1)</h2>
 * <p>A KSDS exposes exactly the access paths the COBOL programs use against
 * {@code TRANCATG}: a single keyed read on the six-byte composite primary key,
 * and a sequential browse of the whole (small) cluster. Both map directly onto
 * operations inherited from {@link JpaRepository}, so this interface declares
 * <strong>no</strong> additional methods of its own:</p>
 * <table border="1">
 *   <caption>Legacy KSDS operation &rarr; inherited Spring Data operation</caption>
 *   <tr><th>Legacy VSAM KSDS operation</th><th>Inherited repository operation</th></tr>
 *   <tr><td>Keyed {@code READ} on the composite {@code TRAN-CAT-KEY}
 *       ({@code TRAN-TYPE-CD} + {@code TRAN-CAT-CD}) &mdash;
 *       {@code 1500-C-LOOKUP-TRANCATG} in {@code CBTRN03C}; posting validation in
 *       {@code CBTRN02C}</td>
 *       <td>{@link JpaRepository#findById(Object) findById(TransactionCategoryId)}
 *           &mdash; returns an {@code Optional}; an absent value models the COBOL
 *           {@code INVALID KEY} ({@code IO-STATUS 23}, "INVALID TRAN CATG KEY")
 *           path</td></tr>
 *   <tr><td>Browse the (small) reference cluster to load all categories</td>
 *       <td>{@link JpaRepository#findAll() findAll()}</td></tr>
 *   <tr><td>Reference-table row count</td>
 *       <td>{@link JpaRepository#count() count()}</td></tr>
 * </table>
 *
 * <h2>Generic type contract</h2>
 * <p>The repository is typed
 * {@code JpaRepository<TransactionCategory, TransactionCategoryId>}. The
 * identifier type is the {@code @Embeddable} composite key
 * {@link TransactionCategoryId} &mdash; <strong>not</strong> a scalar &mdash;
 * because {@link TransactionCategory} carries a
 * <strong>two-part {@code @EmbeddedId} primary key</strong>
 * ({@link TransactionCategory#getId()}), migrated from the COBOL
 * {@code TRAN-CAT-KEY} group. The key's two components, declared in
 * {@link TransactionCategoryId} in their original COBOL field order, are
 * {@code typeCode} ({@code TRAN-TYPE-CD PIC X(02)} &rarr; {@link String} of
 * length&nbsp;2, column {@code type_code}) and {@code catCode}
 * ({@code TRAN-CAT-CD PIC 9(04)} &rarr; {@link Integer}, column
 * {@code category_code}). Consequently {@link JpaRepository#findById(Object)}
 * takes a <strong>fully-populated</strong> {@link TransactionCategoryId} (both
 * the type code and the category code must be set), exactly as the legacy keyed
 * read required the complete six-byte key.</p>
 *
 * <p><strong>This is the interface's single subtlety.</strong> Do not confuse it
 * with the sibling {@code TransactionTypeRepository}: {@code TransactionType}
 * (dataset {@code TRANTYPE}, copybook {@code CVTRA03Y.cpy}) keys on the type code
 * <em>alone</em> and therefore uses a plain {@code @Id} of type {@link String},
 * whereas {@code TransactionCategory} (dataset {@code TRANCATG}, copybook
 * {@code CVTRA04Y.cpy}) keys on the type code <em>plus</em> a four-digit category
 * code and therefore uses an {@code @EmbeddedId} composite key. Hence this
 * repository is parameterised with {@link TransactionCategoryId}, not with a
 * scalar key type.</p>
 *
 * <h2>No optimistic locking / no transactional method</h2>
 * <p>Per AAP &sect;0.7.5, JPA {@code @Version} optimistic locking is applied
 * <strong>only</strong> to {@code Account} and {@code Card} (the read-update
 * programs {@code COACTUPC} and {@code COCRDUPC}). {@code TRANCATG} is static
 * reference data that is read but never rewritten through a read-update snapshot
 * comparison, so the {@link TransactionCategory} entity carries no
 * {@code @Version} column and this interface declares no concurrency-specific
 * method.</p>
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
 *       programs perform against {@code TRANCATG} is a single keyed read or a
 *       full browse, and both are already provided by {@link JpaRepository}. No
 *       program filters by a partial key in a way that would require a derived
 *       query. Per the Minimal Change Clause (AAP &sect;0.7.1) no derived
 *       queries, {@code @Query} methods, Jakarta Bean Validation or business
 *       logic are added here &mdash; that behaviour belongs to the batch
 *       reader/processor/writer and service layers.</li>
 * </ul>
 *
 * <p><strong>Traceability.</strong> Derived from the frozen COBOL baseline at
 * commit SHA {@code 27d6c6f}. The COBOL/JCL sources are read-only reference
 * material and are never copied into this repository.</p>
 *
 * @see TransactionCategory
 * @see TransactionCategoryId
 * @see JpaRepository
 */
public interface TransactionCategoryRepository
        extends JpaRepository<TransactionCategory, TransactionCategoryId> {
    // Intentionally empty: TRANCATG is static reference/lookup data reached only by
    // a keyed read on the six-byte composite TRAN-CAT-KEY (CBTRN03C
    // 1500-C-LOOKUP-TRANCATG; CBTRN02C posting validation) and a full browse of the
    // small cluster. Both map to operations inherited from JpaRepository
    // (findById(TransactionCategoryId) / findAll() / count()), so no custom query
    // method is declared (Minimal Change Clause, AAP §0.7.1). The
    // TransactionCategoryId type parameter reflects TransactionCategory's
    // @EmbeddedId composite key (TRAN-TYPE-CD PIC X(02) -> typeCode String(2) +
    // TRAN-CAT-CD PIC 9(04) -> catCode Integer) and deliberately contrasts with the
    // plain @Id String key used by the sibling TransactionTypeRepository.
}

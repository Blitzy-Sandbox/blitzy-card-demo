package com.cardemo.repository;

import com.cardemo.model.entity.Customer;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA data-access interface for the {@link Customer} aggregate.
 *
 * <p>This repository is the Java 25 / Spring Data JPA replacement for the legacy
 * AWS CardDemo VSAM KSDS dataset {@code CUSTDAT}. On the mainframe that cluster
 * was provisioned by {@code app/jcl/CUSTFILE.jcl}
 * ({@code DEFINE CLUSTER ... KEYS(9 0) RECORDSIZE(500 500) INDEXED}), its
 * fixed-length 500-byte record layout was defined by the copybook
 * {@code app/cpy/CVCUS01Y.cpy} ({@code 01 CUSTOMER-RECORD}), and it was reached
 * through CICS file control (online) and QSAM/VSAM sequential I/O (batch). In the
 * migrated stack the same data lives in the PostgreSQL {@code customer} table
 * mapped by the {@link Customer} entity, and every access path is served through
 * this interface.</p>
 *
 * <h2>Technology substitution &mdash; VSAM KSDS keyed access &rarr; {@code JpaRepository}
 * (Minimal Change Clause, AAP &sect;0.7.1)</h2>
 * <p>The VSAM Key-Sequenced Data Set exposed record-level operations keyed on the
 * 9-byte cluster key {@code CUST-ID} ({@code PIC 9(09)}), plus a sequential browse
 * used by the batch dump. Each of those operations maps directly onto an operation
 * inherited from {@link JpaRepository}, so this interface declares <strong>no</strong>
 * additional methods of its own:</p>
 * <table border="1">
 *   <caption>VSAM/CICS operation &rarr; inherited Spring Data operation</caption>
 *   <tr><th>Legacy VSAM/file operation</th><th>Inherited repository operation</th></tr>
 *   <tr><td>{@code READ DATASET('CUSTDAT') RIDFLD(custId)} (COACTVWC keyed read)</td>
 *       <td>{@link JpaRepository#findById(Object) findById(Long)}</td></tr>
 *   <tr><td>{@code READ ...} existence probe</td>
 *       <td>{@link JpaRepository#existsById(Object) existsById(Long)}</td></tr>
 *   <tr><td>{@code WRITE} / {@code REWRITE DATASET('CUSTDAT')}</td>
 *       <td>{@link JpaRepository#save(Object) save(Customer)}</td></tr>
 *   <tr><td>Bulk load (IDCAMS {@code REPRO} into the cluster)</td>
 *       <td>{@link JpaRepository#saveAll(Iterable) saveAll(Iterable&lt;Customer&gt;)}</td></tr>
 *   <tr><td>{@code DELETE DATASET('CUSTDAT')}</td>
 *       <td>{@link JpaRepository#delete(Object) delete(Customer)} /
 *           {@link JpaRepository#deleteById(Object) deleteById(Long)}</td></tr>
 *   <tr><td>Sequential browse ({@code OPEN INPUT} + {@code READ NEXT}, CBCUS01C dump)</td>
 *       <td>{@link JpaRepository#findAll() findAll()}</td></tr>
 *   <tr><td>Cluster record count</td>
 *       <td>{@link JpaRepository#count() count()}</td></tr>
 * </table>
 *
 * <h2>Generic type contract</h2>
 * <p>The repository is typed {@code JpaRepository<Customer, Long>}. The identifier
 * type is {@link Long} because the primary key {@link Customer#getCustId()} is
 * migrated from {@code CUST-ID PIC 9(09)} &mdash; a nine-digit unsigned integer
 * mapped to a PostgreSQL {@code BIGINT}. Using {@link Long} keeps the key type
 * consistent with the {@link Long}-identifier convention applied across the
 * persistence layer (for example {@code Account.acctId} and the
 * {@code CardCrossReference} account/customer keys).</p>
 *
 * <h2>No optimistic-locking column</h2>
 * <p>Unlike {@code Account} and {@code Card}, the {@link Customer} entity carries
 * <strong>no</strong> JPA {@code @Version} column: it is not an optimistic-locking
 * target (AAP &sect;0.7.5). The dual {@code ACCTDAT}+{@code CUSTDAT} update guarded
 * by the single {@code SYNCPOINT ROLLBACK} of {@code COACTUPC} is handled at the
 * {@code @Transactional} service boundary, not at this repository, and the customer
 * record participates in that unit of work as an ordinary, non-versioned read/write.</p>
 *
 * <h2>Consumers (AAP &sect;0.6.2 access matrix)</h2>
 * <p>{@code CUSTDAT} is shared across the online (REST) and batch contexts, so a
 * single repository serves both layers:</p>
 * <ul>
 *   <li>Online &mdash; {@code COACTVWC} (account view) and {@code COACTUPC}
 *       (account update) read the customer by id ({@code CUST-ID}, resolved from the
 *       card cross-reference) &rarr; {@code findById(Long)}.</li>
 *   <li>Batch &mdash; {@code CBCUS01C} sequentially dumps the customer file
 *       (INDEXED organization, SEQUENTIAL access) &rarr; {@code findAll()}; and
 *       {@code CBSTM03A} reads a customer by id during statement generation &rarr;
 *       {@code findById(Long)}.</li>
 * </ul>
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
 *       programs perform against {@code CUSTDAT} is keyed on the primary key
 *       ({@code CUST-ID}) or is a full sequential scan, and both are already
 *       provided by {@link JpaRepository}. Per the Minimal Change Clause
 *       (AAP &sect;0.7.1) no derived queries, {@code @Query} methods, Jakarta Bean
 *       Validation or business logic are added here &mdash; that behaviour belongs
 *       to the service and DTO layers.</li>
 * </ul>
 *
 * <p><strong>Traceability.</strong> Derived from the frozen COBOL baseline at
 * commit SHA {@code 27d6c6f}. The COBOL/JCL sources are read-only reference
 * material and are never copied into this repository.</p>
 *
 * @see Customer
 * @see JpaRepository
 */
public interface CustomerRepository extends JpaRepository<Customer, Long> {
    // Intentionally empty: CUSTDAT was accessed by its primary key (CUST-ID) for
    // keyed reads/updates and by a full sequential browse for the batch dump, so
    // every required operation is inherited from JpaRepository and no custom query
    // method is declared (Minimal Change Clause, AAP §0.7.1).
}

package com.cardemo.repository;

import com.cardemo.model.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA data-access interface for the {@link Account} aggregate.
 *
 * <p>This repository is the Java 25 / Spring Data JPA replacement for the legacy
 * AWS CardDemo VSAM KSDS dataset {@code ACCTDAT}. On the mainframe that cluster
 * was provisioned by {@code app/jcl/ACCTFILE.jcl}
 * ({@code DEFINE CLUSTER ... KEYS(11 0) RECORDSIZE(300 300) INDEXED}), its
 * fixed-length 300-byte record layout was defined by the copybook
 * {@code app/cpy/CVACT01Y.cpy} ({@code 01 ACCOUNT-RECORD}), and it was reached
 * through CICS file control. In the migrated stack the same data lives in the
 * PostgreSQL {@code account} table mapped by the {@link Account} entity, and all
 * keyed access is served through this interface.</p>
 *
 * <h2>Technology substitution &mdash; VSAM KSDS keyed access &rarr; {@code JpaRepository}
 * (Minimal Change Clause, AAP &sect;0.7.1)</h2>
 * <p>The VSAM Key-Sequenced Data Set exposed record-level operations keyed on the
 * 11-byte cluster key {@code ACCT-ID}. Each of those operations maps directly onto
 * an operation inherited from {@link JpaRepository}, so this interface declares
 * <strong>no</strong> additional methods of its own:</p>
 * <table border="1">
 *   <caption>VSAM/CICS operation &rarr; inherited Spring Data operation</caption>
 *   <tr><th>Legacy VSAM/CICS operation</th><th>Inherited repository operation</th></tr>
 *   <tr><td>{@code READ DATASET('ACCTDAT') RIDFLD(acctId)}</td>
 *       <td>{@link JpaRepository#findById(Object) findById(Long)}</td></tr>
 *   <tr><td>{@code READ ... } existence probe</td>
 *       <td>{@link JpaRepository#existsById(Object) existsById(Long)}</td></tr>
 *   <tr><td>{@code WRITE} / {@code REWRITE DATASET('ACCTDAT')}</td>
 *       <td>{@link JpaRepository#save(Object) save(Account)}</td></tr>
 *   <tr><td>Bulk load (IDCAMS {@code REPRO} into the cluster)</td>
 *       <td>{@link JpaRepository#saveAll(Iterable) saveAll(Iterable&lt;Account&gt;)}</td></tr>
 *   <tr><td>{@code DELETE DATASET('ACCTDAT')}</td>
 *       <td>{@link JpaRepository#delete(Object) delete(Account)} /
 *           {@link JpaRepository#deleteById(Object) deleteById(Long)}</td></tr>
 *   <tr><td>Sequential browse ({@code STARTBR}/{@code READNEXT}, batch dump)</td>
 *       <td>{@link JpaRepository#findAll() findAll()}</td></tr>
 *   <tr><td>Cluster record count</td>
 *       <td>{@link JpaRepository#count() count()}</td></tr>
 * </table>
 *
 * <h2>Generic type contract</h2>
 * <p>The repository is typed {@code JpaRepository<Account, Long>}. The identifier
 * type is {@link Long} because the primary key {@link Account#getAcctId()} is
 * migrated from {@code ACCT-ID PIC 9(11)} &mdash; an eleven-digit unsigned integer
 * whose range exceeds the ~2.1-billion (ten-digit) ceiling of {@code Integer}, and
 * which therefore maps to a PostgreSQL {@code BIGINT}. This deliberately resolves
 * the loosely-documented {@code JpaRepository<Account, String>} variant that
 * appears in the blueprint: {@code String} is incorrect. Using {@link Long} keeps
 * the key type consistent across the whole persistence layer, since the
 * {@code Account} key is referenced by {@code Card}, {@code CardCrossReference}
 * and the {@code TransactionCategoryBalanceId} composite key.</p>
 *
 * <h2>Read-update concurrency &mdash; {@code @Version} optimistic locking</h2>
 * <p>The online account-update program {@code COACTUPC} performed an optimistic
 * read-update snapshot comparison (it re-read the record before its
 * {@code REWRITE} and compared the before/after images) to detect concurrent
 * modification. That safeguard is <em>not</em> reproduced here; it lives on the
 * {@link Account} entity as its JPA {@code @Version} column. Because
 * {@link JpaRepository#save(Object)} honors the version automatically, a stale
 * update surfaces as an {@code org.springframework.orm.ObjectOptimisticLockingFailureException}
 * (wrapping the JPA {@code OptimisticLockException}) without any custom method on
 * this interface. Likewise, the sole {@code SYNCPOINT ROLLBACK} of {@code COACTUPC}
 * (the dual {@code ACCTDAT}+{@code CUSTDAT} update) is handled at the
 * {@code @Transactional} service boundary, not at the repository.</p>
 *
 * <h2>Consumers (AAP &sect;0.6.2 access matrix)</h2>
 * <p>{@code ACCTDAT} is shared across the online (REST) and batch contexts, so a
 * single repository serves both layers:</p>
 * <ul>
 *   <li>Online &mdash; {@code COACTVWC} (account view, keyed read) and
 *       {@code COACTUPC} (account update, keyed read-then-rewrite).</li>
 *   <li>Batch &mdash; {@code CBACT01C} (sequential account dump &rarr;
 *       {@code findAll()}) plus {@code CBACT04C} and {@code CBTRN02C} (keyed reads
 *       &rarr; {@code findById(Long)}).</li>
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
 *       programs perform against {@code ACCTDAT} is keyed on the primary key and
 *       is already provided by {@link JpaRepository}. Per the Minimal Change
 *       Clause (AAP &sect;0.7.1) no derived queries, {@code @Query} methods,
 *       Jakarta Bean Validation or business logic are added here &mdash; that
 *       behaviour belongs to the service and DTO layers.</li>
 * </ul>
 *
 * <p><strong>Traceability.</strong> Derived from the frozen COBOL baseline at
 * commit SHA {@code 27d6c6f}. The COBOL/JCL sources are read-only reference
 * material and are never copied into this repository.</p>
 *
 * @see Account
 * @see JpaRepository
 */
public interface AccountRepository extends JpaRepository<Account, Long> {
    // Intentionally empty: ACCTDAT was accessed exclusively by its primary key
    // (ACCT-ID), so every required operation is inherited from JpaRepository and
    // no custom query method is declared (Minimal Change Clause, AAP §0.7.1).
}

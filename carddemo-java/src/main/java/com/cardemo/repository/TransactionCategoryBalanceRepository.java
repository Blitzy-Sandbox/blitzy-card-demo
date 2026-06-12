package com.cardemo.repository;

import com.cardemo.model.entity.TransactionCategoryBalance;
import com.cardemo.model.key.TransactionCategoryBalanceId;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA data-access interface for the {@link TransactionCategoryBalance}
 * entity &mdash; the per-account, per-type, per-category running-balance record.
 *
 * <p>This repository is the Java&nbsp;25 / Spring Data JPA replacement for the
 * legacy AWS CardDemo <strong>{@code TCATBALF} VSAM KSDS dataset</strong>
 * ({@code AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS}), provisioned on the mainframe by
 * the IDCAMS job {@code app/jcl/TCATBALF.jcl}
 * ({@code DEFINE CLUSTER ... KEYS(17 0) RECORDSIZE(50 50) INDEXED}). Its fixed
 * 50-byte record layout is defined by the COBOL copybook
 * {@code app/cpy/CVTRA01Y.cpy} ({@code 01 TRAN-CAT-BAL-RECORD}: the 17-byte
 * {@code TRAN-CAT-KEY} group &mdash; {@code TRANCAT-ACCT-ID PIC 9(11)} +
 * {@code TRANCAT-TYPE-CD PIC X(02)} + {@code TRANCAT-CD PIC 9(04)} &mdash;
 * followed by {@code TRAN-CAT-BAL PIC S9(09)V99} and {@code FILLER PIC X(22)}).
 * In the migrated stack the same data lives in the PostgreSQL
 * {@code transaction_category_balances} table mapped by the
 * {@link TransactionCategoryBalance} entity, and every access path is served
 * through this interface (AAP &sect;0.4.1, &sect;0.6.2).</p>
 *
 * <h2>Role &mdash; daily-posting running balances read for interest</h2>
 * <p>{@code TCATBALF} holds a running balance for each distinct
 * {@code (account, transaction-type, transaction-category)} combination. On the
 * mainframe it sat at the intersection of the posting and interest paths:</p>
 * <ul>
 *   <li>{@code CBTRN02C} ({@code POSTTRAN} daily-transaction posting):
 *       paragraph {@code 2700-UPDATE-TCATBAL} <em>maintains</em> the balance.
 *       It assembles the full three-part key
 *       ({@code FD-TRANCAT-ACCT-ID} + {@code FD-TRANCAT-TYPE-CD} +
 *       {@code FD-TRANCAT-CD}) and issues a keyed {@code READ TCATBAL-FILE},
 *       then either inserts a new balance row when the key is absent
 *       ({@code 2700-A-CREATE-TCATBAL-REC} &rarr; {@code WRITE} on FILE STATUS
 *       {@code '23'}) or updates the existing row
 *       ({@code 2700-B-UPDATE-TCATBAL-REC} &rarr; {@code REWRITE} on FILE STATUS
 *       {@code '00'}).</li>
 *   <li>{@code CBACT04C} (interest calculation): paragraph
 *       {@code 1000-TCATBALF-GET-NEXT} <em>reads</em> the balances. With the
 *       file opened {@code ACCESS MODE IS SEQUENTIAL} it performs a plain
 *       {@code READ TCATBAL-FILE} (next record) and walks the entire cluster to
 *       end-of-file (FILE STATUS {@code '10'}); it does no {@code START} and no
 *       key positioning.</li>
 * </ul>
 *
 * <h2>Technology substitution &mdash; VSAM KSDS composite keyed access &rarr;
 * {@code JpaRepository} (Minimal Change Clause, AAP &sect;0.7.1)</h2>
 * <p>A KSDS exposes exactly the access paths the COBOL programs use against
 * {@code TCATBALF}: a single keyed read/write on the full composite key, and a
 * sequential browse of the whole cluster. The physically concatenated 17-byte
 * key is replaced by an explicit, typed composite key &mdash; the
 * {@code @EmbeddedId} {@link TransactionCategoryBalanceId} declared on the
 * entity &mdash; and every observed operation maps directly onto a method
 * inherited from {@link JpaRepository}. This interface therefore declares
 * <strong>no</strong> methods of its own:</p>
 * <table border="1">
 *   <caption>Legacy KSDS operation &rarr; inherited Spring Data operation</caption>
 *   <tr><th>Legacy VSAM KSDS operation</th><th>Inherited repository operation</th></tr>
 *   <tr><td>Keyed {@code READ} on the full three-part {@code TRAN-CAT-KEY}
 *       ({@code CBTRN02C 2700-UPDATE-TCATBAL})</td>
 *       <td>{@link JpaRepository#findById(Object)
 *           findById(TransactionCategoryBalanceId)} &mdash; returns an
 *           {@code Optional}; an absent value models the COBOL
 *           {@code INVALID KEY} / FILE STATUS {@code '23'} "not found" path</td></tr>
 *   <tr><td>{@code WRITE} a new balance row (status {@code '23'} create path,
 *       {@code 2700-A-CREATE-TCATBAL-REC}) or {@code REWRITE} the existing row
 *       (status {@code '00'} update path, {@code 2700-B-UPDATE-TCATBAL-REC})</td>
 *       <td>{@link JpaRepository#save(Object) save(TransactionCategoryBalance)}
 *           &mdash; a single upsert-style call that inserts when the entity is
 *           new and updates when it already exists</td></tr>
 *   <tr><td>Sequential browse of the whole cluster
 *       ({@code CBACT04C 1000-TCATBALF-GET-NEXT})</td>
 *       <td>{@link JpaRepository#findAll() findAll()}</td></tr>
 * </table>
 *
 * <h2>Generic type contract</h2>
 * <p>The repository is typed
 * {@code JpaRepository<TransactionCategoryBalance, TransactionCategoryBalanceId>}.
 * Unlike a single-column reference table, {@link TransactionCategoryBalance}
 * carries a <strong>composite</strong> primary key: the {@code @EmbeddedId}
 * {@link TransactionCategoryBalanceId}, whose components &mdash; in COBOL field
 * order &mdash; are {@code acctId} ({@link Long}, from
 * {@code TRANCAT-ACCT-ID PIC 9(11)}; eleven digits exceed the {@code Integer}
 * range, and {@link Long} also matches the {@code Account} primary key so balance
 * rows stay type-consistent with accounts), {@code typeCode} ({@link String} of
 * length&nbsp;2, from {@code TRANCAT-TYPE-CD PIC X(02)}) and {@code catCode}
 * ({@link Integer}, from {@code TRANCAT-CD PIC 9(04)}). The identifier type
 * parameter is therefore the composite-key class
 * {@link TransactionCategoryBalanceId}, not a scalar type, so {@code findById} and
 * {@code save} operate on the full three-part key exactly as the keyed COBOL
 * access did.</p>
 *
 * <h2>Why {@code findByIdAcctId} is deliberately omitted</h2>
 * <p>No custom account-scoped finder (for example {@code findByIdAcctId}) is
 * declared, and this omission is intentional rather than an oversight.
 * <strong>No</strong> source program ranges {@code TCATBALF} by account alone:
 * {@code CBTRN02C} always addresses a row by the <em>full</em> composite key, and
 * {@code CBACT04C} browses the <em>entire</em> file sequentially and correlates by
 * separately reading the matching account &mdash; it never issues a partial-key
 * {@code START}/range read on the account component. Adding an account-only finder
 * would introduce an access path the legacy system never had; that is speculative
 * over-engineering and would violate the Minimal Change Clause (AAP &sect;0.7.1).
 * The inherited {@code findById}, {@code save} and {@code findAll} cover every
 * observed access.</p>
 *
 * <h2>No optimistic locking / no transactional method</h2>
 * <p>Per AAP &sect;0.7.5, JPA {@code @Version} optimistic locking is applied
 * <strong>only</strong> to {@code Account} and {@code Card} (the online
 * read-update programs {@code COACTUPC} and {@code COCRDUPC}). The
 * category-balance record is maintained by batch posting ({@code CBTRN02C}), not
 * by an online read-update snapshot comparison, so the
 * {@link TransactionCategoryBalance} entity carries no {@code @Version} column and
 * this interface declares no concurrency-specific method.</p>
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
 *       programs perform against {@code TCATBALF} is a full-key read/write or a
 *       full browse, and all three are already provided by {@link JpaRepository}.
 *       Per the Minimal Change Clause (AAP &sect;0.7.1) no derived queries,
 *       {@code @Query} methods, Jakarta Bean Validation or business logic are
 *       added here &mdash; the create-vs-update decision (FILE STATUS
 *       {@code '23'} vs {@code '00'}) and the {@link java.math.BigDecimal} balance
 *       arithmetic belong to the batch posting/processing layer, not to the
 *       data-access interface.</li>
 * </ul>
 *
 * <p><strong>Traceability.</strong> Derived from the frozen COBOL baseline at
 * commit SHA {@code 27d6c6f}. The COBOL/JCL sources are read-only reference
 * material and are never copied into this repository.</p>
 *
 * @see TransactionCategoryBalance
 * @see TransactionCategoryBalanceId
 * @see JpaRepository
 */
public interface TransactionCategoryBalanceRepository
        extends JpaRepository<TransactionCategoryBalance, TransactionCategoryBalanceId> {
    // Intentionally empty: TCATBALF is reached only by a full-composite-key
    // read-then-write (CBTRN02C 2700-UPDATE-TCATBAL -> findById + save; FILE
    // STATUS '23' inserts, '00' updates) and a whole-file sequential browse
    // (CBACT04C 1000-TCATBALF-GET-NEXT -> findAll). All three map to operations
    // inherited from JpaRepository, so no custom query method is declared
    // (Minimal Change Clause, AAP §0.7.1). The composite ID type parameter is
    // TransactionCategoryBalanceId (acctId Long + typeCode String(2) + catCode
    // Integer). findByIdAcctId is deliberately omitted: no COBOL program ranges
    // TCATBALF by account alone.
}

package com.cardemo.repository;

import com.cardemo.model.entity.Card;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA data-access interface for the {@link Card} aggregate.
 *
 * <p>This repository is the Java 25 / Spring Data JPA replacement for the legacy
 * AWS CardDemo VSAM KSDS dataset {@code CARDDAT} <em>together with</em> its
 * alternate index {@code CARDAIX}. On the mainframe the base cluster and its
 * alternate index were provisioned by {@code app/jcl/CARDFILE.jcl}
 * ({@code DEFINE CLUSTER ... KEYS(16 0) RECORDSIZE(150 150) INDEXED} for the base
 * cluster, then {@code DEFINE ALTERNATEINDEX ... KEYS(11 16) NONUNIQUEKEY UPGRADE}
 * plus {@code DEFINE PATH} and {@code BLDINDEX} for the alternate index). The
 * fixed-length 150-byte record layout was defined by the copybook
 * {@code app/cpy/CVACT02Y.cpy} ({@code 01 CARD-RECORD}). In the migrated stack the
 * same data lives in the PostgreSQL {@code card} table mapped by the {@link Card}
 * entity, and every access path the online programs used is served through this
 * interface.</p>
 *
 * <h2>Technology substitution &mdash; VSAM keyed access &rarr; {@code JpaRepository}
 * (Minimal Change Clause, AAP &sect;0.7.1)</h2>
 * <p>The base KSDS exposed record-level operations keyed on the 16-byte cluster key
 * {@code CARD-NUM} (the 16-digit Primary Account Number). Each of those primary-key
 * operations maps directly onto an operation inherited from {@link JpaRepository},
 * so the primary-key CRUD surface is inherited rather than redeclared:</p>
 * <table border="1">
 *   <caption>VSAM/CICS operation &rarr; inherited Spring Data operation</caption>
 *   <tr><th>Legacy VSAM/CICS operation</th><th>Inherited repository operation</th></tr>
 *   <tr><td>{@code READ DATASET('CARDDAT') RIDFLD(cardNum)} (COCRDSLC detail read)</td>
 *       <td>{@link JpaRepository#findById(Object) findById(String)}</td></tr>
 *   <tr><td>{@code READ ...} existence probe</td>
 *       <td>{@link JpaRepository#existsById(Object) existsById(String)}</td></tr>
 *   <tr><td>{@code READ ... UPDATE} + {@code REWRITE DATASET('CARDDAT')} (COCRDUPC)</td>
 *       <td>{@link JpaRepository#save(Object) save(Card)} (version-checked)</td></tr>
 *   <tr><td>Bulk load (IDCAMS {@code REPRO} into the cluster)</td>
 *       <td>{@link JpaRepository#saveAll(Iterable) saveAll(Iterable&lt;Card&gt;)}</td></tr>
 *   <tr><td>{@code DELETE DATASET('CARDDAT')}</td>
 *       <td>{@link JpaRepository#delete(Object) delete(Card)} /
 *           {@link JpaRepository#deleteById(Object) deleteById(String)}</td></tr>
 *   <tr><td>Sequential browse ({@code STARTBR}/{@code READNEXT}, batch dump)</td>
 *       <td>{@link JpaRepository#findAll() findAll()}</td></tr>
 *   <tr><td>Cluster record count</td>
 *       <td>{@link JpaRepository#count() count()}</td></tr>
 * </table>
 *
 * <h2>Alternate index {@code CARDAIX} &rarr; secondary query methods (AAP &sect;0.4.2,
 * &sect;0.6.2)</h2>
 * <p>Unlike the other primary-key-only repositories, {@code CARDDAT} carried a
 * second physical access path: the {@code CARDAIX} alternate index keyed on
 * {@code CARD-ACCT-ID} (the 11-byte field at offset 16, {@code PIC 9(11)}). It was
 * defined {@code NONUNIQUEKEY} because one account legitimately owns several cards,
 * so an account-id probe returns <em>zero, one or many</em> rows &mdash; never a
 * single record. AAP &sect;0.4.2 maps this VSAM alternate index onto Spring Data
 * <em>secondary query methods</em> that preserve the alternate-index access pattern;
 * this interface therefore declares two derived queries over the {@link Card}
 * property {@code cardAcctId}. Spring Data parses the {@code findByCardAcctId}
 * method name into a {@code WHERE account_id = ?} clause (the property path
 * {@code cardAcctId} resolves to the {@code account_id} column), exactly
 * reproducing the {@code CARDAIX} key lookup. The corresponding PostgreSQL
 * non-unique B-tree index that backs this lookup is created in the Flyway
 * {@code V2__create_indexes.sql} migration; the index is a performance concern of
 * the schema and does not alter the contract of these methods.</p>
 *
 * <h2>Generic type contract</h2>
 * <p>The repository is typed {@code JpaRepository<Card, String>}. The identifier
 * type is {@link String} &mdash; not a numeric type &mdash; because the primary key
 * {@link Card#getCardNum()} is migrated from {@code CARD-NUM PIC X(16)}, the
 * 16-character Primary Account Number (PAN). It is kept as a {@link String} so that
 * leading characters are preserved exactly and the value matches the keyed VSAM
 * access performed by {@code COCRDSLC} (PostgreSQL {@code VARCHAR(16)}). This is the
 * first alternate-index repository in the persistence layer: its primary key is a
 * {@link String}, while the {@code CARDAIX} alternate key
 * ({@link Card#getCardAcctId()}) is a {@link Long} ({@code BIGINT}, mirroring
 * {@code Account.acctId}) and surfaces through the {@code findByCardAcctId} secondary
 * queries below rather than through {@code findById}.</p>
 *
 * <h2>Read-update concurrency &mdash; {@code @Version} optimistic locking</h2>
 * <p>The online card-update program {@code COCRDUPC} performed an optimistic
 * read-update snapshot comparison: it re-read the card record before its
 * {@code REWRITE} and compared the before/after images, rejecting the update with
 * <q>Record changed by some one else. Please review</q> when a concurrent change was
 * detected. That safeguard is <em>not</em> reproduced on this interface; it lives on
 * the {@link Card} entity as its JPA {@code @Version} column. Because
 * {@link JpaRepository#save(Object)} honors the version automatically, a stale update
 * surfaces as an {@code org.springframework.orm.ObjectOptimisticLockingFailureException}
 * (wrapping the JPA {@code OptimisticLockException}) without any custom method here.
 * Per AAP &sect;0.7.5, {@code @Version} is applied only to {@code Account} and
 * {@code Card}.</p>
 *
 * <h2>Consumers (AAP &sect;0.6.2 access matrix)</h2>
 * <p>{@code CARDDAT} is shared across the online (REST) and batch contexts, so a
 * single repository serves both layers:</p>
 * <ul>
 *   <li>Online &mdash; {@code COCRDLIC} (card list, account-filtered paginated browse
 *       via {@code CARDAIX}) &rarr; {@code findByCardAcctId(Long, Pageable)};
 *       {@code COCRDSLC} (single keyed card detail) &rarr; {@code findById(String)};
 *       {@code COCRDUPC} (card update, keyed read-then-rewrite) &rarr;
 *       {@code findById(String)} then {@code save(Card)}.</li>
 *   <li>Batch &mdash; {@code CBACT02C} sequentially dumps the card file &rarr;
 *       {@code findAll()}.</li>
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
 *   <li><strong>Only alternate-index queries are declared.</strong> Every
 *       primary-key operation against {@code CARDDAT} is inherited from
 *       {@link JpaRepository}; the only methods added here are the two
 *       {@code findByCardAcctId} overloads that reproduce the {@code CARDAIX}
 *       alternate-index access pattern. Per the Minimal Change Clause
 *       (AAP &sect;0.7.1) no {@code @Query} methods, Jakarta Bean Validation or
 *       business logic are added here &mdash; that behaviour belongs to the service
 *       and DTO layers. The page size (7 rows, the {@code COCRDLIC} screen contract)
 *       is chosen by the calling service via the {@link Pageable} it supplies, not
 *       fixed in this interface.</li>
 * </ul>
 *
 * <p><strong>Traceability.</strong> Derived from the frozen COBOL baseline at
 * commit SHA {@code 27d6c6f}. The COBOL/JCL sources are read-only reference
 * material and are never copied into this repository.</p>
 *
 * @see Card
 * @see JpaRepository
 */
public interface CardRepository extends JpaRepository<Card, String> {

    /**
     * Returns every card belonging to the given account.
     *
     * <p>This is the unpaginated form of the {@code CARDAIX} alternate-index lookup
     * (keyed on {@code CARD-ACCT-ID}). Because the legacy alternate index was
     * {@code NONUNIQUEKEY}, a single account may own several cards, so this method
     * returns a (possibly empty) {@link List} rather than a single {@code Card}.
     * Spring Data derives the query from the method name: the property path
     * {@code cardAcctId} on {@link Card} resolves to the {@code account_id} column,
     * producing {@code WHERE account_id = ?}. Useful where the full set of an
     * account's cards is required without page boundaries (for example downstream
     * batch or aggregation paths).</p>
     *
     * @param accountId the owning account identifier ({@code CARD-ACCT-ID},
     *                   {@code PIC 9(11)}); maps to the {@code account_id} column
     * @return all cards whose {@code account_id} equals {@code accountId}; an empty
     *         list if the account owns none (never {@code null})
     */
    List<Card> findByCardAcctId(Long accountId);

    /**
     * Returns a single page of the cards belonging to the given account.
     *
     * <p>This is the paginated form of the {@code CARDAIX} alternate-index lookup and
     * reproduces the online card-list browse of {@code COCRDLIC}, whose 3270 screen
     * displayed {@code OCCURS 7 TIMES} rows with PF7 (page up) / PF8 (page down)
     * navigation. The calling service supplies the page size and offset through the
     * {@link Pageable} argument (for example {@code PageRequest.of(page, 7)} to honor
     * the original 7-rows-per-page contract); the page size is intentionally not
     * fixed here so the screen contract remains a service-layer concern. As with the
     * unpaginated overload, the {@code NONUNIQUEKEY} alternate index means an account
     * may own many cards, hence a {@link Page} of results.</p>
     *
     * @param accountId the owning account identifier ({@code CARD-ACCT-ID},
     *                   {@code PIC 9(11)}); maps to the {@code account_id} column
     * @param pageable  the paging and sorting specification supplied by the caller
     *                  (the {@code COCRDLIC} contract uses a page size of 7)
     * @return the requested page of cards for the account (possibly empty, never
     *         {@code null})
     */
    Page<Card> findByCardAcctId(Long accountId, Pageable pageable);
}

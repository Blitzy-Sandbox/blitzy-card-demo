package com.cardemo.repository;

import com.cardemo.model.entity.CardCrossReference;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA data-access interface for the {@link CardCrossReference} junction
 * record.
 *
 * <p>This repository is the Java 25 / Spring Data JPA replacement for the legacy
 * AWS CardDemo VSAM KSDS dataset {@code CARDXREF} <em>together with</em> its
 * alternate index {@code CXACAIX}. On the mainframe the base cluster and its
 * alternate index were provisioned by {@code app/jcl/XREFFILE.jcl}
 * ({@code DEFINE CLUSTER ... KEYS(16 0) RECORDSIZE(50 50) INDEXED} for the base
 * cluster, then {@code DEFINE ALTERNATEINDEX ... KEYS(11,25) NONUNIQUEKEY UPGRADE}
 * plus {@code DEFINE PATH} and {@code BLDINDEX} for the alternate index). The
 * fixed-length 50-byte record layout was defined by the copybook
 * {@code app/cpy/CVACT03Y.cpy} ({@code 01 CARD-XREF-RECORD}). In the migrated stack
 * the same data lives in the PostgreSQL table mapped by the
 * {@link CardCrossReference} entity (table {@code card_xref}), and every access path
 * the COBOL programs used is served through this interface.</p>
 *
 * <p>The cross-reference is the small but operationally critical <em>junction</em>
 * record that links a card to both its owning account and its owning customer:
 * {@code card }&rarr;{@code  (account, customer)}.</p>
 *
 * <h2>Technology substitution &mdash; VSAM keyed access &rarr; {@code JpaRepository}
 * (Minimal Change Clause, AAP &sect;0.7.1)</h2>
 * <p>The base KSDS exposed record-level operations keyed on the 16-byte cluster key
 * {@code XREF-CARD-NUM} (the 16-character card number / Primary Account Number).
 * Each of those primary-key operations maps directly onto an operation inherited
 * from {@link JpaRepository}, so the primary-key CRUD surface is inherited rather
 * than redeclared:</p>
 * <table border="1">
 *   <caption>VSAM/CICS operation &rarr; inherited Spring Data operation</caption>
 *   <tr><th>Legacy VSAM/CICS operation</th><th>Inherited repository operation</th></tr>
 *   <tr><td>{@code READ DATASET('CARDXREF') RIDFLD(XREF-CARD-NUM)} (resolve one
 *       cross-reference by card number, e.g. {@code COTRN02C} card-key read)</td>
 *       <td>{@link JpaRepository#findById(Object) findById(String)}</td></tr>
 *   <tr><td>{@code READ ...} existence probe</td>
 *       <td>{@link JpaRepository#existsById(Object) existsById(String)}</td></tr>
 *   <tr><td>{@code WRITE} / {@code REWRITE DATASET('CARDXREF')}</td>
 *       <td>{@link JpaRepository#save(Object) save(CardCrossReference)}</td></tr>
 *   <tr><td>Bulk load (IDCAMS {@code REPRO} into the cluster, {@code XREFFILE.jcl}
 *       STEP15)</td>
 *       <td>{@link JpaRepository#saveAll(Iterable) saveAll(Iterable&lt;CardCrossReference&gt;)}</td></tr>
 *   <tr><td>{@code DELETE DATASET('CARDXREF')}</td>
 *       <td>{@link JpaRepository#delete(Object) delete(CardCrossReference)} /
 *           {@link JpaRepository#deleteById(Object) deleteById(String)}</td></tr>
 *   <tr><td>Sequential browse ({@code STARTBR}/{@code READNEXT}, batch dump by
 *       {@code CBACT03C})</td>
 *       <td>{@link JpaRepository#findAll() findAll()}</td></tr>
 *   <tr><td>Cluster record count</td>
 *       <td>{@link JpaRepository#count() count()}</td></tr>
 * </table>
 *
 * <h2>Alternate index {@code CXACAIX} &rarr; secondary query method (AAP &sect;0.4.2,
 * &sect;0.6.2)</h2>
 * <p>Beyond the primary key, {@code CARDXREF} carried a second physical access path:
 * the {@code CXACAIX} alternate index ({@code AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX})
 * keyed on {@code XREF-ACCT-ID} (the 11-byte field at offset 25,
 * {@code PIC 9(11)} &mdash; {@code KEYS(11,25)} in {@code XREFFILE.jcl} STEP20). It
 * was defined {@code NONUNIQUEKEY} because one account legitimately owns several
 * cards, so an account-id probe returns <em>zero, one or many</em> cross-reference
 * rows &mdash; never a single record. AAP &sect;0.4.2 maps this VSAM alternate index
 * onto a Spring Data <em>secondary query method</em> that preserves the
 * alternate-index access pattern; this interface therefore declares one derived
 * query over the {@link CardCrossReference} property {@code xrefAcctId}. Spring Data
 * parses the {@code findByXrefAcctId} method name into a {@code WHERE account_id = ?}
 * clause (the property path {@code xrefAcctId} resolves to the {@code account_id}
 * column), exactly reproducing the {@code CXACAIX} key lookup. The corresponding
 * PostgreSQL non-unique B-tree index that backs this lookup
 * ({@code idx_card_xref_account_id}) is created in the Flyway
 * {@code V2__create_indexes.sql} migration; the index is a performance concern of
 * the schema and does not alter the contract of this method.</p>
 *
 * <h2>Generic type contract</h2>
 * <p>The repository is typed {@code JpaRepository<CardCrossReference, String>}. The
 * identifier type is {@link String} &mdash; not a numeric type &mdash; because the
 * primary key {@link CardCrossReference#getXrefCardNum()} is migrated from
 * {@code XREF-CARD-NUM PIC X(16)}, the 16-character card number (PAN). It is kept as
 * a {@link String} so that leading characters are preserved exactly and the value
 * matches the keyed VSAM access and {@code Card.cardNum} (PostgreSQL
 * {@code VARCHAR(16)}). The {@code CXACAIX} alternate key
 * ({@link CardCrossReference#getXrefAcctId()}) is a {@link Long} ({@code BIGINT},
 * mirroring {@code Account.acctId}) and surfaces through the {@code findByXrefAcctId}
 * secondary query below rather than through {@code findById}.</p>
 *
 * <h2>No optimistic-locking / no transaction method</h2>
 * <p>Per AAP &sect;0.7.5, JPA {@code @Version} optimistic locking is applied
 * <strong>only</strong> to {@code Account} and {@code Card} (the read-update
 * programs {@code COACTUPC} and {@code COCRDUPC}). The cross-reference record is
 * never updated in place through a read-update snapshot comparison, so the
 * {@link CardCrossReference} entity carries no {@code @Version} column and this
 * interface declares no concurrency-specific method.</p>
 *
 * <h2>Consumers (AAP &sect;0.6.2 access matrix)</h2>
 * <p>{@code CARDXREF} is shared across the online (REST) and batch contexts, so a
 * single repository serves both layers:</p>
 * <ul>
 *   <li>Online &mdash; {@code COACTVWC} (account view) reads the cross-reference by
 *       account id via the {@code CXACAIX} path to resolve the card and customer
 *       &rarr; {@code findByXrefAcctId(Long)}; {@code COCRDLIC} (account-filtered
 *       card list) uses the same account-id pivot; {@code COTRN02C} (add
 *       transaction) reads both by card number &rarr; {@code findById(String)} and
 *       by account id &rarr; {@code findByXrefAcctId(Long)}.</li>
 *   <li>Batch &mdash; {@code CBACT03C} sequentially dumps the cross-reference file
 *       &rarr; {@code findAll()}; {@code CBTRN02C} (daily posting) and
 *       {@code CBSTM03A} (statement generation) resolve the account/customer for a
 *       card through this junction.</li>
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
 *   <li><strong>Only the alternate-index query is declared.</strong> Every
 *       primary-key operation against {@code CARDXREF} is inherited from
 *       {@link JpaRepository}; the only method added here is the single
 *       {@code findByXrefAcctId} query that reproduces the {@code CXACAIX}
 *       alternate-index access pattern. Per the Minimal Change Clause
 *       (AAP &sect;0.7.1) no {@code @Query} methods, Jakarta Bean Validation or
 *       business logic are added here &mdash; that behaviour belongs to the service
 *       and DTO layers.</li>
 * </ul>
 *
 * <p><strong>Traceability.</strong> Derived from the frozen COBOL baseline at
 * commit SHA {@code 27d6c6f}. The COBOL/JCL sources are read-only reference
 * material and are never copied into this repository.</p>
 *
 * @see CardCrossReference
 * @see JpaRepository
 */
public interface CardCrossReferenceRepository
        extends JpaRepository<CardCrossReference, String> {

    /**
     * Returns every cross-reference row belonging to the given account.
     *
     * <p>This is the relational replacement for the {@code CXACAIX} alternate-index
     * lookup (keyed on {@code XREF-ACCT-ID}). It reproduces the account&rarr;card(s)
     * pivot performed on the mainframe by {@code COACTVWC}
     * ({@code 9200-GETCARDXREF-BYACCT}, which reads {@code CARD-XREF-RECORD} through
     * the {@code 'CXACAIX'} path and then resolves {@code XREF-CARD-NUM}),
     * {@code COCRDLIC} (account-filtered card list) and {@code COTRN02C}
     * ({@code READ-CXACAIX-FILE RIDFLD(XREF-ACCT-ID)}).</p>
     *
     * <p>Because the legacy alternate index was {@code NONUNIQUEKEY}, a single
     * account may own several cards, so this method returns a (possibly empty)
     * {@link List} rather than a single {@code CardCrossReference} &mdash; never a
     * scalar and never {@code null}. Spring Data derives the query from the method
     * name: the property path {@code xrefAcctId} on {@link CardCrossReference}
     * resolves to the {@code account_id} column, producing
     * {@code WHERE account_id = ?}. The lookup is index-served by the non-unique
     * B-tree index {@code idx_card_xref_account_id} created in the Flyway
     * {@code V2__create_indexes.sql} migration.</p>
     *
     * @param accountId the owning account identifier ({@code XREF-ACCT-ID},
     *                   {@code PIC 9(11)}); maps to the {@code account_id} column
     * @return all cross-reference rows whose {@code account_id} equals
     *         {@code accountId}; an empty list if the account owns none (never
     *         {@code null})
     */
    List<CardCrossReference> findByXrefAcctId(Long accountId);
}

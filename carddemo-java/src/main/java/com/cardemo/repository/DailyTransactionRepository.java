package com.cardemo.repository;

import com.cardemo.model.entity.DailyTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA data-access interface for the {@link DailyTransaction} staging
 * aggregate.
 *
 * <p>This repository is the Java 25 / Spring Data JPA replacement for the legacy
 * AWS CardDemo <strong>{@code DALYTRAN} PS (physical sequential) staging
 * dataset</strong> ({@code AWS.M2.CARDDEMO.DALYTRAN.PS}). Unlike the other ten
 * data entities in this migration &mdash; each of which originates from a VSAM
 * KSDS cluster &mdash; {@code DALYTRAN} is the <em>only</em> entity backed by a
 * flat <strong>sequential file rather than a keyed VSAM cluster</strong>
 * (AAP &sect;0.6.2). Its fixed-length 350-byte record layout was defined by the
 * copybook {@code app/cpy/CVTRA06Y.cpy} ({@code 01 DALYTRAN-RECORD}, a near-clone
 * of the {@code TRANSACT} layout). In the migrated stack the same data lives in
 * the PostgreSQL {@code daily_transactions} table mapped by the
 * {@link DailyTransaction} entity, and every access path is served through this
 * interface.</p>
 *
 * <h2>Role in the daily posting pipeline</h2>
 * <p>{@code DALYTRAN} is the daily, pre-posting transaction feed and is the
 * <strong>input</strong> to the {@code POSTTRAN} batch job
 * ({@code app/jcl/POSTTRAN.jcl}). On the mainframe two batch programs reach it,
 * both declaring {@code ORGANIZATION IS SEQUENTIAL} / {@code ACCESS MODE IS
 * SEQUENTIAL}:</p>
 * <ul>
 *   <li>{@code CBTRN01C} &mdash; the daily-transaction reader/validator: opens the
 *       file {@code INPUT} and reads it <strong>front-to-back</strong>
 *       ({@code READ DALYTRAN-FILE}, looping until the {@code AT END} file-status
 *       {@code '10'}).</li>
 *   <li>{@code CBTRN02C} &mdash; the {@code POSTTRAN} posting step
 *       ({@code STEP15 EXEC PGM=CBTRN02C}): reads the same file sequentially and
 *       <strong>posts</strong> each accepted row into the permanent
 *       {@code TRANSACT} ledger (rejected rows are routed to the daily-reject
 *       sink), while updating the transaction-category balances.</li>
 * </ul>
 *
 * <h2>Technology substitution &mdash; sequential PS file &rarr; {@code JpaRepository}
 * (Minimal Change Clause, AAP &sect;0.7.1)</h2>
 * <p>A QSAM/sequential file exposes only two operations: a forward sequential
 * browse (open input, read next until end-of-file) and a sequential load (write
 * the staged rows out). It has <strong>no record key</strong> and therefore no
 * keyed read, alternate index or path access. Both sequential operations map
 * directly onto operations inherited from {@link JpaRepository}, so this interface
 * declares <strong>no</strong> additional methods of its own:</p>
 * <table border="1">
 *   <caption>Legacy sequential-file operation &rarr; inherited Spring Data operation</caption>
 *   <tr><th>Legacy QSAM/sequential operation</th><th>Inherited repository operation</th></tr>
 *   <tr><td>{@code OPEN INPUT} + {@code READ DALYTRAN-FILE} ... {@code AT END}
 *       (front-to-back browse, {@code CBTRN01C} / {@code CBTRN02C})</td>
 *       <td>{@link JpaRepository#findAll() findAll()} &mdash; with the reader-supplied
 *           {@code findAll(Sort)} / {@code findAll(Pageable)} overloads when an
 *           ordered or chunked stream is required</td></tr>
 *   <tr><td>Sequential load of the staged feed (write the daily rows)</td>
 *       <td>{@link JpaRepository#saveAll(Iterable) saveAll(Iterable&lt;DailyTransaction&gt;)} /
 *           {@link JpaRepository#save(Object) save(DailyTransaction)}</td></tr>
 *   <tr><td>Staging-feed record count</td>
 *       <td>{@link JpaRepository#count() count()}</td></tr>
 *   <tr><td>Purge the staging feed after a posting run</td>
 *       <td>{@link JpaRepository#deleteAll() deleteAll()} /
 *           {@link JpaRepository#deleteAllInBatch() deleteAllInBatch()}</td></tr>
 * </table>
 * <p>Because the original access is sequential, any ordering or page window the
 * Spring Batch reader needs (for example a deterministic chunk-by-chunk stream
 * ordered by {@code dalytranId}) is supplied by the <strong>reader component</strong>
 * through the {@code Sort} / {@code Pageable} argument of the inherited
 * {@code findAll} overloads &mdash; it is not, and must not be, fixed on the
 * repository. This keeps the staging interface a thin, behaviour-free data-access
 * boundary.</p>
 *
 * <h2>Generic type contract</h2>
 * <p>The repository is typed {@code JpaRepository<DailyTransaction, String>}. The
 * identifier type is {@link String} &mdash; not a numeric type &mdash; because the
 * primary key {@link DailyTransaction#getDalytranId()} is migrated from
 * {@code DALYTRAN-ID PIC X(16)}, the 16-character transaction identifier that is
 * the file's natural record id (the PS file carries no VSAM key). It is kept as a
 * {@link String} so the exact 16-character external format is preserved
 * (PostgreSQL {@code VARCHAR(16)}), mirroring the identical key treatment applied
 * to the sibling {@code Transaction} entity / {@code TransactionRepository}.</p>
 *
 * <h2>No optimistic locking / no transaction method</h2>
 * <p>Per AAP &sect;0.7.5, JPA {@code @Version} optimistic locking is applied
 * <strong>only</strong> to {@code Account} and {@code Card} (the read-update
 * programs {@code COACTUPC} and {@code COCRDUPC}). A daily-transaction row is
 * inserted by the feed and read once by the posting job; it is never concurrently
 * rewritten through a read-update snapshot comparison, so the
 * {@link DailyTransaction} entity carries no {@code @Version} column and this
 * interface declares no concurrency-specific method. The transactional boundary
 * that posts a daily row into {@code transactions} is realised at the
 * {@code @Transactional} service / batch-step boundary, not here.</p>
 *
 * <h2>Consumers (AAP &sect;0.6.2 access matrix)</h2>
 * <p>{@code DALYTRAN} is a <strong>batch-only</strong> staging dataset; no online
 * (REST) program reaches it. Its consumers are the batch components migrated from
 * {@code CBTRN01C} and {@code CBTRN02C}:</p>
 * <ul>
 *   <li>The daily-transaction <em>reader</em> (migrated from {@code CBTRN01C})
 *       streams the staged rows &rarr; {@code findAll(...)} with a reader-supplied
 *       {@code Sort} / {@code Pageable}.</li>
 *   <li>The daily-transaction <em>posting</em> job (migrated from {@code CBTRN02C},
 *       {@code POSTTRAN} STEP15) consumes the same rows and posts the accepted ones
 *       into {@code transactions}; the staging feed itself is loaded via
 *       {@code saveAll(...)}.</li>
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
 *       programs perform against {@code DALYTRAN} is a forward sequential browse or
 *       a sequential load, and both are already provided by {@link JpaRepository}.
 *       Per the Minimal Change Clause (AAP &sect;0.7.1) no derived queries,
 *       {@code @Query} methods, Jakarta Bean Validation or business logic are added
 *       here &mdash; that behaviour belongs to the batch reader/processor/writer and
 *       service layers.</li>
 * </ul>
 *
 * <p><strong>Traceability.</strong> Derived from the frozen COBOL baseline at
 * commit SHA {@code 27d6c6f}. The COBOL/JCL sources are read-only reference
 * material and are never copied into this repository.</p>
 *
 * @see DailyTransaction
 * @see JpaRepository
 */
public interface DailyTransactionRepository extends JpaRepository<DailyTransaction, String> {
    // Intentionally empty: DALYTRAN is a sequential PS staging file (not a VSAM
    // KSDS) reached only by a forward sequential browse (CBTRN01C reader,
    // CBTRN02C posting) and a sequential load. Both map to operations inherited
    // from JpaRepository (findAll / findAll(Sort|Pageable) / saveAll / count /
    // deleteAll), so no custom query method is declared. The Spring Batch reader
    // supplies any Sort/Pageable to the inherited findAll (Minimal Change Clause,
    // AAP §0.7.1).
}

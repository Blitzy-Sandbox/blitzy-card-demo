package com.cardemo.repository;

import com.cardemo.model.entity.Transaction;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA data-access interface for the {@link Transaction} aggregate.
 *
 * <p>This repository is the Java 25 / Spring Data JPA replacement for the legacy
 * AWS CardDemo VSAM KSDS dataset {@code TRANSACT} <em>together with</em> its
 * alternate index and path. On the mainframe the base cluster, its alternate
 * index and the path were provisioned by {@code app/jcl/TRANFILE.jcl}
 * ({@code DEFINE CLUSTER ... KEYS(16 0) INDEXED} for the base cluster keyed on
 * the 16-byte {@code TRAN-ID}, then {@code DEFINE ALTERNATEINDEX} keyed on
 * {@code TRAN-PROC-TS} plus {@code DEFINE PATH} and {@code BLDINDEX}). The
 * fixed-length 350-byte record layout was defined by the copybook
 * {@code app/cpy/CVTRA05Y.cpy} ({@code 01 TRAN-RECORD}). In the migrated stack
 * the same data lives in the PostgreSQL {@code transaction} table mapped by the
 * {@link Transaction} entity, and every access path the online and batch
 * programs used is served through this interface.</p>
 *
 * <p>{@code TRANSACT} is the highest-traffic record in the system and this is the
 * <strong>most query-rich repository</strong> in the persistence layer: it must
 * reproduce four distinct legacy VSAM access paths in addition to the inherited
 * primary-key CRUD surface.</p>
 *
 * <h2>Technology substitution &mdash; VSAM keyed access &rarr; {@code JpaRepository}
 * (Minimal Change Clause, AAP &sect;0.7.1)</h2>
 * <p>The base KSDS exposed record-level operations keyed on the 16-byte cluster
 * key {@code TRAN-ID} (the unique transaction identifier). Each of those
 * primary-key operations maps directly onto an operation inherited from
 * {@link JpaRepository}, so the primary-key CRUD surface is inherited rather than
 * redeclared:</p>
 * <table border="1">
 *   <caption>VSAM/CICS operation &rarr; inherited Spring Data operation</caption>
 *   <tr><th>Legacy VSAM/CICS operation</th><th>Inherited repository operation</th></tr>
 *   <tr><td>{@code READ DATASET('TRANSACT') RIDFLD(TRAN-ID)} (single keyed detail
 *       read, e.g. {@code COTRN01C})</td>
 *       <td>{@link JpaRepository#findById(Object) findById(String)}</td></tr>
 *   <tr><td>{@code READ ...} existence probe</td>
 *       <td>{@link JpaRepository#existsById(Object) existsById(String)}</td></tr>
 *   <tr><td>{@code WRITE DATASET('TRANSACT')} (add transaction, e.g.
 *       {@code COTRN02C} / {@code COBIL00C} / {@code CBTRN02C})</td>
 *       <td>{@link JpaRepository#save(Object) save(Transaction)}</td></tr>
 *   <tr><td>Bulk load (IDCAMS {@code REPRO} / batch combine into the cluster)</td>
 *       <td>{@link JpaRepository#saveAll(Iterable) saveAll(Iterable&lt;Transaction&gt;)}</td></tr>
 *   <tr><td>{@code DELETE DATASET('TRANSACT')}</td>
 *       <td>{@link JpaRepository#delete(Object) delete(Transaction)} /
 *           {@link JpaRepository#deleteById(Object) deleteById(String)}</td></tr>
 *   <tr><td>Full sequential browse ({@code STARTBR}/{@code READNEXT}, batch dump)</td>
 *       <td>{@link JpaRepository#findAll() findAll()}</td></tr>
 *   <tr><td>Cluster record count</td>
 *       <td>{@link JpaRepository#count() count()}</td></tr>
 * </table>
 *
 * <h2>Four legacy access paths &rarr; four custom query methods (AAP &sect;0.4.1,
 * &sect;0.4.2, &sect;0.6.2)</h2>
 * <p>Beyond keyed CRUD, the COBOL estate reached {@code TRANSACT} through four
 * distinct, non-primary-key access paths. Each is reproduced by exactly one
 * method declared on this interface; none of them embeds business logic (the
 * Minimal Change Clause confines transformation, increment and formatting rules
 * to the service layer):</p>
 * <table border="1">
 *   <caption>Legacy access path &rarr; repository method</caption>
 *   <tr><th>Legacy access path (source)</th><th>Repository method</th></tr>
 *   <tr><td>{@code STARTBR}/{@code READNEXT} forward browse from a
 *       greater-than-or-equal {@code TRAN-ID} position, PF7/PF8 paged
 *       (online {@code COTRN00C} transaction list)</td>
 *       <td>{@link #findByTranIdGreaterThanEqual(String, Pageable)}</td></tr>
 *   <tr><td>DFSORT {@code INCLUDE COND} on {@code TRAN-PROC-DT} (the first ten
 *       characters of {@code TRAN-PROC-TS}) {@code GE}/{@code LE} a date window,
 *       {@code SORT FIELDS=(TRAN-CARD-NUM,A)} (batch {@code CBTRN03C} /
 *       {@code app/jcl/TRANREPT.jcl} STEP05R report)</td>
 *       <td>{@link #findByProcessingDateRange(String, String)}</td></tr>
 *   <tr><td>{@code MOVE HIGH-VALUES TO TRAN-ID} &rarr; {@code STARTBR} &rarr;
 *       {@code READPREV} to position on the highest key (online {@code COTRN02C}
 *       add-transaction and {@code COBIL00C} bill-pay id generation)</td>
 *       <td>{@link #findMaxTransactionId()}</td></tr>
 *   <tr><td>Gather every transaction belonging to one card number (batch
 *       {@code CBSTM03A} statement generation)</td>
 *       <td>{@link #findByTranCardNum(String)}</td></tr>
 * </table>
 *
 * <h2>Generic type contract</h2>
 * <p>The repository is typed {@code JpaRepository<Transaction, String>}. The
 * identifier type is {@link String} &mdash; not a numeric type &mdash; because
 * the primary key {@link Transaction#getTranId()} is migrated from
 * {@code TRAN-ID PIC X(16)}, the 16-character transaction identifier. It is kept
 * as a {@link String} so the exact 16-character external format (a zero-padded
 * numeric key) is preserved and matches the keyed VSAM access (PostgreSQL
 * {@code VARCHAR(16)}).</p>
 *
 * <h2>No optimistic locking / no transaction method</h2>
 * <p>Per AAP &sect;0.7.5, JPA {@code @Version} optimistic locking is applied
 * <strong>only</strong> to {@code Account} and {@code Card} (the read-update
 * programs {@code COACTUPC} and {@code COCRDUPC}). A transaction record is
 * inserted and read, never rewritten through a read-update snapshot comparison,
 * so the {@link Transaction} entity carries no {@code @Version} column and this
 * interface declares no concurrency-specific method. The sole
 * {@code SYNCPOINT ROLLBACK} of the estate (the dual update in {@code COACTUPC})
 * does not touch {@code TRANSACT}; transactional boundaries that span a balance
 * update and a transaction insert (for example {@code COBIL00C} bill payment)
 * are realised at the {@code @Transactional} service boundary, not here.</p>
 *
 * <h2>Consumers (AAP &sect;0.6.2 access matrix)</h2>
 * <p>{@code TRANSACT} is shared across the online (REST) and batch contexts, so a
 * single repository serves both layers:</p>
 * <ul>
 *   <li>Online &mdash; {@code COTRN00C} (paged transaction list) &rarr;
 *       {@link #findByTranIdGreaterThanEqual(String, Pageable)}; {@code COTRN01C}
 *       (single detail) &rarr; {@code findById(String)}; {@code COTRN02C} (add
 *       transaction) and {@code COBIL00C} (bill payment) &rarr;
 *       {@link #findMaxTransactionId()} then {@code save(Transaction)}.</li>
 *   <li>Batch &mdash; {@code CBTRN02C} (daily posting) &rarr; {@code save}/{@code saveAll};
 *       {@code CBTRN03C} / {@code TRANREPT.jcl} (date-filtered report) &rarr;
 *       {@link #findByProcessingDateRange(String, String)}; {@code CBSTM03A}
 *       (statement generation) &rarr; {@link #findByTranCardNum(String)}.</li>
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
 *   <li><strong>Business logic lives in the service layer.</strong> Per the
 *       Minimal Change Clause (AAP &sect;0.7.1) this interface carries no Jakarta
 *       Bean Validation and no business logic. In particular
 *       {@link #findMaxTransactionId()} returns only the current maximum key; the
 *       {@code ADD 1} increment and 16-character zero-padding that {@code COTRN02C}
 *       and {@code COBIL00C} perform are the responsibility of
 *       {@code TransactionAddService}/{@code BillPaymentService}, and the page size
 *       of {@link #findByTranIdGreaterThanEqual(String, Pageable)} is chosen by the
 *       calling service through its {@link Pageable} argument.</li>
 * </ul>
 *
 * <p><strong>Traceability.</strong> Derived from the frozen COBOL baseline at
 * commit SHA {@code 27d6c6f}. The COBOL/JCL sources are read-only reference
 * material and are never copied into this repository.</p>
 *
 * @see Transaction
 * @see JpaRepository
 */
public interface TransactionRepository extends JpaRepository<Transaction, String> {

    /**
     * Returns one page of transactions whose identifier is greater than or equal
     * to the supplied starting key, ordered and paged by the caller.
     *
     * <p>This is the relational replacement for the forward browse performed by
     * the online transaction-list program {@code COTRN00C}. On the mainframe that
     * program positioned the {@code TRANSACT} browse with
     * {@code EXEC CICS STARTBR RIDFLD(TRAN-ID)} (greater-than-or-equal
     * positioning) and then issued {@code READNEXT} to fill a 3270 screen array,
     * with PF8 paging forward and PF7 paging backward. Spring Data derives the
     * {@code WHERE transaction_id &gt;= ?} predicate from the method name (the
     * property path {@code tranId} resolves to the {@code transaction_id}
     * primary-key column), exactly reproducing the {@code STARTBR} GTEQ
     * positioning; the page window (offset and size) and any additional ordering
     * are supplied by the caller's {@link Pageable}.</p>
     *
     * <p>The original screen displayed a fixed number of rows per page with
     * PF7/PF8 navigation; that page size is intentionally <strong>not</strong>
     * fixed here so the screen contract remains a service-layer concern (the
     * calling service supplies, for example, {@code PageRequest.of(page, size)}).</p>
     *
     * @param startId  the inclusive lower-bound transaction id to browse from
     *                 ({@code TRAN-ID}); maps to the {@code transaction_id} column
     * @param pageable the paging (and optional sorting) specification supplied by
     *                 the caller
     * @return the requested page of transactions with id &ge; {@code startId}
     *         (possibly empty, never {@code null})
     */
    Page<Transaction> findByTranIdGreaterThanEqual(String startId, Pageable pageable);

    /**
     * Returns every transaction whose processing date falls within the inclusive
     * {@code [startDate, endDate]} window, ordered ascending by card number.
     *
     * <p>This is the relational replacement for the date-filtered transaction
     * report driven by the batch program {@code CBTRN03C} and the DFSORT step of
     * {@code app/jcl/TRANREPT.jcl} (STEP05R). The legacy logic filtered on the
     * <em>date portion</em> of the processed timestamp &mdash; in COBOL the
     * reference modification {@code TRAN-PROC-TS (1:10)}, i.e. the first ten
     * characters {@code YYYY-MM-DD} of the 26-character {@code TRAN-PROC-TS}
     * field &mdash; with the inclusive condition
     * {@code TRAN-PROC-TS (1:10) >= WS-START-DATE AND TRAN-PROC-TS (1:10) <= WS-END-DATE},
     * and ordered the output by {@code SORT FIELDS=(TRAN-CARD-NUM,A)} (card number
     * ascending). The query below preserves all three traits exactly: the
     * first-ten-character date extraction, the inclusive {@code >=}/{@code <=}
     * bounds, and the {@code ORDER BY t.tranCardNum} sort (AAP &sect;0.7.4 control
     * flow, &sect;0.7.6 batch fidelity).</p>
     *
     * <p><strong>Technology-substitution note (Minimal Change Clause, AAP
     * &sect;0.7.1).</strong> The {@link Transaction} entity maps
     * {@code TRAN-PROC-TS} to a {@link java.time.LocalDateTime}
     * ({@code processed_timestamp TIMESTAMP} in the {@code V1} schema), not to a
     * raw character string. To honour both the original DFSORT contract (compare
     * the first ten characters of the rendered timestamp) and the
     * {@code YYYY-MM-DD} {@link String} parameter contract, the JPQL first casts
     * the timestamp to its text form ({@code CAST(... AS string)}) and then takes
     * {@code SUBSTRING(..., 1, 10)} &mdash; the JPQL analogue of the COBOL
     * {@code (1:10)} reference modification. On PostgreSQL a {@code TIMESTAMP}
     * renders as {@code YYYY-MM-DD HH:MM:SS[.ffffff]}, so the first ten characters
     * are the ISO date and a lexicographic comparison is equivalent to a
     * chronological one for this fixed-width format. This is the minimal change
     * required to reconcile the prescribed query with the actual entity type.</p>
     *
     * @param startDate the inclusive start of the processing-date window, a
     *                  {@code YYYY-MM-DD} string (legacy {@code WS-START-DATE},
     *                  {@code PIC X(10)})
     * @param endDate   the inclusive end of the processing-date window, a
     *                  {@code YYYY-MM-DD} string (legacy {@code WS-END-DATE},
     *                  {@code PIC X(10)})
     * @return all matching transactions ordered ascending by card number
     *         (possibly empty, never {@code null})
     */
    @Query("SELECT t FROM Transaction t "
            + "WHERE SUBSTRING(CAST(t.tranProcTs AS string), 1, 10) >= :startDate "
            + "AND SUBSTRING(CAST(t.tranProcTs AS string), 1, 10) <= :endDate "
            + "ORDER BY t.tranCardNum")
    List<Transaction> findByProcessingDateRange(
            @Param("startDate") String startDate, @Param("endDate") String endDate);

    /**
     * Returns the current maximum transaction identifier, or an empty
     * {@link Optional} when the table holds no rows.
     *
     * <p>This is the relational replacement for the highest-key discovery that the
     * online programs {@code COTRN02C} (add transaction) and {@code COBIL00C}
     * (bill payment) performed before generating a new id. On the mainframe each
     * program issued {@code MOVE HIGH-VALUES TO TRAN-ID}, then
     * {@code EXEC CICS STARTBR} followed by {@code READPREV} to position on the
     * highest existing key and read it back (treating an empty file as zero).
     * Because {@code TRAN-ID} is a 16-character zero-padded numeric key, its
     * lexicographic maximum equals its numeric maximum, so a single
     * {@code SELECT MAX(t.tranId)} reproduces the {@code HIGH-VALUES}/{@code READPREV}
     * positioning precisely.</p>
     *
     * <p><strong>No business logic here (Minimal Change Clause, AAP &sect;0.7.1).</strong>
     * This method returns only the current maximum. The subsequent
     * {@code ADD 1} increment and re-padding to a 16-character zero-filled string
     * (legacy {@code COTRN02C} L444-451 and {@code COBIL00C}) are deliberately left
     * to the service layer ({@code TransactionAddService}/{@code BillPaymentService});
     * an empty table surfaces as {@link Optional#empty()}, which the service maps to
     * the COBOL "start from zero" behaviour.</p>
     *
     * @return an {@link Optional} containing the maximum {@code TRAN-ID} present in
     *         the table, or {@link Optional#empty()} if the table is empty
     */
    @Query("SELECT MAX(t.tranId) FROM Transaction t")
    Optional<String> findMaxTransactionId();

    /**
     * Returns every transaction recorded against the given card number.
     *
     * <p>This is the relational replacement for the per-card transaction gather
     * performed by the batch statement-generation program {@code CBSTM03A}, which
     * walks the cross-reference to obtain a card number and then collects that
     * card's transactions to build its statement. Spring Data derives the
     * {@code WHERE card_number = ?} predicate from the method name: the property
     * path {@code tranCardNum} on {@link Transaction} resolves to the
     * {@code card_number} column.</p>
     *
     * <p>Note that the physical {@code TRANSACT} alternate index was keyed on
     * {@code TRAN-PROC-TS} rather than on the card number; nevertheless a
     * card-scoped gather is a valid logical access path, and any supporting
     * database index over {@code card_number} is the concern of the schema (Flyway)
     * layer, not of this contract.</p>
     *
     * @param cardNumber the 16-character card number ({@code TRAN-CARD-NUM},
     *                   {@code PIC X(16)}); maps to the {@code card_number} column
     * @return all transactions whose {@code card_number} equals {@code cardNumber};
     *         an empty list if the card has none (never {@code null})
     */
    List<Transaction> findByTranCardNum(String cardNumber);
}

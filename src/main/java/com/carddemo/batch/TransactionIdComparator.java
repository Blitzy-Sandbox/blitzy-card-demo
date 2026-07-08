package com.carddemo.batch;

import com.carddemo.entity.Transaction;
import java.io.Serializable;
import java.util.Comparator;

/**
 * Explicit {@link Comparator} encoding of the DFSORT sort key used by the legacy
 * {@code COMBTRAN} batch job, applied to migrated {@link Transaction} rows.
 *
 * <p><strong>COBOL/DFSORT lineage (reference-only, source SHA {@code 27d6c6f}).</strong>
 * This comparator is the one-to-one Java translation of the sort step declared in
 * {@code app/jcl/COMBTRAN.jcl}:</p>
 *
 * <pre>
 *   //SYMNAMES DD *
 *   TRAN-ID,1,16,CH
 *   //SYSIN    DD *
 *    SORT FIELDS=(TRAN-ID,A)
 * </pre>
 *
 * <p>The {@code STEP05R EXEC PGM=SORT} step concatenates the current transaction
 * backup ({@code AWS.M2.CARDDEMO.TRANSACT.BKUP}) and the system-generated
 * transactions ({@code AWS.M2.CARDDEMO.SYSTRAN}) and orders the combined stream so
 * that the following {@code STEP10 IDCAMS REPRO} can load it into the
 * {@code TRANSACT} VSAM KSDS. The sort key is therefore:</p>
 *
 * <ul>
 *   <li><strong>Field:</strong> {@code TRAN-ID} — the 16-character transaction id
 *       ({@code TRAN-RECORD} / copybook {@code CVTRA05Y}), mapped here to
 *       {@link Transaction#getTranId()}.</li>
 *   <li><strong>Positions:</strong> bytes 1–16 (the entire fixed-width key).</li>
 *   <li><strong>Format:</strong> {@code CH} (character) — a left-to-right, byte-wise
 *       comparison of the fixed-width field, faithfully reproduced by
 *       {@link String#compareTo(String)} on the stored 16-character
 *       (zero/space-padded) id.</li>
 *   <li><strong>Direction:</strong> {@code A} — ascending.</li>
 * </ul>
 *
 * <p><strong>Migration mapping.</strong> This class realizes the AAP "Comparator
 * strategy" design pattern (§0.4.3) and the batch-pipeline rule (§0.8.5): DFSORT /
 * MERGE keys become {@link java.util.Comparator} implementations that preserve key
 * order, direction, and duplicate handling. It also serves as the traceable anchor
 * for the {@code COMBTRAN} sort in the bidirectional traceability matrix (§0.7.3).</p>
 *
 * <p><strong>Duplicate handling.</strong> DFSORT's default equal-key ordering is
 * <em>non-stable</em>. In CardDemo, however, {@code TRAN-ID} is the unique primary
 * key of the {@code TRANSACT} KSDS, so two records with equal keys are not expected
 * in a well-formed input. This comparator implements a <em>total order</em> that
 * returns {@code 0} only when the ids are equal; when it is used to sort a stream
 * that could contain equal keys, callers should rely on a <em>stable</em> sort (for
 * example {@link java.util.List#sort(Comparator)} / {@code Collections.sort}, both of
 * which are stable) to obtain deterministic ordering of any duplicates.</p>
 *
 * <p><strong>Null handling.</strong> A {@code null} {@code tranId} sorts <em>last</em>
 * (via {@link Comparator#nullsLast(Comparator)}), so a malformed row with a missing
 * id can never raise a {@link NullPointerException} during the sort.</p>
 *
 * <p><strong>Usage note for downstream authors.</strong> The idiomatic Spring Batch
 * equivalent of a pre-sorted DFSORT input is to have the reader return items already
 * ordered by {@code tranId} ascending — for example a {@code RepositoryItemReader}
 * configured with {@code Sort.by(Sort.Direction.ASC, "tranId")}, which pushes the
 * {@code ORDER BY} into the database. Prefer that approach; do <em>not</em> load the
 * whole {@code transaction} table into memory purely to sort it. This comparator is
 * provided for explicit in-memory sorts and merges (and to keep the DFSORT key
 * semantics visible and traceable).</p>
 *
 * <p><strong>Thread-safety.</strong> The class is stateless; the single shared
 * {@link #BY_TRAN_ID} delegate is immutable and safe for concurrent use, and each
 * {@link #compare(Transaction, Transaction)} call is allocation-free (it reuses the
 * pre-built delegate rather than constructing a comparator chain per invocation).</p>
 *
 * <p>{@link Serializable} is implemented (with an explicit {@code serialVersionUID})
 * because Spring Batch may serialize comparators that are placed into a step or job
 * {@code ExecutionContext}; declaring it avoids serialization warnings and keeps the
 * type usable in that context. The type carries no instance state, so serialization
 * is trivial.</p>
 *
 * @see Transaction#getTranId()
 * @see java.util.Comparator#comparing(java.util.function.Function, Comparator)
 */
public final class TransactionIdComparator implements Comparator<Transaction>, Serializable {

    /**
     * Serialization version identifier. Present because the class implements
     * {@link Serializable} and Spring Batch may persist comparators into an
     * execution context; a fixed value keeps deserialization stable and satisfies
     * the {@code -Xlint:serial} zero-warning build gate (Gate 2).
     */
    private static final long serialVersionUID = 1L;

    /**
     * Reusable, immutable comparator implementing the {@code COMBTRAN}
     * {@code SORT FIELDS=(TRAN-ID,A)} key: ascending natural (byte-wise / character)
     * order of the 16-character {@link Transaction#getTranId()} value, with
     * {@code null} ids ordered last.
     *
     * <p>Exposed as a public constant so callers can pass the key directly to
     * stream, list, or Spring Batch APIs (for example
     * {@code list.sort(TransactionIdComparator.BY_TRAN_ID)}) without allocating a new
     * comparator, and so {@link #compare(Transaction, Transaction)} can delegate to a
     * single shared instance.</p>
     */
    public static final Comparator<Transaction> BY_TRAN_ID =
            Comparator.comparing(Transaction::getTranId,
                    Comparator.nullsLast(Comparator.naturalOrder()));

    /**
     * Creates a comparator instance. Instances are stateless and interchangeable;
     * callers that only need the key may prefer the shared {@link #BY_TRAN_ID}
     * constant. A public no-argument constructor is provided so the type can be
     * instantiated directly or by a framework (for example Spring Batch).
     */
    public TransactionIdComparator() {
        // No state to initialize; ordering is fully defined by BY_TRAN_ID.
    }

    /**
     * Compares two transactions by their {@code tranId} in ascending character order,
     * reproducing the {@code COMBTRAN} DFSORT key {@code SORT FIELDS=(TRAN-ID,A)}
     * ({@code TRAN-ID}, positions 1–16, format {@code CH}, ascending).
     *
     * <p>Delegates to the shared {@link #BY_TRAN_ID} comparator, so the call performs
     * no per-invocation allocation. A {@code null} id on either argument sorts last.</p>
     *
     * @param a the first transaction to compare (its {@code tranId} may be {@code null})
     * @param b the second transaction to compare (its {@code tranId} may be {@code null})
     * @return a negative integer, zero, or a positive integer as {@code a}'s
     *         {@code tranId} is less than, equal to, or greater than {@code b}'s
     *         {@code tranId}; {@code null} ids are treated as greater than any
     *         non-{@code null} id
     */
    @Override
    public int compare(Transaction a, Transaction b) {
        return BY_TRAN_ID.compare(a, b);
    }
}

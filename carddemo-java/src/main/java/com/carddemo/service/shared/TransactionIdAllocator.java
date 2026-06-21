package com.carddemo.service.shared;

import com.carddemo.repository.TransactionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Allocates the next sequential, fixed-width transaction identifier in a
 * concurrency-safe manner. Shared collaborator for the two online write paths
 * that create transactions: {@code TransactionAddService} (COTRN02C) and
 * {@code BillPaymentService} (COBIL00C).
 *
 * <p>Behavioral translation of the COBOL "browse to end of file and add one"
 * id derivation (COTRN02C {@code STARTBR}/{@code READPREV}/{@code ENDBR} of
 * {@code HIGH-VALUES} followed by {@code ADD 1}); see {@code app/cbl/COTRN02C.cbl}
 * at source commit {@code 27d6c6f} (REFERENCE only, not embedded). The highest
 * existing id is read and incremented, then zero-padded to sixteen digits.</p>
 *
 * <p>Under the single-threaded CICS pseudo-conversational model the read-and-add
 * was inherently serial. The stateless, horizontally scalable Java target admits
 * concurrent callers, so the read-maximum-then-insert sequence is guarded with a
 * PostgreSQL transaction-scoped advisory lock ({@code pg_advisory_xact_lock}).
 * Acquiring the lock before reading the maximum serializes id allocation across
 * all concurrent callers: the lock is held until the surrounding transaction
 * commits (or rolls back), so a waiting caller only reads the maximum after the
 * preceding caller's insert has committed, and therefore observes it. The result
 * is a unique, monotonically increasing id for every concurrent request without
 * primary-key collisions.</p>
 */
@Component
public class TransactionIdAllocator {

    /** Fixed width of the zero-padded numeric transaction identifier (sixteen digits). */
    private static final String TRAN_ID_FORMAT = "%016d";

    /**
     * Application-defined advisory-lock key namespacing transaction-id allocation. Every caller
     * uses the same key so that allocation is mutually exclusive across the application.
     */
    private static final long TRANSACTION_ID_LOCK_KEY = 7_210_001L;

    /**
     * Acquires the transaction-scoped advisory lock. The lock function is wrapped in a derived table
     * and the outer query selects a literal so the statement returns a concrete integer column (rather
     * than the {@code void} the lock function yields), while the {@code FROM}-clause subquery guarantees
     * the volatile lock function is evaluated exactly once.
     */
    private static final String ACQUIRE_ADVISORY_LOCK_SQL =
            "SELECT 1 FROM (SELECT pg_advisory_xact_lock(:lockKey)) AS transaction_id_lock";

    @PersistenceContext
    private EntityManager entityManager;

    private final TransactionRepository transactionRepository;

    /**
     * Creates the allocator.
     *
     * @param transactionRepository repository providing the maximum-identifier lookup
     */
    public TransactionIdAllocator(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    /**
     * Allocates the next transaction identifier within the caller's active transaction.
     *
     * <p>Declared {@link Propagation#MANDATORY}: the advisory lock is transaction-scoped, so allocation
     * is only correct when invoked inside the same transaction that performs the subsequent insert. Both
     * online callers annotate their write operation {@code @Transactional}, which this method joins.</p>
     *
     * @return the next sixteen-digit, zero-padded transaction identifier (the first id is
     *         {@code "0000000000000001"} when no transactions exist)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String allocateNextTransactionId() {
        // Serialize allocation across concurrent callers; the lock is released when the caller's
        // transaction completes, after the new transaction row has been written and committed.
        entityManager.createNativeQuery(ACQUIRE_ADVISORY_LOCK_SQL)
                .setParameter("lockKey", TRANSACTION_ID_LOCK_KEY)
                .getResultList();

        // Read the current maximum (null when the table is empty) and increment. Ids are fixed-width
        // zero-padded numeric strings, so lexicographic maximum equals numeric maximum.
        final String maxTranId = transactionRepository.findMaxTranId();
        final long next = ((maxTranId == null || maxTranId.isBlank())
                ? 0L
                : Long.parseLong(maxTranId.trim())) + 1L;
        return String.format(TRAN_ID_FORMAT, next);
    }
}

package com.carddemo.unit.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.repository.TransactionRepository;
import com.carddemo.service.shared.TransactionIdAllocator;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for {@link TransactionIdAllocator}.
 *
 * <p>Traceability (REFERENCE-ONLY, COBOL not copied; source commit {@code 27d6c6f}): the allocator
 * is the concurrency-safe translation of the COBOL "browse to end of file and add one" transaction-id
 * derivation in {@code app/cbl/COTRN02C.cbl} (the online transaction-add program {@code STARTBR}s the
 * TRANSACT file at {@code HIGH-VALUES}, {@code READPREV}s the highest existing key, {@code ENDBR}s, then
 * {@code ADD 1}). Under the single-threaded CICS pseudo-conversational model that read-and-add was
 * inherently serial; the stateless Java target admits concurrent callers, so allocation is serialized
 * with a PostgreSQL transaction-scoped advisory lock ({@code pg_advisory_xact_lock}). See AAP sections
 * 0.4.3 (Factory pattern for id generation) and 0.8.4 (concurrency rules).</p>
 *
 * <p>The {@link EntityManager} is injected via {@link ReflectionTestUtils} to mirror the runtime
 * {@code @PersistenceContext} field injection without a Spring context. These tests prove: (1) the
 * advisory lock is acquired with the exact SQL and lock key <em>before</em> the maximum id is read,
 * (2) an empty table yields the first id {@code "0000000000000001"}, (3) a blank maximum is treated as
 * empty, and (4) an existing maximum is incremented and rendered fixed-width to sixteen digits.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionIdAllocator - concurrency-safe COTRN02C transaction-id derivation")
class TransactionIdAllocatorTest {

    /** Exact advisory-lock acquisition statement the allocator must issue (see allocator source). */
    private static final String EXPECTED_LOCK_SQL =
            "SELECT 1 FROM (SELECT pg_advisory_xact_lock(:lockKey)) AS transaction_id_lock";

    /** Application-defined advisory-lock key namespacing transaction-id allocation. */
    private static final long EXPECTED_LOCK_KEY = 7_210_001L;

    @Mock
    private EntityManager entityManager;

    @Mock
    private Query lockQuery;

    @Mock
    private TransactionRepository transactionRepository;

    private TransactionIdAllocator allocator;

    @BeforeEach
    void setUp() {
        allocator = new TransactionIdAllocator(transactionRepository);
        // @PersistenceContext field injection is not available without a Spring context; set directly.
        ReflectionTestUtils.setField(allocator, "entityManager", entityManager);
        // Every allocation acquires the advisory lock through this query chain.
        when(entityManager.createNativeQuery(anyString())).thenReturn(lockQuery);
        when(lockQuery.setParameter(anyString(), any())).thenReturn(lockQuery);
        when(lockQuery.getResultList()).thenReturn(List.of(1));
    }

    @Test
    @DisplayName("acquires the advisory lock with the exact SQL and lock key BEFORE reading the max id")
    void acquiresAdvisoryLockBeforeReadingMax() {
        when(transactionRepository.findMaxTranId()).thenReturn("0000000000000018");

        allocator.allocateNextTransactionId();

        // The volatile lock function must be evaluated, and the maximum read AFTER the lock is held,
        // otherwise concurrent callers could read the same maximum and collide on the primary key.
        InOrder inOrder = Mockito.inOrder(entityManager, lockQuery, transactionRepository);
        inOrder.verify(entityManager).createNativeQuery(EXPECTED_LOCK_SQL);
        inOrder.verify(lockQuery).setParameter("lockKey", EXPECTED_LOCK_KEY);
        inOrder.verify(lockQuery).getResultList();
        inOrder.verify(transactionRepository).findMaxTranId();
    }

    @Test
    @DisplayName("empty table (null max) yields the first id 0000000000000001")
    void nullMaxYieldsFirstId() {
        when(transactionRepository.findMaxTranId()).thenReturn(null);

        assertThat(allocator.allocateNextTransactionId()).isEqualTo("0000000000000001");
    }

    @Test
    @DisplayName("blank max is treated as empty and yields the first id 0000000000000001")
    void blankMaxYieldsFirstId() {
        when(transactionRepository.findMaxTranId()).thenReturn("   ");

        assertThat(allocator.allocateNextTransactionId()).isEqualTo("0000000000000001");
    }

    @Test
    @DisplayName("existing max is incremented and rendered fixed-width to sixteen digits")
    void existingMaxIsIncrementedAndPadded() {
        when(transactionRepository.findMaxTranId()).thenReturn("0000000000000018");

        assertThat(allocator.allocateNextTransactionId()).isEqualTo("0000000000000019");
    }

    @Test
    @DisplayName("increment that grows the digit count stays zero-padded to width sixteen")
    void incrementAcrossDigitBoundaryStaysPadded() {
        when(transactionRepository.findMaxTranId()).thenReturn("0000000000000099");

        String allocated = allocator.allocateNextTransactionId();

        assertThat(allocated).isEqualTo("0000000000000100");
        assertThat(allocated).hasSize(16);
    }
}

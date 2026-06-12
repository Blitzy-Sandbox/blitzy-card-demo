package com.cardemo.unit.batch;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.cardemo.batch.processors.TransactionCombineProcessor;
import com.cardemo.model.entity.Transaction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-JVM behavioural-parity unit test for {@link TransactionCombineProcessor}, the Java migration
 * of the legacy AWS CardDemo {@code COMBTRAN} combine/sort step (JES job
 * {@code app/jcl/COMBTRAN.jcl}, frozen baseline commit SHA {@code 27d6c6f}).
 *
 * <p>{@code COMBTRAN} is a pure <em>DFSORT&nbsp;+&nbsp;IDCAMS&nbsp;REPRO</em> utility: it merges two
 * transaction sources, sorts them by {@code TRAN-ID} ascending, and bulk-loads the result &mdash;
 * with <strong>no per-record transformation</strong>. This test proves the two observable contracts
 * the processor is responsible for (AAP &sect;0.7.6, Minimal Change Clause &sect;0.7.1):</p>
 * <ul>
 *   <li><strong>Ordering parity</strong> &mdash; {@link TransactionCombineProcessor#BY_TRAN_ID}
 *       reproduces {@code SYMNAMES TRAN-ID,1,16,CH} + {@code SORT FIELDS=(TRAN-ID,A)}: ascending,
 *       single-key, character ordering of the 16-character transaction id; and
 *       {@link TransactionCombineProcessor#tranIdAscending()} exposes that exact same comparator
 *       instance for reuse.</li>
 *   <li><strong>Identity pass-through</strong> &mdash;
 *       {@link TransactionCombineProcessor#process(Transaction)} returns its argument unchanged
 *       (never a copy, never {@code null} for a supplied item), because DFSORT changes record
 *       <em>ordering</em>, never record <em>content</em>.</li>
 * </ul>
 *
 * <p>This is a fast, isolated unit test: the processor depends only on the JDK and the
 * {@link Transaction} entity, so there is no {@code @SpringBootTest}, application context, database,
 * AWS or Testcontainers (Surefire-compatible, container-free). The COBOL/JCL source is read-only
 * reference and is never copied into this repository (AAP &sect;0.7.2); only its behaviour is
 * asserted here.</p>
 */
class TransactionCombineProcessorTest {

    /** The system under test. Stateless and thread-safe; a single instance suffices. */
    private final TransactionCombineProcessor processor = new TransactionCombineProcessor();

    /**
     * Builds a {@link Transaction} carrying only the 16-character {@code TRAN-ID} sort key &mdash;
     * the single field the {@code COMBTRAN} comparator inspects.
     *
     * @param tranId the 16-character transaction id ({@code @Id})
     * @return a transaction whose {@link Transaction#getTranId()} is {@code tranId}
     */
    private static Transaction tx(String tranId) {
        Transaction t = new Transaction();
        t.setTranId(tranId);
        return t;
    }

    @Nested
    @DisplayName("BY_TRAN_ID / tranIdAscending() comparator (DFSORT SORT FIELDS=(TRAN-ID,A))")
    class Ordering {

        @Test
        @DisplayName("orders a lower transaction id before a higher one (ascending)")
        void ascendingOrder() {
            Transaction lower = tx("0000000000000001");
            Transaction higher = tx("0000000000000002");
            assertThat(TransactionCombineProcessor.BY_TRAN_ID.compare(lower, higher)).isNegative();
            assertThat(TransactionCombineProcessor.BY_TRAN_ID.compare(higher, lower)).isPositive();
        }

        @Test
        @DisplayName("treats equal transaction ids as a tie (compare == 0; single sort key, no EQUALS)")
        void equalKeysAreTie() {
            assertThat(TransactionCombineProcessor.BY_TRAN_ID
                    .compare(tx("0000000000000007"), tx("0000000000000007"))).isZero();
        }

        @Test
        @DisplayName("sorts an out-of-order list into ascending TRAN-ID order")
        void sortsListAscending() {
            List<Transaction> list = new ArrayList<>(List.of(
                    tx("0000000000000003"),
                    tx("0000000000000001"),
                    tx("0000000000000002")));
            list.sort(TransactionCombineProcessor.BY_TRAN_ID);
            assertThat(list).extracting(Transaction::getTranId)
                    .containsExactly("0000000000000001", "0000000000000002", "0000000000000003");
        }

        @Test
        @DisplayName("tranIdAscending() returns the canonical BY_TRAN_ID comparator instance")
        void factoryReturnsCanonicalComparator() {
            Comparator<Transaction> factory = TransactionCombineProcessor.tranIdAscending();
            assertThat(factory).isSameAs(TransactionCombineProcessor.BY_TRAN_ID);
        }
    }

    @Nested
    @DisplayName("process() identity pass-through (DFSORT preserves record content)")
    class ProcessIdentity {

        @Test
        @DisplayName("returns the same instance, never a copy")
        void returnsSameInstance() {
            Transaction item = tx("0000000000000042");
            assertThat(processor.process(item)).isSameAs(item);
        }

        @Test
        @DisplayName("does not mutate the supplied transaction's id")
        void doesNotMutate() {
            Transaction item = tx("0000000000000099");
            processor.process(item);
            assertThat(item.getTranId()).isEqualTo("0000000000000099");
        }

        @Test
        @DisplayName("passes a null item through unchanged (no filtering side effect)")
        void nullPassesThrough() {
            // A Spring Batch processor returning null would FILTER the item out of the chunk;
            // COMBTRAN never filters, so the identity contract returns exactly what it is given.
            assertThat(processor.process(null)).isNull();
        }
    }
}

package com.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.carddemo.entity.Transaction;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TransactionIdComparator}, the explicit {@link Comparator} that reproduces
 * the DFSORT sort key of the legacy {@code COMBTRAN} combine-transaction batch step (source SHA
 * {@code 27d6c6f}, {@code app/jcl/COMBTRAN.jcl}).
 *
 * <p><strong>Why this test exists.</strong> {@code COMBTRAN}'s {@code STEP05R EXEC PGM=SORT}
 * concatenates the transaction backup and the system-generated transactions and orders them with
 * {@code SORT FIELDS=(TRAN-ID,A)} over the {@code SYMNAMES} field {@code TRAN-ID,1,16,CH} — a
 * left-to-right character comparison of the 16-byte transaction id, ascending — so that the
 * following {@code STEP10 IDCAMS REPRO} can load the combined stream into the {@code TRANSACT}
 * KSDS. Every downstream statement and report step assumes that ascending {@code TRAN-ID} order.
 * Getting the direction or the null handling wrong would silently corrupt the ordering of every
 * statement and report, so this tiny comparator is a parity linchpin (AAP&nbsp;G3; §0.4.3
 * "Comparator strategy"; §0.8.5 batch-pipeline rules). These tests pin its contract exactly.</p>
 *
 * <p><strong>What is asserted.</strong> The tests cover the three behaviours that define the
 * DFSORT key:</p>
 * <ul>
 *   <li><em>Direction (ascending).</em> A lower id sorts before a higher id, equal ids compare
 *       equal, and sorting a shuffled list yields strictly ascending {@code tranId} order.</li>
 *   <li><em>Null handling (nulls-last).</em> The production key is
 *       {@code Comparator.comparing(Transaction::getTranId, Comparator.nullsLast(...))}, so the
 *       null guard is on the extracted {@code tranId} <em>value</em>: a {@code null} id sorts
 *       <em>last</em> and never raises during a sort. The guard is deliberately <em>not</em> at
 *       the {@code Transaction}-reference level, so a {@code null} reference fails fast with a
 *       {@link NullPointerException}; a characterization test pins that exact boundary.</li>
 *   <li><em>Serializable contract.</em> Spring Batch may persist comparators into a step/job
 *       {@code ExecutionContext}, so the type is {@link Serializable}; a round-trip through
 *       {@link ObjectOutputStream}/{@link ObjectInputStream} must yield an instance that compares
 *       identically.</li>
 * </ul>
 *
 * <p>These are deliberately pure POJO unit tests: no Spring context, persistence, mocks, or
 * containers, so they run in milliseconds and contribute fast line coverage toward the Gate&nbsp;8
 * (&ge;80%) JaCoCo threshold, and they compile clean under {@code -Xlint:all} (Gate&nbsp;2). No
 * COBOL source is reproduced here; rationale lives in {@code docs/decision-log.md}.</p>
 *
 * <p><strong>Id format.</strong> Transaction ids are the fixed-width 16-digit zero-padded strings
 * of {@code TRAN-ID PIC X(16)}, so lexical (character) ascending order equals numeric ascending
 * order; the samples below use that real format on purpose.</p>
 *
 * @see TransactionIdComparator
 * @see TransactionIdComparator#BY_TRAN_ID
 * @see Transaction#getTranId()
 */
@DisplayName("TransactionIdComparator — COMBTRAN DFSORT key (TRAN-ID, ascending, nulls-last)")
class TransactionIdComparatorTest {

    // ------------------------------------------------------------------
    // Sample ids in the real 16-digit zero-padded TRAN-ID format. Because
    // the field is fixed-width and zero-padded, lexical order == numeric
    // order, so the expected ascending sequence below is unambiguous.
    // ------------------------------------------------------------------
    private static final String ID_1 = "0000000000000001";
    private static final String ID_2 = "0000000000000002";
    private static final String ID_3 = "0000000000000003";
    private static final String ID_5 = "0000000000000005";
    private static final String ID_10 = "0000000000000010";
    private static final String ID_100 = "0000000000000100";
    private static final String ID_683580 = "0000000000683580";

    /**
     * The comparator under test. An instance of the production type is used (rather than only the
     * {@link TransactionIdComparator#BY_TRAN_ID} constant) so that the {@link Serializable}
     * round-trip in Phase 4 exercises the real class: {@code TransactionIdComparator} declares
     * {@link Serializable} directly and carries no instance state, whereas {@code BY_TRAN_ID} is a
     * {@code Comparator.comparing(...)} chain built from a (non-serializable) method reference.
     */
    private final TransactionIdComparator comparator = new TransactionIdComparator();

    // ==================================================================
    // Phase 2 — Ascending order (direction parity with SORT FIELDS=(TRAN-ID,A)).
    // ==================================================================

    @Test
    @DisplayName("a lower id compares before a higher id (negative)")
    void lowerIdComparesBeforeHigherId() {
        assertThat(comparator.compare(tx(ID_1), tx(ID_2))).isNegative();
    }

    @Test
    @DisplayName("a higher id compares after a lower id (positive)")
    void higherIdComparesAfterLowerId() {
        assertThat(comparator.compare(tx(ID_2), tx(ID_1))).isPositive();
    }

    @Test
    @DisplayName("equal ids compare equal (zero)")
    void equalIdsCompareEqual() {
        assertThat(comparator.compare(tx(ID_5), tx(ID_5))).isZero();
    }

    @Test
    @DisplayName("sorting a shuffled list yields strictly ascending tranId order")
    void sortingShuffledListYieldsAscendingOrder() {
        // A deterministic (fixed) out-of-order input keeps the test reproducible while still
        // exercising a genuine reordering across several magnitudes of the zero-padded key.
        List<Transaction> shuffled = new ArrayList<>(List.of(
                tx(ID_100), tx(ID_2), tx(ID_683580), tx(ID_1), tx(ID_10), tx(ID_3)));

        shuffled.sort(comparator);

        List<String> sortedIds = shuffled.stream().map(Transaction::getTranId).toList();
        assertThat(sortedIds).containsExactly(ID_1, ID_2, ID_3, ID_10, ID_100, ID_683580);
        // Redundant-but-explicit: confirm ascending natural order of the extracted ids.
        assertThat(sortedIds).isSorted();
    }

    @Test
    @DisplayName("BY_TRAN_ID public constant encodes the same ascending, nulls-last key")
    void byTranIdConstantEncodesAscendingNullsLastKey() {
        // The exposed constant is part of the public API (callers pass it directly to stream/list/
        // Spring Batch APIs); assert it independently of the instance delegate.
        Comparator<Transaction> byTranId = TransactionIdComparator.BY_TRAN_ID;

        assertThat(byTranId.compare(tx(ID_1), tx(ID_2))).isNegative();
        assertThat(byTranId.compare(tx(ID_2), tx(ID_1))).isPositive();
        assertThat(byTranId.compare(tx(ID_5), tx(ID_5))).isZero();
        assertThat(byTranId.compare(tx(null), tx(ID_1))).isPositive();
        assertThat(byTranId.compare(tx(ID_1), tx(null))).isNegative();
    }

    // ==================================================================
    // Phase 3 — Nulls-last semantics (on the tranId VALUE, matching production exactly).
    // ==================================================================

    @Test
    @DisplayName("a null tranId sorts after a non-null id (null value is greatest)")
    void nullIdComparesAfterNonNullId() {
        assertThat(comparator.compare(tx(null), tx(ID_1))).isPositive();
    }

    @Test
    @DisplayName("a non-null id sorts before a null tranId")
    void nonNullIdComparesBeforeNullId() {
        assertThat(comparator.compare(tx(ID_1), tx(null))).isNegative();
    }

    @Test
    @DisplayName("two null tranIds compare equal (zero)")
    void twoNullIdsCompareEqual() {
        assertThat(comparator.compare(tx(null), tx(null))).isZero();
    }

    @Test
    @DisplayName("sorting a list containing a null-id row places that row last")
    void sortingListWithNullIdPlacesItLast() {
        // The list elements are all non-null Transaction references; only one carries a null
        // tranId. nullsLast on the extracted key means that row lands at the END with no NPE.
        List<Transaction> withNullId = new ArrayList<>(List.of(tx(ID_2), tx(null), tx(ID_1)));

        withNullId.sort(comparator);

        List<String> ids = withNullId.stream().map(Transaction::getTranId).toList();
        assertThat(ids).containsExactly(ID_1, ID_2, null);
    }

    @Test
    @DisplayName("nulls-last is on the tranId value, not the reference: a null Transaction throws NPE")
    void nullTransactionReferenceThrowsNpe() {
        // Production key = Comparator.comparing(Transaction::getTranId, nullsLast(naturalOrder())).
        // The null guard applies to the EXTRACTED id, so a null Transaction reference is not
        // supported and fails fast through the key extractor (in either argument position). This
        // characterization test pins the exact boundary of the null contract so a future change
        // that widened it (e.g. wrapping the whole comparator in nullsLast) would be caught.
        assertThrows(NullPointerException.class, () -> comparator.compare(null, tx(ID_1)));
        assertThrows(NullPointerException.class, () -> comparator.compare(tx(ID_1), null));
    }

    // ==================================================================
    // Phase 4 — Serializable contract (Spring Batch persists step artifacts).
    // ==================================================================

    @Test
    @DisplayName("the comparator is Serializable")
    void comparatorIsSerializable() {
        assertThat(comparator).isInstanceOf(Serializable.class);
    }

    @Test
    @DisplayName("a serialized/deserialized comparator produces identical compare results")
    void deserializedComparatorProducesIdenticalResults() throws IOException, ClassNotFoundException {
        TransactionIdComparator restored = roundTrip(comparator);

        // A genuine round-trip yields a distinct instance of the same type...
        assertThat(restored).isInstanceOf(TransactionIdComparator.class);
        assertThat(restored).isNotSameAs(comparator);

        // ...that reproduces the Phase-2 ascending results exactly.
        assertThat(restored.compare(tx(ID_1), tx(ID_2))).isNegative();
        assertThat(restored.compare(tx(ID_2), tx(ID_1))).isPositive();
        assertThat(restored.compare(tx(ID_5), tx(ID_5))).isZero();
        // ...and preserves nulls-last on the tranId value.
        assertThat(restored.compare(tx(null), tx(ID_1))).isPositive();
        assertThat(restored.compare(tx(ID_1), tx(null))).isNegative();
    }

    // ------------------------------------------------------------------
    // Helpers.
    // ------------------------------------------------------------------

    /**
     * Builds a {@link Transaction} carrying only the given {@code tranId}; every other field is
     * irrelevant to the id-only sort key and is left at its default.
     *
     * @param tranId the transaction id to set (may be {@code null} to exercise nulls-last)
     * @return a transaction whose {@link Transaction#getTranId()} returns {@code tranId}
     */
    private static Transaction tx(String tranId) {
        Transaction transaction = new Transaction();
        transaction.setTranId(tranId);
        return transaction;
    }

    /**
     * Serializes then deserializes the supplied comparator through in-memory byte streams,
     * returning the reconstructed instance. Uses try-with-resources on both the output and input
     * stream pairs. The {@link ObjectInputStream#readObject()} result is cast to the concrete,
     * non-generic {@link TransactionIdComparator} type, so the cast is checked and produces no
     * {@code -Xlint:all} unchecked warning (Gate&nbsp;2).
     *
     * @param original the comparator to round-trip
     * @return a freshly deserialized {@link TransactionIdComparator}
     * @throws IOException            if the in-memory serialization streams fail
     * @throws ClassNotFoundException if the serialized class cannot be resolved on read
     */
    private static TransactionIdComparator roundTrip(TransactionIdComparator original)
            throws IOException, ClassNotFoundException {
        byte[] serialized;
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                ObjectOutputStream objectOut = new ObjectOutputStream(bytes)) {
            objectOut.writeObject(original);
            objectOut.flush();
            serialized = bytes.toByteArray();
        }
        try (ByteArrayInputStream bytes = new ByteArrayInputStream(serialized);
                ObjectInputStream objectIn = new ObjectInputStream(bytes)) {
            return (TransactionIdComparator) objectIn.readObject();
        }
    }
}

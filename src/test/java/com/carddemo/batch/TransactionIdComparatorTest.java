package com.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.entity.Transaction;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pure unit test for {@link TransactionIdComparator}, the Java encoding of the DFSORT sort key used
 * by the legacy {@code COMBTRAN} batch job. Legacy source is referenced read-only at commit SHA
 * {@code 27d6c6f}.
 *
 * <p>{@code COMBTRAN} declares {@code SORT FIELDS=(TRAN-ID,A)} over the 16-character
 * {@code TRAN-ID} field ({@code CVTRA05Y} / {@link Transaction#getTranId()}), format {@code CH}
 * (character), direction ascending. This suite pins that contract:</p>
 * <ul>
 *   <li><strong>Ascending character order</strong> on {@code tranId}, byte-for-byte, matching
 *       {@link String#compareTo(String)} on the fixed-width id.</li>
 *   <li><strong>Null id sorts last</strong> ({@link Comparator#nullsLast}), so a malformed row can
 *       never raise a {@link NullPointerException} during the sort.</li>
 *   <li><strong>Total order</strong>: {@code compare} returns {@code 0} only for equal ids, and the
 *       instance {@code compare} delegates to the shared {@link TransactionIdComparator#BY_TRAN_ID}
 *       constant.</li>
 *   <li><strong>Serializable</strong> with a fixed {@code serialVersionUID}, so Spring Batch can
 *       persist it into an execution context without warnings.</li>
 * </ul>
 *
 * <p>No Spring context, database, or network is used, so the suite is fast and compiles
 * warning-free under {@code -Xlint:all} (Gate&nbsp;2), contributing to Gate&nbsp;8 coverage.</p>
 *
 * @see TransactionIdComparator
 */
@DisplayName("TransactionIdComparator — COMBTRAN SORT FIELDS=(TRAN-ID,A) key")
class TransactionIdComparatorTest {

    /** Comparator instance under test (stateless; interchangeable with {@code BY_TRAN_ID}). */
    private final TransactionIdComparator comparator = new TransactionIdComparator();

    /**
     * Builds a {@link Transaction} carrying only the {@code tranId}, which is all the comparator
     * inspects.
     *
     * @param tranId the transaction id (may be {@code null} to exercise null handling)
     * @return a transaction with the given id
     */
    private static Transaction tx(final String tranId) {
        final Transaction transaction = new Transaction();
        transaction.setTranId(tranId);
        return transaction;
    }

    // =====================================================================
    // 1) Ascending character ordering
    // =====================================================================

    @Nested
    @DisplayName("ascending character ordering on tranId")
    class AscendingOrder {

        @Test
        @DisplayName("orders a lower id before a higher id (negative / positive / zero)")
        void ordersByAscendingTranId() {
            final Transaction lower = tx("0000000000000001");
            final Transaction higher = tx("0000000000000002");

            assertThat(comparator.compare(lower, higher)).isNegative();
            assertThat(comparator.compare(higher, lower)).isPositive();
            assertThat(comparator.compare(lower, tx("0000000000000001"))).isZero();
        }

        @Test
        @DisplayName("sorts a shuffled list into ascending tranId order")
        void sortsListAscending() {
            final List<Transaction> list = new ArrayList<>(List.of(
                    tx("0000000000000003"),
                    tx("0000000000000001"),
                    tx("0000000000000002")));

            list.sort(comparator);

            assertThat(list).extracting(Transaction::getTranId)
                    .containsExactly(
                            "0000000000000001",
                            "0000000000000002",
                            "0000000000000003");
        }
    }

    // =====================================================================
    // 2) Null handling — null id sorts last
    // =====================================================================

    @Nested
    @DisplayName("null tranId sorts last")
    class NullHandling {

        @Test
        @DisplayName("a null id compares greater than any non-null id, and never throws")
        void nullIdSortsLast() {
            final Transaction withId = tx("0000000000000001");
            final Transaction withNull = tx(null);

            assertThat(comparator.compare(withNull, withId)).isPositive();
            assertThat(comparator.compare(withId, withNull)).isNegative();
            // Two null ids are equal to each other under nullsLast.
            assertThat(comparator.compare(withNull, tx(null))).isZero();
        }

        @Test
        @DisplayName("sorting a list containing a null id places it last")
        void sortPlacesNullIdLast() {
            final List<Transaction> list = new ArrayList<>(List.of(
                    tx(null),
                    tx("0000000000000002"),
                    tx("0000000000000001")));

            list.sort(comparator);

            assertThat(list).extracting(Transaction::getTranId)
                    .containsExactly("0000000000000001", "0000000000000002", null);
        }
    }

    // =====================================================================
    // 3) Shared constant + Serializable contract
    // =====================================================================

    @Nested
    @DisplayName("shared BY_TRAN_ID constant and Serializable contract")
    class ContractDetails {

        @Test
        @DisplayName("instance compare delegates to the shared BY_TRAN_ID constant")
        void instanceDelegatesToSharedConstant() {
            final Transaction a = tx("0000000000000001");
            final Transaction b = tx("0000000000000002");

            assertThat(comparator.compare(a, b))
                    .isEqualTo(TransactionIdComparator.BY_TRAN_ID.compare(a, b));
        }

        @Test
        @DisplayName("is Serializable and round-trips through Java serialization")
        void isSerializable() throws Exception {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
                out.writeObject(comparator);
            }

            final Object restored;
            try (ObjectInputStream in =
                    new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
                restored = in.readObject();
            }

            assertThat(restored).isInstanceOf(TransactionIdComparator.class);
            // The deserialized comparator still orders identically (no instance state to lose).
            final Transaction a = tx("0000000000000001");
            final Transaction b = tx("0000000000000002");
            @SuppressWarnings("unchecked")
            final Comparator<Transaction> restoredComparator = (Comparator<Transaction>) restored;
            assertThat(restoredComparator.compare(a, b)).isNegative();
        }
    }
}

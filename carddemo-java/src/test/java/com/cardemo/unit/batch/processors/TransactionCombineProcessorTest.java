package com.cardemo.unit.batch.processors;

import java.math.BigDecimal;
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
 * Fast, dependency-free behavioural-parity unit test for {@link TransactionCombineProcessor}, the
 * Java&nbsp;25 / Spring&nbsp;Boot&nbsp;3.x migration of the legacy AWS CardDemo {@code COMBTRAN}
 * combine/sort step (JES job {@code app/jcl/COMBTRAN.jcl}, frozen baseline commit SHA
 * {@code 27d6c6f}).
 *
 * <h2>What {@code COMBTRAN} does (the parity target)</h2>
 * <p>Unlike most steps in the estate, {@code COMBTRAN} has <strong>no COBOL program</strong>: it is
 * a pure <em>DFSORT&nbsp;+&nbsp;IDCAMS&nbsp;REPRO</em> utility job. Its two observable behaviours,
 * read directly from the JCL, are:</p>
 * <ul>
 *   <li><strong>{@code STEP05R EXEC PGM=SORT} (DFSORT).</strong> The {@code SYMNAMES} statement
 *       defines {@code TRAN-ID,1,16,CH} &mdash; the 16&nbsp;bytes at record positions 1&ndash;16,
 *       compared as <em>character</em> ({@code CH}) data &mdash; and {@code SYSIN} requests
 *       {@code SORT FIELDS=(TRAN-ID,A)}: an <strong>ascending</strong>, single-key sort on that
 *       16-character transaction id. {@code SORTIN} concatenates two generation-data-group inputs
 *       ({@code TRANSACT.BKUP(0)} then {@code SYSTRAN(0)}); {@code SORTOUT} is
 *       {@code TRANSACT.COMBINED(+1)}.</li>
 *   <li><strong>{@code STEP10 EXEC PGM=IDCAMS} ({@code REPRO}).</strong>
 *       {@code REPRO INFILE(TRANSACT) OUTFILE(TRANVSAM)} bulk-loads the sorted file into the VSAM
 *       KSDS. <em>This bulk load is the writer's concern</em> (see the boundary note below) and is
 *       deliberately not exercised here.</li>
 * </ul>
 * <p>Net effect: merge two transaction sources, sort by {@code TRAN-ID} ascending, and bulk-load.
 * Crucially DFSORT changes only record <em>ordering</em>, never record <em>content</em>.</p>
 *
 * <h2>The two contracts proven here (AAP &sect;0.7.6, Minimal Change Clause &sect;0.7.1)</h2>
 * <ul>
 *   <li><strong>Identity pass-through</strong> &mdash;
 *       {@link TransactionCombineProcessor#process(Transaction)} returns its argument
 *       <em>unaltered</em> (the same instance, never a copy, never {@code null} for a supplied
 *       item), because {@code COMBTRAN} performs no per-record transformation, filtering,
 *       deduplication or validation.</li>
 *   <li><strong>Ordering parity</strong> &mdash;
 *       {@link TransactionCombineProcessor#BY_TRAN_ID} (also exposed via the
 *       {@link TransactionCombineProcessor#tranIdAscending()} factory) reproduces
 *       {@code SYMNAMES TRAN-ID,1,16,CH} + {@code SORT FIELDS=(TRAN-ID,A)}: an ascending,
 *       single-key, full-16-character <em>string</em> (lexicographic / {@code CH}) ordering.</li>
 * </ul>
 *
 * <h2>REPRO &rarr; bulk-insert boundary (intentionally NOT asserted here)</h2>
 * <p>{@code STEP10}'s {@code IDCAMS REPRO} bulk load migrates to a JPA bulk insert
 * ({@code TransactionRepository.saveAll(...)} / {@code JdbcTemplate} batch) performed by the batch
 * <em>writer</em> in {@code com.cardemo.batch.writers}, not by this processor. Persistence is
 * therefore out of scope for this test (covered by {@code integration/batch} and the writer's own
 * test); accordingly <strong>no repository is imported</strong> and no persistence side effect is
 * asserted.</p>
 *
 * <h2>Test design</h2>
 * <p>This is a pure-JVM unit test: the processor has no collaborators, so it is instantiated
 * directly with {@code new TransactionCombineProcessor()} &mdash; there is no Mockito, no
 * {@code @SpringBootTest}, no application context, database, AWS or Testcontainers. The COBOL/JCL
 * source is read-only reference and is <strong>never copied</strong> into this repository (AAP
 * &sect;0.7.2); only its observable behaviour is asserted here, against the frozen baseline at
 * commit SHA {@code 27d6c6f}.</p>
 *
 * @see TransactionCombineProcessor
 * @see Transaction
 */
class TransactionCombineProcessorTest {

    /**
     * Sample 16-character card number used by every fixture. It is irrelevant to the
     * {@code COMBTRAN} sort (which keys only on {@code TRAN-ID}); it exists purely so the
     * identity pass-through test can prove a non-key field survives {@code process(...)}
     * unchanged.
     */
    private static final String SAMPLE_CARD_NUM = "1234567890123456";

    /**
     * Sample monetary amount used by every fixture. As with {@link #SAMPLE_CARD_NUM}, it plays no
     * part in the sort; it exists to prove the {@code BigDecimal} amount (a decimal-critical field,
     * AAP &sect;0.7.3) is preserved byte-for-byte by the identity pass-through.
     */
    private static final BigDecimal SAMPLE_AMOUNT = new BigDecimal("1.00");

    /**
     * The system under test. {@link TransactionCombineProcessor} is stateless and thread-safe and
     * declares no collaborators, so a single directly-constructed instance suffices.
     */
    private final TransactionCombineProcessor processor = new TransactionCombineProcessor();

    /**
     * Builds a {@link Transaction} carrying the supplied 16-character {@code TRAN-ID} (the sole
     * field the {@code COMBTRAN} comparator inspects) plus a couple of distinguishing non-key
     * fields ({@code tranCardNum}, {@code tranAmt}) so the identity pass-through can assert that
     * content is preserved.
     *
     * @param tranId the 16-character transaction id ({@code TRAN-ID PIC X(16)}, the entity
     *               {@code @Id})
     * @return a populated {@link Transaction} whose {@link Transaction#getTranId()} is {@code tranId}
     */
    private static Transaction tx(String tranId) {
        Transaction t = new Transaction();
        t.setTranId(tranId);
        t.setTranCardNum(SAMPLE_CARD_NUM);
        t.setTranAmt(SAMPLE_AMOUNT);
        return t;
    }

    /**
     * Phase&nbsp;B &mdash; {@code COMBTRAN} is sort-only, so {@link TransactionCombineProcessor#process(Transaction)}
     * is a deliberate identity pass-through: it returns the very same instance, unmodified.
     */
    @Nested
    @DisplayName("process() — identity pass-through (COMBTRAN STEP05R sorts; it performs no per-record transformation)")
    class IdentityPassThrough {

        @Test
        @DisplayName("process() is an identity pass-through (COMBTRAN performs no per-record transformation)")
        void returnsSameInstance() {
            Transaction input = tx("0000000000000005");

            Transaction result = processor.process(input);

            // DFSORT changes ordering, not content: the processor returns the SAME object.
            // Reference identity (isSameAs) is required here -- Transaction.equals() is keyed only
            // on tranId, so isEqualTo would NOT prove the identical instance was returned.
            assertThat(result).isNotNull();
            assertThat(result).isSameAs(input);
        }

        @Test
        @DisplayName("process() mutates no field — TRAN-ID, TRAN-CARD-NUM and the BigDecimal TRAN-AMT are preserved")
        void doesNotMutateAnyField() {
            Transaction input = tx("0000000000000005");

            processor.process(input);

            // Content is preserved byte-for-byte (Minimal Change Clause): every field still holds
            // exactly the value supplied by the fixture. BigDecimal is compared with
            // isEqualByComparingTo (scale-insensitive), never equals (AAP §0.7.3).
            assertThat(input.getTranId()).isEqualTo("0000000000000005");
            assertThat(input.getTranCardNum()).isEqualTo(SAMPLE_CARD_NUM);
            assertThat(input.getTranAmt()).isEqualByComparingTo(SAMPLE_AMOUNT);
        }
    }

    /**
     * Phase&nbsp;C &mdash; the genuine translation under test: the exposed
     * {@link TransactionCombineProcessor#BY_TRAN_ID} {@link Comparator} reproducing DFSORT
     * {@code SORT FIELDS=(TRAN-ID,A)} over the {@code SYMNAMES TRAN-ID,1,16,CH} key.
     */
    @Nested
    @DisplayName("BY_TRAN_ID — DFSORT SORT FIELDS=(TRAN-ID,A) over SYMNAMES TRAN-ID,1,16,CH")
    class SortComparator {

        @Test
        @DisplayName("BY_TRAN_ID orders by full 16-char tranId ascending (SORT FIELDS=(TRAN-ID,A), TRAN-ID,1,16,CH)")
        void sortsListAscendingByTranId() {
            // An out-of-order batch of records, as DFSORT would receive from the concatenated
            // BKUP(0) + SYSTRAN(0) SORTIN stream.
            List<Transaction> list = new ArrayList<>(List.of(
                    tx("0000000000000300"),
                    tx("0000000000000010"),
                    tx("0000000000000002"),
                    tx("0000000000000100")));

            list.sort(TransactionCombineProcessor.BY_TRAN_ID);

            assertThat(list).extracting(Transaction::getTranId)
                    .containsExactly(
                            "0000000000000002",
                            "0000000000000010",
                            "0000000000000100",
                            "0000000000000300");
        }

        @Test
        @DisplayName("BY_TRAN_ID respects zero-padding: '...0009' precedes '...0010' (full 16-char string compare, not a parsed number)")
        void respectsZeroPaddingAcrossDigitWidth() {
            // Because TRAN-ID is zero-padded to a fixed 16-character width, the lexicographic
            // (DFSORT CH) ordering coincides with numeric ordering: "...0009" sorts before
            // "...0010". This confirms the comparator compares the entire 16-character string
            // (String.compareTo), faithfully reproducing the CH byte comparison.
            List<Transaction> list = new ArrayList<>(List.of(
                    tx("0000000000000010"),
                    tx("0000000000000009")));

            list.sort(TransactionCombineProcessor.BY_TRAN_ID);

            assertThat(list).extracting(Transaction::getTranId)
                    .containsExactly("0000000000000009", "0000000000000010");
        }

        @Test
        @DisplayName("BY_TRAN_ID.compare(...) is negative / positive / zero — the pair-wise ascending contract")
        void pairwiseCompareContract() {
            Transaction lower = tx("0000000000000002");
            Transaction higher = tx("0000000000000010");

            // Ascending: a lower id precedes a higher id, the reverse follows, and equal ids tie.
            assertThat(TransactionCombineProcessor.BY_TRAN_ID.compare(lower, higher)).isNegative();
            assertThat(TransactionCombineProcessor.BY_TRAN_ID.compare(higher, lower)).isPositive();
            assertThat(TransactionCombineProcessor.BY_TRAN_ID
                    .compare(tx("0000000000000007"), tx("0000000000000007"))).isZero();
        }

        @Test
        @DisplayName("tranIdAscending() exposes the canonical public static BY_TRAN_ID instance for reuse")
        void factoryReturnsCanonicalComparator() {
            // Referencing TransactionCombineProcessor.BY_TRAN_ID and tranIdAscending() from this
            // test compiles only because both are public static -- visibility is proven at compile
            // time, with no reflection required.
            Comparator<Transaction> factory = TransactionCombineProcessor.tranIdAscending();

            assertThat(factory).isSameAs(TransactionCombineProcessor.BY_TRAN_ID);
        }
    }
}

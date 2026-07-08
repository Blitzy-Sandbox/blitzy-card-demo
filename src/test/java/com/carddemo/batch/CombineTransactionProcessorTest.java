package com.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.carddemo.entity.Transaction;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CombineTransactionProcessor}, the chunk-step {@code ItemProcessor} for the
 * CardDemo combine-transactions stage (processor #3 of 5 in the batch pipeline, wired into
 * {@code CombineTransactionJob}).
 *
 * <p><strong>Why this test exists.</strong> {@link CombineTransactionProcessor} is an
 * <em>intentional identity (pass-through) transform</em>: it returns the exact instance it is
 * handed, neither copying nor mutating it. That is by design, not by accident — the two
 * responsibilities of the legacy {@code COMBTRAN.jcl} job (source SHA {@code 27d6c6f}) are realized
 * by the surrounding chunk components, leaving nothing for the processor to change:</p>
 *
 * <pre>
 *   STEP05R  EXEC PGM=SORT     SORT FIELDS=(TRAN-ID,A)     (sort combined set ascending by TRAN-ID)
 *   STEP10   EXEC PGM=IDCAMS   REPRO INFILE(TRANSACT)      (reload the sorted combined file into
 *                              OUTFILE(TRANVSAM)            the transaction master VSAM KSDS)
 * </pre>
 *
 * <ul>
 *   <li>The DFSORT ascending sort on {@code TRAN-ID} is realized by the combine reader (whose query
 *       orders rows {@code ORDER BY tranId ASC}, equivalent to {@code TransactionIdComparator}), so
 *       the ordering is established <em>before</em> items reach this processor. Those sort semantics
 *       (ascending TRAN-ID, duplicate handling) are pinned in {@code TransactionIdComparatorTest}
 *       and {@code BatchReadersIT}, not here.</li>
 *   <li>The {@code IDCAMS REPRO} reload (the shared {@code REPROC.prc} procedure) is realized by the
 *       step's {@code RepositoryItemWriter<Transaction>}, which re-persists each item in the order
 *       received.</li>
 * </ul>
 *
 * <p>These tests therefore lock the processor's no-op contract so that no accidental
 * mutation, copying, or filtering ever creeps in (AAP §0.5 "DFSORT keys → Comparator"; identity
 * transform in the chunk):</p>
 * <ol>
 *   <li><strong>Identity</strong> — {@code process(item)} returns the <em>same</em> reference
 *       (asserted with {@code isSameAs}, which is meaningful because {@link Transaction} does not
 *       override {@code equals}/{@code hashCode} and so uses reference identity).</li>
 *   <li><strong>No mutation</strong> — every one of the 13 mapped fields is unchanged after the
 *       call, and the {@code BigDecimal} amount is not even re-scaled (it is the same instance).</li>
 *   <li><strong>Never null for a non-null input</strong> — a {@code null} return would filter the
 *       item out of the Spring Batch chunk and drop it from the reloaded master, breaking
 *       byte-equivalent {@code REPRO} parity; the processor must pass every record through.</li>
 *   <li><strong>Out-of-contract null input</strong> — the chunk contract guarantees a non-null item
 *       (end-of-input is signalled by the reader returning {@code null}, never by a {@code null}
 *       item reaching the processor); a {@code null} item fails fast rather than being silently
 *       swallowed.</li>
 * </ol>
 *
 * <p>The tests are deliberately pure POJO unit tests: no Spring context, persistence, mocks, or
 * containers are involved, so they run in milliseconds and contribute fast line coverage toward the
 * Gate&nbsp;8 (&ge;80%) JaCoCo threshold, and they compile warning-free under {@code -Xlint:all}
 * (Gate&nbsp;2). No COBOL source is reproduced here; rationale lives in {@code docs/decision-log.md}.</p>
 */
@DisplayName("CombineTransactionProcessor — COMBTRAN identity pass-through contract")
class CombineTransactionProcessorTest {

    /**
     * The class under test. {@link CombineTransactionProcessor} is documented as stateless,
     * thread-safe, and holding no injected collaborators, so a single shared instance is safe to
     * reuse across every test method.
     */
    private final CombineTransactionProcessor processor = new CombineTransactionProcessor();

    // ------------------------------------------------------------------
    // Phase 1 — Identity pass-through: same instance, no observable mutation.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("process(item) returns the very same instance (identity, not a copy)")
    void processReturnsTheSameInstance() {
        Transaction t = newTransaction("0000000000000042", new BigDecimal("123.45"));

        // isSameAs asserts reference identity (==), the strongest possible pass-through guarantee.
        // Reference identity is the correct check here: Transaction defines no equals/hashCode.
        assertThat(processor.process(t)).isSameAs(t);
    }

    @Test
    @DisplayName("process(item) mutates no field of the transaction")
    void processDoesNotMutateAnyField() {
        // Fully populate all 13 mapped fields with known values, then capture the amount reference
        // so we can prove the BigDecimal is not reallocated or re-scaled by the pass-through.
        Transaction t = new Transaction();
        t.setTranId("0000000000000042");
        t.setTranTypeCd("01");
        t.setTranCatCd(5);
        t.setTranSource("POS");
        t.setTranDesc("GROCERY STORE PURCHASE");
        BigDecimal amount = new BigDecimal("123.45");
        t.setTranAmt(amount);
        t.setTranMerchantId(987654321L);
        t.setTranMerchantName("ACME MARKETS");
        t.setTranMerchantCity("SEATTLE");
        t.setTranMerchantZip("98101");
        t.setTranCardNum("4111111111111111");
        t.setTranOrigTs("2022-07-19-23.23.05.000000");
        t.setTranProcTs("2022-07-19-23.27.38.000000");

        Transaction result = processor.process(t);

        // Same instance returned ...
        assertThat(result).isSameAs(t);

        // ... and every field still holds exactly what was set — no field was mutated.
        assertThat(result.getTranId()).isEqualTo("0000000000000042");
        assertThat(result.getTranTypeCd()).isEqualTo("01");
        assertThat(result.getTranCatCd()).isEqualTo(5);
        assertThat(result.getTranSource()).isEqualTo("POS");
        assertThat(result.getTranDesc()).isEqualTo("GROCERY STORE PURCHASE");
        // The signed monetary amount (COBOL TRAN-AMT PIC S9(09)V99, scale 2) is untouched: the
        // exact same BigDecimal reference is returned, so neither its value nor its scale changes.
        assertThat(result.getTranAmt()).isSameAs(amount);
        assertThat(result.getTranAmt().scale()).isEqualTo(2);
        assertThat(result.getTranMerchantId()).isEqualTo(987654321L);
        assertThat(result.getTranMerchantName()).isEqualTo("ACME MARKETS");
        assertThat(result.getTranMerchantCity()).isEqualTo("SEATTLE");
        assertThat(result.getTranMerchantZip()).isEqualTo("98101");
        assertThat(result.getTranCardNum()).isEqualTo("4111111111111111");
        assertThat(result.getTranOrigTs()).isEqualTo("2022-07-19-23.23.05.000000");
        assertThat(result.getTranProcTs()).isEqualTo("2022-07-19-23.27.38.000000");
    }

    // ------------------------------------------------------------------
    // Phase 2 — Never null for a non-null input (REPRO 1:1 parity).
    // ------------------------------------------------------------------

    @Test
    @DisplayName("process(item) is non-null and same-ref for several distinct transactions")
    void processReturnsNonNullAndSameRefForSeveralDistinctTransactions() {
        // A representative spread: a minimal positive amount, a negative (credit/refund) amount, and
        // a large amount at the boundary tran-id. Every one must pass through 1:1 and non-null, so
        // the combine stage preserves records one-for-one (nothing is filtered out of the chunk).
        List<Transaction> transactions = List.of(
                newTransaction("0000000000000001", new BigDecimal("0.01")),
                newTransaction("0000000000000500", new BigDecimal("-250.00")),
                newTransaction("9999999999999999", new BigDecimal("1000000.99")));

        for (Transaction t : transactions) {
            Transaction result = processor.process(t);

            assertThat(result).isNotNull();
            assertThat(result).isSameAs(t);
        }
    }

    @Test
    @DisplayName("process(null) throws NullPointerException (out-of-contract input is not swallowed)")
    void processRejectsNullItemWithNullPointerException() {
        // The Spring Batch chunk contract guarantees a non-null item; end-of-input is signalled by
        // the reader returning null, never by a null item reaching the processor. The processor
        // dereferences the item (item.getTranId()) to build its per-item TRACE log line, so a null
        // item fails fast with NullPointerException rather than being silently swallowed — which is
        // the correct REPRO-parity behaviour, because a filtered/dropped record would be omitted
        // from the reloaded transaction master. This characterization test pins that behaviour so a
        // future change cannot quietly turn an invalid null into a dropped (filtered) record.
        assertThrows(NullPointerException.class, () -> processor.process(null));
    }

    // ------------------------------------------------------------------
    // Helper — build a Transaction with just the key/amount populated, so
    // each identity assertion isolates the pass-through behaviour.
    // ------------------------------------------------------------------
    private static Transaction newTransaction(String tranId, BigDecimal amount) {
        Transaction t = new Transaction();
        t.setTranId(tranId);
        t.setTranAmt(amount);
        return t;
    }
}

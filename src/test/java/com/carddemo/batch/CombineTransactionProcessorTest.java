package com.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.entity.Transaction;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pure unit test for {@link CombineTransactionProcessor}, the identity (pass-through)
 * {@code ItemProcessor} of the CardDemo combine-transactions stage (processor&nbsp;#3 of&nbsp;5).
 * Legacy source is referenced read-only at commit SHA {@code 27d6c6f}.
 *
 * <p>The legacy {@code COMBTRAN.jcl} job is a pure DFSORT-then-{@code IDCAMS REPRO} pipeline with
 * <em>no</em> record-level transformation: the ascending {@code TRAN-ID} sort lives in the reader
 * and the master reload lives in the writer. This processor therefore exists only to preserve the
 * uniform {@code reader → processor → writer} chunk shape and to provide a traceable seam, so its
 * one behavioural contract is a strict identity transform.</p>
 *
 * <h2>REPRO parity contract (what this test guards)</h2>
 * <ul>
 *   <li><strong>Never returns {@code null}.</strong> In Spring Batch a {@code null} return filters
 *       the item out of the chunk; dropping a row here would omit it from the reloaded transaction
 *       master and break byte-equivalent parity with the legacy {@code REPRO} load.</li>
 *   <li><strong>Returns the same instance, unmutated.</strong> The exact managed entity the reader
 *       produced is re-persisted by the writer, so the processor must neither copy nor mutate it.</li>
 * </ul>
 *
 * <p>No Spring context, database, or network is used, so the suite is fast and compiles
 * warning-free under {@code -Xlint:all} (Gate&nbsp;2), contributing to Gate&nbsp;8 coverage.</p>
 *
 * @see CombineTransactionProcessor
 */
@DisplayName("CombineTransactionProcessor — REPRO-parity identity pass-through")
class CombineTransactionProcessorTest {

    /** Stateless processor under test. */
    private final CombineTransactionProcessor processor = new CombineTransactionProcessor();

    @Test
    @DisplayName("returns the exact same Transaction instance (identity, never a copy)")
    void returnsSameInstance() {
        final Transaction input = new Transaction();
        input.setTranId("0000000000000042");

        final Transaction output = processor.process(input);

        // Same-instance identity is the REPRO parity contract: the writer re-persists the exact
        // record the reader produced.
        assertThat(output).isSameAs(input);
    }

    @Test
    @DisplayName("never returns null (a null return would drop the row from the reloaded master)")
    void neverReturnsNull() {
        final Transaction input = new Transaction();
        input.setTranId("0000000000000001");

        assertThat(processor.process(input)).isNotNull();
    }

    @Test
    @DisplayName("does not mutate the item's fields")
    void doesNotMutateItem() {
        final Transaction input = new Transaction();
        input.setTranId("0000000000000007");
        input.setTranAmt(new BigDecimal("123.45"));
        input.setTranCardNum("4111111111111111");

        final Transaction output = processor.process(input);

        // Every field the reader set survives the pass-through unchanged.
        assertThat(output.getTranId()).isEqualTo("0000000000000007");
        assertThat(output.getTranAmt()).isEqualByComparingTo("123.45");
        assertThat(output.getTranCardNum()).isEqualTo("4111111111111111");
    }
}

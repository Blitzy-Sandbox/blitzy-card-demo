package com.carddemo.unit.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.batch.processors.CombineTransactionsProcessor;
import com.carddemo.model.entity.Transaction;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CombineTransactionsProcessor}.
 *
 * <p>Traceability (REFERENCE-ONLY, COBOL not copied; source commit {@code 27d6c6f}):
 * the processor models the per-record leg of the mainframe combine job
 * {@code app/jcl/COMBTRAN.jcl} &mdash; {@code STEP05R} ({@code SORT FIELDS=(TRAN-ID,A)})
 * followed by {@code STEP10} ({@code IDCAMS REPRO}). {@code REPRO} copies every record
 * verbatim, so {@code process} is a faithful identity pass-through that mutates nothing and
 * drops nothing, even when the {@code TRAN-ID} sort key is missing. The ascending ordering
 * contract is exposed once as {@link CombineTransactionsProcessor#BY_TRAN_ID}.</p>
 */
@DisplayName("CombineTransactionsProcessor - COMBTRAN identity pass-through and sort contract")
class CombineTransactionsProcessorTest {

    private SimpleMeterRegistry registry;
    private CombineTransactionsProcessor processor;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        processor = new CombineTransactionsProcessor(registry);
    }

    private static Transaction transactionWithId(String tranId) {
        Transaction transaction = new Transaction();
        transaction.setTranId(tranId);
        return transaction;
    }

    @Test
    @DisplayName("returns the same instance unchanged and counts the record")
    void identityPassThrough() {
        Transaction input = transactionWithId("0000000000000001");

        Transaction result = processor.process(input);

        assertThat(result).isSameAs(input);
        assertThat(result.getTranId()).isEqualTo("0000000000000001");
        assertThat(registry.counter("carddemo.batch.records.processed").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("passes through a record with a blank TRAN-ID without dropping it")
    void blankKeyStillPassesThrough() {
        Transaction blank = transactionWithId("   ");

        Transaction result = processor.process(blank);

        assertThat(result).isSameAs(blank);
        assertThat(registry.counter("carddemo.batch.records.processed").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("passes through a record with a null TRAN-ID without dropping it")
    void nullKeyStillPassesThrough() {
        Transaction noKey = transactionWithId(null);

        Transaction result = processor.process(noKey);

        assertThat(result).isSameAs(noKey);
        assertThat(registry.counter("carddemo.batch.records.processed").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("BY_TRAN_ID orders ascending by the TRAN-ID key")
    void sortContractIsAscendingByTranId() {
        Transaction low = transactionWithId("0000000000000001");
        Transaction high = transactionWithId("0000000000000009");

        assertThat(CombineTransactionsProcessor.BY_TRAN_ID.compare(low, high)).isNegative();
        assertThat(CombineTransactionsProcessor.BY_TRAN_ID.compare(high, low)).isPositive();
        assertThat(CombineTransactionsProcessor.BY_TRAN_ID.compare(low, low)).isZero();
    }
}

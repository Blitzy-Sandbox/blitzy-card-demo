package com.carddemo.unit.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.batch.processors.CombineTransactionsProcessor;
import com.carddemo.model.entity.Transaction;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CombineTransactionsProcessor}, the combine-stage
 * {@link org.springframework.batch.item.ItemProcessor} that re-platforms the
 * {@code COMBTRAN} DFSORT + IDCAMS {@code REPRO} step (source commit
 * {@code 27d6c6f}).
 *
 * <p>The suite verifies the two binding contracts of that stage:</p>
 * <ul>
 *   <li><strong>Identity passthrough (REPRO fidelity)</strong> &mdash;
 *       {@link CombineTransactionsProcessor#process(Transaction)} returns the
 *       exact same instance it received, with no field mutated or re-scaled, so
 *       the transaction record contract is preserved verbatim; the
 *       processed-records counter is incremented once per record.</li>
 *   <li><strong>{@code SORT FIELDS=(TRAN-ID,A)} parity</strong> &mdash; the
 *       shared {@link CombineTransactionsProcessor#BY_TRAN_ID} comparator orders
 *       records ascending by the 16-character {@code TRAN-ID} key, identical to
 *       the DFSORT character ascending sort it replaces.</li>
 * </ul>
 *
 * <p>Pure-JVM test: a real {@link SimpleMeterRegistry} and plain entity POJOs
 * only, with no Spring context, Testcontainers, or AWS dependency.</p>
 */
class CombineTransactionsProcessorTest {

    private SimpleMeterRegistry registry;
    private CombineTransactionsProcessor processor;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        processor = new CombineTransactionsProcessor(registry);
    }

    /**
     * Builds a fully populated {@link Transaction} mirroring the COBOL
     * {@code TRAN-RECORD} layout (copybook {@code CVTRA05Y}), varying only the
     * ordering key and amount that the individual tests assert on.
     *
     * @param tranId the transaction id ({@code TRAN-ID})
     * @param amt    the transaction amount ({@code TRAN-AMT})
     * @return a populated transaction instance
     */
    private Transaction newTransaction(final String tranId, final BigDecimal amt) {
        final Transaction tx = new Transaction();
        tx.setTranId(tranId);
        tx.setTranTypeCd("01");
        tx.setTranCatCd(5);
        tx.setTranSource("POS TERM");
        tx.setTranDesc("RETAIL PURCHASE");
        tx.setTranAmt(amt);
        tx.setTranMerchantId(123456789L);
        tx.setTranMerchantName("ACME STORE");
        tx.setTranMerchantCity("SEATTLE");
        tx.setTranMerchantZip("98101");
        tx.setTranCardNum("4111111111111111");
        tx.setTranOrigTs("2022-07-19 23:16:01.000000");
        tx.setTranProcTs("2022-07-19 23:16:02.000000");
        return tx;
    }

    @Test
    void process_returnsSameInstanceUnchanged() {
        final Transaction in = newTransaction("00000000000012345", new BigDecimal("123.45"));

        final Transaction out = processor.process(in);

        assertThat(out).isSameAs(in);
        assertThat(out.getTranId()).isEqualTo("00000000000012345");
        assertThat(out.getTranTypeCd()).isEqualTo("01");
        assertThat(out.getTranCatCd()).isEqualTo(5);
        assertThat(out.getTranSource()).isEqualTo("POS TERM");
        assertThat(out.getTranCardNum()).isEqualTo("4111111111111111");
        // BigDecimal compared with compareTo semantics, never equals (AAP 0.8.2).
        assertThat(out.getTranAmt()).isEqualByComparingTo(new BigDecimal("123.45"));
    }

    @Test
    void process_doesNotAlterAmountScale() {
        final BigDecimal amount = new BigDecimal("100.00");

        final Transaction out = processor.process(newTransaction("0000000000000100", amount));

        // Passthrough preserves the value (compareTo, never equals); asserting
        // the very same BigDecimal flows through proves the scale is untouched.
        assertThat(out.getTranAmt()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(out.getTranAmt()).isSameAs(amount);
    }

    @Test
    void process_incrementsProcessedCounter() {
        processor.process(newTransaction("0000000000000001", new BigDecimal("10.00")));
        processor.process(newTransaction("0000000000000002", new BigDecimal("20.00")));
        processor.process(newTransaction("0000000000000003", new BigDecimal("30.00")));

        assertThat(registry.get("carddemo.batch.records.processed").counter().count())
                .isEqualTo(3.0);
    }

    @Test
    void byTranId_sortsAscendingLikeDfsortSortFields() {
        final Transaction t10 = newTransaction("0000000000000010", new BigDecimal("10.00"));
        final Transaction t2 = newTransaction("0000000000000002", new BigDecimal("2.00"));
        final Transaction t1 = newTransaction("0000000000000001", new BigDecimal("1.00"));
        final Transaction t20 = newTransaction("0000000000000020", new BigDecimal("20.00"));
        final List<Transaction> list = new ArrayList<>(List.of(t10, t2, t1, t20));

        // SORT FIELDS=(TRAN-ID,A): CH ascending on the 16-char fixed-width key == String.compareTo ascending
        list.sort(CombineTransactionsProcessor.BY_TRAN_ID);

        final List<String> ids = list.stream().map(Transaction::getTranId).toList();
        assertThat(ids).containsExactly(
                "0000000000000001",
                "0000000000000002",
                "0000000000000010",
                "0000000000000020");
    }

    @Test
    void byTranId_isStableForEqualKeys() {
        final Transaction first = newTransaction("0000000000000007", new BigDecimal("70.00"));
        final Transaction second = newTransaction("0000000000000007", new BigDecimal("99.99"));
        final List<Transaction> list = new ArrayList<>(List.of(first, second));

        list.sort(CombineTransactionsProcessor.BY_TRAN_ID);

        // List.sort is stable: records sharing a TRAN-ID retain their input order.
        // Reference identity is required here because Transaction.equals keys on
        // tranId only, so the two equal-key instances are otherwise indistinct.
        assertThat(list.get(0)).isSameAs(first);
        assertThat(list.get(1)).isSameAs(second);
    }
}

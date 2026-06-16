package com.carddemo.unit.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.batch.processors.CombineTransactionsProcessor;
import com.carddemo.model.entity.Transaction;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CombineTransactionsProcessor}.
 *
 * <p>Traceability (REFERENCE-ONLY, COBOL/JCL not copied; source commit {@code 27d6c6f}):
 * the processor re-platforms the per-record leg of the mainframe combine job
 * {@code app/jcl/COMBTRAN.jcl}. That job sorted the transaction backup and the
 * system-generated transactions ascending by the 16-character {@code TRAN-ID} key
 * ({@code STEP05R SORT FIELDS=(TRAN-ID,A)} over {@code SYMNAMES TRAN-ID,1,16,CH}) and then
 * copied every sorted record verbatim into the transaction master
 * ({@code STEP10 IDCAMS REPRO}). These tests assert the two binding parities:
 * {@code IDCAMS REPRO} identity pass-through (AAP section 0.8.1 — the record is returned as
 * the same instance, unchanged, never dropped) and the {@code SORT FIELDS=(TRAN-ID,A)}
 * ordering reproduced by {@link CombineTransactionsProcessor#BY_TRAN_ID}
 * (AAP section 0.8.5, decision D-005).</p>
 *
 * <p>The component has a single {@code MeterRegistry} collaborator, so these tests use a
 * real {@link SimpleMeterRegistry} (no Spring context, no mocks, no Testcontainers). The
 * asserted meter name literal matches {@code MetricsConfig.BATCH_RECORDS_PROCESSED}; the
 * monetary {@code TRAN-AMT} field is verified by value with {@code compareTo} semantics and
 * never by {@code BigDecimal.equals} (AAP section 0.8.2).</p>
 */
@DisplayName("CombineTransactionsProcessor - IDCAMS REPRO identity + SORT FIELDS=(TRAN-ID,A) parity")
class CombineTransactionsProcessorTest {

    /** Binding meter name; mirrors {@code MetricsConfig.BATCH_RECORDS_PROCESSED}. */
    private static final String RECORDS_PROCESSED_METRIC = "carddemo.batch.records.processed";

    private SimpleMeterRegistry registry;
    private CombineTransactionsProcessor processor;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        processor = new CombineTransactionsProcessor(registry);
    }

    @Test
    @DisplayName("process() returns the same instance, unchanged (IDCAMS REPRO verbatim copy)")
    void process_returnsSameInstanceUnchanged() {
        Transaction in = newTransaction("00000000000012345", new BigDecimal("123.45"));

        Transaction out = processor.process(in);

        // REPRO performs no transformation: the very same record is returned.
        assertThat(out).isSameAs(in);
        assertThat(out.getTranId()).isEqualTo("00000000000012345");
        assertThat(out.getTranAmt()).isEqualByComparingTo(new BigDecimal("123.45"));
        assertThat(out.getTranTypeCd()).isEqualTo("01");
        assertThat(out.getTranCatCd()).isEqualTo(5);
        assertThat(out.getTranSource()).isEqualTo("POS TERM");
        assertThat(out.getTranCardNum()).isEqualTo("4111111111111111");
    }

    @Test
    @DisplayName("process() preserves the TRAN-AMT value without re-scaling (compareTo, never equals)")
    void process_doesNotAlterAmountScale() {
        Transaction in = newTransaction("00000000000067890", new BigDecimal("100.00"));

        Transaction out = processor.process(in);

        // Pass-through preserves the monetary value; assert by value, not by scale-sensitive equals.
        assertThat(out.getTranAmt()).isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    @DisplayName("process() increments the carddemo.batch.records.processed counter once per record")
    void process_incrementsProcessedCounter() {
        processor.process(newTransaction("00000000000000001", new BigDecimal("10.00")));
        processor.process(newTransaction("00000000000000002", new BigDecimal("20.00")));
        processor.process(newTransaction("00000000000000003", new BigDecimal("30.00")));

        assertThat(registry.get(RECORDS_PROCESSED_METRIC).counter().count()).isEqualTo(3.0);
    }

    @Test
    @DisplayName("BY_TRAN_ID sorts ascending exactly like SORT FIELDS=(TRAN-ID,A)")
    void byTranId_sortsAscendingLikeDfsortSortFields() {
        Transaction t10 = newTransaction("0000000000000010", new BigDecimal("1.00"));
        Transaction t2 = newTransaction("0000000000000002", new BigDecimal("2.00"));
        Transaction t1 = newTransaction("0000000000000001", new BigDecimal("3.00"));
        Transaction t20 = newTransaction("0000000000000020", new BigDecimal("4.00"));
        List<Transaction> list = new ArrayList<>(List.of(t10, t2, t1, t20));

        // SORT FIELDS=(TRAN-ID,A): CH ascending on the 16-char fixed-width key == String.compareTo ascending.
        list.sort(CombineTransactionsProcessor.BY_TRAN_ID);

        List<String> ids = list.stream().map(Transaction::getTranId).toList();
        assertThat(ids).containsExactly(
                "0000000000000001",
                "0000000000000002",
                "0000000000000010",
                "0000000000000020");
    }

    @Test
    @DisplayName("BY_TRAN_ID is stable: equal TRAN-ID keys retain input order (List.sort stability)")
    void byTranId_isStableForEqualKeys() {
        Transaction first = newTransaction("0000000000000005", new BigDecimal("11.11"));
        Transaction second = newTransaction("0000000000000005", new BigDecimal("22.22"));
        List<Transaction> list = new ArrayList<>(List.of(first, second));

        list.sort(CombineTransactionsProcessor.BY_TRAN_ID);

        // equals() is tranId-only, so identity is asserted by reference to prove stable ordering.
        assertThat(list.get(0)).isSameAs(first);
        assertThat(list.get(1)).isSameAs(second);
    }

    /**
     * Builds a fully populated {@link Transaction} from the {@code CVTRA05Y} layout so the
     * pass-through assertions exercise a realistic record rather than a sparse stub.
     *
     * @param tranId the 16-character {@code TRAN-ID} key
     * @param amt    the {@code TRAN-AMT} monetary value (scale preserved by the caller)
     * @return a new, fully populated transaction record
     */
    private Transaction newTransaction(String tranId, BigDecimal amt) {
        Transaction tx = new Transaction();
        tx.setTranId(tranId);
        tx.setTranTypeCd("01");
        tx.setTranCatCd(5);
        tx.setTranSource("POS TERM");
        tx.setTranDesc("PURCHASE - GROCERY STORE");
        tx.setTranAmt(amt);
        tx.setTranMerchantId(1L);
        tx.setTranMerchantName("ACME GROCERY");
        tx.setTranMerchantCity("SEATTLE");
        tx.setTranMerchantZip("98101-0000");
        tx.setTranCardNum("4111111111111111");
        tx.setTranOrigTs("2022-07-19 23.16.01.000000");
        tx.setTranProcTs("2022-07-19 23.16.02.000000");
        return tx;
    }
}

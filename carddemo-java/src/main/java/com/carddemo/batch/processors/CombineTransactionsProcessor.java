package com.carddemo.batch.processors;

import com.carddemo.model.entity.Transaction;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Comparator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

/**
 * Identity {@link ItemProcessor} for the transaction combine stage of the daily
 * batch pipeline. Re-platforms the per-record handling of the mainframe combine
 * job {@code COMBTRAN.jcl} (source commit {@code 27d6c6f}) onto Spring Batch.
 *
 * <p>The original job ran two steps. {@code STEP05R EXEC PGM=SORT} merged the
 * current transaction backup with the system-generated transactions and ordered
 * the result ascending by the 16-character {@code TRAN-ID} key
 * ({@code SORT FIELDS=(TRAN-ID,A)}). {@code STEP10 EXEC PGM=IDCAMS} then issued a
 * {@code REPRO} that copied every sorted record into the transaction master.
 * {@code REPRO} applies no filtering and no transformation, so each combined
 * {@link Transaction} record is preserved verbatim.</p>
 *
 * <p>This component models the per-record leg of that copy as a faithful
 * identity pass-through: it returns the same {@link Transaction} instance it
 * receives, with every field unchanged, and never drops a record. The ascending
 * sort is owned by the combine job and the bulk insert that replaces
 * {@code REPRO} is owned by the combine writer; this processor performs neither
 * persistence nor mutation. The canonical sort contract {@link #BY_TRAN_ID} is
 * exposed here so the job and writer share a single definition of the ordering
 * key.</p>
 */
@Component
public class CombineTransactionsProcessor implements ItemProcessor<Transaction, Transaction> {

    /**
     * Canonical ascending ordering by the {@code TRAN-ID} key, exposed as a
     * single reusable contract so the combine job and writer reference one sort
     * definition rather than redeclaring it.
     */
    public static final Comparator<Transaction> BY_TRAN_ID =
            Comparator.comparing(Transaction::getTranId);

    private static final Logger LOGGER =
            LoggerFactory.getLogger(CombineTransactionsProcessor.class);

    private static final String RECORDS_PROCESSED_METRIC = "carddemo.batch.records.processed";

    private final MeterRegistry meterRegistry;

    /**
     * Creates the processor with the application meter registry used to count
     * the records copied through this stage.
     *
     * @param meterRegistry the Micrometer registry; must not be {@code null}
     */
    public CombineTransactionsProcessor(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * Returns the supplied combined transaction record unchanged, reproducing the
     * verbatim copy performed by the source {@code IDCAMS REPRO} step.
     *
     * <p>The {@code TRAN-ID} sort key is checked for presence; a missing or blank
     * key is logged as a warning, after which the record is still returned
     * unchanged so that no record is dropped. No field is re-mapped, re-scaled,
     * or reformatted, and the {@code BigDecimal} amount is left exactly as
     * received.</p>
     *
     * @param transaction the combined transaction record; never {@code null}
     *                    under the Spring Batch chunk contract
     * @return the same {@link Transaction} instance, unchanged
     */
    @Override
    public Transaction process(Transaction transaction) {
        String tranId = transaction.getTranId();
        if (tranId == null || tranId.isBlank()) {
            LOGGER.warn("Combined transaction record carries a missing or blank TRAN-ID sort key; "
                    + "passing it through unchanged to preserve verbatim copy semantics");
        }
        meterRegistry.counter(RECORDS_PROCESSED_METRIC).increment();
        return transaction;
    }
}

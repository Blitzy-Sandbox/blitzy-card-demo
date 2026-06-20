package com.carddemo.batch.processors;

import com.carddemo.model.entity.Transaction;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Comparator;
import java.util.Objects;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

/**
 * Per-record {@link ItemProcessor} for the transaction combine stage of the
 * batch pipeline (lineage: combine stage, source commit {@code 27d6c6f}).
 *
 * <p>The combine stage concatenates the current transaction backup with the
 * system-generated transactions, orders the merged stream ascending by
 * transaction id, and loads the result into the transaction master. Ordering is
 * performed by the combine job and the bulk load is performed by the combine
 * writer; the master load copies every record verbatim. This processor models
 * the per-record handling of that verbatim copy and is therefore a faithful
 * <strong>identity</strong> processor: each {@link Transaction} is returned
 * unchanged, with no field mutated, re-scaled, or reformatted, so the external
 * record contract is preserved exactly.</p>
 *
 * <h2>Behaviour</h2>
 * <ul>
 *   <li><strong>Identity passthrough</strong> &mdash; {@link #process(Transaction)}
 *       returns the same instance it received; no enrichment, deduplication, or
 *       business rule is applied, and the {@code tranAmt}
 *       {@link java.math.BigDecimal} value and scale are left untouched.</li>
 *   <li><strong>Defensive validation</strong> &mdash; the ordering key
 *       {@code tranId} is verified to be present (non-null, non-blank). A record
 *       missing its ordering key is a data-integrity fault and is surfaced as an
 *       {@link IllegalArgumentException} rather than being silently filtered;
 *       valid records are never dropped.</li>
 *   <li><strong>Metrics</strong> &mdash; the
 *       {@code carddemo.batch.records.processed} counter is incremented once per
 *       record passed through.</li>
 * </ul>
 *
 * <p>No repository or persistence interaction occurs here; loading the combined
 * records into the transaction master is the responsibility of the combine
 * writer.</p>
 */
@Component
public class CombineTransactionsProcessor implements ItemProcessor<Transaction, Transaction> {

    /**
     * Canonical name of the counter incremented for every combined transaction
     * record that passes through this processor. Matches the application-wide
     * meter name so the emitted time series is the one pre-registered by the
     * observability configuration.
     */
    private static final String METRIC_RECORDS_PROCESSED = "carddemo.batch.records.processed";

    /**
     * Canonical ascending comparator on the transaction id, the combine-stage
     * ordering key (a 16-character fixed-width string). Exposed as a single
     * shared definition so the combine job and writer reference one ordering
     * contract.
     */
    public static final Comparator<Transaction> BY_TRAN_ID =
            Comparator.comparing(Transaction::getTranId);

    /** Registry used to resolve and increment the processed-records counter. */
    private final MeterRegistry meterRegistry;

    /**
     * Creates the processor.
     *
     * @param meterRegistry the Micrometer registry used to record the
     *                      processed-records metric; must not be {@code null}
     */
    public CombineTransactionsProcessor(final MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
    }

    /**
     * Passes a combined transaction record through unchanged.
     *
     * <p>The record's ordering key ({@code tranId}) is validated as present;
     * when valid, the processed-records counter is incremented and the very same
     * instance is returned (identity), preserving every field exactly. The
     * record is never mutated and a valid record is never dropped.</p>
     *
     * @param tx the combined transaction record to process; must not be
     *           {@code null}
     * @return the same {@link Transaction} instance, unchanged
     * @throws IllegalArgumentException if {@code tx} is {@code null} or its
     *                                  ordering key {@code tranId} is missing or
     *                                  blank
     */
    @Override
    public Transaction process(final Transaction tx) {
        if (tx == null) {
            throw new IllegalArgumentException(
                    "Combined transaction record must not be null");
        }
        final String tranId = tx.getTranId();
        if (tranId == null || tranId.isBlank()) {
            throw new IllegalArgumentException(
                    "Combined transaction record is missing its ordering key (tranId)");
        }
        meterRegistry.counter(METRIC_RECORDS_PROCESSED).increment();
        return tx;
    }
}

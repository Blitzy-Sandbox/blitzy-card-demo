package com.cardemo.batch.processors;

import com.cardemo.model.entity.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import java.util.Comparator;

/**
 * Spring Batch processor for the transaction combine step — maps
 * {@code COMBTRAN.jcl} STEP05R (DFSORT) + STEP10 (IDCAMS REPRO).
 *
 * <p>This is the ONLY batch job in the CardDemo pipeline with NO
 * corresponding COBOL program. The original JCL is a pure utility
 * job that performs two operations:
 *
 * <ol>
 *   <li><strong>STEP05R — DFSORT:</strong> Merges two input datasets
 *       ({@code AWS.M2.CARDDEMO.TRANSACT.BKUP(0)} and
 *       {@code AWS.M2.CARDDEMO.SYSTRAN(0)}) and sorts the combined
 *       result by {@code TRAN-ID} — position 1, length 16, character
 *       type, ascending order ({@code SORT FIELDS=(TRAN-ID,A)}).</li>
 *   <li><strong>STEP10 — IDCAMS REPRO:</strong> Bulk loads the sorted
 *       {@code TRANSACT.COMBINED} dataset into the
 *       {@code TRANSACT.VSAM.KSDS} master file (conditional on
 *       STEP05R returning condition code 0).</li>
 * </ol>
 *
 * <p>In the Java migration, the heavy lifting — reading from two
 * sources, sorting via {@link #TRAN_ID_COMPARATOR}, and writing to
 * the PostgreSQL transactions table — is orchestrated by
 * {@code CombineTransactionsJob}. This processor acts as a
 * pass-through: every item read from the input sources passes
 * through unchanged. The static {@link #TRAN_ID_COMPARATOR} is
 * exposed for the job-level sort configuration.
 *
 * <p>COBOL source reference: {@code app/jcl/COMBTRAN.jcl}
 * (commit 27d6c6f) — 53 lines, no COBOL program.
 *
 * @see com.cardemo.model.entity.Transaction
 */
@Component
public class TransactionCombineProcessor implements ItemProcessor<Transaction, Transaction> {

    private static final Logger log = LoggerFactory.getLogger(TransactionCombineProcessor.class);

    /**
     * Comparator that sorts {@link Transaction} records by
     * {@code tranId} in ascending lexicographic order.
     *
     * <p>This replaces the DFSORT specification from COMBTRAN.jcl
     * STEP05R:
     * <pre>
     *   SYMNAMES: TRAN-ID,1,16,CH
     *   SORT FIELDS=(TRAN-ID,A)
     * </pre>
     *
     * <p>The COBOL {@code CH} (character) sort type maps directly to
     * Java {@link String#compareTo(String)} — lexicographic ascending
     * ordering. {@code TRAN-ID} is PIC X(16), a 16-character
     * alphanumeric field whose natural String ordering produces
     * identical sort results.
     *
     * <p>Used by {@code CombineTransactionsJob} at the step level to
     * sort the merged input before writing to the transaction table
     * (the REPRO equivalent).
     */
    public static final Comparator<Transaction> TRAN_ID_COMPARATOR =
            Comparator.comparing(Transaction::getTranId);

    /**
     * Processes a single {@link Transaction} item during the combine
     * step. This is a pass-through processor — the item is returned
     * unchanged. Sorting is handled at the Spring Batch step level
     * using {@link #TRAN_ID_COMPARATOR}.
     *
     * <p>The method logs each transaction at {@code DEBUG} level for
     * observability and traceability (structured logging with
     * correlation IDs per AAP requirements).
     *
     * @param item the transaction record read from a backup or
     *             system-generated source; must not be {@code null}
     * @return the same transaction item, unchanged
     * @throws Exception if an unexpected processing error occurs
     *                   (not expected for pass-through logic)
     */
    @Override
    public Transaction process(Transaction item) throws Exception {
        log.debug("Processing transaction for combine: {}", item.getTranId());
        return item;
    }
}

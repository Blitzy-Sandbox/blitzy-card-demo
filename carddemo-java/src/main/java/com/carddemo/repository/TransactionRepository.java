package com.carddemo.repository;

import com.carddemo.model.entity.Transaction;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the Transaction entity (re-platforms the VSAM
 * TRANSACT KSDS and its processing-timestamp alternate index). Provides keyed
 * read/save via inherited operations, paginated browse via inherited
 * {@code findAll(Pageable)} (ten rows per page), a maximum-id lookup for
 * transaction-id generation, and an inclusive processing-date-range query.
 */
@Repository
public interface TransactionRepository extends JpaRepository<Transaction, String> {

    /**
     * Returns the highest existing transaction id, or {@code null} when no
     * transactions exist. Ids are fixed-width, zero-padded numeric strings, so
     * lexicographic maximum equals numeric maximum. The caller is responsible
     * for seeding/incrementing to produce the next id.
     */
    @Query("SELECT MAX(t.tranId) FROM Transaction t")
    String findMaxTranId();

    /**
     * Returns the transactions for the supplied set of card numbers, ordered by
     * card number then transaction id. Re-platforms the statement-generation
     * transaction grouping of {@code CBSTM03A} ({@code 8500-READTRNX-READ} /
     * {@code 4000-TRNXFILE-GET}): instead of scanning the whole TRANSACT file in
     * memory, the {@code tran_card_num} alternate index ({@code idx_tran_card_num},
     * Flyway V2) is used to fetch only the rows belonging to the resolved cards,
     * already ordered for per-card aggregation.
     */
    List<Transaction> findByTranCardNumInOrderByTranCardNumAscTranIdAsc(
            Collection<String> cardNumbers);

    /**
     * Range query backing {@link #findByProcessingDateRange(String, String)}.
     * Compares the <em>whole</em> {@code tran_proc_ts} value (never a substring)
     * so the {@code idx_tran_proc_ts} btree index ({@code TRANSACT} processing
     * alternate key) is usable for the date-range / browse access path.
     */
    List<Transaction> findByTranProcTsBetweenOrderByTranIdAsc(String startInclusive,
                                                              String endInclusive);

    /**
     * Returns the transactions whose processing timestamp falls inclusively
     * within the supplied date range (dates in {@code YYYY-MM-DD} form), ordered
     * by id.
     *
     * <p>The bounds are deliberately <em>asymmetric</em> so a single predicate on
     * the full {@code tran_proc_ts} column covers the three timestamp formats
     * persisted across the application without defeating the btree index:
     * <ul>
     *   <li>dash-dot {@code yyyy-MM-dd-HH.mm.ss.SSSSSS} (transaction posting),</li>
     *   <li>space-colon {@code yyyy-MM-dd HH:mm:ss.SSSSSS} (bill payment), and</li>
     *   <li>date-only {@code yyyy-MM-dd} (online transaction add).</li>
     * </ul>
     * The bare start date is lexicographically &le; the lowest possible timestamp
     * for that day in every format (a bare date is a prefix of, and therefore less
     * than, any same-day timestamp), and the end date suffixed with the maximal
     * time component {@code -99.99.99.999999} is &ge; the highest possible
     * timestamp for that day in every format. This preserves the inclusive
     * semantics of the original substring query while remaining index-friendly.
     */
    default List<Transaction> findByProcessingDateRange(String startDate, String endDate) {
        return findByTranProcTsBetweenOrderByTranIdAsc(startDate, endDate + "-99.99.99.999999");
    }
}

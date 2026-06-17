package com.carddemo.repository;

import com.carddemo.model.entity.Transaction;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the Transaction entity (re-platforms the VSAM
 * TRANSACT KSDS and its processing-timestamp alternate index). Provides keyed
 * read/save via inherited operations, paginated browse via inherited
 * {@code findAll(Pageable)} (ten rows per page), a maximum-id lookup for
 * transaction-id generation, and an inclusive processing-date-range query.
 *
 * <p>The {@link TransactionRepositoryCustom} fragment adds an explicit insert-only
 * {@code insertNew(...)} operation. Because {@code Transaction} has an
 * application-assigned id and no {@code @Version}, the inherited {@code save(...)}
 * follows JPA's merge path (select-then-insert-or-update), which under concurrent
 * same-id adds could silently overwrite the winning row; {@code insertNew(...)}
 * forces a true {@code INSERT} so a collision is rejected and can be retried.</p>
 */
@Repository
public interface TransactionRepository
        extends JpaRepository<Transaction, String>, TransactionRepositoryCustom {

    /**
     * Returns the highest existing transaction id, or {@code null} when no
     * transactions exist. Ids are fixed-width, zero-padded numeric strings, so
     * lexicographic maximum equals numeric maximum. The caller is responsible
     * for seeding/incrementing to produce the next id.
     */
    @Query("SELECT MAX(t.tranId) FROM Transaction t")
    String findMaxTranId();

    /**
     * Returns a single page of transactions whose id is greater than or equal to
     * the supplied start key, ordered as requested by the {@link Pageable}.
     *
     * <p>Re-platforms the keyed forward browse of {@code COTRN00C} (transaction
     * list, transaction {@code CT00}): the CICS {@code STARTBR} on the TRANSACT
     * dataset with {@code RIDFLD = TRAN-ID} positions at the first record whose
     * key is greater than or equal to the entered transaction id (GTEQ), and the
     * subsequent {@code READNEXT} loop reads forward from that position. Because
     * {@code tranId} is a fixed-width, zero-padded numeric string, the
     * lexicographic {@code >=} comparison preserves the VSAM key-ascending browse
     * order. The caller supplies an ascending {@code tranId} sort and the
     * ten-rows-per-page size; when no start key is supplied the caller uses the
     * inherited {@code findAll(Pageable)} to browse from the lowest key.</p>
     */
    Page<Transaction> findByTranIdGreaterThanEqual(String tranId, Pageable pageable);

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

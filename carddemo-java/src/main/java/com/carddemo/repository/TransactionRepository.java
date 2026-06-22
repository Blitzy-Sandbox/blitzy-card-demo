package com.carddemo.repository;

import com.carddemo.model.entity.Transaction;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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
     * Returns one page of transactions whose id is greater than or equal to {@code tranIdStart},
     * ordered by the {@link Pageable}'s sort (ascending transaction id for the browse). This is the
     * "start-at" filter of the COBOL transaction-list program {@code COTRN00C}: its
     * {@code STARTBR-TRANSACT-FILE} positions the CICS browse on the entered transaction id with
     * {@code GTEQ} (greater-than-or-equal) and the {@code READNEXT} loop then reads forward, so the
     * list begins at the supplied id (or the next existing id when an exact match is absent) rather
     * than performing an exact-match lookup. Transaction ids are fixed-width, zero-padded numeric
     * strings, so the lexicographic {@code >=} comparison is identical to a numeric {@code >=}
     * comparison (the caller left-pads a shorter filter to the stored width before invoking this).
     *
     * @param tranIdStart the inclusive lower-bound transaction id (the start-at key)
     * @param pageable    the page request (page index, fixed page size, and ascending-id sort)
     * @return the page of transactions with id {@code >= tranIdStart}
     */
    Page<Transaction> findByTranIdGreaterThanEqual(String tranIdStart, Pageable pageable);

    /**
     * Returns the transactions whose processing-timestamp date portion (the first ten characters
     * of {@code tranProcTs}) falls inclusively between the supplied start and end dates (format
     * {@code YYYY-MM-DD}), ordered ascending by id.
     *
     * <p>The inclusive {@code [startDate, endDate]} contract is unchanged, but the predicate is now
     * expressed as a <strong>sargable raw-column range</strong> so the query can use the
     * {@code idx_tran_proc_ts} B-tree index (created in {@code V2__create_indexes.sql}) instead of
     * scanning the whole table. {@code tran_proc_ts} is an ISO-8601 timestamp whose first ten
     * characters are the calendar date, so the column is lexicographically ordered by date; a
     * string range over the raw column is therefore exactly equivalent to a range over the date
     * portion. The earlier {@code SUBSTRING(tran_proc_ts, 1, 10)} form wrapped the indexed column
     * in a function and was non-sargable, forcing a (parallel) sequential scan whose cost grew
     * linearly with the table.</p>
     *
     * <p>The inclusive upper bound is translated to a half-open exclusive bound
     * ({@code endDate + 1 day}): every timestamp on {@code endDate} sorts strictly before
     * {@code endDate + 1 day}, so {@code tran_proc_ts < endExclusive} selects exactly the rows
     * whose date is {@code <= endDate}; likewise {@code tran_proc_ts >= startDate} selects exactly
     * the rows whose date is {@code >= startDate} (any timestamp on {@code startDate} sorts at or
     * after the bare {@code startDate} prefix). The two bounds together reproduce the original
     * inclusive window with identical results. Delegates to
     * {@link #findByProcessingTimestampRange(String, String)}.</p>
     *
     * @param startDate inclusive lower bound, calendar date in {@code YYYY-MM-DD} form
     * @param endDate   inclusive upper bound, calendar date in {@code YYYY-MM-DD} form
     * @return the in-window transactions ordered ascending by transaction id
     */
    default List<Transaction> findByProcessingDateRange(String startDate, String endDate) {
        // Convert the inclusive end date to an exclusive next-day bound so the backing query is a
        // plain ">= start AND < endExclusive" range over the raw, indexed column (sargable). endDate
        // is the documented YYYY-MM-DD calendar date; LocalDate renders the next day in the same
        // ISO-8601 format, which is exactly what the lexicographic string comparison requires.
        String endExclusive = LocalDate.parse(endDate).plusDays(1).toString();
        return findByProcessingTimestampRange(startDate, endExclusive);
    }

    /**
     * Returns the transactions whose processing timestamp falls in the half-open range
     * {@code [startInclusive, endExclusive)}, ordered ascending by transaction id. This is the
     * sargable, index-using ({@code idx_tran_proc_ts}) backing query for
     * {@link #findByProcessingDateRange(String, String)}; callers should normally use that
     * inclusive-date wrapper rather than this method directly. Both bounds are compared against the
     * raw {@code tran_proc_ts} string column, which is lexicographically ordered by date because it
     * is an ISO-8601 timestamp, so a string range equals a date range.
     *
     * @param startInclusive inclusive lower bound compared against {@code tran_proc_ts}
     * @param endExclusive   exclusive upper bound compared against {@code tran_proc_ts}
     * @return the matching transactions ordered ascending by transaction id
     */
    @Query("""
            SELECT t FROM Transaction t
            WHERE t.tranProcTs >= :startInclusive
              AND t.tranProcTs < :endExclusive
            ORDER BY t.tranId
            """)
    List<Transaction> findByProcessingTimestampRange(@Param("startInclusive") String startInclusive,
                                                      @Param("endExclusive") String endExclusive);

    /**
     * Returns the transactions belonging to any of the supplied card numbers. Used by statement
     * generation ({@code CBSTM03A} per-card transaction gather) to push the card-set filter down to
     * PostgreSQL as a single indexed {@code IN} query, replacing a per-account full-table scan. The
     * caller imposes the deterministic card-number-then-transaction-id order that reproduces the
     * original sequential {@code TRNXFILE} (card + transaction-id key) ordering.
     *
     * @param cardNumbers the card numbers to match (the account's cross-referenced cards)
     * @return the matching transactions (the caller applies the card-then-id ordering)
     */
    List<Transaction> findByTranCardNumIn(Collection<String> cardNumbers);
}

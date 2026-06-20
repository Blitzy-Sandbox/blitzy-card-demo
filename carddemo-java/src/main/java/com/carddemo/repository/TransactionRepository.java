package com.carddemo.repository;

import com.carddemo.model.entity.Transaction;
import java.util.Collection;
import java.util.List;
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
     * Returns the transactions whose processing-timestamp date portion (the
     * first ten characters of {@code tranProcTs}) falls inclusively between the
     * supplied start and end dates (format {@code YYYY-MM-DD}), ordered by id.
     */
    @Query("""
            SELECT t FROM Transaction t
            WHERE SUBSTRING(t.tranProcTs, 1, 10) >= :startDate
              AND SUBSTRING(t.tranProcTs, 1, 10) <= :endDate
            ORDER BY t.tranId
            """)
    List<Transaction> findByProcessingDateRange(@Param("startDate") String startDate,
                                                @Param("endDate") String endDate);

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

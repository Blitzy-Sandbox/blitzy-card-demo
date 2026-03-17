/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License").
 *
 * CardDemo Application — Transaction Repository
 * Migrated from COBOL TRANSACT.VSAM.KSDS dataset access patterns.
 * COBOL source reference: app/jcl/TRANFILE.jcl, app/cpy/CVTRA05Y.cpy (commit 27d6c6f)
 */
package com.cardemo.repository;

import com.cardemo.model.entity.Transaction;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for the {@link Transaction} entity, replacing all
 * VSAM keyed access patterns for the {@code TRANSACT.VSAM.KSDS} dataset.
 *
 * <h2>Source VSAM Dataset</h2>
 * <pre>
 * DEFINE CLUSTER (NAME(AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS)
 *     KEYS(16 0)              — 16-byte transaction ID primary key at offset 0
 *     RECORDSIZE(350 350)     — fixed 350-byte record
 *     SHAREOPTIONS(2 3)
 *     INDEXED)
 *
 * DEFINE ALTERNATEINDEX (NAME(AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX)
 *     RELATE(AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS)
 *     KEYS(26 304)            — 26-byte processed timestamp AIX (NONUNIQUEKEY)
 *     NONUNIQUEKEY
 *     UPGRADE)
 * </pre>
 *
 * <h2>COBOL Access Patterns Mapped</h2>
 * <ul>
 *   <li>{@code COTRN00C.cbl} — STARTBR/READNEXT on TRANSACT (paginated browse, 10 rows/page)
 *       → {@link #findByTranCardNum(String, Pageable)},
 *         {@link #findByTranIdGreaterThanEqual(String, Pageable)}</li>
 *   <li>{@code COTRN01C.cbl} — READ TRANSACT (single keyed read by transaction ID)
 *       → inherited {@code findById(String)}</li>
 *   <li>{@code COTRN02C.cbl} — STARTBR/READPREV TRANSACT (browse-to-end for auto-ID generation)
 *       + WRITE TRANSACT (new transaction)
 *       → {@link #findMaxTransactionId()}, inherited {@code save(Transaction)}</li>
 *   <li>{@code CBTRN02C.cbl} — WRITE TRANSACT (batch posting from daily transactions)
 *       → inherited {@code save(Transaction)}, {@code saveAll(Iterable)}</li>
 *   <li>{@code CBTRN03C.cbl} — Date-filtered read for transaction reporting
 *       → {@link #findByTranOrigTsBetween(LocalDateTime, LocalDateTime)}</li>
 *   <li>{@code CBSTM03A.CBL} — Read transactions for statement generation
 *       → {@link #findByTranCardNum(String)} (non-paginated)</li>
 * </ul>
 *
 * <h2>Inherited JpaRepository Methods</h2>
 * <p>The following methods are inherited from {@link JpaRepository} and replace
 * the corresponding COBOL VSAM I/O operations:
 * <ul>
 *   <li>{@code findById(String)} — replaces COBOL {@code READ TRANSACT}</li>
 *   <li>{@code findAll()} — replaces COBOL sequential browse</li>
 *   <li>{@code findAll(Pageable)} — replaces COBOL STARTBR/READNEXT with pagination</li>
 *   <li>{@code save(Transaction)} — replaces COBOL {@code WRITE/REWRITE TRANSACT}</li>
 *   <li>{@code saveAll(Iterable)} — bulk batch posting</li>
 *   <li>{@code deleteById(String)} — replaces COBOL {@code DELETE TRANSACT}</li>
 *   <li>{@code deleteAll()} — administrative purge</li>
 *   <li>{@code count()} — record count utility</li>
 *   <li>{@code existsById(String)} — existence check utility</li>
 * </ul>
 *
 * @see Transaction
 */
@Repository
public interface TransactionRepository extends JpaRepository<Transaction, String> {

    // -----------------------------------------------------------------------
    // Paginated Browse Methods — Maps COBOL STARTBR/READNEXT Patterns
    // -----------------------------------------------------------------------

    /**
     * Paginated transaction list filtered by card number.
     *
     * <p>Maps the COBOL {@code COTRN00C.cbl} STARTBR/READNEXT browse pattern
     * on the TRANSACT VSAM dataset, which displays 10 transactions per page
     * filtered by the card number associated with the transaction.
     *
     * <p>The VSAM alternate index (AIX) on card number is modeled as a
     * database index ({@code @Index(name = "idx_tran_card_num")}) on the
     * Transaction entity's {@code card_num} column, ensuring query performance.
     *
     * @param cardNum  the card number to filter transactions (16 chars, PIC X(16))
     * @param pageable pagination and sorting parameters (default 10 rows/page)
     * @return a {@link Page} of transactions matching the card number with page metadata
     */
    Page<Transaction> findByTranCardNum(String cardNum, Pageable pageable);

    /**
     * Paginated transaction browse starting from a given transaction ID.
     *
     * <p>Maps the COBOL {@code COTRN00C.cbl} STARTBR pattern where the browse
     * begins at a specified transaction ID and reads forward (READNEXT).
     * In COBOL, {@code STARTBR} with a specific key positions the cursor
     * at or after that key; this method provides equivalent semantics via
     * the {@code GreaterThanEqual} predicate on the primary key.
     *
     * @param tranId   the starting transaction ID (inclusive, 16 chars PIC X(16))
     * @param pageable pagination and sorting parameters
     * @return a {@link Page} of transactions with IDs greater than or equal to the given ID
     */
    Page<Transaction> findByTranIdGreaterThanEqual(String tranId, Pageable pageable);

    // -----------------------------------------------------------------------
    // Non-Paginated List Methods — Maps VSAM AIX and Batch Access
    // -----------------------------------------------------------------------

    /**
     * Non-paginated transaction lookup by card number.
     *
     * <p>Maps the TRANSACT VSAM alternate index (AIX) access pattern used in
     * batch processing contexts such as {@code CBSTM03A.CBL} (statement
     * generation) and {@code CBTRN03C.cbl} (transaction reporting), where
     * all transactions for a given card are needed without pagination overhead.
     *
     * <p>The VSAM AIX is defined as NONUNIQUEKEY, meaning multiple transactions
     * can exist for the same card number; hence the {@link List} return type.
     *
     * @param cardNum the card number to look up (16 chars, PIC X(16))
     * @return all transactions for the given card number, ordered by default
     */
    List<Transaction> findByTranCardNum(String cardNum);

    /**
     * Date-range transaction query for reporting.
     *
     * <p>Maps the {@code CBTRN03C.cbl} date-filtered read pattern used for
     * transaction reporting and the {@code TRANREPT.jcl} DFSORT date extraction.
     * The COBOL batch report program filters transactions by origination
     * timestamp to produce date-bounded reports.
     *
     * <p>Uses the entity's {@code tranOrigTs} field ({@link LocalDateTime}),
     * which maps the COBOL {@code TRAN-ORIG-TS PIC X(26)} timestamp field.
     *
     * @param startDate the inclusive start of the date range
     * @param endDate   the inclusive end of the date range
     * @return all transactions within the specified date range
     */
    List<Transaction> findByTranOrigTsBetween(LocalDateTime startDate, LocalDateTime endDate);

    // -----------------------------------------------------------------------
    // Custom JPQL Query — Maps COBOL Browse-to-End Auto-ID Pattern
    // -----------------------------------------------------------------------

    /**
     * Retrieves the maximum (highest) transaction ID in the database.
     *
     * <p><strong>CRITICAL</strong>: This method maps the COBOL {@code COTRN02C.cbl}
     * auto-ID generation pattern. In the original COBOL program, a new transaction
     * ID is generated by:
     * <ol>
     *   <li>Opening a browse cursor on TRANSACT with HIGH-VALUES key
     *       ({@code EXEC CICS STARTBR FILE('TRANSACT') RIDFLD(HIGH-VALUES)})</li>
     *   <li>Reading the previous record ({@code EXEC CICS READPREV}) to find
     *       the last (highest) transaction ID</li>
     *   <li>Incrementing the ID by 1 to produce the new transaction ID</li>
     * </ol>
     *
     * <p>The Java equivalent uses a JPQL {@code MAX()} aggregate query to find
     * the highest transaction ID, avoiding the need for cursor-based browsing.
     * The caller (typically {@code TransactionAddService}) then increments
     * the returned ID to generate the next sequential transaction ID.
     *
     * <p>Returns {@link Optional#empty()} when no transactions exist in the
     * database, allowing the caller to initialize the ID sequence from a
     * starting value.
     *
     * @return the maximum transaction ID, or empty if no transactions exist
     */
    @Query("SELECT MAX(t.tranId) FROM Transaction t")
    Optional<String> findMaxTransactionId();
}

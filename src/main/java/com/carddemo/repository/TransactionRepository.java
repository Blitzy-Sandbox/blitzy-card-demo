/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.carddemo.repository;

import com.carddemo.entity.Transaction;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the {@link Transaction} entity, modeling the
 * COBOL {@code TRAN-RECORD} layout from copybook {@code app/cpy/CVTRA05Y.cpy}
 * (record length 350, keyed on {@code TRAN-ID PIC X(16)}) at source commit
 * {@code 27d6c6f}.
 *
 * <p>This interface replaces the legacy VSAM {@code TRANSACT} KSDS (keyed on
 * {@code TRAN-ID}) and the sequential report read of the same dataset. The
 * {@code Transaction} table is the posted-transactions fact: rows are
 * insert-only, so the entity carries no {@code @Version} column and no
 * optimistic-locking concern applies here. The mainframe access mechanics map
 * to repository operations as follows:</p>
 *
 * <ul>
 *   <li>Keyed {@code READ} by {@code TRAN-ID} (program {@code COTRN01C} detail,
 *       {@code EXEC CICS READ DATASET(TRANSACT) RIDFLD(TRAN-ID)}) &rarr;
 *       inherited {@link JpaRepository#findById(Object)} with a {@code String}
 *       key matching the entity {@code @Id}.</li>
 *   <li>{@code WRITE} of a posted transaction (program {@code COTRN02C} add and
 *       the batch posting engine) &rarr; inherited
 *       {@link JpaRepository#save(Object)}.</li>
 *   <li>Unfiltered {@code STARTBR}/{@code READNEXT}/{@code READPREV} browse in
 *       {@code TRAN-ID} order (program {@code COTRN00C} list with a blank
 *       {@code TRNIDINI} filter, PF7/PF8 navigation) &rarr; inherited
 *       {@link JpaRepository#findAll(org.springframework.data.domain.Pageable)}
 *       with a caller-supplied {@code Sort.by("tranId")} to preserve the VSAM
 *       browse order; no dedicated finder is declared for the unfiltered
 *       list.</li>
 *   <li>Positioned {@code STARTBR}-at-{@code TRAN-ID} browse (program
 *       {@code COTRN00C} list with a numeric {@code TRNIDINI} filter:
 *       {@code MOVE TRNIDINI TO TRAN-ID}, {@code EXEC CICS STARTBR
 *       DATASET(TRANSACT) RIDFLD(TRAN-ID) GTEQ}, then {@code READNEXT}) &rarr;
 *       {@link #findByTranIdGreaterThanEqual(String, Pageable)}, which positions
 *       the key-sequenced browse at the first {@code TRAN-ID} greater than or
 *       equal to the supplied key and returns the requested page in the
 *       caller-supplied {@code Sort.by("tranId")} order.</li>
 *   <li>{@code STARTBR}+{@code READPREV}-to-end "find highest existing
 *       {@code TRAN-ID}" (program {@code COTRN02C}, used to auto-generate the
 *       next identifier) &rarr; {@link #findTopByOrderByTranIdDesc()}.</li>
 *   <li>By-card transaction read in {@code TRAN-ID} order (statement generation
 *       {@code CBSTM03A} via the {@code TRNX} card+tranid path) &rarr;
 *       {@link #findByCardNumOrderByTranIdAsc(String)}.</li>
 *   <li>Date-windowed sequential report read (program {@code CBTRN03C},
 *       {@code ORGANIZATION IS SEQUENTIAL}, filter
 *       {@code TRAN-PROC-TS (1:10) >= WS-START-DATE AND <= WS-END-DATE})
 *       &rarr; {@link #findByProcessingDateWindow(String, String)}.</li>
 * </ul>
 */
@Repository
public interface TransactionRepository extends JpaRepository<Transaction, String>, TransactionRepositoryCustom {

    /**
     * Returns every transaction for the given card number, ordered by
     * {@code TRAN-ID} ascending.
     *
     * <p>Models the by-card read used by statement generation
     * ({@code CBSTM03A}) and account/card transaction history, where the
     * {@code TRNX} access path is keyed on card number then {@code TRAN-ID}.
     * The derived property {@code CardNum} binds to the entity field
     * {@code cardNum} ({@code TRAN-CARD-NUM PIC X(16)}).</p>
     *
     * @param cardNum the card number ({@code TRAN-CARD-NUM})
     * @return the card's transactions ordered by {@code tranId} ascending,
     *         never {@code null}
     */
    List<Transaction> findByCardNumOrderByTranIdAsc(String cardNum);

    /**
     * Returns one page of transactions whose {@code TRAN-ID} is greater than or
     * equal to the supplied key, in the order carried by {@code pageable}.
     *
     * <p>Reproduces the positioned browse of the {@code COTRN00C} list screen
     * ({@code CT00}) when a numeric {@code TRNIDINI} filter is supplied:
     * {@code MOVE TRNIDINI OF COTRN0AI TO TRAN-ID} followed by
     * {@code EXEC CICS STARTBR DATASET(TRANSACT) RIDFLD(TRAN-ID)} with the
     * implied {@code GTEQ} positioning and {@code READNEXT} forward read. The
     * derived keyword {@code GreaterThanEqual} on the {@code TranId} property
     * binds to the entity {@code @Id} field {@code tranId}
     * ({@code TRAN-ID PIC X(16)}); the page index, size (the ten-row
     * {@code COTRN00} display array), and {@code Sort.by("tranId")} ascending
     * ordering are supplied by the caller through {@code pageable}, so the VSAM
     * key-sequenced browse order is preserved without a server-side cursor.</p>
     *
     * @param tranId   the inclusive lower-bound {@code TRAN-ID} positioning key
     *                 (sixteen-character, the legacy {@code STARTBR} {@code RIDFLD})
     * @param pageable the page index, size, and {@code tranId} sort to apply
     * @return the requested page of transactions positioned at or after
     *         {@code tranId}, never {@code null}
     */
    Page<Transaction> findByTranIdGreaterThanEqual(String tranId, Pageable pageable);

    /**
     * Returns the single highest-keyed transaction (maximum {@code TRAN-ID}),
     * if any exist.
     *
     * <p>Reproduces the {@code COTRN02C} "find last id" step
     * ({@code MOVE HIGH-VALUES TO TRAN-ID}, {@code STARTBR},
     * {@code READPREV}-to-end) that locates the highest existing identifier
     * before auto-generating the next one. The identifier arithmetic itself
     * lives in {@code TransactionAddService}, not in this repository.</p>
     *
     * @return the transaction with the greatest {@code tranId}, or an empty
     *         {@link Optional} when the table is empty
     */
    Optional<Transaction> findTopByOrderByTranIdDesc();

    /**
     * Returns the transactions whose processing date falls within the inclusive
     * window {@code [startDate, endDate]}, ordered by {@code TRAN-ID} ascending.
     *
     * <p>Models the {@code CBTRN03C} date-window report read, which scans the
     * sequential {@code TRANSACT} file in key order and retains a record when
     * {@code TRAN-PROC-TS (1:10) >= WS-START-DATE AND TRAN-PROC-TS (1:10) <=
     * WS-END-DATE}. The COBOL reference modification {@code (1:10)} selects the
     * leading {@code YYYY-MM-DD} date portion of the 26-character
     * {@code TRAN-PROC-TS} timestamp; the JPQL {@code SUBSTRING(t.procTs, 1,
     * 10)} reproduces this exactly (JPQL {@code SUBSTRING} is 1-based). A
     * derived method cannot express a substring comparison, so a parameterized
     * JPQL query is used; both bounds flow exclusively through bound
     * {@code @Param} parameters and are never concatenated into the query.</p>
     *
     * @param startDate the inclusive lower bound, format {@code YYYY-MM-DD}
     *                  ({@code WS-START-DATE})
     * @param endDate   the inclusive upper bound, format {@code YYYY-MM-DD}
     *                  ({@code WS-END-DATE})
     * @return the date-filtered transactions ordered by {@code tranId}
     *         ascending, never {@code null}
     */
    @Query("SELECT t FROM Transaction t "
         + "WHERE SUBSTRING(t.procTs, 1, 10) >= :startDate "
         + "AND SUBSTRING(t.procTs, 1, 10) <= :endDate "
         + "ORDER BY t.tranId ASC")
    List<Transaction> findByProcessingDateWindow(@Param("startDate") String startDate,
                                                 @Param("endDate") String endDate);
}

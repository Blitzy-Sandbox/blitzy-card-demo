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
package com.aws.carddemo.repository;

import com.aws.carddemo.entity.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link Transaction} entities — the Java
 * replacement for COBOL {@code EXEC CICS READ DATASET('TRANSACT')
 * RIDFLD(TRAN-ID)} from {@code app/cbl/COTRN01C.cbl}
 * {@code READ-TRANSACT-FILE} paragraph (lines 267–296) and the
 * {@code STARTBR / READNEXT} browse loop in
 * {@code app/cbl/COTRN00C.cbl} {@code PROCESS-PAGE-FORWARD} (lines 279–328).
 *
 * <h2>COBOL Provenance — COTRN01C.cbl §READ-TRANSACT-FILE</h2>
 *
 * <p>The original COBOL paragraph performs an {@code EXEC CICS READ}
 * against the {@code TRANSACT} VSAM KSDS file by the 16-character
 * {@code TRAN-ID} primary key. The CICS response code {@code WS-RESP-CD}
 * carries the lookup outcome:
 *
 * <ul>
 *   <li>{@code WS-RESP-CD = DFHRESP(NORMAL)} — record found,
 *       continue to populate the {@code COTRN1AO} BMS map</li>
 *   <li>{@code WS-RESP-CD = DFHRESP(NOTFND)} — transaction not found →
 *       {@code MOVE 'Transaction ID NOT found...' TO WS-MESSAGE} and
 *       fall through to {@code SEND-TRNVIEW-SCREEN} (line 285–288)</li>
 *   <li>{@code WS-RESP-CD = WHEN OTHER} — I/O error →
 *       {@code MOVE 'Unable to lookup Transaction...' TO WS-MESSAGE}
 *       (line 289–295)</li>
 * </ul>
 *
 * <p>The Spring Data JPA equivalent is {@link #findById(Object)}: returns
 * {@code Optional.of(transaction)} for the {@code NORMAL} case and
 * {@code Optional.empty()} for the {@code NOTFND} case. I/O errors surface
 * as {@link org.springframework.dao.DataAccessException} subclasses and
 * propagate up to the caller (the production
 * {@link com.aws.carddemo.service.TransactionDetailService} could translate
 * these into the COBOL {@code 'Unable to lookup Transaction...'}
 * equivalent reject message; the stub at this stage lets the exception
 * propagate to the controller layer for translation, mirroring the COBOL
 * {@code HANDLE ABEND} fallback).
 *
 * <h2>COBOL Provenance — COTRN00C.cbl §PROCESS-PAGE-FORWARD (paged list)</h2>
 *
 * <p>The Java migration of {@code COTRN00C.cbl} uses
 * {@link #findAll(Pageable)} (inherited from {@link JpaRepository}) as the
 * direct Java replacement for the {@code STARTBR-TRANSACT-FILE} +
 * {@code PERFORM UNTIL WS-IDX >= 11 OR TRANSACT-EOF OR ERR-FLG-ON}
 * loop (lines 281–303 of {@code COTRN00C.cbl}). The fixed
 * {@code PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > 10} bound
 * materialises as a Spring Data {@link Pageable} with page size 10 — see
 * {@link com.aws.carddemo.service.TransactionListService#PAGE_SIZE}.
 *
 * <p>The COBOL workflow does not implement explicit account-key or
 * card-key filtering on the {@code TRANSACT} browse — the operator
 * navigates by {@code TRAN-ID} alone via the {@code TRNIDINI} starting-
 * browse key. The Java migration introduces two derived filter query
 * methods ({@link #findByAccountId(String, Pageable)} and
 * {@link #findByCardNumber(String, Pageable)}) as documented Java-migration
 * additions (AAP §0.10.2 "All deviations from literal COBOL logic must be
 * documented with the original COBOL paragraph name and reason for
 * divergence"). Reason: the REST controller layer for the migrated
 * transaction-list endpoint exposes account and card filters as URL query
 * parameters, which is the natural REST equivalent of the BMS map-driven
 * starting-browse-key idiom. These filters keep the result set focused on
 * the operator's current account / card context without forcing a client-
 * side filter pass.
 *
 * <h2>Primary Key Type — String</h2>
 *
 * <p>The {@code Transaction} primary key {@link Transaction#getTransactionId()}
 * is a {@link String} (16-character) rather than a {@link Long} so that the
 * byte-for-byte VSAM key format is preserved across the migration. The COBOL
 * {@code TRAN-ID} field is {@code PIC X(16)} — a fixed-width alphanumeric
 * key whose values are not always pure numerics (interest transactions are
 * a 10-character PARM prefix concatenated with a 6-digit sequential suffix).
 * Using {@link String} avoids any conversion ambiguity.
 *
 * <h2>Design Note — Stub Status</h2>
 *
 * <p>This interface is a <strong>minimum-viable JPA repository</strong>
 * created to satisfy {@link com.aws.carddemo.service.TransactionDetailService}
 * and {@link com.aws.carddemo.service.TransactionListService} compilation
 * and the corresponding test suites. Subsequent migration agents
 * (REFACTOR flavor) will add additional custom query methods (e.g.,
 * {@code findAllByOriginTimestampBetween(...)} for the TRANREPT date-range
 * report), a {@code @Repository} stereotype annotation (if the project
 * policy requires explicit stereotype marking — Spring Data infers the
 * bean from the {@code JpaRepository} extension and a stereotype is not
 * strictly necessary), and any custom {@code @Modifying @Query} insert
 * that the COTRN02C transaction-add workflow requires.
 *
 * @see com.aws.carddemo.service.TransactionDetailService
 * @see com.aws.carddemo.service.TransactionListService
 * @see Transaction
 */
public interface TransactionRepository extends JpaRepository<Transaction, String> {

    /**
     * Page through {@link Transaction} rows whose {@code TRAN-CARD-NUM}
     * matches the supplied 16-character PAN.
     *
     * <p>Spring Data derives the JPQL {@code SELECT t FROM Transaction t
     * WHERE t.cardNumber = ?1} from this method name at proxy-creation time
     * (the property name {@code cardNumber} on {@link Transaction} matches
     * the {@code TRAN-CARD-NUM PIC X(16)} field from
     * {@code app/cpy/CVTRA05Y.cpy}).
     *
     * <h3>COBOL Provenance — Java-migration addition</h3>
     *
     * <p>The COBOL {@code COTRN00C.cbl} workflow does not contain an
     * explicit card-key filter — the operator navigates by {@code TRAN-ID}
     * via {@code TRNIDINI} starting-browse key. The Java migration adds
     * this method as documented Java-migration addition (AAP §0.10.2):
     * the REST controller surfaces a {@code ?card=NNNN...} query parameter
     * that maps onto this repository call when the request's
     * {@code cardNumberFilter} field is populated.
     *
     * @param cardNumber 16-character Visa-format PAN (e.g.
     *                   {@code "4111111111111101"}); must not be
     *                   {@code null}. Whitespace-padded values are passed
     *                   through verbatim (the database column is fixed
     *                   width).
     * @param pageable   pagination request (page index + page size). The
     *                   page size should be
     *                   {@link com.aws.carddemo.service.TransactionListService#PAGE_SIZE}
     *                   ({@code 10}) to match the COBOL
     *                   {@code WS-MAX-SCREEN-LINES} semantic.
     * @return a {@link Page} of {@link Transaction} rows matching the
     *         supplied card number; never {@code null}. Empty page when no
     *         rows match.
     */
    Page<Transaction> findByCardNumber(String cardNumber, Pageable pageable);

    /**
     * Page through {@link Transaction} rows whose underlying card belongs
     * to the supplied 11-character zero-padded account identifier.
     *
     * <p>The {@code Transaction} entity does <em>not</em> carry an
     * {@code accountId} column directly — {@code TRAN-CARD-NUM} is the
     * foreign key into {@code CARDDAT} ({@code CARD-NUM} PIC X(16)), and
     * {@code CARDDAT.CARD-ACCT-ID} is the foreign key into {@code ACCTDAT}
     * ({@code ACCT-ID} PIC 9(11)). This method therefore uses a custom
     * {@link Query @Query} that joins {@code transactions} to {@code cards}
     * on {@code card_number} and filters on {@code cards.account_id}. The
     * underlying table aliases match the entity property names so the
     * derived JPQL is portable across the Hibernate dialects used in the
     * migration test suite (H2 for unit tests, PostgreSQL for ITs).
     *
     * <h3>COBOL Provenance — Java-migration addition</h3>
     *
     * <p>The COBOL {@code COTRN00C.cbl} workflow does not contain an
     * explicit account-key filter. The Java migration adds this method as
     * a documented Java-migration addition (AAP §0.10.2): the REST
     * controller surfaces a {@code ?account=NNNN...} query parameter that
     * maps onto this repository call when the request's
     * {@code accountIdFilter} field is populated. This is the natural REST
     * equivalent of the {@code CARDDAT → TRANSACT} alternate-index walk
     * that operators would perform manually in the 3270 workflow when
     * researching transactions for a specific account.
     *
     * @param accountId 11-character zero-padded account identifier (e.g.
     *                  {@code "00000000010"}); must not be {@code null}.
     * @param pageable  pagination request (page index + page size). The
     *                  page size should be
     *                  {@link com.aws.carddemo.service.TransactionListService#PAGE_SIZE}
     *                  ({@code 10}) to match the COBOL
     *                  {@code WS-MAX-SCREEN-LINES} semantic.
     * @return a {@link Page} of {@link Transaction} rows whose card belongs
     *         to the supplied account ID; never {@code null}. Empty page
     *         when no rows match.
     */
    @Query("SELECT t FROM Transaction t WHERE t.cardNumber IN ("
            + "SELECT c.cardNumber FROM Card c WHERE c.accountId = :accountId)")
    Page<Transaction> findByAccountId(@Param("accountId") String accountId, Pageable pageable);

    /**
     * Look up the {@link Transaction} row with the highest (lexicographically
     * greatest) {@code TRAN-ID} primary key.
     *
     * <p>Java replacement for the COBOL TRAN-ID generation pattern used by
     * {@code app/cbl/COBIL00C.cbl} bill-payment workflow (lines 212–217) and
     * any other program that needs the "last assigned TRAN-ID" to compute
     * the next sequential ID:
     *
     * <pre>
     *   MOVE HIGH-VALUES TO TRAN-ID
     *   PERFORM STARTBR-TRANSACT-FILE
     *   PERFORM READPREV-TRANSACT-FILE
     *   PERFORM ENDBR-TRANSACT-FILE
     *   MOVE TRAN-ID     TO WS-TRAN-ID-NUM
     *   ADD 1 TO WS-TRAN-ID-NUM
     * </pre>
     *
     * <p>The COBOL pattern positions the VSAM browse at the highest possible
     * key ({@code HIGH-VALUES}), reads backwards via {@code READPREV} to
     * obtain the highest existing key, and then increments by one to produce
     * the next sequential TRAN-ID. Spring Data derives the equivalent JPQL
     * {@code SELECT t FROM Transaction t ORDER BY t.transactionId DESC} with
     * {@code LIMIT 1} from this method name; the returned {@link Optional}
     * matches the COBOL {@code DFHRESP(ENDFILE)} response (empty store)
     * versus {@code DFHRESP(NORMAL)} (last row available) distinction.
     *
     * <h3>Lexicographic vs Numeric Ordering</h3>
     *
     * <p>The {@code TRAN-ID PIC X(16)} field is a fixed-width 16-character
     * string. Because all TRAN-ID values in {@code dailytran.txt} and in any
     * record generated by the migrated code are <em>zero-padded</em> to 16
     * characters, lexicographic ordering coincides with numeric ordering for
     * the daily-transaction subset; this means {@code ORDER BY transactionId
     * DESC} returns the highest numeric ID as expected. Interest transactions
     * generated by {@code CBACT04C} use a PARM prefix (e.g.
     * {@code "2022071800"} + 6-digit suffix) so they sort lexicographically
     * but the daily-transaction sequence and the interest-transaction
     * sequence each maintain their own monotonically increasing zero-padded
     * subspaces; the bill-payment workflow operates exclusively on the
     * daily-transaction subspace because its read-then-write pattern targets
     * the full {@code TRANSACT} dataset and the highest key currently
     * in the bill-payment / daily-transaction subspace is what is needed.
     *
     * @return {@link Optional#of(Object)} carrying the {@link Transaction}
     *         row with the lexicographically greatest {@code transactionId}
     *         when at least one row exists in the {@code transactions}
     *         table; {@link Optional#empty()} when the table is empty
     *         (COBOL {@code DFHRESP(ENDFILE)} equivalent — see
     *         {@code app/cbl/COBIL00C.cbl} {@code READPREV-TRANSACT-FILE}
     *         paragraph lines 484–488).
     */
    Optional<Transaction> findTopByOrderByTransactionIdDesc();

    /**
     * Page through {@link Transaction} rows whose
     * {@link Transaction#getOriginTimestamp() originTimestamp} falls
     * within the supplied inclusive {@code [startTimestamp,
     * endTimestamp]} window.
     *
     * <p>Java replacement for the COBOL TRANREPT.jcl
     * {@code TRAN-ORIG-TS &gt;= WS-START-DATE AND TRAN-ORIG-TS &lt;=
     * WS-END-DATE} filter that the {@code CBTRN03C.cbl} transaction-
     * detail-report program applies before printing each transaction
     * record. Spring Data derives the equivalent JPQL
     * {@code SELECT t FROM Transaction t WHERE t.originTimestamp
     * BETWEEN :startTimestamp AND :endTimestamp} from this method name.
     *
     * <p>The {@code originTimestamp} column is
     * {@code CHAR(26) PIC X(26)} — a fixed-width ISO-style timestamp
     * string (e.g. {@code "2022-07-18-08.00.00.000000"}). Because the
     * format is sortable lexicographically by design, a JPQL
     * {@code BETWEEN} on this column produces the same row set as a
     * timestamp-typed range query, and both endpoints are inclusive
     * (matching the COBOL {@code >= ... AND <= ...} comparison).
     *
     * <p>Callers that want a date-only filter (no time-of-day) typically
     * pass {@code "2022-07-18-00.00.00.000000"} as the start and
     * {@code "2022-07-18-23.59.59.999999"} as the end. The
     * {@code TRANREPT.jcl} report-submission service computes these
     * boundary values from the operator-supplied YYYY-MM-DD date
     * strings.
     *
     * @param startTimestamp inclusive lower bound of the timestamp
     *                       range — must be a 26-character ISO
     *                       timestamp string; must not be
     *                       {@code null}.
     * @param endTimestamp   inclusive upper bound of the timestamp
     *                       range — must be a 26-character ISO
     *                       timestamp string; must not be
     *                       {@code null}.
     * @param pageable       pagination request (page index + page
     *                       size).
     * @return a {@link Page} of {@link Transaction} rows whose
     *         {@code originTimestamp} falls within the window; never
     *         {@code null}. Empty page when no rows match.
     */
    Page<Transaction> findByOriginTimestampBetween(String startTimestamp,
                                                  String endTimestamp,
                                                  Pageable pageable);

    /**
     * Sum the {@link Transaction#getAmount() amount} of every
     * {@link Transaction} whose {@code cardNumber} matches the
     * supplied 16-character PAN, grouped by
     * {@link Transaction#getTransactionCategoryCode()
     * transactionCategoryCode}.
     *
     * <p>Java replacement for the COBOL CBSTM03A.cbl per-card aggregation
     * pattern that totals each category's transactions before printing
     * the statement summary block. The COBOL paragraph uses a
     * {@code STARTBR / READNEXT WHILE TRAN-CARD-NUM = WS-CARD-NUM}
     * browse to accumulate category-specific totals into
     * {@code WS-CAT-TOT-PURCHASE}, {@code WS-CAT-TOT-CASH}, etc. Spring
     * Data drives the equivalent SQL aggregate query that returns one
     * row per distinct category code with the summed amount.
     *
     * <p>The query uses an explicit {@link Query @Query} JPQL string
     * (rather than a Spring Data derived method name) because Spring
     * Data does not support {@code GROUP BY} via method-name derivation
     * — only via explicit JPQL or native SQL.
     *
     * @param cardNumber 16-character Visa-format PAN (e.g.
     *                   {@code "4111111111111101"}); must not be
     *                   {@code null}.
     * @return a {@link List} of {@link CategoryAggregate} projections,
     *         one per distinct {@code transactionCategoryCode}, with
     *         the summed {@code amount}; never {@code null}. Empty
     *         list when no rows match.
     */
    @Query("SELECT t.transactionCategoryCode AS transactionCategoryCode, "
            + "SUM(t.amount) AS totalAmount "
            + "FROM Transaction t "
            + "WHERE t.cardNumber = :cardNumber "
            + "GROUP BY t.transactionCategoryCode")
    List<CategoryAggregate> sumAmountByCategoryForCard(@Param("cardNumber") String cardNumber);

    /**
     * Spring Data interface-based projection carrying one row of the
     * {@link #sumAmountByCategoryForCard(String)} aggregate result:
     * the transaction category code and the summed monetary amount.
     *
     * <p>Spring Data instantiates an automatic proxy that exposes these
     * two getters; callers do not implement this interface manually.
     */
    interface CategoryAggregate {

        /**
         * @return the 4-character {@code transactionCategoryCode}
         *         (PIC X(04) / CHAR(4)) on which this aggregate row
         *         is grouped.
         */
        String getTransactionCategoryCode();

        /**
         * @return the {@link BigDecimal} sum of the
         *         {@code amount NUMERIC(11,2)} column for the group;
         *         scale is preserved by the SQL aggregate.
         */
        BigDecimal getTotalAmount();
    }
}

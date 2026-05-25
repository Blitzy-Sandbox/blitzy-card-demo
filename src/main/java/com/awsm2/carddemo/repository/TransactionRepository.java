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
package com.awsm2.carddemo.repository;

import com.awsm2.carddemo.domain.Transaction;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for the {@link Transaction} entity &mdash; the
 * canonical transaction journal of the entire system.
 *
 * <p><strong>Replaces VSAM clusters:</strong></p>
 * <ul>
 *   <li>{@code AWS.M2.CARDDEMO.TRANSACT.VSAM.KSDS} ({@code KEYS(16 0)}) &mdash;
 *       primary key {@code TRAN-ID} at offset 0; replaced by the JPA
 *       {@code @Id} on {@code tran_id}.</li>
 *   <li>{@code AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX} ({@code KEYS(26 304)},
 *       {@code NONUNIQUEKEY}) &mdash; alternate index on
 *       {@code TRAN-PROC-TS} at offset 304; replaced by the composite
 *       database index
 *       {@code idx_transactions_card_proc_ts (tran_card_num, tran_proc_ts)}
 *       declared in {@code V005__create_transaction.sql}, plus the
 *       derived query
 *       {@link #findByTranCardNumAndTranProcTsBetween(String,
 *       LocalDateTime, LocalDateTime, Pageable)}.</li>
 * </ul>
 *
 * <p><strong>Source copybook:</strong> {@code app/cpy/CVTRA05Y.cpy}
 * ({@code TRAN-RECORD}, RECLN 350). <strong>Source JCL:</strong>
 * {@code app/jcl/TRANFILE.jcl} (IDCAMS {@code DEFINE CLUSTER} +
 * {@code DEFINE AIX} + {@code DEFINE PATH} + {@code BLDINDEX}).</p>
 *
 * <h2>Three Critical Access Patterns</h2>
 * <ol>
 *   <li><strong>Paged browse</strong> ({@code COTRN00C.cbl}, 10
 *       rows/page per {@code app/bms/COTRN00.bms}) &mdash; see
 *       {@link #findByOrderByTranIdAsc(Pageable)}.</li>
 *   <li><strong>MAX-TRAN-ID generation</strong> ({@code COTRN02C.cbl}
 *       L444-L449: {@code STARTBR} / {@code READPREV} / {@code ENDBR}
 *       + {@code ADD 1 TO WS-TRAN-ID-N}) &mdash; see
 *       {@link #findTopByOrderByTranIdDesc()}.</li>
 *   <li><strong>Date-window report</strong> ({@code CBTRN03C.cbl}
 *       L173-L178: {@code IF TRAN-PROC-TS (1:10) >= WS-START-DATE
 *       AND <= WS-END-DATE}) &mdash; see
 *       {@link #findByTranCardNumAndTranProcTsBetween(String,
 *       LocalDateTime, LocalDateTime, Pageable)}.</li>
 * </ol>
 *
 * <h2>Monetary Precision (AAP &sect;0.6.1)</h2>
 *
 * <p>{@code tranAmt} is {@code BigDecimal} mapped to
 * {@code NUMERIC(11,2)} &mdash; exactly preserving the COBOL
 * {@code TRAN-AMT PIC S9(09)V99} precision. All arithmetic in service
 * code uses {@code RoundingMode.HALF_EVEN}; the repository performs no
 * arithmetic.</p>
 *
 * <h2>Event Publication (AAP &sect;0.6.5)</h2>
 *
 * <p>Posting a transaction publishes to the MSK Kafka topic
 * {@code transaction.posted} (partitioned by account ID for per-account
 * ordering). This publication is the responsibility of the service
 * layer ({@code TransactionPostingService},
 * {@code TransactionAddService}, {@code BillPaymentService}) via the
 * {@code KafkaEventPublisher} adapter, NOT this repository.</p>
 *
 * <h2>Consumers</h2>
 * <ul>
 *   <li>{@code TransactionListService} &mdash; paginated browse via
 *       {@link #findByOrderByTranIdAsc(Pageable)} (replacement for
 *       {@code app/cbl/COTRN00C.cbl}).</li>
 *   <li>{@code TransactionDetailService} &mdash; single
 *       {@link #findById(Object) findById(tranId)} (replacement for
 *       {@code app/cbl/COTRN01C.cbl}).</li>
 *   <li>{@code TransactionAddService} &mdash; calls
 *       {@link #findTopByOrderByTranIdDesc()} to discover the next
 *       sequence number, then {@code save} the new transaction
 *       (replacement for {@code app/cbl/COTRN02C.cbl}).</li>
 *   <li>{@code TransactionPostingService} &mdash; {@code saveAll} of
 *       validated daily transactions plus per-account balance updates
 *       within a {@code @Transactional} boundary (replacement for
 *       {@code app/cbl/CBTRN02C.cbl}).</li>
 *   <li>{@code TransactionReportService} &mdash; date-window iteration
 *       via {@link #findByTranCardNumAndTranProcTsBetween(String,
 *       LocalDateTime, LocalDateTime, Pageable)} (replacement for
 *       {@code app/cbl/CBTRN03C.cbl}).</li>
 *   <li>{@code BillPaymentService} &mdash; appends a payment
 *       transaction record within a {@code @Transactional} boundary
 *       (replacement for {@code app/cbl/COBIL00C.cbl}).</li>
 * </ul>
 *
 * @see com.awsm2.carddemo.domain.Transaction
 */
@Repository
public interface TransactionRepository extends JpaRepository<Transaction, String> {

    /**
     * Returns transactions in ascending {@code tran_id} order,
     * paginated.
     *
     * <p>Replaces the CICS {@code STARTBR DATASET('TRANSACT')} +
     * {@code READNEXT} paged browse pattern in
     * {@code app/cbl/COTRN00C.cbl}. The screen layout
     * {@code app/bms/COTRN00.bms} renders 10 rows per page, so callers
     * typically pass {@code PageRequest.of(pageNumber, 10)}.</p>
     *
     * <p>The PostgreSQL planner satisfies the
     * {@code ORDER BY tran_id ASC} via the primary-key B-tree (no
     * additional index required).</p>
     *
     * @param pageable pagination request (size 10 per source BMS layout)
     * @return paged result with transactions in ascending
     *         {@code tran_id} order
     */
    Page<Transaction> findByOrderByTranIdAsc(Pageable pageable);

    /**
     * Returns the single transaction with the highest {@code tran_id}
     * (if any).
     *
     * <p>Replaces the CICS {@code STARTBR} + {@code READPREV} +
     * {@code ENDBR} MAX-ID generation pattern in
     * {@code app/cbl/COTRN02C.cbl} L444-L449:</p>
     * <pre>
     * MOVE HIGH-VALUES TO TRAN-ID
     * PERFORM STARTBR-TRANSACT-FILE
     * PERFORM READPREV-TRANSACT-FILE
     * PERFORM ENDBR-TRANSACT-FILE
     * MOVE TRAN-ID     TO WS-TRAN-ID-N
     * ADD 1 TO WS-TRAN-ID-N
     * </pre>
     *
     * <p>The caller ({@code TransactionAddService}) extracts
     * {@code tranId} from the returned entity, parses it as a numeric
     * value, adds 1, and formats the result as a 16-character
     * zero-padded string before calling {@link #save(Object) save(...)}
     * on the new transaction.</p>
     *
     * <p>Returns {@link Optional#empty()} when the journal is empty
     * (initial install). The PostgreSQL planner satisfies
     * {@code ORDER BY tran_id DESC LIMIT 1} via the primary-key
     * B-tree.</p>
     *
     * @return the transaction with the highest {@code tran_id}, or
     *         {@link Optional#empty()} if no transactions exist
     */
    Optional<Transaction> findTopByOrderByTranIdDesc();

    /**
     * Returns the transaction with the lexicographically highest
     * <strong>16-character all-numeric</strong> {@code tran_id}, or
     * {@link Optional#empty()} if no such transaction exists.
     *
     * <p><b>BUG #6 fix (QA CP5):</b> The original
     * {@link #findTopByOrderByTranIdDesc()} method ignores the lexical
     * domain of {@code tran_id} values. The CP5 batch
     * ({@code TransactionPostingService.postDailyTransactions}) writes
     * the {@code DALYTRAN-ID} verbatim into {@code Transaction.tran_id},
     * and the COBOL daily-transaction journal often carries non-numeric
     * identifiers (e.g. {@code "HAPPY00000000099"}). Because
     * {@code 'H' > '9'} under {@code ORDER BY tran_id DESC}, the
     * unfiltered query returns the batch's alphanumeric ID. Both
     * {@code TransactionAddService.nextTransactionId()} and
     * {@code BillPaymentService.nextTransactionId()} then attempt
     * {@code new BigDecimal(maxId)}, catch the
     * {@link NumberFormatException}, reseed to {@code 0}, and re-emit
     * {@code "0000000000000001"} for every subsequent online posting.
     * The second posting silently UPDATEs the first via JPA
     * {@code save()} on the duplicate primary key &mdash; a confirmed
     * data-loss violation of the AAP &sect;0.7.2 "must preserve all
     * financial transaction logic" rule.</p>
     *
     * <p>This method scopes the query to {@code tran_id} values that
     * match the strict 16-digit numeric mask used by the COBOL
     * {@code WS-TRAN-ID-NUM} field
     * ({@code app/cbl/COTRN02C.cbl} L444-L449), guaranteeing that
     * {@code new BigDecimal(maxId).add(BigDecimal.ONE)} succeeds and
     * therefore the next ID never collides with an existing record.</p>
     *
     * <p>The PostgreSQL regular-expression operator {@code ~} (POSIX
     * regex match) is used directly via a {@link Query @Query}
     * annotation rather than a derived method name because Spring Data
     * JPA derived-query syntax does not support regex constraints.
     * The pattern {@code ^[0-9]{16}$} matches exactly 16 ASCII digits
     * with no other characters &mdash; the canonical form of the COBOL
     * {@code WS-TRAN-ID-NUM PIC 9(16)} field.</p>
     *
     * <p>The COBOL {@code STARTBR} / {@code READPREV} / {@code ENDBR}
     * MAX-ID pattern in {@code COTRN02C.cbl} returns the largest existing
     * value; this method preserves that semantic exactly within the
     * numeric-only sub-domain. Batch-inserted rows with non-numeric IDs
     * still exist in the journal; they are simply excluded from this
     * MAX-ID-generation lookup so they cannot poison the online
     * ID-generation sequence.</p>
     *
     * <h3>Performance</h3>
     *
     * <p>PostgreSQL evaluates the regex against every row, but the
     * {@code LIMIT 1} on the {@code ORDER BY tran_id DESC} primary-key
     * B-tree means at most one matching row is fetched. For tables up
     * to the demo-ready cutover scale (millions of rows) this is
     * acceptable; if the production volume warrants further
     * optimization, a partial functional index
     * ({@code CREATE INDEX ... ON transactions (tran_id DESC) WHERE
     * tran_id ~ '^[0-9]{16}$'}) can be added later via a Flyway
     * migration without changing this method's contract.</p>
     *
     * @return the transaction with the highest 16-digit all-numeric
     *         {@code tran_id}, or {@link Optional#empty()} if no
     *         all-numeric transaction exists
     */
    @Query(value = "SELECT t.* FROM transactions t "
            + "WHERE t.tran_id ~ '^[0-9]{16}$' "
            + "ORDER BY t.tran_id DESC LIMIT 1",
            nativeQuery = true)
    Optional<Transaction> findTopByNumericTranIdOrderByTranIdDesc();

    /**
     * Returns transactions for a given card that fall within a
     * processing timestamp window, paginated.
     *
     * <p>Replaces the VSAM AIX scan over {@code TRANSACT.VSAM.AIX}
     * ({@code KEYS(26 304)}, {@code NONUNIQUEKEY}) combined with the
     * date-range predicate in {@code app/cbl/CBTRN03C.cbl}
     * L173-L178:</p>
     * <pre>
     * PERFORM 1000-TRANFILE-GET-NEXT
     * IF TRAN-PROC-TS (1:10) >= WS-START-DATE
     *    AND TRAN-PROC-TS (1:10) <= WS-END-DATE
     *    CONTINUE
     * ELSE
     *    NEXT SENTENCE
     * END-IF
     * </pre>
     *
     * <p>where {@code WS-START-DATE}/{@code WS-END-DATE} are
     * 10-character date strings (PIC X(10), {@code YYYY-MM-DD} format)
     * read from the {@code DATEPARM} file. The caller
     * ({@code TransactionReportService} / {@code TransactionReportJob})
     * expands these into {@code LocalDateTime} boundaries &mdash;
     * start-of-day ({@code T00:00:00.000000}) for {@code startTs} and
     * end-of-day ({@code T23:59:59.999999}) for {@code endTs} &mdash;
     * to preserve the COBOL inclusive-range semantic.</p>
     *
     * <p>Backed by the composite database index
     * {@code idx_transactions_card_proc_ts (tran_card_num, tran_proc_ts)}
     * (declared in {@code V005__create_transaction.sql}).</p>
     *
     * @param tranCardNum the 16-character card number to filter on
     * @param startTs     inclusive lower bound on {@code tran_proc_ts}
     * @param endTs       inclusive upper bound on {@code tran_proc_ts}
     * @param pageable    pagination request for chunked streaming
     * @return paged result with matching transactions
     */
    Page<Transaction> findByTranCardNumAndTranProcTsBetween(String tranCardNum,
                                                            LocalDateTime startTs,
                                                            LocalDateTime endTs,
                                                            Pageable pageable);
}

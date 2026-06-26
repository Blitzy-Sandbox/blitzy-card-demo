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
package com.carddemo.integration;

import com.carddemo.entity.Transaction;
import com.carddemo.enums.TransactionTypeCode;
import com.carddemo.repository.TransactionRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link TransactionRepository} against a <strong>real
 * PostgreSQL&nbsp;16</strong> instance provisioned by Testcontainers (via
 * {@link AbstractIntegrationIT}), with Flyway applying the {@code V1}/{@code V2}/{@code V3}
 * migrations and Hibernate running under {@code ddl-auto=validate}. There is
 * <em>no</em> H2 and <em>no</em> mock: an in-memory database or a mocked client
 * would silently diverge from the VSAM-fidelity contract behind Validation
 * Gates&nbsp;1, 4 and 5.
 *
 * <p>{@code TransactionRepository} is the richest repository in the migration:
 * it carries the only custom {@code @Query} (the {@code CBTRN03C} date-window
 * read) plus the ordering and auto-id finders. The repository replaces the
 * legacy VSAM {@code TRANSACT} KSDS (copybook {@code CVTRA05Y} {@code TRAN-RECORD},
 * record length&nbsp;350, keyed on {@code TRAN-ID PIC X(16)}) at source commit
 * {@code 27d6c6f}. The behaviours asserted here trace directly to the COBOL
 * reference programs:</p>
 *
 * <ul>
 *   <li><strong>Auto-id generation</strong> — {@code COTRN02C} moves
 *       {@code HIGH-VALUES} to {@code TRAN-ID}, performs {@code STARTBR} then
 *       {@code READPREV} to read the highest existing identifier (falling back to
 *       {@code ZEROS} on an empty file), adds&nbsp;1, and formats it back into the
 *       16-digit {@code TRAN-ID}. {@link TransactionRepository#findTopByOrderByTranIdDesc()}
 *       supplies that highest identifier; {@code TransactionAddService} performs the
 *       {@code +1} / {@code %016d} arithmetic.</li>
 *   <li><strong>Date-window report</strong> — {@code CBTRN03C} scans the sequential
 *       transaction file and retains a record when
 *       {@code TRAN-PROC-TS (1:10) >= WS-START-DATE AND TRAN-PROC-TS (1:10) <= WS-END-DATE}.
 *       {@link TransactionRepository#findByProcessingDateWindow(String, String)} reproduces this
 *       with JPQL {@code SUBSTRING(t.procTs, 1, 10)} over the inclusive bounds.</li>
 *   <li><strong>By-card ordering</strong> — statement / list reads in {@code TRAN-ID}
 *       order map to {@link TransactionRepository#findByCardNumOrderByTranIdAsc(String)}.</li>
 *   <li><strong>Browse pagination</strong> — {@code STARTBR}/{@code READNEXT} paging maps to
 *       the inherited {@link org.springframework.data.repository.PagingAndSortingRepository#findAll(org.springframework.data.domain.Pageable)}.</li>
 * </ul>
 *
 * <h2>Data isolation</h2>
 * The {@code transactions} table is seeded <strong>empty</strong> by Flyway&nbsp;V3.
 * Every test method here inserts its own fixtures and the class is annotated
 * {@link Transactional}, so Spring rolls the test transaction back afterwards and
 * the table returns to its empty seed state for the other suites that share the
 * singleton container. To prove behaviour against the <em>database</em> rather
 * than the first-level persistence-context cache, each read assertion is preceded
 * by an {@link #flushAndClear()} that flushes pending inserts and detaches all
 * managed entities, forcing a real {@code SELECT} that re-runs
 * {@link com.carddemo.entity.TransactionTypeConverter} and re-materialises the
 * fixed-width {@code CHAR} columns.
 */
@Transactional
@DisplayName("TransactionRepository IT — PostgreSQL 16 + Flyway (VSAM TRANSACT fidelity)")
class TransactionRepositoryIT extends AbstractIntegrationIT {

    /** Sixteen-character card numbers (CHAR(16)); exact width avoids bpchar padding mismatch. */
    private static final String CARD_A = "4111111111111111";
    private static final String CARD_B = "4222222222222222";
    private static final String CARD_DEFAULT = "4000000000000000";

    /** TRANREPT default reporting window (inclusive), mirroring the {@code CBTRN03C} bounds. */
    private static final String WINDOW_START = "2022-01-01";
    private static final String WINDOW_END = "2022-07-06";

    @Autowired
    private TransactionRepository transactionRepository;

    /**
     * Real JPA {@link EntityManager} (transaction-bound) used to {@code flush()} pending
     * inserts and {@code clear()} the persistence context so that repository reads hit the
     * database instead of returning cached managed instances.
     */
    @PersistenceContext
    private EntityManager entityManager;

    // -------------------------------------------------------------------------------------
    // Test fixtures / helpers.
    // -------------------------------------------------------------------------------------

    /** Formats a numeric identifier as the zero-padded 16-character {@code TRAN-ID}. */
    private static String tranId(long n) {
        return String.format("%016d", n);
    }

    /**
     * Builds a 26-character {@code TRAN-PROC-TS}/{@code TRAN-ORIG-TS} value from a
     * {@code YYYY-MM-DD} date by appending a fixed {@code " 00:00:00.000000"} time portion,
     * yielding the exact {@code YYYY-MM-DD HH:MM:SS.mmmmmm} layout the {@code CHAR(26)} column
     * preserves.
     */
    private static String ts(String date) {
        return date + " 00:00:00.000000";
    }

    /** Fully-specified fixture factory mirroring the {@code CVTRA05Y} record layout. */
    private Transaction newTransaction(String tranId, String cardNum, TransactionTypeCode type,
            BigDecimal amt, String origTs, String procTs) {
        return new Transaction(
                tranId,                          // TRAN-ID            X(16)
                type,                            // TRAN-TYPE-CD       X(02) via converter
                1,                               // TRAN-CAT-CD        9(04)
                "POS",                           // TRAN-SOURCE        X(10)
                "Integration test transaction",  // TRAN-DESC          X(100)
                amt,                             // TRAN-AMT           S9(09)V99
                123456789L,                      // TRAN-MERCHANT-ID   9(09)
                "ACME STORE",                    // TRAN-MERCHANT-NAME X(50)
                "SEATTLE",                       // TRAN-MERCHANT-CITY X(50)
                "98101",                         // TRAN-MERCHANT-ZIP  X(10)
                cardNum,                         // TRAN-CARD-NUM      X(16)
                origTs,                          // TRAN-ORIG-TS       X(26)
                procTs);                         // TRAN-PROC-TS       X(26)
    }

    /** Convenience fixture keyed only by {@code TRAN-ID} (default card, type and timestamps). */
    private Transaction newTransaction(String tranId) {
        return newTransaction(tranId, CARD_DEFAULT, TransactionTypeCode.PURCHASE,
                new BigDecimal("100.00"), ts("2022-05-01"), ts("2022-05-01"));
    }

    /** Convenience fixture varying only {@code TRAN-ID} and {@code TRAN-PROC-TS} (date-window tests). */
    private Transaction newTransaction(String tranId, String procTs) {
        return newTransaction(tranId, CARD_DEFAULT, TransactionTypeCode.PURCHASE,
                new BigDecimal("100.00"), procTs, procTs);
    }

    /** Flushes pending inserts to the database and detaches all entities to force fresh reads. */
    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    /** Reads the raw {@code tran_type_cd} CHAR(2) column directly via JDBC to prove the write path. */
    private String rawTypeCode(String tranId) {
        return jdbcTemplate.queryForObject(
                "SELECT tran_type_cd FROM transactions WHERE tran_id = ?", String.class, tranId);
    }

    // =====================================================================================
    // Phase 1 — empty-state and auto-id ordering (COTRN02C next-id derivation).
    // =====================================================================================

    @Test
    @DisplayName("empty table: count()==0 and findTopByOrderByTranIdDesc() is empty (drives auto-id seed → 0000000000000001)")
    void emptyTable_countIsZero_andFindTopIsEmpty() {
        // The transactions table is seeded empty by Flyway V3; with @Transactional rollback it
        // is empty at the start of every method, so this models the COTRN02C READPREV-on-empty
        // path (TRAN-ID falls back to ZEROS, and TransactionAddService seeds the next id at 1).
        assertThat(transactionRepository.count()).isZero();
        assertThat(countRows("transactions")).isZero();
        assertThat(transactionRepository.findTopByOrderByTranIdDesc()).isEmpty();
    }

    @Test
    @DisplayName("findTopByOrderByTranIdDesc() returns the lexical max of zero-padded ids (0001,0002,0010 → 0010)")
    void findTopByOrderByTranIdDesc_returnsLexicalMax_zeroPadded() {
        // Insert out of order to prove the finder, not insertion order, decides the maximum.
        transactionRepository.save(newTransaction(tranId(2)));
        transactionRepository.save(newTransaction(tranId(10)));
        transactionRepository.save(newTransaction(tranId(1)));
        flushAndClear();

        Optional<Transaction> top = transactionRepository.findTopByOrderByTranIdDesc();

        // String/lexical ordering on the zero-padded 16-char ids: "...0010" > "...0002" > "...0001".
        assertThat(top).isPresent();
        assertThat(top.get().getTranId()).isEqualTo(tranId(10));
        assertThat(transactionRepository.count()).isEqualTo(3L);
    }

    // =====================================================================================
    // Phase 2 — TransactionTypeConverter round-trip (enum ↔ 2-char tran_type_cd).
    // =====================================================================================

    @Test
    @DisplayName("converter round-trips PAYMENT ↔ tran_type_cd \"02\" (write path + read path)")
    void transactionType_payment_roundTripsThroughConverter() {
        String id = tranId(2001);
        transactionRepository.save(newTransaction(id, CARD_DEFAULT, TransactionTypeCode.PAYMENT,
                new BigDecimal("42.00"), ts("2022-02-02"), ts("2022-02-02")));
        flushAndClear();

        // Write path: the raw CHAR(2) column holds exactly "02".
        assertThat(rawTypeCode(id)).isEqualTo("02");

        // Read path: a fresh SELECT re-runs the converter, resolving "02" → PAYMENT.
        Transaction reloaded = transactionRepository.findById(id).orElseThrow();
        assertThat(reloaded.getTransactionType()).isEqualTo(TransactionTypeCode.PAYMENT);
        assertThat(reloaded.getTransactionType().getCode()).isEqualTo("02");
    }

    @Test
    @DisplayName("converter round-trips PURCHASE ↔ tran_type_cd \"01\" (write path + read path)")
    void transactionType_purchase_roundTripsThroughConverter() {
        String id = tranId(2002);
        transactionRepository.save(newTransaction(id, CARD_DEFAULT, TransactionTypeCode.PURCHASE,
                new BigDecimal("13.37"), ts("2022-03-03"), ts("2022-03-03")));
        flushAndClear();

        assertThat(rawTypeCode(id)).isEqualTo("01");

        Transaction reloaded = transactionRepository.findById(id).orElseThrow();
        assertThat(reloaded.getTransactionType()).isEqualTo(TransactionTypeCode.PURCHASE);
        assertThat(reloaded.getTransactionType().getCode()).isEqualTo("01");
    }

    // =====================================================================================
    // Phase 3 — by-card ordering and browse pagination.
    // =====================================================================================

    @Test
    @DisplayName("findByCardNumOrderByTranIdAsc(): ascending by tranId, other cards excluded")
    void findByCardNumOrderByTranIdAsc_ascending_andExcludesOtherCards() {
        // CARD_A rows inserted out of order; a CARD_B row must be excluded entirely.
        transactionRepository.save(newTransaction(tranId(5), CARD_A, TransactionTypeCode.PURCHASE,
                new BigDecimal("10.00"), ts("2022-04-01"), ts("2022-04-01")));
        transactionRepository.save(newTransaction(tranId(2), CARD_A, TransactionTypeCode.PURCHASE,
                new BigDecimal("20.00"), ts("2022-04-02"), ts("2022-04-02")));
        transactionRepository.save(newTransaction(tranId(8), CARD_A, TransactionTypeCode.PURCHASE,
                new BigDecimal("30.00"), ts("2022-04-03"), ts("2022-04-03")));
        transactionRepository.save(newTransaction(tranId(3), CARD_B, TransactionTypeCode.PURCHASE,
                new BigDecimal("40.00"), ts("2022-04-04"), ts("2022-04-04")));
        flushAndClear();

        List<Transaction> cardA = transactionRepository.findByCardNumOrderByTranIdAsc(CARD_A);
        assertThat(cardA)
                .extracting(Transaction::getTranId)
                .containsExactly(tranId(2), tranId(5), tranId(8));
        assertThat(cardA).extracting(Transaction::getCardNum).containsOnly(CARD_A);

        List<Transaction> cardB = transactionRepository.findByCardNumOrderByTranIdAsc(CARD_B);
        assertThat(cardB).extracting(Transaction::getTranId).containsExactly(tranId(3));

        // An unknown card returns an empty (never null) list.
        assertThat(transactionRepository.findByCardNumOrderByTranIdAsc("9999999999999999"))
                .isNotNull()
                .isEmpty();
    }

    @Test
    @DisplayName("findAll(Pageable): page size 10 over 12 rows → 2 pages with correct totals and ordering")
    void findAll_pageable_pageSizeTen_multiPageTotals() {
        for (int i = 1; i <= 12; i++) {
            transactionRepository.save(newTransaction(tranId(i)));
        }
        flushAndClear();

        Sort byTranId = Sort.by("tranId");

        Page<Transaction> firstPage = transactionRepository.findAll(PageRequest.of(0, 10, byTranId));
        assertThat(firstPage.getTotalElements()).isEqualTo(12L);
        assertThat(firstPage.getTotalPages()).isEqualTo(2);
        assertThat(firstPage.getNumber()).isZero();
        assertThat(firstPage.getContent()).hasSize(10);
        assertThat(firstPage.getContent().get(0).getTranId()).isEqualTo(tranId(1));
        assertThat(firstPage.getContent().get(9).getTranId()).isEqualTo(tranId(10));
        assertThat(firstPage.isFirst()).isTrue();
        assertThat(firstPage.isLast()).isFalse();

        Page<Transaction> secondPage = transactionRepository.findAll(PageRequest.of(1, 10, byTranId));
        assertThat(secondPage.getContent()).hasSize(2);
        assertThat(secondPage.getContent().get(0).getTranId()).isEqualTo(tranId(11));
        assertThat(secondPage.getContent().get(1).getTranId()).isEqualTo(tranId(12));
        assertThat(secondPage.isLast()).isTrue();
    }

    // =====================================================================================
    // Phase 4 — date-window @Query (CBTRN03C report read; SUBSTRING(proc_ts,1,10) window).
    // =====================================================================================

    @Test
    @DisplayName("findByProcessingDateWindow(): only in-window rows, ordered by tranId (not insertion order)")
    void findByProcessingDateWindow_returnsOnlyInWindow_ordered() {
        // Two in-window rows (ids deliberately out of insertion order) and one out-of-window row.
        transactionRepository.save(newTransaction(tranId(3), ts("2022-07-01"))); // in window
        transactionRepository.save(newTransaction(tranId(1), ts("2022-03-15"))); // in window
        transactionRepository.save(newTransaction(tranId(2), ts("2022-12-31"))); // out of window
        flushAndClear();

        List<Transaction> window =
                transactionRepository.findByProcessingDateWindow(WINDOW_START, WINDOW_END);

        // Only the two 2022-H1 rows return, ordered by tranId ascending.
        assertThat(window)
                .extracting(Transaction::getTranId)
                .containsExactly(tranId(1), tranId(3));
    }

    @Test
    @DisplayName("findByProcessingDateWindow(): boundary dates are inclusive (start & end in; ±1 day out)")
    void findByProcessingDateWindow_boundaryDatesInclusive() {
        transactionRepository.save(newTransaction(tranId(1), ts(WINDOW_START)));  // == start → in
        transactionRepository.save(newTransaction(tranId(2), ts(WINDOW_END)));    // == end   → in
        transactionRepository.save(newTransaction(tranId(3), ts("2021-12-31")));  // day before start → out
        transactionRepository.save(newTransaction(tranId(4), ts("2022-07-07")));  // day after end  → out
        flushAndClear();

        List<Transaction> window =
                transactionRepository.findByProcessingDateWindow(WINDOW_START, WINDOW_END);

        // The inclusive [start, end] bounds keep exactly the two boundary rows.
        assertThat(window)
                .extracting(Transaction::getTranId)
                .containsExactly(tranId(1), tranId(2));
    }

    @Test
    @DisplayName("findByProcessingDateWindow(): empty (never null) when no row falls in the window")
    void findByProcessingDateWindow_emptyWhenNoneInWindow() {
        transactionRepository.save(newTransaction(tranId(1), ts("2022-03-15")));
        transactionRepository.save(newTransaction(tranId(2), ts("2022-07-01")));
        flushAndClear();

        // A 2025 window excludes the 2022 fixtures entirely.
        assertThat(transactionRepository.findByProcessingDateWindow("2025-01-01", "2025-12-31"))
                .isNotNull()
                .isEmpty();
    }

    // =====================================================================================
    // Phase 5 — decimal scale and timestamp byte-fidelity (interface-contract preservation).
    // =====================================================================================

    @Test
    @DisplayName("tranAmt persists at NUMERIC(11,2): scale normalised to 2, compared via compareTo (incl. negative)")
    void tranAmt_persistsAtScaleTwo_viaCompareTo() {
        String positive = tranId(5001);
        String scaleOne = tranId(5002);
        String negative = tranId(5003);
        transactionRepository.save(newTransaction(positive, CARD_DEFAULT, TransactionTypeCode.PURCHASE,
                new BigDecimal("12345.67"), ts("2022-06-01"), ts("2022-06-01")));
        transactionRepository.save(newTransaction(scaleOne, CARD_DEFAULT, TransactionTypeCode.PURCHASE,
                new BigDecimal("100.5"), ts("2022-06-02"), ts("2022-06-02")));
        transactionRepository.save(newTransaction(negative, CARD_DEFAULT, TransactionTypeCode.CREDIT,
                new BigDecimal("-50.25"), ts("2022-06-03"), ts("2022-06-03")));
        flushAndClear();

        // Exact scale-2 amount: value preserved, stored scale is 2.
        BigDecimal reloadedPositive = transactionRepository.findById(positive).orElseThrow().getTranAmt();
        assertThat(reloadedPositive).isEqualByComparingTo("12345.67");
        assertThat(reloadedPositive.scale()).isEqualTo(2);

        // A scale-1 input is normalised to NUMERIC(11,2) on read (100.5 → 100.50); compareTo ignores scale.
        BigDecimal reloadedScaleOne = transactionRepository.findById(scaleOne).orElseThrow().getTranAmt();
        assertThat(reloadedScaleOne).isEqualByComparingTo("100.50");
        assertThat(reloadedScaleOne.scale()).isEqualTo(2);

        // Signed S9(09)V99 amounts retain their sign exactly.
        BigDecimal reloadedNegative = transactionRepository.findById(negative).orElseThrow().getTranAmt();
        assertThat(reloadedNegative).isEqualByComparingTo("-50.25");
        assertThat(reloadedNegative.scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("origTs/procTs CHAR(26) round-trip byte-exact (no truncation, no timezone shift)")
    void origTsAndProcTs_roundTripByteExact_26Chars() {
        String id = tranId(6001);
        String origTs = "2022-03-15 14:23:45.678901";
        String procTs = "2022-07-01 09:08:07.654321";
        transactionRepository.save(newTransaction(id, CARD_DEFAULT, TransactionTypeCode.PURCHASE,
                new BigDecimal("1.00"), origTs, procTs));
        flushAndClear();

        Transaction reloaded = transactionRepository.findById(id).orElseThrow();
        assertThat(reloaded.getOrigTs()).isEqualTo(origTs).hasSize(26);
        assertThat(reloaded.getProcTs()).isEqualTo(procTs).hasSize(26);
    }

    @Test
    @DisplayName("full CVTRA05Y record round-trips: every mapped field is preserved")
    void fullRecord_allFieldsRoundTrip() {
        String id = tranId(7001);
        Transaction original = new Transaction(
                id, TransactionTypeCode.REFUND, 4096, "ONLINE", "Refund for returned item",
                new BigDecimal("250.00"), 987654321L, "GLOBEX CORP", "PORTLAND", "97201",
                CARD_A, "2022-05-20 11:22:33.123456", "2022-05-21 00:11:22.000099");
        transactionRepository.save(original);
        flushAndClear();

        Transaction r = transactionRepository.findById(id).orElseThrow();
        assertThat(r.getTranId()).isEqualTo(id);
        assertThat(r.getTransactionType()).isEqualTo(TransactionTypeCode.REFUND);
        assertThat(r.getTranCatCd()).isEqualTo(4096);
        assertThat(r.getTranSource()).isEqualTo("ONLINE");
        assertThat(r.getTranDesc()).isEqualTo("Refund for returned item");
        assertThat(r.getTranAmt()).isEqualByComparingTo("250.00");
        assertThat(r.getMerchantId()).isEqualTo(987654321L);
        assertThat(r.getMerchantName()).isEqualTo("GLOBEX CORP");
        assertThat(r.getMerchantCity()).isEqualTo("PORTLAND");
        assertThat(r.getMerchantZip()).isEqualTo("97201");
        assertThat(r.getCardNum()).isEqualTo(CARD_A);
        assertThat(r.getOrigTs()).isEqualTo("2022-05-20 11:22:33.123456");
        assertThat(r.getProcTs()).isEqualTo("2022-05-21 00:11:22.000099");
    }
}

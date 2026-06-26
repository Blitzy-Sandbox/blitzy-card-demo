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

import com.carddemo.entity.DailyTransaction;
import com.carddemo.repository.DailyTransactionRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link DailyTransactionRepository} executed against a
 * <strong>real PostgreSQL&nbsp;16</strong> instance provisioned by Testcontainers
 * (no H2, no mock, no hardcoded port). It extends {@link AbstractIntegrationIT},
 * so Flyway applies the real {@code V1}/{@code V2}/{@code V3} migrations and
 * Hibernate runs with {@code ddl-auto=validate}; the Spring context therefore
 * starts only when the {@link DailyTransaction} mapping agrees with the migrated
 * {@code daily_transaction} schema.
 *
 * <h2>What {@code daily_transaction} models</h2>
 * The table is the posting-staging input for the legacy batch pipeline: copybook
 * {@code CVTRA06Y} ({@code DALYTRAN-RECORD}, RECLN&nbsp;350) @ {@code 27d6c6f}, read
 * sequentially by the {@code CBTRN01C} driver and consumed record-by-record by the
 * {@code CBTRN02C} posting engine under JCL {@code POSTTRAN.jcl}. In the migration it
 * is the input the Spring Batch posting job (realized by
 * {@code TransactionPostingProcessor}) reads before applying the four-stage validation
 * cascade.
 *
 * <h2>The staging table is seeded from {@code dailytran.txt}</h2>
 * {@code V3__seed_data.sql} seeds {@code daily_transaction} from
 * {@code app/data/ASCII/dailytran.txt} (300 records, copybook {@code CVTRA06Y}), so a
 * freshly migrated schema contains the 300-row daily feed. The
 * {@code DALYTRAN-AMT S9(09)V99} zoned-overpunch sign is decoded to
 * {@code NUMERIC(11,2)}, and the posting batch reads the feed through
 * {@link DailyTransactionRepository}. The first assertions verify the seeded row
 * count and decode fidelity.
 *
 * <h2>Raw, unvalidated transaction-type code</h2>
 * {@link DailyTransaction#getTranTypeCd()} is a plain {@link String} bound to a
 * {@code CHAR(2)} column with no attribute converter: a staging record holds the
 * two-character {@code DALYTRAN-TYPE-CD} exactly as read from the daily file. The posted
 * {@code Transaction} entity likewise stores its {@code TRAN-TYPE-CD} as a raw
 * {@code CHAR(2)} String (write-through, no attribute converter). Code validation
 * (against the seven {@code TRAN-TYPE} values
 * {@code 01}..{@code 07}) runs later in {@code TransactionPostingProcessor}. The seeded
 * fixture contains only the valid codes {@code 01} and {@code 03}; a synthetic insert
 * below round-trips codes that are not valid enum constants ({@code "99"}, {@code "ZZ"})
 * to confirm the staging layer stores the code verbatim.
 *
 * <h2>Data isolation</h2>
 * The shared PostgreSQL container is reused across every {@code *IT}, so each mutating
 * test here is {@code @Transactional}: Spring rolls the test transaction back, restoring
 * the seeded 300-row baseline before and after. Read-only tests assert the seeded state
 * directly. Reads are forced to hit the database (rather than the persistence-context
 * first-level cache) by flushing and clearing the {@link EntityManager}, so the
 * assertions exercise the real PostgreSQL round-trip &mdash; including the fixed-width
 * {@code CHAR} JDBC bindings and the {@code NUMERIC(11,2)} monetary scale.
 */
@DisplayName("DailyTransactionRepository IT — seeded posting-staging table on real PostgreSQL 16 "
        + "(CVTRA06Y / dailytran.txt / CBTRN01C\u2192CBTRN02C / POSTTRAN)")
class DailyTransactionRepositoryIT extends AbstractIntegrationIT {

    /** Row count seeded into {@code daily_transaction} from {@code dailytran.txt}. */
    private static final long SEEDED_ROW_COUNT = 300L;

    /**
     * A 26-character origination timestamp matching the {@code DALYTRAN-ORIG-TS PIC
     * X(26)} width and the {@code dailytran.txt} format
     * ({@code YYYY-MM-DD HH:MM:SS.mmmmmm}). It fills the {@code CHAR(26)} column exactly,
     * so it round-trips verbatim and lets the read-back assertions check timestamp
     * fidelity (AAP &sect;0.6.4). Every seeded fixture row carries this value.
     */
    private static final String ORIG_TS = "2022-06-10 19:27:53.000000";

    /** First seeded fixture row: {@code DALYTRAN-ID} of the {@code dailytran.txt} line 1. */
    private static final String SEEDED_ID_1 = "0000000000683580";

    /** Second seeded fixture row: {@code DALYTRAN-ID} of the {@code dailytran.txt} line 2. */
    private static final String SEEDED_ID_2 = "0000000001774260";

    /** Repository under test — the staging access replacing {@code DALYTRAN-FILE}. */
    @Autowired
    private DailyTransactionRepository dailyTransactionRepository;

    /**
     * JPA entity manager used to {@code flush()} pending writes to PostgreSQL and
     * {@code clear()} the first-level cache, so subsequent repository reads issue real
     * {@code SELECT}s against the container database rather than returning managed
     * instances from the persistence context.
     */
    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Builds a fully populated {@link DailyTransaction} mirroring the {@code dailytran.txt}
     * record shape (copybook {@code CVTRA06Y}). Fixed-width {@code CHAR} fields are sized to
     * their exact column widths so they round-trip verbatim: {@code tranId}/{@code cardNum}
     * are 16 characters and {@code origTs} is the 26-character {@link #ORIG_TS}. The
     * processing timestamp ({@code DALYTRAN-PROC-TS}) is {@code null} for an unposted record;
     * {@code CBTRN02C} stamps it at posting time.
     *
     * @param tranId      the 16-character {@code DALYTRAN-ID} primary key
     * @param rawTypeCode the raw two-character {@code DALYTRAN-TYPE-CD} (stored verbatim)
     * @param description the {@code DALYTRAN-DESC} text
     * @param amount      the {@code DALYTRAN-AMT} monetary value (scale 2)
     * @param cardNum     the 16-character {@code DALYTRAN-CARD-NUM}
     * @return a transient, fully populated staging transaction ready to persist
     */
    private DailyTransaction stagingRecord(String tranId, String rawTypeCode,
            String description, BigDecimal amount, String cardNum) {
        return new DailyTransaction(
                tranId,                       // DALYTRAN-ID            PIC X(16)
                rawTypeCode,                  // DALYTRAN-TYPE-CD       PIC X(02) — raw, no converter
                1,                            // DALYTRAN-CAT-CD        PIC 9(04)
                "POS TERM",                   // DALYTRAN-SOURCE        PIC X(10)
                description,                  // DALYTRAN-DESC          PIC X(100)
                amount,                       // DALYTRAN-AMT           PIC S9(09)V99
                800_000_000L,                 // DALYTRAN-MERCHANT-ID   PIC 9(09)
                "Merchant " + rawTypeCode,    // DALYTRAN-MERCHANT-NAME PIC X(50)
                "Anytown",                    // DALYTRAN-MERCHANT-CITY PIC X(50)
                "00000",                      // DALYTRAN-MERCHANT-ZIP  PIC X(10)
                cardNum,                      // DALYTRAN-CARD-NUM      PIC X(16)
                ORIG_TS,                      // DALYTRAN-ORIG-TS       PIC X(26)
                null);                        // DALYTRAN-PROC-TS       PIC X(26) — unposted
    }

    // ---------------------------------------------------------------------------------
    // Phase 1 — seeded posting-staging state (Flyway V3 seeds daily_transaction).
    // ---------------------------------------------------------------------------------

    /**
     * The staging table is seeded with the 300-row {@code dailytran.txt} feed by
     * {@code V3__seed_data.sql}.
     */
    @Test
    @DisplayName("count() == 300 — Flyway V3 seeds the staging table from dailytran.txt")
    void countMatchesSeededDailytranFeed() {
        assertThat(dailyTransactionRepository.count())
                .as("daily_transaction is seeded from dailytran.txt (CVTRA06Y, 300 rows)")
                .isEqualTo(SEEDED_ROW_COUNT);
    }

    /**
     * The sequential-read entry point ({@code CBTRN01C} driver) sees all 300 seeded rows.
     */
    @Test
    @DisplayName("findAll() returns the 300 seeded staging rows")
    void findAllReturnsAllSeededStagingRows() {
        assertThat(dailyTransactionRepository.findAll())
                .as("sequential scan returns the seeded daily feed")
                .hasSize((int) SEEDED_ROW_COUNT);
    }

    /**
     * The first two seeded fixture rows decode with byte fidelity: the
     * {@code DALYTRAN-AMT S9(09)V99} overpunch sign yields {@code 504.77} (positive) and
     * {@code -919.00} (negative) at {@code NUMERIC(11,2)} scale&nbsp;2; the raw two-character
     * {@code DALYTRAN-TYPE-CD}, the 16-character {@code DALYTRAN-CARD-NUM}, the
     * {@code DALYTRAN-SOURCE} and the 26-character {@code DALYTRAN-ORIG-TS} are preserved
     * exactly.
     */
    @Test
    @DisplayName("seeded fixture rows decode with byte fidelity (overpunch sign, CHAR widths, timestamp)")
    void seededFixtureRowsDecodeWithByteFidelity() {
        DailyTransaction row1 = dailyTransactionRepository.findById(SEEDED_ID_1).orElseThrow();
        assertThat(row1.getTranTypeCd()).isEqualTo("01");
        assertThat(row1.getTranAmt()).isEqualByComparingTo("504.77");
        assertThat(row1.getTranAmt().scale()).isEqualTo(2);
        assertThat(row1.getCardNum()).isEqualTo("4859452612877065");
        assertThat(row1.getTranSource()).isEqualTo("POS TERM");
        assertThat(row1.getOrigTs()).isEqualTo(ORIG_TS);

        DailyTransaction row2 = dailyTransactionRepository.findById(SEEDED_ID_2).orElseThrow();
        assertThat(row2.getTranTypeCd()).isEqualTo("03");
        assertThat(row2.getTranAmt())
                .as("DALYTRAN-AMT negative overpunch sign preserved")
                .isEqualByComparingTo("-919.00");
        assertThat(row2.getTranAmt().scale()).isEqualTo(2);
    }

    // ---------------------------------------------------------------------------------
    // Phase 2 — staging read fidelity. Mutating tests are @Transactional so the seeded
    // 300-row baseline is restored by rollback.
    // ---------------------------------------------------------------------------------

    /**
     * Inserts staging records whose {@code DALYTRAN-TYPE-CD} values are not valid
     * {@code TRAN-TYPE} enum constants ({@code "99"}, {@code "ZZ"}) using primary keys that
     * are absent from the seeded fixture, then reloads them from PostgreSQL and asserts the
     * raw code is stored and returned verbatim. The staging layer applies no enum conversion
     * and no validation; that responsibility belongs to {@code TransactionPostingProcessor}
     * downstream. The 26-character origination timestamp is also checked for verbatim
     * {@code CHAR(26)} fidelity (AAP &sect;0.6.4).
     */
    @Test
    @Transactional
    @DisplayName("staging rows store RAW tran_type_cd verbatim — no enum conversion, no validation")
    void stagingStoresRawTransactionTypeCodeVerbatim() {
        long baseline = dailyTransactionRepository.count();

        dailyTransactionRepository.saveAll(List.of(
                stagingRecord("TESTRAW990000001", "99", "Raw code 99 — not a known TRAN-TYPE enum",
                        new BigDecimal("281.77"), "8040580410348680"),
                stagingRecord("TESTRAWZZ0000001", "ZZ", "Raw code ZZ — alpha, never enum-valid",
                        new BigDecimal("454.66"), "5656830544981216")));
        entityManager.flush();
        entityManager.clear(); // force reads to hit PostgreSQL, not the L1 cache

        assertThat(dailyTransactionRepository.count()).isEqualTo(baseline + 2);

        DailyTransaction raw99 = dailyTransactionRepository.findById("TESTRAW990000001").orElseThrow();
        assertThat(raw99.getTranTypeCd()).isEqualTo("99");
        assertThat(raw99.getOrigTs()).isEqualTo(ORIG_TS);

        DailyTransaction rawZz = dailyTransactionRepository.findById("TESTRAWZZ0000001").orElseThrow();
        assertThat(rawZz.getTranTypeCd()).isEqualTo("ZZ");
    }

    /**
     * A keyed read by {@code DALYTRAN-ID} ({@code findById}) returns the seeded staging
     * record with its fields intact, and a lookup for a key absent from the seeded fixture
     * returns an empty {@link Optional}.
     */
    @Test
    @DisplayName("findById(DALYTRAN-ID) returns the seeded staging record; absent key returns empty")
    void findByIdReturnsSeededRecordAndEmptyForAbsentKey() {
        Optional<DailyTransaction> found = dailyTransactionRepository.findById(SEEDED_ID_1);

        assertThat(found).as("keyed READ by DALYTRAN-ID returns the seeded record").isPresent();
        DailyTransaction record = found.orElseThrow();
        assertThat(record.getTranId()).isEqualTo(SEEDED_ID_1);
        assertThat(record.getTranTypeCd()).isEqualTo("01");
        assertThat(record.getCardNum()).isEqualTo("4859452612877065");
        assertThat(record.getOrigTs()).isEqualTo(ORIG_TS);
        assertThat(record.getTranAmt()).isEqualByComparingTo("504.77");

        assertThat(dailyTransactionRepository.findById("9999999999999999"))
                .as("a key with no staged record returns Optional.empty")
                .isEmpty();
    }

    // ---------------------------------------------------------------------------------
    // Phase 3 — persist / delete round-trips on top of the seeded baseline. @Transactional
    // rollback restores the seeded 300-row state.
    // ---------------------------------------------------------------------------------

    /**
     * {@code saveAll} adds a batch of synthetic rows (keys absent from the seeded fixture)
     * on top of the seeded baseline, raising the count by the batch size (verified through
     * both the repository and {@code JdbcTemplate}); {@code deleteAllById} removes exactly
     * those rows and returns the count to the seeded baseline. The surrounding transaction
     * rolls back, leaving the seeded staging table intact for every other {@code *IT}.
     */
    @Test
    @Transactional
    @DisplayName("saveAll then deleteAllById round-trips the row count against the seeded baseline")
    void saveAllThenDeleteByIdRoundTripsAgainstSeededBaseline() {
        long baseline = dailyTransactionRepository.count();
        assertThat(baseline).isEqualTo(SEEDED_ROW_COUNT);

        List<String> ids = List.of(
                "TESTRT0000000001", "TESTRT0000000002", "TESTRT0000000003", "TESTRT0000000004");
        dailyTransactionRepository.saveAll(List.of(
                stagingRecord(ids.get(0), "03", "Return item at Sipes Inc",
                        new BigDecimal("-56.77"), "4011500891777367"),
                stagingRecord(ids.get(1), "01", "Purchase at Legros Group",
                        new BigDecimal("373.66"), "8040580410348680"),
                stagingRecord(ids.get(2), "03", "Return item at Turcotte Group",
                        new BigDecimal("-535.88"), "6503535181795992"),
                stagingRecord(ids.get(3), "01", "Purchase at Gleason, Shanahan and Reynolds",
                        new BigDecimal("416.11"), "9501733721429893")));
        dailyTransactionRepository.flush();

        assertThat(dailyTransactionRepository.count())
                .as("count() rises by the number of inserted rows")
                .isEqualTo(baseline + 4);
        assertThat(countRows("daily_transaction"))
                .as("database-level row count via JdbcTemplate agrees with the repository")
                .isEqualTo(baseline + 4);

        dailyTransactionRepository.deleteAllById(ids);
        dailyTransactionRepository.flush();

        assertThat(dailyTransactionRepository.count())
                .as("deleteAllById removes exactly the inserted rows, restoring the seeded baseline")
                .isEqualTo(baseline);
    }

    /**
     * The {@code DALYTRAN-AMT PIC S9(09)V99} monetary value round-trips through
     * {@code NUMERIC(11,2)} at scale&nbsp;2 with sign and precision intact. Seeded rows cover
     * a positive ({@code 504.77}) and a negative ({@code -919.00}) value; a synthetic insert
     * with a scale-1 literal ({@code 829.5}) is normalized to scale&nbsp;2 by the column. A
     * final {@code JdbcTemplate} read of a seeded row confirms the value is stored natively at
     * scale&nbsp;2 (no JPA caching involved).
     */
    @Test
    @Transactional
    @DisplayName("tran_amt round-trips at NUMERIC(11,2) — scale 2 and sign preserved (compareTo)")
    void tranAmtRoundTripsAtScaleTwoPreservingSignAndPrecision() {
        DailyTransaction positive = dailyTransactionRepository.findById(SEEDED_ID_1).orElseThrow();
        assertThat(positive.getTranAmt()).isEqualByComparingTo("504.77");
        assertThat(positive.getTranAmt().scale()).as("NUMERIC(11,2) yields scale 2").isEqualTo(2);

        DailyTransaction negative = dailyTransactionRepository.findById(SEEDED_ID_2).orElseThrow();
        assertThat(negative.getTranAmt()).as("negative amounts retain their sign")
                .isEqualByComparingTo("-919.00");
        assertThat(negative.getTranAmt().scale()).isEqualTo(2);

        dailyTransactionRepository.save(stagingRecord("TESTSCALE0000001", "01",
                "Scale-1 literal normalized by the column", new BigDecimal("829.5"),
                "3766281984155154"));
        entityManager.flush();
        entityManager.clear(); // re-read from PostgreSQL so scale reflects the NUMERIC(11,2) column

        DailyTransaction normalized =
                dailyTransactionRepository.findById("TESTSCALE0000001").orElseThrow();
        assertThat(normalized.getTranAmt()).isEqualByComparingTo("829.50");
        assertThat(normalized.getTranAmt().scale())
                .as("a scale-1 input is normalized to scale 2 by NUMERIC(11,2)")
                .isEqualTo(2);

        // DB-native read (bypasses JPA): the seeded NUMERIC value straight from PostgreSQL.
        BigDecimal dbAmount = jdbcTemplate.queryForObject(
                "SELECT tran_amt FROM daily_transaction WHERE tran_id = ?",
                BigDecimal.class, SEEDED_ID_1);
        assertThat(dbAmount).as("value is stored natively in PostgreSQL").isEqualByComparingTo("504.77");
        assertThat(dbAmount.scale()).isEqualTo(2);
    }
}

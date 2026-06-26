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
 * The table is the <em>unposted</em> posting-staging input for the legacy batch
 * pipeline: copybook {@code CVTRA06Y} ({@code DALYTRAN-RECORD}, RECLN&nbsp;350) @
 * {@code 27d6c6f}, read sequentially by the {@code CBTRN01C} driver and consumed
 * record-by-record by the {@code CBTRN02C} posting engine under JCL
 * {@code POSTTRAN.jcl}. In the migration it is the input the Spring Batch posting
 * job (realized by {@code TransactionPostingProcessor}) reads before applying the
 * four-stage validation cascade.
 *
 * <h2>The staging table starts EMPTY</h2>
 * Unlike the master / reference tables, {@code daily_transaction} is
 * <strong>not seeded by Flyway</strong>: it is the transient daily feed, populated
 * from {@code app/data/ASCII/dailytran.txt} (300 records) as batch <em>input</em>
 * during the POSTTRAN job IT — never by the {@code V3} seed migration. This is the
 * design fixed by the migration contract (the {@code V1} schema annotates the table
 * "posting staging, seeded empty", and {@link AbstractIntegrationIT} documents that
 * the {@code transactions} and {@code daily_transaction} tables start empty), so the
 * first assertions here verify the empty-staging contract directly.
 *
 * <h2>Raw, unvalidated transaction-type code</h2>
 * {@link DailyTransaction#getTranTypeCd()} is a plain {@link String} bound to a
 * {@code CHAR(2)} column with <strong>no attribute converter</strong>: a staging
 * record holds the two-character {@code DALYTRAN-TYPE-CD} exactly as read from the
 * daily file. This is deliberately different from the posted {@code Transaction}
 * entity, whose type code is mapped through {@code TransactionTypeConverter}.
 * Validation of the code (against the seven {@code TRAN-TYPE} values {@code 01}..{@code 07})
 * happens later, in {@code TransactionPostingProcessor} — not at the staging boundary.
 * The tests below prove that fidelity by round-tripping codes that are <em>not</em>
 * valid enum constants (for example {@code "99"} and {@code "ZZ"}) verbatim.
 *
 * <h2>Data isolation</h2>
 * The shared PostgreSQL container is reused across every {@code *IT}, so each
 * mutating test here is {@code @Transactional}: Spring rolls the test transaction
 * back, leaving {@code daily_transaction} empty before and after. Reads are forced
 * to hit the database (rather than the persistence-context first-level cache) by
 * flushing and clearing the {@link EntityManager}, so the assertions exercise the
 * real PostgreSQL round-trip — including the fixed-width {@code CHAR} JDBC bindings
 * and the {@code NUMERIC(11,2)} monetary scale.
 */
@DisplayName("DailyTransactionRepository IT — empty posting-staging table on real PostgreSQL 16 "
        + "(CVTRA06Y / CBTRN01C\u2192CBTRN02C / POSTTRAN)")
class DailyTransactionRepositoryIT extends AbstractIntegrationIT {

    /**
     * A 26-character origination timestamp matching the {@code DALYTRAN-ORIG-TS
     * PIC X(26)} width and the {@code dailytran.txt} format
     * ({@code YYYY-MM-DD HH:MM:SS.mmmmmm}). Because it exactly fills the
     * {@code CHAR(26)} column it round-trips verbatim with no padding or trimming,
     * which lets the read-back assertions check timestamp fidelity (AAP §0.6.4).
     */
    private static final String ORIG_TS = "2022-06-10 19:27:53.000000";

    /** Repository under test — the staging access replacing {@code DALYTRAN-FILE}. */
    @Autowired
    private DailyTransactionRepository dailyTransactionRepository;

    /**
     * JPA entity manager used purely to {@code flush()} pending writes to PostgreSQL
     * and {@code clear()} the first-level cache, so subsequent repository reads issue
     * real {@code SELECT}s against the container database rather than returning
     * managed instances from the persistence context.
     */
    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Builds a fully populated {@link DailyTransaction} mirroring the
     * {@code dailytran.txt} record shape (copybook {@code CVTRA06Y}). All
     * fixed-width {@code CHAR} fields are sized to their exact column widths so they
     * round-trip verbatim: {@code tranId}/{@code cardNum} are 16 characters and
     * {@code origTs} is the 26-character {@link #ORIG_TS}. The processing timestamp
     * ({@code DALYTRAN-PROC-TS}) is left {@code null} because a freshly staged record
     * is <em>unposted</em> — the posting engine ({@code CBTRN02C}) stamps it later.
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
    // Phase 1 — empty posting-staging state (Flyway does not seed daily_transaction).
    // ---------------------------------------------------------------------------------

    /**
     * The staging table is empty on a freshly migrated schema: the {@code V3} seed
     * populates only the master / reference tables, while {@code dailytran.txt} is
     * loaded as batch <em>input</em> by the POSTTRAN job — not by Flyway.
     */
    @Test
    @DisplayName("count() == 0 initially — Flyway V3 does not seed the staging table")
    void countIsZeroInitiallyBecauseFlywayDoesNotSeedStaging() {
        assertThat(dailyTransactionRepository.count())
                .as("daily_transaction is the unposted posting-staging input (CVTRA06Y); "
                        + "Flyway V3 seeds master/reference tables only, so the staging table "
                        + "starts empty and is filled from dailytran.txt as batch INPUT by POSTTRAN")
                .isZero();
    }

    /**
     * {@code findAll()} returns nothing on the empty staging table, confirming the
     * sequential-read entry point ({@code CBTRN01C} driver) sees no records until the
     * daily feed is loaded.
     */
    @Test
    @DisplayName("findAll() is empty on the fresh staging table")
    void findAllIsEmptyOnFreshStagingTable() {
        assertThat(dailyTransactionRepository.findAll())
                .as("no staging records exist until the daily feed is loaded as batch input")
                .isEmpty();
    }

    // ---------------------------------------------------------------------------------
    // Phase 2 — sequential staging read (the CBTRN01C driver feeding CBTRN02C). Mutating
    // tests are @Transactional so the shared staging table is restored to empty by rollback.
    // ---------------------------------------------------------------------------------

    /**
     * Inserts a handful of staging records mirroring the {@code dailytran.txt} shape,
     * reloads them from PostgreSQL and asserts that the raw {@code DALYTRAN-TYPE-CD}
     * is stored and returned <strong>verbatim</strong>. The batch deliberately
     * includes codes that are <em>not</em> valid {@code TRAN-TYPE} enum constants
     * ({@code "99"}, {@code "ZZ"}) to prove the staging layer applies no enum
     * conversion and no validation — that responsibility belongs to
     * {@code TransactionPostingProcessor} downstream. The 26-character origination
     * timestamp is also checked for verbatim {@code CHAR(26)} fidelity (AAP §0.6.4).
     */
    @Test
    @Transactional
    @DisplayName("staging rows read back with RAW tran_type_cd verbatim — no enum conversion, no validation")
    void insertedRecordsAreReadBackWithRawTransactionTypeCodeVerbatim() {
        assertThat(dailyTransactionRepository.count()).as("staging table empty before insert").isZero();

        List<DailyTransaction> dailyFeed = List.of(
                stagingRecord("0000000000683580", "01", "Purchase at Abshire-Lowe",
                        new BigDecimal("504.77"), "4859452612877065"),
                stagingRecord("0000000001774260", "03", "Return item at Nitzsche, Nicolas and Lowe",
                        new BigDecimal("-919.00"), "0927987108636232"),
                stagingRecord("0000000006292564", "02", "Payment received",
                        new BigDecimal("67.88"), "6009619150674526"),
                stagingRecord("0000000009101861", "99", "Raw code 99 — not a known TRAN-TYPE enum",
                        new BigDecimal("281.77"), "8040580410348680"),
                stagingRecord("0000000010142252", "ZZ", "Raw code ZZ — alpha, never enum-valid",
                        new BigDecimal("454.66"), "5656830544981216"));

        dailyTransactionRepository.saveAll(dailyFeed);
        entityManager.flush();
        entityManager.clear(); // force findAll() to read from PostgreSQL, not the L1 cache

        List<DailyTransaction> reloaded = dailyTransactionRepository.findAll();
        assertThat(reloaded).as("every staged record is read back by the sequential scan").hasSize(5);

        // RAW fidelity: the two-character code is persisted and returned exactly as written,
        // including the codes ("99","ZZ") that are NOT valid TransactionTypeCode constants (01..07).
        assertThat(reloaded)
                .as("DALYTRAN-TYPE-CD is stored verbatim — staging applies no enum converter")
                .extracting(DailyTransaction::getTranTypeCd)
                .containsExactlyInAnyOrder("01", "03", "02", "99", "ZZ");

        // 26-character origination timestamp preserved verbatim through CHAR(26).
        assertThat(reloaded)
                .as("DALYTRAN-ORIG-TS X(26) preserved exactly")
                .extracting(DailyTransaction::getOrigTs)
                .containsOnly(ORIG_TS);
    }

    /**
     * A keyed read by {@code DALYTRAN-ID} ({@code findById}) returns the known
     * inserted staging record with all of its fields intact, and a lookup for an
     * absent key returns an empty {@link Optional}.
     */
    @Test
    @Transactional
    @DisplayName("findById(DALYTRAN-ID) returns the known staging record; absent key returns empty")
    void findByIdReturnsTheKnownStagingRecord() {
        String tranId = "0000000010229018";
        dailyTransactionRepository.save(stagingRecord(
                tranId, "01", "Purchase at Gislason-Medhurst",
                new BigDecimal("849.99"), "7379335634661142"));
        entityManager.flush();
        entityManager.clear(); // force findById() to issue a real SELECT

        Optional<DailyTransaction> found = dailyTransactionRepository.findById(tranId);

        assertThat(found).as("keyed READ by DALYTRAN-ID returns the staged record").isPresent();
        DailyTransaction record = found.orElseThrow();
        assertThat(record.getTranId()).isEqualTo(tranId);
        assertThat(record.getTranTypeCd()).isEqualTo("01");
        assertThat(record.getCardNum()).isEqualTo("7379335634661142");
        assertThat(record.getOrigTs()).isEqualTo(ORIG_TS);
        assertThat(record.getTranAmt()).isEqualByComparingTo("849.99");

        assertThat(dailyTransactionRepository.findById("9999999999999999"))
                .as("a key with no staged record returns Optional.empty")
                .isEmpty();
    }

    // ---------------------------------------------------------------------------------
    // Phase 3 — persist / delete round-trips. @Transactional rollback restores empty.
    // ---------------------------------------------------------------------------------

    /**
     * {@code saveAll} persists a batch whose size matches the subsequent
     * {@code count()} (verified both through the repository and at the database level
     * via {@code JdbcTemplate}), and {@code deleteAll} clears the table back to empty.
     * The surrounding test transaction rolls back regardless, so the shared staging
     * table is left empty for every other {@code *IT}.
     */
    @Test
    @Transactional
    @DisplayName("saveAll then deleteAll round-trips the row count and restores the empty staging table")
    void saveAllThenDeleteAllRoundTripsRowCountAndRestoresEmpty() {
        assertThat(dailyTransactionRepository.count()).as("staging table empty before insert").isZero();

        List<DailyTransaction> dailyFeed = List.of(
                stagingRecord("0000000016259484", "03", "Return item at Sipes Inc",
                        new BigDecimal("-56.77"), "4011500891777367"),
                stagingRecord("0000000017874199", "01", "Purchase at Legros Group",
                        new BigDecimal("373.66"), "8040580410348680"),
                stagingRecord("0000000019065428", "03", "Return item at Turcotte Group",
                        new BigDecimal("-535.88"), "6503535181795992"),
                stagingRecord("0000000021711604", "01", "Purchase at Gleason, Shanahan and Reynolds",
                        new BigDecimal("416.11"), "9501733721429893"));

        dailyTransactionRepository.saveAll(dailyFeed);
        dailyTransactionRepository.flush();

        assertThat(dailyTransactionRepository.count())
                .as("count() matches the number of staged records")
                .isEqualTo(4L);
        assertThat(countRows("daily_transaction"))
                .as("database-level row count via JdbcTemplate agrees with the repository")
                .isEqualTo(4L);

        dailyTransactionRepository.deleteAll();
        dailyTransactionRepository.flush();

        assertThat(dailyTransactionRepository.count())
                .as("deleteAll empties the staging table")
                .isZero();
    }

    /**
     * The {@code DALYTRAN-AMT PIC S9(09)V99} monetary value round-trips through the
     * {@code NUMERIC(11,2)} column at scale&nbsp;2 with its sign and precision intact.
     * A scale-1 literal is normalized to scale&nbsp;2 by the column definition. Values
     * are compared with {@code compareTo} (via {@code isEqualByComparingTo}) so the
     * assertion is about numeric equality, and the scale is checked explicitly. A final
     * read straight from PostgreSQL via {@code JdbcTemplate} confirms the value is
     * stored natively at scale&nbsp;2 (no JPA caching involved).
     */
    @Test
    @Transactional
    @DisplayName("tran_amt round-trips at NUMERIC(11,2) — scale 2 and sign preserved (compareTo)")
    void tranAmtRoundTripsAtScaleTwoPreservingSignAndPrecision() {
        dailyTransactionRepository.saveAll(List.of(
                stagingRecord("0000000025430891", "01", "Debit with cents",
                        new BigDecimal("94.33"), "3260763612337560"),
                stagingRecord("0000000028097268", "03", "Credit (negative amount)",
                        new BigDecimal("-250.22"), "7094142751055551"),
                stagingRecord("0000000030755266", "01", "Scale-1 literal normalized by the column",
                        new BigDecimal("829.5"), "3766281984155154")));
        entityManager.flush();
        entityManager.clear(); // re-read from PostgreSQL so scale reflects the NUMERIC(11,2) column

        DailyTransaction debit = dailyTransactionRepository.findById("0000000025430891").orElseThrow();
        assertThat(debit.getTranAmt()).isEqualByComparingTo("94.33");
        assertThat(debit.getTranAmt().scale()).as("NUMERIC(11,2) yields scale 2").isEqualTo(2);

        DailyTransaction credit = dailyTransactionRepository.findById("0000000028097268").orElseThrow();
        assertThat(credit.getTranAmt()).as("negative amounts retain their sign").isEqualByComparingTo("-250.22");
        assertThat(credit.getTranAmt().scale()).isEqualTo(2);

        DailyTransaction normalized = dailyTransactionRepository.findById("0000000030755266").orElseThrow();
        assertThat(normalized.getTranAmt()).isEqualByComparingTo("829.50");
        assertThat(normalized.getTranAmt().scale())
                .as("a scale-1 input is normalized to scale 2 by NUMERIC(11,2)")
                .isEqualTo(2);

        // DB-native proof (bypasses JPA entirely): read the raw NUMERIC from PostgreSQL.
        BigDecimal dbAmount = jdbcTemplate.queryForObject(
                "SELECT tran_amt FROM daily_transaction WHERE tran_id = ?",
                BigDecimal.class, "0000000025430891");
        assertThat(dbAmount).as("value is stored natively in PostgreSQL").isEqualByComparingTo("94.33");
        assertThat(dbAmount.scale()).isEqualTo(2);
    }
}

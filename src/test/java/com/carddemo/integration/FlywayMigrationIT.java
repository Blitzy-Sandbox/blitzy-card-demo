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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test that proves the Flyway migrations {@code V1}/{@code V2}/{@code V3}
 * build the <em>real</em> CardDemo schema, secondary indexes and seed data inside the
 * PostgreSQL&nbsp;16 Testcontainer wired by {@link AbstractIntegrationIT} — with Hibernate
 * running under {@code ddl-auto=validate} so the Spring context starts only when every
 * {@code @Entity} mapping agrees with the migrated schema.
 *
 * <h2>What this guards</h2>
 * <ul>
 *   <li><strong>Migration&nbsp;&rarr;&nbsp;entity contract (Gate&nbsp;5)</strong> — the
 *       columns, types and indexes the JPA layer maps against actually exist, with the
 *       deliberate VSAM-fidelity quirks preserved (for example the COBOL misspelling
 *       {@code expiraion_date}, {@code CHAR(9)} {@code ssn}, {@code CHAR(26)} timestamp
 *       text, and the {@code type_cd} composite-key column).</li>
 *   <li><strong>Seed-fixture parity (Gate&nbsp;1/4)</strong> — the nine fixed-width ASCII
 *       fixtures decode into exactly the expected relational rows, with COBOL zoned
 *       overpunch decoded to signed {@link BigDecimal} (scale&nbsp;2), SSN leading zeros
 *       intact, and legacy plaintext passwords upgraded to BCrypt (constraint&nbsp;C-003).</li>
 * </ul>
 *
 * <h2>Real infrastructure, no mocks</h2>
 * The class extends {@link AbstractIntegrationIT}, so it inherits the shared,
 * credential-free PostgreSQL&nbsp;16 + LocalStack singletons, the {@code test} profile, and
 * the autowired {@code jdbcTemplate}. There is <strong>no H2 and no mocking</strong>: every
 * assertion runs against the live container and the actual {@code V1}/{@code V2}/{@code V3}
 * SQL, because an in-memory database would silently diverge from the production contract.
 *
 * <h2>Strictly read-only</h2>
 * The shared Flyway seed is consumed by sibling integration tests and the gate-verification
 * suite, so this class is deliberately <strong>not</strong> {@code @Transactional} and issues
 * only {@code SELECT}/{@code COUNT} queries — it never mutates a row. Seeded master/reference
 * row counts are stable under the batch pipeline (posting updates balances in place and never
 * changes row counts), which keeps the exact-count assertions deterministic even though the
 * container is shared across the suite.
 *
 * <h2>Note on {@code daily_transaction} and {@code transactions}</h2>
 * The {@code daily_transaction} staging table <strong>is</strong> seeded by {@code V3} from
 * {@code app/data/ASCII/dailytran.txt} (copybook {@code CVTRA06Y}, 300 rows): it is the daily
 * posting feed the {@code CBTRN02C} pipeline reads as input, so a freshly migrated schema holds
 * the full 300-row feed (the {@code DALYTRAN-AMT} zoned-overpunch sign decoded to
 * {@code NUMERIC(11,2)}). The posted {@code transactions} table has no ASCII fixture and remains
 * empty until the posting batch runs. This test verifies the seeded staging count and the empty
 * posted table explicitly.
 */
@DisplayName("Flyway migration IT — V1/V2/V3 schema, indexes & seed on real PostgreSQL 16")
public class FlywayMigrationIT extends AbstractIntegrationIT {

    /** The 11 business tables migrated from the legacy VSAM KSDS clusters by {@code V1}. */
    private static final List<String> APPLICATION_TABLES = List.of(
            "accounts",
            "cards",
            "card_xref",
            "customers",
            "transactions",
            "daily_transaction",
            "transaction_category_balance",
            "disclosure_group",
            "transaction_type",
            "transaction_category",
            "users");

    // =====================================================================================
    // Phase 1 — schema existence and parity-critical column contracts (V1).
    // =====================================================================================

    /**
     * Asserts that {@code V1} created exactly the 11 application tables. The Flyway history
     * table ({@code flyway_schema_history}) and the Spring Batch {@code BATCH_*} metadata
     * tables are expected infrastructure and are filtered out of the exact-count check.
     */
    @Test
    @DisplayName("V1: all 11 application tables exist (system tables excluded)")
    void allElevenApplicationTablesExist() {
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_type = 'BASE TABLE'",
                String.class);

        // Every migrated business table must be present by name.
        assertThat(tables).containsAll(APPLICATION_TABLES);

        // Once Flyway's own history table and the Spring Batch metadata tables are removed,
        // exactly the 11 application tables remain (no stray or missing table).
        List<String> applicationTables = tables.stream()
                .filter(name -> !"flyway_schema_history".equals(name))
                .filter(name -> !name.startsWith("batch_"))
                .toList();
        assertThat(applicationTables)
                .containsExactlyInAnyOrderElementsOf(APPLICATION_TABLES);
    }

    /**
     * Spot-checks the parity-critical column names and string types that encode VSAM
     * fidelity: the deliberate COBOL misspelling {@code expiraion_date} on both
     * {@code accounts} and {@code cards}, the {@code CHAR(9)} {@code ssn} that protects
     * leading zeros, the {@code CHAR(26)} transaction timestamp text, and the
     * {@code transaction_category_balance} composite-key column {@code type_cd} (never
     * {@code tran_type_cd}).
     */
    @Test
    @DisplayName("V1: parity quirks — expiraion_date misspelling, CHAR ssn/timestamps, type_cd key")
    void parityColumnNamesAndStringTypesPreserved() {
        // Deliberate COBOL misspelling ACCT-EXPIRAION-DATE / CARD-EXPIRAION-DATE preserved;
        // the correctly spelled column must NOT exist.
        assertThat(columnExists("accounts", "expiraion_date")).isTrue();
        assertThat(columnExists("cards", "expiraion_date")).isTrue();
        assertThat(columnExists("accounts", "expiration_date")).isFalse();

        // SSN is CHAR(9) text, so leading zeros survive with no numeric truncation.
        assertThat(columnDataType("customers", "ssn")).isEqualTo("character");
        assertThat(columnCharMaxLength("customers", "ssn")).isEqualTo(9);

        // Transaction timestamps are CHAR(26) text, preserving YYYY-MM-DD HH:MM:SS.mmmmmm.
        assertThat(columnDataType("transactions", "orig_ts")).isEqualTo("character");
        assertThat(columnCharMaxLength("transactions", "orig_ts")).isEqualTo(26);
        assertThat(columnDataType("transactions", "proc_ts")).isEqualTo("character");
        assertThat(columnCharMaxLength("transactions", "proc_ts")).isEqualTo(26);

        // The composite key uses type_cd (not tran_type_cd) on transaction_category_balance.
        assertThat(columnExists("transaction_category_balance", "type_cd")).isTrue();
        assertThat(columnExists("transaction_category_balance", "tran_type_cd")).isFalse();
    }

    /**
     * Asserts that monetary columns are {@code NUMERIC} with scale&nbsp;2, the relational
     * realization of COBOL {@code COMP-3}/{@code PIC S9(n)V99} packed-decimal money. A
     * floating-point type here would break decimal-exactness parity, so the scale is checked
     * explicitly on representative columns across three tables.
     */
    @Test
    @DisplayName("V1: monetary columns are NUMERIC scale 2 (BigDecimal parity, no float)")
    void monetaryColumnsAreNumericWithScaleTwo() {
        assertThat(columnDataType("accounts", "curr_bal")).isEqualTo("numeric");
        assertThat(columnNumericScale("accounts", "curr_bal")).isEqualTo(2);

        assertThat(columnDataType("transactions", "tran_amt")).isEqualTo("numeric");
        assertThat(columnNumericScale("transactions", "tran_amt")).isEqualTo(2);

        assertThat(columnDataType("disclosure_group", "dis_int_rate")).isEqualTo("numeric");
        assertThat(columnNumericScale("disclosure_group", "dis_int_rate")).isEqualTo(2);
    }

    // =====================================================================================
    // Phase 2 — secondary indexes (V2): VSAM AIX / PATH equivalents.
    // =====================================================================================

    /**
     * Asserts that {@code V2} created the secondary indexes that stand in for the legacy VSAM
     * alternate indexes. Index names are intentionally not hardcoded: the assertion is made on
     * the indexed <em>column</em> (via {@code pg_indexes.indexdef}) so it stays robust if a
     * name changes — {@code cards.card_acct_id} (CARDAIX), {@code card_xref.xref_acct_id}, and
     * the {@code transactions.proc_ts} index backing the processing-date finder.
     */
    @Test
    @DisplayName("V2: VSAM AIX-equivalent indexes exist on card_acct_id / xref_acct_id / proc_ts")
    void secondaryIndexesFromV2Exist() {
        assertThat(indexDefinitions("cards"))
                .anyMatch(def -> def.contains("card_acct_id"));
        assertThat(indexDefinitions("card_xref"))
                .anyMatch(def -> def.contains("xref_acct_id"));
        assertThat(indexDefinitions("transactions"))
                .anyMatch(def -> def.contains("proc_ts"));
    }

    // =====================================================================================
    // Phase 3 — seed row counts (V3): decoded from the nine ASCII fixtures.
    // =====================================================================================

    /**
     * Asserts the exact seeded row count of every fixture-backed table against the row count
     * of its source ASCII fixture, proving {@code V3} loaded all 9 fixtures in full with no
     * dropped or duplicated records — the 8 reference/master tables plus the
     * {@code daily_transaction} staging feed ({@code dailytran.txt}).
     */
    @Test
    @DisplayName("V3: fixture-backed tables seeded with exact ASCII-fixture row counts (all 9 fixtures)")
    void referenceTableSeedCountsMatchFixtures() {
        assertThat(countRows("accounts")).isEqualTo(50L);                      // acctdata.txt
        assertThat(countRows("cards")).isEqualTo(50L);                         // carddata.txt
        assertThat(countRows("card_xref")).isEqualTo(50L);                     // cardxref.txt
        assertThat(countRows("customers")).isEqualTo(50L);                     // custdata.txt
        assertThat(countRows("disclosure_group")).isEqualTo(51L);              // discgrp.txt
        assertThat(countRows("transaction_category_balance")).isEqualTo(50L);  // tcatbal.txt
        assertThat(countRows("transaction_category")).isEqualTo(18L);          // trancatg.txt
        assertThat(countRows("transaction_type")).isEqualTo(7L);               // trantype.txt
        assertThat(countRows("daily_transaction")).isEqualTo(300L);            // dailytran.txt
    }

    /**
     * Asserts that the posted {@code transactions} table starts empty. It has no ASCII
     * fixture and is populated only at runtime by the transaction-posting batch
     * ({@code CBTRN02C}); batch integration tests rely on this empty starting state.
     */
    @Test
    @DisplayName("V3: posted transactions table starts empty (populated by posting batch)")
    void postedTransactionsTableStartsEmpty() {
        assertThat(countRows("transactions")).isZero();
    }

    /**
     * Asserts that the {@code daily_transaction} staging table is seeded with the full
     * {@code dailytran.txt} feed (copybook {@code CVTRA06Y}, 300 rows) on a freshly migrated
     * schema. The staging table is the daily posting feed the {@code CBTRN02C} pipeline reads as
     * input; the sibling {@code DailyTransactionRepositoryIT} and {@code RepositoryPersistenceIT}
     * assert the same seeded staging contract and the per-field decode fidelity.
     */
    @Test
    @DisplayName("V3: daily_transaction staging seeded with the 300-row dailytran.txt feed")
    void dailyTransactionStagingSeededFromFixture() {
        assertThat(countRows("daily_transaction")).isEqualTo(300L);
    }

    /**
     * Asserts that the user table is seeded and contains both archetypal accounts the secured
     * REST surface depends on: the administrator {@code ADMIN001} (user-type {@code 'A'}) and
     * the standard user {@code USER0001} (user-type {@code 'U'}).
     */
    @Test
    @DisplayName("V3: users seeded incl. ADMIN001 (type A) and USER0001 (type U)")
    void seededUsersIncludeAdminAndStandard() {
        assertThat(countRows("users")).isGreaterThanOrEqualTo(2L);

        String adminType = jdbcTemplate.queryForObject(
                "SELECT user_type FROM users WHERE user_id = ?",
                String.class, SEEDED_ADMIN_USER_ID);
        assertThat(adminType).isEqualTo(SEEDED_ADMIN_USER_TYPE);

        String standardType = jdbcTemplate.queryForObject(
                "SELECT user_type FROM users WHERE user_id = ?",
                String.class, SEEDED_STANDARD_USER_ID);
        assertThat(standardType).isEqualTo(SEEDED_STANDARD_USER_TYPE);
    }

    // =====================================================================================
    // Phase 4 — decoded-value parity assertions and validate-mode confirmation.
    // =====================================================================================

    /**
     * Asserts that COBOL zoned-decimal overpunch was decoded into a signed {@link BigDecimal}
     * with scale&nbsp;2 — never stored as raw overpunched text. The first {@code acctdata.txt}
     * record stores its amounts as digit strings with a trailing zoned-overpunch sign
     * character (a left-brace encodes {@code +0} on the final digit), so {@code 0000000194} and
     * {@code 0000002020} must decode to {@code +194.00} and {@code +2020.00} for
     * {@code curr_bal} and {@code credit_limit}. Comparison uses {@code compareTo} (value
     * equality) plus an explicit scale check.
     */
    @Test
    @DisplayName("V3 parity: zoned-overpunch amounts decoded to signed BigDecimal (scale 2)")
    void overpunchDecodedToSignedNumeric() {
        BigDecimal currBal = jdbcTemplate.queryForObject(
                "SELECT curr_bal FROM accounts WHERE acct_id = ?", BigDecimal.class, 1L);
        BigDecimal creditLimit = jdbcTemplate.queryForObject(
                "SELECT credit_limit FROM accounts WHERE acct_id = ?", BigDecimal.class, 1L);

        assertThat(currBal).isNotNull();
        assertThat(currBal.scale()).isEqualTo(2);
        assertThat(currBal).isEqualByComparingTo(new BigDecimal("194.00"));

        assertThat(creditLimit).isNotNull();
        assertThat(creditLimit.scale()).isEqualTo(2);
        assertThat(creditLimit).isEqualByComparingTo(new BigDecimal("2020.00"));
    }

    /**
     * Asserts that a customer SSN retains its leading zeros, proving the {@code CHAR(9)} string
     * mapping prevented numeric truncation. Customer&nbsp;1 in {@code custdata.txt} has SSN
     * {@code 020973888}.
     */
    @Test
    @DisplayName("V3 parity: customer SSN retains leading zeros (CHAR(9), no truncation)")
    void ssnLeadingZerosPreserved() {
        String ssn = jdbcTemplate.queryForObject(
                "SELECT ssn FROM customers WHERE cust_id = ?", String.class, 1L);

        assertThat(ssn).isNotNull();
        assertThat(ssn).hasSize(9);
        assertThat(ssn).startsWith("0");
        assertThat(ssn).isEqualTo("020973888");
    }

    /**
     * Asserts that the legacy plaintext passwords were upgraded to BCrypt hashes (constraint
     * C-003): every seeded password is a 60-character {@code $2a$}/{@code $2b$}/{@code $2y$}
     * hash, and none is the legacy plaintext literal. The strict format-and-length check alone
     * guarantees no 8-character plaintext password could be stored.
     */
    @Test
    @DisplayName("V3 security (C-003): user passwords stored as BCrypt hashes, never plaintext")
    void bcryptPasswordsSeeded() {
        List<String> passwords =
                jdbcTemplate.queryForList("SELECT password FROM users", String.class);

        assertThat(passwords).isNotEmpty();
        assertThat(passwords).allSatisfy(password -> {
            assertThat(password).as("BCrypt hash length").hasSize(60);
            assertThat(password).as("BCrypt $2a$/$2b$/$2y$ format")
                    .matches("^\\$2[aby]\\$\\d{2}\\$.{53}$");
        });
        assertThat(passwords).noneMatch(password -> password.equalsIgnoreCase("PASSWORD"));
    }

    /**
     * Confirms that Hibernate's {@code ddl-auto=validate} pass succeeded. Reaching this test
     * at all means the Spring context started, which under {@code validate} mode proves every
     * {@code @Entity} mapping agrees with the Flyway-built schema. A trivial live query makes
     * the success deterministic rather than merely implicit in context start-up.
     */
    @Test
    @DisplayName("Hibernate ddl-auto=validate passed: context is up and schema is queryable")
    void hibernateValidateModePasses() {
        Integer one = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        assertThat(one).isEqualTo(1);
    }

    /**
     * Asserts that Flyway recorded migrations {@code 1}, {@code 2} and {@code 3} as applied
     * successfully in {@code flyway_schema_history}, proving the full schema/index/seed chain
     * ran (and not, for example, a partial or failed migration).
     */
    @Test
    @DisplayName("Flyway history shows V1/V2/V3 applied successfully")
    void flywaySchemaHistoryShowsV1V2V3Applied() {
        List<String> appliedVersions = jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history "
                        + "WHERE success = true AND version IN ('1', '2', '3') "
                        + "ORDER BY version",
                String.class);
        assertThat(appliedVersions).containsExactly("1", "2", "3");
    }

    // =====================================================================================
    // Read-only schema-introspection helpers (parameterized; injection-safe by construction).
    // =====================================================================================

    /**
     * Returns whether a column exists on a public-schema table.
     *
     * @param table  the table name
     * @param column the column name
     * @return {@code true} if the column exists
     */
    private boolean columnExists(String table, String column) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
                Integer.class, table, column);
        return count != null && count > 0;
    }

    /**
     * Returns the {@code information_schema} {@code data_type} of a column (for example
     * {@code "character"}, {@code "character varying"} or {@code "numeric"}).
     *
     * @param table  the table name
     * @param column the column name
     * @return the SQL data type name
     */
    private String columnDataType(String table, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT data_type FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
                String.class, table, column);
    }

    /**
     * Returns the declared maximum character length of a {@code CHAR}/{@code VARCHAR} column.
     *
     * @param table  the table name
     * @param column the column name
     * @return the maximum character length
     */
    private Integer columnCharMaxLength(String table, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT character_maximum_length FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
                Integer.class, table, column);
    }

    /**
     * Returns the numeric scale (digits after the decimal point) of a {@code NUMERIC} column.
     *
     * @param table  the table name
     * @param column the column name
     * @return the numeric scale
     */
    private Integer columnNumericScale(String table, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT numeric_scale FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
                Integer.class, table, column);
    }

    /**
     * Returns the {@code CREATE INDEX} definitions for every index on a public-schema table,
     * used to assert index presence by indexed column rather than by index name.
     *
     * @param table the table name
     * @return the list of index definitions
     */
    private List<String> indexDefinitions(String table) {
        return jdbcTemplate.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND tablename = ?",
                String.class, table);
    }
}


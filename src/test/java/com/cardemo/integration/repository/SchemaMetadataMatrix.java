/*
 * ******************************************************************
 * Program     : SchemaMetadataMatrix.java
 * Component   : Integration test support, resident at
 *               src/test/java/com/cardemo/integration/repository
 * Application : CardDemo
 * Type        : JUnit 5 / AssertJ test support - declares no Spring
 *               context, no container and no test method of its own
 * Function    : Carries the complete expected PostgreSQL 16 metadata
 *               contract for all eleven business tables - column
 *               ordinal, SQL type, length, precision, scale,
 *               nullability, primary-key name and column order,
 *               foreign-key name, column order and target, index name,
 *               uniqueness, access method and column order, unique
 *               constraints and check constraints - and asserts a live
 *               schema against it. One declaration site, so a drifting
 *               column cannot be correct in one test and wrong in
 *               another.
 * Source      : app/catlg/LISTCAT.txt:1-3956 @ 7756d89 (key lengths and
 *               average record lengths of the ten catalogued clusters)
 * Source      : app/jcl/DUSRSECJ.jcl:65-66 @ 7756d89
 *               (KEYS(8,0) RECORDSIZE(80,80) for USRSEC, which the
 *               catalogue does not carry)
 * Source      : app/cpy/CVACT01Y.cpy, CVACT02Y.cpy, CVACT03Y.cpy,
 *               CVCUS01Y.cpy, CVTRA01Y.cpy, CVTRA02Y.cpy, CVTRA03Y.cpy,
 *               CVTRA04Y.cpy, CVTRA05Y.cpy, CVTRA06Y.cpy, CSUSR01Y.cpy
 *               @ 7756d89 (the eleven record layouts whose picture
 *               clauses fix every width, precision and scale below)
 * Source      : app/cbl/CBACT04C.cbl:1-21 (banner convention),
 *               CONTRIBUTING.md:33-34 (repository hygiene) @ 7756d89
 * Note        : No COBOL analogue for a test support type exists; this
 *               is new capability mandated by Rule 1 Clause A.
 * ******************************************************************
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 * ******************************************************************
 */
package com.cardemo.integration.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The complete expected PostgreSQL metadata contract for the eleven business tables, and the assertion that
 * a live schema still matches it.
 *
 * <h2>1. What it does</h2>
 *
 * <p>Without this type the repository tier would assert
 * metadata in fragments: the shared schema test checking that the eleven tables are present, that exactly
 * three non-unique alternate indexes exist, and that exactly four tables carry a version column, while
 * each per-table test asserted whichever handful of columns its own behavioural tests happened to touch.
 * Every one of those assertions is true and none of them is sufficient, because none of them can detect
 * <em>type-compatible drift</em>: widening {@code CHAR(10)} to {@code CHAR(11)}, dropping a scale from
 * {@code NUMERIC(12,2)} to {@code NUMERIC(12,0)}, reordering two columns of a composite key, retargeting a
 * foreign key at a different parent, or losing a check constraint would each leave the tier green. For a
 * migration whose whole contract is that a field's width and precision come from a frozen picture clause,
 * that is the most consequential blind spot available.
 *
 * <p>This type therefore declares every facet of every column, key, index and
 * constraint <strong>once</strong>, and {@link #assertTableMatches(JdbcTemplate, String)} compares the live
 * catalogue against it exhaustively - by exact equality on ordered lists, never by containment, so a
 * <em>missing</em> facet and an <em>extra</em> facet both fail. The shared schema test drives it over all
 * eleven tables as a parameterized authority; each per-table test drives it for its own table so that a
 * failure surfaces next to the behaviour it breaks.
 *
 * <h2>2. How to build, run and test</h2>
 *
 * <p>This file declares no test method and is therefore collected by neither Surefire nor Failsafe - which is
 * correct, and is the same arrangement {@code HarnessLifecycleRecord} in this package uses. It is exercised
 * only through the classes that call it, every one of which extends
 * {@link AbstractRepositoryIntegrationTest} and therefore runs against the harness's Testcontainers
 * PostgreSQL 16 with migrations {@code V1}, {@code V2} and {@code V3} applied. A reachable Docker socket is a
 * prerequisite; where none is available the correct report is that the tier is <em>blocked</em>, never an
 * untested pass. Run the tier with {@code ./mvnw -B -ntp clean verify}.
 *
 * <h2>3. Key configuration and defaults</h2>
 *
 * <p>None. This type reads no property, holds no mutable state and constructs nothing. Every expected value
 * below is a literal that was <strong>measured from the live schema</strong> the migrations produce and then
 * checked against the picture clause that fixes it, rather than transcribed from prose - which matters
 * because two of the widths are ones that prose about this schema has previously got wrong.
 *
 * <p>Three facets deserve calling out because they look like defects and are not:
 *
 * <ul>
 *   <li><strong>{@code acct_expiraion_date} and {@code card_expiraion_date} are misspelled.</strong> The
 *       spelling is {@code ACCT-EXPIRAION-DATE} in {@code app/cpy/CVACT01Y.cpy} and
 *       {@code CARD-EXPIRAION-DATE} in {@code app/cpy/CVACT02Y.cpy}. The corpus is frozen and the field
 *       contract is bidirectional, so the misspelling is part of the contract and correcting it here would
 *       be a parity break, not a tidy-up.</li>
 *   <li><strong>Three columns carry a type that differs from every sibling spelling of the same concept.</strong>
 *       {@code transaction_category.tran_type_cd} and {@code transaction_category_balance.tran_type_cd} are
 *       {@code character varying(2)} while every other {@code tran_type_cd} is {@code character(2)};
 *       {@code transaction_category_balance.acct_id} is {@code bigint} while every other {@code acct_id} is
 *       {@code numeric(11,0)}; and {@code disclosure_group.tran_cat_cd} and
 *       {@code transaction_category_balance.tran_cat_cd} are {@code integer} while every other
 *       {@code tran_cat_cd} is {@code numeric(4,0)}. Severity <strong>Low</strong>: they are the mappings
 *       {@code V1__create_schema.sql} declares and that Hibernate's {@code ddl-auto: validate} accepts, so
 *       the application runs; they are recorded here exactly as they are rather than normalised, because a
 *       matrix that asserted what the schema <em>ought</em> to say would fail against the schema that
 *       exists. Remediation, for the owner of the migration: align the three spellings in a future
 *       migration, at which point this matrix is the thing that will tell you it worked.</li>
 *   <li><strong>Nullability is asserted centrally rather than per column.</strong> Every one of the
 *       eighty-seven columns is {@code NOT NULL}, which is a schema-wide requirement rather than a
 *       column-by-column choice, so {@link #assertTableMatches(JdbcTemplate, String)} asserts that no column
 *       of the table is nullable. That is strictly stronger than carrying an expected {@code false} on each
 *       column contract, because a newly added nullable column fails it without anyone having to remember to
 *       extend a list.</li>
 * </ul>
 *
 * <h2>4. Common failure modes and troubleshooting</h2>
 *
 * <dl>
 *   <dt>A column assertion fails on {@code characterMaximumLength} or on {@code numericScale}</dt>
 *   <dd>Severity <strong>Blocker</strong> until diagnosed. Either {@code V1__create_schema.sql} changed or an
 *       entity's {@code columnDefinition} changed and Hibernate's {@code validate} did not catch it, because
 *       {@code validate} compares type <em>compatibility</em> and not geometry. Check the picture clause in
 *       the copybook named in the banner above before changing anything here: the copybook wins, and this
 *       matrix is corrected only when the measurement disagrees with what the matrix claims, never when it
 *       disagrees with what the schema ought to be.</dd>
 *   <dt>A foreign-key assertion fails on column order</dt>
 *   <dd>The composite foreign keys - {@code fk06}, {@code fk08} and {@code fk10} - all target
 *       {@code transaction_category(tran_type_cd, tran_cat_cd)}, and the order of those two columns is the
 *       order the copybook declares. A reversed pair still resolves and still constrains, so nothing fails at
 *       runtime; only an ordered assertion catches it.</dd>
 *   <dt>An index assertion fails with an extra index nobody added</dt>
 *   <dd>PostgreSQL creates an index to back every primary key, so each table's expected index list carries
 *       its {@code pk_} index as well as any explicit one. An index appearing beyond those is either a new
 *       {@code V2} entry or an accidental unique constraint; the latter would break a legacy alternate key,
 *       which is non-unique by definition.</dd>
 *   <dt>{@code IllegalArgumentException: no metadata contract is declared for table ...}</dt>
 *   <dd>A caller asked for a table this matrix does not know. Either the name is misspelled or a twelfth
 *       table now exists, in which case its contract belongs here - measured, not guessed.</dd>
 * </dl>
 */
public final class SchemaMetadataMatrix {

    /**
     * The eleven business tables {@code V1__create_schema.sql} creates, in the order this matrix declares
     * them.
     *
     * <p>Framework-owned tables are deliberately absent: Spring Batch's {@code batch_*} tables and Flyway's
     * {@code flyway_schema_history} are created by their own tooling, are not mapped by any entity, and would
     * make a census of "the schema" say nothing about the migration.
     */
    private static final List<String> BUSINESS_TABLES = List.of(
            "account",
            "card",
            "card_cross_reference",
            "customer",
            "daily_transaction",
            "disclosure_group",
            "transaction",
            "transaction_category",
            "transaction_category_balance",
            "transaction_type",
            "user_security");

    /**
     * Not instantiable.
     *
     * <p>Every member is static and the type holds no state, so an instance would carry no meaning. Declared
     * private rather than omitted so that the default constructor cannot be relied on by accident.
     */
    private SchemaMetadataMatrix() {
        throw new AssertionError("SchemaMetadataMatrix is a static holder and must not be instantiated");
    }

    /**
     * One column's complete geometry, exactly as {@code information_schema.columns} reports it.
     *
     * <p>{@code characterMaximumLength} is populated for character types and {@code null} for numeric ones;
     * {@code numericPrecision} and {@code numericScale} are the reverse. Carrying all three and asserting the
     * {@code null}s is deliberate - a {@code CHAR(10)} silently becoming {@code NUMERIC(10)} would satisfy a
     * check that only looked at whichever field happened to be populated.
     *
     * @param ordinal the one-based position of the column in the table
     * @param name the column name, lower case as PostgreSQL folds it
     * @param dataType the {@code information_schema} type name, for example {@code character} or
     *     {@code numeric}
     * @param characterMaximumLength the declared character width, or {@code null} for a non-character type
     * @param numericPrecision the declared total digits, or {@code null} for a non-numeric type
     * @param numericScale the declared digits after the point, or {@code null} for a non-numeric type
     */
    public record ColumnContract(int ordinal, String name, String dataType, Integer characterMaximumLength,
            Integer numericPrecision, Integer numericScale) {
    }

    /**
     * One foreign key's complete identity: its name, its ordered columns, and its ordered target.
     *
     * @param name the constraint name, which the migration assigns explicitly so a failure names the rule
     * @param columns the constrained columns, in the order the constraint declares them
     * @param referencedTable the parent table
     * @param referencedColumns the parent columns, in the order the constraint declares them
     */
    public record ForeignKeyContract(String name, List<String> columns, String referencedTable,
            List<String> referencedColumns) {
    }

    /**
     * One index's complete identity.
     *
     * @param name the index name
     * @param unique whether the index enforces uniqueness
     * @param primary whether the index backs the table's primary key
     * @param columns the indexed columns, in index order
     */
    public record IndexContract(String name, boolean unique, boolean primary, List<String> columns) {
    }

    /**
     * The complete metadata contract for one table.
     *
     * @param table the table name
     * @param columns every column, in ordinal order
     * @param primaryKeyName the primary-key constraint name
     * @param primaryKeyColumns the primary-key columns, in key order
     * @param foreignKeys every foreign key, ordered by constraint name
     * @param indexes every index, ordered by index name
     * @param uniqueConstraintNames every {@code UNIQUE} table constraint, ordered by name
     * @param checkConstraintNames every {@code CHECK} table constraint, ordered by name
     */
    public record TableContract(String table, List<ColumnContract> columns, String primaryKeyName,
            List<String> primaryKeyColumns, List<ForeignKeyContract> foreignKeys, List<IndexContract> indexes,
            List<String> uniqueConstraintNames, List<String> checkConstraintNames) {
    }

    /**
     * The eleven tables this matrix declares a contract for.
     *
     * @return an immutable list of table names, never empty
     */
    public static List<String> businessTables() {
        return BUSINESS_TABLES;
    }

    /**
     * The declared contract for one table.
     *
     * @param table the table name; must be one of {@link #businessTables()}
     * @return the contract, never {@code null}
     * @throws IllegalArgumentException if no contract is declared for that table, which means either a
     *     misspelling or a table whose contract has not been measured yet
     */
    public static TableContract contractFor(final String table) {
        Objects.requireNonNull(table, "table must not be null");
        return new TableContract(table, columnsOf(table), primaryKeyNameOf(table), primaryKeyColumnsOf(table),
                foreignKeysOf(table), indexesOf(table), List.of(), checkConstraintNamesOf(table));
    }

    /**
     * Asserts that the live schema matches the declared contract for one table, in every facet.
     *
     * <p>Each facet is compared by <strong>exact equality on an ordered list</strong> rather than by
     * containment, so a missing column, an extra column, a reordered composite key, a retargeted foreign key,
     * an accidental unique index and a lost check constraint all fail. Nothing here mutates the schema or the
     * data; every statement is a catalogue read.
     *
     * @param jdbcTemplate the template bound to the harness's container; must not be {@code null}
     * @param table the table to verify; must be one of {@link #businessTables()}
     * @throws IllegalArgumentException if no contract is declared for that table
     */
    public static void assertTableMatches(final JdbcTemplate jdbcTemplate, final String table) {
        Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        final TableContract expected = contractFor(table);

        assertThat(tableExists(jdbcTemplate, table))
                .as("table %s must exist, or every entity mapped to it is mapped to nothing", table)
                .isTrue();

        assertThat(liveColumns(jdbcTemplate, table))
                .as("every column of %s, in ordinal order, with its exact width, precision and scale. "
                        + "Compared as an ordered list so a missing column, an extra column, a reordering "
                        + "and a type-compatible widening all fail - Hibernate's ddl-auto validate catches "
                        + "none of them", table)
                .containsExactlyElementsOf(expected.columns());

        assertThat(nullableColumnNames(jdbcTemplate, table))
                .as("no column of %s may be nullable: the schema declares NOT NULL on all eighty-seven "
                        + "business columns, because a COBOL record has no absent field - a fixed-width "
                        + "field is spaces or zeros, never nothing", table)
                .isEmpty();

        assertThat(primaryKeyName(jdbcTemplate, table))
                .as("the primary-key constraint of %s must keep its declared name, so a violation names the "
                        + "rule rather than an anonymous constraint", table)
                .isEqualTo(expected.primaryKeyName());

        assertThat(primaryKeyColumns(jdbcTemplate, table))
                .as("the primary key of %s, in KEY ORDER. Key order is the copybook's field order and it is "
                        + "load bearing: a browse over a composite key returns rows in that order, so a "
                        + "reversed pair changes what a control break sees while constraining identically",
                        table)
                .containsExactlyElementsOf(expected.primaryKeyColumns());

        assertThat(foreignKeys(jdbcTemplate, table))
                .as("every foreign key of %s with its name, its ordered columns and its ordered target. The "
                        + "ten keys of the schema are named fk01 through fk10, and a retargeted or reordered "
                        + "one still resolves at runtime - only this assertion catches it", table)
                .containsExactlyElementsOf(expected.foreignKeys());

        assertThat(indexes(jdbcTemplate, table))
                .as("every index on %s with its uniqueness, whether it backs the primary key, and its "
                        + "column order. A legacy alternate key is NON-unique by definition, so an "
                        + "accidental unique index would refuse rows the source accepts", table)
                .containsExactlyElementsOf(expected.indexes());

        assertThat(uniqueConstraintNames(jdbcTemplate, table))
                .as("%s declares no UNIQUE table constraint beyond its primary key; the schema has none at "
                        + "all, and one appearing would refuse a duplicate the alternate-key model permits",
                        table)
                .containsExactlyElementsOf(expected.uniqueConstraintNames());

        assertThat(checkConstraintNames(jdbcTemplate, table))
                .as("every CHECK constraint on %s, by name. The schema declares exactly five across all "
                        + "eleven tables, each carrying an 88-level value set from the copybooks", table)
                .containsExactlyElementsOf(expected.checkConstraintNames());
    }

    // =====================================================================================================
    // Live catalogue reads. Each one returns a value shaped for exact comparison against the contract.
    // =====================================================================================================

    /**
     * Whether the table exists in the current schema.
     *
     * @param jdbcTemplate the template
     * @param table the table name
     * @return {@code true} when a base table of that name exists
     */
    private static boolean tableExists(final JdbcTemplate jdbcTemplate, final String table) {
        final Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema() "
                        + "AND table_type = 'BASE TABLE' AND table_name = ?",
                Long.class, table);
        return count != null && count.longValue() == 1L;
    }

    /**
     * Every column of the table, in ordinal order, as contracts.
     *
     * @param jdbcTemplate the template
     * @param table the table name
     * @return the live column geometry
     */
    private static List<ColumnContract> liveColumns(final JdbcTemplate jdbcTemplate, final String table) {
        return jdbcTemplate.query(
                "SELECT ordinal_position, column_name, data_type, character_maximum_length, "
                        + "numeric_precision, numeric_scale FROM information_schema.columns "
                        + "WHERE table_schema = current_schema() AND table_name = ? "
                        + "ORDER BY ordinal_position",
                (rs, rowNum) -> new ColumnContract(rs.getInt(1), rs.getString(2), rs.getString(3),
                        boxedInt(rs.getObject(4)), boxedInt(rs.getObject(5)), boxedInt(rs.getObject(6))),
                table);
    }

    /**
     * The names of any nullable columns, which the contract requires to be none.
     *
     * @param jdbcTemplate the template
     * @param table the table name
     * @return the nullable column names, in ordinal order
     */
    private static List<String> nullableColumnNames(final JdbcTemplate jdbcTemplate, final String table) {
        return jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = current_schema() AND table_name = ? AND is_nullable = 'YES' "
                        + "ORDER BY ordinal_position",
                String.class, table);
    }

    /**
     * The primary-key constraint name.
     *
     * @param jdbcTemplate the template
     * @param table the table name
     * @return the constraint name, or {@code null} when the table has no primary key
     */
    private static String primaryKeyName(final JdbcTemplate jdbcTemplate, final String table) {
        final List<String> names = jdbcTemplate.queryForList(
                "SELECT constraint_name FROM information_schema.table_constraints "
                        + "WHERE table_schema = current_schema() AND table_name = ? "
                        + "AND constraint_type = 'PRIMARY KEY'",
                String.class, table);
        return names.isEmpty() ? null : names.get(0);
    }

    /**
     * The primary-key columns, in key order rather than in table order.
     *
     * @param jdbcTemplate the template
     * @param table the table name
     * @return the key columns in key order
     */
    private static List<String> primaryKeyColumns(final JdbcTemplate jdbcTemplate, final String table) {
        return jdbcTemplate.queryForList(
                "SELECT a.attname FROM pg_index ix "
                        + "JOIN pg_class t ON t.oid = ix.indrelid "
                        + "JOIN unnest(ix.indkey) WITH ORDINALITY AS k(attnum, ord) ON TRUE "
                        + "JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = k.attnum "
                        + "WHERE t.relnamespace = current_schema()::regnamespace AND t.relname = ? "
                        + "AND ix.indisprimary ORDER BY k.ord",
                String.class, table);
    }

    /**
     * Every foreign key of the table, ordered by constraint name, with ordered columns and ordered target.
     *
     * @param jdbcTemplate the template
     * @param table the table name
     * @return the live foreign keys
     */
    private static List<ForeignKeyContract> foreignKeys(final JdbcTemplate jdbcTemplate, final String table) {
        return jdbcTemplate.query(
                "SELECT con.conname, "
                        + "(SELECT string_agg(a.attname, ',' ORDER BY x.ord) "
                        + "   FROM unnest(con.conkey) WITH ORDINALITY AS x(attnum, ord) "
                        + "   JOIN pg_attribute a ON a.attrelid = cl.oid AND a.attnum = x.attnum), "
                        + "fcl.relname, "
                        + "(SELECT string_agg(a.attname, ',' ORDER BY x.ord) "
                        + "   FROM unnest(con.confkey) WITH ORDINALITY AS x(attnum, ord) "
                        + "   JOIN pg_attribute a ON a.attrelid = fcl.oid AND a.attnum = x.attnum) "
                        + "FROM pg_constraint con "
                        + "JOIN pg_class cl ON cl.oid = con.conrelid "
                        + "JOIN pg_class fcl ON fcl.oid = con.confrelid "
                        + "WHERE con.contype = 'f' AND cl.relnamespace = current_schema()::regnamespace "
                        + "AND cl.relname = ? ORDER BY con.conname",
                (rs, rowNum) -> new ForeignKeyContract(rs.getString(1), splitColumns(rs.getString(2)),
                        rs.getString(3), splitColumns(rs.getString(4))),
                table);
    }

    /**
     * Every index on the table, ordered by index name, with uniqueness, primary-key backing and column order.
     *
     * @param jdbcTemplate the template
     * @param table the table name
     * @return the live indexes
     */
    private static List<IndexContract> indexes(final JdbcTemplate jdbcTemplate, final String table) {
        return jdbcTemplate.query(
                "SELECT i.relname, ix.indisunique, ix.indisprimary, "
                        + "(SELECT string_agg(a.attname, ',' ORDER BY x.ord) "
                        + "   FROM unnest(ix.indkey) WITH ORDINALITY AS x(attnum, ord) "
                        + "   JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = x.attnum) "
                        + "FROM pg_index ix "
                        + "JOIN pg_class t ON t.oid = ix.indrelid "
                        + "JOIN pg_class i ON i.oid = ix.indexrelid "
                        + "JOIN pg_am am ON am.oid = i.relam "
                        + "WHERE t.relnamespace = current_schema()::regnamespace AND t.relname = ? "
                        + "AND am.amname = 'btree' ORDER BY i.relname",
                (rs, rowNum) -> new IndexContract(rs.getString(1), rs.getBoolean(2), rs.getBoolean(3),
                        splitColumns(rs.getString(4))),
                table);
    }

    /**
     * Every {@code UNIQUE} table constraint, ordered by name.
     *
     * @param jdbcTemplate the template
     * @param table the table name
     * @return the constraint names
     */
    private static List<String> uniqueConstraintNames(final JdbcTemplate jdbcTemplate, final String table) {
        return jdbcTemplate.queryForList(
                "SELECT constraint_name FROM information_schema.table_constraints "
                        + "WHERE table_schema = current_schema() AND table_name = ? "
                        + "AND constraint_type = 'UNIQUE' ORDER BY constraint_name",
                String.class, table);
    }

    /**
     * Every {@code CHECK} table constraint, ordered by name.
     *
     * <p>PostgreSQL records a {@code NOT NULL} as a check constraint with a generated name, so the query
     * filters to the explicitly named ones the migration declares. Nullability is asserted separately and
     * more strongly by {@link #nullableColumnNames(JdbcTemplate, String)}.
     *
     * @param jdbcTemplate the template
     * @param table the table name
     * @return the constraint names
     */
    private static List<String> checkConstraintNames(final JdbcTemplate jdbcTemplate, final String table) {
        return jdbcTemplate.queryForList(
                "SELECT con.conname FROM pg_constraint con "
                        + "JOIN pg_class cl ON cl.oid = con.conrelid "
                        + "WHERE con.contype = 'c' AND cl.relnamespace = current_schema()::regnamespace "
                        + "AND cl.relname = ? AND con.conname NOT LIKE '%\\_not\\_null' "
                        + "ORDER BY con.conname",
                String.class, table);
    }

    // =====================================================================================================
    // Small pure helpers.
    // =====================================================================================================

    /**
     * Boxes a catalogue value that is legitimately absent for the other half of the type families.
     *
     * @param value the raw column value
     * @return the value as an {@link Integer}, or {@code null} when the catalogue reported none
     */
    private static Integer boxedInt(final Object value) {
        return value == null ? null : Integer.valueOf(((Number) value).intValue());
    }

    /**
     * Splits an aggregated column list back into an ordered list.
     *
     * @param aggregated the comma-separated names, in constraint or index order
     * @return the names in that order, or an empty list when the aggregate was absent
     */
    private static List<String> splitColumns(final String aggregated) {
        return aggregated == null || aggregated.isBlank()
                ? List.of()
                : List.of(aggregated.split(","));
    }

    /**
     * Builds one column contract.
     *
     * @param ordinal the one-based position
     * @param name the column name
     * @param dataType the {@code information_schema} type name
     * @param characterMaximumLength the character width, or {@code null}
     * @param numericPrecision the numeric precision, or {@code null}
     * @param numericScale the numeric scale, or {@code null}
     * @return the contract
     */
    private static ColumnContract column(final int ordinal, final String name, final String dataType,
            final Integer characterMaximumLength, final Integer numericPrecision,
            final Integer numericScale) {

        return new ColumnContract(ordinal, name, dataType, characterMaximumLength, numericPrecision,
                numericScale);
    }

    /**
     * Builds one foreign-key contract from comma-separated column lists, keeping the declared order.
     *
     * @param name the constraint name
     * @param columns the constrained columns, comma separated in constraint order
     * @param referencedTable the parent table
     * @param referencedColumns the parent columns, comma separated in constraint order
     * @return the contract
     */
    private static ForeignKeyContract foreignKey(final String name, final String columns,
            final String referencedTable, final String referencedColumns) {

        return new ForeignKeyContract(name, splitColumns(columns), referencedTable,
                splitColumns(referencedColumns));
    }

    /**
     * Builds the contract for a table's primary-key index, which PostgreSQL creates implicitly.
     *
     * @param name the index name, which equals the constraint name
     * @param columns the key columns, comma separated in key order
     * @return the contract
     */
    private static IndexContract primaryKeyIndex(final String name, final String columns) {
        return new IndexContract(name, true, true, splitColumns(columns));
    }

    /**
     * Builds the contract for an explicitly declared non-unique alternate-key index.
     *
     * @param name the index name
     * @param columns the indexed columns, comma separated in index order
     * @return the contract
     */
    private static IndexContract alternateKeyIndex(final String name, final String columns) {
        return new IndexContract(name, false, false, splitColumns(columns));
    }

    /**
     * Fails with a message that says what to do about it.
     *
     * @param table the unknown table name
     * @return never returns
     */
    private static IllegalArgumentException unknownTable(final String table) {
        return new IllegalArgumentException(String.format(Locale.ROOT,
                "no metadata contract is declared for table '%s'. Either the name is misspelled, or a "
                        + "twelfth business table now exists - in which case measure its catalogue metadata "
                        + "and declare it here rather than exempting it. The eleven declared are %s",
                table, BUSINESS_TABLES));
    }

    // =====================================================================================================
    // The declared contract. Measured from the live schema the migrations produce, then checked against the
    // picture clause that fixes each width, precision and scale.
    // =====================================================================================================

    /**
     * Every column of one table, in ordinal order.
     *
     * @param table the table name
     * @return the declared columns
     * @throws IllegalArgumentException if the table is unknown
     */
    private static List<ColumnContract> columnsOf(final String table) {
        return switch (table) {
            case "account" -> List.of(
                    column(1, "acct_id", "numeric", null, 11, 0),
                    column(2, "acct_active_status", "character", 1, null, null),
                    column(3, "acct_curr_bal", "numeric", null, 12, 2),
                    column(4, "acct_credit_limit", "numeric", null, 12, 2),
                    column(5, "acct_cash_credit_limit", "numeric", null, 12, 2),
                    column(6, "acct_open_date", "character", 10, null, null),
                    column(7, "acct_expiraion_date", "character", 10, null, null),
                    column(8, "acct_reissue_date", "character", 10, null, null),
                    column(9, "acct_curr_cyc_credit", "numeric", null, 12, 2),
                    column(10, "acct_curr_cyc_debit", "numeric", null, 12, 2),
                    column(11, "acct_addr_zip", "character", 10, null, null),
                    column(12, "acct_group_id", "character", 10, null, null),
                    column(13, "version", "bigint", null, 64, 0));
            case "card" -> List.of(
                    column(1, "card_num", "character", 16, null, null),
                    column(2, "card_acct_id", "numeric", null, 11, 0),
                    // CARD-CVV-CD PIC 9(03) of app/cpy/CVACT02Y.cpy sits at ordinal 3 of the 150-byte card
                    // record, between the account identifier and the embossed name. It is modelled as
                    // CHAR(3) rather than a numeric because the field is a fixed-width three-digit code
                    // whose leading zeros are significant, and it is write-only in the entity: the class
                    // exposes matchesVerificationValue rather than a getter, so no read path can return it.
                    column(3, "card_cvv_cd", "character", 3, null, null),
                    column(4, "card_embossed_name", "character", 50, null, null),
                    column(5, "card_expiraion_date", "character", 10, null, null),
                    column(6, "card_active_status", "character", 1, null, null),
                    column(7, "version", "bigint", null, 64, 0));
            case "card_cross_reference" -> List.of(
                    column(1, "xref_card_num", "character", 16, null, null),
                    column(2, "xref_cust_id", "numeric", null, 9, 0),
                    column(3, "xref_acct_id", "numeric", null, 11, 0));
            case "customer" -> List.of(
                    column(1, "cust_id", "numeric", null, 9, 0),
                    column(2, "cust_first_name", "character", 25, null, null),
                    column(3, "cust_middle_name", "character", 25, null, null),
                    column(4, "cust_last_name", "character", 25, null, null),
                    column(5, "cust_addr_line_1", "character", 50, null, null),
                    column(6, "cust_addr_line_2", "character", 50, null, null),
                    column(7, "cust_addr_line_3", "character", 50, null, null),
                    column(8, "cust_addr_state_cd", "character", 2, null, null),
                    column(9, "cust_addr_country_cd", "character", 3, null, null),
                    column(10, "cust_addr_zip", "character", 10, null, null),
                    column(11, "cust_phone_num_1", "character", 15, null, null),
                    column(12, "cust_phone_num_2", "character", 15, null, null),
                    column(13, "cust_ssn", "character", 9, null, null),
                    column(14, "cust_govt_issued_id", "character", 20, null, null),
                    column(15, "cust_dob_yyyy_mm_dd", "character", 10, null, null),
                    column(16, "cust_eft_account_id", "character", 10, null, null),
                    column(17, "cust_pri_card_holder_ind", "character", 1, null, null),
                    column(18, "cust_fico_credit_score", "character", 3, null, null),
                    column(19, "version", "bigint", null, 64, 0));
            case "daily_transaction" -> List.of(
                    column(1, "ingest_seq", "numeric", null, 9, 0),
                    column(2, "dalytran_id", "character", 16, null, null),
                    column(3, "dalytran_type_cd", "character", 2, null, null),
                    column(4, "dalytran_cat_cd", "numeric", null, 4, 0),
                    column(5, "dalytran_source", "character", 10, null, null),
                    column(6, "dalytran_desc", "character", 100, null, null),
                    column(7, "dalytran_amt", "numeric", null, 11, 2),
                    column(8, "dalytran_merchant_id", "numeric", null, 9, 0),
                    column(9, "dalytran_merchant_name", "character", 50, null, null),
                    column(10, "dalytran_merchant_city", "character", 50, null, null),
                    column(11, "dalytran_merchant_zip", "character", 10, null, null),
                    column(12, "dalytran_card_num", "character", 16, null, null),
                    column(13, "dalytran_orig_ts", "character", 26, null, null),
                    column(14, "dalytran_proc_ts", "character", 26, null, null));
            case "disclosure_group" -> List.of(
                    column(1, "acct_group_id", "character", 10, null, null),
                    column(2, "tran_type_cd", "character", 2, null, null),
                    column(3, "tran_cat_cd", "integer", null, 32, 0),
                    column(4, "dis_int_rate", "numeric", null, 6, 2));
            case "transaction" -> List.of(
                    column(1, "tran_id", "character", 16, null, null),
                    column(2, "tran_type_cd", "character", 2, null, null),
                    column(3, "tran_cat_cd", "numeric", null, 4, 0),
                    column(4, "tran_source", "character", 10, null, null),
                    column(5, "tran_desc", "character", 100, null, null),
                    column(6, "tran_amt", "numeric", null, 11, 2),
                    column(7, "tran_merchant_id", "numeric", null, 9, 0),
                    column(8, "tran_merchant_name", "character", 50, null, null),
                    column(9, "tran_merchant_city", "character", 50, null, null),
                    column(10, "tran_merchant_zip", "character", 10, null, null),
                    column(11, "tran_card_num", "character", 16, null, null),
                    column(12, "tran_orig_ts", "character", 26, null, null),
                    column(13, "tran_proc_ts", "character", 26, null, null),
                    column(14, "version", "bigint", null, 64, 0));
            case "transaction_category" -> List.of(
                    column(1, "tran_type_cd", "character varying", 2, null, null),
                    column(2, "tran_cat_cd", "numeric", null, 4, 0),
                    column(3, "tran_cat_type_desc", "character", 50, null, null));
            case "transaction_category_balance" -> List.of(
                    column(1, "acct_id", "bigint", null, 64, 0),
                    column(2, "tran_type_cd", "character varying", 2, null, null),
                    column(3, "tran_cat_cd", "integer", null, 32, 0),
                    column(4, "tran_cat_bal", "numeric", null, 11, 2));
            case "transaction_type" -> List.of(
                    column(1, "tran_type", "character", 2, null, null),
                    column(2, "tran_type_desc", "character", 50, null, null));
            case "user_security" -> List.of(
                    column(1, "sec_usr_id", "character", 8, null, null),
                    column(2, "sec_usr_fname", "character", 20, null, null),
                    column(3, "sec_usr_lname", "character", 20, null, null),
                    column(4, "sec_usr_pwd", "character varying", 60, null, null),
                    column(5, "sec_usr_type", "character", 1, null, null));
            default -> throw unknownTable(table);
        };
    }

    /**
     * The primary-key constraint name of one table.
     *
     * @param table the table name
     * @return the declared name
     * @throws IllegalArgumentException if the table is unknown
     */
    private static String primaryKeyNameOf(final String table) {
        return switch (table) {
            case "account", "card", "card_cross_reference", "customer", "daily_transaction",
                    "disclosure_group", "transaction", "transaction_category",
                    "transaction_category_balance", "transaction_type", "user_security" -> "pk_" + table;
            default -> throw unknownTable(table);
        };
    }

    /**
     * The primary-key columns of one table, in key order.
     *
     * <p>Three tables carry a composite key, and each one's order is the copybook's field order:
     * {@code CVTRA02Y} for the disclosure group, {@code CVTRA04Y} for the transaction category and
     * {@code CVTRA01Y} for the category balance, whose 17-character key of account plus type plus category is
     * exactly what makes an account-level control break work in {@code app/cbl/CBACT04C.cbl}.
     *
     * @param table the table name
     * @return the declared key columns in key order
     * @throws IllegalArgumentException if the table is unknown
     */
    private static List<String> primaryKeyColumnsOf(final String table) {
        return switch (table) {
            case "account" -> List.of("acct_id");
            case "card" -> List.of("card_num");
            case "card_cross_reference" -> List.of("xref_card_num");
            case "customer" -> List.of("cust_id");
            case "daily_transaction" -> List.of("ingest_seq");
            case "disclosure_group" -> List.of("acct_group_id", "tran_type_cd", "tran_cat_cd");
            case "transaction" -> List.of("tran_id");
            case "transaction_category" -> List.of("tran_type_cd", "tran_cat_cd");
            case "transaction_category_balance" -> List.of("acct_id", "tran_type_cd", "tran_cat_cd");
            case "transaction_type" -> List.of("tran_type");
            case "user_security" -> List.of("sec_usr_id");
            default -> throw unknownTable(table);
        };
    }

    /**
     * Every foreign key of one table, ordered by constraint name.
     *
     * <p>The ten keys are numbered {@code fk01} through {@code fk10} by the migration, and five tables carry
     * none: the account and the customer are parents only, the daily-transaction staging table is
     * deliberately unconstrained because a rejected record must still be storable, the transaction type is a
     * root reference table, and the user-security table stands alone.
     *
     * @param table the table name
     * @return the declared foreign keys
     * @throws IllegalArgumentException if the table is unknown
     */
    private static List<ForeignKeyContract> foreignKeysOf(final String table) {
        return switch (table) {
            case "account", "customer", "daily_transaction", "transaction_type", "user_security" -> List.of();
            case "card" -> List.of(
                    foreignKey("fk01_card_account", "card_acct_id", "account", "acct_id"));
            case "card_cross_reference" -> List.of(
                    foreignKey("fk02_xref_customer", "xref_cust_id", "customer", "cust_id"),
                    foreignKey("fk03_xref_account", "xref_acct_id", "account", "acct_id"));
            case "disclosure_group" -> List.of(
                    foreignKey("fk10_discgrp_category", "tran_type_cd,tran_cat_cd", "transaction_category",
                            "tran_type_cd,tran_cat_cd"));
            case "transaction" -> List.of(
                    foreignKey("fk04_transaction_card", "tran_card_num", "card", "card_num"),
                    foreignKey("fk05_transaction_type", "tran_type_cd", "transaction_type", "tran_type"),
                    foreignKey("fk06_transaction_category", "tran_type_cd,tran_cat_cd",
                            "transaction_category", "tran_type_cd,tran_cat_cd"));
            case "transaction_category" -> List.of(
                    foreignKey("fk09_category_type", "tran_type_cd", "transaction_type", "tran_type"));
            case "transaction_category_balance" -> List.of(
                    foreignKey("fk07_tcatbal_account", "acct_id", "account", "acct_id"),
                    foreignKey("fk08_tcatbal_category", "tran_type_cd,tran_cat_cd", "transaction_category",
                            "tran_type_cd,tran_cat_cd"));
            default -> throw unknownTable(table);
        };
    }

    /**
     * Every index on one table, ordered by index name.
     *
     * <p>Each table's primary-key index is listed because PostgreSQL creates it implicitly and a census that
     * omitted it would not be a census. Exactly three tables carry an explicit index beyond that, and those
     * three are the three VSAM alternate indexes of {@code app/catlg/LISTCAT.txt} - non-unique, because a
     * legacy alternate key is.
     *
     * @param table the table name
     * @return the declared indexes
     * @throws IllegalArgumentException if the table is unknown
     */
    private static List<IndexContract> indexesOf(final String table) {
        return switch (table) {
            case "account" -> List.of(primaryKeyIndex("pk_account", "acct_id"));
            case "card" -> List.of(
                    alternateKeyIndex("idx_card_acct_id", "card_acct_id"),
                    primaryKeyIndex("pk_card", "card_num"));
            case "card_cross_reference" -> List.of(
                    alternateKeyIndex("idx_card_cross_reference_acct_id", "xref_acct_id"),
                    primaryKeyIndex("pk_card_cross_reference", "xref_card_num"));
            case "customer" -> List.of(primaryKeyIndex("pk_customer", "cust_id"));
            case "daily_transaction" -> List.of(primaryKeyIndex("pk_daily_transaction", "ingest_seq"));
            case "disclosure_group" -> List.of(
                    primaryKeyIndex("pk_disclosure_group", "acct_group_id,tran_type_cd,tran_cat_cd"));
            case "transaction" -> List.of(
                    alternateKeyIndex("idx_transaction_proc_ts", "tran_proc_ts"),
                    primaryKeyIndex("pk_transaction", "tran_id"));
            case "transaction_category" -> List.of(
                    primaryKeyIndex("pk_transaction_category", "tran_type_cd,tran_cat_cd"));
            case "transaction_category_balance" -> List.of(
                    primaryKeyIndex("pk_transaction_category_balance",
                            "acct_id,tran_type_cd,tran_cat_cd"));
            case "transaction_type" -> List.of(primaryKeyIndex("pk_transaction_type", "tran_type"));
            case "user_security" -> List.of(primaryKeyIndex("pk_user_security", "sec_usr_id"));
            default -> throw unknownTable(table);
        };
    }

    /**
     * Every explicitly named {@code CHECK} constraint on one table, ordered by name.
     *
     * <p>Five across the schema, each one an 88-level value set from a copybook: the two {@code 'Y'}/{@code 'N'}
     * status flags, the primary-card-holder indicator, the nine-digit social security number, and the
     * {@code 'A'}/{@code 'U'} user class of {@code app/cpy/COCOM01Y.cpy}.
     *
     * @param table the table name
     * @return the declared constraint names
     * @throws IllegalArgumentException if the table is unknown
     */
    private static List<String> checkConstraintNamesOf(final String table) {
        return switch (table) {
            case "account" -> List.of("ck_account_active_status");
            case "card" -> List.of("ck_card_active_status");
            case "customer" -> List.of("ck_customer_pri_card_holder_ind", "ck_customer_ssn_numeric");
            case "user_security" -> List.of("ck_user_security_type");
            case "card_cross_reference", "daily_transaction", "disclosure_group", "transaction",
                    "transaction_category", "transaction_category_balance", "transaction_type" -> List.of();
            default -> throw unknownTable(table);
        };
    }
}

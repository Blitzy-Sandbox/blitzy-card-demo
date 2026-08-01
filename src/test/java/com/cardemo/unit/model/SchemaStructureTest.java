/*
 * ******************************************************************
 * Program     : SchemaStructureTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Asserts the structure of the Flyway baseline migration
 *               against two witnesses independent of it - the frozen
 *               record-layout copybooks for field widths and the VSAM
 *               catalogue for key lengths - so the DDL cannot be
 *               validated merely by restating itself.
 * Source      : src/main/resources/db/migration/V1__create_schema.sql,
 *               app/catlg/LISTCAT.txt (cluster key lengths),
 *               app/cpy/CVACT01Y.cpy, CVACT02Y.cpy, CVACT03Y.cpy,
 *               CVCUS01Y.cpy, CVTRA01Y.cpy, CVTRA02Y.cpy,
 *               CVTRA03Y.cpy, CVTRA04Y.cpy, CVTRA05Y.cpy,
 *               CVTRA06Y.cpy, CSUSR01Y.cpy,
 *               app/jcl/DUSRSECJ.jcl:65-66 (the USRSEC key length)
 *               frozen at commit 7756d89
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

package com.cardemo.unit.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Proves the structure of {@code V1__create_schema.sql}, the Flyway baseline that replaces the ten
 * VSAM clusters and the twelve IDCAMS {@code DEFINE CLUSTER} jobs.
 *
 * <p><strong>Why two independent witnesses.</strong> A DDL test that reads a width out of the DDL
 * and asserts it equals itself proves nothing. Every width assertion below therefore compares the
 * migration against {@link RecordLayoutCopybook} - the shared oracle that parses the frozen
 * {@code app/cpy} members - and every composite key length is additionally checked against the key
 * length catalogued in {@code app/catlg/LISTCAT.txt}. The migration supplies only the column list
 * and its order; the widths and lengths come from the corpus.
 *
 * <p>That split matters concretely: {@code acct_id} is declared {@code BIGINT} and
 * {@code tran_cat_cd} is declared {@code INTEGER}, and <em>neither carries a width</em>. Their
 * eleven and four digits exist only in {@code CVTRA01Y}, so a DDL-only test could not check the
 * seventeen-byte {@code TCATBALF} key at all.
 *
 * <p><strong>What this class deliberately does not assert.</strong> At the commit under test the
 * migration directory holds {@code V1__create_schema.sql} and nothing else -
 * {@code V2__create_indexes.sql} and {@code V3__seed_data.sql} have not been authored yet. This
 * class therefore makes <em>no</em> claim about either file, neither that they exist nor that they
 * do not, because both forms of claim would turn a later, legitimate boundary into a spurious
 * failure here. It does assert that {@code V1} itself creates no index, which is true now and
 * remains true once index creation lands in its own migration where the AAP places it.
 *
 * <p><strong>The one sanctioned width divergence.</strong> {@code sec_usr_pwd} is
 * {@code VARCHAR(60)} while {@code app/cpy/CSUSR01Y.cpy:21} declares {@code SEC-USR-PWD PIC X(08)}.
 * That is mandated, not accidental: AAP 0.5.1.3 requires the eight-character password field to
 * become a sixty-character BCrypt hash column. It is the only exemption in
 * {@link #WIDTH_EXEMPT} and it is asserted explicitly rather than merely skipped.
 */
@DisplayName("V1__create_schema.sql - structure checked against the copybooks and the catalogue")
final class SchemaStructureTest {

    /** The Flyway baseline under test. */
    private static final Path MIGRATION =
            Path.of("src", "main", "resources", "db", "migration", "V1__create_schema.sql");

    /**
     * A SQL identifier, optionally double quoted. The character class admits digits after
     * the first character because five customer columns end in one - {@code cust_addr_line_1}
     * through {@code cust_addr_line_3} and {@code cust_phone_num_1} and
     * {@code cust_phone_num_2}. A narrower {@code [a-z_]+} class cannot span those names and
     * dropped all five silently, which is what {@code theCensusIsNotVacuous} detected by
     * reporting 82 columns where the migration declares 88.
     */
    private static final String IDENTIFIER = "\"?[a-z_][a-z0-9_]*\"?";

    /** Opens a table body. The transaction table is quoted because it is a reserved word. */
    private static final Pattern TABLE_START =
            Pattern.compile("^CREATE TABLE\\s+(" + IDENTIFIER + ")\\s*\\($");

    /** Closes a table body. */
    private static final Pattern TABLE_END = Pattern.compile("^\\)\\s*;\\s*$");

    /**
     * A column declaration: name then a SQL type, optionally parameterised with a precision
     * and an optional scale, then any trailing modifiers such as {@code NOT NULL}.
     *
     * <p>The precision group is deliberately <em>not</em> followed by a {@code \b} word
     * boundary. A closing parenthesis and the space that follows it are both non-word
     * characters, so a boundary can never hold at that position; the engine would backtrack,
     * discard the whole optional group and surrender the precision and scale captures for
     * every sized type while still matching the line. The parse then looked healthy - every
     * column was found and every {@code NOT NULL} was still visible in the trailing group -
     * yet no width was known anywhere. The precision floors in
     * {@code TheDecimalDiscipline} and {@code TheColumnWidthsAgainstTheCopybooks} are what
     * exposed it.
     */
    private static final Pattern COLUMN = Pattern.compile(
            "^(" + IDENTIFIER + ")\\s+([A-Z]+)"
                    + "(?:\\s*\\((\\d+)(?:\\s*,\\s*(\\d+))?\\))?(\\s.*)?$");

    /** A named constraint of any kind. */
    private static final Pattern CONSTRAINT =
            Pattern.compile("^CONSTRAINT\\s+(\\S+)\\s*(.*)$");

    /** A primary key clause, wherever it appears on or after its CONSTRAINT line. */
    private static final Pattern PRIMARY_KEY =
            Pattern.compile("PRIMARY KEY\\s*\\(([^)]*)\\)");

    /** Keywords that open a constraint rather than a column. */
    private static final List<String> CONSTRAINT_KEYWORDS =
            List.of("CONSTRAINT", "PRIMARY KEY", "FOREIGN KEY", "CHECK", "UNIQUE", "REFERENCES");

    /** Table to the frozen record-layout copybook that governs its column widths. */
    private static final Map<String, String> COPYBOOK_OF = Map.ofEntries(
            Map.entry("account", "CVACT01Y"),
            Map.entry("card", "CVACT02Y"),
            Map.entry("card_cross_reference", "CVACT03Y"),
            Map.entry("customer", "CVCUS01Y"),
            Map.entry("transaction_category_balance", "CVTRA01Y"),
            Map.entry("disclosure_group", "CVTRA02Y"),
            Map.entry("transaction_type", "CVTRA03Y"),
            Map.entry("transaction_category", "CVTRA04Y"),
            Map.entry("transaction", "CVTRA05Y"),
            Map.entry("daily_transaction", "CVTRA06Y"),
            Map.entry("user_security", "CSUSR01Y"));

    /**
     * Catalogued VSAM key length per table, from {@code app/catlg/LISTCAT.txt} except
     * {@code user_security}, whose cluster is defined in {@code app/jcl/DUSRSECJ.jcl:65-66} as
     * {@code KEYS(8,0)} rather than catalogued. {@code daily_transaction} is deliberately absent:
     * {@code DALYTRAN} is a physical sequential dataset, not a keyed cluster, so it has no key length
     * to check and inventing one would be fabrication.
     */
    private static final Map<String, Integer> CATALOGUED_KEY_LENGTH = Map.ofEntries(
            Map.entry("account", Integer.valueOf(11)),
            Map.entry("card", Integer.valueOf(16)),
            Map.entry("card_cross_reference", Integer.valueOf(16)),
            Map.entry("customer", Integer.valueOf(9)),
            Map.entry("transaction_category_balance", Integer.valueOf(17)),
            Map.entry("disclosure_group", Integer.valueOf(16)),
            Map.entry("transaction_type", Integer.valueOf(2)),
            Map.entry("transaction_category", Integer.valueOf(6)),
            Map.entry("transaction", Integer.valueOf(16)),
            Map.entry("user_security", Integer.valueOf(8)));

    /**
     * Primary-key column to its COBOL field, for the columns whose names are not the mechanical
     * underscore-to-hyphen uppercasing of the copybook field. Composite-key components are the whole
     * of this set, because the DDL names them for the relation while the copybook names them for the
     * record - {@code acct_id} in {@code transaction_category_balance} is {@code TRANCAT-ACCT-ID}.
     */
    private static final Map<String, String> KEY_FIELD_OF = Map.ofEntries(
            Map.entry("transaction_category_balance.acct_id", "TRANCAT-ACCT-ID"),
            Map.entry("transaction_category_balance.tran_type_cd", "TRANCAT-TYPE-CD"),
            Map.entry("transaction_category_balance.tran_cat_cd", "TRANCAT-CD"),
            Map.entry("disclosure_group.acct_group_id", "DIS-ACCT-GROUP-ID"),
            Map.entry("disclosure_group.tran_type_cd", "DIS-TRAN-TYPE-CD"),
            Map.entry("disclosure_group.tran_cat_cd", "DIS-TRAN-CAT-CD"),
            Map.entry("transaction_category.tran_type_cd", "TRAN-TYPE-CD"),
            Map.entry("transaction_category.tran_cat_cd", "TRAN-CAT-CD"),
            Map.entry("transaction_type.tran_type", "TRAN-TYPE"),
            Map.entry("card_cross_reference.xref_card_num", "XREF-CARD-NUM"));

    /**
     * The single column whose declared width may differ from its copybook picture, with the AAP
     * clause that mandates the difference.
     */
    private static final Map<String, String> WIDTH_EXEMPT = Map.of(
            "user_security.sec_usr_pwd",
            "AAP 0.5.1.3 - the PIC X(08) password becomes a 60-character BCrypt hash column");

    /** {@code AAP 0.4.1.3} and {@code 0.5.1.9} fix the CHECK constraint count at five. */
    private static final int EXPECTED_CHECK_COUNT = 5;

    /** {@code AAP 0.4.1.3} fixes the foreign key count at ten. */
    private static final int EXPECTED_FOREIGN_KEY_COUNT = 10;

    /** The four entities the AAP gives an optimistic-lock column. */
    private static final List<String> VERSIONED_TABLES =
            List.of("account", "card", "customer", "transaction");

    /** A parsed column declaration. A null width means the type carries none, as BIGINT does. */
    private record Column(String name, String type, Integer width, Integer scale, boolean notNull) { }

    /** A parsed table body. */
    private record Table(String name, List<Column> columns, List<String> primaryKey,
            List<String> checkConstraints, List<String> foreignKeyConstraints) { }

    /** Every table in declaration order, parsed once per test class use. */
    private static Map<String, Table> parseMigration() {
        final List<String> lines;
        try {
            lines = Files.readAllLines(MIGRATION, StandardCharsets.UTF_8);
        } catch (final IOException failure) {
            throw new UncheckedIOException("cannot read " + MIGRATION.toAbsolutePath(), failure);
        }
        final Map<String, Table> tables = new LinkedHashMap<>();
        String current = null;
        List<Column> columns = new ArrayList<>();
        List<String> primaryKey = new ArrayList<>();
        List<String> checks = new ArrayList<>();
        List<String> foreignKeys = new ArrayList<>();
        String pendingConstraint = null;
        for (final String raw : lines) {
            final String line = raw.trim();
            if (line.isEmpty() || line.startsWith("--")) {
                continue;
            }
            final Matcher start = TABLE_START.matcher(line);
            if (start.matches()) {
                current = start.group(1).replace("\"", "");
                columns = new ArrayList<>();
                primaryKey = new ArrayList<>();
                checks = new ArrayList<>();
                foreignKeys = new ArrayList<>();
                pendingConstraint = null;
                continue;
            }
            if (current == null) {
                continue;
            }
            if (TABLE_END.matcher(line).matches()) {
                tables.put(current, new Table(current, List.copyOf(columns), List.copyOf(primaryKey),
                        List.copyOf(checks), List.copyOf(foreignKeys)));
                current = null;
                continue;
            }
            final Matcher constraint = CONSTRAINT.matcher(line);
            if (constraint.matches()) {
                pendingConstraint = constraint.group(1);
                classify(pendingConstraint, constraint.group(2), checks, foreignKeys, primaryKey);
                continue;
            }
            if (pendingConstraint != null && startsWithConstraintKeyword(line)) {
                classify(pendingConstraint, line, checks, foreignKeys, primaryKey);
                continue;
            }
            if (startsWithConstraintKeyword(line)) {
                continue;
            }
            final Matcher column = COLUMN.matcher(line);
            if (column.matches()) {
                pendingConstraint = null;
                columns.add(new Column(
                        column.group(1).replace("\"", ""),
                        column.group(2),
                        column.group(3) == null ? null : Integer.valueOf(column.group(3)),
                        column.group(4) == null ? null : Integer.valueOf(column.group(4)),
                        // The trailing group is optional, so a column carrying no modifier at
                        // all yields null here. Absent modifiers means absent NOT NULL, which
                        // everyColumnIsNotNull then reports rather than this parse hiding it.
                        column.group(5) != null
                                && column.group(5).toUpperCase(java.util.Locale.ROOT)
                                        .contains("NOT NULL")));
            }
        }
        if (tables.isEmpty()) {
            throw new IllegalStateException(MIGRATION
                    + " yielded no tables; the migration or this test's TABLE_START pattern has changed."
                    + " Silently returning an empty map would make every assertion below vacuous.");
        }
        return tables;
    }

    private static void classify(final String name, final String tail, final List<String> checks,
            final List<String> foreignKeys, final List<String> primaryKey) {
        final String upper = tail.toUpperCase(java.util.Locale.ROOT);
        if (upper.contains("CHECK")) {
            if (!checks.contains(name)) {
                checks.add(name);
            }
        }
        if (upper.contains("FOREIGN KEY")) {
            if (!foreignKeys.contains(name)) {
                foreignKeys.add(name);
            }
        }
        final Matcher key = PRIMARY_KEY.matcher(tail);
        if (key.find() && primaryKey.isEmpty()) {
            for (final String column : key.group(1).split(",")) {
                primaryKey.add(column.trim().replace("\"", ""));
            }
        }
    }

    /**
     * Reports whether a table-body line opens a constraint rather than a column.
     *
     * <p>The keyword must be followed by something that cannot continue a SQL identifier.
     * A bare {@code startsWith} would classify a hypothetical column named
     * {@code check_digit} or {@code unique_ref} as a constraint and drop it from the census
     * without a sound. No such column exists in the migration today - all 76 distinct column
     * names were checked - but the boundary makes the misreading impossible rather than
     * merely absent, so the column census cannot be quietly understated by a future rename.
     */
    private static boolean startsWithConstraintKeyword(final String line) {
        final String upper = line.toUpperCase(java.util.Locale.ROOT);
        for (final String keyword : CONSTRAINT_KEYWORDS) {
            if (!upper.startsWith(keyword)) {
                continue;
            }
            if (upper.length() == keyword.length()) {
                return true;
            }
            final char next = upper.charAt(keyword.length());
            if (!Character.isLetterOrDigit(next) && next != '_') {
                return true;
            }
        }
        return false;
    }

    /** The COBOL field a column maps to: the override if one exists, else the mechanical form. */
    private static String cobolFieldOf(final String table, final String column) {
        final String override = KEY_FIELD_OF.get(table + "." + column);
        if (override != null) {
            return override;
        }
        return column.toUpperCase(java.util.Locale.ROOT).replace('_', '-');
    }

    private static Map<String, Table> tables() {
        return parseMigration();
    }

    // ---------------------------------------------------------------------------------------------
    // 1. The table inventory
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("1. the table inventory matches the ten clusters plus the sequential staging file")
    class TheTableInventory {

        @Test
        @DisplayName("eleven tables are created, one per record-layout copybook")
        void elevenTablesAreCreated() {
            assertThat(tables()).hasSize(11);
            assertThat(tables().keySet()).containsExactlyInAnyOrderElementsOf(COPYBOOK_OF.keySet());
        }

        @Test
        @DisplayName("every table maps to a copybook the shared oracle can actually read")
        void everyTableMapsToAReadableCopybook() {
            for (final Map.Entry<String, String> entry : COPYBOOK_OF.entrySet()) {
                final RecordLayoutCopybook copybook = RecordLayoutCopybook.of(entry.getValue());
                assertThat(copybook.fieldNames())
                        .describedAs("%s -> %s", entry.getKey(), entry.getValue())
                        .isNotEmpty();
            }
        }

        @Test
        @DisplayName("referenced tables are created before the tables that reference them")
        void referencedTablesArePositionedFirst() {
            final List<String> order = new ArrayList<>(tables().keySet());
            assertThat(order.indexOf("transaction_type")).isLessThan(order.indexOf("transaction_category"));
            assertThat(order.indexOf("account")).isLessThan(order.indexOf("card"));
            assertThat(order.indexOf("customer")).isLessThan(order.indexOf("card_cross_reference"));
            assertThat(order.indexOf("card")).isLessThan(order.indexOf("transaction"));
            assertThat(order.indexOf("transaction_category"))
                    .isLessThan(order.indexOf("transaction_category_balance"));
        }

        @Test
        @DisplayName("the reserved-word table is quoted so PostgreSQL accepts it")
        void theReservedWordTableIsQuoted() throws IOException {
            final String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);
            assertThat(sql).contains("CREATE TABLE \"transaction\" (");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 2. Primary keys, checked against the catalogue
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("2. every primary key matches the catalogued VSAM key")
    class ThePrimaryKeys {

        @Test
        @DisplayName("all eleven tables declare a primary key")
        void allElevenTablesDeclareAPrimaryKey() {
            assertThat(tables().values()).allSatisfy(table ->
                    assertThat(table.primaryKey())
                            .describedAs("%s primary key", table.name())
                            .isNotEmpty());
        }

        @Test
        @DisplayName("the three composite keys carry their components in COBOL declaration order")
        void theCompositeKeysKeepCobolOrder() {
            assertThat(tables().get("transaction_category_balance").primaryKey())
                    .containsExactly("acct_id", "tran_type_cd", "tran_cat_cd");
            assertThat(tables().get("disclosure_group").primaryKey())
                    .containsExactly("acct_group_id", "tran_type_cd", "tran_cat_cd");
            assertThat(tables().get("transaction_category").primaryKey())
                    .containsExactly("tran_type_cd", "tran_cat_cd");
        }

        @ParameterizedTest(name = "{0} has a single-column primary key")
        @ValueSource(strings = {"account", "card", "card_cross_reference", "customer",
            "transaction_type", "transaction", "daily_transaction", "user_security"})
        @DisplayName("the eight simple keys are single-column")
        void theSimpleKeysAreSingleColumn(final String table) {
            assertThat(tables().get(table).primaryKey()).hasSize(1);
        }

        @ParameterizedTest(name = "{0} key length sums to the catalogued {1}")
        @CsvSource({
            "account,11", "card,16", "card_cross_reference,16", "customer,9",
            "transaction_category_balance,17", "disclosure_group,16",
            "transaction_type,2", "transaction_category,6", "transaction,16", "user_security,8",
        })
        @DisplayName("each key's copybook widths sum to the key length the catalogue records")
        void keyWidthsSumToTheCataloguedLength(final String table, final int catalogued) {
            final RecordLayoutCopybook copybook = RecordLayoutCopybook.of(COPYBOOK_OF.get(table));
            int sum = 0;
            for (final String column : tables().get(table).primaryKey()) {
                sum += copybook.widthOf(cobolFieldOf(table, column));
            }
            assertThat(sum)
                    .describedAs("%s primary key width from %s", table, COPYBOOK_OF.get(table))
                    .isEqualTo(catalogued);
            assertThat(catalogued)
                    .describedAs("the catalogue witness for %s", table)
                    .isEqualTo(CATALOGUED_KEY_LENGTH.get(table).intValue());
        }

        @Test
        @DisplayName("the sequential staging table has no catalogued key, and none is invented for it")
        void theStagingTableHasNoCataloguedKey() {
            // DALYTRAN is a physical sequential dataset, not a KSDS, so app/catlg/LISTCAT.txt records
            // no key length for it. Its primary key is a Java-side decision, so asserting a
            // catalogued length would mean fabricating a corpus fact. Because the dataset carries no key
            // at all, DALYTRAN-ID supplies no uniqueness guarantee and is deliberately NOT the primary
            // key: the key is the loader-assigned ingestion ordinal, which is the only column in the
            // schema with no copybook field behind it.
            assertThat(CATALOGUED_KEY_LENGTH).doesNotContainKey("daily_transaction");
            assertThat(tables().get("daily_transaction").primaryKey()).containsExactly("ingest_seq");
        }

        @Test
        @DisplayName("every primary key constraint is named for its table")
        void everyPrimaryKeyConstraintIsNamed() throws IOException {
            final String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);
            for (final String table : COPYBOOK_OF.keySet()) {
                assertThat(sql)
                        .describedAs("pk constraint name for %s", table)
                        .contains("CONSTRAINT pk_" + table);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 3. The constraint census the AAP fixes exactly
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("3. the constraint census matches the counts the AAP fixes")
    class TheConstraintCensus {

        @Test
        @DisplayName("there are exactly five CHECK constraints, as AAP 0.4.1.3 states")
        void thereAreExactlyFiveChecks() {
            final List<String> checks = new ArrayList<>();
            tables().values().forEach(table -> checks.addAll(table.checkConstraints()));
            assertThat(checks).hasSize(EXPECTED_CHECK_COUNT);
            assertThat(checks).containsExactlyInAnyOrder(
                    "ck_account_active_status",
                    "ck_customer_pri_card_holder_ind",
                    "ck_customer_ssn_numeric",
                    "ck_card_active_status",
                    "ck_user_security_type");
        }

        @Test
        @DisplayName("the fixed count is why the credential invariant lives in the entity, not a CHECK")
        void theFixedCountExplainsTheCredentialInvariantsPlacement() {
            // A sixth CHECK asserting the BCrypt shape of sec_usr_pwd would break the count AAP
            // 0.4.1.3 and 0.5.1.9 fix at five, which is precisely why UserSecurity enforces that
            // invariant in its constructor, setter and persistence callback instead.
            final List<String> checks = new ArrayList<>();
            tables().values().forEach(table -> checks.addAll(table.checkConstraints()));
            assertThat(checks).noneMatch(name -> name.contains("pwd") || name.contains("hash"));
        }

        @Test
        @DisplayName("there are exactly ten foreign keys, numbered fk01 to fk10 with no gaps")
        void thereAreExactlyTenForeignKeys() {
            final List<String> keys = new ArrayList<>();
            tables().values().forEach(table -> keys.addAll(table.foreignKeyConstraints()));
            assertThat(keys).hasSize(EXPECTED_FOREIGN_KEY_COUNT);
            for (int ordinal = 1; ordinal <= EXPECTED_FOREIGN_KEY_COUNT; ordinal++) {
                final String prefix = String.format(java.util.Locale.ROOT, "fk%02d_", ordinal);
                assertThat(keys)
                        .describedAs("a foreign key numbered %s", prefix)
                        .anyMatch(name -> name.startsWith(prefix));
            }
        }

        @Test
        @DisplayName("every foreign key names a table the migration actually creates")
        void everyForeignKeyReferencesACreatedTable() throws IOException {
            final String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);
            final Matcher references = Pattern
                    .compile("REFERENCES\\s+(\"?[a-z_]+\"?)\\s*\\(")
                    .matcher(sql);
            int found = 0;
            while (references.find()) {
                found++;
                assertThat(tables()).containsKey(references.group(1).replace("\"", ""));
            }
            assertThat(found).isEqualTo(EXPECTED_FOREIGN_KEY_COUNT);
        }

        @Test
        @DisplayName("the migration creates no index, because index creation is its own migration")
        void theBaselineCreatesNoIndex() throws IOException {
            final String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);
            final String code = stripComments(sql);
            assertThat(code).doesNotContainIgnoringCase("CREATE INDEX");
            assertThat(code).doesNotContainIgnoringCase("CREATE UNIQUE INDEX");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 4. Nullability and the optimistic-lock columns
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("4. nullability and the optimistic-lock columns")
    class NullabilityAndVersioning {

        @Test
        @DisplayName("every column in every table is NOT NULL, as a fixed-width record has no null")
        void everyColumnIsNotNull() {
            final List<String> nullable = new ArrayList<>();
            for (final Table table : tables().values()) {
                for (final Column column : table.columns()) {
                    if (!column.notNull()) {
                        nullable.add(table.name() + "." + column.name());
                    }
                }
            }
            assertThat(nullable).isEmpty();
        }

        @Test
        @DisplayName("the census covers all eighty-eight columns, so the claim above is not vacuous")
        void theCensusIsNotVacuous() {
            int total = 0;
            for (final Table table : tables().values()) {
                total += table.columns().size();
            }
            assertThat(total).isEqualTo(88);
        }

        @Test
        @DisplayName("exactly four tables carry a version column")
        void exactlyFourTablesAreVersioned() {
            final List<String> versioned = new ArrayList<>();
            for (final Table table : tables().values()) {
                if (table.columns().stream().anyMatch(column -> "version".equals(column.name()))) {
                    versioned.add(table.name());
                }
            }
            assertThat(versioned).containsExactlyInAnyOrderElementsOf(VERSIONED_TABLES);
        }

        @ParameterizedTest(name = "{0} has no version column")
        @ValueSource(strings = {"card_cross_reference", "disclosure_group", "transaction_type",
            "transaction_category", "transaction_category_balance", "daily_transaction",
            "user_security"})
        @DisplayName("the seven unversioned tables carry no optimistic-lock column")
        void theUnversionedTablesHaveNoVersionColumn(final String table) {
            assertThat(tables().get(table).columns())
                    .noneMatch(column -> "version".equals(column.name()));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 5. Decimal discipline - the Gate 6 assertion at the schema level
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("5. no financial column is floating point, and the scales are the AAP's")
    class DecimalDiscipline {

        @Test
        @DisplayName("no approximate numeric type appears anywhere in the migration")
        void noApproximateNumericTypeAppears() throws IOException {
            final String code = stripComments(Files.readString(MIGRATION, StandardCharsets.UTF_8));
            for (final String forbidden :
                    List.of("FLOAT", "DOUBLE PRECISION", "REAL", "MONEY")) {
                assertThat(code)
                        .describedAs("the migration must not use %s in any column", forbidden)
                        .doesNotContainIgnoringCase(forbidden);
            }
        }

        @Test
        @DisplayName("every scaled column is NUMERIC with scale two, the PIC V99 of the copybooks")
        void everyScaledColumnIsNumericWithScaleTwo() {
            int scaled = 0;
            for (final Table table : tables().values()) {
                for (final Column column : table.columns()) {
                    if (column.scale() != null) {
                        scaled++;
                        assertThat(column.type())
                                .describedAs("%s.%s", table.name(), column.name())
                                .isEqualTo("NUMERIC");
                        assertThat(column.scale())
                                .describedAs("%s.%s scale", table.name(), column.name())
                                .isEqualTo(Integer.valueOf(2));
                    }
                }
            }
            assertThat(scaled)
                    .describedAs("the number of scaled columns, so the loop above is not vacuous")
                    .isEqualTo(9);
        }

        @ParameterizedTest(name = "{0}.{1} is NUMERIC({2},2)")
        @CsvSource({
            "transaction_category_balance,tran_cat_bal,11",
            "disclosure_group,dis_int_rate,6",
            "transaction,tran_amt,11",
            "daily_transaction,dalytran_amt,11",
        })
        @DisplayName("the precisions that differ from the common case are the copybook's own")
        void theUncommonPrecisionsMatchTheirCopybooks(
                final String table, final String column, final int precision) {
            final Column declared = tables().get(table).columns().stream()
                    .filter(candidate -> candidate.name().equals(column))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(table + " declares no column " + column));
            assertThat(declared.width()).isEqualTo(Integer.valueOf(precision));
            assertThat(declared.scale()).isEqualTo(Integer.valueOf(2));
        }

        @Test
        @DisplayName("the account money columns use the wider twelve-digit precision")
        void theAccountMoneyColumnsUseTwelveDigits() {
            final List<Column> money = tables().get("account").columns().stream()
                    .filter(column -> column.scale() != null)
                    .toList();
            assertThat(money).hasSize(5);
            assertThat(money).allSatisfy(column -> {
                assertThat(column.width()).isEqualTo(Integer.valueOf(12));
                assertThat(column.scale()).isEqualTo(Integer.valueOf(2));
            });
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 6. Column widths, checked against the copybooks rather than the DDL
    // ---------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("6. character column widths come from the frozen copybooks")
    class ColumnWidthsAgainstTheCopybooks {

        @Test
        @DisplayName("every fixed-width character column matches its copybook picture")
        void everyCharacterColumnMatchesItsCopybook() {
            final List<String> mismatches = new ArrayList<>();
            int compared = 0;
            for (final Table table : tables().values()) {
                final RecordLayoutCopybook copybook =
                        RecordLayoutCopybook.of(COPYBOOK_OF.get(table.name()));
                for (final Column column : table.columns()) {
                    if (!"CHAR".equals(column.type()) || column.width() == null) {
                        continue;
                    }
                    final String field = cobolFieldOf(table.name(), column.name());
                    if (!copybook.declares(field)) {
                        continue;
                    }
                    compared++;
                    final int declared = copybook.widthOf(field);
                    if (declared != column.width().intValue()) {
                        mismatches.add(table.name() + "." + column.name()
                                + " is CHAR(" + column.width() + ") but " + field
                                + " is width " + declared);
                    }
                }
            }
            assertThat(mismatches).isEmpty();
            assertThat(compared)
                    .describedAs("columns actually compared, so the assertion above is not vacuous")
                    .isGreaterThanOrEqualTo(50);
        }

        @Test
        @DisplayName("the one exempt column is exempt for the reason the AAP gives, and only that one")
        void theOnlyExemptColumnIsTheCredential() {
            assertThat(WIDTH_EXEMPT).hasSize(1);
            assertThat(WIDTH_EXEMPT).containsKey("user_security.sec_usr_pwd");
            final Column credential = tables().get("user_security").columns().stream()
                    .filter(column -> "sec_usr_pwd".equals(column.name()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("user_security declares no sec_usr_pwd"));
            assertThat(credential.type()).isEqualTo("VARCHAR");
            assertThat(credential.width()).isEqualTo(Integer.valueOf(60));
            assertThat(RecordLayoutCopybook.of("CSUSR01Y").widthOf("SEC-USR-PWD")).isEqualTo(8);
        }

        @Test
        @DisplayName("no character column is narrower than its copybook field, which would truncate")
        void noCharacterColumnIsNarrowerThanItsField() {
            for (final Table table : tables().values()) {
                final RecordLayoutCopybook copybook =
                        RecordLayoutCopybook.of(COPYBOOK_OF.get(table.name()));
                for (final Column column : table.columns()) {
                    if (column.width() == null
                            || WIDTH_EXEMPT.containsKey(table.name() + "." + column.name())) {
                        continue;
                    }
                    final String field = cobolFieldOf(table.name(), column.name());
                    if (!copybook.declares(field) || !"CHAR".equals(column.type())) {
                        continue;
                    }
                    assertThat(column.width().intValue())
                            .describedAs("%s.%s versus %s", table.name(), column.name(), field)
                            .isGreaterThanOrEqualTo(copybook.widthOf(field));
                }
            }
        }

        @Test
        @DisplayName("the timestamp columns keep the twenty-six characters the source generates")
        void theTimestampColumnsAreTwentySix() {
            final List<String> timestamps = new ArrayList<>();
            for (final Table table : tables().values()) {
                for (final Column column : table.columns()) {
                    if ("CHAR".equals(column.type()) && Integer.valueOf(26).equals(column.width())) {
                        timestamps.add(table.name() + "." + column.name());
                    }
                }
            }
            assertThat(timestamps).hasSize(4);
            assertThat(timestamps).allSatisfy(name -> assertThat(name).contains("_ts"));
        }
    }

    /** Removes line comments so a keyword mentioned in prose cannot fail a structural assertion. */
    private static String stripComments(final String sql) {
        final StringBuilder code = new StringBuilder();
        for (final String line : sql.split("\n")) {
            if (!line.trim().startsWith("--")) {
                code.append(line).append('\n');
            }
        }
        return code.toString();
    }
}

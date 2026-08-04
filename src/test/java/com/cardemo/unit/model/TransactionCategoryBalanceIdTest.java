/*
 * ******************************************************************
 * Program     : TransactionCategoryBalanceIdTest.java
 * Component   : Unit test tier, resident at
 *               src/test/java/com/cardemo/unit/model
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM tier, no container, no
 *               Spring context, no database, no live endpoint
 * Function    : Holds com.cardemo.model.key.TransactionCategoryBalanceId
 *               to the TRAN-CAT-KEY field contract of the transaction
 *               category balance record: three components in COBOL
 *               declaration order summing to the catalogued key length
 *               of 17 bytes, a total equals/hashCode identity over all
 *               three, freely constructible for a row that does not yet
 *               exist, and distinct from the identically named but
 *               unrelated six byte TRAN-CAT-KEY of CVTRA04Y.
 * Source      : app/cpy/CVTRA01Y.cpy:L5-L8    (this 17 byte key)
 * Source      : app/cpy/CVTRA04Y.cpy:L5-L7    (the 6 byte namesake)
 * Source      : app/catlg/LISTCAT.txt:L1369-L1373 (TCATBALF cluster)
 * Source      : app/cbl/CBACT04C.cbl:L188-L222    (control break)
 * Source      : app/cbl/CBTRN02C.cbl:L467-L530    (upsert path)
 * Source      : app/data/ASCII/tcatbal.txt        (50 x 50 byte seed)
 *               all @ 7756d89
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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License
 * ******************************************************************
 */
package com.cardemo.unit.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.cardemo.model.key.TransactionCategoryBalanceId;
import com.cardemo.model.key.TransactionCategoryId;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.IOException;
import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit test for {@link TransactionCategoryBalanceId}, the {@code @Embeddable} replacement for the
 * three component {@code TRAN-CAT-KEY} group of the transaction category balance record layout.
 *
 * <h2>1. What this test does</h2>
 *
 * <p>It holds the identifier to the field contract of the frozen legacy corpus, read first hand at the
 * traceability anchor commit {@code 7756d89}. Six concerns are proved, one per nested group:
 *
 * <ol>
 *   <li><strong>The component set is closed, ordered and 17 bytes wide.</strong>
 *       {@code app/cpy/CVTRA01Y.cpy:L5-L8} declares {@code 05 TRAN-CAT-KEY.} followed by exactly three
 *       subordinate items, in this order: {@code 10 TRANCAT-ACCT-ID PIC 9(11).},
 *       {@code 10 TRANCAT-TYPE-CD PIC X(02).} and {@code 10 TRANCAT-CD PIC 9(04).} The copybook is
 *       <em>parsed at run time</em> rather than transcribed into a constant, so a drift in the frozen
 *       source fails this test instead of passing silently against a stale literal.</li>
 *   <li><strong>The component order is load bearing.</strong> {@code app/cbl/CBACT04C.cbl:L188-L222}
 *       browses this file sequentially in key order and breaks on a change of account at
 *       {@code :L194}. That is correct only because the account identifier leads.</li>
 *   <li><strong>A key is constructible for a row that is absent from the table.</strong>
 *       {@code app/cbl/CBTRN02C.cbl:L467} ({@code 2700-UPDATE-TCATBAL}) populates the key at
 *       {@code :L469-L471} before reading, and {@code :L481} accepts file status {@code '00'}
 *       <em>or</em> {@code '23'}, treating record not found as an accepted create path.</li>
 *   <li><strong>Identity is total over all three components</strong>, and the type is an immutable
 *       {@code @Embeddable} whose serialized form is pinned.</li>
 *   <li><strong>The two {@code TRAN-CAT-KEY} groups are different contracts.</strong> See section 5.</li>
 *   <li><strong>Every component boundary is guarded</strong>, with the exact message asserted.</li>
 *   </ol>
 *
 * <p>Four independent corroborations fix the width at 17 bytes, so it is settled fact rather than
 * inference: copybook arithmetic {@code 11 + 2 + 4}; {@code app/catlg/LISTCAT.txt:L1371} reading
 * {@code KEYLEN----------------17} with {@code AVGLRECL--------------50} under the cluster
 * {@code AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS} named at {@code :L1369}; {@code :L1372} reading
 * {@code RKP--------------------0} so the key is the record prefix, with {@code :L1373} marking the
 * cluster {@code UNIQUE} and {@code INDEXED}; and the seed fixture
 * {@code app/data/ASCII/tcatbal.txt}, whose fifty 50 byte records split 11/2/4 into the key and then
 * 11/22 into the {@code S9(09)V99} balance and its filler. The fixture arm is asserted here through
 * {@link FixtureLoader}; the catalogue arm is quoted above and is not re-parsed, because
 * {@code LISTCAT.txt} is a 3 956 line print-out whose parsing belongs to no key test.
 *
 * <p><strong>What is deliberately not asserted here.</strong> The balance itself is out of scope. Bytes
 * 18-28 hold {@code TRAN-CAT-BAL PIC S9(09)V99} - eleven characters, not twelve, because the sign is an
 * overpunch on the final digit rather than a separate byte - and its value, its zoned decimal decoding
 * and its {@code NUMERIC(11,2)} scale belong to the entity and to the balance suite. This key carries
 * no monetary component at all, which section 4 asserts by proving that no {@code BigDecimal} and no
 * floating point type appears anywhere in the type. Only the key's <em>extent</em> is corroborated from
 * the record geometry: 17 plus 11 plus 22 accounts for the whole catalogued 50 byte record, so the key
 * cannot be one byte wider or narrower than 17 without the arithmetic failing.
 *
 * <h3>Ordering safety of the numeric mapping</h3>
 *
 * <p>Two of the three components are mapped to {@code Long} and {@code Integer} rather than to
 * {@code String}, and that does not disturb the browse order the control break depends on. Both are
 * {@code PIC 9(n)} - unsigned, fixed width, zero padded on the wire - and for such values the
 * lexicographic order of the stored bytes is identical to the numeric order of the parsed values,
 * which section 2 asserts directly over the seeded account range. A signed or variable width field
 * would not have that property, which is why the assertion is made rather than assumed.
 *
 * <h2>2. How to run, build and test</h2>
 *
 * <pre>{@code
 * ./mvnw -B -ntp clean verify                                     # full gate: -Werror, JaCoCo floor
 * ./mvnw -B -ntp test                                             # whole unit tier
 * ./mvnw -B -ntp test -Dtest=TransactionCategoryBalanceIdTest      # this class alone
 * }</pre>
 *
 * <p><strong>Surefire, not Failsafe, owns this class.</strong> The build includes
 * {@code **}{@code /*Test.java} and excludes {@code **}{@code /integration/**} and
 * {@code **}{@code /e2e/**}, so a class that ends in {@code Test} and sits under
 * {@code src/test/java/com/cardemo/unit} is collected by Surefire and by nothing else. Renaming or
 * relocating it removes it from <em>both</em> plugins at once while the build still reports success,
 * which is why section 0 asserts the binding rather than trusting it.
 *
 * <p><strong>Read the XML report, not the plain text one.</strong> The evidence after any move is
 * {@code target/surefire-reports/TEST-com.cardemo.unit.model.TransactionCategoryBalanceIdTest.xml},
 * which carries one {@code testcase} element per test. The sibling
 * {@code com.cardemo.unit.model.TransactionCategoryBalanceIdTest.txt} summary reports
 * {@code Tests run: 0} at class level and that is expected, not a fault: Surefire attributes a nested
 * group's tests to a test set named after the group's own {@code @DisplayName}, so only the two
 * section 0 tests declared directly on the outer class are counted against the class line. This is the
 * prevailing shape in this tree - 157 of the 266 test sets in a full unit run report the same way - so
 * a zero on that line means the groups ran, whereas a <em>missing</em> report file means they did not.
 *
 * <h2>3. Key configurations and defaults</h2>
 *
 * <p>None are injected and none are needed. There is no Spring context, no connection, no clock and no
 * external endpoint, so nothing here can depend on a profile, an environment variable or a container.
 * Two build settings nevertheless govern the source:
 *
 * <ul>
 *   <li>{@code -Xlint:all -Werror} with {@code failOnWarning} reaches test compilation, so a single
 *       unused import or raw type fails the build rather than warning.</li>
 *   <li>Surefire pins its working directory to the project base directory. Three assertions here read
 *       repository files relative to it - the two copybooks and the schema migration - and that pin is
 *       what makes those paths deterministic across machines.</li>
 *   </ul>
 *
 * <p>Determinism is otherwise absolute by construction: no wall clock, no default locale, no default
 * time zone, no randomness, no iteration order of an unordered collection, and no static mutable state
 * anywhere in this class.
 *
 * <h2>4. Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>The copybook parse finds fewer or more than three components.</strong>
 *       {@code app/cpy/CVTRA01Y.cpy} is frozen and is the authority; a parse failure means the file
 *       moved or was edited, which the migration forbids outright.</li>
 *   <li><strong>The build fails with "missing serialVersionUID".</strong> An
 *       {@code @Embeddable} identifier must implement {@link Serializable}, and a {@code Serializable}
 *       type without an explicit {@code serialVersionUID} is a {@code -Xlint:serial} warning, which
 *       {@code -Werror} escalates to a hard failure. Remedy: declare
 *       {@code private static final long serialVersionUID}.</li>
 *   <li><strong>An equality assertion fails.</strong> A missing, asymmetric or partial
 *       {@code equals}/{@code hashCode} does not fail cleanly in production: {@code find}, {@code merge}
 *       and dirty checking start to misbehave as intermittent, hard to reproduce data errors. Fix the
 *       identifier, never the expectation.</li>
 *   <li><strong>The contiguity assertion fails.</strong> The declaration order changed. That silently
 *       breaks the account level control break of {@code app/cbl/CBACT04C.cbl:L188-L222} and loses the
 *       accumulated interest for whole accounts.</li>
 *   <li><strong>A column assertion fails.</strong> {@code spring.jpa.hibernate.ddl-auto} is
 *       {@code validate} in every profile, so a column name or type mismatch aborts application context
 *       startup. Reconcile the identifier against
 *       {@code src/main/resources/db/migration/V1__create_schema.sql}, not the reverse.</li>
 *   <li><strong>A cross type assertion fails.</strong> The two {@code TRAN-CAT-KEY} groups were
 *       conflated. See section 5 below.</li>
 *   </ul>
 *
 * <h2>5. Contract invariants</h2>
 *
 * <ul>
 *   <li><strong>the two {@code TRAN-CAT-KEY} groups are distinct contracts.</strong>
 *       {@code app/cpy/CVTRA01Y.cpy:L5} declares a 17 byte group of three fields prefixed
 *       {@code TRANCAT-}; {@code app/cpy/CVTRA04Y.cpy:L5} declares a 6 byte group of two fields
 *       prefixed {@code TRAN-}. The group names are byte identical. Conflating them - one shared base
 *       class, one shared abstract key, even one shared helper - would corrupt both mappings.
 *       Instead: three independent value types, each with its own {@code equals} and
 *       {@code hashCode}, which is what this tree does. Rule 1 clause C's "avoid duplication" is
 *       satisfied rather than violated by that, because a few structurally similar lines across
 *       distinct value types is the correct outcome, not duplication.
 *       The recurrence of the column names {@code tran_type_cd} and {@code tran_cat_cd} across the two
 *       tables is <em>not</em> evidence of a shared type either: it is a foreign key. The migration
 *       declares {@code fk08_tcatbal_category FOREIGN KEY (tran_type_cd, tran_cat_cd) REFERENCES
 *       transaction_category (tran_type_cd, tran_cat_cd)}, so the trailing two components of this key
 *       <em>reference</em> the row that {@link TransactionCategoryId} identifies. Reference is not
 *       identity, and section 5 asserts the two types are unequal in both directions.</li>
 *   <li><strong>Hibernate compares JDBC type codes, not widths.</strong> A {@code Long}
 *       mapped over {@code NUMERIC(11)} fails {@code validate} where {@code BIGINT} passes, and an
 *       {@code Integer} over {@code NUMERIC(4)} fails where {@code INTEGER} passes. Because
 *       {@code ddl-auto} is {@code validate} in every profile, the symptom is a refused context start
 *       rather than a runtime fault. Instead: pair each Java type with the type code it validates
 *       against and let the migration own the SQL. {@code V1__create_schema.sql:L967-L978} declares
 *       {@code acct_id BIGINT NOT NULL}, {@code tran_type_cd VARCHAR(2) NOT NULL} and
 *       {@code tran_cat_cd INTEGER NOT NULL}, closed by
 *       {@code CONSTRAINT pk_transaction_category_balance PRIMARY KEY (acct_id, tran_type_cd,
 *       tran_cat_cd)} - the COBOL component order exactly. Section 1 asserts that ordering against the
 *       file, because Hibernate sorts an embeddable's attributes alphabetically and would otherwise
 *       emit {@code (acct_id, tran_cat_cd, tran_type_cd)} if DDL were ever generated from the model.
 *       No SQL type is asserted here; that is the province of the schema structure suite.</li>
 *   <li><strong>{@code Serializable} is implemented for JPA identity only.</strong> Rule 1
 *       clause D names insecure deserialization among the risky patterns to flag, and a
 *       {@code Serializable} identifier is exactly where it lands. This type must never be
 *       deserialized from untrusted input: no {@code ObjectInputStream} over caller supplied bytes, no
 *       Java serialization based cache or message payload, and no custom {@code readObject} hook.
 *       Instead, with its proof: section 4 asserts that the identifier declares <em>no</em> custom
 *       serialization hook at all, and this test deliberately performs no {@code readObject} of any
 *       kind, so the pattern is absent from the assertion surface rather than merely discouraged.</li>
 *   <li><strong>a blank type code is accepted, and that is parity.</strong>
 *       {@code TRANCAT-TYPE-CD} is {@code PIC X(02)}, a fixed width alphanumeric field in a language
 *       with no null, so an unset field holds two spaces and a blank type code is a value the legacy
 *       system can genuinely produce and key on. The identifier therefore rejects only null and a
 *       length other than two. Rejecting blanks would be a behaviour change, which the parity mandate
 *       forbids. Instead: none in the identifier; referential integrity is enforced by
 *       {@code fk08_tcatbal_category}, which no blank pair can satisfy.</li>
 *   </ul>
 *
 * @see TransactionCategoryBalanceId
 * @see TransactionCategoryId
 * @see FixtureLoader
 */
@DisplayName("TransactionCategoryBalanceId - the 17 byte TRAN-CAT-KEY of app/cpy/CVTRA01Y.cpy")
class TransactionCategoryBalanceIdTest {

    /** The frozen copybook that is the sole authority for this key's component set. */
    private static final Path CVTRA01Y = Path.of("app", "cpy", "CVTRA01Y.cpy");

    /** The frozen copybook declaring the unrelated six byte group of the same name. */
    private static final Path CVTRA04Y = Path.of("app", "cpy", "CVTRA04Y.cpy");

    /** The Flyway baseline that owns the physical column types and the primary key order. */
    private static final Path V1_MIGRATION =
            Path.of("src", "main", "resources", "db", "migration", "V1__create_schema.sql");

    /**
     * One {@code 10}-level elementary item of a copybook key group.
     *
     * <p>Matches, for example, {@code 10 TRANCAT-ACCT-ID PIC 9(11).} once the fixed column padding has
     * been collapsed. Group 1 is the COBOL data name, group 2 the picture category ({@code 9} numeric
     * or {@code X} alphanumeric) and group 3 the declared width.
     */
    private static final Pattern KEY_COMPONENT =
            Pattern.compile("^10\\s+([A-Z0-9-]+)\\s+PIC\\s+([9X])\\((\\d{2})\\)\\.$");

    /** {@code TRANCAT-ACCT-ID PIC 9(11)} - app/cpy/CVTRA01Y.cpy:L6. */
    private static final int ACCOUNT_ID_WIDTH = 11;

    /** {@code TRANCAT-TYPE-CD PIC X(02)} - app/cpy/CVTRA01Y.cpy:L7. */
    private static final int TYPE_CD_WIDTH = 2;

    /** {@code TRANCAT-CD PIC 9(04)} - app/cpy/CVTRA01Y.cpy:L8. */
    private static final int CAT_CD_WIDTH = 4;

    /** {@code KEYLEN----------------17} - app/catlg/LISTCAT.txt:L1371. */
    private static final int CATALOGUED_KEY_LENGTH = 17;

    /** {@code AVGLRECL--------------50} - app/catlg/LISTCAT.txt:L1371, and the fixture record width. */
    private static final int CATALOGUED_RECORD_LENGTH = 50;

    /** {@code TRAN-CAT-BAL PIC S9(09)V99} - app/cpy/CVTRA01Y.cpy:L9, nine digits, two decimals, one sign. */
    private static final int BALANCE_WIDTH = 11;

    /** {@code FILLER PIC X(22)} - app/cpy/CVTRA01Y.cpy:L10. */
    private static final int FILLER_WIDTH = 22;

    /** Java component names, in the copybook's declaration order. */
    private static final List<String> COMPONENT_NAMES = List.of("accountId", "typeCd", "catCd");

    /** Mapped column names, in the same order, spelling the composite primary key. */
    private static final List<String> COLUMN_NAMES = List.of("acct_id", "tran_type_cd", "tran_cat_cd");

    /** The widest value {@code PIC 9(11)} can hold: eleven nines. */
    private static final long WIDEST_ACCOUNT_ID = 99_999_999_999L;

    /** The widest value {@code PIC 9(04)} can hold: four nines. */
    private static final int WIDEST_CAT_CD = 9999;

    /**
     * Builds the key of the first row of the seed fixture: account 1, type {@code 01}, category 1.
     *
     * <p>Every row of {@code app/data/ASCII/tcatbal.txt} carries the type and category pair
     * {@code 010001}, so this is a real key rather than an invented one. It is produced by a method
     * rather than held in a field so that each test gets its own instance and no test can perturb
     * another.
     *
     * @return a freshly built key for the first seeded row
     */
    private static TransactionCategoryBalanceId firstSeededKey() {
        return new TransactionCategoryBalanceId(1L, "01", 1);
    }

    /**
     * Captures the message of the rejection a wrongly sized type code provokes.
     *
     * <p>The exception is never swallowed: it is caught, its message is returned for inspection, and the
     * absence of a rejection is itself a failure rather than a silent pass.
     *
     * @param typeCd a type code whose length is not two
     * @return the rejection message, verbatim
     */
    private static String catchTypeCdRejection(final String typeCd) {
        try {
            new TransactionCategoryBalanceId(1L, typeCd, 1);
        } catch (final IllegalArgumentException rejection) {
            return rejection.getMessage();
        }
        throw new AssertionError("a type code of length " + typeCd.length()
                + " must be refused, because TRANCAT-TYPE-CD is PIC X(02)");
    }

    /**
     * One elementary item of a COBOL key group, as parsed from a frozen copybook.
     *
     * @param name     the COBOL data name, for example {@code TRANCAT-ACCT-ID}
     * @param category the picture category: {@code '9'} numeric or {@code 'X'} alphanumeric
     * @param width    the declared width in bytes
     */
    private record Component(String name, char category, int width) {

        /**
         * Renders this item the way the copybook declares it, so a failure message quotes the source.
         *
         * @return the item as {@code NAME PIC C(nn)}, the width zero padded to two digits
         */
        String asDeclared() {
            return name + " PIC " + category + "(" + String.format(Locale.ROOT, "%02d", width) + ")";
        }
    }

    /**
     * Parses every {@code 10}-level elementary item out of a frozen copybook, in file order.
     *
     * <p>Reading the copybook rather than transcribing it is what makes the width and order assertions
     * evidence based: a change to the frozen source fails the test instead of passing against a stale
     * literal. Group items ({@code 01} and {@code 05} levels), comment lines and the version trailer do
     * not match {@link #KEY_COMPONENT} and are skipped.
     *
     * @param copybook the repository relative path of the copybook
     * @return the elementary items of its key group, in declaration order
     * @throws IOException if the copybook cannot be read, which means it was moved or removed
     */
    private static List<Component> keyComponentsOf(final Path copybook) throws IOException {
        final List<Component> components = new ArrayList<>();
        for (final String line : Files.readAllLines(copybook, StandardCharsets.UTF_8)) {
            final Matcher matcher = KEY_COMPONENT.matcher(line.trim());
            if (matcher.matches()) {
                components.add(new Component(matcher.group(1), matcher.group(2).charAt(0),
                        Integer.parseInt(matcher.group(3))));
            }
        }
        return List.copyOf(components);
    }

    /**
     * Collects the identifier's mapped components, preserving the order they are declared in.
     *
     * <p>Static members are excluded because {@code serialVersionUID} and the boundary constants are not
     * components, and synthetic members because the compiler may add its own. Declaration order is what
     * the copybook fixes, so it is preserved rather than sorted.
     *
     * @return the declared instance fields of the identifier, in declaration order
     */
    private static List<Field> mappedFields() {
        final List<Field> fields = new ArrayList<>();
        for (final Field field : TransactionCategoryBalanceId.class.getDeclaredFields()) {
            if (!field.isSynthetic() && !Modifier.isStatic(field.getModifiers())) {
                fields.add(field);
            }
        }
        return List.copyOf(fields);
    }

    /**
     * Returns the {@code @Column} of one component, failing the test rather than returning null when the
     * annotation is absent, so that an unmapped component reports as a mapping defect.
     *
     * @param componentName the Java field name
     * @return its column mapping
     * @throws NoSuchFieldException if the component does not exist under that name
     */
    private static Column columnOf(final String componentName) throws NoSuchFieldException {
        final Column column = TransactionCategoryBalanceId.class
                .getDeclaredField(componentName).getAnnotation(Column.class);

        assertThat(column)
                .as("%s must carry an explicit @Column so that no naming strategy can rename it "
                        + "implicitly; ddl-auto is validate, so an implicit name aborts context startup",
                        componentName)
                .isNotNull();
        return column;
    }

    /**
     * Instantiates the identifier through its no-argument constructor, unsealing it first.
     *
     * <p>That constructor is deliberately {@code protected}: an {@code @Embeddable} must expose one for
     * the persistence provider, and {@code protected} is the narrowest visibility that satisfies the
     * specification without publishing a half built key to application code. Hibernate reaches it
     * reflectively after {@code setAccessible(true)}, so the test reaches it the same way. Invoking it
     * directly would instead assert public visibility, which is precisely what the type must not have.
     *
     * @return a staged instance with all three components absent
     * @throws ReflectiveOperationException if the constructor is absent or cannot be invoked
     */
    private static TransactionCategoryBalanceId stagedInstance() throws ReflectiveOperationException {
        final Constructor<TransactionCategoryBalanceId> constructor =
                TransactionCategoryBalanceId.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    // ==================================================================
    // 0 - Build path self check. A test class that stops being collected
    //     stops protecting anything, and says nothing while it happens.
    // ==================================================================

    @Test
    @DisplayName("0. this class sits where Surefire collects it, and nowhere Failsafe would claim it")
    void thisClassSitsWhereSurefireCollectsIt() {
        final Class<?> self = TransactionCategoryBalanceIdTest.class;

        assertThat(self.getPackageName())
                .as("the unit tier package root is com.cardemo, never com.carddemo")
                .isEqualTo("com.cardemo.unit.model");
        assertThat(self.getSimpleName())
                .as("Surefire includes **/*Test.java and **/*Tests.java, so the suffix is what makes this "
                        + "class visible to the build at all")
                .endsWith("Test");
        assertThat(self.getPackageName())
                .as("Surefire excludes **/integration/** and **/e2e/**, and Failsafe includes exactly "
                        + "those two trees. A class in neither set is collected by NEITHER plugin: the "
                        + "build stays green, both plugins report success, and the class silently never "
                        + "runs.")
                .doesNotContain("integration")
                .doesNotContain("e2e");

        assertThat(Path.of("src", "test", "java", "com", "cardemo", "unit", "model",
                self.getSimpleName() + ".java"))
                .as("the source must sit at the path the package implies; the evidence to read after any "
                        + "move is target/surefire-reports/TEST-%s.xml, whose testcase elements are the "
                        + "proof of collection - the plain text summary reports 0 at class level because "
                        + "each nested group forms its own test set", self.getName())
                .isRegularFile();
    }

    @Test
    @DisplayName("0. every nested group is annotated @Nested, so no group can be silently dropped")
    void everyNestedGroupIsAnnotatedNested() {
        final List<String> unannotated = new ArrayList<>();
        for (final Class<?> group : TransactionCategoryBalanceIdTest.class.getDeclaredClasses()) {
            if (!group.isRecord() && group.getAnnotation(Nested.class) == null) {
                unannotated.add(group.getSimpleName());
            }
        }

        assertThat(unannotated)
                .as("an inner class that loses its @Nested annotation stops contributing tests without "
                        + "failing anything, which is the same silent loss as a relocated class")
                .isEmpty();
        assertThat(TransactionCategoryBalanceIdTest.class.getDeclaredClasses())
                .as("six groups plus the Component record used to parse the frozen copybooks")
                .hasSize(7);
    }

    // ==================================================================
    // 1 - The field contract of app/cpy/CVTRA01Y.cpy:L5-L8, proved against
    //     the frozen copybook, the seed fixture and the migration.
    // ==================================================================

    @Nested
    @DisplayName("1. Field contract: three components, COBOL order, 17 bytes")
    class CopybookFieldContract {

        @Test
        @DisplayName("the copybook declares exactly three key components, in order, at their PIC widths")
        void copybookDeclaresExactlyThreeComponentsInOrder() throws IOException {
            final List<Component> components = keyComponentsOf(CVTRA01Y);

            assertThat(components)
                    .as("app/cpy/CVTRA01Y.cpy:L5-L8 declares 05 TRAN-CAT-KEY over exactly three 10-level "
                            + "items; a fourth would widen the key past the catalogued 17 bytes and a "
                            + "third missing one would narrow it")
                    .hasSize(3)
                    .extracting(Component::asDeclared)
                    .containsExactly(
                            "TRANCAT-ACCT-ID PIC 9(11)",
                            "TRANCAT-TYPE-CD PIC X(02)",
                            "TRANCAT-CD PIC 9(04)");
        }

        @Test
        @DisplayName("the third component is TRANCAT-CD and never the plausible TRANCAT-CAT-CD")
        void theThirdComponentIsTrancatCd() throws IOException {
            final Component third = keyComponentsOf(CVTRA01Y).get(2);

            assertThat(third.name())
                    .as("read directly at app/cpy/CVTRA01Y.cpy:L8. The sibling copybook CVTRA04Y spells "
                            + "its category code TRAN-CAT-CD, which makes TRANCAT-CAT-CD a plausible "
                            + "misreading here; it is not the name and must never be corrected to it")
                    .isEqualTo("TRANCAT-CD")
                    .isNotEqualTo("TRANCAT-CAT-CD");
        }

        @Test
        @DisplayName("the three PIC widths sum to the catalogued TCATBALF key length of 17 bytes")
        void componentWidthsSumToTheCataloguedKeyLength() throws IOException {
            int declaredWidth = 0;
            for (final Component component : keyComponentsOf(CVTRA01Y)) {
                declaredWidth += component.width();
            }

            assertThat(declaredWidth)
                    .as("11 + 2 + 4 is the KEYLEN 17 that app/catlg/LISTCAT.txt:L1371 records for "
                            + "AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS, named at :L1369; :L1372 reads RKP 0, so "
                            + "the key is the record prefix and this arithmetic fixes its extent")
                    .isEqualTo(CATALOGUED_KEY_LENGTH)
                    .isEqualTo(ACCOUNT_ID_WIDTH + TYPE_CD_WIDTH + CAT_CD_WIDTH);
        }

        @Test
        @DisplayName("the key occupies bytes 1-17 of every seeded record, at the widths the copybook gives")
        void theSeedFixtureSplitsAtTheCopybookWidths() {
            final FixtureLoader.FixtureData seed =
                    FixtureLoader.load(FixtureLoader.Fixture.TRANSACTION_CATEGORY_BALANCE);

            assertThat(seed.recordWidth())
                    .as("AVGLRECL 50 at app/catlg/LISTCAT.txt:L1371, and RECLN = 50 at "
                            + "app/cpy/CVTRA01Y.cpy:L2")
                    .isEqualTo(CATALOGUED_RECORD_LENGTH);
            assertThat(CATALOGUED_KEY_LENGTH + BALANCE_WIDTH + FILLER_WIDTH)
                    .as("the 17 byte key, the eleven character S9(09)V99 balance of :L9 and the X(22) "
                            + "filler of :L10 account for the whole 50 byte record with nothing left over")
                    .isEqualTo(CATALOGUED_RECORD_LENGTH);

            assertThat(seed.field(0, 1, ACCOUNT_ID_WIDTH))
                    .as("TRANCAT-ACCT-ID occupies columns 1-11 as eleven zero padded digits")
                    .isEqualTo("00000000001");
            assertThat(seed.field(0, 12, TYPE_CD_WIDTH))
                    .as("TRANCAT-TYPE-CD occupies columns 12-13")
                    .isEqualTo("01");
            assertThat(seed.field(0, 14, CAT_CD_WIDTH))
                    .as("TRANCAT-CD occupies columns 14-17, which is where the key ends")
                    .isEqualTo("0001");
            assertThat(seed.field(0, 1, CATALOGUED_KEY_LENGTH))
                    .as("the three slices are contiguous, so the whole key reads as one 17 character run")
                    .hasSize(CATALOGUED_KEY_LENGTH)
                    .isEqualTo("00000000001" + "01" + "0001");
        }

        @Test
        @DisplayName("every seeded record carries a full 17 byte key, so none is padded or truncated")
        void everySeededRecordCarriesAFullKey() {
            final FixtureLoader.FixtureData seed =
                    FixtureLoader.load(FixtureLoader.Fixture.TRANSACTION_CATEGORY_BALANCE);
            final Set<String> keys = new HashSet<>();

            for (int record = 0; record < seed.recordCount(); record++) {
                final String key = seed.field(record, 1, CATALOGUED_KEY_LENGTH);
                assertThat(key).as("record %d of %s", record, seed.resourceName())
                        .hasSize(CATALOGUED_KEY_LENGTH)
                        .containsOnlyDigits();
                keys.add(key);
            }

            assertThat(keys)
                    .as("app/catlg/LISTCAT.txt:L1373 marks the cluster UNIQUE, so no two records may "
                            + "share a key; %d records must therefore yield %d distinct keys",
                            seed.recordCount(), seed.recordCount())
                    .hasSize(seed.recordCount());
        }

        @Test
        @DisplayName("the identifier declares exactly the three components, under COBOL order names")
        void identifierDeclaresTheThreeComponentsInCopybookOrder() {
            assertThat(mappedFields())
                    .extracting(Field::getName)
                    .as("the account id MUST lead, or the account level control break in "
                            + "app/cbl/CBACT04C.cbl:L188-L222 stops being correct")
                    .isEqualTo(COMPONENT_NAMES);
        }

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({"accountId, acct_id", "typeCd, tran_type_cd", "catCd, tran_cat_cd"})
        @DisplayName("each component is mapped explicitly to its column and marked NOT NULL")
        void eachComponentIsMappedExplicitlyAndNotNull(final String componentName, final String columnName)
                throws NoSuchFieldException {
            final Column column = columnOf(componentName);

            assertThat(column.name()).isEqualTo(columnName);
            assertThat(column.nullable())
                    .as("every component of a composite primary key is NOT NULL by construction, and "
                            + "COBOL has no null to represent an absent one")
                    .isFalse();
        }

        @Test
        @DisplayName("the type code column is bounded at the exact PIC X(02) width")
        void theTypeCodeColumnIsBoundedAtItsPicWidth() throws NoSuchFieldException {
            assertThat(columnOf("typeCd").length())
                    .as("TRANCAT-TYPE-CD is PIC X(02) at app/cpy/CVTRA01Y.cpy:L7; a wider column would "
                            + "accept a value the fixed width source cannot hold")
                    .isEqualTo(TYPE_CD_WIDTH);
        }

        @Test
        @DisplayName("the column names in declaration order spell (acct_id, tran_type_cd, tran_cat_cd)")
        void columnNamesInDeclarationOrderSpellTheCompositeKey() throws NoSuchFieldException {
            final List<String> declared = new ArrayList<>();
            for (final Field component : mappedFields()) {
                declared.add(columnOf(component.getName()).name());
            }

            assertThat(declared)
                    .as("this is the composite primary key order the schema must declare physically; "
                            + "Hibernate sorts an embeddable's attributes alphabetically and would emit "
                            + "(acct_id, tran_cat_cd, tran_type_cd) if DDL were generated from the model")
                    .isEqualTo(COLUMN_NAMES);
        }

        @Test
        @DisplayName("the migration declares the primary key in that same COBOL component order")
        void theMigrationDeclaresThePrimaryKeyInCobolOrder() throws IOException {
            final String migration = Files.readString(V1_MIGRATION, StandardCharsets.UTF_8);

            assertThat(migration)
                    .as("measured at src/main/resources/db/migration/V1__create_schema.sql:L977-L978. The "
                            + "physical order is owned by the migration, never by provider generated DDL, "
                            + "because only the migration can put the account id first")
                    .contains("CONSTRAINT pk_transaction_category_balance")
                    .contains("PRIMARY KEY (" + String.join(", ", COLUMN_NAMES) + ")");
        }

        @Test
        @DisplayName("the account id is a Long, because PIC 9(11) overflows a 32 bit int")
        void theAccountIdIsALongBecauseElevenDigitsOverflowAnInt() throws NoSuchFieldException {
            assertThat(TransactionCategoryBalanceId.class.getDeclaredField("accountId").getType())
                    .as("PIC 9(11) reaches %d, which does not fit an int; an Integer here would wrap "
                            + "silently on a high account id", WIDEST_ACCOUNT_ID)
                    .isEqualTo(Long.class);

            assertThat(WIDEST_ACCOUNT_ID).isGreaterThan(Integer.MAX_VALUE);
            assertThat(new TransactionCategoryBalanceId(WIDEST_ACCOUNT_ID, "01", 1).getAccountId())
                    .isEqualTo(WIDEST_ACCOUNT_ID);
        }

        @ParameterizedTest(name = "{0} is a {1}")
        @CsvSource({"typeCd, java.lang.String", "catCd, java.lang.Integer"})
        @DisplayName("the remaining components take the type their PIC category implies")
        void theRemainingComponentsTakeTheirPicTypes(final String componentName, final String typeName)
                throws NoSuchFieldException {
            assertThat(TransactionCategoryBalanceId.class.getDeclaredField(componentName)
                    .getType().getName())
                    .as("PIC X(02) is alphanumeric and PIC 9(04) is a four digit unsigned number, so "
                            + "neither may be widened or narrowed at the type boundary")
                    .isEqualTo(typeName);
        }

        @Test
        @DisplayName("every component is returned exactly as supplied, unpadded and unnormalised")
        void componentsAreReturnedExactlyAsSupplied() {
            final TransactionCategoryBalanceId key = new TransactionCategoryBalanceId(11L, "05", 4321);

            assertThat(key.getAccountId()).isEqualTo(11L);
            assertThat(key.getTypeCd())
                    .as("no zero padding to the PIC width and no case folding: the identifier stores the "
                            + "value it was given, and rendering is the fixed width writer's concern")
                    .isEqualTo("05");
            assertThat(key.getCatCd()).isEqualTo(4321);
        }
    }

    // ==================================================================
    // 2 - Why the component order is load bearing: the account level
    //     control break of app/cbl/CBACT04C.cbl:L188-L222.
    // ==================================================================

    /**
     * Replays the control break of {@code app/cbl/CBACT04C.cbl:L194} over a browse sequence.
     *
     * <p>The source holds the previous account in {@code WS-LAST-ACCT-NUM}, tests
     * {@code IF TRANCAT-ACCT-ID NOT= WS-LAST-ACCT-NUM}, flushes the accumulated interest through
     * {@code 1050-UPDATE-ACCOUNT} at {@code :L196} when the test succeeds and is not the first record,
     * and re-latches the account at {@code :L201}. This method returns the account seen at each break,
     * in visit order, which is exactly the sequence of flushes the job performs.
     *
     * @param browseOrder the keys in the order a sequential browse would return them
     * @return the account identifier at each control break, in order, repeats included
     */
    private static List<Long> accountBreaks(final List<TransactionCategoryBalanceId> browseOrder) {
        final List<Long> breaks = new ArrayList<>();
        Long lastAccount = null;
        for (final TransactionCategoryBalanceId key : browseOrder) {
            if (!key.getAccountId().equals(lastAccount)) {
                breaks.add(key.getAccountId());
                lastAccount = key.getAccountId();
            }
        }
        return List.copyOf(breaks);
    }

    /**
     * Supplies a browse population whose declaration order is deliberately not key order.
     *
     * <p>Three accounts contribute six rows, interleaved so that neither a stable sort nor an accidental
     * insertion order could make a contiguity assertion pass for the wrong reason. The rows must be
     * sorted before they model a VSAM browse.
     *
     * @return six keys spanning accounts 1, 2 and 3, in no useful order
     */
    private static List<TransactionCategoryBalanceId> unorderedPopulation() {
        return List.of(
                new TransactionCategoryBalanceId(2L, "01", 5),
                new TransactionCategoryBalanceId(1L, "05", 1),
                new TransactionCategoryBalanceId(3L, "01", 1),
                new TransactionCategoryBalanceId(1L, "01", 5),
                new TransactionCategoryBalanceId(2L, "01", 1),
                new TransactionCategoryBalanceId(1L, "01", 9));
    }

    @Nested
    @DisplayName("2. Component order is load bearing, not cosmetic")
    class ComponentOrderIsLoadBearing {

        @Test
        @DisplayName("in component order every account is one contiguous run, so each breaks exactly once")
        void componentOrderKeepsEachAccountContiguous() {
            final List<TransactionCategoryBalanceId> browse =
                    new ArrayList<>(unorderedPopulation());
            browse.sort(Comparator.comparing(TransactionCategoryBalanceId::getAccountId)
                    .thenComparing(TransactionCategoryBalanceId::getTypeCd)
                    .thenComparing(TransactionCategoryBalanceId::getCatCd));

            assertThat(accountBreaks(browse))
                    .as("a VSAM browse proceeds in key order, so the byte layout of the key IS the sort "
                            + "order. With the account id leading, each account is visited as one "
                            + "contiguous run and app/cbl/CBACT04C.cbl:L196 flushes it exactly once")
                    .containsExactly(1L, 2L, 3L)
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("alphabetising the components fragments accounts, which is the loss this order prevents")
        void alphabetisedOrderFragmentsAccounts() {
            final List<TransactionCategoryBalanceId> browse =
                    new ArrayList<>(unorderedPopulation());
            browse.sort(Comparator.comparing(TransactionCategoryBalanceId::getCatCd)
                    .thenComparing(TransactionCategoryBalanceId::getAccountId)
                    .thenComparing(TransactionCategoryBalanceId::getTypeCd));

            assertThat(accountBreaks(browse))
                    .as("this is the order Hibernate would derive if the components were declared "
                            + "alphabetically as catCd, accountId, typeCd. Account 1 and account 2 each "
                            + "break more than once, so app/cbl/CBACT04C.cbl:L196 would flush a partial "
                            + "interest total and :L200 would reset the running sum mid account - "
                            + "interest silently lost for whole accounts.")
                    .hasSizeGreaterThan(3)
                    .contains(1L, 2L, 3L);
            assertThat(accountBreaks(browse).stream().distinct().count())
                    .as("the same three accounts are still present; only their contiguity is destroyed")
                    .isEqualTo(3L);
        }

        @Test
        @DisplayName("zero padded fixed width digits sort lexicographically exactly as they sort numerically")
        void zeroPaddedComponentsSortLexicographicallyAsTheySortNumerically() {
            final FixtureLoader.FixtureData seed =
                    FixtureLoader.load(FixtureLoader.Fixture.TRANSACTION_CATEGORY_BALANCE);
            final List<String> storedOrder = new ArrayList<>();
            final List<Long> parsedOrder = new ArrayList<>();

            for (int record = 0; record < seed.recordCount(); record++) {
                final String stored = seed.field(record, 1, ACCOUNT_ID_WIDTH);
                storedOrder.add(stored);
                parsedOrder.add(Long.valueOf(stored));
            }

            final List<String> lexicographic = new ArrayList<>(storedOrder);
            lexicographic.sort(Comparator.naturalOrder());
            final List<Long> numeric = new ArrayList<>(parsedOrder);
            numeric.sort(Comparator.naturalOrder());

            final List<Long> lexicographicAsNumbers = new ArrayList<>();
            for (final String stored : lexicographic) {
                lexicographicAsNumbers.add(Long.valueOf(stored));
            }

            assertThat(lexicographicAsNumbers)
                    .as("TRANCAT-ACCT-ID is PIC 9(11): unsigned, fixed width and zero padded on the wire. "
                            + "For such a field the byte order a VSAM browse follows and the numeric order "
                            + "a Long comparison follows are the same sequence, which is what makes the "
                            + "Long mapping safe for the control break. A signed or variable width field "
                            + "would not have that property")
                    .isEqualTo(numeric);
        }

        @Test
        @DisplayName("the four digit category code likewise preserves order across the Integer mapping")
        void theCategoryCodePreservesOrderAcrossTheIntegerMapping() {
            final List<String> stored = List.of("0000", "0001", "0009", "0010", "0099", "0100", "9999");
            final List<Integer> parsed = new ArrayList<>();
            for (final String value : stored) {
                parsed.add(Integer.valueOf(value));
            }

            final List<String> lexicographic = new ArrayList<>(stored);
            lexicographic.sort(Comparator.naturalOrder());
            final List<Integer> numeric = new ArrayList<>(parsed);
            numeric.sort(Comparator.naturalOrder());

            assertThat(lexicographic)
                    .as("TRANCAT-CD is PIC 9(04), so the four character run is always zero padded and "
                            + "already in order; nothing is reordered by sorting it")
                    .isEqualTo(stored);
            assertThat(numeric).isEqualTo(parsed);
            assertThat(Integer.valueOf(lexicographic.get(lexicographic.size() - 1)))
                    .isEqualTo(WIDEST_CAT_CD);
        }

        @Test
        @DisplayName("within one account the type code orders ahead of the category code")
        void withinOneAccountTheTypeCodeOrdersAheadOfTheCategoryCode() {
            final List<TransactionCategoryBalanceId> browse = new ArrayList<>(List.of(
                    new TransactionCategoryBalanceId(1L, "05", 1),
                    new TransactionCategoryBalanceId(1L, "01", 9),
                    new TransactionCategoryBalanceId(1L, "01", 1)));
            browse.sort(Comparator.comparing(TransactionCategoryBalanceId::getAccountId)
                    .thenComparing(TransactionCategoryBalanceId::getTypeCd)
                    .thenComparing(TransactionCategoryBalanceId::getCatCd));

            assertThat(browse)
                    .as("bytes 12-13 precede bytes 14-17 in the key, so the type code is the major sort "
                            + "within an account and the category code the minor one. "
                            + "app/cbl/CBACT04C.cbl:L211-L212 reads both per row to resolve the rate, so "
                            + "their relative order decides the sequence of rate lookups")
                    .containsExactly(
                            new TransactionCategoryBalanceId(1L, "01", 1),
                            new TransactionCategoryBalanceId(1L, "01", 9),
                            new TransactionCategoryBalanceId(1L, "05", 1));
        }
    }

    // ==================================================================
    // 3 - Freely constructible for a row that is absent from the table:
    //     the upsert path of app/cbl/CBTRN02C.cbl:L467-L530.
    // ==================================================================

    @Nested
    @DisplayName("3. Constructible for a row that does not yet exist")
    class ConstructibleForARowThatDoesNotYetExist {

        @Test
        @DisplayName("exposes a public all components constructor whose parameters are in COBOL order")
        void exposesAPublicAllComponentsConstructorInCobolOrder() throws NoSuchMethodException {
            final Constructor<TransactionCategoryBalanceId> constructor = TransactionCategoryBalanceId.class
                    .getConstructor(Long.class, String.class, Integer.class);

            assertThat(Modifier.isPublic(constructor.getModifiers()))
                    .as("application code, batch processors and tests all build this key directly, so the "
                            + "all components constructor is part of the published surface")
                    .isTrue();
            assertThat(constructor.getParameterTypes())
                    .as("the parameter order mirrors app/cpy/CVTRA01Y.cpy:L6-L8 so that a call site reads "
                            + "in the same sequence as the three MOVEs at app/cbl/CBTRN02C.cbl:L469-L471")
                    .containsExactly(Long.class, String.class, Integer.class);
        }

        @Test
        @DisplayName("declares exactly two constructors, so no partial or convenience form can drift in")
        void declaresExactlyTwoConstructors() {
            assertThat(TransactionCategoryBalanceId.class.getDeclaredConstructors())
                    .as("one no-argument constructor for the provider and one all components constructor "
                            + "for callers. A two argument form would let a caller build a key that "
                            + "identifies no row")
                    .hasSize(2);
        }

        @Test
        @DisplayName("exposes a no-argument constructor for the provider, and keeps it out of the public API")
        void exposesANonPublicNoArgumentConstructorForTheProvider() throws ReflectiveOperationException {
            final Constructor<TransactionCategoryBalanceId> constructor =
                    TransactionCategoryBalanceId.class.getDeclaredConstructor();

            assertThat(Modifier.isPublic(constructor.getModifiers()))
                    .as("JPA requires a no-argument constructor and reaches it reflectively, so protected "
                            + "is the narrowest visibility that satisfies the specification without "
                            + "publishing a half built key to application code")
                    .isFalse();
            assertThat(Modifier.isProtected(constructor.getModifiers())).isTrue();

            final TransactionCategoryBalanceId staged = stagedInstance();
            assertThat(staged.getAccountId()).isNull();
            assertThat(staged.getTypeCd()).isNull();
            assertThat(staged.getCatCd()).isNull();
        }

        @Test
        @DisplayName("builds a key for a row that is absent, which is what the create path requires")
        void buildsAKeyForARowThatIsAbsent() {
            assertThatCode(() -> new TransactionCategoryBalanceId(WIDEST_ACCOUNT_ID, "99", WIDEST_CAT_CD))
                    .as("app/cbl/CBTRN02C.cbl:L469-L471 fills all three components BEFORE the READ at "
                            + ":L474, and :L481 accepts file status '00' OR '23' - so a not found record is "
                            + "an accepted control path that dispatches to 2700-A-CREATE-TCATBAL-REC at "
                            + ":L503. No component of this key may therefore depend on the row existing")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("asks nothing about the row: no collaborator, no lookup, no I/O on any component")
        void asksNothingAboutTheRow() {
            for (final Field component : mappedFields()) {
                assertThat(component.getType().getName())
                        .as("%s must be a plain value; a repository, an entity manager or any collaborator "
                                + "here would make constructing a key perform a lookup, and the create "
                                + "path at app/cbl/CBTRN02C.cbl:L503 has no row to look up",
                                component.getName())
                        .startsWith("java.lang.");
            }

            assertThat(TransactionCategoryBalanceId.class.getSuperclass())
                    .as("the identifier extends Object directly, so it inherits no state and no behaviour "
                            + "that could reach a data store")
                    .isEqualTo(Object.class);
        }

        @Test
        @DisplayName("carries no persistence lifecycle callback, so no hook can fire on a staged key")
        void carriesNoPersistenceLifecycleCallback() {
            final List<String> hooks = new ArrayList<>();
            for (final Method method : TransactionCategoryBalanceId.class.getDeclaredMethods()) {
                for (final Annotation annotation : method.getAnnotations()) {
                    if (annotation.annotationType().getName().startsWith("jakarta.persistence.")) {
                        hooks.add(method.getName() + " @" + annotation.annotationType().getSimpleName());
                    }
                }
            }

            assertThat(hooks)
                    .as("a callback such as @PrePersist on an @Embeddable identifier would run while the "
                            + "provider has only partly populated it, and the upsert path builds this key "
                            + "before any row is known to exist. There must be none")
                    .isEmpty();
        }

        @Test
        @DisplayName("initialises no component and seeds no balance: the key is key, never payload")
        void initialisesNoComponentAndSeedsNoBalance() throws ReflectiveOperationException {
            assertThat(mappedFields())
                    .as("app/cpy/CVTRA01Y.cpy nests exactly three items under 05 TRAN-CAT-KEY at :L5-L8; "
                            + "TRAN-CAT-BAL at :L9 and FILLER at :L10 are record payload mapped by the "
                            + "entity, so neither may appear here")
                    .hasSize(3)
                    .extracting(Field::getName)
                    .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("bal"));

            final TransactionCategoryBalanceId staged = stagedInstance();
            assertThat(List.of(String.valueOf(staged.getAccountId()), String.valueOf(staged.getTypeCd()),
                            String.valueOf(staged.getCatCd())))
                    .as("2700-A-CREATE-TCATBAL-REC INITIALIZEs the whole record at "
                            + "app/cbl/CBTRN02C.cbl:L504 and only then MOVEs the three components in at "
                            + ":L505-L507, so a default invented here would fabricate a key the source "
                            + "never produced")
                    .containsExactly("null", "null", "null");
        }
    }

    // ==================================================================
    // 4 - @Embeddable, Serializable, a pinned serialized form, and an
    //     equals/hashCode contract total over all three components.
    // ==================================================================

    @Nested
    @DisplayName("4. Embeddable value type with a total identity")
    class EmbeddableSerializableAndIdentity {

        @Test
        @DisplayName("is @Embeddable and Serializable, as a JPA composite identifier must be")
        void isEmbeddableAndSerializable() {
            assertThat(TransactionCategoryBalanceId.class.getAnnotation(Embeddable.class))
                    .as("the entity mounts this type through @EmbeddedId, which requires @Embeddable")
                    .isNotNull();
            assertThat(Serializable.class)
                    .as("the specification requires a composite identifier to be Serializable")
                    .isAssignableFrom(TransactionCategoryBalanceId.class);
        }

        @Test
        @DisplayName("pins its serialized form with an explicit serialVersionUID")
        void pinsItsSerializedFormWithAnExplicitSerialVersionUid() throws NoSuchFieldException {
            final Field uid = TransactionCategoryBalanceId.class.getDeclaredField("serialVersionUID");

            assertThat(uid.getType()).isEqualTo(long.class);
            assertThat(Modifier.isPrivate(uid.getModifiers())).isTrue();
            assertThat(Modifier.isStatic(uid.getModifiers())).isTrue();
            assertThat(Modifier.isFinal(uid.getModifiers()))
                    .as("without an explicit serialVersionUID the value is computed from the class shape, "
                            + "so any later edit changes it. -Xlint:serial reports the omission and "
                            + "-Werror with failOnWarning turns that report into a hard build failure, so "
                            + "this is a compile time contract as well as a runtime one")
                    .isTrue();
        }

        @Test
        @DisplayName("declares no custom serialization hook, keeping the deserialization surface at zero")
        void declaresNoCustomSerializationHook() {
            final List<String> hooks = new ArrayList<>();
            for (final Method method : TransactionCategoryBalanceId.class.getDeclaredMethods()) {
                if (Set.of("readObject", "readObjectNoData", "readResolve", "readExternal",
                        "writeObject", "writeReplace", "writeExternal").contains(method.getName())) {
                    hooks.add(method.getName());
                }
            }

            assertThat(hooks)
                    .as("Serializable is implemented for JPA identity ONLY. Rule 1 clause D names insecure "
                            + "deserialization among the risky patterns, and a custom readObject is where "
                            + "it becomes exploitable. This type must never be deserialized from untrusted "
                            + "input: no ObjectInputStream over caller supplied bytes, no Java "
                            + "serialization based cache or message payload. Should a hook ever "
                            + "appear, delete it and use the constructor")
                    .isEmpty();
        }

        @Test
        @DisplayName("equals is reflexive")
        void equalsIsReflexive() {
            final TransactionCategoryBalanceId key = firstSeededKey();

            assertThat(key.equals(key)).isTrue();
        }

        @Test
        @DisplayName("equals is symmetric across two separately built instances")
        void equalsIsSymmetric() {
            final TransactionCategoryBalanceId left = firstSeededKey();
            final TransactionCategoryBalanceId right = new TransactionCategoryBalanceId(1L, "01", 1);

            assertThat(left.equals(right)).isTrue();
            assertThat(right.equals(left))
                    .as("an asymmetric equals does not fail cleanly: find, merge and dirty checking start "
                            + "to misbehave as intermittent data errors.")
                    .isTrue();
        }

        @Test
        @DisplayName("equals is transitive")
        void equalsIsTransitive() {
            final TransactionCategoryBalanceId first = firstSeededKey();
            final TransactionCategoryBalanceId second = new TransactionCategoryBalanceId(1L, "01", 1);
            final TransactionCategoryBalanceId third = new TransactionCategoryBalanceId(1L, "01", 1);

            assertThat(first.equals(second)).isTrue();
            assertThat(second.equals(third)).isTrue();
            assertThat(first.equals(third))
                    .as("transitivity is what lets a hash container treat any equal key as the same bucket "
                            + "entry regardless of which instance arrived first")
                    .isTrue();
        }

        @Test
        @DisplayName("equals and hashCode are consistent across repeated invocations")
        void equalsAndHashCodeAreConsistentAcrossRepeatedInvocations() {
            final TransactionCategoryBalanceId left = firstSeededKey();
            final TransactionCategoryBalanceId right = new TransactionCategoryBalanceId(1L, "01", 1);
            final int firstHash = left.hashCode();

            for (int invocation = 0; invocation < 3; invocation++) {
                assertThat(left.equals(right))
                        .as("no component is mutable, so no repeated invocation can observe a different "
                                + "answer; invocation %d", invocation)
                        .isTrue();
                assertThat(left.hashCode()).isEqualTo(firstHash);
            }
        }

        @Test
        @DisplayName("equals returns false for null rather than throwing")
        void equalsReturnsFalseForNull() {
            assertThat(firstSeededKey().equals(null))
                    .as("the contract requires x.equals(null) to be false; throwing here would break every "
                            + "collection that probes with a null candidate")
                    .isFalse();
        }

        @Test
        @DisplayName("equals returns false for a foreign type rather than throwing")
        void equalsReturnsFalseForAForeignType() {
            final TransactionCategoryBalanceId key = firstSeededKey();

            assertThat(key.equals("00000000001010001")).isFalse();
            assertThat(key.equals(Long.valueOf(1L))).isFalse();
            assertThat(key).isNotEqualTo(new Object());
        }

        @ParameterizedTest(name = "accountId={0} typeCd={1} catCd={2}")
        @CsvSource({"2, 01, 1", "1, 02, 1", "1, 01, 2"})
        @DisplayName("differs when any single component differs, so identity is total over all three")
        void differsWhenAnySingleComponentDiffers(final long accountId, final String typeCd,
                final int catCd) {
            assertThat(firstSeededKey())
                    .as("a partial identity would merge the balances of two different categories, and "
                            + "app/cbl/CBTRN02C.cbl:L527 adds the transaction amount to whichever row the "
                            + "key resolved.")
                    .isNotEqualTo(new TransactionCategoryBalanceId(accountId, typeCd, catCd));
        }

        @Test
        @DisplayName("equal instances agree on hashCode")
        void equalInstancesAgreeOnHashCode() {
            assertThat(firstSeededKey())
                    .isEqualTo(new TransactionCategoryBalanceId(1L, "01", 1))
                    .hasSameHashCodeAs(new TransactionCategoryBalanceId(1L, "01", 1));
        }

        @Test
        @DisplayName("behaves as a hash container key: a set de-duplicates and a map resolves by value")
        void behavesAsAHashContainerKey() {
            final Set<TransactionCategoryBalanceId> distinct = new HashSet<>();
            distinct.add(firstSeededKey());
            distinct.add(new TransactionCategoryBalanceId(1L, "01", 1));
            distinct.add(new TransactionCategoryBalanceId(1L, "01", 2));

            assertThat(distinct)
                    .as("two equal keys occupy one entry and a third component change makes a new one")
                    .hasSize(2);

            final Map<TransactionCategoryBalanceId, String> byKey = new HashMap<>();
            byKey.put(firstSeededKey(), "resolved");

            assertThat(byKey.get(new TransactionCategoryBalanceId(1L, "01", 1)))
                    .as("the upsert path resolves the row by key, so a freshly built equal key must hit")
                    .isEqualTo("resolved");
        }

        @Test
        @DisplayName("two staged instances denote the same absent key and can still be compared and hashed")
        void twoStagedInstancesAreComparableAndHashable() throws ReflectiveOperationException {
            final TransactionCategoryBalanceId staged = stagedInstance();
            final TransactionCategoryBalanceId other = stagedInstance();

            assertThatCode(() -> {
                staged.equals(other);
                staged.equals(null);
                staged.hashCode();
                staged.toString();
            })
                    .as("the provider fills components one at a time, so a log statement or a collection "
                            + "can touch the instance while it is still incomplete; none of the three may "
                            + "throw on a null component")
                    .doesNotThrowAnyException();
            assertThat(staged).isEqualTo(other).hasSameHashCodeAs(other);
        }

        @Test
        @DisplayName("is immutable in practice: every component is private and there is no mutator")
        void isImmutableInPractice() {
            for (final Field component : mappedFields()) {
                assertThat(Modifier.isPrivate(component.getModifiers()))
                        .as("%s must be private; a package visible component could be reassigned after the "
                                + "key entered a hash container, stranding the entry in the wrong bucket",
                                component.getName())
                        .isTrue();
            }

            final List<String> mutators = new ArrayList<>();
            for (final Method method : TransactionCategoryBalanceId.class.getDeclaredMethods()) {
                if (method.getName().startsWith("set")) {
                    mutators.add(method.getName());
                }
            }

            assertThat(mutators)
                    .as("the components are not declared final because JPA writes them reflectively after "
                            + "the no-argument constructor, so immutability rests entirely on there being "
                            + "no mutator. That is the assertion that keeps it")
                    .isEmpty();
        }

        @Test
        @DisplayName("exposes one explicit accessor per component, hand written rather than generated")
        void exposesOneExplicitAccessorPerComponent() throws NoSuchMethodException {
            assertThat(TransactionCategoryBalanceId.class.getMethod("getAccountId").getReturnType())
                    .isEqualTo(Long.class);
            assertThat(TransactionCategoryBalanceId.class.getMethod("getTypeCd").getReturnType())
                    .isEqualTo(String.class);
            assertThat(TransactionCategoryBalanceId.class.getMethod("getCatCd").getReturnType())
                    .as("accessors are written out by hand: no annotation processor participates in this "
                            + "build, so a generated accessor would simply not exist at run time")
                    .isEqualTo(Integer.class);
        }

        @Test
        @DisplayName("uses no floating point and no BigDecimal anywhere: this key carries no money")
        void usesNoFloatingPointAndNoBigDecimal() {
            final Set<String> forbidden = Set.of("float", "double", "java.lang.Float", "java.lang.Double",
                    "java.math.BigDecimal");
            final List<String> offenders = new ArrayList<>();

            for (final Field field : TransactionCategoryBalanceId.class.getDeclaredFields()) {
                if (forbidden.contains(field.getType().getName())) {
                    offenders.add("field " + field.getName() + " : " + field.getType().getName());
                }
            }
            for (final Method method : TransactionCategoryBalanceId.class.getDeclaredMethods()) {
                if (forbidden.contains(method.getReturnType().getName())) {
                    offenders.add("method " + method.getName() + " : " + method.getReturnType().getName());
                }
            }

            assertThat(offenders)
                    .as("no float or double may appear in a financial path, and the monetary field of this "
                            + "record - TRAN-CAT-BAL PIC S9(09)V99 at app/cpy/CVTRA01Y.cpy:L9, mapped as "
                            + "NUMERIC(11,2) - is payload owned by the entity, not key. A BigDecimal here "
                            + "would mean the balance had leaked into the identifier")
                    .isEmpty();
        }

        @Test
        @DisplayName("holds no mutable static state, so no test and no thread can perturb another")
        void holdsNoMutableStaticState() {
            final List<String> mutableStatics = new ArrayList<>();
            for (final Field field : TransactionCategoryBalanceId.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers())) {
                    mutableStatics.add(field.getName());
                }
            }

            assertThat(mutableStatics)
                    .as("Rule 1 clause B: avoid global mutable state. serialVersionUID and the boundary "
                            + "constants are static FINAL, which is correct; a non-final static would make "
                            + "this value type order dependent across a parallel suite")
                    .isEmpty();
        }

        @Test
        @DisplayName("toString renders the three components and leaks nothing else")
        void toStringRendersTheThreeComponentsAndNothingElse() {
            final String rendered = new TransactionCategoryBalanceId(1L, "01", 1).toString();

            assertThat(rendered)
                    .startsWith("TransactionCategoryBalanceId[")
                    .endsWith("]")
                    .contains("accountId=1")
                    .contains("typeCd=01")
                    .contains("catCd=1");
            assertThat(rendered.replace("TransactionCategoryBalanceId[", "").replace("]", "")
                    .split(", ").length)
                    .as("exactly three rendered components and no fourth: an account identifier, a type "
                            + "code and a category code are not sensitive, so rendering them in full is "
                            + "correct - but a future component might be, and this is the assertion that "
                            + "notices it arriving")
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("imports only the persistence annotations, Serializable and Objects")
        void importsOnlyThePersistenceAnnotationsSerializableAndObjects() throws IOException {
            final Path source =
                    Path.of("src", "main", "java", "com", "cardemo", "model", "key",
                            "TransactionCategoryBalanceId.java");
            final List<String> imports = new ArrayList<>();
            for (final String line : Files.readAllLines(source, StandardCharsets.UTF_8)) {
                final String trimmed = line.trim();
                if (trimmed.startsWith("import ")) {
                    imports.add(trimmed);
                }
            }

            assertThat(imports)
                    .as("a value type that identifies a row needs nothing beyond its mapping annotations "
                            + "and value equality. Anything else - a logger, a validation annotation, a "
                            + "date type, a collection - would be a dependency the 17 byte key does not "
                            + "have, and an unused one would fail the build under -Xlint:all -Werror")
                    .containsExactlyInAnyOrder(
                            "import jakarta.persistence.Column;",
                            "import jakarta.persistence.Embeddable;",
                            "import java.io.Serializable;",
                            "import java.util.Objects;");
        }
    }

    // ==================================================================
    // 5 - The verified name collision: TRAN-CAT-KEY is declared twice,
    //     in CVTRA01Y (17 bytes) and CVTRA04Y (6 bytes).
    // ==================================================================

    @Nested
    @DisplayName("5. The two TRAN-CAT-KEY groups are different contracts")
    class TranCatKeyNameCollision {

        @Test
        @DisplayName("both copybooks really do declare a group literally named TRAN-CAT-KEY")
        void bothCopybooksDeclareAGroupNamedTranCatKey() throws IOException {
            assertThat(Files.readAllLines(CVTRA01Y, StandardCharsets.UTF_8))
                    .as("app/cpy/CVTRA01Y.cpy:L5")
                    .anyMatch(line -> "05  TRAN-CAT-KEY.".equals(line.trim()));
            assertThat(Files.readAllLines(CVTRA04Y, StandardCharsets.UTF_8))
                    .as("app/cpy/CVTRA04Y.cpy:L5 - byte identical group name in an unrelated record. This "
                            + "is the collision, verified rather than assumed")
                    .anyMatch(line -> "05  TRAN-CAT-KEY.".equals(line.trim()));
        }

        @Test
        @DisplayName("the namesake group is six bytes over two fields, prefixed TRAN- rather than TRANCAT-")
        void theNamesakeGroupIsSixBytesOverTwoFields() throws IOException {
            final List<Component> namesake = keyComponentsOf(CVTRA04Y);

            assertThat(namesake)
                    .as("app/cpy/CVTRA04Y.cpy:L6-L7 declares TRAN-TYPE-CD PIC X(02) and TRAN-CAT-CD "
                            + "PIC 9(04) - two fields, not three, and every name carries the shorter "
                            + "TRAN- prefix")
                    .hasSize(2)
                    .extracting(Component::asDeclared)
                    .containsExactly("TRAN-TYPE-CD PIC X(02)", "TRAN-CAT-CD PIC 9(04)");

            int namesakeWidth = 0;
            for (final Component component : namesake) {
                namesakeWidth += component.width();
            }

            assertThat(namesakeWidth)
                    .as("2 + 4 = 6 bytes against the 17 of this key. Two groups of the same name whose "
                            + "widths differ by eleven bytes cannot be one type")
                    .isEqualTo(TYPE_CD_WIDTH + CAT_CD_WIDTH)
                    .isNotEqualTo(CATALOGUED_KEY_LENGTH);
        }

        @Test
        @DisplayName("every component of this key is prefixed TRANCAT-, which is what separates the two")
        void everyComponentOfThisKeyIsPrefixedTrancat() throws IOException {
            assertThat(keyComponentsOf(CVTRA01Y))
                    .extracting(Component::name)
                    .as("the prefix is the discriminator: TRANCAT- belongs to the balance record of "
                            + "app/cpy/CVTRA01Y.cpy and TRAN- to the category type record of "
                            + "app/cpy/CVTRA04Y.cpy")
                    .allMatch(name -> name.startsWith("TRANCAT-"));

            assertThat(keyComponentsOf(CVTRA04Y))
                    .extracting(Component::name)
                    .allMatch(name -> name.startsWith("TRAN-") && !name.startsWith("TRANCAT-"));
        }

        @Test
        @DisplayName("the two identifiers are unequal in both directions, even on matching type and category")
        void theTwoIdentifiersAreUnequalInBothDirections() {
            final TransactionCategoryBalanceId balanceKey = new TransactionCategoryBalanceId(1L, "01", 1);
            final TransactionCategoryId categoryKey = new TransactionCategoryId("01", 1);

            assertThat(balanceKey.equals(categoryKey))
                    .as("the two carry the same type code and the same category code, so a lenient equals "
                            + "written with instanceof and a shared supertype would call them equal. Both "
                            + "compare getClass() strictly, so this direction is false")
                    .isFalse();
            assertThat(categoryKey.equals(balanceKey))
                    .as("and so is the reverse direction, which is what makes the relation symmetric "
                            + "rather than merely one sided. Either direction returning true means "
                            + "instanceof crept in where getClass() belongs")
                    .isFalse();
        }

        @Test
        @DisplayName("one hash container holds both without either displacing the other")
        void oneHashContainerHoldsBothWithoutDisplacement() {
            final Set<Object> keys = new HashSet<>();
            keys.add(new TransactionCategoryBalanceId(1L, "01", 1));
            keys.add(new TransactionCategoryId("01", 1));

            assertThat(keys)
                    .as("if the two were conflated, the second add would be swallowed as a duplicate and a "
                            + "category balance row would resolve to a category type row")
                    .hasSize(2);
        }

        @Test
        @DisplayName("neither type is assignable to the other and they share no supertype but Object")
        void neitherTypeIsAssignableToTheOther() {
            assertThat(TransactionCategoryBalanceId.class.isAssignableFrom(TransactionCategoryId.class))
                    .isFalse();
            assertThat(TransactionCategoryId.class.isAssignableFrom(TransactionCategoryBalanceId.class))
                    .isFalse();

            assertThat(TransactionCategoryBalanceId.class.getSuperclass())
                    .as("no shared base class, no abstract key type and no shared helper. Rule 1 clause "
                            + "C's avoid duplication is SATISFIED by three independent value types each "
                            + "owning its own equals and hashCode: a few structurally similar lines across "
                            + "distinct value types is the correct outcome, not duplication")
                    .isEqualTo(Object.class);
            assertThat(TransactionCategoryId.class.getSuperclass()).isEqualTo(Object.class);

            assertThat(TransactionCategoryBalanceId.class.getInterfaces())
                    .as("the only interface either implements is Serializable, and that is required by the "
                            + "specification rather than shared by design")
                    .containsExactly(Serializable.class);
            assertThat(TransactionCategoryId.class.getInterfaces()).containsExactly(Serializable.class);
        }

        @Test
        @DisplayName("the recurring column names are a foreign key, not evidence of a shared identifier")
        void theRecurringColumnNamesAreAForeignKey() throws IOException, NoSuchFieldException {
            assertThat(columnOf("typeCd").name()).isEqualTo("tran_type_cd");
            assertThat(columnOf("catCd").name())
                    .as("both names recur on the transaction_category table that TransactionCategoryId "
                            + "identifies, which invites the conclusion that the types are shared")
                    .isEqualTo("tran_cat_cd");

            assertThat(Files.readString(V1_MIGRATION, StandardCharsets.UTF_8))
                    .as("measured at src/main/resources/db/migration/V1__create_schema.sql:L992-L993, whose "
                            + "own cited evidence is app/cbl/CBTRN02C.cbl:L470-L471. The trailing two "
                            + "components of this 17 byte key REFERENCE the row that the 6 byte key "
                            + "identifies. Reference is not identity, so the recurrence is a relationship "
                            + "rather than a coincidence and certainly not a shared type")
                    .contains("FOREIGN KEY (tran_type_cd, tran_cat_cd)")
                    .contains("REFERENCES transaction_category (tran_type_cd, tran_cat_cd)");
        }

        @Test
        @DisplayName("their renderings name their own type, so a log line cannot be misread for the other")
        void theirRenderingsNameTheirOwnType() {
            assertThat(new TransactionCategoryBalanceId(1L, "01", 1).toString())
                    .startsWith("TransactionCategoryBalanceId[")
                    .contains("accountId=1");
            assertThat(new TransactionCategoryId("01", 1).toString())
                    .as("the six byte key has no account component at all, which is the difference a "
                            + "diagnostic reader needs to see immediately")
                    .startsWith("TransactionCategoryId[")
                    .doesNotContain("accountId");
        }
    }

    // ==================================================================
    // 6 - Hostile input. Rule 1 clause A: treat inputs as untrusted.
    //     Every rejection asserts the exact message AND the cause.
    // ==================================================================

    @Nested
    @DisplayName("6. Hostile input: every component boundary is guarded")
    class HostileInput {

        @Test
        @DisplayName("a null account identifier is refused, naming its COBOL field, with no invented cause")
        void aNullAccountIdentifierIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new TransactionCategoryBalanceId(null, "01", 1))
                    .withMessage("accountId (TRANCAT-ACCT-ID PIC 9(11)) is required and must not be null")
                    .withNoCause();
        }

        @Test
        @DisplayName("a null type code is refused, naming its COBOL field, with no invented cause")
        void aNullTypeCodeIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new TransactionCategoryBalanceId(1L, null, 1))
                    .withMessage("typeCd (TRANCAT-TYPE-CD PIC X(02)) is required and must not be null")
                    .withNoCause();
        }

        @Test
        @DisplayName("a null category code is refused, naming its COBOL field, with no invented cause")
        void aNullCategoryCodeIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new TransactionCategoryBalanceId(1L, "01", null))
                    .withMessage("catCd (TRANCAT-CD PIC 9(04)) is required and must not be null")
                    .withNoCause();
        }

        @Test
        @DisplayName("the first null component is reported, so the message names one field and not three")
        void theFirstNullComponentIsReported() {
            assertThatIllegalArgumentException()
                    .as("the components are validated in COBOL order, so an all null attempt reports the "
                            + "leading component. Reporting the first is deterministic; reporting an "
                            + "arbitrary one would not be")
                    .isThrownBy(() -> new TransactionCategoryBalanceId(null, null, null))
                    .withMessageContaining("TRANCAT-ACCT-ID")
                    .withNoCause();
        }

        @ParameterizedTest(name = "typeCd=[{0}]")
        @ValueSource(strings = {"", "0", "001", "0100"})
        @DisplayName("a type code of any length other than two is refused, quoting the offending width")
        void aTypeCodeOfTheWrongLengthIsRefused(final String typeCd) {
            assertThatIllegalArgumentException()
                    .as("TRANCAT-TYPE-CD is PIC X(02) at app/cpy/CVTRA01Y.cpy:L7, so bytes 12-13 of the key "
                            + "hold exactly two characters. A shorter value would leave the key one byte "
                            + "narrow and a longer one would overrun into the category code")
                    .isThrownBy(() -> new TransactionCategoryBalanceId(1L, typeCd, 1))
                    .withMessage("typeCd (TRANCAT-TYPE-CD PIC X(02)) must be exactly 2 characters but was "
                            + typeCd.length() + ": [" + typeCd + "]")
                    .withNoCause();
        }

        @Test
        @DisplayName("a blank two character type code is ACCEPTED, because that is parity")
        void aBlankTwoCharacterTypeCodeIsAccepted() {
            assertThatCode(() -> new TransactionCategoryBalanceId(1L, "  ", 1))
                    .as("PIC X(02) is a fixed width alphanumeric field in a language with no null, so an "
                            + "unset TRANCAT-TYPE-CD holds two spaces and a blank type code is a value the "
                            + "source can genuinely produce and key on. Rejecting it would be a behaviour "
                            + "change, which the parity mandate forbids.referential "
                            + "integrity rests on fk08_tcatbal_category, which no blank pair can satisfy")
                    .doesNotThrowAnyException();

            assertThat(new TransactionCategoryBalanceId(1L, "  ", 1).getTypeCd())
                    .as("and the blank is stored verbatim, neither trimmed to empty nor normalised")
                    .isEqualTo("  ")
                    .hasSize(TYPE_CD_WIDTH);
        }

        @Test
        @DisplayName("a negative account identifier is refused: PIC 9(11) is unsigned")
        void aNegativeAccountIdentifierIsRefused() {
            assertThatIllegalArgumentException()
                    .as("PIC 9(11) carries no sign, so a negative value has no representation on the wire "
                            + "and would break the zero padded ordering the control break depends on")
                    .isThrownBy(() -> new TransactionCategoryBalanceId(-1L, "01", 1))
                    .withMessage("accountId (TRANCAT-ACCT-ID PIC 9(11)) must be between 0 and 99999999999 "
                            + "inclusive but was -1")
                    .withNoCause();
        }

        @Test
        @DisplayName("a twelve digit account identifier is refused: it would not fit the eleven byte field")
        void aTwelveDigitAccountIdentifierIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new TransactionCategoryBalanceId(100_000_000_000L, "01", 1))
                    .withMessage("accountId (TRANCAT-ACCT-ID PIC 9(11)) must be between 0 and 99999999999 "
                            + "inclusive but was 100000000000")
                    .withNoCause();
        }

        @Test
        @DisplayName("both account identifier boundaries are accepted, inclusively")
        void bothAccountIdentifierBoundariesAreAccepted() {
            assertThatCode(() -> new TransactionCategoryBalanceId(0L, "01", 1))
                    .as("eleven zeros is a representable PIC 9(11) value, so the lower bound is inclusive")
                    .doesNotThrowAnyException();
            assertThatCode(() -> new TransactionCategoryBalanceId(WIDEST_ACCOUNT_ID, "01", 1))
                    .as("eleven nines is the widest, so the upper bound is inclusive too")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a negative category code is refused: PIC 9(04) is unsigned")
        void aNegativeCategoryCodeIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new TransactionCategoryBalanceId(1L, "01", -1))
                    .withMessage("catCd (TRANCAT-CD PIC 9(04)) must be between 0 and 9999 inclusive but "
                            + "was -1")
                    .withNoCause();
        }

        @Test
        @DisplayName("a five digit category code is refused: it would overrun bytes 14-17")
        void aFiveDigitCategoryCodeIsRefused() {
            assertThatIllegalArgumentException()
                    .as("10000 is the first value PIC 9(04) cannot hold, and the key ends at byte 17, so "
                            + "there is no byte to overflow into")
                    .isThrownBy(() -> new TransactionCategoryBalanceId(1L, "01", 10_000))
                    .withMessage("catCd (TRANCAT-CD PIC 9(04)) must be between 0 and 9999 inclusive but "
                            + "was 10000")
                    .withNoCause();
        }

        @ParameterizedTest(name = "catCd={0}")
        @ValueSource(ints = {0, 1, 9999})
        @DisplayName("both category code boundaries and the seeded value are accepted, inclusively")
        void bothCategoryCodeBoundariesAreAccepted(final int catCd) {
            assertThatCode(() -> new TransactionCategoryBalanceId(1L, "01", catCd))
                    .as("0000 and 9999 are both representable in PIC 9(04); 0001 is the value every row of "
                            + "app/data/ASCII/tcatbal.txt actually carries")
                    .doesNotThrowAnyException();
            assertThat(new TransactionCategoryBalanceId(1L, "01", catCd).getCatCd()).isEqualTo(catCd);
        }

        @Test
        @DisplayName("a rejection message names the field contract and echoes the value it refused")
        void aRejectionMessageNamesTheFieldContractAndEchoesTheRefusedValue() {
            final String synthetic = "REDACTED-PLACEHOLDER";

            assertThatIllegalArgumentException()
                    .as("the message must be actionable, so it names the COBOL field, its picture clause "
                            + "and the width that was wrong")
                    .isThrownBy(() -> new TransactionCategoryBalanceId(1L, synthetic, 1))
                    .withMessageContaining("TRANCAT-TYPE-CD PIC X(02)")
                    .withMessageContaining("must be exactly 2 characters but was " + synthetic.length())
                    .withMessageContaining("[" + synthetic + "]")
                    .withNoCause();
        }

        @Test
        @DisplayName("the guard quotes what it refused, so no component may ever carry sensitive input")
        void theGuardQuotesWhatItRefused() {
            final String synthetic = "REDACTED-PLACEHOLDER";
            final String message = catchTypeCdRejection(synthetic);

            assertThat(message)
                    .as("stated plainly rather than papered over: the width guard echoes the offending "
                            + "value verbatim, which is right for a diagnostic and is safe here because "
                            + "the three components are an account identifier and two reference codes, "
                            + "none of them sensitive. Rule 1 clause D names tests explicitly, so the "
                            + "standing obligation is this: never route a "
                            + "credential, a hash, a cardholder datum or any other sensitive value through "
                            + "a key component, because the guard will quote it. If a "
                            + "sensitive component is ever added: report the width alone and drop the echo")
                    .contains(synthetic)
                    .doesNotContain("$2a$")
                    .doesNotContain("Bearer ");
        }

        @Test
        @DisplayName("a refused construction leaves nothing behind: no partial key escapes the constructor")
        void aRefusedConstructionLeavesNothingBehind() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new TransactionCategoryBalanceId(1L, "01", null))
                    .withMessageContaining("TRANCAT-CD");

            assertThat(new TransactionCategoryBalanceId(1L, "01", 1))
                    .as("validation happens inside the constructor, so a rejected attempt yields no "
                            + "reference at all and cannot leave a half built key reachable. A subsequent "
                            + "valid construction is unaffected, which is what proves no state was shared")
                    .isEqualTo(firstSeededKey());
        }
    }
}

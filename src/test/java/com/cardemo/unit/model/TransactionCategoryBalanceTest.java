/*
 * ******************************************************************
 * Program     : TransactionCategoryBalanceTest.java
 * Component   : Unit test tier, resident at
 *               src/test/java/com/cardemo/unit/model
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM tier, no container, no
 *               Spring context, no database, no live endpoint
 * Function    : Proves that com.cardemo.model.entity.
 *               TransactionCategoryBalance reproduces the
 *               TRAN-CAT-BAL-RECORD contract of the batch-only
 *               TCATBALF cluster: a 17-byte composite key owned
 *               wholly by its @EmbeddedId, exactly one non-key
 *               column at NUMERIC(11,2), an unmodelled FILLER X(22),
 *               no @Version, no lifecycle callback, no default
 *               balance, and free constructibility so the accepted
 *               "record not found" create path of 2700-UPDATE-TCATBAL
 *               remains expressible in one step.
 * Source      : app/cpy/CVTRA01Y.cpy:L5-L10 (50 B, composite key 17)
 *                                                        @ 7756d89
 * Source      : app/cpy/CVTRA04Y.cpy:L5 (TRAN-CAT-KEY group-name
 *               collision, 6 B / 2 fields)              @ 7756d89
 * Source      : app/catlg/LISTCAT.txt:L1371 (KEYLEN 17, AVGLRECL 50)
 *               and :L3938-L3946 (AIX 3 / PATH 3)       @ 7756d89
 * Source      : app/cbl/CBTRN02C.cbl:L467-L500, :L474-L479, :L481,
 *               :L512, :L530 (the upsert)               @ 7756d89
 * Source      : app/cbl/CBACT04C.cbl:L188-L222 (account control
 *               break over a single ascending scan)     @ 7756d89
 * Source      : app/csd/CARDDEMO.CSD (eight CICS files; TCATBALF is
 *               not one of them)                        @ 7756d89
 * Source      : app/data/ASCII/tcatbal.txt (2550 B / 50 rows / 50 B)
 *                                                        @ 7756d89
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
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.cardemo.model.entity.TransactionCategoryBalance;
import com.cardemo.model.key.TransactionCategoryBalanceId;
import com.cardemo.model.key.TransactionCategoryId;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.IdClass;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit test for the {@link TransactionCategoryBalance} entity, which replaces the batch-only VSAM KSDS
 * cluster {@code AWS.M2.CARDDEMO.TCATBALF.VSAM.KSDS}.
 *
 * <h2>1. What this test does</h2>
 *
 * <p>{@code app/cpy/CVTRA01Y.cpy:L5-L10} declares a 50-byte record whose header comment at {@code :L2}
 * reads {@code Data-structure for transaction category balance (RECLN = 50)}:
 *
 * <pre>{@code
 * 01  TRAN-CAT-BAL-RECORD.
 *     05  TRAN-CAT-KEY.                          17 bytes, the composite key
 *        10 TRANCAT-ACCT-ID   PIC 9(11).         bytes  1-11
 *        10 TRANCAT-TYPE-CD   PIC X(02).         bytes 12-13
 *        10 TRANCAT-CD        PIC 9(04).         bytes 14-17
 *     05  TRAN-CAT-BAL        PIC S9(09)V99.     bytes 18-28, the only payload
 *     05  FILLER              PIC X(22).         bytes 29-50, not modelled
 * }</pre>
 *
 * <p>{@code 17 + 11 + 22 = 50}, corroborated by {@code app/catlg/LISTCAT.txt:L1371}, which reads
 * {@code KEYLEN 17} with {@code AVGLRECL 50} for this cluster, and by {@code :L1372}, which reads
 * {@code RKP 0} and {@code MAXLRECL 50} - so the key is the record prefix and the record is fixed width
 * rather than merely 50 bytes on average.
 *
 * <p>Eight concerns are proven, one per nested class, and they are the eight where a plausible
 * implementation diverges from the source:
 *
 * <ol>
 *   <li><b>Record geometry and table mapping.</b> The 50-byte record, the 17-byte key, the unmodelled
 *       {@code FILLER X(22)}, the absence of {@code @Version} - this is not one of the four versioned
 *       entities - and the preserved source spelling {@code TRANCAT-CD}, which is <em>not</em>
 *       {@code TRANCAT-CAT-CD}.</li>
 *   <li><b>Composite key ownership.</b> Every key column is declared once, on the key class, and the
 *       entity adds no {@code @AttributeOverride}, no duplicate {@code @Column} and no scalar key field.
 *       This is the Blocker: duplicating a key column either aborts Hibernate bootstrap or, worse,
 *       silently maps the column twice.</li>
 *   <li><b>Precision.</b> {@code NUMERIC(11,2)} and never {@code NUMERIC(12,2)}, with no binary floating
 *       point anywhere and value comparison by {@code compareTo}.</li>
 *   <li><b>Free constructibility.</b> The accepted "record not found" control path must be able to build a
 *       row for a key that does not yet exist, in one step, with no factory, no builder and no invented
 *       default balance.</li>
 *   <li><b>Seed fixture contract.</b> The measured bytes of {@code app/data/ASCII/tcatbal.txt}, including
 *       the eleven-character balance field that independently confirms the precision tier, and the
 *       position-aware overpunch decode.</li>
 *   <li><b>Batch-only dataset.</b> No online path, no alternate index, no association.</li>
 *   <li><b>Scope and diagnostics.</b> The entity's dependency surface and a {@code toString} that cannot
 *       leak.</li>
 *   <li><b>Hostile input and boundaries.</b> Null, blank, short, long, off-by-one and out-of-range input
 *       on both the key and the balance.</li>
 * </ol>
 *
 * <p>What this class deliberately does <em>not</em> re-test: the internals of
 * {@link TransactionCategoryBalanceId}, which {@code TransactionCategoryBalanceIdTest} in this package
 * owns, and the cross-entity conventions that {@code EntityContractTest} and {@code SchemaStructureTest}
 * own. The gap this class closes is the one the entity's own class documentation records as outstanding: a
 * focused test of this row's column contract.
 *
 * <p><b>Evidence boundary, stated rather than left implicit.</b> Every assertion here derives from one of
 * three things: the frozen artefacts cited above, the measured bytes of the seed fixture, or the loaded
 * bytecode of the type under test. Nothing is asserted about generated SQL. Schema facts beyond the copybook
 * and the catalogue listing are <b>Not available</b> to a pure-JVM unit test and none is invented here; what
 * would be needed to assert them is a migrated database, which is the integration tier's evidence and
 * {@code SchemaStructureTest}'s subject rather than this class's. Where the fixture itself cannot decide a
 * question, that limitation is asserted explicitly instead of being papered over - see the ten-character
 * slice test, which records that this all-zero fixture cannot detect a short field width.
 *
 * <h2>2. How to run, build and test</h2>
 *
 * <p>This class lives under {@code src/test/java/com/cardemo/unit/model}, so <b>Surefire</b> collects it -
 * its {@code <includes>} matches {@code **}{@code /*Test.java} and its {@code <excludes>} removes only
 * {@code integration} and {@code e2e}. Failsafe never sees it. A class placed outside that tree is
 * collected by neither plugin and silently never runs, which is why this file must not be renamed or
 * relocated.
 *
 * <pre>
 * ./mvnw -B -ntp test -Dtest=TransactionCategoryBalanceTest   # this class alone
 * ./mvnw -B -ntp test                                         # the whole unit tier
 * ./mvnw -B -ntp -Ddependency-check.skip=true verify          # adds the JaCoCo line floor
 * </pre>
 *
 * <p>Nothing else is required: there is no container to start, no schema to migrate and no credential to
 * supply, because every assertion here is a pure function of loaded bytecode and one classpath resource.
 *
 * <h2>3. Key configuration and defaults</h2>
 *
 * <p>The fixture is reached through {@link FixtureLoader} by classpath resource name only, never by
 * filesystem path, so {@code app/} is read by nothing here and cannot be written by anything here. The
 * loader proves each fixture against its own declared census on every load, so a reflowed, trimmed,
 * CRLF-converted or truncated fixture fails at load rather than silently weakening an assertion.
 *
 * <p>Two defaults that other classes in this tier rely on are deliberately unused, and saying so is
 * cheaper than leaving a reader to wonder. No clock is injected, because
 * {@link TransactionCategoryBalance} has no temporal member at all; were one ever added, the fixed clock in
 * {@code FixedClockProvider} would supply it and {@code Instant.now()} would still be forbidden. No
 * Mockito mock is created, because the entity has no collaborator to stand in for - a stub here would be
 * unused, and Mockito's strict stubs setting rightly fails a test that creates one. Nothing in this class
 * consults a wall clock, a locale, a time zone, a random source or an iteration order, so every run is
 * identical on every machine.
 *
 * <h2>4. Common failure modes and troubleshooting</h2>
 *
 * <dl>
 *   <dt>The build fails at test compilation rather than at test execution</dt>
 *   <dd>{@code -Xlint:all -Werror} with {@code failOnWarning} reaches test sources. A raw type, a lossy
 *       conversion or a deprecated call is a hard failure, not a warning. An unused import is forbidden by
 *       review under the same standard. Fix the source rather than relaxing the flag.</dd>
 *   <dt>A fixture assertion fails with "no such resource"</dt>
 *   <dd>The daily-transaction fixture is {@code dailytran.txt} and never {@code dalytran.txt}: the
 *       mainframe DD name is {@code DALYTRAN} but the ASCII fixture spells "daily" in full. This class
 *       asserts that spelling so the trap is caught here rather than in a batch test.</dd>
 *   <dt>Hibernate reports a duplicated or repeated column</dt>
 *   <dd>An {@code @AttributeOverride} or a second {@code @Column} for a key component was added to the
 *       entity. The key class is the single owner of all three key columns. Severity Blocker; remediation
 *       is to delete the override, not to rename the column.</dd>
 *   <dt>The create branch of the posting job overwrites an opening balance</dt>
 *   <dd>A field initialiser or a {@code @PrePersist} callback was added and fabricated a zero. The source
 *       supplies the opening value itself, at {@code app/cbl/CBTRN02C.cbl:L508}. Severity High; the
 *       remediation is to remove the default, and this class fails if one reappears.</dd>
 *   <dt>Money assertions fail by a hair</dt>
 *   <dd>{@code BigDecimal.equals} compares scale as well as value, so {@code 0} and {@code 0.00} are
 *       unequal while comparing equal. Use {@code compareTo}, as every assertion here does.</dd>
 * </dl>
 *
 * @see TransactionCategoryBalance
 * @see TransactionCategoryBalanceId
 * @see FixtureLoader
 */
class TransactionCategoryBalanceTest {

    /** Mapped table name, fixed by the entity and by {@code V1__create_schema.sql} alike. */
    private static final String TABLE_NAME = "transaction_category_balance";

    /** Column of the only non-key field, {@code TRAN-CAT-BAL} at {@code app/cpy/CVTRA01Y.cpy:L9}. */
    private static final String BALANCE_COLUMN = "tran_cat_bal";

    /** Width of {@code TRANCAT-ACCT-ID PIC 9(11)}, bytes 1-11 of the key. */
    private static final int ACCT_ID_WIDTH = 11;

    /** Width of {@code TRANCAT-TYPE-CD PIC X(02)}, bytes 12-13 of the key. */
    private static final int TYPE_CD_WIDTH = 2;

    /** Width of {@code TRANCAT-CD PIC 9(04)}, bytes 14-17 of the key. */
    private static final int CAT_CD_WIDTH = 4;

    /** Catalogued composite key length, {@code KEYLEN 17} at {@code app/catlg/LISTCAT.txt:L1371}. */
    private static final int KEY_LENGTH = 17;

    /** Width of {@code TRAN-CAT-BAL PIC S9(09)V99}: nine integer digits, two decimals, one sign overpunch. */
    private static final int BALANCE_WIDTH = 11;

    /** Width of the unmodelled {@code FILLER PIC X(22)} at {@code app/cpy/CVTRA01Y.cpy:L10}. */
    private static final int FILLER_WIDTH = 22;

    /** Catalogued record length, {@code AVGLRECL 50} and {@code MAXLRECL 50}. */
    private static final int RECORD_LENGTH = 50;

    /** Mapped column precision: {@code S9(09)V99} is nine plus two, so eleven. Never twelve. */
    private static final int BALANCE_PRECISION = 11;

    /** Mapped column scale: the two digits after the implied {@code V}. */
    private static final int BALANCE_SCALE = 2;

    /** One-based first column of {@code TRANCAT-TYPE-CD} within a fixture record. */
    private static final int TYPE_CD_COLUMN = 12;

    /** One-based first column of {@code TRANCAT-CD} within a fixture record. */
    private static final int CAT_CD_COLUMN = 14;

    /** One-based first column of {@code TRAN-CAT-BAL} within a fixture record. */
    private static final int BALANCE_COLUMN_START = 18;

    /** One-based first column of the unmodelled {@code FILLER} within a fixture record. */
    private static final int FILLER_COLUMN_START = 29;

    /** Largest value nine signed integer digits and two decimals can hold. */
    private static final String MAX_BALANCE = "999999999.99";

    /** Smallest value nine signed integer digits and two decimals can hold. */
    private static final String MIN_BALANCE = "-999999999.99";

    /** Every zoned-decimal overpunch meaning a positive low-order digit, {@code +0} through {@code +9}. */
    private static final String POSITIVE_OVERPUNCH = "{ABCDEFGHI";

    /** Every zoned-decimal overpunch meaning a negative low-order digit, {@code -0} through {@code -9}. */
    private static final String NEGATIVE_OVERPUNCH = "}JKLMNOPQR";

    /**
     * Returns a fully populated key for the first seeded row, {@code (1, "01", 1)}.
     *
     * <p>A factory rather than a field: an instance handed to several assertions from a mutable static
     * field would be shared state, and this tier keeps none.
     *
     * @return a fresh key, never {@code null}
     */
    private static TransactionCategoryBalanceId firstSeededKey() {
        return new TransactionCategoryBalanceId(1L, "01", 1);
    }

    /**
     * Returns a row carrying {@link #firstSeededKey()} and the given balance.
     *
     * @param balance the balance as an exact decimal literal, for example {@code "-1234.56"}
     * @return a fresh row, never {@code null}
     */
    private static TransactionCategoryBalance rowWithBalance(final String balance) {
        return new TransactionCategoryBalance(firstSeededKey(), new BigDecimal(balance));
    }

    /**
     * Returns the annotation type names declared anywhere on a class: on the type itself, on every declared
     * field, on every declared constructor and method, and on their parameters.
     *
     * <p>Sorted, so the assertion message is identical on every run and no iteration order leaks into a
     * result. Collecting the whole census and asserting it exactly is stronger than checking a list of
     * forbidden annotations one by one, because it also fails for a forbidden annotation nobody thought to
     * name.
     *
     * @param type the class to census
     * @return the simple names of every annotation found, in sorted order, never {@code null}
     */
    private static Set<String> annotationNames(final Class<?> type) {
        final Set<String> names = new TreeSet<>();
        for (final Annotation annotation : type.getDeclaredAnnotations()) {
            names.add(annotation.annotationType().getSimpleName());
        }
        for (final Field field : type.getDeclaredFields()) {
            for (final Annotation annotation : field.getDeclaredAnnotations()) {
                names.add(annotation.annotationType().getSimpleName());
            }
        }
        final List<Executable> executables = new ArrayList<>();
        executables.addAll(List.of(type.getDeclaredConstructors()));
        executables.addAll(List.of(type.getDeclaredMethods()));
        for (final Executable executable : executables) {
            for (final Annotation annotation : executable.getDeclaredAnnotations()) {
                names.add(annotation.annotationType().getSimpleName());
            }
            for (final Annotation[] parameter : executable.getParameterAnnotations()) {
                for (final Annotation annotation : parameter) {
                    names.add(annotation.annotationType().getSimpleName());
                }
            }
        }
        return names;
    }

    /**
     * Returns every type a class names in its own declared members: field types, constructor and method
     * parameter types, return types, and annotation types.
     *
     * <p>This is the entity's dependency surface as the bytecode states it, which is what a scope
     * assertion needs; an import list is only a proxy for it and can be satisfied by a
     * fully-qualified reference.
     *
     * @param type the class to census
     * @return the fully qualified names of every referenced type, sorted, never {@code null}
     */
    private static Set<String> referencedTypeNames(final Class<?> type) {
        final Set<String> names = new TreeSet<>();
        for (final Field field : type.getDeclaredFields()) {
            names.add(field.getType().getName());
        }
        for (final Constructor<?> constructor : type.getDeclaredConstructors()) {
            for (final Class<?> parameter : constructor.getParameterTypes()) {
                names.add(parameter.getName());
            }
        }
        for (final Method method : type.getDeclaredMethods()) {
            names.add(method.getReturnType().getName());
            for (final Class<?> parameter : method.getParameterTypes()) {
                names.add(parameter.getName());
            }
        }
        for (final Annotation annotation : type.getDeclaredAnnotations()) {
            names.add(annotation.annotationType().getName());
        }
        return names;
    }

    /**
     * Returns the fixture snapshot of {@code app/data/ASCII/tcatbal.txt}, census-checked by the loader.
     *
     * @return an immutable snapshot of all 50 records, never {@code null}
     */
    private static FixtureLoader.FixtureData seedFixture() {
        return FixtureLoader.load(FixtureLoader.Fixture.TRANSACTION_CATEGORY_BALANCE);
    }

    /**
     * Record geometry and table mapping: the 50-byte record, the 17-byte key, the unmodelled filler, and the
     * two things that must be absent - a version column and a "corrected" field name.
     */
    @Nested
    @DisplayName("1. Record geometry: 50 bytes, a 17-byte composite key and one payload column")
    class RecordGeometryAndTableMapping {

        @Test
        @DisplayName("is an @Entity mapped to transaction_category_balance")
        void isAnEntityMappedToItsTable() {
            assertThat(TransactionCategoryBalance.class.isAnnotationPresent(Entity.class))
                    .as("the row must be a managed entity: app/cbl/CBTRN02C.cbl:L474 reads it and "
                            + "app/cbl/CBACT04C.cbl:L190 browses it, so it is persistent state, not a DTO")
                    .isTrue();
            assertThat(TransactionCategoryBalance.class.getAnnotation(Table.class).name())
                    .as("the table name is fixed by V1__create_schema.sql; under ddl-auto=validate a "
                            + "mismatch of one character aborts application context startup")
                    .isEqualTo(TABLE_NAME);
        }

        @Test
        @DisplayName("the three key components and the balance and the filler sum to the catalogued 50 bytes")
        void componentWidthsSumToTheCataloguedRecordLength() {
            assertThat(ACCT_ID_WIDTH + TYPE_CD_WIDTH + CAT_CD_WIDTH)
                    .as("TRANCAT-ACCT-ID 9(11) + TRANCAT-TYPE-CD X(02) + TRANCAT-CD 9(04) is the composite "
                            + "key length KEYLEN 17 catalogued at app/catlg/LISTCAT.txt:L1371")
                    .isEqualTo(KEY_LENGTH);
            assertThat(KEY_LENGTH + BALANCE_WIDTH + FILLER_WIDTH)
                    .as("17 key bytes + 11 balance bytes + 22 filler bytes is the AVGLRECL 50 and "
                            + "MAXLRECL 50 of app/catlg/LISTCAT.txt:L1371-L1372, and the RECLN = 50 of "
                            + "the copybook header comment at app/cpy/CVTRA01Y.cpy:L2")
                    .isEqualTo(RECORD_LENGTH);
        }

        @Test
        @DisplayName("the catalogued record length is the width the frozen fixture actually measures")
        void theCataloguedRecordLengthMatchesTheFrozenFixture() {
            assertThat(FixtureLoader.Fixture.TRANSACTION_CATEGORY_BALANCE.recordWidth())
                    .as("the arithmetic above would be self-referential if nothing outside this file "
                            + "confirmed it; app/data/ASCII/tcatbal.txt measures 50 characters per record")
                    .isEqualTo(RECORD_LENGTH);
            assertThat(FixtureLoader.Fixture.TRANSACTION_CATEGORY_BALANCE.resourceName())
                    .as("the fixture is reached by classpath resource name only, so app/ is never opened "
                            + "as a file and can never be written by this tier")
                    .isEqualTo("tcatbal.txt");
        }

        @Test
        @DisplayName("declares exactly two instance fields: the composite key and the balance")
        void declaresExactlyTwoInstanceFields() {
            final Set<String> instanceFields = new TreeSet<>();
            for (final Field field : TransactionCategoryBalance.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    instanceFields.add(field.getName());
                }
            }
            assertThat(instanceFields)
                    .as("the record has exactly one key and one payload field. A third instance field "
                            + "would either duplicate a key component - the Blocker of nested class 2 - or "
                            + "model FILLER X(22), which carries no data and exists only as padding")
                    .containsExactly("balance", "id");
        }

        @Test
        @DisplayName("FILLER X(22) is not modelled, so no field or column corresponds to it")
        void fillerIsNotModelled() {
            final Set<String> columnNames = new TreeSet<>();
            for (final Field field : TransactionCategoryBalance.class.getDeclaredFields()) {
                if (field.isAnnotationPresent(Column.class)) {
                    columnNames.add(field.getAnnotation(Column.class).name());
                }
            }
            assertThat(columnNames)
                    .as("app/cpy/CVTRA01Y.cpy:L10 pads the record to its catalogued 50 bytes and holds no "
                            + "value; a relational row has no such requirement. Exactly one non-key column "
                            + "is declared on the entity and it is the balance")
                    .containsExactly(BALANCE_COLUMN);
        }

        @Test
        @DisplayName("carries NO @Version: this is not one of the four versioned entities")
        void carriesNoVersionColumn() {
            for (final Field field : TransactionCategoryBalance.class.getDeclaredFields()) {
                assertThat(field.isAnnotationPresent(Version.class))
                        .as("field '%s' must not be a version column. Optimistic locking belongs to "
                                + "Account, Card, Customer and Transaction only - the four rows an online "
                                + "screen updates after showing them. TCATBALF has no CICS file definition "
                                + "in app/csd/CARDDEMO.CSD at all, so no screen ever holds it across a "
                                + "conversation and there is no lost update to detect. Severity High: a "
                                + "version column here would add a column the migration does not declare "
                                + "and would make every batch upsert fail on a stale version instead",
                                field.getName())
                        .isFalse();
            }
        }

        @Test
        @DisplayName("declares exactly the annotations it needs and no others, so nothing forbidden can hide")
        void declaresExactlyTheAnnotationsItNeeds() {
            assertThat(annotationNames(TransactionCategoryBalance.class))
                    .as("an exact census fails for any annotation added later, including one nobody "
                            + "thought to forbid by name: @Version, @IdClass, @MapsId, @AttributeOverride, "
                            + "any of the seven lifecycle callbacks, any association, and any bean "
                            + "validation constraint are all excluded by this single assertion. The two "
                            + "Jackson annotations are the deliberate serialisation barrier: this row has "
                            + "no outbound representation and must not acquire one by accident")
                    .containsExactly("Column", "EmbeddedId", "Entity", "JsonAutoDetect", "JsonIgnoreType",
                            "Table");
        }

        @Test
        @DisplayName("preserves the source spelling TRANCAT-CD in its diagnostics, never TRANCAT-CAT-CD")
        void preservesTheSourceSpellingOfTheThirdKeyComponent() {
            assertThatIllegalArgumentException()
                    .as("app/cpy/CVTRA01Y.cpy:L8 spells the third component TRANCAT-CD. The sibling "
                            + "copybook app/cpy/CVTRA04Y.cpy:L7 spells its analogous field TRAN-CAT-CD in "
                            + "full, which makes 'TRANCAT-CAT-CD' the tempting tidy-up. The copybook is "
                            + "the field contract of record and the corpus is frozen, so the abbreviated "
                            + "spelling is preserved - and it is preserved where a reader will actually "
                            + "see it, in the diagnostic")
                    .isThrownBy(() -> new TransactionCategoryBalanceId(1L, "01", null))
                    .withMessageContaining("TRANCAT-CD")
                    .withMessageNotContaining("TRANCAT-CAT-CD")
                    .withNoCause();
        }

        @Test
        @DisplayName("the entity is not Serializable, while its @Embeddable key is and pins its serial form")
        void theEntityIsNotSerializableButTheKeyIs() {
            assertThat(Serializable.class.isAssignableFrom(TransactionCategoryBalance.class))
                    .as("JPA requires Serializable of a composite key class, not of an entity. Leaving "
                            + "the entity unserialisable keeps a persistent row off any byte-stream path "
                            + "by construction, which is the safest answer to the insecure-deserialisation "
                            + "concern of Rule 1 clause D")
                    .isFalse();
            assertThat(Serializable.class.isAssignableFrom(TransactionCategoryBalanceId.class))
                    .as("the key must be Serializable: the provider may serialise an identifier")
                    .isTrue();
            assertThat(TransactionCategoryBalanceId.class.isAnnotationPresent(Embeddable.class))
                    .as("@EmbeddedId on the entity requires @Embeddable on the key type")
                    .isTrue();
            assertThatCode(() -> {
                final Field serialVersionUid =
                        TransactionCategoryBalanceId.class.getDeclaredField("serialVersionUID");
                assertThat(Modifier.isStatic(serialVersionUid.getModifiers())
                        && Modifier.isFinal(serialVersionUid.getModifiers()))
                        .as("serialVersionUID must be static final for the serialised form to be pinned "
                                + "rather than computed from the class shape")
                        .isTrue();
            })
                    .as("an explicitly declared serialVersionUID is what stops an unrelated edit to the "
                            + "key from silently changing its serialised form")
                    .doesNotThrowAnyException();
        }
    }

    /**
     * Composite key ownership. The Blocker of this file: the key class owns every key column, and the
     * entity must not restate, override or duplicate any of them.
     */
    @Nested
    @DisplayName("2. Blocker: the @EmbeddedId key class owns every key column")
    class CompositeKeyOwnership {

        @Test
        @DisplayName("identifies the row with a single @EmbeddedId of the composite key type")
        void identifiesTheRowWithASingleEmbeddedId() throws NoSuchFieldException {
            final Field id = TransactionCategoryBalance.class.getDeclaredField("id");

            assertThat(id.isAnnotationPresent(EmbeddedId.class))
                    .as("TRAN-CAT-KEY at app/cpy/CVTRA01Y.cpy:L5 is a COBOL group of three fields, which "
                            + "is an @Embeddable value type and therefore an @EmbeddedId, not an @IdClass")
                    .isTrue();
            assertThat(id.getType())
                    .as("the identifier type is the dedicated 17-byte key class")
                    .isEqualTo(TransactionCategoryBalanceId.class);

            int embeddedIdCount = 0;
            for (final Field field : TransactionCategoryBalance.class.getDeclaredFields()) {
                if (field.isAnnotationPresent(EmbeddedId.class)) {
                    embeddedIdCount++;
                }
            }
            assertThat(embeddedIdCount)
                    .as("exactly one identifier: a second would be a mapping error rather than a "
                            + "composite key")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("adds NO @AttributeOverride, so a key column cannot be mapped twice")
        void addsNoAttributeOverride() {
            assertThat(TransactionCategoryBalance.class.isAnnotationPresent(AttributeOverride.class))
                    .as("Severity Blocker. An override restates a column the key class already declares. "
                            + "Hibernate then either aborts bootstrap with a repeated-column error or, on "
                            + "a name that happens to differ, maps the component twice and writes one of "
                            + "the two silently. Remediation: delete the override; the key class at "
                            + "TransactionCategoryBalanceId is the single owner of acct_id, tran_type_cd "
                            + "and tran_cat_cd")
                    .isFalse();
            assertThat(TransactionCategoryBalance.class.isAnnotationPresent(AttributeOverrides.class))
                    .as("Severity Blocker, for the reason given above; the plural form is the one an IDE "
                            + "generates and is just as damaging")
                    .isFalse();
            for (final Field field : TransactionCategoryBalance.class.getDeclaredFields()) {
                assertThat(field.isAnnotationPresent(AttributeOverride.class)
                        || field.isAnnotationPresent(AttributeOverrides.class))
                        .as("field '%s' must carry no attribute override either: the annotation is legal "
                                + "on the @EmbeddedId field as well as on the type, so both placements "
                                + "have to be excluded", field.getName())
                        .isFalse();
            }
        }

        @Test
        @DisplayName("declares NO scalar key field, so no key component exists outside the key class")
        void declaresNoScalarKeyField() {
            final Set<String> instanceFieldTypes = new TreeSet<>();
            for (final Field field : TransactionCategoryBalance.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    instanceFieldTypes.add(field.getType().getName());
                }
            }
            assertThat(instanceFieldTypes)
                    .as("Severity Blocker. A scalar accountId, typeCode or categoryCode alongside the "
                            + "@EmbeddedId would map the same column from two places. The only instance "
                            + "field types are the key class and BigDecimal - so there is no String and no "
                            + "Long on this entity at all, and a duplicated key component has nowhere to "
                            + "live")
                    .containsExactly("com.cardemo.model.key.TransactionCategoryBalanceId",
                            "java.math.BigDecimal");
        }

        @Test
        @DisplayName("uses NO @IdClass and NO @MapsId, which are the other two ways to restate a key")
        void usesNeitherIdClassNorMapsId() {
            assertThat(TransactionCategoryBalance.class.isAnnotationPresent(IdClass.class))
                    .as("@IdClass would require the three components to be declared on the entity as "
                            + "well, which is exactly the duplication this nested class forbids")
                    .isFalse();
            for (final Field field : TransactionCategoryBalance.class.getDeclaredFields()) {
                assertThat(field.isAnnotationPresent(MapsId.class))
                        .as("field '%s': @MapsId derives an identifier from an association, and this row "
                                + "has no association at all - the account id is a plain key component",
                                field.getName())
                        .isFalse();
            }
        }

        @Test
        @DisplayName("is not interchangeable with TransactionCategory: two copybooks declare TRAN-CAT-KEY")
        void isNotInterchangeableWithTheTransactionCategoryKey() {
            assertThat(TransactionCategoryBalanceId.class)
                    .as("Severity Medium as a source-reading hazard, Blocker if the two are conflated. "
                            + "app/cpy/CVTRA01Y.cpy:L5 and app/cpy/CVTRA04Y.cpy:L5 both declare a group "
                            + "named TRAN-CAT-KEY, but the first is 17 bytes over three fields and the "
                            + "second is 6 bytes over two. Reading the wrong copybook produces a key of "
                            + "the wrong width for the wrong cluster")
                    .isNotEqualTo(TransactionCategoryId.class);
            assertThat(TransactionCategoryId.class.isAssignableFrom(TransactionCategoryBalanceId.class))
                    .as("neither key type is a subtype of the other, so no assignment can silently "
                            + "substitute one for the other")
                    .isFalse();
            assertThat(TransactionCategoryBalanceId.class.isAssignableFrom(TransactionCategoryId.class))
                    .as("nor in the opposite direction")
                    .isFalse();

            final TransactionCategoryBalanceId balanceKey = new TransactionCategoryBalanceId(1L, "01", 1);
            final TransactionCategoryId categoryKey = new TransactionCategoryId("01", 1);

            assertThat(balanceKey)
                    .as("the 17-byte key carries an account id that the 6-byte key has no field for, so "
                            + "equality across the two must be false in both directions even when the "
                            + "shared type and category components agree")
                    .isNotEqualTo(categoryKey);
            assertThat(categoryKey)
                    .as("equality is symmetric, and both classes guard with an exact getClass() test "
                            + "rather than instanceof, which is what makes it so")
                    .isNotEqualTo(balanceKey);
        }

        @Test
        @DisplayName("orders its components account, type, category - the order the account control break needs")
        void ordersItsComponentsInCobolDeclarationOrder() throws NoSuchMethodException {
            final Constructor<TransactionCategoryBalanceId> canonical =
                    TransactionCategoryBalanceId.class.getDeclaredConstructor(Long.class, String.class,
                            Integer.class);

            assertThat(canonical.getParameterTypes())
                    .as("the parameter types alone pin the order, and they do it without depending on "
                            + "Field ordering, which the JVM does not specify: Long is TRANCAT-ACCT-ID "
                            + "9(11), String is TRANCAT-TYPE-CD X(02), Integer is TRANCAT-CD 9(04). "
                            + "Severity High if reordered: app/cbl/CBACT04C.cbl:L188-L222 browses this "
                            + "cluster in key order and breaks on the account at :L194, which only works "
                            + "because the account id leads the key")
                    .containsExactly(Long.class, String.class, Integer.class);

            final TransactionCategoryBalanceId key = new TransactionCategoryBalanceId(11L, "05", 4321);

            assertThat(key.getAccountId())
                    .as("no silent transposition: the first argument is the account id")
                    .isEqualTo(11L);
            assertThat(key.getTypeCd())
                    .as("the second argument is the two-character type code")
                    .isEqualTo("05");
            assertThat(key.getCatCd())
                    .as("the third argument is the four-digit category code")
                    .isEqualTo(4321);
        }

        @Test
        @DisplayName("keeps every row of one account contiguous in key order, so one ascending scan suffices")
        void keepsEveryRowOfOneAccountContiguousInKeyOrder() {
            final List<TransactionCategoryBalance> rows = new ArrayList<>(List.of(
                    new TransactionCategoryBalance(new TransactionCategoryBalanceId(2L, "01", 1),
                            new BigDecimal("2.00")),
                    new TransactionCategoryBalance(new TransactionCategoryBalanceId(1L, "05", 9),
                            new BigDecimal("1.09")),
                    new TransactionCategoryBalance(new TransactionCategoryBalanceId(2L, "01", 2),
                            new BigDecimal("2.01")),
                    new TransactionCategoryBalance(new TransactionCategoryBalanceId(1L, "01", 1),
                            new BigDecimal("1.00"))));

            rows.sort(Comparator.comparing((TransactionCategoryBalance row) -> row.getId().getAccountId())
                    .thenComparing(row -> row.getId().getTypeCd())
                    .thenComparing(row -> row.getId().getCatCd()));

            final List<Long> accountIds = new ArrayList<>();
            for (final TransactionCategoryBalance row : rows) {
                accountIds.add(row.getId().getAccountId());
            }
            assertThat(accountIds)
                    .as("sorting by the key in its declared order groups the two rows of account 1 ahead "
                            + "of the two rows of account 2, so the control break at "
                            + "app/cbl/CBACT04C.cbl:L194 fires exactly once per account. A key ordered "
                            + "type-first would interleave the accounts and fire the break repeatedly, "
                            + "flushing a partial interest total each time")
                    .containsExactly(1L, 1L, 2L, 2L);
        }
    }

    /**
     * Precision and decimal discipline. {@code TRAN-CAT-BAL} is {@code S9(09)V99} and therefore
     * {@code NUMERIC(11,2)}; the neighbouring account money fields are {@code S9(10)V99} and therefore
     * {@code NUMERIC(12,2)}. Collapsing the two tiers is the likeliest single error in this file.
     */
    @Nested
    @DisplayName("3. Precision: the balance is NUMERIC(11,2) and never NUMERIC(12,2)")
    class BalancePrecisionAndDecimalDiscipline {

        @Test
        @DisplayName("maps the balance to tran_cat_bal at precision 11, scale 2, NOT NULL")
        void mapsTheBalanceAtElevenTwo() throws NoSuchFieldException {
            final Column column =
                    TransactionCategoryBalance.class.getDeclaredField("balance").getAnnotation(Column.class);

            assertThat(column.name())
                    .as("the column name V1__create_schema.sql declares for TRAN-CAT-BAL")
                    .isEqualTo(BALANCE_COLUMN);
            assertThat(column.precision())
                    .as("Severity High. PIC S9(09)V99 is nine integer digits plus two decimals, so "
                            + "precision is 11. Twelve is the Account tier, PIC S9(10)V99, and the two "
                            + "must never be collapsed: under ddl-auto=validate a widened precision "
                            + "aborts context startup, and without validation it degrades into silent "
                            + "scale divergence that no test lacking this assertion would notice. "
                            + "Remediation: derive precision from app/cpy/CVTRA01Y.cpy:L9, never from a "
                            + "neighbouring entity")
                    .isEqualTo(BALANCE_PRECISION);
            assertThat(column.precision())
                    .as("stated as an inequality as well, because the failure this guards against is "
                            + "specifically the value 12")
                    .isNotEqualTo(12);
            assertThat(column.scale())
                    .as("the two digits after the implied V of PIC S9(09)V99")
                    .isEqualTo(BALANCE_SCALE);
            assertThat(column.nullable())
                    .as("a packed numeric field always holds a value, so the column is NOT NULL and the "
                            + "row can never carry an absent balance")
                    .isFalse();
        }

        @Test
        @DisplayName("the mapped precision is the width the frozen fixture measures for that field")
        void theMappedPrecisionMatchesTheFixtureFieldWidth() {
            assertThat(FixtureLoader.AMOUNT_FIELD_WIDTH)
                    .as("app/data/ASCII/tcatbal.txt holds an ELEVEN-character balance field, and eleven "
                            + "characters of S9(09)V99 is nine integer digits plus two decimals - the "
                            + "fixture bytes therefore confirm the precision tier independently of the "
                            + "picture clause. FixtureLoader.MONEY_FIELD_WIDTH is 12 and belongs to the "
                            + "Account tier, which is why the two constants are kept apart")
                    .isEqualTo(BALANCE_WIDTH)
                    .isEqualTo(BALANCE_PRECISION);
            assertThat(FixtureLoader.MONEY_FIELD_WIDTH)
                    .as("the twelve-character tier exists and is a different tier; naming it here is what "
                            + "makes the distinction visible at the point of use")
                    .isNotEqualTo(BALANCE_WIDTH);
            assertThat(FixtureLoader.DECIMAL_SCALE)
                    .as("every signed field in these nine fixtures carries two implied decimals")
                    .isEqualTo(BALANCE_SCALE);
        }

        @Test
        @DisplayName("uses no float and no double anywhere, in either the entity or its key")
        void usesNoBinaryFloatingPointAnywhere() throws NoSuchFieldException {
            final Set<String> offenders = new TreeSet<>();
            for (final Class<?> type : List.of(TransactionCategoryBalance.class,
                    TransactionCategoryBalanceId.class)) {
                for (final String referenced : referencedTypeNames(type)) {
                    if ("float".equals(referenced) || "double".equals(referenced)
                            || "java.lang.Float".equals(referenced) || "java.lang.Double".equals(referenced)) {
                        offenders.add(type.getSimpleName() + " references " + referenced);
                    }
                }
            }
            assertThat(offenders)
                    .as("binary floating point cannot represent an ordinary decimal amount exactly, so a "
                            + "single float or double on this path would diverge from the source system by "
                            + "fractions of a cent that accumulate across a posting run. The security "
                            + "audit gate asserts their absence in every financial field; this census "
                            + "covers field types, constructor and method parameters and return types")
                    .isEmpty();
            assertThat(TransactionCategoryBalance.class.getDeclaredField("balance").getType())
                    .as("the balance is a BigDecimal and nothing else")
                    .isEqualTo(BigDecimal.class);
        }

        @Test
        @DisplayName("compares amounts by compareTo, because BigDecimal.equals also compares scale")
        void comparesAmountsByCompareToAndNotByEquals() {
            final BigDecimal unscaledZero = new BigDecimal("0");
            final BigDecimal scaledZero = new BigDecimal("0.00");

            assertThat(unscaledZero)
                    .as("this is the trap: the two denote the same amount but differ in scale, so equals "
                            + "reports them unequal. Every amount assertion in this file therefore uses "
                            + "isEqualByComparingTo")
                    .isNotEqualTo(scaledZero)
                    .isEqualByComparingTo(scaledZero);
            assertThat(unscaledZero.compareTo(scaledZero))
                    .as("compareTo ignores scale and answers the question actually being asked")
                    .isZero();

            assertThat(rowWithBalance("0").getBalance())
                    .as("the entity stores the value as supplied, so the trap reaches any caller that "
                            + "compares a stored balance with equals")
                    .isEqualByComparingTo(scaledZero);
        }

        @Test
        @DisplayName("stores the balance exactly as supplied, never rescaling, normalising or rounding it")
        void storesTheBalanceWithoutRescaling() {
            assertThat(rowWithBalance("7").getBalance().scale())
                    .as("a scale of zero is accepted and preserved: 7 and 7.00 denote the same amount and "
                            + "the NUMERIC(11,2) column stores either as two decimal places. Rescaling "
                            + "here would hide from the caller which of the two it supplied")
                    .isZero();
            assertThat(rowWithBalance("7.00").getBalance().scale())
                    .as("a scale of two is likewise preserved rather than stripped")
                    .isEqualTo(BALANCE_SCALE);
            assertThat(rowWithBalance("7").getBalance())
                    .as("and the two are the same amount, which is the point of comparing by value")
                    .isEqualByComparingTo(rowWithBalance("7.00").getBalance());
        }

        @ParameterizedTest
        @ValueSource(strings = {"-0.01", "-1234.56", MIN_BALANCE})
        @DisplayName("accepts a negative balance and keeps its sign intact, applying no abs()")
        void acceptsANegativeBalanceWithItsSignIntact(final String negative) {
            final TransactionCategoryBalance row = rowWithBalance(negative);

            assertThat(row.getBalance().signum())
                    .as("app/cbl/CBTRN02C.cbl adds DALYTRAN-AMT to TRAN-CAT-BAL on both the create branch "
                            + "at :L508 and the rewrite branch at :L527, and the picture clause carries an "
                            + "S, so a negative running balance is an ordinary outcome of posting credits. "
                            + "Fifty of the three hundred rows of app/data/ASCII/dailytran.txt carry a "
                            + "negative overpunch, so this branch is exercised by the real fixture rather "
                            + "than only in theory")
                    .isEqualTo(-1);
            assertThat(row.getBalance().toPlainString())
                    .as("no absolute value, no sign normalisation and no rescale: the stored text is "
                            + "character for character what was supplied")
                    .isEqualTo(negative);
        }

        @Test
        @DisplayName("accepts a zero balance, which is the entire seeded state, so no positivity constraint")
        void acceptsAZeroBalance() {
            assertThatCode(() -> rowWithBalance("0.00"))
                    .as("all 50 rows of app/data/ASCII/tcatbal.txt hold +0.00, so a @Positive or a "
                            + "@Min(1) on this field would reject every seeded row. Zero is also the "
                            + "opening value of every row the create branch writes")
                    .doesNotThrowAnyException();
            assertThat(rowWithBalance("0.00").getBalance())
                    .as("zero is stored as zero")
                    .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(annotationNames(TransactionCategoryBalance.class))
                    .as("no bean validation constraint of any kind appears on this entity, so neither "
                            + "@Positive nor @Min nor @DecimalMin can be silently narrowing the domain")
                    .doesNotContain("Positive", "PositiveOrZero", "Min", "DecimalMin", "NotNull");
        }

        @Test
        @DisplayName("accepts both extremes of S9(09)V99 and refuses the first value outside them")
        void acceptsBothExtremesAndRefusesBeyond() {
            assertThat(rowWithBalance(MAX_BALANCE).getBalance())
                    .as("nine integer digits and two decimals reach exactly 999999999.99")
                    .isEqualByComparingTo(MAX_BALANCE);
            assertThat(rowWithBalance(MIN_BALANCE).getBalance())
                    .as("the domain is symmetric about zero because the picture clause is signed")
                    .isEqualByComparingTo(MIN_BALANCE);

            assertThatIllegalArgumentException()
                    .as("one cent beyond the maximum needs a tenth integer digit, which neither "
                            + "PIC S9(09)V99 nor NUMERIC(11,2) can hold. Refusing it here names the field "
                            + "and the picture clause; letting it reach the column yields a diagnostic "
                            + "that names only a column")
                    .isThrownBy(() -> rowWithBalance("1000000000.00"))
                    .withMessageContaining("TRAN-CAT-BAL PIC S9(09)V99")
                    .withMessageContaining(MAX_BALANCE)
                    .withNoCause();
            assertThatIllegalArgumentException()
                    .as("and symmetrically at the negative extreme")
                    .isThrownBy(() -> rowWithBalance("-1000000000.00"))
                    .withMessageContaining(MIN_BALANCE)
                    .withNoCause();
        }

        @Test
        @DisplayName("refuses a third decimal digit rather than letting the column round it away")
        void refusesAThirdDecimalDigit() {
            assertThatIllegalArgumentException()
                    .as("V99 declares exactly two decimal positions. A NUMERIC(11,2) column would round "
                            + "a third digit half away from zero instead of refusing it, so the caller "
                            + "would never learn that its value changed. The diagnostic names the "
                            + "rounding mode to use, which is the project-wide HALF_EVEN")
                    .isThrownBy(() -> rowWithBalance("1.005"))
                    .withMessageContaining("at most 2 decimal digits")
                    .withMessageContaining("HALF_EVEN")
                    .withNoCause();
        }
    }

    /**
     * Free constructibility. {@code app/cbl/CBTRN02C.cbl:L467-L500} is an upsert whose read accepts a
     * not-found status as success, so the create branch must be able to build a complete row for a key that
     * does not yet exist, in one step and with no invented default.
     */
    @Nested
    @DisplayName("4. The accepted not-found control path: freely constructible, with no invented default")
    class ConstructibleForTheAcceptedUpsertPath {

        @Test
        @DisplayName("exposes a public all-args constructor, so the create branch needs no factory")
        void exposesAPublicAllArgsConstructor() throws NoSuchMethodException {
            final Constructor<TransactionCategoryBalance> allArgs =
                    TransactionCategoryBalance.class.getDeclaredConstructor(TransactionCategoryBalanceId.class,
                            BigDecimal.class);

            assertThat(Modifier.isPublic(allArgs.getModifiers()))
                    .as("Severity High if construction required a factory or a builder. When the keyed "
                            + "read at app/cbl/CBTRN02C.cbl:L474-L479 reports INVALID KEY, :L481 accepts "
                            + "that status as success and :L495-L499 dispatch to 2700-A-CREATE-TCATBAL-REC "
                            + "at :L503, which builds a complete record and writes it. The Java "
                            + "equivalent must be reachable from the batch tier in one step, with no "
                            + "half-built intermediate state and no is-new flag")
                    .isTrue();
            assertThat(allArgs.getParameterTypes())
                    .as("the key first, then the balance: exactly the two mapped members and nothing else")
                    .containsExactly(TransactionCategoryBalanceId.class, BigDecimal.class);
        }

        @Test
        @DisplayName("builds a row for a key that does not yet exist, which is what the upsert requires")
        void buildsARowForAKeyThatDoesNotYetExist() {
            assertThatCode(() -> new TransactionCategoryBalance(
                    new TransactionCategoryBalanceId(99_999_999_999L, "ZZ", 9999),
                    new BigDecimal("0.00")))
                    .as("no lookup, no existence check and no repository is consulted at construction. "
                            + "The row for a key with no counterpart in the table is exactly what the "
                            + "create branch writes, so refusing to build one would make the accepted "
                            + "not-found path inexpressible")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("exposes a no-argument constructor the persistence provider can reach")
        void exposesANoArgumentConstructorForTheProvider() throws NoSuchMethodException {
            final Constructor<TransactionCategoryBalance> noArgs =
                    TransactionCategoryBalance.class.getDeclaredConstructor();

            assertThat(Modifier.isPrivate(noArgs.getModifiers()))
                    .as("JPA requires a no-argument constructor that is public or protected. It is "
                            + "protected here rather than public, deliberately and in line with every "
                            + "other entity in the package: an instance with both members unset satisfies "
                            + "none of the invariants the all-args constructor enforces, while being "
                            + "indistinguishable from a real row at the type level. Hibernate is "
                            + "unaffected, and the batch tier reaches the public all-args constructor "
                            + "instead - which is the accessibility the accepted not-found path actually "
                            + "needs")
                    .isFalse();
            assertThat(Modifier.isProtected(noArgs.getModifiers()))
                    .as("protected is the accessibility asserted, so a later widening to public or a "
                            + "narrowing to package-private is caught here rather than at bootstrap")
                    .isTrue();
        }

        @Test
        @DisplayName("invents NO default balance: a provider-built instance carries none")
        void inventsNoDefaultBalance() throws ReflectiveOperationException {
            final Constructor<TransactionCategoryBalance> noArgs =
                    TransactionCategoryBalance.class.getDeclaredConstructor();
            noArgs.setAccessible(true);
            final TransactionCategoryBalance provisional = noArgs.newInstance();

            assertThat(provisional.getBalance())
                    .as("Severity High. A field initialiser of BigDecimal.ZERO would fabricate an opening "
                            + "value the source never produced and would overwrite a supplied balance on "
                            + "every provider-built instance. app/cbl/CBTRN02C.cbl:L504-L508 INITIALIZEs "
                            + "the record and then ADDs DALYTRAN-AMT, so the caller always supplies the "
                            + "opening value. This mirrors what Hibernate itself does - instantiate, then "
                            + "populate reflectively - which is why the absence is observable at all")
                    .isNull();
            assertThat(provisional.getId())
                    .as("and the key is likewise unset, so nothing is silently defaulted")
                    .isNull();
        }

        @Test
        @DisplayName("declares NO JPA lifecycle callback, so nothing rewrites the row on the way to the table")
        void declaresNoJpaLifecycleCallback() {
            final Set<String> callbacks = new TreeSet<>(Set.of("PrePersist", "PostPersist", "PreUpdate",
                    "PostUpdate", "PreRemove", "PostRemove", "PostLoad"));

            assertThat(annotationNames(TransactionCategoryBalance.class))
                    .as("Severity High. A @PrePersist that defaulted the balance, or a @PostLoad that "
                            + "rescaled it, would change the value between the batch tier and the table "
                            + "and would do it invisibly to every unit test of the batch tier. All seven "
                            + "callbacks are excluded by name here as well as by the exact census in "
                            + "nested class 1, because this is the one whose accidental addition looks "
                            + "most like a fix")
                    .doesNotContainAnyElementsOf(callbacks);
        }

        @Test
        @DisplayName("performs no lookup and holds no collaborator, so the upsert decision stays in the batch tier")
        void performsNoLookupAndHoldsNoCollaborator() {
            final Set<String> referenced = referencedTypeNames(TransactionCategoryBalance.class);
            final List<String> forbidden = new ArrayList<>();
            for (final String name : referenced) {
                if (name.startsWith("jakarta.persistence.EntityManager")
                        || name.startsWith("java.sql.")
                        || name.startsWith("javax.sql.")
                        || name.startsWith("org.springframework.")
                        || name.contains("Repository")
                        || name.contains("Service")) {
                    forbidden.add(name);
                }
            }
            assertThat(forbidden)
                    .as("the entity is a data holder. Deciding whether a row exists - the whole substance "
                            + "of app/cbl/CBTRN02C.cbl:L481, where status '00' and status '23' are both "
                            + "success - belongs to the batch tier, which alone can see the read that "
                            + "produced the status. An entity that could look itself up would put that "
                            + "decision in two places")
                    .isEmpty();
        }
    }

    /**
     * The seed fixture contract. Every figure here was measured from {@code app/data/ASCII/tcatbal.txt}
     * rather than inferred, and the fixture is reached by classpath resource name only.
     */
    @Nested
    @DisplayName("5. Seed fixture: 2550 bytes, 50 rows of 50, an eleven-character balance and a zero filler")
    class SeedFixtureContract {

        @Test
        @DisplayName("holds exactly 2550 bytes as 50 records of 50 characters plus one terminator each")
        void holdsTheMeasuredCensus() {
            final FixtureLoader.FixtureData data = seedFixture();

            assertThat(data.byteCount())
                    .as("measured, not assumed: 2550 bytes")
                    .isEqualTo(2550);
            assertThat(data.recordCount())
                    .as("50 rows, one per seeded account")
                    .isEqualTo(50);
            assertThat(data.recordWidth())
                    .as("each row is the catalogued 50-byte record")
                    .isEqualTo(RECORD_LENGTH);
            assertThat(data.impliedByteCount())
                    .as("50 x (50 + 1) = 2550 closes the geometry exactly, the plus one being the single "
                            + "line feed that terminates every record including the last. A fixture "
                            + "converted to CRLF would double the terminator and fail this")
                    .isEqualTo(data.byteCount());
        }

        @Test
        @DisplayName("carries the type and category pair 010001 on ALL 50 rows, with no variety to invent")
        void carriesOneTypeAndCategoryPairOnEveryRow() {
            final FixtureLoader.FixtureData data = seedFixture();
            final Set<String> pairs = new TreeSet<>();
            for (int row = 0; row < data.recordCount(); row++) {
                pairs.add(data.field(row, TYPE_CD_COLUMN, TYPE_CD_WIDTH + CAT_CD_WIDTH));
            }

            assertThat(pairs)
                    .as("TRANCAT-TYPE-CD '01' followed by TRANCAT-CD '0001' on every single row. The seed "
                            + "is deliberately uniform, so a test that expected several type or category "
                            + "combinations would be asserting data that does not exist")
                    .containsExactly("010001");
        }

        @Test
        @DisplayName("holds an ELEVEN-character balance field of '0000000000{' on ALL 50 rows")
        void holdsAnElevenCharacterZeroBalanceOnEveryRow() {
            final FixtureLoader.FixtureData data = seedFixture();
            final Set<String> balances = new TreeSet<>();
            for (int row = 0; row < data.recordCount(); row++) {
                final String raw = data.field(row, BALANCE_COLUMN_START, BALANCE_WIDTH);
                balances.add(raw);
                assertThat(raw.length())
                        .as("row %d: the field is eleven characters wide, which is nine integer digits, "
                                + "two decimals and one trailing sign overpunch - not the twelve of the "
                                + "Account money tier", row)
                        .isEqualTo(BALANCE_WIDTH);
            }

            assertThat(balances)
                    .as("one distinct value across the whole fixture, and it is the positive-zero "
                            + "overpunch form. This single fact corroborates the S9(09)V99 precision from "
                            + "the data side, independently of the picture clause")
                    .containsExactly("0000000000{");
        }

        @Test
        @DisplayName("decodes every seeded balance to exactly +0.00, so accumulation starts from zero")
        void decodesEverySeededBalanceToPositiveZero() {
            final FixtureLoader.FixtureData data = seedFixture();

            for (int row = 0; row < data.recordCount(); row++) {
                final BigDecimal decoded = data.signedDecimal(row, BALANCE_COLUMN_START, BALANCE_WIDTH);

                assertThat(decoded)
                        .as("row %d: the trailing '{' is the overpunch for a positive low-order digit of "
                                + "zero, so the field decodes to +0.00 rather than to any text-like "
                                + "interpretation of the brace", row)
                        .isEqualByComparingTo("0.00");
                assertThat(decoded.signum())
                        .as("row %d: positive zero has signum zero, which is what makes a zero opening "
                                + "balance indistinguishable from an accumulated zero - as it is in the "
                                + "source", row)
                        .isZero();
            }
        }

        @Test
        @DisplayName("pads the unmodelled FILLER X(22) tail with '0' and not with spaces")
        void padsTheFillerTailWithZeroes() {
            final FixtureLoader.FixtureData data = seedFixture();
            final Set<String> tails = new TreeSet<>();
            for (int row = 0; row < data.recordCount(); row++) {
                tails.add(data.field(row, FILLER_COLUMN_START, FILLER_WIDTH));
            }

            assertThat(tails)
                    .as("this fixture zero-fills its filler, unlike trantype.txt, trancatg.txt and "
                            + "discgrp.txt, which space-fill theirs. A fixed-width writer that space-pads "
                            + "would therefore not reproduce these 22 bytes, and the parity comparison "
                            + "would differ in a region that carries no data at all")
                    .containsExactly("0".repeat(FILLER_WIDTH));
            assertThat(data.recordAt(0).charAt(RECORD_LENGTH - 1))
                    .as("the final character of a record is '0' rather than a space, so the trailing "
                            + "space run of this fixture is zero characters long")
                    .isEqualTo('0');
        }

        @Test
        @DisplayName("every one of the 50 rows builds a valid row keyed on accounts 1 through 50")
        void everyRowBuildsAValidEntity() {
            final FixtureLoader.FixtureData data = seedFixture();
            final List<Long> accountIds = new ArrayList<>();

            for (int row = 0; row < data.recordCount(); row++) {
                final TransactionCategoryBalanceId key = new TransactionCategoryBalanceId(
                        Long.valueOf(data.field(row, 1, ACCT_ID_WIDTH)),
                        data.field(row, TYPE_CD_COLUMN, TYPE_CD_WIDTH),
                        Integer.valueOf(data.field(row, CAT_CD_COLUMN, CAT_CD_WIDTH)));
                final TransactionCategoryBalance entity = new TransactionCategoryBalance(key,
                        data.signedDecimal(row, BALANCE_COLUMN_START, BALANCE_WIDTH));

                assertThat(entity.getId().getTypeCd())
                        .as("row %d: the two-character type code is taken verbatim, never trimmed", row)
                        .isEqualTo("01");
                assertThat(entity.getId().getCatCd())
                        .as("row %d: '0001' parses to the category code 1", row)
                        .isEqualTo(1);
                accountIds.add(entity.getId().getAccountId());
            }

            assertThat(accountIds)
                    .as("the seeded accounts are 1 through 50, ascending and contiguous, each appearing "
                            + "exactly once. That is what makes the account control break at "
                            + "app/cbl/CBACT04C.cbl:L194 fire fifty times over one ascending scan")
                    .hasSize(50)
                    .isSorted()
                    .doesNotHaveDuplicates()
                    .startsWith(1L)
                    .endsWith(50L);
        }

        @ParameterizedTest
        @CsvSource({"{, 0.00", "A, 0.01", "E, 0.05", "I, 0.09", "}, 0.00", "J, -0.01", "N, -0.05",
                "R, -0.09"})
        @DisplayName("decodes the whole overpunch alphabet: '{' is +0, A-I are +1..+9, '}' is -0, J-R are -1..-9")
        void decodesTheWholeOverpunchAlphabet(final char sign, final String expected) {
            final String field = "0".repeat(BALANCE_WIDTH - 1) + sign;

            assertThat(FixtureLoader.decodeZonedDecimal(field, BALANCE_SCALE))
                    .as("the sign is the LAST character of the field and carries the low-order digit as "
                            + "well as the sign, which is what a zoned-decimal trailing overpunch is")
                    .isEqualByComparingTo(expected);
        }

        @Test
        @DisplayName("treats the negative-zero overpunch '}' as zero, sign and all")
        void treatsNegativeZeroAsZero() {
            final BigDecimal negativeZero =
                    FixtureLoader.decodeZonedDecimal("0".repeat(BALANCE_WIDTH - 1) + "}", BALANCE_SCALE);

            assertThat(negativeZero.signum())
                    .as("'}' means a negative low-order digit of zero, and negating zero yields zero, so "
                            + "the value is numerically indistinguishable from the '{' form. Both appear "
                            + "in the corpus and neither is an error")
                    .isZero();
            assertThat(negativeZero)
                    .as("and it compares equal to the positive-zero form")
                    .isEqualByComparingTo("0.00");
        }

        @ParameterizedTest
        @ValueSource(chars = {'S', 'Z', '*', '+', '-', ' '})
        @DisplayName("refuses a character that is not an overpunch, naming both alphabets in the diagnostic")
        void refusesANonOverpunchSignCharacter(final char hostile) {
            assertThatIllegalArgumentException()
                    .as("'S' is the boundary case worth naming: it is the very next letter after 'R', "
                            + "which is a legitimate -9, so an implementation that decoded a letter range "
                            + "too generously would accept it. A blank, a plus and a minus are the shapes "
                            + "a hand-edited file would carry")
                    .isThrownBy(() -> FixtureLoader.decodeZonedDecimal(
                            "0".repeat(BALANCE_WIDTH - 1) + hostile, BALANCE_SCALE))
                    .withMessageContaining("is not a zoned-decimal")
                    .withMessageContaining(POSITIVE_OVERPUNCH)
                    .withMessageContaining(NEGATIVE_OVERPUNCH)
                    .withNoCause();
        }

        @Test
        @DisplayName("BLOCKER: decoding must be position-aware, because a misaligned slice fails silently")
        void decodingMustBePositionAware() {
            final FixtureLoader.FixtureData data = seedFixture();

            assertThat(data.signedDecimal(0, BALANCE_COLUMN_START, BALANCE_WIDTH))
                    .as("sliced at the offset the picture clause dictates, row 1 decodes to +0.00")
                    .isEqualByComparingTo("0.00");
            assertThat(data.signedDecimal(0, BALANCE_COLUMN_START - 1, BALANCE_WIDTH))
                    .as("Severity Blocker. Starting one column early swallows the last digit of "
                            + "TRANCAT-CD and drops the overpunch, and the result is 100000000.00 - a "
                            + "hundred million rather than zero, with no exception and no warning. This is "
                            + "why the offset and the width must come from the PIC clause and why a global "
                            + "text substitution of the overpunch letters is never acceptable: A through R "
                            + "occur legitimately as text elsewhere in the corpus")
                    .isEqualByComparingTo("100000000.00");
        }

        @Test
        @DisplayName("a ten-character slice is silently wrong, so the width must come from the PIC clause")
        void aTenCharacterSliceIsSilentlyWrong() {
            final FixtureLoader.FixtureData data = seedFixture();

            assertThat(data.signedDecimal(0, BALANCE_COLUMN_START, BALANCE_WIDTH - 1))
                    .as("one character short of the eleven the picture clause declares, this fixture still "
                            + "decodes to 0.00 - because every digit and the overpunch alike stand for "
                            + "zero here. Stated plainly: THIS FIXTURE CANNOT DETECT A TOO-SHORT WIDTH, "
                            + "which is exactly why the width is taken from app/cpy/CVTRA01Y.cpy:L9 rather "
                            + "than inferred by inspecting data")
                    .isEqualByComparingTo("0.00");

            assertThat(FixtureLoader.decodeZonedDecimal("0000012345", BALANCE_SCALE))
                    .as("on a field that carries a real value the same one-character shortfall is "
                            + "catastrophic and still silent. The genuine eleven-character field "
                            + "'0000012345J' is -1234.51; read ten characters wide it becomes +123.45 - "
                            + "wrong magnitude and wrong sign, no exception raised, because the digit that "
                            + "lands in the sign position is a perfectly valid unsigned one. A twelve-wide "
                            + "read at least throws, as the next test shows; a ten-wide read does not")
                    .isEqualByComparingTo("123.45");
            assertThat(FixtureLoader.decodeZonedDecimal("0000012345J", BALANCE_SCALE))
                    .as("and this is what that same field really holds at its declared width")
                    .isEqualByComparingTo("-1234.51");
        }

        @Test
        @DisplayName("wraps a wrong-width decode in IllegalStateException, preserving the root cause")
        void wrapsAWrongWidthDecodeAndPreservesTheCause() {
            final FixtureLoader.FixtureData data = seedFixture();

            assertThatIllegalStateException()
                    .as("twelve is the Account money tier, PIC S9(10)V99. Applied to this record it reads "
                            + "the first byte of FILLER as the sign and leaves the real overpunch inside "
                            + "the digits, so the wrong precision tier is caught rather than silently "
                            + "tolerated. The context names the resource, the record and the columns, and "
                            + "the cause is preserved rather than swallowed")
                    .isThrownBy(() -> data.signedDecimal(0, BALANCE_COLUMN_START, FixtureLoader.MONEY_FIELD_WIDTH))
                    .withMessageContaining("tcatbal.txt")
                    .withMessageContaining("columns 18-29")
                    .withCauseInstanceOf(IllegalArgumentException.class)
                    .havingCause()
                    .withMessageContaining("every character before the sign position must be a digit");
        }

        @Test
        @DisplayName("resolves the daily-transaction fixture as dailytran.txt and never as dalytran.txt")
        void resolvesTheDailyTransactionFixtureByItsRealName() {
            assertThat(FixtureLoader.Fixture.DAILY_TRANSACTION.resourceName())
                    .as("the mainframe DD name and dataset are DALYTRAN but the ASCII fixture spells "
                            + "'daily' in full. A resource name of 'dalytran.txt' resolves to nothing, and "
                            + "the failure surfaces far from its cause. It is asserted from this class "
                            + "because the posting job that upserts this very row is the job that reads "
                            + "that fixture")
                    .isEqualTo("dailytran.txt");
        }
    }

    /**
     * Batch-only dataset. {@code TCATBALF} has no CICS file definition, no alternate index and no
     * association, so nothing here may presume an online path or a secondary index.
     */
    @Nested
    @DisplayName("6. Batch-only: no online definition, no alternate index, no association")
    class BatchOnlyDataset {

        @Test
        @DisplayName("declares NO index and NO unique constraint, because the cluster has no alternate index")
        void declaresNoSecondaryIndex() {
            final Table table = TransactionCategoryBalance.class.getAnnotation(Table.class);

            assertThat(table.indexes())
                    .as("app/catlg/LISTCAT.txt:L3938 and :L3946 tally exactly AIX 3 and PATH 3 for the "
                            + "whole catalogue, and all three belong to CARDDATA, CARDXREF and TRANSACT. "
                            + "TCATBALF has none, so a secondary index here would be an invention with no "
                            + "counterpart in the source and V2__create_indexes.sql declares none")
                    .isEmpty();
            assertThat(table.uniqueConstraints())
                    .as("the composite primary key is the only uniqueness the cluster asserts")
                    .isEmpty();
        }

        @Test
        @DisplayName("declares NO JPA association: the account id is a plain key component")
        void declaresNoAssociation() {
            for (final Field field : TransactionCategoryBalance.class.getDeclaredFields()) {
                assertThat(field.isAnnotationPresent(ManyToOne.class))
                        .as("field '%s': TRANCAT-ACCT-ID is eleven display digits inside a 17-byte key, "
                                + "not a reference. Modelling it as @ManyToOne would make an account "
                                + "fetch a hidden side effect of touching a balance row and would change "
                                + "the key's shape", field.getName())
                        .isFalse();
            }
            assertThat(annotationNames(TransactionCategoryBalance.class))
                    .as("no association of any cardinality and no join column: the referential rule lives "
                            + "in the migration as a foreign key, where the source's own key derivation "
                            + "at app/cbl/CBTRN02C.cbl:L469-L471 puts it")
                    .doesNotContain("ManyToOne", "OneToMany", "OneToOne", "ManyToMany", "JoinColumn",
                            "JoinTable", "ElementCollection");
        }

        @Test
        @DisplayName("presumes no online path, so no lazy association exists for toString to reach")
        void presumesNoOnlinePath() {
            final TransactionCategoryBalance row = rowWithBalance("12.34");

            assertThatCode(row::toString)
                    .as("app/csd/CARDDEMO.CSD defines exactly eight CICS files - ACCTDAT, CARDAIX, "
                            + "CARDDAT, CCXREF, CUSTDAT, CXACAIX, TRANSACT and USRSEC - and TCATBALF is "
                            + "not among them, which is the evidence that this dataset is batch-only. "
                            + "With no association declared there is nothing lazily loaded for a "
                            + "diagnostic rendering to trigger, so toString cannot fault outside a "
                            + "session")
                    .doesNotThrowAnyException();
            assertThat(referencedTypeNames(TransactionCategoryBalance.class))
                    .as("nothing from the online stack is referenced at all: no session, no proxy and no "
                            + "collection type that could be a lazy handle")
                    .noneMatch(name -> name.startsWith("org.hibernate.")
                            || name.startsWith("java.util.Collection")
                            || name.startsWith("java.util.Set")
                            || name.startsWith("java.util.List"));
        }
    }

    /**
     * Scope and diagnostics. The entity's dependency surface, its identity, and a {@code toString} that
     * carries enough to identify a row and nothing that could leak.
     */
    @Nested
    @DisplayName("7. Scope: one intra-project dependency, a safe toString and no global mutable state")
    class ScopeAndDiagnostics {

        @Test
        @DisplayName("names exactly one intra-project type: the composite key class")
        void namesExactlyOneIntraProjectType() {
            final Set<String> intraProject = new TreeSet<>();
            for (final String referenced : referencedTypeNames(TransactionCategoryBalance.class)) {
                if (referenced.startsWith("com.cardemo.")) {
                    intraProject.add(referenced);
                }
            }

            assertThat(intraProject)
                    .as("the row is a leaf of the dependency graph. Its only project-internal dependency "
                            + "is the key that identifies it, which is what keeps the model package "
                            + "importable from the batch, service and repository tiers without dragging "
                            + "any of them along")
                    .containsExactly("com.cardemo.model.key.TransactionCategoryBalanceId");
        }

        @ParameterizedTest
        @ValueSource(strings = {"com.cardemo.exception.", "com.cardemo.repository.", "com.cardemo.service.",
                "com.cardemo.controller.", "com.cardemo.batch.", "com.cardemo.security.",
                "com.cardemo.config.", "com.cardemo.observability.", "com.cardemo.model.enums."})
        @DisplayName("references nothing from any layer above or beside the model package")
        void referencesNothingFromAnyOtherLayer(final String forbiddenPackage) {
            assertThat(referencedTypeNames(TransactionCategoryBalance.class))
                    .as("a reference to %s would invert the layering: the exception, repository, service, "
                            + "controller, batch, security, configuration and observability tiers all "
                            + "depend on the model, so the model may depend on none of them. model.enums "
                            + "is excluded too - neither the type code nor the category code is an "
                            + "enumeration, because the source validates them against the TRANTYPE and "
                            + "TRANCATG datasets rather than against a fixed set", forbiddenPackage)
                    .noneMatch(name -> name.startsWith(forbiddenPackage));
        }

        @Test
        @DisplayName("toString identifies the row by key and deliberately withholds the balance")
        void toStringIdentifiesTheRowWithoutRenderingTheBalance() {
            final TransactionCategoryBalance row = rowWithBalance("-4321.99");

            assertThat(row.toString())
                    .as("the key components are an account identifier, a two-character type code and a "
                            + "four-digit category code: no credential, no password hash, no government "
                            + "identifier, no cardholder name and no address. They are also the only "
                            + "values that say which row a log line refers to, which is the whole purpose "
                            + "of a diagnostic rendering")
                    .startsWith("TransactionCategoryBalance[id=")
                    .contains("accountId=1")
                    .contains("typeCd=01")
                    .contains("catCd=1")
                    .endsWith("]");
            assertThat(row.toString())
                    .as("the balance is withheld, which is stricter than the safety this file was "
                            + "required to prove and is deliberate. A rendering that carried both the "
                            + "account identifier and the amount would let a log estate be joined back "
                            + "into a per-account, per-category balance ledger with no database access "
                            + "and no authorisation. A reader who needs the amount should read the row")
                    .doesNotContain("4321.99")
                    .doesNotContain("balance");
        }

        @Test
        @DisplayName("toString does not fault on a provider-built instance whose members are still unset")
        void toStringSurvivesAnUnpopulatedInstance() throws ReflectiveOperationException {
            final Constructor<TransactionCategoryBalance> noArgs =
                    TransactionCategoryBalance.class.getDeclaredConstructor();
            noArgs.setAccessible(true);
            final TransactionCategoryBalance provisional = noArgs.newInstance();

            assertThatCode(provisional::toString)
                    .as("a diagnostic that throws while diagnosing is worse than no diagnostic. Between "
                            + "instantiation and reflective population the provider holds an instance with "
                            + "both members null, and a debugger or a log statement may render it in that "
                            + "state")
                    .doesNotThrowAnyException();
            assertThat(provisional.toString())
                    .as("the null key renders as the literal null rather than crashing")
                    .isEqualTo("TransactionCategoryBalance[id=null]");
        }

        @Test
        @DisplayName("identity is the composite key alone, so a balance change does not change identity")
        void identityIsTheCompositeKeyAlone() {
            final TransactionCategoryBalance beforePosting = rowWithBalance("0.00");
            final TransactionCategoryBalance afterPosting = rowWithBalance("-250.00");

            assertThat(beforePosting)
                    .as("the two are the same row of TCATBALF at two moments of the posting run. JPA "
                            + "identity must be the primary key, or a row would leave a persistence "
                            + "context identity map the moment 2700-B-UPDATE-TCATBAL-REC at "
                            + "app/cbl/CBTRN02C.cbl:L526-L527 added an amount to it")
                    .isEqualTo(afterPosting)
                    .hasSameHashCodeAs(afterPosting);
            assertThat(beforePosting)
                    .as("a different key is a different row, and hashCode is stable across calls")
                    .isNotEqualTo(new TransactionCategoryBalance(
                            new TransactionCategoryBalanceId(2L, "01", 1), new BigDecimal("0.00")));
            assertThat(beforePosting.hashCode())
                    .as("repeated calls agree, so the row behaves in a hash container")
                    .isEqualTo(beforePosting.hashCode());
        }

        @Test
        @DisplayName("is unequal to null and to a foreign type without throwing")
        void isUnequalToNullAndToAForeignType() {
            final TransactionCategoryBalance row = rowWithBalance("1.00");

            assertThat(row.equals(null))
                    .as("the null branch is explicit rather than an incidental NullPointerException")
                    .isFalse();
            assertThat(row)
                    .as("an exact getClass() test rather than instanceof, so no unrelated type and no "
                            + "proxy of a different class can compare equal")
                    .isNotEqualTo(firstSeededKey())
                    .isNotEqualTo("TransactionCategoryBalance[id=1]");
        }

        @Test
        @DisplayName("holds no global mutable state: every static field of the entity and its key is final")
        void holdsNoGlobalMutableState() {
            final List<String> mutableStatics = new ArrayList<>();
            for (final Class<?> type : List.of(TransactionCategoryBalance.class,
                    TransactionCategoryBalanceId.class)) {
                for (final Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers())) {
                        mutableStatics.add(type.getSimpleName() + "." + field.getName());
                    }
                }
            }

            assertThat(mutableStatics)
                    .as("a mutable static on an entity is shared across every persistence context in the "
                            + "JVM, so one batch chunk could observe another's value. The bounds and the "
                            + "precision constants are static final, which is what makes them safe to "
                            + "share")
                    .isEmpty();
        }
    }

    /**
     * Hostile input and boundary conditions, per Rule 1 clauses A and B: every argument is treated as
     * untrusted and every boundary is driven on both sides.
     */
    @Nested
    @DisplayName("8. Hostile input: null, blank, short, long, off-by-one and out-of-range")
    class HostileInputAndBoundaries {

        @Test
        @DisplayName("refuses a null key, naming the COBOL group and its copybook line")
        void refusesANullKey() {
            assertThatIllegalArgumentException()
                    .as("both columns are NOT NULL and the source cannot produce a null: TRAN-CAT-KEY is "
                            + "17 fixed bytes. The diagnostic quotes the COBOL name and the exact copybook "
                            + "locator, so a reader is one search away from the contract rather than left "
                            + "with a paraphrase")
                    .isThrownBy(() -> new TransactionCategoryBalance(null, new BigDecimal("0.00")))
                    .withMessageContaining("TRAN-CAT-KEY")
                    .withMessageContaining("app/cpy/CVTRA01Y.cpy:L5")
                    .withMessageContaining(TABLE_NAME)
                    .withNoCause();
        }

        @Test
        @DisplayName("refuses a null balance, naming the picture clause and the mapped SQL type")
        void refusesANullBalance() {
            assertThatIllegalArgumentException()
                    .as("a packed numeric field always holds a value, so a null balance is not a state the "
                            + "source can reach. The diagnostic names PIC S9(09)V99 and NUMERIC(11,2) "
                            + "together, which is where a reader confronting the 11-versus-12 question "
                            + "will actually look")
                    .isThrownBy(() -> new TransactionCategoryBalance(firstSeededKey(), null))
                    .withMessageContaining("TRAN-CAT-BAL PIC S9(09)V99")
                    .withMessageContaining("app/cpy/CVTRA01Y.cpy:L9")
                    .withMessageContaining("NUMERIC(11,2)")
                    .withNoCause();
        }

        @Test
        @DisplayName("the mutators validate exactly as the constructor does, so neither is a way round it")
        void theMutatorsValidateAsTheConstructorDoes() {
            final TransactionCategoryBalance row = rowWithBalance("1.00");

            assertThatIllegalArgumentException()
                    .as("a guard on construction alone would be a guard the provider and any setter-using "
                            + "caller walks straight past")
                    .isThrownBy(() -> row.setId(null))
                    .withMessageContaining("TRAN-CAT-KEY")
                    .withNoCause();
            assertThatIllegalArgumentException()
                    .as("and the same for the balance, including its range")
                    .isThrownBy(() -> row.setBalance(new BigDecimal("1000000000.00")))
                    .withMessageContaining("TRAN-CAT-BAL PIC S9(09)V99")
                    .withNoCause();
            assertThat(row.getBalance())
                    .as("a refused mutation leaves the previous value in place rather than half-applying")
                    .isEqualByComparingTo("1.00");

            row.setBalance(new BigDecimal("-2.50"));
            assertThat(row.getBalance())
                    .as("an accepted mutation stores the value verbatim, sign included: this is the "
                            + "rewrite branch at app/cbl/CBTRN02C.cbl:L527 driving a balance negative")
                    .isEqualByComparingTo("-2.50");
        }

        @ParameterizedTest
        @CsvSource({"1, 16", "3, 18", "0, 15"})
        @DisplayName("refuses a type code that would make the composite key 16 or 18 bytes instead of 17")
        void refusesAKeyOfTheWrongWidth(final int typeCodeLength, final int resultingKeyWidth) {
            final String typeCode = "0".repeat(typeCodeLength);

            assertThat(ACCT_ID_WIDTH + typeCodeLength + CAT_CD_WIDTH)
                    .as("11 + %d + 4 is a %d-byte key", typeCodeLength, resultingKeyWidth)
                    .isEqualTo(resultingKeyWidth);
            assertThatIllegalArgumentException()
                    .as("TRANCAT-TYPE-CD occupies bytes 12 and 13 of a 17-byte key, so the requirement is "
                            + "an exact width and not a maximum: one character short would place "
                            + "TRANCAT-CD at byte 13 and one character long would push it to byte 15, and "
                            + "either way every subsequent read of the key is misaligned. The value is "
                            + "never padded or truncated to fit, because that would silently change the "
                            + "key")
                    .isThrownBy(() -> new TransactionCategoryBalanceId(1L, typeCode, 1))
                    .withMessageContaining("TRANCAT-TYPE-CD PIC X(02)")
                    .withMessageContaining("must be exactly 2 characters")
                    .withNoCause();
        }

        @Test
        @DisplayName("accepts a blank two-character type code, because PIC X(02) admits spaces")
        void acceptsABlankTwoCharacterTypeCode() {
            final TransactionCategoryBalance row = new TransactionCategoryBalance(
                    new TransactionCategoryBalanceId(1L, "  ", 1), new BigDecimal("0.00"));

            assertThat(row.getId().getTypeCd())
                    .as("an alphanumeric picture clause admits spaces, and a fixed-width field pads with "
                            + "them, so a blank code is well-formed input rather than hostile input. It is "
                            + "stored exactly as supplied - not trimmed to the empty string, which would "
                            + "then fail the exact-width rule, and not case folded")
                    .isEqualTo("  ")
                    .hasSize(TYPE_CD_WIDTH);
        }

        @ParameterizedTest
        @CsvSource({"0, 0001", "99999999999, 9999"})
        @DisplayName("accepts both extremes of the numeric key components")
        void acceptsBothExtremesOfTheNumericKeyComponents(final long accountId, final int categoryCode) {
            assertThatCode(() -> new TransactionCategoryBalance(
                    new TransactionCategoryBalanceId(accountId, "01", categoryCode), BigDecimal.ZERO))
                    .as("PIC 9(11) reaches 99999999999, which is why the account id is a Long and not an "
                            + "Integer, and PIC 9(04) reaches 9999. Both are unsigned display fields, so "
                            + "zero is their floor")
                    .doesNotThrowAnyException();
        }

        @ParameterizedTest
        @CsvSource({"-1, 1, TRANCAT-ACCT-ID", "100000000000, 1, TRANCAT-ACCT-ID", "1, -1, TRANCAT-CD",
                "1, 10000, TRANCAT-CD"})
        @DisplayName("refuses a numeric key component one step outside its picture clause")
        void refusesANumericKeyComponentOutsideItsPictureClause(final long accountId, final int categoryCode,
                final String expectedField) {
            assertThatIllegalArgumentException()
                    .as("an unsigned display field has no room for a negative value and no room for an "
                            + "extra digit. The diagnostic names %s so the failure points at a copybook "
                            + "field rather than at a column", expectedField)
                    .isThrownBy(() -> new TransactionCategoryBalanceId(accountId, "01", categoryCode))
                    .withMessageContaining(expectedField)
                    .withNoCause();
        }

        @Test
        @DisplayName("refuses a null key component, naming the component that was absent")
        void refusesANullKeyComponent() {
            assertThatIllegalArgumentException()
                    .as("a key identifies a row, so no component of it can be absent. Naming the specific "
                            + "component is what distinguishes this from a bare NullPointerException")
                    .isThrownBy(() -> new TransactionCategoryBalanceId(null, "01", 1))
                    .withMessageContaining("TRANCAT-ACCT-ID")
                    .withNoCause();
            assertThatIllegalArgumentException()
                    .as("and the same for the type code")
                    .isThrownBy(() -> new TransactionCategoryBalanceId(1L, null, 1))
                    .withMessageContaining("TRANCAT-TYPE-CD")
                    .withNoCause();
        }

        @Test
        @DisplayName("refuses an empty balance field and a negative scale when decoding")
        void refusesADegenerateDecodeRequest() {
            assertThatIllegalArgumentException()
                    .as("an empty field has no sign position, so there is nothing to decode. Returning "
                            + "zero for it would turn a truncated record into a legitimate-looking zero "
                            + "balance")
                    .isThrownBy(() -> FixtureLoader.decodeZonedDecimal("", BALANCE_SCALE))
                    .withMessageContaining("at least the sign position")
                    .withNoCause();
            assertThatIllegalArgumentException()
                    .as("a negative scale is not a picture clause any copybook can express")
                    .isThrownBy(() -> FixtureLoader.decodeZonedDecimal("0000000000{", -1))
                    .withMessageContaining("scale must not be negative")
                    .withNoCause();
        }
    }
}

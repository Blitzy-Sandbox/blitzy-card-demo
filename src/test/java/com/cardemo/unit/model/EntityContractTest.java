/*
 * ******************************************************************
 * Program     : EntityContractTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Proves the persistence contract of all eleven JPA
 *               entities against the frozen record-layout copybooks:
 *               column correspondence, constructor and accessor
 *               behaviour, the width and numeric-domain guards, and
 *               the JPA structural requirements.
 * Source      : app/cpy/CVACT01Y.cpy, CVACT02Y.cpy, CVACT03Y.cpy,
 *               CVCUS01Y.cpy, CVTRA01Y.cpy, CVTRA02Y.cpy,
 *               CVTRA03Y.cpy, CVTRA04Y.cpy, CVTRA05Y.cpy,
 *               CVTRA06Y.cpy, CSUSR01Y.cpy
 *               app/catlg/LISTCAT.txt
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
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.Card;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Customer;
import com.cardemo.model.entity.DailyTransaction;
import com.cardemo.model.entity.DisclosureGroup;
import com.cardemo.model.entity.Transaction;
import com.cardemo.model.entity.TransactionCategory;
import com.cardemo.model.entity.TransactionCategoryBalance;
import com.cardemo.model.entity.TransactionType;
import com.cardemo.model.entity.UserSecurity;
import com.cardemo.model.enums.UserType;
import jakarta.persistence.Column;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Proves the persistence contract of all eleven entities against the corpus that defines it.
 *
 * <p>Every width and every numeric domain asserted here is read from a record-layout copybook through
 * {@link RecordLayoutCopybook}, never from the entity's own constants. That distinction matters: an
 * assertion sourced from {@code @Column(length = CARD_NUMBER_WIDTH)} would prove only that the file agrees
 * with itself, and would keep agreeing if the constant drifted away from {@code PIC X(16)}.
 *
 * <p>The tests are reflection-driven because the eleven entities are eleven instances of one pattern -
 * fixed-width columns, private static guards, a protected no-arg constructor for the provider and a full
 * constructor for application code. Writing them out one at a time would produce eleven near-identical
 * classes and would still leave the newest field of the newest entity untested. Driving the pattern instead
 * means a field added later is covered the moment it is declared.
 */
@DisplayName("Entity contract: eleven entities against their record-layout copybooks")
final class EntityContractTest {

    /** The column every entity may carry for optimistic locking; it has no copybook counterpart. */
    private static final String VERSION_COLUMN = "version";

    /**
     * The one column in the schema that no copybook declares.
     *
     * <p>{@code daily_transaction} stages {@code AWS.M2.CARDDEMO.DALYTRAN.PS}, which
     * {@code app/catlg/LISTCAT.txt} records as {@code NONVSAM} - a physical sequential dataset with no key
     * of any kind - so {@code app/cpy/CVTRA06Y.cpy} supplies no field that could serve as a primary key.
     * The key is therefore the ingestion ordinal, assigned by the loader in read order and modelling
     * {@code WS-TRANSACTION-COUNT} at {@code app/cbl/CBTRN02C.cbl:L185}. It contributes no bytes to the
     * 350-byte record, so it is excluded from the geometry census in exactly the way {@link
     * #VERSION_COLUMN} is, rather than being matched against a copybook field that does not exist.</p>
     */
    private static final String INGEST_SEQUENCE_COLUMN = "ingest_seq";

    /**
     * The one column whose declared width deliberately diverges from its copybook picture.
     *
     * <p>{@code SEC-USR-PWD} is {@code PIC X(08)} at {@code app/cpy/CSUSR01Y.cpy:L21} because the source
     * held an eight-character <em>plaintext</em> password. The AAP mandates the replacement outright at
     * 0.5.1.3 - "the eight-character password field becomes a 60-character BCrypt hash column" - so the
     * divergence is a requirement rather than a defect. It is named here so that the width assertions can
     * exempt exactly this column and no other: any further divergence still fails.
     */
    private static final String CREDENTIAL_PROPERTY = "passwordHash";

    /** The width the credential column actually declares, from AAP 0.5.1.3. */
    private static final int CREDENTIAL_COLUMN_WIDTH = 60;

    /**
     * A syntactically valid BCrypt strength 10 digest: the {@code $2a$10$} tag plus 53 payload
     * characters, 60 in total, matching the shape {@code UserSecurity} enforces.
     */
    private static final String VALID_BCRYPT_HASH =
            "$2a$10$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1";

    /**
     * Each entity paired with the record-layout copybook that defines its geometry.
     *
     * @return entity type and copybook member name
     */
    static Stream<org.junit.jupiter.params.provider.Arguments> entitiesWithLayout() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(Account.class, "CVACT01Y"),
                org.junit.jupiter.params.provider.Arguments.of(Card.class, "CVACT02Y"),
                org.junit.jupiter.params.provider.Arguments.of(CardCrossReference.class, "CVACT03Y"),
                org.junit.jupiter.params.provider.Arguments.of(Customer.class, "CVCUS01Y"),
                org.junit.jupiter.params.provider.Arguments.of(TransactionCategoryBalance.class,
                        "CVTRA01Y"),
                org.junit.jupiter.params.provider.Arguments.of(DisclosureGroup.class, "CVTRA02Y"),
                org.junit.jupiter.params.provider.Arguments.of(TransactionType.class, "CVTRA03Y"),
                org.junit.jupiter.params.provider.Arguments.of(TransactionCategory.class, "CVTRA04Y"),
                org.junit.jupiter.params.provider.Arguments.of(Transaction.class, "CVTRA05Y"),
                org.junit.jupiter.params.provider.Arguments.of(DailyTransaction.class, "CVTRA06Y"),
                org.junit.jupiter.params.provider.Arguments.of(UserSecurity.class, "CSUSR01Y"));
    }

    /**
     * Every entity type under test.
     *
     * @return the eleven entity types
     */
    static Stream<Class<?>> allEntities() {
        return entitiesWithLayout().map(arguments -> (Class<?>) arguments.get()[0]);
    }

    /**
     * One persistent column, resolved against the copybook that defines it.
     *
     * @param field      the Java field carrying the mapping
     * @param columnName the database column name
     * @param cobolField the copybook field name derived from the column name
     * @param geometry   the copybook geometry, or {@code null} for the version column
     */
    private record MappedColumn(Field field, String columnName, String cobolField,
            RecordLayoutCopybook.Geometry geometry) {

        String property() {
            return field.getName();
        }
    }

    /**
     * Resolves every {@code @Column} field of an entity against its copybook.
     *
     * @param entity the entity type
     * @param member the copybook member name
     * @return the mapped columns, in field declaration order
     */
    private static List<MappedColumn> mappedColumns(final Class<?> entity, final String member) {
        final RecordLayoutCopybook layout = RecordLayoutCopybook.of(member);
        final List<MappedColumn> mapped = new ArrayList<>();
        for (final Field field : entity.getDeclaredFields()) {
            final Column column = field.getAnnotation(Column.class);
            if (column == null || Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            final String columnName = column.name();
            if (VERSION_COLUMN.equals(columnName) || INGEST_SEQUENCE_COLUMN.equals(columnName)) {
                mapped.add(new MappedColumn(field, columnName, columnName, null));
                continue;
            }
            final String cobolField = columnName.toUpperCase(java.util.Locale.ROOT).replace('_', '-');
            mapped.add(new MappedColumn(field, columnName, cobolField, layout.geometry(cobolField)));
        }
        return mapped;
    }

    /**
     * Columns whose copybook picture is numeric but which the entity deliberately models as
     * {@code String}.
     *
     * <p>Both hold digit strings that are never arithmetic and whose leading zeros are significant, so a
     * numeric Java type would silently drop them: a social security number is a fixed-width code, and the
     * FICO score is carried as written because twenty-one of the fifty customer fixture rows hold a value
     * below the notional 300 floor. They are bounded by the width probes in group 4 instead of the numeric
     * probes in group 5.</p>
     *
     * <p>{@code Card.cvvCode} was a third entry until finding F13 removed the operational verification
     * column outright. It is not replaced by a substitute entry: the census is derived from the entities
     * that exist, so a removed column must leave the list rather than linger as a name that resolves to
     * nothing.</p>
     *
     * <p>The list is a census, not a licence: {@link NumericGuard#theTextHeldNumericCensusIsWellFormed()}
     * proves every entry really is a numeric-picture column of {@code String} type, so it cannot become a
     * place to park a column that simply lacks a guard.</p>
     */
    private static final List<String> NUMERIC_PICTURE_HELD_AS_TEXT = List.of(
            "Customer.ssn", "Customer.ficoCreditScore");

    /**
     * Whether a column's Java type carries a numeric domain this test can bound.
     *
     * <p>{@code Integer} belongs here and was originally absent from all three numeric probes, which
     * meant the four-digit category codes on {@code Transaction} and {@code DailyTransaction} had no
     * range or signedness assertion anywhere in this class: the loops skipped them and still reported a
     * pass. All three probes now share this one definition, and
     * {@link #numericColumnCount(Class, String)} turns a future omission into a failure instead of a
     * silent skip.</p>
     *
     * @param type the column's Java type
     * @return {@code true} for {@code Long}, {@code Integer} and {@code BigDecimal}
     */
    private static boolean isNumeric(final Class<?> type) {
        return type.equals(Long.class) || type.equals(Integer.class) || type.equals(BigDecimal.class);
    }

    /**
     * The number of numeric columns a probe is expected to examine.
     *
     * <p>The expectation is taken from the copybook picture - {@code digits > 0} - and deliberately not
     * from {@link #isNumeric(Class)}. Deriving it from the same predicate the probe uses would make this
     * census vacuous: dropping a Java type from {@code isNumeric} would shrink the probe and the
     * expectation together and the assertion could never fail. That was verified by mutation, not
     * assumed - removing {@code Integer} from {@code isNumeric} left all tests green until this filter
     * was changed to consult the corpus instead. The copybook is the independent witness here.</p>
     *
     * @param entity the entity type
     * @param member the copybook member
     * @return the count of columns the copybook declares numeric that expose a setter
     */
    private static long numericColumnCount(final Class<?> entity, final String member) {
        return copybookColumns(entity, member).stream()
                .filter(column -> column.geometry().numeric())
                .filter(column -> !NUMERIC_PICTURE_HELD_AS_TEXT.contains(
                        entity.getSimpleName() + "." + column.property()))
                .filter(column -> setterFor(entity, column).isPresent())
                .count();
    }

    /**
     * Counts the columns a test is expected to examine.
     *
     * <p>Compared against the number actually examined, this is stronger than asserting the count is
     * merely positive: it fails both when a test examines nothing and when it quietly skips a column it
     * should have covered. Two entities - {@code TransactionCategoryBalance} and
     * {@code DisclosureGroup} - map only a single numeric column outside their composite key, so for
     * text-only assertions their expected count is legitimately zero.
     *
     * @param entity  the entity type
     * @param member  the copybook member
     * @param textOnly whether to count only {@code String} columns
     * @return the number of columns the test should examine
     */
    private static long eligibleColumnCount(final Class<?> entity, final String member,
            final boolean textOnly) {
        return copybookColumns(entity, member).stream()
                .filter(column -> !textOnly || column.field().getType().equals(String.class))
                .filter(column -> setterFor(entity, column).isPresent())
                .count();
    }

    /**
     * The identifier a column's guard message is expected to name.
     *
     * <p>Every guard names the Java property, which is what a caller passing a bad value recognises. The
     * credential is the deliberate exception: its guard names the {@code sec_usr_pwd} column instead,
     * because {@code passwordHash} is a Java-side rename and the schema is where the COBOL provenance has
     * to remain traceable.
     *
     * @param column the mapped column
     * @return the identifier the message must contain
     */
    private static String identifierInGuardMessage(final MappedColumn column) {
        return CREDENTIAL_PROPERTY.equals(column.property()) ? column.columnName() : column.property();
    }

    /** The width a column is expected to declare, honouring the one AAP-mandated divergence. */
    private static int expectedColumnWidth(final MappedColumn column) {
        return CREDENTIAL_PROPERTY.equals(column.property())
                ? CREDENTIAL_COLUMN_WIDTH
                : column.geometry().width();
    }

    /** The columns that carry copybook geometry, so the version column is excluded. */
    private static List<MappedColumn> copybookColumns(final Class<?> entity, final String member) {
        return mappedColumns(entity, member).stream().filter(c -> c.geometry() != null).toList();
    }

    /**
     * Produces a value that sits inside a column's declared domain.
     *
     * @param column the mapped column
     * @return a valid value for that column's Java type
     */
    private static Object validValue(final MappedColumn column) {
        return valueOfWidth(column, column.geometry().width());
    }

    private static Object valueOfWidth(final MappedColumn column, final int textLength) {
        final Class<?> type = column.field().getType();
        final RecordLayoutCopybook.Geometry geometry = column.geometry();
        if (type.equals(String.class)) {
            // The credential column holds a BCrypt digest and nothing else, so a run of zeros of the
            // right length is still refused. Supply a real digest, and a wider probe by lengthening it.
            if (CREDENTIAL_PROPERTY.equals(column.property())) {
                return textLength > CREDENTIAL_COLUMN_WIDTH
                        ? VALID_BCRYPT_HASH + "0".repeat(textLength - CREDENTIAL_COLUMN_WIDTH)
                        : VALID_BCRYPT_HASH;
            }
            return "0".repeat(textLength);
        }
        if (type.equals(Long.class)) {
            return Long.valueOf(1L);
        }
        if (type.equals(Integer.class)) {
            return Integer.valueOf(1);
        }
        if (type.equals(BigDecimal.class)) {
            return new BigDecimal("1").setScale(geometry.scale());
        }
        if (type.equals(UserType.class)) {
            return UserType.USER;
        }
        throw new AssertionError("no valid value defined for " + type.getName() + " on "
                + column.property() + "; extend valueOfWidth so the column is still exercised");
    }

    /** The largest value a numeric column can hold, from its copybook picture. */
    private static Object maximumValue(final MappedColumn column) {
        final RecordLayoutCopybook.Geometry geometry = column.geometry();
        if (column.field().getType().equals(Long.class)) {
            return Long.valueOf(BigDecimal.TEN.pow(geometry.digits()).longValue() - 1L);
        }
        if (column.field().getType().equals(Integer.class)) {
            return Integer.valueOf(BigDecimal.TEN.pow(geometry.digits()).intValue() - 1);
        }
        return BigDecimal.TEN.pow(geometry.digits())
                .subtract(BigDecimal.ONE.movePointLeft(geometry.scale()))
                .setScale(geometry.scale());
    }

    private static Optional<Method> setterFor(final Class<?> entity, final MappedColumn column) {
        final String name = "set" + Character.toUpperCase(column.property().charAt(0))
                + column.property().substring(1);
        try {
            return Optional.of(entity.getMethod(name, column.field().getType()));
        } catch (final NoSuchMethodException absent) {
            return Optional.empty();
        }
    }

    private static Optional<Method> getterFor(final Class<?> entity, final MappedColumn column) {
        final String name = "get" + Character.toUpperCase(column.property().charAt(0))
                + column.property().substring(1);
        try {
            return Optional.of(entity.getMethod(name));
        } catch (final NoSuchMethodException absent) {
            return Optional.empty();
        }
    }

    /** Instantiates via the provider's no-arg constructor, which JPA requires to be non-private. */
    private static Object blankInstance(final Class<?> entity) {
        try {
            final Constructor<?> constructor = entity.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (final ReflectiveOperationException failure) {
            throw new AssertionError("cannot instantiate " + entity.getName()
                    + " through its no-arg constructor", failure);
        }
    }

    /** The constructor application code uses: the public one taking the most parameters. */
    private static Optional<Constructor<?>> fullConstructor(final Class<?> entity) {
        return Stream.of(entity.getConstructors())
                .filter(candidate -> candidate.getParameterCount() > 0)
                .max(Comparator.comparingInt(Constructor::getParameterCount));
    }

    private static Object invoke(final Method method, final Object target, final Object... arguments) {
        try {
            return method.invoke(target, arguments);
        } catch (final IllegalAccessException failure) {
            throw new AssertionError("cannot invoke " + method.getName(), failure);
        } catch (final InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new AssertionError("cannot invoke " + method.getName(), failure);
        }
    }

    /**
     * The entities whose {@code equals} refuses to treat two unpopulated instances as equal, because it
     * guards the identity with {@code identity != null &&} rather than delegating to
     * {@link java.util.Objects#equals(Object, Object)}.
     *
     * <p>This list is a census of the package as it stands, not a rule the package declares. Seven
     * entities are null-tolerant and these four are null-hostile, and both forms are individually
     * defensible: the null-hostile form keeps two transient instances from colliding in a hash set,
     * which is the behaviour Hibernate documentation recommends. The split is pinned here so that it
     * is visible and so that moving an entity from one policy to the other cannot happen silently.
     * Equality is observable behaviour, so this test asserts what the entities do rather than
     * normalising them onto one policy.</p>
     */
    private static final List<String> NULL_HOSTILE_IDENTITY = List.of(
            "Account", "Customer", "DisclosureGroup", "TransactionCategory");

    /** The entities carrying an optimistic locking version column. */
    private static final List<String> VERSIONED = List.of(
            "Account", "Card", "Customer", "Transaction");

    static Stream<Class<?>> embeddedIdEntities() {
        return Stream.of(TransactionCategoryBalance.class, DisclosureGroup.class,
                TransactionCategory.class);
    }

    /**
     * The copybook member backing {@code entity}, read out of {@link #entitiesWithLayout()} so that the
     * pairing is declared exactly once and cannot drift between the two providers.
     */
    private static String layoutMemberOf(final Class<?> entity) {
        return entitiesWithLayout()
                .filter(arguments -> arguments.get()[0].equals(entity))
                .map(arguments -> (String) arguments.get()[1])
                .findFirst()
                .orElseThrow(() -> new AssertionError(entity.getSimpleName()
                        + " has no copybook member in entitiesWithLayout()"));
    }

    /** The field carrying {@code @Id} or {@code @EmbeddedId}, which is what equality is built on. */
    private static Field identityField(final Class<?> entity) {
        return Stream.of(entity.getDeclaredFields())
                .filter(field -> field.isAnnotationPresent(jakarta.persistence.Id.class)
                        || field.isAnnotationPresent(jakarta.persistence.EmbeddedId.class))
                .findFirst()
                .orElseThrow(() -> new AssertionError(entity.getSimpleName()
                        + " declares neither @Id nor @EmbeddedId, so its identity cannot be derived"));
    }

    /**
     * A valid identity for {@code entity}, with {@code variant} selecting a distinct one. Strings are
     * produced at the mapped column's exact width so that the width guard accepts them.
     */
    private static Object identityValue(final Class<?> entity, final int variant) {
        final Field field = identityField(entity);
        if (field.isAnnotationPresent(jakarta.persistence.EmbeddedId.class)) {
            return CompositeKeys.of(field.getType(), variant);
        }
        if (field.getType().equals(Long.class)) {
            return Long.valueOf(1L + variant);
        }
        final int width = mappedColumns(entity, layoutMemberOf(entity)).stream()
                .filter(column -> column.field().equals(field))
                .findFirst()
                .map(EntityContractTest::expectedColumnWidth)
                .orElseThrow(() -> new AssertionError(
                        entity.getSimpleName() + " identity is not a mapped column"));
        return "0".repeat(width - 1) + variant;
    }

    /** Sets the identity through its setter, which every entity exposes for the provider's benefit. */
    private static Object instanceWithIdentity(final Class<?> entity, final int variant) {
        final Object instance = blankInstance(entity);
        final Field field = identityField(entity);
        final String name = "set" + Character.toUpperCase(field.getName().charAt(0))
                + field.getName().substring(1);
        try {
            invoke(entity.getMethod(name, field.getType()), instance, identityValue(entity, variant));
        } catch (final NoSuchMethodException absent) {
            throw new AssertionError(entity.getSimpleName() + " exposes no " + name, absent);
        }
        return instance;
    }

    @Nested
    @DisplayName("1. Every column corresponds to a copybook field of the same width")
    final class ColumnCorrespondence {

        @ParameterizedTest(name = "{0} against {1}")
        @MethodSource("com.cardemo.unit.model.EntityContractTest#entitiesWithLayout")
        @DisplayName("every column name resolves to a declared copybook field")
        void everyColumnResolves(final Class<?> entity, final String member) {
            // mappedColumns throws from RecordLayoutCopybook.geometry if a name does not resolve, and that
            // message lists every declared field, so a drifted column name fails loudly and informatively.
            assertThat(copybookColumns(entity, member))
                    .as("%s must map at least one column onto %s", entity.getSimpleName(), member)
                    .isNotEmpty();
        }

        @ParameterizedTest(name = "{0} against {1}")
        @MethodSource("com.cardemo.unit.model.EntityContractTest#entitiesWithLayout")
        @DisplayName("every text column's declared length equals the copybook width")
        void textLengthsMatchTheCopybook(final Class<?> entity, final String member) {
            for (final MappedColumn column : copybookColumns(entity, member)) {
                if (!column.field().getType().equals(String.class)) {
                    continue;
                }
                assertThat(column.field().getAnnotation(Column.class).length())
                        .as("%s.%s maps %s %s, so the column length must match the picture unless the "
                                + "AAP mandates otherwise", entity.getSimpleName(), column.property(),
                                column.cobolField(), column.geometry().picture())
                        .isEqualTo(expectedColumnWidth(column));
            }
        }

        @ParameterizedTest(name = "{0} against {1}")
        @MethodSource("com.cardemo.unit.model.EntityContractTest#entitiesWithLayout")
        @DisplayName("every decimal column's precision and scale equal the copybook picture")
        void decimalPrecisionMatchesTheCopybook(final Class<?> entity, final String member) {
            for (final MappedColumn column : copybookColumns(entity, member)) {
                if (!column.field().getType().equals(BigDecimal.class)) {
                    continue;
                }
                final Column annotation = column.field().getAnnotation(Column.class);
                if (!annotation.columnDefinition().isEmpty()) {
                    // Some columns state their SQL type outright rather than through precision and scale;
                    // for those the columnDefinition is the assertion target, checked below.
                    assertThat(annotation.columnDefinition())
                            .as("%s.%s maps %s", entity.getSimpleName(), column.property(),
                                    column.geometry().picture())
                            .contains(String.valueOf(column.geometry().digits()
                                    + column.geometry().scale()));
                    continue;
                }
                assertThat(annotation.scale())
                        .as("%s.%s maps %s", entity.getSimpleName(), column.property(),
                                column.geometry().picture())
                        .isEqualTo(column.geometry().scale());
                assertThat(annotation.precision())
                        .as("%s.%s precision must be integer digits plus scale",
                                entity.getSimpleName(), column.property())
                        .isEqualTo(column.geometry().digits() + column.geometry().scale());
            }
        }

        @Test
        @DisplayName("exactly one column widens beyond its copybook picture, and it is the credential")
        void onlyTheCredentialWidens() {
            final List<String> widened = new ArrayList<>();
            entitiesWithLayout().forEach(arguments -> {
                final Class<?> entity = (Class<?>) arguments.get()[0];
                for (final MappedColumn column : copybookColumns(entity, (String) arguments.get()[1])) {
                    if (!column.field().getType().equals(String.class)) {
                        continue;
                    }
                    if (column.field().getAnnotation(Column.class).length()
                            != column.geometry().width()) {
                        widened.add(entity.getSimpleName() + "." + column.property());
                    }
                }
            });

            // SEC-USR-PWD is PIC X(08) because the source held plaintext; AAP 0.5.1.3 replaces it with a
            // 60-character BCrypt hash column. That is the only sanctioned divergence, so the list is
            // asserted exactly rather than merely being checked for containment.
            assertThat(widened).containsExactly("UserSecurity." + CREDENTIAL_PROPERTY);
        }

        @Test
        @DisplayName("the two decimal precisions that differ from the common case are honoured")
        void theTwoUnusualPrecisions() {
            // A blanket NUMERIC(12,2) would be wrong in exactly these two places, so they are named.
            final MappedColumn balance = copybookColumns(TransactionCategoryBalance.class, "CVTRA01Y")
                    .stream().filter(c -> c.field().getType().equals(BigDecimal.class))
                    .findFirst().orElseThrow();
            assertThat(balance.field().getAnnotation(Column.class).precision())
                    .as("TRAN-CAT-BAL is S9(09)V99")
                    .isEqualTo(11);

            final MappedColumn rate = copybookColumns(DisclosureGroup.class, "CVTRA02Y")
                    .stream().filter(c -> c.field().getType().equals(BigDecimal.class))
                    .findFirst().orElseThrow();
            assertThat(rate.field().getAnnotation(Column.class).precision())
                    .as("DIS-INT-RATE is S9(04)V99")
                    .isEqualTo(6);
        }
    }

    @Nested
    @DisplayName("2. The full constructor accepts a valid record and every accessor returns it")
    final class FullConstructor {

        @ParameterizedTest(name = "{0} against {1}")
        @MethodSource("com.cardemo.unit.model.EntityContractTest#entitiesWithLayout")
        @DisplayName("a record built at exactly the declared widths is accepted and reads back unchanged")
        void constructorAcceptsAWidthExactRecord(final Class<?> entity, final String member) {
            final Optional<Constructor<?>> constructor = fullConstructor(entity);
            if (constructor.isEmpty()) {
                return;
            }
            final Map<String, MappedColumn> byProperty = new LinkedHashMap<>();
            copybookColumns(entity, member).forEach(c -> byProperty.put(c.property(), c));

            final Object[] arguments = new Object[constructor.orElseThrow().getParameterCount()];
            final var parameters = constructor.orElseThrow().getParameters();
            for (int slot = 0; slot < arguments.length; slot++) {
                final MappedColumn column = byProperty.get(parameters[slot].getName());
                if (column == null) {
                    return;
                }
                arguments[slot] = validValue(column);
            }

            final Object instance;
            try {
                instance = constructor.orElseThrow().newInstance(arguments);
            } catch (final ReflectiveOperationException failure) {
                throw new AssertionError("cannot construct " + entity.getSimpleName(), failure);
            }

            for (int slot = 0; slot < arguments.length; slot++) {
                final MappedColumn column = byProperty.get(parameters[slot].getName());
                final Optional<Method> getter = getterFor(entity, column);
                if (getter.isEmpty()) {
                    continue;
                }
                assertThat(invoke(getter.orElseThrow(), instance))
                        .as("%s.%s must read back exactly what the constructor was given",
                                entity.getSimpleName(), column.property())
                        .isEqualTo(arguments[slot]);
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.cardemo.unit.model.EntityContractTest#allEntities")
        @DisplayName("the provider's no-arg constructor exists and is not private")
        void noArgConstructorIsAvailableToTheProvider(final Class<?> entity) {
            final Constructor<?> constructor;
            try {
                constructor = entity.getDeclaredConstructor();
            } catch (final NoSuchMethodException absent) {
                throw new AssertionError(entity.getSimpleName()
                        + " declares no no-arg constructor, which Hibernate requires", absent);
            }
            assertThat(Modifier.isPrivate(constructor.getModifiers()))
                    .as("%s's no-arg constructor must be reachable by the provider",
                            entity.getSimpleName())
                    .isFalse();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.cardemo.unit.model.EntityContractTest#allEntities")
        @DisplayName("the entity is not final, so the provider can proxy it")
        void entityIsNotFinal(final Class<?> entity) {
            assertThat(Modifier.isFinal(entity.getModifiers()))
                    .as("%s must not be final: Hibernate subclasses entities for lazy proxies, which is "
                            + "also why every guard is a private static helper rather than an "
                            + "overridable method", entity.getSimpleName())
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("3. Setters round-trip and touch only their own field")
    final class SetterContract {

        @ParameterizedTest(name = "{0} against {1}")
        @MethodSource("com.cardemo.unit.model.EntityContractTest#entitiesWithLayout")
        @DisplayName("every setter's value is readable through its own getter")
        void settersRoundTrip(final Class<?> entity, final String member) {
            int examined = 0;
            for (final MappedColumn column : copybookColumns(entity, member)) {
                final Optional<Method> setter = setterFor(entity, column);
                final Optional<Method> getter = getterFor(entity, column);
                if (setter.isEmpty() || getter.isEmpty()) {
                    continue;
                }
                final Object instance = blankInstance(entity);
                final Object value = validValue(column);

                invoke(setter.orElseThrow(), instance, value);

                assertThat(invoke(getter.orElseThrow(), instance))
                        .as("%s.%s round-trip", entity.getSimpleName(), column.property())
                        .isEqualTo(value);
                examined++;
            }
            assertThat(examined)
                    .as("%s must expose at least one setter and getter pair", entity.getSimpleName())
                    .isPositive();
        }

        @ParameterizedTest(name = "{0} against {1}")
        @MethodSource("com.cardemo.unit.model.EntityContractTest#entitiesWithLayout")
        @DisplayName("each setter leaves every other mapped field untouched, so no pair is transposed")
        void settersDoNotTransposeFields(final Class<?> entity, final String member) {
            final List<MappedColumn> columns = copybookColumns(entity, member);
            for (final MappedColumn column : columns) {
                final Optional<Method> setter = setterFor(entity, column);
                if (setter.isEmpty()) {
                    continue;
                }
                final Object instance = blankInstance(entity);
                invoke(setter.orElseThrow(), instance, validValue(column));

                for (final MappedColumn other : columns) {
                    if (other.property().equals(column.property())) {
                        continue;
                    }
                    final Optional<Method> otherGetter = getterFor(entity, other);
                    if (otherGetter.isEmpty()) {
                        continue;
                    }
                    assertThat(invoke(otherGetter.orElseThrow(), instance))
                            .as("%s.set%s must not have written the field read by get%s",
                                    entity.getSimpleName(), column.property(), other.property())
                            .isNull();
                }
            }
        }
    }

    @Nested
    @DisplayName("4. The width guard refuses null and over-width, and accepts everything else")
    final class WidthGuard {

        @ParameterizedTest(name = "{0} against {1}")
        @MethodSource("com.cardemo.unit.model.EntityContractTest#entitiesWithLayout")
        @DisplayName("a text value one character wider than the picture is refused")
        void oneCharacterTooWideIsRefused(final Class<?> entity, final String member) {
            int examined = 0;
            for (final MappedColumn column : copybookColumns(entity, member)) {
                if (!column.field().getType().equals(String.class)) {
                    continue;
                }
                final Optional<Method> setter = setterFor(entity, column);
                if (setter.isEmpty()) {
                    continue;
                }
                final Object instance = blankInstance(entity);
                // One past the width the column DECLARES, not the width the picture declares. The two
                // differ only for the credential, whose column is 60 wide by AAP mandate; probing at nine
                // characters there would present a perfectly valid digest and be rightly accepted.
                final Object tooWide = valueOfWidth(column, expectedColumnWidth(column) + 1);

                assertThatExceptionOfType(IllegalArgumentException.class)
                        .as("%s.%s maps %s %s and declares width %d", entity.getSimpleName(),
                                column.property(), column.cobolField(),
                                column.geometry().picture(), expectedColumnWidth(column))
                        .isThrownBy(() -> invoke(setter.orElseThrow(), instance, tooWide))
                        .withMessageContaining(identifierInGuardMessage(column));
                examined++;
            }
            assertThat(examined)
                    .as("%s must have every text column probed one character over its picture",
                            entity.getSimpleName())
                    .isEqualTo((int) eligibleColumnCount(entity, member, true));
        }

        @ParameterizedTest(name = "{0} against {1}")
        @MethodSource("com.cardemo.unit.model.EntityContractTest#entitiesWithLayout")
        @DisplayName("a null is refused on every mapped column, because every column is NOT NULL")
        void nullIsRefused(final Class<?> entity, final String member) {
            int examined = 0;
            for (final MappedColumn column : copybookColumns(entity, member)) {
                final Optional<Method> setter = setterFor(entity, column);
                if (setter.isEmpty()) {
                    continue;
                }
                final Object instance = blankInstance(entity);

                assertThatExceptionOfType(IllegalArgumentException.class)
                        .as("%s.%s maps a NOT NULL column", entity.getSimpleName(), column.property())
                        .isThrownBy(() -> invoke(setter.orElseThrow(), instance,
                                new Object[] {null}))
                        .withMessageContaining(identifierInGuardMessage(column));
                examined++;
            }
            assertThat(examined)
                    .as("%s must have every mapped column probed with null", entity.getSimpleName())
                    .isEqualTo((int) eligibleColumnCount(entity, member, false));
        }

        @ParameterizedTest(name = "{0} against {1}")
        @MethodSource("com.cardemo.unit.model.EntityContractTest#entitiesWithLayout")
        @DisplayName("blank and short values are accepted unchanged, since a fixed-width record allows "
                + "them")
        void blankAndShortAreAccepted(final Class<?> entity, final String member) {
            int examined = 0;
            for (final MappedColumn column : copybookColumns(entity, member)) {
                if (!column.field().getType().equals(String.class)) {
                    continue;
                }
                if (CREDENTIAL_PROPERTY.equals(column.property())) {
                    // The credential is the one text column where blank is NOT legitimate: it holds a
                    // BCrypt digest or nothing, so a run of spaces is correctly refused. Exempting it here
                    // keeps this assertion about fixed-width text and leaves the credential's own shape
                    // rule to the tests that own it.
                    continue;
                }
                final Optional<Method> setter = setterFor(entity, column);
                final Optional<Method> getter = getterFor(entity, column);
                if (setter.isEmpty() || getter.isEmpty()) {
                    continue;
                }
                final String blank = " ".repeat(column.geometry().width());
                final Object instance = blankInstance(entity);

                invoke(setter.orElseThrow(), instance, blank);

                assertThat(invoke(getter.orElseThrow(), instance))
                        .as("%s.%s must retain a blank verbatim, with no trim, pad or case fold",
                                entity.getSimpleName(), column.property())
                        .isEqualTo(blank);
                examined++;
            }
            assertThat(examined)
                    .as("%s must have every non-credential text column probed with a blank",
                            entity.getSimpleName())
                    .isEqualTo((int) eligibleColumnCount(entity, member, true)
                            - (UserSecurity.class.equals(entity) ? 1 : 0));
        }
    }

    @Nested
    @DisplayName("5. The numeric guard bounds each column by its own picture")
    final class NumericGuard {

        @ParameterizedTest(name = "{0} against {1}")
        @MethodSource("com.cardemo.unit.model.EntityContractTest#entitiesWithLayout")
        @DisplayName("the largest value the picture can hold is accepted")
        void maximumIsAccepted(final Class<?> entity, final String member) {
            for (final MappedColumn column : copybookColumns(entity, member)) {
                final Class<?> type = column.field().getType();
                if (!isNumeric(type)) {
                    continue;
                }
                final Optional<Method> setter = setterFor(entity, column);
                final Optional<Method> getter = getterFor(entity, column);
                if (setter.isEmpty() || getter.isEmpty()) {
                    continue;
                }
                final Object instance = blankInstance(entity);
                final Object maximum = maximumValue(column);

                invoke(setter.orElseThrow(), instance, maximum);

                assertThat(invoke(getter.orElseThrow(), instance))
                        .as("%s.%s maps %s, so %s is representable", entity.getSimpleName(),
                                column.property(), column.geometry().picture(), maximum)
                        .isEqualTo(maximum);
            }
        }

        @ParameterizedTest(name = "{0} against {1}")
        @MethodSource("com.cardemo.unit.model.EntityContractTest#entitiesWithLayout")
        @DisplayName("one beyond the largest representable value is refused")
        void beyondTheMaximumIsRefused(final Class<?> entity, final String member) {
            int examined = 0;
            for (final MappedColumn column : copybookColumns(entity, member)) {
                final Class<?> type = column.field().getType();
                if (!isNumeric(type)) {
                    continue;
                }
                final Optional<Method> setter = setterFor(entity, column);
                if (setter.isEmpty()) {
                    continue;
                }
                final Object instance = blankInstance(entity);
                final Object beyond;
                if (type.equals(Long.class)) {
                    beyond = Long.valueOf((Long) maximumValue(column) + 1L);
                } else if (type.equals(Integer.class)) {
                    beyond = Integer.valueOf((Integer) maximumValue(column) + 1);
                } else {
                    beyond = ((BigDecimal) maximumValue(column))
                            .add(BigDecimal.ONE.movePointLeft(column.geometry().scale()));
                }
                examined++;

                assertThatExceptionOfType(IllegalArgumentException.class)
                        .as("%s.%s maps %s", entity.getSimpleName(), column.property(),
                                column.geometry().picture())
                        .isThrownBy(() -> invoke(setter.orElseThrow(), instance, beyond))
                        .withMessageContaining(column.property());
            }
            assertThat(examined)
                    .as("%s must have every numeric column probed beyond its maximum",
                            entity.getSimpleName())
                    .isEqualTo((int) numericColumnCount(entity, member));
        }

        @Test
        @DisplayName("every column censused as text-held really is a numeric picture of String type")
        void theTextHeldNumericCensusIsWellFormed() {
            final List<String> resolved = new ArrayList<>();
            entitiesWithLayout().forEach(arguments -> {
                final Class<?> entity = (Class<?>) arguments.get()[0];
                for (final MappedColumn column : copybookColumns(entity, (String) arguments.get()[1])) {
                    final String name = entity.getSimpleName() + "." + column.property();
                    if (!NUMERIC_PICTURE_HELD_AS_TEXT.contains(name)) {
                        continue;
                    }
                    assertThat(column.geometry().numeric())
                            .as("%s is censused as a numeric picture held as text", name).isTrue();
                    assertThat(column.field().getType())
                            .as("%s is censused as held in a String", name).isEqualTo(String.class);
                    resolved.add(name);
                }
            });

            assertThat(resolved)
                    .as("every censused name must resolve to a real column, so the list cannot rot")
                    .containsExactlyInAnyOrderElementsOf(NUMERIC_PICTURE_HELD_AS_TEXT);
        }

        @ParameterizedTest(name = "{0} against {1}")
        @MethodSource("com.cardemo.unit.model.EntityContractTest#entitiesWithLayout")
        @DisplayName("a scale finer than the picture declares is refused, because PostgreSQL would "
                + "round it silently")
        void tooFineAScaleIsRefused(final Class<?> entity, final String member) {
            for (final MappedColumn column : copybookColumns(entity, member)) {
                if (!column.field().getType().equals(BigDecimal.class)) {
                    continue;
                }
                final Optional<Method> setter = setterFor(entity, column);
                if (setter.isEmpty()) {
                    continue;
                }
                final Object instance = blankInstance(entity);
                final BigDecimal tooFine =
                        BigDecimal.ONE.setScale(column.geometry().scale() + 1);

                // PostgreSQL rounds an over-scaled NUMERIC(p,2) insert half away from zero rather than
                // refusing it, so a value carrying a third decimal would be silently altered on the way
                // in. Refusing it here is what makes the stored value equal the supplied one.
                assertThatExceptionOfType(IllegalArgumentException.class)
                        .as("%s.%s maps %s", entity.getSimpleName(), column.property(),
                                column.geometry().picture())
                        .isThrownBy(() -> invoke(setter.orElseThrow(), instance, tooFine))
                        .withMessageContaining(column.property());
            }
        }

        @ParameterizedTest(name = "{0} against {1}")
        @MethodSource("com.cardemo.unit.model.EntityContractTest#entitiesWithLayout")
        @DisplayName("a negative amount is accepted where the picture is signed, and refused where it "
                + "is not")
        void signednessIsHonoured(final Class<?> entity, final String member) {
            for (final MappedColumn column : copybookColumns(entity, member)) {
                final Class<?> type = column.field().getType();
                if (!isNumeric(type)) {
                    continue;
                }
                final Optional<Method> setter = setterFor(entity, column);
                final Optional<Method> getter = getterFor(entity, column);
                if (setter.isEmpty() || getter.isEmpty()) {
                    continue;
                }
                final Object instance = blankInstance(entity);

                if (column.geometry().signed() && type.equals(Integer.class)) {
                    // No signed picture in this corpus maps to Integer. If one ever does, say so rather
                    // than pushing a BigDecimal into an Integer setter and reporting a confusing failure.
                    throw new AssertionError(entity.getSimpleName() + "." + column.property()
                            + " maps the signed " + column.geometry().picture()
                            + " to Integer; extend this probe for that shape");
                }
                if (column.geometry().signed()) {
                    // The posting path adds negative amounts to the cycle debit accumulator, so a
                    // negative value in a signed field is legitimate business data and must survive.
                    final BigDecimal negative =
                            BigDecimal.ONE.negate().setScale(column.geometry().scale());
                    invoke(setter.orElseThrow(), instance, negative);
                    assertThat(invoke(getter.orElseThrow(), instance))
                            .as("%s.%s maps the signed %s", entity.getSimpleName(), column.property(),
                                    column.geometry().picture())
                            .isEqualTo(negative);
                } else {
                    final Object negative;
                    if (type.equals(Long.class)) {
                        negative = Long.valueOf(-1L);
                    } else if (type.equals(Integer.class)) {
                        negative = Integer.valueOf(-1);
                    } else {
                        negative = BigDecimal.ONE.negate().setScale(column.geometry().scale());
                    }
                    assertThatExceptionOfType(IllegalArgumentException.class)
                            .as("%s.%s maps the unsigned %s", entity.getSimpleName(),
                                    column.property(), column.geometry().picture())
                            .isThrownBy(() -> invoke(setter.orElseThrow(), instance, negative))
                            .withMessageContaining(column.property());
                }
            }
        }
    }

    @Nested
    @DisplayName("6. Diagnostic renderings withhold what the corpus marks sensitive")
    final class Renderings {

        @ParameterizedTest(name = "{0} against {1}")
        @MethodSource("com.cardemo.unit.model.EntityContractTest#entitiesWithLayout")
        @DisplayName("every entity overrides toString rather than inheriting the identity rendering")
        void toStringIsOverridden(final Class<?> entity, final String member) {
            final Object instance = blankInstance(entity);

            assertThat(instance.toString())
                    .as("%s must not fall back to Object's identity rendering",
                            entity.getSimpleName())
                    .doesNotContain("@")
                    .startsWith(entity.getSimpleName());
        }

        @Test
        @DisplayName("the card number never reaches a card rendering")
        void cardNumberIsWithheld() {
            final Card card = new Card("4111111111111111", 1L, "007", "A CARDHOLDER",
                    "2025-01-01", "Y");

            assertThat(card.toString())
                    .as("not in full, not masked, and not as a last-four")
                    .doesNotContain("4111111111111111")
                    .doesNotContain("1111")
                    .doesNotContain("A CARDHOLDER");
            assertThat(card.getCardNumber())
                    .as("the accessor still returns it; withholding is the rendering's job")
                    .isEqualTo("4111111111111111");
        }

        @Test
        @DisplayName("the credential never reaches a user rendering")
        void credentialIsWithheld() {
            final UserSecurity user = new UserSecurity("STDUSR01", "FNAMEAA6", "LNAME6",
                    VALID_BCRYPT_HASH, UserType.USER);

            assertThat(user.toString())
                    .as("neither the digest nor its version tag may reach a rendering")
                    .doesNotContain(VALID_BCRYPT_HASH)
                    .doesNotContain("$2a$");
            assertThat(user.getPasswordHash())
                    .as("the accessor still returns it; withholding is the rendering's job")
                    .isEqualTo(VALID_BCRYPT_HASH);
        }
    }

    @Nested
    @DisplayName("7. Identity semantics are consistent, and the null-identity split is pinned")
    final class IdentitySemantics {

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.cardemo.unit.model.EntityContractTest#allEntities")
        @DisplayName("an instance equals itself and no instance of another class")
        void equalsIsReflexiveAndTypeSafe(final Class<?> entity) {
            final Object populated = instanceWithIdentity(entity, 0);

            assertThat(populated).as("%s must equal itself", entity.getSimpleName())
                    .isEqualTo(populated);
            assertThat(populated.equals(null))
                    .as("%s must not equal null", entity.getSimpleName()).isFalse();
            assertThat(populated.equals("a string"))
                    .as("%s must not equal a foreign type", entity.getSimpleName()).isFalse();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.cardemo.unit.model.EntityContractTest#allEntities")
        @DisplayName("two instances sharing an identity are equal and share a hash")
        void sameIdentityMeansEqual(final Class<?> entity) {
            final Object first = instanceWithIdentity(entity, 0);
            final Object second = instanceWithIdentity(entity, 0);

            assertThat(first).as("%s instances with the same identity must be equal",
                    entity.getSimpleName()).isEqualTo(second);
            assertThat(second).as("equality must be symmetric on %s", entity.getSimpleName())
                    .isEqualTo(first);
            assertThat(first.hashCode())
                    .as("%s equal instances must share a hash, or a hash set breaks",
                            entity.getSimpleName())
                    .isEqualTo(second.hashCode());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.cardemo.unit.model.EntityContractTest#allEntities")
        @DisplayName("two instances with different identities are unequal")
        void differentIdentityMeansUnequal(final Class<?> entity) {
            assertThat(instanceWithIdentity(entity, 0))
                    .as("%s instances with different identities must not be equal",
                            entity.getSimpleName())
                    .isNotEqualTo(instanceWithIdentity(entity, 1));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.cardemo.unit.model.EntityContractTest#allEntities")
        @DisplayName("a hash does not change between calls on an unchanged instance")
        void hashCodeIsStable(final Class<?> entity) {
            final Object populated = instanceWithIdentity(entity, 0);

            assertThat(populated.hashCode()).isEqualTo(populated.hashCode());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.cardemo.unit.model.EntityContractTest#allEntities")
        @DisplayName("the unpopulated-identity policy is exactly the censused split")
        void nullIdentityPolicyIsAsCensused(final Class<?> entity) {
            // Two instances the provider has created but not yet populated. Whether they compare equal
            // is a real behavioural difference within this package, so it is asserted rather than
            // assumed: four entities refuse, seven accept. See NULL_HOSTILE_IDENTITY.
            final boolean equal = blankInstance(entity).equals(blankInstance(entity));

            assertThat(equal)
                    .as("%s is censused as %s; if this failed, its equals policy changed",
                            entity.getSimpleName(),
                            NULL_HOSTILE_IDENTITY.contains(entity.getSimpleName())
                                    ? "null-hostile" : "null-tolerant")
                    .isEqualTo(!NULL_HOSTILE_IDENTITY.contains(entity.getSimpleName()));
        }

        @Test
        @DisplayName("the censused split names only entities that exist, and both sides are populated")
        void theCensusIsWellFormed() {
            final List<String> names = allEntities().map(Class::getSimpleName).toList();

            assertThat(names).as("every censused name must be an entity of this package")
                    .containsAll(NULL_HOSTILE_IDENTITY);
            assertThat(NULL_HOSTILE_IDENTITY).hasSize(4);
            assertThat(names).hasSize(11);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.cardemo.unit.model.EntityContractTest#allEntities")
        @DisplayName("the version accessor round-trips, and accepts null for a transient instance")
        void versionRoundTripsIncludingNull(final Class<?> entity) {
            final boolean versioned = VERSIONED.contains(entity.getSimpleName());
            final Optional<Method> getter = Stream.of(entity.getMethods())
                    .filter(method -> "getVersion".equals(method.getName())).findFirst();

            assertThat(getter.isPresent())
                    .as("%s is censused as %s", entity.getSimpleName(),
                            versioned ? "versioned" : "unversioned")
                    .isEqualTo(versioned);
            if (!versioned) {
                return;
            }
            final Object instance = blankInstance(entity);
            final Method setter;
            try {
                setter = entity.getMethod("setVersion", Long.class);
            } catch (final NoSuchMethodException absent) {
                throw new AssertionError(entity.getSimpleName() + " exposes no setVersion", absent);
            }
            assertThat(invoke(getter.get(), instance))
                    .as("a transient %s has no version yet", entity.getSimpleName()).isNull();
            invoke(setter, instance, Long.valueOf(7L));
            assertThat(invoke(getter.get(), instance)).isEqualTo(7L);
            invoke(setter, instance, (Object) null);
            assertThat(invoke(getter.get(), instance))
                    .as("null must be accepted, unlike the mapped data columns").isNull();
        }
    }

    @Nested
    @DisplayName("8. The three composite-key entities carry their key through the mapped surface")
    final class EmbeddedIdEntities {

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.cardemo.unit.model.EntityContractTest#embeddedIdEntities")
        @DisplayName("the key accessor returns the key the setter stored, unaltered")
        void keyRoundTrips(final Class<?> entity) {
            final Object instance = blankInstance(entity);
            final Object key = identityValue(entity, 0);
            final Field field = identityField(entity);
            final Method getter;
            final Method setter;
            try {
                getter = entity.getMethod("getId");
                setter = entity.getMethod("setId", field.getType());
            } catch (final NoSuchMethodException absent) {
                throw new AssertionError(entity.getSimpleName() + " exposes no getId/setId", absent);
            }

            invoke(setter, instance, key);

            assertThat(invoke(getter, instance))
                    .as("%s must carry the key instance it was given", entity.getSimpleName())
                    .isSameAs(key);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.cardemo.unit.model.EntityContractTest#embeddedIdEntities")
        @DisplayName("a null key is refused by the setter, naming the composite key in the message")
        void nullKeyIsRefused(final Class<?> entity) {
            final Object instance = blankInstance(entity);
            final Method setter;
            try {
                setter = entity.getMethod("setId", identityField(entity).getType());
            } catch (final NoSuchMethodException absent) {
                throw new AssertionError(entity.getSimpleName() + " exposes no setId", absent);
            }

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("%s must refuse a null composite key", entity.getSimpleName())
                    .isThrownBy(() -> invoke(setter, instance, (Object) null))
                    .withMessageContaining("id")
                    .withMessageContaining("null");
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.cardemo.unit.model.EntityContractTest#embeddedIdEntities")
        @DisplayName("the full constructor accepts a valid key and every accessor returns it")
        void constructorAcceptsAValidKey(final Class<?> entity) {
            final Constructor<?> constructor = fullConstructor(entity)
                    .orElseThrow(() -> new AssertionError(
                            entity.getSimpleName() + " declares no public constructor"));
            final Object[] arguments = new Object[constructor.getParameterCount()];
            for (int index = 0; index < arguments.length; index++) {
                final Class<?> type = constructor.getParameterTypes()[index];
                if (type.equals(identityField(entity).getType())) {
                    arguments[index] = identityValue(entity, 0);
                } else if (type.equals(String.class)) {
                    arguments[index] = "a description";
                } else if (type.equals(BigDecimal.class)) {
                    arguments[index] = new BigDecimal("1.00");
                } else {
                    throw new AssertionError("no argument defined for " + type.getName() + " on "
                            + entity.getSimpleName() + "; extend this test rather than skipping it");
                }
            }

            final Object built;
            try {
                built = constructor.newInstance(arguments);
            } catch (final ReflectiveOperationException failure) {
                throw new AssertionError("cannot construct " + entity.getSimpleName(), failure);
            }

            assertThat(built).as("a constructed %s must equal one carrying the same key",
                    entity.getSimpleName()).isEqualTo(instanceWithIdentity(entity, 0));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.cardemo.unit.model.EntityContractTest#embeddedIdEntities")
        @DisplayName("a null key is refused by the constructor as well as the setter")
        void constructorRefusesANullKey(final Class<?> entity) {
            final Constructor<?> constructor = fullConstructor(entity)
                    .orElseThrow(() -> new AssertionError("no public constructor"));
            final Object[] arguments = new Object[constructor.getParameterCount()];
            for (int index = 0; index < arguments.length; index++) {
                final Class<?> type = constructor.getParameterTypes()[index];
                arguments[index] = type.equals(identityField(entity).getType()) ? null
                        : type.equals(BigDecimal.class) ? new BigDecimal("1.00") : "a description";
            }

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("%s must refuse construction without its composite key",
                            entity.getSimpleName())
                    .isThrownBy(() -> {
                        try {
                            constructor.newInstance(arguments);
                        } catch (final InvocationTargetException failure) {
                            if (failure.getCause() instanceof RuntimeException runtime) {
                                throw runtime;
                            }
                            throw new AssertionError(failure);
                        } catch (final ReflectiveOperationException failure) {
                            throw new AssertionError(failure);
                        }
                    });
        }
    }
}

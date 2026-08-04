/*
 * ******************************************************************
 * Program     : AccountTest.java
 * Component   : Unit test tier, resident at
 *               src/test/java/com/cardemo/unit/model
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM tier, no container, no
 *               Spring context, no database, no live endpoint
 * Function    : Proves that the Account entity reproduces the CVACT01Y
 *               300-byte record contract - the eleven-digit key, the five
 *               S9(10)V99 money fields as BigDecimal with NO floating
 *               point anywhere, the three X(10) date fields carried as
 *               text including the source's own ACCT-EXPIRAION-DATE
 *               misspelling, the cycle debit accumulator that
 *               legitimately holds negative values, and the 178-byte
 *               filler that is deliberately not modelled.
 * Source      : app/cpy/CVACT01Y.cpy (300 B, key 11) @ 7756d89
 * Source      : app/catlg/LISTCAT.txt:L57-L60 (ACCTDATA cluster,
 *               KEYLEN 11 / AVGLRECL 300 / MAXLRECL 300) @ 7756d89
 * Source      : app/cbl/CBTRN02C.cbl:L403-L405, :L414, :L547-L552
 *               (over-limit formula, expiry string compare, sign
 *               branch) @ 7756d89
 * Source      : app/cbl/COACTUPC.cbl:L3836-L3839, :L4109,
 *               :L4131-L4133 (component-wise date comparison) @ 7756d89
 * Source      : app/cbl/CBACT04C.cbl:L462-L470 (interest formula
 *               shape) @ 7756d89
 * Source      : app/data/ASCII/acctdata.txt (50 x 300 B overpunch-signed
 *               fixture, byte-identical classpath copy) @ 7756d89
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
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.cardemo.model.entity.Account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit test for the {@link Account} entity, which replaces the {@code ACCTDATA} VSAM KSDS cluster.
 *
 * <h2>1. What this test does</h2>
 *
 * <p>{@code app/cpy/CVACT01Y.cpy} declares a 300-byte record whose components sum exactly:
 * {@code 11 + 1 + 12 + 12 + 12 + 10 + 10 + 10 + 12 + 12 + 10 + 10 + 178 = 300}, matching the record length
 * catalogued for {@code ACCTDATA} at {@code app/catlg/LISTCAT.txt:L59} ({@code KEYLEN 11},
 * {@code AVGLRECL 300}). Every assertion below is anchored to a locator that was read directly from the
 * frozen corpus, and every value that looks like test data is decoded from
 * {@code app/data/ASCII/acctdata.txt} rather than invented. The class asserts:
 *
 * <ul>
 *   <li><strong>Record geometry.</strong> The twelve modelled widths plus the unmodelled
 *       {@code FILLER PIC X(178)} at {@code CVACT01Y.cpy:L17} reach exactly 300 bytes; the table is
 *       {@code account}; every column is {@code NOT NULL}; the eleven-digit key carries no
 *       {@code @GeneratedValue} because identifiers come from the legacy data.</li>
 *   <li><strong>The preserved misspelling.</strong> {@code CVACT01Y.cpy:L11} reads
 *       {@code ACCT-EXPIRAION-DATE} - the second T of EXPIRATION is absent. The Java property is
 *       {@code expiraionDate} and the column {@code acct_expiraion_date}. It is not a typo to fix; it is the
 *       field contract, read by name at {@code CBTRN02C.cbl:L414} and {@code COACTUPC.cbl:L4131-L4133}.
 *       Severity of a silent correction: High.</li>
 *   <li><strong>Dates are text.</strong> All three date fields are {@code CHAR(10)} {@link String}, never a
 *       temporal type, because {@code COACTUPC.cbl:L4131-L4133} compares them as three separate substrings -
 *       {@code (1:4)} year, {@code (6:2)} month, {@code (9:2)} day - and {@code CBTRN02C.cbl:L414} compares
 *       the expiry against the first ten characters of the <em>originating</em> timestamp as a plain
 *       character comparison.</li>
 *   <li><strong>Signed cycle accumulators.</strong> {@code CBTRN02C.cbl:L547-L552} adds a negative amount to
 *       the cycle <em>debit</em> accumulator, which is exactly why the over-limit formula at
 *       {@code CBTRN02C.cbl:L403-L405} subtracts it. Negative values round-trip, and no positivity
 *       constraint or absolute-value normalisation exists anywhere.</li>
 *   <li><strong>Zero floating point.</strong> Five fields are {@code PIC S9(10)V99} money values mapped to
 *       {@code NUMERIC(12,2)}. The no-floating-point assertion is made <em>reflectively over every declared
 *       field</em>, so a sixth money field added later is caught automatically. Equality is asserted through
 *       {@code compareTo}, never {@code equals}, and the difference between the two is itself asserted.</li>
 *   <li><strong>Seed-fixture parity.</strong> All fifty rows of the fixture are decoded and fed through the
 *       constructor, including the non-numeric {@code A000000000} address ZIP and the ten-space group
 *       identifier that every row carries.</li>
 * </ul>
 *
 * <h2>2. How to run, build and test</h2>
 *
 * <p>This class is collected by <strong>Surefire</strong>, whose include patterns are
 * {@code **}{@code /*Test.java} and {@code **}{@code /*Tests.java} minus {@code integration} and {@code e2e}.
 * Both the package and the {@code Test} suffix are therefore load bearing: a class moved out of
 * {@code src/test/java/com/cardemo/unit} is collected by neither Surefire nor Failsafe and silently never
 * runs, leaving a green build with no error and no warning.
 *
 * <pre>{@code
 * ./mvnw -B -ntp -Ddependency-check.skip=true test -Dtest=AccountTest
 * ./mvnw -B -ntp -Ddependency-check.skip=true clean verify
 * }</pre>
 *
 * <p>Confirm collection by checking that {@code target/surefire-reports} contains
 * {@code TEST-com.cardemo.unit.model.AccountTest.xml}.
 *
 * <h2>3. Key configuration and defaults</h2>
 *
 * <ul>
 *   <li><strong>Fixed clock, never a wall clock.</strong> Every timestamp comes from
 *       {@link FixedClockProvider}. {@code Instant.now()}, {@code LocalDate.now()},
 *       {@code System.currentTimeMillis()}, the default locale and the default zone are not used anywhere,
 *       so no assertion here can drift with the calendar or the host configuration.</li>
 *   <li><strong>Fixture access by classpath name.</strong> Records come from {@link FixtureLoader}, which
 *       reads {@code acctdata.txt} from the test classpath and refuses a fixture whose byte count or record
 *       count has drifted. No mock, stub or Mockito strictness setting is needed: this tier has no
 *       collaborator to stand in for, so nothing is mocked at all.</li>
 *   <li><strong>No shared state.</strong> The fixture is held in an instance field, and JUnit builds a fresh
 *       instance per test method, so there is no static mutable state to leak between tests. The record-to-
 *       entity factory is a pure static function.</li>
 * </ul>
 *
 * <h2>4. Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>The build fails on a warning rather than an assertion.</strong> {@code -Xlint:all -Werror}
 *       with {@code failOnWarning} reaches test compilation, so a single unused import or raw type fails the
 *       whole build before any test runs. Read the {@code compiler:testCompile} output, not the test
 *       report.</li>
 *   <li><strong>A fixture cannot be found.</strong> The daily-transaction fixture is
 *       {@code dailytran.txt} - the mainframe DD name and dataset are {@code DALYTRAN}, but the ASCII
 *       fixture spells the word in full, so {@code dalytran.txt} does not exist and never did. The account
 *       fixture used here is {@code acctdata.txt}.</li>
 *   <li><strong>The no-floating-point assertion fails.</strong> A money field became {@code double} or
 *       {@code float}. Severity: Blocker - it is a security-audit failure rather than a style preference,
 *       because it makes a financial comparison non-deterministic. Remediation: restore
 *       {@link java.math.BigDecimal} and rescale with {@link java.math.RoundingMode#HALF_EVEN}.</li>
 *   <li><strong>The misspelling assertion fails.</strong> Somebody corrected {@code expiraionDate}. The
 *       property, the column and the copybook would then disagree and the parity comparison would break.</li>
 *   <li><strong>A geometry assertion fails.</strong> A width changed. Re-derive it from {@code CVACT01Y.cpy};
 *       the widths are not negotiable because the object-storage boundary is byte-exact.</li>
 *   <li><strong>Schema detail beyond the copybook is Not available in this tier.</strong> This is a pure-JVM
 *       test: it asserts what the entity declares, which was derived from the picture clauses. It reads no
 *       migration, opens no connection and invents no SQL type. To verify the physical schema, run the
 *       repository integration tier, which needs a container runtime.</li>
 * </ul>
 */
class AccountTest {

    /** {@code ACCT-ID PIC 9(11)} - CVACT01Y:L5, and the catalogued key length for ACCTDATA. */
    private static final int KEY_WIDTH = 11;

    /** {@code PIC X(01)} - CVACT01Y:L6. */
    private static final int STATUS_WIDTH = 1;

    /** {@code PIC S9(10)V99} - twelve characters: ten integer digits plus two decimals. */
    private static final int MONEY_WIDTH = 12;

    /** The scale every money field must carry, from the {@code V99} of the picture clause. */
    private static final int MONEY_SCALE = 2;

    /** The precision every money column must declare: {@code NUMERIC(12,2)}. */
    private static final int MONEY_PRECISION = 12;

    /** {@code PIC X(10)} - the width shared by the three dates, the ZIP and the group identifier. */
    private static final int TEN_CHARACTER_WIDTH = 10;

    /** {@code FILLER PIC X(178)} - CVACT01Y:L17, deliberately not modelled. */
    private static final int FILLER_WIDTH = 178;

    /** The twelve modelled widths of CVACT01Y:L5-L16, before the filler. */
    private static final int MODELLED_WIDTH = 122;

    /** The catalogued record length for ACCTDATA - app/catlg/LISTCAT.txt:L59. */
    private static final int RECORD_LENGTH = 300;

    /** Twelve copybook properties plus the {@code @Version} counter the store needs. */
    private static final int PERSISTENT_FIELD_COUNT = 13;

    /** One-based start column of each field, exactly as the picture clauses lay them out. */
    private static final int KEY_COLUMN = 1;
    private static final int STATUS_COLUMN = 12;
    private static final int CURRENT_BALANCE_COLUMN = 13;
    private static final int CREDIT_LIMIT_COLUMN = 25;
    private static final int CASH_CREDIT_LIMIT_COLUMN = 37;
    private static final int OPEN_DATE_COLUMN = 49;
    private static final int EXPIRAION_DATE_COLUMN = 59;
    private static final int REISSUE_DATE_COLUMN = 69;
    private static final int CYCLE_CREDIT_COLUMN = 79;
    private static final int CYCLE_DEBIT_COLUMN = 91;
    private static final int ADDRESS_ZIP_COLUMN = 103;
    private static final int GROUP_ID_COLUMN = 113;
    private static final int FILLER_COLUMN = 123;

    /** The first fixture row, whose key is {@code 00000000001}. */
    private static final int FIRST_ROW = 0;

    /**
     * The verified minimum {@code ACCT-EXPIRAION-DATE} across all fifty fixture rows. Used as a literal so
     * that no assertion depends on which row happens to carry it.
     */
    private static final String MINIMUM_EXPIRAION_DATE = "2023-01-06";

    /** The verified {@code ACCT-ADDR-ZIP} literal, identical on all fifty rows and NOT numeric. */
    private static final String FIXTURE_ADDRESS_ZIP = "A000000000";

    /** The verified {@code ACCT-GROUP-ID} value: ten spaces on all fifty rows. Blank, never null. */
    private static final String BLANK_GROUP_ID = "          ";

    /**
     * Ten blank group-identifier bytes plus 178 blank filler bytes. Every fixture record ends in exactly this
     * many spaces, which is what makes a whitespace cleanup destructive rather than cosmetic.
     */
    private static final int TRAILING_SPACE_RUN = 188;

    /** The verified narrowest and widest {@code ACCT-CREDIT-LIMIT} in the fixture. */
    private static final String MINIMUM_CREDIT_LIMIT = "120.00";
    private static final String MAXIMUM_CREDIT_LIMIT = "9750.00";

    /** The widest value {@code PIC S9(10)V99} can hold, and one cent beyond it. */
    private static final String MAXIMUM_MONEY = "9999999999.99";
    private static final String BEYOND_MAXIMUM_MONEY = "10000000000.00";

    /** The widest value {@code PIC 9(11)} can hold, and one beyond it. */
    private static final long MAXIMUM_ACCOUNT_ID = 99_999_999_999L;
    private static final long BEYOND_MAXIMUM_ACCOUNT_ID = 100_000_000_000L;

    /** The zoned-decimal overpunch tables, trailing-sign, exactly as the legacy datasets encode them. */
    private static final String POSITIVE_OVERPUNCH = "{ABCDEFGHI";
    private static final String NEGATIVE_OVERPUNCH = "}JKLMNOPQR";

    /** Eleven leading digits, so a single overpunch character completes a twelve-character money field. */
    private static final String ELEVEN_ZEROES = "00000000000";

    /** The five properties that carry {@code PIC S9(10)V99} money, in copybook order. */
    private static final List<String> MONEY_PROPERTIES = List.of(
            "currentBalance", "creditLimit", "cashCreditLimit", "currentCycleCredit", "currentCycleDebit");

    /** The six properties that carry {@code PIC X(n)} text, in copybook order. */
    private static final List<String> CHARACTER_PROPERTIES = List.of(
            "activeStatus", "openDate", "expiraionDate", "reissueDate", "addressZip", "groupId");

    /**
     * Bean-validation annotations that would forbid a value the source produces. A positivity constraint on
     * any money field would reject the negative cycle debit of {@code CBTRN02C.cbl:L547-L552}, and a
     * not-blank constraint on the group identifier would reject all fifty fixture rows.
     */
    private static final List<String> FORBIDDEN_VALUE_CONSTRAINTS = List.of(
            "Positive", "PositiveOrZero", "Min", "DecimalMin", "NotBlank", "NotEmpty", "Pattern", "Digits");

    /** Association annotations that would couple this entity to another aggregate. */
    private static final List<String> ASSOCIATION_ANNOTATIONS = List.of(
            "OneToOne", "OneToMany", "ManyToOne", "ManyToMany", "JoinColumn", "JoinColumns", "JoinTable",
            "ElementCollection", "Embedded", "EmbeddedId", "MapsId", "SecondaryTable");

    /** Application packages the entity layer must not reach into, in either direction. */
    private static final List<String> FORBIDDEN_PACKAGES = List.of(
            "com.cardemo.exception", "com.cardemo.repository", "com.cardemo.service",
            "com.cardemo.controller", "com.cardemo.batch", "com.cardemo.security",
            "com.cardemo.config", "com.cardemo.observability");

    /**
     * The fifty-record account fixture, loaded per test method. An instance field rather than a static one:
     * JUnit constructs a fresh instance for every test, so there is no shared mutable state, and
     * {@code FixtureData} is itself immutable.
     */
    private final FixtureLoader.FixtureData accountFixture = FixtureLoader.load(FixtureLoader.Fixture.ACCOUNT);

    /**
     * Builds an {@link Account} from one fixture record, decoding each field at the offset its picture clause
     * dictates. A pure function of its arguments: it holds no state and mutates nothing.
     *
     * @param fixture the loaded fixture, never {@code null}
     * @param row     zero-based record index within the fixture
     * @return a fully populated account carrying that record's values verbatim
     */
    private static Account accountFrom(final FixtureLoader.FixtureData fixture, final int row) {
        return new Account(
                Long.valueOf(fixture.field(row, KEY_COLUMN, KEY_WIDTH)),
                fixture.field(row, STATUS_COLUMN, STATUS_WIDTH),
                fixture.signedDecimal(row, CURRENT_BALANCE_COLUMN, MONEY_WIDTH),
                fixture.signedDecimal(row, CREDIT_LIMIT_COLUMN, MONEY_WIDTH),
                fixture.signedDecimal(row, CASH_CREDIT_LIMIT_COLUMN, MONEY_WIDTH),
                fixture.field(row, OPEN_DATE_COLUMN, TEN_CHARACTER_WIDTH),
                fixture.field(row, EXPIRAION_DATE_COLUMN, TEN_CHARACTER_WIDTH),
                fixture.field(row, REISSUE_DATE_COLUMN, TEN_CHARACTER_WIDTH),
                fixture.signedDecimal(row, CYCLE_CREDIT_COLUMN, MONEY_WIDTH),
                fixture.signedDecimal(row, CYCLE_DEBIT_COLUMN, MONEY_WIDTH),
                fixture.field(row, ADDRESS_ZIP_COLUMN, TEN_CHARACTER_WIDTH),
                fixture.field(row, GROUP_ID_COLUMN, TEN_CHARACTER_WIDTH));
    }

    /**
     * Returns the declared field of the given name, failing with the root cause attached rather than letting a
     * checked reflection exception surface as an unexplained error.
     *
     * @param property the Java property name expected on {@link Account}
     * @return the declared field
     */
    private static Field fieldOf(final String property) {
        try {
            return Account.class.getDeclaredField(property);
        } catch (final NoSuchFieldException absent) {
            throw new AssertionError("Account declares no field named '" + property
                    + "', so the CVACT01Y field contract cannot be verified", absent);
        }
    }

    /**
     * Returns the {@code @Column} mapping of the given property.
     *
     * @param property the Java property name expected on {@link Account}
     * @return the column annotation, never {@code null} for a persistent property
     */
    private static Column columnOf(final String property) {
        final Column column = fieldOf(property).getAnnotation(Column.class);
        if (column == null) {
            throw new AssertionError("Account." + property + " carries no @Column mapping, so its name, "
                    + "nullability and width are left to the naming strategy rather than to CVACT01Y");
        }
        return column;
    }

    /**
     * The fields the persistence provider maps: neither static (the width and bound constants) nor synthetic
     * (the outer-instance and switch-map references a compiler may add).
     *
     * @return the mapped fields in declaration order
     */
    private static List<Field> persistentFields() {
        final List<Field> mapped = new ArrayList<>();
        for (final Field field : Account.class.getDeclaredFields()) {
            if (!field.isSynthetic() && !Modifier.isStatic(field.getModifiers())) {
                mapped.add(field);
            }
        }
        return mapped;
    }

    /**
     * The simple names of every annotation on a declared field, which lets a constraint be forbidden without
     * importing it - and catches it whichever package it was imported from.
     *
     * @param property the Java property name expected on {@link Account}
     * @return the annotation simple names in declaration order
     */
    private static List<String> annotationNamesOn(final String property) {
        final List<String> names = new ArrayList<>();
        for (final Annotation annotation : fieldOf(property).getAnnotations()) {
            names.add(annotation.annotationType().getSimpleName());
        }
        return names;
    }

    /**
     * Every type reachable through the entity's declared signature surface: field types, method return and
     * parameter types, and constructor parameter types. An association or a service dependency cannot exist
     * without appearing here, which makes this a stronger check than scanning import statements - an unused
     * import cannot create coupling, and a fully qualified reference would evade a textual scan.
     *
     * @return the referenced types, with duplicates retained so the list cannot be empty by accident
     */
    private static List<Class<?>> signatureTypes() {
        final List<Class<?>> types = new ArrayList<>();
        for (final Field field : Account.class.getDeclaredFields()) {
            types.add(field.getType());
        }
        for (final Method method : Account.class.getDeclaredMethods()) {
            types.add(method.getReturnType());
            types.addAll(Arrays.asList(method.getParameterTypes()));
        }
        for (final Constructor<?> constructor : Account.class.getDeclaredConstructors()) {
            types.addAll(Arrays.asList(constructor.getParameterTypes()));
        }
        return types;
    }

    /**
     * Writes one of the five money properties through its own public setter, so a parameterised test can
     * cover all five without reflective field access.
     *
     * @param account  the instance to mutate
     * @param property one of {@link #MONEY_PROPERTIES}
     * @param value    the value to assign, possibly {@code null} to exercise the guard
     */
    private static void assignMoney(final Account account, final String property, final BigDecimal value) {
        switch (property) {
            case "currentBalance" -> account.setCurrentBalance(value);
            case "creditLimit" -> account.setCreditLimit(value);
            case "cashCreditLimit" -> account.setCashCreditLimit(value);
            case "currentCycleCredit" -> account.setCurrentCycleCredit(value);
            case "currentCycleDebit" -> account.setCurrentCycleDebit(value);
            default -> throw new AssertionError(
                    "'" + property + "' is not one of the five CVACT01Y money properties");
        }
    }

    /**
     * Reads one of the five money properties through its own public getter.
     *
     * @param account  the instance to read
     * @param property one of {@link #MONEY_PROPERTIES}
     * @return the current value
     */
    private static BigDecimal readMoney(final Account account, final String property) {
        return switch (property) {
            case "currentBalance" -> account.getCurrentBalance();
            case "creditLimit" -> account.getCreditLimit();
            case "cashCreditLimit" -> account.getCashCreditLimit();
            case "currentCycleCredit" -> account.getCurrentCycleCredit();
            case "currentCycleDebit" -> account.getCurrentCycleDebit();
            default -> throw new AssertionError(
                    "'" + property + "' is not one of the five CVACT01Y money properties");
        };
    }

    /**
     * Writes one of the six character properties through its own public setter.
     *
     * @param account  the instance to mutate
     * @param property one of {@link #CHARACTER_PROPERTIES}
     * @param value    the value to assign, possibly {@code null} to exercise the guard
     */
    private static void assignText(final Account account, final String property, final String value) {
        switch (property) {
            case "activeStatus" -> account.setActiveStatus(value);
            case "openDate" -> account.setOpenDate(value);
            case "expiraionDate" -> account.setExpiraionDate(value);
            case "reissueDate" -> account.setReissueDate(value);
            case "addressZip" -> account.setAddressZip(value);
            case "groupId" -> account.setGroupId(value);
            default -> throw new AssertionError(
                    "'" + property + "' is not one of the six CVACT01Y character properties");
        }
    }

    /**
     * Reads one of the six character properties through its own public getter.
     *
     * @param account  the instance to read
     * @param property one of {@link #CHARACTER_PROPERTIES}
     * @return the current value
     */
    private static String readText(final Account account, final String property) {
        return switch (property) {
            case "activeStatus" -> account.getActiveStatus();
            case "openDate" -> account.getOpenDate();
            case "expiraionDate" -> account.getExpiraionDate();
            case "reissueDate" -> account.getReissueDate();
            case "addressZip" -> account.getAddressZip();
            case "groupId" -> account.getGroupId();
            default -> throw new AssertionError(
                    "'" + property + "' is not one of the six CVACT01Y character properties");
        };
    }

    /**
     * The declared width of a character property, from its picture clause.
     *
     * @param property one of {@link #CHARACTER_PROPERTIES}
     * @return {@code 1} for the status byte, {@code 10} for the rest
     */
    private static int widthOf(final String property) {
        return "activeStatus".equals(property) ? STATUS_WIDTH : TEN_CHARACTER_WIDTH;
    }

    @Nested
    @DisplayName("the record geometry: 300 bytes, an 11-digit key and a 178-byte unmodelled filler")
    class RecordGeometry {

        @Test
        @DisplayName("the twelve modelled widths plus the filler sum to the catalogued 300 bytes")
        void theModelledWidthsPlusTheFillerSumToThreeHundred() {
            final int modelled = KEY_WIDTH + STATUS_WIDTH + MONEY_WIDTH + MONEY_WIDTH + MONEY_WIDTH
                    + TEN_CHARACTER_WIDTH + TEN_CHARACTER_WIDTH + TEN_CHARACTER_WIDTH
                    + MONEY_WIDTH + MONEY_WIDTH + TEN_CHARACTER_WIDTH + TEN_CHARACTER_WIDTH;

            assertThat(modelled)
                    .as("11 + 1 + 12 + 12 + 12 + 10 + 10 + 10 + 12 + 12 + 10 + 10 = 122 populated bytes "
                            + "from app/cpy/CVACT01Y.cpy:L5-L16")
                    .isEqualTo(MODELLED_WIDTH);
            assertThat(modelled + FILLER_WIDTH)
                    .as("122 populated plus the 178-byte FILLER at CVACT01Y:L17 is exactly the 300-byte "
                            + "record length catalogued for ACCTDATA at app/catlg/LISTCAT.txt:L59, where "
                            + "AVGLRECL and MAXLRECL both read 300")
                    .isEqualTo(RECORD_LENGTH);
        }

        @Test
        @DisplayName("the 178-byte FILLER is not modelled as a property, by any name or accessor")
        void theFillerIsNotModelledAsAProperty() {
            assertThat(persistentFields())
                    .as("CVACT01Y:L17 declares FILLER PIC X(178) purely as padding to the 300-byte "
                            + "boundary. It carries no data, so modelling it would invent a column; it is "
                            + "re-emitted at the fixed-width boundary by the writer tier instead")
                    .noneMatch(field -> field.getName().toLowerCase(Locale.ROOT).contains("filler"));
            assertThat(Account.class.getDeclaredMethods())
                    .as("an accessor would be a mapping route even with no field behind it")
                    .noneMatch(method -> method.getName().toLowerCase(Locale.ROOT).contains("filler"));
            assertThat(persistentFields())
                    .as("twelve copybook properties from CVACT01Y:L5-L16 plus the @Version counter the "
                            + "store needs, and nothing else - the filler is absent and no thirteenth "
                            + "copybook field exists")
                    .hasSize(PERSISTENT_FIELD_COUNT);
        }

        @Test
        @DisplayName("the filler region of a real record is 178 spaces, so nothing was lost by omitting it")
        void theFillerRegionOfARealRecordIsBlank() {
            final String filler = accountFixture.field(FIRST_ROW, FILLER_COLUMN, FILLER_WIDTH);

            assertThat(filler)
                    .as("columns 123-300 of app/data/ASCII/acctdata.txt carry no data on any row, which is "
                            + "the evidence that omitting the field loses nothing - had it held content, "
                            + "omitting it would have been a defect rather than a simplification")
                    .hasSize(FILLER_WIDTH)
                    .isBlank();
        }

        @Test
        @DisplayName("the entity is mapped to table account, declared rather than derived")
        void theEntityIsMappedToTableAccount() {
            assertThat(Account.class.getAnnotation(Entity.class))
                    .as("the ACCTDATA KSDS cluster becomes a JPA entity, one entity per cluster catalogued "
                            + "at app/catlg/LISTCAT.txt")
                    .isNotNull();
            final Table table = Account.class.getAnnotation(Table.class);
            assertThat(table)
                    .as("the table name is declared explicitly so that it cannot drift with a naming "
                            + "strategy change")
                    .isNotNull();
            assertThat(table.name()).isEqualTo("account");
        }

        @Test
        @DisplayName("every one of the thirteen persistent columns is declared NOT NULL")
        void everyPersistentColumnIsDeclaredNotNull() {
            final List<String> nullable = new ArrayList<>();
            for (final Field field : persistentFields()) {
                final Column column = field.getAnnotation(Column.class);
                if (column == null || column.nullable()) {
                    nullable.add(field.getName());
                }
            }

            assertThat(nullable)
                    .as("a COBOL record has no concept of absence: a PIC X(01) field always holds its "
                            + "declared width and a PIC S9(10)V99 field always holds a value, so every "
                            + "column derived from CVACT01Y is NOT NULL and a nullable one could only ever "
                            + "admit a defect")
                    .isEmpty();
        }

        @ParameterizedTest
        @CsvSource({
            "accountId,acct_id",
            "activeStatus,acct_active_status",
            "currentBalance,acct_curr_bal",
            "creditLimit,acct_credit_limit",
            "cashCreditLimit,acct_cash_credit_limit",
            "openDate,acct_open_date",
            "expiraionDate,acct_expiraion_date",
            "reissueDate,acct_reissue_date",
            "currentCycleCredit,acct_curr_cyc_credit",
            "currentCycleDebit,acct_curr_cyc_debit",
            "addressZip,acct_addr_zip",
            "groupId,acct_group_id",
            "version,version",
        })
        @DisplayName("each column name is its copybook field, lower-cased with hyphens as underscores")
        void eachColumnNameIsItsCopybookField(final String property, final String column) {
            assertThat(columnOf(property).name())
                    .as("Account.%s maps the CVACT01Y field of the same name; the transliteration is "
                            + "mechanical so that a reviewer can check it against the copybook by eye", property)
                    .isEqualTo(column);
        }

        @Test
        @DisplayName("the key is eleven digits and a Long, because PIC 9(11) overflows int")
        void theKeyIsElevenDigitsAndALong() {
            assertThat(fieldOf("accountId").getType())
                    .as("PIC 9(11) reaches 99,999,999,999 which exceeds Integer.MAX_VALUE of 2,147,483,647 "
                            + "by more than fortyfold, so an int field would silently overflow")
                    .isEqualTo(Long.class);
            assertThat(String.valueOf(MAXIMUM_ACCOUNT_ID))
                    .as("the widest 9(11) value occupies exactly the catalogued KEYLEN of 11 - "
                            + "app/catlg/LISTCAT.txt:L59")
                    .hasSize(KEY_WIDTH);
            assertThat(columnOf("accountId").columnDefinition())
                    .as("the key column states its own SQL type because a Long would otherwise map to "
                            + "BIGINT, widening the 11-digit key contract")
                    .isEqualTo("NUMERIC(11)");
            assertThat(fieldOf("accountId").getAnnotation(Id.class))
                    .as("ACCT-ID is the VSAM primary key with RKP 0 - app/catlg/LISTCAT.txt:L60")
                    .isNotNull();
        }

        @Test
        @DisplayName("the key is NOT generated, because identifiers come from the legacy data")
        void theKeyIsNotGenerated() {
            assertThat(fieldOf("accountId").getAnnotation(GeneratedValue.class))
                    .as("the fifty fixture keys 00000000001 to 00000000050 are supplied by "
                            + "app/data/ASCII/acctdata.txt and loaded verbatim by the seed migration. A "
                            + "sequence or identity column would either renumber them or drift out of step "
                            + "with them, and either outcome breaks the cross-reference join")
                    .isNull();
        }

        @Test
        @DisplayName("the widest key round-trips and a narrow one zero-pads back to eleven characters")
        void theWidestKeyRoundTripsAndANarrowOneZeroPads() {
            final Account account = accountFrom(accountFixture, FIRST_ROW);

            assertThat(String.format(Locale.ROOT, "%011d", account.getAccountId()))
                    .as("app/data/ASCII/acctdata.txt row 1 begins '00000000001', so a Long of 1 must render "
                            + "with its leading zeros or the fixed-width record would be short by ten bytes. "
                            + "The format is applied with Locale.ROOT so no locale can insert a grouping "
                            + "separator")
                    .isEqualTo("00000000001")
                    .hasSize(KEY_WIDTH);

            account.setAccountId(MAXIMUM_ACCOUNT_ID);
            assertThat(account.getAccountId()).isEqualTo(MAXIMUM_ACCOUNT_ID);
            assertThat(String.valueOf(account.getAccountId())).hasSize(KEY_WIDTH);
        }

        @ParameterizedTest
        @CsvSource({
            "activeStatus,1",
            "openDate,10",
            "expiraionDate,10",
            "reissueDate,10",
            "addressZip,10",
            "groupId,10",
        })
        @DisplayName("each character column declares the width and CHAR type of its picture clause")
        void eachCharacterColumnDeclaresItsPictureWidth(final String property, final int width) {
            final Column column = columnOf(property);

            assertThat(column.length())
                    .as("Account.%s is PIC X(%d) in CVACT01Y, and CHAR is fixed width: a VARCHAR would "
                            + "discard the trailing padding that the fixed-width boundary re-emits", property, width)
                    .isEqualTo(width);
            assertThat(column.columnDefinition()).isEqualTo("CHAR(" + width + ")");
        }
    }

    @Nested
    @DisplayName("the preserved ACCT-EXPIRAION-DATE misspelling, which is a field contract not a typo")
    class PreservedMisspelling {

        @Test
        @DisplayName("the property is expiraionDate, reproducing the copybook's missing second T")
        void thePropertyReproducesTheCopybookMisspelling() {
            assertThat(persistentFields())
                    .as("app/cpy/CVACT01Y.cpy:L11 reads ACCT-EXPIRAION-DATE - the second T of EXPIRATION is "
                            + "absent in the system of record. The Java name preserves it so the "
                            + "traceability mapping stays one-to-one. Severity of a silent correction: High")
                    .anyMatch(field -> "expiraionDate".equals(field.getName()));
        }

        @Test
        @DisplayName("the column carries the misspelling too, so property and column cannot disagree")
        void theColumnCarriesTheMisspellingToo() {
            assertThat(columnOf("expiraionDate").name())
                    .as("correcting the column alone would leave the property reading a column that does "
                            + "not exist; correcting both would leave the schema disagreeing with the "
                            + "copybook that generated it")
                    .isEqualTo("acct_expiraion_date");
        }

        @Test
        @DisplayName("no correctly spelled variant exists anywhere on the type, so there is no ambiguity")
        void noCorrectlySpelledVariantExists() {
            assertThat(persistentFields())
                    .as("carrying both spellings would be worse than carrying the wrong one: a caller "
                            + "could populate the field the persistence layer does not read")
                    .noneMatch(field -> field.getName().contains("expiration"));
            assertThat(Account.class.getDeclaredMethods())
                    .as("nor may an accessor offer the corrected spelling as a convenience alias")
                    .noneMatch(method -> method.getName().toLowerCase(Locale.ROOT).contains("expiration"));
            for (final Field field : persistentFields()) {
                final Column column = field.getAnnotation(Column.class);
                if (column != null) {
                    assertThat(column.name()).doesNotContain("expiration");
                }
            }
        }

        @Test
        @DisplayName("the accessor pair uses the misspelled name consistently")
        void theAccessorPairUsesTheMisspelledNameConsistently() throws NoSuchMethodException {
            assertThat(Account.class.getMethod("getExpiraionDate").getReturnType())
                    .as("the getter returns text, matching PIC X(10) rather than a temporal type")
                    .isEqualTo(String.class);
            assertThat(Account.class.getMethod("setExpiraionDate", String.class).getParameterTypes())
                    .as("the setter must match the getter, or bean introspection would see two "
                            + "half-properties instead of one")
                    .containsExactly(String.class);
        }

        @Test
        @DisplayName("the two live consumers read the field by that name, so the spelling is load bearing")
        void theLiveConsumersReadTheFieldByThatName() {
            final Account account = accountFrom(accountFixture, FIRST_ROW);
            account.setExpiraionDate(MINIMUM_EXPIRAION_DATE);

            assertThat(account.getExpiraionDate())
                    .as("app/cbl/CBTRN02C.cbl:L414 reads ACCT-EXPIRAION-DATE for the expiry check that "
                            + "assigns reject 103, and app/cbl/COACTUPC.cbl:L4131-L4133 reads it for the "
                            + "change-detection comparison. Both name the field, so the misspelling "
                            + "propagates from the copybook into two independent behaviours")
                    .isEqualTo(MINIMUM_EXPIRAION_DATE)
                    .hasSize(TEN_CHARACTER_WIDTH);
        }
    }

    @Nested
    @DisplayName("the three dates are TEXT, compared by substring and never parsed to a temporal type")
    class TextDates {

        @ParameterizedTest
        @ValueSource(strings = {"openDate", "expiraionDate", "reissueDate"})
        @DisplayName("each date field is a String, because the source compares characters not calendar dates")
        void eachDateFieldIsAString(final String property) {
            assertThat(fieldOf(property).getType())
                    .as("Account.%s is PIC X(10) in CVACT01Y. It stays a String because "
                            + "app/cbl/COACTUPC.cbl:L4131-L4133 compares it as three separate substrings and "
                            + "app/cbl/CBTRN02C.cbl:L414 compares it as a whole ten-character string against "
                            + "a timestamp prefix. Parsing to a temporal type would reject the spaces and "
                            + "low values the legacy data legitimately contains, and would re-render the "
                            + "value on the way out", property)
                    .isEqualTo(String.class);
        }

        @Test
        @DisplayName("the fixture's minimum expiry round-trips byte for byte, with no temporal coercion")
        void theMinimumExpiryRoundTripsByteForByte() {
            final Account account = accountFrom(accountFixture, FIRST_ROW);
            account.setExpiraionDate(MINIMUM_EXPIRAION_DATE);

            assertThat(account.getExpiraionDate())
                    .as("2023-01-06 is the earliest ACCT-EXPIRAION-DATE in app/data/ASCII/acctdata.txt")
                    .isEqualTo(MINIMUM_EXPIRAION_DATE);
            assertThat(account.getExpiraionDate().getBytes(StandardCharsets.US_ASCII))
                    .as("byte-for-byte, not merely equal by value: a temporal round-trip could return the "
                            + "same day rendered as 2023-1-6 or 06/01/2023 and still satisfy a date "
                            + "comparison while breaking the fixed-width boundary and the substring offsets")
                    .isEqualTo(MINIMUM_EXPIRAION_DATE.getBytes(StandardCharsets.US_ASCII));
            assertThat(account.getExpiraionDate()).hasSize(TEN_CHARACTER_WIDTH);
        }

        @Test
        @DisplayName("the dates decompose at the source's own offsets: (1:4) year, (6:2) month, (9:2) day")
        void theDatesDecomposeAtTheSourcesOwnOffsets() {
            final Account account = accountFrom(accountFixture, FIRST_ROW);
            account.setOpenDate(MINIMUM_EXPIRAION_DATE);
            final String openDate = account.getOpenDate();

            assertThat(openDate.substring(0, 4))
                    .as("COBOL (1:4) is one-based, so it is substring(0, 4) in Java - "
                            + "app/cbl/COACTUPC.cbl:L4127")
                    .isEqualTo("2023");
            assertThat(openDate.substring(5, 7))
                    .as("COBOL (6:2) skips the separator at position 5 - app/cbl/COACTUPC.cbl:L4128")
                    .isEqualTo("01");
            assertThat(openDate.substring(8, 10))
                    .as("COBOL (9:2) skips the second separator - app/cbl/COACTUPC.cbl:L4129")
                    .isEqualTo("06");
            assertThat(openDate.charAt(4))
                    .as("positions 5 and 8 hold the dash separator that the component offsets step over; "
                            + "that separator is why the component comparison exists at all, since the "
                            + "snapshot side of the comparison holds the same date WITHOUT separators")
                    .isEqualTo('-');
            assertThat(openDate.charAt(7)).isEqualTo('-');
        }

        @Test
        @DisplayName("the change-detection comparison is per component, so a whole-string compare is wrong")
        void theChangeDetectionComparisonIsPerComponent() {
            final Account account = accountFrom(accountFixture, FIRST_ROW);
            account.setExpiraionDate(MINIMUM_EXPIRAION_DATE);
            final String live = account.getExpiraionDate();
            final String separatorless = live.substring(0, 4) + live.substring(5, 7) + live.substring(8, 10);

            assertThat(separatorless)
                    .as("app/cbl/COACTUPC.cbl:L3836 shows the whole-string move COMMENTED OUT and "
                            + ":L3837-L3839 replacing it with three component moves. The snapshot therefore "
                            + "holds the date in its compact form, which is why the comparison must be per "
                            + "component: a naive whole-string compare of 2023-01-06 against 20230106 "
                            + "reports a change on every single request and makes the endpoint unusable")
                    .isEqualTo("20230106")
                    .hasSize(8);
            assertThat(separatorless.equals(live))
                    .as("the two representations are NOT equal as strings, which is the whole point")
                    .isFalse();
            assertThat(live.substring(0, 4)).isEqualTo(separatorless.substring(0, 4));
            assertThat(live.substring(5, 7)).isEqualTo(separatorless.substring(4, 6));
            assertThat(live.substring(8, 10)).isEqualTo(separatorless.substring(6, 8));
        }

        @Test
        @DisplayName("the expiry check is a string comparison against the originating timestamp prefix")
        void theExpiryCheckIsAStringComparisonAgainstTheOriginatingTimestampPrefix() {
            final Clock clock = FixedClockProvider.canonicalClock();
            final String originatingTimestamp = FixedClockProvider.onlineTimestamp(clock);

            assertThat(originatingTimestamp)
                    .as("the originating timestamp comes from an injected fixed clock, never from "
                            + "Instant.now(), so this assertion cannot drift with the wall clock, the host "
                            + "locale or the host zone")
                    .isEqualTo(FixedClockProvider.CANONICAL_ONLINE_TIMESTAMP)
                    .hasSize(FixedClockProvider.TIMESTAMP_LENGTH);

            final String originatingPrefix = originatingTimestamp.substring(0, TEN_CHARACTER_WIDTH);
            assertThat(originatingPrefix)
                    .as("app/cbl/CBTRN02C.cbl:L414 reads DALYTRAN-ORIG-TS (1:10) - the first ten characters "
                            + "of the ORIGINATING timestamp, not the processing one")
                    .isEqualTo("2022-06-10");

            final Account account = accountFrom(accountFixture, FIRST_ROW);
            account.setExpiraionDate(MINIMUM_EXPIRAION_DATE);
            assertThat(account.getExpiraionDate().compareTo(originatingPrefix))
                    .as("IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10) CONTINUE: 2023-01-06 is not "
                            + "earlier than 2022-06-10, so the transaction is accepted and no reject 103 is "
                            + "raised. ISO-8601 ordering is what makes the plain character comparison "
                            + "correct, and it is why no parse is needed")
                    .isPositive();

            account.setExpiraionDate("2022-01-01");
            assertThat(account.getExpiraionDate().compareTo(originatingPrefix))
                    .as("an expiry earlier than the originating timestamp takes the ELSE branch, assigning "
                            + "reject 103 with the literal 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION'. The "
                            + "assertion belongs to the batch tier; what belongs here is that the entity "
                            + "carries a value on which that character comparison is well defined")
                    .isNegative();
        }

        @ParameterizedTest
        @ValueSource(strings = {"openDate", "expiraionDate", "reissueDate"})
        @DisplayName("a space-filled date is carried verbatim, as a legacy record may legitimately hold one")
        void aSpaceFilledDateIsCarriedVerbatim(final String property) {
            final Account account = accountFrom(accountFixture, FIRST_ROW);
            final String spaces = " ".repeat(TEN_CHARACTER_WIDTH);
            assignText(account, property, spaces);

            assertThat(readText(account, property))
                    .as("Account.%s must carry a blank date through unchanged: a strict parse would reject "
                            + "it, and normalising it to null would violate the NOT NULL column while losing "
                            + "the distinction between an absent date and a zero date", property)
                    .isEqualTo(spaces)
                    .hasSize(TEN_CHARACTER_WIDTH)
                    .isBlank();
        }
    }

    @Nested
    @DisplayName("the cycle debit accumulator legitimately holds NEGATIVE values")
    class SignedCycleAccumulators {

        @Test
        @DisplayName("a negative cycle debit decoded from a real overpunch round-trips unchanged")
        void aNegativeCycleDebitDecodedFromAnOverpunchRoundTrips() {
            final BigDecimal negative = FixtureLoader.decodeZonedDecimal("00000001940J", MONEY_SCALE);
            assertThat(negative)
                    .as("the trailing J is the overpunch for -1, so 00000001940J decodes to -194.01 - the "
                            + "same encoding app/data/ASCII/dailytran.txt uses for its genuinely negative "
                            + "amounts, which is what exercises this branch with real data")
                    .isEqualByComparingTo("-194.01")
                    .isNegative();

            final Account account = accountFrom(accountFixture, FIRST_ROW);
            account.setCurrentCycleDebit(negative);

            assertThat(account.getCurrentCycleDebit())
                    .as("app/cbl/CBTRN02C.cbl:L547-L552 adds DALYTRAN-AMT to ACCT-CURR-BAL and then adds it "
                            + "to ACCT-CURR-CYC-CREDIT when it is at least zero, ELSE to "
                            + "ACCT-CURR-CYC-DEBIT. A negative amount is therefore ADDED to the debit "
                            + "accumulator, so that accumulator legitimately holds negative values")
                    .isEqualByComparingTo("-194.01")
                    .isNegative();
            assertThat(account.getCurrentCycleDebit().scale()).isEqualTo(MONEY_SCALE);
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "currentBalance", "creditLimit", "cashCreditLimit", "currentCycleCredit", "currentCycleDebit",
        })
        @DisplayName("no money field carries a positivity or lower-bound constraint")
        void noMoneyFieldCarriesAPositivityConstraint(final String property) {
            assertThat(annotationNamesOn(property))
                    .as("Account.%s is PIC S9(10)V99 - the S is a SIGN. @Positive, @PositiveOrZero, @Min or "
                            + "@DecimalMin would reject the negative cycle debit of "
                            + "app/cbl/CBTRN02C.cbl:L547-L552 and the negative balances the transaction "
                            + "fixture carries, turning a valid posting into a validation failure", property)
                    .doesNotContainAnyElementsOf(FORBIDDEN_VALUE_CONSTRAINTS);
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "currentBalance", "creditLimit", "cashCreditLimit", "currentCycleCredit", "currentCycleDebit",
        })
        @DisplayName("no money field normalises its sign: a negative value stays negative")
        void noMoneyFieldNormalisesItsSign(final String property) {
            final Account account = accountFrom(accountFixture, FIRST_ROW);
            assignMoney(account, property, new BigDecimal("-1234.56"));

            assertThat(readMoney(account, property))
                    .as("no absolute-value normalisation is permitted anywhere on this path. Account.%s must "
                            + "return exactly what was assigned, sign included, because the over-limit "
                            + "formula depends on the sign surviving", property)
                    .isEqualByComparingTo("-1234.56")
                    .isNegative();
        }

        @Test
        @DisplayName("the over-limit formula SUBTRACTS the debit, so a negative debit raises the exposure")
        void theOverLimitFormulaSubtractsTheDebit() {
            final Account account = accountFrom(accountFixture, FIRST_ROW);
            account.setCreditLimit(new BigDecimal("125.00"));
            account.setCurrentCycleCredit(new BigDecimal("100.00"));
            account.setCurrentCycleDebit(new BigDecimal("-25.50"));
            final BigDecimal transactionAmount = new BigDecimal("0.10");

            final BigDecimal temporaryBalance = account.getCurrentCycleCredit()
                    .subtract(account.getCurrentCycleDebit())
                    .add(transactionAmount);

            assertThat(temporaryBalance)
                    .as("app/cbl/CBTRN02C.cbl:L403-L405 computes WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - "
                            + "ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT. With a debit of -25.50 the subtraction "
                            + "ADDS 25.50, giving 125.60 exactly. That is not a quirk to correct: it is "
                            + "correct precisely because the debit accumulator holds negatives")
                    .isEqualByComparingTo("125.60");
            assertThat(account.getCreditLimit().compareTo(temporaryBalance))
                    .as("app/cbl/CBTRN02C.cbl:L407 reads IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL CONTINUE ELSE "
                            + "reject 102 'OVERLIMIT TRANSACTION'. A limit of 125.00 is below 125.60, so "
                            + "this transaction is over limit. The comparison uses compareTo, never equals, "
                            + "because equals would also compare scale")
                    .isNegative();
        }

        @Test
        @DisplayName("rewriting the formula to add the absolute debit changes the outcome, so it may not be")
        void rewritingTheFormulaToAddTheAbsoluteDebitChangesTheOutcome() {
            final BigDecimal cycleCredit = new BigDecimal("100.00");
            final BigDecimal cycleDebit = new BigDecimal("-25.50");
            final BigDecimal transactionAmount = new BigDecimal("0.10");

            final BigDecimal asWritten = cycleCredit.subtract(cycleDebit).add(transactionAmount);
            final BigDecimal normalised = cycleCredit.subtract(cycleDebit.abs()).add(transactionAmount);

            assertThat(asWritten).isEqualByComparingTo("125.60");
            assertThat(normalised)
                    .as("normalising the debit to its magnitude first yields 74.60 - a 51.00 divergence on "
                            + "the same inputs, which would let an over-limit transaction post. This is the "
                            + "concrete cost of the tempting abs() call, and the reason it is forbidden")
                    .isEqualByComparingTo("74.60");
            assertThat(asWritten.compareTo(normalised))
                    .as("the two forms genuinely disagree, so the shape of the expression is load bearing "
                            + "and may not be rewritten into a form that merely looks equivalent")
                    .isPositive();
        }
    }

    @Nested
    @DisplayName("money precision: BigDecimal at NUMERIC(12,2), never a floating-point type")
    class MoneyPrecision {

        @Test
        @DisplayName("NO declared field is float or double, asserted reflectively over the whole class")
        void noDeclaredFieldIsAFloatingPointType() {
            assertThat(Account.class.getDeclaredFields())
                    .as("the sweep must not pass vacuously")
                    .isNotEmpty();
            assertThat(Account.class.getDeclaredFields())
                    .filteredOn(field -> !field.isSynthetic())
                    .allSatisfy(field -> assertThat(field.getType())
                            .as("Account.%s must not be a floating-point type. Five fields are PIC "
                                    + "S9(10)V99 money values, and app/cbl/CBTRN02C.cbl:L403-L407 computes "
                                    + "credit - debit + amount and compares the result against the credit "
                                    + "limit. Binary floating point cannot represent 0.10 exactly, so that "
                                    + "comparison would become non-deterministic at the boundary. The sweep "
                                    + "covers EVERY declared field, so a sixth money field added later is "
                                    + "caught without editing this test", field.getName())
                            .isNotIn(double.class, float.class, Double.class, Float.class));
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "currentBalance", "creditLimit", "cashCreditLimit", "currentCycleCredit", "currentCycleDebit",
        })
        @DisplayName("each of the five money properties is a BigDecimal")
        void eachMoneyPropertyIsABigDecimal(final String property) {
            assertThat(fieldOf(property).getType())
                    .as("Account.%s corresponds to a PIC S9(10)V99 field in CVACT01Y and must be BigDecimal "
                            + "so that decimal arithmetic is exact rather than approximate", property)
                    .isEqualTo(BigDecimal.class);
        }

        @Test
        @DisplayName("exactly five money properties exist, matching CVACT01Y L7, L8, L9, L13 and L14")
        void exactlyFiveMoneyPropertiesExist() {
            final List<String> declared = new ArrayList<>();
            for (final Field field : persistentFields()) {
                if (BigDecimal.class.equals(field.getType())) {
                    declared.add(field.getName());
                }
            }

            assertThat(declared)
                    .as("CVACT01Y declares five money fields and no more; a sixth would mean a column "
                            + "with no picture clause behind it, and a missing one would mean a lost balance")
                    .containsExactlyElementsOf(MONEY_PROPERTIES);
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "currentBalance", "creditLimit", "cashCreditLimit", "currentCycleCredit", "currentCycleDebit",
        })
        @DisplayName("each money column declares NUMERIC(12,2), derived from the picture clause alone")
        void eachMoneyColumnDeclaresNumericTwelveTwo(final String property) {
            final Column column = columnOf(property);

            assertThat(column.precision())
                    .as("PIC S9(10)V99 is ten integer digits plus two decimals, so twelve significant "
                            + "digits: Account.%s maps to NUMERIC(12,2)", property)
                    .isEqualTo(MONEY_PRECISION);
            assertThat(column.scale())
                    .as("the V99 fixes the scale at two; losing it would render 194 where the legacy report "
                            + "prints 194.00")
                    .isEqualTo(MONEY_SCALE);
            assertThat(column.columnDefinition())
                    .as("the money columns state no hand-written SQL type: the type is derived from the "
                            + "precision and scale above, which come from the picture clause. Any further "
                            + "schema detail is Not available in this pure-JVM tier, which reads no "
                            + "migration - verifying the physical column needs the repository tier and a "
                            + "container runtime")
                    .isEmpty();
        }

        @Test
        @DisplayName("the twelve-digit tier is not collapsed with the narrower tiers of other copybooks")
        void theTwelveDigitTierIsNotCollapsedWithTheNarrowerTiers() {
            assertThat(MONEY_PRECISION)
                    .as("this entity's money fields are PIC S9(10)V99, twelve digits. TRAN-AMT, "
                            + "DALYTRAN-AMT and TRAN-CAT-BAL are PIC S9(09)V99 - ELEVEN digits - and "
                            + "DIS-INT-RATE is PIC S9(04)V99 - SIX. Collapsing the three tiers to one "
                            + "would either truncate an account balance or over-widen a rate")
                    .isEqualTo(12)
                    .isNotEqualTo(11)
                    .isNotEqualTo(6);
            assertThat(FixtureLoader.MONEY_FIELD_WIDTH)
                    .as("the twelve-character fixture field width corroborates the twelve-digit precision, "
                            + "since a zoned-decimal field occupies one character per digit with the sign "
                            + "overpunched onto the last")
                    .isEqualTo(MONEY_WIDTH);
            assertThat(FixtureLoader.AMOUNT_FIELD_WIDTH)
                    .as("the transaction amount tier is genuinely narrower in the fixtures too, which is "
                            + "the independent evidence that the tiers are distinct rather than a "
                            + "transcription slip")
                    .isEqualTo(11);
            assertThat(FixtureLoader.RATE_FIELD_WIDTH).isEqualTo(6);
        }

        @Test
        @DisplayName("equals compares scale whereas compareTo does not, which is why compareTo is mandatory")
        void equalsComparesScaleWhereasCompareToDoesNot() {
            final BigDecimal oneDecimal = new BigDecimal("15.0");
            final BigDecimal twoDecimals = new BigDecimal("15.00");

            assertThat(oneDecimal.equals(twoDecimals))
                    .as("BigDecimal.equals compares unscaled value AND scale, so 15.0 and 15.00 are NOT "
                            + "equal despite being the same amount. Using equals for a money comparison is "
                            + "therefore a defect that hides until two code paths disagree about scale")
                    .isFalse();
            assertThat(oneDecimal.compareTo(twoDecimals))
                    .as("compareTo ignores scale and compares value, returning 0 for the same amount. Every "
                            + "money comparison in this migration uses compareTo")
                    .isZero();
            assertThat(oneDecimal.scale()).isEqualTo(1);
            assertThat(twoDecimals.scale()).isEqualTo(MONEY_SCALE);
        }

        @Test
        @DisplayName("the entity ACCEPTS an under-scaled value, so the compareTo mandate is load bearing")
        void theEntityAcceptsAnUnderScaledValue() {
            final Account account = accountFrom(accountFixture, FIRST_ROW);
            account.setCurrentBalance(new BigDecimal("15.0"));

            assertThat(account.getCurrentBalance().scale())
                    .as("the guard rejects a scale ABOVE two but accepts one below it, because a shorter "
                            + "scale loses no information. The stored value therefore genuinely can carry "
                            + "scale 1, which is what makes the compareTo rule a live requirement on this "
                            + "entity rather than an abstract note about BigDecimal")
                    .isEqualTo(1);
            assertThat(account.getCurrentBalance())
                    .as("compareTo sees the same amount")
                    .isEqualByComparingTo("15.00");
            assertThat(account.getCurrentBalance().equals(new BigDecimal("15.00")))
                    .as("equals does not, so an assertion or a branch written with equals would fail here "
                            + "on a value the entity legitimately holds")
                    .isFalse();
        }

        @Test
        @DisplayName("the first fixture record's three balances decode and round-trip exactly")
        void theFirstFixtureRecordsBalancesRoundTripExactly() {
            assertThat(accountFixture.field(FIRST_ROW, CURRENT_BALANCE_COLUMN, MONEY_WIDTH))
                    .as("the raw twelve characters at columns 13-24 of row 1, whose trailing brace is the "
                            + "overpunch for +0")
                    .isEqualTo("00000001940{");

            final Account account = accountFrom(accountFixture, FIRST_ROW);

            assertThat(account.getCurrentBalance())
                    .as("00000001940 with a +0 overpunch is 0000000194.0 followed by 0, decoding to "
                            + "+194.00 - a value the seed migration must produce and this entity must carry "
                            + "without rescaling")
                    .isEqualByComparingTo("194.00");
            assertThat(account.getCurrentBalance().scale()).isEqualTo(MONEY_SCALE);
            assertThat(account.getCreditLimit()).isEqualByComparingTo("2020.00");
            assertThat(account.getCashCreditLimit()).isEqualByComparingTo("1020.00");
        }

        @Test
        @DisplayName("HALF_EVEN differs materially from HALF_UP, so the mode may never be left to a default")
        void halfEvenDiffersMateriallyFromHalfUp() {
            final BigDecimal product = new BigDecimal("194.00").multiply(new BigDecimal("15.00"));

            assertThat(product)
                    .as("the balance from acctdata.txt row 1 times a 15.00 rate is 2910.00 exactly, and "
                            + "2910.00 / 1200 is 2.425 exactly - a value landing precisely on a rounding "
                            + "boundary, which makes it the ideal case for pinning the mode")
                    .isEqualByComparingTo("2910.00");

            final BigDecimal halfEven = product.divide(
                    new BigDecimal("1200"), MONEY_SCALE, RoundingMode.HALF_EVEN);
            final BigDecimal halfUp = product.divide(
                    new BigDecimal("1200"), MONEY_SCALE, RoundingMode.HALF_UP);

            assertThat(halfEven)
                    .as("HALF_EVEN takes 2.425 DOWN to 2.42 because the retained digit 2 is already even. "
                            + "That is the mandated mode, so the expected value is 2.42 and NOT the 2.43 "
                            + "intuition suggests")
                    .isEqualByComparingTo("2.42");
            assertThat(halfUp)
                    .as("HALF_UP yields 2.43 on identical inputs - a one-cent divergence per account per "
                            + "cycle. Asserting both side by side is what makes the choice load bearing")
                    .isEqualByComparingTo("2.43");
            assertThat(halfEven.compareTo(halfUp))
                    .as("the two modes genuinely disagree here")
                    .isNegative();
            assertThat(halfEven.scale()).isEqualTo(MONEY_SCALE);
        }

        @Test
        @DisplayName("the interest formula divides by 1200 in one step, never by 100 then by 12")
        void theInterestFormulaDividesByTwelveHundredInOneStep() {
            final BigDecimal balance = new BigDecimal("100.05");
            final BigDecimal rate = new BigDecimal("13.00");
            final BigDecimal product = balance.multiply(rate);

            assertThat(product)
                    .as("app/cbl/CBACT04C.cbl:L462-L470 multiplies first, so the intermediate is 1300.6500")
                    .isEqualByComparingTo("1300.65");

            final BigDecimal asWritten = product.divide(
                    new BigDecimal("1200"), MONEY_SCALE, RoundingMode.HALF_EVEN);
            final BigDecimal intermediate = product.divide(
                    new BigDecimal("100"), MONEY_SCALE, RoundingMode.HALF_EVEN);

            assertThat(asWritten)
                    .as("1300.65 / 1200 is 1.083875, rounding half-even to 1.08 in a single step")
                    .isEqualByComparingTo("1.08");
            assertThat(intermediate)
                    .as("the tempting two-step rewrite rounds 1300.65/100 to 13.01 FIRST, discarding "
                            + "precision the single-step form retains. On other inputs that lost precision "
                            + "changes the cent, so the shape of the expression is part of the contract "
                            + "even where the two forms happen to agree")
                    .isEqualByComparingTo("13.01");
            assertThat(intermediate.divide(new BigDecimal("12"), MONEY_SCALE, RoundingMode.HALF_EVEN))
                    .isEqualByComparingTo("1.08");
        }

        @Test
        @DisplayName("the widest S9(10)V99 value is accepted and one cent beyond it is rejected")
        void theWidestValueIsAcceptedAndOneCentBeyondIsRejected() {
            final Account account = accountFrom(accountFixture, FIRST_ROW);
            account.setCreditLimit(new BigDecimal(MAXIMUM_MONEY));

            assertThat(account.getCreditLimit())
                    .as("PIC S9(10)V99 holds ten integer digits and two decimals, so 9999999999.99 is the "
                            + "widest representable value; BigDecimal carries it exactly whereas a double "
                            + "loses the cents entirely at this magnitude")
                    .isEqualByComparingTo(MAXIMUM_MONEY);
            assertThat(account.getCreditLimit().precision())
                    .as("twelve significant digits, exactly the picture width")
                    .isEqualTo(MONEY_PRECISION);

            assertThatIllegalArgumentException()
                    .as("an eleventh integer digit would not fit the fixed-width field, so it is refused at "
                            + "the boundary rather than silently truncated by the column")
                    .isThrownBy(() -> account.setCreditLimit(new BigDecimal(BEYOND_MAXIMUM_MONEY)))
                    .withMessageContaining("creditLimit")
                    .withMessageContaining("ACCT-CREDIT-LIMIT PIC S9(10)V99")
                    .withMessageContaining("must be between -9999999999.99 and 9999999999.99 inclusive");
        }
    }

    @Nested
    @DisplayName("the seed fixture: fifty 300-byte records whose own values are the test data")
    class SeedFixtureContract {

        @Test
        @DisplayName("the fixture declares and holds fifty 300-byte records totalling 15050 bytes")
        void theFixtureHoldsFiftyThreeHundredByteRecords() {
            assertThat(FixtureLoader.Fixture.ACCOUNT.resourceName())
                    .as("the classpath copy of app/data/ASCII/acctdata.txt, byte-identical to the frozen "
                            + "original. It is loaded by name rather than by path so that no test depends on "
                            + "the process working directory")
                    .isEqualTo("acctdata.txt");
            assertThat(accountFixture.recordCount())
                    .as("fifty account records, one per catalogued ACCTDATA row")
                    .isEqualTo(FixtureLoader.Fixture.ACCOUNT.expectedRecordCount())
                    .isEqualTo(50);
            assertThat(accountFixture.recordWidth())
                    .as("300 bytes per record, matching AVGLRECL at app/catlg/LISTCAT.txt:L59")
                    .isEqualTo(RECORD_LENGTH);
            assertThat(accountFixture.byteCount())
                    .as("50 records of 300 characters plus one line terminator each is 15050 bytes; the "
                            + "loader refuses the fixture outright if that arithmetic stops holding, which "
                            + "is how an accidental edit is caught before any assertion runs")
                    .isEqualTo(accountFixture.impliedByteCount())
                    .isEqualTo(15_050);
        }

        @Test
        @DisplayName("every record ends in exactly 188 spaces, so a whitespace cleanup would be destructive")
        void everyRecordEndsInOneHundredAndEightyEightSpaces() {
            for (int row = 0; row < accountFixture.recordCount(); row++) {
                final String record = accountFixture.recordAt(row);
                final int trailing = record.length() - record.stripTrailing().length();

                assertThat(record)
                        .as("record %d must retain its full declared width", row)
                        .hasSize(RECORD_LENGTH);
                assertThat(trailing)
                        .as("record %d ends in the ten blank ACCT-GROUP-ID bytes plus the 178 blank FILLER "
                                + "bytes: 188 trailing spaces that are LAYOUT, not formatting. An editor "
                                + "trimming trailing whitespace would shorten every record to 112 "
                                + "characters and destroy the fixed-width geometry the parity comparison "
                                + "depends on, which is why .editorconfig pins trim_trailing_whitespace to "
                                + "false for src/test/resources/**.txt", row)
                        .isEqualTo(TRAILING_SPACE_RUN);
            }
        }

        @Test
        @DisplayName("ACCT-ADDR-ZIP is the non-numeric literal A000000000 on all fifty rows")
        void theAddressZipIsTheNonNumericLiteralOnAllFiftyRows() {
            for (int row = 0; row < accountFixture.recordCount(); row++) {
                assertThat(accountFixture.field(row, ADDRESS_ZIP_COLUMN, TEN_CHARACTER_WIDTH))
                        .as("row %d: columns 103-112 hold A000000000. The leading A means the field is NOT "
                                + "numeric, so no digits-only constraint may be placed on it", row)
                        .isEqualTo(FIXTURE_ADDRESS_ZIP);
            }
        }

        @Test
        @DisplayName("the non-numeric ZIP round-trips unchanged and carries no digits-only constraint")
        void theNonNumericZipRoundTripsUnchanged() {
            final Account account = accountFrom(accountFixture, FIRST_ROW);

            assertThat(account.getAddressZip())
                    .as("construction succeeded with A000000000 and returned it verbatim, which is the "
                            + "behavioural proof that no numeric validation stands in the way of the seed "
                            + "data")
                    .isEqualTo(FIXTURE_ADDRESS_ZIP)
                    .hasSize(TEN_CHARACTER_WIDTH);
            assertThat(annotationNamesOn("addressZip"))
                    .as("@Pattern or @Digits on ACCT-ADDR-ZIP would reject all fifty seeded accounts. The "
                            + "field is PIC X(10) - text - and the source performs no numeric edit on it")
                    .doesNotContainAnyElementsOf(FORBIDDEN_VALUE_CONSTRAINTS);
        }

        @Test
        @DisplayName("decoding the ZIP as if it were money fails, with the root cause preserved")
        void decodingTheZipAsMoneyFailsWithTheRootCausePreserved() {
            assertThatIllegalStateException()
                    .as("overpunch decoding must be POSITION AWARE, driven by the picture clauses alone. "
                            + "Columns 103-112 are PIC X(10) text, so decoding them as a zoned decimal is a "
                            + "programming error and is reported as one rather than silently producing a "
                            + "number. The A of A000000000 is a letter in a text field, NOT the overpunch "
                            + "for +1 - and this is the assertion that proves the distinction is enforced")
                    .isThrownBy(() -> accountFixture.signedDecimal(
                            FIRST_ROW, ADDRESS_ZIP_COLUMN, TEN_CHARACTER_WIDTH))
                    .withMessageContaining("acctdata.txt")
                    .withMessageContaining("columns 103-112")
                    .withCauseInstanceOf(IllegalArgumentException.class)
                    .havingCause()
                    .withMessageContaining("every character before the sign position must be a digit");

            assertThat(FixtureLoader.decodeZonedDecimal(
                            accountFixture.field(FIRST_ROW, CURRENT_BALANCE_COLUMN, MONEY_WIDTH), MONEY_SCALE))
                    .as("the very same decoder applied at the offset the picture clause DOES describe "
                            + "succeeds, so the failure above is about position and not about the decoder")
                    .isEqualByComparingTo("194.00");
        }

        @Test
        @DisplayName("ACCT-GROUP-ID is ten spaces on all fifty rows: blank, but never null")
        void theGroupIdIsBlankOnAllFiftyRows() {
            for (int row = 0; row < accountFixture.recordCount(); row++) {
                assertThat(accountFixture.field(row, GROUP_ID_COLUMN, TEN_CHARACTER_WIDTH))
                        .as("row %d: columns 113-122 hold ten spaces. This is what makes the disclosure-group "
                                + "lookup miss for every seeded account and fall back to the literal "
                                + "DEFAULT group, so the blankness is behaviour rather than an omission", row)
                        .isEqualTo(BLANK_GROUP_ID);
            }

            final Account account = accountFrom(accountFixture, FIRST_ROW);
            assertThat(account.getGroupId())
                    .as("a blank group identifier is a value: it is not null, it occupies its full declared "
                            + "width, and it round-trips unchanged")
                    .isNotNull()
                    .isBlank()
                    .isEqualTo(BLANK_GROUP_ID)
                    .hasSize(TEN_CHARACTER_WIDTH);
        }

        @Test
        @DisplayName("no character property carries a not-blank or not-empty constraint")
        void noCharacterPropertyCarriesANotBlankConstraint() {
            for (final String property : CHARACTER_PROPERTIES) {
                assertThat(annotationNamesOn(property))
                        .as("@NotBlank or @NotEmpty on Account.%s would reject the seed data itself: the "
                                + "group identifier is blank on all fifty rows, and a blank date is a "
                                + "legitimate legacy value. The NOT NULL column is the constraint; "
                                + "non-blankness is not", property)
                        .doesNotContainAnyElementsOf(FORBIDDEN_VALUE_CONSTRAINTS);
            }
        }

        @Test
        @DisplayName("both cycle accumulators are +0.00 on all fifty rows, so the fixture starts a clean cycle")
        void bothCycleAccumulatorsAreZeroOnAllFiftyRows() {
            for (int row = 0; row < accountFixture.recordCount(); row++) {
                assertThat(accountFixture.signedDecimal(row, CYCLE_CREDIT_COLUMN, MONEY_WIDTH))
                        .as("row %d ACCT-CURR-CYC-CREDIT", row)
                        .isEqualByComparingTo("0.00");
                assertThat(accountFixture.signedDecimal(row, CYCLE_DEBIT_COLUMN, MONEY_WIDTH))
                        .as("row %d ACCT-CURR-CYC-DEBIT - zero in the seed data, which is precisely why the "
                                + "negative case must be asserted deliberately rather than discovered from "
                                + "the fixture", row)
                        .isEqualByComparingTo("0.00");
            }
        }

        @Test
        @DisplayName("the fixture credit limits span +120.00 to +9750.00, all positive and all scale two")
        void theCreditLimitsSpanTheVerifiedRange() {
            BigDecimal narrowest = accountFixture.signedDecimal(
                    FIRST_ROW, CREDIT_LIMIT_COLUMN, MONEY_WIDTH);
            BigDecimal widest = narrowest;
            for (int row = 1; row < accountFixture.recordCount(); row++) {
                final BigDecimal limit = accountFixture.signedDecimal(row, CREDIT_LIMIT_COLUMN, MONEY_WIDTH);
                assertThat(limit.scale())
                        .as("row %d must decode at scale two, from the V99 of the picture clause", row)
                        .isEqualTo(MONEY_SCALE);
                if (limit.compareTo(narrowest) < 0) {
                    narrowest = limit;
                }
                if (limit.compareTo(widest) > 0) {
                    widest = limit;
                }
            }

            assertThat(narrowest)
                    .as("the narrowest catalogued credit limit; the extremes are located by comparison "
                            + "rather than by a hardcoded row index, so the assertion survives a reordering "
                            + "of the fixture")
                    .isEqualByComparingTo(MINIMUM_CREDIT_LIMIT);
            assertThat(widest).isEqualByComparingTo(MAXIMUM_CREDIT_LIMIT);
        }

        @Test
        @DisplayName("the earliest ACCT-EXPIRAION-DATE in the fixture is 2023-01-06")
        void theEarliestExpiraionDateIsTheVerifiedValue() {
            String earliest = accountFixture.field(FIRST_ROW, EXPIRAION_DATE_COLUMN, TEN_CHARACTER_WIDTH);
            for (int row = 1; row < accountFixture.recordCount(); row++) {
                final String candidate =
                        accountFixture.field(row, EXPIRAION_DATE_COLUMN, TEN_CHARACTER_WIDTH);
                assertThat(candidate)
                        .as("row %d must carry a full ten-character date", row)
                        .hasSize(TEN_CHARACTER_WIDTH);
                if (candidate.compareTo(earliest) < 0) {
                    earliest = candidate;
                }
            }

            assertThat(earliest)
                    .as("the minimum is found by the same lexicographic comparison the source uses at "
                            + "app/cbl/CBTRN02C.cbl:L414, which is only sound because the dates are "
                            + "ISO-8601 - and that soundness is what lets the field stay text")
                    .isEqualTo(MINIMUM_EXPIRAION_DATE);
        }

        @Test
        @DisplayName("the positive overpunch table decodes every digit from +0 to +9")
        void thePositiveOverpunchTableDecodesEveryDigit() {
            for (int digit = 0; digit <= 9; digit++) {
                final String encoded = ELEVEN_ZEROES + POSITIVE_OVERPUNCH.charAt(digit);

                assertThat(FixtureLoader.decodeZonedDecimal(encoded, MONEY_SCALE))
                        .as("the trailing %c is the positive overpunch for %d, so the twelve-character "
                                + "field decodes to +0.0%d", POSITIVE_OVERPUNCH.charAt(digit), digit, digit)
                        .isEqualByComparingTo(BigDecimal.valueOf(digit, MONEY_SCALE));
                assertThat(encoded).hasSize(MONEY_WIDTH);
            }
        }

        @Test
        @DisplayName("the negative overpunch table decodes every digit from -0 to -9")
        void theNegativeOverpunchTableDecodesEveryDigit() {
            for (int digit = 0; digit <= 9; digit++) {
                final String encoded = ELEVEN_ZEROES + NEGATIVE_OVERPUNCH.charAt(digit);

                assertThat(FixtureLoader.decodeZonedDecimal(encoded, MONEY_SCALE))
                        .as("the trailing %c is the negative overpunch for %d. app/data/ASCII/dailytran.txt "
                                + "contains these characters, which is the evidence that the posting fixture "
                                + "carries genuinely negative amounts and must not be normalised",
                                NEGATIVE_OVERPUNCH.charAt(digit), digit)
                        .isEqualByComparingTo(BigDecimal.valueOf(-digit, MONEY_SCALE));
                assertThat(encoded).hasSize(MONEY_WIDTH);
            }
        }

        @Test
        @DisplayName("every one of the fifty rows constructs an account carrying its twelve fields verbatim")
        void everyRowConstructsAnAccountCarryingItsFieldsVerbatim() {
            for (int row = 0; row < accountFixture.recordCount(); row++) {
                final Account account = accountFrom(accountFixture, row);

                assertThat(account.getAccountId())
                        .as("row %d ACCT-ID", row)
                        .isEqualTo(Long.valueOf(accountFixture.field(row, KEY_COLUMN, KEY_WIDTH)));
                assertThat(account.getActiveStatus())
                        .as("row %d ACCT-ACTIVE-STATUS", row)
                        .isEqualTo(accountFixture.field(row, STATUS_COLUMN, STATUS_WIDTH));
                assertThat(account.getCurrentBalance())
                        .as("row %d ACCT-CURR-BAL", row)
                        .isEqualByComparingTo(
                                accountFixture.signedDecimal(row, CURRENT_BALANCE_COLUMN, MONEY_WIDTH));
                assertThat(account.getCreditLimit())
                        .as("row %d ACCT-CREDIT-LIMIT", row)
                        .isEqualByComparingTo(
                                accountFixture.signedDecimal(row, CREDIT_LIMIT_COLUMN, MONEY_WIDTH));
                assertThat(account.getCashCreditLimit())
                        .as("row %d ACCT-CASH-CREDIT-LIMIT", row)
                        .isEqualByComparingTo(
                                accountFixture.signedDecimal(row, CASH_CREDIT_LIMIT_COLUMN, MONEY_WIDTH));
                assertThat(account.getOpenDate())
                        .as("row %d ACCT-OPEN-DATE", row)
                        .isEqualTo(accountFixture.field(row, OPEN_DATE_COLUMN, TEN_CHARACTER_WIDTH));
                assertThat(account.getExpiraionDate())
                        .as("row %d ACCT-EXPIRAION-DATE", row)
                        .isEqualTo(accountFixture.field(row, EXPIRAION_DATE_COLUMN, TEN_CHARACTER_WIDTH));
                assertThat(account.getReissueDate())
                        .as("row %d ACCT-REISSUE-DATE", row)
                        .isEqualTo(accountFixture.field(row, REISSUE_DATE_COLUMN, TEN_CHARACTER_WIDTH));
                assertThat(account.getCurrentCycleCredit())
                        .as("row %d ACCT-CURR-CYC-CREDIT", row)
                        .isEqualByComparingTo(
                                accountFixture.signedDecimal(row, CYCLE_CREDIT_COLUMN, MONEY_WIDTH));
                assertThat(account.getCurrentCycleDebit())
                        .as("row %d ACCT-CURR-CYC-DEBIT", row)
                        .isEqualByComparingTo(
                                accountFixture.signedDecimal(row, CYCLE_DEBIT_COLUMN, MONEY_WIDTH));
                assertThat(account.getAddressZip())
                        .as("row %d ACCT-ADDR-ZIP", row)
                        .isEqualTo(accountFixture.field(row, ADDRESS_ZIP_COLUMN, TEN_CHARACTER_WIDTH));
                assertThat(account.getGroupId())
                        .as("row %d ACCT-GROUP-ID", row)
                        .isEqualTo(accountFixture.field(row, GROUP_ID_COLUMN, TEN_CHARACTER_WIDTH));
                assertThat(account.getVersion())
                        .as("row %d carries no version: the store assigns it on first flush", row)
                        .isNull();
            }
        }
    }

    @Nested
    @DisplayName("scope boundaries: versioned, unassociated, uncoupled, and silent about the ZIP")
    class ScopeBoundaries {

        @Test
        @DisplayName("the version property carries the JPA @Version annotation")
        void theVersionPropertyCarriesTheVersionAnnotation() {
            assertThat(fieldOf("version").getAnnotation(Version.class))
                    .as("Account is one of exactly four versioned entities - Account, Card, Customer and "
                            + "Transaction - because those four are the ones the update paths rewrite. The "
                            + "annotation is the STORE-level half of the two-layer concurrency design")
                    .isNotNull();
            assertThat(fieldOf("version").getType())
                    .as("a Long rather than a primitive, so an unflushed instance can report the absence of "
                            + "a version instead of a misleading zero")
                    .isEqualTo(Long.class);
            assertThat(columnOf("version").nullable())
                    .as("the column itself is NOT NULL: the provider always writes a version, even though "
                            + "the application never seeds one")
                    .isFalse();
        }

        @Test
        @DisplayName("the version is null until the provider assigns it, and the setter is for the provider")
        void theVersionIsNullUntilTheProviderAssignsIt() {
            final Account account = accountFrom(accountFixture, FIRST_ROW);

            assertThat(account.getVersion())
                    .as("the application never seeds a version; a non-null value here would mean the "
                            + "constructor was inventing persistence state")
                    .isNull();

            account.setVersion(7L);
            assertThat(account.getVersion())
                    .as("the setter exists for the provider and for fixtures. A caller must not use it to "
                            + "fake an optimistic-lock outcome, and the store-level guard is not the whole "
                            + "story in any case: app/cbl/COACTUPC.cbl:L4109 compares business field VALUES "
                            + "against a snapshot, so an explicit field-by-field comparison is required in "
                            + "addition. That snapshot travels on the update request, and its assertions "
                            + "belong to the request DTO's own test rather than here")
                    .isEqualTo(7L);
        }

        @Test
        @DisplayName("the type declares no JPA association, so it owns no other aggregate's lifecycle")
        void theTypeDeclaresNoJpaAssociation() {
            for (final Field field : persistentFields()) {
                final List<String> names = annotationNamesOn(field.getName());

                assertThat(names)
                        .as("Account.%s must map a column, not a relationship. The ten foreign keys in the "
                                + "schema are declared in the migration, not navigated from the entity: a "
                                + "lazy association here would turn a read of one account into an "
                                + "unbounded fan-out, and CVACT01Y describes flat fields only", field.getName())
                        .doesNotContainAnyElementsOf(ASSOCIATION_ANNOTATIONS);
            }
        }

        @Test
        @DisplayName("every persistent field is a Long, a String or a BigDecimal - never another entity")
        void everyPersistentFieldIsAScalar() {
            assertThat(persistentFields())
                    .as("the sweep must not pass vacuously")
                    .isNotEmpty();
            assertThat(persistentFields())
                    .allSatisfy(field -> assertThat(field.getType())
                            .as("Account.%s must be one of the three scalar types the picture clauses "
                                    + "produce: PIC 9(11) becomes Long, PIC X(n) becomes String and PIC "
                                    + "S9(10)V99 becomes BigDecimal. A collection or an entity type would "
                                    + "be an association by another name", field.getName())
                            .isIn(Long.class, String.class, BigDecimal.class));
        }

        @Test
        @DisplayName("the entity is coupled to no other application package, in any signature position")
        void theEntityIsCoupledToNoOtherApplicationPackage() {
            final List<String> offenders = new ArrayList<>();
            final List<Class<?>> referenced = signatureTypes();
            for (final Class<?> type : referenced) {
                for (final String forbidden : FORBIDDEN_PACKAGES) {
                    if (type.getName().startsWith(forbidden + ".")) {
                        offenders.add(type.getName());
                    }
                }
            }

            assertThat(referenced)
                    .as("field types, method returns, method parameters and constructor parameters - the "
                            + "scan must not pass vacuously")
                    .isNotEmpty();
            assertThat(offenders)
                    .as("the entity layer sits at the bottom of the dependency graph. It must not reach "
                            + "into exception, repository, service, controller, batch, security, config or "
                            + "observability, because every one of those depends on it. Scanning the "
                            + "signature surface is stronger than scanning imports: an unused import cannot "
                            + "create coupling, whereas a fully qualified reference would evade a textual "
                            + "search")
                    .isEmpty();
        }

        @Test
        @DisplayName("toString EXCLUDES the address ZIP, which is customer-identifying")
        void toStringExcludesTheAddressZip() {
            final Account account = accountFrom(accountFixture, FIRST_ROW);
            account.setVersion(3L);

            assertThat(account.toString())
                    .as("ACCT-ADDR-ZIP narrows a customer to a locality, so it must never reach a log line "
                            + "through an incidental string conversion. The rendering names the identifier "
                            + "and the version only")
                    .doesNotContain(FIXTURE_ADDRESS_ZIP)
                    .doesNotContain("addressZip")
                    .doesNotContain("acct_addr_zip");
        }

        @Test
        @DisplayName("toString names only the identifier and the version, and no balance either")
        void toStringNamesOnlyTheIdentifierAndTheVersion() {
            final Account account = accountFrom(accountFixture, FIRST_ROW);
            account.setVersion(3L);
            final String rendered = account.toString();

            assertThat(rendered)
                    .as("enough to identify the row in a diagnostic, and no more")
                    .contains("Account{")
                    .contains("accountId=1")
                    .contains("version=3");
            assertThat(rendered)
                    .as("the balances are financial data and the dates narrow the customer further; "
                            + "excluding them keeps an incidental log line free of both")
                    .doesNotContain("194.00")
                    .doesNotContain("2020.00")
                    .doesNotContain(accountFixture.field(
                            FIRST_ROW, EXPIRAION_DATE_COLUMN, TEN_CHARACTER_WIDTH));
        }
    }

    @Nested
    @DisplayName("hostile and boundary input: nulls, over-width values, scales and range extremes")
    class HostileInput {

        @ParameterizedTest
        @ValueSource(strings = {
            "activeStatus", "openDate", "expiraionDate", "reissueDate", "addressZip", "groupId",
        })
        @DisplayName("a null is rejected on every character property, naming its copybook field")
        void aNullIsRejectedOnEveryCharacterProperty(final String property) {
            final Account account = accountFrom(accountFixture, FIRST_ROW);

            assertThatIllegalArgumentException()
                    .as("every column derived from CVACT01Y is NOT NULL, and a COBOL record has no concept "
                            + "of absence, so a null on Account.%s could only ever be a defect. Failing at "
                            + "the boundary names the property and its picture clause; deferring to the "
                            + "flush would surface the same defect as an opaque constraint violation with "
                            + "no field named", property)
                    .isThrownBy(() -> assignText(account, property, null))
                    .withMessageContaining(property)
                    .withMessageContaining("must not be null")
                    .withMessageContaining("NOT NULL CHAR(" + widthOf(property) + ")");
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "currentBalance", "creditLimit", "cashCreditLimit", "currentCycleCredit", "currentCycleDebit",
        })
        @DisplayName("a null is rejected on every money property, naming the NUMERIC(12,2) column")
        void aNullIsRejectedOnEveryMoneyProperty(final String property) {
            final Account account = accountFrom(accountFixture, FIRST_ROW);

            assertThatIllegalArgumentException()
                    .as("a money field with no value has no meaning: PIC S9(10)V99 always holds a number, "
                            + "and zero is that number when nothing has happened", property)
                    .isThrownBy(() -> assignMoney(account, property, null))
                    .withMessageContaining(property)
                    .withMessageContaining("must not be null")
                    .withMessageContaining("NOT NULL NUMERIC(12,2)");
        }

        @Test
        @DisplayName("a null key is rejected, naming the primary key and its copybook locator")
        void aNullKeyIsRejected() {
            final Account account = accountFrom(accountFixture, FIRST_ROW);

            assertThatIllegalArgumentException()
                    .as("ACCT-ID is the 11-byte VSAM key at RKP 0 and the primary key of table account; an "
                            + "instance without it could never be written")
                    .isThrownBy(() -> account.setAccountId(null))
                    .withMessageContaining("accountId (ACCT-ID PIC 9(11))")
                    .withMessageContaining("app/cpy/CVACT01Y.cpy:L5");
        }

        @Test
        @DisplayName("the constructor validates the WHOLE record, not merely the primary key")
        void theConstructorValidatesTheWholeRecord() {
            assertThatIllegalArgumentException()
                    .as("with a valid key and every other argument null, the first guard to fire is the one "
                            + "for the field that follows the key in the copybook, which is what proves the "
                            + "validation is per field and in copybook order rather than key-only")
                    .isThrownBy(() -> new Account(1L, null, null, null, null, null, null, null, null,
                            null, null, null))
                    .withMessageContaining("ACCT-ACTIVE-STATUS")
                    .withMessageContaining("NOT NULL");
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "activeStatus", "openDate", "expiraionDate", "reissueDate", "addressZip", "groupId",
        })
        @DisplayName("an over-width value is rejected, reporting the actual length it was given")
        void anOverWidthValueIsRejected(final String property) {
            final Account account = accountFrom(accountFixture, FIRST_ROW);
            final int width = widthOf(property);
            final String overWidth = "X".repeat(width + 1);

            assertThatIllegalArgumentException()
                    .as("one character past PIC X(%d) would not fit the fixed-width record, so Account.%s "
                            + "refuses it at the boundary instead of letting the column truncate it "
                            + "silently", width, property)
                    .isThrownBy(() -> assignText(account, property, overWidth))
                    .withMessageContaining(property)
                    .withMessageContaining("must be at most " + width + " characters but was " + (width + 1));
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "activeStatus", "openDate", "expiraionDate", "reissueDate", "addressZip", "groupId",
        })
        @DisplayName("a value at exactly the declared width is accepted, because the bound is inclusive")
        void aValueAtExactlyTheDeclaredWidthIsAccepted(final String property) {
            final Account account = accountFrom(accountFixture, FIRST_ROW);
            final String exact = "X".repeat(widthOf(property));
            assignText(account, property, exact);

            assertThat(readText(account, property))
                    .as("Account.%s accepts its full declared width; the guard is a MAXIMUM, not an "
                            + "exact-length requirement", property)
                    .isEqualTo(exact)
                    .hasSize(widthOf(property));
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "activeStatus", "openDate", "expiraionDate", "reissueDate", "addressZip", "groupId",
        })
        @DisplayName("an empty value is accepted and returned verbatim, without padding")
        void anEmptyValueIsAcceptedAndReturnedVerbatim(final String property) {
            final Account account = accountFrom(accountFixture, FIRST_ROW);
            assignText(account, property, "");

            assertThat(readText(account, property))
                    .as("zero characters is under every maximum, so Account.%s accepts an empty value. It "
                            + "is NOT padded on the way in: the fixed-width writer pads at the record "
                            + "boundary, so padding here would double-pad and shift every following field", property)
                    .isEmpty();
        }

        @Test
        @DisplayName("the key range is inclusive at both ends and rejects anything outside it")
        void theKeyRangeIsInclusiveAtBothEnds() {
            final Account account = accountFrom(accountFixture, FIRST_ROW);

            account.setAccountId(0L);
            assertThat(account.getAccountId())
                    .as("zero is accepted because PIC 9(11) is unsigned and the lower bound is inclusive")
                    .isZero();

            account.setAccountId(MAXIMUM_ACCOUNT_ID);
            assertThat(account.getAccountId())
                    .as("the widest eleven-digit value is accepted because the upper bound is inclusive")
                    .isEqualTo(MAXIMUM_ACCOUNT_ID);

            assertThatIllegalArgumentException()
                    .as("PIC 9(11) carries no sign, so a negative key has no representation")
                    .isThrownBy(() -> account.setAccountId(-1L))
                    .withMessageContaining("must be between 0 and 99999999999 inclusive but was -1");
            assertThatIllegalArgumentException()
                    .as("a twelfth digit would not fit the catalogued KEYLEN of 11")
                    .isThrownBy(() -> account.setAccountId(BEYOND_MAXIMUM_ACCOUNT_ID))
                    .withMessageContaining("must be between 0 and 99999999999 inclusive");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> account.setAccountId(Long.MAX_VALUE))
                    .withMessageContaining("ACCT-ID PIC 9(11)");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> account.setAccountId(Long.MIN_VALUE))
                    .withMessageContaining("ACCT-ID PIC 9(11)");
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "currentBalance", "creditLimit", "cashCreditLimit", "currentCycleCredit", "currentCycleDebit",
        })
        @DisplayName("a scale beyond two is rejected rather than silently rounded by the column")
        void aScaleBeyondTwoIsRejected(final String property) {
            final Account account = accountFrom(accountFixture, FIRST_ROW);

            assertThatIllegalArgumentException()
                    .as("PIC S9(10)V99 holds two decimals. A third would be rounded away by the "
                            + "NUMERIC(12,2) column HALF_UP - the wrong mode - so Account.%s refuses it and "
                            + "tells the caller to rescale explicitly with HALF_EVEN instead", property)
                    .isThrownBy(() -> assignMoney(account, property, new BigDecimal("1.234")))
                    .withMessageContaining(property)
                    .withMessageContaining("must carry at most 2 decimal digits but had a scale of 3")
                    .withMessageContaining("RoundingMode.HALF_EVEN");
        }

        @Test
        @DisplayName("an explicitly rescaled value passes the same guard that refused the raw one")
        void anExplicitlyRescaledValuePassesTheGuard() {
            final Account account = accountFrom(accountFixture, FIRST_ROW);
            final BigDecimal rescaled = new BigDecimal("1.235").setScale(MONEY_SCALE, RoundingMode.HALF_EVEN);
            account.setCurrentBalance(rescaled);

            assertThat(account.getCurrentBalance())
                    .as("1.235 rescaled half-even goes to 1.24 because the digit before the tie is odd; "
                            + "HALF_UP would agree here, so the remediation the guard's own message "
                            + "prescribes is asserted rather than merely quoted")
                    .isEqualByComparingTo("1.24");
            assertThat(account.getCurrentBalance().scale()).isEqualTo(MONEY_SCALE);
        }

        @Test
        @DisplayName("the setters re-apply the guards, so an invariant cannot be broken after construction")
        void theSettersReapplyTheGuards() {
            final Account account = accountFrom(accountFixture, FIRST_ROW);

            assertThatIllegalArgumentException()
                    .as("a guard that ran only in the constructor would leave every invariant one setter "
                            + "call away from violation")
                    .isThrownBy(() -> account.setActiveStatus("YY"))
                    .withMessageContaining("ACCT-ACTIVE-STATUS PIC X(01)");
            assertThat(account.getActiveStatus())
                    .as("and the rejected assignment left the previous value intact, so a failed write "
                            + "cannot half-apply")
                    .isEqualTo(accountFixture.field(FIRST_ROW, STATUS_COLUMN, STATUS_WIDTH));
        }

        @Test
        @DisplayName("every one of the thirteen accessor pairs round-trips a value")
        void everyAccessorPairRoundTripsAValue() {
            final Account account = accountFrom(accountFixture, FIRST_ROW);

            account.setAccountId(42L);
            account.setActiveStatus("N");
            account.setCurrentBalance(new BigDecimal("1.01"));
            account.setCreditLimit(new BigDecimal("2.02"));
            account.setCashCreditLimit(new BigDecimal("3.03"));
            account.setOpenDate("2021-02-03");
            account.setExpiraionDate("2026-04-05");
            account.setReissueDate("2023-06-07");
            account.setCurrentCycleCredit(new BigDecimal("4.04"));
            account.setCurrentCycleDebit(new BigDecimal("5.05"));
            account.setAddressZip("A000000001");
            account.setGroupId("ZEROAPR   ");
            account.setVersion(7L);

            assertThat(account.getAccountId()).isEqualTo(42L);
            assertThat(account.getActiveStatus()).isEqualTo("N");
            assertThat(account.getCurrentBalance()).isEqualByComparingTo("1.01");
            assertThat(account.getCreditLimit()).isEqualByComparingTo("2.02");
            assertThat(account.getCashCreditLimit()).isEqualByComparingTo("3.03");
            assertThat(account.getOpenDate()).isEqualTo("2021-02-03");
            assertThat(account.getExpiraionDate()).isEqualTo("2026-04-05");
            assertThat(account.getReissueDate()).isEqualTo("2023-06-07");
            assertThat(account.getCurrentCycleCredit()).isEqualByComparingTo("4.04");
            assertThat(account.getCurrentCycleDebit()).isEqualByComparingTo("5.05");
            assertThat(account.getAddressZip()).isEqualTo("A000000001");
            assertThat(account.getGroupId()).isEqualTo("ZEROAPR   ");
            assertThat(account.getVersion())
                    .as("thirteen properties, thirteen round-trips. This method carried NO @Test annotation "
                            + "before, so every assertion in it silently did not run while the build stayed "
                            + "green - the same failure mode as an uncollected class, one level down. "
                            + "Severity of that omission: Blocker, because it is invisible. Remediation: "
                            + "annotate the method, which is what the annotation above now does")
                    .isEqualTo(7L);
        }
    }
}

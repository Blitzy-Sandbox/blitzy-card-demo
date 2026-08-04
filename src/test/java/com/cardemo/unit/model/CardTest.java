/*
 * ******************************************************************
 * Program     : CardTest.java
 * Component   : Unit test tier, resident at
 *               src/test/java/com/cardemo/unit/model
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM tier, no container, no
 *               Spring context, no database, no live endpoint
 * Function    : Proves that the Card entity reproduces the CVACT02Y
 *               150-byte record contract: the byte arithmetic and the
 *               16-byte key, the column mapping including the preserved
 *               CARD-EXPIRAION-DATE misspelling, the non-disclosure of
 *               the card number and the card verification value, the
 *               non-unique alternate index semantics, and every branch
 *               of every field guard.
 * Source      : app/cpy/CVACT02Y.cpy:L5-L11 (150 B, key 16) @ 7756d89
 * Source      : app/cpy/CVACT01Y.cpy:L11 (sibling ACCT-EXPIRAION-DATE
 *               misspelling) @ 7756d89
 * Source      : app/catlg/LISTCAT.txt:L200,L202 (CARDDATA cluster,
 *               KEYLEN 16 / AVGLRECL 150) @ 7756d89
 * Source      : app/catlg/LISTCAT.txt:L279-L285 (CARDDATA.VSAM.AIX,
 *               KEYLEN 11 / RKP 5 / AXRKP 16 / NONUNIQKEY) @ 7756d89
 * Source      : app/data/ASCII/carddata.txt (7550 B, 50 rows, width
 *               150) @ 7756d89
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

import com.cardemo.model.entity.Card;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit test for the {@link Card} entity, which replaces the {@code CARDDATA} VSAM KSDS cluster.
 *
 * <h2>1. What this test does, and the evidence it rests on</h2>
 *
 * <p>{@code app/cpy/CVACT02Y.cpy} declares a 150-byte record: {@code CARD-NUM X(16)} at :L5,
 * {@code CARD-ACCT-ID 9(11)} at :L6, {@code CARD-CVV-CD 9(03)} at :L7, {@code CARD-EMBOSSED-NAME X(50)} at
 * :L8, {@code CARD-EXPIRAION-DATE X(10)} at :L9, {@code CARD-ACTIVE-STATUS X(01)} at :L10 and
 * {@code FILLER X(59)} at :L11, summing to {@code 16 + 11 + 3 + 50 + 10 + 1 = 91} populated bytes plus 59
 * of filler, which is exactly 150. The copybook's own header comment at :L2 states {@code RECLN 150}, and
 * the catalogue corroborates it physically: {@code app/catlg/LISTCAT.txt:L200} names the cluster and
 * {@code :L202} reports {@code KEYLEN 16} with {@code AVGLRECL 150}, while {@code :L203} reports
 * {@code RKP 0} and {@code MAXLRECL 150}, so the record is fixed width rather than variable.
 *
 * <p>The alternate index is documented at {@code app/catlg/LISTCAT.txt:L279}, whose attributes at
 * {@code :L281} through {@code :L283} give {@code KEYLEN 11}, {@code RKP 5} and {@code AXRKP 16}. Because
 * {@code AXRKP} is zero-based, the alternate key begins at one-based byte 17 - immediately after the
 * 16-byte card number, which is precisely where {@code CARD-ACCT-ID} begins. {@code :L285} declares that
 * index {@code NONUNIQKEY}, which is the catalogued fact behind every non-uniqueness assertion below.
 *
 * <p>Each nested class carries one concern: the record geometry, the column mapping, the retention of
 * verification data, non-disclosure, alternate-index semantics, scope boundaries, the width guards, the
 * account-identifier range guard, the fixture round trip, and construction with accessors.
 *
 * <h2>2. How to run, build and test</h2>
 *
 * <pre>{@code
 * ./mvnw -B -ntp -Ddependency-check.skip=true -Dtest=CardTest test
 * ./mvnw -B -ntp -Ddependency-check.skip=true test
 * }</pre>
 *
 * <p>This class is bound to <strong>Surefire</strong> 3.5.4, which collects {@code **}{@code /*Test.java}
 * under {@code src/test/java} with {@code **}{@code /integration/**} and {@code **}{@code /e2e/**} excluded
 * by path. That is why the file must stay at {@code src/test/java/com/cardemo/unit/model}: a class moved
 * outside that tree is collected by neither Surefire nor Failsafe, so it would silently never run - a green
 * build, both plugins reporting success, and no error or warning anywhere.
 *
 * <h2>3. Key configuration and defaults</h2>
 *
 * <p>There is none to set, which is deliberate.
 *
 * <ul>
 *   <li><strong>No clock is injected, because this entity has no temporal behaviour.</strong>
 *       {@code CARD-EXPIRAION-DATE} is {@code PIC X(10)} and is carried as text that is never parsed, so
 *       there is no instant, zone or locale for a fixed clock to pin. Determinism is achieved by depending
 *       on time not at all: no {@code Instant.now()}, no {@code LocalDate.now()}, no
 *       {@code System.currentTimeMillis()}, no default locale and no default zone appear here. Every
 *       {@code toLowerCase} call passes {@link Locale#ROOT} explicitly.</li>
 *   <li><strong>No mock and no stub.</strong> {@link Card} is a pure data holder with no collaborator, so
 *       there is nothing to stub and Mockito strictness never comes into play. Introducing a mock would add
 *       indirection with no behaviour behind it.</li>
 *   <li><strong>The widths are restated here as this test's own literals</strong>, taken from the copybook
 *       rather than read from the entity, because {@link Card}'s width constants are private. A silent change
 *       to them therefore fails this test instead of moving with it.</li>
 *   <li><strong>No static mutable state.</strong> Every helper is a pure function returning a fresh
 *       instance, so no test can observe another's mutation regardless of execution order.</li>
 * </ul>
 *
 * <h2>4. Findings, classified by severity</h2>
 *
 * <p><i>Blocker - the account identifier property must be named exactly {@code accountId}.</i>
 * {@code com.cardemo.repository.CardRepository} declares the derived finder {@code findByAccountId} and
 * Spring Data resolves derived finders against JavaBean property names, so any other spelling makes the
 * finder unresolvable. Asserted by reflection on the declared field and its accessor.
 *
 * <p><i>Blocker - the card number and the card verification value must never be emitted.</i> Rule 1
 * clause D states "No secrets in code, logs, tests, or config" and names tests explicitly, which is why
 * this file contains no payment-card-shaped literal at all. The 16-character keys used below are
 * deliberately non-numeric or all-but-zero synthetic strings that cannot match an issuer range or satisfy
 * a Luhn check, and no assertion message ever interpolates a card number or a verification value: messages
 * name the record index, the column offset and the width instead. An earlier revision of this file used the
 * canonical Visa and Mastercard test numbers as literals; both were removed as a clause D violation.
 *
 * <p><i>High - the copybook misspelling {@code EXPIRAION} is retained, never corrected.</i>
 * {@code app/cpy/CVACT02Y.cpy:L9} declares {@code CARD-EXPIRAION-DATE}, missing the {@code T} of
 * EXPIRATION, and {@code app/cpy/CVACT01Y.cpy:L11} carries the identical misspelling as
 * {@code ACCT-EXPIRAION-DATE}. It is therefore a corpus-wide spelling and part of the field contract. Both
 * the property {@code expiraionDate} and the column {@code card_expiraion_date} preserve it, and the
 * correctly spelled alternative is asserted absent.
 *
 * <p><i>High - the alternate index is non-unique and must stay that way.</i>
 * {@code app/data/ASCII/carddata.txt} happens to hold 50 distinct card numbers against 50 distinct account
 * identifiers, a one-to-one relationship that is a property of that fixture alone. Reading it as a
 * uniqueness guarantee and adding a unique constraint on {@code card_acct_id} would reject the ordinary
 * case of one account holding several cards, which {@code app/catlg/LISTCAT.txt:L285} shows the legacy
 * system permitted.
 *
 * <p><i>Medium - a verification value must not be reintroduced as a numeric type.</i> A census of all 50
 * rows of the fixture at columns 28-30 finds 8 whose value begins with a zero, so an {@code Integer}
 * mapping would silently drop that zero and break byte-exact re-emission. The entity does not model the
 * field at all, which makes the hazard unreachable; the census is still asserted so the reason stays
 * evidenced rather than remembered.
 *
 * <p><b>Labelled deviation from the assigned field map, with remediation.</b> The specification for this
 * test lists a {@code cvvCode} property mapped to a {@code card_cvv_cd CHAR(3)} column. The implemented
 * system deliberately models no such field, consistently in three places: {@link Card} declares neither
 * the field nor a constructor parameter for it, {@code src/main/resources/db/migration/V1__create_schema.sql}
 * declares {@code CREATE TABLE card} without the column and records why, and no data transfer object
 * carries it. The cited reasons are that verification data must not be retained after authorisation, that
 * the field appears in none of the 17 BMS symbolic maps so no screen ever displayed or accepted it, and
 * that {@code app/cbl/COCRDUPC.cbl} never assigns its update-path field. This test therefore asserts the
 * implemented contract - absence by every route - which is the stronger form of the same requirement, and
 * keeps the three verification bytes inside the 150-byte arithmetic because the copybook is frozen and it
 * is the live column, not the record, that was dropped. Remediation if the field is ever reinstated: map it
 * as {@code String}, never a numeric type, and exclude it from {@link Card#toString()}.
 *
 * <p><b>Not available.</b> Nothing in this tier can observe the physical column type, the primary-key
 * constraint, the foreign key or the index uniqueness flag: those live in the migration and are provable
 * only against a running database, which is the repository integration tier's job and requires a reachable
 * container runtime. Where this file asserts a SQL type it asserts the JPA mapping that requests it, never
 * the type the database reports. No SQL type is invented here.
 *
 * <h2>5. Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>A build failure with no test failure is the {@code -Werror} gate.</strong> Test compilation
 *       runs under {@code -Xlint:all -Werror} with {@code failOnWarning}, so a raw type, an unchecked cast
 *       or a deprecated call here fails the whole build rather than printing a warning. An unused import is
 *       prohibited by Rule 1 clause B but is not machine-detected, because {@code javac} 25 publishes no
 *       {@code unused} lint key - it is caught at review, so leave none behind.</li>
 *   <li><strong>An {@code IllegalArgumentException} naming a classpath resource</strong> means the fixture
 *       copy step has not run; {@code ./mvnw -B -ntp test-compile} copies {@code src/test/resources} to
 *       {@code target/test-classes}. If the resource name itself is wrong, note that the daily transaction
 *       fixture is {@code dailytran.txt} spelled in full: {@code dalytran.txt} does not exist, even though
 *       the mainframe DD name is {@code DALYTRAN}. This class reads only {@code carddata.txt}, by
 *       {@link FixtureLoader.Fixture} constant rather than by a typed-out name, which removes that trap
 *       here.</li>
 *   <li><strong>A width rejection stops firing.</strong> A guard was removed from {@link Card}. Because the
 *       column is {@code CHAR(n) NOT NULL}, the failure would move from construction time to flush time and
 *       lose the stack trace identifying the offending caller.</li>
 *   <li><strong>The maximum-not-exact assertions fail.</strong> A guard was tightened to demand an exact
 *       length, which would reject every row the seed migration loads, since padding to the fixed width
 *       belongs at the emission boundary and not in the entity.</li>
 *   <li><strong>A non-disclosure assertion fails.</strong> {@link Card#toString()} was widened, or a
 *       masking or debug-rendering helper was added. Neither is this entity's concern: presentation-layer
 *       masking belongs to the data transfer object tier.</li>
 * </ul>
 */
class CardTest {

    /** {@code CARD-NUM PIC X(16)} at CVACT02Y:L5, and the catalogued key length at LISTCAT:L202. */
    private static final int CARD_NUMBER_WIDTH = 16;

    /** Digit count of {@code CARD-ACCT-ID PIC 9(11)} at CVACT02Y:L6, and the AIX key length at :L281. */
    private static final int ACCOUNT_ID_WIDTH = 11;

    /** {@code CARD-CVV-CD PIC 9(03)} at CVACT02Y:L7 - three bytes of the record, not a column. */
    private static final int CVV_WIDTH = 3;

    /** {@code CARD-EMBOSSED-NAME PIC X(50)} at CVACT02Y:L8. */
    private static final int EMBOSSED_NAME_WIDTH = 50;

    /** {@code CARD-EXPIRAION-DATE PIC X(10)} at CVACT02Y:L9, misspelling preserved. */
    private static final int EXPIRY_WIDTH = 10;

    /** {@code CARD-ACTIVE-STATUS PIC X(01)} at CVACT02Y:L10. */
    private static final int STATUS_WIDTH = 1;

    /** {@code FILLER PIC X(59)} at CVACT02Y:L11, deliberately not modelled. */
    private static final int FILLER_WIDTH = 59;

    /** The populated width of {@code CARD-RECORD}: {@code 16 + 11 + 3 + 50 + 10 + 1}. */
    private static final int POPULATED_WIDTH = 91;

    /** The catalogued record length for CARDDATA, {@code AVGLRECL 150} at LISTCAT:L202. */
    private static final int RECORD_LENGTH = 150;

    /** Rows in {@code app/data/ASCII/carddata.txt}, matching {@code REC-TOTAL 50} at LISTCAT:L207. */
    private static final int FIXTURE_ROW_COUNT = 50;

    /** Rows of the fixture whose verification value at columns 28-30 begins with a zero. */
    private static final int LEADING_ZERO_CVV_ROWS = 8;

    /** Persisted fields on the entity: five from the copybook plus the optimistic-locking version. */
    private static final int PERSISTENT_FIELD_COUNT = 6;

    /** The widest {@code PIC 9(11)} value, and therefore the inclusive upper bound of the range guard. */
    private static final long MAX_ACCOUNT_ID = 99_999_999_999L;

    /**
     * A 16-character key that is deliberately not payment-card shaped.
     *
     * <p>{@code CARD-NUM} is {@code PIC X(16)}, so text is a legitimate value for the field and this
     * satisfies the contract while being incapable of matching an issuer range or a Luhn check. Rule 1
     * clause D forbids secrets in tests, and a card number is the archetypal example, so no
     * payment-card-shaped literal appears anywhere in this file.
     */
    private static final String SYNTHETIC_KEY = "CARDNUM-TEST-001";

    /** A second synthetic key, for the cases that need two distinct cards. */
    private static final String SYNTHETIC_KEY_ALT = "CARDNUM-TEST-002";

    /**
     * A 16-digit key whose leading zeros are significant.
     *
     * <p>Used to prove that a {@code PIC X(16)} key round-trips byte for byte, which is the reason the
     * field is text rather than numeric. The value is not Luhn-valid and carries no issuer prefix.
     */
    private static final String LEADING_ZERO_KEY = "0000000000000003";

    /** An embossed name holding {@code A} through {@code R}, the zoned-decimal overpunch characters. */
    private static final String OVERPUNCH_LETTER_NAME = "ABCDEFGHIJKLMNOPQR";

    /** JPA association annotations, none of which may appear on this entity. */
    private static final Set<String> ASSOCIATION_ANNOTATIONS = Set.of(
            "ManyToOne", "OneToMany", "OneToOne", "ManyToMany", "JoinColumn", "JoinColumns",
            "JoinTable", "ElementCollection", "Embedded", "EmbeddedId", "MapsId");

    /** Field types that must never appear on this entity: it has no monetary or floating-point value. */
    private static final Set<String> FORBIDDEN_FIELD_TYPES = Set.of(
            "java.math.BigDecimal", "java.math.BigInteger", "float", "double",
            "java.lang.Float", "java.lang.Double");

    /** Packages this entity must not couple itself to, keeping the model tier free of upward dependencies. */
    private static final Set<String> FORBIDDEN_PACKAGES = Set.of(
            "com.cardemo.exception", "com.cardemo.repository", "com.cardemo.service",
            "com.cardemo.controller", "com.cardemo.batch", "com.cardemo.security",
            "com.cardemo.config", "com.cardemo.observability", "com.cardemo.model.key",
            "com.cardemo.model.enums");

    /**
     * Returns a valid card carrying the synthetic key. A fresh instance every call, so no test shares state.
     *
     * @return a populated, valid {@link Card}
     */
    private static Card validCard() {
        return new Card(SYNTHETIC_KEY, 1L, "FNAMEAA6 LNAME6", "2025-01-01", "Y");
    }

    /**
     * Returns the persisted, non-static fields of {@link Card} in declaration order.
     *
     * <p>Static constants are filtered out because they are not part of the mapping, and synthetic fields
     * are filtered out because coverage instrumentation adds one: JaCoCo injects a static synthetic
     * {@code $jacocoData} field, so an unfiltered count would differ between an instrumented and a plain
     * run and make this suite report a different result under {@code test} than under {@code verify}.
     *
     * @return the mapped instance fields
     */
    private static List<Field> persistentFields() {
        final List<Field> fields = new ArrayList<>();
        for (final Field field : Card.class.getDeclaredFields()) {
            if (!field.isSynthetic() && !Modifier.isStatic(field.getModifiers())) {
                fields.add(field);
            }
        }
        return fields;
    }

    /**
     * Returns the simple names of every annotation declared on a member, for name-based checks.
     *
     * <p>Comparing simple names keeps this test from importing a dozen annotation types purely to assert
     * that none of them is present, which would leave unused imports behind the moment one is dropped.
     *
     * @param annotations the annotations to name
     * @return their simple names
     */
    private static List<String> annotationNames(final Annotation[] annotations) {
        final List<String> names = new ArrayList<>();
        for (final Annotation annotation : annotations) {
            names.add(annotation.annotationType().getSimpleName());
        }
        return names;
    }

    /**
     * Returns the {@link Column} mapping of a named field.
     *
     * @param fieldName the Java property name
     * @return the column annotation on that field
     * @throws ReflectiveOperationException if the field does not exist, which is itself the finding
     */
    private static Column columnOf(final String fieldName) throws ReflectiveOperationException {
        return Card.class.getDeclaredField(fieldName).getAnnotation(Column.class);
    }

    /**
     * Loads the card fixture by classpath resource name, through the shared loader.
     *
     * @return the immutable fixture snapshot
     */
    private static FixtureLoader.FixtureData cardFixture() {
        return FixtureLoader.load(FixtureLoader.Fixture.CARD);
    }

    @Nested
    @DisplayName("record geometry: 150 bytes, a 16-byte primary key, the alternate key at byte 17")
    class RecordGeometry {

        @Test
        @DisplayName("the six copybook field widths plus the filler sum to the catalogued 150 bytes")
        void theCopybookWidthsPlusFillerSumTo150() {
            // The three verification bytes stay in this arithmetic even though no column holds them. The
            // COPYBOOK is the record and it is frozen: CARD-RECORD is still 150 bytes wide and every
            // fixture row still carries all 150. What was removed is the live column, not the record, so
            // an arithmetic that dropped the field would be asserting the wrong thing.
            final int populated = CARD_NUMBER_WIDTH + ACCOUNT_ID_WIDTH + CVV_WIDTH + EMBOSSED_NAME_WIDTH
                    + EXPIRY_WIDTH + STATUS_WIDTH;

            assertThat(populated)
                    .as("16 + 11 + 3 + 50 + 10 + 1 = 91 populated bytes from app/cpy/CVACT02Y.cpy:L5-L10")
                    .isEqualTo(POPULATED_WIDTH);
            assertThat(populated + FILLER_WIDTH)
                    .as("91 populated plus the 59-byte FILLER at app/cpy/CVACT02Y.cpy:L11 is exactly the "
                            + "record length catalogued for CARDDATA as AVGLRECL 150 at "
                            + "app/catlg/LISTCAT.txt:L202, and restated as RECLN 150 by the copybook's own "
                            + "header comment at :L2")
                    .isEqualTo(RECORD_LENGTH);
        }

        @Test
        @DisplayName("the card number is the whole 16-byte VSAM primary key, with no composite component")
        void theCardNumberIsTheSixteenByteKey() {
            assertThat(validCard().getCardNumber())
                    .as("CARDDATA is catalogued with KEYLEN 16 at app/catlg/LISTCAT.txt:L202 and RKP 0 at "
                            + ":L203, so the key is CARD-NUM in full, starting at the first byte")
                    .hasSize(CARD_NUMBER_WIDTH);
        }

        @Test
        @DisplayName("the account id begins at byte 17, exactly where the alternate index is anchored")
        void theAccountIdBeginsAtTheAlternateKeyOffset() {
            assertThat(CARD_NUMBER_WIDTH + 1)
                    .as("app/catlg/LISTCAT.txt:L283 reports AXRKP 16 for CARDDATA.VSAM.AIX. AXRKP is "
                            + "zero-based, so the alternate key begins at one-based byte 17 - immediately "
                            + "after the 16-byte card number, which is precisely where CARD-ACCT-ID begins. "
                            + "That correspondence is what makes findByAccountId the correct replacement "
                            + "for the alternate index rather than an invented convenience")
                    .isEqualTo(17);
            assertThat(ACCOUNT_ID_WIDTH)
                    .as("and :L281 reports KEYLEN 11 for that index, which is exactly the width of "
                            + "CARD-ACCT-ID PIC 9(11) - the index covers the whole field and nothing else")
                    .isEqualTo(11);
        }

        @Test
        @DisplayName("the account id property is named exactly accountId, which the derived finder requires")
        void theAccountIdPropertyIsNamedExactlyAccountId() throws ReflectiveOperationException {
            assertThat(persistentFields())
                    .as("Blocker: com.cardemo.repository.CardRepository declares the derived finder "
                            + "findByAccountId, and Spring Data resolves derived finders against JavaBean "
                            + "property names. A rename to acctId or cardAcctId would make the finder "
                            + "unresolvable and fail context startup")
                    .anyMatch(field -> "accountId".equals(field.getName()))
                    .noneMatch(field -> "acctId".equals(field.getName()))
                    .noneMatch(field -> "cardAcctId".equals(field.getName()));
            assertThat(Card.class.getMethod("getAccountId").getReturnType())
                    .as("the accessor must match the property name and return the scalar type, because "
                            + "that pairing is what the derived finder resolves against")
                    .isEqualTo(Long.class);
        }

        @Test
        @DisplayName("the account id is a Long, because PIC 9(11) overflows int")
        void theAccountIdIsALong() throws ReflectiveOperationException {
            assertThat(Card.class.getDeclaredField("accountId").getType())
                    .as("the widest PIC 9(11) value is 99,999,999,999, which exceeds Integer.MAX_VALUE of "
                            + "2,147,483,647, so an int field would silently wrap")
                    .isEqualTo(Long.class);
            assertThat(String.valueOf(MAX_ACCOUNT_ID))
                    .as("and the bound really is eleven digits wide, matching the picture clause")
                    .hasSize(ACCOUNT_ID_WIDTH);
        }

        @Test
        @DisplayName("the trailing FILLER is not modelled, so exactly six fields are persisted")
        void theTrailingFillerIsNotModelled() {
            assertThat(persistentFields())
                    .as("FILLER X(59) at app/cpy/CVACT02Y.cpy:L11 pads the record to the catalogued length "
                            + "and carries no data - it is blank in all 50 fixture rows - so modelling it "
                            + "would add a column that could only ever hold 59 spaces")
                    .noneMatch(field -> field.getName().toLowerCase(Locale.ROOT).contains("filler"));
            assertThat(persistentFields())
                    .as("five copybook fields are persisted, CARD-CVV-CD is not, and the version column is "
                            + "added by the migration: five plus one is six")
                    .hasSize(PERSISTENT_FIELD_COUNT);
        }
    }

    @Nested
    @DisplayName("column mapping: table, key, column names, nullability and the preserved misspelling")
    class ColumnMapping {

        @Test
        @DisplayName("the class is an @Entity mapped to the table named card")
        void theClassIsAnEntityMappedToTheCardTable() {
            assertThat(Card.class.getAnnotation(Entity.class))
                    .as("without @Entity the type is an ordinary bean and nothing persists it")
                    .isNotNull();
            assertThat(Card.class.getAnnotation(Table.class))
                    .as("@Table must be declared explicitly rather than left to the default naming strategy")
                    .isNotNull();
            assertThat(Card.class.getAnnotation(Table.class).name())
                    .as("the table is card, which is what V1__create_schema.sql creates")
                    .isEqualTo("card");
        }

        @Test
        @DisplayName("the card number is the @Id and carries no @GeneratedValue")
        void theCardNumberIsTheIdAndIsNotGenerated() throws ReflectiveOperationException {
            assertThat(Card.class.getDeclaredField("cardNumber").getAnnotation(Id.class))
                    .as("CARDDATA's key is CARD-NUM, so the entity's identifier is the card number itself")
                    .isNotNull();
            for (final Field field : persistentFields()) {
                assertThat(annotationNames(field.getAnnotations()))
                        .as("no field may carry @GeneratedValue: the key is a natural, externally assigned "
                                + "16-character value, and generating it would both replace the legacy "
                                + "identifier and break the parity comparison against the fixture")
                        .doesNotContain("GeneratedValue");
            }
        }

        @ParameterizedTest
        @CsvSource({
            "cardNumber,    card_num,             16",
            "accountId,     card_acct_id,         0",
            "embossedName,  card_embossed_name,   50",
            "expiraionDate, card_expiraion_date,  10",
            "activeStatus,  card_active_status,   1",
        })
        @DisplayName("every copybook field maps to its snake_case column, NOT NULL, at the copybook width")
        void everyFieldMapsToItsColumnNotNullAtTheCopybookWidth(final String property,
                                                                final String columnName,
                                                                final int width)
                throws ReflectiveOperationException {
            final Column column = columnOf(property);

            assertThat(column)
                    .as("%s must declare its column explicitly rather than rely on the naming strategy",
                            property)
                    .isNotNull();
            assertThat(column.name())
                    .as("the column name is fixed by V1__create_schema.sql and a divergence fails startup "
                            + "outright, because spring.jpa.hibernate.ddl-auto is validate in every profile")
                    .isEqualTo(columnName);
            assertThat(column.nullable())
                    .as("every column of the card table is NOT NULL: a fixed-width COBOL record has no "
                            + "concept of an absent field, only of a blank one")
                    .isFalse();
            if (width > 0) {
                assertThat(column.length())
                        .as("%s is declared CHAR(%d) from its PIC clause, and a narrower or wider mapping "
                                + "would silently truncate or pad at the byte boundary", property, width)
                        .isEqualTo(width);
            }
        }

        @Test
        @DisplayName("the account id maps to NUMERIC(11) with scale zero, not to a character column")
        void theAccountIdMapsToNumericElevenScaleZero() throws ReflectiveOperationException {
            final Column column = columnOf("accountId");

            assertThat(column.precision())
                    .as("CARD-ACCT-ID is PIC 9(11), so the precision is the digit count of the picture "
                            + "clause and matches the AIX key length of 11 at app/catlg/LISTCAT.txt:L281")
                    .isEqualTo(ACCOUNT_ID_WIDTH);
            assertThat(column.scale())
                    .as("the picture clause declares no V and no decimal positions, so the identifier is a "
                            + "whole number and the scale is zero")
                    .isZero();
        }

        @Test
        @DisplayName("the version column is present and annotated @Version")
        void theVersionColumnIsPresentAndAnnotated() throws ReflectiveOperationException {
            final Field version = Card.class.getDeclaredField("version");

            assertThat(version.getAnnotation(Version.class))
                    .as("Card is one of exactly four versioned entities. The counter has no counterpart in "
                            + "app/cpy/CVACT02Y.cpy and is added by the migration, because the card update "
                            + "conversation in app/cbl/COCRDUPC.cbl mirrors the account update pattern and "
                            + "needs a store-level concurrency guard")
                    .isNotNull();
            assertThat(version.getType())
                    .as("a Long counter, wide enough that it cannot realistically wrap")
                    .isEqualTo(Long.class);
            assertThat(columnOf("version").name())
                    .as("the column is plainly named version, matching V1__create_schema.sql")
                    .isEqualTo("version");
            assertThat(columnOf("version").nullable())
                    .as("the version column is NOT NULL like every other column on this table")
                    .isFalse();
        }

        @Test
        @DisplayName("the expiry property and column preserve the copybook's EXPIRAION misspelling")
        void theExpiryPropertyAndColumnPreserveTheMisspelling() throws ReflectiveOperationException {
            assertThat(persistentFields())
                    .as("app/cpy/CVACT02Y.cpy:L9 reads CARD-EXPIRAION-DATE, missing the T of EXPIRATION - "
                            + "the same misspelling app/cpy/CVACT01Y.cpy:L11 carries as "
                            + "ACCT-EXPIRAION-DATE. It is a corpus-wide spelling and part of the field "
                            + "contract, so both are preserved and the traceability mapping stays "
                            + "one-to-one. Correcting it would break the column contract silently")
                    .anyMatch(field -> "expiraionDate".equals(field.getName()))
                    .noneMatch(field -> "expirationDate".equals(field.getName()));
            assertThat(columnOf("expiraionDate").name())
                    .as("and the column carries the misspelling too, because that is what the migration "
                            + "declares")
                    .isEqualTo("card_expiraion_date")
                    .isNotEqualTo("card_expiration_date");
        }

        @Test
        @DisplayName("the expiry date is text, never a temporal type, because dates in this system are TEXT")
        void theExpiryDateIsTextNeverTemporal() throws ReflectiveOperationException {
            assertThat(Card.class.getDeclaredField("expiraionDate").getType())
                    .as("CARD-EXPIRAION-DATE is PIC X(10), a character field. Mapping it to LocalDate "
                            + "would impose a parse the legacy system never performed, and would reject or "
                            + "silently rewrite any value the corpus accepted - a blank field, a partial "
                            + "date, or a day the calendar does not have")
                    .isEqualTo(String.class);
            assertThat(Card.class.getDeclaredField("expiraionDate").getType().getName())
                    .as("stated as a type name as well, so the assertion reads unambiguously against the "
                            + "java.time alternatives it excludes")
                    .isEqualTo("java.lang.String");
            assertThat(annotationNames(Card.class.getDeclaredField("expiraionDate").getAnnotations()))
                    .as("and it carries no @Temporal, which would only be meaningful on a date type")
                    .doesNotContain("Temporal");
        }

        @Test
        @DisplayName("no field is a BigDecimal or a floating-point type: this entity holds no money")
        void noFieldIsADecimalOrFloatingPointType() {
            for (final Field field : persistentFields()) {
                assertThat(FORBIDDEN_FIELD_TYPES)
                        .as("the card layout declares no PIC S9(n)V99, no COMP-3 and no rate, so there is "
                                + "no monetary field here at all. The project-wide prohibition on float "
                                + "and double in a financial field is satisfied trivially, and no "
                                + "BigDecimal may be introduced either: field %s is a %s",
                                field.getName(), field.getType().getName())
                        .doesNotContain(field.getType().getName());
            }
        }

        @Test
        @DisplayName("a protected no-argument constructor exists, as the JPA specification requires")
        void aProtectedNoArgumentConstructorExists() throws ReflectiveOperationException {
            final int modifiers = Card.class.getDeclaredConstructor().getModifiers();

            assertThat(Modifier.isPrivate(modifiers))
                    .as("JPA instantiates the entity reflectively and then populates fields, so the "
                            + "no-argument constructor must not be private")
                    .isFalse();
            assertThat(Modifier.isProtected(modifiers))
                    .as("protected rather than public, so application code is steered to the validating "
                            + "constructor and cannot create a half-populated instance by accident")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("verification data: absent by every route, and the leading-zero hazard that made it so")
    class VerificationValueRetention {

        @Test
        @DisplayName("no field, accessor, mutator or column carries the verification value")
        void theVerificationValueIsAbsentByEveryRoute() {
            // Each route is checked separately, because closing one and leaving another open would be
            // indistinguishable from closing none.
            assertThat(persistentFields())
                    .as("a persistent field would be retention, whatever it were named")
                    .noneMatch(field -> field.getName().toLowerCase(Locale.ROOT).contains("cvv"));
            assertThat(Card.class.getDeclaredMethods())
                    .as("an accessor or mutator would be an exposure route even with no field behind it")
                    .noneMatch(method -> method.getName().toLowerCase(Locale.ROOT).contains("cvv"));
            for (final Field field : persistentFields()) {
                final Column column = field.getAnnotation(Column.class);
                assertThat(column.name())
                        .as("and no column mapping may name the verification column either")
                        .doesNotContain("cvv");
            }
        }

        @Test
        @DisplayName("the validating constructor takes five arguments, none of them a verification value")
        void theConstructorTakesFiveArgumentsAndNoVerificationValue() {
            assertThat(Card.class.getConstructors())
                    .as("exactly one public constructor, so there is no second door into the type")
                    .hasSize(1);
            assertThat(Card.class.getConstructors()[0].getParameterCount())
                    .as("five parameters: the card number, the account id, the embossed name, the expiry "
                            + "text and the status. The verification value is not among them, so a caller "
                            + "has nothing to supply and no accessor to read one back")
                    .isEqualTo(5);
        }

        @Test
        @DisplayName("the fixture really does carry leading-zero verification values, 8 of its 50 rows")
        void theFixtureCarriesLeadingZeroVerificationValues() {
            final FixtureLoader.FixtureData fixture = cardFixture();
            int leadingZeroRows = 0;
            for (int row = 0; row < fixture.recordCount(); row++) {
                final String value = fixture.field(row, 28, CVV_WIDTH);
                assertThat(value)
                        .as("the verification value occupies columns 28-30 of every record, per "
                                + "app/cpy/CVACT02Y.cpy:L7 - row %d", row)
                        .hasSize(CVV_WIDTH);
                assertThat(value.chars().allMatch(Character::isDigit))
                        .as("PIC 9(03) is numeric, so every one of the three characters is a digit - row %d",
                                row)
                        .isTrue();
                if (value.startsWith("0")) {
                    leadingZeroRows++;
                }
            }

            assertThat(leadingZeroRows)
                    .as("a census of all 50 rows at columns 28-30 finds 8 whose value begins with a zero. "
                            + "This is the evidence behind the mapping decision, kept as an assertion so "
                            + "the reason stays proven rather than remembered. The values themselves are "
                            + "deliberately not reproduced in this message: Rule 1 clause D forbids "
                            + "secrets in tests and names tests explicitly")
                    .isEqualTo(LEADING_ZERO_CVV_ROWS);
        }

        @Test
        @DisplayName("a numeric mapping would drop the leading zero, which is why text is the only option")
        void aNumericMappingWouldDropTheLeadingZero() {
            final FixtureLoader.FixtureData fixture = cardFixture();
            int narrowedByNumericRoundTrip = 0;
            for (int row = 0; row < fixture.recordCount(); row++) {
                final String value = fixture.field(row, 28, CVV_WIDTH);
                // The round trip is performed on the LENGTH only. No parsed or re-rendered verification
                // value is compared, asserted on, or placed in a message anywhere in this method.
                if (String.valueOf(Integer.parseInt(value)).length() < value.length()) {
                    narrowedByNumericRoundTrip++;
                }
            }

            assertThat(narrowedByNumericRoundTrip)
                    .as("parsing each value as an Integer and rendering it back yields a SHORTER string "
                            + "for exactly the 8 leading-zero rows, so an Integer column would corrupt "
                            + "them and break byte-exact re-emission. Were the field ever reinstated it "
                            + "must be String, never a numeric type")
                    .isEqualTo(LEADING_ZERO_CVV_ROWS);
        }

        @Test
        @DisplayName("a text key preserves its leading zeros byte for byte through the entity")
        void aTextKeyPreservesItsLeadingZeros() {
            final Card card = new Card(LEADING_ZERO_KEY, 1L, "NAME", "2025-01-01", "Y");

            assertThat(card.getCardNumber())
                    .as("this is the positive form of the same property: CARD-NUM is PIC X(16), so the "
                            + "entity stores and returns the exact 16 characters supplied, leading zeros "
                            + "included. A numeric key would have rendered back as a single digit")
                    .isEqualTo(LEADING_ZERO_KEY)
                    .hasSize(CARD_NUMBER_WIDTH)
                    .startsWith("0");
        }

        @Test
        @DisplayName("no overpunch decoding is applied to this fixture, which carries no signed field")
        void noOverpunchDecodingIsAppliedToThisFixture() {
            final Card card = new Card(SYNTHETIC_KEY, 1L, OVERPUNCH_LETTER_NAME, "2025-01-01", "Y");

            assertThat(card.getEmbossedName())
                    .as("carddata.txt has NO signed field: CARD-ACCT-ID is PIC 9(11) unsigned and "
                            + "CARD-CVV-CD is PIC 9(03) unsigned, so no zoned-decimal overpunch decoder may "
                            + "be run over it. The letters A through R are the positive and negative "
                            + "overpunch symbols, and 48 of the 50 fixture rows contain at least one of "
                            + "them inside CARD-EMBOSSED-NAME, where they are ordinary text. A decoder run "
                            + "over a text column would rewrite those names into digits")
                    .isEqualTo(OVERPUNCH_LETTER_NAME);
            assertThat(cardFixture().field(0, 17, ACCOUNT_ID_WIDTH).chars().allMatch(Character::isDigit))
                    .as("and the account id slice is plain digits with no trailing sign symbol, which is "
                            + "the direct evidence that the field is unsigned")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("non-disclosure: the card number, verification value and cardholder name never leave")
    class NonDisclosure {

        @Test
        @DisplayName("toString discloses neither the card number nor the embossed name nor the expiry")
        void toStringDisclosesNoSensitiveValue() {
            final Card card = new Card(LEADING_ZERO_KEY, 7L, OVERPUNCH_LETTER_NAME, "2031-12-25", "Y");
            card.setVersion(4L);

            final String rendered = card.toString();

            assertThat(rendered)
                    .as("Blocker: toString is the single most likely route by which an entity leaks into a "
                            + "log line, an exception message or a stack trace, because it is invoked "
                            + "implicitly - by string concatenation, by a logging placeholder, by a "
                            + "debugger and by an agent capturing local variables - on paths no reviewer "
                            + "sees. The card number must never appear")
                    .doesNotContain(LEADING_ZERO_KEY);
            assertThat(rendered)
                    .as("nor the embossed name, which is cardholder personal data")
                    .doesNotContain(OVERPUNCH_LETTER_NAME);
            assertThat(rendered)
                    .as("nor the expiry date, which paired with a card number completes the data set a "
                            + "card-not-present transaction needs, and which identifies no row so has no "
                            + "correlation value to trade against that risk")
                    .doesNotContain("2031-12-25");
            assertThat(rendered.toLowerCase(Locale.ROOT))
                    .as("and the word cvv must not appear under any casing")
                    .doesNotContain("cvv");
        }

        @Test
        @DisplayName("toString renders only the account id and the version, which is what makes a log useful")
        void toStringRendersOnlyTheAccountIdAndVersion() {
            final Card card = validCard();
            card.setAccountId(42L);
            card.setVersion(9L);

            assertThat(card.toString())
                    .as("what remains is the minimum a log line needs: a surrogate identifier with no "
                            + "payment or personal content, plus the optimistic-locking counter. The "
                            + "permitted upper bound also allows the status and the expiry; this "
                            + "implementation is deliberately narrower, which satisfies the same rule")
                    .isEqualTo("Card{accountId=42, version=9}");
        }

        @Test
        @DisplayName("toString is stable with no values set, so a transient instance cannot break a log")
        void toStringIsStableOnATransientInstance() {
            assertThat(validCard().toString())
                    .as("the version is null until the provider assigns it on first flush, and the "
                            + "rendering uses plain concatenation rather than a formatter, so it depends on "
                            + "no default locale and is byte-identical on every machine")
                    .isEqualTo("Card{accountId=1, version=null}");
        }

        @Test
        @DisplayName("there is no masking, partial-display or debug-rendering helper of any name")
        void thereIsNoMaskingOrDebugRenderingHelper() {
            assertThat(Card.class.getMethods())
                    .as("there is no getMaskedCardNumber and no masking helper under any other name. "
                            + "Masking is a presentation concern owned by the data transfer object tier, "
                            + "not by the entity: a helper here would be a second rendering path to "
                            + "review, and one that returns part of a card number by design")
                    .noneMatch(method -> {
                        final String name = method.getName().toLowerCase(Locale.ROOT);
                        return name.contains("mask") || name.contains("redact") || name.contains("obfuscat")
                                || name.contains("debug") || name.contains("dump");
                    });
        }

        @Test
        @DisplayName("the entity does not implement Serializable, so no serialVersionUID gate applies")
        void theEntityDoesNotImplementSerializable() {
            assertThat(Serializable.class.isAssignableFrom(Card.class))
                    .as("under -Xlint:all -Werror a Serializable type without an explicit "
                            + "serialVersionUID is a hard build failure, so the production type "
                            + "deliberately avoids the interface. Avoiding it also removes an insecure "
                            + "deserialization surface over a type holding a card number, which Rule 1 "
                            + "clause D names as a risky pattern to flag")
                    .isFalse();
            assertThat(Card.class.getInterfaces())
                    .as("the type implements no interface at all, so the mapping can be read in one place")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("alternate index semantics: non-unique, so one account may hold many cards")
    class AlternateIndexSemantics {

        @Test
        @DisplayName("two distinct cards may share one account id, which the entity permits")
        void twoDistinctCardsMayShareOneAccountId() {
            final Card first = new Card(SYNTHETIC_KEY, 500L, "NAME ONE", "2025-01-01", "Y");
            final Card second = new Card(SYNTHETIC_KEY_ALT, 500L, "NAME TWO", "2026-01-01", "Y");

            assertThat(second.getAccountId())
                    .as("High: app/catlg/LISTCAT.txt:L285 declares CARDDATA.VSAM.AIX as NONUNIQKEY, and "
                            + "UNIQUEKEY appears nowhere in the catalogue's 3,956 lines. Non-unique is a "
                            + "catalogued fact rather than a relaxation, and it is the ordinary case: one "
                            + "account legitimately holds several cards. A unique constraint on "
                            + "card_acct_id would reject this pair outright")
                    .isEqualTo(first.getAccountId());
            assertThat(second)
                    .as("and the two remain distinct entities, because identity is the card number alone - "
                            + "the shared account id is an attribute, never part of the key")
                    .isNotEqualTo(first);
        }

        @Test
        @DisplayName("the fixture's one-to-one card-to-account ratio is a coincidence, not a guarantee")
        void theFixtureOneToOneRatioIsACoincidence() {
            final FixtureLoader.FixtureData fixture = cardFixture();
            final List<String> accountIds = new ArrayList<>();
            for (int row = 0; row < fixture.recordCount(); row++) {
                accountIds.add(fixture.field(row, 17, ACCOUNT_ID_WIDTH));
            }

            assertThat(fixture.recordCount())
                    .as("the fixture holds 50 rows, matching REC-TOTAL 50 at app/catlg/LISTCAT.txt:L207")
                    .isEqualTo(FIXTURE_ROW_COUNT);
            assertThat(accountIds).doesNotHaveDuplicates();
            assertThat(accountIds)
                    .as("all 50 account ids are distinct, so this fixture happens to pair one card with "
                            + "one account. That is a property of these 50 rows and of nothing else. "
                            + "Inferring uniqueness from it and adding a unique constraint is the exact "
                            + "mistake this assertion exists to name, which is why the ratio is recorded "
                            + "here as a coincidence rather than left to be rediscovered as a rule")
                    .hasSize(FIXTURE_ROW_COUNT);
        }

        @Test
        @DisplayName("the account id is a plain scalar, so the derived finder resolves against it directly")
        void theAccountIdIsAPlainScalar() throws ReflectiveOperationException {
            assertThat(annotationNames(Card.class.getDeclaredField("accountId").getAnnotations()))
                    .as("modelling the owner as @ManyToOne Account would rename the derived finder to "
                            + "findByAccountAccountId and break findByAccountId, and would add a lazy "
                            + "proxy on every card read for no benefit, since nothing on this entity "
                            + "navigates to the account. Referential integrity belongs to the foreign key "
                            + "in the migration instead")
                    .doesNotContainAnyElementsOf(ASSOCIATION_ANNOTATIONS);
        }
    }

    @Nested
    @DisplayName("scope boundaries: no associations and no coupling above the model tier")
    class ScopeBoundaries {

        @Test
        @DisplayName("no field carries a JPA association annotation of any kind")
        void noFieldCarriesAnAssociationAnnotation() {
            for (final Field field : persistentFields()) {
                assertThat(annotationNames(field.getAnnotations()))
                        .as("field %s must be a plain mapped column: this entity has no association, no "
                                + "fetch or cascade declaration and no @Lob, so the whole mapping is "
                                + "readable in one place", field.getName())
                        .doesNotContainAnyElementsOf(ASSOCIATION_ANNOTATIONS);
            }
        }

        @Test
        @DisplayName("no field type comes from a package above the model tier")
        void noFieldTypeComesFromAboveTheModelTier() {
            for (final Field field : persistentFields()) {
                final String typeName = field.getType().getName();
                for (final String forbidden : FORBIDDEN_PACKAGES) {
                    assertThat(typeName)
                            .as("field %s is a %s. The entity tier must not depend on the exception, "
                                    + "repository, service, controller, batch, security, config or "
                                    + "observability packages, nor on model.key or model.enums, which this "
                                    + "entity uses neither of: a composite key would contradict the "
                                    + "single-column primary key, and the status flag stays the raw "
                                    + "character the copybook declares", field.getName(), typeName)
                            .doesNotStartWith(forbidden + ".");
                }
            }
        }

        @Test
        @DisplayName("every field type is a JDK type, so the entity carries no cross-tier coupling at all")
        void everyFieldTypeIsAJdkType() {
            for (final Field field : persistentFields()) {
                assertThat(field.getType().getName())
                        .as("field %s should be one of the small set of JDK types a fixed-width record "
                                + "needs; anything else is a coupling worth justifying", field.getName())
                        .startsWith("java.lang.");
            }
        }
    }

    @Nested
    @DisplayName("width guards: null rejected, over-width rejected, shorter accepted verbatim")
    class WidthGuards {

        @Test
        @DisplayName("a null card number is rejected, naming the property, the COBOL field and the width")
        void aNullCardNumberIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new Card(null, 1L, "NAME", "2025-01-01", "Y"))
                    .withMessageContaining("cardNumber")
                    .withMessageContaining("CARD-NUM")
                    .withMessageContaining("16")
                    .withNoCause();
        }

        @ParameterizedTest
        @CsvSource({
            "embossedName,  CARD-EMBOSSED-NAME,  50",
            "expiraionDate, CARD-EXPIRAION-DATE, 10",
            "activeStatus,  CARD-ACTIVE-STATUS,  1",
        })
        @DisplayName("a null value is rejected on every character field, with the context needed to fix it")
        void aNullValueIsRejectedOnEveryCharacterField(final String property,
                                                      final String cobolField,
                                                      final int width) {
            final String name = "embossedName".equals(property) ? null : "NAME";
            final String expiry = "expiraionDate".equals(property) ? null : "2025-01-01";
            final String status = "activeStatus".equals(property) ? null : "Y";

            assertThatIllegalArgumentException()
                    .as("%s maps to a NOT NULL column, so null must be refused at construction rather than "
                            + "at flush, where the stack trace no longer identifies the caller", property)
                    .isThrownBy(() -> new Card(SYNTHETIC_KEY, 1L, name, expiry, status))
                    .withMessageContaining(property)
                    .withMessageContaining(cobolField)
                    .withMessageContaining(String.valueOf(width))
                    .withNoCause();
        }

        @Test
        @DisplayName("the rejection carries no cause, because it is a primary failure and wraps nothing")
        void theRejectionCarriesNoCause() {
            assertThatIllegalArgumentException()
                    .as("Rule 1 clause B requires that a wrapped failure preserve its root cause. This "
                            + "rejection wraps nothing: it is the originating validation failure, so there "
                            + "is no cause to lose and asserting its absence is what proves nothing was "
                            + "swallowed on the way here. The message carries the whole context instead")
                    .isThrownBy(() -> new Card(null, 1L, "NAME", "2025-01-01", "Y"))
                    .withNoCause()
                    .withMessageContaining("must not be null");
        }

        @Test
        @DisplayName("a 17-character card number is rejected and the message reports the actual length")
        void aSeventeenCharacterCardNumberIsRejected() {
            assertThatIllegalArgumentException()
                    .as("a seventeenth character could not be written to a CHAR(16) column and would be "
                            + "truncated at the byte boundary, corrupting the primary key silently")
                    .isThrownBy(() -> new Card("X".repeat(CARD_NUMBER_WIDTH + 1), 1L, "NAME",
                            "2025-01-01", "Y"))
                    .withMessageContaining("16")
                    .withMessageContaining("17")
                    .withNoCause();
        }

        @Test
        @DisplayName("a 15-character card number is accepted, because the guard is a maximum not an equality")
        void aFifteenCharacterCardNumberIsAccepted() {
            final String fifteen = "X".repeat(CARD_NUMBER_WIDTH - 1);

            assertThat(new Card(fifteen, 1L, "NAME", "2025-01-01", "Y").getCardNumber())
                    .as("observed behaviour, pinned deliberately: the guard checks an upper bound, so a "
                            + "short key passes and is stored exactly as supplied. Padding to the CHAR(16) "
                            + "width belongs at the fixed-width emission boundary, not in the entity, and a "
                            + "guard that demanded exactly 16 would reject rows the seed migration loads")
                    .isEqualTo(fifteen)
                    .hasSize(CARD_NUMBER_WIDTH - 1);
        }

        @ParameterizedTest
        @CsvSource({
            "51,  CARD-EMBOSSED-NAME",
            "11,  CARD-EXPIRAION-DATE",
            "2,   CARD-ACTIVE-STATUS",
            "151, CARD-EMBOSSED-NAME",
        })
        @DisplayName("an over-width value is rejected on every character field, naming its COBOL field")
        void anOverWidthValueIsRejectedOnEveryField(final int length, final String cobolField) {
            final String tooLong = "X".repeat(length);
            final String name = "CARD-EMBOSSED-NAME".equals(cobolField) ? tooLong : "NAME";
            final String expiry = "CARD-EXPIRAION-DATE".equals(cobolField) ? tooLong : "2025-01-01";
            final String status = "CARD-ACTIVE-STATUS".equals(cobolField) ? tooLong : "Y";

            assertThatIllegalArgumentException()
                    .as("%s must reject a value of length %d: the column is CHAR of a narrower width and "
                            + "the excess would be truncated at the byte boundary. The 151-character case "
                            + "is a whole over-long record offered as one field", cobolField, length)
                    .isThrownBy(() -> new Card(SYNTHETIC_KEY, 1L, name, expiry, status))
                    .withMessageContaining(cobolField)
                    .withNoCause();
        }

        @Test
        @DisplayName("a value at exactly the declared width is accepted: the boundary is inclusive")
        void aValueAtExactlyTheDeclaredWidthIsAccepted() {
            final Card card = new Card(
                    "X".repeat(CARD_NUMBER_WIDTH),
                    MAX_ACCOUNT_ID,
                    "N".repeat(EMBOSSED_NAME_WIDTH),
                    "2".repeat(EXPIRY_WIDTH),
                    "Y".repeat(STATUS_WIDTH));

            assertThat(card.getCardNumber()).hasSize(CARD_NUMBER_WIDTH);
            assertThat(card.getEmbossedName())
                    .as("exactly 50 characters fits CHAR(50) precisely, so the bound must not be exclusive")
                    .hasSize(EMBOSSED_NAME_WIDTH);
            assertThat(card.getExpiraionDate()).hasSize(EXPIRY_WIDTH);
            assertThat(card.getActiveStatus()).hasSize(STATUS_WIDTH);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", " ", "   ", "SHORT", "FNAMEAA6 LNAME6", "ABCDEFGHIJKLMNOPQR"})
        @DisplayName("a blank or shorter value is accepted verbatim, with no padding and no trimming")
        void aBlankOrShorterValueIsAcceptedVerbatim(final String name) {
            assertThat(new Card(SYNTHETIC_KEY, 1L, name, "2025-01-01", "Y").getEmbossedName())
                    .as("a fixed-width COBOL record has no concept of an absent field, only of a blank "
                            + "one, so blank is legitimate and the entity declares no @NotBlank that would "
                            + "reject rows the legacy system accepted. The value is stored exactly as "
                            + "supplied: not padded, not trimmed, and - for the A-to-R case - not decoded")
                    .isEqualTo(name);
        }

        @Test
        @DisplayName("an empty card number is accepted by the width guard, since zero is under the maximum")
        void anEmptyCardNumberIsAcceptedByTheWidthGuard() {
            assertThat(new Card("", 1L, "NAME", "2025-01-01", "Y").getCardNumber())
                    .as("observed behaviour, recorded so a reader does not assume the entity is a complete "
                            + "validator: the guard refuses null and over-width but not emptiness. An empty "
                            + "primary key is refused by the database at flush, which is a deliberate "
                            + "division of labour rather than a gap")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("account id range guard: null, negative, zero, the maximum and beyond")
    class AccountIdRangeGuard {

        @Test
        @DisplayName("a null account id is rejected, naming the NUMERIC(11) column it maps to")
        void aNullAccountIdIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new Card(SYNTHETIC_KEY, null, "NAME", "2025-01-01", "Y"))
                    .withMessageContaining("accountId")
                    .withMessageContaining("CARD-ACCT-ID")
                    .withNoCause();
        }

        @Test
        @DisplayName("a negative account id is rejected, because PIC 9(11) is unsigned")
        void aNegativeAccountIdIsRejected() {
            assertThatIllegalArgumentException()
                    .as("CARD-ACCT-ID is PIC 9(11) with no S, so it carries no sign and a negative value "
                            + "has no representation in the fixed-width record. Every one of the 50 fixture "
                            + "rows confirms it: the slice at columns 17-27 is plain digits with no "
                            + "trailing overpunch symbol")
                    .isThrownBy(() -> new Card(SYNTHETIC_KEY, -1L, "NAME", "2025-01-01", "Y"))
                    .withMessageContaining("accountId")
                    .withMessageContaining("0")
                    .withNoCause();
        }

        @Test
        @DisplayName("an account id above 99,999,999,999 is rejected as too wide for eleven digits")
        void anAccountIdAboveTheMaximumIsRejected() {
            assertThatIllegalArgumentException()
                    .as("a twelfth digit could not be written into an eleven-character field")
                    .isThrownBy(() -> new Card(SYNTHETIC_KEY, MAX_ACCOUNT_ID + 1L, "NAME",
                            "2025-01-01", "Y"))
                    .withMessageContaining("99999999999")
                    .withNoCause();
        }

        @Test
        @DisplayName("zero is accepted, because the lower bound is inclusive")
        void zeroIsAccepted() {
            assertThat(new Card(SYNTHETIC_KEY, 0L, "NAME", "2025-01-01", "Y").getAccountId())
                    .as("an all-zeros account id is a representable PIC 9(11) value even though the seed "
                            + "data does not use it, so the guard must test below zero and not below one")
                    .isZero();
        }

        @Test
        @DisplayName("exactly 99,999,999,999 is accepted, because the upper bound is inclusive")
        void theMaximumIsAccepted() {
            assertThat(new Card(SYNTHETIC_KEY, MAX_ACCOUNT_ID, "NAME", "2025-01-01", "Y").getAccountId())
                    .as("an exclusive upper bound would reject the widest legitimate value the picture "
                            + "clause can hold")
                    .isEqualTo(MAX_ACCOUNT_ID);
        }

        @Test
        @DisplayName("Long.MIN_VALUE and Long.MAX_VALUE are both rejected")
        void theExtremeLongValuesAreRejected() {
            assertThatIllegalArgumentException()
                    .as("the lower extreme is caught by the same below-zero test as any negative value")
                    .isThrownBy(() -> new Card(SYNTHETIC_KEY, Long.MIN_VALUE, "NAME", "2025-01-01", "Y"))
                    .withNoCause();
            assertThatIllegalArgumentException()
                    .as("a guard that tested only the lower bound would let Long.MAX_VALUE through and "
                            + "produce a nineteen-digit value in an eleven-character field")
                    .isThrownBy(() -> new Card(SYNTHETIC_KEY, Long.MAX_VALUE, "NAME", "2025-01-01", "Y"))
                    .withNoCause();
        }
    }

    @Nested
    @DisplayName("fixture round trip: all 50 rows of carddata.txt pass through the entity unchanged")
    class FixtureRoundTrip {

        @Test
        @DisplayName("the fixture is loaded by classpath resource name and satisfies its geometry invariant")
        void theFixtureIsLoadedByResourceNameAndSatisfiesItsGeometry() {
            final FixtureLoader.FixtureData fixture = cardFixture();

            assertThat(fixture.resourceName())
                    .as("the fixture is reached through the Fixture.CARD constant rather than a typed-out "
                            + "name, and it is read from the test classpath - never from app/, which is "
                            + "frozen and must not be opened by path from a test")
                    .isEqualTo("carddata.txt");
            assertThat(fixture.recordCount()).isEqualTo(FIXTURE_ROW_COUNT);
            assertThat(fixture.recordWidth()).isEqualTo(RECORD_LENGTH);
            assertThat(fixture.byteCount())
                    .as("7550 bytes is 50 rows of 150 data bytes plus one line feed each. The invariant "
                            + "byteCount == recordCount * (recordWidth + 1) simultaneously detects CRLF "
                            + "conversion, trailing-whitespace trimming, a missing final newline and row "
                            + "loss - four failures otherwise invisible until a parity comparison "
                            + "disagrees for no apparent reason")
                    .isEqualTo(fixture.impliedByteCount())
                    .isEqualTo(7550);
        }

        @Test
        @DisplayName("every one of the 50 rows constructs a valid card and round-trips byte for byte")
        void everyRowRoundTripsThroughTheEntity() {
            final FixtureLoader.FixtureData fixture = cardFixture();

            for (int row = 0; row < fixture.recordCount(); row++) {
                final String key = fixture.field(row, 1, CARD_NUMBER_WIDTH);
                final String accountId = fixture.field(row, 17, ACCOUNT_ID_WIDTH);
                final String name = fixture.field(row, 31, EMBOSSED_NAME_WIDTH);
                final String expiry = fixture.field(row, 81, EXPIRY_WIDTH);
                final String status = fixture.field(row, 91, STATUS_WIDTH);

                final Card card = new Card(key, Long.parseLong(accountId), name, expiry, status);

                // The key is compared as a boolean rather than through isEqualTo, so that no card number
                // can reach an assertion message even on failure. Rule 1 clause D names tests explicitly.
                assertThat(key.equals(card.getCardNumber()))
                        .as("row %d: the 16-character key at columns 1-16 must round-trip unchanged", row)
                        .isTrue();
                assertThat(card.getAccountId())
                        .as("row %d: the zero-padded account id at columns 17-27 parses to its numeric "
                                + "value, and the padding is an emission concern rather than a stored one",
                                row)
                        .isEqualTo(Long.parseLong(accountId));
                assertThat(card.getEmbossedName())
                        .as("row %d: the 50-byte name is stored with its trailing spaces intact - no trim, "
                                + "because the padding is real geometry", row)
                        .isEqualTo(name)
                        .hasSize(EMBOSSED_NAME_WIDTH);
                assertThat(card.getExpiraionDate())
                        .as("row %d: the expiry stays 10 characters of text, never parsed to a date", row)
                        .isEqualTo(expiry)
                        .hasSize(EXPIRY_WIDTH);
                assertThat(card.getActiveStatus())
                        .as("row %d: the status is the single character at column 91", row)
                        .isEqualTo(status)
                        .hasSize(STATUS_WIDTH);
            }
        }

        @Test
        @DisplayName("the 59-byte trailing filler is blank in every row and is carried by no property")
        void theTrailingFillerIsBlankInEveryRowAndUnmapped() {
            final FixtureLoader.FixtureData fixture = cardFixture();

            for (int row = 0; row < fixture.recordCount(); row++) {
                final String filler = fixture.field(row, 92, FILLER_WIDTH);

                assertThat(filler)
                        .as("row %d: FILLER occupies columns 92-150, which is 59 bytes", row)
                        .hasSize(FILLER_WIDTH);
                assertThat(filler.isBlank())
                        .as("row %d: the filler is blank in every row, which is why modelling it would add "
                                + "a column able to hold nothing but 59 spaces", row)
                        .isTrue();
            }

            assertThat(validCard().toString())
                    .as("and no rendering of the entity carries the filler either")
                    .doesNotContain(" ".repeat(FILLER_WIDTH));
        }

        @Test
        @DisplayName("the whole record is 150 characters with its padding intact, never trimmed")
        void theWholeRecordKeepsItsPadding() {
            final FixtureLoader.FixtureData fixture = cardFixture();
            final String record = fixture.recordAt(0);

            assertThat(record)
                    .as("a single strip anywhere on the read path would shorten the record, break the "
                            + "geometry invariant and move every field offset past the cut. This fixture "
                            + "ends in a 59-space run, so the damage would be silent")
                    .hasSize(RECORD_LENGTH);
            assertThat(record.length() - record.stripTrailing().length())
                    .as("the trailing-space run is exactly the 59-byte filler, so the last populated byte "
                            + "is the status at column 91")
                    .isEqualTo(FILLER_WIDTH);
        }
    }

    @Nested
    @DisplayName("construction, accessors and key-based identity")
    class ConstructionAndAccessors {

        @Test
        @DisplayName("the validating constructor populates all five supplied fields and leaves version unset")
        void theConstructorPopulatesEveryField() {
            final Card card = validCard();

            assertThat(card.getCardNumber()).isEqualTo(SYNTHETIC_KEY);
            assertThat(card.getAccountId()).isEqualTo(1L);
            assertThat(card.getEmbossedName()).isEqualTo("FNAMEAA6 LNAME6");
            assertThat(card.getExpiraionDate()).isEqualTo("2025-01-01");
            assertThat(card.getActiveStatus()).isEqualTo("Y");
            assertThat(card.getVersion())
                    .as("the @Version column is assigned by the provider on first flush and is never "
                            + "seeded by the constructor, so a transient instance carries null")
                    .isNull();
        }

        @Test
        @DisplayName("every one of the six accessor pairs round-trips a value")
        void everyAccessorPairRoundTrips() {
            final Card card = validCard();

            card.setCardNumber(SYNTHETIC_KEY_ALT);
            card.setAccountId(2L);
            card.setEmbossedName("FNAM7 LNAM7");
            card.setExpiraionDate("2026-12-31");
            card.setActiveStatus("N");
            card.setVersion(3L);

            assertThat(card.getCardNumber()).isEqualTo(SYNTHETIC_KEY_ALT);
            assertThat(card.getAccountId()).isEqualTo(2L);
            assertThat(card.getEmbossedName()).isEqualTo("FNAM7 LNAM7");
            assertThat(card.getExpiraionDate()).isEqualTo("2026-12-31");
            assertThat(card.getActiveStatus()).isEqualTo("N");
            assertThat(card.getVersion()).isEqualTo(3L);
        }

        @Test
        @DisplayName("the setters re-apply the guards, so an invariant cannot be escaped after construction")
        void theSettersAlsoApplyTheGuards() {
            final Card card = validCard();

            assertThatIllegalArgumentException()
                    .as("observed behaviour, verified rather than assumed: setCardNumber routes through the "
                            + "same width guard as the constructor, so the invariant holds for the whole "
                            + "lifetime of the instance and not merely at birth")
                    .isThrownBy(() -> card.setCardNumber("X".repeat(99)))
                    .withMessageContaining("CARD-NUM")
                    .withMessageContaining("16");
            assertThatIllegalArgumentException()
                    .as("and setAccountId re-applies the range check, so 0..99,999,999,999 cannot be "
                            + "escaped by mutation either")
                    .isThrownBy(() -> card.setAccountId(-5L))
                    .withMessageContaining("accountId");
            assertThatIllegalArgumentException()
                    .as("nor can null be introduced after construction into a NOT NULL column")
                    .isThrownBy(() -> card.setEmbossedName(null))
                    .withMessageContaining("embossedName");
        }

        @Test
        @DisplayName("a rejected setter leaves the previous value intact, so the instance stays valid")
        void aRejectedSetterLeavesThePreviousValueIntact() {
            final Card card = validCard();

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> card.setEmbossedName("X".repeat(EMBOSSED_NAME_WIDTH + 1)));

            assertThat(card.getEmbossedName())
                    .as("the guard runs before the assignment, so a rejected mutation is a no-op rather "
                            + "than a half-applied change and the instance is never left in a state the "
                            + "database would refuse")
                    .isEqualTo("FNAMEAA6 LNAME6");
            assertThat(card.getCardNumber())
                    .as("and no unrelated field is disturbed by the rejection")
                    .isEqualTo(SYNTHETIC_KEY);
        }

        @Test
        @DisplayName("identity is the card number alone, so every other field may differ")
        void identityIsTheCardNumberAlone() {
            final Card first = new Card(SYNTHETIC_KEY, 1L, "NAME ONE", "2025-01-01", "Y");
            final Card second = new Card(SYNTHETIC_KEY, 999L, "NAME TWO", "2031-12-31", "N");

            assertThat(second)
                    .as("CARDDATA's key is CARD-NUM, so two instances of the same card are the same "
                            + "entity however much their attributes differ - which is the behaviour a "
                            + "persistence context relies on when it reconciles a detached instance")
                    .isEqualTo(first)
                    .hasSameHashCodeAs(first);
            assertThat(first)
                    .as("equality is reflexive")
                    .isEqualTo(first);
            assertThat(new Card(SYNTHETIC_KEY_ALT, 1L, "NAME ONE", "2025-01-01", "Y"))
                    .as("and a different key is a different entity even when every attribute matches")
                    .isNotEqualTo(first);
        }

        @Test
        @DisplayName("equals refuses null and a foreign type without throwing")
        void equalsRefusesNullAndAForeignType() {
            final Card card = validCard();

            assertThat(card.equals(null))
                    .as("the pattern-matching instanceof test rejects null before dereferencing it, so "
                            + "equals is null-safe rather than throwing NullPointerException")
                    .isFalse();
            assertThat(card.equals("CARDNUM-TEST-001"))
                    .as("a foreign type is refused too, even one carrying the same characters as the key: "
                            + "the type test precedes the value comparison")
                    .isFalse();
        }

        @Test
        @DisplayName("the active status carries the legacy Y and N domain in a single character")
        void theActiveStatusCarriesTheLegacyDomain() {
            final Card active = new Card(SYNTHETIC_KEY, 1L, "NAME", "2025-01-01", "Y");
            final Card inactive = new Card(SYNTHETIC_KEY_ALT, 1L, "NAME", "2025-01-01", "N");

            assertThat(active.getActiveStatus())
                    .as("the domain is Y and N, from the 88-level FLG-YES-NO-VALID in "
                            + "app/cbl/COCRDUPC.cbl, and byte 91 of all 50 fixture rows is Y")
                    .isEqualTo("Y")
                    .hasSize(STATUS_WIDTH);
            assertThat(inactive.getActiveStatus()).isEqualTo("N").hasSize(STATUS_WIDTH);
            assertThat(active.getActiveStatus())
                    .as("the status is the raw character the copybook declares, not an enum: mapping it to "
                            + "a type would reject any third value the legacy data might hold, and the "
                            + "check constraint in the migration is where that domain is enforced")
                    .isNotEqualTo(inactive.getActiveStatus());
        }
    }
}

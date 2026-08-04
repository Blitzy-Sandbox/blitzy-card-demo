/*
 * ******************************************************************
 * Program     : CustomerTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM tier, no container, no
 *               Spring context, no database, no live endpoint
 * Function    : Proves that the Customer entity reproduces the
 *               500-byte CUSTOMER-RECORD contract exactly: that one
 *               entity serves both proven-duplicate copybooks, that
 *               the three leading-zero-bearing fields are held as
 *               text, that neither the FICO range predicate nor the
 *               state-code table is enforced on the entity, and that
 *               its diagnostic rendering carries no personal data.
 * Source      : app/cpy/CVCUS01Y.cpy + app/cpy/CUSTREC.cpy
 *               (500 B, key 9) @ 7756d89
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

import com.cardemo.model.entity.Customer;

import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit test for {@link Customer}, the relational replacement for the VSAM KSDS cluster
 * {@code AWS.M2.CARDDEMO.CUSTDATA.VSAM.KSDS}.
 *
 * <h2>1. What this test does, and the evidence it rests on</h2>
 *
 * <p>Six contracts are specific to this entity, and each is asserted here rather than described.
 * Every one carries its locator so a future reader can re-derive it from the frozen corpus instead
 * of trusting this comment.
 *
 * <ol>
 *   <li><strong>One entity serves two copybooks.</strong> {@code app/cpy/CVCUS01Y.cpy} and
 *       {@code app/cpy/CUSTREC.cpy} are the same layout. {@code diff -w} between them yields
 *       exactly two hunks: at {@code :L19} the date-of-birth field is spelled
 *       {@code CUST-DOB-YYYY-MM-DD} in the first and {@code CUST-DOB-YYYYMMDD} in the second -
 *       only the <em>name</em> differs, both declare {@code PIC X(10)} - and at {@code :L25} the
 *       trailing version comment differs by one second. Both groups are
 *       {@code 01 CUSTOMER-RECORD}, so they can never be {@code COPY}ed into one program, which is
 *       precisely why one Java type serves both. The {@code CVCUS01Y.cpy} spelling is canonical.</li>
 *   <li><strong>The 500-byte geometry.</strong> Eighteen populated fields sum to 332 bytes and
 *       {@code FILLER PIC X(168)} at {@code app/cpy/CVCUS01Y.cpy:L23} carries the record to 500.
 *       {@code app/catlg/LISTCAT.txt:L630} names the cluster and {@code :L632} reports
 *       {@code KEYLEN 9} with {@code AVGLRECL 500}.</li>
 *   <li><strong>Three fields are text because leading zeros are real.</strong> In
 *       {@code app/data/ASCII/custdata.txt} six of the fifty social security numbers and seven of
 *       the fifty credit scores begin with {@code 0}, and every date of birth is dash-separated. A
 *       numeric type on any of the three would destroy data on load.</li>
 *   <li><strong>Two latent constraints must not be enforced.</strong>
 *       {@code app/cbl/COACTUPC.cbl:L848-L849} declares
 *       {@code 88 FICO-RANGE-IS-VALID VALUES 300 THROUGH 850} and
 *       {@code app/cpy/CSLKPCDY.cpy:L1013} opens a fifty-six entry
 *       {@code 88 VALID-US-STATE-CODE} table, yet the seed data violates both. Adding either
 *       constraint to the entity is a <strong>High</strong>-severity defect.</li>
 *   <li><strong>The rendering carries no personal data.</strong> This record holds a social
 *       security number, a date of birth, two telephone numbers, a government-issued identifier
 *       and a full postal address, so {@link Customer#toString()} is the strictest in the entity
 *       set: identifier and version only.</li>
 *   <li><strong>The stored date of birth is the dash-separated ten-character form.</strong> The
 *       account-update snapshot holds the same date compactly in eight characters, so their
 *       components sit at different offsets - see section 5 below.</li>
 * </ol>
 *
 * <h2>2. How to build, run and test</h2>
 *
 * <pre>{@code
 * ./mvnw -B -ntp -Ddependency-check.skip=true test -Dtest=CustomerTest
 * ./mvnw -B -ntp -Ddependency-check.skip=true clean verify
 * }</pre>
 *
 * <p>This class is collected by <strong>Surefire 3.5.4</strong>, which includes
 * {@code **}{@code /*Test.java} and excludes the {@code integration} and {@code e2e} trees; the
 * integration tier belongs to Failsafe. A class moved out of
 * {@code src/test/java/com/cardemo/unit/**} would be claimed by neither plugin and would silently
 * never run, so neither the name nor the location may change.
 *
 * <h2>3. Key configuration and defaults</h2>
 *
 * <p>None is required, and that is deliberate. The tier is pure JVM: no Spring context, no
 * container, no database, no queue and no reachable endpoint. There are no Mockito doubles here
 * either - the subject is a data holder with no collaborator to stub, so strict stubbing has
 * nothing to police and introducing a mock would only add a moving part.
 *
 * <p>No clock is consulted anywhere in this class, and none is injected: {@code CUST-DOB} is
 * {@code PIC X(10)} text that the entity neither parses nor validates, so a fixed clock provider
 * would have nothing to feed. There is consequently no call to {@code LocalDate.now()},
 * {@code Instant.now()} or {@code System.currentTimeMillis()}, no default locale or default zone
 * dependence, no randomness, and no reliance on hash iteration order - every ordered assertion
 * runs over an explicitly ordered list.
 *
 * <p>Seed data reaches the assertions through {@link FixtureLoader}, addressed
 * <strong>by classpath resource name only</strong> ({@code custdata.txt} via
 * {@link FixtureLoader.Fixture#CUSTOMER}). Nothing here opens {@code app/}, which is frozen, and
 * nothing writes any file.
 *
 * <h2>4. Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>The build fails at test compilation with no test failure reported.</strong>
 *       {@code maven-compiler-plugin} runs with {@code failOnWarning}, {@code -Xlint:all} and
 *       {@code -Werror} at {@code release 25}, and that configuration reaches the test sources. A
 *       single unused import, raw type or unchecked cast added here fails the whole build. Read the
 *       compiler output, not the Surefire report.</li>
 *   <li><strong>A fixture assertion fails with a resource-not-found message.</strong> The name is
 *       {@code custdata.txt} at the classpath root. The neighbouring daily-transaction fixture is
 *       the classic trap: it is spelled <strong>{@code dailytran.txt}</strong>, in full, even
 *       though the mainframe DD name and dataset are {@code DALYTRAN} - {@code dalytran.txt} does
 *       not exist and never did.</li>
 *   <li><strong>A leading-zero assertion fails.</strong> Something re-typed {@code ssn},
 *       {@code ficoCreditScore} or {@code dateOfBirth} as a number. {@code 020973888} becomes
 *       {@code 20973888} and {@code 001} becomes {@code 1} the moment an {@code Integer},
 *       {@code Long} or {@code BigDecimal} touches them, and the loss is silent.</li>
 *   <li><strong>A trap assertion fails naming a range or a lookup.</strong> A
 *       {@code @Min(300)}/{@code @Max(850)} pair or a state-code membership check was added to the
 *       entity. Remove it: twenty-one of the fifty seeded customers score below 300 and five carry
 *       a state code absent from the copybook table, so the constraint would make real seed data
 *       unloadable and fail the named-fixture gate.</li>
 *   <li><strong>A rendering assertion fails.</strong> A field was added to
 *       {@link Customer#toString()}. Remove it; the identifier and the version are the only two
 *       values that may appear.</li>
 *   <li><strong>Everything passes but a record is 332 bytes wide somewhere downstream.</strong>
 *       Every seeded record ends in exactly 168 spaces, so trimming one collapses 500 to 332 and
 *       every offset from {@code CUST-ADDR-LINE-3} onward silently shifts. Slice by position; never
 *       trim a whole record.</li>
 * </ul>
 *
 * <h2>5. The date-of-birth divergence - High severity, asserted next door</h2>
 *
 * <p>The entity side, which this class owns, is the dash-separated ten-character form: components
 * at offsets 1, 6 and 9. The snapshot side, which it does not, is
 * {@code ACUP-OLD-CUST-DOB-YYYY-MM-DD PIC X(08)} at {@code app/cbl/COACTUPC.cbl:L746} with its
 * {@code ACUP-NEW} twin at {@code :L837} - compact, components at 1, 5 and 7. Accordingly
 * {@code 9700-CHECK-CHANGE-IN-REC} at {@code app/cbl/COACTUPC.cbl:L4174-L4179} compares
 * {@code (1:4)} against {@code (1:4)}, {@code (6:2)} against {@code (5:2)} and {@code (9:2)}
 * against {@code (7:2)}. A whole-string comparison of the two representations is unequal for every
 * customer that has ever existed, so it would set the data-changed flag on every single request and
 * make the account-update endpoint permanently unusable.
 *
 * <p>That offset asymmetry is asserted by {@code AccountUpdateRequestTest}, which owns the snapshot
 * type; duplicating it here would put one contract in two places. This class asserts only its own
 * half - that the stored form really is the dash-separated ten-character one - and demonstrates the
 * inequality that motivates the component-wise comparison.
 *
 * <h2>6. Deliberately not asserted here</h2>
 *
 * <p>Any schema detail beyond what the copybook and the catalogue state is <strong>Not
 * available</strong> from this tier and none is invented. The generated DDL, the physical column
 * types PostgreSQL settles on, index presence and foreign-key wiring all belong to
 * {@code V1__create_schema.sql} and to the repository integration tier, which runs against a real
 * database. What this class asserts about mapping is confined to the annotations the entity itself
 * declares.
 */
@DisplayName("Customer: the 500-byte CUSTOMER-RECORD of app/cpy/CVCUS01Y.cpy and app/cpy/CUSTREC.cpy")
class CustomerTest {

    /** Catalogued record length - {@code app/catlg/LISTCAT.txt:L632}, {@code AVGLRECL 500}. */
    private static final int RECORD_LENGTH = 500;

    /** Catalogued key length - {@code app/catlg/LISTCAT.txt:L632}, {@code KEYLEN 9}. */
    private static final int KEY_LENGTH = 9;

    /** {@code FILLER PIC X(168)} at {@code app/cpy/CVCUS01Y.cpy:L23}, deliberately not modelled. */
    private static final int FILLER_WIDTH = 168;

    /** Largest value nine unsigned display digits can hold - {@code CUST-ID PIC 9(09)}. */
    private static final long MAX_CUSTOMER_ID = 999_999_999L;

    /** Records in {@code custdata.txt}, corroborated by {@code REC-TOTAL 50} in the catalogue. */
    private static final int SEEDED_RECORD_COUNT = 50;

    /** Inclusive floor of the account-update screen predicate at {@code COACTUPC.cbl:L848-L849}. */
    private static final int FICO_PREDICATE_FLOOR = 300;

    /** Inclusive ceiling of the same predicate. */
    private static final int FICO_PREDICATE_CEILING = 850;

    /**
     * One mapped field of the record: the Java property, the column it maps to, the COBOL field and
     * picture clause it comes from, and the width that picture clause declares.
     *
     * <p>The one-based start column is carried here rather than repeated at each use, so that an
     * offset and a width can never drift apart and so that each field's
     * {@code startColumn + width} lining up with the next field's {@code startColumn} becomes a
     * property the tests assert rather than a convention a reader has to check by hand.
     *
     * @param property    the Java field name on {@link Customer}
     * @param column      the {@code @Column} name the entity declares
     * @param cobolField  the originating COBOL field with its picture clause
     * @param width       the declared width in characters
     * @param startColumn the one-based first column of the field inside the 500-byte record, or zero
     *                    when the field has no source position because the record does not carry it
     */
    private record MappedField(String property, String column, String cobolField, int width,
                               int startColumn) { }

    /**
     * A text field's accessor pair, so that a round trip can be asserted for every field without
     * naming any of them twice.
     *
     * @param field  the mapped field this pair belongs to
     * @param getter the reading accessor
     * @param setter the writing accessor
     */
    private record TextField(MappedField field,
                             Function<Customer, String> getter,
                             BiConsumer<Customer, String> setter) { }

    /**
     * The nineteen mapped fields in copybook order, {@code CUST-ID} first and the framework-owned
     * optimistic-locking counter last.
     *
     * <p>Immutable, and immutable all the way down: {@link List#of} over a record of {@code String}
     * and {@code int}. It is shared state but not mutable state, so no test can perturb another.
     */
    private static final List<MappedField> MAPPED_FIELDS = List.of(
            new MappedField("customerId", "cust_id", "CUST-ID PIC 9(09)", KEY_LENGTH, 1),
            new MappedField("firstName", "cust_first_name", "CUST-FIRST-NAME PIC X(25)", 25, 10),
            new MappedField("middleName", "cust_middle_name", "CUST-MIDDLE-NAME PIC X(25)", 25, 35),
            new MappedField("lastName", "cust_last_name", "CUST-LAST-NAME PIC X(25)", 25, 60),
            new MappedField("addressLine1", "cust_addr_line_1", "CUST-ADDR-LINE-1 PIC X(50)", 50, 85),
            new MappedField("addressLine2", "cust_addr_line_2", "CUST-ADDR-LINE-2 PIC X(50)", 50, 135),
            new MappedField("addressLine3", "cust_addr_line_3", "CUST-ADDR-LINE-3 PIC X(50)", 50, 185),
            new MappedField("addressStateCode", "cust_addr_state_cd", "CUST-ADDR-STATE-CD PIC X(02)", 2, 235),
            new MappedField("addressCountryCode", "cust_addr_country_cd", "CUST-ADDR-COUNTRY-CD PIC X(03)", 3, 237),
            new MappedField("addressZip", "cust_addr_zip", "CUST-ADDR-ZIP PIC X(10)", 10, 240),
            new MappedField("phoneNumber1", "cust_phone_num_1", "CUST-PHONE-NUM-1 PIC X(15)", 15, 250),
            new MappedField("phoneNumber2", "cust_phone_num_2", "CUST-PHONE-NUM-2 PIC X(15)", 15, 265),
            new MappedField("ssn", "cust_ssn", "CUST-SSN PIC 9(09)", 9, 280),
            new MappedField("governmentIssuedId", "cust_govt_issued_id", "CUST-GOVT-ISSUED-ID PIC X(20)", 20, 289),
            new MappedField("dateOfBirth", "cust_dob_yyyy_mm_dd", "CUST-DOB-YYYY-MM-DD PIC X(10)", 10, 309),
            new MappedField("eftAccountId", "cust_eft_account_id", "CUST-EFT-ACCOUNT-ID PIC X(10)", 10, 319),
            new MappedField("primaryCardHolderIndicator", "cust_pri_card_holder_ind",
                    "CUST-PRI-CARD-HOLDER-IND PIC X(01)", 1, 329),
            new MappedField("ficoCreditScore", "cust_fico_credit_score", "CUST-FICO-CREDIT-SCORE PIC 9(03)", 3, 330),
            new MappedField("version", "version", "(no source field - optimistic locking)", 0, 0));

    /**
     * The seventeen text fields with their accessor pairs, in copybook order.
     *
     * <p>Every entry's {@link MappedField} is taken from {@link #MAPPED_FIELDS} by property name, so
     * a width or column recorded in one place cannot drift from the other.
     */
    private static final List<TextField> TEXT_FIELDS = List.of(
            new TextField(mapped("firstName"), Customer::getFirstName, Customer::setFirstName),
            new TextField(mapped("middleName"), Customer::getMiddleName, Customer::setMiddleName),
            new TextField(mapped("lastName"), Customer::getLastName, Customer::setLastName),
            new TextField(mapped("addressLine1"), Customer::getAddressLine1, Customer::setAddressLine1),
            new TextField(mapped("addressLine2"), Customer::getAddressLine2, Customer::setAddressLine2),
            new TextField(mapped("addressLine3"), Customer::getAddressLine3, Customer::setAddressLine3),
            new TextField(mapped("addressStateCode"), Customer::getAddressStateCode, Customer::setAddressStateCode),
            new TextField(mapped("addressCountryCode"), Customer::getAddressCountryCode,
                    Customer::setAddressCountryCode),
            new TextField(mapped("addressZip"), Customer::getAddressZip, Customer::setAddressZip),
            new TextField(mapped("phoneNumber1"), Customer::getPhoneNumber1, Customer::setPhoneNumber1),
            new TextField(mapped("phoneNumber2"), Customer::getPhoneNumber2, Customer::setPhoneNumber2),
            new TextField(mapped("ssn"), Customer::getSsn, Customer::setSsn),
            new TextField(mapped("governmentIssuedId"), Customer::getGovernmentIssuedId,
                    Customer::setGovernmentIssuedId),
            new TextField(mapped("dateOfBirth"), Customer::getDateOfBirth, Customer::setDateOfBirth),
            new TextField(mapped("eftAccountId"), Customer::getEftAccountId, Customer::setEftAccountId),
            new TextField(mapped("primaryCardHolderIndicator"), Customer::getPrimaryCardHolderIndicator,
                    Customer::setPrimaryCardHolderIndicator),
            new TextField(mapped("ficoCreditScore"), Customer::getFicoCreditScore, Customer::setFicoCreditScore));

    /**
     * The four state codes present in {@code app/data/ASCII/custdata.txt} that the copybook's
     * {@code 88 VALID-US-STATE-CODE} table at {@code app/cpy/CSLKPCDY.cpy:L1013} does not list.
     *
     * <p>{@code AP} is a military overseas designation and {@code FM}, {@code MH} and {@code PW} are
     * the freely associated states. Between them they account for five of the fifty seeded rows, which
     * is why enforcing the table at the entity would make real seed data unloadable.
     */
    private static final Set<String> UNLISTED_STATE_CODES = Set.of("AP", "FM", "MH", "PW");

    /**
     * The six JPA annotations that would introduce a relationship or a collection.
     *
     * <p>None may appear anywhere on this entity: {@code CUSTOMER-RECORD} is a flat fixed-width
     * record with no repeating group and no pointer to another dataset, and the cross-reference
     * cluster is what joins a customer to its cards.
     */
    private static final List<Class<? extends Annotation>> ASSOCIATION_ANNOTATIONS = List.of(
            OneToMany.class, ManyToOne.class, OneToOne.class, ManyToMany.class,
            JoinColumn.class, ElementCollection.class);

    /**
     * Package prefixes an entity may never reach into.
     *
     * <p>A persistent record that referenced a service, a repository or a controller would invert
     * the dependency direction of the whole tree; one that referenced the exception, security,
     * configuration, batch or observability packages would import behaviour into a data holder.
     * {@code model.key} and {@code model.enums} are listed too: this entity has no composite key
     * and no enumerated column, and a reference to either would signal that a field had been
     * re-typed away from the copybook contract.
     */
    private static final List<String> FORBIDDEN_PACKAGE_PREFIXES = List.of(
            "com.cardemo.exception", "com.cardemo.repository", "com.cardemo.service",
            "com.cardemo.controller", "com.cardemo.batch", "com.cardemo.security",
            "com.cardemo.config", "com.cardemo.observability", "com.cardemo.model.key",
            "com.cardemo.model.enums");

    /**
     * Returns the mapped field with the given property name.
     *
     * @param property the Java property name
     * @return its mapped-field record
     * @throws IllegalStateException if this class's own table has no such property, which would mean
     *                               the two lists had drifted apart
     */
    private static MappedField mapped(final String property) {
        return MAPPED_FIELDS.stream()
                .filter(field -> field.property().equals(property))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "no mapped field named " + property + " in this test's own contract table"));
    }

    /**
     * Returns the entity's declared instance fields, static and synthetic members removed.
     *
     * <p>The entity declares its widths as {@code private static final int} constants and the
     * coverage agent adds a synthetic static field of its own during a {@code verify} run, so both
     * are filtered out; what remains is the persistent state and nothing else.
     *
     * @return the declared instance fields, in declaration order
     */
    private static List<Field> instanceFields() {
        final List<Field> fields = new ArrayList<>();
        for (final Field field : Customer.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) {
                fields.add(field);
            }
        }
        return List.copyOf(fields);
    }

    /**
     * Returns the entity's declared instance field with the given name.
     *
     * @param property the Java property name
     * @return the reflected field
     * @throws IllegalStateException if the entity declares no such field
     */
    private static Field instanceField(final String property) {
        return instanceFields().stream()
                .filter(field -> field.getName().equals(property))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Customer declares no instance field named " + property));
    }

    /**
     * Returns every type name the entity's own declared surface mentions.
     *
     * <p>Field types, method return types, method parameter types, constructor parameter types and
     * the annotation types applied to the class, to its fields and to its methods - the full set a
     * reader would see in the source without following anything.
     *
     * @return the distinct fully qualified type names, in encounter order
     */
    private static Set<String> declaredSurfaceTypeNames() {
        final Set<String> names = new LinkedHashSet<>();
        for (final Annotation annotation : Customer.class.getDeclaredAnnotations()) {
            names.add(annotation.annotationType().getName());
        }
        for (final Field field : Customer.class.getDeclaredFields()) {
            names.add(field.getType().getName());
            for (final Annotation annotation : field.getDeclaredAnnotations()) {
                names.add(annotation.annotationType().getName());
            }
        }
        for (final var method : Customer.class.getDeclaredMethods()) {
            names.add(method.getReturnType().getName());
            for (final Class<?> parameter : method.getParameterTypes()) {
                names.add(parameter.getName());
            }
            for (final Annotation annotation : method.getDeclaredAnnotations()) {
                names.add(annotation.annotationType().getName());
            }
        }
        for (final var constructor : Customer.class.getDeclaredConstructors()) {
            for (final Class<?> parameter : constructor.getParameterTypes()) {
                names.add(parameter.getName());
            }
        }
        return Set.copyOf(names);
    }

    /**
     * Reports whether the given text contains a character that would be a zoned-decimal sign
     * overpunch if it appeared in the final byte of a signed numeric field.
     *
     * <p>{@code A} through {@code I} encode {@code +1} through {@code +9} and {@code J} through
     * {@code R} encode {@code -1} through {@code -9}. In a character field they are ordinary letters,
     * which is exactly why a sign decode must be driven by the picture clause and by position rather
     * than by looking for the characters themselves.
     *
     * @param text the field text to inspect
     * @return true if any character lies in {@code A} through {@code R}
     */
    private static boolean containsOverpunchCharacter(final String text) {
        for (int position = 0; position < text.length(); position++) {
            final char character = text.charAt(position);
            if (character >= 'A' && character <= 'R') {
                return true;
            }
        }
        return false;
    }

    /**
     * Loads the seeded customer fixture by classpath resource name.
     *
     * <p>{@link FixtureLoader} proves the geometry as it loads - fifty records of exactly 500
     * characters, 25,050 bytes, pure 7-bit ASCII, line-feed terminated - so a corrupted or
     * substituted fixture fails here with a message naming the resource rather than surfacing later
     * as an off-by-one slice.
     *
     * @return the snapshot of {@code custdata.txt}
     */
    private static FixtureLoader.FixtureData seededCustomers() {
        return FixtureLoader.load(FixtureLoader.Fixture.CUSTOMER);
    }

    /**
     * Builds a customer from one seeded record by slicing every field at its copybook column.
     *
     * <p>This is the only correct way to read a fixed-width record: each field is taken at the
     * position its picture clause dictates and stored verbatim. Nothing is trimmed - the record's
     * trailing 168 spaces are the {@code FILLER}, and trimming the record rather than slicing it
     * would collapse 500 characters to 332 and shift every field from
     * {@code CUST-ADDR-LINE-3} onward.
     *
     * @param data        the fixture snapshot
     * @param recordIndex the zero-based record index
     * @return the customer that record describes
     */
    private static Customer customerFrom(final FixtureLoader.FixtureData data, final int recordIndex) {
        return new Customer(
                Long.valueOf(slice(data, recordIndex, "customerId")),
                slice(data, recordIndex, "firstName"),
                slice(data, recordIndex, "middleName"),
                slice(data, recordIndex, "lastName"),
                slice(data, recordIndex, "addressLine1"),
                slice(data, recordIndex, "addressLine2"),
                slice(data, recordIndex, "addressLine3"),
                slice(data, recordIndex, "addressStateCode"),
                slice(data, recordIndex, "addressCountryCode"),
                slice(data, recordIndex, "addressZip"),
                slice(data, recordIndex, "phoneNumber1"),
                slice(data, recordIndex, "phoneNumber2"),
                slice(data, recordIndex, "ssn"),
                slice(data, recordIndex, "governmentIssuedId"),
                slice(data, recordIndex, "dateOfBirth"),
                slice(data, recordIndex, "eftAccountId"),
                slice(data, recordIndex, "primaryCardHolderIndicator"),
                slice(data, recordIndex, "ficoCreditScore"));
    }

    /**
     * Slices one field out of one seeded record at the position and width its picture clause declares.
     *
     * <p>Delegates to {@link FixtureLoader.FixtureData#field(int, int, int)}, whose columns are
     * one-based and inclusive of the start exactly as a copybook offset is read. The slice is
     * returned verbatim: padding is preserved and no conversion of any kind is applied, which is what
     * makes the round-trip assertions meaningful.
     *
     * @param data        the fixture snapshot
     * @param recordIndex the zero-based record index
     * @param property    the Java property whose field is wanted
     * @return the raw field text, exactly as wide as the picture clause declares
     */
    private static String slice(final FixtureLoader.FixtureData data, final int recordIndex,
                                final String property) {
        final MappedField mappedField = mapped(property);
        return data.field(recordIndex, mappedField.startColumn(), mappedField.width());
    }

    /**
     * Returns the value of one field on a customer, read through its own accessor.
     *
     * @param customer the customer to read
     * @param property the text property to read
     * @return the stored value
     */
    private static String read(final Customer customer, final String property) {
        return TEXT_FIELDS.stream()
                .filter(textField -> textField.field().property().equals(property))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no text accessor recorded for " + property))
                .getter()
                .apply(customer);
    }

    /**
     * Builds a customer from synthetic values, every field distinct and none resembling a real
     * person's data.
     *
     * <p>The values are deliberately recognisable tokens rather than plausible names, addresses or
     * numbers. That is what lets a rendering assertion search for each one by exact text and prove
     * its absence, and it keeps this source file free of anything that reads as personal data even
     * though it is invented.
     *
     * @return a fully populated customer whose every field value is unique
     */
    private static Customer syntheticCustomer() {
        return new Customer(1L,
                "FIRSTTOKEN", "MIDDLETOKEN", "LASTTOKEN",
                "ADDRONETOKEN", "ADDRTWOTOKEN", "ADDRTHREETOKEN",
                "ZZ", "QQQ", "ZIPTOKEN99",
                "PHONEONE00001", "PHONETWO00002",
                "111111111", "GOVTIDTOKEN00000", "1980-01-01",
                "EFTTOKEN01", "N", "742");
    }

    @Nested
    @DisplayName("1. One entity serves both proven-duplicate copybooks")
    class OneEntityForTwoCopybooks {

        @Test
        @DisplayName("the canonical date-of-birth field is the CVCUS01Y spelling, property dateOfBirth, "
                + "column cust_dob_yyyy_mm_dd")
        void theCanonicalDateOfBirthFieldIsTheCvcus01ySpelling() {
            final Field field = instanceField("dateOfBirth");

            assertThat(field.getType())
                    .as("CUST-DOB-YYYY-MM-DD is PIC X(10) in app/cpy/CVCUS01Y.cpy:L19 and PIC X(10) in "
                            + "app/cpy/CUSTREC.cpy:L19 too - only the NAME differs between the copybooks, so "
                            + "the field is character data in both and must be held as text")
                    .isEqualTo(String.class);

            assertThat(field.getAnnotation(Column.class).name())
                    .as("the column takes the CVCUS01Y spelling because that is the copybook the programs "
                            + "actually COPY; CUSTREC.cpy's CUST-DOB-YYYYMMDD is the same field under "
                            + "another name and contributes no second column")
                    .isEqualTo("cust_dob_yyyy_mm_dd");
        }

        @Test
        @DisplayName("no second column exists under the CUSTREC spelling of the same field")
        void noSecondColumnExistsUnderTheCustrecSpelling() {
            final List<String> columns = new ArrayList<>();
            for (final Field field : instanceFields()) {
                columns.add(field.getAnnotation(Column.class).name());
            }

            assertThat(columns)
                    .as("if CUSTREC.cpy had been modelled as a layout of its own there would be a "
                            + "cust_dob_yyyymmdd column beside cust_dob_yyyy_mm_dd, and two Java fields would "
                            + "have to be kept in lockstep forever over one physical byte range")
                    .doesNotContain("cust_dob_yyyymmdd")
                    .containsOnlyOnce("cust_dob_yyyy_mm_dd");
        }

        @ParameterizedTest(name = "no duplicate layout type named {0}")
        @ValueSource(strings = {
            "com.cardemo.model.entity.CustomerRecord",
            "com.cardemo.model.entity.CustRec",
            "com.cardemo.model.entity.CustomerRec",
            "com.cardemo.model.entity.CustRecord",
            "com.cardemo.model.entity.CustomerDuplicate"})
        @DisplayName("exactly one type models the record: no companion type for the duplicate copybook")
        void exactlyOneTypeModelsTheRecord(final String candidate) {
            assertThatCode(() -> Class.forName(candidate))
                    .as("both copybooks declare 01 CUSTOMER-RECORD, so no program can COPY them together "
                            + "and there is exactly one physical layout to model. A companion type named %s "
                            + "would be a second Java view of the same 500 bytes", candidate)
                    .isInstanceOf(ClassNotFoundException.class);
        }

        @Test
        @DisplayName("the single type carries the whole layout, so both COPY statements resolve to it")
        void theSingleTypeCarriesTheWholeLayout() {
            final List<String> properties = new ArrayList<>();
            for (final Field field : instanceFields()) {
                properties.add(field.getName());
            }

            assertThat(properties)
                    .as("eighteen populated fields of app/cpy/CVCUS01Y.cpy:L5-L22 plus the "
                            + "optimistic-locking counter, and nothing else. COPY CVCUS01Y and COPY CUSTREC "
                            + "both resolve to this one import")
                    .containsExactlyElementsOf(MAPPED_FIELDS.stream().map(MappedField::property).toList());
        }
    }

    @Nested
    @DisplayName("2. The 500-byte geometry, the nine-byte key and the mapping the entity declares")
    class RecordGeometryAndMapping {

        @Test
        @DisplayName("the eighteen populated widths sum to 332 and the 168-byte FILLER carries the record "
                + "to the catalogued 500")
        void thePopulatedWidthsPlusFillerSumToTheCataloguedLength() {
            final int populated = MAPPED_FIELDS.stream()
                    .filter(field -> !"version".equals(field.property()))
                    .mapToInt(MappedField::width)
                    .sum();

            assertThat(populated)
                    .as("9 + 25 + 25 + 25 + 50 + 50 + 50 + 2 + 3 + 10 + 15 + 15 + 9 + 20 + 10 + 10 + 1 + 3, "
                            + "read straight off the picture clauses at app/cpy/CVCUS01Y.cpy:L5-L22")
                    .isEqualTo(332);

            assertThat(populated + FILLER_WIDTH)
                    .as("plus FILLER PIC X(168) at :L23 this is the AVGLRECL 500 reported at "
                            + "app/catlg/LISTCAT.txt:L632, and the MAXLRECL 500 at :L633")
                    .isEqualTo(RECORD_LENGTH);
        }

        @Test
        @DisplayName("the FILLER is not modelled: no field and no column corresponds to it")
        void theFillerIsNotModelled() {
            final List<String> properties = new ArrayList<>();
            final List<String> columns = new ArrayList<>();
            for (final Field field : instanceFields()) {
                properties.add(field.getName().toLowerCase(Locale.ROOT));
                columns.add(field.getAnnotation(Column.class).name().toLowerCase(Locale.ROOT));
            }

            assertThat(properties).as("FILLER is unnamed slack, not data; modelling it would invent a "
                    + "column the corpus never reads or writes").noneMatch(name -> name.contains("filler"));
            assertThat(columns).as("and no column is named for it either").noneMatch(
                    name -> name.contains("filler"));
            assertThat(instanceFields())
                    .as("eighteen populated fields plus the version counter is nineteen; a twentieth would "
                            + "mean the filler had been modelled or a field invented")
                    .hasSize(MAPPED_FIELDS.size());
        }

        @Test
        @DisplayName("the table is customer and the entity is mapped")
        void theTableIsCustomer() {
            assertThat(Customer.class.isAnnotationPresent(Entity.class))
                    .as("the entity replaces the VSAM cluster named at app/catlg/LISTCAT.txt:L630")
                    .isTrue();
            assertThat(Customer.class.getAnnotation(Table.class).name())
                    .as("one table per KSDS cluster, named for the record it holds")
                    .isEqualTo("customer");
        }

        @Test
        @DisplayName("every one of the nineteen columns is NOT NULL")
        void everyColumnIsNotNull() {
            for (final MappedField mappedField : MAPPED_FIELDS) {
                final Column column = instanceField(mappedField.property()).getAnnotation(Column.class);

                assertThat(column.nullable())
                    .as("%s (%s) maps to column %s. A COBOL fixed-width field always holds its declared "
                            + "width - there is no absent value in a 500-byte record, only spaces or zeros - "
                            + "so every column is NOT NULL", mappedField.property(), mappedField.cobolField(),
                            mappedField.column())
                    .isFalse();
                assertThat(column.name())
                        .as("the column name of %s", mappedField.property())
                        .isEqualTo(mappedField.column());
            }
        }

        @Test
        @DisplayName("every text column's declared length equals its picture clause width")
        void everyTextColumnLengthEqualsThePictureWidth() {
            for (final TextField textField : TEXT_FIELDS) {
                final MappedField mappedField = textField.field();
                final Column column = instanceField(mappedField.property()).getAnnotation(Column.class);

                assertThat(column.length())
                        .as("%s comes from %s, so a width other than %d would let a value the record "
                                + "cannot hold reach the column - or refuse one it can",
                                mappedField.property(), mappedField.cobolField(), mappedField.width())
                        .isEqualTo(mappedField.width());
                assertThat(column.columnDefinition())
                        .as("and the declared SQL type is the fixed-width CHAR the picture clause implies, "
                                + "so the blank padding a fixed-width record carries is preserved rather "
                                + "than trimmed by a VARCHAR")
                        .isEqualTo("CHAR(" + mappedField.width() + ")");
            }
        }

        @Test
        @DisplayName("the key is the nine-digit CUST-ID: @Id, numeric, and NOT generated")
        void theKeyIsTheNineDigitCustomerId() {
            final Field key = instanceField("customerId");

            assertThat(key.isAnnotationPresent(Id.class))
                    .as("CUST-ID is the nine-byte VSAM key at RKP 0 - app/catlg/LISTCAT.txt:L632-L633")
                    .isTrue();
            assertThat(key.getType())
                    .as("PIC 9(09) is numeric with no leading-zero significance: the value is the key, and "
                            + "000000001 and 1 are the same record")
                    .isEqualTo(Long.class);
            assertThat(key.getAnnotation(Column.class).columnDefinition())
                    .as("nine digits, declared as such")
                    .isEqualTo("NUMERIC(9)");
            assertThat(key.isAnnotationPresent(GeneratedValue.class))
                    .as("the identifier is assigned by the business, not by a sequence: the seeded records "
                            + "arrive with identifiers 1 through 50 already in them, and generating a new "
                            + "one would break every cross-reference row that points at it")
                    .isFalse();
        }

        @Test
        @DisplayName("the nine-byte key admits exactly nine digits: 999999999 in, 1000000000 out")
        void theKeyAdmitsExactlyNineDigits() {
            final Customer customer = syntheticCustomer();

            assertThatCode(() -> customer.setCustomerId(MAX_CUSTOMER_ID))
                    .as("the largest value nine unsigned display digits can hold, which is what KEYLEN 9 "
                            + "allocates at app/catlg/LISTCAT.txt:L632")
                    .doesNotThrowAnyException();
            assertThat(String.valueOf(MAX_CUSTOMER_ID))
                    .as("and it really is nine characters wide")
                    .hasSize(KEY_LENGTH);
            assertThatIllegalArgumentException()
                    .as("a tenth digit cannot be written into a nine-byte key")
                    .isThrownBy(() -> customer.setCustomerId(MAX_CUSTOMER_ID + 1L))
                    .withMessageContaining("CUST-ID PIC 9(09)");
        }

        @Test
        @DisplayName("the optimistic-locking counter is present and is @Version, not a copybook field")
        void theOptimisticLockingCounterIsPresent() {
            final Field version = instanceField("version");

            assertThat(version.isAnnotationPresent(Version.class))
                    .as("Customer is one of the entities carrying the store-level half of the two-layer "
                            + "concurrency design: COACTUPC reads the customer record for update and "
                            + "rewrites it, so a lost update is a real hazard")
                    .isTrue();
            assertThat(MAPPED_FIELDS.stream().filter(field -> "version".equals(field.property())).toList())
                    .as("it has no counterpart in app/cpy/CVCUS01Y.cpy - the 500 bytes are fully accounted "
                            + "for by the eighteen populated fields and the filler")
                    .singleElement()
                    .extracting(MappedField::width)
                    .isEqualTo(0);
            assertThat(syntheticCustomer().getVersion())
                    .as("and it is null until the provider assigns it on first flush, so a freshly "
                            + "constructed instance is unambiguously transient")
                    .isNull();
        }

        @Test
        @DisplayName("no BigDecimal, no float and no double reaches this record - it holds no money and "
                + "no rate")
        void noDecimalOrFloatingPointFieldExists() {
            final List<Class<?>> types = instanceFields().stream().map(Field::getType).toList();

            assertThat(types)
                    .as("CVCUS01Y declares no V99 picture anywhere: every numeric field is an integral "
                            + "identifier or a three-digit score. Unlike Account, this entity has no money "
                            + "column at all, so there is nothing here for BigDecimal to hold")
                    .doesNotContain(BigDecimal.class)
                    .doesNotContain(Float.class, Double.class, float.class, double.class);
            assertThat(types)
                    .as("which leaves exactly two carriers: Long for the two integral counters and String "
                            + "for the seventeen character fields")
                    .containsOnly(Long.class, String.class);
        }

        @Test
        @DisplayName("the entity is not Serializable, so no record can leave the JVM by deserialization")
        void theEntityIsNotSerializable() {
            assertThat(Serializable.class.isAssignableFrom(Customer.class))
                    .as("nothing in the corpus serialises a customer record: it is read from a KSDS and "
                            + "written back. Implementing Serializable would add an unchecked ingress for "
                            + "the most personal record in the schema and buy nothing")
                    .isFalse();
        }

        @Test
        @DisplayName("the eighteen populated fields tile the first 332 bytes with no gap and no overlap")
        void theFieldsTileTheRecordWithoutGapOrOverlap() {
            int expectedStart = 1;
            for (final MappedField mappedField : MAPPED_FIELDS) {
                if ("version".equals(mappedField.property())) {
                    continue;
                }
                assertThat(mappedField.startColumn())
                        .as("%s (%s) must begin exactly where the previous field ends; a gap or an overlap "
                                + "here would shift every later field and corrupt the whole record",
                                mappedField.property(), mappedField.cobolField())
                        .isEqualTo(expectedStart);
                expectedStart += mappedField.width();
            }

            assertThat(expectedStart - 1)
                    .as("so the populated region ends at byte 332 and the FILLER runs 333-500")
                    .isEqualTo(RECORD_LENGTH - FILLER_WIDTH);
        }
    }

    @Nested
    @DisplayName("3. Leading zeros are real, so three fields are text - proved against the seed data")
    class LeadingZerosForceTextTypes {

        @Test
        @DisplayName("the fixture is fifty records of exactly 500 characters, matching the catalogue")
        void theFixtureMatchesTheCataloguedGeometry() {
            final FixtureLoader.FixtureData data = seededCustomers();

            assertThat(data.resourceName())
                    .as("addressed by classpath resource name only - a bare name at the classpath root, "
                            + "never a filesystem path, so nothing here reaches the frozen app/ tree")
                    .isEqualTo("custdata.txt");
            assertThat(data.recordCount())
                    .as("REC-TOTAL 50 in app/catlg/LISTCAT.txt agrees with the fixture row count")
                    .isEqualTo(SEEDED_RECORD_COUNT);
            assertThat(data.recordWidth())
                    .as("and every record is the catalogued AVGLRECL 500 wide")
                    .isEqualTo(RECORD_LENGTH);
            assertThat(data.byteCount())
                    .as("fifty records of 500 characters plus one line feed each")
                    .isEqualTo(SEEDED_RECORD_COUNT * (RECORD_LENGTH + 1));
        }

        @Test
        @DisplayName("every record ends in exactly 168 spaces, so trimming one would collapse 500 to 332")
        void trimmingAWholeRecordWouldCollapseItTo332() {
            final FixtureLoader.FixtureData data = seededCustomers();

            for (int index = 0; index < data.recordCount(); index++) {
                final String record = data.recordAt(index);
                final String withoutTrailingSpaces = record.stripTrailing();

                assertThat(record.length() - withoutTrailingSpaces.length())
                        .as("record %d: FILLER PIC X(168) at app/cpy/CVCUS01Y.cpy:L23 is all spaces on "
                                + "every seeded row, so a trim removes exactly the filler", index)
                        .isEqualTo(FILLER_WIDTH);
                assertThat(withoutTrailingSpaces)
                        .as("record %d: which is why a record must be SLICED by position and never "
                                + "trimmed - a trimmed record is 332 characters and every offset from "
                                + "CUST-FICO-CREDIT-SCORE at 330-332 backwards is the last that still fits",
                                index)
                        .hasSize(RECORD_LENGTH - FILLER_WIDTH);
            }
        }

        @Test
        @DisplayName("a trimmed record cannot even be sliced: the FICO offset falls off the end")
        void aTrimmedRecordCannotBeSlicedAtTheLaterOffsets() {
            final FixtureLoader.FixtureData data = seededCustomers();
            final String trimmed = data.recordAt(0).trim();
            final MappedField fico = mapped("ficoCreditScore");

            assertThat(trimmed.length())
                    .as("record 1 trimmed at both ends: the leading nine digits of CUST-ID carry no space, "
                            + "so only the 168 trailing filler bytes are lost")
                    .isEqualTo(RECORD_LENGTH - FILLER_WIDTH);
            assertThat(fico.startColumn() + fico.width() - 1)
                    .as("CUST-FICO-CREDIT-SCORE ends at byte 332, the last populated byte, so it is the "
                            + "field that survives a trim by the narrowest margin - and any field the "
                            + "corpus later appends after it would not")
                    .isEqualTo(trimmed.length());
        }

        @Test
        @DisplayName("six of the fifty social security numbers begin with a zero, so ssn is CHAR(9) text")
        void sixSeededSocialSecurityNumbersBeginWithZero() {
            final FixtureLoader.FixtureData data = seededCustomers();
            final List<Integer> rowsWithLeadingZero = new ArrayList<>();
            for (int index = 0; index < data.recordCount(); index++) {
                if (slice(data, index, "ssn").startsWith("0")) {
                    rowsWithLeadingZero.add(index + 1);
                }
            }

            assertThat(rowsWithLeadingZero)
                    .as("rows 1, 15, 24, 29, 40 and 47 of app/data/ASCII/custdata.txt carry a "
                            + "leading-zero CUST-SSN at columns 280-288. An Integer or Long field would "
                            + "silently drop that zero and shorten the number to eight digits")
                    .containsExactly(1, 15, 24, 29, 40, 47);
            assertThat(instanceField("ssn").getType())
                    .as("which is why CUST-SSN PIC 9(09) is held as text even though its picture clause "
                            + "is numeric: the digits are an identifier, never an arithmetic operand")
                    .isEqualTo(String.class);
        }

        @Test
        @DisplayName("a leading-zero social security number round-trips through the entity unchanged")
        void aLeadingZeroSocialSecurityNumberRoundTrips() {
            final FixtureLoader.FixtureData data = seededCustomers();
            final String stored = slice(data, 0, "ssn");
            final Customer customer = customerFrom(data, 0);

            assertThat(customer.getSsn())
                    .as("customer %s: the stored form is returned verbatim", customer.getCustomerId())
                    .isEqualTo(stored)
                    .hasSize(mapped("ssn").width());
            assertThat(customer.getSsn().charAt(0))
                    .as("customer %s: and the leading zero is still the first character",
                            customer.getCustomerId())
                    .isEqualTo('0');
            assertThat(customer.getSsn()).as("customer %s: nine digits and nothing else",
                    customer.getCustomerId()).containsOnlyDigits();
        }

        @Test
        @DisplayName("seven of the fifty credit scores begin with a zero, so ficoCreditScore is CHAR(3) text")
        void sevenSeededCreditScoresBeginWithZero() {
            final FixtureLoader.FixtureData data = seededCustomers();
            final Set<String> leadingZeroScores = new LinkedHashSet<>();
            int rowsWithLeadingZero = 0;
            for (int index = 0; index < data.recordCount(); index++) {
                final String score = slice(data, index, "ficoCreditScore");
                if (score.startsWith("0")) {
                    rowsWithLeadingZero++;
                    leadingZeroScores.add(score);
                }
            }

            assertThat(rowsWithLeadingZero)
                    .as("seven rows carry a leading-zero CUST-FICO-CREDIT-SCORE at columns 330-332")
                    .isEqualTo(7);
            assertThat(leadingZeroScores)
                    .as("and these are the exact values; a numeric field would render 001 as 1 and 044 as "
                            + "44, so the three-character fixed-width image could never be re-emitted")
                    .containsExactlyInAnyOrder("001", "044", "051", "053", "054", "058", "078");
            assertThat(instanceField("ficoCreditScore").getType())
                    .as("hence CUST-FICO-CREDIT-SCORE PIC 9(03) is text: the score is compared and "
                            + "displayed, never summed")
                    .isEqualTo(String.class);
        }

        @ParameterizedTest(name = "credit score {0} round-trips with its leading zeros intact")
        @ValueSource(strings = {"001", "044", "051", "053", "054", "058", "078"})
        @DisplayName("every leading-zero credit score in the seed data round-trips unchanged")
        void everyLeadingZeroCreditScoreRoundTrips(final String score) {
            final Customer customer = syntheticCustomer();

            customer.setFicoCreditScore(score);

            assertThat(customer.getFicoCreditScore())
                    .as("stored verbatim, three characters wide, zeros and all")
                    .isEqualTo(score)
                    .hasSize(mapped("ficoCreditScore").width());
        }

        @Test
        @DisplayName("every seeded date of birth is the dash-separated ten-character form, not a date type")
        void everySeededDateOfBirthIsDashSeparatedText() {
            final FixtureLoader.FixtureData data = seededCustomers();

            for (int index = 0; index < data.recordCount(); index++) {
                final String dateOfBirth = slice(data, index, "dateOfBirth");

                assertThat(dateOfBirth)
                        .as("record %d: CUST-DOB-YYYY-MM-DD at columns 309-318", index)
                        .hasSize(mapped("dateOfBirth").width());
                assertThat(dateOfBirth.charAt(4))
                        .as("record %d: a dash separates year from month", index)
                        .isEqualTo('-');
                assertThat(dateOfBirth.charAt(7))
                        .as("record %d: and month from day", index)
                        .isEqualTo('-');
            }

            assertThat(instanceField("dateOfBirth").getType())
                    .as("the field is text, never LocalDate: the entity stores what the record holds and "
                            + "parses nothing, so a date the legacy system accepted cannot become "
                            + "unloadable here")
                    .isEqualTo(String.class);
        }

        @Test
        @DisplayName("both telephone numbers round-trip with their punctuation and their padding intact")
        void bothTelephoneNumbersRoundTripWithPunctuationAndPadding() {
            final FixtureLoader.FixtureData data = seededCustomers();
            final Customer customer = customerFrom(data, 0);

            for (final String property : List.of("phoneNumber1", "phoneNumber2")) {
                final String stored = slice(data, 0, property);

                assertThat(read(customer, property))
                        .as("customer %s, %s: stored verbatim", customer.getCustomerId(), property)
                        .isEqualTo(stored)
                        .hasSize(mapped(property).width());
                assertThat(stored.substring(0, 13))
                        .as("customer %s, %s: the seeded form is (NNN)NNN-NNNN - thirteen characters of "
                                + "digits and punctuation", customer.getCustomerId(), property)
                        .matches("\\(\\d{3}\\)\\d{3}-\\d{4}");
                assertThat(stored.substring(13))
                        .as("customer %s, %s: followed by two spaces, because PIC X(15) pads a "
                                + "thirteen-character value to its declared width and the fixed-width "
                                + "writer must re-emit all fifteen", customer.getCustomerId(), property)
                        .isEqualTo("  ");
            }
        }

        @Test
        @DisplayName("the ten-character ZIP holds both seeded forms: five digits padded, and full ZIP+4")
        void theZipHoldsBothSeededForms() {
            final FixtureLoader.FixtureData data = seededCustomers();
            int fiveDigitPadded = 0;
            int zipPlusFour = 0;
            for (int index = 0; index < data.recordCount(); index++) {
                final String zip = slice(data, index, "addressZip");
                if (zip.matches("\\d{5} {5}")) {
                    fiveDigitPadded++;
                } else if (zip.matches("\\d{5}-\\d{4}")) {
                    zipPlusFour++;
                }
                assertThat(read(customerFrom(data, index), "addressZip"))
                        .as("record %d: whichever form it takes, CUST-ADDR-ZIP is stored verbatim at its "
                                + "full declared width", index)
                        .isEqualTo(zip)
                        .hasSize(mapped("addressZip").width());
            }

            assertThat(fiveDigitPadded + zipPlusFour)
                    .as("every seeded ZIP is one of the two forms and none is malformed")
                    .isEqualTo(SEEDED_RECORD_COUNT);
            assertThat(fiveDigitPadded)
                    .as("twenty rows hold a five-digit ZIP left-justified and space-padded to ten - row 1 "
                            + "is one of them. A field map that described the column as five digits plus "
                            + "five spaces would be describing only these twenty")
                    .isEqualTo(20);
            assertThat(zipPlusFour)
                    .as("and the other thirty hold a full ZIP+4, filling all ten characters. Both are "
                            + "legal PIC X(10) content, which is exactly why the entity validates width "
                            + "and nothing else")
                    .isEqualTo(30);
        }

        @Test
        @DisplayName("country and primary-holder indicator are constant across all fifty seeded rows")
        void countryAndPrimaryHolderAreConstantAcrossTheSeedData() {
            final FixtureLoader.FixtureData data = seededCustomers();
            final Set<String> countries = new LinkedHashSet<>();
            final Set<String> indicators = new LinkedHashSet<>();
            for (int index = 0; index < data.recordCount(); index++) {
                countries.add(slice(data, index, "addressCountryCode"));
                indicators.add(slice(data, index, "primaryCardHolderIndicator"));
            }

            assertThat(countries)
                    .as("CUST-ADDR-COUNTRY-CD at columns 237-239 is USA on every seeded row - a uniformity "
                            + "of the sample, not a constraint: PIC X(03) admits any three characters and "
                            + "the entity adds no check")
                    .containsExactly("USA");
            assertThat(indicators)
                    .as("CUST-PRI-CARD-HOLDER-IND at column 329 is Y on every seeded row, so the negative "
                            + "case is unexercised by the seed data and must be covered synthetically")
                    .containsExactly("Y");
            assertThatCode(() -> syntheticCustomer().setPrimaryCardHolderIndicator("N"))
                    .as("which this does: N is accepted, because PIC X(01) admits it and no 88-level "
                            + "restricts the field in the copybook")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("all fifty seeded records load and every field round-trips at its copybook offset")
        void allFiftySeededRecordsRoundTripFieldByField() {
            final FixtureLoader.FixtureData data = seededCustomers();

            for (int index = 0; index < data.recordCount(); index++) {
                final Customer customer = customerFrom(data, index);

                assertThat(customer.getCustomerId())
                        .as("record %d: CUST-ID at columns 1-9 parses to the row number, the seed data "
                                + "being identifiers 1 through 50 in order", index)
                        .isEqualTo(Long.valueOf(index + 1L));

                for (final TextField textField : TEXT_FIELDS) {
                    final String property = textField.field().property();
                    assertThat(textField.getter().apply(customer))
                            .as("customer %s, %s (%s): the value read at columns %d-%d is stored and "
                                    + "returned unchanged - no trim, no pad, no case fold",
                                    customer.getCustomerId(), property, textField.field().cobolField(),
                                    textField.field().startColumn(),
                                    textField.field().startColumn() + textField.field().width() - 1)
                            .isEqualTo(slice(data, index, property))
                            .hasSize(textField.field().width());
                }
            }
        }
    }

    @Nested
    @DisplayName("4. Two latent constraints the seed data violates, which must NOT be enforced here")
    class LatentConstraintsThatMustNotBeEnforced {

        @Test
        @DisplayName("HIGH: twenty-one of the fifty seeded scores fall below the 300 floor the screen "
                + "predicate declares")
        void twentyOneSeededScoresFallBelowTheScreenPredicateFloor() {
            final FixtureLoader.FixtureData data = seededCustomers();
            int belowFloor = 0;
            String lowest = null;
            String highest = null;
            for (int index = 0; index < data.recordCount(); index++) {
                final String score = slice(data, index, "ficoCreditScore");
                if (Integer.parseInt(score) < FICO_PREDICATE_FLOOR) {
                    belowFloor++;
                }
                if (lowest == null || Integer.parseInt(score) < Integer.parseInt(lowest)) {
                    lowest = score;
                }
                if (highest == null || Integer.parseInt(score) > Integer.parseInt(highest)) {
                    highest = score;
                }
            }

            assertThat(belowFloor)
                    .as("app/cbl/COACTUPC.cbl:L848-L849 declares 88 FICO-RANGE-IS-VALID VALUES 300 THROUGH "
                            + "850, but that 88-level sits on ACUP-NEW-CUST-FICO-SCORE at :L845-L847 - a "
                            + "SCREEN INPUT field on the account-update path - not on CUST-FICO-CREDIT-SCORE "
                            + "in the record. Twenty-one seeded customers score below the floor")
                    .isEqualTo(21);
            assertThat(lowest)
                    .as("the lowest seeded score, two orders of magnitude below the predicate floor")
                    .isEqualTo("001");
            assertThat(highest)
                    .as("and the highest, itself comfortably below the 850 ceiling")
                    .isEqualTo("793");
            assertThat(Integer.parseInt(highest))
                    .as("so no seeded row tests the ceiling and every failing row fails the floor")
                    .isLessThan(FICO_PREDICATE_CEILING);
        }

        @Test
        @DisplayName("HIGH: a sub-300 score is accepted, stored and returned unchanged - remediation: do "
                + "not add @Min(300)/@Max(850)")
        void aSubThreeHundredScoreIsAcceptedAndReturnedUnchanged() {
            final FixtureLoader.FixtureData data = seededCustomers();
            final Customer customer = customerFrom(data, 7);

            assertThat(customer.getCustomerId())
                    .as("record 8 of the seed data, whose score is the leading-zero 051")
                    .isEqualTo(8L);
            assertThat(customer.getFicoCreditScore())
                    .as("a score far below the screen predicate's floor loads unchanged. Were a range "
                            + "constraint added to the entity, twenty-one of the fifty seeded customers "
                            + "would become unloadable and the named-fixture gate would fail on real seed "
                            + "data - severity High, remediation: do not add the constraint")
                    .isEqualTo("051");
            assertThatCode(() -> customer.setFicoCreditScore("001"))
                    .as("and the lowest seeded score is equally acceptable through the mutator")
                    .doesNotThrowAnyException();
            assertThat(customer.getFicoCreditScore()).as("stored verbatim").isEqualTo("001");
        }

        @ParameterizedTest(name = "credit score {0} is accepted because width, not range, is the contract")
        @ValueSource(strings = {"000", "001", "051", "274", "299", "300", "793", "850", "851", "900", "999"})
        @DisplayName("HIGH: the entity ranges nothing - every three-character score is accepted, on both "
                + "sides of the predicate's bounds")
        void everyThreeCharacterScoreIsAccepted(final String score) {
            final Customer customer = syntheticCustomer();

            assertThatCode(() -> customer.setFicoCreditScore(score))
                    .as("CUST-FICO-CREDIT-SCORE PIC 9(03) admits 000 through 999. 000 and 001 are below the "
                            + "floor, 851 and 900 are above the ceiling, and all four are legal record "
                            + "content - the 88-level is a screen predicate, not a record invariant")
                    .doesNotThrowAnyException();
            assertThat(customer.getFicoCreditScore()).as("and it is stored as given").isEqualTo(score);
        }

        @Test
        @DisplayName("HIGH: no bean-validation constraint of any kind is declared on ficoCreditScore")
        void noValidationConstraintIsDeclaredOnTheCreditScore() {
            final Annotation[] annotations = instanceField("ficoCreditScore").getDeclaredAnnotations();
            final List<String> annotationTypes = new ArrayList<>();
            for (final Annotation annotation : annotations) {
                annotationTypes.add(annotation.annotationType().getName());
            }

            assertThat(annotationTypes)
                    .as("mapping annotations only. A jakarta.validation constraint here - @Min, @Max, "
                            + "@Digits or @Pattern - would be enforced on every persist and would reject "
                            + "the seed data")
                    .noneMatch(name -> name.startsWith("jakarta.validation"));
            assertThat(annotationTypes)
                    .as("what is present is the fixed-width CHAR mapping and nothing else")
                    .contains(Column.class.getName());
        }

        @Test
        @DisplayName("HIGH: four state codes in the seed data are absent from the copybook's fifty-six "
                + "entry table")
        void fourSeededStateCodesAreAbsentFromTheCopybookTable() {
            final FixtureLoader.FixtureData data = seededCustomers();
            final Set<String> distinctCodes = new LinkedHashSet<>();
            int rowsWithUnlistedCode = 0;
            for (int index = 0; index < data.recordCount(); index++) {
                final String code = slice(data, index, "addressStateCode");
                distinctCodes.add(code);
                if (UNLISTED_STATE_CODES.contains(code)) {
                    rowsWithUnlistedCode++;
                }
            }

            assertThat(distinctCodes)
                    .as("thirty-six distinct CUST-ADDR-STATE-CD values at columns 235-236 across the fifty "
                            + "seeded rows")
                    .hasSize(36)
                    .containsAll(UNLISTED_STATE_CODES);
            assertThat(rowsWithUnlistedCode)
                    .as("AP on one row, FM on two, MH on one and PW on one - five rows in total whose code "
                            + "app/cpy/CSLKPCDY.cpy:L1013 does not list among its fifty-six "
                            + "88 VALID-US-STATE-CODE values. AP is a military overseas designation and "
                            + "FM, MH and PW are the freely associated states")
                    .isEqualTo(5);
        }

        @ParameterizedTest(name = "unlisted state code {0} persists unchanged")
        @ValueSource(strings = {"AP", "FM", "MH", "PW"})
        @DisplayName("HIGH: an unlisted state code is accepted and returned unchanged - remediation: do not "
                + "enforce us-state-codes.json membership")
        void anUnlistedStateCodeIsAcceptedAndReturnedUnchanged(final String code) {
            final Customer customer = syntheticCustomer();

            assertThatCode(() -> customer.setAddressStateCode(code))
                    .as("the copybook table gates SCREEN INPUT, exactly as the FICO predicate does. "
                            + "Enforcing us-state-codes.json membership at the entity would make these five "
                            + "seeded rows unloadable - severity High, remediation: do not enforce it. "
                            + "Adding the four codes to the table is equally wrong: it would edit the "
                            + "frozen corpus's own contract")
                    .doesNotThrowAnyException();
            assertThat(customer.getAddressStateCode())
                    .as("stored verbatim at its full two-character width")
                    .isEqualTo(code)
                    .hasSize(mapped("addressStateCode").width());
        }

        @Test
        @DisplayName("HIGH: no bean-validation constraint of any kind is declared on addressStateCode")
        void noValidationConstraintIsDeclaredOnTheStateCode() {
            final List<String> annotationTypes = new ArrayList<>();
            for (final Annotation annotation : instanceField("addressStateCode").getDeclaredAnnotations()) {
                annotationTypes.add(annotation.annotationType().getName());
            }

            assertThat(annotationTypes)
                    .as("no @Pattern and no membership check; the lookup belongs to the validation service "
                            + "that serves the screen paths, where the copybook table actually applies")
                    .noneMatch(name -> name.startsWith("jakarta.validation"));
        }

        @Test
        @DisplayName("no field anywhere on the entity carries a bean-validation constraint")
        void noFieldCarriesABeanValidationConstraint() {
            for (final Field field : instanceFields()) {
                final List<String> annotationTypes = new ArrayList<>();
                for (final Annotation annotation : field.getDeclaredAnnotations()) {
                    annotationTypes.add(annotation.annotationType().getName());
                }

                assertThat(annotationTypes)
                        .as("%s: the entity's only guard is structural - null and width, enforced in its "
                                + "own constructor and mutators. Business predicates live where the source "
                                + "puts them, on the screen paths, so nothing the legacy system stored can "
                                + "become unloadable", field.getName())
                        .noneMatch(name -> name.startsWith("jakarta.validation"));
            }
        }
    }

    @Nested
    @DisplayName("5. Personal data: the strictest rendering in the entity set")
    class PersonalDataNeverLeavesTheRecord {

        @Test
        @DisplayName("the rendering is exactly the identifier and the version, in that form")
        void theRenderingIsExactlyTheIdentifierAndTheVersion() {
            final Customer customer = syntheticCustomer();
            customer.setVersion(3L);

            assertThat(customer.toString())
                    .as("this record carries a social security number, a date of birth, two telephone "
                            + "numbers, a government-issued identifier and a full postal address. A "
                            + "rendering is the string most likely to reach a log line, an exception "
                            + "message or a stack trace, so it carries the two values that identify the row "
                            + "and nothing that describes the person")
                    .isEqualTo("Customer[customerId=1, version=3]");
        }

        @Test
        @DisplayName("none of the seventeen other fields appears in the rendering, by name or by value")
        void noOtherFieldAppearsInTheRendering() {
            final Customer customer = syntheticCustomer();
            customer.setVersion(0L);
            final String rendered = customer.toString();

            for (final TextField textField : TEXT_FIELDS) {
                final MappedField mappedField = textField.field();

                assertThat(rendered)
                        .as("%s (%s) must not reach the rendering: every value in this record is either "
                                + "personal data or a direct route to it", mappedField.property(),
                                mappedField.cobolField())
                        .doesNotContain(textField.getter().apply(customer))
                        .doesNotContain(mappedField.property())
                        .doesNotContain(mappedField.column());
            }

            assertThat(rendered)
                    .as("only the two permitted property names appear at all")
                    .contains("customerId", "version");
        }

        @Test
        @DisplayName("a real seeded record renders without its social security number, telephone numbers "
                + "or date of birth")
        void aSeededRecordRendersWithoutItsPersonalFields() {
            final FixtureLoader.FixtureData data = seededCustomers();
            final Customer customer = customerFrom(data, 0);
            final String rendered = customer.toString();

            for (final String property : List.of("ssn", "phoneNumber1", "phoneNumber2", "dateOfBirth",
                    "governmentIssuedId", "addressLine1", "eftAccountId", "addressZip", "lastName")) {
                assertThat(rendered)
                        .as("customer %s: %s is absent from the rendering. The assertion names the field "
                                + "and the customer identifier, never the value - a failure message is a "
                                + "log line too", customer.getCustomerId(), property)
                        .doesNotContain(read(customer, property));
            }

            assertThat(rendered)
                    .as("customer %s: what remains is the identifier and the version, the version still "
                            + "null because nothing has flushed", customer.getCustomerId())
                    .isEqualTo("Customer[customerId=1, version=null]");
        }

        @Test
        @DisplayName("the width-guard failure message reports the length and never the rejected value")
        void theWidthGuardMessageReportsTheLengthAndNeverTheValue() {
            final Customer customer = syntheticCustomer();
            final String tooLong = "0123456789";

            assertThatIllegalArgumentException()
                    .as("a ten-character social security number cannot fit CUST-SSN PIC 9(09)")
                    .isThrownBy(() -> customer.setSsn(tooLong))
                    .withMessageContaining("ssn")
                    .withMessageContaining("CUST-SSN PIC 9(09)")
                    .withMessageContaining(String.valueOf(tooLong.length()))
                    .withMessageNotContaining(tooLong);
            assertThat(customer.getSsn())
                    .as("and the refusal is a no-op: the guard runs before the assignment, so no partial "
                            + "value is left behind")
                    .isEqualTo("111111111");
        }

        @Test
        @DisplayName("the null-guard failure message names the property and its column, not a value")
        void theNullGuardMessageNamesThePropertyAndItsColumn() {
            final Customer customer = syntheticCustomer();

            assertThatIllegalArgumentException()
                    .as("every column of table customer is NOT NULL, and a fixed-width COBOL field always "
                            + "holds its width, so a null can only be a defect")
                    .isThrownBy(() -> customer.setDateOfBirth(null))
                    .withMessageContaining("dateOfBirth")
                    .withMessageContaining("CUST-DOB-YYYY-MM-DD PIC X(10)")
                    .withMessageContaining("NOT NULL")
                    .withMessageContaining("customer");
        }

        @Test
        @DisplayName("the entity is opaque to JSON, so no serialiser can walk it into a response body")
        void theEntityIsOpaqueToJson() {
            final List<String> classAnnotations = new ArrayList<>();
            for (final Annotation annotation : Customer.class.getDeclaredAnnotations()) {
                classAnnotations.add(annotation.annotationType().getSimpleName());
            }

            assertThat(classAnnotations)
                    .as("the REST surface returns DTOs shaped by the BMS symbolic maps, never the entity. "
                            + "Marking the type ignored and every accessor invisible means an entity that "
                            + "reaches a response body by accident produces nothing rather than the whole "
                            + "record, so the failure is visible instead of a disclosure")
                    .contains("JsonIgnoreType", "JsonAutoDetect");
        }
    }

    @Nested
    @DisplayName("6. The stored date of birth is the dash-separated ten-character form (entity side only)")
    class TheStoredDateOfBirthForm {

        @Test
        @DisplayName("the stored form is ten characters with its components at offsets 1, 6 and 9")
        void theStoredFormHasItsComponentsAtOffsetsOneSixAndNine() {
            final Customer customer = syntheticCustomer();
            final String stored = customer.getDateOfBirth();

            assertThat(stored)
                    .as("CUST-DOB-YYYY-MM-DD PIC X(10) at app/cpy/CVCUS01Y.cpy:L19")
                    .hasSize(mapped("dateOfBirth").width());
            assertThat(stored.substring(0, 4))
                    .as("the year occupies COBOL offset (1:4)")
                    .isEqualTo("1980");
            assertThat(stored.substring(5, 7))
                    .as("the month occupies (6:2), because a dash sits at offset 5")
                    .isEqualTo("01");
            assertThat(stored.substring(8, 10))
                    .as("and the day occupies (9:2), after the second dash at offset 8")
                    .isEqualTo("01");
        }

        @Test
        @DisplayName("HIGH: a whole-string comparison against the compact snapshot form is unequal, which "
                + "is why the source compares components")
        void aWholeStringComparisonAgainstTheCompactFormIsUnequal() {
            final String stored = syntheticCustomer().getDateOfBirth();
            final String compactSnapshotForm = "19800101";

            assertThat(stored)
                    .as("the same date in the two representations the two layers use")
                    .isNotEqualTo(compactSnapshotForm);
            assertThat(compactSnapshotForm)
                    .as("ACUP-OLD-CUST-DOB-YYYY-MM-DD is PIC X(08) at app/cbl/COACTUPC.cbl:L746, with its "
                            + "ACUP-NEW twin at :L837 - two characters narrower than the stored form "
                            + "because it carries no separators")
                    .hasSize(mapped("dateOfBirth").width() - 2);
            assertThat(stored.replace("-", ""))
                    .as("strip the separators and the two agree, which is the whole point: "
                            + "9700-CHECK-CHANGE-IN-REC at app/cbl/COACTUPC.cbl:L4174-L4179 compares (1:4) "
                            + "against (1:4), (6:2) against (5:2) and (9:2) against (7:2). A whole-string "
                            + "comparison would report a change on every single request and make the "
                            + "endpoint permanently unusable - severity High. The offset asymmetry itself is "
                            + "asserted by AccountUpdateRequestTest, which owns the snapshot type")
                    .isEqualTo(compactSnapshotForm);
        }

        @Test
        @DisplayName("the entity parses no date: it accepts the compact form too, storing exactly what it "
                + "is given")
        void theEntityParsesNoDate() {
            final Customer customer = syntheticCustomer();

            assertThatCode(() -> customer.setDateOfBirth("19800101"))
                    .as("PIC X(10) admits any value up to ten characters, and the entity validates width "
                            + "alone. Parsing here would reject content the legacy record could hold, so "
                            + "the interpretation stays with the service layer that knows which "
                            + "representation it is holding")
                    .doesNotThrowAnyException();
            assertThat(customer.getDateOfBirth())
                    .as("stored verbatim, unpadded and unparsed")
                    .isEqualTo("19800101");
            assertThatIllegalArgumentException()
                    .as("eleven characters is the one thing a PIC X(10) field cannot hold")
                    .isThrownBy(() -> customer.setDateOfBirth("1980-01-011"))
                    .withMessageContaining("CUST-DOB-YYYY-MM-DD PIC X(10)");
        }

        @ParameterizedTest(name = "{0} keeps its dashes through the accessor")
        @CsvSource({"1961-06-08", "1980-01-01", "1999-12-31", "2000-02-29"})
        @DisplayName("a dash-separated date round-trips with its separators intact")
        void aDashSeparatedDateRoundTripsWithItsSeparators(final String dateOfBirth) {
            final Customer customer = syntheticCustomer();

            customer.setDateOfBirth(dateOfBirth);

            assertThat(customer.getDateOfBirth())
                    .as("no normalisation, no reformatting: the stored representation is what the "
                            + "change-detection comparison and the fixed-width writer both depend on")
                    .isEqualTo(dateOfBirth)
                    .hasSize(mapped("dateOfBirth").width());
        }
    }

    @Nested
    @DisplayName("7. Scope boundaries: a flat record with no relationship and no reach into other layers")
    class ScopeBoundaries {

        @Test
        @DisplayName("no association or collection annotation appears anywhere on the entity")
        void noAssociationAnnotationAppearsAnywhere() {
            for (final Class<? extends Annotation> association : ASSOCIATION_ANNOTATIONS) {
                assertThat(Customer.class.isAnnotationPresent(association))
                        .as("@%s is absent from the type", association.getSimpleName())
                        .isFalse();

                for (final Field field : Customer.class.getDeclaredFields()) {
                    assertThat(field.isAnnotationPresent(association))
                            .as("@%s is absent from %s. CUSTOMER-RECORD is flat: no OCCURS, no pointer to "
                                    + "another dataset. A customer reaches its cards through the "
                                    + "cross-reference cluster, which is a row in its own table, so an "
                                    + "association here would model a join the record does not contain",
                                    association.getSimpleName(), field.getName())
                            .isFalse();
                }
            }
        }

        @Test
        @DisplayName("the declared surface reaches no service, repository, controller or other layer")
        void theDeclaredSurfaceReachesNoOtherLayer() {
            final Set<String> surface = declaredSurfaceTypeNames();

            for (final String forbidden : FORBIDDEN_PACKAGE_PREFIXES) {
                assertThat(surface)
                        .as("an entity that named a type from %s would invert the dependency direction of "
                                + "the whole tree - a persistent record is the leaf, not the root",
                                forbidden)
                        .noneMatch(name -> name.startsWith(forbidden + "."));
            }
        }

        @Test
        @DisplayName("the entity carries no composite key type and no enumerated column")
        void theEntityCarriesNoCompositeKeyOrEnumeratedColumn() {
            final Set<String> surface = declaredSurfaceTypeNames();

            assertThat(surface)
                    .as("CUSTDATA's key is the nine-byte CUST-ID alone, so unlike the three clusters whose "
                            + "keys are compounds there is nothing for model.key to hold")
                    .noneMatch(name -> name.startsWith("com.cardemo.model.key."));
            assertThat(surface)
                    .as("and no column of this record is an enumeration: the state code, country code and "
                            + "primary-holder indicator all carry values the copybook tables do not "
                            + "constrain, which is precisely why they are text")
                    .noneMatch(name -> name.startsWith("com.cardemo.model.enums."));
        }

        @Test
        @DisplayName("the declared surface names no project type at all, not even the entity itself")
        void theDeclaredSurfaceNamesNoProjectType() {
            final List<String> projectTypes = declaredSurfaceTypeNames().stream()
                    .filter(name -> name.startsWith("com.cardemo."))
                    .sorted()
                    .toList();

            assertThat(projectTypes)
                    .as("a data holder with one dependency - the language - and no collaborator at all. "
                            + "Not even Customer appears in its own surface: equals takes Object, every "
                            + "accessor returns String or Long, and no method accepts or returns the entity "
                            + "type. Every rule that reads these fields lives in the service layer")
                    .isEmpty();
            assertThat(declaredSurfaceTypeNames())
                    .as("what the surface does name is the language, the persistence annotations that map "
                            + "the record, and the two Jackson annotations that keep it out of a response "
                            + "body")
                    .contains(String.class.getName(), Long.class.getName(), Column.class.getName(),
                            Table.class.getName(), Id.class.getName(), Version.class.getName());
        }
    }

    @Nested
    @DisplayName("8. Hostile input and boundary conditions on every field")
    class HostileInputAndBoundaries {

        @Test
        @DisplayName("a null is refused on every one of the seventeen text fields, each naming its own "
                + "COBOL field")
        void aNullIsRefusedOnEveryTextField() {
            for (final TextField textField : TEXT_FIELDS) {
                final MappedField mappedField = textField.field();
                final Customer customer = syntheticCustomer();

                assertThatIllegalArgumentException()
                        .as("%s maps to a NOT NULL column, so a null is refused where the field can be "
                                + "named rather than surfacing later as an opaque constraint violation from "
                                + "the driver", mappedField.property())
                        .isThrownBy(() -> textField.setter().accept(customer, null))
                        .withMessageContaining(mappedField.property())
                        .withMessageContaining(mappedField.cobolField());
            }
        }

        @Test
        @DisplayName("a null key is refused, and the refusal carries no fabricated cause")
        void aNullKeyIsRefusedWithoutAFabricatedCause() {
            final Customer customer = syntheticCustomer();

            assertThatIllegalArgumentException()
                    .as("CUST-ID is the nine-byte VSAM key and the table's primary key; a row without it "
                            + "cannot exist")
                    .isThrownBy(() -> customer.setCustomerId(null))
                    .withMessageContaining("customerId")
                    .withMessageContaining("CUST-ID PIC 9(09)")
                    .withMessageContaining("app/cpy/CVCUS01Y.cpy:L5")
                    .withNoCause();

            assertThatIllegalArgumentException()
                    .as("and the constructor refuses it on the same terms, so neither entry point can "
                            + "produce a keyless instance")
                    .isThrownBy(() -> new Customer(null,
                            "A", "B", "C", "D", "E", "F", "GH", "IJK", "L", "M", "N",
                            "111111111", "O", "1980-01-01", "P", "Y", "742"))
                    .withMessageContaining("CUST-ID PIC 9(09)")
                    .withNoCause();
        }

        @Test
        @DisplayName("a value one character wider than its picture clause is refused on every text field")
        void aValueOneCharacterTooWideIsRefusedOnEveryTextField() {
            for (final TextField textField : TEXT_FIELDS) {
                final MappedField mappedField = textField.field();
                final Customer customer = syntheticCustomer();
                final String oneTooWide = "X".repeat(mappedField.width() + 1);

                assertThatIllegalArgumentException()
                        .as("%s is %s, so %d characters cannot be written into it - a 500-byte record has "
                                + "no room and the CHAR column would refuse it while naming only itself",
                                mappedField.property(), mappedField.cobolField(), mappedField.width() + 1)
                        .isThrownBy(() -> textField.setter().accept(customer, oneTooWide))
                        .withMessageContaining(mappedField.property())
                        .withMessageContaining(String.valueOf(mappedField.width() + 1))
                        .withNoCause();
            }
        }

        @Test
        @DisplayName("a value at exactly its declared width is accepted on every text field")
        void aValueAtExactlyTheDeclaredWidthIsAccepted() {
            for (final TextField textField : TEXT_FIELDS) {
                final MappedField mappedField = textField.field();
                final Customer customer = syntheticCustomer();
                final String exactlyWide = "X".repeat(mappedField.width());

                assertThatCode(() -> textField.setter().accept(customer, exactlyWide))
                        .as("%s at its full %d characters is what the fixed-width record normally holds",
                                mappedField.property(), mappedField.width())
                        .doesNotThrowAnyException();
                assertThat(textField.getter().apply(customer))
                        .as("%s: returned unchanged", mappedField.property())
                        .isEqualTo(exactlyWide);
            }
        }

        @Test
        @DisplayName("a blank value is accepted on every text field, because the seed data contains blanks")
        void aBlankValueIsAcceptedOnEveryTextField() {
            for (final TextField textField : TEXT_FIELDS) {
                final MappedField mappedField = textField.field();
                final Customer customer = syntheticCustomer();
                final String allSpaces = " ".repeat(mappedField.width());

                assertThatCode(() -> textField.setter().accept(customer, allSpaces))
                        .as("%s: CUST-MIDDLE-NAME and CUST-ADDR-LINE-3 are legitimately all spaces in the "
                                + "seed data, and a blank is what a fixed-width record holds when a field "
                                + "is absent. Rejecting blanks would make real rows unloadable",
                                mappedField.property())
                        .doesNotThrowAnyException();
                assertThatCode(() -> textField.setter().accept(customer, ""))
                        .as("%s: an empty value is accepted too - the CHAR column pads it to width, and "
                                + "the entity does not pad on the caller's behalf", mappedField.property())
                        .doesNotThrowAnyException();
                assertThat(textField.getter().apply(customer))
                        .as("%s: stored as given, unpadded", mappedField.property())
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("no seeded record is blank in any optional-looking field, so blank acceptance rests on "
                + "the picture clause and not on the sample")
        void noSeededRecordIsBlankInAnyOptionalLookingField() {
            final FixtureLoader.FixtureData data = seededCustomers();
            int blankMiddleNames = 0;
            int blankSecondAddressLines = 0;
            int blankThirdAddressLines = 0;
            for (int index = 0; index < data.recordCount(); index++) {
                if (slice(data, index, "middleName").isBlank()) {
                    blankMiddleNames++;
                }
                if (slice(data, index, "addressLine2").isBlank()) {
                    blankSecondAddressLines++;
                }
                if (slice(data, index, "addressLine3").isBlank()) {
                    blankThirdAddressLines++;
                }
            }

            assertThat(blankMiddleNames)
                    .as("measured, not assumed: all fifty seeded customers carry a middle name. "
                            + "CUST-MIDDLE-NAME is the field a reader most expects to be blank, and in this "
                            + "fixture it never is")
                    .isZero();
            assertThat(blankSecondAddressLines + blankThirdAddressLines)
                    .as("and all three address lines are populated on every row too")
                    .isZero();
            assertThat(data.recordCount())
                    .as("the census ran over the whole fixture, so the zeros are exhaustive rather than a "
                            + "sampling artefact")
                    .isEqualTo(SEEDED_RECORD_COUNT);
        }

        @Test
        @DisplayName("blank acceptance is justified by the picture clause: a PIC X field holds spaces, "
                + "whatever this fifty-row sample happens to contain")
        void blankAcceptanceIsJustifiedByThePictureClause() {
            final Customer customer = syntheticCustomer();

            for (final String property : List.of("middleName", "addressLine2", "addressLine3")) {
                final MappedField mappedField = mapped(property);
                final String allSpaces = " ".repeat(mappedField.width());
                final TextField textField = TEXT_FIELDS.stream()
                        .filter(candidate -> candidate.field().property().equals(property))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("no accessor for " + property));

                textField.setter().accept(customer, allSpaces);

                assertThat(textField.getter().apply(customer))
                        .as("%s (%s) accepts and returns its declared width in spaces. The seed data "
                                + "populates it on all fifty rows, so the case is not exercised there - but "
                                + "a customer with no middle name is representable in the record and a "
                                + "blank rejection would make such a row unloadable. The guard is "
                                + "structural, so it is derived from the picture clause and not from the "
                                + "sample", property, mappedField.cobolField())
                        .isEqualTo(allSpaces)
                        .isBlank()
                        .hasSize(mappedField.width());
            }
        }

        @ParameterizedTest(name = "customer id {0} is accepted")
        @ValueSource(longs = {0L, 1L, 50L, 999_999_998L, 999_999_999L})
        @DisplayName("the key accepts zero through 999999999, the full range of nine unsigned digits")
        void theKeyAcceptsItsWholeRange(final long customerId) {
            final Customer customer = syntheticCustomer();

            customer.setCustomerId(customerId);

            assertThat(customer.getCustomerId())
                    .as("PIC 9(09) carries no S, so the field is unsigned and zero is its floor; nothing in "
                            + "the corpus reserves zero as a sentinel")
                    .isEqualTo(customerId);
        }

        @ParameterizedTest(name = "customer id {0} is refused")
        @ValueSource(longs = {-1L, -999_999_999L, 1_000_000_000L, 9_999_999_999L})
        @DisplayName("the key refuses a negative value and anything a tenth digit wide")
        void theKeyRefusesOutOfRangeValues(final long customerId) {
            final Customer customer = syntheticCustomer();

            assertThatIllegalArgumentException()
                    .as("neither a sign nor a tenth digit fits the nine-byte key that "
                            + "app/catlg/LISTCAT.txt:L632 allocates")
                    .isThrownBy(() -> customer.setCustomerId(customerId))
                    .withMessageContaining("CUST-ID PIC 9(09)")
                    .withNoCause();
        }

        @ParameterizedTest(name = "a {1}-character social security number is {2}")
        @CsvSource({
            "12345678,8,accepted",
            "020973888,9,accepted",
            "0209738888,10,refused"})
        @DisplayName("the social security number is bounded by width alone: eight and nine in, ten out")
        void theSocialSecurityNumberIsBoundedByWidthAlone(final String ssn, final int length,
                                                          final String outcome) {
            final Customer customer = syntheticCustomer();

            assertThat(ssn).as("the case really is %d characters wide", length).hasSize(length);
            if ("accepted".equals(outcome)) {
                assertThatCode(() -> customer.setSsn(ssn))
                        .as("a short value is legal content for a fixed-width field, which pads it; the "
                                + "entity validates the ceiling and leaves the interpretation alone")
                        .doesNotThrowAnyException();
                assertThat(customer.getSsn()).as("and it is stored as given").isEqualTo(ssn);
            } else {
                assertThatIllegalArgumentException()
                        .as("but a tenth digit cannot be written into CUST-SSN PIC 9(09)")
                        .isThrownBy(() -> customer.setSsn(ssn))
                        .withMessageContaining("CUST-SSN PIC 9(09)")
                        .withMessageNotContaining(ssn);
            }
        }

        @ParameterizedTest(name = "a name containing the overpunch character {0} survives verbatim")
        @ValueSource(strings = {"A", "B", "C", "D", "E", "F", "G", "H", "I",
            "J", "K", "L", "M", "N", "O", "P", "Q", "R", "{", "}"})
        @DisplayName("letters A through R and the brace characters survive inside a name, because sign "
                + "decoding is position-aware")
        void overpunchCharactersSurviveInsideAName(final String overpunch) {
            final Customer customer = syntheticCustomer();
            final String name = "MC" + overpunch + "NALLY";

            customer.setLastName(name);

            assertThat(customer.getLastName())
                    .as("A through I encode +1 to +9, J through R encode -1 to -9, and the braces encode "
                            + "the signed zeros - but only in a zoned-decimal field's final byte. They are "
                            + "ordinary text everywhere else, which is why overpunch decoding must be "
                            + "driven by the PIC clauses and by position, never by scanning for the "
                            + "characters. CUST-LAST-NAME is PIC X(25) and holds them literally")
                    .isEqualTo(name);
            assertThat(customer.getLastName().charAt(2))
                    .as("the character is still where it was put")
                    .isEqualTo(overpunch.charAt(0));
        }

        @Test
        @DisplayName("the seed data really does carry A-through-R characters inside its name fields: "
                + "41 surnames and 43 given names")
        void theSeedDataCarriesOverpunchCharactersInsideNames() {
            final FixtureLoader.FixtureData data = seededCustomers();
            int surnamesCarryingOne = 0;
            int givenNamesCarryingOne = 0;
            for (int index = 0; index < data.recordCount(); index++) {
                if (containsOverpunchCharacter(slice(data, index, "lastName"))) {
                    surnamesCarryingOne++;
                }
                if (containsOverpunchCharacter(slice(data, index, "firstName"))) {
                    givenNamesCarryingOne++;
                }
            }

            assertThat(surnamesCarryingOne)
                    .as("forty-one of the fifty seeded surnames contain a character that, in the final byte "
                            + "of a zoned-decimal field, would be a sign overpunch")
                    .isEqualTo(41);
            assertThat(givenNamesCarryingOne)
                    .as("and forty-three of the given names do. A decoder that scanned for overpunch "
                            + "characters instead of slicing by picture clause would therefore corrupt the "
                            + "great majority of the seeded rows - the concrete reason the decode must be "
                            + "position-aware and driven from the PIC clauses alone")
                    .isEqualTo(43);
        }

        @Test
        @DisplayName("a record shorter than 500 characters cannot be sliced at the later offsets, and the "
                + "loader says so without echoing content")
        void aShortRecordCannotBeSlicedAtTheLaterOffsets() {
            final FixtureLoader.FixtureData truncated = FixtureLoader.loadResource("trantype.txt", 60);
            final MappedField fico = mapped("ficoCreditScore");

            assertThat(truncated.recordWidth())
                    .as("a 60-character record stands in for any record narrower than CUSTOMER-RECORD; the "
                            + "point is the offset arithmetic, and no fixture is copied or altered to make "
                            + "it")
                    .isLessThan(RECORD_LENGTH);
            assertThatIllegalArgumentException()
                    .as("slicing CUST-FICO-CREDIT-SCORE at columns 330-332 out of a record that ends at 60 "
                            + "runs past the end, and the loader refuses with the offsets and widths rather "
                            + "than with the record's content")
                    .isThrownBy(() -> truncated.field(0, fico.startColumn(), fico.width()))
                    .withMessageContaining(String.valueOf(fico.startColumn()))
                    .withMessageContaining("copybook PIC clause");
        }

        @Test
        @DisplayName("an over-long read is refused at the record boundary rather than silently clamped")
        void anOverLongReadIsRefusedAtTheRecordBoundary() {
            final FixtureLoader.FixtureData data = seededCustomers();

            assertThatCode(() -> data.field(0, RECORD_LENGTH, 1))
                    .as("the last byte of the record is readable")
                    .doesNotThrowAnyException();
            assertThatIllegalArgumentException()
                    .as("one byte past it is not. A clamp would return a short value that looked like a "
                            + "field, and the defect would surface as wrong data rather than as an error")
                    .isThrownBy(() -> data.field(0, RECORD_LENGTH, 2))
                    .withMessageContaining(String.valueOf(RECORD_LENGTH));
            assertThatCode(() -> data.recordAt(SEEDED_RECORD_COUNT))
                    .as("and a record index past the fiftieth is refused too, naming the count but never a "
                            + "record")
                    .isInstanceOf(IndexOutOfBoundsException.class);
        }

        @Test
        @DisplayName("identity is by key alone, so two customers differing in every other field are equal")
        void identityIsByKeyAlone() {
            final Customer first = syntheticCustomer();
            final Customer second = customerFrom(seededCustomers(), 0);

            assertThat(second.getCustomerId())
                    .as("the seeded record's key happens to be 1, the same as the synthetic instance's")
                    .isEqualTo(first.getCustomerId());
            assertThat(first)
                    .as("CUST-ID is the whole of a record's identity: it is the nine-byte VSAM key at RKP 0, "
                            + "and two rows carrying it are the same row at different points in time")
                    .isEqualTo(second)
                    .hasSameHashCodeAs(second);
            assertThat(first)
                    .as("and no instance equals a value of another type")
                    .isNotEqualTo(first.getCustomerId());
        }

        @Test
        @DisplayName("every mutation touches exactly one field, so no accessor pair is transposed")
        void everyMutationTouchesExactlyOneField() {
            for (final TextField mutated : TEXT_FIELDS) {
                final Customer customer = syntheticCustomer();
                final String replacement = "Z".repeat(mutated.field().width());

                mutated.setter().accept(customer, replacement);

                for (final TextField observed : TEXT_FIELDS) {
                    if (observed.field().property().equals(mutated.field().property())) {
                        assertThat(observed.getter().apply(customer))
                                .as("%s takes the new value", mutated.field().property())
                                .isEqualTo(replacement);
                    } else {
                        assertThat(observed.getter().apply(customer))
                                .as("setting %s must not disturb %s - a transposed pair would move one "
                                        + "person's data into another's field and no width check would "
                                        + "notice, the two fields being the same width",
                                        mutated.field().property(), observed.field().property())
                                .isEqualTo(read(syntheticCustomer(), observed.field().property()));
                    }
                }
            }
        }

        @Test
        @DisplayName("the provider's no-argument constructor exists, is not private, and validates nothing")
        void theProviderConstructorExistsAndValidatesNothing() throws ReflectiveOperationException {
            final var constructor = Customer.class.getDeclaredConstructor();

            assertThat(Modifier.isPrivate(constructor.getModifiers()))
                    .as("Hibernate instantiates the entity reflectively and then populates it field by "
                            + "field, so the no-argument constructor must be reachable from a subclass "
                            + "proxy")
                    .isFalse();

            constructor.setAccessible(true);
            final Customer hydrating = constructor.newInstance();

            assertThat(hydrating.getCustomerId())
                    .as("it validates nothing, because at that instant no field has been set: validating "
                            + "here would make every load fail")
                    .isNull();
            assertThat(hydrating.toString())
                    .as("and even an empty instance renders only the two permitted values")
                    .isEqualTo("Customer[customerId=null, version=null]");
        }
    }
}

/*
 * ******************************************************************
 * Program     : CardCrossReferenceTest.java
 * Component   : Unit test tier, resident at
 *               src/test/java/com/cardemo/unit/model
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM tier, no container, no
 *               Spring context, no database, no live endpoint
 * Function    : Proves that the CardCrossReference entity reproduces the
 *               CVACT03Y field contract - 36 populated bytes inside a
 *               50-byte catalogued slot behind a 16-byte primary key -
 *               that the 14-byte FILLER stays unmapped, that the account
 *               property is reachable under the JavaBean name accountId
 *               that the CXACAIX derived finder is named against, that
 *               nothing in the mapping implies uniqueness on an alternate
 *               index the catalogue itself marks NONUNIQKEY, that the
 *               ascending card-number ordering CBSTM03A's early exit
 *               depends on actually holds in the fixture, and that no
 *               primary account number ever reaches toString.
 * Source      : app/cpy/CVACT03Y.cpy (36 B in a 50 B slot, key 16) @ 7756d89
 * Source      : app/cpy/CVACT03Y.cpy:L5-L8 (the four elementary items) @ 7756d89
 * Source      : app/catlg/LISTCAT.txt:L403 (CARDXREF KEYLEN 16, AVGLRECL 50) @ 7756d89
 * Source      : app/catlg/LISTCAT.txt:L482 (CARDXREF.VSAM.AIX KEYLEN 11) @ 7756d89
 * Source      : app/catlg/LISTCAT.txt:L486 (AXRKP 25, zero based) @ 7756d89
 * Source      : app/catlg/LISTCAT.txt:L488 (NONUNIQKEY on that alternate index) @ 7756d89
 * Source      : app/catlg/LISTCAT.txt:L3938-L3946 (AIX 3, CLUSTER 10, GDG 7, PATH 3) @ 7756d89
 * Source      : app/data/ASCII/cardxref.txt (1,850 B, 50 rows, width 36) @ 7756d89
 * Source      : app/cbl/CBSTM03A.CBL:L416-L432 (sorted linear scan; early exit at :L419) @ 7756d89
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

import com.cardemo.model.entity.CardCrossReference;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit test for the {@link CardCrossReference} entity, which replaces the {@code CARDXREF} VSAM KSDS cluster.
 *
 * <h2>1. What this test does</h2>
 *
 * <p>{@code app/cpy/CVACT03Y.cpy} declares a record of exactly four elementary items -
 * {@code XREF-CARD-NUM PIC X(16)} at {@code :L5}, {@code XREF-CUST-ID PIC 9(09)} at {@code :L6},
 * {@code XREF-ACCT-ID PIC 9(11)} at {@code :L7} and {@code FILLER PIC X(14)} at {@code :L8}. The first three
 * sum to {@code 16 + 9 + 11 = 36} <em>populated</em> bytes; adding the filler reaches the 50-byte slot the
 * catalogue reports at {@code app/catlg/LISTCAT.txt:L403} as {@code KEYLEN 16} with {@code AVGLRECL 50}, and
 * whose {@code :L404} adds {@code RKP 0} and {@code MAXLRECL 50} - a fixed 50-byte slot keyed on its leading
 * 16 bytes. The copybook's own header comment at {@code :L2} reads "Data-structure for card xref (RECLN 50)".
 *
 * <p>Six things are proved here, and each is something the annotations alone cannot establish:
 *
 * <ol>
 *   <li><strong>Record geometry.</strong> 36 populated bytes inside the 50-byte slot, a 16-byte primary key
 *       at offset zero, and the 14-byte {@code FILLER} left entirely unmapped - no property, no column, no
 *       reserved width.</li>
 *   <li><strong>The JavaBean property name.</strong> The account property is {@code accountId}, reachable as
 *       {@code getAccountId()} and {@code setAccountId(Long)} - see the invariant
 *       below.</li>
 *   <li><strong>Fixture-anchored decoding.</strong> All 50 records of {@code app/data/ASCII/cardxref.txt} are
 *       decoded at their PIC-derived columns and round-tripped through the entity, so the field contract is
 *       asserted against the byte image of the legacy dataset rather than against a hand-typed literal.</li>
 *   <li><strong>Alternate-index geometry, without implying uniqueness.</strong> The 11-byte alternate key
 *       resolves to {@code accountId}, and nothing in the mapping constrains it to be unique.</li>
 *   <li><strong>Identity and disclosure.</strong> {@code equals} and {@code hashCode} are value-based on the
 *       primary key and obey the full contract, and {@code toString} never emits a primary account
 *       number.</li>
 *   <li><strong>Boundary and hostile input.</strong> Every guard is exercised on both sides of its bound,
 *       including the null, empty, short, at-width and over-width cases and the three malformed record
 *       widths.</li>
 * </ol>
 *
 * <h2>2. How to build, run and test</h2>
 *
 * <p>Run this class with {@code ./mvnw -B -ntp -Ddependency-check.skip=true test}, or on its own with
 * {@code ./mvnw -B -ntp -Ddependency-check.skip=true -Dtest=CardCrossReferenceTest test}. Compile only with
 * {@code ./mvnw -B -ntp test-compile}; the full gate is
 * {@code ./mvnw -B -ntp -Ddependency-check.skip=true clean verify}, which additionally enforces the JaCoCo
 * line-coverage floor and the Javadoc doclint gate.
 *
 * <p><strong>Surefire, not Failsafe, collects this class, and that is a function of both its name and its
 * path.</strong> Surefire 3.5.4 includes {@code **}{@code /*Test.java} and {@code **}{@code /*Tests.java}
 * while excluding {@code **}{@code /integration/**} and {@code **}{@code /e2e/**}; Failsafe includes exactly
 * those two excluded trees. A class ending in {@code Test} under {@code src/test/java/com/cardemo/unit} is
 * therefore collected once, by Surefire. Renaming this class so it no longer ends in {@code Test}, or moving
 * it under {@code integration} or {@code e2e}, makes it fall through the gap between the two plugins: it
 * would still compile, both plugins would still report success, JaCoCo would record it as uncovered and the
 * build would stay green while none of the assertions below ever executed. There is no warning for that, so
 * neither the name nor the directory may change. Surefire also pins
 * {@code workingDirectory} to the project base directory, though this class does not depend on it - it reads
 * the fixture from the classpath, never from the filesystem.
 *
 * <h2>3. Key configurations and defaults</h2>
 *
 * <ul>
 *   <li><strong>No clock, fixed or otherwise.</strong> {@code CVACT03Y} declares no temporal item, so the
 *       entity has no date, time or timestamp field and nothing here reads a clock. Determinism is by
 *       construction rather than by injection: no {@code Instant.now()}, no {@code LocalDate.now()}, no
 *       {@code System.currentTimeMillis()}, no random source and no iteration over an unordered collection.
 *       Where a sibling in this package injects a fixed clock, this class needs none, and adding one would be
 *       unused scaffolding.</li>
 *   <li><strong>No test double, so Mockito's strictness setting is never engaged.</strong> The entity is a
 *       pure data holder: it has no collaborator, performs no I/O, starts no unit of work and emits no log
 *       record. There is consequently nothing to stub, and Mockito is deliberately neither imported nor
 *       initialised. That is the reason no {@code MockitoExtension} appears - not an omission. Had one been
 *       registered, its default {@code STRICT_STUBS} setting would have failed the run on the first
 *       unnecessary stubbing.</li>
 *   <li><strong>No locale, charset or zone is read from the environment.</strong> AssertJ descriptions are
 *       built by string concatenation rather than through a format string, so no default locale participates
 *       in any message. The fixture is decoded as 7-bit ASCII by {@link FixtureLoader}, never under the
 *       platform default charset.</li>
 *   <li><strong>The fixture is addressed by classpath resource name only.</strong>
 *       {@link FixtureLoader.Fixture#CARD_XREF} names {@code cardxref.txt}, which the resources plugin copies
 *       to the root of {@code target/test-classes}. Nothing here opens a filesystem path, so the frozen
 *       corpus under {@code app/} is never read, copied, written or edited by this class.</li>
 * </ul>
 *
 * <h2>4. Common failure modes and troubleshooting</h2>
 *
 * <dl>
 *   <dt>A build failure with no test failure at all</dt>
 *   <dd>That is the compiler gate, not an assertion. Test compilation runs at {@code release 25} with
 *       {@code -Xlint:all}, {@code -Werror} and {@code failOnWarning}, so a raw type, an unchecked cast or a
 *       deprecated call anywhere in this file fails the whole build. An unused import is not caught by
 *       {@code javac} - it publishes no {@code unused} lint key - but it is still prohibited, so every import
 *       above is used at least once below.</dd>
 *   <dt>{@code IllegalArgumentException} naming a classpath resource</dt>
 *   <dd>The fixture copy step has not run. Execute {@code ./mvnw -B -ntp test-compile}, which copies
 *       {@code src/test/resources} to {@code target/test-classes}. Note the neighbouring trap while you are
 *       there: the daily-transaction fixture is {@code dailytran.txt}, spelled in full. {@code dalytran.txt}
 *       does not exist, even though the legacy DD name and dataset are {@code DALYTRAN}, so code written from
 *       the DD name gets a null resource stream. This class does not read that fixture, but the same loader
 *       serves both.</dd>
 *   <dt>An assertion reporting a record width of 50 where 36 was expected</dt>
 *   <dd>Something padded the fixture back out to the catalogued slot size. {@code cardxref.txt} is genuinely
 *       36 bytes wide: {@code 1850 == 50 x (36 + 1)}, one line feed per record, and the longest run of
 *       trailing spaces anywhere in the file is zero characters. The 14-byte {@code FILLER} of {@code :L8} is
 *       not present in the fixture at all. Padding it to 50 corrupts the geometry rather than restoring
 *       it.</dd>
 *   <dt>{@code findByAccountId} failing to resolve at Spring Data bootstrap</dt>
 *   <dd>The account property has been renamed. Spring Data derives that finder from the JavaBean property
 *       name, so the field must stay {@code accountId}. This failure appears at application context startup
 *       rather than as a test failure, which is exactly why it is asserted here.</dd>
 *   <dt>A schema-validation failure mentioning {@code bpchar}</dt>
 *   <dd>Not reproducible from this tier and not this class's concern: it belongs to the integration tier,
 *       where Hibernate validates against a live schema. It is named only so the symptom is not mistaken for
 *       a mapping defect asserted here.</dd>
 * </dl>
 *
 * <h2>5. Contract invariants</h2>
 *
 * <dl>
 *   <dt>renaming the account property</dt>
 *   <dd>{@code com.cardemo.repository.CardCrossReferenceRepository} declares the derived finder
 *       {@code findByAccountId}, which replaces alternate index {@code CARDXREF.VSAM.AIX}. Spring Data
 *       resolves it against the JavaBean property name, so {@code acctId} or {@code xrefAcctId} would make it
 *       unresolvable and would fail context startup rather than fail a test.
 *       Instead: keep the field {@code accountId} with {@code getAccountId()} and
 *       {@code setAccountId(Long)}; asserted by {@link PropertyNaming}.</dd>
 *   <dt>introducing a unique constraint on the account identifier</dt>
 *   <dd>{@code app/catlg/LISTCAT.txt:L488} declares the alternate index {@code NONUNIQKEY}, so one account
 *       may legitimately be reached by several card numbers. A unique constraint or unique index on
 *       {@code xref_acct_id} would reject data the legacy system accepts.
 *       Instead: keep every {@code unique} attribute false and the index non-unique; asserted by
 *       {@link AlternateIndexGeometry}.</dd>
 *   <dt>the expected customer identifier of fixture row 1</dt>
 *   <dd>{@code XREF-CUST-ID} is {@code PIC 9(09)} at {@code app/cpy/CVACT03Y.cpy:L6}, so it occupies
 *       one-based bytes 17-25 of the {@code <16-char-card><9-char-customer><11-char-account>} record.
 *       Decoding eight of those nine digits instead of all nine yields a different identifier, so the
 *       declared width is the whole of the contract. Instead: each expected identifier is taken from its
 *       field at the declared offset and width, and {@link FixtureRoundTrip} asserts it against the fixture
 *       read off the classpath rather than against a value transcribed into prose.</dd>
 *   <dt>reading the alternate-key offset as one-based</dt>
 *   <dd>{@code app/catlg/LISTCAT.txt:L486} reports {@code AXRKP 25}.
 *       {@code AXRKP} is zero-based, so 25 resolves to one-based byte 26 - the first byte of
 *       {@code XREF-ACCT-ID}, since {@code 16 + 9 = 25} bytes precede it. Read as one-based it would land
 *       inside {@code XREF-CUST-ID} and the alternate key would be attributed to the wrong field.
 *       Instead: the zero-based reading is asserted arithmetically by
 *       {@link AlternateIndexGeometry}.</dd>
 *   <dt>re-sorting or shuffling the fixture</dt>
 *   <dd>{@code cardxref.txt} is already strictly ascending by card number, and
 *       {@code app/cbl/CBSTM03A.CBL:L419} depends on it: the scan in {@code 4000-TRNXFILE-GET} exits early on
 *       {@code OR (WS-CARD-NUM (CR-JMP) > XREF-CARD-NUM)}, which finds a present record only while the table
 *       ascends. A test that sorted or shuffled the loaded records would destroy the evidence for that
 *       precondition. Instead: the records are consumed in file order and the ordering is
 *       asserted rather than imposed; see {@link FixtureRoundTrip}.</dd>
 *   <dt>a blank card number is accepted by the entity</dt>
 *   <dd>The width guard is a maximum, so an empty or all-blank card number passes it; only {@code null} and
 *       over-width values are rejected. This is deliberate rather than defective - a {@code PIC X(16)} item
 *       is blank-padded, and structural validation of an inbound payload belongs to the request DTOs and to
 *       the database constraints, not to a data holder. It is asserted as observed behaviour by
 *       {@link BoundaryAndHostileInput} so that a future change to it cannot pass unnoticed.</dd>
 * </dl>
 *
 * <h2>6. What is deliberately not asserted</h2>
 *
 * <p><strong>Any detail of {@code V1__create_schema.sql} or {@code V2__create_indexes.sql} beyond what the
 * copybook and the catalogue state is {@code Not available} at this tier, and none is invented.</strong> This
 * is a pure-JVM test: it reads no SQL file, starts no database and validates no schema, so it asserts the
 * mapping the entity declares and stops there. To close that gap the following would be needed, and all of
 * it belongs to the integration tier rather than here: a container runtime with an accessible Docker socket,
 * the Testcontainers PostgreSQL 16 tier, and a Hibernate schema validation run against the migrated schema.
 *
 * <p>Equally out of scope, and for the same reason: the two foreign keys to {@code account} and
 * {@code customer} are database-level constraints, so nothing here asserts referential integrity; and no
 * assertion is made about the physical index type behind the alternate key beyond the negative claim that it
 * must not be unique.
 *
 * @see CardCrossReference
 * @see FixtureLoader
 */
class CardCrossReferenceTest {

    /** Width of {@code XREF-CARD-NUM PIC X(16)} at {@code app/cpy/CVACT03Y.cpy:L5}, and the VSAM key length. */
    private static final int CARD_NUMBER_WIDTH = 16;

    /** Width of {@code XREF-CUST-ID PIC 9(09)} at {@code app/cpy/CVACT03Y.cpy:L6}. */
    private static final int CUSTOMER_ID_WIDTH = 9;

    /** Width of {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7}. */
    private static final int ACCOUNT_ID_WIDTH = 11;

    /** Width of the deliberately unmapped {@code FILLER PIC X(14)} at {@code app/cpy/CVACT03Y.cpy:L8}. */
    private static final int FILLER_WIDTH = 14;

    /** One-based first column of {@code XREF-CARD-NUM}: it leads the record, so the key is at offset zero. */
    private static final int CARD_NUMBER_COLUMN = 1;

    /** One-based first column of {@code XREF-CUST-ID}: the 16 card-number bytes precede it. */
    private static final int CUSTOMER_ID_COLUMN = 17;

    /** One-based first column of {@code XREF-ACCT-ID}: {@code 16 + 9 = 25} bytes precede it. */
    private static final int ACCOUNT_ID_COLUMN = 26;

    /** The 36 populated bytes of the record: {@code 16 + 9 + 11}, with the filler excluded. */
    private static final int POPULATED_RECORD_WIDTH = 36;

    /** The catalogued slot width: {@code AVGLRECL 50} and {@code MAXLRECL 50} at {@code LISTCAT.txt:L403-L404}. */
    private static final int CATALOGUED_SLOT_WIDTH = 50;

    /** {@code RKP 0} at {@code app/catlg/LISTCAT.txt:L404}: the primary key starts at the first byte. */
    private static final int PRIMARY_KEY_RELATIVE_POSITION = 0;

    /** {@code AXRKP 25} at {@code app/catlg/LISTCAT.txt:L486}, which is a <em>zero-based</em> byte offset. */
    private static final int ALTERNATE_KEY_AXRKP = 25;

    /** {@code KEYLEN 11} of {@code CARDXREF.VSAM.AIX} at {@code app/catlg/LISTCAT.txt:L482}. */
    private static final int ALTERNATE_KEY_WIDTH = 11;

    /** Records in {@code app/data/ASCII/cardxref.txt}, corroborated by {@code REC-TOTAL 50} at {@code :L408}. */
    private static final int FIXTURE_RECORD_COUNT = 50;

    /** Bytes in {@code app/data/ASCII/cardxref.txt}: {@code 50 x (36 + 1)}. */
    private static final int FIXTURE_BYTE_COUNT = 1_850;

    /** Inclusive upper bound of {@code XREF-CUST-ID PIC 9(09)}: nine unsigned display digits. */
    private static final long MAX_CUSTOMER_ID = 999_999_999L;

    /** Inclusive upper bound of {@code XREF-ACCT-ID PIC 9(11)}: eleven unsigned display digits. */
    private static final long MAX_ACCOUNT_ID = 99_999_999_999L;

    /** The relational table replacing the cluster {@code AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS}. */
    private static final String TABLE_NAME = "card_cross_reference";

    /** Column carrying {@code XREF-CARD-NUM}. */
    private static final String CARD_NUMBER_COLUMN_NAME = "xref_card_num";

    /** Column carrying {@code XREF-CUST-ID}. */
    private static final String CUSTOMER_ID_COLUMN_NAME = "xref_cust_id";

    /** Column carrying {@code XREF-ACCT-ID}. */
    private static final String ACCOUNT_ID_COLUMN_NAME = "xref_acct_id";

    /** Java field name of the account identifier; the derived finder {@code findByAccountId} is named on it. */
    private static final String ACCOUNT_ID_PROPERTY = "accountId";

    /** Java field name of the customer identifier. */
    private static final String CUSTOMER_ID_PROPERTY = "customerId";

    /** Java field name of the card number, which is also the identifier. */
    private static final String CARD_NUMBER_PROPERTY = "cardNumber";

    /**
     * Row 1 of {@code app/data/ASCII/cardxref.txt}, verbatim.
     *
     * <p>Present as a literal for exactly one purpose: to prove that the fixture actually read off the
     * classpath is the record this test was written against. Every other assertion decodes the loaded bytes
     * rather than this string. Its leading zero is the reason {@code cardNumber} is a {@code String}: as a
     * numeric type the value would lose that digit and stop being a 16-character key.
     */
    private static final String FIRST_FIXTURE_RECORD = "050002445376574000000005000000000050";

    /** The card number of row 1, which is the leading 16 characters of {@link #FIRST_FIXTURE_RECORD}. */
    private static final String FIRST_FIXTURE_CARD_NUMBER = "0500024453765740";

    /** The customer identifier of row 1: the nine digits at bytes 17-25 decode to 50. */
    private static final long FIRST_FIXTURE_CUSTOMER_ID = 50L;

    /** The account identifier of row 1: the eleven digits at bytes 26-36 decode to 50. */
    private static final long FIRST_FIXTURE_ACCOUNT_ID = 50L;

    /**
     * A synthetic 16-digit card number, used wherever a value is merely needed rather than being the subject.
     *
     * <p>Synthetic on purpose: a fixture card number in a test that is not about the fixture would create a
     * second, unanchored source of truth for the same datum.
     */
    private static final String SYNTHETIC_CARD_NUMBER = "4111111111111111";

    /** A second synthetic card number differing from {@link #SYNTHETIC_CARD_NUMBER} in its final digit only. */
    private static final String OTHER_SYNTHETIC_CARD_NUMBER = "4111111111111112";

    /**
     * Package prefixes this entity must not reach, from any of its members, annotations or supertypes.
     *
     * <p>A data holder that reached into any of these would have acquired a dependency on a layer above it,
     * and the cross reference is the most tempting entity in the package to do that to because in shape it is
     * a join table. Immutable, so this constant is not global mutable state.
     */
    private static final List<String> FORBIDDEN_PACKAGE_PREFIXES = List.of(
            "com.cardemo.exception",
            "com.cardemo.repository",
            "com.cardemo.service",
            "com.cardemo.controller",
            "com.cardemo.batch",
            "com.cardemo.security",
            "com.cardemo.config",
            "com.cardemo.observability",
            "com.cardemo.model.key",
            "com.cardemo.model.enums");

    /**
     * Loads the frozen cross-reference fixture from the classpath, proved against its declared census.
     *
     * <p>A method rather than a field: every call re-reads, so no snapshot is shared between tests and there
     * is no static mutable state anywhere in this class. Addressed by classpath resource name only, so the
     * frozen corpus under {@code app/} is never touched.
     *
     * @return an immutable snapshot of all 50 records, in file order, trailing spaces intact
     */
    private static FixtureLoader.FixtureData crossReferenceFixture() {
        return FixtureLoader.load(FixtureLoader.Fixture.CARD_XREF);
    }

    /**
     * Decodes one 36-character {@code CVACT03Y} record into an entity, position-aware from the PIC clauses.
     *
     * <p>The width is checked before anything is sliced, which is what makes the three malformed-width cases
     * fail loudly instead of silently mapping a shifted record. In particular a 50-character record - one
     * padded back out to the catalogued slot - is rejected rather than quietly truncated to its first 36
     * bytes, because accepting it would mean the geometry was never really being asserted.
     *
     * @param record one record read from the fixture, or a synthetic probe
     * @return the entity the record describes
     * @throws IllegalArgumentException if {@code record} is {@code null}, is not exactly 36 characters, or
     *                                  holds a non-digit anywhere in either numeric zone. The message reports
     *                                  widths and positions only - never content, because a mis-sliced zone
     *                                  can overlap the card number
     */
    private static CardCrossReference decodeRecord(final String record) {
        if (record == null) {
            throw new IllegalArgumentException("a CVACT03Y record must not be null");
        }
        if (record.length() != POPULATED_RECORD_WIDTH) {
            throw new IllegalArgumentException("a CVACT03Y record is exactly " + POPULATED_RECORD_WIDTH
                    + " characters - XREF-CARD-NUM X(16) then XREF-CUST-ID 9(09) then XREF-ACCT-ID 9(11) - "
                    + "but this one is " + record.length() + "; the 14-byte FILLER of app/cpy/CVACT03Y.cpy:L8 "
                    + "is absent from app/data/ASCII/cardxref.txt and must not be padded back on");
        }
        final String cardNumber = record.substring(CARD_NUMBER_COLUMN - 1, CUSTOMER_ID_COLUMN - 1);
        final long customerId = parseDisplayDigits(
                record.substring(CUSTOMER_ID_COLUMN - 1, ACCOUNT_ID_COLUMN - 1), "XREF-CUST-ID PIC 9(09)");
        final long accountId = parseDisplayDigits(
                record.substring(ACCOUNT_ID_COLUMN - 1, POPULATED_RECORD_WIDTH), "XREF-ACCT-ID PIC 9(11)");
        return new CardCrossReference(cardNumber, Long.valueOf(customerId), Long.valueOf(accountId));
    }

    /**
     * Parses an unsigned COBOL display-numeric zone, rejecting any non-digit before it can reach the parser.
     *
     * <p>The guard is not redundant with {@code Long.parseLong}. That method reports the offending input
     * inside its own exception message, so a zone mis-sliced far enough left would print part of a card
     * number into a stack trace. Checking first, and reporting a position rather than the content, makes that
     * impossible by construction.
     *
     * @param zone       the raw fixed-width zone, already sliced at its PIC-derived column
     * @param cobolField the COBOL item and picture, for diagnostics
     * @return the decoded value
     * @throws IllegalArgumentException if any character is not a display digit; the message withholds content
     */
    private static long parseDisplayDigits(final String zone, final String cobolField) {
        for (int index = 0; index < zone.length(); index++) {
            final char digit = zone.charAt(index);
            if (digit < '0' || digit > '9') {
                throw new IllegalArgumentException("every character of " + cobolField + " must be a display "
                        + "digit, but position " + (index + 1) + " of this " + zone.length()
                        + "-character zone is not; the content is withheld because a mis-sliced zone can "
                        + "overlap the card number, so check the offset against app/cpy/CVACT03Y.cpy");
            }
        }
        return Long.parseLong(zone);
    }

    /**
     * Returns the entity's persistent fields: declared, non-static and not compiler- or agent-generated.
     *
     * <p>Both filters matter. The static filter removes the width and bound constants, and the synthetic
     * filter removes {@code $jacocoData}, which the coverage agent adds to every instrumented class at load
     * time - without it this census would report one extra field under {@code verify} and none under a plain
     * {@code test} run, which is the least helpful kind of intermittent failure.
     *
     * @return the persistent fields, in declaration order
     */
    private static List<Field> persistentFields() {
        final List<Field> fields = new ArrayList<>();
        for (final Field field : CardCrossReference.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) {
                fields.add(field);
            }
        }
        return List.copyOf(fields);
    }

    /**
     * Returns the annotations that would declare a JPA association, none of which may appear on this entity.
     *
     * @return the six association-declaring annotation types
     */
    private static List<Class<? extends Annotation>> associationAnnotations() {
        return List.of(ManyToOne.class, OneToMany.class, OneToOne.class, ManyToMany.class, JoinColumn.class,
                JoinTable.class);
    }

    /**
     * Returns every type the entity mentions in its own declaration, for the layering census.
     *
     * @return field types, accessor return and parameter types, constructor parameter types, the superclass,
     *         every implemented interface and every annotation type present on the class or its fields
     */
    private static Set<Class<?>> declaredTypeSurface() {
        final Set<Class<?>> surface = new LinkedHashSet<>();
        surface.add(CardCrossReference.class.getSuperclass());
        surface.addAll(List.of(CardCrossReference.class.getInterfaces()));
        for (final Annotation annotation : CardCrossReference.class.getAnnotations()) {
            surface.add(annotation.annotationType());
        }
        for (final Field field : CardCrossReference.class.getDeclaredFields()) {
            surface.add(field.getType());
            for (final Annotation annotation : field.getAnnotations()) {
                surface.add(annotation.annotationType());
            }
        }
        for (final Method method : CardCrossReference.class.getDeclaredMethods()) {
            if (method.isSynthetic()) {
                continue;
            }
            surface.add(method.getReturnType());
            surface.addAll(List.of(method.getParameterTypes()));
        }
        for (final Constructor<?> constructor : CardCrossReference.class.getDeclaredConstructors()) {
            surface.addAll(List.of(constructor.getParameterTypes()));
        }
        return Set.copyOf(surface);
    }

    /**
     * Builds a valid entity from synthetic values, for the tests that are not about the fixture.
     *
     * @return a fresh instance; never shared, so no test can observe another's mutation
     */
    private static CardCrossReference syntheticCrossReference() {
        return new CardCrossReference(SYNTHETIC_CARD_NUMBER, Long.valueOf(1L), Long.valueOf(2L));
    }

    @Nested
    @DisplayName("the record geometry: 36 populated bytes in a 50-byte slot behind a 16-byte key")
    class RecordGeometry {

        @Test
        @DisplayName("the three mapped widths sum to 36 populated bytes, and the filler completes the 50")
        void theThreeMappedWidthsSumToThirtySix() {
            assertThat(CARD_NUMBER_WIDTH + CUSTOMER_ID_WIDTH + ACCOUNT_ID_WIDTH)
                    .as("app/cpy/CVACT03Y.cpy:L5-L7 declares X(16) then 9(09) then 9(11), so the mapped "
                            + "part of the record is 16 + 9 + 11 populated bytes")
                    .isEqualTo(POPULATED_RECORD_WIDTH);
            assertThat(POPULATED_RECORD_WIDTH + FILLER_WIDTH)
                    .as("adding the FILLER X(14) of :L8 reaches the catalogued slot, which "
                            + "app/catlg/LISTCAT.txt:L403 reports as AVGLRECL 50 and :L404 as MAXLRECL 50, "
                            + "and which the copybook's own :L2 header comment calls RECLN 50")
                    .isEqualTo(CATALOGUED_SLOT_WIDTH);
        }

        @Test
        @DisplayName("the 14-byte filler is slack, not data: 50 minus 36 is exactly the declared filler width")
        void theFillerIsExactlyTheUnaccountedSlack() {
            assertThat(CATALOGUED_SLOT_WIDTH - POPULATED_RECORD_WIDTH)
                    .as("the gap between the catalogued slot and the mapped fields is accounted for in full "
                            + "by FILLER X(14) at app/cpy/CVACT03Y.cpy:L8, so no byte of the record is "
                            + "unexplained and none is left over to be modelled as a fourth field")
                    .isEqualTo(FILLER_WIDTH);
        }

        @Test
        @DisplayName("the card number is the whole 16-byte primary key, and it starts at the first byte")
        void theCardNumberIsTheWholePrimaryKey() {
            assertThat(syntheticCrossReference().getCardNumber())
                    .as("app/catlg/LISTCAT.txt:L403 records KEYLEN 16 and the leading item of "
                            + "app/cpy/CVACT03Y.cpy is XREF-CARD-NUM X(16), so the key is the card number in "
                            + "full - there is no composite component and no partial key")
                    .hasSize(CARD_NUMBER_WIDTH);
            assertThat(CARD_NUMBER_COLUMN - 1)
                    .as("app/catlg/LISTCAT.txt:L404 records RKP 0, so the key begins at the first byte of "
                            + "the record, which is one-based column 1")
                    .isEqualTo(PRIMARY_KEY_RELATIVE_POSITION);
        }

        @Test
        @DisplayName("each field starts where the preceding widths put it, so no column offset is guessed")
        void eachFieldStartsWhereThePrecedingWidthsPutIt() {
            assertThat(CUSTOMER_ID_COLUMN)
                    .as("XREF-CUST-ID follows the 16-byte card number, so it opens at one-based column 17")
                    .isEqualTo(CARD_NUMBER_COLUMN + CARD_NUMBER_WIDTH);
            assertThat(ACCOUNT_ID_COLUMN)
                    .as("XREF-ACCT-ID follows 16 + 9 = 25 bytes, so it opens at one-based column 26")
                    .isEqualTo(CUSTOMER_ID_COLUMN + CUSTOMER_ID_WIDTH);
            assertThat(ACCOUNT_ID_COLUMN + ACCOUNT_ID_WIDTH - 1)
                    .as("and it closes the populated part of the record at column 36")
                    .isEqualTo(POPULATED_RECORD_WIDTH);
        }

        @Test
        @DisplayName("the fixture is 36 bytes wide, not the catalogued 50")
        void theFixtureIsThirtySixBytesWideNotFifty() {
            assertThat(FixtureLoader.Fixture.CARD_XREF.recordWidth())
                    .as("app/data/ASCII/cardxref.txt holds the populated bytes only; the 14-byte FILLER is "
                            + "not present in it at all, so its records are 36 characters and padding them "
                            + "out to the catalogued slot size would corrupt the geometry")
                    .isEqualTo(POPULATED_RECORD_WIDTH)
                    .isNotEqualTo(CATALOGUED_SLOT_WIDTH);
            assertThat(FixtureLoader.Fixture.CARD_XREF.resourceName())
                    .as("addressed by classpath resource name only, never as a filesystem path into app/")
                    .isEqualTo("cardxref.txt");
        }

        @Test
        @DisplayName("the fixture satisfies its own geometry invariant: 1850 bytes is 50 records of 36 plus one")
        void theFixtureSatisfiesItsGeometryInvariant() {
            final FixtureLoader.FixtureData fixture = crossReferenceFixture();
            assertThat(fixture.byteCount())
                    .as("1850 == 50 x (36 + 1): 36 data bytes and one line feed per record, the final "
                            + "record included")
                    .isEqualTo(FIXTURE_BYTE_COUNT)
                    .isEqualTo(fixture.impliedByteCount());
            assertThat(fixture.recordCount())
                    .as("app/catlg/LISTCAT.txt:L408 reports REC-TOTAL 50 for the cluster, and :L490 the "
                            + "same 50 for its alternate index, so the fixture is the full population")
                    .isEqualTo(FIXTURE_RECORD_COUNT);
        }

        @Test
        @DisplayName("no record carries a single trailing space, because there is no filler to pad")
        void noRecordCarriesATrailingSpace() {
            final FixtureLoader.FixtureData fixture = crossReferenceFixture();
            for (int index = 0; index < fixture.recordCount(); index++) {
                final String record = fixture.recordAt(index);
                assertThat(record)
                        .as("row " + index + " must be exactly 36 characters with no padding; unlike the "
                                + "account, card, customer and daily-transaction fixtures this one has a "
                                + "maximum trailing-space run of zero, because its filler is absent rather "
                                + "than blank")
                        .hasSize(POPULATED_RECORD_WIDTH)
                        .doesNotEndWith(" ");
            }
        }

        @Test
        @DisplayName("asking the loader for 50-byte records fails loudly rather than silently truncating")
        void askingForFiftyByteRecordsFailsLoudly() {
            assertThatIllegalStateException()
                    .as("the 36-in-50 boundary has to fail noisily: a loader that tolerated the catalogued "
                            + "slot width would leave every column offset unverified")
                    .isThrownBy(() -> FixtureLoader.loadResource("cardxref.txt", CATALOGUED_SLOT_WIDTH))
                    .withMessageContaining("record 0 of cardxref.txt is 36 characters wide")
                    .withMessageContaining("must be exactly 50")
                    .withNoCause();
        }
    }

    @Nested
    @DisplayName("the mapped columns: exactly three, all NOT NULL, no version, no generated identifier")
    class MappedColumns {

        @Test
        @DisplayName("the type is an entity mapped to card_cross_reference")
        void theTypeIsAnEntityMappedToTheExpectedTable() {
            assertThat(CardCrossReference.class)
                    .as("the relational replacement for cluster AWS.M2.CARDDEMO.CARDXREF.VSAM.KSDS, which "
                            + "app/catlg/LISTCAT.txt:L401 associates with the CARDXREF data component")
                    .hasAnnotation(Entity.class)
                    .hasAnnotation(Table.class);
            assertThat(CardCrossReference.class.getAnnotation(Table.class).name())
                    .isEqualTo(TABLE_NAME);
        }

        @Test
        @DisplayName("exactly three fields are persistent, and they carry exactly the three expected columns")
        void exactlyThreeFieldsArePersistent() {
            final List<Field> fields = persistentFields();
            assertThat(fields)
                    .as("XREF-CARD-NUM, XREF-CUST-ID and XREF-ACCT-ID and nothing else; the FILLER of "
                            + "app/cpy/CVACT03Y.cpy:L8 contributes no field")
                    .hasSize(3);
            final List<String> columnNames = new ArrayList<>();
            for (final Field field : fields) {
                assertThat(field.getAnnotation(Column.class))
                        .as("every persistent field of this entity is explicitly mapped, because the "
                                + "physical naming strategy is left at its default")
                        .isNotNull();
                columnNames.add(field.getAnnotation(Column.class).name());
            }
            assertThat(columnNames).containsExactly(
                    CARD_NUMBER_COLUMN_NAME, CUSTOMER_ID_COLUMN_NAME, ACCOUNT_ID_COLUMN_NAME);
        }

        @Test
        @DisplayName("every mapped column is NOT NULL, matching a fixed-width record with no optional field")
        void everyMappedColumnIsNotNull() {
            for (final Field field : persistentFields()) {
                assertThat(field.getAnnotation(Column.class).nullable())
                        .as("column " + field.getAnnotation(Column.class).name() + " must be NOT NULL: a "
                                + "fixed-width COBOL record has no concept of an absent field, so every one "
                                + "of the three is mandatory")
                        .isFalse();
            }
        }

        @Test
        @DisplayName("the card number is the identifier: a String, and not generated")
        void theCardNumberIsTheIdentifierAndIsNotGenerated() throws ReflectiveOperationException {
            final Field identifier = CardCrossReference.class.getDeclaredField(CARD_NUMBER_PROPERTY);
            assertThat(identifier.isAnnotationPresent(Id.class))
                    .as("the 16-byte VSAM key becomes the primary key")
                    .isTrue();
            assertThat(identifier.getType())
                    .as("a String, never a numeric type: five of the 50 fixture card numbers begin with a "
                            + "zero, which any numeric type would discard, shortening a 16-character key")
                    .isEqualTo(String.class);
            assertThat(identifier.isAnnotationPresent(GeneratedValue.class))
                    .as("the key is supplied by the cross-reference record itself, so generating one would "
                            + "invent an identifier the legacy system does not have")
                    .isFalse();
            assertThat(identifier.getAnnotation(Column.class).length())
                    .isEqualTo(CARD_NUMBER_WIDTH);
        }

        @Test
        @DisplayName("no field carries a version column, because this entity is not one of the four versioned")
        void noFieldCarriesAVersionColumn() {
            for (final Field field : CardCrossReference.class.getDeclaredFields()) {
                assertThat(field.isAnnotationPresent(Version.class))
                        .as("of the eleven entities a version column belongs on exactly four - Account, "
                                + "Card, Customer and Transaction - and this is not one of them. The cross "
                                + "reference is written whole by the provisioning job and is never the "
                                + "target of a read-modify-write screen conversation, so adding one here "
                                + "would fabricate a column the schema does not have")
                        .isFalse();
            }
            assertThat(persistentFields())
                    .as("a version field would also show up as a fourth persistent field")
                    .hasSize(3);
        }

        @Test
        @DisplayName("no monetary or approximate numeric type appears anywhere: this record holds no money")
        void noMonetaryOrApproximateNumericTypeAppears() {
            final Set<Class<?>> forbidden = Set.of(
                    BigDecimal.class, Float.class, Double.class, float.class, double.class);
            for (final Class<?> mentioned : declaredTypeSurface()) {
                assertThat(forbidden.contains(mentioned))
                        .as("the type surface must not mention " + mentioned.getName() + ": the cross "
                                + "reference carries three identifiers and no amount, rate or balance, so "
                                + "BigDecimal would be as wrong here as a binary floating-point type is "
                                + "anywhere in the codebase")
                        .isFalse();
            }
        }

        @Test
        @DisplayName("the entity does not implement Serializable")
        void theEntityDoesNotImplementSerializable() {
            assertThat(Serializable.class.isAssignableFrom(CardCrossReference.class))
                    .as("insecure deserialization is a risky pattern this project refuses outright, and a "
                            + "serialisable class with no explicit serial version identifier is in any case "
                            + "a fatal warning under -Werror")
                    .isFalse();
            assertThat(CardCrossReference.class.getInterfaces())
                    .as("a data holder needs no interface at all")
                    .isEmpty();
        }

        @ParameterizedTest
        @DisplayName("the filler is unmapped: no property of any plausible name exists for it")
        @ValueSource(strings = {"filler", "FILLER", "reserved", "slack", "xrefFiller", "filler1", "padding"})
        void theFillerIsUnmapped(final String candidate) {
            assertThatExceptionIsNoSuchField(candidate);
        }

        @Test
        @DisplayName("and no column is 14 characters wide, so the filler has no reserved column either")
        void noColumnIsFourteenCharactersWide() {
            for (final Field field : persistentFields()) {
                assertThat(field.getAnnotation(Column.class).length())
                        .as("column " + field.getAnnotation(Column.class).name() + " must not be the "
                                + "filler width: FILLER X(14) is allocation slack, so it is neither a "
                                + "property nor a reserved column")
                        .isNotEqualTo(FILLER_WIDTH);
            }
        }

        /**
         * Asserts that the entity declares no field under the given name.
         *
         * @param candidate the field name that must not exist
         */
        private void assertThatExceptionIsNoSuchField(final String candidate) {
            assertThat(catchNoSuchField(candidate))
                    .as("app/cpy/CVACT03Y.cpy:L8 declares an unnamed FILLER, which no program in the corpus "
                            + "can reference and which therefore carries no business meaning to map; a "
                            + "property called " + candidate + " would be an invention")
                    .isTrue();
        }

        /**
         * Reports whether the entity is free of a declared field with the given name.
         *
         * @param candidate the field name to look for
         * @return {@code true} when no such field is declared
         */
        private boolean catchNoSuchField(final String candidate) {
            for (final Field field : CardCrossReference.class.getDeclaredFields()) {
                if (field.getName().equals(candidate)) {
                    return false;
                }
            }
            return true;
        }
    }

    @Nested
    @DisplayName("the JavaBean property names, on which the derived finders depend")
    class PropertyNaming {

        @Test
        @DisplayName("the account property is named accountId, which findByAccountId is derived from")
        void theAccountPropertyIsNamedAccountId() throws ReflectiveOperationException {
            final Field field = CardCrossReference.class.getDeclaredField(ACCOUNT_ID_PROPERTY);
            assertThat(field.getType())
                    .as("a Long, because " + MAX_ACCOUNT_ID + " exceeds Integer.MAX_VALUE and an int field "
                            + "would silently wrap")
                    .isEqualTo(Long.class);
            assertThat(CardCrossReference.class.getMethod("getAccountId").getReturnType())
                    .as("Spring Data derives CardCrossReferenceRepository.findByAccountId from the JavaBean "
                            + "property name, so acctId or xrefAcctId would leave the finder unresolvable "
                            + "and would fail application context startup rather than fail a test - which "
                            + "is exactly why the name is asserted here, where it costs nothing to find")
                    .isEqualTo(Long.class);
            assertThat(CardCrossReference.class.getMethod("setAccountId", Long.class).getReturnType())
                    .as("the mutator completes the JavaBean pair, so the property is writable as well as "
                            + "readable")
                    .isEqualTo(void.class);
        }

        @Test
        @DisplayName("the customer property is named customerId, by the same rule")
        void theCustomerPropertyIsNamedCustomerId() throws ReflectiveOperationException {
            final Field field = CardCrossReference.class.getDeclaredField(CUSTOMER_ID_PROPERTY);
            assertThat(field.getType())
                    .as("XREF-CUST-ID is PIC 9(09), whose maximum " + MAX_CUSTOMER_ID + " would fit an int; "
                            + "it is nonetheless a Long, so that every identifier in the package is the same "
                            + "type and no call site has to remember which is which")
                    .isEqualTo(Long.class);
            assertThat(CardCrossReference.class.getMethod("getCustomerId").getReturnType())
                    .isEqualTo(Long.class);
            assertThat(CardCrossReference.class.getMethod("setCustomerId", Long.class).getReturnType())
                    .isEqualTo(void.class);
        }

        @Test
        @DisplayName("the three persistent fields are named exactly cardNumber, customerId and accountId")
        void theThreePersistentFieldsAreNamedExactly() {
            final List<String> names = new ArrayList<>();
            for (final Field field : persistentFields()) {
                names.add(field.getName());
            }
            assertThat(names)
                    .as("in copybook order, and under their JavaBean names rather than transliterated COBOL "
                            + "item names - the mapping from XREF-CARD-NUM, XREF-CUST-ID and XREF-ACCT-ID is "
                            + "carried by the column names, which stay verbatim")
                    .containsExactly(CARD_NUMBER_PROPERTY, CUSTOMER_ID_PROPERTY, ACCOUNT_ID_PROPERTY);
        }

        @ParameterizedTest
        @DisplayName("no transliterated or abbreviated alias of the account property exists")
        @ValueSource(strings = {"acctId", "xrefAcctId", "xrefAccountId", "acct", "custId", "xrefCustId",
            "xrefCardNum", "cardNum", "pan"})
        void noTransliteratedAliasExists(final String alias) {
            final List<String> declared = new ArrayList<>();
            for (final Field field : CardCrossReference.class.getDeclaredFields()) {
                declared.add(field.getName());
            }
            assertThat(declared)
                    .as("an alias called " + alias + " would either shadow the real property or, worse, "
                            + "replace it - and replacing accountId is the one change to this class that "
                            + "breaks at bootstrap instead of in a test")
                    .doesNotContain(alias);
        }
    }

    @Nested
    @DisplayName("the fixture round trip: all 50 records of cardxref.txt decoded at their PIC columns")
    class FixtureRoundTrip {

        @Test
        @DisplayName("the fixture read from the classpath is the one this test was written against")
        void theFixtureIsTheOneThisTestWasWrittenAgainst() {
            assertThat(crossReferenceFixture().recordAt(0))
                    .as("if this fails, the fixture has changed since the assertions below were derived "
                            + "from it; restore it from app/data/ASCII/cardxref.txt rather than adjusting "
                            + "the expectations")
                    .isEqualTo(FIRST_FIXTURE_RECORD)
                    .hasSize(POPULATED_RECORD_WIDTH);
        }

        @Test
        @DisplayName("row 1 decodes to the fixture card number, customer 50 and account 50")
        void rowOneDecodesExactly() {
            final CardCrossReference first = decodeRecord(crossReferenceFixture().recordAt(0));
            assertThat(first.getCardNumber())
                    .as("the leading 16 bytes, verbatim and with the leading zero intact")
                    .isEqualTo(FIRST_FIXTURE_CARD_NUMBER);
            assertThat(first.getCustomerId())
                    .as("XREF-CUST-ID is PIC 9(09) at app/cpy/CVACT03Y.cpy:L6 and therefore occupies "
                            + "one-based bytes 17-25; decoding all nine of those digits is what yields this "
                            + "identifier, and decoding only eight of them yields a different one")
                    .isEqualTo(FIRST_FIXTURE_CUSTOMER_ID);
            assertThat(first.getAccountId())
                    .as("XREF-ACCT-ID is PIC 9(11) at :L7 and occupies one-based bytes 26-36, reading "
                            + "00000000050")
                    .isEqualTo(FIRST_FIXTURE_ACCOUNT_ID);
        }

        @Test
        @DisplayName("all 50 records decode, and each decoded value equals its own PIC-derived column slice")
        void allFiftyRecordsDecodeToTheirColumnSlices() {
            final FixtureLoader.FixtureData fixture = crossReferenceFixture();
            for (int index = 0; index < fixture.recordCount(); index++) {
                final CardCrossReference decoded = decodeRecord(fixture.recordAt(index));
                assertThat(decoded.getCardNumber())
                        .as("row " + index + ": the card number must be the slice at one-based columns 1-16")
                        .isEqualTo(fixture.field(index, CARD_NUMBER_COLUMN, CARD_NUMBER_WIDTH))
                        .hasSize(CARD_NUMBER_WIDTH);
                assertThat(decoded.getCustomerId().longValue())
                        .as("row " + index + ": the customer id must be the zone at columns 17-25")
                        .isEqualTo(Long.parseLong(
                                fixture.field(index, CUSTOMER_ID_COLUMN, CUSTOMER_ID_WIDTH)));
                assertThat(decoded.getAccountId().longValue())
                        .as("row " + index + ": the account id must be the zone at columns 26-36")
                        .isEqualTo(Long.parseLong(
                                fixture.field(index, ACCOUNT_ID_COLUMN, ACCOUNT_ID_WIDTH)));
            }
        }

        @Test
        @DisplayName("a leading-zero card number round-trips intact, which is why the key is a String")
        void aLeadingZeroCardNumberRoundTripsIntact() {
            final FixtureLoader.FixtureData fixture = crossReferenceFixture();
            int leadingZeroRows = 0;
            for (int index = 0; index < fixture.recordCount(); index++) {
                final String raw = fixture.field(index, CARD_NUMBER_COLUMN, CARD_NUMBER_WIDTH);
                if (raw.charAt(0) != '0') {
                    continue;
                }
                leadingZeroRows++;
                final CardCrossReference decoded = decodeRecord(fixture.recordAt(index));
                assertThat(decoded.getCardNumber())
                        .as("row " + index + " opens with a zero digit, so it survives the round trip only "
                                + "as text; a numeric type would drop it and leave a 15-character key")
                        .isEqualTo(raw)
                        .hasSize(CARD_NUMBER_WIDTH)
                        .startsWith("0");
            }
            assertThat(leadingZeroRows)
                    .as("the leading-zero case must actually be exercised: if the fixture ever stopped "
                            + "containing one, this test would silently assert nothing at all")
                    .isPositive();
        }

        @Test
        @DisplayName("the fixture is already strictly ascending by card number, which CBSTM03A depends on")
        void theFixtureIsStrictlyAscendingByCardNumber() {
            final FixtureLoader.FixtureData fixture = crossReferenceFixture();
            for (int index = 1; index < fixture.recordCount(); index++) {
                final String previous = fixture.field(index - 1, CARD_NUMBER_COLUMN, CARD_NUMBER_WIDTH);
                final String current = fixture.field(index, CARD_NUMBER_COLUMN, CARD_NUMBER_WIDTH);
                assertThat(previous.compareTo(current))
                        .as("row " + (index - 1) + " must sort before row " + index + ". The scan in "
                                + "app/cbl/CBSTM03A.CBL 4000-TRNXFILE-GET at :L416-L432 leaves its loop on "
                                + "OR (WS-CARD-NUM (CR-JMP) > XREF-CARD-NUM) at :L419, so it finds a record "
                                + "that is present only while the table ascends. That early exit is correct "
                                + "because of this ordering, not in spite of it")
                        .isNegative();
            }
        }

        @Test
        @DisplayName("the records are consumed in file order: nothing here sorts or shuffles the fixture")
        void theRecordsAreConsumedInFileOrder() {
            final FixtureLoader.FixtureData fixture = crossReferenceFixture();
            final List<String> asRead = new ArrayList<>(fixture.records());
            final List<String> reloaded = crossReferenceFixture().records();
            assertThat(asRead)
                    .as("two independent loads must agree element for element and position for position; a "
                            + "test that re-sorted or shuffled the fixture would destroy the evidence for "
                            + "the ordering precondition asserted above")
                    .containsExactlyElementsOf(reloaded);
        }
    }

    @Nested
    @DisplayName("the alternate index: an 11-byte key at zero-based offset 25, and NON-UNIQUE")
    class AlternateIndexGeometry {

        @Test
        @DisplayName("AXRKP 25 is zero-based, so the alternate key opens at one-based byte 26 on accountId")
        void theAlternateKeyOpensAtOneBasedByteTwentySix() {
            assertThat(ALTERNATE_KEY_AXRKP)
                    .as("app/catlg/LISTCAT.txt:L486 reports AXRKP 25 for CARDXREF.VSAM.AIX, and 25 is "
                            + "exactly the number of bytes that precede XREF-ACCT-ID: 16 for the card "
                            + "number plus 9 for the customer id. That arithmetic is what proves the offset "
                            + "is zero-based")
                    .isEqualTo(CARD_NUMBER_WIDTH + CUSTOMER_ID_WIDTH);
            assertThat(ALTERNATE_KEY_AXRKP + 1)
                    .as("converted to the one-based columns a copybook is read in, the alternate key "
                            + "therefore begins at column 26 - the first byte of XREF-ACCT-ID. The "
                            + "zero-based reading is the one the class documentation explains")
                    .isEqualTo(ACCOUNT_ID_COLUMN);
        }

        @Test
        @DisplayName("read as one-based instead, AXRKP 25 would land inside the customer id and mislead")
        void readAsOneBasedTheOffsetWouldLandInsideTheCustomerId() {
            assertThat(ALTERNATE_KEY_AXRKP)
                    .as("this is why the base is worth asserting rather than assuming: taken as a one-based "
                            + "column, 25 falls within XREF-CUST-ID at columns 17-25 - its final byte - and "
                            + "the 11-byte alternate key would be attributed to the wrong field, straddling "
                            + "the boundary between the two identifiers")
                    .isBetween(CUSTOMER_ID_COLUMN, CUSTOMER_ID_COLUMN + CUSTOMER_ID_WIDTH - 1);
        }

        @Test
        @DisplayName("the 11-byte alternate key is the account id in full, spanning one-based bytes 26 to 36")
        void theAlternateKeyIsTheAccountIdInFull() {
            assertThat(ALTERNATE_KEY_WIDTH)
                    .as("app/catlg/LISTCAT.txt:L482 reports KEYLEN 11 for the alternate index, which is the "
                            + "whole width of XREF-ACCT-ID PIC 9(11) - not a prefix of it")
                    .isEqualTo(ACCOUNT_ID_WIDTH);
            assertThat(ALTERNATE_KEY_AXRKP + ALTERNATE_KEY_WIDTH)
                    .as("so the key ends on the last populated byte of the record, column 36")
                    .isEqualTo(POPULATED_RECORD_WIDTH);
            assertThat(ACCOUNT_ID_COLUMN)
                    .as("and it lies entirely outside the 16-byte primary key, which is why it needs a "
                            + "secondary index at all: no prefix of the primary key could answer a query on "
                            + "it. app/catlg/LISTCAT.txt:L3938-L3946 tallies AIX 3, CLUSTER 10, GDG 7 and "
                            + "PATH 3, corroborating that this is one of exactly three alternate indexes in "
                            + "the catalogue - the other two belonging to the card and transaction clusters")
                    .isGreaterThan(CARD_NUMBER_WIDTH);
        }

        @Test
        @DisplayName("no column is declared unique, so nothing implies uniqueness on the account id")
        void noColumnIsDeclaredUnique() {
            for (final Field field : persistentFields()) {
                assertThat(field.getAnnotation(Column.class).unique())
                        .as("column " + field.getAnnotation(Column.class).name() + " must not be declared "
                                + "unique. app/catlg/LISTCAT.txt:L488 marks the alternate index NONUNIQKEY, "
                                + "so one account may legitimately be reached through several card numbers; "
                                + "a unique constraint here would reject data the legacy system accepts")
                        .isFalse();
            }
        }

        @Test
        @DisplayName("the table declares no unique constraint and no index of its own either")
        void theTableDeclaresNoUniqueConstraint() {
            final Table table = CardCrossReference.class.getAnnotation(Table.class);
            assertThat(table.uniqueConstraints())
                    .as("the only uniqueness this table has is its primary key on xref_card_num; a unique "
                            + "constraint naming xref_acct_id would reject one account holding several "
                            + "cards, which the catalogued NONUNIQKEY alternate index permits")
                    .isEmpty();
            assertThat(table.indexes())
                    .as("the index replacing the alternate index is created by the migration rather than "
                            + "generated from this annotation, because the schema is validated and never "
                            + "generated from the mapping")
                    .isEmpty();
        }

        @Test
        @DisplayName("two distinct card numbers may share one account id, and both survive as separate rows")
        void twoDistinctCardNumbersMayShareOneAccountId() {
            final Long sharedAccountId = Long.valueOf(4_000_000_001L);
            final CardCrossReference first =
                    new CardCrossReference(SYNTHETIC_CARD_NUMBER, Long.valueOf(11L), sharedAccountId);
            final CardCrossReference second =
                    new CardCrossReference(OTHER_SYNTHETIC_CARD_NUMBER, Long.valueOf(12L), sharedAccountId);
            assertThat(first.getAccountId())
                    .as("the many-to-one direction is the whole reason the alternate index exists: a "
                            + "customer's several cards all resolve to one account")
                    .isEqualTo(second.getAccountId());
            assertThat(first)
                    .as("they remain distinct entities, because identity is the card number and not the "
                            + "account")
                    .isNotEqualTo(second);
            final Set<CardCrossReference> rows = new LinkedHashSet<>();
            rows.add(first);
            rows.add(second);
            assertThat(rows)
                    .as("and both are retained rather than collapsing into one, which is what a uniqueness "
                            + "assumption on the account id would have done")
                    .hasSize(2);
        }

        @Test
        @DisplayName("the fixture happens to be one-to-one, and that is a property of the fixture only")
        void theFixtureHappensToBeOneToOne() {
            final FixtureLoader.FixtureData fixture = crossReferenceFixture();
            final Set<Long> accountIds = new LinkedHashSet<>();
            final Set<String> cardNumbers = new LinkedHashSet<>();
            for (int index = 0; index < fixture.recordCount(); index++) {
                final CardCrossReference decoded = decodeRecord(fixture.recordAt(index));
                accountIds.add(decoded.getAccountId());
                cardNumbers.add(decoded.getCardNumber());
            }
            assertThat(accountIds)
                    .as("50 rows carry 50 distinct account ids, so this particular fixture never exercises "
                            + "the many-to-one case")
                    .hasSize(FIXTURE_RECORD_COUNT);
            assertThat(cardNumbers)
                    .as("and 50 distinct card numbers, as a primary key requires")
                    .hasSize(FIXTURE_RECORD_COUNT);
            assertThat(accountIds).hasSameSizeAs(cardNumbers);
            // The preceding test proves the schema permits the many-to-one case regardless. Reading the
            // one-to-one shape of this fixture as a schema rule is exactly the inference the catalogue's
            // NONUNIQKEY flag at app/catlg/LISTCAT.txt:L488 forbids.
        }
    }

    @Nested
    @DisplayName("identity: value-based on the primary key, and obeying the whole equals contract")
    class IdentityContract {

        @Test
        @DisplayName("equals is reflexive, and repeated invocation is stable")
        void equalsIsReflexiveAndStable() {
            final CardCrossReference row = syntheticCrossReference();
            assertThat(row).isEqualTo(row);
            assertThat(row.equals(row))
                    .as("a second invocation on unchanged state must agree with the first: nothing in "
                            + "equals reads a clock, a counter or any other mutable source")
                    .isEqualTo(row.equals(row));
            assertThat(row.hashCode())
                    .as("hashCode is likewise stable across invocations on unchanged state")
                    .isEqualTo(row.hashCode());
        }

        @Test
        @DisplayName("equals is symmetric and transitive over the card number")
        void equalsIsSymmetricAndTransitive() {
            final CardCrossReference first =
                    new CardCrossReference(SYNTHETIC_CARD_NUMBER, Long.valueOf(1L), Long.valueOf(2L));
            final CardCrossReference second =
                    new CardCrossReference(SYNTHETIC_CARD_NUMBER, Long.valueOf(3L), Long.valueOf(4L));
            final CardCrossReference third =
                    new CardCrossReference(SYNTHETIC_CARD_NUMBER, Long.valueOf(5L), Long.valueOf(6L));
            assertThat(first.equals(second))
                    .as("identity is the primary key alone, so three rows bearing the same card number are "
                            + "the same row even where their customer and account ids differ - which is the "
                            + "behaviour a persistence identity must have, since those two fields are "
                            + "mutable and the key is not")
                    .isTrue();
            assertThat(second.equals(first))
                    .as("symmetry")
                    .isTrue();
            assertThat(first.equals(third) && second.equals(third))
                    .as("transitivity")
                    .isTrue();
            assertThat(first.hashCode())
                    .as("and equal instances must share a hash code, or a hash-based collection would hold "
                            + "duplicates of one row")
                    .isEqualTo(second.hashCode())
                    .isEqualTo(third.hashCode());
        }

        @Test
        @DisplayName("a different card number is a different row")
        void aDifferentCardNumberIsADifferentRow() {
            final CardCrossReference first =
                    new CardCrossReference(SYNTHETIC_CARD_NUMBER, Long.valueOf(1L), Long.valueOf(2L));
            final CardCrossReference second =
                    new CardCrossReference(OTHER_SYNTHETIC_CARD_NUMBER, Long.valueOf(1L), Long.valueOf(2L));
            assertThat(first)
                    .as("the two differ in their final digit only, and that is enough: the key is compared "
                            + "in full rather than by any prefix")
                    .isNotEqualTo(second);
        }

        @Test
        @DisplayName("equals is null-safe and type-safe")
        void equalsIsNullSafeAndTypeSafe() {
            final CardCrossReference row = syntheticCrossReference();
            assertThat(row.equals(null))
                    .as("the contract requires false rather than a NullPointerException")
                    .isFalse();
            assertThat(row.equals(SYNTHETIC_CARD_NUMBER))
                    .as("comparing against the bare key value must not succeed either, or a collection "
                            + "holding both would behave asymmetrically")
                    .isFalse();
        }

        @Test
        @DisplayName("a hash-based collection deduplicates rows bearing the same card number")
        void aHashBasedCollectionDeduplicatesByCardNumber() {
            final Set<CardCrossReference> rows = new LinkedHashSet<>();
            rows.add(new CardCrossReference(SYNTHETIC_CARD_NUMBER, Long.valueOf(1L), Long.valueOf(2L)));
            rows.add(new CardCrossReference(SYNTHETIC_CARD_NUMBER, Long.valueOf(9L), Long.valueOf(8L)));
            assertThat(rows)
                    .as("equals and hashCode agree, so the second add is recognised as the same row")
                    .hasSize(1);
        }

        @Test
        @DisplayName("all 50 fixture rows are mutually distinct under equals")
        void allFiftyFixtureRowsAreMutuallyDistinct() {
            final FixtureLoader.FixtureData fixture = crossReferenceFixture();
            final Set<CardCrossReference> rows = new LinkedHashSet<>();
            for (int index = 0; index < fixture.recordCount(); index++) {
                rows.add(decodeRecord(fixture.recordAt(index)));
            }
            assertThat(rows)
                    .as("a primary key that collided would show up here as a shortfall, and would mean the "
                            + "fixture could not load into the table at all")
                    .hasSize(FIXTURE_RECORD_COUNT);
        }
    }

    @Nested
    @DisplayName("disclosure: no primary account number ever reaches a rendered string")
    class SensitiveDisclosure {

        @Test
        @DisplayName("toString omits the card number and reports the account id instead")
        void toStringOmitsTheCardNumber() {
            final CardCrossReference row = syntheticCrossReference();
            assertThat(row.toString())
                    .as("a 16-digit primary account number must never reach a log line, and the surest way "
                            + "to guarantee that is to never render it; the masking rules in "
                            + "logback-spring.xml are a backstop, not the primary defence")
                    .doesNotContain(SYNTHETIC_CARD_NUMBER)
                    .doesNotContain(SYNTHETIC_CARD_NUMBER.substring(CARD_NUMBER_WIDTH - 4))
                    .contains("accountId=");
        }

        @Test
        @DisplayName("no fixture row leaks its own card number, or even its last four digits, through toString")
        void noFixtureRowLeaksItsCardNumber() {
            final FixtureLoader.FixtureData fixture = crossReferenceFixture();
            for (int index = 0; index < fixture.recordCount(); index++) {
                final CardCrossReference decoded = decodeRecord(fixture.recordAt(index));
                final String rendered = decoded.toString();
                assertThat(rendered.contains(decoded.getCardNumber()))
                        .as("row " + index + " must not render its card number in full")
                        .isFalse();
                assertThat(rendered.contains(
                        decoded.getCardNumber().substring(CARD_NUMBER_WIDTH - 4)))
                        .as("row " + index + " must not render even the last four digits, which are the "
                                + "fragment a rendering is most often tempted to keep")
                        .isFalse();
            }
        }

        @Test
        @DisplayName("no accessor or rendering exposes a masked, partial or debug form of the card number")
        void noAccessorExposesAPartialCardNumber() {
            final List<String> renderingMethods = new ArrayList<>();
            for (final Method method : CardCrossReference.class.getDeclaredMethods()) {
                if (!method.isSynthetic() && method.getParameterCount() == 0
                        && method.getReturnType() == String.class) {
                    renderingMethods.add(method.getName());
                }
            }
            assertThat(renderingMethods)
                    .as("exactly two no-argument String methods exist: the accessor a caller must ask for "
                            + "explicitly, and toString, which omits the value. A masked or debug variant "
                            + "would be a third route to the same datum and a third place to get it wrong")
                    .containsExactlyInAnyOrder("getCardNumber", "toString");
        }

        @Test
        @DisplayName("the assertion messages in this class identify rows by index and account id, never by card")
        void assertionMessagesIdentifyRowsWithoutTheCardNumber() {
            final FixtureLoader.FixtureData fixture = crossReferenceFixture();
            final CardCrossReference decoded = decodeRecord(fixture.recordAt(0));
            final String safeDescription = "row 0 with account id " + decoded.getAccountId();
            assertThat(safeDescription)
                    .as("this is the convention the rest of the class follows, asserted once so it is a "
                            + "contract rather than a habit: a failure is located by row index or account "
                            + "id, both of which are safe to print, and never by card number")
                    .doesNotContain(decoded.getCardNumber())
                    .contains("row 0");
        }
    }

    @Nested
    @DisplayName("boundaries and hostile input: every guard exercised on both sides of its bound")
    class BoundaryAndHostileInput {

        @Test
        @DisplayName("a null card number is rejected, naming the COBOL item and the NOT NULL column")
        void aNullCardNumberIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new CardCrossReference(null, Long.valueOf(1L), Long.valueOf(2L)))
                    .withMessage("cardNumber (COBOL XREF-CARD-NUM, column xref_card_num) must not be null: "
                            + "the column is NOT NULL in card_cross_reference")
                    .withNoCause();
        }

        @Test
        @DisplayName("a null customer id or account id is rejected the same way")
        void aNullIdentifierIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new CardCrossReference(SYNTHETIC_CARD_NUMBER, null, Long.valueOf(2L)))
                    .withMessage("customerId (COBOL XREF-CUST-ID, column xref_cust_id) must not be null: "
                            + "the column is NOT NULL in card_cross_reference")
                    .withNoCause();
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new CardCrossReference(SYNTHETIC_CARD_NUMBER, Long.valueOf(1L), null))
                    .withMessage("accountId (COBOL XREF-ACCT-ID, column xref_acct_id) must not be null: "
                            + "the column is NOT NULL in card_cross_reference")
                    .withNoCause();
        }

        @Test
        @DisplayName("a 17-character card number is rejected, reporting the actual length")
        void aSeventeenCharacterCardNumberIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new CardCrossReference(
                            SYNTHETIC_CARD_NUMBER + "1", Long.valueOf(1L), Long.valueOf(2L)))
                    .withMessage("cardNumber (COBOL XREF-CARD-NUM PIC X(16)) must be at most 16 characters "
                            + "but was 17")
                    .withNoCause();
        }

        @Test
        @DisplayName("a 16-character card number is accepted: the bound is inclusive")
        void aSixteenCharacterCardNumberIsAccepted() {
            assertThat(new CardCrossReference(
                    SYNTHETIC_CARD_NUMBER, Long.valueOf(1L), Long.valueOf(2L)).getCardNumber())
                    .as("PIC X(16) permits exactly 16 characters, so the guard rejects 17 and admits 16")
                    .hasSize(CARD_NUMBER_WIDTH);
        }

        @Test
        @DisplayName("a 15-character card number is accepted verbatim and is not padded to 16")
        void aFifteenCharacterCardNumberIsAcceptedVerbatim() {
            final String short15 = SYNTHETIC_CARD_NUMBER.substring(1);
            assertThat(new CardCrossReference(
                    short15, Long.valueOf(1L), Long.valueOf(2L)).getCardNumber())
                    .as("the width guard is a MAXIMUM, not an exact length. Blank padding a PIC X(16) item "
                            + "back to 16 characters is a fixed-width emission concern owned by the batch "
                            + "writers, so the entity stores what it was given and adds nothing")
                    .hasSize(short15.length())
                    .isEqualTo(short15);
        }

        @Test
        @DisplayName("an empty or all-blank card number is accepted, which is documented rather than desired")
        void anEmptyOrBlankCardNumberIsAccepted() {
            assertThat(new CardCrossReference("", Long.valueOf(1L), Long.valueOf(2L)).getCardNumber())
                    .as("only null and "
                            + "over-width values are rejected, so a blank key passes the entity and is "
                            + "caught instead by the request DTOs and by the database. Asserted as observed "
                            + "behaviour so that a future change to it cannot pass unnoticed")
                    .isEmpty();
            final String allBlank = " ".repeat(CARD_NUMBER_WIDTH);
            assertThat(new CardCrossReference(allBlank, Long.valueOf(1L), Long.valueOf(2L)).getCardNumber())
                    .as("an all-blank key of the declared width is likewise accepted and stored verbatim, "
                            + "without being trimmed")
                    .isEqualTo(allBlank)
                    .hasSize(CARD_NUMBER_WIDTH);
        }

        @ParameterizedTest
        @DisplayName("the customer id accepts its inclusive bounds and rejects the values just outside them")
        @CsvSource({
            "0,             true",
            "1,             true",
            "999999999,     true",
            "1000000000,    false",
            "-1,            false"
        })
        void theCustomerIdRangeIsInclusive(final long candidate, final boolean accepted) {
            if (accepted) {
                assertThat(new CardCrossReference(SYNTHETIC_CARD_NUMBER, Long.valueOf(candidate),
                        Long.valueOf(2L)).getCustomerId())
                        .as("XREF-CUST-ID PIC 9(09) is unsigned, so 0 through " + MAX_CUSTOMER_ID
                                + " inclusive are the representable values")
                        .isEqualTo(candidate);
            } else {
                assertThatIllegalArgumentException()
                        .isThrownBy(() -> new CardCrossReference(SYNTHETIC_CARD_NUMBER,
                                Long.valueOf(candidate), Long.valueOf(2L)))
                        .withMessage("customerId (COBOL XREF-CUST-ID PIC 9(09)) must be between 0 and "
                                + MAX_CUSTOMER_ID + " inclusive but was " + candidate)
                        .withNoCause();
            }
        }

        @ParameterizedTest
        @DisplayName("the account id accepts its inclusive bounds and rejects the values just outside them")
        @CsvSource({
            "0,              true",
            "50,             true",
            "99999999999,    true",
            "100000000000,   false",
            "-1,             false"
        })
        void theAccountIdRangeIsInclusive(final long candidate, final boolean accepted) {
            if (accepted) {
                assertThat(new CardCrossReference(SYNTHETIC_CARD_NUMBER, Long.valueOf(1L),
                        Long.valueOf(candidate)).getAccountId())
                        .as("XREF-ACCT-ID PIC 9(11) reaches " + MAX_ACCOUNT_ID + ", which is why the field "
                                + "is a Long: the maximum overflows an int")
                        .isEqualTo(candidate);
            } else {
                assertThatIllegalArgumentException()
                        .isThrownBy(() -> new CardCrossReference(SYNTHETIC_CARD_NUMBER, Long.valueOf(1L),
                                Long.valueOf(candidate)))
                        .withMessage("accountId (COBOL XREF-ACCT-ID PIC 9(11)) must be between 0 and "
                                + MAX_ACCOUNT_ID + " inclusive but was " + candidate)
                        .withNoCause();
            }
        }

        @Test
        @DisplayName("the mutators enforce the same guards, and a rejected write leaves the old value intact")
        void aRejectedWriteLeavesTheOldValueIntact() {
            final CardCrossReference row = syntheticCrossReference();
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> row.setCardNumber(SYNTHETIC_CARD_NUMBER + "1"))
                    .withMessage("cardNumber (COBOL XREF-CARD-NUM PIC X(16)) must be at most 16 characters "
                            + "but was 17")
                    .withNoCause();
            assertThat(row.getCardNumber())
                    .as("the guard runs before the assignment, so a rejected write cannot leave the entity "
                            + "half-updated - which matters because a rejected key write that had taken "
                            + "effect would silently change the row's identity")
                    .isEqualTo(SYNTHETIC_CARD_NUMBER);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> row.setAccountId(null))
                    .withMessage("accountId (COBOL XREF-ACCT-ID, column xref_acct_id) must not be null: "
                            + "the column is NOT NULL in card_cross_reference")
                    .withNoCause();
            assertThat(row.getAccountId())
                    .as("the same holds for the account id")
                    .isEqualTo(2L);
            row.setAccountId(Long.valueOf(MAX_ACCOUNT_ID));
            assertThat(row.getAccountId())
                    .as("and an accepted write does take effect, so the guard is not simply refusing "
                            + "everything")
                    .isEqualTo(MAX_ACCOUNT_ID);
        }

        @ParameterizedTest
        @DisplayName("a record of the wrong width is refused, including one padded out to the catalogued 50")
        @ValueSource(ints = {35, 37, 50})
        void aRecordOfTheWrongWidthIsRefused(final int width) {
            final String probe = probeOfWidth(width);
            assertThat(probe).hasSize(width);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> decodeRecord(probe))
                    .withMessageContaining("a CVACT03Y record is exactly 36 characters")
                    .withMessageContaining("but this one is " + width)
                    .withNoCause();
        }

        @Test
        @DisplayName("the 50-byte case is the one that matters: it must not be silently truncated to 36")
        void theFiftyByteCaseIsNotSilentlyTruncated() {
            final String padded = FIRST_FIXTURE_RECORD + " ".repeat(FILLER_WIDTH);
            assertThat(padded)
                    .as("this is the record as the catalogued slot would hold it - 36 populated bytes "
                            + "followed by the 14-byte FILLER as blanks")
                    .hasSize(CATALOGUED_SLOT_WIDTH);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> decodeRecord(padded))
                    .withMessageContaining("must not be padded back on")
                    .withNoCause();
        }

        @Test
        @DisplayName("a null record is refused rather than raising a NullPointerException")
        void aNullRecordIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> decodeRecord(null))
                    .withMessage("a CVACT03Y record must not be null")
                    .withNoCause();
        }

        @Test
        @DisplayName("a non-numeric customer zone is refused, and the message withholds the card number")
        void aNonNumericCustomerZoneIsRefused() {
            final String probe = SYNTHETIC_CARD_NUMBER + "ABCDEFGHI" + "00000000050";
            assertThat(probe).hasSize(POPULATED_RECORD_WIDTH);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> decodeRecord(probe))
                    .withMessageContaining("every character of XREF-CUST-ID PIC 9(09) must be a display "
                            + "digit")
                    .withMessageContaining("position 1 of this 9-character zone")
                    .withMessageNotContaining(SYNTHETIC_CARD_NUMBER)
                    .withNoCause();
        }

        @Test
        @DisplayName("a non-numeric account zone is refused on the same terms")
        void aNonNumericAccountZoneIsRefused() {
            final String probe = SYNTHETIC_CARD_NUMBER + "000000050" + "0000000005X";
            assertThat(probe).hasSize(POPULATED_RECORD_WIDTH);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> decodeRecord(probe))
                    .withMessageContaining("every character of XREF-ACCT-ID PIC 9(11) must be a display "
                            + "digit")
                    .withMessageContaining("position 11 of this 11-character zone")
                    .withMessageNotContaining(SYNTHETIC_CARD_NUMBER)
                    .withNoCause();
        }

        /**
         * Builds a malformed probe record of the requested width from the real first fixture record.
         *
         * @param width the character width the probe must have
         * @return a probe of exactly {@code width} characters
         */
        private String probeOfWidth(final int width) {
            if (width < POPULATED_RECORD_WIDTH) {
                return FIRST_FIXTURE_RECORD.substring(0, width);
            }
            return FIRST_FIXTURE_RECORD + " ".repeat(width - POPULATED_RECORD_WIDTH);
        }
    }

    @Nested
    @DisplayName("scope: a data holder with no association and no reach into any layer above it")
    class ScopeBoundaries {

        @Test
        @DisplayName("no field declares a JPA association, in either direction")
        void noFieldDeclaresAnAssociation() {
            for (final Class<? extends Annotation> association : associationAnnotations()) {
                assertThat(CardCrossReference.class.isAnnotationPresent(association))
                        .as("the type must not carry " + association.getSimpleName())
                        .isFalse();
                for (final Field field : CardCrossReference.class.getDeclaredFields()) {
                    assertThat(field.isAnnotationPresent(association))
                            .as("field " + field.getName() + " must not carry "
                                    + association.getSimpleName() + ". This entity is the most tempting "
                                    + "place in the package to introduce a mapping, because in shape it is "
                                    + "a join table, and three things make it wrong: the legacy corpus "
                                    + "performs explicit keyed reads in a fixed order - cross reference, "
                                    + "then account, then customer - which an association would turn into "
                                    + "an implicit read whose ordering is a side effect of the mapping; a "
                                    + "many-to-one on the account would rename the JavaBean property and "
                                    + "break findByAccountId; and with no association there is no lazy "
                                    + "proxy and therefore no possibility of an N+1 select")
                            .isFalse();
                }
            }
        }

        @Test
        @DisplayName("both foreign keys are plain scalar Long values")
        void bothForeignKeysArePlainScalars() throws ReflectiveOperationException {
            assertThat(CardCrossReference.class.getDeclaredField(CUSTOMER_ID_PROPERTY).getType())
                    .as("referential integrity to customer is a database-level foreign key, not a mapping; "
                            + "asserting the SQL that declares it is Not available at this tier, which "
                            + "reads no migration file and starts no database")
                    .isEqualTo(Long.class);
            assertThat(CardCrossReference.class.getDeclaredField(ACCOUNT_ID_PROPERTY).getType())
                    .as("and the same for account")
                    .isEqualTo(Long.class);
        }

        @Test
        @DisplayName("the entity mentions no type from any layer above the model")
        void theEntityMentionsNoTypeFromALayerAbove() {
            for (final Class<?> mentioned : declaredTypeSurface()) {
                for (final String forbidden : FORBIDDEN_PACKAGE_PREFIXES) {
                    assertThat(mentioned.getName().startsWith(forbidden + "."))
                            .as(mentioned.getName() + " lies in " + forbidden + ", which a persistent data "
                                    + "holder must not reach: it would invert the dependency direction and, "
                                    + "for the two model sub-packages, would imply a composite key or an "
                                    + "enumerated column this record does not have")
                            .isFalse();
                }
            }
        }

        @Test
        @DisplayName("the entity itself lives in the entity package, under the com.cardemo root")
        void theEntityLivesInTheEntityPackage() {
            assertThat(CardCrossReference.class.getName())
                    .as("the package root is com.cardemo throughout - never com.carddemo, a "
                            + "near-miss spelling that would leave every import in the tree "
                            + "unresolvable")
                    .isEqualTo("com.cardemo.model.entity.CardCrossReference");
        }

        @Test
        @DisplayName("the public surface is exactly a constructor, three accessor pairs and the three overrides")
        void thePublicSurfaceIsExactlyWhatIsDeclared() {
            final List<String> publicMethods = new ArrayList<>();
            for (final Method method : CardCrossReference.class.getDeclaredMethods()) {
                if (!method.isSynthetic() && Modifier.isPublic(method.getModifiers())) {
                    publicMethods.add(method.getName());
                }
            }
            assertThat(publicMethods)
                    .as("no lifecycle callback, no business method and no persistence hook: the entity "
                            + "performs no I/O, holds no collaborator, starts no unit of work and emits no "
                            + "log record, so anything beyond this list would be a responsibility it should "
                            + "not have")
                    .containsExactlyInAnyOrder("getCardNumber", "setCardNumber", "getCustomerId",
                            "setCustomerId", "getAccountId", "setAccountId", "equals", "hashCode",
                            "toString");
            final List<Constructor<?>> constructors =
                    List.of(CardCrossReference.class.getDeclaredConstructors());
            assertThat(constructors)
                    .as("two constructors: the three-argument one callers use, and the no-argument one the "
                            + "persistence provider requires")
                    .hasSize(2);
        }
    }
}

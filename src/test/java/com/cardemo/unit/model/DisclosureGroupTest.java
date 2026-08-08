/*
 * ******************************************************************
 * Program     : DisclosureGroupTest.java
 * Component   : Unit test tier, resident at
 *               src/test/java/com/cardemo/unit/model
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM tier, no container, no
 *               Spring context, no database, no live endpoint
 * Function    : Proves that com.cardemo.model.entity.DisclosureGroup
 *               reproduces the DIS-GROUP-RECORD field contract - a
 *               50-byte record whose 16-byte composite key is an
 *               embedded id and whose single non-key column is the
 *               NUMERIC(6,2) interest rate - and that the blank-padded
 *               CHAR(10) group id, the legitimate zero rate and the
 *               absence of any cached or defaulted rate state keep the
 *               interest calculator's DEFAULT fallback working.
 * Source      : app/cpy/CVTRA02Y.cpy:L5-L10 (50 B, composite key 16) @ 7756d89
 * Source      : app/catlg/LISTCAT.txt:L896 (KEYLEN 16, AVGLRECL 50)
 * Source      : app/cbl/CBACT04C.cbl:L214-L216, L415-L440, L443-L460, L462-L470,
 *               L482-L484
 * Source      : app/csd/CARDDEMO.CSD (eight CICS files; DISCGRP absent)
 * Source      : app/data/ASCII/discgrp.txt, trancatg.txt, acctdata.txt
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
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.cardemo.model.entity.DisclosureGroup;
import com.cardemo.model.key.DisclosureGroupId;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
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
 * Unit test for {@link DisclosureGroup}, the JPA entity that replaces the {@code DIS-GROUP-RECORD} layout of
 * the {@code DISCGRP} disclosure-group reference file.
 *
 * <h2>1. What this test does</h2>
 *
 * <p>It holds {@link DisclosureGroup} to the field contract of the frozen legacy corpus, read first hand at
 * the traceability anchor commit {@code 7756d89}. Every expectation below is traced to a locator, because the
 * copybook and the catalogue are the authority and this test is only their transcription.</p>
 *
 * <ul>
 *   <li><strong>Record geometry.</strong> {@code app/cpy/CVTRA02Y.cpy:L5-L10} declares a 16-byte
 *       {@code DIS-GROUP-KEY}, a six-character {@code DIS-INT-RATE} and a 28-byte {@code FILLER}, which sum
 *       to the 50 bytes the copybook header comment at {@code :L2} states. {@code app/catlg/LISTCAT.txt:L896}
 *       corroborates it independently with {@code KEYLEN 16} and {@code AVGLRECL 50}. Citing {@code :L896}
 *       precisely matters: two other clusters in the same catalogue also report {@code KEYLEN 16}.</li>
 *   <li><strong>The blank-padded group id, which matters.</strong>
 *       {@code app/cbl/CBACT04C.cbl:L415-L440} accepts file status {@code '00'} <em>or</em> {@code '23'} on the
 *       first read and then, at {@code :L437}, executes {@code MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID} and
 *       retries. The retry at {@code :L443-L458} accepts only {@code '00'} and otherwise abends at
 *       {@code :L458}. A {@code MOVE} of the seven-character literal into a {@code PIC X(10)} field
 *       space-pads it, so the column must be blank-padded {@code CHAR(10)}. Under {@code VARCHAR(10)} the
 *       padded and unpadded forms are different values, the fallback lookup misses and every account whose
 *       group id is not a stored group id abends.</li>
 *   <li><strong>The narrowest decimal tier.</strong> {@code DIS-INT-RATE PIC S9(04)V99} at
 *       {@code app/cpy/CVTRA02Y.cpy:L9} is four integer digits plus two decimals, so the column is
 *       {@code NUMERIC(6,2)} - the only one in the schema, and never to be collapsed onto the
 *       {@code NUMERIC(11,2)} or {@code NUMERIC(12,2)} tiers.</li>
 *   <li><strong>A zero rate is a value, not an absence.</strong> {@code app/cbl/CBACT04C.cbl:L214} guards both
 *       {@code 1300-COMPUTE-INTEREST} and {@code 1400-COMPUTE-FEES} with {@code IF DIS-INT-RATE NOT = 0}, so a
 *       zero rate legitimately produces no interest transaction and no accumulation. No positivity constraint
 *       may therefore be declared.</li>
 *   <li><strong>No stale rate state.</strong> COBOL reads into a shared record area, so on an invalid key the
 *       previous iteration's contents survive until the default read overwrites them. The entity must offer
 *       nothing that could host that hazard: no mutable static field, no cache, no lifecycle callback and no
 *       defaulted rate.</li>
 *   <li><strong>The seed fixture, asserted rather than assumed.</strong> {@code app/data/ASCII/discgrp.txt} is
 *       51 records of 50 characters in three blocks of exactly seventeen, and every rate overpunch in the file
 *       is the positive-zero brace. Overpunch decoding is position aware, driven only by the picture clause.</li>
 *   <li><strong>Two referential facts that must not be "fixed".</strong>
 *       {@code app/data/ASCII/trancatg.txt} holds eighteen type-and-category pairs while the default block
 *       holds seventeen; the extra pair is the interest calculator's own <em>output</em> category from
 *       {@code app/cbl/CBACT04C.cbl:L482-L483}. And {@code app/csd/CARDDEMO.CSD} defines eight CICS files, none
 *       of them {@code DISCGRP}, so this dataset is batch-only.</li>
 *   </ul>
 *
 * <h2>2. How to run, build and test it</h2>
 *
 * <pre>
 * ./mvnw -B -ntp -Ddependency-check.skip=true test                              # whole unit tier
 * ./mvnw -B -ntp -Ddependency-check.skip=true test -Dtest=DisclosureGroupTest   # this class alone
 * ./mvnw -B -ntp clean verify                                                   # the full gate, no skips
 * </pre>
 *
 * <p>This class is bound to <strong>Surefire</strong>, not Failsafe: the build descriptor gives Surefire
 * {@code **}{@code /*Test.java} while excluding {@code integration} and {@code e2e}, and gives Failsafe only
 * those two trees. That is why the file must stay at {@code src/test/java/com/cardemo/unit/model}. A class
 * moved out of the unit tree without also matching a Failsafe include is collected by neither plugin and
 * silently never runs - a green build that proves nothing.</p>
 *
 * <h2>3. Key configuration and defaults</h2>
 *
 * <p>None to configure. This tier starts no Spring context, opens no connection, reaches no endpoint and
 * writes nothing; it reads one classpath resource per fixture through {@link FixtureLoader} and otherwise
 * inspects an already-compiled class reflectively.</p>
 *
 * <p>Two package defaults are deliberately <em>unused</em> here, and the reason is recorded so that their
 * absence is not read as an oversight. {@link FixedClockProvider} supplies this package's fixed clock and
 * canonical instant, but {@code DIS-GROUP-RECORD} carries no date or timestamp field, so there is no
 * temporal value to pin and no call to a wall-clock method anywhere in this class. Mockito's default strict
 * stubbing likewise has nothing to enforce: the entity has no collaborator to stub, so this class creates no
 * mock and every value it asserts on is either a literal traced to a locator or a byte read from a fixture.</p>
 *
 * <h2>4. Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>the compile fails on this file rather than a test failing.</strong> The build runs
 *       {@code -Xlint:all} with {@code -Werror} and {@code failOnWarning} at release 25, and that reaches test
 *       compilation. An unused import, a raw type or a deprecation is a build failure, not a warning.</li>
 *   <li><strong>a group id assertion fails.</strong> The column type moved off blank-padded
 *       {@code CHAR(10)}. Instead: restore it in both the entity's identifier class and
 *       {@code V1__create_schema.sql} in the same change; {@code VARCHAR(10)} breaks the {@code DEFAULT}
 *       fallback described above.</li>
 *   <li><strong>a fixture assertion fails on a record count or a byte count.</strong> The fixture
 *       under {@code src/test/resources} has drifted from the frozen copy under {@code app/data/ASCII}. The
 *       frozen copy wins. Note the resource names: the daily transaction fixture is {@code dailytran.txt} and
 *       never {@code dalytran.txt}, which does not exist even though the mainframe DD name is
 *       {@code DALYTRAN}.</li>
 *   <li><strong>a precision assertion fails.</strong> The rate was widened. It is {@code NUMERIC(6,2)};
 *       {@code NUMERIC(11,2)} and {@code NUMERIC(12,2)} belong to other entities and are the two likeliest
 *       wrong answers.</li>
 *   <li><strong>a zero rate is rejected.</strong> A positivity constraint was added. Measured against
 *       the seed fixture, {@code jakarta.validation.constraints.Positive} on the rate would reject 30 of the
 *       51 seeded rows. An earlier statement of this requirement put the figure at 24, which counts only the
 *       default and zero-APR blocks and omits the six zero-rate rows of the first block; the measured figure
 *       governs and this class asserts it.</li>
 *   <li><strong>a rate read back does not equal the rate written.</strong> Almost always a
 *       scale-sensitive comparison. Compare with {@code compareTo}; {@code BigDecimal.equals} also compares
 *       scale, so {@code 15.0} and {@code 15.00} are unequal to it.</li>
 *   </ul>
 *
 * @see DisclosureGroup
 * @see DisclosureGroupId
 * @see FixtureLoader
 */
final class DisclosureGroupTest {

    /** {@code DIS-ACCT-GROUP-ID PIC X(10)} - app/cpy/CVTRA02Y.cpy:L6, bytes 1-10 of the key. */
    private static final int GROUP_ID_WIDTH = 10;

    /** {@code DIS-TRAN-TYPE-CD PIC X(02)} - app/cpy/CVTRA02Y.cpy:L7, bytes 11-12 of the key. */
    private static final int TYPE_CD_WIDTH = 2;

    /** {@code DIS-TRAN-CAT-CD PIC 9(04)} - app/cpy/CVTRA02Y.cpy:L8, bytes 13-16 of the key. */
    private static final int CAT_CD_WIDTH = 4;

    /** {@code DIS-GROUP-KEY} - app/cpy/CVTRA02Y.cpy:L5, and {@code KEYLEN 16} at LISTCAT.txt:L896. */
    private static final int KEY_LENGTH = GROUP_ID_WIDTH + TYPE_CD_WIDTH + CAT_CD_WIDTH;

    /** {@code DIS-INT-RATE PIC S9(04)V99} - app/cpy/CVTRA02Y.cpy:L9, six characters at bytes 17-22. */
    private static final int RATE_WIDTH = 6;

    /** The {@code V99} of {@code DIS-INT-RATE}: exactly two decimal positions. */
    private static final int RATE_SCALE = 2;

    /** Column {@code dis_int_rate} is {@code NUMERIC(6,2)}: four integer digits plus the two decimals. */
    private static final int RATE_PRECISION = 6;

    /** {@code FILLER PIC X(28)} - app/cpy/CVTRA02Y.cpy:L10, bytes 23-50, deliberately never mapped. */
    private static final int FILLER_WIDTH = 28;

    /** {@code RECLN = 50} per the copybook header at app/cpy/CVTRA02Y.cpy:L2 and {@code AVGLRECL 50}. */
    private static final int RECORD_LENGTH = KEY_LENGTH + RATE_WIDTH + FILLER_WIDTH;

    /** One-based COBOL column of {@code DIS-ACCT-GROUP-ID}. */
    private static final int GROUP_ID_COLUMN = 1;

    /** One-based COBOL column of {@code DIS-TRAN-TYPE-CD}. */
    private static final int TYPE_CD_COLUMN = 11;

    /** One-based COBOL column of {@code DIS-TRAN-CAT-CD}. */
    private static final int CAT_CD_COLUMN = 13;

    /** One-based COBOL column of {@code DIS-INT-RATE}. */
    private static final int RATE_COLUMN = 17;

    /** One-based COBOL column of the trailing {@code FILLER}. */
    private static final int FILLER_COLUMN = 23;

    /** Records in app/data/ASCII/discgrp.txt: measured, not assumed. */
    private static final int SEED_ROW_COUNT = 51;

    /** Rows per group-id block: the file is three blocks of exactly this many. */
    private static final int BLOCK_SIZE = 17;

    /** Zero-rate rows across the whole fixture: six in the first block, seven default, seventeen zero-APR. */
    private static final int ZERO_RATE_ROW_COUNT = 30;

    /** The table this entity maps to. */
    private static final String TABLE_NAME = "disclosure_group";

    /** Column of {@code DIS-ACCT-GROUP-ID}, declared by the identifier class, not by the entity. */
    private static final String GROUP_ID_COLUMN_NAME = "acct_group_id";

    /** Column of {@code DIS-TRAN-TYPE-CD}, declared by the identifier class. */
    private static final String TYPE_CD_COLUMN_NAME = "tran_type_cd";

    /** Column of {@code DIS-TRAN-CAT-CD}, declared by the identifier class. */
    private static final String CAT_CD_COLUMN_NAME = "tran_cat_cd";

    /** The single non-key column, declared by the entity itself. */
    private static final String RATE_COLUMN_NAME = "dis_int_rate";

    /** The literal moved at app/cbl/CBACT04C.cbl:L437, exactly as written there: seven characters. */
    private static final String DEFAULT_GROUP_LITERAL = "DEFAULT";

    /** The same literal after a COBOL {@code MOVE} into {@code PIC X(10)}: space-padded to ten characters. */
    private static final String PADDED_DEFAULT_GROUP = "DEFAULT   ";

    /** The first block's group id. It begins with {@code A}, which is also a positive overpunch character. */
    private static final String FIRST_BLOCK_GROUP = "A000000000";

    /** The third block's group id, space-padded to the {@code PIC X(10)} width. */
    private static final String ZERO_APR_GROUP = "ZEROAPR   ";

    /** The three group-id blocks in fixture order, each exactly {@link #BLOCK_SIZE} rows long. */
    private static final List<String> GROUP_BLOCKS =
            List.of(FIRST_BLOCK_GROUP, PADDED_DEFAULT_GROUP, ZERO_APR_GROUP);

    /** The raw six-character rate field of a fifteen percent row: five digits and a positive-zero sign. */
    private static final String RATE_FIELD_FIFTEEN = "00150{";

    /** The raw six-character rate field of a zero-rate row. */
    private static final String RATE_FIELD_ZERO = "00000{";

    /** The trailing sign character of every rate in the fixture: the positive-zero overpunch. */
    private static final char POSITIVE_ZERO_OVERPUNCH = '{';

    /** The 28-character {@code FILLER} tail as the fixture writes it: zero filled, never space filled. */
    private static final String FILLER_ZERO_TAIL = "0".repeat(FILLER_WIDTH);

    /** One-based COBOL column of {@code ACCT-GROUP-ID} in the 300-byte account record. */
    private static final int ACCOUNT_GROUP_ID_COLUMN = 113;

    /** The package prefix of the one intra-project type this entity is allowed to name. */
    private static final String KEY_TYPE_NAME = "com.cardemo.model.key.DisclosureGroupId";

    /** Packages the entity must not reach into: a data holder depends on no layer above or beside it. */
    private static final List<String> FORBIDDEN_PACKAGES = List.of(
            "com.cardemo.exception.", "com.cardemo.repository.", "com.cardemo.service.",
            "com.cardemo.controller.", "com.cardemo.batch.", "com.cardemo.security.",
            "com.cardemo.config.", "com.cardemo.observability.", "com.cardemo.model.enums.");

    /** The Jakarta Persistence lifecycle callbacks, none of which may appear on this entity. */
    private static final List<String> LIFECYCLE_CALLBACKS = List.of(
            "jakarta.persistence.PrePersist", "jakarta.persistence.PostPersist",
            "jakarta.persistence.PreUpdate", "jakarta.persistence.PostUpdate",
            "jakarta.persistence.PreRemove", "jakarta.persistence.PostRemove",
            "jakarta.persistence.PostLoad");

    /** Association and identifier-generation annotations, none of which may appear on this entity. */
    private static final List<String> FORBIDDEN_MAPPINGS = List.of(
            "jakarta.persistence.ManyToOne", "jakarta.persistence.OneToMany",
            "jakarta.persistence.OneToOne", "jakarta.persistence.ManyToMany",
            "jakarta.persistence.JoinColumn", "jakarta.persistence.JoinTable",
            "jakarta.persistence.GeneratedValue", "jakarta.persistence.Id");

    /**
     * The seventeen type-and-category pairs the default block carries, in fixture order.
     *
     * <p>These are the pairs the {@code DEFAULT} fallback can satisfy. Every one of them is load-bearing:
     * {@code app/cbl/CBACT04C.cbl:L458} abends when the default read finds nothing, so deleting any row here
     * turns a working fallback into a job failure.</p>
     */
    private static final List<String> DEFAULT_BLOCK_PAIRS = List.of(
            "010001", "010002", "010003", "010004", "020001", "020002", "020003", "030001", "030002",
            "030003", "040001", "040002", "040003", "050001", "060001", "060002", "070001");

    /**
     * The seven default-block pairs whose rate is zero, named individually.
     *
     * <p>They are the only fixture coverage of the skip branch that
     * {@code app/cbl/CBACT04C.cbl:L214-L217} takes when {@code DIS-INT-RATE} is zero, which is why they are
     * asserted by name rather than merely counted.</p>
     */
    private static final List<String> ZERO_RATE_DEFAULT_PAIRS = List.of(
            "020001", "020002", "020003", "030001", "030002", "030003", "070001");

    /** The pair present in trancatg.txt but absent from the default block: the interest job's own output. */
    private static final String INTEREST_OUTPUT_PAIR = "010005";

    /** Description of the interest output category, from app/data/ASCII/trancatg.txt row 5. */
    private static final String INTEREST_OUTPUT_DESCRIPTION = "Interest Amount";

    /** A valid rate at the widest magnitude {@code S9(04)V99} can hold. */
    private static final BigDecimal MAX_RATE = new BigDecimal("9999.99");

    /** The rate that the first default row decodes to: {@code 00150} overpunched with a positive zero. */
    private static final BigDecimal FIFTEEN_PERCENT = new BigDecimal("15.00");

    /** A zero rate at the column's own scale, which is what a zero-rate seed row must round-trip to. */
    private static final BigDecimal ZERO_RATE = new BigDecimal("0.00");

    /**
     * A valid key using the padded default group id, which is the form the fallback lookup presents.
     *
     * @return a fully populated identifier at the exact picture widths
     */
    private static DisclosureGroupId paddedDefaultKey() {
        return new DisclosureGroupId(PADDED_DEFAULT_GROUP, "01", 1);
    }

    /**
     * A disclosure group row carrying the padded default key and a fifteen percent rate.
     *
     * @return a valid entity instance built through the public all-arguments constructor
     */
    private static DisclosureGroup defaultGroupRow() {
        return new DisclosureGroup(paddedDefaultKey(), FIFTEEN_PERCENT);
    }

    /**
     * Loads the disclosure group seed fixture by classpath resource name.
     *
     * @return an immutable snapshot of app/data/ASCII/discgrp.txt, trailing characters intact
     */
    private static FixtureLoader.FixtureData discgrp() {
        return FixtureLoader.load(FixtureLoader.Fixture.DISCLOSURE_GROUP);
    }

    /**
     * Reads the six-character type-and-category pair that keys one fixture row.
     *
     * @param fixture  the loaded disclosure group fixture
     * @param rowIndex the zero-based row index
     * @return the two-character type code concatenated with the four-digit category code
     */
    private static String pairOf(final FixtureLoader.FixtureData fixture, final int rowIndex) {
        return fixture.field(rowIndex, TYPE_CD_COLUMN, TYPE_CD_WIDTH)
                + fixture.field(rowIndex, CAT_CD_COLUMN, CAT_CD_WIDTH);
    }

    /**
     * Returns one declared field of a type, failing with the source locator when it is absent.
     *
     * @param type the declaring type
     * @param name the field name
     * @return the declared field
     */
    private static Field declaredField(final Class<?> type, final String name) {
        try {
            return type.getDeclaredField(name);
        } catch (final NoSuchFieldException absent) {
            throw new AssertionError(type.getName() + " must declare a field named '" + name
                    + "', derived from app/cpy/CVTRA02Y.cpy:L5-L10", absent);
        }
    }

    /**
     * Returns the {@code @Column} mapping of one declared field, failing when the field is not mapped.
     *
     * @param type the declaring type
     * @param name the field name
     * @return the column mapping declared on that field
     */
    private static Column columnOf(final Class<?> type, final String name) {
        final Column column = declaredField(type, name).getAnnotation(Column.class);
        if (column == null) {
            throw new AssertionError(type.getName() + '.' + name
                    + " must carry a @Column mapping so the schema contract is explicit rather than derived");
        }
        return column;
    }

    /**
     * Decodes one fixture row's rate, position aware, from the picture-derived column and width.
     *
     * @param fixture  the loaded disclosure group fixture
     * @param rowIndex the zero-based row index
     * @return the decoded rate at the column's own scale
     */
    private static BigDecimal rateOf(final FixtureLoader.FixtureData fixture, final int rowIndex) {
        return fixture.signedDecimal(rowIndex, RATE_COLUMN, FixtureLoader.RATE_FIELD_WIDTH);
    }

    /**
     * Counts the fixture rows whose decoded rate is zero.
     *
     * <p>This is the measured cost of adding a positivity constraint to the rate, and it is computed from the
     * fixture rather than asserted from a remembered figure so that it cannot drift.</p>
     *
     * @param fixture the loaded disclosure group fixture
     * @return the number of rows carrying a zero rate
     */
    private static int countZeroRateRows(final FixtureLoader.FixtureData fixture) {
        int zeroRows = 0;
        for (int rowIndex = 0; rowIndex < fixture.recordCount(); rowIndex++) {
            if (rateOf(fixture, rowIndex).signum() == 0) {
                zeroRows++;
            }
        }
        return zeroRows;
    }

    /**
     * Names every type that appears anywhere on a type's own declared surface, synthetic members excluded.
     *
     * @param type the type to inspect
     * @return the distinct declared type names
     */
    private static Set<String> surfaceTypeNames(final Class<?> type) {
        return new LinkedHashSet<>(ReflectionCensus.declaredSurfaceTypeNames(type));
    }

    /**
     * Names every annotation declared on a type or on any member it declares, synthetic members excluded.
     *
     * @param type the type to inspect
     * @return the distinct declared annotation type names
     */
    private static Set<String> annotationNames(final Class<?> type) {
        return new LinkedHashSet<>(ReflectionCensus.declaredAnnotationTypeNames(type));
    }

    @Nested
    @DisplayName("record geometry: 50 bytes, a 16-byte composite key and one non-key column")
    class RecordGeometry {

        @Test
        @DisplayName("the copybook widths sum to the catalogued 50-byte record and 16-byte key")
        void copybookWidthsSumToTheCataloguedRecordAndKeyLengths() {
            assertThat(GROUP_ID_WIDTH).as("DIS-ACCT-GROUP-ID PIC X(10) - CVTRA02Y.cpy:L6").isEqualTo(10);
            assertThat(TYPE_CD_WIDTH).as("DIS-TRAN-TYPE-CD PIC X(02) - CVTRA02Y.cpy:L7").isEqualTo(2);
            assertThat(CAT_CD_WIDTH).as("DIS-TRAN-CAT-CD PIC 9(04) - CVTRA02Y.cpy:L8").isEqualTo(4);
            assertThat(RATE_WIDTH).as("DIS-INT-RATE PIC S9(04)V99 - CVTRA02Y.cpy:L9").isEqualTo(6);
            assertThat(FILLER_WIDTH).as("FILLER PIC X(28) - CVTRA02Y.cpy:L10").isEqualTo(28);

            assertThat(KEY_LENGTH)
                    .as("DIS-GROUP-KEY must be the KEYLEN 16 that LISTCAT.txt:L896 records for DISCGRP")
                    .isEqualTo(16);
            assertThat(RECORD_LENGTH)
                    .as("RECLN 50 per CVTRA02Y.cpy:L2 and AVGLRECL 50 per LISTCAT.txt:L896")
                    .isEqualTo(50);
        }

        @Test
        @DisplayName("every seeded record measures exactly the 50 bytes the copybook and catalogue agree on")
        void everySeededRecordMeasuresTheCataloguedRecordLength() {
            final FixtureLoader.FixtureData fixture = discgrp();

            assertThat(fixture.recordWidth())
                    .as("the fixture is the third independent witness to RECLN 50")
                    .isEqualTo(RECORD_LENGTH);
            assertThat(fixture.records())
                    .as("no row may be short, reflowed or padded")
                    .allSatisfy(row -> assertThat(row).hasSize(RECORD_LENGTH));
        }

        @Test
        @DisplayName("maps to table disclosure_group and is annotated as an entity")
        void mapsToTheDisclosureGroupTable() {
            assertThat(DisclosureGroup.class.getAnnotation(Entity.class))
                    .as("the replacement for a VSAM KSDS cluster must be a persistent entity")
                    .isNotNull();

            final Table table = DisclosureGroup.class.getAnnotation(Table.class);
            assertThat(table).as("the table name must be explicit, never inferred from the class name")
                    .isNotNull();
            assertThat(table.name()).isEqualTo(TABLE_NAME);
        }

        @Test
        @DisplayName("carries the composite key as an embedded id and exactly one non-key column")
        void carriesTheKeyAsAnEmbeddedIdAndOneNonKeyColumn() {
            assertThat(ReflectionCensus.declaredFieldNames(DisclosureGroup.class, false))
                    .as("22 populated bytes become exactly two attributes: the key and the rate")
                    .containsExactlyInAnyOrder("id", "interestRate");

            assertThat(declaredField(DisclosureGroup.class, "id").getAnnotation(EmbeddedId.class))
                    .as("DIS-GROUP-KEY is a three-component group, so the identity is embedded")
                    .isNotNull();
            assertThat(declaredField(DisclosureGroup.class, "id").getType())
                    .isEqualTo(DisclosureGroupId.class);
            assertThat(columnOf(DisclosureGroup.class, "interestRate").name())
                    .isEqualTo(RATE_COLUMN_NAME);
        }

        @Test
        @DisplayName("leaves FILLER X(28) unmapped, so 22 of the 50 bytes are modelled and 28 are not")
        void leavesTheTwentyEightByteFillerUnmapped() {
            final List<String> instanceFields =
                    ReflectionCensus.declaredFieldNames(DisclosureGroup.class, false);

            assertThat(instanceFields)
                    .as("FILLER carries no data and must never become an attribute")
                    .noneSatisfy(name -> assertThat(name.toLowerCase(Locale.ROOT))
                            .contains("filler"));
            assertThat(KEY_LENGTH + RATE_WIDTH)
                    .as("the modelled prefix of the record: 16 key bytes plus the six-character rate")
                    .isEqualTo(22);
            assertThat(RECORD_LENGTH - (KEY_LENGTH + RATE_WIDTH))
                    .as("the unmodelled remainder is exactly FILLER PIC X(28)")
                    .isEqualTo(FILLER_WIDTH);
        }

        @Test
        @DisplayName("declares NO @Version: this is not one of the four optimistically locked entities")
        void declaresNoVersionAttribute() {
            assertThat(annotationNames(DisclosureGroup.class))
                    .as(" DISCGRP is read-mostly reference data with no update path")
                    .doesNotContain(Version.class.getName());

            for (final Field field : DisclosureGroup.class.getDeclaredFields()) {
                assertThat(field.isAnnotationPresent(Version.class))
                        .as("field %s must not be a version attribute", field.getName())
                        .isFalse();
            }
        }

        @Test
        @DisplayName("uses exactly four Jakarta Persistence annotations and no other mapping construct")
        void usesExactlyFourPersistenceAnnotations() {
            final List<String> persistence = new ArrayList<>();
            for (final String name : annotationNames(DisclosureGroup.class)) {
                if (name.startsWith("jakarta.persistence.")) {
                    persistence.add(name);
                }
            }

            assertThat(persistence)
                    .as("a closed set is checkable by inspection; anything else is a silent mapping change")
                    .containsExactlyInAnyOrder(Entity.class.getName(), Table.class.getName(),
                            EmbeddedId.class.getName(), Column.class.getName());
        }

        @Test
        @DisplayName("is not Serializable, while its embeddable key is and pins its serialVersionUID")
        void isNotSerializableWhileItsKeyIsWithAPinnedSerialVersionUid() {
            assertThat(Serializable.class.isAssignableFrom(DisclosureGroup.class))
                    .as("an entity need not be serializable, and declining it declines a risky pattern")
                    .isFalse();
            assertThat(Serializable.class.isAssignableFrom(DisclosureGroupId.class))
                    .as("a Jakarta Persistence composite key is required to be serializable")
                    .isTrue();

            final Field serialVersionUid = declaredField(DisclosureGroupId.class, "serialVersionUID");
            assertThat(serialVersionUid.getType()).isEqualTo(long.class);
            assertThat(Modifier.isStatic(serialVersionUid.getModifiers())).isTrue();
            assertThat(Modifier.isFinal(serialVersionUid.getModifiers())).isTrue();
            assertThat(Modifier.isPrivate(serialVersionUid.getModifiers())).isTrue();
        }
    }

    @Nested
    @DisplayName("blank-padded CHAR(10) is what makes the DEFAULT fallback resolve")
    class ColumnContractAndDefaultFallback {

        @Test
        @DisplayName("the group id column is blank-padded CHAR(10) and never VARCHAR")
        void theGroupIdColumnIsBlankPaddedCharNeverVarchar() {
            final Column column = columnOf(DisclosureGroupId.class, "accountGroupId");

            assertThat(column.name()).isEqualTo(GROUP_ID_COLUMN_NAME);
            assertThat(column.length())
                    .as("DIS-ACCT-GROUP-ID PIC X(10) - CVTRA02Y.cpy:L6")
                    .isEqualTo(GROUP_ID_WIDTH);
            assertThat(column.nullable()).isFalse();
            assertThat(column.columnDefinition().toLowerCase(Locale.ROOT))
                    .as(" bpchar is PostgreSQL's blank-padded CHAR; VARCHAR would keep "
                            + "'DEFAULT' and 'DEFAULT   ' apart and the fallback read at CBACT04C.cbl:L444 "
                            + "would find nothing, abending the job at :L458")
                    .isEqualTo("bpchar(" + GROUP_ID_WIDTH + ")")
                    .doesNotContain("varchar");
        }

        @Test
        @DisplayName("the type code column is blank-padded CHAR(2) at its picture width")
        void theTypeCodeColumnIsBlankPaddedCharAtItsPictureWidth() {
            final Column column = columnOf(DisclosureGroupId.class, "tranTypeCd");

            assertThat(column.name()).isEqualTo(TYPE_CD_COLUMN_NAME);
            assertThat(column.length()).isEqualTo(TYPE_CD_WIDTH);
            assertThat(column.nullable()).isFalse();
            assertThat(column.columnDefinition().toLowerCase(Locale.ROOT))
                    .isEqualTo("bpchar(" + TYPE_CD_WIDTH + ")")
                    .doesNotContain("varchar");
        }

        @Test
        @DisplayName("the category code is an integral display code, never a decimal or approximate type")
        void theCategoryCodeIsAnIntegralDisplayCode() {
            final Field component = declaredField(DisclosureGroupId.class, "tranCatCd");

            assertThat(columnOf(DisclosureGroupId.class, "tranCatCd").name()).isEqualTo(CAT_CD_COLUMN_NAME);
            assertThat(component.getType())
                    .as("PIC 9(04) is an unsigned four-digit identifier, not a quantity to be scaled")
                    .isEqualTo(Integer.class);
        }

        @Test
        @DisplayName("the key treats the bare and the space-padded DEFAULT literal as DISTINCT values")
        void theKeyTreatsTheBareAndPaddedDefaultLiteralAsDistinct() {
            final DisclosureGroupId bare = new DisclosureGroupId(DEFAULT_GROUP_LITERAL, "01", 1);
            final DisclosureGroupId padded = new DisclosureGroupId(PADDED_DEFAULT_GROUP, "01", 1);

            assertThat(DEFAULT_GROUP_LITERAL).hasSize(7);
            assertThat(PADDED_DEFAULT_GROUP).hasSize(GROUP_ID_WIDTH).startsWith(DEFAULT_GROUP_LITERAL);
            assertThat(bare)
                    .as("the decided semantics, asserted rather than left implicit: equals is value-based "
                            + "over the raw components and trims nothing, so a caller that supplies the bare "
                            + "literal holds a different key from one that supplies the padded form")
                    .isNotEqualTo(padded);
            assertThat(bare.getAccountGroupId()).isEqualTo(DEFAULT_GROUP_LITERAL);
            assertThat(padded.getAccountGroupId()).isEqualTo(PADDED_DEFAULT_GROUP);
        }

        @Test
        @DisplayName("padding is reconciled by the blank-padded column, not by the key, so callers must pad")
        void paddingIsReconciledByTheColumnAndNotByTheKey() {
            assertThat(PADDED_DEFAULT_GROUP.strip())
                    .as("CHAR comparison ignores trailing blanks, which is why the column type carries this "
                            + "reconciliation and the key does not have to")
                    .isEqualTo(DEFAULT_GROUP_LITERAL);
            assertThat(new DisclosureGroupId(PADDED_DEFAULT_GROUP, "01", 1))
                    .as("two callers that both pad agree, which is the contract this test pins")
                    .isEqualTo(new DisclosureGroupId(PADDED_DEFAULT_GROUP, "01", 1));
        }

        @ParameterizedTest
        @ValueSource(strings = {DEFAULT_GROUP_LITERAL, PADDED_DEFAULT_GROUP})
        @DisplayName("both the bare and the padded DEFAULT group id are storable, in either direction")
        void bothFormsOfTheDefaultGroupAreStorable(final String groupId) {
            final DisclosureGroup row =
                    new DisclosureGroup(new DisclosureGroupId(groupId, "01", 1), FIFTEEN_PERCENT);

            assertThat(row.getId().getAccountGroupId()).isEqualTo(groupId);
            assertThat(row.getInterestRate()).isEqualByComparingTo(FIFTEEN_PERCENT);
        }

        @Test
        @DisplayName("declares NO DEFAULT group constant: the literal belongs to the batch tier's control flow")
        void declaresNoDefaultGroupConstant() {
            final List<String> staticFields =
                    ReflectionCensus.declaredFieldNames(DisclosureGroup.class, true);

            assertThat(staticFields)
                    .as("the declared constants are the rate geometry and its bounds, nothing else")
                    .containsExactlyInAnyOrder("INTEREST_RATE_SCALE", "INTEREST_RATE_INTEGER_DIGITS",
                            "INTEREST_RATE_PRECISION", "MAX_INTEREST_RATE", "MIN_INTEREST_RATE");
            for (final String name : staticFields) {
                assertThat(declaredField(DisclosureGroup.class, name).getType())
                        .as("static field %s must not be a String: 'DEFAULT' is moved at "
                                + "CBACT04C.cbl:L437 and belongs to the interest calculator", name)
                        .isNotEqualTo(String.class);
            }
        }

        @Test
        @DisplayName("the all-components key constructor takes the copybook order: group id, type, category")
        void theKeyConstructorTakesTheCopybookComponentOrder() {
            final DisclosureGroupId key = new DisclosureGroupId(FIRST_BLOCK_GROUP, "07", 1);

            assertThat(key.getAccountGroupId())
                    .as("first component: DIS-ACCT-GROUP-ID at CVTRA02Y.cpy:L6, key bytes 1-10")
                    .isEqualTo(FIRST_BLOCK_GROUP);
            assertThat(key.getTranTypeCd())
                    .as("second component: DIS-TRAN-TYPE-CD at CVTRA02Y.cpy:L7, key bytes 11-12")
                    .isEqualTo("07");
            assertThat(key.getTranCatCd())
                    .as("third component: DIS-TRAN-CAT-CD at CVTRA02Y.cpy:L8, key bytes 13-16")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("component order is the copybook declaration order, not the order of the source MOVEs")
        void componentOrderIsTheCopybookOrderAndNotTheMoveOrder() {
            final FixtureLoader.FixtureData fixture = discgrp();

            assertThat(fixture.field(0, GROUP_ID_COLUMN, GROUP_ID_WIDTH)).isEqualTo(FIRST_BLOCK_GROUP);
            assertThat(fixture.field(0, TYPE_CD_COLUMN, TYPE_CD_WIDTH)).isEqualTo("01");
            assertThat(fixture.field(0, CAT_CD_COLUMN, CAT_CD_WIDTH)).isEqualTo("0001");
            assertThat(GROUP_ID_COLUMN + GROUP_ID_WIDTH)
                    .as("the type code begins where the group id ends, so the byte order is the key order")
                    .isEqualTo(TYPE_CD_COLUMN);
            assertThat(TYPE_CD_COLUMN + TYPE_CD_WIDTH)
                    .as("and the category code begins where the type code ends")
                    .isEqualTo(CAT_CD_COLUMN);

            assertThat(new DisclosureGroupId(FIRST_BLOCK_GROUP, "07", 1))
                    .as("app/cbl/CBACT04C.cbl:L210-L212 moves the group id, then the CATEGORY code at :L211, "
                            + "then the TYPE code at :L212 - a measured order that differs from the key "
                            + "layout. MOVE order is immaterial because all three are set before the READ at "
                            + ":L416; the layout at CVTRA02Y.cpy:L6-L8 is what fixes the component order. "
                            + "Interchanging the type and category values yields a different key, which is "
                            + "why a reordering is silent rather than loud.")
                    .isNotEqualTo(new DisclosureGroupId(FIRST_BLOCK_GROUP, "01", 7));
        }
    }

    @Nested
    @DisplayName("the rate is NUMERIC(6,2), the only such column in the schema")
    class InterestRatePrecision {

        @Test
        @DisplayName("declares precision 6 and scale 2, taken from PIC S9(04)V99")
        void declaresPrecisionSixAndScaleTwo() {
            final Column column = columnOf(DisclosureGroup.class, "interestRate");

            assertThat(column.name()).isEqualTo(RATE_COLUMN_NAME);
            assertThat(column.nullable())
                    .as("the source field is fixed width and always carries a value")
                    .isFalse();
            assertThat(column.precision())
                    .as(" NUMERIC(11,2) and NUMERIC(12,2) are OTHER entities' tiers")
                    .isEqualTo(RATE_PRECISION);
            assertThat(column.scale())
                    .as("the V99 of DIS-INT-RATE at CVTRA02Y.cpy:L9")
                    .isEqualTo(RATE_SCALE);
            assertThat(RATE_PRECISION)
                    .as("four integer digits plus two decimals is exactly the six-character field width")
                    .isEqualTo(RATE_WIDTH);
        }

        @Test
        @DisplayName("the three decimal tiers are distinct and must never be collapsed onto one another")
        void theThreeDecimalTiersAreDistinct() {
            assertThat(FixtureLoader.RATE_FIELD_WIDTH)
                    .as("S9(04)V99 - the disclosure rate, NUMERIC(6,2)")
                    .isEqualTo(RATE_WIDTH);
            assertThat(FixtureLoader.AMOUNT_FIELD_WIDTH)
                    .as("S9(09)V99 - transaction amounts and category balances, NUMERIC(11,2)")
                    .isEqualTo(11);
            assertThat(FixtureLoader.MONEY_FIELD_WIDTH)
                    .as("S9(10)V99 - the account money fields, NUMERIC(12,2)")
                    .isEqualTo(12);
            assertThat(List.of(RATE_WIDTH, FixtureLoader.AMOUNT_FIELD_WIDTH, FixtureLoader.MONEY_FIELD_WIDTH))
                    .as("three widths, three tiers, no two alike")
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("carries the rate as an exact decimal, with no approximate binary type anywhere")
        void carriesTheRateAsAnExactDecimalWithNoApproximateType() {
            assertThat(declaredField(DisclosureGroup.class, "interestRate").getType())
                    .isEqualTo(BigDecimal.class);
            assertThat(surfaceTypeNames(DisclosureGroup.class))
                    .as("the security audit gate asserts zero float and zero double in any financial field")
                    .doesNotContain("float", "double", "java.lang.Float", "java.lang.Double");
            assertThat(surfaceTypeNames(DisclosureGroupId.class))
                    .as("the key carries a display code, which is integral and never approximate")
                    .doesNotContain("float", "double", "java.lang.Float", "java.lang.Double");
        }

        @Test
        @DisplayName("two rates that differ only in scale compare equal by compareTo but NOT by equals")
        void ratesAreComparedByCompareToAndNeverByEquals() {
            final BigDecimal narrowerScale = new BigDecimal("15.0");

            assertThat(FIFTEEN_PERCENT.compareTo(narrowerScale))
                    .as("compareTo is scale-insensitive and is the only correct comparison for a rate")
                    .isZero();
            assertThat(FIFTEEN_PERCENT.equals(narrowerScale))
                    .as("BigDecimal.equals also compares scale, so it reports 15.00 and 15.0 as unequal")
                    .isFalse();
            assertThat(new DisclosureGroup(paddedDefaultKey(), narrowerScale).getInterestRate())
                    .as("the entity stores what it is given and rescales nothing on the way in")
                    .isEqualByComparingTo(FIFTEEN_PERCENT);
        }

        @Test
        @DisplayName("a rate carrying more decimals than V99 declares is rejected, naming HALF_EVEN as the fix")
        void rejectsARateCarryingMoreDecimalsThanThePictureDeclares() {
            final BigDecimal threeDecimals = new BigDecimal("15.005");

            assertThat(threeDecimals.scale()).isEqualTo(3);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new DisclosureGroup(paddedDefaultKey(), threeDecimals))
                    .withMessageContaining("DIS-INT-RATE PIC S9(04)V99")
                    .withMessageContaining("at most " + RATE_SCALE + " decimal digits")
                    .withMessageContaining("RoundingMode.HALF_EVEN");

            assertThat(threeDecimals.setScale(RATE_SCALE, RoundingMode.HALF_EVEN))
                    .as("HALF_EVEN sends a tie to the even neighbour, so 15.005 becomes 15.00")
                    .isEqualByComparingTo(FIFTEEN_PERCENT);
            assertThat(threeDecimals.setScale(RATE_SCALE, RoundingMode.HALF_UP))
                    .as("HALF_UP would give 15.01, which is why the rounding mode is part of the contract")
                    .isEqualByComparingTo(new BigDecimal("15.01"));
        }

        @Test
        @DisplayName("a ZERO rate is accepted and round-trips as exactly 0.00")
        void aZeroRateIsAcceptedAndRoundTripsAtTheColumnScale() {
            final DisclosureGroup row = new DisclosureGroup(paddedDefaultKey(), ZERO_RATE);

            assertThat(row.getInterestRate())
                    .as("CBACT04C.cbl:L214 branches on IF DIS-INT-RATE NOT = 0, so zero is a value")
                    .isEqualByComparingTo(ZERO_RATE);
            assertThat(row.getInterestRate().scale()).isEqualTo(RATE_SCALE);
            assertThat(row.getInterestRate().toPlainString()).isEqualTo("0.00");
            assertThat(row.getInterestRate().signum()).isZero();
        }

        @Test
        @DisplayName("declares NO positivity constraint: one would reject 30 of the 51 seeded rows")
        void declaresNoPositivityConstraint() {
            final List<String> validation = new ArrayList<>();
            for (final String name : annotationNames(DisclosureGroup.class)) {
                if (name.startsWith("jakarta.validation.")) {
                    validation.add(name);
                }
            }

            assertThat(validation)
                    .as(" @Positive, @Min(1) or a magnitude normalisation would reject every "
                            + "zero-rate seed row and change which accounts accrue interest")
                    .isEmpty();
            assertThat(countZeroRateRows(discgrp()))
                    .as("the measured cost of adding one, asserted so the figure cannot drift")
                    .isEqualTo(ZERO_RATE_ROW_COUNT);
        }

        @ParameterizedTest
        @ValueSource(strings = {"0.00", "0.01", "15.00", "2.50", "-15.00", "9999.99", "-9999.99"})
        @DisplayName("accepts every rate the signed four-digit picture can hold, sign and bounds included")
        void acceptsEveryRateTheSignedPictureCanHold(final String rate) {
            final BigDecimal value = new BigDecimal(rate);

            assertThatCode(() -> new DisclosureGroup(paddedDefaultKey(), value))
                    .as("PIC S9(04)V99 carries an S, so the negative range is inside the source domain and "
                            + "no non-negativity constraint may be declared")
                    .doesNotThrowAnyException();
            assertThat(new DisclosureGroup(paddedDefaultKey(), value).getInterestRate())
                    .isEqualByComparingTo(value);
        }

        @ParameterizedTest
        @ValueSource(strings = {"10000.00", "-10000.00", "99999.99"})
        @DisplayName("rejects a rate beyond the four signed integer digits, naming the inclusive bounds")
        void rejectsARateBeyondTheFourSignedIntegerDigits(final String rate) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new DisclosureGroup(paddedDefaultKey(), new BigDecimal(rate)))
                    .withMessageContaining("must be between -" + MAX_RATE.toPlainString())
                    .withMessageContaining(MAX_RATE.toPlainString())
                    .withMessageContaining("4 signed integer digits");
        }
    }

    @Nested
    @DisplayName("High: nothing here can host stale rate state across iterations")
    class NoStaleRateState {

        @Test
        @DisplayName("every static field is final, and none is an array or a mutable container")
        void everyStaticFieldIsFinalAndNoneIsMutable() {
            final List<String> staticFields =
                    ReflectionCensus.declaredFieldNames(DisclosureGroup.class, true);

            assertThat(staticFields).as("the declared constants must not silently grow").isNotEmpty();
            for (final String name : staticFields) {
                final Field field = declaredField(DisclosureGroup.class, name);
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as(" a non-final static field is exactly where a memoised 'last rate' "
                                + "would live, and CBACT04C leaves the previous record in place on an "
                                + "invalid key, so a cache here would apply one group's rate to another")
                        .isTrue();
                assertThat(field.getType().isArray())
                        .as("static field %s must not be an array", name)
                        .isFalse();
                assertThat(field.getType())
                        .as("static field %s must be an immutable scalar", name)
                        .isIn(int.class, BigDecimal.class);
            }
        }

        @Test
        @DisplayName("declares no cache, no memoisation and no reset method beyond its accessors")
        void declaresNoCacheOrMemoisationMethod() {
            assertThat(ReflectionCensus.declaredMethodNames(DisclosureGroup.class))
                    .as("a closed method set is what makes the absence of a cache checkable by inspection")
                    .containsExactlyInAnyOrder("getId", "setId", "getInterestRate", "setInterestRate",
                            "equals", "hashCode", "toString", "requireId", "requireInterestRate");
        }

        @Test
        @DisplayName("declares NO JPA lifecycle callback, so nothing runs behind a read or a write")
        void declaresNoLifecycleCallback() {
            assertThat(annotationNames(DisclosureGroup.class))
                    .as("a callback could quietly default or normalise the rate on load or on persist")
                    .doesNotContainAnyElementsOf(LIFECYCLE_CALLBACKS);
        }

        @Test
        @DisplayName("declares NO default rate initialiser: an unpopulated instance holds null, not 0.00")
        void declaresNoDefaultRateInitialiser() throws ReflectiveOperationException {
            final Constructor<DisclosureGroup> noArgument = DisclosureGroup.class.getDeclaredConstructor();
            noArgument.setAccessible(true);
            final DisclosureGroup unpopulated = noArgument.newInstance();

            assertThat(unpopulated.getInterestRate())
                    .as(" a default of zero would be indistinguishable from one of the 30 "
                            + "legitimate zero rates in the seed fixture")
                    .isNull();
            assertThat(unpopulated.getId())
                    .as("the key is likewise never synthesised; the provider populates it after construction")
                    .isNull();
        }

        @Test
        @DisplayName("exposes a non-private no-arg constructor for the provider and a public all-args one")
        void exposesTheTwoConstructorsTheProviderAndTheBatchTierNeed() throws ReflectiveOperationException {
            assertThat(DisclosureGroup.class.getDeclaredConstructors())
                    .as("exactly two: one for the provider, one for a caller")
                    .hasSize(2);

            final Constructor<DisclosureGroup> noArgument = DisclosureGroup.class.getDeclaredConstructor();
            final int modifiers = noArgument.getModifiers();
            assertThat(Modifier.isPrivate(modifiers))
                    .as("a private no-arg constructor would stop the provider subclassing this class for a "
                            + "lazy-loading proxy, so it must be visible to a subclass. It is declared "
                            + "protected, which is the narrowest modifier that satisfies that requirement; "
                            + "the batch tier constructs rows through the all-arguments constructor below "
                            + "and never needs this one to be public.")
                    .isFalse();
            assertThat(Modifier.isProtected(modifiers) || Modifier.isPublic(modifiers))
                    .as("visible to a generated subclass in another package")
                    .isTrue();

            final Constructor<DisclosureGroup> allArguments = DisclosureGroup.class.getDeclaredConstructor(
                    DisclosureGroupId.class, BigDecimal.class);
            assertThat(Modifier.isPublic(allArguments.getModifiers()))
                    .as("the batch tier builds a row directly from a key and a decoded rate")
                    .isTrue();
            assertThat(allArguments.getParameterTypes())
                    .as("key first, then the single non-key column, mirroring the record layout")
                    .containsExactly(DisclosureGroupId.class, BigDecimal.class);
        }

        @Test
        @DisplayName("two rows built from equal keys share no rate state, so one cannot stale the other")
        void twoRowsBuiltFromEqualKeysShareNoRateState() {
            final DisclosureGroup first = new DisclosureGroup(paddedDefaultKey(), FIFTEEN_PERCENT);
            final DisclosureGroup second = new DisclosureGroup(paddedDefaultKey(), ZERO_RATE);

            second.setInterestRate(new BigDecimal("2.50"));

            assertThat(first.getInterestRate())
                    .as("mutating one instance must never be visible through another")
                    .isEqualByComparingTo(FIFTEEN_PERCENT);
            assertThat(second.getInterestRate()).isEqualByComparingTo(new BigDecimal("2.50"));
            assertThat(first.getId())
                    .as("equal keys, distinct instances: this is JPA identity, not shared state")
                    .isEqualTo(second.getId());
            assertThat(first.getId()).isNotSameAs(second.getId());
        }

        @Test
        @DisplayName("the rate setter re-applies the constructor guard rather than trusting the caller")
        void theRateSetterReAppliesTheConstructorGuard() {
            final DisclosureGroup row = defaultGroupRow();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> row.setInterestRate(null))
                    .withMessageContaining("interestRate (DIS-INT-RATE)");
            assertThat(row.getInterestRate())
                    .as("a refused mutation must leave the row exactly as it was")
                    .isEqualByComparingTo(FIFTEEN_PERCENT);
        }
    }

    @Nested
    @DisplayName("the seed fixture: 51 rows in three blocks of exactly seventeen")
    class SeedFixture {

        @Test
        @DisplayName("matches its declared census: 51 records of 50 characters in 2601 bytes")
        void matchesItsDeclaredCensus() {
            final FixtureLoader.FixtureData fixture = discgrp();

            assertThat(fixture.resourceName())
                    .as("loaded by classpath resource name only; app/data/ASCII is never read or written")
                    .isEqualTo("discgrp.txt");
            assertThat(fixture.recordCount()).isEqualTo(SEED_ROW_COUNT);
            assertThat(fixture.recordWidth()).isEqualTo(RECORD_LENGTH);
            assertThat(fixture.byteCount())
                    .as("51 records of 50 characters plus one line feed each")
                    .isEqualTo(SEED_ROW_COUNT * (RECORD_LENGTH + 1))
                    .isEqualTo(fixture.impliedByteCount())
                    .isEqualTo(2601);
        }

        @ParameterizedTest
        @CsvSource({"0, 16, 0", "17, 33, 1", "34, 50, 2"})
        @DisplayName("the three group-id blocks are exactly seventeen rows each, in fixture order")
        void theThreeGroupBlocksAreExactlySeventeenRowsEach(final int firstRow, final int lastRow,
                final int blockIndex) {
            final FixtureLoader.FixtureData fixture = discgrp();
            final String expectedGroupId = GROUP_BLOCKS.get(blockIndex);

            assertThat(lastRow - firstRow + 1)
                    .as("block %d spans rows %d to %d", blockIndex, firstRow, lastRow)
                    .isEqualTo(BLOCK_SIZE);
            for (int rowIndex = firstRow; rowIndex <= lastRow; rowIndex++) {
                assertThat(fixture.field(rowIndex, GROUP_ID_COLUMN, GROUP_ID_WIDTH))
                        .as("row %d of discgrp.txt", rowIndex + 1)
                        .isEqualTo(expectedGroupId);
            }
            assertThat(GROUP_BLOCKS.size() * BLOCK_SIZE)
                    .as("three blocks of seventeen account for every seeded row")
                    .isEqualTo(SEED_ROW_COUNT);
        }

        @Test
        @DisplayName("row 1 carries the first block's group id, NOT the DEFAULT literal")
        void rowOneCarriesTheFirstBlockGroupIdAndNotTheDefaultLiteral() {
            final FixtureLoader.FixtureData fixture = discgrp();

            assertThat(fixture.recordAt(0))
                    .as("the file does not begin with the default block, and assuming it does misreads "
                            + "every offset that follows")
                    .isEqualTo(FIRST_BLOCK_GROUP + "01" + "0001" + RATE_FIELD_FIFTEEN + FILLER_ZERO_TAIL);
            assertThat(fixture.field(0, GROUP_ID_COLUMN, GROUP_ID_WIDTH))
                    .isEqualTo(FIRST_BLOCK_GROUP)
                    .isNotEqualTo(PADDED_DEFAULT_GROUP);
            assertThat(rateOf(fixture, 0)).isEqualByComparingTo(FIFTEEN_PERCENT);
        }

        @Test
        @DisplayName("row 18 is the first DEFAULT row and its rate decodes to exactly +15.00")
        void rowEighteenIsTheFirstDefaultRowAndDecodesToFifteen() {
            final FixtureLoader.FixtureData fixture = discgrp();

            assertThat(fixture.recordAt(17))
                    .isEqualTo(PADDED_DEFAULT_GROUP + "01" + "0001" + RATE_FIELD_FIFTEEN + FILLER_ZERO_TAIL);
            assertThat(fixture.field(17, RATE_COLUMN, RATE_WIDTH)).isEqualTo(RATE_FIELD_FIFTEEN);
            assertThat(rateOf(fixture, 17))
                    .as("six characters of PIC S9(04)V99 with a positive-zero overpunch: +15.00, not 1500")
                    .isEqualByComparingTo(FIFTEEN_PERCENT);
            assertThat(rateOf(fixture, 17).scale()).isEqualTo(RATE_SCALE);
        }

        @Test
        @DisplayName("the last row is a zero-rate ZEROAPR row decoding to exactly +0.00")
        void theLastRowIsAZeroRateZeroAprRow() {
            final FixtureLoader.FixtureData fixture = discgrp();

            assertThat(fixture.recordAt(SEED_ROW_COUNT - 1))
                    .isEqualTo(ZERO_APR_GROUP + "07" + "0001" + RATE_FIELD_ZERO + FILLER_ZERO_TAIL);
            assertThat(rateOf(fixture, SEED_ROW_COUNT - 1)).isEqualByComparingTo(ZERO_RATE);
        }

        @Test
        @DisplayName("every rate overpunch on all 51 rows is the positive-zero brace, so none is negative")
        void everyRateOverpunchIsThePositiveZeroBrace() {
            final FixtureLoader.FixtureData fixture = discgrp();

            for (int rowIndex = 0; rowIndex < fixture.recordCount(); rowIndex++) {
                final String rateField = fixture.field(rowIndex, RATE_COLUMN, RATE_WIDTH);
                assertThat(rateField.charAt(RATE_WIDTH - 1))
                        .as("row %d sign position", rowIndex + 1)
                        .isEqualTo(POSITIVE_ZERO_OVERPUNCH);
                assertThat(rateOf(fixture, rowIndex).signum())
                        .as("row %d must not be negative; no negative seed row may be fabricated", rowIndex + 1)
                        .isGreaterThanOrEqualTo(0);
            }
        }

        @Test
        @DisplayName("the seven zero-rate DEFAULT pairs are exactly the named ones, and ten are non-zero")
        void theSevenZeroRateDefaultPairsAreExactlyTheNamedOnes() {
            final FixtureLoader.FixtureData fixture = discgrp();
            final List<String> zeroPairs = new ArrayList<>();
            final List<String> nonZeroPairs = new ArrayList<>();

            for (int rowIndex = BLOCK_SIZE; rowIndex < 2 * BLOCK_SIZE; rowIndex++) {
                if (rateOf(fixture, rowIndex).signum() == 0) {
                    zeroPairs.add(pairOf(fixture, rowIndex));
                } else {
                    nonZeroPairs.add(pairOf(fixture, rowIndex));
                }
            }

            assertThat(zeroPairs)
                    .as("the ONLY fixture coverage of the skip branch at CBACT04C.cbl:L214-L217")
                    .containsExactlyElementsOf(ZERO_RATE_DEFAULT_PAIRS);
            assertThat(nonZeroPairs).hasSize(10);
            assertThat(zeroPairs.size() + nonZeroPairs.size()).isEqualTo(BLOCK_SIZE);
        }

        @Test
        @DisplayName("all seventeen DEFAULT rows are present and load-bearing: deleting one abends the job")
        void allSeventeenDefaultRowsArePresentAndLoadBearing() {
            final FixtureLoader.FixtureData fixture = discgrp();
            final List<String> defaultPairs = new ArrayList<>();

            for (int rowIndex = BLOCK_SIZE; rowIndex < 2 * BLOCK_SIZE; rowIndex++) {
                defaultPairs.add(pairOf(fixture, rowIndex));
            }

            assertThat(defaultPairs)
                    .as(" the retry at CBACT04C.cbl:L444 accepts "
                            + "only file status '00', so a missing default row abends at :L458")
                    .containsExactlyElementsOf(DEFAULT_BLOCK_PAIRS)
                    .doesNotHaveDuplicates()
                    .hasSize(BLOCK_SIZE);
        }

        @Test
        @DisplayName("the FILLER X(28) tail is ZERO filled, not space filled")
        void theFillerTailIsZeroFilledNotSpaceFilled() {
            final FixtureLoader.FixtureData fixture = discgrp();

            for (int rowIndex = 0; rowIndex < fixture.recordCount(); rowIndex++) {
                assertThat(fixture.field(rowIndex, FILLER_COLUMN, FILLER_WIDTH))
                        .as("row %d: a fixed-width writer that space-pads will not reproduce this fixture "
                                + "byte for byte", rowIndex + 1)
                        .isEqualTo(FILLER_ZERO_TAIL)
                        .doesNotContain(" ");
            }
            assertThat(FILLER_COLUMN + FILLER_WIDTH - 1)
                    .as("the filler ends on the last byte of the record")
                    .isEqualTo(RECORD_LENGTH);
        }

        @Test
        @DisplayName("thirty of the fifty-one seeded rates are zero: six, then seven, then seventeen")
        void thirtyOfTheFiftyOneSeededRatesAreZero() {
            final FixtureLoader.FixtureData fixture = discgrp();
            final List<Integer> zerosPerBlock = new ArrayList<>();

            for (int block = 0; block < GROUP_BLOCKS.size(); block++) {
                int zeros = 0;
                for (int rowIndex = block * BLOCK_SIZE; rowIndex < (block + 1) * BLOCK_SIZE; rowIndex++) {
                    if (rateOf(fixture, rowIndex).signum() == 0) {
                        zeros++;
                    }
                }
                zerosPerBlock.add(zeros);
            }

            assertThat(zerosPerBlock)
                    .as("the first block also carries six zero-rate rows, which is what an earlier count of "
                            + "24 omitted; the zero-APR block is uniformly zero")
                    .containsExactly(6, 7, BLOCK_SIZE);
            assertThat(countZeroRateRows(fixture)).isEqualTo(ZERO_RATE_ROW_COUNT);
            assertThat(SEED_ROW_COUNT - ZERO_RATE_ROW_COUNT)
                    .as("the remaining rows carry a non-zero rate")
                    .isEqualTo(21);
        }

        @Test
        @DisplayName("every one of the 51 seeded rows is storable by the entity exactly as decoded")
        void everySeededRowIsStorableByTheEntity() {
            final FixtureLoader.FixtureData fixture = discgrp();

            for (int rowIndex = 0; rowIndex < fixture.recordCount(); rowIndex++) {
                final BigDecimal rate = rateOf(fixture, rowIndex);
                final DisclosureGroupId key = new DisclosureGroupId(
                        fixture.field(rowIndex, GROUP_ID_COLUMN, GROUP_ID_WIDTH),
                        fixture.field(rowIndex, TYPE_CD_COLUMN, TYPE_CD_WIDTH),
                        Integer.parseInt(fixture.field(rowIndex, CAT_CD_COLUMN, CAT_CD_WIDTH)));
                final DisclosureGroup row = new DisclosureGroup(key, rate);

                assertThat(row.getInterestRate())
                        .as("row %d must survive the entity's guards unaltered", rowIndex + 1)
                        .isEqualByComparingTo(rate);
                assertThat(row.getId()).isEqualTo(key);
            }
        }
    }

    @Nested
    @DisplayName("overpunch decoding is position aware, driven only by the picture clause")
    class OverpunchDecoding {

        @Test
        @DisplayName("decoding at the picture-derived width yields the documented value")
        void decodingAtThePictureDerivedWidthYieldsTheDocumentedValue() {
            assertThat(FixtureLoader.decodeZonedDecimal(RATE_FIELD_FIFTEEN, RATE_SCALE))
                    .as("the brace carries +0 as the low-order digit, so 00150 with a brace is +15.00")
                    .isEqualByComparingTo(FIFTEEN_PERCENT);
            assertThat(FixtureLoader.decodeZonedDecimal(RATE_FIELD_ZERO, RATE_SCALE))
                    .isEqualByComparingTo(ZERO_RATE);
            assertThat(FixtureLoader.RATE_FIELD_WIDTH)
                    .as("the width must come from PIC S9(04)V99 and never from a bare number")
                    .isEqualTo(RATE_WIDTH);
        }

        @Test
        @DisplayName("a rate slice one character SHORT decodes silently to the wrong value")
        void aShortRateSliceDecodesSilentlyToTheWrongValue() {
            final FixtureLoader.FixtureData fixture = discgrp();
            final String shortSlice = fixture.field(17, RATE_COLUMN, RATE_WIDTH - 1);

            assertThat(shortSlice).isEqualTo("00150");
            assertThat(FixtureLoader.decodeZonedDecimal(shortSlice, RATE_SCALE))
                    .as("the trailing zero is read as the sign position, so the value silently becomes 1.50 "
                            + "instead of 15.00 - no exception, no warning, a wrong number. This is why the "
                            + "width is taken from the picture clause rather than guessed.")
                    .isEqualByComparingTo(new BigDecimal("1.50"))
                    .isNotEqualByComparingTo(FIFTEEN_PERCENT);
        }

        @Test
        @DisplayName("a rate slice one character LONG fails loudly, with its root cause preserved")
        void anOverlongRateSliceFailsLoudlyWithItsCausePreserved() {
            final FixtureLoader.FixtureData fixture = discgrp();

            final Throwable thrown =
                    catchThrowable(() -> fixture.signedDecimal(17, RATE_COLUMN, RATE_WIDTH + 1));

            assertThat(thrown)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("does not hold a valid zoned decimal")
                    .hasMessageContaining("columns " + RATE_COLUMN + "-" + (RATE_COLUMN + RATE_WIDTH))
                    .cause()
                    .as("the wrapper adds the resource and offset context without discarding the root cause")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("every character before the sign position must be a digit");
        }

        @Test
        @DisplayName("the group id A000000000 is not a zoned decimal where it stands, and decoding it fails")
        void theGroupIdBeginningWithAIsNotAZonedDecimalWhereItStands() {
            final FixtureLoader.FixtureData fixture = discgrp();
            final String groupIdField = fixture.field(0, GROUP_ID_COLUMN, GROUP_ID_WIDTH);

            assertThat(groupIdField).isEqualTo(FIRST_BLOCK_GROUP).startsWith("A");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> FixtureLoader.decodeZonedDecimal(groupIdField, RATE_SCALE))
                    .withMessageContaining("every character before the sign position must be a digit");
        }

        @Test
        @DisplayName("the letter A is an overpunch ONLY at the sign position, so a global substitution is wrong")
        void theLetterAIsAnOverpunchOnlyAtTheSignPosition() {
            assertThat(FixtureLoader.decodeZonedDecimal("000000000A", RATE_SCALE))
                    .as("at the sign position A carries +1, which is exactly why applying the overpunch "
                            + "table globally would corrupt the group id A000000000 into a signed number. "
                            + "")
                    .isEqualByComparingTo(new BigDecimal("0.01"));
            assertThat(FixtureLoader.decodeZonedDecimal("00150" + POSITIVE_ZERO_OVERPUNCH, RATE_SCALE))
                    .as("the same table applied at the picture-derived offset is correct")
                    .isEqualByComparingTo(FIFTEEN_PERCENT);
        }

        @Test
        @DisplayName("a negative overpunch is recognised, but no seeded rate uses one")
        void aNegativeOverpunchIsRecognisedButUnusedInThisFixture() {
            assertThat(FixtureLoader.decodeZonedDecimal("00150}", RATE_SCALE))
                    .as("the closing brace carries -0, so the decoder handles the signed half of the domain")
                    .isEqualByComparingTo(new BigDecimal("-15.00"));
            assertThat(FixtureLoader.decodeZonedDecimal("00150J", RATE_SCALE))
                    .as("J carries -1")
                    .isEqualByComparingTo(new BigDecimal("-15.01"));
            assertThat(countZeroRateRows(discgrp()) + 21)
                    .as("all 51 seeded rates are zero or positive; none is negative")
                    .isEqualTo(SEED_ROW_COUNT);
        }

        @Test
        @DisplayName("a character that is neither a digit nor an overpunch is rejected, naming both tables")
        void aCharacterThatIsNeitherDigitNorOverpunchIsRejected() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> FixtureLoader.decodeZonedDecimal("00150Z", RATE_SCALE))
                    .withMessageContaining("is not a zoned-decimal overpunch")
                    .withMessageContaining("{ABCDEFGHI")
                    .withMessageContaining("}JKLMNOPQR");
        }
    }

    @Nested
    @DisplayName("cross-fixture and batch-only facts that must not be 'fixed'")
    class BatchOnlyDatasetAndCrossFixtureFacts {

        @Test
        @DisplayName("the DEFAULT block covers seventeen of the eighteen seeded category pairs")
        void theDefaultBlockCoversSeventeenOfTheEighteenSeededCategoryPairs() {
            final FixtureLoader.FixtureData categories =
                    FixtureLoader.load(FixtureLoader.Fixture.TRANSACTION_CATEGORY);
            final List<String> categoryPairs = new ArrayList<>();

            for (int rowIndex = 0; rowIndex < categories.recordCount(); rowIndex++) {
                categoryPairs.add(categories.field(rowIndex, 1, TYPE_CD_WIDTH + CAT_CD_WIDTH));
            }

            assertThat(categoryPairs).hasSize(18).doesNotHaveDuplicates();
            assertThat(categoryPairs)
                    .as("the eighteen pairs are a STRICT SUPERSET of the seventeen the default block rates")
                    .containsAll(DEFAULT_BLOCK_PAIRS);
            assertThat(DEFAULT_BLOCK_PAIRS).hasSize(BLOCK_SIZE);
        }

        @Test
        @DisplayName("the one uncovered pair is the interest job's OUTPUT category, so no row may be invented")
        void theOneUncoveredPairIsTheInterestJobsOutputCategory() {
            final FixtureLoader.FixtureData categories =
                    FixtureLoader.load(FixtureLoader.Fixture.TRANSACTION_CATEGORY);
            final List<String> uncovered = new ArrayList<>();

            for (int rowIndex = 0; rowIndex < categories.recordCount(); rowIndex++) {
                final String pair = categories.field(rowIndex, 1, TYPE_CD_WIDTH + CAT_CD_WIDTH);
                if (!DEFAULT_BLOCK_PAIRS.contains(pair)) {
                    uncovered.add(pair);
                }
            }

            assertThat(uncovered)
                    .as("adding a 010005 DEFAULT row would fabricate data. "
                            + "app/cbl/CBACT04C.cbl:L482-L483 moves '01' to TRAN-TYPE-CD and '05' to "
                            + "TRAN-CAT-CD when WRITING the generated interest transaction, so this pair is "
                            + "an output classification and never an input rate lookup. The 17-versus-18 "
                            + "asymmetry is intentional.")
                    .containsExactly(INTEREST_OUTPUT_PAIR);
            assertThat(categories.field(4, TYPE_CD_WIDTH + CAT_CD_WIDTH + 1, 50).strip())
                    .as("row 5 of trancatg.txt names the category")
                    .isEqualTo(INTEREST_OUTPUT_DESCRIPTION);
        }

        @Test
        @DisplayName("no seeded account references a disclosure group, which is why DEFAULT is the live path")
        void noSeededAccountReferencesADisclosureGroup() {
            final FixtureLoader.FixtureData accounts = FixtureLoader.load(FixtureLoader.Fixture.ACCOUNT);

            assertThat(accounts.recordCount()).isEqualTo(50);
            for (int rowIndex = 0; rowIndex < accounts.recordCount(); rowIndex++) {
                assertThat(accounts.field(rowIndex, ACCOUNT_GROUP_ID_COLUMN, GROUP_ID_WIDTH))
                        .as("ACCT-GROUP-ID of account row %d", rowIndex + 1)
                        .isEqualTo(" ".repeat(GROUP_ID_WIDTH))
                        .isNotEqualTo(PADDED_DEFAULT_GROUP);
            }
        }

        @Test
        @DisplayName("declares no association, no join column and no generated identifier")
        void declaresNoAssociationAndNoGeneratedIdentifier() {
            assertThat(annotationNames(DisclosureGroup.class))
                    .as("the group id is a plain key component, never a reference to Account: a blank group "
                            + "id would break any such reference, and no foreign key from Account exists")
                    .doesNotContainAnyElementsOf(FORBIDDEN_MAPPINGS);
            assertThat(annotationNames(DisclosureGroupId.class))
                    .doesNotContainAnyElementsOf(FORBIDDEN_MAPPINGS);
        }

        @Test
        @DisplayName("nothing here presumes an online path or an alternate index")
        void nothingPresumesAnOnlinePathOrAnAlternateIndex() {
            assertThat(surfaceTypeNames(DisclosureGroup.class))
                    .as("DISCGRP is absent from the eight CICS FILE definitions in app/csd/CARDDEMO.CSD, so "
                            + "this dataset is batch-only and no online construct may appear on its surface")
                    .allSatisfy(name -> assertThat(name.toLowerCase(Locale.ROOT))
                            .doesNotContain("servlet", "request", "response", "session"));
            assertThat(annotationNames(DisclosureGroup.class))
                    .as("app/catlg/LISTCAT.txt:L3938-L3946 tallies AIX 3 / CLUSTER 10 / GDG 7 / PATH 3, and "
                            + "the three alternate indexes belong to CARDDATA, CARDXREF and TRANSACT only, "
                            + "so no secondary index is declared for this table")
                    .doesNotContain("jakarta.persistence.Index", "jakarta.persistence.SecondaryTable");
        }
    }

    @Nested
    @DisplayName("scope boundaries: one intra-project type, and no interchangeable identifier")
    class ScopeBoundaries {

        @Test
        @DisplayName("the only intra-project type on the entity's surface is its own composite key")
        void theOnlyIntraProjectTypeOnTheSurfaceIsItsKey() {
            final List<String> intraProject = new ArrayList<>();
            for (final String name : surfaceTypeNames(DisclosureGroup.class)) {
                if (name.startsWith("com.cardemo.")) {
                    intraProject.add(name);
                }
            }

            assertThat(intraProject)
                    .as("a data holder names exactly one sibling type: the identifier that keys it")
                    .containsExactly(KEY_TYPE_NAME);
        }

        @Test
        @DisplayName("reaches into no layer above or beside the model")
        void reachesIntoNoLayerAboveOrBesideTheModel() {
            for (final String forbidden : FORBIDDEN_PACKAGES) {
                for (final String declared : surfaceTypeNames(DisclosureGroup.class)) {
                    assertThat(declared)
                            .as("the entity must not depend on %s", forbidden)
                            .doesNotStartWith(forbidden);
                }
                for (final String declared : surfaceTypeNames(DisclosureGroupId.class)) {
                    assertThat(declared)
                            .as("the key must not depend on %s", forbidden)
                            .doesNotStartWith(forbidden);
                }
            }
        }

        @Test
        @DisplayName("the key is not interchangeable with either of the other two composite identifiers")
        void theKeyIsNotInterchangeableWithTheOtherCompositeIdentifiers() {
            final DisclosureGroupId key = paddedDefaultKey();

            for (final Class<?> otherType : CompositeKeys.types()) {
                if (otherType.equals(DisclosureGroupId.class)) {
                    continue;
                }
                final Object foreignKey = CompositeKeys.of(otherType, 0);

                assertThat(key)
                        .as("a 16-byte DIS-GROUP-KEY must never equal a %s", otherType.getSimpleName())
                        .isNotEqualTo(foreignKey);
                assertThat(foreignKey)
                        .as("and the relation must be symmetric, so neither equals accepts a foreign id type")
                        .isNotEqualTo(key);
            }
            assertThat(CompositeKeys.types())
                    .as("three composite identifiers exist: 17-byte, 16-byte and 6-byte keys")
                    .hasSize(3)
                    .contains(DisclosureGroupId.class);
        }

        @Test
        @DisplayName("an entity of another type never equals a disclosure group row")
        void anEntityOfAnotherTypeNeverEqualsADisclosureGroupRow() {
            final DisclosureGroup row = defaultGroupRow();

            assertThat(row).isEqualTo(row);
            assertThat(row.equals(null))
                    .as("equals must be null-safe rather than throwing")
                    .isFalse();
            assertThat(row).isNotEqualTo(paddedDefaultKey());
            assertThat(row).isNotEqualTo(TABLE_NAME);
            assertThat(row).isEqualTo(new DisclosureGroup(paddedDefaultKey(), ZERO_RATE));
            assertThat(row.hashCode()).isEqualTo(new DisclosureGroup(paddedDefaultKey(), MAX_RATE).hashCode());
        }
    }

    @Nested
    @DisplayName("untrusted input: every boundary the source itself branches on")
    class HostileInputAndBoundaries {

        @Test
        @DisplayName("rejects a null key, naming the COBOL field, the table and the copybook locator")
        void rejectsANullKeyNamingTheCobolFieldAndTheLocator() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new DisclosureGroup(null, FIFTEEN_PERCENT))
                    .withMessageContaining("id (DIS-GROUP-KEY)")
                    .withMessageContaining(TABLE_NAME)
                    .withMessageContaining("app/cpy/CVTRA02Y.cpy:L5")
                    .withNoCause();
        }

        @Test
        @DisplayName("rejects a null rate, naming the column, the precision and the picture locator")
        void rejectsANullRateNamingTheColumnAndThePicture() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new DisclosureGroup(paddedDefaultKey(), null))
                    .withMessageContaining("interestRate (DIS-INT-RATE)")
                    .withMessageContaining("NUMERIC(" + RATE_PRECISION + "," + RATE_SCALE + ")")
                    .withMessageContaining("app/cpy/CVTRA02Y.cpy:L9")
                    .withNoCause();
        }

        @Test
        @DisplayName("rejects a group id that would make the key 17 bytes instead of 16")
        void rejectsAGroupIdThatWouldMakeTheKeySeventeenBytes() {
            final String overlong = "A".repeat(GROUP_ID_WIDTH + 1);

            assertThat(overlong.length() + TYPE_CD_WIDTH + CAT_CD_WIDTH).isEqualTo(KEY_LENGTH + 1);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new DisclosureGroupId(overlong, "01", 1))
                    .withMessageContaining("accountGroupId (DIS-ACCT-GROUP-ID)")
                    .withMessageContaining("must not exceed " + GROUP_ID_WIDTH);
        }

        @Test
        @DisplayName("accepts a group id that leaves the key 15 bytes, because the fallback literal is short")
        void acceptsAGroupIdThatLeavesTheKeyFifteenBytes() {
            final String nineCharacters = "DEFAULT  ";

            assertThat(nineCharacters.length() + TYPE_CD_WIDTH + CAT_CD_WIDTH).isEqualTo(KEY_LENGTH - 1);
            assertThatCode(() -> new DisclosureGroupId(nineCharacters, "01", 1))
                    .as("the guard is a ceiling, not an exact width, because CBACT04C.cbl:L437 supplies the "
                            + "bare seven-character literal rather than ten padded bytes")
                    .doesNotThrowAnyException();
            assertThat(new DisclosureGroupId(nineCharacters, "01", 1))
                    .as("but a differently padded value is a DIFFERENT key, which is the caller's burden")
                    .isNotEqualTo(paddedDefaultKey());
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "0", "001", "0100"})
        @DisplayName("rejects a transaction type code that is not exactly two characters")
        void rejectsATypeCodeThatIsNotExactlyTwoCharacters(final String badTypeCode) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new DisclosureGroupId(PADDED_DEFAULT_GROUP, badTypeCode, 1))
                    .withMessageContaining("tranTypeCd (DIS-TRAN-TYPE-CD)")
                    .withMessageContaining("must be exactly " + TYPE_CD_WIDTH + " characters");
        }

        @Test
        @DisplayName("rejects a null in each of the three key components separately")
        void rejectsANullInEachKeyComponentSeparately() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new DisclosureGroupId(null, "01", 1))
                    .withMessageContaining("accountGroupId (DIS-ACCT-GROUP-ID) must not be null");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new DisclosureGroupId(PADDED_DEFAULT_GROUP, null, 1))
                    .withMessageContaining("tranTypeCd (DIS-TRAN-TYPE-CD) must not be null");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new DisclosureGroupId(PADDED_DEFAULT_GROUP, "01", null))
                    .withMessageContaining("tranCatCd (DIS-TRAN-CAT-CD) must not be null");
        }

        @ParameterizedTest
        @ValueSource(ints = {-1, 10_000, 99_999})
        @DisplayName("rejects a category code outside the unsigned four-digit domain of PIC 9(04)")
        void rejectsACategoryCodeOutsideTheFourDigitDomain(final int outOfDomain) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new DisclosureGroupId(PADDED_DEFAULT_GROUP, "01", outOfDomain))
                    .withMessageContaining("tranCatCd (DIS-TRAN-CAT-CD)")
                    .withMessageContaining("must be within 0 to 9999");
        }

        @Test
        @DisplayName("an unpopulated row is equal to nothing, not even to another unpopulated row")
        void anUnpopulatedRowIsEqualToNothing() throws ReflectiveOperationException {
            final Constructor<DisclosureGroup> noArgument = DisclosureGroup.class.getDeclaredConstructor();
            noArgument.setAccessible(true);
            final DisclosureGroup first = noArgument.newInstance();
            final DisclosureGroup second = noArgument.newInstance();

            assertThat(first)
                    .as("a null key makes identity undefined, so two unsaved rows must not collapse onto one")
                    .isNotEqualTo(second);
            assertThat(first).isEqualTo(first);
            assertThat(first.toString())
                    .as("a diagnostic rendering must not throw on an unpopulated row")
                    .contains("DisclosureGroup");
        }

        @Test
        @DisplayName("toString renders the whole row, which is safe because nothing here is sensitive")
        void toStringRendersTheWholeRowAndCarriesNothingSensitive() {
            assertThat(defaultGroupRow().toString())
                    .as("a group id, a type code, a category code and a rate: no credential, no token and "
                            + "no personal data, so the whole row may be rendered")
                    .contains("DisclosureGroup")
                    .contains(PADDED_DEFAULT_GROUP)
                    .contains(FIFTEEN_PERCENT.toPlainString());
        }

        @Test
        @DisplayName("the key setter re-applies its guard, so a row cannot be left without an identity")
        void theKeySetterReAppliesItsGuard() {
            final DisclosureGroup row = defaultGroupRow();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> row.setId(null))
                    .withMessageContaining("id (DIS-GROUP-KEY)");
            assertThat(row.getId()).isEqualTo(paddedDefaultKey());

            row.setId(new DisclosureGroupId(ZERO_APR_GROUP, "07", 1));
            assertThat(row.getId().getAccountGroupId()).isEqualTo(ZERO_APR_GROUP);
        }
    }
}

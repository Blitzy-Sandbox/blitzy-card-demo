/*
 * ******************************************************************
 * Program     : TransactionCategoryTest.java
 * Component   : Unit test tier, resident at
 *               src/test/java/com/cardemo/unit/model
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM tier, no container, no
 *               Spring context, no database, no live endpoint
 * Function    : Proves that com.cardemo.model.entity.TransactionCategory
 *               reproduces the TRAN-CAT-RECORD contract of the
 *               transaction category type file TRANCATG - a 60 byte
 *               record over a 6 byte composite key, exactly one non-key
 *               column, an unmapped four byte FILLER, no version column
 *               and no association - and that the eighteen row seed
 *               fixture, the twin TRAN-CAT-KEY group name and the batch
 *               only dataset status are asserted rather than assumed.
 * Source      : app/cpy/CVTRA04Y.cpy (60 B, composite key 6) @ 7756d89
 * Source      : app/cpy/CVTRA01Y.cpy:L5-L8 (the 17 byte twin key)
 * Source      : app/cpy/CVTRA03Y.cpy:L5 (TRAN-TYPE, no -CD suffix)
 * Source      : app/catlg/LISTCAT.txt:L1473-L1477, :L3938-L3946
 * Source      : app/cbl/CBACT04C.cbl:L482-L484 @ 7756d89
 * Source      : app/csd/CARDDEMO.CSD (eight CICS files, no TRANCATG)
 * Source      : app/data/ASCII/trancatg.txt, trantype.txt, discgrp.txt
 *               and tcatbal.txt @ 7756d89
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
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.cardemo.model.entity.TransactionCategory;
import com.cardemo.model.key.DisclosureGroupId;
import com.cardemo.model.key.TransactionCategoryBalanceId;
import com.cardemo.model.key.TransactionCategoryId;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.io.IOException;
import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * Unit test for {@link TransactionCategory}, the JPA entity that replaces the {@code TRAN-CAT-RECORD} layout of
 * the transaction category type file {@code TRANCATG}.
 *
 * <h2>1. What this test does</h2>
 *
 * <p>It holds the entity, its composite identifier and the seed fixture to the contract of the frozen legacy
 * corpus, every clause of which was read first hand at the traceability anchor commit {@code 7756d89}. Nothing
 * below is inherited from prose: each figure is either re-derived from a copybook at run time or asserted
 * against a constant that carries its own locator.
 *
 * <ol>
 *   <li><strong>Record geometry.</strong> {@code app/cpy/CVTRA04Y.cpy} declares, at {@code :L4-L9},
 *       {@code 01 TRAN-CAT-RECORD.} then {@code 05 TRAN-CAT-KEY.} with {@code 10 TRAN-TYPE-CD PIC X(02).} and
 *       {@code 10 TRAN-CAT-CD PIC 9(04).}, then {@code 05 TRAN-CAT-TYPE-DESC PIC X(50).} and
 *       {@code 05 FILLER PIC X(04).} The arithmetic closes exactly: {@code 2 + 4 = 6} bytes of key,
 *       {@code + 50 = 56} populated bytes, {@code + 4 = 60}, which is the {@code RECLN = 60} the copybook
 *       header states at {@code :L2} and the {@code AVGLRECL 60} the catalogue reports at
 *       {@code app/catlg/LISTCAT.txt:L1475} beneath {@code CLUSTER--AWS.M2.CARDDEMO.TRANCATG.VSAM.KSDS} at
 *       {@code :L1473}. The same catalogue line reports {@code KEYLEN 6}, and {@code :L1476} reports
 *       {@code RKP 0}, so the key is the record prefix. The entity models 56 of the 60 bytes: the four byte
 *       {@code FILLER} is mapped nowhere, and this test asserts that it stays unmapped.</li>
 *   <li><strong>Exactly one non-key column, and no version column.</strong> {@code categoryDescription} is the
 *       only non-key persistent member. A version column exists on four entities in this schema -
 *       {@code Account}, {@code Card}, {@code Customer} and {@code Transaction} - because those are the rows
 *       the online programs read for update under a concurrency guard. This is a reference table that batch
 *       reads and the seed migration writes, so adding one here would fabricate a column no source field
 *       justifies. Severity of a regression: <strong>High</strong>.</li>
 *   <li><strong>The key class owns every key column.</strong> The entity declares a single
 *       {@code @EmbeddedId} of type {@link TransactionCategoryId} and restates no key column: no
 *       {@code @AttributeOverride}, no second {@code @Column} for {@code tran_type_cd} or
 *       {@code tran_cat_cd}, no scalar component field beside the identifier, and no second identity mechanism
 *       such as {@code @IdClass} or {@code @MapsId}. Any of those maps one database column twice, which either
 *       aborts the Hibernate bootstrap or resolves silently into a duplicated column. Severity of a
 *       regression: <strong>Blocker</strong>.</li>
 *   <li><strong>The {@code TRAN-CAT-KEY} collision.</strong> The group name is declared in two copybooks with
 *       two entirely different shapes. {@code app/cpy/CVTRA04Y.cpy:L5} is 6 bytes over two components with a
 *       {@code TRAN-} prefix, and is this table's key. {@code app/cpy/CVTRA01Y.cpy:L5} is 17 bytes over three
 *       components with a {@code TRANCAT-} prefix - {@code TRANCAT-ACCT-ID PIC 9(11)},
 *       {@code TRANCAT-TYPE-CD PIC X(02)} and {@code TRANCAT-CD PIC 9(04)} - over the {@code TCATBALF}
 *       cluster, and belongs to {@link TransactionCategoryBalanceId}. Note the third member is spelled
 *       {@code TRANCAT-CD} at {@code app/cpy/CVTRA01Y.cpy:L8} and <em>not</em> {@code TRANCAT-CAT-CD}; that
 *       abbreviation is a second name trap in the immediate neighbourhood, severity <strong>Medium</strong>,
 *       and must never be "corrected". The two identifier types are asserted here to be mutually unusable, in
 *       both directions, and distinct again from the 16 byte {@link DisclosureGroupId}. Severity of the group
 *       name collision as a source reading hazard: <strong>Medium</strong>; severity if the types are ever
 *       conflated in production code: <strong>Blocker</strong>.</li>
 *   <li><strong>The seed fixture, measured.</strong> {@code app/data/ASCII/trancatg.txt} is 1098 bytes of 18
 *       records 60 characters wide, and {@code 18 x (60 + 1) = 1098} closes the geometry. The type code census
 *       is {@code 01} five times, {@code 02} three, {@code 03} three, {@code 04} three, {@code 05} once,
 *       {@code 06} twice and {@code 07} once. Every one of those codes resolves against the seven rows of
 *       {@code app/data/ASCII/trantype.txt} with no orphan in either direction.</li>
 *   <li><strong>The 17 versus 18 asymmetry is deliberate.</strong> The 18 pairs here are a strict superset of
 *       the 17 pairs in the {@code DEFAULT} block of {@code app/data/ASCII/discgrp.txt}, and the one extra
 *       pair is {@code 010005}, "Interest Amount". That pair is an <em>output</em> category:
 *       {@code app/cbl/CBACT04C.cbl:L482-L483} moves the literals {@code '01'} and {@code '05'} into the type
 *       and category fields of every synthetic interest transaction the job writes, with {@code :L484} moving
 *       the literal {@code 'System'} into its source field. Because {@code 010005} is never an input to the
 *       rate lookup at {@code app/cbl/CBACT04C.cbl:L415-L440}, the {@code DEFAULT} block correctly has no row
 *       for it. Adding a {@code 010005 DEFAULT} row, or deleting {@code 010005} from here, would both be
 *       fabrication. Severity: <strong>Blocker</strong>.</li>
 *   <li><strong>The {@code FILLER} tail is zero filled.</strong> Bytes 57-60 hold four literal {@code '0'}
 *       characters on all 18 rows, not spaces - the same convention as {@code trantype.txt},
 *       {@code discgrp.txt} and {@code tcatbal.txt}. A fixed width writer that space pads cannot reproduce
 *       the fixture byte for byte, and the measured maximum trailing space run of the 60 character record is
 *       consequently zero. Severity of a regression: <strong>High</strong>.</li>
 *   <li><strong>No overpunch decoder belongs anywhere near this fixture.</strong> The copybook declares no
 *       signed field at all, while the descriptions carry letters across the whole {@code A}-{@code R} range
 *       that a zoned decimal decode reads as sign overpunches. Decoding must stay position aware, driven only
 *       by a PIC clause. This test proves the point by applying the decoder to the description column and
 *       asserting it is rejected with its cause preserved. Severity if a global decoder is ever applied:
 *       <strong>Blocker</strong>.</li>
 *   <li><strong>Batch only, and no alternate index.</strong> {@code app/csd/CARDDEMO.CSD} defines exactly
 *       eight CICS files - {@code ACCTDAT}, {@code CARDAIX}, {@code CARDDAT}, {@code CCXREF},
 *       {@code CUSTDAT}, {@code CXACAIX}, {@code TRANSACT} and {@code USRSEC} - and {@code TRANCATG} is not
 *       among them, so the dataset has no online definition and no authorisation rule.
 *       {@code app/catlg/LISTCAT.txt:L3938-L3946} tallies {@code AIX 3}, {@code CLUSTER 10}, {@code GDG 7} and
 *       {@code PATH 3}, and the three alternate indexes are named at {@code :L254}, {@code :L455} and
 *       {@code :L3645} as those of {@code CARDDATA}, {@code CARDXREF} and {@code TRANSACT}. None is
 *       {@code TRANCATG}. Severity of a regression here: <strong>Low</strong>, because inventing an index or an
 *       endpoint for this dataset costs correctness nothing but does contradict the source of record; the
 *       remedy is to delete the addition rather than to widen this test.</li>
 *   <li><strong>Identity, rendering and hostile input.</strong> Equality is value based over the embedded
 *       identifier and is reflexive, symmetric, transitive, null safe, type safe and stable; the rendering
 *       carries the key and the description and nothing else; and every guard is exercised at and beyond its
 *       boundary with the exception message asserted, never merely the exception type.</li>
 * </ol>
 *
 * <h2>2. How to run, build and test</h2>
 *
 * <p>This class sits in {@code src/test/java/com/cardemo/unit/model}, which is the tree the root
 * {@code pom.xml} binds to Surefire; the Failsafe execution is scoped to the integration and end to end trees
 * and never collects it. A class placed outside the Surefire tree runs under neither plugin and reports no
 * failure at all, so the location is part of the contract.
 *
 * <pre>{@code
 * ./mvnw -B -ntp -o test                                        # the whole unit tier
 * ./mvnw -B -ntp -o test -Dtest=TransactionCategoryTest         # this class alone
 * ./mvnw -B -ntp -o verify                                      # adds the coverage gate
 * }</pre>
 *
 * <h2>3. Configuration and defaults</h2>
 *
 * <p>None, deliberately. There is no Spring context, no connection, no queue, no object store and no reachable
 * endpoint, so there is nothing to configure and no credential to hold. The four fixtures are read from the
 * test classpath by resource name through {@link FixtureLoader}, never from a path and never with a write.
 * Determinism is total: no method here reads a wall clock, a default locale, a default time zone, a random
 * source or a hash iteration order, and the entity under test carries no temporal member, so the fixed clock
 * that {@link FixedClockProvider} supplies to the time dependent tiers is not needed. No test double is
 * created either, so Mockito's strict stubbing never comes into play.
 *
 * <h2>4. Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>Compilation fails on a warning.</strong> The compiler runs at release 25 with
 *       {@code -Xlint:all}, {@code -Werror} and {@code failOnWarning}, and that configuration reaches test
 *       compilation as well as main. A raw type or a deprecation is a build failure rather than a warning, so
 *       fix the cause; the strict settings are not to be relaxed.</li>
 *   <li><strong>A fixture resource cannot be found.</strong> The nine fixtures are copied to the root of
 *       {@code target/test-classes}, so run {@code test-compile} first. Watch the spelling of the daily
 *       transaction fixture in particular: it is {@code dailytran.txt}, spelled in full, even though the
 *       legacy DD name is {@code DALYTRAN} - {@code dalytran.txt} does not exist.</li>
 *   <li><strong>A record width assertion fails.</strong> Trailing padding is part of the layout. A 59
 *       character record usually means trailing whitespace was trimmed somewhere; a 61 character one means a
 *       byte was added. Either breaks the 60 byte geometry that the catalogue fixes.</li>
 *   <li><strong>A key mapping assertion fails.</strong> A key column was restated on the entity. Remove the
 *       {@code @AttributeOverride}, the duplicate {@code @Column} or the scalar component field rather than
 *       renaming a column to make the clash disappear.</li>
 *   <li><strong>An identifier equality assertion fails.</strong> The two same named {@code TRAN-CAT-KEY}
 *       groups were unified into one Java type. They are different keys of different clusters and must stay
 *       disjoint; the duplication of a few lines of {@code equals} across three value types is the intended
 *       outcome.</li>
 *   <li><strong>The superset assertion fails.</strong> Either a {@code 010005 DEFAULT} row was invented in the
 *       disclosure group fixture or {@code 010005} was deleted from this one. Both are fabrications: restore
 *       the fixtures, which are frozen, and re-read {@code app/cbl/CBACT04C.cbl:L482-L483}.</li>
 * </ul>
 *
 * <p><strong>Scope, stated rather than implied.</strong> The relational DDL for this table is owned by the
 * Flyway migration and is asserted by the schema guard of this same tier; this class deliberately asserts only
 * what the copybook, the catalogue, the CICS definitions, the interest program and the fixtures prove, and
 * invents no column, constraint or index beyond them. One thing genuinely has no source to assert: because the
 * dataset is batch only, no BMS mapset or symbolic map describes it, so a screen field contract for
 * {@code TRANCATG} is <em>Not available</em> - and none is invented here. What would be needed to supply one
 * is a mapset in {@code app/bms} naming the dataset, and there is none.
 *
 * @see TransactionCategory
 * @see TransactionCategoryId
 * @see FixtureLoader
 */
class TransactionCategoryTest {

    /** Relational table of {@code TRAN-CAT-RECORD}, declared by {@code @Table} on the entity. */
    private static final String TABLE_NAME = "transaction_category";

    /** Column of {@code TRAN-CAT-TYPE-DESC PIC X(50)} - app/cpy/CVTRA04Y.cpy:L8. */
    private static final String DESCRIPTION_COLUMN = "tran_cat_type_desc";

    /** First key column, from {@code TRAN-TYPE-CD PIC X(02)} - app/cpy/CVTRA04Y.cpy:L6. */
    private static final String TYPE_CD_COLUMN = "tran_type_cd";

    /** Second key column, from {@code TRAN-CAT-CD PIC 9(04)} - app/cpy/CVTRA04Y.cpy:L7. */
    private static final String CAT_CD_COLUMN = "tran_cat_cd";

    /** {@code RECLN = 60} - app/cpy/CVTRA04Y.cpy:L2, corroborated by AVGLRECL 60 at LISTCAT.txt:L1475. */
    private static final int RECORD_LENGTH = 60;

    /** {@code KEYLEN 6} at LISTCAT.txt:L1475, equal to the 2 + 4 the copybook declares. */
    private static final int KEY_LENGTH = 6;

    /** Width of {@code TRAN-TYPE-CD PIC X(02)}. */
    private static final int TYPE_CD_WIDTH = 2;

    /** Width of {@code TRAN-CAT-CD PIC 9(04)}. */
    private static final int CAT_CD_WIDTH = 4;

    /** Width of {@code TRAN-CAT-TYPE-DESC PIC X(50)}. */
    private static final int DESCRIPTION_WIDTH = 50;

    /** Width of the unmapped {@code FILLER PIC X(04)} at app/cpy/CVTRA04Y.cpy:L9. */
    private static final int FILLER_WIDTH = 4;

    /** One-based first column of {@code TRAN-TYPE-CD}: the key is the record prefix, RKP 0. */
    private static final int TYPE_CD_START_COLUMN = 1;

    /** One-based first column of {@code TRAN-CAT-CD}, immediately after the two byte type code. */
    private static final int CAT_CD_START_COLUMN = 3;

    /** One-based first column of the description, immediately after the six byte key. */
    private static final int DESCRIPTION_START_COLUMN = 7;

    /** One-based first column of the four byte {@code FILLER} tail. */
    private static final int FILLER_START_COLUMN = 57;

    /** The measured content of the {@code FILLER} tail: four literal zero characters, never spaces. */
    private static final String FILLER_IMAGE = "0000";

    /** Copybook member of this entity's record layout. */
    private static final String CATEGORY_COPYBOOK = "CVTRA04Y";

    /** Copybook member of the 17 byte twin that shares the group name {@code TRAN-CAT-KEY}. */
    private static final String BALANCE_COPYBOOK = "CVTRA01Y";

    /** Copybook member of the transaction type record, whose key drops the {@code -CD} suffix. */
    private static final String TYPE_COPYBOOK = "CVTRA03Y";

    /** {@code KEYLEN 17} of the TCATBALF cluster at LISTCAT.txt:L1371, being 11 + 2 + 4. */
    private static final int BALANCE_KEY_LENGTH = 17;

    /** {@code KEYLEN 16} of the DISCGRP cluster, being 10 + 2 + 4. */
    private static final int DISCLOSURE_KEY_LENGTH = 16;

    /** {@code RECLN = 50} of the twin record - app/cpy/CVTRA01Y.cpy:L2, a different record entirely. */
    private static final int BALANCE_RECORD_LENGTH = 50;

    /** Type code the interest job writes - app/cbl/CBACT04C.cbl:L482. */
    private static final String INTEREST_TYPE_CD = "01";

    /** Category code the interest job writes - app/cbl/CBACT04C.cbl:L483, as a decoded value. */
    private static final int INTEREST_CAT_CD = 5;

    /** The same category code as the fixture's four digit zero padded image. */
    private static final String INTEREST_CAT_IMAGE = "0005";

    /** Verbatim description of the pair the interest job writes; asserted exactly, never paraphrased. */
    private static final String INTEREST_DESCRIPTION = "Interest Amount";

    /** Zero-based index of the {@code 010005} row in the fixture, which is its fifth record. */
    private static final int INTEREST_ROW_INDEX = 4;

    /** Measured record count of app/data/ASCII/trancatg.txt. */
    private static final int FIXTURE_ROWS = 18;

    /** Measured byte count of app/data/ASCII/trancatg.txt, being 18 records of 60 plus one terminator each. */
    private static final int FIXTURE_BYTES = 1_098;

    /** Measured row count of the {@code DEFAULT} block of app/data/ASCII/discgrp.txt. */
    private static final int DEFAULT_DISCLOSURE_ROWS = 17;

    /** {@code DIS-ACCT-GROUP-ID PIC X(10)} holding the fallback group, space padded to its full width. */
    private static final String DEFAULT_GROUP_IMAGE = "DEFAULT   ";

    /** One-based column of {@code DIS-TRAN-TYPE-CD} in the disclosure group record. */
    private static final int DISCLOSURE_TYPE_CD_COLUMN = 11;

    /** One-based column of {@code DIS-TRAN-CAT-CD} in the disclosure group record. */
    private static final int DISCLOSURE_CAT_CD_COLUMN = 13;

    /** The single type and category pair the balance fixture carries, on every one of its rows. */
    private static final String BALANCE_FIXTURE_PAIR = "010001";

    /** One-based column of {@code TRANCAT-TYPE-CD}, immediately after {@code TRANCAT-ACCT-ID PIC 9(11)}. */
    private static final int BALANCE_PAIR_COLUMN = 12;

    /** Measured row count of app/data/ASCII/tcatbal.txt. */
    private static final int BALANCE_FIXTURE_ROWS = 50;

    /** The eight CICS file names of app/csd/CARDDEMO.CSD, in definition order. TRANCATG is absent by design. */
    private static final List<String> CICS_FILES = List.of(
            "ACCTDAT", "CARDAIX", "CARDDAT", "CCXREF", "CUSTDAT", "CXACAIX", "TRANSACT", "USRSEC");

    /** The frozen CICS resource definitions, read repository-relative because the runner pins that directory. */
    private static final Path CSD = Path.of("app", "csd", "CARDDEMO.CSD");

    /** The frozen VSAM catalogue listing, the authoritative physical specification. */
    private static final Path CATALOGUE = Path.of("app", "catlg", "LISTCAT.txt");

    /** The entity under test, read as text so that its declared import surface can be asserted. */
    private static final Path ENTITY_SOURCE =
            Path.of("src", "main", "java", "com", "cardemo", "model", "entity", "TransactionCategory.java");

    /** A CICS file definition in the resource definitions, capturing the file name. */
    private static final Pattern DEFINE_FILE = Pattern.compile("^\\s*DEFINE\\s+FILE\\((\\w+)\\)");

    /** An import of a CardDemo type, capturing the fully qualified name. */
    private static final Pattern CARDDEMO_IMPORT = Pattern.compile("^import\\s+(com\\.cardemo\\.[\\w.]+);");

    /** A catalogue entry tally line, such as {@code AIX -------------------3}. */
    private static final String TALLY_PATTERN = "^\\s*%s\\s-+(\\d+)\\s*$";

    /** An alternate index heading in the catalogue, such as {@code 0AIX ----------- AWS.M2....VSAM.AIX}. */
    private static final Pattern AIX_HEADING = Pattern.compile("^0AIX\\s+-+\\s+(\\S+)\\s*$");

    /** The catalogue line that fixes this cluster's geometry, two lines below its cluster association. */
    private static final Pattern KEYLEN_SIX_AVGLRECL_SIXTY = Pattern.compile("KEYLEN-+6\\s+AVGLRECL-+60\\s");

    /** The cluster association line for this dataset - app/catlg/LISTCAT.txt:L1473. */
    private static final String TRANCATG_CLUSTER = "CLUSTER--AWS.M2.CARDDEMO.TRANCATG.VSAM.KSDS";

    /** Offset from the cluster association line to the attributes line carrying KEYLEN and AVGLRECL. */
    private static final int ATTRIBUTES_LINE_OFFSET = 2;

    /** {@code AIX} entries the catalogue tallies at :L3938 - all three belong to other clusters. */
    private static final int AIX_TALLY = 3;

    /** {@code CLUSTER} entries the catalogue tallies at :L3940. */
    private static final int CLUSTER_TALLY = 10;

    /** {@code GDG} entries the catalogue tallies at :L3942. */
    private static final int GDG_TALLY = 7;

    /** {@code PATH} entries the catalogue tallies at :L3946, one per alternate index. */
    private static final int PATH_TALLY = 3;

    /** Mapping annotations that would restate a key column or add a second identity mechanism. */
    private static final List<Class<? extends Annotation>> FORBIDDEN_MAPPINGS = List.of(
            AttributeOverride.class, AttributeOverrides.class, Id.class, IdClass.class, MapsId.class);

    /** Association annotations, none of which may appear: the legacy corpus performs explicit keyed reads. */
    private static final List<Class<? extends Annotation>> FORBIDDEN_ASSOCIATIONS = List.of(
            ManyToOne.class, OneToMany.class, OneToOne.class, ManyToMany.class, JoinColumn.class,
            JoinTable.class);

    /** Field names that would shadow a component the embedded identifier already owns. */
    private static final List<String> FORBIDDEN_SCALAR_FIELDS = List.of(
            "tranTypeCd", "tranCatCd", "typeCode", "categoryCode", "typeCd", "catCd", "version");

    /** Type names that must not appear on the surface: this row holds no money and no floating point value. */
    private static final List<String> FORBIDDEN_SURFACE_TYPES = List.of(
            "java.math.BigDecimal", "float", "double", "java.lang.Float", "java.lang.Double");

    /** Packages the entity must not depend on; its only CardDemo dependency is its own key. */
    private static final List<String> FORBIDDEN_ENTITY_PACKAGES = List.of(
            "com.cardemo.exception.", "com.cardemo.repository.", "com.cardemo.service.",
            "com.cardemo.controller.", "com.cardemo.batch.", "com.cardemo.security.", "com.cardemo.config.",
            "com.cardemo.observability.", "com.cardemo.model.enums.");

    /** The one CardDemo type the entity is permitted to import. */
    private static final String PERMITTED_ENTITY_IMPORT = "com.cardemo.model.key.TransactionCategoryId";

    /**
     * The key of the category the interest job writes, built fresh on every call.
     *
     * @return a new identifier carrying the {@code '01'} and {@code '05'} literals of
     *         app/cbl/CBACT04C.cbl:L482-L483
     */
    private static TransactionCategoryId interestAmountKey() {
        return new TransactionCategoryId(INTEREST_TYPE_CD, INTEREST_CAT_CD);
    }

    /**
     * The whole {@code 010005} row, built fresh on every call so that no test shares mutable state with
     * another.
     *
     * @return a new entity carrying the interest category key and its verbatim description
     */
    private static TransactionCategory interestAmountRow() {
        return new TransactionCategory(interestAmountKey(), INTEREST_DESCRIPTION);
    }

    /**
     * Loads the seed fixture by classpath resource name, proving its declared census on the way.
     *
     * @return an immutable snapshot of all 18 records, trailing padding intact
     */
    private static FixtureLoader.FixtureData categoryFixture() {
        return FixtureLoader.load(FixtureLoader.Fixture.TRANSACTION_CATEGORY);
    }

    /**
     * Returns the persistent members of a mapped type: the instance fields, in declaration order.
     *
     * @param type the mapped type to inspect
     * @return every non-synthetic instance field, static constants and compiler artefacts excluded
     */
    private static List<Field> persistentFields(final Class<?> type) {
        final List<Field> persistent = new ArrayList<>();
        for (final Field field : type.getDeclaredFields()) {
            if (!field.isSynthetic() && !Modifier.isStatic(field.getModifiers())) {
                persistent.add(field);
            }
        }
        return persistent;
    }

    /**
     * Collects the column names a mapped type declares itself, which is how a duplicated mapping is detected.
     *
     * @param type the mapped type to inspect
     * @return the {@code @Column} names on its instance fields, in declaration order
     */
    private static List<String> declaredColumnNames(final Class<?> type) {
        final List<String> columns = new ArrayList<>();
        for (final Field field : persistentFields(type)) {
            final Column column = field.getAnnotation(Column.class);
            if (column != null) {
                columns.add(column.name());
            }
        }
        return columns;
    }

    /**
     * Reports whether an annotation appears on a type or on any of its declared fields.
     *
     * @param type the type to inspect
     * @param annotation the annotation to look for
     * @return {@code true} if the annotation is present at either level
     */
    private static boolean declaresAnywhere(final Class<?> type, final Class<? extends Annotation> annotation) {
        if (type.getAnnotation(annotation) != null) {
            return true;
        }
        for (final Field field : type.getDeclaredFields()) {
            if (field.getAnnotation(annotation) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reads one entry tally from the catalogue's closing census.
     *
     * @param catalogue every line of the catalogue listing
     * @param entryType the entry type to read, such as {@code AIX} or {@code CLUSTER}
     * @return the tally the census reports
     */
    private static int catalogueTally(final List<String> catalogue, final String entryType) {
        final Pattern tally = Pattern.compile(String.format(Locale.ROOT, TALLY_PATTERN, entryType));
        for (final String line : catalogue) {
            final Matcher matched = tally.matcher(line);
            if (matched.matches()) {
                return Integer.parseInt(matched.group(1));
            }
        }
        throw new AssertionError("app/catlg/LISTCAT.txt reports no entry tally for " + entryType
                + "; the closing census at :L3936-L3950 is the source of the alternate index count");
    }

    /**
     * Reads the frozen corpus, which the test runner reaches through the pinned repository-root directory.
     *
     * @param path a repository-relative path
     * @return every line of the file
     * @throws IOException if the file cannot be read; the cause is propagated rather than swallowed
     */
    private static List<String> linesOf(final Path path) throws IOException {
        return Files.readAllLines(path, StandardCharsets.ISO_8859_1);
    }

    /**
     * Right pads text to a fixed width with spaces, the way a {@code PIC X(n)} field stores it.
     *
     * @param text the value to pad
     * @param width the fixed field width
     * @return the value padded to exactly {@code width} characters
     */
    private static String spacePadded(final String text, final int width) {
        final StringBuilder padded = new StringBuilder(text);
        while (padded.length() < width) {
            padded.append(' ');
        }
        return padded.toString();
    }

    /**
     * Collects the type and category pairs a category fixture snapshot holds, as six character images.
     *
     * @param fixture the loaded snapshot
     * @return the pairs in file order, duplicates collapsed
     */
    private static Set<String> categoryPairs(final FixtureLoader.FixtureData fixture) {
        final Set<String> pairs = new LinkedHashSet<>();
        for (int row = 0; row < fixture.recordCount(); row++) {
            pairs.add(fixture.field(row, TYPE_CD_START_COLUMN, TYPE_CD_WIDTH)
                    + fixture.field(row, CAT_CD_START_COLUMN, CAT_CD_WIDTH));
        }
        return pairs;
    }

    @Nested
    @DisplayName("record geometry: 60 bytes over a 6 byte key, with the four byte FILLER mapped nowhere")
    class RecordGeometry {

        @Test
        @DisplayName("the copybook's own widths sum to the catalogued 60 byte record and 6 byte key")
        void copybookWidthsSumToTheCataloguedGeometry() {
            final RecordLayoutCopybook layout = RecordLayoutCopybook.of(CATEGORY_COPYBOOK);

            assertThat(layout.member()).isEqualTo(CATEGORY_COPYBOOK);
            assertThat(layout.widthOf("TRAN-TYPE-CD")).isEqualTo(TYPE_CD_WIDTH);
            assertThat(layout.widthOf("TRAN-CAT-CD")).isEqualTo(CAT_CD_WIDTH);
            assertThat(layout.widthOf("TRAN-CAT-TYPE-DESC")).isEqualTo(DESCRIPTION_WIDTH);
            assertThat(layout.widthOf("TRAN-TYPE-CD") + layout.widthOf("TRAN-CAT-CD"))
                    .as("TRAN-CAT-KEY is 2 + 4, which is the KEYLEN 6 of app/catlg/LISTCAT.txt:L1475")
                    .isEqualTo(KEY_LENGTH);
            assertThat(layout.recordLength())
                    .as("the RECLN = 60 the copybook header states at app/cpy/CVTRA04Y.cpy:L2")
                    .isEqualTo(RECORD_LENGTH);
            assertThat(KEY_LENGTH + DESCRIPTION_WIDTH + FILLER_WIDTH).isEqualTo(RECORD_LENGTH);
        }

        @Test
        @DisplayName("the catalogue fixes the same geometry: KEYLEN 6 with AVGLRECL 60 on the TRANCATG cluster")
        void theCatalogueFixesTheSameGeometry() throws IOException {
            final List<String> catalogue = linesOf(CATALOGUE);

            int association = -1;
            for (int index = 0; index < catalogue.size(); index++) {
                if (catalogue.get(index).contains(TRANCATG_CLUSTER)) {
                    association = index;
                    break;
                }
            }

            assertThat(association)
                    .as("app/catlg/LISTCAT.txt must associate " + TRANCATG_CLUSTER)
                    .isNotNegative();
            final String attributes = catalogue.get(association + ATTRIBUTES_LINE_OFFSET);
            assertThat(KEYLEN_SIX_AVGLRECL_SIXTY.matcher(attributes).find())
                    .as("the attributes line two below the cluster association must read KEYLEN 6 and "
                            + "AVGLRECL 60, but read: " + attributes.strip())
                    .isTrue();
        }

        @Test
        @DisplayName("the entity is an @Entity mapped to transaction_category with two persistent members")
        void theEntityIsMappedToTheExpectedTable() {
            assertThat(TransactionCategory.class.getAnnotation(Entity.class)).isNotNull();
            final Table table = TransactionCategory.class.getAnnotation(Table.class);

            assertThat(table).isNotNull();
            assertThat(table.name()).isEqualTo(TABLE_NAME);
            assertThat(ReflectionCensus.declaredFieldNames(TransactionCategory.class, false))
                    .as("the record has one key and one payload column, so the entity has two members")
                    .containsExactly("id", "categoryDescription");
        }

        @Test
        @DisplayName("exactly ONE non-key column is declared, at the PIC X(50) width and NOT NULL")
        void exactlyOneNonKeyColumnIsDeclared() throws NoSuchFieldException {
            assertThat(declaredColumnNames(TransactionCategory.class)).containsExactly(DESCRIPTION_COLUMN);

            final Column column = TransactionCategory.class
                    .getDeclaredField("categoryDescription")
                    .getAnnotation(Column.class);
            assertThat(column).isNotNull();
            assertThat(column.length())
                    .as("TRAN-CAT-TYPE-DESC PIC X(50) at app/cpy/CVTRA04Y.cpy:L8 is fixed width")
                    .isEqualTo(DESCRIPTION_WIDTH);
            assertThat(column.nullable()).isFalse();
        }

        @Test
        @DisplayName("the four byte FILLER is declared by the copybook and mapped by no column at all")
        void theFillerIsDeclaredInTheCopybookAndMappedNowhere() {
            final RecordLayoutCopybook layout = RecordLayoutCopybook.of(CATEGORY_COPYBOOK);
            final int named = layout.widthOf("TRAN-TYPE-CD") + layout.widthOf("TRAN-CAT-CD")
                    + layout.widthOf("TRAN-CAT-TYPE-DESC");

            assertThat(named).isEqualTo(KEY_LENGTH + DESCRIPTION_WIDTH);
            assertThat(layout.recordLength() - named)
                    .as("FILLER PIC X(04) at app/cpy/CVTRA04Y.cpy:L9 closes the record and is modelled nowhere")
                    .isEqualTo(FILLER_WIDTH);

            final List<String> mapped = new ArrayList<>(declaredColumnNames(TransactionCategory.class));
            mapped.addAll(declaredColumnNames(TransactionCategoryId.class));
            assertThat(mapped)
                    .as("three columns across the entity and its key map 56 of the 60 bytes, and no more")
                    .containsExactly(DESCRIPTION_COLUMN, TYPE_CD_COLUMN, CAT_CD_COLUMN);
        }

        @Test
        @DisplayName("no version column: this is not one of the four entities read for update under contention")
        void noVersionColumnIsDeclared() {
            assertThat(declaresAnywhere(TransactionCategory.class, Version.class))
                    .as("a version column would add a column no source field justifies")
                    .isFalse();
            assertThat(ReflectionCensus.declaredFieldNames(TransactionCategory.class, false))
                    .doesNotContain("version");
        }

        @Test
        @DisplayName("no BigDecimal, float or double anywhere on the surface: this row holds no money")
        void noFinancialTypeAppearsOnTheSurface() {
            final List<String> surface = ReflectionCensus.declaredSurfaceTypeNames(TransactionCategory.class);

            assertThat(surface).isNotEmpty().doesNotContainAnyElementsOf(FORBIDDEN_SURFACE_TYPES);
            assertThat(ReflectionCensus.declaredSurfaceTypeNames(TransactionCategoryId.class))
                    .isNotEmpty()
                    .doesNotContainAnyElementsOf(FORBIDDEN_SURFACE_TYPES);
        }

        @Test
        @DisplayName("the entity is not Serializable, while its composite key is and pins its serial version")
        void theEntityIsNotSerializableWhileItsKeyIs() throws NoSuchFieldException {
            assertThat(Serializable.class.isAssignableFrom(TransactionCategory.class))
                    .as("implementing it would open a Java deserialisation surface for no benefit")
                    .isFalse();
            assertThat(Serializable.class.isAssignableFrom(TransactionCategoryId.class))
                    .as("Jakarta Persistence requires a composite identifier class to be serializable")
                    .isTrue();

            final Field serialVersion = TransactionCategoryId.class.getDeclaredField("serialVersionUID");
            assertThat(Modifier.isPrivate(serialVersion.getModifiers())).isTrue();
            assertThat(Modifier.isStatic(serialVersion.getModifiers())).isTrue();
            assertThat(Modifier.isFinal(serialVersion.getModifiers())).isTrue();
            assertThat(serialVersion.getType()).isEqualTo(long.class);
        }

        @Test
        @DisplayName("the type code is TRAN-TYPE-CD here and TRAN-TYPE in the other 60 byte reference record")
        void theTypeCodeSpellingDiffersBetweenTheTwoSixtyByteRecords() {
            final RecordLayoutCopybook category = RecordLayoutCopybook.of(CATEGORY_COPYBOOK);
            final RecordLayoutCopybook type = RecordLayoutCopybook.of(TYPE_COPYBOOK);

            assertThat(category.declares("TRAN-TYPE-CD")).isTrue();
            assertThat(category.declares("TRAN-TYPE"))
                    .as("app/cpy/CVTRA04Y.cpy:L6 spells the suffix; the neighbouring record does not")
                    .isFalse();
            assertThat(type.declares("TRAN-TYPE"))
                    .as("app/cpy/CVTRA03Y.cpy:L5 drops the -CD suffix, the one exception in the corpus")
                    .isTrue();
            assertThat(type.declares("TRAN-TYPE-CD")).isFalse();
            assertThat(type.widthOf("TRAN-TYPE")).isEqualTo(TYPE_CD_WIDTH);
            assertThat(type.recordLength())
                    .as("both reference records are 60 bytes, which is why the spelling is the only signal")
                    .isEqualTo(RECORD_LENGTH);
        }
    }

    @Nested
    @DisplayName("key mapping: the composite key class owns every key column, and the entity restates none")
    class KeyColumnOwnership {

        @Test
        @DisplayName("the entity declares exactly one @EmbeddedId, of the six byte key type, named id")
        void theEntityDeclaresExactlyOneEmbeddedId() {
            final List<Field> embedded = new ArrayList<>();
            for (final Field field : persistentFields(TransactionCategory.class)) {
                if (field.getAnnotation(EmbeddedId.class) != null) {
                    embedded.add(field);
                }
            }

            assertThat(embedded).hasSize(1);
            assertThat(embedded.get(0).getName()).isEqualTo("id");
            assertThat(embedded.get(0).getType()).isEqualTo(TransactionCategoryId.class);
        }

        @Test
        @DisplayName("no @AttributeOverride, @IdClass, @MapsId, @Id or @Version restates the key mapping")
        void noAnnotationRestatesTheKeyMapping() {
            for (final Class<? extends Annotation> forbidden : FORBIDDEN_MAPPINGS) {
                assertThat(declaresAnywhere(TransactionCategory.class, forbidden))
                        .as("@" + forbidden.getSimpleName() + " would map a key column a second time, which "
                                + "either aborts the Hibernate bootstrap or duplicates the column silently")
                        .isFalse();
            }
        }

        @Test
        @DisplayName("each key column is declared exactly once, and only on the key class")
        void eachKeyColumnIsDeclaredOnceAndOnlyOnTheKeyClass() {
            final List<String> keyColumns = declaredColumnNames(TransactionCategoryId.class);
            final List<String> entityColumns = declaredColumnNames(TransactionCategory.class);

            assertThat(keyColumns)
                    .as("in COBOL declaration order, from app/cpy/CVTRA04Y.cpy:L6 then :L7")
                    .containsExactly(TYPE_CD_COLUMN, CAT_CD_COLUMN);
            assertThat(entityColumns).doesNotContain(TYPE_CD_COLUMN, CAT_CD_COLUMN);
            assertThat(keyColumns).doesNotContainAnyElementsOf(entityColumns);
            assertThat(keyColumns).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("no scalar component field shadows the embedded identifier")
        void noScalarComponentFieldShadowsTheEmbeddedId() {
            assertThat(ReflectionCensus.declaredFieldNames(TransactionCategory.class, false))
                    .doesNotContainAnyElementsOf(FORBIDDEN_SCALAR_FIELDS);
            assertThat(ReflectionCensus.declaredMethodNames(TransactionCategory.class))
                    .as("a pass-through accessor would duplicate the key class's public surface")
                    .doesNotContain("getTranTypeCd", "getTranCatCd", "setTranTypeCd", "setTranCatCd");
        }

        @Test
        @DisplayName("both constructors take their arguments in COBOL declaration order")
        void bothConstructorsTakeTheirArgumentsInCobolOrder() throws NoSuchMethodException {
            assertThat(ReflectionCensus.declaredFieldNames(TransactionCategoryId.class, false))
                    .containsExactly("tranTypeCd", "tranCatCd");
            assertThat(TransactionCategoryId.class.getDeclaredConstructor(String.class, Integer.class)
                    .getParameterTypes())
                    .as("TRAN-TYPE-CD PIC X(02) first, then TRAN-CAT-CD PIC 9(04)")
                    .containsExactly(String.class, Integer.class);
            assertThat(TransactionCategory.class
                    .getDeclaredConstructor(TransactionCategoryId.class, String.class)
                    .getParameterTypes())
                    .as("the key first, then the one non-key column")
                    .containsExactly(TransactionCategoryId.class, String.class);

            final TransactionCategoryId key = interestAmountKey();
            assertThat(key.getTranTypeCd()).isEqualTo(INTEREST_TYPE_CD);
            assertThat(key.getTranCatCd()).isEqualTo(INTEREST_CAT_CD);
        }
    }

    @Nested
    @DisplayName("the TRAN-CAT-KEY collision: one group name, two records, two irreconcilable key types")
    class TranCatKeyCollision {

        @Test
        @DisplayName("the same group name is 6 bytes over two components here and 17 over three in the twin")
        void theGroupNameDenotesTwoDifferentShapes() {
            final RecordLayoutCopybook category = RecordLayoutCopybook.of(CATEGORY_COPYBOOK);
            final RecordLayoutCopybook balance = RecordLayoutCopybook.of(BALANCE_COPYBOOK);

            assertThat(category.fieldNames())
                    .as("app/cpy/CVTRA04Y.cpy:L5-L8, the TRAN- prefixed two component key plus its payload")
                    .containsExactly("TRAN-TYPE-CD", "TRAN-CAT-CD", "TRAN-CAT-TYPE-DESC");
            assertThat(balance.fieldNames())
                    .as("app/cpy/CVTRA01Y.cpy:L5-L9, the TRANCAT- prefixed three component key plus its "
                            + "signed balance")
                    .containsExactly("TRANCAT-ACCT-ID", "TRANCAT-TYPE-CD", "TRANCAT-CD", "TRAN-CAT-BAL");

            assertThat(category.widthOf("TRAN-TYPE-CD") + category.widthOf("TRAN-CAT-CD"))
                    .isEqualTo(KEY_LENGTH);
            assertThat(balance.widthOf("TRANCAT-ACCT-ID") + balance.widthOf("TRANCAT-TYPE-CD")
                    + balance.widthOf("TRANCAT-CD"))
                    .as("11 + 2 + 4, the KEYLEN 17 the catalogue records for the TCATBALF cluster")
                    .isEqualTo(BALANCE_KEY_LENGTH);
            assertThat(category.recordLength()).isEqualTo(RECORD_LENGTH);
            assertThat(balance.recordLength())
                    .as("the twin record is RECLN = 50, a different record of a different cluster")
                    .isEqualTo(BALANCE_RECORD_LENGTH);
            assertThat(BALANCE_KEY_LENGTH).isNotEqualTo(KEY_LENGTH).isNotEqualTo(DISCLOSURE_KEY_LENGTH);
        }

        @Test
        @DisplayName("the 17 byte key spells its third member TRANCAT-CD, never TRANCAT-CAT-CD")
        void theSeventeenByteKeyAbbreviatesItsThirdMember() {
            final RecordLayoutCopybook balance = RecordLayoutCopybook.of(BALANCE_COPYBOOK);

            assertThat(balance.declares("TRANCAT-CD"))
                    .as("app/cpy/CVTRA01Y.cpy:L8 abbreviates, and the abbreviation must never be corrected")
                    .isTrue();
            assertThat(balance.declares("TRANCAT-CAT-CD")).isFalse();
            assertThat(balance.declares("TRAN-CAT-CD"))
                    .as("TRAN-CAT-CD belongs to app/cpy/CVTRA04Y.cpy:L7 and to no other record")
                    .isFalse();
            assertThat(balance.widthOf("TRANCAT-CD")).isEqualTo(CAT_CD_WIDTH);
        }

        @Test
        @DisplayName("neither identifier's equals accepts the other, in both directions")
        void neitherIdentifierAcceptsTheOtherInEquals() {
            final TransactionCategoryId sixByte = interestAmountKey();
            final TransactionCategoryBalanceId seventeenByte =
                    new TransactionCategoryBalanceId(1L, INTEREST_TYPE_CD, INTEREST_CAT_CD);
            final DisclosureGroupId sixteenByte =
                    new DisclosureGroupId(DEFAULT_GROUP_IMAGE, INTEREST_TYPE_CD, INTEREST_CAT_CD);

            assertThat(sixByte).isNotEqualTo(seventeenByte).isNotEqualTo(sixteenByte);
            assertThat(seventeenByte).isNotEqualTo(sixByte).isNotEqualTo(sixteenByte);
            assertThat(sixteenByte).isNotEqualTo(sixByte).isNotEqualTo(seventeenByte);
            assertThat(sixByte).isEqualTo(interestAmountKey());
        }

        @Test
        @DisplayName("the three composite identifiers are mutually non-assignable, sharing only Serializable")
        void theThreeCompositeIdentifiersAreMutuallyNonAssignable() {
            final List<Class<?>> identifiers = List.of(
                    TransactionCategoryId.class, TransactionCategoryBalanceId.class, DisclosureGroupId.class);

            assertThat(CompositeKeys.types())
                    .as("the tier recognises exactly these three composite identifiers, and no fourth type "
                            + "may join them unnoticed and be conflated with one of them")
                    .containsExactlyInAnyOrderElementsOf(identifiers);

            for (final Class<?> one : identifiers) {
                for (final Class<?> other : identifiers) {
                    if (!one.equals(other)) {
                        assertThat(one.isAssignableFrom(other))
                                .as(other.getSimpleName() + " must not be usable where "
                                        + one.getSimpleName() + " is expected")
                                .isFalse();
                    }
                }
                assertThat(one.getSuperclass())
                        .as(one.getSimpleName() + " must have no shared abstract key supertype")
                        .isEqualTo(Object.class);
                assertThat(one.getInterfaces())
                        .as(one.getSimpleName() + " must share no key interface, only Serializable")
                        .containsExactly(Serializable.class);
            }
        }

        @Test
        @DisplayName("the entity accepts only the six byte key type, which is a compile time guarantee")
        void theEntityAcceptsOnlyTheSixByteKeyType() throws NoSuchMethodException {
            final Constructor<TransactionCategory> constructor = TransactionCategory.class
                    .getDeclaredConstructor(TransactionCategoryId.class, String.class);

            assertThat(constructor.getParameterTypes()[0]).isEqualTo(TransactionCategoryId.class);
            assertThat(TransactionCategory.class.getDeclaredConstructors())
                    .as("no overload accepts a wider key type, so the 17 byte key cannot reach this entity")
                    .hasSize(2);
            assertThat(TransactionCategoryId.class.isAssignableFrom(TransactionCategoryBalanceId.class))
                    .isFalse();
            assertThat(interestAmountRow().getId()).isExactlyInstanceOf(TransactionCategoryId.class);
        }
    }

    @Nested
    @DisplayName("the eighteen row seed fixture, measured rather than assumed")
    class SeedFixture {

        @Test
        @DisplayName("the fixture geometry matches the catalogued record length: 18 records of 60, 1098 bytes")
        void theFixtureGeometryMatchesTheCataloguedRecordLength() {
            final FixtureLoader.FixtureData fixture = categoryFixture();

            assertThat(fixture.resourceName()).isEqualTo("trancatg.txt");
            assertThat(fixture.recordWidth()).isEqualTo(RECORD_LENGTH);
            assertThat(fixture.recordCount()).isEqualTo(FIXTURE_ROWS);
            assertThat(fixture.byteCount()).isEqualTo(FIXTURE_BYTES);
            assertThat(fixture.impliedByteCount())
                    .as("18 records of 60 characters plus one terminator each is exactly 1098 bytes")
                    .isEqualTo(fixture.byteCount());
            assertThat(fixture.records()).hasSize(FIXTURE_ROWS).allSatisfy(
                    image -> assertThat(image).hasSize(RECORD_LENGTH));
        }

        @Test
        @DisplayName("every row splits into a two digit type, a four digit category, 50 characters and 0000")
        void everyRowSplitsIntoTheCopybookFields() {
            final FixtureLoader.FixtureData fixture = categoryFixture();

            for (int row = 0; row < fixture.recordCount(); row++) {
                final String description = fixture.field(row, DESCRIPTION_START_COLUMN, DESCRIPTION_WIDTH);

                assertThat(fixture.field(row, TYPE_CD_START_COLUMN, TYPE_CD_WIDTH))
                        .as("TRAN-TYPE-CD of row " + row)
                        .matches("\\d{" + TYPE_CD_WIDTH + "}");
                assertThat(fixture.field(row, CAT_CD_START_COLUMN, CAT_CD_WIDTH))
                        .as("TRAN-CAT-CD of row " + row)
                        .matches("\\d{" + CAT_CD_WIDTH + "}");
                assertThat(description)
                        .as("TRAN-CAT-TYPE-DESC of row " + row + " is space padded to its full PIC width")
                        .hasSize(DESCRIPTION_WIDTH)
                        .isEqualTo(spacePadded(description.stripTrailing(), DESCRIPTION_WIDTH))
                        .isNotBlank();
                assertThat(fixture.field(row, FILLER_START_COLUMN, FILLER_WIDTH))
                        .as("FILLER of row " + row)
                        .isEqualTo(FILLER_IMAGE);
            }
        }

        @Test
        @DisplayName("the FILLER tail is zero filled, not space filled, so the record has no trailing blank")
        void theFillerTailIsZeroFilledRatherThanSpaceFilled() {
            final FixtureLoader.FixtureData fixture = categoryFixture();

            for (int row = 0; row < fixture.recordCount(); row++) {
                final String filler = fixture.field(row, FILLER_START_COLUMN, FILLER_WIDTH);
                assertThat(filler).isEqualTo(FILLER_IMAGE).doesNotContain(" ");
                assertThat(filler.chars().allMatch(character -> character == '0'))
                        .as("row " + row + ": a writer that space pads the filler cannot reproduce this "
                                + "fixture byte for byte")
                        .isTrue();
                assertThat(fixture.recordAt(row))
                        .as("row " + row + " therefore ends in a zero, never in a blank")
                        .doesNotEndWith(" ");
            }
        }

        @Test
        @DisplayName("the type code census is 5, 3, 3, 3, 1, 2, 1 in file order and sums to eighteen")
        void theTypeCodeCensusIsExactlyAsMeasured() {
            final FixtureLoader.FixtureData fixture = categoryFixture();
            final Map<String, Integer> census = new LinkedHashMap<>();
            for (int row = 0; row < fixture.recordCount(); row++) {
                census.merge(fixture.field(row, TYPE_CD_START_COLUMN, TYPE_CD_WIDTH), 1, Integer::sum);
            }

            final Map<String, Integer> expected = new LinkedHashMap<>();
            expected.put("01", 5);
            expected.put("02", 3);
            expected.put("03", 3);
            expected.put("04", 3);
            expected.put("05", 1);
            expected.put("06", 2);
            expected.put("07", 1);

            assertThat(census).isEqualTo(expected);
            assertThat(census.keySet())
                    .as("the fixture is stored in ascending key order, which is what makes a VSAM browse work")
                    .containsExactlyElementsOf(expected.keySet());
            assertThat(census.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(FIXTURE_ROWS);
        }

        @Test
        @DisplayName("every type code resolves against the seven transaction types, with no orphan either way")
        void everyTypeCodeResolvesAgainstTheTransactionTypes() {
            final FixtureLoader.FixtureData types = FixtureLoader.load(FixtureLoader.Fixture.TRANSACTION_TYPE);
            final Set<String> declared = new LinkedHashSet<>();
            for (int row = 0; row < types.recordCount(); row++) {
                declared.add(types.field(row, TYPE_CD_START_COLUMN, TYPE_CD_WIDTH));
            }

            final FixtureLoader.FixtureData fixture = categoryFixture();
            final Set<String> used = new LinkedHashSet<>();
            for (int row = 0; row < fixture.recordCount(); row++) {
                used.add(fixture.field(row, TYPE_CD_START_COLUMN, TYPE_CD_WIDTH));
            }

            assertThat(declared).containsExactly("01", "02", "03", "04", "05", "06", "07");
            assertThat(used)
                    .as("no category names a type code the transaction type file does not declare")
                    .isEqualTo(declared);
        }

        @Test
        @DisplayName("the eighteen pairs are a strict superset of the seventeen DEFAULT disclosure pairs")
        void theEighteenPairsAreAStrictSupersetOfTheDefaultDisclosurePairs() {
            final FixtureLoader.FixtureData disclosure =
                    FixtureLoader.load(FixtureLoader.Fixture.DISCLOSURE_GROUP);
            final Set<String> fallbackPairs = new LinkedHashSet<>();
            for (int row = 0; row < disclosure.recordCount(); row++) {
                if (DEFAULT_GROUP_IMAGE.equals(
                        disclosure.field(row, TYPE_CD_START_COLUMN, DEFAULT_GROUP_IMAGE.length()))) {
                    fallbackPairs.add(disclosure.field(row, DISCLOSURE_TYPE_CD_COLUMN, TYPE_CD_WIDTH)
                            + disclosure.field(row, DISCLOSURE_CAT_CD_COLUMN, CAT_CD_WIDTH));
                }
            }

            final Set<String> seededPairs = categoryPairs(categoryFixture());

            assertThat(fallbackPairs).hasSize(DEFAULT_DISCLOSURE_ROWS);
            assertThat(seededPairs).hasSize(FIXTURE_ROWS).containsAll(fallbackPairs);
            assertThat(seededPairs.size() - fallbackPairs.size())
                    .as("the asymmetry is exactly one pair, and it is deliberate")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("the one extra pair is 010005, Interest Amount, which the interest job writes as output")
        void theOneExtraPairIsTheInterestCategoryTheBatchJobWrites() {
            final FixtureLoader.FixtureData disclosure =
                    FixtureLoader.load(FixtureLoader.Fixture.DISCLOSURE_GROUP);
            final Set<String> fallbackPairs = new LinkedHashSet<>();
            for (int row = 0; row < disclosure.recordCount(); row++) {
                if (DEFAULT_GROUP_IMAGE.equals(
                        disclosure.field(row, TYPE_CD_START_COLUMN, DEFAULT_GROUP_IMAGE.length()))) {
                    fallbackPairs.add(disclosure.field(row, DISCLOSURE_TYPE_CD_COLUMN, TYPE_CD_WIDTH)
                            + disclosure.field(row, DISCLOSURE_CAT_CD_COLUMN, CAT_CD_WIDTH));
                }
            }

            final Set<String> extra = new LinkedHashSet<>(categoryPairs(categoryFixture()));
            extra.removeAll(fallbackPairs);

            assertThat(extra)
                    .as("app/cbl/CBACT04C.cbl:L482-L483 moves '01' and '05' into the type and category of "
                            + "every synthetic interest transaction, so 010005 is an output category and the "
                            + "rate lookup at :L415-L440 never receives it. Neither adding a 010005 DEFAULT "
                            + "row nor deleting 010005 from here is permitted.")
                    .containsExactly(INTEREST_TYPE_CD + INTEREST_CAT_IMAGE);
        }

        @Test
        @DisplayName("the 010005 row carries the verbatim description Interest Amount and round trips intact")
        void theInterestRowRoundTripsThroughTheEntityWithItsPaddingIntact() {
            final FixtureLoader.FixtureData fixture = categoryFixture();
            final String typeCd = fixture.field(INTEREST_ROW_INDEX, TYPE_CD_START_COLUMN, TYPE_CD_WIDTH);
            final String catCd = fixture.field(INTEREST_ROW_INDEX, CAT_CD_START_COLUMN, CAT_CD_WIDTH);
            final String stored = fixture.field(INTEREST_ROW_INDEX, DESCRIPTION_START_COLUMN, DESCRIPTION_WIDTH);

            assertThat(typeCd).isEqualTo(INTEREST_TYPE_CD);
            assertThat(catCd).isEqualTo(INTEREST_CAT_IMAGE);
            assertThat(stored)
                    .as("the description is stored space padded to its full PIC X(50) width")
                    .isEqualTo(spacePadded(INTEREST_DESCRIPTION, DESCRIPTION_WIDTH));
            assertThat(stored.strip()).isEqualTo(INTEREST_DESCRIPTION);

            final TransactionCategory row = new TransactionCategory(
                    new TransactionCategoryId(typeCd, Integer.valueOf(catCd)), stored);

            assertThat(row.getId())
                    .as("the four digit image decodes to the literal the interest job moves at :L483")
                    .isEqualTo(interestAmountKey());
            assertThat(row.getCategoryDescription())
                    .as("the entity never trims: the padding is the faithful PIC X(50) image")
                    .isEqualTo(stored)
                    .hasSize(DESCRIPTION_WIDTH);
        }

        @Test
        @DisplayName("the balance fixture exercises exactly one of the eighteen categories, on all 50 rows")
        void theBalanceFixtureExercisesExactlyOneCategory() {
            final FixtureLoader.FixtureData balances =
                    FixtureLoader.load(FixtureLoader.Fixture.TRANSACTION_CATEGORY_BALANCE);
            final Set<String> pairs = new LinkedHashSet<>();
            for (int row = 0; row < balances.recordCount(); row++) {
                pairs.add(balances.field(row, BALANCE_PAIR_COLUMN, KEY_LENGTH));
            }

            assertThat(balances.recordCount()).isEqualTo(BALANCE_FIXTURE_ROWS);
            assertThat(pairs)
                    .as("TRANCAT-TYPE-CD and TRANCAT-CD sit at columns 12-17, after TRANCAT-ACCT-ID PIC 9(11)")
                    .containsExactly(BALANCE_FIXTURE_PAIR);
            assertThat(categoryPairs(categoryFixture()))
                    .as("that single pair is one of the eighteen, and the claim is not widened beyond it")
                    .contains(BALANCE_FIXTURE_PAIR);
        }

        @Test
        @DisplayName("the descriptions are printable ASCII and carry letters a global overpunch would corrupt")
        void theDescriptionsCarryLettersAGlobalOverpunchDecodeWouldCorrupt() {
            final FixtureLoader.FixtureData fixture = categoryFixture();
            int overpunchLetters = 0;
            for (int row = 0; row < fixture.recordCount(); row++) {
                for (final char character
                        : fixture.field(row, DESCRIPTION_START_COLUMN, DESCRIPTION_WIDTH).toCharArray()) {
                    assertThat((int) character)
                            .as("row " + row + " must hold printable 7 bit ASCII only")
                            .isBetween((int) ' ', (int) '~');
                    if (character >= 'A' && character <= 'R') {
                        overpunchLetters++;
                    }
                }
            }

            assertThat(overpunchLetters)
                    .as("letters in the A to R range occur legitimately as text, so a blanket substitution "
                            + "would silently corrupt the descriptions")
                    .isPositive();
        }

        @Test
        @DisplayName("this record declares no signed field, while its 17 byte twin declares one")
        void thisRecordDeclaresNoSignedFieldWhileItsTwinDoes() {
            final RecordLayoutCopybook category = RecordLayoutCopybook.of(CATEGORY_COPYBOOK);
            for (final String field : category.fieldNames()) {
                assertThat(category.geometry(field).signed())
                        .as(field + " must not be signed: an overpunch decode has no business here")
                        .isFalse();
            }

            final RecordLayoutCopybook balance = RecordLayoutCopybook.of(BALANCE_COPYBOOK);
            assertThat(balance.geometry("TRAN-CAT-BAL").signed())
                    .as("TRAN-CAT-BAL PIC S9(09)V99 is where a position aware decode does belong")
                    .isTrue();
        }

        @Test
        @DisplayName("applying the zoned decimal decoder to a description is rejected, with its cause kept")
        void theDescriptionColumnIsRejectedByTheZonedDecimalDecoder() {
            final FixtureLoader.FixtureData fixture = categoryFixture();

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> fixture.signedDecimal(
                            INTEREST_ROW_INDEX, DESCRIPTION_START_COLUMN, DESCRIPTION_WIDTH))
                    .withMessageContaining(FixtureLoader.Fixture.TRANSACTION_CATEGORY.resourceName())
                    .withMessageContaining("does not hold a valid zoned decimal at columns "
                            + DESCRIPTION_START_COLUMN + "-"
                            + (DESCRIPTION_START_COLUMN + DESCRIPTION_WIDTH - 1))
                    .withMessageContaining("must be a digit")
                    .withCauseInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("batch only dataset: no CICS definition, no alternate index, no association, no dependency")
    class BatchOnlyIsolation {

        @Test
        @DisplayName("the CICS resource definitions name eight files, and TRANCATG is not one of them")
        void theCicsResourceDefinitionsDoNotNameThisDataset() throws IOException {
            final List<String> files = new ArrayList<>();
            for (final String line : linesOf(CSD)) {
                final Matcher matched = DEFINE_FILE.matcher(line);
                if (matched.find()) {
                    files.add(matched.group(1));
                }
            }

            assertThat(files)
                    .as("app/csd/CARDDEMO.CSD is the online file control table in full")
                    .containsExactlyElementsOf(CICS_FILES);
            assertThat(files)
                    .as("TRANCATG, TCATBALF, DISCGRP and TRANTYPE are batch only, so no endpoint fronts them "
                            + "and no authorisation rule is owed for them")
                    .doesNotContain("TRANCATG", "TCATBALF", "DISCGRP", "TRANTYPE");
        }

        @Test
        @DisplayName("the catalogue tallies three alternate indexes, and none of them belongs to TRANCATG")
        void theCatalogueTalliesThreeAlternateIndexesNoneOfThemHere() throws IOException {
            final List<String> catalogue = linesOf(CATALOGUE);

            assertThat(catalogueTally(catalogue, "AIX")).isEqualTo(AIX_TALLY);
            assertThat(catalogueTally(catalogue, "CLUSTER")).isEqualTo(CLUSTER_TALLY);
            assertThat(catalogueTally(catalogue, "GDG")).isEqualTo(GDG_TALLY);
            assertThat(catalogueTally(catalogue, "PATH"))
                    .as("one path per alternate index, which is why the two tallies agree")
                    .isEqualTo(PATH_TALLY);

            final List<String> alternateIndexes = new ArrayList<>();
            for (final String line : catalogue) {
                final Matcher matched = AIX_HEADING.matcher(line);
                if (matched.matches()) {
                    alternateIndexes.add(matched.group(1));
                }
            }

            assertThat(alternateIndexes).containsExactly(
                    "AWS.M2.CARDDEMO.CARDDATA.VSAM.AIX",
                    "AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX",
                    "AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX");
            assertThat(alternateIndexes)
                    .as("so the second migration owes this table no index beyond its primary key")
                    .noneMatch(name -> name.contains("TRANCATG"));
        }

        @Test
        @DisplayName("the entity declares no JPA association of any kind, so there is no lazy proxy to fetch")
        void theEntityDeclaresNoAssociation() {
            for (final Class<? extends Annotation> association : FORBIDDEN_ASSOCIATIONS) {
                assertThat(declaresAnywhere(TransactionCategory.class, association))
                        .as("@" + association.getSimpleName() + " would replace the corpus's explicit keyed "
                                + "reads with navigation, and a reverse collection over the transaction "
                                + "table would be an unbounded fetch")
                        .isFalse();
            }
        }

        @Test
        @DisplayName("the only CardDemo type the entity depends on is its own composite key")
        void theOnlyCardDemoTypeTheEntityDependsOnIsItsKey() {
            final Set<String> dependencies = new LinkedHashSet<>();
            for (final String name : ReflectionCensus.declaredSurfaceTypeNames(TransactionCategory.class)) {
                if (name.startsWith("com.cardemo.")) {
                    dependencies.add(name);
                }
            }

            assertThat(dependencies).containsExactly(PERMITTED_ENTITY_IMPORT);
        }

        @Test
        @DisplayName("the entity imports nothing from the service, batch, security or infrastructure packages")
        void theEntityImportsNothingFromTheForbiddenPackages() throws IOException {
            final List<String> imports = new ArrayList<>();
            for (final String line : linesOf(ENTITY_SOURCE)) {
                final Matcher matched = CARDDEMO_IMPORT.matcher(line);
                if (matched.find()) {
                    imports.add(matched.group(1));
                }
            }

            assertThat(imports)
                    .as("a reference entity that reached into another layer would invert the dependency")
                    .containsExactly(PERMITTED_ENTITY_IMPORT);
            for (final String forbidden : FORBIDDEN_ENTITY_PACKAGES) {
                assertThat(imports)
                        .as("no import may come from " + forbidden)
                        .noneMatch(name -> name.startsWith(forbidden));
            }
        }
    }

    @Nested
    @DisplayName("identity and rendering: value equality over the key, and a rendering that leaks nothing")
    class IdentityAndRendering {

        @Test
        @DisplayName("the rendering names the type and carries the key and the description, and nothing else")
        void theRenderingCarriesTheKeyAndTheDescriptionOnly() {
            final TransactionCategory row = interestAmountRow();

            assertThat(row).hasToString("TransactionCategory[id=TransactionCategoryId[tranTypeCd="
                    + INTEREST_TYPE_CD + ", tranCatCd=" + INTEREST_CAT_CD + "], categoryDescription="
                    + INTEREST_DESCRIPTION + "]");
            assertThat(row.toString())
                    .as("this row holds neither a credential nor personal data, so the whole of it may be "
                            + "rendered, which is deliberately unlike the customer and card rows")
                    .startsWith(TransactionCategory.class.getSimpleName() + "[")
                    .contains(INTEREST_DESCRIPTION)
                    .doesNotContain("password", "secret", "token");
        }

        @Test
        @DisplayName("identity is the key alone, so two rows differing only in description are equal")
        void identityIsTheKeyAlone() {
            final TransactionCategory first = interestAmountRow();
            final TransactionCategory second = new TransactionCategory(
                    interestAmountKey(), spacePadded(INTEREST_DESCRIPTION, DESCRIPTION_WIDTH));

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
            assertThat(first.getCategoryDescription()).isNotEqualTo(second.getCategoryDescription());
        }

        @ParameterizedTest
        @CsvSource({"02, 5", "01, 4"})
        @DisplayName("two rows differ when either key component differs, so identity is total over both")
        void rowsDifferWhenEitherKeyComponentDiffers(final String typeCd, final int catCd) {
            final TransactionCategory other = new TransactionCategory(
                    new TransactionCategoryId(typeCd, catCd), INTEREST_DESCRIPTION);

            assertThat(other).isNotEqualTo(interestAmountRow());
        }

        @Test
        @DisplayName("equality is reflexive, symmetric, transitive and stable under repeated invocation")
        void equalityIsReflexiveSymmetricTransitiveAndStable() {
            final TransactionCategory first = interestAmountRow();
            final TransactionCategory second = interestAmountRow();
            final TransactionCategory third = interestAmountRow();

            assertThat(first).isEqualTo(first);
            assertThat(first.equals(second)).isTrue();
            assertThat(second.equals(first)).isTrue();
            assertThat(second.equals(third)).isTrue();
            assertThat(first.equals(third)).isTrue();
            assertThat(first.equals(second)).isEqualTo(first.equals(second));
            assertThat(first.hashCode()).isEqualTo(first.hashCode()).isEqualTo(second.hashCode());
        }

        @Test
        @DisplayName("equality is null safe and type safe, including against the two neighbouring key types")
        void equalityIsNullSafeAndTypeSafe() {
            final TransactionCategory row = interestAmountRow();

            assertThat(row.equals(null)).isFalse();
            assertThat(row).isNotEqualTo(interestAmountKey());
            assertThat(row).isNotEqualTo(new TransactionCategoryBalanceId(
                    1L, INTEREST_TYPE_CD, INTEREST_CAT_CD));
            assertThat(row).isNotEqualTo(new DisclosureGroupId(
                    DEFAULT_GROUP_IMAGE, INTEREST_TYPE_CD, INTEREST_CAT_CD));
            assertThat(row).isNotEqualTo(INTEREST_DESCRIPTION);
        }

        @Test
        @DisplayName("hash and equality agree, so a map collapses two rows carrying the same key")
        void hashAndEqualityAgreeInsideAMap() {
            final Map<TransactionCategory, String> byRow = new LinkedHashMap<>();
            byRow.put(interestAmountRow(), "first");
            byRow.put(interestAmountRow(), "second");

            assertThat(byRow).hasSize(1).containsValue("second");
            assertThat(byRow.keySet()).containsExactly(interestAmountRow());
        }

        @Test
        @DisplayName("two unsaved rows carrying no key stay distinct, which is the safe identity policy")
        void twoUnsavedRowsCarryingNoKeyStayDistinct() throws ReflectiveOperationException {
            final Constructor<TransactionCategory> providerConstructor =
                    TransactionCategory.class.getDeclaredConstructor();

            assertThat(Modifier.isProtected(providerConstructor.getModifiers()))
                    .as("Jakarta Persistence needs a no argument constructor, but a public one would offer "
                            + "application code an unguarded way to build a row")
                    .isTrue();
            providerConstructor.setAccessible(true);
            final TransactionCategory first = providerConstructor.newInstance();
            final TransactionCategory second = providerConstructor.newInstance();

            assertThat(first.getId()).isNull();
            assertThat(first.getCategoryDescription()).isNull();
            assertThat(first).isEqualTo(first).isNotEqualTo(second);
            assertThat(first.hashCode()).isZero();
            assertThat(Set.of(first, second))
                    .as("collapsing two unsaved rows into one would be an operational hazard")
                    .hasSize(2);
        }
    }

    @Nested
    @DisplayName("untrusted input: every guard exercised at and beyond its boundary, message and cause asserted")
    class HostileInput {

        @Test
        @DisplayName("a null identifier is rejected, naming the COBOL group, with no cause fabricated")
        void aNullIdentifierIsRejected() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new TransactionCategory(null, INTEREST_DESCRIPTION))
                    .withMessageContaining("TRAN-CAT-KEY")
                    .withMessageContaining("must not be null")
                    .withNoCause();
        }

        @Test
        @DisplayName("a null description is rejected, naming the PIC clause, with no cause fabricated")
        void aNullDescriptionIsRejected() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new TransactionCategory(interestAmountKey(), null))
                    .withMessageContaining("TRAN-CAT-TYPE-DESC PIC X(50)")
                    .withMessageContaining("must not be null")
                    .withNoCause();
        }

        @Test
        @DisplayName("a 51 character description is rejected and the message reports the width received")
        void aDescriptionWiderThanItsPicClauseIsRejected() {
            final String tooWide = "A".repeat(DESCRIPTION_WIDTH + 1);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new TransactionCategory(interestAmountKey(), tooWide))
                    .withMessageContaining("must be at most " + DESCRIPTION_WIDTH + " characters")
                    .withMessageContaining("but was " + tooWide.length())
                    .withNoCause();
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 1, DESCRIPTION_WIDTH - 1, DESCRIPTION_WIDTH})
        @DisplayName("every description width up to and including the PIC width of 50 is accepted verbatim")
        void everyDescriptionWidthUpToThePicWidthIsAccepted(final int width) {
            final String description = "X".repeat(width);
            final TransactionCategory row = new TransactionCategory(interestAmountKey(), description);

            assertThat(row.getCategoryDescription()).isEqualTo(description).hasSize(width);
        }

        @Test
        @DisplayName("a description of nothing but spaces is accepted, because PIC X(50) can hold one")
        void aBlankDescriptionIsAcceptedBecauseTheSourceCanHoldOne() {
            final String blank = " ".repeat(DESCRIPTION_WIDTH);
            final TransactionCategory row = new TransactionCategory(interestAmountKey(), blank);

            assertThat(row.getCategoryDescription())
                    .as("a blankness constraint would reject data the system of record accepts")
                    .isEqualTo(blank)
                    .isBlank()
                    .hasSize(DESCRIPTION_WIDTH);
        }

        @Test
        @DisplayName("a description of nothing but A to R letters round trips completely unchanged")
        void aDescriptionOfOverpunchLettersRoundTripsUnchanged() {
            final String letters = "ABCDEFGHIJKLMNOPQR";
            final TransactionCategory row = new TransactionCategory(
                    interestAmountKey(), spacePadded(letters, DESCRIPTION_WIDTH));

            assertThat(row.getCategoryDescription())
                    .as("no character is reinterpreted as a sign overpunch on the way in or out")
                    .isEqualTo(spacePadded(letters, DESCRIPTION_WIDTH))
                    .startsWith(letters)
                    .hasSize(DESCRIPTION_WIDTH);
        }

        @Test
        @DisplayName("the setters re-apply the guards, and a rejected mutation leaves the row untouched")
        void theSettersReapplyTheGuardsAndLeaveTheRowUntouchedWhenTheyReject() {
            final TransactionCategory row = interestAmountRow();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> row.setId(null))
                    .withMessageContaining("TRAN-CAT-KEY")
                    .withNoCause();
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> row.setCategoryDescription(null))
                    .withMessageContaining("TRAN-CAT-TYPE-DESC PIC X(50)")
                    .withNoCause();
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> row.setCategoryDescription("B".repeat(DESCRIPTION_WIDTH + 1)))
                    .withMessageContaining("must be at most " + DESCRIPTION_WIDTH + " characters")
                    .withNoCause();

            assertThat(row.getId()).isEqualTo(interestAmountKey());
            assertThat(row.getCategoryDescription()).isEqualTo(INTEREST_DESCRIPTION);

            row.setCategoryDescription(spacePadded(INTEREST_DESCRIPTION, DESCRIPTION_WIDTH));
            assertThat(row.getCategoryDescription()).hasSize(DESCRIPTION_WIDTH);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "0", "1", "001", "0100"})
        @DisplayName("a type code that is not exactly two characters is rejected: the guard is exact")
        void aTypeCodeOfTheWrongWidthIsRejected(final String wrongWidth) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new TransactionCategoryId(wrongWidth, INTEREST_CAT_CD))
                    .withMessageContaining("TRAN-TYPE-CD PIC X(02)")
                    .withMessageContaining("must be exactly " + TYPE_CD_WIDTH + " characters")
                    .withNoCause();
        }

        @Test
        @DisplayName("a two character type code of spaces is accepted, because PIC X(02) can hold one")
        void aTwoCharacterTypeCodeOfSpacesIsAccepted() {
            final TransactionCategory row = new TransactionCategory(
                    new TransactionCategoryId("  ", INTEREST_CAT_CD), INTEREST_DESCRIPTION);

            assertThat(row.getId().getTranTypeCd()).isBlank().hasSize(TYPE_CD_WIDTH);
        }

        @ParameterizedTest
        @ValueSource(ints = {-1, -9999, 10_000, 99_999})
        @DisplayName("a category code outside the unsigned four digit domain is rejected at both ends")
        void aCategoryCodeOutsideTheFourDigitDomainIsRejected(final int outOfDomain) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new TransactionCategoryId(INTEREST_TYPE_CD, outOfDomain))
                    .withMessageContaining("TRAN-CAT-CD PIC 9(04)")
                    .withMessageContaining("but was " + outOfDomain)
                    .withNoCause();
        }

        @Test
        @DisplayName("a three digit category code is legal as a value and zero pads back to four digits")
        void aThreeDigitCategoryCodeIsLegalAndZeroPadsBackToFourDigits() {
            final int threeDigitCode = 999;
            final TransactionCategoryId key = new TransactionCategoryId(INTEREST_TYPE_CD, threeDigitCode);

            assertThat(key.getTranCatCd())
                    .as("PIC 9(04) bounds a value range, not a decimal string width")
                    .isEqualTo(threeDigitCode);
            assertThat(String.format(Locale.ROOT, "%0" + CAT_CD_WIDTH + "d", key.getTranCatCd()))
                    .as("any fixed width rendering must pad it back, or the 60 byte geometry breaks")
                    .isEqualTo("0999")
                    .hasSize(CAT_CD_WIDTH);
        }

        @Test
        @DisplayName("a 59 or 61 character record is rejected by the width census, which is the only guard")
        void aRecordOfTheWrongWidthIsRejectedByTheWidthCensus() {
            final String resource = FixtureLoader.Fixture.TRANSACTION_CATEGORY.resourceName();

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> FixtureLoader.loadResource(resource, RECORD_LENGTH - 1))
                    .withMessageContaining("is " + RECORD_LENGTH + " characters wide but every record must "
                            + "be exactly " + (RECORD_LENGTH - 1));
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> FixtureLoader.loadResource(resource, RECORD_LENGTH + 1))
                    .withMessageContaining("is " + RECORD_LENGTH + " characters wide but every record must "
                            + "be exactly " + (RECORD_LENGTH + 1));
        }

        @Test
        @DisplayName("a slice past the record and an index past the last row are both rejected by offset")
        void aSlicePastTheRecordAndAnIndexPastTheLastRowAreRejected() {
            final FixtureLoader.FixtureData fixture = categoryFixture();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> fixture.field(0, FILLER_START_COLUMN, FILLER_WIDTH + 1))
                    .withMessageContaining("runs past the " + RECORD_LENGTH + "-character record 0");
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> fixture.recordAt(FIXTURE_ROWS))
                    .withMessageContaining("record index " + FIXTURE_ROWS + " is outside "
                            + FixtureLoader.Fixture.TRANSACTION_CATEGORY.resourceName());
        }
    }
}

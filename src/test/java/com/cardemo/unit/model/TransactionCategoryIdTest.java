/*
 * ******************************************************************
 * Program     : TransactionCategoryIdTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM tier, no container, no
 *               Spring context, no database, no live endpoint
 * Function    : Holds com.cardemo.model.key.TransactionCategoryId to
 *               the TRAN-CAT-KEY field contract of the transaction
 *               category record - two components in COBOL declaration
 *               order summing to the catalogued key length of 6, the
 *               complete equals and hashCode contract, and above all
 *               the distinctness of this key from the identically
 *               named but three component, 17 byte TRAN-CAT-KEY group
 *               of app/cpy/CVTRA01Y.cpy.
 * Source      : app/cpy/CVTRA04Y.cpy (key 6) @ 7756d89
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

import com.cardemo.model.key.DisclosureGroupId;
import com.cardemo.model.key.TransactionCategoryBalanceId;
import com.cardemo.model.key.TransactionCategoryId;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.IOException;
import java.io.ObjectStreamClass;
import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit test for {@link TransactionCategoryId}, the {@code @Embeddable} replacement for the two component
 * {@code TRAN-CAT-KEY} group of the transaction category record layout.
 *
 * <h2>1. What this test does, and the evidence it rests on</h2>
 *
 * <p>It holds {@link TransactionCategoryId} to the field contract of the frozen legacy corpus. Every
 * structural assertion is anchored in a file rather than in a retyped literal, because a literal proves only
 * that it agrees with itself. The four locators this class reads directly, all at the traceability anchor
 * commit {@code 7756d89}:
 *
 * <ol>
 *   <li>{@code app/cpy/CVTRA04Y.cpy} - the record layout. Line 5 opens {@code 05 TRAN-CAT-KEY.} and lines 6
 *       and 7 declare its only two subordinate items, {@code 10 TRAN-TYPE-CD PIC X(02).} then
 *       {@code 10 TRAN-CAT-CD PIC 9(04).} The test parses those PIC clauses out of the file and proves the
 *       widths are 2 and 4, so the key length is 6.</li>
 *   <li>{@code app/catlg/LISTCAT.txt:L1475} - the VSAM catalogue, which records
 *       {@code KEYLEN-----------------6} and {@code AVGLRECL--------------60} for the cluster named at
 *       {@code app/catlg/LISTCAT.txt:L1473}, {@code CLUSTER--AWS.M2.CARDDEMO.TRANCATG.VSAM.KSDS}.
 *       {@code app/catlg/LISTCAT.txt:L1476} adds {@code RKP--------------------0}, which places the key at
 *       the front of the record, and the following line shows the cluster is {@code UNIQUE} and
 *       {@code INDEXED}. This is an independent confirmation of the copybook arithmetic, from a different
 *       artefact produced by a different tool.</li>
 *   <li>{@code app/cbl/CBTRN03C.cbl:L108} - the consumer. That line is exactly {@code COPY CVTRA04Y.}, which
 *       is how the transaction report program resolves a category description. The test asserts the line so
 *       that the provenance recorded here cannot rot.</li>
 *   <li>{@code app/data/ASCII/trancatg.txt} - the seed fixture, read through {@link FixtureLoader} by
 *       classpath resource name only. All 18 records are exactly 60 bytes wide, the first is
 *       {@code 010001Regular Sales Draft}, and every one of the 18 keys is built through the identifier under
 *       test so that the accepted domain is the domain the data actually occupies.</li>
 * </ol>
 *
 * <p>The record length is 60, which is {@code 6 + 50 + 4}: the key, then {@code TRAN-CAT-TYPE-DESC PIC X(50)},
 * then a four byte {@code FILLER}. Note that 60 differs from the 50 of the other two composite key clusters.
 * Record length is the owning entity's concern and is asserted in the entity's own test; the only length this
 * file asserts is the key length of 6.
 *
 * <h2>2. The collision hazard this file exists to prevent</h2>
 *
 * <p><strong>The COBOL group name {@code TRAN-CAT-KEY} is declared at line 5 of two different copybooks, and
 * the identically named group in {@code app/cpy/CVTRA01Y.cpy} is a different key altogether - 17 bytes over
 * three fields, {@code TRANCAT-ACCT-ID PIC 9(11)} then {@code TRANCAT-TYPE-CD PIC X(02)} then
 * {@code TRANCAT-CD PIC 9(04)}, belonging to {@link TransactionCategoryBalanceId} and not to this
 * class.</strong> This class is the 6 byte, two component key of the {@code TRANCATG} cluster and has no
 * account identifier component at all.
 *
 * <p>Nothing but the group name is shared. The prefixes differ, {@code TRAN-} here against {@code TRANCAT-}
 * there; the field counts differ, two against three; the key lengths differ, 6 against 17; the record lengths
 * differ, 60 against 50; and the clusters differ, {@code TRANCATG} against {@code TCATBALF}. The test proves
 * the 2 versus 3 composition by parsing both copybooks in one method, so the hazard is demonstrated from the
 * source rather than described in prose.
 *
 * <p>It then proves the consequence that matters at run time: an instance of this class is unequal to a
 * {@link TransactionCategoryBalanceId} <em>carrying the very same type and category codes</em>, in both
 * directions, and likewise unequal to a {@link DisclosureGroupId} carrying the same pair. A third assertion
 * shows the three keys coexist in one hash container without collapsing.
 *
 * <p>{@link DisclosureGroupId} is included because its table reuses the same two column names,
 * {@code tran_type_cd} and {@code tran_cat_cd}, even though its COBOL group is named {@code DIS-GROUP-KEY}
 * and its fields are prefixed {@code DIS-}. That reuse across three tables is a normalisation coincidence,
 * not evidence of a shared type.
 *
 * <p>The three keys deliberately share no superclass, no interface beyond {@link Serializable}, no abstract
 * key type and no helper, and a test here asserts that they do not. Repeating a few lines of
 * {@code equals} and {@code hashCode} across three independent value types is the correct outcome, not a
 * violation of the convention against duplication: collapsing them would couple three unrelated contracts,
 * and that is precisely the mistake this file exists to catch.
 *
 * <h2>3. Ordering safety of the numeric component</h2>
 *
 * <p>{@code TRAN-CAT-CD} is stored as zero padded fixed width unsigned digits, so the lexicographic order of
 * the VSAM byte image is the same as the numeric order of the decoded value. Mapping the component to
 * {@link Integer} therefore preserves browse ordering rather than perturbing it, and a test proves the two
 * orderings agree across all 18 fixture keys rather than asserting it in a comment. Any fixed width rendering
 * of the component must zero pad it back to four digits.
 *
 * <h2>4. How to build, run and test</h2>
 *
 * <pre>{@code
 * ./mvnw -B -ntp test-compile                                 # compile only
 * ./mvnw -B -ntp test                                         # the whole unit tier
 * ./mvnw -B -ntp test -Dtest=TransactionCategoryIdTest        # this class alone
 * ./mvnw -B -ntp verify                                       # adds the coverage gate
 * }</pre>
 *
 * <p><strong>Surefire collects this class, and the reason is worth knowing.</strong> The root {@code pom.xml}
 * configures Surefire 3.5.4 to include {@code **}{@code /*Test.java} and {@code **}{@code /*Tests.java} while
 * excluding {@code **}{@code /integration/**} and {@code **}{@code /e2e/**}, which leaves the unit tree as the
 * collected set. Two edits would therefore remove this class from every run <em>silently</em>: renaming it off
 * the {@code Test} suffix, or moving it under an excluded path. Neither produces an error or a warning -
 * Surefire and Failsafe both report success, and the coverage report simply shows the class uncovered. Do not
 * rename or relocate this file.
 *
 * <p>Surefire also pins {@code workingDirectory} to the project base directory, which is what lets the
 * assertions above read {@code app/cpy/CVTRA04Y.cpy} and its siblings through relative paths. Those reads are
 * strictly read only; the legacy corpus is frozen and nothing here writes, copies or edits any part of it.
 *
 * <h2>5. Key configuration and defaults</h2>
 *
 * <p>There is nothing to configure, and the absences are deliberate rather than incidental:
 *
 * <ul>
 *   <li><strong>No clock.</strong> {@link TransactionCategoryId} has no temporal component and performs no
 *       time dependent operation, so the fixed clock this package provides for date sensitive tiers is not
 *       needed here. No test in this file calls a wall clock, and none may: a key contract that varied with
 *       the time of day would not be a contract.</li>
 *   <li><strong>No mock.</strong> The subject is a pure value type with no collaborator, so Mockito is not
 *       used at all and its strictness setting is never engaged. A mock here would only be able to verify
 *       this test against itself.</li>
 *   <li><strong>No locale or zone sensitivity.</strong> The one formatting call in this file passes
 *       {@link Locale#ROOT} explicitly, and every file read names {@link StandardCharsets#UTF_8} explicitly,
 *       so no assertion can change behaviour with the platform default locale, charset or zone.</li>
 *   <li><strong>No shared mutable state.</strong> Every constant in this class is {@code static final} and
 *       immutable, and every fixture value is built inside the test that uses it.</li>
 *   </ul>
 *
 * <h2>6. Findings, by severity</h2>
 *
 * <ul>
 *   <li><strong>High - a missing or asymmetric {@code equals} and {@code hashCode} pair.</strong> A composite
 *       identifier with a broken equality contract does not fail cleanly. {@code find} misses rows that
 *       exist, {@code merge} inserts where it should update, and dirty checking writes when nothing changed,
 *       so the symptom is intermittent wrong data rather than an exception. Remediation, verified here: the
 *       contract is asserted in full - reflexivity, symmetry, transitivity, consistency across repeated
 *       invocations, rejection of {@code null}, rejection of a foreign type, inequality when either component
 *       differs, and agreement of {@code hashCode} for equal instances.</li>
 *   <li><strong>High - insecure deserialization.</strong> {@link Serializable} is implemented for one reason
 *       only, that Jakarta Persistence requires a composite identifier class to be serializable so a provider
 *       can use it as an identity map key. It is not a wire format. Remediation, verified here: the class
 *       declares no {@code readObject}, {@code readObjectNoData}, {@code readResolve}, {@code writeObject} or
 *       {@code writeReplace} hook, so there is no deserialization callback surface at all. This test
 *       deliberately never constructs an {@code ObjectInputStream}: the pinned serialized form is proved
 *       instead through {@link ObjectStreamClass}, which reads class metadata without deserializing anything.
 *       Untrusted bytes must never be fed to this type; wire representations belong to the DTO layer and are
 *       JSON.</li>
 *   <li><strong>Medium - conflating the two {@code TRAN-CAT-KEY} groups.</strong> Covered at length in
 *       section 2. Remediation, verified here: cross type inequality is asserted in both directions against
 *       both sibling keys, the absence of a shared supertype is asserted, and the differing compositions are
 *       parsed out of the two copybooks.</li>
 *   <li><strong>Medium - the Hibernate JDBC type code pairing.</strong> Hibernate's schema validator compares
 *       JDBC type codes and not merely column names, and {@code spring.jpa.hibernate.ddl-auto} is
 *       {@code validate} in every profile, so a mismatch fails application startup rather than degrading
 *       quietly. A bare {@link Integer} resolves to the {@code INTEGER} type code, which does not pair with a
 *       {@code NUMERIC} column. Remediation, verified here: the mapping declares
 *       {@code columnDefinition = "numeric(4)"} on the category code, and this test asserts that exact
 *       spelling, so the pairing cannot drift unnoticed. <strong>What this file deliberately does not
 *       assert:</strong> any SQL type spelling of its own. The DDL is owned by
 *       {@code src/main/resources/db/migration/V1__create_schema.sql} and asserted by that migration's own
 *       test; duplicating those assertions here would create two owners for one fact. No SQL type is invented
 *       anywhere in this file.</li>
 *   <li><strong>Low - the migration is not unavailable.</strong> {@code V1__create_schema.sql} carries
 *       authored content, so the SQL side of the contract is available; the boundary drawn
 *       above is one of ownership, not of absence.</li>
 *   <li><strong>Not available - a paragraph level locator for the equality contract.</strong> The COBOL
 *       corpus implements no key equality routine: VSAM compares key bytes inside the access method, so there
 *       is no paragraph to cite and none is invented. The equality requirements asserted here derive from the
 *       Jakarta Persistence composite identifier contract, while the <em>component set</em> those
 *       requirements operate over derives from {@code app/cpy/CVTRA04Y.cpy}. To close this gap a reader would
 *       need access to the access method's key comparison, which is not part of the corpus.</li>
 *   </ul>
 *
 * <h2>7. Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>The build fails with no test failure.</strong> That is the {@code -Xlint:all -Werror} gate at
 *       release 25, not an assertion. Test compilation is held to the same zero warning standard as main, so
 *       a raw type or an unchecked cast stops the build. A {@link Serializable} type that omits
 *       {@code serialVersionUID} fails the same way, which is why the subject declares one explicitly.</li>
 *   <li><strong>A cross type inequality assertion fails.</strong> An {@code equals} implementation was
 *       loosened from an exact class comparison to an {@code instanceof} test, or the three keys were given a
 *       common supertype. Re-read section 2 before changing either: the two keys named {@code TRAN-CAT-KEY}
 *       are different keys.</li>
 *   <li><strong>The copybook parsing assertions fail.</strong> A component width drifted from its PIC clause,
 *       or the frozen corpus was edited. The corpus is the system of record and must be restored, not
 *       adjusted to match the code.</li>
 *   <li><strong>A locator assertion fails with a line number in the message.</strong> The cited line moved.
 *       Because five files in the corpus carry CRLF endings, a checkout that converted line endings shifts
 *       cited lines; the four files this class reads are all LF and must stay that way.</li>
 *   <li><strong>An exact width assertion fails.</strong> The type code guard was relaxed from an exact width
 *       to a ceiling. A one character type code does not merely under fill its own field, it moves the
 *       category code to the wrong offset and so denotes a different row.</li>
 *   <li><strong>A fixture assertion fails naming a resource.</strong> Run {@code ./mvnw -B -ntp test-compile}
 *       so that {@code src/test/resources} is copied to {@code target/test-classes}. A width or census
 *       failure instead means the fixture was reflowed or converted to CRLF and must be restored from
 *       {@code app/data/ASCII}.</li>
 *   </ul>
 *
 * @see TransactionCategoryId
 * @see TransactionCategoryBalanceId
 * @see DisclosureGroupId
 * @see FixtureLoader
 */
class TransactionCategoryIdTest {

    /** {@code TRAN-TYPE-CD PIC X(02)} at {@code app/cpy/CVTRA04Y.cpy:L6}. */
    private static final int TRAN_TYPE_CD_WIDTH = 2;

    /** {@code TRAN-CAT-CD PIC 9(04)} at {@code app/cpy/CVTRA04Y.cpy:L7}. */
    private static final int TRAN_CAT_CD_WIDTH = 4;

    /** The key length the catalogue records for the {@code TRANCATG} cluster: {@code 2 + 4}. */
    private static final int CATALOGUED_KEY_LENGTH = 6;

    /** The key length of the identically named, three field {@code TRAN-CAT-KEY} of {@code CVTRA01Y.cpy}. */
    private static final int BALANCE_KEY_LENGTH = 17;

    /** Lowest value {@code PIC 9(04)} can hold: the picture is unsigned, so the domain starts at zero. */
    private static final int TRAN_CAT_CD_MIN = 0;

    /** Highest value {@code PIC 9(04)} can hold: four unsigned display digits. */
    private static final int TRAN_CAT_CD_MAX = 9999;

    /** The COBOL group name declared, identically, at line 5 of both {@code CVTRA04Y} and {@code CVTRA01Y}. */
    private static final String SHARED_GROUP_NAME = "TRAN-CAT-KEY";

    /** The frozen record layout this identifier is derived from. */
    private static final Path CVTRA04Y = Path.of("app", "cpy", "CVTRA04Y.cpy");

    /** The frozen record layout whose identically named key group must not be confused with this one. */
    private static final Path CVTRA01Y = Path.of("app", "cpy", "CVTRA01Y.cpy");

    /** The frozen VSAM catalogue listing, the independent confirmation of the key length. */
    private static final Path LISTCAT = Path.of("app", "catlg", "LISTCAT.txt");

    /** The frozen consumer program, which copies this layout at line 108. */
    private static final Path CBTRN03C = Path.of("app", "cbl", "CBTRN03C.cbl");

    /** Zero based index of {@code app/catlg/LISTCAT.txt:L1473}, naming the {@code TRANCATG} cluster. */
    private static final int LISTCAT_CLUSTER_LINE = 1472;

    /** Zero based index of {@code app/catlg/LISTCAT.txt:L1475}, carrying {@code KEYLEN} and {@code AVGLRECL}. */
    private static final int LISTCAT_KEYLEN_LINE = 1474;

    /** Zero based index of {@code app/catlg/LISTCAT.txt:L1476}, carrying {@code RKP}. */
    private static final int LISTCAT_RKP_LINE = 1475;

    /** Zero based index of {@code app/cbl/CBTRN03C.cbl:L108}, the {@code COPY CVTRA04Y.} statement. */
    private static final int CBTRN03C_COPY_LINE = 107;

    /** Total line count of the consumer program, asserted so that the cited line index cannot drift. */
    private static final int CBTRN03C_LINE_COUNT = 649;

    /** Record count of {@code app/data/ASCII/trancatg.txt}, matching {@code REC-TOTAL} in the catalogue. */
    private static final int FIXTURE_RECORD_COUNT = 18;

    /** Record width of {@code app/data/ASCII/trancatg.txt}: {@code 6 + 50 + 4}. */
    private static final int FIXTURE_RECORD_WIDTH = 60;

    /** One based first column of {@code TRAN-CAT-TYPE-DESC PIC X(50)}, immediately after the six byte key. */
    private static final int DESCRIPTION_COLUMN = 7;

    /** Width of {@code TRAN-CAT-TYPE-DESC PIC X(50)}. */
    private static final int DESCRIPTION_WIDTH = 50;

    /** One based first column of the four byte {@code FILLER} that closes the record. */
    private static final int FILLER_COLUMN = 57;

    /** Width of the trailing {@code FILLER PIC X(04)}. */
    private static final int FILLER_WIDTH = 4;

    /** One based first column of the type and category pair inside a {@code discgrp.txt} record. */
    private static final int DISCLOSURE_PAIR_COLUMN = 11;

    /** Width of the account group identifier that opens a {@code discgrp.txt} record. */
    private static final int DISCLOSURE_GROUP_ID_WIDTH = 10;

    /** The group identifier of the fallback rate block the interest calculation reads. */
    private static final String DEFAULT_GROUP_ID = "DEFAULT";

    /** Number of {@code DEFAULT} rows in {@code app/data/ASCII/discgrp.txt}. */
    private static final int DEFAULT_BLOCK_SIZE = 17;

    /** The one key present in the category fixture but absent from the disclosure {@code DEFAULT} block. */
    private static final String INTEREST_KEY_IMAGE = "010005";

    /** The description that identifies {@link #INTEREST_KEY_IMAGE} as the interest output category. */
    private static final String INTEREST_DESCRIPTION = "Interest Amount";

    /** The only packages and types {@link TransactionCategoryId} is permitted to import. */
    private static final Set<String> PERMITTED_IMPORTS =
            Set.of("java.io.Serializable", "java.util.Objects");

    /** The permitted import package prefix, alongside the two exact types above. */
    private static final String PERMITTED_IMPORT_PREFIX = "jakarta.persistence.";

    /** Source of the type under test, read to prove the import restriction directly rather than by proxy. */
    private static final Path SUBJECT_SOURCE =
            Path.of("src", "main", "java", "com", "cardemo", "model", "key", "TransactionCategoryId.java");

    /** A type code drawn from the fixture: {@code 01} heads five of its 18 rows. */
    private static final String FIXTURE_TYPE_CD = "01";

    /** A category code drawn from the fixture: {@code 0001} pairs with {@code 01} on its first row. */
    private static final int FIXTURE_CAT_CD = 1;

    /**
     * A key built from a pair that genuinely occurs in {@code app/data/ASCII/trancatg.txt} line 1,
     * {@code 010001Regular Sales Draft}, rather than from an invented pair.
     *
     * @return a fresh, valid identifier; a new instance each call, so no test can mutate another's subject
     */
    private static TransactionCategoryId fixtureKey() {
        return new TransactionCategoryId(FIXTURE_TYPE_CD, FIXTURE_CAT_CD);
    }

    // 1. The field contract, read out of the frozen corpus

    @Test
    @DisplayName("the copybook declares exactly two subordinate items under TRAN-CAT-KEY, 2 and 4 bytes wide")
    void theCopybookDeclaresTwoKeyComponentsOfTwoAndFourBytes() throws IOException {
        assertThat(subordinateWidths(CVTRA04Y, SHARED_GROUP_NAME))
                .as("app/cpy/CVTRA04Y.cpy:L5-L7 nests TRAN-TYPE-CD PIC X(02) then TRAN-CAT-CD PIC 9(04) "
                        + "inside TRAN-CAT-KEY, and nothing else; the widths are read from the PIC clauses "
                        + "in the frozen file rather than retyped here")
                .containsExactly(TRAN_TYPE_CD_WIDTH, TRAN_CAT_CD_WIDTH);
    }

    @Test
    @DisplayName("the two component widths sum to the catalogued TRANCATG key length of 6 bytes")
    void theComponentWidthsSumToTheCataloguedKeyLength() throws IOException {
        final int keyLength = subordinateWidths(CVTRA04Y, SHARED_GROUP_NAME).stream()
                .mapToInt(Integer::intValue)
                .sum();

        assertThat(keyLength)
                .as("2 + 4 = 6, the shortest key in the corpus")
                .isEqualTo(CATALOGUED_KEY_LENGTH);
    }

    @Test
    @DisplayName("the VSAM catalogue independently records KEYLEN 6, AVGLRECL 60 and RKP 0 for TRANCATG")
    void theCatalogueIndependentlyRecordsKeyLengthSix() throws IOException {
        final List<String> listcat = Files.readAllLines(LISTCAT, StandardCharsets.UTF_8);

        assertThat(listcat.get(LISTCAT_CLUSTER_LINE))
                .as("app/catlg/LISTCAT.txt:L1473 names the cluster whose attributes follow")
                .contains("CLUSTER--AWS.M2.CARDDEMO.TRANCATG.VSAM.KSDS");
        assertThat(catalogueAttribute(listcat.get(LISTCAT_KEYLEN_LINE), "KEYLEN"))
                .as("app/catlg/LISTCAT.txt:L1475 confirms the copybook arithmetic from a different artefact")
                .isEqualTo(CATALOGUED_KEY_LENGTH);
        assertThat(catalogueAttribute(listcat.get(LISTCAT_KEYLEN_LINE), "AVGLRECL"))
                .as("the record is 60 bytes, which is the 6 byte key plus X(50) plus X(04); the record "
                        + "length itself is the owning entity's assertion, cited here only as corroboration")
                .isEqualTo(FIXTURE_RECORD_WIDTH);
        assertThat(catalogueAttribute(listcat.get(LISTCAT_RKP_LINE), "RKP"))
                .as("app/catlg/LISTCAT.txt:L1476 places the key at relative byte position zero, so the key "
                        + "is the record prefix and its component order is the browse order")
                .isZero();
    }

    @Test
    @DisplayName("the transaction report program copies this layout at CBTRN03C.cbl:L108")
    void theConsumerProgramCopiesThisLayout() throws IOException {
        final List<String> program = Files.readAllLines(CBTRN03C, StandardCharsets.UTF_8);

        assertThat(program)
                .as("app/cbl/CBTRN03C.cbl is 649 lines; asserting the count keeps the cited line index "
                        + "honest, because a line ending conversion would shift it")
                .hasSize(CBTRN03C_LINE_COUNT);
        assertThat(program.get(CBTRN03C_COPY_LINE).strip())
                .as("app/cbl/CBTRN03C.cbl:L108 resolves the category description layout. That program also "
                        + "carries a preserved legacy quirk - its control break fires on the card number "
                        + "while the emitted label reads \"Account Total\" - which belongs to the batch "
                        + "processor layer and is deliberately not modelled or asserted here")
                .isEqualTo("COPY CVTRA04Y.");
    }

    @Test
    @DisplayName("the composite primary key columns are declared in COBOL order: tran_type_cd, tran_cat_cd")
    void theCompositePrimaryKeyColumnsAreDeclaredInCobolOrder() {
        assertThat(persistentFields().stream().map(field -> field.getAnnotation(Column.class).name()).toList())
                .as("the VSAM key byte layout is the browse order, so the relational primary key must be "
                        + "declared as (tran_type_cd, tran_cat_cd) in that sequence; the components are "
                        + "deliberately not alphabetised and must not be reordered")
                .containsExactly("tran_type_cd", "tran_cat_cd");
    }

    @Test
    @DisplayName("declares exactly the two persistent components of the copybook group, in its order")
    void declaresExactlyTheTwoPersistentComponentsInCopybookOrder() {
        assertThat(persistentFields().stream().map(Field::getName).toList())
                .as("the group name TRAN-CAT-KEY is shared with CVTRA01Y, whose key has three components, "
                        + "so the two component shape is asserted explicitly rather than assumed")
                .containsExactly("tranTypeCd", "tranCatCd");
    }

    // 2. The collision hazard: two copybooks, one group name, two entirely different keys

    @Test
    @DisplayName("TRAN-CAT-KEY is declared in BOTH copybooks with different compositions: 2 fields vs 3")
    void theTwoIdenticallyNamedKeyGroupsHaveDifferentCompositions() throws IOException {
        final List<Integer> thisKey = subordinateWidths(CVTRA04Y, SHARED_GROUP_NAME);
        final List<Integer> balanceKey = subordinateWidths(CVTRA01Y, SHARED_GROUP_NAME);

        assertThat(thisKey)
                .as("app/cpy/CVTRA04Y.cpy declares TRAN-CAT-KEY with two subordinate items summing to 6")
                .containsExactly(TRAN_TYPE_CD_WIDTH, TRAN_CAT_CD_WIDTH);
        assertThat(balanceKey)
                .as("app/cpy/CVTRA01Y.cpy declares a group of the SAME NAME with three subordinate items - "
                        + "TRANCAT-ACCT-ID PIC 9(11), TRANCAT-TYPE-CD PIC X(02), TRANCAT-CD PIC 9(04) - "
                        + "belonging to TransactionCategoryBalanceId and not to the type under test")
                .containsExactly(11, TRAN_TYPE_CD_WIDTH, TRAN_CAT_CD_WIDTH);
        assertThat(balanceKey.stream().mapToInt(Integer::intValue).sum())
                .as("17 bytes there against 6 here: the shared group name is a naming coincidence in the "
                        + "legacy corpus, not evidence of a shared concept")
                .isEqualTo(BALANCE_KEY_LENGTH)
                .isNotEqualTo(CATALOGUED_KEY_LENGTH);
    }

    @Test
    @DisplayName("is NOT equal to a TransactionCategoryBalanceId carrying the same type and category codes")
    void isNotEqualToABalanceIdCarryingTheSameTypeAndCategoryCodes() {
        final TransactionCategoryId category = fixtureKey();
        final TransactionCategoryBalanceId balance =
                new TransactionCategoryBalanceId(1L, FIXTURE_TYPE_CD, FIXTURE_CAT_CD);

        assertThat(category.equals(balance))
                .as("the two keys named TRAN-CAT-KEY are different keys; a six byte category key can never "
                        + "equal a seventeen byte account-scoped balance key, however the codes line up")
                .isFalse();
        assertThat(balance.equals(category))
                .as("and the rejection is symmetric: neither type's equals accepts the other")
                .isFalse();
    }

    @Test
    @DisplayName("is NOT equal to a DisclosureGroupId carrying the same type and category codes")
    void isNotEqualToADisclosureGroupIdCarryingTheSameTypeAndCategoryCodes() {
        final TransactionCategoryId category = fixtureKey();
        final DisclosureGroupId disclosure =
                new DisclosureGroupId(DEFAULT_GROUP_ID, FIXTURE_TYPE_CD, FIXTURE_CAT_CD);

        assertThat(category.equals(disclosure))
                .as("DisclosureGroupId's COBOL group is DIS-GROUP-KEY and its fields are prefixed DIS-; the "
                        + "shared tran_type_cd and tran_cat_cd column names are a normalisation coincidence "
                        + "across three separate tables, not evidence of a shared type")
                .isFalse();
        assertThat(disclosure.equals(category))
                .as("and the rejection is symmetric in this direction too")
                .isFalse();
    }

    @Test
    @DisplayName("the three composite keys share no supertype beyond Object and Serializable")
    void theThreeCompositeKeysShareNoSupertype() {
        for (final Class<?> keyType : List.of(TransactionCategoryId.class,
                TransactionCategoryBalanceId.class, DisclosureGroupId.class)) {
            assertThat(keyType.getSuperclass())
                    .as("%s must extend Object directly: an abstract key type would couple three unrelated "
                            + "contracts, and duplicating equals and hashCode across them is the intended "
                            + "outcome rather than a violation of the convention against duplication",
                            keyType.getSimpleName())
                    .isEqualTo(Object.class);
            assertThat(keyType.getInterfaces())
                    .as("%s must implement Serializable and nothing else: a shared marker interface would "
                            + "be the same coupling by another route", keyType.getSimpleName())
                    .containsExactly(Serializable.class);
        }
    }

    @Test
    @DisplayName("the three same-valued keys coexist in one hash container without collapsing into one")
    void theThreeSameValuedKeysCoexistInOneHashContainer() {
        final Set<Object> keys = new HashSet<>();
        keys.add(fixtureKey());
        keys.add(new TransactionCategoryBalanceId(1L, FIXTURE_TYPE_CD, FIXTURE_CAT_CD));
        keys.add(new DisclosureGroupId(DEFAULT_GROUP_ID, FIXTURE_TYPE_CD, FIXTURE_CAT_CD));

        assertThat(keys)
                .as("all three carry type code %s and category code %d, yet they are three distinct "
                        + "identities; a shared supertype or a loosened equals would silently reduce this "
                        + "set and, in a persistence context, resolve one entity through another's key",
                        FIXTURE_TYPE_CD, FIXTURE_CAT_CD)
                .hasSize(3);

        final Map<Object, String> byKey = new HashMap<>();
        byKey.put(fixtureKey(), "transaction category");

        assertThat(byKey.get(new TransactionCategoryBalanceId(1L, FIXTURE_TYPE_CD, FIXTURE_CAT_CD)))
                .as("a lookup with the balance key must not resolve the category entry")
                .isNull();
        assertThat(byKey.get(new DisclosureGroupId(DEFAULT_GROUP_ID, FIXTURE_TYPE_CD, FIXTURE_CAT_CD)))
                .as("nor must a lookup with the disclosure key")
                .isNull();
        assertThat(byKey.get(fixtureKey()))
                .as("while an equal category key resolves it by value, as a JPA identity map requires")
                .isEqualTo("transaction category");
    }

    // 3. The Jakarta Persistence composite identifier contract

    @Test
    @DisplayName("is @Embeddable and Serializable, as an @EmbeddedId composite key must be")
    void isEmbeddableAndSerializable() {
        assertThat(TransactionCategoryId.class.getAnnotation(Embeddable.class))
                .as("the key is consumed through @EmbeddedId on the owning entity, which requires the key "
                        + "class itself to be @Embeddable")
                .isNotNull();
        assertThat(Serializable.class)
                .as("a provider must be able to use the identifier as an identity map key and place it in a "
                        + "second level cache, which requires serializability")
                .isAssignableFrom(TransactionCategoryId.class);
    }

    @Test
    @DisplayName("declares an explicitly pinned serialVersionUID, so the serialized form is not compiler derived")
    void declaresAnExplicitlyPinnedSerialVersionUid() throws ReflectiveOperationException {
        final Field declared = TransactionCategoryId.class.getDeclaredField("serialVersionUID");
        declared.setAccessible(true);

        assertThat(Modifier.isStatic(declared.getModifiers()))
                .as("serialVersionUID must be static")
                .isTrue();
        assertThat(Modifier.isFinal(declared.getModifiers()))
                .as("and final: this is the one static field the type is permitted, and it is immutable")
                .isTrue();
        assertThat(declared.getType())
                .as("and of primitive type long, or the runtime ignores it entirely")
                .isEqualTo(long.class);

        final long declaredUid = declared.getLong(null);

        assertThat(declaredUid)
                .as("under -Xlint:all -Werror a Serializable type that omits serialVersionUID fails the "
                        + "build outright, so its presence is a build contract and not merely a convention")
                .isEqualTo(1L);
        assertThat(ObjectStreamClass.lookup(TransactionCategoryId.class).getSerialVersionUID())
                .as("the runtime's view of the serialized form agrees with the declared field. This is "
                        + "proved through class metadata precisely so that no ObjectInputStream is "
                        + "constructed anywhere in this file")
                .isEqualTo(declaredUid);
    }

    @Test
    @DisplayName("declares no serialization hook, so there is no deserialization callback surface at all")
    void declaresNoSerializationHook() {
        final List<String> hooks = Arrays.stream(TransactionCategoryId.class.getDeclaredMethods())
                .map(Method::getName)
                .filter(name -> List.of("readObject", "readObjectNoData", "readResolve",
                        "writeObject", "writeReplace").contains(name))
                .toList();

        assertThat(hooks)
                .as("Serializable is implemented for JPA identity only. Insecure deserialization is a named "
                        + "risky pattern, so the type must expose no callback a crafted byte stream could "
                        + "reach: untrusted bytes are never fed to this type, no Java serialization caching "
                        + "or messaging is built on it, and wire representations are JSON in the DTO layer")
                .isEmpty();
    }

    @Test
    @DisplayName("the no-argument constructor exists for the provider but is not public")
    void theNoArgumentConstructorExistsForTheProviderButIsNotPublic() throws NoSuchMethodException {
        final Constructor<TransactionCategoryId> provider =
                TransactionCategoryId.class.getDeclaredConstructor();

        assertThat(Modifier.isPublic(provider.getModifiers()))
                .as("a PUBLIC no-argument constructor would offer application code a key that had passed no "
                        + "width or range guard at all; reduced visibility keeps the provider contract while "
                        + "leaving the validating constructor as the only route open to callers")
                .isFalse();
        assertThat(Modifier.isProtected(provider.getModifiers()))
                .as("protected is the visibility that satisfies a reflective provider without publishing an "
                        + "unvalidated construction path")
                .isTrue();
    }

    @Test
    @DisplayName("the provider constructor yields an instance with both components unset")
    void theProviderConstructorYieldsAnInstanceWithBothComponentsUnset() throws ReflectiveOperationException {
        final Constructor<TransactionCategoryId> provider =
                TransactionCategoryId.class.getDeclaredConstructor();
        provider.setAccessible(true);

        final TransactionCategoryId unpopulated = provider.newInstance();

        assertThat(unpopulated.getTranTypeCd())
                .as("a provider instantiates first and populates the fields reflectively afterwards, so a "
                        + "default assigned here would reject a legitimate provider-created instance")
                .isNull();
        assertThat(unpopulated.getTranCatCd()).isNull();
    }

    @Test
    @DisplayName("the all-components constructor is public and takes the two components in COBOL order")
    void theAllComponentsConstructorTakesTheComponentsInCobolOrder() throws NoSuchMethodException {
        final Constructor<TransactionCategoryId> constructor =
                TransactionCategoryId.class.getDeclaredConstructor(String.class, Integer.class);

        assertThat(Modifier.isPublic(constructor.getModifiers()))
                .as("this is the only construction path application code may use, so it must be public")
                .isTrue();
        assertThat(constructor.getParameterTypes())
                .as("TRAN-TYPE-CD PIC X(02) precedes TRAN-CAT-CD PIC 9(04) in app/cpy/CVTRA04Y.cpy, so the "
                        + "parameters are String then Integer; transposing them would compile only by "
                        + "accident and would denote a different row")
                .containsExactly(String.class, Integer.class);
    }

    @Test
    @DisplayName("maps the type code to tran_type_cd at the exact PIC X(02) width and NOT NULL")
    void mapsTheTypeCodeToTranTypeCdAtItsPicWidth() throws NoSuchFieldException {
        final Column column =
                TransactionCategoryId.class.getDeclaredField("tranTypeCd").getAnnotation(Column.class);

        assertThat(column)
                .as("an explicit @Column is required so that no naming strategy can rename the column "
                        + "silently under ddl-auto: validate")
                .isNotNull();
        assertThat(column.name()).isEqualTo("tran_type_cd");
        assertThat(column.length())
                .as("PIC X(02) is two characters, and the length is declared rather than defaulted")
                .isEqualTo(TRAN_TYPE_CD_WIDTH);
        assertThat(column.nullable())
                .as("a key component can never be null")
                .isFalse();
    }

    @Test
    @DisplayName("maps the category code to tran_cat_cd pinned to numeric(4), not to a bare INTEGER")
    void mapsTheCategoryCodeToTranCatCdPinnedToNumericFour() throws NoSuchFieldException {
        final Column column =
                TransactionCategoryId.class.getDeclaredField("tranCatCd").getAnnotation(Column.class);

        assertThat(column).isNotNull();
        assertThat(column.name()).isEqualTo("tran_cat_cd");
        assertThat(column.nullable()).isFalse();
        assertThat(column.columnDefinition())
                .as("Hibernate's validator compares JDBC type codes, and a bare Integer resolves to INTEGER, "
                        + "which does not pair with a NUMERIC column; the explicit spelling is what makes "
                        + "ddl-auto: validate accept the mapping. The DDL itself is owned and asserted by "
                        + "the migration, so no SQL type is invented here")
                .isEqualTo("numeric(4)");
    }

    @Test
    @DisplayName("uses no float, double or BigDecimal in any component: this is an identifier, not an amount")
    void usesNoFloatingPointOrDecimalTypeInAnyComponent() {
        assertThat(persistentFields().stream().map(Field::getType).toList())
                .as("a category code is an unsigned four digit classification, so it maps to Integer. No "
                        + "financial type belongs in a key, and no binary floating point type belongs "
                        + "anywhere in this application")
                .doesNotContain(float.class, double.class, Float.class, Double.class, BigDecimal.class)
                .containsExactly(String.class, Integer.class);
    }

    @Test
    @DisplayName("holds no static mutable state: every static field is final")
    void holdsNoStaticMutableState() {
        final List<String> mutableStatics = Arrays.stream(TransactionCategoryId.class.getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .filter(field -> Modifier.isStatic(field.getModifiers()))
                .filter(field -> !Modifier.isFinal(field.getModifiers()))
                .map(Field::getName)
                .toList();

        assertThat(mutableStatics)
                .as("global mutable state is prohibited; serialVersionUID and the width and range bounds are "
                        + "static final compile-time constants, which is the permitted form")
                .isEmpty();
    }

    @Test
    @DisplayName("exposes a hand-written accessor per component and carries no Lombok annotation")
    void exposesAnExplicitAccessorPerComponentAndNoLombokAnnotation() throws NoSuchMethodException {
        assertThat(Modifier.isPublic(
                TransactionCategoryId.class.getDeclaredMethod("getTranTypeCd").getModifiers()))
                .as("accessors are written out rather than generated, so the documented contract and the "
                        + "code cannot drift apart")
                .isTrue();
        assertThat(Modifier.isPublic(
                TransactionCategoryId.class.getDeclaredMethod("getTranCatCd").getModifiers())).isTrue();

        final List<String> generatedBy = new ArrayList<>();
        Arrays.stream(TransactionCategoryId.class.getAnnotations())
                .forEach(annotation -> generatedBy.add(annotation.annotationType().getName()));
        persistentFields().forEach(field -> Arrays.stream(field.getAnnotations())
                .forEach(annotation -> generatedBy.add(annotation.annotationType().getName())));

        assertThat(generatedBy)
                .as("no annotation processor participates in this type; Lombok is deliberately absent from "
                        + "the build and must not appear on the class or on either component")
                .isNotEmpty()
                .noneMatch(name -> name.startsWith("lombok."));
    }

    @Test
    @DisplayName("carries no mutator, so a live identifier cannot be altered in place")
    void carriesNoMutator() {
        assertThat(Arrays.stream(TransactionCategoryId.class.getDeclaredMethods()).map(Method::getName).toList())
                .as("mutating a live identifier would corrupt the persistence context's identity map. The "
                        + "components are not declared final only because a provider populates them "
                        + "reflectively, so immutability rests on the absence of setters")
                .noneMatch(name -> name.startsWith("set"));
    }

    @Test
    @DisplayName("toString renders only the two key components, and nothing else")
    void toStringRendersOnlyTheTwoKeyComponents() {
        assertThat(fixtureKey().toString())
                .as("a key carries no business payload and no personal data, so its diagnostic rendering is "
                        + "safe to log in full; it must stay that way")
                .isEqualTo("TransactionCategoryId[tranTypeCd=01, tranCatCd=1]");
    }

    @Test
    @DisplayName("the production type imports only jakarta.persistence, Serializable and Objects")
    void theProductionTypeImportsOnlyThePermittedApis() throws IOException {
        final List<String> imports = Files.readAllLines(SUBJECT_SOURCE, StandardCharsets.UTF_8).stream()
                .map(String::strip)
                .filter(line -> line.startsWith("import "))
                .map(line -> line.substring("import ".length()).replace(";", "").strip())
                .toList();

        assertThat(imports)
                .as("the source must declare imports for the reflective surface asserted above to mean "
                        + "anything")
                .isNotEmpty();
        assertThat(imports)
                .as("the key is a pure value type: it depends on the persistence API, on Serializable and "
                        + "on Objects, and on nothing else. No CardDemo type, no framework, no utility "
                        + "library and no static import may appear, because a key that reached further "
                        + "could not be constructed freely in a test or a batch step")
                .allSatisfy(declaration -> assertThat(PERMITTED_IMPORTS.contains(declaration)
                        || declaration.startsWith(PERMITTED_IMPORT_PREFIX))
                        .as("import %s is outside the permitted set %s plus %s*",
                                declaration, PERMITTED_IMPORTS, PERMITTED_IMPORT_PREFIX)
                        .isTrue());
    }

    // 4. The equals and hashCode contract, in full

    @Test
    @DisplayName("equals is reflexive: an identifier equals itself")
    void equalsIsReflexive() {
        final TransactionCategoryId key = fixtureKey();

        assertThat(key.equals(key))
                .as("reflexivity is what lets a persistence context recognise the instance it already holds")
                .isTrue();
    }

    @Test
    @DisplayName("equals is symmetric: two separately built identifiers agree in both directions")
    void equalsIsSymmetric() {
        final TransactionCategoryId first = fixtureKey();
        final TransactionCategoryId second = fixtureKey();

        assertThat(first.equals(second))
                .as("an asymmetric contract makes find, merge and dirty checking fail as intermittent wrong "
                        + "data rather than as a clean exception")
                .isTrue();
        assertThat(second.equals(first)).isTrue();
        assertThat(first)
                .as("and the instances are distinct objects, so this proves value equality and not identity")
                .isNotSameAs(second);
    }

    @Test
    @DisplayName("equals is transitive across three identifiers of equal value")
    void equalsIsTransitive() {
        final TransactionCategoryId first = fixtureKey();
        final TransactionCategoryId second = fixtureKey();
        final TransactionCategoryId third = fixtureKey();

        assertThat(first.equals(second)).isTrue();
        assertThat(second.equals(third)).isTrue();
        assertThat(first.equals(third))
                .as("transitivity is what makes the identifier safe as a hash container key across a whole "
                        + "unit of work rather than only pairwise")
                .isTrue();
    }

    @Test
    @DisplayName("equals is consistent: repeated invocations on unchanged instances return the same answer")
    void equalsIsConsistentAcrossRepeatedInvocations() {
        final TransactionCategoryId key = fixtureKey();
        final TransactionCategoryId equal = fixtureKey();
        final TransactionCategoryId different = new TransactionCategoryId(FIXTURE_TYPE_CD, FIXTURE_CAT_CD + 1);

        for (int invocation = 0; invocation < 3; invocation++) {
            assertThat(key.equals(equal))
                    .as("invocation %d must agree with every other: equality reads only the two immutable "
                            + "components and consults no clock, no counter and no shared state",
                            invocation)
                    .isTrue();
            assertThat(key.equals(different)).isFalse();
        }
    }

    @Test
    @DisplayName("equals rejects null without throwing")
    void equalsRejectsNullWithoutThrowing() {
        assertThat(fixtureKey().equals(null))
                .as("the contract requires false rather than a NullPointerException, and a provider does "
                        + "compare against null while populating an identity map")
                .isFalse();
    }

    @Test
    @DisplayName("equals rejects a foreign type without throwing")
    void equalsRejectsAForeignType() {
        final TransactionCategoryId key = fixtureKey();

        assertThat(key.equals("010001"))
                .as("the six character key image is not the key: an implementation that compared renderings "
                        + "would accept a String and lose the type distinction entirely")
                .isFalse();
        assertThat(key.equals(new Object())).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"02, 1", "01, 2", "99, 9999"})
    @DisplayName("differs when either component differs, so identity is total over both")
    void differsWhenEitherComponentDiffers(final String typeCd, final int catCd) {
        final TransactionCategoryId other = new TransactionCategoryId(typeCd, catCd);

        assertThat(fixtureKey().equals(other))
                .as("a key that ignored either component would resolve one category row through another's "
                        + "identifier; '%s'/%d must not equal '%s'/%d",
                        typeCd, catCd, FIXTURE_TYPE_CD, FIXTURE_CAT_CD)
                .isFalse();
        assertThat(other.equals(fixtureKey()))
                .as("and the inequality holds in the other direction too")
                .isFalse();
    }

    @Test
    @DisplayName("hashCode agrees for equal identifiers, as the contract requires")
    void hashCodeAgreesForEqualIdentifiers() {
        assertThat(fixtureKey())
                .as("equal objects with unequal hashes vanish from every hash container, which is the "
                        + "classic silent data error this assertion exists to prevent")
                .hasSameHashCodeAs(fixtureKey());
    }

    @Test
    @DisplayName("hashCode is stable across repeated invocations on the same instance")
    void hashCodeIsStableAcrossRepeatedInvocations() {
        final TransactionCategoryId key = fixtureKey();
        final int first = key.hashCode();

        for (int invocation = 0; invocation < 3; invocation++) {
            assertThat(key.hashCode())
                    .as("invocation %d must reproduce the first hash: the components are immutable, so the "
                            + "hash cannot drift", invocation)
                    .isEqualTo(first);
        }
    }

    @Test
    @DisplayName("behaves as a hash container key: a set de-duplicates and a map resolves by value")
    void behavesAsAHashContainerKey() {
        final Set<TransactionCategoryId> keys = new HashSet<>();
        keys.add(fixtureKey());
        keys.add(fixtureKey());
        keys.add(new TransactionCategoryId(FIXTURE_TYPE_CD, FIXTURE_CAT_CD + 1));

        assertThat(keys)
                .as("two equal keys collapse to one entry and a third distinct key stays separate, which is "
                        + "exactly how a provider's identity map behaves")
                .hasSize(2);

        final Map<TransactionCategoryId, String> descriptions = new HashMap<>();
        descriptions.put(fixtureKey(), "Regular Sales Draft");

        assertThat(descriptions.get(fixtureKey()))
                .as("resolution is by value, not by reference")
                .isEqualTo("Regular Sales Draft");
    }

    // 5. Hostile and boundary input: every component treated as untrusted

    @Test
    @DisplayName("rejects a null type code, naming the Java property and the COBOL picture clause")
    void rejectsANullTypeCode() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new TransactionCategoryId(null, FIXTURE_CAT_CD))
                .withMessage("tranTypeCd (TRAN-TYPE-CD PIC X(02)) is required and must not be null")
                .withNoCause();
    }

    @Test
    @DisplayName("rejects a null category code, naming the Java property and the COBOL picture clause")
    void rejectsANullCategoryCode() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new TransactionCategoryId(FIXTURE_TYPE_CD, null))
                .withMessage("tranCatCd (TRAN-CAT-CD PIC 9(04)) is required and must not be null")
                .withNoCause();
    }

    @ParameterizedTest
    @ValueSource(strings = {"01", "02", "05", "07", "99", "AB", "  "})
    @DisplayName("accepts any type code of exactly two characters, blanks included, and carries it verbatim")
    void acceptsAnyTypeCodeOfExactlyTwoCharacters(final String typeCd) {
        assertThat(new TransactionCategoryId(typeCd, FIXTURE_CAT_CD).getTranTypeCd())
                .as("PIC X(02) is alphanumeric, so the guard is a width guard and not a numeric one. Two "
                        + "spaces are a legitimate value of an alphanumeric fixed width field and are stored "
                        + "verbatim: trimming would shorten the key and move the category code's offset")
                .isEqualTo(typeCd)
                .hasSize(TRAN_TYPE_CD_WIDTH);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "0", "1", "001", "0123", "     "})
    @DisplayName("rejects a type code of any width other than two: the guard is exact, not a ceiling")
    void rejectsATypeCodeOfAnyWidthOtherThanTwo(final String typeCd) {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("a one or three character type code does not merely under or over fill its own field: "
                        + "inside a six byte key it moves TRAN-CAT-CD to the wrong offset and so denotes a "
                        + "different row. '%s' has length %d", typeCd, typeCd.length())
                .isThrownBy(() -> new TransactionCategoryId(typeCd, FIXTURE_CAT_CD))
                .withMessage("tranTypeCd (TRAN-TYPE-CD PIC X(02)) must be exactly " + TRAN_TYPE_CD_WIDTH
                        + " characters but was " + typeCd.length() + ": [" + typeCd + "]")
                .withNoCause();
    }

    @ParameterizedTest
    @ValueSource(ints = {TRAN_CAT_CD_MIN, 1, 5, 9, 1000, 9998, TRAN_CAT_CD_MAX})
    @DisplayName("accepts every category code inside the PIC 9(04) domain, both boundaries included")
    void acceptsEveryCategoryCodeInsideTheFourDigitDomain(final int catCd) {
        assertThat(new TransactionCategoryId(FIXTURE_TYPE_CD, catCd).getTranCatCd())
                .as("the domain is the closed interval 0 to 9999, and both endpoints are inside it")
                .isEqualTo(catCd);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, -5, 10_000, 12_345, Integer.MAX_VALUE, Integer.MIN_VALUE})
    @DisplayName("rejects every category code outside PIC 9(04), negatives and five-digit values alike")
    void rejectsEveryCategoryCodeOutsideTheFourDigitDomain(final int catCd) {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("PIC 9(04) is unsigned and four digits wide, so %d could not round-trip through the "
                        + "legacy record", catCd)
                .isThrownBy(() -> new TransactionCategoryId(FIXTURE_TYPE_CD, catCd))
                .withMessage("tranCatCd (TRAN-CAT-CD PIC 9(04)) must be between " + TRAN_CAT_CD_MIN + " and "
                        + TRAN_CAT_CD_MAX + " inclusive but was " + catCd)
                .withNoCause();
    }

    // 6. The seed fixture: the domain the data actually occupies

    @Test
    @DisplayName("every key in the frozen fixture round-trips through this identifier, and all 18 are distinct")
    void everyKeyInTheFrozenFixtureRoundTripsThroughThisIdentifier() {
        final FixtureLoader.FixtureData fixture =
                FixtureLoader.load(FixtureLoader.Fixture.TRANSACTION_CATEGORY);

        assertThat(fixture.recordCount())
                .as("app/data/ASCII/trancatg.txt holds 18 records, matching REC-TOTAL in the catalogue")
                .isEqualTo(FIXTURE_RECORD_COUNT);
        assertThat(fixture.recordWidth())
                .as("each record is 60 bytes: the 6 byte key, X(50) description and X(04) filler")
                .isEqualTo(FIXTURE_RECORD_WIDTH);

        final Set<TransactionCategoryId> keys = new HashSet<>();
        for (int row = 0; row < fixture.recordCount(); row++) {
            final String typeCd = fixture.field(row, 1, TRAN_TYPE_CD_WIDTH);
            final String catCd = fixture.field(row, 1 + TRAN_TYPE_CD_WIDTH, TRAN_CAT_CD_WIDTH);
            final TransactionCategoryId key = new TransactionCategoryId(typeCd, Integer.parseInt(catCd));

            assertThat(key.getTranTypeCd())
                    .as("row %d carries its type code in columns 1-2, stored verbatim", row)
                    .isEqualTo(typeCd);
            assertThat(key.getTranCatCd())
                    .as("row %d carries its category code, zero padded, in columns 3-6", row)
                    .isEqualTo(Integer.parseInt(catCd));
            keys.add(key);
        }

        assertThat(keys)
                .as("the cluster is UNIQUE and INDEXED, so all 18 keys must be distinct; a de-duplicating "
                        + "set proves it without a second pass over the data")
                .hasSize(FIXTURE_RECORD_COUNT);
    }

    @Test
    @DisplayName("lexicographic byte order equals numeric order, because the category code is zero padded")
    void lexicographicByteOrderEqualsNumericOrder() {
        final FixtureLoader.FixtureData fixture =
                FixtureLoader.load(FixtureLoader.Fixture.TRANSACTION_CATEGORY);
        final List<String> byteImages = new ArrayList<>();
        final List<TransactionCategoryId> keys = new ArrayList<>();

        for (int row = 0; row < fixture.recordCount(); row++) {
            byteImages.add(fixture.field(row, 1, CATALOGUED_KEY_LENGTH));
            keys.add(new TransactionCategoryId(fixture.field(row, 1, TRAN_TYPE_CD_WIDTH),
                    Integer.parseInt(fixture.field(row, 1 + TRAN_TYPE_CD_WIDTH, TRAN_CAT_CD_WIDTH))));
        }

        final List<String> lexicographic = byteImages.stream().sorted().toList();
        final List<String> numeric = keys.stream()
                .sorted(Comparator.comparing(TransactionCategoryId::getTranTypeCd)
                        .thenComparingInt(TransactionCategoryId::getTranCatCd))
                .map(TransactionCategoryIdTest::byteImageOf)
                .toList();

        assertThat(numeric)
                .as("VSAM browses in key byte order. Because TRAN-CAT-CD is zero padded fixed width unsigned "
                        + "digits, sorting the raw six byte images and sorting the decoded components give "
                        + "the same sequence, so mapping the component to Integer preserves browse ordering "
                        + "rather than perturbing it")
                .containsExactlyElementsOf(lexicographic);
    }

    @Test
    @DisplayName("the fixture's trailing FILLER is zero filled at columns 57-60, outside the key")
    void theFixtureTrailingFillerIsZeroFilledOutsideTheKey() {
        final FixtureLoader.FixtureData fixture =
                FixtureLoader.load(FixtureLoader.Fixture.TRANSACTION_CATEGORY);

        for (int row = 0; row < fixture.recordCount(); row++) {
            assertThat(fixture.field(row, FILLER_COLUMN, FILLER_WIDTH))
                    .as("FILLER PIC X(04) at app/cpy/CVTRA04Y.cpy:L9 is zero filled rather than blank in "
                            + "every row, including row %d. It sits well past the six byte key and is "
                            + "asserted here only to prove that the key occupies columns 1-6 and nothing "
                            + "beyond them", row)
                    .isEqualTo("0000");
        }
    }

    @Test
    @DisplayName("the one key the category fixture adds over the disclosure DEFAULT block is 010005, Interest Amount")
    void theOneExtraKeyOverTheDisclosureDefaultBlockIsTheInterestCategory() {
        final FixtureLoader.FixtureData categories =
                FixtureLoader.load(FixtureLoader.Fixture.TRANSACTION_CATEGORY);
        final FixtureLoader.FixtureData disclosures =
                FixtureLoader.load(FixtureLoader.Fixture.DISCLOSURE_GROUP);

        final Set<String> categoryKeys = new LinkedHashSet<>();
        for (int row = 0; row < categories.recordCount(); row++) {
            categoryKeys.add(categories.field(row, 1, CATALOGUED_KEY_LENGTH));
        }

        final Set<String> defaultKeys = new LinkedHashSet<>();
        for (int row = 0; row < disclosures.recordCount(); row++) {
            if (DEFAULT_GROUP_ID.equals(disclosures.field(row, 1, DISCLOSURE_GROUP_ID_WIDTH).strip())) {
                defaultKeys.add(disclosures.field(row, DISCLOSURE_PAIR_COLUMN, CATALOGUED_KEY_LENGTH));
            }
        }

        assertThat(defaultKeys)
                .as("app/data/ASCII/discgrp.txt carries 17 DEFAULT rows, whose group identifier is the bare "
                        + "literal DEFAULT padded into a X(10) field, hence the strip on that slice alone")
                .hasSize(DEFAULT_BLOCK_SIZE);
        assertThat(categoryKeys)
                .as("the 18 category keys are a strict superset of the 17 DEFAULT rate keys")
                .hasSize(FIXTURE_RECORD_COUNT)
                .containsAll(defaultKeys);

        final Set<String> extra = new LinkedHashSet<>(categoryKeys);
        extra.removeAll(defaultKeys);

        assertThat(extra)
                .as("exactly one key has no DEFAULT rate row, and it is the interest output category the "
                        + "interest calculation writes rather than an input to any rate lookup. The two sets "
                        + "are therefore correctly unequal, and no DEFAULT row may be added anywhere to "
                        + "balance them: fabricating seed data the corpus does not contain would corrupt the "
                        + "parity oracle")
                .containsExactly(INTEREST_KEY_IMAGE);

        final int interestRow = new ArrayList<>(categoryKeys).indexOf(INTEREST_KEY_IMAGE);

        assertThat(categories.field(interestRow, DESCRIPTION_COLUMN, DESCRIPTION_WIDTH).strip())
                .as("the description at columns 7-56 of that row identifies it beyond doubt")
                .isEqualTo(INTEREST_DESCRIPTION);
    }

    // Private helpers: parsing the frozen corpus, and reflecting over the subject

    /**
     * Renders a key back to its fixed width VSAM byte image, zero padding the category code to four digits.
     *
     * @param key the identifier to render
     * @return the six character image, {@code TRAN-TYPE-CD} followed by a zero padded {@code TRAN-CAT-CD}
     */
    private static String byteImageOf(final TransactionCategoryId key) {
        return key.getTranTypeCd() + String.format(Locale.ROOT, "%04d", key.getTranCatCd());
    }

    /**
     * Returns the declared instance fields of the subject, in declaration order.
     *
     * <p>Static and synthetic members are filtered out, so what remains is exactly the persistent component
     * set: the constants and {@code serialVersionUID} are excluded, and so is any field the compiler adds.
     *
     * @return the persistent components in declaration order
     */
    private static List<Field> persistentFields() {
        return Arrays.stream(TransactionCategoryId.class.getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .toList();
    }

    /**
     * Reads the widths of the items nested directly inside a named COBOL group, in declaration order.
     *
     * <p>This exists because the collision this file guards against is one of group <em>nesting</em>: two
     * copybooks declare a group of the same name with different subordinate items. A parser that flattened
     * the record, as a general purpose layout reader must, could not express that difference at all, so the
     * group structure is read here directly.
     *
     * <p>The scan keeps only level 10 items while the enclosing level 05 group is the one requested. Reaching
     * any other level 05 item closes the group, which is what bounds the scan without needing a lookahead.
     * Comment lines, whose seventh column is an asterisk in fixed format COBOL, are skipped.
     *
     * @param copybook the frozen copybook to read
     * @param groupName the level 05 group whose subordinate items are wanted, without a trailing period
     * @return the width of each nested item, in declaration order; empty if the group is absent
     * @throws IOException if the copybook cannot be read
     */
    private static List<Integer> subordinateWidths(final Path copybook, final String groupName)
            throws IOException {
        final List<Integer> widths = new ArrayList<>();
        boolean insideGroup = false;

        for (final String line : Files.readAllLines(copybook, StandardCharsets.UTF_8)) {
            final String statement = line.strip();
            if (statement.isEmpty() || statement.startsWith("*")) {
                continue;
            }
            final String[] tokens = statement.split("\\s+");
            if (tokens.length < 2) {
                continue;
            }
            if ("05".equals(tokens[0])) {
                insideGroup = groupName.equals(stripPeriod(tokens[1]));
            } else if ("10".equals(tokens[0]) && insideGroup) {
                widths.add(pictureWidth(copybook, statement, tokens));
            }
        }
        return widths;
    }

    /**
     * Extracts the declared width from the {@code PIC} clause of a single COBOL data description.
     *
     * @param copybook the copybook being read, named in any failure message
     * @param statement the whole statement, named in any failure message
     * @param tokens the statement split on whitespace
     * @return the parenthesised width, so {@code PIC X(02)} yields 2 and {@code PIC 9(11)} yields 11
     * @throws AssertionError if the statement carries no parenthesised picture clause. Every item nested in
     *     either {@code TRAN-CAT-KEY} group is {@code X(nn)} or {@code 9(nn)}, so this signals that the
     *     frozen copybook changed shape rather than that the parser is too narrow
     */
    private static int pictureWidth(final Path copybook, final String statement, final String[] tokens) {
        for (int token = 0; token < tokens.length - 1; token++) {
            if (!"PIC".equals(tokens[token])) {
                continue;
            }
            final String picture = stripPeriod(tokens[token + 1]);
            final int open = picture.indexOf('(');
            final int close = picture.indexOf(')');
            if (open < 0 || close < open) {
                break;
            }
            return Integer.parseInt(picture.substring(open + 1, close));
        }
        throw new AssertionError("no parenthesised PIC clause in " + copybook + ": " + statement);
    }

    /**
     * Removes the statement terminating period from a COBOL token, if it carries one.
     *
     * @param token a whitespace delimited token such as {@code TRAN-CAT-KEY.} or {@code X(02).}
     * @return the token without a trailing period
     */
    private static String stripPeriod(final String token) {
        return token.endsWith(".") ? token.substring(0, token.length() - 1) : token;
    }

    /**
     * Reads one numeric attribute off an IDCAMS {@code LISTCAT} line.
     *
     * <p>The listing pads each attribute name out to its value with a run of hyphens, as in
     * {@code KEYLEN-----------------6}. Skipping the run rather than counting it keeps the assertion readable
     * and immune to the differing pad widths the utility emits for differently named attributes.
     *
     * @param line the catalogue line to read
     * @param attribute the attribute name, such as {@code KEYLEN} or {@code AVGLRECL}
     * @return the numeric value that follows the hyphen run
     * @throws AssertionError if the attribute is absent from the line or carries no digits
     */
    private static int catalogueAttribute(final String line, final String attribute) {
        final int at = line.indexOf(attribute);
        if (at < 0) {
            throw new AssertionError(attribute + " is not on catalogue line: " + line);
        }
        int cursor = at + attribute.length();
        while (cursor < line.length() && line.charAt(cursor) == '-') {
            cursor++;
        }
        final int firstDigit = cursor;
        while (cursor < line.length() && Character.isDigit(line.charAt(cursor))) {
            cursor++;
        }
        if (cursor == firstDigit) {
            throw new AssertionError(attribute + " carries no numeric value on catalogue line: " + line);
        }
        return Integer.parseInt(line.substring(firstDigit, cursor));
    }
}

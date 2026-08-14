/*
 * ******************************************************************
 * Program     : DisclosureGroupIdTest.java
 * Application : CardDemo
 * Type        : Java 25 / JUnit 5 unit test - pure JVM tier, no
 *               container, no Spring context, no database, no live
 *               endpoint
 * Function    : Holds com.cardemo.model.key.DisclosureGroupId to the
 *               DIS-GROUP-KEY field contract: three components in
 *               copybook order summing to the catalogued key length of
 *               16 bytes, the PIC-derived width and range guards, a
 *               total equals/hashCode identity over all three
 *               components, distinctness from the two sibling
 *               composite identifiers, and the trailing-blank
 *               behaviour the DEFAULT group fallback depends on.
 * Source      : app/cpy/CVTRA02Y.cpy (key 16) @ 7756d89
 *               app/catlg/LISTCAT.txt:L896 - KEYLEN 16, AVGLRECL 50,
 *               under CLUSTER--AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS at
 *               :L894, with RKP 0 at :L897 and UNIQUE INDEXED at :L898
 *               app/cbl/CBACT04C.cbl:L107 - COPY CVTRA02Y - and
 *               :L415-L440 and :L443-L460 - the DEFAULT group fallback
 *               app/data/ASCII/discgrp.txt - 51 rows, three groups of
 *               seventeen, 50 bytes each
 *               app/data/ASCII/acctdata.txt - ACCT-GROUP-ID is ten
 *               spaces on all 50 rows
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
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit test for {@link DisclosureGroupId}, the {@code @Embeddable} replacement for the three-component
 * {@code DIS-GROUP-KEY} group of the disclosure-group (interest rate) record layout.
 *
 * <h2>1. What it does</h2>
 *
 * <p>It holds {@link DisclosureGroupId} to the field contract of the frozen legacy corpus, every locator read
 * first hand at the traceability anchor commit {@code 7756d89}:
 *
 * <ol>
 *   <li><strong>The component set is closed, ordered and 16 bytes wide.</strong>
 *       {@code app/cpy/CVTRA02Y.cpy:L5-L8} declares {@code 05 DIS-GROUP-KEY.} followed by exactly three
 *       subordinate items - {@code DIS-ACCT-GROUP-ID PIC X(10).}, {@code DIS-TRAN-TYPE-CD PIC X(02).} and
 *       {@code DIS-TRAN-CAT-CD PIC 9(04).} - so 10 + 2 + 4 = 16. That length is corroborated four independent
 *       ways: the copybook arithmetic; {@code app/catlg/LISTCAT.txt:L896} reading
 *       {@code KEYLEN----------------16     AVGLRECL--------------50} beneath
 *       {@code CLUSTER--AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS} at {@code :L894};
 *       {@code app/catlg/LISTCAT.txt:L897} reading {@code RKP--------------------0}, so the key is the record
 *       prefix, with {@code UNIQUE} and {@code INDEXED} at {@code :L898}; and the seed fixture
 *       {@code app/data/ASCII/discgrp.txt:L1}, whose first record splits {@code A000000000} + {@code 01} +
 *       {@code 0001} exactly 10/2/4 before the six-byte rate and 28 filler bytes. Line {@code L896} is cited
 *       precisely because {@code :L202} (CARDDATA) and {@code :L403} (CARDXREF) also report {@code KEYLEN 16}
 *       for entirely different clusters.</li>
 *   <li><strong>The declared order is the copybook order, not the order the consumer assigns.</strong>
 *       {@code app/cbl/CBACT04C.cbl:L210-L212} populates the lookup key as group id, then <em>category</em>,
 *       then <em>type</em>. Assignment order has no bearing on key layout; this test asserts the copybook
 *       order and treats those MOVE statements as the trap they are. <em>Ordering safety:</em> all three
 *       components are fixed-width and the numeric one is zero-padded display digits, so lexicographic byte
 *       order - the order a VSAM browse follows - coincides with numeric order over the whole
 *       {@code PIC 9(04)} domain. Mapping that component to {@link Integer} therefore preserves browse
 *       sequence rather than reshuffling it, and re-emitting it as four zero-padded digits is the job of
 *       whichever layer writes fixed-width records, not of this identifier.</li>
 *   <li><strong>Trailing blanks are significant to the identifier.</strong> {@code app/cbl/CBACT04C.cbl:L437}
 *       moves the seven-character literal {@code 'DEFAULT'} into {@code PIC X(10)}, which space-pads it, and
 *       {@code app/data/ASCII/discgrp.txt:L18} carries exactly that padded image. The test pins the decision
 *       recorded in section 3 below rather than leaving it to be inferred.</li>
 *   <li><strong>Identity is total over all three components.</strong> Reflexive, symmetric, transitive,
 *       consistent across repeated invocations, {@code null}-safe, and sensitive to every single component -
 *       because JPA resolves an {@code @EmbeddedId} through {@code equals} and {@code hashCode}.</li>
 *   <li><strong>The type is distinct from its two siblings.</strong> A {@link DisclosureGroupId} is never
 *       equal to, and never interchangeable with, a {@link TransactionCategoryBalanceId} or a
 *       {@link TransactionCategoryId}, even when the shared {@code tran_type_cd} and {@code tran_cat_cd}
 *       values coincide.</li>
 *   </ol>
 *
 * <h2>2. How to run, build and test</h2>
 *
 * <pre>{@code
 * ./mvnw -B -ntp test                                    # whole unit tier
 * ./mvnw -B -ntp test -Dtest=DisclosureGroupIdTest       # this class alone
 * ./mvnw -B -ntp verify                                  # adds the JaCoCo line-coverage floor
 * }</pre>
 *
 * <p>This class is collected by <strong>Surefire</strong> 3.5.4, whose {@code includes} are
 * {@code **}{@code /*Test.java} and {@code **}{@code /*Tests.java} and whose {@code excludes} are
 * {@code **}{@code /integration/**} and {@code **}{@code /e2e/**}. Residency under
 * {@code src/test/java/com/cardemo/unit/model} is therefore load-bearing: a class moved into the integration
 * or end-to-end tree would be collected by neither plugin and would silently stop running, with the build
 * still green and coverage quietly falling. Do not rename or relocate it. Compilation runs under
 * {@code --release 25} with {@code -Xlint:all}, {@code -Werror} and {@code failOnWarning}, so a single unused
 * import or raw type fails the build rather than emitting a warning.
 *
 * <p><strong>How to confirm it really ran</strong>, which is the check that closes the residency hazard. The
 * console prints one {@code Tests run} line per {@code @Nested} group and one accurate total. In
 * {@code target/surefire-reports/TEST-com.cardemo.unit.model.DisclosureGroupIdTest.xml}, count the
 * {@code <testcase>} elements: every test in this class is recorded there. Do <em>not</em> read the
 * {@code tests} attribute of the enclosing {@code <testsuite>} element - with {@code @Nested} groups and no
 * test method directly on the outer class, Surefire 3.5.4 leaves that attribute at {@code 0} and puts the
 * nested group's display name in each {@code classname}. Measured, and identical for the pre-existing
 * {@code CompositeKeyContractTest}, so it is established behaviour of this test tier rather than a symptom of
 * a class that failed to run. It is recorded here because a {@code tests="0"} attribute looks exactly like the
 * silent non-collection the residency hazard describes, and confusing the two would waste an investigation.
 *
 * <h2>3. Key configuration and defaults</h2>
 *
 * <p><strong>No clock and no mock.</strong> {@link FixedClockProvider} is deliberately not used: a composite
 * identifier has no temporal component, so there is no wall-clock, time-zone or locale input to freeze and
 * determinism here is unconditional rather than configured. For the same reason no Mockito strictness setting
 * applies - this class creates no test double at all, because the type under test has no collaborator. Where
 * a case transformation appears it passes {@link Locale#ROOT} explicitly, so a Turkish or Azeri default locale
 * cannot change the outcome. {@link FixtureLoader} <em>is</em> used, because the frozen seed fixtures are
 * first-hand evidence for the byte geometry this key claims; it reads byte-identical copies of
 * {@code app/data/ASCII/**} from the test classpath and never reaches into the frozen corpus itself.
 *
 * <p><strong>The trailing-blank decision, stated explicitly.</strong> {@code "DEFAULT"} and
 * {@code "DEFAULT   "} are <em>different keys</em> in Java and this test asserts that they are. The reasoning
 * is that an identifier must remain a faithful carrier of the sixteen key bytes: two distinct byte images may
 * not collapse onto one value, and {@link DisclosureGroupId} accordingly neither trims nor pads nor case-folds
 * any component. Blank-insensitive matching is delegated to the storage layer, where SQL {@code CHAR} ignores
 * trailing blanks on comparison - which is exactly why {@code V1__create_schema.sql} declares
 * {@code acct_group_id CHAR(10)} rather than {@code VARCHAR(10)}, and why the fallback at
 * {@code app/cbl/CBACT04C.cbl:L437} matches whether the literal arrives padded or bare. Switching that column
 * to {@code VARCHAR} would break the fallback for an unpadded caller; that consequence is recorded here rather
 * than discovered later. The literal {@code DEFAULT} itself belongs to the interest-calculation path and is
 * deliberately <em>not</em> a constant on the key class, so this test also asserts its absence there.
 *
 * <p><strong>What this test deliberately does not assert.</strong> {@code DIS-INT-RATE PIC S9(04)V99} at
 * {@code app/cpy/CVTRA02Y.cpy:L9} maps to {@code NUMERIC(6,2)} - six characters, the only column of that
 * precision in the schema, and never the {@code NUMERIC(12,2)} used for account money. It sits outside
 * {@code DIS-GROUP-KEY}, so this key carries no decimal component and the rate's own contract is asserted on
 * the entity test rather than here. The context matters all the same, because it explains why the category
 * code below may legitimately be any value in its domain: a <em>zero</em> rate is a real, seeded value, not a
 * missing one. Seven of the seventeen {@code DEFAULT} rows carry it - the type-and-category pairs
 * {@code 020001}, {@code 020002}, {@code 020003}, {@code 030001}, {@code 030002}, {@code 030003} and
 * {@code 070001} - and {@code app/cbl/CBACT04C.cbl:L214} guards on it with {@code IF DIS-INT-RATE NOT = 0}
 * rather than treating it as absent. No {@code @Positive} and no {@code @Min(1)} may therefore appear on the
 * rate, and no absolute-value normalisation anywhere: every rate overpunch across all 51 fixture rows is
 * {@code &#123;}, which decodes to {@code +0} under the position-aware table
 * {@code &#123;} to {@code +0}, {@code A}-{@code I} to {@code +1}-{@code +9}, {@code &#125;} to {@code -0} and
 * {@code J}-{@code R} to {@code -1}-{@code -9}, read from the picture clause and never from the character
 * alone.
 *
 * <h2>4. Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>The build fails with a {@code serial} warning on the production type.</strong> A
 *       {@link Serializable} class with no explicitly declared {@code serialVersionUID} is a hard failure
 *       under {@code -Xlint:all -Werror}, not a warning. Restore the field; do not weaken the compiler
 *       configuration.</li>
 *   <li><strong>An identity assertion fails.</strong> {@code equals} or {@code hashCode} stopped covering all
 *       three components, or became asymmetric. This does not surface as a clean failure in production: it
 *       surfaces as {@code find}, {@code merge} and dirty-checking resolving the wrong row, which is an
 *       intermittent data error. Restore totality over all three components.</li>
 *   <li><strong>A component-order assertion fails.</strong> Someone alphabetised the fields, or inferred the
 *       order from the {@code MOVE} statements at {@code app/cbl/CBACT04C.cbl:L210-L212}. The copybook is
 *       authoritative; restore {@code accountGroupId}, {@code tranTypeCd}, {@code tranCatCd}.</li>
 *   <li><strong>A DEFAULT-group lookup finds nothing.</strong> Almost always a {@code "DEFAULT"} versus
 *       {@code "DEFAULT   "} mismatch. Against {@code CHAR(10)} either form matches; against {@code VARCHAR}
 *       only the padded form does; and in Java the two are never equal. Pass the value as the source presents
 *       it. When the default row is genuinely absent the legacy behaviour is an abend, per
 *       {@code app/cbl/CBACT04C.cbl:L443-L460}.</li>
 *   <li><strong>A width assertion fails.</strong> A {@code @Column} length or a guard constant drifted from
 *       its PIC clause. The copybook is frozen: correct the mapping, never the expectation.</li>
 *   </ul>
 *
 * <h2>5. Findings, classified</h2>
 *
 * <ul>
 *   <li><strong>Blocker</strong> - none open. The Surefire residency hazard described in section 2 is a
 *       standing blocker <em>if</em> the class is relocated; it is closed by the path this file occupies.</li>
 *   <li><strong>High</strong> - inferring the component order from {@code app/cbl/CBACT04C.cbl:L210-L212}, and
 *       a missing or asymmetric {@code equals}/{@code hashCode}. Both are asserted here. Remediation: keep the
 *       copybook order and keep identity total over all three components.</li>
 *   <li><strong>Medium</strong> - the Hibernate JDBC type-code hazard. Hibernate 6's schema validator compares
 *       JDBC type codes, and {@code spring.jpa.hibernate.ddl-auto: validate} is set in every profile, so an
 *       {@link Integer} component over a {@code NUMERIC(4)} column can fail startup where {@code INTEGER}
 *       passes. Remediation: keep {@code tran_cat_cd} an {@code INTEGER} column and leave the component
 *       without a {@code columnDefinition} so the JDBC type code governs, which is what this test asserts.
 *       Also Medium: all seventeen {@code DEFAULT} rows of {@code app/data/ASCII/discgrp.txt} are load-bearing,
 *       because {@code ACCT-GROUP-ID} is ten spaces on all fifty rows of {@code app/data/ASCII/acctdata.txt},
 *       so the first lookup misses for every account and the fallback is always taken. Deleting any of the
 *       seventeen abends {@code CBACT04C}. Both fixture facts are asserted here.</li>
 *   <li><strong>Low</strong> - the fixture's 28 filler bytes are zeros rather than the blanks
 *       {@code PIC X(28)} implies. Outside the key and asserted nowhere; recorded for accuracy only. Also Low:
 *       the {@code tests="0"} reporting quirk described in section 2. Remediation: count {@code <testcase>}
 *       elements, or read the console total, rather than the enclosing suite's attribute.</li>
 *   </ul>
 *
 * <p><strong>Not available from this tier:</strong> JDBC-level type-code verification of the three columns.
 * Establishing it needs a live PostgreSQL instance and a Hibernate {@code validate} pass, which are out of
 * scope for a pure-JVM test; it belongs to the repository integration tier. What <em>is</em> available and is
 * cited rather than invented: {@code src/main/resources/db/migration/V1__create_schema.sql} declares
 * {@code acct_group_id CHAR(10)}, {@code tran_type_cd CHAR(2)}, {@code tran_cat_cd INTEGER} and
 * {@code CONSTRAINT pk_disclosure_group PRIMARY KEY (acct_group_id, tran_type_cd, tran_cat_cd)}. No SQL type
 * is guessed anywhere in this file.
 *
 * @see DisclosureGroupId
 * @see FixtureLoader
 */
@DisplayName("DisclosureGroupId: the 16-byte DIS-GROUP-KEY contract of app/cpy/CVTRA02Y.cpy")
class DisclosureGroupIdTest {

    /** {@code DIS-ACCT-GROUP-ID PIC X(10)} - app/cpy/CVTRA02Y.cpy:L6. */
    private static final int ACCOUNT_GROUP_ID_WIDTH = 10;

    /** {@code DIS-TRAN-TYPE-CD PIC X(02)} - app/cpy/CVTRA02Y.cpy:L7. */
    private static final int TRAN_TYPE_CD_WIDTH = 2;

    /** {@code DIS-TRAN-CAT-CD PIC 9(04)} - app/cpy/CVTRA02Y.cpy:L8, four display digits wide. */
    private static final int TRAN_CAT_CD_WIDTH = 4;

    /** The key length recorded at app/catlg/LISTCAT.txt:L896 for the DISCGRP cluster: 10 + 2 + 4. */
    private static final int CATALOGUED_KEY_LENGTH = 16;

    /** The record length recorded at the same line, and the width of every fixture row. */
    private static final int CATALOGUED_RECORD_LENGTH = 50;

    /**
     * The group id exactly as {@code app/cbl/CBACT04C.cbl:L437} presents it and as
     * {@code app/data/ASCII/discgrp.txt:L18} stores it: {@code DEFAULT} space-padded to {@code PIC X(10)}.
     */
    private static final String PADDED_DEFAULT_GROUP = "DEFAULT   ";

    /** The same literal unpadded, as it appears in the COBOL source text. A different key, deliberately. */
    private static final String BARE_DEFAULT_GROUP = "DEFAULT";

    /** {@code DIS-TRAN-TYPE-CD} of the first fixture row, at its exact two-character width. */
    private static final String TYPE_CD = "01";

    /** {@code DIS-TRAN-CAT-CD} of the first fixture row, {@code 0001} decoded. */
    private static final int CAT_CD = 1;

    /** The three key columns in composite primary-key order, per app/cpy/CVTRA02Y.cpy:L6-L8. */
    private static final List<String> KEY_COLUMNS_IN_ORDER =
            List.of("acct_group_id", "tran_type_cd", "tran_cat_cd");

    /** The three Java components in the same order, which is the copybook order. */
    private static final List<String> KEY_COMPONENTS_IN_ORDER =
            List.of("accountGroupId", "tranTypeCd", "tranCatCd");

    /** The only imports {@link DisclosureGroupId} is permitted to declare. */
    private static final List<String> PERMITTED_IMPORTS = List.of(
            "jakarta.persistence.Column",
            "jakarta.persistence.Embeddable",
            "java.io.Serializable",
            "java.util.Objects");

    /** The Java-serialization callbacks the type must not declare, so it has no deserialization surface. */
    private static final List<String> SERIALIZATION_HOOKS =
            List.of("readObject", "readObjectNoData", "readResolve", "writeObject", "writeReplace");

    /** Source of the type under test, relative to the Surefire working directory {@code ${project.basedir}}. */
    private static final Path KEY_SOURCE =
            Path.of("src", "main", "java", "com", "cardemo", "model", "key", "DisclosureGroupId.java");

    /** The migration that owns the {@code disclosure_group} table. */
    private static final Path SCHEMA_MIGRATION =
            Path.of("src", "main", "resources", "db", "migration", "V1__create_schema.sql");

    /** 1-based column of {@code ACCT-GROUP-ID} in the 300-byte account record of app/cpy/CVACT01Y.cpy. */
    private static final int ACCOUNT_GROUP_ID_COLUMN = 113;

    /** Rows per group in app/data/ASCII/discgrp.txt: three groups of seventeen across 51 records. */
    private static final int ROWS_PER_GROUP = 17;

    /**
     * A representative valid key: the padded DEFAULT group, type {@code 01}, category {@code 0001}, which is
     * the key {@code app/data/ASCII/discgrp.txt:L18} carries.
     *
     * @return a fully populated identifier, never {@code null}
     */
    private static DisclosureGroupId defaultGroupKey() {
        return new DisclosureGroupId(PADDED_DEFAULT_GROUP, TYPE_CD, CAT_CD);
    }

    /**
     * The persistent instance fields of the type under test, in declaration order.
     *
     * <p>Static members are excluded because they are compile-time constants rather than key components, and
     * synthetic members because a coverage agent may add them. Declaration order is what
     * {@code getDeclaredFields} reports for a class compiled by {@code javac}, and it is the order this test
     * asserts against the copybook; the alternative would be to assert nothing about order, which is precisely
     * the property that matters for a composite key.
     *
     * @return the three key components, in declaration order
     */
    private static List<Field> persistentFields() {
        return Arrays.stream(DisclosureGroupId.class.getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .toList();
    }

    /**
     * The names of the persistent instance fields, in declaration order.
     *
     * @return the component names, in declaration order
     */
    private static List<String> persistentFieldNames() {
        return persistentFields().stream().map(Field::getName).toList();
    }

    /**
     * The {@code @Column} mapping of one named component.
     *
     * @param componentName the Java field name
     * @return the annotation, or {@code null} when the field carries none
     * @throws AssertionError if no field of that name is declared
     */
    private static Column columnOf(final String componentName) {
        return persistentFields().stream()
                .filter(field -> field.getName().equals(componentName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(componentName
                        + " is not a declared component of DisclosureGroupId; declared components are "
                        + persistentFieldNames()))
                .getAnnotation(Column.class);
    }

    /**
     * The declared static fields of the type under test, synthetic members excluded.
     *
     * @return the static fields
     */
    private static List<Field> staticFields() {
        return Arrays.stream(DisclosureGroupId.class.getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .filter(field -> Modifier.isStatic(field.getModifiers()))
                .toList();
    }

    /**
     * Reads one repository file as UTF-8 text.
     *
     * <p>Resolved against the Surefire working directory, which {@code pom.xml} pins to
     * {@code ${project.basedir}}. The {@link IOException} is wrapped rather than swallowed so the root cause
     * survives, and the message names the absolute path that was attempted so a working-directory problem is
     * self-diagnosing.
     *
     * @param path a repository-relative path
     * @return the file content
     * @throws UncheckedIOException if the file cannot be read, with the original exception as its cause
     */
    private static String sourceText(final Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (final IOException cause) {
            throw new UncheckedIOException("Cannot read " + path.toAbsolutePath()
                    + ". This test resolves repository files against the Surefire working directory, which "
                    + "pom.xml pins to ${project.basedir}; run it from the repository root.", cause);
        }
    }

    /**
     * The code-bearing lines of a Java source file, with comments removed.
     *
     * <p>Comment text is stripped so that a token scan cannot be tripped by prose: the production class
     * legitimately discusses floating-point types and object streams in its documentation while declaring
     * neither. Handles the three comment forms this repository uses - a block comment whose lines are
     * asterisk-prefixed, a line comment, and a trailing line comment - which is sufficient for the file under
     * inspection and is asserted to yield a non-empty result so that a silent over-strip cannot pass.
     *
     * @param source the full source text
     * @return the code lines, comments removed and blank lines dropped
     */
    private static List<String> codeLines(final String source) {
        final List<String> code = new ArrayList<>();
        boolean inBlockComment = false;
        for (final String rawLine : source.split("\n", -1)) {
            String line = rawLine;
            if (inBlockComment) {
                final int blockEnd = line.indexOf("*/");
                if (blockEnd < 0) {
                    continue;
                }
                inBlockComment = false;
                line = line.substring(blockEnd + 2);
            }
            final int blockStart = line.indexOf("/*");
            if (blockStart >= 0) {
                final int blockEnd = line.indexOf("*/", blockStart + 2);
                if (blockEnd < 0) {
                    inBlockComment = true;
                    line = line.substring(0, blockStart);
                } else {
                    line = line.substring(0, blockStart) + line.substring(blockEnd + 2);
                }
            }
            final int lineComment = line.indexOf("//");
            if (lineComment >= 0) {
                line = line.substring(0, lineComment);
            }
            if (!line.isBlank()) {
                code.add(line);
            }
        }
        return List.copyOf(code);
    }

    /**
     * The fully qualified types a Java source file imports, in declaration order.
     *
     * @param source the full source text
     * @return the imported type names, {@code static} imports included and reported without the keyword
     */
    private static List<String> importedTypes(final String source) {
        return codeLines(source).stream()
                .map(String::strip)
                .filter(line -> line.startsWith("import "))
                .map(line -> line.substring("import ".length()))
                .map(line -> line.startsWith("static ") ? line.substring("static ".length()) : line)
                .map(line -> line.endsWith(";") ? line.substring(0, line.length() - 1) : line)
                .map(String::strip)
                .toList();
    }

    /**
     * The composite primary-key columns the migration declares for {@code disclosure_group}, in order.
     *
     * <p>Deliberately narrow: it reads only the one {@code PRIMARY KEY} clause this key class owns. The wider
     * shape of the migration - every table, column type, constraint and index - is asserted by
     * {@code SchemaStructureTest}, and duplicating that here would create two places to disagree. What this
     * method establishes is the one fact neither file can assert alone: that the key class and the migration
     * name the same three columns in the same order.
     *
     * @return the primary-key columns, in declaration order
     * @throws AssertionError if the table or its primary-key clause cannot be located
     */
    private static List<String> migrationPrimaryKeyColumns() {
        final String migration = sourceText(SCHEMA_MIGRATION);
        final int table = migration.indexOf("CREATE TABLE disclosure_group");
        if (table < 0) {
            throw new AssertionError("CREATE TABLE disclosure_group is absent from " + SCHEMA_MIGRATION
                    + ", so the key class cannot be reconciled with the schema");
        }
        final int clause = migration.indexOf("PRIMARY KEY (", table);
        final int close = clause < 0 ? -1 : migration.indexOf(')', clause);
        if (clause < 0 || close < 0) {
            throw new AssertionError("disclosure_group declares no PRIMARY KEY clause in " + SCHEMA_MIGRATION
                    + "; a composite identifier without a matching primary key cannot resolve a row");
        }
        final String columns = migration.substring(clause + "PRIMARY KEY (".length(), close);
        return Arrays.stream(columns.split(",")).map(String::strip).toList();
    }

    /**
     * The no-argument constructor Jakarta Persistence requires.
     *
     * @return the declared no-argument constructor
     * @throws NoSuchMethodException if the type declares none
     */
    private static Constructor<DisclosureGroupId> noArgumentConstructor() throws NoSuchMethodException {
        return DisclosureGroupId.class.getDeclaredConstructor();
    }

    /**
     * The all-components constructor, looked up by its parameter types in copybook order.
     *
     * @return the declared three-argument constructor
     * @throws NoSuchMethodException if no constructor takes {@code (String, String, Integer)}
     */
    private static Constructor<DisclosureGroupId> allComponentsConstructor() throws NoSuchMethodException {
        return DisclosureGroupId.class.getDeclaredConstructor(String.class, String.class, Integer.class);
    }

    /**
     * Instantiates the type through its no-argument constructor, unsealing it first.
     *
     * <p>That constructor is deliberately {@code protected}: an {@code @Embeddable} identifier must expose one
     * for the persistence provider, and {@code protected} is the narrowest visibility the specification
     * permits. Hibernate reaches it after {@code setAccessible(true)}, so the test reaches it the same way.
     * Calling it directly would require public visibility, which is exactly what the type must not have.
     *
     * @return a staged instance whose components are all unset
     * @throws ReflectiveOperationException if the constructor is absent or cannot be invoked
     */
    private static DisclosureGroupId stagedInstance() throws ReflectiveOperationException {
        final Constructor<DisclosureGroupId> constructor = noArgumentConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    /**
     * Every type that appears on the declared member signatures of the type under test.
     *
     * <p>Field types, method return types, method parameter types and constructor parameter types. Method
     * bodies are invisible to reflection, which is why the source-text scans complement this.
     *
     * @return the distinct types named by the declared surface
     */
    private static Set<Class<?>> declaredSurfaceTypes() {
        final Set<Class<?>> types = new HashSet<>();
        for (final Field field : DisclosureGroupId.class.getDeclaredFields()) {
            types.add(field.getType());
        }
        for (final Method method : DisclosureGroupId.class.getDeclaredMethods()) {
            types.add(method.getReturnType());
            types.addAll(Arrays.asList(method.getParameterTypes()));
        }
        for (final Constructor<?> constructor : DisclosureGroupId.class.getDeclaredConstructors()) {
            types.addAll(Arrays.asList(constructor.getParameterTypes()));
        }
        return types;
    }

    /**
     * The disclosure-group seed fixture, byte-identical to {@code app/data/ASCII/discgrp.txt}.
     *
     * @return the loaded fixture, its record census already verified by the loader
     */
    private static FixtureLoader.FixtureData disclosureGroupFixture() {
        return FixtureLoader.load(FixtureLoader.Fixture.DISCLOSURE_GROUP);
    }

    /**
     * The field contract of {@code DIS-GROUP-KEY}: three components, 10 + 2 + 4 = 16 bytes, each mapped to a
     * named column, in the one order that reproduces the VSAM key layout.
     */
    @Nested
    @DisplayName("1. Field contract: three components, 10 + 2 + 4 = 16 bytes, explicitly mapped")
    class FieldContract {

        @Test
        @DisplayName("declares exactly the three DIS-GROUP-KEY components, in copybook order and no others")
        void declaresExactlyThreeComponentsInCopybookOrder() {
            assertThat(persistentFieldNames())
                    .as("app/cpy/CVTRA02Y.cpy:L6-L8 declares DIS-ACCT-GROUP-ID, DIS-TRAN-TYPE-CD and "
                            + "DIS-TRAN-CAT-CD in that order and nothing else; DIS-INT-RATE at :L9 and the "
                            + "FILLER at :L10 sit outside DIS-GROUP-KEY and belong to the entity")
                    .containsExactlyElementsOf(KEY_COMPONENTS_IN_ORDER);
        }

        @Test
        @DisplayName("the three PIC widths sum to the KEYLEN 16 recorded at app/catlg/LISTCAT.txt:L896")
        void componentWidthsSumToTheCataloguedKeyLength() {
            assertThat(ACCOUNT_GROUP_ID_WIDTH + TRAN_TYPE_CD_WIDTH + TRAN_CAT_CD_WIDTH)
                    .as("X(10) + X(02) + 9(04) is the 16-byte key that app/catlg/LISTCAT.txt:L896 records "
                            + "for CLUSTER--AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS at :L894; :L202 and :L403 "
                            + "report KEYLEN 16 for other clusters, which is why :L896 is cited precisely")
                    .isEqualTo(CATALOGUED_KEY_LENGTH);
        }

        @ParameterizedTest
        @CsvSource({
            "accountGroupId, acct_group_id",
            "tranTypeCd,     tran_type_cd",
            "tranCatCd,      tran_cat_cd",
        })
        @DisplayName("maps every component to an explicitly named NOT NULL column")
        void mapsEveryComponentToAnExplicitlyNamedNotNullColumn(final String component, final String column) {
            final Column mapping = columnOf(component);

            assertThat(mapping)
                    .as("%s must be mapped explicitly; left to a naming strategy, a column could be "
                            + "renamed by configuration and the key would stop resolving", component)
                    .isNotNull();
            assertThat(mapping.name()).isEqualTo(column);
            assertThat(mapping.nullable())
                    .as("every component of a composite key is NOT NULL by construction, and the migration "
                            + "declares all three columns NOT NULL")
                    .isFalse();
        }

        @ParameterizedTest
        @CsvSource({
            "accountGroupId, " + ACCOUNT_GROUP_ID_WIDTH,
            "tranTypeCd,     " + TRAN_TYPE_CD_WIDTH,
        })
        @DisplayName("declares the PIC width as the column length on both character components")
        void declaresThePicWidthOnBothCharacterComponents(final String component, final int picWidth) {
            assertThat(columnOf(component).length())
                    .as("%s carries its width from app/cpy/CVTRA02Y.cpy; widening it silently accepts "
                            + "values the 50-byte legacy record could not hold", component)
                    .isEqualTo(picWidth);
        }

        @ParameterizedTest
        @CsvSource({
            "accountGroupId, bpchar(10)",
            "tranTypeCd,     bpchar(2)",
        })
        @DisplayName("pins both character components to fixed-width bpchar, which is what validates against "
                + "the CHAR(n) columns the migration declares")
        void pinsBothCharacterComponentsToBpchar(final String component, final String columnDefinition) {
            assertThat(columnOf(component).columnDefinition())
                    .as("V1__create_schema.sql declares acct_group_id CHAR(10) and tran_type_cd CHAR(2); "
                            + "PostgreSQL reports CHAR(n) as bpchar, so a plain String mapping resolves to "
                            + "VARCHAR and fails a Hibernate validate pass. Fixed width is also what makes "
                            + "the DEFAULT fallback match an unpadded literal")
                    .isEqualTo(columnDefinition);
        }

        @Test
        @DisplayName("leaves the category code without a columnDefinition so its JDBC type code governs")
        void leavesTheCategoryCodeWithoutAColumnDefinition() {
            assertThat(columnOf("tranCatCd").columnDefinition())
                    .as("Medium finding: Hibernate 6's validator compares JDBC type codes, and every "
                            + "profile sets spring.jpa.hibernate.ddl-auto: validate. An Integer over "
                            + "NUMERIC(4) can fail where INTEGER passes, so the remediation is an INTEGER "
                            + "column - which V1__create_schema.sql declares - and no columnDefinition here")
                    .isEmpty();
            assertThat(persistentFields().get(2).getType())
                    .as("PIC 9(04) is an unsigned four-digit display identifier, not a monetary amount, so "
                            + "the component is an Integer and the JDBC type code it maps to is what the "
                            + "validator compares against the column")
                    .isEqualTo(Integer.class);
        }

        @Test
        @DisplayName("the column sequence in declaration order IS the composite primary-key order")
        void theColumnSequenceIsTheCompositePrimaryKeyOrder() {
            final List<String> columns = persistentFields().stream()
                    .map(field -> field.getAnnotation(Column.class).name())
                    .toList();

            assertThat(columns)
                    .as("a composite key declares its own primary-key order; reordering these three "
                            + "columns produces a different index and a different browse sequence")
                    .containsExactlyElementsOf(KEY_COLUMNS_IN_ORDER);
        }

        @Test
        @DisplayName("agrees with the PRIMARY KEY clause V1__create_schema.sql declares for disclosure_group")
        void agreesWithTheMigrationsPrimaryKeyOrder() {
            assertThat(migrationPrimaryKeyColumns())
                    .as("the key class and the migration must name the same three columns in the same "
                            + "order; SchemaStructureTest asserts the migration in isolation, and this is "
                            + "the agreement between the two that neither can establish alone")
                    .containsExactlyElementsOf(KEY_COLUMNS_IN_ORDER);
        }

        @Test
        @DisplayName("the seed fixture corroborates the 10/2/4 split across the first 16 bytes")
        void theSeedFixtureCorroboratesTheTenTwoFourSplit() {
            final FixtureLoader.FixtureData fixture = disclosureGroupFixture();
            final String groupId = fixture.field(0, 1, ACCOUNT_GROUP_ID_WIDTH);
            final String typeCd = fixture.field(0, 1 + ACCOUNT_GROUP_ID_WIDTH, TRAN_TYPE_CD_WIDTH);
            final String catCd = fixture.field(
                    0, 1 + ACCOUNT_GROUP_ID_WIDTH + TRAN_TYPE_CD_WIDTH, TRAN_CAT_CD_WIDTH);

            assertThat(groupId).as("app/data/ASCII/discgrp.txt:L1 opens with a ten-character group id")
                    .isEqualTo("A000000000");
            assertThat(typeCd).isEqualTo(TYPE_CD);
            assertThat(catCd).as("PIC 9(04) is zero-padded display digits, so 1 is stored as 0001")
                    .isEqualTo("0001");
            assertThat(groupId + typeCd + catCd)
                    .as("the three components are contiguous and occupy exactly the 16-byte key prefix, "
                            + "which is what RKP 0 at app/catlg/LISTCAT.txt:L897 means")
                    .hasSize(CATALOGUED_KEY_LENGTH)
                    .isEqualTo(fixture.recordAt(0).substring(0, CATALOGUED_KEY_LENGTH));
        }

        @Test
        @DisplayName("every seed record is the catalogued 50-byte length, so the key is a prefix of a whole "
                + "record and never a truncation")
        void everySeedRecordIsTheCataloguedRecordLength() {
            final FixtureLoader.FixtureData fixture = disclosureGroupFixture();

            assertThat(fixture.recordWidth()).isEqualTo(CATALOGUED_RECORD_LENGTH);
            assertThat(fixture.recordCount())
                    .as("app/catlg/LISTCAT.txt:L901 records REC-TOTAL 51 for this cluster")
                    .isEqualTo(3 * ROWS_PER_GROUP);
            assertThat(fixture.records())
                    .as("16 key bytes + 6 rate bytes + 28 FILLER bytes = the AVGLRECL 50 of :L896")
                    .allSatisfy(record -> assertThat(record).hasSize(CATALOGUED_RECORD_LENGTH));
        }
    }

    /**
     * The component order trap: {@code app/cbl/CBACT04C.cbl:L210-L212} assigns the key in a different order
     * from the one the copybook declares, and the copybook wins.
     */
    @Nested
    @DisplayName("2. Component order: the copybook wins over the CBACT04C MOVE order")
    class ComponentOrderTrap {

        /** The order app/cbl/CBACT04C.cbl:L210-L212 assigns in: group id, then category, then type. */
        private final List<String> moveStatementOrder = List.of("accountGroupId", "tranCatCd", "tranTypeCd");

        @Test
        @DisplayName("the declared order is the copybook order and is NOT the MOVE order of CBACT04C")
        void theDeclaredOrderIsNotTheMoveOrder() {
            assertThat(persistentFieldNames())
                    .as("High finding. app/cbl/CBACT04C.cbl:L210 moves ACCT-GROUP-ID, :L211 moves TRANCAT-CD "
                            + "into FD-DIS-TRAN-CAT-CD and only then :L212 moves TRANCAT-TYPE-CD, so the "
                            + "assignment order is group, category, type. Assignment order says nothing "
                            + "about key layout: app/cpy/CVTRA02Y.cpy:L6-L8 is authoritative. Remediation: "
                            + "read the copybook, never the MOVE statements")
                    .isNotEqualTo(moveStatementOrder)
                    .containsExactlyElementsOf(KEY_COMPONENTS_IN_ORDER);
        }

        @Test
        @DisplayName("the all-components constructor takes its parameters in copybook order too")
        void theConstructorParameterOrderIsTheCopybookOrder() throws NoSuchMethodException {
            final Parameter[] parameters = allComponentsConstructor().getParameters();

            assertThat(parameters).as("three components mean three constructor parameters").hasSize(3);
            assertThat(parameters[0].isNamePresent())
                    .as("pom.xml compiles with <parameters>true</parameters>, so parameter names survive "
                            + "into the class file and the order can be asserted by name rather than by "
                            + "type alone - which matters here because the first two are both String")
                    .isTrue();
            assertThat(Arrays.stream(parameters).map(Parameter::getName).toList())
                    .containsExactlyElementsOf(KEY_COMPONENTS_IN_ORDER);
            assertThat(Arrays.stream(parameters).map(Parameter::getType).toList())
                    .as("X(10) and X(02) are alphanumeric so both are String; 9(04) is an unsigned "
                            + "four-digit display integer, never a decimal or floating-point type")
                    .containsExactly(String.class, String.class, Integer.class);
        }

        @Test
        @DisplayName("the trailing pair is positional: transposing type and category yields a different key")
        void transposingTheTrailingPairYieldsADifferentKey() {
            final DisclosureGroupId typeTwelveCategoryThirtyFour =
                    new DisclosureGroupId(PADDED_DEFAULT_GROUP, "12", 34);
            final DisclosureGroupId typeThirtyFourCategoryTwelve =
                    new DisclosureGroupId(PADDED_DEFAULT_GROUP, "34", 12);

            assertThat(typeTwelveCategoryThirtyFour)
                    .as("if the two trailing components were interchangeable, code that followed the "
                            + "CBACT04C assignment order would still resolve the right row; it does not")
                    .isNotEqualTo(typeThirtyFourCategoryTwelve);
        }

        @Test
        @DisplayName("zero-padded four-digit category codes sort identically as text and as numbers, so "
                + "mapping the component to Integer preserves the VSAM browse order")
        void zeroPaddedCategoryCodesSortIdenticallyAsTextAndAsNumbers() {
            final List<Integer> codes = List.of(0, 1, 2, 9, 10, 99, 100, 1000, 9998, 9999);

            for (int left = 0; left < codes.size(); left++) {
                for (int right = 0; right < codes.size(); right++) {
                    final String leftText = String.format(Locale.ROOT, "%04d", codes.get(left));
                    final String rightText = String.format(Locale.ROOT, "%04d", codes.get(right));

                    assertThat(Integer.signum(leftText.compareTo(rightText)))
                            .as("PIC 9(04) is fixed width and zero padded, so byte order equals numeric "
                                    + "order over the whole domain: %s versus %s. Locale.ROOT is passed "
                                    + "explicitly so no default locale can reshape the padding",
                                    leftText, rightText)
                            .isEqualTo(Integer.signum(Integer.compare(codes.get(left), codes.get(right))));
                }
            }
        }
    }

    /**
     * Trailing blanks and the DEFAULT group fallback: the one place where a plausible implementation choice
     * changes which rows are found.
     */
    @Nested
    @DisplayName("3. Trailing blanks are part of the key, and DEFAULT is the interest job's literal")
    class DefaultGroupPadding {

        @Test
        @DisplayName("carries a padded group id verbatim, trailing blanks included, without trimming it")
        void carriesAPaddedGroupIdVerbatim() {
            assertThat(defaultGroupKey().getAccountGroupId())
                    .as("app/cbl/CBACT04C.cbl:L437 moves the seven-character literal 'DEFAULT' into an "
                            + "X(10) field, which space-pads it; app/data/ASCII/discgrp.txt:L18 stores that "
                            + "padded image. An identifier that trimmed it would no longer carry the key "
                            + "bytes the record holds")
                    .isEqualTo(PADDED_DEFAULT_GROUP)
                    .hasSize(ACCOUNT_GROUP_ID_WIDTH)
                    .endsWith("   ");
        }

        @Test
        @DisplayName("does not pad a short group id up to the PIC width")
        void doesNotPadAShortGroupIdToThePicWidth() {
            assertThat(new DisclosureGroupId(BARE_DEFAULT_GROUP, TYPE_CD, CAT_CD).getAccountGroupId())
                    .as("padding on the way in would silently turn a caller's value into a different key, "
                            + "which is the outcome the width guard exists to prevent")
                    .isEqualTo(BARE_DEFAULT_GROUP)
                    .hasSize(BARE_DEFAULT_GROUP.length());
        }

        @Test
        @DisplayName("THE DECISION: 'DEFAULT' and 'DEFAULT   ' are DIFFERENT keys in Java, and "
                + "blank-insensitive matching is delegated to the CHAR(10) column")
        void theBareAndPaddedDefaultGroupAreDifferentKeys() {
            final DisclosureGroupId bare = new DisclosureGroupId(BARE_DEFAULT_GROUP, TYPE_CD, CAT_CD);
            final DisclosureGroupId padded = defaultGroupKey();

            assertThat(bare)
                    .as("Recorded deliberately, because an equals that treated these two as equal - or as "
                            + "unequal - changes which rows are found. They are UNEQUAL: an identifier is a "
                            + "faithful carrier of the 16 key bytes and two distinct byte images must not "
                            + "collapse onto one value. Trailing-blank equivalence is a storage property, so "
                            + "V1__create_schema.sql declares acct_group_id CHAR(10) and SQL ignores "
                            + "trailing blanks on comparison; that is what lets the fallback at "
                            + "app/cbl/CBACT04C.cbl:L437 match whether the literal arrives padded or bare. "
                            + "Switching that column to VARCHAR would break the unpadded caller")
                    .isNotEqualTo(padded);
            assertThat(padded).isNotEqualTo(bare);
            assertThat(bare.getAccountGroupId().strip())
                    .as("they differ only in the padding, which is exactly why the distinction is easy to "
                            + "get wrong and is asserted rather than assumed")
                    .isEqualTo(padded.getAccountGroupId().strip());
        }

        @Test
        @DisplayName("declares no DEFAULT constant: the literal belongs to the interest-calculation path, "
                + "not to the identifier")
        void declaresNoDefaultGroupConstant() {
            assertThat(staticFields())
                    .as("every static member must be a width or range constant; a group-id literal here "
                            + "would put a business rule of app/cbl/CBACT04C.cbl:L437 inside a value type")
                    .noneMatch(field -> field.getType().equals(String.class));
            assertThat(codeLines(sourceText(KEY_SOURCE)))
                    .as("the string DEFAULT appears in the documentation of DisclosureGroupId, which is "
                            + "correct, but must not appear in its code")
                    .noneMatch(line -> line.contains("\"DEFAULT"));
        }

        @Test
        @DisplayName("the seed fixture stores the DEFAULT group space-padded to exactly ten characters")
        void theSeedFixtureStoresTheDefaultGroupSpacePadded() {
            assertThat(disclosureGroupFixture().field(17, 1, ACCOUNT_GROUP_ID_WIDTH))
                    .as("app/data/ASCII/discgrp.txt:L18 - the eighteenth record, index 17 - reads DEFAULT "
                            + "followed by three blanks, then 01, then 0001")
                    .isEqualTo(PADDED_DEFAULT_GROUP);
        }

        @ParameterizedTest
        @ValueSource(strings = {"A000000000", "DEFAULT   ", "ZEROAPR   "})
        @DisplayName("the seed fixture carries exactly seventeen rows for each of the three groups, and all "
                + "seventeen DEFAULT rows are load-bearing")
        void theSeedFixtureCarriesSeventeenRowsPerGroup(final String groupId) {
            assertThat(disclosureGroupFixture().records().stream()
                    .filter(record -> record.startsWith(groupId))
                    .count())
                    .as("Medium finding: 51 records in three groups of seventeen. Deleting any DEFAULT row "
                            + "abends CBACT04C, because the retry at app/cbl/CBACT04C.cbl:L443-L460 accepts "
                            + "only file status '00' and performs 9999-ABEND-PROGRAM at :L458 otherwise")
                    .isEqualTo(ROWS_PER_GROUP);
        }

        @Test
        @DisplayName("a key rebuilt from the fixture's own bytes equals the hand-built DEFAULT key")
        void aKeyRebuiltFromTheFixtureBytesEqualsTheHandBuiltKey() {
            final FixtureLoader.FixtureData fixture = disclosureGroupFixture();
            final DisclosureGroupId fromFixture = new DisclosureGroupId(
                    fixture.field(17, 1, ACCOUNT_GROUP_ID_WIDTH),
                    fixture.field(17, 1 + ACCOUNT_GROUP_ID_WIDTH, TRAN_TYPE_CD_WIDTH),
                    Integer.valueOf(fixture.field(
                            17, 1 + ACCOUNT_GROUP_ID_WIDTH + TRAN_TYPE_CD_WIDTH, TRAN_CAT_CD_WIDTH)));

            assertThat(fromFixture)
                    .as("the identifier round-trips the frozen seed bytes, which is the whole point of "
                            + "deriving its widths from the copybook rather than choosing them")
                    .isEqualTo(defaultGroupKey())
                    .hasSameHashCodeAs(defaultGroupKey());
        }

        @Test
        @DisplayName("the account fixture carries a ten-space ACCT-GROUP-ID on every one of its fifty rows, "
                + "so the first lookup misses for every account and the DEFAULT retry is always taken")
        void theAccountFixtureCarriesATenSpaceGroupIdOnEveryRow() {
            final FixtureLoader.FixtureData accounts = FixtureLoader.load(FixtureLoader.Fixture.ACCOUNT);
            final String tenSpaces = " ".repeat(ACCOUNT_GROUP_ID_WIDTH);

            assertThat(accounts.recordCount()).isEqualTo(50);
            for (int record = 0; record < accounts.recordCount(); record++) {
                assertThat(accounts.field(record, ACCOUNT_GROUP_ID_COLUMN, ACCOUNT_GROUP_ID_WIDTH))
                        .as("ACCT-GROUP-ID occupies 1-based columns 113-122 of the 300-byte account record; "
                                + "columns 103-112 hold ACCT-ADDR-ZIP, which reads A000000000 and is easily "
                                + "misread as the group id. Record %d", record)
                        .isEqualTo(tenSpaces);
            }
        }

        @ParameterizedTest
        @ValueSource(strings = {"A000000000", "DEFAULT   ", "ZEROAPR   "})
        @DisplayName("a ten-space group id matches none of the three seeded groups, which is why the legacy "
                + "read returns file status '23' and the fallback runs")
        void aTenSpaceGroupIdMatchesNoneOfTheSeededGroups(final String seededGroupId) {
            final DisclosureGroupId fromAccount =
                    new DisclosureGroupId(" ".repeat(ACCOUNT_GROUP_ID_WIDTH), TYPE_CD, CAT_CD);

            assertThat(fromAccount)
                    .as("app/cbl/CBACT04C.cbl:L422 accepts '00' OR '23', so the miss is not an error; :L436 "
                            + "then detects '23', :L437 substitutes 'DEFAULT' and :L438 retries")
                    .isNotEqualTo(new DisclosureGroupId(seededGroupId, TYPE_CD, CAT_CD));
        }
    }

    /**
     * The {@code @Embeddable} value-type contract: how the type is constructed, that it cannot be mutated
     * afterwards, that it holds no floating-point or decimal component, and what it is allowed to render.
     */
    @Nested
    @DisplayName("4. Embeddable value type: construction, immutability and rendering")
    class JpaValueTypeContract {

        @Test
        @DisplayName("is @Embeddable and Serializable, as a JPA composite key must be")
        void isEmbeddableAndSerializable() {
            assertThat(DisclosureGroupId.class.getAnnotation(Embeddable.class))
                    .as("a key mounted through @EmbeddedId must itself be @Embeddable")
                    .isNotNull();
            assertThat(Serializable.class)
                    .as("Jakarta Persistence requires the class of a composite identifier to be serializable")
                    .isAssignableFrom(DisclosureGroupId.class);
        }

        @Test
        @DisplayName("declares an explicit private static final long serialVersionUID, whose absence would "
                + "fail the build rather than warn")
        void declaresAnExplicitPinnedSerialVersionUid() throws NoSuchFieldException {
            final Field uid = DisclosureGroupId.class.getDeclaredField("serialVersionUID");

            assertThat(uid.getType())
                    .as("the serialized form must be pinned rather than computed from the class shape, or a "
                            + "field rename silently breaks compatibility")
                    .isEqualTo(long.class);
            assertThat(Modifier.isPrivate(uid.getModifiers())).isTrue();
            assertThat(Modifier.isStatic(uid.getModifiers())).isTrue();
            assertThat(Modifier.isFinal(uid.getModifiers()))
                    .as("under -Xlint:all -Werror a Serializable type without this field is a hard build "
                            + "failure, so its presence is a build precondition and not a nicety")
                    .isTrue();
        }

        @Test
        @DisplayName("exposes a protected, not public, no-argument constructor for the provider")
        void exposesAProtectedNoArgumentConstructor() throws NoSuchMethodException {
            final int modifiers = noArgumentConstructor().getModifiers();

            assertThat(Modifier.isProtected(modifiers))
                    .as("protected is the narrowest visibility the specification permits, so a half-built "
                            + "key is never published to application code")
                    .isTrue();
            assertThat(Modifier.isPublic(modifiers)).isFalse();
        }

        @Test
        @DisplayName("the provider constructor yields an instance with all three components unset")
        void theProviderConstructorYieldsAnInstanceWithComponentsUnset() throws ReflectiveOperationException {
            final DisclosureGroupId staged = stagedInstance();

            assertThat(staged.getAccountGroupId())
                    .as("no component is defaulted: a synthetic default would be indistinguishable from a "
                            + "value actually read from the database")
                    .isNull();
            assertThat(staged.getTranTypeCd()).isNull();
            assertThat(staged.getTranCatCd()).isNull();
        }

        @Test
        @DisplayName("exposes exactly one public all-components constructor for application code")
        void exposesOnePublicAllComponentsConstructor() throws NoSuchMethodException {
            assertThat(Modifier.isPublic(allComponentsConstructor().getModifiers())).isTrue();
            assertThat(DisclosureGroupId.class.getDeclaredConstructors())
                    .as("two constructors and no more: the provider hook and the application entry point")
                    .hasSize(2);
        }

        @Test
        @DisplayName("carries no mutator, so a persisted key cannot be altered in place")
        void carriesNoMutator() {
            assertThat(DisclosureGroupId.class.getDeclaredMethods())
                    .as("the components cannot be final because the provider writes them by field after "
                            + "instantiation, so immutability rests on there being no setter at all; a "
                            + "setter would let application code change the identity of a managed row")
                    .noneMatch(method -> method.getName().startsWith("set"));
        }

        @Test
        @DisplayName("keeps every component private, which is what makes the absence of a setter sufficient")
        void keepsEveryComponentPrivate() {
            assertThat(persistentFields())
                    .as("immutability in practice: final is unavailable because Jakarta Persistence writes "
                            + "these fields reflectively after calling the no-argument constructor, so the "
                            + "guarantee is private access plus no mutator. A package-private or public "
                            + "component would let a caller in this package reassign a live key")
                    .isNotEmpty()
                    .allSatisfy(field -> assertThat(Modifier.isPrivate(field.getModifiers()))
                            .as("component %s must be private", field.getName())
                            .isTrue());
        }

        @ParameterizedTest
        @CsvSource({
            "getAccountGroupId, java.lang.String",
            "getTranTypeCd,     java.lang.String",
            "getTranCatCd,      java.lang.Integer",
        })
        @DisplayName("declares an explicit public accessor for every component, hand-written and not generated")
        void declaresAnExplicitAccessorForEveryComponent(final String accessor, final String returnType)
                throws NoSuchMethodException {
            final Method method = DisclosureGroupId.class.getDeclaredMethod(accessor);

            assertThat(Modifier.isPublic(method.getModifiers())).isTrue();
            assertThat(method.getReturnType().getName())
                    .as("%s returns the component's declared type unchanged", accessor)
                    .isEqualTo(returnType);
        }

        @Test
        @DisplayName("carries no annotation from outside jakarta.persistence, which is also the proof that no "
                + "annotation processor generated any member")
        void carriesNoAnnotationFromOutsideJakartaPersistence() {
            final List<String> annotationPackages = new ArrayList<>();
            Arrays.stream(DisclosureGroupId.class.getAnnotations())
                    .forEach(annotation -> annotationPackages.add(
                            annotation.annotationType().getPackageName()));
            for (final Field field : DisclosureGroupId.class.getDeclaredFields()) {
                Arrays.stream(field.getAnnotations()).forEach(annotation -> annotationPackages.add(
                        annotation.annotationType().getPackageName()));
            }
            for (final Method method : DisclosureGroupId.class.getDeclaredMethods()) {
                Arrays.stream(method.getAnnotations()).forEach(annotation -> annotationPackages.add(
                        annotation.annotationType().getPackageName()));
            }

            assertThat(annotationPackages)
                    .as("the runtime-visible annotation set is @Embeddable plus three @Column mappings and "
                            + "nothing else - no validation constraint, no framework marker and no code "
                            + "generator, which is what makes the accessors demonstrably hand-written")
                    .isNotEmpty()
                    .allSatisfy(packageName -> assertThat(packageName).isEqualTo("jakarta.persistence"));
        }

        @Test
        @DisplayName("declares no static mutable state: every static member is final")
        void declaresNoStaticMutableState() {
            assertThat(staticFields())
                    .as("Rule 1 clause B forbids global mutable state. serialVersionUID and the four width "
                            + "and range constants are static final, which is correct; a mutable static "
                            + "would make the guards order-dependent across tests and across threads")
                    .isNotEmpty()
                    .allSatisfy(field -> assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("static field %s must be final", field.getName())
                            .isTrue());
        }

        @Test
        @DisplayName("names no float, double or BigDecimal anywhere on its declared surface")
        void namesNoFloatingPointOrDecimalTypeOnItsDeclaredSurface() {
            final List<String> forbidden =
                    List.of("float", "double", "java.lang.Float", "java.lang.Double",
                            "java.math.BigDecimal", "java.math.BigInteger");

            assertThat(declaredSurfaceTypes().stream().map(Class::getName).toList())
                    .as("DIS-INT-RATE PIC S9(04)V99 at app/cpy/CVTRA02Y.cpy:L9 is the only numeric value in "
                            + "this record that needs decimal arithmetic, and it sits OUTSIDE DIS-GROUP-KEY: "
                            + "it belongs to the entity as NUMERIC(6,2). The key carries no monetary or rate "
                            + "field at all, so a decimal type here would be a modelling error")
                    .doesNotContainAnyElementsOf(forbidden);
        }

        @Test
        @DisplayName("mentions no float, double or BigDecimal token in its code, comments excluded")
        void mentionsNoFloatingPointTokenInItsCode() {
            final List<String> code = codeLines(sourceText(KEY_SOURCE));

            assertThat(code)
                    .as("the comment stripper must leave code behind, or this assertion would pass vacuously")
                    .isNotEmpty();
            assertThat(code)
                    .as("reflection cannot see a method body, so the code text is scanned too. Comments are "
                            + "excluded on purpose: the production documentation legitimately discusses "
                            + "floating-point types while declaring none")
                    .noneMatch(line -> line.matches(".*\\b(float|double|BigDecimal)\\b.*"));
        }

        @Test
        @DisplayName("toString renders exactly the three key components and nothing else")
        void toStringRendersExactlyTheThreeKeyComponents() {
            assertThat(defaultGroupKey().toString())
                    .as("a diagnostic rendering of an identifier may expose its key components, which carry "
                            + "no credential and no personal data - an account group id, a two-character "
                            + "classification and a four-digit category. It must expose nothing else")
                    .isEqualTo("DisclosureGroupId[accountGroupId=DEFAULT   , tranTypeCd=01, tranCatCd=1]")
                    .doesNotContain("intRate", "dis_int_rate", "FILLER", "rate=");
        }

        @Test
        @DisplayName("toString is locale-independent: the category code renders as bare digits with no "
                + "grouping separator")
        void toStringIsLocaleIndependent() {
            assertThat(new DisclosureGroupId(PADDED_DEFAULT_GROUP, TYPE_CD, 1000).toString())
                    .as("a locale-aware number format would render 1000 as 1,000 or 1.000 and the rendering "
                            + "would then differ between machines; string concatenation of an Integer cannot")
                    .contains("tranCatCd=1000")
                    .doesNotContain("1,000", "1.000", "1 000");
        }

        @Test
        @DisplayName("a staged instance can still be compared, hashed and rendered without throwing")
        void aStagedInstanceCanStillBeComparedHashedAndRendered() throws ReflectiveOperationException {
            final DisclosureGroupId staged = stagedInstance();

            assertThat(staged)
                    .as("a provider may hash or render a key mid-population, so every component comparison "
                            + "goes through Objects.equals and no path dereferences a null component")
                    .isEqualTo(staged)
                    .isNotEqualTo(defaultGroupKey());
            assertThat(staged.hashCode()).isEqualTo(staged.hashCode());
            assertThat(staged.toString()).contains("accountGroupId=null", "tranTypeCd=null", "tranCatCd=null");
        }
    }

    /**
     * The {@code equals} and {@code hashCode} contract in full, over all three components. A gap here does not
     * fail cleanly: it makes {@code find}, {@code merge} and dirty-checking resolve the wrong row.
     */
    @Nested
    @DisplayName("5. Identity: reflexive, symmetric, transitive, consistent, null-safe, total")
    class EqualityContract {

        @Test
        @DisplayName("is reflexive: a key equals itself")
        void isReflexive() {
            final DisclosureGroupId key = defaultGroupKey();

            assertThat(key.equals(key))
                    .as("the identity shortcut every equals opens with, and the first clause of the contract")
                    .isTrue();
        }

        @Test
        @DisplayName("is symmetric: two separately built equal keys agree in both directions")
        void isSymmetric() {
            final DisclosureGroupId first = defaultGroupKey();
            final DisclosureGroupId second = defaultGroupKey();

            assertThat(first.equals(second))
                    .as("High finding if it fails: an asymmetric equals makes a JPA lookup succeed or fail "
                            + "depending on which instance the provider happens to hold on the left, which "
                            + "surfaces as an intermittent data error rather than a clean failure")
                    .isTrue();
            assertThat(second.equals(first)).isTrue();
        }

        @Test
        @DisplayName("is transitive across three separately built equal keys")
        void isTransitive() {
            final DisclosureGroupId first = defaultGroupKey();
            final DisclosureGroupId second = defaultGroupKey();
            final DisclosureGroupId third = defaultGroupKey();

            assertThat(first.equals(second)).isTrue();
            assertThat(second.equals(third)).isTrue();
            assertThat(first.equals(third))
                    .as("transitivity is what lets a hash container hold one slot for a value however many "
                            + "instances of it are offered")
                    .isTrue();
        }

        @Test
        @DisplayName("is consistent: repeated invocations of equals and hashCode never change their answer")
        void isConsistentAcrossRepeatedInvocations() {
            final DisclosureGroupId key = defaultGroupKey();
            final DisclosureGroupId same = defaultGroupKey();
            final DisclosureGroupId different = new DisclosureGroupId(PADDED_DEFAULT_GROUP, TYPE_CD, 2);
            final int hash = key.hashCode();

            for (int invocation = 0; invocation < 3; invocation++) {
                assertThat(key.equals(same))
                        .as("no component is mutable, so no answer may drift; invocation %d", invocation)
                        .isTrue();
                assertThat(key.equals(different)).isFalse();
                assertThat(key.hashCode()).isEqualTo(hash);
            }
        }

        @Test
        @DisplayName("is never equal to null, and says so rather than throwing")
        void isNeverEqualToNull() {
            assertThat(defaultGroupKey().equals(null))
                    .as("the contract requires false, never a NullPointerException")
                    .isFalse();
        }

        @Test
        @DisplayName("is never equal to a foreign type, and says so rather than throwing")
        void isNeverEqualToAForeignType() {
            final DisclosureGroupId key = defaultGroupKey();

            assertThat(key.equals("DEFAULT   010001"))
                    .as("the concatenated key bytes are not the key: equals compares components, and a "
                            + "getClass check refuses a foreign type before any cast")
                    .isFalse();
            assertThat(key).isNotEqualTo(new Object());
        }

        @Test
        @DisplayName("differs when only the account group id differs")
        void differsWhenOnlyTheGroupIdDiffers() {
            assertThat(defaultGroupKey())
                    .as("the first component is part of the identity, so two rates for the same type and "
                            + "category under different groups stay distinct")
                    .isNotEqualTo(new DisclosureGroupId("ZEROAPR   ", TYPE_CD, CAT_CD));
        }

        @Test
        @DisplayName("differs when only the transaction type code differs")
        void differsWhenOnlyTheTypeCodeDiffers() {
            assertThat(defaultGroupKey())
                    .as("app/data/ASCII/discgrp.txt carries rows 010001 and 020001 under the same DEFAULT "
                            + "group, at rates 15.00 and 0.00, so conflating the type code would return the "
                            + "wrong rate")
                    .isNotEqualTo(new DisclosureGroupId(PADDED_DEFAULT_GROUP, "02", CAT_CD));
        }

        @Test
        @DisplayName("differs when only the transaction category code differs")
        void differsWhenOnlyTheCategoryCodeDiffers() {
            assertThat(defaultGroupKey())
                    .as("the third component is part of the identity too, so a partial key never matches a "
                            + "complete one")
                    .isNotEqualTo(new DisclosureGroupId(PADDED_DEFAULT_GROUP, TYPE_CD, CAT_CD + 1));
        }

        @Test
        @DisplayName("equal keys share a hash code")
        void equalKeysShareAHashCode() {
            assertThat(defaultGroupKey())
                    .as("unequal hash codes for equal values would put one value in two buckets and a keyed "
                            + "lookup would miss")
                    .hasSameHashCodeAs(defaultGroupKey());
        }

        @Test
        @DisplayName("behaves as a hash key: a set de-duplicates equal keys and a map resolves by value")
        void behavesAsAHashKey() {
            final Set<DisclosureGroupId> keys = new HashSet<>();
            keys.add(defaultGroupKey());
            keys.add(defaultGroupKey());
            keys.add(new DisclosureGroupId(PADDED_DEFAULT_GROUP, TYPE_CD, CAT_CD + 1));

            assertThat(keys)
                    .as("two equal keys occupy one slot; a third distinct key occupies its own")
                    .hasSize(2);

            final Map<DisclosureGroupId, String> rates = new HashMap<>();
            rates.put(defaultGroupKey(), "0015.00");

            assertThat(rates.get(defaultGroupKey()))
                    .as("a rate lookup keyed on a freshly built equal key resolves, exactly as the keyed "
                            + "VSAM read at app/cbl/CBACT04C.cbl:L416 did")
                    .isEqualTo("0015.00");
        }

        @Test
        @DisplayName("is case-sensitive, so any case folding is the caller's decision and must pass "
                + "Locale.ROOT")
        void isCaseSensitiveSoCaseFoldingIsTheCallersDecision() {
            final String lowerCased = PADDED_DEFAULT_GROUP.toLowerCase(Locale.ROOT);

            assertThat(lowerCased)
                    .as("Locale.ROOT is passed explicitly: under a Turkish default locale the dotless i "
                            + "would change this string and the assertion would become machine-dependent")
                    .isEqualTo("default   ");
            assertThat(new DisclosureGroupId(lowerCased, TYPE_CD, CAT_CD))
                    .as("the identifier normalises nothing, so the seeded upper-case group id and its "
                            + "lower-case spelling are different keys; a caller that needs folding does it "
                            + "before construction and passes Locale.ROOT when it does")
                    .isNotEqualTo(defaultGroupKey());
        }
    }

    /**
     * Distinctness from the two sibling composite identifiers. They look structurally similar and are not
     * interchangeable, and no abstraction is shared between them.
     */
    @Nested
    @DisplayName("6. Distinct from TransactionCategoryBalanceId and TransactionCategoryId")
    class SiblingDistinctness {

        /** A transaction category key carrying the same trailing pair as {@link #defaultGroupKey()}. */
        private final TransactionCategoryId categoryKey = new TransactionCategoryId(TYPE_CD, CAT_CD);

        /** A category-balance key carrying the same trailing pair, under account 1. */
        private final TransactionCategoryBalanceId balanceKey =
                new TransactionCategoryBalanceId(1L, TYPE_CD, CAT_CD);

        @Test
        @DisplayName("is never equal to a TransactionCategoryId that shares the type and category pair")
        void isNeverEqualToATransactionCategoryIdSharingTheTrailingPair() {
            assertThat(defaultGroupKey().equals(categoryKey))
                    .as("both keys carry tran_type_cd and tran_cat_cd, so a structural comparison would "
                            + "match; equals opens with a getClass check precisely so it does not")
                    .isFalse();
            assertThat(categoryKey.equals(defaultGroupKey()))
                    .as("and the refusal is symmetric, so neither type can absorb the other")
                    .isFalse();
        }

        @Test
        @DisplayName("is never equal to a TransactionCategoryBalanceId that shares the type and category pair")
        void isNeverEqualToATransactionCategoryBalanceIdSharingTheTrailingPair() {
            assertThat(defaultGroupKey().equals(balanceKey)).isFalse();
            assertThat(balanceKey.equals(defaultGroupKey()))
                    .as("app/cpy/CVTRA01Y.cpy declares a 17-byte key over three fields and names its group "
                            + "TRAN-CAT-KEY; this key is the 16-byte DIS-GROUP-KEY of app/cpy/CVTRA02Y.cpy, "
                            + "so the resemblance is coincidental and must not become equality")
                    .isFalse();
        }

        @Test
        @DisplayName("is not interchangeable as a map key with either sibling")
        void isNotInterchangeableAsAMapKey() {
            final Map<Object, String> rates = new HashMap<>();
            rates.put(defaultGroupKey(), "0015.00");

            assertThat(rates.get(categoryKey))
                    .as("a sibling key must not resolve a disclosure-group row, or an interest lookup could "
                            + "silently read a rate that belongs to a different table")
                    .isNull();
            assertThat(rates.get(balanceKey)).isNull();
            assertThat(rates.get(defaultGroupKey())).isEqualTo("0015.00");
        }

        @Test
        @DisplayName("the three identifiers occupy three distinct slots in one set")
        void theThreeIdentifiersOccupyThreeDistinctSlots() {
            final Set<Object> keys = new HashSet<>();
            keys.add(defaultGroupKey());
            keys.add(categoryKey);
            keys.add(balanceKey);

            assertThat(keys)
                    .as("three distinct value types with overlapping components must never collapse")
                    .hasSize(3);
        }

        @ParameterizedTest
        @ValueSource(classes = {
            DisclosureGroupId.class,
            TransactionCategoryBalanceId.class,
            TransactionCategoryId.class,
        })
        @DisplayName("every one of the three keys extends Object directly: no shared base class exists")
        void everyKeyExtendsObjectDirectly(final Class<?> keyType) {
            assertThat(keyType.getSuperclass())
                    .as("Rule 1 clause C asks that duplication be avoided, and here that is satisfied rather "
                            + "than violated: three distinct value types repeating a few lines of equals and "
                            + "hashCode is correct, because a shared abstract key would assert an "
                            + "equivalence the copybooks do not have. %s", keyType.getSimpleName())
                    .isEqualTo(Object.class);
        }

        @ParameterizedTest
        @ValueSource(classes = {
            DisclosureGroupId.class,
            TransactionCategoryBalanceId.class,
            TransactionCategoryId.class,
        })
        @DisplayName("every one of the three keys implements Serializable and no other interface")
        void everyKeyImplementsSerializableAndNothingElse(final Class<?> keyType) {
            assertThat(keyType.getInterfaces())
                    .as("no shared marker interface and no common key contract; Serializable is present only "
                            + "because Jakarta Persistence requires it of an identifier class. %s",
                            keyType.getSimpleName())
                    .containsExactly(Serializable.class);
        }

        @Test
        @DisplayName("none of the three types is assignable to another, so no cast between them compiles or "
                + "succeeds")
        void noneOfTheThreeTypesIsAssignableToAnother() {
            final List<Class<?>> types = List.of(
                    DisclosureGroupId.class, TransactionCategoryBalanceId.class, TransactionCategoryId.class);

            for (final Class<?> left : types) {
                for (final Class<?> right : types) {
                    if (!left.equals(right)) {
                        assertThat(left.isAssignableFrom(right))
                                .as("%s must not accept a %s", left.getSimpleName(), right.getSimpleName())
                                .isFalse();
                    }
                }
            }
        }

        @Test
        @DisplayName("differs structurally from both siblings: three components led by a character group id, "
                + "against three led by a numeric account id and two with no leading component at all")
        void differsStructurallyFromBothSiblings() {
            assertThat(persistentFieldNames()).hasSize(3);
            assertThat(persistentFields().get(0).getType())
                    .as("DIS-ACCT-GROUP-ID is PIC X(10), whereas TRANCAT-ACCT-ID of app/cpy/CVTRA01Y.cpy is "
                            + "PIC 9(11) and maps to Long; the two keys therefore differ in their very first "
                            + "component and 16 bytes against 17 is not a rounding difference")
                    .isEqualTo(String.class);
            assertThat(Arrays.stream(TransactionCategoryId.class.getDeclaredFields())
                    .filter(field -> !field.isSynthetic())
                    .filter(field -> !Modifier.isStatic(field.getModifiers()))
                    .count())
                    .as("app/cpy/CVTRA04Y.cpy declares a 6-byte key over two fields, so it cannot even hold "
                            + "the group id this key leads with")
                    .isEqualTo(2L);
        }
    }

    /**
     * Untrusted input at and beyond every boundary the picture clauses define. Every component is validated at
     * construction and every failure names the component, the COBOL field and the limit that was broken.
     */
    @Nested
    @DisplayName("7. Untrusted input: null, empty, blank, short, overlong and out-of-domain")
    class HostileInput {

        @Test
        @DisplayName("rejects a null account group id rather than staging a null key component")
        void rejectsANullAccountGroupId() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new DisclosureGroupId(null, TYPE_CD, CAT_CD))
                    .withMessage("accountGroupId (DIS-ACCT-GROUP-ID) must not be null")
                    .withNoCause();
        }

        @Test
        @DisplayName("rejects a null transaction type code")
        void rejectsANullTranTypeCode() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new DisclosureGroupId(PADDED_DEFAULT_GROUP, null, CAT_CD))
                    .withMessage("tranTypeCd (DIS-TRAN-TYPE-CD) must not be null")
                    .withNoCause();
        }

        @Test
        @DisplayName("rejects a null transaction category code")
        void rejectsANullTranCatCd() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new DisclosureGroupId(PADDED_DEFAULT_GROUP, TYPE_CD, null))
                    .withMessage("tranCatCd (DIS-TRAN-CAT-CD) must not be null")
                    .withNoCause();
        }

        @ParameterizedTest
        @ValueSource(strings = {"", " ", "         ", "          ", "A", "DEFAULT", "DEFAULT  "})
        @DisplayName("accepts an empty, blank or short group id: the width guard is a ceiling, not an exact "
                + "width, because the interest job supplies a bare seven-character literal")
        void acceptsAnEmptyBlankOrShortGroupId(final String withinWidth) {
            assertThat(new DisclosureGroupId(withinWidth, TYPE_CD, CAT_CD).getAccountGroupId())
                    .as("app/cbl/CBACT04C.cbl:L437 moves the bare literal 'DEFAULT' - seven characters - so "
                            + "an exact-width guard here would reject the source's own fallback value. The "
                            + "ten-blank case is what app/data/ASCII/acctdata.txt carries on all fifty rows, "
                            + "and the value is stored verbatim either way")
                    .isEqualTo(withinWidth);
        }

        @Test
        @DisplayName("rejects an eleven-character group id, one past the PIC X(10) ceiling")
        void rejectsAnElevenCharacterGroupId() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new DisclosureGroupId("DEFAULT    ", TYPE_CD, CAT_CD))
                    .withMessage("accountGroupId (DIS-ACCT-GROUP-ID) must not exceed 10 characters but was "
                            + "11: 'DEFAULT    '")
                    .withNoCause();
        }

        @Test
        @DisplayName("the overlong rejection names the Java component, the COBOL field, the limit and the "
                + "length received")
        void theOverlongRejectionNamesEveryDetail() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new DisclosureGroupId("DEFAULTGROUP", TYPE_CD, CAT_CD))
                    .withMessageContaining("accountGroupId")
                    .withMessageContaining("DIS-ACCT-GROUP-ID")
                    .withMessageContaining(String.valueOf(ACCOUNT_GROUP_ID_WIDTH))
                    .withMessageContaining("12");
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "0", "012", "0123"})
        @DisplayName("rejects any type code that is not exactly two characters: this guard is exact, not a "
                + "ceiling, because the component fixes the offset of the category code")
        void rejectsATypeCodeThatIsNotExactlyTwoCharacters(final String wrongWidth) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("DIS-TRAN-TYPE-CD occupies bytes 11 and 12 of the 16-byte key, so a one-character "
                            + "value does not merely under-fill its own field - it moves DIS-TRAN-CAT-CD to "
                            + "the wrong offset and denotes a different row")
                    .isThrownBy(() -> new DisclosureGroupId(PADDED_DEFAULT_GROUP, wrongWidth, CAT_CD))
                    .withMessageContaining("tranTypeCd")
                    .withMessageContaining("DIS-TRAN-TYPE-CD")
                    .withMessageContaining("must be exactly 2 characters")
                    .withMessageContaining("was " + wrongWidth.length());
        }

        @Test
        @DisplayName("accepts a two-blank type code, because PIC X(02) is alphanumeric and not numeric")
        void acceptsATwoBlankTypeCode() {
            assertThat(new DisclosureGroupId(PADDED_DEFAULT_GROUP, "  ", CAT_CD).getTranTypeCd())
                    .as("the guard checks width, not content: X(02) admits any two characters, blanks "
                            + "included, and the fixture happens to use digit pairs only")
                    .isEqualTo("  ");
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 1, 9, 10, 99, 100, 1000, 9998, 9999})
        @DisplayName("accepts every category code inside the unsigned four-digit domain, both bounds included")
        void acceptsEveryCategoryCodeInsideTheDomain(final int inDomain) {
            assertThat(new DisclosureGroupId(PADDED_DEFAULT_GROUP, TYPE_CD, inDomain).getTranCatCd())
                    .as("PIC 9(04) spans 0000 to 9999, so both bounds are legitimate values and zero in "
                            + "particular is load-bearing: app/data/ASCII/discgrp.txt seeds category 0001 "
                            + "under seven type codes and app/cbl/CBACT04C.cbl:L214 guards on a zero rate")
                    .isEqualTo(inDomain);
        }

        @ParameterizedTest
        @ValueSource(ints = {-1, -9999, 10_000, 100_000, Integer.MAX_VALUE, Integer.MIN_VALUE})
        @DisplayName("rejects every category code outside PIC 9(04), negatives and five-digit values alike")
        void rejectsEveryCategoryCodeOutsideTheDomain(final int outOfDomain) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("the picture is unsigned and four digits wide, so %d cannot round-trip through the "
                            + "50-byte legacy record and is refused at construction", outOfDomain)
                    .isThrownBy(() -> new DisclosureGroupId(PADDED_DEFAULT_GROUP, TYPE_CD, outOfDomain))
                    .withMessageContaining("tranCatCd")
                    .withMessageContaining("DIS-TRAN-CAT-CD")
                    .withMessageContaining(String.valueOf(outOfDomain));
        }

        @Test
        @DisplayName("the out-of-domain rejection quotes the whole domain and the offending value")
        void theOutOfDomainRejectionQuotesTheDomainAndTheValue() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new DisclosureGroupId(PADDED_DEFAULT_GROUP, TYPE_CD, 10_000))
                    .withMessage("tranCatCd (DIS-TRAN-CAT-CD) must be within 0 to 9999 but was 10000")
                    .withNoCause();
        }
    }

    /**
     * {@link Serializable} is implemented for JPA identity only. This group proves the type has no
     * deserialization surface at all, which is what closes the insecure-deserialization risk Rule 1 clause D
     * names, and pins the import set the type is allowed to declare.
     */
    @Nested
    @DisplayName("8. Serializable for JPA identity only: no deserialization surface, no stray import")
    class SerializationSafety {

        @ParameterizedTest
        @ValueSource(strings = {"readObject", "readObjectNoData", "readResolve", "writeObject",
            "writeReplace"})
        @DisplayName("declares no Java-serialization callback, so there is no hook an attacker could reach")
        void declaresNoJavaSerializationCallback(final String hook) {
            assertThat(SERIALIZATION_HOOKS)
                    .as("the parameter list and the asserted list are kept in step")
                    .contains(hook);
            assertThat(DisclosureGroupId.class.getDeclaredMethods())
                    .as("Java serialization validates nothing before it constructs an object graph, so the "
                            + "safe position is to declare no callback whatsoever: %s must be absent", hook)
                    .noneMatch(method -> method.getName().equals(hook));
        }

        @Test
        @DisplayName("exposes no java.io type on its declared surface beyond the Serializable marker")
        void exposesNoJavaIoTypeOnItsDeclaredSurface() {
            assertThat(declaredSurfaceTypes())
                    .as("no field, accessor, constructor parameter or return value trades in streams, so the "
                            + "type cannot be handed a stream to read itself from")
                    .isNotEmpty()
                    .allSatisfy(type -> assertThat(type.getPackageName()).isNotEqualTo("java.io"));
        }

        @Test
        @DisplayName("references no object stream and no process or script execution in its code")
        void referencesNoObjectStreamOrExecutionPrimitive() {
            final List<String> code = codeLines(sourceText(KEY_SOURCE));
            final List<String> forbidden = List.of("ObjectInputStream", "ObjectOutputStream",
                    "ObjectStreamField", "readObject", "Runtime.getRuntime", "ProcessBuilder",
                    "ScriptEngine");

            assertThat(code).isNotEmpty();
            for (final String token : forbidden) {
                assertThat(code)
                        .as("Rule 1 clause D names insecure deserialization, eval or exec, and shell "
                                + "injection as risky patterns. This type is persisted and transported as "
                                + "JPA columns or as JSON, never as a serialized object stream and never as "
                                + "a Java-serialization cache or message payload: %s must not appear", token)
                        .noneMatch(line -> line.contains(token));
            }
        }

        @Test
        @DisplayName("declares only the four permitted imports, so the type stays a plain Jakarta "
                + "Persistence value type with no framework reach")
        void declaresOnlyThePermittedImports() {
            assertThat(importedTypes(sourceText(KEY_SOURCE)))
                    .as("jakarta.persistence for the mapping, java.io.Serializable for the identifier "
                            + "requirement and java.util.Objects for null-safe comparison. Anything else - a "
                            + "Hibernate annotation, a validation constraint, a logging facade - would give "
                            + "a value type a dependency it has no use for")
                    .containsExactlyElementsOf(PERMITTED_IMPORTS);
        }
    }
}

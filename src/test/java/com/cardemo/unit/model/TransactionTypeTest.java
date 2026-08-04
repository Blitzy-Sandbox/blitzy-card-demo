/*
 * ******************************************************************
 * Program     : TransactionTypeTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Proves the TransactionType entity reproduces the 60-byte
 *               TRAN-TYPE-RECORD layout exactly: the key column is
 *               tran_type and never tran_type_cd, the key is two
 *               characters, the eight-byte FILLER is unmapped, the type
 *               is a table rather than a Java enum, the cluster has no
 *               alternate index and no online definition, and the seven
 *               seeded rows of trantype.txt survive the round trip with
 *               their space padding and their zero-filled tail intact.
 * Source      : app/cpy/CVTRA03Y.cpy (60 B, key 2) @ 7756d89
 *               app/cpy/CVTRA04Y.cpy:L6            (TRAN-TYPE-CD contrast)
 *               app/catlg/LISTCAT.txt:L3779        (KEYLEN 2, AVGLRECL 60)
 *               app/catlg/LISTCAT.txt:L3938-L3946  (AIX 3, PATH 3)
 *               app/csd/CARDDEMO.CSD               (8 files, no TRANTYPE)
 *               app/data/ASCII/trantype.txt        (427 B, 7 rows, w 60)
 *               app/data/ASCII/trancatg.txt        (1098 B, 18 rows, w 60)
 *               app/cbl/CBACT04C.cbl:L1-L21        (banner convention)
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.cardemo.model.entity.TransactionType;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The transaction type reference table: sixty bytes, two columns, seven rows, and one naming exception.
 *
 * <h2>What this class does</h2>
 *
 * <p>It holds {@link TransactionType} against the four independent authorities that define it, and it reads
 * every one of them rather than restating a remembered figure:
 *
 * <ul>
 *   <li>{@code app/cpy/CVTRA03Y.cpy:L5-L7} - the record layout. {@code TRAN-TYPE PIC X(02)},
 *       {@code TRAN-TYPE-DESC PIC X(50)} and {@code FILLER PIC X(08)}, summing to the sixty bytes its
 *       own header comment at {@code :L2} declares as {@code RECLN = 60}.</li>
 *   <li>{@code app/catlg/LISTCAT.txt:L3779} - the physical geometry. {@code KEYLEN 2} with
 *       {@code AVGLRECL 60}, and on the following line {@code RKP 0} with {@code MAXLRECL 60}, so the key
 *       occupies bytes one and two and sixty is an exact length rather than an average. The same entry
 *       reports {@code REC-TOTAL 7}, which is the fixture row count reached independently.</li>
 *   <li>{@code app/csd/CARDDEMO.CSD} - the online file control table, which names exactly eight CICS files
 *       and does not name this one.</li>
 *   <li>{@code app/data/ASCII/trantype.txt} and {@code app/data/ASCII/trancatg.txt} - the seeded data,
 *       read through {@link FixtureLoader} by classpath resource name only.</li>
 *   </ul>
 *
 * <p>Reading the corpus is the point. An assertion written against a literal typed out by hand proves only
 * that the literal agrees with itself; an assertion that parses {@code app/cpy/CVTRA03Y.cpy} fails the moment
 * the mapping and the system of record diverge, which is the only failure worth catching here.
 *
 * <h2>Findings this class encodes, by severity</h2>
 *
 * <table border="1">
 *   <caption>Severity register</caption>
 *   <tr><th>Severity</th><th>Finding</th><th>Remediation</th></tr>
 *   <tr>
 *     <td>Blocker</td>
 *     <td>Applying a global zoned-decimal overpunch substitution to {@code trantype.txt}. Every one of the
 *         seven descriptions <em>begins</em> with a letter in the overpunch alphabet - {@code Purchase} and
 *         {@code Payment} with {@code P}, {@code Credit} with {@code C}, {@code Authorization} and
 *         {@code Adjustment} with {@code A}, {@code Refund} and {@code Reversal} with {@code R} - so a
 *         blanket replacement would rewrite all seven into digits and signs.</td>
 *     <td>Decode position aware, driven by the {@code PIC} clause alone. This layout declares
 *         {@code X(02)}, {@code X(50)} and {@code X(08)}: three character fields and not one
 *         {@code S9(n)V99}, so no decode belongs anywhere near it.</td>
 *   </tr>
 *   <tr>
 *     <td>High</td>
 *     <td>Modelling the seven type codes as a Java enum.</td>
 *     <td>Keep the JPA {@code @Entity}. The codes are reference data seeded by the migration and extensible
 *         without a recompile, which is why they are a table.</td>
 *   </tr>
 *   <tr>
 *     <td>High</td>
 *     <td>Adding an optimistic locking version column.</td>
 *     <td>Leave it off. Only {@code Account}, {@code Card}, {@code Customer} and {@code Transaction} carry
 *         one; this row is written once by the seed migration.</td>
 *   </tr>
 *   <tr>
 *     <td>High</td>
 *     <td>A fixed-width writer that space-pads the trailing {@code FILLER X(08)}.</td>
 *     <td>Pad it with {@code '0'}. The eight bytes at columns 53-60 of every row of {@code trantype.txt} are
 *         {@code 00000000}, so space padding loses byte-exact reproduction.</td>
 *   </tr>
 *   <tr>
 *     <td>High</td>
 *     <td>Delimiting a {@code LISTCAT} entry by the carriage control character in column one. The
 *         {@code TRANTYPE} cluster entry opens at {@code app/catlg/LISTCAT.txt:L3742} but its
 *         {@code ASSOCIATIONS} block is only at {@code :L3766-L3768}, and the listing utility prints a page
 *         header at {@code :L3757}, its subtitle at {@code :L3758} and a continuation line at {@code :L3759}
 *         in between - carrying {@code 1}, {@code -} and {@code 0} in column one. A parser that split on
 *         those characters stops at the page break and then reports this cluster as having <em>no</em>
 *         associations, which is a wrong answer rather than a failure and would silently confirm whatever
 *         the caller was hoping to prove.</td>
 *     <td>Terminate an entry on the next line that parses as an entry header, or on the closing census, and
 *         skip page furniture. This class does exactly that, and asserts the straddle so the hazard stays
 *         visible.</td>
 *   </tr>
 *   <tr>
 *     <td>Medium</td>
 *     <td>Harmonising the key column to {@code tran_type_cd} to match the sibling copybooks.</td>
 *     <td>Name it {@code tran_type}. {@code app/cpy/CVTRA03Y.cpy:L5} is the sole declaration in the corpus
 *         that omits the {@code -CD} suffix, and the copybook is authoritative. The wrong name aborts
 *         startup, because {@code spring.jpa.hibernate.ddl-auto} is {@code validate} in every profile.</td>
 *   </tr>
 *   <tr>
 *     <td>Low</td>
 *     <td>Presuming an online path for this table. {@code app/csd/CARDDEMO.CSD} names exactly eight CICS
 *         files and this is not one of them, so it is batch only, as are {@code TCATBALF}, {@code DISCGRP}
 *         and {@code TRANCATG}.</td>
 *     <td>Give it no authorisation rule of its own and no REST endpoint, and exercise it through the batch
 *         tier rather than the online surface.</td>
 *   </tr>
 * </table>
 *
 * <h2>What is deliberately not asserted here</h2>
 *
 * <p><b>Data definition language: {@code Not available}.</b> No claim is made about
 * {@code src/main/resources/db/migration/V1__create_schema.sql} - not the column types it emits, not the
 * primary key it declares, not the ten foreign keys, and not the absence of an index. That file has no
 * planned children in this run, so its content is not established evidence from where this class stands, and
 * inventing a claim about it would be worse than omitting one. What would be needed to assert it: the
 * migration's authoritative text, plus a schema validation of the mapping against a live PostgreSQL 16
 * instance. Both belong to the integration tier, which is where they are exercised. This class asserts the
 * mapping against the copybook and the catalogue, which are frozen and readable, and stops there.
 *
 * <h2>How to build, run and test</h2>
 *
 * <p>Run the whole unit tier with {@code ./mvnw -B -ntp test}, this class alone with
 * {@code ./mvnw -B -ntp -Dtest=TransactionTypeTest test}, and the full gate with
 * {@code ./mvnw -B -ntp clean verify}, which adds the coverage floor, the documentation gate and the
 * dependency vulnerability scan.
 *
 * <p><b>Surefire binds this tier, and only by path.</b> The build collects {@code **}{@code /*Test.java} and
 * {@code **}{@code /*Tests.java} with {@code **}{@code /integration/**} and {@code **}{@code /e2e/**}
 * excluded, so this class is collected because it is named {@code TransactionTypeTest} and sits under
 * {@code src/test/java/com/cardemo/unit/model}. Moving or renaming it is silent: neither Surefire nor
 * Failsafe would collect it, both would report success, and coverage would simply record it as unexecuted.
 * Surefire also sets its working directory to the project base directory, which is what lets the frozen
 * corpus below be resolved by relative path.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>There is nothing to configure, and three defaults are pinned in code because leaving them to the
 * environment is how a test of this shape stops being deterministic:
 *
 * <ul>
 *   <li><b>No clock is consulted, because the layout has no temporal field.</b> {@code CVTRA03Y.cpy}
 *       declares three character fields and no date, timestamp or duration, so there is no wall-clock
 *       reading to pin and {@code FixedClockProvider} has nothing to supply here. Determinism is therefore
 *       structural rather than injected: no reading of the current instant, the current date or the
 *       millisecond counter occurs anywhere in this file, and no assertion could depend on one even if it
 *       did. Were a temporal field ever added to this layout, the fixed clock is where its value would have
 *       to come from.</li>
 *   <li><b>No mock, so Mockito strictness never applies.</b> The entity is a pure data holder with no
 *       injected collaborator to stub, so this class creates no mock and no stubbing can go unused. The
 *       framework default of strict stubs would fail such a test; there is simply nothing for it to
 *       police.</li>
 *   <li><b>Every case comparison goes through {@link Locale#ROOT} and every corpus read through an explicit
 *       charset.</b> The platform default locale and the platform default charset are never read. The frozen
 *       corpus is decoded as {@link StandardCharsets#ISO_8859_1}, which is total and byte preserving, so a
 *       stray byte can neither throw nor be silently replaced; the fixtures come through
 *       {@link FixtureLoader}, which pins them to US-ASCII.</li>
 *   </ul>
 *
 * <p>No static mutable state exists. The frozen corpus is read on demand inside the assertion that needs it
 * rather than cached in a static field, which keeps the failure a named {@link IllegalStateException} from a
 * test method instead of an {@code ExceptionInInitializerError} thrown out of class initialisation.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><b>The build fails with no test failure.</b> That is the compiler gate, not this class. Test
 *       compilation runs under {@code -Xlint:all -Werror} with {@code failOnWarning}, so a raw type, an
 *       unchecked cast or a deprecated call here fails the whole build. An unused import does not fail it -
 *       {@code javac} 25 publishes no {@code unused} lint key - so that prohibition is held at review.</li>
 *   <li><b>The fixture is {@code dailytran.txt}, spelled out in full.</b> {@code dalytran.txt} does not
 *       exist. The mainframe DD name and dataset are {@code DALYTRAN}, but the ASCII fixture spells the word
 *       completely, so code written from the DD name gets a null resource stream. This class reads
 *       {@code trantype.txt} and {@code trancatg.txt} and so does not meet the trap directly, but it is the
 *       most common failure in this area and every author reading these fixtures should know it.</li>
 *   <li><b>Startup fails with "Schema-validation: missing column [tran_type]".</b> Almost always the
 *       {@code TRAN-TYPE-CD} harmonisation described above. Correct the schema, not the mapping.</li>
 *   <li><b>A byte-exact comparison of a re-emitted record differs in its last eight bytes.</b> The
 *       {@code FILLER} is zero filled, not space filled.</li>
 *   <li><b>A description comparison fails against an unpadded literal.</b> The value is a {@code PIC X(50)}
 *       image and stays blank padded to fifty characters; nothing here trims it.</li>
 *   <li><b>An {@link IllegalStateException} naming the working directory.</b> The frozen corpus could not be
 *       located upward from the working directory. Run from the repository root or any directory beneath
 *       it.</li>
 *   </ul>
 *
 * <h2>Why {@code toString} may carry both columns</h2>
 *
 * <p>Rule 1 clause D forbids secrets in code, logs, tests and configuration, and names tests explicitly. A
 * two-character type code and its English description are neither credential nor personal data, so
 * {@code toString} renders both in full and this class asserts that it does. The contrast with its siblings
 * is deliberate rather than an oversight: {@code Customer} renders its identifier and version only, because
 * its layout carries a social security number, a date of birth and two telephone numbers; and {@code Card}
 * and {@code Transaction} withhold the card number, because a primary account number is exactly the value
 * that must never reach a log line. Nothing in this layout is in that class of data, so nothing is withheld.
 *
 * @see FixtureLoader
 */
@DisplayName("TransactionType: the 60-byte TRAN-TYPE-RECORD, its tran_type key and its seven seeded rows")
class TransactionTypeTest {

    /** The frozen corpus root, relative to the repository root. Read only; never written by this class. */
    private static final String FROZEN_CORPUS = "app";

    /** The record layout that defines this entity. */
    private static final String COPYBOOK = "app/cpy/CVTRA03Y.cpy";

    /** The copybook directory, scanned exhaustively to prove the {@code TRAN-TYPE} naming exception. */
    private static final String COPYBOOK_DIRECTORY = "app/cpy";

    /** The VSAM catalogue listing: the authoritative physical geometry of every cluster. */
    private static final String CATALOGUE = "app/catlg/LISTCAT.txt";

    /** The CICS resource definitions: the online file control table and the transient data queue. */
    private static final String RESOURCE_DEFINITIONS = "app/csd/CARDDEMO.CSD";

    /** Table name declared by {@code @Table} on the entity. */
    private static final String TABLE_NAME = "transaction_type";

    /** The key column, from the bare {@code TRAN-TYPE} of {@code app/cpy/CVTRA03Y.cpy:L5}. */
    private static final String KEY_COLUMN = "tran_type";

    /** The description column, from {@code TRAN-TYPE-DESC} of {@code app/cpy/CVTRA03Y.cpy:L6}. */
    private static final String DESCRIPTION_COLUMN = "tran_type_desc";

    /** The column name the six sibling copybooks would suggest, and which must never appear. */
    private static final String HARMONISED_KEY_COLUMN = "tran_type_cd";

    /** Java property backing {@link #KEY_COLUMN}. */
    private static final String KEY_PROPERTY = "typeCode";

    /** Java property backing {@link #DESCRIPTION_COLUMN}. */
    private static final String DESCRIPTION_PROPERTY = "typeDescription";

    /** Width of {@code TRAN-TYPE PIC X(02)}, and the catalogued {@code KEYLEN}. */
    private static final int KEY_WIDTH = 2;

    /** Width of {@code TRAN-TYPE-DESC PIC X(50)}. */
    private static final int DESCRIPTION_WIDTH = 50;

    /** Width of the unmapped trailing {@code FILLER PIC X(08)}. */
    private static final int FILLER_WIDTH = 8;

    /** The whole record: {@code 2 + 50 + 8}, and the catalogued {@code AVGLRECL} and {@code MAXLRECL}. */
    private static final int RECORD_WIDTH = 60;

    /** One-based column at which the key starts, which is the catalogued {@code RKP 0}. */
    private static final int KEY_START_COLUMN = 1;

    /** One-based column at which the description starts. */
    private static final int DESCRIPTION_START_COLUMN = 3;

    /** One-based column at which the unmapped filler starts. */
    private static final int FILLER_START_COLUMN = 53;

    /** The character the trailing filler is filled with in the fixture: a zero, never a space. */
    private static final char FILLER_FILL_CHARACTER = '0';

    /** Rows seeded from {@code app/data/ASCII/trantype.txt}, and the row count the catalogue reports. */
    private static final int SEEDED_ROW_COUNT = 7;

    /**
     * The complete overpunch alphabet: <code>&#123;</code> and {@code A}-{@code I} carry +0 to +9,
     * <code>&#125;</code> and {@code J}-{@code R} carry -0 to -9. Held here to demonstrate the corruption a
     * position-unaware substitution would cause, never to decode anything in this layout.
     */
    private static final String OVERPUNCH_ALPHABET = "{ABCDEFGHI}JKLMNOPQR";

    /**
     * Package prefixes, in class-file internal form, that the entity must not reach. A reference to any of
     * them would make a data holder depend on a layer above it.
     */
    private static final List<String> FORBIDDEN_PACKAGES = List.of(
            "com/cardemo/model/key/",
            "com/cardemo/model/enums/",
            "com/cardemo/exception/",
            "com/cardemo/repository/",
            "com/cardemo/service/",
            "com/cardemo/controller/",
            "com/cardemo/batch/",
            "com/cardemo/security/",
            "com/cardemo/config/",
            "com/cardemo/observability/");

    /**
     * The JPA association annotations. Their absence is what keeps a keyed reference lookup from becoming an
     * unbounded collection hanging off the largest table in the schema.
     */
    private static final List<String> ASSOCIATION_ANNOTATIONS = List.of(
            "jakarta.persistence.OneToMany",
            "jakarta.persistence.ManyToOne",
            "jakarta.persistence.OneToOne",
            "jakarta.persistence.ManyToMany",
            "jakarta.persistence.JoinColumn",
            "jakarta.persistence.JoinColumns",
            "jakarta.persistence.JoinTable",
            "jakarta.persistence.ElementCollection",
            "jakarta.persistence.Enumerated");

    /** Binary floating point, primitive and boxed, which must not appear in any entity of this schema. */
    private static final List<String> BINARY_APPROXIMATION_TYPES =
            List.of("float", "double", "java.lang.Float", "java.lang.Double");

    /** A level item declaring a picture clause, once interior whitespace has been collapsed to one space. */
    private static final Pattern FIELD_DECLARATION =
            Pattern.compile("^(?<level>\\d{2}) (?<name>[A-Z0-9-]+) PIC (?<picture>[A-Z0-9()V.]+)\\.$");

    /** A catalogue entry header, which carries an ANSI carriage control character in column one. */
    private static final Pattern ENTRY_HEADER = Pattern.compile("^[01](?<kind>[A-Z]+) -+ (?<name>\\S+)$");

    /** One line of the catalogue's closing census, for example {@code AIX -------------------3}. */
    private static final Pattern CENSUS_LINE = Pattern.compile("^(?<kind>[A-Z]+) -+(?<count>\\d+)$");

    /**
     * A page header or its subtitle, which the listing utility interleaves <em>inside</em> an entry every
     * sixty or so lines. Both carry an ANSI carriage control character in column one that is
     * indistinguishable from the one on a genuine entry header, which is why an entry may not be delimited
     * by that character alone.
     */
    private static final Pattern PAGE_FURNITURE =
            Pattern.compile("^(?:1IDCAMS\\s+SYSTEM SERVICES|-\\s+LISTING FROM CATALOG).*$");

    /** The line that opens the catalogue's closing census, and so ends the last entry. */
    private static final String CENSUS_MARKER = "THE NUMBER OF ENTRIES PROCESSED WAS";

    /** A CICS file definition in the resource definition file. */
    private static final Pattern CICS_FILE = Pattern.compile("DEFINE FILE\\((?<name>[A-Z0-9]+)\\)");

    /**
     * One expected row of {@code app/data/ASCII/trantype.txt}, as the fixture spells it.
     *
     * @param code        the two-character {@code TRAN-TYPE} value at columns 1-2
     * @param description the {@code TRAN-TYPE-DESC} text at columns 3-52, before the fixture pads it
     */
    private record SeededType(String code, String description) {
    }

    /**
     * All seven rows in file order, spelled exactly as the fixture spells them. Enumerated in full rather
     * than sampled: seven rows is the entire table, so a partial assertion would be a choice to leave the
     * rest unproven.
     */
    private static final List<SeededType> SEEDED_TYPES = List.of(
            new SeededType("01", "Purchase"),
            new SeededType("02", "Payment"),
            new SeededType("03", "Credit"),
            new SeededType("04", "Authorization"),
            new SeededType("05", "Refund"),
            new SeededType("06", "Reversal"),
            new SeededType("07", "Adjustment"));

    @Nested
    @DisplayName("1. The field contract, parsed from app/cpy/CVTRA03Y.cpy")
    class TheCopybookFieldContract {

        @Test
        @DisplayName("L5, L6 and L7 declare TRAN-TYPE X(02), TRAN-TYPE-DESC X(50) and FILLER X(08)")
        void theThreeFieldsAreDeclaredWithTheirPictures() {
            final List<String> lines = frozenLines(COPYBOOK);

            assertThat(collapse(lines.get(3)))
                    .as("%s:L4 names the group item the whole layout hangs from", COPYBOOK)
                    .isEqualTo("01 TRAN-TYPE-RECORD.");
            assertThat(collapse(lines.get(4)))
                    .as("%s:L5 is the key field, and it is spelled TRAN-TYPE with no -CD suffix", COPYBOOK)
                    .isEqualTo("05 TRAN-TYPE PIC X(02).");
            assertThat(collapse(lines.get(5)))
                    .as("%s:L6 is the description the batch report writer prints", COPYBOOK)
                    .isEqualTo("05 TRAN-TYPE-DESC PIC X(50).");
            assertThat(collapse(lines.get(6)))
                    .as("%s:L7 is unnamed slack: counted toward the record length, never mapped", COPYBOOK)
                    .isEqualTo("05 FILLER PIC X(08).");
        }

        @Test
        @DisplayName("The copybook declares exactly three fields whose pictures sum to the 60 bytes its own "
                + "header states")
        void thePicturesSumToTheDeclaredRecordLength() {
            final List<String> lines = frozenLines(COPYBOOK);

            assertThat(collapse(lines.get(1)))
                    .as("%s:L2 states the record length in the copybook's own words", COPYBOOK)
                    .isEqualTo("* Data-structure for transaction type (RECLN = 60)");

            final List<Integer> widths = new ArrayList<>();
            for (final String line : lines) {
                final Matcher declaration = FIELD_DECLARATION.matcher(collapse(line));
                if (declaration.matches()) {
                    widths.add(characterWidthOf(declaration.group("picture")));
                }
            }

            assertThat(widths)
                    .as("%s declares three fields in layout order and no fourth", COPYBOOK)
                    .containsExactly(KEY_WIDTH, DESCRIPTION_WIDTH, FILLER_WIDTH);
            assertThat(widths.stream().mapToInt(Integer::intValue).sum())
                    .as("2 + 50 + 8 is the 60-byte record, which is also the catalogued AVGLRECL and "
                            + "MAXLRECL at %s:L3779-L3780", CATALOGUE)
                    .isEqualTo(RECORD_WIDTH);
        }

        @Test
        @DisplayName("Medium: TRAN-TYPE is the only two-character type code in app/cpy that omits the -CD "
                + "suffix, so the column is tran_type and never tran_type_cd")
        void theBareTypeNameIsUniqueAcrossTheWholeCopybookDirectory() {
            final List<String> bare = new ArrayList<>();
            final List<String> suffixed = new ArrayList<>();

            for (final Path copybook : frozenCopybooks()) {
                final List<String> lines = readLines(copybook);
                for (int index = 0; index < lines.size(); index++) {
                    final Matcher declaration = FIELD_DECLARATION.matcher(collapse(lines.get(index)));
                    if (!declaration.matches()) {
                        continue;
                    }
                    final String name = declaration.group("name");
                    // A two-character type code, which is the whole family this entity's key belongs to.
                    // Every other TYPE-named field in the corpus is X(01), X(15) or X(50) and so is not one.
                    if (!name.contains("TYPE") || !"X(02)".equals(declaration.group("picture"))) {
                        continue;
                    }
                    final String locator = copybook.getFileName() + ":" + (index + 1) + " " + name;
                    if (name.endsWith("-CD")) {
                        suffixed.add(locator);
                    } else {
                        bare.add(locator);
                    }
                }
            }

            assertThat(suffixed)
                    .as("every other two-character type code in %s carries the -CD suffix, which is what "
                            + "makes CVTRA03Y the exception rather than the convention", COPYBOOK_DIRECTORY)
                    .containsExactly(
                            "COSTM01.CPY:25 TRNX-TYPE-CD",
                            "CVTRA01Y.cpy:7 TRANCAT-TYPE-CD",
                            "CVTRA02Y.cpy:7 DIS-TRAN-TYPE-CD",
                            "CVTRA04Y.cpy:6 TRAN-TYPE-CD",
                            "CVTRA05Y.cpy:6 TRAN-TYPE-CD",
                            "CVTRA06Y.cpy:6 DALYTRAN-TYPE-CD",
                            "CVTRA07Y.cpy:20 TRAN-REPORT-TYPE-CD");
            assertThat(bare)
                    .as("exactly one declaration omits the suffix, and it is the primary key of this "
                            + "entity; remediation if the column is ever harmonised: name it %s, because the "
                            + "copybook and not the sibling naming habit is authoritative", KEY_COLUMN)
                    .containsExactly("CVTRA03Y.cpy:5 TRAN-TYPE");
        }

        @Test
        @DisplayName("The entity's key column is the underscored form of the bare copybook name, and the "
                + "harmonised tran_type_cd appears on no column at all")
        void theKeyColumnFollowsTheCopybookAndNotTheSiblings() {
            assertThat(columnNames())
                    .as("a wrong column name is not a cosmetic defect: ddl-auto is validate in every "
                            + "profile, so it aborts startup rather than degrading quietly")
                    .contains(KEY_COLUMN)
                    .doesNotContain(HARMONISED_KEY_COLUMN);
        }
    }

    @Nested
    @DisplayName("2. The catalogued geometry, parsed from app/catlg/LISTCAT.txt")
    class TheCataloguedGeometry {

        @Test
        @DisplayName("The TRANTYPE cluster reports KEYLEN 2, AVGLRECL 60, MAXLRECL 60 and RKP 0")
        void theClusterGeometryMatchesTheCopybook() {
            final List<String> data = catalogueEntry("DATA", "AWS.M2.CARDDEMO.TRANTYPE.VSAM.KSDS.DATA");

            assertThat(catalogueAttribute(data, "KEYLEN"))
                    .as("%s:L3779 reports the key length, which is TRAN-TYPE PIC X(02)", CATALOGUE)
                    .isEqualTo(KEY_WIDTH);
            assertThat(catalogueAttribute(data, "AVGLRECL"))
                    .as("%s:L3779 reports the average record length", CATALOGUE)
                    .isEqualTo(RECORD_WIDTH);
            assertThat(catalogueAttribute(data, "MAXLRECL"))
                    .as("%s:L3780 reports the maximum record length; equal to the average, so 60 is an "
                            + "exact length and not a mean", CATALOGUE)
                    .isEqualTo(RECORD_WIDTH);
            assertThat(catalogueAttribute(data, "RKP"))
                    .as("%s:L3780 reports the relative key position; zero puts the key at bytes 1-2, which "
                            + "is why a single scalar @Id is correct and no offset is modelled", CATALOGUE)
                    .isEqualTo(KEY_START_COLUMN - 1);
        }

        @Test
        @DisplayName("The catalogue's own record total is the seven rows the fixture holds")
        void theCataloguedRecordTotalIsTheSeededRowCount() {
            final List<String> data = catalogueEntry("DATA", "AWS.M2.CARDDEMO.TRANTYPE.VSAM.KSDS.DATA");

            assertThat(catalogueAttribute(data, "REC-TOTAL"))
                    .as("%s:L3784 counts the records the legacy cluster held, reached independently of "
                            + "app/data/ASCII/trantype.txt and agreeing with it", CATALOGUE)
                    .isEqualTo(SEEDED_ROW_COUNT)
                    .isEqualTo(SEEDED_TYPES.size());
        }

        @Test
        @DisplayName("The TRANTYPE cluster associates a DATA and an INDEX component and nothing else, so it "
                + "carries no alternate index and no path")
        void theClusterHasNoAlternateIndexAndNoPath() {
            final List<String> cluster = catalogueEntry("CLUSTER", "AWS.M2.CARDDEMO.TRANTYPE.VSAM.KSDS");

            assertThat(associationKinds(cluster))
                    .as("%s:L3766-L3768 lists this cluster's whole association set; an AIX or PATH entry "
                            + "here is the only thing that would justify a derived finder beyond the "
                            + "primary key, and there is none", CATALOGUE)
                    .containsExactly("DATA", "INDEX")
                    .doesNotContain("AIX", "PATH");
        }

        @Test
        @DisplayName("The cluster entry straddles a page break, so an entry cannot be delimited by the "
                + "carriage control character in column one")
        void theClusterEntryStraddlesAPageBreakAndIsStillReadWhole() {
            final List<String> lines = frozenLines(CATALOGUE);

            int header = -1;
            for (int index = 0; index < lines.size() && header < 0; index++) {
                final Matcher candidate = ENTRY_HEADER.matcher(lines.get(index));
                if (candidate.matches() && "CLUSTER".equals(candidate.group("kind"))
                        && "AWS.M2.CARDDEMO.TRANTYPE.VSAM.KSDS".equals(candidate.group("name"))) {
                    header = index;
                }
            }
            assertThat(header).as("%s:L3742 opens the cluster entry", CATALOGUE).isEqualTo(3741);

            int associations = -1;
            for (int index = header + 1; index < lines.size() && associations < 0; index++) {
                if ("ASSOCIATIONS".equals(lines.get(index).strip())) {
                    associations = index;
                }
            }
            assertThat(associations)
                    .as("%s:L3766 opens its association list, twenty-four lines further down", CATALOGUE)
                    .isEqualTo(3765);

            assertThat(lines.subList(header + 1, associations))
                    .as("a page header, its subtitle and a continuation line sit between the two, carrying "
                            + "the carriage control characters 1, - and 0 in column one; a parser that "
                            + "split on those characters would stop at %s:L3757 and never see the "
                            + "association list, then wrongly report this cluster as having no "
                            + "associations at all rather than failing outright", CATALOGUE)
                    .anyMatch(line -> line.startsWith("1IDCAMS"))
                    .anyMatch(line -> line.startsWith("-"))
                    .anyMatch(line -> line.startsWith("0"));

            assertThat(associationKinds(catalogueEntry("CLUSTER", "AWS.M2.CARDDEMO.TRANTYPE.VSAM.KSDS")))
                    .as("terminating on the next entry header instead reads the entry whole")
                    .isNotEmpty();
        }

        @Test
        @DisplayName("The catalogue tallies 3 alternate indexes, 10 clusters, 7 generation groups and 3 "
                + "paths, and its entry headers independently corroborate that tally")
        void theCensusAndTheEntryHeadersAgree() {
            assertThat(catalogueCensus("AIX")).as("%s:L3938", CATALOGUE).isEqualTo(3);
            assertThat(catalogueCensus("CLUSTER")).as("%s:L3940", CATALOGUE).isEqualTo(10);
            assertThat(catalogueCensus("GDG")).as("%s:L3942", CATALOGUE).isEqualTo(7);
            assertThat(catalogueCensus("PATH")).as("%s:L3946", CATALOGUE).isEqualTo(3);

            // Counting the entry headers is an independent route to the same figures: the census is a
            // summary the utility printed, the headers are the entries themselves. Agreement means neither
            // reading is a transcription error.
            assertThat(entryNames("AIX")).hasSize(catalogueCensus("AIX"));
            assertThat(entryNames("CLUSTER")).hasSize(catalogueCensus("CLUSTER"));
            assertThat(entryNames("PATH")).hasSize(catalogueCensus("PATH"));
        }

        @Test
        @DisplayName("All three alternate indexes and all three paths belong to CARDDATA, CARDXREF and "
                + "TRANSACT, so none can belong to TRANTYPE")
        void theThreeAlternateIndexesBelongToOtherClusters() {
            assertThat(entryNames("AIX"))
                    .as("%s enumerates every alternate index in the catalogue", CATALOGUE)
                    .containsExactly(
                            "AWS.M2.CARDDEMO.CARDDATA.VSAM.AIX",
                            "AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX",
                            "AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX");
            assertThat(entryNames("PATH"))
                    .as("each alternate index has exactly one path, and none of the three is over TRANTYPE")
                    .containsExactly(
                            "AWS.M2.CARDDEMO.CARDDATA.VSAM.AIX.PATH",
                            "AWS.M2.CARDDEMO.CARDXREF.VSAM.AIX.PATH",
                            "AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX.PATH");
            assertThat(entryNames("AIX"))
                    .noneMatch(name -> name.contains("TRANTYPE"));
        }
    }

    @Nested
    @DisplayName("3. The online definition that does not exist, parsed from app/csd/CARDDEMO.CSD")
    class TheAbsentOnlineDefinition {

        @Test
        @DisplayName("The resource definitions name exactly eight CICS files")
        void theFileControlTableHoldsEightFiles() {
            assertThat(cicsFileNames())
                    .as("%s is the whole online file control table", RESOURCE_DEFINITIONS)
                    .containsExactly("ACCTDAT", "CARDAIX", "CARDDAT", "CCXREF",
                            "CUSTDAT", "CXACAIX", "TRANSACT", "USRSEC");
        }

        @Test
        @DisplayName("TRANTYPE is not one of them, alongside TCATBALF, DISCGRP and TRANCATG, so this is a "
                + "batch-only dataset with no online path and no authorisation rule of its own")
        void theTypeTableHasNoOnlineDefinition() {
            assertThat(cicsFileNames())
                    .as("the four batch-only datasets have no CICS definition at all, which is why no REST "
                            + "endpoint reads this table directly and it needs no role rule")
                    .doesNotContain("TRANTYPE", "TCATBALF", "DISCGRP", "TRANCATG");

            assertThat(frozenLines(RESOURCE_DEFINITIONS))
                    .as("the name does not appear anywhere in %s, not even in a comment or a group "
                            + "definition", RESOURCE_DEFINITIONS)
                    .noneMatch(line -> line.contains("TRANTYPE"));
        }
    }

    @Nested
    @DisplayName("4. The JPA mapping: two columns, one scalar key, and nothing else")
    class TheJpaMapping {

        @Test
        @DisplayName("The type is an @Entity mapped to the transaction_type table")
        void theEntityIsMappedToItsTable() {
            assertThat(TransactionType.class.isAnnotationPresent(Entity.class))
                    .as("a persistent reference table must be an @Entity to be readable by a repository")
                    .isTrue();

            final Table table = TransactionType.class.getAnnotation(Table.class);
            assertThat(table).as("@Table names the table explicitly rather than leaving it to a naming "
                    + "strategy, because ddl-auto validate compares the name it resolves").isNotNull();
            assertThat(table.name()).isEqualTo(TABLE_NAME);
        }

        @Test
        @DisplayName("Exactly two columns are mapped, named tran_type and tran_type_desc in layout order")
        void exactlyTwoColumnsAreMapped() {
            assertThat(columnNames())
                    .as("this is the smallest entity in the schema: %s:L5-L6 declares two named fields and "
                            + "%s:L7 declares unnamed slack, so a third column would be an invention",
                            COPYBOOK, COPYBOOK)
                    .containsExactly(KEY_COLUMN, DESCRIPTION_COLUMN)
                    .hasSize(2);
        }

        @Test
        @DisplayName("The key is a plain scalar @Id of two characters with no generated value")
        void theKeyIsATwoCharacterScalarIdentifier() {
            final Field key = persistentFieldNamed(KEY_PROPERTY);

            assertThat(key.isAnnotationPresent(Id.class))
                    .as("%s carries @Id, from TRAN-TYPE at %s:L5", KEY_PROPERTY, COPYBOOK)
                    .isTrue();
            assertThat(key.getType())
                    .as("PIC X(02) is a character field, so the key is a String and not a numeric type")
                    .isEqualTo(String.class);
            assertThat(key.getAnnotation(Column.class).length())
                    .as("the declared width is the catalogued KEYLEN at %s:L3779; leaving it at the JPA "
                            + "default of 255 would silently widen a fixed-width key", CATALOGUE)
                    .isEqualTo(KEY_WIDTH);
            assertThat(key.isAnnotationPresent(GeneratedValue.class))
                    .as("the code arrives from app/data/ASCII/trantype.txt through the seed migration; "
                            + "generating it would invent identifiers the corpus does not have and would "
                            + "break every keyed read that resolves a code taken from a transaction record")
                    .isFalse();
        }

        @Test
        @DisplayName("The description is fifty characters and both columns are declared NOT NULL")
        void theDescriptionIsFiftyCharactersAndNothingIsNullable() {
            assertThat(persistentFieldNamed(DESCRIPTION_PROPERTY).getAnnotation(Column.class).length())
                    .as("TRAN-TYPE-DESC PIC X(50) at %s:L6", COPYBOOK)
                    .isEqualTo(DESCRIPTION_WIDTH);

            for (final Field mapped : persistentFields()) {
                assertThat(mapped.getAnnotation(Column.class).nullable())
                        .as("%s states nullable false rather than relying on the permissive JPA default, "
                                + "because a fixed-width COBOL field cannot be absent", mapped.getName())
                        .isFalse();
            }
        }

        @Test
        @DisplayName("High: no version column, because this is reference data written once by the seed "
                + "migration")
        void noOptimisticLockingVersionIsDeclared() {
            assertThat(declaredAnnotationNames())
                    .as("@Version belongs on the four mutable transactional entities - Account, Card, "
                            + "Customer and Transaction - and adding one here would oblige the schema to "
                            + "carry a column nothing ever increments")
                    .doesNotContain(Version.class.getName());

            for (final Field field : persistentFields()) {
                assertThat(field.isAnnotationPresent(Version.class)).isFalse();
            }
        }

        @Test
        @DisplayName("No embedded composite identifier, and no type from the key package anywhere in the "
                + "declared surface")
        void theKeyIsNotComposite() {
            assertThat(declaredAnnotationNames())
                    .as("only TransactionCategoryBalance, DisclosureGroup and TransactionCategory have a "
                            + "composite key; this layout is flat with a single two-byte key at RKP 0")
                    .doesNotContain(EmbeddedId.class.getName());

            assertThat(declaredSurfaceTypeNames())
                    .as("no @EmbeddedId means no identifier class, so nothing from the key package is "
                            + "reachable from this entity's surface")
                    .noneMatch(typeName -> typeName.startsWith("com.cardemo.model.key."));
        }

        @Test
        @DisplayName("The eight-byte FILLER is unmapped, yet the 60-byte record stays reconstructible")
        void theFillerIsUnmappedAndTheRecordStillAddsUp() {
            assertThat(persistentFields())
                    .as("no property models unnamed slack, so no caller can read or write those bytes by "
                            + "mistake")
                    .noneMatch(field -> field.getName().toLowerCase(Locale.ROOT).contains("filler"));

            assertThat(KEY_WIDTH + DESCRIPTION_WIDTH)
                    .as("the two mapped columns account for 52 of the 60 bytes")
                    .isEqualTo(52);
            assertThat(RECORD_WIDTH - KEY_WIDTH - DESCRIPTION_WIDTH)
                    .as("the remaining 8 bytes are the FILLER at %s:L7; the record length is recoverable "
                            + "from this class plus the copybook without a filler property existing",
                            COPYBOOK)
                    .isEqualTo(FILLER_WIDTH);
        }

        @Test
        @DisplayName("No JPA association in either direction, so a type lookup can never become an "
                + "unbounded collection")
        void noAssociationIsDeclared() {
            assertThat(declaredAnnotationNames())
                    .as("a reverse collection here would hang every transaction of a given type off this "
                            + "row, turning a keyed lookup into either an eager multi-row fetch or a lazy "
                            + "initialisation hazard; zero associations means structurally zero N+1")
                    .doesNotContainAnyElementsOf(ASSOCIATION_ANNOTATIONS);
        }
    }

    @Nested
    @DisplayName("5. High: it is a table, not a Java enum")
    class TheTypeIsATableNotAnEnum {

        @Test
        @DisplayName("The class is not an enum and does not live in the enums package")
        void theTypeIsAClassAndNotAnEnumConstantSet() {
            assertThat(TransactionType.class.isEnum())
                    .as("the seven codes are reference data seeded by the migration and extensible without "
                            + "a recompile; an enum would turn an unrecognised code into a load-time "
                            + "failure instead of the diagnostic the legacy program produces")
                    .isFalse();
            assertThat(TransactionType.class.getEnumConstants())
                    .as("a non-enum reports no enum constants")
                    .isNull();
            assertThat(TransactionType.class.getPackageName())
                    .as("the enums package holds UserType, FileStatus, TransactionSource and RejectCode, "
                            + "and this type is not among them")
                    .isEqualTo("com.cardemo.model.entity")
                    .isNotEqualTo("com.cardemo.model.enums");
        }

        @Test
        @DisplayName("The enums package is closed at four enums and this type is not one of them: it is one "
                + "of the eleven entities instead")
        void theTypeIsNotOneOfTheFourEnums() {
            assertThat(javaFileNamesIn("src/main/java/com/cardemo/model/enums"))
                    .as("the enums package holds exactly four enums and its package documentation; a fifth "
                            + "enum named for this table would duplicate reference data the seed migration "
                            + "owns, and would have to be edited and recompiled to add a type code")
                    .containsExactly("FileStatus.java", "RejectCode.java", "TransactionSource.java",
                            "UserType.java", "package-info.java")
                    .doesNotContain("TransactionType.java");
            assertThat(javaFileNamesIn("src/main/java/com/cardemo/model/entity"))
                    .as("it is an entity, one of the eleven record layouts of app/cpy")
                    .contains("TransactionType.java");
            assertThat(javaFileNamesIn("src/main/java/com/cardemo/model/key"))
                    .as("and the key package holds identifiers for the three composite-key clusters only, "
                            + "none of them this one")
                    .containsExactly("DisclosureGroupId.java", "TransactionCategoryBalanceId.java",
                            "TransactionCategoryId.java", "package-info.java")
                    .noneMatch(name -> name.startsWith("TransactionType."));
        }

        @Test
        @DisplayName("The class is not Serializable and extends nothing but Object")
        void theTypeOpensNoDeserialisationSurface() {
            assertThat(Serializable.class.isAssignableFrom(TransactionType.class))
                    .as("implementing Serializable would oblige a serialVersionUID, whose absence the "
                            + "serial lint reports and -Werror turns into a build failure; nothing "
                            + "serialises this type through Java serialisation, so the insecure "
                            + "deserialisation surface never opens")
                    .isFalse();
            assertThat(TransactionType.class.getSuperclass())
                    .as("no mapped superclass and no auditable base type: a two-column reference table "
                            + "gains nothing from a hierarchy and would pay for it in coupling")
                    .isEqualTo(Object.class);
            assertThat(TransactionType.class.getInterfaces()).isEmpty();
        }

        @Test
        @DisplayName("No binary floating point and no BigDecimal, because this layout holds no money")
        void theTypeDeclaresNoNumericValueAtAll() {
            final List<String> surface = declaredSurfaceTypeNames();

            assertThat(surface)
                    .as("no financial field may be a binary approximation; here there is no financial "
                            + "field at all, so neither primitive nor boxed form has any reason to appear")
                    .doesNotContainAnyElementsOf(BINARY_APPROXIMATION_TYPES);
            assertThat(surface)
                    .as("%s declares X(02), X(50) and X(08): three character fields and not one "
                            + "S9(n)V99, so a BigDecimal here would fabricate a column", COPYBOOK)
                    .doesNotContain("java.math.BigDecimal");
        }

        @Test
        @DisplayName("A no-argument constructor exists for the persistence provider, alongside the "
                + "two-argument constructor application code uses")
        void bothConstructorsArePresent() {
            final List<Integer> parameterCounts = new ArrayList<>();
            for (final Constructor<?> constructor : TransactionType.class.getDeclaredConstructors()) {
                if (!constructor.isSynthetic()) {
                    parameterCounts.add(constructor.getParameterCount());
                }
            }

            assertThat(parameterCounts)
                    .as("the JPA specification requires a no-argument constructor so the provider can "
                            + "instantiate a managed instance before populating it, and forbids a final "
                            + "entity; the two-argument form is how application and test code build one")
                    .containsExactlyInAnyOrder(0, 2);
            assertThat(Modifier.isFinal(TransactionType.class.getModifiers()))
                    .as("a final entity cannot be proxied and is forbidden by the specification")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("6. The dependency boundary, read from the compiled class file")
    class TheDependencyBoundary {

        @Test
        @DisplayName("The compiled entity references no type from the key, enums, exception, repository, "
                + "service, controller, batch, security, config or observability packages")
        void theEntityReachesNoLayerAboveIt() throws IOException {
            final String image = compiledImageOfTheEntity();

            for (final String forbidden : FORBIDDEN_PACKAGES) {
                assertThat(image)
                        .as("a data holder that reached %s would invert the dependency direction of the "
                                + "whole model package; the constant pool names every type the class "
                                + "actually uses, including one used only inside a method body, so this is "
                                + "a stronger statement than reading the import list", forbidden)
                        .doesNotContain(forbidden);
            }
        }

        @Test
        @DisplayName("The only cardemo type the compiled entity names is itself")
        void theEntityNamesNoOtherCardemoType() throws IOException {
            final Matcher references =
                    Pattern.compile("com/cardemo/[A-Za-z0-9/$]+").matcher(compiledImageOfTheEntity());
            final Set<String> named = new LinkedHashSet<>();
            while (references.find()) {
                named.add(references.group());
            }

            assertThat(named)
                    .as("a reference table needs no collaborator, so its compiled form names no sibling "
                            + "entity, no key class, no enum and no service")
                    .containsExactly("com/cardemo/model/entity/TransactionType");
        }
    }

    @Nested
    @DisplayName("7. The seeded fixture app/data/ASCII/trantype.txt, read by classpath resource name")
    class TheSeededFixture {

        @Test
        @DisplayName("The census is 427 bytes of 7 records 60 characters wide, and only a width of 60 can "
                + "satisfy it")
        void theGeometryIsForcedRatherThanChosen() {
            final FixtureLoader.FixtureData data = FixtureLoader.load(FixtureLoader.Fixture.TRANSACTION_TYPE);

            assertThat(data.resourceName()).isEqualTo("trantype.txt");
            assertThat(data.byteCount()).isEqualTo(427);
            assertThat(data.recordCount()).isEqualTo(SEEDED_ROW_COUNT);
            assertThat(data.recordWidth()).isEqualTo(RECORD_WIDTH);
            assertThat(data.impliedByteCount())
                    .as("7 records of 60 characters plus one line feed each is exactly 427 bytes")
                    .isEqualTo(data.byteCount());

            // The arithmetic is what makes the width a fact rather than a preference. Seven records one byte
            // narrower would imply 420 bytes and one byte wider 434, and the file is neither, so 60 is the
            // only width that satisfies the census. This one invariant simultaneously detects CRLF
            // conversion, trailing-space trimming, a missing final line feed and a lost row.
            final int ifOneByteNarrower = SEEDED_ROW_COUNT * ((RECORD_WIDTH - 1) + 1);
            final int ifOneByteWider = SEEDED_ROW_COUNT * ((RECORD_WIDTH + 1) + 1);
            assertThat(ifOneByteNarrower).isEqualTo(420).isNotEqualTo(data.byteCount());
            assertThat(ifOneByteWider).isEqualTo(434).isNotEqualTo(data.byteCount());
        }

        @Test
        @DisplayName("There is a 60th column and no 61st, so no record carries a stray byte")
        void theRecordEndsAtColumnSixty() {
            final FixtureLoader.FixtureData data = FixtureLoader.load(FixtureLoader.Fixture.TRANSACTION_TYPE);

            assertThatCode(() -> data.field(0, RECORD_WIDTH, 1))
                    .as("column 60 is the last byte of the record")
                    .doesNotThrowAnyException();
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a 61st column would mean the fixture had gained a byte, most often a carriage "
                            + "return from a checkout on a host that rewrites line endings")
                    .isThrownBy(() -> data.field(0, RECORD_WIDTH + 1, 1))
                    .withMessageContaining("runs past the 60-character record")
                    .withNoCause();
        }

        @Test
        @DisplayName("All seven rows carry their exact code and description: 01 Purchase, 02 Payment, "
                + "03 Credit, 04 Authorization, 05 Refund, 06 Reversal, 07 Adjustment")
        void allSevenRowsMatchTheirSeededValues() {
            final FixtureLoader.FixtureData data = FixtureLoader.load(FixtureLoader.Fixture.TRANSACTION_TYPE);

            for (int row = 0; row < SEEDED_TYPES.size(); row++) {
                final SeededType expected = SEEDED_TYPES.get(row);
                final TransactionType loaded = new TransactionType(
                        data.field(row, KEY_START_COLUMN, KEY_WIDTH),
                        data.field(row, DESCRIPTION_START_COLUMN, DESCRIPTION_WIDTH));

                assertThat(loaded.getTypeCode())
                        .as("row %d of trantype.txt holds code %s at columns 1-2", row + 1, expected.code())
                        .isEqualTo(expected.code());
                assertThat(loaded.getTypeDescription().strip())
                        .as("row %d holds the description %s at columns 3-52", row + 1,
                                expected.description())
                        .isEqualTo(expected.description());
            }
        }

        @Test
        @DisplayName("Each description is blank padded to exactly fifty characters and the entity returns it "
                + "padded, never trimmed")
        void theFiftyCharacterPaddingSurvivesTheRoundTrip() {
            final FixtureLoader.FixtureData data = FixtureLoader.load(FixtureLoader.Fixture.TRANSACTION_TYPE);

            for (int row = 0; row < SEEDED_TYPES.size(); row++) {
                final SeededType expected = SEEDED_TYPES.get(row);
                final String padded = expected.description()
                        + " ".repeat(DESCRIPTION_WIDTH - expected.description().length());
                final TransactionType loaded = new TransactionType(expected.code(), padded);

                assertThat(padded).hasSize(DESCRIPTION_WIDTH);
                assertThat(data.field(row, DESCRIPTION_START_COLUMN, DESCRIPTION_WIDTH))
                        .as("the fixture pads %s with %d trailing spaces", expected.description(),
                                DESCRIPTION_WIDTH - expected.description().length())
                        .isEqualTo(padded);
                assertThat(loaded.getTypeDescription())
                        .as("the padded value is the faithful PIC X(50) image, and trimming it would "
                                + "discard the geometry the fixed-width writers depend on")
                        .isEqualTo(padded)
                        .hasSize(DESCRIPTION_WIDTH)
                        .isNotEqualTo(expected.description());
            }
        }

        @Test
        @DisplayName("High: the trailing FILLER is zero filled, so a fixed-width writer that space pads "
                + "cannot reproduce the fixture byte for byte")
        void theFillerIsZeroFilledAndNotSpaceFilled() {
            final FixtureLoader.FixtureData data = FixtureLoader.load(FixtureLoader.Fixture.TRANSACTION_TYPE);
            final String zeroFilled = String.valueOf(FILLER_FILL_CHARACTER).repeat(FILLER_WIDTH);

            for (int row = 0; row < data.recordCount(); row++) {
                final String filler = data.field(row, FILLER_START_COLUMN, FILLER_WIDTH);

                assertThat(filler)
                        .as("columns 53-60 of row %d; the same convention holds in trancatg.txt, "
                                + "discgrp.txt and tcatbal.txt", row + 1)
                        .isEqualTo(zeroFilled)
                        .isNotEqualTo(" ".repeat(FILLER_WIDTH));
                assertThat(filler.chars().allMatch(character -> character == FILLER_FILL_CHARACTER))
                        .as("every filler byte is the character zero, not a space and not a NUL")
                        .isTrue();
            }
        }

        @Test
        @DisplayName("Blocker: every one of the seven descriptions begins with a letter in the overpunch "
                + "alphabet, so a global sign substitution would corrupt all seven")
        void aGlobalOverpunchSubstitutionWouldCorruptEveryDescription() {
            final List<String> wouldBeRewritten = new ArrayList<>();
            for (final SeededType seeded : SEEDED_TYPES) {
                if (OVERPUNCH_ALPHABET.indexOf(seeded.description().charAt(0)) >= 0) {
                    wouldBeRewritten.add(seeded.description());
                }
            }

            assertThat(wouldBeRewritten)
                    .as("P carries -7, C carries +3, A carries +1 and R carries -9, so a decoder applied "
                            + "without regard to position would turn each of these words into a number; "
                            + "remediation: slice at the PIC-derived offset and decode only S9(n)V99 fields")
                    .containsExactlyElementsOf(SEEDED_TYPES.stream().map(SeededType::description).toList())
                    .hasSize(SEEDED_ROW_COUNT);
        }

        @Test
        @DisplayName("Blocker guard: a decoder cannot diagnose itself, because on this layout it fails on "
                + "the description and succeeds meaninglessly on the code and the filler")
        void onlyThePictureClauseCanDecideWhereADecodeBelongs() {
            final FixtureLoader.FixtureData data = FixtureLoader.load(FixtureLoader.Fixture.TRANSACTION_TYPE);
            final String description = data.field(0, DESCRIPTION_START_COLUMN, DESCRIPTION_WIDTH);

            // Loud failure on the description: the leading 'P' of "Purchase" is not a digit.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> FixtureLoader.decodeZonedDecimal(description, FixtureLoader.DECIMAL_SCALE))
                    .withMessageContaining("every character before the sign position must be a digit")
                    .withMessageContaining("check the offset against the copybook PIC clause")
                    .withNoCause();

            // Silent success on the two all-digit fields, which is the dangerous case: the decode returns a
            // number for a field the copybook declares as PIC X, so a decoder that "works" proves nothing.
            assertThat(FixtureLoader.decodeZonedDecimal(
                    data.field(0, KEY_START_COLUMN, KEY_WIDTH), FixtureLoader.DECIMAL_SCALE))
                    .as("the key 01 decodes to a value even though X(02) is a character field, which is "
                            + "exactly why the decision must come from the PIC clause and never from "
                            + "whether a decode happens to throw")
                    .isEqualByComparingTo("0.01");
            assertThat(FixtureLoader.decodeZonedDecimal(
                    data.field(0, FILLER_START_COLUMN, FILLER_WIDTH), FixtureLoader.DECIMAL_SCALE))
                    .as("the zero-filled X(08) filler likewise decodes without complaint, and likewise "
                            + "must never be decoded")
                    .isEqualByComparingTo("0.00");
        }
    }

    @Nested
    @DisplayName("8. The lookup root: every trancatg.txt type code resolves here, and there is no eighth")
    class TheReferentialRoot {

        @Test
        @DisplayName("trancatg.txt holds eighteen rows whose type codes recur 5, 3, 3, 3, 1, 2 and 1 times")
        void theCategoryFixtureCarriesTheExpectedTypeCodeCensus() {
            final List<String> codes = categoryTypeCodes();

            assertThat(codes).hasSize(18);
            assertThat(codes.stream().filter("01"::equals).count()).as("Purchase").isEqualTo(5);
            assertThat(codes.stream().filter("02"::equals).count()).as("Payment").isEqualTo(3);
            assertThat(codes.stream().filter("03"::equals).count()).as("Credit").isEqualTo(3);
            assertThat(codes.stream().filter("04"::equals).count()).as("Authorization").isEqualTo(3);
            assertThat(codes.stream().filter("05"::equals).count()).as("Refund").isEqualTo(1);
            assertThat(codes.stream().filter("06"::equals).count()).as("Reversal").isEqualTo(2);
            assertThat(codes.stream().filter("07"::equals).count()).as("Adjustment").isEqualTo(1);
        }

        @Test
        @DisplayName("Every category type code resolves to a seeded type, so the reference set has no orphan")
        void theCategoryFixtureIsReferentiallyComplete() {
            final Set<String> seeded = new LinkedHashSet<>();
            for (final SeededType type : SEEDED_TYPES) {
                seeded.add(type.code());
            }

            assertThat(categoryTypeCodes())
                    .as("trancatg.txt depends on trantype.txt and trantype.txt depends on nothing, which is "
                            + "what makes this table the root of the reference-data set and the first thing "
                            + "the seed migration must load")
                    .allMatch(seeded::contains);
            assertThat(new LinkedHashSet<>(categoryTypeCodes()))
                    .as("the eighteen category rows between them exercise all seven types, so no seeded "
                            + "type is unreferenced either")
                    .containsExactlyInAnyOrderElementsOf(seeded);
        }

        @Test
        @DisplayName("No eighth type code exists in either fixture")
        void thereIsNoEighthTypeCode() {
            final Set<String> permitted = new LinkedHashSet<>();
            for (final SeededType type : SEEDED_TYPES) {
                permitted.add(type.code());
            }

            final FixtureLoader.FixtureData types =
                    FixtureLoader.load(FixtureLoader.Fixture.TRANSACTION_TYPE);
            final List<String> declared = new ArrayList<>();
            for (int row = 0; row < types.recordCount(); row++) {
                declared.add(types.field(row, KEY_START_COLUMN, KEY_WIDTH));
            }

            assertThat(declared)
                    .as("the codes are 01 through 07 with no gap, no duplicate and no eighth value")
                    .containsExactlyElementsOf(permitted)
                    .doesNotHaveDuplicates()
                    .hasSize(SEEDED_ROW_COUNT);
            assertThat(categoryTypeCodes())
                    .as("and nothing outside that set appears in trancatg.txt either")
                    .doesNotContain("00", "08", "09", "10", "  ");
        }
    }

    @Nested
    @DisplayName("9. Untrusted input: what each field admits, and what it refuses and why")
    class UntrustedInputAndBoundaries {

        @Test
        @DisplayName("A null code is refused by name, citing the column it maps to, and carries no cause")
        void aNullCodeIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("the column is NOT NULL and a fixed-width COBOL field cannot be absent, so the "
                            + "value is refused at construction rather than at flush")
                    .isThrownBy(() -> new TransactionType(null, "Purchase"))
                    .withMessage("typeCode (TRAN-TYPE PIC X(02)) must not be null: it maps to a NOT NULL "
                            + "CHAR(2) column of table transaction_type")
                    .withNoCause();
        }

        @Test
        @DisplayName("A null description is refused the same way, naming its own field and width")
        void aNullDescriptionIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new TransactionType("01", null))
                    .withMessage("typeDescription (TRAN-TYPE-DESC PIC X(50)) must not be null: it maps to a "
                            + "NOT NULL CHAR(50) column of table transaction_type")
                    .withNoCause();
        }

        @Test
        @DisplayName("The refusal names the offending property and never quotes the rejected value")
        void theRefusalDisclosesNoContent() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new TransactionType("001", "Purchase"))
                    .withMessage("typeCode (TRAN-TYPE PIC X(02)) must be at most 2 characters but was 3")
                    .withNoCause();

            // The diagnostic reports a width and a length, and no message here echoes an input value back.
            // Nothing in this layout is sensitive, but the habit is the one that matters: the same accessor
            // shape on Customer or UserSecurity would be echoing a government identifier or a credential.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new TransactionType("01", "x".repeat(DESCRIPTION_WIDTH + 1)))
                    .withMessage("typeDescription (TRAN-TYPE-DESC PIC X(50)) must be at most 50 characters "
                            + "but was 51")
                    .withNoCause();
        }

        @Test
        @DisplayName("A one-character code and an all-blank code are both admitted, because PIC X(02) admits "
                + "them and CHAR(2) blank pads")
        void shortAndBlankValuesAreAdmitted() {
            assertThatCode(() -> new TransactionType("1", "Purchase"))
                    .as("a shorter value is legal; the CHAR(2) column pads it, and refusing it here would "
                            + "reject a record the source accepts")
                    .doesNotThrowAnyException();
            assertThatCode(() -> new TransactionType(" ".repeat(KEY_WIDTH), " ".repeat(DESCRIPTION_WIDTH)))
                    .as("a value of only spaces is what an uninitialised COBOL character field holds, so "
                            + "no not-blank constraint may be declared on either column")
                    .doesNotThrowAnyException();
            assertThat(new TransactionType("1", "Purchase").getTypeCode())
                    .as("and the short value is stored exactly as given: nothing is padded, trimmed or "
                            + "normalised on the way in")
                    .isEqualTo("1");
        }

        @Test
        @DisplayName("The boundary is exact: fifty characters pass and fifty-one fail; two pass and three "
                + "fail")
        void theWidthBoundaryIsExact() {
            assertThatCode(() -> new TransactionType("01", "x".repeat(DESCRIPTION_WIDTH)))
                    .doesNotThrowAnyException();
            assertThatCode(() -> new TransactionType("x".repeat(KEY_WIDTH), "Purchase"))
                    .doesNotThrowAnyException();
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new TransactionType("x".repeat(KEY_WIDTH + 1), "Purchase"))
                    .withNoCause();
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new TransactionType("01", "x".repeat(DESCRIPTION_WIDTH + 1)))
                    .withNoCause();
        }

        @Test
        @DisplayName("The setters re-apply the same guard, so a validated instance cannot be widened after "
                + "construction")
        void theSettersReapplyTheGuard() {
            final TransactionType type = new TransactionType("01", "Purchase");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> type.setTypeCode("001"))
                    .withNoCause();
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> type.setTypeDescription("x".repeat(DESCRIPTION_WIDTH + 1)))
                    .withNoCause();
            assertThat(type.getTypeCode())
                    .as("a refused setter leaves the instance exactly as it was")
                    .isEqualTo("01");
            assertThat(type.getTypeDescription()).isEqualTo("Purchase");
        }

        @Test
        @DisplayName("Nothing is case folded: a lower-case value is stored and compared as written")
        void noValueIsCaseFolded() {
            final TransactionType lower = new TransactionType("0a", "purchase");

            assertThat(lower.getTypeCode())
                    .as("the source neither upper-cases nor lower-cases this field, so neither does the "
                            + "mapping; sign-on is the one place that folds case, and it folds both the "
                            + "identifier and the password")
                    .isEqualTo("0a")
                    .isNotEqualTo(lower.getTypeCode().toUpperCase(Locale.ROOT));
            assertThat(lower)
                    .as("two codes differing only in case are two different keys, and CHAR comparison in "
                            + "PostgreSQL is case sensitive, so folding here would merge distinct rows")
                    .isNotEqualTo(new TransactionType("0A", "purchase"));
            assertThat(lower.getTypeDescription())
                    .as("the description is stored verbatim too")
                    .isEqualTo("purchase")
                    .isNotEqualTo("Purchase");
        }
    }

    @Nested
    @DisplayName("10. Identity on the key alone, and a toString that may carry both columns")
    class IdentityAndDiagnosticRendering {

        @Test
        @DisplayName("Equality is reflexive, symmetric, transitive, null safe and type safe")
        void theEqualityContractHolds() {
            final TransactionType first = new TransactionType("01", "Purchase");
            final TransactionType second = new TransactionType("01", "Purchase");
            final TransactionType third = new TransactionType("01", "Purchase");

            assertThat(first).as("reflexive").isEqualTo(first);
            assertThat(first).as("symmetric").isEqualTo(second);
            assertThat(second).as("symmetric").isEqualTo(first);
            assertThat(second).as("transitive").isEqualTo(third);
            assertThat(first).as("transitive").isEqualTo(third);
            assertThat(first).as("null safe: no NullPointerException, simply not equal").isNotEqualTo(null);
            assertThat(first)
                    .as("type safe: a value of an unrelated type is never equal, and comparing one throws "
                            + "nothing")
                    .isNotEqualTo(KEY_COLUMN);
        }

        @Test
        @DisplayName("Equality and the hash code are keyed on the code alone, so the description is not part "
                + "of identity")
        void identityIsTheKeyAndNothingElse() {
            final TransactionType asSeeded = new TransactionType("01", "Purchase");
            final TransactionType renamed = new TransactionType("01", "Retail purchase");
            final TransactionType otherCode = new TransactionType("02", "Purchase");

            assertThat(asSeeded)
                    .as("the primary key is the identity, so re-describing a type does not make it a "
                            + "different type")
                    .isEqualTo(renamed)
                    .hasSameHashCodeAs(renamed);
            assertThat(asSeeded)
                    .as("and two different codes are two different rows however alike their text")
                    .isNotEqualTo(otherCode);
        }

        @Test
        @DisplayName("The hash code is stable across repeated invocation and consistent with equality")
        void theHashCodeIsStable() {
            final TransactionType type = new TransactionType("04", "Authorization");
            final int first = type.hashCode();

            assertThat(type.hashCode())
                    .as("a hash code that varied between calls would corrupt any map this row is held in")
                    .isEqualTo(first)
                    .isEqualTo(type.hashCode());
            assertThat(new TransactionType("04", "Authorization")).hasSameHashCodeAs(type);
        }

        @Test
        @DisplayName("All seven seeded rows are mutually distinct, so the key really does identify a row")
        void theSevenSeededRowsAreDistinct() {
            final Set<TransactionType> distinct = new LinkedHashSet<>();
            for (final SeededType seeded : SEEDED_TYPES) {
                distinct.add(new TransactionType(seeded.code(), seeded.description()));
            }

            assertThat(distinct)
                    .as("seven codes collapsing to fewer than seven set members would mean equality or the "
                            + "hash code was ignoring the key")
                    .hasSize(SEEDED_ROW_COUNT);
        }

        @Test
        @DisplayName("toString renders both columns, because neither is a secret")
        void toStringCarriesBothColumns() {
            final TransactionType type = new TransactionType("04", "Authorization");

            assertThat(type.toString())
                    .as("a two-character code and an English description are neither credential nor "
                            + "personal data, so both are rendered; Customer renders its identifier and "
                            + "version only because its layout carries a government identifier, a date of "
                            + "birth and two telephone numbers, and Card and Transaction withhold the card "
                            + "number because a primary account number must never reach a log line")
                    .contains("TransactionType")
                    .contains(KEY_PROPERTY + "=04")
                    .contains(DESCRIPTION_PROPERTY + "=Authorization");
        }

        @Test
        @DisplayName("toString names no credential, no token and no personal field, on any seeded row")
        void toStringDisclosesNothingSensitive() {
            for (final SeededType seeded : SEEDED_TYPES) {
                final String rendered =
                        new TransactionType(seeded.code(), seeded.description()).toString();

                assertThat(rendered.toLowerCase(Locale.ROOT))
                        .as("the rendering of row %s is bounded by the two columns the layout declares, so "
                                + "there is structurally nothing sensitive for it to leak", seeded.code())
                        .doesNotContain("password", "secret", "token", "credential", "ssn");
            }
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Private helpers. Each is a pure function of its arguments and of the frozen, read-only corpus; none
    // holds state between calls, and none writes, copies or moves a file of any kind.
    // ------------------------------------------------------------------------------------------------

    /**
     * Walks upward from the working directory to the directory holding both {@code pom.xml} and {@code app/}.
     *
     * <p>Surefire is configured with its working directory set to the project base directory, so the first
     * candidate normally succeeds. The walk exists so that a run started from a subdirectory still resolves
     * rather than silently reading nothing.
     *
     * @return the repository root
     * @throws IllegalStateException if no ancestor directory holds both markers
     */
    private static Path repositoryRoot() {
        final Path start = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path candidate = start; candidate != null; candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("pom.xml"))
                    && Files.isDirectory(candidate.resolve(FROZEN_CORPUS))) {
                return candidate;
            }
        }
        throw new IllegalStateException("No directory from " + start + " upward holds both pom.xml and "
                + FROZEN_CORPUS + "/, so the frozen corpus cannot be read. Run this test with the "
                + "repository root, or any directory beneath it, as the working directory.");
    }

    /**
     * Reads one frozen corpus file, named by its repository-relative path.
     *
     * @param relativePath the path relative to the repository root, for example {@code app/cpy/CVTRA03Y.cpy}
     * @return the lines, in file order, with trailing spaces intact
     */
    private static List<String> frozenLines(final String relativePath) {
        final Path file = repositoryRoot().resolve(relativePath);
        assertThat(file)
                .as("%s is part of the frozen legacy corpus and must be present and readable, or every "
                        + "assertion drawn from it proves nothing", relativePath)
                .isRegularFile();
        return readLines(file);
    }

    /**
     * Reads a file as ISO-8859-1, which is total and byte preserving so no byte can throw or be replaced.
     *
     * @param file the file to read
     * @return the lines, in file order
     * @throws UncheckedIOException if the file is present but cannot be read to completion
     */
    private static List<String> readLines(final Path file) {
        try {
            return List.copyOf(Files.readAllLines(file, StandardCharsets.ISO_8859_1));
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(file + " is present but could not be read to completion",
                    unreadable);
        }
    }

    /**
     * Lists every copybook, ordered by file name so the scan is repeatable on any file system.
     *
     * @return the copybook paths, in file-name order
     * @throws UncheckedIOException if the directory cannot be listed
     */
    private static List<Path> frozenCopybooks() {
        final Path directory = repositoryRoot().resolve(COPYBOOK_DIRECTORY);
        assertThat(directory).as("%s holds the record layouts", COPYBOOK_DIRECTORY).isDirectory();
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(Files::isRegularFile)
                    .sorted((left, right) -> left.getFileName().toString()
                            .compareTo(right.getFileName().toString()))
                    .toList();
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(COPYBOOK_DIRECTORY + " could not be listed", unreadable);
        }
    }

    /**
     * Lists the Java source file names of one package directory, in file-name order.
     *
     * <p>Reading the directory rather than a remembered list is what lets a package census be an assertion
     * instead of a comment: a type added to, moved into or renamed within the package changes the answer.
     *
     * @param relativeDirectory the directory relative to the repository root
     * @return the file names, ordered so the result is repeatable on any file system
     * @throws UncheckedIOException if the directory cannot be listed
     */
    private static List<String> javaFileNamesIn(final String relativeDirectory) {
        final Path directory = repositoryRoot().resolve(relativeDirectory);
        assertThat(directory).as("%s must exist for its census to mean anything", relativeDirectory)
                .isDirectory();
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".java"))
                    .sorted()
                    .toList();
        } catch (final IOException unreadable) {
            throw new UncheckedIOException(relativeDirectory + " could not be listed", unreadable);
        }
    }

    /**
     * Strips a source line and collapses every run of interior whitespace to one space.
     *
     * <p>This is what makes a copybook comparable without depending on the fixed-column padding that pads
     * every legacy line out to column 72, and it removes a stray carriage return from the handful of members
     * that carry one.
     *
     * @param line the raw line
     * @return the line with leading, trailing and repeated whitespace normalised
     */
    private static String collapse(final String line) {
        return line.strip().replaceAll("\\s{2,}", " ");
    }

    /**
     * Returns the character width a {@code PIC X(n)} clause declares.
     *
     * @param picture the picture clause, for example {@code X(02)}
     * @return the declared width in characters
     * @throws IllegalStateException if the clause is not an alphanumeric picture of the expected shape
     */
    private static int characterWidthOf(final String picture) {
        final Matcher alphanumeric = Pattern.compile("^X\\((?<width>\\d+)\\)$").matcher(picture);
        if (!alphanumeric.matches()) {
            throw new IllegalStateException(picture + " is not an alphanumeric picture clause of the form "
                    + "X(n); " + COPYBOOK + " declares three of them and nothing else");
        }
        return Integer.parseInt(alphanumeric.group("width"));
    }

    /**
     * Returns the lines of one catalogue entry, from its header to the line before the next entry.
     *
     * <p>Entries are delimited by the ANSI carriage control character the listing utility writes in column
     * one, so an entry runs until the next line beginning with {@code 0} or {@code 1}.
     *
     * @param kind the entry kind, for example {@code CLUSTER} or {@code DATA}
     * @param name the fully qualified dataset name
     * @return the entry's lines, header first
     * @throws IllegalStateException if the catalogue holds no such entry
     */
    private static List<String> catalogueEntry(final String kind, final String name) {
        final List<String> lines = frozenLines(CATALOGUE);
        for (int index = 0; index < lines.size(); index++) {
            final Matcher header = ENTRY_HEADER.matcher(lines.get(index));
            if (!header.matches() || !header.group("kind").equals(kind)
                    || !header.group("name").equals(name)) {
                continue;
            }
            final List<String> entry = new ArrayList<>();
            entry.add(lines.get(index));
            for (int within = index + 1; within < lines.size(); within++) {
                final String line = lines.get(within);
                // An entry ends at the next entry header or at the closing census, and at nothing else. In
                // particular it does NOT end at a page break: the utility interleaves a page header and its
                // subtitle mid-entry, and both carry a carriage control character in column one that looks
                // exactly like the one on a real header. Splitting on that character alone truncates this
                // very cluster at its page break and loses its whole association list.
                if (ENTRY_HEADER.matcher(line).matches() || line.contains(CENSUS_MARKER)) {
                    break;
                }
                if (PAGE_FURNITURE.matcher(line).matches()) {
                    continue;
                }
                entry.add(line);
            }
            return List.copyOf(entry);
        }
        throw new IllegalStateException(
                CATALOGUE + " holds no " + kind + " entry named " + name + "; the catalogue is frozen, so "
                        + "either the name is misspelled or the corpus has been altered");
    }

    /**
     * Reads one numeric attribute out of a catalogue entry, for example the {@code KEYLEN} of a cluster.
     *
     * @param entry     the entry's lines, as returned by {@link #catalogueEntry(String, String)}
     * @param attribute the attribute name exactly as the listing spells it
     * @return the attribute's value
     * @throws IllegalStateException if the entry does not report that attribute
     */
    private static int catalogueAttribute(final List<String> entry, final String attribute) {
        final Pattern reported = Pattern.compile("(?<![A-Z-])" + attribute + "-+(?<value>\\d+)");
        for (final String line : entry) {
            final Matcher value = reported.matcher(line);
            if (value.find()) {
                return Integer.parseInt(value.group("value"));
            }
        }
        throw new IllegalStateException(
                "no " + attribute + " is reported by the catalogue entry beginning " + entry.getFirst());
    }

    /**
     * Returns the association kinds a catalogue entry declares, in listing order.
     *
     * @param entry the entry's lines
     * @return the kinds, for example {@code DATA} and {@code INDEX}
     */
    private static List<String> associationKinds(final List<String> entry) {
        final Pattern association = Pattern.compile("^(?<kind>[A-Z]+)-+(?<name>[A-Z0-9.]+)$");
        final Pattern sectionHeading = Pattern.compile("^[A-Z][A-Z0-9 ]*$");
        final List<String> kinds = new ArrayList<>();
        boolean withinAssociations = false;
        for (final String line : entry) {
            final String stripped = line.strip();
            if (!withinAssociations) {
                withinAssociations = "ASSOCIATIONS".equals(stripped);
                continue;
            }
            final Matcher matched = association.matcher(stripped);
            if (matched.matches()) {
                kinds.add(matched.group("kind"));
            } else if (sectionHeading.matcher(stripped).matches()) {
                // The entry's next section, for example ATTRIBUTES. Stopping on a bare section heading
                // rather than on the first line that does not parse keeps a page break from truncating a
                // list that continues after it.
                break;
            }
        }
        return List.copyOf(kinds);
    }

    /**
     * Reads one figure out of the catalogue's closing census of processed entries.
     *
     * @param kind the entry kind, for example {@code AIX}
     * @return the number of entries of that kind the utility reported
     * @throws IllegalStateException if the census does not report that kind
     */
    private static int catalogueCensus(final String kind) {
        for (final String line : frozenLines(CATALOGUE)) {
            final Matcher census = CENSUS_LINE.matcher(line.strip());
            if (census.matches() && census.group("kind").equals(kind)) {
                return Integer.parseInt(census.group("count"));
            }
        }
        throw new IllegalStateException(CATALOGUE + " reports no census figure for " + kind);
    }

    /**
     * Enumerates every catalogue entry of one kind, by dataset name, in listing order.
     *
     * @param kind the entry kind, for example {@code AIX} or {@code PATH}
     * @return the dataset names
     */
    private static List<String> entryNames(final String kind) {
        final List<String> names = new ArrayList<>();
        for (final String line : frozenLines(CATALOGUE)) {
            final Matcher header = ENTRY_HEADER.matcher(line);
            if (header.matches() && header.group("kind").equals(kind)) {
                names.add(header.group("name"));
            }
        }
        return List.copyOf(names);
    }

    /**
     * Returns every CICS file name the resource definitions declare, in definition order.
     *
     * @return the eight file names of the online file control table
     */
    private static List<String> cicsFileNames() {
        final List<String> names = new ArrayList<>();
        for (final String line : frozenLines(RESOURCE_DEFINITIONS)) {
            final Matcher file = CICS_FILE.matcher(line);
            if (file.find()) {
                names.add(file.group("name"));
            }
        }
        return List.copyOf(names);
    }

    /**
     * Returns the {@code TRAN-TYPE-CD} value of every row of {@code app/data/ASCII/trancatg.txt}.
     *
     * <p>The columns come from {@code app/cpy/CVTRA04Y.cpy:L6}, where {@code TRAN-TYPE-CD PIC X(02)} is the
     * first two bytes of the composite key, which is why the slice is taken at columns 1-2 of that fixture
     * and not at the description.
     *
     * @return the eighteen type codes, in file order and with repeats
     */
    private static List<String> categoryTypeCodes() {
        final FixtureLoader.FixtureData categories =
                FixtureLoader.load(FixtureLoader.Fixture.TRANSACTION_CATEGORY);
        final List<String> codes = new ArrayList<>();
        for (int row = 0; row < categories.recordCount(); row++) {
            codes.add(categories.field(row, KEY_START_COLUMN, KEY_WIDTH));
        }
        return List.copyOf(codes);
    }

    /**
     * Returns the entity's persistent fields: instance fields carrying a {@code Column}, in declaration
     * order.
     *
     * <p>Static and synthetic fields are excluded, which removes both the private width constants and the
     * field the coverage agent adds when it instruments a class.
     *
     * @return the mapped fields
     */
    private static List<Field> persistentFields() {
        final List<Field> mapped = new ArrayList<>();
        for (final Field field : TransactionType.class.getDeclaredFields()) {
            if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            if (field.isAnnotationPresent(Column.class)) {
                mapped.add(field);
            }
        }
        return List.copyOf(mapped);
    }

    /**
     * Returns the column names of {@link #persistentFields()}, in declaration order.
     *
     * @return the mapped column names
     */
    private static List<String> columnNames() {
        final List<String> names = new ArrayList<>();
        for (final Field field : persistentFields()) {
            names.add(field.getAnnotation(Column.class).name());
        }
        return List.copyOf(names);
    }

    /**
     * Returns one persistent field by its Java property name.
     *
     * @param propertyName the field name, for example {@code typeCode}
     * @return the field
     * @throws IllegalStateException if the entity declares no mapped field of that name
     */
    private static Field persistentFieldNamed(final String propertyName) {
        for (final Field field : persistentFields()) {
            if (field.getName().equals(propertyName)) {
                return field;
            }
        }
        throw new IllegalStateException(
                "TransactionType declares no mapped field named " + propertyName + "; it maps " + columnNames());
    }

    /**
     * Names every annotation declared on the entity or on any member it declares.
     *
     * @return the fully qualified annotation type names, without repeats
     */
    private static Set<String> declaredAnnotationNames() {
        final Set<String> names = new LinkedHashSet<>();
        for (final Annotation annotation : TransactionType.class.getDeclaredAnnotations()) {
            names.add(annotation.annotationType().getName());
        }
        for (final Field field : TransactionType.class.getDeclaredFields()) {
            for (final Annotation annotation : field.getDeclaredAnnotations()) {
                names.add(annotation.annotationType().getName());
            }
        }
        for (final Constructor<?> constructor : TransactionType.class.getDeclaredConstructors()) {
            for (final Annotation annotation : constructor.getDeclaredAnnotations()) {
                names.add(annotation.annotationType().getName());
            }
        }
        for (final Method method : TransactionType.class.getDeclaredMethods()) {
            for (final Annotation annotation : method.getDeclaredAnnotations()) {
                names.add(annotation.annotationType().getName());
            }
        }
        return Set.copyOf(names);
    }

    /**
     * Names every type appearing anywhere in the entity's own declared surface.
     *
     * <p>Field types, method return types, method parameter types and constructor parameter types are all
     * reported, each as its generic type name, which is what makes this census able to prove that a type is
     * absent rather than merely unimported.
     *
     * @return the declared type names, with repeats
     */
    private static List<String> declaredSurfaceTypeNames() {
        final List<String> names = new ArrayList<>();
        for (final Field field : TransactionType.class.getDeclaredFields()) {
            if (!field.isSynthetic()) {
                names.add(field.getGenericType().getTypeName());
            }
        }
        for (final Method method : TransactionType.class.getDeclaredMethods()) {
            if (method.isSynthetic() || method.isBridge()) {
                continue;
            }
            names.add(method.getGenericReturnType().getTypeName());
            for (final Type parameter : method.getGenericParameterTypes()) {
                names.add(parameter.getTypeName());
            }
        }
        for (final Constructor<?> constructor : TransactionType.class.getDeclaredConstructors()) {
            if (constructor.isSynthetic()) {
                continue;
            }
            for (final Type parameter : constructor.getGenericParameterTypes()) {
                names.add(parameter.getTypeName());
            }
        }
        return List.copyOf(names);
    }

    /**
     * Reads the compiled entity off the test classpath and decodes its bytes one for one as characters, which
     * makes the constant pool searchable as text.
     *
     * <p>The coverage agent instruments classes as they are loaded and does not rewrite the file on disk, so
     * what this reads is the class file the compiler emitted.
     *
     * @return the compiled class file, one character per byte
     * @throws IOException if the class file is present but cannot be read to completion
     */
    private static String compiledImageOfTheEntity() throws IOException {
        final String resourceName = TransactionType.class.getSimpleName() + ".class";
        try (InputStream compiled = TransactionType.class.getResourceAsStream(resourceName)) {
            assertThat(compiled)
                    .as("%s must be resolvable from the package of the entity on the test classpath, or the "
                            + "scan proves nothing", resourceName)
                    .isNotNull();
            return new String(compiled.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }
}

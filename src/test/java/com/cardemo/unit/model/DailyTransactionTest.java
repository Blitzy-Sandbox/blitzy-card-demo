/*
 * ******************************************************************
 * Program     : DailyTransactionTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Proves the DALYTRAN staging entity against the frozen
 *               350-byte copybook and the 300-row ASCII fixture: the
 *               offset map, NUMERIC(11,2) amount precision, signed
 *               amounts carried without normalisation, the 26-blank
 *               process timestamp, free-text transaction source, and
 *               the deliberate ABSENCE of a version column, a foreign
 *               key and any shared superclass with Transaction.
 * Source      : app/cpy/CVTRA06Y.cpy:L4-L18   (350 B staging layout)
 *               app/cpy/CVTRA05Y.cpy:L4-L18   (the keyed twin)
 *               app/cpy/CSDAT01Y.cpy:L42-L55  (WS-TIMESTAMP geometry)
 *               app/cbl/CBTRN02C.cbl:L547-L552 (cycle credit/debit)
 *               app/cbl/COBIL00C.cbl:L249-L267 (online timestamp)
 *               app/catlg/LISTCAT.txt:L3938-L3946 (10 CLUSTER, no DALYTRAN)
 *               app/data/ASCII/dailytran.txt  (300 rows x 350 bytes)
 *               @ 7756d89
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
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.cardemo.model.entity.DailyTransaction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link DailyTransaction}, the JPA entity that stages the inbound
 * {@code DALYTRAN} daily transaction feed.
 *
 * <h2>What this class does</h2>
 *
 * <p>It proves the entity against two independent oracles rather than against numbers
 * transcribed into the test. The first is the frozen copybook
 * {@code app/cpy/CVTRA06Y.cpy:L4-L18}, parsed at run time by {@link RecordLayoutCopybook},
 * which declares {@code 01 DALYTRAN-RECORD.} with thirteen leaf fields and a closing
 * {@code FILLER PIC X(20)} summing to the 350 bytes its {@code :L2} header states. The
 * second is the shipped fixture {@code app/data/ASCII/dailytran.txt}, loaded through
 * {@link FixtureLoader}, whose 300 records corroborate the same geometry byte for byte.
 * Where the two agree, a hardcoded expectation in this file would add nothing; where a
 * number appears literally below it is because the fixture census establishes it.
 *
 * <p>Five properties of this entity are easy to get wrong in ways no compiler catches,
 * and each has a dedicated group here.
 *
 * <ul>
 *   <li><b>{@code DALYTRAN-AMT} is {@code S9(09)V99}, so the column is
 *       {@code NUMERIC(11,2)} and never {@code NUMERIC(12,2)}.</b> Eleven characters, not
 *       twelve: the twelve-character tier belongs to {@code Account}'s {@code S9(10)V99}
 *       money fields and the six-character tier to {@code DisclosureGroup}'s
 *       {@code S9(04)V99} rate. Collapsing the three tiers silently widens a column.</li>
 *   <li><b>The amount keeps its sign.</b>
 *       {@code app/cbl/CBTRN02C.cbl:L547-L552} adds a non-negative amount to the cycle
 *       credit accumulator and a negative one to the cycle debit accumulator, so the debit
 *       accumulator legitimately holds negative values - which is exactly why the
 *       over-limit formula subtracts it. The fixture carries 50 genuinely negative rows.
 *       No absolute value, rescale or rounding may be applied anywhere.</li>
 *   <li><b>{@code DALYTRAN-PROC-TS} is 26 blanks on all 300 fixture rows</b>, so the
 *       column must be {@code CHAR(26)}. A temporal type cannot represent 26 spaces and
 *       the seed load would fail outright.</li>
 *   <li><b>{@code DALYTRAN-SOURCE} is {@code PIC X(10)} free text</b>, never a closed
 *       enum: {@code OPERATOR} occupies 50 of the 300 rows yet appears in no program
 *       literal in the corpus, so an enum-typed column would reject valid input.</li>
 *   <li><b>The absences are contracts too.</b> No version column, no foreign key, no
 *       association and no shared superclass with {@code Transaction}. This is an
 *       untrusted inbound staging table: referential judgement belongs to the posting
 *       processor's reject codes 100 ({@code INVALID CARD NUMBER FOUND}) and 101
 *       ({@code ACCOUNT RECORD NOT FOUND}), which are business outcomes driving an exit
 *       status rather than exceptions, and never to a database constraint.</li>
 * </ul>
 *
 * <h2>Severity of the regressions these tests guard against</h2>
 *
 * <p>Each entry names the defect, the group that would fail, and the remediation.
 *
 * <ul>
 *   <li><b>Blocker</b> - typing {@code dalytran_source} as {@code TransactionSource} rather than
 *       {@code String}. Caught by {@link TransactionSourceIsFreeText}. Remediation: restore
 *       {@code String}; the column is {@code PIC X(10)} free text and {@code OPERATOR} is in no
 *       program literal.</li>
 *   <li><b>Blocker</b> - applying the zoned-decimal overpunch table across a whole record instead of
 *       to the eleven bytes at columns 133-143. Caught by {@link OverpunchDecoding}. Remediation:
 *       slice the field at its PIC-derived offset first, then decode.</li>
 *   <li><b>High</b> - declaring {@code dalytran_proc_ts} as {@code TIMESTAMP} rather than
 *       {@code CHAR(26)}. Caught by {@link SchemaMapping} and {@link FixtureCensus}. Remediation:
 *       restore {@code CHAR(26)}; 26 blanks are not a temporal value.</li>
 *   <li><b>High</b> - adding {@code @Version} to this entity. Caught by {@link SchemaMapping}.
 *       Remediation: remove it; only four entities are versioned and this staging table is loaded
 *       and consumed inside one job.</li>
 *   <li><b>High</b> - adding a foreign key or a JPA association. Caught by {@link SchemaMapping}.
 *       Remediation: remove it; reject codes 100 and 101 own referential judgement.</li>
 *   <li><b>High</b> - unifying this entity with {@code Transaction} under a shared superclass.
 *       Caught by {@link NoSharedSuperclass}. Remediation: keep them separate; unification would
 *       hand this table a version column.</li>
 *   <li><b>High</b> - declaring the amount {@code NUMERIC(12,2)}. Caught by {@link AmountPrecision}.
 *       Remediation: restore {@code NUMERIC(11,2)}; twelve is the {@code S9(10)V99} tier.</li>
 *   <li><b>Medium</b> - trimming or reflowing the fixture. Caught by {@link FixtureCensus}.
 *       Remediation: restore the byte-exact copy; trailing-space runs reach 46 characters.</li>
 *   <li><b>Low</b> - widening {@code toString} to carry the amount or a timestamp. Caught by
 *       {@link ToStringSecurity}. Remediation: render the identifier only.</li>
 * </ul>
 *
 * <p>Where the source genuinely does not carry a fact, these tests say so rather than inventing one:
 * {@code DALYTRAN} has no VSAM catalogue entry, so its key length is <b>Not available</b> by
 * construction, and {@link ScopeBoundaries#theEntityHasNoCatalogueEntry()} proves that from
 * {@code app/catlg/LISTCAT.txt} instead of asserting a fabricated number.
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>{@code ./mvnw -B -ntp test} runs this class; {@code ./mvnw -B -ntp verify} adds the
 * coverage gate. The build compiles at {@code release 25} with {@code -Xlint:all -Werror}
 * and {@code failOnWarning}, and that reaches test compilation, so a single unused import
 * or raw type fails the build rather than warning. This file sits under
 * {@code src/test/java/com/cardemo/unit/} because <b>Surefire alone collects that
 * tree</b>: the plugin includes {@code **}{@code /*Test.java} while excluding
 * {@code **}{@code /integration/**} and {@code **}{@code /e2e/**}, so a class moved out of
 * it is picked up by neither Surefire nor Failsafe and silently never runs - a green build
 * that proves nothing.
 *
 * <h2>Key configurations and defaults</h2>
 *
 * <p>This is the pure-JVM tier: no Spring context, no container, no database and no
 * network. Every instance is built through the entity's own public constructor, so no test
 * double is required and none is created - a mock of a POJO would assert the mock rather
 * than the entity. Time is supplied exclusively by {@link FixedClockProvider}, whose
 * {@code CANONICAL_ONLINE_TIMESTAMP} is the single value the fixture holds in bytes
 * 279-304; {@code Instant.now()}, {@code LocalDate.now()} and the default locale and zone
 * are never consulted, so a run in any timezone produces an identical result. Fixtures are
 * read by classpath resource name only and never written, copied or edited: {@code app/}
 * is frozen.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><b>Build fails with "warnings found and -Werror specified".</b> An unused import,
 *       a raw type or a deprecated call in this file. The compiler names the line; remove
 *       the cause rather than relaxing the flag.</li>
 *   <li><b>{@code no classpath resource named 'dalytran.txt'}.</b> The fixture spells the
 *       word in full - {@code dailytran.txt} - even though the mainframe DD name and
 *       dataset are {@code DALYTRAN}. Use {@link FixtureLoader.Fixture#DAILY_TRANSACTION}
 *       rather than a literal.</li>
 *   <li><b>Seed or persistence failure on the process timestamp.</b> The column has been
 *       declared {@code TIMESTAMP} instead of {@code CHAR(26)}; 26 spaces are not a
 *       temporal value in any format. Restore {@code CHAR(26)}.</li>
 *   <li><b>Merchant text arrives corrupted.</b> Overpunch decoding has been applied
 *       globally instead of position-aware from the PIC clause. {@code A} through
 *       {@code R} occur legitimately inside {@code DALYTRAN-DESC},
 *       {@code DALYTRAN-MERCHANT-NAME} and {@code DALYTRAN-MERCHANT-CITY} on every one of
 *       the 300 rows, so a blanket substitution corrupts all three fields. Decode only the
 *       eleven bytes at columns 133-143.</li>
 *   <li><b>Fixture census assertion fails.</b> The fixture has been reflowed, trimmed or
 *       converted to CRLF. Its trailing-space runs reach 46 characters, so any whitespace
 *       cleanup destroys the fixed-width geometry.</li>
 * </ul>
 *
 * @see DailyTransaction
 * @see FixtureLoader
 * @see FixedClockProvider
 */
@DisplayName("DailyTransaction: the 350-byte DALYTRAN staging entity")
class DailyTransactionTest {

    /** The copybook member that declares this entity's record layout. */
    private static final String COPYBOOK_MEMBER = "CVTRA06Y";

    /** Record length declared by {@code app/cpy/CVTRA06Y.cpy:L2}, in bytes. */
    private static final int RECORD_LENGTH = 350;

    /** Number of leaf fields the copybook names, excluding the trailing {@code FILLER}. */
    private static final int MAPPED_FIELD_COUNT = 13;

    /** Width of the unnamed trailing {@code FILLER PIC X(20)} at {@code :L18}. */
    private static final int FILLER_WIDTH = 20;

    /** One-based start column of {@code DALYTRAN-AMT}. */
    private static final int AMOUNT_COLUMN = 133;

    /** One-based start column of {@code DALYTRAN-PROC-TS}. */
    private static final int PROC_TS_COLUMN = 305;

    /** One-based start column of {@code DALYTRAN-ORIG-TS}. */
    private static final int ORIG_TS_COLUMN = 279;

    /** One-based start column of {@code DALYTRAN-CARD-NUM}. */
    private static final int CARD_NUMBER_COLUMN = 263;

    /** One-based start column of {@code DALYTRAN-TYPE-CD}. */
    private static final int TYPE_CODE_COLUMN = 17;

    /** One-based start column of {@code DALYTRAN-SOURCE}. */
    private static final int SOURCE_COLUMN = 23;

    /** Width shared by {@code DALYTRAN-ORIG-TS} and {@code DALYTRAN-PROC-TS}. */
    private static final int TIMESTAMP_WIDTH = 26;

    /** Width of {@code DALYTRAN-CARD-NUM} and {@code DALYTRAN-ID}. */
    private static final int CARD_NUMBER_WIDTH = 16;

    /** Declared precision of the {@code dalytran_amt} column: nine integer digits plus two decimals. */
    private static final int AMOUNT_PRECISION = 11;

    /** Declared scale of the {@code dalytran_amt} column, from the {@code V99} of the PIC clause. */
    private static final int AMOUNT_SCALE = 2;

    /** The one prefix every column derived from the copybook carries. */
    private static final String COLUMN_PREFIX = "dalytran_";

    /** The synthetic staging key: the only mapped column with no copybook line. */
    private static final String SYNTHETIC_KEY_COLUMN = "ingest_seq";

    /** Fully qualified name of the keyed twin, resolved reflectively so no import is needed. */
    private static final String TWIN_CLASS = "com.cardemo.model.entity.Transaction";

    /**
     * A deliberately unusable stand-in for {@code DALYTRAN-CARD-NUM}, used only to prove that
     * {@code toString} withholds it.
     *
     * <p>Sixteen digits so that it satisfies {@code PIC X(16)}, but chosen to fail a Luhn check and to
     * match no issuer prefix, so it cannot be mistaken for a card number by a reader or by a credential
     * scanner. No card number from {@code app/data/ASCII/dailytran.txt} is ever written into this file:
     * the fixture-driven tests read that field through {@link FixtureLoader} and assert on its length
     * rather than its value.
     */
    private static final String SYNTHETIC_CARD_NUMBER = "9999888877776666";

    /** Source file of the entity under test, read to prove which packages it draws on. */
    private static final Path ENTITY_SOURCE =
            Path.of("src", "main", "java", "com", "cardemo", "model", "entity", "DailyTransaction.java");

    /**
     * The thirteen copybook field names in declaration order, each paired with the Java
     * property and the column it maps to. Held as CSV on the parameterised tests rather
     * than as a mutable static structure, so no test can perturb what another observes.
     */
    private static final String OFFSET_MAP = """
            DALYTRAN-ID,           transactionId,     dalytran_id,             1,  16
            DALYTRAN-TYPE-CD,      typeCode,          dalytran_type_cd,       17,   2
            DALYTRAN-CAT-CD,       categoryCode,      dalytran_cat_cd,        19,   4
            DALYTRAN-SOURCE,       transactionSource, dalytran_source,        23,  10
            DALYTRAN-DESC,         description,       dalytran_desc,          33, 100
            DALYTRAN-AMT,          amount,            dalytran_amt,          133,  11
            DALYTRAN-MERCHANT-ID,  merchantId,        dalytran_merchant_id,  144,   9
            DALYTRAN-MERCHANT-NAME,merchantName,      dalytran_merchant_name,153,  50
            DALYTRAN-MERCHANT-CITY,merchantCity,      dalytran_merchant_city,203,  50
            DALYTRAN-MERCHANT-ZIP, merchantZip,       dalytran_merchant_zip, 253,  10
            DALYTRAN-CARD-NUM,     cardNumber,        dalytran_card_num,     263,  16
            DALYTRAN-ORIG-TS,      origTs,            dalytran_orig_ts,      279,  26
            DALYTRAN-PROC-TS,      procTs,            dalytran_proc_ts,      305,  26
            """;

    /**
     * Builds one staging row from the fixture record at the supplied index, slicing every
     * field at its PIC-derived offset.
     *
     * <p>The ingestion ordinal is the one-based row position, which is what the loader
     * assigns and what {@code WS-TRANSACTION-COUNT} at {@code app/cbl/CBTRN02C.cbl:L185}
     * counts. Nothing is trimmed, padded or re-signed on the way in.
     *
     * @param data       the loaded fixture snapshot
     * @param recordIndex the zero-based record index
     * @return the staging row that record describes
     */
    private static DailyTransaction rowFrom(final FixtureLoader.FixtureData data, final int recordIndex) {
        return new DailyTransaction(
                (long) (recordIndex + 1),
                data.field(recordIndex, 1, CARD_NUMBER_WIDTH),
                data.field(recordIndex, TYPE_CODE_COLUMN, 2),
                Integer.valueOf(data.field(recordIndex, 19, 4)),
                data.field(recordIndex, SOURCE_COLUMN, 10),
                data.field(recordIndex, 33, 100),
                data.signedDecimal(recordIndex, AMOUNT_COLUMN, FixtureLoader.AMOUNT_FIELD_WIDTH),
                Long.valueOf(data.field(recordIndex, 144, 9)),
                data.field(recordIndex, 153, 50),
                data.field(recordIndex, 203, 50),
                data.field(recordIndex, 253, 10),
                data.field(recordIndex, CARD_NUMBER_COLUMN, CARD_NUMBER_WIDTH),
                data.field(recordIndex, ORIG_TS_COLUMN, TIMESTAMP_WIDTH),
                data.field(recordIndex, PROC_TS_COLUMN, TIMESTAMP_WIDTH));
    }

    /**
     * Builds a staging row whose every field is valid, so that a test can vary exactly one
     * of them.
     *
     * <p>Each value is padded to its PIC width, because that is what a fixed-width read
     * yields. A fresh instance is returned on every call: no shared mutable fixture exists
     * in this class.
     *
     * @return a valid staging row carrying no data from any real cardholder
     */
    private static DailyTransaction validRow() {
        return new DailyTransaction(
                1L,
                "0000000000000001",
                "01",
                Integer.valueOf(1),
                "POS TERM  ",
                "Purchase".repeat(1) + " ".repeat(92),
                new BigDecimal("504.77"),
                Long.valueOf(800_000_000L),
                "Test Merchant" + " ".repeat(37),
                "Test City" + " ".repeat(41),
                "72112     ",
                "0000000000000000",
                FixedClockProvider.CANONICAL_ONLINE_TIMESTAMP,
                " ".repeat(TIMESTAMP_WIDTH));
    }

    /**
     * Returns the one-based start column of a copybook field, accumulated from the widths
     * of everything declared before it.
     *
     * <p>This is the only place an offset is computed in this class, and it derives every
     * value from the frozen copybook rather than from a transcribed number. That matters
     * for a field whose neighbours are blank: shifting
     * {@code DALYTRAN-PROC-TS} one byte into the trailing {@code FILLER} still yields
     * twenty-six spaces, so a blankness assertion alone cannot pin the boundary and the
     * offset has to be proven against the source instead.
     *
     * @param copybook   the parsed layout
     * @param cobolField the field whose start column is wanted
     * @return the one-based column at which the field begins
     */
    private static int startColumnOf(final RecordLayoutCopybook copybook, final String cobolField) {
        int cumulative = 1;
        for (final String name : copybook.fieldNames()) {
            if (name.equals(cobolField)) {
                return cumulative;
            }
            cumulative += copybook.widthOf(name);
        }
        throw new AssertionError(copybook.member() + " declares no field named " + cobolField);
    }

    /**
     * Returns the declared field of the entity under test carrying the supplied name.
     *
     * @param property the Java property name
     * @return the reflected field, never {@code null}
     */
    private static Field fieldNamed(final String property) {
        try {
            return DailyTransaction.class.getDeclaredField(property);
        } catch (final NoSuchFieldException absent) {
            throw new AssertionError("DailyTransaction declares no field named " + property
                    + "; the copybook app/cpy/CVTRA06Y.cpy requires it", absent);
        }
    }

    /**
     * Returns the simple names of every annotation present on the supplied field.
     *
     * <p>Comparing simple names keeps this file from importing an annotation purely to
     * assert that it is absent, which {@code -Xlint:all -Werror} would reject as an unused
     * import the moment the assertion were rewritten.
     *
     * @param field the field to inspect
     * @return the annotation simple names
     */
    private static Set<String> annotationNamesOn(final Field field) {
        return Arrays.stream(field.getAnnotations())
                .map(annotation -> annotation.annotationType().getSimpleName())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /**
     * Returns every mapped column name the entity declares, in field declaration order.
     *
     * @return the column names taken from each {@link Column} annotation
     */
    private static List<String> mappedColumnNames() {
        final List<String> columns = new ArrayList<>();
        for (final Field field : DailyTransaction.class.getDeclaredFields()) {
            final Column column = field.getAnnotation(Column.class);
            if (column != null) {
                columns.add(column.name());
            }
        }
        return List.copyOf(columns);
    }

    /**
     * Reads the import statements the entity's source file declares.
     *
     * @return the imported type names, without the {@code import} keyword or semicolon
     */
    private static List<String> entityImports() {
        final String source;
        try {
            source = Files.readString(ENTITY_SOURCE, StandardCharsets.UTF_8);
        } catch (final IOException unreadable) {
            throw new AssertionError("cannot read " + ENTITY_SOURCE
                    + "; run from the project root so the source tree is visible", unreadable);
        }
        return source.lines()
                .map(String::strip)
                .filter(line -> line.startsWith("import "))
                .map(line -> line.substring("import ".length()).replace(";", "").strip())
                .toList();
    }

    @Nested
    @DisplayName("the 350-byte record layout, proven against the frozen copybook")
    class RecordLayoutContract {

        @Test
        @DisplayName("the copybook's fourteen leaf widths sum to exactly 350 bytes")
        void theLeafWidthsSumTo350() {
            final RecordLayoutCopybook copybook = RecordLayoutCopybook.of(COPYBOOK_MEMBER);

            assertThat(copybook.member()).isEqualTo(COPYBOOK_MEMBER);
            assertThat(copybook.recordLength())
                    .as("app/cpy/CVTRA06Y.cpy:L2 declares RECLN = 350, and the leaf widths must account "
                            + "for every one of those bytes including the trailing FILLER")
                    .isEqualTo(RECORD_LENGTH);
        }

        @Test
        @DisplayName("the copybook names exactly thirteen fields, all with the DALYTRAN- prefix")
        void theCopybookNamesThirteenPrefixedFields() {
            final RecordLayoutCopybook copybook = RecordLayoutCopybook.of(COPYBOOK_MEMBER);

            assertThat(copybook.fieldNames())
                    .hasSize(MAPPED_FIELD_COUNT)
                    .allSatisfy(name -> assertThat(name).startsWith("DALYTRAN-"));
        }

        @Test
        @DisplayName("the trailing FILLER X(20) is counted toward 350 but is not a named field")
        void theTrailingFillerIsCountedButNotNamed() {
            final RecordLayoutCopybook copybook = RecordLayoutCopybook.of(COPYBOOK_MEMBER);

            assertThat(copybook.declares("FILLER"))
                    .as("FILLER is unnamed slack: it carries no data, so it is mapped by no property")
                    .isFalse();

            final int namedWidth = copybook.fieldNames().stream().mapToInt(copybook::widthOf).sum();
            assertThat(RECORD_LENGTH - namedWidth)
                    .as("bytes 331-350 are the FILLER the entity deliberately does not model")
                    .isEqualTo(FILLER_WIDTH);
        }

        @ParameterizedTest(name = "{0} occupies {4} bytes from column {3}")
        @CsvSource(textBlock = OFFSET_MAP)
        @DisplayName("each copybook field declares the width the offset map documents")
        void eachFieldDeclaresItsDocumentedWidth(final String cobolField,
                                                final String property,
                                                final String column,
                                                final int startColumn,
                                                final int width) {
            final RecordLayoutCopybook copybook = RecordLayoutCopybook.of(COPYBOOK_MEMBER);

            assertThat(copybook.declares(cobolField)).isTrue();
            assertThat(copybook.widthOf(cobolField))
                    .as("%s at column %d maps to property %s and column %s", cobolField, startColumn, property,
                            column)
                    .isEqualTo(width);
        }

        @ParameterizedTest(name = "{0} starts at one-based column {3}")
        @CsvSource(textBlock = OFFSET_MAP)
        @DisplayName("the copybook widths accumulate to the documented one-based start column of each field")
        void theWidthsAccumulateToEachDocumentedStartColumn(final String cobolField,
                                                           final String property,
                                                           final String column,
                                                           final int startColumn,
                                                           final int width) {
            final RecordLayoutCopybook copybook = RecordLayoutCopybook.of(COPYBOOK_MEMBER);
            final List<String> order = copybook.fieldNames();

            int cumulative = 1;
            for (final String name : order) {
                if (name.equals(cobolField)) {
                    break;
                }
                cumulative += copybook.widthOf(name);
            }

            assertThat(cumulative)
                    .as("%s (property %s, column %s) must begin at column %d once the preceding widths are "
                            + "accumulated, which is what a fixed-width slice depends on", cobolField, property,
                            column, startColumn)
                    .isEqualTo(startColumn);
            assertThat(cumulative + width - 1).isLessThanOrEqualTo(RECORD_LENGTH);
        }

        @Test
        @DisplayName("DALYTRAN-AMT is the only signed field, and it is S9(09)V99 with scale two")
        void theAmountIsTheOnlySignedField() {
            final RecordLayoutCopybook copybook = RecordLayoutCopybook.of(COPYBOOK_MEMBER);

            final List<String> signed = copybook.fieldNames().stream()
                    .filter(name -> copybook.geometry(name).signed())
                    .toList();
            assertThat(signed).containsExactly("DALYTRAN-AMT");

            final RecordLayoutCopybook.Geometry amount = copybook.geometry("DALYTRAN-AMT");
            assertThat(amount.picture()).isEqualTo("S9(09)V99");
            assertThat(amount.digits()).isEqualTo(AMOUNT_PRECISION - AMOUNT_SCALE);
            assertThat(amount.scale()).isEqualTo(AMOUNT_SCALE);
            assertThat(amount.width()).isEqualTo(FixtureLoader.AMOUNT_FIELD_WIDTH);
            assertThat(amount.numeric()).isTrue();
        }

        @Test
        @DisplayName("every offset constant this class relies on is the copybook's own accumulated offset")
        void everyOffsetConstantMatchesTheCopybook() {
            final RecordLayoutCopybook copybook = RecordLayoutCopybook.of(COPYBOOK_MEMBER);

            assertThat(startColumnOf(copybook, "DALYTRAN-TYPE-CD")).isEqualTo(TYPE_CODE_COLUMN);
            assertThat(startColumnOf(copybook, "DALYTRAN-SOURCE")).isEqualTo(SOURCE_COLUMN);
            assertThat(startColumnOf(copybook, "DALYTRAN-AMT")).isEqualTo(AMOUNT_COLUMN);
            assertThat(startColumnOf(copybook, "DALYTRAN-CARD-NUM")).isEqualTo(CARD_NUMBER_COLUMN);
            assertThat(startColumnOf(copybook, "DALYTRAN-ORIG-TS")).isEqualTo(ORIG_TS_COLUMN);
            assertThat(startColumnOf(copybook, "DALYTRAN-PROC-TS"))
                    .as("this offset cannot be proven by inspecting the bytes, because the field and the "
                            + "FILLER that follows it are both blank on every row; the copybook is the "
                            + "only oracle that pins it")
                    .isEqualTo(PROC_TS_COLUMN);
            assertThat(copybook.widthOf("DALYTRAN-PROC-TS")).isEqualTo(TIMESTAMP_WIDTH);
            assertThat(copybook.widthOf("DALYTRAN-ORIG-TS")).isEqualTo(TIMESTAMP_WIDTH);
            assertThat(copybook.widthOf("DALYTRAN-CARD-NUM")).isEqualTo(CARD_NUMBER_WIDTH);
            assertThat(copybook.widthOf("DALYTRAN-ID")).isEqualTo(CARD_NUMBER_WIDTH);
            assertThat(PROC_TS_COLUMN + TIMESTAMP_WIDTH - 1)
                    .as("the record's last data byte is 330, after which only the FILLER remains")
                    .isEqualTo(RECORD_LENGTH - FILLER_WIDTH);
        }

        @Test
        @DisplayName("the two timestamp fields are alphanumeric X(26), never a temporal picture")
        void bothTimestampFieldsAreAlphanumeric() {
            final RecordLayoutCopybook copybook = RecordLayoutCopybook.of(COPYBOOK_MEMBER);

            for (final String field : List.of("DALYTRAN-ORIG-TS", "DALYTRAN-PROC-TS")) {
                final RecordLayoutCopybook.Geometry geometry = copybook.geometry(field);
                assertThat(geometry.picture()).as("%s picture", field).isEqualTo("X(26)");
                assertThat(geometry.width()).isEqualTo(TIMESTAMP_WIDTH);
                assertThat(geometry.numeric())
                        .as("%s is text: bytes 305-330 are blank on every fixture row, which no temporal "
                                + "type can hold", field)
                        .isFalse();
            }
        }
    }

    @Nested
    @DisplayName("the schema mapping: thirteen copybook columns plus one synthetic staging key")
    class SchemaMapping {

        @Test
        @DisplayName("the class is an @Entity mapped to table daily_transaction")
        void theClassIsMappedToTheStagingTable() {
            assertThat(DailyTransaction.class.getAnnotation(Entity.class)).isNotNull();

            final Table table = DailyTransaction.class.getAnnotation(Table.class);
            assertThat(table).isNotNull();
            assertThat(table.name()).isEqualTo("daily_transaction");
            assertThat(table.uniqueConstraints())
                    .as("a unique constraint over dalytran_id would reject staged input the unkeyed "
                            + "sequential dataset may legitimately repeat")
                    .isEmpty();
            assertThat(table.indexes()).isEmpty();
        }

        @Test
        @DisplayName("exactly thirteen columns carry the dalytran_ prefix, in copybook order")
        void thirteenColumnsCarryTheCopybookPrefix() {
            final List<String> copybookColumns = mappedColumnNames().stream()
                    .filter(name -> name.startsWith(COLUMN_PREFIX))
                    .toList();

            assertThat(copybookColumns)
                    .hasSize(MAPPED_FIELD_COUNT)
                    .containsExactly("dalytran_id", "dalytran_type_cd", "dalytran_cat_cd", "dalytran_source",
                            "dalytran_desc", "dalytran_amt", "dalytran_merchant_id", "dalytran_merchant_name",
                            "dalytran_merchant_city", "dalytran_merchant_zip", "dalytran_card_num",
                            "dalytran_orig_ts", "dalytran_proc_ts");
        }

        @Test
        @DisplayName("the one column without the prefix is the synthetic key, and it is the @Id")
        void theOnlyUnprefixedColumnIsTheSyntheticKey() {
            final List<String> unprefixed = mappedColumnNames().stream()
                    .filter(name -> !name.startsWith(COLUMN_PREFIX))
                    .toList();

            assertThat(unprefixed)
                    .as("the naming convention separates the thirteen record columns from the one column "
                            + "that has no copybook line")
                    .containsExactly(SYNTHETIC_KEY_COLUMN);

            final List<Field> identifiers = Arrays.stream(DailyTransaction.class.getDeclaredFields())
                    .filter(field -> field.isAnnotationPresent(Id.class))
                    .toList();
            assertThat(identifiers).hasSize(1);
            assertThat(identifiers.getFirst().getAnnotation(Column.class).name())
                    .isEqualTo(SYNTHETIC_KEY_COLUMN);
        }

        @Test
        @DisplayName("the identifier carries no @GeneratedValue: the loader assigns the ordinal, not the database")
        void theIdentifierIsNotDatabaseGenerated() {
            final Field identifier = fieldNamed("ingestSequence");

            assertThat(annotationNamesOn(identifier))
                    .as("re-staging the same file must yield the same ordinals, which a database allocator "
                            + "cannot promise because it keeps counting across a truncate-and-reload")
                    .doesNotContain("GeneratedValue", "SequenceGenerator", "TableGenerator");
        }

        @Test
        @DisplayName("DALYTRAN-ID is an ordinary non-unique data column, deliberately not the key")
        void theTransactionIdIsNotTheKey() {
            final Field transactionId = fieldNamed("transactionId");

            assertThat(transactionId.isAnnotationPresent(Id.class))
                    .as("the staged input may legitimately repeat DALYTRAN-ID, so keying on it would "
                            + "reject valid input")
                    .isFalse();
            assertThat(transactionId.getAnnotation(Column.class).unique()).isFalse();
        }

        @ParameterizedTest(name = "{2} is NOT NULL")
        @CsvSource(textBlock = OFFSET_MAP)
        @DisplayName("every copybook column is NOT NULL, because a fixed-width read always yields a value")
        void everyCopybookColumnIsNotNull(final String cobolField,
                                         final String property,
                                         final String column,
                                         final int startColumn,
                                         final int width) {
            final Column mapping = fieldNamed(property).getAnnotation(Column.class);

            assertThat(mapping).as("%s must be mapped", property).isNotNull();
            assertThat(mapping.name()).isEqualTo(column);
            assertThat(mapping.nullable())
                    .as("%s (%s at column %d) is NOT NULL", column, cobolField, startColumn)
                    .isFalse();
            assertThat(width).isPositive();
        }

        @Test
        @DisplayName("the synthetic key column is NOT NULL too")
        void theSyntheticKeyColumnIsNotNull() {
            assertThat(fieldNamed("ingestSequence").getAnnotation(Column.class).nullable()).isFalse();
        }

        @ParameterizedTest(name = "{0} declares length {1}")
        @CsvSource({
            "transactionId,  16", "typeCode,        2", "transactionSource, 10", "description,   100",
            "merchantName,   50", "merchantCity,   50", "merchantZip,       10", "cardNumber,     16",
            "origTs,         26", "procTs,         26"})
        @DisplayName("each character column declares the width its PIC clause states")
        void eachCharacterColumnDeclaresItsPictureWidth(final String property, final int width) {
            final Column mapping = fieldNamed(property).getAnnotation(Column.class);

            assertThat(mapping.length()).isEqualTo(width);
            assertThat(mapping.columnDefinition()).isEqualTo("CHAR(" + width + ")");
        }

        @Test
        @DisplayName("both timestamp columns are CHAR(26), never TIMESTAMP")
        void bothTimestampColumnsAreFixedCharacter() {
            for (final String property : List.of("origTs", "procTs")) {
                final Column mapping = fieldNamed(property).getAnnotation(Column.class);
                assertThat(mapping.columnDefinition())
                        .as("%s holds 26 blanks on every fixture row; a temporal type cannot represent "
                                + "that and the seed load would fail outright", property)
                        .isEqualTo("CHAR(" + TIMESTAMP_WIDTH + ")");
                assertThat(fieldNamed(property).getType()).isEqualTo(String.class);
            }
        }

        @Test
        @DisplayName("the FILLER at bytes 331-350 is modelled by no field and no column")
        void theFillerIsNotModelled() {
            final Set<String> properties = Arrays.stream(DailyTransaction.class.getDeclaredFields())
                    .map(Field::getName)
                    .collect(Collectors.toCollection(TreeSet::new));

            assertThat(properties).doesNotContain("filler", "FILLER", "reserved", "slack");
            assertThat(mappedColumnNames()).doesNotContain("dalytran_filler", "filler");
        }

        @Test
        @DisplayName("no field carries @Version: this staging table is loaded and consumed within one job")
        void noFieldCarriesAVersionColumn() {
            for (final Field field : DailyTransaction.class.getDeclaredFields()) {
                assertThat(annotationNamesOn(field))
                        .as("adding an optimistic lock to %s would fabricate a column the four versioned "
                                + "entities own and this one must not", field.getName())
                        .doesNotContain("Version");
            }
            assertThat(Arrays.stream(DailyTransaction.class.getMethods()).map(Method::getName))
                    .doesNotContain("getVersion", "setVersion");
        }

        @Test
        @DisplayName("no field declares a foreign key or a JPA association")
        void noFieldDeclaresAnAssociation() {
            for (final Field field : DailyTransaction.class.getDeclaredFields()) {
                assertThat(annotationNamesOn(field))
                        .as("referential judgement on %s belongs to the posting processor's reject codes "
                                + "100 and 101, which are business outcomes driving an exit status, and "
                                + "never to a database constraint on an untrusted staging table",
                                field.getName())
                        .doesNotContain("ManyToOne", "OneToMany", "OneToOne", "ManyToMany", "JoinColumn",
                                "JoinColumns", "JoinTable", "ForeignKey", "MapsId", "EmbeddedId", "Embedded");
            }
        }

        @Test
        @DisplayName("every mapped field is a plain scalar: no entity type leaks into the staging row")
        void everyMappedFieldIsAScalar() {
            for (final Field field : DailyTransaction.class.getDeclaredFields()) {
                if (!field.isAnnotationPresent(Column.class)) {
                    continue;
                }
                assertThat(field.getType())
                        .as("%s must be a scalar, because an association would impose the referential "
                                + "constraint this table exists to avoid", field.getName())
                        .isIn(String.class, Integer.class, Long.class, BigDecimal.class);
            }
        }
    }

    @Nested
    @DisplayName("the amount: NUMERIC(11,2) from PIC S9(09)V99, signed and never normalised")
    class AmountPrecision {

        @Test
        @DisplayName("the column declares precision 11 and scale 2, not the account tier's 12")
        void theColumnDeclaresElevenTwo() {
            final Column mapping = fieldNamed("amount").getAnnotation(Column.class);

            assertThat(mapping.precision())
                    .as("S9(09)V99 is eleven characters: nine integer digits plus two decimals. "
                            + "NUMERIC(12,2) belongs to Account's S9(10)V99 money fields and NUMERIC(6,2) "
                            + "to DisclosureGroup's S9(04)V99 rate; the three tiers must never collapse")
                    .isEqualTo(AMOUNT_PRECISION);
            assertThat(mapping.scale()).isEqualTo(AMOUNT_SCALE);
            assertThat(mapping.columnDefinition()).isEqualTo("NUMERIC(11,2)");
        }

        @Test
        @DisplayName("the amount is a BigDecimal, and no float or double appears anywhere on the entity")
        void noBinaryFloatingPointAppearsAnywhere() {
            assertThat(fieldNamed("amount").getType()).isEqualTo(BigDecimal.class);

            final Set<Class<?>> forbidden = Set.of(float.class, double.class, Float.class, Double.class);
            for (final Field field : DailyTransaction.class.getDeclaredFields()) {
                assertThat(field.getType())
                        .as("field %s must not be binary floating point: it cannot represent a decimal "
                                + "money value exactly", field.getName())
                        .isNotIn(forbidden);
            }
            for (final Method method : DailyTransaction.class.getDeclaredMethods()) {
                assertThat(method.getReturnType())
                        .as("method %s must not return binary floating point", method.getName())
                        .isNotIn(forbidden);
                assertThat(List.of(method.getParameterTypes()))
                        .as("method %s must not accept binary floating point", method.getName())
                        .doesNotContainAnyElementsOf(forbidden);
            }
        }

        @Test
        @DisplayName("amounts compare by compareTo, which ignores scale, and not by equals, which does not")
        void amountsCompareByCompareToRatherThanEquals() {
            final BigDecimal stored = validRow().getAmount();
            final BigDecimal sameValueWiderScale = new BigDecimal("504.7700");

            assertThat(stored.compareTo(sameValueWiderScale))
                    .as("compareTo is the numeric comparison and is the one the migration mandates")
                    .isZero();
            assertThat(stored.equals(sameValueWiderScale))
                    .as("BigDecimal.equals also compares scale, so it reports two equal amounts as "
                            + "different; it must never be used for a money comparison")
                    .isFalse();
        }

        @Test
        @DisplayName("a negative amount is stored negative: no absolute value is taken")
        void aNegativeAmountKeepsItsSign() {
            final BigDecimal debit = new BigDecimal("-919.00");
            final DailyTransaction row = new DailyTransaction(1L, "0000000001774260", "03",
                    Integer.valueOf(1), "OPERATOR  ", " ".repeat(100), debit, Long.valueOf(800_000_000L),
                    " ".repeat(50), " ".repeat(50), " ".repeat(10), "0000000000000000",
                    FixedClockProvider.CANONICAL_ONLINE_TIMESTAMP, " ".repeat(TIMESTAMP_WIDTH));

            assertThat(row.getAmount()).isNegative();
            assertThat(row.getAmount().compareTo(debit)).isZero();
            assertThat(row.getAmount().signum())
                    .as("app/cbl/CBTRN02C.cbl:L547-L552 routes a negative amount to the cycle debit "
                            + "accumulator, so that accumulator legitimately holds negative values and the "
                            + "over-limit formula subtracts it; normalising the sign corrupts the arithmetic")
                    .isEqualTo(-1);
        }

        @Test
        @DisplayName("the amount is stored at the scale supplied, unrescaled and unrounded")
        void theAmountIsStoredWithoutRescaling() {
            final BigDecimal wholeUnits = new BigDecimal("7");

            final BigDecimal stored = new DailyTransaction(1L, "0000000000000001", "01",
                    Integer.valueOf(1), "POS TERM  ", " ".repeat(100), wholeUnits, Long.valueOf(0L),
                    " ".repeat(50), " ".repeat(50), " ".repeat(10), "0000000000000000",
                    FixedClockProvider.CANONICAL_ONLINE_TIMESTAMP, " ".repeat(TIMESTAMP_WIDTH)).getAmount();

            assertThat(stored.scale())
                    .as("a scale below two is accepted verbatim: the entity neither pads nor rounds")
                    .isEqualTo(wholeUnits.scale());
            assertThat(stored.compareTo(new BigDecimal("7.00"))).isZero();
        }

        @Test
        @DisplayName("an amount carrying more than two decimals is refused rather than silently rounded")
        void anOverScaledAmountIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new DailyTransaction(1L, "0000000000000001", "01",
                            Integer.valueOf(1), "POS TERM  ", " ".repeat(100), new BigDecimal("1.005"),
                            Long.valueOf(0L), " ".repeat(50), " ".repeat(50), " ".repeat(10),
                            "0000000000000000", FixedClockProvider.CANONICAL_ONLINE_TIMESTAMP,
                            " ".repeat(TIMESTAMP_WIDTH)))
                    .withMessageContaining("DALYTRAN-AMT PIC S9(09)V99")
                    .withMessageContaining("at most " + AMOUNT_SCALE + " decimal digits")
                    .withMessageContaining(RoundingMode.HALF_EVEN.name())
                    .withNoCause();
        }

        @ParameterizedTest(name = "the representable bound {0} is accepted")
        @ValueSource(strings = {"999999999.99", "-999999999.99", "0.00", "-0.01", "0.01"})
        @DisplayName("every value the eleven zoned-decimal bytes can hold is accepted, sign included")
        void everyRepresentableAmountIsAccepted(final String amount) {
            final BigDecimal candidate = new BigDecimal(amount);

            assertThatNoException().isThrownBy(() -> new DailyTransaction(1L, "0000000000000001", "01",
                    Integer.valueOf(1), "POS TERM  ", " ".repeat(100), candidate, Long.valueOf(0L),
                    " ".repeat(50), " ".repeat(50), " ".repeat(10), "0000000000000000",
                    FixedClockProvider.CANONICAL_ONLINE_TIMESTAMP, " ".repeat(TIMESTAMP_WIDTH)));
        }

        @ParameterizedTest(name = "the out-of-range value {0} is refused")
        @ValueSource(strings = {"1000000000.00", "-1000000000.00"})
        @DisplayName("a magnitude the record cannot hold is refused, and the message states the bound")
        void anOutOfRangeAmountIsRefused(final String amount) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new DailyTransaction(1L, "0000000000000001", "01",
                            Integer.valueOf(1), "POS TERM  ", " ".repeat(100), new BigDecimal(amount),
                            Long.valueOf(0L), " ".repeat(50), " ".repeat(50), " ".repeat(10),
                            "0000000000000000", FixedClockProvider.CANONICAL_ONLINE_TIMESTAMP,
                            " ".repeat(TIMESTAMP_WIDTH)))
                    .withMessageContaining("999999999.99")
                    .withNoCause();
        }
    }

    @Nested
    @DisplayName("the 300-row fixture census, which the entity must carry unchanged")
    class FixtureCensus {

        @Test
        @DisplayName("the fixture is 300 records of 350 bytes, byte-for-byte as shipped")
        void theFixtureGeometryIsIntact() {
            final FixtureLoader.FixtureData data = FixtureLoader.load(FixtureLoader.Fixture.DAILY_TRANSACTION);

            assertThat(data.resourceName())
                    .as("the fixture spells 'daily' in full even though the DD name is DALYTRAN")
                    .isEqualTo("dailytran.txt");
            assertThat(data.recordCount()).isEqualTo(300);
            assertThat(data.recordWidth()).isEqualTo(RECORD_LENGTH);
            assertThat(data.byteCount()).isEqualTo(data.impliedByteCount()).isEqualTo(105_300);
            assertThat(data.records()).allSatisfy(record -> assertThat(record).hasSize(RECORD_LENGTH));
        }

        @Test
        @DisplayName("bytes 305-330 are twenty-six spaces on all 300 rows")
        void theProcessTimestampIsBlankOnEveryRow() {
            final FixtureLoader.FixtureData data = FixtureLoader.load(FixtureLoader.Fixture.DAILY_TRANSACTION);
            final Set<String> distinct = new LinkedHashSet<>();

            for (int index = 0; index < data.recordCount(); index++) {
                distinct.add(data.field(index, PROC_TS_COLUMN, TIMESTAMP_WIDTH));
            }

            assertThat(distinct)
                    .as("a single distinct value across the whole fixture, and it is blank; this is why "
                            + "the column must be CHAR(26) and never TIMESTAMP")
                    .containsExactly(" ".repeat(TIMESTAMP_WIDTH));
        }

        @Test
        @DisplayName("the blank field starts at byte 305, anchored on the origin timestamp's last non-blank byte")
        void theProcessTimestampBoundaryIsAnchoredOnItsNeighbour() {
            final FixtureLoader.FixtureData data = FixtureLoader.load(FixtureLoader.Fixture.DAILY_TRANSACTION);

            for (int index = 0; index < data.recordCount(); index++) {
                assertThat(data.field(index, PROC_TS_COLUMN - 1, 1))
                        .as("byte 304 is the last of the origin timestamp's six literal zeros, so it is "
                                + "not blank; that is what fixes the left boundary of the blank run at "
                                + "byte 305 rather than one byte either side (row %d)", index)
                        .isNotBlank()
                        .isEqualTo("0");
            }

            assertThat(data.field(0, PROC_TS_COLUMN, TIMESTAMP_WIDTH + FILLER_WIDTH))
                    .as("bytes 305-350 are one unbroken 46-character blank run: the process timestamp "
                            + "followed by the FILLER, which is why blankness alone cannot locate the "
                            + "field and the copybook must supply the offset")
                    .isBlank()
                    .hasSize(TIMESTAMP_WIDTH + FILLER_WIDTH);
        }

        @Test
        @DisplayName("a blank process timestamp round-trips unchanged: not trimmed, not emptied, not nulled")
        void aBlankProcessTimestampRoundTripsUnchanged() {
            final FixtureLoader.FixtureData data = FixtureLoader.load(FixtureLoader.Fixture.DAILY_TRANSACTION);
            final DailyTransaction row = rowFrom(data, 0);

            assertThat(row.getProcTs())
                    .isNotNull()
                    .isNotEmpty()
                    .hasSize(TIMESTAMP_WIDTH)
                    .isEqualTo(" ".repeat(TIMESTAMP_WIDTH))
                    .isBlank();
            assertThat(row.getProcTs().strip())
                    .as("blank is the normal state of this column on a staging row, so a caller must not "
                            + "read it as missing; stripping is what a caller must never persist")
                    .isEmpty();
        }

        @Test
        @DisplayName("bytes 279-304 hold the one canonical online timestamp the fixed clock renders")
        void theOriginTimestampMatchesTheFixedClock() {
            final FixtureLoader.FixtureData data = FixtureLoader.load(FixtureLoader.Fixture.DAILY_TRANSACTION);
            final Set<String> distinct = new LinkedHashSet<>();

            for (int index = 0; index < data.recordCount(); index++) {
                distinct.add(data.field(index, ORIG_TS_COLUMN, TIMESTAMP_WIDTH));
            }

            final Clock clock = FixedClockProvider.canonicalClock();
            assertThat(distinct)
                    .as("one distinct value on all 300 rows, and it is exactly what the fixed clock "
                            + "renders in the online layout; no call to now() can reproduce this")
                    .containsExactly(FixedClockProvider.onlineTimestamp(clock))
                    .containsExactly(FixedClockProvider.CANONICAL_ONLINE_TIMESTAMP);
        }

        @Test
        @DisplayName("the origin timestamp is the online layout: byte 11 a space, byte 20 a dot, bytes 21-26 zeros")
        void theOriginTimestampIsTheOnlineLayout() {
            final FixtureLoader.FixtureData data = FixtureLoader.load(FixtureLoader.Fixture.DAILY_TRANSACTION);
            final String origin = data.field(0, ORIG_TS_COLUMN, TIMESTAMP_WIDTH);

            assertThat(origin).hasSize(FixedClockProvider.TIMESTAMP_LENGTH);
            assertThat(origin.charAt(10))
                    .as("app/cpy/CSDAT01Y.cpy:L48 declares position 11 as FILLER PIC X(01) VALUE ' ', and "
                            + "INITIALIZE WS-TIMESTAMP at app/cbl/COBIL00C.cbl:L263 leaves it blank; the "
                            + "batch layout would put a dash here instead")
                    .isEqualTo(' ');
            assertThat(origin.charAt(19))
                    .as("app/cpy/CSDAT01Y.cpy:L54 declares position 20 as FILLER PIC X(01) VALUE '.'")
                    .isEqualTo('.');
            assertThat(origin.substring(20))
                    .as("MOVE ZEROS TO WS-TIMESTAMP-TM-MS6 at app/cbl/COBIL00C.cbl:L266 writes six "
                            + "literal zeros, so sub-second precision is discarded rather than rounded")
                    .isEqualTo("000000");
            assertThat(origin).isNotEqualTo(FixedClockProvider.CANONICAL_BATCH_TIMESTAMP);
        }

        @Test
        @DisplayName("the amount census is 250 positive and 50 negative, with no zero amount")
        void theAmountCensusIs250PositiveAnd50Negative() {
            final FixtureLoader.FixtureData data = FixtureLoader.load(FixtureLoader.Fixture.DAILY_TRANSACTION);
            int positive = 0;
            int negative = 0;
            int zero = 0;

            for (int index = 0; index < data.recordCount(); index++) {
                final int signum =
                        data.signedDecimal(index, AMOUNT_COLUMN, FixtureLoader.AMOUNT_FIELD_WIDTH).signum();
                if (signum > 0) {
                    positive++;
                } else if (signum < 0) {
                    negative++;
                } else {
                    zero++;
                }
            }

            assertThat(positive).isEqualTo(250);
            assertThat(negative)
                    .as("50 genuinely negative rows are what make this fixture exercise the cycle-debit "
                            + "branch of the posting logic rather than only the credit branch")
                    .isEqualTo(50);
            assertThat(zero).isZero();
        }

        @Test
        @DisplayName("both overpunch sign families are exercised, and both extremes decode exactly")
        void bothSignFamiliesAndBothExtremesDecode() {
            final FixtureLoader.FixtureData data = FixtureLoader.load(FixtureLoader.Fixture.DAILY_TRANSACTION);
            final Set<Character> signCharacters = new TreeSet<>();
            BigDecimal lowest = null;
            BigDecimal highest = null;

            for (int index = 0; index < data.recordCount(); index++) {
                final String raw = data.field(index, AMOUNT_COLUMN, FixtureLoader.AMOUNT_FIELD_WIDTH);
                signCharacters.add(raw.charAt(raw.length() - 1));
                final BigDecimal value =
                        data.signedDecimal(index, AMOUNT_COLUMN, FixtureLoader.AMOUNT_FIELD_WIDTH);
                lowest = lowest == null || value.compareTo(lowest) < 0 ? value : lowest;
                highest = highest == null || value.compareTo(highest) > 0 ? value : highest;
            }

            assertThat(signCharacters)
                    .as("all twenty overpunch codes appear, so the decode table is exercised in full")
                    .hasSize(20)
                    .contains('{', '}', 'A', 'I', 'J', 'R');
            assertThat(lowest).isEqualByComparingTo(new BigDecimal("-998.33"));
            assertThat(highest).isEqualByComparingTo(new BigDecimal("999.77"));
        }

        @Test
        @DisplayName("the fixture partitions perfectly into two cells: 01/POS TERM credits and 03/OPERATOR debits")
        void theFixturePartitionsIntoExactlyTwoCells() {
            final FixtureLoader.FixtureData data = FixtureLoader.load(FixtureLoader.Fixture.DAILY_TRANSACTION);
            final Set<String> cells = new TreeSet<>();
            int credits = 0;
            int debits = 0;

            for (int index = 0; index < data.recordCount(); index++) {
                final DailyTransaction row = rowFrom(data, index);
                final boolean negative = row.getAmount().signum() < 0;
                cells.add(row.getTypeCode() + "|" + row.getTransactionSource() + "|" + negative);
                if (negative) {
                    debits++;
                } else {
                    credits++;
                }
            }

            assertThat(cells)
                    .as("exactly two combinations occur across 300 rows, which is what makes the census "
                            + "reproducible rather than incidental")
                    .containsExactly("01|POS TERM  |false", "03|OPERATOR  |true");
            assertThat(credits).isEqualTo(250);
            assertThat(debits).isEqualTo(50);
        }

        @Test
        @DisplayName("the category code and merchant identifier are invariant across all 300 rows")
        void theCategoryCodeAndMerchantIdentifierAreInvariant() {
            final FixtureLoader.FixtureData data = FixtureLoader.load(FixtureLoader.Fixture.DAILY_TRANSACTION);
            final Set<Integer> categories = new TreeSet<>();
            final Set<Long> merchants = new TreeSet<>();

            for (int index = 0; index < data.recordCount(); index++) {
                final DailyTransaction row = rowFrom(data, index);
                categories.add(row.getCategoryCode());
                merchants.add(row.getMerchantId());
            }

            assertThat(categories).containsExactly(Integer.valueOf(1));
            assertThat(merchants).containsExactly(Long.valueOf(800_000_000L));
        }

        @Test
        @DisplayName("there are 300 distinct transaction identifiers over 50 distinct cards, with no orphan")
        void everyCardResolvesAgainstTheCrossReference() {
            final FixtureLoader.FixtureData staged =
                    FixtureLoader.load(FixtureLoader.Fixture.DAILY_TRANSACTION);
            final FixtureLoader.FixtureData crossReference =
                    FixtureLoader.load(FixtureLoader.Fixture.CARD_XREF);

            final Set<String> identifiers = new LinkedHashSet<>();
            final Set<String> stagedCards = new LinkedHashSet<>();
            for (int index = 0; index < staged.recordCount(); index++) {
                identifiers.add(staged.field(index, 1, CARD_NUMBER_WIDTH));
                stagedCards.add(staged.field(index, CARD_NUMBER_COLUMN, CARD_NUMBER_WIDTH));
            }

            final Set<String> knownCards = new LinkedHashSet<>();
            for (int index = 0; index < crossReference.recordCount(); index++) {
                knownCards.add(crossReference.field(index, 1, CARD_NUMBER_WIDTH));
            }

            assertThat(identifiers).hasSize(300);
            assertThat(stagedCards).hasSize(50);
            assertThat(knownCards).hasSize(50);
            assertThat(stagedCards.stream().filter(card -> !knownCards.contains(card)).count())
                    .as("no orphan, so the fixture drives the accepted path of the posting job rather "
                            + "than only reject code 100; the count is reported without naming any card")
                    .isZero();
        }

        @Test
        @DisplayName("merchant postal codes mix five-digit and ZIP+4 forms, so no fixed-length rule may be imposed")
        void merchantPostalCodesMixBothForms() {
            final FixtureLoader.FixtureData data = FixtureLoader.load(FixtureLoader.Fixture.DAILY_TRANSACTION);
            int fiveDigit = 0;
            int plusFour = 0;

            for (int index = 0; index < data.recordCount(); index++) {
                final String zip = data.field(index, 253, 10);
                assertThat(zip).as("row %d postal code width", index).hasSize(10);
                if (zip.indexOf('-') >= 0) {
                    plusFour++;
                } else {
                    fiveDigit++;
                }
            }

            assertThat(fiveDigit).isPositive();
            assertThat(plusFour)
                    .as("a digits-only or fixed-length postal code constraint would reject the ZIP+4 rows")
                    .isPositive();
            assertThat(fiveDigit + plusFour).isEqualTo(300);
        }

        @Test
        @DisplayName("trailing spaces are load-bearing: the longest run is 46 characters and must survive")
        void trailingSpacesAreLoadBearing() {
            final FixtureLoader.FixtureData data = FixtureLoader.load(FixtureLoader.Fixture.DAILY_TRANSACTION);
            int longestRun = 0;

            for (int index = 0; index < data.recordCount(); index++) {
                final String record = data.recordAt(index);
                longestRun = Math.max(longestRun, record.length() - record.stripTrailing().length());
            }

            assertThat(longestRun)
                    .as("26 blank process-timestamp bytes plus the 20-byte FILLER: a whitespace cleanup "
                            + "that trimmed a record would destroy the fixed-width geometry outright")
                    .isEqualTo(TIMESTAMP_WIDTH + FILLER_WIDTH)
                    .isEqualTo(46);
        }

        @Test
        @DisplayName("the entity carries every field of a real fixture row through verbatim")
        void aFixtureRowIsCarriedThroughVerbatim() {
            final FixtureLoader.FixtureData data = FixtureLoader.load(FixtureLoader.Fixture.DAILY_TRANSACTION);
            final DailyTransaction row = rowFrom(data, 0);

            assertThat(row.getIngestSequence()).isEqualTo(1L);
            assertThat(row.getTransactionId()).isEqualTo(data.field(0, 1, CARD_NUMBER_WIDTH));
            assertThat(row.getTypeCode()).isEqualTo("01");
            assertThat(row.getCategoryCode()).isEqualTo(Integer.valueOf(1));
            assertThat(row.getTransactionSource()).isEqualTo("POS TERM  ");
            assertThat(row.getDescription()).isEqualTo(data.field(0, 33, 100)).hasSize(100);
            assertThat(row.getAmount()).isEqualByComparingTo(new BigDecimal("504.77"));
            assertThat(row.getMerchantId()).isEqualTo(Long.valueOf(800_000_000L));
            assertThat(row.getMerchantName()).isEqualTo(data.field(0, 153, 50)).hasSize(50);
            assertThat(row.getMerchantCity()).isEqualTo(data.field(0, 203, 50)).hasSize(50);
            assertThat(row.getMerchantZip()).isEqualTo(data.field(0, 253, 10)).hasSize(10);
            assertThat(row.getCardNumber()).hasSize(CARD_NUMBER_WIDTH);
            assertThat(row.getOrigTs()).isEqualTo(FixedClockProvider.CANONICAL_ONLINE_TIMESTAMP);
            assertThat(row.getProcTs()).isEqualTo(" ".repeat(TIMESTAMP_WIDTH));
        }

        @Test
        @DisplayName("every one of the 300 rows constructs, so no row violates the record layout")
        void everyFixtureRowConstructs() {
            final FixtureLoader.FixtureData data = FixtureLoader.load(FixtureLoader.Fixture.DAILY_TRANSACTION);

            for (int index = 0; index < data.recordCount(); index++) {
                final int rowIndex = index;
                assertThatNoException()
                        .as("row %d must satisfy the copybook layout", rowIndex)
                        .isThrownBy(() -> rowFrom(data, rowIndex));
            }
        }
    }

    @Nested
    @DisplayName("position-aware overpunch decoding, which a global substitution would break")
    class OverpunchDecoding {

        @ParameterizedTest(name = "the field {0} decodes to {1}")
        @CsvSource({
            "0000005047G, 504.77", "0000009190}, -919.00", "0000009983L, -998.33", "0000009997G, 999.77",
            "0000000000{,    0.00", "00000000000,    0.00"})
        @DisplayName("the decode table maps each sign character to its digit and its sign")
        void theDecodeTableMapsEachSignCharacter(final String field, final String expected) {
            assertThat(FixtureLoader.decodeZonedDecimal(field, AMOUNT_SCALE))
                    .isEqualByComparingTo(new BigDecimal(expected));
        }

        @Test
        @DisplayName("the letters A through R occur as ordinary text on every one of the 300 rows")
        void theOverpunchLettersOccurAsOrdinaryTextOnEveryRow() {
            final FixtureLoader.FixtureData data = FixtureLoader.load(FixtureLoader.Fixture.DAILY_TRANSACTION);
            int rowsCarryingLetters = 0;

            for (int index = 0; index < data.recordCount(); index++) {
                final String text = data.field(index, 33, 100)
                        + data.field(index, 153, 50)
                        + data.field(index, 203, 50);
                if (text.chars().anyMatch(character -> character >= 'A' && character <= 'R')) {
                    rowsCarryingLetters++;
                }
            }

            assertThat(rowsCarryingLetters)
                    .as("DALYTRAN-DESC, DALYTRAN-MERCHANT-NAME and DALYTRAN-MERCHANT-CITY carry these "
                            + "letters legitimately, so a blanket substitution of the overpunch table "
                            + "across a whole record would corrupt all three fields")
                    .isEqualTo(300);
        }

        @Test
        @DisplayName("decoding a text column instead of the amount column fails loudly, preserving the cause")
        void decodingATextColumnFailsLoudly() {
            final FixtureLoader.FixtureData data = FixtureLoader.load(FixtureLoader.Fixture.DAILY_TRANSACTION);

            assertThatExceptionOfType(IllegalStateException.class)
                    .as("the merchant name is not a zoned decimal, and reading it as one must fail "
                            + "rather than silently yield a number")
                    .isThrownBy(() -> data.signedDecimal(0, 153, FixtureLoader.AMOUNT_FIELD_WIDTH))
                    .withMessageContaining("does not hold a valid zoned decimal")
                    .havingCause()
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("an unrecognised sign character is refused, and the message names no field content")
        void anUnrecognisedSignCharacterIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> FixtureLoader.decodeZonedDecimal("0000005047*", AMOUNT_SCALE))
                    .withMessageContaining("is not a zoned-decimal")
                    .withNoCause();
        }

        @Test
        @DisplayName("a width off by one reads a neighbouring column as the sign, so the width is required")
        void anOffByOneWidthReadsTheWrongColumn() {
            final FixtureLoader.FixtureData data = FixtureLoader.load(FixtureLoader.Fixture.DAILY_TRANSACTION);

            final BigDecimal correct =
                    data.signedDecimal(0, AMOUNT_COLUMN, FixtureLoader.AMOUNT_FIELD_WIDTH);
            final BigDecimal truncated =
                    data.signedDecimal(0, AMOUNT_COLUMN, FixtureLoader.AMOUNT_FIELD_WIDTH - 1);

            assertThat(correct).isEqualByComparingTo(new BigDecimal("504.77"));
            assertThat(truncated)
                    .as("dropping the last byte moves the sign position onto a data digit, so the value "
                            + "changes silently; the width must always come from the PIC clause")
                    .isNotEqualByComparingTo(correct);
        }
    }

    @Nested
    @DisplayName("DALYTRAN-SOURCE is PIC X(10) free text, never a closed enum")
    class TransactionSourceIsFreeText {

        @Test
        @DisplayName("the property is a String, and no enum type appears on any mapped field")
        void theSourcePropertyIsAString() {
            final Field source = fieldNamed("transactionSource");

            assertThat(source.getType())
                    .as("an enum-typed column would reject OPERATOR, which occupies 50 of the 300 fixture "
                            + "rows yet appears in no program literal anywhere in the corpus")
                    .isEqualTo(String.class);
            assertThat(source.getType().isEnum()).isFalse();

            for (final Field field : DailyTransaction.class.getDeclaredFields()) {
                assertThat(field.getType().isEnum())
                        .as("field %s must not be an enum: a closed domain on a staging column breaks the "
                                + "seed load for any value the corpus does not happen to name",
                                field.getName())
                        .isFalse();
            }
        }

        @Test
        @DisplayName("the entity draws on nothing from com.cardemo.model.enums")
        void theEntityDrawsOnNoEnumPackage() {
            assertThat(entityImports())
                    .as("importing TransactionSource here would be the first step toward typing the column")
                    .noneMatch(imported -> imported.startsWith("com.cardemo.model.enums"));
        }

        @ParameterizedTest(name = "the source value \"{0}\" is accepted verbatim")
        @ValueSource(strings = {"POS TERM  ", "OPERATOR  ", "System    ", "          ", "ANYTHING10"})
        @DisplayName("any ten-character text is accepted and stored without folding or trimming")
        void anyTenCharacterTextIsAccepted(final String source) {
            final DailyTransaction row = new DailyTransaction(1L, "0000000000000001", "01",
                    Integer.valueOf(1), source, " ".repeat(100), BigDecimal.ZERO, Long.valueOf(0L),
                    " ".repeat(50), " ".repeat(50), " ".repeat(10), "0000000000000000",
                    FixedClockProvider.CANONICAL_ONLINE_TIMESTAMP, " ".repeat(TIMESTAMP_WIDTH));

            assertThat(row.getTransactionSource()).isEqualTo(source).hasSize(10);
        }
    }

    @Nested
    @DisplayName("no shared superclass with the keyed twin, whose lifecycle differs")
    class NoSharedSuperclass {

        @Test
        @DisplayName("the entity extends Object directly and implements no interface")
        void theEntityExtendsObjectAndImplementsNothing() {
            assertThat(DailyTransaction.class.getSuperclass())
                    .as("unifying the two 350-byte entities under a shared base would hand this staging "
                            + "table the version column it must not have")
                    .isEqualTo(Object.class);
            assertThat(DailyTransaction.class.getInterfaces()).isEmpty();
        }

        @Test
        @DisplayName("no inheritance or mapped-superclass annotation appears on the entity")
        void noInheritanceAnnotationAppears() {
            final Set<String> classAnnotations = Arrays.stream(DailyTransaction.class.getAnnotations())
                    .map(annotation -> annotation.annotationType().getSimpleName())
                    .collect(Collectors.toCollection(TreeSet::new));

            assertThat(classAnnotations)
                    .doesNotContain("MappedSuperclass", "Inheritance", "DiscriminatorColumn",
                            "DiscriminatorValue", "PrimaryKeyJoinColumn");
            assertThat(classAnnotations).contains("Entity", "Table");
        }

        @Test
        @DisplayName("the entity does not implement Serializable")
        void theEntityIsNotSerializable() {
            assertThat(Serializable.class.isAssignableFrom(DailyTransaction.class))
                    .as("nothing serialises a staging row: it is written by the reader and consumed by the "
                            + "posting processor within one job, and Java serialisation of an untrusted "
                            + "record is a risky pattern in its own right")
                    .isFalse();
        }

        @Test
        @DisplayName("the keyed twin carries a version column and this staging entity does not")
        void onlyTheKeyedTwinCarriesAVersionColumn() throws ClassNotFoundException {
            final Class<?> twin = Class.forName(TWIN_CLASS);

            final boolean twinIsVersioned = Arrays.stream(twin.getDeclaredFields())
                    .flatMap(field -> Arrays.stream(field.getAnnotations()))
                    .map(annotation -> annotation.annotationType().getSimpleName())
                    .anyMatch("Version"::equals);
            final boolean stagingIsVersioned = Arrays.stream(DailyTransaction.class.getDeclaredFields())
                    .flatMap(field -> Arrays.stream(field.getAnnotations()))
                    .map(annotation -> annotation.annotationType().getSimpleName())
                    .anyMatch("Version"::equals);

            assertThat(twinIsVersioned)
                    .as("Transaction is a keyed cluster catalogued at app/catlg/LISTCAT.txt with an "
                            + "alternate index, and it is updated concurrently")
                    .isTrue();
            assertThat(stagingIsVersioned).isFalse();
        }

        @Test
        @DisplayName("the two types are not interchangeable in either direction")
        void theTwoTypesAreNotInterchangeable() throws ClassNotFoundException {
            final Class<?> twin = Class.forName(TWIN_CLASS);

            assertThat(twin.isAssignableFrom(DailyTransaction.class)).isFalse();
            assertThat(DailyTransaction.class.isAssignableFrom(twin)).isFalse();
            assertThat(twin.getSuperclass())
                    .as("neither type may acquire a common supertype other than Object")
                    .isEqualTo(Object.class);
        }

        @Test
        @DisplayName("the two copybooks share a geometry but not a prefix, which is why one entity cannot serve both")
        void theTwoCopybooksShareGeometryButNotPrefix() {
            final RecordLayoutCopybook staging = RecordLayoutCopybook.of(COPYBOOK_MEMBER);
            final RecordLayoutCopybook keyed = RecordLayoutCopybook.of("CVTRA05Y");

            assertThat(keyed.recordLength()).isEqualTo(staging.recordLength()).isEqualTo(RECORD_LENGTH);
            assertThat(keyed.fieldNames()).hasSameSizeAs(staging.fieldNames());
            assertThat(keyed.fieldNames()).allSatisfy(name -> assertThat(name).startsWith("TRAN-"));
            assertThat(staging.fieldNames()).allSatisfy(name -> assertThat(name).startsWith("DALYTRAN-"));
            assertThat(keyed.fieldNames()).doesNotContainAnyElementsOf(staging.fieldNames());
        }
    }

    @Nested
    @DisplayName("toString discloses the identifier and nothing else")
    class ToStringSecurity {

        @Test
        @DisplayName("the rendering omits the card number, the merchant detail and the amount")
        void theRenderingOmitsSensitiveValues() {
            final DailyTransaction row = new DailyTransaction(1L, "0000000000000001", "01",
                    Integer.valueOf(1), "POS TERM  ", "Purchase at Test Merchant" + " ".repeat(75),
                    new BigDecimal("504.77"), Long.valueOf(800_000_000L),
                    "Test Merchant" + " ".repeat(37), "Test City" + " ".repeat(41), "72112-1234",
                    SYNTHETIC_CARD_NUMBER, FixedClockProvider.CANONICAL_ONLINE_TIMESTAMP,
                    " ".repeat(TIMESTAMP_WIDTH));

            final String rendered = row.toString();

            assertThat(rendered)
                    .as("toString is the single most likely route by which a field reaches a log record, "
                            + "an exception message or a stack trace, often unintentionally")
                    .doesNotContain(row.getCardNumber())
                    .doesNotContain(SYNTHETIC_CARD_NUMBER.substring(0, 4))
                    .doesNotContain("Test Merchant")
                    .doesNotContain("Test City")
                    .doesNotContain("72112")
                    .doesNotContain("504.77")
                    .doesNotContain(FixedClockProvider.CANONICAL_ONLINE_TIMESTAMP);
        }

        @Test
        @DisplayName("the rendering carries the transaction identifier, so a log line still names its row")
        void theRenderingCarriesTheIdentifier() {
            final DailyTransaction row = validRow();

            assertThat(row.toString())
                    .startsWith("DailyTransaction{")
                    .endsWith("}")
                    .contains(row.getTransactionId());
        }

        @Test
        @DisplayName("the rendering is independent of locale and of any clock")
        void theRenderingIsDeterministic() {
            assertThat(validRow().toString())
                    .as("plain concatenation only, so the output is identical on every machine")
                    .isEqualTo(validRow().toString());
        }

        @Test
        @DisplayName("no companion method renders the withheld fields, so the exclusion cannot be bypassed")
        void noCompanionMethodRendersTheWithheldFields() {
            final List<String> renderers = Arrays.stream(DailyTransaction.class.getDeclaredMethods())
                    .filter(method -> method.getReturnType() == String.class)
                    .filter(method -> method.getParameterCount() == 0)
                    .map(Method::getName)
                    .filter(name -> !name.startsWith("get"))
                    .toList();

            assertThat(renderers).containsExactly("toString");
        }

        @Test
        @DisplayName("the Jackson barrier keeps the entity out of any JSON response")
        void theJacksonBarrierIsInPlace() {
            final Set<String> classAnnotations = Arrays.stream(DailyTransaction.class.getAnnotations())
                    .map(annotation -> annotation.annotationType().getSimpleName())
                    .collect(Collectors.toCollection(TreeSet::new));

            assertThat(classAnnotations)
                    .as("without the barrier, returning this type from a controller would publish the card "
                            + "number alongside the amount and the full merchant detail")
                    .contains("JsonIgnoreType", "JsonAutoDetect");
        }
    }

    @Nested
    @DisplayName("scope boundaries: a model type that depends on no other layer")
    class ScopeBoundaries {

        @ParameterizedTest(name = "no import from {0}")
        @ValueSource(strings = {
            "com.cardemo.exception", "com.cardemo.repository", "com.cardemo.service", "com.cardemo.controller",
            "com.cardemo.batch", "com.cardemo.security", "com.cardemo.config", "com.cardemo.observability",
            "com.cardemo.model.key", "com.cardemo.model.enums"})
        @DisplayName("the entity imports nothing from any package outside the model layer")
        void theEntityImportsNothingFromOtherLayers(final String forbiddenPackage) {
            assertThat(entityImports())
                    .as("a model type that reached into %s would invert the dependency direction the "
                            + "package layering establishes", forbiddenPackage)
                    .noneMatch(imported -> imported.startsWith(forbiddenPackage + "."));
        }

        @Test
        @DisplayName("the entity imports nothing from com.cardemo at all")
        void theEntityImportsNothingFromTheApplicationRoot() {
            assertThat(entityImports())
                    .as("the keyed twin is referenced only from documentation and lives in the same "
                            + "package, so no application import is needed at all")
                    .noneMatch(imported -> imported.startsWith("com.cardemo."));
        }

        @Test
        @DisplayName("no reject-code type reaches this entity: reject codes drive an exit status, never a constraint")
        void noRejectCodeTypeReachesTheEntity() {
            assertThat(entityImports()).doesNotContain("com.cardemo.model.enums.RejectCode");

            for (final Field field : DailyTransaction.class.getDeclaredFields()) {
                assertThat(field.getType().getName())
                        .as("field %s must not carry a reject code: codes 100 and 101 are business "
                                + "outcomes owned by the posting processor", field.getName())
                        .doesNotContain("RejectCode");
            }
        }

        @Test
        @DisplayName("every declared field is private, so state is reached only through the accessors")
        void everyDeclaredFieldIsPrivate() {
            for (final Field field : DailyTransaction.class.getDeclaredFields()) {
                assertThat(Modifier.isPrivate(field.getModifiers()))
                        .as("field %s must be private", field.getName())
                        .isTrue();
                assertThat(Modifier.isStatic(field.getModifiers()) || !Modifier.isFinal(field.getModifiers()))
                        .as("field %s must stay assignable by the persistence provider", field.getName())
                        .isTrue();
            }
        }

        @Test
        @DisplayName("the entity has no catalogue entry, because DALYTRAN is a sequential dataset")
        void theEntityHasNoCatalogueEntry() {
            final Path listing = Path.of("app", "catlg", "LISTCAT.txt");
            final List<String> lines;
            try {
                lines = Files.readAllLines(listing, StandardCharsets.ISO_8859_1);
            } catch (final IOException unreadable) {
                throw new AssertionError("cannot read " + listing, unreadable);
            }

            final List<String> clusterLines = lines.stream()
                    .filter(line -> line.contains("CLUSTER ------- "))
                    .toList();
            final List<String> nonVsamDalytran = lines.stream()
                    .filter(line -> line.contains("NONVSAM") && line.contains("DALYTRAN"))
                    .toList();

            assertThat(clusterLines)
                    .as("the catalogue tallies exactly ten CLUSTER entries, and DALYTRAN is not among them")
                    .isNotEmpty()
                    .noneMatch(line -> line.contains("DALYTRAN"));
            assertThat(nonVsamDalytran)
                    .as("DALYTRAN appears only as NONVSAM, the physical sequential dataset "
                            + "AWS.M2.CARDDEMO.DALYTRAN.PS, so it has no key length to cite: that detail "
                            + "is Not available by construction rather than omitted")
                    .isNotEmpty();
            assertThat(lines).anyMatch(line -> line.contains("CLUSTER --------------10"));
        }
    }

    @Nested
    @DisplayName("untrusted input: every boundary of the record layout is enforced explicitly")
    class UntrustedInput {

        /**
         * Builds a row whose single named property is {@code null} and whose others are valid.
         *
         * @param property the property to null out
         * @return the resulting row, if the entity permits it
         */
        private DailyTransaction rowWithNullAt(final String property) {
            return new DailyTransaction(
                    1L,
                    "transactionId".equals(property) ? null : "0000000000000001",
                    "typeCode".equals(property) ? null : "01",
                    "categoryCode".equals(property) ? null : Integer.valueOf(1),
                    "transactionSource".equals(property) ? null : "POS TERM  ",
                    "description".equals(property) ? null : " ".repeat(100),
                    "amount".equals(property) ? null : BigDecimal.ZERO,
                    "merchantId".equals(property) ? null : Long.valueOf(0L),
                    "merchantName".equals(property) ? null : " ".repeat(50),
                    "merchantCity".equals(property) ? null : " ".repeat(50),
                    "merchantZip".equals(property) ? null : " ".repeat(10),
                    "cardNumber".equals(property) ? null : "0000000000000000",
                    "origTs".equals(property) ? null : FixedClockProvider.CANONICAL_ONLINE_TIMESTAMP,
                    "procTs".equals(property) ? null : " ".repeat(TIMESTAMP_WIDTH));
        }

        @ParameterizedTest(name = "a null {1} is refused and the message names {0}")
        @CsvSource(textBlock = OFFSET_MAP)
        @DisplayName("each of the thirteen record properties refuses null, naming the property and its picture")
        void everyRecordPropertyRefusesNull(final String cobolField,
                                           final String property,
                                           final String column,
                                           final int startColumn,
                                           final int width) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("the fixed-width reader slices %d bytes at column %d, so it can never yield null; "
                            + "a null here is a defect in the caller", width, startColumn)
                    .isThrownBy(() -> rowWithNullAt(property))
                    .withMessageContaining(property)
                    .withMessageContaining(cobolField)
                    .withMessageContaining("must not be null")
                    .withMessageContaining("NOT NULL")
                    .withMessageContaining("table daily_transaction")
                    .withNoCause();
        }

        @ParameterizedTest(name = "{0} refuses a value one character over its width")
        @CsvSource({
            "transactionId,  16", "typeCode,        2", "transactionSource, 10", "description,   100",
            "merchantName,   50", "merchantCity,   50", "merchantZip,       10", "cardNumber,     16",
            "origTs,         26", "procTs,         26"})
        @DisplayName("each character property refuses a value wider than the 350-byte record can hold")
        void eachCharacterPropertyRefusesAnOverWideValue(final String property, final int width) {
            final String tooWide = "X".repeat(width + 1);

            final DailyTransaction row = validRow();
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> {
                        switch (property) {
                            case "transactionId" -> row.setTransactionId(tooWide);
                            case "typeCode" -> row.setTypeCode(tooWide);
                            case "transactionSource" -> row.setTransactionSource(tooWide);
                            case "description" -> row.setDescription(tooWide);
                            case "merchantName" -> row.setMerchantName(tooWide);
                            case "merchantCity" -> row.setMerchantCity(tooWide);
                            case "merchantZip" -> row.setMerchantZip(tooWide);
                            case "cardNumber" -> row.setCardNumber(tooWide);
                            case "origTs" -> row.setOrigTs(tooWide);
                            case "procTs" -> row.setProcTs(tooWide);
                            default -> throw new AssertionError("unmapped property " + property);
                        }
                    })
                    .withMessageContaining(property)
                    .withMessageContaining("at most " + width + " characters")
                    .withMessageContaining("but was " + (width + 1))
                    .withNoCause();
        }

        @ParameterizedTest(name = "an empty {0} is accepted, because a PIC X field may hold only spaces")
        @ValueSource(strings = {"transactionSource", "description", "merchantZip", "procTs"})
        @DisplayName("an empty string is accepted where the picture clause admits blank content")
        void anEmptyStringIsAcceptedWhereBlankIsLegitimate(final String property) {
            final DailyTransaction row = validRow();

            assertThatNoException().isThrownBy(() -> {
                switch (property) {
                    case "transactionSource" -> row.setTransactionSource("");
                    case "description" -> row.setDescription("");
                    case "merchantZip" -> row.setMerchantZip("");
                    case "procTs" -> row.setProcTs("");
                    default -> throw new AssertionError("unmapped property " + property);
                }
            });
            assertThat(row.getProcTs()).isNotNull();
        }

        @ParameterizedTest(name = "the category code {0} is accepted")
        @ValueSource(ints = {0, 1, 9999})
        @DisplayName("every value four unsigned display digits can hold is accepted as a category code")
        void everyRepresentableCategoryCodeIsAccepted(final int categoryCode) {
            final DailyTransaction row = validRow();

            assertThatNoException().isThrownBy(() -> row.setCategoryCode(Integer.valueOf(categoryCode)));
            assertThat(row.getCategoryCode()).isEqualTo(Integer.valueOf(categoryCode));
        }

        @ParameterizedTest(name = "the category code {0} is refused")
        @ValueSource(ints = {-1, 10_000, Integer.MAX_VALUE, Integer.MIN_VALUE})
        @DisplayName("a category code outside PIC 9(04) is refused, and no membership check is attempted")
        void anOutOfRangeCategoryCodeIsRefused(final int categoryCode) {
            final DailyTransaction row = validRow();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("the range is structural; membership in the transaction-category table is the "
                            + "posting job's judgement, not this entity's")
                    .isThrownBy(() -> row.setCategoryCode(Integer.valueOf(categoryCode)))
                    .withMessageContaining("DALYTRAN-CAT-CD PIC 9(04)")
                    .withMessageContaining("between 0 and 9999")
                    .withNoCause();
        }

        @ParameterizedTest(name = "the merchant identifier {0} is accepted")
        @ValueSource(longs = {0L, 800_000_000L, 999_999_999L})
        @DisplayName("every value nine unsigned display digits can hold is accepted, zero included")
        void everyRepresentableMerchantIdentifierIsAccepted(final long merchantId) {
            final DailyTransaction row = validRow();

            assertThatNoException().isThrownBy(() -> row.setMerchantId(Long.valueOf(merchantId)));
            assertThat(row.getMerchantId()).isEqualTo(Long.valueOf(merchantId));
        }

        @ParameterizedTest(name = "the merchant identifier {0} is refused")
        @ValueSource(longs = {-1L, 1_000_000_000L, Long.MAX_VALUE})
        @DisplayName("a merchant identifier outside PIC 9(09) is refused")
        void anOutOfRangeMerchantIdentifierIsRefused(final long merchantId) {
            final DailyTransaction row = validRow();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> row.setMerchantId(Long.valueOf(merchantId)))
                    .withMessageContaining("DALYTRAN-MERCHANT-ID PIC 9(09)")
                    .withMessageContaining("between 0 and 999999999")
                    .withNoCause();
        }

        @ParameterizedTest(name = "a declared record width of {0} is rejected against the real 350-byte fixture")
        @ValueSource(ints = {349, 351})
        @DisplayName("a record one byte short or one byte long is detected rather than silently accepted")
        void aRecordOffByOneByteIsDetected(final int wrongWidth) {
            assertThatExceptionOfType(IllegalStateException.class)
                    .as("the fixture is read as shipped; only the declared width is varied, because "
                            + "app/data/ASCII is frozen and no altered copy may be written")
                    .isThrownBy(() -> FixtureLoader.loadResource("dailytran.txt", wrongWidth))
                    .withMessageContaining("350 characters wide")
                    .withMessageContaining("must be exactly " + wrongWidth);
        }

        @Test
        @DisplayName("a slice running past byte 350 is refused, so the FILLER cannot be over-read")
        void aSliceRunningPastTheRecordIsRefused() {
            final FixtureLoader.FixtureData data = FixtureLoader.load(FixtureLoader.Fixture.DAILY_TRANSACTION);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> data.field(0, 341, FILLER_WIDTH))
                    .withMessageContaining("runs past the 350-character record")
                    .withNoCause();
        }

        @Test
        @DisplayName("a record index outside the fixture is refused, naming the bound and not the content")
        void aRecordIndexOutsideTheFixtureIsRefused() {
            final FixtureLoader.FixtureData data = FixtureLoader.load(FixtureLoader.Fixture.DAILY_TRANSACTION);

            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> data.recordAt(data.recordCount()))
                    .withMessageContaining("300 records");
        }

        @Test
        @DisplayName("identity rests on the ingestion ordinal alone, so two staged rows never collide by content")
        void identityRestsOnTheIngestionOrdinalAlone() {
            final DailyTransaction first = validRow();
            final DailyTransaction second = validRow();

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);

            second.setIngestSequence(2L);
            assertThat(first)
                    .as("the ordinal is the identity, so a second row staged from the same content is a "
                            + "different row")
                    .isNotEqualTo(second);
            assertThat(first).isNotEqualTo(null).isNotEqualTo("0000000000000001");
        }

        @Test
        @DisplayName("a repeated transaction identifier is accepted, because the unkeyed input may repeat it")
        void aRepeatedTransactionIdentifierIsAccepted() {
            final DailyTransaction first = validRow();
            final DailyTransaction second = validRow();
            second.setIngestSequence(2L);

            assertThat(second.getTransactionId()).isEqualTo(first.getTransactionId());
            assertThat(second)
                    .as("keying on DALYTRAN-ID would reject valid input from a sequential dataset")
                    .isNotEqualTo(first);
        }
    }
}

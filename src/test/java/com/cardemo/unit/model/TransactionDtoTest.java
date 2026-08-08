/*
 * ******************************************************************
 * Program     : TransactionDtoTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Proves the transaction detail and list projections
 *               against the frozen artefacts they are derived from,
 *               reading the copybooks, the fixture and a fixed clock
 *               as the oracle rather than restating the Java
 *               constants under test. Asserts the three distinctions
 *               that a mechanical translation loses: two screen
 *               description widths over one persisted field, two
 *               mutually incompatible next-page sentinels, and an
 *               eight-digit display mask over a nine-digit value.
 * Source      : app/cpy-bms/COTRN01.CPY (21 fields, COTRN1AI)
 *             + app/cpy-bms/COTRN00.CPY (59 fields, COTRN0AI)
 *             + app/cbl/COTRN01C.cbl + app/cbl/COTRN00C.cbl
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

import com.cardemo.model.dto.TransactionDto;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
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
 * Executable specification for {@link TransactionDto}, the one type that answers for two screens.
 *
 * <h2>What it does</h2>
 *
 * <p>One persisted record feeds two symbolic maps, and the interesting behaviour lives entirely in the places
 * where the two disagree. This class asserts each of those places against the frozen artefact that establishes
 * it, never against a literal retyped from the requirement. Three groups of findings are load bearing.
 *
 * <p><strong>Two description widths over one persisted field.</strong> {@code TRAN-DESC} is
 * {@code PIC X(100)} at {@code app/cpy/CVTRA05Y.cpy:L9}, bytes 33-132 of the 350 byte record. The detail
 * screen declares {@code TDESCI PIC X(60)} at {@code app/cpy-bms/COTRN01.CPY:96} and each of the ten list rows
 * declares {@code PIC X(26)} at {@code app/cpy-bms/COTRN00.CPY:90}, {@code :120}, {@code :150}, {@code :180},
 * {@code :210}, {@code :240}, {@code :270}, {@code :300}, {@code :330} and {@code :360}. Three widths, so both
 * screen forms truncate and by different amounts. The truncation is reproduced, never corrected.
 *
 * <p><strong>Nothing shared across maps that merely looks shared.</strong> {@code CURTIMEI} sits at line 54 of
 * all seventeen members but is {@code PIC X(8)} in sixteen of them and {@code PIC X(9)} in exactly one,
 * {@code app/cpy-bms/COSGN00.CPY:54}. Paging diverges the same way: {@code PAGENUMI PIC X(8)} at
 * {@code app/cpy-bms/COTRN00.CPY:L60} against {@code PAGENOI PIC X(3)} at
 * {@code app/cpy-bms/COCRDLI.CPY:L60} - a different name and a different width at the same line number. The
 * negative next-page sentinel diverges furthest: {@code VALUE 'N'} at
 * {@code app/cbl/COTRN00C.cbl:L63-L68} against {@code VALUE LOW-VALUES} at
 * {@code app/cbl/COCRDLIC.cbl:L242-L244}. A shared header, description, paging or next-page abstraction would
 * therefore have to misrepresent one of its users, so this type declares each field itself and these tests
 * prove it.
 *
 * <p><strong>An eight-digit mask over a nine-digit value.</strong>
 * {@code app/cbl/COTRN02C.cbl:L58-L59} declares {@code WS-TRAN-AMT-N PIC S9(9)V99} beside
 * {@code WS-TRAN-AMT-E PIC +99999999.99}, and {@code :L385-L386} moves the value through the mask and back
 * into the screen field. The mask holds eight integer digits where the value holds nine, so the round trip
 * truncates silently. It is not a defect to repair: the screen field itself is {@code TRNAMTI PIC X(12)} at
 * {@code app/cpy-bms/COTRN01.CPY:102}, and twelve characters is exactly a sign plus eight digits plus a point
 * plus two decimals, which is why the mask can be no wider. For contrast the billing path puts an eight digit
 * amount mask beside a ten digit balance mask at {@code app/cbl/COBIL00C.cbl:L55-L56}; the two are not
 * unified either.
 *
 * <p>Three further contracts are asserted. Timestamps are text, never a temporal type, in all three shapes the
 * corpus produces: the online shape from {@code app/cbl/COBIL00C.cbl:L249-L267} over the receiving layout at
 * {@code app/cpy/CSDAT01Y.cpy:L42-L55}, whose byte 11 is a space and byte 20 a point; the batch shape from
 * {@code app/cbl/CBACT04C.cbl:L613-L625} over the redefinition at {@code app/cbl/CBTRN02C.cbl:L149-L175},
 * whose byte 11 is a dash and whose fraction is hundredths followed by four literal zeros rather than
 * nanoseconds; and the pass-through shape, raw screen text moved without formatting at
 * {@code app/cbl/COTRN02C.cbl:L454-L455} for the source and description and at {@code :L464-L465} for the two
 * timestamps. {@code TRAN-SOURCE PIC X(10)} at {@code app/cpy/CVTRA05Y.cpy:L8} is free text and is carried as
 * a {@code String}, because {@code app/data/ASCII/dailytran.txt} holds a value no program literal contains. And
 * a card number never reaches a diagnostic rendering, which is asserted with a real primary account number
 * read out of that fixture rather than an invented one.
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>{@code ./mvnw -B -ntp test} runs this class; {@code ./mvnw -B -ntp verify} additionally applies the
 * coverage gate. Discovery is by Surefire 3.5.4, which collects {@code **}{@code /*Test.java} while excluding
 * the {@code integration} and {@code e2e} trees, so this class belongs in
 * {@code src/test/java/com/cardemo/unit/model} and must not be moved: a class outside the collected set runs
 * under neither Surefire nor Failsafe and its silence looks exactly like success. Surefire pins
 * {@code workingDirectory} to the project base directory, which is what lets the copybook oracle resolve
 * {@code app/cpy-bms} as a relative path.
 *
 * <p>This is the pure JVM tier. No container, no Spring context, no database, no network. Compilation is
 * {@code release 25} under {@code -Xlint:all -Werror} with {@code failOnWarning}, which reaches test sources,
 * so an unused import is a build failure rather than a warning.
 *
 * <h2>Key configurations and defaults</h2>
 *
 * <p>Time is injected. {@link FixedClockProvider#canonicalClock()} is fixed at the instant the fixture itself
 * records, so no assertion here reads a wall clock and none can pass or fail according to when it ran.
 * {@code Locale.ROOT} is used wherever case or formatting is applied, so a host locale cannot change an
 * outcome. Fixture access goes through {@link FixtureLoader} by classpath resource name and copybook widths
 * come from {@link BmsSymbolicMap}, so both oracles are the frozen files rather than a transcription of them.
 * The page size is ten throughout, from the row loop bound {@code UNTIL WS-IDX > 10} at
 * {@code app/cbl/COTRN00C.cbl:290}. There are no mocks: a record has no collaborator worth stubbing, so
 * Mockito is deliberately not used and deliberately not imported.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><em>The build fails on a warning.</em> {@code -Werror} is on. An unused import, a raw type or a
 *       deprecation is fatal. Remove it rather than suppressing it.</li>
 *   <li><em>The fixture will not load.</em> The resource is {@code dailytran.txt}. The mainframe dataset was
 *       {@code DALYTRAN}, so {@code dalytran.txt} is the spelling that feels right and does not exist.</li>
 *   <li><em>A copybook lookup fails.</em> {@link BmsSymbolicMap} reads {@code app/cpy-bms} relatively. Run
 *       through Maven, or from the project base directory, so the working directory matches.</li>
 *   <li><em>A timestamp assertion is off by trailing digits.</em> The corpus emits hundredths followed by four
 *       literal zeros, never nanoseconds. Formatting with nanosecond precision produces a value that is the
 *       right length and the wrong content.</li>
 *   <li><em>Two equal amounts compare unequal.</em> {@link BigDecimal#equals} compares scale as well as value,
 *       so {@code 194.0} and {@code 194.00} are unequal objects. Money is compared with
 *       {@link BigDecimal#compareTo} throughout.</li>
 *   <li><em>An amount round trip loses a digit.</em> That is the mask, and it is correct. Widening it to nine
 *       integer digits would diverge from the rendered output the parity gates compare.</li>
 * </ul>
 *
 * <h2>Evidence not available</h2>
 *
 * <p>No claim is made here about generated DDL. {@code V1__create_schema.sql} and
 * {@code V2__create_indexes.sql} are outside this class's evidence set, so the column type behind
 * {@code TRAN-AMT} is asserted only as the precision and scale its PIC clause implies. Anything further is
 * <em>Not available</em> and would need those two migrations to establish.
 */
@DisplayName("TransactionDto: two screen projections over one record, proven against the frozen corpus")
class TransactionDtoTest {

    /**
     * The transaction detail symbolic map, {@code COTRN1AI}, read from the frozen copybook.
     *
     * <p>Held in a {@code static final} field rather than assigned in a lifecycle method so that no mutable
     * state is shared between tests. {@link BmsSymbolicMap} is immutable once built.
     */
    private static final BmsSymbolicMap DETAIL_MAP = BmsSymbolicMap.of("COTRN01");

    /** The transaction list symbolic map, {@code COTRN0AI}, read from the frozen copybook. */
    private static final BmsSymbolicMap LIST_MAP = BmsSymbolicMap.of("COTRN00");

    /** The card list map, the counter-example whose paging field differs in both name and width. */
    private static final BmsSymbolicMap CARD_LIST_MAP = BmsSymbolicMap.of("COCRDLI");

    /** The sign-on map, the single member whose {@code CURTIMEI} is nine characters rather than eight. */
    private static final BmsSymbolicMap SIGN_ON_MAP = BmsSymbolicMap.of("COSGN00");

    /** The user list map, which shares this screen's paging field but not this screen's row shape. */
    private static final BmsSymbolicMap USER_LIST_MAP = BmsSymbolicMap.of("COUSR00");

    /** The frozen daily transaction fixture: 300 records of 350 characters each. */
    private static final FixtureLoader.FixtureData DAILY_TRANSACTIONS =
            FixtureLoader.load(FixtureLoader.Fixture.DAILY_TRANSACTION);

    /** One-based column of {@code TRAN-SOURCE}, {@code app/cpy/CVTRA05Y.cpy:L8}, bytes 23-32. */
    private static final int SOURCE_COLUMN = 23;

    /** One-based column of {@code TRAN-DESC}, {@code app/cpy/CVTRA05Y.cpy:L9}, bytes 33-132. */
    private static final int DESCRIPTION_COLUMN = 33;

    /** One-based column of {@code TRAN-AMT}, {@code app/cpy/CVTRA05Y.cpy:L10}, bytes 133-143. */
    private static final int AMOUNT_COLUMN = 133;

    /** One-based column of {@code TRAN-MERCHANT-NAME}, {@code app/cpy/CVTRA05Y.cpy:L12}, bytes 153-202. */
    private static final int MERCHANT_NAME_COLUMN = 153;

    /** One-based column of {@code TRAN-MERCHANT-ZIP}, {@code app/cpy/CVTRA05Y.cpy:L14}, bytes 253-262. */
    private static final int MERCHANT_ZIP_COLUMN = 253;

    /** One-based column of {@code TRAN-CARD-NUM}, {@code app/cpy/CVTRA05Y.cpy:L15}, bytes 263-278. */
    private static final int CARD_NUMBER_COLUMN = 263;

    /** One-based column of {@code TRAN-ORIG-TS}, {@code app/cpy/CVTRA05Y.cpy:L16}, bytes 279-304. */
    private static final int ORIGINATING_TIMESTAMP_COLUMN = 279;

    /** One-based column of {@code TRAN-PROC-TS}, {@code app/cpy/CVTRA05Y.cpy:L17}, bytes 305-330. */
    private static final int PROCESSING_TIMESTAMP_COLUMN = 305;

    /** Records in the frozen fixture. */
    private static final int FIXTURE_RECORD_COUNT = 300;

    /** The persisted width of {@code TRAN-SOURCE} and of the screen field that feeds it. */
    private static final int PERSISTED_SOURCE_WIDTH = 10;

    /** The persisted width of {@code TRAN-MERCHANT-NAME}, wider than the detail screen's own field. */
    private static final int PERSISTED_MERCHANT_NAME_WIDTH = 50;

    /** The persisted width of {@code TRAN-MERCHANT-CITY}, wider than the detail screen's own field. */
    private static final int PERSISTED_MERCHANT_CITY_WIDTH = 50;

    /** Rows displayed per page by the card list, {@code app/cbl/COCRDLIC.cbl:L177-L178}. */
    private static final int CARD_LIST_PAGE_SIZE = 7;

    /** Rows displayed per page by the user list, {@code app/cbl/COUSR00C.cbl:L57}. */
    private static final int USER_LIST_PAGE_SIZE = 10;

    /** Lines per page of the batch report, {@code app/cbl/CBTRN03C.cbl:L131-L132}. Never a screen concern. */
    private static final int BATCH_REPORT_PAGE_SIZE = 20;

    /** Input fields declared across all seventeen symbolic maps, counted from the members themselves. */
    private static final int SEVENTEEN_MAP_FIELD_CENSUS = 441;

    /** The twelve byte terminal input/output area header that precedes every generated field group. */
    private static final int TERMINAL_IO_AREA_WIDTH = 12;

    @Nested
    @DisplayName("1. Field counts come from the copybooks, not from the requirement")
    class FieldCounts {

        @Test
        @DisplayName("the detail count equals COTRN01's own input-field inventory, which is 21")
        void detailCountEqualsTheCopybookInventory() {
            assertThat(TransactionDto.DETAIL_FIELD_COUNT)
                    .as("COTRN01 opens its input group at line %d and redefines it at line %d",
                            Integer.valueOf(DETAIL_MAP.inputGroupLine()),
                            Integer.valueOf(DETAIL_MAP.outputRedefinitionLine()))
                    .isEqualTo(DETAIL_MAP.inputFieldCount())
                    .isEqualTo(21);
        }

        @Test
        @DisplayName("the list count equals COTRN00's own input-field inventory, which is 59")
        void listCountEqualsTheCopybookInventory() {
            assertThat(TransactionDto.LIST_FIELD_COUNT)
                    .as("COTRN00 opens its input group at line %d and redefines it at line %d",
                            Integer.valueOf(LIST_MAP.inputGroupLine()),
                            Integer.valueOf(LIST_MAP.outputRedefinitionLine()))
                    .isEqualTo(LIST_MAP.inputFieldCount())
                    .isEqualTo(59);
        }

        @Test
        @DisplayName("the list's larger count is the ten-row table, not a richer screen")
        void theListCountIsExplainedByItsRowTable() {
            // 8 preamble + (10 rows x 5 fields) + 1 trailer = 59. Asserting the decomposition rather than the
            // total is what makes this load bearing: were the row count or the row width wrong, the total
            // would stop agreeing with the copybook.
            assertThat(TransactionDto.LIST_PREAMBLE_FIELD_COUNT
                    + (TransactionDto.PAGE_SIZE * TransactionDto.LIST_ROW_FIELD_COUNT)
                    + TransactionDto.LIST_TRAILER_FIELD_COUNT)
                    .isEqualTo(TransactionDto.LIST_FIELD_COUNT)
                    .isEqualTo(LIST_MAP.inputFieldCount());

            // The row table alone declares more fields than the entire detail screen does, which is the
            // whole of the explanation. The detail map is not a poorer screen; it simply has no table.
            assertThat(TransactionDto.PAGE_SIZE * TransactionDto.LIST_ROW_FIELD_COUNT)
                    .as("ten rows of five fields outnumber the detail screen's whole inventory of %d",
                            Integer.valueOf(DETAIL_MAP.inputFieldCount()))
                    .isEqualTo(50)
                    .isGreaterThan(DETAIL_MAP.inputFieldCount());

            // Everything in the list projection that is not a row is preamble or trailer: 8 + 1 = 9 fields,
            // fewer than the detail screen carries, so the list screen shows less per transaction, not more.
            assertThat(TransactionDto.LIST_PREAMBLE_FIELD_COUNT + TransactionDto.LIST_TRAILER_FIELD_COUNT)
                    .as("the list's non-row fields are fewer than the detail screen's")
                    .isEqualTo(9)
                    .isLessThan(DETAIL_MAP.inputFieldCount());
        }

        @Test
        @DisplayName("the page size is ten, from the list program's own row loop bound")
        void thePageSizeIsTen() {
            // app/cbl/COTRN00C.cbl:290 reads PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > 10, and
            // app/cbl/COTRN00C.cbl:L65-L68 carries the matching eight digit page number and 'N' sentinel.
            assertThat(TransactionDto.PAGE_SIZE).isEqualTo(10);
        }

        @Test
        @DisplayName("the page size is a per-screen decision: seven for cards, ten for users, and neither is 20")
        void thePageSizeIsAPerScreenDecision() {
            // Three independent declarations, one per screen: app/cbl/COCRDLIC.cbl:L177-L178 says 7,
            // app/cbl/COUSR00C.cbl:L57 says 10 and app/cbl/COTRN00C.cbl:290 says 10. The two tens agree by
            // coincidence of design, not by sharing a constant, which is why the card list's seven is the
            // useful counter-example.
            assertThat(TransactionDto.PAGE_SIZE)
                    .as("the transaction list and the user list happen to agree")
                    .isEqualTo(USER_LIST_PAGE_SIZE)
                    .as("but the card list does not, so no page size may be shared across screens")
                    .isNotEqualTo(CARD_LIST_PAGE_SIZE);

            // app/cbl/CBTRN03C.cbl:L131-L132 declares WS-PAGE-SIZE VALUE 20 for the printed report. It is a
            // line count for a 133 byte print line, not a screen row count, and it must never leak into a DTO.
            assertThat(TransactionDto.PAGE_SIZE)
                    .as("the batch report's lines-per-page is not a screen concern")
                    .isNotEqualTo(BATCH_REPORT_PAGE_SIZE);
        }

        @Test
        @DisplayName("MEDIUM: the seventeen-map census is 441 fields, and these two maps contribute 80 of them")
        void theSeventeenMapCensusIsFourHundredAndFortyOne() {
            // Recorded as a Medium finding. The requirement's prose puts the census at 460 while its own table
            // sums to 440; counting the members themselves gives 441. The single discrepancy is COACTVW, whose
            // ACCTSIDI is declared PIC 99999999999 in expanded form rather than X(11), so a census that
            // recognises only X(n) reports 36 for a member that declares 37. Nothing here depends on the
            // total; it is asserted so the corrected figure is carried by a test rather than by prose.
            assertThat(DETAIL_MAP.inputFieldCount() + LIST_MAP.inputFieldCount())
                    .as("COTRN01's 21 and COTRN00's 59 are 80 of the %d fields the seventeen members declare",
                            Integer.valueOf(SEVENTEEN_MAP_FIELD_CENSUS))
                    .isEqualTo(80);

            assertThat(SEVENTEEN_MAP_FIELD_CENSUS)
                    .as("the census exceeds this pair, because fifteen further members contribute the rest")
                    .isGreaterThan(80);
        }
    }

    @Nested
    @DisplayName("2. Every published width is the width its copybook declares")
    class Widths {

        @ParameterizedTest(name = "{1} is {2} characters in COTRN01, so {0} is too")
        @CsvSource({
            "TRANSACTION_NAME_LENGTH, TRNNAMEI,  4",
            "TITLE_LENGTH,            TITLE01I, 40",
            "CURRENT_DATE_LENGTH,     CURDATEI,  8",
            "PROGRAM_NAME_LENGTH,     PGMNAMEI,  8",
            "CURRENT_TIME_LENGTH,     CURTIMEI,  8",
            "TRANSACTION_ID_LENGTH,   TRNIDI,   16",
            "CARD_NUMBER_LENGTH,      CARDNUMI, 16",
            "TYPE_CODE_LENGTH,        TTYPCDI,   2",
            "CATEGORY_CODE_LENGTH,    TCATCDI,   4",
            "SOURCE_LENGTH,           TRNSRCI,  10",
            "DESCRIPTION_LENGTH,      TDESCI,   60",
            "AMOUNT_DISPLAY_LENGTH,   TRNAMTI,  12",
            "DETAIL_DATE_LENGTH,      TORIGDTI, 10",
            "MERCHANT_ID_LENGTH,      MIDI,      9",
            "MERCHANT_NAME_LENGTH,    MNAMEI,   30",
            "MERCHANT_CITY_LENGTH,    MCITYI,   25",
            "MERCHANT_ZIP_LENGTH,     MZIPI,    10",
            "ERROR_MESSAGE_LENGTH,    ERRMSGI,  78",
        })
        @DisplayName("the detail widths are read from COTRN01")
        void detailWidthsAreReadFromTheCopybook(final String constantName, final String field,
                final int expectedWidth) {
            // Three-way: the constant, the copybook and the case label must all agree. Comparing the constant
            // against the copybook alone would let a shared typo pass; comparing it against the label alone
            // would prove only that a number was typed twice.
            assertThat(constantValue(constantName))
                    .as("%s is declared %s in %s", field, DETAIL_MAP.member(), DETAIL_MAP.member())
                    .isEqualTo(DETAIL_MAP.widthOf(field))
                    .isEqualTo(expectedWidth);
        }

        @ParameterizedTest(name = "{1} is {2} characters in COTRN00, so {0} is too")
        @CsvSource({
            "SELECTION_FLAG_LENGTH,   SEL0001I,  1",
            "TRANSACTION_ID_LENGTH,   TRNID01I, 16",
            "ROW_DATE_LENGTH,         TDATE01I,  8",
            "ROW_DESCRIPTION_LENGTH,  TDESC01I, 26",
            "AMOUNT_DISPLAY_LENGTH,   TAMT001I, 12",
            "PAGE_NUMBER_LENGTH,      PAGENUMI,  8",
        })
        @DisplayName("the list widths are read from COTRN00")
        void listWidthsAreReadFromTheCopybook(final String constantName, final String field,
                final int expectedWidth) {
            assertThat(constantValue(constantName))
                    .as("%s is declared in %s", field, LIST_MAP.member())
                    .isEqualTo(LIST_MAP.widthOf(field))
                    .isEqualTo(expectedWidth);
        }

        @Test
        @DisplayName("all ten row description slots declare the same narrower width, not just the first")
        void everyRowSlotDeclaresTheSameWidth() {
            // COTRN00.CPY:90, 120, 150, 180, 210, 240, 270, 300, 330 and 360. Asserting only TDESC01I would
            // leave a generator that emitted a different width for a later row undetected.
            for (int ordinal = 1; ordinal <= TransactionDto.PAGE_SIZE; ordinal++) {
                final String field = String.format(Locale.ROOT, "TDESC%02dI", Integer.valueOf(ordinal));

                assertThat(LIST_MAP.declares(field))
                        .as("COTRN00 must declare a description for row %d", Integer.valueOf(ordinal))
                        .isTrue();
                assertThat(LIST_MAP.widthOf(field))
                        .as("row %d's description width", Integer.valueOf(ordinal))
                        .isEqualTo(TransactionDto.ROW_DESCRIPTION_LENGTH);
            }
        }

        @Test
        @DisplayName("a row narrows the description and the date but keeps the amount's full display width")
        void aRowNarrowsSomeFieldsAndNotOthers() {
            assertThat(TransactionDto.ROW_DESCRIPTION_LENGTH)
                    .as("a list row shows less description than the detail screen")
                    .isLessThan(TransactionDto.DESCRIPTION_LENGTH);
            assertThat(TransactionDto.ROW_DATE_LENGTH)
                    .as("a list row shows a shorter date than the detail screen")
                    .isLessThan(TransactionDto.DETAIL_DATE_LENGTH);

            // The amount is the exception, and it is the interesting one: the row keeps all twelve characters
            // because the mask is twelve characters wide and cannot be abbreviated without changing its shape.
            assertThat(LIST_MAP.widthOf("TAMT001I"))
                    .as("the row amount keeps the detail width, unlike the description and the date")
                    .isEqualTo(DETAIL_MAP.widthOf("TRNAMTI"))
                    .isEqualTo(TransactionDto.AMOUNT_DISPLAY_LENGTH);
        }

        @Test
        @DisplayName("the eight preamble fields are the eight the list projection emits, in copybook order")
        void thePreambleOrderMatchesTheProjection() {
            final List<String> declared = LIST_MAP.fieldNames();

            assertThat(declared)
                    .as("COTRN00 declares its fields in the order the projection must emit them")
                    .startsWith("TRNNAMEI", "TITLE01I", "CURDATEI", "PGMNAMEI", "TITLE02I", "CURTIMEI",
                            "PAGENUMI", "TRNIDINI")
                    .endsWith("ERRMSGI")
                    .hasSize(TransactionDto.LIST_FIELD_COUNT);

            assertThat(TransactionDto.LIST_PREAMBLE_FIELD_COUNT)
                    .as("the preamble runs from TRNNAMEI to TRNIDINI inclusive")
                    .isEqualTo(declared.indexOf("SEL0001I"))
                    .isEqualTo(8);
        }

        @Test
        @DisplayName("the twelve-byte terminal header is not one of the counted input fields")
        void theTerminalHeaderIsNotAnInputField() {
            // Every generated group opens with 02 FILLER PIC X(12), the terminal input/output area. It carries
            // no screen value, so it is not a DTO field, and counting it would put the inventories at 22 and
            // 60 rather than 21 and 59.
            assertThat(DETAIL_MAP.fieldNames())
                    .as("the header is FILLER and so is unnamed and uncounted")
                    .doesNotContain("FILLER")
                    .hasSize(TransactionDto.DETAIL_FIELD_COUNT);

            assertThat(TERMINAL_IO_AREA_WIDTH)
                    .as("the header is twelve bytes wide but contributes no field")
                    .isEqualTo(12);
        }

        @Test
        @DisplayName("the amount precision decomposes into nine integer digits and a scale of two")
        void theAmountPrecisionDecomposes() {
            // TRAN-AMT PIC S9(09)V99 at app/cpy/CVTRA05Y.cpy:L10 occupies bytes 133-143, eleven characters.
            assertThat(TransactionDto.AMOUNT_PRECISION)
                    .isEqualTo(TransactionDto.AMOUNT_INTEGER_DIGITS + TransactionDto.AMOUNT_SCALE)
                    .isEqualTo(11);
            assertThat(TransactionDto.AMOUNT_INTEGER_DIGITS).isEqualTo(9);
            assertThat(TransactionDto.AMOUNT_SCALE).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("3. HIGH: three description widths over one persisted field, and both screens truncate")
    class DescriptionWidths {

        @Test
        @DisplayName("the persisted width is 100, the detail width 60 and the row width 26, all distinct")
        void allThreeWidthsAreDistinct() {
            assertThat(TransactionDto.DESCRIPTION_PERSISTED_LENGTH)
                    .as("TRAN-DESC PIC X(100) at app/cpy/CVTRA05Y.cpy:L9, bytes 33-132")
                    .isEqualTo(100);
            assertThat(TransactionDto.DESCRIPTION_LENGTH)
                    .as("TDESCI PIC X(60) at app/cpy-bms/COTRN01.CPY:96")
                    .isEqualTo(DETAIL_MAP.widthOf("TDESCI"))
                    .isEqualTo(60);
            assertThat(TransactionDto.ROW_DESCRIPTION_LENGTH)
                    .as("TDESC01I PIC X(26) at app/cpy-bms/COTRN00.CPY:90")
                    .isEqualTo(LIST_MAP.widthOf("TDESC01I"))
                    .isEqualTo(26);

            // Three distinct values is the finding. Any two of them being equal would mean one screen form had
            // been widened to match the other, which is the High severity mistake this group exists to catch.
            // A mutable set is built deliberately: Set.of rejects duplicates by throwing, which would turn a
            // genuine width collision into a confusing error instead of a clean assertion failure.
            final Set<Integer> distinctWidths = new LinkedHashSet<>();
            distinctWidths.add(Integer.valueOf(TransactionDto.DESCRIPTION_PERSISTED_LENGTH));
            distinctWidths.add(Integer.valueOf(TransactionDto.DESCRIPTION_LENGTH));
            distinctWidths.add(Integer.valueOf(TransactionDto.ROW_DESCRIPTION_LENGTH));

            assertThat(distinctWidths)
                    .as("100, 60 and 26 are three widths, not one width used three times")
                    .hasSize(3)
                    .containsExactly(Integer.valueOf(100), Integer.valueOf(60), Integer.valueOf(26));
        }

        @Test
        @DisplayName("both screen widths are narrower than the persisted field, so both truncate")
        void bothScreenFormsTruncate() {
            assertThat(TransactionDto.DESCRIPTION_LENGTH)
                    .as("the detail screen loses 40 of the 100 persisted characters")
                    .isLessThan(TransactionDto.DESCRIPTION_PERSISTED_LENGTH);
            assertThat(TransactionDto.ROW_DESCRIPTION_LENGTH)
                    .as("a list row loses 74 of the 100 persisted characters")
                    .isLessThan(TransactionDto.DESCRIPTION_PERSISTED_LENGTH);
            assertThat(TransactionDto.ROW_DESCRIPTION_LENGTH)
                    .as("and the row loses more than the detail screen does")
                    .isLessThan(TransactionDto.DESCRIPTION_LENGTH);

            assertThat(TransactionDto.DESCRIPTION_PERSISTED_LENGTH - TransactionDto.DESCRIPTION_LENGTH)
                    .isEqualTo(40);
            assertThat(TransactionDto.DESCRIPTION_PERSISTED_LENGTH - TransactionDto.ROW_DESCRIPTION_LENGTH)
                    .isEqualTo(74);
        }

        @Test
        @DisplayName("the truncation is reproduced rather than corrected: each field refuses the other's width")
        void eachFieldRefusesTheOtherWidth() {
            final String sixtyCharacters = "D".repeat(TransactionDto.DESCRIPTION_LENGTH);
            final String sixtyOneCharacters = "D".repeat(TransactionDto.DESCRIPTION_LENGTH + 1);
            final String twentySixCharacters = "R".repeat(TransactionDto.ROW_DESCRIPTION_LENGTH);
            final String twentySevenCharacters = "R".repeat(TransactionDto.ROW_DESCRIPTION_LENGTH + 1);

            // The detail field takes 60 and refuses 61. Had it been widened to the persisted 100 it would take
            // 61 happily and the rendered screen would no longer match the legacy one.
            assertThat(detailWithDescription(sixtyCharacters).description()).isEqualTo(sixtyCharacters);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> detailWithDescription(sixtyOneCharacters))
                    .withMessage("description exceeds its declared COBOL width: 61 characters supplied,"
                            + " 60 permitted");

            // The row field takes 26 and refuses 27, and in particular refuses the detail screen's 60.
            assertThat(new TransactionDto.TransactionListRow(null, null, null, twentySixCharacters, null)
                    .description()).isEqualTo(twentySixCharacters);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new TransactionDto.TransactionListRow(
                            null, null, null, twentySevenCharacters, null))
                    .withMessage("description exceeds its declared COBOL width: 27 characters supplied,"
                            + " 26 permitted");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a row must not accept a detail-width description")
                    .isThrownBy(() -> new TransactionDto.TransactionListRow(
                            null, null, null, sixtyCharacters, null))
                    .withMessage("description exceeds its declared COBOL width: 60 characters supplied,"
                            + " 26 permitted");
        }

        @Test
        @DisplayName("no single component and no single width can serve both screen forms")
        void noSharedDescriptionComponentExists() {
            // The detail description and the row description are separate components on separate types, which
            // is the structural consequence of the widths differing. A shared component or a shared formatter
            // would have to pick one width and would silently corrupt the other screen.
            assertThat(recordComponentNames(TransactionDto.class))
                    .as("the detail description is a component of the outer record")
                    .contains("description");
            assertThat(recordComponentNames(TransactionDto.TransactionListRow.class))
                    .as("the row description is a separate component of the row record")
                    .contains("description");

            assertThat(TransactionDto.DESCRIPTION_LENGTH)
                    .as("and the two are bounded by different constants")
                    .isNotEqualTo(TransactionDto.ROW_DESCRIPTION_LENGTH);
        }

        @Test
        @DisplayName("merchant name and city truncate the same way, so the pattern is not unique to descriptions")
        void merchantFieldsTruncateToo() {
            // app/cpy/CVTRA05Y.cpy:L12-L13 persist 50 characters each, while app/cpy-bms/COTRN01.CPY:126 and
            // :132 declare 30 and 25. Recording this alongside the description keeps the description from
            // looking like an anomaly that might be worth "fixing".
            assertThat(TransactionDto.MERCHANT_NAME_LENGTH)
                    .isEqualTo(DETAIL_MAP.widthOf("MNAMEI"))
                    .isEqualTo(30)
                    .isLessThan(PERSISTED_MERCHANT_NAME_WIDTH);
            assertThat(TransactionDto.MERCHANT_CITY_LENGTH)
                    .isEqualTo(DETAIL_MAP.widthOf("MCITYI"))
                    .isEqualTo(25)
                    .isLessThan(PERSISTED_MERCHANT_CITY_WIDTH);
        }
    }

    @Nested
    @DisplayName("4. HIGH: the six common header fields are declared inline, because they are not common")
    class CommonHeaderIsNotShared {

        @ParameterizedTest(name = "{0} is the same width on both transaction maps")
        @CsvSource({"TRNNAMEI, 4", "TITLE01I, 40", "CURDATEI, 8", "PGMNAMEI, 8", "TITLE02I, 40"})
        @DisplayName("five of the six header fields are uniform across the maps")
        void fiveHeaderFieldsAreUniform(final String field, final int expectedWidth) {
            assertThat(DETAIL_MAP.widthOf(field))
                    .as("%s on the detail map", field)
                    .isEqualTo(expectedWidth);
            assertThat(LIST_MAP.widthOf(field))
                    .as("%s on the list map", field)
                    .isEqualTo(expectedWidth);
            assertThat(SIGN_ON_MAP.widthOf(field))
                    .as("%s on the sign-on map, which agrees on these five", field)
                    .isEqualTo(expectedWidth);
        }

        @Test
        @DisplayName("but CURTIMEI is eight characters here and nine on the sign-on map, at the same line")
        void theSixthHeaderFieldIsNotUniform() {
            // app/cpy-bms/COTRN01.CPY:54 and app/cpy-bms/COTRN00.CPY:54 declare PIC X(8); only
            // app/cpy-bms/COSGN00.CPY:54 declares PIC X(9). Identical line number, identical name, different
            // width - which is precisely how a shared header would go unnoticed until it truncated a value.
            assertThat(DETAIL_MAP.widthOf("CURTIMEI"))
                    .isEqualTo(LIST_MAP.widthOf("CURTIMEI"))
                    .isEqualTo(TransactionDto.CURRENT_TIME_LENGTH)
                    .isEqualTo(8);

            assertThat(SIGN_ON_MAP.widthOf("CURTIMEI"))
                    .as("COSGN00 is the single member that declares a ninth character")
                    .isEqualTo(9)
                    .isNotEqualTo(TransactionDto.CURRENT_TIME_LENGTH);
        }

        @Test
        @DisplayName("so this type declares all six header fields itself, inheriting none")
        void allSixHeaderFieldsAreDeclaredOnThisType() {
            // No base class, no interface, no mixin and no embedded header type: a supertype would have to fix
            // CURTIMEI at one width and would then misrepresent either this screen or the sign-on screen.
            assertThat(recordComponentNames(TransactionDto.class))
                    .as("the six header fields are components of this record, not inherited members")
                    .contains("transactionName", "title01", "currentDate", "programName", "title02",
                            "currentTime");

            assertThat(TransactionDto.class.getSuperclass())
                    .as("a record extends java.lang.Record and nothing else, so no header can be inherited")
                    .isEqualTo(Record.class);
            assertThat(TransactionDto.class.getInterfaces())
                    .as("and it implements no header-carrying interface")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("5. HIGH: the paging field is this screen's own, in both name and width")
    class PagingIsNotShared {

        @Test
        @DisplayName("this screen declares PAGENUMI at eight characters")
        void thisScreenDeclaresPageNumberAtEight() {
            assertThat(LIST_MAP.declares("PAGENUMI"))
                    .as("app/cpy-bms/COTRN00.CPY:L60")
                    .isTrue();
            assertThat(LIST_MAP.widthOf("PAGENUMI"))
                    .isEqualTo(TransactionDto.PAGE_NUMBER_LENGTH)
                    .isEqualTo(8);
        }

        @Test
        @DisplayName("the card list declares PAGENOI at three, a different name and width at the same line")
        void theCardListDivergesInNameAndWidth() {
            // app/cpy-bms/COCRDLI.CPY:L60 against app/cpy-bms/COTRN00.CPY:L60. Both are the sixty-first line
            // of their member and neither can stand in for the other.
            assertThat(CARD_LIST_MAP.declares("PAGENOI"))
                    .as("the card list names its field PAGENOI")
                    .isTrue();
            assertThat(CARD_LIST_MAP.declares("PAGENUMI"))
                    .as("and does not declare PAGENUMI at all")
                    .isFalse();
            assertThat(LIST_MAP.declares("PAGENOI"))
                    .as("just as this screen does not declare PAGENOI")
                    .isFalse();

            assertThat(CARD_LIST_MAP.widthOf("PAGENOI"))
                    .as("three characters against this screen's eight")
                    .isEqualTo(3)
                    .isNotEqualTo(TransactionDto.PAGE_NUMBER_LENGTH);
        }

        @Test
        @DisplayName("the user list happens to agree, which is a coincidence and not a shared definition")
        void theUserListAgreesByCoincidence() {
            // app/cpy-bms/COUSR00.CPY:L60 also declares PAGENUMI PIC X(8). Two members agreeing does not make
            // the field shared; the card list is the proof that no third member need agree.
            assertThat(USER_LIST_MAP.widthOf("PAGENUMI"))
                    .isEqualTo(LIST_MAP.widthOf("PAGENUMI"))
                    .isEqualTo(8);
            assertThat(USER_LIST_MAP.inputFieldCount())
                    .as("yet the two screens are otherwise different, both declaring 59 fields for "
                            + "different reasons")
                    .isEqualTo(LIST_MAP.inputFieldCount());
        }

        @Test
        @DisplayName("the page number is carried as text, matching the screen field rather than an integer")
        void thePageNumberIsCarriedAsText() {
            // PAGENUMI is PIC X(8), an alphanumeric screen field, so a blank page number is representable and
            // an unpopulated one is distinguishable from zero. Typing it as an int would lose both states.
            assertThat(recordComponentType(TransactionDto.class, "pageNumber"))
                    .isEqualTo(String.class);
            assertThat(listWithPageNumber("       1").pageNumber()).isEqualTo("       1");
            assertThat(listWithPageNumber("        ").pageNumber())
                    .as("eight spaces is a real screen state and is preserved as supplied")
                    .isEqualTo("        ")
                    .hasSize(TransactionDto.PAGE_NUMBER_LENGTH);
        }
    }

    @Nested
    @DisplayName("6. HIGH: keyset boundaries are text, and no next-page or session-state field is carried")
    class KeysetPagingAndSentinels {

        @Test
        @DisplayName("the keyset boundaries are sixteen-character identifiers, not offsets")
        void theKeysetBoundariesAreSixteenCharacterIdentifiers() {
            // app/cbl/COTRN00C.cbl:L63-L64 declares CDEMO-CT00-TRNID-FIRST and CDEMO-CT00-TRNID-LAST, both
            // PIC X(16). Paging forwards and backwards is anchored on those two identifiers, so it is keyset
            // based; an offset would need a row number, and the program declares none.
            assertThat(TransactionDto.TRANSACTION_ID_LENGTH)
                    .as("both boundary fields are as wide as a transaction identifier")
                    .isEqualTo(DETAIL_MAP.widthOf("TRNIDI"))
                    .isEqualTo(LIST_MAP.widthOf("TRNID01I"))
                    .isEqualTo(16);

            // A transaction identifier is sixteen characters of zero-padded text, so it must be typed as text:
            // parsing it as a number would drop the padding that the fixed-width record depends on.
            assertThat(recordComponentType(TransactionDto.class, "transactionId")).isEqualTo(String.class);
            assertThat(recordComponentType(TransactionDto.TransactionListRow.class, "transactionId"))
                    .isEqualTo(String.class);

            final String paddedIdentifier = "0000000000000001";
            assertThat(detailWithTransactionId(paddedIdentifier).transactionId())
                    .as("the leading zeros survive, which they would not through an integer")
                    .isEqualTo(paddedIdentifier)
                    .hasSize(TransactionDto.TRANSACTION_ID_LENGTH)
                    .startsWith("000");
        }

        @Test
        @DisplayName("the search key and the echoed identifier stay separate, as the copybook declares them")
        void theSearchKeyAndTheEchoedIdentifierStaySeparate() {
            // COTRN01 declares TRNIDINI at line 60 and TRNIDI at line 66: what the operator typed, and what
            // the record came back with. Collapsing them would lose the ability to redisplay a failed search.
            assertThat(DETAIL_MAP.declares("TRNIDINI")).isTrue();
            assertThat(DETAIL_MAP.declares("TRNIDI")).isTrue();
            assertThat(recordComponentNames(TransactionDto.class))
                    .contains("transactionIdInput", "transactionId");

            final TransactionDto searched = detailWithTransactionId("0000000000000002");
            assertThat(searched.transactionIdInput())
                    .as("the typed key is carried independently of the retrieved identifier")
                    .isNotEqualTo(searched.transactionId());
        }

        @Test
        @DisplayName("the page number the program keeps is eight digits wide, matching the screen field")
        void theProgramPageNumberIsEightDigits() {
            // app/cbl/COTRN00C.cbl:L65 declares CDEMO-CT00-PAGE-NUM PIC 9(08) and
            // app/cpy-bms/COTRN00.CPY:L60 declares PAGENUMI PIC X(8). Eight in working storage, eight on the
            // screen, so nothing is lost between them.
            assertThat(TransactionDto.PAGE_NUMBER_LENGTH)
                    .isEqualTo(LIST_MAP.widthOf("PAGENUMI"))
                    .isEqualTo(8);
        }

        @Test
        @DisplayName("HIGH: no next-page flag is carried, so the two incompatible sentinels never meet")
        void noNextPageFlagIsCarried() {
            // The two screens disagree irreconcilably on what "no next page" looks like:
            //   app/cbl/COTRN00C.cbl:L66-L68  NEXT-PAGE-FLG PIC X(01) VALUE 'N', 88 YES 'Y', 88 NO 'N'
            //   app/cbl/COCRDLIC.cbl:L242-L244 WS-CA-NEXT-PAGE-IND PIC X(1), 88 NOT-EXISTS LOW-VALUES,
            //                                  88 EXISTS 'Y'
            // 'N' against LOW-VALUES. A shared next-page abstraction would have to pick one and would then
            // misreport the other screen's state. This type resolves it by carrying no flag at all: under AAP
            // transformation rule 7 there is no server-side session state, so the sentinel is a per-request
            // decision made by the response metadata rather than a field persisted between requests.
            assertThat(recordComponentNames(TransactionDto.class))
                    .as("no component names a next-page flag under any spelling")
                    .allSatisfy(name -> assertThat(name.toLowerCase(Locale.ROOT))
                            .doesNotContain("nextpage", "nextpg", "morepages", "haspage"));
        }

        @Test
        @DisplayName("HIGH: no routing, program-context or last-map field is carried")
        void noSessionStateFieldIsCarried() {
            // app/cpy/COCOM01Y.cpy carries CDEMO-FROM-TRANID X(04) and CDEMO-TO-TRANID X(04) at L21 and L23,
            // CDEMO-FROM-PROGRAM X(08) and CDEMO-TO-PROGRAM X(08) at L22 and L24, CDEMO-PGM-CONTEXT 9(01) at
            // L29 and CDEMO-LAST-MAP and CDEMO-LAST-MAPSET at L43-L44, both PIC X(7) - seven, not eight.
            // Every one of them is pseudo-conversational plumbing with no stateless counterpart: routing is
            // the URL, and there is no screen to redisplay.
            final List<String> components = recordComponentNames(TransactionDto.class);

            assertThat(components)
                    .as("routing, re-entry and last-map state have no place on a stateless response")
                    .doesNotContain("fromTranId", "toTranId", "fromProgram", "toProgram", "programContext",
                            "pgmContext", "lastMap", "lastMapset", "reenter", "reEnter");

            // programName is retained and is a different thing: it is PGMNAMEI PIC X(8) at
            // app/cpy-bms/COTRN01.CPY:42, a display field naming the program that produced the screen, not a
            // routing instruction telling the monitor where to transfer control next.
            assertThat(components).contains("programName");
            assertThat(TransactionDto.PROGRAM_NAME_LENGTH)
                    .as("and it is eight characters, the width of the display field")
                    .isEqualTo(DETAIL_MAP.widthOf("PGMNAMEI"))
                    .isEqualTo(8);
        }

        @Test
        @DisplayName("MEDIUM: pagination state originates in working storage, not in the COMMAREA")
        void paginationOriginatesInWorkingStorage() {
            // Recorded as a Medium finding against the requirement, which attributes the page number and
            // next-page flag to app/cpy/COCOM01Y.cpy. That copybook was read in full - 47 lines - and declares
            // neither: its sixteen elementary items total 160 bytes and none of them mentions a page. The
            // fields live in each program's own WORKING-STORAGE, as app/cbl/COTRN00C.cbl:L62-L70 shows, which
            // is why they differ from screen to screen instead of being uniform.
            //
            // The consequence for this type is the one asserted above: the page number is this screen's own
            // field, sized from this screen's copybook, and not a shared communication-area value.
            assertThat(TransactionDto.PAGE_NUMBER_LENGTH)
                    .as("sized from app/cpy-bms/COTRN00.CPY:L60, the screen's own declaration")
                    .isEqualTo(LIST_MAP.widthOf("PAGENUMI"));
            assertThat(CARD_LIST_MAP.widthOf("PAGENOI"))
                    .as("a communication-area field could not have produced two different widths")
                    .isNotEqualTo(LIST_MAP.widthOf("PAGENUMI"));
        }
    }

    @Nested
    @DisplayName("7. HIGH: an eight-digit mask over a nine-digit value, and decimal money throughout")
    class AmountMaskAndPrecision {

        @Test
        @DisplayName("the mask is +99999999.99 and is exactly as wide as the screen field that holds it")
        void theMaskFillsTheScreenField() {
            // app/cbl/COTRN02C.cbl:L59 declares WS-TRAN-AMT-E PIC +99999999.99. That is a sign, eight integer
            // digits, a point and two decimals: twelve characters. app/cpy-bms/COTRN01.CPY:102 declares
            // TRNAMTI PIC X(12). The mask is not arbitrarily narrow - it is as wide as the field permits.
            assertThat(TransactionDto.AMOUNT_EDITED_MASK)
                    .isEqualTo("+99999999.99")
                    .hasSize(TransactionDto.AMOUNT_DISPLAY_LENGTH);
            assertThat(TransactionDto.AMOUNT_DISPLAY_LENGTH)
                    .isEqualTo(DETAIL_MAP.widthOf("TRNAMTI"))
                    .isEqualTo(LIST_MAP.widthOf("TAMT001I"))
                    .isEqualTo(12);

            // The mask decomposes into exactly those four parts, which is what fixes it at eight digits.
            assertThat(TransactionDto.AMOUNT_EDITED_MASK).startsWith("+");
            assertThat(TransactionDto.AMOUNT_EDITED_MASK.chars().filter(c -> c == '9').count())
                    .as("eight integer digits plus two decimal digits")
                    .isEqualTo(10L);
            assertThat(TransactionDto.AMOUNT_EDITED_MASK.indexOf('.'))
                    .as("the point sits after the eight integer digits and the sign")
                    .isEqualTo(9);
        }

        @Test
        @DisplayName("HIGH: the mask holds eight integer digits while the value holds nine, so it truncates")
        void theMaskTruncatesAndIsNotCorrected() {
            // app/cbl/COTRN02C.cbl:L58 declares WS-TRAN-AMT-N PIC S9(9)V99 and :L385-L386 moves that value
            // into the mask and straight back into the screen field. Nine digits through an eight digit mask
            // loses the most significant one, silently. This is reproduced, not repaired: the parity gates
            // compare rendered output, so widening the mask would diverge on every large amount.
            final int maskIntegerDigits = TransactionDto.AMOUNT_EDITED_MASK.indexOf('.') - 1;

            assertThat(maskIntegerDigits)
                    .as("the mask's integer digit capacity")
                    .isEqualTo(8);
            assertThat(maskIntegerDigits)
                    .as("which is one fewer than PIC S9(9)V99 can hold, and that gap is the truncation")
                    .isLessThan(TransactionDto.AMOUNT_INTEGER_DIGITS);
            assertThat(TransactionDto.AMOUNT_INTEGER_DIGITS - maskIntegerDigits).isEqualTo(1);

            // The arithmetic companion is stored at full precision even though the mask cannot render it, so
            // the truncation stays confined to the display field and never corrupts the computed value.
            final BigDecimal nineIntegerDigits = new BigDecimal("999999999.99");
            final TransactionDto rendered = detailWithAmount("+99999999.99", nineIntegerDigits);

            assertThat(rendered.amountValue())
                    .as("the value keeps all nine integer digits")
                    .isEqualByComparingTo(nineIntegerDigits);
            assertThat(rendered.amount())
                    .as("while the display field carries only what the mask can hold")
                    .hasSize(TransactionDto.AMOUNT_DISPLAY_LENGTH);
        }

        @Test
        @DisplayName("the billing path's two masks differ from each other, and are not unified with this one")
        void theBillingMasksAreNotUnified() {
            // app/cbl/COBIL00C.cbl:L55 declares WS-TRAN-AMT PIC +99999999.99 and :L56 declares
            // WS-CURR-BAL PIC +9999999999.99 - an eight digit amount mask beside a ten digit balance mask, in
            // the same working-storage section. The amount mask matches this screen's; the balance mask does
            // not, and must not be borrowed for an amount.
            final String billingAmountMask = "+99999999.99";
            final String billingBalanceMask = "+9999999999.99";

            assertThat(billingAmountMask)
                    .as("the billing amount mask is the same shape as this screen's")
                    .isEqualTo(TransactionDto.AMOUNT_EDITED_MASK);
            assertThat(billingBalanceMask)
                    .as("but the balance mask is two integer digits wider and is a different field")
                    .isNotEqualTo(TransactionDto.AMOUNT_EDITED_MASK)
                    .hasSize(TransactionDto.AMOUNT_DISPLAY_LENGTH + 2);
        }

        @Test
        @DisplayName("the persisted amount is eleven characters, so precision 11 and scale 2, never 12 and 2")
        void thePersistedAmountIsElevenCharacters() {
            // TRAN-AMT PIC S9(09)V99 at app/cpy/CVTRA05Y.cpy:L10 occupies bytes 133-143 of the 350 byte
            // record: eleven characters. The account tier is S9(10)V99 and the disclosure-group tier is
            // S9(04)V99; the three are different pictures and collapsing them onto one precision is a High
            // severity error because it silently rescales money.
            assertThat(TransactionDto.AMOUNT_PRECISION)
                    .as("precision 11, from nine integer digits and two decimals")
                    .isEqualTo(11)
                    .isNotEqualTo(12);
            assertThat(TransactionDto.AMOUNT_SCALE).isEqualTo(2);

            // The fixture corroborates the width independently: the field is sliced at eleven characters and
            // every one of the 300 records decodes, which an off-by-one width would not do.
            assertThat(FixtureLoader.AMOUNT_FIELD_WIDTH)
                    .as("the fixture loader slices TRAN-AMT at its PIC width")
                    .isEqualTo(TransactionDto.AMOUNT_PRECISION)
                    .isEqualTo(11);
        }

        @Test
        @DisplayName("money is BigDecimal at HALF_EVEN, and no float or double appears anywhere on the surface")
        void moneyIsDecimalAndNeverFloatingPoint() {
            assertThat(recordComponentType(TransactionDto.class, "amountValue")).isEqualTo(BigDecimal.class);
            assertThat(TransactionDto.AMOUNT_ROUNDING_MODE).isEqualTo(RoundingMode.HALF_EVEN);

            // Gate 6 asserts the absence of binary floating point in financial fields. A census of the whole
            // declared surface - fields, return types, parameters and constructor parameters - is what makes
            // that provable rather than merely intended.
            assertThat(ReflectionCensus.declaredSurfaceTypeNames(TransactionDto.class))
                    .as("no binary floating-point type may touch money")
                    .doesNotContain("float", "double", "java.lang.Float", "java.lang.Double");
            assertThat(ReflectionCensus.declaredSurfaceTypeNames(TransactionDto.TransactionListRow.class))
                    .doesNotContain("float", "double", "java.lang.Float", "java.lang.Double");
        }

        @Test
        @DisplayName("equal amounts are compared with compareTo, because equals also compares scale")
        void amountsAreComparedWithCompareTo() {
            // The constructor canonicalises to scale 2, so a caller supplying 194.0 and a caller supplying
            // 194.00 must be indistinguishable afterwards. BigDecimal.equals would still separate them if the
            // scales differed, which is why every money assertion here uses compareTo.
            final BigDecimal oneDecimalPlace = new BigDecimal("194.0");
            final BigDecimal twoDecimalPlaces = new BigDecimal("194.00");

            assertThat(oneDecimalPlace.equals(twoDecimalPlaces))
                    .as("the two literals are unequal objects, which is the trap this guards against")
                    .isFalse();
            assertThat(oneDecimalPlace.compareTo(twoDecimalPlaces))
                    .as("but they are the same amount of money")
                    .isZero();

            final BigDecimal canonicalised = detailWithAmount("+00000194.00", oneDecimalPlace).amountValue();

            assertThat(canonicalised)
                    .as("so the constructor normalises the scale it was given")
                    .isEqualByComparingTo(twoDecimalPlaces);
            assertThat(canonicalised.scale())
                    .as("to exactly the persisted scale")
                    .isEqualTo(TransactionDto.AMOUNT_SCALE);
        }

        @Test
        @DisplayName("a negative amount keeps its sign: nothing anywhere takes an absolute value")
        void aNegativeAmountKeepsItsSign() {
            // app/cbl/CBTRN02C.cbl:L547-L552 adds the amount to the balance, then adds it to the cycle credit
            // when it is non-negative and to the cycle debit otherwise - so the debit accumulator legitimately
            // holds negative values, which is exactly why the over-limit formula subtracts it. Normalising the
            // sign would change which transactions are rejected.
            final BigDecimal debit = new BigDecimal("-919.00");
            final TransactionDto carried = detailWithAmount("-00000919.00", debit);

            assertThat(carried.amountValue())
                    .as("the sign survives construction")
                    .isEqualByComparingTo(debit)
                    .isNegative();
            assertThat(carried.amount())
                    .as("and the display field carries the minus the mask's sign position provides")
                    .startsWith("-");

            // The magnitude bound is symmetric, so the extreme negative is accepted just as the extreme
            // positive is, and neither is folded onto the other.
            final BigDecimal largestNegative = new BigDecimal("-999999999.99");
            assertThat(detailWithAmount("-99999999.99", largestNegative).amountValue())
                    .isEqualByComparingTo(largestNegative)
                    .isNegative();
        }

        @Test
        @DisplayName("a magnitude beyond nine integer digits is refused, with the message naming the picture")
        void anOversizedMagnitudeIsRefused() {
            final BigDecimal tenIntegerDigits = new BigDecimal("1000000000.00");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> detailWithAmount("+99999999.99", tenIntegerDigits))
                    .withMessage("amountValue exceeds the 9 integer digits permitted by PIC S9(09)V99")
                    .withNoCause();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("and symmetrically for the negative of the same magnitude")
                    .isThrownBy(() -> detailWithAmount("-99999999.99", tenIntegerDigits.negate()))
                    .withMessage("amountValue exceeds the 9 integer digits permitted by PIC S9(09)V99");
        }
    }

    @Nested
    @DisplayName("8. HIGH: timestamps are text in three shapes, generated from an injected clock")
    class Timestamps {

        @Test
        @DisplayName("the persisted timestamp is 26 characters and the screen field is 10, both text")
        void timestampsAreTextAtTwoWidths() {
            // TRAN-ORIG-TS and TRAN-PROC-TS are PIC X(26) at app/cpy/CVTRA05Y.cpy:L16-L17, bytes 279-304 and
            // 305-330. The screen shows only a date: TORIGDTI and TPROCDTI are PIC X(10) at
            // app/cpy-bms/COTRN01.CPY:108 and :114.
            assertThat(TransactionDto.PERSISTED_TIMESTAMP_LENGTH)
                    .isEqualTo(FixedClockProvider.TIMESTAMP_LENGTH)
                    .isEqualTo(26);
            assertThat(TransactionDto.DETAIL_DATE_LENGTH)
                    .isEqualTo(DETAIL_MAP.widthOf("TORIGDTI"))
                    .isEqualTo(DETAIL_MAP.widthOf("TPROCDTI"))
                    .isEqualTo(10);

            // Both are carried as text. A temporal type could not hold the blank state the fixture actually
            // contains, and would re-render its own canonical form instead of the corpus's.
            assertThat(recordComponentType(TransactionDto.class, "originatingDate")).isEqualTo(String.class);
            assertThat(recordComponentType(TransactionDto.class, "processingDate")).isEqualTo(String.class);
            assertThat(recordComponentType(TransactionDto.TransactionListRow.class, "transactionDate"))
                    .isEqualTo(String.class);
        }

        @Test
        @DisplayName("HIGH: no temporal type appears anywhere on either type's surface")
        void noTemporalTypeAppearsOnTheSurface() {
            assertThat(ReflectionCensus.declaredSurfaceTypeNames(TransactionDto.class))
                    .as("a timestamp modelled as a temporal type stops being the corpus's 26 characters")
                    .doesNotContain("java.time.LocalDateTime", "java.time.LocalDate", "java.time.Instant",
                            "java.time.OffsetDateTime", "java.time.ZonedDateTime", "java.sql.Timestamp",
                            "java.util.Date");
            assertThat(ReflectionCensus.declaredSurfaceTypeNames(TransactionDto.TransactionListRow.class))
                    .doesNotContain("java.time.LocalDateTime", "java.time.LocalDate", "java.time.Instant",
                            "java.sql.Timestamp", "java.util.Date");
        }

        @Test
        @DisplayName("the online shape puts a space at byte 11, a point at byte 20 and zeros in 21-26")
        void theOnlineShapeIsSpaceSeparated() {
            // app/cbl/COBIL00C.cbl:L249-L267 formats the date into WS-TIMESTAMP(01:10) and the time into
            // (12:08), then moves ZEROS into the microsecond field. Byte 11 is never written by either move, so
            // it keeps the value the receiving layout declares for it: app/cpy/CSDAT01Y.cpy:L48 declares
            // FILLER PIC X(01) VALUE ' ', and :L54 declares the point at byte 20.
            final String online = FixedClockProvider.onlineTimestamp(FixedClockProvider.canonicalClock());

            assertThat(online)
                    .hasSize(FixedClockProvider.TIMESTAMP_LENGTH)
                    .isEqualTo(FixedClockProvider.CANONICAL_ONLINE_TIMESTAMP);
            assertThat(online.charAt(10))
                    .as("byte 11 of the online shape is a space")
                    .isEqualTo(' ');
            assertThat(online.charAt(19))
                    .as("byte 20 is the decimal point")
                    .isEqualTo('.');
            assertThat(online.substring(20))
                    .as("bytes 21-26 are the literal zeros MOVE ZEROS produces")
                    .isEqualTo("000000");
        }

        @Test
        @DisplayName("the batch shape puts a dash at byte 11 and ends in hundredths plus four literal zeros")
        void theBatchShapeIsDashSeparated() {
            // app/cbl/CBACT04C.cbl:L623 reads MOVE '-' TO DB2-STREEP-1 DB2-STREEP-2 DB2-STREEP-3 - three
            // dashes, so the separator between the day and the hour is a dash where the online shape has a
            // space. app/cbl/CBTRN02C.cbl:L173-L174 declares DB2-MIL PIC 9(002), hundredths, followed by
            // DB2-REST PIC X(04) which :L622 fills with the literal '0000'. The source's own comment at
            // app/cbl/CBTRN02C.cbl:L149 spells the result EEEE-MM-DD-UU.MM.SS.HH0000.
            final String batch = FixedClockProvider.batchTimestamp(FixedClockProvider.canonicalClock());

            assertThat(batch)
                    .hasSize(FixedClockProvider.TIMESTAMP_LENGTH)
                    .isEqualTo(FixedClockProvider.CANONICAL_BATCH_TIMESTAMP);
            assertThat(batch.charAt(10))
                    .as("byte 11 of the batch shape is a dash, not a space")
                    .isEqualTo('-');
            assertThat(batch.substring(22))
                    .as("the fraction is hundredths then four literal zeros, so it always ends in 0000")
                    .isEqualTo("0000");
            assertThat(batch)
                    .as("nanosecond formatting would produce nine fractional digits and no trailing zeros")
                    .doesNotContain("53.000000000");
        }

        @Test
        @DisplayName("the two generated shapes differ in their three separators only, never in their digits")
        void theTwoGeneratedShapesDifferOnlyInSeparators() {
            // Rendering one instant both ways isolates exactly what the two paths disagree about. The online
            // path asks CICS for DATESEP('-') and TIMESEP(':') at app/cbl/COBIL00C.cbl:L258 and :L260 and
            // leaves byte 11 as the space its receiving layout declares. The batch path writes its own
            // separators: MOVE '-' TO DB2-STREEP-1 DB2-STREEP-2 DB2-STREEP-3 at app/cbl/CBACT04C.cbl:L623 puts
            // a dash where the online shape has a space, and MOVE '.' TO DB2-DOT-1 DB2-DOT-2 DB2-DOT-3 at :L624
            // puts dots where the online shape has colons. Three separator positions, no digit positions.
            final String online = FixedClockProvider.onlineTimestamp(FixedClockProvider.canonicalClock());
            final String batch = FixedClockProvider.batchTimestamp(FixedClockProvider.canonicalClock());
            final List<Integer> differingBytes = new ArrayList<>();
            for (int index = 0; index < FixedClockProvider.TIMESTAMP_LENGTH; index++) {
                if (online.charAt(index) != batch.charAt(index)) {
                    differingBytes.add(Integer.valueOf(index + 1));
                }
            }

            assertThat(differingBytes)
                    .as("byte 11 is the day-to-hour separator; bytes 14 and 17 are the two time separators")
                    .containsExactly(Integer.valueOf(11), Integer.valueOf(14), Integer.valueOf(17));

            assertThat(online.charAt(13)).isEqualTo(':');
            assertThat(batch.charAt(13))
                    .as("the batch shape separates hours from minutes with a dot, not a colon")
                    .isEqualTo('.');
            assertThat(online.charAt(16)).isEqualTo(':');
            assertThat(batch.charAt(16))
                    .as("and minutes from seconds likewise")
                    .isEqualTo('.');

            // Byte 20 is a dot in both, so the fractional separator is the one the two paths agree on.
            assertThat(online.charAt(19))
                    .as("both shapes precede the fraction with a dot")
                    .isEqualTo(batch.charAt(19))
                    .isEqualTo('.');

            // Both layouts place their separators at the same six positions, so the remaining twenty are digit
            // positions in both. Those twenty coincide exactly, which is what proves the two renderings are one
            // instant expressed twice rather than two readings of a clock.
            assertThat(digitsOnly(online))
                    .as("the twenty digit positions are identical in both shapes")
                    .isEqualTo(digitsOnly(batch))
                    .isEqualTo("20220610192753000000")
                    .hasSize(20);
        }

        @Test
        @DisplayName("all three shapes share their first ten characters, which is the screen field's width")
        void allThreeShapesShareTheirFirstTenCharacters() {
            // This is why a ten character screen field can display a date drawn from any of the three paths:
            // they agree on yyyy-MM-dd and only then diverge. It is also why the screen field cannot be used to
            // tell them apart.
            final String online = FixedClockProvider.onlineTimestamp(FixedClockProvider.canonicalClock());
            final String batch = FixedClockProvider.batchTimestamp(FixedClockProvider.canonicalClock());
            final String passedThrough = FixedClockProvider.passThroughTimestamp(
                    FixedClockProvider.CANONICAL_ONLINE_TIMESTAMP);
            final int screenWidth = TransactionDto.DETAIL_DATE_LENGTH;

            assertThat(online.substring(0, screenWidth))
                    .isEqualTo(batch.substring(0, screenWidth))
                    .isEqualTo(passedThrough.substring(0, screenWidth))
                    .isEqualTo("2022-06-10");

            // And that common prefix is what the detail screen actually carries.
            assertThat(detailWithDate(online.substring(0, screenWidth)).originatingDate())
                    .isEqualTo("2022-06-10")
                    .hasSize(screenWidth);
        }

        @Test
        @DisplayName("the pass-through shape formats nothing, and a 26-space value round-trips unchanged")
        void thePassThroughShapeRoundTripsBlanks() {
            // app/cbl/COTRN02C.cbl:L454-L455 moves the screen's source and description into the record and
            // :L464-L465 moves TORIGDTI and TPROCDTI into TRAN-ORIG-TS and TRAN-PROC-TS - alphanumeric moves
            // with no formatting whatever. The fixture proves the blank case is real: bytes 305-330 are 26
            // spaces on all 300 records, because nothing has been posted yet.
            final String blankTimestamp = " ".repeat(FixedClockProvider.TIMESTAMP_LENGTH);

            assertThat(FixedClockProvider.passThroughTimestamp(blankTimestamp))
                    .as("26 spaces in, 26 spaces out - not trimmed, not normalised, not rejected")
                    .isEqualTo(blankTimestamp)
                    .hasSize(FixedClockProvider.TIMESTAMP_LENGTH)
                    .isBlank();

            // A generated shape passes through unchanged too, since it already fills the receiving field.
            assertThat(FixedClockProvider.passThroughTimestamp(
                    FixedClockProvider.CANONICAL_BATCH_TIMESTAMP))
                    .isEqualTo(FixedClockProvider.CANONICAL_BATCH_TIMESTAMP);

            // The screen-width blank is likewise carried rather than collapsed to null or to empty.
            assertThat(detailWithDate("          ").originatingDate())
                    .isEqualTo("          ")
                    .hasSize(TransactionDto.DETAIL_DATE_LENGTH);
        }

        @Test
        @DisplayName("the clock is injected, so the same instant always renders the same text")
        void theClockIsInjected() {
            // Determinism is the point: nothing here reads a wall clock, so no assertion can pass today and
            // fail tomorrow. A second, unrelated instant is rendered to show the formatting is a function of
            // its input rather than of the canonical constant.
            final Clock canonical = FixedClockProvider.canonicalClock();

            assertThat(FixedClockProvider.onlineTimestamp(canonical))
                    .as("rendering the same fixed clock twice gives the same text")
                    .isEqualTo(FixedClockProvider.onlineTimestamp(canonical));
            assertThat(canonical.instant()).isEqualTo(FixedClockProvider.CANONICAL_INSTANT);

            final Clock newYear = FixedClockProvider.fixedClock(Instant.parse("2023-01-01T00:00:00Z"));

            assertThat(FixedClockProvider.onlineTimestamp(newYear))
                    .isEqualTo("2023-01-01 00:00:00.000000");
            assertThat(FixedClockProvider.batchTimestamp(newYear))
                    .as("the same instant in the batch shape differs only at byte 11")
                    .isEqualTo("2023-01-01-00.00.00.000000");
        }
    }

    @Nested
    @DisplayName("9. BLOCKER: the source field is free text, never a closed enumeration")
    class SourceIsFreeText {

        @Test
        @DisplayName("the source component is a String and is not an enum type")
        void theSourceComponentIsAString() {
            // TRAN-SOURCE PIC X(10) at app/cpy/CVTRA05Y.cpy:L8, bytes 23-32, and TRNSRCI PIC X(10) at
            // app/cpy-bms/COTRN01.CPY:90. An alphanumeric picture is free text; nothing in the corpus
            // constrains it to a value set.
            assertThat(recordComponentType(TransactionDto.class, "source"))
                    .as("the source must be carried as text")
                    .isEqualTo(String.class);
            assertThat(recordComponentType(TransactionDto.class, "source").isEnum())
                    .as("an enum-typed source would be unable to represent values the corpus contains")
                    .isFalse();
            assertThat(TransactionDto.SOURCE_LENGTH)
                    .isEqualTo(DETAIL_MAP.widthOf("TRNSRCI"))
                    .isEqualTo(PERSISTED_SOURCE_WIDTH)
                    .isEqualTo(10);
        }

        @Test
        @DisplayName("BLOCKER: the fixture carries a source value no program literal contains")
        void theFixtureCarriesAValueNoProgramLiteralContains() {
            // The two literals the corpus assigns are 'System' at app/cbl/CBACT04C.cbl:L484 and 'POS TERM' at
            // app/cbl/COBIL00C.cbl:L222. The fixture carries POS TERM on 250 of its 300 records and OPERATOR
            // on the other 50 - and OPERATOR appears in no program literal anywhere in app/cbl. A closed
            // enumeration built from the program literals would therefore reject one record in six of the real
            // data, which is why this is a Blocker rather than a style preference.
            final Set<String> observedSources = new LinkedHashSet<>();
            for (int record = 0; record < DAILY_TRANSACTIONS.recordCount(); record++) {
                observedSources.add(DAILY_TRANSACTIONS.field(record, SOURCE_COLUMN, PERSISTED_SOURCE_WIDTH));
            }

            assertThat(observedSources)
                    .as("the frozen fixture's complete source vocabulary")
                    .containsExactlyInAnyOrder("POS TERM  ", "OPERATOR  ");
            assertThat(countRecordsWithSource("OPERATOR  "))
                    .as("OPERATOR is not a stray record; it is a sixth of the fixture")
                    .isEqualTo(50);
            assertThat(countRecordsWithSource("POS TERM  "))
                    .isEqualTo(250);
            assertThat(countRecordsWithSource("OPERATOR  ") + countRecordsWithSource("POS TERM  "))
                    .isEqualTo(FIXTURE_RECORD_COUNT);
        }

        @Test
        @DisplayName("all three corpus source values round-trip byte-exactly at ten characters")
        void allThreeCorpusSourceValuesRoundTrip() {
            // 'System' and 'POS TERM' are shorter than the field, so they arrive space-padded to ten; OPERATOR
            // likewise. The padding is part of the value and is preserved rather than trimmed.
            final List<String> corpusSources = List.of("System    ", "POS TERM  ", "OPERATOR  ");

            assertThat(corpusSources).allSatisfy(source -> {
                assertThat(source)
                        .as("every corpus source value fills the ten character field exactly")
                        .hasSize(TransactionDto.SOURCE_LENGTH);
                assertThat(detailWithSource(source).source())
                        .as("and is carried through unaltered, trailing spaces included")
                        .isEqualTo(source);
            });

            // Trimming would make the three indistinguishable from their unpadded forms, which the fixed-width
            // record cannot tolerate.
            assertThat(detailWithSource("System    ").source())
                    .isNotEqualTo("System")
                    .endsWith("    ");
        }

        @Test
        @DisplayName("an unrecognised source value is accepted, because the field is not validated by a list")
        void anUnrecognisedSourceValueIsAccepted() {
            // Whatever a future channel writes into ten alphanumeric bytes is a legal value. The only
            // constraint the corpus imposes is the width, so that is the only constraint enforced.
            assertThat(detailWithSource("BATCH JOB ").source()).isEqualTo("BATCH JOB ");
            assertThat(detailWithSource("          ").source())
                    .as("ten spaces is a legal value too")
                    .isEqualTo("          ");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("only the width is enforced, and it is enforced exactly")
                    .isThrownBy(() -> detailWithSource("ELEVEN CHAR"))
                    .withMessage("source exceeds its declared COBOL width: 11 characters supplied,"
                            + " 10 permitted")
                    .withNoCause();
        }
    }

    @Nested
    @DisplayName("10. BLOCKER: no primary account number and no merchant detail in any rendering")
    class DiagnosticsCarryNoSensitiveData {

        @Test
        @DisplayName("BLOCKER: a real fixture card number never reaches toString")
        void aRealFixtureCardNumberNeverReachesToString() {
            // Asserted with an actual primary account number lifted from app/data/ASCII/dailytran.txt at
            // bytes 263-278 rather than an invented literal, so this proves the omission against the shape of
            // data the system genuinely carries. The number itself is never placed in an assertion message;
            // failures are identified by transaction identifier.
            final String realCardNumber =
                    DAILY_TRANSACTIONS.field(0, CARD_NUMBER_COLUMN, TransactionDto.CARD_NUMBER_LENGTH);
            final String transactionId = DAILY_TRANSACTIONS.field(0, 1, TransactionDto.TRANSACTION_ID_LENGTH);
            final TransactionDto carrying = detailWithCardNumber(realCardNumber, transactionId);

            assertThat(realCardNumber)
                    .as("the fixture's card number fills the sixteen character field")
                    .hasSize(TransactionDto.CARD_NUMBER_LENGTH);
            assertThat(carrying.toString())
                    .as("the rendering of transaction %s must not disclose its card number", transactionId)
                    .doesNotContain(realCardNumber)
                    .isEqualTo("TransactionDto[transactionId=" + transactionId
                            + ", programName=COTRN01C, protectedFieldsOmitted=true]");

            // Interpolation is the realistic exposure route, since a log statement concatenates rather than
            // calling toString explicitly.
            assertThat("transaction lookup failed for " + carrying)
                    .as("interpolating transaction %s must not disclose its card number either", transactionId)
                    .doesNotContain(realCardNumber);
        }

        @Test
        @DisplayName("BLOCKER: merchant name, city and ZIP are omitted from the rendering as well")
        void merchantDetailIsOmittedFromTheRendering() {
            // The merchant fields locate a cardholder's transaction geographically, so they are treated as
            // sensitive alongside the card number. Values are taken from the fixture so that a real ZIP+4 form
            // is exercised rather than a tidy five digit invention.
            final String merchantName =
                    DAILY_TRANSACTIONS.field(0, MERCHANT_NAME_COLUMN, TransactionDto.MERCHANT_NAME_LENGTH);
            final String merchantZip =
                    DAILY_TRANSACTIONS.field(0, MERCHANT_ZIP_COLUMN, TransactionDto.MERCHANT_ZIP_LENGTH);
            final TransactionDto carrying = detailWithMerchant(merchantName, "SEATTLE", merchantZip);
            final String rendered = carrying.toString();

            assertThat(rendered)
                    .doesNotContain(merchantName.strip())
                    .doesNotContain("SEATTLE")
                    .doesNotContain(merchantZip.strip());

            // The values remain readable through their accessors, so nothing has been lost - only the default
            // rendering is narrowed.
            assertThat(carrying.merchantName()).isEqualTo(merchantName);
            assertThat(carrying.merchantCity()).isEqualTo("SEATTLE");
            assertThat(carrying.merchantZip()).isEqualTo(merchantZip);
        }

        @Test
        @DisplayName("the rendering says fields were omitted, so a reader is not misled into reading absence")
        void theRenderingDeclaresItsOwnOmissions() {
            assertThat(detail().toString())
                    .as("a narrowed rendering that looked complete would suggest the other fields were empty")
                    .contains("protectedFieldsOmitted=true");
        }

        @Test
        @DisplayName("a row rendering carries the identifier only, not its description or amount")
        void aRowRenderingCarriesTheIdentifierOnly() {
            final TransactionDto.TransactionListRow populated = new TransactionDto.TransactionListRow(
                    "S", "0000000000000001", "06/10/22", "GROCERIES", "+00000194.00");

            assertThat(populated.toString())
                    .isEqualTo("TransactionDto.TransactionListRow[transactionId=0000000000000001]")
                    .doesNotContain("GROCERIES")
                    .doesNotContain("+00000194.00");

            // A populated page renders through its rows, so the same guarantee has to hold for the whole list.
            assertThat(listWith(TransactionDto.PAGE_SIZE).toString())
                    .as("rendering a full page must not expand its rows")
                    .doesNotContain("GROCERIES");
        }

        @Test
        @DisplayName("neither type carries a password, a hash, a national identifier, a phone or a birth date")
        void neitherTypeCarriesAnyOtherSensitiveField() {
            // The transaction maps declare no such field: app/cpy-bms/COTRN01.CPY and COTRN00.CPY have no
            // credential field at all, whereas app/cpy-bms/COSGN00.CPY:78 does declare PASSWDI PIC X(8). The
            // contrast is the point - a credential field is a real thing in this corpus, just not on these
            // screens, so its absence here is a deliberate property worth asserting.
            assertThat(SIGN_ON_MAP.declares("PASSWDI"))
                    .as("the sign-on map does declare a credential field")
                    .isTrue();
            assertThat(DETAIL_MAP.declares("PASSWDI")).isFalse();
            assertThat(LIST_MAP.declares("PASSWDI")).isFalse();

            assertThat(recordComponentNames(TransactionDto.class))
                    .allSatisfy(name -> assertThat(name.toLowerCase(Locale.ROOT))
                            .doesNotContain("password", "passwd", "secret", "token", "credential", "hash",
                                    "ssn", "socialsecurity", "phone", "dateofbirth", "dob"));
            assertThat(recordComponentNames(TransactionDto.TransactionListRow.class))
                    .allSatisfy(name -> assertThat(name.toLowerCase(Locale.ROOT))
                            .doesNotContain("password", "secret", "token", "ssn", "phone", "dob"));
        }

        @Test
        @DisplayName("equality is the record's own value semantics, and discloses nothing textually")
        void equalityDisclosesNothing() {
            // Neither type overrides equals or hashCode, so both use the record's generated value semantics
            // over every component, the card number included. That is the correct behaviour for a value type
            // and it leaks nothing: a boolean and an int cannot carry a primary account number. The disclosure
            // risk is in rendering, and that is closed by toString above.
            final String realCardNumber =
                    DAILY_TRANSACTIONS.field(1, CARD_NUMBER_COLUMN, TransactionDto.CARD_NUMBER_LENGTH);
            final TransactionDto first = detailWithCardNumber(realCardNumber, "0000000000000003");
            final TransactionDto same = detailWithCardNumber(realCardNumber, "0000000000000003");
            final TransactionDto differentCard =
                    detailWithCardNumber("0000000000000000", "0000000000000003");

            assertThat(first)
                    .as("two instances built from the same values are equal")
                    .isEqualTo(same)
                    .hasSameHashCodeAs(same);
            assertThat(first)
                    .as("and the card number participates, as a value type requires")
                    .isNotEqualTo(differentCard);

            // The record's equality members are compiler generated, so reflection reports them as declared
            // alongside the hand-written toString and cannot tell the two apart. The distinction that matters
            // is therefore behavioural rather than structural, and it is asserted above: equality spans every
            // component, while the rendering spans three. What reflection can establish is that both members
            // return a primitive, so neither has any way to emit a card number in the first place.
            assertThat(ReflectionCensus.declaredMethodNames(TransactionDto.class))
                    .as("all three of the record's Object overrides are present")
                    .contains("toString", "equals", "hashCode");
            assertThat(ReflectionCensus.declaredSurfaceTypeNames(TransactionDto.class))
                    .as("equals returns boolean and hashCode returns int, neither of which can carry text")
                    .contains("boolean", "int");
        }
    }

    @Nested
    @DisplayName("11. The list projection's shape is fixed by the screen, never by the data")
    class ListProjectionShape {

        @ParameterizedTest(name = "with {0} populated rows the projection is still 59 entries")
        @ValueSource(ints = {0, 1, 5, 9, 10})
        @DisplayName("the projection width never varies with how many rows were found")
        void theProjectionWidthNeverVaries(final int populatedRows) {
            // A 3270 screen has ten row slots whether or not the browse filled them, so a short page still
            // sends every field. A projection that shortened itself would change the screen's geometry.
            assertThat(listWith(populatedRows).listProjection())
                    .as("%d populated rows still occupy the whole screen", Integer.valueOf(populatedRows))
                    .hasSize(TransactionDto.LIST_FIELD_COUNT)
                    .hasSize(LIST_MAP.inputFieldCount());
        }

        @Test
        @DisplayName("unpopulated slots project as nulls, which is what a blank screen row is")
        void unpopulatedSlotsProjectAsNull() {
            final List<String> projection = listWith(1).listProjection();

            // Rows two to ten are unpopulated. Their five fields each must still appear, as nulls.
            assertThat(projection).hasSize(TransactionDto.LIST_FIELD_COUNT);
            assertThat(projection.subList(
                    TransactionDto.LIST_PREAMBLE_FIELD_COUNT + TransactionDto.LIST_ROW_FIELD_COUNT,
                    TransactionDto.LIST_FIELD_COUNT - TransactionDto.LIST_TRAILER_FIELD_COUNT))
                    .as("the nine unpopulated rows contribute 45 null entries")
                    .hasSize(45)
                    .containsOnlyNulls();
        }

        @Test
        @DisplayName("the detail projection carries exactly the 21 fields COTRN01 declares")
        void theDetailProjectionCarriesTwentyOneFields() {
            assertThat(detail().detailProjection())
                    .hasSize(TransactionDto.DETAIL_FIELD_COUNT)
                    .hasSize(DETAIL_MAP.inputFieldCount());
        }

        @Test
        @DisplayName("both projections are unmodifiable, so a caller cannot reshape a screen")
        void bothProjectionsAreUnmodifiable() {
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .as("the list projection is a fixed screen shape")
                    .isThrownBy(() -> listWith(2).listProjection().add("intruder"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .as("and so is the detail projection")
                    .isThrownBy(() -> detail().detailProjection().add("intruder"));
        }
    }

    @Nested
    @DisplayName("12. Row addressing is bounded by the screen, not by the data")
    class RowAddressing {

        @ParameterizedTest(name = "slot {0} is addressable")
        @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9})
        @DisplayName("every slot the screen declares is addressable")
        void everyScreenSlotIsAddressable(final int slot) {
            assertThat(listWith(TransactionDto.PAGE_SIZE).rowAt(slot))
                    .as("slot %d of a full page", Integer.valueOf(slot))
                    .isNotNull();
        }

        @Test
        @DisplayName("a slot within the screen but beyond the data yields null rather than throwing")
        void anUnpopulatedSlotYieldsNull() {
            // Reaching row eight of a three row page is an ordinary screen state, not an error.
            assertThat(listWith(3).rowAt(7)).isNull();
        }

        @ParameterizedTest(name = "slot {0} does not exist on the screen")
        @ValueSource(ints = {-1, 10, 11, 20})
        @DisplayName("a slot the screen does not have is refused, naming the bound")
        void aNonexistentSlotIsRefused(final int slot) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> listWith(TransactionDto.PAGE_SIZE).rowAt(slot))
                    .withMessage("slot must be at least 0 and less than 10 because the transaction list screen"
                            + " declares that many row slots, but was " + slot)
                    .withNoCause();
        }

        @Test
        @DisplayName("more rows than the screen has slots is refused, naming the count and the bound")
        void tooManyRowsIsRefused() {
            final List<TransactionDto.TransactionListRow> eleven = new ArrayList<>();
            for (int ordinal = 1; ordinal <= TransactionDto.PAGE_SIZE + 1; ordinal++) {
                eleven.add(row(ordinal));
            }

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> build("01", null, null, eleven))
                    .withMessage("rows holds 11 elements, which exceeds the 10 row slots the transaction list"
                            + " screen declares")
                    .withNoCause();
        }

        @Test
        @DisplayName("a null row is refused, naming the index, because a blank row is a row of nulls")
        void aNullRowIsRefused() {
            final List<TransactionDto.TransactionListRow> withHole = new ArrayList<>();
            withHole.add(row(1));
            withHole.add(null);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a blank screen row is a row whose fields are null, not an absent row")
                    .isThrownBy(() -> build("01", null, null, withHole))
                    .withMessage("rows must not contain a null element, but index 1 was null")
                    .withNoCause();
        }

        @Test
        @DisplayName("the row list is unmodifiable, so a page cannot be grown after construction")
        void theRowListIsUnmodifiable() {
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> listWith(2).rows().add(row(3)));
        }

        @Test
        @DisplayName("mutating the caller's list afterwards does not change the constructed page")
        void theRowsAreDefensivelyCopied() {
            final List<TransactionDto.TransactionListRow> caller = new ArrayList<>();
            caller.add(row(1));
            final TransactionDto page = build("01", null, null, caller);

            caller.add(row(2));

            assertThat(page.rows())
                    .as("the page took a copy, so the later addition is invisible to it")
                    .hasSize(1);
        }
    }

    @Nested
    @DisplayName("13. HIGH: absent, blank and populated are three distinct states, never collapsed")
    class TriStateAndBoundaries {

        @Test
        @DisplayName("null and blank remain distinguishable on every text field")
        void nullAndBlankRemainDistinguishable() {
            // app/cpy/CSSETATY.cpy is a COPY ... REPLACING template over the PROCEDURE DIVISION - it declares
            // no data at all, which is why it maps to no class - and it models exactly three states:
            //   IF (FLG-(TESTVAR1)-NOT-OK OR FLG-(TESTVAR1)-BLANK) AND CDEMO-PGM-REENTER
            // with an inner IF FLG-(TESTVAR1)-BLANK that writes '*'. NOT-OK and BLANK take different screen
            // treatment, so a field that was never supplied and a field supplied as spaces cannot be folded
            // together without losing which marker the screen would have shown.
            assertThat(detailWithSource(null).source())
                    .as("never supplied stays null")
                    .isNull();
            assertThat(detailWithSource("").source())
                    .as("supplied as empty stays empty, and is not null")
                    .isNotNull()
                    .isEmpty();
            assertThat(detailWithSource("          ").source())
                    .as("supplied as ten spaces stays ten spaces, and is neither null nor empty")
                    .isEqualTo("          ")
                    .isNotEmpty()
                    .isBlank();
        }

        @Test
        @DisplayName("an absent row array is not an empty one, mirroring detail against an empty page")
        void anAbsentRowArrayIsNotAnEmptyOne() {
            // A detail response has no row array; a list response that found nothing has an empty one. The
            // difference is "this screen has no rows" against "this screen's rows are all blank", and it is
            // the same distinction the 'N' and LOW-VALUES sentinels draw at app/cbl/COTRN00C.cbl:L66 and
            // app/cbl/COCRDLIC.cbl:L243 - two ways of saying nothing follows, deliberately not unified.
            assertThat(detail().rows())
                    .as("a detail response carries no row array at all")
                    .isNull();
            assertThat(listWith(0).rows())
                    .as("an empty page carries an array with no entries, which is a different answer")
                    .isNotNull()
                    .isEmpty();
        }

        @Test
        @DisplayName("a row of nulls is accepted, because that is precisely a blank screen row")
        void aRowOfNullsIsAccepted() {
            final TransactionDto.TransactionListRow blank =
                    new TransactionDto.TransactionListRow(null, null, null, null, null);

            assertThat(blank.selectionFlag()).isNull();
            assertThat(blank.transactionId()).isNull();
            assertThat(blank.transactionDate()).isNull();
            assertThat(blank.description()).isNull();
            assertThat(blank.amount()).isNull();
        }

        @ParameterizedTest(name = "{0} accepts its exact width and refuses one character more")
        @CsvSource({
            "transactionName,  4",
            "currentDate,      8",
            "typeCode,         2",
            "categoryCode,     4",
            "source,          10",
            "description,     60",
            "merchantId,       9",
            "merchantName,    30",
            "merchantCity,    25",
            "merchantZip,     10",
            "errorMessage,    78",
        })
        @DisplayName("every field accepts exactly its declared width and refuses one over")
        void everyFieldIsBoundedAtItsDeclaredWidth(final String component, final int width) {
            final String exact = "X".repeat(width);
            final String oneOver = "X".repeat(width + 1);

            assertThat(componentValue(detailWithComponent(component, exact), component))
                    .as("%s accepts its full declared width", component)
                    .isEqualTo(exact);
            assertThat(componentValue(detailWithComponent(component, "X".repeat(width - 1)), component))
                    .as("%s accepts one under, since a short screen value is legal", component)
                    .hasSize(width - 1);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("%s refuses one character over", component)
                    .isThrownBy(() -> detailWithComponent(component, oneOver))
                    .withMessage(component + " exceeds its declared COBOL width: " + (width + 1)
                            + " characters supplied, " + width + " permitted")
                    .withNoCause();
        }

        @Test
        @DisplayName("a ZIP+4 is accepted, because the fixture carries both that form and a bare five digits")
        void aZipPlusFourIsAccepted() {
            // The fixture's merchant ZIPs occur in both forms, so neither a digits-only nor a fixed-length
            // constraint may be imposed on the field.
            assertThat(detailWithMerchant("MERCHANT", "SEATTLE", "03491-5716").merchantZip())
                    .as("the hyphenated ZIP+4 form is legal")
                    .isEqualTo("03491-5716")
                    .hasSize(TransactionDto.MERCHANT_ZIP_LENGTH);
            assertThat(detailWithMerchant("MERCHANT", "SEATTLE", "72112     ").merchantZip())
                    .as("and so is a five digit ZIP padded to the field width")
                    .isEqualTo("72112     ");
        }
    }

    @Nested
    @DisplayName("14. The frozen fixture corroborates the record layout independently of the copybooks")
    class FixtureParity {

        @Test
        @DisplayName("the fixture's geometry is 300 records of 350 characters, 105300 bytes with terminators")
        void theFixtureGeometryIsIntact() {
            // 105300 == 300 x 351. The arithmetic is what detects a reflow: trimming trailing spaces, adding
            // carriage returns or dropping the final line feed all break at least one of these three.
            assertThat(DAILY_TRANSACTIONS.recordCount()).isEqualTo(FIXTURE_RECORD_COUNT);
            assertThat(DAILY_TRANSACTIONS.recordWidth())
                    .as("the 350 byte transaction record of app/cpy/CVTRA05Y.cpy")
                    .isEqualTo(350);
            assertThat(DAILY_TRANSACTIONS.byteCount())
                    .isEqualTo(DAILY_TRANSACTIONS.impliedByteCount())
                    .isEqualTo(105_300);
            assertThat(DAILY_TRANSACTIONS.resourceName())
                    .as("the fixture is dailytran.txt; the dataset was DALYTRAN but the file is not")
                    .isEqualTo("dailytran.txt");
        }

        @Test
        @DisplayName("the originating timestamp is one single value on all 300 records, in the online shape")
        void theOriginatingTimestampIsTheOnlineShape() {
            // Independent corroboration of the online layout: every record carries the same 26 characters, and
            // they are exactly what the online generator produces for the canonical instant - space at byte 11,
            // point at byte 20, six zeros after it.
            final Set<String> distinct = new LinkedHashSet<>();
            for (int record = 0; record < DAILY_TRANSACTIONS.recordCount(); record++) {
                distinct.add(DAILY_TRANSACTIONS.field(record, ORIGINATING_TIMESTAMP_COLUMN,
                        TransactionDto.PERSISTED_TIMESTAMP_LENGTH));
            }

            assertThat(distinct)
                    .as("all 300 records were captured at one instant")
                    .containsExactly(FixedClockProvider.CANONICAL_ONLINE_TIMESTAMP);
            assertThat(FixedClockProvider.onlineTimestamp(FixedClockProvider.canonicalClock()))
                    .as("which the injected clock reproduces exactly")
                    .isEqualTo(distinct.iterator().next());
        }

        @Test
        @DisplayName("the processing timestamp is 26 spaces on all 300 records, an unposted state")
        void theProcessingTimestampIsBlank() {
            for (int record = 0; record < DAILY_TRANSACTIONS.recordCount(); record++) {
                assertThat(DAILY_TRANSACTIONS.field(record, PROCESSING_TIMESTAMP_COLUMN,
                        TransactionDto.PERSISTED_TIMESTAMP_LENGTH))
                        .as("record %d has not been posted yet", Integer.valueOf(record))
                        .isBlank()
                        .hasSize(TransactionDto.PERSISTED_TIMESTAMP_LENGTH);
            }
        }

        @Test
        @DisplayName("HIGH: 250 amounts are positive and 50 negative, and none is normalised away")
        void theAmountSignDistributionIsPreserved() {
            // Decoded position-aware at the PIC width, which is the only correct way: the overpunch is the last
            // character of the field, so a width that is off by one reads a neighbouring column as the sign.
            int positive = 0;
            int negative = 0;
            BigDecimal smallest = null;
            BigDecimal largest = null;
            for (int record = 0; record < DAILY_TRANSACTIONS.recordCount(); record++) {
                final BigDecimal amount = DAILY_TRANSACTIONS.signedDecimal(
                        record, AMOUNT_COLUMN, FixtureLoader.AMOUNT_FIELD_WIDTH);
                if (amount.signum() < 0) {
                    negative++;
                } else if (amount.signum() > 0) {
                    positive++;
                }
                smallest = smallest == null || amount.compareTo(smallest) < 0 ? amount : smallest;
                largest = largest == null || amount.compareTo(largest) > 0 ? amount : largest;
            }

            assertThat(positive).as("positive amounts").isEqualTo(250);
            assertThat(negative)
                    .as("negative amounts, which exercise the cycle debit branch of "
                            + "app/cbl/CBTRN02C.cbl:L547-L552")
                    .isEqualTo(50);
            assertThat(positive + negative)
                    .as("and no amount is zero, so every record exercises one branch or the other")
                    .isEqualTo(FIXTURE_RECORD_COUNT);
            assertThat(smallest).isEqualByComparingTo(new BigDecimal("-998.33"));
            assertThat(largest).isEqualByComparingTo(new BigDecimal("999.77"));
        }

        @Test
        @DisplayName("every decoded amount fits the nine integer digits the record's picture permits")
        void everyDecodedAmountFitsTheRecordPicture() {
            // Feeding each decoded value through the constructor proves the fixture and the type agree on the
            // precision, and that the canonicalised scale is the persisted one.
            for (int record = 0; record < DAILY_TRANSACTIONS.recordCount(); record++) {
                final BigDecimal amount = DAILY_TRANSACTIONS.signedDecimal(
                        record, AMOUNT_COLUMN, FixtureLoader.AMOUNT_FIELD_WIDTH);

                assertThat(detailWithAmount("+00000000.00", amount).amountValue())
                        .as("record %d decodes to a value the type accepts", Integer.valueOf(record))
                        .isEqualByComparingTo(amount);
                assertThat(amount.scale()).isEqualTo(TransactionDto.AMOUNT_SCALE);
            }
        }

        @Test
        @DisplayName("BLOCKER: overpunch letters occur inside text fields, so decoding must be position-aware")
        void overpunchLettersOccurInsideTextFields() {
            // The decode table maps '{' and A-I to positive digits and '}' and J-R to negative ones. Those same
            // letters occur legitimately inside TRAN-DESC and TRAN-MERCHANT-NAME - record one's description
            // begins "Purchase at Abshire-Lowe", which contains A, L and O - so a global character
            // substitution would silently corrupt merchant and description text across the whole fixture. Only
            // a decode driven by the field's own PIC clause and column is safe.
            final String description = DAILY_TRANSACTIONS.field(
                    0, DESCRIPTION_COLUMN, TransactionDto.ROW_DESCRIPTION_LENGTH);

            assertThat(description)
                    .as("a text field carrying characters the overpunch table also uses")
                    .containsAnyOf("A", "L", "O", "I", "E", "R");
            assertThat(description)
                    .as("and it is carried verbatim, never decoded")
                    .isEqualTo("Purchase at Abshire-Lowe  ");

            // The same 26 characters are exactly what a list row shows, truncated from the 100 persisted.
            assertThat(new TransactionDto.TransactionListRow(null, null, null, description, null).description())
                    .isEqualTo(description)
                    .hasSize(TransactionDto.ROW_DESCRIPTION_LENGTH);
        }

        @Test
        @DisplayName("the category code and merchant identifier are invariant across the fixture")
        void theCategoryCodeAndMerchantIdentifierAreInvariant() {
            // Both are constant on all 300 records, which is what makes them useful as a tripwire: a fixture
            // that had been regenerated or reflowed would almost certainly disturb one of them.
            for (int record = 0; record < DAILY_TRANSACTIONS.recordCount(); record++) {
                assertThat(DAILY_TRANSACTIONS.field(record, 19, TransactionDto.CATEGORY_CODE_LENGTH))
                        .as("record %d category code", Integer.valueOf(record))
                        .isEqualTo("0001");
                assertThat(DAILY_TRANSACTIONS.field(record, 144, TransactionDto.MERCHANT_ID_LENGTH))
                        .as("record %d merchant identifier", Integer.valueOf(record))
                        .isEqualTo("800000000");
            }
        }

        @Test
        @DisplayName("a fixture record's card number and identifier populate this type at their exact widths")
        void aFixtureRecordPopulatesThisType() {
            final String transactionId = DAILY_TRANSACTIONS.field(0, 1, TransactionDto.TRANSACTION_ID_LENGTH);
            final String cardNumber =
                    DAILY_TRANSACTIONS.field(0, CARD_NUMBER_COLUMN, TransactionDto.CARD_NUMBER_LENGTH);
            final TransactionDto populated = detailWithCardNumber(cardNumber, transactionId);

            assertThat(populated.transactionId())
                    .as("a zero-padded sixteen character identifier survives as text")
                    .isEqualTo(transactionId)
                    .hasSize(TransactionDto.TRANSACTION_ID_LENGTH)
                    .startsWith("0");
            assertThat(populated.cardNumber())
                    .as("transaction %s carries a sixteen character card number", transactionId)
                    .hasSize(TransactionDto.CARD_NUMBER_LENGTH);
        }
    }

    // Fixtures and helpers.
    //
    // The canonical constructor takes twenty-four components, so it is invoked in exactly one place and every
    // scenario is expressed as an override of one baseline. Baseline values are drawn from record one of
    // app/data/ASCII/dailytran.txt wherever the corpus supplies one, and the card number is deliberately all
    // zeros: a synthetic value that cannot resemble a real primary account number.

    /**
     * The twenty-two text components of {@link TransactionDto}, in canonical constructor order.
     *
     * <p>The first {@value TransactionDto#DETAIL_FIELD_COUNT} of them are also, in the same order, the detail
     * projection, which is what lets a component's value be read back positionally without invoking an
     * accessor reflectively.
     */
    private static final List<String> TEXT_COMPONENTS = List.of(
            "transactionName", "title01", "currentDate", "programName", "title02", "currentTime",
            "transactionIdInput", "transactionId", "cardNumber", "typeCode", "categoryCode", "source",
            "description", "amount", "originatingDate", "processingDate", "merchantId", "merchantName",
            "merchantCity", "merchantZip", "errorMessage", "pageNumber");

    /** The baseline amount, matching record one of the fixture once its overpunch is decoded. */
    private static final BigDecimal BASELINE_AMOUNT = new BigDecimal("504.77");

    /**
     * Returns the baseline text components of a detail response.
     *
     * @return a fresh mutable array of {@link #TEXT_COMPONENTS} values, safe for a caller to overwrite
     */
    private static String[] baselineComponents() {
        return new String[] {
            "CT01", "AWS Mainframe Modernization", "06/10/22", "COTRN01C", "CardDemo", "19:27:53",
            "0000000000683580", "0000000000683581", "0000000000000000", "01", "0001", "POS TERM  ",
            "Purchase at Abshire-Lowe", "+00000504.77", "2022-06-10", "          ", "800000000",
            "Abshire-Lowe", "SEATTLE", "72112     ", null, null,
        };
    }

    /**
     * Invokes the canonical constructor positionally. The single call site for a twenty-four component record.
     *
     * @param text the twenty-two text components, in {@link #TEXT_COMPONENTS} order
     * @param rows the row slots, or {@code null} for a detail response
     * @param amountValue the arithmetic companion, or {@code null} where the screen supplies none
     * @return the constructed instance
     */
    private static TransactionDto from(final String[] text,
            final List<TransactionDto.TransactionListRow> rows, final BigDecimal amountValue) {
        return new TransactionDto(text[0], text[1], text[2], text[3], text[4], text[5], text[6], text[7],
                text[8], text[9], text[10], text[11], text[12], text[13], text[14], text[15], text[16],
                text[17], text[18], text[19], text[20], text[21], rows, amountValue);
    }

    /**
     * Returns a detail response with one text component overridden.
     *
     * @param component the component name, which must appear in {@link #TEXT_COMPONENTS}
     * @param value the replacement value, possibly {@code null}
     * @return the constructed instance
     */
    private static TransactionDto detailWithComponent(final String component, final String value) {
        final String[] text = baselineComponents();
        text[indexOfComponent(component)] = value;
        return from(text, null, BASELINE_AMOUNT);
    }

    /**
     * Returns the position of a text component, failing loudly rather than silently misplacing a value.
     *
     * @param component the component name
     * @return its index in {@link #TEXT_COMPONENTS}
     */
    private static int indexOfComponent(final String component) {
        final int index = TEXT_COMPONENTS.indexOf(component);
        if (index < 0) {
            throw new AssertionError("TransactionDto declares no text component named " + component
                    + "; it declares " + TEXT_COMPONENTS);
        }
        return index;
    }

    /**
     * Reads one text component back out of a detail response through its detail projection.
     *
     * <p>Positional rather than reflective: the detail projection is documented to emit the copybook's fields
     * in declaration order, which is the same order as the leading components of the canonical constructor.
     *
     * @param transaction the instance to read
     * @param component the component name, which must be one the detail projection carries
     * @return the component's value, possibly {@code null}
     */
    private static String componentValue(final TransactionDto transaction, final String component) {
        final int index = indexOfComponent(component);
        if (index >= TransactionDto.DETAIL_FIELD_COUNT) {
            throw new AssertionError(component + " is not part of the detail projection, so it cannot be read "
                    + "positionally; read it through its own accessor instead");
        }
        return transaction.detailProjection().get(index);
    }

    /**
     * Returns the value of a named {@code public static final int} constant on {@link TransactionDto}.
     *
     * <p>Read reflectively so that a parameterised case can name the constant it is checking and a failure
     * reports which constant disagreed with the copybook. Nothing is invoked; a constant is only read.
     *
     * @param constantName the constant's name
     * @return its value
     */
    private static int constantValue(final String constantName) {
        try {
            return TransactionDto.class.getField(constantName).getInt(null);
        } catch (final NoSuchFieldException | IllegalAccessException unavailable) {
            throw new AssertionError("TransactionDto declares no int constant " + constantName, unavailable);
        }
    }

    /**
     * Names the record components a type declares, in declaration order.
     *
     * @param type the record type to inspect
     * @return the component names
     */
    private static List<String> recordComponentNames(final Class<?> type) {
        final RecordComponent[] components = type.getRecordComponents();
        if (components == null) {
            throw new AssertionError(type.getSimpleName() + " is not a record");
        }
        final List<String> names = new ArrayList<>(components.length);
        for (final RecordComponent component : components) {
            names.add(component.getName());
        }
        return names;
    }

    /**
     * Returns the declared type of one record component.
     *
     * @param type the record type to inspect
     * @param component the component name
     * @return the component's declared type
     */
    private static Class<?> recordComponentType(final Class<?> type, final String component) {
        final RecordComponent[] components = type.getRecordComponents();
        if (components == null) {
            throw new AssertionError(type.getSimpleName() + " is not a record");
        }
        for (final RecordComponent candidate : components) {
            if (candidate.getName().equals(component)) {
                return candidate.getType();
            }
        }
        throw new AssertionError(type.getSimpleName() + " declares no component named " + component);
    }

    /**
     * Returns only the digit characters of a rendered timestamp, discarding every separator.
     *
     * <p>Used to compare the online and batch renderings of one instant without their separators, which is the
     * only respect in which the two layouts differ.
     *
     * @param timestamp the 26 character rendering
     * @return its digits in order, twenty of them for either layout
     */
    private static String digitsOnly(final String timestamp) {
        final StringBuilder digits = new StringBuilder(timestamp.length());
        for (int index = 0; index < timestamp.length(); index++) {
            final char character = timestamp.charAt(index);
            if (character >= '0' && character <= '9') {
                digits.append(character);
            }
        }
        return digits.toString();
    }

    /**
     * Counts fixture records carrying a given ten character source value.
     *
     * @param source the padded source value to count
     * @return the number of records carrying it
     */
    private static int countRecordsWithSource(final String source) {
        int matches = 0;
        for (int record = 0; record < DAILY_TRANSACTIONS.recordCount(); record++) {
            if (DAILY_TRANSACTIONS.field(record, SOURCE_COLUMN, PERSISTED_SOURCE_WIDTH).equals(source)) {
                matches++;
            }
        }
        return matches;
    }

    /**
     * A fully populated detail response, the baseline the assertions vary from.
     *
     * @return a fully populated detail response, carrying no row array
     */
    private static TransactionDto detail() {
        return from(baselineComponents(), null, BASELINE_AMOUNT);
    }

    /**
     * A detail response carrying one chosen amount pair.
     *
     * @param amount the display-masked amount
     * @param amountValue the arithmetic companion
     * @return a detail response carrying that amount
     */
    private static TransactionDto detailWithAmount(final String amount, final BigDecimal amountValue) {
        final String[] text = baselineComponents();
        text[indexOfComponent("amount")] = amount;
        return from(text, null, amountValue);
    }

    /**
     * A detail response carrying one chosen description.
     *
     * @param description the description to carry
     * @return a detail response carrying that description
     */
    private static TransactionDto detailWithDescription(final String description) {
        return detailWithComponent("description", description);
    }

    /**
     * A detail response carrying one chosen source value.
     *
     * @param source the ten character source value to carry
     * @return a detail response carrying that source
     */
    private static TransactionDto detailWithSource(final String source) {
        return detailWithComponent("source", source);
    }

    /**
     * A detail response carrying one chosen originating date.
     *
     * @param date the ten character originating date to carry
     * @return a detail response carrying that date
     */
    private static TransactionDto detailWithDate(final String date) {
        return detailWithComponent("originatingDate", date);
    }

    /**
     * A detail response carrying one chosen retrieved identifier.
     *
     * @param transactionId the retrieved identifier to carry
     * @return a detail response carrying that identifier, distinct from the typed search key
     */
    private static TransactionDto detailWithTransactionId(final String transactionId) {
        return detailWithComponent("transactionId", transactionId);
    }

    /**
     * A detail response carrying one chosen card number and identifier.
     *
     * @param cardNumber the card number to carry
     * @param transactionId the identifier to carry
     * @return a detail response carrying both
     */
    private static TransactionDto detailWithCardNumber(final String cardNumber, final String transactionId) {
        final String[] text = baselineComponents();
        text[indexOfComponent("cardNumber")] = cardNumber;
        text[indexOfComponent("transactionId")] = transactionId;
        return from(text, null, BASELINE_AMOUNT);
    }

    /**
     * A detail response carrying one chosen merchant triple.
     *
     * @param name the merchant name to carry
     * @param city the merchant city to carry
     * @param zip the merchant postal code to carry
     * @return a detail response carrying all three
     */
    private static TransactionDto detailWithMerchant(final String name, final String city, final String zip) {
        final String[] text = baselineComponents();
        text[indexOfComponent("merchantName")] = name;
        text[indexOfComponent("merchantCity")] = city;
        text[indexOfComponent("merchantZip")] = zip;
        return from(text, null, BASELINE_AMOUNT);
    }

    /**
     * A list response with a chosen number of the ten screen slots filled.
     *
     * @param populatedRows how many of the ten screen slots to fill
     * @return a list response with that many rows populated
     */
    private static TransactionDto listWith(final int populatedRows) {
        final List<TransactionDto.TransactionListRow> rows = new ArrayList<>();
        for (int ordinal = 1; ordinal <= populatedRows; ordinal++) {
            rows.add(row(ordinal));
        }
        final String[] text = baselineComponents();
        text[indexOfComponent("pageNumber")] = "       1";
        return from(text, rows, null);
    }

    /**
     * A list response carrying one chosen page number.
     *
     * @param pageNumber the eight character page number to carry
     * @return a list response carrying that page number and one populated row
     */
    private static TransactionDto listWithPageNumber(final String pageNumber) {
        final List<TransactionDto.TransactionListRow> rows = new ArrayList<>();
        rows.add(row(1));
        final String[] text = baselineComponents();
        text[indexOfComponent("pageNumber")] = pageNumber;
        return from(text, rows, null);
    }

    /**
     * The shared builder every factory above delegates to.
     *
     * @param typeCode the type code to carry
     * @param amount the display-masked amount to carry
     * @param amountValue the arithmetic companion
     * @param rows the row slots, or {@code null} for a detail response
     * @return the constructed instance
     */
    private static TransactionDto build(final String typeCode, final String amount,
            final BigDecimal amountValue, final List<TransactionDto.TransactionListRow> rows) {
        final String[] text = baselineComponents();
        text[indexOfComponent("typeCode")] = typeCode;
        text[indexOfComponent("amount")] = amount;
        return from(text, rows, amountValue);
    }

    /**
     * One populated list row, addressed by its screen ordinal.
     *
     * @param ordinal the one-based row ordinal
     * @return a populated list row whose description fits the narrower row width
     */
    private static TransactionDto.TransactionListRow row(final int ordinal) {
        return new TransactionDto.TransactionListRow("S",
                String.format(Locale.ROOT, "%016d", Integer.valueOf(ordinal)), "06/10/22",
                String.format(Locale.ROOT, "PURCHASE %d", Integer.valueOf(ordinal)), "+00000504.77");
    }
}

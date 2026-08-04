/*
 * ******************************************************************
 * Program     : CardDtoTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Executable specification for the card detail and card
 *               list projections. Pins the two symbolic maps this one
 *               DTO straddles, the row-1 selector-type gap that exists
 *               in the map itself, the four widths that diverge between
 *               the maps, the underlying 150-byte card record, the
 *               non-unique alternate index, the three distinct empty
 *               states, and the cardholder data that must never reach a
 *               rendered string.
 * Source      : app/cpy-bms/COCRDSL.CPY (15 fields, CCRDSLAI)
 *               + app/cpy-bms/COCRDLI.CPY (45 fields, CCRDLIAI)
 *               + app/cbl/COCRDSLC.cbl + app/cbl/COCRDLIC.cbl
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

import com.cardemo.model.dto.CardDto;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Executable specification for {@link CardDto}, the payload that carries both card read screens.
 *
 * <h2>What it does</h2>
 *
 * <p>One Java type answers for two BMS symbolic maps, and every assertion below is anchored in one of those
 * frozen members rather than in a hand-typed expectation. {@code app/cpy-bms/COCRDSL.CPY} declares the
 * card-detail screen with 15 input fields inside {@code 01 CCRDSLAI.}; {@code app/cpy-bms/COCRDLI.CPY} declares
 * the card-list screen with 45 inside {@code 01 CCRDLIAI.}. The programs behind them are
 * {@code app/cbl/COCRDSLC.cbl} (887 lines) and {@code app/cbl/COCRDLIC.cbl} (1,459 lines).
 *
 * <p>Four properties of the source make this contract easy to get wrong, and each has its own group below.
 *
 * <p><strong>The list map is not a uniform row array.</strong> It declares seven row groups, but row 1 has four
 * input fields where rows 2 through 7 have five: the token {@code CRDSTP1} appears nowhere in the member -
 * nowhere in the whole frozen tree - while {@code CRDSTP2I} through {@code CRDSTP7I} are declared at
 * {@code app/cpy-bms/COCRDLI.CPY:108}, {@code :138}, {@code :168}, {@code :198}, {@code :228} and {@code :258},
 * each {@code PIC X(1)}. A seven-by-five row model computes 46 fields and would read every row from the wrong
 * offset. The arithmetic that closes is 9 preamble + 4 + (6 x 5) + 2 trailer = 45.
 *
 * <p><strong>Four fields diverge between the two maps, two of them in opposite directions.</strong>
 * {@code INFOMSGI} is {@code PIC X(40)} at {@code app/cpy-bms/COCRDSL.CPY:96} but {@code PIC X(45)} at
 * {@code app/cpy-bms/COCRDLI.CPY:282}, while {@code ERRMSGI} is {@code PIC X(80)} at
 * {@code app/cpy-bms/COCRDSL.CPY:102} but {@code PIC X(78)} at {@code app/cpy-bms/COCRDLI.CPY:288} - so
 * sizing each member to whichever map is wider would relax the contract on both screens at once.
 * {@code FKEYSI PIC X(75)} at {@code app/cpy-bms/COCRDSL.CPY:108} exists only on the detail map, and
 * {@code PAGENOI PIC X(3)} at {@code app/cpy-bms/COCRDLI.CPY:60} only on the list map.
 *
 * <p><strong>Three tempting abstractions are declined, each because the source refuses it.</strong> There is no
 * shared terminal-header component, because {@code CURTIMEI} is {@code PIC X(8)} at
 * {@code app/cpy-bms/COCRDSL.CPY:54} and {@code app/cpy-bms/COCRDLI.CPY:54} but {@code PIC X(9)} at
 * {@code app/cpy-bms/COSGN00.CPY:54}, so a shared helper would misreport one screen. There is no shared paging
 * field, because this map names it {@code PAGENOI} at width 3 while {@code app/cpy-bms/COTRN00.CPY:60} and
 * {@code app/cpy-bms/COUSR00.CPY:60} name it {@code PAGENUMI} at width 8 - a different name and a different
 * width. And there is no shared next-page sentinel, because {@code app/cbl/COCRDLIC.cbl:239-244} encodes
 * "no next page" as {@code LOW-VALUES} while {@code app/cbl/COTRN00C.cbl:65-68} encodes it as the character
 * {@code 'N'}; the two are mutually incompatible, and unifying them would make one list report the wrong
 * paging state.
 *
 * <p><strong>This payload is the densest concentration of cardholder data in the package.</strong> Eight card
 * numbers can be present at once - one detail value, one list filter value and one per row - alongside the
 * cardholder name. The security group asserts that no rendering of either type reproduces any of them, and
 * that no card verification value, password, hash or other personal identifier exists on the type at all.
 *
 * <h2>Exact source locators used</h2>
 *
 * <p>{@code app/cpy-bms/COCRDSL.CPY:54}, {@code :60}, {@code :96}, {@code :102}, {@code :108};
 * {@code app/cpy-bms/COCRDLI.CPY:60}, {@code :66}, {@code :108}, {@code :138}, {@code :168}, {@code :198},
 * {@code :228}, {@code :258}, {@code :282}, {@code :288}; {@code app/cpy-bms/COCRDUP.CPY:96};
 * {@code app/cpy-bms/COSGN00.CPY:54}; {@code app/cpy-bms/COTRN00.CPY:60}; {@code app/cpy-bms/COUSR00.CPY:60};
 * {@code app/cbl/COCRDLIC.cbl:61-68}, {@code :72-73}, {@code :76}, {@code :80-82}, {@code :86},
 * {@code :177-178}, {@code :239-244}, {@code :1007-1009}, {@code :1022}, {@code :1042-1044}, {@code :1058};
 * {@code app/cbl/COTRN00C.cbl:65-68}; {@code app/cbl/COUSR00C.cbl:57}; {@code app/cbl/CBTRN03C.cbl:131};
 * {@code app/cpy/CVACT02Y.cpy:5-11}; {@code app/cpy/CVACT01Y.cpy:11}; {@code app/cpy/CSSETATY.cpy};
 * {@code app/catlg/LISTCAT.txt:202}, {@code :281}, {@code :283}, {@code :285};
 * {@code app/data/ASCII/carddata.txt}; {@code app/data/ASCII/cardxref.txt}.
 *
 * <h2>How to run, build and test</h2>
 *
 * <p>Run this tier with {@code ./mvnw -B -ntp test}, this class alone with
 * {@code ./mvnw -B -ntp -Dtest=CardDtoTest test}, and compile only with {@code ./mvnw -B -ntp test-compile}.
 * <strong>Surefire 3.5.4 is what binds this class</strong>: it collects {@code **}{@code /*Test.java} under
 * {@code src/test/java} with {@code **}{@code /integration/**} and {@code **}{@code /e2e/**} excluded by path,
 * so a class that keeps this name inside {@code src/test/java/com/cardemo/unit} is collected and one moved out
 * of that tree is collected by neither Surefire nor Failsafe - it would simply stop running, with no error and
 * no warning. Test compilation runs at {@code release 25} under {@code -Xlint:all -Werror} with
 * {@code failOnWarning}, so a raw type or an unchecked cast here fails the whole build rather than printing a
 * warning.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>There is nothing to configure, and the three defaults a reader might look for are settled as follows.
 *
 * <ul>
 *   <li><strong>Page size is 7, and only 7.</strong> {@code 05 WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7.} at
 *       {@code app/cbl/COCRDLIC.cbl:177-178}, corroborated four times inside the same program. The transaction
 *       and user lists page by 10 and the batch transaction report writes 20 lines to a page; none of those
 *       three figures may appear anywhere in this file or in the type it tests.</li>
 *   <li><strong>No clock is injected, because there is no temporal member to fix.</strong> Every member of
 *       {@link CardDto} is a {@code String}, and the header date and time are {@code PIC X(8)} text rather than
 *       instants, so nothing here consults a clock. That is why {@code FixedClockProvider} is not used: it
 *       would pin a value this contract does not have. No {@code now()} of any kind is called, and no default
 *       locale or default zone is relied on.</li>
 *   <li><strong>No mock is created, so no Mockito strictness setting applies.</strong> {@link CardDto} is a
 *       pure data holder with no collaborator to stub; the frozen copybooks and the frozen fixtures are the
 *       only inputs, and they are read directly. A mock here would verify nothing.</li>
 *   </ul>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>A build failure with no test failure is the {@code -Werror} gate.</strong> One unused import or
 *       one raw type is enough. Read the {@code javac} output rather than the Surefire report.</li>
 *   <li><strong>The fixture is {@code dailytran.txt}, spelled in full.</strong> {@code dalytran.txt} does not
 *       exist; the mainframe DD name is {@code DALYTRAN} but the ASCII fixture spells the word out. Code
 *       written from the DD name gets a null resource stream, surfacing as an
 *       {@code IllegalArgumentException} naming the resource.</li>
 *   <li><strong>Assuming a {@code CRDSTP1I} exists shifts every list row.</strong> If the row model is made
 *       uniform, the field census becomes 46 and each row reads its account number, card number and status
 *       from the wrong offset. {@code RowIrregularity} fails first and is the group to read.</li>
 *   <li><strong>A numeric card verification value or account identifier loses its leading zero.</strong> Eight
 *       of the fifty seeded verification values begin with a zero and every seeded account identifier is
 *       zero-padded to eleven characters, so {@code String} is the only representation that round-trips.</li>
 *   <li><strong>A card number leaking through a rendered string is a Blocker.</strong> Neither
 *       {@link CardDto} nor {@code CardDto.CardListRow} declares {@code toString}, which is what keeps field
 *       values out of the inherited rendering. Adding one, or converting either type to a record, reintroduces
 *       the disclosure; {@code ProtectedCardholderData} is the group that catches it.</li>
 *   <li><strong>A whitespace cleanup of a fixture destroys it.</strong> Every {@code carddata.txt} record ends
 *       in exactly 59 spaces, which are the {@code FILLER} of the layout and not padding to be trimmed.</li>
 *   <li><strong>A global overpunch decode over {@code carddata.txt} corrupts the embossed names</strong>, which
 *       are full of letters in the {@code A} to {@code R} range. That fixture holds no signed field at all, so
 *       no decode belongs anywhere near it.</li>
 *   </ul>
 *
 * <h2>Findings this file records, by severity</h2>
 *
 * <ul>
 *   <li><strong>Blocker</strong> - a card number or verification value reaching a rendered string; a global,
 *       position-blind overpunch decode applied to the card fixture.</li>
 *   <li><strong>High</strong> - a uniform seven-row model; a shared header, paging or next-page abstraction; a
 *       numeric verification value or account identifier; anything implying the account-identifier alternate
 *       index is unique; conflating this payload with the card-update payload; collapsing absent, blank and
 *       low-values into one state.</li>
 *   <li><strong>Medium</strong> - the plan's 460-field census for the seventeen maps. Counting the input groups
 *       yields 441, and the plan's own table sums to 440. The two maps this type serves are unaffected at 15
 *       and 45, so no code changes; {@code COACTVW} is 37 rather than 36 because of the expanded
 *       {@code PIC 99999999999} at {@code app/cpy-bms/COACTVW.CPY:60}.</li>
 *   <li><strong>Not available</strong> - no assertion is made here about the physical schema. The card table's
 *       column types, index definitions and constraints would come from the Flyway migrations, and this tier
 *       neither reads nor stands up a database, so any such claim would be invented. The catalogued key length,
 *       record length and alternate-index displacement asserted below come from
 *       {@code app/catlg/LISTCAT.txt}, which is a frozen physical listing rather than a schema claim.</li>
 *   </ul>
 */
@DisplayName("CardDto: two screen projections, an irregular row array and four divergent fields")
class CardDtoTest {

    /** The detail map's own field census, {@code 01 CCRDSLAI.} of {@code app/cpy-bms/COCRDSL.CPY}. */
    private static final int DETAIL_MAP_FIELDS = 15;

    /** The list map's own field census, {@code 01 CCRDLIAI.} of {@code app/cpy-bms/COCRDLI.CPY}. */
    private static final int LIST_MAP_FIELDS = 45;

    /** Header and filter fields ahead of the first row group on the list map, {@code TRNNAMEI} to
     * {@code CARDSIDI}. */
    private static final int LIST_PREAMBLE_FIELDS = 9;

    /** Message fields after the last row group on the list map, {@code INFOMSGI} and {@code ERRMSGI}. */
    private static final int LIST_TRAILER_FIELDS = 2;

    /** Rows per page on the card list, {@code app/cbl/COCRDLIC.cbl:177-178}. */
    private static final int CARD_LIST_ROWS = 7;

    /** Rows per page on the transaction and user lists. Present only to assert it never leaks here. */
    private static final int TEN_ROW_LIST = 10;

    /** Report lines per page in the batch transaction report. Present only to assert it never leaks here. */
    private static final int BATCH_REPORT_LINES_PER_PAGE = 20;

    /** Character count of the card number, {@code CARD-NUM PIC X(16)}. */
    private static final int CARD_NUMBER_WIDTH = 16;

    /** Character count of the account identifier, {@code CARD-ACCT-ID PIC 9(11)}. */
    private static final int ACCOUNT_ID_WIDTH = 11;

    /** Byte length of one card record, {@code app/cpy/CVACT02Y.cpy}. */
    private static final int CARD_RECORD_LENGTH = 150;

    /** Trailing {@code FILLER} of a card record, {@code app/cpy/CVACT02Y.cpy:11}. */
    private static final int CARD_RECORD_FILLER = 59;

    /** Populated byte count of a cross-reference record, 16 + 9 + 11, inside a 50-byte cluster slot. */
    private static final int XREF_POPULATED_BYTES = 36;

    /** Cluster slot the cross-reference record sits in, whose trailing 14 bytes are absent from the fixture. */
    private static final int XREF_CLUSTER_SLOT = 50;

    /** A representative synthetic card number. Not a real account number and never rendered by the type. */
    private static final String SAMPLE_CARD_NUMBER = "4000000000000002";

    /** A representative synthetic account identifier, zero-padded to its full declared width. */
    private static final String SAMPLE_ACCOUNT_ID = "00000000001";

    /** A representative synthetic cardholder name. */
    private static final String SAMPLE_CARDHOLDER_NAME = "SPECIMEN CARDHOLDER";

    // Read once from the frozen corpus. The references are static because @BeforeAll requires it, but every
    // referent is an immutable snapshot assigned exactly once and never replaced or mutated, so this is
    // effectively a set of constants rather than the global mutable state Rule 1 Clause B prohibits.
    private static BmsSymbolicMap detailMap;
    private static BmsSymbolicMap listMap;
    private static BmsSymbolicMap updateMap;
    private static BmsSymbolicMap signOnMap;
    private static BmsSymbolicMap transactionListMap;
    private static BmsSymbolicMap userListMap;
    private static RecordLayoutCopybook cardRecord;
    private static List<String> cardListProgram;
    private static List<String> transactionListProgram;
    private static List<String> catalogue;

    @BeforeAll
    static void readFrozenCorpus() {
        detailMap = BmsSymbolicMap.of("COCRDSL");
        listMap = BmsSymbolicMap.of("COCRDLI");
        updateMap = BmsSymbolicMap.of("COCRDUP");
        signOnMap = BmsSymbolicMap.of("COSGN00");
        transactionListMap = BmsSymbolicMap.of("COTRN00");
        userListMap = BmsSymbolicMap.of("COUSR00");
        cardRecord = RecordLayoutCopybook.of("CVACT02Y");
        cardListProgram = frozenLines(Path.of("app", "cbl", "COCRDLIC.cbl"));
        transactionListProgram = frozenLines(Path.of("app", "cbl", "COTRN00C.cbl"));
        catalogue = frozenLines(Path.of("app", "catlg", "LISTCAT.txt"));
    }

    @Nested
    @DisplayName("1. The published counts match the copybooks")
    class FieldCounts {

        @Test
        @DisplayName("the detail field count equals COCRDSL's input-field inventory")
        void detailCountMatchesCopybook() {
            assertThat(CardDto.DETAIL_FIELD_COUNT)
                    .as("COCRDSL declares its input group at line %d and redefines it at line %d",
                            detailMap.inputGroupLine(), detailMap.outputRedefinitionLine())
                    .isEqualTo(detailMap.inputFieldCount())
                    .isEqualTo(DETAIL_MAP_FIELDS);
        }

        @Test
        @DisplayName("the list field count equals COCRDLI's input-field inventory")
        void listCountMatchesCopybook() {
            assertThat(CardDto.LIST_FIELD_COUNT)
                    .as("COCRDLI declares its input group at line %d and redefines it at line %d",
                            listMap.inputGroupLine(), listMap.outputRedefinitionLine())
                    .isEqualTo(listMap.inputFieldCount())
                    .isEqualTo(LIST_MAP_FIELDS);
        }

        @Test
        @DisplayName("the two maps are different shapes, so neither count may stand in for the other")
        void theTwoMapsAreDifferentShapes() {
            assertThat(CardDto.DETAIL_FIELD_COUNT).isNotEqualTo(CardDto.LIST_FIELD_COUNT);
            assertThat(detailMap.inputFieldCount()).isNotEqualTo(listMap.inputFieldCount());
        }

        @Test
        @DisplayName("the page size is seven, as the list program declares")
        void pageSizeIsSeven() {
            // app/cbl/COCRDLIC.cbl:177-178, read from the program rather than restated.
            assertThat(sourceLine(cardListProgram, 177)).isEqualTo("05 WS-MAX-SCREEN-LINES PIC S9(4) COMP");
            assertThat(sourceLine(cardListProgram, 178)).isEqualTo("VALUE 7.");
            assertThat(CardDto.CARD_LIST_PAGE_SIZE).isEqualTo(CARD_LIST_ROWS);
        }

        @Test
        @DisplayName("the program corroborates seven four more times, so the figure is not a single literal")
        void sevenIsCorroboratedAcrossTheProgram() {
            // A page size read from one VALUE clause could be a typo. These four independent declarations in
            // the same program are what make seven the screen's actual physical row count.
            assertThat(sourceLine(cardListProgram, 72)).isEqualTo("05 WS-EDIT-SELECT-FLAGS PIC X(7)");
            assertThat(sourceLine(cardListProgram, 76)).isEqualTo("OCCURS 7 TIMES.");
            assertThat(sourceLine(cardListProgram, 86))
                    .isEqualTo("10 WS-EDIT-SELECT-ERRORS OCCURS 7 TIMES.");
            assertThat(listMap.declares("CRDSEL" + CARD_LIST_ROWS + "I"))
                    .as("the map itself declares a seventh row group")
                    .isTrue();
            assertThat(listMap.declares("CRDSEL" + (CARD_LIST_ROWS + 1) + "I"))
                    .as("and declares no eighth")
                    .isFalse();
        }

        @Test
        @DisplayName("the other pagination figures in the corpus never leak into this type")
        void otherPaginationFiguresDoNotLeak() {
            // The transaction list and user list page by ten and the batch report writes twenty lines to a
            // page. Each belongs to its own type; carrying any of them here would address rows this map lacks.
            assertThat(CardDto.CARD_LIST_PAGE_SIZE)
                    .isNotEqualTo(TEN_ROW_LIST)
                    .isNotEqualTo(BATCH_REPORT_LINES_PER_PAGE);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a tenth row does not exist on this screen")
                    .isThrownBy(() -> row(TEN_ROW_LIST))
                    .withMessageContaining("rowNumber");
        }

        @Test
        @DisplayName("the row field counts sum with the preamble and trailer to the map's total")
        void countsReconcileWithTheMap() {
            // 9 preamble + 4 (row 1) + 30 (rows 2-7) + 2 trailer = 45. Deriving the row contribution from
            // getBmsFieldCount() rather than restating 34 is what ties the arithmetic to the code under test.
            int rowFields = 0;
            for (int rowNumber = CardDto.FIRST_ROW_NUMBER;
                    rowNumber <= CardDto.CARD_LIST_PAGE_SIZE; rowNumber++) {
                rowFields += row(rowNumber).getBmsFieldCount();
            }

            final int expectedRowFields = CardDto.ROW_FIELD_COUNT_WITHOUT_SELECTOR_TYPE
                    + (CARD_LIST_ROWS - 1) * CardDto.ROW_FIELD_COUNT_WITH_SELECTOR_TYPE;
            assertThat(rowFields)
                    .as("row 1 contributes %d and each of the other six contributes %d",
                            CardDto.ROW_FIELD_COUNT_WITHOUT_SELECTOR_TYPE,
                            CardDto.ROW_FIELD_COUNT_WITH_SELECTOR_TYPE)
                    .isEqualTo(expectedRowFields)
                    .isEqualTo(34);
            assertThat(LIST_PREAMBLE_FIELDS + rowFields + LIST_TRAILER_FIELDS)
                    .isEqualTo(CardDto.LIST_FIELD_COUNT)
                    .isEqualTo(listMap.inputFieldCount());
        }

        @Test
        @DisplayName("a uniform seven-by-five row model would overcount the map by one")
        void uniformRowModelWouldOvercount() {
            // The counter-factual, stated numerically so the cost of regularising the rows is explicit.
            final int uniform = LIST_PREAMBLE_FIELDS
                    + CARD_LIST_ROWS * CardDto.ROW_FIELD_COUNT_WITH_SELECTOR_TYPE
                    + LIST_TRAILER_FIELDS;
            assertThat(uniform)
                    .as("a uniform model computes %d where COCRDLI declares %d", uniform, LIST_MAP_FIELDS)
                    .isEqualTo(LIST_MAP_FIELDS + 1)
                    .isNotEqualTo(listMap.inputFieldCount());
        }
    }

    @Nested
    @DisplayName("2. The row-1 selector-type gap that exists in the copybook")
    class RowIrregularity {

        @Test
        @DisplayName("the copybook declares no selector-type field for row 1")
        void copybookOmitsRowOneSelectorType() {
            // The finding this whole group exists for, asserted against the map rather than the Java.
            assertThat(listMap.declares("CRDSTP1I"))
                    .as("CRDSTP1I appears nowhere in COCRDLI.CPY")
                    .isFalse();
            assertThat(listMap.fieldNames())
                    .as("nor under any other spelling anywhere in the input group")
                    .noneMatch(name -> name.startsWith("CRDSTP1"));
        }

        @ParameterizedTest(name = "row {0} does have a selector-type field")
        @ValueSource(ints = {2, 3, 4, 5, 6, 7})
        @DisplayName("every other row does declare one, each a single character")
        void copybookDeclaresTheOtherSelectorTypes(final int rowNumber) {
            assertThat(listMap.declares("CRDSTP" + rowNumber + "I")).isTrue();
            assertThat(listMap.widthOf("CRDSTP" + rowNumber + "I")).isEqualTo(1);
        }

        @Test
        @DisplayName("row 1 reports no selector type and four fields")
        void rowOneIsShort() {
            final CardDto.CardListRow first = row(CardDto.FIRST_ROW_NUMBER);

            assertThat(first.hasSelectorType()).isFalse();
            assertThat(first.getBmsFieldCount())
                    .isEqualTo(CardDto.ROW_FIELD_COUNT_WITHOUT_SELECTOR_TYPE)
                    .isEqualTo(4);
        }

        @ParameterizedTest(name = "row {0} reports a selector type and five fields")
        @ValueSource(ints = {2, 3, 4, 5, 6, 7})
        @DisplayName("every other row reports a selector type and five fields")
        void otherRowsAreFull(final int rowNumber) {
            final CardDto.CardListRow other = row(rowNumber);

            assertThat(other.hasSelectorType()).isTrue();
            assertThat(other.getBmsFieldCount())
                    .isEqualTo(CardDto.ROW_FIELD_COUNT_WITH_SELECTOR_TYPE)
                    .isEqualTo(5);
        }

        @Test
        @DisplayName("the Java agrees with the copybook row by row, not merely in total")
        void javaAgreesWithTheCopybookRowByRow() {
            // A type could get the total of 45 right while attributing the missing field to the wrong row.
            // Comparing per row against the map's own declarations is what rules that out.
            for (int rowNumber = CardDto.FIRST_ROW_NUMBER;
                    rowNumber <= CardDto.CARD_LIST_PAGE_SIZE; rowNumber++) {
                assertThat(row(rowNumber).hasSelectorType())
                        .as("row %d", rowNumber)
                        .isEqualTo(listMap.declares("CRDSTP" + rowNumber + "I"));
            }
        }

        @Test
        @DisplayName("row 1 refuses a selector type outright, so the gap cannot be regularised by a caller")
        void rowOneRefusesASelectorType() {
            // Documenting the absence would not prevent it; refusing the argument does. The refusal names the
            // field and the copybook rather than echoing a value.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new CardDto.CardListRow(CardDto.FIRST_ROW_NUMBER, "S", "U",
                            SAMPLE_ACCOUNT_ID, SAMPLE_CARD_NUMBER, "Y"))
                    .withMessageContaining("selectorType")
                    .withMessageContaining("CRDSTP1I");
        }

        @ParameterizedTest(name = "row number {0} does not exist on the screen")
        @ValueSource(ints = {0, -1, 8, 99})
        @DisplayName("a row number outside the seven the map declares is refused")
        void offScreenRowNumberIsRefused(final int rowNumber) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> row(rowNumber))
                    .withMessageContaining("rowNumber");
        }

        @Test
        @DisplayName("row 1 still carries a selection flag, which is a different field from the selector type")
        void rowOneStillCarriesASelectionFlag() {
            // CRDSEL1I is declared at COCRDLI.CPY:78; it is CRDSTP1I that is absent. Conflating the two would
            // make row 1 unselectable, which is a behaviour change rather than a field-count discrepancy.
            assertThat(listMap.declares("CRDSEL1I")).isTrue();
            assertThat(row(CardDto.FIRST_ROW_NUMBER).getSelectionFlag()).isEqualTo("S");
        }

        @Test
        @DisplayName("a full page of seven is accepted and the rows stay in screen order")
        void aFullPageIsAcceptedInOrder() {
            final CardDto page = listOf(fullPage());

            assertThat(page.getRows()).hasSize(CARD_LIST_ROWS);
            for (int index = 0; index < CARD_LIST_ROWS; index++) {
                assertThat(page.getRows().get(index).getRowNumber())
                        .as("row at index %d", index)
                        .isEqualTo(index + CardDto.FIRST_ROW_NUMBER);
            }
        }
    }

    @Nested
    @DisplayName("3. The fields whose widths or presence differ between the maps")
    class DivergentWidths {

        @Test
        @DisplayName("the copybooks really do declare different widths for the same field names")
        void copybooksDiverge() {
            // Established first, so that the choice tested below is shown to be a real decision rather than an
            // arbitrary number.
            assertThat(detailMap.widthOf("INFOMSGI")).isEqualTo(40);
            assertThat(listMap.widthOf("INFOMSGI")).isEqualTo(45);
            assertThat(detailMap.widthOf("ERRMSGI")).isEqualTo(80);
            assertThat(listMap.widthOf("ERRMSGI")).isEqualTo(78);
        }

        @Test
        @DisplayName("the two divergences run in opposite directions, so no single width satisfies both maps")
        void theDivergencesRunInOppositeDirections() {
            // This is the reason a shared constant per field name is impossible rather than merely untidy: the
            // list map is wider on the information line and narrower on the error line, so whichever map a
            // shared width followed would be wrong on exactly one of the two fields.
            assertThat(listMap.widthOf("INFOMSGI"))
                    .as("the list map is the wider one on the information line")
                    .isGreaterThan(detailMap.widthOf("INFOMSGI"));
            assertThat(listMap.widthOf("ERRMSGI"))
                    .as("but the narrower one on the error line")
                    .isLessThan(detailMap.widthOf("ERRMSGI"));
        }

        @Test
        @DisplayName("the information message is sized per projection, so neither map's width is widened")
        void informationMessageIsSizedPerProjection() {
            // One type serves two screens, but a value can only have arrived on the screen that sent it.
            // Sizing both projections to the wider of the two declarations would let a 45-character message
            // through the DETAIL projection, which COCRDSL.CPY:96 declares as PIC X(40) - a value that screen
            // cannot produce. Each projection is therefore bounded by its own map.
            final int detailWidth = detailMap.widthOf("INFOMSGI");
            final int listWidth = listMap.widthOf("INFOMSGI");

            assertThat(detailWithInformationMessage("x".repeat(detailWidth)).getInformationMessage())
                    .as("COCRDSL declares PIC X(%d), so exactly that many characters must be accepted",
                            detailWidth)
                    .hasSize(detailWidth);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("one character beyond the DETAIL declaration is refused even though the LIST map "
                            + "declares %d, because this is the detail projection", listWidth)
                    .isThrownBy(() -> detailWithInformationMessage("x".repeat(detailWidth + 1)));

            assertThat(listWithInformationMessage("x".repeat(listWidth)).getInformationMessage())
                    .as("COCRDLI declares PIC X(%d) for the same field name", listWidth)
                    .hasSize(listWidth);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> listWithInformationMessage("x".repeat(listWidth + 1)));
        }

        @Test
        @DisplayName("the error message is sized per projection too, and the divergence runs the other way")
        void errorMessageIsSizedPerProjection() {
            // Note the divergence runs the other way here - the DETAIL map is the wider one - so a type that
            // simply preferred one map over the other would get exactly one of these two fields wrong.
            final int detailWidth = detailMap.widthOf("ERRMSGI");
            final int listWidth = listMap.widthOf("ERRMSGI");

            assertThat(detailWidth)
                    .as("for the error message it is the DETAIL map that is wider, unlike the info message")
                    .isGreaterThan(listWidth);
            assertThat(detailWithErrorMessage("x".repeat(detailWidth)).getErrorMessage())
                    .hasSize(detailWidth);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> detailWithErrorMessage("x".repeat(detailWidth + 1)));

            assertThat(listWithErrorMessage("x".repeat(listWidth)).getErrorMessage()).hasSize(listWidth);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("the LIST projection refuses at %d even though the DETAIL map declares %d",
                            listWidth, detailWidth)
                    .isThrownBy(() -> listWithErrorMessage("x".repeat(listWidth + 1)));
        }

        @Test
        @DisplayName("the page number takes the card list's three characters, not the transaction list's eight")
        void pageNumberIsThreeCharacters() {
            // COCRDLI:60 declares PAGENOI PIC X(3) where COTRN00:60 and COUSR00:60 declare PAGENUMI PIC X(8).
            // The name AND the width differ, so a shared page-number member across the DTOs would be wrong for
            // one of them on both counts.
            assertThat(listMap.declares("PAGENOI")).isTrue();
            assertThat(listMap.widthOf("PAGENOI")).isEqualTo(3);
            assertThat(listMap.declares("PAGENUMI"))
                    .as("the card list does not use the transaction list's field name")
                    .isFalse();
            assertThat(transactionListMap.declares("PAGENOI")).isFalse();
            assertThat(transactionListMap.widthOf("PAGENUMI")).isEqualTo(8);
            assertThat(userListMap.widthOf("PAGENUMI")).isEqualTo(8);

            assertThat(listWithPageNumber("999").getPageNumber()).isEqualTo("999");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a four-character page number is a PAGENUMI value and this map declares PAGENOI")
                    .isThrownBy(() -> listWithPageNumber("1000"))
                    .withMessageContaining("pageNumber");
        }

        @Test
        @DisplayName("the function-key legend is declared on the detail map only and is absent from the list")
        void functionKeyLegendBelongsToTheDetailMapOnly() {
            assertThat(detailMap.widthOf("FKEYSI")).isEqualTo(75);
            assertThat(listMap.declares("FKEYSI"))
                    .as("COCRDLI declares no function-key legend at all")
                    .isFalse();
            assertThat(listOf(List.of(row(CardDto.FIRST_ROW_NUMBER))).getFunctionKeys())
                    .as("so a list payload carries none")
                    .isNull();
        }

        @Test
        @DisplayName("the page number is absent from the detail map, mirroring the legend the other way")
        void pageNumberBelongsToTheListMapOnly() {
            assertThat(detailMap.declares("PAGENOI")).isFalse();
            assertThat(detail().getPageNumber())
                    .as("a detail payload carries no page number, because its map declares none")
                    .isNull();
        }
    }

    @Nested
    @DisplayName("4. Widths shared by both maps are enforced at the declared boundary")
    class SharedWidths {

        @ParameterizedTest(name = "{0} is {1} characters on both maps")
        @CsvSource({
            "TRNNAMEI,  4",
            "TITLE01I, 40",
            "CURDATEI,  8",
            "PGMNAMEI,  8",
            "TITLE02I, 40",
            "CURTIMEI,  8",
            "ACCTSIDI, 11",
            "CARDSIDI, 16",
        })
        @DisplayName("the header and key fields agree across the two maps")
        void headerWidthsAgreeAcrossMaps(final String field, final int width) {
            assertThat(detailMap.widthOf(field))
                    .as("%s on COCRDSL", field)
                    .isEqualTo(width);
            assertThat(listMap.widthOf(field))
                    .as("%s on COCRDLI", field)
                    .isEqualTo(width);
        }

        @ParameterizedTest(name = "{0} is {1} characters and exists on COCRDSL only")
        @CsvSource({
            "CRDNAMEI, 50",
            "CRDSTCDI,  1",
            "EXPMONI,   2",
            "EXPYEARI,  4",
        })
        @DisplayName("the detail-only fields are declared on COCRDSL and absent from COCRDLI")
        void detailOnlyFieldsAreAbsentFromTheListMap(final String field, final int width) {
            assertThat(detailMap.widthOf(field)).isEqualTo(width);
            assertThat(listMap.declares(field)).isFalse();
        }

        @Test
        @DisplayName("the cardholder name is accepted at exactly its declared width and refused beyond it")
        void cardholderNameBoundary() {
            final int width = detailMap.widthOf("CRDNAMEI");

            assertThat(detailWithCardholderName("N".repeat(width)).getCardholderName()).hasSize(width);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> detailWithCardholderName("N".repeat(width + 1)))
                    .withMessageContaining("cardholderName");
        }

        @Test
        @DisplayName("the row fields are accepted at their declared widths and refused beyond them")
        void rowFieldBoundaries() {
            final int accountWidth = listMap.widthOf("ACCTNO1I");
            final int cardWidth = listMap.widthOf("CRDNUM1I");

            final CardDto.CardListRow atWidth = new CardDto.CardListRow(CardDto.FIRST_ROW_NUMBER, "S", null,
                    "9".repeat(accountWidth), "9".repeat(cardWidth), "Y");
            assertThat(atWidth.getAccountNumber()).hasSize(accountWidth);
            assertThat(atWidth.getCardNumber()).hasSize(cardWidth);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new CardDto.CardListRow(CardDto.FIRST_ROW_NUMBER, "S", null,
                            "9".repeat(accountWidth + 1), "9".repeat(cardWidth), "Y"))
                    .withMessageContaining("accountNumber");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new CardDto.CardListRow(CardDto.FIRST_ROW_NUMBER, "S", null,
                            "9".repeat(accountWidth), "9".repeat(cardWidth + 1), "Y"))
                    .withMessageContaining("cardNumber");
        }

        @Test
        @DisplayName("the row account and card widths equal the screen-level filter widths they mirror")
        void rowWidthsMirrorTheFilterWidths() {
            // The row fields repeat the filter fields, so a divergence between them would mean one of the two
            // was mis-derived.
            for (int rowNumber = CardDto.FIRST_ROW_NUMBER;
                    rowNumber <= CardDto.CARD_LIST_PAGE_SIZE; rowNumber++) {
                assertThat(listMap.widthOf("ACCTNO" + rowNumber + "I"))
                        .as("row %d account width", rowNumber)
                        .isEqualTo(listMap.widthOf("ACCTSIDI"))
                        .isEqualTo(ACCOUNT_ID_WIDTH);
                assertThat(listMap.widthOf("CRDNUM" + rowNumber + "I"))
                        .as("row %d card width", rowNumber)
                        .isEqualTo(listMap.widthOf("CARDSIDI"))
                        .isEqualTo(CARD_NUMBER_WIDTH);
                assertThat(listMap.widthOf("CRDSTS" + rowNumber + "I"))
                        .as("row %d status width", rowNumber)
                        .isEqualTo(1);
            }
        }
    }

    @Nested
    @DisplayName("5. The two factories build the two screen shapes")
    class Factories {

        @Test
        @DisplayName("the detail factory populates the detail fields and leaves the list fields empty")
        void detailFactoryShape() {
            final CardDto card = detail();

            assertThat(card.getTransactionName()).isEqualTo("CCDL");
            assertThat(card.getAccountId()).isEqualTo(SAMPLE_ACCOUNT_ID);
            assertThat(card.getCardNumber()).isEqualTo(SAMPLE_CARD_NUMBER);
            assertThat(card.getCardholderName()).isEqualTo(SAMPLE_CARDHOLDER_NAME);
            assertThat(card.getCardStatusCode()).isEqualTo("Y");
            assertThat(card.getExpiryMonth()).isEqualTo("03");
            assertThat(card.getExpiryYear()).isEqualTo("2023");
            assertThat(card.getPageNumber()).as("COCRDSL declares no page number").isNull();
            assertThat(card.getRows()).as("COCRDSL declares no row array").isNull();
        }

        @Test
        @DisplayName("the list factory populates the rows and leaves the detail-only fields empty")
        void listFactoryShape() {
            final CardDto card = listOf(fullPage());

            assertThat(card.getTransactionName()).isEqualTo("CCLI");
            assertThat(card.getPageNumber()).isEqualTo("001");
            assertThat(card.getRows()).hasSize(CARD_LIST_ROWS);
            assertThat(card.getCardholderName()).as("COCRDLI declares no cardholder name").isNull();
            assertThat(card.getCardStatusCode()).as("COCRDLI declares no status code").isNull();
            assertThat(card.getExpiryMonth()).as("COCRDLI declares no expiry month").isNull();
            assertThat(card.getExpiryYear()).as("COCRDLI declares no expiry year").isNull();
            assertThat(card.getFunctionKeys()).as("COCRDLI declares no function-key legend").isNull();
        }

        @Test
        @DisplayName("a list never carries more rows than the screen has")
        void listCannotExceedThePage() {
            final List<CardDto.CardListRow> tooMany = new ArrayList<>(fullPage());
            tooMany.add(new CardDto.CardListRow(CARD_LIST_ROWS, "S", "U", SAMPLE_ACCOUNT_ID,
                    SAMPLE_CARD_NUMBER, "Y"));

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> listOf(tooMany))
                    .withMessageContaining("rows")
                    .withMessageContaining("COCRDLIC.cbl:177-178");
        }

        @Test
        @DisplayName("the row list is unmodifiable, so a page cannot be grown after construction")
        void rowListIsUnmodifiable() {
            final List<CardDto.CardListRow> rows = listOf(fullPage()).getRows();

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> rows.add(row(CardDto.FIRST_ROW_NUMBER)));
        }

        @Test
        @DisplayName("mutating the caller's list afterwards does not change the payload")
        void rowListIsDefensivelyCopied() {
            final List<CardDto.CardListRow> caller = new ArrayList<>(fullPage());
            final CardDto card = listOf(caller);
            caller.clear();

            assertThat(card.getRows())
                    .as("the payload holds its own copy")
                    .hasSize(CARD_LIST_ROWS);
        }

        @Test
        @DisplayName("rows must be positional, so an out-of-order page is refused")
        void rowsMustBePositional() {
            // Row order is the screen's own ordering; a shuffled page would draw row 3's card against row 1's
            // line. The refusal names positions rather than values.
            final List<CardDto.CardListRow> shuffled = List.of(row(2), row(1));

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> listOf(shuffled))
                    .withMessageContaining("positional");
        }

        @Test
        @DisplayName("nulls are accepted throughout, because an unpopulated screen field is absent")
        void nullsAreAccepted() {
            final CardDto empty = CardDto.detail(null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null);

            assertThat(empty.getTransactionName()).isNull();
            assertThat(empty.getAccountId()).isNull();
            assertThat(empty.getCardNumber()).isNull();
            assertThat(empty.getCardholderName()).isNull();
            assertThat(empty.getExpiryMonth()).isNull();
            assertThat(empty.getRows()).isNull();
        }

        @Test
        @DisplayName("a row accepts nulls in every field except its structural row number")
        void rowAcceptsNullsExceptTheRowNumber() {
            final CardDto.CardListRow blank =
                    new CardDto.CardListRow(CardDto.FIRST_ROW_NUMBER, null, null, null, null, null);

            assertThat(blank.getRowNumber()).isEqualTo(CardDto.FIRST_ROW_NUMBER);
            assertThat(blank.getSelectionFlag()).isNull();
            assertThat(blank.getSelectorType()).isNull();
            assertThat(blank.getAccountNumber()).isNull();
            assertThat(blank.getCardNumber()).isNull();
            assertThat(blank.getStatusCode()).isNull();
        }

        @Test
        @DisplayName("an empty row list is a list payload, not a detail one")
        void anEmptyPageIsStillAListPayload() {
            // The presence of the row list, not its size, is what marks the projection - which is also what
            // selects the per-projection message widths.
            final CardDto emptyPage = listOf(List.of());

            assertThat(emptyPage.getRows()).isNotNull().isEmpty();
            assertThat(emptyPage.getPageNumber()).isNotNull();
        }
    }

    @Nested
    @DisplayName("6. Three abstractions the source refuses, and the evidence that refuses them")
    class DeclinedAbstractions {

        @Test
        @DisplayName("no shared header component is possible: CURTIMEI is 8 here and 9 on the sign-on map")
        void theTerminalHeaderIsNotSharedAcrossTheCorpus() {
            // The six header fields look identical across the corpus and are not. A base class, interface or
            // mixin would have to choose one width for CURTIMEI and would misreport whichever screen it did not
            // choose, so the six fields are declared inline on the type instead. Severity High if unified.
            assertThat(detailMap.widthOf("CURTIMEI")).isEqualTo(8);
            assertThat(listMap.widthOf("CURTIMEI")).isEqualTo(8);
            assertThat(signOnMap.widthOf("CURTIMEI"))
                    .as("app/cpy-bms/COSGN00.CPY:54 declares the same field name one character wider")
                    .isEqualTo(9)
                    .isNotEqualTo(detailMap.widthOf("CURTIMEI"));
        }

        @ParameterizedTest(name = "{0} is uniform at {1} characters across all three maps")
        @CsvSource({
            "TRNNAMEI,  4",
            "TITLE01I, 40",
            "CURDATEI,  8",
            "PGMNAMEI,  8",
            "TITLE02I, 40",
        })
        @DisplayName("the other five header fields are uniform, which is what isolates the divergence")
        void theOtherFiveHeaderFieldsAreUniform(final String field, final int width) {
            // Showing that exactly one of the six diverges is what makes the refusal precise rather than
            // superstitious: five could have been shared, and the sixth is why none is.
            assertThat(detailMap.widthOf(field)).isEqualTo(width);
            assertThat(listMap.widthOf(field)).isEqualTo(width);
            assertThat(signOnMap.widthOf(field)).isEqualTo(width);
        }

        @Test
        @DisplayName("the detail map declares no expiry day, so this payload carries none")
        void theDetailMapDeclaresNoExpiryDay() {
            // COCRDSL runs EXPMONI at :84 and EXPYEARI at :90 and stops. The token EXPDAY appears nowhere in
            // either card read map, so no expiry-day member is added here - not as null, not as an optional,
            // and not for symmetry with the update path.
            assertThat(detailMap.declares("EXPDAYI")).isFalse();
            assertThat(listMap.declares("EXPDAYI")).isFalse();
            assertThat(detailMap.fieldNames()).noneMatch(name -> name.startsWith("EXPDAY"));
            assertThat(listMap.fieldNames()).noneMatch(name -> name.startsWith("EXPDAY"));
            assertThat(accessorNames())
                    .as("and the Java surface exposes no expiry-day accessor either")
                    .noneMatch(name -> containsAnyIgnoringCase(name, "expiryday", "expirationday"));
        }

        @Test
        @DisplayName("the update map does declare one, which is why the two payloads are different types")
        void theUpdateMapDeclaresAnExpiryDay() {
            // app/cpy-bms/COCRDUP.CPY:96 declares EXPDAYI PIC X(2). The update screen therefore has a shape
            // this payload does not, and reusing this type as the update request body would silently drop a
            // field the screen sends. Severity High if conflated.
            assertThat(updateMap.declares("EXPDAYI")).isTrue();
            assertThat(updateMap.widthOf("EXPDAYI")).isEqualTo(2);
            assertThat(updateMap.inputFieldCount())
                    .as("COCRDUP declares 17 input fields against COCRDSL's %d", DETAIL_MAP_FIELDS)
                    .isEqualTo(17)
                    .isNotEqualTo(detailMap.inputFieldCount());
        }

        @Test
        @DisplayName("the update map diverges beyond the expiry day, so the shapes are not nearly the same")
        void theUpdateMapDivergesFurtherStill() {
            // Two more differences, so that the non-conflation rests on shape rather than on one field: the
            // update map splits the function-key legend into two narrower fields where the detail map has one
            // wide one.
            assertThat(updateMap.widthOf("FKEYSI"))
                    .as("COCRDUP:FKEYSI is 21 where COCRDSL:FKEYSI is %d", detailMap.widthOf("FKEYSI"))
                    .isEqualTo(21)
                    .isNotEqualTo(detailMap.widthOf("FKEYSI"));
            assertThat(updateMap.declares("FKEYSCI"))
                    .as("and COCRDUP declares a second legend field the detail map has no counterpart for")
                    .isTrue();
            assertThat(detailMap.declares("FKEYSCI")).isFalse();
        }

        @Test
        @DisplayName("the account identifier stays textual, so a zero-padded value survives intact")
        void theAccountIdentifierIsTextual() {
            // ACCTSIDI is PIC X(11) at COCRDLI:66 and at COCRDSL:60 - note the different line numbers - while
            // COACTVW:60 declares the same name as an expanded numeric. Text is the representation that
            // survives both, and it is the only one that keeps a leading zero. Severity High if numeric.
            assertThat(detailMap.widthOf("ACCTSIDI")).isEqualTo(ACCOUNT_ID_WIDTH);
            assertThat(listMap.widthOf("ACCTSIDI")).isEqualTo(ACCOUNT_ID_WIDTH);
            assertThat(detail().getAccountId())
                    .isEqualTo(SAMPLE_ACCOUNT_ID)
                    .startsWith("0")
                    .hasSize(ACCOUNT_ID_WIDTH);
        }
    }

    @Nested
    @DisplayName("7. The next-page sentinel diverges, so no shared paging state exists")
    class NextPageSentinel {

        @Test
        @DisplayName("the card list encodes 'no next page' as LOW-VALUES, read from the program itself")
        void theCardListSentinelIsLowValues() {
            assertThat(sourceLine(cardListProgram, 242)).isEqualTo("10 WS-CA-NEXT-PAGE-IND PIC X(1).");
            assertThat(sourceLine(cardListProgram, 243))
                    .isEqualTo("88 CA-NEXT-PAGE-NOT-EXISTS VALUE LOW-VALUES.");
            assertThat(sourceLine(cardListProgram, 244)).isEqualTo("88 CA-NEXT-PAGE-EXISTS VALUE 'Y'.");
        }

        @Test
        @DisplayName("the transaction list encodes it as the character 'N' instead")
        void theTransactionListSentinelIsTheLetterN() {
            assertThat(sourceLine(transactionListProgram, 65)).isEqualTo("10 CDEMO-CT00-PAGE-NUM PIC 9(08).");
            assertThat(sourceLine(transactionListProgram, 66))
                    .isEqualTo("10 CDEMO-CT00-NEXT-PAGE-FLG PIC X(01) VALUE 'N'.");
            assertThat(sourceLine(transactionListProgram, 67)).isEqualTo("88 NEXT-PAGE-YES VALUE 'Y'.");
            assertThat(sourceLine(transactionListProgram, 68)).isEqualTo("88 NEXT-PAGE-NO VALUE 'N'.");
        }

        @Test
        @DisplayName("the two negative sentinels are mutually incompatible, so neither can be shared")
        void theTwoNegativeSentinelsAreIncompatible() {
            // Binary zeros against the character 'N'. A shared next-page abstraction would have to pick one,
            // and the list that did not get its own sentinel would report the wrong paging state on every
            // page - silently, because both values are one character wide. Severity High if unified.
            assertThat(sourceLine(cardListProgram, 243)).contains("LOW-VALUES").doesNotContain("'N'");
            assertThat(sourceLine(transactionListProgram, 68)).contains("'N'").doesNotContain("LOW-VALUES");
            assertThat(sourceLine(cardListProgram, 244))
                    .as("only the affirmative sentinel is common ground")
                    .contains("'Y'");
            assertThat(sourceLine(transactionListProgram, 67)).contains("'Y'");
        }

        @Test
        @DisplayName("the last-page indicator is 0 for shown and 9 for not shown, counter-intuitively")
        void theLastPageIndicatorIsZeroForShown() {
            // Zero means the last page HAS been displayed and nine means it has not. Reading these the natural
            // way round inverts the end-of-list behaviour, so the values are pinned as written rather than
            // interpreted.
            assertThat(sourceLine(cardListProgram, 239)).isEqualTo("10 WS-CA-LAST-PAGE-DISPLAYED PIC 9(1).");
            assertThat(sourceLine(cardListProgram, 240)).isEqualTo("88 CA-LAST-PAGE-SHOWN VALUE 0.");
            assertThat(sourceLine(cardListProgram, 241)).isEqualTo("88 CA-LAST-PAGE-NOT-SHOWN VALUE 9.");
        }

        @Test
        @DisplayName("the payload carries no paging sentinel of its own, so it cannot encode either convention")
        void thePayloadCarriesNoSentinel() {
            // The divergence is resolved by keeping the sentinel out of this type altogether: it carries the
            // screen's PAGENOI text and nothing else. The paging envelope owns the rest, so there is no member
            // here for a wrong convention to be written into.
            assertThat(accessorNames())
                    .as("no next-page, more-pages or last-page accessor exists on the payload")
                    .noneMatch(name -> containsAnyIgnoringCase(name, "nextpage", "morepage", "haspage",
                            "lastpage", "pagecount", "totalpage"));
            assertThat(rowAccessorNames())
                    .as("nor on a row")
                    .noneMatch(name -> containsAnyIgnoringCase(name, "nextpage", "morepage", "lastpage"));
        }

        @Test
        @DisplayName("no server-side session state is carried: no routing, context or last-map member exists")
        void noSessionStateIsCarried() {
            // The stateless-transport rule of the migration. The COMMAREA's routing fields, its enter-versus-
            // re-enter flag and its last-map and last-mapset fields have no counterpart here: navigation is by
            // URL and pagination travels in request parameters and response metadata.
            assertThat(accessorNames())
                    .noneMatch(name -> containsAnyIgnoringCase(name, "fromtranid", "totranid", "fromprogram",
                            "toprogram", "pgmcontext", "programcontext", "reenter", "lastmap", "lastmapset",
                            "commarea", "session"));
        }
    }

    @Nested
    @DisplayName("8. The 150-byte card record underneath, and its catalogued alternate index")
    class UnderlyingCardRecord {

        @Test
        @DisplayName("the record is 150 bytes and its fields sit at the offsets the copybook declares")
        void theRecordGeometryIsAsDeclared() {
            // app/cpy/CVACT02Y.cpy:5-11. Read from the copybook so the offsets are derived rather than retyped.
            assertThat(cardRecord.widthOf("CARD-NUM")).isEqualTo(CARD_NUMBER_WIDTH);
            assertThat(cardRecord.widthOf("CARD-ACCT-ID")).isEqualTo(ACCOUNT_ID_WIDTH);
            assertThat(cardRecord.widthOf("CARD-CVV-CD")).isEqualTo(3);
            assertThat(cardRecord.widthOf("CARD-EMBOSSED-NAME")).isEqualTo(50);
            assertThat(cardRecord.widthOf("CARD-ACTIVE-STATUS")).isEqualTo(1);
            assertThat(cardRecord.recordLength())
                    .as("16 + 11 + 3 + 50 + 10 + 1 + %d trailing FILLER", CARD_RECORD_FILLER)
                    .isEqualTo(CARD_RECORD_LENGTH);
        }

        @Test
        @DisplayName("the expiry field name is misspelled in the source and the misspelling is preserved")
        void theExpiryFieldNameIsMisspelled() {
            // CARD-EXPIRAION-DATE is missing the second T, exactly as ACCT-EXPIRAION-DATE is at
            // app/cpy/CVACT01Y.cpy:11. The name is part of the field contract, so it is never "corrected"
            // wherever the source spelling is echoed.
            assertThat(cardRecord.declares("CARD-EXPIRAION-DATE"))
                    .as("the source spells it EXPIRAION, without the second T")
                    .isTrue();
            assertThat(cardRecord.declares("CARD-EXPIRATION-DATE"))
                    .as("the corrected spelling does not exist in the copybook")
                    .isFalse();
        }

        @Test
        @DisplayName("expiry is ten characters of text, so the screen components are positional substrings")
        void expiryIsTextRatherThanADate() {
            // The record holds PIC X(10) text such as 2023-03-09, and the batch expiry comparison is a string
            // comparison. A date or year-month type would normalise away the representation the comparison
            // depends on, so the screen components stay String too.
            assertThat(cardRecord.widthOf("CARD-EXPIRAION-DATE")).isEqualTo(10);
            assertThat(cardRecord.geometry("CARD-EXPIRAION-DATE").numeric())
                    .as("PIC X(10) is alphanumeric, not numeric")
                    .isFalse();
            assertThat(detail().getExpiryMonth()).isInstanceOf(String.class).isEqualTo("03");
            assertThat(detail().getExpiryYear()).isInstanceOf(String.class).isEqualTo("2023");
            assertThat(surfaceTypeNames())
                    .as("no temporal type appears anywhere on the payload's surface")
                    .noneMatch(name -> containsAnyIgnoringCase(name, "LocalDate", "LocalDateTime", "YearMonth",
                            "Instant", "java.util.Date", "Calendar", "OffsetDateTime", "ZonedDateTime"));
        }

        @Test
        @DisplayName("the alternate index sits on the account identifier and is explicitly NON-unique")
        void theAlternateIndexIsNonUnique() {
            // app/catlg/LISTCAT.txt:283 gives AXRKP 16, a zero-based displacement, which resolves to one-based
            // bytes 17-27 - exactly CARD-ACCT-ID, since CARD-NUM occupies 1-16. Line 285 spells the index
            // NONUNIQKEY, so one account may legitimately carry many cards and nothing may imply otherwise.
            // Severity High if a uniqueness constraint is implied.
            assertThat(catalogueLine(202)).contains("KEYLEN----------------16", "AVGLRECL-------------150");
            assertThat(catalogueLine(281)).contains("KEYLEN----------------11");
            assertThat(catalogueLine(283)).isEqualTo("AXRKP-----------------16");
            assertThat(catalogueLine(285))
                    .as("the alternate index is declared NON-unique in the catalogue itself")
                    .contains("NONUNIQKEY");

            final int alternateKeyDisplacement = 16;
            assertThat(alternateKeyDisplacement + 1)
                    .as("a zero-based displacement of %d is one-based byte %d, where CARD-ACCT-ID begins",
                            alternateKeyDisplacement, alternateKeyDisplacement + 1)
                    .isEqualTo(cardRecord.widthOf("CARD-NUM") + 1);
        }

        @Test
        @DisplayName("many rows may repeat one account identifier, which a unique index would forbid")
        void oneAccountMayCarryManyCards() {
            // The behavioural consequence of NONUNIQKEY, asserted rather than merely noted: a page whose rows
            // all cite the same account is legitimate. The seeded fixture happens to pair them one-to-one, but
            // that is a property of the fixture and not of the contract.
            final List<CardDto.CardListRow> sameAccount = new ArrayList<>();
            for (int rowNumber = CardDto.FIRST_ROW_NUMBER; rowNumber <= CARD_LIST_ROWS; rowNumber++) {
                sameAccount.add(new CardDto.CardListRow(rowNumber, "S",
                        rowNumber == CardDto.ROW_NUMBER_WITHOUT_SELECTOR_TYPE ? null : "U",
                        SAMPLE_ACCOUNT_ID, "40000000000000" + String.format(Locale.ROOT, "%02d", rowNumber),
                        "Y"));
            }

            final CardDto page = listOf(sameAccount);
            assertThat(page.getRows()).hasSize(CARD_LIST_ROWS);
            assertThat(page.getRows()).allSatisfy(
                    listRow -> assertThat(listRow.getAccountNumber()).isEqualTo(SAMPLE_ACCOUNT_ID));
        }

        @Test
        @DisplayName("no verification value member exists, so the record's CVV never reaches this payload")
        void theVerificationValueIsNotProjected() {
            // CARD-CVV-CD PIC 9(03) is in the record at bytes 28-30, and neither card screen declares a field
            // for it. Absence is the strongest form of the requirement that it never be rendered.
            assertThat(cardRecord.declares("CARD-CVV-CD")).isTrue();
            assertThat(detailMap.fieldNames()).noneMatch(name -> containsAnyIgnoringCase(name, "CVV"));
            assertThat(listMap.fieldNames()).noneMatch(name -> containsAnyIgnoringCase(name, "CVV"));
            assertThat(accessorNames()).noneMatch(name -> containsAnyIgnoringCase(name, "cvv", "verification",
                    "securitycode", "cvc", "cid"));
            assertThat(rowAccessorNames()).noneMatch(name -> containsAnyIgnoringCase(name, "cvv",
                    "verification", "securitycode", "cvc", "cid"));
        }
    }

    @Nested
    @DisplayName("9. Protected cardholder data never reaches a rendered string")
    class ProtectedCardholderData {

        @Test
        @DisplayName("rendering a detail payload reproduces neither the card number nor the cardholder name")
        void renderingADetailPayloadDisclosesNothing() {
            // Blocker. The type declares no toString, so it inherits the Object rendering, which discloses a
            // class name and an identity hash and no field value at all. Failures below are identified by
            // account identifier, never by the value being protected.
            final String rendered = detail().toString();

            assertThat(rendered)
                    .as("the rendering of the payload for account %s", SAMPLE_ACCOUNT_ID)
                    .doesNotContain(SAMPLE_CARD_NUMBER)
                    .doesNotContain(SAMPLE_CARDHOLDER_NAME);
            assertThat(rendered).startsWith(CardDto.class.getName() + "@");
        }

        @Test
        @DisplayName("rendering a full page reproduces none of its eight card numbers")
        void renderingAPageDisclosesNothing() {
            // A list payload is the worst case: one filter value plus one per row. The page is rendered and
            // every row is rendered, and no card number survives either.
            final CardDto page = listOf(fullPage());
            final StringBuilder rendered = new StringBuilder(page.toString());
            for (final CardDto.CardListRow listRow : page.getRows()) {
                rendered.append(' ').append(listRow.toString());
            }

            assertThat(rendered.toString())
                    .as("the rendering of the page for account %s", SAMPLE_ACCOUNT_ID)
                    .doesNotContain(SAMPLE_CARD_NUMBER);
        }

        @Test
        @DisplayName("no toString is declared on either type, which is what keeps the rendering empty")
        void neitherTypeDeclaresAToString() {
            // Asserting the rendering alone would pass against a toString that merely happened to omit the
            // sample values. Asserting the absence of the declaration is what makes the guarantee structural.
            assertThat(ReflectionCensus.declaredMethodNames(CardDto.class)).doesNotContain("toString");
            assertThat(ReflectionCensus.declaredMethodNames(CardDto.CardListRow.class))
                    .doesNotContain("toString");
        }

        @Test
        @DisplayName("neither type is a record, so no rendering can be generated for it later")
        void neitherTypeIsARecord() {
            // A record's generated toString emits every component, which would have put all eight card numbers
            // and the cardholder name into any log line that rendered this payload. Declining the record
            // removes the hazard rather than guarding it with an override a later edit could delete.
            assertThat(CardDto.class.isRecord()).isFalse();
            assertThat(CardDto.CardListRow.class.isRecord()).isFalse();
        }

        @Test
        @DisplayName("no value equality is published, so no protected field can enter a comparison")
        void noValueEqualityIsPublished() {
            // equals and hashCode are deliberately not overridden: instances compare by identity, business
            // comparison lives in the service layer, and a card number cannot be drawn into an incidental
            // comparison or into a hash bucket.
            assertThat(ReflectionCensus.declaredMethodNames(CardDto.class))
                    .doesNotContain("equals", "hashCode");
            assertThat(ReflectionCensus.declaredMethodNames(CardDto.CardListRow.class))
                    .doesNotContain("equals", "hashCode");

            final CardDto first = detail();
            final CardDto second = detail();
            assertThat(first).isNotSameAs(second).isNotEqualTo(second);
        }

        @Test
        @DisplayName("no credential and no personal identifier beyond the cardholder name exists on the type")
        void noCredentialOrPersonalIdentifierExists() {
            final List<String> everyName = new ArrayList<>(accessorNames());
            everyName.addAll(rowAccessorNames());
            everyName.addAll(ReflectionCensus.declaredFieldNames(CardDto.class, false));
            everyName.addAll(ReflectionCensus.declaredFieldNames(CardDto.CardListRow.class, false));

            assertThat(everyName)
                    .as("neither card map declares any of these, and none may be added")
                    .noneMatch(name -> containsAnyIgnoringCase(name, "password", "passwd", "secret", "token",
                            "credential", "hash", "bcrypt", "ssn", "socialsecurity", "governmentid", "phone",
                            "dateofbirth", "dob", "signingkey"));
        }

        @Test
        @DisplayName("the width refusal names the field and its picture clause but never the rejected value")
        void theWidthRefusalWithholdsTheValue() {
            // The diagnostic has to be actionable without disclosing what was rejected, because the value may
            // be a card number. It reports the field name, the two lengths and the picture clause.
            final String overLongCardNumber = SAMPLE_CARD_NUMBER + "7";

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> detailWithCardNumber(overLongCardNumber))
                    .withMessageContaining("cardNumber")
                    .withMessageContaining("PIC X(" + CARD_NUMBER_WIDTH + ")")
                    .satisfies(thrown -> assertThat(thrown.getMessage())
                            .doesNotContain(overLongCardNumber)
                            .doesNotContain(SAMPLE_CARD_NUMBER));
        }

        @Test
        @DisplayName("the row-level refusal withholds the value too, and identifies the row by number")
        void theRowRefusalWithholdsTheValue() {
            final String overLongCardNumber = SAMPLE_CARD_NUMBER + "7";

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("row %d of the page", CardDto.FIRST_ROW_NUMBER)
                    .isThrownBy(() -> new CardDto.CardListRow(CardDto.FIRST_ROW_NUMBER, "S", null,
                            SAMPLE_ACCOUNT_ID, overLongCardNumber, "Y"))
                    .satisfies(thrown -> assertThat(thrown.getMessage())
                            .contains("cardNumber")
                            .doesNotContain(overLongCardNumber));
        }
    }

    @Nested
    @DisplayName("10. The frozen seed fixtures the widths must round-trip")
    class FrozenFixtures {

        @Test
        @DisplayName("the card fixture is fifty 150-byte records, each ending in exactly 59 spaces")
        void theCardFixtureGeometryHolds() {
            // Loaded through FixtureLoader by classpath resource name only - never copied, edited or written.
            // The trailing run is the record's FILLER and not padding: a whitespace cleanup would shorten every
            // record, break the byte arithmetic and move every field offset past the cut.
            final FixtureLoader.FixtureData cards = FixtureLoader.load(FixtureLoader.Fixture.CARD);

            assertThat(cards.resourceName()).isEqualTo("carddata.txt");
            assertThat(cards.recordCount()).isEqualTo(50);
            assertThat(cards.recordWidth()).isEqualTo(CARD_RECORD_LENGTH);
            assertThat(cards.byteCount()).isEqualTo(cards.impliedByteCount()).isEqualTo(7550);
            for (int index = 0; index < cards.recordCount(); index++) {
                final String record = cards.recordAt(index);
                assertThat(record).as("record %d", index).hasSize(CARD_RECORD_LENGTH);
                assertThat(record.substring(CARD_RECORD_LENGTH - CARD_RECORD_FILLER))
                        .as("the trailing FILLER of record %d", index)
                        .isEqualTo(" ".repeat(CARD_RECORD_FILLER));
            }
        }

        @Test
        @DisplayName("every seeded account identifier is zero-padded, which a numeric type would destroy")
        void everySeededAccountIdentifierIsZeroPadded() {
            final FixtureLoader.FixtureData cards = FixtureLoader.load(FixtureLoader.Fixture.CARD);

            for (int index = 0; index < cards.recordCount(); index++) {
                final String accountId = cards.field(index, 17, ACCOUNT_ID_WIDTH);
                assertThat(accountId).as("account identifier of record %d", index)
                        .hasSize(ACCOUNT_ID_WIDTH)
                        .startsWith("0")
                        .containsOnlyDigits();
                // The payload accepts it unchanged, padding intact, which is the round-trip that matters.
                assertThat(detailWithAccountId(accountId).getAccountId()).isEqualTo(accountId);
            }
        }

        @Test
        @DisplayName("eight of the fifty seeded verification values begin with a zero")
        void eightSeededVerificationValuesBeginWithAZero() {
            // The evidence that the record's CVV is textual despite its PIC 9(03) picture: a numeric type would
            // render 003 as 3. This payload declares no verification value at all, which is why the assertion
            // is made against the fixture rather than against an accessor. Severity High if ever made numeric.
            final FixtureLoader.FixtureData cards = FixtureLoader.load(FixtureLoader.Fixture.CARD);
            final Set<String> leadingZero = new LinkedHashSet<>();
            for (int index = 0; index < cards.recordCount(); index++) {
                final String verificationValue = cards.field(index, 28, 3);
                assertThat(verificationValue).as("record %d", index).hasSize(3).containsOnlyDigits();
                if (verificationValue.startsWith("0")) {
                    leadingZero.add(verificationValue);
                }
            }

            assertThat(leadingZero)
                    .as("bytes 28-30 of app/data/ASCII/carddata.txt")
                    .containsExactlyInAnyOrder("003", "021", "028", "031", "033", "045", "067", "075")
                    .hasSize(8);
        }

        @Test
        @DisplayName("the card fixture holds no signed field, so no overpunch decode belongs near it")
        void theCardFixtureHoldsNoSignedField() {
            // Blocker if a global decoder is applied. There is not one overpunch sign character in the whole
            // fixture, so there is nothing to decode - while the embossed names are full of the very letters a
            // position-blind decoder would treat as signs.
            final FixtureLoader.FixtureData cards = FixtureLoader.load(FixtureLoader.Fixture.CARD);
            final Set<Character> signCharacters = new LinkedHashSet<>();
            final Set<Character> lettersInNames = new LinkedHashSet<>();
            for (int index = 0; index < cards.recordCount(); index++) {
                for (final char character : cards.recordAt(index).toCharArray()) {
                    if (character == '{' || character == '}') {
                        signCharacters.add(Character.valueOf(character));
                    }
                }
                for (final char character : cards.field(index, 31, 50).toCharArray()) {
                    if (character >= 'A' && character <= 'R') {
                        lettersInNames.add(Character.valueOf(character));
                    }
                }
            }

            assertThat(signCharacters)
                    .as("no zoned-decimal sign character occurs anywhere in carddata.txt")
                    .isEmpty();
            assertThat(lettersInNames)
                    .as("but CARD-EMBOSSED-NAME is full of letters a blanket substitution would corrupt")
                    .contains(Character.valueOf('A'), Character.valueOf('I'), Character.valueOf('J'),
                            Character.valueOf('R'));
        }

        @Test
        @DisplayName("the decoder refuses a name slice outright, so it cannot be applied position-blind")
        void theDecoderRefusesANameSlice() {
            // Proving the decoder is position aware rather than trusting that it is. Applied to an embossed
            // name it refuses, and the refusal withholds the content because a name is personal data.
            final FixtureLoader.FixtureData cards = FixtureLoader.load(FixtureLoader.Fixture.CARD);
            final String embossedName = cards.field(0, 31, 50);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> FixtureLoader.decodeZonedDecimal(embossedName,
                            FixtureLoader.DECIMAL_SCALE))
                    .satisfies(thrown -> assertThat(thrown.getMessage())
                            .contains("must be a digit")
                            .doesNotContain(embossedName.strip()));
        }

        @Test
        @DisplayName("the cross-reference fixture is 36 populated bytes in a 50-byte slot and is not padded")
        void theCrossReferenceFixtureIsNotPadded() {
            // 16 + 9 + 11 = 36. The catalogued cluster record size is 50 and the trailing 14-byte FILLER is
            // genuinely absent from the fixture; padding it to 50 to match the cluster would corrupt it.
            final FixtureLoader.FixtureData xref = FixtureLoader.load(FixtureLoader.Fixture.CARD_XREF);

            assertThat(xref.resourceName()).isEqualTo("cardxref.txt");
            assertThat(xref.recordWidth())
                    .isEqualTo(XREF_POPULATED_BYTES)
                    .isEqualTo(CARD_NUMBER_WIDTH + 9 + ACCOUNT_ID_WIDTH)
                    .isNotEqualTo(XREF_CLUSTER_SLOT);
            assertThat(xref.recordCount()).isEqualTo(50);
            assertThat(xref.byteCount()).isEqualTo(1850);
        }

        @Test
        @DisplayName("the cross-reference fixture is already ascending by card number")
        void theCrossReferenceFixtureIsAscending() {
            // The browse order the list screen pages through. Asserted because the seven-row page is only
            // meaningful against a defined ordering.
            final FixtureLoader.FixtureData xref = FixtureLoader.load(FixtureLoader.Fixture.CARD_XREF);
            final List<String> cardNumbers = new ArrayList<>();
            for (int index = 0; index < xref.recordCount(); index++) {
                cardNumbers.add(xref.field(index, 1, CARD_NUMBER_WIDTH));
            }

            assertThat(cardNumbers).isSorted().doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("a fixture card number fills a row exactly, so the seeded data round-trips the page")
        void aSeededPageRoundTrips() {
            // The end-to-end shape of this group: real fixture values, sliced at PIC offsets, carried through a
            // seven-row page unchanged. Rows are numbered from the fixture index, not invented.
            final FixtureLoader.FixtureData xref = FixtureLoader.load(FixtureLoader.Fixture.CARD_XREF);
            final List<CardDto.CardListRow> rows = new ArrayList<>();
            for (int index = 0; index < CARD_LIST_ROWS; index++) {
                final int rowNumber = index + CardDto.FIRST_ROW_NUMBER;
                rows.add(new CardDto.CardListRow(rowNumber, "S",
                        rowNumber == CardDto.ROW_NUMBER_WITHOUT_SELECTOR_TYPE ? null : "U",
                        xref.field(index, 26, ACCOUNT_ID_WIDTH),
                        xref.field(index, 1, CARD_NUMBER_WIDTH), "Y"));
            }

            final CardDto page = listOf(rows);
            assertThat(page.getRows()).hasSize(CARD_LIST_ROWS);
            for (int index = 0; index < CARD_LIST_ROWS; index++) {
                assertThat(page.getRows().get(index).getCardNumber())
                        .as("row %d card number, compared by length and padding rather than by value",
                                index + CardDto.FIRST_ROW_NUMBER)
                        .hasSize(CARD_NUMBER_WIDTH)
                        .isEqualTo(xref.field(index, 1, CARD_NUMBER_WIDTH));
                assertThat(page.getRows().get(index).getAccountNumber()).hasSize(ACCOUNT_ID_WIDTH);
            }
        }

        @Test
        @DisplayName("the daily transaction fixture is spelled in full, and every card number resolves")
        void theDailyTransactionFixtureIsSpelledInFull() {
            // dalytran.txt does not exist. The mainframe DD name is DALYTRAN but the ASCII fixture spells the
            // word out, and code written from the DD name gets a null resource stream.
            final FixtureLoader.FixtureData daily =
                    FixtureLoader.load(FixtureLoader.Fixture.DAILY_TRANSACTION);

            assertThat(daily.resourceName()).isEqualTo("dailytran.txt").isNotEqualTo("dalytran.txt");
            assertThat(daily.recordCount()).isEqualTo(300);

            final Set<String> referenced = new LinkedHashSet<>();
            for (int index = 0; index < daily.recordCount(); index++) {
                referenced.add(daily.field(index, 263, CARD_NUMBER_WIDTH));
            }
            final Set<String> known = new LinkedHashSet<>();
            final FixtureLoader.FixtureData cards = FixtureLoader.load(FixtureLoader.Fixture.CARD);
            for (int index = 0; index < cards.recordCount(); index++) {
                known.add(cards.field(index, 1, CARD_NUMBER_WIDTH));
            }

            assertThat(referenced).hasSize(50);
            assertThat(known.containsAll(referenced))
                    .as("every card number the transaction fixture cites exists in the card fixture")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("11. Absent, blank and low-values stay three distinct states, and both filters are optional")
    class TriStateAndFilters {

        @Test
        @DisplayName("the source models three states, not two, and tests each separately")
        void theSourceModelsThreeStates() {
            // app/cbl/COCRDLIC.cbl:1007-1009 tests LOW-VALUES, then SPACES, then ZEROS as three separate
            // predicates, and :80-82 lists SPACE and LOW-VALUES side by side under one condition name. The
            // template at app/cpy/CSSETATY.cpy models the same OK / NOT-OK / BLANK triple and is procedural, so
            // it has no Java counterpart of its own. Severity High if collapsed.
            assertThat(sourceLine(cardListProgram, 1007)).isEqualTo("IF CC-ACCT-ID EQUAL LOW-VALUES");
            assertThat(sourceLine(cardListProgram, 1008)).isEqualTo("OR CC-ACCT-ID EQUAL SPACES");
            assertThat(sourceLine(cardListProgram, 1009)).isEqualTo("OR CC-ACCT-ID-N EQUAL ZEROS");
            assertThat(sourceLine(cardListProgram, 80)).isEqualTo("88 SELECT-BLANK VALUES");
            assertThat(sourceLine(cardListProgram, 81)).isEqualTo("' ',");
            assertThat(sourceLine(cardListProgram, 82)).isEqualTo("LOW-VALUES.");
        }

        @Test
        @DisplayName("absent, empty and a low-values marker are carried as three different things")
        void theThreeStatesRemainDistinguishable() {
            // Null is never coerced to empty and empty is never coerced to null, so a caller can still tell
            // "the screen sent nothing" from "the screen sent a cleared field".
            final String lowValues = "\u0000";

            assertThat(detailWithCardStatus(null).getCardStatusCode()).isNull();
            assertThat(detailWithCardStatus("").getCardStatusCode()).isNotNull();
            assertThat(detailWithCardStatus("").getCardStatusCode()).isEmpty();
            assertThat(detailWithCardStatus(lowValues).getCardStatusCode())
                    .isEqualTo(lowValues)
                    .isNotEmpty()
                    .isNotEqualTo(" ");
            assertThat(detailWithCardStatus(" ").getCardStatusCode())
                    .as("and a space is a fourth, separate value from a binary zero")
                    .isEqualTo(" ")
                    .isNotEqualTo(lowValues);
        }

        @Test
        @DisplayName("nothing is trimmed, padded or case-folded on the way in")
        void nothingIsNormalisedOnTheWayIn() {
            // Trailing padding is part of a fixed-width value, and folding case would break the byte-exact
            // comparison the parity gate performs.
            final String padded = "AB  ";
            final String mixedCase = "aB";

            assertThat(detailWithInformationMessage(padded).getInformationMessage()).isEqualTo(padded);
            assertThat(detailWithInformationMessage(mixedCase).getInformationMessage()).isEqualTo(mixedCase);
        }

        @Test
        @DisplayName("both filter fields exist at their map widths, and the source calls each optional")
        void bothFiltersExistAndBothAreOptional() {
            // COCRDLIC supports filtering by account and by card. The two diagnostics both read "IF SUPPLIED",
            // which is the source's own statement that neither is required when the other is given.
            assertThat(listMap.widthOf("ACCTSIDI")).isEqualTo(ACCOUNT_ID_WIDTH);
            assertThat(listMap.widthOf("CARDSIDI")).isEqualTo(CARD_NUMBER_WIDTH);
            assertThat(sourceLine(cardListProgram, 1022))
                    .isEqualTo("'ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER'");
            assertThat(sourceLine(cardListProgram, 1058))
                    .isEqualTo("'CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER'");
        }

        @Test
        @DisplayName("supplying one filter without the other is accepted, in either direction")
        void eitherFilterMayStandAlone() {
            final List<CardDto.CardListRow> rows = List.of(row(CardDto.FIRST_ROW_NUMBER));

            final CardDto byAccount = CardDto.list("CCLI", null, null, null, null, null, "001",
                    SAMPLE_ACCOUNT_ID, null, rows, null, null);
            assertThat(byAccount.getAccountId()).isEqualTo(SAMPLE_ACCOUNT_ID);
            assertThat(byAccount.getCardNumber()).as("the card filter was not supplied").isNull();

            final CardDto byCard = CardDto.list("CCLI", null, null, null, null, null, "001", null,
                    SAMPLE_CARD_NUMBER, rows, null, null);
            assertThat(byCard.getAccountId()).as("the account filter was not supplied").isNull();
            assertThat(byCard.getCardNumber()).isNotNull();

            final CardDto neither = CardDto.list("CCLI", null, null, null, null, null, "001", null, null,
                    rows, null, null);
            assertThat(neither.getAccountId()).isNull();
            assertThat(neither.getCardNumber()).isNull();
        }

        @Test
        @DisplayName("the filter tri-state flags in the program carry three values, matching the template")
        void theFilterFlagsCarryThreeValues() {
            // app/cbl/COCRDLIC.cbl:62-64 and :66-68. Three condition names on a one-character flag, which is
            // why absent and invalid are not the same outcome on this screen.
            assertThat(sourceLine(cardListProgram, 62)).isEqualTo("88 FLG-ACCTFILTER-NOT-OK VALUE '0'.");
            assertThat(sourceLine(cardListProgram, 63)).isEqualTo("88 FLG-ACCTFILTER-ISVALID VALUE '1'.");
            assertThat(sourceLine(cardListProgram, 64)).isEqualTo("88 FLG-ACCTFILTER-BLANK VALUE ' '.");
            assertThat(sourceLine(cardListProgram, 66)).isEqualTo("88 FLG-CARDFILTER-NOT-OK VALUE '0'.");
            assertThat(sourceLine(cardListProgram, 67)).isEqualTo("88 FLG-CARDFILTER-ISVALID VALUE '1'.");
            assertThat(sourceLine(cardListProgram, 68)).isEqualTo("88 FLG-CARDFILTER-BLANK VALUE ' '.");
        }
    }

    @Nested
    @DisplayName("12. Hostile input is refused when over-wide and carried verbatim when within width")
    class HostileInput {

        @ParameterizedTest(name = "a {0}-character card number")
        @ValueSource(ints = {0, 1, 15, 16})
        @DisplayName("a card number at or under sixteen characters is carried, padding and all")
        void aCardNumberWithinWidthIsCarried(final int length) {
            // Fifteen is a short value the screen can genuinely send, since the field is fixed width and may
            // arrive part-filled. Under-width is not a width violation; validating the value itself is the
            // service layer's business and not this read payload's.
            final String candidate = "4".repeat(length);

            assertThat(detailWithCardNumber(candidate).getCardNumber())
                    .isEqualTo(candidate)
                    .hasSize(length);
        }

        @ParameterizedTest(name = "a {0}-character card number is refused")
        @ValueSource(ints = {17, 19, 32})
        @DisplayName("a card number beyond sixteen characters is refused")
        void anOverWideCardNumberIsRefused(final int length) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> detailWithCardNumber("4".repeat(length)))
                    .withMessageContaining("cardNumber")
                    .withMessageContaining("PIC X(" + CARD_NUMBER_WIDTH + ")");
        }

        @ParameterizedTest(name = "an {0}-character account identifier")
        @ValueSource(ints = {1, 10, 11})
        @DisplayName("an account identifier at or under eleven characters is carried")
        void anAccountIdentifierWithinWidthIsCarried(final int length) {
            final String candidate = "0".repeat(length - 1) + "1";

            assertThat(detailWithAccountId(candidate).getAccountId())
                    .isEqualTo(candidate)
                    .hasSize(length);
        }

        @ParameterizedTest(name = "a {0}-character account identifier is refused")
        @ValueSource(ints = {12, 16})
        @DisplayName("an account identifier beyond eleven characters is refused")
        void anOverWideAccountIdentifierIsRefused(final int length) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> detailWithAccountId("9".repeat(length)))
                    .withMessageContaining("accountId")
                    .withMessageContaining("PIC X(" + ACCOUNT_ID_WIDTH + ")");
        }

        @ParameterizedTest(name = "an expiry month of {0} characters")
        @CsvSource({"'', 0", "'3', 1", "'03', 2"})
        @DisplayName("an expiry month within its two characters is carried, however short")
        void anExpiryMonthWithinWidthIsCarried(final String candidate, final int length) {
            assertThat(detailWithExpiryMonth(candidate).getExpiryMonth())
                    .isEqualTo(candidate)
                    .hasSize(length);
        }

        @ParameterizedTest(name = "an expiry month of {0} is refused")
        @ValueSource(strings = {"003", "2023", "March"})
        @DisplayName("an expiry month beyond two characters is refused")
        void anOverWideExpiryMonthIsRefused(final String candidate) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> detailWithExpiryMonth(candidate))
                    .withMessageContaining("expiryMonth")
                    .withMessageContaining("PIC X(2)");
        }

        @Test
        @DisplayName("a three-character value in a one-character status field is refused")
        void anOverWideStatusCodeIsRefused() {
            // The shape a verification value would have if one were ever wrongly routed into the status field.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> detailWithCardStatus("003"))
                    .withMessageContaining("cardStatusCode")
                    .withMessageContaining("PIC X(1)");
        }

        @Test
        @DisplayName("an out-of-domain single-character status code is carried rather than rejected")
        void anOutOfDomainStatusCodeIsCarried() {
            // The record declares PIC X(01) and no domain, so binding this to an enumeration would turn an
            // unexpected code into a deserialisation failure and would replace the legacy screen's own message
            // for that condition.
            assertThat(detailWithCardStatus("Q").getCardStatusCode()).isEqualTo("Q");
            assertThat(detailWithCardStatus("9").getCardStatusCode()).isEqualTo("9");
        }

        @Test
        @DisplayName("embossed-name letters in the overpunch range are carried untouched")
        void embossedNameLettersAreCarriedUntouched() {
            // The characters a position-blind overpunch decoder would corrupt. The payload applies no decode
            // and no substitution of any kind, so they survive exactly.
            final String overpunchRange = "ABCDEFGHIJKLMNOPQR";

            assertThat(detailWithCardholderName(overpunchRange).getCardholderName())
                    .isEqualTo(overpunchRange)
                    .hasSize(overpunchRange.length());
        }

        @Test
        @DisplayName("a record one byte short or one byte long is not a valid card record")
        void aRecordOffByOneByteIsNotACardRecord() {
            // The fixed-width geometry stated as a boundary: 149 and 151 are both wrong, which is what makes
            // the loader's byte arithmetic worth having.
            assertThat(CARD_RECORD_LENGTH - 1).isNotEqualTo(cardRecord.recordLength());
            assertThat(CARD_RECORD_LENGTH + 1).isNotEqualTo(cardRecord.recordLength());

            final FixtureLoader.FixtureData cards = FixtureLoader.load(FixtureLoader.Fixture.CARD);
            for (int index = 0; index < cards.recordCount(); index++) {
                assertThat(cards.recordAt(index).length())
                        .as("record %d", index)
                        .isNotEqualTo(CARD_RECORD_LENGTH - 1)
                        .isNotEqualTo(CARD_RECORD_LENGTH + 1)
                        .isEqualTo(cardRecord.recordLength());
            }
        }

        @Test
        @DisplayName("a slice running past the record width is refused rather than silently truncated")
        void aSliceBeyondTheRecordIsRefused() {
            // The one-over case on the loader itself: reading the expiry field one byte too far reports the
            // offsets and never the content.
            final FixtureLoader.FixtureData cards = FixtureLoader.load(FixtureLoader.Fixture.CARD);

            assertThat(cards.field(0, 81, 10)).as("the expiry field read correctly").hasSize(10);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> cards.field(0, CARD_RECORD_LENGTH, 2))
                    .withMessageContaining("runs past");
        }

        @ParameterizedTest(name = "page index {0} is beyond the seven-row page")
        @ValueSource(ints = {8, 9, 10, 20})
        @DisplayName("a page index beyond seven is refused, including the other lists' page sizes")
        void aPageIndexBeyondSevenIsRefused(final int rowNumber) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> row(rowNumber))
                    .withMessageContaining("rowNumber")
                    .withMessageContaining("COCRDLI.CPY");
        }

        @Test
        @DisplayName("a null element inside a page is refused, and the diagnostic names the position")
        void aNullRowIsRefused() {
            final List<CardDto.CardListRow> withHole = new ArrayList<>();
            withHole.add(row(CardDto.FIRST_ROW_NUMBER));
            withHole.add(null);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> listOf(withHole))
                    .withMessageContaining("null element")
                    .withMessageContaining("index 1");
        }

        @Test
        @DisplayName("a width refusal is an originating failure and carries no cause to lose")
        void theWidthRefusalIsAnOriginatingFailure() {
            // Stated rather than assumed: the payload does not catch and re-wrap anything on this path, so the
            // refusal is the root cause. An assertion that the cause is absent is what distinguishes an
            // originating validation failure from a swallowed exception dressed up as one.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> detailWithExpiryMonth("2023"))
                    .withNoCause()
                    .withMessageContaining("expiryMonth");
        }

        @Test
        @DisplayName("the frozen-corpus reader wraps a read failure with context and preserves the root cause")
        void theFrozenReaderWrapsWithContextAndPreservesTheCause() {
            // The one place in this file that catches anything. The catch must never swallow: the message has
            // to name the path a reader needs, and the original must survive as the cause. A member name that
            // deliberately does not exist is the only way to reach the branch.
            final Path absent = Path.of("app", "cbl", "NO-SUCH-MEMBER-FOR-THIS-TEST.cbl");

            assertThatExceptionOfType(UncheckedIOException.class)
                    .isThrownBy(() -> frozenLines(absent))
                    .withMessageContaining(absent.toString())
                    .withCauseInstanceOf(IOException.class);
        }

        @Test
        @DisplayName("no floating-point type appears anywhere on either type's surface")
        void noFloatingPointTypeAppears() {
            // The card maps declare no monetary field, so none is needed; the prohibition is asserted anyway
            // because a decimal value must never be represented approximately anywhere in this tree.
            assertThat(surfaceTypeNames())
                    .noneMatch(name -> containsAnyIgnoringCase(name, "float", "double"));
            assertThat(ReflectionCensus.declaredSurfaceTypeNames(CardDto.CardListRow.class))
                    .noneMatch(name -> containsAnyIgnoringCase(name, "float", "double"));
        }
    }

    // ----------------------------------------------------------------------------------------------------
    // Frozen-corpus readers
    // ----------------------------------------------------------------------------------------------------

    /**
     * Reads one frozen member in full, as an immutable snapshot.
     *
     * <p>{@code ISO_8859_1} is used rather than the platform default so that every byte maps to exactly one
     * character and no offset can shift; the corpus is 7-bit ASCII, so no character is altered. The read is
     * strictly read only: nothing here writes, copies or moves a file.
     *
     * @param path the member's repository-relative path, resolved against the Maven base directory that
     * Surefire sets as the working directory
     * @return the member's lines, unmodifiable and with trailing padding intact
     * @throws UncheckedIOException if the member cannot be read, naming the path and preserving the root cause
     */
    private static List<String> frozenLines(final Path path) {
        try {
            return List.copyOf(Files.readAllLines(path, StandardCharsets.ISO_8859_1));
        } catch (final IOException cause) {
            throw new UncheckedIOException(
                    "cannot read the frozen member " + path + "; it is read only and must be present", cause);
        }
    }

    /**
     * Returns one line of a frozen COBOL member with its runs of whitespace collapsed to single spaces.
     *
     * <p>Collapsing is what makes an assertion both exact and stable: the corpus is space-padded to fixed
     * columns and some declarations continue onto a second line, so comparing raw text would assert the padding
     * rather than the declaration.
     *
     * @param lines the member's lines
     * @param oneBasedLine the line number as a reader of the member would cite it
     * @return the collapsed, trimmed line
     */
    private static String sourceLine(final List<String> lines, final int oneBasedLine) {
        return lines.get(oneBasedLine - 1).replaceAll("\\s+", " ").strip();
    }

    /**
     * Returns one line of the frozen catalogue listing, collapsed the same way.
     *
     * @param oneBasedLine the line number as a reader of the listing would cite it
     * @return the collapsed, trimmed line
     */
    private static String catalogueLine(final int oneBasedLine) {
        return sourceLine(catalogue, oneBasedLine);
    }

    // ----------------------------------------------------------------------------------------------------
    // Reflection helpers
    // ----------------------------------------------------------------------------------------------------

    /**
     * @return every method name the payload declares
     */
    private static List<String> accessorNames() {
        return ReflectionCensus.declaredMethodNames(CardDto.class);
    }

    /**
     * @return every method name one list row declares
     */
    private static List<String> rowAccessorNames() {
        return ReflectionCensus.declaredMethodNames(CardDto.CardListRow.class);
    }

    /**
     * @return every type name appearing on the payload's declared surface
     */
    private static List<String> surfaceTypeNames() {
        return ReflectionCensus.declaredSurfaceTypeNames(CardDto.class);
    }

    /**
     * Reports whether a name contains any of the given fragments, case-insensitively.
     *
     * <p>{@code Locale.ROOT} is passed explicitly so the comparison cannot vary with the platform default
     * locale, which is the kind of environment dependence Rule 1 Clause A rules out.
     *
     * @param name the name to inspect
     * @param fragments the fragments to look for, each already lower case
     * @return {@code true} when at least one fragment occurs
     */
    private static boolean containsAnyIgnoringCase(final String name, final String... fragments) {
        final String folded = name.toLowerCase(Locale.ROOT);
        for (final String fragment : fragments) {
            if (folded.contains(fragment.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    // ----------------------------------------------------------------------------------------------------
    // Payload fixtures. Every value is synthetic; none is a real account number.
    // ----------------------------------------------------------------------------------------------------

    /**
     * @param rowNumber the one-based row ordinal
     * @return a populated card-list row for that ordinal, with no selector type on row 1
     */
    private static CardDto.CardListRow row(final int rowNumber) {
        return new CardDto.CardListRow(rowNumber, "S",
                rowNumber == CardDto.ROW_NUMBER_WITHOUT_SELECTOR_TYPE ? null : "U",
                SAMPLE_ACCOUNT_ID, SAMPLE_CARD_NUMBER, "Y");
    }

    /**
     * @return a full seven-row page in screen order
     */
    private static List<CardDto.CardListRow> fullPage() {
        final List<CardDto.CardListRow> rows = new ArrayList<>(CARD_LIST_ROWS);
        for (int rowNumber = CardDto.FIRST_ROW_NUMBER; rowNumber <= CARD_LIST_ROWS; rowNumber++) {
            rows.add(row(rowNumber));
        }
        return rows;
    }

    /**
     * @param rows the rows to carry
     * @return a list projection carrying them
     */
    private static CardDto listOf(final List<CardDto.CardListRow> rows) {
        return CardDto.list("CCLI", null, null, null, null, null, "001", SAMPLE_ACCOUNT_ID,
                SAMPLE_CARD_NUMBER, rows, null, null);
    }

    /**
     * @return a fully populated detail projection
     */
    private static CardDto detail() {
        return CardDto.detail("CCDL", null, null, null, null, null, SAMPLE_ACCOUNT_ID, SAMPLE_CARD_NUMBER,
                SAMPLE_CARDHOLDER_NAME, "Y", "03", "2023", null, null, null);
    }

    /**
     * @param informationMessage the information message to carry
     * @return a detail projection carrying it
     */
    private static CardDto detailWithInformationMessage(final String informationMessage) {
        return CardDto.detail("CCDL", null, null, null, null, null, SAMPLE_ACCOUNT_ID, SAMPLE_CARD_NUMBER,
                SAMPLE_CARDHOLDER_NAME, "Y", "03", "2023", informationMessage, null, null);
    }

    /**
     * @param informationMessage the information message to carry
     * @return a list projection carrying it
     */
    private static CardDto listWithInformationMessage(final String informationMessage) {
        return CardDto.list("CCLI", null, null, null, null, null, "001", SAMPLE_ACCOUNT_ID, null,
                List.of(), informationMessage, null);
    }

    /**
     * @param errorMessage the error message to carry
     * @return a list projection carrying it
     */
    private static CardDto listWithErrorMessage(final String errorMessage) {
        return CardDto.list("CCLI", null, null, null, null, null, "001", SAMPLE_ACCOUNT_ID, null,
                List.of(), null, errorMessage);
    }

    /**
     * @param errorMessage the error message to carry
     * @return a detail projection carrying it
     */
    private static CardDto detailWithErrorMessage(final String errorMessage) {
        return CardDto.detail("CCDL", null, null, null, null, null, SAMPLE_ACCOUNT_ID, SAMPLE_CARD_NUMBER,
                SAMPLE_CARDHOLDER_NAME, "Y", "03", "2023", null, errorMessage, null);
    }

    /**
     * @param cardholderName the cardholder name to carry
     * @return a detail projection carrying it
     */
    private static CardDto detailWithCardholderName(final String cardholderName) {
        return CardDto.detail("CCDL", null, null, null, null, null, SAMPLE_ACCOUNT_ID, SAMPLE_CARD_NUMBER,
                cardholderName, "Y", "03", "2023", null, null, null);
    }

    /**
     * @param cardNumber the card number to carry
     * @return a detail projection carrying it
     */
    private static CardDto detailWithCardNumber(final String cardNumber) {
        return CardDto.detail("CCDL", null, null, null, null, null, SAMPLE_ACCOUNT_ID, cardNumber,
                SAMPLE_CARDHOLDER_NAME, "Y", "03", "2023", null, null, null);
    }

    /**
     * @param accountId the account identifier to carry
     * @return a detail projection carrying it
     */
    private static CardDto detailWithAccountId(final String accountId) {
        return CardDto.detail("CCDL", null, null, null, null, null, accountId, SAMPLE_CARD_NUMBER,
                SAMPLE_CARDHOLDER_NAME, "Y", "03", "2023", null, null, null);
    }

    /**
     * @param cardStatusCode the status code to carry
     * @return a detail projection carrying it
     */
    private static CardDto detailWithCardStatus(final String cardStatusCode) {
        return CardDto.detail("CCDL", null, null, null, null, null, SAMPLE_ACCOUNT_ID, SAMPLE_CARD_NUMBER,
                SAMPLE_CARDHOLDER_NAME, cardStatusCode, "03", "2023", null, null, null);
    }

    /**
     * @param expiryMonth the expiry month to carry
     * @return a detail projection carrying it
     */
    private static CardDto detailWithExpiryMonth(final String expiryMonth) {
        return CardDto.detail("CCDL", null, null, null, null, null, SAMPLE_ACCOUNT_ID, SAMPLE_CARD_NUMBER,
                SAMPLE_CARDHOLDER_NAME, "Y", expiryMonth, "2023", null, null, null);
    }

    /**
     * @param pageNumber the page number to carry
     * @return a list projection carrying it
     */
    private static CardDto listWithPageNumber(final String pageNumber) {
        return CardDto.list("CCLI", null, null, null, null, null, pageNumber, SAMPLE_ACCOUNT_ID,
                SAMPLE_CARD_NUMBER, List.of(row(CardDto.FIRST_ROW_NUMBER)), null, null);
    }
}


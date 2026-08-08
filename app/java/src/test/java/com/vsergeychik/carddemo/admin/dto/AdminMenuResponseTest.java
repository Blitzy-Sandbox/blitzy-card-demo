package com.vsergeychik.carddemo.admin.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.NavigationContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link AdminMenuResponse}, the outbound projection of {@code COADM1AO} in
 * {@code app/cpy-bms/COADM01.CPY}.
 *
 * <p>Every expected value below is transcribed from the legacy sources - the symbolic map, the
 * mapset and the program - and never read back from the class under test, so the copybook stays the
 * authority. A width, a name or an ordering that drifted in either place fails here.
 *
 * <p>The suite is organised around the five properties of this screen that determine the
 * implementation, so that a failure names a translation decision rather than merely a value:
 *
 * <ol>
 *   <li>There are <strong>20</strong> payload fields, in map order, and their widths sum to
 *       <strong>668</strong> inside an <strong>820</strong>-byte image.</li>
 *   <li>There are <strong>12</strong> option slots even though the program fills 4 and can reach
 *       only 10 - the sharpest dead-code trap in this folder.</li>
 *   <li>{@code OPTIONO} is <strong>textual and zero-filled</strong>: option 1 is {@code "01"}, and
 *       "nothing entered yet" is spaces.</li>
 *   <li>The message colour defaults to {@code DFHRED} by map declaration and is moved to
 *       {@code DFHGREEN} by the program, and it is the <strong>only</strong> attribute byte that
 *       reaches the payload.</li>
 *   <li>No conversation state is held server-side: the communication area and the three navigation
 *       members travel in the body.</li>
 * </ol>
 */
@DisplayName("AdminMenuResponse - the COADM1AO output projection of the CA00 admin menu")
class AdminMenuResponseTest {

    /**
     * The twenty symbolic-map names in map order, transcribed from
     * {@code app/cpy-bms/COADM01.CPY} lines 146 to 260.
     */
    private static final List<String> EXPECTED_FIELDS = List.of("TRNNAMEO",
            "TITLE01O",
            "CURDATEO",
            "PGMNAMEO",
            "TITLE02O",
            "CURTIMEO",
            "OPTN001O",
            "OPTN002O",
            "OPTN003O",
            "OPTN004O",
            "OPTN005O",
            "OPTN006O",
            "OPTN007O",
            "OPTN008O",
            "OPTN009O",
            "OPTN010O",
            "OPTN011O",
            "OPTN012O",
            "OPTIONO",
            "ERRMSGO");

    /**
     * The twenty declared widths in the same order, transcribed from the same lines: {@code X(4)},
     * {@code X(40)}, {@code X(8)}, {@code X(8)}, {@code X(40)}, {@code X(8)}, twelve {@code X(40)},
     * {@code X(2)}, {@code X(78)}.
     */
    private static final List<Integer> EXPECTED_WIDTHS = List.of(4,
            40,
            8,
            8,
            40,
            8,
            40,
            40,
            40,
            40,
            40,
            40,
            40,
            40,
            40,
            40,
            40,
            40,
            2,
            78);

    /**
     * The JSON property names this type publishes, in declaration order. Twenty screen fields, the
     * communication area, three navigation members, the colour attribute and the repaint flag.
     */
    private static final List<String> EXPECTED_JSON_KEYS = List.of("trnName",
            "title01",
            "curDate",
            "pgmName",
            "title02",
            "curTime",
            "optn001",
            "optn002",
            "optn003",
            "optn004",
            "optn005",
            "optn006",
            "optn007",
            "optn008",
            "optn009",
            "optn010",
            "optn011",
            "optn012",
            "option",
            "errMsg",
            "navigationContext",
            "nextProgram",
            "nextMapset",
            "nextMap");

    /**
     * The two components that are deliberately NOT published: the {@code ERRMSGC} attribute byte and the
     * clear-the-screen signal. Both are {@code @JsonIgnore}d - reachable in Java, absent from the
     * document - because {@code app/cpy-bms/COADM01.CPY:256} declares {@code ERRMSGC} as metadata and
     * the reset signal corresponds to no copybook item at all.
     */
    private static final List<String> UNPUBLISHED_MEMBERS =
            List.of("messageColour", "resetAllOutputFields");

    /** The four attribute bytes of the output view plus the three metadata items of the input view. */
    private static final List<String> METADATA_SUFFIXES = List.of("L", "F", "A", "C", "P", "H", "V");

    private static String spaces(final int width) {
        return " ".repeat(width);
    }

    /**
     * A fully populated response, built the way {@code AdminMenuService} would after
     * {@code POPULATE-HEADER-INFO} and {@code BUILD-MENU-OPTIONS} have run on a four-option screen.
     */
    private static AdminMenuResponse populated() {
        return AdminMenuResponse.builder()
                .trnName("CA00")
                .title01("      AWS Mainframe Modernization       ")
                .curDate("07/19/22")
                .pgmName("COADM01C")
                .title02("              CardDemo                  ")
                .curTime("23:12:32")
                .optn001("01. User List (Security)               ")
                .optn002("02. User Add (Security)                ")
                .optn003("03. User Update (Security)             ")
                .optn004("04. User Delete (Security)             ")
                .option("01")
                .errMsg(spaces(AdminMenuResponse.ERR_MSG_LENGTH))
                .navigationContext(NavigationContext.empty()
                        .withUserId("ADMIN001")
                        .withUserTypeAdmin()
                        .withPgmReenter()
                        .withFromTranid("CA00")
                        .withFromProgram("COADM01C"))
                .nextProgram("COUSR00C")
                .build();
    }

    // =================================================================================================

    @Nested
    @DisplayName("Geometry - 20 of 28 DFHMDF fields, 668 payload bytes inside an 820-byte image")
    class Geometry {

        @Test
        @DisplayName("exactly 20 payload fields, in map order, named as the copybook names them")
        void payloadFieldsMatchTheCopybookExactly() {
            assertThat(AdminMenuResponse.PAYLOAD_FIELDS)
                    .as("the xxxO items of COADM1AO, in map order")
                    .containsExactlyElementsOf(EXPECTED_FIELDS)
                    .hasSize(20);
            assertThat(AdminMenuResponse.PAYLOAD_FIELD_COUNT).isEqualTo(20);
            assertThat(AdminMenuResponse.NAMED_MAP_FIELD_COUNT).isEqualTo(20);
        }

        @Test
        @DisplayName("28 DFHMDF definitions in the mapset, 20 named and 8 unnamed literals")
        void mapsetFieldCountsSplitTwentyEight() {
            assertThat(AdminMenuResponse.MAPSET_FIELD_COUNT).isEqualTo(28);
            assertThat(AdminMenuResponse.UNNAMED_MAP_FIELD_COUNT).isEqualTo(8);
            assertThat(AdminMenuResponse.NAMED_MAP_FIELD_COUNT
                    + AdminMenuResponse.UNNAMED_MAP_FIELD_COUNT)
                    .isEqualTo(AdminMenuResponse.MAPSET_FIELD_COUNT);
        }

        @Test
        @DisplayName("the declared widths are 4, 40, 8, 8, 40, 8, forty times twelve, 2, 78")
        void declaredWidthsMatchThePictureClauses() {
            assertThat(AdminMenuResponse.PAYLOAD_FIELD_LENGTHS)
                    .containsExactlyElementsOf(EXPECTED_WIDTHS);
            assertThat(AdminMenuResponse.TRN_NAME_LENGTH).isEqualTo(4);
            assertThat(AdminMenuResponse.TITLE_LENGTH).isEqualTo(40);
            assertThat(AdminMenuResponse.CUR_DATE_LENGTH).isEqualTo(8);
            assertThat(AdminMenuResponse.PGM_NAME_LENGTH).isEqualTo(8);
            assertThat(AdminMenuResponse.CUR_TIME_LENGTH).isEqualTo(8);
            assertThat(AdminMenuResponse.OPTION_LINE_LENGTH).isEqualTo(40);
            assertThat(AdminMenuResponse.OPTION_LINE_COUNT).isEqualTo(12);
            assertThat(AdminMenuResponse.OPTION_LENGTH).isEqualTo(2);
            assertThat(AdminMenuResponse.ERR_MSG_LENGTH).isEqualTo(78);
        }

        @Test
        @DisplayName("ERRMSGO is 78 - never the 80 of WS-MESSAGE and never the 50 of the invalid-key text")
        void errMsgIsSeventyEightNotEightyNorFifty() {
            assertThat(AdminMenuResponse.ERR_MSG_LENGTH).isEqualTo(78).isNotEqualTo(80).isNotEqualTo(50);
        }

        @Test
        @DisplayName("the twenty widths sum to 668")
        void payloadWidthsSumToSixHundredAndSixtyEight() {
            assertThat(EXPECTED_WIDTHS.stream().mapToInt(Integer::intValue).sum()).isEqualTo(668);
            assertThat(AdminMenuResponse.PAYLOAD_DATA_LENGTH).isEqualTo(668);
        }

        @Test
        @DisplayName("the whole image is 12 + 20 times 7 + 668 = 820 bytes")
        void symbolicMapImageIsEightHundredAndTwenty() {
            assertThat(AdminMenuResponse.TIOAPFX_FILLER_LENGTH).isEqualTo(12);
            assertThat(AdminMenuResponse.ATTRIBUTE_FILLER_LENGTH).isEqualTo(3);
            assertThat(AdminMenuResponse.ATTRIBUTE_PREFIX_LENGTH)
                    .as("FILLER X(3) plus the four attribute bytes xxxC, xxxP, xxxH, xxxV")
                    .isEqualTo(7);
            assertThat(AdminMenuResponse.SYMBOLIC_MAP_LENGTH)
                    .isEqualTo(12 + 20 * 7 + 668)
                    .isEqualTo(820);
        }

        @Test
        @DisplayName("the screen is 24 by 80, as the single DFHMDI declares")
        void screenIsTwentyFourByEighty() {
            assertThat(AdminMenuResponse.SCREEN_ROWS).isEqualTo(24);
            assertThat(AdminMenuResponse.SCREEN_COLUMNS).isEqualTo(80);
        }

        @Test
        @DisplayName("names, widths and values stay positionally aligned across all three views")
        void namesWidthsAndValuesAreAligned() {
            final AdminMenuResponse response = populated();
            assertThat(AdminMenuResponse.PAYLOAD_FIELDS).hasSameSizeAs(EXPECTED_FIELDS);
            assertThat(AdminMenuResponse.PAYLOAD_FIELD_LENGTHS).hasSameSizeAs(EXPECTED_FIELDS);
            assertThat(response.payloadValues()).hasSameSizeAs(EXPECTED_FIELDS);
            assertThat(response.payloadValues().get(0)).isEqualTo(response.trnName());
            assertThat(response.payloadValues().get(6)).isEqualTo(response.optn001());
            assertThat(response.payloadValues().get(17)).isEqualTo(response.optn012());
            assertThat(response.payloadValues().get(18)).isEqualTo(response.option());
            assertThat(response.payloadValues().get(19)).isEqualTo(response.errMsg());
        }

        @Test
        @DisplayName("the screen identity is CA00 / COADM01C / COADM01 / COADM1A")
        void screenIdentityMatchesTheCsdTheProgramAndTheMapset() {
            assertThat(AdminMenuResponse.TRANSACTION_ID).isEqualTo("CA00");
            assertThat(AdminMenuResponse.PROGRAM_NAME).isEqualTo("COADM01C");
            assertThat(AdminMenuResponse.MAPSET_NAME).isEqualTo("COADM01");
            assertThat(AdminMenuResponse.MAP_NAME).isEqualTo("COADM1A");
            assertThat(AdminMenuResponse.TRANSACTION_ID).hasSize(AdminMenuResponse.TRN_NAME_LENGTH);
            assertThat(AdminMenuResponse.PROGRAM_NAME).hasSize(AdminMenuResponse.PGM_NAME_LENGTH);
            assertThat(AdminMenuResponse.MAPSET_NAME)
                    .as("a mapset name is X(7), because the eighth character is the direction suffix")
                    .hasSize(7);
            assertThat(AdminMenuResponse.MAP_NAME).hasSize(7);
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("Twelve option slots - four filled, ten reachable, twelve declared")
    class TwelveSlots {

        @Test
        @DisplayName("all twelve slots exist and are addressable on a blank response")
        void allTwelveSlotsAreAddressableWhenUnset() {
            final AdminMenuResponse blank = AdminMenuResponse.empty();
            assertThat(blank.optionLines())
                    .hasSize(12)
                    .allSatisfy(line -> assertThat(line)
                            .isNotNull()
                            .hasSize(AdminMenuResponse.OPTION_LINE_LENGTH)
                            .isBlank());
        }

        @Test
        @DisplayName("slots 5 to 12 are addressable even though the program never fills them")
        void slotsFiveToTwelveAreAddressableThoughNeverFilled() {
            final AdminMenuResponse response = populated();
            final String blankLine = spaces(AdminMenuResponse.OPTION_LINE_LENGTH);
            assertThat(response.optn005()).isEqualTo(blankLine);
            assertThat(response.optn006()).isEqualTo(blankLine);
            assertThat(response.optn007()).isEqualTo(blankLine);
            assertThat(response.optn008()).isEqualTo(blankLine);
            assertThat(response.optn009()).isEqualTo(blankLine);
            assertThat(response.optn010()).isEqualTo(blankLine);
            assertThat(response.optn011())
                    .as("OPTN011O has no assignment anywhere in COADM01C, yet the mapset declares it")
                    .isEqualTo(blankLine);
            assertThat(response.optn012())
                    .as("OPTN012O likewise")
                    .isEqualTo(blankLine);
        }

        @ParameterizedTest(name = "WS-IDX = {0} addresses OPTN0{0}O")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12})
        @DisplayName("optionLine accepts every COBOL subscript from 1 to 12")
        void optionLineAcceptsEveryCobolSubscript(final int cobolSubscript) {
            final String marker = "line " + cobolSubscript;
            final AdminMenuResponse response = AdminMenuResponse.builder()
                    .optionLine(cobolSubscript, marker)
                    .build();
            assertThat(response.optionLine(cobolSubscript)).isEqualTo(marker);
            assertThat(response.optionLines().get(cobolSubscript - 1))
                    .as("a one-based COBOL subscript is a zero-based Java index")
                    .isEqualTo(marker);
        }

        @ParameterizedTest(name = "subscript {0} is rejected")
        @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 13, 99, Integer.MAX_VALUE})
        @DisplayName("optionLine rejects a subscript outside the declared twelve, on both sides")
        void optionLineRejectsOutOfRangeSubscripts(final int cobolSubscript) {
            final AdminMenuResponse blank = AdminMenuResponse.empty();
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> blank.optionLine(cobolSubscript))
                    .withMessageContaining("1 to 12")
                    .withMessageContaining("COADM01");
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> AdminMenuResponse.builder().optionLine(cobolSubscript, "x"))
                    .withMessageContaining("1 to 12");
        }

        @Test
        @DisplayName("the twelve slot names are OPTN001O to OPTN012O in order")
        void slotNamesRunFromOptn001oToOptn012o() {
            assertThat(AdminMenuResponse.OPTION_LINE_FIELDS)
                    .containsExactly("OPTN001O",
                            "OPTN002O",
                            "OPTN003O",
                            "OPTN004O",
                            "OPTN005O",
                            "OPTN006O",
                            "OPTN007O",
                            "OPTN008O",
                            "OPTN009O",
                            "OPTN010O",
                            "OPTN011O",
                            "OPTN012O");
        }

        @Test
        @DisplayName("the slot count is twelve - not the four the program fills, not the ten it can reach")
        void slotCountIsTwelveNotFourAndNotTen() {
            assertThat(AdminMenuResponse.OPTION_LINE_COUNT).isEqualTo(12).isNotEqualTo(4).isNotEqualTo(10);
            assertThat(AdminMenuResponse.OPTION_LINE_FIELDS).hasSize(12);
            assertThat(AdminMenuResponse.empty().optionLines()).hasSize(12);
        }

        @Test
        @DisplayName("each named slot setter writes only its own slot")
        void eachNamedSetterWritesOnlyItsOwnSlot() {
            final List<UnaryOperator<AdminMenuResponse.Builder>> setters = List.of(
                    builder -> builder.optn001("v"),
                    builder -> builder.optn002("v"),
                    builder -> builder.optn003("v"),
                    builder -> builder.optn004("v"),
                    builder -> builder.optn005("v"),
                    builder -> builder.optn006("v"),
                    builder -> builder.optn007("v"),
                    builder -> builder.optn008("v"),
                    builder -> builder.optn009("v"),
                    builder -> builder.optn010("v"),
                    builder -> builder.optn011("v"),
                    builder -> builder.optn012("v"));

            for (int slot = 1; slot <= setters.size(); slot++) {
                final AdminMenuResponse response =
                        setters.get(slot - 1).apply(AdminMenuResponse.builder()).build();
                assertThat(response.optionLine(slot)).as("slot %d written", slot).isEqualTo("v");
                assertThat(response.optionLines())
                        .as("only slot %d written", slot)
                        .filteredOn("v"::equals)
                        .hasSize(1);
            }
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("The initial state - MOVE LOW-VALUES TO COADM1AO before the first SEND")
    class InitialState {

        @Test
        @DisplayName("every text field is space-filled to its own declared width")
        void everyTextFieldIsSpaceFilledToItsDeclaredWidth() {
            final AdminMenuResponse blank = AdminMenuResponse.empty();
            final List<String> values = blank.payloadValues();
            for (int i = 0; i < values.size(); i++) {
                assertThat(values.get(i))
                        .as("%s is spaces at its declared width", EXPECTED_FIELDS.get(i))
                        .isEqualTo(spaces(EXPECTED_WIDTHS.get(i)));
            }
        }

        @Test
        @DisplayName("the communication area is the initial 160-byte COMMAREA")
        void communicationAreaIsTheInitialCommarea() {
            assertThat(AdminMenuResponse.empty().navigationContext())
                    .isEqualTo(NavigationContext.empty());
            assertThat(NavigationContext.COMMAREA_LENGTH).isEqualTo(160);
        }

        @Test
        @DisplayName("the screen to render is this screen, and the next program is left undecided")
        void nextScreenIsThisScreenAndNextProgramIsUndecided() {
            final AdminMenuResponse blank = AdminMenuResponse.empty();
            assertThat(blank.nextMapset()).isEqualTo("COADM01");
            assertThat(blank.nextMap()).isEqualTo("COADM1A");
            assertThat(blank.nextProgram())
                    .as("choosing an XCTL target is a decision, and decisions belong to the service")
                    .isEqualTo(spaces(AdminMenuResponse.NEXT_PROGRAM_LENGTH))
                    .isBlank();
        }

        @Test
        @DisplayName("the repaint signal is off on a blank instance")
        void repaintSignalIsOffOnABlankInstance() {
            assertThat(AdminMenuResponse.empty().resetAllOutputFields()).isFalse();
            assertThat(AdminMenuResponse.builder().resetAllOutputFields(true).build()
                    .resetAllOutputFields()).isTrue();
        }

        @Test
        @DisplayName("builder() and empty() cannot drift apart")
        void builderAndEmptyAgree() {
            assertThat(AdminMenuResponse.builder().build()).isEqualTo(AdminMenuResponse.empty());
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("Absent members become their COBOL-initial values, because COBOL has no null")
    class NullNormalisation {

        @Test
        @DisplayName("an absent character member becomes spaces at its declared width")
        void absentCharacterMembersBecomeSpaces() {
            final AdminMenuResponse response = new AdminMenuResponse(null,
                    null, null, null, null, null,
                    null, null, null, null, null, null,
                    null, null, null, null, null, null,
                    null, null, null, null, null, null,
                    BmsAttributes.DFHRED, false);

            assertThat(response.trnName()).isEqualTo(spaces(4));
            assertThat(response.title01()).isEqualTo(spaces(40));
            assertThat(response.curDate()).isEqualTo(spaces(8));
            assertThat(response.pgmName()).isEqualTo(spaces(8));
            assertThat(response.title02()).isEqualTo(spaces(40));
            assertThat(response.curTime()).isEqualTo(spaces(8));
            assertThat(response.optionLines()).allSatisfy(line -> assertThat(line).isEqualTo(spaces(40)));
            assertThat(response.option()).isEqualTo(spaces(2));
            assertThat(response.errMsg()).isEqualTo(spaces(78));
            assertThat(response.nextProgram()).isEqualTo(spaces(8));
            assertThat(response.nextMapset()).isEqualTo(spaces(7));
            assertThat(response.nextMap()).isEqualTo(spaces(7));
        }

        @Test
        @DisplayName("an absent communication area becomes the initial COMMAREA, never null")
        void absentCommunicationAreaBecomesTheInitialCommarea() {
            final AdminMenuResponse response = AdminMenuResponse.builder()
                    .navigationContext(null)
                    .build();
            assertThat(response.navigationContext())
                    .isNotNull()
                    .isEqualTo(NavigationContext.empty());
        }

        @Test
        @DisplayName("a supplied value is stored verbatim - never padded, never trimmed, never truncated")
        void suppliedValuesAreStoredVerbatim() {
            final String shorterThanDeclared = "1";
            final String longerThanDeclared = "x".repeat(200);
            final AdminMenuResponse response = AdminMenuResponse.builder()
                    .option(shorterThanDeclared)
                    .errMsg(longerThanDeclared)
                    .navigationContext(NavigationContext.empty().withUserId("USER0001"))
                    .build();

            assertThat(response.option())
                    .as("padding is FixedWidthCodec's MOVE rule, not this type's")
                    .isEqualTo("1");
            assertThat(response.errMsg())
                    .as("narrowing X(80) to X(78) is the controller's work, not this type's")
                    .isEqualTo(longerThanDeclared);
            assertThat(response.navigationContext().userId()).isEqualTo("USER0001");
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("OPTIONO is textual and zero-filled - MOVE WS-OPTION PIC 9(02) TO OPTIONO PIC X(2)")
    class OptionEcho {

        @ParameterizedTest(name = "WS-OPTION {0} renders as \"{1}\"")
        @CsvSource({"1,01", "2,02", "3,03", "4,04", "9,09", "10,10", "12,12", "99,99"})
        @DisplayName("a single-digit option keeps its leading zero")
        void singleDigitOptionsKeepTheirLeadingZero(final String entered, final String rendered) {
            final AdminMenuResponse response = AdminMenuResponse.builder().option(rendered).build();
            assertThat(response.option())
                    .as("WS-OPTION %s moved into a PIC X(2) receiver", entered)
                    .isEqualTo(rendered)
                    .hasSize(AdminMenuResponse.OPTION_LENGTH);
        }

        @Test
        @DisplayName("\"nothing entered yet\" is spaces - a third state no integer has")
        void nothingEnteredYetIsSpaces() {
            assertThat(AdminMenuResponse.empty().option()).isEqualTo("  ").isNotEqualTo("00");
        }

        @Test
        @DisplayName("the echo is a String, so \"01\" and \"1\" stay distinct")
        void theEchoIsTextualSoLeadingZerosAreSignificant() {
            assertThat(AdminMenuResponse.builder().option("01").build().option())
                    .isNotEqualTo(AdminMenuResponse.builder().option("1").build().option());
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("Navigation - EXEC CICS XCTL becomes three response members")
    class Navigation {

        @ParameterizedTest(name = "nextProgram can carry {0}")
        @ValueSource(strings = {"COUSR00C", "COUSR01C", "COUSR02C", "COUSR03C", "COSGN00C"})
        @DisplayName("both XCTL sites are expressible: the four option targets and the sign-on return")
        void bothXctlSitesAreExpressible(final String target) {
            final AdminMenuResponse response =
                    AdminMenuResponse.builder().nextProgram(target).build();
            assertThat(response.nextProgram())
                    .isEqualTo(target)
                    .hasSizeLessThanOrEqualTo(AdminMenuResponse.NEXT_PROGRAM_LENGTH);
        }

        @Test
        @DisplayName("the sign-on literal of COADM01C is recorded, but is not a default")
        void signonProgramIsRecordedButNotDefaulted() {
            assertThat(AdminMenuResponse.SIGNON_PROGRAM).isEqualTo("COSGN00C");
            assertThat(AdminMenuResponse.empty().nextProgram())
                    .isNotEqualTo(AdminMenuResponse.SIGNON_PROGRAM);
        }

        @Test
        @DisplayName("the navigation widths come from the COMMAREA: a program is 8, a map is 7")
        void navigationWidthsComeFromTheCommarea() {
            assertThat(AdminMenuResponse.NEXT_PROGRAM_LENGTH)
                    .isEqualTo(8)
                    .isEqualTo(NavigationContext.TO_PROGRAM_LENGTH);
            assertThat(AdminMenuResponse.NEXT_MAPSET_LENGTH)
                    .isEqualTo(7)
                    .isEqualTo(NavigationContext.LAST_MAPSET_LENGTH);
            assertThat(AdminMenuResponse.NEXT_MAP_LENGTH)
                    .isEqualTo(7)
                    .isEqualTo(NavigationContext.LAST_MAP_LENGTH);
        }

        @Test
        @DisplayName("the screen to render is overridable, so the client can be sent elsewhere")
        void theScreenToRenderIsOverridable() {
            final AdminMenuResponse response = AdminMenuResponse.builder()
                    .nextMapset("COUSR00")
                    .nextMap("COUSR0A")
                    .build();
            assertThat(response.nextMapset()).isEqualTo("COUSR00");
            assertThat(response.nextMap()).isEqualTo("COUSR0A");
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("The message colour - the only attribute byte that reaches the payload")
    class MessageColour {

        @Test
        @DisplayName("a freshly built response carries DFHRED, as the mapset's COLOR=RED declares")
        void freshResponseCarriesDfhred() {
            assertThat(AdminMenuResponse.empty().messageColour()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(AdminMenuResponse.builder().build().messageColour())
                    .isEqualTo(BmsAttributes.DFHRED);
            assertThat(populated().messageColour()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(BmsAttributes.DFHRED).isEqualTo((byte) 0xF2);
        }

        @Test
        @DisplayName("the coming-soon path can move DFHGREEN in, exactly as COADM01C:148 does")
        void comingSoonPathCanMoveDfhgreenIn() {
            final AdminMenuResponse response = populated().toBuilder()
                    .messageColour(BmsAttributes.DFHGREEN)
                    .errMsg("This option is coming soon ...")
                    .build();
            assertThat(response.messageColour()).isEqualTo(BmsAttributes.DFHGREEN);
            assertThat(BmsAttributes.DFHGREEN).isEqualTo((byte) 0xF4);
            assertThat(response.errMsg())
                    .as("the admin text carries no option name - COADM01C:150-151 are commented out")
                    .isEqualTo("This option is coming soon ...")
                    .doesNotContain("User List");
        }

        @Test
        @DisplayName("the colour renders as its DFHBMSCA mnemonic and as unsigned hex")
        void colourRendersAsMnemonicAndHex() {
            assertThat(AdminMenuResponse.empty().messageColourMnemonic())
                    .isEqualTo(BmsAttributes.colourMnemonic(BmsAttributes.DFHRED));
            assertThat(AdminMenuResponse.empty().messageColourHex())
                    .isEqualTo(BmsAttributes.toHex(BmsAttributes.DFHRED));
            assertThat(AdminMenuResponse.builder().messageColour(BmsAttributes.DFHGREEN).build()
                    .messageColourMnemonic())
                    .isEqualTo(BmsAttributes.colourMnemonic(BmsAttributes.DFHGREEN));
        }

        @Test
        @DisplayName("the default is never the terminal default colour")
        void defaultIsNeverDfhdfcol() {
            assertThat(AdminMenuResponse.empty().messageColour())
                    .as("a builder whose primitive defaulted would give DFHDFCOL")
                    .isNotEqualTo(BmsAttributes.DFHDFCOL);
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("JSON - twenty screen fields plus five carriers, and no attribute metadata")
    class Json {

        private final ObjectMapper mapper = new ObjectMapper();

        private Map<String, Object> serialize(final AdminMenuResponse response) throws Exception {
            @SuppressWarnings("unchecked")
            final Map<String, Object> map = mapper.readValue(mapper.writeValueAsString(response),
                    LinkedHashMap.class);
            return map;
        }

        @Test
        @DisplayName("the published keys are exactly the twenty-six declared members")
        void publishedKeysAreExactlyTheDeclaredMembers() throws Exception {
            assertThat(serialize(populated()).keySet())
                    .containsExactlyInAnyOrderElementsOf(EXPECTED_JSON_KEYS);
        }

        @Test
        @DisplayName("a non-default colour and reset signal do not survive a round trip, by design")
        void theColourDoesNotTravel() throws Exception {
            final AdminMenuResponse coloured = populated().toBuilder()
                    .messageColour(BmsAttributes.DFHGREEN)
                    .resetAllOutputFields(true)
                    .build();

            final AdminMenuResponse after =
                    mapper.readValue(mapper.writeValueAsString(coloured), AdminMenuResponse.class);

            assertThat(after.messageColour()).isEqualTo(BmsAttributes.DFHDFCOL);
            assertThat(after.resetAllOutputFields()).isFalse();
            // ...while everything that traces to a DFHMDF definition crosses unchanged.
            assertThat(after.payloadValues()).containsExactlyElementsOf(coloured.payloadValues());
            assertThat(after.navigationContext()).isEqualTo(coloured.navigationContext());
        }

        @Test
        @DisplayName("the ERRMSGC colour byte and the reset signal are not JSON properties")
        void theTwoMetadataMembersAreNotPublished() throws Exception {
            final AdminMenuResponse response = populated().toBuilder()
                    .messageColour(BmsAttributes.DFHGREEN)
                    .resetAllOutputFields(true)
                    .build();

            assertThat(serialize(response).keySet()).doesNotContainAnyElementsOf(UNPUBLISHED_MEMBERS);

            // Both are still components of the record - reachable in Java, where the service sets them
            // and this assertion reads them - which is what "internal" means here.
            assertThat(response.messageColour()).isEqualTo(BmsAttributes.DFHGREEN);
            assertThat(response.resetAllOutputFields()).isTrue();
            assertThat(Stream.of(AdminMenuResponse.class.getRecordComponents())
                    .map(java.lang.reflect.RecordComponent::getName).toList())
                    .containsAll(UNPUBLISHED_MEMBERS);
        }

        @Test
        @DisplayName("no xxxL, xxxF, xxxA, xxxC, xxxP, xxxH or xxxV item leaks into the payload")
        void noAttributeMetadataLeaksIntoThePayload() throws Exception {
            final List<String> keys = new ArrayList<>(serialize(populated()).keySet());
            final List<String> forbidden = new ArrayList<>();
            for (final String base : EXPECTED_JSON_KEYS.subList(0, 20)) {
                for (final String suffix : METADATA_SUFFIXES) {
                    forbidden.add(base + suffix);
                }
            }
            for (final String base : EXPECTED_FIELDS) {
                final String stem = base.substring(0, base.length() - 1);
                for (final String suffix : METADATA_SUFFIXES) {
                    forbidden.add(stem + suffix);
                }
            }
            assertThat(keys).doesNotContainAnyElementsOf(forbidden);
            assertThat(keys)
                    .as("the four attribute bytes are metadata and the colour is carried explicitly")
                    .doesNotContain("errMsgC", "ERRMSGC", "errMsgA", "trnNameL", "optn001C");
        }

        @Test
        @DisplayName("no derived view is published")
        void noDerivedViewIsPublished() throws Exception {
            assertThat(serialize(populated()).keySet())
                    .doesNotContain("optionLines",
                            "payloadValues",
                            "messageColourMnemonic",
                            "messageColourHex");
        }

        @Test
        @DisplayName("a round trip preserves all twenty fields, spaces and leading zeros included")
        void roundTripPreservesEveryField() throws Exception {
            final AdminMenuResponse original = populated();
            final String json = mapper.writeValueAsString(original);
            final AdminMenuResponse restored = mapper.readValue(json, AdminMenuResponse.class);

            // Every published member survives; the two ignored ones do not travel, so whole-object
            // equality is deliberately NOT the property under test here - see theColourDoesNotTravel.
            assertThat(restored.payloadValues()).containsExactlyElementsOf(original.payloadValues());
            assertThat(restored.option()).isEqualTo("01");
            assertThat(restored.optn012())
                    .as("an all-spaces value must not be trimmed away")
                    .isEqualTo(spaces(AdminMenuResponse.OPTION_LINE_LENGTH));
            assertThat(restored.navigationContext()).isEqualTo(original.navigationContext());
            // The colour is not on the wire, so it comes back as DFHDFCOL - the terminal's default
            // colour, X'00' - rather than being carried. That is the point of ignoring it: it is a
            // presentation attribute the server decides on each response, not state a client echoes.
            assertThat(restored.messageColour()).isEqualTo(BmsAttributes.DFHDFCOL);
        }

        @Test
        @DisplayName("a blank response round-trips too, and absent keys normalise rather than fail")
        void blankResponseRoundTrips() throws Exception {
            final AdminMenuResponse blank = AdminMenuResponse.empty();
            final AdminMenuResponse afterBlank =
                    mapper.readValue(mapper.writeValueAsString(blank), AdminMenuResponse.class);

            // Every published member survives a blank round trip...
            assertThat(afterBlank.payloadValues()).containsExactlyElementsOf(blank.payloadValues());
            assertThat(afterBlank.navigationContext()).isEqualTo(blank.navigationContext());
            assertThat(afterBlank.nextMapset()).isEqualTo(blank.nextMapset());
            assertThat(afterBlank.nextMap()).isEqualTo(blank.nextMap());

            // ...and the two ignored members come back at the Java defaults the canonical constructor
            // receives, because nothing about them reached the document. DFHDFCOL is X'00', the
            // terminal's own default colour, so the result is a coherent response rather than a
            // nonsense attribute.
            assertThat(afterBlank.messageColour()).isEqualTo(BmsAttributes.DFHDFCOL);
            assertThat(afterBlank.resetAllOutputFields()).isFalse();
            assertThat(mapper.readValue("{}", AdminMenuResponse.class).optionLines())
                    .hasSize(12)
                    .allSatisfy(line -> assertThat(line).isEqualTo(spaces(40)));
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("Immutability - a handed-out response cannot change underneath its holder")
    class Immutability {

        @Test
        @DisplayName("the twelve option lines are handed back unmodifiable")
        void optionLinesAreUnmodifiable() {
            final List<String> lines = AdminMenuResponse.empty().optionLines();
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> lines.set(0, "mutated"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> lines.add("appended"));
            assertThat(AdminMenuResponse.empty().optionLines().get(0)).isEqualTo(spaces(40));
        }

        @Test
        @DisplayName("the twenty payload values are handed back unmodifiable")
        void payloadValuesAreUnmodifiable() {
            final List<String> values = populated().payloadValues();
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> values.set(0, "mutated"));
            assertThat(populated().trnName()).isEqualTo("CA00");
        }

        @Test
        @DisplayName("the static name and width tables are unmodifiable")
        void staticTablesAreUnmodifiable() {
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> AdminMenuResponse.PAYLOAD_FIELDS.set(0, "mutated"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> AdminMenuResponse.OPTION_LINE_FIELDS.clear());
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> AdminMenuResponse.PAYLOAD_FIELD_LENGTHS.set(0, 99));
            assertThat(AdminMenuResponse.PAYLOAD_FIELDS).containsExactlyElementsOf(EXPECTED_FIELDS);
        }

        @Test
        @DisplayName("toBuilder produces a copy, leaving the original untouched")
        void toBuilderProducesACopy() {
            final AdminMenuResponse original = populated();
            final AdminMenuResponse modified = original.toBuilder()
                    .messageColour(BmsAttributes.DFHGREEN)
                    .optn001("changed")
                    .nextProgram("COSGN00C")
                    .build();

            assertThat(original.messageColour()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(original.optn001()).isEqualTo("01. User List (Security)               ");
            assertThat(original.nextProgram()).isEqualTo("COUSR00C");
            assertThat(modified.messageColour()).isEqualTo(BmsAttributes.DFHGREEN);
            assertThat(modified.optn001()).isEqualTo("changed");
            assertThat(modified.nextProgram()).isEqualTo("COSGN00C");
        }

        @Test
        @DisplayName("reusing a builder does not disturb a value it already produced")
        void reusingABuilderDoesNotDisturbAnAlreadyBuiltValue() {
            final AdminMenuResponse.Builder builder = AdminMenuResponse.builder().optn001("first");
            final AdminMenuResponse first = builder.build();
            builder.optn001("second").optn002("also second");
            final AdminMenuResponse second = builder.build();

            assertThat(first.optn001()).isEqualTo("first");
            assertThat(first.optn002()).isEqualTo(spaces(40));
            assertThat(second.optn001()).isEqualTo("second");
            assertThat(second.optn002()).isEqualTo("also second");
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("Statelessness - the conversation travels in the body, not on the server")
    class Statelessness {

        @Test
        @DisplayName("the echoed COMMAREA carries the user type and the program context back")
        void echoedCommareaCarriesUserTypeAndProgramContext() {
            final AdminMenuResponse response = populated();
            assertThat(response.navigationContext().isAdmin()).isTrue();
            assertThat(response.navigationContext().isUser()).isFalse();
            assertThat(response.navigationContext().isReenter()).isTrue();
            assertThat(response.navigationContext().isEnter()).isFalse();
            assertThat(response.navigationContext().userId()).isEqualTo("ADMIN001");
        }

        @Test
        @DisplayName("the first-entry repaint is a payload flag, not a server-side screen buffer")
        void firstEntryRepaintIsAPayloadFlag() {
            final AdminMenuResponse firstEntry = AdminMenuResponse.builder()
                    .navigationContext(NavigationContext.empty().withPgmReenter())
                    .resetAllOutputFields(true)
                    .build();
            assertThat(firstEntry.resetAllOutputFields()).isTrue();
            assertThat(firstEntry.navigationContext().isReenter()).isTrue();
            assertThat(firstEntry.payloadValues())
                    .as("LOW-VALUES leaves every output field blank")
                    .allSatisfy(value -> assertThat(value).isBlank());
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("Value semantics - every one of the twenty-six members participates in equality")
    class ValueSemantics {

        static Stream<Arguments> oneChangePerMember() {
            return Stream.of(
                    Arguments.of("trnName", op(builder -> builder.trnName("ZZZZ"))),
                    Arguments.of("title01", op(builder -> builder.title01("changed"))),
                    Arguments.of("curDate", op(builder -> builder.curDate("01/01/00"))),
                    Arguments.of("pgmName", op(builder -> builder.pgmName("ZZZZZZZZ"))),
                    Arguments.of("title02", op(builder -> builder.title02("changed"))),
                    Arguments.of("curTime", op(builder -> builder.curTime("00:00:00"))),
                    Arguments.of("optn001", op(builder -> builder.optn001("changed"))),
                    Arguments.of("optn002", op(builder -> builder.optn002("changed"))),
                    Arguments.of("optn003", op(builder -> builder.optn003("changed"))),
                    Arguments.of("optn004", op(builder -> builder.optn004("changed"))),
                    Arguments.of("optn005", op(builder -> builder.optn005("changed"))),
                    Arguments.of("optn006", op(builder -> builder.optn006("changed"))),
                    Arguments.of("optn007", op(builder -> builder.optn007("changed"))),
                    Arguments.of("optn008", op(builder -> builder.optn008("changed"))),
                    Arguments.of("optn009", op(builder -> builder.optn009("changed"))),
                    Arguments.of("optn010", op(builder -> builder.optn010("changed"))),
                    Arguments.of("optn011", op(builder -> builder.optn011("changed"))),
                    Arguments.of("optn012", op(builder -> builder.optn012("changed"))),
                    Arguments.of("option", op(builder -> builder.option("99"))),
                    Arguments.of("errMsg", op(builder -> builder.errMsg("changed"))),
                    Arguments.of("navigationContext",
                            op(builder -> builder.navigationContext(
                                    NavigationContext.empty().withUserId("OTHER001")))),
                    Arguments.of("nextProgram", op(builder -> builder.nextProgram("COSGN00C"))),
                    Arguments.of("nextMapset", op(builder -> builder.nextMapset("COMEN01"))),
                    Arguments.of("nextMap", op(builder -> builder.nextMap("COMEN1A"))),
                    Arguments.of("messageColour",
                            op(builder -> builder.messageColour(BmsAttributes.DFHGREEN))),
                    Arguments.of("resetAllOutputFields",
                            op(builder -> builder.resetAllOutputFields(true))));
        }

        private static UnaryOperator<AdminMenuResponse.Builder> op(
                final UnaryOperator<AdminMenuResponse.Builder> operator) {
            return operator;
        }

        @ParameterizedTest(name = "changing {0} breaks equality")
        @MethodSource("oneChangePerMember")
        @DisplayName("a difference in any single member is observable")
        void aDifferenceInAnySingleMemberIsObservable(
                final String member, final UnaryOperator<AdminMenuResponse.Builder> change) {
            final AdminMenuResponse original = populated();
            final AdminMenuResponse changed = change.apply(original.toBuilder()).build();

            assertThat(changed).as("%s must participate in equals", member).isNotEqualTo(original);
            assertThat(changed.hashCode())
                    .as("%s must participate in hashCode", member)
                    .isNotEqualTo(original.hashCode());
        }

        @Test
        @DisplayName("two identically built responses are equal and share a hash code")
        void identicallyBuiltResponsesAreEqual() {
            assertThat(populated()).isEqualTo(populated()).hasSameHashCodeAs(populated());
            assertThat(AdminMenuResponse.empty()).isEqualTo(AdminMenuResponse.empty());
        }

        @Test
        @DisplayName("a response is not equal to null or to an unrelated type")
        void aResponseIsNotEqualToNullOrToAnUnrelatedType() {
            final AdminMenuResponse response = populated();
            assertThat(response).isNotEqualTo(null).isNotEqualTo("CA00").isEqualTo(response);
        }

        @Test
        @DisplayName("toString names the type and carries the screen fields")
        void toStringNamesTheTypeAndCarriesTheScreenFields() {
            assertThat(populated().toString())
                    .contains("AdminMenuResponse")
                    .contains("trnName=CA00")
                    .contains("option=01");
        }
    }
}

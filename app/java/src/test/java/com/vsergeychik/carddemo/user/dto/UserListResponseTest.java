package com.vsergeychik.carddemo.user.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.user.dto.UserListResponse.Row;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Parity tests for {@link UserListResponse}, the {@code CU00} list-users payload projected from
 * {@code app/cpy-bms/COUSR00.CPY}, {@code app/bms/COUSR00.bms} and {@code app/cbl/COUSR00C.cbl}.
 *
 * <p>The suite is organised around the properties of the screen that determine the implementation, so
 * a failure names a translation decision rather than merely a value:
 *
 * <ol>
 *   <li>The map declares exactly <strong>59</strong> addressable fields, and this type projects
 *       exactly those - traced field by field against the labelled {@code DFHMDF} lines of the real
 *       mapset, not against a copy of the list.</li>
 *   <li>Every width is the one the {@code PICTURE} clause declares: {@code CURTIME} is 8 and
 *       {@code ERRMSG} is 78.</li>
 *   <li>Ten rows are the screen. A short final page carries ten rows with the tail blank - never
 *       omitted, never {@code null} - and row {@code 1} is element {@code 0}.</li>
 *   <li>The {@code CU00} extension is 34 bytes on top of a 160-byte communication area, and the
 *       page number is an integral type because its picture is {@code 9(08)}.</li>
 *   <li>No control byte, no {@code FILLER} and no derived predicate reaches the payload.</li>
 * </ol>
 */
@DisplayName("UserListResponse - the CU00 list-users payload of COUSR00C")
class UserListResponseTest {

    /**
     * The 59 map-derived Java component names in map order, written out here independently of the
     * production list so that the two can disagree. Asserting {@code FIELD_NAMES} against itself
     * would prove nothing.
     */
    private static final List<String> EXPECTED_MAP_COMPONENTS = expectedMapComponents();

    /** The declared width of every fixed-width component, keyed by component name. */
    private static final Map<String, Integer> EXPECTED_WIDTHS = expectedWidths();

    private static List<String> expectedMapComponents() {
        List<String> names = new ArrayList<>(List.of("trnName", "title01", "curDate", "pgmName",
                "title02", "curTime", "pageNum", "usrIdIn"));
        for (int row = 1; row <= 10; row++) {
            names.add(String.format("sel%04d", row));
            names.add(String.format("usrId%02d", row));
            names.add(String.format("fname%02d", row));
            names.add(String.format("lname%02d", row));
            names.add(String.format("utype%02d", row));
        }
        names.add("errMsg");
        return List.copyOf(names);
    }

    private static Map<String, Integer> expectedWidths() {
        Map<String, Integer> widths = new LinkedHashMap<>();
        widths.put("trnName", 4);
        widths.put("title01", 40);
        widths.put("curDate", 8);
        widths.put("pgmName", 8);
        widths.put("title02", 40);
        widths.put("curTime", 8);
        widths.put("pageNum", 8);
        widths.put("usrIdIn", 8);
        for (int row = 1; row <= 10; row++) {
            widths.put(String.format("sel%04d", row), 1);
            widths.put(String.format("usrId%02d", row), 8);
            widths.put(String.format("fname%02d", row), 20);
            widths.put(String.format("lname%02d", row), 20);
            widths.put(String.format("utype%02d", row), 1);
        }
        widths.put("errMsg", 78);
        widths.put("cdemoCu00UsrIdFirst", 8);
        widths.put("cdemoCu00UsrIdLast", 8);
        widths.put("cdemoCu00NextPageFlg", 1);
        widths.put("cdemoCu00UsrSelFlg", 1);
        widths.put("cdemoCu00UsrSelected", 8);
        widths.put("nextProgram", 8);
        widths.put("nextMapset", 7);
        widths.put("nextMap", 7);
        return Map.copyOf(widths);
    }

    /** The record component names, in declaration order. */
    private static List<String> componentNames() {
        return Stream.of(UserListResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    /**
     * The COBOL field name a map-derived Java component projects, spelled as the mapset spells it.
     * The selection column carries four digits and the other four carry two; that asymmetry is the
     * point of the method.
     */
    private static String cobolNameOf(String componentName) {
        Matcher selection = Pattern.compile("^sel(\\d{4})$").matcher(componentName);
        if (selection.matches()) {
            return "SEL" + selection.group(1);
        }
        Matcher rowCell = Pattern.compile("^(usrId|fname|lname|utype)(\\d{2})$")
                .matcher(componentName);
        if (rowCell.matches()) {
            return rowCell.group(1).toUpperCase(java.util.Locale.ROOT) + rowCell.group(2);
        }
        return switch (componentName) {
            case "trnName" -> "TRNNAME";
            case "title01" -> "TITLE01";
            case "curDate" -> "CURDATE";
            case "pgmName" -> "PGMNAME";
            case "title02" -> "TITLE02";
            case "curTime" -> "CURTIME";
            case "pageNum" -> "PAGENUM";
            case "usrIdIn" -> "USRIDIN";
            case "errMsg" -> "ERRMSG";
            default -> throw new IllegalArgumentException(componentName + " is not map-derived");
        };
    }

    /** The labelled {@code DFHMDF} field names of the real mapset, in declaration order. */
    private static Optional<List<String>> mapsetFieldLabels() {
        Optional<Path> mapset = locateRepositoryFile("app/bms/COUSR00.bms");
        if (mapset.isEmpty()) {
            return Optional.empty();
        }
        Pattern labelled = Pattern.compile("^([A-Z0-9]+)\\s+DFHMDF");
        try {
            List<String> labels = new ArrayList<>();
            for (String line : Files.readAllLines(mapset.get(), StandardCharsets.ISO_8859_1)) {
                Matcher matcher = labelled.matcher(line);
                if (matcher.find()) {
                    labels.add(matcher.group(1));
                }
            }
            return Optional.of(List.copyOf(labels));
        } catch (IOException problem) {
            throw new IllegalStateException("Could not read " + mapset.get(), problem);
        }
    }

    /** Walks up from the working directory looking for a repository-relative path. */
    private static Optional<Path> locateRepositoryFile(String repositoryRelativePath) {
        Path candidate = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 8 && candidate != null; depth++) {
            Path resolved = candidate.resolve(repositoryRelativePath);
            if (Files.isRegularFile(resolved)) {
                return Optional.of(resolved);
            }
            candidate = candidate.getParent();
        }
        return Optional.empty();
    }

    /** A response with ten fully populated rows, used wherever real values matter. */
    private static UserListResponse fullPage() {
        UserListResponse.Builder builder = UserListResponse.builder()
                .trnName(UserListResponse.TRANSACTION_ID)
                .title01("AWS Mainframe Modernization - CardDemo  ")
                .curDate("08/08/26")
                .pgmName(UserListResponse.PROGRAM_NAME)
                .title02("List Users                              ")
                .curTime("11:22:33")
                .pageNum("00000001")
                .usrIdIn(" ")
                .cdemoCu00UsrIdFirst("USER0001")
                .cdemoCu00UsrIdLast("USER0010")
                .cdemoCu00PageNum(1)
                .nextPageYes()
                .nextMapset(UserListResponse.MAPSET_NAME)
                .nextMap(UserListResponse.MAP_NAME);
        for (int row = 1; row <= UserListResponse.ROW_COUNT; row++) {
            builder.populateRow(row,
                    String.format("USER%04d", row),
                    String.format("First%02d", row),
                    String.format("Last%02d", row),
                    row % 2 == 0 ? "A" : "U");
        }
        return builder.build();
    }

    @Nested
    @DisplayName("The field inventory - exactly 59 map-derived members")
    class FieldInventory {

        @Test
        @DisplayName("59 map-derived members: 8 header + 50 row + 1 message")
        void mapFieldCountIs59() {
            assertThat(UserListResponse.MAP_FIELD_COUNT).isEqualTo(59);
            assertThat(8 + UserListResponse.ROW_COUNT * UserListResponse.ROW_FIELD_COUNT + 1)
                    .isEqualTo(UserListResponse.MAP_FIELD_COUNT);
            assertThat(UserListResponse.FIELD_NAMES).hasSize(59);
            assertThat(EXPECTED_MAP_COMPONENTS).hasSize(59);
        }

        @Test
        @DisplayName("69 components: 59 map-derived, 6 CU00 context, 3 navigation, 1 commarea")
        void componentCountIs69() {
            assertThat(UserListResponse.COMPONENT_COUNT).isEqualTo(69);
            assertThat(componentNames()).hasSize(UserListResponse.COMPONENT_COUNT);
            assertThat(UserListResponse.MAP_FIELD_COUNT + 6 + 3 + 1)
                    .isEqualTo(UserListResponse.COMPONENT_COUNT);
        }

        @Test
        @DisplayName("the components appear in the map's own order, map-derived first")
        void componentsAreInMapOrder() {
            List<String> components = componentNames();
            assertThat(components.subList(0, UserListResponse.MAP_FIELD_COUNT))
                    .containsExactlyElementsOf(EXPECTED_MAP_COMPONENTS);
            assertThat(components.subList(UserListResponse.MAP_FIELD_COUNT, components.size()))
                    .containsExactly("cdemoCu00UsrIdFirst", "cdemoCu00UsrIdLast",
                            "cdemoCu00PageNum", "cdemoCu00NextPageFlg", "cdemoCu00UsrSelFlg",
                            "cdemoCu00UsrSelected", "nextProgram", "nextMapset", "nextMap",
                            "navigationContext");
        }

        @Test
        @DisplayName("every map-derived component projects the field name FIELD_NAMES declares")
        void componentsProjectTheDeclaredFieldNames() {
            List<String> projected = EXPECTED_MAP_COMPONENTS.stream()
                    .map(UserListResponseTest::cobolNameOf)
                    .toList();
            assertThat(projected).containsExactlyElementsOf(UserListResponse.FIELD_NAMES);
        }

        @Test
        @DisplayName("FIELD_NAMES traces one for one to the labelled DFHMDF lines of the mapset")
        void fieldNamesTraceToTheMapset() {
            Optional<List<String>> labels = mapsetFieldLabels();
            Assumptions.assumeTrue(labels.isPresent(),
                    "app/bms/COUSR00.bms is not reachable from " + Paths.get("").toAbsolutePath());
            assertThat(labels.get())
                    .as("labelled DFHMDF fields of app/bms/COUSR00.bms")
                    .hasSize(UserListResponse.MAP_FIELD_COUNT)
                    .containsExactlyElementsOf(UserListResponse.FIELD_NAMES);
        }

        @Test
        @DisplayName("the selection column is spelled with four digits, the data columns with two")
        void rowFieldNamesKeepTheirSourceSpelling() {
            assertThat(UserListResponse.FIELD_NAMES)
                    .contains("SEL0001", "SEL0009", "SEL0010")
                    .contains("USRID01", "USRID10", "FNAME01", "FNAME10",
                            "LNAME01", "LNAME10", "UTYPE01", "UTYPE10")
                    .doesNotContain("SEL01", "SEL10", "USRID0001", "UTYPE0010");
            for (int row = 1; row <= UserListResponse.ROW_COUNT; row++) {
                assertThat(UserListResponse.FIELD_NAMES)
                        .contains(String.format("SEL%04d", row),
                                String.format("USRID%02d", row),
                                String.format("FNAME%02d", row),
                                String.format("LNAME%02d", row),
                                String.format("UTYPE%02d", row));
            }
        }

        @Test
        @DisplayName("USRIDIN keeps its own spelling; COUSR01's USERID is not harmonised in")
        void usrIdInIsNotHarmonised() {
            assertThat(UserListResponse.USRIDIN_FIELD).isEqualTo("USRIDIN");
            assertThat(UserListResponse.FIELD_NAMES).doesNotContain("USERID");
        }

        @Test
        @DisplayName("no control byte, FILLER or staging-area field is a member")
        void controlBytesAreNotMembers() {
            List<String> components = componentNames();
            for (String component : components) {
                assertThat(component).doesNotEndWith("L").doesNotEndWith("F").doesNotEndWith("A")
                        .doesNotEndWith("C").doesNotEndWith("P").doesNotEndWith("H")
                        .doesNotEndWith("V");
            }
            assertThat(components).noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT)
                    .contains("filler"));
            assertThat(components).doesNotContain("userName", "userSel", "userType", "pageSize");
        }

        @Test
        @DisplayName("the field-name list is immutable, so exposing it shares no mutable state")
        void fieldNamesAreImmutable() {
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> UserListResponse.FIELD_NAMES.add("SEL0011"));
            assertThat(UserListResponse.blank().fieldNames())
                    .isSameAs(UserListResponse.FIELD_NAMES);
        }
    }

    @Nested
    @DisplayName("Declared widths, read off the PICTURE clauses")
    class DeclaredWidths {

        @Test
        @DisplayName("the header widths are 4, 40, 8, 8, 40, 8, 8 and 8")
        void headerWidths() {
            assertThat(UserListResponse.TRNNAME_LENGTH).isEqualTo(4);
            assertThat(UserListResponse.TITLE01_LENGTH).isEqualTo(40);
            assertThat(UserListResponse.CURDATE_LENGTH).isEqualTo(8);
            assertThat(UserListResponse.PGMNAME_LENGTH).isEqualTo(8);
            assertThat(UserListResponse.TITLE02_LENGTH).isEqualTo(40);
            assertThat(UserListResponse.PAGENUM_LENGTH).isEqualTo(8);
            assertThat(UserListResponse.USRIDIN_LENGTH).isEqualTo(8);
        }

        @Test
        @DisplayName("CURTIME is 8, not the 9 that only COSGN00 uses")
        void curTimeIsEight() {
            assertThat(UserListResponse.CURTIME_LENGTH).isEqualTo(8);
        }

        @Test
        @DisplayName("ERRMSG is 78, not the 80 of WS-MESSAGE")
        void errMsgIsSeventyEight() {
            assertThat(UserListResponse.ERRMSG_LENGTH).isEqualTo(78);
            assertThat(UserListResponse.ERRMSG_LENGTH).isNotEqualTo(80);
        }

        @Test
        @DisplayName("the row-cell widths are 1, 8, 20, 20 and 1")
        void rowWidths() {
            assertThat(UserListResponse.SEL_LENGTH).isEqualTo(1);
            assertThat(UserListResponse.USRID_LENGTH).isEqualTo(8);
            assertThat(UserListResponse.FNAME_LENGTH).isEqualTo(20);
            assertThat(UserListResponse.LNAME_LENGTH).isEqualTo(20);
            assertThat(UserListResponse.UTYPE_LENGTH).isEqualTo(1);
        }

        @Test
        @DisplayName("the symbolic map is 1127 bytes: 12 + 7 x 59 + 702")
        void symbolicMapGeometry() {
            int controlBytes = 7 * UserListResponse.MAP_FIELD_COUNT;
            int data = 124 + UserListResponse.ROW_COUNT * 50 + UserListResponse.ERRMSG_LENGTH;
            assertThat(controlBytes).isEqualTo(413);
            assertThat(data).isEqualTo(702);
            assertThat(12 + controlBytes + data)
                    .isEqualTo(UserListResponse.SYMBOLIC_MAP_LENGTH)
                    .isEqualTo(1127);
        }

        @Test
        @DisplayName("blank() fills every fixed-width member to exactly its declared width")
        void blankFillsEveryMemberToItsDeclaredWidth() throws ReflectiveOperationException {
            UserListResponse blank = UserListResponse.blank();
            for (RecordComponent component : UserListResponse.class.getRecordComponents()) {
                Integer declared = EXPECTED_WIDTHS.get(component.getName());
                if (declared == null) {
                    continue;
                }
                Object value = component.getAccessor().invoke(blank);
                assertThat(value)
                        .as("%s must be a String", component.getName())
                        .isInstanceOf(String.class);
                // Spaces everywhere except the next-page flag, which its declaration initialises to
                // 'N' - app/cbl/COUSR00C.cbl line 70 - and which is therefore not blank at rest.
                String expected = component.getName().equals("cdemoCu00NextPageFlg")
                        ? UserListResponse.NEXT_PAGE_NO
                        : " ".repeat(declared);
                assertThat((String) value)
                        .as("%s is declared PIC X(%d)", component.getName(), declared)
                        .hasSize(declared)
                        .isEqualTo(expected);
            }
            assertThat(EXPECTED_WIDTHS).hasSize(UserListResponse.MAP_FIELD_COUNT + 5 + 3);
        }

        @Test
        @DisplayName("blank() carries the copybook VALUE 'N' and a zero page number")
        void blankCarriesTheCopybookDefaults() {
            UserListResponse blank = UserListResponse.blank();
            assertThat(blank.cdemoCu00NextPageFlg()).isEqualTo(UserListResponse.NEXT_PAGE_NO);
            assertThat(blank.cdemoCu00PageNum()).isZero();
            assertThat(blank.navigationContext()).isEqualTo(NavigationContext.empty());
        }

        @ParameterizedTest(name = "{0} rejects a value wider than {1}")
        @CsvSource({
            "trnName,4", "title01,40", "curTime,8", "pageNum,8", "usrIdIn,8", "errMsg,78",
            "sel0001,1", "usrId01,8", "fname01,20", "lname01,20", "utype01,1",
            "cdemoCu00UsrIdFirst,8", "cdemoCu00NextPageFlg,1", "nextProgram,8",
            "nextMapset,7", "nextMap,7",
        })
        @DisplayName("a value wider than its PICTURE is rejected, never truncated")
        void overWideValuesAreRejected(String component, int declaredWidth) {
            String tooLong = "X".repeat(declaredWidth + 1);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> withComponent(component, tooLong))
                    .withMessageContaining(cobolOrContextNameOf(component))
                    .withMessageContaining("PIC X(" + declaredWidth + ")");
        }

        @Test
        @DisplayName("an 80-character message is rejected here; the 80-to-78 MOVE is the caller's")
        void theEightyToSeventyEightMoveIsNotPerformedHere() {
            String eighty = "M".repeat(80);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> UserListResponse.builder().errMsg(eighty).build())
                    .withMessageContaining("ERRMSG")
                    .withMessageContaining("80 character(s)");
            String seventyEight = eighty.substring(0, UserListResponse.ERRMSG_LENGTH);
            assertThat(UserListResponse.builder().errMsg(seventyEight).build().errMsg())
                    .hasSize(78)
                    .isEqualTo(seventyEight);
        }

        @Test
        @DisplayName("a shorter value is accepted unchanged - padding belongs to the codec")
        void shorterValuesAreAcceptedUnchanged() {
            assertThat(UserListResponse.builder().title01("List Users").build().title01())
                    .isEqualTo("List Users");
        }

        @ParameterizedTest(name = "{0} rejects null")
        @ValueSource(strings = {"trnName", "curTime", "pageNum", "usrIdIn", "errMsg", "sel0010",
            "usrId10", "fname10", "lname10", "utype10", "cdemoCu00UsrIdLast",
            "cdemoCu00UsrSelFlg", "cdemoCu00UsrSelected", "nextProgram", "nextMap"})
        @DisplayName("null is rejected: a COBOL screen field holds spaces, never null")
        void nullIsRejected(String component) {
            assertThatNullPointerException()
                    .isThrownBy(() -> withComponent(component, null))
                    .withMessageContaining(cobolOrContextNameOf(component));
        }

        @Test
        @DisplayName("the communication area is not optional")
        void navigationContextIsRequired() {
            assertThatNullPointerException()
                    .isThrownBy(() -> UserListResponse.builder().navigationContext(null).build())
                    .withMessageContaining("CARDDEMO-COMMAREA");
        }
    }

    /** Builds a response with one component replaced, for the width and null checks. */
    private static UserListResponse withComponent(String component, String value) {
        UserListResponse.Builder builder = UserListResponse.builder();
        switch (component) {
            case "trnName" -> builder.trnName(value);
            case "title01" -> builder.title01(value);
            case "curDate" -> builder.curDate(value);
            case "pgmName" -> builder.pgmName(value);
            case "title02" -> builder.title02(value);
            case "curTime" -> builder.curTime(value);
            case "pageNum" -> builder.pageNum(value);
            case "usrIdIn" -> builder.usrIdIn(value);
            case "sel0001" -> builder.selection(1, value);
            case "sel0010" -> builder.selection(10, value);
            case "usrId01" -> builder.populateRow(1, value, "F", "L", "U");
            case "fname01" -> builder.populateRow(1, "U0000001", value, "L", "U");
            case "lname01" -> builder.populateRow(1, "U0000001", "F", value, "U");
            case "utype01" -> builder.populateRow(1, "U0000001", "F", "L", value);
            case "usrId10" -> builder.populateRow(10, value, "F", "L", "U");
            case "fname10" -> builder.populateRow(10, "U0000010", value, "L", "U");
            case "lname10" -> builder.populateRow(10, "U0000010", "F", value, "U");
            case "utype10" -> builder.populateRow(10, "U0000010", "F", "L", value);
            case "errMsg" -> builder.errMsg(value);
            case "cdemoCu00UsrIdFirst" -> builder.cdemoCu00UsrIdFirst(value);
            case "cdemoCu00UsrIdLast" -> builder.cdemoCu00UsrIdLast(value);
            case "cdemoCu00NextPageFlg" -> builder.cdemoCu00NextPageFlg(value);
            case "cdemoCu00UsrSelFlg" -> builder.cdemoCu00UsrSelFlg(value);
            case "cdemoCu00UsrSelected" -> builder.cdemoCu00UsrSelected(value);
            case "nextProgram" -> builder.nextProgram(value);
            case "nextMapset" -> builder.nextMapset(value);
            case "nextMap" -> builder.nextMap(value);
            default -> throw new IllegalArgumentException("unmapped component " + component);
        }
        return builder.build();
    }

    /** The COBOL name a diagnostic is expected to quote for a given component. */
    private static String cobolOrContextNameOf(String component) {
        return switch (component) {
            case "cdemoCu00UsrIdFirst" -> UserListResponse.CU00_USRID_FIRST_FIELD;
            case "cdemoCu00UsrIdLast" -> UserListResponse.CU00_USRID_LAST_FIELD;
            case "cdemoCu00NextPageFlg" -> UserListResponse.CU00_NEXT_PAGE_FLG_FIELD;
            case "cdemoCu00UsrSelFlg" -> UserListResponse.CU00_USR_SEL_FLG_FIELD;
            case "cdemoCu00UsrSelected" -> UserListResponse.CU00_USR_SELECTED_FIELD;
            case "nextProgram" -> NavigationContext.TO_PROGRAM_FIELD;
            case "nextMapset" -> NavigationContext.LAST_MAPSET_FIELD;
            case "nextMap" -> NavigationContext.LAST_MAP_FIELD;
            default -> cobolNameOf(component);
        };
    }

    @Nested
    @DisplayName("The ten rows - blanked, never omitted, and row 1 is element 0")
    class TenRows {

        @Test
        @DisplayName("ten rows is behaviour, not configuration")
        void tenRowsIsBehaviour() {
            assertThat(UserListResponse.ROW_COUNT).isEqualTo(10);
            assertThat(UserListResponse.ROW_FIELD_COUNT).isEqualTo(5);
            assertThat(componentNames()).doesNotContain("pageSize", "rowCount", "limit");
        }

        @Test
        @DisplayName("rows() always returns exactly ten rows, numbered 1 to 10")
        void rowsAlwaysReturnsTen() {
            assertThat(UserListResponse.blank().rows()).hasSize(UserListResponse.ROW_COUNT);
            assertThat(fullPage().rows()).hasSize(UserListResponse.ROW_COUNT)
                    .extracting(Row::rowNumber)
                    .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        }

        @Test
        @DisplayName("element 0 is row 1 and element 9 is row 10 - COBOL counts from one")
        void oneBasedToZeroBased() {
            UserListResponse page = fullPage();
            List<Row> rows = page.rows();
            assertThat(rows.get(0).rowNumber()).isEqualTo(1);
            assertThat(rows.get(0).userId()).isEqualTo("USER0001").isEqualTo(page.usrId01());
            assertThat(rows.get(9).rowNumber()).isEqualTo(10);
            assertThat(rows.get(9).userId()).isEqualTo("USER0010").isEqualTo(page.usrId10());
        }

        @ParameterizedTest(name = "row({0}) returns that row's five cells")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
        @DisplayName("every row number from 1 to 10 resolves to its own cells")
        void everyRowResolves(int rowNumber) {
            Row row = fullPage().row(rowNumber);
            assertThat(row.rowNumber()).isEqualTo(rowNumber);
            assertThat(row.userId()).isEqualTo(String.format("USER%04d", rowNumber));
            assertThat(row.firstName()).isEqualTo(String.format("First%02d", rowNumber));
            assertThat(row.lastName()).isEqualTo(String.format("Last%02d", rowNumber));
            assertThat(row.userType()).isEqualTo(rowNumber % 2 == 0 ? "A" : "U");
            assertThat(row.selection()).isEqualTo(" ");
        }

        @ParameterizedTest(name = "row({0}) is rejected")
        @ValueSource(ints = {-1, 0, 11, 99})
        @DisplayName("a row number outside 1 to 10 is rejected, not clamped")
        void rowNumbersOutsideTheScreenAreRejected(int rowNumber) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> fullPage().row(rowNumber))
                    .withMessageContaining("Row " + rowNumber + " does not exist")
                    .withMessageContaining(UserListResponse.MAP_NAME);
        }

        @ParameterizedTest(name = "the builder rejects row {0}")
        @ValueSource(ints = {-1, 0, 11, 99})
        @DisplayName("the builder rejects a row number outside 1 to 10 on every row method")
        void builderRejectsRowNumbersOutsideTheScreen(int rowNumber) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> UserListResponse.builder()
                            .populateRow(rowNumber, "U", "F", "L", "A"))
                    .withMessageContaining("Row " + rowNumber + " does not exist");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> UserListResponse.builder().blankRow(rowNumber));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> UserListResponse.builder().selection(rowNumber, "U"));
        }

        @Test
        @DisplayName("populateRow writes the four data cells and leaves the selection cell alone")
        void populateRowLeavesTheSelectionCellAlone() {
            UserListResponse page = UserListResponse.builder()
                    .selection(3, "U")
                    .populateRow(3, "USER0003", "Ada", "Lovelace", "A")
                    .build();
            assertThat(page.usrId03()).isEqualTo("USER0003");
            assertThat(page.fname03()).isEqualTo("Ada");
            assertThat(page.lname03()).isEqualTo("Lovelace");
            assertThat(page.utype03()).isEqualTo("A");
            assertThat(page.sel0003()).as("SEL0003 is echoed input, never written by the program")
                    .isEqualTo("U");
        }

        @Test
        @DisplayName("blankRow blanks the four data cells and leaves the selection cell alone")
        void blankRowMirrorsInitializeUserData() {
            UserListResponse page = UserListResponse.builder()
                    .selection(4, "D")
                    .populateRow(4, "USER0004", "Grace", "Hopper", "A")
                    .blankRow(4)
                    .build();
            assertThat(page.usrId04()).isEqualTo(" ".repeat(UserListResponse.USRID_LENGTH));
            assertThat(page.fname04()).isEqualTo(" ".repeat(UserListResponse.FNAME_LENGTH));
            assertThat(page.lname04()).isEqualTo(" ".repeat(UserListResponse.LNAME_LENGTH));
            assertThat(page.utype04()).isEqualTo(" ".repeat(UserListResponse.UTYPE_LENGTH));
            assertThat(page.sel0004()).isEqualTo("D");
            assertThat(page.row(4).blankRow()).isTrue();
        }

        @Test
        @DisplayName("a short final page carries ten rows with the tail blank, never null")
        void shortFinalPageStillCarriesTenRows() {
            UserListResponse.Builder builder = UserListResponse.builder()
                    .cdemoCu00PageNum(6)
                    .nextPageNo();
            for (int row = 1; row <= UserListResponse.ROW_COUNT; row++) {
                builder.blankRow(row);
            }
            builder.populateRow(1, "USER0051", "Last", "Page", "U");
            builder.populateRow(2, "USER0052", "Also", "Here", "A");
            UserListResponse page = builder.build();

            assertThat(page.rows()).hasSize(UserListResponse.ROW_COUNT);
            assertThat(page.row(1).blankRow()).isFalse();
            assertThat(page.row(2).blankRow()).isFalse();
            for (int row = 3; row <= UserListResponse.ROW_COUNT; row++) {
                Row tail = page.row(row);
                assertThat(tail.userId()).isNotNull().isBlank()
                        .hasSize(UserListResponse.USRID_LENGTH);
                assertThat(tail.firstName()).isNotNull().isBlank()
                        .hasSize(UserListResponse.FNAME_LENGTH);
                assertThat(tail.lastName()).isNotNull().isBlank()
                        .hasSize(UserListResponse.LNAME_LENGTH);
                assertThat(tail.userType()).isNotNull().isBlank()
                        .hasSize(UserListResponse.UTYPE_LENGTH);
                assertThat(tail.blankRow()).isTrue();
            }
            assertThat(page.nextPageNo()).isTrue();
        }

        @ParameterizedTest(name = "blankRow() is {5} for id={0} first={1} last={2} type={3}")
        @MethodSource(
                "com.vsergeychik.carddemo.user.dto.UserListResponseTest#blankRowCases")
        @DisplayName("blankRow() is true only when all four data cells are blank")
        void blankRowExaminesEveryDataCell(String userId,
                                           String firstName,
                                           String lastName,
                                           String userType,
                                           String selection,
                                           boolean expected) {
            Row row = new Row(1, selection, userId, firstName, lastName, userType);
            assertThat(row.blankRow()).isEqualTo(expected);
        }
    }

    /** The five combinations needed to drive both sides of each condition in {@code blankRow()}. */
    static Stream<Arguments> blankRowCases() {
        return Stream.of(
                Arguments.of("        ", "                    ", "                    ", " ",
                        "U", true),
                Arguments.of("USER0001", "                    ", "                    ", " ",
                        " ", false),
                Arguments.of("        ", "Ada                 ", "                    ", " ",
                        " ", false),
                Arguments.of("        ", "                    ", "Lovelace            ", " ",
                        " ", false),
                Arguments.of("        ", "                    ", "                    ", "A",
                        " ", false));
    }

    @Nested
    @DisplayName("The CU00 paging context - 34 bytes on top of a 160-byte commarea")
    class Cu00Context {

        @Test
        @DisplayName("all six items of CDEMO-CU00-INFO are carried")
        void allSixItemsAreCarried() {
            assertThat(componentNames()).contains("cdemoCu00UsrIdFirst", "cdemoCu00UsrIdLast",
                    "cdemoCu00PageNum", "cdemoCu00NextPageFlg", "cdemoCu00UsrSelFlg",
                    "cdemoCu00UsrSelected");
        }

        @Test
        @DisplayName("the six items sum to 34 bytes, so the CU00 commarea is 194")
        void theExtensionIsThirtyFourBytes() {
            int declared = UserListResponse.CU00_USRID_FIRST_LENGTH
                    + UserListResponse.CU00_USRID_LAST_LENGTH
                    + UserListResponse.CU00_PAGE_NUM_DIGITS
                    + UserListResponse.CU00_NEXT_PAGE_FLG_LENGTH
                    + UserListResponse.CU00_USR_SEL_FLG_LENGTH
                    + UserListResponse.CU00_USR_SELECTED_LENGTH;
            assertThat(declared).isEqualTo(UserListResponse.CU00_INFO_LENGTH).isEqualTo(34);
            assertThat(UserListResponse.CU00_COMMAREA_LENGTH).isEqualTo(194)
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH
                            + UserListResponse.CU00_INFO_LENGTH);
        }

        @Test
        @DisplayName("the page number is an int, because PIC 9(08) is unsigned and unscaled")
        void thePageNumberIsAnIntegralType() throws ReflectiveOperationException {
            RecordComponent pageNumber = Stream.of(UserListResponse.class.getRecordComponents())
                    .filter(component -> component.getName().equals("cdemoCu00PageNum"))
                    .findFirst()
                    .orElseThrow();
            assertThat(pageNumber.getType()).isEqualTo(int.class);
            assertThat(UserListResponse.CU00_PAGE_NUM_DIGITS).isEqualTo(8);
            assertThat(UserListResponse.builder().cdemoCu00PageNum(99_999_999).build()
                    .cdemoCu00PageNum()).isEqualTo(99_999_999);
        }

        @Test
        @DisplayName("a negative page number is rejected: the picture has no sign position")
        void negativePageNumbersAreRejected() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> UserListResponse.builder().cdemoCu00PageNum(-1).build())
                    .withMessageContaining(UserListResponse.CU00_PAGE_NUM_FIELD)
                    .withMessageContaining("unsigned");
        }

        @Test
        @DisplayName("a nine-digit page number is rejected rather than silently shortened")
        void overLongPageNumbersAreRejected() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> UserListResponse.builder()
                            .cdemoCu00PageNum(100_000_000).build())
                    .withMessageContaining(UserListResponse.CU00_PAGE_NUM_FIELD)
                    .withMessageContaining("9 digit(s)");
        }

        @Test
        @DisplayName("PAGENUM the screen field and CDEMO-CU00-PAGE-NUM the number are distinct")
        void theTwoPageNumbersAreNotConflated() {
            UserListResponse page = UserListResponse.builder()
                    .pageNum("00000007")
                    .cdemoCu00PageNum(7)
                    .build();
            assertThat(page.pageNum()).isEqualTo("00000007").hasSize(8);
            assertThat(page.cdemoCu00PageNum()).isEqualTo(7);

            UserListResponse onlyText = UserListResponse.builder().pageNum("00000009").build();
            assertThat(onlyText.cdemoCu00PageNum())
                    .as("setting the screen text must not set the numeric field")
                    .isZero();
        }

        @Test
        @DisplayName("the 88-level conditions read through the flag rather than duplicating it")
        void theConditionsReadThroughTheFlag() {
            assertThat(UserListResponse.NEXT_PAGE_YES).isEqualTo("Y");
            assertThat(UserListResponse.NEXT_PAGE_NO).isEqualTo("N");

            UserListResponse yes = UserListResponse.builder().nextPageYes().build();
            assertThat(yes.cdemoCu00NextPageFlg()).isEqualTo("Y");
            assertThat(yes.nextPageYes()).isTrue();
            assertThat(yes.nextPageNo()).isFalse();

            UserListResponse no = UserListResponse.builder().nextPageNo().build();
            assertThat(no.cdemoCu00NextPageFlg()).isEqualTo("N");
            assertThat(no.nextPageYes()).isFalse();
            assertThat(no.nextPageNo()).isTrue();
        }

        @Test
        @DisplayName("a space is neither condition, so they are not each other's negation")
        void aSpaceSatisfiesNeitherCondition() {
            UserListResponse neither = UserListResponse.builder()
                    .cdemoCu00NextPageFlg(" ")
                    .build();
            assertThat(neither.nextPageYes()).isFalse();
            assertThat(neither.nextPageNo()).isFalse();
        }

        @Test
        @DisplayName("the browse cursors survive a round trip unchanged")
        void theBrowseCursorsAreCarried() {
            UserListResponse page = fullPage();
            assertThat(page.cdemoCu00UsrIdFirst()).isEqualTo("USER0001")
                    .isEqualTo(page.row(1).userId());
            assertThat(page.cdemoCu00UsrIdLast()).isEqualTo("USER0010")
                    .isEqualTo(page.row(UserListResponse.ROW_COUNT).userId());
        }
    }

    @Nested
    @DisplayName("Statelessness and the navigation contract")
    class Navigation {

        @Test
        @DisplayName("nextProgram is 8 while nextMapset and nextMap are 7, not 8")
        void theNavigationWidthsComeFromTheCommarea() {
            assertThat(UserListResponse.NEXT_PROGRAM_LENGTH).isEqualTo(8)
                    .isEqualTo(NavigationContext.TO_PROGRAM_LENGTH);
            assertThat(UserListResponse.NEXT_MAPSET_LENGTH).isEqualTo(7)
                    .isEqualTo(NavigationContext.LAST_MAPSET_LENGTH);
            assertThat(UserListResponse.NEXT_MAP_LENGTH).isEqualTo(7)
                    .isEqualTo(NavigationContext.LAST_MAP_LENGTH);
            assertThat(UserListResponse.MAPSET_NAME).isEqualTo("COUSR00").hasSize(7);
            assertThat(UserListResponse.MAP_NAME).isEqualTo("COUSR0A").hasSize(7);
        }

        @Test
        @DisplayName("the screen identifies itself as CU00 / COUSR00C")
        void theScreenIdentity() {
            assertThat(UserListResponse.TRANSACTION_ID).isEqualTo("CU00");
            assertThat(UserListResponse.PROGRAM_NAME).isEqualTo("COUSR00C");
        }

        @Test
        @DisplayName("the documented XCTL targets are COUSR02C, COUSR03C and COSGN00C")
        void theDocumentedTargets() {
            assertThat(UserListResponse.NEXT_PROGRAM_USER_UPDATE).isEqualTo("COUSR02C").hasSize(8);
            assertThat(UserListResponse.NEXT_PROGRAM_USER_DELETE).isEqualTo("COUSR03C").hasSize(8);
            assertThat(UserListResponse.NEXT_PROGRAM_SIGNON).isEqualTo("COSGN00C").hasSize(8);
            assertThat(UserListResponse.INVALID_SELECTION_MESSAGE)
                    .isEqualTo("Invalid selection. Valid values are U and D");
        }

        @ParameterizedTest(name = "selection ''{0}'' is carried alongside {1}")
        @CsvSource({
            "U,COUSR02C", "u,COUSR02C", "D,COUSR03C", "d,COUSR03C",
        })
        @DisplayName("the selection is case-insensitive: U and u update, D and d delete")
        void theSelectionIsCaseInsensitive(String selection, String expectedTarget) {
            String target = "U".equalsIgnoreCase(selection)
                    ? UserListResponse.NEXT_PROGRAM_USER_UPDATE
                    : UserListResponse.NEXT_PROGRAM_USER_DELETE;
            assertThat(target).isEqualTo(expectedTarget);

            UserListResponse page = UserListResponse.builder()
                    .cdemoCu00UsrSelFlg(selection)
                    .cdemoCu00UsrSelected("USER0005")
                    .nextProgram(target)
                    .nextMapset(UserListResponse.MAPSET_NAME)
                    .nextMap(UserListResponse.MAP_NAME)
                    .build();
            assertThat(page.cdemoCu00UsrSelFlg()).isEqualTo(selection);
            assertThat(page.cdemoCu00UsrSelected()).isEqualTo("USER0005");
            assertThat(page.nextProgram()).isEqualTo(expectedTarget);
        }

        @Test
        @DisplayName("the commarea is referenced as it is and is not widened")
        void theCommareaIsReferencedNotWidened() {
            assertThat(NavigationContext.COMMAREA_LENGTH).isEqualTo(160);
            assertThat(NavigationContext.class.getRecordComponents())
                    .as("a field added to NavigationContext would change 17 screens")
                    .hasSize(16);

            NavigationContext context = NavigationContext.empty()
                    .withFromTranid(UserListResponse.TRANSACTION_ID)
                    .withFromProgram(UserListResponse.PROGRAM_NAME)
                    .withToProgram(UserListResponse.NEXT_PROGRAM_USER_UPDATE);
            UserListResponse page = UserListResponse.builder().navigationContext(context).build();
            assertThat(page.navigationContext()).isSameAs(context);
            assertThat(page.navigationContext().toProgram()).isEqualTo("COUSR02C");
            assertThat(page.navigationContext().isEnter()).isTrue();
        }

        @Test
        @DisplayName("no member and no annotation introduces server-side state")
        void nothingIntroducesServerSideState() {
            String annotations = Stream.of(UserListResponse.class.getAnnotations())
                    .map(Object::toString)
                    .reduce("", String::concat);
            assertThat(annotations).isEmpty();
            assertThat(componentNames())
                    .noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT).contains("session"));
        }
    }

    @Nested
    @DisplayName("Serialisation - the payload is the 69 components and nothing else")
    class Serialisation {

        private final ObjectMapper mapper = new ObjectMapper();

        /**
         * The payload as a keyed map, obtained through a typed reference so the conversion carries
         * its element types instead of an unchecked cast.
         */
        private Map<String, Object> payloadOf(UserListResponse response) {
            return mapper.convertValue(response, new TypeReference<Map<String, Object>>() { });
        }

        @Test
        @DisplayName("the JSON keys are exactly the 69 component names")
        void theJsonKeysAreExactlyTheComponents() throws IOException {
            Map<String, Object> json = payloadOf(fullPage());
            assertThat(json.keySet()).containsExactlyInAnyOrderElementsOf(componentNames());
            assertThat(json).hasSize(UserListResponse.COMPONENT_COUNT);
        }

        @Test
        @DisplayName("no control-byte key reaches the payload")
        void noControlByteKeyReachesThePayload() throws IOException {
            Map<String, Object> json = payloadOf(fullPage());
            for (String field : UserListResponse.FIELD_NAMES) {
                for (String suffix : List.of("L", "F", "A", "C", "P", "H", "V")) {
                    assertThat(json).doesNotContainKey(field + suffix);
                    assertThat(json).doesNotContainKey(
                            field.toLowerCase(java.util.Locale.ROOT) + suffix);
                }
            }
            assertThat(json).doesNotContainKeys("rows", "fieldNames", "nextPageYes", "nextPageNo",
                    "pageSize");
        }

        @Test
        @DisplayName("all 50 row values appear in the payload under their own names")
        void allFiftyRowValuesAppear() throws IOException {
            Map<String, Object> json = payloadOf(fullPage());
            for (int row = 1; row <= UserListResponse.ROW_COUNT; row++) {
                assertThat(json).containsKey(String.format("sel%04d", row));
                assertThat(json).containsEntry(String.format("usrId%02d", row),
                        String.format("USER%04d", row));
                assertThat(json).containsEntry(String.format("fname%02d", row),
                        String.format("First%02d", row));
                assertThat(json).containsEntry(String.format("lname%02d", row),
                        String.format("Last%02d", row));
                assertThat(json).containsKey(String.format("utype%02d", row));
            }
        }

        @Test
        @DisplayName("space padding survives a round trip: nothing is trimmed or nulled")
        void spacePaddingSurvivesARoundTrip() throws IOException {
            UserListResponse blank = UserListResponse.blank();
            String payload = mapper.writeValueAsString(blank);
            UserListResponse restored = mapper.readValue(payload, UserListResponse.class);
            assertThat(restored).isEqualTo(blank);
            assertThat(restored.title01()).hasSize(UserListResponse.TITLE01_LENGTH).isBlank();
            assertThat(restored.errMsg()).hasSize(UserListResponse.ERRMSG_LENGTH).isBlank();
            assertThat(restored.rows()).hasSize(UserListResponse.ROW_COUNT);
        }

        @Test
        @DisplayName("a short final page serialises ten rows, the tail space filled")
        void aShortPageSerialisesTenRows() throws IOException {
            UserListResponse.Builder builder = UserListResponse.builder();
            for (int row = 1; row <= UserListResponse.ROW_COUNT; row++) {
                builder.blankRow(row);
            }
            builder.populateRow(1, "USER0001", "Only", "One", "U");
            String payload = mapper.writeValueAsString(builder.build());
            for (int row = 2; row <= UserListResponse.ROW_COUNT; row++) {
                assertThat(payload)
                        .as("row %d must be present and space filled", row)
                        .contains("\"usrId%02d\":\"        \"".formatted(row))
                        .doesNotContain("\"usrId%02d\":null".formatted(row));
            }
        }
    }

    @Nested
    @DisplayName("Value semantics - immutable, with no static mutable state")
    class ValueSemantics {

        @Test
        @DisplayName("toBuilder().build() reproduces the original exactly")
        void toBuilderRoundTrips() {
            UserListResponse page = fullPage();
            assertThat(page.toBuilder().build()).isEqualTo(page).hasSameHashCodeAs(page);
        }

        @Test
        @DisplayName("an edit produces a new value and leaves the original untouched")
        void anEditDoesNotMutateTheOriginal() {
            UserListResponse page = fullPage();
            UserListResponse edited = page.toBuilder()
                    .errMsg(UserListResponse.INVALID_SELECTION_MESSAGE)
                    .blankRow(10)
                    .build();
            assertThat(page.errMsg()).isEqualTo(" ".repeat(UserListResponse.ERRMSG_LENGTH));
            assertThat(page.usrId10()).isEqualTo("USER0010");
            assertThat(edited.errMsg()).isEqualTo(UserListResponse.INVALID_SELECTION_MESSAGE);
            assertThat(edited.usrId10()).isBlank();
            assertThat(edited).isNotEqualTo(page);
        }

        @Test
        @DisplayName("two builders never share a row array")
        void buildersShareNothing() {
            UserListResponse.Builder first = UserListResponse.builder()
                    .populateRow(1, "USER0001", "A", "B", "U");
            UserListResponse.Builder second = UserListResponse.builder()
                    .populateRow(1, "USER0002", "C", "D", "A");
            assertThat(first.build().usrId01()).isEqualTo("USER0001");
            assertThat(second.build().usrId01()).isEqualTo("USER0002");
        }

        @Test
        @DisplayName("every static field is final, in the record and in its nested types")
        void noStaticMutableState() {
            for (Class<?> type : List.of(UserListResponse.class, UserListResponse.Builder.class,
                    Row.class)) {
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) {
                        assertThat(Modifier.isFinal(field.getModifiers()))
                                .as("%s.%s is static and must be final",
                                        type.getSimpleName(), field.getName())
                                .isTrue();
                    }
                }
            }
        }

        @Test
        @DisplayName("rows() returns an immutable list")
        void rowsAreImmutable() {
            List<Row> rows = fullPage().rows();
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> rows.add(new Row(11, " ", "        ",
                            "                    ", "                    ", " ")));
        }

        @Test
        @DisplayName("toString names the fields, which is what a parity diff needs")
        void toStringNamesTheFields() {
            assertThat(fullPage().toString())
                    .as("the row form renders through Row, which masks the 20 personal names")
                    .contains("Row[1, ")
                    .contains("userId='USER0001'")
                    .contains("errMsg=")
                    .contains("cdemoCu00PageNum=1");
        }
    }
}

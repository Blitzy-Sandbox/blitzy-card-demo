package com.vsergeychik.carddemo.admin.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.NavigationContext;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link MainMenuResponse}, the outbound payload of {@code GET /api/menu} - CICS
 * transaction {@code CM00}, program {@code COMEN01C}, mapset {@code COMEN01}, map {@code COMEN1A}.
 *
 * <p>The assertions are deliberately derived from the read-only COBOL oracle wherever they can be:
 * {@link FieldList#membersMatchTheCopybook()} parses the {@code xxxO} items straight out of
 * {@code app/cpy-bms/COMEN01.CPY}, {@link AdminTwin#widthsMatchTheAdminMap()} compares them against
 * {@code COADM01.CPY}, and {@link StatelessNavigation#targetsMatchTheMenuTable()} checks the ten
 * transfer targets against {@code app/cpy/COMEN02Y.cpy}. A hand-copied expectation can drift from the
 * source; one read from the source cannot.
 *
 * <p>Two behaviours get disproportionate attention because they are the easiest to lose. All twelve
 * option slots are exercised even though the program only ever fills ten - the mapset declares twelve
 * and so must this payload. And every path is checked for the absence of padding, trimming and
 * truncation, because the {@code PIC X(80)} to {@code PIC X(78)} narrowing belongs to the controller
 * and a helpful DTO that "fixed" a width here would silently break byte-for-byte parity.
 */
class MainMenuResponseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The twenty xxxO payload items, in map order, exactly as the copybook declares them. */
    private static final List<String> EXPECTED_MEMBERS = List.of("trnName",
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
            "errMsg");

    /** The ten XCTL targets of app/cpy/COMEN02Y.cpy, plus the sign-on fallback. */
    private static final List<String> XCTL_TARGETS = List.of("COACTVWC",
            "COACTUPC",
            "COCRDLIC",
            "COCRDSLC",
            "COCRDUPC",
            "COTRN00C",
            "COTRN01C",
            "COTRN02C",
            "CORPT00C",
            "COBIL00C",
            "COSGN00C");

    // ---------------------------------------------------------------------------------------------
    // Locating the read-only COBOL sources, so assertions are derived from the oracle itself.
    // ---------------------------------------------------------------------------------------------

    private static Path repoRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("app/cpy-bms/COMEN01.CPY"))) {
            dir = dir.getParent();
        }
        assertThat(dir).as("repository root containing app/cpy-bms/COMEN01.CPY").isNotNull();
        return dir;
    }

    /** Parses the xxxO items of the AO group of a symbolic map into name -> width, in order. */
    private static Map<String, Integer> outputItems(String symbolicMap) throws IOException {
        List<String> lines = Files.readAllLines(repoRoot().resolve("app/cpy-bms/" + symbolicMap),
                StandardCharsets.ISO_8859_1);
        int aoStart = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("REDEFINES") && lines.get(i).trim().startsWith("01")) {
                aoStart = i;
            }
        }
        assertThat(aoStart).as("AO group opener in " + symbolicMap).isGreaterThan(0);

        Pattern item = Pattern.compile("^\\s+02\\s+([A-Z0-9]+O)\\s+PIC\\s+X\\((\\d+)\\)");
        Map<String, Integer> items = new LinkedHashMap<>();
        for (String line : lines.subList(aoStart, lines.size())) {
            Matcher matcher = item.matcher(line);
            if (matcher.find()) {
                items.put(matcher.group(1), Integer.parseInt(matcher.group(2)));
            }
        }
        return items;
    }

    private static MainMenuResponse populated() {
        MainMenuResponse.Builder builder = MainMenuResponse.builder()
                .trnName(MainMenuResponse.TRANSACTION_ID)
                .title01("      AWS Mainframe Modernization       ")
                .curDate("08/08/26")
                .pgmName(MainMenuResponse.PROGRAM_NAME)
                .title02("              CardDemo                  ")
                .curTime("09:33:18")
                .option("01")
                .errMsg("Please enter a valid option number...")
                .nextProgram("COACTVWC");
        for (int slot = 1; slot <= MainMenuResponse.ACTIVE_OPTION_LINE_COUNT; slot++) {
            builder.optionLine(slot, String.format("%02d. Option %d", slot, slot));
        }
        return builder.build();
    }

    // =============================================================================================
    /**
     * The two record components that are deliberately not JSON properties: the {@code ERRMSGC}
     * attribute byte and the clear-the-screen signal, both {@code @JsonIgnore}d.
     */
    private static final List<String> UNPUBLISHED_MEMBERS =
            List.of("errMsgColor", "resetAllOutputFields");

    @Nested
    @DisplayName("Accuracy: the field list is the symbolic map's xxxO list")
    class FieldList {

        @Test
        @DisplayName("exactly 20 map-derived members, in map order")
        void twentyMembersInMapOrder() {
            List<String> components = Stream.of(MainMenuResponse.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(components).hasSize(26);
            assertThat(components.subList(0, 20)).containsExactlyElementsOf(EXPECTED_MEMBERS);
            assertThat(MainMenuResponse.SYMBOLIC_MAP_FIELD_COUNT).isEqualTo(20);
        }

        @Test
        @DisplayName("member names and widths match app/cpy-bms/COMEN01.CPY itself")
        void membersMatchTheCopybook() throws IOException {
            Map<String, Integer> items = outputItems("COMEN01.CPY");

            assertThat(items).hasSize(MainMenuResponse.SYMBOLIC_MAP_FIELD_COUNT);
            assertThat(items.keySet()).containsExactly("TRNNAMEO",
                    "TITLE01O", "CURDATEO", "PGMNAMEO", "TITLE02O", "CURTIMEO",
                    "OPTN001O", "OPTN002O", "OPTN003O", "OPTN004O", "OPTN005O", "OPTN006O",
                    "OPTN007O", "OPTN008O", "OPTN009O", "OPTN010O", "OPTN011O", "OPTN012O",
                    "OPTIONO", "ERRMSGO");

            assertThat(items.get("TRNNAMEO")).isEqualTo(MainMenuResponse.TRN_NAME_LENGTH);
            assertThat(items.get("TITLE01O")).isEqualTo(MainMenuResponse.TITLE_LENGTH);
            assertThat(items.get("TITLE02O")).isEqualTo(MainMenuResponse.TITLE_LENGTH);
            assertThat(items.get("CURDATEO")).isEqualTo(MainMenuResponse.CUR_DATE_LENGTH);
            assertThat(items.get("PGMNAMEO")).isEqualTo(MainMenuResponse.PGM_NAME_LENGTH);
            assertThat(items.get("CURTIMEO")).isEqualTo(MainMenuResponse.CUR_TIME_LENGTH);
            assertThat(items.get("OPTIONO")).isEqualTo(MainMenuResponse.OPTION_LENGTH);
            assertThat(items.get("ERRMSGO")).isEqualTo(MainMenuResponse.ERR_MSG_LENGTH);
            for (int slot = 1; slot <= MainMenuResponse.OPTION_LINE_COUNT; slot++) {
                assertThat(items.get(String.format("OPTN%03dO", slot)))
                        .isEqualTo(MainMenuResponse.OPTION_LINE_LENGTH);
            }
        }

        @Test
        @DisplayName("declared widths are 4/40/8/8/40/8/12x40/2/78 summing to 668, image 820")
        void arithmeticReconciles() {
            assertThat(MainMenuResponse.TRN_NAME_LENGTH).isEqualTo(4);
            assertThat(MainMenuResponse.TITLE_LENGTH).isEqualTo(40);
            assertThat(MainMenuResponse.CUR_DATE_LENGTH).isEqualTo(8);
            assertThat(MainMenuResponse.PGM_NAME_LENGTH).isEqualTo(8);
            assertThat(MainMenuResponse.CUR_TIME_LENGTH).isEqualTo(8);
            assertThat(MainMenuResponse.OPTION_LINE_LENGTH).isEqualTo(40);
            assertThat(MainMenuResponse.OPTION_LINE_COUNT).isEqualTo(12);
            assertThat(MainMenuResponse.OPTION_LENGTH).isEqualTo(2);
            assertThat(MainMenuResponse.ERR_MSG_LENGTH).isEqualTo(78);

            assertThat(MainMenuResponse.PAYLOAD_BYTES).isEqualTo(668);
            assertThat(MainMenuResponse.TIOAPFX_FILLER_LENGTH).isEqualTo(12);
            assertThat(MainMenuResponse.ATTRIBUTE_PREFIX_LENGTH).isEqualTo(7);
            assertThat(MainMenuResponse.SYMBOLIC_MAP_LENGTH)
                    .isEqualTo(12 + 20 * 7 + 668)
                    .isEqualTo(820);
            assertThat(MainMenuResponse.MAPSET_FIELD_DEFINITION_COUNT).isEqualTo(28);
        }

        @Test
        @DisplayName("@Size(max) on every String member equals its copybook width")
        void sizeAnnotationsCarryNamedWidths() throws Exception {
            Map<String, Integer> expected = new LinkedHashMap<>();
            expected.put("trnName", MainMenuResponse.TRN_NAME_LENGTH);
            expected.put("title01", MainMenuResponse.TITLE_LENGTH);
            expected.put("curDate", MainMenuResponse.CUR_DATE_LENGTH);
            expected.put("pgmName", MainMenuResponse.PGM_NAME_LENGTH);
            expected.put("title02", MainMenuResponse.TITLE_LENGTH);
            expected.put("curTime", MainMenuResponse.CUR_TIME_LENGTH);
            expected.put("option", MainMenuResponse.OPTION_LENGTH);
            expected.put("errMsg", MainMenuResponse.ERR_MSG_LENGTH);
            expected.put("nextProgram", MainMenuResponse.NEXT_PROGRAM_LENGTH);
            expected.put("nextMapset", MainMenuResponse.NEXT_MAPSET_LENGTH);
            expected.put("nextMap", MainMenuResponse.NEXT_MAP_LENGTH);
            for (int slot = 1; slot <= MainMenuResponse.OPTION_LINE_COUNT; slot++) {
                expected.put(String.format("optn%03d", slot), MainMenuResponse.OPTION_LINE_LENGTH);
            }

            // jakarta.validation.constraints.Size declares @Target({METHOD, FIELD,
            // ANNOTATION_TYPE, CONSTRUCTOR, PARAMETER, TYPE_USE}) - notably WITHOUT
            // RECORD_COMPONENT. An annotation written on a record component is therefore propagated
            // to every applicable declaration (the private field, the accessor and the canonical
            // constructor parameter) but is NOT retained on the RecordComponent itself, so
            // RecordComponent.getAnnotation would answer null here. Bean Validation reads fields and
            // getters, so the constraint is live; the assertion just has to look where it landed.
            int annotated = 0;
            for (RecordComponent component : MainMenuResponse.class.getRecordComponents()) {
                String name = component.getName();
                Size onField = MainMenuResponse.class.getDeclaredField(name).getAnnotation(Size.class);
                Size onAccessor = MainMenuResponse.class.getMethod(name).getAnnotation(Size.class);

                if (expected.containsKey(name)) {
                    assertThat(onField).as("@Size on field " + name).isNotNull();
                    assertThat(onField.max()).as("@Size max on field " + name)
                            .isEqualTo(expected.get(name));
                    assertThat(onAccessor).as("@Size on accessor " + name).isNotNull();
                    assertThat(onAccessor.max()).as("@Size max on accessor " + name)
                            .isEqualTo(expected.get(name));
                    annotated++;
                } else {
                    assertThat(onField).as("no @Size on non-text " + name).isNull();
                    assertThat(onAccessor).as("no @Size on non-text accessor " + name).isNull();
                }
            }
            // The twenty payload members plus nextProgram, nextMapset and nextMap.
            assertThat(annotated).isEqualTo(23);
            assertThat(expected).hasSize(23);
        }

        @Test
        @DisplayName("@Size also reaches the canonical constructor parameters")
        void sizeReachesConstructorParameters() {
            java.lang.reflect.Parameter[] parameters =
                    MainMenuResponse.class.getDeclaredConstructors()[0].getParameters();

            assertThat(parameters).hasSize(26);
            assertThat(parameters[0].getAnnotation(Size.class)).isNotNull();
            assertThat(parameters[0].getAnnotation(Size.class).max())
                    .isEqualTo(MainMenuResponse.TRN_NAME_LENGTH);
            // Index 20 is navigationContext, which is not a CharSequence and carries no @Size.
            assertThat(parameters[20].getAnnotation(Size.class)).isNull();
        }

        @Test
        @DisplayName("navigation widths come from the communication area: 8, 7 and 7")
        void navigationWidthsTrackTheCommarea() {
            assertThat(MainMenuResponse.NEXT_PROGRAM_LENGTH)
                    .isEqualTo(NavigationContext.TO_PROGRAM_LENGTH).isEqualTo(8);
            assertThat(MainMenuResponse.NEXT_MAPSET_LENGTH)
                    .isEqualTo(NavigationContext.LAST_MAPSET_LENGTH).isEqualTo(7);
            assertThat(MainMenuResponse.NEXT_MAP_LENGTH)
                    .isEqualTo(NavigationContext.LAST_MAP_LENGTH).isEqualTo(7);
        }
    }

    // =============================================================================================
    @Nested
    @DisplayName("Preservation: all twelve option slots, though only ten are ever filled")
    class TwelveSlots {

        @ParameterizedTest(name = "slot {0} is addressable when unset")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12})
        void everySlotAddressableWhenUnset(int slot) {
            MainMenuResponse response = MainMenuResponse.builder().build();

            assertThat(response.optionLine(slot)).isNull();
            assertThat(response.optionLines()).hasSize(12);
        }

        @ParameterizedTest(name = "slot {0} round-trips through the 1-based API")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12})
        void everySlotWritableAndReadable(int slot) {
            MainMenuResponse response = MainMenuResponse.builder()
                    .optionLine(slot, "slot-" + slot)
                    .build();

            assertThat(response.optionLine(slot)).isEqualTo("slot-" + slot);
            assertThat(response.optionLines().get(slot - 1)).isEqualTo("slot-" + slot);
        }

        @Test
        @DisplayName("optn011 and optn012 exist as real components and carry values")
        void unreachableSlotsStillExist() {
            List<String> names = Stream.of(MainMenuResponse.class.getRecordComponents())
                    .map(RecordComponent::getName).toList();
            assertThat(names).contains("optn011", "optn012");

            MainMenuResponse response = MainMenuResponse.builder()
                    .optionLine(11, "eleven")
                    .optionLine(12, "twelve")
                    .build();

            assertThat(response.optn011()).isEqualTo("eleven");
            assertThat(response.optn012()).isEqualTo("twelve");
        }

        @Test
        @DisplayName("table size 12 and active count 10 are separate facts")
        void tableSizeAndActiveCountAreDistinct() {
            assertThat(MainMenuResponse.OPTION_LINE_COUNT).isEqualTo(12);
            assertThat(MainMenuResponse.ACTIVE_OPTION_LINE_COUNT).isEqualTo(10);
            assertThat(MainMenuResponse.OPTION_LINE_COUNT)
                    .isGreaterThan(MainMenuResponse.ACTIVE_OPTION_LINE_COUNT);
        }

        @ParameterizedTest(name = "slot {0} populated by program = {1}")
        @MethodSource("slotPopulation")
        void populationMirrorsTheLoopBound(int slot, boolean populated) {
            assertThat(MainMenuResponse.builder().build().isPopulatedByProgram(slot))
                    .isEqualTo(populated);
        }

        static Stream<Arguments> slotPopulation() {
            List<Arguments> cases = new ArrayList<>();
            for (int slot = 1; slot <= 12; slot++) {
                cases.add(Arguments.of(slot, slot <= 10));
            }
            return cases.stream();
        }

        @ParameterizedTest(name = "slot {0} is rejected, not clamped")
        @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 13, 14, Integer.MAX_VALUE})
        void outOfRangeSlotsRejected(int slot) {
            MainMenuResponse response = MainMenuResponse.builder().build();

            assertThatThrownBy(() -> response.optionLine(slot))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("1..12");
            assertThatThrownBy(() -> response.isPopulatedByProgram(slot))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> MainMenuResponse.builder().optionLine(slot, "x"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("slot bounds are the COBOL's 1-based numbering")
        void slotBoundsAreOneBased() {
            assertThat(MainMenuResponse.FIRST_OPTION_LINE_SLOT).isEqualTo(1);
            assertThat(MainMenuResponse.LAST_OPTION_LINE_SLOT)
                    .isEqualTo(MainMenuResponse.OPTION_LINE_COUNT).isEqualTo(12);
        }
    }

    // =============================================================================================
    @Nested
    @DisplayName("Independence from the admin twin")
    class AdminTwin {

        @Test
        @DisplayName("the two symbolic maps declare identical xxxO names-modulo-prefix and widths")
        void widthsMatchTheAdminMap() throws IOException {
            List<Integer> main = new ArrayList<>(outputItems("COMEN01.CPY").values());
            List<Integer> admin = new ArrayList<>(outputItems("COADM01.CPY").values());

            assertThat(main).containsExactlyElementsOf(admin).hasSize(20);
            assertThat(outputItems("COMEN01.CPY").keySet())
                    .containsExactlyElementsOf(outputItems("COADM01.CPY").keySet());
        }

        @Test
        @DisplayName("yet this class shares no type with anything: it extends only Record")
        void staysAnIndependentType() {
            assertThat(MainMenuResponse.class.getSuperclass()).isEqualTo(Record.class);
            assertThat(MainMenuResponse.class.getInterfaces()).isEmpty();
            assertThat(MainMenuResponse.class.isRecord()).isTrue();
        }
    }

    // =============================================================================================
    @Nested
    @DisplayName("Navigation, colour and the echoed communication area")
    class StatelessNavigation {

        @Test
        @DisplayName("a freshly built response targets COMEN01 / COMEN1A")
        void navigationDefaults() {
            MainMenuResponse fresh = MainMenuResponse.builder().build();

            assertThat(fresh.nextMapset()).isEqualTo("COMEN01").isEqualTo(MainMenuResponse.MAPSET_NAME);
            assertThat(fresh.nextMap()).isEqualTo("COMEN1A").isEqualTo(MainMenuResponse.MAP_NAME);
            assertThat(MainMenuResponse.MAPSET_NAME).hasSize(MainMenuResponse.NEXT_MAPSET_LENGTH);
            assertThat(MainMenuResponse.MAP_NAME).hasSize(MainMenuResponse.NEXT_MAP_LENGTH);
            assertThat(MainMenuResponse.TRANSACTION_ID).isEqualTo("CM00");
            assertThat(MainMenuResponse.PROGRAM_NAME).isEqualTo("COMEN01C");
            assertThat(MainMenuResponse.SIGNON_PROGRAM).isEqualTo("COSGN00C");
        }

        @ParameterizedTest(name = "nextProgram carries {0}")
        @MethodSource("xctlTargets")
        void everyXctlTargetFits(String target) {
            assertThat(MainMenuResponse.builder().nextProgram(target).build().nextProgram())
                    .isEqualTo(target);
            assertThat(target.length()).isLessThanOrEqualTo(MainMenuResponse.NEXT_PROGRAM_LENGTH);
        }

        static Stream<String> xctlTargets() {
            return XCTL_TARGETS.stream();
        }

        @Test
        @DisplayName("the ten menu targets are exactly those of app/cpy/COMEN02Y.cpy")
        void targetsMatchTheMenuTable() throws IOException {
            String copybook = Files.readString(repoRoot().resolve("app/cpy/COMEN02Y.cpy"),
                    StandardCharsets.ISO_8859_1);
            for (String target : XCTL_TARGETS.subList(0, 10)) {
                assertThat(copybook).as(target + " in COMEN02Y").contains("'" + target + "'");
            }
        }

        @Test
        @DisplayName("message colour defaults to DFHRED and can be overridden to DFHGREEN")
        void colourDefaultAndOverride() {
            assertThat(MainMenuResponse.builder().build().errMsgColor())
                    .isEqualTo(BmsAttributes.DFHRED).isEqualTo((byte) 0xF2);
            assertThat(MainMenuResponse.initial().errMsgColor()).isEqualTo(BmsAttributes.DFHRED);

            MainMenuResponse green = MainMenuResponse.initial()
                    .withErrMsgColor(BmsAttributes.DFHGREEN);
            assertThat(green.errMsgColor()).isEqualTo(BmsAttributes.DFHGREEN).isEqualTo((byte) 0xF4);
            assertThat(BmsAttributes.DFHRED).isNotEqualTo(BmsAttributes.DFHGREEN);
        }

        @Test
        @DisplayName("initial() mirrors MOVE LOW-VALUES TO COMEN1AO")
        void initialIsTheFirstEntryRepaint() {
            MainMenuResponse initial = MainMenuResponse.initial();

            assertThat(initial.resetAllOutputFields()).isTrue();
            assertThat(MainMenuResponse.builder().build().resetAllOutputFields()).isFalse();
            assertThat(initial.navigationContext()).isEqualTo(NavigationContext.empty());
            assertThat(initial.trnName()).isNull();
            assertThat(initial.errMsg()).isNull();
        }

        @Test
        @DisplayName("CDEMO-USER-TYPE is echoed byte-identically, with no defaulting")
        void userTypeEchoedUnaltered() {
            for (String userType : List.of("A", "U", " ", "X")) {
                NavigationContext inbound = NavigationContext.empty()
                        .withUserType(userType)
                        .withUserId("USER0001");

                MainMenuResponse response = MainMenuResponse.builder()
                        .navigationContext(inbound)
                        .build();

                assertThat(response.navigationContext()).isSameAs(inbound);
                assertThat(response.navigationContext().userType()).isEqualTo(userType);
                assertThat(response.navigationContext().userId()).isEqualTo("USER0001");
            }
        }

        @Test
        @DisplayName("admin and user contexts survive the echo with their 88-levels intact")
        void adminAndUserContextsPreserved() {
            NavigationContext admin = NavigationContext.empty().withUserTypeAdmin();
            NavigationContext user = NavigationContext.empty().withUserTypeUser();

            assertThat(MainMenuResponse.builder().navigationContext(admin).build()
                    .navigationContext().isAdmin()).isTrue();
            assertThat(MainMenuResponse.builder().navigationContext(user).build()
                    .navigationContext().isUser()).isTrue();
        }

        @Test
        @DisplayName("a null communication area falls back to the initial 160-byte area")
        void nullContextFallsBackToEmpty() {
            assertThat(MainMenuResponse.builder().navigationContext(null).build()
                    .navigationContext()).isEqualTo(NavigationContext.empty());
            assertThat(NavigationContext.COMMAREA_LENGTH).isEqualTo(160);
        }

        @Test
        @DisplayName("PGM-CONTEXT enter and reenter both survive the echo")
        void programContextPreserved() {
            assertThat(MainMenuResponse.builder()
                    .navigationContext(NavigationContext.empty().withPgmReenter()).build()
                    .navigationContext().isReenter()).isTrue();
            assertThat(MainMenuResponse.builder()
                    .navigationContext(NavigationContext.empty().withPgmEnter()).build()
                    .navigationContext().isEnter()).isTrue();
        }
    }

    // =============================================================================================
    @Nested
    @DisplayName("Pure data: no truncation, no padding, no coercion")
    class PureData {

        @Test
        @DisplayName("an over-width message is stored verbatim, never truncated to 78")
        void doesNotTruncate() {
            String eighty = "X".repeat(80);

            assertThat(MainMenuResponse.builder().errMsg(eighty).build().errMsg())
                    .isEqualTo(eighty)
                    .hasSize(80);
        }

        @Test
        @DisplayName("an all-spaces option line is preserved exactly, not trimmed")
        void doesNotTrim() {
            String spaces = " ".repeat(MainMenuResponse.OPTION_LINE_LENGTH);

            MainMenuResponse response = MainMenuResponse.builder().optionLine(1, spaces).build();

            assertThat(response.optn001()).isEqualTo(spaces).hasSize(40);
        }

        @ParameterizedTest(name = "option {0} keeps its exact two characters")
        @ValueSource(strings = {"01", "02", "09", "10", "00", "  "})
        void optionIsAZeroFilledStringNotAnInt(String option) {
            assertThat(MainMenuResponse.builder().option(option).build().option())
                    .isEqualTo(option)
                    .hasSize(MainMenuResponse.OPTION_LENGTH);
        }

        @Test
        @DisplayName("option is declared as a String component")
        void optionComponentIsString() throws NoSuchMethodException {
            assertThat(MainMenuResponse.class.getMethod("option").getReturnType())
                    .isEqualTo(String.class);
            assertThat(MainMenuResponse.class.getMethod("errMsgColor").getReturnType())
                    .isEqualTo(byte.class);
        }

        @Test
        @DisplayName("no component is a floating-point or BigDecimal type")
        void noFloatingPointAnywhere() {
            for (RecordComponent component : MainMenuResponse.class.getRecordComponents()) {
                assertThat(component.getType()).isNotIn(double.class,
                        float.class,
                        Double.class,
                        Float.class,
                        java.math.BigDecimal.class);
            }
        }
    }

    // =============================================================================================
    @Nested
    @DisplayName("Immutability")
    class Immutability {

        @Test
        @DisplayName("the option-line view rejects add, set and remove")
        void optionLineViewIsUnmodifiable() {
            MainMenuResponse response = populated();
            List<String> lines = response.optionLines();

            assertThatThrownBy(() -> lines.set(0, "hacked"))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> lines.add("hacked"))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> lines.clear())
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThat(response.optn001()).isEqualTo("01. Option 1");
        }

        @Test
        @DisplayName("a fresh view is handed out each time, so one caller cannot affect another")
        void viewsAreIndependent() {
            MainMenuResponse response = populated();

            assertThat(response.optionLines())
                    .isNotSameAs(response.optionLines())
                    .isEqualTo(response.optionLines());
        }

        @Test
        @DisplayName("every with-method returns a new instance and leaves the original untouched")
        void withMethodsDoNotMutate() {
            MainMenuResponse original = populated();

            MainMenuResponse changed = original.withErrMsg("changed")
                    .withErrMsgColor(BmsAttributes.DFHGREEN)
                    .withNextProgram("COBIL00C")
                    .withOptionLine(12, "twelve")
                    .withNavigationContext(NavigationContext.empty().withUserTypeAdmin());

            assertThat(original.errMsg()).isEqualTo("Please enter a valid option number...");
            assertThat(original.errMsgColor()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(original.nextProgram()).isEqualTo("COACTVWC");
            assertThat(original.optn012()).isNull();

            assertThat(changed).isNotSameAs(original);
            assertThat(changed.errMsg()).isEqualTo("changed");
            assertThat(changed.errMsgColor()).isEqualTo(BmsAttributes.DFHGREEN);
            assertThat(changed.nextProgram()).isEqualTo("COBIL00C");
            assertThat(changed.optn012()).isEqualTo("twelve");
            assertThat(changed.navigationContext().isAdmin()).isTrue();
            // Everything not named by a with-method is carried across unchanged.
            assertThat(changed.trnName()).isEqualTo(original.trnName());
            assertThat(changed.optn010()).isEqualTo(original.optn010());
            assertThat(changed.nextMapset()).isEqualTo(original.nextMapset());
            assertThat(changed.resetAllOutputFields()).isEqualTo(original.resetAllOutputFields());
        }

        @Test
        @DisplayName("toBuilder round-trips every one of the 26 components")
        void toBuilderPreservesEverything() {
            MainMenuResponse original = populated()
                    .withOptionLine(11, "eleven")
                    .withOptionLine(12, "twelve");

            assertThat(original.toBuilder().build()).isEqualTo(original);
        }

        @Test
        @DisplayName("value semantics: equals, hashCode and toString are derived from components")
        void valueSemantics() {
            assertThat(populated()).isEqualTo(populated())
                    .hasSameHashCodeAs(populated())
                    .isNotEqualTo(populated().withErrMsg("other"));
            assertThat(populated().toString()).contains("trnName=CM00");
        }
    }

    // =============================================================================================
    @Nested
    @DisplayName("JSON: payload only, metadata never")
    class Json {

        @Test
        @DisplayName("no attribute-metadata key appears in the serialised form")
        void metadataExcluded() throws IOException {
            @SuppressWarnings("unchecked")
            Map<String, Object> json = MAPPER.readValue(MAPPER.writeValueAsString(populated()),
                    Map.class);

            for (String key : json.keySet()) {
                assertThat(key.substring(key.length() - 1))
                        .as("key '" + key + "' must not end in an attribute-item suffix")
                        .doesNotMatch("[LFACPHV]");
            }
            for (String field : List.of("trnName", "title01", "curDate", "pgmName", "title02",
                    "curTime", "optn001", "optn012", "option", "errMsg")) {
                for (String suffix : List.of("L", "F", "A", "C", "P", "H", "V", "I")) {
                    assertThat(json).doesNotContainKey(field + suffix);
                }
            }
            assertThat(json).doesNotContainKeys("optionLines", "populatedByProgram", "optionLine");
        }

        @Test
        @DisplayName("the serialised form is exactly the 26 declared members")
        void jsonIsExactlyTheComponents() throws IOException {
            @SuppressWarnings("unchecked")
            Map<String, Object> json = MAPPER.readValue(MAPPER.writeValueAsString(populated()),
                    Map.class);

            List<String> components = Stream.of(MainMenuResponse.class.getRecordComponents())
                    .map(RecordComponent::getName).toList();

            // Two of the components are deliberately not published: errMsgColor is the ERRMSGC
            // attribute byte, which COMEN01.CPY:256 declares as metadata, and resetAllOutputFields
            // names an action rather than a field. Both are @JsonIgnore'd.
            List<String> published = components.stream()
                    .filter(name -> !UNPUBLISHED_MEMBERS.contains(name)).toList();

            assertThat(components).hasSize(26).containsAll(UNPUBLISHED_MEMBERS);
            assertThat(json.keySet()).containsExactlyInAnyOrderElementsOf(published).hasSize(24);
        }

        @Test
        @DisplayName("round-trip preserves all-spaces option lines and a zero-filled option")
        void roundTripIsLossless() throws IOException {
            MainMenuResponse.Builder builder = MainMenuResponse.builder()
                    .trnName("CM00")
                    .option("01")
                    .errMsg(" ".repeat(MainMenuResponse.ERR_MSG_LENGTH))
                    .navigationContext(NavigationContext.empty().withUserTypeUser());
            for (int slot = 1; slot <= MainMenuResponse.OPTION_LINE_COUNT; slot++) {
                builder.optionLine(slot, " ".repeat(MainMenuResponse.OPTION_LINE_LENGTH));
            }
            MainMenuResponse original = builder.build();

            MainMenuResponse revived = MAPPER.readValue(MAPPER.writeValueAsString(original),
                    MainMenuResponse.class);

            // Whole-object equality is deliberately not asserted: errMsgColor and resetAllOutputFields
            // are @JsonIgnore'd metadata and do not travel, so they come back at the canonical
            // constructor's defaults. Every published member is checked instead.
            assertThat(revived.errMsgColor()).isEqualTo(BmsAttributes.DFHDFCOL);
            assertThat(revived.trnName()).isEqualTo(original.trnName());
            assertThat(revived.navigationContext()).isEqualTo(original.navigationContext());
            assertThat(revived.nextMapset()).isEqualTo(original.nextMapset());
            assertThat(revived.nextMap()).isEqualTo(original.nextMap());
            assertThat(revived.option()).isEqualTo("01").hasSize(2);
            assertThat(revived.errMsg()).hasSize(78).isBlank();
            for (int slot = 1; slot <= MainMenuResponse.OPTION_LINE_COUNT; slot++) {
                assertThat(revived.optionLine(slot)).hasSize(40).isBlank();
            }
        }

        @Test
        @DisplayName("round-trip preserves option 10 without numeric coercion")
        void roundTripKeepsTwoDigitOption() throws IOException {
            MainMenuResponse revived = MAPPER.readValue(
                    MAPPER.writeValueAsString(populated().toBuilder().option("10").build()),
                    MainMenuResponse.class);

            assertThat(revived.option()).isEqualTo("10");
            assertThat(MAPPER.writeValueAsString(revived)).contains("\"option\":\"10\"");
        }

        @Test
        @DisplayName("round-trip preserves the echoed communication area; the colour byte is internal")
        void roundTripKeepsContextAndColour() throws IOException {
            MainMenuResponse original = MainMenuResponse.initial()
                    .withErrMsgColor(BmsAttributes.DFHGREEN)
                    .withNavigationContext(NavigationContext.empty()
                            .withUserTypeAdmin().withPgmReenter());

            MainMenuResponse revived = MAPPER.readValue(MAPPER.writeValueAsString(original),
                    MainMenuResponse.class);

            // The communication area is payload and survives whole.
            assertThat(revived.navigationContext().userType()).isEqualTo("A");
            assertThat(revived.navigationContext().isReenter()).isTrue();
            assertThat(revived.errMsg()).isEqualTo(original.errMsg());
            assertThat(revived.option()).isEqualTo(original.option());

            // The two metadata members are not on the wire, so they come back at their defaults rather
            // than being carried. Ignoring them is the fix; this is what the fix looks like from a
            // client's side, and it is correct: a presentation attribute is the server's decision.
            // DFHDFCOL is X'00', the terminal's own default colour, so a response read off the wire
            // carries a coherent attribute rather than a nonsense one.
            assertThat(revived.errMsgColor()).isEqualTo(BmsAttributes.DFHDFCOL);
            assertThat(revived.resetAllOutputFields()).isFalse();
            assertThat(original.errMsgColor()).isEqualTo(BmsAttributes.DFHGREEN);
            assertThat(original.resetAllOutputFields()).isTrue();
        }

        @Test
        @DisplayName("the ERRMSGC colour byte and the reset signal are not JSON properties")
        void theTwoMetadataMembersAreNotPublished() throws IOException {
            MainMenuResponse response = MainMenuResponse.initial()
                    .withErrMsgColor(BmsAttributes.DFHGREEN);

            @SuppressWarnings("unchecked")
            Map<String, Object> json =
                    MAPPER.readValue(MAPPER.writeValueAsString(response), Map.class);

            assertThat(json.keySet()).doesNotContainAnyElementsOf(UNPUBLISHED_MEMBERS);
            // Still reachable in Java, which is what "internal" means.
            assertThat(response.errMsgColor()).isEqualTo(BmsAttributes.DFHGREEN);
            assertThat(response.resetAllOutputFields()).isTrue();
        }
    }

    // =============================================================================================
    @Nested
    @DisplayName("Consumer fit: the canonical constructor is directly usable")
    class ConsumerFit {

        @Test
        @DisplayName("all 26 components can be supplied positionally")
        void canonicalConstructorUsable() {
            MainMenuResponse response = new MainMenuResponse("CM00",
                    "t1", "08/08/26", "COMEN01C", "t2", "09:33:18",
                    "o1", "o2", "o3", "o4", "o5", "o6", "o7", "o8", "o9", "o10", "o11", "o12",
                    "01", "msg",
                    NavigationContext.empty(),
                    "COACTVWC", "COMEN01", "COMEN1A",
                    BmsAttributes.DFHRED, false);

            assertThat(response.trnName()).isEqualTo("CM00");
            assertThat(response.optn012()).isEqualTo("o12");
            assertThat(response.optionLines()).containsExactly("o1", "o2", "o3", "o4", "o5", "o6",
                    "o7", "o8", "o9", "o10", "o11", "o12");
        }
    }
}

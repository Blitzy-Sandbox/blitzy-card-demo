package com.vsergeychik.carddemo.admin.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.common.NavigationContext;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link MainMenuRequest}, the inbound projection of the {@code xxxI} items of
 * {@code 01 COMEN1AI} in {@code app/cpy-bms/COMEN01.CPY}.
 *
 * <p>The suite is organised around the properties of the symbolic map and of
 * {@code app/cbl/COMEN01C.cbl} that determine the implementation, so a failure names a translation
 * decision rather than merely a value:
 *
 * <ol>
 *   <li>Exactly <strong>twenty</strong> map members, in map order, at the widths the copybook
 *       declares - and no metadata item among them (gate G9).</li>
 *   <li>The declared geometry: {@code 4 + 40 + 8 + 8 + 40 + 8 + 12x40 + 2 + 78 = 668} payload bytes
 *       inside a {@code 12 + 20x7 + 668 = 820}-byte image.</li>
 *   <li>{@link Size} is the <em>only</em> constraint and it caps only the maximum, because
 *       {@code COMEN01C.cbl:127-134} answers a bad option with a message rather than a
 *       rejection.</li>
 *   <li>{@code OPTIONI} is carried raw: no trim, no pad, no re-justification, no parse, because
 *       {@code COMEN01C.cbl:117-124} consumes the exact space pattern the terminal sent.</li>
 *   <li>The conversation travels in the payload and nowhere else (gate G37), with
 *       {@code CDEMO-USER-TYPE} received verbatim and never defaulted.</li>
 *   <li>The wire format carries the twenty-two components and nothing else - no {@code xxxL},
 *       {@code xxxF}, {@code xxxA}, {@code xxxC}, {@code xxxP}, {@code xxxH} or {@code xxxV} key.</li>
 *   <li>The type is structurally independent of the admin menu request even though the two copybooks
 *       are byte-identical apart from their group names (practice B4), and holds no static mutable
 *       state (gate G53).</li>
 * </ol>
 */
@DisplayName("MainMenuRequest - the COMEN01 symbolic-map input projection (GET /api/menu, CM00)")
class MainMenuRequestTest {

    /** The twenty map-derived components, in the order {@code app/cpy-bms/COMEN01.CPY} declares them. */
    private static final List<String> MAP_COMPONENTS_IN_MAP_ORDER = List.of("trnName",
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

    /** The two conversation carriers that follow the map members, in declaration order. */
    private static final List<String> CONVERSATION_COMPONENTS = List.of("navigationContext", "eibAid");

    /**
     * The twenty name-labelled {@code DFHMDF} fields of {@code app/bms/COMEN01.bms}, in map order.
     * Each one contributes a {@code xxxL}, {@code xxxF} and {@code xxxA} metadata item to
     * {@code 01 COMEN1AI} and a {@code xxxC}, {@code xxxP}, {@code xxxH} and {@code xxxV} item to
     * {@code 01 COMEN1AO}; none of those may ever reach the wire.
     */
    private static final List<String> BMS_FIELD_NAMES = List.of("TRNNAME",
            "TITLE01",
            "CURDATE",
            "PGMNAME",
            "TITLE02",
            "CURTIME",
            "OPTN001",
            "OPTN002",
            "OPTN003",
            "OPTN004",
            "OPTN005",
            "OPTN006",
            "OPTN007",
            "OPTN008",
            "OPTN009",
            "OPTN010",
            "OPTN011",
            "OPTN012",
            "OPTION",
            "ERRMSG");

    /** The metadata suffixes of the input and output views - never the payload suffix {@code I}. */
    private static final char[] METADATA_SUFFIXES = {'L', 'F', 'A', 'C', 'P', 'H', 'V'};

    /** {@code DFHENTER}, the AID byte {@code COMEN01C.cbl:94} matches first. */
    private static final byte DFH_ENTER = (byte) 0x7D;

    /** {@code DFHPF3}, the AID byte {@code COMEN01C.cbl:96} matches to leave the menu. */
    private static final byte DFH_PF3 = (byte) 0xF3;

    private static ValidatorFactory validatorFactory;

    private static Validator validator;

    @BeforeAll
    static void openValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    // =================================================================================================
    // Shared fixtures. Every helper builds a fresh instance: nothing is shared mutable state, which is
    // the same property the class under test guarantees.
    // =================================================================================================

    private static NavigationContext regularUserOnReentry() {
        return NavigationContext.empty()
                .withFromTranid(MainMenuRequest.TRANSACTION_ID)
                .withFromProgram(MainMenuRequest.PROGRAM_NAME)
                .withUserId("USER0001")
                .withUserTypeUser()
                .withPgmReenter()
                .withLastMap(MainMenuRequest.MAP_NAME)
                .withLastMapset(MainMenuRequest.MAPSET_NAME);
    }

    private static MainMenuRequest populated(NavigationContext context, byte eibAid) {
        return new MainMenuRequest("CM00",
                "                CardDemo                ",
                "08/22/22",
                "COMEN01C",
                "      AWS Mainframe Modernization       ",
                "17:02:43",
                "01. Account View                        ",
                "02. Account Update                      ",
                "03. Credit Card List                    ",
                "04. Credit Card View                    ",
                "05. Credit Card Update                  ",
                "06. Transaction List                    ",
                "07. Transaction View                    ",
                "08. Transaction Add                     ",
                "09. Transaction Reports                 ",
                "10. Bill Payment                        ",
                "                                        ",
                "                                        ",
                " 1",
                "Please enter a valid option number...    ",
                context,
                eibAid);
    }

    /** The canonical constructor, resolved from the record components so it cannot drift. */
    private static MainMenuRequest construct(Object[] arguments) throws ReflectiveOperationException {
        Class<?>[] parameterTypes = Arrays.stream(MainMenuRequest.class.getRecordComponents())
                .map(RecordComponent::getType)
                .toArray(Class<?>[]::new);
        return MainMenuRequest.class.getDeclaredConstructor(parameterTypes).newInstance(arguments);
    }

    /** Constructor arguments with every map member {@code null} - the minimal legal payload. */
    private static Object[] allNullMapMembers() {
        Object[] arguments = new Object[MAP_COMPONENTS_IN_MAP_ORDER.size() + 2];
        Arrays.fill(arguments, 0, MAP_COMPONENTS_IN_MAP_ORDER.size(), null);
        arguments[MAP_COMPONENTS_IN_MAP_ORDER.size()] = NavigationContext.empty();
        arguments[MAP_COMPONENTS_IN_MAP_ORDER.size() + 1] = (byte) 0;
        return arguments;
    }

    private static Method accessor(String componentName) {
        return Arrays.stream(MainMenuRequest.class.getRecordComponents())
                .filter(component -> component.getName().equals(componentName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no record component named " + componentName))
                .getAccessor();
    }

    private static int declaredMaximum(String componentName) {
        Size size = accessor(componentName).getAnnotation(Size.class);
        assertThat(size).as("@Size on accessor %s()", componentName).isNotNull();
        return size.max();
    }

    /** The width the copybook declares for each of the twenty {@code xxxI} items. */
    private static Stream<Arguments> declaredWidths() {
        return Stream.of(Arguments.of("trnName", 4, "TRNNAMEI PIC X(4)", 24),
                Arguments.of("title01", 40, "TITLE01I PIC X(40)", 30),
                Arguments.of("curDate", 8, "CURDATEI PIC X(8)", 36),
                Arguments.of("pgmName", 8, "PGMNAMEI PIC X(8)", 42),
                Arguments.of("title02", 40, "TITLE02I PIC X(40)", 48),
                Arguments.of("curTime", 8, "CURTIMEI PIC X(8)", 54),
                Arguments.of("optn001", 40, "OPTN001I PIC X(40)", 60),
                Arguments.of("optn002", 40, "OPTN002I PIC X(40)", 66),
                Arguments.of("optn003", 40, "OPTN003I PIC X(40)", 72),
                Arguments.of("optn004", 40, "OPTN004I PIC X(40)", 78),
                Arguments.of("optn005", 40, "OPTN005I PIC X(40)", 84),
                Arguments.of("optn006", 40, "OPTN006I PIC X(40)", 90),
                Arguments.of("optn007", 40, "OPTN007I PIC X(40)", 96),
                Arguments.of("optn008", 40, "OPTN008I PIC X(40)", 102),
                Arguments.of("optn009", 40, "OPTN009I PIC X(40)", 108),
                Arguments.of("optn010", 40, "OPTN010I PIC X(40)", 114),
                Arguments.of("optn011", 40, "OPTN011I PIC X(40)", 120),
                Arguments.of("optn012", 40, "OPTN012I PIC X(40)", 126),
                Arguments.of("option", 2, "OPTIONI PIC X(2)", 132),
                Arguments.of("errMsg", 78, "ERRMSGI PIC X(78)", 138));
    }

    @Nested
    @DisplayName("Projection accuracy - twenty xxxI items, in map order, at their declared widths (G9)")
    class ProjectionAccuracy {

        @Test
        @DisplayName("twenty map members plus the two conversation carriers, and nothing else")
        void componentCount() {
            List<String> declared = Arrays.stream(MainMenuRequest.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(declared).hasSize(MAP_COMPONENTS_IN_MAP_ORDER.size()
                    + CONVERSATION_COMPONENTS.size());
            assertThat(declared).startsWith(MAP_COMPONENTS_IN_MAP_ORDER.toArray(String[]::new));
            assertThat(declared).endsWith(CONVERSATION_COMPONENTS.toArray(String[]::new));
        }

        @Test
        @DisplayName("the map members are in map order, TRNNAME through ERRMSG")
        void mapOrderIsPreserved() {
            List<String> mapMembers = Arrays.stream(MainMenuRequest.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .filter(name -> !CONVERSATION_COMPONENTS.contains(name))
                    .toList();

            assertThat(mapMembers).containsExactlyElementsOf(MAP_COMPONENTS_IN_MAP_ORDER);
        }

        @Test
        @DisplayName("the member count equals the name-labelled DFHMDF count of app/bms/COMEN01.bms")
        void twentyOfTwentyEightScreenFields() {
            assertThat(MainMenuRequest.MAP_FIELD_COUNT).isEqualTo(MAP_COMPONENTS_IN_MAP_ORDER.size());
            assertThat(MainMenuRequest.SCREEN_FIELD_COUNT).isEqualTo(28);
            assertThat(MainMenuRequest.SCREEN_FIELD_COUNT - MainMenuRequest.MAP_FIELD_COUNT)
                    .as("the eight unnamed literal fields are not payload")
                    .isEqualTo(8);
        }

        @ParameterizedTest(name = "{0} is {1} wide - {2}, app/cpy-bms/COMEN01.CPY:{3}")
        @MethodSource(
                "com.vsergeychik.carddemo.admin.dto.MainMenuRequestTest#declaredWidths")
        @DisplayName("every member's @Size maximum is the width its xxxI PICTURE declares")
        void widthsMatchTheCopybook(String component, int width, String picture, int copybookLine) {
            assertThat(declaredMaximum(component))
                    .as("%s must be %d wide (%s at line %d)", component, width, picture, copybookLine)
                    .isEqualTo(width);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource(
                "com.vsergeychik.carddemo.admin.dto.MainMenuRequestTest#declaredWidths")
        @DisplayName("@Size is propagated to the field as well as the accessor, and is a String member")
        void sizeIsOnFieldAndAccessor(String component, int width, String picture, int copybookLine)
                throws ReflectiveOperationException {
            Field field = MainMenuRequest.class.getDeclaredField(component);

            assertThat(field.getType()).isEqualTo(String.class);
            assertThat(field.getAnnotation(Size.class)).isNotNull();
            assertThat(field.getAnnotation(Size.class).max()).isEqualTo(width);
            assertThat(accessor(component).getReturnType()).isEqualTo(String.class);
        }

        @Test
        @DisplayName("no metadata item leaks in: nothing named xxxL, xxxF, xxxA, xxxC, xxxP, xxxH, xxxV")
        void noMetadataMember() {
            assertThat(MAP_COMPONENTS_IN_MAP_ORDER).allSatisfy(name ->
                    assertThat(name).doesNotEndWith("L")
                            .doesNotEndWith("F")
                            .doesNotEndWith("A")
                            .doesNotEndWith("C")
                            .doesNotEndWith("P")
                            .doesNotEndWith("H")
                            .doesNotEndWith("V"));
        }

        @Test
        @DisplayName("no member is a double, a float or a BigDecimal - no decimal PICTURE on this screen")
        void noBinaryFloatingPointAnywhere() {
            assertThat(MainMenuRequest.class.getRecordComponents())
                    .allSatisfy(component -> assertThat(component.getType())
                            .isNotIn(double.class, float.class, Double.class, Float.class,
                                    BigDecimal.class));
        }

        @Test
        @DisplayName("each BMS field name projects onto exactly one Java member, one for one")
        void projectionIsOneForOne() {
            assertThat(BMS_FIELD_NAMES).hasSameSizeAs(MAP_COMPONENTS_IN_MAP_ORDER);

            for (int i = 0; i < BMS_FIELD_NAMES.size(); i++) {
                String bmsField = BMS_FIELD_NAMES.get(i);
                String javaMember = MAP_COMPONENTS_IN_MAP_ORDER.get(i);

                assertThat(javaMember)
                        .as("%s of app/bms/COMEN01.bms projects onto %s", bmsField, javaMember)
                        .isEqualToIgnoringCase(bmsField.equals("TRNNAME") ? "trnName"
                                : bmsField.equals("PGMNAME") ? "pgmName"
                                : bmsField.equals("CURDATE") ? "curDate"
                                : bmsField.equals("CURTIME") ? "curTime"
                                : bmsField.equals("ERRMSG") ? "errMsg"
                                : bmsField);
            }
        }

        @Test
        @DisplayName("the component types are String, NavigationContext and byte - nothing else")
        void componentTypesAreExactlyThree() {
            assertThat(Arrays.stream(MainMenuRequest.class.getRecordComponents())
                    .map(RecordComponent::getType)
                    .distinct()
                    .toList())
                    .containsExactlyInAnyOrder(String.class, NavigationContext.class, byte.class);
        }
    }

    @Nested
    @DisplayName("Symbolic-map geometry - 668 payload bytes inside an 820-byte image")
    class Geometry {

        @Test
        @DisplayName("the twenty payload widths sum to 668")
        void payloadSum() {
            int summed = MAP_COMPONENTS_IN_MAP_ORDER.stream()
                    .mapToInt(MainMenuRequestTest::declaredMaximum)
                    .sum();

            assertThat(summed).isEqualTo(668);
            assertThat(MainMenuRequest.SYMBOLIC_MAP_PAYLOAD_LENGTH).isEqualTo(summed);
        }

        @Test
        @DisplayName("12 + 20 x 7 + 668 = 820, the width of the whole COMEN1AI group item")
        void wholeImage() {
            assertThat(MainMenuRequest.TIOAPFX_FILLER_LENGTH).isEqualTo(12);
            assertThat(MainMenuRequest.FIELD_METADATA_LENGTH).isEqualTo(7);
            assertThat(MainMenuRequest.SYMBOLIC_MAP_LENGTH).isEqualTo(820);
            assertThat(MainMenuRequest.SYMBOLIC_MAP_LENGTH)
                    .isEqualTo(MainMenuRequest.TIOAPFX_FILLER_LENGTH
                            + MainMenuRequest.MAP_FIELD_COUNT * MainMenuRequest.FIELD_METADATA_LENGTH
                            + MainMenuRequest.SYMBOLIC_MAP_PAYLOAD_LENGTH);
        }

        @Test
        @DisplayName("all twelve option lines exist, at one shared width, even though COMEN01C fills ten")
        void twelveOptionLines() {
            assertThat(MainMenuRequest.OPTION_LINE_COUNT).isEqualTo(12);
            assertThat(MainMenuRequest.OPTION_LINE_LENGTH).isEqualTo(40);
            assertThat(MAP_COMPONENTS_IN_MAP_ORDER.stream().filter(n -> n.startsWith("optn")).toList())
                    .hasSize(MainMenuRequest.OPTION_LINE_COUNT);
        }

        @Test
        @DisplayName("ERRMSG is 78, never the 80 of WS-MESSAGE - the narrowing belongs to the controller")
        void errMsgIsSeventyEight() {
            assertThat(MainMenuRequest.ERR_MSG_LENGTH).isEqualTo(78).isNotEqualTo(80);
        }
    }

    @Nested
    @DisplayName("Screen identity - CM00 / COMEN01C / COMEN01 / COMEN1A")
    class ScreenIdentity {

        @Test
        @DisplayName("the four literals are byte-exact")
        void literals() {
            assertThat(MainMenuRequest.TRANSACTION_ID).isEqualTo("CM00");
            assertThat(MainMenuRequest.PROGRAM_NAME).isEqualTo("COMEN01C");
            assertThat(MainMenuRequest.MAPSET_NAME).isEqualTo("COMEN01");
            assertThat(MainMenuRequest.MAP_NAME).isEqualTo("COMEN1A");
        }

        @Test
        @DisplayName("each literal fits the field or commarea slot that carries it")
        void literalWidths() {
            assertThat(MainMenuRequest.TRANSACTION_ID).hasSize(MainMenuRequest.TRN_NAME_LENGTH);
            assertThat(MainMenuRequest.PROGRAM_NAME).hasSize(MainMenuRequest.PGM_NAME_LENGTH);
            assertThat(MainMenuRequest.MAP_NAME).hasSize(NavigationContext.LAST_MAP_LENGTH);
            assertThat(MainMenuRequest.MAPSET_NAME).hasSize(NavigationContext.LAST_MAPSET_LENGTH);
        }
    }

    @Nested
    @DisplayName("Bean Validation - @Size is the only constraint, and it caps only the maximum")
    class BeanValidation {

        @Test
        @DisplayName("every map member may be null: COMEN01C reads only OPTIONI and rejects nothing")
        void nullMembersAreValid() throws ReflectiveOperationException {
            MainMenuRequest minimal = construct(allNullMapMembers());

            assertThat(validator.validate(minimal)).isEmpty();
            assertThat(minimal.option()).isNull();
            assertThat(minimal.errMsg()).isNull();
        }

        @Test
        @DisplayName("a fully populated payload at the declared widths is valid")
        void atWidthIsValid() {
            assertThat(validator.validate(populated(regularUserOnReentry(), DFH_ENTER))).isEmpty();
        }

        @ParameterizedTest(name = "{0} rejects a value of {1} + 1 characters")
        @MethodSource(
                "com.vsergeychik.carddemo.admin.dto.MainMenuRequestTest#declaredWidths")
        @DisplayName("one character past the map width is the only thing rejected, and only there")
        void overWidthIsRejected(String component, int width, String picture, int copybookLine)
                throws ReflectiveOperationException {
            Object[] arguments = allNullMapMembers();
            arguments[MAP_COMPONENTS_IN_MAP_ORDER.indexOf(component)] = "x".repeat(width + 1);

            Set<ConstraintViolation<MainMenuRequest>> violations =
                    validator.validate(construct(arguments));

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath())
                    .hasToString(component);
        }

        @ParameterizedTest(name = "{0} accepts a value of exactly its declared width")
        @MethodSource(
                "com.vsergeychik.carddemo.admin.dto.MainMenuRequestTest#declaredWidths")
        @DisplayName("exactly the map width is accepted - the boundary is inclusive")
        void exactWidthIsAccepted(String component, int width, String picture, int copybookLine)
                throws ReflectiveOperationException {
            Object[] arguments = allNullMapMembers();
            arguments[MAP_COMPONENTS_IN_MAP_ORDER.indexOf(component)] = "x".repeat(width);

            assertThat(validator.validate(construct(arguments))).isEmpty();
        }

        @Test
        @DisplayName("no @NotNull and no @NotBlank anywhere: a rejection would change behaviour")
        void noPresenceConstraints() throws ReflectiveOperationException {
            for (RecordComponent component : MainMenuRequest.class.getRecordComponents()) {
                Field field = MainMenuRequest.class.getDeclaredField(component.getName());

                assertThat(field.getAnnotation(NotNull.class))
                        .as("@NotNull on %s", component.getName())
                        .isNull();
                assertThat(field.getAnnotation(NotBlank.class))
                        .as("@NotBlank on %s", component.getName())
                        .isNull();
                assertThat(component.getAccessor().getAnnotation(NotNull.class)).isNull();
                assertThat(component.getAccessor().getAnnotation(NotBlank.class)).isNull();
            }
        }

        @Test
        @DisplayName("an absent communication area is valid - COMEN01C.cbl:82 handles it, it is not an error")
        void absentCommareaIsValid() throws ReflectiveOperationException {
            Object[] arguments = allNullMapMembers();
            arguments[MAP_COMPONENTS_IN_MAP_ORDER.size()] = null;

            MainMenuRequest signedOut = construct(arguments);

            assertThat(validator.validate(signedOut)).isEmpty();
            assertThat(signedOut.commareaAbsent()).isTrue();
        }
    }

    @Nested
    @DisplayName("OPTIONI is carried raw - no trim, no pad, no re-justification, no parse")
    class OptionPassThrough {

        @ParameterizedTest(name = "option [{0}] survives verbatim")
        @ValueSource(strings = {"", " ", "  ", "1", " 1", "1 ", "01", "12", " 0", "0 "})
        @DisplayName("the exact space pattern the terminal sent reaches COMEN01C.cbl:117-124 untouched")
        void valueSurvivesVerbatim(String raw) throws ReflectiveOperationException {
            Object[] arguments = allNullMapMembers();
            arguments[MAP_COMPONENTS_IN_MAP_ORDER.indexOf("option")] = raw;

            MainMenuRequest request = construct(arguments);

            assertThat(request.option()).isEqualTo(raw).hasSameSizeAs(raw);
            assertThat(validator.validate(request)).isEmpty();
        }

        @ParameterizedTest(name = "option [{0}] survives a JSON round trip verbatim")
        @ValueSource(strings = {"", " ", "  ", " 1", "1 ", "12"})
        @DisplayName("Jackson neither trims the value nor coerces it to a number")
        void survivesJsonRoundTrip(String raw) throws Exception {
            Object[] arguments = allNullMapMembers();
            arguments[MAP_COMPONENTS_IN_MAP_ORDER.indexOf("option")] = raw;
            ObjectMapper mapper = new ObjectMapper();

            String json = mapper.writeValueAsString(construct(arguments));
            MainMenuRequest revived = mapper.readValue(json, MainMenuRequest.class);

            assertThat(mapper.readTree(json).get("option").isTextual()).isTrue();
            assertThat(revived.option()).isEqualTo(raw);
        }

        @Test
        @DisplayName("a blank option raises no violation - COMEN01C answers it with a message, not a refusal")
        void blankOptionIsAccepted() throws ReflectiveOperationException {
            Object[] arguments = allNullMapMembers();
            arguments[MAP_COMPONENTS_IN_MAP_ORDER.indexOf("option")] = "  ";

            assertThat(validator.validate(construct(arguments))).isEmpty();
        }
    }

    @Nested
    @DisplayName("The twelve option lines - always present, unmodifiable, addressed one-based")
    class OptionLines {

        @Test
        @DisplayName("twelve slots in map order, matching the twelve accessors")
        void twelveSlotsInMapOrder() {
            MainMenuRequest request = populated(regularUserOnReentry(), DFH_ENTER);

            assertThat(request.optionLines()).containsExactly(request.optn001(),
                    request.optn002(),
                    request.optn003(),
                    request.optn004(),
                    request.optn005(),
                    request.optn006(),
                    request.optn007(),
                    request.optn008(),
                    request.optn009(),
                    request.optn010(),
                    request.optn011(),
                    request.optn012());
        }

        @Test
        @DisplayName("still twelve slots when every line is null - the map declares twelve regardless")
        void nullsAreTolerated() throws ReflectiveOperationException {
            List<String> lines = construct(allNullMapMembers()).optionLines();

            assertThat(lines).hasSize(MainMenuRequest.OPTION_LINE_COUNT).containsOnlyNulls();
        }

        @Test
        @DisplayName("the returned list is unmodifiable: set, add, remove and clear all throw")
        void listIsUnmodifiable() {
            List<String> lines = populated(regularUserOnReentry(), DFH_ENTER).optionLines();

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> lines.set(0, "tampered"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> lines.add("tampered"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> lines.remove(0));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(lines::clear);
        }

        @Test
        @DisplayName("a failed mutation leaves the instance untouched, and each call returns a fresh wrapper")
        void instanceIsUnaffectedByAttemptedMutation() {
            MainMenuRequest request = populated(regularUserOnReentry(), DFH_ENTER);
            String before = request.optn001();
            List<String> first = request.optionLines();

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> first.set(0, "tampered"));

            assertThat(request.optn001()).isEqualTo(before);
            assertThat(request.optionLines()).isNotSameAs(first).isEqualTo(first);
        }

        @ParameterizedTest(name = "optionLine({0}) is the line at index {0} - 1")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12})
        @DisplayName("the index is one-based, as every COBOL subscript is")
        void oneBasedLookup(int mapPosition) {
            MainMenuRequest request = populated(regularUserOnReentry(), DFH_ENTER);

            assertThat(request.optionLine(mapPosition))
                    .isEqualTo(request.optionLines().get(mapPosition - 1));
        }

        @Test
        @DisplayName("position 1 is OPTN001 and position 12 is OPTN012 - both ends anchored")
        void bothEndsAnchored() {
            MainMenuRequest request = populated(regularUserOnReentry(), DFH_ENTER);

            assertThat(request.optionLine(1)).isEqualTo(request.optn001());
            assertThat(request.optionLine(MainMenuRequest.OPTION_LINE_COUNT))
                    .isEqualTo(request.optn012());
        }

        @ParameterizedTest(name = "optionLine({0}) is out of range")
        @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 13, 40, Integer.MAX_VALUE})
        @DisplayName("anything outside 1..12 names a field the map does not declare")
        void outOfRangeThrows(int mapPosition) {
            MainMenuRequest request = populated(regularUserOnReentry(), DFH_ENTER);

            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> request.optionLine(mapPosition))
                    .withMessageContaining(MainMenuRequest.MAP_NAME)
                    .withMessageContaining(MainMenuRequest.MAPSET_NAME);
        }
    }

    @Nested
    @DisplayName("The conversation travels in the payload - no server-side session (G37)")
    class Conversation {

        @Test
        @DisplayName("commareaPresent and commareaAbsent are exact opposites, both sides driven")
        void presenceIsTwoSided() throws ReflectiveOperationException {
            MainMenuRequest withArea = populated(regularUserOnReentry(), DFH_ENTER);
            Object[] arguments = allNullMapMembers();
            arguments[MAP_COMPONENTS_IN_MAP_ORDER.size()] = null;
            MainMenuRequest withoutArea = construct(arguments);

            assertThat(withArea.commareaPresent()).isTrue();
            assertThat(withArea.commareaAbsent()).isFalse();
            assertThat(withoutArea.commareaPresent()).isFalse();
            assertThat(withoutArea.commareaAbsent()).isTrue();
        }

        @Test
        @DisplayName("enter() and reenter() read through to CDEMO-PGM-CONTEXT, never a duplicated flag")
        void contextIsReadThrough() {
            MainMenuRequest onFirstEntry =
                    populated(NavigationContext.empty().withPgmEnter(), DFH_ENTER);
            MainMenuRequest onReentry = populated(regularUserOnReentry(), DFH_ENTER);

            assertThat(onFirstEntry.enter()).isTrue();
            assertThat(onFirstEntry.reenter()).isFalse();
            assertThat(onFirstEntry.navigationContext().pgmContext())
                    .isEqualTo(NavigationContext.PGM_CONTEXT_ENTER);

            assertThat(onReentry.enter()).isFalse();
            assertThat(onReentry.reenter()).isTrue();
            assertThat(onReentry.navigationContext().pgmContext())
                    .isEqualTo(NavigationContext.PGM_CONTEXT_REENTER);
        }

        @Test
        @DisplayName("with no communication area neither state is claimed - both predicates are false")
        void neitherStateIsClaimedWithoutAnArea() throws ReflectiveOperationException {
            Object[] arguments = allNullMapMembers();
            arguments[MAP_COMPONENTS_IN_MAP_ORDER.size()] = null;

            MainMenuRequest signedOut = construct(arguments);

            assertThat(signedOut.enter()).isFalse();
            assertThat(signedOut.reenter()).isFalse();
        }

        @ParameterizedTest(name = "EIBAID 0x{0} is carried verbatim")
        @ValueSource(ints = {0x00, 0x6D, 0x7D, 0xF1, 0xF3, 0xFF})
        @DisplayName("the raw AID byte is stored unchanged; resolving it belongs to the service")
        void rawAidIsCarriedVerbatim(int aid) throws ReflectiveOperationException {
            Object[] arguments = allNullMapMembers();
            arguments[MAP_COMPONENTS_IN_MAP_ORDER.size() + 1] = (byte) aid;

            assertThat(construct(arguments).eibAid()).isEqualTo((byte) aid);
        }

        @Test
        @DisplayName("DFHENTER and DFHPF3, the two AIDs COMEN01C.cbl:94 and :96 match, survive intact")
        void theTwoNamedAidsSurvive() {
            assertThat(populated(regularUserOnReentry(), DFH_ENTER).eibAid()).isEqualTo(DFH_ENTER);
            assertThat(populated(regularUserOnReentry(), DFH_PF3).eibAid()).isEqualTo(DFH_PF3);
        }

        @ParameterizedTest(name = "CDEMO-USER-TYPE [{0}] is returned byte-identical")
        @ValueSource(strings = {"A", "U", " ", "a", "u", "X"})
        @DisplayName("the user type is received verbatim and never derived, defaulted or overwritten")
        void userTypeIsPassedThroughUntouched(String userType) throws Exception {
            NavigationContext supplied = NavigationContext.empty().withUserType(userType);
            Object[] arguments = allNullMapMembers();
            arguments[MAP_COMPONENTS_IN_MAP_ORDER.size()] = supplied;
            ObjectMapper mapper = new ObjectMapper();

            MainMenuRequest request = construct(arguments);
            MainMenuRequest revived = mapper.readValue(mapper.writeValueAsString(request),
                    MainMenuRequest.class);

            assertThat(request.navigationContext().userType()).isEqualTo(userType);
            assertThat(revived.navigationContext().userType()).isEqualTo(userType);
            assertThat(request.navigationContext().userType().toCharArray())
                    .containsExactly(userType.toCharArray());
        }

        @Test
        @DisplayName("no user-type or role accessor of its own - the admin-only filter lives in the service")
        void noRoleAccessorIsDeclared() {
            List<String> declared = Arrays.stream(MainMenuRequest.class.getDeclaredMethods())
                    .map(Method::getName)
                    .toList();

            assertThat(declared).doesNotContain("userType",
                    "isAdmin",
                    "isUser",
                    "admin",
                    "role",
                    "authorities",
                    "password");
        }
    }

    @Nested
    @DisplayName("The JSON wire format - the twenty-two components and nothing else")
    class WireFormat {

        private Map<String, JsonNode> serialise(MainMenuRequest request) throws Exception {
            JsonNode root = new ObjectMapper().valueToTree(request);
            Map<String, JsonNode> byName = new LinkedHashMap<>();
            root.fieldNames().forEachRemaining(name -> byName.put(name, root.get(name)));
            return byName;
        }

        @Test
        @DisplayName("exactly the twenty map members plus navigationContext and eibAid")
        void keySet() throws Exception {
            List<String> expected = new ArrayList<>(MAP_COMPONENTS_IN_MAP_ORDER);
            expected.addAll(CONVERSATION_COMPONENTS);

            assertThat(serialise(populated(regularUserOnReentry(), DFH_ENTER)).keySet())
                    .containsExactlyInAnyOrderElementsOf(expected);
        }

        @Test
        @DisplayName("no xxxL, xxxF, xxxA, xxxC, xxxP, xxxH or xxxV metadata key appears")
        void noMetadataKeys() throws Exception {
            Set<String> keys = serialise(populated(regularUserOnReentry(), DFH_ENTER)).keySet();
            List<String> forbidden = new ArrayList<>();
            for (String bmsField : BMS_FIELD_NAMES) {
                for (char suffix : METADATA_SUFFIXES) {
                    forbidden.add(bmsField + suffix);
                }
            }

            assertThat(forbidden).hasSize(BMS_FIELD_NAMES.size() * METADATA_SUFFIXES.length);
            assertThat(forbidden).allSatisfy(metadataName -> assertThat(keys)
                    .as("metadata item %s must never reach the wire", metadataName)
                    .noneMatch(key -> key.equalsIgnoreCase(metadataName)));
        }

        @Test
        @DisplayName("the derived views are excluded: no optionLines, enter, reenter or commarea key")
        void derivedViewsAreNotSerialised() throws Exception {
            assertThat(serialise(populated(regularUserOnReentry(), DFH_ENTER)).keySet())
                    .doesNotContain("optionLines",
                            "optionLine",
                            "enter",
                            "reenter",
                            "commareaPresent",
                            "commareaAbsent");
        }

        @Test
        @DisplayName("a round trip preserves every component, including the record's value semantics")
        void roundTripIsLossless() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            MainMenuRequest original = populated(regularUserOnReentry(), DFH_PF3);

            String json = mapper.writeValueAsString(original);
            MainMenuRequest revived = mapper.readValue(json, MainMenuRequest.class);

            assertThat(revived).isEqualTo(original).hasSameHashCodeAs(original);
            assertThat(revived.optionLines()).isEqualTo(original.optionLines());
            assertThat(revived.eibAid()).isEqualTo(DFH_PF3);
            assertThat(mapper.writeValueAsString(revived)).isEqualTo(json);
        }

        @Test
        @DisplayName("an absent communication area round-trips as an absent one, not an empty one")
        void absentCommareaRoundTrips() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            Object[] arguments = allNullMapMembers();
            arguments[MAP_COMPONENTS_IN_MAP_ORDER.size()] = null;
            MainMenuRequest signedOut = construct(arguments);

            MainMenuRequest revived = mapper.readValue(mapper.writeValueAsString(signedOut),
                    MainMenuRequest.class);

            assertThat(revived.navigationContext()).isNull();
            assertThat(revived.commareaAbsent()).isTrue();
            assertThat(revived).isEqualTo(signedOut);
        }
    }

    @Nested
    @DisplayName("Structural independence from the admin twin, and no static mutable state")
    class Structure {

        @Test
        @DisplayName("a record with no superclass beyond Record and no interface - practice B4")
        void standsAlone() {
            assertThat(MainMenuRequest.class.isRecord()).isTrue();
            assertThat(MainMenuRequest.class.getSuperclass()).isEqualTo(Record.class);
            assertThat(MainMenuRequest.class.getInterfaces()).isEmpty();
            assertThat(Modifier.isFinal(MainMenuRequest.class.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("no member, parameter or return type reaches into the admin twin or the option table")
        void noForbiddenTypeInTheSignature() {
            List<String> referenced = new ArrayList<>();
            Arrays.stream(MainMenuRequest.class.getRecordComponents())
                    .forEach(component -> referenced.add(component.getType().getName()));
            Arrays.stream(MainMenuRequest.class.getDeclaredMethods()).forEach(method -> {
                referenced.add(method.getReturnType().getName());
                Arrays.stream(method.getParameterTypes())
                        .forEach(type -> referenced.add(type.getName()));
            });

            assertThat(referenced).allSatisfy(name -> assertThat(name)
                    .doesNotContain("AdminMenuRequest")
                    .doesNotContain("MenuOptions")
                    .doesNotContain("FixedWidth")
                    .doesNotContain("PfKeyResolver")
                    .doesNotContain("org.springframework")
                    .doesNotContain("jakarta.persistence")
                    .doesNotContain("HttpSession"));
        }

        @Test
        @DisplayName("every static field is final, and every instance field is final - gate G53")
        void noStaticMutableState() {
            for (Field field : MainMenuRequest.class.getDeclaredFields()) {
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("field %s must be final", field.getName())
                        .isTrue();
                if (Modifier.isStatic(field.getModifiers())) {
                    assertThat(field.getType().isPrimitive() || field.getType() == String.class)
                            .as("static field %s must be an immutable constant, was %s",
                                    field.getName(), field.getType().getName())
                            .isTrue();
                }
            }
        }

        @Test
        @DisplayName("the admin twin, once it lands, must carry the same widths and stay a separate type")
        void twinCarriesTheSameCopybookWidths() throws ReflectiveOperationException {
            Class<?> twin;
            try {
                twin = Class.forName("com.vsergeychik.carddemo.admin.dto.AdminMenuRequest");
            } catch (ClassNotFoundException notYetGenerated) {
                Assumptions.abort("AdminMenuRequest is not on the classpath yet; this cross-check "
                        + "activates as soon as the admin twin lands. app/cpy-bms/COMEN01.CPY and "
                        + "app/cpy-bms/COADM01.CPY are byte-identical apart from their group names, "
                        + "so the two projections cannot legitimately declare different widths.");
                return;
            }

            List<Integer> mine = MAP_COMPONENTS_IN_MAP_ORDER.stream()
                    .map(MainMenuRequestTest::declaredMaximum)
                    .sorted()
                    .toList();
            List<Integer> theirs = Arrays.stream(twin.getRecordComponents())
                    .map(RecordComponent::getAccessor)
                    .map(accessor -> accessor.getAnnotation(Size.class))
                    .filter(size -> size != null)
                    .map(Size::max)
                    .sorted()
                    .toList();

            assertThat(theirs)
                    .as("the twin copybooks declare identical widths, so the projections must too")
                    .containsExactlyElementsOf(mine);
            assertThat(MainMenuRequest.class.isAssignableFrom(twin)).isFalse();
            assertThat(twin.isAssignableFrom(MainMenuRequest.class)).isFalse();
        }

        @Test
        @DisplayName("value semantics: two payloads with the same twenty-two components are equal")
        void valueSemantics() {
            MainMenuRequest one = populated(regularUserOnReentry(), DFH_ENTER);
            MainMenuRequest same = populated(regularUserOnReentry(), DFH_ENTER);
            MainMenuRequest differentAid = populated(regularUserOnReentry(), DFH_PF3);

            assertThat(one).isEqualTo(same).hasSameHashCodeAs(same).isNotEqualTo(differentAid);
            assertThat(one.toString()).contains("COMEN01C");
        }
    }
}

package com.vsergeychik.carddemo.admin.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link MainMenuRequest}, the inbound payload of {@code GET /api/menu} - CICS transaction
 * {@code CM00}, program {@code COMEN01C}, mapset {@code COMEN01}, map {@code COMEN1A}.
 */
@DisplayName("MainMenuRequest - the COMEN1AI input projection of the CM00 main menu (GET /api/menu)")
class MainMenuRequestTest {
    private static final List<String> EXPECTED_ITEM_NAMES = List.of("TRNNAMEI",
            "TITLE01I",
            "CURDATEI",
            "PGMNAMEI",
            "TITLE02I",
            "CURTIMEI",
            "OPTN001I",
            "OPTN002I",
            "OPTN003I",
            "OPTN004I",
            "OPTN005I",
            "OPTN006I",
            "OPTN007I",
            "OPTN008I",
            "OPTN009I",
            "OPTN010I",
            "OPTN011I",
            "OPTN012I",
            "OPTIONI",
            "ERRMSGI");

    private static final List<String> EXPECTED_WIRE_NAMES = EXPECTED_ITEM_NAMES.stream()
            .map(item -> item.substring(0, item.length() - 1).toLowerCase(Locale.ROOT))
            .toList();

    private static final List<String> EXPECTED_MEMBER_NAMES = List.of("trnName",
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

    private static final List<Integer> EXPECTED_WIDTHS =
            List.of(4, 40, 8, 8, 40, 8, 40, 40, 40, 40, 40, 40, 40, 40, 40, 40, 40, 40, 2, 78);

    private static final List<String> EXPECTED_CARRIER_NAMES =
            List.of("navigationContext", "eibAid");

    private static final List<String> METADATA_SUFFIXES =
            List.of("L", "F", "A", "C", "P", "H", "V");

    private MainMenuRequest atDeclaredWidths(String option, NavigationContext context) {
        return new MainMenuRequest("CM00",
                "x".repeat(MainMenuRequest.TITLE_LENGTH),
                "07/19/22",
                "COMEN01C",
                "y".repeat(MainMenuRequest.TITLE_LENGTH),
                "23:12:33",
                "01. Account View",
                "02. Account Update",
                "03. Credit Card List",
                "04. Credit Card View",
                "05. Credit Card Update",
                "06. Transaction List",
                "07. Transaction View",
                "08. Transaction Add",
                "09. Transaction Reports",
                "10. Bill Payment",
                null,
                null,
                option,
                "z".repeat(MainMenuRequest.ERR_MSG_LENGTH),
                context,
                (byte) 0x7D);
    }

    private MainMenuRequest withScreenField(int index, String value) {
        String[] fields = new String[MainMenuRequest.MAP_FIELD_COUNT];
        fields[index] = value;
        return new MainMenuRequest(fields[0],
                fields[1],
                fields[2],
                fields[3],
                fields[4],
                fields[5],
                fields[6],
                fields[7],
                fields[8],
                fields[9],
                fields[10],
                fields[11],
                fields[12],
                fields[13],
                fields[14],
                fields[15],
                fields[16],
                fields[17],
                fields[18],
                fields[19],
                NavigationContext.empty(),
                (byte) 0x7D);
    }

    private List<String> recordComponentNames() {
        return Arrays.stream(MainMenuRequest.class.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toList());
    }

    private List<String> screenFieldsOf(MainMenuRequest request) {
        List<String> values = new ArrayList<>();
        for (String member : EXPECTED_MEMBER_NAMES) {
            try {
                values.add((String) MainMenuRequest.class.getMethod(member).invoke(request));
            } catch (ReflectiveOperationException unreachable) {
                throw new AssertionError("no accessor " + member + "() on MainMenuRequest",
                        unreachable);
            }
        }
        return values;
    }

    private int declaredWidthOf(int index) {
        try {
            Size size = MainMenuRequest.class.getMethod(EXPECTED_MEMBER_NAMES.get(index))
                    .getAnnotation(Size.class);
            assertThat(size).as("@Size on %s()", EXPECTED_MEMBER_NAMES.get(index)).isNotNull();
            return size.max();
        } catch (NoSuchMethodException absent) {
            throw new AssertionError("no accessor for " + EXPECTED_MEMBER_NAMES.get(index), absent);
        }
    }

    private List<String> violatedProperties(MainMenuRequest request) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(request)
                    .stream()
                    .map(ConstraintViolation::getPropertyPath)
                    .map(Object::toString)
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    @Nested
    @DisplayName("The twenty payload members, and only those - gate G9")
    class TheTwentyPayloadMembers {
        @Test
        @DisplayName("twenty screen members in map order, then the two carriers, and nothing else")
        void twentyScreenMembersThenTwoCarriers() {
            List<String> components = recordComponentNames();

            assertThat(components).hasSize(22);
            assertThat(components.subList(0, 20)).containsExactlyElementsOf(EXPECTED_MEMBER_NAMES);
            assertThat(components.subList(20, 22)).containsExactlyElementsOf(EXPECTED_CARRIER_NAMES);
            assertThat(MainMenuRequest.MAP_FIELD_COUNT).isEqualTo(20)
                    .isEqualTo(EXPECTED_MEMBER_NAMES.size());
        }

        @Test
        @DisplayName("each xxxI item projects onto exactly one Java member, one for one")
        void eachItemProjectsOntoExactlyOneMember() {
            assertThat(EXPECTED_ITEM_NAMES).hasSameSizeAs(EXPECTED_MEMBER_NAMES);
            assertThat(new LinkedHashSet<>(EXPECTED_MEMBER_NAMES)).hasSize(20);

            for (int index = 0; index < EXPECTED_ITEM_NAMES.size(); index++) {
                String item = EXPECTED_ITEM_NAMES.get(index);
                String member = EXPECTED_MEMBER_NAMES.get(index);

                assertThat(item).as("item %s must end in the payload suffix I", item).endsWith("I");
                assertThat(member).as("%s projects %s", member, item)
                        .isEqualToIgnoringCase(item.substring(0, item.length() - 1));
                assertThat(recordComponentNames()).contains(member);
            }
        }

        @Test
        @DisplayName("every screen member is a String - no decimal PICTURE appears on this screen")
        void everyScreenMemberIsAString() {
            for (RecordComponent component : MainMenuRequest.class.getRecordComponents()) {
                if (EXPECTED_MEMBER_NAMES.contains(component.getName())) {
                    assertThat(component.getType()).as("%s must be a String", component.getName())
                            .isEqualTo(String.class);
                }
            }

            assertThat(MainMenuRequest.class.getRecordComponents()).noneMatch(component ->
                    component.getType() == double.class
                            || component.getType() == float.class
                            || component.getType() == Double.class
                            || component.getType() == Float.class
                            || component.getType() == java.math.BigDecimal.class);
        }

        @Test
        @DisplayName("the carriers are a NavigationContext and a raw byte - nothing else is a member")
        void theCarriersAreAContextAndAByte() {
            List<Class<?>> types = Arrays.stream(MainMenuRequest.class.getRecordComponents())
                    .map(RecordComponent::getType)
                    .distinct()
                    .collect(Collectors.toList());

            assertThat(types).containsExactlyInAnyOrder(String.class,
                    NavigationContext.class,
                    byte.class);
        }

        @Test
        @DisplayName("twenty of the twenty-eight DFHMDF fields are named; the other eight are labels")
        void twentyOfTwentyEightDfhmdfFieldsAreNamed() {
            int namedFields = MainMenuRequest.MAP_FIELD_COUNT;
            int unnamedLiteralLabels = 8;

            assertThat(namedFields).isEqualTo(20);
            assertThat(unnamedLiteralLabels).isEqualTo(8);
            assertThat(namedFields + unnamedLiteralLabels).isEqualTo(28)
                    .isEqualTo(MainMenuRequest.SCREEN_FIELD_COUNT);
            assertThat(recordComponentNames().subList(0, 20)).hasSize(namedFields);

            assertThat(recordComponentNames()).doesNotContain("mainMenu",
                    "MAINMENU",
                    "heading",
                    "tranLabel",
                    "dateLabel",
                    "progLabel",
                    "timeLabel",
                    "prompt",
                    "footer");
        }

        @Test
        @DisplayName("the length item xxxL is absent: trnNameL is not a member")
        void theLengthItemIsAbsent() {
            assertThat(recordComponentNames()).doesNotContain("trnNameL", "TRNNAMEL", "trnNameLength");
            assertThatExceptionOfType(NoSuchMethodException.class)
                    .isThrownBy(() -> MainMenuRequest.class.getMethod("trnNameL"));

            assertThat(atDeclaredWidths("01", NavigationContext.empty()).trnName())
                    .hasSize(MainMenuRequest.TRN_NAME_LENGTH);
        }

        @Test
        @DisplayName("the flag byte xxxF is absent: optionF is not a member")
        void theFlagByteIsAbsent() {
            assertThat(recordComponentNames()).doesNotContain("optionF", "OPTIONF", "optionFlag");
            assertThatExceptionOfType(NoSuchMethodException.class)
                    .isThrownBy(() -> MainMenuRequest.class.getMethod("optionF"));
        }

        @Test
        @DisplayName("the attribute view xxxA is absent: errMsgA is not a member")
        void theAttributeViewIsAbsent() {
            assertThat(recordComponentNames()).doesNotContain("errMsgA", "ERRMSGA", "errMsgAttribute");
            assertThatExceptionOfType(NoSuchMethodException.class)
                    .isThrownBy(() -> MainMenuRequest.class.getMethod("errMsgA"));
        }

        @Test
        @DisplayName("no length, flag or attribute item reaches the payload for any of the twenty")
        void noMetadataItemIsAMemberForAnyField() {
            Set<String> components = new LinkedHashSet<>(recordComponentNames());

            for (int index = 0; index < EXPECTED_MEMBER_NAMES.size(); index++) {
                String member = EXPECTED_MEMBER_NAMES.get(index);
                String item = EXPECTED_ITEM_NAMES.get(index);
                String itemBase = item.substring(0, item.length() - 1);

                for (String suffix : METADATA_SUFFIXES) {
                    assertThat(components).as("metadata item %s%s must not be a payload member",
                            member,
                            suffix).doesNotContain(member + suffix, itemBase + suffix);
                }
            }

            assertThat(EXPECTED_MEMBER_NAMES.size() * METADATA_SUFFIXES.size())
                    .as("twenty fields x seven metadata suffixes were swept")
                    .isEqualTo(140);

            assertThat(components).hasSize(22)
                    .allSatisfy(name -> assertThat(EXPECTED_MEMBER_NAMES.contains(name)
                            || EXPECTED_CARRIER_NAMES.contains(name))
                            .as("component %s is either a screen field or a carrier", name)
                            .isTrue());
        }
    }

    @Nested
    @DisplayName("Width arithmetic, written out by hand - gates G21 and practice B11")
    class WidthArithmetic {
        @ParameterizedTest(name = "{1} is PIC X({2}) at COMEN01.CPY:{3}")
        @CsvSource({"0,  TRNNAMEI,  4,  24",
                "1,  TITLE01I, 40,  30",
                "2,  CURDATEI,  8,  36",
                "3,  PGMNAMEI,  8,  42",
                "4,  TITLE02I, 40,  48",
                "5,  CURTIMEI,  8,  54",
                "6,  OPTN001I, 40,  60",
                "7,  OPTN002I, 40,  66",
                "8,  OPTN003I, 40,  72",
                "9,  OPTN004I, 40,  78",
                "10, OPTN005I, 40,  84",
                "11, OPTN006I, 40,  90",
                "12, OPTN007I, 40,  96",
                "13, OPTN008I, 40, 102",
                "14, OPTN009I, 40, 108",
                "15, OPTN010I, 40, 114",
                "16, OPTN011I, 40, 120",
                "17, OPTN012I, 40, 126",
                "18, OPTIONI,   2, 132",
                "19, ERRMSGI,  78, 138"})
        @DisplayName("each width is pinned twice: to the subject's constant and to the copybook literal")
        void eachWidthIsPinnedTwice(int index, String itemName, int copybookWidth, int copybookLine) {
            assertThat(declaredWidthOf(index)).as("%s (%s:%d) declared width",
                    itemName,
                    "app/cpy-bms/COMEN01.CPY",
                    copybookLine).isEqualTo(copybookWidth);
            assertThat(EXPECTED_WIDTHS.get(index)).as("%s transcribed width", itemName)
                    .isEqualTo(copybookWidth);
            assertThat(EXPECTED_ITEM_NAMES.get(index)).isEqualTo(itemName);

            assertThat(violatedProperties(withScreenField(index, "x".repeat(copybookWidth)))).isEmpty();
            assertThat(violatedProperties(withScreenField(index, "x".repeat(copybookWidth + 1))))
                    .containsExactly(EXPECTED_MEMBER_NAMES.get(index));
        }

        @Test
        @DisplayName("the eight named width constants hold their copybook values")
        void theWidthConstantsHoldTheirCopybookValues() {
            assertThat(MainMenuRequest.TRN_NAME_LENGTH).isEqualTo(4);
            assertThat(MainMenuRequest.TITLE_LENGTH).isEqualTo(40);
            assertThat(MainMenuRequest.CUR_DATE_LENGTH).isEqualTo(8);
            assertThat(MainMenuRequest.PGM_NAME_LENGTH).isEqualTo(8);
            assertThat(MainMenuRequest.CUR_TIME_LENGTH).isEqualTo(8);
            assertThat(MainMenuRequest.OPTION_LINE_LENGTH).isEqualTo(40);
            assertThat(MainMenuRequest.OPTION_LENGTH).isEqualTo(2);
            assertThat(MainMenuRequest.ERR_MSG_LENGTH).isEqualTo(78);

            assertThat(MainMenuRequest.TITLE_LENGTH).isEqualTo(MainMenuRequest.OPTION_LINE_LENGTH);
            assertThat(declaredWidthOf(1)).isEqualTo(MainMenuRequest.TITLE_LENGTH);
            assertThat(declaredWidthOf(4)).isEqualTo(MainMenuRequest.TITLE_LENGTH);
            assertThat(declaredWidthOf(6)).isEqualTo(MainMenuRequest.OPTION_LINE_LENGTH);

            assertThat(MainMenuRequest.ERR_MSG_LENGTH).isNotEqualTo(80);
        }

        @Test
        @DisplayName("the twenty widths sum to 668 by hand, and the subject agrees")
        void theTwentyWidthsSumToSixHundredSixtyEight() {
            int byHand = 4 + 40 + 8 + 8 + 40 + 8 + 12 * 40 + 2 + 78;

            assertThat(byHand).isEqualTo(668);
            assertThat(EXPECTED_WIDTHS).hasSize(20);
            assertThat(EXPECTED_WIDTHS.stream().mapToInt(Integer::intValue).sum()).isEqualTo(byHand);

            assertThat(MainMenuRequest.SYMBOLIC_MAP_PAYLOAD_LENGTH).isEqualTo(668).isEqualTo(byHand);
        }

        @Test
        @DisplayName("the whole symbolic map is 12 + 20x7 + 668 = 820 bytes")
        void theWholeSymbolicMapIsEightHundredTwentyBytes() {
            assertThat(MainMenuRequest.FIELD_METADATA_LENGTH).isEqualTo(2 + 1 + 4).isEqualTo(7);

            assertThat(MainMenuRequest.TIOAPFX_FILLER_LENGTH).isEqualTo(12);

            int byHand = 12 + 20 * 7 + 668;

            assertThat(byHand).isEqualTo(820);
            assertThat(MainMenuRequest.SYMBOLIC_MAP_LENGTH).isEqualTo(820).isEqualTo(byHand);

            assertThat(MainMenuRequest.SYMBOLIC_MAP_LENGTH)
                    .isEqualTo(MainMenuRequest.TIOAPFX_FILLER_LENGTH
                            + MainMenuRequest.MAP_FIELD_COUNT
                                    * MainMenuRequest.FIELD_METADATA_LENGTH
                            + MainMenuRequest.SYMBOLIC_MAP_PAYLOAD_LENGTH);

            assertThat(MainMenuRequest.SYMBOLIC_MAP_LENGTH
                    - MainMenuRequest.SYMBOLIC_MAP_PAYLOAD_LENGTH)
                    .as("what is not screen data is the prefix and the twenty strides")
                    .isEqualTo(12 + 140);
        }

        @Test
        @DisplayName("composing the twenty fields produces 668 bytes, and dropping one fails it")
        void composingTheTwentyFieldsProducesSixHundredSixtyEightBytes() {
            FixedWidthCodec codec = new FixedWidthCodec(StandardCharsets.US_ASCII);
            MainMenuRequest request = atDeclaredWidths(" 1", NavigationContext.empty());

            assertThat(codec.charset()).isEqualTo(StandardCharsets.US_ASCII);

            StringBuilder image = new StringBuilder();
            List<String> values = screenFieldsOf(request);
            for (int index = 0; index < values.size(); index++) {
                String value = values.get(index);
                image.append(codec.movePicX(value == null ? "" : value, declaredWidthOf(index)));
            }

            assertThat(image.length()).isEqualTo(MainMenuRequest.SYMBOLIC_MAP_PAYLOAD_LENGTH)
                    .isEqualTo(668);

            byte[] encoded = codec.encodeImage(image.toString(), "COMEN1AI screen data");
            assertThat(encoded).hasSize(668);

            assertThat(image.length() - MainMenuRequest.OPTION_LINE_LENGTH)
                    .as("omitting one forty-byte option line must not still total 668")
                    .isNotEqualTo(MainMenuRequest.SYMBOLIC_MAP_PAYLOAD_LENGTH);
            assertThat(image.length() - MainMenuRequest.TRN_NAME_LENGTH)
                    .as("omitting the four-byte transaction name must not still total 668")
                    .isNotEqualTo(MainMenuRequest.SYMBOLIC_MAP_PAYLOAD_LENGTH);
        }

        @Test
        @DisplayName("an 820-byte record is what the map image would occupy end to end")
        void theSymbolicMapImageIsEightHundredTwentyBytesWide() {
            FixedWidthRecord image = new FixedWidthRecord(MainMenuRequest.SYMBOLIC_MAP_LENGTH,
                    StandardCharsets.US_ASCII);

            assertThat(image.recordLength()).isEqualTo(820);
            assertThat(image.charset()).isEqualTo(StandardCharsets.US_ASCII);
            assertThat(image.recordLength() - MainMenuRequest.TIOAPFX_FILLER_LENGTH
                    - MainMenuRequest.MAP_FIELD_COUNT * MainMenuRequest.FIELD_METADATA_LENGTH)
                    .as("what remains after the prefix and the twenty strides is the screen data")
                    .isEqualTo(MainMenuRequest.SYMBOLIC_MAP_PAYLOAD_LENGTH);
        }
    }

    @Nested
    @DisplayName("CDEMO-USER-TYPE is load-bearing on THIS screen - the one behavioural difference")
    class UserTypeIsLoadBearingHere {
        @Test
        @DisplayName("the user type is reachable through the carried communication area")
        void theUserTypeIsReachableThroughTheCarriedContext() {
            NavigationContext context = NavigationContext.empty().withUserTypeUser();
            MainMenuRequest request = atDeclaredWidths(" 1", context);

            assertThat(recordComponentNames()).contains("navigationContext");
            assertThat(request.navigationContext()).isSameAs(context);
            assertThat(request.navigationContext().userType())
                    .isEqualTo(NavigationContext.USER_TYPE_USER);
            assertThat(NavigationContext.USER_TYPE_FIELD).isEqualTo("CDEMO-USER-TYPE");
            assertThat(NavigationContext.USER_TYPE_LENGTH).isEqualTo(1);
        }

        @Test
        @DisplayName("both 88-level predicates are driven: ADMIN 'A' and USER 'U', each true and false")
        void bothUserTypeConditionsAreDriven() {
            MainMenuRequest asAdmin =
                    atDeclaredWidths(" 1", NavigationContext.empty().withUserTypeAdmin());
            MainMenuRequest asUser =
                    atDeclaredWidths(" 1", NavigationContext.empty().withUserTypeUser());

            assertThat(asAdmin.navigationContext().isAdmin()).isTrue();
            assertThat(asAdmin.navigationContext().isUser()).isFalse();
            assertThat(asUser.navigationContext().isUser()).isTrue();
            assertThat(asUser.navigationContext().isAdmin()).isFalse();

            assertThat(asAdmin.navigationContext().userType())
                    .isEqualTo(NavigationContext.USER_TYPE_ADMIN)
                    .isEqualTo("A");
            assertThat(asUser.navigationContext().userType())
                    .isEqualTo(NavigationContext.USER_TYPE_USER)
                    .isEqualTo("U");

            assertThat(asUser.navigationContext().isUser())
                    .as("COMEN01C.cbl:136 tests CDEMO-USRTYP-USER, so only 'U' can enter the guard")
                    .isNotEqualTo(asAdmin.navigationContext().isUser());
        }

        @ParameterizedTest(name = "CDEMO-USER-TYPE [{0}] survives byte-identical")
        @ValueSource(strings = {"A", "U", " ", "X", "a", "u", "0"})
        @DisplayName("every user type is carried verbatim - never derived, defaulted or upper-cased")
        void everyUserTypeIsCarriedVerbatim(String userType) throws Exception {
            NavigationContext supplied = NavigationContext.empty().withUserType(userType);
            MainMenuRequest request = atDeclaredWidths(" 1", supplied);
            ObjectMapper mapper = new ObjectMapper();

            assertThat(request.navigationContext().userType()).isEqualTo(userType)
                    .hasSize(NavigationContext.USER_TYPE_LENGTH);
            assertThat(request.navigationContext().userType().toCharArray())
                    .containsExactly(userType.toCharArray());

            MainMenuRequest revived =
                    mapper.readValue(mapper.writeValueAsString(request), MainMenuRequest.class);
            assertThat(revived.navigationContext().userType()).isEqualTo(userType);

            boolean admin = revived.navigationContext().isAdmin();
            boolean user = revived.navigationContext().isUser();
            if ("A".equals(userType)) {
                assertThat(admin).isTrue();
                assertThat(user).isFalse();
            } else if ("U".equals(userType)) {
                assertThat(user).isTrue();
                assertThat(admin).isFalse();
            } else {
                assertThat(admin).as("%s matches no 88-level, so no role is implied", userType)
                        .isFalse();
                assertThat(user).as("%s matches no 88-level, so no role is implied", userType)
                        .isFalse();
            }
        }

        @Test
        @DisplayName("a blank user type is neither role - the 88-levels are not complementary")
        void aBlankUserTypeIsNeitherRole() {
            MainMenuRequest beforeSignOn = atDeclaredWidths(" 1", NavigationContext.empty());

            assertThat(beforeSignOn.navigationContext().isAdmin()).isFalse();
            assertThat(beforeSignOn.navigationContext().isUser()).isFalse();
            assertThat(beforeSignOn.navigationContext().userType())
                    .isNotEqualTo(NavigationContext.USER_TYPE_ADMIN)
                    .isNotEqualTo(NavigationContext.USER_TYPE_USER);
        }

        @Test
        @DisplayName("the request declares no role accessor of its own - the filter lives in the service")
        void theRequestDeclaresNoRoleAccessor() {
            List<String> declared = Arrays.stream(MainMenuRequest.class.getDeclaredMethods())
                    .map(Method::getName)
                    .collect(Collectors.toList());

            assertThat(declared).doesNotContain("userType",
                    "isAdmin",
                    "isUser",
                    "admin",
                    "user",
                    "role",
                    "roles",
                    "authorities",
                    "authorised",
                    "adminOnly",
                    "password");

            assertThat(recordComponentNames()).doesNotContain("userType", "cdemoUserType", "role");
        }

        @Test
        @DisplayName("the user type is independent of the option, so the guard's inputs stay separate")
        void theUserTypeIsIndependentOfTheOption() {
            MainMenuRequest userPickingEight =
                    atDeclaredWidths(" 8", NavigationContext.empty().withUserTypeUser());
            MainMenuRequest adminPickingEight =
                    atDeclaredWidths(" 8", NavigationContext.empty().withUserTypeAdmin());

            assertThat(userPickingEight.option()).isEqualTo(" 8").isEqualTo(adminPickingEight.option());
            assertThat(userPickingEight.navigationContext().userType())
                    .isNotEqualTo(adminPickingEight.navigationContext().userType());
            assertThat(userPickingEight).isNotEqualTo(adminPickingEight);
        }
    }

    @Nested
    @DisplayName("OPTIONI is the only editable field, is capped at 2, and is carried entirely raw")
    class OptionIsTheOnlyEditableField {
        @Test
        @DisplayName("exactly two characters is accepted and three is rejected, naming option")
        void twoCharactersIsAcceptedAndThreeIsRejected() {
            assertThat(MainMenuRequest.OPTION_LENGTH).isEqualTo(2);
            assertThat(declaredWidthOf(EXPECTED_MEMBER_NAMES.indexOf("option"))).isEqualTo(2);

            assertThat(violatedProperties(atDeclaredWidths("10", NavigationContext.empty()))).isEmpty();
            assertThat(violatedProperties(atDeclaredWidths("100", NavigationContext.empty())))
                    .containsExactly("option");
        }

        @ParameterizedTest(name = "option [{0}] raises no violation")
        @ValueSource(strings = {"", " ", "  ", "1", " 1", "1 ", "10", "99", "00", " 0", "0 ", "ab", "**"})
        @DisplayName("anything of two characters or fewer is accepted, whatever it contains")
        void anythingOfTwoCharactersOrFewerIsAccepted(String option) {
            assertThat(violatedProperties(atDeclaredWidths(option, NavigationContext.empty())))
                    .as("option [%s] is at most two characters, so the constraint has nothing to say",
                            option)
                    .isEmpty();
        }

        @Test
        @DisplayName("a null option raises no violation - COMEN01C answers a blank with a message")
        void aNullOptionRaisesNoViolation() {
            assertThat(violatedProperties(atDeclaredWidths(null, NavigationContext.empty()))).isEmpty();
            assertThat(atDeclaredWidths(null, NavigationContext.empty()).option()).isNull();
        }

        @ParameterizedTest(name = "option [{0}] is returned byte-identical")
        @ValueSource(strings = {" 3", "3 ", "  ", " 1", "1 ", "10", " 0", "0 ", "07", "7 "})
        @DisplayName("the exact space pattern the terminal sent survives, byte for byte")
        void theExactSpacePatternSurvives(String option) {
            MainMenuRequest request = atDeclaredWidths(option, NavigationContext.empty());

            assertThat(request.option()).isEqualTo(option).hasSize(option.length());
            assertThat(request.option().toCharArray()).containsExactly(option.toCharArray());

            assertThat(request.option().indexOf(' '))
                    .as("option [%s] keeps its space in the position the terminal put it", option)
                    .isEqualTo(option.indexOf(' '));
        }

        @Test
        @DisplayName("\" 3\" stays \" 3\" and \"3 \" stays \"3 \" - the two are never conflated")
        void leadingAndTrailingSpacesAreBothPreservedAndDistinct() {
            MainMenuRequest rightJustified = atDeclaredWidths(" 3", NavigationContext.empty());
            MainMenuRequest leftJustified = atDeclaredWidths("3 ", NavigationContext.empty());

            assertThat(rightJustified.option()).isEqualTo(" 3");
            assertThat(rightJustified.option().toCharArray()).containsExactly(' ', '3');
            assertThat(leftJustified.option()).isEqualTo("3 ");
            assertThat(leftJustified.option().toCharArray()).containsExactly('3', ' ');

            assertThat(rightJustified.option()).isNotEqualTo(leftJustified.option());
            assertThat(rightJustified).isNotEqualTo(leftJustified);

            assertThat(rightJustified.option()).isNotEqualTo("3");
            assertThat(leftJustified.option()).isNotEqualTo("3");
        }

        @Test
        @DisplayName("no zero-fill, no re-justification and no numeric coercion is applied")
        void noNormalisationIsApplied() {
            assertThat(atDeclaredWidths(" 1", NavigationContext.empty()).option())
                    .as("zero-fill belongs to COMEN01C.cbl:123, not to this payload")
                    .isEqualTo(" 1")
                    .isNotEqualTo("01");
            assertThat(atDeclaredWidths("1 ", NavigationContext.empty()).option())
                    .as("right-justification belongs to COMEN01C.cbl:45, not to this payload")
                    .isEqualTo("1 ")
                    .isNotEqualTo(" 1");
            assertThat(MainMenuRequest.class.getRecordComponents()[18].getType())
                    .as("numeric conversion belongs to COMEN01C.cbl:124, so the member stays a String")
                    .isEqualTo(String.class);
        }

        @Test
        @DisplayName("Jackson neither trims the option nor coerces it to a number")
        void jacksonNeitherTrimsNorCoercesTheOption() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            MainMenuRequest original = atDeclaredWidths(" 3", NavigationContext.empty());

            String json = mapper.writeValueAsString(original);
            JsonNode node = mapper.readTree(json);

            assertThat(node.get("option").isTextual()).isTrue();
            assertThat(node.get("option").asText()).isEqualTo(" 3");
            assertThat(mapper.readValue(json, MainMenuRequest.class).option()).isEqualTo(" 3");

            String trailing = mapper.writeValueAsString(atDeclaredWidths("3 ",
                    NavigationContext.empty()));
            assertThat(mapper.readTree(trailing).get("option").asText()).isEqualTo("3 ");
            assertThat(mapper.readValue(trailing, MainMenuRequest.class).option()).isEqualTo("3 ");
        }

        @ParameterizedTest(name = "{0} rejects one character past its width and nothing shorter")
        @CsvSource({"trnName,  4", "title01, 40", "curDate,  8", "pgmName,  8", "title02, 40",
                "curTime,  8", "optn001, 40", "optn002, 40", "optn003, 40", "optn004, 40",
                "optn005, 40", "optn006, 40", "optn007, 40", "optn008, 40", "optn009, 40",
                "optn010, 40", "optn011, 40", "optn012, 40", "option,   2", "errMsg,  78"})
        @DisplayName("both sides of every @Size, named field by named field")
        void bothSidesOfEverySizeConstraint(String member, int width) {
            int index = EXPECTED_MEMBER_NAMES.indexOf(member);

            assertThat(index).as("%s is one of the twenty screen members", member).isNotNegative();
            assertThat(declaredWidthOf(index)).as("%s declared width", member).isEqualTo(width);

            assertThat(violatedProperties(withScreenField(index, "v".repeat(width)))).isEmpty();
            assertThat(violatedProperties(withScreenField(index, "v".repeat(width - 1)))).isEmpty();

            assertThat(violatedProperties(withScreenField(index, "v".repeat(width + 1))))
                    .containsExactly(member);
        }

        @Test
        @DisplayName("@Size is the only constraint: no @NotNull and no @NotBlank anywhere")
        void sizeIsTheOnlyConstraint() {
            for (RecordComponent component : MainMenuRequest.class.getRecordComponents()) {
                Method accessor = component.getAccessor();

                assertThat(accessor.getAnnotation(NotNull.class))
                        .as("%s must not be @NotNull", component.getName())
                        .isNull();
                assertThat(accessor.getAnnotation(NotBlank.class))
                        .as("%s must not be @NotBlank", component.getName())
                        .isNull();
            }

            assertThat(violatedProperties(withScreenField(0, null))).isEmpty();
        }

        @Test
        @DisplayName("the twenty constraints cap the maximum only - there is no minimum")
        void theConstraintsCapTheMaximumOnly() {
            for (int index = 0; index < EXPECTED_MEMBER_NAMES.size(); index++) {
                Size size = MainMenuRequest.class.getRecordComponents()[index]
                        .getAccessor()
                        .getAnnotation(Size.class);

                assertThat(size).as("%s carries @Size", EXPECTED_MEMBER_NAMES.get(index)).isNotNull();
                assertThat(size.min()).as("%s must have no minimum", EXPECTED_MEMBER_NAMES.get(index))
                        .isZero();
                assertThat(size.max()).isEqualTo(EXPECTED_WIDTHS.get(index));
            }

            assertThat(violatedProperties(atDeclaredWidths("", NavigationContext.empty()))).isEmpty();
        }

        @Test
        @DisplayName("only OPTIONI is editable: the other nineteen are ASKIP on the map")
        void onlyOptionIsEditable() {
            int editableFields = 1;

            assertThat(MainMenuRequest.MAP_FIELD_COUNT - editableFields)
                    .as("nineteen of the twenty named fields are skip-protected")
                    .isEqualTo(19);
            assertThat(recordComponentNames()).contains("option");
            assertThat(recordComponentNames()).doesNotContain("optionAttribute",
                    "optionProtected",
                    "optionUnprotected",
                    "optionEditable");
        }
    }

    @Nested
    @DisplayName("All twelve option slots, of which the program can fill ten - practice B5")
    class TwelveOptionSlots {
        @ParameterizedTest(name = "optn0{0} exists at PIC X(40), COMEN01.CPY:{1}")
        @CsvSource({"01,  60", "02,  66", "03,  72", "04,  78", "05,  84", "06,  90",
                "07,  96", "08, 102", "09, 108", "10, 114", "11, 120", "12, 126"})
        @DisplayName("all twelve slots exist, each forty bytes wide, none pruned")
        void allTwelveSlotsExist(String ordinal, int copybookLine) {
            String member = "optn0" + ordinal;
            int index = EXPECTED_MEMBER_NAMES.indexOf(member);

            assertThat(index).as("%s is a declared payload member", member).isNotNegative();
            assertThat(recordComponentNames()).contains(member);
            assertThat(declaredWidthOf(index)).as("%s (COMEN01.CPY:%d)", member, copybookLine)
                    .isEqualTo(40)
                    .isEqualTo(MainMenuRequest.OPTION_LINE_LENGTH);
            assertThat(EXPECTED_ITEM_NAMES.get(index)).isEqualTo("OPTN0" + ordinal + "I");
        }

        @Test
        @DisplayName("slots eleven and twelve are present even though COMEN01C can never write them")
        void slotsElevenAndTwelveArePresent() {
            assertThat(recordComponentNames()).contains("optn011", "optn012");
            assertThat(declaredWidthOf(EXPECTED_MEMBER_NAMES.indexOf("optn011"))).isEqualTo(40);
            assertThat(declaredWidthOf(EXPECTED_MEMBER_NAMES.indexOf("optn012"))).isEqualTo(40);

            MainMenuRequest eleventh =
                    withScreenField(EXPECTED_MEMBER_NAMES.indexOf("optn011"), "11. Never Written");
            MainMenuRequest twelfth =
                    withScreenField(EXPECTED_MEMBER_NAMES.indexOf("optn012"), "12. Never Written");

            assertThat(eleventh.optn011()).isEqualTo("11. Never Written");
            assertThat(twelfth.optn012()).isEqualTo("12. Never Written");
            assertThat(violatedProperties(eleventh)).isEmpty();
            assertThat(violatedProperties(twelfth)).isEmpty();
        }

        @Test
        @DisplayName("twelve slots and ten fillable captions are different numbers, both recorded")
        void twelveSlotsAndTenCaptionsAreBothRecorded() {
            int declaredSlots = MainMenuRequest.OPTION_LINE_COUNT;
            int fillableByTheProgram = 10;
            int unreachableSlots = declaredSlots - fillableByTheProgram;

            assertThat(declaredSlots).isEqualTo(12);
            assertThat(fillableByTheProgram).isEqualTo(10);
            assertThat(unreachableSlots).as("OPTN011I and OPTN012I can never be written")
                    .isEqualTo(2);

            assertThat(EXPECTED_MEMBER_NAMES.stream().filter(name -> name.startsWith("optn0")).count())
                    .isEqualTo(declaredSlots)
                    .isNotEqualTo((long) fillableByTheProgram);

            assertThat(declaredSlots * MainMenuRequest.OPTION_LINE_LENGTH).isEqualTo(480);
            assertThat(MainMenuRequest.SYMBOLIC_MAP_PAYLOAD_LENGTH
                    - fillableByTheProgram * MainMenuRequest.OPTION_LINE_LENGTH)
                    .as("ten slots would leave 668 short by eighty bytes")
                    .isNotEqualTo(MainMenuRequest.SYMBOLIC_MAP_PAYLOAD_LENGTH - 480);
        }

        @Test
        @DisplayName("optionLines() is always twelve long, in map order, nulls included")
        void optionLinesIsAlwaysTwelveLong() {
            MainMenuRequest populated = atDeclaredWidths(" 1", NavigationContext.empty());
            List<String> lines = populated.optionLines();

            assertThat(lines).hasSize(12).hasSize(MainMenuRequest.OPTION_LINE_COUNT);
            assertThat(lines.get(0)).isEqualTo(populated.optn001());
            assertThat(lines.get(9)).isEqualTo(populated.optn010());
            assertThat(lines.get(10)).isNull();
            assertThat(lines.get(11)).isNull();

            List<String> empty = withScreenField(0, "CM00").optionLines();
            assertThat(empty).hasSize(12).containsOnlyNulls();
        }

        @Test
        @DisplayName("optionLines() is unmodifiable and hands back a fresh wrapper each call")
        void optionLinesIsUnmodifiable() {
            MainMenuRequest request = atDeclaredWidths(" 1", NavigationContext.empty());
            List<String> lines = request.optionLines();

            assertThatThrownBy(() -> lines.set(0, "tampered"))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> lines.add("appended"))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> lines.remove(0))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(lines::clear).isInstanceOf(UnsupportedOperationException.class);

            assertThat(request.optn001()).isEqualTo("01. Account View");
            assertThat(request.optionLines()).isEqualTo(lines).isNotSameAs(lines);
        }

        @Test
        @DisplayName("optionLine(n) is ONE-based, as every COBOL subscript is")
        void optionLineIsOneBased() {
            MainMenuRequest request = atDeclaredWidths(" 1", NavigationContext.empty());

            assertThat(request.optionLine(1)).isEqualTo(request.optn001())
                    .isEqualTo("01. Account View");
            assertThat(request.optionLine(10)).isEqualTo(request.optn010())
                    .isEqualTo("10. Bill Payment");
            assertThat(request.optionLine(11)).isEqualTo(request.optn011()).isNull();
            assertThat(request.optionLine(12)).isEqualTo(request.optn012()).isNull();

            List<String> lines = request.optionLines();
            for (int position = 1; position <= MainMenuRequest.OPTION_LINE_COUNT; position++) {
                assertThat(request.optionLine(position))
                        .as("position %d must equal element %d of optionLines()", position, position - 1)
                        .isEqualTo(lines.get(position - 1));
            }
        }

        @ParameterizedTest(name = "optionLine({0}) is outside 1..12 and is refused")
        @ValueSource(ints = {-1, 0, 13, 99})
        @DisplayName("a subscript outside 1..12 names a field the map does not declare")
        void aSubscriptOutsideOneToTwelveIsRefused(int position) {
            MainMenuRequest request = atDeclaredWidths(" 1", NavigationContext.empty());

            assertThatThrownBy(() -> request.optionLine(position))
                    .isInstanceOf(IndexOutOfBoundsException.class);
        }
    }

    @Nested
    @DisplayName("The conversation travels in the payload, never in server state - gates G37 and G53")
    class ConversationStateTravelsInThePayload {
        @Test
        @DisplayName("the communication area is a payload member, not a session lookup")
        void theCommunicationAreaIsAPayloadMember() throws Exception {
            NavigationContext context = NavigationContext.empty().withUserTypeUser();
            MainMenuRequest request = atDeclaredWidths(" 1", context);

            assertThat(recordComponentNames()).contains("navigationContext");
            assertThat(MainMenuRequest.class.getMethod("navigationContext").getReturnType())
                    .isEqualTo(NavigationContext.class);
            assertThat(request.navigationContext()).isSameAs(context);
            assertThat(request.commareaPresent()).isTrue();
        }

        @Test
        @DisplayName("the carried communication area is exactly 160 bytes: 34 + 84 + 12 + 16 + 14")
        void theCarriedCommunicationAreaIsOneHundredSixtyBytes() {
            int byHand = 34 + 84 + 12 + 16 + 14;

            assertThat(byHand).isEqualTo(160);
            assertThat(NavigationContext.COMMAREA_LENGTH).isEqualTo(160).isEqualTo(byHand);
            assertThat(NavigationContext.GENERAL_INFO_LENGTH).isEqualTo(34);
            assertThat(NavigationContext.CUSTOMER_INFO_LENGTH).isEqualTo(84);
            assertThat(NavigationContext.ACCOUNT_INFO_LENGTH).isEqualTo(12);
            assertThat(NavigationContext.CARD_INFO_LENGTH).isEqualTo(16);
            assertThat(NavigationContext.MORE_INFO_LENGTH).isEqualTo(14);
            assertThat(NavigationContext.LAYOUT.recordLength()).isEqualTo(160);

            FixedWidthCodec codec = new FixedWidthCodec(StandardCharsets.US_ASCII);
            MainMenuRequest request = atDeclaredWidths(" 1", NavigationContext.empty()
                    .withFromTranid(MainMenuRequest.TRANSACTION_ID)
                    .withFromProgram(MainMenuRequest.PROGRAM_NAME)
                    .withUserId("USER0001")
                    .withUserTypeUser()
                    .withPgmReenter()
                    .withLastMap(MainMenuRequest.MAP_NAME)
                    .withLastMapset(MainMenuRequest.MAPSET_NAME));

            assertThat(codec.charset()).isEqualTo(StandardCharsets.US_ASCII);
            assertThat(request.navigationContext().toFixedWidth(codec)).hasSize(160);
        }

        @Test
        @DisplayName("the last map and mapset are X(7), not X(8) - the width the total depends on")
        void theLastMapAndMapsetAreSevenBytes() {
            assertThat(NavigationContext.LAST_MAP_LENGTH).isEqualTo(7);
            assertThat(NavigationContext.LAST_MAPSET_LENGTH).isEqualTo(7);
            assertThat(MainMenuRequest.MAP_NAME).hasSize(NavigationContext.LAST_MAP_LENGTH);
            assertThat(MainMenuRequest.MAPSET_NAME).hasSize(NavigationContext.LAST_MAPSET_LENGTH);

            FixedWidthRecord.FieldSpan lastMap =
                    NavigationContext.LAYOUT.span(NavigationContext.LAST_MAP_FIELD);
            FixedWidthRecord.FieldSpan lastMapset =
                    NavigationContext.LAYOUT.span(NavigationContext.LAST_MAPSET_FIELD);

            assertThat(lastMap.length()).isEqualTo(7);
            assertThat(lastMapset.length()).isEqualTo(7);
            assertThat(lastMapset.offset()).isEqualTo(lastMap.offset() + 7);
            assertThat(lastMapset.endOffsetExclusive()).isEqualTo(160);

            assertThat(34 + 84 + 12 + 16 + (8 + 8)).isNotEqualTo(NavigationContext.COMMAREA_LENGTH);
        }

        @Test
        @DisplayName("the raw EIBAID byte is a payload member and is stored unchanged")
        void theRawEibAidByteIsAPayloadMember() throws Exception {
            assertThat(recordComponentNames()).contains("eibAid");
            assertThat(MainMenuRequest.class.getMethod("eibAid").getReturnType()).isEqualTo(byte.class);

            for (byte candidate : new byte[] {(byte) 0x7D, (byte) 0xF3, (byte) 0x6D, (byte) 0x00,
                    (byte) 0xFF, (byte) 0xC1}) {
                MainMenuRequest request = new MainMenuRequest("CM00", null, null, "COMEN01C", null,
                        null, null, null, null, null, null, null, null, null, null, null, null, null,
                        " 1", null, NavigationContext.empty(), candidate);

                assertThat(request.eibAid()).as("AID byte 0x%02X survives", candidate)
                        .isEqualTo(candidate);
            }
        }

        @Test
        @DisplayName("DFHENTER and DFHPF3, the two AIDs COMEN01C.cbl:94 and :96 match, survive intact")
        void theTwoRecognisedAidsSurviveIntact() throws Exception {
            byte dfhEnter = (byte) 0x7D;
            byte dfhPf3 = (byte) 0xF3;
            ObjectMapper mapper = new ObjectMapper();

            MainMenuRequest entered = atDeclaredWidths(" 1", NavigationContext.empty());
            assertThat(entered.eibAid()).isEqualTo(dfhEnter);

            MainMenuRequest exited = new MainMenuRequest("CM00", null, null, "COMEN01C", null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, " 1", null,
                    NavigationContext.empty(), dfhPf3);
            assertThat(exited.eibAid()).isEqualTo(dfhPf3);
            assertThat(exited).isNotEqualTo(entered);

            assertThat(dfhPf3).isEqualTo((byte) -13);
            assertThat(mapper.readValue(mapper.writeValueAsString(exited), MainMenuRequest.class)
                    .eibAid()).isEqualTo(dfhPf3);
        }

        @Test
        @DisplayName("both program-context states are driven: ENTER 0 and REENTER 1, read through")
        void bothProgramContextStatesAreDriven() {
            MainMenuRequest onEnter =
                    atDeclaredWidths(" 1", NavigationContext.empty().withPgmEnter());
            MainMenuRequest onReenter =
                    atDeclaredWidths(" 1", NavigationContext.empty().withPgmReenter());

            assertThat(onEnter.enter()).isTrue();
            assertThat(onEnter.reenter()).isFalse();
            assertThat(onReenter.reenter()).isTrue();
            assertThat(onReenter.enter()).isFalse();

            assertThat(onEnter.enter()).isEqualTo(onEnter.navigationContext().isEnter());
            assertThat(onReenter.reenter()).isEqualTo(onReenter.navigationContext().isReenter());
            assertThat(onEnter.navigationContext().pgmContext())
                    .isEqualTo(NavigationContext.PGM_CONTEXT_ENTER)
                    .isZero();
            assertThat(onReenter.navigationContext().pgmContext())
                    .isEqualTo(NavigationContext.PGM_CONTEXT_REENTER)
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("the absent-communication-area marker reproduces IF EIBCALEN = 0 at COMEN01C.cbl:82")
        void theAbsentCommunicationAreaMarkerReproducesEibcalenZero() {
            MainMenuRequest withoutArea = atDeclaredWidths(" 1", null);
            MainMenuRequest withArea = atDeclaredWidths(" 1", NavigationContext.empty());

            assertThat(withoutArea.navigationContext()).isNull();
            assertThat(withoutArea.commareaAbsent()).isTrue();
            assertThat(withoutArea.commareaPresent()).isFalse();
            assertThat(violatedProperties(withoutArea)).isEmpty();

            assertThat(withArea.navigationContext()).isNotNull();
            assertThat(withArea.commareaAbsent()).isFalse();
            assertThat(withArea.commareaPresent()).isTrue();

            assertThat(withoutArea.commareaAbsent()).isNotEqualTo(withoutArea.commareaPresent());
            assertThat(withArea.commareaAbsent()).isNotEqualTo(withArea.commareaPresent());
        }

        @Test
        @DisplayName("with no communication area neither context state is claimed")
        void withNoCommunicationAreaNeitherContextStateIsClaimed() {
            MainMenuRequest signedOut = atDeclaredWidths(" 1", null);

            assertThat(signedOut.enter()).isFalse();
            assertThat(signedOut.reenter()).isFalse();
            assertThat(signedOut.commareaAbsent()).isTrue();
        }

        @Test
        @DisplayName("no HttpSession, no servlet type and no Spring type appears anywhere in the shape")
        void noServletOrSessionTypeAppearsInTheShape() {
            List<String> referenced = new ArrayList<>();
            Arrays.stream(MainMenuRequest.class.getRecordComponents())
                    .forEach(component -> referenced.add(component.getType().getName()));
            Arrays.stream(MainMenuRequest.class.getDeclaredMethods()).forEach(method -> {
                referenced.add(method.getReturnType().getName());
                Arrays.stream(method.getParameterTypes())
                        .forEach(type -> referenced.add(type.getName()));
            });

            assertThat(referenced).isNotEmpty()
                    .allSatisfy(name -> assertThat(name).doesNotContain("HttpSession")
                            .doesNotContain("HttpServletRequest")
                            .doesNotContain("HttpServletResponse")
                            .doesNotContain("jakarta.servlet")
                            .doesNotContain("org.springframework")
                            .doesNotContain("SecurityContext")
                            .doesNotContain("ThreadLocal")
                            .doesNotContain("jakarta.persistence"));

            assertThat(recordComponentNames()).doesNotContain("session",
                    "httpSession",
                    "sessionId",
                    "conversationId",
                    "cache",
                    "state");
        }

        @Test
        @DisplayName("no field is static and non-final, and no field is mutable at all - gate G53")
        void noStaticMutableStateExists() {
            Field[] fields = MainMenuRequest.class.getDeclaredFields();

            assertThat(fields).isNotEmpty();
            for (Field field : fields) {
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("field %s must be final", field.getName())
                        .isTrue();

                if (Modifier.isStatic(field.getModifiers())) {
                    assertThat(field.getType().isPrimitive()
                            || field.getType() == String.class
                            || field.getType() == Integer.class)
                            .as("static field %s must be an immutable constant, was %s",
                                    field.getName(),
                                    field.getType().getName())
                            .isTrue();
                    assertThat(field.getType().isArray())
                            .as("static field %s must not be an array", field.getName())
                            .isFalse();
                }
            }

            assertThat(Arrays.stream(fields)
                    .filter(field -> !Modifier.isStatic(field.getModifiers()))
                    .count()).isEqualTo(22);

            for (Field field : MainMenuRequestTest.class.getDeclaredFields()) {
                if (field.isSynthetic()) {
                    continue;
                }
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("test field %s must be final", field.getName())
                        .isTrue();
                assertThat(Modifier.isStatic(field.getModifiers()))
                        .as("test field %s must be static, so it is a constant not per-test state",
                                field.getName())
                        .isTrue();
            }
        }

        @Test
        @DisplayName("two requests never share mutable structure, so one cannot observe the other")
        void twoRequestsNeverShareMutableStructure() {
            MainMenuRequest first = atDeclaredWidths(" 1", NavigationContext.empty().withPgmEnter());
            MainMenuRequest second = atDeclaredWidths(" 2", NavigationContext.empty().withPgmReenter());

            assertThat(first.optionLines()).isNotSameAs(second.optionLines());
            assertThat(first.navigationContext()).isNotSameAs(second.navigationContext());
            assertThat(first.option()).isEqualTo(" 1");
            assertThat(second.option()).isEqualTo(" 2");
            assertThat(first.enter()).isTrue();
            assertThat(second.reenter()).isTrue();

            assertThat(atDeclaredWidths(" 1", NavigationContext.empty().withPgmEnter()))
                    .isEqualTo(first)
                    .hasSameHashCodeAs(first);
        }
    }

    @Nested
    @DisplayName("Screen identity - CM00 / COMEN01C / COMEN01 / COMEN1A, each fitting its field exactly")
    class ScreenIdentity {
        @Test
        @DisplayName("the transaction identifier is CM00, four characters into a PIC X(4) field")
        void theTransactionIdentifierIsCm00() {
            assertThat(MainMenuRequest.TRANSACTION_ID).isEqualTo("CM00")
                    .hasSize(4)
                    .hasSize(MainMenuRequest.TRN_NAME_LENGTH)
                    .isEqualTo(MainMenuRequest.TRANSACTION_ID.trim())
                    .doesNotContain(" ");

            assertThat(MainMenuRequest.TRANSACTION_ID).hasSize(declaredWidthOf(0));
            assertThat(MainMenuRequest.TRANSACTION_ID)
                    .hasSize(NavigationContext.FROM_TRANID_LENGTH);

            MainMenuRequest request = atDeclaredWidths(" 1", NavigationContext.empty());
            assertThat(request.trnName()).isEqualTo("CM00").isEqualTo(MainMenuRequest.TRANSACTION_ID);
            assertThat(violatedProperties(request)).isEmpty();
        }

        @Test
        @DisplayName("the program name is COMEN01C, eight characters into a PIC X(8) field")
        void theProgramNameIsComen01c() {
            assertThat(MainMenuRequest.PROGRAM_NAME).isEqualTo("COMEN01C")
                    .hasSize(8)
                    .hasSize(MainMenuRequest.PGM_NAME_LENGTH)
                    .isEqualTo(MainMenuRequest.PROGRAM_NAME.trim())
                    .doesNotContain(" ");

            assertThat(MainMenuRequest.PROGRAM_NAME).hasSize(declaredWidthOf(3));
            assertThat(MainMenuRequest.PROGRAM_NAME).hasSize(NavigationContext.FROM_PROGRAM_LENGTH);

            assertThat(atDeclaredWidths(" 1", NavigationContext.empty()).pgmName())
                    .isEqualTo("COMEN01C")
                    .isEqualTo(MainMenuRequest.PROGRAM_NAME);
        }

        @Test
        @DisplayName("the mapset is COMEN01 and the map is COMEN1A, seven characters each")
        void theMapsetAndMapAreSevenCharactersEach() {
            assertThat(MainMenuRequest.MAPSET_NAME).isEqualTo("COMEN01").hasSize(7);
            assertThat(MainMenuRequest.MAP_NAME).isEqualTo("COMEN1A").hasSize(7);
            assertThat(MainMenuRequest.MAPSET_NAME).hasSize(NavigationContext.LAST_MAPSET_LENGTH);
            assertThat(MainMenuRequest.MAP_NAME).hasSize(NavigationContext.LAST_MAP_LENGTH);

            assertThat(MainMenuRequest.MAP_NAME).isNotEqualTo(MainMenuRequest.MAPSET_NAME);

            NavigationContext carried = NavigationContext.empty()
                    .withLastMap(MainMenuRequest.MAP_NAME)
                    .withLastMapset(MainMenuRequest.MAPSET_NAME);
            assertThat(atDeclaredWidths(" 1", carried).navigationContext().lastMap())
                    .isEqualTo("COMEN1A");
            assertThat(atDeclaredWidths(" 1", carried).navigationContext().lastMapset())
                    .isEqualTo("COMEN01");
        }

        @Test
        @DisplayName("the four identity literals are distinct and none is blank")
        void theFourIdentityLiteralsAreDistinctAndNonBlank() {
            assertThat(List.of(MainMenuRequest.TRANSACTION_ID,
                    MainMenuRequest.PROGRAM_NAME,
                    MainMenuRequest.MAPSET_NAME,
                    MainMenuRequest.MAP_NAME))
                    .containsExactly("CM00", "COMEN01C", "COMEN01", "COMEN1A")
                    .doesNotHaveDuplicates()
                    .allSatisfy(literal -> assertThat(literal).isNotBlank());
        }
    }

    @Nested
    @DisplayName("The duplication with the admin menu is documented, not removed - practice B4")
    class DuplicationIsDocumentedNotRemoved {
        @Test
        @DisplayName("MainMenuRequest and AdminMenuRequest are distinct Java types, deliberately")
        void mainAndAdminMenuRequestsAreDistinctTypes() {
            assertThat(MainMenuRequest.class).isNotEqualTo(AdminMenuRequest.class);
            assertThat(MainMenuRequest.class.getName()).isNotEqualTo(AdminMenuRequest.class.getName());

            assertThat(MainMenuRequest.class.isAssignableFrom(AdminMenuRequest.class)).isFalse();
            assertThat(AdminMenuRequest.class.isAssignableFrom(MainMenuRequest.class)).isFalse();

            assertThat(MainMenuRequest.class.getSuperclass()).isEqualTo(Record.class);
            assertThat(AdminMenuRequest.class.getSuperclass()).isEqualTo(Record.class);
            assertThat(MainMenuRequest.class.getInterfaces()).isEmpty();
            assertThat(AdminMenuRequest.class.getInterfaces()).isEmpty();
            assertThat(MainMenuRequest.class.isRecord()).isTrue();
            assertThat(Modifier.isFinal(MainMenuRequest.class.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("the coincidence itself is asserted, so a future divergence surfaces here")
        void theCoincidenceIsAssertedRatherThanExploited() {
            assertThat(MainMenuRequest.MAP_FIELD_COUNT).isEqualTo(AdminMenuRequest.MAPPED_FIELD_COUNT);
            assertThat(MainMenuRequest.SYMBOLIC_MAP_PAYLOAD_LENGTH)
                    .isEqualTo(AdminMenuRequest.PAYLOAD_DATA_LENGTH);
            assertThat(MainMenuRequest.SYMBOLIC_MAP_LENGTH)
                    .isEqualTo(AdminMenuRequest.SYMBOLIC_MAP_LENGTH);
            assertThat(MainMenuRequest.FIELD_METADATA_LENGTH)
                    .isEqualTo(AdminMenuRequest.METADATA_BYTES_PER_FIELD);
            assertThat(MainMenuRequest.TIOAPFX_FILLER_LENGTH)
                    .isEqualTo(AdminMenuRequest.TIOAPFX_LENGTH);
            assertThat(MainMenuRequest.OPTION_LINE_COUNT)
                    .isEqualTo(AdminMenuRequest.OPTION_LINE_COUNT);
            assertThat(MainMenuRequest.OPTION_LENGTH).isEqualTo(AdminMenuRequest.OPTION_LENGTH);
            assertThat(MainMenuRequest.ERR_MSG_LENGTH).isEqualTo(AdminMenuRequest.ERR_MSG_LENGTH);

            List<String> theirScreenMembers = Arrays.stream(AdminMenuRequest.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .limit(20)
                    .collect(Collectors.toList());
            assertThat(theirScreenMembers).containsExactlyElementsOf(EXPECTED_MEMBER_NAMES);

            assertThat(MainMenuRequest.TRANSACTION_ID).isNotEqualTo(AdminMenuRequest.TRANSACTION_ID);
            assertThat(MainMenuRequest.PROGRAM_NAME).isNotEqualTo(AdminMenuRequest.PROGRAM_NAME);
            assertThat(MainMenuRequest.MAPSET_NAME).isNotEqualTo(AdminMenuRequest.MAPSET_NAME);
            assertThat(MainMenuRequest.MAP_NAME).isNotEqualTo(AdminMenuRequest.MAP_NAME);
        }

        @Test
        @DisplayName("this test class shares no base type, and the two suites are independent")
        void thisTestClassSharesNoBaseType() {
            assertThat(MainMenuRequestTest.class.getSuperclass()).isEqualTo(Object.class);
            assertThat(MainMenuRequestTest.class.getInterfaces()).isEmpty();

            assertThat(Arrays.stream(MainMenuRequestTest.class.getDeclaredMethods())
                    .flatMap(method -> {
                        List<Class<?>> types = new ArrayList<>();
                        types.add(method.getReturnType());
                        types.addAll(Arrays.asList(method.getParameterTypes()));
                        return types.stream();
                    })
                    .map(Class::getName)
                    .collect(Collectors.toList()))
                    .allSatisfy(name -> assertThat(name).doesNotContain("AdminMenuRequestTest"));

            for (Class<?> nested : MainMenuRequestTest.class.getDeclaredClasses()) {
                assertThat(nested.getSuperclass())
                        .as("nested class %s must stand alone", nested.getSimpleName())
                        .isEqualTo(Object.class);
            }
        }
    }

    @Nested
    @DisplayName("The wire carries the twenty screen fields, the area and the AID - nothing else")
    class JsonWireFormat {
        private Set<String> keysOf(MainMenuRequest request) throws Exception {
            JsonNode root = new ObjectMapper().valueToTree(request);
            Set<String> keys = new LinkedHashSet<>();
            root.fieldNames().forEachRemaining(keys::add);
            return keys;
        }

        @Test
        @DisplayName("exactly the twenty screen members plus navigationContext and eibAid")
        void exactlyTwentyTwoKeys() throws Exception {
            List<String> expected = new ArrayList<>(EXPECTED_WIRE_NAMES);
            expected.addAll(EXPECTED_CARRIER_NAMES);

            assertThat(keysOf(atDeclaredWidths(" 1", NavigationContext.empty())))
                    .containsExactlyInAnyOrderElementsOf(expected)
                    .hasSize(22);
        }

        @Test
        @DisplayName("no symbolic-map metadata item reaches the wire, under any capitalisation")
        void noMetadataItemReachesTheWire() throws Exception {
            Set<String> keys = keysOf(atDeclaredWidths(" 1", NavigationContext.empty()));
            List<String> forbidden = new ArrayList<>();
            for (String item : EXPECTED_ITEM_NAMES) {
                String base = item.substring(0, item.length() - 1);
                for (String suffix : METADATA_SUFFIXES) {
                    forbidden.add(base + suffix);
                }
            }

            assertThat(forbidden).hasSize(140);
            assertThat(forbidden).allSatisfy(metadata -> assertThat(keys)
                    .as("metadata item %s must never reach the wire", metadata)
                    .noneMatch(key -> key.equalsIgnoreCase(metadata)));

            assertThat(keys).doesNotContain("trnNameL", "optionF", "errMsgA", "TRNNAMEL", "OPTIONF",
                    "ERRMSGA");
        }

        @Test
        @DisplayName("the derived views are not serialised - they are reads, not state")
        void theDerivedViewsAreNotSerialised() throws Exception {
            assertThat(keysOf(atDeclaredWidths(" 1", NavigationContext.empty())))
                    .doesNotContain("optionLines",
                            "optionLine",
                            "commareaPresent",
                            "commareaAbsent",
                            "enter",
                            "reenter");
        }

        @Test
        @DisplayName("a round trip preserves every component, and re-serialises identically")
        void aRoundTripIsLossless() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            MainMenuRequest original = atDeclaredWidths(" 3", NavigationContext.empty()
                    .withFromTranid(MainMenuRequest.TRANSACTION_ID)
                    .withFromProgram(MainMenuRequest.PROGRAM_NAME)
                    .withUserId("USER0001")
                    .withUserTypeUser()
                    .withPgmReenter()
                    .withLastMap(MainMenuRequest.MAP_NAME)
                    .withLastMapset(MainMenuRequest.MAPSET_NAME));

            String json = mapper.writeValueAsString(original);
            MainMenuRequest revived = mapper.readValue(json, MainMenuRequest.class);

            assertThat(revived).isEqualTo(original).hasSameHashCodeAs(original);
            assertThat(revived.optionLines()).isEqualTo(original.optionLines());
            assertThat(revived.option()).isEqualTo(" 3");
            assertThat(revived.navigationContext().userType())
                    .isEqualTo(NavigationContext.USER_TYPE_USER);
            assertThat(revived.reenter()).isTrue();
            assertThat(mapper.writeValueAsString(revived)).isEqualTo(json);
        }

        @Test
        @DisplayName("an absent communication area round-trips as absent, not as an empty one")
        void anAbsentCommunicationAreaRoundTripsAsAbsent() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            MainMenuRequest signedOut = atDeclaredWidths(" 1", null);

            MainMenuRequest revived =
                    mapper.readValue(mapper.writeValueAsString(signedOut), MainMenuRequest.class);

            assertThat(revived.navigationContext()).isNull();
            assertThat(revived.commareaAbsent()).isTrue();
            assertThat(revived.commareaPresent()).isFalse();
            assertThat(revived).isEqualTo(signedOut);
        }

        @Test
        @DisplayName("toString names the screen, so a log line identifies which payload failed")
        void toStringNamesTheScreen() {
            assertThat(atDeclaredWidths(" 1", NavigationContext.empty()).toString())
                    .contains("COMEN01C")
                    .contains("CM00");
        }
    }
}

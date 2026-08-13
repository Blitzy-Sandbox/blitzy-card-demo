package com.vsergeychik.carddemo.admin.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.NavigationContext;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.lang.reflect.Field;
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
 * Tests for {@link AdminMenuRequest}, the inbound payload of {@code GET /api/admin/menu} - CICS transaction
 * {@code CA00}, program {@code COADM01C}, mapset {@code COADM01}, map {@code COADM1A}.
 */
@DisplayName("AdminMenuRequest - the COADM1AI input projection of the CA00 admin menu")
class AdminMenuRequestTest {
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

    private static final List<String> METADATA_SUFFIXES = List.of("L", "F", "A");

    private AdminMenuRequest atDeclaredWidths(String option, NavigationContext context) {
        return new AdminMenuRequest("CA00",
                "x".repeat(AdminMenuRequest.TITLE_LENGTH),
                "08/08/26",
                "COADM01C",
                "y".repeat(AdminMenuRequest.TITLE_LENGTH),
                "09:41:07",
                "01. User List (Security)",
                "02. User Add (Security)",
                "03. User Update (Security)",
                "04. User Delete (Security)",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                option,
                "z".repeat(AdminMenuRequest.ERR_MSG_LENGTH),
                context,
                (byte) 0x7D);
    }

    private AdminMenuRequest withScreenField(int index, String value) {
        String[] fields = new String[AdminMenuRequest.MAPPED_FIELD_COUNT];
        fields[index] = value;
        return new AdminMenuRequest(fields[0],
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
                null,
                (byte) 0x7D);
    }

    private int declaredWidthOf(int index) {
        return switch (index) {
            case 0 -> AdminMenuRequest.TRN_NAME_LENGTH;
            case 1, 4 -> AdminMenuRequest.TITLE_LENGTH;
            case 2 -> AdminMenuRequest.CUR_DATE_LENGTH;
            case 3 -> AdminMenuRequest.PGM_NAME_LENGTH;
            case 5 -> AdminMenuRequest.CUR_TIME_LENGTH;
            case 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17 -> AdminMenuRequest.OPTION_LINE_LENGTH;
            case 18 -> AdminMenuRequest.OPTION_LENGTH;
            case 19 -> AdminMenuRequest.ERR_MSG_LENGTH;
            default -> throw new IllegalArgumentException("no screen field at map position " + index);
        };
    }

    private Set<String> violatedProperties(AdminMenuRequest request) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(request)
                    .stream()
                    .map(ConstraintViolation::getPropertyPath)
                    .map(Object::toString)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
    }

    private List<String> screenFieldsOf(AdminMenuRequest request) {
        return Arrays.asList(request.trnName(),
                request.title01(),
                request.curDate(),
                request.pgmName(),
                request.title02(),
                request.curTime(),
                request.optn001(),
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
                request.optn012(),
                request.option(),
                request.errMsg());
    }

    private List<String> recordComponentNames() {
        return Arrays.stream(AdminMenuRequest.class.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toList());
    }

    @Nested
    @DisplayName("The twenty payload members, one per name-labelled DFHMDF (gate G9)")
    class TheTwentyPayloadMembers {
        @Test
        @DisplayName("there are exactly twenty screen members plus the two conversation carriers")
        void exactlyTwentyScreenMembersAndNothingElse() {
            List<String> components = recordComponentNames();

            assertThat(AdminMenuRequest.MAPPED_FIELD_COUNT).isEqualTo(20);
            assertThat(EXPECTED_MEMBER_NAMES).hasSize(20);
            assertThat(components).hasSize(22)
                    .startsWith(EXPECTED_MEMBER_NAMES.toArray(String[]::new))
                    .endsWith(EXPECTED_CARRIER_NAMES.toArray(String[]::new));
            assertThat(components).containsAll(EXPECTED_MEMBER_NAMES);
        }

        @Test
        @DisplayName("the twenty members appear in map order, not in some tidied-up order")
        void membersFollowMapOrder() {
            List<String> screenMembers = recordComponentNames().subList(0, 20);

            assertThat(screenMembers).containsExactlyElementsOf(EXPECTED_MEMBER_NAMES);
            assertThat(screenMembers).first().isEqualTo("trnName");
            assertThat(screenMembers).last().isEqualTo("errMsg");
            assertThat(screenMembers.get(18)).isEqualTo("option");
        }

        @Test
        @DisplayName("every member has an accessor returning String, and the carriers do not")
        void everyScreenMemberIsAnAlphanumericAccessor() throws Exception {
            for (String member : EXPECTED_MEMBER_NAMES) {
                assertThat(AdminMenuRequest.class.getMethod(member).getReturnType())
                        .as("accessor %s()", member)
                        .isEqualTo(String.class);
            }

            assertThat(AdminMenuRequest.class.getMethod("navigationContext").getReturnType())
                    .isEqualTo(NavigationContext.class);
            assertThat(AdminMenuRequest.class.getMethod("eibAid").getReturnType())
                    .isEqualTo(byte.class);
        }

        @Test
        @DisplayName("each member names its symbolic-map item verbatim, trailing I included")
        void eachMemberNamesItsItemVerbatim() {
            List<String> declared = new ArrayList<>();
            declared.add(AdminMenuRequest.TRN_NAME_FIELD);
            declared.add(AdminMenuRequest.TITLE01_FIELD);
            declared.add(AdminMenuRequest.CUR_DATE_FIELD);
            declared.add(AdminMenuRequest.PGM_NAME_FIELD);
            declared.add(AdminMenuRequest.TITLE02_FIELD);
            declared.add(AdminMenuRequest.CUR_TIME_FIELD);
            declared.addAll(AdminMenuRequest.OPTION_LINE_FIELDS);
            declared.add(AdminMenuRequest.OPTION_FIELD);
            declared.add(AdminMenuRequest.ERR_MSG_FIELD);

            assertThat(declared).containsExactlyElementsOf(EXPECTED_ITEM_NAMES);
            assertThat(declared).hasSize(AdminMenuRequest.MAPPED_FIELD_COUNT)
                    .allSatisfy(name -> assertThat(name).endsWith("I"));
        }

        @Test
        @DisplayName("twenty of the twenty-eight DFHMDF fields are named; the other eight are labels")
        void twentyOfTwentyEightDfhmdfFieldsAreNamed() {
            int namedFields = AdminMenuRequest.MAPPED_FIELD_COUNT;
            int unnamedLiteralLabels = 8;

            assertThat(namedFields).isEqualTo(20);
            assertThat(namedFields + unnamedLiteralLabels).isEqualTo(28);
            assertThat(recordComponentNames().subList(0, 20)).hasSize(namedFields);
        }

        @Test
        @DisplayName("the length item xxxL is absent: trnNameL is not a member")
        void theLengthItemIsAbsent() throws Exception {
            assertThat(recordComponentNames()).doesNotContain("trnNameL", "TRNNAMEL");
            assertThatExceptionOfType(NoSuchMethodException.class)
                    .isThrownBy(() -> AdminMenuRequest.class.getMethod("trnNameL"));
            assertThat(atDeclaredWidths("01", null).trnName())
                    .hasSize(AdminMenuRequest.TRN_NAME_LENGTH);
        }

        @Test
        @DisplayName("the flag byte xxxF is absent: optionF is not a member")
        void theFlagByteIsAbsent() throws Exception {
            assertThat(recordComponentNames()).doesNotContain("optionF", "OPTIONF");
            assertThatExceptionOfType(NoSuchMethodException.class)
                    .isThrownBy(() -> AdminMenuRequest.class.getMethod("optionF"));
        }

        @Test
        @DisplayName("the attribute view xxxA is absent: errMsgA is not a member")
        void theAttributeViewIsAbsent() throws Exception {
            assertThat(recordComponentNames()).doesNotContain("errMsgA", "ERRMSGA");
            assertThatExceptionOfType(NoSuchMethodException.class)
                    .isThrownBy(() -> AdminMenuRequest.class.getMethod("errMsgA"));
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

            assertThat(components).hasSize(22)
                    .allSatisfy(name -> assertThat(EXPECTED_MEMBER_NAMES.contains(name)
                            || EXPECTED_CARRIER_NAMES.contains(name))
                            .as("component %s is either a screen field or a carrier", name)
                            .isTrue());
        }
    }

    @Nested
    @DisplayName("Width arithmetic, written out by hand (gates G21 and B11)")
    class WidthArithmetic {
        @ParameterizedTest(name = "{1} is PIC X({2}) at COADM01.CPY:{3}")
        @CsvSource({"0,  TRNNAMEI,  4, 24",
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
            assertThat(declaredWidthOf(index)).as("%s (%s:%d) declared constant",
                    itemName,
                    "app/cpy-bms/COADM01.CPY",
                    copybookLine).isEqualTo(copybookWidth);
            assertThat(EXPECTED_WIDTHS.get(index)).as("%s transcribed width", itemName)
                    .isEqualTo(copybookWidth);
            assertThat(EXPECTED_ITEM_NAMES.get(index)).isEqualTo(itemName);

            assertThat(violatedProperties(withScreenField(index, "x".repeat(copybookWidth)))).isEmpty();
            assertThat(violatedProperties(withScreenField(index, "x".repeat(copybookWidth + 1))))
                    .containsExactly(EXPECTED_MEMBER_NAMES.get(index));
        }

        @Test
        @DisplayName("the eight width constants hold their copybook values")
        void theWidthConstantsHoldTheirCopybookValues() {
            assertThat(AdminMenuRequest.TRN_NAME_LENGTH).isEqualTo(4);
            assertThat(AdminMenuRequest.TITLE_LENGTH).isEqualTo(40);
            assertThat(AdminMenuRequest.CUR_DATE_LENGTH).isEqualTo(8);
            assertThat(AdminMenuRequest.PGM_NAME_LENGTH).isEqualTo(8);
            assertThat(AdminMenuRequest.CUR_TIME_LENGTH).isEqualTo(8);
            assertThat(AdminMenuRequest.OPTION_LINE_LENGTH).isEqualTo(40);
            assertThat(AdminMenuRequest.OPTION_LENGTH).isEqualTo(2);
            assertThat(AdminMenuRequest.ERR_MSG_LENGTH).isEqualTo(78);

            assertThat(AdminMenuRequest.ERR_MSG_LENGTH).isNotEqualTo(80);
        }

        @Test
        @DisplayName("the twenty widths sum to 668 by hand, and the subject agrees")
        void theTwentyWidthsSumToSixHundredSixtyEight() {
            int byHand = 4 + 40 + 8 + 8 + 40 + 8 + 12 * 40 + 2 + 78;

            assertThat(byHand).isEqualTo(668);
            assertThat(EXPECTED_WIDTHS).hasSize(20);
            assertThat(EXPECTED_WIDTHS.stream().mapToInt(Integer::intValue).sum()).isEqualTo(byHand);

            assertThat(AdminMenuRequest.PAYLOAD_DATA_LENGTH).isEqualTo(668).isEqualTo(byHand);
        }

        @Test
        @DisplayName("the whole symbolic map is 12 + 20x7 + 668 = 820 bytes")
        void theWholeSymbolicMapIsEightHundredTwentyBytes() {
            assertThat(AdminMenuRequest.METADATA_BYTES_PER_FIELD).isEqualTo(2 + 1 + 4).isEqualTo(7);

            assertThat(AdminMenuRequest.TIOAPFX_LENGTH).isEqualTo(12);

            int byHand = 12 + 20 * 7 + 668;

            assertThat(byHand).isEqualTo(820);
            assertThat(AdminMenuRequest.SYMBOLIC_MAP_LENGTH).isEqualTo(820).isEqualTo(byHand);

            assertThat(AdminMenuRequest.SYMBOLIC_MAP_LENGTH)
                    .isEqualTo(AdminMenuRequest.TIOAPFX_LENGTH
                            + AdminMenuRequest.MAPPED_FIELD_COUNT
                                    * AdminMenuRequest.METADATA_BYTES_PER_FIELD
                            + AdminMenuRequest.PAYLOAD_DATA_LENGTH);
        }

        @Test
        @DisplayName("composing the twenty fields really does produce 668 bytes, and omitting one fails it")
        void composingTheTwentyFieldsProducesSixHundredSixtyEightBytes() {
            FixedWidthCodec codec = new FixedWidthCodec(StandardCharsets.US_ASCII);
            AdminMenuRequest request = atDeclaredWidths("04", NavigationContext.empty());

            assertThat(codec.charset()).isEqualTo(StandardCharsets.US_ASCII);

            StringBuilder image = new StringBuilder();
            List<String> values = screenFieldsOf(request);
            for (int index = 0; index < values.size(); index++) {
                String value = values.get(index);
                image.append(codec.movePicX(value == null ? "" : value, declaredWidthOf(index)));
            }

            assertThat(image.length()).isEqualTo(AdminMenuRequest.PAYLOAD_DATA_LENGTH).isEqualTo(668);

            byte[] encoded = codec.encodeImage(image.toString(), "COADM1AI screen data");
            assertThat(encoded).hasSize(668);

            assertThat(image.length() - AdminMenuRequest.OPTION_LINE_LENGTH)
                    .as("omitting one forty-byte option line must not still total 668")
                    .isNotEqualTo(AdminMenuRequest.PAYLOAD_DATA_LENGTH);
        }

        @Test
        @DisplayName("an 820-byte record is what the map image would occupy end to end")
        void theSymbolicMapImageIsEightHundredTwentyBytesWide() {
            FixedWidthRecord image = new FixedWidthRecord(AdminMenuRequest.SYMBOLIC_MAP_LENGTH,
                    StandardCharsets.US_ASCII);

            assertThat(image.recordLength()).isEqualTo(820);
            assertThat(image.recordLength() - AdminMenuRequest.TIOAPFX_LENGTH
                    - AdminMenuRequest.MAPPED_FIELD_COUNT
                            * AdminMenuRequest.METADATA_BYTES_PER_FIELD)
                    .as("what remains after the prefix and the twenty strides is the screen data")
                    .isEqualTo(AdminMenuRequest.PAYLOAD_DATA_LENGTH);
        }
    }

    @Nested
    @DisplayName("Twelve option slots, of which the program can fill ten (practice B5)")
    class TwelveOptionSlots {
        @Test
        @DisplayName("all twelve slots exist, even the two the program can never write")
        void allTwelveSlotsExist() throws Exception {
            assertThat(AdminMenuRequest.OPTION_LINE_COUNT).isEqualTo(12);

            for (int slot = 1; slot <= AdminMenuRequest.OPTION_LINE_COUNT; slot++) {
                String member = String.format(Locale.ROOT, "optn%03d", slot);
                assertThat(AdminMenuRequest.class.getMethod(member).getReturnType())
                        .as("accessor %s() must exist", member)
                        .isEqualTo(String.class);
            }

            assertThat(recordComponentNames()).contains("optn010", "optn011", "optn012");
        }

        @ParameterizedTest(name = "optn{0} is PIC X(40)")
        @ValueSource(strings = {"001", "002", "003", "004", "005", "006", "007", "008", "009", "010",
                "011", "012"})
        @DisplayName("every slot is forty bytes wide, including the unreachable eleventh and twelfth")
        void everySlotIsFortyBytesWide(String slot) {
            int index = 5 + Integer.parseInt(slot);

            assertThat(AdminMenuRequest.OPTION_LINE_LENGTH).isEqualTo(40);
            assertThat(declaredWidthOf(index)).isEqualTo(40);
            assertThat(EXPECTED_WIDTHS.get(index)).isEqualTo(40);
            assertThat(EXPECTED_ITEM_NAMES.get(index)).isEqualTo("OPTN" + slot + "I");

            assertThat(2 + 2 + 35).isLessThan(AdminMenuRequest.OPTION_LINE_LENGTH);
            assertThat(violatedProperties(withScreenField(index, "x".repeat(40)))).isEmpty();
            assertThat(violatedProperties(withScreenField(index, "x".repeat(41))))
                    .containsExactly("optn" + slot);
        }

        @Test
        @DisplayName("the option-line view is twelve long and in map order, OPTN001I first")
        void theOptionLineViewIsInMapOrder() {
            AdminMenuRequest request = atDeclaredWidths("04", null);

            assertThat(request.optionLines()).hasSize(AdminMenuRequest.OPTION_LINE_COUNT)
                    .hasSameSizeAs(AdminMenuRequest.OPTION_LINE_FIELDS);
            assertThat(request.optionLines().subList(0, 4))
                    .containsExactly("01. User List (Security)",
                            "02. User Add (Security)",
                            "03. User Update (Security)",
                            "04. User Delete (Security)");
            assertThat(request.optionLines().get(0)).isEqualTo(request.optn001());
            assertThat(request.optionLines().get(11)).isEqualTo(request.optn012());
        }

        @Test
        @DisplayName("the view tolerates the unpopulated slots rather than rejecting them")
        void theViewToleratesUnpopulatedSlots() {
            AdminMenuRequest request = atDeclaredWidths("01", null);

            assertThat(request.optionLines()).hasSize(12).containsNull();
            assertThat(request.optn011()).isNull();
            assertThat(request.optn012()).isNull();
        }

        @Test
        @DisplayName("the view is unmodifiable, so a caller cannot reach back into the payload")
        void theViewIsUnmodifiable() {
            AdminMenuRequest request = atDeclaredWidths("01", null);
            List<String> lines = request.optionLines();

            assertThatThrownBy(() -> lines.set(0, "tampered"))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> lines.add("tampered"))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(lines::clear).isInstanceOf(UnsupportedOperationException.class);

            assertThat(request.optn001()).isEqualTo("01. User List (Security)");
        }

        @Test
        @DisplayName("the item-name constant list is immutable and parallel to the value view")
        void theItemNameListIsImmutableAndParallel() {
            AdminMenuRequest request = atDeclaredWidths("01", null);

            assertThat(AdminMenuRequest.OPTION_LINE_FIELDS)
                    .containsExactly("OPTN001I", "OPTN002I", "OPTN003I", "OPTN004I", "OPTN005I",
                            "OPTN006I", "OPTN007I", "OPTN008I", "OPTN009I", "OPTN010I", "OPTN011I",
                            "OPTN012I");
            assertThat(AdminMenuRequest.OPTION_LINE_FIELDS)
                    .hasSameSizeAs(request.optionLines());
            assertThatThrownBy(() -> AdminMenuRequest.OPTION_LINE_FIELDS.set(0, "tampered"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("OPTIONI is the only editable field, and it is carried raw")
    class OptionIsTheOnlyEditableField {
        @Test
        @DisplayName("option accepts exactly two characters and rejects three, naming itself")
        void optionAcceptsTwoCharactersAndRejectsThree() {
            assertThat(AdminMenuRequest.OPTION_LENGTH).isEqualTo(2);

            assertThat(violatedProperties(atDeclaredWidths("12", NavigationContext.empty()))).isEmpty();
            assertThat(violatedProperties(atDeclaredWidths("123", NavigationContext.empty())))
                    .containsExactly("option");
        }

        @Test
        @DisplayName("a request with every field at its declared width is entirely valid")
        void everyFieldAtItsDeclaredWidthIsValid() {
            assertThat(violatedProperties(atDeclaredWidths("04", NavigationContext.empty()))).isEmpty();
        }

        @Test
        @DisplayName("one character over the width is rejected, and only the offending field is named")
        void onlyTheOffendingFieldIsNamed() {
            AdminMenuRequest tooLongTitle = withScreenField(1,
                    "x".repeat(AdminMenuRequest.TITLE_LENGTH + 1));

            assertThat(violatedProperties(tooLongTitle)).containsExactly("title01");

            AdminMenuRequest tooLongSecondTitle = withScreenField(4,
                    "x".repeat(AdminMenuRequest.TITLE_LENGTH + 1));

            assertThat(violatedProperties(tooLongSecondTitle)).containsExactly("title02");
        }

        @ParameterizedTest(name = "option [{0}] is accepted, not rejected")
        @ValueSource(strings = {"", " ", "  ", "0", "01", "99", "ab", " 3", "3 ", "**"})
        @DisplayName("a blank or non-numeric option is accepted, because the program answers with a message")
        void blankOrNonNumericOptionIsAccepted(String option) {
            assertThat(violatedProperties(atDeclaredWidths(option, NavigationContext.empty()))).isEmpty();
        }

        @Test
        @DisplayName("an entirely absent payload is valid: no screen field is mandatory")
        void anEntirelyAbsentPayloadIsValid() {
            AdminMenuRequest empty = new AdminMenuRequest(null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                    (byte) 0x00);

            assertThat(violatedProperties(empty)).isEmpty();
        }

        @ParameterizedTest(name = "[{0}] is stored byte for byte")
        @ValueSource(strings = {"", " ", "  ", " 3", "3 ", "12", "0", "ab"})
        @DisplayName("no trimming, padding, right-justifying or zero-filling happens on the way in")
        void optionIsNeitherTrimmedNorNormalised(String option) {
            assertThat(atDeclaredWidths(option, null).option()).isEqualTo(option);
        }

        @Test
        @DisplayName("a leading space survives, and so does a trailing one")
        void bothSpacePatternsSurviveUntouched() {
            assertThat(atDeclaredWidths(" 3", null).option()).isEqualTo(" 3").hasSize(2);
            assertThat(atDeclaredWidths("3 ", null).option()).isEqualTo("3 ").hasSize(2);

            assertThat(atDeclaredWidths(" 3", null).option())
                    .isNotEqualTo(atDeclaredWidths("3 ", null).option())
                    .isNotEqualTo("03")
                    .isNotEqualTo("3");
        }

        @Test
        @DisplayName("an all-spaces option is preserved rather than collapsed to empty or null")
        void allSpacesSurvives() {
            AdminMenuRequest request = atDeclaredWidths("  ", null);

            assertThat(request.option()).isEqualTo("  ").hasSize(AdminMenuRequest.OPTION_LENGTH);
        }

        @Test
        @DisplayName("no screen field is normalised: all twenty are carried exactly as supplied")
        void noScreenFieldIsNormalised() {
            AdminMenuRequest request = new AdminMenuRequest(" CA0",
                    "  padded title  ",
                    " 8/08/26",
                    "COADM01 ",
                    "  another title ",
                    " 9:41:07",
                    "  01. leading and trailing  ",
                    null, null, null, null, null, null, null, null, null, null, null,
                    " 3",
                    "  message with spaces  ",
                    null,
                    (byte) 0x7D);

            assertThat(request.trnName()).isEqualTo(" CA0");
            assertThat(request.title01()).isEqualTo("  padded title  ");
            assertThat(request.curDate()).isEqualTo(" 8/08/26");
            assertThat(request.pgmName()).isEqualTo("COADM01 ");
            assertThat(request.title02()).isEqualTo("  another title ");
            assertThat(request.curTime()).isEqualTo(" 9:41:07");
            assertThat(request.optn001()).isEqualTo("  01. leading and trailing  ");
            assertThat(request.option()).isEqualTo(" 3");
            assertThat(request.errMsg()).isEqualTo("  message with spaces  ");
        }
    }

    @Nested
    @DisplayName("Conversation state travels in the payload (gates G37 and G53, rule R6)")
    class ConversationStateTravelsInThePayload {
        @Test
        @DisplayName("the communication area is a payload member, not a session lookup")
        void theCommunicationAreaIsAPayloadMember() throws Exception {
            NavigationContext context = NavigationContext.empty().withUserTypeAdmin();
            AdminMenuRequest request = atDeclaredWidths("01", context);

            assertThat(recordComponentNames()).contains("navigationContext");
            assertThat(AdminMenuRequest.class.getMethod("navigationContext").getReturnType())
                    .isEqualTo(NavigationContext.class);
            assertThat(request.navigationContext()).isSameAs(context);
            assertThat(request.isCommareaPresent()).isTrue();
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
            AdminMenuRequest request = atDeclaredWidths("01", NavigationContext.empty());

            assertThat(request.navigationContext().toFixedWidth(codec)).hasSize(160);
        }

        @Test
        @DisplayName("the last map and mapset are X(7), not X(8) - the width the total depends on")
        void theLastMapAndMapsetAreSevenBytes() {
            assertThat(NavigationContext.LAST_MAP_LENGTH).isEqualTo(7);
            assertThat(NavigationContext.LAST_MAPSET_LENGTH).isEqualTo(7);
            assertThat(AdminMenuRequest.MAP_NAME).hasSize(NavigationContext.LAST_MAP_LENGTH);

            FixedWidthRecord.FieldSpan lastMap =
                    NavigationContext.LAYOUT.span(NavigationContext.LAST_MAP_FIELD);
            FixedWidthRecord.FieldSpan lastMapset =
                    NavigationContext.LAYOUT.span(NavigationContext.LAST_MAPSET_FIELD);

            assertThat(lastMap.length()).isEqualTo(7);
            assertThat(lastMapset.length()).isEqualTo(7);
            assertThat(lastMapset.offset()).isEqualTo(lastMap.offset() + 7);

            assertThat(34 + 84 + 12 + 16 + (8 + 8)).isNotEqualTo(NavigationContext.COMMAREA_LENGTH);
        }

        @Test
        @DisplayName("the admin and user conditions are both reachable through the carried context")
        void bothUserTypeConditionsAreReachable() {
            AdminMenuRequest asAdmin =
                    atDeclaredWidths("01", NavigationContext.empty().withUserTypeAdmin());
            AdminMenuRequest asUser =
                    atDeclaredWidths("01", NavigationContext.empty().withUserTypeUser());

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
        }

        @Test
        @DisplayName("neither user-type condition holds for a type that is neither A nor U")
        void neitherUserTypeConditionHoldsForAnotherValue() {
            AdminMenuRequest beforeSignOn = atDeclaredWidths("01", NavigationContext.empty());

            assertThat(beforeSignOn.navigationContext().isAdmin()).isFalse();
            assertThat(beforeSignOn.navigationContext().isUser()).isFalse();
        }

        @Test
        @DisplayName("the enter and re-enter conditions are both reachable, and read through")
        void bothProgramContextConditionsAreReachable() {
            AdminMenuRequest onEnter = atDeclaredWidths("01", NavigationContext.empty().withPgmEnter());
            AdminMenuRequest onReenter =
                    atDeclaredWidths("01", NavigationContext.empty().withPgmReenter());

            assertThat(onEnter.isEnter()).isTrue();
            assertThat(onEnter.isReenter()).isFalse();
            assertThat(onReenter.isReenter()).isTrue();
            assertThat(onReenter.isEnter()).isFalse();

            assertThat(onEnter.isEnter()).isEqualTo(onEnter.navigationContext().isEnter());
            assertThat(onReenter.isReenter()).isEqualTo(onReenter.navigationContext().isReenter());
            assertThat(onEnter.navigationContext().pgmContext())
                    .isEqualTo(NavigationContext.PGM_CONTEXT_ENTER)
                    .isZero();
            assertThat(onReenter.navigationContext().pgmContext())
                    .isEqualTo(NavigationContext.PGM_CONTEXT_REENTER)
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("neither context view holds for a digit that is neither 0 nor 1")
        void neitherContextViewHoldsForAnUnusedDigit() {
            AdminMenuRequest request =
                    atDeclaredWidths("01", NavigationContext.empty().withPgmContext(9));

            assertThat(request.isEnter()).isFalse();
            assertThat(request.isReenter()).isFalse();
            assertThat(request.isCommareaPresent()).isTrue();
        }

        @ParameterizedTest(name = "EIBAID 0x{0} survives unresolved")
        @CsvSource({"7D", "F3", "F1", "6D", "00"})
        @DisplayName("the attention identifier byte is carried raw and unresolved")
        void theAttentionIdentifierIsCarriedRaw(String hex) {
            byte aid = (byte) Integer.parseInt(hex, 16);
            AdminMenuRequest request = withScreenField(18, "01");

            assertThat(request.eibAid()).isEqualTo((byte) 0x7D);
            assertThat(atDeclaredWidths("01", null).eibAid()).isEqualTo((byte) 0x7D);

            AdminMenuRequest withAid = new AdminMenuRequest(null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, "01", null, null,
                    aid);

            assertThat(withAid.eibAid()).isEqualTo(aid);
        }

        @Test
        @DisplayName("an absent communication area encodes EIBCALEN = 0")
        void anAbsentCommunicationAreaEncodesEibcalenZero() {
            AdminMenuRequest request = atDeclaredWidths("01", null);

            assertThat(request.navigationContext()).isNull();
            assertThat(request.isCommareaPresent()).isFalse();
        }

        @Test
        @DisplayName("a present communication area clears the marker - the ELSE branch at :85")
        void aPresentCommunicationAreaClearsTheMarker() {
            AdminMenuRequest request = atDeclaredWidths("01", NavigationContext.empty());

            assertThat(request.navigationContext()).isNotNull();
            assertThat(request.isCommareaPresent()).isTrue();
        }

        @Test
        @DisplayName("neither context view holds without a communication area to consult")
        void neitherContextViewHoldsWithoutACommunicationArea() {
            AdminMenuRequest request = atDeclaredWidths("01", null);

            assertThat(request.isEnter()).isFalse();
            assertThat(request.isReenter()).isFalse();
            assertThat(request.isCommareaPresent()).isFalse();
        }

        @Test
        @DisplayName("there is no server-side session state of any kind")
        void thereIsNoServerSideSessionState() {
            List<Field> declaredState = Arrays.stream(AdminMenuRequest.class.getDeclaredFields())
                    .filter(field -> !field.isSynthetic())
                    .collect(Collectors.toList());

            assertThat(declaredState).isNotEmpty();
            for (Field field : declaredState) {
                if (Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("static field %s must be final - no static mutable state", field.getName())
                            .isTrue();
                } else {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("instance field %s must be final", field.getName())
                            .isTrue();
                    assertThat(Modifier.isPrivate(field.getModifiers()))
                            .as("instance field %s must be private", field.getName())
                            .isTrue();
                }
            }

            assertThat(AdminMenuRequest.class.isRecord()).isTrue();
        }

        @Test
        @DisplayName("no servlet, session or thread-local type appears anywhere in the payload")
        void noServletOrSessionTypeAppearsInThePayload() {
            Set<String> componentTypes = Arrays.stream(AdminMenuRequest.class.getRecordComponents())
                    .map(RecordComponent::getType)
                    .map(Class::getName)
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            assertThat(componentTypes).containsExactlyInAnyOrder("java.lang.String",
                    "byte",
                    NavigationContext.class.getName());
            assertThat(componentTypes).allSatisfy(type -> assertThat(type)
                    .doesNotContain("jakarta.servlet")
                    .doesNotContain("HttpSession")
                    .doesNotContain("HttpServletRequest")
                    .doesNotContain("ThreadLocal"));
        }
    }

    @Nested
    @DisplayName("Screen identity, from the CSD and the program's own WORKING-STORAGE")
    class ScreenIdentity {
        @Test
        @DisplayName("the transaction is CA00 and fills X(4) exactly")
        void theTransactionIsCa00() {
            assertThat(AdminMenuRequest.TRANSACTION_ID).isEqualTo("CA00");

            assertThat(AdminMenuRequest.TRANSACTION_ID).hasSize(4)
                    .hasSize(AdminMenuRequest.TRN_NAME_LENGTH)
                    .doesNotContain(" ");
        }

        @Test
        @DisplayName("the program is COADM01C and fills X(8) exactly")
        void theProgramIsCoadm01c() {
            assertThat(AdminMenuRequest.PROGRAM_NAME).isEqualTo("COADM01C");

            assertThat(AdminMenuRequest.PROGRAM_NAME).hasSize(8)
                    .hasSize(AdminMenuRequest.PGM_NAME_LENGTH)
                    .doesNotContain(" ");
        }

        @Test
        @DisplayName("the mapset is COADM01 and the map is COADM1A")
        void theMapsetAndMapAreNamedAsTheCsdNamesThem() {
            assertThat(AdminMenuRequest.MAPSET_NAME).isEqualTo("COADM01").hasSize(7);
            assertThat(AdminMenuRequest.MAP_NAME).isEqualTo("COADM1A").hasSize(7);

            assertThat(AdminMenuRequest.MAP_NAME + "I").isEqualTo("COADM1AI").hasSize(8);
            assertThat(AdminMenuRequest.MAP_NAME + "O").isEqualTo("COADM1AO").hasSize(8);
        }

        @Test
        @DisplayName("the identity constants are what a well-formed request actually carries")
        void theIdentityConstantsAreCarriedByAWellFormedRequest() {
            AdminMenuRequest request = atDeclaredWidths("01", NavigationContext.empty());

            assertThat(request.trnName()).isEqualTo(AdminMenuRequest.TRANSACTION_ID);
            assertThat(request.pgmName()).isEqualTo(AdminMenuRequest.PROGRAM_NAME);
            assertThat(violatedProperties(request)).isEmpty();
        }
    }

    @Nested
    @DisplayName("The duplication with the main menu is documented, not removed (practice B4)")
    class DuplicationIsDocumentedNotRemoved {
        @Test
        @DisplayName("AdminMenuRequest and MainMenuRequest are distinct Java types, deliberately")
        void adminAndMainMenuRequestsAreDistinctTypes() {
            assertThat(AdminMenuRequest.class).isNotEqualTo(MainMenuRequest.class);
            assertThat(AdminMenuRequest.class.getName()).isNotEqualTo(MainMenuRequest.class.getName());

            assertThat(MainMenuRequest.class.isAssignableFrom(AdminMenuRequest.class)).isFalse();
            assertThat(AdminMenuRequest.class.isAssignableFrom(MainMenuRequest.class)).isFalse();

            assertThat(AdminMenuRequest.class.getSuperclass()).isEqualTo(Record.class);
            assertThat(MainMenuRequest.class.getSuperclass()).isEqualTo(Record.class);
            assertThat(AdminMenuRequest.class.getInterfaces()).isEmpty();
        }

        @Test
        @DisplayName("the coincidence itself is asserted, so a future divergence is visible here")
        void theCoincidenceIsAssertedRatherThanAssumed() {
            assertThat(MainMenuRequest.MAP_FIELD_COUNT).isEqualTo(AdminMenuRequest.MAPPED_FIELD_COUNT);
            assertThat(MainMenuRequest.SYMBOLIC_MAP_PAYLOAD_LENGTH)
                    .isEqualTo(AdminMenuRequest.PAYLOAD_DATA_LENGTH);
            assertThat(MainMenuRequest.SYMBOLIC_MAP_LENGTH)
                    .isEqualTo(AdminMenuRequest.SYMBOLIC_MAP_LENGTH);
            assertThat(MainMenuRequest.OPTION_LINE_COUNT)
                    .isEqualTo(AdminMenuRequest.OPTION_LINE_COUNT);

            assertThat(MainMenuRequest.SCREEN_FIELD_COUNT).isEqualTo(28);
            assertThat(MainMenuRequest.SCREEN_FIELD_COUNT - MainMenuRequest.MAP_FIELD_COUNT)
                    .as("28 DFHMDF fields less the 20 that carry a name leaves 8 literal labels")
                    .isEqualTo(8);

            assertThat(MainMenuRequest.TRANSACTION_ID).isNotEqualTo(AdminMenuRequest.TRANSACTION_ID);
            assertThat(MainMenuRequest.PROGRAM_NAME).isNotEqualTo(AdminMenuRequest.PROGRAM_NAME);
            assertThat(MainMenuRequest.MAPSET_NAME).isNotEqualTo(AdminMenuRequest.MAPSET_NAME);
        }
    }

    @Nested
    @DisplayName("The wire carries the twenty screen fields, the area and the AID - nothing else")
    class JsonWireFormat {
        @Test
        @DisplayName("no symbolic-map metadata item reaches the wire under any capitalisation")
        void noMetadataItemReachesTheWire() throws Exception {
            String json = new ObjectMapper()
                    .writeValueAsString(atDeclaredWidths("01", NavigationContext.empty()));

            for (String item : EXPECTED_ITEM_NAMES) {
                String base = item.substring(0, item.length() - 1);
                for (String suffix : List.of("L", "F", "A", "C", "P", "H", "V")) {
                    assertThat(json).doesNotContain("\"" + base + suffix + "\"")
                            .doesNotContain("\"" + base.toLowerCase(Locale.ROOT) + suffix + "\"");
                }
            }

            for (String member : EXPECTED_WIRE_NAMES) {
                assertThat(json).as("payload member %s must reach the wire", member)
                        .contains("\"" + member + "\"");
            }
            assertThat(json).contains("\"navigationContext\"").contains("\"eibAid\"");
        }

        @Test
        @DisplayName("no derived view reaches the wire either")
        void noDerivedViewReachesTheWire() throws Exception {
            String json = new ObjectMapper().writeValueAsString(
                    atDeclaredWidths("01", NavigationContext.empty().withPgmReenter()));

            assertThat(json).doesNotContain("optionLines")
                    .doesNotContain("commareaPresent")
                    .doesNotContain("\"enter\"")
                    .doesNotContain("\"reenter\"");
        }

        @Test
        @DisplayName("all twenty screen fields survive a round trip untouched")
        void allTwentyScreenFieldsSurviveARoundTrip() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            AdminMenuRequest original =
                    atDeclaredWidths("  ", NavigationContext.empty().withPgmReenter());

            AdminMenuRequest roundTripped =
                    mapper.readValue(mapper.writeValueAsString(original), AdminMenuRequest.class);

            assertThat(roundTripped).isEqualTo(original);
            assertThat(screenFieldsOf(roundTripped))
                    .containsExactlyElementsOf(screenFieldsOf(original));

            assertThat(roundTripped.option()).isEqualTo("  ");
            assertThat(roundTripped.errMsg()).hasSize(AdminMenuRequest.ERR_MSG_LENGTH);
            assertThat(roundTripped.navigationContext()).isEqualTo(original.navigationContext());
            assertThat(roundTripped.eibAid()).isEqualTo(original.eibAid());
            assertThat(roundTripped.isReenter()).isTrue();
        }

        @Test
        @DisplayName("an empty option round trips as empty, not as null")
        void anEmptyOptionRoundTripsAsEmpty() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            AdminMenuRequest original = atDeclaredWidths("", null);

            AdminMenuRequest roundTripped =
                    mapper.readValue(mapper.writeValueAsString(original), AdminMenuRequest.class);

            assertThat(roundTripped.option()).isEmpty();
            assertThat(roundTripped.isCommareaPresent()).isFalse();
        }

        @Test
        @DisplayName("a body naming only the option binds, leaving every other field absent")
        void aSparseBodyBinds() throws Exception {
            AdminMenuRequest bound = new ObjectMapper()
                    .readValue("{\"option\":\"04\"}", AdminMenuRequest.class);

            assertThat(bound.option()).isEqualTo("04");
            assertThat(bound.trnName()).isNull();
            assertThat(bound.isCommareaPresent()).isFalse();
            assertThat(bound.isEnter()).isFalse();
            assertThat(bound.isReenter()).isFalse();
            assertThat(bound.optionLines()).hasSize(AdminMenuRequest.OPTION_LINE_COUNT)
                    .containsOnlyNulls();
            assertThat(violatedProperties(bound)).isEmpty();
        }
    }
}

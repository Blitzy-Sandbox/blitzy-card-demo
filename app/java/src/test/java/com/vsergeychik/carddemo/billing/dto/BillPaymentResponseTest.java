package com.vsergeychik.carddemo.billing.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.vsergeychik.carddemo.billing.dto.BillPaymentResponse.CursorField;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ScreenFieldImage;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Unit tests for {@link BillPaymentResponse}, the outbound payload of the CardDemo bill-payment screen and
 * a field-for-field projection of the {@code xxxO} items of {@code 01 COBIL0AO REDEFINES COBIL0AI}.
 */
@DisplayName("BillPaymentResponse - the COBIL00 symbolic-map output projection (POST /api/billpay, CB00)")
class BillPaymentResponseTest {
    private static final Map<String, Integer> MAP_MEMBER_WIDTHS = buildMapMemberWidths();

    private static final Map<String, String> WIDTH_CONSTANT_NAMES = buildWidthConstantNames();

    private static final Map<String, String> CANONICAL_MAP_VALUES = buildCanonicalMapValues();

    private static final List<String> SYMBOLIC_MAP_ITEM_STEMS = List.of("TRNNAME",
            "TITLE01",
            "CURDATE",
            "PGMNAME",
            "TITLE02",
            "CURTIME",
            "ACTIDIN",
            "CURBAL",
            "CONFIRM",
            "ERRMSG");

    private static final List<String> METADATA_SUFFIXES =
            List.of("L", "F", "A", "C", "P", "H", "V");

    private static final Set<String> NOT_PUBLISHED = Set.of("cursorField", "messageHighlight");

    private static final List<String> WIRE_MEMBERS = List.of("trnName",
            "title01",
            "curDate",
            "pgmName",
            "title02",
            "curTime",
            "actIdIn",
            "curBal",
            "confirm",
            "errMsg",
            "cursorField",
            "messageHighlight",
            "navigationContext",
            "nextProgram",
            "nextMapset",
            "nextMap",
            "trnIdFirst",
            "trnIdLast",
            "pageNum",
            "nextPageFlg",
            "trnSelFlg",
            "trnSelected");

    private static final java.util.Set<String> SCREEN_FIELD_MEMBERS = java.util.Set.of(
            "trnName", "title01", "curDate", "pgmName", "title02", "curTime", "actIdIn", "curBal",
            "confirm", "errMsg");

    private static String wireNameOf(String member) {
        return SCREEN_FIELD_MEMBERS.contains(member)
                ? member.toLowerCase(java.util.Locale.ROOT) : member;
    }

    private static List<String> wireNamesOf(java.util.Collection<String> members) {
        return members.stream().map(BillPaymentResponseTest::wireNameOf).toList();
    }

    private static final Map<String, Integer> CB00_INFO_WIDTHS = buildCb00InfoWidths();

    private static final List<String> UNREFERENCED_CB00_MEMBERS =
            List.of("trnIdFirst", "trnIdLast", "pageNum", "nextPageFlg", "trnSelFlg");

    private static final String MESSAGE_HIGHLIGHT_GREEN =
            String.valueOf((char) BmsAttributes.unsigned(BmsAttributes.DFHGREEN));

    private static final char MUTATION_CHARACTER = 'Z';

    private final ObjectMapper mapper = new ObjectMapper();

    private static Map<String, Integer> buildMapMemberWidths() {
        Map<String, Integer> widths = new LinkedHashMap<>();
        widths.put("trnName", BillPaymentResponse.TRN_NAME_LENGTH);
        widths.put("title01", BillPaymentResponse.TITLE01_LENGTH);
        widths.put("curDate", BillPaymentResponse.CUR_DATE_LENGTH);
        widths.put("pgmName", BillPaymentResponse.PGM_NAME_LENGTH);
        widths.put("title02", BillPaymentResponse.TITLE02_LENGTH);
        widths.put("curTime", BillPaymentResponse.CUR_TIME_LENGTH);
        widths.put("actIdIn", BillPaymentResponse.ACT_ID_IN_LENGTH);
        widths.put("curBal", BillPaymentResponse.CUR_BAL_LENGTH);
        widths.put("confirm", BillPaymentResponse.CONFIRM_LENGTH);
        widths.put("errMsg", BillPaymentResponse.ERR_MSG_LENGTH);
        return Collections.unmodifiableMap(widths);
    }

    private static Map<String, String> buildWidthConstantNames() {
        Map<String, String> constants = new LinkedHashMap<>();
        constants.put("trnName", "TRN_NAME_LENGTH");
        constants.put("title01", "TITLE01_LENGTH");
        constants.put("curDate", "CUR_DATE_LENGTH");
        constants.put("pgmName", "PGM_NAME_LENGTH");
        constants.put("title02", "TITLE02_LENGTH");
        constants.put("curTime", "CUR_TIME_LENGTH");
        constants.put("actIdIn", "ACT_ID_IN_LENGTH");
        constants.put("curBal", "CUR_BAL_LENGTH");
        constants.put("confirm", "CONFIRM_LENGTH");
        constants.put("errMsg", "ERR_MSG_LENGTH");
        return Collections.unmodifiableMap(constants);
    }

    private static Map<String, Integer> buildCb00InfoWidths() {
        Map<String, Integer> widths = new LinkedHashMap<>();
        widths.put("trnIdFirst", BillPaymentResponse.TRN_ID_FIRST_LENGTH);
        widths.put("trnIdLast", BillPaymentResponse.TRN_ID_LAST_LENGTH);
        widths.put("pageNum", BillPaymentResponse.PAGE_NUM_DIGITS);
        widths.put("nextPageFlg", BillPaymentResponse.NEXT_PAGE_FLG_LENGTH);
        widths.put("trnSelFlg", BillPaymentResponse.TRN_SEL_FLG_LENGTH);
        widths.put("trnSelected", BillPaymentResponse.TRN_SELECTED_LENGTH);
        return Collections.unmodifiableMap(widths);
    }

    private static Map<String, String> buildCanonicalMapValues() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("trnName", BillPaymentResponse.TRANSACTION_ID);
        values.put("title01", atWidth("AWS Mainframe Modernization",
                BillPaymentResponse.TITLE01_LENGTH));
        values.put("curDate", "07/19/22");
        values.put("pgmName", BillPaymentResponse.PROGRAM_NAME);
        values.put("title02", atWidth("CardDemo", BillPaymentResponse.TITLE02_LENGTH));
        values.put("curTime", "23:15:57");
        values.put("actIdIn", "00000000011");
        values.put("curBal", "+0000000019.40");
        values.put("confirm", "Y");
        values.put("errMsg", atWidth("Payment successful.  Your Transaction ID is 0000000000000042.",
                BillPaymentResponse.ERR_MSG_LENGTH));
        return Collections.unmodifiableMap(values);
    }

    private static String atWidth(String text, int width) {
        return text + " ".repeat(width - text.length());
    }

    private static String repeat(char character, int length) {
        return String.valueOf(character).repeat(length);
    }

    private static String capitalise(String member) {
        return Character.toUpperCase(member.charAt(0)) + member.substring(1);
    }

    private static void set(BillPaymentResponse response, String member, String value)
            throws ReflectiveOperationException {
        BillPaymentResponse.class.getMethod("set" + capitalise(member), String.class)
                .invoke(response, value);
    }

    private static String get(BillPaymentResponse response, String member)
            throws ReflectiveOperationException {
        return (String) BillPaymentResponse.class.getMethod("get" + capitalise(member))
                .invoke(response);
    }

    private static void setOnRequest(BillPaymentRequest request, String member, String value)
            throws ReflectiveOperationException {
        BillPaymentRequest.class.getMethod("set" + capitalise(member), String.class)
                .invoke(request, value);
    }

    private static int constant(Class<?> type, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        return field.getInt(null);
    }

    private static BillPaymentResponse canonical() throws ReflectiveOperationException {
        BillPaymentResponse response = new BillPaymentResponse();
        for (Map.Entry<String, String> value : CANONICAL_MAP_VALUES.entrySet()) {
            set(response, value.getKey(), value.getValue());
        }
        response.setCursorField(CursorField.ACTIDIN);
        response.setMessageHighlight(MESSAGE_HIGHLIGHT_GREEN);
        response.setNavigationContext(NavigationContext.empty()
                .withFromTranid(BillPaymentResponse.TRANSACTION_ID)
                .withFromProgram(BillPaymentResponse.PROGRAM_NAME)
                .withUserId("USER0001")
                .withUserTypeUser()
                .withPgmReenter()
                .withLastMap(BillPaymentResponse.MAP_NAME)
                .withLastMapset(BillPaymentResponse.MAPSET_NAME));
        response.setNextProgram(BillPaymentResponse.MAIN_MENU_PROGRAM);
        response.setNextMapset(BillPaymentResponse.MAPSET_NAME);
        response.setNextMap(BillPaymentResponse.MAP_NAME);
        response.setTrnIdFirst("0000000000000001");
        response.setTrnIdLast("0000000000000010");
        response.setPageNum(3);
        response.setNextPageFlg(BillPaymentResponse.NEXT_PAGE_YES);
        response.setTrnSelFlg("S");
        response.setTrnSelected("0000000000000007");
        return response;
    }

    private static BillPaymentRequest requestWithTheSameTenValues()
            throws ReflectiveOperationException {
        BillPaymentRequest request = new BillPaymentRequest();
        for (Map.Entry<String, String> value : CANONICAL_MAP_VALUES.entrySet()) {
            setOnRequest(request, value.getKey(), value.getValue());
        }
        return request;
    }

    private static Stream<Arguments> mapMemberWidths() {
        return MAP_MEMBER_WIDTHS.entrySet().stream()
                .map(entry -> Arguments.of(entry.getKey(), entry.getValue()));
    }

    private static Stream<Arguments> cb00InfoWidths() {
        return CB00_INFO_WIDTHS.entrySet().stream()
                .map(entry -> Arguments.of(entry.getKey(), entry.getValue()));
    }

    private static Stream<Arguments> neverCursoredStems() {
        return SYMBOLIC_MAP_ITEM_STEMS.stream()
                .filter(stem -> !"ACTIDIN".equals(stem) && !"CONFIRM".equals(stem))
                .map(Arguments::of);
    }

    private static Stream<Arguments> reachableNextPrograms() {
        return Stream.of(
                Arguments.of(BillPaymentResponse.SIGN_ON_PROGRAM,
                        "COBIL00C:107-109 EIBCALEN = 0, and :275-276 the LOW-VALUES fallback"),
                Arguments.of(BillPaymentResponse.MAIN_MENU_PROGRAM,
                        "COBIL00C:129-130 DFHPF3 with no caller to return to"),
                Arguments.of("COADM01C",
                        "COBIL00C:131-133 the echoed CDEMO-FROM-PROGRAM"),
                Arguments.of("COCRDLIC",
                        "COBIL00C:282 the echoed CDEMO-TO-PROGRAM"));
    }

    private static Stream<Arguments> editedBalances() {
        return Stream.of(Arguments.of("+0000000019.40"),
                Arguments.of("-0000000019.40"),
                Arguments.of("+0000000000.00"));
    }

    private Set<String> publishedNames(BillPaymentResponse response) {
        JsonNode body = mapper.valueToTree(response);
        Set<String> names = new LinkedHashSet<>();
        body.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static List<String> instanceFieldNames() {
        List<String> names = new ArrayList<>();
        for (Field field : BillPaymentResponse.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                names.add(field.getName());
            }
        }
        return names;
    }

    @Nested
    @DisplayName("1. The ten xxxO payload members, and the redefinition identity with the request")
    class PayloadMembers {
        @ParameterizedTest(name = "{0} is declared String, width {1}")
        @MethodSource(
                "com.vsergeychik.carddemo.billing.dto.BillPaymentResponseTest#mapMemberWidths")
        @DisplayName("every map member is a String - the mapset declares no PICIN and no PICOUT")
        void everyMapMemberIsAString(String member, int width) throws Exception {
            Field field = BillPaymentResponse.class.getDeclaredField(member);

            assertThat(field.getType())
                    .as("%s projects a PIC X(%d) item and must be a String", member, width)
                    .isEqualTo(String.class);
            assertThat(width).isPositive();
        }

        @ParameterizedTest(name = "{0} is bounded by the constant declaring {1}")
        @MethodSource(
                "com.vsergeychik.carddemo.billing.dto.BillPaymentResponseTest#mapMemberWidths")
        @DisplayName("each width is published as a public static final int naming its copybook item")
        void eachWidthIsPublishedAsAConstant(String member, int width) throws Exception {
            String constantName = WIDTH_CONSTANT_NAMES.get(member);
            assertThat(constantName).as("every member has a hand-written constant name").isNotNull();

            Field constant = BillPaymentResponse.class.getDeclaredField(constantName);

            assertThat(constant.getType()).isEqualTo(int.class);
            assertThat(Modifier.isPublic(constant.getModifiers())).isTrue();
            assertThat(Modifier.isStatic(constant.getModifiers())).isTrue();
            assertThat(Modifier.isFinal(constant.getModifiers())).isTrue();
            assertThat(constant.getInt(null))
                    .as("%s must declare the copybook width of its xxxO item", constantName)
                    .isEqualTo(width);
        }

        @ParameterizedTest(name = "{0} carries a value of exactly {1} characters unaltered")
        @MethodSource(
                "com.vsergeychik.carddemo.billing.dto.BillPaymentResponseTest#mapMemberWidths")
        @DisplayName("a value exactly at the declared width is carried unaltered")
        void aValueAtTheDeclaredWidthIsCarriedUnaltered(String member, int width) throws Exception {
            BillPaymentResponse response = new BillPaymentResponse();
            String value = CANONICAL_MAP_VALUES.get(member);

            set(response, member, value);

            assertThat(value).as("the canonical value for %s is width-exact", member).hasSize(width);
            assertThat(get(response, member)).isEqualTo(value).hasSize(width);
        }

        @Test
        @DisplayName("the ten canonical values are all distinct, so a mutation is unambiguous")
        void theTenCanonicalValuesAreDistinct() {
            assertThat(CANONICAL_MAP_VALUES).hasSize(BillPaymentResponse.MAP_FIELD_COUNT);
            assertThat(CANONICAL_MAP_VALUES.values()).doesNotHaveDuplicates();
            assertThat(CANONICAL_MAP_VALUES.keySet())
                    .containsExactlyElementsOf(MAP_MEMBER_WIDTHS.keySet());
        }

        @Test
        @DisplayName("the ten widths sum to 212, the data length of 01 COBIL0AO")
        void theTenWidthsSumToTheDataLength() {
            int sum = MAP_MEMBER_WIDTHS.values().stream().mapToInt(Integer::intValue).sum();

            assertThat(sum).isEqualTo(212);
            assertThat(BillPaymentResponse.MAP_DATA_LENGTH).isEqualTo(sum);
        }

        @Test
        @DisplayName("the symbolic map is 294 bytes: 12 prefix + 10 x 7 prologue + 212 data")
        void theSymbolicMapIsTwoHundredAndNinetyFourBytes() {
            assertThat(BillPaymentResponse.TIOAPFX_PREFIX_LENGTH).isEqualTo(12);
            assertThat(BillPaymentResponse.FIELD_PROLOGUE_LENGTH).isEqualTo(7);
            assertThat(BillPaymentResponse.MAP_FIELD_COUNT).isEqualTo(10);
            assertThat(BillPaymentResponse.SYMBOLIC_MAP_LENGTH)
                    .isEqualTo(12 + (10 * 7) + 212)
                    .isEqualTo(294);
        }

        @Test
        @DisplayName("there are exactly ten map members - ten stems, ten widths, ten constants")
        void thereAreExactlyTenMapMembers() {
            assertThat(MAP_MEMBER_WIDTHS).hasSize(BillPaymentResponse.MAP_FIELD_COUNT);
            assertThat(WIDTH_CONSTANT_NAMES).hasSize(BillPaymentResponse.MAP_FIELD_COUNT);
            assertThat(SYMBOLIC_MAP_ITEM_STEMS).hasSize(BillPaymentResponse.MAP_FIELD_COUNT);
        }

        @Test
        @DisplayName("no member is named after a symbolic-map metadata item (COBIL00.CPY:81-85)")
        void noMemberIsNamedAfterAMetadataItem() {
            Set<String> declared = new LinkedHashSet<>();
            for (String name : instanceFieldNames()) {
                declared.add(name.toLowerCase(Locale.ROOT));
            }

            List<String> named = new ArrayList<>();
            for (String stem : SYMBOLIC_MAP_ITEM_STEMS) {
                for (String suffix : METADATA_SUFFIXES) {
                    if (declared.contains((stem + suffix).toLowerCase(Locale.ROOT))) {
                        named.add(stem + suffix);
                    }
                }
            }

            assertThat(named)
                    .as("a member named after an xxxL, xxxF, xxxA, xxxC, xxxP, xxxH or xxxV item "
                            + "would put presentation metadata on the wire (gate G9)")
                    .isEmpty();
        }

        @Test
        @DisplayName("the twenty-two declared members are exactly the wire members, in order")
        void theDeclaredMembersAreExactlyTheWireMembers() {
            assertThat(instanceFieldNames()).containsExactlyElementsOf(WIRE_MEMBERS);
            assertThat(WIRE_MEMBERS).hasSize(22);
            assertThat(NOT_PUBLISHED).hasSize(2).allMatch(WIRE_MEMBERS::contains);
        }

        @Test
        @DisplayName("request and response declare the same ten payload names (COBIL00.CPY:79)")
        void requestAndResponseDeclareTheSameTenPayloadNames() {
            Set<String> responseMembers = new LinkedHashSet<>(MAP_MEMBER_WIDTHS.keySet());
            Set<String> requestMembers = new LinkedHashSet<>();
            for (Field field : BillPaymentRequest.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())
                        && responseMembers.contains(field.getName())) {
                    requestMembers.add(field.getName());
                }
            }

            assertThat(requestMembers)
                    .as("the output view redefines the input view, so both carry the same ten names")
                    .containsExactlyInAnyOrderElementsOf(responseMembers);
        }

        @ParameterizedTest(name = "{0} is width {1} and type String on both request and response")
        @MethodSource(
                "com.vsergeychik.carddemo.billing.dto.BillPaymentResponseTest#mapMemberWidths")
        @DisplayName("every payload width and type is identical on request and response")
        void everyPayloadWidthAndTypeIsIdenticalOnBothViews(String member, int width)
                throws Exception {
            String constantName = WIDTH_CONSTANT_NAMES.get(member);

            assertThat(constant(BillPaymentResponse.class, constantName)).isEqualTo(width);
            assertThat(constant(BillPaymentRequest.class, constantName))
                    .as("%s must declare the same width on both views", constantName)
                    .isEqualTo(width);
            assertThat(BillPaymentRequest.class.getDeclaredField(member).getType())
                    .isEqualTo(String.class);
            assertThat(BillPaymentResponse.class.getDeclaredField(member).getType())
                    .isEqualTo(String.class);
        }

        @Test
        @DisplayName("both views agree on the geometry, which is why COBIL00C:194 is legal")
        void bothViewsAgreeOnTheGeometry() throws Exception {
            assertThat(constant(BillPaymentRequest.class, "SYMBOLIC_MAP_LENGTH"))
                    .isEqualTo(BillPaymentResponse.SYMBOLIC_MAP_LENGTH);
            assertThat(constant(BillPaymentRequest.class, "MAP_DATA_LENGTH"))
                    .isEqualTo(BillPaymentResponse.MAP_DATA_LENGTH);
            assertThat(constant(BillPaymentRequest.class, "TIOAPFX_PREFIX_LENGTH"))
                    .isEqualTo(BillPaymentResponse.TIOAPFX_PREFIX_LENGTH);
            assertThat(constant(BillPaymentRequest.class, "FIELD_PROLOGUE_LENGTH"))
                    .isEqualTo(BillPaymentResponse.FIELD_PROLOGUE_LENGTH);
            assertThat(constant(BillPaymentRequest.class, "MAP_FIELD_COUNT"))
                    .isEqualTo(BillPaymentResponse.MAP_FIELD_COUNT);
        }

        @Test
        @DisplayName("the screen identity constants are the CSD and mapset values")
        void theScreenIdentityConstantsAreTheCsdValues() {
            assertThat(BillPaymentResponse.MAPSET_NAME).isEqualTo("COBIL00");
            assertThat(BillPaymentResponse.MAP_NAME).isEqualTo("COBIL0A");
            assertThat(BillPaymentResponse.PROGRAM_NAME).isEqualTo("COBIL00C");
            assertThat(BillPaymentResponse.TRANSACTION_ID).isEqualTo("CB00");
            assertThat(BillPaymentResponse.TRANSACTION_ID)
                    .as("WS-TRANID PIC X(04) at COBIL00C:38 - and the width of TRNNAMEO")
                    .hasSize(BillPaymentResponse.TRN_NAME_LENGTH);
            assertThat(BillPaymentResponse.PROGRAM_NAME)
                    .as("WS-PGMNAME PIC X(08) at COBIL00C:37 - and the width of PGMNAMEO")
                    .hasSize(BillPaymentResponse.PGM_NAME_LENGTH);
        }
    }

    @Nested
    @DisplayName("2. The wire format - twenty-two members, no metadata, and strict binding")
    class WireFormat {
        @Test
        @DisplayName("the serialised body carries exactly the twenty published members and nothing else")
        void theBodyCarriesExactlyTheTwentyMembers() throws Exception {
            Set<String> published = publishedNames(canonical());

            List<String> publishedMembers = WIRE_MEMBERS.stream()
                    .filter(member -> !NOT_PUBLISHED.contains(member))
                    .toList();
            assertThat(published).containsExactlyInAnyOrderElementsOf(wireNamesOf(publishedMembers));
            assertThat(published).hasSize(20);
            assertThat(published).doesNotContain("cursorField", "messageHighlight");
        }

        @Test
        @DisplayName("no symbolic-map metadata item is published - all 7 suffixes x 10 fields (G9)")
        void noSymbolicMapMetadataItemIsPublished() throws Exception {
            Set<String> publishedLowerCase = new LinkedHashSet<>();
            for (String name : publishedNames(canonical())) {
                publishedLowerCase.add(name.toLowerCase(Locale.ROOT));
            }

            List<String> leaked = new ArrayList<>();
            for (String stem : SYMBOLIC_MAP_ITEM_STEMS) {
                for (String suffix : METADATA_SUFFIXES) {
                    if (publishedLowerCase.contains((stem + suffix).toLowerCase(Locale.ROOT))) {
                        leaked.add(stem + suffix);
                    }
                }
            }

            assertThat(leaked)
                    .as("a symbolic-map metadata item reached the wire; only the ten xxxO data items "
                            + "are payload (gate G9)")
                    .isEmpty();
            assertThat(SYMBOLIC_MAP_ITEM_STEMS).hasSize(BillPaymentResponse.MAP_FIELD_COUNT);
            assertThat(METADATA_SUFFIXES).hasSize(7);
        }

        @Test
        @DisplayName("there is no property named actidinL - the cursor signal is an enum instead")
        void thereIsNoPropertyNamedActidinL() throws Exception {
            Set<String> published = publishedNames(canonical());
            Set<String> publishedLowerCase = new LinkedHashSet<>();
            for (String name : published) {
                publishedLowerCase.add(name.toLowerCase(Locale.ROOT));
            }

            assertThat(published).doesNotContain("actidinL");
            assertThat(publishedLowerCase).doesNotContain("actidinl");
            assertThat(published)
                    .as("the cursor signal is metadata and travels as screenMetadata.cursorField, which "
                            + "BillPaymentController assembles - it is not a member of this type")
                    .doesNotContain("cursorField");
        }

        @Test
        @DisplayName("no derived predicate leaks as a property")
        void noDerivedPredicateLeaksAsAProperty() {
            BillPaymentResponse response = new BillPaymentResponse();
            response.setNextPageFlg(BillPaymentResponse.NEXT_PAGE_YES);

            JsonNode body = mapper.valueToTree(response);

            assertThat(body.has("nextPageYes")).isFalse();
            assertThat(body.has("nextPageNo")).isFalse();
            assertThat(body.get("nextPageFlg").asText())
                    .isEqualTo(BillPaymentResponse.NEXT_PAGE_YES);
        }

        @Test
        @DisplayName("a round trip through JSON is lossless for every one of the twenty-two members")
        void aRoundTripIsLossless() throws Exception {
            BillPaymentResponse original = canonical();

            BillPaymentResponse bound = mapper.readValue(
                    mapper.writeValueAsString(original), BillPaymentResponse.class);

            for (String member : MAP_MEMBER_WIDTHS.keySet()) {
                assertThat(get(bound, member)).as(member).isEqualTo(get(original, member));
            }
            assertThat(bound.getCursorField()).isSameAs(CursorField.NONE);
            assertThat(bound.getMessageHighlight()).isNull();
            assertThat(bound.getNavigationContext()).isEqualTo(original.getNavigationContext());
            assertThat(bound.getNextProgram()).isEqualTo(BillPaymentResponse.MAIN_MENU_PROGRAM);
            assertThat(bound.getNextMapset()).isEqualTo(BillPaymentResponse.MAPSET_NAME);
            assertThat(bound.getNextMap()).isEqualTo(BillPaymentResponse.MAP_NAME);
            assertThat(bound.getTrnIdFirst()).isEqualTo("0000000000000001");
            assertThat(bound.getTrnIdLast()).isEqualTo("0000000000000010");
            assertThat(bound.getPageNum()).isEqualTo(3);
            assertThat(bound.getNextPageFlg()).isEqualTo(BillPaymentResponse.NEXT_PAGE_YES);
            assertThat(bound.getTrnSelFlg()).isEqualTo("S");
            assertThat(bound.getTrnSelected()).isEqualTo("0000000000000007");
        }

        @Test
        @DisplayName("an undeclared property is refused - the screen contract is closed")
        void anUndeclaredPropertyIsRefused() {
            assertThatExceptionOfType(UnrecognizedPropertyException.class)
                    .isThrownBy(() -> mapper.readValue(
                            "{\"actidin\":\"00000000011\",\"notAScreenField\":\"x\"}",
                            BillPaymentResponse.class))
                    .withMessageContaining("notAScreenField");
        }

        @Test
        @DisplayName("a metadata item name in the body is refused, not silently absorbed")
        void aMetadataItemNameInTheBodyIsRefused() {
            assertThatExceptionOfType(UnrecognizedPropertyException.class)
                    .isThrownBy(() -> mapper.readValue("{\"errMsgC\":\" \"}",
                            BillPaymentResponse.class))
                    .withMessageContaining("errMsgC");
            assertThatExceptionOfType(UnrecognizedPropertyException.class)
                    .isThrownBy(() -> mapper.readValue("{\"actIdInL\":-1}",
                            BillPaymentResponse.class))
                    .withMessageContaining("actIdInL");
        }

        @Test
        @DisplayName("an empty body binds to the declared initial state")
        void anEmptyBodyBinds() throws Exception {
            BillPaymentResponse bound = mapper.readValue("{}", BillPaymentResponse.class);

            for (String member : MAP_MEMBER_WIDTHS.keySet()) {
                assertThat(get(bound, member))
                        .as(member)
                        .isEqualTo(ScreenFieldImage.unpainted(MAP_MEMBER_WIDTHS.get(member)));
            }
            assertThat(bound.getCursorField())
                    .as("the field initialiser stands - CursorField.NONE means no override, and the "
                            + "terminal then applies the mapset's own IC field at COBIL00.bms:85")
                    .isEqualTo(CursorField.NONE);
            assertThat(bound.getMessageHighlight())
                    .as("@JsonIgnore, so it does not travel and comes back at its initial state")
                    .isNull();
            assertThat(bound.getNavigationContext()).isNull();
            assertThat(bound.getNextProgram()).isBlank()
                    .hasSize(NavigationContext.TO_PROGRAM_LENGTH);
            assertThat(bound.getNextMapset()).isBlank()
                    .hasSize(NavigationContext.LAST_MAPSET_LENGTH);
            assertThat(bound.getNextMap()).isBlank()
                    .hasSize(NavigationContext.LAST_MAP_LENGTH);
            assertThat(bound.getTrnIdFirst()).isBlank()
                    .hasSize(BillPaymentResponse.TRN_ID_FIRST_LENGTH);
            assertThat(bound.getTrnIdLast()).isBlank()
                    .hasSize(BillPaymentResponse.TRN_ID_LAST_LENGTH);
            assertThat(bound.getPageNum()).isZero();
            assertThat(bound.getNextPageFlg())
                    .as("the VALUE 'N' clause of app/cbl/COBIL00C.cbl:68 survives an empty body")
                    .isEqualTo(BillPaymentResponse.NEXT_PAGE_NO);
            assertThat(bound.getTrnSelFlg()).isBlank()
                    .hasSize(BillPaymentResponse.TRN_SEL_FLG_LENGTH);
            assertThat(bound.getTrnSelected()).isBlank()
                    .hasSize(BillPaymentResponse.TRN_SELECTED_LENGTH);
        }

        @Test
        @DisplayName("an explicit null overwrites a defaulted member - the type coalesces nothing")
        void anExplicitNullOverwritesADefaultedMember() throws Exception {
            BillPaymentResponse bound = mapper.readValue(
                    "{\"errmsg\":null,\"cursorField\":null,\"nextPageFlg\":null}",
                    BillPaymentResponse.class);

            assertThat(bound.getErrMsg()).isNull();
            assertThat(bound.getCursorField())
                    .as("cursorField is @JsonIgnore, so an explicit null in the body is not even read "
                            + "and the field initialiser stands - the cursor request travels on "
                            + "screenMetadata, which BillPaymentController assembles")
                    .isSameAs(CursorField.NONE);
            assertThat(bound.getNextPageFlg()).isNull();
        }

        @Test
        @DisplayName("null, empty and blank survive a round trip as three distinct states")
        void theThreeStatesSurviveDistinctly() throws Exception {
            BillPaymentResponse absent = new BillPaymentResponse();
            BillPaymentResponse empty = new BillPaymentResponse();
            BillPaymentResponse blank = new BillPaymentResponse();
            absent.setConfirm(null);
            empty.setConfirm("");
            blank.setConfirm(" ");

            assertThat(reboundConfirm(absent)).isNull();
            assertThat(reboundConfirm(empty)).isEmpty();
            assertThat(reboundConfirm(blank)).isEqualTo(" ").isNotEmpty().isBlank();
        }

        @Test
        @DisplayName("the ten payload properties are identical on request and response (COBIL00.CPY:79)")
        void theTenPayloadPropertiesAreIdenticalOnBothViews() throws Exception {
            JsonNode responseBody = mapper.valueToTree(canonical());
            JsonNode requestBody = mapper.valueToTree(requestWithTheSameTenValues());

            for (Map.Entry<String, String> value : CANONICAL_MAP_VALUES.entrySet()) {
                String member = wireNameOf(value.getKey());
                assertThat(responseBody.has(member)).as("response publishes %s", member).isTrue();
                assertThat(requestBody.has(member)).as("request publishes %s", member).isTrue();
                assertThat(responseBody.get(member).asText())
                        .as("%s must carry the same value on both views", member)
                        .isEqualTo(requestBody.get(member).asText())
                        .isEqualTo(value.getValue());
            }
        }

        @Test
        @DisplayName("every payload property serialises as a JSON string, never as a number")
        void everyPayloadPropertyIsTextual() throws Exception {
            JsonNode body = mapper.valueToTree(canonical());

            for (String member : wireNamesOf(MAP_MEMBER_WIDTHS.keySet())) {
                assertThat(body.get(member).isTextual())
                        .as("%s projects a PIC X item and must be quoted text (gate G22)", member)
                        .isTrue();
            }
            assertThat(body.get("pageNum").isInt())
                    .as("CDEMO-CB00-PAGE-NUM PIC 9(08) is the one scale-free numeric member")
                    .isTrue();
        }

        private String reboundConfirm(BillPaymentResponse response) throws Exception {
            return mapper.readValue(mapper.writeValueAsString(response),
                    BillPaymentResponse.class).getConfirm();
        }
    }

    @Nested
    @DisplayName("3. The cursor indicator - seventeen MOVE -1 sites collapsed into three constants")
    class CursorIndicator {
        @Test
        @DisplayName("the enum declares exactly NONE, ACTIDIN and CONFIRM, in that order")
        void theEnumDeclaresExactlyThreeConstants() {
            assertThat(CursorField.values())
                    .containsExactly(CursorField.NONE, CursorField.ACTIDIN, CursorField.CONFIRM);
            assertThat(CursorField.values()).hasSize(3);
            assertThat(CursorField.NONE.ordinal()).isZero();
            assertThat(CursorField.ACTIDIN.ordinal()).isEqualTo(1);
            assertThat(CursorField.CONFIRM.ordinal()).isEqualTo(2);
            assertThat(CursorField.NONE.name()).isEqualTo("NONE");
            assertThat(CursorField.ACTIDIN.name()).isEqualTo("ACTIDIN");
            assertThat(CursorField.CONFIRM.name()).isEqualTo("CONFIRM");
        }

        @ParameterizedTest(name = "{0} is carried and read back identically")
        @EnumSource(CursorField.class)
        @DisplayName("every constant is reachable through the accessor pair")
        void everyConstantIsReachable(CursorField constant) {
            BillPaymentResponse response = new BillPaymentResponse();

            response.setCursorField(constant);

            assertThat(response.getCursorField()).isSameAs(constant);
            assertThat(CursorField.valueOf(constant.name())).isSameAs(constant);
        }

        @ParameterizedTest(name = "{0} round-trips through JSON as its own name")
        @EnumSource(CursorField.class)
        @DisplayName("every constant serialises as its declared name and binds back to itself")
        void everyConstantRoundTripsThroughJson(CursorField constant) throws Exception {
            BillPaymentResponse response = new BillPaymentResponse();
            response.setCursorField(constant);

            JsonNode body = mapper.valueToTree(response);
            BillPaymentResponse bound = mapper.readValue(
                    mapper.writeValueAsString(response), BillPaymentResponse.class);

            assertThat(body.has("cursorField")).isFalse();
            assertThat(response.getCursorField()).isSameAs(constant);
            assertThat(bound.getCursorField())
                    .as("it did not travel, so the receiving side is at its initial state")
                    .isSameAs(CursorField.NONE);
        }

        @Test
        @DisplayName("valueOf rejects an unknown name, including the length item it derives from")
        void valueOfRejectsAnUnknownName() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CursorField.valueOf("ACTIDINL"));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CursorField.valueOf("CONFIRML"));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CursorField.valueOf("actidin"));
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> CursorField.valueOf(null));
        }

        @ParameterizedTest(name = "no constant exists for {0}")
        @MethodSource(
                "com.vsergeychik.carddemo.billing.dto.BillPaymentResponseTest#neverCursoredStems")
        @DisplayName("no constant exists for a field that never receives MOVE -1")
        void noConstantExistsForAFieldThatIsNeverCursored(String stem) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CursorField.valueOf(stem));
        }

        @Test
        @DisplayName("the two cursor targets are exactly the two UNPROT fields of the mapset")
        void theTwoCursorTargetsAreTheTwoTypeableFields() {
            List<String> targets = new ArrayList<>();
            for (CursorField constant : CursorField.values()) {
                if (constant != CursorField.NONE) {
                    targets.add(constant.name());
                }
            }

            assertThat(targets).containsExactly("ACTIDIN", "CONFIRM");
            assertThat(targets).hasSize(CursorField.values().length - 1);
            assertThat(SYMBOLIC_MAP_ITEM_STEMS).containsAll(targets);
        }

        @Test
        @DisplayName("NONE is the initial state, so no assignment means no override")
        void noneIsTheInitialState() {
            BillPaymentResponse fresh = new BillPaymentResponse();

            assertThat(fresh.getCursorField()).isSameAs(CursorField.NONE);
            assertThat(CursorField.values()[0]).isSameAs(CursorField.NONE);
        }

        @Test
        @DisplayName("an unknown enum name reaching the enum directly is refused, not bound as null")
        void anUnknownEnumNameIsRefused() throws Exception {
            assertThatExceptionOfType(InvalidFormatException.class)
                    .isThrownBy(() -> mapper.readValue("\"ACTIDINL\"", CursorField.class))
                    .withMessageContaining("ACTIDINL");
            assertThat(mapper.readValue("{\"cursorField\":\"ACTIDINL\"}", BillPaymentResponse.class)
                            .getCursorField())
                    .as("and on the response the member is ignored outright, so it cannot be poisoned")
                    .isSameAs(CursorField.NONE);
        }

        @Test
        @DisplayName("the enum is nested on the response, is an enum, and adds no mutable state")
        void theEnumIsNestedAndImmutable() {
            assertThat(CursorField.class.getEnclosingClass()).isEqualTo(BillPaymentResponse.class);
            assertThat(CursorField.class.isEnum()).isTrue();
            for (Field field : CursorField.class.getDeclaredFields()) {
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("enum field %s must be final", field.getName())
                        .isTrue();
            }
        }
    }

    @Nested
    @DisplayName("4. The message highlight - DFHGREEN, the program's only attribute write (:526)")
    class MessageHighlight {
        @Test
        @DisplayName("the member carries the DFHGREEN attribute byte in one character")
        void theMemberCarriesTheAttributeByteInOneCharacter() {
            BillPaymentResponse response = new BillPaymentResponse();

            response.setMessageHighlight(MESSAGE_HIGHLIGHT_GREEN);

            assertThat(BillPaymentResponse.MESSAGE_HIGHLIGHT_LENGTH).isEqualTo(1);
            assertThat(response.getMessageHighlight())
                    .hasSize(BillPaymentResponse.MESSAGE_HIGHLIGHT_LENGTH);
            assertThat((byte) response.getMessageHighlight().charAt(0))
                    .as("MOVE DFHGREEN TO ERRMSGC OF COBIL0AO at app/cbl/COBIL00C.cbl:526")
                    .isEqualTo(BmsAttributes.DFHGREEN);
        }

        @Test
        @DisplayName("the set arm: a successful payment carries the highlight through JSON intact")
        void theSetArmSurvivesJson() throws Exception {
            BillPaymentResponse response = canonical();

            BillPaymentResponse bound = mapper.readValue(
                    mapper.writeValueAsString(response), BillPaymentResponse.class);

            assertThat(response.getMessageHighlight()).isEqualTo(MESSAGE_HIGHLIGHT_GREEN);
            assertThat((byte) response.getMessageHighlight().charAt(0))
                    .isEqualTo(BmsAttributes.DFHGREEN);
            assertThat(bound.getMessageHighlight()).isNull();
        }

        @Test
        @DisplayName("the unset arm: every other path leaves it null, the map's declared red")
        void theUnsetArmIsNull() throws Exception {
            BillPaymentResponse errorPath = new BillPaymentResponse();
            errorPath.setErrMsg(atWidth("Acct ID can NOT be empty...",
                    BillPaymentResponse.ERR_MSG_LENGTH));

            BillPaymentResponse bound = mapper.readValue(
                    mapper.writeValueAsString(errorPath), BillPaymentResponse.class);

            assertThat(new BillPaymentResponse().getMessageHighlight()).isNull();
            assertThat(bound.getMessageHighlight()).isNull();
            assertThat(bound.getErrMsg()).isNotNull();
        }

        @Test
        @DisplayName("both arms are distinguishable through the accessor, and neither is on the wire")
        void bothArmsAreDistinguishableThroughTheAccessor() throws Exception {
            JsonNode green = mapper.valueToTree(canonical());
            JsonNode none = mapper.valueToTree(new BillPaymentResponse());

            assertThat(green.has("messageHighlight")).isFalse();
            assertThat(none.has("messageHighlight")).isFalse();
            assertThat(canonical().getMessageHighlight()).isEqualTo(MESSAGE_HIGHLIGHT_GREEN);
            assertThat(new BillPaymentResponse().getMessageHighlight()).isNull();
        }

        @Test
        @DisplayName("the member is named for its meaning, not after the ERRMSGC item it projects")
        void theMemberIsNamedForItsMeaning() {
            assertThat(instanceFieldNames()).contains("messageHighlight");
            assertThat(instanceFieldNames()).doesNotContain("errMsgC", "errmsgC", "errMsgc");
        }

        @Test
        @DisplayName("DFHGREEN is an override of the map's RED, and the two bytes differ")
        void greenIsAnOverrideOfRed() {
            assertThat(BmsAttributes.DFHGREEN).isNotEqualTo(BmsAttributes.DFHRED);
            assertThat(BmsAttributes.unsigned(BmsAttributes.DFHGREEN))
                    .isNotEqualTo(BmsAttributes.unsigned(BmsAttributes.DFHRED));
        }
    }

    @Nested
    @DisplayName("5. Navigation - the stateless replacement for the program's single XCTL")
    class Navigation {
        @Test
        @DisplayName("nextMapset is COBIL00 - MAPSET('COBIL00') at app/cbl/COBIL00C.cbl:297")
        void nextMapsetIsTheMapsetName() throws Exception {
            BillPaymentResponse response = canonical();

            assertThat(response.getNextMapset())
                    .as("MAPSET('COBIL00') on the EXEC CICS SEND MAP at COBIL00C:297")
                    .isEqualTo("COBIL00")
                    .isEqualTo(BillPaymentResponse.MAPSET_NAME);
        }

        @Test
        @DisplayName("nextMap is COBIL0A - MAP('COBIL0A') at app/cbl/COBIL00C.cbl:296")
        void nextMapIsTheMapName() throws Exception {
            BillPaymentResponse response = canonical();

            assertThat(response.getNextMap())
                    .as("MAP('COBIL0A') on the EXEC CICS SEND MAP at COBIL00C:296")
                    .isEqualTo("COBIL0A")
                    .isEqualTo(BillPaymentResponse.MAP_NAME);
        }

        @Test
        @DisplayName("the mapset ends in two zeros and the map ends 0A - they are not interchangeable")
        void theMapsetAndTheMapAreNotInterchangeable() throws Exception {
            BillPaymentResponse response = canonical();

            assertThat(BillPaymentResponse.MAPSET_NAME).endsWith("00");
            assertThat(BillPaymentResponse.MAP_NAME).endsWith("0A");
            assertThat(BillPaymentResponse.MAPSET_NAME).isNotEqualTo(BillPaymentResponse.MAP_NAME);
            assertThat(response.getNextMapset()).isNotEqualTo(response.getNextMap());
            assertThat(BillPaymentResponse.MAP_NAME).hasSize(NavigationContext.LAST_MAP_LENGTH);
            assertThat(BillPaymentResponse.MAPSET_NAME)
                    .hasSize(NavigationContext.LAST_MAPSET_LENGTH);
        }

        @ParameterizedTest(name = "nextProgram carries {0} - {1}")
        @MethodSource(
                "com.vsergeychik.carddemo.billing.dto.BillPaymentResponseTest#reachableNextPrograms")
        @DisplayName("each of the four reachable targets round-trips through the DTO and JSON")
        void eachReachableTargetRoundTrips(String program, String evidence) throws Exception {
            BillPaymentResponse response = new BillPaymentResponse();
            response.setNextProgram(program);

            BillPaymentResponse bound = mapper.readValue(
                    mapper.writeValueAsString(response), BillPaymentResponse.class);

            assertThat(response.getNextProgram()).as(evidence).isEqualTo(program);
            assertThat(bound.getNextProgram()).as(evidence).isEqualTo(program);
            assertThat(program)
                    .as("CDEMO-FROM-PROGRAM and CDEMO-TO-PROGRAM are both PIC X(08)")
                    .hasSize(NavigationContext.TO_PROGRAM_LENGTH);
        }

        @Test
        @DisplayName("the two literal targets are the exact eight-character CSD program names")
        void theTwoLiteralTargetsAreTheExactCsdNames() {
            assertThat(BillPaymentResponse.SIGN_ON_PROGRAM).isEqualTo("COSGN00C").hasSize(8);
            assertThat(BillPaymentResponse.MAIN_MENU_PROGRAM).isEqualTo("COMEN01C").hasSize(8);
            assertThat(BillPaymentResponse.SIGN_ON_PROGRAM)
                    .isNotEqualTo(BillPaymentResponse.MAIN_MENU_PROGRAM);
        }

        @Test
        @DisplayName("all three navigation members are declared Strings and start absent")
        void allThreeNavigationMembersAreDeclaredStrings() throws Exception {
            BillPaymentResponse fresh = new BillPaymentResponse();

            for (String member : List.of("nextProgram", "nextMapset", "nextMap")) {
                assertThat(BillPaymentResponse.class.getDeclaredField(member).getType())
                        .as(member)
                        .isEqualTo(String.class);
            }
            assertThat(fresh.getNextProgram()).isBlank()
                    .hasSize(NavigationContext.TO_PROGRAM_LENGTH);
            assertThat(fresh.getNextMapset()).isBlank()
                    .hasSize(NavigationContext.LAST_MAPSET_LENGTH);
            assertThat(fresh.getNextMap()).isBlank()
                    .hasSize(NavigationContext.LAST_MAP_LENGTH);
        }

        @Test
        @DisplayName("the type defaults nothing - the LOW-VALUES fallback of :275-276 is not here")
        void theTypeDefaultsNothing() throws Exception {
            BillPaymentResponse absent = new BillPaymentResponse();
            BillPaymentResponse blank = new BillPaymentResponse();
            absent.setNextProgram(null);
            blank.setNextProgram("        ");

            assertThat(absent.getNextProgram()).isNull();
            assertThat(blank.getNextProgram())
                    .isEqualTo("        ")
                    .isNotEqualTo(BillPaymentResponse.SIGN_ON_PROGRAM);
            assertThat(mapper.readValue(mapper.writeValueAsString(blank),
                    BillPaymentResponse.class).getNextProgram()).isEqualTo("        ");
        }

        @Test
        @DisplayName("a named target, not a redirect - no forward, location or redirect member")
        void aNamedTargetNotARedirect() {
            List<String> offending = new ArrayList<>();
            for (Method method : BillPaymentResponse.class.getDeclaredMethods()) {
                String name = method.getName().toLowerCase(Locale.ROOT);
                if (name.contains("redirect") || name.contains("forward")
                        || name.contains("location")) {
                    offending.add(method.getName());
                }
            }

            assertThat(offending).isEmpty();
        }
    }

    @Nested
    @DisplayName("6. curBal is edited text (G22) and errMsg is X(78) fed by an X(80) source")
    class EditedTextAndTruncation {
        @Test
        @DisplayName("curBal is declared exactly java.lang.String - never a numeric type")
        void curBalIsDeclaredAString() throws Exception {
            Field field = BillPaymentResponse.class.getDeclaredField("curBal");

            assertThat(field.getType())
                    .as("WS-CURR-BAL PIC +9999999999.99 at app/cbl/COBIL00C.cbl:56 is an EDITED "
                            + "picture, so CURBALO is PIC X(14) and this member must be a String. A "
                            + "double, float, Double, Float or BigDecimal would discard the leading "
                            + "zeros, the explicit sign character and the column alignment that the "
                            + "field-for-field parity comparison checks (gate G22)")
                    .isEqualTo(String.class);
            assertThat(field.getType()).isNotIn(double.class, float.class, Double.class, Float.class,
                    java.math.BigDecimal.class);
        }

        @Test
        @DisplayName("no floating-point or BigDecimal type appears anywhere on the type (G22)")
        void noFloatingPointTypeAppearsAnywhere() {
            List<String> offending = new ArrayList<>();

            for (Field field : BillPaymentResponse.class.getDeclaredFields()) {
                rejectInexactType("field " + field.getName(), field.getType(), offending);
            }
            for (Method method : BillPaymentResponse.class.getDeclaredMethods()) {
                rejectInexactType("return of " + method.getName(), method.getReturnType(), offending);
                for (Class<?> parameter : method.getParameterTypes()) {
                    rejectInexactType("parameter of " + method.getName(), parameter, offending);
                }
            }

            assertThat(offending)
                    .as("no double, float, Double, Float or BigDecimal may appear on a payload that "
                            + "carries edited text (gate G22)")
                    .isEmpty();
        }

        @ParameterizedTest(name = "{0} round-trips byte-identically")
        @MethodSource(
                "com.vsergeychik.carddemo.billing.dto.BillPaymentResponseTest#editedBalances")
        @DisplayName("a positive, a negative and a zero balance all survive intact at 14 characters")
        void everyEditedBalanceSurvivesIntact(String edited) throws Exception {
            BillPaymentResponse response = new BillPaymentResponse();
            response.setCurBal(edited);

            JsonNode body = mapper.valueToTree(response);
            BillPaymentResponse bound = mapper.readValue(
                    mapper.writeValueAsString(response), BillPaymentResponse.class);

            assertThat(edited).hasSize(BillPaymentResponse.CUR_BAL_LENGTH);
            assertThat(response.getCurBal()).isEqualTo(edited);
            assertThat(body.get("curbal").isTextual())
                    .as("the balance is a quoted JSON string, never a JSON number")
                    .isTrue();
            assertThat(body.get("curbal").asText()).isEqualTo(edited);
            assertThat(mapper.writeValueAsString(response))
                    .as("the sign and every leading zero appear verbatim in the serialised body")
                    .contains("\"curbal\":\"" + edited + "\"");
            assertThat(bound.getCurBal()).isEqualTo(edited).hasSize(14);
        }

        @Test
        @DisplayName("fourteen is the edit mask: 1 sign + 10 digits + 1 point + 2 digits")
        void fourteenIsTheEditMask() {
            assertThat(BillPaymentResponse.CUR_BAL_LENGTH).isEqualTo(1 + 10 + 1 + 2).isEqualTo(14);
        }

        @Test
        @DisplayName("errMsg is 78 while WS-MESSAGE is 80 - the move loses exactly two bytes")
        void errMsgIsNarrowerThanItsSource() {
            assertThat(BillPaymentResponse.ERR_MSG_LENGTH).isEqualTo(78);
            assertThat(BillPaymentResponse.WS_MESSAGE_LENGTH).isEqualTo(80);
            assertThat(BillPaymentResponse.WS_MESSAGE_LENGTH - BillPaymentResponse.ERR_MSG_LENGTH)
                    .as("the width is deliberately not widened to match the source of the move")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("a 78-character message is carried intact")
        void aSeventyEightCharacterMessageIsCarriedIntact() throws Exception {
            String atWidth = repeat('A', BillPaymentResponse.ERR_MSG_LENGTH);
            BillPaymentResponse response = new BillPaymentResponse();

            response.setErrMsg(atWidth);

            assertThat(response.getErrMsg()).isEqualTo(atWidth).hasSize(78);
            assertThat(mapper.readValue(mapper.writeValueAsString(response),
                    BillPaymentResponse.class).getErrMsg()).isEqualTo(atWidth);
        }

        @Test
        @DisplayName("an 80-character message keeps the FIRST 78 - COBOL truncates on the right")
        void anEightyCharacterMessageKeepsTheFirstSeventyEight() {
            String source = repeat('A', BillPaymentResponse.ERR_MSG_LENGTH) + "ZZ";
            String sent = source.substring(0, BillPaymentResponse.ERR_MSG_LENGTH);

            assertThat(source).hasSize(BillPaymentResponse.WS_MESSAGE_LENGTH);
            assertThat(sent)
                    .as("a COBOL MOVE between alphanumeric items truncates on the RIGHT, so the two "
                            + "final bytes are discarded and the first 78 survive")
                    .isEqualTo(repeat('A', BillPaymentResponse.ERR_MSG_LENGTH))
                    .hasSize(BillPaymentResponse.ERR_MSG_LENGTH)
                    .doesNotContain("Z");
            assertThat(sent)
                    .as("getting the direction backwards is a classic silent parity break: the LAST "
                            + "78 characters are NOT what the field receives")
                    .isNotEqualTo(source.substring(source.length()
                            - BillPaymentResponse.ERR_MSG_LENGTH));
        }

        @Test
        @DisplayName("the DTO stores 80 characters verbatim - the truncation is the caller's")
        void theDtoStoresTheSourceVerbatim() throws Exception {
            String source = repeat('A', BillPaymentResponse.ERR_MSG_LENGTH) + "ZZ";
            BillPaymentResponse response = new BillPaymentResponse();

            response.setErrMsg(source);

            assertThat(response.getErrMsg())
                    .as("no setter on this type trims, pads or truncates - it holds no decision logic")
                    .isEqualTo(source)
                    .hasSize(BillPaymentResponse.WS_MESSAGE_LENGTH);
            assertThat(mapper.readValue(mapper.writeValueAsString(response),
                    BillPaymentResponse.class).getErrMsg()).isEqualTo(source);
        }

        @Test
        @DisplayName("a short message is neither padded nor altered by the DTO")
        void aShortMessageIsNeitherPaddedNorAltered() throws Exception {
            String short1 = "Acct ID can NOT be empty...";
            String short2 = "Invalid value. Valid values are (Y/N)...";
            BillPaymentResponse response = new BillPaymentResponse();

            response.setErrMsg(short1);
            assertThat(response.getErrMsg()).isEqualTo(short1).hasSize(27);

            response.setErrMsg(short2);
            assertThat(response.getErrMsg()).isEqualTo(short2).hasSize(40);
            assertThat(mapper.readValue(mapper.writeValueAsString(response),
                    BillPaymentResponse.class).getErrMsg())
                    .as("the space padding a COBOL MOVE would apply belongs to the codec, not here")
                    .isEqualTo(short2);
        }

        @Test
        @DisplayName("the longest message this screen composes fits the field with room to spare")
        void theLongestComposedMessageFits() {
            String composed = "Payment successful.  Your Transaction ID is 0000000000000042.";

            assertThat(composed).hasSize(61);
            assertThat(composed.length()).isLessThan(BillPaymentResponse.ERR_MSG_LENGTH);
        }

        private void rejectInexactType(String where, Class<?> type, List<String> offending) {
            if (type == double.class || type == float.class || type == Double.class
                    || type == Float.class || type == java.math.BigDecimal.class) {
                offending.add(where + " is " + type.getName());
            }
        }
    }

    @Nested
    @DisplayName("7. The carried communication area - 160 shared bytes plus 58 program-local ones")
    class CarriedConversationState {
        @ParameterizedTest(name = "{0} is declared with its source width {1}")
        @MethodSource(
                "com.vsergeychik.carddemo.billing.dto.BillPaymentResponseTest#cb00InfoWidths")
        @DisplayName("each of the six CDEMO-CB00-INFO members is declared with its source width")
        void eachExtensionMemberIsDeclaredWithItsSourceWidth(String member, int width)
                throws Exception {
            Field field = BillPaymentResponse.class.getDeclaredField(member);

            assertThat(width).isPositive();
            if ("pageNum".equals(member)) {
                assertThat(field.getType())
                        .as("CDEMO-CB00-PAGE-NUM PIC 9(08) is scale-free - eight digits, no V and no "
                                + "sign - so it is an int, the one non-String member of the six")
                        .isEqualTo(int.class);
            } else {
                assertThat(field.getType()).as(member).isEqualTo(String.class);
            }
        }

        @Test
        @DisplayName("the six widths sum to 58 - the length of 05 CDEMO-CB00-INFO")
        void theSixWidthsSumToFiftyEight() {
            int sum = CB00_INFO_WIDTHS.values().stream().mapToInt(Integer::intValue).sum();

            assertThat(CB00_INFO_WIDTHS).hasSize(6);
            assertThat(sum).isEqualTo(58);
            assertThat(BillPaymentResponse.CB00_INFO_LENGTH).isEqualTo(sum);
        }

        @Test
        @DisplayName("this program's communication area is 218 bytes: the shared 160 plus 58")
        void theCommunicationAreaIsTwoHundredAndEighteenBytes() {
            assertThat(NavigationContext.COMMAREA_LENGTH).isEqualTo(160);
            assertThat(BillPaymentResponse.CB00_COMMAREA_LENGTH)
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH
                            + BillPaymentResponse.CB00_INFO_LENGTH)
                    .isEqualTo(218);
        }

        @Test
        @DisplayName("NavigationContext is exactly 160 bytes, with X(7) map and mapset (COCOM01Y:43-44)")
        void theSharedCarrierIsExactlyOneHundredAndSixtyBytes() {
            assertThat(NavigationContext.LAST_MAP_LENGTH).isEqualTo(7);
            assertThat(NavigationContext.LAST_MAPSET_LENGTH).isEqualTo(7);
            assertThat(NavigationContext.MORE_INFO_LENGTH)
                    .isEqualTo(NavigationContext.LAST_MAP_LENGTH
                            + NavigationContext.LAST_MAPSET_LENGTH)
                    .isEqualTo(14);
            assertThat(NavigationContext.GENERAL_INFO_LENGTH
                    + NavigationContext.CUSTOMER_INFO_LENGTH
                    + NavigationContext.ACCOUNT_INFO_LENGTH
                    + NavigationContext.CARD_INFO_LENGTH
                    + NavigationContext.MORE_INFO_LENGTH)
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH)
                    .isEqualTo(160);
        }

        @Test
        @DisplayName("no extension member is folded into the shared communication area")
        void noExtensionMemberIsFoldedIntoTheSharedCarrier() {
            Set<String> carrierComponents = new LinkedHashSet<>();
            for (RecordComponent component : NavigationContext.class.getRecordComponents()) {
                carrierComponents.add(component.getName());
            }

            assertThat(NavigationContext.class.isRecord()).isTrue();
            assertThat(carrierComponents).doesNotContainAnyElementsOf(CB00_INFO_WIDTHS.keySet());
            assertThat(instanceFieldNames()).containsAll(CB00_INFO_WIDTHS.keySet());
            assertThat(instanceFieldNames()).contains("navigationContext");
        }

        @Test
        @DisplayName("the paging flag defaults to 'N', the VALUE clause the source declares")
        void thePagingFlagDefaultsToNo() {
            BillPaymentResponse fresh = new BillPaymentResponse();

            assertThat(BillPaymentResponse.NEXT_PAGE_NO).isEqualTo("N");
            assertThat(fresh.getNextPageFlg()).isEqualTo(BillPaymentResponse.NEXT_PAGE_NO);
        }

        @Test
        @DisplayName("an explicitly supplied paging flag replaces the default")
        void anExplicitPagingFlagReplacesTheDefault() {
            BillPaymentResponse response = new BillPaymentResponse();

            response.setNextPageFlg(BillPaymentResponse.NEXT_PAGE_YES);

            assertThat(BillPaymentResponse.NEXT_PAGE_YES).isEqualTo("Y");
            assertThat(response.getNextPageFlg()).isEqualTo(BillPaymentResponse.NEXT_PAGE_YES);
        }

        @Test
        @DisplayName("the two condition names are independent tests, not each other's negation")
        void theTwoConditionNamesAreIndependent() {
            BillPaymentResponse blank = new BillPaymentResponse();
            BillPaymentResponse absent = new BillPaymentResponse();
            blank.setNextPageFlg(" ");
            absent.setNextPageFlg(null);

            assertThat(blank.getNextPageFlg())
                    .isNotEqualTo(BillPaymentResponse.NEXT_PAGE_YES)
                    .isNotEqualTo(BillPaymentResponse.NEXT_PAGE_NO)
                    .isEqualTo(" ");
            assertThat(absent.getNextPageFlg()).isNull();
        }

        @Test
        @DisplayName("the one member the program reads is carried, and so are the five it never reads")
        void theUnreferencedMembersAreCarriedAnyway() throws Exception {
            BillPaymentResponse response = canonical();

            assertThat(response.getTrnSelected()).isEqualTo("0000000000000007");
            assertThat(instanceFieldNames()).containsAll(UNREFERENCED_CB00_MEMBERS);
            assertThat(UNREFERENCED_CB00_MEMBERS).hasSize(5);
            assertThat(publishedNames(response)).containsAll(UNREFERENCED_CB00_MEMBERS);
        }

        @Test
        @DisplayName("the six members are carried and read back unaltered")
        void theSixMembersAreCarriedUnaltered() throws Exception {
            BillPaymentResponse response = canonical();

            assertThat(response.getTrnIdFirst()).isEqualTo("0000000000000001")
                    .hasSize(BillPaymentResponse.TRN_ID_FIRST_LENGTH);
            assertThat(response.getTrnIdLast()).isEqualTo("0000000000000010")
                    .hasSize(BillPaymentResponse.TRN_ID_LAST_LENGTH);
            assertThat(response.getPageNum()).isEqualTo(3);
            assertThat(response.getNextPageFlg()).isEqualTo(BillPaymentResponse.NEXT_PAGE_YES);
            assertThat(response.getTrnSelFlg()).isEqualTo("S")
                    .hasSize(BillPaymentResponse.TRN_SEL_FLG_LENGTH);
            assertThat(response.getTrnSelected()).isEqualTo("0000000000000007")
                    .hasSize(BillPaymentResponse.TRN_SELECTED_LENGTH);
        }

        @Test
        @DisplayName("pageNum is a scale-free int and carries its full eight-digit range")
        void pageNumIsAScaleFreeInt() {
            BillPaymentResponse response = new BillPaymentResponse();

            assertThat(response.getPageNum())
                    .as("a numeric PIC 9 item initialises to zero")
                    .isZero();

            response.setPageNum(99_999_999);

            assertThat(BillPaymentResponse.PAGE_NUM_DIGITS).isEqualTo(8);
            assertThat(response.getPageNum()).isEqualTo(99_999_999);
        }

        @Test
        @DisplayName("the carrier is referenced, not redeclared - its own values read through")
        void theCarrierIsReferencedNotRedeclared() throws Exception {
            BillPaymentResponse response = canonical();

            assertThat(response.getNavigationContext().fromTranid())
                    .isEqualTo(BillPaymentResponse.TRANSACTION_ID);
            assertThat(response.getNavigationContext().fromProgram())
                    .isEqualTo(BillPaymentResponse.PROGRAM_NAME);
            assertThat(response.getNavigationContext().lastMap())
                    .isEqualTo(BillPaymentResponse.MAP_NAME);
            assertThat(response.getNavigationContext().lastMapset())
                    .isEqualTo(BillPaymentResponse.MAPSET_NAME);
            assertThat(response.getNavigationContext().isReenter()).isTrue();
            assertThat(response.getNavigationContext().pgmContext())
                    .isEqualTo(NavigationContext.PGM_CONTEXT_REENTER);
        }

        @Test
        @DisplayName("the enter/re-enter context has exactly one home - inside the carrier")
        void theContextHasExactlyOneHome() {
            assertThat(instanceFieldNames()).doesNotContain("pgmContext", "pgmReenter", "reenter");
            assertThat(NavigationContext.PGM_CONTEXT_ENTER).isZero();
            assertThat(NavigationContext.PGM_CONTEXT_REENTER).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("8. Statelessness (G37), immutable statics (G53) and value semantics")
    class StatelessnessAndValueSemantics {
        @Test
        @DisplayName("the type holds no static mutable state")
        void theTypeHoldsNoStaticMutableState() {
            for (Field field : BillPaymentResponse.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("static field %s must be final", field.getName())
                            .isTrue();
                    assertThat(field.getType().isPrimitive() || field.getType() == String.class)
                            .as("static field %s must be an immutable constant, was %s",
                                    field.getName(), field.getType().getName())
                            .isTrue();
                }
            }
        }

        @Test
        @DisplayName("no session, servlet or thread-local type appears anywhere on the type")
        void noServerSideSessionTypeAppears() {
            List<String> offending = new ArrayList<>();

            for (Field field : BillPaymentResponse.class.getDeclaredFields()) {
                rejectSessionType("field " + field.getName(), field.getType(), offending);
            }
            for (Method method : BillPaymentResponse.class.getDeclaredMethods()) {
                rejectSessionType("return of " + method.getName(), method.getReturnType(), offending);
                for (Class<?> parameter : method.getParameterTypes()) {
                    rejectSessionType("parameter of " + method.getName(), parameter, offending);
                }
            }
            for (Annotation annotation : BillPaymentResponse.class.getAnnotations()) {
                String name = annotation.annotationType().getSimpleName();
                if (name.contains("Session") || name.contains("Scope")) {
                    offending.add("the class carries @" + name);
                }
            }

            assertThat(offending)
                    .as("no HttpSession, no jakarta.servlet or javax.servlet type, no ThreadLocal and "
                            + "no session or scope annotation may appear (gate G37)")
                    .isEmpty();
        }

        @Test
        @DisplayName("two instances share nothing")
        void twoInstancesShareNothing() {
            BillPaymentResponse first = new BillPaymentResponse();
            BillPaymentResponse second = new BillPaymentResponse();

            first.setActIdIn("00000000001");
            first.setCursorField(CursorField.CONFIRM);
            first.setMessageHighlight(MESSAGE_HIGHLIGHT_GREEN);
            first.setPageNum(7);

            assertThat(second.getActIdIn())
                    .isEqualTo(ScreenFieldImage.unpainted(BillPaymentResponse.ACT_ID_IN_LENGTH));
            assertThat(second.getCursorField()).isSameAs(CursorField.NONE);
            assertThat(second.getMessageHighlight()).isNull();
            assertThat(second.getPageNum()).isZero();
            assertThat(second.getNavigationContext()).isNull();
        }

        @Test
        @DisplayName("two responses carrying different contexts do not influence one another")
        void twoResponsesCarryingDifferentContextsDoNotInfluenceOneAnother() {
            BillPaymentResponse onEnter = new BillPaymentResponse();
            onEnter.setNavigationContext(
                    NavigationContext.empty().withUserId("USER0001").withPgmEnter());
            BillPaymentResponse onReenter = new BillPaymentResponse();
            onReenter.setNavigationContext(NavigationContext.empty()
                    .withUserId("ADMIN001")
                    .withUserTypeAdmin()
                    .withPgmReenter());

            assertThat(onEnter.getNavigationContext().isEnter()).isTrue();
            assertThat(onReenter.getNavigationContext().isReenter()).isTrue();
            assertThat(onEnter.getNavigationContext().userId()).isEqualTo("USER0001");
            assertThat(onReenter.getNavigationContext().userId()).isEqualTo("ADMIN001");
            assertThat(onEnter.getNavigationContext().isAdmin()).isFalse();
            assertThat(onReenter.getNavigationContext().isAdmin()).isTrue();
            assertThat(onEnter.getNavigationContext())
                    .isNotEqualTo(onReenter.getNavigationContext());
        }

        @Test
        @DisplayName("the type declares neither equals nor hashCode, and declares one nested enum")
        void theTypeDeclaresNeitherEqualsNorHashCode() {
            assertThatExceptionOfType(NoSuchMethodException.class)
                    .isThrownBy(() -> BillPaymentResponse.class.getDeclaredMethod("equals",
                            Object.class));
            assertThatExceptionOfType(NoSuchMethodException.class)
                    .isThrownBy(() -> BillPaymentResponse.class.getDeclaredMethod("hashCode"));

            assertThat(BillPaymentResponse.class.getDeclaredClasses())
                    .as("exactly one nested type is declared - the cursor enum - and its every "
                            + "constant is driven above")
                    .containsExactly(CursorField.class);
            assertThat(BillPaymentResponse.class.isRecord())
                    .as("a plain bean was chosen over a record: twenty-two members and constant "
                            + "partial population make a no-argument constructor plus setters the "
                            + "workable shape, and the controller fills the object incrementally")
                    .isFalse();
        }

        @Test
        @DisplayName("identity semantics follow from that decision")
        void identitySemanticsFollowFromThatDecision() throws Exception {
            BillPaymentResponse instance = canonical();
            BillPaymentResponse componentWiseCopy = canonical();

            assertThat(instance).isEqualTo(instance);
            assertThat(instance.equals(componentWiseCopy))
                    .as("with no value equality declared, a component-wise copy is a different object")
                    .isFalse();
            assertThat(instance.equals(null)).isFalse();
            assertThat(instance.equals("BillPaymentResponse")).isFalse();
            assertThat(componentWiseCopy).isNotSameAs(instance);

            BillPaymentResponse withValue = canonical();
            BillPaymentResponse withoutValue = canonical();
            withoutValue.setConfirm(null);

            assertThat(withValue.getConfirm()).isEqualTo("Y");
            assertThat(withoutValue.getConfirm()).isNull();
            assertThat(withValue.getConfirm()).isNotEqualTo(withoutValue.getConfirm());
            assertThat(withoutValue.getConfirm()).isNotEqualTo(withValue.getConfirm());
            assertThat(withValue.equals(withoutValue)).isFalse();
            assertThat(withoutValue.equals(withValue)).isFalse();
        }

        @Test
        @DisplayName("the inherited hashCode is stable, and equal references agree on it")
        void theInheritedHashCodeIsStable() throws Exception {
            BillPaymentResponse instance = canonical();

            int first = instance.hashCode();
            int second = instance.hashCode();

            assertThat(second).isEqualTo(first);
            assertThat(instance.hashCode()).isEqualTo(instance.hashCode());
        }

        @ParameterizedTest(name = "changing {0} changes {0} and nothing else")
        @MethodSource(
                "com.vsergeychik.carddemo.billing.dto.BillPaymentResponseTest#mapMemberWidths")
        @DisplayName("mutating one map member changes exactly that member")
        void mutatingOneMapMemberChangesExactlyThatMember(String member, int width) throws Exception {
            BillPaymentResponse mutated = canonical();
            String sentinel = repeat(MUTATION_CHARACTER, width);

            set(mutated, member, sentinel);

            assertThat(get(mutated, member)).isEqualTo(sentinel).hasSize(width);
            for (Map.Entry<String, String> untouched : CANONICAL_MAP_VALUES.entrySet()) {
                if (!untouched.getKey().equals(member)) {
                    assertThat(get(mutated, untouched.getKey()))
                            .as("changing %s must not disturb %s", member, untouched.getKey())
                            .isEqualTo(untouched.getValue());
                }
            }
            assertThat(mutated.getCursorField()).isSameAs(CursorField.ACTIDIN);
            assertThat(mutated.getMessageHighlight()).isEqualTo(MESSAGE_HIGHLIGHT_GREEN);
            assertThat(mutated.getPageNum()).isEqualTo(3);
        }

        @Test
        @DisplayName("mutating one carried member changes exactly that member")
        void mutatingOneCarriedMemberChangesExactlyThatMember() throws Exception {
            assertOnlyChange(response -> response.setCursorField(CursorField.CONFIRM),
                    "cursorField");
            assertOnlyChange(response -> response.setMessageHighlight(null), "messageHighlight");
            assertOnlyChange(response -> response.setNavigationContext(NavigationContext.empty()),
                    "navigationContext");
            assertOnlyChange(response -> response.setNextProgram(
                    BillPaymentResponse.SIGN_ON_PROGRAM), "nextProgram");
            assertOnlyChange(response -> response.setNextMapset(null), "nextMapset");
            assertOnlyChange(response -> response.setNextMap(null), "nextMap");
            assertOnlyChange(response -> response.setTrnIdFirst("9999999999999999"), "trnIdFirst");
            assertOnlyChange(response -> response.setTrnIdLast("9999999999999999"), "trnIdLast");
            assertOnlyChange(response -> response.setPageNum(11), "pageNum");
            assertOnlyChange(response -> response.setNextPageFlg(
                    BillPaymentResponse.NEXT_PAGE_NO), "nextPageFlg");
            assertOnlyChange(response -> response.setTrnSelFlg(null), "trnSelFlg");
            assertOnlyChange(response -> response.setTrnSelected(null), "trnSelected");
        }

        @ParameterizedTest(name = "{0} reads back exactly what was set")
        @MethodSource(
                "com.vsergeychik.carddemo.billing.dto.BillPaymentResponseTest#mapMemberWidths")
        @DisplayName("every map accessor reads back exactly what its setter stored")
        void everyMapAccessorReadsBackWhatWasSet(String member, int width) throws Exception {
            BillPaymentResponse response = new BillPaymentResponse();
            String value = repeat('7', width);

            set(response, member, value);

            assertThat(get(response, member)).isEqualTo(value);
        }

        @ParameterizedTest(name = "{0} accepts null and returns it")
        @MethodSource(
                "com.vsergeychik.carddemo.billing.dto.BillPaymentResponseTest#mapMemberWidths")
        @DisplayName("a setter accepts null and the accessor returns it - absence is representable")
        void aSetterAcceptsNullAndTheAccessorReturnsIt(String member, int width) throws Exception {
            BillPaymentResponse response = canonical();

            set(response, member, null);

            assertThat(get(response, member)).as(member).isNull();
            assertThat(width).isPositive();
        }

        @Test
        @DisplayName("every carried accessor reads back exactly what was set")
        void everyCarriedAccessorReadsBackWhatWasSet() throws Exception {
            BillPaymentResponse response = canonical();

            assertThat(response.getCursorField()).isSameAs(CursorField.ACTIDIN);
            assertThat(response.getMessageHighlight()).isEqualTo(MESSAGE_HIGHLIGHT_GREEN);
            assertThat(response.getNavigationContext()).isNotNull();
            assertThat(response.getNextProgram()).isEqualTo(BillPaymentResponse.MAIN_MENU_PROGRAM);
            assertThat(response.getNextMapset()).isEqualTo(BillPaymentResponse.MAPSET_NAME);
            assertThat(response.getNextMap()).isEqualTo(BillPaymentResponse.MAP_NAME);
            assertThat(response.getTrnIdFirst()).isEqualTo("0000000000000001");
            assertThat(response.getTrnIdLast()).isEqualTo("0000000000000010");
            assertThat(response.getPageNum()).isEqualTo(3);
            assertThat(response.getNextPageFlg()).isEqualTo(BillPaymentResponse.NEXT_PAGE_YES);
            assertThat(response.getTrnSelFlg()).isEqualTo("S");
            assertThat(response.getTrnSelected()).isEqualTo("0000000000000007");
        }

        @Test
        @DisplayName("a fresh instance reports the declared initial state on every accessor")
        void aFreshInstanceReportsTheDeclaredInitialState() throws Exception {
            BillPaymentResponse fresh = new BillPaymentResponse();

            for (String member : MAP_MEMBER_WIDTHS.keySet()) {
                assertThat(get(fresh, member))
                        .as(member)
                        .isEqualTo(ScreenFieldImage.unpainted(MAP_MEMBER_WIDTHS.get(member)));
            }
            assertThat(fresh.getCursorField()).isSameAs(CursorField.NONE);
            assertThat(fresh.getMessageHighlight()).isNull();
            assertThat(fresh.getNavigationContext()).isNull();
            assertThat(fresh.getNextProgram()).isBlank()
                    .hasSize(NavigationContext.TO_PROGRAM_LENGTH);
            assertThat(fresh.getNextMapset()).isBlank()
                    .hasSize(NavigationContext.LAST_MAPSET_LENGTH);
            assertThat(fresh.getNextMap()).isBlank()
                    .hasSize(NavigationContext.LAST_MAP_LENGTH);
            assertThat(fresh.getTrnIdFirst()).isBlank()
                    .hasSize(BillPaymentResponse.TRN_ID_FIRST_LENGTH);
            assertThat(fresh.getTrnIdLast()).isBlank()
                    .hasSize(BillPaymentResponse.TRN_ID_LAST_LENGTH);
            assertThat(fresh.getPageNum()).isZero();
            assertThat(fresh.getNextPageFlg()).isEqualTo(BillPaymentResponse.NEXT_PAGE_NO);
            assertThat(fresh.getTrnSelFlg()).isBlank()
                    .hasSize(BillPaymentResponse.TRN_SEL_FLG_LENGTH);
            assertThat(fresh.getTrnSelected()).isBlank()
                    .hasSize(BillPaymentResponse.TRN_SELECTED_LENGTH);
        }

        @Test
        @DisplayName("toString names the type, the cursor field and the message, and diagnoses")
        void toStringNamesTheTypeTheCursorFieldAndTheMessage() throws Exception {
            BillPaymentResponse response = canonical();

            String rendered = response.toString();

            assertThat(rendered).startsWith("BillPaymentResponse[").endsWith("]");
            assertThat(rendered)
                    .as("the cursor constant must appear so a failure is diagnostic")
                    .contains("cursorField=" + CursorField.ACTIDIN.name());
            assertThat(rendered).contains("errMsg=Payment successful.");
            assertThat(rendered).contains("pageNum=3");
            assertThat(rendered).contains("curBal=").contains("actIdIn=");
        }

        @Test
        @DisplayName("toString withholds the balance and masks the identifiers")
        void toStringWithholdsTheBalanceAndMasksTheIdentifiers() throws Exception {
            BillPaymentResponse response = canonical();

            String rendered = response.toString();

            assertThat(rendered)
                    .as("the rendering is a diagnostic, not an observable output, so it neither needs "
                            + "nor reproduces the whole account identifier")
                    .doesNotContain("00000000011");
            assertThat(rendered).contains("0011");
            assertThat(rendered)
                    .as("the balance is withheld rather than printed")
                    .doesNotContain("+0000000019.40");
            assertThat(rendered).doesNotContain("0000000000000001", "0000000000000010",
                    "0000000000000007");
        }

        @Test
        @DisplayName("toString is total - an empty response renders without throwing")
        void toStringIsTotalForAnEmptyResponse() {
            BillPaymentResponse empty = new BillPaymentResponse();

            assertThatCode(empty::toString).doesNotThrowAnyException();
            assertThat(empty.toString()).startsWith("BillPaymentResponse[").endsWith("]");
            assertThat(empty.toString()).contains("cursorField=" + CursorField.NONE.name());
            assertThat(empty.toString()).contains("actIdIn=").contains("curBal=");
        }

        @Test
        @DisplayName("the no-argument constructor is public, so Jackson binds with no ceremony")
        void theNoArgumentConstructorIsPublic() throws Exception {
            assertThat(BillPaymentResponse.class.getDeclaredConstructors()).hasSize(1);
            assertThat(Modifier.isPublic(
                    BillPaymentResponse.class.getDeclaredConstructor().getModifiers())).isTrue();
            assertThatCode(() -> BillPaymentResponse.class.getDeclaredConstructor().newInstance())
                    .doesNotThrowAnyException();
        }

        private void assertOnlyChange(Consumer<BillPaymentResponse> mutation,
                String mutatedMember) throws Exception {
            BillPaymentResponse pristine = canonical();
            BillPaymentResponse mutated = canonical();

            mutation.accept(mutated);

            for (String member : MAP_MEMBER_WIDTHS.keySet()) {
                assertThat(get(mutated, member))
                        .as("changing %s must not disturb %s", mutatedMember, member)
                        .isEqualTo(get(pristine, member));
            }
            Map<String, Object> before = carriedValues(pristine);
            Map<String, Object> after = carriedValues(mutated);
            for (Map.Entry<String, Object> entry : before.entrySet()) {
                if (entry.getKey().equals(mutatedMember)) {
                    assertThat(after.get(entry.getKey()))
                            .as("%s must actually change", mutatedMember)
                            .isNotEqualTo(entry.getValue());
                } else {
                    assertThat(after.get(entry.getKey()))
                            .as("changing %s must not disturb %s", mutatedMember, entry.getKey())
                            .isEqualTo(entry.getValue());
                }
            }
        }

        private Map<String, Object> carriedValues(BillPaymentResponse response) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("cursorField", response.getCursorField());
            values.put("messageHighlight", response.getMessageHighlight());
            values.put("navigationContext", response.getNavigationContext());
            values.put("nextProgram", response.getNextProgram());
            values.put("nextMapset", response.getNextMapset());
            values.put("nextMap", response.getNextMap());
            values.put("trnIdFirst", response.getTrnIdFirst());
            values.put("trnIdLast", response.getTrnIdLast());
            values.put("pageNum", response.getPageNum());
            values.put("nextPageFlg", response.getNextPageFlg());
            values.put("trnSelFlg", response.getTrnSelFlg());
            values.put("trnSelected", response.getTrnSelected());
            return values;
        }

        private void rejectSessionType(String where, Class<?> type, List<String> offending) {
            String name = type.getName();
            if (name.startsWith("jakarta.servlet") || name.startsWith("javax.servlet")
                    || name.endsWith("HttpSession") || ThreadLocal.class.isAssignableFrom(type)) {
                offending.add(where + " is " + name);
            }
        }
    }

    @Nested
    @DisplayName("the published member order is the order app/cpy-bms/COBIL00.CPY declares")
    class BmsSerialisationOrder {
        private static final List<String> MAP_PROJECTION = List.of(
                "trnname", "title01", "curdate", "pgmname", "title02", "curtime", "actidin",
                "curbal", "confirm", "errmsg");

        private static final List<String> TRANSPORT_EXTENSIONS = List.of(
                "navigationContext", "nextProgram", "nextMapset", "nextMap", "trnIdFirst",
                "trnIdLast", "pageNum", "nextPageFlg", "trnSelFlg", "trnSelected");

        private static final List<String> PUBLISHED_ORDER =
                joined(MAP_PROJECTION, TRANSPORT_EXTENSIONS);

        private static List<String> joined(List<String> first, List<String> second) {
            List<String> all = new ArrayList<>(first);
            all.addAll(second);
            return List.copyOf(all);
        }

        private static List<String> publishedMembers() {
            JsonNode body = new ObjectMapper().valueToTree(new BillPaymentResponse());
            List<String> published = new ArrayList<>();
            body.fieldNames().forEachRemaining(published::add);
            return published;
        }

        @Test
        @DisplayName("every member is published exactly once, in exactly that order")
        void theOrderIsTheMapsOwnOrder() {
            assertThat(publishedMembers()).containsExactlyElementsOf(PUBLISHED_ORDER);
        }

        @Test
        @DisplayName("the map projection leads and the transport extensions follow it")
        void theMapProjectionLeadsAndTransportFollows() {
            List<String> published = publishedMembers();
            assertThat(published.subList(0, MAP_PROJECTION.size()))
                    .containsExactlyElementsOf(MAP_PROJECTION);
            assertThat(published.subList(MAP_PROJECTION.size(), published.size()))
                    .containsExactlyElementsOf(TRANSPORT_EXTENSIONS);
        }

        @Test
        @DisplayName("the order is declared on the type, so it cannot be derived from reflection")
        void theOrderIsDeclaredAndNotDerived() {
            JsonPropertyOrder declared = BillPaymentResponse.class.getAnnotation(JsonPropertyOrder.class);
            assertThat(declared).as("the published order must be fixed by annotation").isNotNull();
            assertThat(declared.value()).containsExactlyElementsOf(PUBLISHED_ORDER);
        }
    }
}

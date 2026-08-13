package com.vsergeychik.carddemo.user.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.user.model.SecUserRecord;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for {@link UserUpdateResponse} - the outbound payload of
 * {@code PUT /api/users/&#123;userId&#125;}, CICS transaction {@code CU02}, program
 * {@code app/cbl/COUSR02C.cbl}, map {@code COUSR2A} of mapset {@code COUSR02}.
 */
@DisplayName("UserUpdateResponse - the COUSR02 (CU02) update-user outbound payload")
class UserUpdateResponseTest {
    private static final Charset MAP_CHARSET = StandardCharsets.US_ASCII;

    private static final String MAP_NAME = "COUSR2A";

    private static final String MAPSET_NAME = "COUSR02";

    private static final String TRANSACTION_ID = "CU02";

    private static final String PROGRAM_NAME = "COUSR02C";

    private static final String INPUT_GROUP_NAME = "COUSR2AI";

    private static final String OUTPUT_GROUP_NAME = "COUSR2AO";

    private static final String CSD_TRANSACTION_BINDING = TRANSACTION_ID + "->" + PROGRAM_NAME;

    private static final int DFHMDF_TOTAL = 29;

    private static final int DFHMDF_NAMED = 12;

    private static final int DFHMDF_UNLABELLED = DFHMDF_TOTAL - DFHMDF_NAMED;

    private static final int COSGN00_DFHMDF_NAMED = 11;

    private static final int COSGN00_RESPONSE_MEMBERS = 10;

    private static final int COSGN00C_PASSWDO_REFERENCES = 0;

    private static final int COUSR01_DFHMDF_NAMED = 12;

    private static final int COUSR03_DFHMDF_NAMED = 11;

    private static final List<String> SCREEN_FIELDS = List.of("TRNNAME",
            "TITLE01",
            "CURDATE",
            "PGMNAME",
            "TITLE02",
            "CURTIME",
            "USRIDIN",
            "FNAME",
            "LNAME",
            "PASSWD",
            "USRTYPE",
            "ERRMSG");

    private static final List<String> OUTPUT_MAP_ITEMS =
            SCREEN_FIELDS.stream().map(field -> field + "O").toList();

    private static final List<String> INPUT_MAP_ITEMS =
            SCREEN_FIELDS.stream().map(field -> field + "I").toList();

    private static final List<Integer> OUTPUT_ITEM_LINES =
            List.of(98, 104, 110, 116, 122, 128, 134, 140, 146, 152, 158, 164);

    private static final List<Integer> DECLARED_WIDTHS =
            List.of(4, 40, 8, 8, 40, 8, 8, 20, 20, 8, 1, 78);

    private static final List<String> MAP_MEMBERS = List.of("trnName",
            "title01",
            "curDate",
            "pgmName",
            "title02",
            "curTime",
            "usrIdIn",
            "fName",
            "lName",
            "passwd",
            "usrType",
            "errMsg");

    private static String wireNameOf(String member) {
        return MAP_MEMBERS.contains(member) ? member.toLowerCase(Locale.ROOT) : member;
    }

    private static List<String> wireNamesOf(List<String> members) {
        return members.stream().map(UserUpdateResponseTest::wireNameOf).toList();
    }

    private static final List<String> STATE_MEMBERS =
            List.of("navigationContext", "nextProgram", "nextMapset", "nextMap", "cu02Info");

    private static final int COMPONENT_COUNT = DFHMDF_NAMED + 5;

    private static final int TIOAPFX_PREFIX_LENGTH = 12;

    private static final int LENGTH_ITEM_LENGTH = 2;

    private static final int ATTRIBUTE_ITEM_LENGTH = 1;

    private static final int INPUT_FILLER_LENGTH = 4;

    private static final int OUTPUT_FILLER_LENGTH = 3;

    private static final List<String> ATTRIBUTE_SUFFIXES = List.of("C", "P", "H", "V");

    private static final int INPUT_PREFIX_LENGTH =
            LENGTH_ITEM_LENGTH + ATTRIBUTE_ITEM_LENGTH + INPUT_FILLER_LENGTH;

    private static final int OUTPUT_PREFIX_LENGTH =
            OUTPUT_FILLER_LENGTH + ATTRIBUTE_SUFFIXES.size() * ATTRIBUTE_ITEM_LENGTH;

    private static final int PAYLOAD_WIDTH_TOTAL = 243;

    private static final int SYMBOLIC_MAP_LENGTH = 339;

    private static final int WS_MESSAGE_LENGTH = 80;

    private static final int PER_FIELD_REDEFINES = 12;

    private static final int GROUP_LEVEL_REDEFINES = 1;

    private static final int COPYBOOK_REDEFINES_TOTAL = PER_FIELD_REDEFINES + GROUP_LEVEL_REDEFINES;

    private static final int PACKAGE_REDEFINES_TOTAL = 110;

    private static final int PACKAGE_GROUP_LEVEL_REDEFINES = 5;

    private static final int PROGRAM_REDEFINES_TOTAL = 0;

    private static final int COMMAREA_LENGTH = 160;

    private static final int CU02_EXTENSION_LENGTH = 34;

    private static final int CU02_COMMAREA_LENGTH = COMMAREA_LENGTH + CU02_EXTENSION_LENGTH;

    private static final List<String> CU02_ITEM_NAMES = List.of("CDEMO-CU02-USRID-FIRST",
            "CDEMO-CU02-USRID-LAST",
            "CDEMO-CU02-PAGE-NUM",
            "CDEMO-CU02-NEXT-PAGE-FLG",
            "CDEMO-CU02-USR-SEL-FLG",
            "CDEMO-CU02-USR-SELECTED");

    private static final List<Integer> CU02_ITEM_WIDTHS = List.of(8, 8, 8, 1, 1, 8);

    private static final List<String> COUSR01_SCREEN_FIELDS = List.of("TRNNAME",
            "TITLE01",
            "CURDATE",
            "PGMNAME",
            "TITLE02",
            "CURTIME",
            "FNAME",
            "LNAME",
            "USERID",
            "PASSWD",
            "USRTYPE",
            "ERRMSG");

    private static final String NEXT_PAGE_YES = "Y";

    private static final String NEXT_PAGE_NO = "N";

    private static final String NEXT_PAGE_NEITHER = " ";

    private static final int SEC_USER_DATA_LENGTH = 80;

    private static final List<String> SEC_USER_ITEMS = List.of("SEC-USR-ID",
            "SEC-USR-FNAME",
            "SEC-USR-LNAME",
            "SEC-USR-PWD",
            "SEC-USR-TYPE");

    private static final List<Integer> SEC_USER_OFFSETS = List.of(0, 8, 28, 48, 56);

    private static final List<Integer> SEC_USER_WIDTHS = List.of(8, 20, 20, 8, 1);

    private static final List<String> SEC_USER_TARGET_MEMBERS =
            List.of("usrIdIn", "fName", "lName", "passwd", "usrType");

    private static final int SEC_USER_FILLER_OFFSET = 57;

    private static final String MSG_USER_ID_EMPTY = "User ID can NOT be empty...";

    private static final String MSG_FIRST_NAME_EMPTY = "First Name can NOT be empty...";

    private static final String MSG_LAST_NAME_EMPTY = "Last Name can NOT be empty...";

    private static final String MSG_PASSWORD_EMPTY = "Password can NOT be empty...";

    private static final String MSG_USER_TYPE_EMPTY = "User Type can NOT be empty...";

    private static final List<String> BLANK_FIELD_MESSAGES = List.of(MSG_USER_ID_EMPTY,
            MSG_FIRST_NAME_EMPTY,
            MSG_LAST_NAME_EMPTY,
            MSG_PASSWORD_EMPTY,
            MSG_USER_TYPE_EMPTY);

    private static final String MSG_PLEASE_MODIFY = "Please modify to update ...";

    private static final String MSG_UPDATED_PREFIX = "User ";

    private static final String MSG_UPDATED_SUFFIX = " has been updated ...";

    private static final String MSG_PRESS_PF5 = "Press PF5 key to save your updates ...";

    private static final String MSG_USER_NOT_FOUND = "User ID NOT found...";

    private static final String MSG_UNABLE_TO_LOOKUP = "Unable to lookup User...";

    private static final String MSG_UNABLE_TO_UPDATE = "Unable to Update User...";

    private static final Instant FIXED_INSTANT = Instant.parse("2022-07-19T23:12:34Z");

    private static final String EXPECTED_CURDATE = "07/19/22";

    private static final String EXPECTED_CURTIME = "23:12:34";

    private static final String PASSWD_FIXTURE = "PW-FAKE1";

    private static final String SHORT_PASSWD_FIXTURE = "PW-2";

    /**
     * The {@code MOVE LOW-VALUES TO COUSR2AO} image of the field: eight {@code x'00'} bytes.
     *
     * <p>It is not whitespace to Java, which is why the blank predicate has to name both forms rather
     * than calling {@code isBlank()}.
     */
    private static final String LOW_VALUES_PASSWD = "\u0000".repeat(8);

    /** An eight-character user id, filling {@code SEC-USR-ID PIC X(08)} exactly. */
    private static final String USER_ID_FIXTURE = "USER0001";

    private static final String SHORT_USER_ID_FIXTURE = "ADMIN";

    private static final int PASSWD_DECLARED_WIDTH = 8;

    private static final FixedWidthRecord.RecordLayout INPUT_VIEW_LAYOUT = inputViewLayout();

    private static final FixedWidthRecord.RecordLayout OUTPUT_VIEW_LAYOUT = outputViewLayout();

    /** The complete set of JSON member names this payload accepts inbound: the twelve plus the five. */
    private static final Set<String> EXPECTED_JSON_MEMBERS = expectedJsonMembers();

    /**
     * The one member whose component is {@code @JsonIgnore}d: {@code passwd}.
     *
     * <p>{@code app/bms/COUSR02.bms:129-134} declares {@code PASSWD} with {@code ATTRB=(..,DRK)}, and
     * JSON has no {@code DRK}. So the span stays on the model - {@link UserUpdateResponse#passwd()} and
     * {@link UserUpdateResponse#fieldValues()} both report it in full, which is what the parity
     * fingerprint compares - while what crosses the wire under this name is
     * {@link UserUpdateResponse#passwdOnTheWire()}: the fixed non-secret marker
     * {@link UserUpdateResponse#PASSWD_UNCHANGED}, which a client echoes back to mean "unchanged".
     */
    private static final String WRITE_ONLY_MEMBER = "passwd";

    /**
     * The member names the payload actually emits: {@link #EXPECTED_JSON_MEMBERS} in full, the
     * credential's name included, because the marker is published under it.
     */
    private static final Set<String> SERIALISED_JSON_MEMBERS = serialisedJsonMembers();

    /**
     * Names that must never appear in the serialised form: the four output attribute items, the three
     * input metadata items, and the two {@code FILLER} spans - for all twelve fields.
     */
    private static final Set<String> FORBIDDEN_JSON_MEMBERS = forbiddenJsonMembers();

    private static FixedWidthRecord.RecordLayout inputViewLayout() {
        List<FixedWidthRecord.FieldSpan> spans = new ArrayList<>();
        spans.add(FixedWidthRecord.FieldSpan.filler(0, TIOAPFX_PREFIX_LENGTH));
        int cursor = TIOAPFX_PREFIX_LENGTH;
        for (int index = 0; index < DFHMDF_NAMED; index++) {
            String field = SCREEN_FIELDS.get(index);
            spans.add(FixedWidthRecord.FieldSpan.filler(cursor, LENGTH_ITEM_LENGTH));
            cursor += LENGTH_ITEM_LENGTH;
            FixedWidthRecord.FieldSpan flag = FixedWidthRecord.FieldSpan.alphanumeric(
                    field + "F", cursor, ATTRIBUTE_ITEM_LENGTH);
            spans.add(flag);
            spans.add(flag.redefinedAs(field + "A", FixedWidthRecord.PictureKind.ALPHANUMERIC));
            cursor += ATTRIBUTE_ITEM_LENGTH;
            spans.add(FixedWidthRecord.FieldSpan.filler(cursor, INPUT_FILLER_LENGTH));
            cursor += INPUT_FILLER_LENGTH;
            spans.add(FixedWidthRecord.FieldSpan.alphanumeric(
                    INPUT_MAP_ITEMS.get(index), cursor, DECLARED_WIDTHS.get(index)));
            cursor += DECLARED_WIDTHS.get(index);
        }
        return FixedWidthRecord.RecordLayout.of(SYMBOLIC_MAP_LENGTH,
                spans.toArray(FixedWidthRecord.FieldSpan[]::new));
    }

    private static FixedWidthRecord.RecordLayout outputViewLayout() {
        List<FixedWidthRecord.FieldSpan> spans = new ArrayList<>();
        spans.add(FixedWidthRecord.FieldSpan.filler(0, TIOAPFX_PREFIX_LENGTH));
        int cursor = TIOAPFX_PREFIX_LENGTH;
        for (int index = 0; index < DFHMDF_NAMED; index++) {
            String field = SCREEN_FIELDS.get(index);
            spans.add(FixedWidthRecord.FieldSpan.filler(cursor, OUTPUT_FILLER_LENGTH));
            cursor += OUTPUT_FILLER_LENGTH;
            for (String suffix : ATTRIBUTE_SUFFIXES) {
                spans.add(FixedWidthRecord.FieldSpan.alphanumeric(
                        field + suffix, cursor, ATTRIBUTE_ITEM_LENGTH));
                cursor += ATTRIBUTE_ITEM_LENGTH;
            }
            spans.add(FixedWidthRecord.FieldSpan.alphanumeric(
                    OUTPUT_MAP_ITEMS.get(index), cursor, DECLARED_WIDTHS.get(index)));
            cursor += DECLARED_WIDTHS.get(index);
        }
        spans.add(FixedWidthRecord.FieldSpan.redefining(OUTPUT_GROUP_NAME, 0, SYMBOLIC_MAP_LENGTH,
                FixedWidthRecord.PictureKind.ALPHANUMERIC));
        return FixedWidthRecord.RecordLayout.of(SYMBOLIC_MAP_LENGTH,
                spans.toArray(FixedWidthRecord.FieldSpan[]::new));
    }

    private static Set<String> expectedJsonMembers() {
        Set<String> members = new LinkedHashSet<>(wireNamesOf(MAP_MEMBERS));
        members.addAll(STATE_MEMBERS);
        return Set.copyOf(members);
    }

    private static Set<String> serialisedJsonMembers() {
        Set<String> members = new LinkedHashSet<>(expectedJsonMembers());
        if (!members.contains(WRITE_ONLY_MEMBER)) {
            throw new AssertionError(WRITE_ONLY_MEMBER + " is not one of the declared members, so "
                    + "asserting that the marker is published under its name would assert nothing");
        }
        return Set.copyOf(members);
    }

    private static Set<String> forbiddenJsonMembers() {
        Set<String> forbidden = new LinkedHashSet<>();
        for (String field : SCREEN_FIELDS) {
            for (String suffix : ATTRIBUTE_SUFFIXES) {
                forbidden.add(field + suffix);
            }
            forbidden.add(field + "L");
            forbidden.add(field + "F");
            forbidden.add(field + "A");
        }
        forbidden.add("FILLER");
        return Set.copyOf(forbidden);
    }

    private static FixedWidthCodec codec() {
        return new FixedWidthCodec(MAP_CHARSET);
    }

    private static ObjectMapper webConfigEquivalentMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        mapper.configure(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN, true);
        mapper.disable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT);
        return mapper;
    }

    private static UserUpdateResponse afterSuccessfulRead() {
        return UserUpdateResponse.blank()
                .withTrnName(TRANSACTION_ID)
                .withPgmName(PROGRAM_NAME)
                .withTitle01(ScreenTitles.CCDA_TITLE01)
                .withTitle02(ScreenTitles.CCDA_TITLE02)
                .withCurDate(EXPECTED_CURDATE)
                .withCurTime(EXPECTED_CURTIME)
                .withUsrIdIn(USER_ID_FIXTURE)
                .withFName("John")
                .withLName("Doe")
                .withPasswd(PASSWD_FIXTURE)
                .withUsrType("U");
    }

    private static Method accessorOf(String componentName) {
        return Arrays.stream(UserUpdateResponse.class.getRecordComponents())
                .filter(component -> component.getName().equals(componentName))
                .map(RecordComponent::getAccessor)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        componentName + " is not a component of UserUpdateResponse"));
    }

    private static Set<String> annotationsReachableFrom(String componentName) {
        Set<String> found = new LinkedHashSet<>();
        RecordComponent component = Arrays.stream(UserUpdateResponse.class.getRecordComponents())
                .filter(candidate -> candidate.getName().equals(componentName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        componentName + " is not a component of UserUpdateResponse"));
        collectNames(found, component.getAnnotations());
        collectNames(found, component.getAccessor().getAnnotations());
        try {
            Field field = UserUpdateResponse.class.getDeclaredField(componentName);
            collectNames(found, field.getAnnotations());
        } catch (NoSuchFieldException neverHappensForARecordComponent) {
            throw new AssertionError("a record component always has a backing field",
                    neverHappensForARecordComponent);
        }
        for (Constructor<?> constructor : UserUpdateResponse.class.getDeclaredConstructors()) {
            for (Parameter parameter : constructor.getParameters()) {
                if (parameter.getName().equals(componentName)) {
                    collectNames(found, parameter.getAnnotations());
                }
            }
        }
        return found;
    }

    private static void collectNames(Set<String> target, Annotation[] annotations) {
        for (Annotation annotation : annotations) {
            target.add(annotation.annotationType().getSimpleName());
        }
    }

    /**
     * The single {@link JsonProperty} that governs one component's wire projection.
     *
     * <p>Searched across the same four surfaces {@link #annotationsReachableFrom(String)} inspects, and
     * every one found is required to agree, so declaring one access mode on the component and a
     * different one on the accessor cannot pass unnoticed.
     *
     * @param componentName the component to inspect
     * @return the annotation that governs it
     */
    private static JsonProperty appliedJsonPropertyOn(String componentName) {
        List<JsonProperty> found = new ArrayList<>();
        RecordComponent component = Arrays.stream(UserUpdateResponse.class.getRecordComponents())
                .filter(candidate -> candidate.getName().equals(componentName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        componentName + " is not a component of UserUpdateResponse"));
        collectJsonProperty(found, component.getAnnotation(JsonProperty.class));
        collectJsonProperty(found, component.getAccessor().getAnnotation(JsonProperty.class));
        try {
            collectJsonProperty(found, UserUpdateResponse.class.getDeclaredField(componentName)
                    .getAnnotation(JsonProperty.class));
        } catch (NoSuchFieldException neverHappensForARecordComponent) {
            throw new AssertionError("a record component always has a backing field",
                    neverHappensForARecordComponent);
        }
        for (Constructor<?> constructor : UserUpdateResponse.class.getDeclaredConstructors()) {
            for (Parameter parameter : constructor.getParameters()) {
                if (parameter.getName().equals(componentName)) {
                    collectJsonProperty(found, parameter.getAnnotation(JsonProperty.class));
                }
            }
        }
        assertThat(found)
                .as("%s carries a @JsonProperty on at least one of its four surfaces", componentName)
                .isNotEmpty();
        assertThat(found.stream().map(JsonProperty::access).distinct().toList())
                .as("and every surface declares the same access mode")
                .hasSize(1);
        assertThat(found.stream().map(JsonProperty::value).distinct().toList())
                .as("and the same wire name")
                .hasSize(1);
        return found.get(0);
    }

    private static void collectJsonProperty(List<JsonProperty> target, JsonProperty annotation) {
        if (annotation != null) {
            target.add(annotation);
        }
    }

    /**
     * What a sending item contributes to a {@code STRING} statement under
     * {@code DELIMITED BY SPACE}: everything before its first space.
     *
     * <p>This is <strong>not</strong> a stand-in for a COBOL {@code MOVE} - that rule lives in
     * {@link FixedWidthCodec#movePicX(String, int)} and is used as such everywhere below.
     * {@code DELIMITED BY SPACE} is a different construct with a different rule, and modelling it needs
     * a first-space scan, which is what this is. Without it, {@code 'ADMIN   '} would contribute all
     * eight characters and the composed message would read {@code 'User ADMIN    has been updated ...'}
     * with three stray spaces.
     *
     * @param value the sending item, at its declared width
     * @return the characters up to but excluding the first space, or all of them if there is none
     */
    private static String delimitedBySpace(String value) {
        int firstSpace = value.indexOf(' ');
        return firstSpace < 0 ? value : value.substring(0, firstSpace);
    }

    private static String errMsgImageOf(String text) {
        FixedWidthCodec codec = codec();
        String wsMessage = codec.movePicX(text, WS_MESSAGE_LENGTH);
        return codec.movePicX(wsMessage, UserUpdateResponse.ERR_MSG_LENGTH);
    }

    @Nested
    @DisplayName("Projection of 01 COUSR2AO - twelve map members, in copybook order")
    class MapProjection {
        @Test
        @DisplayName("twelve of the twenty-nine DFHMDF definitions are fields; seventeen are furniture")
        void twelveOfTwentyNineDefinitionsAreFields() {
            assertThat(DFHMDF_NAMED)
                    .as("the name-labelled DFHMDF definitions of app/bms/COUSR02.bms")
                    .isEqualTo(12);
            assertThat(DFHMDF_UNLABELLED)
                    .as("literal INITIAL text and three LENGTH=0 stoppers - not fields")
                    .isEqualTo(17);
            assertThat(DFHMDF_NAMED + DFHMDF_UNLABELLED).isEqualTo(DFHMDF_TOTAL);
            assertThat(UserUpdateResponse.MAP_FIELD_COUNT)
                    .as("the payload projects the labelled fields and nothing else")
                    .isEqualTo(DFHMDF_NAMED);
        }

        @Test
        @DisplayName("the record declares seventeen components: the twelve plus five state carriers")
        void theComponentCountIsTwelvePlusFive() {
            RecordComponent[] components = UserUpdateResponse.class.getRecordComponents();

            assertThat(components)
                    .as("UserUpdateResponse is a record, so its components are its payload")
                    .isNotNull()
                    .hasSize(COMPONENT_COUNT);
            assertThat(COMPONENT_COUNT)
                    .as("12 map members + navigationContext, nextProgram, nextMapset, nextMap, cu02Info")
                    .isEqualTo(17);
            assertThat(Arrays.stream(components).map(RecordComponent::getName).toList())
                    .as("the twelve map members come first, in screen order, then the five carriers")
                    .containsExactlyElementsOf(
                            Stream.concat(MAP_MEMBERS.stream(), STATE_MEMBERS.stream())
                                    .toList());
        }

        @ParameterizedTest(name = "[{index}] {0} -> {1}")
        @CsvSource({"TRNNAMEO,trnName", "TITLE01O,title01", "CURDATEO,curDate", "PGMNAMEO,pgmName",
            "TITLE02O,title02", "CURTIMEO,curTime", "USRIDINO,usrIdIn", "FNAMEO,fName",
            "LNAMEO,lName", "PASSWDO,passwd", "USRTYPEO,usrType", "ERRMSGO,errMsg"})
        @DisplayName("every xxxO item has exactly one member, and it is a String")
        void everyOutputItemHasOneStringMember(String cobolItem, String memberName) {
            int index = OUTPUT_MAP_ITEMS.indexOf(cobolItem);

            assertThat(index)
                    .as("%s is declared at app/cpy-bms/COUSR02.CPY:%d", cobolItem,
                            OUTPUT_ITEM_LINES.get(Math.max(index, 0)))
                    .isNotNegative();
            assertThat(MAP_MEMBERS.get(index))
                    .as("%s projects to %s, at the same position in both lists", cobolItem, memberName)
                    .isEqualTo(memberName);
            assertThat(accessorOf(memberName).getReturnType())
                    .as("%s is PIC X(%d), so the member is a String and never a numeric type",
                            cobolItem, DECLARED_WIDTHS.get(index))
                    .isEqualTo(String.class);
        }

        @Test
        @DisplayName("MAP_FIELD_NAMES is the twelve xxxO names, in screen order, and unmodifiable")
        void mapFieldNamesIsTheTwelveOutputItems() {
            assertThat(UserUpdateResponse.MAP_FIELD_NAMES)
                    .containsExactlyElementsOf(OUTPUT_MAP_ITEMS)
                    .hasSize(UserUpdateResponse.MAP_FIELD_COUNT);
            assertThatExceptionOfTypeUnsupported(
                    () -> UserUpdateResponse.MAP_FIELD_NAMES.add("SOMETHINGO"));
        }

        @Test
        @DisplayName("fieldValues() keys on the xxxO names and preserves screen declaration order")
        void fieldValuesIsScreenOrdered() {
            Map<String, String> values = afterSuccessfulRead().fieldValues();

            assertThat(values).hasSize(UserUpdateResponse.MAP_FIELD_COUNT);
            assertThat(values.keySet())
                    .as("declaration order, not an arbitrary one: a field-by-field diff walks this")
                    .containsExactlyElementsOf(OUTPUT_MAP_ITEMS);
            assertThat(values.get(UserUpdateResponse.TRN_NAME_FIELD)).isEqualTo(TRANSACTION_ID);
            assertThat(values.get(UserUpdateResponse.PGM_NAME_FIELD)).isEqualTo(PROGRAM_NAME);
        }

        @ParameterizedTest(name = "[{index}] {0} is PIC X({1})")
        @CsvSource({"TRNNAMEO,4", "TITLE01O,40", "CURDATEO,8", "PGMNAMEO,8", "TITLE02O,40",
            "CURTIMEO,8", "USRIDINO,8", "FNAMEO,20", "LNAMEO,20", "PASSWDO,8", "USRTYPEO,1",
            "ERRMSGO,78"})
        @DisplayName("each declared width is the one the copybook states, and the layout agrees")
        void eachDeclaredWidthIsTheCopybooks(String cobolItem, int declaredWidth) {
            int index = OUTPUT_MAP_ITEMS.indexOf(cobolItem);

            assertThat(DECLARED_WIDTHS.get(index))
                    .as("%s at app/cpy-bms/COUSR02.CPY:%d", cobolItem, OUTPUT_ITEM_LINES.get(index))
                    .isEqualTo(declaredWidth);
            assertThat(OUTPUT_VIEW_LAYOUT.span(cobolItem).length())
                    .as("and the rebuilt output view places %s at that same width", cobolItem)
                    .isEqualTo(declaredWidth);
            assertThat(UserUpdateResponse.blank().value(cobolItem))
                    .as("blank() renders every field as its declared run of spaces")
                    .isEqualTo(" ".repeat(declaredWidth));
        }

        @Test
        @DisplayName("the screen identity constants are the program's own literals")
        void theScreenIdentityConstantsAreTheProgramsOwn() {
            assertThat(UserUpdateResponse.TRANSACTION_ID)
                    .as("WS-TRANID at app/cbl/COUSR02C.cbl:37")
                    .isEqualTo(TRANSACTION_ID)
                    .hasSize(UserUpdateResponse.TRN_NAME_LENGTH);
            assertThat(UserUpdateResponse.PROGRAM_NAME)
                    .as("WS-PGMNAME at app/cbl/COUSR02C.cbl:36")
                    .isEqualTo(PROGRAM_NAME)
                    .hasSize(UserUpdateResponse.PGM_NAME_LENGTH);
            assertThat(UserUpdateResponse.MAP_NAME)
                    .as("MAP('COUSR2A') at app/cbl/COUSR02C.cbl:273")
                    .isEqualTo(MAP_NAME);
            assertThat(UserUpdateResponse.MAPSET_NAME)
                    .as("MAPSET('COUSR02') at app/cbl/COUSR02C.cbl:274")
                    .isEqualTo(MAPSET_NAME);
            assertThat(UserUpdateResponse.GROUP_NAME)
                    .as("01 COUSR2AO at app/cpy-bms/COUSR02.CPY:91 - the map name plus 'O'")
                    .isEqualTo(OUTPUT_GROUP_NAME)
                    .isEqualTo(MAP_NAME + FieldAttributeSetter.OUTPUT_MAP_SUFFIX);
            assertThat(INPUT_GROUP_NAME)
                    .as("and the input view is the same map name plus 'I' - CPY:17")
                    .isEqualTo(MAP_NAME + "I");
        }

        @Test
        @DisplayName("transaction CU02 is bound to program COUSR02C by the CSD, not by convention")
        void theCsdBindsTheTransactionToTheProgram() {
            assertThat(CSD_TRANSACTION_BINDING)
                    .as("app/csd/CARDDEMO.CSD:469-470")
                    .isEqualTo(UserUpdateResponse.TRANSACTION_ID + "->"
                            + UserUpdateResponse.PROGRAM_NAME);
        }

        @Test
        @DisplayName("title01 and title02 carry the forty-character CCDA titles, padding included")
        void theTitlesAreTheFortyCharacterScreenTitles() {
            UserUpdateResponse response = afterSuccessfulRead();

            assertThat(ScreenTitles.CCDA_TITLE01)
                    .as("CCDA-TITLE01, moved at app/cbl/COUSR02C.cbl:300")
                    .hasSize(ScreenTitles.TITLE_LENGTH)
                    .hasSize(UserUpdateResponse.TITLE01_LENGTH);
            assertThat(ScreenTitles.CCDA_TITLE02)
                    .as("CCDA-TITLE02, moved at line 301")
                    .hasSize(ScreenTitles.TITLE_LENGTH)
                    .hasSize(UserUpdateResponse.TITLE02_LENGTH);
            assertThat(response.title01()).isEqualTo(ScreenTitles.CCDA_TITLE01);
            assertThat(response.title02()).isEqualTo(ScreenTitles.CCDA_TITLE02);
        }

        @Test
        @DisplayName("ScreenTitles.CCDA_THANK_YOU is not SystemMessages.CCDA_MSG_THANK_YOU: 40 vs 50")
        void theTwoThankYouLiteralsAreDifferentThings() {
            assertThat(ScreenTitles.CCDA_THANK_YOU)
                    .hasSize(ScreenTitles.TITLE_LENGTH)
                    .hasSize(40);
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU)
                    .hasSize(SystemMessages.MESSAGE_LENGTH)
                    .hasSize(50)
                    .isNotEqualTo(ScreenTitles.CCDA_THANK_YOU);
            assertThat(SystemMessages.MESSAGE_LENGTH).isNotEqualTo(ScreenTitles.TITLE_LENGTH);
            assertThat(SystemMessages.CCDA_MSG_INVALID_KEY.length())
                    .as("the invalid-key text is the one this screen does use, at line 129, and it "
                            + "fits the 78-character message field")
                    .isLessThanOrEqualTo(UserUpdateResponse.ERR_MSG_LENGTH);
        }

        @ParameterizedTest(name = "[{index}] {0} PIC X -> {1}")
        @CsvSource({"SEC-USR-ID,usrIdIn", "SEC-USR-FNAME,fName", "SEC-USR-LNAME,lName",
            "SEC-USR-PWD,passwd", "SEC-USR-TYPE,usrType"})
        @DisplayName("the five stored-record widths match the map widths they are moved into")
        void theStoredRecordWidthsMatchTheMapWidths(String secItem, String memberName) {
            int recordIndex = SEC_USER_ITEMS.indexOf(secItem);
            int mapIndex = MAP_MEMBERS.indexOf(memberName);

            assertThat(recordIndex).as("%s is declared in app/cpy/CSUSR01Y.cpy:17-23", secItem)
                    .isNotNegative();
            assertThat(SEC_USER_TARGET_MEMBERS.get(recordIndex)).isEqualTo(memberName);
            assertThat(SEC_USER_WIDTHS.get(recordIndex))
                    .as("%s and %s are the same width, so the MOVE neither pads nor truncates",
                            secItem, OUTPUT_MAP_ITEMS.get(mapIndex))
                    .isEqualTo(DECLARED_WIDTHS.get(mapIndex));
        }

        @Test
        @DisplayName("SecUserRecord places the password at offset 48 of an eighty-byte record")
        void theStoredRecordGeometryIsTheCopybooks() {
            assertThat(SecUserRecord.RECORD_LENGTH)
                    .as("8+20+20+8+1+23, app/cpy/CSUSR01Y.cpy:18-23")
                    .isEqualTo(SEC_USER_DATA_LENGTH)
                    .isEqualTo(80);
            assertThat(List.of(SecUserRecord.SEC_USR_ID_OFFSET,
                    SecUserRecord.SEC_USR_FNAME_OFFSET,
                    SecUserRecord.SEC_USR_LNAME_OFFSET,
                    SecUserRecord.SEC_USR_PWD_OFFSET,
                    SecUserRecord.SEC_USR_TYPE_OFFSET))
                    .as("0, 8, 28, 48, 56 - the password sits at 48")
                    .containsExactlyElementsOf(SEC_USER_OFFSETS);
            assertThat(SecUserRecord.SEC_USR_PWD_OFFSET).isEqualTo(48);
            assertThat(SecUserRecord.SEC_USR_FILLER_OFFSET)
                    .as("SEC-USR-FILLER X(23) begins at 57 and runs to the end")
                    .isEqualTo(SEC_USER_FILLER_OFFSET);
            assertThat(SecUserRecord.SEC_USR_FILLER_OFFSET + SecUserRecord.SEC_USR_FILLER_LENGTH)
                    .isEqualTo(SecUserRecord.RECORD_LENGTH);
            assertThat(SecUserRecord.SEC_USR_PWD_LENGTH)
                    .as("and its width is the map field's width, so line 169 is a clean move")
                    .isEqualTo(UserUpdateResponse.PASSWD_LENGTH);
        }

        @Test
        @DisplayName("the header renders MM/DD/YY and HH:MM:SS from a fixed clock, never a live one")
        void theHeaderIsDrivenFromAFixedClock() {
            DateHeader header = DateHeader.from(codec(),
                    Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));

            assertThat(header.wsCurdateMmDdYy())
                    .as("MM/DD/YY, composed at app/cbl/COUSR02C.cbl:305-309")
                    .isEqualTo(EXPECTED_CURDATE)
                    .hasSize(UserUpdateResponse.CUR_DATE_LENGTH);
            assertThat(header.wsCurtimeHhMmSs())
                    .as("HH:MM:SS, composed at lines 311-315")
                    .isEqualTo(EXPECTED_CURTIME)
                    .hasSize(UserUpdateResponse.CUR_TIME_LENGTH);
            UserUpdateResponse response = UserUpdateResponse.blank()
                    .withCurDate(header.wsCurdateMmDdYy())
                    .withCurTime(header.wsCurtimeHhMmSs());
            assertThat(response.curDate()).isEqualTo(EXPECTED_CURDATE);
            assertThat(response.curTime()).isEqualTo(EXPECTED_CURTIME);
        }
    }

    @Nested
    @DisplayName("Cross-screen inversions - USRIDIN not USERID, and the identifier comes first")
    class CrossScreenInversions {
        @Test
        @DisplayName("the identifier member is usrIdIn, spelled for the field this map declares")
        void theIdentifierMemberIsUsrIdIn() {
            assertThat(UserUpdateResponse.USR_ID_IN_FIELD)
                    .as("USRIDINO at app/cpy-bms/COUSR02.CPY:134, USRIDIN at app/bms/COUSR02.bms:85")
                    .isEqualTo("USRIDINO");
            assertThat(MAP_MEMBERS)
                    .as("COUSR01 labels its equivalent field USERID; the two are not harmonised")
                    .contains("usrIdIn")
                    .doesNotContain("userId")
                    .doesNotContain("secUsrId");
            assertThat(Arrays.stream(UserUpdateResponse.class.getRecordComponents())
                    .map(RecordComponent::getName).toList())
                    .contains("usrIdIn")
                    .doesNotContain("userId");
        }

        @Test
        @DisplayName("usrIdIn is the seventh member, before fName and lName - the inverse of COUSR01")
        void theIdentifierPrecedesTheNames() {
            List<String> declared = Arrays.stream(UserUpdateResponse.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(declared.indexOf("usrIdIn"))
                    .as("seventh of the twelve map members, zero-based six")
                    .isEqualTo(6);
            assertThat(declared.indexOf("usrIdIn"))
                    .as("COUSR01 puts its identifier AFTER the names; this screen puts it before")
                    .isLessThan(declared.indexOf("fName"))
                    .isLessThan(declared.indexOf("lName"))
                    .isLessThan(declared.indexOf("passwd"));
            assertThat(UserUpdateResponse.MAP_FIELD_NAMES.indexOf(UserUpdateResponse.USR_ID_IN_FIELD))
                    .as("and the published order agrees with the declared order")
                    .isEqualTo(6);
        }

        @Test
        @DisplayName("the inversion is asserted against COUSR01's own order, not merely claimed")
        void theInversionIsAssertedAgainstTheOtherSideOfIt() {
            assertThat(COUSR01_SCREEN_FIELDS)
                    .as("app/bms/COUSR01.bms declares twelve fields, as this map does")
                    .hasSize(COUSR01_DFHMDF_NAMED)
                    .hasSameSizeAs(SCREEN_FIELDS);
            assertThat(COUSR01_SCREEN_FIELDS.indexOf("USERID"))
                    .as("COUSR01 declares its identifier ninth, after both name fields")
                    .isEqualTo(8)
                    .isGreaterThan(COUSR01_SCREEN_FIELDS.indexOf("FNAME"))
                    .isGreaterThan(COUSR01_SCREEN_FIELDS.indexOf("LNAME"));
            assertThat(SCREEN_FIELDS.indexOf("USRIDIN"))
                    .as("this map declares its identifier seventh, before both name fields - the "
                            + "inversion, stated from both sides")
                    .isEqualTo(6)
                    .isLessThan(SCREEN_FIELDS.indexOf("FNAME"))
                    .isLessThan(SCREEN_FIELDS.indexOf("LNAME"));
            assertThat(COUSR01_SCREEN_FIELDS)
                    .as("and the spellings differ: USERID there, USRIDIN here, deliberately not "
                            + "harmonised")
                    .contains("USERID")
                    .doesNotContain("USRIDIN");
            assertThat(SCREEN_FIELDS).contains("USRIDIN").doesNotContain("USERID");
            assertThat(COUSR01_SCREEN_FIELDS)
                    .as("PASSWD sits eleventh-from-last on both, which is why the field COUNT alone "
                            + "cannot tell the two responses apart - only the program can")
                    .contains("PASSWD");
        }

        @Test
        @DisplayName("a misspelling of the identifier field fails at the point of the mistake")
        void aMisspelledIdentifierFails() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> afterSuccessfulRead().value("USERIDO"))
                    .withMessageContaining(UserUpdateResponse.USR_ID_IN_FIELD);
            assertThat(afterSuccessfulRead().value(UserUpdateResponse.USR_ID_IN_FIELD))
                    .isEqualTo(USER_ID_FIXTURE);
        }
    }

    @Nested
    @DisplayName("Width traps - eight not nine, seventy-eight not eighty, and seven not eight")
    class WidthTraps {
        @Test
        @DisplayName("curTime is X(8) on this map; only COSGN00 declares a nine-character time")
        void theTimeFieldIsEightCharactersWide() {
            assertThat(UserUpdateResponse.CUR_TIME_LENGTH)
                    .as("CURTIMEO PIC X(8) at app/cpy-bms/COUSR02.CPY:128, LENGTH=8 at bms line 72")
                    .isEqualTo(8)
                    .isNotEqualTo(9);
            assertThat(DECLARED_WIDTHS.get(OUTPUT_MAP_ITEMS.indexOf("CURTIMEO"))).isEqualTo(8);
            assertThat(EXPECTED_CURTIME).hasSize(UserUpdateResponse.CUR_TIME_LENGTH);
            assertThatIllegalArgumentException()
                    .as("a nine-character time copied from COSGN00 is refused, not silently trimmed")
                    .isThrownBy(() -> UserUpdateResponse.blank().withCurTime("23:12:34 "))
                    .withMessageContaining(UserUpdateResponse.CUR_TIME_FIELD);
        }

        @Test
        @DisplayName("errMsg is X(78) while WS-MESSAGE is X(80), so line 270 discards two characters")
        void theMessageFieldNarrowsFromEightyToSeventyEight() {
            assertThat(UserUpdateResponse.ERR_MSG_LENGTH)
                    .as("ERRMSGO PIC X(78) at app/cpy-bms/COUSR02.CPY:164, LENGTH=78 at bms line 157")
                    .isEqualTo(78);
            assertThat(WS_MESSAGE_LENGTH)
                    .as("WS-MESSAGE PIC X(80) at app/cbl/COUSR02C.cbl:38")
                    .isEqualTo(80)
                    .isGreaterThan(UserUpdateResponse.ERR_MSG_LENGTH);
            assertThat(WS_MESSAGE_LENGTH - UserUpdateResponse.ERR_MSG_LENGTH)
                    .as("exactly two characters are lost on the right")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("the eighty-to-seventy-eight move truncates on the RIGHT, through the codec")
        void theNarrowingMoveTruncatesOnTheRight() {
            String wsMessage = "A".repeat(WS_MESSAGE_LENGTH - 2) + "YZ";
            assertThat(wsMessage).hasSize(WS_MESSAGE_LENGTH).endsWith("YZ");

            String moved = codec().movePicX(wsMessage, UserUpdateResponse.ERR_MSG_LENGTH);

            assertThat(moved)
                    .as("COBOL fills a PIC X receiver from the left and discards the overflow")
                    .hasSize(UserUpdateResponse.ERR_MSG_LENGTH)
                    .isEqualTo("A".repeat(UserUpdateResponse.ERR_MSG_LENGTH))
                    .doesNotContain("Y")
                    .doesNotContain("Z");
            assertThat(UserUpdateResponse.blank().withErrMsg(moved).errMsg()).isEqualTo(moved);
        }

        @Test
        @DisplayName("the payload itself never truncates: an over-wide message is refused")
        void thePayloadRefusesRatherThanTruncates() {
            String eighty = "B".repeat(WS_MESSAGE_LENGTH);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> UserUpdateResponse.blank().withErrMsg(eighty))
                    .withMessageContaining(UserUpdateResponse.ERR_MSG_FIELD)
                    .withMessageContaining("movePicX");
        }

        @Test
        @DisplayName("ERRMSG's map-declared colour is RED, so DFHGREEN is an override and DFHRED is not")
        void theMessageFieldsDeclaredColourIsRed() {
            assertThat(BmsAttributes.COLOUR_MNEMONICS.get(BmsAttributes.DFHRED))
                    .as("the declared default colour of ERRMSG")
                    .isEqualTo("DFHRED");
            assertThat(BmsAttributes.DFHGREEN)
                    .as("the success override at line 371 is a different byte from the default")
                    .isNotEqualTo(BmsAttributes.DFHRED);
            assertThat(BmsAttributes.DFHNEUTR)
                    .as("and the save prompt at line 338 is a third")
                    .isNotEqualTo(BmsAttributes.DFHRED)
                    .isNotEqualTo(BmsAttributes.DFHGREEN);
            assertThat(FieldAttributeSetter.COLOUR_ITEM_SUFFIX)
                    .as("all three are moved to ERRMSGC - the xxxC item is the colour item")
                    .isEqualTo("C");
            assertThat(ATTRIBUTE_SUFFIXES.get(0)).isEqualTo(FieldAttributeSetter.COLOUR_ITEM_SUFFIX);
        }

        @Test
        @DisplayName("nextMapset and nextMap are X(7), and this screen's own names are seven long")
        void theMapAndMapsetMembersAreSevenWide() {
            assertThat(UserUpdateResponse.NEXT_MAP_LENGTH)
                    .as("CDEMO-LAST-MAP PIC X(7) at app/cpy/COCOM01Y.cpy:43")
                    .isEqualTo(7)
                    .isEqualTo(NavigationContext.LAST_MAP_LENGTH);
            assertThat(UserUpdateResponse.NEXT_MAPSET_LENGTH)
                    .as("CDEMO-LAST-MAPSET PIC X(7) at app/cpy/COCOM01Y.cpy:44")
                    .isEqualTo(7)
                    .isEqualTo(NavigationContext.LAST_MAPSET_LENGTH);
            assertThat(MAP_NAME)
                    .as("COUSR2A is exactly seven characters - which is why X(7) is right, not X(8)")
                    .hasSize(UserUpdateResponse.NEXT_MAP_LENGTH);
            assertThat(MAPSET_NAME).hasSize(UserUpdateResponse.NEXT_MAPSET_LENGTH);
            assertThat(UserUpdateResponse.NEXT_PROGRAM_LENGTH)
                    .as("a program name really is eight - CDEMO-TO-PROGRAM PIC X(08), COCOM01Y:24")
                    .isEqualTo(8)
                    .isNotEqualTo(UserUpdateResponse.NEXT_MAP_LENGTH);
            assertThat(PROGRAM_NAME).hasSize(UserUpdateResponse.NEXT_PROGRAM_LENGTH);
        }

        @ParameterizedTest(name = "[{index}] {0} refuses one character too many")
        @CsvSource({"TRNNAMEO,4", "TITLE01O,40", "CURDATEO,8", "PGMNAMEO,8", "TITLE02O,40",
            "CURTIMEO,8", "USRIDINO,8", "FNAMEO,20", "LNAMEO,20", "PASSWDO,8", "USRTYPEO,1",
            "ERRMSGO,78"})
        @DisplayName("every field refuses a value wider than its PICTURE clause")
        void everyFieldRefusesAnOverWideValue(String cobolItem, int declaredWidth) {
            String tooLong = "X".repeat(declaredWidth + 1);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> withValue(cobolItem, tooLong))
                    .withMessageContaining(cobolItem)
                    .withMessageContaining("PIC X(" + declaredWidth + ")");
        }

        @ParameterizedTest(name = "[{index}] {0} accepts a short value unchanged")
        @ValueSource(strings = {"TITLE01O", "FNAMEO", "LNAMEO", "PASSWDO", "ERRMSGO"})
        @DisplayName("a value shorter than the declared width is stored as given, never padded here")
        void aShortValueIsStoredUnchanged(String cobolItem) {
            UserUpdateResponse response = withValue(cobolItem, "Q");

            assertThat(response.value(cobolItem)).isEqualTo("Q").hasSize(1);
            assertThat(codec().movePicX(response.value(cobolItem),
                    DECLARED_WIDTHS.get(OUTPUT_MAP_ITEMS.indexOf(cobolItem))))
                    .as("and the codec is what renders it to the declared width, on the right")
                    .startsWith("Q")
                    .hasSize(DECLARED_WIDTHS.get(OUTPUT_MAP_ITEMS.indexOf(cobolItem)));
        }

        @ParameterizedTest(name = "[{index}] {0} refuses null")
        @ValueSource(strings = {"TRNNAMEO", "USRIDINO", "ERRMSGO"})
        @DisplayName("null is refused for a character field: a COBOL PIC X item holds spaces, not nothing")
        void nullIsRefusedForACharacterField(String cobolItem) {
            assertThatNullPointerException()
                    .isThrownBy(() -> withValue(cobolItem, null))
                    .withMessageContaining(cobolItem);
        }

        @Test
        @DisplayName("PASSWDO alone accepts null, and normalises it to the unpainted image")
        void nullIsNormalisedForTheUnpublishedCredential() {
            // Every other character field is on the wire, so a null there could only be a caller's
            // mistake and is refused with the item's name. This one is not published, so a body read
            // back genuinely arrives without it, and a type that refused null could not represent its
            // own wire form. The blank image is the safe reading of the two states the wire cannot tell
            // apart: it can never be mistaken for a value the operator left in place.
            assertThat(withValue("PASSWDO", null).passwd())
                    .isNotNull()
                    .isEqualTo(" ".repeat(UserUpdateResponse.PASSWD_LENGTH))
                    .hasSize(UserUpdateResponse.PASSWD_LENGTH);
        }
    }

    private static UserUpdateResponse withValue(String cobolItem, String value) {
        UserUpdateResponse blank = UserUpdateResponse.blank();
        return switch (cobolItem) {
            case "TRNNAMEO" -> blank.withTrnName(value);
            case "TITLE01O" -> blank.withTitle01(value);
            case "CURDATEO" -> blank.withCurDate(value);
            case "PGMNAMEO" -> blank.withPgmName(value);
            case "TITLE02O" -> blank.withTitle02(value);
            case "CURTIMEO" -> blank.withCurTime(value);
            case "USRIDINO" -> blank.withUsrIdIn(value);
            case "FNAMEO" -> blank.withFName(value);
            case "LNAMEO" -> blank.withLName(value);
            case "PASSWDO" -> blank.withPasswd(value);
            case "USRTYPEO" -> blank.withUsrType(value);
            case "ERRMSGO" -> blank.withErrMsg(value);
            default -> throw new AssertionError(cobolItem + " is not an xxxO item of " + MAP_NAME);
        };
    }

    private static void assertThatExceptionOfTypeUnsupported(Runnable call) {
        assertThatExceptionOfType(UnsupportedOperationException.class).isThrownBy(call::run);
    }

    @Nested
    @DisplayName("Asymmetry #2 - the stored password IS held and echoed as a marker, never published")
    class EchoedPlaintextPassword {
        @Test
        @DisplayName("passwd exists, is a String, and is PIC X(8) - the width SEC-USR-PWD is")
        void thePasswordMemberExistsAtEightCharacters() {
            assertThat(MAP_MEMBERS)
                    .as("app/cbl/COUSR02C.cbl:169 puts SEC-USR-PWD on the screen, so the member exists")
                    .contains("passwd");
            assertThat(accessorOf("passwd").getReturnType()).isEqualTo(String.class);
            assertThat(UserUpdateResponse.PASSWD_FIELD)
                    .as("PASSWDO at app/cpy-bms/COUSR02.CPY:152")
                    .isEqualTo("PASSWDO");
            assertThat(UserUpdateResponse.PASSWD_LENGTH)
                    .as("PIC X(8) on the map and PIC X(08) in the record - the move is clean")
                    .isEqualTo(PASSWD_DECLARED_WIDTH)
                    .isEqualTo(SecUserRecord.SEC_USR_PWD_LENGTH);
            assertThat(UserUpdateResponse.MAP_FIELD_NAMES)
                    .as("and it is one of the twelve, not an extra")
                    .contains(UserUpdateResponse.PASSWD_FIELD)
                    .hasSize(UserUpdateResponse.MAP_FIELD_COUNT);
        }

        @Test
        @DisplayName("the value is populated from the STORED record, not echoed back from the request")
        void theValueComesFromTheStoredRecord() {
            SecUserRecord stored = SecUserRecord.of(USER_ID_FIXTURE, "John", "Doe", PASSWD_FIXTURE,
                    "U", MAP_CHARSET);

            UserUpdateResponse afterRead = UserUpdateResponse.blank()
                    .withUsrIdIn(stored.secUsrId())
                    .withFName(stored.secUsrFname())
                    .withLName(stored.secUsrLname())
                    .withPasswd(stored.secUsrPwd())
                    .withUsrType(stored.secUsrType());

            assertThat(afterRead.passwd())
                    .as("app/cbl/COUSR02C.cbl:169 - MOVE SEC-USR-PWD TO PASSWDI OF COUSR2AI")
                    .isEqualTo(stored.secUsrPwd())
                    .isEqualTo(PASSWD_FIXTURE);
            assertThat(UserUpdateResponse.blank().passwd())
                    .as("and a failed lookup leaves it as lines 158-161 blanked it")
                    .isEqualTo(" ".repeat(UserUpdateResponse.PASSWD_LENGTH));
        }

        @Test
        @DisplayName("the stored image round-trips through the eighty-byte record at offset 48")
        void theStoredImageRoundTripsAtOffsetFortyEight() {
            SecUserRecord stored = SecUserRecord.of(USER_ID_FIXTURE, "", "", PASSWD_FIXTURE, "",
                    MAP_CHARSET);

            byte[] image = SecUserRecord.encode(stored, codec());

            assertThat(image).hasSize(SecUserRecord.RECORD_LENGTH).hasSize(SEC_USER_DATA_LENGTH);
            assertThat(new String(image, SecUserRecord.SEC_USR_PWD_OFFSET,
                    SecUserRecord.SEC_USR_PWD_LENGTH, MAP_CHARSET))
                    .as("SEC-USR-PWD occupies bytes 48 to 55 inclusive")
                    .isEqualTo(PASSWD_FIXTURE);
            assertThat(SecUserRecord.decode(image, codec()).secUsrPwd())
                    .as("and it decodes back byte for byte - no transformation on either leg")
                    .isEqualTo(PASSWD_FIXTURE);
        }

        @Test
        @DisplayName("an eight-character password survives the payload byte for byte")
        void anEightCharacterPasswordSurvivesUnchanged() {
            UserUpdateResponse response = UserUpdateResponse.blank().withPasswd(PASSWD_FIXTURE);

            assertThat(response.passwd())
                    .isEqualTo(PASSWD_FIXTURE)
                    .hasSize(UserUpdateResponse.PASSWD_LENGTH);
            assertThat(response.value(UserUpdateResponse.PASSWD_FIELD)).isEqualTo(PASSWD_FIXTURE);
            assertThat(response.fieldValues().get(UserUpdateResponse.PASSWD_FIELD))
                    .as("fieldValues() INCLUDES the password: omitting it would hide a real "
                            + "difference from a comparison whose whole purpose is to find differences")
                    .isEqualTo(PASSWD_FIXTURE);
        }

        @Test
        @DisplayName("a shorter password is space-padded to eight by the codec, never trimmed away")
        void aShorterPasswordIsPaddedNotTrimmed() {
            UserUpdateResponse response = UserUpdateResponse.blank()
                    .withPasswd(SHORT_PASSWD_FIXTURE);

            assertThat(response.passwd())
                    .as("the payload stores what it was given, short and all")
                    .isEqualTo(SHORT_PASSWD_FIXTURE);
            assertThat(codec().movePicX(response.passwd(), UserUpdateResponse.PASSWD_LENGTH))
                    .as("and the codec renders it to PIC X(08) by padding on the RIGHT")
                    .isEqualTo(SHORT_PASSWD_FIXTURE + " ".repeat(
                            UserUpdateResponse.PASSWD_LENGTH - SHORT_PASSWD_FIXTURE.length()))
                    .hasSize(UserUpdateResponse.PASSWD_LENGTH)
                    .startsWith(SHORT_PASSWD_FIXTURE);
        }

        @Test
        @DisplayName("the component is @JsonIgnore'd, and no masking or write-only variant is used")
        void noRedactionAnnotationIsAppliedToThePassword() {
            Set<String> reachable = annotationsReachableFrom("passwd");

            assertThat(reachable)
                    .as("line 169 puts the stored secret on the screen and the DRK attribute is what "
                            + "makes that safe there; JSON has no DRK bit, so the member is withheld")
                    .contains(JsonIgnore.class.getSimpleName());
            assertThat(reachable)
                    .as("and withheld by suppression alone - no masking serialiser, no raw value, no "
                            + "view, and no rename that would put it back under another key")
                    .doesNotContain("JsonSerialize")
                    .doesNotContain("JsonRawValue")
                    .doesNotContain("JsonView")
                    .doesNotContain(JsonProperty.class.getSimpleName());
        }

        @Test
        @DisplayName("the value is readable in-process and published nowhere, under any name")
        void theStoredValueIsReadableInProcessAndPublishedNowhere() throws Exception {
            UserUpdateResponse response = afterSuccessfulRead();

            assertThat(response.passwd())
                    .as("line 169's MOVE is still observable: the component holds what was stored")
                    .isEqualTo(PASSWD_FIXTURE);
            assertThat(webConfigEquivalentMapper().writeValueAsString(response))
                    .as("and the payload carries the marker in its place, never the secret")
                    .doesNotContain(PASSWD_FIXTURE)
                    .contains("\"passwd\":\"" + UserUpdateResponse.PASSWD_UNCHANGED + "\"");
            assertThat(response.passwdOnTheWire())
                    .isEqualTo(UserUpdateResponse.PASSWD_UNCHANGED)
                    .hasSize(UserUpdateResponse.PASSWD_LENGTH)
                    .isNotBlank();
            assertThat(UserUpdateResponse.blank().passwdOnTheWire())
                    .as("a screen that holds no password publishes the blank image, because 'no user "
                            + "has been read yet' is a state a client must be able to see")
                    .isEqualTo(" ".repeat(UserUpdateResponse.PASSWD_LENGTH));
            assertThat(UserUpdateResponse.blank()
                    .withPasswd(LOW_VALUES_PASSWD).passwdOnTheWire())
                    .as("and a LOW-VALUES span is blank too - x'00' is not whitespace to Java, so the "
                            + "predicate has to name both forms")
                    .isEqualTo(" ".repeat(UserUpdateResponse.PASSWD_LENGTH));
            assertThat(UserUpdateResponse.blank().withPasswd("").passwdOnTheWire())
                    .as("an empty image is blank as well: a MOVE into a wider PIC X receiver is the "
                            + "codec's job, so a value can legitimately arrive short - or absent")
                    .isEqualTo(" ".repeat(UserUpdateResponse.PASSWD_LENGTH));
            assertThat(UserUpdateResponse.blank().withPasswd("  X     ").passwdOnTheWire())
                    .as("while one significant character anywhere in the span makes it a password, so "
                            + "the marker is what is published")
                    .isEqualTo(UserUpdateResponse.PASSWD_UNCHANGED);
        }

        @Test
        @DisplayName("no hashing, encoding, masking or authentication-framework type is reachable")
        void noSecurityFrameworkTypeIsReachable() {
            List<String> forbiddenFragments = List.of("passwordencoder", "bcrypt", "hash", "digest",
                    "encrypt", "mask", "redact", "token", "jwt", "springframework.security");

            Set<String> declaredTypeNames = new LinkedHashSet<>();
            for (Method method : UserUpdateResponse.class.getDeclaredMethods()) {
                declaredTypeNames.add(method.getReturnType().getName());
                for (Class<?> parameterType : method.getParameterTypes()) {
                    declaredTypeNames.add(parameterType.getName());
                }
            }
            for (Field field : UserUpdateResponse.class.getDeclaredFields()) {
                declaredTypeNames.add(field.getType().getName());
            }

            for (String typeName : declaredTypeNames) {
                String lower = typeName.toLowerCase(Locale.ROOT);
                for (String fragment : forbiddenFragments) {
                    assertThat(lower)
                            .as("no %s type may appear on this payload's surface: hashing or hiding "
                                    + "the password would change behaviour and would require a "
                                    + "framework this migration puts out of scope", fragment)
                            .doesNotContain(fragment);
                }
            }
            assertThat(declaredTypeNames)
                    .as("the surface is Strings, the two carriers and the type itself - nothing else")
                    .contains(String.class.getName());
        }

        @Test
        @DisplayName("the accessor performs no transformation: what goes in is what comes out")
        void theAccessorPerformsNoTransformation() {
            for (String candidate : List.of("aB-cD-1", " lead123", "mid pw12", "!@#$%^&*")) {
                String fitted = codec().movePicX(candidate, UserUpdateResponse.PASSWD_LENGTH);

                assertThat(UserUpdateResponse.blank().withPasswd(fitted).passwd())
                        .as("'%s' is stored and returned verbatim - no case folding, no trimming, "
                                + "no substitution", fitted)
                        .isEqualTo(fitted);
            }
        }

        @Test
        @DisplayName("equals and hashCode include the password, so two responses differing only there "
                + "are unequal")
        void equalityIncludesThePassword() {
            UserUpdateResponse one = UserUpdateResponse.blank().withPasswd(PASSWD_FIXTURE);
            UserUpdateResponse other = UserUpdateResponse.blank().withPasswd("PW-FAKE2");
            UserUpdateResponse same = UserUpdateResponse.blank().withPasswd(PASSWD_FIXTURE);

            assertThat(one).isNotEqualTo(other);
            assertThat(one).isEqualTo(same).hasSameHashCodeAs(same);
        }

        @Test
        @DisplayName("toString() withholds the password - a diagnostic concern, NOT a payload one (B4)")
        void toStringWithholdsThePasswordButThePayloadDoesNot() {
            UserUpdateResponse response = UserUpdateResponse.blank().withPasswd(PASSWD_FIXTURE);

            assertThat(response.toString())
                    .as("the rendering names the field but withholds its value")
                    .contains(UserUpdateResponse.PASSWD_FIELD)
                    .doesNotContain(PASSWD_FIXTURE);
            assertThat(response.passwd())
                    .as("while the accessor returns it in full - the two are not the same surface")
                    .isEqualTo(PASSWD_FIXTURE);
            assertThat(response.fieldValues())
                    .containsEntry(UserUpdateResponse.PASSWD_FIELD, PASSWD_FIXTURE);
            assertThat(response.toString())
                    .as("every other field is rendered as held, padding included")
                    .contains(UserUpdateResponse.USR_ID_IN_FIELD)
                    .contains(UserUpdateResponse.ERR_MSG_FIELD);
        }

        @Test
        @DisplayName("the three-way contrast: absent in SignOnResponse, blank in UserAddResponse, "
                + "carried here")
        void theThreeWayContrastAcrossThePackage() {
            assertThat(UserUpdateResponse.MAP_FIELD_COUNT)
                    .as("twelve, and the twelfth reason is that PASSWD is one of them")
                    .isEqualTo(12)
                    .isEqualTo(DFHMDF_NAMED);
            assertThat(COSGN00_DFHMDF_NAMED)
                    .as("COSGN00's map has eleven fields and PASSWD IS one of them - so the sign-on "
                            + "response's missing member is not the map's doing")
                    .isEqualTo(11);
            assertThat(COSGN00_RESPONSE_MEMBERS)
                    .as("yet its response projects ten: one fewer than the map declares")
                    .isEqualTo(10)
                    .isEqualTo(COSGN00_DFHMDF_NAMED - 1)
                    .isLessThan(UserUpdateResponse.MAP_FIELD_COUNT);
            assertThat(COSGN00C_PASSWDO_REFERENCES)
                    .as("because COSGN00C never writes PASSWDO - that is the program's decision, and "
                            + "it is why the absence there is correct and the presence here also is")
                    .isZero();
            assertThat(COUSR01_DFHMDF_NAMED)
                    .as("COUSR01 declares PASSWD too, so its response has a member - twelve as well, "
                            + "which is why the count alone cannot distinguish the two")
                    .isEqualTo(UserUpdateResponse.MAP_FIELD_COUNT);
            assertThat(COUSR03_DFHMDF_NAMED)
                    .as("COUSR03 has no PASSWD field at all - eleven, not twelve, and that contrast "
                            + "is precisely why this one has twelve")
                    .isEqualTo(11)
                    .isLessThan(UserUpdateResponse.MAP_FIELD_COUNT);
            assertThat(UserUpdateResponse.MAP_FIELD_NAMES)
                    .as("here the member exists AND carries the stored value")
                    .contains(UserUpdateResponse.PASSWD_FIELD);
            assertThat(UserUpdateResponse.blank().withPasswd(PASSWD_FIXTURE).passwd())
                    .as("app/cbl/COUSR02C.cbl:169 - and here, and only here, the stored value is "
                            + "carried onto the screen")
                    .isEqualTo(PASSWD_FIXTURE);
            assertThat(UserUpdateResponse.blank().passwd())
                    .as("COUSR01's member, by contrast, never gets past this blank state: its only "
                            + "password move is inbound, at COUSR01C:157")
                    .isBlank()
                    .hasSize(UserUpdateResponse.PASSWD_LENGTH);
        }

        @Test
        @DisplayName("the request half of the pairing carries a password too, at the same width")
        void theRequestHalfOfThePairingAgrees() {
            assertThat(UserUpdateRequest.PASSWD_LENGTH)
                    .as("the same PIC X(8), so line 227 compares like with like")
                    .isEqualTo(UserUpdateResponse.PASSWD_LENGTH);
            assertThat(UserUpdateRequest.MAP_FIELD_NAMES)
                    .as("the request keys on the xxxI spelling, the response on the xxxO spelling")
                    .contains("PASSWDI")
                    .doesNotContain(UserUpdateResponse.PASSWD_FIELD);
            assertThat(UserUpdateResponse.MAP_FIELD_NAMES)
                    .contains(UserUpdateResponse.PASSWD_FIELD)
                    .doesNotContain("PASSWDI");
        }

        @Test
        @DisplayName("no version, entity tag, revision or timestamp member exists - gate G43 is not "
                + "in scope for this package")
        void noConcurrencyTokenIsSmuggledIn() {
            List<String> forbiddenMembers = List.of("version", "etag", "eTag", "revision", "timestamp",
                    "lastModified", "modifiedAt", "updatedAt", "concurrencyToken", "rowVersion");

            List<String> declared = Arrays.stream(UserUpdateResponse.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(declared).hasSize(COMPONENT_COUNT);
            for (String forbidden : forbiddenMembers) {
                assertThat(declared)
                        .as("%s would be an optimistic-concurrency token, and this program performs no "
                                + "concurrency check", forbidden)
                        .doesNotContain(forbidden);
            }
            assertThat(declared)
                    .as("the seventeen are the twelve map members plus the five carriers, and no more")
                    .containsExactlyElementsOf(
                            Stream.concat(MAP_MEMBERS.stream(), STATE_MEMBERS.stream())
                                    .toList());
        }

        @Test
        @DisplayName("no persistence or bean-validation annotation appears on the response")
        void noPersistenceOrValidationAnnotationAppears() {
            Set<String> annotationNames = new LinkedHashSet<>();
            collectNames(annotationNames, UserUpdateResponse.class.getAnnotations());
            for (String member : MAP_MEMBERS) {
                annotationNames.addAll(annotationsReachableFrom(member));
            }
            for (String member : STATE_MEMBERS) {
                annotationNames.addAll(annotationsReachableFrom(member));
            }

            assertThat(annotationNames)
                    .doesNotContain("Entity")
                    .doesNotContain("Table")
                    .doesNotContain("Column")
                    .doesNotContain("Id")
                    .doesNotContain("Version")
                    .doesNotContain("NotNull")
                    .doesNotContain("NotBlank")
                    .doesNotContain("NotEmpty")
                    .doesNotContain("Pattern");
        }
    }

    @Nested
    @DisplayName("REDEFINES - one 339-byte area, two views, zero drift")
    class GroupRedefinesOverlay {
        @Test
        @DisplayName("the geometry is the copybook's: 12 + 12 x 7 + 243 = 339, in both views")
        void theGeometryIsTheCopybooks() {
            assertThat(INPUT_PREFIX_LENGTH)
                    .as("input view: xxxL 2 + xxxF 1 + FILLER X(4) = 7")
                    .isEqualTo(7);
            assertThat(OUTPUT_PREFIX_LENGTH)
                    .as("output view: FILLER X(3) + xxxC + xxxP + xxxH + xxxV = 7 as well")
                    .isEqualTo(7)
                    .isEqualTo(INPUT_PREFIX_LENGTH);
            assertThat(DECLARED_WIDTHS.stream().mapToInt(Integer::intValue).sum())
                    .as("4+40+8+8+40+8+8+20+20+8+1+78")
                    .isEqualTo(PAYLOAD_WIDTH_TOTAL)
                    .isEqualTo(243);
            assertThat(TIOAPFX_PREFIX_LENGTH + DFHMDF_NAMED * OUTPUT_PREFIX_LENGTH
                    + PAYLOAD_WIDTH_TOTAL)
                    .as("12 + 12 x 7 + 243")
                    .isEqualTo(SYMBOLIC_MAP_LENGTH)
                    .isEqualTo(339);
            assertThat(OUTPUT_VIEW_LAYOUT.recordLength())
                    .isEqualTo(INPUT_VIEW_LAYOUT.recordLength())
                    .isEqualTo(SYMBOLIC_MAP_LENGTH);
            assertThat(OUTPUT_VIEW_LAYOUT.storageSpans().stream()
                    .mapToInt(FixedWidthRecord.FieldSpan::length).sum())
                    .as("the output view's storage spans, overlays excluded, tile the area exactly")
                    .isEqualTo(SYMBOLIC_MAP_LENGTH);
            assertThat(INPUT_VIEW_LAYOUT.storageSpans().stream()
                    .mapToInt(FixedWidthRecord.FieldSpan::length).sum())
                    .as("and so do the input view's - which is what makes the overlay exact")
                    .isEqualTo(SYMBOLIC_MAP_LENGTH);
        }

        @Test
        @DisplayName("the copybook declares thirteen REDEFINES: twelve per-field plus this one")
        void theCopybookDeclaresThirteenRedefinitions() {
            assertThat(COPYBOOK_REDEFINES_TOTAL)
                    .as("app/cpy-bms/COUSR02.CPY, twelve xxxA overlays plus the group view at line 91")
                    .isEqualTo(13);
            assertThat(GROUP_LEVEL_REDEFINES).isEqualTo(1);
            assertThat(INPUT_VIEW_LAYOUT.redefinitions())
                    .as("the twelve per-field overlays, which UserUpdateRequestTest owns")
                    .hasSize(PER_FIELD_REDEFINES);
            assertThat(OUTPUT_VIEW_LAYOUT.redefinitions())
                    .as("and the one group-level overlay, which this file owns")
                    .hasSize(GROUP_LEVEL_REDEFINES);
            assertThat(OUTPUT_VIEW_LAYOUT.redefinitions().get(0).name())
                    .isEqualTo(OUTPUT_GROUP_NAME);
            assertThat(OUTPUT_VIEW_LAYOUT.redefinitions().get(0).length())
                    .as("it redefines the WHOLE area, not a field within it")
                    .isEqualTo(SYMBOLIC_MAP_LENGTH);
            assertThat(OUTPUT_VIEW_LAYOUT.redefinitions().get(0).offset()).isZero();
        }

        @Test
        @DisplayName("this package's redefinitions live entirely in its maps, never in its programs")
        void theRedefinitionsAreTheMapsAndNotThePrograms() {
            assertThat(PROGRAM_REDEFINES_TOTAL)
                    .as("COSGN00C, COUSR00C, COUSR01C, COUSR02C and COUSR03C declare none")
                    .isZero();
            assertThat(PACKAGE_REDEFINES_TOTAL)
                    .as("105 per-field + 5 group-level across the five maps")
                    .isEqualTo(110)
                    .isEqualTo(105 + PACKAGE_GROUP_LEVEL_REDEFINES);
            assertThat(PACKAGE_GROUP_LEVEL_REDEFINES)
                    .as("one group-level overlay per map, and COUSR02's is the one asserted here")
                    .isEqualTo(5)
                    .isGreaterThan(GROUP_LEVEL_REDEFINES);
            assertThat(COPYBOOK_REDEFINES_TOTAL).isLessThan(PACKAGE_REDEFINES_TOTAL);
        }

        @ParameterizedTest(name = "[{index}] {0}: xxxI and xxxO address the identical offset")
        @ValueSource(strings = {"TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02", "CURTIME",
            "USRIDIN", "FNAME", "LNAME", "PASSWD", "USRTYPE", "ERRMSG"})
        @DisplayName("the two views align field for field, with zero drift")
        void theTwoViewsAlignFieldForField(String screenField) {
            FixedWidthRecord.FieldSpan inbound = INPUT_VIEW_LAYOUT.span(screenField + "I");
            FixedWidthRecord.FieldSpan outbound = OUTPUT_VIEW_LAYOUT.span(screenField + "O");

            assertThat(outbound.offset())
                    .as("%sO begins exactly where %sI begins - both per-field prefixes are seven "
                            + "bytes, so nothing drifts", screenField, screenField)
                    .isEqualTo(inbound.offset());
            assertThat(outbound.length())
                    .as("and both are the same PICTURE clause")
                    .isEqualTo(inbound.length());
            assertThat(outbound.kind()).isEqualTo(inbound.kind())
                    .isEqualTo(FixedWidthRecord.PictureKind.ALPHANUMERIC);
        }

        @Test
        @DisplayName("PASSWDI and PASSWDO are one storage span - the mechanism behind the echo")
        void thePasswordOverlayIsTheMechanismBehindTheEcho() {
            FixedWidthRecord.FieldSpan inbound = INPUT_VIEW_LAYOUT.span("PASSWDI");
            FixedWidthRecord.FieldSpan outbound = OUTPUT_VIEW_LAYOUT.span("PASSWDO");

            assertThat(inbound.offset())
                    .as("12 prefix + 9 fields' worth of prefixes and data, re-derived by the builder")
                    .isEqualTo(outbound.offset());
            assertThat(outbound.length()).isEqualTo(UserUpdateResponse.PASSWD_LENGTH).isEqualTo(8);

            FixedWidthRecord area = FixedWidthRecord.forLayout(OUTPUT_VIEW_LAYOUT, MAP_CHARSET);
            FixedWidthRecord sameArea = FixedWidthRecord.copyOf(area.toByteArray(),
                    SYMBOLIC_MAP_LENGTH, MAP_CHARSET);
            sameArea.writeString(inbound.offset(), inbound.length(), PASSWD_FIXTURE);

            assertThat(sameArea.readString(outbound.offset(), outbound.length()))
                    .as("app/cbl/COUSR02C.cbl:169 writes PASSWDI, and PASSWDO is what gets sent")
                    .isEqualTo(PASSWD_FIXTURE);

            sameArea.writeString(outbound.offset(), outbound.length(), "PW-FAKE2");
            assertThat(sameArea.readString(inbound.offset(), inbound.length())).isEqualTo("PW-FAKE2");
            assertThat(sameArea.toByteArray()).hasSize(SYMBOLIC_MAP_LENGTH);
        }

        @ParameterizedTest(name = "[{index}] {0} round-trips across the two views")
        @ValueSource(strings = {"TRNNAME", "PASSWD", "ERRMSG"})
        @DisplayName("the first, a middle and the last field all cross between the views intact")
        void theFirstAMiddleAndTheLastFieldAllCross(String screenField) {
            int index = SCREEN_FIELDS.indexOf(screenField);
            FixedWidthRecord.FieldSpan inbound = INPUT_VIEW_LAYOUT.span(screenField + "I");
            FixedWidthRecord.FieldSpan outbound = OUTPUT_VIEW_LAYOUT.span(screenField + "O");
            FixedWidthRecord area = FixedWidthRecord.forLayout(OUTPUT_VIEW_LAYOUT, MAP_CHARSET);
            byte[] before = area.toByteArray();
            String value = codec().movePicX("Z", DECLARED_WIDTHS.get(index));

            area.writeSpan(outbound, value);

            assertThat(area.readString(inbound.offset(), inbound.length()))
                    .as("%sI reads what was written through %sO", screenField, screenField)
                    .isEqualTo(value);
            assertThat(area.readSpan(outbound)).isEqualTo(value);

            byte[] after = area.toByteArray();
            assertThat(after).hasSize(before.length).hasSize(SYMBOLIC_MAP_LENGTH);
            int differing = 0;
            for (int offset = 0; offset < after.length; offset++) {
                if (after[offset] != before[offset]) {
                    differing++;
                    assertThat(offset)
                            .as("every changed byte lies inside %sO's own span", screenField)
                            .isGreaterThanOrEqualTo(outbound.offset())
                            .isLessThan(outbound.endOffsetExclusive());
                }
            }
            assertThat(differing)
                    .as("writing 'Z' padded to width changes at most that field's bytes")
                    .isPositive()
                    .isLessThanOrEqualTo(outbound.length());
        }

        @Test
        @DisplayName("the data offsets are the copybook's, and the password sits at 238")
        void theDataOffsetsAreTheCopybooks() {
            int cursor = TIOAPFX_PREFIX_LENGTH;
            for (int index = 0; index < DFHMDF_NAMED; index++) {
                cursor += OUTPUT_PREFIX_LENGTH;
                assertThat(OUTPUT_VIEW_LAYOUT.span(OUTPUT_MAP_ITEMS.get(index)).offset())
                        .as("%s begins at %d", OUTPUT_MAP_ITEMS.get(index), cursor)
                        .isEqualTo(cursor);
                cursor += DECLARED_WIDTHS.get(index);
            }
            assertThat(cursor)
                    .as("and the walk lands exactly on the declared length")
                    .isEqualTo(SYMBOLIC_MAP_LENGTH);
            assertThat(OUTPUT_VIEW_LAYOUT.span(UserUpdateResponse.PASSWD_FIELD).offset())
                    .as("PASSWDO's data begins at 238 - not to be confused with SEC-USR-PWD's 48")
                    .isEqualTo(238)
                    .isNotEqualTo(SecUserRecord.SEC_USR_PWD_OFFSET);
            assertThat(OUTPUT_VIEW_LAYOUT.span(UserUpdateResponse.ERR_MSG_FIELD).endOffsetExclusive())
                    .as("and ERRMSGO's last byte is the area's last byte")
                    .isEqualTo(SYMBOLIC_MAP_LENGTH);
        }
    }

    @Nested
    @DisplayName("Metadata - xxxC, xxxP, xxxH, xxxV, xxxL, xxxF, xxxA and every FILLER stay off the wire")
    class MetadataStaysOffTheWire {
        @Test
        @DisplayName("the output attribute quartet exists in storage but is no member of the payload")
        void theAttributeQuartetIsStorageOnly() {
            List<String> memberNames = Arrays.stream(UserUpdateResponse.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            for (String field : SCREEN_FIELDS) {
                for (String suffix : ATTRIBUTE_SUFFIXES) {
                    assertThat(OUTPUT_VIEW_LAYOUT.hasSpan(field + suffix))
                            .as("%s%s is real storage - EXTATT=YES asks for it", field, suffix)
                            .isTrue();
                    assertThat(OUTPUT_VIEW_LAYOUT.span(field + suffix).length())
                            .isEqualTo(ATTRIBUTE_ITEM_LENGTH);
                    assertThat(memberNames)
                            .as("but %s%s is presentation metadata, never a payload member",
                                    field, suffix)
                            .doesNotContain(field + suffix)
                            .doesNotContain((field + suffix).toLowerCase(Locale.ROOT));
                    assertThat(UserUpdateResponse.MAP_FIELD_NAMES).doesNotContain(field + suffix);
                }
            }
        }

        @Test
        @DisplayName("xxxC is the colour item, and it is the one COUSR02C moves DFHRED and DFHGREEN to")
        void theColourItemIsTheFirstOfTheQuartet() {
            assertThat(ATTRIBUTE_SUFFIXES)
                    .as("declaration order in the output view: colour first, then P, H, V")
                    .containsExactly("C", "P", "H", "V");
            assertThat(FieldAttributeSetter.COLOUR_ITEM_SUFFIX).isEqualTo("C");
            assertThat(OUTPUT_VIEW_LAYOUT.span("ERRMSGC").offset())
                    .as("ERRMSGC sits three bytes past the field's FILLER and just before ERRMSGO")
                    .isEqualTo(OUTPUT_VIEW_LAYOUT.span(UserUpdateResponse.ERR_MSG_FIELD).offset()
                            - ATTRIBUTE_SUFFIXES.size() * ATTRIBUTE_ITEM_LENGTH);
            assertThat(FieldAttributeSetter.OUTPUT_ITEM_SUFFIX)
                    .as("and the data item is the O-suffixed one this payload projects")
                    .isEqualTo("O");
        }

        @Test
        @DisplayName("the input view's xxxL, xxxF and xxxA items are metadata as well")
        void theInputMetadataItemsAreAlsoNotPayload() {
            List<String> memberNames = Arrays.stream(UserUpdateResponse.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            for (String field : SCREEN_FIELDS) {
                assertThat(memberNames).doesNotContain(field + "L").doesNotContain(field + "l");
                assertThat(INPUT_VIEW_LAYOUT.hasSpan(field + "F"))
                        .as("%sF is the attribute byte, and it is storage", field)
                        .isTrue();
                assertThat(INPUT_VIEW_LAYOUT.hasSpan(field + "A"))
                        .as("%sA redefines it", field)
                        .isTrue();
                assertThat(memberNames).doesNotContain(field + "F").doesNotContain(field + "A");
                assertThat(UserUpdateResponse.MAP_FIELD_NAMES)
                        .doesNotContain(field + "L")
                        .doesNotContain(field + "F")
                        .doesNotContain(field + "A");
            }
            assertThat(LENGTH_ITEM_LENGTH)
                    .as("xxxL is COMP - a binary halfword, so two bytes, and never a payload member")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("the twelve-byte prefix and the eleven three-byte fillers are not exposed")
        void theFillersAreNotExposed() {
            long outputFillerBytes = OUTPUT_VIEW_LAYOUT.storageSpans().stream()
                    .filter(span -> span.kind().filler())
                    .mapToInt(FixedWidthRecord.FieldSpan::length)
                    .sum();

            assertThat(outputFillerBytes)
                    .as("12 for the TIOAPFX prefix plus 12 x 3 for the per-field fillers")
                    .isEqualTo(TIOAPFX_PREFIX_LENGTH + (long) DFHMDF_NAMED * OUTPUT_FILLER_LENGTH)
                    .isEqualTo(48);
            assertThat(Arrays.stream(UserUpdateResponse.class.getRecordComponents())
                    .map(RecordComponent::getName).toList())
                    .as("reserved storage is never a member, under any spelling")
                    .doesNotContain("filler")
                    .doesNotContain("FILLER")
                    .doesNotContain("tioapfx");
            assertThat(UserUpdateResponse.MAP_FIELD_NAMES)
                    .noneMatch(name -> name.contains("FILLER"));
        }

        @Test
        @DisplayName("value() refuses an attribute item by name rather than answering null")
        void valueRefusesAnAttributeItem() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> afterSuccessfulRead().value("ERRMSGC"))
                    .withMessageContaining(OUTPUT_GROUP_NAME);
            assertThatNullPointerException()
                    .isThrownBy(() -> afterSuccessfulRead().value(null));
        }
    }

    @Nested
    @DisplayName("Outcome messages - every text fits the 78-character field after the 80-to-78 move")
    class OutcomeMessages {
        @ParameterizedTest(name = "[{index}] \"{0}\"")
        @ValueSource(strings = {"User ID can NOT be empty...", "First Name can NOT be empty...",
            "Last Name can NOT be empty...", "Password can NOT be empty...",
            "User Type can NOT be empty..."})
        @DisplayName("each of the five blank-field messages composes an 80-byte image and fits at 78")
        void eachBlankFieldMessageFits(String text) {
            assertThat(BLANK_FIELD_MESSAGES)
                    .as("all five are transcribed from app/cbl/COUSR02C.cbl:182, 188, 194, 200 and 206")
                    .contains(text);

            String wsMessage = codec().movePicX(text, WS_MESSAGE_LENGTH);
            String errMsg = errMsgImageOf(text);

            assertThat(wsMessage)
                    .as("MOVE into WS-MESSAGE PIC X(80) pads on the right")
                    .hasSize(WS_MESSAGE_LENGTH)
                    .startsWith(text);
            assertThat(errMsg)
                    .as("line 270 then narrows to PIC X(78); these texts are short, so nothing of the "
                            + "text itself is lost - only two trailing spaces")
                    .hasSize(UserUpdateResponse.ERR_MSG_LENGTH)
                    .startsWith(text);
            assertThat(errMsg.strip()).isEqualTo(text);
            assertThat(UserUpdateResponse.blank().withErrMsg(errMsg).errMsg())
                    .as("and the payload carries the 78-character image verbatim, padding included")
                    .isEqualTo(errMsg);
        }

        @Test
        @DisplayName("the five arms are evaluated identifier-first, which decides which message shows")
        void theFiveArmsAreEvaluatedIdentifierFirst() {
            assertThat(BLANK_FIELD_MESSAGES)
                    .containsExactly(MSG_USER_ID_EMPTY,
                            MSG_FIRST_NAME_EMPTY,
                            MSG_LAST_NAME_EMPTY,
                            MSG_PASSWORD_EMPTY,
                            MSG_USER_TYPE_EMPTY);
            assertThat(BLANK_FIELD_MESSAGES.indexOf(MSG_USER_ID_EMPTY))
                    .as("the identifier arm is first - matching the map, where USRIDIN is field seven "
                            + "but the FIRST of the five editable ones")
                    .isZero()
                    .isLessThan(BLANK_FIELD_MESSAGES.indexOf(MSG_PASSWORD_EMPTY));
            assertThat(BLANK_FIELD_MESSAGES)
                    .as("the WHEN OTHER arm at line 210 sets no message at all, so there is no sixth")
                    .hasSize(5);
        }

        @Test
        @DisplayName("the no-change arm pairs with DFHRED, restating ERRMSG's declared default colour")
        void theNoChangeArmPairsWithRed() {
            String errMsg = errMsgImageOf(MSG_PLEASE_MODIFY);

            assertThat(MSG_PLEASE_MODIFY)
                    .as("note the space before the ellipsis, which the five arms above lack")
                    .isEqualTo("Please modify to update ...")
                    .hasSizeLessThanOrEqualTo(UserUpdateResponse.ERR_MSG_LENGTH);
            assertThat(errMsg).hasSize(UserUpdateResponse.ERR_MSG_LENGTH).startsWith(MSG_PLEASE_MODIFY);
            assertThat(BmsAttributes.COLOUR_MNEMONICS.get(BmsAttributes.DFHRED)).isEqualTo("DFHRED");
            assertThat(UserUpdateResponse.blank().withErrMsg(errMsg).errMsg()).isEqualTo(errMsg);
        }

        @Test
        @DisplayName("the success text is built by STRING, and DELIMITED BY SPACE makes it variable")
        void theSuccessTextIsVariableLength() {
            FixedWidthCodec codec = codec();
            String eightCharId = codec.movePicX(USER_ID_FIXTURE, SecUserRecord.SEC_USR_ID_LENGTH);
            String fiveCharId = codec.movePicX(SHORT_USER_ID_FIXTURE,
                    SecUserRecord.SEC_USR_ID_LENGTH);

            String forEight = codec.concatenateDelimitedBySize(MSG_UPDATED_PREFIX,
                    delimitedBySpace(eightCharId), MSG_UPDATED_SUFFIX);
            String forFive = codec.concatenateDelimitedBySize(MSG_UPDATED_PREFIX,
                    delimitedBySpace(fiveCharId), MSG_UPDATED_SUFFIX);

            assertThat(forEight)
                    .as("an eight-character id has no space, so it contributes all eight")
                    .isEqualTo("User USER0001 has been updated ...");
            assertThat(forFive)
                    .as("a five-character id is space-padded in the record, so it contributes five - "
                            + "three fewer characters, and no stray spaces in the middle")
                    .isEqualTo("User ADMIN has been updated ...")
                    .doesNotContain("ADMIN   ");
            assertThat(forFive.length())
                    .as("the composed length is VARIABLE, not fixed: shorter id, shorter message")
                    .isNotEqualTo(forEight.length())
                    .isEqualTo(forEight.length() - 3);
            assertThat(BmsAttributes.DFHGREEN)
                    .as("and this arm alone pairs with DFHGREEN, at line 371")
                    .isNotEqualTo(BmsAttributes.DFHRED);
        }

        @ParameterizedTest(name = "[{index}] id \"{0}\" composes a message of {1} characters")
        @CsvSource({"USER0001,34", "ADMIN,31", "A,27", "AB,28"})
        @DisplayName("every id length composes a message that still fits the 78-character field")
        void everyIdLengthStillFits(String rawId, int expectedLength) {
            FixedWidthCodec codec = codec();
            String stored = codec.movePicX(rawId, SecUserRecord.SEC_USR_ID_LENGTH);
            String composed = codec.concatenateDelimitedBySize(MSG_UPDATED_PREFIX,
                    delimitedBySpace(stored), MSG_UPDATED_SUFFIX);

            assertThat(stored).hasSize(SecUserRecord.SEC_USR_ID_LENGTH);
            assertThat(composed)
                    .hasSize(expectedLength)
                    .hasSizeLessThanOrEqualTo(UserUpdateResponse.ERR_MSG_LENGTH);
            assertThat(errMsgImageOf(composed))
                    .hasSize(UserUpdateResponse.ERR_MSG_LENGTH)
                    .startsWith(composed);
            assertThat(UserUpdateResponse.blank().withErrMsg(errMsgImageOf(composed)).errMsg())
                    .isEqualTo(errMsgImageOf(composed));
        }

        @ParameterizedTest(name = "[{index}] \"{0}\"")
        @ValueSource(strings = {"Press PF5 key to save your updates ...", "User ID NOT found...",
            "Unable to lookup User...", "Unable to Update User..."})
        @DisplayName("the read and rewrite outcomes fit the field too, and each pairs with its colour")
        void theReadAndRewriteOutcomesFit(String text) {
            assertThat(List.of(MSG_PRESS_PF5, MSG_USER_NOT_FOUND, MSG_UNABLE_TO_LOOKUP,
                    MSG_UNABLE_TO_UPDATE))
                    .as("transcribed from app/cbl/COUSR02C.cbl:336, 342, 349 and 386")
                    .contains(text);
            assertThat(errMsgImageOf(text))
                    .hasSize(UserUpdateResponse.ERR_MSG_LENGTH)
                    .startsWith(text);
            assertThat(BmsAttributes.DFHNEUTR)
                    .as("the save prompt at line 336 pairs with DFHNEUTR at line 338 - a third colour, "
                            + "neither the red default nor the green success")
                    .isNotEqualTo(BmsAttributes.DFHRED)
                    .isNotEqualTo(BmsAttributes.DFHGREEN);
        }

        @Test
        @DisplayName("blank() leaves errMsg as 78 spaces, matching lines 88 and 411")
        void blankLeavesTheMessageFieldSpaced() {
            assertThat(UserUpdateResponse.blank().errMsg())
                    .isEqualTo(" ".repeat(UserUpdateResponse.ERR_MSG_LENGTH))
                    .hasSize(78);
            assertThat(errMsgImageOf(""))
                    .as("and moving an empty WS-MESSAGE in gives the same 78 spaces")
                    .isEqualTo(UserUpdateResponse.blank().errMsg());
        }
    }

    @Nested
    @DisplayName("Navigation - XCTL at line 259 becomes three response fields")
    class Navigation {
        @Test
        @DisplayName("nextProgram, nextMapset and nextMap are declared members of the response")
        void theThreeNavigationMembersAreDeclared() {
            List<String> declared = Arrays.stream(UserUpdateResponse.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(declared)
                    .as("EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM) at app/cbl/COUSR02C.cbl:259 becomes "
                            + "data the client acts on, so there is no server-side forward")
                    .contains("nextProgram", "nextMapset", "nextMap");
            for (String member : List.of("nextProgram", "nextMapset", "nextMap")) {
                assertThat(accessorOf(member).getReturnType()).isEqualTo(String.class);
            }
            assertThat(UserUpdateResponse.MAP_FIELD_NAMES)
                    .as("and none of the three is a map field - they have no DFHMDF definition")
                    .doesNotContain(UserUpdateResponse.NEXT_PROGRAM_FIELD)
                    .doesNotContain(UserUpdateResponse.NEXT_MAPSET_FIELD)
                    .doesNotContain(UserUpdateResponse.NEXT_MAP_FIELD);
            assertThat(afterSuccessfulRead().fieldValues())
                    .as("so fieldValues() excludes them: including them would corrupt a comparison "
                            + "against the symbolic map")
                    .hasSize(UserUpdateResponse.MAP_FIELD_COUNT)
                    .doesNotContainKey(UserUpdateResponse.NEXT_PROGRAM_FIELD);
        }

        @Test
        @DisplayName("their COBOL names are the COMMAREA's, because that is where the values come from")
        void theirNamesAreTheCommareasOwn() {
            assertThat(UserUpdateResponse.NEXT_PROGRAM_FIELD)
                    .as("app/cpy/COCOM01Y.cpy:24")
                    .isEqualTo(NavigationContext.TO_PROGRAM_FIELD)
                    .isEqualTo("CDEMO-TO-PROGRAM");
            assertThat(UserUpdateResponse.NEXT_MAP_FIELD)
                    .as("app/cpy/COCOM01Y.cpy:43")
                    .isEqualTo(NavigationContext.LAST_MAP_FIELD)
                    .isEqualTo("CDEMO-LAST-MAP");
            assertThat(UserUpdateResponse.NEXT_MAPSET_FIELD)
                    .as("app/cpy/COCOM01Y.cpy:44")
                    .isEqualTo(NavigationContext.LAST_MAPSET_FIELD)
                    .isEqualTo("CDEMO-LAST-MAPSET");
        }

        @Test
        @DisplayName("this screen's own map and mapset names fit the X(7) fields exactly")
        void thisScreensNamesFitSevenCharacters() {
            UserUpdateResponse response = UserUpdateResponse.blank()
                    .withNextMap(MAP_NAME)
                    .withNextMapset(MAPSET_NAME);

            assertThat(response.nextMap()).isEqualTo(MAP_NAME).hasSize(7);
            assertThat(response.nextMapset()).isEqualTo(MAPSET_NAME).hasSize(7);
            assertThatIllegalArgumentException()
                    .as("an eight-character value does not fit a PIC X(7) item")
                    .isThrownBy(() -> UserUpdateResponse.blank().withNextMap("COUSR2AB"))
                    .withMessageContaining(UserUpdateResponse.NEXT_MAP_FIELD);
        }

        @ParameterizedTest(name = "[{index}] {0} is a real XCTL target of this program")
        @ValueSource(strings = {"COADM01C", "COSGN00C", "COUSR00C"})
        @DisplayName("the three targets the program actually names all fit the eight-character field")
        void theRealTargetsFitTheProgramField(String target) {
            UserUpdateResponse response = UserUpdateResponse.blank().withNextProgram(target);

            assertThat(target).hasSize(UserUpdateResponse.NEXT_PROGRAM_LENGTH);
            assertThat(response.nextProgram()).isEqualTo(target);
            assertThat(response.navigationContext())
                    .as("and the area travels alongside, exactly as line 260's COMMAREA does")
                    .isEqualTo(NavigationContext.empty());
        }

        @Test
        @DisplayName("blank() sets no target: a response that navigates nowhere is representable")
        void blankSetsNoTarget() {
            assertThat(UserUpdateResponse.blank().nextProgram())
                    .isEqualTo(" ".repeat(UserUpdateResponse.NEXT_PROGRAM_LENGTH));
            assertThat(UserUpdateResponse.blank().nextMap())
                    .isEqualTo(" ".repeat(UserUpdateResponse.NEXT_MAP_LENGTH));
            assertThat(UserUpdateResponse.blank().nextMapset())
                    .isEqualTo(" ".repeat(UserUpdateResponse.NEXT_MAPSET_LENGTH));
        }
    }

    @Nested
    @DisplayName("Conversation state - the commarea travels in the payload, never in a session")
    class ConversationState {
        @Test
        @DisplayName("the 160-byte communication area is a payload member, proven through the codec")
        void theCommareaIsAPayloadMember() {
            assertThat(NavigationContext.COMMAREA_LENGTH)
                    .as("34 general + 84 customer + 12 account + 16 card + 14 more, COCOM01Y:19-44")
                    .isEqualTo(COMMAREA_LENGTH)
                    .isEqualTo(160);
            assertThat(NavigationContext.GENERAL_INFO_LENGTH
                    + NavigationContext.CUSTOMER_INFO_LENGTH
                    + NavigationContext.ACCOUNT_INFO_LENGTH
                    + NavigationContext.CARD_INFO_LENGTH
                    + NavigationContext.MORE_INFO_LENGTH)
                    .as("re-derived from the five groups rather than restated")
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH);

            NavigationContext area = NavigationContext.empty()
                    .withFromTranid(TRANSACTION_ID)
                    .withFromProgram(PROGRAM_NAME)
                    .withToProgram("COADM01C")
                    .withLastMap(MAP_NAME)
                    .withLastMapset(MAPSET_NAME);
            byte[] image = area.toFixedWidth(codec());

            assertThat(image)
                    .as("the whole conversation fits 160 bytes, so nothing needs to be held server-side")
                    .hasSize(NavigationContext.COMMAREA_LENGTH);
            assertThat(NavigationContext.fromFixedWidth(codec(), image))
                    .as("and it round-trips, which is what makes carrying it in the payload viable")
                    .isEqualTo(area);
            assertThat(UserUpdateResponse.blank().withNavigationContext(area).navigationContext())
                    .isEqualTo(area);
        }

        @Test
        @DisplayName("an absent area is the initialised one, because EIBCALEN = 0 is a recognised state")
        void anAbsentAreaIsTheInitialisedOne() {
            assertThat(UserUpdateResponse.blank().withNavigationContext(null).navigationContext())
                    .isEqualTo(NavigationContext.empty());
            assertThat(UserUpdateResponse.blank().navigationContext())
                    .isEqualTo(NavigationContext.empty());
        }

        @Test
        @DisplayName("CDEMO-PGM-CONTEXT reads through the area, and both 88-levels are driven")
        void bothContextStatesAreDriven() {
            UserUpdateResponse onEnter = UserUpdateResponse.blank()
                    .withNavigationContext(NavigationContext.empty().withPgmEnter());
            UserUpdateResponse onReenter = UserUpdateResponse.blank()
                    .withNavigationContext(NavigationContext.empty().withPgmReenter());

            assertThat(onEnter.navigationContext().isEnter())
                    .as("88 CDEMO-PGM-ENTER VALUE 0, app/cpy/COCOM01Y.cpy:30 - line 257 moves ZEROS "
                            + "here before the XCTL")
                    .isTrue();
            assertThat(onEnter.navigationContext().isReenter()).isFalse();
            assertThat(onEnter.navigationContext().pgmContext()).isZero();

            assertThat(onReenter.navigationContext().isReenter())
                    .as("88 CDEMO-PGM-REENTER VALUE 1, line 31 - line 96 sets it before painting")
                    .isTrue();
            assertThat(onReenter.navigationContext().isEnter()).isFalse();
            assertThat(onReenter.navigationContext().pgmContext()).isEqualTo(1);
        }

        @Test
        @DisplayName("the user-type 88-levels are driven in both directions as well")
        void bothUserTypeStatesAreDriven() {
            NavigationContext admin = NavigationContext.empty().withUserTypeAdmin();
            NavigationContext user = NavigationContext.empty().withUserTypeUser();

            assertThat(admin.isAdmin()).isTrue();
            assertThat(admin.isUser()).isFalse();
            assertThat(user.isUser()).isTrue();
            assertThat(user.isAdmin()).isFalse();
            assertThat(UserUpdateResponse.blank().withUsrType("A").usrType())
                    .as("and the map field holds one character of the same alphabet")
                    .isEqualTo("A")
                    .hasSize(UserUpdateResponse.USR_TYPE_LENGTH);
            assertThat(UserUpdateResponse.blank().withUsrType("U").usrType()).isEqualTo("U");
        }

        @Test
        @DisplayName("the AID vocabulary is the resolver's, and it is the request that carries it")
        void theAidVocabularyIsTheResolvers() {
            assertThat(PfKeyResolver.AID_TOKEN_LENGTH)
                    .as("CCARD-AID X(5) - every token is rendered at five characters")
                    .isEqualTo(5);
            for (PfKeyResolver.AidKey key : PfKeyResolver.AidKey.values()) {
                assertThat(key.token())
                        .as("%s renders at the declared five characters", key.name())
                        .hasSize(PfKeyResolver.AID_TOKEN_LENGTH);
            }
            assertThat(Arrays.stream(PfKeyResolver.AidKey.values())
                    .map(PfKeyResolver.AidKey::token).toList())
                    .as("ENTER, CLEAR, the two PA keys padded to five, and PFK01 through PFK12")
                    .contains("ENTER", "CLEAR", "PA1  ", "PA2  ", "PFK01", "PFK03", "PFK04", "PFK05",
                            "PFK12");
            assertThat(PfKeyResolver.AidKey.values()).hasSize(16);
            assertThat(Arrays.stream(UserUpdateResponse.class.getRecordComponents())
                    .map(RecordComponent::getName).toList())
                    .as("and the response does not re-carry it: the outbound half of the same contract "
                            + "is nextProgram, nextMapset and nextMap")
                    .doesNotContain("aid")
                    .contains("nextProgram");
        }

        @Test
        @DisplayName("no session handle, no cache and no static mutable state exists on the payload")
        void statelessnessIsStructural() {
            for (Field field : UserUpdateResponse.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("static field %s must be final: a mutable static holder would be a "
                                    + "session by another name and would break request isolation",
                                    field.getName())
                            .isTrue();
                } else {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("record component %s is final, so an instance cannot be changed under "
                                    + "a collaborator that already holds it", field.getName())
                            .isTrue();
                }
            }
            Set<String> typeNames = new LinkedHashSet<>();
            for (Method method : UserUpdateResponse.class.getDeclaredMethods()) {
                typeNames.add(method.getReturnType().getName().toLowerCase(Locale.ROOT));
            }
            for (Field field : UserUpdateResponse.class.getDeclaredFields()) {
                typeNames.add(field.getType().getName().toLowerCase(Locale.ROOT));
            }
            for (String typeName : typeNames) {
                assertThat(typeName)
                        .doesNotContain("httpsession")
                        .doesNotContain("threadlocal")
                        .doesNotContain("servlet")
                        .doesNotContain("cache");
            }
        }
    }

    @Nested
    @DisplayName("CDEMO-CU02-INFO on the reply - six items, 34 bytes, and a 194-byte area")
    class Cu02InfoOnTheReply {
        @Test
        @DisplayName("the extension is 34 bytes, re-derived from its six declared widths")
        void theExtensionIsThirtyFourBytes() {
            assertThat(CU02_ITEM_NAMES).hasSize(6).hasSameSizeAs(CU02_ITEM_WIDTHS);
            assertThat(CU02_ITEM_WIDTHS.stream().mapToInt(Integer::intValue).sum())
                    .as("8+8+8+1+1+8, app/cbl/COUSR02C.cbl:51-58")
                    .isEqualTo(CU02_EXTENSION_LENGTH)
                    .isEqualTo(34);
            assertThat(UserUpdateRequest.Cu02Info.LENGTH)
                    .as("and the type re-derives the same total from its own width constants")
                    .isEqualTo(CU02_EXTENSION_LENGTH);
            assertThat(UserUpdateRequest.Cu02Info.initial().fieldImages().keySet())
                    .as("keyed on the names the program spells, in declaration order")
                    .containsExactlyElementsOf(CU02_ITEM_NAMES);
        }

        @Test
        @DisplayName("NavigationContext stays 160 and the CU02 area is 194 - the two are not merged")
        void theCommareaStaysOneHundredAndSixtyAndTheAreaIsOneNinetyFour() {
            assertThat(NavigationContext.COMMAREA_LENGTH)
                    .as("shared by all seventeen controllers, and never widened")
                    .isEqualTo(160);
            assertThat(NavigationContext.COMMAREA_LENGTH + UserUpdateRequest.Cu02Info.LENGTH)
                    .as("160 + 34 = the 194 bytes app/cbl/COUSR02C.cbl:94 restores")
                    .isEqualTo(CU02_COMMAREA_LENGTH)
                    .isEqualTo(194);
            assertThat(NavigationContext.empty().toFixedWidth(codec()))
                    .as("and the shared area's image is still exactly 160 bytes")
                    .hasSize(160);
            assertThat(CU02_ITEM_NAMES)
                    .as("every item is CDEMO-CU02- prefixed, so it cannot be confused with the "
                            + "identically shaped CDEMO-CU00- block of COUSR00C:67-75 or the "
                            + "CDEMO-CU03- block of COUSR03C:50-58 - three declarations of one span, "
                            + "not one shared type, and COSGN00C and COUSR01C declare none")
                    .allMatch(name -> name.startsWith("CDEMO-CU02-"));
        }

        @Test
        @DisplayName("the extension is a member of the response and is carried, not consumed")
        void theExtensionIsCarriedOnTheReply() {
            assertThat(Arrays.stream(UserUpdateResponse.class.getRecordComponents())
                    .map(RecordComponent::getName).toList())
                    .as("line 260 hands the whole 194-byte area back on the XCTL, extension included")
                    .contains("cu02Info");
            assertThat(accessorOf("cu02Info").getReturnType())
                    .isEqualTo(UserUpdateRequest.Cu02Info.class);
            assertThat(UserUpdateResponse.blank().cu02Info())
                    .as("blank() supplies the group as its VALUE clauses left it")
                    .isEqualTo(UserUpdateRequest.Cu02Info.initial());
            assertThat(UserUpdateResponse.blank().withCu02Info(null).cu02Info())
                    .as("and an absent group is the initialised group, not an error - the area has no "
                            + "absent state once EIBCALEN is non-zero")
                    .isEqualTo(UserUpdateRequest.Cu02Info.initial());
        }

        @Test
        @DisplayName("pageNum is an int, never a binary floating-point type")
        void thePageNumberIsAnIntegralType() {
            RecordComponent pageNum = Arrays.stream(UserUpdateRequest.Cu02Info.class
                            .getRecordComponents())
                    .filter(component -> component.getName().equals("pageNum"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Cu02Info declares no pageNum"));

            assertThat(pageNum.getType())
                    .as("PIC 9(08) is scale-free, so int - and explicitly not a floating-point type")
                    .isEqualTo(int.class)
                    .isNotEqualTo(double.class)
                    .isNotEqualTo(float.class);
            assertThat(UserUpdateRequest.Cu02Info.PAGE_NUM_DIGITS).isEqualTo(8);
            assertThat(codec().movePic9(7L, UserUpdateRequest.Cu02Info.PAGE_NUM_DIGITS))
                    .as("and its image is zero-filled to eight digits, as PIC 9(08) requires")
                    .isEqualTo("00000007");
        }

        @Test
        @DisplayName("no floating-point type appears anywhere on either payload's surface")
        void noFloatingPointTypeAppearsAnywhere() {
            for (Class<?> payload : List.of(UserUpdateResponse.class,
                    UserUpdateRequest.Cu02Info.class)) {
                for (RecordComponent component : payload.getRecordComponents()) {
                    assertThat(component.getType())
                            .as("%s.%s", payload.getSimpleName(), component.getName())
                            .isNotEqualTo(double.class)
                            .isNotEqualTo(float.class)
                            .isNotEqualTo(Double.class)
                            .isNotEqualTo(Float.class);
                }
                for (Method method : payload.getDeclaredMethods()) {
                    assertThat(method.getReturnType())
                            .as("%s.%s()", payload.getSimpleName(), method.getName())
                            .isNotEqualTo(double.class)
                            .isNotEqualTo(float.class);
                }
            }
        }

        @ParameterizedTest(name = "[{index}] flag \"{0}\": yes={1}, no={2}")
        @CsvSource({"Y,true,false", "N,false,true", "' ',false,false"})
        @DisplayName("both 88-levels of NEXT-PAGE-FLG are driven, and a third value satisfies neither")
        void bothConditionNamesAreDrivenInBothDirections(String flag, boolean expectYes,
                boolean expectNo) {
            UserUpdateRequest.Cu02Info info = new UserUpdateRequest.Cu02Info("        ", "        ",
                    0, flag, " ", "        ");

            assertThat(flag.equals(NEXT_PAGE_YES)).as("NEXT-PAGE-YES for '%s'", flag)
                    .isEqualTo(expectYes);
            assertThat(flag.equals(NEXT_PAGE_NO)).as("NEXT-PAGE-NO for '%s'", flag)
                    .isEqualTo(expectNo);
            assertThat(info.nextPageFlg())
                    .as("the group stores the character at its declared width of one")
                    .isEqualTo(flag)
                    .hasSize(UserUpdateRequest.Cu02Info.NEXT_PAGE_FLG_LENGTH);
            assertThat(UserUpdateResponse.blank().withCu02Info(info).cu02Info().nextPageFlg())
                    .isEqualTo(flag);
        }

        @Test
        @DisplayName("aThirdValueSatisfiesNeitherConditionName - stated on its own, because the source "
                + "declares no WHEN OTHER for these two")
        void aThirdValueSatisfiesNeitherConditionName() {
            assertThat(NEXT_PAGE_NEITHER)
                    .isNotEqualTo(NEXT_PAGE_YES)
                    .isNotEqualTo(NEXT_PAGE_NO)
                    .hasSize(UserUpdateRequest.Cu02Info.NEXT_PAGE_FLG_LENGTH);
            assertThat(NEXT_PAGE_YES).isEqualTo(UserUpdateRequest.Cu02Info.NEXT_PAGE_YES);
            assertThat(NEXT_PAGE_NO).isEqualTo(UserUpdateRequest.Cu02Info.NEXT_PAGE_NO);
        }

        @Test
        @DisplayName("'N' is the declared default, from the VALUE clause at line 54")
        void theDeclaredDefaultIsNo() {
            assertThat(UserUpdateRequest.Cu02Info.initial().nextPageFlg())
                    .as("PIC X(01) VALUE 'N' - not a convention chosen in Java")
                    .isEqualTo(NEXT_PAGE_NO);
            assertThat(UserUpdateResponse.blank().cu02Info().nextPageFlg()).isEqualTo(NEXT_PAGE_NO);
            assertThat(UserUpdateRequest.Cu02Info.initial().pageNum())
                    .as("the other five items declare no VALUE, so they begin as spaces and zero")
                    .isZero();
            assertThat(UserUpdateRequest.Cu02Info.initial().usrSelected()).isBlank();
        }

        @Test
        @DisplayName("a negative or over-wide page number is refused, not silently stored")
        void thePageNumberGuardsAreDriven() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new UserUpdateRequest.Cu02Info("        ", "        ", -1,
                            NEXT_PAGE_NO, " ", "        "))
                    .withMessageContaining("CDEMO-CU02-PAGE-NUM");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new UserUpdateRequest.Cu02Info("        ", "        ", 100_000_000,
                            NEXT_PAGE_NO, " ", "        "))
                    .withMessageContaining("CDEMO-CU02-PAGE-NUM");
            assertThat(new UserUpdateRequest.Cu02Info("        ", "        ", 99_999_999,
                    NEXT_PAGE_NO, " ", "        ").pageNum())
                    .as("the largest eight-digit value is representable")
                    .isEqualTo(99_999_999);
        }
    }

    @Nested
    @DisplayName("Serialisation - the password member is a marker, and padding survives")
    class Serialisation {
        @Test
        @DisplayName("the serialised JSON carries the unchanged marker, never the stored characters")
        void theJsonContainsThePlaintextPassword() throws Exception {
            UserUpdateResponse response = afterSuccessfulRead();

            String json = webConfigEquivalentMapper().writeValueAsString(response);

            assertThat(json)
                    .as("the member is present under its own untransformed name, so the projection is "
                            + "still twelve of twelve and a client still has a field to send back")
                    .contains("\"passwd\"");
            assertThat(json)
                    .as("carrying the marker, at the field's own declared width")
                    .contains("\"passwd\":\"" + UserUpdateResponse.PASSWD_UNCHANGED + "\"");
            assertThat(json)
                    .as("and the eight characters line 169 moved onto the screen appear nowhere in it")
                    .doesNotContain(PASSWD_FIXTURE);
            assertThat(webConfigEquivalentMapper().readTree(json).get("passwd").asText())
                    .isEqualTo(UserUpdateResponse.PASSWD_UNCHANGED)
                    .hasSize(UserUpdateResponse.PASSWD_LENGTH)
                    .as("not blank, because line 198 refuses a blank password and a client echoing "
                            + "this value must be able to change some other field")
                    .isNotBlank();
        }

        @Test
        @DisplayName("the response's own inbound leg withholds it too, and the request is what carries it")
        void thePasswordIsWithheldOnTheWayInToo() {
            // The comparison COUSR02C.cbl:219-234 performs reads PASSWDI, which arrives on a
            // UserUpdateRequest (app/cpy-bms/COUSR02.CPY:78) and is untouched by this type. What a
            // client echoes into that request is the marker, and the controller substitutes the stored
            // value before the comparison. This type's own member is therefore withheld on BOTH legs:
            // even a body that names it cannot put a credential back into a response.
            ObjectMapper mapper = webConfigEquivalentMapper();
            ObjectNode tree = (ObjectNode) assertDoesNotThrowTree(
                    () -> mapper.valueToTree(UserUpdateResponse.blank()));
            tree.put(WRITE_ONLY_MEMBER, PASSWD_FIXTURE);

            UserUpdateResponse revived = assertDoesNotThrowResponse(
                    () -> mapper.treeToValue(tree, UserUpdateResponse.class));

            assertThat(revived.passwd())
                    .as("the member is suppressed inbound, so the absent value normalises to the blank "
                            + "image rather than adopting the eight characters the tree named")
                    .isEqualTo(" ".repeat(UserUpdateResponse.PASSWD_LENGTH))
                    .isNotEqualTo(PASSWD_FIXTURE);
        }

        @Test
        @DisplayName("a blanked password publishes the blank image rather than the marker, and never null")
        void aBlankedPasswordSerialisesAsSpaces() {
            ObjectMapper mapper = webConfigEquivalentMapper();
            UserUpdateResponse blank = UserUpdateResponse.blank();

            String json = assertDoesNotThrowJson(() -> mapper.writeValueAsString(blank));

            assertThat(json)
                    .contains("\"passwd\":\"" + " ".repeat(UserUpdateResponse.PASSWD_LENGTH) + "\"")
                    .doesNotContain("\"passwd\":\"" + UserUpdateResponse.PASSWD_UNCHANGED + "\"");
            assertThat(json).doesNotContain("\"passwd\":null");

            ObjectNode tree = (ObjectNode) assertDoesNotThrowTree(() -> mapper.readTree(json));
            tree.put(WRITE_ONLY_MEMBER, " ".repeat(UserUpdateResponse.PASSWD_LENGTH));
            UserUpdateResponse revived = assertDoesNotThrowResponse(
                    () -> mapper.treeToValue(tree, UserUpdateResponse.class));
            assertThat(revived.passwd())
                    .as("an all-spaces PIC X(08) value stays the screen data it is, and is not "
                            + "coerced to null")
                    .isEqualTo(" ".repeat(UserUpdateResponse.PASSWD_LENGTH))
                    .isNotNull();
        }

        @Test
        @DisplayName("exactly the sixteen emitted member names appear, and nothing else")
        void exactlyTheExpectedMembersAppear() throws Exception {
            ObjectNode tree = (ObjectNode) webConfigEquivalentMapper()
                    .valueToTree(afterSuccessfulRead());

            Set<String> emitted = new LinkedHashSet<>();
            tree.fieldNames().forEachRemaining(emitted::add);

            assertThat(emitted)
                    .as("the twelve map members plus the five carriers, with the DRK field's name "
                            + "carrying the marker the wire publishes in place of the credential")
                    .containsExactlyInAnyOrderElementsOf(SERIALISED_JSON_MEMBERS)
                    .hasSize(COMPONENT_COUNT);
            assertThat(EXPECTED_JSON_MEMBERS)
                    .as("the model declares all seventeen, and the projection is as wide - it is the "
                            + "credential's VALUE that the wire withholds, not its name")
                    .hasSize(COMPONENT_COUNT)
                    .contains(WRITE_ONLY_MEMBER);
            assertThat(tree.get(WRITE_ONLY_MEMBER).asText())
                    .as("and that value is the marker")
                    .isEqualTo(UserUpdateResponse.PASSWD_UNCHANGED);
        }

        @Test
        @DisplayName("no attribute, length or filler item name appears in the serialised form")
        void noMetadataNameAppears() throws Exception {
            String json = webConfigEquivalentMapper().writeValueAsString(afterSuccessfulRead());

            for (String forbidden : FORBIDDEN_JSON_MEMBERS) {
                assertThat(json)
                        .as("%s is presentation or reserved metadata and must not reach the wire",
                                forbidden)
                        .doesNotContain("\"" + forbidden + "\"");
            }
            assertThat(FORBIDDEN_JSON_MEMBERS)
                    .as("four attribute items plus xxxL, xxxF and xxxA for twelve fields, plus FILLER")
                    .hasSize(DFHMDF_NAMED * (ATTRIBUTE_SUFFIXES.size() + 3) + 1)
                    .hasSize(85);
        }

        @Test
        @DisplayName("property names are untransformed, so each still traces to its xxxO item")
        void propertyNamesAreUntransformed() throws Exception {
            ObjectMapper mapper = webConfigEquivalentMapper();
            String json = mapper.writeValueAsString(afterSuccessfulRead());

            for (String member : wireNamesOf(MAP_MEMBERS)) {
                if (member.equals(WRITE_ONLY_MEMBER)) {
                    // The one member whose VALUE the wire withholds. Its name is published unchanged,
                    // because a client echoing the marker back has to spell it the same way.
                    assertThat(json)
                            .as("the DRK field keeps its own name and carries the marker")
                            .contains("\"" + member + "\":\"" + UserUpdateResponse.PASSWD_UNCHANGED + "\"")
                            .doesNotContain(PASSWD_FIXTURE);
                    continue;
                }
                assertThat(json)
                        .as("%s is emitted as its xxxI item in lower case - no snake_case, no "
                                + "kebab-case, no upper camel", member)
                        .contains("\"" + member + "\"");
            }

            Set<String> topLevel = new LinkedHashSet<>();
            ((ObjectNode) mapper.valueToTree(afterSuccessfulRead())).fieldNames()
                    .forEachRemaining(topLevel::add);

            assertThat(topLevel)
                    .as("this map spells its identifier USRIDIN, and COUSR01 spells its USERID; the "
                            + "two are deliberately not harmonised")
                    .contains("usridin")
                    .doesNotContain("userid")
                    .doesNotContain("usrIdIn")
                    .doesNotContain("usr_id_in")
                    .doesNotContain("UsrIdIn")
                    .doesNotContain("secUsrId");
            assertThat(mapper.valueToTree(afterSuccessfulRead()).get("navigationContext")
                    .has("userId"))
                    .as("while the communication area's own CDEMO-USER-ID stays where it belongs")
                    .isTrue();
        }

        @Test
        @DisplayName("a fully padded instance survives serialise then deserialise byte for byte")
        void aFullyPaddedInstanceRoundTrips() throws Exception {
            ObjectMapper mapper = webConfigEquivalentMapper();
            FixedWidthCodec codec = codec();
            UserUpdateResponse original = UserUpdateResponse.blank()
                    .withTrnName(TRANSACTION_ID)
                    .withTitle01(ScreenTitles.CCDA_TITLE01)
                    .withCurDate(EXPECTED_CURDATE)
                    .withPgmName(PROGRAM_NAME)
                    .withTitle02(ScreenTitles.CCDA_TITLE02)
                    .withCurTime(EXPECTED_CURTIME)
                    .withUsrIdIn(USER_ID_FIXTURE)
                    .withFName(codec.movePicX("John", UserUpdateResponse.FNAME_LENGTH))
                    .withLName(codec.movePicX("Doe", UserUpdateResponse.LNAME_LENGTH))
                    .withPasswd(PASSWD_FIXTURE)
                    .withUsrType("U")
                    .withErrMsg(errMsgImageOf(MSG_PRESS_PF5))
                    .withNextProgram("COADM01C")
                    .withNextMapset(MAPSET_NAME)
                    .withNextMap(MAP_NAME)
                    .withNavigationContext(NavigationContext.empty()
                            .withFromTranid(TRANSACTION_ID)
                            .withFromProgram(PROGRAM_NAME)
                            .withUserId(USER_ID_FIXTURE)
                            .withPgmReenter())
                    .withCu02Info(new UserUpdateRequest.Cu02Info("USER0001", "USER0050", 3,
                            NEXT_PAGE_YES, "S", USER_ID_FIXTURE));

            // The outbound leg publishes the marker in the credential's place, and the inbound leg
            // withholds the member however it is named - which is exactly the round trip a client
            // performs, since it holds the value it sent. Any OTHER member going missing still fails
            // this assertion.
            ObjectNode wire = (ObjectNode) assertDoesNotThrowTree(
                    () -> mapper.readTree(mapper.writeValueAsString(original)));
            assertThat(wire.get(WRITE_ONLY_MEMBER).asText())
                    .as("the wire really did withhold the stored value, so this is not a no-op")
                    .isEqualTo(UserUpdateResponse.PASSWD_UNCHANGED)
                    .isNotEqualTo(PASSWD_FIXTURE);
            wire.put(WRITE_ONLY_MEMBER, original.passwd());

            UserUpdateResponse revived = mapper.treeToValue(wire, UserUpdateResponse.class);

            assertThat(revived)
                    .as("every member, padding and all - trimming any of it would change which "
                            + "updates lines 219-234 detect. The credential is the one member the wire "
                            + "form cannot reconstruct, and it comes back unpainted")
                    .isEqualTo(original.withPasswd(" ".repeat(UserUpdateResponse.PASSWD_LENGTH)));
            assertThat(revived.fName())
                    .hasSize(UserUpdateResponse.FNAME_LENGTH)
                    .isEqualTo("John" + " ".repeat(UserUpdateResponse.FNAME_LENGTH - 4));
            assertThat(revived.lName()).hasSize(UserUpdateResponse.LNAME_LENGTH);
            assertThat(revived.errMsg()).hasSize(UserUpdateResponse.ERR_MSG_LENGTH);
            assertThat(revived.passwd())
                    .as("and the credential comes back unpainted rather than as the stored value: the "
                            + "wire carried the marker, and the member is read-only on that leg")
                    .isEqualTo(" ".repeat(UserUpdateResponse.PASSWD_LENGTH))
                    .isNotEqualTo(PASSWD_FIXTURE);
            assertThat(revived.cu02Info().pageNum())
                    .as("the page number survives as an integer, with no exponent notation anywhere")
                    .isEqualTo(3);
            assertThat(revived.navigationContext().isReenter()).isTrue();
        }

        @Test
        @DisplayName("the page number renders as a plain integer, never in exponent notation")
        void thePageNumberRendersPlainly() throws Exception {
            UserUpdateResponse response = UserUpdateResponse.blank()
                    .withCu02Info(new UserUpdateRequest.Cu02Info("        ", "        ", 99_999_999,
                            NEXT_PAGE_NO, " ", "        "));

            String json = webConfigEquivalentMapper().writeValueAsString(response);

            assertThat(json)
                    .as("WRITE_BIGDECIMAL_AS_PLAIN is enabled, and an int never uses exponent form")
                    .contains("99999999")
                    .doesNotContain("9.9999999E7")
                    .doesNotContain("E+");
        }

        @Test
        @DisplayName("a default mapper would be the wrong instrument, and this states why")
        void theMapperConfigurationIsTheDeployedOne() {
            ObjectMapper configured = webConfigEquivalentMapper();

            assertThat(configured.isEnabled(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS))
                    .as("no value may route through a binary floating-point type")
                    .isTrue();
            assertThat(configured.getFactory()
                    .isEnabled(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN))
                    .as("and none may serialise in exponent notation")
                    .isTrue();
            assertThat(configured.isEnabled(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT))
                    .as("an all-spaces PIC X(n) value must stay the screen data it is; coercing it to "
                            + "null would defeat every blank-field assertion above, the blanked "
                            + "password among them")
                    .isFalse();
            assertThat(configured.getPropertyNamingStrategy())
                    .as("no naming strategy, so each property still traces to one xxxO item")
                    .isNull();
        }
    }

    private static String assertDoesNotThrowJson(JsonCall<String> call) {
        try {
            return call.get();
        } catch (Exception failure) {
            throw new AssertionError("serialising a blank response must not fail", failure);
        }
    }

    private static UserUpdateResponse assertDoesNotThrowResponse(JsonCall<UserUpdateResponse> call) {
        try {
            return call.get();
        } catch (Exception failure) {
            throw new AssertionError("deserialising a blank response must not fail", failure);
        }
    }

    /**
     * Runs a tree conversion that is not expected to fail.
     *
     * <p>Needed because the write-only password has to be put back onto the tree before an inbound leg
     * can be driven, and building that tree is itself a mapper call.
     *
     * @param call the conversion
     * @return the tree produced
     */
    private static JsonNode assertDoesNotThrowTree(JsonCall<? extends JsonNode> call) {
        try {
            return call.get();
        } catch (Exception failure) {
            throw new AssertionError("building a JSON tree from a response must not fail", failure);
        }
    }

    /**
     * A call that may throw a checked exception, so the two helpers above can wrap one.
     *
     * @param <T> the produced type
     */
    @FunctionalInterface
    private interface JsonCall<T> {
        T get() throws Exception;
    }
}

package com.vsergeychik.carddemo.user.dto;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.user.model.SecUserRecord;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Unit tests for {@link UserUpdateRequest} - the inbound payload of
 * {@code PUT /api/users/&#123;userId&#125;}, CICS transaction {@code CU02}, program
 * {@code app/cbl/COUSR02C.cbl}, map {@code COUSR2A} of mapset {@code COUSR02}.
 */
@DisplayName("UserUpdateRequest - the COUSR02 (CU02) update-user inbound payload")
class UserUpdateRequestTest {
    private static final Charset MAP_CHARSET = StandardCharsets.US_ASCII;

    private static final String MAP_NAME = "COUSR2A";

    private static final String MAPSET_NAME = "COUSR02";

    private static final String SYMBOLIC_MAP_INPUT = "COUSR2AI";

    private static final String SYMBOLIC_MAP_OUTPUT = "COUSR2AO";

    private static final String TRANSACTION_ID = "CU02";

    private static final String PROGRAM_NAME = "COUSR02C";

    private static final int SCREEN_ROWS = 24;

    private static final int SCREEN_COLUMNS = 80;

    private static final int DFHMDF_TOTAL = 29;

    private static final int DFHMDF_NAMED = 12;

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
        return members.stream().map(UserUpdateRequestTest::wireNameOf).toList();
    }

    private static final List<String> SYMBOLIC_MAP_ITEMS = List.of("TRNNAMEI",
            "TITLE01I",
            "CURDATEI",
            "PGMNAMEI",
            "TITLE02I",
            "CURTIMEI",
            "USRIDINI",
            "FNAMEI",
            "LNAMEI",
            "PASSWDI",
            "USRTYPEI",
            "ERRMSGI");

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

    private static final List<Integer> DECLARED_WIDTHS =
            List.of(4, 40, 8, 8, 40, 8, 8, 20, 20, 8, 1, 78);

    private static final List<Integer> PUBLISHED_WIDTHS = List.of(UserUpdateRequest.TRNNAME_LENGTH,
            UserUpdateRequest.TITLE01_LENGTH,
            UserUpdateRequest.CURDATE_LENGTH,
            UserUpdateRequest.PGMNAME_LENGTH,
            UserUpdateRequest.TITLE02_LENGTH,
            UserUpdateRequest.CURTIME_LENGTH,
            UserUpdateRequest.USRIDIN_LENGTH,
            UserUpdateRequest.FNAME_LENGTH,
            UserUpdateRequest.LNAME_LENGTH,
            UserUpdateRequest.PASSWD_LENGTH,
            UserUpdateRequest.USRTYPE_LENGTH,
            UserUpdateRequest.ERRMSG_LENGTH);

    private static final List<Integer> COPYBOOK_LINES =
            List.of(24, 30, 36, 42, 48, 54, 60, 66, 72, 78, 84, 90);

    private static final List<Integer> MAPSET_LINES =
            List.of(34, 38, 47, 57, 61, 70, 85, 103, 116, 130, 145, 155);

    private static final List<Integer> PER_FIELD_REDEFINES_LINES =
            List.of(21, 27, 33, 39, 45, 51, 57, 63, 69, 75, 81, 87);

    private static final int COPYBOOK_REDEFINES_TOTAL = 13;

    private static final int PACKAGE_REDEFINES_TOTAL = 110;

    private static final int COUSR00_REDEFINES_TOTAL = 60;

    private static final List<String> STATE_MEMBERS =
            List.of("navigationContext", "aid", "cu02Info");

    private static final int COMPONENT_COUNT = DFHMDF_NAMED + 3;

    private static final int TIOAPFX_PREFIX_LENGTH = 12;

    private static final int LENGTH_ITEM_LENGTH = 2;

    private static final int ATTRIBUTE_ITEM_LENGTH = 1;

    private static final int ATTRIBUTE_FILLER_LENGTH = 4;

    private static final int FIELD_PREFIX_LENGTH =
            LENGTH_ITEM_LENGTH + ATTRIBUTE_ITEM_LENGTH + ATTRIBUTE_FILLER_LENGTH;

    private static final int PAYLOAD_WIDTH_TOTAL = 243;

    private static final int SYMBOLIC_MAP_LENGTH = 339;

    private static final int WS_MESSAGE_LENGTH = 80;

    private static final int SEC_USR_ID_LENGTH = 8;

    private static final int SEC_USR_FNAME_LENGTH = 20;

    private static final int SEC_USR_LNAME_LENGTH = 20;

    private static final int SEC_USR_PWD_LENGTH = 8;

    private static final int SEC_USR_TYPE_LENGTH = 1;

    private static final List<String> CHANGE_DETECTED_MEMBERS =
            List.of("fName", "lName", "passwd", "usrType");

    private static final List<Integer> CHANGE_DETECTED_WIDTHS = List.of(SEC_USR_FNAME_LENGTH,
            SEC_USR_LNAME_LENGTH, SEC_USR_PWD_LENGTH, SEC_USR_TYPE_LENGTH);

    private static final List<String> BLANK_GUARD_ORDER =
            List.of("usrIdIn", "fName", "lName", "passwd", "usrType");

    private static final List<String> BLANK_GUARD_MESSAGES =
            List.of("User ID can NOT be empty...",
                    "First Name can NOT be empty...",
                    "Last Name can NOT be empty...",
                    "Password can NOT be empty...",
                    "User Type can NOT be empty...");

    private static final List<String> COUSR01_ITEM_ORDER = List.of("TRNNAMEI",
            "TITLE01I",
            "CURDATEI",
            "PGMNAMEI",
            "TITLE02I",
            "CURTIMEI",
            "FNAMEI",
            "LNAMEI",
            "USERIDI",
            "PASSWDI",
            "USRTYPEI",
            "ERRMSGI");

    private static final String COUSR01_ID_ITEM = "USERIDI";

    private static final int COSGN00_CURTIME_LENGTH = 9;

    private static final int COUSR03_DFHMDF_NAMED = 11;

    private static final List<String> CU02_ITEM_NAMES = List.of("CDEMO-CU02-USRID-FIRST",
            "CDEMO-CU02-USRID-LAST",
            "CDEMO-CU02-PAGE-NUM",
            "CDEMO-CU02-NEXT-PAGE-FLG",
            "CDEMO-CU02-USR-SEL-FLG",
            "CDEMO-CU02-USR-SELECTED");

    private static final List<Integer> CU02_ITEM_WIDTHS = List.of(8, 8, 8, 1, 1, 8);

    private static final int CU02_INFO_LENGTH = 34;

    private static final int COMMAREA_LENGTH = 160;

    private static final int CU02_COMMAREA_LENGTH = COMMAREA_LENGTH + CU02_INFO_LENGTH;

    private static final String NEXT_PAGE_YES = "Y";

    private static final String NEXT_PAGE_NO = "N";

    private static final String NEITHER_PAGE_FLAG = " ";

    private static final Instant FIXED_INSTANT = Instant.parse("2022-07-19T23:12:34Z");

    private static final String FIXED_CURDATE = "07/19/22";

    private static final String FIXED_CURTIME = "23:12:34";

    private static final String NOT_A_REAL_PASSWORD = "NOTAREAL";

    private static final String OTHER_NOT_A_REAL_PASSWORD = "ALSOFAKE";

    private static final FixedWidthRecord.RecordLayout SYMBOLIC_MAP_LAYOUT = symbolicMapLayout();

    private static final Set<String> EXPECTED_JSON_MEMBERS = expectedJsonMembers();

    private static FixedWidthRecord.RecordLayout symbolicMapLayout() {
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
            spans.add(FixedWidthRecord.FieldSpan.filler(cursor, ATTRIBUTE_FILLER_LENGTH));
            cursor += ATTRIBUTE_FILLER_LENGTH;
            spans.add(FixedWidthRecord.FieldSpan.alphanumeric(
                    SYMBOLIC_MAP_ITEMS.get(index), cursor, DECLARED_WIDTHS.get(index)));
            cursor += DECLARED_WIDTHS.get(index);
        }
        return FixedWidthRecord.RecordLayout.of(SYMBOLIC_MAP_LENGTH,
                spans.toArray(FixedWidthRecord.FieldSpan[]::new));
    }

    private static Set<String> expectedJsonMembers() {
        Set<String> members = new LinkedHashSet<>(wireNamesOf(MAP_MEMBERS));
        members.addAll(STATE_MEMBERS);
        return Set.copyOf(members);
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

    private static UserUpdateRequest requestOf(List<String> mapValues, NavigationContext context,
            String aid, UserUpdateRequest.Cu02Info cu02Info) {
        return new UserUpdateRequest(mapValues.get(0), mapValues.get(1), mapValues.get(2),
                mapValues.get(3), mapValues.get(4), mapValues.get(5), mapValues.get(6),
                mapValues.get(7), mapValues.get(8), mapValues.get(9), mapValues.get(10),
                mapValues.get(11), context, aid, cu02Info);
    }

    private static List<String> mapValuesOf(UserUpdateRequest request) {
        return Arrays.asList(request.trnName(), request.title01(), request.curDate(),
                request.pgmName(), request.title02(), request.curTime(), request.usrIdIn(),
                request.fName(), request.lName(), request.passwd(), request.usrType(),
                request.errMsg());
    }

    private static List<String> blankMapValues() {
        List<String> values = new ArrayList<>(DFHMDF_NAMED);
        for (int index = 0; index < DFHMDF_NAMED; index++) {
            values.add("");
        }
        return values;
    }

    private static List<String> nullMapValues() {
        return Arrays.asList(new String[DFHMDF_NAMED]);
    }

    private static List<String> spaceFilledMapValues() {
        List<String> values = new ArrayList<>(DFHMDF_NAMED);
        for (int index = 0; index < DFHMDF_NAMED; index++) {
            values.add(codec().movePicX("", DECLARED_WIDTHS.get(index)));
        }
        return values;
    }

    private static UserUpdateRequest populatedRequest() {
        return requestOf(populatedMapValues(),
                populatedContext(),
                PfKeyResolver.AidKey.PFK05.token(),
                populatedCu02Info());
    }

    private static List<String> populatedMapValues() {
        return Arrays.asList(TRANSACTION_ID,
                ScreenTitles.CCDA_TITLE01,
                header().wsCurdateMmDdYy(),
                PROGRAM_NAME,
                ScreenTitles.CCDA_TITLE02,
                header().wsCurtimeHhMmSs(),
                "USER0001",
                codec().movePicX("LAWRENCE", UserUpdateRequest.FNAME_LENGTH),
                codec().movePicX("THOMAS", UserUpdateRequest.LNAME_LENGTH),
                NOT_A_REAL_PASSWORD,
                UserUpdateRequest.USER_TYPE_USER,
                codec().movePicX("", UserUpdateRequest.ERRMSG_LENGTH));
    }

    private static NavigationContext populatedContext() {
        return NavigationContext.empty()
                .withFromTranid("CU00")
                .withFromProgram("COUSR00C")
                .withToTranid(TRANSACTION_ID)
                .withToProgram(PROGRAM_NAME)
                .withUserId("ADMIN001")
                .withUserTypeAdmin()
                .withLastMap(MAP_NAME)
                .withLastMapset(MAPSET_NAME)
                .withPgmReenter();
    }

    private static UserUpdateRequest.Cu02Info populatedCu02Info() {
        return new UserUpdateRequest.Cu02Info("USER0001", "USER0050", 1, NEXT_PAGE_NO, "U",
                "USER0001");
    }

    private static DateHeader header() {
        return DateHeader.from(codec(), Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
    }

    private static Set<ConstraintViolation<UserUpdateRequest>> validate(UserUpdateRequest request) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(request);
        }
    }

    private static String serialise(UserUpdateRequest request) {
        try {
            return webConfigEquivalentMapper().writeValueAsString(request);
        } catch (IOException failure) {
            throw new UncheckedIOException("Serialising a UserUpdateRequest must not fail", failure);
        }
    }

    private static UserUpdateRequest deserialise(String json) {
        try {
            return webConfigEquivalentMapper().readValue(json, UserUpdateRequest.class);
        } catch (IOException failure) {
            throw new UncheckedIOException("Deserialising a UserUpdateRequest must not fail", failure);
        }
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> joined = new ArrayList<>(first.size() + second.size());
        joined.addAll(first);
        joined.addAll(second);
        return List.copyOf(joined);
    }

    private static UserUpdateRequest withMapValue(String member, String value) {
        int index = MAP_MEMBERS.indexOf(member);
        if (index < 0) {
            throw new IllegalArgumentException(member + " is not one of the twelve map members "
                    + MAP_MEMBERS + "; the twelve come from the xxxI items of COUSR02.CPY:17-90");
        }
        List<String> values = new ArrayList<>(populatedMapValues());
        values.set(index, value);
        return requestOf(values, populatedContext(), PfKeyResolver.AidKey.PFK05.token(),
                populatedCu02Info());
    }

    private static List<String> publishedFieldNames() {
        return List.of(UserUpdateRequest.TRNNAME_FIELD,
                UserUpdateRequest.TITLE01_FIELD,
                UserUpdateRequest.CURDATE_FIELD,
                UserUpdateRequest.PGMNAME_FIELD,
                UserUpdateRequest.TITLE02_FIELD,
                UserUpdateRequest.CURTIME_FIELD,
                UserUpdateRequest.USRIDIN_FIELD,
                UserUpdateRequest.FNAME_FIELD,
                UserUpdateRequest.LNAME_FIELD,
                UserUpdateRequest.PASSWD_FIELD,
                UserUpdateRequest.USRTYPE_FIELD,
                UserUpdateRequest.ERRMSG_FIELD);
    }

    private static Class<?> componentType(Class<?> recordType, String component) {
        for (RecordComponent candidate : recordType.getRecordComponents()) {
            if (candidate.getName().equals(component)) {
                return candidate.getType();
            }
        }
        throw new IllegalArgumentException(recordType.getSimpleName() + " declares no component "
                + component);
    }

    private static List<Class<?>> reachableTypes() {
        List<Class<?>> types = new ArrayList<>();
        for (Class<?> owner : List.of(UserUpdateRequest.class, UserUpdateRequest.Cu02Info.class)) {
            for (RecordComponent component : owner.getRecordComponents()) {
                types.add(component.getType());
            }
            for (Method method : owner.getDeclaredMethods()) {
                types.add(method.getReturnType());
                types.addAll(Arrays.asList(method.getParameterTypes()));
            }
            for (Field field : owner.getDeclaredFields()) {
                types.add(field.getType());
            }
        }
        return List.copyOf(types);
    }

    private static Set<String> jsonMembersOf(UserUpdateRequest request) {
        try {
            Map<String, Object> tree = webConfigEquivalentMapper()
                    .readValue(serialise(request), new TypeReference<Map<String, Object>>() { });
            return tree.keySet();
        } catch (IOException failure) {
            throw new UncheckedIOException("Reading a serialised UserUpdateRequest must not fail",
                    failure);
        }
    }

    @Nested
    @DisplayName("Projection of 01 COUSR2AI - twelve map members, in copybook order")
    class MapProjection {
        @Test
        @DisplayName("fifteen components: the twelve map members then the three state carriers")
        void componentCensus() {
            List<String> declared = Arrays.stream(UserUpdateRequest.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(declared)
                    .as("the twelve xxxI items of COUSR02.CPY:17-90 followed by the communication "
                            + "area, the attention identifier and the CDEMO-CU02-INFO extension")
                    .hasSize(COMPONENT_COUNT)
                    .containsExactlyElementsOf(concat(MAP_MEMBERS, STATE_MEMBERS));
            assertThat(declared.subList(0, DFHMDF_NAMED))
                    .as("the map members come first and in the map's own order")
                    .containsExactlyElementsOf(MAP_MEMBERS);
        }

        @Test
        @DisplayName("every map member is a String, because every xxxI item is PIC X(n)")
        void everyMapMemberIsCharacter() {
            RecordComponent[] components = UserUpdateRequest.class.getRecordComponents();
            for (int index = 0; index < DFHMDF_NAMED; index++) {
                assertThat(components[index].getType())
                        .as("%s projects %s PIC X(%d)", MAP_MEMBERS.get(index),
                                SYMBOLIC_MAP_ITEMS.get(index), DECLARED_WIDTHS.get(index))
                        .isEqualTo(String.class);
            }
            assertThat(components[DFHMDF_NAMED].getType()).isEqualTo(NavigationContext.class);
            assertThat(components[DFHMDF_NAMED + 1].getType()).isEqualTo(String.class);
            assertThat(components[DFHMDF_NAMED + 2].getType())
                    .isEqualTo(UserUpdateRequest.Cu02Info.class);
        }

        @Test
        @DisplayName("no component is a binary floating-point type, at any depth (gate G22)")
        void noComponentIsFloatingPoint() {
            for (Class<?> type : reachableTypes()) {
                assertThat(type)
                        .isNotEqualTo(double.class)
                        .isNotEqualTo(float.class)
                        .isNotEqualTo(Double.class)
                        .isNotEqualTo(Float.class);
            }
            assertThat(componentType(UserUpdateRequest.Cu02Info.class, "pageNum"))
                    .as("CDEMO-CU02-PAGE-NUM PIC 9(08) is scale-free, so it is an integral type")
                    .isEqualTo(int.class);
        }

        @Test
        @DisplayName("MAP_FIELD_COUNT is the mapset's own count, and 17 definitions stay unexposed")
        void countsAreTheMapsetsOwn() {
            assertThat(UserUpdateRequest.MAP_FIELD_COUNT)
                    .as("twelve name-labelled DFHMDF definitions, twelve xxxI items, twelve xxxO items")
                    .isEqualTo(DFHMDF_NAMED)
                    .isEqualTo(MAP_MEMBERS.size())
                    .isEqualTo(SYMBOLIC_MAP_ITEMS.size());
            assertThat(DFHMDF_TOTAL - DFHMDF_NAMED)
                    .as("the seventeen unnamed definitions are literal furniture: no name, no "
                            + "symbolic-map item, and so no payload member")
                    .isEqualTo(17);
            assertThat(UserUpdateRequest.MAP_FIELD_COUNT)
                    .as("and COUSR03 has eleven because it declares no PASSWD; the difference is "
                            + "preserved, not averaged (B5)")
                    .isEqualTo(COUSR03_DFHMDF_NAMED + 1);
        }

        @Test
        @DisplayName("MAP_FIELD_NAMES is the twelve xxxI items in order, and is unmodifiable")
        void theFieldNameListIsTheCopybooks() {
            assertThat(UserUpdateRequest.MAP_FIELD_NAMES)
                    .containsExactlyElementsOf(SYMBOLIC_MAP_ITEMS);
            assertThat(UserUpdateRequest.MAP_FIELD_NAMES.get(6))
                    .as("seventh: the identifier, before the two names")
                    .isEqualTo("USRIDINI");
            assertThat(UserUpdateRequest.MAP_FIELD_NAMES.getClass().getName())
                    .as("List.of produces an immutable list, so the constant is not mutable state")
                    .contains("ImmutableCollections");
        }

        @ParameterizedTest(name = "{0} PIC X({1}) at COUSR02.CPY:{2}")
        @CsvSource({
            "TRNNAMEI,  4, 24",
            "TITLE01I, 40, 30",
            "CURDATEI,  8, 36",
            "PGMNAMEI,  8, 42",
            "TITLE02I, 40, 48",
            "CURTIMEI,  8, 54",
            "USRIDINI,  8, 60",
            "FNAMEI,   20, 66",
            "LNAMEI,   20, 72",
            "PASSWDI,   8, 78",
            "USRTYPEI,  1, 84",
            "ERRMSGI,  78, 90"})
        @DisplayName("each published width is the width its xxxI PICTURE clause declares")
        void publishedWidthMatchesTheCopybook(String item, int width, int copybookLine) {
            int index = SYMBOLIC_MAP_ITEMS.indexOf(item);

            assertThat(index).as("%s must be one of the twelve items", item).isNotNegative();
            assertThat(COPYBOOK_LINES.get(index))
                    .as("%s is declared on COUSR02.CPY:%d", item, copybookLine)
                    .isEqualTo(copybookLine);
            assertThat(PUBLISHED_WIDTHS.get(index))
                    .as("%s PIC X(%d) - the width the type publishes must be the copybook's",
                            item, width)
                    .isEqualTo(width)
                    .isEqualTo(DECLARED_WIDTHS.get(index));
        }

        @ParameterizedTest(name = "{0} LENGTH={1} at COUSR02.bms:{2}")
        @CsvSource({
            "TRNNAME,  4,  34",
            "TITLE01, 40,  38",
            "CURDATE,  8,  47",
            "PGMNAME,  8,  57",
            "TITLE02, 40,  61",
            "CURTIME,  8,  70",
            "USRIDIN,  8,  85",
            "FNAME,   20, 103",
            "LNAME,   20, 116",
            "PASSWD,   8, 130",
            "USRTYPE,  1, 145",
            "ERRMSG,  78, 155"})
        @DisplayName("the mapset's LENGTH operand agrees independently of the copybook")
        void publishedWidthMatchesTheMapset(String screenField, int length, int mapsetLine) {
            int index = SCREEN_FIELDS.indexOf(screenField);

            assertThat(index).as("%s must be one of the twelve named fields", screenField)
                    .isNotNegative();
            assertThat(MAPSET_LINES.get(index))
                    .as("%s DFHMDF begins on COUSR02.bms:%d", screenField, mapsetLine)
                    .isEqualTo(mapsetLine);
            assertThat(PUBLISHED_WIDTHS.get(index))
                    .as("two independent sources, one width: LENGTH=%d and PIC X(%d)", length, length)
                    .isEqualTo(length);
        }

        @Test
        @DisplayName("every member traces to one screen field, and its xxxI item is that field plus I")
        void everyMemberTracesToAScreenField() {
            assertThat(SCREEN_FIELDS).hasSize(DFHMDF_NAMED);
            for (int index = 0; index < DFHMDF_NAMED; index++) {
                assertThat(SYMBOLIC_MAP_ITEMS.get(index))
                        .as("the symbolic-map item is the DFHMDF name with the input suffix")
                        .isEqualTo(SCREEN_FIELDS.get(index) + "I");
            }
            assertThat(publishedFieldNames())
                    .as("the type publishes the same twelve item names, in the same order")
                    .containsExactlyElementsOf(SYMBOLIC_MAP_ITEMS);
        }

        @Test
        @DisplayName("the screen identity constants match WS-TRANID, WS-PGMNAME, DFHMDI and DFHMSD")
        void screenIdentity() {
            assertThat(UserUpdateRequest.TRANSACTION_ID)
                    .as("WS-TRANID at COUSR02C.cbl:37, and TRANSACTION(CU02) at CARDDEMO.CSD:469")
                    .isEqualTo(TRANSACTION_ID)
                    .hasSize(UserUpdateRequest.TRNNAME_LENGTH);
            assertThat(UserUpdateRequest.PROGRAM_NAME)
                    .as("WS-PGMNAME at COUSR02C.cbl:36, and PROGRAM(COUSR02C) at CARDDEMO.CSD:470")
                    .isEqualTo(PROGRAM_NAME)
                    .hasSize(UserUpdateRequest.PGMNAME_LENGTH);
            assertThat(UserUpdateRequest.MAP_NAME).isEqualTo(MAP_NAME)
                    .hasSize(NavigationContext.LAST_MAP_LENGTH);
            assertThat(UserUpdateRequest.MAPSET_NAME).isEqualTo(MAPSET_NAME)
                    .hasSize(NavigationContext.LAST_MAPSET_LENGTH);
            assertThat(UserUpdateRequest.SYMBOLIC_MAP_INPUT)
                    .as("INTO(COUSR2AI) at COUSR02C.cbl:288")
                    .isEqualTo(SYMBOLIC_MAP_INPUT);
            assertThat(SCREEN_ROWS * SCREEN_COLUMNS)
                    .as("SIZE=(24,80) at COUSR02.bms:28 - one 3270 screen, 1920 positions")
                    .isEqualTo(1920);
        }

        @Test
        @DisplayName("the user-type literals are COCOM01Y's, and neither is enforced as a format")
        void theUserTypeLiterals() {
            assertThat(UserUpdateRequest.USER_TYPE_ADMIN)
                    .as("88 CDEMO-USRTYP-ADMIN VALUE 'A', COCOM01Y.cpy:27")
                    .isEqualTo(NavigationContext.USER_TYPE_ADMIN)
                    .hasSize(UserUpdateRequest.USRTYPE_LENGTH);
            assertThat(UserUpdateRequest.USER_TYPE_USER)
                    .as("88 CDEMO-USRTYP-USER VALUE 'U', COCOM01Y.cpy:28")
                    .isEqualTo(NavigationContext.USER_TYPE_USER)
                    .hasSize(UserUpdateRequest.USRTYPE_LENGTH);

            UserUpdateRequest odd = withMapValue("usrType", "Z");
            assertThat(odd.usrType()).isEqualTo("Z");
            assertThat(validate(odd)).isEmpty();
        }

        @Test
        @DisplayName("declares no static mutable state (gate G53)")
        void declaresNoStaticMutableState() {
            for (Class<?> type : List.of(UserUpdateRequest.class, UserUpdateRequest.Cu02Info.class,
                    UserUpdateRequestTest.class)) {
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) {
                        assertThat(Modifier.isFinal(field.getModifiers()))
                                .as("%s.%s is static and must therefore be final: COBOL "
                                        + "WORKING-STORAGE must never become a shared Java field",
                                        type.getSimpleName(), field.getName())
                                .isTrue();
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("Cross-screen inversions - USRIDIN not USERID, and the identifier comes first")
    class CrossScreenInversions {
        @Test
        @DisplayName("the identifier member is named for USRIDIN, and not for COUSR01's USERID")
        void theIdentifierIsNamedUsrIdIn() {
            assertThat(UserUpdateRequest.USRIDIN_FIELD)
                    .as("COUSR02.CPY:60 declares USRIDINI; COUSR01.CPY:72 declares USERIDI. Two "
                            + "spellings of one concept, and this screen uses the first")
                    .isEqualTo("USRIDINI")
                    .isNotEqualTo(COUSR01_ID_ITEM);
            assertThat(MAP_MEMBERS)
                    .as("the Java member follows the mapset: usrIdIn, never userId")
                    .contains("usrIdIn")
                    .doesNotContain("userId");
            assertThat(Arrays.stream(UserUpdateRequest.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList())
                    .contains("usrIdIn")
                    .doesNotContain("userId", "usrId", "userID");
            assertThat(UserUpdateRequest.MAP_FIELD_NAMES)
                    .doesNotContain(COUSR01_ID_ITEM);
        }

        @Test
        @DisplayName("the identifier is declared BEFORE the names, the inverse of COUSR01")
        void theIdentifierPrecedesTheNames() {
            int identifier = MAP_MEMBERS.indexOf("usrIdIn");
            int firstName = MAP_MEMBERS.indexOf("fName");
            int lastName = MAP_MEMBERS.indexOf("lName");

            assertThat(identifier)
                    .as("USRIDINI is seventh on COUSR02.CPY, at line 60")
                    .isEqualTo(6);
            assertThat(identifier).isLessThan(firstName).isLessThan(lastName);
            assertThat(firstName).isLessThan(lastName);

            assertThat(COUSR01_ITEM_ORDER.indexOf(COUSR01_ID_ITEM))
                    .as("COUSR01 puts its identifier ninth, after both names")
                    .isEqualTo(8)
                    .isGreaterThan(COUSR01_ITEM_ORDER.indexOf("FNAMEI"))
                    .isGreaterThan(COUSR01_ITEM_ORDER.indexOf("LNAMEI"));
            assertThat(SYMBOLIC_MAP_ITEMS)
                    .as("the two orders genuinely differ; neither is harmonised (B4)")
                    .isNotEqualTo(COUSR01_ITEM_ORDER);
        }

        @Test
        @DisplayName("that order is what makes the blank-field message deterministic")
        void theOrderDecidesWhichBlankMessageIsProduced() {
            assertThat(BLANK_GUARD_ORDER)
                    .as("the five guarded members, in the order the EVALUATE tests them")
                    .containsExactly("usrIdIn", "fName", "lName", "passwd", "usrType");
            assertThat(BLANK_GUARD_ORDER.get(0))
                    .as("arm one is the identifier, so it decides a multi-blank payload")
                    .isEqualTo("usrIdIn");
            for (int arm = 1; arm < BLANK_GUARD_ORDER.size(); arm++) {
                assertThat(MAP_MEMBERS.indexOf(BLANK_GUARD_ORDER.get(arm)))
                        .as("guard %d, %s, follows the identifier in the map as well as in the chain",
                                arm + 1, BLANK_GUARD_ORDER.get(arm))
                        .isGreaterThan(MAP_MEMBERS.indexOf(BLANK_GUARD_ORDER.get(0)));
            }

            UserUpdateRequest bothBlank = requestOf(blankMapValues(), populatedContext(),
                    PfKeyResolver.AidKey.PFK05.token(), null);
            assertThat(bothBlank.usrIdIn()).isEmpty();
            assertThat(bothBlank.fName()).isEmpty();
            assertThat(validate(bothBlank))
                    .as("both blanks are valid input; the program answers them with text, not a 400")
                    .isEmpty();
        }

        @Test
        @DisplayName("the five blank-guard messages are carried byte for byte by the 78-wide field")
        void theBlankGuardMessagesFitTheMessageField() {
            assertThat(BLANK_GUARD_MESSAGES).hasSameSizeAs(BLANK_GUARD_ORDER);
            for (String message : BLANK_GUARD_MESSAGES) {
                assertThat(message.length())
                        .as("'%s' must fit WS-MESSAGE PIC X(80) and survive the move to ERRMSG X(78)",
                                message)
                        .isLessThanOrEqualTo(UserUpdateRequest.ERRMSG_LENGTH);
                UserUpdateRequest carried = withMapValue("errMsg",
                        codec().movePicX(message, UserUpdateRequest.ERRMSG_LENGTH));
                assertThat(carried.errMsg())
                        .hasSize(UserUpdateRequest.ERRMSG_LENGTH)
                        .startsWith(message);
                assertThat(validate(carried)).isEmpty();
            }
        }

        @Test
        @DisplayName("this screen has a password field and COUSR03 does not; both facts are preserved")
        void thePasswordFieldExistsHereAndNotOnTheDeleteScreen() {
            assertThat(SCREEN_FIELDS)
                    .as("PASSWD DFHMDF at COUSR02.bms:130")
                    .contains("PASSWD");
            assertThat(MAP_MEMBERS).contains("passwd");
            assertThat(DFHMDF_NAMED - COUSR03_DFHMDF_NAMED)
                    .as("the one field COUSR03 does not declare is exactly PASSWD")
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Width traps - eight not nine, and seventy-eight not eighty")
    class WidthTraps {
        @Test
        @DisplayName("curTime is eight characters here; only COSGN00 widens its time field to nine")
        void curTimeIsEightNotNine() {
            assertThat(UserUpdateRequest.CURTIME_LENGTH)
                    .as("CURTIMEI PIC X(8) at COUSR02.CPY:54, CURTIME LENGTH=8 at COUSR02.bms:70")
                    .isEqualTo(8)
                    .isNotEqualTo(COSGN00_CURTIME_LENGTH);
            assertThat(COSGN00_CURTIME_LENGTH)
                    .as("COSGN00.CPY:54 declares CURTIMEI PIC X(9) - the only nine in the application")
                    .isEqualTo(9);
            assertThat(header().wsCurtimeHhMmSs())
                    .as("WS-CURTIME-HH-MM-SS is hh:mm:ss, eight characters, and fills the field "
                            + "exactly with no padding")
                    .isEqualTo(FIXED_CURTIME)
                    .hasSize(UserUpdateRequest.CURTIME_LENGTH)
                    .hasSize(DateHeader.WS_CURTIME_HH_MM_SS_LENGTH);
        }

        @Test
        @DisplayName("curDate is eight characters and the header date fills it exactly")
        void curDateIsEightAndFillsExactly() {
            assertThat(UserUpdateRequest.CURDATE_LENGTH)
                    .as("CURDATEI PIC X(8) at COUSR02.CPY:36")
                    .isEqualTo(DateHeader.WS_CURDATE_MM_DD_YY_LENGTH);
            assertThat(header().wsCurdateMmDdYy())
                    .as("WS-CURDATE-MM-DD-YY as COUSR02C.cbl:305-309 assembles it, from the fixed "
                            + "clock so the expectation is exact (B7)")
                    .isEqualTo(FIXED_CURDATE)
                    .hasSize(UserUpdateRequest.CURDATE_LENGTH);

            UserUpdateRequest painted = populatedRequest();
            assertThat(painted.curDate()).isEqualTo(FIXED_CURDATE);
            assertThat(painted.curTime()).isEqualTo(FIXED_CURTIME);
        }

        @Test
        @DisplayName("errMsg is 78 while WS-MESSAGE is 80, so the move discards two characters")
        void theEightyByteMessageLosesItsLastTwoCharacters() {
            String head = codec().movePicX("Please modify to update ...",
                    UserUpdateRequest.ERRMSG_LENGTH);
            String eighty = head + "#!";

            assertThat(head).hasSize(UserUpdateRequest.ERRMSG_LENGTH);
            assertThat(eighty)
                    .as("an eighty-character WS-MESSAGE whose final two characters are not spaces")
                    .hasSize(WS_MESSAGE_LENGTH)
                    .endsWith("#!");

            String narrowed = codec().movePicX(eighty, UserUpdateRequest.ERRMSG_LENGTH);

            assertThat(narrowed)
                    .as("the receiving width is 78, so exactly the leading 78 characters survive")
                    .hasSize(UserUpdateRequest.ERRMSG_LENGTH)
                    .isEqualTo(head)
                    .startsWith("Please modify to update ...")
                    .doesNotContain("#")
                    .doesNotContain("!");
            assertThat(WS_MESSAGE_LENGTH - UserUpdateRequest.ERRMSG_LENGTH)
                    .as("two characters, on every send, unconditionally")
                    .isEqualTo(2);

            assertThat(withMapValue("errMsg", narrowed).errMsg())
                    .isEqualTo(narrowed)
                    .hasSize(UserUpdateRequest.ERRMSG_LENGTH);
        }

        @Test
        @DisplayName("truncation is on the RIGHT, which is the direction a PIC X receiver uses")
        void theMoveTruncatesOnTheRight() {
            assertThat(codec().movePicX("ABCDEF", UserUpdateRequest.TRNNAME_LENGTH))
                    .isEqualTo("ABCD")
                    .isNotEqualTo("CDEF");
            assertThat(codec().movePic9(123456789L,
                    UserUpdateRequest.Cu02Info.PAGE_NUM_DIGITS))
                    .as("a numeric receiver is aligned on its implied point, so the LOW-order digits "
                            + "survive - the opposite rule, on the same screen")
                    .isEqualTo("23456789");
        }

        @Test
        @DisplayName("a value shorter than its field is padded on the right, never left-aligned wrongly")
        void aShortValueIsPaddedOnTheRight() {
            String padded = codec().movePicX("THOMAS", UserUpdateRequest.LNAME_LENGTH);

            assertThat(padded)
                    .hasSize(UserUpdateRequest.LNAME_LENGTH)
                    .startsWith("THOMAS")
                    .isEqualTo("THOMAS" + " ".repeat(UserUpdateRequest.LNAME_LENGTH - 6));
            assertThat(withMapValue("lName", padded).lName())
                    .as("and the payload keeps the padding: the change test at COUSR02C.cbl:223 "
                            + "compares against a space-padded SEC-USR-LNAME")
                    .isEqualTo(padded);
        }

        @Test
        @DisplayName("title01 and title02 are forty and carry the screen titles byte for byte")
        void theTitlesAreFortyCharacters() {
            assertThat(UserUpdateRequest.TITLE01_LENGTH)
                    .isEqualTo(ScreenTitles.TITLE_LENGTH)
                    .isEqualTo(UserUpdateRequest.TITLE02_LENGTH);
            assertThat(ScreenTitles.CCDA_TITLE01)
                    .as("MOVE CCDA-TITLE01 TO TITLE01O at COUSR02C.cbl:300")
                    .hasSize(UserUpdateRequest.TITLE01_LENGTH);
            assertThat(ScreenTitles.CCDA_TITLE02)
                    .as("MOVE CCDA-TITLE02 TO TITLE02O at COUSR02C.cbl:301")
                    .hasSize(UserUpdateRequest.TITLE02_LENGTH);

            UserUpdateRequest painted = populatedRequest();
            assertThat(painted.title01()).isEqualTo(ScreenTitles.CCDA_TITLE01);
            assertThat(painted.title02()).isEqualTo(ScreenTitles.CCDA_TITLE02);
        }

        @Test
        @DisplayName("the two thank-you literals are different texts of different widths")
        void theThankYouLiteralsAreNotInterchangeable() {
            assertThat(ScreenTitles.CCDA_THANK_YOU)
                    .hasSize(ScreenTitles.TITLE_LENGTH)
                    .hasSize(UserUpdateRequest.TITLE01_LENGTH)
                    .isNotEqualTo(SystemMessages.CCDA_MSG_THANK_YOU);
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU)
                    .hasSize(SystemMessages.MESSAGE_LENGTH);
            assertThat(SystemMessages.MESSAGE_LENGTH)
                    .as("fifty, not forty, and neither is the 78 of ERRMSG")
                    .isNotEqualTo(ScreenTitles.TITLE_LENGTH)
                    .isNotEqualTo(UserUpdateRequest.ERRMSG_LENGTH);
        }

        @Test
        @DisplayName("the five data widths are the SEC-USER-DATA widths CSUSR01Y declares")
        void theDataWidthsMatchTheSecurityRecord() {
            assertThat(UserUpdateRequest.USRIDIN_LENGTH)
                    .as("SEC-USR-ID PIC X(08) at CSUSR01Y.cpy:18, moved at COUSR02C.cbl:162 and :216")
                    .isEqualTo(SEC_USR_ID_LENGTH)
                    .isEqualTo(SecUserRecord.SEC_USR_ID_LENGTH)
                    .isEqualTo(SecUserRecord.KEY_LENGTH);
            assertThat(UserUpdateRequest.FNAME_LENGTH)
                    .as("SEC-USR-FNAME PIC X(20) at CSUSR01Y.cpy:19, compared at :219")
                    .isEqualTo(SEC_USR_FNAME_LENGTH)
                    .isEqualTo(SecUserRecord.SEC_USR_FNAME_LENGTH);
            assertThat(UserUpdateRequest.LNAME_LENGTH)
                    .as("SEC-USR-LNAME PIC X(20) at CSUSR01Y.cpy:20, compared at :223")
                    .isEqualTo(SEC_USR_LNAME_LENGTH)
                    .isEqualTo(SecUserRecord.SEC_USR_LNAME_LENGTH);
            assertThat(UserUpdateRequest.PASSWD_LENGTH)
                    .as("SEC-USR-PWD PIC X(08) at CSUSR01Y.cpy:21, compared at :227")
                    .isEqualTo(SEC_USR_PWD_LENGTH)
                    .isEqualTo(SecUserRecord.SEC_USR_PWD_LENGTH);
            assertThat(UserUpdateRequest.USRTYPE_LENGTH)
                    .as("SEC-USR-TYPE PIC X(01) at CSUSR01Y.cpy:22, compared at :231")
                    .isEqualTo(SEC_USR_TYPE_LENGTH)
                    .isEqualTo(SecUserRecord.SEC_USR_TYPE_LENGTH);
        }

        @Test
        @DisplayName("the record those five reach is 80 bytes at offsets 0, 8, 28, 48, 56 and 57")
        void theSecurityRecordGeometryIsUnchanged() {
            assertThat(SecUserRecord.RECORD_LENGTH)
                    .as("SEC-USER-DATA closes at 80 bytes: 8 + 20 + 20 + 8 + 1 + 23")
                    .isEqualTo(80);
            assertThat(List.of(SecUserRecord.SEC_USR_ID_OFFSET,
                    SecUserRecord.SEC_USR_FNAME_OFFSET,
                    SecUserRecord.SEC_USR_LNAME_OFFSET,
                    SecUserRecord.SEC_USR_PWD_OFFSET,
                    SecUserRecord.SEC_USR_TYPE_OFFSET,
                    SecUserRecord.SEC_USR_FILLER_OFFSET))
                    .as("the offsets the five payload members are compared at, FILLER included")
                    .containsExactly(0, 8, 28, 48, 56, 57);
            assertThat(SecUserRecord.SEC_USR_FILLER_OFFSET + SecUserRecord.SEC_USR_FILLER_LENGTH)
                    .as("FILLER X(23) is emitted, or every downstream offset is wrong")
                    .isEqualTo(SecUserRecord.RECORD_LENGTH);

            byte[] stored = SecUserRecord.encode(SecUserRecord.of("USER0001",
                    codec().movePicX("LAWRENCE", SEC_USR_FNAME_LENGTH),
                    codec().movePicX("THOMAS", SEC_USR_LNAME_LENGTH),
                    NOT_A_REAL_PASSWORD,
                    UserUpdateRequest.USER_TYPE_USER,
                    MAP_CHARSET), MAP_CHARSET);
            assertThat(stored).hasSize(SecUserRecord.RECORD_LENGTH);
            assertThat(SecUserRecord.decode(stored, MAP_CHARSET).secUsrPwd())
                    .as("and it round-trips byte for byte, plaintext included")
                    .isEqualTo(NOT_A_REAL_PASSWORD)
                    .hasSize(UserUpdateRequest.PASSWD_LENGTH);
        }
    }

    @Nested
    @DisplayName("Metadata - xxxL, xxxF, xxxA and every FILLER stay off the wire")
    class MetadataStaysOffTheWire {
        @Test
        @DisplayName("the serialised payload carries exactly the fifteen expected member names")
        void theWireCarriesOnlyTheDeclaredMembers() {
            assertThat(jsonMembersOf(populatedRequest()))
                    .containsExactlyInAnyOrderElementsOf(EXPECTED_JSON_MEMBERS)
                    .hasSize(COMPONENT_COUNT);
        }

        @ParameterizedTest(name = "{0}L, {0}F and {0}A are absent")
        @ValueSource(strings = {"TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02", "CURTIME",
            "USRIDIN", "FNAME", "LNAME", "PASSWD", "USRTYPE", "ERRMSG"})
        @DisplayName("no length, flag or attribute item appears under any spelling")
        void noMetadataItemIsAMember(String screenField) {
            String payload = serialise(populatedRequest());
            Set<String> members = jsonMembersOf(populatedRequest());

            for (String suffix : List.of("L", "F", "A")) {
                String item = screenField + suffix;
                assertThat(payload)
                        .as("%s is metadata: %s", item, suffix.equals("L")
                                ? "the length CICS reports, and the cursor carrier"
                                : "an attribute byte the terminal reads")
                        .doesNotContain(item);
                assertThat(members).doesNotContain(item, item.toLowerCase(Locale.ROOT));
            }
        }

        @ParameterizedTest(name = "the output view's {0} items are absent")
        @ValueSource(strings = {"C", "P", "H", "V", "O"})
        @DisplayName("nothing from the group-level COUSR2AO redefinition reaches this payload")
        void noOutputViewItemIsAMember(String suffix) {
            String payload = serialise(populatedRequest());
            for (String screenField : SCREEN_FIELDS) {
                assertThat(payload).doesNotContain(screenField + suffix);
            }
            assertThat(payload).doesNotContain(SYMBOLIC_MAP_OUTPUT);
            assertThat(COPYBOOK_REDEFINES_TOTAL - PER_FIELD_REDEFINES_LINES.size())
                    .as("thirteen REDEFINES in the copybook, twelve of them per-field; the "
                            + "thirteenth is the group view at line 91")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("neither the TIOAPFX prefix nor any per-field filler is exposed")
        void noFillerIsAMember() {
            Set<String> members = jsonMembersOf(populatedRequest());

            assertThat(members).noneSatisfy(member ->
                    assertThat(member.toLowerCase(Locale.ROOT)).contains("filler"));
            assertThat(serialise(populatedRequest())).doesNotContain("FILLER", "TIOAPFX");
            assertThat(TIOAPFX_PREFIX_LENGTH + DFHMDF_NAMED * ATTRIBUTE_FILLER_LENGTH)
                    .as("sixty bytes of the symbolic map are filler, and none of them is a member")
                    .isEqualTo(60);
        }

        @Test
        @DisplayName("the read-through predicates are withheld, so a payload cannot contradict itself")
        void theDerivedPredicatesAreWithheld() throws NoSuchMethodException {
            Set<String> members = jsonMembersOf(populatedRequest());

            assertThat(members).doesNotContain("hasNavigationContext", "contextIsEnter",
                    "contextIsReenter", "navigationContextPresent", "enter", "reenter");
            for (String name : List.of("hasNavigationContext", "contextIsEnter", "contextIsReenter")) {
                Method method = UserUpdateRequest.class.getDeclaredMethod(name);
                assertThat(method.getReturnType()).isEqualTo(boolean.class);
                assertThat(name)
                        .as("%s is deliberately not bean-accessor shaped: a record method named "
                                + "getXxx or isXxx would be collected as an extra JSON property, "
                                + "putting a value on the wire the canonical constructor cannot "
                                + "accept back", name)
                        .doesNotStartWith("get")
                        .doesNotStartWith("is");
            }
        }

        @Test
        @DisplayName("the error highlight is metadata too, and applies only in the re-enter state")
        void theErrorHighlightIsMetadataAndReenterOnly() {
            FieldAttributeSetter.FieldHighlight onFirstEntry =
                    FieldAttributeSetter.resolveFromFlags(true, true, false);
            FieldAttributeSetter.FieldHighlight onReentry =
                    FieldAttributeSetter.resolveFromFlags(true, true, true);

            assertThat(onFirstEntry.untouched())
                    .as("first entry paints no error, because nothing has been keyed yet")
                    .isTrue();
            assertThat(onReentry.untouched()).isFalse();
            assertThat(onReentry.colourItemValue())
                    .as("DFHRED, from the absent-but-reproduced DFHBMSCA")
                    .isEqualTo(BmsAttributes.DFHRED);
            assertThat(onReentry.outputItemValue()).isEqualTo(FieldAttributeSetter.ASTERISK);

            Set<String> members = jsonMembersOf(populatedRequest());
            assertThat(members).doesNotContain("highlight", "attribute", "colour", "color");
        }
    }

    @Nested
    @DisplayName("Validation - @Size maxima only, never a presence or format constraint")
    class ValidationConstraints {
        @Test
        @DisplayName("carries thirteen @Size constraints and no other constraint at all")
        void carriesOnlySizeConstraints() {
            int sized = 0;
            for (RecordComponent component : UserUpdateRequest.class.getRecordComponents()) {
                for (Annotation annotation : component.getAccessor().getAnnotations()) {
                    assertThat(annotation.annotationType().getName())
                            .as("%s carries a constraint COUSR02C does not perform. Every blank "
                                    + "field yields a screen with a message - a 200 - and never a "
                                    + "400: see COUSR02C.cbl:180, :186, :192, :198 and :204",
                                    component.getName())
                            .doesNotContain("NotBlank")
                            .doesNotContain("NotNull")
                            .doesNotContain("NotEmpty")
                            .doesNotContain("Pattern")
                            .doesNotContain("Email")
                            .doesNotContain("Digits")
                            .doesNotContain("AssertTrue");
                    if (annotation instanceof Size) {
                        sized++;
                    }
                }
            }
            assertThat(sized)
                    .as("the twelve screen fields plus the AID token. The communication area and the "
                            + "extension group validate themselves at construction and need no "
                            + "annotation")
                    .isEqualTo(DFHMDF_NAMED + 1);
        }

        @Test
        @DisplayName("each @Size(max) is the width its symbolic-map item declares")
        void eachSizeMaximumEqualsTheDeclaredWidth() {
            RecordComponent[] components = UserUpdateRequest.class.getRecordComponents();
            for (int index = 0; index < DFHMDF_NAMED; index++) {
                Size size = components[index].getAccessor().getAnnotation(Size.class);

                assertThat(size)
                        .as("%s must be width-constrained", components[index].getName())
                        .isNotNull();
                assertThat(size.max())
                        .as("%s projects %s PIC X(%d)", components[index].getName(),
                                SYMBOLIC_MAP_ITEMS.get(index), DECLARED_WIDTHS.get(index))
                        .isEqualTo(DECLARED_WIDTHS.get(index));
                assertThat(size.min())
                        .as("no lower bound: a shorter value is what a partly keyed screen sends")
                        .isZero();
            }
            assertThat(components[DFHMDF_NAMED].getAccessor().getAnnotation(Size.class))
                    .as("the communication area is a typed object, not a character field")
                    .isNull();
            assertThat(components[DFHMDF_NAMED + 1].getAccessor().getAnnotation(Size.class).max())
                    .as("the AID token is CCARD-AID PIC X(5) wide")
                    .isEqualTo(UserUpdateRequest.AID_LENGTH)
                    .isEqualTo(PfKeyResolver.AID_TOKEN_LENGTH);
            assertThat(components[DFHMDF_NAMED + 2].getAccessor().getAnnotation(Size.class))
                    .as("the extension group enforces its own widths in its constructor")
                    .isNull();
        }

        @Test
        @DisplayName("a wholly blank instance is valid, because the program answers blanks with text")
        void aBlankInstanceIsValid() {
            UserUpdateRequest blank = requestOf(blankMapValues(), NavigationContext.empty(), "", null);

            assertThat(validate(blank))
                    .as("five guards answer a blank field with a message and re-send; none rejects")
                    .isEmpty();
            assertThat(mapValuesOf(blank)).allSatisfy(value -> assertThat(value).isEmpty());
        }

        @Test
        @DisplayName("a null in every position is valid too, so nothing precedes the program's own test")
        void aNullInEveryPositionIsValid() {
            UserUpdateRequest absent = requestOf(nullMapValues(), null, null, null);

            assertThat(validate(absent)).isEmpty();
            assertThat(mapValuesOf(absent)).allSatisfy(value -> assertThat(value).isNull());
            assertThat(absent.navigationContext())
                    .as("EIBCALEN = 0 is a handled input state, COUSR02C.cbl:90-92")
                    .isNull();
            assertThat(absent.aid()).isNull();
            assertThat(absent.cu02Info())
                    .as("the one component that is normalised rather than carried: a PIC X group has "
                            + "no absent state, so null becomes the VALUE-clause image")
                    .isEqualTo(UserUpdateRequest.Cu02Info.initial());
        }

        @ParameterizedTest(name = "{0} accepts {1} characters and refuses {2}")
        @CsvSource({
            "trnName,  4,  5",
            "title01, 40, 41",
            "curDate,  8,  9",
            "pgmName,  8,  9",
            "title02, 40, 41",
            "curTime,  8,  9",
            "usrIdIn,  8,  9",
            "fName,   20, 21",
            "lName,   20, 21",
            "passwd,   8,  9",
            "usrType,  1,  2",
            "errMsg,  78, 79"})
        @DisplayName("one character over the declared width is exactly one violation, on that member")
        void oneCharacterTooManyIsOneViolation(String member, int width, int overWidth) {
            assertThat(validate(withMapValue(member, "V".repeat(width))))
                    .as("%s accepts exactly its declared width", member)
                    .isEmpty();

            Set<ConstraintViolation<UserUpdateRequest>> violations =
                    validate(withMapValue(member, "V".repeat(overWidth)));

            assertThat(violations)
                    .as("%s at %d characters is one violation and nothing else", member, overWidth)
                    .hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath().toString())
                    .isEqualTo(member);
            assertThat(violations.iterator().next().getConstraintDescriptor().getAnnotation())
                    .isInstanceOf(Size.class);
        }

        @Test
        @DisplayName("an over-wide AID token is refused as well, and two failures report as two")
        void theAidTokenIsConstrainedToo() {
            UserUpdateRequest overWideAid = requestOf(populatedMapValues(), populatedContext(),
                    "PFK011", populatedCu02Info());

            assertThat(validate(overWideAid)).hasSize(1);
            assertThat(validate(overWideAid).iterator().next().getPropertyPath().toString())
                    .isEqualTo("aid");

            List<String> values = new ArrayList<>(populatedMapValues());
            values.set(MAP_MEMBERS.indexOf("usrType"), "AU");
            assertThat(validate(requestOf(values, populatedContext(), "PFK011",
                    populatedCu02Info())))
                    .as("two independent over-widths are two violations, not one aggregated failure")
                    .hasSize(2);
        }

        @Test
        @DisplayName("SPACES and LOW-VALUES are both carried, distinctly, and neither is coerced")
        void spacesAndLowValuesAreBothCarried() {
            UserUpdateRequest spaces = requestOf(spaceFilledMapValues(), NavigationContext.empty(),
                    PfKeyResolver.AidKey.ENTER.token(), null);
            UserUpdateRequest lowValues = requestOf(nullMapValues(), NavigationContext.empty(),
                    PfKeyResolver.AidKey.ENTER.token(), null);

            assertThat(validate(spaces)).isEmpty();
            assertThat(validate(lowValues)).isEmpty();
            for (int index = 0; index < DFHMDF_NAMED; index++) {
                assertThat(mapValuesOf(spaces).get(index))
                        .as("%s arrives as its declared width in spaces, untrimmed and not null",
                                MAP_MEMBERS.get(index))
                        .isNotNull()
                        .hasSize(DECLARED_WIDTHS.get(index))
                        .isBlank();
                assertThat(mapValuesOf(lowValues).get(index))
                        .as("%s arrives absent, which is a different state", MAP_MEMBERS.get(index))
                        .isNull();
            }
            assertThat(spaces).isNotEqualTo(lowValues);

            String nulFilled = "\u0000".repeat(UserUpdateRequest.USRIDIN_LENGTH);
            UserUpdateRequest nulKeyed = withMapValue("usrIdIn", nulFilled);
            assertThat(nulKeyed.usrIdIn())
                    .isEqualTo(nulFilled)
                    .hasSize(UserUpdateRequest.USRIDIN_LENGTH)
                    .isNotEqualTo(codec().movePicX("", UserUpdateRequest.USRIDIN_LENGTH));
            assertThat(validate(nulKeyed)).isEmpty();
        }

        @Test
        @DisplayName("the constructor validates nothing, so @Size at the boundary stays reachable")
        void theConstructorIsTotal() {
            List<String> tooWide = new ArrayList<>(populatedMapValues());
            tooWide.set(MAP_MEMBERS.indexOf("trnName"), "OVERWIDE");

            UserUpdateRequest accepted = requestOf(tooWide, null, "OVERLONGAID", null);

            assertThat(accepted.trnName()).isEqualTo("OVERWIDE");
            assertThat(accepted.aid()).isEqualTo("OVERLONGAID");
            assertThat(accepted.cu02Info()).isEqualTo(UserUpdateRequest.Cu02Info.initial());
            assertThat(validate(accepted))
                    .as("the boundary reports both over-widths as constraint violations instead")
                    .hasSize(2);
        }
    }

    @Nested
    @DisplayName("Change detection - four independent comparisons, and no concurrency token")
    class ChangeDetectionSurface {
        @Test
        @DisplayName("exactly four members are compared, at the SEC-USER-DATA widths 20, 20, 8 and 1")
        void theFourComparedMembers() {
            assertThat(CHANGE_DETECTED_MEMBERS)
                    .as("FNAMEI at :219, LNAMEI at :223, PASSWDI at :227, USRTYPEI at :231")
                    .containsExactly("fName", "lName", "passwd", "usrType")
                    .allSatisfy(member -> assertThat(MAP_MEMBERS).contains(member));
            for (int index = 0; index < CHANGE_DETECTED_MEMBERS.size(); index++) {
                String member = CHANGE_DETECTED_MEMBERS.get(index);
                assertThat(PUBLISHED_WIDTHS.get(MAP_MEMBERS.indexOf(member)))
                        .as("%s is compared against a field of its own width, so the comparison is a "
                                + "plain equality of equal widths", member)
                        .isEqualTo(CHANGE_DETECTED_WIDTHS.get(index));
                assertThat(componentType(UserUpdateRequest.class, member))
                        .as("%s must be individually addressable, not folded into a collection",
                                member)
                        .isEqualTo(String.class);
            }
        }

        @Test
        @DisplayName("the identifier is the key, not an editable field, so it is not among the four")
        void theIdentifierIsTheKeyAndNotCompared() {
            assertThat(CHANGE_DETECTED_MEMBERS)
                    .as("COUSR02C.cbl:216 moves USRIDINI into SEC-USR-ID to re-read the record; it is "
                            + "never compared for change")
                    .doesNotContain("usrIdIn");
            assertThat(UserUpdateRequest.USRIDIN_LENGTH)
                    .as("and it is exactly the USRSEC key width, so the move needs no adjustment")
                    .isEqualTo(SecUserRecord.KEY_LENGTH);
            assertThat(MAP_MEMBERS.indexOf("usrIdIn"))
                    .as("it precedes all four, which is also the order the blank chain guards them in")
                    .isLessThan(MAP_MEMBERS.indexOf(CHANGE_DETECTED_MEMBERS.get(0)));
        }

        @Test
        @DisplayName("the four are independent: every subset of them is separately observable")
        void theFourComparisonsAreIndependentNotAChain() {
            SecUserRecord stored = SecUserRecord.of("USER0001", "LAWRENCE", "THOMAS",
                    NOT_A_REAL_PASSWORD, UserUpdateRequest.USER_TYPE_USER, MAP_CHARSET);

            for (int subset = 0; subset < 16; subset++) {
                List<String> values = new ArrayList<>(populatedMapValues());
                List<String> expectedDifferences = new ArrayList<>();
                if ((subset & 1) != 0) {
                    values.set(MAP_MEMBERS.indexOf("fName"),
                            codec().movePicX("LAWRENCIA", UserUpdateRequest.FNAME_LENGTH));
                    expectedDifferences.add("fName");
                }
                if ((subset & 2) != 0) {
                    values.set(MAP_MEMBERS.indexOf("lName"),
                            codec().movePicX("THOMSON", UserUpdateRequest.LNAME_LENGTH));
                    expectedDifferences.add("lName");
                }
                if ((subset & 4) != 0) {
                    values.set(MAP_MEMBERS.indexOf("passwd"), OTHER_NOT_A_REAL_PASSWORD);
                    expectedDifferences.add("passwd");
                }
                if ((subset & 8) != 0) {
                    values.set(MAP_MEMBERS.indexOf("usrType"), UserUpdateRequest.USER_TYPE_ADMIN);
                    expectedDifferences.add("usrType");
                }

                UserUpdateRequest keyed = requestOf(values, populatedContext(),
                        PfKeyResolver.AidKey.PFK05.token(), populatedCu02Info());
                List<String> observed = new ArrayList<>();
                if (!keyed.fName().equals(stored.secUsrFname())) {
                    observed.add("fName");
                }
                if (!keyed.lName().equals(stored.secUsrLname())) {
                    observed.add("lName");
                }
                if (!keyed.passwd().equals(stored.secUsrPwd())) {
                    observed.add("passwd");
                }
                if (!keyed.usrType().equals(stored.secUsrType())) {
                    observed.add("usrType");
                }

                assertThat(observed)
                        .as("subset %d: each of the four comparisons stands alone", subset)
                        .containsExactlyElementsOf(expectedDifferences);
                assertThat(validate(keyed))
                        .as("subset %d is a valid payload whatever it changed", subset)
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("the comparison is untrimmed, so trailing padding is significant")
        void theComparisonIsUntrimmed() {
            SecUserRecord stored = SecUserRecord.of("USER0001", "LAWRENCE", "THOMAS",
                    NOT_A_REAL_PASSWORD, UserUpdateRequest.USER_TYPE_USER, MAP_CHARSET);

            UserUpdateRequest padded = withMapValue("lName",
                    codec().movePicX("THOMAS", UserUpdateRequest.LNAME_LENGTH));
            UserUpdateRequest trimmed = withMapValue("lName", "THOMAS");

            assertThat(padded.lName())
                    .hasSize(UserUpdateRequest.LNAME_LENGTH)
                    .isEqualTo(stored.secUsrLname());
            assertThat(trimmed.lName())
                    .as("a trimmed value is a different value, and the payload keeps it as sent")
                    .isEqualTo("THOMAS")
                    .isNotEqualTo(stored.secUsrLname());
        }

        @Test
        @DisplayName("no version, entity tag, revision or timestamp member exists (gate G43 is N/A)")
        void noConcurrencyTokenExists() {
            List<String> forbidden = List.of("version", "etag", "revision", "timestamp", "updatedat",
                    "lastmodified", "sequence", "generation", "rowversion", "concurrency");

            List<String> names = new ArrayList<>();
            for (Class<?> owner : List.of(UserUpdateRequest.class,
                    UserUpdateRequest.Cu02Info.class)) {
                for (RecordComponent component : owner.getRecordComponents()) {
                    names.add(component.getName());
                }
                for (Method method : owner.getDeclaredMethods()) {
                    names.add(method.getName());
                }
            }

            for (String name : names) {
                for (String marker : forbidden) {
                    assertThat(name.toLowerCase(Locale.ROOT))
                            .as("%s suggests a concurrency token; COUSR02C has none", name)
                            .doesNotContain(marker);
                }
            }
            assertThat(jsonMembersOf(populatedRequest())).hasSize(COMPONENT_COUNT);
        }
    }

    @Nested
    @DisplayName("CDEMO-CU02-INFO - six items, 34 bytes, and a 194-byte communication area")
    class Cu02InfoExtension {
        @Test
        @DisplayName("the six items are declared with the COBOL names COUSR02C.cbl:51-58 spells")
        void theSixItemsAreDeclared() {
            List<String> declared =
                    Arrays.stream(UserUpdateRequest.Cu02Info.class.getRecordComponents())
                            .map(RecordComponent::getName)
                            .toList();

            assertThat(declared)
                    .as("the declared spellings are the COBOL abbreviations, not expanded English: "
                            + "pageNum for -PAGE-NUM, nextPageFlg for -NEXT-PAGE-FLG, usrSelFlg for "
                            + "-USR-SEL-FLG")
                    .containsExactly("usridFirst", "usridLast", "pageNum", "nextPageFlg",
                            "usrSelFlg", "usrSelected");
            assertThat(UserUpdateRequest.Cu02Info.initial().fieldImages().keySet())
                    .as("and each maps to the CDEMO-CU02-prefixed item name, in declaration order")
                    .containsExactlyElementsOf(CU02_ITEM_NAMES);
            assertThat(CU02_ITEM_NAMES)
                    .allSatisfy(name -> assertThat(name).startsWith("CDEMO-CU02-"));
        }

        @Test
        @DisplayName("the group is 34 bytes: 8 + 8 + 8 + 1 + 1 + 8")
        void theGroupIsThirtyFourBytes() {
            assertThat(List.of(UserUpdateRequest.Cu02Info.USRID_FIRST_LENGTH,
                    UserUpdateRequest.Cu02Info.USRID_LAST_LENGTH,
                    UserUpdateRequest.Cu02Info.PAGE_NUM_DIGITS,
                    UserUpdateRequest.Cu02Info.NEXT_PAGE_FLG_LENGTH,
                    UserUpdateRequest.Cu02Info.USR_SEL_FLG_LENGTH,
                    UserUpdateRequest.Cu02Info.USR_SELECTED_LENGTH))
                    .containsExactlyElementsOf(CU02_ITEM_WIDTHS);
            assertThat(UserUpdateRequest.Cu02Info.LENGTH)
                    .isEqualTo(CU02_INFO_LENGTH)
                    .isEqualTo(CU02_ITEM_WIDTHS.stream().mapToInt(Integer::intValue).sum());

            String image = String.join("", populatedCu02Info().fieldImages().values());
            assertThat(image).hasSize(CU02_INFO_LENGTH);
            assertThat(codec().encodeImage(image, "CDEMO-CU02-INFO"))
                    .as("encoded through the named code page (B8), it is 34 bytes")
                    .hasSize(CU02_INFO_LENGTH);
        }

        @Test
        @DisplayName("NavigationContext stays exactly 160 bytes, and the sections account for all of them")
        void theCommareaIsNotWidened() {
            assertThat(NavigationContext.COMMAREA_LENGTH)
                    .as("app/cpy/COCOM01Y.cpy:19-44 - shared by all seventeen controllers, so "
                            + "widening it for one program's private group would change the area "
                            + "every other program receives")
                    .isEqualTo(COMMAREA_LENGTH);
            assertThat(NavigationContext.GENERAL_INFO_LENGTH
                    + NavigationContext.CUSTOMER_INFO_LENGTH
                    + NavigationContext.ACCOUNT_INFO_LENGTH
                    + NavigationContext.CARD_INFO_LENGTH
                    + NavigationContext.MORE_INFO_LENGTH)
                    .as("34 general + 84 customer + 12 account + 16 card + 14 more = 160")
                    .isEqualTo(COMMAREA_LENGTH);
            assertThat(NavigationContext.MORE_INFO_LENGTH)
                    .as("CDEMO-LAST-MAP and CDEMO-LAST-MAPSET are PIC X(7) each, not X(8)")
                    .isEqualTo(NavigationContext.LAST_MAP_LENGTH
                            + NavigationContext.LAST_MAPSET_LENGTH)
                    .isEqualTo(14);
            assertThat(NavigationContext.empty().toFixedWidth(codec()))
                    .as("proved through the codec rather than asserted from the constant alone")
                    .hasSize(COMMAREA_LENGTH);

            assertThat(componentType(UserUpdateRequest.class, "cu02Info"))
                    .as("the extension is a member of its own, never folded into the shared area")
                    .isEqualTo(UserUpdateRequest.Cu02Info.class);
            assertThat(Arrays.stream(NavigationContext.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList())
                    .as("and nothing CDEMO-CU02-shaped has leaked into the shared type")
                    .doesNotContain("pageNum", "nextPageFlg", "usrSelFlg", "usrSelected",
                            "usridFirst", "usridLast");
        }

        @Test
        @DisplayName("160 plus 34 is the 194-byte area line 94 restores and line 137 returns")
        void theAreaThisProgramCarriesIsOneHundredAndNinetyFour() {
            byte[] commarea = populatedContext().toFixedWidth(codec());
            byte[] extension = codec().encodeImage(
                    String.join("", populatedCu02Info().fieldImages().values()), "CDEMO-CU02-INFO");

            assertThat(commarea).hasSize(COMMAREA_LENGTH);
            assertThat(extension).hasSize(CU02_INFO_LENGTH);
            assertThat(commarea.length + extension.length)
                    .as("EXEC CICS RETURN TRANSID(CU02) COMMAREA(CARDDEMO-COMMAREA) at "
                            + "COUSR02C.cbl:135-138 hands back all 194")
                    .isEqualTo(CU02_COMMAREA_LENGTH)
                    .isEqualTo(194);
        }

        @Test
        @DisplayName("initial() is what the VALUE clauses leave: spaces, zero, and 'N'")
        void initialIsTheValueClauseState() {
            UserUpdateRequest.Cu02Info initial = UserUpdateRequest.Cu02Info.initial();

            assertThat(initial.usridFirst())
                    .hasSize(UserUpdateRequest.Cu02Info.USRID_FIRST_LENGTH).isBlank();
            assertThat(initial.usridLast())
                    .hasSize(UserUpdateRequest.Cu02Info.USRID_LAST_LENGTH).isBlank();
            assertThat(initial.pageNum())
                    .as("CDEMO-CU02-PAGE-NUM declares no VALUE, so a cold start sees zero")
                    .isZero();
            assertThat(initial.nextPageFlg())
                    .as("VALUE 'N' at COUSR02C.cbl:54 - not a convention chosen here")
                    .isEqualTo(NEXT_PAGE_NO)
                    .hasSize(UserUpdateRequest.Cu02Info.NEXT_PAGE_FLG_LENGTH);
            assertThat(initial.usrSelFlg())
                    .hasSize(UserUpdateRequest.Cu02Info.USR_SEL_FLG_LENGTH).isBlank();
            assertThat(initial.usrSelected())
                    .hasSize(UserUpdateRequest.Cu02Info.USR_SELECTED_LENGTH).isBlank();
            assertThat(initial.fieldImages().get("CDEMO-CU02-PAGE-NUM"))
                    .as("and the group's image renders it as PIC 9(08), zero-filled")
                    .isEqualTo("00000000");
            assertThat(initial).isEqualTo(UserUpdateRequest.Cu02Info.initial());
        }

        @Test
        @DisplayName("a null group becomes initial(), because a PIC X group has no absent state")
        void aNullGroupIsNormalised() {
            assertThat(requestOf(populatedMapValues(), populatedContext(), "ENTER", null).cu02Info())
                    .isEqualTo(UserUpdateRequest.Cu02Info.initial());
            assertThat(requestOf(populatedMapValues(), populatedContext(), "ENTER",
                    populatedCu02Info()).cu02Info())
                    .as("and a supplied group is carried through untouched")
                    .isEqualTo(populatedCu02Info());
        }

        @Test
        @DisplayName("an item that was not supplied becomes its declared width in spaces, not null")
        void anAbsentItemBecomesSpaces() {
            UserUpdateRequest.Cu02Info sparse =
                    new UserUpdateRequest.Cu02Info(null, null, 0, null, null, null);

            assertThat(sparse.usridFirst())
                    .hasSize(UserUpdateRequest.Cu02Info.USRID_FIRST_LENGTH).isBlank();
            assertThat(sparse.usridLast())
                    .hasSize(UserUpdateRequest.Cu02Info.USRID_LAST_LENGTH).isBlank();
            assertThat(sparse.nextPageFlg())
                    .as("a null flag is one space, which satisfies neither 88-level")
                    .isEqualTo(NEITHER_PAGE_FLAG);
            assertThat(sparse.usrSelFlg()).isEqualTo(NEITHER_PAGE_FLAG);
            assertThat(sparse.usrSelected())
                    .hasSize(UserUpdateRequest.Cu02Info.USR_SELECTED_LENGTH).isBlank();
            assertThat(String.join("", sparse.fieldImages().values()))
                    .hasSize(CU02_INFO_LENGTH);
        }

        @Test
        @DisplayName("an over-wide item is truncated on the right, as a PIC X MOVE is (B11)")
        void anOverWideItemIsTruncatedOnTheRight() {
            UserUpdateRequest.Cu02Info wide = new UserUpdateRequest.Cu02Info("USER00010",
                    "USER00509", 0, "YES", "UPD", "USER00019");

            assertThat(wide.usridFirst())
                    .hasSize(UserUpdateRequest.Cu02Info.USRID_FIRST_LENGTH)
                    .isEqualTo("USER0001");
            assertThat(wide.usridLast()).isEqualTo("USER0050");
            assertThat(wide.nextPageFlg())
                    .as("'YES' into a PIC X(01) receiver keeps the leading character")
                    .isEqualTo(NEXT_PAGE_YES);
            assertThat(wide.usrSelFlg()).isEqualTo("U");
            assertThat(wide.usrSelected()).isEqualTo("USER0001");
            assertThat(String.join("", wide.fieldImages().values())).hasSize(CU02_INFO_LENGTH);
        }

        @Test
        @DisplayName("CDEMO-CU02-PAGE-NUM is PIC 9(08): a negative page number has no representation")
        void aNegativePageNumberIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new UserUpdateRequest.Cu02Info("", "", -1, NEXT_PAGE_NO, "", ""))
                    .withMessageContaining("CDEMO-CU02-PAGE-NUM")
                    .withMessageContaining("unsigned");
        }

        @Test
        @DisplayName("a page number needing more than eight digits is refused, not silently truncated")
        void anOverWidePageNumberIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new UserUpdateRequest.Cu02Info("", "", 100_000_000,
                            NEXT_PAGE_NO, "", ""))
                    .withMessageContaining("CDEMO-CU02-PAGE-NUM");
            assertThat(new UserUpdateRequest.Cu02Info("", "", 99_999_999, NEXT_PAGE_NO, "", "")
                    .fieldImages().get("CDEMO-CU02-PAGE-NUM"))
                    .as("the widest value the picture can hold is accepted and fills all eight digits")
                    .isEqualTo("99999999");
        }

        @ParameterizedTest(name = "page {0} renders as {1}")
        @CsvSource({"0, 00000000", "1, 00000001", "7, 00000007", "10, 00000010",
            "12345678, 12345678"})
        @DisplayName("the page number is integral and zero-filled to eight digits, never floating point")
        void thePageNumberIsIntegralAndZeroFilled(int page, String image) {
            UserUpdateRequest.Cu02Info info =
                    new UserUpdateRequest.Cu02Info("", "", page, NEXT_PAGE_NO, "", "");

            assertThat(info.pageNum()).isEqualTo(page);
            assertThat(info.fieldImages().get("CDEMO-CU02-PAGE-NUM"))
                    .isEqualTo(image)
                    .hasSize(UserUpdateRequest.Cu02Info.PAGE_NUM_DIGITS);
            assertThat(componentType(UserUpdateRequest.Cu02Info.class, "pageNum"))
                    .as("gate G22: PIC 9(08) is scale-free, so it is an int and never a double")
                    .isEqualTo(int.class);
        }

        @ParameterizedTest(name = "flag \"{0}\": yes={1}, no={2}")
        @CsvSource({"Y, true, false", "N, false, true", "' ', false, false", "A, false, false"})
        @DisplayName("both 88-levels are driven in both directions, plus a value that is neither")
        void bothPagingStatesAreDriven(String flag, boolean yes, boolean no) {
            UserUpdateRequest.Cu02Info info =
                    new UserUpdateRequest.Cu02Info("", "", 0, flag, "", "");

            assertThat(UserUpdateRequest.Cu02Info.NEXT_PAGE_YES.equals(info.nextPageFlg()))
                    .as("NEXT-PAGE-YES for %s", flag)
                    .isEqualTo(yes);
            assertThat(UserUpdateRequest.Cu02Info.NEXT_PAGE_NO.equals(info.nextPageFlg()))
                    .as("NEXT-PAGE-NO for %s", flag)
                    .isEqualTo(no);
            assertThat(yes && no)
                    .as("no value satisfies both conditions")
                    .isFalse();
            assertThat(info.nextPageFlg())
                    .hasSize(UserUpdateRequest.Cu02Info.NEXT_PAGE_FLG_LENGTH);
        }

        @Test
        @DisplayName("the two literals are the copybook's, and 'N' is also the field's own VALUE")
        void thePagingLiteralsAreTheSources() {
            assertThat(UserUpdateRequest.Cu02Info.NEXT_PAGE_YES).isEqualTo(NEXT_PAGE_YES);
            assertThat(UserUpdateRequest.Cu02Info.NEXT_PAGE_NO).isEqualTo(NEXT_PAGE_NO);
            assertThat(UserUpdateRequest.Cu02Info.initial().nextPageFlg())
                    .as("VALUE 'N' is the declared default, so a cold start reads NEXT-PAGE-NO")
                    .isEqualTo(UserUpdateRequest.Cu02Info.NEXT_PAGE_NO);
            assertThat(NEXT_PAGE_YES).isNotEqualTo(NEXT_PAGE_NO);
        }

        @Test
        @DisplayName("the selection flag and the selected identifier are carried, paging members and all")
        void theSelectionItemsAreCarried() {
            UserUpdateRequest.Cu02Info handedOver = populatedCu02Info();

            assertThat(handedOver.usrSelFlg())
                    .hasSize(UserUpdateRequest.Cu02Info.USR_SEL_FLG_LENGTH)
                    .isEqualTo("U");
            assertThat(handedOver.usrSelected())
                    .as("the row COUSR00C marked, which becomes USRIDINI at :101-102")
                    .isEqualTo("USER0001")
                    .hasSize(UserUpdateRequest.Cu02Info.USR_SELECTED_LENGTH)
                    .isNotBlank();
            assertThat(handedOver.usridFirst()).isEqualTo("USER0001");
            assertThat(handedOver.usridLast()).isEqualTo("USER0050");
            assertThat(handedOver.pageNum()).isEqualTo(1);

            assertThat(UserUpdateRequest.Cu02Info.initial().usrSelected()).isBlank();
        }

        @Test
        @DisplayName("the group is CDEMO-CU02 prefixed, so it is not the CU00 or CU03 group")
        void theGroupIsThisProgramsOwn() {
            assertThat(UserUpdateRequest.Cu02Info.class.getSimpleName()).isEqualTo("Cu02Info");
            assertThat(UserUpdateRequest.Cu02Info.class.getEnclosingClass())
                    .as("declared on the payload that owns it, not on a shared type")
                    .isEqualTo(UserUpdateRequest.class);
            assertThat(CU02_ITEM_NAMES)
                    .allSatisfy(name -> assertThat(name)
                            .startsWith("CDEMO-CU02-")
                            .doesNotContain("CDEMO-CU00-")
                            .doesNotContain("CDEMO-CU03-"));
            assertThat(UserUpdateRequest.Cu02Info.LENGTH)
                    .as("the shapes are identical, which is why only the prefix distinguishes them")
                    .isEqualTo(CU02_INFO_LENGTH);
        }
    }

    @Nested
    @DisplayName("Conversation state - the commarea, the AID and the extension travel in the payload")
    class ConversationState {
        @Test
        @DisplayName("all three state carriers are payload members, so no session is ever needed")
        void theStateCarriersArePayloadMembers() {
            assertThat(jsonMembersOf(populatedRequest()))
                    .containsAll(STATE_MEMBERS);
            assertThat(populatedRequest().navigationContext()).isNotNull();
            assertThat(populatedRequest().aid()).isNotNull();
            assertThat(populatedRequest().cu02Info()).isNotNull();
        }

        @Test
        @DisplayName("no session, thread-local or static cache mechanism is reachable")
        void noServerSideStateMechanismIsReachable() {
            List<String> forbidden = List.of("HttpSession", "Session", "ThreadLocal", "Cache",
                    "RequestContextHolder", "ServletRequest", "Cookie", "Scope");

            for (Class<?> type : reachableTypes()) {
                for (String marker : forbidden) {
                    assertThat(type.getName())
                            .as("%s suggests %s; a static holder would be a session by another name "
                                    + "and would break request isolation", type.getName(), marker)
                            .doesNotContain(marker);
                }
            }
        }

        @Test
        @DisplayName("the communication area is carried through untouched, never widened or re-modelled")
        void theContextIsPassedThrough() {
            NavigationContext context = populatedContext();
            UserUpdateRequest request = requestOf(populatedMapValues(), context,
                    PfKeyResolver.AidKey.PFK05.token(), populatedCu02Info());

            assertThat(request.navigationContext())
                    .as("the same instance, not a copy and not a widened variant")
                    .isSameAs(context);
            assertThat(request.navigationContext().fromProgram())
                    .as("CDEMO-FROM-PROGRAM is what PF3 echoes as its target at COUSR02C.cbl:116-117")
                    .isEqualTo(codec().movePicX("COUSR00C", NavigationContext.FROM_PROGRAM_LENGTH));
            assertThat(request.navigationContext().isAdmin()).isTrue();
            assertThat(request.navigationContext().lastMap()).isEqualTo(MAP_NAME);
            assertThat(request.navigationContext().lastMapset()).isEqualTo(MAPSET_NAME);
            assertThat(request.navigationContext().toFixedWidth(codec()))
                    .hasSize(NavigationContext.COMMAREA_LENGTH);
        }

        @Test
        @DisplayName("an absent communication area is a handled state, not an error")
        void anAbsentContextIsTheColdStart() {
            UserUpdateRequest cold = requestOf(nullMapValues(), null,
                    PfKeyResolver.AidKey.ENTER.token(), null);

            assertThat(cold.hasNavigationContext())
                    .as("EIBCALEN = 0 at COUSR02C.cbl:90, which transfers to COSGN00C")
                    .isFalse();
            assertThat(cold.contextIsEnter()).isFalse();
            assertThat(cold.contextIsReenter()).isFalse();
            assertThat(populatedRequest().hasNavigationContext()).isTrue();
        }

        @ParameterizedTest(name = "context {0}: enter={1}, reenter={2}")
        @CsvSource({"0, true, false", "1, false, true", "2, false, false", "9, false, false"})
        @DisplayName("both 88-level states are driven, in both directions, plus digits that are neither")
        void bothContextStatesAreDriven(int pgmContext, boolean enter, boolean reenter) {
            UserUpdateRequest request = requestOf(populatedMapValues(),
                    NavigationContext.empty().withPgmContext(pgmContext),
                    PfKeyResolver.AidKey.ENTER.token(), populatedCu02Info());

            assertThat(request.contextIsEnter()).as("CDEMO-PGM-ENTER for %d", pgmContext)
                    .isEqualTo(enter);
            assertThat(request.contextIsReenter()).as("CDEMO-PGM-REENTER for %d", pgmContext)
                    .isEqualTo(reenter);
            assertThat(request.hasNavigationContext()).isTrue();
            assertThat(!request.contextIsReenter())
                    .as("the first-entry arm at :95 is NOT CDEMO-PGM-REENTER, which is true for %d",
                            pgmContext)
                    .isEqualTo(!reenter);
        }

        @Test
        @DisplayName("the named context constants are the copybook's own values")
        void theContextConstantsAreTheCopybooks() {
            assertThat(NavigationContext.PGM_CONTEXT_ENTER)
                    .as("88 CDEMO-PGM-ENTER VALUE 0, COCOM01Y.cpy:30")
                    .isZero();
            assertThat(NavigationContext.PGM_CONTEXT_REENTER)
                    .as("88 CDEMO-PGM-REENTER VALUE 1, COCOM01Y.cpy:31")
                    .isEqualTo(1);
            assertThat(requestOf(populatedMapValues(), NavigationContext.empty().withPgmEnter(),
                    "ENTER", null).contextIsEnter()).isTrue();
            assertThat(requestOf(populatedMapValues(), NavigationContext.empty().withPgmReenter(),
                    "ENTER", null).contextIsReenter()).isTrue();
        }

        @ParameterizedTest(name = "aid = \"{0}\"")
        @ValueSource(strings = {"ENTER", "CLEAR", "PA1  ", "PA2  ", "PFK03", "PFK04", "PFK05",
            "PFK12"})
        @DisplayName("the AID arrives as a resolved five-character token, carried verbatim")
        void theAidIsCarriedAsAResolvedToken(String token) {
            UserUpdateRequest request = requestOf(populatedMapValues(), populatedContext(), token,
                    populatedCu02Info());

            assertThat(request.aid())
                    .as("trailing spaces included: CCARD-AID-PA1 is VALUE 'PA1  '")
                    .isEqualTo(token)
                    .hasSize(PfKeyResolver.AID_TOKEN_LENGTH);
            assertThat(componentType(UserUpdateRequest.class, "aid"))
                    .as("a token, never a byte")
                    .isEqualTo(String.class);
            assertThat(validate(request)).isEmpty();
            assertThat(serialise(request)).contains("\"aid\":\"" + token + "\"");
        }

        @ParameterizedTest(name = "EIBAID {0} resolves to {1}")
        @CsvSource({"ENTER, ENTER", "PF3, PFK03", "PF4, PFK04", "PF5, PFK05", "PF12, PFK12",
            "PF17, PFK05"})
        @DisplayName("every key this program dispatches on has a resolver token to travel as")
        void everyDispatchedKeyResolves(String key, String expectedToken) {
            byte eibAid = switch (key) {
                case "ENTER" -> CicsAid.DFHENTER;
                case "PF3" -> CicsAid.DFHPF3;
                case "PF4" -> CicsAid.DFHPF4;
                case "PF5" -> CicsAid.DFHPF5;
                case "PF12" -> CicsAid.DFHPF12;
                case "PF17" -> CicsAid.DFHPF17;
                default -> throw new IllegalArgumentException("Unhandled key " + key);
            };

            Optional<PfKeyResolver.AidKey> resolved = PfKeyResolver.resolve(eibAid);

            assertThat(resolved).isPresent();
            assertThat(resolved.orElseThrow().token())
                    .as("PF17 folds onto PFK05 exactly as CSSTRPFY.cpy does, so a 3270 that sends the "
                            + "high range still reaches the save path")
                    .isEqualTo(expectedToken);
            assertThat(requestOf(populatedMapValues(), populatedContext(),
                    resolved.orElseThrow().token(), populatedCu02Info()).aid())
                    .isEqualTo(expectedToken);
        }

        @Test
        @DisplayName("an unresolvable key is an empty result, and the payload can still carry nothing")
        void anUnresolvableKeyIsCarriedAsAbsent() {
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPA3))
                    .as("PA3 is not among the sixteen conditions CSSTRPFY declares")
                    .isEmpty();

            UserUpdateRequest request = requestOf(populatedMapValues(), populatedContext(), null,
                    populatedCu02Info());
            assertThat(request.aid()).isNull();
            assertThat(validate(request)).isEmpty();
            assertThat(SystemMessages.CCDA_MSG_INVALID_KEY)
                    .as("and the message that answers it fits the 78-wide field")
                    .hasSize(SystemMessages.MESSAGE_LENGTH);
        }
    }

    @Nested
    @DisplayName("REDEFINES - twelve attribute overlays, each over one shared byte")
    class RedefinesOverlays {
        @Test
        @DisplayName("the layout tiles 339 bytes exactly: 12 + 12 x 7 + 243")
        void theGeometryIsTheCopybooks() {
            assertThat(FIELD_PREFIX_LENGTH)
                    .as("xxxL 2 + xxxF 1 + FILLER X(4) = 7 per field")
                    .isEqualTo(7);
            assertThat(DECLARED_WIDTHS.stream().mapToInt(Integer::intValue).sum())
                    .isEqualTo(PAYLOAD_WIDTH_TOTAL);
            assertThat(TIOAPFX_PREFIX_LENGTH + DFHMDF_NAMED * FIELD_PREFIX_LENGTH
                    + PAYLOAD_WIDTH_TOTAL)
                    .isEqualTo(SYMBOLIC_MAP_LENGTH);
            assertThat(SYMBOLIC_MAP_LAYOUT.recordLength()).isEqualTo(SYMBOLIC_MAP_LENGTH);
            assertThat(SYMBOLIC_MAP_LAYOUT.redefinitions())
                    .as("twelve per-field overlays; the group-level one at line 91 is not modelled")
                    .hasSize(DFHMDF_NAMED);
            assertThat(PER_FIELD_REDEFINES_LINES).hasSize(DFHMDF_NAMED);
            assertThat(SYMBOLIC_MAP_LAYOUT.storageSpans().stream()
                    .mapToInt(FixedWidthRecord.FieldSpan::length).sum())
                    .as("the storage spans, overlays excluded, sum to the record length")
                    .isEqualTo(SYMBOLIC_MAP_LENGTH);
        }

        @Test
        @DisplayName("this package's redefinitions live entirely in its maps, never in its programs")
        void theRedefinitionsAreTheMapsAndNotThePrograms() {
            assertThat(COPYBOOK_REDEFINES_TOTAL)
                    .as("COUSR02.CPY: twelve per-field overlays plus the group view at line 91")
                    .isEqualTo(PER_FIELD_REDEFINES_LINES.size() + 1);
            assertThat(PACKAGE_REDEFINES_TOTAL)
                    .as("and the five maps of this package add up to 110")
                    .isEqualTo(12 + COUSR00_REDEFINES_TOTAL + 13 + COPYBOOK_REDEFINES_TOTAL + 12)
                    .isGreaterThan(COPYBOOK_REDEFINES_TOTAL);
            assertThat(SYMBOLIC_MAP_LAYOUT.redefinitions())
                    .as("of which this suite models the twelve that belong to the input view")
                    .hasSize(COPYBOOK_REDEFINES_TOTAL - 1);
        }

        @ParameterizedTest(name = "{0}A redefines {0}F")
        @ValueSource(strings = {"TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02", "CURTIME",
            "USRIDIN", "FNAME", "LNAME", "PASSWD", "USRTYPE", "ERRMSG"})
        @DisplayName("the flag view and the attribute view describe one and the same byte")
        void theTwoViewsShareOneByte(String screenField) {
            FixedWidthRecord.FieldSpan flag = SYMBOLIC_MAP_LAYOUT.span(screenField + "F");
            FixedWidthRecord.FieldSpan attribute = SYMBOLIC_MAP_LAYOUT.span(screenField + "A");

            assertThat(attribute.offset())
                    .as("%sA starts where %sF starts", screenField, screenField)
                    .isEqualTo(flag.offset());
            assertThat(attribute.length())
                    .as("both are PICTURE X - one byte, not a copy of one byte")
                    .isEqualTo(ATTRIBUTE_ITEM_LENGTH)
                    .isEqualTo(flag.length());
            assertThat(attribute.redefinition())
                    .as("%sA is declared through 02 FILLER REDEFINES %sF", screenField, screenField)
                    .isTrue();
            assertThat(flag.redefinition())
                    .as("%sF is the storage; only the overlay is a redefinition", screenField)
                    .isFalse();
            assertThat(attribute.kind()).isEqualTo(flag.kind())
                    .isEqualTo(FixedWidthRecord.PictureKind.ALPHANUMERIC);
        }

        @ParameterizedTest(name = "{0}: a write through either view is read by the other")
        @ValueSource(strings = {"TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02", "CURTIME",
            "USRIDIN", "FNAME", "LNAME", "PASSWD", "USRTYPE", "ERRMSG"})
        @DisplayName("the overlay round-trips in both directions, and touches nothing else")
        void theOverlayRoundTripsBothWays(String screenField) {
            FixedWidthRecord record = FixedWidthRecord.forLayout(SYMBOLIC_MAP_LAYOUT, MAP_CHARSET);
            FixedWidthRecord.FieldSpan flag = SYMBOLIC_MAP_LAYOUT.span(screenField + "F");
            FixedWidthRecord.FieldSpan attribute = SYMBOLIC_MAP_LAYOUT.span(screenField + "A");
            byte[] before = record.toByteArray();

            record.writeSpan(flag, "A");
            assertThat(record.readSpan(attribute)).isEqualTo("A");
            assertThat(record.readSpanBytes(attribute)).isEqualTo(record.readSpanBytes(flag));

            record.writeSpan(attribute, "Z");
            assertThat(record.readSpan(flag)).isEqualTo("Z");
            assertThat(record.readSpanBytes(flag)).isEqualTo(record.readSpanBytes(attribute));

            byte[] after = record.toByteArray();
            assertThat(after).hasSize(before.length).hasSize(SYMBOLIC_MAP_LENGTH);
            int differing = 0;
            for (int offset = 0; offset < after.length; offset++) {
                if (after[offset] != before[offset]) {
                    differing++;
                    assertThat(offset)
                            .as("only the shared attribute byte may change")
                            .isEqualTo(flag.offset());
                }
            }
            assertThat(differing).isEqualTo(ATTRIBUTE_ITEM_LENGTH);
        }

        @Test
        @DisplayName("writing an attribute leaves every xxxI item and every FILLER byte alone")
        void anAttributeWriteDoesNotDisturbTheData() {
            FixedWidthRecord record = FixedWidthRecord.forLayout(SYMBOLIC_MAP_LAYOUT, MAP_CHARSET);
            for (int index = 0; index < DFHMDF_NAMED; index++) {
                record.writeSpan(SYMBOLIC_MAP_LAYOUT.span(SYMBOLIC_MAP_ITEMS.get(index)),
                        "V".repeat(DECLARED_WIDTHS.get(index)));
            }
            for (String screenField : SCREEN_FIELDS) {
                record.writeSpan(SYMBOLIC_MAP_LAYOUT.span(screenField + "A"), "R");
            }
            for (int index = 0; index < DFHMDF_NAMED; index++) {
                assertThat(record.readSpan(SYMBOLIC_MAP_LAYOUT.span(SYMBOLIC_MAP_ITEMS.get(index))))
                        .as("%s must be untouched by the attribute writes",
                                SYMBOLIC_MAP_ITEMS.get(index))
                        .isEqualTo("V".repeat(DECLARED_WIDTHS.get(index)));
                assertThat(record.readSpan(SYMBOLIC_MAP_LAYOUT.span(SCREEN_FIELDS.get(index) + "F")))
                        .as("and each flag byte still reads what the overlay wrote")
                        .isEqualTo("R");
            }
            assertThat(record.toByteArray()).hasSize(SYMBOLIC_MAP_LENGTH);
        }

        @Test
        @DisplayName("a real attribute value goes through the overlay, and the data is still readable")
        void aRealAttributeValueGoesThroughTheOverlay() {
            FixedWidthRecord record = FixedWidthRecord.forLayout(SYMBOLIC_MAP_LAYOUT, MAP_CHARSET);
            FixedWidthRecord.FieldSpan errorFlag = SYMBOLIC_MAP_LAYOUT.span("ERRMSGF");
            FixedWidthRecord.FieldSpan errorAttribute = SYMBOLIC_MAP_LAYOUT.span("ERRMSGA");

            record.writeSpan(SYMBOLIC_MAP_LAYOUT.span("ERRMSGI"),
                    codec().movePicX("Please modify to update ...", UserUpdateRequest.ERRMSG_LENGTH));
            record.writeSpanBytes(errorAttribute, new byte[] {BmsAttributes.DFHRED});

            assertThat(record.readSpanBytes(errorFlag))
                    .as("read back through the other view of the same byte")
                    .isEqualTo(new byte[] {BmsAttributes.DFHRED});
            assertThat(record.readSpan(SYMBOLIC_MAP_LAYOUT.span("ERRMSGI")))
                    .as("and the message itself is unharmed")
                    .startsWith("Please modify to update ...")
                    .hasSize(UserUpdateRequest.ERRMSG_LENGTH);
            assertThat(BmsAttributes.isNonDisplay(BmsAttributes.DFHBMDAR))
                    .as("DRK is non-display, which is a terminal rendering property")
                    .isTrue();
            assertThat(record.toByteArray()).hasSize(SYMBOLIC_MAP_LENGTH);
        }
    }

    @Nested
    @DisplayName("Serialisation - padding survives, names are untransformed")
    class Serialisation {
        @Test
        @DisplayName("the mapper this suite uses carries the three settings the module configures")
        void theMapperMatchesTheModuleConfiguration() {
            ObjectMapper mapper = webConfigEquivalentMapper();

            assertThat(mapper.isEnabled(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)).isTrue();
            assertThat(mapper.getFactory().isEnabled(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN))
                    .isTrue();
            assertThat(mapper.isEnabled(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT))
                    .as("a default mapper would coerce an empty PIC X(n) value to null and silently "
                            + "break every blank-value assertion in this suite")
                    .isFalse();
            assertThat(mapper.getSerializationConfig().getPropertyNamingStrategy())
                    .as("no naming strategy, so each property still traces 1:1 to an xxxI item")
                    .isNull();
        }

        @Test
        @DisplayName("the member names are the component names, untransformed")
        void theMemberNamesAreUntransformed() {
            String payload = serialise(populatedRequest());

            for (String member : wireNamesOf(MAP_MEMBERS)) {
                assertThat(payload)
                        .as("%s appears as its xxxI item in lower case: not snake_case, not upper "
                                + "case, not renamed by a strategy", member)
                        .contains("\"" + member + "\":");
            }
            for (String member : STATE_MEMBERS) {
                assertThat(payload).contains("\"" + member + "\":");
            }
            assertThat(jsonMembersOf(populatedRequest()))
                    .containsExactlyInAnyOrderElementsOf(EXPECTED_JSON_MEMBERS);
        }

        @Test
        @DisplayName("a space-padded payload survives serialise then deserialise byte for byte")
        void spacePaddingSurvivesTheRoundTrip() {
            UserUpdateRequest original = new UserUpdateRequest(TRANSACTION_ID,
                    ScreenTitles.CCDA_TITLE01,
                    FIXED_CURDATE,
                    PROGRAM_NAME,
                    ScreenTitles.CCDA_TITLE02,
                    FIXED_CURTIME,
                    "USER    ",
                    codec().movePicX("LAWRENCE", UserUpdateRequest.FNAME_LENGTH),
                    codec().movePicX("THOMAS", UserUpdateRequest.LNAME_LENGTH),
                    NOT_A_REAL_PASSWORD,
                    UserUpdateRequest.USER_TYPE_USER,
                    codec().movePicX("", UserUpdateRequest.ERRMSG_LENGTH),
                    populatedContext(),
                    PfKeyResolver.AidKey.PA1.token(),
                    populatedCu02Info());

            UserUpdateRequest back = deserialise(serialise(original));

            assertThat(back).isEqualTo(original);
            assertThat(back.usrIdIn())
                    .as("an identifier keyed short of its field keeps the spaces the terminal sent")
                    .isEqualTo("USER    ")
                    .hasSize(UserUpdateRequest.USRIDIN_LENGTH);
            assertThat(back.fName())
                    .isEqualTo(original.fName())
                    .hasSize(UserUpdateRequest.FNAME_LENGTH);
            assertThat(back.lName()).hasSize(UserUpdateRequest.LNAME_LENGTH);
            assertThat(back.passwd())
                    .as("eight characters of plaintext, unchanged in either direction")
                    .isEqualTo(NOT_A_REAL_PASSWORD)
                    .hasSize(UserUpdateRequest.PASSWD_LENGTH);
            assertThat(back.errMsg())
                    .as("a blank message line is 78 spaces, not an empty string and not absent")
                    .hasSize(UserUpdateRequest.ERRMSG_LENGTH)
                    .isBlank();
            assertThat(back.aid())
                    .as("PA1 really does carry two trailing spaces in CCARD-AID PIC X(5)")
                    .isEqualTo("PA1  ")
                    .hasSize(PfKeyResolver.AID_TOKEN_LENGTH);
        }

        @Test
        @DisplayName("an empty value stays empty and an absent one stays absent; the two stay distinct")
        void emptyAndAbsentBothSurvive() {
            List<String> values = new ArrayList<>(blankMapValues());
            values.set(MAP_MEMBERS.indexOf("curDate"), null);
            values.set(MAP_MEMBERS.indexOf("lName"), null);

            UserUpdateRequest back = deserialise(serialise(
                    requestOf(values, NavigationContext.empty(), "ENTER", null)));

            assertThat(back.usrIdIn())
                    .as("SPACES survives as an empty string rather than being coerced to null")
                    .isNotNull()
                    .isEmpty();
            assertThat(back.curDate())
                    .as("LOW-VALUES survives as absent - a field the terminal never transmitted")
                    .isNull();
            assertThat(back.lName()).isNull();
            assertThat(jsonMembersOf(requestOf(values, NavigationContext.empty(), "ENTER", null)))
                    .as("an absent member is emitted rather than dropped, so the shape is stable")
                    .containsExactlyInAnyOrderElementsOf(EXPECTED_JSON_MEMBERS);
        }

        @Test
        @DisplayName("an absent communication area and AID round-trip as absent")
        void absentStateCarriersRoundTrip() {
            UserUpdateRequest cold = requestOf(nullMapValues(), null, null, null);

            UserUpdateRequest back = deserialise(serialise(cold));

            assertThat(back).isEqualTo(cold);
            assertThat(back.navigationContext()).isNull();
            assertThat(back.aid()).isNull();
            assertThat(back.hasNavigationContext()).isFalse();
            assertThat(back.cu02Info())
                    .as("and the extension is still normalised on the way back in")
                    .isEqualTo(UserUpdateRequest.Cu02Info.initial());
            assertThat(serialise(cold)).contains("\"navigationContext\":null", "\"aid\":null");
        }

        @Test
        @DisplayName("the communication area round-trips as a nested object, not as a string")
        void theNestedContextRoundTrips() {
            UserUpdateRequest original = populatedRequest();

            UserUpdateRequest back = deserialise(serialise(original));

            assertThat(back.navigationContext()).isEqualTo(original.navigationContext());
            assertThat(back.contextIsReenter()).isTrue();
            assertThat(serialise(original))
                    .as("a nested object, so the 160-byte area keeps its named items")
                    .contains("\"navigationContext\":{");
            assertThat(back.navigationContext().toFixedWidth(codec()))
                    .isEqualTo(original.navigationContext().toFixedWidth(codec()));
        }

        @Test
        @DisplayName("the extension round-trips with its six items and an integral page number")
        void theExtensionRoundTrips() {
            UserUpdateRequest original = populatedRequest();

            String payload = serialise(original);
            UserUpdateRequest back = deserialise(payload);

            assertThat(payload).contains("\"cu02Info\":{");
            for (String item : List.of("usridFirst", "usridLast", "pageNum", "nextPageFlg",
                    "usrSelFlg", "usrSelected")) {
                assertThat(payload).contains("\"" + item + "\":");
            }
            assertThat(payload)
                    .as("the page number is a JSON number, unquoted and with no exponent")
                    .contains("\"pageNum\":1");
            assertThat(back.cu02Info()).isEqualTo(original.cu02Info());
            assertThat(back.cu02Info().pageNum()).isEqualTo(1);
            assertThat(String.join("", back.cu02Info().fieldImages().values()))
                    .hasSize(CU02_INFO_LENGTH);
        }
    }

    @Nested
    @DisplayName("Security posture - plaintext as the program compares it, and no more")
    class SecurityPosture {
        @Test
        @DisplayName("passwd is a plaintext String of the width CSUSR01Y declares")
        void thePasswordIsAPlaintextStringOfEight() {
            assertThat(componentType(UserUpdateRequest.class, "passwd"))
                    .as("characters, not a digest, not a byte array, not an opaque credential type")
                    .isEqualTo(String.class);
            assertThat(UserUpdateRequest.PASSWD_LENGTH)
                    .isEqualTo(SEC_USR_PWD_LENGTH)
                    .isEqualTo(SecUserRecord.SEC_USR_PWD_LENGTH);
            assertThat(UserUpdateRequest.PASSWD_FIELD).isEqualTo("PASSWDI");
            assertThat(withMapValue("passwd", NOT_A_REAL_PASSWORD).passwd())
                    .isEqualTo(NOT_A_REAL_PASSWORD)
                    .hasSize(UserUpdateRequest.PASSWD_LENGTH);
        }

        @Test
        @DisplayName("an eight-character password survives the round trip byte for byte")
        void anEightCharacterPasswordSurvivesTheRoundTrip() {
            UserUpdateRequest original = withMapValue("passwd", NOT_A_REAL_PASSWORD);

            UserUpdateRequest back = deserialise(serialise(original));

            assertThat(back.passwd())
                    .as("if the value did not round trip, the comparison at COUSR02C.cbl:227 and the "
                            + "echo at :169 would both change behaviour")
                    .isEqualTo(NOT_A_REAL_PASSWORD)
                    .hasSize(UserUpdateRequest.PASSWD_LENGTH);
            assertThat(serialise(original)).contains(NOT_A_REAL_PASSWORD);
            assertThat(back).isEqualTo(original);
        }

        @Test
        @DisplayName("the payload does not hash, encode, upper-case or otherwise normalise the value")
        void thePayloadIsAPassiveCarrier() {
            String mixedCase = "aBcDeFgH";

            UserUpdateRequest request = withMapValue("passwd", mixedCase);

            assertThat(request.passwd()).isEqualTo(mixedCase);
            assertThat(deserialise(serialise(request)).passwd()).isEqualTo(mixedCase);
            assertThat(withMapValue("usrIdIn", "user0001").usrIdIn())
                    .as("nor is the identifier folded: COUSR02C.cbl:216 moves it as keyed")
                    .isEqualTo("user0001");
        }

        @Test
        @DisplayName("no hashing, encoder, token or security framework type is reachable")
        void noSecurityFrameworkTypeIsReachable() {
            List<String> forbidden = List.of("PasswordEncoder", "BCrypt", "MessageDigest", "Hash",
                    "org.springframework.security", "jwt", "Jwt", "Cipher", "SecretKey", "Base64",
                    "Authentication", "Principal");

            List<String> reachable = new ArrayList<>();
            for (Class<?> owner : List.of(UserUpdateRequest.class,
                    UserUpdateRequest.Cu02Info.class)) {
                for (RecordComponent component : owner.getRecordComponents()) {
                    reachable.add(component.getType().getName());
                    for (Annotation annotation : component.getAccessor().getAnnotations()) {
                        reachable.add(annotation.annotationType().getName());
                    }
                }
                for (Method method : owner.getDeclaredMethods()) {
                    reachable.add(method.getReturnType().getName());
                    reachable.add(method.getName());
                    for (Class<?> parameter : method.getParameterTypes()) {
                        reachable.add(parameter.getName());
                    }
                }
            }

            for (String name : reachable) {
                for (String marker : forbidden) {
                    assertThat(name)
                            .as("%s suggests %s; strengthening the comparison would change "
                                    + "observable behaviour and is out of scope", name, marker)
                            .doesNotContain(marker);
                }
            }
        }

        @Test
        @DisplayName("the DRK attribute on PASSWD is presentation masking, never storage masking")
        void theDarkAttributeIsPresentationOnly() {
            assertThat(BmsAttributes.isNonDisplay(BmsAttributes.DFHBMDAR))
                    .as("non-display, which is what DRK means")
                    .isTrue();
            assertThat(BmsAttributes.isProtected(BmsAttributes.DFHBMDAR))
                    .as("and still unprotected, because the operator has to be able to type into it")
                    .isFalse();

            UserUpdateRequest keyed = withMapValue("passwd", NOT_A_REAL_PASSWORD);
            assertThat(keyed.passwd())
                    .as("the payload carries the keyed characters, unmasked and uncounted")
                    .isEqualTo(NOT_A_REAL_PASSWORD);
            assertThat(serialise(keyed))
                    .as("and they are on the wire, because that is what the program compares")
                    .contains(NOT_A_REAL_PASSWORD);
        }

        @Test
        @DisplayName("the credential is bidirectional on this screen: :227 compares it and :169 echoes it")
        void theCredentialTravelsInBothDirections() {
            assertThat(MAP_MEMBERS)
                    .as("PASSWDI is a member of the INPUT view 01 COUSR2AI, COUSR02.CPY:78, so the "
                            + "operator keys it")
                    .contains("passwd");
            assertThat(UserUpdateRequest.PASSWD_FIELD).isEqualTo("PASSWDI");
            assertThat(SYMBOLIC_MAP_ITEMS.get(MAP_MEMBERS.indexOf("passwd")))
                    .as("and the output view COUSR2AO at COUSR02.CPY:91 redefines that same span, "
                            + "which is how :169 writes the stored value back over it")
                    .isEqualTo(UserUpdateRequest.PASSWD_FIELD);

            UserUpdateRequest echoed = withMapValue("passwd", NOT_A_REAL_PASSWORD);
            assertThat(deserialise(serialise(echoed)).passwd())
                    .as("the value the program echoes has to survive being sent back in unchanged")
                    .isEqualTo(NOT_A_REAL_PASSWORD)
                    .hasSize(UserUpdateRequest.PASSWD_LENGTH);
        }

        @Test
        @DisplayName("the diagnostic rendering withholds the password and the two names")
        void theDiagnosticRenderingWithholdsTheSensitiveFields() {
            UserUpdateRequest request = populatedRequest();
            String rendered = request.toString();

            assertThat(rendered)
                    .as("the keyed characters must not reach a log line")
                    .doesNotContain(NOT_A_REAL_PASSWORD)
                    .contains("passwd=");
            assertThat(rendered)
                    .as("names are personal data, so the content is dropped and only the shape kept")
                    .doesNotContain(request.fName().strip())
                    .doesNotContain(request.lName().strip())
                    .contains("fName=")
                    .contains("lName=");
            assertThat(rendered)
                    .as("and everything a parity failure has to be diagnosed from stays legible")
                    .contains(TRANSACTION_ID)
                    .contains(PROGRAM_NAME)
                    .contains(FIXED_CURDATE)
                    .contains(FIXED_CURTIME)
                    .contains("usrIdIn=USER0001")
                    .startsWith("UserUpdateRequest[")
                    .endsWith("]");
        }

        @Test
        @DisplayName("the password rendering reveals neither the value nor its length")
        void theRenderedPasswordRevealsNothing() {
            String eight = withMapValue("passwd", NOT_A_REAL_PASSWORD).toString();
            String alsoEight = withMapValue("passwd", OTHER_NOT_A_REAL_PASSWORD).toString();
            String two = withMapValue("passwd", "AB").toString();

            assertThat(alsoEight)
                    .as("different content, same rendering")
                    .isEqualTo(eight);
            assertThat(two)
                    .as("different length, still the same rendering - no length is disclosed either")
                    .isEqualTo(eight);
            assertThat(eight)
                    .doesNotContain(NOT_A_REAL_PASSWORD)
                    .doesNotContain(OTHER_NOT_A_REAL_PASSWORD);
        }

        @Test
        @DisplayName("a name's shape is reported, so equal lengths render alike and unequal ones do not")
        void theRenderedNamesDiscloseShapeOnly() {
            String lawrence = withMapValue("fName",
                    codec().movePicX("LAWRENCE", UserUpdateRequest.FNAME_LENGTH)).toString();
            String hermione = withMapValue("fName",
                    codec().movePicX("HERMIONE", UserUpdateRequest.FNAME_LENGTH)).toString();
            String short5 = withMapValue("fName", "SHORT").toString();

            assertThat(hermione)
                    .as("two twenty-character names are indistinguishable in the rendering")
                    .isEqualTo(lawrence);
            assertThat(short5)
                    .as("a five-character name is not, because the shape differs")
                    .isNotEqualTo(lawrence);
            assertThat(lawrence).doesNotContain("LAWRENCE").doesNotContain("HERMIONE");
        }

        @Test
        @DisplayName("an absent password is reported as absent, because presence is not content")
        void anAbsentPasswordIsReportedAsAbsent() {
            String absent = withMapValue("passwd", null).toString();

            assertThat(absent).contains("passwd=null");
            assertThat(absent)
                    .as("and absent is distinguishable from present, which is the whole point")
                    .isNotEqualTo(withMapValue("passwd", NOT_A_REAL_PASSWORD).toString());
            assertThat(withMapValue("passwd", null).passwd()).isNull();
        }

        @Test
        @DisplayName("equals and hashCode still include the password, because they disclose nothing")
        void valueSemanticsIncludeThePassword() {
            UserUpdateRequest one = withMapValue("passwd", NOT_A_REAL_PASSWORD);
            UserUpdateRequest other = withMapValue("passwd", OTHER_NOT_A_REAL_PASSWORD);

            assertThat(one).isNotEqualTo(other);
            assertThat(one).isEqualTo(withMapValue("passwd", NOT_A_REAL_PASSWORD));
            assertThat(one).hasSameHashCodeAs(withMapValue("passwd", NOT_A_REAL_PASSWORD));
            assertThat(one.cu02Info()).isEqualTo(other.cu02Info());
        }
    }
}

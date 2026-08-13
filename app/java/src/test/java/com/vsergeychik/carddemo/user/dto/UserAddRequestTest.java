package com.vsergeychik.carddemo.user.dto;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SensitiveDiagnostics;
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
 * Unit tests for {@link UserAddRequest} - the inbound payload of {@code POST /api/users}, CICS transaction
 * {@code CU01}, program {@code app/cbl/COUSR01C.cbl}, map {@code COUSR1A} of mapset {@code COUSR01}: the
 * Add User screen.
 */
@DisplayName("UserAddRequest - the COUSR01 (CU01) Add User inbound payload")
class UserAddRequestTest {
    private static final Charset MAP_CHARSET = StandardCharsets.US_ASCII;

    private static final String MAP_NAME = "COUSR1A";

    private static final String MAPSET_NAME = "COUSR01";

    private static final String TRANSACTION_ID = "CU01";

    private static final String PROGRAM_NAME = "COUSR01C";

    private static final int DFHMDF_TOTAL = 28;

    private static final int DFHMDF_NAMED = 12;

    private static final List<String> SCREEN_FIELDS = List.of(
            "TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02", "CURTIME",
            "FNAME", "LNAME", "USERID", "PASSWD", "USRTYPE", "ERRMSG");

    private static final List<String> SYMBOLIC_MAP_ITEMS = List.of(
            "TRNNAMEI", "TITLE01I", "CURDATEI", "PGMNAMEI", "TITLE02I", "CURTIMEI",
            "FNAMEI", "LNAMEI", "USERIDI", "PASSWDI", "USRTYPEI", "ERRMSGI");

    private static final List<String> MAP_MEMBERS = List.of(
            "trnName", "title01", "curDate", "pgmName", "title02", "curTime",
            "fName", "lName", "userId", "passwd", "usrType", "errMsg");

    private static final List<String> STATE_MEMBERS = List.of("navigationContext", "aid");

    private static String wireNameOf(String member) {
        return MAP_MEMBERS.contains(member) ? member.toLowerCase(Locale.ROOT) : member;
    }

    private static List<String> wireNamesOf(List<String> members) {
        return members.stream().map(UserAddRequestTest::wireNameOf).toList();
    }

    private static final int COMPONENT_COUNT = DFHMDF_NAMED + 2;

    private static final List<Integer> DECLARED_WIDTHS =
            List.of(4, 40, 8, 8, 40, 8, 20, 20, 8, 8, 1, 78);

    private static final List<Integer> COPYBOOK_LINES =
            List.of(24, 30, 36, 42, 48, 54, 60, 66, 72, 78, 84, 90);

    private static final List<Integer> MAPSET_LINES =
            List.of(34, 38, 47, 57, 61, 70, 84, 97, 111, 126, 141, 151);

    private static final List<Integer> MAPSET_LENGTH_LINES =
            List.of(36, 40, 49, 59, 63, 72, 87, 100, 114, 129, 144, 153);

    private static final List<Integer> REDEFINES_LINES =
            List.of(21, 27, 33, 39, 45, 51, 57, 63, 69, 75, 81, 87);

    private static final int GROUP_REDEFINES_LINE = 91;

    private static final int TIOAPFX_PREFIX_LENGTH = 12;

    private static final int LENGTH_ITEM_LENGTH = 2;

    private static final int ATTRIBUTE_ITEM_LENGTH = 1;

    private static final int ATTRIBUTE_FILLER_LENGTH = 4;

    private static final int FIELD_PREFIX_LENGTH =
            LENGTH_ITEM_LENGTH + ATTRIBUTE_ITEM_LENGTH + ATTRIBUTE_FILLER_LENGTH;

    private static final int PAYLOAD_WIDTH_TOTAL = 243;

    private static final int SYMBOLIC_MAP_LENGTH = 339;

    private static final int SEC_USR_ID_LENGTH = 8;

    private static final int SEC_USR_FNAME_LENGTH = 20;

    private static final int SEC_USR_LNAME_LENGTH = 20;

    private static final int SEC_USR_PWD_LENGTH = 8;

    private static final int SEC_USR_TYPE_LENGTH = 1;

    private static final int SEC_USR_FILLER_LENGTH = 23;

    private static final int SEC_USER_DATA_LENGTH = 80;

    private static final int WS_MESSAGE_LENGTH = 80;

    private static final List<String> BLANK_CHAIN_ITEMS =
            List.of("FNAMEI", "LNAMEI", "USERIDI", "PASSWDI", "USRTYPEI");

    private static final List<String> BLANK_CHAIN_MESSAGES = List.of(
            "First Name can NOT be empty...",
            "Last Name can NOT be empty...",
            "User ID can NOT be empty...",
            "Password can NOT be empty...",
            "User Type can NOT be empty...");

    private static final FixedWidthRecord.RecordLayout SYMBOLIC_MAP_LAYOUT = symbolicMapLayout();

    private static final Instant FIXED_INSTANT = Instant.parse("2022-07-19T23:12:34Z");

    private static final String FIXED_CURDATE = "07/19/22";

    private static final String FIXED_CURTIME = "23:12:34";

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
        mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        mapper.configure(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN, true);
        mapper.disable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT);
        return mapper;
    }

    private static UserAddRequest requestOf(List<String> mapValues, NavigationContext context,
            String aid) {
        return new UserAddRequest(mapValues.get(0), mapValues.get(1), mapValues.get(2),
                mapValues.get(3), mapValues.get(4), mapValues.get(5), mapValues.get(6),
                mapValues.get(7), mapValues.get(8), mapValues.get(9), mapValues.get(10),
                mapValues.get(11), context, aid);
    }

    private static List<String> mapValuesOf(UserAddRequest request) {
        return Arrays.asList(request.trnName(), request.title01(), request.curDate(),
                request.pgmName(), request.title02(), request.curTime(), request.fName(),
                request.lName(), request.userId(), request.passwd(), request.usrType(),
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

    private static List<String> paddedMapValues() {
        List<String> values = new ArrayList<>(DFHMDF_NAMED);
        for (int index = 0; index < DFHMDF_NAMED; index++) {
            values.add(" ".repeat(DECLARED_WIDTHS.get(index)));
        }
        return values;
    }

    private static List<String> withMember(List<String> values, String member, String value) {
        List<String> copy = new ArrayList<>(values);
        copy.set(MAP_MEMBERS.indexOf(member), value);
        return copy;
    }

    private static List<String> populatedMapValues() {
        FixedWidthCodec codec = codec();
        return Arrays.asList(
                TRANSACTION_ID,
                ScreenTitles.CCDA_TITLE01,
                FIXED_CURDATE,
                PROGRAM_NAME,
                ScreenTitles.CCDA_TITLE02,
                FIXED_CURTIME,
                codec.movePicX("JANE", UserAddRequest.FNAME_LENGTH),
                codec.movePicX("DOE", UserAddRequest.LNAME_LENGTH),
                "NEWUSR01",
                "NOTAREAL",
                NavigationContext.USER_TYPE_USER,
                codec.movePicX(BLANK_CHAIN_MESSAGES.get(0), UserAddRequest.ERRMSG_LENGTH));
    }

    private static UserAddRequest populatedRequest() {
        return requestOf(populatedMapValues(),
                NavigationContext.empty()
                        .withFromTranid(TRANSACTION_ID)
                        .withFromProgram(PROGRAM_NAME)
                        .withLastMap(MAP_NAME)
                        .withLastMapset(MAPSET_NAME)
                        .withPgmReenter(),
                PfKeyResolver.AidKey.ENTER.token());
    }

    private static Set<ConstraintViolation<UserAddRequest>> validate(UserAddRequest request) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(request);
        }
    }

    private static String serialise(UserAddRequest request) {
        try {
            return webConfigEquivalentMapper().writeValueAsString(request);
        } catch (IOException failure) {
            throw new UncheckedIOException("Serialising a UserAddRequest must not fail", failure);
        }
    }

    private static UserAddRequest deserialise(String json) {
        try {
            return webConfigEquivalentMapper().readValue(json, UserAddRequest.class);
        } catch (IOException failure) {
            throw new UncheckedIOException("Deserialising a UserAddRequest must not fail", failure);
        }
    }

    private static Set<String> jsonMembersOf(UserAddRequest request) {
        try {
            Map<String, Object> tree = webConfigEquivalentMapper()
                    .readValue(serialise(request), new TypeReference<Map<String, Object>>() { });
            return tree.keySet();
        } catch (IOException failure) {
            throw new UncheckedIOException("Reading a serialised UserAddRequest must not fail",
                    failure);
        }
    }

    private static List<String> componentNames() {
        List<String> names = new ArrayList<>(COMPONENT_COUNT);
        for (RecordComponent component : UserAddRequest.class.getRecordComponents()) {
            names.add(component.getName());
        }
        return names;
    }

    private static List<String> reachableTypeNames() {
        List<String> reachable = new ArrayList<>();
        for (RecordComponent component : UserAddRequest.class.getRecordComponents()) {
            reachable.add(component.getType().getName());
            reachable.add(component.getName());
            for (Annotation annotation : component.getAccessor().getAnnotations()) {
                reachable.add(annotation.annotationType().getName());
            }
        }
        for (Method method : UserAddRequest.class.getDeclaredMethods()) {
            reachable.add(method.getReturnType().getName());
            reachable.add(method.getName());
            for (Class<?> parameter : method.getParameterTypes()) {
                reachable.add(parameter.getName());
            }
        }
        for (Field field : UserAddRequest.class.getDeclaredFields()) {
            reachable.add(field.getType().getName());
            reachable.add(field.getName());
        }
        return reachable;
    }

    private static List<Integer> publishedWidths() {
        return List.of(UserAddRequest.TRNNAME_LENGTH, UserAddRequest.TITLE01_LENGTH,
                UserAddRequest.CURDATE_LENGTH, UserAddRequest.PGMNAME_LENGTH,
                UserAddRequest.TITLE02_LENGTH, UserAddRequest.CURTIME_LENGTH,
                UserAddRequest.FNAME_LENGTH, UserAddRequest.LNAME_LENGTH,
                UserAddRequest.USERID_LENGTH, UserAddRequest.PASSWD_LENGTH,
                UserAddRequest.USRTYPE_LENGTH, UserAddRequest.ERRMSG_LENGTH);
    }

    @Nested
    @DisplayName("Projection of 01 COUSR1AI - twelve map members, in copybook order")
    class MapProjection {
        @Test
        @DisplayName("fourteen components: the twelve map members then the two state members")
        void componentCensus() {
            assertThat(UserAddRequest.class.isRecord())
                    .as("an immutable projection of a screen contract, not a mutable bean")
                    .isTrue();
            assertThat(UserAddRequest.class.getRecordComponents()).hasSize(COMPONENT_COUNT);

            List<String> expected = new ArrayList<>(MAP_MEMBERS);
            expected.addAll(STATE_MEMBERS);
            assertThat(componentNames())
                    .as("declaration order is the copybook's order, so the class can be eye-diffed "
                            + "against app/cpy-bms/COUSR01.CPY a line at a time")
                    .isEqualTo(expected);
            assertThat(UserAddRequest.MAP_FIELD_NAMES)
                    .as("one entry per name-labelled DFHMDF of app/bms/COUSR01.bms")
                    .isEqualTo(SYMBOLIC_MAP_ITEMS)
                    .hasSize(DFHMDF_NAMED);
        }

        @Test
        @DisplayName("every map member is a String, because every xxxI item is PIC X(n)")
        void everyMapMemberIsCharacter() {
            RecordComponent[] components = UserAddRequest.class.getRecordComponents();
            for (int index = 0; index < DFHMDF_NAMED; index++) {
                assertThat(components[index].getType())
                        .as("%s projects %s PIC X(%d)", components[index].getName(),
                                SYMBOLIC_MAP_ITEMS.get(index), DECLARED_WIDTHS.get(index))
                        .isEqualTo(String.class);
            }
            for (RecordComponent component : components) {
                assertThat(component.getType())
                        .as("%s must never be a binary floating-point type", component.getName())
                        .isNotEqualTo(double.class).isNotEqualTo(Double.class)
                        .isNotEqualTo(float.class).isNotEqualTo(Float.class);
            }
            assertThat(components[DFHMDF_NAMED].getType())
                    .as("the communication area is the one shared type, never a copy of its fields")
                    .isEqualTo(NavigationContext.class);
            assertThat(components[DFHMDF_NAMED + 1].getType())
                    .as("the AID travels as a resolved token, so a String")
                    .isEqualTo(String.class);
        }

        @Test
        @DisplayName("the mapset declares 28 DFHMDF fields, so 16 stay unexposed screen furniture")
        void sixteenDefinitionsAreLiteralFurniture() {
            assertThat(DFHMDF_TOTAL)
                    .as("app/bms/COUSR01.bms - every DFHMDF, labelled or not")
                    .isEqualTo(28);
            assertThat(DFHMDF_NAMED)
                    .as("of those, the ones carrying a name label in column one")
                    .isEqualTo(12);
            assertThat(DFHMDF_TOTAL - DFHMDF_NAMED)
                    .as("captions, prompts, the '(8 Char)' and '(A=Admin, U=User)' hints and the "
                            + "function-key legend; the terminal paints them and the program never "
                            + "reads them, so none can be a payload member")
                    .isEqualTo(16);
            assertThat(SCREEN_FIELDS).hasSize(DFHMDF_NAMED);
            assertThat(MAPSET_LINES).hasSize(DFHMDF_NAMED);
            assertThat(UserAddRequest.MAP_FIELD_NAMES).hasSize(DFHMDF_NAMED);
        }

        @ParameterizedTest(name = "{0} is {1} wide at COUSR01.CPY:{2}")
        @CsvSource({
            "TRNNAMEI,   4, 24", "TITLE01I, 40, 30", "CURDATEI,  8, 36", "PGMNAMEI,  8, 42",
            "TITLE02I,  40, 48", "CURTIMEI,  8, 54", "FNAMEI,   20, 60", "LNAMEI,   20, 66",
            "USERIDI,    8, 72", "PASSWDI,   8, 78", "USRTYPEI,  1, 84", "ERRMSGI,  78, 90",
        })
        @DisplayName("each published width is the width its xxxI PICTURE clause declares")
        void publishedWidthMatchesTheCopybook(String item, int width, int copybookLine) {
            int position = UserAddRequest.MAP_FIELD_NAMES.indexOf(item);
            assertThat(position).as("%s must be a payload field", item).isNotNegative();
            assertThat(COPYBOOK_LINES.get(position))
                    .as("%s is declared at app/cpy-bms/COUSR01.CPY:%d", item, copybookLine)
                    .isEqualTo(copybookLine);
            assertThat(DECLARED_WIDTHS.get(position)).isEqualTo(width);
            assertThat(publishedWidths().get(position))
                    .as("the class must publish the copybook's width, not a rounded or shared one")
                    .isEqualTo(width);
        }

        @ParameterizedTest(name = "DFHMDF {0} LENGTH={1} at COUSR01.bms:{2}")
        @CsvSource({
            "TRNNAME,  4,  36", "TITLE01, 40,  40", "CURDATE,  8,  49", "PGMNAME,  8,  59",
            "TITLE02, 40,  63", "CURTIME,  8,  72", "FNAME,   20,  87", "LNAME,   20, 100",
            "USERID,   8, 114", "PASSWD,   8, 129", "USRTYPE,  1, 144", "ERRMSG,  78, 153",
        })
        @DisplayName("the mapset's LENGTH operand agrees, independently of the copybook")
        void publishedWidthMatchesTheMapset(String screenField, int length, int lengthLine) {
            int position = SCREEN_FIELDS.indexOf(screenField);
            assertThat(position).as("%s must be name-labelled", screenField).isNotNegative();
            assertThat(MAPSET_LENGTH_LINES.get(position))
                    .as("%s declares LENGTH at app/bms/COUSR01.bms:%d", screenField, lengthLine)
                    .isEqualTo(lengthLine);
            assertThat(publishedWidths().get(position)).isEqualTo(length)
                    .isEqualTo(DECLARED_WIDTHS.get(position));
        }

        @Test
        @DisplayName("every member traces to one screen field, and its xxxI item is that field plus I")
        void everyMemberTracesToAScreenField() {
            for (int index = 0; index < DFHMDF_NAMED; index++) {
                String screenField = SCREEN_FIELDS.get(index);
                assertThat(UserAddRequest.MAP_FIELD_NAMES.get(index))
                        .as("DFHMDF %s at app/bms/COUSR01.bms:%d backs the symbolic-map item",
                                screenField, MAPSET_LINES.get(index))
                        .isEqualTo(screenField + "I")
                        .isEqualTo(SYMBOLIC_MAP_ITEMS.get(index));
                assertThat(MAP_MEMBERS.get(index).toUpperCase(Locale.ROOT))
                        .as("the component name is the field name in Java casing")
                        .isEqualTo(screenField);
            }
        }

        @Test
        @DisplayName("the field-name constants are the copybook's spellings, one per member")
        void theFieldNameConstantsAreTheCopybooks() {
            assertThat(UserAddRequest.TRNNAME_FIELD).isEqualTo("TRNNAMEI");
            assertThat(UserAddRequest.TITLE01_FIELD).isEqualTo("TITLE01I");
            assertThat(UserAddRequest.CURDATE_FIELD).isEqualTo("CURDATEI");
            assertThat(UserAddRequest.PGMNAME_FIELD).isEqualTo("PGMNAMEI");
            assertThat(UserAddRequest.TITLE02_FIELD).isEqualTo("TITLE02I");
            assertThat(UserAddRequest.CURTIME_FIELD).isEqualTo("CURTIMEI");
            assertThat(UserAddRequest.FNAME_FIELD).isEqualTo("FNAMEI");
            assertThat(UserAddRequest.LNAME_FIELD).isEqualTo("LNAMEI");
            assertThat(UserAddRequest.USERID_FIELD).isEqualTo("USERIDI");
            assertThat(UserAddRequest.PASSWD_FIELD).isEqualTo("PASSWDI");
            assertThat(UserAddRequest.USRTYPE_FIELD).isEqualTo("USRTYPEI");
            assertThat(UserAddRequest.ERRMSG_FIELD).isEqualTo("ERRMSGI");
        }

        @Test
        @DisplayName("the screen identity literals are byte-exact and fit the commarea's PIC X(7)")
        void screenIdentityLiteralsAreByteExact() {
            assertThat(UserAddRequest.TRANSACTION_ID)
                    .as("WS-TRANID VALUE 'CU01' at app/cbl/COUSR01C.cbl:37, corroborated by "
                            + "app/csd/CARDDEMO.CSD:459")
                    .isEqualTo(TRANSACTION_ID)
                    .hasSize(UserAddRequest.TRNNAME_LENGTH);
            assertThat(UserAddRequest.PROGRAM_NAME)
                    .as("WS-PGMNAME VALUE 'COUSR01C' at app/cbl/COUSR01C.cbl:36, corroborated by "
                            + "app/csd/CARDDEMO.CSD:285 and :460")
                    .isEqualTo(PROGRAM_NAME)
                    .hasSize(UserAddRequest.PGMNAME_LENGTH);
            assertThat(UserAddRequest.MAP_NAME)
                    .as("MAP('COUSR1A') at app/cbl/COUSR01C.cbl:191")
                    .isEqualTo(MAP_NAME)
                    .hasSize(NavigationContext.LAST_MAP_LENGTH);
            assertThat(UserAddRequest.MAPSET_NAME)
                    .as("MAPSET('COUSR01') at app/cbl/COUSR01C.cbl:192")
                    .isEqualTo(MAPSET_NAME)
                    .hasSize(NavigationContext.LAST_MAPSET_LENGTH);
            assertThat(UserAddRequest.AID_LENGTH)
                    .as("CCARD-AID is PIC X(5) and the resolver produces exactly that width")
                    .isEqualTo(PfKeyResolver.AID_TOKEN_LENGTH);
        }

        @Test
        @DisplayName("declares no static mutable state, and its name list is unmodifiable")
        void declaresNoStaticMutableState() {
            for (Field field : UserAddRequest.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("static field %s must be final (G53)", field.getName())
                            .isTrue();
                }
            }
            assertThat(UserAddRequest.MAP_FIELD_NAMES).isUnmodifiable();
        }
    }

    @Nested
    @DisplayName("Cross-screen traps - USERID, names-before-id, curTime 8, errMsg 78")
    class CrossScreenTraps {
        @Test
        @DisplayName("TRAP 1: the identifier item is USERIDI, never the USRIDINI of COUSR00/02/03")
        void theIdentifierItemIsUseridAndNotUsridin() {
            assertThat(UserAddRequest.USERID_FIELD)
                    .as("app/cpy-bms/COUSR01.CPY:72")
                    .isEqualTo("USERIDI");
            assertThat(UserAddRequest.MAP_FIELD_NAMES)
                    .contains("USERIDI")
                    .doesNotContain("USRIDINI", "USRIDIN");
            assertThat(SCREEN_FIELDS)
                    .as("the DFHMDF at app/bms/COUSR01.bms:111 is labelled USERID")
                    .contains("USERID")
                    .doesNotContain("USRIDIN");
            assertThat(componentNames())
                    .as("and the component is userId, not usrIdIn")
                    .contains("userId")
                    .doesNotContain("usrIdIn", "usrIdin");
        }

        @Test
        @DisplayName("TRAP 2: the input fields run names first, then identifier, password and type")
        void theInputFieldOrderPutsTheNamesFirst() {
            List<String> inputItems = UserAddRequest.MAP_FIELD_NAMES.subList(6, 11);
            assertThat(inputItems)
                    .containsExactly("FNAMEI", "LNAMEI", "USERIDI", "PASSWDI", "USRTYPEI")
                    .isEqualTo(BLANK_CHAIN_ITEMS);
            assertThat(componentNames().subList(6, 11))
                    .containsExactly("fName", "lName", "userId", "passwd", "usrType");

            assertThat(UserAddRequest.MAP_FIELD_NAMES.indexOf("USERIDI"))
                    .as("the identifier follows both names, which is what makes First Name arm one")
                    .isGreaterThan(UserAddRequest.MAP_FIELD_NAMES.indexOf("FNAMEI"))
                    .isGreaterThan(UserAddRequest.MAP_FIELD_NAMES.indexOf("LNAMEI"));
            assertThat(BLANK_CHAIN_ITEMS.get(0))
                    .as("arm one, at app/cbl/COUSR01C.cbl:118")
                    .isEqualTo("FNAMEI");
        }

        @Test
        @DisplayName("TRAP 2 consequence: with two fields blank, the earlier field decides the outcome")
        void theEarlierBlankFieldDecidesTheOutcome() {
            UserAddRequest bothBlank = requestOf(
                    withMember(withMember(populatedMapValues(), "fName", " ".repeat(
                            UserAddRequest.FNAME_LENGTH)), "userId", " ".repeat(
                            UserAddRequest.USERID_LENGTH)),
                    NavigationContext.empty().withPgmReenter(),
                    PfKeyResolver.AidKey.ENTER.token());

            assertThat(bothBlank.fName()).isBlank().hasSize(UserAddRequest.FNAME_LENGTH);
            assertThat(bothBlank.userId()).isBlank().hasSize(UserAddRequest.USERID_LENGTH);
            assertThat(bothBlank.lName())
                    .as("the field between them is populated, so arm two cannot be the winner")
                    .isNotBlank();

            UserAddRequest restored = deserialise(serialise(bothBlank));
            assertThat(restored.fName()).isEqualTo(bothBlank.fName());
            assertThat(restored.userId()).isEqualTo(bothBlank.userId());
            assertThat(BLANK_CHAIN_ITEMS.indexOf("FNAMEI"))
                    .isLessThan(BLANK_CHAIN_ITEMS.indexOf("USERIDI"));
        }

        @Test
        @DisplayName("TRAP 3: curTime is eight characters here, not the nine of COSGN00")
        void curTimeIsEightNotNine() {
            assertThat(UserAddRequest.CURTIME_LENGTH).isEqualTo(8);
            assertThat(UserAddRequest.CURTIME_LENGTH)
                    .as("eight is the same width as curDate on this screen; on COSGN00 it is one more")
                    .isEqualTo(UserAddRequest.CURDATE_LENGTH);
            assertThat("hh:mm:ss")
                    .as("HH:MM:SS is eight characters, which is exactly what this field holds")
                    .hasSize(UserAddRequest.CURTIME_LENGTH);
        }

        @Test
        @DisplayName("TRAP 3 driven: the eight-character header time fills curTime with no padding")
        void theHeaderTimeFillsCurTimeExactly() {
            DateHeader header = DateHeader.from(codec(), Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
            assertThat(header.wsCurtimeHhMmSs())
                    .as("WS-CURTIME-HH-MM-SS is eight characters, composed at COUSR01C.cbl:229-233")
                    .isEqualTo(FIXED_CURTIME)
                    .hasSize(DateHeader.WS_CURTIME_HH_MM_SS_LENGTH);

            String moved = codec().movePicX(header.wsCurtimeHhMmSs(), UserAddRequest.CURTIME_LENGTH);
            assertThat(moved)
                    .as("eight into eight is neither padded nor truncated; on COSGN00's nine-wide "
                            + "field the same move would pad one space on the right")
                    .isEqualTo(FIXED_CURTIME)
                    .hasSize(UserAddRequest.CURTIME_LENGTH)
                    .doesNotEndWith(" ");
        }

        @Test
        @DisplayName("TRAP 3 companion: the header date fills curDate exactly, also without padding")
        void theHeaderDateFillsCurDateExactly() {
            DateHeader header = DateHeader.from(codec(), Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
            assertThat(header.wsCurdateMmDdYy())
                    .as("WS-CURDATE-MM-DD-YY, composed at COUSR01C.cbl:223-227 and moved at :227")
                    .isEqualTo(FIXED_CURDATE)
                    .hasSize(DateHeader.WS_CURDATE_MM_DD_YY_LENGTH);
            assertThat(codec().movePicX(header.wsCurdateMmDdYy(), UserAddRequest.CURDATE_LENGTH))
                    .isEqualTo(FIXED_CURDATE)
                    .hasSize(UserAddRequest.CURDATE_LENGTH);
        }

        @Test
        @DisplayName("TRAP 4: errMsg is 78 while WS-MESSAGE is 80, so the move discards two characters")
        void errMsgNarrowsTheEightyByteMessage() {
            assertThat(WS_MESSAGE_LENGTH)
                    .as("app/cbl/COUSR01C.cbl:38 declares 05 WS-MESSAGE PIC X(80) VALUE SPACES")
                    .isEqualTo(UserAddRequest.ERRMSG_LENGTH + 2);
            assertThat(UserAddRequest.ERRMSG_LENGTH)
                    .as("app/cpy-bms/COUSR01.CPY:90 and app/bms/COUSR01.bms:153 both say 78")
                    .isEqualTo(78);

            String message = "A".repeat(WS_MESSAGE_LENGTH - 2) + "YZ";
            assertThat(message).hasSize(WS_MESSAGE_LENGTH);

            String moved = codec().movePicX(message, UserAddRequest.ERRMSG_LENGTH);
            assertThat(moved)
                    .as("COUSR01C.cbl:188 moves WS-MESSAGE into ERRMSGO, a narrower receiver; a COBOL "
                            + "alphanumeric MOVE fills from the left and discards the overflow")
                    .hasSize(UserAddRequest.ERRMSG_LENGTH)
                    .isEqualTo("A".repeat(WS_MESSAGE_LENGTH - 2))
                    .doesNotContain("Y")
                    .doesNotContain("Z");
        }

        @Test
        @DisplayName("TRAP 4 direction: a PIC X move truncates on the RIGHT, not the left")
        void theMoveTruncatesOnTheRight() {
            assertThat(codec().movePicX("ABCDEF", UserAddRequest.TRNNAME_LENGTH))
                    .as("a PIC X receiver keeps the leading characters")
                    .isEqualTo("ABCD");
            assertThat(codec().movePicX("AU", UserAddRequest.USRTYPE_LENGTH))
                    .as("a one-byte receiver keeps the first byte, so 'AU' becomes 'A'")
                    .isEqualTo("A");
        }

        @Test
        @DisplayName("TRAP 4 corollary: no message the blank chain produces is long enough to be cut")
        void noBlankChainMessageIsTruncated() {
            assertThat(BLANK_CHAIN_MESSAGES).hasSize(BLANK_CHAIN_ITEMS.size()).hasSize(5);
            for (int index = 0; index < BLANK_CHAIN_MESSAGES.size(); index++) {
                String message = BLANK_CHAIN_MESSAGES.get(index);
                assertThat(message.length())
                        .as("arm %d's message must fit ERRMSG intact", index + 1)
                        .isLessThanOrEqualTo(UserAddRequest.ERRMSG_LENGTH);
                String moved = codec().movePicX(message, UserAddRequest.ERRMSG_LENGTH);
                assertThat(moved)
                        .hasSize(UserAddRequest.ERRMSG_LENGTH)
                        .startsWith(message);
                assertThat(moved.substring(message.length()))
                        .as("the remainder of a PIC X receiver is spaces, not nulls and not zeros")
                        .isEqualTo(" ".repeat(UserAddRequest.ERRMSG_LENGTH - message.length()));
                assertThat(moved.strip()).isEqualTo(message);
            }
        }

        @Test
        @DisplayName("the two titles are forty characters and carry the screen titles byte for byte")
        void titlesAreFortyCharacters() {
            assertThat(UserAddRequest.TITLE01_LENGTH)
                    .isEqualTo(ScreenTitles.TITLE_LENGTH).isEqualTo(40);
            assertThat(UserAddRequest.TITLE02_LENGTH)
                    .isEqualTo(ScreenTitles.TITLE_LENGTH).isEqualTo(40);

            assertThat(ScreenTitles.CCDA_TITLE01)
                    .hasSize(UserAddRequest.TITLE01_LENGTH)
                    .contains("AWS Mainframe Modernization")
                    .startsWith(" ")
                    .endsWith(" ");
            assertThat(ScreenTitles.CCDA_TITLE02)
                    .hasSize(UserAddRequest.TITLE02_LENGTH)
                    .contains("CardDemo")
                    .startsWith(" ")
                    .endsWith(" ");
            assertThat(codec().movePicX(ScreenTitles.CCDA_TITLE01, UserAddRequest.TITLE01_LENGTH))
                    .isEqualTo(ScreenTitles.CCDA_TITLE01);
        }

        @Test
        @DisplayName("the two thank-you literals are different texts of different widths")
        void theThankYouLiteralsAreNotInterchangeable() {
            assertThat(ScreenTitles.CCDA_THANK_YOU).hasSize(ScreenTitles.TITLE_LENGTH).hasSize(40);
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU)
                    .hasSize(SystemMessages.MESSAGE_LENGTH).hasSize(50);
            assertThat(ScreenTitles.CCDA_THANK_YOU)
                    .isNotEqualTo(SystemMessages.CCDA_MSG_THANK_YOU);
            assertThat(ScreenTitles.CCDA_THANK_YOU.strip())
                    .isNotEqualTo(SystemMessages.CCDA_MSG_THANK_YOU.strip());
            assertThat(SystemMessages.MESSAGE_LENGTH)
                    .as("a PIC X(50) message does not fit this screen's PIC X(40) title field")
                    .isGreaterThan(UserAddRequest.TITLE01_LENGTH);
        }

        @Test
        @DisplayName("the five input widths are the USRSEC record's own, field for field")
        void theFiveInputWidthsMatchTheSecurityRecord() {
            assertThat(UserAddRequest.FNAME_LENGTH)
                    .as("FNAMEI -> SEC-USR-FNAME at COUSR01C.cbl:155")
                    .isEqualTo(SEC_USR_FNAME_LENGTH)
                    .isEqualTo(SecUserRecord.SEC_USR_FNAME_LENGTH);
            assertThat(UserAddRequest.LNAME_LENGTH)
                    .as("LNAMEI -> SEC-USR-LNAME at COUSR01C.cbl:156")
                    .isEqualTo(SEC_USR_LNAME_LENGTH)
                    .isEqualTo(SecUserRecord.SEC_USR_LNAME_LENGTH);
            assertThat(UserAddRequest.USERID_LENGTH)
                    .as("USERIDI -> SEC-USR-ID at COUSR01C.cbl:154, and the USRSEC key")
                    .isEqualTo(SEC_USR_ID_LENGTH)
                    .isEqualTo(SecUserRecord.SEC_USR_ID_LENGTH)
                    .isEqualTo(SecUserRecord.KEY_LENGTH);
            assertThat(UserAddRequest.PASSWD_LENGTH)
                    .as("PASSWDI -> SEC-USR-PWD at COUSR01C.cbl:157")
                    .isEqualTo(SEC_USR_PWD_LENGTH)
                    .isEqualTo(SecUserRecord.SEC_USR_PWD_LENGTH);
            assertThat(UserAddRequest.USRTYPE_LENGTH)
                    .as("USRTYPEI -> SEC-USR-TYPE at COUSR01C.cbl:158")
                    .isEqualTo(SEC_USR_TYPE_LENGTH)
                    .isEqualTo(SecUserRecord.SEC_USR_TYPE_LENGTH);
        }

        @Test
        @DisplayName("the USRSEC offsets and its 80-byte total corroborate those five widths")
        void theSecurityRecordGeometryCorroboratesTheWidths() {
            assertThat(SecUserRecord.SEC_USR_ID_OFFSET).isZero();
            assertThat(SecUserRecord.SEC_USR_FNAME_OFFSET).isEqualTo(8);
            assertThat(SecUserRecord.SEC_USR_LNAME_OFFSET).isEqualTo(28);
            assertThat(SecUserRecord.SEC_USR_PWD_OFFSET).isEqualTo(48);
            assertThat(SecUserRecord.SEC_USR_TYPE_OFFSET).isEqualTo(56);
            assertThat(SecUserRecord.SEC_USR_FILLER_OFFSET).isEqualTo(57);
            assertThat(SecUserRecord.SEC_USR_FILLER_LENGTH)
                    .as("05 SEC-USR-FILLER PIC X(23) at app/cpy/CSUSR01Y.cpy:23")
                    .isEqualTo(SEC_USR_FILLER_LENGTH);
            assertThat(SecUserRecord.RECORD_LENGTH)
                    .as("8 + 20 + 20 + 8 + 1 + 23")
                    .isEqualTo(SEC_USER_DATA_LENGTH)
                    .isEqualTo(SEC_USR_ID_LENGTH + SEC_USR_FNAME_LENGTH + SEC_USR_LNAME_LENGTH
                            + SEC_USR_PWD_LENGTH + SEC_USR_TYPE_LENGTH + SEC_USR_FILLER_LENGTH);
            assertThat(SecUserRecord.KEY_OFFSET)
                    .as("RIDFLD(SEC-USR-ID) at COUSR01C.cbl:244 keys on the leading eight bytes")
                    .isZero();
        }

        @Test
        @DisplayName("this screen's user type is one byte, and admin and user are its two values")
        void theUserTypeIsOneByte() {
            assertThat(UserAddRequest.USRTYPE_LENGTH).isEqualTo(1);
            assertThat(NavigationContext.USER_TYPE_ADMIN)
                    .as("88 CDEMO-USRTYP-ADMIN VALUE 'A' at app/cpy/COCOM01Y.cpy:27")
                    .isEqualTo("A").hasSize(UserAddRequest.USRTYPE_LENGTH);
            assertThat(NavigationContext.USER_TYPE_USER)
                    .as("88 CDEMO-USRTYP-USER VALUE 'U' at app/cpy/COCOM01Y.cpy:28")
                    .isEqualTo("U").hasSize(UserAddRequest.USRTYPE_LENGTH);

            UserAddRequest odd = requestOf(withMember(populatedMapValues(), "usrType", "X"),
                    NavigationContext.empty(), "");
            assertThat(odd.usrType()).isEqualTo("X");
            assertThat(validate(odd))
                    .as("the program stores an unexpected type rather than rejecting it, so no "
                            + "constraint may reject it here either")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("Metadata - xxxL, xxxF, xxxA and every FILLER stay off the wire")
    class MetadataIsNotPayload {
        @Test
        @DisplayName("the serialised payload carries exactly the fourteen expected member names")
        void theWireCarriesOnlyTheDeclaredMembers() {
            assertThat(jsonMembersOf(populatedRequest()))
                    .containsExactlyInAnyOrderElementsOf(EXPECTED_JSON_MEMBERS)
                    .hasSize(COMPONENT_COUNT);
        }

        @ParameterizedTest(name = "{0}L, {0}F and {0}A are absent")
        @ValueSource(strings = {"TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02", "CURTIME",
            "FNAME", "LNAME", "USERID", "PASSWD", "USRTYPE", "ERRMSG"})
        @DisplayName("no length, flag or attribute item appears under any spelling")
        void noMetadataItemIsAMember(String screenField) {
            String json = serialise(populatedRequest());
            List<String> componentNames = componentNames();
            for (String suffix : List.of("L", "F", "A", "C", "P", "H", "V")) {
                String item = screenField + suffix;
                assertThat(json)
                        .as("%s is terminal mechanics and must not reach the wire", item)
                        .doesNotContain("\"" + item + "\"");
                for (String component : componentNames) {
                    assertThat(component.toUpperCase(Locale.ROOT))
                            .as("no component may project %s", item)
                            .isNotEqualTo(item);
                }
            }
            assertThat(UserAddRequest.MAP_FIELD_NAMES).contains(screenField + "I");
        }

        @Test
        @DisplayName("the xxxL items are the cursor carrier, which is why they are not data")
        void theLengthItemsAreTheCursorCarrier() {
            assertThat(LENGTH_ITEM_LENGTH)
                    .as("COMP PIC S9(4) is a binary halfword - two bytes, not a character field")
                    .isEqualTo(2);
            String json = serialise(populatedRequest());
            for (String screenField : SCREEN_FIELDS) {
                assertThat(json).doesNotContain(screenField + "L");
            }
            assertThat(componentNames())
                    .doesNotContain("fNameL", "lNameL", "userIdL", "passwdL", "usrTypeL");
        }

        @Test
        @DisplayName("neither the TIOAPFX prefix nor any per-field filler is exposed")
        void noFillerIsExposed() {
            assertThat(TIOAPFX_PREFIX_LENGTH)
                    .as("02 FILLER PIC X(12) at app/cpy-bms/COUSR01.CPY:18")
                    .isEqualTo(12);
            assertThat(ATTRIBUTE_FILLER_LENGTH)
                    .as("02 FILLER PICTURE X(4), once per field")
                    .isEqualTo(4);

            String json = serialise(populatedRequest());
            assertThat(json)
                    .doesNotContain("FILLER").doesNotContain("filler")
                    .doesNotContain("TIOAPFX").doesNotContain("tioapfx");
            for (String component : componentNames()) {
                assertThat(component.toLowerCase(Locale.ROOT))
                        .as("%s must not project reserved storage", component)
                        .doesNotContain("filler").doesNotContain("tioapfx").doesNotContain("reserved");
            }
        }

        @Test
        @DisplayName("the derived context predicates are withheld, so a payload cannot contradict itself")
        void theDerivedPredicatesAreWithheld() throws NoSuchMethodException {
            Set<String> members = jsonMembersOf(populatedRequest());
            assertThat(members).doesNotContain("pgmEnter", "pgmReenter", "pgmContext");
            assertThat(componentNames()).doesNotContain("pgmEnter", "pgmReenter", "pgmContext");

            Method enter = UserAddRequest.class.getDeclaredMethod("pgmEnter");
            Method reenter = UserAddRequest.class.getDeclaredMethod("pgmReenter");
            for (Method predicate : List.of(enter, reenter)) {
                assertThat(predicate.getName())
                        .as("%s must not be shaped like a bean getter", predicate.getName())
                        .doesNotStartWith("get").doesNotStartWith("is");
                assertThat(predicate.getReturnType()).isEqualTo(boolean.class);
                assertThat(predicate.getParameterCount()).isZero();
                assertThat(Modifier.isStatic(predicate.getModifiers())).isFalse();
            }
        }
    }

    @Nested
    @DisplayName("REDEFINES - twelve attribute overlays, each over one shared byte")
    class RedefinesOverlays {
        @Test
        @DisplayName("the layout tiles 339 bytes exactly: 12 + 12 x 7 + 243")
        void theGeometryIsTheCopybooks() {
            assertThat(FIELD_PREFIX_LENGTH)
                    .as("xxxL 2 + xxxF 1 + FILLER X(4) = 7, and the output view's prefix is also 7, "
                            + "which is what lets COUSR1AO overlay COUSR1AI field for field at line 91")
                    .isEqualTo(7);
            assertThat(DECLARED_WIDTHS.stream().mapToInt(Integer::intValue).sum())
                    .isEqualTo(PAYLOAD_WIDTH_TOTAL);
            assertThat(TIOAPFX_PREFIX_LENGTH + DFHMDF_NAMED * FIELD_PREFIX_LENGTH
                    + PAYLOAD_WIDTH_TOTAL).isEqualTo(SYMBOLIC_MAP_LENGTH);
            assertThat(SYMBOLIC_MAP_LAYOUT.recordLength()).isEqualTo(SYMBOLIC_MAP_LENGTH);
            assertThat(SYMBOLIC_MAP_LAYOUT.storageSpans().stream()
                    .mapToInt(FixedWidthRecord.FieldSpan::length).sum())
                    .as("the storage spans, overlays excluded, sum to the record length; an omitted "
                            + "FILLER would make this short and every later offset wrong")
                    .isEqualTo(SYMBOLIC_MAP_LENGTH);
        }

        @Test
        @DisplayName("twelve per-field overlays are modelled, and the thirteenth is not this file's")
        void twelveOverlaysAreModelled() {
            assertThat(SYMBOLIC_MAP_LAYOUT.redefinitions())
                    .as("one 02 FILLER REDEFINES xxxF per field, at COUSR01.CPY lines %s",
                            REDEFINES_LINES)
                    .hasSize(DFHMDF_NAMED);
            assertThat(REDEFINES_LINES).hasSize(DFHMDF_NAMED);
            assertThat(REDEFINES_LINES)
                    .as("each REDEFINES sits two lines above its own xxxI item")
                    .containsExactly(21, 27, 33, 39, 45, 51, 57, 63, 69, 75, 81, 87);
            for (int index = 0; index < DFHMDF_NAMED; index++) {
                assertThat(COPYBOOK_LINES.get(index) - REDEFINES_LINES.get(index))
                        .as("the group is REDEFINES, xxxA, FILLER, xxxI - three lines apart")
                        .isEqualTo(3);
            }
            assertThat(GROUP_REDEFINES_LINE)
                    .as("01 COUSR1AO REDEFINES COUSR1AI - the output view, and UserAddResponseTest's "
                            + "subject rather than this file's")
                    .isEqualTo(91)
                    .isGreaterThan(COPYBOOK_LINES.get(DFHMDF_NAMED - 1));
            assertThat(REDEFINES_LINES.size() + 1)
                    .as("twelve per-field plus one group-level is the copybook's thirteen")
                    .isEqualTo(13);
        }

        @ParameterizedTest(name = "{0}A redefines {0}F")
        @ValueSource(strings = {"TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02", "CURTIME",
            "FNAME", "LNAME", "USERID", "PASSWD", "USRTYPE", "ERRMSG"})
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
            assertThat(attribute.endOffsetExclusive()).isEqualTo(flag.endOffsetExclusive());
        }

        @ParameterizedTest(name = "{0}: a write through either view is read by the other")
        @ValueSource(strings = {"TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02", "CURTIME",
            "FNAME", "LNAME", "USERID", "PASSWD", "USRTYPE", "ERRMSG"})
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
        @DisplayName("the xxxI data spans sit at the offsets the copybook's own tiling produces")
        void theDataSpansSitWhereTheCopybookPutsThem() {
            int cursor = TIOAPFX_PREFIX_LENGTH;
            for (int index = 0; index < DFHMDF_NAMED; index++) {
                FixedWidthRecord.FieldSpan data =
                        SYMBOLIC_MAP_LAYOUT.span(SYMBOLIC_MAP_ITEMS.get(index));
                FixedWidthRecord.FieldSpan flag =
                        SYMBOLIC_MAP_LAYOUT.span(SCREEN_FIELDS.get(index) + "F");
                assertThat(flag.offset())
                        .as("%sF follows the xxxL halfword", SCREEN_FIELDS.get(index))
                        .isEqualTo(cursor + LENGTH_ITEM_LENGTH);
                assertThat(data.offset())
                        .as("%s begins after its seven-byte prefix", SYMBOLIC_MAP_ITEMS.get(index))
                        .isEqualTo(cursor + FIELD_PREFIX_LENGTH);
                assertThat(data.length()).isEqualTo(DECLARED_WIDTHS.get(index));
                cursor += FIELD_PREFIX_LENGTH + DECLARED_WIDTHS.get(index);
            }
            assertThat(cursor)
                    .as("the walk ends exactly at the declared record length")
                    .isEqualTo(SYMBOLIC_MAP_LENGTH);
            assertThat(SYMBOLIC_MAP_LAYOUT.hasSpan("ERRMSGI")).isTrue();
            assertThat(SYMBOLIC_MAP_LAYOUT.hasSpan("USRIDINI"))
                    .as("the sibling screens' identifier item has no place in this map")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("Conversation state - the commarea and the AID travel in the payload")
    class ConversationState {
        @Test
        @DisplayName("the communication area is a payload member, so no session is ever needed")
        void theCommareaIsAPayloadMember() {
            assertThat(componentNames()).contains("navigationContext");
            assertThat(jsonMembersOf(populatedRequest())).contains("navigationContext");
            assertThat(populatedRequest().navigationContext()).isNotNull();

            List<String> forbidden = List.of("HttpSession", "SessionAttribute", "SessionScope",
                    "ThreadLocal", "Cache", "ServletRequest", "HttpServlet");
            for (String name : reachableTypeNames()) {
                for (String marker : forbidden) {
                    assertThat(name)
                            .as("%s suggests server-side state, which rule R6 forbids", name)
                            .doesNotContain(marker);
                }
            }
            for (Field field : UserAddRequest.class.getDeclaredFields()) {
                assertThat(Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(
                        field.getModifiers()))
                        .as("%s must not be a mutable static holder", field.getName())
                        .isFalse();
            }
        }

        @Test
        @DisplayName("the commarea is exactly 160 bytes, and the sections account for all of them")
        void theCommareaIsOneHundredAndSixtyBytes() {
            assertThat(NavigationContext.COMMAREA_LENGTH).isEqualTo(160);
            assertThat(NavigationContext.GENERAL_INFO_LENGTH)
                    .as("lines 20-31: 4 + 8 + 4 + 8 + 8 + 1 + 1")
                    .isEqualTo(34);
            assertThat(NavigationContext.CUSTOMER_INFO_LENGTH)
                    .as("lines 32-36: 9 + 25 + 25 + 25")
                    .isEqualTo(84);
            assertThat(NavigationContext.ACCOUNT_INFO_LENGTH)
                    .as("lines 37-39: 11 + 1")
                    .isEqualTo(12);
            assertThat(NavigationContext.CARD_INFO_LENGTH)
                    .as("lines 40-41: 16")
                    .isEqualTo(16);
            assertThat(NavigationContext.MORE_INFO_LENGTH)
                    .as("lines 42-44: 7 + 7, and both are PIC X(7) rather than X(8)")
                    .isEqualTo(14);
            assertThat(NavigationContext.GENERAL_INFO_LENGTH + NavigationContext.CUSTOMER_INFO_LENGTH
                    + NavigationContext.ACCOUNT_INFO_LENGTH + NavigationContext.CARD_INFO_LENGTH
                    + NavigationContext.MORE_INFO_LENGTH)
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH);

            byte[] image = populatedRequest().navigationContext().toFixedWidth(codec());
            assertThat(image).hasSize(NavigationContext.COMMAREA_LENGTH);

            assertThat(NavigationContext.fromFixedWidth(codec(), image))
                    .isEqualTo(populatedRequest().navigationContext());
        }

        @Test
        @DisplayName("the last map and mapset are seven characters, which this screen's names fit")
        void theMapNamesAreSevenCharacters() {
            assertThat(NavigationContext.LAST_MAP_LENGTH)
                    .as("app/cpy/COCOM01Y.cpy:43 declares CDEMO-LAST-MAP PIC X(7), not X(8)")
                    .isEqualTo(7);
            assertThat(NavigationContext.LAST_MAPSET_LENGTH)
                    .as("app/cpy/COCOM01Y.cpy:44 declares CDEMO-LAST-MAPSET PIC X(7)")
                    .isEqualTo(7);

            NavigationContext context = populatedRequest().navigationContext();
            assertThat(context.lastMap()).isEqualTo(MAP_NAME).hasSize(7);
            assertThat(context.lastMapset()).isEqualTo(MAPSET_NAME).hasSize(7);

            assertThatIllegalArgumentException()
                    .as("an eight-character map name has no representation in PIC X(7)")
                    .isThrownBy(() -> NavigationContext.empty().withLastMap("COUSR1AX"));
        }

        @Test
        @DisplayName("the AID arrives already resolved to its five-character token")
        void theAidIsCarriedAsAResolvedToken() {
            assertThat(UserAddRequest.AID_LENGTH)
                    .as("CCARD-AID is PIC X(5), and the resolver produces exactly that width")
                    .isEqualTo(PfKeyResolver.AID_TOKEN_LENGTH)
                    .isEqualTo(5);

            for (PfKeyResolver.AidKey key : PfKeyResolver.AidKey.values()) {
                assertThat(key.token())
                        .as("%s must be a full-width token; a trimmed one would be the wrong width",
                                key.name())
                        .hasSize(UserAddRequest.AID_LENGTH);
            }
            assertThat(PfKeyResolver.AidKey.PA1.token()).isEqualTo("PA1  ");
            assertThat(PfKeyResolver.AidKey.PA2.token()).isEqualTo("PA2  ");
            assertThat(PfKeyResolver.AidKey.ENTER.token()).isEqualTo("ENTER");
            assertThat(PfKeyResolver.AidKey.CLEAR.token()).isEqualTo("CLEAR");

            assertThat(PfKeyResolver.resolve(CicsAid.DFHENTER))
                    .contains(PfKeyResolver.AidKey.ENTER);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF3))
                    .contains(PfKeyResolver.AidKey.PFK03);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF4))
                    .contains(PfKeyResolver.AidKey.PFK04);

            UserAddRequest request = populatedRequest();
            assertThat(request.aid())
                    .isEqualTo(PfKeyResolver.AidKey.ENTER.token())
                    .hasSize(UserAddRequest.AID_LENGTH);
            assertThat(deserialise(serialise(request)).aid()).isEqualTo(request.aid());

            for (RecordComponent component : UserAddRequest.class.getRecordComponents()) {
                assertThat(component.getType())
                        .as("%s must not carry a raw AID byte", component.getName())
                        .isNotEqualTo(byte.class)
                        .isNotEqualTo(Byte.class)
                        .isNotEqualTo(byte[].class);
            }
        }

        @Test
        @DisplayName("PF13-PF24 fold onto PFK01-PFK12, and an unrecognised byte resolves to nothing")
        void theResolverFoldsTheHighFunctionKeysAndReportsNoMatch() {
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF3))
                    .isEqualTo(PfKeyResolver.resolve(CicsAid.DFHPF15))
                    .contains(PfKeyResolver.AidKey.PFK03);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF12))
                    .isEqualTo(PfKeyResolver.resolve(CicsAid.DFHPF24))
                    .contains(PfKeyResolver.AidKey.PFK12);

            Optional<PfKeyResolver.AidKey> unmatched = PfKeyResolver.resolve((byte) 0x00);
            assertThat(unmatched).isEmpty();
            UserAddRequest noKey = requestOf(populatedMapValues(), NavigationContext.empty(),
                    unmatched.map(PfKeyResolver.AidKey::token).orElse(""));
            assertThat(noKey.aid()).isEmpty();
            assertThat(validate(noKey))
                    .as("an absent token is a legitimate state, not a rejectable one")
                    .isEmpty();
        }

        @ParameterizedTest(name = "context {0}: pgmEnter={1}, pgmReenter={2}")
        @CsvSource({
            "0, true,  false",
            "1, false, true",
            "2, false, false",
            "9, false, false",
        })
        @DisplayName("both 88-level states are driven, in both directions, plus a digit that is neither")
        void bothContextStatesAreDriven(int pgmContext, boolean enter, boolean reenter) {
            UserAddRequest request = requestOf(blankMapValues(),
                    NavigationContext.empty().withPgmContext(pgmContext), "");
            assertThat(request.pgmEnter()).isEqualTo(enter);
            assertThat(request.pgmReenter()).isEqualTo(reenter);

            assertThat(request.pgmEnter()).isEqualTo(request.navigationContext().isEnter());
            assertThat(request.pgmReenter()).isEqualTo(request.navigationContext().isReenter());
            assertThat(request.navigationContext().pgmContext()).isEqualTo(pgmContext);
        }

        @Test
        @DisplayName("the named context values are the copybook's, and the state survives JSON")
        void theContextStateSurvivesTheRoundTrip() {
            assertThat(NavigationContext.PGM_CONTEXT_ENTER).isZero();
            assertThat(NavigationContext.PGM_CONTEXT_REENTER).isEqualTo(1);

            UserAddRequest entered = requestOf(blankMapValues(),
                    NavigationContext.empty().withPgmEnter(), "");
            UserAddRequest reentered = requestOf(blankMapValues(),
                    NavigationContext.empty().withPgmReenter(), "");

            UserAddRequest restoredEnter = deserialise(serialise(entered));
            assertThat(restoredEnter.pgmEnter()).isTrue();
            assertThat(restoredEnter.pgmReenter()).isFalse();

            UserAddRequest restoredReenter = deserialise(serialise(reentered));
            assertThat(restoredReenter.pgmReenter()).isTrue();
            assertThat(restoredReenter.pgmEnter()).isFalse();
            assertThat(restoredReenter.navigationContext())
                    .isEqualTo(reentered.navigationContext());
        }

        @Test
        @DisplayName("an absent communication area is representable - the EIBCALEN = 0 case")
        void anAbsentCommunicationAreaIsRepresentable() {
            UserAddRequest absent = requestOf(blankMapValues(), null, "");
            assertThat(absent.navigationContext()).isNull();
            assertThat(absent.pgmEnter()).isFalse();
            assertThat(absent.pgmReenter()).isFalse();
            assertThat(validate(absent))
                    .as("no constraint may reject the cold-start payload")
                    .isEmpty();

            UserAddRequest restored = deserialise(serialise(absent));
            assertThat(restored.navigationContext()).isNull();
            assertThat(restored).isEqualTo(absent);
        }

        @Test
        @DisplayName("no CDEMO-CU0n-INFO extension block is carried, because COUSR01C declares none")
        void noExtensionBlockIsCarried() {
            assertThat(UserAddRequest.class.getRecordComponents()).hasSize(COMPONENT_COUNT);
            for (RecordComponent component : UserAddRequest.class.getRecordComponents()) {
                assertThat(component.getName().toLowerCase(Locale.ROOT))
                        .as("%s must not be an extension block", component.getName())
                        .doesNotContain("cu00").doesNotContain("cu01").doesNotContain("cu02")
                        .doesNotContain("cu03").doesNotContain("extension").doesNotContain("page");
            }
            assertThat(populatedRequest().navigationContext().toFixedWidth(codec()))
                    .hasSize(NavigationContext.COMMAREA_LENGTH);
            assertThat(jsonMembersOf(populatedRequest()))
                    .doesNotContain("cdemoCu01Info", "pageNum", "selectedRow");
        }
    }

    @Nested
    @DisplayName("Validation - @Size maxima only, never a presence or format constraint")
    class ValidationConstraints {
        @Test
        @DisplayName("carries thirteen @Size constraints and no other constraint at all")
        void carriesOnlySizeConstraints() {
            int sized = 0;
            for (RecordComponent component : UserAddRequest.class.getRecordComponents()) {
                for (Annotation annotation : component.getAccessor().getAnnotations()) {
                    String type = annotation.annotationType().getName();
                    assertThat(type)
                            .as("%s carries a constraint the program does not perform",
                                    component.getName())
                            .doesNotContain("NotBlank")
                            .doesNotContain("NotNull")
                            .doesNotContain("NotEmpty")
                            .doesNotContain("Pattern")
                            .doesNotContain("Email")
                            .doesNotContain("Digits")
                            .doesNotContain("Positive")
                            .doesNotContain("AssertTrue");
                    if (annotation instanceof Size) {
                        sized++;
                    }
                }
            }
            assertThat(sized)
                    .as("the twelve screen fields plus the AID token; the commarea is a typed member "
                            + "and carries none")
                    .isEqualTo(DFHMDF_NAMED + 1);
        }

        @Test
        @DisplayName("the communication area carries no constraint, because its own type enforces its own")
        void theCommareaCarriesNoConstraint() {
            RecordComponent commarea =
                    UserAddRequest.class.getRecordComponents()[DFHMDF_NAMED];
            assertThat(commarea.getName()).isEqualTo("navigationContext");
            assertThat(commarea.getAccessor().getAnnotations())
                    .as("the 160-byte geometry is NavigationContext's own invariant, refused at "
                            + "construction rather than reported as a violation")
                    .isEmpty();
        }

        @ParameterizedTest(name = "{0} is @Size(max = {1})")
        @CsvSource({
            "trnName,  4", "title01, 40", "curDate,  8", "pgmName,  8",
            "title02, 40", "curTime,  8", "fName,   20", "lName,   20",
            "userId,   8", "passwd,   8", "usrType,  1", "errMsg,  78",
        })
        @DisplayName("each @Size(max) is the width its symbolic-map item declares")
        void eachSizeMaximumEqualsTheDeclaredWidth(String member, int width) {
            int position = MAP_MEMBERS.indexOf(member);
            assertThat(position).as("%s must be a map member", member).isNotNegative();

            RecordComponent component = UserAddRequest.class.getRecordComponents()[position];
            Size size = component.getAccessor().getAnnotation(Size.class);
            assertThat(size)
                    .as("%s must be width-constrained, because %s is PIC X(%d)", member,
                            SYMBOLIC_MAP_ITEMS.get(position), width)
                    .isNotNull();
            assertThat(size.max()).isEqualTo(width).isEqualTo(DECLARED_WIDTHS.get(position));
            assertThat(size.min())
                    .as("a minimum would be a presence constraint by another name")
                    .isZero();
        }

        @Test
        @DisplayName("the AID token is width-constrained to five as well")
        void theAidTokenIsWidthConstrained() {
            RecordComponent aid = UserAddRequest.class.getRecordComponents()[DFHMDF_NAMED + 1];
            assertThat(aid.getName()).isEqualTo("aid");
            Size size = aid.getAccessor().getAnnotation(Size.class);
            assertThat(size).isNotNull();
            assertThat(size.max()).isEqualTo(UserAddRequest.AID_LENGTH)
                    .isEqualTo(PfKeyResolver.AID_TOKEN_LENGTH);
            assertThat(size.min()).isZero();
        }

        @Test
        @DisplayName("a wholly blank instance is valid, because the program answers blanks with text")
        void aBlankInstanceIsValid() {
            assertThat(validate(requestOf(blankMapValues(), null, ""))).isEmpty();
            assertThat(validate(requestOf(blankMapValues(), NavigationContext.empty(), ""))).isEmpty();
        }

        @Test
        @DisplayName("a null in every position is valid too, so nothing precedes the program's own test")
        void aNullInEveryPositionIsValid() {
            UserAddRequest nulls = requestOf(nullMapValues(), null, null);
            assertThat(validate(nulls)).isEmpty();
            for (String value : mapValuesOf(nulls)) {
                assertThat(value).isNull();
            }
            assertThat(nulls.aid()).isNull();
            assertThat(nulls.navigationContext()).isNull();
        }

        @ParameterizedTest(name = "{0} at {1} + 1 characters is exactly one violation")
        @CsvSource({
            "trnName,  4", "title01, 40", "curDate,  8", "pgmName,  8",
            "title02, 40", "curTime,  8", "fName,   20", "lName,   20",
            "userId,   8", "passwd,   8", "usrType,  1", "errMsg,  78",
        })
        @DisplayName("one character over the declared width is exactly one violation, on that member")
        void oneCharacterTooManyIsOneViolation(String member, int width) {
            List<String> values = withMember(blankMapValues(), member, "X".repeat(width + 1));
            Set<ConstraintViolation<UserAddRequest>> violations =
                    validate(requestOf(values, NavigationContext.empty(), ""));

            assertThat(violations).hasSize(1);
            ConstraintViolation<UserAddRequest> violation = violations.iterator().next();
            assertThat(violation.getPropertyPath()).hasToString(member);
            assertThat(violation.getConstraintDescriptor().getAnnotation())
                    .isInstanceOf(Size.class);

            assertThat(validate(requestOf(withMember(blankMapValues(), member, "X".repeat(width)),
                    NavigationContext.empty(), ""))).isEmpty();
        }

        @Test
        @DisplayName("an over-wide AID token is refused as well, and two failures report as two")
        void twoOverWideValuesReportAsTwoViolations() {
            Set<ConstraintViolation<UserAddRequest>> aidOnly = validate(
                    requestOf(blankMapValues(), NavigationContext.empty(), "ENTER!"));
            assertThat(aidOnly).hasSize(1);
            assertThat(aidOnly.iterator().next().getPropertyPath()).hasToString("aid");

            assertThat(validate(requestOf(
                    withMember(blankMapValues(), "usrType", "AU"),
                    NavigationContext.empty(), "ENTER!")))
                    .as("each constraint reports independently; neither masks the other")
                    .hasSize(2);
        }

        @Test
        @DisplayName("SPACES and LOW-VALUES are both blank to the program, and both are carried intact")
        void spacesAndLowValuesAreBothCarried() {
            String spaces = " ".repeat(UserAddRequest.FNAME_LENGTH);
            String lowValues = "\u0000".repeat(UserAddRequest.FNAME_LENGTH);
            assertThat(spaces).isNotEqualTo(lowValues).hasSameSizeAs(lowValues);

            UserAddRequest spaced = requestOf(withMember(blankMapValues(), "fName", spaces),
                    NavigationContext.empty(), "");
            UserAddRequest lowValued = requestOf(withMember(blankMapValues(), "fName", lowValues),
                    NavigationContext.empty(), "");

            assertThat(validate(spaced)).as("a run of spaces fills the field exactly").isEmpty();
            assertThat(validate(lowValued)).as("and so does a run of LOW-VALUES").isEmpty();

            assertThat(spaced.fName()).isEqualTo(spaces).isNotNull().isBlank()
                    .hasSize(UserAddRequest.FNAME_LENGTH);
            assertThat(lowValued.fName()).isEqualTo(lowValues).isNotNull()
                    .hasSize(UserAddRequest.FNAME_LENGTH);
            assertThat(spaced).isNotEqualTo(lowValued);

            assertThat(deserialise(serialise(spaced)).fName()).isEqualTo(spaces);
            assertThat(deserialise(serialise(lowValued)).fName()).isEqualTo(lowValues);
            assertThat(deserialise(serialise(spaced))).isNotEqualTo(deserialise(serialise(lowValued)));
        }

        @Test
        @DisplayName("an empty value is distinct from a space-filled one and from an absent one")
        void emptyBlankAndAbsentAreThreeDistinctStates() {
            UserAddRequest empty = requestOf(withMember(blankMapValues(), "userId", ""),
                    NavigationContext.empty(), "");
            UserAddRequest padded = requestOf(withMember(blankMapValues(), "userId",
                    " ".repeat(UserAddRequest.USERID_LENGTH)), NavigationContext.empty(), "");
            UserAddRequest absent = requestOf(withMember(blankMapValues(), "userId", null),
                    NavigationContext.empty(), "");

            assertThat(empty.userId()).isEmpty();
            assertThat(padded.userId()).isBlank().hasSize(UserAddRequest.USERID_LENGTH);
            assertThat(absent.userId()).isNull();
            assertThat(empty).isNotEqualTo(padded).isNotEqualTo(absent);
            assertThat(padded).isNotEqualTo(absent);
            for (UserAddRequest request : List.of(empty, padded, absent)) {
                assertThat(validate(request))
                        .as("all three are legitimate inputs the program handles")
                        .isEmpty();
            }
        }
    }

    @Nested
    @DisplayName("Serialisation - padding survives, names are untransformed")
    class JsonRoundTrip {
        @Test
        @DisplayName("the mapper this suite uses carries the settings the module configures")
        void theMapperMatchesTheModuleConfiguration() {
            ObjectMapper mapper = webConfigEquivalentMapper();
            assertThat(mapper.getDeserializationConfig()
                    .isEnabled(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS))
                    .as("no value derived from a PIC S9(p)V99 clause may route through a double")
                    .isTrue();
            assertThat(mapper.getFactory()
                    .isEnabled(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN))
                    .as("a scale-2 amount serialises as 100.00, never as 1.0E+2")
                    .isTrue();
            assertThat(mapper.getDeserializationConfig()
                    .isEnabled(DeserializationFeature.FAIL_ON_TRAILING_TOKENS))
                    .as("a malformed body is refused outright rather than half-read")
                    .isTrue();
            assertThat(mapper.getDeserializationConfig()
                    .isEnabled(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT))
                    .as("an empty or all-spaces PIC X(n) value is real screen data, not an absent one; "
                            + "a default mapper would make the blank-value cases above assert the "
                            + "wrong thing")
                    .isFalse();
        }

        @Test
        @DisplayName("the property names are the component names, untransformed")
        void thePropertyNamesAreUntransformed() {
            Set<String> members = jsonMembersOf(populatedRequest());
            assertThat(members).containsAll(wireNamesOf(MAP_MEMBERS)).containsAll(STATE_MEMBERS);
            for (String member : members) {
                assertThat(member)
                        .as("%s must be a plain camelCase component name", member)
                        .doesNotContain("_").doesNotContain("-")
                        .isEqualTo(member.trim());
            }
        }

        @Test
        @DisplayName("a space-padded payload survives serialise then deserialise byte for byte")
        void spacePaddingSurvivesTheRoundTrip() {
            List<String> values = withMember(withMember(withMember(withMember(blankMapValues(),
                    "fName", codec().movePicX("JANE", UserAddRequest.FNAME_LENGTH)),
                    "lName", codec().movePicX("DOE", UserAddRequest.LNAME_LENGTH)),
                    "passwd", codec().movePicX("PWD7CHR", UserAddRequest.PASSWD_LENGTH)),
                    "errMsg", " ".repeat(UserAddRequest.ERRMSG_LENGTH));
            values = withMember(values, "title01", ScreenTitles.CCDA_TITLE01);
            UserAddRequest original = requestOf(values,
                    NavigationContext.empty().withPgmReenter(),
                    PfKeyResolver.AidKey.ENTER.token());

            UserAddRequest restored = deserialise(serialise(original));
            assertThat(restored).isEqualTo(original);
            assertThat(restored.hashCode()).isEqualTo(original.hashCode());

            assertThat(restored.fName())
                    .isEqualTo("JANE" + " ".repeat(UserAddRequest.FNAME_LENGTH - 4))
                    .hasSize(UserAddRequest.FNAME_LENGTH)
                    .endsWith(" ");
            assertThat(restored.lName())
                    .isEqualTo("DOE" + " ".repeat(UserAddRequest.LNAME_LENGTH - 3))
                    .hasSize(UserAddRequest.LNAME_LENGTH)
                    .endsWith(" ");
            assertThat(restored.passwd())
                    .as("the eight bytes SEC-USR-PWD receives must arrive unaltered")
                    .isEqualTo("PWD7CHR ")
                    .hasSize(UserAddRequest.PASSWD_LENGTH);
            assertThat(restored.errMsg())
                    .as("78 spaces must come back as 78 spaces, neither trimmed nor nulled")
                    .isEqualTo(" ".repeat(UserAddRequest.ERRMSG_LENGTH))
                    .hasSize(UserAddRequest.ERRMSG_LENGTH);
            assertThat(restored.title01())
                    .isEqualTo(ScreenTitles.CCDA_TITLE01)
                    .hasSize(UserAddRequest.TITLE01_LENGTH)
                    .endsWith(" ");
        }

        @Test
        @DisplayName("a fully painted screen round-trips with every member at its declared width")
        void aFullyPaintedScreenRoundTrips() {
            UserAddRequest original = requestOf(paddedMapValues(),
                    NavigationContext.empty().withPgmEnter(), "");
            UserAddRequest restored = deserialise(serialise(original));
            assertThat(restored).isEqualTo(original);

            List<String> values = mapValuesOf(restored);
            for (int index = 0; index < DFHMDF_NAMED; index++) {
                assertThat(values.get(index))
                        .as("%s must come back at its declared width", MAP_MEMBERS.get(index))
                        .hasSize(DECLARED_WIDTHS.get(index))
                        .isBlank();
            }
            assertThat(jsonMembersOf(restored)).hasSize(COMPONENT_COUNT);
        }

        @Test
        @DisplayName("an empty value stays empty and is not coerced to absent")
        void anEmptyValueIsNotCoercedToNull() {
            UserAddRequest restored = deserialise(serialise(
                    requestOf(blankMapValues(), NavigationContext.empty(), "")));
            for (String value : mapValuesOf(restored)) {
                assertThat(value).isNotNull().isEmpty();
            }
            assertThat(restored.aid()).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("an absent member deserialises as absent rather than as a default")
        void anAbsentMemberDeserialisesAsAbsent() {
            UserAddRequest restored = deserialise("{\"trnname\":\"CU01\"}");
            assertThat(restored.trnName()).isEqualTo(TRANSACTION_ID);
            assertThat(restored.fName()).isNull();
            assertThat(restored.userId()).isNull();
            assertThat(restored.navigationContext()).isNull();
            assertThat(restored.aid()).isNull();
            assertThat(restored.pgmEnter()).isFalse();
            assertThat(restored.pgmReenter()).isFalse();
        }

        @Test
        @DisplayName("the emitted JSON names exactly the fourteen members, in no fewer and no more")
        void theEmittedJsonNamesExactlyTheFourteenMembers() {
            String json = serialise(populatedRequest());
            for (String member : EXPECTED_JSON_MEMBERS) {
                assertThat(json).contains("\"" + member + "\"");
            }
            assertThat(jsonMembersOf(populatedRequest()))
                    .hasSize(COMPONENT_COUNT)
                    .containsExactlyInAnyOrderElementsOf(EXPECTED_JSON_MEMBERS);
            assertThat(json).doesNotContain("AWS.M2.CARDDEMO").doesNotContain("VSAM")
                    .doesNotContain("USRSEC");
        }
    }

    @Nested
    @DisplayName("Security posture - plaintext by parity, withheld from diagnostics")
    class SecurityPosture {
        @Test
        @DisplayName("passwd is a plaintext String of the width CSUSR01Y declares")
        void thePasswordIsAPlaintextStringOfEight() {
            RecordComponent passwd =
                    UserAddRequest.class.getRecordComponents()[MAP_MEMBERS.indexOf("passwd")];
            assertThat(passwd.getName()).isEqualTo("passwd");
            assertThat(passwd.getType())
                    .as("a String, not a char[], not a wrapper type and not an encoded form")
                    .isEqualTo(String.class);
            assertThat(UserAddRequest.PASSWD_LENGTH)
                    .as("SEC-USR-PWD PIC X(08) at app/cpy/CSUSR01Y.cpy:21")
                    .isEqualTo(SEC_USR_PWD_LENGTH);
        }

        @Test
        @DisplayName("an eight-character password survives the round trip byte for byte")
        void anEightCharacterPasswordSurvivesTheRoundTrip() {
            String keyed = "P4dNotRl".substring(0, UserAddRequest.PASSWD_LENGTH);
            UserAddRequest original = requestOf(withMember(blankMapValues(), "passwd", keyed),
                    NavigationContext.empty(), "");

            UserAddRequest restored = deserialise(serialise(original));
            assertThat(restored.passwd())
                    .as("the value the program stores must arrive unaltered; any transformation here "
                            + "would change what COUSR01C.cbl:157 writes into USRSEC")
                    .isEqualTo(keyed)
                    .isEqualTo(original.passwd())
                    .hasSize(UserAddRequest.PASSWD_LENGTH);
            assertThat(restored).isEqualTo(original);
        }

        @Test
        @DisplayName("no hashing, encoder, token or security framework type is reachable")
        void noSecurityFrameworkTypeIsReachable() {
            List<String> forbidden = List.of("PasswordEncoder", "BCrypt", "MessageDigest", "Hash",
                    "org.springframework.security", "jwt", "Jwt", "Cipher", "SecretKey", "Base64",
                    "Encrypt", "Digest");
            for (String name : reachableTypeNames()) {
                for (String marker : forbidden) {
                    assertThat(name)
                            .as("%s suggests %s; strengthening the comparison would change observable "
                                    + "behaviour and is out of scope", name, marker)
                            .doesNotContain(marker);
                }
            }
        }

        @Test
        @DisplayName("the DRK attribute on PASSWD is presentation masking, never storage masking")
        void theDarkAttributeIsPresentationOnly() {
            String keyed = "PLAINTXT";
            UserAddRequest request = requestOf(withMember(blankMapValues(), "passwd", keyed),
                    NavigationContext.empty(), "");
            assertThat(request.passwd())
                    .as("the payload carries the keyed characters, unmasked and uncounted")
                    .isEqualTo(keyed)
                    .hasSize(UserAddRequest.PASSWD_LENGTH);
            assertThat(serialise(request))
                    .as("and they are on the wire, because that is what the program stores")
                    .contains(keyed);
        }

        @Test
        @DisplayName("the payload does not upper-case, hash or otherwise normalise what it carries")
        void thePayloadIsAPassiveCarrier() {
            UserAddRequest request = requestOf(withMember(withMember(withMember(blankMapValues(),
                    "userId", "newusr01"), "passwd", "lowerpwd"), "fName", "jane"),
                    NavigationContext.empty(), "");

            assertThat(request.userId()).isEqualTo("newusr01")
                    .isNotEqualTo("newusr01".toUpperCase(Locale.ROOT));
            assertThat(request.passwd()).isEqualTo("lowerpwd")
                    .isNotEqualTo("lowerpwd".toUpperCase(Locale.ROOT));
            assertThat(request.fName()).isEqualTo("jane")
                    .as("nor is a name padded, trimmed or title-cased on the way in")
                    .hasSize(4);
            assertThat(deserialise(serialise(request)).userId()).isEqualTo("newusr01");
            assertThat(deserialise(serialise(request)).passwd()).isEqualTo("lowerpwd");
        }
    }

    @Nested
    @DisplayName("Diagnostics - the password is withheld unconditionally")
    class Diagnostics {
        @Test
        @DisplayName("toString withholds the password and reports the two names by length only")
        void toStringWithholdsThePassword() {
            UserAddRequest request = populatedRequest();
            String rendered = request.toString();

            assertThat(rendered)
                    .startsWith("UserAddRequest[")
                    .endsWith("]")
                    .doesNotContain(request.passwd())
                    .contains("passwd=" + UserAddRequest.PASSWORD_MASK)
                    .contains("trnName=" + TRANSACTION_ID)
                    .contains("pgmName=" + PROGRAM_NAME)
                    .contains("userId=NEWUSR01")
                    .contains("aid=" + PfKeyResolver.AidKey.ENTER.token());

            assertThat(rendered)
                    .doesNotContain("JANE")
                    .doesNotContain("DOE")
                    .contains(SensitiveDiagnostics.describeText(request.fName()))
                    .contains(SensitiveDiagnostics.describeText(request.lName()));
        }

        @Test
        @DisplayName("the mask is unconditional, so an absent password is masked too")
        void theMaskIsUnconditional() {
            assertThat(requestOf(nullMapValues(), null, null).toString())
                    .contains("passwd=" + UserAddRequest.PASSWORD_MASK)
                    .contains("navigationContext=null");
            assertThat(requestOf(blankMapValues(), NavigationContext.empty(), "").toString())
                    .contains("passwd=" + UserAddRequest.PASSWORD_MASK);

            assertThat(UserAddRequest.PASSWORD_MASK)
                    .isEqualTo(SensitiveDiagnostics.REDACTED)
                    .doesNotContain("*")
                    .isNotEqualTo("*".repeat(UserAddRequest.PASSWD_LENGTH));
        }

        @Test
        @DisplayName("equals and hashCode still consider the real password")
        void equalsStillConsidersTheRealPassword() {
            UserAddRequest one = requestOf(withMember(blankMapValues(), "passwd", "PWDFAKE1"),
                    NavigationContext.empty(), "");
            UserAddRequest other = requestOf(withMember(blankMapValues(), "passwd", "PWDFAKE2"),
                    NavigationContext.empty(), "");
            UserAddRequest sameAsOne = requestOf(withMember(blankMapValues(), "passwd", "PWDFAKE1"),
                    NavigationContext.empty(), "");

            assertThat(one).isEqualTo(sameAsOne).isNotEqualTo(other);
            assertThat(one.hashCode()).isEqualTo(sameAsOne.hashCode());
            assertThat(one).isNotEqualTo(null).isNotEqualTo("not a request");
            assertThat(one.toString()).isEqualTo(other.toString());
        }

        @Test
        @DisplayName("every component is accounted for in the rendering, so nothing is silently dropped")
        void everyComponentIsAccountedForInTheRendering() {
            String rendered = populatedRequest().toString();
            for (String component : componentNames()) {
                assertThat(rendered)
                        .as("%s must appear in the rendering", component)
                        .contains(component + "=");
            }
        }
    }

}

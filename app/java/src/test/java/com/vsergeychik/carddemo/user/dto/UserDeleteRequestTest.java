package com.vsergeychik.carddemo.user.dto;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.annotation.JsonProperty;
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
 * Unit tests for {@link UserDeleteRequest} - the inbound payload of
 * {@code DELETE /api/users/&#123;userId&#125;}, CICS transaction {@code CU03}, program
 * {@code app/cbl/COUSR03C.cbl}, map {@code COUSR3A} of mapset {@code COUSR03}.
 */
@DisplayName("UserDeleteRequest - the CU03 delete-user payload, and the password it does not have")
class UserDeleteRequestTest {
    private static final Charset MAP_CHARSET = StandardCharsets.US_ASCII;

    private static final List<String> MAP_MEMBERS = List.of("trnName", "title01", "curDate",
            "pgmName", "title02", "curTime", "usrIdIn", "fName", "lName", "usrType", "errMsg");

    private static String wireNameOf(String member) {
        return MAP_MEMBERS.contains(member) ? member.toLowerCase(Locale.ROOT) : member;
    }

    private static List<String> wireNamesOf(List<String> members) {
        return members.stream().map(UserDeleteRequestTest::wireNameOf).toList();
    }

    private static final List<String> SYMBOLIC_MAP_ITEMS = List.of("TRNNAMEI", "TITLE01I",
            "CURDATEI", "PGMNAMEI", "TITLE02I", "CURTIMEI", "USRIDINI", "FNAMEI", "LNAMEI",
            "USRTYPEI", "ERRMSGI");

    private static final List<String> SCREEN_FIELDS = List.of("TRNNAME", "TITLE01", "CURDATE",
            "PGMNAME", "TITLE02", "CURTIME", "USRIDIN", "FNAME", "LNAME", "USRTYPE", "ERRMSG");

    private static final List<Integer> DECLARED_WIDTHS =
            List.of(4, 40, 8, 8, 40, 8, 8, 20, 20, 1, 78);

    private static final List<Integer> MAPSET_LENGTHS =
            List.of(4, 40, 8, 8, 40, 8, 8, 20, 20, 1, 78);

    private static final List<Integer> PUBLISHED_WIDTHS = List.of(
            UserDeleteRequest.TRNNAME_LENGTH,
            UserDeleteRequest.TITLE01_LENGTH,
            UserDeleteRequest.CURDATE_LENGTH,
            UserDeleteRequest.PGMNAME_LENGTH,
            UserDeleteRequest.TITLE02_LENGTH,
            UserDeleteRequest.CURTIME_LENGTH,
            UserDeleteRequest.USRIDIN_LENGTH,
            UserDeleteRequest.FNAME_LENGTH,
            UserDeleteRequest.LNAME_LENGTH,
            UserDeleteRequest.USRTYPE_LENGTH,
            UserDeleteRequest.ERRMSG_LENGTH);

    private static final List<String> PUBLISHED_ITEM_NAMES = List.of(
            UserDeleteRequest.TRNNAME_FIELD,
            UserDeleteRequest.TITLE01_FIELD,
            UserDeleteRequest.CURDATE_FIELD,
            UserDeleteRequest.PGMNAME_FIELD,
            UserDeleteRequest.TITLE02_FIELD,
            UserDeleteRequest.CURTIME_FIELD,
            UserDeleteRequest.USRIDIN_FIELD,
            UserDeleteRequest.FNAME_FIELD,
            UserDeleteRequest.LNAME_FIELD,
            UserDeleteRequest.USRTYPE_FIELD,
            UserDeleteRequest.ERRMSG_FIELD);

    private static final List<Integer> COPYBOOK_LINES =
            List.of(24, 30, 36, 42, 48, 54, 60, 66, 72, 78, 84);

    private static final List<Integer> MAPSET_LINES =
            List.of(34, 38, 47, 57, 61, 70, 85, 103, 116, 130, 140);

    private static final List<Integer> REDEFINES_LINES =
            List.of(21, 27, 33, 39, 45, 51, 57, 63, 69, 75, 81);

    private static final List<Integer> COUSR02_COPYBOOK_LINES =
            List.of(24, 30, 36, 42, 48, 54, 60, 66, 72, 84, 90);

    private static final int COUSR02_PASSWD_LINE = 78;

    private static final int COUSR02_MAP_FIELD_COUNT = 12;

    private static final int DFHMDF_TOTAL = 26;

    private static final int DFHMDF_NAMED = 11;

    private static final List<String> STATE_MEMBERS =
            List.of("navigationContext", "aid", "cu03Info");

    private static final int COMPONENT_COUNT = 14;

    private static final List<String> EXTENSION_MEMBERS = List.of("usridFirst", "usridLast",
            "pageNum", "nextPageFlg", "usrSelFlg", "usrSelected");

    private static final List<String> EXTENSION_ITEM_NAMES = List.of("CDEMO-CU03-USRID-FIRST",
            "CDEMO-CU03-USRID-LAST", "CDEMO-CU03-PAGE-NUM", "CDEMO-CU03-NEXT-PAGE-FLG",
            "CDEMO-CU03-USR-SEL-FLG", "CDEMO-CU03-USR-SELECTED");

    private static final List<Integer> EXTENSION_WIDTHS = List.of(8, 8, 8, 1, 1, 8);

    private static final int EXTENSION_LENGTH = 34;

    private static final int CU03_COMMAREA_LENGTH =
            NavigationContext.COMMAREA_LENGTH + EXTENSION_LENGTH;

    private static final int TIOAPFX_PREFIX_LENGTH = 12;

    private static final int LENGTH_ITEM_LENGTH = 2;

    private static final int ATTRIBUTE_ITEM_LENGTH = 1;

    private static final int ATTRIBUTE_FILLER_LENGTH = 4;

    private static final int FIELD_PREFIX_LENGTH =
            LENGTH_ITEM_LENGTH + ATTRIBUTE_ITEM_LENGTH + ATTRIBUTE_FILLER_LENGTH;

    private static final int PAYLOAD_WIDTH_TOTAL = 235;

    private static final int SYMBOLIC_MAP_LENGTH = 324;

    private static final int WS_MESSAGE_LENGTH = 80;

    private static final String BLANK_USER_ID_MESSAGE = "User ID can NOT be empty...";

    private static final FixedWidthRecord.RecordLayout SYMBOLIC_MAP_LAYOUT = symbolicMapLayout();

    private static final Instant FIXED_INSTANT = Instant.parse("2022-07-19T23:12:35Z");

    private static final String FIXED_CURDATE = "07/19/22";

    private static final String FIXED_CURTIME = "23:12:35";

    private static final Set<String> EXPECTED_JSON_MEMBERS = expectedJsonMembers();

    private static final List<String> FORBIDDEN_SECURITY_MARKERS = List.of("PasswordEncoder",
            "BCrypt", "MessageDigest", "org.springframework.security", "Jwt", "Cipher", "SecretKey");

    private static final List<String> FORBIDDEN_CREDENTIAL_NAMES = List.of("passwd", "password",
            "pwd", "secret", "credential", "token");

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

    private static List<String> metadataNamesOf(String screenField) {
        return List.of(screenField + "L", screenField + "F", screenField + "A", screenField + "O");
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

    private static String serialise(UserDeleteRequest request) {
        try {
            return webConfigEquivalentMapper().writeValueAsString(request);
        } catch (IOException failure) {
            throw new UncheckedIOException("Serialising a UserDeleteRequest must not fail", failure);
        }
    }

    private static UserDeleteRequest deserialise(String json) {
        try {
            return webConfigEquivalentMapper().readValue(json, UserDeleteRequest.class);
        } catch (IOException failure) {
            throw new UncheckedIOException("Deserialising a UserDeleteRequest must not fail",
                    failure);
        }
    }

    private static Set<String> jsonMembersOf(UserDeleteRequest request) {
        try {
            Map<String, Object> tree = webConfigEquivalentMapper()
                    .readValue(serialise(request), new TypeReference<Map<String, Object>>() { });
            return tree.keySet();
        } catch (IOException failure) {
            throw new UncheckedIOException("Reading a serialised UserDeleteRequest must not fail",
                    failure);
        }
    }

    private static UserDeleteRequest requestOf(List<String> mapValues, NavigationContext context,
            String aid, UserDeleteRequest.Cu03Info extension) {
        return new UserDeleteRequest(mapValues.get(0), mapValues.get(1), mapValues.get(2),
                mapValues.get(3), mapValues.get(4), mapValues.get(5), mapValues.get(6),
                mapValues.get(7), mapValues.get(8), mapValues.get(9), mapValues.get(10),
                context, aid, extension);
    }

    private static UserDeleteRequest requestOf(List<String> mapValues, NavigationContext context,
            String aid) {
        return requestOf(mapValues, context, aid, UserDeleteRequest.Cu03Info.initial());
    }

    private static List<String> mapValuesOf(UserDeleteRequest request) {
        return Arrays.asList(request.trnName(), request.title01(), request.curDate(),
                request.pgmName(), request.title02(), request.curTime(), request.usrIdIn(),
                request.fName(), request.lName(), request.usrType(), request.errMsg());
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
            values.add(spaces(DECLARED_WIDTHS.get(index)));
        }
        return values;
    }

    private static UserDeleteRequest populatedRequest() {
        List<String> values = Arrays.asList(
                UserDeleteRequest.TRANSACTION_ID,
                ScreenTitles.CCDA_TITLE01,
                FIXED_CURDATE,
                UserDeleteRequest.PROGRAM_NAME,
                ScreenTitles.CCDA_TITLE02,
                FIXED_CURTIME,
                "USER0001",
                codec().movePicX("Given", UserDeleteRequest.FNAME_LENGTH),
                codec().movePicX("Family", UserDeleteRequest.LNAME_LENGTH),
                NavigationContext.USER_TYPE_USER,
                spaces(UserDeleteRequest.ERRMSG_LENGTH));
        return requestOf(values, NavigationContext.empty(), PfKeyResolver.AidKey.ENTER.token(),
                UserDeleteRequest.Cu03Info.initial());
    }

    private static String spaces(int width) {
        return " ".repeat(width);
    }

    private static String lowValues(int width) {
        return "\u0000".repeat(width);
    }

    private static Set<ConstraintViolation<UserDeleteRequest>> violationsOf(
            UserDeleteRequest request) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(request);
        }
    }

    @Nested
    @DisplayName("Projection of 01 COUSR3AI - eleven map members, in copybook order")
    class MapProjection {
        @Test
        @DisplayName("fourteen components: the eleven map members then the three state carriers")
        void componentCensus() {
            RecordComponent[] components = UserDeleteRequest.class.getRecordComponents();
            assertThat(components)
                    .as("eleven name-labelled DFHMDF fields plus navigationContext, aid and cu03Info")
                    .hasSize(COMPONENT_COUNT);

            List<String> names = Arrays.stream(components).map(RecordComponent::getName).toList();
            assertThat(names.subList(0, DFHMDF_NAMED))
                    .as("the map members must appear in 01 COUSR3AI declaration order")
                    .containsExactlyElementsOf(MAP_MEMBERS);
            assertThat(names.subList(DFHMDF_NAMED, COMPONENT_COUNT))
                    .as("the three members with no DFHMDF behind them come last")
                    .containsExactlyElementsOf(STATE_MEMBERS);
        }

        @Test
        @DisplayName("every map member is a String, because every xxxI item is PIC X(n)")
        void everyMapMemberIsCharacter() {
            RecordComponent[] components = UserDeleteRequest.class.getRecordComponents();
            for (int index = 0; index < DFHMDF_NAMED; index++) {
                assertThat(components[index].getType())
                        .as("%s projects %s, which is PIC X(%d) at COUSR03.CPY:%d",
                                MAP_MEMBERS.get(index), SYMBOLIC_MAP_ITEMS.get(index),
                                DECLARED_WIDTHS.get(index), COPYBOOK_LINES.get(index))
                        .isEqualTo(String.class);
            }
            assertThat(components[DFHMDF_NAMED].getType())
                    .as("the communication area travels as itself, not as a flattened string")
                    .isEqualTo(NavigationContext.class);
            assertThat(components[DFHMDF_NAMED + 1].getType())
                    .as("the AID token is the five-character CCARD-AID literal")
                    .isEqualTo(String.class);
            assertThat(components[DFHMDF_NAMED + 2].getType())
                    .as("the CU03 extension travels as its own typed group, not as 34 loose members")
                    .isEqualTo(UserDeleteRequest.Cu03Info.class);
        }

        @Test
        @DisplayName("no member anywhere in the type is double or float, pageNum included")
        void noFloatingPointMemberExists() {
            for (RecordComponent component : UserDeleteRequest.class.getRecordComponents()) {
                assertThat(component.getType())
                        .as("%s must not be a binary floating-point type", component.getName())
                        .isNotEqualTo(double.class).isNotEqualTo(Double.class)
                        .isNotEqualTo(float.class).isNotEqualTo(Float.class);
            }
            for (RecordComponent component
                    : UserDeleteRequest.Cu03Info.class.getRecordComponents()) {
                assertThat(component.getType())
                        .as("%s must not be a binary floating-point type", component.getName())
                        .isNotEqualTo(double.class).isNotEqualTo(Double.class)
                        .isNotEqualTo(float.class).isNotEqualTo(Float.class);
            }
            assertThat(UserDeleteRequest.Cu03Info.class.getRecordComponents()[2].getType())
                    .as("CDEMO-CU03-PAGE-NUM PIC 9(08) is a scale-free integer picture")
                    .isEqualTo(int.class);
        }

        @Test
        @DisplayName("MAP_FIELD_COUNT is the mapset's own count, and 15 definitions stay unexposed")
        void countsAreTheMapsetsOwn() {
            assertThat(UserDeleteRequest.MAP_FIELD_COUNT).isEqualTo(DFHMDF_NAMED);
            assertThat(MAP_MEMBERS).hasSize(UserDeleteRequest.MAP_FIELD_COUNT);
            assertThat(SYMBOLIC_MAP_ITEMS).hasSize(UserDeleteRequest.MAP_FIELD_COUNT);
            assertThat(SCREEN_FIELDS).hasSize(UserDeleteRequest.MAP_FIELD_COUNT);
            assertThat(DECLARED_WIDTHS).hasSize(UserDeleteRequest.MAP_FIELD_COUNT);
            assertThat(MAPSET_LENGTHS).hasSize(UserDeleteRequest.MAP_FIELD_COUNT);
            assertThat(PUBLISHED_WIDTHS).hasSize(UserDeleteRequest.MAP_FIELD_COUNT);
            assertThat(PUBLISHED_ITEM_NAMES).hasSize(UserDeleteRequest.MAP_FIELD_COUNT);
            assertThat(COPYBOOK_LINES).hasSize(UserDeleteRequest.MAP_FIELD_COUNT);
            assertThat(MAPSET_LINES).hasSize(UserDeleteRequest.MAP_FIELD_COUNT);
            assertThat(REDEFINES_LINES).hasSize(UserDeleteRequest.MAP_FIELD_COUNT);

            assertThat(DFHMDF_TOTAL - DFHMDF_NAMED)
                    .as("26 definitions less the 11 name-labelled ones are screen literals")
                    .isEqualTo(15);
            assertThat(DFHMDF_NAMED).isLessThan(DFHMDF_TOTAL);
        }

        @ParameterizedTest(name = "{0} projects {1}")
        @CsvSource({"trnName,TRNNAMEI", "title01,TITLE01I", "curDate,CURDATEI", "pgmName,PGMNAMEI",
            "title02,TITLE02I", "curTime,CURTIMEI", "usrIdIn,USRIDINI", "fName,FNAMEI",
            "lName,LNAMEI", "usrType,USRTYPEI", "errMsg,ERRMSGI"})
        @DisplayName("each member names the symbolic-map item it projects, input suffix kept")
        void cobolItemNames(String member, String cobolItem) {
            int index = MAP_MEMBERS.indexOf(member);
            assertThat(index).as("%s must be a declared member", member).isNotNegative();
            assertThat(SYMBOLIC_MAP_ITEMS.get(index)).isEqualTo(cobolItem);
            assertThat(PUBLISHED_ITEM_NAMES.get(index))
                    .as("the published constant must spell the item exactly as the copybook does")
                    .isEqualTo(cobolItem);
            assertThat(cobolItem)
                    .as("the I suffix distinguishes the input view from the xxxO output view")
                    .endsWith("I")
                    .startsWith(SCREEN_FIELDS.get(index));
        }

        @Test
        @DisplayName("the screen identity constants match WS-TRANID, WS-PGMNAME, DFHMDI and DFHMSD")
        void screenIdentity() {
            assertThat(UserDeleteRequest.TRANSACTION_ID)
                    .as("WS-TRANID PIC X(04) VALUE 'CU03', COUSR03C.cbl:37, and "
                            + "DEFINE TRANSACTION(CU03) at CARDDEMO.CSD:479")
                    .isEqualTo("CU03")
                    .hasSize(UserDeleteRequest.TRNNAME_LENGTH);
            assertThat(UserDeleteRequest.PROGRAM_NAME)
                    .as("WS-PGMNAME PIC X(08) VALUE 'COUSR03C', COUSR03C.cbl:36, and "
                            + "PROGRAM(COUSR03C) at CARDDEMO.CSD:480")
                    .isEqualTo("COUSR03C")
                    .hasSize(UserDeleteRequest.PGMNAME_LENGTH);
            assertThat(UserDeleteRequest.MAP_NAME)
                    .as("COUSR3A DFHMDI, COUSR03.bms:26, sent at COUSR03C.cbl:220")
                    .isEqualTo("COUSR3A");
            assertThat(UserDeleteRequest.MAPSET_NAME)
                    .as("COUSR03 DFHMSD, COUSR03.bms:19, named at COUSR03C.cbl:221")
                    .isEqualTo("COUSR03");
            assertThat(UserDeleteRequest.MAP_NAME)
                    .as("map and mapset share the COUSR stem but are not the same name, and the "
                            + "symbolic map's group names - COUSR3AI and COUSR3AO - are formed from "
                            + "the MAP name, not the mapset's")
                    .startsWith("COUSR")
                    .isNotEqualTo(UserDeleteRequest.MAPSET_NAME);
            assertThat(UserDeleteRequest.MAPSET_NAME).startsWith("COUSR");
        }

        @Test
        @DisplayName("no static field of this type is mutable")
        void noStaticMutableState() {
            for (Field field : UserDeleteRequest.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("static field %s must be final", field.getName())
                            .isTrue();
                }
            }
            for (Field field : UserDeleteRequest.Cu03Info.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("static field %s must be final", field.getName())
                            .isTrue();
                }
            }
        }

        @Test
        @DisplayName("the type is a record, so it is immutable and safe to share and to compare")
        void theTypeIsAValueCarrier() {
            assertThat(UserDeleteRequest.class.isRecord()).isTrue();
            assertThat(UserDeleteRequest.Cu03Info.class.isRecord()).isTrue();

            UserDeleteRequest one = populatedRequest();
            UserDeleteRequest other = populatedRequest();
            assertThat(one).isEqualTo(other).hasSameHashCodeAs(other);
            for (Field field : UserDeleteRequest.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("instance field %s of a record must be final", field.getName())
                            .isTrue();
                }
            }
        }
    }

    @Nested
    @DisplayName("Asymmetry #3 - the absent password, proved three ways (B5, G9)")
    class AbsentPasswordField {
        @Test
        @DisplayName("no member is named for a credential, under any spelling")
        void noCredentialMemberExists() {
            List<String> declared = new ArrayList<>();
            for (RecordComponent component : UserDeleteRequest.class.getRecordComponents()) {
                declared.add(component.getName().toLowerCase(Locale.ROOT));
            }
            for (RecordComponent component
                    : UserDeleteRequest.Cu03Info.class.getRecordComponents()) {
                declared.add(component.getName().toLowerCase(Locale.ROOT));
            }

            for (String name : declared) {
                for (String forbidden : FORBIDDEN_CREDENTIAL_NAMES) {
                    assertThat(name)
                            .as("%s reads as a credential; COUSR03 declares none, so any such member "
                                    + "would be an invented field the program cannot observe", name)
                            .doesNotContain(forbidden);
                }
            }
            assertThat(declared).hasSize(COMPONENT_COUNT + EXTENSION_MEMBERS.size());
        }

        @Test
        @DisplayName("nothing in the map, the mapset or the symbolic map spells PASSWD")
        void thePasswordExistsNowhereInTheScreenContract() {
            for (String item : SYMBOLIC_MAP_ITEMS) {
                assertThat(item).doesNotContain("PASSWD");
            }
            for (String field : SCREEN_FIELDS) {
                assertThat(field).doesNotContain("PASSWD");
            }
            for (String member : PUBLISHED_ITEM_NAMES) {
                assertThat(member).doesNotContain("PASSWD");
            }
            assertThat(SYMBOLIC_MAP_LAYOUT.hasSpan("PASSWDI"))
                    .as("the copybook's own geometry has no such span")
                    .isFalse();
            assertThat(SYMBOLIC_MAP_LAYOUT.hasSpan("PASSWDF")).isFalse();
            assertThat(SYMBOLIC_MAP_LAYOUT.hasSpan("PASSWDA")).isFalse();
        }

        @Test
        @DisplayName("the tail is shifted up by exactly one field, and so by exactly six lines")
        void theTailIsShiftedUpByExactlyOneField() {
            assertThat(COPYBOOK_LINES.subList(0, 9))
                    .as("the two copybooks agree exactly up to and including LNAMEI")
                    .containsExactlyElementsOf(COUSR02_COPYBOOK_LINES.subList(0, 9));

            int usrTypeIndex = MAP_MEMBERS.indexOf("usrType");
            int errMsgIndex = MAP_MEMBERS.indexOf("errMsg");
            assertThat(COPYBOOK_LINES.get(usrTypeIndex)).isEqualTo(78);
            assertThat(COPYBOOK_LINES.get(errMsgIndex)).isEqualTo(84);
            assertThat(COUSR02_COPYBOOK_LINES.get(usrTypeIndex)).isEqualTo(84);
            assertThat(COUSR02_COPYBOOK_LINES.get(errMsgIndex)).isEqualTo(90);

            int shift = COUSR02_COPYBOOK_LINES.get(usrTypeIndex) - COPYBOOK_LINES.get(usrTypeIndex);
            assertThat(shift)
                    .as("one field group is six copybook lines: xxxL, xxxF, FILLER REDEFINES, xxxA, "
                            + "FILLER X(4) and xxxI")
                    .isEqualTo(6)
                    .isEqualTo(COUSR02_COPYBOOK_LINES.get(errMsgIndex)
                            - COPYBOOK_LINES.get(errMsgIndex));
            assertThat(COUSR02_PASSWD_LINE)
                    .as("PASSWDI occupies, on COUSR02, the very line USRTYPEI occupies here")
                    .isEqualTo(COPYBOOK_LINES.get(usrTypeIndex));
            assertThat(UserDeleteRequest.MAP_FIELD_COUNT)
                    .as("one field fewer than COUSR02, and that field is the password")
                    .isEqualTo(COUSR02_MAP_FIELD_COUNT - 1);
        }

        @Test
        @DisplayName("the password's structural position holds usrType PIC X(1), not an X(8) field")
        void thePasswordPositionHoldsTheTypeField() {
            RecordComponent[] components = UserDeleteRequest.class.getRecordComponents();
            int lNameIndex = MAP_MEMBERS.indexOf("lName");
            assertThat(components[lNameIndex].getName()).isEqualTo("lName");
            assertThat(components[lNameIndex + 1].getName())
                    .as("nothing separates the family name from the user type on this screen")
                    .isEqualTo("usrType");
            assertThat(DECLARED_WIDTHS.get(lNameIndex + 1))
                    .as("SEC-USR-TYPE PIC X(01), not SEC-USR-PWD PIC X(08)")
                    .isEqualTo(1)
                    .isNotEqualTo(SecUserRecord.SEC_USR_PWD_LENGTH);
            assertThat(components[lNameIndex + 2].getName())
                    .as("and the error message follows immediately after the type")
                    .isEqualTo("errMsg");

            FixedWidthRecord.FieldSpan lastName = SYMBOLIC_MAP_LAYOUT.span("LNAMEI");
            FixedWidthRecord.FieldSpan userType = SYMBOLIC_MAP_LAYOUT.span("USRTYPEI");
            assertThat(userType.offset() - lastName.endOffsetExclusive())
                    .as("exactly one xxxL/xxxF/FILLER prefix separates them - no hidden field")
                    .isEqualTo(FIELD_PREFIX_LENGTH);
        }

        @Test
        @DisplayName("the geometry is 235 data bytes, not the 243 a twelfth X(8) field would make")
        void theGeometryItselfExcludesAPasswordField() {
            assertThat(DECLARED_WIDTHS.stream().mapToInt(Integer::intValue).sum())
                    .isEqualTo(PAYLOAD_WIDTH_TOTAL)
                    .isEqualTo(235);
            assertThat(SYMBOLIC_MAP_LENGTH)
                    .as("12 + 11 x 7 + 235")
                    .isEqualTo(TIOAPFX_PREFIX_LENGTH + DFHMDF_NAMED * FIELD_PREFIX_LENGTH
                            + PAYLOAD_WIDTH_TOTAL);

            int withAPasswordField = SYMBOLIC_MAP_LENGTH + FIELD_PREFIX_LENGTH
                    + SecUserRecord.SEC_USR_PWD_LENGTH;
            assertThat(withAPasswordField).isEqualTo(339);
            assertThat(UserDeleteRequest.SYMBOLIC_MAP_LENGTH)
                    .as("the type publishes the eleven-field geometry, not the twelve-field one")
                    .isEqualTo(SYMBOLIC_MAP_LENGTH)
                    .isNotEqualTo(withAPasswordField);
        }

        @Test
        @DisplayName("the serialised payload carries no credential key either")
        void theWireFormatCarriesNoCredential() {
            Set<String> members = jsonMembersOf(populatedRequest());
            assertThat(members).containsExactlyInAnyOrderElementsOf(EXPECTED_JSON_MEMBERS);
            for (String member : members) {
                for (String forbidden : FORBIDDEN_CREDENTIAL_NAMES) {
                    assertThat(member.toLowerCase(Locale.ROOT)).doesNotContain(forbidden);
                }
            }
            assertThat(members).hasSize(COMPONENT_COUNT);
        }

        @Test
        @DisplayName("usrIdIn comes FIRST, before the names - COUSR02's order, not COUSR01's")
        void theIdFieldComesFirstAndIsNamedForUsridin() {
            assertThat(UserDeleteRequest.USRIDIN_FIELD).isEqualTo("USRIDINI")
                    .isNotEqualTo("USERIDI");
            int idIndex = MAP_MEMBERS.indexOf("usrIdIn");
            assertThat(idIndex)
                    .as("the id is the seventh item - straight after the six header fields")
                    .isEqualTo(6);
            assertThat(idIndex)
                    .as("and it precedes both name fields, which COUSR01 reverses")
                    .isLessThan(MAP_MEMBERS.indexOf("fName"))
                    .isLessThan(MAP_MEMBERS.indexOf("lName"));
            assertThat(SYMBOLIC_MAP_LAYOUT.span("USRIDINI").offset())
                    .isLessThan(SYMBOLIC_MAP_LAYOUT.span("FNAMEI").offset());
        }
    }

    @Nested
    @DisplayName("Width traps - eight not nine, seventy-eight not eighty")
    class WidthTraps {
        @ParameterizedTest(name = "component {0}")
        @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
        @DisplayName("each published width equals its PICTURE clause and its DFHMDF LENGTH")
        void publishedWidthsMatchBothSources(int index) {
            assertThat(PUBLISHED_WIDTHS.get(index))
                    .as("%s is %s PIC X(%d) at COUSR03.CPY:%d, and %s DFHMDF LENGTH=%d at "
                            + "COUSR03.bms:%d", MAP_MEMBERS.get(index),
                            SYMBOLIC_MAP_ITEMS.get(index), DECLARED_WIDTHS.get(index),
                            COPYBOOK_LINES.get(index), SCREEN_FIELDS.get(index),
                            MAPSET_LENGTHS.get(index), MAPSET_LINES.get(index))
                    .isEqualTo(DECLARED_WIDTHS.get(index))
                    .isEqualTo(MAPSET_LENGTHS.get(index));
            assertThat(SYMBOLIC_MAP_LAYOUT.span(SYMBOLIC_MAP_ITEMS.get(index)).length())
                    .as("and the copybook geometry agrees")
                    .isEqualTo(DECLARED_WIDTHS.get(index));
        }

        @Test
        @DisplayName("the eleven widths are 4, 40, 8, 8, 40, 8, 8, 20, 20, 1, 78")
        void declaredWidths() {
            assertThat(PUBLISHED_WIDTHS)
                    .containsExactly(4, 40, 8, 8, 40, 8, 8, 20, 20, 1, 78);
            assertThat(UserDeleteRequest.MAP_FIELDS_WIDTH_TOTAL)
                    .as("the eleven xxxI items occupy 235 bytes between them")
                    .isEqualTo(PAYLOAD_WIDTH_TOTAL);
        }

        @Test
        @DisplayName("TRAP 2 - curTime is 8 here; only COSGN00 widens its time field to 9")
        void curTimeIsEightNotNine() {
            assertThat(UserDeleteRequest.CURTIME_LENGTH)
                    .as("CURTIMEI PIC X(8), COUSR03.CPY:54")
                    .isEqualTo(8)
                    .isEqualTo(UserDeleteRequest.CURDATE_LENGTH);
            assertThat(UserDeleteRequest.CURTIME_LENGTH)
                    .as("eight, not the nine COSGN00 alone declares")
                    .isNotEqualTo(9);

            DateHeader header = DateHeader.from(codec(), Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
            assertThat(header.wsCurtimeHhMmSs())
                    .isEqualTo(FIXED_CURTIME)
                    .hasSize(DateHeader.WS_CURTIME_HH_MM_SS_LENGTH);
            assertThat(codec().movePicX(header.wsCurtimeHhMmSs(),
                    UserDeleteRequest.CURTIME_LENGTH))
                    .as("eight into eight is neither padded nor truncated")
                    .isEqualTo(FIXED_CURTIME)
                    .hasSize(UserDeleteRequest.CURTIME_LENGTH);
        }

        @Test
        @DisplayName("the header date fills curDate exactly, on a clock that never moves")
        void theHeaderDateFillsCurDateExactly() {
            DateHeader header = DateHeader.from(codec(), Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
            assertThat(header.wsCurdateMmDdYy())
                    .as("MM/DD/YY, the shape COUSR03C.cbl moves into CURDATE after :245")
                    .isEqualTo(FIXED_CURDATE)
                    .hasSize(DateHeader.WS_CURDATE_MM_DD_YY_LENGTH);
            assertThat(codec().movePicX(header.wsCurdateMmDdYy(),
                    UserDeleteRequest.CURDATE_LENGTH))
                    .isEqualTo(FIXED_CURDATE)
                    .hasSize(UserDeleteRequest.CURDATE_LENGTH);
            assertThat(codec().charset())
                    .as("the code page is named on every call, never inherited (B8)")
                    .isEqualTo(MAP_CHARSET);
        }

        @Test
        @DisplayName("TRAP 3 - the 80-byte message loses its last two characters on the way in")
        void theEightyByteMessageLosesItsLastTwoCharacters() {
            assertThat(WS_MESSAGE_LENGTH)
                    .as("WS-MESSAGE is two characters wider than the item it is moved into")
                    .isEqualTo(UserDeleteRequest.ERRMSG_LENGTH + 2);

            String eightyCharacters = "M".repeat(WS_MESSAGE_LENGTH - 2) + "XY";
            assertThat(eightyCharacters).hasSize(WS_MESSAGE_LENGTH);

            String moved = codec().movePicX(eightyCharacters, UserDeleteRequest.ERRMSG_LENGTH);
            assertThat(moved)
                    .as("78 characters survive; XY does not")
                    .hasSize(UserDeleteRequest.ERRMSG_LENGTH)
                    .isEqualTo("M".repeat(WS_MESSAGE_LENGTH - 2))
                    .doesNotContain("X")
                    .doesNotContain("Y");

            String padded = codec().movePicX(BLANK_USER_ID_MESSAGE,
                    UserDeleteRequest.ERRMSG_LENGTH);
            assertThat(padded)
                    .as("'User ID can NOT be empty...' at COUSR03C.cbl:179 pads on the right")
                    .hasSize(UserDeleteRequest.ERRMSG_LENGTH)
                    .startsWith(BLANK_USER_ID_MESSAGE)
                    .endsWith(" ");
            assertThat(padded.trim()).isEqualTo(BLANK_USER_ID_MESSAGE);

            List<String> values = blankMapValues();
            values.set(MAP_MEMBERS.indexOf("errMsg"), moved);
            assertThat(requestOf(values, NavigationContext.empty(), "").errMsg())
                    .isEqualTo(moved)
                    .hasSize(UserDeleteRequest.ERRMSG_LENGTH);
        }

        @Test
        @DisplayName("the titles are the 40-character CCDA literals, padding included")
        void theTitlesAreTheScreenTitleLiterals() {
            assertThat(UserDeleteRequest.TITLE01_LENGTH).isEqualTo(ScreenTitles.TITLE_LENGTH);
            assertThat(UserDeleteRequest.TITLE02_LENGTH).isEqualTo(ScreenTitles.TITLE_LENGTH);
            assertThat(ScreenTitles.CCDA_TITLE01).hasSize(UserDeleteRequest.TITLE01_LENGTH);
            assertThat(ScreenTitles.CCDA_TITLE02).hasSize(UserDeleteRequest.TITLE02_LENGTH);

            UserDeleteRequest request = populatedRequest();
            assertThat(request.title01()).isEqualTo(ScreenTitles.CCDA_TITLE01)
                    .hasSize(ScreenTitles.TITLE_LENGTH);
            assertThat(request.title02()).isEqualTo(ScreenTitles.CCDA_TITLE02)
                    .hasSize(ScreenTitles.TITLE_LENGTH);
            assertThat(codec().movePicX(ScreenTitles.CCDA_TITLE01,
                    UserDeleteRequest.TITLE01_LENGTH))
                    .as("40 into 40 changes nothing, trailing spaces included")
                    .isEqualTo(ScreenTitles.CCDA_TITLE01);
        }

        @Test
        @DisplayName("CCDA-THANK-YOU and CCDA-MSG-THANK-YOU are different things, and neither is here")
        void theTwoThankYouLiteralsAreNotInterchangeable() {
            assertThat(ScreenTitles.CCDA_THANK_YOU).hasSize(ScreenTitles.TITLE_LENGTH);
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU).hasSize(SystemMessages.MESSAGE_LENGTH);
            assertThat(SystemMessages.MESSAGE_LENGTH)
                    .as("50 against 40 - different widths, different owners")
                    .isNotEqualTo(ScreenTitles.TITLE_LENGTH);
            assertThat(ScreenTitles.CCDA_THANK_YOU.trim())
                    .isNotEqualTo(SystemMessages.CCDA_MSG_THANK_YOU.trim());

            assertThat(SystemMessages.CCDA_MSG_INVALID_KEY)
                    .hasSize(SystemMessages.MESSAGE_LENGTH);
            assertThat(SystemMessages.MESSAGE_LENGTH)
                    .as("50 into 78 pads; it never truncates")
                    .isLessThan(UserDeleteRequest.ERRMSG_LENGTH);
            assertThat(codec().movePicX(SystemMessages.CCDA_MSG_INVALID_KEY,
                    UserDeleteRequest.ERRMSG_LENGTH))
                    .hasSize(UserDeleteRequest.ERRMSG_LENGTH)
                    .startsWith(SystemMessages.CCDA_MSG_INVALID_KEY);
        }

        @Test
        @DisplayName("the AID token is 5, the width of CCARD-AID PIC X(5)")
        void theAidTokenWidth() {
            assertThat(UserDeleteRequest.AID_LENGTH)
                    .isEqualTo(5)
                    .isEqualTo(PfKeyResolver.AID_TOKEN_LENGTH);
            for (PfKeyResolver.AidKey key : PfKeyResolver.AidKey.values()) {
                assertThat(key.token())
                        .as("%s renders at the declared width, padded where the mnemonic is shorter",
                                key.name())
                        .hasSize(UserDeleteRequest.AID_LENGTH);
            }
        }

        @Test
        @DisplayName("the symbolic map closes at 324 bytes, and the two views are the same size")
        void symbolicMapGeometry() {
            assertThat(UserDeleteRequest.TIOAPFX_PREFIX_LENGTH)
                    .as("02 FILLER PIC X(12) at COUSR03.CPY:18, present because TIOAPFX=YES")
                    .isEqualTo(TIOAPFX_PREFIX_LENGTH);
            assertThat(UserDeleteRequest.FIELD_OVERHEAD_LENGTH)
                    .as("xxxL 2 + xxxF 1 + FILLER X(4) = 7 per field on the input view, and "
                            + "FILLER X(3) + xxxC + xxxP + xxxH + xxxV = 7 on the output view")
                    .isEqualTo(FIELD_PREFIX_LENGTH)
                    .isEqualTo(7);
            assertThat(UserDeleteRequest.SYMBOLIC_MAP_LENGTH)
                    .as("12 + 11 x 7 + 235 = 324, the same figure from either view, which is what "
                            + "lets 01 COUSR3AO REDEFINES COUSR3AI overlay field for field")
                    .isEqualTo(SYMBOLIC_MAP_LENGTH)
                    .isEqualTo(SYMBOLIC_MAP_LAYOUT.recordLength());
        }
    }

    @Nested
    @DisplayName("Projection onto SEC-USER-DATA - four fields of six, and the two left behind")
    class RecordProjection {
        @Test
        @DisplayName("the four projected widths are CSUSR01Y's own")
        void projectedWidthsAreTheRecordsWidths() {
            assertThat(UserDeleteRequest.USRIDIN_LENGTH)
                    .as("SEC-USR-ID PIC X(08), CSUSR01Y.cpy:18, moved to at COUSR03C.cbl:160")
                    .isEqualTo(SecUserRecord.SEC_USR_ID_LENGTH);
            assertThat(UserDeleteRequest.FNAME_LENGTH)
                    .as("SEC-USR-FNAME PIC X(20), CSUSR01Y.cpy:19, moved from at COUSR03C.cbl:165")
                    .isEqualTo(SecUserRecord.SEC_USR_FNAME_LENGTH);
            assertThat(UserDeleteRequest.LNAME_LENGTH)
                    .as("SEC-USR-LNAME PIC X(20), CSUSR01Y.cpy:20, moved from at COUSR03C.cbl:166")
                    .isEqualTo(SecUserRecord.SEC_USR_LNAME_LENGTH);
            assertThat(UserDeleteRequest.USRTYPE_LENGTH)
                    .as("SEC-USR-TYPE PIC X(01), CSUSR01Y.cpy:22, moved from at COUSR03C.cbl:167")
                    .isEqualTo(SecUserRecord.SEC_USR_TYPE_LENGTH);
        }

        @Test
        @DisplayName("the record is 80 bytes at offsets 0, 8, 28, 48, 56 and 57 - unchanged by this screen")
        void theRecordGeometryIsUnchanged() {
            assertThat(SecUserRecord.RECORD_LENGTH).isEqualTo(80);
            assertThat(List.of(SecUserRecord.SEC_USR_ID_OFFSET,
                    SecUserRecord.SEC_USR_FNAME_OFFSET,
                    SecUserRecord.SEC_USR_LNAME_OFFSET,
                    SecUserRecord.SEC_USR_PWD_OFFSET,
                    SecUserRecord.SEC_USR_TYPE_OFFSET,
                    SecUserRecord.SEC_USR_FILLER_OFFSET))
                    .as("CSUSR01Y.cpy:18-23 in declaration order")
                    .containsExactly(0, 8, 28, 48, 56, 57);
            assertThat(SecUserRecord.SEC_USR_ID_LENGTH + SecUserRecord.SEC_USR_FNAME_LENGTH
                    + SecUserRecord.SEC_USR_LNAME_LENGTH + SecUserRecord.SEC_USR_PWD_LENGTH
                    + SecUserRecord.SEC_USR_TYPE_LENGTH + SecUserRecord.SEC_USR_FILLER_LENGTH)
                    .as("8 + 20 + 20 + 8 + 1 + 23 = 80, with no byte unaccounted for")
                    .isEqualTo(SecUserRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("the record still holds a password at offset 48 - it is the SCREEN that omits it")
        void theRecordKeepsThePasswordTheScreenDoesNot() {
            assertThat(SecUserRecord.SEC_USR_PWD_OFFSET).isEqualTo(48);
            assertThat(SecUserRecord.SEC_USR_PWD_LENGTH).isEqualTo(8);
            assertThat(SecUserRecord.LAYOUT.hasSpan(SecUserRecord.FIELD_SEC_USR_PWD)).isTrue();

            SecUserRecord record = SecUserRecord.of("USER0001", "Given", "Family", "NOTREAL1",
                    NavigationContext.USER_TYPE_USER, MAP_CHARSET);
            byte[] image = SecUserRecord.encode(record, MAP_CHARSET);
            assertThat(image).hasSize(SecUserRecord.RECORD_LENGTH);
            SecUserRecord restored = SecUserRecord.decode(image, MAP_CHARSET);
            assertThat(restored.image(SecUserRecord.FIELD_SEC_USR_PWD))
                    .as("the record's own field, at its own width, unchanged")
                    .hasSize(SecUserRecord.SEC_USR_PWD_LENGTH)
                    .isEqualTo("NOTREAL1");

            assertThat(SYMBOLIC_MAP_ITEMS)
                    .doesNotContain("PASSWDI")
                    .hasSize(UserDeleteRequest.MAP_FIELD_COUNT);
        }

        @Test
        @DisplayName("SEC-USR-FILLER is named storage and is likewise not projected")
        void theNamedFillerIsNotProjected() {
            assertThat(SecUserRecord.SEC_USR_FILLER_LENGTH).isEqualTo(23);
            assertThat(SecUserRecord.SEC_USR_FILLER_OFFSET + SecUserRecord.SEC_USR_FILLER_LENGTH)
                    .as("it closes the record at byte 80")
                    .isEqualTo(SecUserRecord.RECORD_LENGTH);
            for (String item : PUBLISHED_ITEM_NAMES) {
                assertThat(item).doesNotContain("FILLER");
            }
            assertThat(SecUserRecord.blank().image(SecUserRecord.FIELD_SEC_USR_FILLER))
                    .as("a blank record still carries the filler, as spaces at its declared width")
                    .isEqualTo(spaces(SecUserRecord.SEC_USR_FILLER_LENGTH));
        }

        @Test
        @DisplayName("the type field carries A or U, and the payload does not police which")
        void theTypeFieldIsCarriedNotValidated() {
            for (String type : List.of(NavigationContext.USER_TYPE_ADMIN,
                    NavigationContext.USER_TYPE_USER, " ", "X")) {
                List<String> values = blankMapValues();
                values.set(MAP_MEMBERS.indexOf("usrType"), type);
                UserDeleteRequest request = requestOf(values, NavigationContext.empty(), "");
                assertThat(request.usrType()).isEqualTo(type);
                assertThat(violationsOf(request))
                        .as("a single character always fits PIC X(1), whatever it is")
                        .isEmpty();
            }
        }
    }

    @Nested
    @DisplayName("Validation - @Size maxima only, and a one-arm blank check the framework never sees")
    class ValidationConstraints {
        @Test
        @DisplayName("carries twelve @Size constraints and no other constraint at all")
        void carriesOnlySizeConstraints() {
            int sized = 0;
            for (RecordComponent component : UserDeleteRequest.class.getRecordComponents()) {
                for (Annotation annotation : component.getAccessor().getAnnotations()) {
                    assertThat(annotation.annotationType().getName())
                            .as("%s carries a constraint COUSR03C does not perform",
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
                    .as("the eleven map members plus the AID token; the commarea and the extension "
                            + "carry their own widths internally")
                    .isEqualTo(DFHMDF_NAMED + 1);
        }

        @ParameterizedTest(name = "component {0}")
        @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
        @DisplayName("each map member carries @Size(max = n) with n from its PICTURE clause")
        void sizeMirrorsTheDeclaredWidth(int index) {
            RecordComponent component = UserDeleteRequest.class.getRecordComponents()[index];
            Size size = component.getAccessor().getAnnotation(Size.class);
            assertThat(size)
                    .as("%s must bound what it accepts to its field width", MAP_MEMBERS.get(index))
                    .isNotNull();
            assertThat(size.max())
                    .as("%s is PIC X(%d)", SYMBOLIC_MAP_ITEMS.get(index),
                            DECLARED_WIDTHS.get(index))
                    .isEqualTo(DECLARED_WIDTHS.get(index));
            assertThat(size.min())
                    .as("no lower bound: a blank field is valid input on this screen")
                    .isZero();
        }

        @Test
        @DisplayName("the AID is bounded at 5 and the two typed carriers are unconstrained")
        void stateCarrierConstraints() {
            RecordComponent[] components = UserDeleteRequest.class.getRecordComponents();
            assertThat(components[DFHMDF_NAMED].getAccessor().getAnnotation(Size.class))
                    .as("NavigationContext validates its own components; a @Size on the whole group "
                            + "would be meaningless")
                    .isNull();
            assertThat(components[DFHMDF_NAMED + 1].getAccessor().getAnnotation(Size.class).max())
                    .as("CCARD-AID PIC X(5)")
                    .isEqualTo(UserDeleteRequest.AID_LENGTH);
            assertThat(components[DFHMDF_NAMED + 2].getAccessor().getAnnotation(Size.class))
                    .as("Cu03Info renders each item at its declared width in its own constructor")
                    .isNull();
        }

        @Test
        @DisplayName("a fully blank instance produces zero violations - the whole point of section 5")
        void blanknessIsNotAConstraintViolation() {
            assertThat(violationsOf(requestOf(blankMapValues(), NavigationContext.empty(), "")))
                    .as("eleven empty strings must bind, because COUSR03C:177 answers a blank id "
                            + "with a message rather than a rejection")
                    .isEmpty();
            assertThat(violationsOf(requestOf(nullMapValues(), null, null)))
                    .as("and so must eleven nulls plus absent state - the MOVE LOW-VALUES shape")
                    .isEmpty();
            assertThat(violationsOf(requestOf(spaceFilledMapValues(), NavigationContext.empty(),
                    spaces(UserDeleteRequest.AID_LENGTH))))
                    .as("and so must eleven space-filled fields at their declared widths")
                    .isEmpty();
            assertThat(violationsOf(populatedRequest()))
                    .as("a fully painted screen is equally valid")
                    .isEmpty();
        }

        @ParameterizedTest(name = "{0} at width {1} + 1")
        @CsvSource({"trnName,4", "title01,40", "curDate,8", "pgmName,8", "title02,40", "curTime,8",
            "usrIdIn,8", "fName,20", "lName,20", "usrType,1", "errMsg,78"})
        @DisplayName("a value one character wider than its field is reported against that field alone")
        void overWideValuesAreReportedAgainstOneField(String member, int width) {
            int index = MAP_MEMBERS.indexOf(member);
            List<String> values = blankMapValues();
            values.set(index, "W".repeat(width + 1));

            Set<ConstraintViolation<UserDeleteRequest>> violations =
                    violationsOf(requestOf(values, NavigationContext.empty(), ""));
            assertThat(violations)
                    .as("%s accepted %d characters into PIC X(%d)", member, width + 1, width)
                    .hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath().toString())
                    .as("the violation must name the member that overflowed and no other")
                    .isEqualTo(member);
        }

        @Test
        @DisplayName("an over-wide AID token is reported too, and nothing else is")
        void anOverWideAidIsReported() {
            Set<ConstraintViolation<UserDeleteRequest>> violations = violationsOf(
                    requestOf(blankMapValues(), NavigationContext.empty(),
                            "T".repeat(UserDeleteRequest.AID_LENGTH + 1)));
            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("aid");
        }

        @Test
        @DisplayName("a value exactly at its declared width is accepted, so the bound is inclusive")
        void exactWidthValuesAreAccepted() {
            assertThat(violationsOf(requestOf(spaceFilledMapValues(), NavigationContext.empty(),
                    PfKeyResolver.AidKey.PFK05.token())))
                    .as("@Size(max = n) admits exactly n, which is what a painted screen sends")
                    .isEmpty();
        }

        @Test
        @DisplayName("no blank check exists on fName, lName or usrType - they are display-only here")
        void theDisplayOnlyFieldsCarryNoPresenceCheck() {
            List<String> values = blankMapValues();
            values.set(MAP_MEMBERS.indexOf("usrIdIn"), "USER0001");
            UserDeleteRequest request = requestOf(values, NavigationContext.empty(), "");

            assertThat(violationsOf(request))
                    .as("an id with three empty display fields is exactly the state COUSR03C:157-160 "
                            + "constructs before it reads the file")
                    .isEmpty();
            assertThat(request.fName()).isEmpty();
            assertThat(request.lName()).isEmpty();
            assertThat(request.usrType()).isEmpty();

            for (String member : List.of("fName", "lName", "usrType")) {
                RecordComponent component = UserDeleteRequest.class
                        .getRecordComponents()[MAP_MEMBERS.indexOf(member)];
                assertThat(component.getAccessor().getAnnotation(Size.class))
                        .as("%s must carry a width bound", member)
                        .isNotNull();
                assertThat(component.getAccessor().getAnnotations())
                        .as("%s must carry a width bound and a wire name, and no presence check", member)
                        .allSatisfy(annotation -> assertThat(annotation.annotationType())
                                .isIn(Size.class, JsonProperty.class));
            }
        }

        @Test
        @DisplayName("the blank-id message is the program's own text, unchanged and unabbreviated")
        void theBlankIdMessageIsVerbatim() {
            assertThat(BLANK_USER_ID_MESSAGE)
                    .isEqualTo("User ID can NOT be empty...")
                    .endsWith("...")
                    .hasSizeLessThanOrEqualTo(WS_MESSAGE_LENGTH)
                    .hasSizeLessThanOrEqualTo(UserDeleteRequest.ERRMSG_LENGTH);

            List<String> values = blankMapValues();
            values.set(MAP_MEMBERS.indexOf("errMsg"),
                    codec().movePicX(BLANK_USER_ID_MESSAGE, UserDeleteRequest.ERRMSG_LENGTH));
            UserDeleteRequest request = requestOf(values, NavigationContext.empty(), "");
            assertThat(violationsOf(request))
                    .as("the message at its field width is valid input, since the response echoes it")
                    .isEmpty();
            assertThat(request.errMsg()).startsWith(BLANK_USER_ID_MESSAGE);
        }
    }

    @Nested
    @DisplayName("SPACES and LOW-VALUES - both carried, neither trimmed, neither nulled")
    class SpacesAndLowValues {
        @Test
        @DisplayName("a space-filled id and a LOW-VALUES id are different values, both valid")
        void theTwoBlankFormsAreDistinct() {
            String spacesId = spaces(UserDeleteRequest.USRIDIN_LENGTH);
            String lowValuesId = lowValues(UserDeleteRequest.USRIDIN_LENGTH);
            assertThat(spacesId).hasSize(UserDeleteRequest.USRIDIN_LENGTH);
            assertThat(lowValuesId).hasSize(UserDeleteRequest.USRIDIN_LENGTH)
                    .isNotEqualTo(spacesId);

            List<String> spacesValues = blankMapValues();
            spacesValues.set(MAP_MEMBERS.indexOf("usrIdIn"), spacesId);
            List<String> lowValues = blankMapValues();
            lowValues.set(MAP_MEMBERS.indexOf("usrIdIn"), lowValuesId);

            UserDeleteRequest spaced = requestOf(spacesValues, NavigationContext.empty(), "");
            UserDeleteRequest nulled = requestOf(lowValues, NavigationContext.empty(), "");

            assertThat(spaced.usrIdIn()).isEqualTo(spacesId);
            assertThat(nulled.usrIdIn()).isEqualTo(lowValuesId);
            assertThat(spaced)
                    .as("the two blank forms are different payloads, as the copybook makes them")
                    .isNotEqualTo(nulled);
            assertThat(violationsOf(spaced)).isEmpty();
            assertThat(violationsOf(nulled)).isEmpty();
        }

        @Test
        @DisplayName("neither form is trimmed, coerced to null or shortened by the round trip")
        void neitherFormIsCoercedByTheWire() {
            String spacesId = spaces(UserDeleteRequest.USRIDIN_LENGTH);
            String lowValuesId = lowValues(UserDeleteRequest.USRIDIN_LENGTH);

            List<String> values = blankMapValues();
            values.set(MAP_MEMBERS.indexOf("usrIdIn"), spacesId);
            values.set(MAP_MEMBERS.indexOf("fName"), lowValuesId + spaces(12));
            UserDeleteRequest request = requestOf(values, NavigationContext.empty(),
                    spaces(UserDeleteRequest.AID_LENGTH));

            UserDeleteRequest restored = deserialise(serialise(request));
            assertThat(restored.usrIdIn())
                    .as("eight spaces stay eight spaces; a trimming mapper would make them empty")
                    .isEqualTo(spacesId)
                    .hasSize(UserDeleteRequest.USRIDIN_LENGTH);
            assertThat(restored.fName())
                    .as("eight NULs followed by twelve spaces survive byte for byte")
                    .isEqualTo(lowValuesId + spaces(12))
                    .hasSize(UserDeleteRequest.FNAME_LENGTH);
            assertThat(restored.aid())
                    .as("and an all-spaces AID is not nulled either")
                    .isEqualTo(spaces(UserDeleteRequest.AID_LENGTH));
            assertThat(restored).isEqualTo(request);
        }

        @Test
        @DisplayName("an empty string is not turned into null, and null is not turned into empty")
        void emptyAndNullStayApart() {
            UserDeleteRequest empties = requestOf(blankMapValues(), NavigationContext.empty(), "");
            UserDeleteRequest nulls = requestOf(nullMapValues(), NavigationContext.empty(), null);

            assertThat(mapValuesOf(empties)).allMatch(""::equals);
            assertThat(mapValuesOf(nulls)).containsOnlyNulls();
            assertThat(empties).isNotEqualTo(nulls);

            UserDeleteRequest restoredEmpties = deserialise(serialise(empties));
            UserDeleteRequest restoredNulls = deserialise(serialise(nulls));
            assertThat(mapValuesOf(restoredEmpties))
                    .as("ACCEPT_EMPTY_STRING_AS_NULL_OBJECT is disabled, so \"\" stays \"\"")
                    .allMatch(""::equals);
            assertThat(mapValuesOf(restoredNulls))
                    .as("and a null member is emitted and read back as null, not as \"\"")
                    .containsOnlyNulls();
            assertThat(restoredEmpties).isEqualTo(empties);
            assertThat(restoredNulls).isEqualTo(nulls);
        }
    }

    @Nested
    @DisplayName("Metadata - xxxL, xxxF, xxxA and every FILLER stay off the wire")
    class MetadataIsNotPayload {
        @Test
        @DisplayName("the payload is exactly the eleven map members plus the three state members")
        void keySet() {
            assertThat(jsonMembersOf(populatedRequest()))
                    .containsExactlyInAnyOrderElementsOf(EXPECTED_JSON_MEMBERS)
                    .hasSize(COMPONENT_COUNT);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02", "CURTIME",
            "USRIDIN", "FNAME", "LNAME", "USRTYPE", "ERRMSG"})
        @DisplayName("no xxxL, xxxF, xxxA or xxxO name reaches the serialised form")
        void noSymbolicMapMetadataReachesTheWire(String screenField) {
            String json = serialise(populatedRequest()).toUpperCase(Locale.ROOT);
            for (String metadata : metadataNamesOf(screenField)) {
                assertThat(json)
                        .as("%s is metadata or the output view, never payload", metadata)
                        .doesNotContain("\"" + metadata + "\"");
            }
            Set<String> members = jsonMembersOf(populatedRequest());
            for (String member : members) {
                assertThat(member.toUpperCase(Locale.ROOT))
                        .as("no member may be named for a metadata item")
                        .isNotIn(metadataNamesOf(screenField));
            }
        }

        @Test
        @DisplayName("neither FILLER span is exposed, though both are declared in the geometry")
        void noFillerIsExposed() {
            assertThat(SYMBOLIC_MAP_LAYOUT.storageSpans())
                    .as("the TIOAPFX prefix, 11 xxxL halfwords, 11 xxxF bytes, 11 FILLER X(4) spans "
                            + "and 11 xxxI items")
                    .hasSize(1 + DFHMDF_NAMED * 4);
            long fillerSpans = SYMBOLIC_MAP_LAYOUT.storageSpans().stream()
                    .filter(span -> "FILLER".equals(span.name()))
                    .count();
            assertThat(fillerSpans)
                    .as("one 12-byte prefix, plus a 2-byte halfword and a 4-byte gap per field")
                    .isEqualTo(1 + DFHMDF_NAMED * 2L);

            String json = serialise(populatedRequest());
            assertThat(json).doesNotContain("FILLER").doesNotContain("filler")
                    .doesNotContain("TIOAPFX").doesNotContain("tioapfx");
            for (String member : jsonMembersOf(populatedRequest())) {
                assertThat(member.toLowerCase(Locale.ROOT)).doesNotContain("filler");
            }
        }

        @Test
        @DisplayName("the read-through context predicates are not promoted to JSON properties")
        void derivedPredicatesStayOffTheWire() {
            assertThat(jsonMembersOf(populatedRequest()))
                    .doesNotContain("pgmEnter").doesNotContain("pgmReenter")
                    .doesNotContain("enter").doesNotContain("reenter");
            assertThat(serialise(populatedRequest()))
                    .doesNotContain("\"pgmEnter\"").doesNotContain("\"pgmReenter\"");
        }

        @Test
        @DisplayName("a painted screen survives serialise then deserialise byte for byte")
        void roundTripPreservesEveryByte() {
            List<String> values = spaceFilledMapValues();
            values.set(MAP_MEMBERS.indexOf("usrIdIn"), "USER0001");
            values.set(MAP_MEMBERS.indexOf("fName"),
                    codec().movePicX("Given", UserDeleteRequest.FNAME_LENGTH));
            values.set(MAP_MEMBERS.indexOf("lName"),
                    codec().movePicX("Family", UserDeleteRequest.LNAME_LENGTH));
            values.set(MAP_MEMBERS.indexOf("usrType"), NavigationContext.USER_TYPE_ADMIN);
            values.set(MAP_MEMBERS.indexOf("errMsg"), spaces(UserDeleteRequest.ERRMSG_LENGTH));

            UserDeleteRequest request = requestOf(values,
                    NavigationContext.empty().withPgmReenter(),
                    PfKeyResolver.AidKey.PFK05.token(),
                    new UserDeleteRequest.Cu03Info("USER0001", "USER0050", 3,
                            UserDeleteRequest.Cu03Info.NEXT_PAGE_YES, "D", "USER0007"));
            UserDeleteRequest restored = deserialise(serialise(request));

            assertThat(mapValuesOf(restored))
                    .as("every one of the eleven members, padding included")
                    .containsExactlyElementsOf(mapValuesOf(request));
            assertThat(restored.fName())
                    .as("twenty characters in, twenty characters out")
                    .hasSize(UserDeleteRequest.FNAME_LENGTH)
                    .isEqualTo("Given" + spaces(UserDeleteRequest.FNAME_LENGTH - 5));
            assertThat(restored.lName())
                    .hasSize(UserDeleteRequest.LNAME_LENGTH)
                    .isEqualTo("Family" + spaces(UserDeleteRequest.LNAME_LENGTH - 6));
            assertThat(restored.errMsg())
                    .as("a 78-space message is 78 spaces, not an empty string")
                    .isEqualTo(spaces(UserDeleteRequest.ERRMSG_LENGTH));
            assertThat(restored.navigationContext()).isEqualTo(request.navigationContext());
            assertThat(restored.cu03Info()).isEqualTo(request.cu03Info());
            assertThat(restored).isEqualTo(request);
        }

        @Test
        @DisplayName("the communication area round-trips as a nested object, not a flattened string")
        void nestedContextRoundTrip() {
            UserDeleteRequest request = requestOf(blankMapValues(),
                    NavigationContext.empty()
                            .withFromTranid(UserDeleteRequest.TRANSACTION_ID)
                            .withFromProgram(UserDeleteRequest.PROGRAM_NAME)
                            .withUserTypeAdmin()
                            .withPgmReenter(),
                    PfKeyResolver.AidKey.ENTER.token());
            String json = serialise(request);
            assertThat(json)
                    .as("the group is an object, so its own members are addressable on the wire")
                    .contains("\"navigationContext\":{")
                    .contains("\"cu03Info\":{");

            UserDeleteRequest restored = deserialise(json);
            assertThat(restored.navigationContext().fromTranid())
                    .isEqualTo(UserDeleteRequest.TRANSACTION_ID);
            assertThat(restored.navigationContext().fromProgram())
                    .isEqualTo(UserDeleteRequest.PROGRAM_NAME);
            assertThat(restored.navigationContext().isAdmin()).isTrue();
            assertThat(restored.pgmReenter()).isTrue();
            assertThat(restored).isEqualTo(request);
        }

        @Test
        @DisplayName("the CSSETATY highlight targets items this inbound payload does not carry")
        void theHighlightTargetsItemsThisPayloadDoesNotCarry() {
            FieldAttributeSetter.FieldHighlight highlighted = FieldAttributeSetter.resolveFromFlags(
                    false, true, true, "USRIDIN", UserDeleteRequest.MAP_NAME + "O");
            assertThat(highlighted.colourItemAssigned())
                    .as("a blank field, on re-entry, is coloured")
                    .isTrue();
            assertThat(highlighted.outputItemAssigned())
                    .as("and the blank state is the one that also receives the asterisk")
                    .isTrue();
            assertThat(highlighted.colourItemValue()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(highlighted.outputItemValue()).isEqualTo(FieldAttributeSetter.ASTERISK);

            Set<String> members = jsonMembersOf(populatedRequest());
            assertThat(members)
                    .as("%s and %s are output-view items and are not inbound payload members",
                            highlighted.colourItemName(), highlighted.outputItemName())
                    .doesNotContain(highlighted.colourItemName())
                    .doesNotContain(highlighted.outputItemName());
            assertThat(highlighted.colourItemName())
                    .isEqualTo("USRIDIN" + FieldAttributeSetter.COLOUR_ITEM_SUFFIX);
            assertThat(highlighted.outputItemName())
                    .isEqualTo("USRIDIN" + FieldAttributeSetter.OUTPUT_ITEM_SUFFIX);

            assertThat(FieldAttributeSetter.resolveFromFlags(false, true, false, "USRIDIN",
                    UserDeleteRequest.MAP_NAME + "O").untouched())
                    .as("COUSR03C paints on first entry at :105 and validates only from :107")
                    .isTrue();
        }

        @Test
        @DisplayName("the extension round-trips under its own six member names")
        void extensionRoundTrip() {
            UserDeleteRequest request = requestOf(blankMapValues(), NavigationContext.empty(), "",
                    new UserDeleteRequest.Cu03Info("USER0001", "USER0050", 12,
                            UserDeleteRequest.Cu03Info.NEXT_PAGE_NO, "S", "USER0031"));
            String json = serialise(request);
            for (String member : EXTENSION_MEMBERS) {
                assertThat(json)
                        .as("%s is a member of the extension group on the wire", member)
                        .contains("\"" + member + "\"");
            }
            assertThat(deserialise(json).cu03Info()).isEqualTo(request.cu03Info());
        }
    }

    @Nested
    @DisplayName("CDEMO-CU03-INFO - 34 bytes on this DTO, and 160 + 34 = 194 in the commarea")
    class Cu03InfoExtension {
        @Test
        @DisplayName("the six items are on THIS type, in declaration order, at their declared widths")
        void theSixItemsAreCarriedHere() {
            RecordComponent[] components =
                    UserDeleteRequest.Cu03Info.class.getRecordComponents();
            assertThat(Arrays.stream(components).map(RecordComponent::getName).toList())
                    .as("COUSR03C.cbl:51-58 order, preserved")
                    .containsExactlyElementsOf(EXTENSION_MEMBERS);
            assertThat(List.of(UserDeleteRequest.Cu03Info.USRID_FIRST_LENGTH,
                    UserDeleteRequest.Cu03Info.USRID_LAST_LENGTH,
                    UserDeleteRequest.Cu03Info.PAGE_NUM_DIGITS,
                    UserDeleteRequest.Cu03Info.NEXT_PAGE_FLG_LENGTH,
                    UserDeleteRequest.Cu03Info.USR_SEL_FLG_LENGTH,
                    UserDeleteRequest.Cu03Info.USR_SELECTED_LENGTH))
                    .containsExactlyElementsOf(EXTENSION_WIDTHS);
            assertThat(UserDeleteRequest.Cu03Info.LENGTH)
                    .as("8 + 8 + 8 + 1 + 1 + 8")
                    .isEqualTo(EXTENSION_LENGTH)
                    .isEqualTo(EXTENSION_WIDTHS.stream().mapToInt(Integer::intValue).sum());
            assertThat(UserDeleteRequest.Cu03Info.initial().fieldImages().keySet())
                    .as("keyed by the names COUSR03C spells, hyphens and CU03 prefix intact")
                    .containsExactlyElementsOf(EXTENSION_ITEM_NAMES);
        }

        @Test
        @DisplayName("the commarea stays 160 bytes, proved through the codec at a named code page")
        void theCommareaIsNotWidenedByTheExtension() {
            assertThat(NavigationContext.GENERAL_INFO_LENGTH
                    + NavigationContext.CUSTOMER_INFO_LENGTH
                    + NavigationContext.ACCOUNT_INFO_LENGTH
                    + NavigationContext.CARD_INFO_LENGTH
                    + NavigationContext.MORE_INFO_LENGTH)
                    .as("34 + 84 + 12 + 16 + 14, COCOM01Y.cpy:19-44")
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH)
                    .isEqualTo(160);
            assertThat(NavigationContext.MORE_INFO_LENGTH)
                    .as("CDEMO-LAST-MAP and CDEMO-LAST-MAPSET are X(7) each, not X(8) - 14, not 16")
                    .isEqualTo(NavigationContext.LAST_MAP_LENGTH
                            + NavigationContext.LAST_MAPSET_LENGTH)
                    .isEqualTo(14);

            byte[] image = NavigationContext.empty().toFixedWidth(codec());
            assertThat(image)
                    .as("the encoded area is exactly the copybook's width, with the code page named")
                    .hasSize(NavigationContext.COMMAREA_LENGTH);
            assertThat(NavigationContext.fromFixedWidth(codec(), image))
                    .isEqualTo(NavigationContext.empty());

            for (RecordComponent component : NavigationContext.class.getRecordComponents()) {
                assertThat(component.getName().toLowerCase(Locale.ROOT))
                        .as("%s must not be a CU03 item", component.getName())
                        .doesNotContain("cu03").doesNotContain("nextpage")
                        .doesNotContain("selected");
            }
        }

        @Test
        @DisplayName("160 + 34 = 194, the area COUSR03C:94 restores and :136 hands back")
        void theCu03CommareaIsOneHundredAndNinetyFour() {
            assertThat(CU03_COMMAREA_LENGTH)
                    .as("CARDDEMO-COMMAREA plus 05 CDEMO-CU03-INFO")
                    .isEqualTo(194)
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH
                            + UserDeleteRequest.Cu03Info.LENGTH);

            byte[] commarea = NavigationContext.empty().toFixedWidth(codec());
            StringBuilder extension = new StringBuilder();
            UserDeleteRequest.Cu03Info.initial().fieldImages().values().forEach(extension::append);
            byte[] extensionImage = codec().encodeImage(extension.toString(),
                    "CDEMO-CU03-INFO");
            assertThat(extensionImage).hasSize(UserDeleteRequest.Cu03Info.LENGTH);
            assertThat(commarea.length + extensionImage.length).isEqualTo(CU03_COMMAREA_LENGTH);
        }

        @Test
        @DisplayName("the extension is a distinct block from CU00's and CU02's, not a shared type")
        void theBlockIsPrefixedForThisProgramAlone() {
            for (String item : EXTENSION_ITEM_NAMES) {
                assertThat(item)
                        .as("%s must carry this program's own prefix", item)
                        .startsWith("CDEMO-CU03-")
                        .doesNotContain("CU00").doesNotContain("CU02");
            }
            assertThat(UserDeleteRequest.Cu03Info.class.getSimpleName())
                    .as("named for the transaction that owns it")
                    .isEqualTo("Cu03Info");
            assertThat(UserDeleteRequest.Cu03Info.class.getEnclosingClass())
                    .as("declared where its owning payload is, as COUSR03C declares it inline")
                    .isEqualTo(UserDeleteRequest.class);
        }

        @Test
        @DisplayName("pageNum is an int - PIC 9(08) is scale-free, so no BigDecimal and no double")
        void thePageNumberIsAScaleFreeInteger() {
            RecordComponent pageNum =
                    UserDeleteRequest.Cu03Info.class.getRecordComponents()[2];
            assertThat(pageNum.getName()).isEqualTo("pageNum");
            assertThat(pageNum.getType())
                    .as("gate G22: eight digits, no V, no decimal position")
                    .isEqualTo(int.class);
            assertThat(UserDeleteRequest.Cu03Info.PAGE_NUM_DIGITS).isEqualTo(8);

            assertThat(new UserDeleteRequest.Cu03Info("", "", 7, "N", "", "").fieldImages()
                    .get("CDEMO-CU03-PAGE-NUM"))
                    .as("a numeric MOVE zero-fills on the LEFT")
                    .isEqualTo("00000007")
                    .hasSize(UserDeleteRequest.Cu03Info.PAGE_NUM_DIGITS);
            assertThat(codec().movePic9(7L, UserDeleteRequest.Cu03Info.PAGE_NUM_DIGITS))
                    .isEqualTo("00000007");
        }

        @ParameterizedTest(name = "flag \"{0}\": YES={1} NO={2}")
        @CsvSource({
            "Y, true,  false",
            "N, false, true",
            "' ', false, false",
            "X, false, false",
        })
        @DisplayName("both 88-level states are driven, in both directions, plus values that are neither")
        void bothNextPageStatesAreDriven(String flag, boolean yes, boolean no) {
            UserDeleteRequest.Cu03Info extension =
                    new UserDeleteRequest.Cu03Info("", "", 0, flag, "", "");

            assertThat(extension.nextPageFlg())
                    .as("the flag is carried at its declared width, whatever it holds")
                    .hasSize(UserDeleteRequest.Cu03Info.NEXT_PAGE_FLG_LENGTH);
            assertThat(UserDeleteRequest.Cu03Info.NEXT_PAGE_YES.equals(extension.nextPageFlg()))
                    .as("88 NEXT-PAGE-YES VALUE 'Y' holds for \"%s\": %s", flag, yes)
                    .isEqualTo(yes);
            assertThat(UserDeleteRequest.Cu03Info.NEXT_PAGE_NO.equals(extension.nextPageFlg()))
                    .as("88 NEXT-PAGE-NO VALUE 'N' holds for \"%s\": %s", flag, no)
                    .isEqualTo(no);
            assertThat(yes && no)
                    .as("no value satisfies both conditions")
                    .isFalse();

            UserDeleteRequest request = requestOf(blankMapValues(), NavigationContext.empty(), "",
                    extension);
            assertThat(deserialise(serialise(request)).cu03Info().nextPageFlg())
                    .isEqualTo(extension.nextPageFlg());
        }

        @Test
        @DisplayName("the declared default is 'N', which is the VALUE clause on line 54")
        void theDeclaredDefaultIsNo() {
            UserDeleteRequest.Cu03Info initial = UserDeleteRequest.Cu03Info.initial();
            assertThat(initial.nextPageFlg())
                    .as("PIC X(01) VALUE 'N' - what a cold start sees before :94 overwrites the area")
                    .isEqualTo(UserDeleteRequest.Cu03Info.NEXT_PAGE_NO)
                    .isEqualTo("N");
            assertThat(initial.pageNum())
                    .as("the other five items declare no VALUE, so the number begins at zero")
                    .isZero();
            assertThat(initial.usridFirst())
                    .isEqualTo(spaces(UserDeleteRequest.Cu03Info.USRID_FIRST_LENGTH));
            assertThat(initial.usridLast())
                    .isEqualTo(spaces(UserDeleteRequest.Cu03Info.USRID_LAST_LENGTH));
            assertThat(initial.usrSelFlg())
                    .isEqualTo(spaces(UserDeleteRequest.Cu03Info.USR_SEL_FLG_LENGTH));
            assertThat(initial.usrSelected())
                    .isEqualTo(spaces(UserDeleteRequest.Cu03Info.USR_SELECTED_LENGTH));
            assertThat(UserDeleteRequest.Cu03Info.NEXT_PAGE_YES).isEqualTo("Y");
        }

        @Test
        @DisplayName("an absent extension becomes the cold-start state, never null")
        void anAbsentExtensionIsNormalised() {
            assertThat(requestOf(blankMapValues(), NavigationContext.empty(), "", null).cu03Info())
                    .isEqualTo(UserDeleteRequest.Cu03Info.initial());

            UserDeleteRequest.Cu03Info supplied = new UserDeleteRequest.Cu03Info("USER0001",
                    "USER0050", 2, UserDeleteRequest.Cu03Info.NEXT_PAGE_YES, "D", "USER0007");
            assertThat(requestOf(blankMapValues(), NavigationContext.empty(), "", supplied)
                    .cu03Info())
                    .as("a supplied group is carried through untouched")
                    .isSameAs(supplied);
        }

        @Test
        @DisplayName("an absent character item becomes its width in spaces, and an over-wide one truncates")
        void theItemsFollowThePicXMoveRule() {
            UserDeleteRequest.Cu03Info absent =
                    new UserDeleteRequest.Cu03Info(null, null, 0, null, null, null);
            assertThat(absent.usridFirst())
                    .as("a PIC X item has no absent state; null becomes its spaces")
                    .isEqualTo(spaces(UserDeleteRequest.Cu03Info.USRID_FIRST_LENGTH));
            assertThat(absent.nextPageFlg()).isEqualTo(" ");
            assertThat(absent.usrSelected())
                    .isEqualTo(spaces(UserDeleteRequest.Cu03Info.USR_SELECTED_LENGTH));

            UserDeleteRequest.Cu03Info wide = new UserDeleteRequest.Cu03Info(
                    "USER00012345", "USER00067890", 0, "YES", "DELETE", "USER00099999");
            assertThat(wide.usridFirst())
                    .as("an alphanumeric MOVE truncates on the RIGHT")
                    .isEqualTo("USER0001")
                    .hasSize(UserDeleteRequest.Cu03Info.USRID_FIRST_LENGTH);
            assertThat(wide.nextPageFlg())
                    .as("'YES' into PIC X(01) keeps the leading character only")
                    .isEqualTo("Y");
            assertThat(wide.usrSelFlg()).isEqualTo("D");
            assertThat(wide.usrSelected()).isEqualTo("USER0009")
                    .hasSize(UserDeleteRequest.Cu03Info.USR_SELECTED_LENGTH);
        }

        @Test
        @DisplayName("a negative page number has no representation in PIC 9(08) and is refused")
        void aNegativePageNumberIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new UserDeleteRequest.Cu03Info("", "", -1, "N", "", ""))
                    .withMessageContaining("PIC 9(8)");
        }

        @Test
        @DisplayName("a page number needing nine digits is refused, not silently truncated")
        void anOverWidePageNumberIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new UserDeleteRequest.Cu03Info("", "", 100_000_000, "N", "",
                            ""))
                    .withMessageContaining("cannot hold");
            assertThat(new UserDeleteRequest.Cu03Info("", "", 99_999_999, "N", "", "").pageNum())
                    .as("the largest value the picture can hold is accepted, so the bound is exact")
                    .isEqualTo(99_999_999);
        }

        @Test
        @DisplayName("usrSelFlg and usrSelected are carried - this is how COUSR00C hands a user over")
        void theSelectionItemsAreCarried() {
            UserDeleteRequest.Cu03Info handover = new UserDeleteRequest.Cu03Info("USER0001",
                    "USER0010", 1, UserDeleteRequest.Cu03Info.NEXT_PAGE_YES, "D", "USER0007");
            assertThat(handover.usrSelFlg())
                    .hasSize(UserDeleteRequest.Cu03Info.USR_SEL_FLG_LENGTH)
                    .isEqualTo("D");
            assertThat(handover.usrSelected())
                    .as("COUSR03C.cbl:99-102 moves this into USRIDINI when it is not blank")
                    .hasSize(UserDeleteRequest.Cu03Info.USR_SELECTED_LENGTH)
                    .isEqualTo("USER0007");
            assertThat(handover.fieldImages())
                    .containsEntry("CDEMO-CU03-USR-SEL-FLG", "D")
                    .containsEntry("CDEMO-CU03-USR-SELECTED", "USER0007")
                    .containsEntry("CDEMO-CU03-PAGE-NUM", "00000001")
                    .hasSize(EXTENSION_MEMBERS.size());
        }
    }

    @Nested
    @DisplayName("Conversation state - the commarea, the AID and the context flag are all payload")
    class ConversationState {
        @Test
        @DisplayName("the communication area is a member, so no session is ever needed")
        void theCommareaIsAPayloadMember() {
            assertThat(jsonMembersOf(populatedRequest())).contains("navigationContext");
            assertThat(populatedRequest().navigationContext()).isNotNull();
            assertThat(UserDeleteRequest.class.getRecordComponents()[DFHMDF_NAMED].getType())
                    .isEqualTo(NavigationContext.class);

            for (Method method : UserDeleteRequest.class.getDeclaredMethods()) {
                assertThat(method.getReturnType().getName())
                        .as("%s must not expose a server-side state holder", method.getName())
                        .doesNotContain("HttpSession").doesNotContain("Cache")
                        .doesNotContain("SessionScope");
            }
        }

        @Test
        @DisplayName("the context is carried through untouched, never widened or re-modelled")
        void theContextIsPassedThrough() {
            NavigationContext context = NavigationContext.empty()
                    .withFromTranid(UserDeleteRequest.TRANSACTION_ID)
                    .withFromProgram(UserDeleteRequest.PROGRAM_NAME)
                    .withToProgram("COADM01C")
                    .withUserId("ADMIN001")
                    .withUserTypeAdmin()
                    .withPgmReenter();
            UserDeleteRequest request = requestOf(blankMapValues(), context,
                    PfKeyResolver.AidKey.PFK03.token());

            assertThat(request.navigationContext())
                    .as("the same instance, not a copy and not a projection")
                    .isSameAs(context);
            assertThat(request.navigationContext().toFixedWidth(codec()))
                    .as("and still exactly 160 bytes, because this payload does not widen it")
                    .hasSize(NavigationContext.COMMAREA_LENGTH);
        }

        @ParameterizedTest(name = "CDEMO-PGM-CONTEXT = {0}: ENTER={1} REENTER={2}")
        @CsvSource({
            "0, true,  false",
            "1, false, true",
            "2, false, false",
            "9, false, false",
        })
        @DisplayName("both 88-level context states are driven, in both directions, plus digits that are neither")
        void bothContextStatesAreDriven(int pgmContext, boolean enter, boolean reenter) {
            UserDeleteRequest request = requestOf(blankMapValues(),
                    NavigationContext.empty().withPgmContext(pgmContext), "");

            assertThat(request.pgmEnter()).isEqualTo(enter);
            assertThat(request.pgmReenter()).isEqualTo(reenter);

            assertThat(request.pgmEnter()).isEqualTo(request.navigationContext().isEnter());
            assertThat(request.pgmReenter()).isEqualTo(request.navigationContext().isReenter());
            assertThat(request.navigationContext().pgmContext()).isEqualTo(pgmContext);
        }

        @Test
        @DisplayName("without a context neither condition holds - the EIBCALEN = 0 cold start")
        void anAbsentContextSatisfiesNeitherCondition() {
            UserDeleteRequest request = requestOf(blankMapValues(), null, null);
            assertThat(request.navigationContext()).isNull();
            assertThat(request.pgmEnter()).isFalse();
            assertThat(request.pgmReenter()).isFalse();
            assertThat(violationsOf(request))
                    .as("an absent context is not a constraint violation; it is a routing condition")
                    .isEmpty();
        }

        @Test
        @DisplayName("the named context values are the copybook's, and the state survives JSON")
        void theContextStateSurvivesTheRoundTrip() {
            assertThat(NavigationContext.PGM_CONTEXT_ENTER).isZero();
            assertThat(NavigationContext.PGM_CONTEXT_REENTER).isEqualTo(1);

            UserDeleteRequest reentered = requestOf(blankMapValues(),
                    NavigationContext.empty().withPgmReenter(), "");
            UserDeleteRequest restored = deserialise(serialise(reentered));
            assertThat(restored.pgmReenter())
                    .as("SET CDEMO-PGM-REENTER TO TRUE at :96 must survive the wire, or the next "
                            + "entry would paint the screen again instead of validating it")
                    .isTrue();
            assertThat(restored.pgmEnter()).isFalse();
            assertThat(restored.navigationContext()).isEqualTo(reentered.navigationContext());
        }

        @ParameterizedTest(name = "aid = \"{0}\"")
        @ValueSource(strings = {"ENTER", "CLEAR", "PA1  ", "PA2  ", "PFK03", "PFK04", "PFK05",
            "PFK12"})
        @DisplayName("the resolved AID token is carried verbatim, trailing spaces included")
        void theAidTokenIsCarriedVerbatim(String token) {
            UserDeleteRequest request =
                    requestOf(blankMapValues(), NavigationContext.empty(), token);
            assertThat(request.aid())
                    .as("a token, not a raw EIBAID byte: the byte is EBCDIC and code-page dependent, "
                            + "the token is not")
                    .isEqualTo(token)
                    .hasSize(UserDeleteRequest.AID_LENGTH);
            assertThat(deserialise(serialise(request)).aid()).isEqualTo(token);
            assertThat(violationsOf(request)).isEmpty();
        }

        @Test
        @DisplayName("the tokens this screen branches on are the ones PfKeyResolver produces")
        void theTokensComeFromTheResolver() {
            assertThat(PfKeyResolver.resolve(CicsAid.DFHENTER))
                    .as("WHEN DFHENTER at :109 - PERFORM PROCESS-ENTER-KEY")
                    .contains(PfKeyResolver.AidKey.ENTER);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF3))
                    .as("WHEN DFHPF3 at :111 - return without saving, unlike COUSR02C's PF3")
                    .contains(PfKeyResolver.AidKey.PFK03);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF4))
                    .as("WHEN DFHPF4 at :119 - PERFORM CLEAR-CURRENT-SCREEN")
                    .contains(PfKeyResolver.AidKey.PFK04);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF5))
                    .as("WHEN DFHPF5 at :121 - PERFORM DELETE-USER-INFO, the confirm step")
                    .contains(PfKeyResolver.AidKey.PFK05);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF12))
                    .as("WHEN DFHPF12 at :123 - back to the admin menu")
                    .contains(PfKeyResolver.AidKey.PFK12);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHCLEAR))
                    .as("CLEAR is not a WHEN of this EVALUATE, so it falls to WHEN OTHER at :126 - "
                            + "but the token must still be expressible in the payload")
                    .contains(PfKeyResolver.AidKey.CLEAR);

            for (PfKeyResolver.AidKey key : List.of(PfKeyResolver.AidKey.ENTER,
                    PfKeyResolver.AidKey.PFK03, PfKeyResolver.AidKey.PFK04,
                    PfKeyResolver.AidKey.PFK05, PfKeyResolver.AidKey.PFK12)) {
                UserDeleteRequest request = requestOf(blankMapValues(), NavigationContext.empty(),
                        key.token());
                assertThat(request.aid()).isEqualTo(key.token())
                        .hasSize(UserDeleteRequest.AID_LENGTH);
            }
        }

        @Test
        @DisplayName("an absent or blank AID is carried as itself - no key indication is a state too")
        void anAbsentAidIsCarried() {
            assertThat(requestOf(blankMapValues(), NavigationContext.empty(), null).aid()).isNull();
            assertThat(requestOf(blankMapValues(), NavigationContext.empty(),
                    spaces(UserDeleteRequest.AID_LENGTH)).aid())
                    .isEqualTo(spaces(UserDeleteRequest.AID_LENGTH));
            assertThat(requestOf(blankMapValues(), NavigationContext.empty(), "").aid()).isEmpty();
            assertThat(PfKeyResolver.resolve(CicsAid.DFHNULL))
                    .as("DFHNULL is the no-key-pressed indication and resolves to no token")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("REDEFINES - eleven attribute overlays, each over one shared byte")
    class RedefinesOverlays {
        @Test
        @DisplayName("the layout tiles 324 bytes exactly: 12 + 11 x 7 + 235")
        void theGeometryIsTheCopybooks() {
            assertThat(FIELD_PREFIX_LENGTH)
                    .as("xxxL 2 + xxxF 1 + FILLER X(4) = 7, and the output view's prefix is also 7, "
                            + "which is what lets COUSR3AO overlay COUSR3AI field for field")
                    .isEqualTo(7);
            assertThat(DECLARED_WIDTHS.stream().mapToInt(Integer::intValue).sum())
                    .isEqualTo(PAYLOAD_WIDTH_TOTAL);
            assertThat(TIOAPFX_PREFIX_LENGTH + DFHMDF_NAMED * FIELD_PREFIX_LENGTH
                    + PAYLOAD_WIDTH_TOTAL).isEqualTo(SYMBOLIC_MAP_LENGTH);
            assertThat(SYMBOLIC_MAP_LAYOUT.recordLength()).isEqualTo(SYMBOLIC_MAP_LENGTH);
            assertThat(SYMBOLIC_MAP_LAYOUT.redefinitions())
                    .as("eleven per-field overlays; the group-level one is not modelled here")
                    .hasSize(DFHMDF_NAMED)
                    .hasSize(REDEFINES_LINES.size());
            assertThat(SYMBOLIC_MAP_LAYOUT.storageSpans().stream()
                    .mapToInt(FixedWidthRecord.FieldSpan::length).sum())
                    .as("the storage spans, overlays excluded, sum to the record length")
                    .isEqualTo(SYMBOLIC_MAP_LENGTH);
        }

        @ParameterizedTest(name = "{0}A redefines {0}F")
        @ValueSource(strings = {"TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02", "CURTIME",
            "USRIDIN", "FNAME", "LNAME", "USRTYPE", "ERRMSG"})
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
            "USRIDIN", "FNAME", "LNAME", "USRTYPE", "ERRMSG"})
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
        @DisplayName("writing every attribute leaves every xxxI item and every FILLER byte alone")
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
            assertThat(record.charset())
                    .as("the record was built over the named code page, never a default (B8)")
                    .isEqualTo(MAP_CHARSET);
        }

        @ParameterizedTest(name = "{0}A carries a real DFHBMSCA attribute byte")
        @ValueSource(strings = {"TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02", "CURTIME",
            "USRIDIN", "FNAME", "LNAME", "USRTYPE", "ERRMSG"})
        @DisplayName("the shared byte holds the attribute values DFHBMSCA actually defines")
        void theOverlayCarriesARealAttributeByte(String screenField) {
            FixedWidthRecord record = FixedWidthRecord.forLayout(SYMBOLIC_MAP_LAYOUT, MAP_CHARSET);
            FixedWidthRecord.FieldSpan flag = SYMBOLIC_MAP_LAYOUT.span(screenField + "F");
            FixedWidthRecord.FieldSpan attribute = SYMBOLIC_MAP_LAYOUT.span(screenField + "A");

            record.writeSpanBytes(attribute, new byte[] {BmsAttributes.DFHRED});
            assertThat(record.readSpanBytes(flag))
                    .as("the error colour written through the attribute view is the flag byte")
                    .containsExactly(BmsAttributes.DFHRED);

            record.writeSpanBytes(flag, new byte[] {BmsAttributes.DFHBMASB});
            assertThat(record.readSpanBytes(attribute))
                    .as("and an autoskip-bright attribute written through the flag view reads back "
                            + "through the overlay")
                    .containsExactly(BmsAttributes.DFHBMASB);
            assertThat(record.toByteArray()).hasSize(SYMBOLIC_MAP_LENGTH);
        }

        @Test
        @DisplayName("the eleven overlay offsets are the eleven flag offsets, and no other span moved")
        void everyOverlayLandsOnItsOwnFlagByte() {
            List<Integer> flagOffsets = new ArrayList<>();
            List<Integer> overlayOffsets = new ArrayList<>();
            for (String screenField : SCREEN_FIELDS) {
                flagOffsets.add(SYMBOLIC_MAP_LAYOUT.span(screenField + "F").offset());
                overlayOffsets.add(SYMBOLIC_MAP_LAYOUT.span(screenField + "A").offset());
            }
            assertThat(overlayOffsets)
                    .as("each of the eleven overlays sits on its own field's flag byte")
                    .containsExactlyElementsOf(flagOffsets)
                    .doesNotHaveDuplicates()
                    .isSorted();
            assertThat(SYMBOLIC_MAP_LAYOUT.redefinitions().stream()
                    .map(FixedWidthRecord.FieldSpan::offset).toList())
                    .containsExactlyElementsOf(flagOffsets);
        }
    }

    @Nested
    @DisplayName("Security posture - nothing to protect, and nothing invented to protect it with")
    class SecurityPosture {
        @Test
        @DisplayName("no hashing, encoder, token or security framework type is reachable")
        void noSecurityFrameworkTypeIsReachable() {
            List<String> reachable = new ArrayList<>();
            for (RecordComponent component : UserDeleteRequest.class.getRecordComponents()) {
                reachable.add(component.getType().getName());
                for (Annotation annotation : component.getAccessor().getAnnotations()) {
                    reachable.add(annotation.annotationType().getName());
                }
            }
            for (Method method : UserDeleteRequest.class.getDeclaredMethods()) {
                reachable.add(method.getReturnType().getName());
                reachable.add(method.getName());
                for (Class<?> parameter : method.getParameterTypes()) {
                    reachable.add(parameter.getName());
                }
            }
            for (Method method : UserDeleteRequest.Cu03Info.class.getDeclaredMethods()) {
                reachable.add(method.getReturnType().getName());
                reachable.add(method.getName());
            }

            for (String name : reachable) {
                for (String marker : FORBIDDEN_SECURITY_MARKERS) {
                    assertThat(name)
                            .as("%s suggests %s, which this screen has no subject for", name, marker)
                            .doesNotContain(marker);
                }
            }
            assertThat(reachable).isNotEmpty();
        }

        @Test
        @DisplayName("the diagnostic rendering withholds the personal name and reports its presence")
        void theDiagnosticRenderingWithholdsThePersonalName() {
            UserDeleteRequest request = populatedRequest();
            String rendered = request.toString();

            assertThat(rendered).doesNotContain("Given").doesNotContain("Family");
            assertThat(rendered).contains("fName=").contains("lName=");
            assertThat(rendered)
                    .contains(UserDeleteRequest.TRANSACTION_ID)
                    .contains(UserDeleteRequest.PROGRAM_NAME)
                    .contains(request.usrIdIn())
                    .contains("navigationContext=")
                    .contains("aid=");
            assertThat(rendered).startsWith("UserDeleteRequest[").endsWith("]");
        }

        @Test
        @DisplayName("equals and hashCode cover every component, extension included")
        void valueSemanticsCoverEveryComponent() {
            UserDeleteRequest base = populatedRequest();

            for (int index = 0; index < DFHMDF_NAMED; index++) {
                List<String> altered = new ArrayList<>(mapValuesOf(base));
                altered.set(index, "Q".repeat(DECLARED_WIDTHS.get(index)));
                assertThat(requestOf(altered, base.navigationContext(), base.aid(),
                        base.cu03Info()))
                        .as("changing %s must change the value", MAP_MEMBERS.get(index))
                        .isNotEqualTo(base);
            }
            assertThat(requestOf(mapValuesOf(base), NavigationContext.empty().withPgmReenter(),
                    base.aid(), base.cu03Info()))
                    .as("and so must changing the communication area")
                    .isNotEqualTo(base);
            assertThat(requestOf(mapValuesOf(base), base.navigationContext(),
                    PfKeyResolver.AidKey.PFK05.token(), base.cu03Info()))
                    .as("and the AID token")
                    .isNotEqualTo(base);
            assertThat(requestOf(mapValuesOf(base), base.navigationContext(), base.aid(),
                    new UserDeleteRequest.Cu03Info("", "", 1, "N", "", "")))
                    .as("and the extension group")
                    .isNotEqualTo(base);
            assertThat(populatedRequest()).isEqualTo(base).hasSameHashCodeAs(base);
        }
    }

    @Nested
    @DisplayName("Vestigial and declared state - recorded, not modelled (B4, B5)")
    class VestigialAndDeclaredState {
        @Test
        @DisplayName("the vestigial WS-USR-MODIFIED flag is recorded here and modelled nowhere")
        void theVestigialModifiedFlagIsNotModelled() {
            for (RecordComponent component : UserDeleteRequest.class.getRecordComponents()) {
                assertThat(component.getName().toLowerCase(Locale.ROOT))
                        .as("%s must not model WS-USR-MODIFIED", component.getName())
                        .doesNotContain("modified").doesNotContain("dirty").doesNotContain("changed");
            }
            for (RecordComponent component
                    : UserDeleteRequest.Cu03Info.class.getRecordComponents()) {
                assertThat(component.getName().toLowerCase(Locale.ROOT))
                        .doesNotContain("modified");
            }
            assertThat(serialise(populatedRequest()))
                    .doesNotContain("usrModified").doesNotContain("USR-MODIFIED");
        }

        @Test
        @DisplayName("no optimistic-concurrency member exists, because COUSR03C has no such check")
        void noOptimisticConcurrencyMemberExists() {
            for (RecordComponent component : UserDeleteRequest.class.getRecordComponents()) {
                assertThat(component.getName().toLowerCase(Locale.ROOT))
                        .as("%s must not be a concurrency token", component.getName())
                        .doesNotContain("version").doesNotContain("etag")
                        .doesNotContain("beforeimage").doesNotContain("revision");
            }
        }

        @Test
        @DisplayName("no dataset name appears in the payload's surface (G46)")
        void noDatasetNameIsExposed() {
            String json = serialise(populatedRequest());
            assertThat(json)
                    .doesNotContain("AWS.M2.CARDDEMO")
                    .doesNotContain("VSAM")
                    .doesNotContain("USRSEC");
            for (String member : jsonMembersOf(populatedRequest())) {
                assertThat(member.toUpperCase(Locale.ROOT)).doesNotContain("USRSEC");
            }
        }
    }

    @Nested
    @DisplayName("The blank payload of INITIALIZE-ALL-FIELDS")
    class BlankPayload {
        @ParameterizedTest(name = "{0} is {1} spaces")
        @CsvSource({"trnName,4", "title01,40", "curDate,8", "pgmName,8", "title02,40", "curTime,8",
            "usrIdIn,8", "fName,20", "lName,20", "usrType,1", "errMsg,78"})
        @DisplayName("each member is a run of spaces of exactly its declared width")
        void spaceFilledAtDeclaredWidth(String member, int width) {
            String value = mapValuesOf(UserDeleteRequest.empty()).get(MAP_MEMBERS.indexOf(member));
            assertThat(value)
                    .as("%s must be %d spaces, not an empty string and not null", member, width)
                    .isEqualTo(spaces(width))
                    .hasSize(width);
        }

        @Test
        @DisplayName("no member is null, the AID is five spaces and the state carriers are initial")
        void stateCarriersAreInitialised() {
            UserDeleteRequest blank = UserDeleteRequest.empty();
            assertThat(mapValuesOf(blank)).doesNotContainNull();
            assertThat(blank.aid()).isEqualTo(spaces(UserDeleteRequest.AID_LENGTH));
            assertThat(blank.navigationContext()).isEqualTo(NavigationContext.empty());
            assertThat(blank.cu03Info()).isEqualTo(UserDeleteRequest.Cu03Info.initial());
            assertThat(blank.pgmEnter())
                    .as("an initial commarea is in CDEMO-PGM-ENTER state, so the screen paints")
                    .isTrue();
            assertThat(blank.pgmReenter()).isFalse();
            assertThat(violationsOf(blank))
                    .as("a blank payload is valid input, which is the whole point of it")
                    .isEmpty();
        }

        @Test
        @DisplayName("it is the MOVE SPACES shape, not the MOVE LOW-VALUES shape of line 97")
        void spacesNotLowValues() {
            UserDeleteRequest blank = UserDeleteRequest.empty();
            assertThat(blank.usrIdIn())
                    .isEqualTo(spaces(UserDeleteRequest.USRIDIN_LENGTH))
                    .isNotEqualTo(lowValues(UserDeleteRequest.USRIDIN_LENGTH));
            assertThat(blank).isNotEqualTo(requestOf(nullMapValues(), NavigationContext.empty(),
                    spaces(UserDeleteRequest.AID_LENGTH)));
            assertThat(blank.errMsg())
                    .as("WS-MESSAGE is blanked alongside the fields at :356")
                    .isEqualTo(spaces(UserDeleteRequest.ERRMSG_LENGTH));
        }

        @Test
        @DisplayName("two blank payloads are equal, share a hash and survive the wire unchanged")
        void valueSemanticsAndRoundTrip() {
            assertThat(UserDeleteRequest.empty())
                    .isEqualTo(UserDeleteRequest.empty())
                    .hasSameHashCodeAs(UserDeleteRequest.empty());
            assertThat(deserialise(serialise(UserDeleteRequest.empty())))
                    .as("324 bytes of screen reduced to a payload and back, padding intact")
                    .isEqualTo(UserDeleteRequest.empty());
            assertThat(jsonMembersOf(UserDeleteRequest.empty()))
                    .containsExactlyInAnyOrderElementsOf(EXPECTED_JSON_MEMBERS);
        }
    }
}

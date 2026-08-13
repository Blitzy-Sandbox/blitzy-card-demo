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
import com.vsergeychik.carddemo.common.ScreenFieldImage;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.user.model.SecUserRecord;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for {@link UserDeleteResponse} - the outbound payload of
 * {@code DELETE /api/users/&#123;userId&#125;}, CICS transaction {@code CU03}, program
 * {@code app/cbl/COUSR03C.cbl}, map {@code COUSR3A} of mapset {@code COUSR03}.
 */
@DisplayName("UserDeleteResponse - COUSR03 symbolic map, CU03 / COUSR03C")
class UserDeleteResponseTest {
    private static final Charset MAP_CHARSET = StandardCharsets.US_ASCII;

    private static final List<String> DFHMDF_LABELS = List.of("TRNNAME",
            "TITLE01",
            "CURDATE",
            "PGMNAME",
            "TITLE02",
            "CURTIME",
            "USRIDIN",
            "FNAME",
            "LNAME",
            "USRTYPE",
            "ERRMSG");

    private static final int DFHMDF_TOTAL = 26;

    private static final int DFHMDF_NAMED = 11;

    private static final List<String> XXXO_ITEMS = List.of("TRNNAMEO",
            "TITLE01O",
            "CURDATEO",
            "PGMNAMEO",
            "TITLE02O",
            "CURTIMEO",
            "USRIDINO",
            "FNAMEO",
            "LNAMEO",
            "USRTYPEO",
            "ERRMSGO");

    private static final List<String> XXXI_ITEMS = List.of("TRNNAMEI",
            "TITLE01I",
            "CURDATEI",
            "PGMNAMEI",
            "TITLE02I",
            "CURTIMEI",
            "USRIDINI",
            "FNAMEI",
            "LNAMEI",
            "USRTYPEI",
            "ERRMSGI");

    private static final List<String> MAP_MEMBERS = List.of("trnName",
            "title01",
            "curDate",
            "pgmName",
            "title02",
            "curTime",
            "usrIdIn",
            "fName",
            "lName",
            "usrType",
            "errMsg");

    private static String wireNameOf(String member) {
        return MAP_MEMBERS.contains(member) ? member.toLowerCase(Locale.ROOT) : member;
    }

    private static List<String> wireNamesOf(List<String> members) {
        return members.stream().map(UserDeleteResponseTest::wireNameOf).toList();
    }

    private static final List<Integer> DECLARED_WIDTHS = List.of(4, 40, 8, 8, 40, 8, 8, 20, 20, 1, 78);

    private static final List<Integer> COUSR03_XXXI_LINES =
            List.of(24, 30, 36, 42, 48, 54, 60, 66, 72, 78, 84);

    private static final List<Integer> COUSR03_XXXO_LINES =
            List.of(92, 98, 104, 110, 116, 122, 128, 134, 140, 146, 152);

    private static final List<Integer> COUSR03_BMS_LINES =
            List.of(34, 38, 47, 57, 61, 70, 85, 103, 116, 130, 140);

    private static final int COUSR03_OUTPUT_GROUP_LINE = 85;

    private static final int COUSR02_OUTPUT_GROUP_LINE = 91;

    private static final int COUSR03_USRTYPEI_LINE = 78;

    private static final int COUSR03_ERRMSGI_LINE = 84;

    private static final int COUSR02_PASSWDI_LINE = 78;

    private static final int COUSR02_USRTYPEI_LINE = 84;

    private static final int COUSR02_ERRMSGI_LINE = 90;

    private static final int COUSR02_MAP_FIELD_COUNT = 12;

    private static final int CPY_LINES_PER_FIELD_GROUP = 6;

    private static final int TIOAPFX_PREFIX_LENGTH = 12;

    private static final int LENGTH_ITEM_LENGTH = 2;

    private static final int ATTRIBUTE_ITEM_LENGTH = 1;

    private static final int INPUT_FILLER_LENGTH = 4;

    private static final int OUTPUT_FILLER_LENGTH = 3;

    private static final int OUTPUT_ATTRIBUTE_ITEM_COUNT = 4;

    private static final int INPUT_FIELD_PREFIX_LENGTH =
            LENGTH_ITEM_LENGTH + ATTRIBUTE_ITEM_LENGTH + INPUT_FILLER_LENGTH;

    private static final int OUTPUT_FIELD_PREFIX_LENGTH =
            OUTPUT_FILLER_LENGTH + OUTPUT_ATTRIBUTE_ITEM_COUNT * ATTRIBUTE_ITEM_LENGTH;

    private static final int PAYLOAD_WIDTH_TOTAL = 235;

    private static final int SYMBOLIC_MAP_LENGTH = 324;

    private static final int COUSR02_SYMBOLIC_MAP_LENGTH = 339;

    private static final int ABSENT_PASSWORD_WIDTH = 8;

    private static final int COUSR03_REDEFINES_COUNT = 12;

    private static final int COUSR03_PER_FIELD_REDEFINES = 11;

    private static final int COUSR03_GROUP_REDEFINES = 1;

    private static final String TRANSACTION_ID = "CU03";

    private static final String PROGRAM_NAME = "COUSR03C";

    private static final String MAP_NAME = "COUSR3A";

    private static final String MAPSET_NAME = "COUSR03";

    private static final int CSD_TRANSACTION_LINE = 479;

    private static final int CSD_PROGRAM_LINE = 480;

    private static final String PF3_DEFAULT_PROGRAM = "COSGN00C";

    private static final String MSG_USER_ID_EMPTY = "User ID can NOT be empty...";

    private static final String MSG_PRESS_PF5 = "Press PF5 key to delete this user ...";

    private static final String MSG_UNABLE_TO_LOOKUP_USER = "Unable to lookup User...";

    private static final String MSG_USER_ID_NOT_FOUND = "User ID NOT found...";

    private static final String MSG_UNABLE_TO_UPDATE_USER = "Unable to Update User...";

    private static final String SUCCESS_PREFIX = "User ";

    private static final String SUCCESS_SUFFIX = " has been deleted ...";

    private static final int WS_MESSAGE_LENGTH = 80;

    private static final int ERR_MSG_LENGTH = 78;

    private static final int TRUNCATED_CHARACTERS = WS_MESSAGE_LENGTH - ERR_MSG_LENGTH;

    private static final int SEC_USR_ID_WIDTH = 8;

    private static final int SEC_USR_FNAME_WIDTH = 20;

    private static final int SEC_USR_LNAME_WIDTH = 20;

    private static final int SEC_USR_TYPE_WIDTH = 1;

    private static final int SEC_USR_PWD_WIDTH = 8;

    private static final int SEC_USR_FILLER_WIDTH = 23;

    private static final int SEC_USER_DATA_LENGTH = 80;

    private static final int COMMAREA_GENERAL_LENGTH = 34;

    private static final int COMMAREA_CUSTOMER_LENGTH = 84;

    private static final int COMMAREA_ACCOUNT_LENGTH = 12;

    private static final int COMMAREA_CARD_LENGTH = 16;

    private static final int COMMAREA_MORE_LENGTH = 14;

    private static final int COMMAREA_LENGTH = 160;

    private static final int LAST_MAP_WIDTH = 7;

    private static final int LAST_MAPSET_WIDTH = 7;

    private static final int TO_PROGRAM_WIDTH = 8;

    private static final List<String> CU03_ITEM_NAMES = List.of("CDEMO-CU03-USRID-FIRST",
            "CDEMO-CU03-USRID-LAST",
            "CDEMO-CU03-PAGE-NUM",
            "CDEMO-CU03-NEXT-PAGE-FLG",
            "CDEMO-CU03-USR-SEL-FLG",
            "CDEMO-CU03-USR-SELECTED");

    private static final List<Integer> CU03_ITEM_WIDTHS = List.of(8, 8, 8, 1, 1, 8);

    private static final int CU03_EXTENSION_LENGTH = 34;

    private static final int CU03_COMMAREA_LENGTH = COMMAREA_LENGTH + CU03_EXTENSION_LENGTH;

    private static final String NEXT_PAGE_YES = "Y";

    private static final String NEXT_PAGE_NO = "N";

    private static final int PAGE_NUM_DIGITS = 8;

    private static final int PGM_CONTEXT_ENTER = 0;

    private static final int PGM_CONTEXT_REENTER = 1;

    private static final Instant FIXED_INSTANT = Instant.parse("2022-07-19T23:12:35Z");

    private static final String FIXED_CURDATE = "07/19/22";

    private static final String FIXED_CURTIME = "23:12:35";

    private static final List<String> CREDENTIAL_TOKENS = List.of("passwd",
            "password",
            "pwd",
            "secret",
            "credential",
            "passphrase");

    private static final List<String> FORBIDDEN_SECURITY_MARKERS = List.of("PasswordEncoder",
            "BCrypt",
            "MessageDigest",
            "Jwt",
            "JWT",
            "Authentication",
            "GrantedAuthority",
            "UserDetails",
            "SecurityContext",
            "org.springframework.security");

    private static final List<String> FORBIDDEN_STATE_MARKERS = List.of("HttpSession",
            "HttpServletRequest",
            "SessionAttribute",
            "ThreadLocal",
            "Cache",
            "AtomicReference");

    private static final List<String> CONTROL_SUFFIXES = List.of("L", "F", "A", "C", "P", "H", "V");

    private static final List<String> NAVIGATION_MEMBERS =
            List.of("navigationContext", "nextProgram", "nextMapset", "nextMap");

    private static final String EXTENSION_MEMBER = "cu03Info";

    private static final int COMPONENT_COUNT = 16;

    private static final FixedWidthRecord.RecordLayout INPUT_VIEW_LAYOUT = inputViewLayout();

    private static final FixedWidthRecord.RecordLayout OUTPUT_VIEW_LAYOUT = outputViewLayout();

    private static FixedWidthCodec codec() {
        return new FixedWidthCodec(MAP_CHARSET);
    }

    private static FixedWidthRecord.RecordLayout inputViewLayout() {
        List<FixedWidthRecord.FieldSpan> spans = new ArrayList<>();
        spans.add(FixedWidthRecord.FieldSpan.filler(0, TIOAPFX_PREFIX_LENGTH));
        int cursor = TIOAPFX_PREFIX_LENGTH;
        for (int index = 0; index < DFHMDF_NAMED; index++) {
            String field = DFHMDF_LABELS.get(index);
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
                    XXXI_ITEMS.get(index), cursor, DECLARED_WIDTHS.get(index)));
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
            String field = DFHMDF_LABELS.get(index);
            spans.add(FixedWidthRecord.FieldSpan.filler(cursor, OUTPUT_FILLER_LENGTH));
            cursor += OUTPUT_FILLER_LENGTH;
            for (String suffix : List.of("C", "P", "H", "V")) {
                spans.add(FixedWidthRecord.FieldSpan.alphanumeric(
                        field + suffix, cursor, ATTRIBUTE_ITEM_LENGTH));
                cursor += ATTRIBUTE_ITEM_LENGTH;
            }
            spans.add(FixedWidthRecord.FieldSpan.alphanumeric(
                    XXXO_ITEMS.get(index), cursor, DECLARED_WIDTHS.get(index)));
            cursor += DECLARED_WIDTHS.get(index);
        }
        return FixedWidthRecord.RecordLayout.of(SYMBOLIC_MAP_LENGTH,
                spans.toArray(FixedWidthRecord.FieldSpan[]::new));
    }

    private static List<String> componentNames() {
        return Arrays.stream(UserDeleteResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    private static List<Class<?>> componentTypes() {
        return Arrays.stream(UserDeleteResponse.class.getRecordComponents())
                .map(RecordComponent::getType)
                .toList();
    }

    private static String wsMessageImage(String text) {
        return codec().movePicX(text, WS_MESSAGE_LENGTH);
    }

    private static String errMsgImage(String wsMessage) {
        return codec().movePicX(wsMessage, ERR_MSG_LENGTH);
    }

    private static String delimitedBySpace(String value) {
        int firstSpace = value.indexOf(' ');
        return firstSpace < 0 ? value : value.substring(0, firstSpace);
    }

    private static String successMessageImage(String secUsrId) {
        String composed = codec().concatenateDelimitedBySize(SUCCESS_PREFIX,
                delimitedBySpace(secUsrId),
                SUCCESS_SUFFIX);
        return wsMessageImage(composed);
    }

    private static UserDeleteResponse populated() {
        return UserDeleteResponse.empty()
                .withTrnName(TRANSACTION_ID)
                .withTitle01(ScreenTitles.CCDA_TITLE01)
                .withCurDate(FIXED_CURDATE)
                .withPgmName(PROGRAM_NAME)
                .withTitle02(ScreenTitles.CCDA_TITLE02)
                .withCurTime(FIXED_CURTIME)
                .withUsrIdIn("USER0001")
                .withFName(codec().movePicX("John", SEC_USR_FNAME_WIDTH))
                .withLName(codec().movePicX("Doe", SEC_USR_LNAME_WIDTH))
                .withUsrType("U")
                .withErrMsg(errMsgImage(wsMessageImage(MSG_PRESS_PF5)))
                .withNextProgram("COADM01C")
                .withNextMapset(MAPSET_NAME)
                .withNextMap(MAP_NAME)
                .withCu03Info(new UserDeleteRequest.Cu03Info("USER0001",
                        "USER0010",
                        3,
                        NEXT_PAGE_NO,
                        "D",
                        "USER0004"));
    }

    private static ObjectMapper webConfigMapper() {
        return new ObjectMapper()
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT)
                .configure(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN, true);
    }

    private static UserDeleteResponse roundTrip(UserDeleteResponse response) throws Exception {
        ObjectMapper mapper = webConfigMapper();
        return mapper.readValue(mapper.writeValueAsString(response), UserDeleteResponse.class);
    }

    private static Map<String, Object> asJsonMap(UserDeleteResponse response) throws Exception {
        ObjectMapper mapper = webConfigMapper();
        return mapper.readValue(mapper.writeValueAsString(response),
                new TypeReference<Map<String, Object>>() { });
    }

    @Nested
    @DisplayName("Map projection - gate G9, eleven members and eleven DFHMDF definitions")
    class MapProjection {
        @Test
        @DisplayName("sixteen components: eleven map-derived, four navigation, one extension")
        void componentInventory() {
            List<String> components = componentNames();

            assertThat(components)
                    .as("11 xxxO items + navigationContext/nextProgram/nextMapset/nextMap + cu03Info")
                    .hasSize(COMPONENT_COUNT);
            assertThat(components.subList(0, DFHMDF_NAMED))
                    .as("the map-derived members come first, in map order")
                    .containsExactlyElementsOf(MAP_MEMBERS);
            assertThat(components).containsAll(NAVIGATION_MEMBERS).contains(EXTENSION_MEMBER);
            assertThat(UserDeleteResponse.MAP_FIELD_COUNT)
                    .as("the type publishes the count, so a reader need not recount the copybook")
                    .isEqualTo(DFHMDF_NAMED)
                    .isEqualTo(XXXO_ITEMS.size())
                    .isEqualTo(DFHMDF_LABELS.size());
        }

        @Test
        @DisplayName("the 26-versus-11 DFHMDF split: only the labelled fields are payload members")
        void onlyLabelledFieldsAreMembers() {
            assertThat(DFHMDF_TOTAL)
                    .as("app/bms/COUSR03.bms declares 26 DFHMDF entries in all")
                    .isEqualTo(26);
            assertThat(DFHMDF_NAMED)
                    .as("only 11 carry a name label; the other 15 are unlabelled screen furniture "
                            + "with no symbolic-map item and therefore no payload member")
                    .isEqualTo(11);
            assertThat(DFHMDF_TOTAL - DFHMDF_NAMED).isEqualTo(15);
            assertThat(COUSR03_BMS_LINES)
                    .as("one mapset line per labelled field, in ascending map order")
                    .hasSize(DFHMDF_NAMED)
                    .isSorted();
        }

        @ParameterizedTest(name = "member {1} projects {0}")
        @MethodSource("com.vsergeychik.carddemo.user.dto.UserDeleteResponseTest#fieldTriples")
        @DisplayName("each member projects its xxxO item at its declared width")
        void eachMemberProjectsItsItem(String xxxO, String member, int width) {
            int index = XXXO_ITEMS.indexOf(xxxO);

            assertThat(index).as("%s is one of the eleven output items", xxxO).isNotNegative();
            assertThat(MAP_MEMBERS.get(index)).isEqualTo(member);
            assertThat(DECLARED_WIDTHS.get(index)).isEqualTo(width);
            assertThat(componentNames().indexOf(member))
                    .as("%s is declared in the same position the copybook declares %s", member, xxxO)
                    .isEqualTo(index);
            assertThat(componentTypes().get(index))
                    .as("every PIC X item projects as a String, never as a char array or a wrapper")
                    .isEqualTo(String.class);
        }

        @Test
        @DisplayName("the published COBOL names are the xxxO items, not the xxxI items")
        void publishedNamesAreTheOutputItems() {
            List<String> published = List.of(UserDeleteResponse.TRN_NAME_FIELD,
                    UserDeleteResponse.TITLE01_FIELD,
                    UserDeleteResponse.CUR_DATE_FIELD,
                    UserDeleteResponse.PGM_NAME_FIELD,
                    UserDeleteResponse.TITLE02_FIELD,
                    UserDeleteResponse.CUR_TIME_FIELD,
                    UserDeleteResponse.USR_ID_IN_FIELD,
                    UserDeleteResponse.F_NAME_FIELD,
                    UserDeleteResponse.L_NAME_FIELD,
                    UserDeleteResponse.USR_TYPE_FIELD,
                    UserDeleteResponse.ERR_MSG_FIELD);

            assertThat(published)
                    .as("this is the RESPONSE, so its field names end in O - the view COUSR03C:217 "
                            + "and :317 write - while UserDeleteRequest publishes the I names")
                    .containsExactlyElementsOf(XXXO_ITEMS);
            assertThat(published).allSatisfy(name -> assertThat(name).endsWith("O"));
            assertThat(published).doesNotContainAnyElementsOf(XXXI_ITEMS);
        }

        @Test
        @DisplayName("the published widths are 4, 40, 8, 8, 40, 8, 8, 20, 20, 1 and 78")
        void publishedWidths() {
            List<Integer> published = List.of(UserDeleteResponse.TRN_NAME_LENGTH,
                    UserDeleteResponse.TITLE01_LENGTH,
                    UserDeleteResponse.CUR_DATE_LENGTH,
                    UserDeleteResponse.PGM_NAME_LENGTH,
                    UserDeleteResponse.TITLE02_LENGTH,
                    UserDeleteResponse.CUR_TIME_LENGTH,
                    UserDeleteResponse.USR_ID_IN_LENGTH,
                    UserDeleteResponse.F_NAME_LENGTH,
                    UserDeleteResponse.L_NAME_LENGTH,
                    UserDeleteResponse.USR_TYPE_LENGTH,
                    UserDeleteResponse.ERR_MSG_LENGTH);

            assertThat(published).containsExactlyElementsOf(DECLARED_WIDTHS);
            assertThat(published.stream().mapToInt(Integer::intValue).sum())
                    .as("the eleven data items occupy 235 of the map's 324 bytes")
                    .isEqualTo(PAYLOAD_WIDTH_TOTAL);
        }

        @Test
        @DisplayName("TRAP 1 - the id member is usrIdIn, from USRIDIN, and it comes FIRST")
        void trapOneTheIdMemberIsUsrIdIn() {
            assertThat(componentNames())
                    .contains("usrIdIn")
                    .doesNotContain("userId", "usrId", "userid");
            assertThat(UserDeleteResponse.USR_ID_IN_FIELD).isEqualTo("USRIDINO");

            int idPosition = MAP_MEMBERS.indexOf("usrIdIn");
            assertThat(idPosition)
                    .as("USRIDIN is the seventh labelled DFHMDF, immediately after the header six")
                    .isEqualTo(6);
            assertThat(idPosition)
                    .as("the id precedes fName and lName, as on COUSR02 and unlike COUSR01")
                    .isLessThan(MAP_MEMBERS.indexOf("fName"))
                    .isLessThan(MAP_MEMBERS.indexOf("lName"));
        }

        @Test
        @DisplayName("TRAP 2 - CURTIMEO is X(8) here, never COSGN00's X(9)")
        void trapTwoCurTimeIsEight() {
            assertThat(UserDeleteResponse.CUR_TIME_LENGTH)
                    .as("app/cpy-bms/COUSR03.CPY:122 declares CURTIMEO PIC X(8), and "
                            + "app/bms/COUSR03.bms:70-74 declares LENGTH=8 at POS=(2,71). COSGN00 "
                            + "alone widens its time field to nine; this map does not")
                    .isEqualTo(8)
                    .isNotEqualTo(9);
            assertThat(FIXED_CURTIME)
                    .as("hh:mm:ss is exactly eight characters, which is why eight is enough")
                    .hasSize(UserDeleteResponse.CUR_TIME_LENGTH);
            assertThat(UserDeleteResponse.CUR_TIME_LENGTH)
                    .as("the date field alongside it is also eight - MM/DD/YY")
                    .isEqualTo(UserDeleteResponse.CUR_DATE_LENGTH);
        }

        @Test
        @DisplayName("TRAP 3 - ERRMSGO is 78 while WS-MESSAGE is 80, so a MOVE loses two characters")
        void trapThreeErrMsgIsSeventyEight() {
            assertThat(UserDeleteResponse.ERR_MSG_LENGTH)
                    .as("app/cpy-bms/COUSR03.CPY:152 declares ERRMSGO PIC X(78), matching "
                            + "app/bms/COUSR03.bms:142 LENGTH=78")
                    .isEqualTo(ERR_MSG_LENGTH)
                    .isEqualTo(78);
            assertThat(UserDeleteResponse.WS_MESSAGE_LENGTH)
                    .as("app/cbl/COUSR03C.cbl:38 declares WS-MESSAGE PIC X(80)")
                    .isEqualTo(WS_MESSAGE_LENGTH)
                    .isEqualTo(80);
            assertThat(UserDeleteResponse.WS_MESSAGE_LENGTH - UserDeleteResponse.ERR_MSG_LENGTH)
                    .as("MOVE WS-MESSAGE TO ERRMSGO at :217 discards exactly two characters")
                    .isEqualTo(TRUNCATED_CHARACTERS)
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("the screen identity literals are quoted from the program and the CSD")
        void screenIdentity() {
            assertThat(UserDeleteResponse.TRANSACTION_ID)
                    .as("WS-TRANID VALUE 'CU03' at app/cbl/COUSR03C.cbl:37")
                    .isEqualTo(TRANSACTION_ID)
                    .hasSize(UserDeleteResponse.TRN_NAME_LENGTH);
            assertThat(UserDeleteResponse.PROGRAM_NAME)
                    .as("WS-PGMNAME VALUE 'COUSR03C' at app/cbl/COUSR03C.cbl:36")
                    .isEqualTo(PROGRAM_NAME)
                    .hasSize(UserDeleteResponse.PGM_NAME_LENGTH);
            assertThat(UserDeleteResponse.MAP_NAME)
                    .as("MAP('COUSR3A') at app/cbl/COUSR03C.cbl:220")
                    .isEqualTo(MAP_NAME);
            assertThat(UserDeleteResponse.MAPSET_NAME)
                    .as("MAPSET('COUSR03') at app/cbl/COUSR03C.cbl:221")
                    .isEqualTo(MAPSET_NAME);
            assertThat(CSD_PROGRAM_LINE)
                    .as("PROGRAM(COUSR03C) sits on the line after DEFINE TRANSACTION(CU03)")
                    .isEqualTo(CSD_TRANSACTION_LINE + 1);
        }

        @Test
        @DisplayName("title01 and title02 carry the 40-character COTTL01Y literals")
        void titlesAreTheScreenTitles() {
            assertThat(ScreenTitles.CCDA_TITLE01)
                    .as("app/cpy/COTTL01Y.cpy:18-19, exactly 40 characters including its padding")
                    .hasSize(UserDeleteResponse.TITLE01_LENGTH)
                    .hasSize(ScreenTitles.TITLE_LENGTH)
                    .contains("AWS Mainframe Modernization");
            assertThat(ScreenTitles.CCDA_TITLE02)
                    .as("app/cpy/COTTL01Y.cpy:20-22, exactly 40 characters including its padding")
                    .hasSize(UserDeleteResponse.TITLE02_LENGTH)
                    .contains("CardDemo");

            UserDeleteResponse titled = UserDeleteResponse.empty()
                    .withTitle01(ScreenTitles.CCDA_TITLE01)
                    .withTitle02(ScreenTitles.CCDA_TITLE02);
            assertThat(titled.title01()).isEqualTo(ScreenTitles.CCDA_TITLE01);
            assertThat(titled.title02()).isEqualTo(ScreenTitles.CCDA_TITLE02);
        }

        @Test
        @DisplayName("the ScreenTitles X(40) and SystemMessages X(50) sign-offs are different things")
        void theThankYouTrap() {
            assertThat(ScreenTitles.CCDA_THANK_YOU)
                    .hasSize(ScreenTitles.TITLE_LENGTH)
                    .hasSize(UserDeleteResponse.TITLE01_LENGTH)
                    .contains("CCDA");
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU)
                    .hasSize(SystemMessages.MESSAGE_LENGTH)
                    .contains("CardDemo");
            assertThat(ScreenTitles.TITLE_LENGTH)
                    .as("40 against 50 - the widths alone make them non-interchangeable")
                    .isEqualTo(40)
                    .isNotEqualTo(SystemMessages.MESSAGE_LENGTH);
            assertThat(ScreenTitles.CCDA_THANK_YOU.strip())
                    .isNotEqualTo(SystemMessages.CCDA_MSG_THANK_YOU.strip());
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU.length())
                    .as("the X(50) message would not even fit TITLE01O's 40 bytes")
                    .isGreaterThan(UserDeleteResponse.TITLE01_LENGTH);
        }

        @Test
        @DisplayName("no control item - xxxL, xxxF, xxxA, xxxC, xxxP, xxxH, xxxV - is a member")
        void noControlItemIsAMember() {
            Set<String> lowerCaseComponents = new LinkedHashSet<>();
            componentNames().forEach(name -> lowerCaseComponents.add(name.toLowerCase(Locale.ROOT)));

            for (String label : DFHMDF_LABELS) {
                for (String suffix : CONTROL_SUFFIXES) {
                    assertThat(lowerCaseComponents)
                            .as("%s%s is metadata: a length, an attribute or a colour byte", label,
                                    suffix)
                            .doesNotContain((label + suffix).toLowerCase(Locale.ROOT));
                }
            }
            assertThat(lowerCaseComponents)
                    .as("nor is either FILLER exposed - the 12-byte TIOAPFX prefix or the "
                            + "per-field 3-byte span")
                    .doesNotContain("filler", "tioapfx");
        }
    }

    @Nested
    @DisplayName("Asymmetry #3 - eleven members because there is no password at all")
    class AbsentPassword {
        @ParameterizedTest(name = "no component named like \"{0}\"")
        @ValueSource(strings = {"passwd", "password", "pwd", "secret", "credential", "passphrase"})
        @DisplayName("no component spells a credential in any form")
        void noCredentialComponent(String token) {
            for (String component : componentNames()) {
                assertThat(component.toLowerCase(Locale.ROOT))
                        .as("grep -c 'PASSWD' app/cbl/COUSR03C.cbl returns 0 and the same grep over "
                                + "app/bms/COUSR03.bms returns 0, so no member may imply one")
                        .doesNotContain(token);
            }
        }

        @Test
        @DisplayName("no declared method spells a credential either")
        void noCredentialAccessor() {
            for (Method method : UserDeleteResponse.class.getDeclaredMethods()) {
                if (method.isSynthetic()) {
                    continue;
                }
                String name = method.getName().toLowerCase(Locale.ROOT);
                assertThat(CREDENTIAL_TOKENS)
                        .as("method %s must not name a credential", method.getName())
                        .noneMatch(name::contains);
            }
            for (java.lang.reflect.Field field : UserDeleteResponse.class.getDeclaredFields()) {
                String name = field.getName().toLowerCase(Locale.ROOT);
                assertThat(CREDENTIAL_TOKENS)
                        .as("field %s must not name a credential", field.getName())
                        .noneMatch(name::contains);
            }
        }

        @Test
        @DisplayName("no member of any name occupies the password's structural position or width")
        void noMemberSitsWhereThePasswordWould() {
            int usrTypePosition = MAP_MEMBERS.indexOf("usrType");
            int errMsgPosition = MAP_MEMBERS.indexOf("errMsg");

            assertThat(errMsgPosition)
                    .as("usrType and errMsg are ADJACENT here; on COUSR02 PASSWD lies between them")
                    .isEqualTo(usrTypePosition + 1);
            assertThat(DECLARED_WIDTHS.get(usrTypePosition)).isEqualTo(SEC_USR_TYPE_WIDTH);
            assertThat(DECLARED_WIDTHS.get(errMsgPosition)).isEqualTo(ERR_MSG_LENGTH);
            assertThat(DECLARED_WIDTHS.stream()
                    .filter(width -> width == ABSENT_PASSWORD_WIDTH)
                    .count())
                    .as("four X(8) fields - CURDATE, PGMNAME, CURTIME and USRIDIN - each with its "
                            + "own DFHMDF; none of them is a credential")
                    .isEqualTo(4);
        }

        @Test
        @DisplayName("proof 2 - the symbolic map's tail is shifted up by exactly one six-line group")
        void proofTwoTheCopybookTailShift() {
            assertThat(COUSR03_USRTYPEI_LINE)
                    .as("USRTYPEI is at COUSR03.CPY:78, where COUSR02.CPY has PASSWDI")
                    .isEqualTo(COUSR02_PASSWDI_LINE);
            assertThat(COUSR02_USRTYPEI_LINE - COUSR03_USRTYPEI_LINE)
                    .as("one field group is six copybook lines: xxxL, xxxF, FILLER REDEFINES, xxxA, "
                            + "FILLER X(4) and xxxI")
                    .isEqualTo(CPY_LINES_PER_FIELD_GROUP);
            assertThat(COUSR02_ERRMSGI_LINE - COUSR03_ERRMSGI_LINE)
                    .as("the same six-line shift carries through to ERRMSGI")
                    .isEqualTo(CPY_LINES_PER_FIELD_GROUP);
            assertThat(COUSR02_OUTPUT_GROUP_LINE - COUSR03_OUTPUT_GROUP_LINE)
                    .as("and to the output group: 01 COUSR3AO at 85 against 01 COUSR2AO at 91")
                    .isEqualTo(CPY_LINES_PER_FIELD_GROUP);
            assertThat(COUSR03_OUTPUT_GROUP_LINE)
                    .as("the output group opens on the line after the last xxxI item")
                    .isEqualTo(COUSR03_ERRMSGI_LINE + 1);
            assertThat(DFHMDF_NAMED)
                    .as("one field fewer than COUSR02 - eleven against twelve")
                    .isEqualTo(COUSR02_MAP_FIELD_COUNT - 1);
        }

        @Test
        @DisplayName("the count stays eleven; a twelfth would be a field with no DFHMDF behind it")
        void theCountStaysEleven() {
            assertThat(UserDeleteResponse.MAP_FIELD_COUNT).isEqualTo(DFHMDF_NAMED);
            assertThat(componentNames()).hasSize(COMPONENT_COUNT);
            assertThat(COMPONENT_COUNT)
                    .as("16 = 11 map + 4 navigation + 1 extension, with nothing unaccounted for")
                    .isEqualTo(DFHMDF_NAMED + NAVIGATION_MEMBERS.size() + 1);
        }
    }

    @Nested
    @DisplayName("Stored-record projection - four of CSUSR01Y's six items reach this screen")
    class StoredRecordProjection {
        @Test
        @DisplayName("the four projected widths match app/cpy/CSUSR01Y.cpy:17-23 exactly")
        void projectedWidthsMatchTheCopybook() {
            assertThat(UserDeleteResponse.USR_ID_IN_LENGTH)
                    .as("SEC-USR-ID PIC X(08) at CSUSR01Y.cpy:18 projects onto USRIDINO")
                    .isEqualTo(SEC_USR_ID_WIDTH)
                    .isEqualTo(SecUserRecord.SEC_USR_ID_LENGTH);
            assertThat(UserDeleteResponse.F_NAME_LENGTH)
                    .as("SEC-USR-FNAME PIC X(20) at CSUSR01Y.cpy:19 projects onto FNAMEO")
                    .isEqualTo(SEC_USR_FNAME_WIDTH)
                    .isEqualTo(SecUserRecord.SEC_USR_FNAME_LENGTH);
            assertThat(UserDeleteResponse.L_NAME_LENGTH)
                    .as("SEC-USR-LNAME PIC X(20) at CSUSR01Y.cpy:20 projects onto LNAMEO")
                    .isEqualTo(SEC_USR_LNAME_WIDTH)
                    .isEqualTo(SecUserRecord.SEC_USR_LNAME_LENGTH);
            assertThat(UserDeleteResponse.USR_TYPE_LENGTH)
                    .as("SEC-USR-TYPE PIC X(01) at CSUSR01Y.cpy:22 projects onto USRTYPEO")
                    .isEqualTo(SEC_USR_TYPE_WIDTH)
                    .isEqualTo(SecUserRecord.SEC_USR_TYPE_LENGTH);
        }

        @Test
        @DisplayName("the record still holds a password at offset 48; only the SCREEN omits it")
        void theRecordKeepsWhatTheScreenDropped() {
            assertThat(SecUserRecord.RECORD_LENGTH)
                    .as("SEC-USER-DATA is 80 bytes: 8 + 20 + 20 + 8 + 1 + 23")
                    .isEqualTo(SEC_USER_DATA_LENGTH)
                    .isEqualTo(SEC_USR_ID_WIDTH + SEC_USR_FNAME_WIDTH + SEC_USR_LNAME_WIDTH
                            + SEC_USR_PWD_WIDTH + SEC_USR_TYPE_WIDTH + SEC_USR_FILLER_WIDTH);
            assertThat(SecUserRecord.SEC_USR_ID_OFFSET).isZero();
            assertThat(SecUserRecord.SEC_USR_FNAME_OFFSET).isEqualTo(8);
            assertThat(SecUserRecord.SEC_USR_LNAME_OFFSET).isEqualTo(28);
            assertThat(SecUserRecord.SEC_USR_PWD_OFFSET)
                    .as("SEC-USR-PWD X(08) really is there, at offset 48 - it is simply never sent")
                    .isEqualTo(48);
            assertThat(SecUserRecord.SEC_USR_TYPE_OFFSET).isEqualTo(56);
            assertThat(SecUserRecord.SEC_USR_FILLER_OFFSET)
                    .as("the named SEC-USR-FILLER X(23) at 57 is likewise unprojected")
                    .isEqualTo(57);
            assertThat(SecUserRecord.SEC_USR_PWD_LENGTH).isEqualTo(SEC_USR_PWD_WIDTH);
            assertThat(SecUserRecord.SEC_USR_FILLER_LENGTH).isEqualTo(SEC_USR_FILLER_WIDTH);

            int headerFields = 6;
            int projectedRecordItems = 4;
            int messageLines = 1;
            assertThat(headerFields + projectedRecordItems + messageLines)
                    .as("6 + 4 + 1 = 11, so every screen field is accounted for and none of them is "
                            + "the record's password or its filler")
                    .isEqualTo(DFHMDF_NAMED);
        }

        @Test
        @DisplayName("the stored record's own values reach the screen unchanged, at screen width")
        void storedValuesReachTheScreen() {
            SecUserRecord stored = SecUserRecord.of("USER0001",
                    "John",
                    "Doe",
                    "PASSWORD",
                    "U",
                    MAP_CHARSET);

            UserDeleteResponse response = UserDeleteResponse.empty()
                    .withUsrIdIn(stored.secUsrId())
                    .withFName(stored.secUsrFname())
                    .withLName(stored.secUsrLname())
                    .withUsrType(stored.secUsrType());

            assertThat(response.usrIdIn()).isEqualTo("USER0001").hasSize(SEC_USR_ID_WIDTH);
            assertThat(response.fName())
                    .as("SEC-USR-FNAME arrives at its full PIC X(20) width, trailing spaces included")
                    .isEqualTo(codec().movePicX("John", SEC_USR_FNAME_WIDTH))
                    .hasSize(SEC_USR_FNAME_WIDTH);
            assertThat(response.lName())
                    .isEqualTo(codec().movePicX("Doe", SEC_USR_LNAME_WIDTH))
                    .hasSize(SEC_USR_LNAME_WIDTH);
            assertThat(response.usrType()).isEqualTo("U").hasSize(SEC_USR_TYPE_WIDTH);

            assertThat(stored.secUsrPwd()).isEqualTo("PASSWORD").hasSize(SEC_USR_PWD_WIDTH);
            byte[] image = SecUserRecord.encode(stored, MAP_CHARSET);
            assertThat(image).hasSize(SEC_USER_DATA_LENGTH);
            assertThat(new String(image, MAP_CHARSET)).contains("PASSWORD");

            String serialisedFields = String.join("\u0001",
                    response.trnName(), response.title01(), response.curDate(), response.pgmName(),
                    response.title02(), response.curTime(), response.usrIdIn(), response.fName(),
                    response.lName(), response.usrType(), response.errMsg());
            assertThat(serialisedFields)
                    .as("no screen field carries the stored password onto the wire")
                    .doesNotContain("PASSWORD");
        }

        @Test
        @DisplayName("the three display-only fields start unpainted, as the :97 map clear leaves them")
        void displayOnlyFieldsStartUnpainted() {
            UserDeleteResponse blanked = UserDeleteResponse.empty();

            assertThat(blanked.fName())
                    .isEqualTo(ScreenFieldImage.unpainted(SEC_USR_FNAME_WIDTH));
            assertThat(blanked.lName())
                    .isEqualTo(ScreenFieldImage.unpainted(SEC_USR_LNAME_WIDTH));
            assertThat(blanked.usrType())
                    .isEqualTo(ScreenFieldImage.unpainted(SEC_USR_TYPE_WIDTH));

            assertThat(blanked.usrIdIn())
                    .isEqualTo(ScreenFieldImage.unpainted(SEC_USR_ID_WIDTH));
        }
    }

    @Nested
    @DisplayName("Group-level REDEFINES - COUSR3AO over COUSR3AI, gate G34")
    class SymbolicMapOverlay {
        @Test
        @DisplayName("the geometry tiles 324 bytes in both views: 12 + 11 x 7 + 235")
        void theGeometryIsTheCopybooks() {
            assertThat(INPUT_FIELD_PREFIX_LENGTH)
                    .as("input view: xxxL 2 + xxxF 1 + FILLER X(4) 4")
                    .isEqualTo(7);
            assertThat(OUTPUT_FIELD_PREFIX_LENGTH)
                    .as("output view: FILLER X(3) 3 + xxxC + xxxP + xxxH + xxxV")
                    .isEqualTo(7)
                    .isEqualTo(INPUT_FIELD_PREFIX_LENGTH);
            assertThat(DECLARED_WIDTHS.stream().mapToInt(Integer::intValue).sum())
                    .isEqualTo(PAYLOAD_WIDTH_TOTAL);
            assertThat(TIOAPFX_PREFIX_LENGTH + DFHMDF_NAMED * INPUT_FIELD_PREFIX_LENGTH
                    + PAYLOAD_WIDTH_TOTAL)
                    .as("12 + 77 + 235 = 324")
                    .isEqualTo(SYMBOLIC_MAP_LENGTH);
            assertThat(INPUT_VIEW_LAYOUT.recordLength())
                    .isEqualTo(SYMBOLIC_MAP_LENGTH)
                    .isEqualTo(OUTPUT_VIEW_LAYOUT.recordLength());
            assertThat(INPUT_VIEW_LAYOUT.storageSpans().stream()
                    .mapToInt(FixedWidthRecord.FieldSpan::length).sum())
                    .as("the input view's storage spans, overlays excluded, tile the record")
                    .isEqualTo(SYMBOLIC_MAP_LENGTH);
            assertThat(OUTPUT_VIEW_LAYOUT.storageSpans().stream()
                    .mapToInt(FixedWidthRecord.FieldSpan::length).sum())
                    .as("and so do the output view's")
                    .isEqualTo(SYMBOLIC_MAP_LENGTH);
        }

        @Test
        @DisplayName("324 is fifteen bytes short of COUSR02's 339 - the 8-byte password plus its 7-byte prefix")
        void theGeometryIsFifteenBytesShorterThanCousr02() {
            assertThat(COUSR02_SYMBOLIC_MAP_LENGTH - SYMBOLIC_MAP_LENGTH)
                    .as("339 - 324 = 15")
                    .isEqualTo(15)
                    .isEqualTo(ABSENT_PASSWORD_WIDTH + INPUT_FIELD_PREFIX_LENGTH);
            assertThat(COUSR02_SYMBOLIC_MAP_LENGTH)
                    .as("COUSR02 re-derived from its own field count: 12 + 12 x 7 + 243")
                    .isEqualTo(TIOAPFX_PREFIX_LENGTH
                            + COUSR02_MAP_FIELD_COUNT * INPUT_FIELD_PREFIX_LENGTH
                            + PAYLOAD_WIDTH_TOTAL + ABSENT_PASSWORD_WIDTH);
            assertThat(SYMBOLIC_MAP_LENGTH).isLessThan(COUSR02_SYMBOLIC_MAP_LENGTH);
        }

        @Test
        @DisplayName("this copybook holds 12 REDEFINES: 11 per-field plus the one group-level overlay")
        void theRedefinesAccounting() {
            assertThat(COUSR03_PER_FIELD_REDEFINES + COUSR03_GROUP_REDEFINES)
                    .isEqualTo(COUSR03_REDEFINES_COUNT)
                    .isEqualTo(12);
            assertThat(COUSR03_PER_FIELD_REDEFINES)
                    .as("one 02 FILLER REDEFINES xxxF per field; UserDeleteRequestTest owns those")
                    .isEqualTo(DFHMDF_NAMED);
            assertThat(COUSR03_GROUP_REDEFINES)
                    .as("01 COUSR3AO REDEFINES COUSR3AI at COUSR03.CPY:85 - this file's subject")
                    .isEqualTo(1);
            assertThat(INPUT_VIEW_LAYOUT.redefinitions())
                    .as("the modelled input view carries the eleven per-field overlays")
                    .hasSize(COUSR03_PER_FIELD_REDEFINES);
            assertThat(COUSR03_REDEFINES_COUNT).isEqualTo(DFHMDF_NAMED + COUSR03_GROUP_REDEFINES);
        }

        @ParameterizedTest(name = "{0}O overlays {0}I at the same offset and width")
        @ValueSource(strings = {"TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02", "CURTIME",
            "USRIDIN", "FNAME", "LNAME", "USRTYPE", "ERRMSG"})
        @DisplayName("every data item sits at the identical offset in both views - zero drift")
        void everyDataItemAlignsAcrossTheViews(String screenField) {
            int index = DFHMDF_LABELS.indexOf(screenField);
            FixedWidthRecord.FieldSpan input = INPUT_VIEW_LAYOUT.span(XXXI_ITEMS.get(index));
            FixedWidthRecord.FieldSpan output = OUTPUT_VIEW_LAYOUT.span(XXXO_ITEMS.get(index));

            assertThat(output.offset())
                    .as("%sO starts exactly where %sI starts, because both per-field prefixes are 7",
                            screenField, screenField)
                    .isEqualTo(input.offset());
            assertThat(output.length())
                    .isEqualTo(input.length())
                    .isEqualTo(DECLARED_WIDTHS.get(index));
            assertThat(output.endOffsetExclusive()).isEqualTo(input.endOffsetExclusive());
            assertThat(input.offset())
                    .as("and the offset is the running total the copybook implies")
                    .isEqualTo(expectedDataOffset(index));
        }

        private int expectedDataOffset(int index) {
            int offset = TIOAPFX_PREFIX_LENGTH;
            for (int earlier = 0; earlier < index; earlier++) {
                offset += INPUT_FIELD_PREFIX_LENGTH + DECLARED_WIDTHS.get(earlier);
            }
            return offset + INPUT_FIELD_PREFIX_LENGTH;
        }

        @ParameterizedTest(name = "{0}: written through I, read through O, and back")
        @CsvSource({"USRIDIN, USER0001", "FNAME, Jane", "ERRMSG, User ID NOT found..."})
        @DisplayName("a genuine round trip: one storage area addressed through both views")
        void theOverlayRoundTripsThroughOneStorageArea(String screenField, String value) {
            int index = DFHMDF_LABELS.indexOf(screenField);
            int width = DECLARED_WIDTHS.get(index);
            FixedWidthRecord.FieldSpan input = INPUT_VIEW_LAYOUT.span(XXXI_ITEMS.get(index));
            FixedWidthRecord.FieldSpan output = OUTPUT_VIEW_LAYOUT.span(XXXO_ITEMS.get(index));

            FixedWidthRecord storage = new FixedWidthRecord(SYMBOLIC_MAP_LENGTH, MAP_CHARSET);
            String image = codec().movePicX(value, width);

            storage.writeSpan(input, image);
            assertThat(storage.readSpan(output))
                    .as("what RECEIVE MAP put in %sI is what SEND MAP reads from %sO", screenField,
                            screenField)
                    .isEqualTo(image);
            assertThat(storage.readSpanBytes(output)).isEqualTo(storage.readSpanBytes(input));

            String replacement = codec().movePicX("Z".repeat(Math.min(3, width)), width);
            storage.writeSpan(output, replacement);
            assertThat(storage.readSpan(input))
                    .as("and back the other way - one span, two names for it")
                    .isEqualTo(replacement);
            assertThat(storage.toByteArray()).hasSize(SYMBOLIC_MAP_LENGTH);
        }

        @Test
        @DisplayName("ERRMSGC - the colour item COUSR03C:317 writes - shares the storage the I view sees")
        void theErrMsgColourItemSharesTheStorage() {
            FixedWidthRecord storage = new FixedWidthRecord(SYMBOLIC_MAP_LENGTH, MAP_CHARSET);
            FixedWidthRecord.FieldSpan errMsgColour = OUTPUT_VIEW_LAYOUT.span("ERRMSGC");
            FixedWidthRecord.FieldSpan errMsgFlag = INPUT_VIEW_LAYOUT.span("ERRMSGF");
            FixedWidthRecord.FieldSpan errMsgAttribute = INPUT_VIEW_LAYOUT.span("ERRMSGA");

            assertThat(errMsgColour.length())
                    .as("a BMS colour is a single attribute byte")
                    .isEqualTo(ATTRIBUTE_ITEM_LENGTH);
            assertThat(errMsgColour.offset())
                    .as("ERRMSGC is the FIRST of the output view's four attribute items, so it lands "
                            + "three bytes into the field's prefix, one byte past the input view's "
                            + "xxxL halfword - ERRMSGF at 2, ERRMSGC at 3")
                    .isEqualTo(errMsgFlag.offset() + 1);
            assertThat(errMsgAttribute.offset())
                    .as("ERRMSGA is the input view's own overlay of ERRMSGF, not of ERRMSGC")
                    .isEqualTo(errMsgFlag.offset());

            byte[] green = {BmsAttributes.DFHGREEN};
            storage.writeSpanBytes(errMsgColour, green);
            assertThat(storage.readSpanBytes(errMsgColour)).isEqualTo(green);
            assertThat(storage.toByteArray()[errMsgColour.offset()])
                    .as("the colour byte lands at the absolute offset the copybook gives ERRMSGC")
                    .isEqualTo(BmsAttributes.DFHGREEN);

            byte[] neutral = {BmsAttributes.DFHNEUTR};
            storage.writeSpanBytes(errMsgColour, neutral);
            assertThat(storage.readSpanBytes(errMsgColour))
                    .as("MOVE DFHNEUTR TO ERRMSGC at :285 writes the same one byte")
                    .isEqualTo(neutral);

            FixedWidthRecord.FieldSpan errMsgData = OUTPUT_VIEW_LAYOUT.span("ERRMSGO");
            String text = errMsgImage(wsMessageImage(MSG_USER_ID_NOT_FOUND));
            storage.writeSpan(errMsgData, text);
            storage.writeSpanBytes(errMsgColour, green);
            assertThat(storage.readSpan(errMsgData))
                    .as("the colour item and the data item are adjacent, never overlapping")
                    .isEqualTo(text);
            assertThat(storage.readSpan(INPUT_VIEW_LAYOUT.span("ERRMSGI")))
                    .as("and the input view reads the same 78 bytes of text")
                    .isEqualTo(text);
        }

        @Test
        @DisplayName("the xxxC/xxxP/xxxH/xxxV quartet is EXTATT=YES metadata, absent from the payload")
        void theAttributeQuartetIsMetadata() {
            for (String label : DFHMDF_LABELS) {
                for (String suffix : List.of("C", "P", "H", "V")) {
                    assertThat(OUTPUT_VIEW_LAYOUT.hasSpan(label + suffix))
                            .as("%s%s exists in the copybook", label, suffix)
                            .isTrue();
                    assertThat(OUTPUT_VIEW_LAYOUT.span(label + suffix).length())
                            .isEqualTo(ATTRIBUTE_ITEM_LENGTH);
                }
                assertThat(INPUT_VIEW_LAYOUT.hasSpan(label + "F")).isTrue();
                assertThat(INPUT_VIEW_LAYOUT.hasSpan(label + "A")).isTrue();
            }

            List<String> lowerCaseComponents =
                    componentNames().stream().map(name -> name.toLowerCase(Locale.ROOT)).toList();
            for (String label : DFHMDF_LABELS) {
                for (String suffix : CONTROL_SUFFIXES) {
                    assertThat(lowerCaseComponents)
                            .doesNotContain((label + suffix).toLowerCase(Locale.ROOT));
                }
            }

            assertThat(OUTPUT_VIEW_LAYOUT.storageSpans())
                    .as("12-byte prefix + 11 x 3-byte fillers + 44 attribute bytes + 235 data bytes")
                    .isNotEmpty();
            assertThat(TIOAPFX_PREFIX_LENGTH
                    + DFHMDF_NAMED * OUTPUT_FILLER_LENGTH
                    + DFHMDF_NAMED * OUTPUT_ATTRIBUTE_ITEM_COUNT * ATTRIBUTE_ITEM_LENGTH
                    + PAYLOAD_WIDTH_TOTAL)
                    .as("12 + 33 + 44 + 235 = 324, so nothing in the output view is unaccounted for")
                    .isEqualTo(SYMBOLIC_MAP_LENGTH);
        }

        @Test
        @DisplayName("ERRMSG's map-declared colour is RED; DFHGREEN at :317 is a success-path override")
        void errMsgDefaultsToRed() {
            assertThat(BmsAttributes.DFHRED)
                    .as("the extended-colour byte the mapset's COLOR=RED resolves to")
                    .isEqualTo((byte) 0xF2);
            assertThat(BmsAttributes.DFHGREEN)
                    .as("MOVE DFHGREEN TO ERRMSGC at app/cbl/COUSR03C.cbl:317")
                    .isEqualTo((byte) 0xF4)
                    .isNotEqualTo(BmsAttributes.DFHRED);
            assertThat(BmsAttributes.DFHNEUTR)
                    .as("MOVE DFHNEUTR TO ERRMSGC at app/cbl/COUSR03C.cbl:285")
                    .isEqualTo((byte) 0xF7)
                    .isNotEqualTo(BmsAttributes.DFHGREEN);
            assertThat(BmsAttributes.COLOUR_MNEMONICS)
                    .as("all three are real DFHBMSCA colour mnemonics, reproduced from IBM's "
                            + "documentation because the copybook is absent from this repository")
                    .containsKeys(BmsAttributes.DFHRED, BmsAttributes.DFHGREEN,
                            BmsAttributes.DFHNEUTR);

            FieldAttributeSetter.FieldHighlight highlight = FieldAttributeSetter.resolve(
                    FieldAttributeSetter.FieldValidationState.BLANK, true, "ERRMSG", MAP_NAME);
            assertThat(highlight.colourItemAssigned()).isTrue();
            assertThat(highlight.colourItemValue()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(highlight.colourItemName())
                    .as("CSSETATY writes the C item, which is precisely the item :317 writes")
                    .isEqualTo("ERRMSGC");
            assertThat(highlight.outputItemName()).isEqualTo("ERRMSGO");
            assertThat(highlight.outputItemValue()).isEqualTo(FieldAttributeSetter.ASTERISK);
            assertThat(highlight.outputMapGroupName())
                    .as("the seven-character map name COUSR3A plus the output suffix - the group "
                            + "COUSR03C:317 qualifies ERRMSGC with")
                    .isEqualTo("COUSR3AO");
        }

        @Test
        @DisplayName("no highlight is applied on first entry - CSSETATY acts under REENTER only")
        void noHighlightOnFirstEntry() {
            FieldAttributeSetter.FieldHighlight enterState = FieldAttributeSetter.resolve(
                    FieldAttributeSetter.FieldValidationState.BLANK, false, "ERRMSG", MAP_NAME);

            assertThat(enterState.untouched())
                    .as("app/cpy/CSSETATY.cpy tests the re-entry state before touching a field")
                    .isTrue();
            assertThat(enterState.colourItemAssigned()).isFalse();
            assertThat(enterState.outputItemAssigned()).isFalse();

            FieldAttributeSetter.FieldHighlight validUnderReenter = FieldAttributeSetter.resolve(
                    FieldAttributeSetter.FieldValidationState.OK, true, "ERRMSG", MAP_NAME);
            assertThat(validUnderReenter.untouched())
                    .as("and a field that passed validation is left alone even under re-entry")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("The message surface - five outcomes, one of them with the wrong verb")
    class MessageSurface {
        @ParameterizedTest(name = "\"{0}\" fits WS-MESSAGE and ERRMSGO and round-trips")
        @MethodSource("com.vsergeychik.carddemo.user.dto.UserDeleteResponseTest#everyMessageText")
        @DisplayName("every outcome text fits both widths and survives the narrowing unchanged")
        void everyOutcomeTextFits(String text, int cobolLine) {
            String wsMessage = wsMessageImage(text);
            String errMsg = errMsgImage(wsMessage);

            assertThat(wsMessage)
                    .as("MOVE '%s' TO WS-MESSAGE at app/cbl/COUSR03C.cbl:%d - a PIC X(80) receiver, "
                            + "so the text is padded on the right", text, cobolLine)
                    .hasSize(WS_MESSAGE_LENGTH)
                    .startsWith(text);
            assertThat(errMsg)
                    .as("MOVE WS-MESSAGE TO ERRMSGO at :217 - a PIC X(78) receiver")
                    .hasSize(ERR_MSG_LENGTH)
                    .startsWith(text);
            assertThat(text.length())
                    .as("every one of these texts is short enough that the two discarded characters "
                            + "are padding, so no message is visibly cut on screen")
                    .isLessThanOrEqualTo(ERR_MSG_LENGTH);

            UserDeleteResponse response = UserDeleteResponse.empty().withErrMsg(errMsg);
            assertThat(response.errMsg()).isEqualTo(errMsg).hasSize(ERR_MSG_LENGTH);
        }

        @Test
        @DisplayName("the narrowing really does discard two characters when they are not padding")
        void theNarrowingDiscardsTwoRealCharacters() {
            String eighty = "A".repeat(WS_MESSAGE_LENGTH - 2) + "YZ";
            assertThat(eighty).hasSize(WS_MESSAGE_LENGTH).endsWith("YZ");

            String wsMessage = wsMessageImage(eighty);
            assertThat(wsMessage).isEqualTo(eighty).hasSize(WS_MESSAGE_LENGTH);

            String errMsg = errMsgImage(wsMessage);
            assertThat(errMsg)
                    .as("COBOL fills a PIC X receiver from the LEFT and discards the overflow, so "
                            + "the surviving characters are the leading 78 - never the trailing 78")
                    .hasSize(ERR_MSG_LENGTH)
                    .isEqualTo("A".repeat(WS_MESSAGE_LENGTH - 2))
                    .doesNotEndWith("YZ")
                    .doesNotContain("Y")
                    .doesNotContain("Z");
            assertThat(wsMessage.length() - errMsg.length()).isEqualTo(TRUNCATED_CHARACTERS);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> UserDeleteResponse.empty().withErrMsg(eighty))
                    .withMessageContaining("ERRMSGO")
                    .withMessageContaining("movePicX");
            assertThat(UserDeleteResponse.empty().withErrMsg(errMsg).errMsg()).isEqualTo(errMsg);
        }

        @Test
        @DisplayName("the single blank-field arm - this screen has one where its siblings have five")
        void theSingleBlankFieldArm() {
            String errMsg = errMsgImage(wsMessageImage(MSG_USER_ID_EMPTY));

            assertThat(MSG_USER_ID_EMPTY)
                    .as("verbatim from app/cbl/COUSR03C.cbl:179, ellipsis included")
                    .isEqualTo("User ID can NOT be empty...")
                    .endsWith("...");
            assertThat(errMsg).startsWith(MSG_USER_ID_EMPTY).hasSize(ERR_MSG_LENGTH);
            assertThat(UserDeleteResponse.empty().withErrMsg(errMsg).errMsg()).isEqualTo(errMsg);

            assertThat(UserDeleteResponse.empty()
                            .withErrMsg(" ".repeat(ERR_MSG_LENGTH)).errMsg())
                    .as("MOVE SPACES TO WS-MESSAGE / ERRMSGO OF COUSR3AO at :88")
                    .isEqualTo(" ".repeat(ERR_MSG_LENGTH));
            assertThat(UserDeleteResponse.empty().errMsg())
                    .as("and the map before :88 has run - MOVE LOW-VALUES TO COUSR3AO at :97")
                    .isEqualTo(ScreenFieldImage.unpainted(ERR_MSG_LENGTH));
        }

        @Test
        @DisplayName("the lookup-failure arm at :296, preceded by the RESP/REAS display at :294")
        void theLookupFailureArm() {
            String errMsg = errMsgImage(wsMessageImage(MSG_UNABLE_TO_LOOKUP_USER));

            assertThat(MSG_UNABLE_TO_LOOKUP_USER)
                    .as("verbatim from app/cbl/COUSR03C.cbl:296. The DISPLAY 'RESP:' WS-RESP-CD "
                            + "'REAS:' WS-REAS-CD at :294 goes to the job log, never to ERRMSGO, so "
                            + "no response code leaks onto the screen")
                    .isEqualTo("Unable to lookup User...");
            assertThat(errMsg)
                    .startsWith(MSG_UNABLE_TO_LOOKUP_USER)
                    .doesNotContain("RESP:")
                    .doesNotContain("REAS:");
            assertThat(UserDeleteResponse.empty().withErrMsg(errMsg).errMsg()).hasSize(ERR_MSG_LENGTH);
        }

        @Test
        @DisplayName("the not-found arm at :325 - and the identical text on the read path at :289")
        void theNotFoundArm() {
            String errMsg = errMsgImage(wsMessageImage(MSG_USER_ID_NOT_FOUND));

            assertThat(MSG_USER_ID_NOT_FOUND)
                    .as("verbatim from app/cbl/COUSR03C.cbl:325, the DFHRESP(NOTFND) arm of the "
                            + "delete; the read path at :289 sets the same text, which is why one "
                            + "constant serves both")
                    .isEqualTo("User ID NOT found...");
            assertThat(errMsg).startsWith(MSG_USER_ID_NOT_FOUND).hasSize(ERR_MSG_LENGTH);
            assertThat(UserDeleteResponse.empty().withErrMsg(errMsg).errMsg()).isEqualTo(errMsg);
        }

        @Test
        @DisplayName("the confirmation prompt at :283 pairs with DFHNEUTR, not with an error colour")
        void theConfirmationPrompt() {
            String errMsg = errMsgImage(wsMessageImage(MSG_PRESS_PF5));

            assertThat(MSG_PRESS_PF5)
                    .as("verbatim from app/cbl/COUSR03C.cbl:283 - note the SPACED ellipsis, which "
                            + "differs from the unspaced ellipses of the error texts")
                    .isEqualTo("Press PF5 key to delete this user ...")
                    .endsWith(" ...");
            assertThat(errMsg).startsWith(MSG_PRESS_PF5).hasSize(ERR_MSG_LENGTH);
            assertThat(BmsAttributes.DFHNEUTR).isNotEqualTo(BmsAttributes.DFHRED);
        }

        @ParameterizedTest(name = "id \"{0}\" contributes \"{1}\", giving a {2}-character message")
        @CsvSource({"USER0001, USER0001, 34", "'USER1   ', USER1, 31", "'A       ', A, 27"})
        @DisplayName("the success text is composed by STRING, and DELIMITED BY SPACE makes it variable")
        void theSuccessTextIsComposed(String secUsrId, String contributed, int composedLength) {
            assertThat(secUsrId).hasSize(SEC_USR_ID_WIDTH);
            assertThat(delimitedBySpace(secUsrId)).isEqualTo(contributed);

            String wsMessage = successMessageImage(secUsrId);
            String expected = SUCCESS_PREFIX + contributed + SUCCESS_SUFFIX;

            assertThat(expected).hasSize(composedLength);
            assertThat(wsMessage)
                    .as("the composed sentence, padded to PIC X(80)")
                    .hasSize(WS_MESSAGE_LENGTH)
                    .startsWith(expected);
            assertThat(wsMessage.substring(composedLength))
                    .as("INITIALIZE-ALL-FIELDS at :349-356 and MOVE SPACES at :316 both blank "
                            + "WS-MESSAGE before the STRING runs, so the residue is spaces and never "
                            + "a fragment of the previous message")
                    .isEqualTo(" ".repeat(WS_MESSAGE_LENGTH - composedLength));

            String errMsg = errMsgImage(wsMessage);
            assertThat(errMsg).hasSize(ERR_MSG_LENGTH).startsWith(expected);
            assertThat(errMsg)
                    .as("the id survives the narrowing - it is nowhere near the truncation point")
                    .contains(contributed);
            assertThat(UserDeleteResponse.empty().withErrMsg(errMsg).errMsg()).isEqualTo(errMsg);
        }

        @Test
        @DisplayName("INITIALIZE-ALL-FIELDS does not clear SEC-USR-ID, so the id is still there at :318")
        void initialiseDoesNotClearTheStoredId() {
            SecUserRecord stored = SecUserRecord.of("USER0007", "Ada", "Lovelace", "SECRET01", "A",
                    MAP_CHARSET);

            UserDeleteResponse afterInitialise = UserDeleteResponse.empty();
            assertThat(afterInitialise.usrIdIn())
                    .as("the screen's id field carries nothing on a fresh map - the LOW-VALUES image "
                            + "MOVE LOW-VALUES TO COUSR3AO (:97) leaves. INITIALIZE-ALL-FIELDS at "
                            + ":351-356 moves SPACES and is exercised through the controller")
                    .isEqualTo(ScreenFieldImage.unpainted(SEC_USR_ID_WIDTH));
            assertThat(ScreenFieldImage.isUnpainted(afterInitialise.fName())).isTrue();
            assertThat(ScreenFieldImage.isUnpainted(afterInitialise.lName())).isTrue();
            assertThat(ScreenFieldImage.isUnpainted(afterInitialise.usrType())).isTrue();

            String errMsg = errMsgImage(successMessageImage(stored.secUsrId()));
            UserDeleteResponse sent = afterInitialise.withErrMsg(errMsg);

            assertThat(sent.errMsg())
                    .as("the id reaches the screen through the MESSAGE even though the id FIELD is "
                            + "blank - the two travel by different routes")
                    .contains("USER0007")
                    .startsWith("User USER0007 has been deleted ...");
            assertThat(sent.usrIdIn())
                    .as("INITIALIZE-ALL-FIELDS was not performed on this composition, so the id field is "
                            + "still the LOW-VALUES image a fresh map carries - the point of the test is "
                            + "that the id reached the MESSAGE, not the field")
                    .isEqualTo(ScreenFieldImage.unpainted(SEC_USR_ID_WIDTH));
            assertThat(sent.errMsg())
                    .as("and the stored password is not in the message either")
                    .doesNotContain("SECRET01");
        }

        @Test
        @DisplayName("PRESERVED DEFECT - the delete-failure arm says 'Update', and it stays that way")
        void thePreservedWrongVerbDefect() {
            assertThat(MSG_UNABLE_TO_UPDATE_USER)
                    .as("VERBATIM from app/cbl/COUSR03C.cbl:332 - wrong verb included")
                    .isEqualTo("Unable to Update User...")
                    .contains("Update")
                    .doesNotContain("Delete")
                    .doesNotContain("delete");

            String wsMessage = wsMessageImage(MSG_UNABLE_TO_UPDATE_USER);
            String errMsg = errMsgImage(wsMessage);

            assertThat(wsMessage).hasSize(WS_MESSAGE_LENGTH).startsWith(MSG_UNABLE_TO_UPDATE_USER);
            assertThat(errMsg).hasSize(ERR_MSG_LENGTH).startsWith(MSG_UNABLE_TO_UPDATE_USER);

            UserDeleteResponse response = UserDeleteResponse.empty().withErrMsg(errMsg);
            assertThat(response.errMsg())
                    .as("the wrong verb reaches the payload unaltered")
                    .startsWith("Unable to Update User...");

            assertThat(MSG_UNABLE_TO_LOOKUP_USER)
                    .isNotEqualTo(MSG_UNABLE_TO_UPDATE_USER)
                    .contains("lookup");
        }

        @Test
        @DisplayName("all five texts are distinct, so no arm can be mistaken for another")
        void theFiveTextsAreDistinct() {
            List<String> texts = List.of(MSG_USER_ID_EMPTY,
                    MSG_PRESS_PF5,
                    MSG_UNABLE_TO_LOOKUP_USER,
                    MSG_USER_ID_NOT_FOUND,
                    MSG_UNABLE_TO_UPDATE_USER);

            assertThat(texts).doesNotHaveDuplicates().hasSize(5);
            assertThat(texts).allSatisfy(text -> assertThat(text)
                    .as("every text fits ERRMSGO without being cut")
                    .hasSizeLessThanOrEqualTo(ERR_MSG_LENGTH));
            assertThat(texts).allSatisfy(text -> assertThat(errMsgImage(wsMessageImage(text)))
                    .hasSize(ERR_MSG_LENGTH));
        }
    }

    @Nested
    @DisplayName("XCTL becomes response fields - gates G40 and G37")
    class NavigationAndXctl {
        @Test
        @DisplayName("nextProgram, nextMapset and nextMap replace the XCTL at :204-207")
        void theThreeNavigationFields() {
            assertThat(componentNames())
                    .contains("nextProgram", "nextMapset", "nextMap");
            assertThat(UserDeleteResponse.NEXT_PROGRAM_FIELD)
                    .as("the XCTL operand is CDEMO-TO-PROGRAM, app/cpy/COCOM01Y.cpy:24")
                    .isEqualTo(NavigationContext.TO_PROGRAM_FIELD)
                    .isEqualTo("CDEMO-TO-PROGRAM");
            assertThat(UserDeleteResponse.NEXT_MAPSET_FIELD).isEqualTo("CDEMO-LAST-MAPSET");
            assertThat(UserDeleteResponse.NEXT_MAP_FIELD).isEqualTo("CDEMO-LAST-MAP");
        }

        @Test
        @DisplayName("nextMap and nextMapset are X(7), and the real values are exactly seven long")
        void theMapWidthsAreSeven() {
            assertThat(UserDeleteResponse.NEXT_MAP_LENGTH)
                    .as("CDEMO-LAST-MAP PIC X(7) at app/cpy/COCOM01Y.cpy:43")
                    .isEqualTo(LAST_MAP_WIDTH)
                    .isEqualTo(NavigationContext.LAST_MAP_LENGTH)
                    .isEqualTo(7);
            assertThat(UserDeleteResponse.NEXT_MAPSET_LENGTH)
                    .as("CDEMO-LAST-MAPSET PIC X(7) at app/cpy/COCOM01Y.cpy:44")
                    .isEqualTo(LAST_MAPSET_WIDTH)
                    .isEqualTo(7);
            assertThat(UserDeleteResponse.NEXT_PROGRAM_LENGTH)
                    .as("CDEMO-TO-PROGRAM PIC X(08) - eight, because a program name is eight")
                    .isEqualTo(TO_PROGRAM_WIDTH)
                    .isEqualTo(8);

            assertThat(MAP_NAME).hasSize(LAST_MAP_WIDTH);
            assertThat(MAPSET_NAME).hasSize(LAST_MAPSET_WIDTH);
            assertThat(PROGRAM_NAME).hasSize(TO_PROGRAM_WIDTH);

            UserDeleteResponse routed = UserDeleteResponse.empty()
                    .withNextMap(MAP_NAME)
                    .withNextMapset(MAPSET_NAME);
            assertThat(routed.nextMap()).isEqualTo("COUSR3A").hasSize(7);
            assertThat(routed.nextMapset()).isEqualTo("COUSR03").hasSize(7);
        }

        @ParameterizedTest(name = "an unset CDEMO-TO-PROGRAM ({0}) routes to COSGN00C")
        @MethodSource("com.vsergeychik.carddemo.user.dto.UserDeleteResponseTest#unsetReturnTargets")
        @DisplayName("PF3 branch A - LOW-VALUES and SPACES both take the COSGN00C default")
        void pf3DefaultsWhenNoTargetWasSupplied(String description, String suppliedTarget) {
            boolean unset = suppliedTarget.isBlank()
                    || suppliedTarget.chars().allMatch(character -> character == 0);
            assertThat(unset).as("%s satisfies the source predicate", description).isTrue();

            String resolved = unset ? PF3_DEFAULT_PROGRAM : suppliedTarget;
            UserDeleteResponse response = UserDeleteResponse.empty().withNextProgram(resolved);

            assertThat(response.nextProgram())
                    .as("with nothing supplied, PF3 returns to the sign-on screen")
                    .isEqualTo(PF3_DEFAULT_PROGRAM)
                    .isEqualTo("COSGN00C")
                    .hasSize(TO_PROGRAM_WIDTH);
        }

        @ParameterizedTest(name = "a supplied CDEMO-TO-PROGRAM of {0} is preserved")
        @ValueSource(strings = {"COADM01C", "COMEN01C", "COUSR00C"})
        @DisplayName("PF3 branch B - a caller-supplied target is preserved, never overwritten")
        void pf3PreservesASuppliedTarget(String suppliedTarget) {
            boolean unset = suppliedTarget.isBlank()
                    || suppliedTarget.chars().allMatch(character -> character == 0);
            assertThat(unset)
                    .as("%s is neither SPACES nor LOW-VALUES, so the IF at :199 does not fire",
                            suppliedTarget)
                    .isFalse();

            String resolved = unset ? PF3_DEFAULT_PROGRAM : suppliedTarget;
            UserDeleteResponse response = UserDeleteResponse.empty().withNextProgram(resolved);

            assertThat(response.nextProgram())
                    .isEqualTo(suppliedTarget)
                    .isNotEqualTo(PF3_DEFAULT_PROGRAM);
            assertThat(List.of("COADM01C", "COMEN01C", "COUSR00C")).contains(suppliedTarget);
        }

        @Test
        @DisplayName("unlike COUSR02C, this program's PF3 path does NOT save before routing away")
        void pf3DoesNotSaveFirst() {
            List<String> components = componentNames();
            assertThat(components)
                    .doesNotContain("pendingDelete", "confirmed", "deleteHeld", "dirty", "saved");

            UserDeleteResponse response = UserDeleteResponse.empty()
                    .withNextProgram(PF3_DEFAULT_PROGRAM)
                    .withNavigationContext(NavigationContext.empty()
                            .withFromTranid(TRANSACTION_ID)
                            .withFromProgram(PROGRAM_NAME)
                            .withPgmEnter());

            assertThat(response.navigationContext().fromTranid()).isEqualTo(TRANSACTION_ID);
            assertThat(response.navigationContext().fromProgram()).isEqualTo(PROGRAM_NAME);
            assertThat(response.navigationContext().pgmContext())
                    .as("MOVE ZEROS TO CDEMO-PGM-CONTEXT at :202 - the next screen sees a first entry")
                    .isEqualTo(PGM_CONTEXT_ENTER);
            assertThat(response.navigationContext().isEnter()).isTrue();
        }

        @Test
        @DisplayName("the 160-byte communication area travels in the payload, proved through the codec")
        void theCommareaTravelsInThePayload() {
            assertThat(NavigationContext.COMMAREA_LENGTH)
                    .as("34 + 84 + 12 + 16 + 14, from app/cpy/COCOM01Y.cpy:19-44")
                    .isEqualTo(COMMAREA_LENGTH)
                    .isEqualTo(COMMAREA_GENERAL_LENGTH + COMMAREA_CUSTOMER_LENGTH
                            + COMMAREA_ACCOUNT_LENGTH + COMMAREA_CARD_LENGTH + COMMAREA_MORE_LENGTH);
            assertThat(NavigationContext.GENERAL_INFO_LENGTH).isEqualTo(COMMAREA_GENERAL_LENGTH);
            assertThat(NavigationContext.CUSTOMER_INFO_LENGTH).isEqualTo(COMMAREA_CUSTOMER_LENGTH);
            assertThat(NavigationContext.ACCOUNT_INFO_LENGTH).isEqualTo(COMMAREA_ACCOUNT_LENGTH);
            assertThat(NavigationContext.CARD_INFO_LENGTH).isEqualTo(COMMAREA_CARD_LENGTH);
            assertThat(NavigationContext.MORE_INFO_LENGTH).isEqualTo(COMMAREA_MORE_LENGTH);

            NavigationContext carried = NavigationContext.empty()
                    .withFromTranid(TRANSACTION_ID)
                    .withFromProgram(PROGRAM_NAME)
                    .withToProgram(PF3_DEFAULT_PROGRAM)
                    .withUserId("ADMIN001")
                    .withUserTypeAdmin()
                    .withPgmReenter()
                    .withLastMap(MAP_NAME)
                    .withLastMapset(MAPSET_NAME);

            byte[] image = carried.toFixedWidth(codec());
            assertThat(image)
                    .as("the area serialises to exactly 160 bytes, with the code page named")
                    .hasSize(COMMAREA_LENGTH);
            assertThat(NavigationContext.fromFixedWidth(codec(), image))
                    .as("and deserialises back to the same value - so the client can hand it back")
                    .isEqualTo(carried);

            UserDeleteResponse response = UserDeleteResponse.empty().withNavigationContext(carried);
            assertThat(response.navigationContext()).isEqualTo(carried);
            assertThat(response.navigationContext().isAdmin()).isTrue();
            assertThat(response.navigationContext().isUser()).isFalse();
        }

        @Test
        @DisplayName("no member and no accessor introduces server-side state")
        void noServerSideState() {
            List<String> componentTypeNames = componentTypes().stream()
                    .map(Class::getName)
                    .toList();

            for (String marker : FORBIDDEN_STATE_MARKERS) {
                assertThat(componentTypeNames)
                        .as("no component may be typed %s - the conversation travels in the payload",
                                marker)
                        .noneMatch(name -> name.contains(marker));
            }
            assertThat(componentTypes())
                    .as("fifteen Strings and one NavigationContext plus the extension carrier - all "
                            + "immutable values")
                    .containsOnly(String.class, NavigationContext.class,
                            UserDeleteRequest.Cu03Info.class);

            for (Method method : UserDeleteResponse.class.getDeclaredMethods()) {
                if (method.isSynthetic()) {
                    continue;
                }
                assertThat(Modifier.isSynchronized(method.getModifiers()))
                        .as("%s must not synchronise: an immutable value has nothing to guard",
                                method.getName())
                        .isFalse();
            }
            for (java.lang.reflect.Field field : UserDeleteResponse.class.getDeclaredFields()) {
                if (field.isSynthetic()) {
                    continue;
                }
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("%s must be final - no mutable state, static or otherwise (practice B9)",
                                field.getName())
                        .isTrue();
            }
        }
    }

    @Nested
    @DisplayName("Conversation state - the AID and the ENTER/REENTER flag ride in the payload")
    class ConversationState {
        @Test
        @DisplayName("the resolved AID token is five characters and comes from EIBAID, not a session")
        void theAidTokenIsFiveCharacters() {
            assertThat(PfKeyResolver.AID_TOKEN_LENGTH)
                    .as("CCARD-AID PIC X(5) - app/cpy/CVCRD01Y.cpy")
                    .isEqualTo(5);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHENTER))
                    .contains(PfKeyResolver.AidKey.ENTER);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHCLEAR))
                    .contains(PfKeyResolver.AidKey.CLEAR);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF3))
                    .as("PF3 - the RETURN-TO-PREV-SCREEN key")
                    .contains(PfKeyResolver.AidKey.PFK03);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF4))
                    .as("PF4 - CLEAR-CURRENT-SCREEN, app/cbl/COUSR03C.cbl:343-344")
                    .contains(PfKeyResolver.AidKey.PFK04);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF5))
                    .as("PF5 - the key that actually deletes")
                    .contains(PfKeyResolver.AidKey.PFK05);
        }

        @Test
        @DisplayName("every token is exactly five characters, PA1 and PA2 space-padded to fit")
        void everyTokenIsFiveCharacters() {
            for (PfKeyResolver.AidKey key : PfKeyResolver.AidKey.values()) {
                assertThat(key.token())
                        .as("%s occupies the whole PIC X(5) item", key.name())
                        .hasSize(PfKeyResolver.AID_TOKEN_LENGTH);
            }
            assertThat(PfKeyResolver.AidKey.PA1.token())
                    .as("'PA1' plus two spaces - the padding is part of the fixed-width item")
                    .isEqualTo("PA1  ");
            assertThat(PfKeyResolver.AidKey.PA2.token()).isEqualTo("PA2  ");
            assertThat(PfKeyResolver.AidKey.ENTER.token()).isEqualTo("ENTER");
            assertThat(PfKeyResolver.AidKey.CLEAR.token()).isEqualTo("CLEAR");
        }

        @Test
        @DisplayName("PF13-PF24 fold onto PFK01-PFK12, and an unknown AID resolves to nothing")
        void theUpperKeysFoldAndAnUnknownAidDoesNot() {
            Optional<PfKeyResolver.AidKey> shifted = PfKeyResolver.resolve(CicsAid.DFHPF13);

            assertThat(shifted)
                    .as("a 3270 sends PF13-PF24 as shifted PF1-PF12, so the tokens fold")
                    .contains(PfKeyResolver.AidKey.PFK01);
            assertThat(shifted.map(PfKeyResolver.AidKey::token))
                    .as("and the folded token is still a five-character PIC X(5) image")
                    .contains("PFK01");
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF24))
                    .contains(PfKeyResolver.AidKey.PFK12);
            assertThat(PfKeyResolver.resolve((byte) 0x00))
                    .as("an AID matching none of the DFHAID constants yields an EXPLICIT no-match "
                            + "rather than a silent ENTER - the EVALUATE in CSSTRPFY has no default")
                    .isEmpty();
            assertThat(PfKeyResolver.resolve(CicsAid.DFHNULL))
                    .as("DFHNULL likewise resolves to nothing")
                    .isEmpty();
        }

        @ParameterizedTest(name = "CDEMO-PGM-CONTEXT {0} means enter={1}")
        @CsvSource({"0, true", "1, false"})
        @DisplayName("both 88-levels of CDEMO-PGM-CONTEXT are driven - gate G50")
        void bothContextStatesAreDriven(int context, boolean expectEnter) {
            NavigationContext carried = NavigationContext.empty().withPgmContext(context);
            UserDeleteResponse response = UserDeleteResponse.empty().withNavigationContext(carried);

            assertThat(response.navigationContext().pgmContext()).isEqualTo(context);
            assertThat(response.navigationContext().isEnter()).isEqualTo(expectEnter);
            assertThat(response.navigationContext().isReenter()).isEqualTo(!expectEnter);
            assertThat(context)
                    .isIn(PGM_CONTEXT_ENTER, PGM_CONTEXT_REENTER);
        }

        @Test
        @DisplayName("the two context states are mutually exclusive and exhaustively covered")
        void theContextStatesAreExclusive() {
            NavigationContext onEntry = NavigationContext.empty().withPgmEnter();
            NavigationContext onReentry = NavigationContext.empty().withPgmReenter();

            assertThat(onEntry.isEnter()).isTrue();
            assertThat(onEntry.isReenter()).isFalse();
            assertThat(onReentry.isReenter()).isTrue();
            assertThat(onReentry.isEnter()).isFalse();
            assertThat(NavigationContext.PGM_CONTEXT_ENTER).isEqualTo(PGM_CONTEXT_ENTER);
            assertThat(NavigationContext.PGM_CONTEXT_REENTER).isEqualTo(PGM_CONTEXT_REENTER);

            assertThat(UserDeleteResponse.empty().withNavigationContext(onEntry).navigationContext())
                    .isNotEqualTo(UserDeleteResponse.empty()
                            .withNavigationContext(onReentry).navigationContext());
        }
    }

    @Nested
    @DisplayName("CDEMO-CU03-INFO - 34 bytes on this DTO, and COCOM01Y stays exactly 160")
    class Cu03InfoExtension {
        @Test
        @DisplayName("the six items sum to 34, so the CU03 area is 194 while COCOM01Y stays 160")
        void theExtensionIsThirtyFourAndTheAreaIsOneNinetyFour() {
            assertThat(CU03_ITEM_NAMES).hasSize(6);
            assertThat(CU03_ITEM_WIDTHS).hasSize(6);
            assertThat(CU03_ITEM_WIDTHS.stream().mapToInt(Integer::intValue).sum())
                    .as("8 + 8 + 8 + 1 + 1 + 8, from app/cbl/COUSR03C.cbl:51-58")
                    .isEqualTo(CU03_EXTENSION_LENGTH)
                    .isEqualTo(34);
            assertThat(UserDeleteRequest.Cu03Info.LENGTH)
                    .as("the carrier publishes the same 34")
                    .isEqualTo(CU03_EXTENSION_LENGTH);
            assertThat(NavigationContext.COMMAREA_LENGTH)
                    .as("COCOM01Y is 160 for ALL SEVENTEEN controllers and gains nothing from this "
                            + "program's private extension - which is exactly why the extension is a "
                            + "member of its own rather than fifteen more fields on the context")
                    .isEqualTo(COMMAREA_LENGTH)
                    .isEqualTo(160);
            assertThat(CU03_COMMAREA_LENGTH)
                    .as("160 + 34 = 194, the area COUSR03C:94 restores from DFHCOMMAREA")
                    .isEqualTo(194);
            assertThat(NavigationContext.COMMAREA_LENGTH + UserDeleteRequest.Cu03Info.LENGTH)
                    .isEqualTo(CU03_COMMAREA_LENGTH);
        }

        @Test
        @DisplayName("the extension is one member on this DTO, not folded into NavigationContext")
        void theExtensionIsItsOwnMember() {
            assertThat(componentNames()).contains(EXTENSION_MEMBER);
            assertThat(componentTypes().get(componentNames().indexOf(EXTENSION_MEMBER)))
                    .isEqualTo(UserDeleteRequest.Cu03Info.class);

            List<String> contextComponents =
                    Arrays.stream(NavigationContext.class.getRecordComponents())
                            .map(RecordComponent::getName)
                            .toList();
            assertThat(contextComponents)
                    .as("the shared context carries the COCOM01Y items and nothing else")
                    .hasSize(16)
                    .doesNotContain("usridFirst", "usridLast", "pageNum", "nextPageFlg",
                            "usrSelFlg", "usrSelected");
        }

        @Test
        @DisplayName("the six item names are CDEMO-CU03- prefixed, distinct from CU00's and CU02's")
        void thePrefixIsCu03AndNotCu00OrCu02() {
            Map<String, String> images = UserDeleteRequest.Cu03Info.initial().fieldImages();

            assertThat(images.keySet())
                    .as("app/cbl/COUSR03C.cbl:51-58 spells every item CDEMO-CU03-*")
                    .containsExactlyElementsOf(CU03_ITEM_NAMES);
            assertThat(images.keySet()).allSatisfy(name -> assertThat(name)
                    .startsWith("CDEMO-CU03-")
                    .doesNotContain("CDEMO-CU00-")
                    .doesNotContain("CDEMO-CU02-"));

            assertThat(CU03_ITEM_NAMES.stream()
                    .map(name -> name.replace("CU03", "CU00"))
                    .toList())
                    .as("the CU00 spelling is a different set of names for the same shape")
                    .doesNotContainAnyElementsOf(CU03_ITEM_NAMES);
        }

        @Test
        @DisplayName("a delete screen carries paging members it never pages with - preserved, not pruned")
        void thePagingMembersAreCarriedAnyway() {
            UserDeleteRequest.Cu03Info handedOver = new UserDeleteRequest.Cu03Info("USER0001",
                    "USER0010",
                    2,
                    NEXT_PAGE_YES,
                    "D",
                    "USER0004");
            UserDeleteResponse response = UserDeleteResponse.empty().withCu03Info(handedOver);

            assertThat(response.cu03Info()).isEqualTo(handedOver);
            assertThat(response.cu03Info().usrSelected())
                    .as("the one item this program reads, at COUSR03C:99-102")
                    .isEqualTo("USER0004");
            assertThat(response.cu03Info().usrSelFlg())
                    .as("the 'D' that COUSR00C:200-202 routed on")
                    .isEqualTo("D");
            assertThat(response.cu03Info().pageNum())
                    .as("carried untouched, never incremented here")
                    .isEqualTo(2);
            assertThat(response.cu03Info().fieldImages())
                    .containsEntry("CDEMO-CU03-USRID-FIRST", "USER0001")
                    .containsEntry("CDEMO-CU03-USRID-LAST", "USER0010")
                    .containsEntry("CDEMO-CU03-NEXT-PAGE-FLG", NEXT_PAGE_YES);
        }

        @Test
        @DisplayName("pageNum is an int - PIC 9(08) is scale-free, so no floating point exists here")
        void pageNumIsIntegral() {
            RecordComponent pageNumComponent =
                    Arrays.stream(UserDeleteRequest.Cu03Info.class.getRecordComponents())
                            .filter(component -> "pageNum".equals(component.getName()))
                            .findFirst()
                            .orElseThrow();

            assertThat(pageNumComponent.getType())
                    .as("CDEMO-CU03-PAGE-NUM PIC 9(08) at app/cbl/COUSR03C.cbl:53 has no V and no "
                            + "sign, so it is an integer - gate G22 and rule R4")
                    .isEqualTo(int.class);
            assertThat(pageNumComponent.getType())
                    .isNotEqualTo(double.class)
                    .isNotEqualTo(float.class)
                    .isNotEqualTo(Double.class)
                    .isNotEqualTo(Float.class)
                    .isNotEqualTo(java.math.BigDecimal.class);
            assertThat(UserDeleteRequest.Cu03Info.PAGE_NUM_DIGITS).isEqualTo(PAGE_NUM_DIGITS);

            assertThat(new UserDeleteRequest.Cu03Info("        ", "        ", 3, NEXT_PAGE_NO, " ",
                    "        ").fieldImages())
                    .containsEntry("CDEMO-CU03-PAGE-NUM", "00000003");
            assertThat(codec().movePic9(3L, PAGE_NUM_DIGITS))
                    .as("PIC 9 fills from the RIGHT, so 3 becomes 00000003 and never 30000000")
                    .isEqualTo("00000003");
        }

        @Test
        @DisplayName("no component of this payload is a floating-point type - gate G22")
        void noComponentIsFloatingPoint() {
            for (Class<?> type : componentTypes()) {
                assertThat(type)
                        .as("%s must not be a floating-point type", type.getName())
                        .isNotEqualTo(double.class)
                        .isNotEqualTo(float.class)
                        .isNotEqualTo(Double.class)
                        .isNotEqualTo(Float.class);
            }
            for (java.lang.reflect.Field field : UserDeleteResponse.class.getDeclaredFields()) {
                assertThat(field.getType())
                        .as("field %s must not be a floating-point type", field.getName())
                        .isNotEqualTo(double.class)
                        .isNotEqualTo(float.class)
                        .isNotEqualTo(Double.class)
                        .isNotEqualTo(Float.class);
            }
            for (Method method : UserDeleteResponse.class.getDeclaredMethods()) {
                assertThat(method.getReturnType())
                        .as("%s must not return a floating-point type", method.getName())
                        .isNotEqualTo(double.class)
                        .isNotEqualTo(float.class)
                        .isNotEqualTo(Double.class)
                        .isNotEqualTo(Float.class);
            }
        }

        @ParameterizedTest(name = "NEXT-PAGE-FLG ''{0}'' -> yes={1}, no={2}")
        @CsvSource({"Y, true, false", "N, false, true", "' ', false, false"})
        @DisplayName("both 88-levels of NEXT-PAGE-FLG are driven, plus a value satisfying neither")
        void bothNextPageStatesAndAThird(String flag, boolean expectYes, boolean expectNo) {
            UserDeleteRequest.Cu03Info info = new UserDeleteRequest.Cu03Info("        ",
                    "        ",
                    0,
                    flag,
                    " ",
                    "        ");
            UserDeleteResponse response = UserDeleteResponse.empty().withCu03Info(info);
            String carried = response.cu03Info().nextPageFlg();

            assertThat(carried).hasSize(1);
            assertThat(UserDeleteRequest.Cu03Info.NEXT_PAGE_YES.equals(carried))
                    .as("88 NEXT-PAGE-YES VALUE 'Y' holds")
                    .isEqualTo(expectYes);
            assertThat(UserDeleteRequest.Cu03Info.NEXT_PAGE_NO.equals(carried))
                    .as("88 NEXT-PAGE-NO VALUE 'N' holds")
                    .isEqualTo(expectNo);
            assertThat(expectYes && expectNo)
                    .as("the two condition names are mutually exclusive")
                    .isFalse();
        }

        @Test
        @DisplayName("the declared VALUE 'N' is the cold-start state, not a convention chosen in Java")
        void theDeclaredDefaultIsNo() {
            assertThat(UserDeleteRequest.Cu03Info.initial().nextPageFlg())
                    .as("VALUE 'N' on app/cbl/COUSR03C.cbl:54")
                    .isEqualTo(NEXT_PAGE_NO)
                    .isEqualTo("N");
            assertThat(UserDeleteRequest.Cu03Info.initial().pageNum())
                    .as("no VALUE clause on PAGE-NUM, so a cold start sees zero")
                    .isZero();
            assertThat(UserDeleteRequest.Cu03Info.initial().usridFirst()).isEqualTo(" ".repeat(8));
            assertThat(UserDeleteRequest.Cu03Info.initial().usridLast()).isEqualTo(" ".repeat(8));
            assertThat(UserDeleteRequest.Cu03Info.initial().usrSelFlg()).isEqualTo(" ");
            assertThat(UserDeleteRequest.Cu03Info.initial().usrSelected()).isEqualTo(" ".repeat(8));
            assertThat(UserDeleteResponse.empty().cu03Info())
                    .as("an empty screen carries the extension in its VALUE-clause state")
                    .isEqualTo(UserDeleteRequest.Cu03Info.initial());
        }

        @Test
        @DisplayName("an absent extension normalises: 34 bytes of storage always exist")
        void anAbsentExtensionNormalises() {
            UserDeleteResponse response = new UserDeleteResponse(" ".repeat(4),
                    " ".repeat(40),
                    " ".repeat(8),
                    " ".repeat(8),
                    " ".repeat(40),
                    " ".repeat(8),
                    " ".repeat(8),
                    " ".repeat(20),
                    " ".repeat(20),
                    " ",
                    " ".repeat(78),
                    NavigationContext.empty(),
                    " ".repeat(8),
                    " ".repeat(7),
                    " ".repeat(7),
                    null);

            assertThat(response.cu03Info())
                    .as("05 CDEMO-CU03-INFO is storage inside the communication area; there is no "
                            + "absent state for it, so null becomes the cold-start value")
                    .isNotNull()
                    .isEqualTo(UserDeleteRequest.Cu03Info.initial());
            assertThat(UserDeleteResponse.empty().withCu03Info(null).cu03Info())
                    .isEqualTo(UserDeleteRequest.Cu03Info.initial());
        }
    }

    @Nested
    @DisplayName("Vestigial and dead state - recorded here, modelled nowhere")
    class VestigialAndDeadState {
        @Test
        @DisplayName("WS-USR-MODIFIED is dead storage, so there is no usrModified member")
        void theVestigialModifiedFlagIsNotModelled() {
            List<String> lowerCaseComponents =
                    componentNames().stream().map(name -> name.toLowerCase(Locale.ROOT)).toList();

            assertThat(lowerCaseComponents)
                    .doesNotContain("usrmodified", "usermodified", "modified", "modifiedflag");
            for (String component : componentNames()) {
                assertThat(component.toLowerCase(Locale.ROOT)).doesNotContain("modif");
            }
            for (Method method : UserDeleteResponse.class.getDeclaredMethods()) {
                assertThat(method.getName().toLowerCase(Locale.ROOT))
                        .as("no accessor may expose the dead flag either")
                        .doesNotContain("modif");
            }
            assertThat(DFHMDF_LABELS)
                    .as("and it is absent from the mapset, which is the reason it is not a member")
                    .doesNotContain("USRMOD", "MODIFIED");
        }

        @Test
        @DisplayName("gate G43 has no subject here - this program has no 9300-CHECK-CHANGE-IN-REC")
        void noOptimisticConcurrencyBelongsInThisPackage() {
            List<String> lowerCaseComponents =
                    componentNames().stream().map(name -> name.toLowerCase(Locale.ROOT)).toList();

            assertThat(lowerCaseComponents)
                    .doesNotContain("version", "etag", "revision", "timestamp", "lastmodified",
                            "optimisticlock");
            for (Method method : UserDeleteResponse.class.getDeclaredMethods()) {
                String name = method.getName().toLowerCase(Locale.ROOT);
                assertThat(name).doesNotContain("version").doesNotContain("etag");
            }
        }

        @Test
        @DisplayName("the declared toString omits cu03Info - asserted as declared, not corrected")
        void theDeclaredToStringOmitsTheExtension() {
            UserDeleteResponse response = populated();
            String rendered = response.toString();

            assertThat(rendered).startsWith("UserDeleteResponse[").endsWith("]");
            assertThat(rendered)
                    .contains("trnName=", "usrIdIn=", "errMsg=", "nextProgram=", "nextMapset=",
                            "nextMap=", "navigationContext=");
            assertThat(rendered)
                    .as("cu03Info is not rendered by the declared override")
                    .doesNotContain("cu03Info=");
        }

        @Test
        @DisplayName("toString withholds the personal names it renders every other field verbatim")
        void toStringWithholdsTheNames() {
            UserDeleteResponse response = UserDeleteResponse.empty()
                    .withUsrIdIn("USER0001")
                    .withFName(codec().movePicX("Grace", SEC_USR_FNAME_WIDTH))
                    .withLName(codec().movePicX("Hopper", SEC_USR_LNAME_WIDTH));
            String rendered = response.toString();

            assertThat(rendered)
                    .as("a given name and a family name have no safely revealable part")
                    .doesNotContain("Grace")
                    .doesNotContain("Hopper");
            assertThat(rendered)
                    .as("the eight-character operator id is not a personal identifier and a screen "
                            + "parity failure has to be readable from it")
                    .contains("USER0001");
        }
    }

    @Nested
    @DisplayName("Security posture - nothing to hash, and nothing that hashes")
    class SecurityPosture {
        @ParameterizedTest(name = "no reference to {0}")
        @ValueSource(strings = {"PasswordEncoder", "BCrypt", "MessageDigest", "Jwt", "JWT",
            "Authentication", "GrantedAuthority", "UserDetails", "SecurityContext",
            "org.springframework.security"})
        @DisplayName("no encoder, digest, token or Spring Security type is reachable from this payload")
        void noSecurityTypeIsReachable(String marker) {
            List<String> reachableTypeNames = new ArrayList<>();
            componentTypes().forEach(type -> reachableTypeNames.add(type.getName()));
            for (Method method : UserDeleteResponse.class.getDeclaredMethods()) {
                reachableTypeNames.add(method.getReturnType().getName());
                for (Class<?> parameter : method.getParameterTypes()) {
                    reachableTypeNames.add(parameter.getName());
                }
            }
            for (java.lang.reflect.Constructor<?> constructor
                    : UserDeleteResponse.class.getDeclaredConstructors()) {
                for (Class<?> parameter : constructor.getParameterTypes()) {
                    reachableTypeNames.add(parameter.getName());
                }
            }
            for (java.lang.reflect.Field field : UserDeleteResponse.class.getDeclaredFields()) {
                reachableTypeNames.add(field.getType().getName());
            }

            assertThat(reachableTypeNames)
                    .as("%s would mean the security posture had been changed; the screen has no "
                            + "credential, so the payload carries none and nothing hashes one",
                            marker)
                    .noneMatch(name -> name.contains(marker));
            assertThat(FORBIDDEN_SECURITY_MARKERS).contains(marker);
        }

        @Test
        @DisplayName("authentication stays file-based and plaintext elsewhere - not this payload's concern")
        void authenticationIsNotThisPayloadsConcern() {
            assertThat(SecUserRecord.SEC_USR_PWD_LENGTH)
                    .as("the record's password field is PIC X(08) and stays plaintext")
                    .isEqualTo(SEC_USR_PWD_WIDTH);
            assertThat(componentNames())
                    .as("and none of it reaches this payload")
                    .doesNotContain("secUsrPwd", "password", "passwd");
            assertThat(componentTypes())
                    .as("no char[] either - a credential-shaped carrier by another name")
                    .doesNotContain(char[].class);
        }
    }

    @Nested
    @DisplayName("Width enforcement - shorter fits, longer is refused, null is refused")
    class WidthEnforcement {
        @ParameterizedTest(name = "{0} PIC X({1}) accepts a value of exactly its width")
        @MethodSource("com.vsergeychik.carddemo.user.dto.UserDeleteResponseTest#everyStringField")
        @DisplayName("a value of exactly the declared width is accepted unchanged")
        void exactWidthAccepted(String cobolName,
                int width,
                BiFunction<UserDeleteResponse, String, UserDeleteResponse> with,
                Function<UserDeleteResponse, String> read) {
            String value = "X".repeat(width);

            UserDeleteResponse response = with.apply(UserDeleteResponse.empty(), value);

            assertThat(read.apply(response))
                    .as("%s accepts its full %d characters unchanged", cobolName, width)
                    .isEqualTo(value);
            assertEveryOtherFieldIsUntouched(response, cobolName);
        }

        @ParameterizedTest(name = "{0} PIC X({1}) accepts a shorter value unchanged")
        @MethodSource("com.vsergeychik.carddemo.user.dto.UserDeleteResponseTest#everyStringField")
        @DisplayName("a shorter value is accepted as it stands, as a MOVE into a wider receiver is")
        void shorterAccepted(String cobolName,
                int width,
                BiFunction<UserDeleteResponse, String, UserDeleteResponse> with,
                Function<UserDeleteResponse, String> read) {
            String value = "X".repeat(Math.max(0, width - 1));

            UserDeleteResponse response = with.apply(UserDeleteResponse.empty(), value);

            assertThat(read.apply(response))
                    .as("%s tolerates %d characters as they stand; padding happens when the image is "
                            + "rendered, not on the way in", cobolName, value.length())
                    .isEqualTo(value);
            assertEveryOtherFieldIsUntouched(response, cobolName);
        }

        private void assertEveryOtherFieldIsUntouched(UserDeleteResponse response, String written) {
            Map<String, String> before = everyReadableField(UserDeleteResponse.empty());
            Map<String, String> after = everyReadableField(response);
            assertThat(after.keySet())
                    .as("the fixture must read the same fields from both responses")
                    .isEqualTo(before.keySet());
            assertThat(before)
                    .as("%s must be one of the fields the fixture can read", written)
                    .containsKey(written);
            for (String item : before.keySet()) {
                if (item.equals(written)) {
                    continue;
                }
                assertThat(after.get(item))
                        .as("writing %s must not have disturbed %s", written, item)
                        .isEqualTo(before.get(item));
            }
        }

        @SuppressWarnings("unchecked")
        private Map<String, String> everyReadableField(UserDeleteResponse response) {
            Map<String, String> values = new LinkedHashMap<>();
            for (Arguments row : everyStringField()) {
                Object[] cells = row.get();
                values.put((String) cells[0],
                        ((Function<UserDeleteResponse, String>) cells[3]).apply(response));
            }
            return values;
        }

        @ParameterizedTest(name = "{0} PIC X({1}) refuses one character too many")
        @MethodSource("com.vsergeychik.carddemo.user.dto.UserDeleteResponseTest#everyStringField")
        @DisplayName("an over-long value is refused rather than silently truncated")
        void overWidthRefused(String cobolName,
                int width,
                BiFunction<UserDeleteResponse, String, UserDeleteResponse> with,
                Function<UserDeleteResponse, String> read) {
            String value = "X".repeat(width + 1);

            assertThatIllegalArgumentException()
                    .as("%s is PIC X(%d) and the screen has nowhere to put the surplus", cobolName,
                            width)
                    .isThrownBy(() -> with.apply(UserDeleteResponse.empty(), value))
                    .withMessageContaining(cobolName)
                    .withMessageContaining(String.valueOf(width));
        }

        @ParameterizedTest(name = "{0} refuses null")
        @MethodSource("com.vsergeychik.carddemo.user.dto.UserDeleteResponseTest#everyStringField")
        @DisplayName("null is refused - there is no null in a COBOL screen field")
        void nullRefused(String cobolName,
                int width,
                BiFunction<UserDeleteResponse, String, UserDeleteResponse> with,
                Function<UserDeleteResponse, String> read) {
            assertThat(width).as("%s has a declared width", cobolName).isPositive();
            assertThatNullPointerException()
                    .as("an unpopulated PIC X field holds spaces or low-values, never nothing")
                    .isThrownBy(() -> with.apply(UserDeleteResponse.empty(), null))
                    .withMessageContaining(cobolName);
        }

        @Test
        @DisplayName("a null communication area is refused, naming gate G37")
        void nullCommareaRefused() {
            assertThatNullPointerException()
                    .isThrownBy(() -> UserDeleteResponse.empty().withNavigationContext(null))
                    .withMessageContaining("navigationContext");
        }

        @Test
        @DisplayName("an 80-character WS-MESSAGE is refused by errMsg, which holds 78")
        void wsMessageWidthIsRefused() {
            String eighty = "M".repeat(WS_MESSAGE_LENGTH);

            assertThatIllegalArgumentException()
                    .as("the 80-to-78 narrowing of COUSR03C:217 is the CALLER's decision, made "
                            + "explicit through FixedWidthCodec.movePicX")
                    .isThrownBy(() -> UserDeleteResponse.empty().withErrMsg(eighty))
                    .withMessageContaining("ERRMSGO")
                    .withMessageContaining(String.valueOf(ERR_MSG_LENGTH));
            assertThat(UserDeleteResponse.empty().withErrMsg(errMsgImage(eighty)).errMsg())
                    .hasSize(ERR_MSG_LENGTH);
        }
    }

    @Nested
    @DisplayName("Factory and immutable copies")
    class FactoryAndCopies {
        @Test
        @DisplayName("empty() space-fills every field to its declared width")
        void emptyIsSpaceFilled() {
            UserDeleteResponse empty = UserDeleteResponse.empty();
            List<String> values = List.of(empty.trnName(), empty.title01(), empty.curDate(),
                    empty.pgmName(), empty.title02(), empty.curTime(), empty.usrIdIn(),
                    empty.fName(), empty.lName(), empty.usrType(), empty.errMsg());

            for (int index = 0; index < DFHMDF_NAMED; index++) {
                assertThat(values.get(index))
                        .as("%s is %d LOW-VALUES", XXXO_ITEMS.get(index), DECLARED_WIDTHS.get(index))
                        .isEqualTo(ScreenFieldImage.unpainted(DECLARED_WIDTHS.get(index)))
                        .hasSize(DECLARED_WIDTHS.get(index));
            }
            assertThat(values.stream().mapToInt(String::length).sum())
                    .as("the eleven fields together occupy the map's 235 data bytes")
                    .isEqualTo(PAYLOAD_WIDTH_TOTAL);
            assertThat(empty.navigationContext()).isEqualTo(NavigationContext.empty());
            assertThat(empty.cu03Info()).isEqualTo(UserDeleteRequest.Cu03Info.initial());
            assertThat(empty.nextProgram()).isEqualTo(" ".repeat(TO_PROGRAM_WIDTH));
            assertThat(empty.nextMapset()).isEqualTo(" ".repeat(LAST_MAPSET_WIDTH));
            assertThat(empty.nextMap()).isEqualTo(" ".repeat(LAST_MAP_WIDTH));
        }

        @Test
        @DisplayName("empty() does not pre-populate the header - POPULATE-HEADER-INFO does that")
        void emptyDoesNotPrePopulateTheHeader() {
            UserDeleteResponse empty = UserDeleteResponse.empty();

            assertThat(empty.trnName()).isNotEqualTo(TRANSACTION_ID);
            assertThat(ScreenFieldImage.isUnpainted(empty.trnName())).isTrue();
            assertThat(empty.pgmName()).isNotEqualTo(PROGRAM_NAME);
            assertThat(ScreenFieldImage.isUnpainted(empty.pgmName())).isTrue();
            assertThat(empty.withTrnName(TRANSACTION_ID).trnName()).isEqualTo(TRANSACTION_ID);
            assertThat(empty.withPgmName(PROGRAM_NAME).pgmName()).isEqualTo(PROGRAM_NAME);
        }

        @Test
        @DisplayName("each with* replaces only its own field and leaves the original untouched")
        void withReplacesOnlyItsOwnField() {
            UserDeleteResponse original = populated();
            UserDeleteResponse changed = original.withErrMsg(
                    errMsgImage(wsMessageImage(MSG_UNABLE_TO_UPDATE_USER)));

            assertThat(changed.errMsg()).startsWith(MSG_UNABLE_TO_UPDATE_USER);
            assertThat(changed.trnName()).isEqualTo(original.trnName());
            assertThat(changed.title01()).isEqualTo(original.title01());
            assertThat(changed.curDate()).isEqualTo(original.curDate());
            assertThat(changed.pgmName()).isEqualTo(original.pgmName());
            assertThat(changed.title02()).isEqualTo(original.title02());
            assertThat(changed.curTime()).isEqualTo(original.curTime());
            assertThat(changed.usrIdIn()).isEqualTo(original.usrIdIn());
            assertThat(changed.fName()).isEqualTo(original.fName());
            assertThat(changed.lName()).isEqualTo(original.lName());
            assertThat(changed.usrType()).isEqualTo(original.usrType());
            assertThat(changed.navigationContext()).isEqualTo(original.navigationContext());
            assertThat(changed.nextProgram()).isEqualTo(original.nextProgram());
            assertThat(changed.nextMapset()).isEqualTo(original.nextMapset());
            assertThat(changed.nextMap()).isEqualTo(original.nextMap());
            assertThat(changed.cu03Info()).isEqualTo(original.cu03Info());
            assertThat(original.errMsg())
                    .as("the original is unchanged - every copy method returns a new instance")
                    .startsWith(MSG_PRESS_PF5);
        }

        @Test
        @DisplayName("there is one with* method per component, so no field is unreachable")
        void everyComponentHasACopyMethod() {
            long copyMethods = Arrays.stream(UserDeleteResponse.class.getDeclaredMethods())
                    .filter(method -> method.getName().startsWith("with"))
                    .filter(method -> method.getParameterCount() == 1)
                    .filter(method -> UserDeleteResponse.class.equals(method.getReturnType()))
                    .count();

            assertThat(copyMethods)
                    .as("sixteen components, sixteen single-argument copy methods")
                    .isEqualTo(COMPONENT_COUNT);
        }

        @Test
        @DisplayName("equality is by value, and the type is immutable")
        void valueSemantics() {
            assertThat(populated())
                    .isEqualTo(populated())
                    .hasSameHashCodeAs(populated())
                    .isNotEqualTo(UserDeleteResponse.empty());
            assertThat(UserDeleteResponse.class.isRecord())
                    .as("a record, so equals and hashCode are componentwise by construction")
                    .isTrue();
            assertThat(Modifier.isFinal(UserDeleteResponse.class.getModifiers()))
                    .as("records are implicitly final - no subclass can add mutable state")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("JSON round trip - exactly the declared components, byte for byte")
    class Serialisation {
        @Test
        @DisplayName("the JSON key set is exactly the sixteen components")
        void keySetIsExactlyTheComponents() throws Exception {
            Map<String, Object> json = asJsonMap(populated());

            assertThat(json.keySet())
                    .containsExactlyInAnyOrderElementsOf(wireNamesOf(componentNames()));
            assertThat(json).hasSize(COMPONENT_COUNT);
            assertThat(json.keySet()).containsAll(wireNamesOf(MAP_MEMBERS))
                    .containsAll(NAVIGATION_MEMBERS)
                    .contains(EXTENSION_MEMBER);
        }

        @Test
        @DisplayName("no control item, no filler and no credential appears as a JSON key")
        void noMetadataOrCredentialKey() throws Exception {
            Map<String, Object> json = asJsonMap(populated());

            for (String key : json.keySet()) {
                String lower = key.toLowerCase(Locale.ROOT);
                assertThat(CREDENTIAL_TOKENS)
                        .as("key %s must not name a credential", key)
                        .noneMatch(lower::contains);
                assertThat(lower).doesNotContain("filler").doesNotContain("tioapfx");
                for (String label : DFHMDF_LABELS) {
                    for (String suffix : CONTROL_SUFFIXES) {
                        assertThat(lower)
                                .as("%s%s is metadata, never a payload key", label, suffix)
                                .isNotEqualTo((label + suffix).toLowerCase(Locale.ROOT));
                    }
                }
            }
        }

        @Test
        @DisplayName("member names are serialised untransformed - no rename, no snake_case")
        void namesAreUntransformed() throws Exception {
            String json = webConfigMapper().writeValueAsString(populated());

            assertThat(json)
                    .as("config.WebConfig leaves the naming strategy alone, so a member name is its "
                            + "JSON key verbatim")
                    .contains("\"usridin\"", "\"errmsg\"", "\"nextMapset\"", "\"cu03Info\"",
                            "\"navigationContext\"")
                    .doesNotContain("usr_id_in", "err_msg", "next_mapset", "cu03_info")
                    .doesNotContain("USRIDINO", "ERRMSGO");
        }

        @Test
        @DisplayName("space padding survives - no trimming, no empty-string-to-null coercion")
        void spacePaddingSurvives() throws Exception {
            UserDeleteResponse padded = UserDeleteResponse.empty()
                    .withUsrIdIn("USER1   ")
                    .withFName(codec().movePicX("Ada", SEC_USR_FNAME_WIDTH))
                    .withLName(codec().movePicX("Byron", SEC_USR_LNAME_WIDTH));

            UserDeleteResponse back = roundTrip(padded);

            assertThat(back.usrIdIn())
                    .as("a trimmed id would not be eight bytes and the record key would not match")
                    .isEqualTo("USER1   ")
                    .hasSize(SEC_USR_ID_WIDTH);
            assertThat(back.fName()).hasSize(SEC_USR_FNAME_WIDTH).startsWith("Ada");
            assertThat(back.lName()).hasSize(SEC_USR_LNAME_WIDTH).startsWith("Byron");
            assertThat(back.errMsg())
                    .as("ACCEPT_EMPTY_STRING_AS_NULL_OBJECT stays disabled, so a fixed-width blank field "
                            + "survives the round trip at full width and does not become null")
                    .isEqualTo(ScreenFieldImage.unpainted(ERR_MSG_LENGTH))
                    .hasSize(ERR_MSG_LENGTH);
            assertThat(back.usrType())
                    .isEqualTo(ScreenFieldImage.unpainted(SEC_USR_TYPE_WIDTH))
                    .hasSize(SEC_USR_TYPE_WIDTH);
            assertThat(back).isEqualTo(padded);
        }

        @Test
        @DisplayName("a fully populated payload round-trips byte for byte, extension included")
        void populatedRoundTrips() throws Exception {
            UserDeleteResponse original = populated();
            UserDeleteResponse back = roundTrip(original);

            assertThat(back).isEqualTo(original);
            assertThat(back.cu03Info()).isEqualTo(original.cu03Info());
            assertThat(back.navigationContext()).isEqualTo(original.navigationContext());
            assertThat(back.errMsg()).isEqualTo(original.errMsg()).hasSize(ERR_MSG_LENGTH);
        }

        @Test
        @DisplayName("the page number renders as an eight-digit image without a decimal point")
        void thePageNumberHasNoDecimalPoint() throws Exception {
            UserDeleteResponse response = UserDeleteResponse.empty()
                    .withCu03Info(new UserDeleteRequest.Cu03Info("USER0001", "USER0010", 7,
                            NEXT_PAGE_NO, " ", "        "));

            String json = webConfigMapper().writeValueAsString(response);
            UserDeleteResponse back = roundTrip(response);

            assertThat(json)
                    .as("WRITE_BIGDECIMAL_AS_PLAIN is enabled and PIC 9(08) is scale-free, so no "
                            + "exponent and no decimal point can appear")
                    .contains("\"pageNum\":7")
                    .doesNotContain("7.0")
                    .doesNotContain("E+");
            assertThat(back.cu03Info().pageNum()).isEqualTo(7);
            assertThat(back.cu03Info().fieldImages())
                    .containsEntry("CDEMO-CU03-PAGE-NUM", "00000007");
        }

        @Test
        @DisplayName("no password-shaped member appears in the serialised form, under any name")
        void noPasswordShapedMemberIsSerialised() throws Exception {
            Map<String, Object> json = asJsonMap(populated());

            assertThat(json).hasSize(COMPONENT_COUNT);
            assertThat(json.keySet())
                    .as("sixteen keys, and none of them a credential - the screen has no password "
                            + "field, so the payload has no password key")
                    .allSatisfy(key -> assertThat(CREDENTIAL_TOKENS)
                            .noneMatch(key.toLowerCase(Locale.ROOT)::contains));

            assertThat(json.keySet())
                    .containsExactlyInAnyOrderElementsOf(wireNamesOf(componentNames()));
        }
    }

    @Nested
    @DisplayName("Determinism - a fixed clock, a named code page, no dataset literal")
    class Determinism {
        @Test
        @DisplayName("the date and time header is driven from a fixed clock, never from now()")
        void theHeaderIsDrivenFromAFixedClock() {
            DateHeader header = DateHeader.from(codec(), Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));

            assertThat(header.wsCurdateMmDdYy())
                    .as("MM/DD/YY, the shape COUSR03C:252-256 builds and moves into CURDATEO")
                    .isEqualTo(FIXED_CURDATE)
                    .hasSize(DateHeader.WS_CURDATE_MM_DD_YY_LENGTH)
                    .hasSize(UserDeleteResponse.CUR_DATE_LENGTH);
            assertThat(header.wsCurtimeHhMmSs())
                    .as("hh:mm:ss, the shape COUSR03C:258-262 builds and moves into CURTIMEO")
                    .isEqualTo(FIXED_CURTIME)
                    .hasSize(DateHeader.WS_CURTIME_HH_MM_SS_LENGTH)
                    .hasSize(UserDeleteResponse.CUR_TIME_LENGTH);

            UserDeleteResponse stamped = UserDeleteResponse.empty()
                    .withCurDate(header.wsCurdateMmDdYy())
                    .withCurTime(header.wsCurtimeHhMmSs());
            assertThat(stamped.curDate()).isEqualTo(FIXED_CURDATE);
            assertThat(stamped.curTime()).isEqualTo(FIXED_CURTIME);

            DateHeader again = DateHeader.from(codec(), Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
            assertThat(again.wsCurdateMmDdYy()).isEqualTo(header.wsCurdateMmDdYy());
            assertThat(again.wsCurtimeHhMmSs()).isEqualTo(header.wsCurtimeHhMmSs());
        }

        @Test
        @DisplayName("every codec in this suite names US-ASCII explicitly")
        void theCodePageIsNamed() {
            assertThat(MAP_CHARSET)
                    .as("app/data/ASCII holds the authoritative fixtures, so US-ASCII is the code "
                            + "page for a screen payload - and it is STATED, never defaulted "
                            + "(practice B8)")
                    .isEqualTo(StandardCharsets.US_ASCII);
            assertThat(codec().charset()).isEqualTo(MAP_CHARSET);
            assertThat(new FixedWidthRecord(SYMBOLIC_MAP_LENGTH, MAP_CHARSET).charset())
                    .isEqualTo(MAP_CHARSET);
            assertThat(SecUserRecord.encode(SecUserRecord.blank(), MAP_CHARSET))
                    .hasSize(SEC_USER_DATA_LENGTH);
        }

        @Test
        @DisplayName("no dataset literal appears in this payload's published constants - gate G46")
        void noDatasetLiteral() {
            List<String> publishedLiterals = List.of(UserDeleteResponse.TRANSACTION_ID,
                    UserDeleteResponse.PROGRAM_NAME,
                    UserDeleteResponse.MAP_NAME,
                    UserDeleteResponse.MAPSET_NAME,
                    UserDeleteResponse.TRN_NAME_FIELD,
                    UserDeleteResponse.TITLE01_FIELD,
                    UserDeleteResponse.CUR_DATE_FIELD,
                    UserDeleteResponse.PGM_NAME_FIELD,
                    UserDeleteResponse.TITLE02_FIELD,
                    UserDeleteResponse.CUR_TIME_FIELD,
                    UserDeleteResponse.USR_ID_IN_FIELD,
                    UserDeleteResponse.F_NAME_FIELD,
                    UserDeleteResponse.L_NAME_FIELD,
                    UserDeleteResponse.USR_TYPE_FIELD,
                    UserDeleteResponse.ERR_MSG_FIELD,
                    UserDeleteResponse.NEXT_PROGRAM_FIELD,
                    UserDeleteResponse.NEXT_MAPSET_FIELD,
                    UserDeleteResponse.NEXT_MAP_FIELD);

            assertThat(publishedLiterals)
                    .as("dataset names are resolved from application.yml, never embedded in Java")
                    .allSatisfy(literal -> assertThat(literal)
                            .doesNotContain("AWS.M2.CARDDEMO")
                            .doesNotContain("VSAM")
                            .doesNotContain("USRSEC"));
        }

        @Test
        @DisplayName("this suite is hermetic - it reads no file and depends on no working directory")
        void theSuiteIsHermetic() {
            assertThat(DFHMDF_LABELS).hasSize(DFHMDF_NAMED);
            assertThat(XXXI_ITEMS).hasSize(DFHMDF_NAMED);
            assertThat(XXXO_ITEMS).hasSize(DFHMDF_NAMED);
            assertThat(MAP_MEMBERS).hasSize(DFHMDF_NAMED);
            assertThat(DECLARED_WIDTHS).hasSize(DFHMDF_NAMED);
            assertThat(COUSR03_XXXI_LINES).hasSize(DFHMDF_NAMED).isSorted();
            assertThat(COUSR03_XXXO_LINES).hasSize(DFHMDF_NAMED).isSorted();
            assertThat(COUSR03_BMS_LINES).hasSize(DFHMDF_NAMED).isSorted();

            for (int index = 1; index < DFHMDF_NAMED; index++) {
                assertThat(COUSR03_XXXI_LINES.get(index) - COUSR03_XXXI_LINES.get(index - 1))
                        .as("consecutive xxxI items are one six-line group apart")
                        .isEqualTo(CPY_LINES_PER_FIELD_GROUP);
                assertThat(COUSR03_XXXO_LINES.get(index) - COUSR03_XXXO_LINES.get(index - 1))
                        .as("and consecutive xxxO items likewise - FILLER, C, P, H, V, then xxxO")
                        .isEqualTo(CPY_LINES_PER_FIELD_GROUP);
            }
            assertThat(COUSR03_XXXO_LINES.get(0))
                    .as("the first xxxO item follows the group header at 85 and its FILLER at 86-91")
                    .isEqualTo(COUSR03_OUTPUT_GROUP_LINE + 7);
        }
    }

    static List<Arguments> fieldTriples() {
        List<Arguments> triples = new ArrayList<>();
        for (int index = 0; index < DFHMDF_NAMED; index++) {
            triples.add(Arguments.of(XXXO_ITEMS.get(index),
                    MAP_MEMBERS.get(index),
                    DECLARED_WIDTHS.get(index)));
        }
        return triples;
    }

    static List<Arguments> everyStringField() {
        return List.of(
                Arguments.of("TRNNAMEO", UserDeleteResponse.TRN_NAME_LENGTH,
                        (BiFunction<UserDeleteResponse, String, UserDeleteResponse>)
                                UserDeleteResponse::withTrnName,
                        (Function<UserDeleteResponse, String>) UserDeleteResponse::trnName),
                Arguments.of("TITLE01O", UserDeleteResponse.TITLE01_LENGTH,
                        (BiFunction<UserDeleteResponse, String, UserDeleteResponse>)
                                UserDeleteResponse::withTitle01,
                        (Function<UserDeleteResponse, String>) UserDeleteResponse::title01),
                Arguments.of("CURDATEO", UserDeleteResponse.CUR_DATE_LENGTH,
                        (BiFunction<UserDeleteResponse, String, UserDeleteResponse>)
                                UserDeleteResponse::withCurDate,
                        (Function<UserDeleteResponse, String>) UserDeleteResponse::curDate),
                Arguments.of("PGMNAMEO", UserDeleteResponse.PGM_NAME_LENGTH,
                        (BiFunction<UserDeleteResponse, String, UserDeleteResponse>)
                                UserDeleteResponse::withPgmName,
                        (Function<UserDeleteResponse, String>) UserDeleteResponse::pgmName),
                Arguments.of("TITLE02O", UserDeleteResponse.TITLE02_LENGTH,
                        (BiFunction<UserDeleteResponse, String, UserDeleteResponse>)
                                UserDeleteResponse::withTitle02,
                        (Function<UserDeleteResponse, String>) UserDeleteResponse::title02),
                Arguments.of("CURTIMEO", UserDeleteResponse.CUR_TIME_LENGTH,
                        (BiFunction<UserDeleteResponse, String, UserDeleteResponse>)
                                UserDeleteResponse::withCurTime,
                        (Function<UserDeleteResponse, String>) UserDeleteResponse::curTime),
                Arguments.of("USRIDINO", UserDeleteResponse.USR_ID_IN_LENGTH,
                        (BiFunction<UserDeleteResponse, String, UserDeleteResponse>)
                                UserDeleteResponse::withUsrIdIn,
                        (Function<UserDeleteResponse, String>) UserDeleteResponse::usrIdIn),
                Arguments.of("FNAMEO", UserDeleteResponse.F_NAME_LENGTH,
                        (BiFunction<UserDeleteResponse, String, UserDeleteResponse>)
                                UserDeleteResponse::withFName,
                        (Function<UserDeleteResponse, String>) UserDeleteResponse::fName),
                Arguments.of("LNAMEO", UserDeleteResponse.L_NAME_LENGTH,
                        (BiFunction<UserDeleteResponse, String, UserDeleteResponse>)
                                UserDeleteResponse::withLName,
                        (Function<UserDeleteResponse, String>) UserDeleteResponse::lName),
                Arguments.of("USRTYPEO", UserDeleteResponse.USR_TYPE_LENGTH,
                        (BiFunction<UserDeleteResponse, String, UserDeleteResponse>)
                                UserDeleteResponse::withUsrType,
                        (Function<UserDeleteResponse, String>) UserDeleteResponse::usrType),
                Arguments.of("ERRMSGO", UserDeleteResponse.ERR_MSG_LENGTH,
                        (BiFunction<UserDeleteResponse, String, UserDeleteResponse>)
                                UserDeleteResponse::withErrMsg,
                        (Function<UserDeleteResponse, String>) UserDeleteResponse::errMsg),
                Arguments.of("CDEMO-TO-PROGRAM", UserDeleteResponse.NEXT_PROGRAM_LENGTH,
                        (BiFunction<UserDeleteResponse, String, UserDeleteResponse>)
                                UserDeleteResponse::withNextProgram,
                        (Function<UserDeleteResponse, String>) UserDeleteResponse::nextProgram),
                Arguments.of("CDEMO-LAST-MAPSET", UserDeleteResponse.NEXT_MAPSET_LENGTH,
                        (BiFunction<UserDeleteResponse, String, UserDeleteResponse>)
                                UserDeleteResponse::withNextMapset,
                        (Function<UserDeleteResponse, String>) UserDeleteResponse::nextMapset),
                Arguments.of("CDEMO-LAST-MAP", UserDeleteResponse.NEXT_MAP_LENGTH,
                        (BiFunction<UserDeleteResponse, String, UserDeleteResponse>)
                                UserDeleteResponse::withNextMap,
                        (Function<UserDeleteResponse, String>) UserDeleteResponse::nextMap));
    }

    static List<Arguments> unsetReturnTargets() {
        return List.of(Arguments.of("SPACES", " ".repeat(TO_PROGRAM_WIDTH)),
                Arguments.of("LOW-VALUES", "\u0000".repeat(TO_PROGRAM_WIDTH)));
    }

    static List<Arguments> everyMessageText() {
        return List.of(Arguments.of(MSG_USER_ID_EMPTY, 179),
                Arguments.of(MSG_PRESS_PF5, 283),
                Arguments.of(MSG_UNABLE_TO_LOOKUP_USER, 296),
                Arguments.of(MSG_USER_ID_NOT_FOUND, 325),
                Arguments.of(MSG_UNABLE_TO_UPDATE_USER, 332));
    }
}

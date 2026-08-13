package com.vsergeychik.carddemo.user.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

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
import com.vsergeychik.carddemo.user.dto.UserListResponse.Row;
import com.vsergeychik.carddemo.user.model.SecUserRecord;
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
import java.util.LinkedHashMap;
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

/**
 * Unit tests for {@link UserListResponse} - the outbound payload of {@code GET /api/users}, CICS
 * transaction {@code CU00}, program {@code app/cbl/COUSR00C.cbl}, map {@code COUSR0A} of mapset
 * {@code COUSR00}.
 */
@DisplayName("UserListResponse - the CU00 list-users payload of COUSR00C")
class UserListResponseTest {
    private static final Charset MAP_CHARSET = StandardCharsets.US_ASCII;

    private static final String MAP_NAME = "COUSR0A";

    private static final String MAPSET_NAME = "COUSR00";

    private static final String TRANSACTION_ID = "CU00";

    private static final String PROGRAM_NAME = "COUSR00C";

    private static final int SCREEN_ROWS = 24;

    private static final int SCREEN_COLUMNS = 80;

    private static final int DFHMDF_TOTAL = 89;

    private static final int DFHMDF_NAMED = 59;

    private static final int DFHMDF_LITERALS = 30;

    private static final String USR_SEL_UPDATE = "U";

    private static final String USR_SEL_UPDATE_LOWER = "u";

    private static final String USR_SEL_DELETE = "D";

    private static final String USR_SEL_DELETE_LOWER = "d";

    private static final String TARGET_USER_UPDATE = "COUSR02C";

    private static final String TARGET_USER_DELETE = "COUSR03C";

    private static final String TARGET_SIGNON = "COSGN00C";

    private static final String INVALID_SELECTION_TEXT =
            "Invalid selection. Valid values are U and D";

    private static final String LOW_VALUE_FLAG = "\u0000";

    private static final String LOW_VALUE_USER_ID = "\u0000".repeat(8);

    private static final int TIOAPFX_PREFIX_LENGTH = 12;

    private static final int LENGTH_ITEM_LENGTH = 2;

    private static final int ATTRIBUTE_ITEM_LENGTH = 1;

    private static final int ATTRIBUTE_FILLER_LENGTH = 4;

    private static final int INPUT_FIELD_PREFIX_LENGTH =
            LENGTH_ITEM_LENGTH + ATTRIBUTE_ITEM_LENGTH + ATTRIBUTE_FILLER_LENGTH;

    private static final int OUTPUT_FILLER_LENGTH = 3;

    private static final int EXTATT_ITEM_LENGTH = 1;

    private static final int EXTATT_ITEM_COUNT = 4;

    private static final int OUTPUT_FIELD_PREFIX_LENGTH =
            OUTPUT_FILLER_LENGTH + EXTATT_ITEM_COUNT * EXTATT_ITEM_LENGTH;

    private static final int HEADER_WIDTH_TOTAL = 124;

    private static final int ROW_BAND_WIDTH_TOTAL = 500;

    private static final int TRAILER_WIDTH_TOTAL = 78;

    private static final int PAYLOAD_WIDTH_TOTAL =
            HEADER_WIDTH_TOTAL + ROW_BAND_WIDTH_TOTAL + TRAILER_WIDTH_TOTAL;

    private static final int SYMBOLIC_MAP_LENGTH = 1127;

    private static final int COPYBOOK_INPUT_GROUP_LINE = 17;

    private static final int COPYBOOK_GROUP_REDEFINES_LINE = 373;

    private static final int PER_FIELD_REDEFINES = 59;

    private static final int GROUP_LEVEL_REDEFINES = 1;

    private static final int COPYBOOK_REDEFINES_TOTAL =
            PER_FIELD_REDEFINES + GROUP_LEVEL_REDEFINES;

    private static final String LENGTH_ITEM_SUFFIX = "L";

    private static final String FLAG_ITEM_SUFFIX = "F";

    private static final String ATTRIBUTE_ITEM_SUFFIX = "A";

    private static final String INPUT_ITEM_SUFFIX = "I";

    private static final String COLOUR_ITEM_SUFFIX = "C";

    private static final String PS_ITEM_SUFFIX = "P";

    private static final String HIGHLIGHT_ITEM_SUFFIX = "H";

    private static final String VALIDATION_ITEM_SUFFIX = "V";

    private static final String OUTPUT_ITEM_SUFFIX = "O";

    private static final List<String> METADATA_ITEM_SUFFIXES = List.of(
            LENGTH_ITEM_SUFFIX, FLAG_ITEM_SUFFIX, ATTRIBUTE_ITEM_SUFFIX,
            COLOUR_ITEM_SUFFIX, PS_ITEM_SUFFIX, HIGHLIGHT_ITEM_SUFFIX, VALIDATION_ITEM_SUFFIX);

    private static final String FILLER_NAME = "FILLER";

    private static final int OCCURS_COUNT = 10;

    private static final int MAP_ROW_WIDTH = 50;

    private static final int FIRST_ROW_NUMBER = 1;

    private static final int LAST_ROW_NUMBER = 10;

    private static final int SEL_FIELD_DIGITS = 4;

    private static final int ROW_FIELD_DIGITS = 2;

    private static final int WS_USER_SEL_LENGTH = 1;

    private static final int WS_USER_ID_LENGTH = 8;

    private static final int WS_USER_NAME_LENGTH = 25;

    private static final int WS_USER_TYPE_LENGTH = 8;

    private static final int WS_USER_FILLER_LENGTH = 2;

    private static final int WS_USER_FILLER_COUNT = 3;

    private static final int WS_USER_REC_LENGTH = 48;

    private static final int WS_MESSAGE_LENGTH = 80;

    private static final int CU00_USRID_FIRST_LENGTH = 8;

    private static final int CU00_USRID_LAST_LENGTH = 8;

    private static final int CU00_PAGE_NUM_DIGITS = 8;

    private static final int CU00_NEXT_PAGE_FLG_LENGTH = 1;

    private static final int CU00_USR_SEL_FLG_LENGTH = 1;

    private static final int CU00_USR_SELECTED_LENGTH = 8;

    private static final int CU00_INFO_LENGTH = 34;

    private static final int CU00_INFO_ITEM_COUNT = 6;

    private static final String NEXT_PAGE_YES_VALUE = "Y";

    private static final String NEXT_PAGE_NO_VALUE = "N";

    private static final String CU00_FIELD_PREFIX = "CDEMO-CU00-";

    private static final int SEC_USR_ID_LENGTH = 8;

    private static final int SEC_USR_FNAME_LENGTH = 20;

    private static final int SEC_USR_LNAME_LENGTH = 20;

    private static final int SEC_USR_PWD_LENGTH = 8;

    private static final int SEC_USR_TYPE_LENGTH = 1;

    private static final int SEC_USR_FILLER_LENGTH = 23;

    private static final int SEC_USER_RECORD_LENGTH = 80;

    private static final String FIXTURE_NOT_A_PASSWORD = "NOTAPWD1";

    private static final Instant FIXED_INSTANT = Instant.parse("2022-07-19T23:15:57Z");

    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    /**
     * One {@code DFHMDF} field: the label the mapset gives it and the width its {@code PICTURE} has.
     *
     * @param name the label the mapset gives the field
     * @param width the width the receiving picture declares, in digits
     */
    private record MapField(String name, int width) {
    }

    private static final List<MapField> MAP_FIELDS = List.of(
            new MapField("TRNNAME", 4),
            new MapField("TITLE01", 40),
            new MapField("CURDATE", 8),
            new MapField("PGMNAME", 8),
            new MapField("TITLE02", 40),
            new MapField("CURTIME", 8),
            new MapField("PAGENUM", 8),
            new MapField("USRIDIN", 8),
            new MapField("SEL0001", 1), new MapField("USRID01", 8),
            new MapField("FNAME01", 20), new MapField("LNAME01", 20), new MapField("UTYPE01", 1),
            new MapField("SEL0002", 1), new MapField("USRID02", 8),
            new MapField("FNAME02", 20), new MapField("LNAME02", 20), new MapField("UTYPE02", 1),
            new MapField("SEL0003", 1), new MapField("USRID03", 8),
            new MapField("FNAME03", 20), new MapField("LNAME03", 20), new MapField("UTYPE03", 1),
            new MapField("SEL0004", 1), new MapField("USRID04", 8),
            new MapField("FNAME04", 20), new MapField("LNAME04", 20), new MapField("UTYPE04", 1),
            new MapField("SEL0005", 1), new MapField("USRID05", 8),
            new MapField("FNAME05", 20), new MapField("LNAME05", 20), new MapField("UTYPE05", 1),
            new MapField("SEL0006", 1), new MapField("USRID06", 8),
            new MapField("FNAME06", 20), new MapField("LNAME06", 20), new MapField("UTYPE06", 1),
            new MapField("SEL0007", 1), new MapField("USRID07", 8),
            new MapField("FNAME07", 20), new MapField("LNAME07", 20), new MapField("UTYPE07", 1),
            new MapField("SEL0008", 1), new MapField("USRID08", 8),
            new MapField("FNAME08", 20), new MapField("LNAME08", 20), new MapField("UTYPE08", 1),
            new MapField("SEL0009", 1), new MapField("USRID09", 8),
            new MapField("FNAME09", 20), new MapField("LNAME09", 20), new MapField("UTYPE09", 1),
            new MapField("SEL0010", 1), new MapField("USRID10", 8),
            new MapField("FNAME10", 20), new MapField("LNAME10", 20), new MapField("UTYPE10", 1),
            new MapField("ERRMSG", 78));

    private static final int HEADER_FIELD_COUNT = 8;

    private static final int TRAILER_FIELD_COUNT = 1;

    private static final List<String> EXPECTED_COMPONENT_NAMES = List.of(
            "trnName", "title01", "curDate", "pgmName", "title02", "curTime", "pageNum", "usrIdIn",
            "sel0001", "usrId01", "fname01", "lname01", "utype01",
            "sel0002", "usrId02", "fname02", "lname02", "utype02",
            "sel0003", "usrId03", "fname03", "lname03", "utype03",
            "sel0004", "usrId04", "fname04", "lname04", "utype04",
            "sel0005", "usrId05", "fname05", "lname05", "utype05",
            "sel0006", "usrId06", "fname06", "lname06", "utype06",
            "sel0007", "usrId07", "fname07", "lname07", "utype07",
            "sel0008", "usrId08", "fname08", "lname08", "utype08",
            "sel0009", "usrId09", "fname09", "lname09", "utype09",
            "sel0010", "usrId10", "fname10", "lname10", "utype10",
            "errMsg",
            "cdemoCu00UsrIdFirst", "cdemoCu00UsrIdLast", "cdemoCu00PageNum",
            "cdemoCu00NextPageFlg", "cdemoCu00UsrSelFlg", "cdemoCu00UsrSelected",
            "nextProgram", "nextMapset", "nextMap",
            "navigationContext");

    private static final java.util.Set<String> SCREEN_FIELD_MEMBERS =
            java.util.Set.copyOf(EXPECTED_COMPONENT_NAMES.subList(0, 59));

    private static String wireNameOf(String member) {
        return SCREEN_FIELD_MEMBERS.contains(member) ? member.toLowerCase(Locale.ROOT) : member;
    }

    private static List<String> wireNamesOf(List<String> members) {
        return members.stream().map(UserListResponseTest::wireNameOf).toList();
    }

    private static FixedWidthCodec codec() {
        return new FixedWidthCodec(MAP_CHARSET);
    }

    private static DateHeader dateHeader() {
        return DateHeader.from(codec(), FIXED_CLOCK);
    }

    private static ObjectMapper webConfigEquivalentMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        mapper.configure(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN, true);
        mapper.disable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT);
        return mapper;
    }

    private static NavigationContext listingContext() {
        FixedWidthCodec codec = codec();
        return NavigationContext.empty()
                .withFromTranid(codec.movePicX(TRANSACTION_ID, NavigationContext.FROM_TRANID_LENGTH))
                .withFromProgram(codec.movePicX(PROGRAM_NAME, NavigationContext.FROM_PROGRAM_LENGTH))
                .withUserId(codec.movePicX("ADMIN001", NavigationContext.USER_ID_LENGTH))
                .withUserTypeAdmin()
                .withPgmEnter()
                .withLastMap(codec.movePicX(MAP_NAME, NavigationContext.LAST_MAP_LENGTH))
                .withLastMapset(codec.movePicX(MAPSET_NAME, NavigationContext.LAST_MAPSET_LENGTH));
    }

    private static String userIdFor(int rowNumber) {
        FixedWidthCodec codec = codec();
        return codec.movePicX("USER00" + codec.movePic9(rowNumber, ROW_FIELD_DIGITS),
                UserListResponse.USRID_LENGTH);
    }

    private static String firstNameFor(int rowNumber) {
        FixedWidthCodec codec = codec();
        return codec.movePicX("First" + codec.movePic9(rowNumber, ROW_FIELD_DIGITS),
                UserListResponse.FNAME_LENGTH);
    }

    private static String lastNameFor(int rowNumber) {
        FixedWidthCodec codec = codec();
        return codec.movePicX("Last" + codec.movePic9(rowNumber, ROW_FIELD_DIGITS),
                UserListResponse.LNAME_LENGTH);
    }

    private static String userTypeFor(int rowNumber) {
        return rowNumber == FIRST_ROW_NUMBER
                ? NavigationContext.USER_TYPE_ADMIN
                : NavigationContext.USER_TYPE_USER;
    }

    private static UserListResponse populatedResponse() {
        FixedWidthCodec codec = codec();
        DateHeader header = dateHeader();
        UserListResponse.Builder builder = UserListResponse.builder()
                .trnName(codec.movePicX(TRANSACTION_ID, UserListResponse.TRNNAME_LENGTH))
                .title01(codec.movePicX(ScreenTitles.CCDA_TITLE01, UserListResponse.TITLE01_LENGTH))
                .curDate(codec.movePicX(header.wsCurdate(), UserListResponse.CURDATE_LENGTH))
                .pgmName(codec.movePicX(PROGRAM_NAME, UserListResponse.PGMNAME_LENGTH))
                .title02(codec.movePicX(ScreenTitles.CCDA_TITLE02, UserListResponse.TITLE02_LENGTH))
                .curTime(codec.movePicX(header.wsCurtime(), UserListResponse.CURTIME_LENGTH))
                .pageNum(codec.movePic9(1L, UserListResponse.PAGENUM_LENGTH))
                .usrIdIn(codec.movePicX("USER0001", UserListResponse.USRIDIN_LENGTH))
                .errMsg(codec.movePicX("", UserListResponse.ERRMSG_LENGTH))
                .cdemoCu00UsrIdFirst(codec.movePicX("USER0001", CU00_USRID_FIRST_LENGTH))
                .cdemoCu00UsrIdLast(codec.movePicX("USER0010", CU00_USRID_LAST_LENGTH))
                .cdemoCu00PageNum(1)
                .nextPageYes()
                .cdemoCu00UsrSelFlg(codec.movePicX("", CU00_USR_SEL_FLG_LENGTH))
                .cdemoCu00UsrSelected(codec.movePicX("", CU00_USR_SELECTED_LENGTH))
                .nextProgram(codec.movePicX(PROGRAM_NAME, UserListResponse.NEXT_PROGRAM_LENGTH))
                .nextMapset(codec.movePicX(MAPSET_NAME, UserListResponse.NEXT_MAPSET_LENGTH))
                .nextMap(codec.movePicX(MAP_NAME, UserListResponse.NEXT_MAP_LENGTH))
                .navigationContext(listingContext());
        for (int rowNumber = FIRST_ROW_NUMBER; rowNumber <= LAST_ROW_NUMBER; rowNumber++) {
            builder.populateRow(rowNumber, userIdFor(rowNumber), firstNameFor(rowNumber),
                    lastNameFor(rowNumber), userTypeFor(rowNumber));
        }
        return builder.build();
    }

    private static UserListResponse routedResponse(String selectionFlag, String selectedUserId,
                                                   String targetProgram) {
        FixedWidthCodec codec = codec();
        return populatedResponse().toBuilder()
                .cdemoCu00UsrSelFlg(codec.movePicX(selectionFlag, CU00_USR_SEL_FLG_LENGTH))
                .cdemoCu00UsrSelected(codec.movePicX(selectedUserId, CU00_USR_SELECTED_LENGTH))
                .nextProgram(codec.movePicX(targetProgram, UserListResponse.NEXT_PROGRAM_LENGTH))
                .navigationContext(listingContext()
                        .withToProgram(codec.movePicX(targetProgram,
                                NavigationContext.TO_PROGRAM_LENGTH))
                        .withFromTranid(codec.movePicX(TRANSACTION_ID,
                                NavigationContext.FROM_TRANID_LENGTH))
                        .withFromProgram(codec.movePicX(PROGRAM_NAME,
                                NavigationContext.FROM_PROGRAM_LENGTH))
                        .withPgmEnter())
                .build();
    }

    private static UserListResponse unroutedResponse(String selectionFlag, String selectedUserId) {
        FixedWidthCodec codec = codec();
        return populatedResponse().toBuilder()
                .cdemoCu00UsrSelFlg(codec.movePicX(selectionFlag, CU00_USR_SEL_FLG_LENGTH))
                .cdemoCu00UsrSelected(codec.movePicX(selectedUserId, CU00_USR_SELECTED_LENGTH))
                .errMsg(codec.movePicX(INVALID_SELECTION_TEXT, UserListResponse.ERRMSG_LENGTH))
                .nextProgram(codec.movePicX("", UserListResponse.NEXT_PROGRAM_LENGTH))
                .navigationContext(listingContext())
                .build();
    }

    private static Map<String, String> mapImages(UserListResponse response) {
        Map<String, String> images = new LinkedHashMap<>();
        images.put(UserListResponse.TRNNAME_FIELD, response.trnName());
        images.put(UserListResponse.TITLE01_FIELD, response.title01());
        images.put(UserListResponse.CURDATE_FIELD, response.curDate());
        images.put(UserListResponse.PGMNAME_FIELD, response.pgmName());
        images.put(UserListResponse.TITLE02_FIELD, response.title02());
        images.put(UserListResponse.CURTIME_FIELD, response.curTime());
        images.put(UserListResponse.PAGENUM_FIELD, response.pageNum());
        images.put(UserListResponse.USRIDIN_FIELD, response.usrIdIn());
        for (Row row : response.rows()) {
            int first = HEADER_FIELD_COUNT
                    + (row.rowNumber() - FIRST_ROW_NUMBER) * UserListResponse.ROW_FIELD_COUNT;
            images.put(MAP_FIELDS.get(first).name(), row.selection());
            images.put(MAP_FIELDS.get(first + 1).name(), row.userId());
            images.put(MAP_FIELDS.get(first + 2).name(), row.firstName());
            images.put(MAP_FIELDS.get(first + 3).name(), row.lastName());
            images.put(MAP_FIELDS.get(first + 4).name(), row.userType());
        }
        images.put(UserListResponse.ERRMSG_FIELD, response.errMsg());
        return images;
    }

    private static List<String> componentNames() {
        List<String> names = new ArrayList<>();
        for (RecordComponent component : UserListResponse.class.getRecordComponents()) {
            names.add(component.getName());
        }
        return names;
    }

    private static Set<String> jsonKeys(UserListResponse response) throws Exception {
        String json = webConfigEquivalentMapper().writeValueAsString(response);
        Map<String, Object> tree = webConfigEquivalentMapper()
                .readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
                });
        return new LinkedHashSet<>(tree.keySet());
    }

    private static FixedWidthRecord.RecordLayout inputViewLayout() {
        List<FixedWidthRecord.FieldSpan> spans = new ArrayList<>();
        spans.add(FixedWidthRecord.FieldSpan.filler(0, TIOAPFX_PREFIX_LENGTH));
        int cursor = TIOAPFX_PREFIX_LENGTH;
        for (MapField field : MAP_FIELDS) {
            spans.add(FixedWidthRecord.FieldSpan.filler(cursor, LENGTH_ITEM_LENGTH));
            cursor += LENGTH_ITEM_LENGTH;
            spans.add(FixedWidthRecord.FieldSpan.alphanumeric(
                    field.name() + FLAG_ITEM_SUFFIX, cursor, ATTRIBUTE_ITEM_LENGTH));
            cursor += ATTRIBUTE_ITEM_LENGTH;
            spans.add(FixedWidthRecord.FieldSpan.filler(cursor, ATTRIBUTE_FILLER_LENGTH));
            cursor += ATTRIBUTE_FILLER_LENGTH;
            spans.add(FixedWidthRecord.FieldSpan.alphanumeric(
                    field.name() + INPUT_ITEM_SUFFIX, cursor, field.width()));
            cursor += field.width();
        }
        return FixedWidthRecord.RecordLayout.of(SYMBOLIC_MAP_LENGTH,
                spans.toArray(FixedWidthRecord.FieldSpan[]::new));
    }

    private static FixedWidthRecord.RecordLayout outputViewLayout() {
        List<FixedWidthRecord.FieldSpan> spans = new ArrayList<>();
        spans.add(FixedWidthRecord.FieldSpan.filler(0, TIOAPFX_PREFIX_LENGTH));
        int cursor = TIOAPFX_PREFIX_LENGTH;
        for (MapField field : MAP_FIELDS) {
            spans.add(FixedWidthRecord.FieldSpan.filler(cursor, OUTPUT_FILLER_LENGTH));
            cursor += OUTPUT_FILLER_LENGTH;
            for (String suffix : List.of(COLOUR_ITEM_SUFFIX, PS_ITEM_SUFFIX,
                    HIGHLIGHT_ITEM_SUFFIX, VALIDATION_ITEM_SUFFIX)) {
                spans.add(FixedWidthRecord.FieldSpan.alphanumeric(
                        field.name() + suffix, cursor, EXTATT_ITEM_LENGTH));
                cursor += EXTATT_ITEM_LENGTH;
            }
            spans.add(FixedWidthRecord.FieldSpan.alphanumeric(
                    field.name() + OUTPUT_ITEM_SUFFIX, cursor, field.width()));
            cursor += field.width();
        }
        return FixedWidthRecord.RecordLayout.of(SYMBOLIC_MAP_LENGTH,
                spans.toArray(FixedWidthRecord.FieldSpan[]::new));
    }

    private static FixedWidthRecord.RecordLayout overlaidLayout() {
        List<FixedWidthRecord.FieldSpan> spans = new ArrayList<>(inputViewLayout().spans());
        spans.add(FixedWidthRecord.FieldSpan.redefining(FILLER_NAME, 0, TIOAPFX_PREFIX_LENGTH,
                FixedWidthRecord.PictureKind.FILLER));
        int cursor = TIOAPFX_PREFIX_LENGTH;
        for (MapField field : MAP_FIELDS) {
            spans.add(FixedWidthRecord.FieldSpan.redefining(FILLER_NAME, cursor,
                    OUTPUT_FILLER_LENGTH, FixedWidthRecord.PictureKind.FILLER));
            cursor += OUTPUT_FILLER_LENGTH;
            for (String suffix : List.of(COLOUR_ITEM_SUFFIX, PS_ITEM_SUFFIX,
                    HIGHLIGHT_ITEM_SUFFIX, VALIDATION_ITEM_SUFFIX)) {
                spans.add(FixedWidthRecord.FieldSpan.redefining(field.name() + suffix, cursor,
                        EXTATT_ITEM_LENGTH, FixedWidthRecord.PictureKind.ALPHANUMERIC));
                cursor += EXTATT_ITEM_LENGTH;
            }
            spans.add(FixedWidthRecord.FieldSpan.redefining(field.name() + OUTPUT_ITEM_SUFFIX, cursor,
                    field.width(), FixedWidthRecord.PictureKind.ALPHANUMERIC));
            cursor += field.width();
        }
        return FixedWidthRecord.RecordLayout.of(SYMBOLIC_MAP_LENGTH,
                spans.toArray(FixedWidthRecord.FieldSpan[]::new));
    }

    private static FixedWidthRecord.FieldSpan rowBandSpan() {
        return FixedWidthRecord.FieldSpan.alphanumeric("USER-ROW-BAND", 0, ROW_BAND_WIDTH_TOTAL);
    }

    @Nested
    @DisplayName("The field inventory - exactly 59 map-derived members and 10 more")
    class FieldInventory {
        @Test
        @DisplayName("the census is 8 header and paging + 50 row + 1 message = 59")
        void theCensusIsFiftyNine() {
            assertThat(HEADER_FIELD_COUNT + OCCURS_COUNT * UserListResponse.ROW_FIELD_COUNT
                    + TRAILER_FIELD_COUNT)
                    .as("8 + 10 x 5 + 1")
                    .isEqualTo(DFHMDF_NAMED);
            assertThat(MAP_FIELDS).hasSize(DFHMDF_NAMED);
            assertThat(UserListResponse.MAP_FIELD_COUNT).isEqualTo(DFHMDF_NAMED);
        }

        @Test
        @DisplayName("the mapset holds 89 DFHMDF definitions of which only 59 are name-labelled")
        void thirtyDefinitionsAreScreenFurniture() {
            assertThat(DFHMDF_TOTAL - DFHMDF_NAMED)
                    .as("app/bms/COUSR00.bms literal captions, headings and the key legend")
                    .isEqualTo(DFHMDF_LITERALS);
            assertThat(UserListResponse.MAP_FIELD_COUNT)
                    .as("a payload field is a labelled DFHMDF; furniture is not a field")
                    .isEqualTo(DFHMDF_NAMED)
                    .isLessThan(DFHMDF_TOTAL);
        }

        @Test
        @DisplayName("69 components: 59 map-derived, 6 CU00 items, 3 XCTL replacements, 1 commarea")
        void sixtyNineComponents() {
            assertThat(UserListResponse.class.getRecordComponents()).hasSize(
                    UserListResponse.COMPONENT_COUNT);
            assertThat(UserListResponse.COMPONENT_COUNT)
                    .isEqualTo(DFHMDF_NAMED + CU00_INFO_ITEM_COUNT + 3 + 1)
                    .isEqualTo(69);
        }

        @Test
        @DisplayName("the components appear in the layout's own order, the 59 map fields first")
        void componentsFollowTheMapOrder() {
            assertThat(componentNames())
                    .as("declaration order is not cosmetic: the payload projects a byte layout")
                    .containsExactlyElementsOf(EXPECTED_COMPONENT_NAMES);
        }

        @Test
        @DisplayName("FIELD_NAMES is the copybook's own list, in the copybook's own order")
        void fieldNamesAreTheCopybookList() {
            List<String> transcribed = new ArrayList<>();
            for (MapField field : MAP_FIELDS) {
                transcribed.add(field.name());
            }
            assertThat(UserListResponse.FIELD_NAMES).containsExactlyElementsOf(transcribed);
        }

        @Test
        @DisplayName("the selection column is spelled with four digits, the data columns with two")
        void theRowItemNamesKeepTheirSourceSpelling() {
            FixedWidthCodec codec = codec();
            for (int rowNumber = FIRST_ROW_NUMBER; rowNumber <= LAST_ROW_NUMBER; rowNumber++) {
                String four = codec.movePic9(rowNumber, SEL_FIELD_DIGITS);
                String two = codec.movePic9(rowNumber, ROW_FIELD_DIGITS);
                assertThat(UserListResponse.FIELD_NAMES)
                        .as("row %d of app/cpy-bms/COUSR00.CPY", rowNumber)
                        .contains("SEL" + four, "USRID" + two, "FNAME" + two, "LNAME" + two,
                                "UTYPE" + two)
                        .doesNotContain("SEL" + two, "USRID" + four, "FNAME" + four,
                                "LNAME" + four, "UTYPE" + four);
            }
        }

        @Test
        @DisplayName("USRIDIN keeps its own spelling; COUSR01's USERID is not harmonised in")
        void usrIdInIsNotHarmonised() {
            assertThat(UserListResponse.USRIDIN_FIELD).isEqualTo("USRIDIN");
            assertThat(UserListResponse.FIELD_NAMES).doesNotContain("USERID");
            assertThat(componentNames()).contains("usrIdIn").doesNotContain("userId");
        }

        @Test
        @DisplayName("no xxxL, xxxF, xxxA, xxxC, xxxP, xxxH or xxxV item is a member")
        void noMetadataItemIsAMember() {
            Set<String> upperCased = new LinkedHashSet<>();
            for (String component : componentNames()) {
                upperCased.add(component.toUpperCase(Locale.ROOT));
            }
            for (MapField field : MAP_FIELDS) {
                for (String suffix : METADATA_ITEM_SUFFIXES) {
                    assertThat(upperCased)
                            .as("%s%s is control information, not payload", field.name(), suffix)
                            .doesNotContain(field.name() + suffix);
                }
            }
        }

        @Test
        @DisplayName("no FILLER and no staging-area field is a member")
        void noFillerIsAMember() {
            Set<String> upperCased = new LinkedHashSet<>();
            for (String component : componentNames()) {
                upperCased.add(component.toUpperCase(Locale.ROOT));
            }
            assertThat(upperCased).doesNotContain(FILLER_NAME, "USER-REC", "USERREC", "WS-USER-DATA");
        }

        @Test
        @DisplayName("the field-name list is immutable, so exposing it shares no mutable state")
        void theFieldNameListIsImmutable() {
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> UserListResponse.FIELD_NAMES.add("SEL0011"));
            assertThat(UserListResponse.blank().fieldNames())
                    .isSameAs(UserListResponse.FIELD_NAMES);
        }
    }

    @Nested
    @DisplayName("Declared widths, read off the PICTURE clauses")
    class DeclaredWidths {
        @Test
        @DisplayName("the header widths are 4, 40, 8, 8, 40, 8, 8 and 8, summing to 124")
        void theHeaderWidths() {
            assertThat(List.of(UserListResponse.TRNNAME_LENGTH, UserListResponse.TITLE01_LENGTH,
                    UserListResponse.CURDATE_LENGTH, UserListResponse.PGMNAME_LENGTH,
                    UserListResponse.TITLE02_LENGTH, UserListResponse.CURTIME_LENGTH,
                    UserListResponse.PAGENUM_LENGTH, UserListResponse.USRIDIN_LENGTH))
                    .containsExactly(4, 40, 8, 8, 40, 8, 8, 8);
            int total = 0;
            for (int index = 0; index < HEADER_FIELD_COUNT; index++) {
                total += MAP_FIELDS.get(index).width();
            }
            assertThat(total).isEqualTo(HEADER_WIDTH_TOTAL);
        }

        @Test
        @DisplayName("CURTIME is 8 here, not the 9 that only COSGN00 declares")
        void curTimeIsEightNotNine() {
            assertThat(UserListResponse.CURTIME_LENGTH)
                    .as("app/cpy-bms/COUSR00.CPY - CURTIMEO PIC X(8); COSGN00's own is X(9)")
                    .isEqualTo(8)
                    .isEqualTo(DateHeader.WS_CURTIME_LENGTH)
                    .isNotEqualTo(9);
            assertThat(UserListResponse.CURDATE_LENGTH).isEqualTo(DateHeader.WS_CURDATE_LENGTH);
        }

        @Test
        @DisplayName("the header the fixed clock renders fits CURDATE and CURTIME exactly")
        void theRenderedHeaderFitsItsFields() {
            DateHeader header = dateHeader();
            assertThat(header.wsCurdate()).hasSize(UserListResponse.CURDATE_LENGTH);
            assertThat(header.wsCurtime()).hasSize(UserListResponse.CURTIME_LENGTH);
            UserListResponse response = populatedResponse();
            assertThat(response.curDate()).isEqualTo(header.wsCurdate());
            assertThat(response.curTime()).isEqualTo(header.wsCurtime());
        }

        @Test
        @DisplayName("ERRMSG is 78, and WS-MESSAGE that feeds it is 80")
        void errMsgIsSeventyEightNotEighty() {
            assertThat(UserListResponse.ERRMSG_LENGTH).isEqualTo(78);
            assertThat(WS_MESSAGE_LENGTH).isEqualTo(80);
            assertThat(WS_MESSAGE_LENGTH - UserListResponse.ERRMSG_LENGTH)
                    .as("MOVE WS-MESSAGE TO ERRMSGO at app/cbl/COUSR00C.cbl:526 loses two characters")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("the 80-to-78 MOVE truncates on the right, and the codec is what does it")
        void theMessageMoveTruncatesOnTheRight() {
            FixedWidthCodec codec = codec();
            String wsMessage = codec.movePicX("A".repeat(UserListResponse.ERRMSG_LENGTH) + "YZ",
                    WS_MESSAGE_LENGTH);
            assertThat(wsMessage).hasSize(WS_MESSAGE_LENGTH).endsWith("YZ");

            String moved = codec.movePicX(wsMessage, UserListResponse.ERRMSG_LENGTH);

            assertThat(moved)
                    .as("a PIC X receiver fills from the left and discards the overflow")
                    .hasSize(UserListResponse.ERRMSG_LENGTH)
                    .isEqualTo("A".repeat(UserListResponse.ERRMSG_LENGTH))
                    .doesNotEndWith("YZ");
            assertThat(UserListResponse.blank().toBuilder().errMsg(moved).build().errMsg())
                    .isEqualTo(moved);
        }

        @Test
        @DisplayName("an 80-character message is rejected here: the payload never truncates silently")
        void anEightyCharacterMessageIsRejected() {
            String wsMessage = codec().movePicX(INVALID_SELECTION_TEXT, WS_MESSAGE_LENGTH);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> UserListResponse.blank().toBuilder().errMsg(wsMessage).build())
                    .withMessageContaining(UserListResponse.ERRMSG_FIELD)
                    .withMessageContaining("truncate");
        }

        @Test
        @DisplayName("the invalid-selection message is carried verbatim and padded to 78")
        void theInvalidSelectionMessageIsCarriedVerbatim() {
            assertThat(UserListResponse.INVALID_SELECTION_MESSAGE).isEqualTo(INVALID_SELECTION_TEXT);
            String padded = codec().movePicX(INVALID_SELECTION_TEXT, UserListResponse.ERRMSG_LENGTH);
            assertThat(padded)
                    .as("the codec pads a PIC X receiver on the right, byte for byte")
                    .hasSize(UserListResponse.ERRMSG_LENGTH)
                    .isEqualTo(INVALID_SELECTION_TEXT + " ".repeat(
                            UserListResponse.ERRMSG_LENGTH - INVALID_SELECTION_TEXT.length()));
        }

        @Test
        @DisplayName("the row-cell widths are 1, 8, 20, 20 and 1, summing to 50 and 500")
        void theRowCellWidths() {
            assertThat(List.of(UserListResponse.SEL_LENGTH, UserListResponse.USRID_LENGTH,
                    UserListResponse.FNAME_LENGTH, UserListResponse.LNAME_LENGTH,
                    UserListResponse.UTYPE_LENGTH))
                    .containsExactly(1, 8, 20, 20, 1);
            assertThat(UserListResponse.SEL_LENGTH + UserListResponse.USRID_LENGTH
                    + UserListResponse.FNAME_LENGTH + UserListResponse.LNAME_LENGTH
                    + UserListResponse.UTYPE_LENGTH).isEqualTo(MAP_ROW_WIDTH);
            assertThat(MAP_ROW_WIDTH * OCCURS_COUNT).isEqualTo(ROW_BAND_WIDTH_TOTAL);
        }

        @Test
        @DisplayName("the 59 declared widths sum to the 702 data bytes of the symbolic map")
        void theDeclaredWidthsSumToTheDataBytes() {
            int total = 0;
            for (MapField field : MAP_FIELDS) {
                total += field.width();
            }
            assertThat(total)
                    .as("124 header and paging + 500 rows + 78 message")
                    .isEqualTo(PAYLOAD_WIDTH_TOTAL)
                    .isEqualTo(702);
        }

        @Test
        @DisplayName("the symbolic map is 1127 bytes: 12 + 7 x 59 + 702")
        void theSymbolicMapIsElevenTwentySeven() {
            assertThat(TIOAPFX_PREFIX_LENGTH + INPUT_FIELD_PREFIX_LENGTH * DFHMDF_NAMED
                    + PAYLOAD_WIDTH_TOTAL)
                    .isEqualTo(SYMBOLIC_MAP_LENGTH);
            assertThat(UserListResponse.SYMBOLIC_MAP_LENGTH).isEqualTo(SYMBOLIC_MAP_LENGTH);
        }

        @Test
        @DisplayName("blank() fills every one of the 59 fields to exactly its declared width")
        void blankFillsEveryFieldToItsDeclaredWidth() {
            Map<String, String> images = mapImages(UserListResponse.blank());
            assertThat(images).hasSize(DFHMDF_NAMED);
            for (MapField field : MAP_FIELDS) {
                assertThat(images.get(field.name()))
                        .as("%s PIC X(%d)", field.name(), field.width())
                        .hasSize(field.width())
                        .isEqualTo(ScreenFieldImage.unpainted(field.width()));
            }
        }

        @Test
        @DisplayName("blank() carries the copybook VALUE 'N' and a zero page number")
        void blankCarriesTheDeclaredValues() {
            UserListResponse blank = UserListResponse.blank();
            assertThat(blank.cdemoCu00NextPageFlg()).isEqualTo(NEXT_PAGE_NO_VALUE);
            assertThat(blank.nextPageNo()).isTrue();
            assertThat(blank.nextPageYes()).isFalse();
            assertThat(blank.cdemoCu00PageNum()).isZero();
            assertThat(blank.navigationContext()).isEqualTo(NavigationContext.empty());
        }

        @Test
        @DisplayName("a value wider than its PICTURE is rejected, never truncated")
        void anOverWideValueIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> UserListResponse.blank().toBuilder()
                            .trnName(TRANSACTION_ID + "X").build())
                    .withMessageContaining(UserListResponse.TRNNAME_FIELD)
                    .withMessageContaining("PIC X(" + UserListResponse.TRNNAME_LENGTH + ")");
        }

        @Test
        @DisplayName("a shorter value is accepted unchanged: padding belongs to the codec")
        void aShorterValueIsAcceptedUnchanged() {
            UserListResponse response = UserListResponse.blank().toBuilder()
                    .usrIdIn("ADMIN").build();
            assertThat(response.usrIdIn()).isEqualTo("ADMIN").hasSize(5);
        }

        @Test
        @DisplayName("null is rejected: a COBOL screen field holds spaces, never null")
        void nullIsRejected() {
            assertThatNullPointerException()
                    .isThrownBy(() -> UserListResponse.blank().toBuilder().errMsg(null).build())
                    .withMessageContaining(UserListResponse.ERRMSG_FIELD);
        }

        @Test
        @DisplayName("the communication area is not optional")
        void theCommareaIsNotOptional() {
            assertThatNullPointerException()
                    .isThrownBy(() -> UserListResponse.blank().toBuilder()
                            .navigationContext(null).build())
                    .withMessageContaining("CARDDEMO-COMMAREA");
        }
    }

    @Nested
    @DisplayName("The group-level overlay - 01 COUSR0AO REDEFINES COUSR0AI")
    class GroupRedefinesOverlay {
        @Test
        @DisplayName("the copybook holds 60 REDEFINES: 59 per-field and this one at the group level")
        void theCopybookHoldsSixtyRedefines() {
            assertThat(PER_FIELD_REDEFINES)
                    .as("02 FILLER REDEFINES xxxF - UserListRequestTest's subject")
                    .isEqualTo(DFHMDF_NAMED);
            assertThat(GROUP_LEVEL_REDEFINES)
                    .as("01 COUSR0AO REDEFINES COUSR0AI at line %d - this file's subject",
                            COPYBOOK_GROUP_REDEFINES_LINE)
                    .isOne();
            assertThat(COPYBOOK_REDEFINES_TOTAL)
                    .as("no gap and no double count between the two suites")
                    .isEqualTo(60);
            assertThat(COPYBOOK_GROUP_REDEFINES_LINE).isGreaterThan(COPYBOOK_INPUT_GROUP_LINE);
        }

        @Test
        @DisplayName("both per-field prefixes are seven bytes, which is what makes the overlay legal")
        void bothPrefixesAreSevenBytes() {
            assertThat(INPUT_FIELD_PREFIX_LENGTH)
                    .as("xxxL 2 + xxxF 1 + FILLER X(4)")
                    .isEqualTo(7);
            assertThat(OUTPUT_FIELD_PREFIX_LENGTH)
                    .as("FILLER X(3) + xxxC + xxxP + xxxH + xxxV")
                    .isEqualTo(7)
                    .isEqualTo(INPUT_FIELD_PREFIX_LENGTH);
        }

        @Test
        @DisplayName("both views tile the same 1127 bytes")
        void bothViewsTileTheSameBytes() {
            FixedWidthRecord.RecordLayout input = inputViewLayout();
            FixedWidthRecord.RecordLayout output = outputViewLayout();
            assertThat(input.recordLength()).isEqualTo(SYMBOLIC_MAP_LENGTH);
            assertThat(output.recordLength()).isEqualTo(SYMBOLIC_MAP_LENGTH);

            int inputBytes = 0;
            for (FixedWidthRecord.FieldSpan span : input.storageSpans()) {
                inputBytes += span.length();
            }
            int outputBytes = 0;
            for (FixedWidthRecord.FieldSpan span : output.storageSpans()) {
                outputBytes += span.length();
            }
            assertThat(inputBytes).isEqualTo(SYMBOLIC_MAP_LENGTH).isEqualTo(outputBytes);
        }

        @Test
        @DisplayName("each view accounts for every byte: prefix, metadata and data")
        void eachViewAccountsForEveryByte() {
            assertThat(TIOAPFX_PREFIX_LENGTH
                    + DFHMDF_NAMED * (LENGTH_ITEM_LENGTH + ATTRIBUTE_FILLER_LENGTH)
                    + DFHMDF_NAMED * ATTRIBUTE_ITEM_LENGTH
                    + PAYLOAD_WIDTH_TOTAL)
                    .as("input view: 12 + 59 x 6 FILLER + 59 flag bytes + 702 data")
                    .isEqualTo(SYMBOLIC_MAP_LENGTH);
            assertThat(TIOAPFX_PREFIX_LENGTH
                    + DFHMDF_NAMED * OUTPUT_FILLER_LENGTH
                    + DFHMDF_NAMED * EXTATT_ITEM_COUNT * EXTATT_ITEM_LENGTH
                    + PAYLOAD_WIDTH_TOTAL)
                    .as("output view: 12 + 59 x 3 FILLER + 59 x 4 attribute bytes + 702 data")
                    .isEqualTo(SYMBOLIC_MAP_LENGTH);
        }

        @Test
        @DisplayName("the two views align field for field, with zero drift across all 59 fields")
        void theTwoViewsAlignFieldForField() {
            FixedWidthRecord.RecordLayout input = inputViewLayout();
            FixedWidthRecord.RecordLayout output = outputViewLayout();
            for (MapField field : MAP_FIELDS) {
                FixedWidthRecord.FieldSpan inputItem = input.span(field.name() + INPUT_ITEM_SUFFIX);
                FixedWidthRecord.FieldSpan outputItem = output.span(field.name() + OUTPUT_ITEM_SUFFIX);
                assertThat(outputItem.offset())
                        .as("%sO must shadow %sI exactly", field.name(), field.name())
                        .isEqualTo(inputItem.offset());
                assertThat(outputItem.length())
                        .as("%sO width", field.name())
                        .isEqualTo(inputItem.length())
                        .isEqualTo(field.width());
            }
        }

        @Test
        @DisplayName("the output view is declared as an overlay of the input view, not as storage")
        void theOutputViewIsAnOverlay() {
            FixedWidthRecord.RecordLayout overlaid = overlaidLayout();
            assertThat(overlaid.recordLength()).isEqualTo(SYMBOLIC_MAP_LENGTH);
            assertThat(overlaid.storageSpans())
                    .as("only 01 COUSR0AI occupies storage")
                    .containsExactlyElementsOf(inputViewLayout().storageSpans());
            assertThat(overlaid.redefinitions())
                    .as("1 prefix FILLER + 59 x (1 FILLER + 4 attribute items + 1 data item)")
                    .hasSize(1 + DFHMDF_NAMED * (1 + EXTATT_ITEM_COUNT + 1));
            for (FixedWidthRecord.FieldSpan overlay : overlaid.redefinitions()) {
                assertThat(overlay.redefinition()).isTrue();
                assertThat(overlay.endOffsetExclusive())
                        .as("%s must stay inside the storage it redefines", overlay.describe())
                        .isLessThanOrEqualTo(SYMBOLIC_MAP_LENGTH);
            }
        }

        @Test
        @DisplayName("the EXTATT=YES quartet is declared for every field, immediately before its data")
        void theExtattQuartetIsDeclaredForEveryField() {
            FixedWidthRecord.RecordLayout overlaid = overlaidLayout();
            List<String> quartet = List.of(COLOUR_ITEM_SUFFIX, PS_ITEM_SUFFIX,
                    HIGHLIGHT_ITEM_SUFFIX, VALIDATION_ITEM_SUFFIX);
            assertThat(quartet).hasSize(EXTATT_ITEM_COUNT);
            for (MapField field : MAP_FIELDS) {
                FixedWidthRecord.FieldSpan dataItem = overlaid.span(
                        field.name() + OUTPUT_ITEM_SUFFIX);
                for (int position = 0; position < quartet.size(); position++) {
                    FixedWidthRecord.FieldSpan attribute = overlaid.span(
                            field.name() + quartet.get(position));
                    assertThat(attribute.length())
                            .as("%s%s is one byte", field.name(), quartet.get(position))
                            .isEqualTo(EXTATT_ITEM_LENGTH);
                    assertThat(attribute.offset())
                            .as("%s%s sits %d byte(s) before %sO", field.name(),
                                    quartet.get(position), EXTATT_ITEM_COUNT - position,
                                    field.name())
                            .isEqualTo(dataItem.offset() - EXTATT_ITEM_COUNT + position);
                }
            }
        }

        @ParameterizedTest(name = "{0} round-trips through both views")
        @CsvSource({
            "TRNNAME, CU00",
            "USRID05, USER0005",
            "FNAME05, Fifth",
            "ERRMSG, Invalid selection. Valid values are U and D"
        })
        @DisplayName("a value written through one view is readable through the other, and back")
        void aValueWrittenThroughOneViewIsReadableThroughTheOther(String fieldName, String value) {
            FixedWidthCodec codec = codec();
            FixedWidthRecord.RecordLayout layout = overlaidLayout();
            FixedWidthRecord.FieldSpan inputItem = layout.span(fieldName + INPUT_ITEM_SUFFIX);
            FixedWidthRecord.FieldSpan outputItem = layout.span(fieldName + OUTPUT_ITEM_SUFFIX);
            String image = codec.movePicX(value, inputItem.length());

            FixedWidthRecord record = codec.newRecord(layout);
            record.writeSpan(inputItem, image);

            assertThat(record.readSpan(outputItem))
                    .as("%sI and %sO are one span, so the O view sees what the I view wrote",
                            fieldName, fieldName)
                    .isEqualTo(image);

            String replacement = codec.movePicX("", inputItem.length());
            record.writeSpan(outputItem, replacement);

            assertThat(record.readSpan(inputItem))
                    .as("and back again - the overlay is symmetric")
                    .isEqualTo(replacement);
        }

        @Test
        @DisplayName("the views share one byte array: what one writes the other reads from the bytes")
        void theViewsShareOneByteArray() {
            FixedWidthCodec codec = codec();
            FixedWidthRecord.RecordLayout input = inputViewLayout();
            FixedWidthRecord written = codec.newRecord(input);
            String image = codec.movePicX(PROGRAM_NAME, UserListResponse.PGMNAME_LENGTH);
            written.writeSpan(input.span("PGMNAME" + INPUT_ITEM_SUFFIX), image);

            byte[] bytes = written.toByteArray();
            assertThat(bytes).hasSize(SYMBOLIC_MAP_LENGTH);

            FixedWidthRecord reread = codec.wrap(bytes, outputViewLayout());

            assertThat(reread.readSpan(outputViewLayout().span("PGMNAME" + OUTPUT_ITEM_SUFFIX)))
                    .as("one storage area, two layouts - not two independent encodings")
                    .isEqualTo(image);
        }

        @Test
        @DisplayName("the overlay is why COUSR00C writes an input item on an output path")
        void theOverlayIsWhyTheProgramWritesAnInputItemOnAnOutputPath() {
            FixedWidthRecord.RecordLayout input = inputViewLayout();
            FixedWidthRecord.RecordLayout output = outputViewLayout();
            assertThat(output.span("PAGENUM" + OUTPUT_ITEM_SUFFIX).offset())
                    .isEqualTo(input.span("PAGENUM" + INPUT_ITEM_SUFFIX).offset());
            assertThat(output.span("USRIDIN" + OUTPUT_ITEM_SUFFIX).offset())
                    .isEqualTo(input.span("USRIDIN" + INPUT_ITEM_SUFFIX).offset());
            assertThat(output.span("ERRMSG" + OUTPUT_ITEM_SUFFIX).offset())
                    .isEqualTo(input.span("ERRMSG" + INPUT_ITEM_SUFFIX).offset());
        }

        @Test
        @DisplayName("the attribute quartet is metadata and never reaches the serialised payload")
        void theAttributeQuartetNeverReachesThePayload() throws Exception {
            Set<String> keys = jsonKeys(populatedResponse());
            Set<String> upperCased = new LinkedHashSet<>();
            for (String key : keys) {
                upperCased.add(key.toUpperCase(Locale.ROOT));
            }
            for (MapField field : MAP_FIELDS) {
                for (String suffix : METADATA_ITEM_SUFFIXES) {
                    assertThat(upperCased)
                            .as("%s%s belongs to the 3270 datastream, not to JSON",
                                    field.name(), suffix)
                            .doesNotContain(field.name() + suffix);
                }
            }
        }

        @Test
        @DisplayName("the 12-byte prefix and the per-field FILLERs are not exposed either")
        void thePrefixAndFillersAreNotExposed() throws Exception {
            for (String key : jsonKeys(populatedResponse())) {
                assertThat(key.toUpperCase(Locale.ROOT))
                        .as("no FILLER, TIOAPFX or attribute span is a payload member")
                        .doesNotContain(FILLER_NAME)
                        .doesNotContain("TIOAPFX");
            }
            assertThat(componentNames()).hasSize(UserListResponse.COMPONENT_COUNT);
            assertThat(TIOAPFX_PREFIX_LENGTH + DFHMDF_NAMED * OUTPUT_FILLER_LENGTH)
                    .as("189 bytes of the output view are FILLER and carry no payload")
                    .isEqualTo(189);
        }

        @Test
        @DisplayName("the colour item the overlay adds is what FieldAttributeSetter writes to")
        void theColourItemIsTheOverlaysOwn() {
            assertThat(FieldAttributeSetter.COLOUR_ITEM_SUFFIX).isEqualTo(COLOUR_ITEM_SUFFIX);
            assertThat(FieldAttributeSetter.OUTPUT_MAP_SUFFIX).isEqualTo(OUTPUT_ITEM_SUFFIX);
            assertThat(overlaidLayout().hasSpan("ERRMSG" + COLOUR_ITEM_SUFFIX)).isTrue();
            assertThat(overlaidLayout().span("ERRMSG" + COLOUR_ITEM_SUFFIX).length())
                    .isEqualTo(EXTATT_ITEM_LENGTH);
        }
    }

    @Nested
    @DisplayName("The ten rows - blanked never omitted, and row 1 is element 0")
    class TenRows {
        @Test
        @DisplayName("ten rows is behaviour, not configuration")
        void tenRowsIsBehaviourNotConfiguration() {
            assertThat(UserListResponse.ROW_COUNT)
                    .as("OCCURS 10 TIMES at :57, UNTIL WS-IDX > 10 at :293 and :347, "
                            + "UNTIL WS-IDX >= 11 at :300, MOVE 10 TO WS-IDX at :352")
                    .isEqualTo(OCCURS_COUNT)
                    .isEqualTo(10);
            assertThat(UserListResponse.ROW_FIELD_COUNT).isEqualTo(5);

            List<String> settingLikeNames = new ArrayList<>();
            for (Method method : UserListResponse.class.getMethods()) {
                settingLikeNames.add(method.getName().toLowerCase(Locale.ROOT));
            }
            for (Method method : UserListResponse.Builder.class.getMethods()) {
                settingLikeNames.add(method.getName().toLowerCase(Locale.ROOT));
            }
            assertThat(settingLikeNames)
                    .as("no page-size setter, constructor parameter or configuration key")
                    .doesNotContain("pagesize", "setpagesize", "rowcount", "setrowcount", "limit");
        }

        @Test
        @DisplayName("rows() always returns exactly ten rows, numbered 1 to 10")
        void rowsAlwaysReturnsTenRows() {
            List<Row> rows = populatedResponse().rows();
            assertThat(rows).hasSize(OCCURS_COUNT);
            for (int index = 0; index < rows.size(); index++) {
                assertThat(rows.get(index).rowNumber())
                        .as("element %d carries its own COBOL row number", index)
                        .isEqualTo(index + 1);
            }
        }

        @Test
        @DisplayName("element 0 is row 1 and element 9 is row 10 - COBOL counts from one")
        void elementZeroIsRowOneAndElementNineIsRowTen() {
            UserListResponse response = populatedResponse();
            List<Row> rows = response.rows();

            assertThat(rows.get(0).rowNumber()).isEqualTo(FIRST_ROW_NUMBER);
            assertThat(rows.get(0).userId()).isEqualTo(userIdFor(FIRST_ROW_NUMBER));
            assertThat(rows.get(0)).isEqualTo(response.row(FIRST_ROW_NUMBER));

            assertThat(rows.get(OCCURS_COUNT - 1).rowNumber()).isEqualTo(LAST_ROW_NUMBER);
            assertThat(rows.get(OCCURS_COUNT - 1).userId()).isEqualTo(userIdFor(LAST_ROW_NUMBER));
            assertThat(rows.get(OCCURS_COUNT - 1)).isEqualTo(response.row(LAST_ROW_NUMBER));
        }

        @Test
        @DisplayName("every row number from 1 to 10 resolves to its own five cells")
        void everyRowNumberResolvesToItsOwnCells() {
            UserListResponse response = populatedResponse();
            for (int rowNumber = FIRST_ROW_NUMBER; rowNumber <= LAST_ROW_NUMBER; rowNumber++) {
                Row row = response.row(rowNumber);
                assertThat(row.userId()).isEqualTo(userIdFor(rowNumber));
                assertThat(row.firstName()).isEqualTo(firstNameFor(rowNumber));
                assertThat(row.lastName()).isEqualTo(lastNameFor(rowNumber));
                assertThat(row.userType()).isEqualTo(userTypeFor(rowNumber));
                assertThat(row.selection()).hasSize(UserListResponse.SEL_LENGTH);
            }
        }

        @ParameterizedTest(name = "row {0} does not exist")
        @ValueSource(ints = {-1, 0, 11, 100})
        @DisplayName("a row number outside 1 to 10 is rejected, not clamped")
        void anOutOfRangeRowNumberIsRejected(int rowNumber) {
            UserListResponse response = populatedResponse();
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> response.row(rowNumber))
                    .withMessageContaining("Row " + rowNumber)
                    .withMessageContaining(MAP_NAME);
        }

        @ParameterizedTest(name = "the builder rejects row {0}")
        @ValueSource(ints = {0, 11})
        @DisplayName("the builder rejects an out-of-range row on every row method")
        void theBuilderRejectsAnOutOfRangeRow(int rowNumber) {
            assertThatIllegalArgumentException().isThrownBy(() -> UserListResponse.builder()
                    .populateRow(rowNumber, userIdFor(FIRST_ROW_NUMBER),
                            firstNameFor(FIRST_ROW_NUMBER), lastNameFor(FIRST_ROW_NUMBER),
                            NavigationContext.USER_TYPE_USER));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> UserListResponse.builder().blankRow(rowNumber));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> UserListResponse.builder()
                            .selection(rowNumber, USR_SEL_UPDATE));
        }

        @Test
        @DisplayName("populateRow writes the four data cells and leaves the selection cell alone")
        void populateRowLeavesTheSelectionAlone() {
            UserListResponse response = UserListResponse.builder()
                    .selection(3, USR_SEL_UPDATE)
                    .populateRow(3, userIdFor(3), firstNameFor(3), lastNameFor(3), userTypeFor(3))
                    .navigationContext(listingContext())
                    .build();

            Row row = response.row(3);
            assertThat(row.selection())
                    .as("SEL000n is echoed user input; INITIALIZE-USER-DATA never touches it")
                    .isEqualTo(USR_SEL_UPDATE);
            assertThat(row.userId()).isEqualTo(userIdFor(3));
            assertThat(row.blankRow()).isFalse();
        }

        @Test
        @DisplayName("blankRow blanks the four data cells and leaves the selection cell alone")
        void blankRowLeavesTheSelectionAlone() {
            UserListResponse response = populatedResponse().toBuilder()
                    .selection(4, USR_SEL_DELETE)
                    .blankRow(4)
                    .build();

            Row row = response.row(4);
            assertThat(row.selection()).isEqualTo(USR_SEL_DELETE);
            assertThat(row.userId()).hasSize(UserListResponse.USRID_LENGTH).isBlank();
            assertThat(row.firstName()).hasSize(UserListResponse.FNAME_LENGTH).isBlank();
            assertThat(row.lastName()).hasSize(UserListResponse.LNAME_LENGTH).isBlank();
            assertThat(row.userType()).hasSize(UserListResponse.UTYPE_LENGTH).isBlank();
            assertThat(row.blankRow()).isTrue();
        }

        @Test
        @DisplayName("a short final page carries ten rows with the tail blank, never null")
        void aShortFinalPageStillCarriesTenRows() {
            int found = 3;
            UserListResponse.Builder builder = UserListResponse.builder()
                    .navigationContext(listingContext())
                    .nextPageNo();
            for (int rowNumber = FIRST_ROW_NUMBER; rowNumber <= found; rowNumber++) {
                builder.populateRow(rowNumber, userIdFor(rowNumber), firstNameFor(rowNumber),
                        lastNameFor(rowNumber), userTypeFor(rowNumber));
            }
            UserListResponse response = builder.build();

            assertThat(response.rows()).hasSize(OCCURS_COUNT).doesNotContainNull();
            for (int rowNumber = FIRST_ROW_NUMBER; rowNumber <= LAST_ROW_NUMBER; rowNumber++) {
                Row row = response.row(rowNumber);
                if (rowNumber <= found) {
                    assertThat(row.blankRow()).isFalse();
                } else {
                    assertThat(row.blankRow())
                            .as("row %d found no user, so it holds nothing - spaces where "
                                    + "INITIALIZE-USER-DATA reached it, LOW-VALUES where the map clear "
                                    + "at :117 is all that touched it", rowNumber)
                            .isTrue();
                    assertThat(row.userId())
                            .isNotNull()
                            .satisfies(id -> assertThat(ScreenFieldImage.isSpacesOrLowValues(id))
                                    .isTrue());
                }
            }
        }

        @ParameterizedTest(name = "blankRow() is {4} for id=[{0}] first=[{1}] last=[{2}] type=[{3}]")
        @CsvSource(nullValues = "@", value = {
            "'        ', '                    ', '                    ', ' ', true",
            "'USER0001', '                    ', '                    ', ' ', false",
            "'        ', 'First               ', '                    ', ' ', false",
            "'        ', '                    ', 'Last                ', ' ', false",
            "'        ', '                    ', '                    ', 'U', false",
            "'USER0001', 'First               ', 'Last                ', 'A', false"
        })
        @DisplayName("blankRow() is true only when all four data cells are blank")
        void theBlankRowPredicateDrivesAllFourConditions(String userId, String firstName,
                                                        String lastName, String userType,
                                                        boolean expected) {
            UserListResponse response = UserListResponse.builder()
                    .populateRow(FIRST_ROW_NUMBER, userId, firstName, lastName, userType)
                    .navigationContext(listingContext())
                    .build();

            assertThat(response.row(FIRST_ROW_NUMBER).blankRow()).isEqualTo(expected);
        }

        @Test
        @DisplayName("the one-based subscript conversion is carried by the shared OCCURS helper")
        void theSubscriptConversionIsCarriedByTheSharedHelper() {
            FixedWidthRecord.FieldSpan band = rowBandSpan();
            assertThat(band.length()).isEqualTo(ROW_BAND_WIDTH_TOTAL);

            assertThat(FixedWidthRecord.occursElementOffsetOneBased(band.offset(), MAP_ROW_WIDTH,
                    OCCURS_COUNT, FIRST_ROW_NUMBER))
                    .as("COBOL row 1 is the first element")
                    .isZero();
            assertThat(FixedWidthRecord.occursElementOffsetOneBased(band.offset(), MAP_ROW_WIDTH,
                    OCCURS_COUNT, LAST_ROW_NUMBER))
                    .as("COBOL row 10 begins 9 elements in")
                    .isEqualTo((LAST_ROW_NUMBER - 1) * MAP_ROW_WIDTH);

            assertThat(FixedWidthRecord.occursElementSpan(band, OCCURS_COUNT, FIRST_ROW_NUMBER,
                    "USER-REC", FixedWidthRecord.PictureKind.ALPHANUMERIC).length())
                    .isEqualTo(MAP_ROW_WIDTH);
        }

        @ParameterizedTest(name = "OCCURS subscript {0} is out of range")
        @ValueSource(ints = {0, 11})
        @DisplayName("an OCCURS subscript of 0 or 11 is rejected: COBOL has no element zero")
        void anOutOfRangeSubscriptIsRejected(int subscript) {
            FixedWidthRecord.FieldSpan band = rowBandSpan();
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> FixedWidthRecord.occursElementOffsetOneBased(band.offset(),
                            MAP_ROW_WIDTH, OCCURS_COUNT, subscript))
                    .withMessageContaining("1.." + OCCURS_COUNT);
        }
    }

    @Nested
    @DisplayName("Row widths - from CSUSR01Y, not from WS-USER-DATA")
    class RowWidthsComeFromCsusr01y {
        @Test
        @DisplayName("the four data widths are the security record's own 8, 20, 20 and 1")
        void theDataWidthsAreTheSecurityRecordsOwn() {
            assertThat(UserListResponse.USRID_LENGTH)
                    .isEqualTo(SEC_USR_ID_LENGTH)
                    .isEqualTo(SecUserRecord.SEC_USR_ID_LENGTH);
            assertThat(UserListResponse.FNAME_LENGTH)
                    .isEqualTo(SEC_USR_FNAME_LENGTH)
                    .isEqualTo(SecUserRecord.SEC_USR_FNAME_LENGTH);
            assertThat(UserListResponse.LNAME_LENGTH)
                    .isEqualTo(SEC_USR_LNAME_LENGTH)
                    .isEqualTo(SecUserRecord.SEC_USR_LNAME_LENGTH);
            assertThat(UserListResponse.UTYPE_LENGTH)
                    .isEqualTo(SEC_USR_TYPE_LENGTH)
                    .isEqualTo(SecUserRecord.SEC_USR_TYPE_LENGTH);
            assertThat(UserListResponse.SEL_LENGTH)
                    .as("the selection cell is the screen's own; no copybook declares it")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("the security record is 80 bytes at offsets 0, 8, 28, 48, 56 and 57")
        void theSecurityRecordGeometry() {
            assertThat(SecUserRecord.RECORD_LENGTH).isEqualTo(SEC_USER_RECORD_LENGTH);
            assertThat(List.of(SecUserRecord.SEC_USR_ID_OFFSET, SecUserRecord.SEC_USR_FNAME_OFFSET,
                    SecUserRecord.SEC_USR_LNAME_OFFSET, SecUserRecord.SEC_USR_PWD_OFFSET,
                    SecUserRecord.SEC_USR_TYPE_OFFSET, SecUserRecord.SEC_USR_FILLER_OFFSET))
                    .containsExactly(0, 8, 28, 48, 56, 57);
            assertThat(SEC_USR_ID_LENGTH + SEC_USR_FNAME_LENGTH + SEC_USR_LNAME_LENGTH
                    + SEC_USR_PWD_LENGTH + SEC_USR_TYPE_LENGTH + SEC_USR_FILLER_LENGTH)
                    .isEqualTo(SEC_USER_RECORD_LENGTH);
        }

        @Test
        @DisplayName("only four of the record's six items are projected: not the password, not FILLER")
        void thePasswordAndFillerAreNotProjected() throws Exception {
            SecUserRecord stored = SecUserRecord.of(userIdFor(FIRST_ROW_NUMBER), "First", "Last",
                    FIXTURE_NOT_A_PASSWORD, NavigationContext.USER_TYPE_ADMIN, MAP_CHARSET);
            byte[] image = SecUserRecord.encode(stored, MAP_CHARSET);
            assertThat(image).hasSize(SEC_USER_RECORD_LENGTH);
            SecUserRecord reread = SecUserRecord.decode(image, MAP_CHARSET);

            FixedWidthCodec codec = codec();
            UserListResponse response = populatedResponse().toBuilder()
                    .populateRow(FIRST_ROW_NUMBER,
                            codec.movePicX(reread.secUsrId(), UserListResponse.USRID_LENGTH),
                            codec.movePicX(reread.secUsrFname(), UserListResponse.FNAME_LENGTH),
                            codec.movePicX(reread.secUsrLname(), UserListResponse.LNAME_LENGTH),
                            codec.movePicX(reread.secUsrType(), UserListResponse.UTYPE_LENGTH))
                    .build();

            assertThat(mapImages(response).values())
                    .as("SEC-USR-PWD at offset 48 has no screen field to be written to")
                    .noneSatisfy(value -> assertThat(value).contains(FIXTURE_NOT_A_PASSWORD));
            assertThat(webConfigEquivalentMapper().writeValueAsString(response))
                    .doesNotContain(FIXTURE_NOT_A_PASSWORD);
            assertThat(UserListResponse.FIELD_NAMES)
                    .doesNotContain(SecUserRecord.FIELD_SEC_USR_PWD,
                            SecUserRecord.FIELD_SEC_USR_FILLER);
            assertThat(response.row(FIRST_ROW_NUMBER).userType())
                    .isEqualTo(NavigationContext.USER_TYPE_ADMIN);
        }

        @Test
        @DisplayName("the internal display table is 48 bytes of 25 and 8 - and is NOT the projection")
        void theInternalDisplayTableIsNotTheProjection() {
            assertThat(WS_USER_SEL_LENGTH + WS_USER_FILLER_COUNT * WS_USER_FILLER_LENGTH
                    + WS_USER_ID_LENGTH + WS_USER_NAME_LENGTH + WS_USER_TYPE_LENGTH)
                    .as("app/cbl/COUSR00C.cbl:56-64 - one USER-REC of WS-USER-DATA")
                    .isEqualTo(WS_USER_REC_LENGTH);
            assertThat(WS_USER_REC_LENGTH)
                    .as("the staging row and the map row are different widths, and stay so")
                    .isNotEqualTo(MAP_ROW_WIDTH);
            assertThat(UserListResponse.FNAME_LENGTH)
                    .as("USER-NAME X(25) is one field for both names; the map has two of 20")
                    .isNotEqualTo(WS_USER_NAME_LENGTH);
            assertThat(UserListResponse.UTYPE_LENGTH)
                    .as("USER-TYPE X(08) is a display column; UTYPEnn is one character")
                    .isNotEqualTo(WS_USER_TYPE_LENGTH);
        }

        @Test
        @DisplayName("a 25-character staging name narrows to the map's 20 on the right")
        void aStagingNameNarrowsToTheMapWidth() {
            FixedWidthCodec codec = codec();
            String staged = codec.movePicX("ABCDEFGHIJKLMNOPQRSTUVWXY", WS_USER_NAME_LENGTH);
            assertThat(staged).hasSize(WS_USER_NAME_LENGTH);

            String projected = codec.movePicX(staged, UserListResponse.FNAME_LENGTH);

            assertThat(projected)
                    .hasSize(UserListResponse.FNAME_LENGTH)
                    .isEqualTo("ABCDEFGHIJKLMNOPQRST");
        }
    }

    @Nested
    @DisplayName("The CU00 paging context - 34 bytes on top of a 160-byte commarea")
    class Cu00CommareaExtension {
        @Test
        @DisplayName("all six items of CDEMO-CU00-INFO are carried, on this payload")
        void allSixItemsAreCarried() {
            assertThat(componentNames()).contains("cdemoCu00UsrIdFirst", "cdemoCu00UsrIdLast",
                    "cdemoCu00PageNum", "cdemoCu00NextPageFlg", "cdemoCu00UsrSelFlg",
                    "cdemoCu00UsrSelected");
            assertThat(List.of(UserListResponse.CU00_USRID_FIRST_FIELD,
                    UserListResponse.CU00_USRID_LAST_FIELD, UserListResponse.CU00_PAGE_NUM_FIELD,
                    UserListResponse.CU00_NEXT_PAGE_FLG_FIELD,
                    UserListResponse.CU00_USR_SEL_FLG_FIELD,
                    UserListResponse.CU00_USR_SELECTED_FIELD))
                    .hasSize(CU00_INFO_ITEM_COUNT)
                    .allSatisfy(name -> assertThat(name).startsWith(CU00_FIELD_PREFIX));
        }

        @Test
        @DisplayName("the six items sum to 34, so the CU00 communication area is 194 bytes")
        void theSixItemsSumToThirtyFour() {
            assertThat(CU00_USRID_FIRST_LENGTH + CU00_USRID_LAST_LENGTH + CU00_PAGE_NUM_DIGITS
                    + CU00_NEXT_PAGE_FLG_LENGTH + CU00_USR_SEL_FLG_LENGTH
                    + CU00_USR_SELECTED_LENGTH)
                    .isEqualTo(CU00_INFO_LENGTH)
                    .isEqualTo(UserListResponse.CU00_INFO_LENGTH);

            FixedWidthCodec codec = codec();
            UserListResponse response = populatedResponse();
            String extensionImage =
                    codec.movePicX(response.cdemoCu00UsrIdFirst(), CU00_USRID_FIRST_LENGTH)
                            + codec.movePicX(response.cdemoCu00UsrIdLast(), CU00_USRID_LAST_LENGTH)
                            + codec.movePic9(response.cdemoCu00PageNum(), CU00_PAGE_NUM_DIGITS)
                            + codec.movePicX(response.cdemoCu00NextPageFlg(),
                                    CU00_NEXT_PAGE_FLG_LENGTH)
                            + codec.movePicX(response.cdemoCu00UsrSelFlg(),
                                    CU00_USR_SEL_FLG_LENGTH)
                            + codec.movePicX(response.cdemoCu00UsrSelected(),
                                    CU00_USR_SELECTED_LENGTH);
            assertThat(extensionImage).hasSize(CU00_INFO_LENGTH);

            byte[] commarea = response.navigationContext().toFixedWidth(codec);
            assertThat(commarea)
                    .as("CDEMO-GENERAL 34 + CUSTOMER 84 + ACCOUNT 12 + CARD 16 + MORE 14")
                    .hasSize(NavigationContext.COMMAREA_LENGTH);
            assertThat(NavigationContext.COMMAREA_LENGTH).isEqualTo(160);
            assertThat(commarea.length + extensionImage.length())
                    .isEqualTo(UserListResponse.CU00_COMMAREA_LENGTH)
                    .isEqualTo(194);
        }

        @Test
        @DisplayName("the 160 bytes are the copybook's five blocks: 34 + 84 + 12 + 16 + 14")
        void theCommareaIsTheCopybooksFiveBlocks() {
            assertThat(NavigationContext.GENERAL_INFO_LENGTH).isEqualTo(34);
            assertThat(NavigationContext.CUSTOMER_INFO_LENGTH).isEqualTo(84);
            assertThat(NavigationContext.ACCOUNT_INFO_LENGTH).isEqualTo(12);
            assertThat(NavigationContext.CARD_INFO_LENGTH).isEqualTo(16);
            assertThat(NavigationContext.MORE_INFO_LENGTH).isEqualTo(14);
            assertThat(NavigationContext.GENERAL_INFO_LENGTH
                    + NavigationContext.CUSTOMER_INFO_LENGTH
                    + NavigationContext.ACCOUNT_INFO_LENGTH
                    + NavigationContext.CARD_INFO_LENGTH
                    + NavigationContext.MORE_INFO_LENGTH)
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH);
            assertThat(NavigationContext.COMMAREA_LENGTH + CU00_INFO_LENGTH)
                    .as("the CU00 extension is additional to the shared area, not carved out of it")
                    .isEqualTo(UserListResponse.CU00_COMMAREA_LENGTH);
        }

        @Test
        @DisplayName("the extension is not folded into the 160-byte communication area")
        void theExtensionIsNotFoldedIntoTheCommarea() {
            List<String> commareaComponents = new ArrayList<>();
            for (RecordComponent component : NavigationContext.class.getRecordComponents()) {
                commareaComponents.add(component.getName().toLowerCase(Locale.ROOT));
            }
            assertThat(commareaComponents)
                    .as("CARDDEMO-COMMAREA is shared by all 17 screens and stays exactly 160 bytes")
                    .noneSatisfy(name -> assertThat(name).contains("cu00"))
                    .doesNotContain("pagenum", "nextpageflg", "usrselflg", "usrselected");
        }

        @Test
        @DisplayName("the extension prefix is this screen's own, not CU02's and not CU03's")
        void theExtensionPrefixIsThisScreensOwn() {
            for (String name : List.of(UserListResponse.CU00_USRID_FIRST_FIELD,
                    UserListResponse.CU00_USRID_LAST_FIELD, UserListResponse.CU00_PAGE_NUM_FIELD,
                    UserListResponse.CU00_NEXT_PAGE_FLG_FIELD,
                    UserListResponse.CU00_USR_SEL_FLG_FIELD,
                    UserListResponse.CU00_USR_SELECTED_FIELD)) {
                assertThat(name).startsWith(CU00_FIELD_PREFIX)
                        .doesNotContain("CDEMO-CU02-")
                        .doesNotContain("CDEMO-CU03-");
            }
        }

        @Test
        @DisplayName("the page number is an int, because PIC 9(08) is unsigned and unscaled")
        void thePageNumberIsIntegral() throws Exception {
            RecordComponent pageNumber = null;
            for (RecordComponent component : UserListResponse.class.getRecordComponents()) {
                if ("cdemoCu00PageNum".equals(component.getName())) {
                    pageNumber = component;
                }
            }
            assertThat(pageNumber).isNotNull();
            assertThat(pageNumber.getType()).isEqualTo(int.class);
            assertThat(UserListResponse.CU00_PAGE_NUM_DIGITS).isEqualTo(CU00_PAGE_NUM_DIGITS);
        }

        @Test
        @DisplayName("no component and no method of this payload is double or float")
        void noFloatingPointAnywhere() {
            for (RecordComponent component : UserListResponse.class.getRecordComponents()) {
                assertThat(component.getType())
                        .as("component %s", component.getName())
                        .isNotEqualTo(double.class).isNotEqualTo(float.class)
                        .isNotEqualTo(Double.class).isNotEqualTo(Float.class);
            }
            for (Class<?> type : List.of(UserListResponse.class, Row.class,
                    UserListResponse.Builder.class)) {
                for (Method method : type.getDeclaredMethods()) {
                    assertThat(method.getReturnType())
                            .as("%s.%s return type", type.getSimpleName(), method.getName())
                            .isNotEqualTo(double.class).isNotEqualTo(float.class);
                    for (Class<?> parameter : method.getParameterTypes()) {
                        assertThat(parameter)
                                .as("%s.%s parameter", type.getSimpleName(), method.getName())
                                .isNotEqualTo(double.class).isNotEqualTo(float.class);
                    }
                }
            }
        }

        @Test
        @DisplayName("a negative page number is rejected: the picture has no sign position")
        void aNegativePageNumberIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> UserListResponse.blank().toBuilder()
                            .cdemoCu00PageNum(-1).build())
                    .withMessageContaining(UserListResponse.CU00_PAGE_NUM_FIELD)
                    .withMessageContaining("unsigned");
        }

        @Test
        @DisplayName("a nine-digit page number is stored with its high-order digit dropped, as a PIC "
                + "9(08) receiver drops it")
        void aNineDigitPageNumberIsTruncatedOnTheLeft() {
            assertThat(UserListResponse.blank().toBuilder()
                    .cdemoCu00PageNum(100_000_000).build().cdemoCu00PageNum())
                    .as("100000000 keeps its low-order eight digits, which are all zero")
                    .isZero();
            assertThat(UserListResponse.blank().toBuilder()
                    .cdemoCu00PageNum(123_456_789).build().cdemoCu00PageNum())
                    .as("the leading 1 is the digit that does not fit")
                    .isEqualTo(23_456_789);
            assertThat(UserListResponse.blank().toBuilder()
                    .cdemoCu00PageNum(99_999_999).build().cdemoCu00PageNum())
                    .as("the widest value that fits is stored unchanged")
                    .isEqualTo(99_999_999);
        }

        @ParameterizedTest(name = "page {0} renders as {1}")
        @CsvSource({
            "0, 00000000",
            "1, 00000001",
            "42, 00000042",
            "99999999, 99999999"
        })
        @DisplayName("the page number renders as a zero-filled eight-character image")
        void thePageNumberRendersZeroFilled(int pageNumber, String expectedImage) {
            String image = codec().movePic9(pageNumber, UserListResponse.PAGENUM_LENGTH);
            assertThat(image).isEqualTo(expectedImage).hasSize(UserListResponse.PAGENUM_LENGTH);

            UserListResponse response = populatedResponse().toBuilder()
                    .cdemoCu00PageNum(pageNumber)
                    .pageNum(image)
                    .build();

            assertThat(response.cdemoCu00PageNum()).isEqualTo(pageNumber);
            assertThat(response.pageNum()).isEqualTo(expectedImage);
        }

        @Test
        @DisplayName("PAGENUM the screen field and CDEMO-CU00-PAGE-NUM the number are distinct")
        void theScreenFieldAndTheNumberAreDistinct() {
            assertThat(componentNames()).contains("pageNum", "cdemoCu00PageNum");
            UserListResponse response = populatedResponse();
            assertThat(response.pageNum()).isInstanceOf(String.class);
            assertThat(response.cdemoCu00PageNum()).isEqualTo(1);
            assertThat(response.pageNum())
                    .isEqualTo(codec().movePic9(response.cdemoCu00PageNum(),
                            UserListResponse.PAGENUM_LENGTH));
        }

        @ParameterizedTest(name = "flag [{0}] gives NEXT-PAGE-YES={1} and NEXT-PAGE-NO={2}")
        @CsvSource({
            "Y, true, false",
            "N, false, true",
            "' ', false, false"
        })
        @DisplayName("both 88-level conditions are driven, and a third value satisfies neither")
        void bothConditionsOfTheNextPageFlagAreDriven(String flag, boolean yes, boolean no) {
            UserListResponse response = populatedResponse().toBuilder()
                    .cdemoCu00NextPageFlg(flag)
                    .build();

            assertThat(response.nextPageYes()).isEqualTo(yes);
            assertThat(response.nextPageNo()).isEqualTo(no);
            assertThat(response.nextPageYes() && response.nextPageNo())
                    .as("neither 88 is the negation of the other: the pair is not exhaustive")
                    .isFalse();
        }

        @Test
        @DisplayName("the builder's two shortcuts set the copybook's own literals")
        void theBuilderShortcutsSetTheDeclaredLiterals() {
            assertThat(UserListResponse.NEXT_PAGE_YES).isEqualTo(NEXT_PAGE_YES_VALUE);
            assertThat(UserListResponse.NEXT_PAGE_NO).isEqualTo(NEXT_PAGE_NO_VALUE);
            assertThat(UserListResponse.builder().navigationContext(listingContext())
                    .nextPageYes().build().cdemoCu00NextPageFlg())
                    .isEqualTo(NEXT_PAGE_YES_VALUE);
            assertThat(UserListResponse.builder().navigationContext(listingContext())
                    .nextPageNo().build().cdemoCu00NextPageFlg())
                    .isEqualTo(NEXT_PAGE_NO_VALUE);
        }

        @Test
        @DisplayName("the browse cursors and the selection survive a round trip unchanged")
        void theBrowseCursorsSurviveARoundTrip() {
            FixedWidthCodec codec = codec();
            UserListResponse response = populatedResponse().toBuilder()
                    .cdemoCu00UsrIdFirst(codec.movePicX("USER0011", CU00_USRID_FIRST_LENGTH))
                    .cdemoCu00UsrIdLast(codec.movePicX("USER0020", CU00_USRID_LAST_LENGTH))
                    .cdemoCu00UsrSelFlg(USR_SEL_UPDATE)
                    .cdemoCu00UsrSelected(codec.movePicX("USER0013", CU00_USR_SELECTED_LENGTH))
                    .build();

            assertThat(response.cdemoCu00UsrIdFirst()).isEqualTo("USER0011");
            assertThat(response.cdemoCu00UsrIdLast()).isEqualTo("USER0020");
            assertThat(response.cdemoCu00UsrSelFlg()).isEqualTo(USR_SEL_UPDATE);
            assertThat(response.cdemoCu00UsrSelected()).isEqualTo("USER0013");
            assertThat(response.toBuilder().build()).isEqualTo(response);
        }
    }

    @Nested
    @DisplayName("Selection routing - what XCTL became")
    class SelectionRouting {
        private String selectionFlagOf(String kind) {
            return switch (kind) {
                case "UPDATE" -> USR_SEL_UPDATE;
                case "BLANK" -> codec().movePicX("", CU00_USR_SEL_FLG_LENGTH);
                case "LOW" -> LOW_VALUE_FLAG;
                default -> throw new IllegalArgumentException("Unknown flag kind " + kind);
            };
        }

        private String selectedUserOf(String kind) {
            return switch (kind) {
                case "USER" -> userIdFor(FIRST_ROW_NUMBER);
                case "BLANK" -> codec().movePicX("", CU00_USR_SELECTED_LENGTH);
                case "LOW" -> LOW_VALUE_USER_ID;
                default -> throw new IllegalArgumentException("Unknown selection kind " + kind);
            };
        }

        @Test
        @DisplayName("the XCTL target became nextProgram, nextMapset and nextMap")
        void theXctlTargetBecameResponseFields() {
            assertThat(componentNames()).contains("nextProgram", "nextMapset", "nextMap");
            assertThat(UserListResponse.NEXT_PROGRAM_LENGTH)
                    .isEqualTo(NavigationContext.TO_PROGRAM_LENGTH).isEqualTo(8);
            assertThat(UserListResponse.NEXT_MAPSET_LENGTH)
                    .isEqualTo(NavigationContext.LAST_MAPSET_LENGTH).isEqualTo(7);
            assertThat(UserListResponse.NEXT_MAP_LENGTH)
                    .isEqualTo(NavigationContext.LAST_MAP_LENGTH).isEqualTo(7);
        }

        @ParameterizedTest(name = "selection [{0}] routes to {1}")
        @CsvSource({
            "U, COUSR02C",
            "u, COUSR02C",
            "D, COUSR03C",
            "d, COUSR03C"
        })
        @DisplayName("U and u update, D and d delete - four separate WHEN arms, not a case fold")
        void theSelectionRoutesToUpdateOrDelete(String flag, String target) {
            UserListResponse response =
                    routedResponse(flag, userIdFor(FIRST_ROW_NUMBER), target);

            assertThat(response.nextProgram()).isEqualTo(target)
                    .hasSize(UserListResponse.NEXT_PROGRAM_LENGTH);
            assertThat(response.cdemoCu00UsrSelFlg())
                    .as("the source lists 'U', 'u', 'D' and 'd' as four labels, so case is kept")
                    .isEqualTo(flag);
            assertThat(response.cdemoCu00UsrSelected()).isEqualTo(userIdFor(FIRST_ROW_NUMBER));
            assertThat(response.navigationContext().toProgram()).isEqualTo(target);
            assertThat(response.errMsg())
                    .as("a successful transfer carries no message")
                    .isBlank();
        }

        @Test
        @DisplayName("the two documented targets are the COBOL's own literals")
        void theTargetsAreTheCobolsOwnLiterals() {
            assertThat(UserListResponse.NEXT_PROGRAM_USER_UPDATE).isEqualTo(TARGET_USER_UPDATE);
            assertThat(UserListResponse.NEXT_PROGRAM_USER_DELETE).isEqualTo(TARGET_USER_DELETE);
            assertThat(UserListResponse.NEXT_PROGRAM_SIGNON).isEqualTo(TARGET_SIGNON);
            assertThat(List.of(TARGET_USER_UPDATE, TARGET_USER_DELETE, TARGET_SIGNON))
                    .allSatisfy(target -> assertThat(target)
                            .hasSize(UserListResponse.NEXT_PROGRAM_LENGTH));
        }

        @ParameterizedTest(name = "each arm stamps the from fields for target {0}")
        @CsvSource({"COUSR02C", "COUSR03C"})
        @DisplayName("each arm also stamps CDEMO-FROM-TRANID, CDEMO-FROM-PROGRAM and context 0")
        void eachArmStampsTheFromFieldsAndTheEnterContext(String target) {
            UserListResponse response =
                    routedResponse(USR_SEL_UPDATE, userIdFor(FIRST_ROW_NUMBER), target);
            NavigationContext context = response.navigationContext();

            assertThat(context.fromTranid()).isEqualTo(TRANSACTION_ID);
            assertThat(context.fromProgram()).isEqualTo(PROGRAM_NAME);
            assertThat(context.pgmContext()).isEqualTo(NavigationContext.PGM_CONTEXT_ENTER);
            assertThat(context.isEnter()).isTrue();
            assertThat(context.isReenter()).isFalse();
        }

        @ParameterizedTest(name = "selection [{0}] transfers nothing and reports the message")
        @CsvSource({"X", "1", "A", "z"})
        @DisplayName("WHEN OTHER sets a message and performs no program transfer")
        void anUnrecognisedSelectionTransfersNothing(String flag) {
            UserListResponse response = unroutedResponse(flag, userIdFor(FIRST_ROW_NUMBER));

            assertThat(response.nextProgram())
                    .as("no XCTL is issued at app/cbl/COUSR00C.cbl:210-214")
                    .isBlank()
                    .hasSize(UserListResponse.NEXT_PROGRAM_LENGTH);
            assertThat(response.nextProgram().trim())
                    .isNotEqualTo(TARGET_USER_UPDATE)
                    .isNotEqualTo(TARGET_USER_DELETE);
            assertThat(response.errMsg())
                    .startsWith(INVALID_SELECTION_TEXT)
                    .hasSize(UserListResponse.ERRMSG_LENGTH);
            assertThat(response.cdemoCu00UsrSelFlg()).isEqualTo(flag);
        }

        @ParameterizedTest(name = "flag={0} selected={1} routes={2}")
        @CsvSource({
            "UPDATE, USER,  true",
            "BLANK,  USER,  false",
            "UPDATE, BLANK, false",
            "BLANK,  BLANK, false",
            "LOW,    USER,  false",
            "UPDATE, LOW,   false"
        })
        @DisplayName("the guard needs both operands non-blank and non-low-values")
        void theGuardNeedsBothOperands(String flagKind, String selectedKind, boolean routes) {
            String flag = selectionFlagOf(flagKind);
            String selected = selectedUserOf(selectedKind);

            UserListResponse response = routes
                    ? routedResponse(flag, selected, TARGET_USER_UPDATE)
                    : populatedResponse().toBuilder()
                            .cdemoCu00UsrSelFlg(flag)
                            .cdemoCu00UsrSelected(selected)
                            .nextProgram(codec().movePicX("",
                                    UserListResponse.NEXT_PROGRAM_LENGTH))
                            .build();

            if (routes) {
                assertThat(response.nextProgram()).isEqualTo(TARGET_USER_UPDATE);
            } else {
                assertThat(response.nextProgram()).isBlank();
                assertThat(response.errMsg())
                        .as("the guard failing is silent: no message is set either")
                        .isBlank();
            }
            assertThat(response.cdemoCu00UsrSelFlg()).isEqualTo(flag);
            assertThat(response.cdemoCu00UsrSelected()).isEqualTo(selected);
        }

        @Test
        @DisplayName("the map and mapset are seven characters, which is why X(7) is correct")
        void theMapAndMapsetAreSevenCharacters() {
            assertThat(MAP_NAME).hasSize(UserListResponse.NEXT_MAP_LENGTH).hasSize(7);
            assertThat(MAPSET_NAME).hasSize(UserListResponse.NEXT_MAPSET_LENGTH).hasSize(7);
            UserListResponse response = populatedResponse();
            assertThat(response.nextMap()).isEqualTo(MAP_NAME);
            assertThat(response.nextMapset()).isEqualTo(MAPSET_NAME);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> response.toBuilder().nextMap(MAP_NAME + "X").build())
                    .withMessageContaining(NavigationContext.LAST_MAP_FIELD);
        }

        @Test
        @DisplayName("the PF3 fallback target is carried in the same field")
        void theSignOnFallbackIsCarriedTheSameWay() {
            UserListResponse response = populatedResponse().toBuilder()
                    .nextProgram(codec().movePicX(TARGET_SIGNON,
                            UserListResponse.NEXT_PROGRAM_LENGTH))
                    .build();
            assertThat(response.nextProgram()).isEqualTo(TARGET_SIGNON);
        }
    }

    @Nested
    @DisplayName("Statelessness - the conversation travels in the payload")
    class Statelessness {
        @Test
        @DisplayName("the 160-byte communication area is a payload member")
        void theCommareaIsAPayloadMember() throws Exception {
            UserListResponse response = populatedResponse();
            assertThat(response.navigationContext()).isNotNull();
            assertThat(jsonKeys(response)).contains("navigationContext");
            assertThat(response.navigationContext().toFixedWidth(codec()))
                    .hasSize(NavigationContext.COMMAREA_LENGTH);
        }

        @ParameterizedTest(name = "context {0} gives ENTER={1} REENTER={2}")
        @CsvSource({
            "0, true, false",
            "1, false, true"
        })
        @DisplayName("both ENTER and REENTER are drivable through the carried commarea")
        void bothProgramContextsAreDrivable(int context, boolean enter, boolean reenter) {
            UserListResponse response = populatedResponse().toBuilder()
                    .navigationContext(listingContext().withPgmContext(context))
                    .build();

            assertThat(response.navigationContext().pgmContext()).isEqualTo(context);
            assertThat(response.navigationContext().isEnter()).isEqualTo(enter);
            assertThat(response.navigationContext().isReenter()).isEqualTo(reenter);
        }

        @Test
        @DisplayName("the two context shortcuts agree with the two 88-levels")
        void theContextShortcutsAgreeWithThe88Levels() {
            assertThat(NavigationContext.PGM_CONTEXT_ENTER).isZero();
            assertThat(NavigationContext.PGM_CONTEXT_REENTER).isOne();
            assertThat(listingContext().withPgmEnter().isEnter()).isTrue();
            assertThat(listingContext().withPgmReenter().isReenter()).isTrue();
        }

        @Test
        @DisplayName("the resolved AID token is five characters and is inbound only")
        void theResolvedAidTokenIsFiveCharactersAndInboundOnly() {
            assertThat(PfKeyResolver.AID_TOKEN_LENGTH).isEqualTo(5);
            for (PfKeyResolver.AidKey key : PfKeyResolver.AidKey.values()) {
                assertThat(key.token())
                        .as("CCARD-AID is PIC X(5), so every token is padded to five")
                        .hasSize(PfKeyResolver.AID_TOKEN_LENGTH);
            }
            assertThat(PfKeyResolver.resolve(CicsAid.DFHENTER))
                    .contains(PfKeyResolver.AidKey.ENTER);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHCLEAR))
                    .contains(PfKeyResolver.AidKey.CLEAR);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPA1))
                    .contains(PfKeyResolver.AidKey.PA1);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF3))
                    .contains(PfKeyResolver.AidKey.PFK03);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF7))
                    .contains(PfKeyResolver.AidKey.PFK07);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF8))
                    .contains(PfKeyResolver.AidKey.PFK08);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF15))
                    .as("PF13 to PF24 fold back onto PFK01 to PFK12")
                    .contains(PfKeyResolver.AidKey.PFK03);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHNULL))
                    .as("the source declares no WHEN OTHER, so an unmatched AID sets nothing")
                    .isEqualTo(Optional.empty());

            assertThat(componentNames()).doesNotContain("aid", "eibAid");
        }

        @Test
        @DisplayName("no member, and no annotation, introduces server-side state")
        void noServerSideStateIsIntroduced() {
            assertThat(UserListResponse.class.getAnnotations())
                    .as("no scope, no session and no framework annotation on the payload")
                    .isEmpty();
            List<Class<?>> referenced = new ArrayList<>();
            for (RecordComponent component : UserListResponse.class.getRecordComponents()) {
                referenced.add(component.getType());
            }
            for (Method method : UserListResponse.class.getDeclaredMethods()) {
                referenced.add(method.getReturnType());
                referenced.addAll(List.of(method.getParameterTypes()));
            }
            for (Class<?> type : referenced) {
                String name = type.getName();
                assertThat(name)
                        .as("%s must not pull in a container or a session", name)
                        .doesNotStartWith("jakarta.servlet")
                        .doesNotStartWith("javax.servlet")
                        .doesNotStartWith("org.springframework");
            }
        }
    }

    @Nested
    @DisplayName("Security posture - this screen has no password at all")
    class SecurityPosture {
        @Test
        @DisplayName("no member of this payload is a password")
        void noMemberIsAPassword() {
            for (String component : componentNames()) {
                assertThat(component.toLowerCase(Locale.ROOT))
                        .as("%s", component)
                        .doesNotContain("pwd")
                        .doesNotContain("password")
                        .doesNotContain("secret")
                        .doesNotContain("token");
            }
            for (MapField field : MAP_FIELDS) {
                assertThat(field.name()).doesNotContain("PWD").doesNotContain("PASS");
            }
        }

        @Test
        @DisplayName("no encoder and no Spring Security type is introduced")
        void noSecurityTypeIsIntroduced() {
            for (RecordComponent component : UserListResponse.class.getRecordComponents()) {
                assertThat(component.getType().getName())
                        .doesNotStartWith("org.springframework.security");
            }
            assertThat(SecUserRecord.SEC_USR_PWD_LENGTH)
                    .as("SEC-USR-PWD stays PIC X(08) plaintext where COSGN00C compares it, and is "
                            + "simply not projected here")
                    .isEqualTo(SEC_USR_PWD_LENGTH);
        }
    }

    @Nested
    @DisplayName("The message line - RED by declaration, GREEN only as an override")
    class ErrorHighlight {
        @Test
        @DisplayName("ERRMSG is declared COLOR=RED, so red is the default and not a highlight")
        void errMsgIsRedByDeclaration() {
            assertThat(BmsAttributes.colourMnemonic(BmsAttributes.DFHRED)).contains("RED");
            assertThat(BmsAttributes.DFHGREEN)
                    .as("app/cbl/COUSR03C.cbl:317 moves DFHGREEN into ERRMSGC: an override of the "
                            + "declared red, on the success path")
                    .isNotEqualTo(BmsAttributes.DFHRED);
            assertThat(UserListResponse.ERRMSG_LENGTH).isEqualTo(78);
        }

        @Test
        @DisplayName("COUSR00C copies DFHBMSCA yet references none of its constants (B4)")
        void theProgramCopiesDfhbmscaWithoutUsingIt() {
            assertThat(BmsAttributes.unsigned(BmsAttributes.DFHRED)).isEqualTo(0xF2);
            assertThat(BmsAttributes.unsigned(BmsAttributes.DFHGREEN)).isEqualTo(0xF4);
        }

        @ParameterizedTest(name = "state {0} in reenter={1} assigns colour={2} and asterisk={3}")
        @CsvSource({
            "OK,      true,  false, false",
            "OK,      false, false, false",
            "NOT_OK,  true,  true,  false",
            "NOT_OK,  false, false, false",
            "BLANK,   true,  true,  true",
            "BLANK,   false, false, false"
        })
        @DisplayName("the CSSETATY highlight applies only in REENTER state")
        void theHighlightAppliesOnlyInReenterState(String state, boolean reenter, boolean colour,
                                                  boolean asterisk) {
            FieldAttributeSetter.FieldHighlight highlight = FieldAttributeSetter.resolve(
                    FieldAttributeSetter.FieldValidationState.valueOf(state), reenter,
                    UserListResponse.ERRMSG_FIELD, MAP_NAME);

            assertThat(highlight.colourItemAssigned()).isEqualTo(colour);
            assertThat(highlight.outputItemAssigned()).isEqualTo(asterisk);
            if (colour) {
                assertThat(highlight.colourItemValue()).isEqualTo(BmsAttributes.DFHRED);
            }
            if (asterisk) {
                assertThat(highlight.outputItemValue()).isEqualTo(FieldAttributeSetter.ASTERISK);
            }
        }
    }

    @Nested
    @DisplayName("Screen identity and the title lines")
    class ScreenIdentityAndTitles {
        @Test
        @DisplayName("the screen identifies itself as CU00 / COUSR00C on map COUSR0A of COUSR00")
        void theScreenIdentifiesItself() {
            assertThat(UserListResponse.TRANSACTION_ID).isEqualTo(TRANSACTION_ID)
                    .hasSize(UserListResponse.TRNNAME_LENGTH);
            assertThat(UserListResponse.PROGRAM_NAME).isEqualTo(PROGRAM_NAME)
                    .hasSize(UserListResponse.PGMNAME_LENGTH);
            assertThat(UserListResponse.MAP_NAME).isEqualTo(MAP_NAME);
            assertThat(UserListResponse.MAPSET_NAME).isEqualTo(MAPSET_NAME);

            UserListResponse response = populatedResponse();
            assertThat(response.trnName()).isEqualTo(TRANSACTION_ID);
            assertThat(response.pgmName()).isEqualTo(PROGRAM_NAME);
        }

        @Test
        @DisplayName("the map is the 24 by 80 screen every mapset in this application declares")
        void theMapIsTwentyFourByEighty() {
            assertThat(SCREEN_ROWS).isEqualTo(24);
            assertThat(SCREEN_COLUMNS).isEqualTo(80);
            assertThat(UserListResponse.ERRMSG_LENGTH)
                    .as("ERRMSG at POS=(23,1) spans 78 of the 80 columns")
                    .isLessThan(SCREEN_COLUMNS);
        }

        @Test
        @DisplayName("both title lines are the 40-character screen titles, padding included")
        void bothTitleLinesAreTheScreenTitles() {
            assertThat(ScreenTitles.CCDA_TITLE01).hasSize(ScreenTitles.TITLE_LENGTH)
                    .hasSize(UserListResponse.TITLE01_LENGTH);
            assertThat(ScreenTitles.CCDA_TITLE02).hasSize(ScreenTitles.TITLE_LENGTH)
                    .hasSize(UserListResponse.TITLE02_LENGTH);

            UserListResponse response = populatedResponse();
            assertThat(response.title01()).isEqualTo(ScreenTitles.CCDA_TITLE01);
            assertThat(response.title02()).isEqualTo(ScreenTitles.CCDA_TITLE02);
        }

        @Test
        @DisplayName("the two thank-you constants are different things and are not interchangeable")
        void theThankYouConstantsAreNotInterchangeable() {
            assertThat(ScreenTitles.CCDA_THANK_YOU).hasSize(ScreenTitles.TITLE_LENGTH);
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU)
                    .hasSize(SystemMessages.MESSAGE_LENGTH)
                    .isNotEqualTo(ScreenTitles.CCDA_THANK_YOU);
            assertThat(SystemMessages.MESSAGE_LENGTH)
                    .isNotEqualTo(ScreenTitles.TITLE_LENGTH);
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU.length())
                    .as("a 50-character message still fits the 78-character message line")
                    .isLessThan(UserListResponse.ERRMSG_LENGTH);
            assertThat(codec().movePicX(SystemMessages.CCDA_MSG_INVALID_KEY,
                    UserListResponse.ERRMSG_LENGTH))
                    .hasSize(UserListResponse.ERRMSG_LENGTH)
                    .startsWith(SystemMessages.CCDA_MSG_INVALID_KEY);
        }
    }

    @Nested
    @DisplayName("Serialisation - the payload is the 69 components and nothing else")
    class JsonRoundTrip {
        @Test
        @DisplayName("the mapper under test is configured exactly as the application's is")
        void theMapperIsTheApplicationsOwn() {
            ObjectMapper mapper = webConfigEquivalentMapper();
            assertThat(mapper.isEnabled(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)).isTrue();
            assertThat(mapper.isEnabled(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)).isTrue();
            assertThat(mapper.isEnabled(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN)).isTrue();
            assertThat(mapper.isEnabled(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT))
                    .as("a default mapper coerces \"\" to null and breaks a space-padded PIC X")
                    .isFalse();
        }

        @Test
        @DisplayName("the JSON keys are exactly the 69 component names, in declaration order")
        void theJsonKeysAreExactlyTheComponentNames() throws Exception {
            assertThat(jsonKeys(populatedResponse()))
                    .containsExactlyElementsOf(wireNamesOf(EXPECTED_COMPONENT_NAMES));
        }

        @Test
        @DisplayName("all 50 row values appear in the payload under their own names")
        void allFiftyRowValuesAppearUnderTheirOwnNames() throws Exception {
            String json = webConfigEquivalentMapper().writeValueAsString(populatedResponse());
            FixedWidthCodec codec = codec();
            for (int rowNumber = FIRST_ROW_NUMBER; rowNumber <= LAST_ROW_NUMBER; rowNumber++) {
                String two = codec.movePic9(rowNumber, ROW_FIELD_DIGITS);
                String four = codec.movePic9(rowNumber, SEL_FIELD_DIGITS);
                assertThat(json)
                        .as("row %d", rowNumber)
                        .contains("\"sel" + four + "\"")
                        .contains("\"usrid" + two + "\"")
                        .contains("\"fname" + two + "\"")
                        .contains("\"lname" + two + "\"")
                        .contains("\"utype" + two + "\"");
            }
        }

        @Test
        @DisplayName("space padding survives a round trip: nothing is trimmed and nothing is nulled")
        void spacePaddingSurvivesARoundTrip() throws Exception {
            ObjectMapper mapper = webConfigEquivalentMapper();
            UserListResponse original = populatedResponse();

            String json = mapper.writeValueAsString(original);
            UserListResponse restored = mapper.readValue(json, UserListResponse.class);

            assertThat(restored).isEqualTo(original);
            assertThat(restored.errMsg())
                    .as("78 spaces, not \"\" and not null")
                    .isNotNull()
                    .hasSize(UserListResponse.ERRMSG_LENGTH)
                    .isBlank();
            for (MapField field : MAP_FIELDS) {
                assertThat(mapImages(restored).get(field.name()))
                        .as("%s survives at its declared width", field.name())
                        .isEqualTo(mapImages(original).get(field.name()));
            }
            assertThat(restored.navigationContext()).isEqualTo(original.navigationContext());
        }

        @Test
        @DisplayName("a short final page serialises ten rows, the tail space filled")
        void aShortFinalPageSerialisesTenRows() throws Exception {
            ObjectMapper mapper = webConfigEquivalentMapper();
            UserListResponse response = UserListResponse.builder()
                    .navigationContext(listingContext())
                    .populateRow(FIRST_ROW_NUMBER, userIdFor(FIRST_ROW_NUMBER),
                            firstNameFor(FIRST_ROW_NUMBER), lastNameFor(FIRST_ROW_NUMBER),
                            userTypeFor(FIRST_ROW_NUMBER))
                    .nextPageNo()
                    .build();

            UserListResponse restored = mapper.readValue(mapper.writeValueAsString(response),
                    UserListResponse.class);

            assertThat(restored.rows()).hasSize(OCCURS_COUNT);
            assertThat(restored.row(LAST_ROW_NUMBER).userId())
                    .hasSize(UserListResponse.USRID_LENGTH)
                    .satisfies(id -> assertThat(ScreenFieldImage.isSpacesOrLowValues(id)).isTrue());
            assertThat(restored.nextPageNo()).isTrue();
        }

        @Test
        @DisplayName("the communication area is a nested object, not flattened into the screen")
        void theCommareaIsANestedObject() throws Exception {
            ObjectMapper mapper = webConfigEquivalentMapper();
            String json = mapper.writeValueAsString(populatedResponse());
            Map<String, Object> tree = mapper.readValue(json,
                    new TypeReference<LinkedHashMap<String, Object>>() {
                    });

            assertThat(tree.get("navigationContext")).isInstanceOf(Map.class);
            assertThat(tree.keySet())
                    .as("the commarea's own fields do not leak into the screen's namespace")
                    .doesNotContain(NavigationContext.FROM_TRANID_FIELD, "fromTranid", "toProgram");
        }
    }

    @Nested
    @DisplayName("Value semantics - immutable, with no static mutable state")
    class ValueSemantics {
        @Test
        @DisplayName("toBuilder().build() reproduces the original exactly")
        void toBuilderReproducesTheOriginal() {
            UserListResponse original = populatedResponse();
            assertThat(original.toBuilder().build()).isEqualTo(original)
                    .hasSameHashCodeAs(original);
        }

        @Test
        @DisplayName("an edit produces a new value and leaves the original untouched")
        void anEditProducesANewValue() {
            UserListResponse original = populatedResponse();
            UserListResponse edited = original.toBuilder()
                    .errMsg(codec().movePicX(INVALID_SELECTION_TEXT,
                            UserListResponse.ERRMSG_LENGTH))
                    .build();

            assertThat(edited).isNotEqualTo(original);
            assertThat(original.errMsg()).isBlank();
            assertThat(edited.errMsg()).startsWith(INVALID_SELECTION_TEXT);
        }

        @Test
        @DisplayName("two builders never share a row array")
        void twoBuildersNeverShareARowArray() {
            UserListResponse.Builder first = UserListResponse.builder()
                    .navigationContext(listingContext())
                    .populateRow(FIRST_ROW_NUMBER, userIdFor(FIRST_ROW_NUMBER), "A", "B",
                            NavigationContext.USER_TYPE_ADMIN);
            UserListResponse.Builder second = UserListResponse.builder()
                    .navigationContext(listingContext())
                    .populateRow(FIRST_ROW_NUMBER, userIdFor(2), "C", "D",
                            NavigationContext.USER_TYPE_USER);

            assertThat(first.build().usrId01()).isEqualTo(userIdFor(FIRST_ROW_NUMBER));
            assertThat(second.build().usrId01()).isEqualTo(userIdFor(2));
        }

        @Test
        @DisplayName("every static field is final, in the record, its nested types and this suite")
        void noStaticMutableState() {
            for (Class<?> type : List.of(UserListResponse.class, UserListResponse.Builder.class,
                    Row.class, UserListResponseTest.class)) {
                for (Field field : type.getDeclaredFields()) {
                    if (field.isSynthetic() || !Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("%s.%s is static and must be final",
                                    type.getSimpleName(), field.getName())
                            .isTrue();
                }
            }
        }

        @Test
        @DisplayName("rows() returns an immutable list")
        void rowsAreImmutable() {
            List<Row> rows = populatedResponse().rows();
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> rows.add(new Row(11, " ", "        ",
                            "                    ", "                    ", " ")));
        }

        @Test
        @DisplayName("toString names the fields a parity diff needs and masks the personal names")
        void toStringNamesTheFieldsAndMasksTheNames() {
            UserListResponse response = populatedResponse();
            String rendered = response.toString();

            assertThat(rendered)
                    .contains("trnName=" + TRANSACTION_ID)
                    .contains("Row[1, ")
                    .contains("userId='" + userIdFor(FIRST_ROW_NUMBER) + "'")
                    .contains("cdemoCu00PageNum=1")
                    .contains("errMsg=");
            assertThat(rendered)
                    .as("twenty given and family names must not reach a log line")
                    .doesNotContain("First01")
                    .doesNotContain("Last10")
                    .contains("[text len=" + UserListResponse.FNAME_LENGTH + "]");
        }
    }
}

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
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.user.dto.UserListRequest.UserListRow;
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
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Unit tests for {@link UserListRequest} - the inbound payload of {@code GET /api/users}, CICS transaction
 * {@code CU00}, program {@code app/cbl/COUSR00C.cbl}, map {@code COUSR0A} of mapset {@code COUSR00}.
 */
class UserListRequestTest {
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

    private static final List<String> SCREEN_FIELDS = List.of(
            "TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02",
            "CURTIME", "PAGENUM", "USRIDIN", "SEL0001", "USRID01",
            "FNAME01", "LNAME01", "UTYPE01", "SEL0002", "USRID02",
            "FNAME02", "LNAME02", "UTYPE02", "SEL0003", "USRID03",
            "FNAME03", "LNAME03", "UTYPE03", "SEL0004", "USRID04",
            "FNAME04", "LNAME04", "UTYPE04", "SEL0005", "USRID05",
            "FNAME05", "LNAME05", "UTYPE05", "SEL0006", "USRID06",
            "FNAME06", "LNAME06", "UTYPE06", "SEL0007", "USRID07",
            "FNAME07", "LNAME07", "UTYPE07", "SEL0008", "USRID08",
            "FNAME08", "LNAME08", "UTYPE08", "SEL0009", "USRID09",
            "FNAME09", "LNAME09", "UTYPE09", "SEL0010", "USRID10",
            "FNAME10", "LNAME10", "UTYPE10", "ERRMSG");

    private static final List<String> SYMBOLIC_MAP_ITEMS = List.of(
            "TRNNAMEI", "TITLE01I", "CURDATEI", "PGMNAMEI", "TITLE02I",
            "CURTIMEI", "PAGENUMI", "USRIDINI", "SEL0001I", "USRID01I",
            "FNAME01I", "LNAME01I", "UTYPE01I", "SEL0002I", "USRID02I",
            "FNAME02I", "LNAME02I", "UTYPE02I", "SEL0003I", "USRID03I",
            "FNAME03I", "LNAME03I", "UTYPE03I", "SEL0004I", "USRID04I",
            "FNAME04I", "LNAME04I", "UTYPE04I", "SEL0005I", "USRID05I",
            "FNAME05I", "LNAME05I", "UTYPE05I", "SEL0006I", "USRID06I",
            "FNAME06I", "LNAME06I", "UTYPE06I", "SEL0007I", "USRID07I",
            "FNAME07I", "LNAME07I", "UTYPE07I", "SEL0008I", "USRID08I",
            "FNAME08I", "LNAME08I", "UTYPE08I", "SEL0009I", "USRID09I",
            "FNAME09I", "LNAME09I", "UTYPE09I", "SEL0010I", "USRID10I",
            "FNAME10I", "LNAME10I", "UTYPE10I", "ERRMSGI");

    private static final List<String> MAP_MEMBERS = List.of(
            "trnName", "title01", "curDate", "pgmName", "title02", "curTime",
            "pageNum", "usrIdIn", "sel0001", "usrId01", "fname01", "lname01",
            "utype01", "sel0002", "usrId02", "fname02", "lname02", "utype02",
            "sel0003", "usrId03", "fname03", "lname03", "utype03", "sel0004",
            "usrId04", "fname04", "lname04", "utype04", "sel0005", "usrId05",
            "fname05", "lname05", "utype05", "sel0006", "usrId06", "fname06",
            "lname06", "utype06", "sel0007", "usrId07", "fname07", "lname07",
            "utype07", "sel0008", "usrId08", "fname08", "lname08", "utype08",
            "sel0009", "usrId09", "fname09", "lname09", "utype09", "sel0010",
            "usrId10", "fname10", "lname10", "utype10", "errMsg");

    private static String wireNameOf(String member) {
        return MAP_MEMBERS.contains(member) ? member.toLowerCase(Locale.ROOT) : member;
    }

    private static List<String> wireNamesOf(List<String> members) {
        return members.stream().map(UserListRequestTest::wireNameOf).toList();
    }

    private static final List<Integer> DECLARED_WIDTHS = List.of(
            4, 40, 8, 8, 40, 8, 8, 8,
            1, 8, 20, 20, 1,
            1, 8, 20, 20, 1,
            1, 8, 20, 20, 1,
            1, 8, 20, 20, 1,
            1, 8, 20, 20, 1,
            1, 8, 20, 20, 1,
            1, 8, 20, 20, 1,
            1, 8, 20, 20, 1,
            1, 8, 20, 20, 1,
            1, 8, 20, 20, 1,
            78);

    private static final List<Integer> COPYBOOK_LINES = List.of(
            24, 30, 36, 42, 48, 54, 60, 66,
            72, 78, 84, 90, 96,
            102, 108, 114, 120, 126,
            132, 138, 144, 150, 156,
            162, 168, 174, 180, 186,
            192, 198, 204, 210, 216,
            222, 228, 234, 240, 246,
            252, 258, 264, 270, 276,
            282, 288, 294, 300, 306,
            312, 318, 324, 330, 336,
            342, 348, 354, 360, 366,
            372);

    private static final List<Integer> PUBLISHED_WIDTHS = publishedWidths();

    private static final int SEC_USR_ID_LENGTH = 8;

    private static final int SEC_USR_FNAME_LENGTH = 20;

    private static final int SEC_USR_LNAME_LENGTH = 20;

    private static final int SEC_USR_PWD_LENGTH = 8;

    private static final int SEC_USR_TYPE_LENGTH = 1;

    private static final int SEC_USR_FILLER_LENGTH = 23;

    private static final int SEC_USER_DATA_LENGTH = 80;

    private static final int WS_USER_SEL_LENGTH = 1;

    private static final int WS_USER_ID_LENGTH = 8;

    private static final int WS_USER_NAME_LENGTH = 25;

    private static final int WS_USER_TYPE_LENGTH = 8;

    private static final int WS_USER_REC_FILLER_LENGTH = 2;

    private static final int WS_USER_REC_FILLER_COUNT = 3;

    private static final int WS_USER_REC_LENGTH = 48;

    private static final int TIOAPFX_PREFIX_LENGTH = 12;

    private static final int LENGTH_ITEM_LENGTH = 2;

    private static final int ATTRIBUTE_ITEM_LENGTH = 1;

    private static final int ATTRIBUTE_FILLER_LENGTH = 4;

    private static final int FIELD_PREFIX_LENGTH =
            LENGTH_ITEM_LENGTH + ATTRIBUTE_ITEM_LENGTH + ATTRIBUTE_FILLER_LENGTH;

    private static final int PAYLOAD_WIDTH_TOTAL = 702;

    private static final int SYMBOLIC_MAP_LENGTH = 1127;

    private static final int REDEFINES_LINE_OFFSET = -3;

    private static final int ATTRIBUTE_VIEW_LINE_OFFSET = -2;

    private static final int GROUP_REDEFINES_LINE = 373;

    private static final int COPYBOOK_REDEFINES_TOTAL = 60;

    private static final String FLAG_ITEM_SUFFIX = "F";

    private static final String ATTRIBUTE_ITEM_SUFFIX = "A";

    private static final String LENGTH_ITEM_SUFFIX = "L";

    private static final String OUTPUT_ITEM_SUFFIX = "O";

    private static final FixedWidthRecord.RecordLayout SYMBOLIC_MAP_LAYOUT = symbolicMapLayout();

    private static final int MAP_ROW_PAYLOAD_LENGTH = 50;

    private static final int OCCURS_COUNT = 10;

    private static final int FIRST_SUBSCRIPT = 1;

    private static final int LAST_SUBSCRIPT = 10;

    private static final int EXCLUSIVE_FORWARD_BOUND = 11;

    private static final int EXCLUSIVE_BACKWARD_BOUND = 0;

    private static final List<Integer> PAGE_SIZE_EVIDENCE_LINES = List.of(57, 293, 300, 347, 352);

    private static final int CU00_USRID_FIRST_LENGTH = 8;

    private static final int CU00_USRID_LAST_LENGTH = 8;

    private static final int CU00_PAGE_NUM_DIGITS = 8;

    private static final int CU00_NEXT_PAGE_FLG_LENGTH = 1;

    private static final int CU00_USR_SEL_FLG_LENGTH = 1;

    private static final int CU00_USR_SELECTED_LENGTH = 8;

    private static final int CU00_INFO_LENGTH = 34;

    private static final int COMMAREA_LENGTH = 160;

    private static final int CU00_COMMAREA_LENGTH = 194;

    private static final String NEXT_PAGE_FLG_DEFAULT = "N";

    private static final String NEXT_PAGE_YES_VALUE = "Y";

    private static final String NEXT_PAGE_NO_VALUE = "N";

    private static final String USR_SEL_UPDATE_VALUE = "U";

    private static final String USR_SEL_DELETE_VALUE = "D";

    private static final int PAGE_NUM_MAX = 99_999_999;

    private static final int PGM_CONTEXT_ENTER = 0;

    private static final int PGM_CONTEXT_REENTER = 1;

    private static final String PF3_TARGET_PROGRAM = "COADM01C";

    private static final int WS_MESSAGE_LENGTH = 80;

    private static final int ERRMSG_LENGTH = 78;

    private static final int ERRMSG_TRUNCATED_CHARACTERS = 2;

    private static final Instant FIXED_INSTANT = Instant.parse("2022-07-19T23:15:57Z");

    private static final String FIXED_CURDATE = "07/19/22";

    private static final String FIXED_CURTIME = "23:15:57";

    private static final int COSGN00_CURTIME_LENGTH = 9;

    private static final List<String> STATE_MEMBERS = List.of("navigationContext", "aid");

    private static final int WIRE_MEMBER_COUNT = 67;

    private static final int COMPONENT_COUNT = 18;

    private static final Set<String> EXPECTED_JSON_MEMBERS = expectedJsonMembers();

    private static List<Integer> publishedWidths() {
        List<Integer> widths = new ArrayList<>(DFHMDF_NAMED);
        widths.add(UserListRequest.TRNNAME_LENGTH);
        widths.add(UserListRequest.TITLE01_LENGTH);
        widths.add(UserListRequest.CURDATE_LENGTH);
        widths.add(UserListRequest.PGMNAME_LENGTH);
        widths.add(UserListRequest.TITLE02_LENGTH);
        widths.add(UserListRequest.CURTIME_LENGTH);
        widths.add(UserListRequest.PAGENUM_LENGTH);
        widths.add(UserListRequest.USRIDIN_LENGTH);
        for (int rowNumber = FIRST_SUBSCRIPT; rowNumber <= LAST_SUBSCRIPT; rowNumber++) {
            widths.add(UserListRequest.SEL_LENGTH);
            widths.add(UserListRequest.USRID_LENGTH);
            widths.add(UserListRequest.FNAME_LENGTH);
            widths.add(UserListRequest.LNAME_LENGTH);
            widths.add(UserListRequest.UTYPE_LENGTH);
        }
        widths.add(UserListRequest.ERRMSG_LENGTH);
        return List.copyOf(widths);
    }

    private static FixedWidthRecord.RecordLayout symbolicMapLayout() {
        List<FixedWidthRecord.FieldSpan> spans = new ArrayList<>();
        spans.add(FixedWidthRecord.FieldSpan.filler(0, TIOAPFX_PREFIX_LENGTH));
        int cursor = TIOAPFX_PREFIX_LENGTH;
        for (int index = 0; index < DFHMDF_NAMED; index++) {
            String field = SCREEN_FIELDS.get(index);
            spans.add(FixedWidthRecord.FieldSpan.filler(cursor, LENGTH_ITEM_LENGTH));
            cursor += LENGTH_ITEM_LENGTH;
            FixedWidthRecord.FieldSpan flag = FixedWidthRecord.FieldSpan.alphanumeric(
                    field + FLAG_ITEM_SUFFIX, cursor, ATTRIBUTE_ITEM_LENGTH);
            spans.add(flag);
            spans.add(flag.redefinedAs(field + ATTRIBUTE_ITEM_SUFFIX,
                    FixedWidthRecord.PictureKind.ALPHANUMERIC));
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
        members.add("cdemoCu00UsrIdFirst");
        members.add("cdemoCu00UsrIdLast");
        members.add("cdemoCu00PageNum");
        members.add("cdemoCu00NextPageFlg");
        members.add("cdemoCu00UsrSelFlg");
        members.add("cdemoCu00UsrSelected");
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

    private static UserListRequest blankRequest() {
        return UserListRequest.empty();
    }

    private static UserListRequest populatedRequest() {
        FixedWidthCodec codec = codec();
        DateHeader header = DateHeader.from(codec, Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
        UserListRequest request = UserListRequest.empty()
                .withTrnName(TRANSACTION_ID)
                .withTitle01(ScreenTitles.CCDA_TITLE01)
                .withCurDate(header.wsCurdateMmDdYy())
                .withPgmName(PROGRAM_NAME)
                .withTitle02(ScreenTitles.CCDA_TITLE02)
                .withCurTime(header.wsCurtimeHhMmSs())
                .withPageNum(codec.movePic9(2L, UserListRequest.PAGENUM_LENGTH))
                .withUsrIdIn(codec.movePicX("USER0001", UserListRequest.USRIDIN_LENGTH))
                .withErrMsg(codec.movePicX(SystemMessages.CCDA_MSG_INVALID_KEY,
                        UserListRequest.ERRMSG_LENGTH))
                .withCdemoCu00UsrIdFirst(codec.movePicX("USER0001", CU00_USRID_FIRST_LENGTH))
                .withCdemoCu00UsrIdLast(codec.movePicX("USER0010", CU00_USRID_LAST_LENGTH))
                .withCdemoCu00PageNum(2)
                .withNextPageYes()
                .withCdemoCu00UsrSelFlg(USR_SEL_UPDATE_VALUE)
                .withCdemoCu00UsrSelected(codec.movePicX("USER0001", CU00_USR_SELECTED_LENGTH))
                .withNavigationContext(NavigationContext.empty()
                        .withFromTranid(TRANSACTION_ID)
                        .withFromProgram(PROGRAM_NAME)
                        .withUserId("ADMIN001")
                        .withUserTypeAdmin()
                        .withLastMap(MAP_NAME)
                        .withLastMapset(MAPSET_NAME)
                        .withPgmReenter())
                .withAid(PfKeyResolver.AidKey.PFK08.token());
        for (int rowNumber = FIRST_SUBSCRIPT; rowNumber <= LAST_SUBSCRIPT; rowNumber++) {
            request = request.withRow(rowNumber, rowFor(codec, rowNumber));
        }
        return request.withRow(FIRST_SUBSCRIPT,
                request.row(FIRST_SUBSCRIPT).withSel(USR_SEL_UPDATE_VALUE));
    }

    private static UserListRow rowFor(FixedWidthCodec codec, int rowNumber) {
        String suffix = codec.movePic9((long) rowNumber, UserListRequest.ROW_FIELD_DIGITS);
        return new UserListRow(
                codec.movePicX("", UserListRequest.SEL_LENGTH),
                codec.movePicX("USER00" + suffix, UserListRequest.USRID_LENGTH),
                codec.movePicX("First" + suffix, UserListRequest.FNAME_LENGTH),
                codec.movePicX("Last" + suffix, UserListRequest.LNAME_LENGTH),
                rowNumber == FIRST_SUBSCRIPT ? NavigationContext.USER_TYPE_ADMIN
                        : NavigationContext.USER_TYPE_USER);
    }

    private static List<String> mapValuesOf(UserListRequest request) {
        return List.copyOf(request.mapFields().values());
    }

    private static Set<ConstraintViolation<UserListRequest>> validate(UserListRequest request) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            return factory.getValidator().validate(request);
        }
    }

    private static Set<ConstraintViolation<UserListRequest>> validateValue(String member,
            Object value) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            return factory.getValidator().validateValue(UserListRequest.class, member, value);
        }
    }

    private static Set<ConstraintViolation<UserListRow>> validateRowValue(String component,
            Object value) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            return factory.getValidator().validateValue(UserListRow.class, component, value);
        }
    }

    private static boolean isRecordComponent(String member) {
        return Arrays.stream(UserListRequest.class.getRecordComponents())
                .map(RecordComponent::getName)
                .anyMatch(member::equals);
    }

    private static String serialise(UserListRequest request) {
        try {
            return webConfigEquivalentMapper().writeValueAsString(request);
        } catch (IOException failure) {
            throw new UncheckedIOException("Serialising a UserListRequest must not fail", failure);
        }
    }

    private static UserListRequest deserialise(String json) {
        try {
            return webConfigEquivalentMapper().readValue(json, UserListRequest.class);
        } catch (IOException failure) {
            throw new UncheckedIOException("Deserialising a UserListRequest must not fail", failure);
        }
    }

    private static Map<String, Object> jsonTreeOf(UserListRequest request) {
        try {
            return webConfigEquivalentMapper().readValue(serialise(request),
                    new TypeReference<Map<String, Object>>() { });
        } catch (IOException failure) {
            throw new UncheckedIOException("Reading a serialised UserListRequest must not fail",
                    failure);
        }
    }

    private static List<String> screenFields() {
        return SCREEN_FIELDS;
    }

    private static List<Arguments> membersAndWidths() {
        List<Arguments> cases = new ArrayList<>(DFHMDF_NAMED);
        for (int index = 0; index < DFHMDF_NAMED; index++) {
            cases.add(Arguments.of(MAP_MEMBERS.get(index), DECLARED_WIDTHS.get(index)));
        }
        return List.copyOf(cases);
    }

    @Nested
    @DisplayName("Projection of 01 COUSR0AI - fifty-nine screen fields, in copybook order")
    class MapProjection {
        @Test
        @DisplayName("the four transcriptions are the same length, and that length is fifty-nine")
        void theTranscriptionsAgreeOnTheFieldCount() {
            assertThat(SCREEN_FIELDS).hasSize(DFHMDF_NAMED);
            assertThat(SYMBOLIC_MAP_ITEMS).hasSize(DFHMDF_NAMED);
            assertThat(MAP_MEMBERS).hasSize(DFHMDF_NAMED);
            assertThat(DECLARED_WIDTHS).hasSize(DFHMDF_NAMED);
            assertThat(COPYBOOK_LINES).hasSize(DFHMDF_NAMED);
            assertThat(UserListRequest.MAP_FIELD_COUNT)
                    .as("8 header and paging + 10 rows x 5 + 1 trailer")
                    .isEqualTo(UserListRequest.HEADER_FIELD_COUNT
                            + UserListRequest.ROW_COUNT * UserListRequest.ROW_FIELD_COUNT
                            + UserListRequest.TRAILER_FIELD_COUNT)
                    .isEqualTo(DFHMDF_NAMED);
        }

        @Test
        @DisplayName("eighty-nine DFHMDF definitions, of which fifty-nine are fields and thirty are not")
        void theMapsetSplitsIntoFieldsAndFurniture() {
            assertThat(DFHMDF_NAMED + DFHMDF_LITERALS).isEqualTo(DFHMDF_TOTAL);
            assertThat(UserListRequest.MAP_FIELD_COUNT).isEqualTo(DFHMDF_NAMED);
            assertThat(SCREEN_ROWS * SCREEN_COLUMNS)
                    .as("SIZE=(24,80) at app/bms/COUSR00.bms:28 - one 1920-character screen")
                    .isEqualTo(1920);
        }

        @Test
        @DisplayName("eighteen record components: the header, the row table, the trailer, CU00, the state")
        void componentCensus() {
            RecordComponent[] components = UserListRequest.class.getRecordComponents();
            assertThat(components)
                    .as("8 header and paging + rows + errMsg + 6 CDEMO-CU00-* + navigationContext "
                            + "and aid")
                    .hasSize(COMPONENT_COUNT);

            assertThat(Arrays.stream(components).map(RecordComponent::getName).toList())
                    .containsExactly("trnName", "title01", "curDate", "pgmName", "title02",
                            "curTime", "pageNum", "usrIdIn", "rows", "errMsg",
                            "cdemoCu00UsrIdFirst", "cdemoCu00UsrIdLast", "cdemoCu00PageNum",
                            "cdemoCu00NextPageFlg", "cdemoCu00UsrSelFlg", "cdemoCu00UsrSelected",
                            "navigationContext", "aid");
        }

        @Test
        @DisplayName("mapFieldNames() lists the fifty-nine screen fields in the copybook's order")
        void theFieldNamesAreTheCopybooksInTheCopybooksOrder() {
            assertThat(UserListRequest.mapFieldNames())
                    .as("every payload field traces to one name-labelled DFHMDF, in declaration order")
                    .containsExactlyElementsOf(SCREEN_FIELDS);
        }

        @Test
        @DisplayName("mapFields() projects one value per screen field, in that same order")
        void theFieldViewIsOrderedAndComplete() {
            Map<String, String> fields = populatedRequest().mapFields();
            assertThat(fields).hasSize(DFHMDF_NAMED);
            assertThat(fields.keySet()).containsExactlyElementsOf(SCREEN_FIELDS);
            assertThat(mapValuesOf(populatedRequest())).hasSize(DFHMDF_NAMED);
        }

        @ParameterizedTest(name = "{0} is {1} character(s) wide")
        @MethodSource(
                "com.vsergeychik.carddemo.user.dto.UserListRequestTest#membersAndWidths")
        @DisplayName("each member is exactly as wide as its xxxI PICTURE clause")
        void eachMemberIsAsWideAsItsPictureClause(String member, int declaredWidth) {
            int index = MAP_MEMBERS.indexOf(member);
            assertThat(index).as("%s must be one of the fifty-nine map members", member)
                    .isNotNegative();

            assertThat(PUBLISHED_WIDTHS.get(index))
                    .as("%s is %s PIC X(%d) at app/cpy-bms/COUSR00.CPY:%d", member,
                            SYMBOLIC_MAP_ITEMS.get(index), declaredWidth, COPYBOOK_LINES.get(index))
                    .isEqualTo(declaredWidth);

            assertThat(SYMBOLIC_MAP_LAYOUT.span(SYMBOLIC_MAP_ITEMS.get(index)).length())
                    .isEqualTo(declaredWidth);
        }

        @Test
        @DisplayName("the sum of the declared widths is 124 header + 500 rows + 78 trailer")
        void theWidthsSumAsTheCopybookLaysThemOut() {
            int header = DECLARED_WIDTHS.subList(0, UserListRequest.HEADER_FIELD_COUNT).stream()
                    .mapToInt(Integer::intValue).sum();
            int rows = DECLARED_WIDTHS.subList(UserListRequest.HEADER_FIELD_COUNT,
                            DFHMDF_NAMED - UserListRequest.TRAILER_FIELD_COUNT).stream()
                    .mapToInt(Integer::intValue).sum();
            int trailer = DECLARED_WIDTHS.get(DFHMDF_NAMED - 1);

            assertThat(header).as("4 + 40 + 8 + 8 + 40 + 8 + 8 + 8").isEqualTo(124);
            assertThat(rows).as("ten rows of 1 + 8 + 20 + 20 + 1")
                    .isEqualTo(UserListRequest.ROW_COUNT * MAP_ROW_PAYLOAD_LENGTH)
                    .isEqualTo(500);
            assertThat(trailer).isEqualTo(ERRMSG_LENGTH);
            assertThat(header + rows + trailer).isEqualTo(PAYLOAD_WIDTH_TOTAL);
        }

        @Test
        @DisplayName("the copybook item lines advance by a constant six-line stride")
        void theCopybookStrideIsConstant() {
            for (int index = 1; index < DFHMDF_NAMED; index++) {
                assertThat(COPYBOOK_LINES.get(index) - COPYBOOK_LINES.get(index - 1))
                        .as("%s follows %s six lines later", SYMBOLIC_MAP_ITEMS.get(index),
                                SYMBOLIC_MAP_ITEMS.get(index - 1))
                        .isEqualTo(6);
            }
            assertThat(COPYBOOK_LINES.get(0)).as("TRNNAMEI is the first item").isEqualTo(24);
            assertThat(COPYBOOK_LINES.get(DFHMDF_NAMED - 1))
                    .as("ERRMSGI is the last item of 01 COUSR0AI, on the line immediately before "
                            + "01 COUSR0AO REDEFINES COUSR0AI")
                    .isEqualTo(GROUP_REDEFINES_LINE - 1)
                    .isEqualTo(372);
        }

        @Test
        @DisplayName("the identity is CU00 / COUSR00C / COUSR0A / COUSR00, from three sources")
        void theScreenIdentityIsTheSourcesOwn() {
            assertThat(UserListRequest.TRANID)
                    .as("WS-TRANID at app/cbl/COUSR00C.cbl:37, and DEFINE TRANSACTION(CU00) at "
                            + "app/csd/CARDDEMO.CSD:449")
                    .isEqualTo(TRANSACTION_ID)
                    .hasSize(UserListRequest.TRNNAME_LENGTH);
            assertThat(UserListRequest.PROGRAM)
                    .as("WS-PGMNAME at app/cbl/COUSR00C.cbl:36, and PROGRAM(COUSR00C) at "
                            + "app/csd/CARDDEMO.CSD:450")
                    .isEqualTo(PROGRAM_NAME)
                    .hasSize(UserListRequest.PGMNAME_LENGTH);
            assertThat(UserListRequest.MAP)
                    .as("MAP('COUSR0A') at app/cbl/COUSR00C.cbl:530")
                    .isEqualTo(MAP_NAME);
            assertThat(UserListRequest.MAPSET)
                    .as("MAPSET('COUSR00') at app/cbl/COUSR00C.cbl:531")
                    .isEqualTo(MAPSET_NAME);

            assertThat(NavigationContext.LAST_MAP_LENGTH)
                    .as("CDEMO-LAST-MAP is PIC X(7), so COUSR0A fits exactly and COUSR00C would not")
                    .isEqualTo(MAP_NAME.length())
                    .isEqualTo(7);
            assertThat(NavigationContext.LAST_MAPSET_LENGTH).isEqualTo(MAPSET_NAME.length());
        }

        @Test
        @DisplayName("the search field is USRIDIN, not USERID - COUSR01 is the odd one out")
        void theSearchFieldIsNamedUsridin() {
            assertThat(UserListRequest.USRIDIN_FIELD).isEqualTo("USRIDIN");
            assertThat(SCREEN_FIELDS).contains("USRIDIN").doesNotContain("USERID");
            assertThat(SYMBOLIC_MAP_ITEMS).contains("USRIDINI").doesNotContain("USERIDI");
            assertThat(MAP_MEMBERS).contains("usrIdIn").doesNotContain("userId");
            assertThat(UserListRequest.USRIDIN_LENGTH)
                    .as("USRIDINI PIC X(8) at app/cpy-bms/COUSR00.CPY:66, matching SEC-USR-ID X(08)")
                    .isEqualTo(SEC_USR_ID_LENGTH);
        }

        @ParameterizedTest(name = "row {0}: SEL has four digits, the other four have two")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
        @DisplayName("the selection column is numbered SEL0001..SEL0010, the rest 01..10")
        void theRowLabelsCarryTheCopybooksNumbering(int rowNumber) {
            String twoDigits = codec().movePic9((long) rowNumber, UserListRequest.ROW_FIELD_DIGITS);
            String fourDigits = codec().movePic9((long) rowNumber, UserListRequest.SEL_FIELD_DIGITS);

            assertThat(UserListRequest.selFieldName(rowNumber))
                    .as("the copybook spells it SEL000n, with four digits")
                    .isEqualTo(UserListRequest.SEL_FIELD_STEM + fourDigits)
                    .hasSize(UserListRequest.SEL_FIELD_STEM.length()
                            + UserListRequest.SEL_FIELD_DIGITS);
            assertThat(UserListRequest.usrIdFieldName(rowNumber))
                    .isEqualTo(UserListRequest.USRID_FIELD_STEM + twoDigits);
            assertThat(UserListRequest.fnameFieldName(rowNumber))
                    .isEqualTo(UserListRequest.FNAME_FIELD_STEM + twoDigits);
            assertThat(UserListRequest.lnameFieldName(rowNumber))
                    .isEqualTo(UserListRequest.LNAME_FIELD_STEM + twoDigits);
            assertThat(UserListRequest.utypeFieldName(rowNumber))
                    .isEqualTo(UserListRequest.UTYPE_FIELD_STEM + twoDigits);

            assertThat(SCREEN_FIELDS).contains(UserListRequest.selFieldName(rowNumber),
                    UserListRequest.usrIdFieldName(rowNumber),
                    UserListRequest.fnameFieldName(rowNumber),
                    UserListRequest.lnameFieldName(rowNumber),
                    UserListRequest.utypeFieldName(rowNumber));
        }

        @Test
        @DisplayName("the four-digit and two-digit label widths are two different constants")
        void theTwoLabelWidthsAreDistinct() {
            assertThat(UserListRequest.SEL_FIELD_DIGITS).isEqualTo(4);
            assertThat(UserListRequest.ROW_FIELD_DIGITS).isEqualTo(2);
            assertThat(UserListRequest.SEL_FIELD_DIGITS)
                    .as("collapsing these two into one constant renames thirty of the fifty row "
                            + "fields and is a silent G9 violation")
                    .isNotEqualTo(UserListRequest.ROW_FIELD_DIGITS);
        }
    }

    @Nested
    @DisplayName("Width traps - the four places a plausible reading is the wrong reading")
    class WidthTraps {
        @Test
        @DisplayName("CURTIME is X(8) here and X(9) on COSGN00")
        void curTimeIsEightNotNine() {
            assertThat(UserListRequest.CURTIME_LENGTH).isEqualTo(8);
            assertThat(UserListRequest.CURTIME_LENGTH).isNotEqualTo(COSGN00_CURTIME_LENGTH);
            assertThat(DECLARED_WIDTHS.get(SCREEN_FIELDS.indexOf("CURTIME"))).isEqualTo(8);

            DateHeader header = DateHeader.from(codec(), Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
            assertThat(header.wsCurtimeHhMmSs())
                    .as("hh:mm:ss fills the field exactly, with no room for a leading attribute byte")
                    .isEqualTo(FIXED_CURTIME)
                    .hasSize(UserListRequest.CURTIME_LENGTH);
            assertThat(blankRequest().withCurTime(header.wsCurtimeHhMmSs()).curTime())
                    .isEqualTo(FIXED_CURTIME);
        }

        @Test
        @DisplayName("CURDATE is X(8) and carries mm/dd/yy")
        void curDateIsEightAndCarriesTheTwoDigitYear() {
            DateHeader header = DateHeader.from(codec(), Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
            assertThat(header.wsCurdateMmDdYy())
                    .isEqualTo(FIXED_CURDATE)
                    .hasSize(UserListRequest.CURDATE_LENGTH);
            assertThat(UserListRequest.CURDATE_LENGTH).isEqualTo(8);
            assertThat(blankRequest().withCurDate(header.wsCurdateMmDdYy()).curDate())
                    .isEqualTo(FIXED_CURDATE);
        }

        @Test
        @DisplayName("ERRMSG is X(78) while WS-MESSAGE is X(80), so the MOVE discards two characters")
        void theMessageMoveTruncatesOnTheRight() {
            assertThat(WS_MESSAGE_LENGTH - UserListRequest.ERRMSG_LENGTH)
                    .isEqualTo(ERRMSG_TRUNCATED_CHARACTERS);

            String wsMessage = "X".repeat(UserListRequest.ERRMSG_LENGTH) + "YZ";
            assertThat(wsMessage).hasSize(WS_MESSAGE_LENGTH);

            String moved = codec().movePicX(wsMessage, UserListRequest.ERRMSG_LENGTH);
            assertThat(moved)
                    .as("the surviving characters are the leading 78")
                    .hasSize(UserListRequest.ERRMSG_LENGTH)
                    .isEqualTo("X".repeat(UserListRequest.ERRMSG_LENGTH))
                    .doesNotContain("Y")
                    .doesNotContain("Z");
            assertThat(blankRequest().withErrMsg(moved).errMsg()).isEqualTo(moved);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> blankRequest().withErrMsg(wsMessage))
                    .withMessageContaining(UserListRequest.ERRMSG_FIELD)
                    .withMessageContaining("movePicX");
        }

        @Test
        @DisplayName("a shorter message is space-padded to 78, not left short")
        void aShorterMessageIsPaddedToTheFieldWidth() {
            assertThat(SystemMessages.CCDA_MSG_INVALID_KEY)
                    .hasSize(SystemMessages.MESSAGE_LENGTH)
                    .hasSize(50);
            String padded = codec().movePicX(SystemMessages.CCDA_MSG_INVALID_KEY,
                    UserListRequest.ERRMSG_LENGTH);
            assertThat(padded)
                    .hasSize(UserListRequest.ERRMSG_LENGTH)
                    .isEqualTo(SystemMessages.CCDA_MSG_INVALID_KEY
                            + " ".repeat(UserListRequest.ERRMSG_LENGTH
                                    - SystemMessages.MESSAGE_LENGTH));
        }

        @Test
        @DisplayName("TITLE01 and TITLE02 are X(40), and each title literal is exactly 40 characters")
        void theTitlesFillTheirFieldsExactly() {
            assertThat(ScreenTitles.TITLE_LENGTH).isEqualTo(UserListRequest.TITLE01_LENGTH)
                    .isEqualTo(UserListRequest.TITLE02_LENGTH).isEqualTo(40);
            assertThat(ScreenTitles.CCDA_TITLE01).hasSize(UserListRequest.TITLE01_LENGTH);
            assertThat(ScreenTitles.CCDA_TITLE02).hasSize(UserListRequest.TITLE02_LENGTH);

            UserListRequest request = blankRequest()
                    .withTitle01(ScreenTitles.CCDA_TITLE01)
                    .withTitle02(ScreenTitles.CCDA_TITLE02);
            assertThat(request.title01()).isEqualTo(ScreenTitles.CCDA_TITLE01);
            assertThat(request.title02()).isEqualTo(ScreenTitles.CCDA_TITLE02);
            assertThat(codec().movePicX(ScreenTitles.CCDA_TITLE01, UserListRequest.TITLE01_LENGTH))
                    .as("a MOVE at equal width is the identity")
                    .isEqualTo(ScreenTitles.CCDA_TITLE01);
        }

        @Test
        @DisplayName("the two thank-you literals are different texts of different widths in different owners")
        void theThankYouLiteralsAreNotInterchangeable() {
            assertThat(ScreenTitles.CCDA_THANK_YOU).hasSize(ScreenTitles.TITLE_LENGTH).hasSize(40);
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU)
                    .hasSize(SystemMessages.MESSAGE_LENGTH).hasSize(50);
            assertThat(ScreenTitles.CCDA_THANK_YOU.length())
                    .isNotEqualTo(SystemMessages.CCDA_MSG_THANK_YOU.length());
            assertThat(ScreenTitles.CCDA_THANK_YOU.trim())
                    .isNotEqualTo(SystemMessages.CCDA_MSG_THANK_YOU.trim());

            assertThat(SystemMessages.MESSAGE_LENGTH)
                    .isLessThanOrEqualTo(UserListRequest.ERRMSG_LENGTH);
        }
    }

    @Nested
    @DisplayName("Page size ten - fixed by the map and by COUSR00C's loop bounds")
    class PageSizeIsBehaviour {
        @Test
        @DisplayName("exactly ten rows of five fields, and fifty of the fifty-nine fields are rows")
        void theTableIsTenRowsOfFive() {
            assertThat(UserListRequest.ROW_COUNT).isEqualTo(OCCURS_COUNT).isEqualTo(10);
            assertThat(UserListRequest.ROW_FIELD_COUNT).isEqualTo(5);
            assertThat(UserListRequest.ROW_COUNT * UserListRequest.ROW_FIELD_COUNT).isEqualTo(50);
            assertThat(blankRequest().rows()).hasSize(UserListRequest.ROW_COUNT);
            assertThat(UserListRequest.blankRows()).hasSize(UserListRequest.ROW_COUNT);
            assertThat(populatedRequest().rows()).hasSize(UserListRequest.ROW_COUNT);
            assertThat(UserListRow.class.getRecordComponents())
                    .as("SEL X(1), USRID X(8), FNAME X(20), LNAME X(20), UTYPE X(1)")
                    .hasSize(UserListRequest.ROW_FIELD_COUNT);
        }

        @Test
        @DisplayName("ten is stated five times in the source, so it is a property and not a setting")
        void tenIsRestatedFiveTimesInTheSource() {
            assertThat(PAGE_SIZE_EVIDENCE_LINES).hasSize(5).containsExactly(57, 293, 300, 347, 352);
            assertThat(EXCLUSIVE_FORWARD_BOUND)
                    .as("the forward loop stops when WS-IDX reaches 11, having filled 1..10")
                    .isEqualTo(LAST_SUBSCRIPT + 1);
            assertThat(EXCLUSIVE_BACKWARD_BOUND)
                    .as("the backward loop stops when WS-IDX reaches 0, having filled 10..1")
                    .isEqualTo(FIRST_SUBSCRIPT - 1);
        }

        @Test
        @DisplayName("no page-size setter, constructor parameter or configuration key exists")
        void thePageSizeCannotBeChanged() {
            List<String> methodNames = Arrays.stream(UserListRequest.class.getDeclaredMethods())
                    .map(Method::getName).toList();
            assertThat(methodNames)
                    .as("a page size that could be set would make ten a configurable value")
                    .doesNotContain("setPageSize", "withPageSize", "setRowCount", "withRowCount",
                            "pageSize", "rowCount");

            assertThat(Arrays.stream(UserListRequest.class.getRecordComponents())
                    .map(RecordComponent::getName).toList())
                    .doesNotContain("pageSize", "rowCount", "rowsPerPage");

            assertThat(MAP_MEMBERS).contains("pageNum");
            assertThat(UserListRequest.PAGENUM_LENGTH)
                    .as("PAGENUMI PIC X(8) is the page number's image, not a page size")
                    .isEqualTo(CU00_PAGE_NUM_DIGITS);
        }

        @ParameterizedTest(name = "COBOL subscript {0} is Java index {1}")
        @CsvSource({"1, 0", "2, 1", "3, 2", "4, 3", "5, 4", "6, 5", "7, 6", "8, 7", "9, 8", "10, 9"})
        @DisplayName("row(n) addresses rows().get(n - 1), at every subscript in the table")
        void theOneBasedSubscriptMapsToTheZeroBasedIndex(int subscript, int index) {
            UserListRequest request = populatedRequest();

            assertThat(request.row(subscript))
                    .as("WS-IDX %d is list index %d", subscript, index)
                    .isSameAs(request.rows().get(index));

            int rowTableOffset = SYMBOLIC_MAP_LAYOUT.span(SYMBOLIC_MAP_ITEMS.get(
                    UserListRequest.HEADER_FIELD_COUNT)).offset() - FIELD_PREFIX_LENGTH;
            int elementOffset = FixedWidthRecord.occursElementOffsetOneBased(rowTableOffset,
                    MAP_ROW_PAYLOAD_LENGTH + UserListRequest.ROW_FIELD_COUNT * FIELD_PREFIX_LENGTH,
                    OCCURS_COUNT, subscript);
            assertThat(elementOffset)
                    .as("element %d starts %d whole rows past the table's base", subscript, index)
                    .isEqualTo(rowTableOffset
                            + index * (MAP_ROW_PAYLOAD_LENGTH
                                    + UserListRequest.ROW_FIELD_COUNT * FIELD_PREFIX_LENGTH));
        }

        @Test
        @DisplayName("the first row is index 0 and the last row is index 9, both proved by value")
        void theFirstAndLastElementsAreBothAsserted() {
            UserListRequest request = populatedRequest();

            assertThat(request.row(FIRST_SUBSCRIPT).usrId())
                    .as("WS-IDX 1 writes USRID01I, which is list index 0")
                    .isEqualTo(request.rows().get(0).usrId())
                    .isEqualTo(request.usrId01());
            assertThat(request.row(LAST_SUBSCRIPT).usrId())
                    .as("WS-IDX 10 writes USRID10I, which is list index 9")
                    .isEqualTo(request.rows().get(UserListRequest.ROW_COUNT - 1).usrId())
                    .isEqualTo(request.usrId10());

            assertThat(request.row(FIRST_SUBSCRIPT).usrId())
                    .isNotEqualTo(request.row(LAST_SUBSCRIPT).usrId());
        }

        @ParameterizedTest(name = "subscript {0} is rejected")
        @ValueSource(ints = {-1, 0, 11, 12, Integer.MIN_VALUE, Integer.MAX_VALUE})
        @DisplayName("a subscript outside 1..10 is rejected, never clamped to the nearest row")
        void anOutOfRangeSubscriptIsRejected(int subscript) {
            UserListRequest request = populatedRequest();

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> request.row(subscript))
                    .withMessageContaining("between 1 and " + UserListRequest.ROW_COUNT);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> request.withRow(subscript, UserListRow.blank()));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> UserListRequest.selFieldName(subscript));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> UserListRequest.usrIdFieldName(subscript));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> UserListRequest.fnameFieldName(subscript));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> UserListRequest.lnameFieldName(subscript));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> UserListRequest.utypeFieldName(subscript));
        }

        @Test
        @DisplayName("the codec's own OCCURS helper refuses subscript 0 and subscript 11 too")
        void theCodecRefusesTheSameOutOfRangeSubscripts() {
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> FixedWidthRecord.occursElementOffsetOneBased(0,
                            MAP_ROW_PAYLOAD_LENGTH, OCCURS_COUNT, EXCLUSIVE_BACKWARD_BOUND))
                    .withMessageContaining("1..");
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> FixedWidthRecord.occursElementOffsetOneBased(0,
                            MAP_ROW_PAYLOAD_LENGTH, OCCURS_COUNT, EXCLUSIVE_FORWARD_BOUND));

            assertThat(FixedWidthRecord.occursElementOffsetOneBased(0, MAP_ROW_PAYLOAD_LENGTH,
                    OCCURS_COUNT, FIRST_SUBSCRIPT))
                    .as("subscript 1 is the base offset itself")
                    .isZero();
            assertThat(FixedWidthRecord.occursElementOffsetOneBased(0, MAP_ROW_PAYLOAD_LENGTH,
                    OCCURS_COUNT, LAST_SUBSCRIPT))
                    .as("subscript 10 is nine whole rows in")
                    .isEqualTo((OCCURS_COUNT - 1) * MAP_ROW_PAYLOAD_LENGTH);
        }

        @Test
        @DisplayName("a row list of any size other than ten is refused, not padded or truncated")
        void onlyTenRowsAreAccepted() {
            List<UserListRow> nine = new ArrayList<>(UserListRequest.blankRows());
            nine.remove(0);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> blankRequest().withRows(nine))
                    .withMessageContaining("exactly " + UserListRequest.ROW_COUNT)
                    .withMessageContaining("not a configurable page size");

            List<UserListRow> eleven = new ArrayList<>(UserListRequest.blankRows());
            eleven.add(UserListRow.blank());
            assertThatIllegalArgumentException().isThrownBy(() -> blankRequest().withRows(eleven));

            assertThatIllegalArgumentException().isThrownBy(() -> blankRequest().withRows(List.of()));

            assertThat(blankRequest().withRows(null).rows()).hasSize(UserListRequest.ROW_COUNT);
        }

        @Test
        @DisplayName("replacing one row leaves the other nine untouched")
        void replacingOneRowIsLocal() {
            UserListRequest before = populatedRequest();
            UserListRow replacement = UserListRow.blank().withUsrId("REPLACED");
            UserListRequest after = before.withRow(5, replacement);

            assertThat(after.row(5)).isEqualTo(replacement);
            for (int rowNumber = FIRST_SUBSCRIPT; rowNumber <= LAST_SUBSCRIPT; rowNumber++) {
                if (rowNumber != 5) {
                    assertThat(after.row(rowNumber))
                            .as("row %d must be untouched", rowNumber)
                            .isEqualTo(before.row(rowNumber));
                }
            }
            assertThat(before.row(5))
                    .as("the type is immutable, so the original still holds its own row")
                    .isNotEqualTo(replacement);
        }
    }

    @Nested
    @DisplayName("The CU00 paging context - 160 + 34 = 194 bytes")
    class Cu00CommareaExtension {
        @Test
        @DisplayName("all six CDEMO-CU00-* members live on this payload")
        void theSixExtensionMembersAreCarriedHere() {
            assertThat(Arrays.stream(UserListRequest.class.getRecordComponents())
                    .map(RecordComponent::getName).toList())
                    .containsSubsequence("cdemoCu00UsrIdFirst", "cdemoCu00UsrIdLast",
                            "cdemoCu00PageNum", "cdemoCu00NextPageFlg", "cdemoCu00UsrSelFlg",
                            "cdemoCu00UsrSelected");

            assertThat(UserListRequest.CU00_USRID_FIRST_FIELD).isEqualTo("CDEMO-CU00-USRID-FIRST");
            assertThat(UserListRequest.CU00_USRID_LAST_FIELD).isEqualTo("CDEMO-CU00-USRID-LAST");
            assertThat(UserListRequest.CU00_PAGE_NUM_FIELD).isEqualTo("CDEMO-CU00-PAGE-NUM");
            assertThat(UserListRequest.CU00_NEXT_PAGE_FLG_FIELD)
                    .isEqualTo("CDEMO-CU00-NEXT-PAGE-FLG");
            assertThat(UserListRequest.CU00_USR_SEL_FLG_FIELD).isEqualTo("CDEMO-CU00-USR-SEL-FLG");
            assertThat(UserListRequest.CU00_USR_SELECTED_FIELD).isEqualTo("CDEMO-CU00-USR-SELECTED");
        }

        @Test
        @DisplayName("the extension is 34 bytes: 8 + 8 + 8 + 1 + 1 + 8")
        void theExtensionIsThirtyFourBytes() {
            assertThat(UserListRequest.CU00_USRID_FIRST_LENGTH).isEqualTo(CU00_USRID_FIRST_LENGTH);
            assertThat(UserListRequest.CU00_USRID_LAST_LENGTH).isEqualTo(CU00_USRID_LAST_LENGTH);
            assertThat(UserListRequest.CU00_PAGE_NUM_LENGTH).isEqualTo(CU00_PAGE_NUM_DIGITS);
            assertThat(UserListRequest.CU00_NEXT_PAGE_FLG_LENGTH)
                    .isEqualTo(CU00_NEXT_PAGE_FLG_LENGTH);
            assertThat(UserListRequest.CU00_USR_SEL_FLG_LENGTH).isEqualTo(CU00_USR_SEL_FLG_LENGTH);
            assertThat(UserListRequest.CU00_USR_SELECTED_LENGTH).isEqualTo(CU00_USR_SELECTED_LENGTH);

            int sum = CU00_USRID_FIRST_LENGTH + CU00_USRID_LAST_LENGTH + CU00_PAGE_NUM_DIGITS
                    + CU00_NEXT_PAGE_FLG_LENGTH + CU00_USR_SEL_FLG_LENGTH
                    + CU00_USR_SELECTED_LENGTH;
            assertThat(sum).isEqualTo(CU00_INFO_LENGTH).isEqualTo(34);
            assertThat(UserListRequest.CU00_INFO_LENGTH).isEqualTo(CU00_INFO_LENGTH);

            FixedWidthCodec codec = codec();
            String image = codec.movePicX("USER0001", CU00_USRID_FIRST_LENGTH)
                    + codec.movePicX("USER0010", CU00_USRID_LAST_LENGTH)
                    + codec.movePic9(1L, CU00_PAGE_NUM_DIGITS)
                    + codec.movePicX(NEXT_PAGE_YES_VALUE, CU00_NEXT_PAGE_FLG_LENGTH)
                    + codec.movePicX(USR_SEL_UPDATE_VALUE, CU00_USR_SEL_FLG_LENGTH)
                    + codec.movePicX("USER0001", CU00_USR_SELECTED_LENGTH);
            assertThat(codec.encodeImage(image, UserListRequest.CU00_PAGE_NUM_FIELD))
                    .hasSize(CU00_INFO_LENGTH);
        }

        @Test
        @DisplayName("the shared communication area stays 160 bytes, and 160 + 34 = 194")
        void theSharedAreaIsNotExtended() {
            assertThat(NavigationContext.GENERAL_INFO_LENGTH).isEqualTo(34);
            assertThat(NavigationContext.CUSTOMER_INFO_LENGTH).isEqualTo(84);
            assertThat(NavigationContext.ACCOUNT_INFO_LENGTH).isEqualTo(12);
            assertThat(NavigationContext.CARD_INFO_LENGTH).isEqualTo(16);
            assertThat(NavigationContext.MORE_INFO_LENGTH)
                    .as("CDEMO-LAST-MAP X(7) + CDEMO-LAST-MAPSET X(7)")
                    .isEqualTo(NavigationContext.LAST_MAP_LENGTH
                            + NavigationContext.LAST_MAPSET_LENGTH)
                    .isEqualTo(14);
            assertThat(NavigationContext.GENERAL_INFO_LENGTH
                    + NavigationContext.CUSTOMER_INFO_LENGTH
                    + NavigationContext.ACCOUNT_INFO_LENGTH
                    + NavigationContext.CARD_INFO_LENGTH
                    + NavigationContext.MORE_INFO_LENGTH)
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH)
                    .isEqualTo(COMMAREA_LENGTH);

            assertThat(NavigationContext.LAYOUT.recordLength()).isEqualTo(COMMAREA_LENGTH);
            assertThat(FixedWidthRecord.forLayout(NavigationContext.LAYOUT, MAP_CHARSET)
                    .toByteArray()).hasSize(COMMAREA_LENGTH);

            assertThat(COMMAREA_LENGTH + CU00_INFO_LENGTH)
                    .isEqualTo(CU00_COMMAREA_LENGTH).isEqualTo(194);
            assertThat(UserListRequest.CU00_COMMAREA_LENGTH).isEqualTo(CU00_COMMAREA_LENGTH);
            assertThat(populatedRequest().commareaLength())
                    .as("EIBCALEN for transaction CU00")
                    .isEqualTo(CU00_COMMAREA_LENGTH);
        }

        @Test
        @DisplayName("the extension is NOT folded into NavigationContext, which stays screen-agnostic")
        void theExtensionIsNotInTheSharedType() {
            List<String> sharedComponents = Arrays.stream(
                            NavigationContext.class.getRecordComponents())
                    .map(RecordComponent::getName).toList();
            assertThat(sharedComponents)
                    .doesNotContain("cdemoCu00UsrIdFirst", "cdemoCu00UsrIdLast", "cdemoCu00PageNum",
                            "cdemoCu00NextPageFlg", "cdemoCu00UsrSelFlg", "cdemoCu00UsrSelected");
            assertThat(sharedComponents.stream().filter(name -> name.contains("Cu0")).toList())
                    .as("no screen-specific member of any kind belongs to the shared area")
                    .isEmpty();
            assertThat(NavigationContext.LAYOUT.hasSpan(UserListRequest.CU00_PAGE_NUM_FIELD))
                    .isFalse();
        }

        @Test
        @DisplayName("the page number is an int, and no floating-point type appears anywhere")
        void thePageNumberIsIntegral() {
            RecordComponent pageNum = Arrays.stream(UserListRequest.class.getRecordComponents())
                    .filter(component -> "cdemoCu00PageNum".equals(component.getName()))
                    .findFirst()
                    .orElseThrow();
            assertThat(pageNum.getType()).isEqualTo(int.class);

            for (RecordComponent component : UserListRequest.class.getRecordComponents()) {
                assertThat(component.getType())
                        .as("%s must not be a floating-point type", component.getName())
                        .isNotIn(double.class, float.class, Double.class, Float.class);
            }
            for (RecordComponent component : UserListRow.class.getRecordComponents()) {
                assertThat(component.getType())
                        .isNotIn(double.class, float.class, Double.class, Float.class);
            }
            for (Method method : UserListRequest.class.getDeclaredMethods()) {
                assertThat(method.getReturnType())
                        .as("%s must not return a floating-point type", method.getName())
                        .isNotIn(double.class, float.class, Double.class, Float.class);
            }
        }

        @ParameterizedTest(name = "page number {0} renders as \"{1}\"")
        @CsvSource({"0, 00000000", "1, 00000001", "2, 00000002", "10, 00000010",
            "99999999, 99999999"})
        @DisplayName("the numeric page number renders as the zero-filled eight-character image")
        void thePageNumberRendersZeroFilled(int pageNumber, String expectedImage) {
            String image = codec().movePic9((long) pageNumber, UserListRequest.PAGENUM_LENGTH);
            assertThat(image).isEqualTo(expectedImage).hasSize(UserListRequest.PAGENUM_LENGTH);

            UserListRequest request = blankRequest()
                    .withCdemoCu00PageNum(pageNumber)
                    .withPageNum(image);
            assertThat(request.cdemoCu00PageNum()).isEqualTo(pageNumber);
            assertThat(request.pageNum())
                    .as("the two members are one value in two representations")
                    .isEqualTo(expectedImage);
            assertThat(codec().decodePic9AsInt(request.pageNum()))
                    .as("and the image decodes back to the number it was rendered from")
                    .isEqualTo(request.cdemoCu00PageNum());
        }

        @Test
        @DisplayName("the page number's bounds are PIC 9(08)'s own: zero is valid, negative is not")
        void thePageNumberIsBoundedByItsPictureClause() {
            assertThat(UserListRequest.CU00_PAGE_NUM_INITIAL)
                    .as("app/cbl/COUSR00C.cbl:227 zeroes the field before the first forward page")
                    .isZero();
            assertThat(UserListRequest.CU00_PAGE_NUM_MAX).isEqualTo(PAGE_NUM_MAX);
            assertThat(blankRequest().cdemoCu00PageNum()).isEqualTo(UserListRequest
                    .CU00_PAGE_NUM_INITIAL);
            assertThat(blankRequest().withCdemoCu00PageNum(PAGE_NUM_MAX).cdemoCu00PageNum())
                    .isEqualTo(PAGE_NUM_MAX);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> blankRequest().withCdemoCu00PageNum(-1))
                    .withMessageContaining("unsigned");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> blankRequest().withCdemoCu00PageNum(PAGE_NUM_MAX + 1))
                    .withMessageContaining("more than " + CU00_PAGE_NUM_DIGITS + " digits");
        }

        @Test
        @DisplayName("NEXT-PAGE-FLG defaults to 'N', as its VALUE clause declares")
        void theNextPageFlagDefaultsToNo() {
            UserListRequest blank = blankRequest();
            assertThat(blank.cdemoCu00NextPageFlg()).isEqualTo(NEXT_PAGE_FLG_DEFAULT);
            assertThat(blank.nextPageNo()).isTrue();
            assertThat(blank.nextPageYes()).isFalse();
            assertThat(blank.cdemoCu00UsrSelFlg())
                    .as("declared without a VALUE clause, so blank rather than 'N'")
                    .isEqualTo(" ")
                    .hasSize(CU00_USR_SEL_FLG_LENGTH);
        }

        @ParameterizedTest(name = "flag \"{0}\": NEXT-PAGE-YES={1}, NEXT-PAGE-NO={2}")
        @CsvSource({"Y, true, false", "N, false, true", "' ', false, false", "y, false, false",
            "n, false, false", "X, false, false"})
        @DisplayName("both 88-levels are driven in both readings, and a third value satisfies neither")
        void bothConditionNamesAreDrivenBothWays(String flag, boolean yes, boolean no) {
            UserListRequest request = blankRequest().withCdemoCu00NextPageFlg(flag);

            assertThat(request.nextPageYes()).as("NEXT-PAGE-YES for '%s'", flag).isEqualTo(yes);
            assertThat(request.nextPageNo()).as("NEXT-PAGE-NO for '%s'", flag).isEqualTo(no);
            assertThat(yes && no).as("the two conditions are mutually exclusive").isFalse();
            assertThat(request.cdemoCu00NextPageFlg())
                    .hasSize(UserListRequest.CU00_NEXT_PAGE_FLG_LENGTH);
        }

        @Test
        @DisplayName("the two convenience setters set exactly the two declared 88-level values")
        void theConvenienceSettersUseTheDeclaredValues() {
            assertThat(UserListRequest.NEXT_PAGE_YES).isEqualTo(NEXT_PAGE_YES_VALUE);
            assertThat(UserListRequest.NEXT_PAGE_NO).isEqualTo(NEXT_PAGE_NO_VALUE);

            UserListRequest yes = blankRequest().withNextPageYes();
            assertThat(yes.cdemoCu00NextPageFlg()).isEqualTo(NEXT_PAGE_YES_VALUE);
            assertThat(yes.nextPageYes()).isTrue();
            assertThat(yes.nextPageNo()).isFalse();

            UserListRequest no = yes.withNextPageNo();
            assertThat(no.cdemoCu00NextPageFlg()).isEqualTo(NEXT_PAGE_NO_VALUE);
            assertThat(no.nextPageNo()).isTrue();
            assertThat(no.nextPageYes()).isFalse();
        }

        @Test
        @DisplayName("the selection flag and the selected id are both carried, at 1 and 8 characters")
        void theSelectionPairIsCarried() {
            UserListRequest request = blankRequest()
                    .withCdemoCu00UsrSelFlg(USR_SEL_UPDATE_VALUE)
                    .withCdemoCu00UsrSelected(codec().movePicX("USER0001",
                            CU00_USR_SELECTED_LENGTH));

            assertThat(request.cdemoCu00UsrSelFlg()).hasSize(CU00_USR_SEL_FLG_LENGTH)
                    .isEqualTo(USR_SEL_UPDATE_VALUE);
            assertThat(request.cdemoCu00UsrSelected()).hasSize(CU00_USR_SELECTED_LENGTH)
                    .isEqualTo("USER0001");
            assertThat(request.usrSelUpdate()).isTrue();
            assertThat(request.usrSelDelete()).isFalse();

            UserListRequest delete = request.withCdemoCu00UsrSelFlg(USR_SEL_DELETE_VALUE);
            assertThat(delete.usrSelDelete()).isTrue();
            assertThat(delete.usrSelUpdate()).isFalse();

            assertThat(request.withCdemoCu00UsrSelFlg("u").usrSelUpdate()).isTrue();
            assertThat(request.withCdemoCu00UsrSelFlg("d").usrSelDelete()).isTrue();

            UserListRequest neither = request.withCdemoCu00UsrSelFlg("X");
            assertThat(neither.usrSelUpdate()).isFalse();
            assertThat(neither.usrSelDelete()).isFalse();
            assertThat(blankRequest().usrSelUpdate()).isFalse();
            assertThat(blankRequest().usrSelDelete()).isFalse();
        }

        @Test
        @DisplayName("the first and last user ids of the page are both carried, at eight characters")
        void thePageBoundaryIdsAreCarried() {
            UserListRequest request = blankRequest()
                    .withCdemoCu00UsrIdFirst(codec().movePicX("USER0001", CU00_USRID_FIRST_LENGTH))
                    .withCdemoCu00UsrIdLast(codec().movePicX("USER0010", CU00_USRID_LAST_LENGTH));

            assertThat(request.cdemoCu00UsrIdFirst()).isEqualTo("USER0001")
                    .hasSize(SEC_USR_ID_LENGTH);
            assertThat(request.cdemoCu00UsrIdLast()).isEqualTo("USER0010")
                    .hasSize(SEC_USR_ID_LENGTH);
            assertThat(blankRequest().cdemoCu00UsrIdFirst())
                    .as("blank is meaningful: it starts the browse at the beginning of the file")
                    .isEqualTo(" ".repeat(CU00_USRID_FIRST_LENGTH));
            assertThat(blankRequest().cdemoCu00UsrIdLast())
                    .isEqualTo(" ".repeat(CU00_USRID_LAST_LENGTH));
        }
    }

    @Nested
    @DisplayName("Row widths - from CSUSR01Y and the map, never from WS-USER-DATA")
    class RowWidthsComeFromCsusr01y {
        @Test
        @DisplayName("the four row data widths are SEC-USR-ID, -FNAME, -LNAME and -TYPE")
        void theRowWidthsAreTheSecurityRecordsOwn() {
            assertThat(UserListRequest.USRID_LENGTH).isEqualTo(SEC_USR_ID_LENGTH).isEqualTo(8);
            assertThat(UserListRequest.FNAME_LENGTH).isEqualTo(SEC_USR_FNAME_LENGTH).isEqualTo(20);
            assertThat(UserListRequest.LNAME_LENGTH).isEqualTo(SEC_USR_LNAME_LENGTH).isEqualTo(20);
            assertThat(UserListRequest.UTYPE_LENGTH).isEqualTo(SEC_USR_TYPE_LENGTH).isEqualTo(1);

            assertThat(UserListRequest.SEL_LENGTH).isEqualTo(1);
        }

        @Test
        @DisplayName("those widths match SecUserRecord's declared offsets and its 80-byte total")
        void theWidthsMatchTheRecordType() {
            assertThat(SecUserRecord.RECORD_LENGTH).isEqualTo(SEC_USER_DATA_LENGTH).isEqualTo(80);
            assertThat(SecUserRecord.SEC_USR_ID_OFFSET).isZero();
            assertThat(SecUserRecord.SEC_USR_FNAME_OFFSET).isEqualTo(8);
            assertThat(SecUserRecord.SEC_USR_LNAME_OFFSET).isEqualTo(28);
            assertThat(SecUserRecord.SEC_USR_PWD_OFFSET).isEqualTo(48);
            assertThat(SecUserRecord.SEC_USR_TYPE_OFFSET).isEqualTo(56);
            assertThat(SecUserRecord.SEC_USR_FILLER_OFFSET).isEqualTo(57);

            assertThat(SecUserRecord.SEC_USR_ID_LENGTH).isEqualTo(UserListRequest.USRID_LENGTH);
            assertThat(SecUserRecord.SEC_USR_FNAME_LENGTH).isEqualTo(UserListRequest.FNAME_LENGTH);
            assertThat(SecUserRecord.SEC_USR_LNAME_LENGTH).isEqualTo(UserListRequest.LNAME_LENGTH);
            assertThat(SecUserRecord.SEC_USR_TYPE_LENGTH).isEqualTo(UserListRequest.UTYPE_LENGTH);

            assertThat(SEC_USR_ID_LENGTH + SEC_USR_FNAME_LENGTH + SEC_USR_LNAME_LENGTH
                    + SEC_USR_PWD_LENGTH + SEC_USR_TYPE_LENGTH + SEC_USR_FILLER_LENGTH)
                    .isEqualTo(SEC_USER_DATA_LENGTH);

            assertThat(SecUserRecord.encode(SecUserRecord.of("USER0001", "First", "Last",
                    "NOTAREAL", NavigationContext.USER_TYPE_USER, MAP_CHARSET), MAP_CHARSET))
                    .hasSize(SecUserRecord.RECORD_LENGTH);
        }

        @Test
        @DisplayName("only four of the record's six items reach the screen")
        void twoRecordItemsAreNotProjected() {
            int projected = SEC_USR_ID_LENGTH + SEC_USR_FNAME_LENGTH + SEC_USR_LNAME_LENGTH
                    + SEC_USR_TYPE_LENGTH;
            assertThat(projected).isEqualTo(49);
            assertThat(SEC_USR_PWD_LENGTH + SEC_USR_FILLER_LENGTH).isEqualTo(31);
            assertThat(projected + SEC_USR_PWD_LENGTH + SEC_USR_FILLER_LENGTH)
                    .isEqualTo(SEC_USER_DATA_LENGTH);

            assertThat(SecUserRecord.blank().fieldImages().keySet())
                    .as("the record owns six items")
                    .containsExactly(SecUserRecord.FIELD_SEC_USR_ID, SecUserRecord.FIELD_SEC_USR_FNAME,
                            SecUserRecord.FIELD_SEC_USR_LNAME, SecUserRecord.FIELD_SEC_USR_PWD,
                            SecUserRecord.FIELD_SEC_USR_TYPE, SecUserRecord.FIELD_SEC_USR_FILLER);

            assertThat(UserListRequest.SEL_LENGTH + projected).isEqualTo(MAP_ROW_PAYLOAD_LENGTH);
        }

        @Test
        @DisplayName("the DTO follows the map's 20/20/1, not the display table's 25/8")
        void theDisplayTableWidthsAreNotAdopted() {
            assertThat(WS_USER_SEL_LENGTH + WS_USER_REC_FILLER_COUNT * WS_USER_REC_FILLER_LENGTH
                    + WS_USER_ID_LENGTH + WS_USER_NAME_LENGTH + WS_USER_TYPE_LENGTH)
                    .as("1 + 3 x 2 + 8 + 25 + 8")
                    .isEqualTo(WS_USER_REC_LENGTH)
                    .isEqualTo(48);

            assertThat(UserListRequest.FNAME_LENGTH)
                    .as("FNAME is 20, not the display table's 25")
                    .isNotEqualTo(WS_USER_NAME_LENGTH);
            assertThat(UserListRequest.LNAME_LENGTH).isNotEqualTo(WS_USER_NAME_LENGTH);
            assertThat(UserListRequest.UTYPE_LENGTH)
                    .as("UTYPE is 1, not the display table's 8")
                    .isNotEqualTo(WS_USER_TYPE_LENGTH);

            assertThat(UserListRequest.USRID_LENGTH).isEqualTo(WS_USER_ID_LENGTH);
            assertThat(UserListRequest.SEL_LENGTH).isEqualTo(WS_USER_SEL_LENGTH);

            assertThat(MAP_ROW_PAYLOAD_LENGTH).isEqualTo(50).isNotEqualTo(WS_USER_REC_LENGTH);
        }

        @Test
        @DisplayName("a row is rejected at 21 characters and accepted at 20")
        void aRowFieldWiderThanTheMapIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> UserListRow.blank()
                            .withFname("X".repeat(UserListRequest.FNAME_LENGTH + 1)))
                    .withMessageContaining(UserListRequest.FNAME_FIELD_STEM);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> UserListRow.blank().withFname("X".repeat(WS_USER_NAME_LENGTH)));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> UserListRow.blank().withUtype("X".repeat(WS_USER_TYPE_LENGTH)));

            assertThat(UserListRow.blank().withFname("X".repeat(UserListRequest.FNAME_LENGTH))
                    .fname()).hasSize(UserListRequest.FNAME_LENGTH);

            assertThat(codec().movePicX("X".repeat(WS_USER_NAME_LENGTH),
                    UserListRequest.FNAME_LENGTH)).hasSize(UserListRequest.FNAME_LENGTH);
        }

        @Test
        @DisplayName("a blank row is five fields of spaces, one per declared width")
        void aBlankRowIsSpacesNotNulls() {
            UserListRow blank = UserListRow.blank();
            assertThat(blank.sel()).isEqualTo(" ");
            assertThat(blank.usrId()).isEqualTo(" ".repeat(UserListRequest.USRID_LENGTH));
            assertThat(blank.fname()).isEqualTo(" ".repeat(UserListRequest.FNAME_LENGTH));
            assertThat(blank.lname()).isEqualTo(" ".repeat(UserListRequest.LNAME_LENGTH));
            assertThat(blank.utype()).isEqualTo(" ".repeat(UserListRequest.UTYPE_LENGTH));

            assertThat(new UserListRow(null, null, null, null, null))
                    .as("a null in every position becomes the blank row, never a null-bearing one")
                    .isEqualTo(blank);
            assertThat(UserListRequest.blankRows()).allSatisfy(row -> assertThat(row)
                    .isEqualTo(blank));
        }
    }

    @Nested
    @DisplayName("Conversation state - the commarea, the AID and the context flag are payload members")
    class ConversationState {
        @Test
        @DisplayName("the communication area is a member, so no session is needed to reconstruct it")
        void theCommareaIsCarried() {
            assertThat(Arrays.stream(UserListRequest.class.getRecordComponents())
                    .filter(component -> "navigationContext".equals(component.getName()))
                    .map(RecordComponent::getType).toList())
                    .containsExactly(NavigationContext.class);

            UserListRequest request = populatedRequest();
            assertThat(request.hasNavigationContext()).isTrue();
            assertThat(request.navigationContext()).isNotNull();
            assertThat(request.commareaLength()).isEqualTo(CU00_COMMAREA_LENGTH);

            List<String> names = new ArrayList<>(Arrays.stream(
                            UserListRequest.class.getRecordComponents())
                    .map(RecordComponent::getName).toList());
            Arrays.stream(UserListRequest.class.getDeclaredMethods()).map(Method::getName)
                    .forEach(names::add);
            assertThat(names).noneMatch(name -> name.toLowerCase(Locale.ROOT)
                    .contains("session"));
        }

        @Test
        @DisplayName("an absent communication area stays absent, because EIBCALEN = 0 is a real state")
        void anAbsentCommareaIsNotInvented() {
            UserListRequest cold = populatedRequest().withoutNavigationContext();
            assertThat(cold.navigationContext()).isNull();
            assertThat(cold.hasNavigationContext()).isFalse();
            assertThat(cold.commareaLength())
                    .as("EIBCALEN = 0 - the cold start")
                    .isZero();

            assertThat(cold.mapFields()).isEqualTo(populatedRequest().mapFields());
        }

        @Test
        @DisplayName("the AID travels as a five-character token, not as a raw EIBAID byte")
        void theAidTravelsAsAToken() {
            assertThat(UserListRequest.AID_LENGTH).isEqualTo(PfKeyResolver.AID_TOKEN_LENGTH)
                    .isEqualTo(5);
            assertThat(UserListRequest.AID_FIELD).isEqualTo("EIBAID");

            assertThat(Arrays.stream(UserListRequest.class.getRecordComponents())
                    .filter(component -> "aid".equals(component.getName()))
                    .map(RecordComponent::getType).toList())
                    .as("a String token, never a byte")
                    .containsExactly(String.class);

            UserListRequest request = blankRequest()
                    .withAid(PfKeyResolver.AidKey.ENTER.token());
            assertThat(request.aid()).isEqualTo("ENTER").hasSize(UserListRequest.AID_LENGTH);
        }

        @ParameterizedTest(name = "{0} resolves to the token {1}")
        @CsvSource({"ENTER, ENTER", "PFK03, PFK03", "PFK07, PFK07", "PFK08, PFK08",
            "CLEAR, CLEAR"})
        @DisplayName("every key this screen reacts to has a five-character token the payload can hold")
        void theKeysThisScreenReactsToAllFit(String key, String expectedToken) {
            PfKeyResolver.AidKey aidKey = PfKeyResolver.AidKey.valueOf(key);
            assertThat(aidKey.token()).isEqualTo(expectedToken)
                    .hasSize(PfKeyResolver.AID_TOKEN_LENGTH);
            assertThat(blankRequest().withAid(aidKey.token()).aid()).isEqualTo(expectedToken);
        }

        @Test
        @DisplayName("the four AID bytes COUSR00C names all resolve, and each token fits the member")
        void theFourAidBytesNamedBySourceResolve() {
            assertThat(PfKeyResolver.resolve(CicsAid.DFHENTER))
                    .contains(PfKeyResolver.AidKey.ENTER);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF3)).contains(PfKeyResolver.AidKey.PFK03);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF7)).contains(PfKeyResolver.AidKey.PFK07);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF8)).contains(PfKeyResolver.AidKey.PFK08);

            assertThat(PfKeyResolver.isEnter(CicsAid.DFHENTER)).isTrue();
            assertThat(PfKeyResolver.isPf3(CicsAid.DFHPF3)).isTrue();
            assertThat(PfKeyResolver.isPf7(CicsAid.DFHPF7)).isTrue();
            assertThat(PfKeyResolver.isPf8(CicsAid.DFHPF8)).isTrue();

            for (PfKeyResolver.AidKey key : PfKeyResolver.AidKey.values()) {
                assertThat(key.token())
                        .as("%s must fit the aid member", key.name())
                        .hasSize(UserListRequest.AID_LENGTH);
                assertThat(blankRequest().withAid(key.token()).aid()).isEqualTo(key.token());
            }
        }

        @ParameterizedTest(name = "the high function key at EIBAID {0} still fits the member")
        @ValueSource(ints = {0xC1, 0xC7, 0x4C})
        @DisplayName("a key from the upper twelve folds onto a five-character token too")
        void theUpperFunctionKeysAlsoFitTheMember(int eibAid) {
            Optional<PfKeyResolver.AidKey> resolved = PfKeyResolver.resolve((byte) eibAid);
            assertThat(resolved).isPresent();
            assertThat(resolved.orElseThrow().token())
                    .hasSize(UserListRequest.AID_LENGTH)
                    .startsWith("PFK");
            assertThat(blankRequest().withAid(resolved.orElseThrow().token()).aid())
                    .hasSize(UserListRequest.AID_LENGTH);
        }

        @Test
        @DisplayName("the twelve upper keys fold onto the twelve lower tokens, one for one")
        void theUpperTwelveFoldOntoTheLowerTwelve() {
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF13))
                    .as("PF13 reports as PF1, so the token set stays twelve wide")
                    .isEqualTo(PfKeyResolver.resolve(CicsAid.DFHPF1));
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF19))
                    .isEqualTo(PfKeyResolver.resolve(CicsAid.DFHPF7));
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF24))
                    .isEqualTo(PfKeyResolver.resolve(CicsAid.DFHPF12));

            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF19))
                    .contains(PfKeyResolver.AidKey.PFK07);
            assertThat(PfKeyResolver.AidKey.values())
                    .as("twelve function-key tokens plus ENTER, CLEAR, PA1 and PA2")
                    .hasSize(16);
        }

        @Test
        @DisplayName("a byte that is no AID at all resolves to nothing, and the payload stays blank")
        void anUnrecognisedAidResolvesToNothing() {
            Optional<PfKeyResolver.AidKey> resolved = PfKeyResolver.resolve((byte) 0x00);
            assertThat(resolved).isEmpty();

            String token = resolved.map(PfKeyResolver.AidKey::token)
                    .orElse(" ".repeat(UserListRequest.AID_LENGTH));
            assertThat(blankRequest().withAid(token).aid())
                    .hasSize(UserListRequest.AID_LENGTH)
                    .isBlank();
            assertThat(blankRequest().aid())
                    .as("a blank AID is the first-entry state, before any key has been pressed")
                    .isEqualTo(" ".repeat(UserListRequest.AID_LENGTH));
        }

        @Test
        @DisplayName("an AID token wider than five characters is rejected by name")
        void anOverWideAidTokenIsRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> blankRequest().withAid("PFK013"))
                    .withMessageContaining(UserListRequest.AID_FIELD);
        }

        @Test
        @DisplayName("both ENTER and REENTER are driven, and they are the only two states")
        void bothContextStatesAreDriven() {
            assertThat(NavigationContext.PGM_CONTEXT_ENTER).isEqualTo(PGM_CONTEXT_ENTER).isZero();
            assertThat(NavigationContext.PGM_CONTEXT_REENTER).isEqualTo(PGM_CONTEXT_REENTER)
                    .isEqualTo(1);

            UserListRequest enter = blankRequest()
                    .withNavigationContext(NavigationContext.empty().withPgmEnter());
            assertThat(enter.navigationContext().isEnter()).isTrue();
            assertThat(enter.navigationContext().isReenter()).isFalse();
            assertThat(enter.navigationContext().pgmContext()).isEqualTo(PGM_CONTEXT_ENTER);

            UserListRequest reenter = blankRequest()
                    .withNavigationContext(NavigationContext.empty().withPgmReenter());
            assertThat(reenter.navigationContext().isReenter()).isTrue();
            assertThat(reenter.navigationContext().isEnter()).isFalse();
            assertThat(reenter.navigationContext().pgmContext()).isEqualTo(PGM_CONTEXT_REENTER);
        }

        @Test
        @DisplayName("the commarea carries the user type PF3 routing depends on")
        void theCommareaCarriesTheUserType() {
            assertThat(NavigationContext.USER_TYPE_ADMIN).isEqualTo("A");
            assertThat(NavigationContext.USER_TYPE_USER).isEqualTo("U");
            assertThat(PF3_TARGET_PROGRAM).hasSize(UserListRequest.PGMNAME_LENGTH);

            NavigationContext admin = populatedRequest().navigationContext();
            assertThat(admin.isAdmin()).isTrue();
            assertThat(admin.isUser()).isFalse();
            assertThat(admin.fromTranid()).isEqualTo(TRANSACTION_ID);
            assertThat(admin.fromProgram()).isEqualTo(PROGRAM_NAME);
            assertThat(admin.lastMap()).isEqualTo(MAP_NAME);
            assertThat(admin.lastMapset()).isEqualTo(MAPSET_NAME);

            NavigationContext regular = NavigationContext.empty().withUserTypeUser();
            assertThat(regular.isUser()).isTrue();
            assertThat(regular.isAdmin()).isFalse();
        }
    }

    @Nested
    @DisplayName("REDEFINES overlays - fifty-nine attribute views over fifty-nine flag bytes")
    class RedefinesOverlays {
        @Test
        @DisplayName("the geometry tiles 1127 bytes exactly: 12 + 59 x 7 + 702")
        void theGeometryIsTheCopybooks() {
            assertThat(FIELD_PREFIX_LENGTH)
                    .as("xxxL 2 + xxxF 1 + FILLER X(4) = 7, and the output view's FILLER X(3) plus "
                            + "xxxC, xxxP, xxxH and xxxV is also 7, which is what lets COUSR0AO "
                            + "overlay COUSR0AI field for field")
                    .isEqualTo(7);
            assertThat(DECLARED_WIDTHS.stream().mapToInt(Integer::intValue).sum())
                    .isEqualTo(PAYLOAD_WIDTH_TOTAL);
            assertThat(TIOAPFX_PREFIX_LENGTH + DFHMDF_NAMED * FIELD_PREFIX_LENGTH
                    + PAYLOAD_WIDTH_TOTAL).isEqualTo(SYMBOLIC_MAP_LENGTH);
            assertThat(SYMBOLIC_MAP_LAYOUT.recordLength()).isEqualTo(SYMBOLIC_MAP_LENGTH);
            assertThat(SYMBOLIC_MAP_LAYOUT.storageSpans().stream()
                    .mapToInt(FixedWidthRecord.FieldSpan::length).sum())
                    .as("the storage spans, overlays excluded, sum to the record length")
                    .isEqualTo(SYMBOLIC_MAP_LENGTH);
        }

        @Test
        @DisplayName("fifty-nine per-field overlays are modelled; the group-level one is not")
        void onlyThePerFieldOverlaysAreModelled() {
            assertThat(SYMBOLIC_MAP_LAYOUT.redefinitions())
                    .as("one overlay per field, and the 60th - the group-level COUSR0AO - belongs to "
                            + "the response payload")
                    .hasSize(DFHMDF_NAMED);
            assertThat(DFHMDF_NAMED + 1).isEqualTo(COPYBOOK_REDEFINES_TOTAL).isEqualTo(60);
            assertThat(GROUP_REDEFINES_LINE)
                    .as("01 COUSR0AO REDEFINES COUSR0AI, one line after the last xxxI item")
                    .isEqualTo(COPYBOOK_LINES.get(DFHMDF_NAMED - 1) + 1);
            assertThat(SYMBOLIC_MAP_LAYOUT.hasSpan("COUSR0AO")).isFalse();
        }

        @ParameterizedTest(name = "{0}A redefines {0}F")
        @MethodSource("com.vsergeychik.carddemo.user.dto.UserListRequestTest#screenFields")
        @DisplayName("the flag view and the attribute view describe one and the same byte")
        void theTwoViewsShareOneByte(String screenField) {
            FixedWidthRecord.FieldSpan flag =
                    SYMBOLIC_MAP_LAYOUT.span(screenField + FLAG_ITEM_SUFFIX);
            FixedWidthRecord.FieldSpan attribute =
                    SYMBOLIC_MAP_LAYOUT.span(screenField + ATTRIBUTE_ITEM_SUFFIX);

            assertThat(attribute.offset())
                    .as("%sA starts where %sF starts", screenField, screenField)
                    .isEqualTo(flag.offset());
            assertThat(attribute.length())
                    .as("both are PICTURE X - one byte, and not a copy of one byte")
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

            int itemLine = COPYBOOK_LINES.get(SCREEN_FIELDS.indexOf(screenField));
            assertThat(itemLine + REDEFINES_LINE_OFFSET)
                    .as("02 FILLER REDEFINES %sF", screenField)
                    .isEqualTo(itemLine - 3);
            assertThat(itemLine + ATTRIBUTE_VIEW_LINE_OFFSET)
                    .as("03 %sA PICTURE X", screenField)
                    .isEqualTo(itemLine - 2);
        }

        @ParameterizedTest(name = "{0}: a write through either view is read by the other")
        @MethodSource("com.vsergeychik.carddemo.user.dto.UserListRequestTest#screenFields")
        @DisplayName("each overlay round-trips in both directions and touches nothing else")
        void theOverlayRoundTripsBothWays(String screenField) {
            FixedWidthRecord record = FixedWidthRecord.forLayout(SYMBOLIC_MAP_LAYOUT, MAP_CHARSET);
            FixedWidthRecord.FieldSpan flag =
                    SYMBOLIC_MAP_LAYOUT.span(screenField + FLAG_ITEM_SUFFIX);
            FixedWidthRecord.FieldSpan attribute =
                    SYMBOLIC_MAP_LAYOUT.span(screenField + ATTRIBUTE_ITEM_SUFFIX);
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
        @DisplayName("writing all fifty-nine attributes leaves every data item and filler byte alone")
        void attributeWritesDoNotDisturbTheData() {
            FixedWidthRecord record = FixedWidthRecord.forLayout(SYMBOLIC_MAP_LAYOUT, MAP_CHARSET);
            for (int index = 0; index < DFHMDF_NAMED; index++) {
                record.writeSpan(SYMBOLIC_MAP_LAYOUT.span(SYMBOLIC_MAP_ITEMS.get(index)),
                        "V".repeat(DECLARED_WIDTHS.get(index)));
            }
            for (String screenField : SCREEN_FIELDS) {
                record.writeSpan(SYMBOLIC_MAP_LAYOUT.span(screenField + ATTRIBUTE_ITEM_SUFFIX), "R");
            }
            for (int index = 0; index < DFHMDF_NAMED; index++) {
                assertThat(record.readSpan(SYMBOLIC_MAP_LAYOUT.span(SYMBOLIC_MAP_ITEMS.get(index))))
                        .as("%s must be untouched by the attribute writes",
                                SYMBOLIC_MAP_ITEMS.get(index))
                        .isEqualTo("V".repeat(DECLARED_WIDTHS.get(index)));
                assertThat(record.readSpan(
                        SYMBOLIC_MAP_LAYOUT.span(SCREEN_FIELDS.get(index) + FLAG_ITEM_SUFFIX)))
                        .as("and each flag byte still reads what the overlay wrote")
                        .isEqualTo("R");
            }
            assertThat(record.toByteArray()).hasSize(SYMBOLIC_MAP_LENGTH);
        }

        @Test
        @DisplayName("every data item sits seven bytes after the previous one ends")
        void theDataItemsAreSeparatedByTheirPrefixes() {
            int previousEnd = TIOAPFX_PREFIX_LENGTH;
            for (int index = 0; index < DFHMDF_NAMED; index++) {
                FixedWidthRecord.FieldSpan item =
                        SYMBOLIC_MAP_LAYOUT.span(SYMBOLIC_MAP_ITEMS.get(index));
                assertThat(item.offset())
                        .as("%s starts seven bytes past the end of the previous item",
                                SYMBOLIC_MAP_ITEMS.get(index))
                        .isEqualTo(previousEnd + FIELD_PREFIX_LENGTH);
                assertThat(item.length()).isEqualTo(DECLARED_WIDTHS.get(index));
                previousEnd = item.endOffsetExclusive();
            }
            assertThat(previousEnd)
                    .as("ERRMSGI ends at the end of 01 COUSR0AI")
                    .isEqualTo(SYMBOLIC_MAP_LENGTH);
        }
    }

    @Nested
    @DisplayName("Metadata - the length, flag and attribute items never reach the wire")
    class MetadataIsNotPayload {
        @Test
        @DisplayName("no xxxL, xxxF or xxxA name appears among the record's components")
        void theMetadataItemsAreNotMembers() {
            List<String> components = Arrays.stream(UserListRequest.class.getRecordComponents())
                    .map(RecordComponent::getName).toList();
            for (String screenField : SCREEN_FIELDS) {
                String stem = screenField.toLowerCase(Locale.ROOT);
                assertThat(components)
                        .as("%s%s is metadata, not a member", screenField, LENGTH_ITEM_SUFFIX)
                        .doesNotContain(stem + LENGTH_ITEM_SUFFIX.toLowerCase(Locale.ROOT),
                                stem + FLAG_ITEM_SUFFIX.toLowerCase(Locale.ROOT),
                                stem + ATTRIBUTE_ITEM_SUFFIX.toLowerCase(Locale.ROOT),
                                stem + OUTPUT_ITEM_SUFFIX.toLowerCase(Locale.ROOT));
            }
        }

        @Test
        @DisplayName("no xxxL, xxxF or xxxA name appears in the serialised form either")
        void theMetadataItemsAreNotSerialised() {
            Set<String> wireNames = jsonTreeOf(populatedRequest()).keySet();
            for (String screenField : SCREEN_FIELDS) {
                for (String suffix : List.of(LENGTH_ITEM_SUFFIX, FLAG_ITEM_SUFFIX,
                        ATTRIBUTE_ITEM_SUFFIX, OUTPUT_ITEM_SUFFIX)) {
                    assertThat(wireNames)
                            .doesNotContain(screenField + suffix,
                                    screenField.toLowerCase(Locale.ROOT) + suffix);
                }
            }
            assertThat(wireNames).doesNotContain("USRIDINL", "usrIdInL", "ERRMSGF", "ERRMSGA");
        }

        @Test
        @DisplayName("the TIOAPFX prefix and the per-field fillers are storage, never members")
        void theFillersAreNotMembers() {
            assertThat(TIOAPFX_PREFIX_LENGTH + DFHMDF_NAMED * ATTRIBUTE_FILLER_LENGTH)
                    .isEqualTo(248);

            List<FixedWidthRecord.FieldSpan> fillers = SYMBOLIC_MAP_LAYOUT.storageSpans().stream()
                    .filter(span -> span.kind().filler())
                    .toList();
            assertThat(fillers)
                    .as("the TIOAPFX prefix, and per field one length halfword and one X(4) filler")
                    .hasSize(1 + DFHMDF_NAMED * 2);
            assertThat(fillers.stream().mapToInt(FixedWidthRecord.FieldSpan::length).sum())
                    .as("12 + 59 x 4 filler bytes + 59 x 2 length-item bytes")
                    .isEqualTo(TIOAPFX_PREFIX_LENGTH + DFHMDF_NAMED * ATTRIBUTE_FILLER_LENGTH
                            + DFHMDF_NAMED * LENGTH_ITEM_LENGTH);

            Set<String> wireNames = jsonTreeOf(populatedRequest()).keySet();
            assertThat(wireNames).noneMatch(name -> name.toLowerCase(Locale.ROOT)
                    .contains("filler"));
            assertThat(wireNames).noneMatch(name -> name.toLowerCase(Locale.ROOT)
                    .contains("tioapfx"));
        }

        @Test
        @DisplayName("the row list itself is suppressed, so only the fifty numbered names travel")
        void theRowListIsNotAWireMember() {
            Set<String> wireNames = jsonTreeOf(populatedRequest()).keySet();
            assertThat(wireNames).doesNotContain("rows");
            assertThat(wireNames).contains("sel0001", "usrid01", "fname01", "lname01", "utype01",
                    "sel0010", "usrid10", "fname10", "lname10", "utype10");
            assertThat(Arrays.stream(UserListRequest.class.getRecordComponents())
                    .map(RecordComponent::getName).toList())
                    .as("internally it is one component, on the wire it is fifty names")
                    .contains("rows");
        }

        @Test
        @DisplayName("no attribute or colour member exists, because this screen sets none")
        void noAttributeMemberExists() {
            List<String> names = new ArrayList<>(Arrays.stream(
                            UserListRequest.class.getRecordComponents())
                    .map(RecordComponent::getName).toList());
            Arrays.stream(UserListRow.class.getRecordComponents()).map(RecordComponent::getName)
                    .forEach(names::add);
            names.addAll(jsonTreeOf(populatedRequest()).keySet());

            assertThat(names).noneMatch(name -> {
                String lower = name.toLowerCase(Locale.ROOT);
                return lower.contains("attrib") || lower.contains("colour") || lower.contains("color")
                        || lower.contains("highlight") || lower.contains("dfh");
            });
            assertThat(UserListRequest.mapFieldNames())
                    .as("the fifty-nine field labels are field names, never attribute names")
                    .noneMatch(field -> field.endsWith(ATTRIBUTE_ITEM_SUFFIX + ATTRIBUTE_ITEM_SUFFIX)
                            || field.startsWith("DFH"));
        }

        @Test
        @DisplayName("the derived predicates are not properties, so none of them reaches the wire")
        void theDerivedPredicatesAreNotProperties() {
            Set<String> wireNames = jsonTreeOf(populatedRequest()).keySet();
            assertThat(wireNames).doesNotContain("nextPageYes", "nextPageNo", "usrSelUpdate",
                    "usrSelDelete", "hasNavigationContext", "commareaLength", "mapFields",
                    "mapFieldNames");
        }
    }

    @Nested
    @DisplayName("Validation - @Size maxima only, and blank is always valid")
    class ValidationConstraints {
        @Test
        @DisplayName("no member carries @NotBlank, @NotNull, @NotEmpty or @Pattern")
        void noMemberIsMandatory() {
            for (RecordComponent component : UserListRequest.class.getRecordComponents()) {
                for (Annotation annotation : component.getAccessor().getAnnotations()) {
                    assertThat(annotation.annotationType().getName())
                            .as("%s carries a constraint COUSR00C does not perform",
                                    component.getName())
                            .doesNotContain("NotBlank")
                            .doesNotContain("NotNull")
                            .doesNotContain("NotEmpty")
                            .doesNotContain("Pattern")
                            .doesNotContain("Email")
                            .doesNotContain("Positive")
                            .doesNotContain("Min")
                            .doesNotContain("Max");
                }
            }
            for (RecordComponent component : UserListRow.class.getRecordComponents()) {
                for (Annotation annotation : component.getAccessor().getAnnotations()) {
                    assertThat(annotation.annotationType().getName())
                            .as("row member %s must be width-constrained and nothing more",
                                    component.getName())
                            .doesNotContain("NotBlank")
                            .doesNotContain("NotNull")
                            .doesNotContain("Pattern");
                }
            }
        }

        @Test
        @DisplayName("the character members are size-constrained; the int and the commarea are not")
        void theConstraintCensusMatchesTheMembers() {
            int sized = 0;
            for (RecordComponent component : UserListRequest.class.getRecordComponents()) {
                if (component.getAccessor().getAnnotation(Size.class) != null) {
                    sized++;
                }
            }
            assertThat(sized)
                    .as("8 header and paging + rows + errMsg + 5 character CDEMO-CU00-* members + aid")
                    .isEqualTo(16);

            assertThat(Arrays.stream(UserListRequest.class.getRecordComponents())
                    .filter(component -> component.getAccessor().getAnnotation(Size.class) == null)
                    .map(RecordComponent::getName).toList())
                    .as("an int is bounded by its PICTURE clause and the commarea validates itself")
                    .containsExactly("cdemoCu00PageNum", "navigationContext");
        }

        @ParameterizedTest(name = "{0} is @Size(max = {1})")
        @MethodSource(
                "com.vsergeychik.carddemo.user.dto.UserListRequestTest#membersAndWidths")
        @DisplayName("every @Size maximum equals its xxxI PICTURE length")
        void eachSizeMaximumEqualsTheDeclaredWidth(String member, int declaredWidth) {
            int index = MAP_MEMBERS.indexOf(member);
            assertThat(index).isNotNegative();

            Size size = sizeOf(member);
            assertThat(size).as("%s must be width-constrained", member).isNotNull();
            assertThat(size.max())
                    .as("%s is %s PIC X(%d)", member, SYMBOLIC_MAP_ITEMS.get(index), declaredWidth)
                    .isEqualTo(declaredWidth);
        }

        private Size sizeOf(String member) {
            for (RecordComponent component : UserListRequest.class.getRecordComponents()) {
                if (component.getName().equals(member)) {
                    return component.getAccessor().getAnnotation(Size.class);
                }
            }
            String rowComponent = rowComponentFor(member);
            for (RecordComponent component : UserListRow.class.getRecordComponents()) {
                if (component.getName().equals(rowComponent)) {
                    return component.getAccessor().getAnnotation(Size.class);
                }
            }
            throw new AssertionError("No member or row component matches " + member);
        }

        private String rowComponentFor(String member) {
            if (member.startsWith("sel")) {
                return "sel";
            }
            if (member.startsWith("usrId") && !"usrIdIn".equals(member)) {
                return "usrId";
            }
            if (member.startsWith("fname")) {
                return "fname";
            }
            if (member.startsWith("lname")) {
                return "lname";
            }
            if (member.startsWith("utype")) {
                return "utype";
            }
            return member;
        }

        @Test
        @DisplayName("the row list is constrained to exactly ten, and is cascaded into")
        void theRowListIsConstrainedToTen() {
            RecordComponent rows = Arrays.stream(UserListRequest.class.getRecordComponents())
                    .filter(component -> "rows".equals(component.getName()))
                    .findFirst()
                    .orElseThrow();
            Size size = rows.getAccessor().getAnnotation(Size.class);
            assertThat(size).isNotNull();
            assertThat(size.min()).isEqualTo(UserListRequest.ROW_COUNT);
            assertThat(size.max()).isEqualTo(UserListRequest.ROW_COUNT);

            assertThat(Arrays.stream(rows.getAccessor().getAnnotations())
                    .map(annotation -> annotation.annotationType().getSimpleName()).toList())
                    .as("@Valid cascades into each row, so a row's own widths are validated too")
                    .contains("Valid");
        }

        @Test
        @DisplayName("a wholly blank request is valid, because the program answers blanks with text")
        void aBlankRequestIsValid() {
            assertThat(validate(blankRequest()))
                    .as("a 400 here would replace 'Invalid selection. Valid values are U and D' with "
                            + "a different observable behaviour")
                    .isEmpty();
            assertThat(validate(populatedRequest())).isEmpty();
        }

        @Test
        @DisplayName("a null in every position is valid too, so nothing precedes the program's own test")
        void aNullInEveryPositionIsValid() {
            UserListRequest allNull = new UserListRequest(null, null, null, null, null, null, null,
                    null, null, null, null, null, UserListRequest.CU00_PAGE_NUM_INITIAL, null, null,
                    null, null, null);
            assertThat(validate(allNull)).isEmpty();
            assertThat(allNull.trnName()).isEqualTo(" ".repeat(UserListRequest.TRNNAME_LENGTH));
            assertThat(allNull.rows()).hasSize(UserListRequest.ROW_COUNT);
            assertThat(allNull.hasNavigationContext())
                    .as("the commarea is the one member that stays absent when it is absent")
                    .isFalse();
        }

        @ParameterizedTest(name = "{0} refuses {1} + 1 characters")
        @MethodSource(
                "com.vsergeychik.carddemo.user.dto.UserListRequestTest#membersAndWidths")
        @DisplayName("one character over the declared width is exactly one violation, on that member")
        void oneCharacterTooManyIsOneViolation(String member, int declaredWidth) {
            String overWide = "X".repeat(declaredWidth + 1);
            String exactWidth = "X".repeat(declaredWidth);

            if (isRecordComponent(member)) {
                Set<ConstraintViolation<UserListRequest>> violations =
                        validateValue(member, overWide);
                assertThat(violations).as("%s is a component of this record", member).hasSize(1);
                assertThat(violations.iterator().next().getPropertyPath()).hasToString(member);
                assertThat(validateValue(member, exactWidth))
                        .as("exactly the declared width is accepted, so the boundary is inclusive")
                        .isEmpty();
                assertThat(validateValue(member, null))
                        .as("@Size is satisfied by null, which is what lets it bound a width without "
                                + "making a field mandatory")
                        .isEmpty();
            } else {
                String component = rowComponentFor(member);
                Set<ConstraintViolation<UserListRow>> violations =
                        validateRowValue(component, overWide);
                assertThat(violations)
                        .as("%s projects the row component %s", member, component)
                        .hasSize(1);
                assertThat(violations.iterator().next().getPropertyPath()).hasToString(component);
                assertThat(validateRowValue(component, exactWidth)).isEmpty();
                assertThat(validateRowValue(component, null)).isEmpty();
            }

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> assign(member, overWide))
                    .withMessageContaining("PIC X(" + declaredWidth + ")");
        }

        private void assign(String member, String value) {
            int index = MAP_MEMBERS.indexOf(member);
            UserListRequest request = blankRequest();
            switch (member) {
                case "trnName" -> request.withTrnName(value);
                case "title01" -> request.withTitle01(value);
                case "curDate" -> request.withCurDate(value);
                case "pgmName" -> request.withPgmName(value);
                case "title02" -> request.withTitle02(value);
                case "curTime" -> request.withCurTime(value);
                case "pageNum" -> request.withPageNum(value);
                case "usrIdIn" -> request.withUsrIdIn(value);
                case "errMsg" -> request.withErrMsg(value);
                default -> assignRowMember(request, index, value);
            }
        }

        private void assignRowMember(UserListRequest request, int index, String value) {
            int rowNumber = (index - UserListRequest.HEADER_FIELD_COUNT)
                    / UserListRequest.ROW_FIELD_COUNT + 1;
            int within = (index - UserListRequest.HEADER_FIELD_COUNT)
                    % UserListRequest.ROW_FIELD_COUNT;
            UserListRow row = request.row(rowNumber);
            switch (within) {
                case 0 -> row.withSel(value);
                case 1 -> row.withUsrId(value);
                case 2 -> row.withFname(value);
                case 3 -> row.withLname(value);
                default -> row.withUtype(value);
            }
        }

        @Test
        @DisplayName("a row list of the wrong size is one violation on rows, and refused outright too")
        void theRowCountIsBothConstrainedAndEnforced() {
            assertThat(validateValue("rows", List.of(UserListRow.blank())))
                    .as("@Size(min = 10, max = 10) on the row list")
                    .hasSize(1);
            assertThat(validateValue("rows", UserListRequest.blankRows())).isEmpty();
            assertThat(validateValue("rows", null))
                    .as("absent is normalised to ten blank rows, so null cannot violate the bound")
                    .isEmpty();
        }

        @Test
        @DisplayName("the aid member is width-constrained at five, like the token it carries")
        void theAidMemberIsConstrained() {
            RecordComponent aid = Arrays.stream(UserListRequest.class.getRecordComponents())
                    .filter(component -> "aid".equals(component.getName()))
                    .findFirst()
                    .orElseThrow();
            assertThat(aid.getAccessor().getAnnotation(Size.class).max())
                    .isEqualTo(UserListRequest.AID_LENGTH);
            assertThat(validateValue("aid", "PFK013")).hasSize(1);
            assertThat(validateValue("aid", PfKeyResolver.AidKey.PFK12.token())).isEmpty();
        }

        @Test
        @DisplayName("the five character CDEMO-CU00-* members are constrained at their own widths")
        void theExtensionMembersAreConstrained() {
            assertThat(validateValue("cdemoCu00UsrIdFirst", "X".repeat(CU00_USRID_FIRST_LENGTH + 1)))
                    .hasSize(1);
            assertThat(validateValue("cdemoCu00UsrIdLast", "X".repeat(CU00_USRID_LAST_LENGTH + 1)))
                    .hasSize(1);
            assertThat(validateValue("cdemoCu00NextPageFlg", "YY")).hasSize(1);
            assertThat(validateValue("cdemoCu00UsrSelFlg", "UU")).hasSize(1);
            assertThat(validateValue("cdemoCu00UsrSelected", "X".repeat(
                    CU00_USR_SELECTED_LENGTH + 1))).hasSize(1);

            assertThat(validateValue("cdemoCu00UsrIdFirst", "X".repeat(CU00_USRID_FIRST_LENGTH)))
                    .isEmpty();
            assertThat(validateValue("cdemoCu00NextPageFlg", NEXT_PAGE_YES_VALUE)).isEmpty();
            assertThat(validateValue("cdemoCu00UsrSelFlg", USR_SEL_DELETE_VALUE)).isEmpty();
        }

        @Test
        @DisplayName("the page number has no annotation, because its PICTURE clause is the bound")
        void thePageNumberIsBoundedWithoutAnAnnotation() {
            RecordComponent pageNum = Arrays.stream(UserListRequest.class.getRecordComponents())
                    .filter(component -> "cdemoCu00PageNum".equals(component.getName()))
                    .findFirst()
                    .orElseThrow();
            assertThat(pageNum.getAccessor().getAnnotations())
                    .as("neither @Min nor @Max: PIC 9(08) is unsigned and eight digits wide, and the "
                            + "constructor enforces exactly that")
                    .isEmpty();
            assertThat(validate(blankRequest().withCdemoCu00PageNum(PAGE_NUM_MAX))).isEmpty();
        }
    }

    @Nested
    @DisplayName("Serialisation - sixty-seven untransformed names, and padding that survives")
    class JsonRoundTrip {
        @Test
        @DisplayName("the wire carries exactly the sixty-seven expected names, and no others")
        void theWireNamesAreExactlyTheExpectedOnes() {
            Set<String> wireNames = jsonTreeOf(populatedRequest()).keySet();
            assertThat(wireNames).hasSize(WIRE_MEMBER_COUNT).isEqualTo(EXPECTED_JSON_MEMBERS);
            assertThat(EXPECTED_JSON_MEMBERS).hasSize(WIRE_MEMBER_COUNT);
            assertThat(wireNames).containsAll(wireNamesOf(MAP_MEMBERS));
        }

        @Test
        @DisplayName("the names are untransformed - camel case, exactly as declared")
        void theNamesAreNotTransformed() {
            Set<String> wireNames = jsonTreeOf(populatedRequest()).keySet();
            assertThat(wireNames).contains("trnname", "curdate", "pgmname", "usridin", "pagenum",
                    "errmsg", "cdemoCu00UsrIdFirst", "cdemoCu00NextPageFlg", "navigationContext");
            assertThat(wireNames).doesNotContain("trn_name", "cur_date", "pgm_name", "usr_id_in",
                    "page_num", "err_msg", "cdemo_cu00_usrid_first", "TRNNAME");
        }

        @Test
        @DisplayName("the fifty numbered row names are all present, in copybook order")
        void theFiftyRowNamesAreAllPresent() {
            List<String> wireOrder = List.copyOf(jsonTreeOf(populatedRequest()).keySet());
            assertThat(wireOrder.subList(0, DFHMDF_NAMED - 1))
                    .as("the header and the fifty row names come first, in the copybook's order")
                    .containsExactlyElementsOf(
                            wireNamesOf(MAP_MEMBERS).subList(0, DFHMDF_NAMED - 1));
            assertThat(wireOrder.get(DFHMDF_NAMED - 1))
                    .as("errmsg closes the map fields, as ERRMSGI closes 01 COUSR0AI")
                    .isEqualTo("errmsg");
            assertThat(wireOrder.subList(DFHMDF_NAMED, WIRE_MEMBER_COUNT))
                    .as("then the CU00 extension, then the conversation")
                    .containsExactly("cdemoCu00UsrIdFirst", "cdemoCu00UsrIdLast", "cdemoCu00PageNum",
                            "cdemoCu00NextPageFlg", "cdemoCu00UsrSelFlg", "cdemoCu00UsrSelected",
                            "navigationContext", "aid");
        }

        @Test
        @DisplayName("a fully populated ten-row request survives the round trip byte for byte")
        void aFullyPopulatedRequestRoundTrips() {
            UserListRequest before = populatedRequest();
            UserListRequest after = deserialise(serialise(before));

            assertThat(after).isEqualTo(before);
            assertThat(after.mapFields())
                    .as("all fifty-nine screen fields, value for value")
                    .isEqualTo(before.mapFields());
            for (int rowNumber = FIRST_SUBSCRIPT; rowNumber <= LAST_SUBSCRIPT; rowNumber++) {
                assertThat(after.row(rowNumber))
                        .as("row %d must survive the projection onto fifty names and back", rowNumber)
                        .isEqualTo(before.row(rowNumber));
            }
            assertThat(after.navigationContext()).isEqualTo(before.navigationContext());
            assertThat(after.cdemoCu00PageNum()).isEqualTo(before.cdemoCu00PageNum());
            assertThat(after.nextPageYes()).isEqualTo(before.nextPageYes());
            assertThat(after.aid()).isEqualTo(before.aid());
        }

        @Test
        @DisplayName("space padding survives, and is neither trimmed nor coerced to null")
        void paddingSurvivesTheRoundTrip() {
            FixedWidthCodec codec = codec();
            UserListRequest before = blankRequest()
                    .withUsrIdIn(codec.movePicX("AB", UserListRequest.USRIDIN_LENGTH))
                    .withErrMsg(codec.movePicX("", UserListRequest.ERRMSG_LENGTH))
                    .withPageNum(codec.movePic9(1L, UserListRequest.PAGENUM_LENGTH));
            UserListRequest after = deserialise(serialise(before));

            assertThat(after.usrIdIn())
                    .as("a two-character id padded to eight stays eight characters")
                    .isEqualTo("AB      ")
                    .hasSize(UserListRequest.USRIDIN_LENGTH);
            assertThat(after.errMsg())
                    .as("seventy-eight spaces are seventy-eight spaces, not an empty string and not "
                            + "null - a default mapper would coerce this one to null")
                    .isEqualTo(" ".repeat(UserListRequest.ERRMSG_LENGTH))
                    .isNotNull()
                    .isNotEmpty();
            assertThat(after.pageNum()).isEqualTo("00000001");
            assertThat(after).isEqualTo(before);
        }

        @Test
        @DisplayName("a blank request round-trips too, so first entry is expressible on the wire")
        void aBlankRequestRoundTrips() {
            UserListRequest before = blankRequest();
            UserListRequest after = deserialise(serialise(before));

            assertThat(after).isEqualTo(before);
            assertThat(after.rows()).hasSize(UserListRequest.ROW_COUNT)
                    .allSatisfy(row -> assertThat(row).isEqualTo(UserListRow.blank()));
            assertThat(after.cdemoCu00NextPageFlg()).isEqualTo(NEXT_PAGE_FLG_DEFAULT);
            assertThat(after.cdemoCu00PageNum()).isEqualTo(UserListRequest.CU00_PAGE_NUM_INITIAL);
        }

        @Test
        @DisplayName("an absent commarea round-trips as absent, not as an empty one")
        void anAbsentCommareaRoundTrips() {
            UserListRequest before = populatedRequest().withoutNavigationContext();
            UserListRequest after = deserialise(serialise(before));

            assertThat(after.hasNavigationContext()).isFalse();
            assertThat(after.navigationContext()).isNull();
            assertThat(after.commareaLength()).isZero();
            assertThat(after).isEqualTo(before);
            assertThat(jsonTreeOf(before))
                    .as("the name is still emitted, carrying null - omitting it would make absent and "
                            + "unset indistinguishable")
                    .containsKey("navigationContext");
        }

        @Test
        @DisplayName("the page number is emitted as a plain integer, never in exponent form")
        void thePageNumberIsEmittedAsAnInteger() {
            String json = serialise(blankRequest().withCdemoCu00PageNum(PAGE_NUM_MAX));
            assertThat(json).contains("\"cdemoCu00PageNum\":" + PAGE_NUM_MAX);
            assertThat(json).doesNotContain("9.9999999E7").doesNotContain("E+");
            assertThat(deserialise(json).cdemoCu00PageNum()).isEqualTo(PAGE_NUM_MAX);
        }

        @Test
        @DisplayName("an over-wide value on the wire is refused during deserialisation, not truncated")
        void anOverWideWireValueIsRefused() {
            String json = serialise(blankRequest()).replace("\"trnname\":\"    \"",
                    "\"trnname\":\"TOOLONG\"");
            assertThatExceptionOfType(Exception.class)
                    .isThrownBy(() -> deserialise(json))
                    .withStackTraceContaining(UserListRequest.TRNNAME_FIELD);
        }
    }

    @Nested
    @DisplayName("Security posture - no password on this screen, and nothing added")
    class SecurityPosture {
        @Test
        @DisplayName("no PASSWD field exists on this map, so none is projected")
        void thereIsNoPasswordField() {
            assertThat(SCREEN_FIELDS).doesNotContain("PASSWD", "PASSWORD", "PWD");
            assertThat(SYMBOLIC_MAP_ITEMS).doesNotContain("PASSWDI", "PASSWORDI");
            assertThat(MAP_MEMBERS).doesNotContain("passwd", "password", "pwd");
            assertThat(UserListRequest.mapFieldNames()).doesNotContain("PASSWD");

            assertThat(Arrays.stream(UserListRequest.class.getRecordComponents())
                    .map(RecordComponent::getName).toList())
                    .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("pass")
                            || name.toLowerCase(Locale.ROOT).contains("pwd")
                            || name.toLowerCase(Locale.ROOT).contains("secret"));
            assertThat(Arrays.stream(UserListRow.class.getRecordComponents())
                    .map(RecordComponent::getName).toList())
                    .as("a row carries the id, the two names and the type - never the password")
                    .containsExactly("sel", "usrId", "fname", "lname", "utype");
            assertThat(jsonTreeOf(populatedRequest()).keySet())
                    .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("pass"));
        }

        @Test
        @DisplayName("the record's own password item is 8 bytes and is simply not projected")
        void theRecordsPasswordIsNotProjected() {
            assertThat(SecUserRecord.SEC_USR_PWD_LENGTH).isEqualTo(SEC_USR_PWD_LENGTH).isEqualTo(8);
            assertThat(SecUserRecord.FIELD_SEC_USR_PWD).isEqualTo("SEC-USR-PWD");
            assertThat(UserListRequest.mapFieldNames())
                    .doesNotContain(SecUserRecord.FIELD_SEC_USR_PWD);

            SecUserRecord record = SecUserRecord.of("USER0001", "First", "Last", "NOTAREAL",
                    NavigationContext.USER_TYPE_USER, MAP_CHARSET);
            UserListRow row = new UserListRow(" ", record.secUsrId(), record.secUsrFname(),
                    record.secUsrLname(), record.secUsrType());
            assertThat(row.usrId()).isEqualTo(record.secUsrId());
            assertThat(row.fname()).isEqualTo(record.secUsrFname());
            assertThat(row.lname()).isEqualTo(record.secUsrLname());
            assertThat(row.utype()).isEqualTo(record.secUsrType());
            assertThat(List.of(row.sel(), row.usrId(), row.fname(), row.lname(), row.utype()))
                    .doesNotContain(record.secUsrPwd());
        }

        @Test
        @DisplayName("no encoder, token or security type is introduced by this payload")
        void nothingSecurityShapedIsIntroduced() {
            List<String> names = new ArrayList<>();
            Arrays.stream(UserListRequest.class.getDeclaredMethods()).map(Method::getName)
                    .forEach(names::add);
            Arrays.stream(UserListRequest.class.getRecordComponents()).map(RecordComponent::getName)
                    .forEach(names::add);
            Arrays.stream(UserListRequest.class.getDeclaredFields())
                    .map(Field::getName).forEach(names::add);

            assertThat(names).noneMatch(name -> {
                String lower = name.toLowerCase(Locale.ROOT);
                return lower.contains("encoder") || lower.contains("bcrypt") || lower.contains("jwt")
                        || lower.contains("token") && !lower.contains("aid")
                        || lower.contains("authenticat") || lower.contains("credential");
            });

            for (RecordComponent component : UserListRequest.class.getRecordComponents()) {
                assertThat(component.getType().getName())
                        .as("%s must not be a Spring Security type", component.getName())
                        .doesNotContain("springframework.security");
            }
        }

        @Test
        @DisplayName("a row's diagnostic form does not reproduce the names it carries")
        void theRowsDiagnosticFormDoesNotLeakNames() {
            UserListRow row = UserListRow.blank()
                    .withUsrId("USER0001")
                    .withFname("Reginald")
                    .withLname("Fotheringay")
                    .withUtype(NavigationContext.USER_TYPE_USER);

            assertThat(row.toString())
                    .contains("USER0001")
                    .doesNotContain("Reginald")
                    .doesNotContain("Fotheringay");
            assertThat(row.fname()).startsWith("Reginald");
            assertThat(row.lname()).startsWith("Fotheringay");
        }

        @Test
        @DisplayName("this suite holds no mutable static state of its own")
        void thisSuiteHoldsNoMutableStaticState() {
            for (Field field : UserListRequestTest.class.getDeclaredFields()) {
                if (field.isSynthetic()) {
                    continue;
                }
                assertThat(Modifier.isStatic(field.getModifiers()))
                        .as("%s must be static", field.getName())
                        .isTrue();
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("%s must be final", field.getName())
                        .isTrue();
            }
        }
    }

}

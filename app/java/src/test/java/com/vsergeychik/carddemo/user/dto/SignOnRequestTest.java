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
 * Unit tests for {@link SignOnRequest} - the inbound payload of {@code POST /api/signon}, CICS transaction
 * {@code CC00}, program {@code app/cbl/COSGN00C.cbl}, map {@code COSGN0A} of mapset {@code COSGN00}.
 */
@DisplayName("SignOnRequest - the COSGN00 (CC00) sign-on inbound payload")
class SignOnRequestTest {
    private static final Charset MAP_CHARSET = StandardCharsets.US_ASCII;

    private static final String MAP_NAME = "COSGN0A";

    private static final String MAPSET_NAME = "COSGN00";

    private static final String TRANSACTION_ID = "CC00";

    private static final String PROGRAM_NAME = "COSGN00C";

    private static final int SCREEN_ROWS = 24;

    private static final int SCREEN_COLUMNS = 80;

    private static final int DFHMDF_TOTAL = 37;

    private static final int DFHMDF_NAMED = 11;

    private static final List<String> MAP_MEMBERS = List.of(
            "trnName", "title01", "curDate", "pgmName", "title02", "curTime",
            "applId", "sysId", "userId", "passwd", "errMsg");

    private static final List<String> SYMBOLIC_MAP_ITEMS = List.of(
            "TRNNAMEI", "TITLE01I", "CURDATEI", "PGMNAMEI", "TITLE02I", "CURTIMEI",
            "APPLIDI", "SYSIDI", "USERIDI", "PASSWDI", "ERRMSGI");

    private static final List<String> SCREEN_FIELDS = List.of(
            "TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02", "CURTIME",
            "APPLID", "SYSID", "USERID", "PASSWD", "ERRMSG");

    private static String wireNameOf(String member) {
        return MAP_MEMBERS.contains(member) ? member.toLowerCase(Locale.ROOT) : member;
    }

    private static List<String> wireNamesOf(List<String> members) {
        return members.stream().map(SignOnRequestTest::wireNameOf).toList();
    }

    private static final List<Integer> DECLARED_WIDTHS = List.of(4, 40, 8, 8, 40, 9, 8, 8, 8, 8, 78);

    private static final List<Integer> PUBLISHED_WIDTHS = List.of(
            SignOnRequest.TRNNAME_LENGTH,
            SignOnRequest.TITLE01_LENGTH,
            SignOnRequest.CURDATE_LENGTH,
            SignOnRequest.PGMNAME_LENGTH,
            SignOnRequest.TITLE02_LENGTH,
            SignOnRequest.CURTIME_LENGTH,
            SignOnRequest.APPLID_LENGTH,
            SignOnRequest.SYSID_LENGTH,
            SignOnRequest.USERID_LENGTH,
            SignOnRequest.PASSWD_LENGTH,
            SignOnRequest.ERRMSG_LENGTH);

    private static final int SEC_USR_ID_LENGTH = 8;

    private static final int SEC_USR_PWD_LENGTH = 8;

    private static final String CURTIME_INITIAL = "Ahh:mm:ss";

    private static final String CURDATE_INITIAL = "mm/dd/yy";

    private static final List<Integer> COPYBOOK_LINES = List.of(24, 30, 36, 42, 48, 54, 60, 66, 72,
            78, 84);

    private static final List<Integer> MAPSET_LINES = List.of(34, 38, 47, 57, 61, 70, 80, 89, 156,
            175, 197);

    private static final List<Integer> REDEFINES_LINES = List.of(21, 27, 33, 39, 45, 51, 57, 63, 69,
            75, 81);

    private static final List<String> STATE_MEMBERS = List.of("navigationContext", "aid");

    private static final int COMPONENT_COUNT = 13;

    private static final int TIOAPFX_PREFIX_LENGTH = 12;

    private static final int LENGTH_ITEM_LENGTH = 2;

    private static final int ATTRIBUTE_ITEM_LENGTH = 1;

    private static final int ATTRIBUTE_FILLER_LENGTH = 4;

    private static final int FIELD_PREFIX_LENGTH =
            LENGTH_ITEM_LENGTH + ATTRIBUTE_ITEM_LENGTH + ATTRIBUTE_FILLER_LENGTH;

    private static final int PAYLOAD_WIDTH_TOTAL = 219;

    private static final int SYMBOLIC_MAP_LENGTH = 308;

    private static final int WS_MESSAGE_LENGTH = 80;

    private static final FixedWidthRecord.RecordLayout SYMBOLIC_MAP_LAYOUT = symbolicMapLayout();

    private static final Instant FIXED_INSTANT = Instant.parse("2022-07-19T23:12:33Z");

    private static final String FIXED_CURDATE = "07/19/22";

    private static final String FIXED_CURTIME = "23:12:33";

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

    private static SignOnRequest requestOf(List<String> mapValues, NavigationContext context,
            String aid) {
        return new SignOnRequest(mapValues.get(0), mapValues.get(1), mapValues.get(2),
                mapValues.get(3), mapValues.get(4), mapValues.get(5), mapValues.get(6),
                mapValues.get(7), mapValues.get(8), mapValues.get(9), mapValues.get(10),
                context, aid);
    }

    private static List<String> mapValuesOf(SignOnRequest request) {
        return Arrays.asList(request.trnName(), request.title01(), request.curDate(),
                request.pgmName(), request.title02(), request.curTime(), request.applId(),
                request.sysId(), request.userId(), request.passwd(), request.errMsg());
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

    private static SignOnRequest populatedRequest() {
        List<String> values = Arrays.asList(
                TRANSACTION_ID,
                ScreenTitles.CCDA_TITLE01,
                FIXED_CURDATE,
                PROGRAM_NAME,
                ScreenTitles.CCDA_TITLE02,
                codec().movePicX(FIXED_CURTIME, SignOnRequest.CURTIME_LENGTH),
                "CICSAWS1",
                "AWS1",
                "ADMIN001",
                "NOTAREAL",
                codec().movePicX("Please enter User ID ...", SignOnRequest.ERRMSG_LENGTH));
        return requestOf(values,
                NavigationContext.empty()
                        .withFromTranid(TRANSACTION_ID)
                        .withFromProgram(PROGRAM_NAME)
                        .withUserId("ADMIN001")
                        .withUserTypeAdmin()
                        .withLastMap(MAP_NAME)
                        .withLastMapset(MAPSET_NAME)
                        .withPgmEnter(),
                PfKeyResolver.AidKey.ENTER.token());
    }

    private static Set<ConstraintViolation<SignOnRequest>> validate(SignOnRequest request) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(request);
        }
    }

    private static String serialise(SignOnRequest request) {
        try {
            return webConfigEquivalentMapper().writeValueAsString(request);
        } catch (IOException failure) {
            throw new UncheckedIOException("Serialising a SignOnRequest must not fail", failure);
        }
    }

    private static SignOnRequest deserialise(String json) {
        try {
            return webConfigEquivalentMapper().readValue(json, SignOnRequest.class);
        } catch (IOException failure) {
            throw new UncheckedIOException("Deserialising a SignOnRequest must not fail", failure);
        }
    }

    private static Set<String> jsonMembersOf(SignOnRequest request) {
        try {
            Map<String, Object> tree = webConfigEquivalentMapper()
                    .readValue(serialise(request), new TypeReference<Map<String, Object>>() { });
            return tree.keySet();
        } catch (IOException failure) {
            throw new UncheckedIOException("Reading a serialised SignOnRequest must not fail",
                    failure);
        }
    }

    @Nested
    @DisplayName("Projection of 01 COSGN0AI - eleven map members, in copybook order")
    class MapProjection {
        @Test
        @DisplayName("thirteen components: the eleven map members then the two state members")
        void componentCensus() {
            RecordComponent[] components = SignOnRequest.class.getRecordComponents();
            assertThat(components)
                    .as("eleven name-labelled DFHMDF fields plus navigationContext and aid")
                    .hasSize(COMPONENT_COUNT);

            List<String> names = Arrays.stream(components).map(RecordComponent::getName).toList();
            assertThat(names.subList(0, DFHMDF_NAMED))
                    .as("the map members must appear in 01 COSGN0AI declaration order")
                    .containsExactlyElementsOf(MAP_MEMBERS);
            assertThat(names.subList(DFHMDF_NAMED, COMPONENT_COUNT))
                    .as("the two members with no DFHMDF behind them come last")
                    .containsExactlyElementsOf(STATE_MEMBERS);
        }

        @Test
        @DisplayName("every map member is a String, because every xxxI item is PIC X(n)")
        void everyMapMemberIsCharacter() {
            RecordComponent[] components = SignOnRequest.class.getRecordComponents();
            for (int index = 0; index < DFHMDF_NAMED; index++) {
                assertThat(components[index].getType())
                        .as("%s projects %s, which is PIC X(%d)", MAP_MEMBERS.get(index),
                                SYMBOLIC_MAP_ITEMS.get(index), DECLARED_WIDTHS.get(index))
                        .isEqualTo(String.class);
            }
            assertThat(components[DFHMDF_NAMED].getType())
                    .as("the communication area travels as itself, not as a flattened string")
                    .isEqualTo(NavigationContext.class);
            assertThat(components[DFHMDF_NAMED + 1].getType())
                    .as("the AID token is the five-character CCARD-AID literal")
                    .isEqualTo(String.class);
        }

        @Test
        @DisplayName("MAP_FIELD_COUNT is the mapset's own count, and 26 definitions stay unexposed")
        void countsAreTheMapsetsOwn() {
            assertThat(SignOnRequest.MAP_FIELD_COUNT).isEqualTo(DFHMDF_NAMED);
            assertThat(MAP_MEMBERS).hasSize(SignOnRequest.MAP_FIELD_COUNT);
            assertThat(SYMBOLIC_MAP_ITEMS).hasSize(SignOnRequest.MAP_FIELD_COUNT);
            assertThat(SCREEN_FIELDS).hasSize(SignOnRequest.MAP_FIELD_COUNT);
            assertThat(DECLARED_WIDTHS).hasSize(SignOnRequest.MAP_FIELD_COUNT);
            assertThat(PUBLISHED_WIDTHS).hasSize(SignOnRequest.MAP_FIELD_COUNT);
            assertThat(COPYBOOK_LINES).hasSize(SignOnRequest.MAP_FIELD_COUNT);
            assertThat(MAPSET_LINES).hasSize(SignOnRequest.MAP_FIELD_COUNT);
            assertThat(REDEFINES_LINES).hasSize(SignOnRequest.MAP_FIELD_COUNT);
            assertThat(DFHMDF_TOTAL - DFHMDF_NAMED)
                    .as("the unlabelled DFHMDF definitions are screen furniture, not missing fields")
                    .isEqualTo(26);
        }

        @ParameterizedTest(name = "{0} PIC X({1}) at COSGN00.CPY:{2}")
        @CsvSource({
            "TRNNAMEI,  4, 24",
            "TITLE01I, 40, 30",
            "CURDATEI,  8, 36",
            "PGMNAMEI,  8, 42",
            "TITLE02I, 40, 48",
            "CURTIMEI,  9, 54",
            "APPLIDI,   8, 60",
            "SYSIDI,    8, 66",
            "USERIDI,   8, 72",
            "PASSWDI,   8, 78",
            "ERRMSGI,  78, 84",
        })
        @DisplayName("each published width is the width its xxxI PICTURE clause declares")
        void publishedWidthMatchesTheCopybook(String item, int width, int copybookLine) {
            int index = SYMBOLIC_MAP_ITEMS.indexOf(item);
            assertThat(index).as("%s must be one of the eleven xxxI items", item).isNotNegative();
            assertThat(COPYBOOK_LINES.get(index))
                    .as("%s is declared at COSGN00.CPY:%d", item, copybookLine)
                    .isEqualTo(copybookLine);
            assertThat(DECLARED_WIDTHS.get(index)).isEqualTo(width);
            assertThat(PUBLISHED_WIDTHS.get(index))
                    .as("%s declares PIC X(%d), so %s must publish %d", item, width,
                            MAP_MEMBERS.get(index), width)
                    .isEqualTo(width);
        }

        @ParameterizedTest(name = "{0} LENGTH={1} at COSGN00.bms:{2}")
        @CsvSource({
            "TRNNAME,  4,  34",
            "TITLE01, 40,  38",
            "CURDATE,  8,  47",
            "PGMNAME,  8,  57",
            "TITLE02, 40,  61",
            "CURTIME,  9,  70",
            "APPLID,   8,  80",
            "SYSID,    8,  89",
            "USERID,   8, 156",
            "PASSWD,   8, 175",
            "ERRMSG,  78, 197",
        })
        @DisplayName("the mapset's LENGTH operand agrees independently of the copybook")
        void publishedWidthMatchesTheMapset(String screenField, int length, int mapsetLine) {
            int index = SCREEN_FIELDS.indexOf(screenField);
            assertThat(index).as("%s must be a name-labelled DFHMDF", screenField).isNotNegative();
            assertThat(MAPSET_LINES.get(index))
                    .as("%s is labelled at COSGN00.bms:%d", screenField, mapsetLine)
                    .isEqualTo(mapsetLine);
            assertThat(PUBLISHED_WIDTHS.get(index))
                    .as("two authorities, one width: %s LENGTH=%d", screenField, length)
                    .isEqualTo(length);
        }

        @Test
        @DisplayName("every member traces to one screen field, and its xxxI item is that field plus I")
        void everyMemberTracesToAScreenField() {
            for (int index = 0; index < DFHMDF_NAMED; index++) {
                String screenField = SCREEN_FIELDS.get(index);
                assertThat(SYMBOLIC_MAP_ITEMS.get(index))
                        .as("the symbolic map suffixes the DFHMDF label with I for the input view")
                        .isEqualTo(screenField + "I");
                assertThat(MAP_MEMBERS.get(index).toUpperCase(Locale.ROOT))
                        .as("%s is the Java spelling of %s", MAP_MEMBERS.get(index), screenField)
                        .isEqualTo(screenField);
            }
        }

        @Test
        @DisplayName("the screen is 24 by 80 and its map and mapset names are seven characters")
        void screenIdentity() {
            assertThat(SCREEN_ROWS).isEqualTo(24);
            assertThat(SCREEN_COLUMNS).isEqualTo(80);
            assertThat(MAP_NAME)
                    .as("CDEMO-LAST-MAP is PIC X(7), and COSGN0A is exactly seven characters")
                    .hasSize(NavigationContext.LAST_MAP_LENGTH);
            assertThat(MAPSET_NAME)
                    .as("CDEMO-LAST-MAPSET is PIC X(7), and COSGN00 is exactly seven characters")
                    .hasSize(NavigationContext.LAST_MAPSET_LENGTH);
            assertThat(TRANSACTION_ID).hasSize(SignOnRequest.TRNNAME_LENGTH);
            assertThat(PROGRAM_NAME).hasSize(SignOnRequest.PGMNAME_LENGTH);
        }

        @Test
        @DisplayName("declares no static mutable state, and publishes counts rather than a name list")
        void declaresNoStaticMutableState() {
            for (Field field : SignOnRequest.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("%s is a static field and must be final", field.getName())
                        .isTrue();
                assertThat(field.getType().isPrimitive() || field.getType() == String.class)
                        .as("%s is a static %s; a collection or array constant would be shared "
                                + "mutable state, and this type publishes MAP_FIELD_COUNT instead "
                                + "of a name list", field.getName(), field.getType().getName())
                        .isTrue();
            }
        }
    }

    @Nested
    @DisplayName("Width traps - nine, not eight; seventy-eight, not eighty")
    class WidthTraps {
        @Test
        @DisplayName("curTime is nine characters, and only this screen's is")
        void curTimeIsNineNotEight() {
            assertThat(SignOnRequest.CURTIME_LENGTH)
                    .as("COSGN00.CPY:54 declares CURTIMEI PIC X(9); COUSR00 through COUSR03 all "
                            + "declare X(8), and harmonising this one to eight would shorten the "
                            + "rendered time on the only screen that shows nine")
                    .isEqualTo(9);
            assertThat(CURTIME_INITIAL)
                    .as("COSGN00.bms:74 corroborates with LENGTH=9 and a nine-character INITIAL")
                    .hasSize(SignOnRequest.CURTIME_LENGTH);
            assertThat(SignOnRequest.CURTIME_LENGTH)
                    .as("nine is one more than the eight of every sibling user screen")
                    .isEqualTo(SignOnRequest.CURDATE_LENGTH + 1);
        }

        @Test
        @DisplayName("the eight-character header time pads on the right into the nine-wide field")
        void theHeaderTimeIsPaddedIntoTheNineWideField() {
            DateHeader header = DateHeader.from(codec(), Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
            assertThat(header.wsCurtimeHhMmSs())
                    .as("WS-CURTIME-HH-MM-SS is eight characters, HH:MM:SS")
                    .isEqualTo(FIXED_CURTIME)
                    .hasSize(DateHeader.WS_CURTIME_HH_MM_SS_LENGTH);

            String moved = codec().movePicX(header.wsCurtimeHhMmSs(), SignOnRequest.CURTIME_LENGTH);
            assertThat(moved)
                    .as("COSGN00C.cbl:196 moves eight characters into a nine-character receiver, so "
                            + "COBOL pads one space on the right")
                    .isEqualTo(FIXED_CURTIME + " ")
                    .hasSize(SignOnRequest.CURTIME_LENGTH);
            assertThat(moved.charAt(SignOnRequest.CURTIME_LENGTH - 1)).isEqualTo(' ');
        }

        @Test
        @DisplayName("the eight-character header date fills curDate exactly, with no padding")
        void theHeaderDateFillsCurDateExactly() {
            DateHeader header = DateHeader.from(codec(), Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
            assertThat(header.wsCurdateMmDdYy()).isEqualTo(FIXED_CURDATE);
            assertThat(CURDATE_INITIAL).hasSize(SignOnRequest.CURDATE_LENGTH);
            assertThat(codec().movePicX(header.wsCurdateMmDdYy(), SignOnRequest.CURDATE_LENGTH))
                    .as("eight into eight is neither padded nor truncated")
                    .isEqualTo(FIXED_CURDATE)
                    .hasSize(SignOnRequest.CURDATE_LENGTH);
        }

        @Test
        @DisplayName("errMsg is 78 while WS-MESSAGE is 80, so the move discards two characters")
        void errMsgNarrowsTheEightyByteMessage() {
            assertThat(WS_MESSAGE_LENGTH)
                    .as("COSGN00C.cbl:38 declares WS-MESSAGE PIC X(80)")
                    .isEqualTo(SignOnRequest.ERRMSG_LENGTH + 2);

            String message = "A".repeat(WS_MESSAGE_LENGTH - 2) + "YZ";
            assertThat(message).hasSize(WS_MESSAGE_LENGTH);

            String moved = codec().movePicX(message, SignOnRequest.ERRMSG_LENGTH);
            assertThat(moved)
                    .as("COSGN00C.cbl:149 moves WS-MESSAGE into a PIC X(78) receiver; a COBOL "
                            + "alphanumeric MOVE fills from the left and discards the overflow")
                    .hasSize(SignOnRequest.ERRMSG_LENGTH)
                    .isEqualTo("A".repeat(WS_MESSAGE_LENGTH - 2))
                    .doesNotContain("Y")
                    .doesNotContain("Z");
        }

        @Test
        @DisplayName("a short message is padded on the right, never left-aligned by accident")
        void errMsgPadsAShortMessage() {
            String message = "Please enter User ID ...";
            String moved = codec().movePicX(message, SignOnRequest.ERRMSG_LENGTH);
            assertThat(moved).hasSize(SignOnRequest.ERRMSG_LENGTH).startsWith(message);
            assertThat(moved.substring(message.length()))
                    .as("the remainder of a PIC X receiver is spaces, not nulls and not zeros")
                    .isEqualTo(" ".repeat(SignOnRequest.ERRMSG_LENGTH - message.length()));
        }

        @Test
        @DisplayName("the narrowing instrument truncates on the right, which is the COBOL direction")
        void theMoveTruncatesOnTheRight() {
            assertThat(codec().movePicX("ABCDEF", SignOnRequest.TRNNAME_LENGTH))
                    .as("a PIC X receiver keeps the leading characters; keeping the trailing ones "
                            + "would be the numeric MOVE rule and the wrong one here")
                    .isEqualTo("ABCD");
        }

        @Test
        @DisplayName("applId and sysId are eight, and exist on this screen alone")
        void applIdAndSysIdAreEight() {
            assertThat(SignOnRequest.APPLID_LENGTH).isEqualTo(8);
            assertThat(SignOnRequest.SYSID_LENGTH).isEqualTo(8);
            SignOnRequest echoed = populatedRequest();
            assertThat(echoed.applId()).hasSize(SignOnRequest.APPLID_LENGTH);
            assertThat(deserialise(serialise(echoed)).applId()).isEqualTo(echoed.applId());
            assertThat(deserialise(serialise(echoed)).sysId()).isEqualTo(echoed.sysId());
        }

        @Test
        @DisplayName("title01 and title02 are forty and carry the screen titles byte for byte")
        void titlesAreFortyCharacters() {
            assertThat(SignOnRequest.TITLE01_LENGTH).isEqualTo(ScreenTitles.TITLE_LENGTH);
            assertThat(SignOnRequest.TITLE02_LENGTH).isEqualTo(ScreenTitles.TITLE_LENGTH);
            assertThat(ScreenTitles.CCDA_TITLE01).hasSize(SignOnRequest.TITLE01_LENGTH);
            assertThat(ScreenTitles.CCDA_TITLE02).hasSize(SignOnRequest.TITLE02_LENGTH);

            SignOnRequest request = populatedRequest();
            assertThat(request.title01())
                    .as("COSGN00C.cbl:181 moves CCDA-TITLE01 into the title field")
                    .isEqualTo(ScreenTitles.CCDA_TITLE01);
            assertThat(request.title02())
                    .as("COSGN00C.cbl:182 moves CCDA-TITLE02")
                    .isEqualTo(ScreenTitles.CCDA_TITLE02);
        }

        @Test
        @DisplayName("the two thank-you literals are different texts of different widths")
        void theThankYouLiteralsAreNotInterchangeable() {
            assertThat(ScreenTitles.CCDA_THANK_YOU).hasSize(ScreenTitles.TITLE_LENGTH);
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU).hasSize(SystemMessages.MESSAGE_LENGTH);
            assertThat(SystemMessages.MESSAGE_LENGTH).isNotEqualTo(ScreenTitles.TITLE_LENGTH);
            assertThat(ScreenTitles.CCDA_THANK_YOU.trim())
                    .isNotEqualTo(SystemMessages.CCDA_MSG_THANK_YOU.trim());
        }

        @Test
        @DisplayName("userId and passwd are the widths of the USRSEC record they are compared against")
        void theCredentialWidthsMatchTheSecurityRecord() {
            assertThat(SignOnRequest.USERID_LENGTH)
                    .as("CSUSR01Y.cpy:18 declares SEC-USR-ID PIC X(08), which is what makes the "
                            + "keyed value usable directly as the USRSEC key at COSGN00C.cbl:215-216")
                    .isEqualTo(SEC_USR_ID_LENGTH);
            assertThat(SignOnRequest.PASSWD_LENGTH)
                    .as("CSUSR01Y.cpy:21 declares SEC-USR-PWD PIC X(08)")
                    .isEqualTo(SEC_USR_PWD_LENGTH);
        }
    }

    @Nested
    @DisplayName("Metadata - xxxL, xxxF, xxxA and every FILLER stay off the wire")
    class MetadataIsNotPayload {
        @Test
        @DisplayName("the serialised payload carries exactly the thirteen expected member names")
        void theWireCarriesOnlyTheDeclaredMembers() {
            assertThat(jsonMembersOf(populatedRequest()))
                    .containsExactlyInAnyOrderElementsOf(EXPECTED_JSON_MEMBERS)
                    .hasSize(COMPONENT_COUNT);
        }

        @ParameterizedTest(name = "{0}L, {0}F and {0}A are absent")
        @ValueSource(strings = {"TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02", "CURTIME",
            "APPLID", "SYSID", "USERID", "PASSWD", "ERRMSG"})
        @DisplayName("no length, flag or attribute item appears under any spelling")
        void noMetadataItemIsAMember(String screenField) {
            Set<String> members = jsonMembersOf(populatedRequest());
            int index = SCREEN_FIELDS.indexOf(screenField);
            String member = MAP_MEMBERS.get(index);

            for (String suffix : List.of("L", "F", "A")) {
                String copybookSpelling = screenField + suffix;
                String javaSpelling = member + suffix;
                assertThat(members)
                        .as("%s is metadata: %s is the reported input length, %s the attribute and "
                                + "flag byte, %s its REDEFINES view", copybookSpelling,
                                screenField + "L", screenField + "F", screenField + "A")
                        .doesNotContain(copybookSpelling, javaSpelling,
                                copybookSpelling.toLowerCase(Locale.ROOT),
                                javaSpelling.toLowerCase(Locale.ROOT));
            }
        }

        @Test
        @DisplayName("neither the TIOAPFX prefix nor any per-field filler is exposed")
        void noFillerIsAMember() {
            Set<String> members = jsonMembersOf(populatedRequest());
            assertThat(members).noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("filler"));
            assertThat(members).noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("tioapfx"));
            assertThat(members).noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("prefix"));
            assertThat(TIOAPFX_PREFIX_LENGTH + DFHMDF_NAMED * ATTRIBUTE_FILLER_LENGTH)
                    .as("56 bytes of declared filler in the group, none of it a member")
                    .isEqualTo(56);
        }

        @Test
        @DisplayName("the derived predicates are withheld too, so a payload cannot contradict itself")
        void theDerivedPredicatesAreWithheld() throws NoSuchMethodException {
            Method presence = SignOnRequest.class.getMethod("hasNavigationContext");
            Method length = SignOnRequest.class.getMethod("commareaLength");
            assertThat(annotationNames(presence))
                    .as("a presence flag beside the member it describes could disagree with it")
                    .anyMatch(name -> name.endsWith("JsonIgnore"));
            assertThat(annotationNames(length))
                    .as("EIBCALEN is derived from the member, not carried alongside it")
                    .anyMatch(name -> name.endsWith("JsonIgnore"));

            Set<String> members = jsonMembersOf(populatedRequest());
            assertThat(members).doesNotContain("hasNavigationContext", "commareaLength",
                    "inEnterState", "inReenterState", "enterState", "reenterState");
        }

        @Test
        @DisplayName("the context predicates are not bean getters, so they never become properties")
        void theContextPredicatesAreNotBeanGetters() throws NoSuchMethodException {
            for (String name : List.of("inEnterState", "inReenterState")) {
                Method predicate = SignOnRequest.class.getMethod(name);
                assertThat(predicate.getReturnType()).isEqualTo(boolean.class);
                assertThat(name).doesNotStartWith("is").doesNotStartWith("get");
            }
        }

        private List<String> annotationNames(Method method) {
            List<String> names = new ArrayList<>();
            for (Annotation annotation : method.getAnnotations()) {
                names.add(annotation.annotationType().getName());
            }
            return names;
        }
    }

    @Nested
    @DisplayName("REDEFINES - eleven attribute overlays, each over one shared byte")
    class RedefinesOverlays {
        @Test
        @DisplayName("the layout tiles 308 bytes exactly: 12 + 11 x 7 + 219")
        void theGeometryIsTheCopybooks() {
            assertThat(FIELD_PREFIX_LENGTH)
                    .as("xxxL 2 + xxxF 1 + FILLER X(4) = 7, and the output view's prefix is also 7, "
                            + "which is what lets COSGN0AO overlay COSGN0AI field for field")
                    .isEqualTo(7);
            assertThat(DECLARED_WIDTHS.stream().mapToInt(Integer::intValue).sum())
                    .isEqualTo(PAYLOAD_WIDTH_TOTAL);
            assertThat(TIOAPFX_PREFIX_LENGTH + DFHMDF_NAMED * FIELD_PREFIX_LENGTH
                    + PAYLOAD_WIDTH_TOTAL).isEqualTo(SYMBOLIC_MAP_LENGTH);
            assertThat(SYMBOLIC_MAP_LAYOUT.recordLength()).isEqualTo(SYMBOLIC_MAP_LENGTH);
            assertThat(SYMBOLIC_MAP_LAYOUT.redefinitions())
                    .as("eleven per-field overlays; the group-level one is not modelled here")
                    .hasSize(DFHMDF_NAMED);
            assertThat(SYMBOLIC_MAP_LAYOUT.storageSpans().stream()
                    .mapToInt(FixedWidthRecord.FieldSpan::length).sum())
                    .as("the storage spans, overlays excluded, sum to the record length")
                    .isEqualTo(SYMBOLIC_MAP_LENGTH);
        }

        @ParameterizedTest(name = "{0}A redefines {0}F")
        @ValueSource(strings = {"TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02", "CURTIME",
            "APPLID", "SYSID", "USERID", "PASSWD", "ERRMSG"})
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
            "APPLID", "SYSID", "USERID", "PASSWD", "ERRMSG"})
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
    }

    @Nested
    @DisplayName("Conversation state - the commarea and the AID travel in the payload")
    class ConversationState {
        @Test
        @DisplayName("the communication area is a payload member, so no session is ever needed")
        void theCommareaIsAPayloadMember() {
            assertThat(jsonMembersOf(populatedRequest())).contains("navigationContext");
            assertThat(populatedRequest().navigationContext()).isNotNull();

            for (RecordComponent component : SignOnRequest.class.getRecordComponents()) {
                String type = component.getType().getName();
                assertThat(type)
                        .as("%s is typed %s", component.getName(), type)
                        .doesNotContain("jakarta.servlet")
                        .doesNotContain("javax.servlet")
                        .doesNotContain("HttpSession")
                        .doesNotContain("ThreadLocal")
                        .doesNotContain("org.springframework");
            }
        }

        @Test
        @DisplayName("the commarea is exactly 160 bytes, and the sections account for all of them")
        void theCommareaIsOneHundredAndSixtyBytes() {
            assertThat(NavigationContext.COMMAREA_LENGTH).isEqualTo(160);
            assertThat(NavigationContext.GENERAL_INFO_LENGTH)
                    .as("4 + 8 + 4 + 8 + 8 + 1 + 1")
                    .isEqualTo(34);
            assertThat(NavigationContext.CUSTOMER_INFO_LENGTH).as("9 + 25 + 25 + 25").isEqualTo(84);
            assertThat(NavigationContext.ACCOUNT_INFO_LENGTH).as("11 + 1").isEqualTo(12);
            assertThat(NavigationContext.CARD_INFO_LENGTH).isEqualTo(16);
            assertThat(NavigationContext.MORE_INFO_LENGTH).as("7 + 7").isEqualTo(14);
            assertThat(NavigationContext.GENERAL_INFO_LENGTH + NavigationContext.CUSTOMER_INFO_LENGTH
                    + NavigationContext.ACCOUNT_INFO_LENGTH + NavigationContext.CARD_INFO_LENGTH
                    + NavigationContext.MORE_INFO_LENGTH)
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH);

            byte[] image = populatedRequest().navigationContext().toFixedWidth(codec());
            assertThat(image).hasSize(NavigationContext.COMMAREA_LENGTH);
            assertThat(populatedRequest().commareaLength())
                    .as("EIBCALEN for a request that carries an area")
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH);
        }

        @Test
        @DisplayName("the last map and mapset are seven characters, which the real names corroborate")
        void theMapNamesAreSevenCharacters() {
            assertThat(NavigationContext.LAST_MAP_LENGTH)
                    .as("COCOM01Y.cpy:43 declares CDEMO-LAST-MAP PIC X(7), not X(8)")
                    .isEqualTo(7);
            assertThat(NavigationContext.LAST_MAPSET_LENGTH)
                    .as("COCOM01Y.cpy:44 declares CDEMO-LAST-MAPSET PIC X(7)")
                    .isEqualTo(7);

            NavigationContext context = populatedRequest().navigationContext();
            assertThat(context.lastMap()).isEqualTo(MAP_NAME).hasSize(7);
            assertThat(context.lastMapset()).isEqualTo(MAPSET_NAME).hasSize(7);

            assertThatIllegalArgumentException()
                    .as("an eight-character map name has no representation in PIC X(7)")
                    .isThrownBy(() -> NavigationContext.empty().withLastMap("COSGN0AX"));
        }

        @Test
        @DisplayName("the AID arrives already resolved to its five-character token")
        void theAidIsCarriedAsAResolvedToken() {
            assertThat(SignOnRequest.AID_LENGTH)
                    .as("CCARD-AID is PIC X(5), and the resolver produces exactly that width")
                    .isEqualTo(PfKeyResolver.AID_TOKEN_LENGTH)
                    .isEqualTo(5);

            for (PfKeyResolver.AidKey key : PfKeyResolver.AidKey.values()) {
                assertThat(key.token())
                        .as("%s must be a full-width token; a trimmed one would be the wrong width",
                                key.name())
                        .hasSize(SignOnRequest.AID_LENGTH);
            }
            assertThat(PfKeyResolver.AidKey.PA1.token()).isEqualTo("PA1  ");
            assertThat(PfKeyResolver.AidKey.PA2.token()).isEqualTo("PA2  ");

            assertThat(PfKeyResolver.resolve(CicsAid.DFHENTER))
                    .contains(PfKeyResolver.AidKey.ENTER);
            assertThat(PfKeyResolver.resolve(CicsAid.DFHPF3))
                    .contains(PfKeyResolver.AidKey.PFK03);

            SignOnRequest request = populatedRequest();
            assertThat(request.aid())
                    .isEqualTo(PfKeyResolver.AidKey.ENTER.token())
                    .hasSize(SignOnRequest.AID_LENGTH);
            assertThat(deserialise(serialise(request)).aid()).isEqualTo(request.aid());

            for (RecordComponent component : SignOnRequest.class.getRecordComponents()) {
                assertThat(component.getType())
                        .as("%s must not carry a raw AID byte", component.getName())
                        .isNotEqualTo(byte.class)
                        .isNotEqualTo(Byte.class)
                        .isNotEqualTo(byte[].class);
            }
        }

        @ParameterizedTest(name = "context {0}: enter={1}, reenter={2}")
        @CsvSource({
            "0, true,  false",
            "1, false, true",
            "2, false, false",
            "9, false, false",
        })
        @DisplayName("both 88-level states are driven, in both directions, plus a digit that is neither")
        void bothContextStatesAreDriven(int pgmContext, boolean enter, boolean reenter) {
            SignOnRequest request = requestOf(blankMapValues(),
                    NavigationContext.empty().withPgmContext(pgmContext), "");
            assertThat(request.inEnterState()).isEqualTo(enter);
            assertThat(request.inReenterState()).isEqualTo(reenter);
            assertThat(request.hasNavigationContext()).isTrue();

            assertThat(request.inEnterState())
                    .isEqualTo(request.navigationContext().isEnter());
            assertThat(request.inReenterState())
                    .isEqualTo(request.navigationContext().isReenter());
            assertThat(request.navigationContext().pgmContext()).isEqualTo(pgmContext);
        }

        @Test
        @DisplayName("the named context values are the copybook's, and the enter state survives JSON")
        void theContextStateSurvivesTheRoundTrip() {
            assertThat(NavigationContext.PGM_CONTEXT_ENTER).isEqualTo(0);
            assertThat(NavigationContext.PGM_CONTEXT_REENTER).isEqualTo(1);

            SignOnRequest reentered = requestOf(blankMapValues(),
                    NavigationContext.empty().withPgmReenter(), "");
            SignOnRequest restored = deserialise(serialise(reentered));
            assertThat(restored.inReenterState()).isTrue();
            assertThat(restored.inEnterState()).isFalse();
            assertThat(restored.navigationContext()).isEqualTo(reentered.navigationContext());
        }

        @Test
        @DisplayName("no CDEMO-CU0n-INFO extension block is carried, because COSGN00C declares none")
        void noCustomerInfoExtensionBlockIsCarried() {
            assertThat(SignOnRequest.class.getRecordComponents()).hasSize(COMPONENT_COUNT);
            for (RecordComponent component : SignOnRequest.class.getRecordComponents()) {
                assertThat(component.getName().toLowerCase(Locale.ROOT))
                        .as("%s must not be an extension block", component.getName())
                        .doesNotContain("cu00").doesNotContain("cu01").doesNotContain("cu02")
                        .doesNotContain("cu03").doesNotContain("extension");
            }
            assertThat(populatedRequest().commareaLength())
                    .as("EIBCALEN is the commarea's own width, with no extension behind it")
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH);
        }
    }

    @Nested
    @DisplayName("Validation - @Size maxima only, never a presence or format constraint")
    class ValidationConstraints {
        @Test
        @DisplayName("carries twelve @Size constraints and no other constraint at all")
        void carriesOnlySizeConstraints() {
            int sized = 0;
            for (RecordComponent component : SignOnRequest.class.getRecordComponents()) {
                for (Annotation annotation : component.getAccessor().getAnnotations()) {
                    assertThat(annotation.annotationType().getName())
                            .as("%s carries a constraint COSGN00C does not perform",
                                    component.getName())
                            .doesNotContain("NotBlank")
                            .doesNotContain("NotNull")
                            .doesNotContain("NotEmpty")
                            .doesNotContain("Pattern")
                            .doesNotContain("Email");
                    if (annotation instanceof Size) {
                        sized++;
                    }
                }
            }
            assertThat(sized)
                    .as("the eleven screen fields plus the AID token; the commarea validates itself "
                            + "at construction and needs no annotation")
                    .isEqualTo(DFHMDF_NAMED + 1);
        }

        @Test
        @DisplayName("each @Size(max) is the width its symbolic-map item declares")
        void eachSizeMaximumEqualsTheDeclaredWidth() {
            RecordComponent[] components = SignOnRequest.class.getRecordComponents();
            for (int index = 0; index < DFHMDF_NAMED; index++) {
                Size size = components[index].getAccessor().getAnnotation(Size.class);
                assertThat(size)
                        .as("%s must be width-constrained", components[index].getName())
                        .isNotNull();
                assertThat(size.max())
                        .as("%s is %s PIC X(%d)", components[index].getName(),
                                SYMBOLIC_MAP_ITEMS.get(index), DECLARED_WIDTHS.get(index))
                        .isEqualTo(DECLARED_WIDTHS.get(index));
            }
            assertThat(components[DFHMDF_NAMED])
                    .as("the commarea is not size-constrained; it validates every component itself")
                    .extracting(component -> component.getAccessor().getAnnotation(Size.class))
                    .isNull();
            assertThat(components[DFHMDF_NAMED + 1].getAccessor().getAnnotation(Size.class).max())
                    .isEqualTo(SignOnRequest.AID_LENGTH);
        }

        @Test
        @DisplayName("a wholly blank instance is valid, because the program answers blanks with text")
        void aBlankInstanceIsValid() {
            assertThat(validate(requestOf(blankMapValues(), NavigationContext.empty(), "")))
                    .as("a 400 here would replace 'Please enter User ID ...' with a different "
                            + "observable behaviour, which a like-for-like migration may not do")
                    .isEmpty();
        }

        @Test
        @DisplayName("a null in every position is valid too, so nothing precedes the program's own test")
        void aNullInEveryPositionIsValid() {
            assertThat(validate(requestOf(nullMapValues(), null, null))).isEmpty();
        }

        @ParameterizedTest(name = "{0} refuses {1} + 1 characters")
        @CsvSource({
            "trnName,  4",
            "title01, 40",
            "curDate,  8",
            "pgmName,  8",
            "title02, 40",
            "curTime,  9",
            "applId,   8",
            "sysId,    8",
            "userId,   8",
            "passwd,   8",
            "errMsg,  78",
        })
        @DisplayName("one character over the declared width is exactly one violation, on that member")
        void oneCharacterTooManyIsOneViolation(String member, int width) {
            int index = MAP_MEMBERS.indexOf(member);
            assertThat(index).as("%s must be one of the eleven map members", member).isNotNegative();
            assertThat(width).isEqualTo(PUBLISHED_WIDTHS.get(index));

            List<String> values = blankMapValues();
            values.set(index, "X".repeat(width + 1));
            Set<ConstraintViolation<SignOnRequest>> violations =
                    validate(requestOf(values, NavigationContext.empty(), ""));

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath()).hasToString(member);

            values.set(index, "X".repeat(width));
            assertThat(validate(requestOf(values, NavigationContext.empty(), ""))).isEmpty();
        }

        @Test
        @DisplayName("an over-wide AID token is refused as well, and two failures report as two")
        void theAidTokenIsConstrainedToo() {
            Set<ConstraintViolation<SignOnRequest>> aidOnly =
                    validate(requestOf(blankMapValues(), NavigationContext.empty(), "ENTER!"));
            assertThat(aidOnly).hasSize(1);
            assertThat(aidOnly.iterator().next().getPropertyPath()).hasToString("aid");

            List<String> values = blankMapValues();
            values.set(MAP_MEMBERS.indexOf("passwd"), "OVERWIDEVALUE");
            assertThat(validate(requestOf(values, NavigationContext.empty(), "ENTER!"))).hasSize(2);
        }
    }

    @Nested
    @DisplayName("Serialisation - padding survives, names are untransformed")
    class JsonRoundTrip {
        @Test
        @DisplayName("the mapper this suite uses carries the three settings the module configures")
        void theMapperMatchesTheModuleConfiguration() {
            ObjectMapper mapper = webConfigEquivalentMapper();
            assertThat(mapper.getDeserializationConfig()
                    .isEnabled(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS))
                    .as("a value derived from a PIC S9(p)V99 clause must never route through a double")
                    .isTrue();
            assertThat(mapper.getFactory()
                    .isEnabled(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN))
                    .as("a scale-2 amount serialises as 100.00, never as 1.0E+2")
                    .isTrue();
            assertThat(mapper.getDeserializationConfig()
                    .isEnabled(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT))
                    .as("an empty or all-spaces PIC X(n) value is real screen data, not an absent one; "
                            + "a default mapper would make this suite assert the wrong thing")
                    .isFalse();
        }

        @Test
        @DisplayName("a space-padded payload survives serialise then deserialise byte for byte")
        void spacePaddingSurvivesTheRoundTrip() {
            List<String> values = blankMapValues();
            values.set(MAP_MEMBERS.indexOf("title01"), ScreenTitles.CCDA_TITLE01);
            values.set(MAP_MEMBERS.indexOf("errMsg"), " ".repeat(SignOnRequest.ERRMSG_LENGTH));
            values.set(MAP_MEMBERS.indexOf("curTime"),
                    codec().movePicX(FIXED_CURTIME, SignOnRequest.CURTIME_LENGTH));
            SignOnRequest original = requestOf(values, NavigationContext.empty(), "");

            SignOnRequest restored = deserialise(serialise(original));
            assertThat(restored).isEqualTo(original);
            assertThat(restored.title01())
                    .isEqualTo(ScreenTitles.CCDA_TITLE01)
                    .hasSize(SignOnRequest.TITLE01_LENGTH)
                    .endsWith(" ");
            assertThat(restored.errMsg())
                    .as("78 spaces must come back as 78 spaces, neither trimmed nor nulled")
                    .isEqualTo(" ".repeat(SignOnRequest.ERRMSG_LENGTH))
                    .hasSize(SignOnRequest.ERRMSG_LENGTH);
            assertThat(restored.curTime())
                    .hasSize(SignOnRequest.CURTIME_LENGTH)
                    .endsWith(" ");
        }

        @Test
        @DisplayName("an empty value stays empty and is not coerced to absent")
        void anEmptyValueIsNotCoercedToNull() {
            SignOnRequest restored = deserialise(serialise(
                    requestOf(blankMapValues(), NavigationContext.empty(), "")));
            for (String value : mapValuesOf(restored)) {
                assertThat(value).isNotNull().isEmpty();
            }
            assertThat(restored.aid()).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("the member names are the component names, untransformed")
        void theMemberNamesAreUntransformed() {
            String json = serialise(populatedRequest());
            for (String member : wireNamesOf(MAP_MEMBERS)) {
                assertThat(json)
                        .as("no naming strategy may rename %s, or the 1:1 trace to its xxxI item "
                                + "is lost", member)
                        .contains("\"" + member + "\"");
            }
            for (String member : STATE_MEMBERS) {
                assertThat(json).contains("\"" + member + "\"");
            }
            assertThat(json).doesNotContain("trn_name").doesNotContain("TRNNAMEI")
                    .doesNotContain("cur_time").doesNotContain("err_msg");
        }

        @Test
        @DisplayName("an absent member is emitted rather than dropped, and reads back absent")
        void absentMembersAreEmitted() {
            SignOnRequest nulls = requestOf(nullMapValues(), null, null);
            assertThat(jsonMembersOf(nulls))
                    .as("no inclusion filter may drop a member; a missing name is not the same "
                            + "statement as a null one")
                    .containsExactlyInAnyOrderElementsOf(EXPECTED_JSON_MEMBERS);

            SignOnRequest restored = deserialise(serialise(nulls));
            assertThat(restored).isEqualTo(nulls);
            assertThat(restored.hasNavigationContext()).isFalse();
            assertThat(restored.commareaLength()).isEqualTo(0);
            for (String value : mapValuesOf(restored)) {
                assertThat(value).isNull();
            }
        }
    }

    @Nested
    @DisplayName("Security posture - plaintext as the program compares it, and no more")
    class SecurityPosture {
        @Test
        @DisplayName("passwd is a plaintext String of the width CSUSR01Y declares")
        void thePasswordIsAPlaintextStringOfEight() {
            RecordComponent passwd = SignOnRequest.class.getRecordComponents()[
                    MAP_MEMBERS.indexOf("passwd")];
            assertThat(passwd.getName()).isEqualTo("passwd");
            assertThat(passwd.getType())
                    .as("a String, not a char[], not a wrapper type and not an encoded form")
                    .isEqualTo(String.class);
            assertThat(SignOnRequest.PASSWD_LENGTH).isEqualTo(SEC_USR_PWD_LENGTH);
        }

        @Test
        @DisplayName("an eight-character password survives the round trip byte for byte")
        void anEightCharacterPasswordSurvivesTheRoundTrip() {
            String keyed = "P@d7chr";
            List<String> values = blankMapValues();
            values.set(MAP_MEMBERS.indexOf("passwd"),
                    codec().movePicX(keyed, SignOnRequest.PASSWD_LENGTH));
            SignOnRequest original = requestOf(values, NavigationContext.empty(), "");

            SignOnRequest restored = deserialise(serialise(original));
            assertThat(restored.passwd())
                    .as("the value the program compares must arrive unaltered; any transformation "
                            + "here would change the outcome of COSGN00C.cbl:223")
                    .isEqualTo(original.passwd())
                    .hasSize(SignOnRequest.PASSWD_LENGTH)
                    .startsWith(keyed);
            assertThat(restored).isEqualTo(original);
        }

        @Test
        @DisplayName("the payload does not upper-case, hash or otherwise normalise what it carries")
        void thePayloadIsAPassiveCarrier() {
            List<String> values = blankMapValues();
            values.set(MAP_MEMBERS.indexOf("userId"), "admin001");
            values.set(MAP_MEMBERS.indexOf("passwd"), "lowerpwd");
            SignOnRequest request = requestOf(values, NavigationContext.empty(), "");

            assertThat(request.userId()).isEqualTo("admin001")
                    .isNotEqualTo("admin001".toUpperCase(Locale.ROOT));
            assertThat(request.passwd()).isEqualTo("lowerpwd")
                    .isNotEqualTo("lowerpwd".toUpperCase(Locale.ROOT));
            assertThat(deserialise(serialise(request)).userId()).isEqualTo("admin001");
        }

        @Test
        @DisplayName("no hashing, encoder, token or security framework type is reachable")
        void noSecurityFrameworkTypeIsReachable() {
            List<String> forbidden = List.of("PasswordEncoder", "BCrypt", "MessageDigest", "Hash",
                    "org.springframework.security", "jwt", "Jwt", "Cipher", "SecretKey", "Base64");

            List<String> reachable = new ArrayList<>();
            for (RecordComponent component : SignOnRequest.class.getRecordComponents()) {
                reachable.add(component.getType().getName());
                for (Annotation annotation : component.getAccessor().getAnnotations()) {
                    reachable.add(annotation.annotationType().getName());
                }
            }
            for (Method method : SignOnRequest.class.getDeclaredMethods()) {
                reachable.add(method.getReturnType().getName());
                reachable.add(method.getName());
                for (Class<?> parameter : method.getParameterTypes()) {
                    reachable.add(parameter.getName());
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
            String keyed = "PLAINTXT";
            List<String> values = blankMapValues();
            values.set(MAP_MEMBERS.indexOf("passwd"), keyed);
            SignOnRequest request = requestOf(values, NavigationContext.empty(), "");

            assertThat(request.passwd())
                    .as("the payload carries the keyed characters, unmasked and uncounted")
                    .isEqualTo(keyed);
            assertThat(serialise(request))
                    .as("and they are on the wire, because that is what the program compares")
                    .contains(keyed);
        }

        @Test
        @DisplayName("the diagnostic rendering withholds the password and nothing else")
        void theDiagnosticRenderingWithholdsOnlyThePassword() {
            SignOnRequest request = populatedRequest();
            String rendered = request.toString();

            assertThat(rendered).doesNotContain(request.passwd());
            assertThat(rendered).contains("passwd=");
            assertThat(rendered)
                    .contains(request.trnName())
                    .contains(request.pgmName())
                    .contains(request.userId())
                    .contains(request.applId())
                    .contains(request.sysId());
        }

        @Test
        @DisplayName("equals and hashCode still include the password, because they disclose nothing")
        void valueSemanticsIncludeThePassword() {
            List<String> first = blankMapValues();
            first.set(MAP_MEMBERS.indexOf("passwd"), "NOTREAL1");
            List<String> second = blankMapValues();
            second.set(MAP_MEMBERS.indexOf("passwd"), "NOTREAL2");

            SignOnRequest one = requestOf(first, NavigationContext.empty(), "");
            SignOnRequest other = requestOf(second, NavigationContext.empty(), "");
            assertThat(one).isNotEqualTo(other);
            assertThat(one).isEqualTo(requestOf(first, NavigationContext.empty(), ""));
            assertThat(one).hasSameHashCodeAs(requestOf(first, NavigationContext.empty(), ""));
        }
    }
}
